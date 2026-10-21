package com.byd.dlink.customer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * A horizontal gradient with a marker that slides along it. Hue and alpha are the same gesture over
 * different colour stops, so they share this instead of each carrying their own touch handling.
 */
class GradientBar extends View {

    interface Listener {
        void onSlide(int bar, float position);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bar;
    private Listener listener;

    private int[] colors = new int[]{0xFFFFFFFF};
    private LinearGradient shader;
    private float position;

    GradientBar(Context context, int bar, Listener listener) {
        super(context);
        this.bar = bar;
        this.listener = listener;
        marker.setStyle(Paint.Style.STROKE);
        marker.setStrokeWidth(dp(context, 2f));
    }

    void setColors(int[] colors) {
        this.colors = colors;
        shader = null;
        invalidate();
    }

    void setPosition(float position) {
        this.position = clamp(position);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        if (shader == null) {
            shader = new LinearGradient(0, 0, width, 0, colors, null, Shader.TileMode.CLAMP);
            fill.setShader(shader);
        }
        canvas.drawRect(0, 0, width, height, fill);
        float radius = height * 0.42f;
        // The end stops have to stay visible, so the marker stops short of the edges.
        float x = radius + position * (width - 2 * radius);
        marker.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, height / 2f, radius, marker);
        marker.setColor(0xFF000000);
        canvas.drawCircle(x, height / 2f, radius * 0.7f, marker);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP) {
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        position = clamp(event.getX() / Math.max(1, getWidth()));
        invalidate();
        if (listener != null) {
            listener.onSlide(bar, position);
        }
        return true;
    }

    private static float clamp(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }
}
