package com.byd.dlink.customer.hook;

import android.view.View;
import android.view.ViewParent;

/**
 * Which view is which bar. Both hooks ask this instead of carrying their own copy of the names:
 * the bars are only recognisable by the resource entry name SystemUI laid them out with, and a
 * second list of those names is how one hook starts seeing a bar the other one paints.
 */
final class BarViews {

    static final String STATUS_BAR_VIEW = "status_bar";
    static final String NAV_BAR_VIEW = "byd_custom_nav_bar";
    // Dlink 3.0/4.0 has no BYD nav container at all: the keys are stock KeyButtonViews laid out by
    // AOSP's NavigationBarInflaterView, and the only view that spans the bar and still sits behind
    // every icon is the window's own frame.
    static final String NAV_BAR_VIEW_34 = "navigation_bar_frame";

    static final int NONE = 0;
    static final int NAV = 1;
    static final int STATUS = 2;

    /** The name a view was laid out with, or null when it carries no id SystemUI can name. */
    static String entryName(View view) {
        int id = view.getId();
        if (id == 0) {
            return null;
        }
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which bar a laid-out view answers for, from its own name. */
    static int namedAs(View bar) {
        String name = entryName(bar);
        if (STATUS_BAR_VIEW.equals(name)) {
            return STATUS;
        }
        if (NAV_BAR_VIEW.equals(name) || NAV_BAR_VIEW_34.equals(name)) {
            return NAV;
        }
        return NONE;
    }

    /** Whether a view is a bar's own container, which is where an ancestor walk stops. */
    static boolean isBar(View view) {
        return namedAs(view) != NONE;
    }

    /**
     * Which bar a view belongs to, found by walking up to the bar container that holds it. The walk
     * is what keeps this independent of which button it is: anything in the bar, ours or another
     * module's, hangs off the same container.
     */
    static int barOf(View view) {
        for (View current = view; current != null; ) {
            int bar = namedAs(current);
            if (bar != NONE) {
                return bar;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return NONE;
    }

    static String name(int bar) {
        return bar == STATUS ? "status" : bar == NAV ? "nav" : "none";
    }

    private BarViews() {
    }
}
