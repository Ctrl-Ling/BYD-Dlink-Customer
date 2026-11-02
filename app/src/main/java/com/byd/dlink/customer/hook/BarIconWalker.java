package com.byd.dlink.customer.hook;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import de.robv.android.xposed.XposedHelpers;

/**
 * The half the setter hooks cannot do: BYD colours most of a bar once, while inflating it, and never
 * calls a setter again -- measured, a whole SystemUI boot produced exactly one ink call for the nav
 * bar, from a key that was being rebound. So the bar is read instead of waited for: every view under it
 * is asked what it draws with now, and a view that answers with ink the band cannot be read against, or
 * with no decision of its own at all, is given the tone the band needs.
 *
 * Nothing is assumed about which family a view belongs to, which is the point -- the bar is
 * user-rearrangeable, and the buttons in it are only limited by what the owner chooses to put there.
 * Dlink 3.0/4.0 needs it for both bars: its status bar is BYD's own clock and air-conditioning
 * readout, coloured while it was inflated, and the ROM's dark-icon tint reaches none of it.
 */
final class BarIconWalker {

    private static final String KEY_ITEM_ICON = "mNaviItemIcon";
    private static final String KEY_ORIGINAL_APP_ICON = "mUsingOriginalAppIcon";

    // The class BYD's nav items share, so the walk can tell an item's own icon view from the icons
    // hanging off it. Set once by the hook that finds it; null until then.
    private static Class<?> sBaseItem;

    static void setBaseItem(Class<?> baseItem) {
        sBaseItem = baseItem;
    }

    /**
     * The colour to use instead of the one BYD asked for, for a view in {@code bar}. Null when the band
     * has nothing to judge against or this fork names no ink pair for that bar.
     */
    static Integer inkColour(View view, int bar, int requested) {
        Integer band = BarBands.judgedInk(bar);
        if (band == null) {
            return null;
        }
        Resources resources = view.getResources();
        int id = BarInkPalette.inkId(resources, band.intValue(), bar);
        if (id == 0) {
            return null;
        }
        int pick = resources.getColor(id);
        return Color.argb(Color.alpha(requested), Color.red(pick), Color.green(pick),
                Color.blue(pick));
    }

    /** Re-read a whole bar and hand every icon in it the tone its band needs. */
    static void pullBarInk(int bar) {
        View content = bar == BarViews.NAV ? BarBands.navBar() : BarBands.statusBar();
        Integer band = BarBands.judgedInk(bar);
        if (content == null || band == null) {
            return;
        }
        Resources resources = content.getResources();
        int id = BarInkPalette.inkId(resources, band.intValue(), bar);
        if (id == 0) {
            return;
        }
        pullInk(content, band.intValue(), resources.getColor(id));
    }

    private static void pullInk(View view, int band, int target) {
        if (sBaseItem != null && sBaseItem.isInstance(view)) {
            giveIconItsTone(view, target);
        }
        if (view instanceof TextView) {
            if (!BarIconReader.hasOwnOrParentBackground(view)) {
                ColorStateList colours = ((TextView) view).getTextColors();
                int current = colours == null ? 0 : colours.getDefaultColor();
                Integer ink = BarContrast.betterInk(current, band, target);
                if (ink != null) {
                    ((TextView) view).setTextColor(ink);
                }
            }
        } else if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            Drawable drawn = image.getDrawable();
            if (!BarIconReader.isPictureIcon(drawn)
                    && !BarIconReader.hasOwnOrParentBackground(image)) {
                Integer ink = BarContrast.betterInk(
                        BarIconReader.inkOf(image, drawn), band, target);
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
            if (BarIconReader.isPictureIcon(drawn)) {
                int current = BarIconReader.inkOf(image, drawn);
                if (current != 0 && BarContrast.isInk(current)) {
                    image.setColorFilter((ColorFilter) null);
                }
                return;
            }
            if (BarIconReader.inkOf(image, drawn) == 0) {
                image.setColorFilter(target);
            }
        } catch (Throwable t) {
            // Not an item that carries these; the walk simply moves on.
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
    static void paintIcon(ImageView image, Drawable drawn, int ink) {
        if (image.getImageTintList() != null || (drawn != null && BarIconReader.tintOf(drawn) != null)) {
            image.setImageTintList(ColorStateList.valueOf(ink));
        }
        image.setColorFilter(ink);
    }

    private BarIconWalker() {
    }
}
