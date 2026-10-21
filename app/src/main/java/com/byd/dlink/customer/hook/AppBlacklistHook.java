package com.byd.dlink.customer.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import com.byd.dlink.customer.utils.PrefsManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Lifts the head unit's split-screen app blacklist. There is no package list behind it: every
 * gate reads ActivityInfo.resizeMode and rejects 0 (unresizeable) and 5/6 (the modes the system
 * assigns itself), in the picker and in WindowManager alike. Lifting it means answering both,
 * because an app that is only added to the picker gets moved straight back out of the split
 * stacks by ActivityStackSupervisor.handleNonResizableTaskIfNeeded.
 */
public class AppBlacklistHook {

    private static final String TAG = "BYD Dlink Customer";

    private static final String DIVIDER_UTILS =
            "com.android.systemui.stackdivider.DividerUtils";
    private static final String CUSTOM_DIVIDER_ACTIVITY =
            "com.android.systemui.recents.customdivider.CustomDividerActivity";
    private static final String SCREEN_SPLIT_UTILS =
            "com.android.server.utils.ScreenSplitUtils";
    private static final String TASK_RECORD =
            "com.android.server.wm.TaskRecord";
    private static final String ACTIVITY_RECORD =
            "com.android.server.wm.ActivityRecord";

    // The picker adapter reads these three keys out of every entry and casts the icon to
    // Drawable and the text to String, so a new entry has to look exactly like the ones the
    // framework builds itself.
    private static final String KEY_PACKAGE_NAME = "packageName";
    private static final String KEY_ICON = "img";
    private static final String KEY_TEXT = "text";

    // ScreenSplitUtils reports 1 for a supported app and -1 for a blacklisted one; 0 means the
    // intent resolved to no activity at all, which is not a blacklist verdict.
    private static final int SPLIT_SUPPORTED = 1;
    private static final int SPLIT_BLACKLISTED = -1;

    private static volatile boolean sPickerHookLogged = false;
    private static volatile boolean sHomeLookupLogged = false;
    private static volatile boolean sWindowGateApplied = false;

    public static void initSystemUi(XC_LoadPackage.LoadPackageParam lpparam) {
        hookPicker(lpparam.classLoader, DIVIDER_UTILS);
        hookPicker(lpparam.classLoader, CUSTOM_DIVIDER_ACTIVITY);

        // Only DividerUtils.startActivityFromStatusBar consults this, and it falls back to a
        // plain fullscreen start when the answer is no. The picker's own launch path skips it.
        try {
            XposedHelpers.findAndHookMethod(DIVIDER_UTILS, lpparam.classLoader,
                    "isAppSuportSplit", Context.class, Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!PrefsManager.isSplitBlacklistDisabled()) return;
                            if (!Boolean.FALSE.equals(param.getResult())) return;
                            param.setResult(true);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DividerUtils.isAppSuportSplit not hooked: " + t);
        }
    }

    public static void initSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        // Installed even when the switch is off: system_server only runs this at boot, and each
        // hook re-reads the switch, so turning the feature on needs no framework restart.
        hookScreenSplitUtils(lpparam.classLoader);
        hookTaskRecord(lpparam.classLoader);
        hookActivityRecord(lpparam.classLoader);
    }

    @SuppressWarnings("unchecked")
    private static void hookPicker(ClassLoader loader, String className) {
        try {
            XposedHelpers.findAndHookMethod(className, loader, "getSplitAppInfo",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!PrefsManager.isSplitBlacklistDisabled()) return;
                            if (!(param.getResult() instanceof List)) return;
                            appendBlacklistedLaunchers(
                                    (List<Object>) param.getResult(),
                                    (Context) param.args[0]);
                        }
                    });
            if (!sPickerHookLogged) {
                sPickerHookLogged = true;
                XposedBridge.log(TAG + ": split app picker unfiltered");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": picker " + className + " not hooked: " + t);
        }
    }

    // The dropped apps are whatever a launcher query returns minus what the caller listed, so
    // this also covers any package-based exclusion the ROM adds later on top of resizeMode.
    @SuppressWarnings("unchecked")
    private static void appendBlacklistedLaunchers(List<Object> entries, Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchers = pm.queryIntentActivities(intent, 0);
            if (launchers == null) return;

            Set<String> listed = new HashSet<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map)) continue;
                Object pkg = ((Map<Object, Object>) entry).get(KEY_PACKAGE_NAME);
                if (pkg instanceof String) listed.add((String) pkg);
            }

            int added = 0;
            for (ResolveInfo info : launchers) {
                if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
                if (!listed.add(info.activityInfo.packageName)) continue;
                Drawable icon;
                String label;
                try {
                    icon = info.loadIcon(pm);
                    label = info.loadLabel(pm).toString();
                } catch (Throwable t) {
                    continue;
                }
                if (icon == null) continue;
                Map<String, Object> entry = new HashMap<>();
                entry.put(KEY_PACKAGE_NAME, info.activityInfo.packageName);
                entry.put(KEY_ICON, icon);
                entry.put(KEY_TEXT, label);
                entries.add(entry);
                added++;
            }
            if (added > 0) {
                XposedBridge.log(TAG + ": picker shows " + added
                        + " blacklisted app(s), " + entries.size() + " total");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": picker fill failed: " + t);
        }
    }

    private static void hookScreenSplitUtils(ClassLoader loader) {
        // The (Context, String) overload builds a MAIN intent for the package and calls this
        // one, and ActivityStarter.startActivity consults it to decide whether to build split
        // ActivityOptions for a launch at all.
        try {
            XposedHelpers.findAndHookMethod(SCREEN_SPLIT_UTILS, loader,
                    "isAppSuportSplit", Context.class, Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!PrefsManager.isSplitBlacklistDisabled()) return;
                            if (!(param.getResult() instanceof Integer)) return;
                            if ((Integer) param.getResult() != SPLIT_BLACKLISTED) return;
                            param.setResult(SPLIT_SUPPORTED);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ScreenSplitUtils.isAppSuportSplit not hooked: " + t);
        }
    }

    private static void hookTaskRecord(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(TASK_RECORD, loader,
                    "supportsSplitScreenWindowingMode", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!PrefsManager.isSplitBlacklistDisabled()) return;
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            if (!isActivityTypeHome(param.thisObject)) {
                                param.setResult(true);
                                logWindowGateOnce();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TaskRecord gate not hooked: " + t);
        }
    }

    private static void hookActivityRecord(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(ACTIVITY_RECORD, loader,
                    "supportsResizeableMultiWindow", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!PrefsManager.isSplitBlacklistDisabled()) return;
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            if (!isActivityTypeHome(param.thisObject)) {
                                param.setResult(true);
                                logWindowGateOnce();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ActivityRecord gate not hooked: " + t);
        }
    }

    private static void logWindowGateOnce() {
        if (sWindowGateApplied) return;
        sWindowGateApplied = true;
        XposedBridge.log(TAG + ": split windowing mode forced for blacklisted activities");
    }

    // Home is excluded from the rescue on purpose. The setting does short-circuit before any
    // activity type test on TaskRecord, but the launcher answering "I can split" changes which
    // stack a desktop-originated split anchors to, and that path is already working. An
    // unreadable activity type is treated as Home so the gate keeps its original answer.
    private static boolean isActivityTypeHome(Object container) {
        try {
            Object home = XposedHelpers.callMethod(container, "isActivityTypeHome");
            if (home instanceof Boolean) return (Boolean) home;
        } catch (Throwable t) {
            if (!sHomeLookupLogged) {
                sHomeLookupLogged = true;
                XposedBridge.log(TAG + ": isActivityTypeHome unreadable: " + t);
            }
        }
        return true;
    }
}
