package com.buhanzhe.webviewupdater;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Keeps the single-column phone layout readable on tablets and televisions. */
public final class MaxWidthLinearLayout extends LinearLayout {
    public MaxWidthLinearLayout(Context context) {
        super(context);
    }

    public MaxWidthLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxWidthLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.page_max_width);
        int available = MeasureSpec.getSize(widthMeasureSpec);
        if (available > maxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
