package com.mikeschamoy.amvconverter;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
public class PlayerPreviewFrame extends FrameLayout {
    public PlayerPreviewFrame(Context c){super(c);}
    public PlayerPreviewFrame(Context c, AttributeSet a){super(c,a);}
    @Override protected void onMeasure(int wSpec,int hSpec){
        int width=MeasureSpec.getSize(wSpec);
        super.onMeasure(wSpec,MeasureSpec.makeMeasureSpec(Math.round(width*3f/4f),MeasureSpec.EXACTLY));
    }
}
