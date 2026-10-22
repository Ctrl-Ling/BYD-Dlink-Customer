package com.byd.dlink.customer.hook;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
 * views inside the bar instead of waiting to be called, which is {@link #pullBarInk(int)}.
 */
public class BarIconColourHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String DARK_ICON_DISPATCHER =
            "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl";
    private static final String BASE_NAVI_ITEM =
            "com.android.systemui.statusbar.phone.navigationbar.items.BaseNaviItem";
    // The two ink colours each bar ships with, by name. Which names those are is a per-fork question
    // -- the newer panels name them after the nav bar, Dlink 3.0/4.0 after the air-conditioning
    // readout the keys and the temperature labels share -- so each bar is asked of a list, first pair
    // found wins. The values are what gets judged, never the names.
    private static final String[] NAV_INK_NAMES = {
            "nav_bar_icon_day", "nav_bar_icon_night",
            "byd_nav_bar_temp_color", "byd_nav_bar_temp_color_night",
    };
    private static final String[] STATUS_INK_NAMES = {
            "byd_status_bar_color", "byd_status_bar_color_night",
    };
    private static final String NAV_COLOR_METHOD = "updateLottieAnimationView";
    private static final String KEY_ITEM_ICON = "mNaviItemIcon";
    private static final String KEY_ORIGINAL_APP_ICON = "mUsingOriginalAppIcon";
    private static final String STATUS_BAR_VIEW = "status_bar";
    private static final String NAV_BAR_VIEW = "byd_custom_nav_bar";
    // Dlink 3.0/4.0 has no BYD container to name: its keys are stock KeyButtonViews, and the nav
    // window's own frame is the view they all hang off. Deeper on the newer panels, where the name
    // above is met first, so this one only ever answers for 3.0/4.0.
    private static final String NAV_BAR_VIEW_34 = "navigation_bar_frame";

    /**
     * How far a colour may stray from grey and still count as bar ink. BYD's own icon greys sit well
     * below this ({@code #262d33} spans 13, {@code #343a3e} spans 10) while the colours that carry
     * meaning -- the blue of an active nav item, the HI/LO temperature accents -- span over 100.
     */
    private static final int MAX_INK_SPREAD = 0x40;

    /**
     * Above this share of its own pixels covered, a bitmap is a picture BYD is showing as artwork --
     * a filled tile with something drawn inside it -- rather than a glyph whose strokes are the whole
     * of it. Measured on this ROM: the app-list tile covers 96%, a real app icon 95%, the split-screen
     * key 13%.
     */
    private static final float PICTURE_COVERAGE = 0.5f;

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

    // Each bar's {day, night} ink ids, resolved from the name pairs above the first time a walk needs
    // them, and {0, 0} once it is known this fork names neither.
    private static int[] sNavInkPair;
    private static int[] sStatusInkPair;

    // Cache for hasOwnOrParentBackground() results - view backgrounds rarely change
    private static final Map<View, Boolean> sBackgroundCache =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    // Cache for analyse() results - a bitmap's coverage and colour never change once it is drawn with
    private static final Map<Drawable, int[]> sAssetInk =
            Collections.synchronizedMap(new WeakHashMap<Drawable, int[]>());

    // Cache for inkOf() results - ImageView color filters rarely change
    private static final Map<ImageView, Integer> sInkCache =
            Collections.synchronizedMap(new WeakHashMap<ImageView, Integer>());

    // Cache for inkId() results - band values change infrequently
    private static final Map<Integer, Integer> sInkIdCache =
            Collections.synchronizedMap(new WeakHashMap<Integer, Integer>());

    // Cache for bandToJudge() results - painted colors change infrequently
    private static final Map<Integer, Integer> sBandJudgeCache =
            Collections.synchronizedMap(new WeakHashMap<Integer, Integer>());

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
     * once and never revisits is covered by {@link #pullBarInk(int)} instead.
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
                if (barOf(view) != BAR_NAV || !isInk(requested)) {
                    return;
                }
                if (view instanceof ImageView
                        && isPictureIcon(((ImageView) view).getDrawable())) {
                    param.setResult(null);
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
     * Dlink 3.0/4.0 needs it for both bars: its status bar is BYD's own clock and air-conditioning
     * readout, coloured while it was inflated, and the ROM's dark-icon tint reaches none of it.
     */
    private static void pullBarInk(int bar) {
        View content = bar == BAR_NAV ? SystemBarBackgroundHook.navBarView()
                : SystemBarBackgroundHook.statusBarView();
        Integer band = bandOf(bar);
        if (content == null || band == null) {
            return;
        }
        Resources resources = content.getResources();
        int id = inkId(resources, band.intValue(), bar);
        if (id == 0) {
            return;
        }
        pullInk(content, band.intValue(), resources.getColor(id));
    }

    /**
     * BYD colours an item's icon by putting a filter on the image view inside it -- but only when it
     * rebinds that key, so an icon nobody has rebound since SystemUI started still draws with whatever
     * its asset carries, which is white. The item's own state says whether that is a decision: an item
     * showing a real app icon is BYD keeping that icon's colours, so it is left alone, and one whose
     * icon carries no colour decision at all is given the tone the band needs.
     *
     * BYD's flag only covers the icons it launched from an app, though. The keys it draws from a tile
     * bitmap of its own -- the app-list badge, for one -- carry their message in their artwork, and a
     * single ink colour erases it, so they are stood down the same way and handed back whatever BYD
     * had already flattened them to.
     */
    private static void giveIconItsTone(View item, int target) {
        try {
            Object icon = XposedHelpers.getObjectField(item, KEY_ITEM_ICON);
            if (!(icon instanceof ImageView)
                    || XposedHelpers.getBooleanField(item, KEY_ORIGINAL_APP_ICON)) {
                return;
            }
            ImageView image = (ImageView) icon;
            Drawable drawn = image.getDrawable();
            if (isPictureIcon(drawn)) {
                int current = inkOf(image, drawn);
                if (current != 0 && isInk(current)) {
                    image.setColorFilter((ColorFilter) null);
                }
                return;
            }
            if (inkOf(image, drawn) == 0) {
                image.setColorFilter(target);
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
            ImageView image = (ImageView) view;
            Drawable drawn = image.getDrawable();
            if (!isPictureIcon(drawn) && !hasOwnOrParentBackground(image)) {
                Integer ink = betterInk(inkOf(image, drawn), band, target);
                if (ink != null) {
                    paintIcon(image, drawn, ink.intValue());
                }
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
     * Hand the icon its ink through both mechanisms an image view can draw with, asked of the view
     * rather than of the drawable so a later asset swap carries them over: {@code ImageView} re-applies
     * its tint list to whatever drawable arrives but not its colour filter. The tint is the one with the
     * last word -- measured on Dlink 3.0/4.0, a key whose view carries a light colour filter paints the
     * dark colour of its own tint -- and the filter is what the ROM's own keys and every icon without a
     * tint are set through.
     */
    private static void paintIcon(ImageView image, Drawable drawn, int ink) {
        if (image.getImageTintList() != null || (drawn != null && tintOf(drawn) != null)) {
            image.setImageTintList(ColorStateList.valueOf(ink));
        }
        image.setColorFilter(ink);
    }

    /**
     * Whether an image draws from a bitmap that carries the message in its artwork rather than in its
     * shape -- a filled tile with detail inside it, the way BYD draws the app-list key and every real
     * app icon. Coverage tells the two apart: a glyph is a few strokes over a transparent field, a
     * picture is nearly all ink. Flattening the second to one bar colour erases what it depicts, so it
     * is left to draw itself, the same way an item flagged as showing its original app icon is.
     */
    private static boolean isPictureIcon(Drawable drawable) {
        return drawable instanceof BitmapDrawable && analyse((BitmapDrawable) drawable)[0] == 1;
    }

    /**
     * What an asset paints, read off the asset: how much of its own box it covers, and the colour of
     * what it covers with. Coverage tells a glyph from a picture; the colour is the ink an icon with no
     * filter and no tint of its own is drawn with, which is the only answer BYD's Dlink 3.0/4.0 keys
     * give -- they carry a light tint nobody applies and a dark asset that is what reaches the screen.
     *
     * @return {@code {fills its box? 1 : 0, average colour, or 0 when that colour is one of BYD's
     *         meaning-carrying ones rather than bar ink}}
     */
    private static int[] analyse(BitmapDrawable drawable) {
        int[] cached = sAssetInk.get(drawable);
        if (cached != null) {
            return cached;
        }
        int[] result = new int[]{0, 0};
        Bitmap bitmap = drawable.getBitmap();
        if (bitmap != null && !bitmap.isRecycled()) {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                    bitmap.getWidth(), bitmap.getHeight());
            int covered = 0;
            long weight = 0, red = 0, green = 0, blue = 0;
            for (int i = 0; i < pixels.length; i++) {
                int alpha = pixels[i] >>> 24;
                if (alpha < 0x20) {
                    continue;
                }
                covered++;
                weight += alpha;
                red += (long) (pixels[i] >> 16 & 0xff) * alpha;
                green += (long) (pixels[i] >> 8 & 0xff) * alpha;
                blue += (long) (pixels[i] & 0xff) * alpha;
            }
            result[0] = covered >= pixels.length * PICTURE_COVERAGE ? 1 : 0;
            if (weight > 0) {
                int ink = Color.rgb((int) (red / weight), (int) (green / weight),
                        (int) (blue / weight));
                result[1] = isInk(ink) ? ink : 0;
            }
        }
        sAssetInk.put(drawable, result);
        return result;
    }

    /**
     * Whether the view or any of its ancestors carries a background that should be left alone. A view
     * sitting on a background of its own -- a key drawn on its own plate rather than on the band -- is
     * BYD's own design and must not be recoloured by the band contrast logic.
     */
    private static boolean hasOwnOrParentBackground(View view) {
        Boolean cached = sBackgroundCache.get(view);
        if (cached != null) {
            return cached;
        }
        Drawable bg = view.getBackground();
        if (bg != null && !isInkBackground(bg)) {
            sBackgroundCache.put(view, true);
            return true;
        }
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = ((View) parent).getParent()) {
            View held = (View) parent;
            String name = entryName(held);
            if (NAV_BAR_VIEW.equals(name) || NAV_BAR_VIEW_34.equals(name)
                    || STATUS_BAR_VIEW.equals(name)
                    || SystemBarBackgroundHook.isBandView(held)) {
                // The bar's own colour band, which this module paints. Reading it as a plate behind
                // every icon in the bar stood the whole bar down whenever the user picked a saturated
                // colour -- the pink of a Dlink 3.0/4.0 nav band is not ink, so isInk() said plate.
                break;
            }
            Drawable parentBg = held.getBackground();
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
        if (drawable instanceof android.graphics.drawable.RippleDrawable
                || drawable.getClass().getName().endsWith("Ripple")) {
            // Press feedback, which paints nothing at rest. BYD's keys carry {@code KeyButtonRipple},
            // and that extends {@code Drawable}, not {@code RippleDrawable}, so an instanceof test
            // misses it and reads every stock key in a Dlink 3.0/4.0 bar as a plate of its own.
            return true;
        }
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

    /**
     * What an image is being drawn with, asked in the order the framework applies the mechanisms: a tint
     * overrides a colour filter on the same drawable, and the view's tint is handed to every drawable it
     * is given, so either of those, not a remembered filter, is what reaches the pixels. With neither,
     * the asset's own colour is the answer, and a colourful asset answers with none, which is BYD
     * meaning something by it and is left alone.
     */
    private static int inkOf(ImageView image, Drawable drawable) {
        Integer cached = sInkCache.get(image);
        if (cached != null) {
            return cached;
        }
        int result = tintColour(tintOfDrawable(drawable, image), drawable);
        if (result == 0 && drawable != null) {
            ColorFilter filter = drawable.getColorFilter();
            result = filter == null ? 0 : colourOf(filter);
        }
        if (result == 0 && drawable instanceof BitmapDrawable) {
            result = analyse((BitmapDrawable) drawable)[1];
        }
        sInkCache.put(image, Integer.valueOf(result));
        return result;
    }

    /**
     * The tint the drawable will be drawn with: its own if it carries one, since that is what paints,
     * otherwise the view's, which is applied to the drawable when the asset is handed over.
     * {@code Drawable.getTintList} is hidden, which SystemUI is exempt from asking for; when it still
     * refuses, the view's tint is the only answer left.
     */
    private static ColorStateList tintOfDrawable(Drawable drawable, ImageView image) {
        ColorStateList own = tintOf(drawable);
        if (own != null) {
            return own;
        }
        return image.getImageTintList();
    }

    /** The tint list a drawable carries, read through reflection because the getter is a hidden API. */
    private static ColorStateList tintOf(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        try {
            Object tint = XposedHelpers.callMethod(drawable, "getTintList");
            return tint instanceof ColorStateList ? (ColorStateList) tint : null;
        } catch (Throwable ignored) {
            // No answer from this drawable.
            return null;
        }
    }

    /** The colour a colour filter carries, from whichever class in BYD's hierarchy holds it. */
    private static int colourOf(ColorFilter filter) {
        for (Class<?> owner = filter.getClass(); owner != null; owner = owner.getSuperclass()) {
            try {
                Field colour = owner.getDeclaredField("mColor");
                colour.setAccessible(true);
                return colour.getInt(filter);
            } catch (Throwable ignored) {
                // Not a colour-carrying filter of this shape; keep walking up.
            }
        }
        return 0;
    }

    /**
     * The colour a tint list actually resolves to. BYD's bar ink is a state list whose day and night
     * entries differ, so {@code getDefaultColor} -- the colour of no state at all -- can disagree with
     * what is being drawn: measured on a Dlink 3.0/4.0 key, default #EBF4FE while the drawable's own
     * state painted #243240, which is how a walk that asked the default came to see an icon that was
     * already light and leave a dark one alone.
     */
    private static int tintColour(ColorStateList tint, Drawable drawn) {
        if (tint == null) {
            return 0;
        }
        int fallback = tint.getDefaultColor();
        return drawn == null ? fallback : tint.getColorForState(drawn.getState(), fallback);
    }

    /** @return the colour to use instead of {@code requested}, or null to let BYD's value stand. */
    private static Integer inkColour(View view, int bar, int requested) {
        Integer band = bandOf(bar);
        if (band == null) {
            return null;
        }
        Resources resources = view.getResources();
        int id = inkId(resources, band.intValue(), bar);
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
            if (NAV_BAR_VIEW.equals(name) || NAV_BAR_VIEW_34.equals(name)) {
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

    /** The colour bar ink has to be readable against, or null while that bar has no colour to judge. */
    private static Integer bandOf(int bar) {
        return bandToJudge(bar == BAR_NAV
                ? SystemBarBackgroundHook.navInkBackdrop()
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
        // The walks read the view tree as it is now, so nothing carried over from the last decision.
        sBackgroundCache.clear();
        sInkCache.clear();
        sInkIdCache.clear();
        sBandJudgeCache.clear();
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
        pullBarInk(BAR_NAV);
        pullBarInk(BAR_STATUS);
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
        int id = band == null ? 0 : inkId(item.getResources(), band.intValue(), BAR_NAV);
        return id == 0 ? requested : id;
    }

    /**
     * @return whichever of BYD's two bar ink colours reads better on {@code band}, or 0 when the pair
     *         cannot be found. The values, not the names, are what is judged, so a ROM that ships the
     *         same colours under different names still decides the same way.
     */
    private static int inkId(Resources resources, int band, int bar) {
        Integer cached = sInkIdCache.get(Integer.valueOf(band * 4 + bar));
        if (cached != null) {
            return cached;
        }
        int[] pair = inkPair(resources, bar);
        if (pair[0] == 0 || pair[1] == 0) {
            return 0;
        }
        int day = resources.getColor(pair[0]);
        int night = resources.getColor(pair[1]);
        int result = contrast(band, night) > contrast(band, day) ? pair[1] : pair[0];
        sInkIdCache.put(Integer.valueOf(band * 4 + bar), Integer.valueOf(result));
        return result;
    }

    /** @return this fork's {@code {day, night}} ink ids for one bar, or {@code {0, 0}} when it has none. */
    private static int[] inkPair(Resources resources, int bar) {
        if (bar == BAR_NAV) {
            if (sNavInkPair == null) {
                sNavInkPair = resolvePair(resources, NAV_INK_NAMES);
            }
            return sNavInkPair;
        }
        if (sStatusInkPair == null) {
            sStatusInkPair = resolvePair(resources, STATUS_INK_NAMES);
        }
        return sStatusInkPair;
    }

    private static int[] resolvePair(Resources resources, String[] names) {
        for (int i = 0; i + 1 < names.length; i += 2) {
            int day = resources.getIdentifier(names[i], "color", SYSTEM_UI_PACKAGE);
            int night = resources.getIdentifier(names[i + 1], "color", SYSTEM_UI_PACKAGE);
            if (day != 0 && night != 0) {
                return new int[]{day, night};
            }
        }
        return new int[]{0, 0};
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

    /** What the ink decision sees for each bar, for the view-tree dump. */
    static String inkReport() {
        return inkReportOf("nav", BAR_NAV) + inkReportOf("status", BAR_STATUS);
    }

    private static String inkReportOf(String label, int bar) {
        Integer judged = bandOf(bar);
        StringBuilder text = new StringBuilder(" ").append(label).append(" ink=")
                .append(colour(judged));
        View content = bar == BAR_NAV ? SystemBarBackgroundHook.navBarView()
                : SystemBarBackgroundHook.statusBarView();
        Resources resources = content == null ? null : content.getResources();
        if (resources == null) {
            return text.append(" bar=none").toString();
        }
        int[] pair = inkPair(resources, bar);
        if (pair[0] == 0 || pair[1] == 0) {
            return text.append(" pair=none").toString();
        }
        if (judged == null) {
            return text.append(" band=none").toString();
        }
        int chosen = inkId(resources, judged.intValue(), bar);
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
        View content = SystemBarBackgroundHook.navBarView();
        Integer band = bandOf(BAR_NAV);
        if (content == null || band == null) {
            XposedBridge.log(TAG + ": ink diagnose: no nav bar to walk");
            return;
        }
        Resources resources = content.getResources();
        int id = inkId(resources, band.intValue(), BAR_NAV);
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
                int before = inkOf(image, drawn);
                String skip = isPictureIcon(drawn) ? "picture"
                        : hasOwnOrParentBackground(image) ? "plate" : null;
                Integer ink = skip == null ? betterInk(before, band, target) : null;
                String stuck = "not applied";
                if (ink != null) {
                    paintIcon(image, drawn, ink.intValue());
                    ColorFilter now = drawn == null ? null : drawn.getColorFilter();
                    int nowColour = now == null ? 0 : colourOf(now);
                    stuck = "filter now " + (nowColour == 0 ? "none" : colour(Integer.valueOf(nowColour)))
                            + " tint now " + tintText(tintOf(drawn), drawn);
                }
                XposedBridge.log(TAG + ": ink " + entryName(view) + " "
                        + view.getClass().getSimpleName() + " vis=" + view.getVisibility()
                        + " size=" + view.getWidth() + 'x' + view.getHeight()
                        + " drw=" + (drawn == null ? "none" : drawn.getClass().getSimpleName())
                        + " was=" + (before == 0 ? "none" : colour(Integer.valueOf(before)))
                        + " skip=" + (skip == null ? "none" : skip)
                        + " put=" + (ink == null ? "none"
                                : colour(Integer.valueOf(ink.intValue())))
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

    private static String tintText(ColorStateList tint, Drawable drawn) {
        if (tint == null) {
            return "none";
        }
        return String.format("#%06X>[%06X", tint.getDefaultColor() & 0xFFFFFF,
                tintColour(tint, drawn) & 0xFFFFFF);
    }

    private static String colour(Integer value) {
        return value == null ? "none" : String.format("#%06X", value.intValue() & 0xFFFFFF);
    }

    private static float contrast(int background, int foreground) {
        float light = Math.max(luminance(background), luminance(foreground));
        float dark = Math.min(luminance(background), luminance(foreground));
        return (light + 0.05f) / (dark + 0.05f);
    }
}
