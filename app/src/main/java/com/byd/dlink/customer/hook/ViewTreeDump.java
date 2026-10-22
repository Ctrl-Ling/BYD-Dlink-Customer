package com.byd.dlink.customer.hook;

import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * A bar-height override has to be judged by where the icons end up, and the only thing that says
 * so is the view tree of the bar windows themselves: which container still carries the old height,
 * what its padding is, and what gravity its children were laid out with.
 *
 * Runs on the main thread because reading a view's bounds off the layout thread returns whatever
 * the previous pass left there.
 */
public class ViewTreeDump {

    private static final String TAG = "BYD Dlink Customer";
    private static final int MAX_LINES = 260;

    private static int sLines;

    public static void request() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                dump();
            }
        });
    }

    private static void dump() {
        try {
            Object global = XposedHelpers.callStaticMethod(
                    Class.forName("android.view.WindowManagerGlobal"), "getInstance");
            List<?> views = (List<?>) XposedHelpers.getObjectField(global, "mViews");
            List<?> roots = (List<?>) XposedHelpers.getObjectField(global, "mRoots");
            List<?> params = (List<?>) XposedHelpers.getObjectField(global, "mParams");
            sLines = 0;
            XposedBridge.log(TAG + ": painted navBand=" + hex(SystemBarBackgroundHook.navBarColour())
                    + " statusBand=" + hex(SystemBarBackgroundHook.statusBarColour()));
            XposedBridge.log(TAG + ": " + BarIconColourHook.inkReport());
            BarIconColourHook.diagnose();
            for (int i = 0; i < views.size(); i++) {
                View view = (View) views.get(i);
                Object root = roots.get(i);
                String title = str(root, "mTitle");
                if (title == null) {
                    title = "";
                }
                Object lp = params.get(i);
                // The bar windows are named whatever this fork's SystemUI names them, so match on
                // the shape that cannot lie: a full-width strip at one of the two screen edges.
                int[] xy = new int[2];
                view.getLocationInWindow(xy);
                boolean strip = view.getWidth() > 600 && view.getHeight() > 0
                        && view.getHeight() <= 200;
                // A band that darkens the icons it is supposed to sit behind can only do so from
                // another window, so every window is walked, not just the strips.
                XposedBridge.log(TAG + ": window[" + i + "] \"" + title + "\" type="
                        + intAt(lp, "type") + " size=" + view.getWidth() + 'x' + view.getHeight()
                        + " at=" + xy[0] + ',' + xy[1] + " lpH=" + intAt(lp, "height")
                        + " gravity=" + intAt(lp, "gravity") + " walk=all");
                {
                    sLines = 0;
                    if (strip) {
                        probe(view);
                    }
                    describe(title.isEmpty() ? "w" + i : title, view);
                }
            }
            XposedBridge.log(TAG + ": view tree dumped");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": view tree dump failed: " + t);
        }
    }

    // A bar that still measures its stock height says nothing about *why*, and the answer is
    // either the framework dimen or the same-named copy the APK carries. Read both the way the
    // bar itself reads them, through the window's own Resources.
    private static void probe(View view) {
        Resources res = view.getResources();
        String pkg = view.getContext().getPackageName();
        StringBuilder sb = new StringBuilder("dimens");
        String[] names = { "status_bar_height", "navigation_bar_height",
                "navigation_bar_height_car_mode", "navigation_bar_size", };
        for (int i = 0; i < names.length; i++) {
            sb.append(' ').append(names[i]).append("=android/")
                    .append(read(res, names[i], "android"))
                    .append(' ').append(pkg).append('/').append(read(res, names[i], pkg));
        }
        XposedBridge.log(TAG + ": " + sb);
    }

    private static String read(Resources res, String name, String pkg) {
        try {
            int id = res.getIdentifier(name, "dimen", pkg);
            return id == 0 ? "-" : String.valueOf(res.getDimensionPixelSize(id));
        } catch (Throwable t) {
            return "!";
        }
    }

    private static void describe(String window, View view) {
        if (sLines++ > MAX_LINES) {
            return;
        }
        int[] xy = new int[2];
        view.getLocationInWindow(xy);
        WindowManager.LayoutParams lp =
                view.getLayoutParams() instanceof WindowManager.LayoutParams
                        ? (WindowManager.LayoutParams) view.getLayoutParams() : null;
        StringBuilder sb = new StringBuilder();
        sb.append(window).append("  ").append(view.getClass().getName())
                .append(" id=").append(idName(view))
                .append(" pos=").append(xy[0]).append(',').append(xy[1])
                .append(" size=").append(view.getWidth()).append('x').append(view.getHeight())
                .append(" ty=").append(view.getTranslationY())
                .append(" pad=").append(view.getPaddingLeft()).append(',')
                .append(view.getPaddingTop()).append(',')
                .append(view.getPaddingRight()).append(',')
                .append(view.getPaddingBottom())
                .append(" vis=").append(view.getVisibility())
                .append(" alpha=").append(view.getAlpha())
                .append(" layer=").append(view.getLayerType())
                .append(" bg=").append(backgroundOf(view))
                .append(" ink=").append(inkOf(view))
                .append(" drw=").append(drawableOf(view))
                .append(" app=").append(originalAppIconOf(view));
        Object params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) params;
            sb.append(" lp=").append(m.width).append('x').append(m.height)
                    .append(" margin=").append(m.leftMargin).append(',')
                    .append(m.topMargin).append(',').append(m.rightMargin).append(',')
                    .append(m.bottomMargin);
        }
        if (params instanceof ViewGroup.LayoutParams) {
            sb.append(" gravity=" + gravityOf(params));
        }
        if (lp != null) {
            sb.append(" winGravity=").append(lp.gravity);
        }
        if (view instanceof android.widget.TextView) {
            sb.append(textOf((android.widget.TextView) view));
        }
        XposedBridge.log(TAG + ": view " + sb);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            describe(window + '/' + group.getChildCount(), group.getChildAt(i));
        }
    }
    /**
     * Where a label's own box puts its line: the gravity it carries and the line it laid out.
     * Together they say whether low letters come from the gravity, the font padding, or the box.
     */
    private static String textOf(android.widget.TextView text) {
        StringBuilder sb = new StringBuilder(" grv=0x")
                .append(Integer.toHexString(text.getGravity()))
                .append(" txt=").append(text.getText());
        android.text.Layout layout = text.getLayout();
        if (layout == null || layout.getLineCount() == 0) {
            return sb.append(" line=-").toString();
        }
        int last = layout.getLineCount() - 1;
        android.graphics.Rect ink = new android.graphics.Rect();
        String value = text.getText().toString();
        if (!value.isEmpty()) {
            text.getPaint().getTextBounds(value, 0, value.length(), ink);
        }
        return sb.append(" line=").append(layout.getLineTop(0)).append(',')
                .append(layout.getLineBottom(last))
                .append(" base=").append(layout.getLineBaseline(0))
                .append(" lh=").append(layout.getHeight())
                .append(" ext=").append(text.getExtendedPaddingTop()).append(',')
                .append(text.getExtendedPaddingBottom())
                .append(" ink=").append(ink.top).append(',').append(ink.bottom).toString();
    }

    private static String drawableOf(View view) {
        if (!(view instanceof android.widget.ImageView)) {
            return "-";
        }
        Drawable drawable = ((android.widget.ImageView) view).getDrawable();
        if (drawable == null) {
            return "-";
        }
        String name = drawable.getClass().getSimpleName()
                + ":op" + drawable.getOpacity()
                + ':' + drawable.getIntrinsicWidth() + 'x' + drawable.getIntrinsicHeight();
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap bitmap =
                    ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) {
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                        bitmap.getWidth(), bitmap.getHeight());
                int opaque = 0;
                java.util.Set<Integer> colours = new java.util.HashSet<Integer>();
                for (int i = 0; i < pixels.length; i++) {
                    int argb = pixels[i];
                    if ((argb >>> 24) < 0x20) {
                        continue;
                    }
                    opaque++;
                    colours.add(Integer.valueOf(((argb >> 12) & 0xF00)
                            | ((argb >> 8) & 0xF0) | ((argb >> 4) & 0xF)));
                }
                name = name + " solid=" + (opaque * 100 / pixels.length) + "% cols=" + colours.size();
            }
        }
        return name;
    }

    private static String hex(Integer colour) {
        return colour == null ? "none" : Integer.toHexString(colour.intValue());
    }

    private static String backgroundOf(View view) {
        Drawable background = view.getBackground();
        if (background == null) {
            return "-";
        }
        if (background instanceof ColorDrawable) {
            return Integer.toHexString(((ColorDrawable) background).getColor());
        }
        return background.getClass().getSimpleName() + '@'
                + Integer.toHexString(background.getAlpha());
    }

    /** What the icon text or image is actually being drawn with, from either mechanism BYD used. */
    private static String inkOf(View view) {
        int colour = 0;
        if (view instanceof android.widget.TextView) {
            android.content.res.ColorStateList colours =
                    ((android.widget.TextView) view).getTextColors();
            colour = colours == null ? 0 : colours.getDefaultColor();
        } else if (view instanceof android.widget.ImageView) {
            android.graphics.ColorFilter filter = ((android.widget.ImageView) view).getColorFilter();
            for (Class<?> owner = filter == null ? null : filter.getClass();
                    owner != null; owner = owner.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = owner.getDeclaredField("mColor");
                    field.setAccessible(true);
                    colour = field.getInt(filter);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (colour == 0) {
                android.content.res.ColorStateList tint =
                        ((android.widget.ImageView) view).getImageTintList();
                colour = tint == null ? 0 : tint.getDefaultColor();
            }
        }
        return colour == 0 ? "-" : Integer.toHexString(colour);
    }

    /** Whether a nav item is one BYD is deliberately drawing with a real app icon, and what it draws. */
    private static String originalAppIconOf(View view) {
        try {
            Object icon = XposedHelpers.getObjectField(view, "mNaviItemIcon");
            Drawable drawable = icon instanceof android.widget.ImageView
                    ? ((android.widget.ImageView) icon).getDrawable() : null;
            return XposedHelpers.getBooleanField(view, "mUsingOriginalAppIcon")
                    + "/" + (drawable == null ? "-"
                            : drawable.getClass().getSimpleName() + " op=" + drawable.getOpacity()
                                    + " " + drawable.getIntrinsicWidth() + 'x'
                                    + drawable.getIntrinsicHeight());
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String gravityOf(Object params) {
        try {
            return String.valueOf(XposedHelpers.getIntField(params, "gravity"));
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String idName(View view) {
        int id = view.getId();
        if (id == 0) {
            return "none";
        }
        try {
            return view.getResources().getResourceName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String str(Object target, String field) {
        try {
            Object value = XposedHelpers.getObjectField(target, field);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String intAt(Object target, String field) {
        try {
            return String.valueOf(XposedHelpers.getIntField(target, field));
        } catch (Throwable t) {
            return "-";
        }
    }
}
