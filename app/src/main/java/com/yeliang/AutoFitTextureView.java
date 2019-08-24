package com.yeliang;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;

/**
 * Author: yeliang
 * Date: 2019/8/23
 * Time: 4:22 PM
 * Description:
 */

public class AutoFitTextureView extends TextureView {

    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    private String TAG_LOG = "===TAG===";

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            return;
        }

        mRatioWidth = width;
        mRatioHeight = height;

        Log.i(TAG_LOG, "mRatioWidth = " + mRatioWidth + ", mRatioHeight = " + mRatioHeight);
        requestLayout();
    }

    /**
     * 重写此方法的目的是让此View的宽高按照预定的宽高比率显示
     * 比如设置宽高比2:1，那么如果实际测量的宽高为300:100，即3:1。那么设置当前View的宽高为200 100
     *
     * @param widthMeasureSpec
     * @param heightMeasureSpec
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        Log.i(TAG_LOG, "width = " + width + ", height = " + height);

        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height);
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
                Log.i(TAG_LOG, "实际设置的宽高为: " + width + ", " + width * mRatioHeight / mRatioWidth);
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
                Log.i(TAG_LOG, "实际设置的宽高为: " + height * mRatioWidth / mRatioHeight + ", " + height);
            }

        }
    }
}
