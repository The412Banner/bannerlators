package com.winlator.star.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.star.R;
import com.winlator.star.core.KeyValueSet;
import com.winlator.star.core.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

public class FrameRatingHorizontal extends FrameLayout implements Runnable {
    private final Context context;
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private float cpuTemp = 0;
    private int gpuLoad = 0;
    private float batteryTemp = 0;
    private float batteryWattage = 0;
    private final String totalRAM;

    private final TextView tvFPS, tvCPUTemp, tvGPULoad, tvRAM, tvBatteryTemp, tvBatteryVoltage, tvRenderer, tvGPU;

    public FrameRatingHorizontal(Context context) {
        this(context, null);
    }

    public FrameRatingHorizontal(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        LayoutInflater.from(context).inflate(R.layout.hud_horizontal, this, true);

        tvFPS = findViewById(R.id.TVFPS);
        tvCPUTemp = findViewById(R.id.TVCPUTemp);
        tvGPULoad = findViewById(R.id.TVGPULoad);
        tvRAM = findViewById(R.id.TVRAM);
        tvBatteryTemp = findViewById(R.id.TVBatteryTemp);
        tvBatteryVoltage = findViewById(R.id.TVBatteryVoltage);
        tvRenderer = findViewById(R.id.TVRenderer);
        tvGPU = findViewById(R.id.TVGPU);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalRAM = StringUtils.formatBytes(mi.totalMem, false);
    }

    public void setRenderer(String renderer) {
        if (tvRenderer != null) post(() -> tvRenderer.setText(renderer));
    }

    public void setGpuName(String gpuName) {
        if (tvGPU != null) post(() -> tvGPU.setText(gpuName));
    }

    public void reset() {
        lastTime = 0;
        frameCount = 0;
        lastFPS = 0;
        post(this);
    }

    public void applyConfig(String configString) {
        if (configString == null || configString.isEmpty()) return;
        KeyValueSet config = new KeyValueSet(configString);
        try {
            int trans = Integer.parseInt(config.get("hudTransparency", "0"));
            this.setAlpha(1.0f - (trans / 100.0f));

            int scaleInt = Integer.parseInt(config.get("hudScale", "100"));
            float scaleFactor = Math.max(50, Math.min(150, scaleInt)) / 100.0f;
            this.setScaleX(scaleFactor);
            this.setScaleY(scaleFactor);
        } catch (Exception ignored) {}
    }

    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();

        if (time >= lastTime + 500) {
            lastFPS = ((float) (frameCount * 1000) / (time - lastTime));
            cpuTemp = getCPUTemperature();
            gpuLoad = calculateGPULoad();

            Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                batteryTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                long microAmps = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                batteryWattage = (microAmps < 0) ? (Math.abs(microAmps) * voltageMv) / 1000000000.0f : 0.0f;
            }

            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        if (tvFPS != null) tvFPS.setText(String.format(Locale.ENGLISH, "%.1f", lastFPS));
        if (tvCPUTemp != null) tvCPUTemp.setText(String.format(Locale.ENGLISH, "%.1f°C", cpuTemp));
        if (tvGPULoad != null) tvGPULoad.setText(gpuLoad + "%");
        if (tvRAM != null) tvRAM.setText(getUsedRAM() + " / " + totalRAM);
        if (tvBatteryTemp != null) tvBatteryTemp.setText(String.format(Locale.ENGLISH, "%.1f°C", batteryTemp));
        if (tvBatteryVoltage != null) tvBatteryVoltage.setText(String.format(Locale.ENGLISH, "%.2fW", batteryWattage));
    }

    private String getUsedRAM() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return StringUtils.formatBytes(mi.totalMem - mi.availMem, false);
    }

    private float getCPUTemperature() {
        String[] paths = {"/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp", "/sys/devices/virtual/thermal/thermal_zone0/temp"};
        for (String path : paths) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line = reader.readLine();
                if (line != null) {
                    float temp = Float.parseFloat(line.trim());
                    return temp > 1000 ? temp / 1000.0f : temp;
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private int calculateGPULoad() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                long busy = Long.parseLong(parts[0]);
                long total = Long.parseLong(parts[1]);
                return total != 0 ? (int) ((busy * 100) / total) : 0;
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
