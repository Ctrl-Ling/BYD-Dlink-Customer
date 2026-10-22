package com.byd.dlink.customer.hook;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
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
    // Dlink 3.0/4.0 has no BYD nav container at all: the keys are stock KeyButtonViews laid out by
    // AOSP's NavigationBarInflaterView, and the only view that spans the bar and still sits behind
    // every icon is the window's own frame.
    private static final String NAV_BAR_VIEW_34 = "navigation_bar_frame";

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

    // The flags each bar's band answer below was worked out from, and that answer. Measured: the
    // package event that names a new app arrives ~300 ms before the visibility event that carries its
    // flags, so a decision taken in between reads the *previous* app's flags. That is what painted a
    // band over the incoming launcher and left the screen that does not draw behind with none -- the
    // black flash between a immersive screen and a specified colour. A bar whose colour the user picked
    // has nothing to correct, so its answer is held until the flags themselves move. A bar that reads
    // the screen keeps re-deriving it every pass as it always did, because its next pass -- once the
    // switch has finished sliding in -- is what corrects a colour sampled off a moving frame.
    private static Integer sStatusFlags;
    private static Integer sNavFlags;
    private static boolean sStatusBehind = true;
    private static boolean sNavBehind = true;

    // Whether a band's own pixels moved during the pass. The icons only have to be re-decided when
    // they did: re-driving them walks the whole bar and costs 12-47 ms of the main thread per pass,
    // which is what made the ink visibly lag behind the colour.
    private static boolean sBandsMoved;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        // The corner mask hangs off the same layout pass that says where the bars are, so this hook
        // has to be standing whenever either feature could be asked for.
        if (!SYSTEM_UI_PACKAGE.equals(lpparam.packageName)
                || (!PrefsManager.isStatusBarBgEnabled() && !PrefsManager.isNavBarBgEnabled()
                && !PrefsManager.isScreenCornerEnabled())) {
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
                            } else if (NAV_BAR_VIEW.equals(name) || NAV_BAR_VIEW_34.equals(name)) {
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
                            // A layout is also the only moment the bands' own heights are known, and
                            // the shorter of the two is the ceiling on the corner arc.
                            ScreenCornerHook.refresh(bar(sStatusBarView), bar(sNavBarView));
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
                    // The flags the app in front draws by just changed. This is the only call that can
                    // change that answer, so it is the only one the band question is asked of.
                    if (needsSampling()) {
                        // A colour has to be read off the frame that follows, so let its layout decide.
                        sRedecide = true;
                    } else {
                        // Nothing has to be read but the flags in hand, so deciding here puts the band
                        // on the bar at the start of the switch instead of one layout later, which is
                        // the gap that shows as black between two screens.
                        apply();
                    }
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
        if (packageName == null || packageName.equals(sDecidedPackage) || !needsSampling()) {
            return;
        }
        sNamedPackage = packageName;
        sRedecide = true;
    }

    /**
     * Whether a decision still has to read the display. Auto colour does, because the colour is the
     * app's own pixels; a specified colour does not, because the only thing a new app can change is
     * whether it draws behind the bar -- which the flags say, and which only the visibility event moves.
     */
    private static boolean needsSampling() {
        return (PrefsManager.isStatusBarBgEnabled() && PrefsManager.isStatusBarBgAuto())
                || (PrefsManager.isNavBarBgEnabled() && PrefsManager.isNavBarBgAuto());
    }

    /**
     * Decide both bars again for the app in front. This is the expensive half only when a bar is set to
     * auto colour, which has to read the screen: a specified colour is answered from the flags, so it is
     * decided the moment the flags move rather than waiting for the layout that draws them. The layout
     * pass on its own is no news at all, and a scrolling app re-lays out the status bar several times a
     * second, which is what made the bars visibly re-colour inside one app.
     */
    private static void apply() {
        sRedecide = false;
        sBandsMoved = false;
        if (sNamedPackage != null) {
            sDecidedPackage = sNamedPackage;
            sNamedPackage = null;
        }
        Integer visibility = topAppVisibility();
        boolean statusAuto = PrefsManager.isStatusBarBgEnabled() && PrefsManager.isStatusBarBgAuto();
        boolean navAuto = PrefsManager.isNavBarBgEnabled() && PrefsManager.isNavBarBgAuto();
        if (statusAuto || !java.util.Objects.equals(visibility, sStatusFlags)) {
            sStatusBehind = drawsBehind(visibility, View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            sStatusFlags = visibility;
        }
        if (navAuto || !java.util.Objects.equals(visibility, sNavFlags)) {
            sNavBehind = drawsBehind(visibility, View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            sNavFlags = visibility;
        }
        View statusBar = bar(sStatusBarView);
        sStatusBarColour = paint(statusBar, statusBar, sStatusBehind, true,
                statusAuto, PrefsManager.isStatusBarBgImmersiveEnabled(),
                PrefsManager.getStatusBarBgColor());
        View navBar = bar(sNavBarView);
        sNavBarColour = paint(navBandView(), navBar, sNavBehind, false,
                navAuto, PrefsManager.isNavBarBgImmersiveEnabled(),
                PrefsManager.getNavigationBarBgColor());
        putColour(navPlateView(), sNavBarColour);
        // Recorded here so judging the icons never costs a capture of its own. While the nav band is
        // the app's, the keys sit on the app's own pixels, and those are read once per decision.
        Integer ink = sNavBarColour != null ? sNavBarColour
                : navBar == null || !PrefsManager.isNavBarBgEnabled()
                        ? null : edgeColour(navBar, false);
        boolean inkMoved = !java.util.Objects.equals(ink, sNavInkBackdrop);
        sNavInkBackdrop = ink;
        // A bar SystemUI has not created yet has no colour to answer for, and that answer is still
        // owed; layouts ask again until both exist.
        sRedecide = statusBar == null || navBar == null;
        if (sBandsMoved || inkMoved) {
            redrive();
        }
        // A decision is the only thing that can move a band, and it is supposed to be news about the
        // app in front, not a frame of its own. This line is how a scroll inside one app is proved --
        // one per switch is the answer, anything more is the bar re-reading the display per frame.
        XposedBridge.log(TAG + ": decided app=" + sDecidedPackage + " status=" + hex(sStatusBarColour)
                + " nav=" + hex(sNavBarColour));
    }

    /** What a band is showing, in the words a log line can be read in: {@code app} means we own none. */
    private static String hex(Integer colour) {
        return colour == null ? "app" : Integer.toHexString(colour.intValue());
    }

    /**
     * Whether the app in front lays its own pixels under a bar. Unknown flags are a reason to keep off
     * the bar, not a reason to guess: guessing wrong paints over somebody's content.
     */
    private static boolean drawsBehind(Integer visibility, int layoutFlag) {
        return visibility == null || (visibility.intValue() & layoutFlag) != 0;
    }

    /**
     * Put the colours already decided back on the bars. Used by a layout pass, where the band views can
     * be new ones that have never carried our colour.
     */
    private static void repaint() {
        sBandsMoved = false;
        putColour(navBandView(), sNavBarColour);
        putColour(navPlateView(), sNavBarColour);
        putColour(bar(sStatusBarView), sStatusBarColour);
        if (sBandsMoved) {
            redrive();
        }
    }

    /**
     * Puts {@code colour} on a band, or -- when {@code colour} is null -- the colour it carried before
     * we ever touched it. Setting a background costs the icons a re-decision, so a band that already
     * shows what was decided for it is left alone, and says so.
     */
    private static void putColour(View band, Integer colour) {
        if (band == null) {
            return;
        }
        Drawable carried = band.getBackground();
        if (colour == null) {
            if (!sOriginals.containsKey(band) || carried == sOriginals.get(band)) {
                return;
            }
            band.setBackground(sOriginals.get(band));
        } else {
            if (carried instanceof ColorDrawable
                    && ((ColorDrawable) carried).getColor() == colour.intValue()) {
                return;
            }
            if (!sOriginals.containsKey(band)) {
                sOriginals.put(band, carried);
            }
            band.setBackground(new ColorDrawable(colour.intValue()));
        }
        sBandsMoved = true;
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
     * @param appDrawsBehind   whether the app in front lays its own pixels under this bar
     * @param below            whether the screen edge to match lies under this bar
     * @param auto             paint the colour the edge next to the bar really has
     * @param paintWhenBehind  override the immersive stand-down and paint the picked colour anyway
     * @param configured       the picked colour, null when this bar's background switch is off
     * @return the colour the bar is now showing because we painted it, null when the app owns it
     */
    private static Integer paint(View band, View bar, boolean appDrawsBehind, boolean below,
            boolean auto, boolean paintWhenBehind, Integer configured) {
        if (band == null || configured == null) {
            return restore(band);
        }
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
        putColour(band, colour);
        return colour;
    }

    private static Integer restore(View band) {
        putColour(band, null);
        return null;
    }

    /**
     * The band the user picks has to sit behind the keys, and on this fork the container the keys are
     * laid out in is drawn above its own background -- measured, an opaque colour on it hides every
     * icon in the bar. The parent it is added to is not, so the band goes there.
     *
     * Dlink 3.0/4.0 hands over the window's own frame instead, which is behind the keys but has an
     * opaque plate drawn above it, so the frame alone is not enough -- see {@link #navPlateView()}.
     */
    private static View navBandView() {
        View bar = bar(sNavBarView);
        if (bar == null) {
            return null;
        }
        if (NAV_BAR_VIEW_34.equals(entryName(bar))) {
            return bar;
        }
        android.view.ViewParent parent = bar.getParent();
        return parent instanceof View ? (View) parent : bar;
    }

    /**
     * The plate Dlink 3.0/4.0 draws above its nav frame: the child of the frame that spans the bar. The
     * frame is behind it and the plate is opaque, so a colour put on the frame alone is invisible
     * wherever the ROM shows the plate -- which is every app that keeps its pixels off the bar, i.e.
     * exactly the screens a nav bar colour is asked for. Measured: the frame carried the picked
     * #f5f5f5 while the strip on screen stayed the plate's own #18222f. The plate is where the keys
     * already sit, so colouring it costs them nothing.
     *
     * @return the plate, or null where there is none -- the other forks paint the frame's parent
     */
    private static View navPlateView() {
        View bar = bar(sNavBarView);
        if (bar == null || !NAV_BAR_VIEW_34.equals(entryName(bar))) {
            return null;
        }
        View plate = bar;
        for (int depth = 0; depth < 4; depth++) {
            View spanning = spanningChild(plate);
            if (spanning == null) {
                break;
            }
            plate = spanning;
        }
        return plate == bar ? null : plate;
    }

    /** The child that covers its parent whole, which is how a plate is recognised without a name. */
    private static View spanningChild(View parent) {
        if (!(parent instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() >= group.getWidth()
                    && child.getHeight() >= group.getHeight()) {
                return child;
            }
        }
        return null;
    }

    /**
     * Whether this view is a band this module paints. Its colour is then ours, and an icon's judgement
     * has to be made against it rather than standing down because a plate is behind the icon -- a
     * saturated band colour reads as a plate to any test that only looks at the colour.
     */
    static boolean isBandView(View view) {
        return sOriginals.containsKey(view);
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
        ScreenCornerHook.refresh(bar(sStatusBarView), bar(sNavBarView));
    }

    /** The nav bar view, so the icon hook can read the views inside it rather than wait to be called. */
    static View navBarView() {
        return bar(sNavBarView);
    }

    /** The status bar view, for the same reason. */
    static View statusBarView() {
        return bar(sStatusBarView);
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
