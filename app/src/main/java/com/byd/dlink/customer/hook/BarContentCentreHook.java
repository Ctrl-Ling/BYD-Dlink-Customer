package com.byd.dlink.customer.hook;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.byd.dlink.customer.utils.PrefsManager;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The height override reaches everything that asks Resources for a bar dimen, which is why the
 * windows and the status bar now size themselves to it. The bars' own content does not: heights
 * and margins written as layout attributes are resolved by LayoutInflater inside the resource
 * table through TypedArray, so no hooked Resources method ever sees the request. What is left
 * depends on the panel. On the newer one the nav keys keep their stock 90px box, taller than the
 * resized bar, so the glyph is pushed below the middle. On Dlink 3.0/4.0 the opposite happens --
 * the clock cluster keeps a 48px box and stays flush with the top of a bar grown to 60, the
 * air-conditioning icons keep a bottom flush in a bar shrunk from 90 to 55.
 *
 * Rather than intercept attribute resolution for every view SystemUI inflates, centre what is
 * left: after the bar has laid itself out, each view is moved to where its own container would
 * have it, which puts the glyph -- drawn centred inside its own box -- in the middle of the bar.
 */
public class BarContentCentreHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String[] BAR_CONTAINERS = {
            "byd_custom_nav_bar",
            "status_bar",
    };
    // Dlink 3.0/4.0 without the 4.5 UI module names its bars differently again, and what sits
    // inside them keeps the stock 48px/90px geometry rather than the resized bar.
    private static final String[] DLINK34_BAR_CONTAINERS = {
            "status_bar_container",
            "nav_buttons",
    };
    private static final int MAX_DEPTH = 12;
    // The status bar subtree is a few hundred views and this runs on a layout pass, so one walk has
    // a ceiling; the bars' own content is shallower and narrower than it.
    private static final int MAX_VIEWS = 400;
    /** A view this close to where it is wanted is left exactly where BYD put it. */
    private static final float CENTRE_TOLERANCE = 2f;

    // Chosen at process start, like every other bar height decision: a variant switch is worth a
    // SystemUI restart, and reading it on each layout pass would ask a property per FrameLayout.
    private static boolean sContentFillsBar;
    // Counted across one walk, reset by the layout pass that starts it; the main thread is the only
    // one that lays views out, so no guard is needed between passes.
    private static int sViews;
    private static int sMoved;
    private static final Set<String> sWalked = new HashSet<String>();

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI_PACKAGE.equals(lpparam.packageName)
                || (PrefsManager.getStatusBarHeightOverride() < 0
                && PrefsManager.getNavigationBarHeightOverride() < 0)) {
            return;
        }
        // 3.0/4.0 keeps its bar content inside boxes whose height came out of the stock bar, so
        // those boxes now measure less than the bar and sit flush against one edge; the newer
        // panels hand back a box taller than the bar instead.
        sContentFillsBar = PrefsManager.getEffectiveSystemVariant() == PrefsManager.SYSTEM_DLINK_34;
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
                            if (bar.getWidth() < 600) {
                                return;
                            }
                            String root = barContainerName(bar);
                            if (root == null) {
                                return;
                            }
                            if (sContentFillsBar) {
                                sViews = 0;
                                sMoved = 0;
                                centreInsideParent(bar, 0);
                                if (sWalked.add(root)) {
                                    XposedBridge.log(TAG + ": centring walked " + root
                                            + " views=" + sViews + " moved=" + sMoved);
                                }
                            } else {
                                centreOverflow(bar, 0);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": bar content centring installed mode="
                    + (sContentFillsBar ? "box" : "overflow"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar content centring not installed: " + t);
        }
    }

    private static String barContainerName(View view) {
        int id = view.getId();
        if (id == 0) {
            return null;
        }
        String name;
        try {
            name = view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
        return contains(BAR_CONTAINERS, name)
                || sContentFillsBar && contains(DLINK34_BAR_CONTAINERS, name) ? name : null;
    }

    private static boolean contains(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Put each view back where its own container would have it: in the middle. Computed from where
     * it actually is, so a view BYD already centred returns zero and is not touched, and a container
     * that moves carries its own centred children with it instead of centring them a second time.
     * Absolute, never additive -- onLayout runs again on every pass, and the layout position this
     * reads does not move with the translation it writes.
     */
    private static void centreInsideParent(ViewGroup parent, int depth) {
        if (depth >= MAX_DEPTH || sViews >= MAX_VIEWS) {
            return;
        }
        int contentTop = parent.getPaddingTop();
        int available = parent.getHeight() - contentTop - parent.getPaddingBottom();
        if (available <= 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int height = child.getHeight();
            if (height <= 0 || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (++sViews > MAX_VIEWS) {
                return;
            }
            float delta = (available - height) / 2f - (child.getTop() - contentTop);
            if (Math.abs(delta) > CENTRE_TOLERANCE) {
                child.setTranslationY(delta);
                sMoved++;
            }
            if (child instanceof ViewGroup) {
                centreInsideParent((ViewGroup) child, depth + 1);
            }
        }
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
