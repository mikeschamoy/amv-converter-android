package com.mikeschamoy.amvconverter;
import java.util.*;
public final class QueuePausePolicyRegression {
    private static void yes(boolean v,String msg){if(!v)throw new AssertionError(msg);}
    public static void main(String[] args){
        yes(QueuePausePolicy.shouldStart(0,false,true),"pause before first file allows file 0");
        yes(!QueuePausePolicy.shouldStart(1,false,true),"pause prevents next file");
        yes(QueuePausePolicy.shouldStart(7,false,false),"normal queue continues");
        yes(!QueuePausePolicy.shouldStart(0,true,false),"cancel stops first file");
        yes(QueuePausePolicy.hasRemaining(8,1,false,true),"save seven remaining");
        yes(!QueuePausePolicy.hasRemaining(8,8,false,true),"no phantom paused job");
        yes(!QueuePausePolicy.hasRemaining(8,1,true,true),"cancel is not pause");
        List<String> inputs=Arrays.asList("e1","e2","e3");
        yes(QueuePausePolicy.remaining(inputs,1).equals(Arrays.asList("e2","e3")),"skip completed");
        yes(QueuePausePolicy.remaining(inputs,3).isEmpty(),"all completed");
        yes(QueuePausePolicy.remaining(inputs,100).isEmpty(),"clamp too large");
        yes(QueuePausePolicy.remaining(inputs,-1).equals(inputs),"clamp negative");
        System.out.println("PASS queue pause policy: 11 boundary and resume cases");
    }
}
