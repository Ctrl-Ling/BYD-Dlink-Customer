package com.byd.dlink.customer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.byd.dlink.customer.ui.ColorPickerDialog;
import com.byd.dlink.customer.utils.BydChannels;
import com.byd.dlink.customer.utils.PrefsManager;
import com.byd.dlink.customer.utils.SystemBarDimens;
import com.byd.dlink.customer.utils.VolumeStreams;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private static final int RATIO_MIN = 10;
    private static final int RATIO_MAX = 90;
    private static final int HEIGHT_MAX_PERCENT = 20;
    private static final int HEIGHT_UNSET = -1;
    private static final int STEPS_UNSET = -1;
    private static final int CUSTOM_CHANNEL_MAX = 99;
    private static final int DEFAULT_BAR_COLOR = 0xFF000000;
    private static final String CONFIG_FILE = "/data/local/tmp/byd_dlink_config.properties";
    private static final String LOCAL_CONFIG = "config.properties";

    private static final int PAGE_HOME = 0;
    private static final int PAGE_SPLIT = 1;
    private static final int PAGE_BARS = 2;
    private static final int PAGE_VOLUME = 3;
    private static final int PAGE_APP_AUDIO = 4;
    private static final int PAGE_OTA = 5;
    private static final int PAGE_COUNT = 6;

    private Properties config;

    // What the last save actually put on the device, so a second save asked for the same thing writes
    // nothing at all: a root shell, a file copy and a SystemUI re-decision per no-op was what one tap
    // of a mutually exclusive switch cost twice over. Only ever set from a write that reported success,
    // so a lost write leaves the next save free to retry the same values.
    private volatile Properties saved;

    // One thread, so the writes land in the order they were asked for -- a later save whose temp file
    // has already been overtaken would otherwise move the newer file into place and then fail its own
    // mv, taking its setprops down the && chain with it. Static because a page closing must not take
    // the writer with it: the last change made before leaving would then be refused and lost.
    private static final ExecutorService configWriter = Executors.newSingleThreadExecutor();

    private ScrollView settingsScroll;
    private MaterialToolbar toolbar;
    private final View[] pages = new View[PAGE_COUNT];
    private final TextView[] entrySummaries = new TextView[PAGE_COUNT];
    private int page = PAGE_HOME;

    private TextView systemVariantValue;
    private SwitchCompat globalSwitch;
    private SwitchCompat splitScreenSwitch;
    private SwitchCompat splitBlacklistSwitch;
    private BarHeightControl statusBarControl;
    private BarHeightControl navBarControl;
    private BarBackgroundControl statusBarBgControl;
    private BarBackgroundControl navBarBgControl;
    private SwitchCompat volumeSwitch;
    private LinearLayout volumeStreamList;
    private final List<VolumeStepControl> volumeControls = new ArrayList<>();
    private SwitchCompat appGainSwitch;
    private View appGainMasterRow;
    private TextView appGainMasterText;
    private View appGainMasterHint;
    private SwitchCompat appChannelSwitch;
    private View appChannelMasterRow;
    private TextView appChannelMasterText;
    private View appChannelMasterHint;
    private View appGainDivider;
    private SwitchCompat otaRebootSwitch;
    private SwitchCompat otaDisableSwitch;
    private TextView otaCurrentSlotText;
    private SwitchCompat appStartSwitch;
    private SwitchCompat appStartDisableSwitch;
    private SwitchCompat appStartUpdateSwitch;
    private SwitchCompat appStartInstallSwitch;
    private View appStartSubPanel;
    private SwitchCompat screenCornerSwitch;
    private View screenCornerRow;
    private TextView screenCornerValue;
    private LinearLayout appGainList;
    private View addAppButton;
    private final List<AppGainControl> appGainControls = new ArrayList<>();
    private SeekBar landscapeSeekBar;
    private SeekBar portraitSeekBar;
    private TextView landscapeValue;
    private TextView portraitValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = loadConfig();
        saved = copyOf(config);

        initViews();
        addFeatureEntries();
        setupSystemVariant();
        setupGlobalSwitch();
        setupSplitScreenSwitch();
        setupSplitBlacklistSwitch();
        setupBarHeight(statusBarControl);
        setupBarHeight(navBarControl);
        setupBarBackground(statusBarBgControl);
        setupBarBackground(navBarBgControl);
        setupScreenCorner();
        setupVolume();
        setupAppGain();
        setupAppChannel();
        setupOta();
        setupAppStart();
        setupSeekBars();
        setupShellCommand();
        showPage(PAGE_HOME);
        // Rows are built by the setups above, so their summaries can only be filled in after them.
        updateEntrySummaries();
        updateModuleStatus();
        updateVersionInfo();
        // Nothing that depends on which panel this is can be decided before he has said so.
        if (systemVariantChoice() == PrefsManager.SYSTEM_VARIANT_UNSET) {
            showSystemVariantPicker(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("BYD Dlink Customer", "onResume triggered");
        updateModuleStatus();
        Log.d("BYD Dlink Customer", "isModuleActivated result: " + isModuleActivated());
    }

    private void initViews() {
        settingsScroll = findViewById(R.id.settingsScroll);
        toolbar = findViewById(R.id.toolbar);
        systemVariantValue = findViewById(R.id.systemVariantValue);
        pages[PAGE_HOME] = findViewById(R.id.pageHome);
        pages[PAGE_SPLIT] = findViewById(R.id.pageSplit);
        pages[PAGE_BARS] = findViewById(R.id.pageBars);
        pages[PAGE_VOLUME] = findViewById(R.id.pageVolume);
        pages[PAGE_APP_AUDIO] = findViewById(R.id.pageAppAudio);
        pages[PAGE_OTA] = findViewById(R.id.pageOta);
        globalSwitch = findViewById(R.id.globalSwitch);
        splitScreenSwitch = findViewById(R.id.splitScreenSwitch);
        splitBlacklistSwitch = findViewById(R.id.splitBlacklistSwitch);
        statusBarControl = new BarHeightControl(
                findViewById(R.id.statusBarSwitch),
                findViewById(R.id.statusBarValueRow),
                findViewById(R.id.statusBarValueText),
                R.string.status_bar_switch_title,
                R.string.status_bar_value_title,
                "status_bar_enabled", "status_bar_height", SystemBarDimens.STATUS_BAR);
        navBarControl = new BarHeightControl(
                findViewById(R.id.navBarSwitch),
                findViewById(R.id.navBarValueRow),
                findViewById(R.id.navBarValueText),
                R.string.nav_bar_switch_title,
                R.string.nav_bar_value_title,
                "nav_bar_enabled", "nav_bar_height", SystemBarDimens.NAV_BAR);
        statusBarBgControl = new BarBackgroundControl(
                findViewById(R.id.statusBarBgSwitch),
                findViewById(R.id.statusBarBgValueRow),
                findViewById(R.id.statusBarBgSwatch),
                findViewById(R.id.statusBarBgValueText),
                findViewById(R.id.statusBarBgImmersiveRow),
                findViewById(R.id.statusBarBgImmersiveSwitch),
                findViewById(R.id.statusBarBgAutoRow),
                findViewById(R.id.statusBarBgAutoSwitch),
                findViewById(R.id.statusBarBgAutoSummary),
                R.string.status_bar_bg_value_title,
                "status_bar_bg_enabled", "status_bar_bg_color",
                "status_bar_bg_immersive", "status_bar_bg_auto");
        navBarBgControl = new BarBackgroundControl(
                findViewById(R.id.navBarBgSwitch),
                findViewById(R.id.navBarBgValueRow),
                findViewById(R.id.navBarBgSwatch),
                findViewById(R.id.navBarBgValueText),
                findViewById(R.id.navBarBgImmersiveRow),
                findViewById(R.id.navBarBgImmersiveSwitch),
                findViewById(R.id.navBarBgAutoRow),
                findViewById(R.id.navBarBgAutoSwitch),
                findViewById(R.id.navBarBgAutoSummary),
                R.string.nav_bar_bg_value_title,
                "nav_bar_bg_enabled", "nav_bar_bg_color",
                "nav_bar_bg_immersive", "nav_bar_bg_auto");
        landscapeSeekBar = findViewById(R.id.landscapeSeekBar);
        portraitSeekBar = findViewById(R.id.portraitSeekBar);
        landscapeValue = findViewById(R.id.landscapeValue);
        portraitValue = findViewById(R.id.portraitValue);
        volumeSwitch = findViewById(R.id.volumeSwitch);
        volumeStreamList = findViewById(R.id.volumeStreamList);
        appGainSwitch = findViewById(R.id.appGainSwitch);
        appGainMasterRow = findViewById(R.id.appGainMasterRow);
        appGainMasterText = findViewById(R.id.appGainMasterText);
        appGainMasterHint = findViewById(R.id.appGainMasterHint);
        appGainDivider = findViewById(R.id.appGainDivider);
        appGainList = findViewById(R.id.appGainList);
        addAppButton = findViewById(R.id.addAppBtn);
        appChannelSwitch = findViewById(R.id.appChannelSwitch);
        appChannelMasterRow = findViewById(R.id.appChannelMasterRow);
        appChannelMasterText = findViewById(R.id.appChannelMasterText);
        appChannelMasterHint = findViewById(R.id.appChannelMasterHint);
        otaRebootSwitch = findViewById(R.id.otaRebootSwitch);
        otaDisableSwitch = findViewById(R.id.otaDisableSwitch);
        otaCurrentSlotText = findViewById(R.id.otaCurrentSlotText);
        appStartSwitch = findViewById(R.id.appStartSwitch);
        appStartDisableSwitch = findViewById(R.id.appStartDisableSwitch);
        appStartUpdateSwitch = findViewById(R.id.appStartUpdateSwitch);
        appStartInstallSwitch = findViewById(R.id.appStartInstallSwitch);
        appStartSubPanel = findViewById(R.id.appStartSubPanel);
        screenCornerSwitch = findViewById(R.id.screenCornerSwitch);
        screenCornerRow = findViewById(R.id.screenCornerRow);
        screenCornerValue = findViewById(R.id.screenCornerValueText);
    }

    /**
     * One row per hook on the home screen. A per-app hook has to list every app, which on a head
     * unit with a full launcher is far too long to share a screen with the rest, so each hook gets a
     * page of its own and the home screen only carries the state each one is in.
     */
    private void addFeatureEntries() {
        LinearLayout list = findViewById(R.id.featureList);
        addFeatureEntry(list, PAGE_SPLIT, R.string.split_screen_title,
                v -> showPage(PAGE_SPLIT));
        addFeatureEntry(list, PAGE_BARS, R.string.system_bar_title,
                v -> showPage(PAGE_BARS));
        addFeatureEntry(list, PAGE_VOLUME, R.string.volume_title,
                v -> showPage(PAGE_VOLUME));
        addFeatureEntry(list, PAGE_APP_AUDIO, R.string.app_gain_title,
                v -> showPage(PAGE_APP_AUDIO));
        addFeatureEntry(list, PAGE_OTA, R.string.ota_title,
                v -> showPage(PAGE_OTA));
    }

    private void addFeatureEntry(LinearLayout parent, final int index, int titleRes,
                                 View.OnClickListener open) {
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(15f);
        title.setTextColor(getColor(R.color.colorTextPrimary));

        TextView summary = new TextView(this);
        summary.setTextSize(12f);
        summary.setTextColor(getColor(R.color.colorTextSecondary));
        summary.setText(R.string.entry_state_off);
        entrySummaries[index] = summary;

        TextView chevron = new TextView(this);
        chevron.setText(R.string.entry_chevron);
        chevron.setTextSize(20f);
        chevron.setTextColor(getColor(R.color.colorDivider));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(title);
        texts.addView(summary);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setBackgroundResource(rowBackground());
        row.setOnClickListener(open);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chevron);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        parent.addView(row, params);
    }

    /**
     * Which head unit the hooks are running against. Everything written so far assumes Dlink
     * 3.0/4.0 plus the 4.5 UI Magisk module, and a 5.0+ panel answers to different classes, so the
     * hooks that differ have to be told. Unanswered is offered as nothing selected rather than as
     * the default, because on the first run there is no answer to fall back to.
     */
    private void setupSystemVariant() {
        findViewById(R.id.systemVariantRow).setOnClickListener(v -> showSystemVariantPicker(false));
        updateSystemVariantRow();
    }

    private int systemVariantChoice() {
        return getIntProperty(PrefsManager.KEY_SYSTEM_VARIANT, PrefsManager.SYSTEM_VARIANT_UNSET);
    }

    private void updateSystemVariantRow() {
        systemVariantValue.setText(labelForSystemVariant(systemVariantChoice()));
    }

    private String labelForSystemVariant(int variant) {
        switch (variant) {
            case PrefsManager.SYSTEM_DLINK_34:
                return getString(R.string.system_variant_dlink34);
            case PrefsManager.SYSTEM_DLINK_45UI:
                return getString(R.string.system_variant_dlink45ui);
            case PrefsManager.SYSTEM_DLINK_5:
                return getString(R.string.system_variant_dlink5);
            default:
                return getString(R.string.system_variant_unselected);
        }
    }

    /** Forced is the first run, where leaving the question unanswered is not an option. */
    private void showSystemVariantPicker(boolean forced) {
        final String[] labels = {
                getString(R.string.system_variant_dlink34),
                getString(R.string.system_variant_dlink45ui),
                getString(R.string.system_variant_dlink5),
        };
        int current = systemVariantChoice();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.system_variant_picker_title)
                .setMessage(R.string.system_variant_desc)
                .setSingleChoiceItems(labels, current == PrefsManager.SYSTEM_VARIANT_UNSET
                        ? -1 : current - 1, (dialog, which) -> {
                    int variant = which + 1;
                    dialog.dismiss();
                    if (variant == systemVariantChoice()) return;
                    config.setProperty(PrefsManager.KEY_SYSTEM_VARIANT, String.valueOf(variant));
                    saveConfig();
                    updateSystemVariantRow();
                });
        if (!forced) {
            builder.setNegativeButton(android.R.string.cancel, null);
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(!forced);
        dialog.show();
    }

    private void showPage(int target) {
        page = target;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == target ? View.VISIBLE : View.GONE);
        }
        toolbar.setTitle(titleForPage(target));
        toolbar.setNavigationIcon(target == PAGE_HOME
                ? (Drawable) null : getDrawable(R.drawable.ic_arrow_back));
        toolbar.setNavigationOnClickListener(v -> showPage(PAGE_HOME));
        settingsScroll.scrollTo(0, 0);
    }

    private int titleForPage(int target) {
        switch (target) {
            case PAGE_SPLIT:
                return R.string.split_screen_title;
            case PAGE_BARS:
                return R.string.system_bar_title;
            case PAGE_VOLUME:
                return R.string.volume_title;
            case PAGE_APP_AUDIO:
                return R.string.app_gain_title;
            case PAGE_OTA:
                return R.string.ota_title;
            default:
                return R.string.app_name;
        }
    }

    @Override
    public void onBackPressed() {
        if (page == PAGE_HOME) {
            super.onBackPressed();
            return;
        }
        showPage(PAGE_HOME);
    }

    /** The home rows say what each hook is set to, so a page visit is only needed to change one. */
    private void updateEntrySummaries() {
        boolean global = globalSwitch == null || globalSwitch.isChecked();
        entrySummaries[PAGE_SPLIT].setText(global && splitScreenSwitch.isChecked()
                ? getString(R.string.entry_split_format,
                        getIntProperty(PrefsManager.KEY_LANDSCAPE_RATIO, PrefsManager.DEFAULT_RATIO),
                        getIntProperty(PrefsManager.KEY_PORTRAIT_RATIO, PrefsManager.DEFAULT_RATIO))
                : getString(R.string.entry_state_off));
        entrySummaries[PAGE_BARS].setText(global && (statusBarControl.toggle.isChecked()
                || navBarControl.toggle.isChecked()
                || statusBarBgControl.toggle.isChecked()
                || navBarBgControl.toggle.isChecked())
                ? barSummary() : getString(R.string.entry_state_off));
        int streamsOn = 0;
        for (VolumeStepControl control : volumeControls) {
            if (control.toggle.isChecked()) {
                streamsOn++;
            }
        }
        entrySummaries[PAGE_VOLUME].setText(global && volumeSwitch.isChecked()
                ? getString(R.string.entry_volume_format, streamsOn)
                : getString(R.string.entry_state_off));
        updateOtaEntrySummary();
        if (!global || !appGainSwitch.isChecked()) {
            entrySummaries[PAGE_APP_AUDIO].setText(R.string.entry_state_off);
            return;
        }
        int configured = 0;
        for (AppGainControl control : appGainControls) {
            if (isConfigured(control.packageName)) {
                configured++;
            }
        }
        String summary = getString(R.string.entry_app_gain_format,
                clampGain(getIntProperty(PrefsManager.KEY_APP_GAIN_MASTER, PrefsManager.GAIN_MAX)),
                appGainControls.size(), configured);
        entrySummaries[PAGE_APP_AUDIO].setText(appChannelSwitch.isChecked()
                ? getString(R.string.entry_app_channel_format, summary) : summary);
    }

    /** The home row for the update page names every switch on it that is currently on. */
    private void updateOtaEntrySummary() {
        StringBuilder active = new StringBuilder();
        appendOtaEntry(active, otaRebootSwitch != null && otaRebootSwitch.isChecked(),
                R.string.ota_reboot_intercept_title);
        appendOtaEntry(active, otaDisableSwitch != null && otaDisableSwitch.isChecked(),
                R.string.ota_update_disable_title);
        appendOtaEntry(active, appStartSwitch != null && appStartSwitch.isChecked(),
                R.string.app_start_title);
        entrySummaries[PAGE_OTA].setText(active.length() > 0
                ? active.toString() : getString(R.string.entry_state_off));
    }

    private void appendOtaEntry(StringBuilder active, boolean on, int titleRes) {
        if (!on) return;
        if (active.length() > 0) {
            active.append(", ");
        }
        active.append(getString(titleRes));
    }

    private String barSummary() {
        StringBuilder summary = new StringBuilder();
        appendBar(summary, statusBarControl);
        appendBar(summary, navBarControl);
        appendBarColor(summary, statusBarBgControl);
        appendBarColor(summary, navBarBgControl);
        appendCorner(summary);
        return summary.toString();
    }

    private void appendCorner(StringBuilder summary) {
        if (!screenCornerSwitch.isChecked()) {
            return;
        }
        if (summary.length() > 0) {
            summary.append(getString(R.string.entry_bars_separator));
        }
        summary.append(getString(R.string.entry_screen_corner_format,
                Math.min(getIntProperty(PrefsManager.KEY_SCREEN_CORNER_RADIUS,
                        PrefsManager.CORNER_DEFAULT), maxCornerRadiusPx())));
    }

    private void appendBarColor(StringBuilder summary, BarBackgroundControl control) {
        if (!control.toggle.isChecked()) {
            return;
        }
        if (summary.length() > 0) {
            summary.append(getString(R.string.entry_bars_separator));
        }
        if (control.autoToggle.isChecked()) {
            summary.append(getString(R.string.entry_bar_bg_auto_format,
                    getString(control.labelRes)));
            return;
        }
        summary.append(getString(R.string.entry_bar_bg_item_format, getString(control.labelRes),
                PrefsManager.formatColor(getColorProperty(control.colorKey, DEFAULT_BAR_COLOR))));
        if (control.immersiveToggle.isChecked()) {
            summary.append(getString(R.string.bar_immersive_tag));
        }
    }

    private void appendBar(StringBuilder summary, BarHeightControl control) {
        if (!control.toggle.isChecked()) {
            return;
        }
        if (summary.length() > 0) {
            summary.append(getString(R.string.entry_bars_separator));
        }
        int saved = getIntProperty(control.heightKey, HEIGHT_UNSET);
        summary.append(getString(R.string.entry_bars_item_format, getString(control.labelRes),
                saved >= 0 ? saved : SystemBarDimens.readHeightPx(control.dimenNames)));
    }

    private void setupGlobalSwitch() {
        boolean enabled = "true".equals(config.getProperty("global_enabled", "false"));
        globalSwitch.setChecked(enabled);

        globalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty("global_enabled", String.valueOf(isChecked));
            saveConfig();
            updateModuleDependentViews();
        });

        updateModuleDependentViews();
    }

    private void setupSplitScreenSwitch() {
        boolean enabled = "true".equals(config.getProperty("split_screen_enabled", "false"));
        splitScreenSwitch.setChecked(enabled);

        splitScreenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty("split_screen_enabled", String.valueOf(isChecked));
            saveConfig();
            updateSplitScreenDependentViews();
        });

        updateSplitScreenDependentViews();
    }

    private void setupSplitBlacklistSwitch() {
        boolean enabled = "true".equals(config.getProperty("split_blacklist_disabled", "false"));
        splitBlacklistSwitch.setChecked(enabled);

        splitBlacklistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty("split_blacklist_disabled", String.valueOf(isChecked));
            saveConfig();
        });
    }

    private void setupBarHeight(BarHeightControl control) {
        control.toggle.setChecked("true".equals(config.getProperty(control.enabledKey, "false")));
        control.toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(control.enabledKey, String.valueOf(isChecked));
            saveConfig();
            updateBarHeight(control);
            if (isChecked) {
                showHeightPicker(control);
            }
        });
        control.valueRow.setOnClickListener(v -> showHeightPicker(control));
        updateBarHeight(control);
    }

    private void updateBarHeight(BarHeightControl control) {
        boolean on = control.toggle.isChecked();
        control.valueRow.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            return;
        }
        int current = SystemBarDimens.readHeightPx(control.dimenNames);
        int saved = getIntProperty(control.heightKey, HEIGHT_UNSET);
        control.valueText.setText(saved >= 0
                ? getString(R.string.system_bar_value_format, current, saved)
                : getString(R.string.system_bar_height_format, current));
    }

    private void showHeightPicker(final BarHeightControl control) {
        final int maxHeight = maxBarHeightPx();
        int saved = getIntProperty(control.heightKey, HEIGHT_UNSET);
        int initial = saved >= 0 ? saved : SystemBarDimens.readHeightPx(control.dimenNames);

        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(maxHeight);
        picker.setValue(Math.max(0, Math.min(initial, maxHeight)));

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText(getString(R.string.system_bar_picker_hint, maxHeight));

        int pad = (int) (getResources().getDisplayMetrics().density * 24f);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(picker);
        content.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(control.titleRes)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // A typed-in value only reaches getValue() once the picker's editor gives
                    // up focus, which the dialog's button does not do on its own.
                    picker.clearFocus();
                    int picked = Math.max(0, Math.min(picker.getValue(), maxHeight));
                    config.setProperty(control.heightKey, String.valueOf(picked));
                    saveConfig();
                    updateBarHeight(control);
                    if (clampCornerRadiusToBars()) {
                        updateScreenCornerRow();
                        updateEntrySummaries();
                        Toast.makeText(this, getString(R.string.screen_corner_clamped_format,
                                maxCornerRadiusPx()), Toast.LENGTH_LONG).show();
                    }
                    showRebootNotice(picked);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRebootNotice(int heightPx) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.system_bar_reboot_title)
                .setMessage(getString(R.string.system_bar_reboot_message, heightPx))
                .setPositiveButton(R.string.system_bar_reboot_confirm, null)
                .show();
    }

    /** The head unit is fixed landscape, so the short edge is the axis both bars eat into. */
    private int maxBarHeightPx() {
        Point size = new Point();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealSize(size);
        return Math.max(0, size.y * HEIGHT_MAX_PERCENT / 100);
    }

    /**
     * A bar colour is a SystemUI-side paint, so the switch has to be on before there is anything
     * to paint with: enabling it writes the default colour and opens the picker straight away.
     */
    private void setupBarBackground(BarBackgroundControl control) {
        control.toggle.setChecked(
                "true".equals(config.getProperty(control.enabledKey, "false")));
        control.toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(control.enabledKey, String.valueOf(isChecked));
            if (isChecked && config.getProperty(control.colorKey) == null) {
                setColorProperty(control, DEFAULT_BAR_COLOR);
            }
            saveConfig();
            updateBarBackground(control);
            if (isChecked && !control.autoToggle.isChecked()) {
                showColorPicker(control);
            }
        });
        control.valueRow.setOnClickListener(v -> showColorPicker(control));
        setupBarBgModes(control);
        updateBarBackground(control);
    }

    /**
     * A bar paints its configured colour, the edge of the screen, or nothing -- so the two modes that
     * choose between them can never both be on, and switching one on is what switches the other off.
     * One listener serves both switches and detaches the other's while forcing it off: two listeners
     * re-entering each other saved the config twice for a single tap, which is two root shells and two
     * trips through SystemUI's bar decision for one change.
     */
    private void setupBarBgModes(final BarBackgroundControl control) {
        final SwitchCompat immersive = control.immersiveToggle;
        final SwitchCompat auto = control.autoToggle;
        immersive.setChecked("true".equals(config.getProperty(control.immersiveKey, "false")));
        auto.setChecked("true".equals(config.getProperty(control.autoKey, "false")));
        CompoundButton.OnCheckedChangeListener modeListener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        boolean fromAuto = buttonView == auto;
                        SwitchCompat other = fromAuto ? immersive : auto;
                        String key = fromAuto ? control.autoKey : control.immersiveKey;
                        String otherKey = fromAuto ? control.immersiveKey : control.autoKey;
                        config.setProperty(key, String.valueOf(isChecked));
                        if (isChecked && other.isChecked()) {
                            other.setOnCheckedChangeListener(null);
                            other.setChecked(false);
                            other.setOnCheckedChangeListener(this);
                            config.setProperty(otherKey, "false");
                        }
                        saveConfig();
                        updateBarBackground(control);
                    }
                };
        immersive.setOnCheckedChangeListener(modeListener);
        auto.setOnCheckedChangeListener(modeListener);
    }

    private void updateBarBackground(BarBackgroundControl control) {
        boolean on = control.toggle.isChecked();
        control.immersiveRow.setVisibility(on ? View.VISIBLE : View.GONE);
        control.autoRow.setVisibility(on ? View.VISIBLE : View.GONE);
        control.autoSummary.setVisibility(on && control.autoToggle.isChecked()
                ? View.VISIBLE : View.GONE);
        control.valueRow.setVisibility(on && !control.autoToggle.isChecked()
                ? View.VISIBLE : View.GONE);
        if (control.valueRow.getVisibility() != View.VISIBLE) {
            return;
        }
        int argb = getColorProperty(control.colorKey, DEFAULT_BAR_COLOR);
        GradientDrawable swatch = new GradientDrawable();
        swatch.setCornerRadius(dp(4));
        swatch.setStroke(dp(1), getColor(R.color.colorDivider));
        swatch.setColor(argb);
        control.swatch.setBackground(swatch);
        control.valueText.setText(getString(R.string.system_bar_bg_value_format,
                PrefsManager.formatColor(argb)));
    }

    private void showColorPicker(final BarBackgroundControl control) {
        ColorPickerDialog.show(this,
                getString(R.string.color_picker_title, getString(control.labelRes)),
                getColorProperty(control.colorKey, DEFAULT_BAR_COLOR),
                argb -> {
                    setColorProperty(control, argb);
                    saveConfig();
                    updateBarBackground(control);
                    showRestartNotice(control, argb);
                });
    }

    /** The colour only reaches the bars when SystemUI reads it, which happens when SystemUI starts. */
    private void showRestartNotice(BarBackgroundControl control, int argb) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bar_restart_title)
                .setMessage(getString(R.string.bar_restart_message,
                        getString(control.labelRes), PrefsManager.formatColor(argb)))
                .setPositiveButton(R.string.bar_restart_yes, (dialog, which) -> restartSystemUi())
                .setNegativeButton(R.string.bar_restart_no, null)
                .show();
    }

    /**
     * force-stop does not bring BYD's SystemUI back, so the process is killed and zygote respawns
     * it. Restarting SystemUI cannot lose the user's work, which is why this one needs no
     * confirmation beyond the dialog above.
     */
    private void restartSystemUi() {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "kill $(pidof com.android.systemui)"});
        } catch (Throwable t) {
            Log.e("BYD Dlink Customer", "restart SystemUI failed", t);
            Toast.makeText(this, R.string.bar_restart_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Screen corners: the arc is drawn inside the two bar bands, so its ceiling is the shorter of
     * them, and that number moves the moment either height is changed.
     */
    private void setupScreenCorner() {
        clampCornerRadiusToBars();
        screenCornerSwitch.setChecked("true".equals(
                config.getProperty(PrefsManager.KEY_SCREEN_CORNER_ENABLED, "false")));
        screenCornerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(PrefsManager.KEY_SCREEN_CORNER_ENABLED, String.valueOf(isChecked));
            saveConfig();
            updateScreenCornerRow();
            updateEntrySummaries();
            if (isChecked) {
                // The mask is put on by a layout pass the bars only have once SystemUI has installed
                // the hook, so switching the feature on for the first time cannot be live.
                new AlertDialog.Builder(this)
                        .setTitle(R.string.bar_restart_title)
                        .setMessage(R.string.screen_corner_restart_message)
                        .setPositiveButton(R.string.bar_restart_yes,
                                (dialog, which) -> restartSystemUi())
                        .setNegativeButton(R.string.bar_restart_no, null)
                        .show();
            }
        });
        screenCornerRow.setOnClickListener(v -> showCornerPicker());
        updateScreenCornerRow();
    }

    private void updateScreenCornerRow() {
        boolean on = screenCornerSwitch.isChecked();
        screenCornerRow.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            return;
        }
        int max = maxCornerRadiusPx();
        screenCornerValue.setText(getString(R.string.screen_corner_value_format,
                Math.min(getIntProperty(PrefsManager.KEY_SCREEN_CORNER_RADIUS,
                        PrefsManager.CORNER_DEFAULT), max), max));
    }

    private void showCornerPicker() {
        final int max = maxCornerRadiusPx();
        final int min = Math.min(PrefsManager.CORNER_MIN, max);
        int saved = getIntProperty(PrefsManager.KEY_SCREEN_CORNER_RADIUS,
                PrefsManager.CORNER_DEFAULT);

        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(saved, max)));

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText(getString(R.string.screen_corner_picker_hint, min, max));

        int pad = (int) (getResources().getDisplayMetrics().density * 24f);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(picker);
        content.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.screen_corner_value_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    picker.clearFocus();
                    int picked = Math.max(min, Math.min(picker.getValue(), max));
                    config.setProperty(PrefsManager.KEY_SCREEN_CORNER_RADIUS,
                            String.valueOf(picked));
                    saveConfig();
                    updateScreenCornerRow();
                    updateEntrySummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Follow a bar height that moved: a radius that no longer fits is pulled down to the new
     * ceiling, and one that still fits is left exactly as the user set it.
     */
    private boolean clampCornerRadiusToBars() {
        int max = maxCornerRadiusPx();
        int saved = getIntProperty(PrefsManager.KEY_SCREEN_CORNER_RADIUS,
                PrefsManager.CORNER_DEFAULT);
        if (saved <= max) {
            return false;
        }
        config.setProperty(PrefsManager.KEY_SCREEN_CORNER_RADIUS, String.valueOf(max));
        saveConfig();
        return true;
    }

    /** The arc cannot reach past the band carrying it, so the shorter bar is the ceiling. */
    private int maxCornerRadiusPx() {
        return Math.min(effectiveBarHeightPx(statusBarControl),
                effectiveBarHeightPx(navBarControl));
    }

    private int effectiveBarHeightPx(BarHeightControl control) {
        int saved = getIntProperty(control.heightKey, HEIGHT_UNSET);
        return saved >= 0 ? saved : SystemBarDimens.readHeightPx(control.dimenNames);
    }

    /** Colours travel as eight hex digits: a leading '#' would read as a comment to the shell. */
    private int getColorProperty(String key, int fallback) {
        Integer parsed = PrefsManager.parseColor(config.getProperty(key));
        return parsed == null ? fallback : parsed.intValue();
    }

    private void setColorProperty(BarBackgroundControl control, int argb) {
        config.setProperty(control.colorKey, PrefsManager.formatColor(argb).substring(1));
    }

    /**
     * One row per audio stream the device exposes, so a ROM that gains a stream needs no change
     * here. Rows are built once; only their enabled state and value text are refreshed.
     */
    private void setupVolume() {
        volumeSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_VOLUME_HOOK_ENABLED, "false")));
        volumeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(PrefsManager.KEY_VOLUME_HOOK_ENABLED, String.valueOf(isChecked));
            saveConfig();
            updateVolumeRows();
        });
        addVolumeRows();
        updateVolumeRows();
    }

    private void addVolumeRows() {
        for (VolumeStreams.Stream stream : VolumeStreams.list(this)) {
            SwitchCompat toggle = new SwitchCompat(this);
            TextView value = new TextView(this);
            value.setTextSize(14f);
            value.setTextColor(getColor(R.color.colorPrimary));
            value.setPadding(0, dp(10), 0, dp(2));
            value.setVisibility(View.GONE);

            TextView name = new TextView(this);
            name.setText(stream.label);
            name.setTextSize(15f);
            name.setTextColor(getColor(R.color.colorTextPrimary));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(12);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(toggle);
            volumeStreamList.addView(row, rowParams);
            volumeStreamList.addView(value);

            final VolumeStepControl control = new VolumeStepControl(stream, toggle, value);
            toggle.setChecked("true".equals(config.getProperty(control.enabledKey, "false")));
            toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                config.setProperty(control.enabledKey, String.valueOf(isChecked));
                saveConfig();
                updateVolumeRow(control);
            });
            value.setOnClickListener(v -> showStepsPicker(control));
            volumeControls.add(control);
        }
    }

    private void updateVolumeRows() {
        for (VolumeStepControl control : volumeControls) {
            updateVolumeRow(control);
        }
    }

    private void updateVolumeRow(VolumeStepControl control) {
        boolean master = volumeSwitch.isChecked();
        control.toggle.setEnabled(master);
        boolean on = master && control.toggle.isChecked();
        control.valueText.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            return;
        }
        int saved = getIntProperty(control.stepsKey, STEPS_UNSET);
        control.valueText.setText(saved > 0
                ? getString(R.string.volume_steps_value_format, control.stream.steps, saved)
                : getString(R.string.volume_steps_current_format, control.stream.steps));
    }

    private void showStepsPicker(final VolumeStepControl control) {
        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(PrefsManager.STEPS_MIN);
        picker.setMaxValue(PrefsManager.STEPS_MAX);
        int saved = getIntProperty(control.stepsKey, STEPS_UNSET);
        int initial = saved > 0 ? saved : control.stream.steps;
        picker.setValue(Math.max(PrefsManager.STEPS_MIN,
                Math.min(initial, PrefsManager.STEPS_MAX)));

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText(getString(R.string.volume_steps_picker_hint,
                PrefsManager.STEPS_MIN, PrefsManager.STEPS_MAX));

        int pad = dp(24);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(picker);
        content.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(control.stream.label)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    picker.clearFocus();
                    int picked = Math.max(PrefsManager.STEPS_MIN,
                            Math.min(picker.getValue(), PrefsManager.STEPS_MAX));
                    config.setProperty(control.stepsKey, String.valueOf(picked));
                    saveConfig();
                    updateVolumeRow(control);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.volume_reboot_title)
                            .setMessage(getString(R.string.volume_reboot_message,
                                    control.stream.label, picked))
                            .setPositiveButton(R.string.system_bar_reboot_confirm, null)
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    /**
     * Per-app output gain, multiplied by the master percentage. Nothing here needs a reboot: the
     * hooks in the app processes read the properties on every volume call.
     */
    private void setupAppGain() {
        appGainSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_APP_GAIN_ENABLED, "false")));
        appGainSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(PrefsManager.KEY_APP_GAIN_ENABLED, String.valueOf(isChecked));
            saveConfig();
            updateAppGainRows();
        });
        appGainMasterRow.setOnClickListener(v -> showPercentPicker(
                PrefsManager.KEY_APP_GAIN_MASTER, getString(R.string.app_gain_master_title)));
        addAppButton.setOnClickListener(v -> showAppPicker());
        rebuildAppRows(addedPackages());
        updateAppGainRows();
    }

    /**
     * Per-app BYD channel, plus the master channel every app without a setting of its own obeys.
     * Like gain this needs no reboot: the hook reads the property on every attributes build inside
     * the app, so a change lands on that app's next playback. The apps share the list under the gain
     * switch because one row per app is enough for a driver to work with.
     */
    private void setupAppChannel() {
        appChannelSwitch.setChecked("true".equals(
                config.getProperty(PrefsManager.KEY_APP_CHANNEL_ENABLED, "false")));
        appChannelSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(PrefsManager.KEY_APP_CHANNEL_ENABLED, String.valueOf(isChecked));
            saveConfig();
            updateAppGainRows();
        });
        appChannelMasterRow.setOnClickListener(v -> showChannelPicker(
                PrefsManager.KEY_APP_CHANNEL_MASTER,
                getString(R.string.app_channel_master_title), true));
        // Rows were built with this switch still unread, so their channel suffix needs a pass.
        updateAppGainRows();
    }

    /**
     * OTA update control: intercept reboot after install, and optionally disable updates entirely.
     * A/B slot devices switch slots on reboot, so the user decides when that happens.
     */
    private void setupOta() {
        // Display current slot
        updateCurrentSlotDisplay();

        otaRebootSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_OTA_REBOOT_INTERCEPT, "false")));
        otaRebootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Show confirmation dialog before enabling
                new AlertDialog.Builder(this)
                        .setTitle(R.string.ota_reboot_intercept_title)
                        .setMessage(R.string.ota_reboot_intercept_confirm_message)
                        .setPositiveButton(R.string.ota_confirm_yes, (dialog, which) -> {
                            config.setProperty(PrefsManager.KEY_OTA_REBOOT_INTERCEPT, "true");
                            saveConfig();
                            updateEntrySummaries();
                        })
                        .setNegativeButton(R.string.ota_confirm_no, (dialog, which) -> {
                            otaRebootSwitch.setChecked(false);
                        })
                        .setCancelable(false)
                        .show();
            } else {
                config.setProperty(PrefsManager.KEY_OTA_REBOOT_INTERCEPT, "false");
                saveConfig();
                updateEntrySummaries();
            }
        });

        otaDisableSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_OTA_UPDATE_DISABLED, "false")));
        otaDisableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Show confirmation dialog before enabling
                new AlertDialog.Builder(this)
                        .setTitle(R.string.ota_update_disable_title)
                        .setMessage(R.string.ota_update_disable_confirm_message)
                        .setPositiveButton(R.string.ota_confirm_yes, (dialog, which) -> {
                            config.setProperty(PrefsManager.KEY_OTA_UPDATE_DISABLED, "true");
                            saveConfig();
                            updateEntrySummaries();
                        })
                        .setNegativeButton(R.string.ota_confirm_no, (dialog, which) -> {
                            otaDisableSwitch.setChecked(false);
                        })
                        .setCancelable(false)
                        .show();
            } else {
                config.setProperty(PrefsManager.KEY_OTA_UPDATE_DISABLED, "false");
                saveConfig();
                updateEntrySummaries();
            }
        });
    }

    /**
     * Autostart management hooks. The master switch only reveals the three choices, and the two
     * persistence choices need no confirmation: worst case an app keeps a box ticked the user
     * unticked. Which apps they cover is decided inside system_server on each package broadcast,
     * so a change takes effect from the next update and only the very first use needs a reboot.
     */
    private void setupAppStart() {
        appStartSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_APP_START_ENABLED, "false")));
        appStartDisableSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_APP_START_DISABLED, "false")));
        appStartUpdateSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_APP_START_KEEP_UPDATE, "false")));
        appStartInstallSwitch.setChecked(
                "true".equals(config.getProperty(PrefsManager.KEY_APP_START_KEEP_INSTALL, "false")));

        appStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setProperty(PrefsManager.KEY_APP_START_ENABLED, String.valueOf(isChecked));
            saveConfig();
            updateAppStartPanel();
            updateEntrySummaries();
        });

        // One listener for all three: clearing the two that lost needs to hand the same listener
        // back to them, which only works while a single instance serves every row.
        CompoundButton.OnCheckedChangeListener exclusiveListener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        SwitchCompat source = (SwitchCompat) buttonView;
                        config.setProperty(appStartKeyFor(source), String.valueOf(isChecked));
                        if (isChecked) {
                            for (SwitchCompat other : appStartOthersOf(source)) {
                                if (!other.isChecked()) continue;
                                other.setOnCheckedChangeListener(null);
                                other.setChecked(false);
                                other.setOnCheckedChangeListener(this);
                                config.setProperty(appStartKeyFor(other), "false");
                            }
                        }
                        saveConfig();
                        updateEntrySummaries();
                    }
                };
        appStartDisableSwitch.setOnCheckedChangeListener(exclusiveListener);
        appStartUpdateSwitch.setOnCheckedChangeListener(exclusiveListener);
        appStartInstallSwitch.setOnCheckedChangeListener(exclusiveListener);

        updateAppStartPanel();
    }

    private void updateAppStartPanel() {
        appStartSubPanel.setVisibility(
                appStartSwitch.isChecked() ? View.VISIBLE : View.GONE);
    }

    private String appStartKeyFor(SwitchCompat switchView) {
        if (switchView == appStartDisableSwitch) return PrefsManager.KEY_APP_START_DISABLED;
        if (switchView == appStartUpdateSwitch) return PrefsManager.KEY_APP_START_KEEP_UPDATE;
        return PrefsManager.KEY_APP_START_KEEP_INSTALL;
    }

    /** Removing the gate makes saving a tick state pointless, so the two choices cannot coexist. */
    private SwitchCompat[] appStartOthersOf(SwitchCompat switchView) {
        return switchView == appStartDisableSwitch
                ? new SwitchCompat[]{appStartUpdateSwitch, appStartInstallSwitch}
                : new SwitchCompat[]{appStartDisableSwitch};
    }

    /**
     * Update the current slot display. Reads from /proc/cmdline to determine active slot.
     */
    private void updateCurrentSlotDisplay() {
        if (otaCurrentSlotText == null) {
            return;
        }
        String currentSlot = readCurrentSlot();
        if (currentSlot != null) {
            otaCurrentSlotText.setText(getString(R.string.ota_current_slot_format, currentSlot));
        } else {
            otaCurrentSlotText.setText(R.string.ota_current_slot_unknown);
        }
    }

    /**
     * Read current slot from /proc/cmdline. Looks for androidboot.slot_suffix=_a or _b.
     */
    private String readCurrentSlot() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /proc/cmdline"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String result = null;
            while ((line = reader.readLine()) != null) {
                if (line.contains("androidboot.slot_suffix=_a")) {
                    result = "A";
                    break;
                } else if (line.contains("androidboot.slot_suffix=_b")) {
                    result = "B";
                    break;
                }
            }
            reader.close();
            process.waitFor();
            return result;
        } catch (Throwable t) {
            Log.e("BYD Dlink Customer", "Failed to read current slot", t);
        }
        return null;
    }

    /**
     * The choices are whatever this ROM's own attribute builder names, so a firmware that renames or
     * adds a channel changes this list by itself. A master row has one way out -- 不修改, which leaves
     * every app where it is -- while an app's own row has two: follow the master, or keep the channel
     * the app chose for itself, which is the one answer the master does not override. Each name is
     * followed by the stream number the car itself names that channel by, because that is the number
     * BYD's own audio settings and 高德's hidden 音频通道 list use.
     */
    private void showChannelPicker(final String key, String label, final boolean master) {
        final List<BydChannels.Channel> channels = BydChannels.discover();
        int ownChoices = master ? 1 : 2;
        CharSequence[] items = new CharSequence[channels.size() + ownChoices + 1];
        items[0] = getString(master ? R.string.app_channel_master_none
                : R.string.app_channel_unset);
        if (!master) {
            items[1] = getString(R.string.app_channel_exempt);
        }
        for (int i = 0; i < channels.size(); i++) {
            BydChannels.Channel channel = channels.get(i);
            items[i + ownChoices] = channel.stream == BydChannels.NO_STREAM
                    ? channel.label
                    : getString(R.string.app_channel_item_format, channel.label, channel.stream);
        }
        items[items.length - 1] = getString(R.string.app_channel_custom);
        int current = getIntProperty(key, PrefsManager.CHANNEL_UNSET);
        int checked = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).contentType == current) {
                checked = i + ownChoices;
                break;
            }
        }
        final String appLabel = label;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_channel_picker_title, label))
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which != items.length - 1) {
                        return;
                    }
                    dialog.dismiss();
                    showCustomChannelPicker(key, appLabel, master);
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int index = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    int value;
                    if (index < ownChoices) {
                        value = !master && index == 1 ? PrefsManager.CHANNEL_EXEMPT
                                : PrefsManager.CHANNEL_UNSET;
                    } else {
                        value = channels.get(index - ownChoices).contentType;
                    }
                    applyChannel(key, value, appLabel, master);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A number typed the way the car names channels -- a stream, so 导航 is 14 -- resolved back to
     * the content type the hook has to write. One that no channel answers to is written as itself:
     * BYD's builder accepts content types the policy never names, and the point of a custom field is
     * to reach those, so the screen reports what it took the number for rather than refusing it.
     */
    private void showCustomChannelPicker(final String key, final String appLabel,
            final boolean master) {
        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(CUSTOM_CHANNEL_MAX);
        int saved = getIntProperty(key, PrefsManager.CHANNEL_UNSET);
        picker.setValue(saved < 0 ? 0 : Math.min(saved, CUSTOM_CHANNEL_MAX));

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText(getString(R.string.app_channel_custom_hint, CUSTOM_CHANNEL_MAX));

        int pad = dp(24);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(picker);
        content.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_channel_custom_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    picker.clearFocus();
                    int typed = Math.max(0, Math.min(picker.getValue(), CUSTOM_CHANNEL_MAX));
                    int byStream = BydChannels.contentTypeForStream(typed);
                    applyChannel(key,
                            byStream == BydChannels.NO_STREAM ? typed : byStream,
                            appLabel, master);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyChannel(String key, int contentType, String appLabel, boolean master) {
        config.setProperty(key, String.valueOf(contentType));
        saveConfig();
        updateAppGainRows();
        if (master) {
            Toast.makeText(this, contentType < 0 ? getString(R.string.app_channel_master_cleared)
                    : getString(R.string.app_channel_master_applied_format,
                            BydChannels.displayFor(contentType)),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, contentType < 0 ? getString(R.string.app_channel_cleared_toast)
                : getString(R.string.app_channel_applied_format, appLabel,
                        contentType == PrefsManager.CHANNEL_EXEMPT
                                ? getString(R.string.app_channel_exempt)
                                : BydChannels.displayFor(contentType)),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Rows carry the apps the driver added, and only those: the whole launcher of a head unit is
     * longer than the screen that would have to hold it. Each row shows icon, name and package
     * because the car can hold two builds under one name, and a 强制停止 button, because hook code
     * only reaches an app once that app's process starts again.
     */
    private void rebuildAppRows(Set<String> packages) {
        // The hooks obey this list rather than the LSPosed scope, so persisting it is not a UI
        // detail: whatever the screen shows has to be what the per-app membership properties say.
        // Saving every time also carries an upgraded setting -- one that predates the list and was
        // seeded from its per-app values -- over into the flag the hooks now require.
        saveAddedPackages(packages);
        appGainList.removeAllViews();
        appGainControls.clear();
        for (String packageName : packages) {
            addAppRow(packageName);
        }
    }

    private void addAppRow(String packageName) {
        PackageManager pm = getPackageManager();
        ApplicationInfo info = appInfoOf(pm, packageName);
        String label = labelOf(pm, info, packageName);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(info == null ? pm.getDefaultActivityIcon() : info.loadIcon(pm));
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.rightMargin = dp(12);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(15f);
        name.setTextColor(getColor(R.color.colorTextPrimary));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        TextView pkg = new TextView(this);
        pkg.setText(packageName);
        pkg.setTextSize(12f);
        pkg.setTextColor(getColor(R.color.colorTextSecondary));
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.END);

        TextView value = new TextView(this);
        value.setTextSize(14f);
        value.setTextColor(getColor(R.color.colorPrimary));
        value.setBackgroundResource(R.drawable.bg_button);
        value.setPadding(dp(12), dp(6), dp(12), dp(6));
        value.setVisibility(appGainSwitch.isChecked() ? View.VISIBLE : View.GONE);

        MaterialButton stop = rowButton(R.string.app_force_stop);
        stop.setOnClickListener(v -> forceStop(packageName, label));

        MaterialButton remove = rowButton(R.string.app_remove);
        remove.setOnClickListener(v -> removeFromList(packageName, label));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(name);
        texts.addView(pkg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setBackgroundResource(rowBackground());
        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(12);
        row.addView(icon, iconParams);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams stopParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        stopParams.leftMargin = dp(8);
        row.addView(stop, stopParams);
        LinearLayout.LayoutParams removeParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        removeParams.leftMargin = dp(6);
        row.addView(remove, removeParams);
        appGainList.addView(row, rowParams);

        final AppGainControl control = new AppGainControl(packageName, label, name, value);
        row.setOnClickListener(v -> showAppAudioDialog(control));
        appGainControls.add(control);
    }

    /** The small action button at the end of an app row. */
    private MaterialButton rowButton(int textRes) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinWidth(0);
        button.setMinHeight(dp(36));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        return button;
    }

    /**
     * Take an app off the hook list. Membership is what the injected code obeys, so this is the whole
     * un-configuration: the app's own gain and channel values stay behind, which is what lets adding it
     * back restore the setting rather than start over.
     */
    private void removeFromList(String packageName, String label) {
        Set<String> packages = addedPackages();
        if (!packages.remove(packageName)) {
            return;
        }
        rebuildAppRows(packages);
        updateAppGainRows();
        Toast.makeText(this, getString(R.string.app_removed_format, label),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Stopping the app is what makes a hook code change reach it: the injected code belongs to the
     * process, so a player that keeps running keeps the hooks it was started with. The package
     * arrives from this device's own package list, and is still checked because it is going into a
     * shell command.
     */
    private void forceStop(String packageName, String label) {
        if (!isShellSafe(packageName)) {
            return;
        }
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "am force-stop " + packageName}).waitFor();
            Toast.makeText(this, getString(R.string.app_force_stop_done_format, label),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e("BYD Dlink Customer", "force stop failed", t);
            Toast.makeText(this, R.string.app_force_stop_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isShellSafe(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** The apps this screen carries rows for, in the order they were added. */
    private Set<String> addedPackages() {
        Set<String> packages = new LinkedHashSet<>();
        String stored = config.getProperty(PrefsManager.KEY_APP_AUDIO_LIST);
        if (stored == null) {
            // Upgrading must not hide a setting the hooks still obey: whatever already has a
            // per-app value starts on the list.
            for (String key : config.stringPropertyNames()) {
                if (isAppAudioKey(key)) {
                    packages.add(key.substring(prefixOf(key).length()));
                }
            }
            return packages;
        }
        for (String entry : stored.split(",")) {
            String pkg = entry.trim();
            if (!pkg.isEmpty()) {
                packages.add(pkg);
            }
        }
        return packages;
    }

    private void saveAddedPackages(Set<String> packages) {
        syncSelectedFlags(packages);
        config.setProperty(PrefsManager.KEY_APP_AUDIO_LIST, TextUtils.join(",", packages));
        saveConfig();
    }

    /**
     * The list is not only what this screen shows -- the hooks obey it, so membership has to cross
     * into every app process through its own property. A flag left true behind a removed app would
     * keep hooking it, which is the whole thing the list exists to prevent, so both directions are
     * written.
     */
    private void syncSelectedFlags(Set<String> packages) {
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith(PrefsManager.KEY_APP_SELECTED_PREFIX)) {
                continue;
            }
            if (!packages.contains(key.substring(PrefsManager.KEY_APP_SELECTED_PREFIX.length()))
                    && !"false".equals(config.getProperty(key))) {
                config.setProperty(key, "false");
            }
        }
        for (String packageName : packages) {
            config.setProperty(PrefsManager.KEY_APP_SELECTED_PREFIX + packageName, "true");
        }
    }

    /**
     * The picker: multi-select, search by name, and system apps behind a checkbox, because the apps
     * worth re-channeling are the handful the driver installed and the rest is noise that makes the
     * list hard to read. What is already on the list arrives ticked, so un-ticking one is how it
     * comes off -- and since the hooks obey this list, off means untouched.
     */
    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (app.packageName.equals(getPackageName())) {
                continue;
            }
            installed.add(app);
        }
        Collections.sort(installed, (a, b) -> labelOf(pm, a, a.packageName)
                .compareToIgnoreCase(labelOf(pm, b, b.packageName)));
        final Set<String> selected = new LinkedHashSet<>(addedPackages());
        final AppPickerAdapter adapter = new AppPickerAdapter(pm, installed, selected);

        EditText search = new EditText(this);
        search.setHint(R.string.app_picker_search_hint);
        search.setSingleLine(true);
        search.setTextSize(14f);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s == null ? "" : s.toString());
            }

            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });

        CheckBox showSystem = new CheckBox(this);
        showSystem.setText(R.string.app_picker_show_system);
        showSystem.setTextSize(14f);
        showSystem.setOnCheckedChangeListener((buttonView, isChecked) ->
                adapter.setShowSystem(isChecked));

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        TextView empty = new TextView(this);
        empty.setText(R.string.app_picker_empty);
        empty.setTextSize(14f);
        empty.setTextColor(getColor(R.color.colorTextSecondary));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(24), 0, 0);
        list.setEmptyView(empty);

        int pad = dp(24);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(search);
        content.addView(showSystem);
        // A fixed height, and a dialog short enough to sit above the keyboard the search field
        // brings up: measured on the head unit, an adjust-pan dialog whose bottom is under the IME
        // leaves its 确认添加 button untappable.
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_picker_title)
                .setView(content)
                .setPositiveButton(R.string.app_picker_confirm, (d, which) -> {
                    rebuildAppRows(selected);
                    updateAppGainRows();
                    Toast.makeText(this,
                            getString(R.string.app_picker_saved_format, selected.size()),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        dialog.show();
    }

    /** One row of the picker: icon, name, package, and a box that says whether it is on the list. */
    private final class AppPickerAdapter extends BaseAdapter {
        private final PackageManager pm;
        private final List<ApplicationInfo> installed;
        private final Set<String> selected;
        private final List<ApplicationInfo> matched = new ArrayList<>();
        private boolean showSystem;
        private String query = "";

        AppPickerAdapter(PackageManager pm, List<ApplicationInfo> installed,
                         Set<String> selected) {
            this.pm = pm;
            this.installed = installed;
            this.selected = selected;
            refresh();
        }

        void setShowSystem(boolean value) {
            showSystem = value;
            refresh();
        }

        void setQuery(String value) {
            query = value.trim().toLowerCase();
            refresh();
        }

        private void refresh() {
            matched.clear();
            for (ApplicationInfo app : installed) {
                if (!showSystem && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                if (!query.isEmpty() && !matches(app)) {
                    continue;
                }
                matched.add(app);
            }
            notifyDataSetChanged();
        }

        private boolean matches(ApplicationInfo app) {
            return labelOf(pm, app, app.packageName).toLowerCase().contains(query)
                    || app.packageName.toLowerCase().contains(query);
        }

        @Override
        public int getCount() {
            return matched.size();
        }

        @Override
        public Object getItem(int position) {
            return matched.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ApplicationInfo app = matched.get(position);
            final Holder holder;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Holder) {
                holder = (Holder) convertView.getTag();
            } else {
                holder = new Holder();
                holder.icon = new ImageView(SettingsActivity.this);
                holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.name = new TextView(SettingsActivity.this);
                holder.name.setTextSize(15f);
                holder.name.setTextColor(getColor(R.color.colorTextPrimary));
                holder.name.setSingleLine(true);
                holder.name.setEllipsize(TextUtils.TruncateAt.END);
                holder.pkg = new TextView(SettingsActivity.this);
                holder.pkg.setTextSize(12f);
                holder.pkg.setTextColor(getColor(R.color.colorTextSecondary));
                holder.pkg.setSingleLine(true);
                holder.pkg.setEllipsize(TextUtils.TruncateAt.END);
                holder.check = new CheckBox(SettingsActivity.this);
                // The row is the tap target: a clickable box of its own would keep whichever app
                // it was first built for.
                holder.check.setFocusable(false);
                holder.check.setClickable(false);

                LinearLayout texts = new LinearLayout(SettingsActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(holder.name);
                texts.addView(holder.pkg);

                LinearLayout row = new LinearLayout(SettingsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(rowBackground());
                row.setPadding(0, dp(8), 0, dp(8));
                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(dp(40), dp(40));
                iconParams.rightMargin = dp(12);
                row.addView(holder.icon, iconParams);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(holder.check);
                convertView = row;
                convertView.setTag(holder);
            }
            holder.icon.setImageDrawable(safeIcon(app));
            holder.name.setText(labelOf(pm, app, app.packageName));
            holder.pkg.setText(app.packageName);
            holder.check.setChecked(selected.contains(app.packageName));
            ((LinearLayout) convertView).setOnClickListener(v -> {
                holder.check.toggle();
                select(app.packageName, holder.check.isChecked());
            });
            return convertView;
        }

        private void select(String packageName, boolean on) {
            if (on) {
                selected.add(packageName);
            } else {
                selected.remove(packageName);
            }
        }

        private Drawable safeIcon(ApplicationInfo app) {
            try {
                return app.loadIcon(pm);
            } catch (Throwable t) {
                return pm.getDefaultActivityIcon();
            }
        }

        private final class Holder {
            ImageView icon;
            TextView name;
            TextView pkg;
            CheckBox check;
        }
    }

    private ApplicationInfo appInfoOf(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationInfo(packageName, 0);
        } catch (Throwable t) {
            // Uninstalled since it was added: the row still carries a value the hook obeys.
            return null;
        }
    }

    /** Two installs can share a label, so callers that list apps spell the package out beside it. */
    private static String labelOf(PackageManager pm, ApplicationInfo info, String fallback) {
        if (info == null) {
            return fallback;
        }
        try {
            String label = pm.getApplicationLabel(info).toString().trim();
            return label.isEmpty() ? fallback : label;
        } catch (Throwable t) {
            return fallback;
        }
    }


    private int rowBackground() {
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        return ripple.resourceId;
    }

    private static boolean isAppAudioKey(String key) {
        return key.startsWith(PrefsManager.KEY_APP_GAIN_PREFIX)
                || key.startsWith(PrefsManager.KEY_APP_CHANNEL_PREFIX);
    }

    private static String prefixOf(String key) {
        return key.startsWith(PrefsManager.KEY_APP_CHANNEL_PREFIX)
                ? PrefsManager.KEY_APP_CHANNEL_PREFIX : PrefsManager.KEY_APP_GAIN_PREFIX;
    }

    private boolean isConfigured(String packageName) {
        String channel = config.getProperty(PrefsManager.KEY_APP_CHANNEL_PREFIX + packageName);
        if (channel != null && !"-1".equals(channel)) {
            return true;
        }
        String value = config.getProperty(PrefsManager.KEY_APP_GAIN_PREFIX + packageName);
        try {
            return value != null && Integer.parseInt(value) < PrefsManager.GAIN_MAX;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateAppGainRows() {
        boolean global = globalSwitch.isChecked();
        boolean gainOn = global && appGainSwitch.isChecked();
        boolean channelOn = global && appChannelSwitch.isChecked();
        // One list serves both hooks, so it stays open while either of them is live.
        boolean listOn = gainOn || channelOn;

        appGainMasterRow.setVisibility(gainOn ? View.VISIBLE : View.GONE);
        appGainMasterHint.setVisibility(gainOn ? View.VISIBLE : View.GONE);
        appChannelMasterRow.setVisibility(channelOn ? View.VISIBLE : View.GONE);
        appChannelMasterHint.setVisibility(channelOn ? View.VISIBLE : View.GONE);
        appGainDivider.setVisibility(listOn ? View.VISIBLE : View.GONE);
        addAppButton.setVisibility(listOn ? View.VISIBLE : View.GONE);
        appGainList.setVisibility(listOn ? View.VISIBLE : View.GONE);

        if (gainOn) {
            appGainMasterText.setText(getString(R.string.app_gain_master_format,
                    clampGain(getIntProperty(PrefsManager.KEY_APP_GAIN_MASTER,
                            PrefsManager.GAIN_MAX))));
        }
        if (channelOn) {
            int master = masterChannel();
            appChannelMasterText.setText(master < 0 ? getString(R.string.app_channel_master_none)
                    : getString(R.string.app_channel_row_format, BydChannels.displayFor(master)));
        }

        int master = clampGain(getIntProperty(PrefsManager.KEY_APP_GAIN_MASTER,
                PrefsManager.GAIN_MAX));
        for (AppGainControl control : appGainControls) {
            int percent = clampGain(getIntProperty(control.key, PrefsManager.GAIN_MAX));
            control.value.setVisibility(gainOn ? View.VISIBLE : View.GONE);
            control.value.setText(getString(R.string.app_gain_master_format, percent)
                    + channelSuffix(control));
            control.name.setText(gainOn && percent < PrefsManager.GAIN_MAX
                    ? getString(R.string.app_gain_row_format, control.label, master * percent / 100)
                    : control.label);
        }
    }

    /**
     * Which channel this app will really play on: its own setting wins, an app that asked to keep its
     * own choice beats even that, and anything else follows the master. Null is the one case with
     * nothing configured at either level.
     */
    private String channelText(AppGainControl control, int own) {
        if (own == PrefsManager.CHANNEL_EXEMPT) {
            return getString(R.string.app_channel_exempt_short);
        }
        if (own >= 0) {
            return BydChannels.displayFor(own);
        }
        int master = masterChannel();
        return master < 0 ? null : getString(R.string.app_channel_follows_master_format,
                BydChannels.displayFor(master));
    }

    private String channelSuffix(AppGainControl control) {
        if (!globalSwitch.isChecked() || !appChannelSwitch.isChecked()) {
            return "";
        }
        String text = channelText(control, channelOf(control));
        return text == null ? "" : " · " + text;
    }

    private int channelOf(AppGainControl control) {
        return getIntProperty(PrefsManager.KEY_APP_CHANNEL_PREFIX + control.packageName,
                PrefsManager.CHANNEL_UNSET);
    }

    private int masterChannel() {
        return getIntProperty(PrefsManager.KEY_APP_CHANNEL_MASTER, PrefsManager.CHANNEL_UNSET);
    }

    private int clampGain(int value) {
        return Math.max(PrefsManager.GAIN_MIN, Math.min(PrefsManager.GAIN_MAX, value));
    }

    /**
     * One dialog carries both per-app audio settings -- the percentage to play at and the channel to
     * play on -- because a driver should have to open one thing per app, not two.
     */
    private void showAppAudioDialog(final AppGainControl control) {
        final TextView channelLine = new TextView(this);
        channelLine.setTextSize(15f);
        channelLine.setTextColor(getColor(R.color.colorPrimary));
        channelLine.setPadding(0, dp(16), 0, 0);
        int channel = channelOf(control);
        String channelText = channelText(control, channel);
        channelLine.setText(getString(R.string.app_channel_row_format, channelText == null
                ? getString(R.string.app_channel_unset) : channelText));
        channelLine.setVisibility(appChannelSwitch.isChecked() ? View.VISIBLE : View.GONE);
        channelLine.setOnClickListener(v -> {
            channelDialog.dismiss();
            showChannelPicker(PrefsManager.KEY_APP_CHANNEL_PREFIX + control.packageName,
                    control.label, false);
        });
        showPercentDialog(control.key, control.label, channelLine);
    }

    private AlertDialog channelDialog;

    private void showPercentPicker(String key, String title) {
        showPercentDialog(key, title, null);
    }

    /**
     * A percentage is a position on a range, so it is dragged, not spun: the bar is the same control
     * the split-screen ratios already use, which keeps one gesture vocabulary in this screen.
     */
    private void showPercentDialog(final String key, String title, View extra) {
        final int initial = clampGain(getIntProperty(key, PrefsManager.GAIN_MAX));

        TextView value = new TextView(this);
        value.setTextSize(30f);
        value.setTextColor(getColor(R.color.colorPrimary));
        value.setGravity(Gravity.CENTER);
        value.setText(getString(R.string.app_gain_master_format, initial));

        final SeekBar bar = new SeekBar(this);
        bar.setMax(PrefsManager.GAIN_MAX);
        bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(getString(R.string.app_gain_master_format, clampGain(progress)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText(getString(R.string.app_gain_picker_hint,
                PrefsManager.GAIN_MIN, PrefsManager.GAIN_MAX));

        int pad = dp(24);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(value);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(12);
        content.addView(bar, barParams);
        content.addView(hint);
        if (extra != null) {
            content.addView(extra);
        }

        channelDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    config.setProperty(key, String.valueOf(clampGain(bar.getProgress())));
                    saveConfig();
                    updateAppGainRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        channelDialog.show();
    }

    private void setupSeekBars() {
        int landscapeRatio = getIntProperty("landscape_ratio", 50);
        int portraitRatio = getIntProperty("portrait_ratio", 50);

        landscapeSeekBar.setProgress(landscapeRatio - RATIO_MIN);
        portraitSeekBar.setProgress(portraitRatio - RATIO_MIN);

        landscapeValue.setText(getString(R.string.ratio_format, landscapeRatio));
        portraitValue.setText(getString(R.string.ratio_format, portraitRatio));

        landscapeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ratio = progress + RATIO_MIN;
                landscapeValue.setText(getString(R.string.ratio_format, ratio));
                if (fromUser) {
                    config.setProperty("landscape_ratio", String.valueOf(ratio));
                    saveConfig();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        portraitSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ratio = progress + RATIO_MIN;
                portraitValue.setText(getString(R.string.ratio_format, ratio));
                if (fromUser) {
                    config.setProperty("portrait_ratio", String.valueOf(ratio));
                    saveConfig();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupShellCommand() {
        MaterialButton copyBtn = findViewById(R.id.copyCommandBtn);
        copyBtn.setOnClickListener(v -> {
            String command = "am broadcast -a com.byd.dlink.customer.SPLIT_SCREEN " +
                    "--es pkg1 \"com.example.app1\" " +
                    "--es pkg2 \"com.example.app2\"";

            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("split_screen_cmd", command));
            Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateModuleStatus() {
        View statusDot = findViewById(R.id.statusDot);
        TextView statusText = findViewById(R.id.statusText);
        boolean activated = isModuleActivated();

        if (activated) {
            statusDot.setBackgroundResource(R.drawable.status_dot_active);
            statusText.setText(R.string.module_activated);
            statusText.setTextColor(getColor(R.color.colorActive));
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_inactive);
            statusText.setText(R.string.module_not_activated);
            statusText.setTextColor(getColor(R.color.colorInactive));
        }
    }

    private void updateVersionInfo() {
        TextView versionText = findViewById(R.id.versionText);
        MaterialButton githubButton = findViewById(R.id.githubButton);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            versionText.setText(getString(R.string.version_info, version));
        } catch (Exception e) {
            versionText.setText(getString(R.string.version_info, "unknown"));
        }
        githubButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(getString(R.string.about_github_url)));
            startActivity(intent);
        });
    }

    private void updateModuleDependentViews() {
        boolean globalEnabled = globalSwitch.isChecked();
        splitScreenSwitch.setEnabled(globalEnabled);
        splitBlacklistSwitch.setEnabled(globalEnabled);
        statusBarControl.toggle.setEnabled(globalEnabled);
        navBarControl.toggle.setEnabled(globalEnabled);
        updateBarHeight(statusBarControl);
        updateBarHeight(navBarControl);
        statusBarBgControl.toggle.setEnabled(globalEnabled);
        navBarBgControl.toggle.setEnabled(globalEnabled);
        statusBarBgControl.immersiveToggle.setEnabled(globalEnabled);
        statusBarBgControl.autoToggle.setEnabled(globalEnabled);
        navBarBgControl.immersiveToggle.setEnabled(globalEnabled);
        navBarBgControl.autoToggle.setEnabled(globalEnabled);
        updateBarBackground(statusBarBgControl);
        updateBarBackground(navBarBgControl);
        volumeSwitch.setEnabled(globalEnabled);
        updateVolumeRows();
        appGainSwitch.setEnabled(globalEnabled);
        updateAppGainRows();
        appChannelSwitch.setEnabled(globalEnabled);
        updateSplitScreenDependentViews();
        updateEntrySummaries();
    }

    private void updateSplitScreenDependentViews() {
        boolean enabled = globalSwitch.isChecked() && splitScreenSwitch.isChecked();
        landscapeSeekBar.setEnabled(enabled);
        portraitSeekBar.setEnabled(enabled);
    }

    private boolean isModuleActivated() {
        return isModuleActive();
    }

    public static boolean isModuleActive() {
        return false;
    }

    private int getIntProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(config.getProperty(key));
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        try {
            File localFile = new File(getFilesDir(), LOCAL_CONFIG);
            if (localFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(localFile))) {
                    props.load(reader);
                }
            }
        } catch (Throwable ignored) {
        }
        return props;
    }

    private void saveConfig() {
        final Properties changed = changesSinceSaved();
        if (changed.isEmpty()) {
            return;
        }
        try {
            final File localFile = new File(getFilesDir(), LOCAL_CONFIG);
            final File tempFile = new File(getFilesDir(), LOCAL_CONFIG + ".tmp");
            final Properties toWrite = copyOf(config);
            StringBuilder cmd = new StringBuilder();
            cmd.append("mv ").append(tempFile.getAbsolutePath())
                    .append(" ").append(localFile.getAbsolutePath())
                    .append(" && cp ").append(localFile.getAbsolutePath())
                    .append(" ").append(CONFIG_FILE)
                    .append(" && chmod 666 ").append(CONFIG_FILE);
            for (String key : changed.stringPropertyNames()) {
                String propKey = PrefsManager.propKeyFor(key);
                if (propKey != null) {
                    cmd.append(" && setprop ").append(propKey).append(' ')
                            .append(changed.getProperty(key));
                }
            }
            final String shell = cmd.toString();
            // Writing the file and forking a root shell are disk work with no business on the UI
            // thread, and one thread keeps them arriving in the order they were asked for.
            configWriter.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        try (FileWriter writer = new FileWriter(tempFile)) {
                            toWrite.store(writer, null);
                        }
                        Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", shell});
                        java.io.InputStream err = process.getErrorStream();
                        StringBuilder output = new StringBuilder();
                        byte[] buf = new byte[512];
                        int read;
                        while ((read = err.read(buf)) > 0) {
                            output.append(new String(buf, 0, read));
                        }
                        int exit = process.waitFor();
                        if (exit != 0) {
                            Log.e("BYD Dlink Customer",
                                    "config write failed, exit=" + exit + " " + output);
                            return;
                        }
                        saved = toWrite;
                    } catch (Throwable t) {
                        Log.e("BYD Dlink Customer", "config write failed", t);
                        return;
                    }
                    // SystemUI decides the bars once per window now rather than on every layout pass,
                    // so a change made here has to be announced or it would not show until the next
                    // app came forward.
                    sendBroadcast(new Intent("com.byd.dlink.customer.PREFS_CHANGED")
                            .setPackage("com.android.systemui"));
                }
            });
            updateEntrySummaries();
        } catch (Throwable t) {
            Log.e("BYD Dlink Customer", "saveConfig error", t);
        }
    }

    /** The keys whose value is not already what the device was last given. */
    private Properties changesSinceSaved() {
        Properties changed = new Properties();
        for (String key : config.stringPropertyNames()) {
            String value = config.getProperty(key);
            if (value != null && !value.equals(saved.getProperty(key))) {
                changed.setProperty(key, value);
            }
        }
        return changed;
    }

    private static Properties copyOf(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private static final class BarHeightControl {
        final SwitchCompat toggle;
        final View valueRow;
        final TextView valueText;
        final int titleRes;
        final int labelRes;
        final String enabledKey;
        final String heightKey;
        final String[] dimenNames;

        BarHeightControl(SwitchCompat toggle, View valueRow, TextView valueText, int titleRes,
                         int labelRes, String enabledKey, String heightKey, String[] dimenNames) {
            this.toggle = toggle;
            this.valueRow = valueRow;
            this.valueText = valueText;
            this.titleRes = titleRes;
            this.labelRes = labelRes;
            this.enabledKey = enabledKey;
            this.heightKey = heightKey;
            this.dimenNames = dimenNames;
        }
    }

    /**
     * One bar's background rows: its hook switch, then the two exclusive modes, then a colour chip
     * and the code it stands for. The colour is stored without its '#' because it travels through a
     * shell command.
     */
    private static final class BarBackgroundControl {
        final SwitchCompat toggle;
        final View valueRow;
        final View swatch;
        final TextView valueText;
        final View immersiveRow;
        final SwitchCompat immersiveToggle;
        final View autoRow;
        final SwitchCompat autoToggle;
        final TextView autoSummary;
        final int labelRes;
        final String enabledKey;
        final String colorKey;
        final String immersiveKey;
        final String autoKey;

        BarBackgroundControl(SwitchCompat toggle, View valueRow, View swatch, TextView valueText,
                             View immersiveRow, SwitchCompat immersiveToggle, View autoRow,
                             SwitchCompat autoToggle, TextView autoSummary, int labelRes,
                             String enabledKey, String colorKey, String immersiveKey,
                             String autoKey) {
            this.toggle = toggle;
            this.valueRow = valueRow;
            this.swatch = swatch;
            this.valueText = valueText;
            this.immersiveRow = immersiveRow;
            this.immersiveToggle = immersiveToggle;
            this.autoRow = autoRow;
            this.autoToggle = autoToggle;
            this.autoSummary = autoSummary;
            this.labelRes = labelRes;
            this.enabledKey = enabledKey;
            this.colorKey = colorKey;
            this.immersiveKey = immersiveKey;
            this.autoKey = autoKey;
        }
    }

    /** One audio stream's row: its own hook switch plus the tappable step count below it. */
    private static final class VolumeStepControl {
        final VolumeStreams.Stream stream;
        final SwitchCompat toggle;
        final TextView valueText;
        final String enabledKey;
        final String stepsKey;

        VolumeStepControl(VolumeStreams.Stream stream, SwitchCompat toggle, TextView valueText) {
            this.stream = stream;
            this.toggle = toggle;
            this.valueText = valueText;
            this.enabledKey = PrefsManager.KEY_VOLUME_STREAM_ENABLED_PREFIX + stream.type;
            this.stepsKey = PrefsManager.KEY_VOLUME_STREAM_STEPS_PREFIX + stream.type;
        }
    }

    /**
     * One app's row: the label doubles as the live read-out of what the app will actually play at,
     * the value on the right is the percentage set for this app alone.
     */
    private static final class AppGainControl {
        final String label;
        final String packageName;
        final TextView name;
        final TextView value;
        final String key;

        AppGainControl(String packageName, String label, TextView name, TextView value) {
            this.label = label;
            this.packageName = packageName;
            this.name = name;
            this.value = value;
            this.key = PrefsManager.KEY_APP_GAIN_PREFIX + packageName;
        }
    }
}
