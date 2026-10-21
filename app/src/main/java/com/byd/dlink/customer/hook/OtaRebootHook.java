package com.byd.dlink.customer.hook;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.view.WindowManager;

import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks OTA update installation and reboot to give the user control over when the system restarts.
 *
 * A/B slot devices switch slots on reboot after an update is installed. This hook intercepts the
 * automatic reboot that follows a successful installation, shows a dialog asking the user whether
 * to restart now, and only proceeds with the reboot if the user confirms.
 *
 * Additionally provides a switch to completely disable OTA update installation.
 */
public class OtaRebootHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String OTA_UPDATE_PACKAGE = "com.byd.otaupdate";
    private static final String UPDATED_PACKAGE = "com.byd.updated";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PrefsManager.isOtaRebootInterceptEnabled() && !PrefsManager.isOtaUpdateDisabled()) {
            return;
        }

        if (OTA_UPDATE_PACKAGE.equals(lpparam.packageName)) {
            hookOtaUpdate(lpparam.classLoader);
        } else if (UPDATED_PACKAGE.equals(lpparam.packageName)) {
            hookUpdatedService(lpparam.classLoader);
        }

        // Hook PowerManager.reboot() globally to intercept any reboot request
        hookPowerManagerReboot();
    }

    /**
     * Hook OTA update package to intercept installation and reboot.
     */
    private static void hookOtaUpdate(ClassLoader classLoader) {
        if (PrefsManager.isOtaUpdateDisabled()) {
            // Disable update check
            try {
                Class<?> updateModel = XposedHelpers.findClass(
                        "com.byd.otaupdate.ui.vehicleota.UpdateOTAModel", classLoader);
                XposedBridge.hookAllMethods(updateModel, "checkOTAUpdateConditions",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                XposedBridge.log(TAG + ": OTA update check blocked by user setting");
                                param.setResult(null);
                            }
                        });
                XposedBridge.log(TAG + ": OTA update check disabled");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to disable OTA update check: " + t);
            }

            // Disable download
            try {
                Class<?> downloadHelper = XposedHelpers.findClass(
                        "com.byd.otaupdate.api.base.download.DownloadFileHelper", classLoader);
                XposedBridge.hookAllMethods(downloadHelper, "downloadFile",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                XposedBridge.log(TAG + ": OTA download blocked by user setting");
                                param.setResult(null);
                            }
                        });
                XposedBridge.log(TAG + ": OTA download disabled");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to disable OTA download: " + t);
            }
        }

        if (PrefsManager.isOtaRebootInterceptEnabled()) {
            // Hook installation completion
            try {
                Class<?> updateService = XposedHelpers.findClass(
                        "com.byd.otaupdate.ui.vehicleota.UpdateOTAService", classLoader);
                XposedBridge.hookAllMethods(updateService, "applyAndAckSettings",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                XposedBridge.log(TAG + ": OTA install intercepted, showing reboot dialog");
                                showRebootDialog();
                                param.setResult(null);
                            }
                        });
                XposedBridge.log(TAG + ": OTA install hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to hook OTA install: " + t);
            }
        }
    }

    /**
     * Hook RapidUpdateService package.
     */
    private static void hookUpdatedService(ClassLoader classLoader) {
        if (PrefsManager.isOtaUpdateDisabled()) {
            try {
                Class<?> updateService = XposedHelpers.findClass(
                        "com.byd.updated.UpdateService", classLoader);
                XposedBridge.hookAllMethods(updateService, "onStartCommand",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                XposedBridge.log(TAG + ": RapidUpdateService blocked by user setting");
                                param.setResult(0);
                            }
                        });
                XposedBridge.log(TAG + ": RapidUpdateService disabled");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to disable RapidUpdateService: " + t);
            }
        }
    }

    /**
     * Hook PowerManager.reboot() to intercept all reboot requests.
     * Shows a dialog asking the user to confirm the reboot.
     */
    private static void hookPowerManagerReboot() {
        if (!PrefsManager.isOtaRebootInterceptEnabled()) {
            return;
        }

        try {
            XposedBridge.hookAllMethods(PowerManager.class, "reboot", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!PrefsManager.isOtaRebootInterceptEnabled()) {
                        return;
                    }

                    String reason = param.args.length > 0 ? (String) param.args[0] : "";
                    XposedBridge.log(TAG + ": Reboot intercepted, reason=" + reason);

                    // Show confirmation dialog
                    showRebootConfirmation(reason);

                    // Prevent the reboot
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + ": PowerManager.reboot hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook PowerManager.reboot: " + t);
        }
    }

    /**
     * Show reboot confirmation dialog with 30-second countdown.
     * Auto-reboots if user doesn't respond within 30 seconds.
     */
    private static void showRebootConfirmation(String reason) {
        try {
            Context context = getCurrentContext();
            if (context == null) {
                XposedBridge.log(TAG + ": No context available for reboot dialog");
                return;
            }

            final int countdownSeconds = 30;
            final String[] currentMessage = new String[1];
            final AlertDialog[] dialogHolder = new AlertDialog[1];

            currentMessage[0] = buildRebootMessage(countdownSeconds);

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("系统更新重启确认");
            builder.setMessage(currentMessage[0]);
            builder.setCancelable(false);

            builder.setPositiveButton("立即重启", (dialog, which) -> {
                XposedBridge.log(TAG + ": User confirmed reboot immediately");
                if (dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                    dialogHolder[0].dismiss();
                }
                performReboot(context, reason);
            });

            builder.setNegativeButton("稍后手动重启", (dialog, which) -> {
                XposedBridge.log(TAG + ": User declined reboot, will reboot manually later");
                if (dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                    dialogHolder[0].dismiss();
                }
            });

            AlertDialog dialog = builder.create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();
            dialogHolder[0] = dialog;

            CountDownTimer countdownTimer = new CountDownTimer(countdownSeconds * 1000L, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    int secondsLeft = (int) (millisUntilFinished / 1000);
                    currentMessage[0] = buildRebootMessage(secondsLeft);
                    if (dialog.isShowing()) {
                        dialog.setMessage(currentMessage[0]);
                    }
                }

                @Override
                public void onFinish() {
                    XposedBridge.log(TAG + ": Countdown finished, auto-rebooting");
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    performReboot(context, reason);
                }
            };

            countdownTimer.start();
            XposedBridge.log(TAG + ": Reboot confirmation dialog shown with 30s countdown");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to show reboot dialog: " + t);
        }
    }

    private static String buildRebootMessage(int secondsLeft) {
        return "系统更新已安装完成，需要重启以切换到新槽位。\n\n" +
                secondsLeft + " 秒后将自动重启。\n\n" +
                "• 点击「立即重启」：马上重启\n" +
                "• 点击「稍后手动重启」：取消本次重启，请自行找时间重启";
    }

    private static void performReboot(Context context, String reason) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                pm.reboot(reason);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to reboot: " + t);
        }
    }

    /**
     * Show reboot dialog after OTA install completion.
     */
    private static void showRebootDialog() {
        showRebootConfirmation("ota_update_installed");
    }

    /**
     * Get current activity context.
     */
    private static Context getCurrentContext() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object currentActivityThread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            Object context = XposedHelpers.callMethod(currentActivityThread, "getSystemContext");
            return (Context) context;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to get current context: " + t);
            return null;
        }
    }
}
