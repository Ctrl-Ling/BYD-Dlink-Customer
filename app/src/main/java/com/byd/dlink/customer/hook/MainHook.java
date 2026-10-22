package com.byd.dlink.customer.hook;

import com.byd.dlink.customer.SettingsActivity;
import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String SYSTEM_SERVER_PACKAGE = "android";
    private static final String MODULE_PACKAGE = "com.byd.dlink.customer";
    private static final String OTA_UPDATE_PACKAGE = "com.byd.otaupdate";
    private static final String UPDATED_PACKAGE = "com.byd.updated";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(MODULE_PACKAGE)) {
            XposedHelpers.findAndHookMethod(
                    SettingsActivity.class.getName(),
                    lpparam.classLoader,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });
            XposedBridge.log("BYD Dlink Customer: self-hook installed");
            return;
        }

        // Split bounds are never written from system_server: a hook there logs while
        // WindowManager holds its global lock, and a divider drag is exactly the moment that
        // lock has to stay free. The blacklist gates are the one exception, and they answer
        // without touching the lock.
        if (lpparam.packageName.equals(SYSTEM_SERVER_PACKAGE)) {
            XposedBridge.log("BYD Dlink Customer: system_server loaded, config"
                    + " enabled=" + PrefsManager.isSplitScreenEnabled()
                    + " blacklist=" + PrefsManager.isSplitBlacklistDisabled()
                    + " landscape=" + PrefsManager.getLandscapeRatio()
                    + " portrait=" + PrefsManager.getPortraitRatio()
                    + " statusBar=" + PrefsManager.getStatusBarHeightOverride()
                    + " navBar=" + PrefsManager.getNavigationBarHeightOverride()
                    + " volume=" + PrefsManager.isVolumeHookEnabled()
                    + " otaReboot=" + PrefsManager.isOtaRebootInterceptEnabled()
                    + " otaDisable=" + PrefsManager.isOtaUpdateDisabled()
                    + " appStart=" + PrefsManager.isAppStartHookEnabled()
                    + " appStartOff=" + PrefsManager.isAppStartManagerDisabled()
                    + " appStartUpdate=" + PrefsManager.isAppStartUpdatePreserved()
                    + " appStartInstall=" + PrefsManager.isAppStartInstallPreserved());
            AppBlacklistHook.initSystemServer(lpparam);
            SystemBarHeightHook.initSystemServer(lpparam);
            VolumeStepHook.init(lpparam);
            AppStartHook.initSystemServer(lpparam);
            if (PrefsManager.isVolumeHookEnabled()) {
                NaviVolumeChannel.init(lpparam);
            }
            // Hook PowerManager.reboot() in system_server for OTA reboot intercept
            if (PrefsManager.isOtaRebootInterceptEnabled()) {
                OtaRebootHook.init(lpparam);
            }
            return;
        }

        // Hook OTA update packages
        if (lpparam.packageName.equals(OTA_UPDATE_PACKAGE) || lpparam.packageName.equals(UPDATED_PACKAGE)) {
            if (PrefsManager.isOtaRebootInterceptEnabled() || PrefsManager.isOtaUpdateDisabled()) {
                XposedBridge.log("BYD Dlink Customer: hooking OTA package " + lpparam.packageName);
                OtaRebootHook.init(lpparam);
            }
            return;
        }

        if (!lpparam.packageName.equals(SYSTEM_UI_PACKAGE)) {
            if (PrefsManager.isGlobalEnabled()) {
                AppGainHook.init(lpparam);
                AppChannelHook.init(lpparam);
            }
            return;
        }

        if (!PrefsManager.isGlobalEnabled()) {
            return;
        }

        XposedBridge.log("BYD Dlink Customer: hooking SystemUI");

        SplitScreenHook.init(lpparam);
        AppBlacklistHook.initSystemUi(lpparam);
        SystemBarHeightHook.init(lpparam);
        BarContentCentreHook.init(lpparam);
        SystemBarBackgroundHook.init(lpparam);
        BarIconColourHook.init(lpparam);
        CommandReceiver.init(lpparam);
        VolumePanelHook.init(lpparam);
        if (PrefsManager.isVolumeHookEnabled()) {
            NaviVolumeChannel.init(lpparam);
        }
    }
}
