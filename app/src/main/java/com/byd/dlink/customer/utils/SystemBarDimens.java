package com.byd.dlink.customer.utils;

import android.content.res.Resources;

public final class SystemBarDimens {

    // These are matched by resource *entry name*, not by id, in SystemBarHeightHook. Matching by
    // name is what catches the copies inside SystemUI itself: aapt resolves a dimen that just
    // aliases the framework one at table level, so asking SystemUI for its
    // dimen/status_bar_height never calls getDimensionPixelSize with the framework id, and the
    // bar content kept its stock 84/90 while only the window frame moved.
    public static final String[] STATUS_BAR = {
            "status_bar_height_landscape",
            "status_bar_height",
            "status_bar_height_portrait",
    };

    public static final String[] NAV_BAR = {
            "navigation_bar_height_landscape",
            "navigation_bar_height",
            "navigation_bar_height_portrait",
            "navigation_bar_height_car_mode",
            "navigation_bar_size",
            "navigation_bar_frame_height_landscape",
            "navigation_bar_frame_height",
            "navigation_bar_frame_height_portrait",
    };

    private SystemBarDimens() {
    }

    /**
     * Current value of a bar as the ROM sees it, framework namespace. Names are tried in order,
     * so the orientation-specific one wins and is the one shown as "current".
     */
    public static int readHeightPx(String[] names) {
        for (int i = 0; i < names.length; i++) {
            int id = idOf(names[i]);
            if (id == 0) continue;
            int px = readPx(id);
            if (px > 0) return px;
        }
        return 0;
    }

    private static int idOf(String name) {
        try {
            return Resources.getSystem().getIdentifier(name, "dimen", "android");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int readPx(int id) {
        try {
            return Resources.getSystem().getDimensionPixelSize(id);
        } catch (Throwable t) {
            return 0;
        }
    }
}
