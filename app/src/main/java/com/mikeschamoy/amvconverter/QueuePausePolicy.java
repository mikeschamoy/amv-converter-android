package com.mikeschamoy.amvconverter;

import java.util.ArrayList;
import java.util.List;

/** Process a selected queue only up to a fully completed file boundary. */
public final class QueuePausePolicy {
    private QueuePausePolicy(){}
    public static boolean shouldStart(int index,boolean cancelRequested,boolean pauseRequested){
        return !cancelRequested&&(!pauseRequested||index==0);
    }
    public static boolean hasRemaining(int total,int processed,boolean cancelRequested,boolean pauseRequested){
        return pauseRequested&&!cancelRequested&&processed<total;
    }
    public static <T> ArrayList<T> remaining(List<T> selected,int processed){
        int safe=Math.max(0,Math.min(selected.size(),processed));
        return new ArrayList<>(selected.subList(safe,selected.size()));
    }
}
