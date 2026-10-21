package com.mikeschamoy.amvconverter;
import java.io.*;
import java.nio.file.*;

public final class AMVIntegrityRegression {
    private static void four(OutputStream os,String s)throws IOException{
        os.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private static void word(OutputStream os,int value)throws IOException{
        for(int n=0;n<4;n++)os.write((value>>>(n*8))&255);
    }
    private static void word(byte[] b,int off,int value){
        for(int n=0;n<4;n++)b[off+n]=(byte)((value>>>(8*n))&255);
    }
    private static void shouldFail(File f,String message)throws Exception{
        try{AMVIntegrity.inspect(f,1.0);throw new AssertionError(message+" unexpectedly passed");}
        catch(IOException expected){System.out.println("PASS rejected "+message+": "+expected.getMessage());}
    }
    public static void main(String[]args)throws Exception{
        Path temp=Files.createTempFile("clipamv-qa-",".amv");
        try{
            try(OutputStream os=Files.newOutputStream(temp)){
                byte[] header=new byte[316];
                byte[] r="RIFF".getBytes("US-ASCII");System.arraycopy(r,0,header,0,4);
                r="AMV ".getBytes("US-ASCII");System.arraycopy(r,0,header,8,4);
                r="LIST".getBytes("US-ASCII");System.arraycopy(r,0,header,304,4);
                r="movi".getBytes("US-ASCII");System.arraycopy(r,0,header,312,4);
                word(header,64,320);word(header,68,240);word(header,72,30);word(header,76,1);
                header[84]=1; // one second, 30 video/audio pairs
                os.write(header);
                for(int i=0;i<30;i++){
                    four(os,"00dc");word(os,4);os.write(new byte[]{-1,-40,-1,-39});
                    four(os,"01wb");word(os,376);os.write(new byte[376]);
                }
                four(os,"AMV_");four(os,"END_");
            }
            File file=temp.toFile();
            AMVIntegrity.Result good=AMVIntegrity.inspect(file,1.0);
            if(good.videoFrames!=30||good.audioFrames!=30)throw new AssertionError("frame count");
            System.out.println("PASS complete AMV: "+good);
            try(RandomAccessFile r=new RandomAccessFile(file,"rw")){
                r.seek(r.length()-8);r.write("BAD_END_".getBytes("US-ASCII"));
            }
            shouldFail(file,"damaged footer");
            try(RandomAccessFile r=new RandomAccessFile(file,"rw")){
                r.seek(r.length()-8);r.write("AMV_END_".getBytes("US-ASCII"));
                r.setLength(r.length()-120);
            }
            shouldFail(file,"truncated final packet");
        }finally{Files.deleteIfExists(temp);}
    }
}
