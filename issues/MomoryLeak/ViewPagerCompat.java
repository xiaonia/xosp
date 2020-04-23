package com.xosp.hwademo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * @author xuqingqi01@gmail.com
 * 兼容处理 Android5.0-6.0系统，由于硬件加速引起的内存泄漏的问题
 */
public class ViewPagerCompat extends ViewPager {

    private static final String TAG = "ViewPagerCompat";

    private static final boolean TRICK_ENABLED = false;

    private static final int PFLAG2_VIEW_QUICK_REJECTED_COPY = 0x10000000; //View.PFLAG2_VIEW_QUICK_REJECTED

    private static final int LOLLIPOP = 21; // Build.VERSION_CODES.LOLLIPOP

    private static final int MARSHMALLOW = 23; // Build.VERSION_CODES.M

    public ViewPagerCompat(Context context) {
        super(context);
    }

    public ViewPagerCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final boolean more = super.drawChild(canvas, child, drawingTime);
        if (!TRICK_ENABLED) {
            return more;
        }

        if (Build.VERSION.SDK_INT >= LOLLIPOP && Build.VERSION.SDK_INT <= MARSHMALLOW
                && canvas.isHardwareAccelerated() && child.isHardwareAccelerated()
                && isViewQuickRejected(child)
        ) {
            resetDisplayList(child);
        }

        return more;
    }

    /**
     * check whether the view failed the quickReject() check in draw()
     */
    private static boolean isViewQuickRejected(@NonNull View view) {
        try {
            Field field = View.class.getDeclaredField("mPrivateFlags2");
            field.setAccessible(true);
            int flags = (int) field.get(view);
            return (flags & PFLAG2_VIEW_QUICK_REJECTED_COPY ) == PFLAG2_VIEW_QUICK_REJECTED_COPY;
        } catch (Exception ignore) {
            //ignore.printStackTrace();
        }
        return false;
    }

    /**
     * release display list data
     */
    @SuppressLint("PrivateApi")
    private static void resetDisplayList(@NonNull View view) {
        Log.d(TAG, "resetDisplayList, view=" + view);
        try {
            Method method = View.class.getDeclaredMethod("resetDisplayList");
            method.setAccessible(true);
            method.invoke(view);
        } catch (Exception ignore) {
            //ignore.printStackTrace();
        }
    }

}
