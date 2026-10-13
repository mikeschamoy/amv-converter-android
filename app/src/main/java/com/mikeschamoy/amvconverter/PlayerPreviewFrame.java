package com.mikeschamoy.amvconverter;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class PlayerPreviewFrame extends FrameLayout {
    public PlayerPreviewFrame(Context c) { super(c); }
    public PlayerPreviewFrame(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * 3f / 4f);
        int exactH = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, exactH);
    }
}
