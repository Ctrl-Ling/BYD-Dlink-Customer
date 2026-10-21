package com.byd.dlink.customer.hook;

import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.lang.reflect.Field;

import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BYD's 声道 is not an output device. The ROM patched AudioAttributes with content types of its own
 * -- measured on the device by walking the builder: 5 BTMUSIC, 6 NAVI, 7 MUTE, 8 FM, 9 AUX,
 * 10 BTTS, above the AOSP 1..4 -- and its audio policy turns each one into a separate stream with a
 * separate hardware path. A test tone from this module, read back in dumpsys media.audio_flinger:
 * content 2 became stream 3 on the deep-buffer output, content 6 became stream 14 on a dedicated
 * AUDIO_OUTPUT_FLAG_NAVI / _UE / _GAODE output, content 8 became stream 11 and content 10 stream 16,
 * both on the primary output. So an app whose attributes say content 6 is on the navigation channel
 * -- navigation volume ladder, navigation ducking, navigation speaker group -- whatever it thinks it
 * is playing. That is the same lever 高德 exposes as its hidden 音频通道 = 14, 14 being the stream BYD
 * derives from content 6.
 *
 * The channel is settled while the player object is created, so nothing that runs after the track
 * exists can move it, and the constructors are not hookable here. What is hookable is the framework's
 * own attribute builder, which every app that names a channel passes through, and MediaPlayer's
 * prepare(), which is where a player that never named one can still be placed.
 */
public class AppChannelHook {

    private static final String TAG = "AppChannel";

    private static String packageName;
    private static Field contentTypeField;
    private static Field usageField;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        // Android loads the WebView package into the app's own process, so handleLoadPackage runs
        // twice for one process and these statics are shared by both. The app's load is first; a
        // later one must not re-point the channel at a package nobody configured.
        if (packageName != null) {
            return;
        }
        packageName = lpparam.packageName;
        contentTypeField = intField(AudioAttributes.class, "mContentType", "content");
        usageField = intField(AudioAttributes.class, "mUsage", "usage");
        try {
            XposedHelpers.findAndHookMethod(AudioAttributes.Builder.class, "build",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            forceChannel((AudioAttributes) param.getResult());
                        }
                    });
            XC_MethodHook beforePrepare = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    placePlayerIfUnset((MediaPlayer) param.thisObject);
                }
            };
            XposedHelpers.findAndHookMethod(MediaPlayer.class, "prepare", beforePrepare);
            XposedHelpers.findAndHookMethod(MediaPlayer.class, "prepareAsync", beforePrepare);
            XposedBridge.log(TAG + ": app channel hooks installed in " + packageName
                    + " (contentTypeField=" + contentTypeField + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": app channel hooks incomplete in " + packageName + ": " + t);
        }
    }

    /**
     * Media playback is the only thing re-channeled. Attributes describing a notification, an alarm
     * or a microphone have no business arriving on the navigation output, and a channel the app
     * picked deliberately is left as it chose it.
     */
    private static void forceChannel(AudioAttributes attributes) {
        if (attributes == null) {
            return;
        }
        int channel = PrefsManager.getChannelFor(packageName);
        if (channel < 0) {
            return;
        }
        int usage = read(usageField, attributes);
        int content = read(contentTypeField, attributes);
        if (usage != AudioAttributes.USAGE_MEDIA) {
            return;
        }
        if (content != AudioAttributes.CONTENT_TYPE_UNKNOWN
                && content != AudioAttributes.CONTENT_TYPE_MUSIC) {
            return;
        }
        if (content == channel || contentTypeField == null) {
            return;
        }
        try {
            contentTypeField.setInt(attributes, channel);
            XposedBridge.log(TAG + ": " + packageName + " media content " + content
                    + " -> " + channel);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + packageName + " content write refused: " + t);
        }
    }

    /**
     * A player that never built attributes at all gets them here, before prepare() hands the player
     * to the framework, which would otherwise default it to stream 3. Attributes the app did name
     * are run through the same rule the builder uses.
     */
    private static void placePlayerIfUnset(MediaPlayer player) {
        int channel = PrefsManager.getChannelFor(packageName);
        if (channel < 0) {
            return;
        }
        AudioAttributes existing;
        try {
            existing = (AudioAttributes) XposedHelpers.getObjectField(player, "mAudioAttributes");
        } catch (Throwable t) {
            existing = null;
        }
        if (existing != null) {
            forceChannel(existing);
            return;
        }
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(channel).build());
            XposedBridge.log(TAG + ": " + packageName + " MediaPlayer placed on content " + channel);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + packageName + " MediaPlayer not placed: " + t);
        }
    }

    /**
     * The field that has to change belongs to the framework, and its name is not ours to assume:
     * ask for the documented one, then fall back to the only int field whose name mentions it.
     */
    private static Field intField(Class<?> cls, String documented, String contains) {
        try {
            Field exact = cls.getDeclaredField(documented);
            exact.setAccessible(true);
            return exact;
        } catch (Throwable ignored) {
        }
        try {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() == int.class
                        && field.getName().toLowerCase().contains(contains)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int read(Field field, Object target) {
        if (field == null) {
            return -1;
        }
        try {
            return field.getInt(target);
        } catch (Throwable t) {
            return -1;
        }
    }
}
