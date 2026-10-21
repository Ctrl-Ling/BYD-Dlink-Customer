package com.byd.dlink.customer.utils;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BYD's 声道 are content types the AOSP framework never had. They are not a list to copy out of a
 * datasheet: this ROM's AudioAttributes names them itself, so asking the builder for every value in
 * range and reading back the name it prints is what produces the menu -- a firmware that renames or
 * adds a channel changes the choices with no change here.
 *
 * The names are BYD's, the Chinese labels are what the car's own audio settings call them.
 */
public final class BydChannels {

    /** The hook writes content types; the car's own menus and 高德's 音频通道 show the stream. */
    public static final int NO_STREAM = -1;

    public static final class Channel {
        public final int contentType;
        public final int stream;
        public final String label;

        Channel(int contentType, int stream, String label) {
            this.contentType = contentType;
            this.stream = stream;
            this.label = label;
        }
    }

    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<Integer, Integer> STREAMS = new HashMap<>();

    static {
        LABELS.put("CONTENT_TYPE_SPEECH", "语音");
        LABELS.put("CONTENT_TYPE_MUSIC", "媒体");
        LABELS.put("CONTENT_TYPE_MOVIE", "影视");
        LABELS.put("CONTENT_TYPE_SONIFICATION", "提示音");
        LABELS.put("CONTENT_TYPE_BTMUSIC", "蓝牙媒体");
        LABELS.put("CONTENT_TYPE_NAVI", "导航");
        LABELS.put("CONTENT_TYPE_MUTE", "静音");
        LABELS.put("CONTENT_TYPE_FM", "FM 收音机");
        LABELS.put("CONTENT_TYPE_AUX", "辅助音频");
        LABELS.put("CONTENT_TYPE_BTTS", "蓝牙语音");
    }

    private BydChannels() {
    }

    /**
     * Every content type this ROM's own attribute builder accepts and gives a name to. A value the
     * builder rejects cannot be asked for by an app either, so it is not a choice worth showing.
     */
    public static List<Channel> discover() {
        List<Channel> channels = new ArrayList<>();
        for (int value = 1; value <= MAX_PROBED; value++) {
            String name = nameOf(value);
            // 0 is unknown by definition; anything above the named set reads back as unknown too.
            if (name == null || "CONTENT_TYPE_UNKNOWN".equals(name)) {
                continue;
            }
            String label = LABELS.get(name);
            channels.add(new Channel(value, streamOf(value),
                    label == null ? name : label));
        }
        return channels;
    }

    /** The framework's own name for one content type, or null where the builder refuses it. */
    public static String nameOf(int contentType) {
        try {
            return contentName(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType).build()
                    .toString());
        } catch (Throwable t) {
            return null;
        }
    }

    public static String labelFor(int contentType) {
        String name = nameOf(contentType);
        String label = name == null ? null : LABELS.get(name);
        return label == null ? String.valueOf(contentType) : label;
    }

    /** Label plus the stream number the car names this channel by, e.g. {@code 导航 14}. */
    public static String displayFor(int contentType) {
        int stream = streamOf(contentType);
        return stream == NO_STREAM ? labelFor(contentType) : labelFor(contentType) + " " + stream;
    }

    /**
     * The stream BYD's audio policy derives from a content type -- 6 becomes 14, which is why 高德
     * calls the navigation channel "音频通道 14". Asked of the framework that does the deriving: a
     * track built with those attributes reports the stream it was routed onto, so a firmware that
     * renumbers its channels renumbers this. (The Java-side helper the framework uses internally,
     * AudioPolicy.getStreamForAttributes, is not reachable even from a hooked process; measured, not
     * assumed.)
     */
    public static int streamOf(int contentType) {
        Integer cached = STREAMS.get(contentType);
        if (cached != null) {
            return cached.intValue();
        }
        int stream = readStream(contentType);
        STREAMS.put(contentType, Integer.valueOf(stream));
        return stream;
    }

    /**
     * The content type that plays on this stream, or {@link #NO_STREAM} when none does. Several
     * content types collapse onto the music stream, and a driver typing 3 means 媒体, so the AOSP
     * music type wins the tie instead of the lowest number.
     */
    public static int contentTypeForStream(int stream) {
        if (stream == NO_STREAM) {
            return NO_STREAM;
        }
        int first = NO_STREAM;
        for (int contentType = 1; contentType <= MAX_PROBED; contentType++) {
            if (streamOf(contentType) != stream) {
                continue;
            }
            if ("CONTENT_TYPE_MUSIC".equals(nameOf(contentType))) {
                return contentType;
            }
            if (first == NO_STREAM) {
                first = contentType;
            }
        }
        return first;
    }

    @SuppressWarnings("deprecation")
    private static int readStream(int contentType) {
        AudioTrack track = null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType).build();
            int buffer = AudioTrack.getMinBufferSize(PROBE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(PROBE_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
            return track.getStreamType();
        } catch (Throwable t) {
            return NO_STREAM;
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // Static mode and never played: the number is decided when the track is created, so nothing has
    // to reach the speaker.
    private static final int PROBE_SAMPLE_RATE = 8000;

    private static String contentName(String attributes) {
        int start = attributes.indexOf("content=");
        if (start < 0) {
            return null;
        }
        int end = attributes.indexOf(' ', start);
        return end < 0 ? attributes.substring(start + 8) : attributes.substring(start + 8, end);
    }

    // Above the last value BYD names, the builder still accepts integers but they all read back as
    // unknown, so a small margin past the named set is enough to notice a new channel.
    private static final int MAX_PROBED = 20;
}
