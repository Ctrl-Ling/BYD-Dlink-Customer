package com.byd.dlink.customer.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.byd.dlink.customer.utils.PrefsManager;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Rounds the screen by drawing the corners the panel does not have. The display is a rectangle, so
 * the only thing that can read as a rounded corner is a bar of bezel black outside an arc -- and
 * the two bar bands are exactly the views that already sit in all four corners.
 *
 * The mask goes on as each bar's foreground, which is drawn above its background and its icons
 * without adding a child view, without a window of its own, and without taking any touch: the arc
 * is opaque only where a bar has no business drawing anyway.
 */
public class ScreenCornerHook {

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

    /**
     * The two corners of one band that touch the edge of the screen, filled outside the corner arc.
     *
     * The arc is a pre-computed bitmap rather than a filled path: a hardware canvas turns a path
     * into chords before filling it, and at the radii this panel can carry -- tens of pixels across
     * -- the chords are what the eye catches. Sampling the coverage once, when the radius changes,
     * gives every pixel its exact share of bezel and leaves nothing for the tessellator to guess at.
     */
    private static final class CornerMask extends Drawable {

        /** Per axis, so a pixel's coverage is known to better than one grey level. */
        private static final int SAMPLES = 8;

        /** 2 is a circle; 4 flattens the arc against both screen edges, which is the look he picked. */
        private static final double EXPONENT = 4;

        private final Bitmap mCorner;
        private final Paint mPaint = new Paint();
        private final Matrix mMirror = new Matrix();
        private final int mRadius;
        private final boolean mTop;

        CornerMask(int radius, boolean top) {
            mRadius = radius;
            mTop = top;
            mCorner = build(radius);
        }

        boolean shows(int radius, boolean top) {
            return mRadius == radius && mTop == top;
        }

        /**
         * One corner, the top-left, as the fraction of each pixel that falls outside the arc. The
         * other three are this one mirrored, which is why a single bitmap covers the whole screen
         * and why the arc stays tangent to both screen edges however the bars are laid out.
         *
         * The last pass averages each pixel with its neighbours, which is what carries the end of
         * the arc out to nothing instead of leaving it to stop on a pixel boundary.
         */
        private static Bitmap build(int radius) {
            double[] coverage = sample(radius);
            int[] pixels = new int[radius * radius];
            for (int y = 0; y < radius; y++) {
                for (int x = 0; x < radius; x++) {
                    double sum = 0;
                    for (int j = -1; j <= 1; j++) {
                        for (int i = -1; i <= 1; i++) {
                            sum += coverage[edge(coverage, radius, y + j, x + i)];
                        }
                    }
                    pixels[y * radius + x] = (int) Math.round(sum * 255 / 9) << 24;
                }
            }
            return Bitmap.createBitmap(pixels, radius, radius, Bitmap.Config.ARGB_8888);
        }

        /** How much of each pixel lies outside the arc through it. */
        private static double[] sample(int radius) {
            double[] coverage = new double[radius * radius];
            double r = radius, corner = Math.pow(radius, EXPONENT);
            for (int y = 0; y < radius; y++) {
                for (int x = 0; x < radius; x++) {
                    int outside = 0;
                    for (int j = 0; j < SAMPLES; j++) {
                        double dy = Math.pow(r - (y + (j + 0.5) / SAMPLES), EXPONENT);
                        for (int i = 0; i < SAMPLES; i++) {
                            if (Math.pow(r - (x + (i + 0.5) / SAMPLES), EXPONENT) + dy > corner) {
                                outside++;
                            }
                        }
                    }
                    coverage[y * radius + x] = (double) outside / (SAMPLES * SAMPLES);
                }
            }
            return coverage;
        }

        private static int edge(double[] coverage, int radius, int y, int x) {
            int row = y < 0 ? 0 : y >= radius ? radius - 1 : y;
            int column = x < 0 ? 0 : x >= radius ? radius - 1 : x;
            return row * radius + column;
        }

        @Override
        public void draw(Canvas canvas) {
            int width = getBounds().width();
            int height = getBounds().height();
            canvas.save();
            if (mTop) {
                canvas.drawBitmap(mCorner, 0, 0, mPaint);
            } else {
                // The same corner seen from the other edge, so the arcs land on the bottom two.
                canvas.translate(0, height);
                canvas.scale(1, -1);
                canvas.drawBitmap(mCorner, 0, 0, mPaint);
            }
            mMirror.reset();
            mMirror.postTranslate(width, 0);
            mMirror.preScale(-1, 1);
            canvas.drawBitmap(mCorner, mMirror, mPaint);
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
