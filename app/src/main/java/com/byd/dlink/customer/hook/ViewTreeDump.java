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
                .append(" pad=").append(view.getPaddingLeft()).append(',')
                .append(view.getPaddingTop()).append(',')
                .append(view.getPaddingRight()).append(',')
                .append(view.getPaddingBottom())
                .append(" vis=").append(view.getVisibility())
                .append(" alpha=").append(view.getAlpha())
                .append(" layer=").append(view.getLayerType())
                .append(" bg=").append(backgroundOf(view));
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
        XposedBridge.log(TAG + ": view " + sb);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            describe(window + '/' + group.getChildCount(), group.getChildAt(i));
        }
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
