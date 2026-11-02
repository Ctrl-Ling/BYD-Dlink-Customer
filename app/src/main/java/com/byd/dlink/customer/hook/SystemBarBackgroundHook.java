package com.byd.dlink.customer.hook;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.byd.dlink.customer.utils.PrefsManager;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Decides which colour each bar shows, and when that decision has to be taken again.
 *
 * The decision itself is a small rule, and its result lives in {@link BarBands}: paint the colour the
 * user picked, stand down while the app in front draws behind the bar, or paint the app's own edge
 * colour when the bar is set to follow the screen. Almost all of this class is the other half of the
 * job -- knowing when a new screen has arrived, and when its pixels are actually on the display. Every
 * event that names the incoming app fires before that app is composited, so a colour read at the event
 * is the *outgoing* screen's colour; the timing rules below exist because of that.
 *
 * Whether an app draws behind a bar comes from the state SystemUI already keeps: StatusBar
 * .mSystemUiVisibility holds the top app's window flags, and its layout bits say which bar that app
 * paints through.
 */
public class SystemBarBackgroundHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String STATUS_BAR_CLASS = "com.android.systemui.statusbar.phone.StatusBar";
    private static final String NAV_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.phone.navigationbar.CustomNavigationBarController";
    // system_server's way of telling SystemUI that a window transition has landed on the display.
    private static final String COMMAND_QUEUE_CLASS = "com.android.systemui.statusbar.CommandQueue";

    /** Written by StatusBar.setSystemUiVisibility on the main thread, read at paint time. */
    private static WeakReference<Object> sStatusBar;

    /** The thread the bars are drawn on; a transition is reported to us on a binder thread. */
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    // Whether a new screen has been reported since the bars were last decided. Something else's turn
    // on screen is the only news that can change either bar's colour, so a decision is asked for when
    // that happens and taken by the next layout, which is the first frame of it on the display.
    private static boolean sRedecide = true;

    // The app the bars were last decided for, and the one just named by an event. Both the events
    // that carry a package are BYD's own nav-key refreshes, and those re-fire inside one app -- so
    // news of the app already decided for is no news, and one app never re-reads the display twice.
    private static String sDecidedPackage;
    private static String sNamedPackage;

    // The flags each bar's behind-or-not answer was worked out from, and that answer. Measured: the
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

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        // The corner mask hangs off the same layout pass that says where the bars are, so this hook
        // has to be standing whenever either feature could be asked for.
        if (!SYSTEM_UI_PACKAGE.equals(lpparam.packageName)
                || (!PrefsManager.isStatusBarBgEnabled() && !PrefsManager.isNavBarBgEnabled()
                && !PrefsManager.isScreenCornerEnabled())) {
            return;
        }
        hookLayout();
        hookVisibility(lpparam.classLoader);
        hookTopApp(lpparam.classLoader);
        hookTransition(lpparam.classLoader);
    }

    /**
     * The layout pass that says where each bar sits. It is the only moment the bar views and the bands'
     * own heights are known, so it is also where a decision armed by an earlier event gets taken.
     */
    private static void hookLayout() {
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
                            if (BarBands.remember(bar) == BarViews.NONE) {
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
                            ScreenCornerHook.refresh(BarBands.statusBar(), BarBands.navBar());
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar background layout hook not installed: " + t);
        }
    }

    /**
     * The flags the app in front draws by. This is the only call that can hand a band over to an app or
     * take it back, so it is the only one the band question is asked of. Hooking all overloads also
     * avoids guessing this fork's parameter list.
     */
    private static void hookVisibility(ClassLoader classLoader) {
        try {
            Class<?> statusBar = XposedHelpers.findClass(STATUS_BAR_CLASS, classLoader);
            XposedBridge.hookAllMethods(statusBar, "setSystemUiVisibility", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sStatusBar = new WeakReference<>(param.thisObject);
                    if (bandReadsScreen(true) || bandReadsScreen(false)) {
                        // A colour has to be read off the frame that follows, so let its layout decide.
                        sRedecide = true;
                    } else {
                        // Nothing has to be read but the flags in hand, so deciding here puts the band
                        // on the bar at the start of the switch instead of one layout later, which is
                        // the gap that shows as black between two screens.
                        apply();
                        // The icons are the exception: a band stood down by this answer has to be read
                        // against the app's frame, and that frame is only on the display one pass
                        // later. So the band is settled now and the ink by the layout that draws it.
                        if (aBandStoodDown()) {
                            sRedecide = true;
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": bar background installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar background visibility hook not installed: " + t);
        }
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

    /**
     * The moment the screen the bars have to match is actually on the display.
     *
     * Both events that name a new app -- BYD's package report and the visibility flags -- arrive
     * before the incoming window is composited, so a colour read off the screen at that moment is the
     * *outgoing* screen's. Measured on this fork: opening this module over a black launcher decided
     * black for both bars when the module's own edges are blue and white, and going back to that same
     * black launcher decided its blue and white. Each app wore the colour of the one it replaced, and
     * the package dedupe that keeps one app from reading the display twice then held that answer until
     * the next switch. A transition finishing is the one report that means the new frame has landed,
     * so the colour is asked of it again there.
     *
     * The bars are not laid out by a transition ending, so the decision is taken here rather than
     * armed for a layout that may never come.
     */
    private static void hookTransition(ClassLoader classLoader) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!readsDisplay()) {
                    return;
                }
                sMain.post(new Runnable() {
                    @Override
                    public void run() {
                        apply();
                    }
                });
            }
        };
        try {
            Class<?> queue = XposedHelpers.findClass(COMMAND_QUEUE_CLASS, classLoader);
            // Every overload is asked: the argument list is this fork's, and guessing it is how a
            // hook point silently stops covering the case it was added for.
            XposedBridge.hookAllMethods(queue, "appTransitionFinished", callback);
            XposedBridge.log(TAG + ": bar transition hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar transition hook not installed: " + t);
        }
    }

    private static String asPackage(Object named) {
        return named instanceof String ? (String) named : null;
    }

    /** A new app on screen: ask for the bars to be decided for it, by the layout that draws it. */
    private static void onTopPackage(String packageName) {
        if (packageName == null || packageName.equals(sDecidedPackage) || !readsDisplay()) {
            return;
        }
        sNamedPackage = packageName;
        sRedecide = true;
    }

    /**
     * Whether one bar's band colour comes off the display. Auto colour does, because the colour is the
     * app's own pixels; a specified colour does not, because the only thing a new app can change about
     * it is whether that app draws behind the bar -- which the flags say, and which only the visibility
     * event moves.
     */
    private static boolean bandReadsScreen(boolean status) {
        return status
                ? PrefsManager.isStatusBarBgEnabled() && PrefsManager.isStatusBarBgAuto()
                : PrefsManager.isNavBarBgEnabled() && PrefsManager.isNavBarBgAuto();
    }

    /**
     * Whether one bar's icons have to read the display for the backdrop they are judged against. A bar
     * that keeps off an immersive screen leaves its icons on the app's pixels, and those are only on
     * the screen once the app that owns them has been drawn -- so a new app is news for them too. The
     * two other settings need no reading: an auto-coloured band already *is* the app's edge, and a band
     * that paints over an immersive screen already carries the picked colour.
     */
    private static boolean inkReadsScreen(boolean status) {
        return status
                ? PrefsManager.isStatusBarBgEnabled() && !PrefsManager.isStatusBarBgAuto()
                        && !PrefsManager.isStatusBarBgImmersiveEnabled()
                : PrefsManager.isNavBarBgEnabled() && !PrefsManager.isNavBarBgAuto()
                        && !PrefsManager.isNavBarBgImmersiveEnabled();
    }

    /** Whether any part of the decision -- a band colour or an ink backdrop -- comes off the screen. */
    private static boolean readsDisplay() {
        return bandReadsScreen(true) || bandReadsScreen(false)
                || inkReadsScreen(true) || inkReadsScreen(false);
    }

    /**
     * Whether the decision just taken left a band to the app. Only then do the icons have something on
     * the incoming frame to be read against, so this is what a visibility event arms a second pass for.
     */
    private static boolean aBandStoodDown() {
        return (PrefsManager.isStatusBarBgEnabled() && BarBands.statusBarColour() == null)
                || (PrefsManager.isNavBarBgEnabled() && BarBands.navBarColour() == null);
    }

    /**
     * Decide both bars again for the app in front. This is the expensive half only when a bar has to
     * read the screen; a specified colour is answered from the flags, so it is decided the moment the
     * flags move rather than waiting for the layout that draws them. The layout pass on its own is no
     * news at all, and a scrolling app re-lays out the status bar several times a second, which is what
     * made the bars visibly re-colour inside one app.
     */
    private static void apply() {
        sRedecide = false;
        BarBands.beginPass();
        if (sNamedPackage != null) {
            sDecidedPackage = sNamedPackage;
            sNamedPackage = null;
        }
        Integer visibility = topAppVisibility();
        if (bandReadsScreen(true) || !java.util.Objects.equals(visibility, sStatusFlags)) {
            sStatusBehind = drawsBehind(visibility, View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            sStatusFlags = visibility;
        }
        if (bandReadsScreen(false) || !java.util.Objects.equals(visibility, sNavFlags)) {
            sNavBehind = drawsBehind(visibility, View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            sNavFlags = visibility;
        }
        View statusBar = BarBands.statusBar();
        View navBar = BarBands.navBar();
        Integer statusColour = paintBar(statusBar, statusBar, sStatusBehind, true,
                PrefsManager.getStatusBarBgColor(), PrefsManager.isStatusBarBgImmersiveEnabled());
        Integer navColour = paintBar(BarBands.navBandView(), navBar, sNavBehind, false,
                PrefsManager.getNavigationBarBgColor(), PrefsManager.isNavBarBgImmersiveEnabled());
        BarBands.putColour(BarBands.navPlateView(), navColour);
        BarBands.setColours(statusColour, navColour);
        // Recorded here so judging the icons never costs a capture of its own. While a band is the
        // app's, its icons sit on the app's own pixels, and those are read once per decision.
        boolean inkMoved = recordInk(statusBar, navBar);
        // A bar SystemUI has not created yet has no colour to answer for, and that answer is still
        // owed; layouts ask again until both exist.
        sRedecide = statusBar == null || navBar == null;
        if (BarBands.moved() || inkMoved) {
            redrive();
        }
        logDecision();
    }

    /**
     * Remember what each bar's icons have to be readable against.
     *
     * @return whether that answer moved, which is when the icons have to be decided again.
     */
    private static boolean recordInk(View statusBar, View navBar) {
        Integer statusInk = inkBackdrop(statusBar, BarBands.statusBarColour(),
                PrefsManager.isStatusBarBgEnabled(), true);
        Integer navInk = inkBackdrop(navBar, BarBands.navBarColour(),
                PrefsManager.isNavBarBgEnabled(), false);
        boolean moved = !java.util.Objects.equals(statusInk, BarBands.statusInkBackdrop())
                || !java.util.Objects.equals(navInk, BarBands.navInkBackdrop());
        BarBands.setInkBackdrops(statusInk, navInk);
        return moved;
    }

    /**
     * What one bar's icons have to be readable against: the colour we painted, or -- when the band was
     * stood down and the app shows through -- the app's own edge. A stand-down still leaves the icons
     * needing ink that reads against those pixels, because BYD does not re-decide a key once it has
     * tinted one, so a stale white filter survives onto a light screen.
     */
    private static Integer inkBackdrop(View bar, Integer painted, boolean enabled, boolean below) {
        if (painted != null) {
            return painted;
        }
        return bar == null || !enabled ? null : BarBands.edgeColour(bar, below);
    }

    /**
     * Decide one bar and put the result on it.
     *
     * @param band             the view whose background we own
     * @param bar              the view that says where the bar sits on screen -- for the nav bar
     *                         these are two different views, see {@link BarBands#navBandView()}
     * @param appDrawsBehind   whether the app in front lays its own pixels under this bar
     * @param below            whether the screen edge to match lies under this bar
     * @param configured       the picked colour, null when this bar's background switch is off
     * @param paintWhenBehind  override the immersive stand-down and paint the picked colour anyway
     * @return the colour the bar is now showing because we painted it, null when the app owns it
     */
    private static Integer paintBar(View band, View bar, boolean appDrawsBehind, boolean below,
            Integer configured, boolean paintWhenBehind) {
        if (band == null || configured == null) {
            return BarBands.restore(band);
        }
        if (bandReadsScreen(below)) {
            if (appDrawsBehind) {
                return BarBands.restore(band);
            }
            Integer edge = bar == null ? null : BarBands.edgeColour(bar, below);
            return edge == null ? BarBands.restore(band) : BarBands.paintColour(band, edge);
        }
        return appDrawsBehind && !paintWhenBehind
                ? BarBands.restore(band) : BarBands.paintColour(band, configured);
    }

    /** Put the colours already decided back on the bars, for a layout that brought new band views. */
    private static void repaint() {
        BarBands.beginPass();
        BarBands.paintAgain();
        if (BarBands.moved()) {
            redrive();
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
     * A decision is the only thing that can move a band, and it is supposed to be news about the app in
     * front, not a frame of its own. This line is how a scroll inside one app is proved -- one per
     * switch is the answer, anything more is the bar re-reading the display per frame.
     */
    private static void logDecision() {
        XposedBridge.log(TAG + ": decided app=" + sDecidedPackage
                + " status=" + hex(BarBands.statusBarColour())
                + " nav=" + hex(BarBands.navBarColour())
                + " ink=" + hex(BarBands.statusInkBackdrop())
                + "/" + hex(BarBands.navInkBackdrop()));
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

    /** The configuration changed, so the bars have to be decided again for the app already in front. */
    public static void onPrefsChanged() {
        apply();
        ScreenCornerHook.refresh(BarBands.statusBar(), BarBands.navBar());
    }

    public static Integer statusBarColour() {
        return BarBands.statusBarColour();
    }

    public static Integer navBarColour() {
        return BarBands.navBarColour();
    }
}
