package com.byd.dlink.customer.utils;

import android.content.Context;
import android.media.AudioManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The audio streams this device actually exposes.
 *
 * BYD adds streams the Android SDK has no constants for (navigation, FM, AUX, ...), so the list
 * is built by asking AudioManager for each stream type's step count and keeping the ones it
 * answers for. That way a ROM that gains or drops a stream shows up in the settings screen
 * without a code change.
 */
public final class VolumeStreams {

    public static final class Stream {
        public final int type;
        public final String label;
        public final int steps;

        Stream(int type, String label, int steps) {
            this.type = type;
            this.label = label;
            this.steps = steps;
        }
    }

    // AudioService rejects a stream type past the last one it knows, so this only bounds the
    // probe loop; whatever the device does not have is skipped by the getter throwing.
    private static final int PROBE_LIMIT = 64;

    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        LABELS.put("STREAM_VOICE_CALL", "通话");
        LABELS.put("STREAM_SYSTEM", "系统");
        LABELS.put("STREAM_SYSTEM_ENFORCED", "系统强制");
        LABELS.put("STREAM_RING", "铃声");
        LABELS.put("STREAM_MUSIC", "媒体");
        LABELS.put("STREAM_ALARM", "闹钟");
        LABELS.put("STREAM_NOTIFICATION", "通知");
        LABELS.put("STREAM_BLUETOOTH_SCO", "蓝牙通话");
        LABELS.put("STREAM_DTMF", "按键音");
        LABELS.put("STREAM_TTS", "语音播报");
        LABELS.put("STREAM_ACCESSIBILITY", "无障碍");
        LABELS.put("STREAM_NAVI", "导航");
        LABELS.put("STREAM_FM", "FM 收音机");
        LABELS.put("STREAM_AUX", "辅助音频");
        LABELS.put("STREAM_BT_MUSIC", "蓝牙媒体");
        LABELS.put("STREAM_BTTS", "蓝牙语音");
        LABELS.put("STREAM_MUTE", "静音");
    }

    private VolumeStreams() {
    }

    public static List<Stream> list(Context context) {
        List<Stream> streams = new ArrayList<>();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return streams;
        }
        Map<Integer, String> names = constantNames();
        for (int type = 0; type < PROBE_LIMIT; type++) {
            int steps;
            try {
                steps = audio.getStreamMaxVolume(type);
            } catch (Throwable ignored) {
                continue;
            }
            if (steps <= 0) continue;
            streams.add(new Stream(type, label(names, type), steps));
        }
        return streams;
    }

    private static String label(Map<Integer, String> names, int type) {
        String constant = names.get(type);
        if (constant == null) return "STREAM_" + type;
        String chinese = LABELS.get(constant);
        return chinese != null ? chinese + " · " + constant : constant;
    }

    private static Map<Integer, String> constantNames() {
        Map<Integer, String> names = new HashMap<>();
        try {
            for (Field field : AudioManager.class.getFields()) {
                if (!field.getName().startsWith("STREAM_") || field.getType() != int.class) {
                    continue;
                }
                names.put((Integer) field.get(null), field.getName());
            }
        } catch (Throwable ignored) {
            // Without the constants the rows still work; they just carry the raw type number.
        }
        return names;
    }
}
