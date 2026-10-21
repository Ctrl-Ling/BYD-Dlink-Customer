package com.byd.dlink.customer.hook;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The height override reaches everything that asks Resources for a bar dimen, which is why the
 * windows and the status bar now size themselves to it. The navigation bar's keys do not: their
 * 90px box is a layout attribute, and LayoutInflater resolves those inside the resource table
 * through TypedArray, so no hooked Resources method ever sees the request.
 *
 * Rather than intercept attribute resolution for every view SystemUI inflates, centre what is
 * left: after the bar has laid itself out, anything taller than the container holding it is
 * shifted up by half the difference, which puts the glyph -- drawn centred inside its own box --
 * in the middle of the bar instead of below it.
 */
public class BarContentCentreHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String[] BAR_CONTAINERS = {
            "byd_custom_nav_bar",
            "status_bar",
    };
    private static final int MAX_DEPTH = 8;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI_PACKAGE.equals(lpparam.packageName)
                || (PrefsManager.getStatusBarHeightOverride() < 0
                && PrefsManager.getNavigationBarHeightOverride() < 0)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(FrameLayout.class, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FrameLayout bar = (FrameLayout) param.thisObject;
                            // A resource-name lookup on every FrameLayout in SystemUI would be
                            // paid by every tile and button in the panel; the bars are the only
                            // ones that span the screen.
                            if (bar.getWidth() < 600 || !isBarContainer(bar)) {
                                return;
                            }
                            centreOverflow(bar, 0);
                        }
                    });
            XposedBridge.log(TAG + ": bar content centring installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar content centring not installed: " + t);
        }
    }

    private static boolean isBarContainer(View view) {
        int id = view.getId();
        if (id == 0) {
            return false;
        }
        String name;
        try {
            name = view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return false;
        }
        for (int i = 0; i < BAR_CONTAINERS.length; i++) {
            if (BAR_CONTAINERS[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void centreOverflow(ViewGroup parent, int depth) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        int available = parent.getHeight();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int height = child.getHeight();
            if (available > 0 && height > available) {
                child.setTranslationY((available - height) / 2f);
                // Its own children are centred inside it, so moving it moves them; descending
                // would shift them a second time.
                continue;
            }
            if (child instanceof ViewGroup) {
                centreOverflow((ViewGroup) child, depth + 1);
            }
        }
    }
}
