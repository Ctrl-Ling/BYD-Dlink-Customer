package com.byd.dlink.customer.hook;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The two bands a bar background is drawn on: which view holds them, what colour they are showing, and
 * what the icons standing on them have to be readable against.
 *
 * A band is not a window this module can set a colour on. The transparency machinery AOSP would use for
 * it (StatusBar.setTransparency, the colour arguments of setWindowState) was stripped out of this fork,
 * and the trimmed setWindowState no longer carries a colour at all, so the only thing that holds a
 * bar's pixels is the bar view's own background. That is also the view an immersive screen draws
 * through, which is why standing a band down has to put back exactly what SystemUI carried before.
 */
final class BarBands {

    /** How many rows of screen a bar's edge colour is read from. */
    private static final int EDGE_ROWS = 2;

    // Found by the layout pass that says where the bars are, and read at paint time.
    private static WeakReference<View> sStatusBarView;
    private static WeakReference<View> sNavBarView;

    // What the bar carried before we touched it, so standing down puts SystemUI's own drawing
    // back exactly -- including the common case of it having had no background at all.
    private static final Map<View, Drawable> sOriginals =
            Collections.synchronizedMap(new IdentityHashMap<View, Drawable>());

    // What each band is showing because we painted it. Null means the app owns that band, so the icons
    // have to be judged against the app's pixels instead -- see the ink backdrops below.
    private static Integer sStatusBarColour;
    private static Integer sNavBarColour;

    // What each bar's icons have to be readable against, recorded with the decision that put the band
    // there. Reading the display is what it costs to answer that, so it is asked once per decision
    // and every icon that needs judging reads this instead. It is not the same question as "what did
    // we paint": a bar that stands down for an immersive screen paints nothing and still owes its
    // icons an answer, because they are then drawn on the app's own pixels.
    private static Integer sStatusInkBackdrop;
    private static Integer sNavInkBackdrop;

    // Whether a band's own pixels moved during the pass. The icons only have to be re-decided when
    // they did: re-driving them walks the whole bar and costs 12-47 ms of the main thread per pass,
    // which is what made the ink visibly lag behind the colour.
    private static boolean sMoved;

    /**
     * The moment SystemUI laid a bar out: that is the only time its view, and the bands' own heights,
     * are known.
     *
     * @return which bar this view is, so the caller has to recognise the name once.
     */
    static int remember(View bar) {
        int which = BarViews.namedAs(bar);
        if (which == BarViews.STATUS) {
            sStatusBarView = new WeakReference<>(bar);
        } else if (which == BarViews.NAV) {
            sNavBarView = new WeakReference<>(bar);
        }
        return which;
    }

    /** The status bar view, so the icon side can read the views inside it rather than wait to be called. */
    static View statusBar() {
        return held(sStatusBarView);
    }

    static View navBar() {
        return held(sNavBarView);
    }

    /**
     * Whether our colour is what the bar's band is showing right now, and which colour that is.
     * Anything that wants to paint the band has to agree with the pixels the user actually sees, so it
     * asks here instead of re-reading the flags or the configured value, neither of which is the band
     * once auto colour is on. Judging an icon asks {@link #judgedInk(int)} instead: an icon still has
     * something to read against when the band is the app's.
     */
    static Integer statusBarColour() {
        return sStatusBarColour;
    }

    static Integer navBarColour() {
        return sNavBarColour;
    }

    static void setColours(Integer status, Integer nav) {
        sStatusBarColour = status;
        sNavBarColour = nav;
    }

    /**
     * What the icons of one bar really are drawn on: our band when we paint it, the app's own pixels
     * showing through when we stand down. A stand-down still leaves the icons needing ink that reads
     * against those pixels -- BYD does not re-decide a bitmap key once it has tinted one, so a stale
     * white filter survives onto a light home screen.
     *
     * This is the answer the last decision recorded, never a fresh capture: every icon in the bar asks
     * here, and a display read per icon is what scrolling would then cost.
     */
    static void setInkBackdrops(Integer status, Integer nav) {
        sStatusInkBackdrop = status;
        sNavInkBackdrop = nav;
    }

    static Integer statusInkBackdrop() {
        return sStatusInkBackdrop;
    }

    static Integer navInkBackdrop() {
        return sNavInkBackdrop;
    }

    /**
     * The colour one bar's icons have to contrast to, already blended for a translucent band, or null
     * when that bar has nothing to judge against.
     */
    static Integer judgedInk(int bar) {
        return BarContrast.bandToJudge(bar == BarViews.NAV ? sNavInkBackdrop : sStatusInkBackdrop);
    }

    /**
     * Whether this view is a band this module paints. Its colour is then ours, and an icon's judgement
     * has to be made against it rather than standing down because a plate is behind the icon -- a
     * saturated band colour reads as a plate to any test that only looks at the colour.
     */
    static boolean isBandView(View view) {
        return sOriginals.containsKey(view);
    }

    /** A pass is starting, so "did a band move" has to be answered from this pass alone. */
    static void beginPass() {
        sMoved = false;
    }

    /** @return whether a band's own pixels changed during this pass. */
    static boolean moved() {
        return sMoved;
    }

    /**
     * Puts {@code colour} on a band, or -- when {@code colour} is null -- the colour it carried before
     * we ever touched it. Setting a background costs the icons a re-decision, so a band that already
     * shows what was decided for it is left alone, and says so.
     */
    static void putColour(View band, Integer colour) {
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
        sMoved = true;
    }

    static Integer paintColour(View band, Integer colour) {
        putColour(band, colour);
        return colour;
    }

    static Integer restore(View band) {
        putColour(band, null);
        return null;
    }

    /**
     * Put the colours already decided back on the bars. Used by a layout pass, where the band views can
     * be new ones that have never carried our colour.
     */
    static void paintAgain() {
        putColour(navBandView(), sNavBarColour);
        putColour(navPlateView(), sNavBarColour);
        putColour(statusBar(), sStatusBarColour);
    }

    /**
     * The colour of the couple of rows of screen touching the bar, i.e. the app's own edge. Reading
     * the display is the only way to it: an app that does not draw behind a bar keeps its pixels to
     * itself, and nothing SystemUI is told about that window names its colours.
     */
    static Integer edgeColour(View bar, boolean below) {
        int[] location = new int[2];
        bar.getLocationOnScreen(location);
        int top = below ? location[1] + bar.getHeight() : location[1] - EDGE_ROWS;
        if (top < 0 || bar.getWidth() <= 0) {
            return null;
        }
        return ScreenEdgeSampler.dominantColour(0, top, bar.getWidth(), top + EDGE_ROWS);
    }

    /**
     * The band the user picks has to sit behind the keys, and on this fork the container the keys are
     * laid out in is drawn above its own background -- measured, an opaque colour on it hides every
     * icon in the bar. The parent it is added to is not, so the band goes there.
     *
     * Dlink 3.0/4.0 hands over the window's own frame instead, which is behind the keys but has an
     * opaque plate drawn above it, so the frame alone is not enough -- see {@link #navPlateView()}.
     */
    static View navBandView() {
        View bar = navBar();
        if (bar == null) {
            return null;
        }
        if (BarViews.NAV_BAR_VIEW_34.equals(BarViews.entryName(bar))) {
            return bar;
        }
        ViewParent parent = bar.getParent();
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
    static View navPlateView() {
        View bar = navBar();
        if (bar == null || !BarViews.NAV_BAR_VIEW_34.equals(BarViews.entryName(bar))) {
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

    private static View held(WeakReference<View> reference) {
        return reference == null ? null : reference.get();
    }

    private BarBands() {
    }
}
