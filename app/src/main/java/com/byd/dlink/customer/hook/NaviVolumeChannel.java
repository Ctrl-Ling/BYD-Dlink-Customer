package com.byd.dlink.customer.hook;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The step count is not what limits the navigation volume: with MAX_STREAM_VOLUME patched to 20 the
 * raw index reaches the audio policy ("Index Cur 0002 : 20") and BYD's own mirror
 * (Settings.System.volume_navi_speaker reads 20), yet past 10 the speaker does not change. BYD's amp
 * entry point is android.hardware.bydauto.audio.BYDAutoAudioDevice, which carries its own
 * setNaviVolume(int)/getNaviVolume() pair and declares NAVI_VOLUME_MAX=10 beside AUDIO_VOLUME_MAX=100
 * for the media channel.
 *
 * Whether that 10 is the amp's real ladder or only a bound the BYD framework imposes decides if a
 * 20-step navigation volume can sound different at all. Measured: setNaviVolume(15) answers
 * AUDIO_COMMAND_INVALID_VALUE and getNaviVolume() reads 10 back, so the Java entry point validates
 * against NAVI_VOLUME_MAX, and sweeping the Android index 1..20 makes the amp follow min(index, 10)
 * while BYD's mirror keeps the raw index.
 *
 * Two things still decide whether finer navigation steps are reachable at all. Measured: with the
 * caller hook live in SystemUI, moving the Android index 8 -> 3 moved the amp level in the same
 * second and the hook never fired, and services.jar's AudioService has no navigation volume method
 * (only getNaviMuteState/setNaviMuteState), so SystemUI is not what drives the amp. The same hook
 * still needs one boot to install in system_server, where BYD's own BYDAutoVolumeService lives, so
 * that is the one Java caller left to rule out. The second open question is what the transport
 * underneath setNaviVolume accepts; see rawWrite.
 */
public class NaviVolumeChannel {

    private static final String TAG = "BYD Dlink Customer";
    private static final String NAVI_STREAM = "STREAM_NAVI";
    private static final String AUDIO_DEVICE = "android.hardware.bydauto.audio.BYDAutoAudioDevice";
    private static final String SET_NAVI_VOLUME = "setNaviVolume";
    private static final String EVENT_VALUE = "android.hardware.bydauto.BYDAutoEventValue";
    private static final int STACK_FRAMES = 14;

    /**
     * The navigation level reaches the amp through somebody's call of setNaviVolume. Logging that
     * stack in the processes this module already loads into (system_server, SystemUI) is what tells
     * a Java-side clamp we could widen from a native one we could not.
     */
    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> deviceClass = XposedHelpers.findClass(AUDIO_DEVICE, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(deviceClass, SET_NAVI_VOLUME, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": setNaviVolume(" + param.args[0]
                                    + ") from " + lpparam.processName + " <- " + callerStack());
                        }
                    });
            XposedBridge.log(TAG + ": navi amp caller hook installed");
        } catch (Throwable t) {
            // Absent class or signature: nothing to log, and no reason to bother the boot log.
            XposedBridge.log(TAG + ": navi amp caller hook not installed: " + t);
        }
    }

    private static String callerStack() {
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        // Frames 0..1 are this method and the hook callback itself.
        for (int i = 2; i < frames.length && i < STACK_FRAMES; i++) {
            stack.append(stack.length() == 0 ? "" : " <- ").append(frames[i]);
        }
        return stack.toString();
    }

    public static void probe(Context context, Intent intent) {
        int stream = naviStream();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (stream >= 0 && audio != null) {
            XposedBridge.log(TAG + ": navi stream " + NAVI_STREAM + "=" + stream
                    + " level=" + audio.getStreamVolume(stream)
                    + " max=" + audio.getStreamMaxVolume(stream));
        }
        if (intent.getBooleanExtra("devices", false) && audio != null) {
            dumpOutputDevices(audio);
        }
        if (intent.getBooleanExtra("track", false)) {
            dumpPlayerEntryPoints();
        }
        if (intent.getBooleanExtra("ctor", false)) {
            dumpConstructors();
        }
        if (intent.getBooleanExtra("ct", false)) {
            dumpContentTypes();
        }
        if (intent.getBooleanExtra("streammap", false)) {
            dumpStreamMap();
        }
        int toneCt = intent.getIntExtra("tone", -1);
        if (toneCt >= 0) {
            playTone(intent.getIntExtra("us", 1), toneCt, intent.getIntExtra("vol", 100),
                    intent.getIntExtra("amp", 0) != 0);
        }
        int requested = intent.getIntExtra("navi", -1);
        Object device = acquireAudioDevice(context);
        if (device == null) {
            return;
        }
        // Whether BYD's own amp levels track the Android index at all decides which of the two
        // ladders is the one the driver hears.
        XposedBridge.log(TAG + ": amp state: getNaviVolume=" + read(device, "getNaviVolume")
                + " getVolume=" + read(device, "getVolume")
                + " ctrlMode=" + read(device, "getVolumeCtrlMode")
                + " source=" + read(device, "getVolumeSettingsource")
                + " mute=" + read(device, "getMuteState")
                + " naviMute=" + read(device, "getNaviMuteState")
                + " mirror=" + mirror(context));
        if (intent.getBooleanExtra("dump", false)) {
            dumpSurface(device);
        }
        int rawId = intent.getIntExtra("rawid", -1);
        if (rawId >= 0) {
            rawWrite(device, rawId, intent.getIntExtra("rawval", -1));
        }
        if (requested >= 0) {
            drive(context, audio, stream, device, requested);
        }
    }

    /**
     * What a per-app route can actually offer is whatever this ROM exposes, and a car head unit's
     * list is not the AOSP one. Neither dumpsys shows it, so ask the running framework and print
     * the same numbers the hook will later compare against.
     */
    private static void dumpOutputDevices(AudioManager audio) {
        AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        XposedBridge.log(TAG + ": output devices " + devices.length);
        for (AudioDeviceInfo d : devices) {
            XposedBridge.log(TAG + ": device type=" + d.getType() + " id=" + d.getId()
                    + " addr=\"" + d.getAddress() + "\" product=\"" + d.getProductName() + "\""
                    + " sink=" + d.isSink()
                    + " encodings=" + java.util.Arrays.toString(d.getEncodings()));
        }
    }

    /**
     * A per-app output channel has to be set before the track exists, and the constructors are not
     * hookable here -- so the entry point is whatever private method every constructor funnels into.
     * Which methods those are on this fork is exactly what cannot be assumed from AOSP.
     */
    private static void dumpPlayerEntryPoints() {
        Class<?>[] players = new Class<?>[] {
                android.media.AudioTrack.class, android.media.MediaPlayer.class,
                android.media.SoundPool.class };
        for (Class<?> cls : players) {
            Method[] all;
            try {
                all = cls.getDeclaredMethods();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + cls.getSimpleName() + " methods unreadable: " + t);
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (Method m : all) {
                String name = m.getName();
                if (!name.contains("init") && !name.contains("prepare")
                        && !name.equals("setAudioStreamType") && !name.equals("setDataSource")) {
                    continue;
                }
                sb.append("  ").append(name).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(params[i].getSimpleName());
                }
                sb.append(")");
            }
            XposedBridge.log(TAG + ": " + cls.getSimpleName() + " methods=" + all.length
                    + " entries:" + sb);
        }
    }

    /**
     * BYD's own audio policy routes a custom content type onto the navigation output, and
     * dumpsys names those types (NAVI, AUX, BTMUSIC, BTTS, FM, MUTE). Whether an app may ask for
     * one is decided by the framework's builder validation, so walk the whole range and let the
     * ROM's own toString() label each accepted value: that is the channel list the settings picker
     * can be built from without hardcoding a single number.
     */
    private static void dumpContentTypes() {
        for (int ct = 0; ct <= 14; ct++) {
            try {
                XposedBridge.log(TAG + ": contentType " + ct + " -> "
                        + new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(ct).build());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": contentType " + ct + " rejected: " + t);
            }
        }
        for (int usage = 0; usage <= 17; usage++) {
            try {
                XposedBridge.log(TAG + ": usage " + usage + " -> "
                        + new AudioAttributes.Builder().setUsage(usage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": usage " + usage + " rejected: " + t);
            }
        }
    }

    /**
     * The channel is fixed when the player object is constructed, so a per-app override has to sit
     * on a constructor. Which constructors are reachable from inside a hooked process -- and how
     * many methods reflection is even willing to list for AudioTrack -- is what this measures.
     */
    private static void dumpConstructors() {
        Class<?>[] targets = new Class<?>[] {
                AudioTrack.class, AudioTrack.Builder.class, android.media.MediaPlayer.class,
                android.media.SoundPool.class,
                AudioAttributes.Builder.class };
        for (Class<?> cls : targets) {
            StringBuilder sb = new StringBuilder();
            try {
                Constructor<?>[] ctors = cls.getDeclaredConstructors();
                for (Constructor<?> c : ctors) {
                    sb.append("  <init>(");
                    Class<?>[] params = c.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        sb.append(i == 0 ? "" : ",").append(params[i].getSimpleName());
                    }
                    sb.append(")");
                }
                XposedBridge.log(TAG + ": " + cls.getSimpleName() + " ctors="
                        + ctors.length + " methods=" + cls.getDeclaredMethods().length
                        + ":" + sb);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + cls.getSimpleName() + " ctors unreadable: " + t
                        + " read so far:" + sb);
            }
        }
    }

    /**
     * The settings screen has to print the number the car names a channel by (导航 is 14, not 6), and
     * two candidate sources exist: the framework's own attribute-to-stream helper, which is hidden
     * API, and the stream an AudioTrack reports for the attributes it was built with, which is
     * public. Which of them BYD actually patched -- and whether the hidden one is even reachable
     * from a hooked process -- is measured here rather than assumed.
     */
    private static void dumpStreamMap() {
        Method policyStream = null;
        try {
            policyStream = Class.forName("android.media.AudioPolicy")
                    .getMethod("getStreamForAttributes", AudioAttributes.class);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AudioPolicy.getStreamForAttributes unreadable: " + t);
        }
        for (int ct = 1; ct <= 14; ct++) {
            AudioAttributes attrs;
            try {
                attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(ct).build();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": streammap ct " + ct + " attrs refused: " + t);
                continue;
            }
            String byPolicy = policyStream == null ? "no-method" : call(policyStream, attrs);
            XposedBridge.log(TAG + ": streammap ct=" + ct + " policy=" + byPolicy
                    + " track=" + trackStream(attrs));
        }
    }

    private static String call(Method method, AudioAttributes attrs) {
        try {
            return String.valueOf(method.invoke(null, attrs));
        } catch (Throwable t) {
            return "threw " + t;
        }
    }

    @SuppressWarnings("deprecation")
    private static String trackStream(AudioAttributes attrs) {
        AudioTrack track = null;
        try {
            int sampleRate = 8000;
            int buffer = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
            return String.valueOf(track.getStreamType());
        } catch (Throwable t) {
            return "threw " + t;
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * The one question the whole feature rests on: will this ROM put a track built by an ordinary
     * app onto BYD's navigation output? The answer is in which AudioFlinger thread the track
     * appears on, so the tone only has to stay alive long enough for the dump to be taken.
     *
     * @param attenuateInSamples whether the second half loses level in the PCM it writes rather
     * than through the track's own volume, which the navi output was measured ignoring.
     */
    private static void playTone(int usage, int contentType, int volumePercent,
            boolean attenuateInSamples) {
        final AudioTrack track;
        final float volume = Math.max(0, Math.min(100, volumePercent)) / 100f;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(usage)
                    .setContentType(contentType).build();
            int sampleRate = 8000;
            int channelMask = AudioFormat.CHANNEL_OUT_MONO;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            final int buffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding) * 4;
            track = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(encoding)
                            .setSampleRate(sampleRate).setChannelMask(channelMask).build())
                    .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
            XposedBridge.log(TAG + ": tone attrs=" + attributes + " state=" + track.getState()
                    + " buffer=" + buffer + " cfg=" + configuration(track));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": tone track refused usage=" + usage
                    + " contentType=" + contentType + ": " + t);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                short[] samples = new short[8000];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) ((i % 40 < 20 ? 1 : -1) * 12000);
                }
                final short[] attenuated = new short[8000];
                for (int i = 0; i < attenuated.length; i++) {
                    attenuated[i] = (short) (samples[i] * volume);
                }
                try {
                    track.play();
                    XposedBridge.log(TAG + ": tone started, cfg=" + configuration(track));
                    // First half at full scale, second half at the asked-for one: one broadcast is
                    // then both the control and the measurement, and the answer is a single sentence
                    // about whether the tone got quieter partway through.
                    for (int second = 0; second < 8; second++) {
                        short[] out = samples;
                        if (second == 4) {
                            if (attenuateInSamples) {
                                out = attenuated;
                                XposedBridge.log(TAG + ": tone amplitude -> " + volume
                                        + " written into the samples, track volume untouched");
                            } else {
                                int status = track.setStereoVolume(volume, volume);
                                XposedBridge.log(TAG + ": tone volume -> " + volume
                                        + " status=" + status);
                            }
                        }
                        track.write(out, 0, out.length);
                    }
                    track.stop();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": tone failed: " + t);
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
                XposedBridge.log(TAG + ": tone finished");
            }
        }).start();
    }

    /**
     * What the framework knows about where this track ended up. The getters are hidden API, so
     * rather than name any of them, print every no-argument scalar the object offers.
     */
    private static String configuration(AudioTrack track) {
        try {
            Object cfg = XposedHelpers.callMethod(track, "getPlaybackConfiguration");
            StringBuilder sb = new StringBuilder(String.valueOf(cfg));
            for (Method m : cfg.getClass().getMethods()) {
                if (!m.getName().startsWith("get") || m.getParameterTypes().length != 0) {
                    continue;
                }
                try {
                    Object value = m.invoke(cfg);
                    if (value instanceof Integer || value instanceof Long
                            || value instanceof Boolean || value instanceof String) {
                        sb.append(' ').append(m.getName()).append('=').append(value);
                    }
                } catch (Throwable ignored) {
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "unreadable: " + t;
        }
    }

    /**
     * setNaviVolume is BYD's validated entry point: 15 answers AUDIO_COMMAND_INVALID_VALUE and the
     * level reads back 10. Underneath every typed setter on these devices sits a transport that
     * addresses the CAN feature by id -- AbsBYDAutoDevice#set(int[], BYDAutoEventValue), and the
     * manager's setInt -- which may or may not repeat that bound. Writing the navigation id
     * (BYDAutoFeatureIds.AUDIO_NAVI_VOLUME_SET) through it is the last test of whether the amp's
     * ladder stops at 10 or only BYD's client-side check does: an accepted 15 that reads back as 15
     * means the ceiling is ours to move, a refused one means 10 is the amp's own top rung and no
     * hook can put a step between 9 and 10.
     */
    private static void rawWrite(Object device, int featureId, int value) {
        XposedBridge.log(TAG + ": raw " + featureId + "=" + value + " before getNaviVolume="
                + read(device, "getNaviVolume"));
        Class<?> eventClass;
        try {
            eventClass = Class.forName(EVENT_VALUE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + EVENT_VALUE + " unavailable: " + t);
            return;
        }
        XposedBridge.log(TAG + ": " + eventClass.getName() + " constructors "
                + constructors(eventClass) + " factories " + factories(eventClass));
        Method transport = eventTransport(device.getClass(), eventClass);
        if (transport == null) {
            XposedBridge.log(TAG + ": no set(int[], " + eventClass.getSimpleName() + ") on "
                    + device.getClass().getName());
            return;
        }
        for (Constructor<?> ctor : valueConstructors(eventClass)) {
            Object boxed = boxArgument(ctor, value);
            if (boxed == null) continue;
            Object eventValue;
            try {
                eventValue = ctor.newInstance(boxed);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + eventClass.getSimpleName() + " could not be built from "
                        + ctor + ": " + t);
                continue;
            }
            Object status = invokeRaw(transport, device, featureId, eventValue);
            XposedBridge.log(TAG + ": raw " + featureId + "=" + value + " through " + ctor
                    + " -> status=" + status + " getNaviVolume=" + read(device, "getNaviVolume"));
            if (!(status instanceof Throwable)) return;
        }
        XposedBridge.log(TAG + ": no " + eventClass.getSimpleName()
                + " constructor took " + value);
    }

    private static Object invokeRaw(Method transport, Object device, int featureId, Object value) {
        try {
            return transport.invoke(device, new int[] {featureId}, value);
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * BYDAutoEventValue is published with one constructor per payload type, and the int one is what
     * a volume is; try it before the float and double forms so the first answer is the right one.
     */
    private static List<Constructor<?>> valueConstructors(Class<?> eventClass) {
        List<Constructor<?>> ranked = new ArrayList<>();
        for (Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0] == int.class) ranked.add(ctor);
        }
        for (Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0] != int.class) ranked.add(ctor);
        }
        return ranked;
    }

    /** Boxes the value the way each constructor wants it, so one probe can try them all. */
    private static Object boxArgument(Constructor<?> ctor, int value) {
        Class<?>[] params = ctor.getParameterTypes();
        if (params.length != 1) return null;
        Class<?> first = params[0];
        if (first == int.class || first == Integer.class || first == Object.class) return value;
        if (first == long.class) return (long) value;
        if (first == float.class) return (float) value;
        if (first == double.class) return (double) value;
        return null;
    }

    private static Method eventTransport(Class<?> clazz, Class<?> eventClass) {
        for (Class<?> current = clazz; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getName().equals("set") && params.length == 2
                        && params[0] == int[].class && params[1] == eventClass) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String constructors(Class<?> clazz) {
        StringBuilder out = new StringBuilder();
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            out.append(out.length() == 0 ? "" : ", ").append(ctor.toString());
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static String factories(Class<?> clazz) {
        StringBuilder out = new StringBuilder();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != clazz) {
                continue;
            }
            StringBuilder params = new StringBuilder();
            for (Class<?> type : method.getParameterTypes()) {
                params.append(params.length() == 0 ? "" : ",").append(type.getSimpleName());
            }
            out.append(out.length() == 0 ? "" : ", ")
                    .append(method.getName()).append('(').append(params).append(')');
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /**
     * The bound that rejects 15 sits in setNaviVolume itself; underneath it, BYD's devices share
     * AbsBYDAutoDevice's raw feature transport, which addresses a value by (feature id, value) and
     * may not repeat the bound. Printing the class chain, its int-taking methods and the navigation
     * feature ids is what makes that path callable.
     */
    private static void dumpSurface(Object device) {
        for (Class<?> current = device.getClass();
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            XposedBridge.log(TAG + ": surface " + current.getName()
                    + " interfaces " + names(current.getInterfaces()));
            XposedBridge.log(TAG + ": declared methods of " + current.getName() + ": "
                    + methodSignatures(current));
            XposedBridge.log(TAG + ": int constants of " + current.getName() + ": "
                    + intConstants(current));
            dumpFeatureIds(current);
        }
    }

    // The feature id table lives beside the device classes, not inside them, and its package is
    // only known once the chain is walked; derive it instead of assuming it.
    private static void dumpFeatureIds(Class<?> from) {
        String name = from.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0) return;
        try {
            Class<?> ids = Class.forName(name.substring(0, lastDot) + ".BYDAutoFeatureIds");
            XposedBridge.log(TAG + ": feature ids " + ids.getName() + ": " + navVolumeConstants(ids));
        } catch (Throwable ignored) {
            // Not every class in the chain shares that package; only the matching one resolves.
        }
    }

    private static String navVolumeConstants(Class<?> ids) {
        StringBuilder out = new StringBuilder();
        for (Field field : ids.getDeclaredFields()) {
            String name = field.getName().toUpperCase();
            if (!(name.contains("NAVI") || name.contains("VOLUME"))) continue;
            String pair = field.getName() + "=" + staticInt(field);
            out.append(out.length() == 0 ? "" : ", ").append(pair);
        }
        return out.length() == 0 ? "none named NAVI/VOLUME" : out.toString();
    }

    private static String methodSignatures(Class<?> clazz) {
        StringBuilder out = new StringBuilder();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            StringBuilder params = new StringBuilder();
            for (Class<?> type : method.getParameterTypes()) {
                params.append(params.length() == 0 ? "" : ",").append(type.getSimpleName());
            }
            out.append(out.length() == 0 ? "" : ", ")
                    .append(method.getReturnType().getSimpleName())
                    .append(' ').append(method.getName()).append('(').append(params).append(')');
        }
        return out.toString();
    }

    private static String intConstants(Class<?> clazz) {
        StringBuilder out = new StringBuilder();
        for (Field field : clazz.getDeclaredFields()) {
            String value = staticInt(field);
            if (value == null) continue;
            out.append(out.length() == 0 ? "" : ", ").append(field.getName()).append('=').append(value);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static String staticInt(Field field) {
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                || field.getType() != int.class) {
            return null;
        }
        try {
            field.setAccessible(true);
            return String.valueOf(field.getInt(null));
        } catch (Throwable t) {
            return "unreadable";
        }
    }

    private static String names(Class<?>[] types) {
        StringBuilder out = new StringBuilder();
        for (Class<?> type : types) {
            out.append(out.length() == 0 ? "" : ",").append(type.getName());
        }
        return out.toString();
    }

    private static void drive(Context context, AudioManager audio, int stream, Object device,
            int requested) {
        XposedBridge.log(TAG + ": navi drive " + requested + ": before getNaviVolume="
                + read(device, "getNaviVolume") + " getVolume=" + read(device, "getVolume"));
        // setNaviVolume answers with BYD's own command status (AUDIO_COMMAND_SUCCESS=0,
        // AUDIO_COMMAND_INVALID_VALUE=-2147482645), so a rejected value reports itself.
        Object status = write(device, "setNaviVolume", requested);
        if (status instanceof Throwable) {
            XposedBridge.log(TAG + ": navi drive setNaviVolume(" + requested
                    + ") threw: " + status);
            return;
        }
        XposedBridge.log(TAG + ": navi drive " + requested + ": status=" + status
                + " getNaviVolume=" + read(device, "getNaviVolume")
                + " getVolume=" + read(device, "getVolume")
                + " mirror=" + mirror(context)
                + (stream < 0 || audio == null ? "" : " stream=" + audio.getStreamVolume(stream)));
    }

    // BYD keeps the navi level in Settings.System.volume_navi_speaker; whether a direct
    // setNaviVolume moves it says if the two are the same channel or two independent ones.
    private static String mirror(Context context) {
        try {
            return String.valueOf(android.provider.Settings.System.getInt(
                    context.getContentResolver(), "volume_navi_speaker"));
        } catch (Throwable t) {
            return "unreadable";
        }
    }

    /** BYDAutoAudioDevice publishes itself as a Context singleton, which is the shortest path in. */
    private static Object acquireAudioDevice(Context context) {
        try {
            Class<?> deviceClass = Class.forName(AUDIO_DEVICE);
            Object device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            if (device != null) {
                XposedBridge.log(TAG + ": audio device handle " + device.getClass().getName());
            }
            return device;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": audio device handle failed: " + t);
            return null;
        }
    }

    private static Object read(Object device, String getter) {
        try {
            return device.getClass().getMethod(getter).invoke(device);
        } catch (Throwable t) {
            return "failed " + t;
        }
    }

    private static Object write(Object device, String setter, int value) {
        try {
            return device.getClass().getMethod(setter, int.class).invoke(device, value);
        } catch (Throwable t) {
            return t;
        }
    }

    // BYD's navigation stream has no SDK constant, and the module must not assume its number.
    private static int naviStream() {
        try {
            return AudioManager.class.getField(NAVI_STREAM).getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }
}
