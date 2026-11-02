package com.byd.dlink.customer.hook;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * What a view is being drawn with, asked of the view rather than guessed from the class it belongs to.
 *
 * An icon can carry its colour in four places -- the view's tint list, the drawable's own tint, a
 * colour filter handed to the drawable, or the asset's pixels -- and the framework applies them in
 * that order, so reading them in any other order answers with a colour that never reaches the screen.
 * Which of the four a given key uses is this fork's business, not a class name's: the bar is
 * user-rearrangeable and the buttons in it are only limited by what the owner chooses to put there.
 */
final class BarIconReader {

    /**
     * Above this share of its own pixels covered, a bitmap is a picture BYD is showing as artwork --
     * a filled tile with something drawn inside it -- rather than a glyph whose strokes are the whole
     * of it. Measured on this ROM: the app-list tile covers 96%, a real app icon 95%, the split-screen
     * key 13%.
     */
    private static final float PICTURE_COVERAGE = 0.5f;

    // Cache for hasOwnOrParentBackground() results - view backgrounds rarely change
    private static final Map<View, Boolean> sBackgroundCache =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    // Cache for analyse() results - a bitmap's coverage and colour never change once it is drawn with
    private static final Map<Drawable, int[]> sAssetInk =
            Collections.synchronizedMap(new WeakHashMap<Drawable, int[]>());

    // Cache for inkOf() results - ImageView color filters rarely change
    private static final Map<ImageView, Integer> sInkCache =
            Collections.synchronizedMap(new WeakHashMap<ImageView, Integer>());

    /**
     * What an image is being drawn with, asked in the order the framework applies the mechanisms: a
     * tint overrides a colour filter on the same drawable, and the view's tint is handed to every
     * drawable it is given, so either of those, not a remembered filter, is what reaches the pixels.
     * With neither, the asset's own colour is the answer, and a colourful asset answers with none,
     * which is BYD meaning something by it and is left alone.
     */
    static int inkOf(ImageView image, Drawable drawable) {
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
     * Whether an image draws from a bitmap that carries the message in its artwork rather than in its
     * shape -- a filled tile with detail inside it, the way BYD draws the app-list key and every real
     * app icon. Coverage tells the two apart: a glyph is a few strokes over a transparent field, a
     * picture is nearly all ink. Flattening the second to one bar colour erases what it depicts, so it
     * is left to draw itself, the same way an item flagged as showing its original app icon is.
     */
    static boolean isPictureIcon(Drawable drawable) {
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
                result[1] = BarContrast.isInk(ink) ? ink : 0;
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
    static boolean hasOwnOrParentBackground(View view) {
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
            if (BarViews.isBar(held) || BarBands.isBandView(held)) {
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
    private static boolean isInkBackground(Drawable drawable) {
        if (drawable instanceof RippleDrawable
                || drawable.getClass().getName().endsWith("Ripple")) {
            // Press feedback, which paints nothing at rest. BYD's keys carry {@code KeyButtonRipple},
            // and that extends {@code Drawable}, not {@code RippleDrawable}, so an instanceof test
            // misses it and reads every stock key in a Dlink 3.0/4.0 bar as a plate of its own.
            return true;
        }
        if (drawable instanceof ColorDrawable) {
            int colour = ((ColorDrawable) drawable).getColor();
            return Color.alpha(colour) <= 0x40 || BarContrast.isInk(colour);
        }
        return false;
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
    static ColorStateList tintOf(Drawable drawable) {
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
    static int colourOf(ColorFilter filter) {
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
    static int tintColour(ColorStateList tint, Drawable drawn) {
        if (tint == null) {
            return 0;
        }
        int fallback = tint.getDefaultColor();
        return drawn == null ? fallback : tint.getColorForState(drawn.getState(), fallback);
    }

    /** A new decision has been taken, so every view has to be asked again rather than remembered. */
    static void clearCaches() {
        sBackgroundCache.clear();
        sInkCache.clear();
    }

    private BarIconReader() {
    }
}
