package com.byd.dlink.customer.hook;

import android.media.AudioManager;

import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemUI's volume panel never asks the framework how many steps a stream has. Each of its three
 * rows calls setMax() with a literal that mirrors AudioService's MAX_STREAM_VOLUME table -- navi 10,
 * media 39, bluetooth call 24 -- so extending that table in system_server moves the stored index and
 * the audio policy range while the bar stays saturated at its old ceiling. That is why a step change
 * is invisible on the screen the driver is actually looking at.
 *
 * initView is the only place that ceiling is written: every later update either sets progress
 * directly or derives the dragged level from seekBar.getMax(), so replacing the constant with
 * getStreamMaxVolume(stream) after initView returns fixes the bar, the label and the drag together.
 */
public class VolumePanelHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String SEEKBAR_FIELD = "mVolumeSeekbar";
    private static final String AUDIO_FIELD = "mAudioManager";
    private static final String[] ROW_VIEWS = {
            "com.android.systemui.volume.BydDialogVolumeNaviView",
            "com.android.systemui.volume.BydDialogVolumeRowView",
            "com.android.systemui.volume.BydDialogVolumeVoiceView",
    };

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PrefsManager.isVolumeHookEnabled()) {
            return;
        }
        StringBuilder installed = new StringBuilder();
        for (String view : ROW_VIEWS) {
            try {
                XposedHelpers.findAndHookMethod(view, lpparam.classLoader, "initView", int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                useFrameworkRange(param.thisObject, (Integer) param.args[0]);
                            }
                        });
                installed.append(installed.length() == 0 ? "" : ", ")
                        .append(view, view.lastIndexOf('.') + 1, view.length());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": volume panel row " + view + " not hooked: " + t);
            }
        }
        XposedBridge.log(TAG + ": volume panel range hook installed for " + installed);
    }

    private static void useFrameworkRange(Object view, int stream) {
        try {
            Object seekBar = XposedHelpers.getObjectField(view, SEEKBAR_FIELD);
            if (seekBar == null) {
                return;
            }
            AudioManager audio = (AudioManager) XposedHelpers.getObjectField(view, AUDIO_FIELD);
            int max = audio.getStreamMaxVolume(stream);
            int level = audio.getStreamVolume(stream);
            XposedHelpers.callMethod(seekBar, "setMax", max);
            // Raising the ceiling leaves the progress clamped at the old one until it is re-applied.
            XposedHelpers.callMethod(seekBar, "setProgress", level);
            XposedBridge.log(TAG + ": volume panel stream " + stream + " range " + max
                    + " level " + level);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": volume panel range not applied: " + t);
        }
    }
}
