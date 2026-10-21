package com.mikeschamoy.amvconverter;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ClipAMV seek-safety gate. AMV is indexless: every video/audio pair must be
 * contiguous and intact, including the last pair and the AMV_END_ trailer.
 * This checks the whole byte stream, not merely a few successful FFmpeg seeks.
 * A passed audit cannot certify third-party player firmware behavior.
 */
public final class AMVIntegrity {
    private AMVIntegrity() {}

    public static final class Result {
        public final long videoFrames;
        public final long audioFrames;
        public final int fps;
        public final int audioBlockBytes;
        public final double seconds;
        public final long bytes;

        private Result(long v, long a, int fps, int block, long bytes) {
            videoFrames=v; audioFrames=a; this.fps=fps;
            audioBlockBytes=block; seconds=(double)v/fps; this.bytes=bytes;
        }
        @Override public String toString() {
            return String.format(Locale.US,
                "AMV structural audit PASS: %d video/audio pairs, %.2f sec, %d fps, %d-byte audio blocks, %d bytes (complete ending)",
                videoFrames,seconds,fps,audioBlockBytes,bytes);
        }
    }

    private static String ascii(byte[] b, int off, int n) {
        return new String(b,off,n,StandardCharsets.US_ASCII);
    }
    private static long u32(byte[] b,int off) {
        return ((long)b[off]&255L) | (((long)b[off+1]&255L)<<8)
             | (((long)b[off+2]&255L)<<16) | (((long)b[off+3]&255L)<<24);
    }
    private static IOException bad(String reason,long offset) {
        return new IOException("AMV integrity failure at byte "+offset+": "+reason);
    }

    public static Result inspect(File file,double expectedSeconds) throws IOException {
        if(file==null||!file.isFile())throw new IOException("AMV file not found");
        try(RandomAccessFile f=new RandomAccessFile(file,"r")) {
            long length=f.length();
            if(length<340)throw bad("file too short",0);
            byte[] hdr=new byte[512];
            f.seek(0); f.readFully(hdr);
            if(!"RIFF".equals(ascii(hdr,0,4))||!"AMV ".equals(ascii(hdr,8,4)))
                throw bad("not an AMV RIFF file",0);
            if(u32(hdr,64)!=320||u32(hdr,68)!=240)
                throw bad("expected a 320x240 Gen 2 AMV",64);
            long rate=u32(hdr,72),den=u32(hdr,76);
            if(den==0||rate%den!=0||rate/den<1||rate/den>60)
                throw bad("unsupported AMV frame-rate header",72);
            int fps=(int)(rate/den);

            long start=-1;
            for(int i=0;i+12<=hdr.length;i++){
                if("LIST".equals(ascii(hdr,i,4))&&"movi".equals(ascii(hdr,i+8,4))) {
                    start=i+12;break;
                }
            }
            if(start<0)throw bad("missing movi media section",0);
            f.seek(length-8);byte[] tail=new byte[8];f.readFully(tail);
            if(!"AMV_END_".equals(ascii(tail,0,8)))
                throw bad("missing AMV_END_ trailer (possibly incomplete conversion or copy)",length-8);

            long end=length-8,pos=start,videoFrames=0,audioFrames=0;
            boolean videoNext=true;
            int firstBlock=-1;
            byte[] chunk=new byte[8];
            while(pos<end){
                if(end-pos==1){
                    f.seek(pos);if(f.read()==0){pos=end;break;}
                }
                if(end-pos<8)throw bad("partial packet header",pos);
                f.seek(pos);f.readFully(chunk);
                String type=ascii(chunk,0,4);
                String needed=videoNext?"00dc":"01wb";
                if(!needed.equals(type))
                    throw bad("expected "+needed+" but found "+type+" (out-of-order packet)",pos);
                long payload=u32(chunk,4),remaining=end-pos-8;
                if(payload<4||payload>remaining)
                    throw bad("truncated or invalid "+type+" packet; length="+payload,pos);
                if(videoNext){
                    f.seek(pos+8);
                    if(f.read()!=0xff||f.read()!=0xd8)
                        throw bad("video frame missing JPEG start marker",pos+8);
                    f.seek(pos+8+payload-2);
                    if(f.read()!=0xff||f.read()!=0xd9)
                        throw bad("video frame missing JPEG end marker",pos+8+payload-2);
                    videoFrames++;
                }else{
                    if(firstBlock<0)firstBlock=(int)payload;
                    else if(payload!=firstBlock)
                        throw bad("unequal audio packet sizes ("+payload+" versus "+firstBlock+")",pos);
                    audioFrames++;
                }
                pos+=8+payload; // AMV chunks have no alignment padding, even for odd sizes.
                videoNext=!videoNext;
            }
            if(pos!=end||videoNext==false||videoFrames==0||videoFrames!=audioFrames)
                throw bad("incomplete video/audio pair near end",pos);
            double duration=(double)videoFrames/fps;
            // AMV has no index; a wrong duration header can send the player's
            // seek algorithm beyond the final video/audio pair.
            long headerSeconds=(hdr[84]&255L)+60L*(hdr[85]&255L)+
                3600L*((hdr[86]&255L)|((hdr[87]&255L)<<8));
            if(Math.abs(duration-headerSeconds)>Math.max(3.0,duration*0.03))
                throw bad(String.format(Locale.US,
                    "duration header says %d sec but packets span %.2f sec",headerSeconds,duration),end);
            if(expectedSeconds>0.1 && Math.abs(duration-expectedSeconds)>
                Math.max(2.0,expectedSeconds*0.03))
                throw bad(String.format(Locale.US,
                    "frame count covers %.2f sec, expected %.2f sec",duration,expectedSeconds),end);
            return new Result(videoFrames,audioFrames,fps,firstBlock,length);
        }
    }

    // Runs in Termux or CI via javac/java without Android dependencies.
    public static void main(String[] args) throws Exception {
        if(args.length<1||args.length>2){
            System.err.println("Usage: AMVIntegrity file.amv [expected-duration-seconds]");
            System.exit(2);
        }
        System.out.println(inspect(new File(args[0]),args.length==2?Double.parseDouble(args[1]):0));
    }
}
