package com.byd.dlink.customer.hook;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
 * The ROM already owns a light/dark pair for both bars, so the module never supplies an icon colour: it
 * keeps whichever of the ROM's two candidates contrasts better against the band we are painting.
 *
 * The pick is made by rewriting what the ROM asked for, not by painting over its result. A nav item's
 * colour arrives as a resource id handed to BaseNaviItem.updateLottieAnimationView(int), and that method
 * has two branches -- a Lottie colour filter for animation items and ImageView.setColorFilter for the
 * rest -- plus an asset swap when the id is the night one. Overwriting the id covers all of them with
 * the ROM's own code; attaching our own Lottie callback covered only the first.
 *
 * Both bars are also re-decided whenever the band changes hands, which is why reapply() exists: BYD asks
 * for a colour once, when the item is bound, before SystemUI has settled which app owns the screen, so a
 * decision taken there would otherwise be the only one ever taken. Most of the nav bar never asks again
 * at all -- measured, a whole boot produced one colour call for it -- so that re-decision walks the
 * views inside the bar instead of waiting to be called, which is {@link #pullNavInk()}.
 */
public class BarIconColourHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String DARK_ICON_DISPATCHER =
            "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl";
    private static final String BASE_NAVI_ITEM =
            "com.android.systemui.statusbar.phone.navigationbar.items.BaseNaviItem";
    private static final String NAV_ICON_DAY = "nav_bar_icon_day";
    private static final String NAV_ICON_NIGHT = "nav_bar_icon_night";
    private static final String NAV_COLOR_METHOD = "updateLottieAnimationView";
    private static final String KEY_ITEM_ICON = "mNaviItemIcon";
    private static final String KEY_ORIGINAL_APP_ICON = "mUsingOriginalAppIcon";
    private static final String STATUS_BAR_VIEW = "status_bar";
    private static final String NAV_BAR_VIEW = "byd_custom_nav_bar";

    /**
     * How far a colour may stray from grey and still count as bar ink. BYD's own icon greys sit well
     * below this ({@code #262d33} spans 13, {@code #343a3e} spans 10) while the colours that carry
     * meaning -- the blue of an active nav item, the HI/LO temperature accents -- span over 100.
     */
    private static final int MAX_INK_SPREAD = 0x40;

    /**
     * Below this the band is mostly whatever is behind it, and no colour we can name is what the
     * user is looking at -- so the ROM's own day/night signal is left alone.
     */
    private static final int MIN_BAND_ALPHA = 0x66;
    private static final int NEUTRAL_BACKDROP = 0xFF808080;

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

    private static int sDayId;
    private static int sNightId;

    // Cache for hasOwnOrParentBackground() results - view backgrounds rarely change
    private static final Map<View, Boolean> sBackgroundCache =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    // Cache for inkOf() results - ImageView color filters rarely change
    private static final Map<ImageView, Integer> sInkCache =
            Collections.synchronizedMap(new WeakHashMap<ImageView, Integer>());

    // Cache for inkId() results - band values change infrequently
    private static final Map<Integer, Integer> sInkIdCache =
            Collections.synchronizedMap(new WeakHashMap<Integer, Integer>());

    // Cache for bandToJudge() results - painted colors change infrequently
    private static final Map<Integer, Integer> sBandJudgeCache =
            Collections.synchronizedMap(new WeakHashMap<Integer, Integer>());

    // Debounce: last reapply() timestamp to avoid redundant work
    private static long sLastReapplyTime = 0;
    private static final long REAPPLY_DEBOUNCE_MS = 100;

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
     * SystemUI's own distribution loop carries our answer. Standing the band down needs no undo, because
     * the tint is recomputed by the ROM's applyDarkIntensity on the window change that made the app
     * immersive, and this hook then leaves it alone.
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
     * once and never revisits is covered by {@link #pullNavInk()} instead.
     *
     * Neutral colours only. A saturated one is BYD telling the user something -- the blue of the active
     * nav item, the HI/LO temperature accents -- and a band judgement must not overrule that. The alpha
     * BYD asked with is kept, so a deliberately dimmed icon stays dimmed, only in the right colour.
     */
    private static void hookBarInk() {
        XC_MethodHook inkHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                int requested = ((Integer) param.args[0]).intValue();
                if (barOf(view) != BAR_NAV || !isInk(requested)) {
                    return;
                }
                Integer ink = inkColour(view, BAR_NAV, requested);
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
     * The other half of the same job, and the half the setter hook cannot do: BYD colours most of the
     * bar once, while inflating it, and never calls a setter again -- measured, a whole SystemUI boot
     * produced exactly one ink call for the nav bar, from a key that was being rebound. So the bar is
     * read instead of waited for: every view under it is asked what it draws with now, and a view that
     * answers with ink the band cannot be read against, or with no decision of its own at all, is given
     * the tone the band needs.
     *
     * Nothing is assumed about which family a view belongs to, which is the point -- the bar is
     * user-rearrangeable, and the buttons in it are only limited by what the owner chooses to put there.
     */
    private static void pullNavInk() {
        View bar = SystemBarBackgroundHook.navBarView();
        Integer band = bandOf(BAR_NAV);
        if (bar == null || band == null) {
            return;
        }
        // Clear caches before walking - view tree may have changed
        sBackgroundCache.clear();
        sInkCache.clear();
        sInkIdCache.clear();
        sBandJudgeCache.clear();

        Resources resources = bar.getResources();
        int id = inkId(resources, band.intValue());
        if (id == 0) {
            return;
        }
        pullInk(bar, band.intValue(), resources.getColor(id));
    }

    /**
     * BYD colours an item's icon by putting a filter on the image view inside it -- but only when it
     * rebinds that key, so an icon nobody has rebound since SystemUI started still draws with whatever
     * its asset carries, which is white. The item's own state says whether that is a decision: an item
     * showing a real app icon is BYD keeping that icon's colours, so it is left alone, and one whose
     * icon carries no colour decision at all is given the tone the band needs.
     */
    private static void giveIconItsTone(View item, int target) {
        try {
            if (XposedHelpers.getBooleanField(item, KEY_ORIGINAL_APP_ICON)) {
                return;
            }
            Object icon = XposedHelpers.getObjectField(item, KEY_ITEM_ICON);
            if (icon instanceof ImageView && inkOf((ImageView) icon) == 0) {
                ((ImageView) icon).setColorFilter(target);
            }
        } catch (Throwable t) {
            // Not an item that carries these; the walk simply moves on.
        }
    }

    private static void pullInk(View view, int band, int target) {
        if (sBaseItem != null && sBaseItem.isInstance(view)) {
            giveIconItsTone(view, target);
        }
        if (view instanceof TextView) {
            if (!hasOwnOrParentBackground(view)) {
                ColorStateList colours = ((TextView) view).getTextColors();
                int current = colours == null ? 0 : colours.getDefaultColor();
                Integer ink = betterInk(current, band, target);
                if (ink != null) {
                    ((TextView) view).setTextColor(ink);
                }
            }
        } else if (view instanceof ImageView) {
            // Icons inside buttons with distinct backgrounds (e.g. the rightmost app-list button)
            // still need their icon colour adjusted — only the button background is BYD's design.
            int current = inkOf((ImageView) view);
            Integer ink = betterInk(current, band, target);
            if (ink != null) {
                ((ImageView) view).setColorFilter(ink);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                pullInk(group.getChildAt(i), band, target);
            }
        }
    }

    /**
     * Whether the view or any of its ancestors carries a background that should be left alone.
     * A nav item with a distinct background (the rightmost app-list button, for example) is BYD's
     * own design and must not be recoloured by the band contrast logic.
     */
    private static boolean hasOwnOrParentBackground(View view) {
        Boolean cached = sBackgroundCache.get(view);
        if (cached != null) {
            return cached;
        }
        android.graphics.drawable.Drawable bg = view.getBackground();
        if (bg != null && !isInkBackground(bg)) {
            sBackgroundCache.put(view, true);
            return true;
        }
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = ((View) parent).getParent()) {
            View parentView = (View) parent;
            android.graphics.drawable.Drawable parentBg = parentView.getBackground();
            if (parentBg != null && !isInkBackground(parentBg)) {
                sBackgroundCache.put(view, true);
                return true;
            }
        }
        sBackgroundCache.put(view, false);
        return false;
    }

    /** @return whether the drawable is an ink-coloured background we're allowed to recolor over. */
    private static boolean isInkBackground(android.graphics.drawable.Drawable drawable) {
        if (drawable instanceof android.graphics.drawable.ColorDrawable) {
            int colour = ((android.graphics.drawable.ColorDrawable) drawable).getColor();
            return Color.alpha(colour) <= 0x40 || isInk(colour);
        }
        return false;
    }

    /** @return the colour to draw with instead of {@code current}, or null if {@code current} reads better. */
    private static Integer betterInk(int current, int band, int target) {
        if (current == 0 || !isInk(current)
                || contrast(band, current) >= contrast(band, target)) {
            return null;
        }
        return Color.argb(Color.alpha(current), Color.red(target), Color.green(target),
                Color.blue(target));
    }

    /** What an image is being drawn with, from whichever of the two mechanisms BYD used. */
    private static int inkOf(ImageView image) {
        Integer cached = sInkCache.get(image);
        if (cached != null) {
            return cached;
        }
        ColorFilter filter = image.getColorFilter();
        for (Class<?> owner = filter == null ? null : filter.getClass();
                owner != null; owner = owner.getSuperclass()) {
            try {
                Field colour = owner.getDeclaredField("mColor");
                colour.setAccessible(true);
                int result = colour.getInt(filter);
                sInkCache.put(image, result);
                return result;
            } catch (Throwable ignored) {
                // Not a colour-carrying filter of this shape; the tint is the other place to look.
            }
        }
        ColorStateList tint = image.getImageTintList();
        int result = tint == null ? 0 : tint.getDefaultColor();
        sInkCache.put(image, result);
        return result;
    }

    /** @return the colour to use instead of {@code requested}, or null to let BYD's value stand. */
    private static Integer inkColour(View view, int bar, int requested) {
        Integer band = bandOf(bar);
        if (band == null) {
            return null;
        }
        Resources resources = view.getResources();
        int id = inkId(resources, band.intValue());
        if (id == 0) {
            return null;
        }
        int pick = resources.getColor(id);
        return Color.argb(Color.alpha(requested), Color.red(pick), Color.green(pick),
                Color.blue(pick));
    }

    private static final int BAR_NONE = 0;
    private static final int BAR_NAV = 1;
    private static final int BAR_STATUS = 2;

    /**
     * Which bar a view belongs to, found by walking up to the bar container that holds it. The walk is
     * what keeps this independent of which button it is: anything in the bar, ours or another module's,
     * hangs off the same container.
     */
    private static int barOf(View view) {
        for (View current = view; current != null; ) {
            String name = entryName(current);
            if (NAV_BAR_VIEW.equals(name)) {
                return BAR_NAV;
            }
            if (STATUS_BAR_VIEW.equals(name)) {
                return BAR_STATUS;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return BAR_NONE;
    }

    /** The colour bar ink has to be readable against, or null while that bar is not ours to paint. */
    private static Integer bandOf(int bar) {
        return bandToJudge(bar == BAR_NAV
                ? SystemBarBackgroundHook.navBarColour()
                : SystemBarBackgroundHook.statusBarColour());
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

    /** Whether {@code colour} is bar ink rather than one of BYD's meaning-carrying accents. */
    private static boolean isInk(int colour) {
        int red = Color.red(colour), green = Color.green(colour), blue = Color.blue(colour);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue))
                <= MAX_INK_SPREAD;
    }

    /**
     * Re-make both bars' icon decisions for the state the background hook has just settled. Re-driving an
     * item with the id BYD itself asked for is idempotent: the hook above records that same id again and
     * then rewrites it, so no remembered value can drift toward ours.
     */
    static void reapply() {
        long now = System.currentTimeMillis();
        if (now - sLastReapplyTime < REAPPLY_DEBOUNCE_MS) {
            return;
        }
        sLastReapplyTime = now;

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
                XposedHelpers.callMethod(entry.getKey(), "updateLottieAnimationView",
                        entry.getValue().intValue());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": nav icon re-drive failed: " + t);
            }
        }
        pullNavInk();
    }

    /** @return whether the tint the ROM computed was replaced with our contrast pick. */
    private static boolean writeIconTint(Object dispatcher) {
        Integer band = bandToJudge(SystemBarBackgroundHook.statusBarColour());
        if (band == null || sIconTint == null || sLightTone == null || sDarkTone == null) {
            return false;
        }
        try {
            int light = sLightTone.getInt(dispatcher);
            int dark = sDarkTone.getInt(dispatcher);
            sIconTint.setInt(dispatcher, contrast(band, dark) >= contrast(band, light) ? dark : light);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status icon contrast failed: " + t);
            return false;
        }
    }

    /** The id to ask the ROM for, or the one it asked for itself when there is nothing to judge. */
    private static int navColorId(int requested, View item) {
        Integer band = bandToJudge(SystemBarBackgroundHook.navBarColour());
        int id = band == null ? 0 : inkId(item.getResources(), band.intValue());
        return id == 0 ? requested : id;
    }

    /**
     * @return whichever of BYD's two bar ink colours reads better on {@code band}, or 0 when the pair
     *         cannot be found. The values, not the names, are what is judged, so a ROM that ships the
     *         same colours under different names still decides the same way.
     */
    private static int inkId(Resources resources, int band) {
        Integer cached = sInkIdCache.get(band);
        if (cached != null) {
            return cached;
        }
        if (sDayId == 0 || sNightId == 0) {
            sDayId = resources.getIdentifier(NAV_ICON_DAY, "color", SYSTEM_UI_PACKAGE);
            sNightId = resources.getIdentifier(NAV_ICON_NIGHT, "color", SYSTEM_UI_PACKAGE);
        }
        if (sDayId == 0 || sNightId == 0) {
            return 0;
        }
        int day = resources.getColor(sDayId);
        int night = resources.getColor(sNightId);
        int result = contrast(band, night) > contrast(band, day) ? sNightId : sDayId;
        sInkIdCache.put(band, result);
        return result;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * The colour an icon has to be readable against, or null when there is nothing to judge. What we
     * paint is only half of it: a translucent band lets the app behind it show through, so the colour
     * is judged as the blend the eye sees rather than as the value that was set.
     */
    private static Integer bandToJudge(Integer painted) {
        if (painted == null) {
            return null;
        }
        Integer cached = sBandJudgeCache.get(painted);
        if (cached != null) {
            return cached;
        }
        if (Color.alpha(painted.intValue()) < MIN_BAND_ALPHA) {
            return null;
        }
        int configured = painted.intValue();
        float fraction = Color.alpha(configured) / 255f;
        int channel = (int) (fraction * Color.red(configured)
                + (1f - fraction) * Color.red(NEUTRAL_BACKDROP) + 0.5f);
        int green = (int) (fraction * Color.green(configured)
                + (1f - fraction) * Color.green(NEUTRAL_BACKDROP) + 0.5f);
        int blue = (int) (fraction * Color.blue(configured)
                + (1f - fraction) * Color.blue(NEUTRAL_BACKDROP) + 0.5f);
        int result = Color.rgb(channel, green, blue);
        sBandJudgeCache.put(painted, result);
        return result;
    }

    private static float luminance(int color) {
        return 0.2126f * channel(Color.red(color))
                + 0.7152f * channel(Color.green(color))
                + 0.0722f * channel(Color.blue(color));
    }

    private static float channel(int value) {
        float linear = value / 255f;
        return linear <= 0.03928f ? linear / 12.92f : (float) Math.pow((linear + 0.055f) / 1.055f, 2.4f);
    }

    private static float contrast(int background, int foreground) {
        float light = Math.max(luminance(background), luminance(foreground));
        float dark = Math.min(luminance(background), luminance(foreground));
        return (light + 0.05f) / (dark + 0.05f);
    }
}
