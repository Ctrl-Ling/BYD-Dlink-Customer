package com.byd.dlink.customer.hook;

import android.content.res.Resources;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * BYD's own light/dark pair for each bar, which is the only currency its icon colours are written in:
 * the module never supplies an icon colour, it keeps whichever of the ROM's two candidates contrasts
 * better against the band.
 *
 * Which names those are is a per-fork question -- the newer panels name them after the nav bar,
 * Dlink 3.0/4.0 after the air-conditioning readout the keys and the temperature labels share -- so each
 * bar is asked of a list and the first pair found wins. The values are what gets judged, never names.
 */
final class BarInkPalette {

    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String[] NAV_INK_NAMES = {
            "nav_bar_icon_day", "nav_bar_icon_night",
            "byd_nav_bar_temp_color", "byd_nav_bar_temp_color_night",
    };
    private static final String[] STATUS_INK_NAMES = {
            "byd_status_bar_color", "byd_status_bar_color_night",
    };

    // Each bar's {day, night} ink ids, resolved from the name pairs above the first time a walk needs
    // them, and {0, 0} once it is known this fork names neither.
    private static int[] sNavInkPair;
    private static int[] sStatusInkPair;

    // Cache for inkId() results - band values change infrequently
    private static final Map<Integer, Integer> sInkIdCache =
            Collections.synchronizedMap(new WeakHashMap<Integer, Integer>());

    /**
     * @return whichever of BYD's two bar ink colours reads better on {@code band}, or 0 when the pair
     *         cannot be found. The values, not the names, are what is judged, so a ROM that ships the
     *         same colours under different names still decides the same way.
     */
    static int inkId(Resources resources, int band, int bar) {
        Integer cached = sInkIdCache.get(Integer.valueOf(band * 4 + bar));
        if (cached != null) {
            return cached;
        }
        int[] pair = inkPair(resources, bar);
        if (pair[0] == 0 || pair[1] == 0) {
            return 0;
        }
        int day = resources.getColor(pair[0]);
        int night = resources.getColor(pair[1]);
        int result = BarContrast.contrast(band, night) > BarContrast.contrast(band, day)
                ? pair[1] : pair[0];
        sInkIdCache.put(Integer.valueOf(band * 4 + bar), Integer.valueOf(result));
        return result;
    }

    /** @return this fork's {@code {day, night}} ink ids for one bar, or {@code {0, 0}} when it has none. */
    static int[] inkPair(Resources resources, int bar) {
        if (bar == BarViews.NAV) {
            if (sNavInkPair == null) {
                sNavInkPair = resolvePair(resources, NAV_INK_NAMES);
            }
            return sNavInkPair;
        }
        if (sStatusInkPair == null) {
            sStatusInkPair = resolvePair(resources, STATUS_INK_NAMES);
        }
        return sStatusInkPair;
    }

    private static int[] resolvePair(Resources resources, String[] names) {
        for (int i = 0; i + 1 < names.length; i += 2) {
            int day = resources.getIdentifier(names[i], "color", SYSTEM_UI_PACKAGE);
            int night = resources.getIdentifier(names[i + 1], "color", SYSTEM_UI_PACKAGE);
            if (day != 0 && night != 0) {
                return new int[]{day, night};
            }
        }
        return new int[]{0, 0};
    }

    /** A new decision has been taken, so the pairs stay resolved but no band answer may carry over. */
    static void clearCaches() {
        sInkIdCache.clear();
    }

    private BarInkPalette() {
    }
}
