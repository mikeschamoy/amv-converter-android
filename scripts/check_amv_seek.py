#!/usr/bin/env python3
"""ClipAMV seek QA: validate AMV packet pairs AND decode late seek positions.

Usage: python scripts/check_amv_seek.py path.amv [--deep]
The file format is intentionally indexless. Passing this test does not certify
any proprietary player firmware; the MP3 player must still be tested.
"""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import sys


def fail(msg):
    raise ValueError(msg)


def structure(path):
    with path.open('rb') as f:
        size=path.stat().st_size
        if size<352:fail('file too short')
        header=f.read(min(size,4096))
        if header[:4]!=b'RIFF' or header[8:12]!=b'AMV ':fail('not RIFF AMV')
        if header[64:68]!=struct.pack('<I',320) or header[68:72]!=struct.pack('<I',240):
            fail('expected 320x240 AMV')
        num,den=struct.unpack_from('<II',header,72)
        if not den or num%den or not 1<=num//den<=60:fail('bad AMV FPS header')
        fps=num//den
        movi=header.find(b'LIST\x00\x00\x00\x00movi')
        if movi<0:fail('movi marker missing')
        pos=movi+12
        f.seek(size-8)
        if f.read(8)!=b'AMV_END_':fail('AMV_END_ trailer missing')
        end=size-8
        count=audio=0
        audio_block=None
        while pos<end:
            if end-pos==1:
                f.seek(pos)
                if f.read(1)==b'\x00':
                    pos+=1
                    break
            if end-pos<8:fail(f'incomplete chunk header at byte {pos}')
            f.seek(pos)
            tag=f.read(4)
            length=struct.unpack('<I',f.read(4))[0]
            expected=b'00dc' if count==audio else b'01wb'
            if tag!=expected:fail(f'packet order at {pos}: expected {expected!r}, found {tag!r}')
            if length<4 or length>end-pos-8:fail(f'truncated packet at byte {pos}')
            if tag==b'00dc':
                f.seek(pos+8)
                if f.read(2)!=b'\xff\xd8':fail(f'JPEG start invalid at {pos}')
                f.seek(pos+8+length-2)
                if f.read(2)!=b'\xff\xd9':fail(f'JPEG ending invalid at {pos}')
                count+=1
            else:
                if audio_block is None:audio_block=length
                elif audio_block!=length:fail(f'audio block length changed at byte {pos}')
                audio+=1
            pos+=8+length  # AMV packets are not padded individually.
        if pos!=end or count==0 or count!=audio:
            fail(f'incomplete video/audio pairs: {count}/{audio} at {pos} of {end}')
        declared=header[84]+60*header[85]+3600*int.from_bytes(header[86:88],'little')
        duration=count/fps
        if abs(duration-declared)>max(3,duration*.03):
            fail(f'header duration {declared}s != frame duration {duration:.2f}s')
        return fps,count,duration,audio_block


def probe_frame(path,seconds,timeout=180):
    cmd=['ffmpeg','-nostdin','-v','error','-ss',f'{seconds:.3f}', '-i',str(path),
         '-map','0:v:0','-frames:v','1','-an','-f','framecrc','-']
    proc=subprocess.run(cmd,text=True,stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,timeout=timeout)
    if proc.returncode!=0:fail(f'FFmpeg seek failed at {seconds:.2f}s: {proc.stderr[-250:]}')
    if not re.search(r'^0,\s*[-\d]+,',proc.stdout,re.M):
        fail(f'FFmpeg produced ZERO video frames at {seconds:.2f}s')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('video',type=Path)
    parser.add_argument('--deep',action='store_true',help='full decode after seeking tests')
    args=parser.parse_args()
    if not args.video.is_file():fail('file not found')
    fps,frames,duration,block=structure(args.video)
    print(f'PASS structure: {frames} video/audio pairs; {duration:.2f}s; {fps} fps; audio blocks {block} B')
    fractions=(.1,.5,.75,.9,.95,.98) if args.deep else (.5,.9,.95,.98)
    positions=sorted({round(min(duration*f,duration-.75),3) for f in fractions}
                     | {round(max(0,duration-1.5),3)})
    for pos in positions:
        if pos<0:continue
        probe_frame(args.video,pos)
        print(f'PASS decoded frame at {pos:.2f}s ({pos*100/duration:.1f}%)')
    if args.deep:
        print('Deep decoding every video/audio frame...')
        proc=subprocess.run(['ffmpeg','-nostdin','-v','error','-xerror','-i',
            str(args.video),'-f','null','-'],timeout=1800)
        if proc.returncode!=0:fail('full decode failed')
        print('PASS full decode')
    print('PASS file-level seek QA. Confirm the final minutes on the physical Gen 2 player.')

if __name__=='__main__':
    try:main()
    except (ValueError, subprocess.TimeoutExpired, OSError) as e:
        print('FAIL:',e,file=sys.stderr)
        sys.exit(1)
