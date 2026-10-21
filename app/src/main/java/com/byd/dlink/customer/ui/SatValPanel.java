package com.byd.dlink.customer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Saturation along the horizontal axis, brightness down the vertical one, in the hue the picker
 * last chose. Kept as its own view because the gradient is the only way a bar of numbers reads
 * as a colour.
 */
class SatValPanel extends View {

    interface Listener {
        void onPick(float saturation, float value);
    }

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[]{0f, 1f, 1f};

    private Listener listener;
    private Shader saturationShader;
    private Shader valueShader;
    private int shaderWidth;
    private int shaderHeight;

    SatValPanel(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(context, 2f));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setHsv(float[] hsv) {
        this.hsv[0] = hsv[0];
        this.hsv[1] = hsv[1];
        this.hsv[2] = hsv[2];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        if (saturationShader == null || shaderWidth != width || shaderHeight != height) {
            int pureHue = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            saturationShader = new LinearGradient(0, 0, width, 0,
                    0xFFFFFFFF, pureHue, Shader.TileMode.CLAMP);
            valueShader = new LinearGradient(0, 0, 0, height,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            shaderWidth = width;
            shaderHeight = height;
        }
        background.setShader(saturationShader);
        canvas.drawRect(0, 0, width, height, background);
        background.setShader(valueShader);
        canvas.drawRect(0, 0, width, height, background);

        float radius = Math.min(width, height) * 0.06f;
        // A marker that sits exactly on a corner would be drawn half outside the panel.
        float x = Math.max(radius, Math.min(width - radius, hsv[1] * width));
        float y = Math.max(radius, Math.min(height - radius, (1f - hsv[2]) * height));
        // The panel's own pixels are what the ring has to stand out from, so it is drawn twice.
        ring.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, y, radius, ring);
        ring.setColor(hsv[2] < 0.5f ? 0xFFFFFFFF : 0xFF000000);
        canvas.drawCircle(x, y, radius * 0.7f, ring);
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
        float saturation = clamp(event.getX() / Math.max(1, getWidth()));
        float value = 1f - clamp(event.getY() / Math.max(1, getHeight()));
        hsv[1] = saturation;
        hsv[2] = value;
        invalidate();
        if (listener != null) {
            listener.onPick(saturation, value);
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
