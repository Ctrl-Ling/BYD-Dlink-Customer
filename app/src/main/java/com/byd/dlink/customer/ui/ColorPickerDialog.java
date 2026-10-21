package com.byd.dlink.customer.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.byd.dlink.customer.R;
import com.byd.dlink.customer.utils.PrefsManager;

/**
 * Colour, plus the transparency that makes it usable over a moving map: the value that leaves here
 * is a full ARGB int, shown and typed as #AARRGGBB.
 *
 * The hue and alpha bars are the same widget with different gradients, and every control edits one
 * shared HSV state, so the palette, the bars and the typed code cannot drift apart.
 */
public class ColorPickerDialog {

    public interface OnPicked {
        void onPicked(int argb);
    }

    public static void show(Context context, String title, int initialArgb, OnPicked callback) {
        new Picker(context, initialArgb, callback).show(title);
    }

    private static class Picker implements GradientBar.Listener {

        private static final int BAR_HUE = 1;
        private static final int BAR_ALPHA = 2;

        private final Context context;
        private final float[] hsv = new float[3];
        private final OnPicked callback;
        private int alpha = 255;

        private SatValPanel field;
        private GradientBar hueBar;
        private GradientBar alphaBar;
        private TextView alphaValue;
        private EditText code;
        private View preview;
        private boolean updatingCode;

        Picker(Context context, int initialArgb, OnPicked callback) {
            this.context = context;
            this.callback = callback;
            Color.colorToHSV(initialArgb, hsv);
            setAlpha(Color.alpha(initialArgb));
        }

        void show(String title) {
            int pad = dp(20);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, pad / 2, pad, 0);

            field = new SatValPanel(context);
            field.setListener((sat, value) -> {
                hsv[1] = sat;
                hsv[2] = value;
                refresh();
            });
            content.addView(field, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

            hueBar = new GradientBar(context, BAR_HUE, this);
            hueBar.setColors(new int[]{
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
                    0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000, });
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
            barParams.topMargin = dp(14);
            content.addView(hueBar, barParams);

            content.addView(label(R.string.color_picker_alpha));
            alphaValue = new TextView(context);
            alphaValue.setTextSize(12f);
            alphaValue.setTextColor(context.getColor(R.color.colorTextSecondary));
            alphaBar = new GradientBar(context, BAR_ALPHA, this);
            LinearLayout.LayoutParams alphaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
            alphaParams.topMargin = dp(4);
            content.addView(alphaBar, alphaParams);
            content.addView(alphaValue);

            content.addView(label(R.string.color_picker_code));
            code = new EditText(context);
            code.setSingleLine(true);
            code.setTextSize(14f);
            code.setHint(R.string.color_picker_code_hint);
            code.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (updatingCode) {
                        return;
                    }
                    // A code that is mid-typing is not a mistake, so it is only reported once
                    // there is nothing sensible left about it.
                    Integer parsed = PrefsManager.parseColor(s.toString());
                    if (parsed != null) {
                        Color.colorToHSV(parsed, hsv);
                        setAlpha(Color.alpha(parsed));
                        refresh();
                    }
                }
            });
            content.addView(code);

            preview = new View(context);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
            previewParams.topMargin = dp(12);
            content.addView(preview, previewParams);

            new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(content)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        Integer typed = PrefsManager.parseColor(code.getText().toString());
                        callback.onPicked(typed != null ? typed.intValue() : current());
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            refresh();
        }

        @Override
        public void onSlide(int bar, float position) {
            if (bar == BAR_HUE) {
                hsv[0] = position * 360f;
            } else {
                setAlpha(Math.round(position * 255f));
            }
            refresh();
        }

        private void setAlpha(int value) {
            alpha = Math.max(0, Math.min(255, value));
        }

        private int current() {
            return (alpha << 24) | (Color.HSVToColor(hsv) & 0x00FFFFFF);
        }

        private void refresh() {
            int opaque = Color.HSVToColor(hsv);
            field.setHsv(hsv);
            hueBar.setPosition(hsv[0] / 360f);
            alphaBar.setColors(new int[]{opaque & 0x00FFFFFF, 0xFF000000 | (opaque & 0x00FFFFFF)});
            alphaBar.setPosition(alpha / 255f);
            alphaValue.setText(context.getString(R.string.color_picker_alpha_format,
                    Math.round(alpha / 255f * 100f)));
            preview.setBackground(new ColorDrawable(current()));
            String text = PrefsManager.formatColor(current());
            if (!text.equalsIgnoreCase(code.getText().toString())) {
                updatingCode = true;
                code.setText(text);
                code.setSelection(text.length());
                updatingCode = false;
            }
        }

        private TextView label(int textRes) {
            TextView label = new TextView(context);
            label.setText(textRes);
            label.setTextSize(12f);
            label.setTextColor(context.getColor(R.color.colorTextSecondary));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(10);
            label.setLayoutParams(params);
            return label;
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
