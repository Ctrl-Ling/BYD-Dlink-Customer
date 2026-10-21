package com.byd.dlink.customer.hook;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.Display;
import android.view.SurfaceControl;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Reads back what is actually on the display, so a bar can be painted the colour of the screen edge
 * it sits against.
 *
 * The way in is SurfaceControl.screenshot(Rect, int, int, int) -- the call BYD's own GaussianUtil
 * makes inside this SystemUI to blur the screen behind its scene modes, so it is a capture this ROM
 * already performs here. The newer captureLayers is present too but answers null: this SystemUI runs
 * as an application uid, and SurfaceFlinger will not hand one of those a display.
 */
public final class ScreenEdgeSampler {

    private static final String TAG = "BYD Dlink Customer";

    private ScreenEdgeSampler() {
    }

    /**
     * The colour that fills the strip given, i.e. what the screen actually looks like along that row.
     *
     * @return an opaque ARGB colour, or null when the display cannot be read at all
     */
    public static Integer dominantColour(int left, int top, int right, int bottom) {
        Bitmap bitmap = capture(left, top, right, bottom);
        if (bitmap == null) {
            return null;
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            Map<Integer, Integer> counts = new HashMap<>();
            int best = 0;
            int bestCount = -1;
            for (int pixel : pixels) {
                int colour = pixel | 0xFF000000;
                Integer count = counts.get(colour);
                int seen = (count == null ? 0 : count.intValue()) + 1;
                counts.put(colour, seen);
                if (seen > bestCount) {
                    best = colour;
                    bestCount = seen;
                }
            }
            return bestCount > 0 ? Integer.valueOf(best) : null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": edge pixels unreadable: " + t);
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * A screenshot of the region given, whose corners are display pixels. The framework scales the
     * crop to the size asked for, so that size is asked for exactly and nothing is read back that
     * was not on the strip.
     */
    private static Bitmap capture(int left, int top, int right, int bottom) {
        try {
            int rotation = rotation();
            if (rotation < 0) {
                return null;
            }
            Bitmap bitmap = (Bitmap) XposedHelpers.callStaticMethod(SurfaceControl.class, "screenshot",
                    new Rect(left, top, right, bottom), right - left, bottom - top, rotation);
            if (bitmap == null) {
                return null;
            }
            // A capture can come back as a hardware bitmap, which will not answer getPixels.
            return bitmap.getConfig() == Bitmap.Config.HARDWARE
                    ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": edge capture failed: " + t);
            return null;
        }
    }

    /** The rotation the display is composited at, or -1 when it cannot be read. */
    private static int rotation() {
        try {
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object instance = XposedHelpers.callStaticMethod(global, "getInstance");
            Display display = (Display) XposedHelpers.callMethod(instance,
                    "getRealDisplay", Display.DEFAULT_DISPLAY);
            return display == null ? -1 : display.getRotation();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": display rotation unreadable: " + t);
            return -1;
        }
    }
}
