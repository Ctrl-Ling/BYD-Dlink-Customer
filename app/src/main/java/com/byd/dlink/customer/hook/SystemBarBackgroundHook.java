package com.byd.dlink.customer.hook;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

import com.byd.dlink.customer.utils.PrefsManager;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * A bar background is a colour the user picks, and the only thing that holds a bar's pixels on
 * this fork is the bar view itself: the transparency machinery AOSP would use for it
 * (StatusBar.setTransparency, the colour arguments of setWindowState) was stripped out, and the
 * trimmed setWindowState no longer carries a colour at all.
 *
 * The catch is that the same two windows are what an immersive screen draws through. When an app
 * lays its content behind a bar, the band is the app -- a wallpaper, a map -- and painting SystemUI's
 * view over it would cover the screen. So the decision is taken from the state SystemUI already
 * keeps: StatusBar.mSystemUiVisibility holds the top app's window flags, and its layout bits say
 * which bar that app is drawing behind.
 */
public class SystemBarBackgroundHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String STATUS_BAR_CLASS = "com.android.systemui.statusbar.phone.StatusBar";
    private static final String STATUS_BAR_VIEW = "status_bar";
    private static final String NAV_BAR_VIEW = "byd_custom_nav_bar";

    /** Written by StatusBar.setSystemUiVisibility on the main thread, read at paint time. */
    private static WeakReference<Object> sStatusBar;
    private static WeakReference<View> sStatusBarView;
    private static WeakReference<View> sNavBarView;

    // What the bar carried before we touched it, so standing down puts SystemUI's own drawing
    // back exactly -- including the common case of it having had no background at all.
    private static final Map<View, Drawable> sOriginals =
            Collections.synchronizedMap(new IdentityHashMap<View, Drawable>());

    /** How many rows of screen a bar's edge colour is read from. */
    private static final int EDGE_ROWS = 2;

    // What each band is showing because we painted it. Null means the app owns that band, which is
    // both the answer to "may we colour the icons on it" and the colour to judge them against.
    private static Integer sStatusBarColour;
    private static Integer sNavBarColour;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI_PACKAGE.equals(lpparam.packageName)
                || (!PrefsManager.isStatusBarBgEnabled() && !PrefsManager.isNavBarBgEnabled())) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(FrameLayout.class, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View bar = (View) param.thisObject;
                            if (bar.getWidth() < 600) {
                                return;
                            }
                            String name = entryName(bar);
                            if (STATUS_BAR_VIEW.equals(name)) {
                                sStatusBarView = new WeakReference<>(bar);
                                apply();
                            } else if (NAV_BAR_VIEW.equals(name)) {
                                sNavBarView = new WeakReference<>(bar);
                                // A bar that has just been laid out holds items that were bound and
                                // coloured before it knew what was on screen; apply() re-decides them.
                                apply();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar background layout hook not installed: " + t);
        }
        try {
            Class<?> statusBar = XposedHelpers.findClass(STATUS_BAR_CLASS, lpparam.classLoader);
            // Every window change passes through here, which is what makes a bar repaint for the
            // app that came forward. Hooking all overloads also avoids guessing this fork's
            // parameter list.
            XposedBridge.hookAllMethods(statusBar, "setSystemUiVisibility", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sStatusBar = new WeakReference<>(param.thisObject);
                    // The bars' icons have to be re-decided on exactly this event, and apply() does it:
                    // an item is bound once, before any window has told SystemUI what is on screen. This
                    // is not a layout pass, so re-running BYD's own colour setter is safe.
                    apply();
                }
            });
            XposedBridge.log(TAG + ": bar background installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar background visibility hook not installed: " + t);
        }
    }

    private static void apply() {
        Integer visibility = topAppVisibility();
        View statusBar = bar(sStatusBarView);
        sStatusBarColour = paint(statusBar, statusBar, View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN, true,
                PrefsManager.isStatusBarBgAuto(), PrefsManager.isStatusBarBgImmersiveEnabled(),
                PrefsManager.getStatusBarBgColor(), visibility);
        View navBar = bar(sNavBarView);
        sNavBarColour = paint(navBandView(), navBar, View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION,
                false, PrefsManager.isNavBarBgAuto(), PrefsManager.isNavBarBgImmersiveEnabled(),
                PrefsManager.getNavigationBarBgColor(), visibility);
        // Whatever path decided the bands -- a window change, either bar's layout pass -- the icons on
        // them have to be re-decided against the result. Repainting the band without re-inking the keys
        // leaves the previous scene's ink sitting on the new colour: measured, a status-bar layout pass
        // alone repainted the nav band white after a dark app, and every key kept a white filter.
        BarIconColourHook.reapply();
    }

    /**
     * Decide one bar and put the result on it.
     *
     * @param band             the view whose background we own
     * @param bar              the view that says where the bar sits on screen -- for the nav bar
     *                         these are two different views, see {@link #navBandView()}
     * @param below            whether the screen edge to match lies under this bar
     * @param auto             paint the colour the edge next to the bar really has
     * @param paintWhenBehind  override the immersive stand-down and paint the picked colour anyway
     * @param configured       the picked colour, null when this bar's background switch is off
     * @param visibility       the top app's window flags, null while SystemUI has not reported one
     * @return the colour the bar is now showing because we painted it, null when the app owns it
     */
    private static Integer paint(View band, View bar, int layoutFlag, boolean below, boolean auto,
            boolean paintWhenBehind, Integer configured, Integer visibility) {
        if (band == null || configured == null) {
            return restore(band);
        }
        // Unknown means SystemUI has not told us about a window yet, which is not the same as
        // knowing the app draws behind -- so it stays a reason to keep off the bar.
        boolean appDrawsBehind = visibility == null
                || (visibility & layoutFlag) != 0;
        if (auto) {
            if (appDrawsBehind) {
                return restore(band);
            }
            Integer edge = bar == null ? null : edgeColour(bar, below);
            return edge == null ? restore(band) : paintColour(band, edge);
        }
        return appDrawsBehind && !paintWhenBehind
                ? restore(band) : paintColour(band, configured);
    }

    /**
     * The colour of the couple of rows of screen touching the bar, i.e. the app's own edge. Reading
     * the display is the only way to it: an app that does not draw behind a bar keeps its pixels to
     * itself, and nothing SystemUI is told about that window names its colours.
     */
    private static Integer edgeColour(View bar, boolean below) {
        int[] location = new int[2];
        bar.getLocationOnScreen(location);
        int top = below ? location[1] + bar.getHeight() : location[1] - EDGE_ROWS;
        if (top < 0 || bar.getWidth() <= 0) {
            return null;
        }
        return ScreenEdgeSampler.dominantColour(0, top, bar.getWidth(), top + EDGE_ROWS);
    }

    private static Integer paintColour(View band, Integer colour) {
        if (!sOriginals.containsKey(band)) {
            sOriginals.put(band, band.getBackground());
        }
        band.setBackground(new ColorDrawable(colour.intValue()));
        return colour;
    }

    private static Integer restore(View band) {
        if (band != null && sOriginals.containsKey(band)) {
            band.setBackground(sOriginals.get(band));
        }
        return null;
    }

    /**
     * The band the user picks has to sit behind the keys, and on this fork the container the keys are
     * laid out in is drawn above its own background -- measured, an opaque colour on it hides every
     * icon in the bar. The parent it is added to is not, so the band goes there.
     */
    private static View navBandView() {
        View bar = bar(sNavBarView);
        if (bar == null) {
            return null;
        }
        android.view.ViewParent parent = bar.getParent();
        return parent instanceof View ? (View) parent : bar;
    }

    /**
     * Whether our colour is what the bar's band is showing right now, and which colour that is.
     * Anything that wants to judge the look of the band -- icon contrast is the case that matters
     * -- has to agree with the pixels the user actually sees, so it asks here instead of re-reading
     * the flags or the configured value, neither of which is the band once auto colour is on.
     */
    public static Integer statusBarColour() {
        return sStatusBarColour;
    }

    public static Integer navBarColour() {
        return sNavBarColour;
    }

    /**
     * What the nav keys are really drawn on. Ours when we paint the band; otherwise the app's own
     * pixels showing through the transparent bar, read the same way auto colour reads an edge. A
     * stand-down still leaves the icons needing ink that reads against those pixels -- BYD does not
     * re-decide a bitmap key once it has tinted one, so a stale white filter survives onto a light
     * home screen.
     */
    public static Integer navInkBackdrop() {
        if (sNavBarColour != null) {
            return sNavBarColour;
        }
        View bar = bar(sNavBarView);
        return bar == null ? null : edgeColour(bar, false);
    }

    /** The nav bar view, so the icon hook can read the views inside it rather than wait to be called. */
    static View navBarView() {
        return bar(sNavBarView);
    }

    /**
     * The flags of the app that owns the screen, read from the StatusBar instance that was last
     * told about it. Null until SystemUI has reported one: standing down is then the only safe
     * answer, because guessing wrong paints over somebody's content.
     */
    private static Integer topAppVisibility() {
        WeakReference<Object> reference = sStatusBar;
        if (reference == null) {
            return null;
        }
        Object statusBar = reference.get();
        if (statusBar == null) {
            return null;
        }
        try {
            return (Integer) XposedHelpers.getObjectField(statusBar, "mSystemUiVisibility");
        } catch (Throwable t) {
            return null;
        }
    }

    private static View bar(WeakReference<View> reference) {
        return reference == null ? null : reference.get();
    }

    private static String entryName(View view) {
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
}
