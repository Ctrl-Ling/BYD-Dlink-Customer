package com.byd.dlink.customer.hook;

import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Every audio stream's step count comes from two static tables, MAX_STREAM_VOLUME and
 * MIN_STREAM_VOLUME, that AudioService reads while building one VolumeStreamState per stream --
 * and each state hands its own (min, max) to AudioSystem.initStreamVolume, which is what
 * registers the range with the audio policy.
 *
 * The tables are not stable until createStreamStates is entered: AudioService's constructor first
 * overwrites every entry with AudioSystem.getMaxVolumeIndexForAttributes, then applies
 * ro.config.{vc_call,media,alarm,system}_vol_steps on top. So the rewrite happens in a before-hook
 * of createStreamStates, the last point after all of that and the last point before the states are
 * built. Rewriting any earlier is silently clobbered.
 *
 * No getter needs a hook of its own: the index AudioService stores, the range the native policy
 * clamps to, and every client's getStreamMaxVolume answer through the binder all follow the table,
 * which is why this installs once in system_server instead of chasing each volume UI.
 *
 * createStreamStates runs at boot, so a saved change reaches the device on the next restart.
 */
public class VolumeStepHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final String AUDIO_SERVICE = "com.android.server.audio.AudioService";
    private static final String CREATE_STREAM_STATES = "createStreamStates";
    private static final String MAX_TABLE = "MAX_STREAM_VOLUME";
    private static final String MIN_TABLE = "MIN_STREAM_VOLUME";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PrefsManager.isVolumeHookEnabled()) {
            return;
        }
        Class<?> service;
        try {
            service = XposedHelpers.findClass(AUDIO_SERVICE, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AudioService not found for volume steps: " + t);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(service, CREATE_STREAM_STATES,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            applyStepOverrides(service);
                        }
                    });
            XposedBridge.log(TAG + ": volume step hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": createStreamStates not hooked: " + t);
        }
    }

    private static void applyStepOverrides(Class<?> service) {
        int[] maxSteps = readTable(service, MAX_TABLE);
        if (maxSteps == null) {
            return;
        }
        int[] minSteps = readTable(service, MIN_TABLE);
        StringBuilder patched = new StringBuilder();
        for (int stream = 0; stream < maxSteps.length; stream++) {
            int configured = PrefsManager.getVolumeSteps(stream);
            if (configured < PrefsManager.STEPS_MIN || configured == maxSteps[stream]) {
                continue;
            }
            maxSteps[stream] = configured;
            // A floor above the new ceiling would leave the stream with no reachable step.
            if (minSteps != null && stream < minSteps.length && minSteps[stream] > configured) {
                minSteps[stream] = configured;
            }
            patched.append(' ').append(stream).append('=').append(configured);
        }
        XposedBridge.log(TAG + ": volume steps"
                + (patched.length() == 0 ? " left at system values" : " overridden:" + patched));
    }

    private static int[] readTable(Class<?> service, String field) {
        try {
            Object table = XposedHelpers.getStaticObjectField(service, field);
            return table instanceof int[] ? (int[]) table : null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + field + " unreadable: " + t);
            return null;
        }
    }
}
