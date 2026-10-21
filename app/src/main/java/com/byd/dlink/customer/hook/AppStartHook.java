package com.byd.dlink.customer.hook;

import android.content.Intent;

import com.byd.dlink.customer.utils.PrefsManager;

import java.util.HashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BYD's 自启动管理 page ticks a box per app; the box is a row keyed by uid holding 1 (禁止自启动)
 * or 0 (允许), with an absent row meaning 允许. ActivityManagerService consults the whole table on
 * every bindService, startService, provider acquire and broadcast dispatch, so a ticked app can
 * never be started by a third party.
 *
 * The table itself survives in a content provider, but AppOpsDataCachedService answers every
 * package broadcast by writing 1 for the uid, which silently re-ticks whatever the user unticked.
 * Two levers follow: replace the table AMS reads with an empty one, or filter those writes.
 */
public class AppStartHook {

    private static final String TAG = "BYD Dlink Customer";

    private static final String ACTIVITY_MANAGER_SERVICE =
            "com.android.server.am.ActivityManagerService";
    private static final String APP_OPS_DATA_CACHED_SERVICE =
            "com.android.server.AppOpsDataCachedService";

    public static void initSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        // Installed even when every switch is off: system_server only runs this at boot, and both
        // callbacks re-read the switches, so turning a feature on needs no framework restart.
        hookStartupGate(lpparam.classLoader);
        hookTableWrite(lpparam.classLoader);
    }

    /**
     * Every block decision reads the table through this one accessor, so an empty answer lets all
     * apps start again without touching the ACC-off protection or the whitelists around it.
     */
    private static void hookStartupGate(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(ACTIVITY_MANAGER_SERVICE, loader,
                    "getAppOpsDataMapInternal", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!PrefsManager.isAppStartManagerDisabled()) return;
                            param.setResult(new HashMap<String, Integer>());
                        }
                    });
            XposedBridge.log(TAG + ": autostart gate reads an empty table");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": autostart gate not hooked: " + t);
        }
    }

    private static void hookTableWrite(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(APP_OPS_DATA_CACHED_SERVICE, loader,
                    "updateAppOpsChangedLocked", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shouldSkip((Intent) param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": autostart table writes filtered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": autostart table not hooked: " + t);
        }
    }

    /**
     * The service deletes the row on a removal and writes 1 on anything else. An update arrives as
     * a replacing removal followed by the add actions, so keeping a row through an update means
     * skipping the delete and every write that follows it.
     */
    private static boolean shouldSkip(Intent intent) {
        if (intent == null || intent.getAction() == null) return false;
        String action = intent.getAction();
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        if (isRemoval(action)) {
            return replacing && PrefsManager.isAppStartUpdatePreserved();
        }
        if (isUpdate(action, replacing)) {
            return PrefsManager.isAppStartUpdatePreserved();
        }
        return PrefsManager.isAppStartInstallPreserved();
    }

    private static boolean isRemoval(String action) {
        return Intent.ACTION_PACKAGE_REMOVED.equals(action)
                || Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)
                || Intent.ACTION_UID_REMOVED.equals(action);
    }

    private static boolean isUpdate(String action, boolean replacing) {
        return Intent.ACTION_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || replacing;
    }
}
