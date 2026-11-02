package com.byd.dlink.customer.hook;

import android.graphics.Color;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The colour maths behind every icon decision, and nothing else: what reads as bar ink rather than as
 * one of BYD's meaning-carrying accents, what two colours contrast to, and what a translucent band
 * actually looks like to the eye. Keeping it here is what lets the walk, the tint and the setter hook
 * reach the same answer for the same band.
 */
final class BarContrast {

    /**
     * How far a colour may stray from grey and still count as bar ink. BYD's own icon greys sit well
     * below this ({@code #262d33} spans 13, {@code #343a3e} spans 10) while the colours that carry
     * meaning -- the blue of an active nav item, the HI/LO temperature accents -- span over 100.
     */
    private static final int MAX_INK_SPREAD = 0x40;

    /**
     * Below this the band is mostly whatever is behind it, and no colour we can name is what the
     * user is looking at -- so the ROM's own day/night signal is left alone.
     */
    private static final int MIN_BAND_ALPHA = 0x66;
    private static final int NEUTRAL_BACKDROP = 0xFF808080;

    // Cache for bandToJudge() results - painted colors change infrequently
    private static final Map<Integer, Integer> sBandJudgeCache =
            Collections.synchronizedMap(new WeakHashMap<Integer, Integer>());

    /** Whether {@code colour} is bar ink rather than one of BYD's meaning-carrying accents. */
    static boolean isInk(int colour) {
        int red = Color.red(colour), green = Color.green(colour), blue = Color.blue(colour);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue))
                <= MAX_INK_SPREAD;
    }

    /**
     * The colour an icon has to be readable against, or null when there is nothing to judge. What we
     * paint is only half of it: a translucent band lets the app behind it show through, so the colour
     * is judged as the blend the eye sees rather than as the value that was set.
     */
    static Integer bandToJudge(Integer painted) {
        if (painted == null) {
            return null;
        }
        Integer cached = sBandJudgeCache.get(painted);
        if (cached != null) {
            return cached;
        }
        if (Color.alpha(painted.intValue()) < MIN_BAND_ALPHA) {
            return null;
        }
        int configured = painted.intValue();
        float fraction = Color.alpha(configured) / 255f;
        int red = (int) (fraction * Color.red(configured)
                + (1f - fraction) * Color.red(NEUTRAL_BACKDROP) + 0.5f);
        int green = (int) (fraction * Color.green(configured)
                + (1f - fraction) * Color.green(NEUTRAL_BACKDROP) + 0.5f);
        int blue = (int) (fraction * Color.blue(configured)
                + (1f - fraction) * Color.blue(NEUTRAL_BACKDROP) + 0.5f);
        int result = Color.rgb(red, green, blue);
        sBandJudgeCache.put(painted, result);
        return result;
    }

    /**
     * @return the colour to use instead of {@code current}, or null if {@code current} reads better.
     *         The alpha BYD asked with is kept, so a deliberately dimmed icon stays dimmed, only in
     *         the right colour.
     */
    static Integer betterInk(int current, int band, int target) {
        if (current == 0 || !isInk(current)
                || contrast(band, current) >= contrast(band, target)) {
            return null;
        }
        return Color.argb(Color.alpha(current), Color.red(target), Color.green(target),
                Color.blue(target));
    }

    static float contrast(int background, int foreground) {
        float light = Math.max(luminance(background), luminance(foreground));
        float dark = Math.min(luminance(background), luminance(foreground));
        return (light + 0.05f) / (dark + 0.05f);
    }

    private static float luminance(int color) {
        return 0.2126f * channel(Color.red(color))
                + 0.7152f * channel(Color.green(color))
                + 0.0722f * channel(Color.blue(color));
    }

    private static float channel(int value) {
        float linear = value / 255f;
        return linear <= 0.03928f ? linear / 12.92f : (float) Math.pow((linear + 0.055f) / 1.055f, 2.4f);
    }

    /** A new decision has been taken, so nothing remembered about a band may carry over. */
    static void clearCaches() {
        sBandJudgeCache.clear();
    }

    private BarContrast() {
    }
}
