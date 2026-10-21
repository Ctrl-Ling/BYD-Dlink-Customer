package com.byd.dlink.customer.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

public class PrefsManager {

    public static final String CONFIG_FILE = "/data/local/tmp/byd_dlink_config.properties";

    public static final String KEY_GLOBAL_ENABLED = "global_enabled";
    public static final String KEY_SPLIT_SCREEN_ENABLED = "split_screen_enabled";
    public static final String KEY_SPLIT_BLACKLIST_DISABLED = "split_blacklist_disabled";
    public static final String KEY_STATUS_BAR_ENABLED = "status_bar_enabled";
    public static final String KEY_STATUS_BAR_HEIGHT = "status_bar_height";
    public static final String KEY_NAV_BAR_ENABLED = "nav_bar_enabled";
    public static final String KEY_NAV_BAR_HEIGHT = "nav_bar_height";
    public static final String KEY_STATUS_BAR_BG_ENABLED = "status_bar_bg_enabled";
    public static final String KEY_STATUS_BAR_BG_COLOR = "status_bar_bg_color";
    public static final String KEY_NAV_BAR_BG_ENABLED = "nav_bar_bg_enabled";
    public static final String KEY_NAV_BAR_BG_COLOR = "nav_bar_bg_color";
    public static final String KEY_STATUS_BAR_BG_IMMERSIVE = "status_bar_bg_immersive";
    public static final String KEY_NAV_BAR_BG_IMMERSIVE = "nav_bar_bg_immersive";
    public static final String KEY_STATUS_BAR_BG_AUTO = "status_bar_bg_auto";
    public static final String KEY_NAV_BAR_BG_AUTO = "nav_bar_bg_auto";
    public static final String KEY_LANDSCAPE_RATIO = "landscape_ratio";
    public static final String KEY_PORTRAIT_RATIO = "portrait_ratio";
    public static final String KEY_VOLUME_HOOK_ENABLED = "volume_step_enabled";

    // Per-app output gain: one master percentage the whole car obeys, multiplied by the
    // percentage configured for the playing package. 100 means "as the system plays it".
    public static final String KEY_APP_GAIN_ENABLED = "app_gain_enabled";
    public static final String KEY_APP_GAIN_MASTER = "app_gain_master";
    public static final String KEY_APP_GAIN_PREFIX = "app_gain.";
    public static final String KEY_APP_CHANNEL_ENABLED = "app_channel_enabled";
    public static final String KEY_APP_CHANNEL_PREFIX = "app_channel.";
    /** The channel an app follows when nobody named one for it. -1 leaves every app alone. */
    public static final String KEY_APP_CHANNEL_MASTER = "app_channel_master";
    // Which apps the settings screen carries rows for. The list is also what the hooks obey --
    // an app in the LSPosed scope but not on it is left exactly as the car plays it -- so each
    // package carries its own true/false membership property: the config file the list lives in
    // cannot be read from an app process.
    public static final String KEY_APP_AUDIO_LIST = "app_audio_list";
    public static final String KEY_APP_SELECTED_PREFIX = "app_selected.";

    // Per-stream volume keys are "<prefix><streamType>"; the stream types themselves are read
    // from the device at runtime, so only the prefixes live here.
    public static final String KEY_VOLUME_STREAM_ENABLED_PREFIX = "volume_step_on_";
    public static final String KEY_VOLUME_STREAM_STEPS_PREFIX = "volume_step_";

    public static final int DEFAULT_RATIO = 50;
    public static final int HEIGHT_UNSET = -1;
    public static final int STEPS_UNSET = -1;
    public static final int STEPS_MIN = 1;
    public static final int STEPS_MAX = 200;
    // 0 is a real setting, not a missing one: the app hears nothing.
    public static final int GAIN_MIN = 0;
    public static final int GAIN_MAX = 100;
    public static final int CHANNEL_UNSET = -1;
    /** The one per-app answer the master channel does not override: the app keeps its own choice. */
    public static final int CHANNEL_EXEMPT = -2;

    // System properties are the config channel usable from every process,
    // including system_server, which SELinux blocks from reading /data/local/tmp.
    // Names of the colour keys stay short: init on the head unit refused to set
    // persist.byd.dlink.statusbar_bg_color while accepting a 33-character one.
    private static final String[][] PROP_KEYS = {
            {KEY_GLOBAL_ENABLED, "persist.byd.dlink.enabled"},
            {KEY_SPLIT_SCREEN_ENABLED, "persist.byd.dlink.split"},
            {KEY_SPLIT_BLACKLIST_DISABLED, "persist.byd.dlink.blacklist_disabled"},
            {KEY_STATUS_BAR_ENABLED, "persist.byd.dlink.statusbar_enabled"},
            {KEY_STATUS_BAR_HEIGHT, "persist.byd.dlink.statusbar_height"},
            {KEY_NAV_BAR_ENABLED, "persist.byd.dlink.navbar_enabled"},
            {KEY_NAV_BAR_HEIGHT, "persist.byd.dlink.navbar_height"},
            {KEY_STATUS_BAR_BG_ENABLED, "persist.byd.dlink.sb_bg_on"},
            {KEY_STATUS_BAR_BG_COLOR, "persist.byd.dlink.sb_bg"},
            {KEY_NAV_BAR_BG_ENABLED, "persist.byd.dlink.nb_bg_on"},
            {KEY_NAV_BAR_BG_COLOR, "persist.byd.dlink.nb_bg"},
            {KEY_STATUS_BAR_BG_IMMERSIVE, "persist.byd.dlink.sb_imm"},
            {KEY_NAV_BAR_BG_IMMERSIVE, "persist.byd.dlink.nb_imm"},
            {KEY_STATUS_BAR_BG_AUTO, "persist.byd.dlink.sb_auto"},
            {KEY_NAV_BAR_BG_AUTO, "persist.byd.dlink.nb_auto"},
            {KEY_LANDSCAPE_RATIO, "persist.byd.dlink.landscape_ratio"},
            {KEY_PORTRAIT_RATIO, "persist.byd.dlink.portrait_ratio"},
            {KEY_VOLUME_HOOK_ENABLED, "persist.byd.dlink.volume_enabled"},
            {KEY_APP_GAIN_ENABLED, "persist.byd.dlink.appgain_on"},
            {KEY_APP_GAIN_MASTER, "persist.byd.dlink.master_gain"},
            {KEY_APP_CHANNEL_ENABLED, "persist.byd.dlink.appch_on"},
            {KEY_APP_CHANNEL_MASTER, "persist.byd.dlink.master_ch"},
    };

    private static volatile boolean sPropChannelLogged = false;
    private static volatile Method sPropGet = null;
    private static volatile boolean sPropReflectFailed = false;

    private static String readProp(String key) {
        try {
            Method get = sPropGet;
            if (get == null) {
                if (sPropReflectFailed) return null;
                try {
                    Class<?> clazz = Class.forName("android.os.SystemProperties");
                    get = clazz.getMethod("get", String.class);
                    sPropGet = get;
                } catch (Throwable t) {
                    sPropReflectFailed = true;
                    XposedBridge.log("BYD Dlink Customer: SystemProperties lookup failed: " + t);
                    return null;
                }
            }
            Object value = get.invoke(null, key);
            if (value instanceof String) {
                String s = ((String) value).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable t) {
            if (!sPropChannelLogged) {
                sPropChannelLogged = true;
                XposedBridge.log("BYD Dlink Customer: prop read error: " + t);
            }
        }
        return null;
    }

    private static String readConfigFileValue(String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq > 0 && key.equals(line.substring(0, eq).trim())) {
                    return line.substring(eq + 1).trim();
                }
            }
        } catch (Throwable ignored) {
            // Expected in system_server: SELinux denies shell_data_file reads.
        }
        return null;
    }

    /** The system property that carries this config key, or null when it has none. */
    public static String propKeyFor(String configKey) {
        for (String[] pair : PROP_KEYS) {
            if (pair[0].equals(configKey)) return pair[1];
        }
        if (configKey.startsWith(KEY_VOLUME_STREAM_ENABLED_PREFIX)) {
            return "persist.byd.dlink.vstep_on."
                    + configKey.substring(KEY_VOLUME_STREAM_ENABLED_PREFIX.length());
        }
        if (configKey.startsWith(KEY_VOLUME_STREAM_STEPS_PREFIX)) {
            return "persist.byd.dlink.vstep."
                    + configKey.substring(KEY_VOLUME_STREAM_STEPS_PREFIX.length());
        }
        if (configKey.startsWith(KEY_APP_GAIN_PREFIX)) {
            return "persist.byd.dlink.gain."
                    + configKey.substring(KEY_APP_GAIN_PREFIX.length());
        }
        if (configKey.startsWith(KEY_APP_CHANNEL_PREFIX)) {
            return "persist.byd.dlink.ch."
                    + configKey.substring(KEY_APP_CHANNEL_PREFIX.length());
        }
        if (configKey.startsWith(KEY_APP_SELECTED_PREFIX)) {
            return "persist.byd.dlink.on."
                    + configKey.substring(KEY_APP_SELECTED_PREFIX.length());
        }
        return null;
    }

    private static String getValue(String configKey) {
        String propKey = propKeyFor(configKey);
        if (propKey != null) {
            String value = readProp(propKey);
            if (value != null) return value;
            return null;
        }
        return readConfigFileValue(configKey);
    }

    private static boolean getBool(String key) {
        return "true".equalsIgnoreCase(getValue(key));
    }

    private static int getInt(String key) {
        return getInt(key, DEFAULT_RATIO);
    }

    private static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getValue(key));
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    public static boolean isGlobalEnabled() {
        return getBool(KEY_GLOBAL_ENABLED);
    }

    public static boolean isSplitScreenEnabled() {
        return isGlobalEnabled() && getBool(KEY_SPLIT_SCREEN_ENABLED);
    }

    public static boolean isSplitBlacklistDisabled() {
        return isGlobalEnabled() && getBool(KEY_SPLIT_BLACKLIST_DISABLED);
    }

    public static int getStatusBarHeightOverride() {
        return isGlobalEnabled() && getBool(KEY_STATUS_BAR_ENABLED)
                ? getInt(KEY_STATUS_BAR_HEIGHT, HEIGHT_UNSET) : HEIGHT_UNSET;
    }

    public static int getNavigationBarHeightOverride() {
        return isGlobalEnabled() && getBool(KEY_NAV_BAR_ENABLED)
                ? getInt(KEY_NAV_BAR_HEIGHT, HEIGHT_UNSET) : HEIGHT_UNSET;
    }

    /**
     * The background colour painted on a bar, or null when the bar should stay exactly as
     * SystemUI draws it. Alpha is part of the value: a colour with alpha 0 is a real choice
     * (a see-through band), so it must not be confused with "unset".
     */
    public static Integer getStatusBarBgColor() {
        return isGlobalEnabled() && getBool(KEY_STATUS_BAR_BG_ENABLED)
                ? parseColor(getValue(KEY_STATUS_BAR_BG_COLOR)) : null;
    }

    public static Integer getNavigationBarBgColor() {
        return isGlobalEnabled() && getBool(KEY_NAV_BAR_BG_ENABLED)
                ? parseColor(getValue(KEY_NAV_BAR_BG_COLOR)) : null;
    }

    /** Whether a bar takes its background from us at all -- which of the three modes is below. */
    public static boolean isStatusBarBgEnabled() {
        return isGlobalEnabled() && getBool(KEY_STATUS_BAR_BG_ENABLED);
    }

    public static boolean isNavBarBgEnabled() {
        return isGlobalEnabled() && getBool(KEY_NAV_BAR_BG_ENABLED);
    }

    /** Paint the configured colour even where the app draws behind the bar. Off by default. */
    public static boolean isStatusBarBgImmersiveEnabled() {
        return isStatusBarBgEnabled() && getBool(KEY_STATUS_BAR_BG_IMMERSIVE);
    }

    public static boolean isNavBarBgImmersiveEnabled() {
        return isNavBarBgEnabled() && getBool(KEY_NAV_BAR_BG_IMMERSIVE);
    }

    /**
     * Paint the colour the screen edge next to the bar actually has, instead of a configured one.
     * Offered as the alternative to the immersive override and never both, so an app that really
     * does draw behind a bar keeps its own pixels there.
     */
    public static boolean isStatusBarBgAuto() {
        return isStatusBarBgEnabled() && getBool(KEY_STATUS_BAR_BG_AUTO);
    }

    public static boolean isNavBarBgAuto() {
        return isNavBarBgEnabled() && getBool(KEY_NAV_BAR_BG_AUTO);
    }

    /** Accepts "RRGGBB", "AARRGGBB" and either with a leading '#'; null when it is not a colour. */
    public static Integer parseColor(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.length() != 6 && text.length() != 8) return null;
        try {
            long value = Long.parseLong(text, 16);
            // A six-digit code carries no alpha, and an eight-digit one whose alpha is absent
            // would otherwise read as fully transparent black.
            return text.length() == 6 ? (int) (0xFF000000L | value) : (int) value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The way the settings screen shows a colour back to the user. */
    public static String formatColor(int argb) {
        return String.format("#%08X", argb);
    }

    public static boolean isVolumeHookEnabled() {
        return isGlobalEnabled() && getBool(KEY_VOLUME_HOOK_ENABLED);
    }

    /** The step count configured for one audio stream, or STEPS_UNSET when it should be left alone. */
    public static int getVolumeSteps(int streamType) {
        if (!isVolumeHookEnabled()) return STEPS_UNSET;
        if (!getBool(KEY_VOLUME_STREAM_ENABLED_PREFIX + streamType)) return STEPS_UNSET;
        return getInt(KEY_VOLUME_STREAM_STEPS_PREFIX + streamType, STEPS_UNSET);
    }

    public static int getLandscapeRatio() {
        return getInt(KEY_LANDSCAPE_RATIO);
    }

    public static int getPortraitRatio() {
        return getInt(KEY_PORTRAIT_RATIO);
    }

    private static int clampGain(int value) {
        return Math.max(GAIN_MIN, Math.min(GAIN_MAX, value));
    }

    /**
     * Gain is read straight from the properties, never from the config file: this runs inside
     * every scoped app on each volume call, where a file open would both fail on SELinux grounds
     * and cost a syscall.
     */
    private static int getGainPercent(String configKey) {
        String raw = readProp(propKeyFor(configKey));
        if (raw == null) return GAIN_MAX;
        try {
            return clampGain(Integer.parseInt(raw));
        } catch (Throwable t) {
            return GAIN_MAX;
        }
    }

    public static boolean isAppGainEnabled() {
        return isGlobalEnabled()
                && "true".equalsIgnoreCase(readProp(propKeyFor(KEY_APP_GAIN_ENABLED)));
    }

    public static int getAppGainMaster() {
        return getGainPercent(KEY_APP_GAIN_MASTER);
    }

    /** Whether any app is re-channeled at all; the hook installs but stays inert when off. */
    public static boolean isAppChannelEnabled() {
        return "true".equalsIgnoreCase(readProp(propKeyFor(KEY_APP_CHANNEL_ENABLED)));
    }

    public static int getAppGainPercent(String packageName) {
        return getGainPercent(KEY_APP_GAIN_PREFIX + packageName);
    }

    /**
     * Whether the driver put this app on the audio list. Being in the LSPosed scope only makes an
     * app hookable; the list is what says whether this module may touch its sound at all.
     */
    public static boolean isAppSelected(String packageName) {
        return packageName != null && "true".equalsIgnoreCase(
                readProp(propKeyFor(KEY_APP_SELECTED_PREFIX + packageName)));
    }

    /** The playing app hears this multiple of what the system would have played: master x app. */
    public static float gainFactorFor(String packageName) {
        if (packageName == null || !isAppGainEnabled() || !isAppSelected(packageName)) {
            return 1.0f;
        }
        int app = getAppGainPercent(packageName);
        if (app >= GAIN_MAX) return 1.0f;
        return getAppGainMaster() * app / 10000f;
    }

    /**
     * BYD's content type an app's media playback is forced onto. An app the driver never added to
     * the audio list is not moved at all; among the ones that are, an app nobody named obeys the
     * master channel, so one setting can put the whole car's media on navigation output;
     * CHANNEL_EXEMPT is the one per-app answer the master may not override -- it means the app
     * keeps the channel it chose for itself.
     */
    public static int getChannelFor(String packageName) {
        if (!isAppSelected(packageName) || !isGlobalEnabled() || !isAppChannelEnabled()) {
            return CHANNEL_UNSET;
        }
        int own = readChannel(propKeyFor(KEY_APP_CHANNEL_PREFIX + packageName));
        if (own == CHANNEL_EXEMPT) return CHANNEL_UNSET;
        return own >= 0 ? own : getChannelMaster();
    }

    /** The channel an app with no setting of its own is placed on. */
    public static int getChannelMaster() {
        if (!isGlobalEnabled() || !isAppChannelEnabled()) {
            return CHANNEL_UNSET;
        }
        int master = readChannel(propKeyFor(KEY_APP_CHANNEL_MASTER));
        return master == CHANNEL_EXEMPT ? CHANNEL_UNSET : master;
    }

    /** The stored number exactly as it was named, CHANNEL_UNSET when nothing was ever written. */
    private static int readChannel(String propKey) {
        String raw = readProp(propKey);
        if (raw == null) return CHANNEL_UNSET;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return CHANNEL_UNSET;
        }
    }
}
