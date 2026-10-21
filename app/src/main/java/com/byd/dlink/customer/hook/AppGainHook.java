package com.byd.dlink.customer.hook;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.SoundPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.byd.dlink.customer.utils.PrefsManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Per-app output gain: the playing app hears master% x app% of whatever it asked for.
 *
 * Two levers, because the ROM honours only one of them per output. Scaling the player's own volume
 * call is the cheap one and works on the AOSP outputs. BYD's own channels (content 5..10) each sit
 * on a separate hardware output which was measured mixing every track at unity: a track whose
 * volume row read -40 dB still delivered its full sample amplitude to the HAL, while the same
 * amplitude written 26 dB quieter in the PCM itself arrived 26 dB quieter. So an app placed on one
 * of those channels is attenuated in the samples its AudioTrack writes, and its volume calls are
 * left exactly as the app made them -- scaling both levers on one track would square the factor.
 *
 * Both levers can only attenuate -- a factor above 1.0 would clip -- and the system volume, BYD
 * ducking and the amp ladder stay as built.
 *
 * The factor is read live from the config properties on every call, so moving a slider here
 * changes what a player hears without restarting anything.
 */
public class AppGainHook {

    private static final String TAG = "BYD Dlink Customer";
    private static final float[] FULL = {1f, 1f};

    /** Content types below this are AOSP's own, where the volume call was measured to work. */
    private static final int BYD_FIRST_CONTENT = 5;

    /**
     * What the app itself asked for, per player object. Hooks write raw * factor, never
     * current * factor, so neither a framework-internal re-entry (AudioTrack.setVolume(float)
     * delegates to the hooked two-channel overload) nor a play() re-assert can compound the scale.
     */
    private static final Map<Object, float[]> REQUESTED =
            Collections.synchronizedMap(new WeakHashMap<Object, float[]>());

    /** A track's sample format never changes once it exists, so it is worth remembering. */
    private static final Map<Object, Integer> ENCODINGS =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    /** Only to keep the log to one line per track: writes are per buffer, not per playback. */
    private static final Map<Object, Boolean> SCALED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private static String packageName;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        // A process can be told about more than one package: Android WebView is loaded into the
        // app's own process as com.android.webview, and these statics are shared by both loads.
        // The app's own load is always the first, so a later one must not re-point the gain at a
        // package nobody configured -- that silently turns the factor back into 1.0 for a player
        // that is already playing.
        if (packageName != null) {
            return;
        }
        packageName = lpparam.packageName;
        try {
            // setStereoVolume is the one sink both AudioTrack volume calls reach: setVolume(float)
            // delegates to it, so hooking it scales every path exactly once. play() re-asserts so
            // apps that never touch their volume -- the common case -- still get attenuated.
            XposedHelpers.findAndHookMethod(AudioTrack.class, "setStereoVolume",
                    float.class, float.class, scaleAt(0));
            XposedHelpers.findAndHookMethod(AudioTrack.class, "play",
                    reassertOn(new VolumeSetter() {
                        @Override
                        public int set(Object target, float left, float right) {
                            return ((AudioTrack) target).setStereoVolume(left, right);
                        }
                    }));

            XposedHelpers.findAndHookMethod(MediaPlayer.class, "setVolume",
                    float.class, float.class, scaleAt(0));
            XposedHelpers.findAndHookMethod(MediaPlayer.class, "start",
                    reassertOn(new VolumeSetter() {
                        @Override
                        public int set(Object target, float left, float right) {
                            ((MediaPlayer) target).setVolume(left, right);
                            return 0;
                        }
                    }));

            // SoundPool carries the volumes in play() itself and resets them per playback, so the
            // arguments are all that need scaling. Rate is a float too and must stay untouched --
            // hence the explicit indices instead of a sweep over the float arguments.
            XposedHelpers.findAndHookMethod(SoundPool.class, "setVolume",
                    int.class, float.class, float.class, scaleAt(1));
            XposedHelpers.findAndHookMethod(SoundPool.class, "play",
                    int.class, float.class, float.class, int.class, int.class, float.class,
                    scaleAt(1));

            installSampleScaling();

            XposedBridge.log(TAG + ": app gain hooks installed in " + packageName
                    + " (factor " + factor() + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": app gain hooks incomplete in " + packageName + ": " + t);
        }
    }

    /**
     * Scales the two channel volumes at {@code leftIndex}/{@code leftIndex + 1} of the hooked
     * call, after recording what the app asked for.
     */
    private static XC_MethodHook scaleAt(final int leftIndex) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (samplesInsteadOfVolume(param.thisObject)) {
                    return;
                }
                float left = (Float) param.args[leftIndex];
                float right = (Float) param.args[leftIndex + 1];
                REQUESTED.put(param.thisObject, new float[] {left, right});
                float factor = factor();
                param.args[leftIndex] = left * factor;
                param.args[leftIndex + 1] = right * factor;
                report(param.thisObject.getClass().getSimpleName() + " " + left + "x" + right
                        + " -> " + param.args[leftIndex] + "x" + param.args[leftIndex + 1]
                        + " (factor " + factor + ")");
            }
        };
    }

    /**
     * Applies a volume to a player object; the call itself passes back through these hooks.
     * Returns the status the setter reported, or -1 where the API has no return value.
     */
    private interface VolumeSetter {
        int set(Object target, float left, float right);
    }

    /**
     * Starting playback is the point where an app that never sets a volume still has to be
     * attenuated, and where an app that set one before playing gets the current factor applied.
     * The raw value goes back through the volume hook, which is what scales it -- passing an
     * already-scaled value here would square the factor.
     */
    private static XC_MethodHook reassertOn(final VolumeSetter setter) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getThrowable() != null || samplesInsteadOfVolume(param.thisObject)) {
                    return;
                }
                float factor = factor();
                float[] requested = REQUESTED.get(param.thisObject);
                if (requested == null) {
                    if (factor >= 1f) {
                        return;
                    }
                    requested = FULL;
                } else if (requested[0] == 0f && requested[1] == 0f) {
                    return;
                }
                int status = setter.set(param.thisObject, requested[0], requested[1]);
                report(param.thisObject.getClass().getSimpleName() + " play reassert "
                        + requested[0] + "x" + requested[1]
                        + " (factor " + factor + ") status=" + status);
            }
        };
    }

    /**
     * Attenuating the PCM is the only lever BYD's own outputs leave an app, so every write of a
     * track from an app sitting on one of them passes through here. An app on an AOSP channel keeps
     * its volume scaling, which was measured working there.
     */
    private static void installSampleScaling() {
        XC_MethodHook scaleWrites = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                float factor = factor();
                if (factor >= 1f || !bydChannel()) {
                    return;
                }
                AudioTrack track = (AudioTrack) param.thisObject;
                try {
                    if (!SCALED.containsKey(track)) {
                        SCALED.put(track, Boolean.TRUE);
                        report("AudioTrack writes scaled by " + factor
                                + " instead of by its volume");
                    }
                    scaleData(param.args, factor, track);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": " + packageName + " write not scaled: " + t);
                }
            }
        };
        XposedBridge.hookAllMethods(AudioTrack.class, "write", scaleWrites);
        XposedBridge.hookAllMethods(AudioTrack.class, "writeStatic", scaleWrites);
    }

    /** Whether this app's media was put on an output that mixes every track at unity. */
    private static boolean bydChannel() {
        return PrefsManager.getChannelFor(packageName) >= BYD_FIRST_CONTENT;
    }

    private static boolean samplesInsteadOfVolume(Object player) {
        return player instanceof AudioTrack && factor() < 1f && bydChannel();
    }

    /**
     * A write names its audio in one of four shapes, and for an array the offset and size are the
     * two ints after it; a ByteBuffer names its size only and starts where it is positioned.
     */
    private static void scaleData(Object[] args, float factor, AudioTrack track) {
        for (int i = 0; i < args.length; i++) {
            Object data = args[i];
            if (data instanceof short[]) {
                scaleRange((short[]) data, intAt(args, i + 1, 0),
                        intAt(args, i + 2, ((short[]) data).length), factor);
                return;
            }
            if (data instanceof float[]) {
                scaleRange((float[]) data, intAt(args, i + 1, 0),
                        intAt(args, i + 2, ((float[]) data).length), factor);
                return;
            }
            if (data instanceof byte[]) {
                byte[] bytes = (byte[]) data;
                scaleRange(bytes, intAt(args, i + 1, 0),
                        intAt(args, i + 2, bytes.length), factor, encoding(track));
                return;
            }
            if (data instanceof ByteBuffer) {
                ByteBuffer buffer = (ByteBuffer) data;
                if (buffer.isReadOnly()) {
                    return;
                }
                scaleRange(buffer, buffer.position(),
                        intAt(args, i + 1, buffer.remaining()), factor, encoding(track));
                return;
            }
        }
    }

    private static int intAt(Object[] args, int index, int fallback) {
        return index < args.length && args[index] instanceof Integer
                ? (Integer) args[index] : fallback;
    }

    private static void scaleRange(short[] data, int offset, int size, float factor) {
        for (int i = offset, end = Math.min(data.length, offset + size); i < end; i++) {
            data[i] = scale16(data[i] * factor);
        }
    }

    private static void scaleRange(float[] data, int offset, int size, float factor) {
        for (int i = offset, end = Math.min(data.length, offset + size); i < end; i++) {
            data[i] *= factor;
        }
    }

    private static void scaleRange(byte[] data, int offset, int size, float factor, int encoding) {
        int end = Math.min(data.length, offset + size);
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            for (int i = offset; i < end; i++) {
                data[i] = (byte) (scale16(((data[i] & 0xff) - 128) * factor) + 128);
            }
            return;
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT || (offset & 1) != 0) {
            return;
        }
        for (int i = offset; i + 1 < end; i += 2) {
            short scaled = scale16(((short) ((data[i] & 0xff) | (data[i + 1] << 8))) * factor);
            data[i] = (byte) (scaled & 0xff);
            data[i + 1] = (byte) (scaled >> 8);
        }
    }

    private static void scaleRange(ByteBuffer buffer, int offset, int size, float factor,
            int encoding) {
        int end = Math.min(buffer.limit(), offset + size);
        ByteOrder order = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                for (int i = offset; i + 4 <= end; i += 4) {
                    buffer.putFloat(i, buffer.getFloat(i) * factor);
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                for (int i = offset; i < end; i++) {
                    buffer.put(i, (byte) (scale16(((buffer.get(i) & 0xff) - 128) * factor) + 128));
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                for (int i = offset; i + 2 <= end; i += 2) {
                    buffer.putShort(i, scale16(buffer.getShort(i) * factor));
                }
            }
        } finally {
            buffer.order(order);
        }
    }

    private static short scale16(float value) {
        return (short) (value <= -32768f ? -32768 : value >= 32767f ? 32767 : (int) value);
    }

    /** Byte transfers do not say what they hold, and an odd one would be mangled by guessing. */
    private static int encoding(AudioTrack track) {
        Integer known = ENCODINGS.get(track);
        if (known != null) {
            return known.intValue();
        }
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        try {
            Object value = XposedHelpers.callMethod(track, "getEncoding");
            if (value instanceof Integer) {
                encoding = (Integer) value;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + packageName + " track encoding unreadable: " + t);
        }
        ENCODINGS.put(track, encoding);
        return encoding;
    }

    private static float factor() {
        return PrefsManager.gainFactorFor(packageName);
    }

    /**
     * Volume calls are rare (a change, or a playback start), so this stays readable in a log that
     * otherwise carries one line per process. It is the only way to tell "the app never asked for
     * a volume" apart from "the framework refused the one we wrote".
     */
    private static void report(String what) {
        XposedBridge.log(TAG + ": app gain " + packageName + ": " + what);
    }
}
