package com.byd.dlink.customer.hook;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.byd.dlink.customer.utils.PrefsManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps the icons of both bars readable against whatever their band is showing.
 *
 * The ROM already owns a light/dark ink pair for each bar, so the module never supplies an icon
 * colour: it keeps whichever of the ROM's two candidates contrasts better against the band. The maths
 * of that judgement lives in {@link BarContrast}, the ROM's own pair in {@link BarInkPalette}, what a
 * view is drawn with in {@link BarIconReader}, and the walk that re-inks a whole bar in
 * {@link BarIconWalker}.
 *
 * What this class holds is where the ROM is reached. The pick is made by rewriting what the ROM asked
 * for, not by painting over its result: a nav item's colour arrives as a resource id handed to
 * {@code BaseNaviItem.updateLottieAnimationView(int)}, and that method has two branches -- a Lottie
 * colour filter for animation items and {@code ImageView.setColorFilter} for the rest -- plus an asset
 * swap when the id is the night one. Overwriting the id covers all of them with the ROM's own code;
 * attaching our own Lottie callback covered only the first.
 *
 * Both bars are also re-decided whenever the band changes hands, which is why {@link #reapply()}
 * exists: BYD asks for a colour once, when the item is bound, before SystemUI has settled which app
 * owns the screen, so a decision taken there would otherwise be the only one ever taken. Most of the
 * nav bar never asks again at all -- measured, a whole boot produced one colour call for it -- so that
 * re-decision walks the views inside the bar instead of waiting to be called.
 */
public class BarIconColourHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String DARK_ICON_DISPATCHER =
            "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl";
    private static final String BASE_NAVI_ITEM =
            "com.android.systemui.statusbar.phone.navigationbar.items.BaseNaviItem";
    private static final String NAV_COLOR_METHOD = "updateLottieAnimationView";

    // These three are ints in the ROM, so they are read and written as ints rather than as objects.
    private static Field sIconTint;
    private static Field sLightTone;
    private static Field sDarkTone;
    private static WeakReference<Object> sDispatcher;

    // Each item's own colour id, exactly as BYD asked for it, so standing down can hand it back.
    private static final Map<Object, Integer> sRomNavColorIds =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    private static Class<?> sBaseItem;
    private static XC_MethodHook sColourHook;

    // Item classes already looked at for their own copy of the colour setter, so the search runs once
    // per class rather than on every colour call.
    private static final Set<Class<?>> sOverrideAttempts =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI_PACKAGE.equals(lpparam.packageName)
                || (!PrefsManager.isStatusBarBgEnabled() && !PrefsManager.isNavBarBgEnabled())) {
            return;
        }
        hookStatusIconTint(lpparam.classLoader);
        hookNavigationIconColour(lpparam.classLoader);
        hookBarInk();
    }

    /**
     * mIconTint is the only thing that reaches a status icon: applyIconTint reads it for every receiver
     * already registered, and the ones added later read it too. Writing it before that method runs means
     * SystemUI's own distribution loop carries our answer. This is also the only lever an immersive screen
     * leaves the status bar with: the day/night pair it ships holds the same colour twice, so the walk
     * can only re-ink through the tint. Standing the band down needs no undo, because the tint is
     * recomputed by the ROM's applyDarkIntensity on the window change that made the app immersive -- and
     * from then on it is judged against the app's pixels, not left to whatever the ROM guessed.
     */
    private static void hookStatusIconTint(ClassLoader classLoader) {
        try {
            Class<?> dispatcher = XposedHelpers.findClass(DARK_ICON_DISPATCHER, classLoader);
            sIconTint = field(dispatcher, "mIconTint");
            sLightTone = field(dispatcher, "mLightModeIconColorSingleTone");
            sDarkTone = field(dispatcher, "mDarkModeIconColorSingleTone");
            XposedBridge.hookAllMethods(dispatcher, "applyIconTint", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sDispatcher = new WeakReference<>(param.thisObject);
                    writeIconTint(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": status icon contrast installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status icon contrast not installed: " + t);
        }
    }

    /**
     * Every nav item takes its colour through here, by whichever route SystemUI reached it, and the
     * argument it is given is a colour resource id -- the same currency the ROM's own day/night pair is
     * written in. So the answer is handed back as an id and BYD's method does the rest, including the
     * ImageView branch and the night asset swap that a Lottie-only callback would have walked past.
     */
    private static void hookNavigationIconColour(ClassLoader classLoader) {
        try {
            Class<?> item = XposedHelpers.findClass(BASE_NAVI_ITEM, classLoader);
            sBaseItem = item;
            BarIconWalker.setBaseItem(item);
            sColourHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int requested = (Integer) param.args[0];
                    sRomNavColorIds.put(param.thisObject, requested);
                    param.args[0] = navColorId(requested, (View) param.thisObject);
                    hookSubclassOverrides(param.thisObject.getClass());
                }
            };
            XposedHelpers.findAndHookMethod(item, NAV_COLOR_METHOD, int.class, sColourHook);
            XposedBridge.log(TAG + ": nav icon contrast installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": nav icon contrast not installed: " + t);
        }
    }

    /**
     * An item that overrides the colour setter and then does its own work with the id it was handed
     * cannot be fixed from the base implementation alone: {@code DualPipItem} calls super first and
     * afterwards paints its own {@code setColorFilter} over the result, so only the base call sees our
     * rewrite and the key keeps BYD's original colour. The base hook fires on every item, including
     * those, so the concrete class it reports is how the overriders are found -- no list of BYD's item
     * class names to keep in step with the ROM. Hooking mid-call does not change the call in flight,
     * which is why the answer still arrives through {@link #reapply()}.
     */
    private static void hookSubclassOverrides(Class<?> concrete) {
        for (Class<?> current = concrete;
                current != null && !current.equals(sBaseItem);
                current = current.getSuperclass()) {
            if (!sOverrideAttempts.add(current)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(
                        current.getDeclaredMethod(NAV_COLOR_METHOD, int.class), sColourHook);
                XposedBridge.log(TAG + ": nav icon contrast installed on " + current.getName());
            } catch (NoSuchMethodException e) {
                // Inherits the base implementation, which is already hooked.
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": nav icon override not hooked on "
                        + current.getName() + ": " + t);
            }
        }
    }

    /**
     * The bar is user-rearrangeable and each family colours itself differently -- {@code BaseNaviItem}
     * by a resource id, {@code DualPipItem} by a filter of its own, the air-conditioning cluster through
     * {@code AcBarImageView} and {@code AcBarTextView} -- so a per-class fix can never keep up with the
     * buttons the owner chooses to put in. These two framework calls are where the recolouring happens
     * when BYD decides one on its own, e.g. the moment an air-conditioning key is pressed: rewriting the
     * argument keeps that in step with the band without a flash of the wrong tone first. What BYD decides
     * once and never revisits is covered by {@link BarIconWalker#pullBarInk(int)} instead.
     *
     * Neutral colours only. A saturated one is BYD telling the user something -- the blue of the active
     * nav item, the HI/LO temperature accents -- and a band judgement must not overrule that. The alpha
     * BYD asked with is kept, so a deliberately dimmed icon stays dimmed, only in the right colour.
     * An ink BYD asks for over a picture icon is refused outright: that call is what flattens the tile
     * into a blob, and the tile has nothing to gain from the band.
     */
    private static void hookBarInk() {
        XC_MethodHook inkHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                int requested = ((Integer) param.args[0]).intValue();
                if (BarViews.barOf(view) != BarViews.NAV || !BarContrast.isInk(requested)) {
                    return;
                }
                if (view instanceof ImageView
                        && BarIconReader.isPictureIcon(((ImageView) view).getDrawable())) {
                    param.setResult(null);
                    return;
                }
                Integer ink = BarIconWalker.inkColour(view, BarViews.NAV, requested);
                if (ink != null) {
                    param.args[0] = ink;
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", int.class, inkHook);
            XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", int.class, inkHook);
            XposedBridge.log(TAG + ": bar ink contrast installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bar ink contrast not installed: " + t);
        }
    }

    /**
     * Re-make both bars' icon decisions for the state the background hook has just settled. Re-driving an
     * item with the id BYD itself asked for is idempotent: the hook above records that same id again and
     * then rewrites it, so no remembered value can drift toward ours.
     */
    static void reapply() {
        // The walks read the view tree as it is now, so nothing carried over from the last decision.
        BarContrast.clearCaches();
        BarInkPalette.clearCaches();
        BarIconReader.clearCaches();
        Object dispatcher = sDispatcher == null ? null : sDispatcher.get();
        if (dispatcher != null && writeIconTint(dispatcher)) {
            try {
                XposedHelpers.callMethod(dispatcher, "applyIconTint");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": status tint re-drive failed: " + t);
            }
        }
        List<Map.Entry<Object, Integer>> items;
        synchronized (sRomNavColorIds) {
            items = new ArrayList<>(sRomNavColorIds.entrySet());
        }
        for (Map.Entry<Object, Integer> entry : items) {
            try {
                XposedHelpers.callMethod(entry.getKey(), NAV_COLOR_METHOD,
                        entry.getValue().intValue());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": nav icon re-drive failed: " + t);
            }
        }
        BarIconWalker.pullBarInk(BarViews.NAV);
        BarIconWalker.pullBarInk(BarViews.STATUS);
    }

    /** @return whether the tint the ROM computed was replaced with our contrast pick. */
    private static boolean writeIconTint(Object dispatcher) {
        Integer band = BarBands.judgedInk(BarViews.STATUS);
        if (band == null || sIconTint == null || sLightTone == null || sDarkTone == null) {
            return false;
        }
        try {
            int light = sLightTone.getInt(dispatcher);
            int dark = sDarkTone.getInt(dispatcher);
            sIconTint.setInt(dispatcher, BarContrast.contrast(band, dark)
                    >= BarContrast.contrast(band, light) ? dark : light);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status icon contrast failed: " + t);
            return false;
        }
    }

    /** The id to ask the ROM for, or the one it asked for itself when there is nothing to judge. */
    private static int navColorId(int requested, View item) {
        Integer band = BarBands.judgedInk(BarViews.NAV);
        int id = band == null ? 0
                : BarInkPalette.inkId(item.getResources(), band.intValue(), BarViews.NAV);
        return id == 0 ? requested : id;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** What the ink decision sees for each bar, for the view-tree dump. */
    static String inkReport() {
        return inkReportOf("nav", BarViews.NAV) + inkReportOf("status", BarViews.STATUS);
    }

    private static String inkReportOf(String label, int bar) {
        Integer judged = BarBands.judgedInk(bar);
        StringBuilder text = new StringBuilder(" ").append(label).append(" ink=")
                .append(colour(judged));
        View content = bar == BarViews.NAV ? BarBands.navBar() : BarBands.statusBar();
        Resources resources = content == null ? null : content.getResources();
        if (resources == null) {
            return text.append(" bar=none").toString();
        }
        int[] pair = BarInkPalette.inkPair(resources, bar);
        if (pair[0] == 0 || pair[1] == 0) {
            return text.append(" pair=none").toString();
        }
        if (judged == null) {
            return text.append(" band=none").toString();
        }
        int chosen = BarInkPalette.inkId(resources, judged.intValue(), bar);
        return text.append(" day=").append(colour(resources.getColor(pair[0])))
                .append(" night=").append(colour(resources.getColor(pair[1])))
                .append(" chosen=").append(chosen == pair[1] ? "night" : "day").toString();
    }

    /**
     * Per-image trace of the walk, for the view-tree dump: what each image in the nav bar is asked to
     * draw with, what the walk decides, and whether the colour is still on the drawable right after it
     * is handed over. That last one is the question the dump of a Dlink 3.0/4.0 bar cannot answer by
     * itself -- a key that reports a light ink while the pixels stay dark is a key whose drawable
     * refused the colour, not a key the walk missed.
     */
    static void diagnose() {
        View content = BarBands.navBar();
        Integer band = BarBands.judgedInk(BarViews.NAV);
        if (content == null || band == null) {
            XposedBridge.log(TAG + ": ink diagnose: no nav bar to walk");
            return;
        }
        Resources resources = content.getResources();
        int id = BarInkPalette.inkId(resources, band.intValue(), BarViews.NAV);
        int target = id == 0 ? 0 : resources.getColor(id);
        XposedBridge.log(TAG + ": ink diagnose nav band=" + colour(band)
                + " target=" + (target == 0 ? "none" : colour(Integer.valueOf(target))));
        diagnoseWalk(content, band.intValue(), target, new int[1]);
    }

    private static void diagnoseWalk(View view, int band, int target, int[] seen) {
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            Drawable drawn = image.getDrawable();
            if (seen[0]++ < 24) {
                int before = BarIconReader.inkOf(image, drawn);
                String skip = BarIconReader.isPictureIcon(drawn) ? "picture"
                        : BarIconReader.hasOwnOrParentBackground(image) ? "plate" : null;
                Integer ink = skip == null ? BarContrast.betterInk(before, band, target) : null;
                String stuck = "not applied";
                if (ink != null) {
                    BarIconWalker.paintIcon(image, drawn, ink.intValue());
                    ColorFilter now = drawn == null ? null : drawn.getColorFilter();
                    int nowColour = now == null ? 0 : BarIconReader.colourOf(now);
                    stuck = "filter now " + (nowColour == 0 ? "none"
                            : colour(Integer.valueOf(nowColour)))
                            + " tint now " + tintText(drawn);
                }
                XposedBridge.log(TAG + ": ink " + BarViews.entryName(view) + " "
                        + view.getClass().getSimpleName() + " vis=" + view.getVisibility()
                        + " size=" + view.getWidth() + 'x' + view.getHeight()
                        + " drw=" + (drawn == null ? "none" : drawn.getClass().getSimpleName())
                        + " was=" + (before == 0 ? "none" : colour(Integer.valueOf(before)))
                        + " skip=" + (skip == null ? "none" : skip)
                        + " put=" + (ink == null ? "none" : colour(Integer.valueOf(ink.intValue())))
                        + " " + stuck);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                diagnoseWalk(group.getChildAt(i), band, target, seen);
            }
        }
    }

    private static String tintText(Drawable drawn) {
        ColorStateList tint = BarIconReader.tintOf(drawn);
        if (tint == null) {
            return "none";
        }
        return String.format("#%06X>[%06X", tint.getDefaultColor() & 0xFFFFFF,
                BarIconReader.tintColour(tint, drawn) & 0xFFFFFF);
    }

    private static String colour(Integer value) {
        return value == null ? "none" : String.format("#%06X", value.intValue() & 0xFFFFFF);
    }
}
