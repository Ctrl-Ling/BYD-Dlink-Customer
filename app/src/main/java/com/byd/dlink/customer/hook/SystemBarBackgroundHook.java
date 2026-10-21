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
    private static final String NAV_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.phone.navigationbar.CustomNavigationBarController";
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

    // What the nav keys have to be readable against, recorded with the decision that put the band
    // there. Reading the display is what it costs to answer that, so it is asked once per decision
    // and every icon that needs judging reads this instead.
    private static Integer sNavInkBackdrop;

    // Whether a new screen has been reported since the bars were last decided. Something else's turn
    // on screen is the only news that can change either bar's colour, so a decision is asked for when
    // that happens and taken by the next layout, which is the first frame of it on the display.
    private static boolean sRedecide = true;

    // The app the bars were last decided for, and the one just named by an event. Both the events
    // that carry a package are BYD's own nav-key refreshes, and those re-fire inside one app -- so
    // news of the app already decided for is no news, and one app never re-reads the display twice.
    private static String sDecidedPackage;
    private static String sNamedPackage;

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
                            } else if (NAV_BAR_VIEW.equals(name)) {
                                sNavBarView = new WeakReference<>(bar);
                            } else {
                                return;
                            }
                            // Reading the display is the expensive half, so a layout only does it when
                            // something that can change the answer has been reported since the last one.
                            if (sRedecide) {
                                apply();
                            } else {
                                repaint();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar background layout hook not installed: " + t);
        }
        try {
            Class<?> statusBar = XposedHelpers.findClass(STATUS_BAR_CLASS, lpparam.classLoader);
            // What the app in front draws by: flags can hand a band over to it, and reading them here
            // is what lets a bar stand down. Hooking all overloads also avoids guessing this fork's
            // parameter list.
            XposedBridge.hookAllMethods(statusBar, "setSystemUiVisibility", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sStatusBar = new WeakReference<>(param.thisObject);
                    // The flags the app in front draws by just changed, which can hand a band over.
                    sRedecide = true;
                }
            });
            XposedBridge.log(TAG + ": bar background installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar background visibility hook not installed: " + t);
        }
        hookTopApp(lpparam.classLoader);
    }

    /**
     * BYD's own two ways of naming the app on screen: StatusBar is told the top package by
     * system_server, and its nav controller re-inks the keys for it. Either one is patchy on this
     * fork -- measured, a third-party app changes hands with neither, while another re-fires the
     * controller several times while one screen scrolls -- so both are asked and the package is what
     * tells them apart. Contrast that with a layout pass, which came 41 times in fifteen swipes of a
     * single app.
     */
    private static void hookTopApp(ClassLoader classLoader) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                onTopPackage(param.args.length == 0 ? null : asPackage(param.args[0]));
            }
        };
        try {
            Class<?> statusBar = XposedHelpers.findClass(STATUS_BAR_CLASS, classLoader);
            XposedBridge.hookAllMethods(statusBar, "setPackageNameForStatusAndNaviationBar", callback);
            Class<?> controller = XposedHelpers.findClass(NAV_CONTROLLER_CLASS, classLoader);
            XposedBridge.hookAllMethods(controller, "updateNavBarIcon", callback);
            XposedBridge.log(TAG + ": bar top-app hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar top-app hook not installed: " + t);
        }
    }

    private static String asPackage(Object named) {
        return named instanceof String ? (String) named : null;
    }

    /** A new app on screen: ask for the bars to be decided for it, by the layout that draws it. */
    private static void onTopPackage(String packageName) {
        if (packageName == null || packageName.equals(sDecidedPackage)) {
            return;
        }
        sNamedPackage = packageName;
        sRedecide = true;
    }

    /**
     * Read the display and decide both bars again. This is the expensive half -- two screen captures --
     * so it runs on the first layout after news that can change the answer: another app's turn on
     * screen, or the configuration. A layout pass on its own is no such news, and a scrolling app
     * re-lays out the status bar several times a second, which is what made the bars visibly
     * re-colour inside one app.
     */
    private static void apply() {
        sRedecide = false;
        if (sNamedPackage != null) {
            sDecidedPackage = sNamedPackage;
            sNamedPackage = null;
        }
        Integer visibility = topAppVisibility();
        View statusBar = bar(sStatusBarView);
        sStatusBarColour = paint(statusBar, statusBar, View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN, true,
                PrefsManager.isStatusBarBgAuto(), PrefsManager.isStatusBarBgImmersiveEnabled(),
                PrefsManager.getStatusBarBgColor(), visibility);
        View navBar = bar(sNavBarView);
        sNavBarColour = paint(navBandView(), navBar, View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION,
                false, PrefsManager.isNavBarBgAuto(), PrefsManager.isNavBarBgImmersiveEnabled(),
                PrefsManager.getNavigationBarBgColor(), visibility);
        // Recorded here so judging the icons never costs a capture of its own. While the nav band is
        // the app's, the keys sit on the app's own pixels, and those are read once per decision.
        sNavInkBackdrop = sNavBarColour != null ? sNavBarColour
                : navBar == null || !PrefsManager.isNavBarBgEnabled()
                        ? null : edgeColour(navBar, false);
        // A bar SystemUI has not created yet has no colour to answer for, and that answer is still
        // owed; layouts ask again until both exist.
        sRedecide = statusBar == null || navBar == null;
        redrive();
    }

    /**
     * Put the colours already decided back on the bars and re-decide the icons against them. Used by a
     * layout pass, where the band views can be new ones that have never carried our colour.
     */
    private static void repaint() {
        putColour(navBandView(), sNavBarColour);
        putColour(bar(sStatusBarView), sStatusBarColour);
        redrive();
    }

    private static void putColour(View band, Integer colour) {
        if (band == null) {
            return;
        }
        if (colour != null) {
            paintColour(band, colour);
        } else {
            restore(band);
        }
    }

    /**
     * The icons have to be re-decided against whatever the bands now show. Repainting a band without
     * re-inking the keys leaves the previous scene's filters sitting on the new colour: measured, a
     * status-bar layout pass alone repainted the nav band white after a dark app, and every key kept a
     * white filter.
     */
    private static void redrive() {
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
     * What the nav keys are really drawn on: our band when we paint it, the app's own pixels showing
     * through when we stand down. A stand-down still leaves the icons needing ink that reads against
     * those pixels -- BYD does not re-decide a bitmap key once it has tinted one, so a stale white
     * filter survives onto a light home screen.
     *
     * This is the answer the last decision recorded, never a fresh capture: every icon in the bar asks
     * here, and a display read per icon is what scrolling would then cost.
     */
    public static Integer navInkBackdrop() {
        return sNavInkBackdrop;
    }

    /** The configuration changed, so the bars have to be decided again for the app already in front. */
    public static void onPrefsChanged() {
        apply();
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
