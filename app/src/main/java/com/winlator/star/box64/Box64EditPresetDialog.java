package com.winlator.star.box64;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.winlator.star.R;
import com.winlator.star.contentdialog.ContentDialog;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.ArrayUtils;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.StringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public class Box64EditPresetDialog extends ContentDialog {
    private final Context context;
    private final String prefix;
    private final Box64Preset preset;
    private final boolean readonly;
    private Runnable onConfirmCallback;

    private boolean isDarkMode;

    public Box64EditPresetDialog(@NonNull Context context, String prefix, String presetId) {
        super(context, R.layout.box64_edit_preset_dialog);
        this.context = context;
        this.prefix = prefix;
        preset = presetId != null ? Box64PresetManager.getPreset(prefix, context, presetId) : null;
        readonly = preset != null && !preset.isCustom();
        setTitle(StringUtils.getString(context, prefix+"_preset"));
        setIcon(R.drawable.icon_env_var);

        // Load the user's preferred theme
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        isDarkMode = sharedPreferences.getBoolean("dark_mode", false);

        TextView environmentVariablesLabel = findViewById(R.id.TVEnvironmentVariables);
        applyFieldSetLabelStyle(environmentVariablesLabel, isDarkMode);  // Apply the dark or light mode styles

        final EditText etName = findViewById(R.id.ETName);
        etName.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        etName.setEnabled(!readonly);
        if (preset != null) {
            etName.setText(preset.name);
        }
        else etName.setText(context.getString(R.string.preset)+"-"+ Box64PresetManager.getNextPresetId(context, prefix));

        applyDarkThemeToEditText(etName);

        loadEnvVarsList();

        super.setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) return;
            name = name.replaceAll("[,\\|]+", "");
            Box64PresetManager.editPreset(prefix, context, preset != null ? preset.id : null, name, getEnvVars());
            if (onConfirmCallback != null) onConfirmCallback.run();
        });
    }

    @Override
    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    private EnvVars getEnvVars() {
        EnvVars envVars = new EnvVars();
        LinearLayout parent = findViewById(R.id.LLContent);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            String name = ((TextView)child.findViewById(R.id.TextView)).getText().toString();

            Spinner spinner = child.findViewById(R.id.Spinner);
            ToggleButton toggleButton = child.findViewById(R.id.ToggleButton);
            boolean toggleSwitch = toggleButton.getVisibility() == View.VISIBLE;
            String value = toggleSwitch ? (toggleButton.isChecked() ? "1" : "0") : spinner.getSelectedItem().toString();
            envVars.put(name, value);
        }
        return envVars;
    }

    private void loadEnvVarsList() {
        try {
            LinearLayout parent = findViewById(R.id.LLContent);
            LayoutInflater inflater = LayoutInflater.from(context);
            JSONArray data = new JSONArray(FileUtils.readString(context, prefix+"_env_vars.json"));
            EnvVars envVars = preset != null ? Box64PresetManager.getEnvVars(prefix, context, preset.id) : null;

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                final String name = item.getString("name");
                View child = inflater.inflate(R.layout.box64_env_var_list_item, parent, false);
                ((TextView)child.findViewById(R.id.TextView)).setText(name);

                child.findViewById(R.id.BTHelp).setOnClickListener((v) -> {
                    String suffix = name.replace(prefix.toUpperCase(Locale.ENGLISH)+"_", "").toLowerCase(Locale.ENGLISH);
                    String value = StringUtils.getString(context, "box64_env_var_help__"+suffix);
                    if (value != null) AppUtils.showHelpBox(context, v, value);
                });

                Spinner spinner = child.findViewById(R.id.Spinner);
                ToggleButton toggleButton = child.findViewById(R.id.ToggleButton);
                String[] values = ArrayUtils.toStringArray(item.getJSONArray("values"));
                String value = envVars != null && envVars.has(name) ? envVars.get(name) : item.getString("defaultValue");

                if (item.optBoolean("toggleSwitch", false)) {
                    toggleButton.setVisibility(View.VISIBLE);
                    toggleButton.setEnabled(!readonly);
                    toggleButton.setChecked(value.equals("1"));
                    if (readonly) toggleButton.setAlpha(0.5f);
                }
                else {
                    // The preset dialog is always dark (window background is #000000), so the
                    // popup must be dark too; the conditional left it white when the dark_mode
                    // pref was off, making the white-text dropdown items invisible.
                    spinner.setPopupBackgroundResource(R.drawable.dialog_background_dark_blue);
                    spinner.setVisibility(View.VISIBLE);
                    spinner.setEnabled(!readonly);
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values);
                    adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
                    spinner.setAdapter(adapter);
                    AppUtils.setSpinnerSelectionFromValue(spinner, value);
                }

                parent.addView(child);
            }
        }
        catch (JSONException e) {}
    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
//        Context context = textView.getContext();

        if (isDarkMode) {
            // Apply dark mode-specific attributes
            textView.setTextColor(Color.parseColor("#cccccc")); // Set text color to #cccccc
            textView.setBackgroundResource(R.color.content_dialog_background_dark); // Set dark background color
        } else {
            // Apply light mode-specific attributes (original FieldSetLabel)
            textView.setTextColor(Color.parseColor("#bdbdbd")); // Set text color to #bdbdbd
            textView.setBackgroundResource(R.color.window_background_color); // Set light background color
        }
    }

    private void applyDarkThemeToEditText(EditText editText) {
        // This dialog is always dark (window background #000000), so theme the field dark
        // unconditionally instead of keying off the dark_mode pref (which left it a white box).
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(Color.GRAY);
        editText.setBackgroundResource(R.drawable.edit_text_dark);
    }
}
