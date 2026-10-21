package com.byd.dlink.customer.hook;

import android.content.res.Resources;
import android.util.SparseArray;

import com.byd.dlink.customer.utils.PrefsManager;
import com.byd.dlink.customer.utils.SystemBarDimens;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Status bar and navigation bar heights are framework dimens that WindowManager reads once per
 * configuration change and then uses to place every window, so replacing the dimen at the
 * Resources level is what moves both the bars and the app content area together.
 *
 * The replacement is keyed on the resource entry name rather than the framework id: SystemUI
 * keeps dimens of its own that merely alias the framework ones, aapt resolves those aliases
 * inside the resource table, and a table-level alias therefore never shows up as a call with the
 * framework id. Matching ids alone moved the window frames but left the bar content at its stock
 * height, which put every icon off-centre inside its own cropped box.
 *
 * Installed in system_server (WindowManager) and SystemUI (the process that draws the two bars).
 * Values are read at process start, which is why the settings screen promises the change on reboot.
 */
public class SystemBarHeightHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String RESOURCES_CLASS = "android.content.res.Resources";
    private static final String[] DIMEN_GETTERS = {
            "getDimensionPixelSize", "getDimensionPixelOffset", "getDimension",
    };
    private static final int NOT_A_BAR = -1;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        final int statusBarPx = PrefsManager.getStatusBarHeightOverride();
        final int navBarPx = PrefsManager.getNavigationBarHeightOverride();
        if (statusBarPx < 0 && navBarPx < 0) {
            return;
        }

        final Map<String, Integer> pxByName = new HashMap<String, Integer>();
        addNames(pxByName, SystemBarDimens.STATUS_BAR, statusBarPx);
        addNames(pxByName, SystemBarDimens.NAV_BAR, navBarPx);
        if (pxByName.isEmpty()) {
            XposedBridge.log(TAG + ": no status/navigation bar dimen names, height override skipped");
            return;
        }

        // Whether an id is a bar height is answered per Resources: the same int means something
        // else in every package, and the lookup is a JNI round trip that has no business
        // happening on every dimension read in system_server.
        final Map<Resources, SparseArray<Integer>> decisions =
                Collections.synchronizedMap(new WeakHashMap<Resources, SparseArray<Integer>>());

        Class<?> resourcesClass;
        try {
            resourcesClass = XposedHelpers.findClass(RESOURCES_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Resources class not found for bar heights: " + t);
            return;
        }

        XC_MethodHook replaceDimen = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object value = param.getResult();
                int px;
                if (value instanceof Integer) {
                    px = ((Integer) value).intValue();
                } else if (value instanceof Float) {
                    px = (int) ((Float) value).floatValue();
                } else {
                    return;
                }
                Resources resources = (Resources) param.thisObject;
                int id = ((Number) param.args[0]).intValue();
                int target = overridePx(resources, id, px, pxByName, decisions);
                if (target == NOT_A_BAR || target == px) {
                    return;
                }
                // Spelled out as two branches on purpose. Written as a single conditional
                // expression, Integer and Float are both unboxed, promoted to float and reboxed,
                // so the hook always handed back a Float -- and the generated hooker for an
                // int-returning getDimensionPixelSize then threw ClassCastException while
                // SystemUI was creating its first service, crash-looping it.
                if (value instanceof Integer) {
                    param.setResult(Integer.valueOf(target));
                } else {
                    param.setResult(Float.valueOf(target));
                }
            }
        };

        for (String getter : DIMEN_GETTERS) {
            try {
                XposedHelpers.findAndHookMethod(resourcesClass, getter, int.class, replaceDimen);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + getter + " not hooked: " + t);
            }
        }

        XposedBridge.log(TAG + ": bar height override installed"
                + " statusBar=" + statusBarPx + " navBar=" + navBarPx
                + " names=" + pxByName.size());
    }

    private static int overridePx(Resources resources, int id, int observed,
            Map<String, Integer> pxByName, Map<Resources, SparseArray<Integer>> decisions) {
        synchronized (decisions) {
            SparseArray<Integer> perResources = decisions.get(resources);
            if (perResources == null) {
                perResources = new SparseArray<Integer>();
                decisions.put(resources, perResources);
            }
            Integer cached = perResources.get(id);
            if (cached != null) {
                return cached.intValue();
            }
            // Decide from the first value seen: a name the ROM currently reads as 0 is a bar it
            // switched off, and an override must only shrink or grow a bar, never invent one.
            String name = observed > 0 ? entryName(resources, id) : null;
            int target = name == null ? NOT_A_BAR : match(pxByName, name);
            perResources.put(id, Integer.valueOf(target));
            if (target != NOT_A_BAR) {
                XposedBridge.log(TAG + ": dimen " + name + " id=0x" + Integer.toHexString(id)
                        + " " + observed + " -> " + target);
            }
            return target;
        }
    }

    private static int match(Map<String, Integer> pxByName, String name) {
        Integer px = name == null ? null : pxByName.get(name);
        return px == null ? NOT_A_BAR : px.intValue();
    }

    private static String entryName(Resources resources, int id) {
        try {
            return resources.getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addNames(Map<String, Integer> pxByName, String[] names, int px) {
        if (px < 0) {
            return;
        }
        for (int i = 0; i < names.length; i++) {
            pxByName.put(names[i], Integer.valueOf(px));
        }
    }
}
