package com.byd.dlink.customer.hook;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.byd.dlink.customer.utils.PrefsManager;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Rounds the screen by drawing the corners the panel does not have. The display is a rectangle, so
 * the only thing that can read as a rounded corner is a bar of bezel colour outside an arc -- and
 * the two bar bands are exactly the views that already sit in all four corners.
 *
 * The mask goes on as each bar's foreground, which is drawn above its background and its icons
 * without adding a child view, without a window of its own, and without taking any touch: the arc
 * is opaque only where a bar has no business drawing anyway.
 */
public class ScreenCornerHook {

    /** The colour outside the arc: the bezel this panel pretends to have. */
    private static final int BEZEL = Color.BLACK;

    // What each bar carried before we put a mask on it, so standing down gives back exactly that,
    // including the usual case of it having carried nothing.
    private static final Map<View, Drawable> sOriginals =
            Collections.synchronizedMap(new IdentityHashMap<View, Drawable>());

    /**
     * Put the arcs on, or take them off. Both bars are always done together: the four corners of one
     * screen have to agree on the radius, and one bar's layout is as good a moment as any to make
     * the other catch up.
     */
    public static void refresh(View statusBar, View navBar) {
        int radius = PrefsManager.isScreenCornerEnabled()
                ? Math.min(PrefsManager.getScreenCornerRadius(), cap(statusBar, navBar))
                : 0;
        put(statusBar, radius, true);
        put(navBar, radius, false);
    }

    /**
     * The arc has to fit inside the shorter band -- a mask cannot reach past the view carrying it --
     * and a bar SystemUI has not built yet has no height to answer with, so the one that exists
     * decides until the other lays out and both are re-decided together.
     */
    private static int cap(View statusBar, View navBar) {
        int status = statusBar == null ? 0 : Math.max(statusBar.getHeight(), 0);
        int nav = navBar == null ? 0 : Math.max(navBar.getHeight(), 0);
        return status == 0 ? nav : nav == 0 ? status : Math.min(status, nav);
    }

    private static void put(View bar, int radius, boolean top) {
        if (bar == null) {
            return;
        }
        Drawable carried = bar.getForeground();
        CornerMask shown = carried instanceof CornerMask ? (CornerMask) carried : null;
        if (radius <= 0) {
            if (shown != null) {
                bar.setForeground(sOriginals.remove(bar));
            }
            return;
        }
        if (shown != null && shown.shows(radius, top)) {
            return;
        }
        if (shown == null) {
            sOriginals.put(bar, carried);
        }
        bar.setForeground(new CornerMask(radius, top));
    }

    /** The two corners of one band that touch the edge of the screen, filled outside a quarter arc. */
    private static final class CornerMask extends Drawable {

        private final Path mLeft = new Path();
        private final Path mRight = new Path();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mArc = new RectF();
        private final int mRadius;
        private final boolean mTop;

        CornerMask(int radius, boolean top) {
            mRadius = radius;
            mTop = top;
            mPaint.setColor(BEZEL);
        }

        boolean shows(int radius, boolean top) {
            return mRadius == radius && mTop == top;
        }

        /**
         * Built once per size rather than per frame: a band lays out with the screen, and a path
         * rebuilt on every draw would cost the main thread for no visible difference.
         *
         * Each corner is traced as its own closed piece -- edge, arc, edge -- rather than cut out
         * of the band with a boolean path op: {@link Path#op} answers false and leaves the operand
         * untouched when it cannot do the subtraction, and an untouched full rect paints the whole
         * bar black.
         */
        @Override
        protected void onBoundsChange(Rect bounds) {
            mLeft.reset();
            mRight.reset();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= 0) {
                return;
            }
            int r = Math.min(mRadius, height);
            mLeft.moveTo(r, 0);
            mArc.set(0, 0, 2 * r, 2 * r);
            mLeft.arcTo(mArc, 270, -90);
            mLeft.lineTo(0, 0);
            mLeft.close();
            mRight.moveTo(width - r, 0);
            mArc.set(width - 2 * r, 0, width, 2 * r);
            mRight.arcTo(mArc, 270, 90);
            mRight.lineTo(width, 0);
            mRight.close();
        }

        @Override
        public void draw(Canvas canvas) {
            if (mLeft.isEmpty() || mRight.isEmpty()) {
                return;
            }
            if (mTop) {
                canvas.drawPath(mLeft, mPaint);
                canvas.drawPath(mRight, mPaint);
                return;
            }
            // The same paths seen from the other edge, so the arcs land on the bottom two corners.
            canvas.save();
            canvas.translate(0, getBounds().height());
            canvas.scale(1, -1);
            canvas.drawPath(mLeft, mPaint);
            canvas.drawPath(mRight, mPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
