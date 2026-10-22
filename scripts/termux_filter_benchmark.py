#!/usr/bin/env python3
"""Compare two FFmpeg filter graphs using the SAME Termux FFmpeg executable.

This is NOT a benchmark of FFmpegKit inside the Android app. For that, run a
ClipAMV conversion with the matching settings and export app diagnostics,
which now contain the native encode speed and the effective filter.
"""
import csv, json, os, pathlib, shutil, subprocess, sys, tempfile, time

ROOT=pathlib.Path(__file__).resolve().parent.parent

def cmd(args, capture=True):
    return subprocess.run(args,text=True,stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None,check=True)

def main():
    if len(sys.argv)<2 or len(sys.argv)>4:
        sys.exit("Usage: python3 scripts/termux_filter_benchmark.py '/path/to/movie.mkv' [seconds=15] [start=60]")
    src=pathlib.Path(sys.argv[1]).expanduser().resolve()
    if not src.is_file():sys.exit(f"Input not found: {src}")
    seconds=float(sys.argv[2]) if len(sys.argv)>2 else 15
    start=float(sys.argv[3]) if len(sys.argv)>3 else 60
    if not (1<=seconds<=120 and start>=0):sys.exit("Use duration 1-120 sec and nonnegative start")
    for exe in ['ffmpeg','ffprobe','javac','java']:
        if not shutil.which(exe):sys.exit(f"Missing {exe} (in Termux: pkg install ffmpeg openjdk-17)")
    probe=json.loads(cmd(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(src)]).stdout)
    video=next((s for s in probe.get('streams',[]) if s.get('codec_type')=='video'),None)
    audio=next((s for s in probe.get('streams',[]) if s.get('codec_type')=='audio'),None)
    if not video or not audio:sys.exit('This benchmark requires a video with an audio track')
    dur=float(probe.get('format',{}).get('duration') or 0)
    if dur and start+seconds>dur:start=max(0,dur-seconds)
    w,h=video['width'],video['height']
    sar=video.get('sample_aspect_ratio','1:1')
    a,b=(sar.split(':') if ':' in sar else ('1','1'))
    sar_num=float(a)/float(b) if float(b) else 1
    manual="scale=-2:184:flags=lanczos,crop=320:184:x='(iw-ow)/2-32':y=0,pad=320:240:0:28:black"
    if w/h*184 < 320:
        print('NOTICE: this source is too narrow for the legacy 320px crop; use wider cinematic source for this comparison')
        sys.exit(2)
    with tempfile.TemporaryDirectory(prefix='clipamv_bench_') as temp:
        d=pathlib.Path(temp)
        cmd(['javac','-d',str(d),str(ROOT/'app/src/main/java/com/mikeschamoy/amvconverter/PresetEngine.java'),str(ROOT/'tools/ParityPresetCLI.java')])
        native_filter=cmd(['java','-cp',str(d),'com.mikeschamoy.amvconverter.ParityPresetCLI',str(w),str(h),str(sar_num),'184']).stdout.strip()
        results=[]
        for name,vf in [('manual',manual),('app_filter',native_filter)]:
            out=d/(name+'.amv')
            ff=['ffmpeg','-nostdin','-hide_banner','-v','error','-y','-ss',f'{start:.3f}',
                '-i',str(src),'-t',f'{seconds:.3f}','-vf',vf,'-map','0:v:0','-map','0:a:0',
                '-sn','-r','25','-c:v','amv','-c:a','adpcm_ima_amv','-ac','1',
                '-ar','22050','-block_size','882','-f','amv',str(out)]
            print(f'Running {name} filter...')
            t0=time.monotonic()
            p=subprocess.run(ff,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
            elapsed=time.monotonic()-t0
            if p.returncode:sys.exit(f'{name} failed (exit {p.returncode}): {p.stderr[-1200:]}')
            if not out.is_file() or out.stat().st_size<1000:sys.exit(f'{name} produced empty AMV')
            meta=cmd(['ffprobe','-v','error','-show_entries','stream=codec_name,width,height,sample_rate,channels',
                      '-of','default=nw=1',str(out)]).stdout
            for expected in ['codec_name=amv','width=320','height=240','codec_name=adpcm_ima_amv','sample_rate=22050','channels=1']:
                if expected not in meta:sys.exit(f'{name} output validation failed: {expected}')
            results.append((name,round(seconds/elapsed,3),round(elapsed,3),out.stat().st_size,vf))
        report=pathlib.Path.home()/'storage/shared/Download/ClipAMV_Filter_Benchmark.csv'
        report.parent.mkdir(parents=True,exist_ok=True)
        with report.open('w',newline='') as f:
            c=csv.writer(f);c.writerow(['filter','speed_x','elapsed_s','output_bytes','ffmpeg_filter']);c.writerows(results)
        for row in results:print(f'{row[0]}: {row[1]}x, {row[2]}s, {row[3]/1024/1024:.2f} MiB')
        print('Results:',report)
        print('Both were measured in Termux with the SAME FFmpeg executable.')
        print('To compare native ClipAMV vs Termux, match settings and inspect native speed in exported diagnostics.')

if __name__=='__main__':main()
