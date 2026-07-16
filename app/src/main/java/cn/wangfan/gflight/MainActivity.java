package cn.wangfan.gflight;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_PREVIEW_LOCATION = 42;
    private static final int OPEN_CSV = 41;

    private TextView statusText;
    private TextView totalGText;
    private TextView verticalGText;
    private TextView deviceAccelText;
    private TextView pressureText;
    private TextView gpsText;
    private TextView rowText;
    private TextView coordinateText;
    private Button recordButton;
    private Switch gnssSwitch;
    private Switch reliableSwitch;
    private LinearLayout fileList;
    private FlightVectorView vectorView;
    private boolean pendingStart;
    private SensorManager previewSensorManager;
    private LocationManager previewLocationManager;
    private Sensor previewAccelerometer;
    private Sensor previewRotationSensor;
    private Sensor previewPressureSensor;
    private final float[] previewRotationMatrix = new float[9];
    private boolean hasPreviewRotation;
    private float previewBaselinePressure = Float.NaN;
    private boolean previewLocationActive;
    private long lastPreviewAccelerationUiMs;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String error = intent.getStringExtra(RecordingService.EXTRA_ERROR);
            if (error != null) Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            String mode = intent.getStringExtra(RecordingService.EXTRA_MODE);
            if (error != null && mode == null) return;
            if ("STOPPED".equals(mode)) {
                renderStopped();
                startPreview();
                refreshFiles();
                return;
            }
            stopPreview();
            float total = intent.getFloatExtra(RecordingService.EXTRA_TOTAL_G, Float.NaN);
            float vertical = intent.getFloatExtra(RecordingService.EXTRA_VERTICAL_G, Float.NaN);
            float deviceX = intent.getFloatExtra(RecordingService.EXTRA_DEVICE_X, Float.NaN);
            float deviceY = intent.getFloatExtra(RecordingService.EXTRA_DEVICE_Y, Float.NaN);
            float deviceZ = intent.getFloatExtra(RecordingService.EXTRA_DEVICE_Z, Float.NaN);
            float east = intent.getFloatExtra(RecordingService.EXTRA_EAST, Float.NaN);
            float north = intent.getFloatExtra(RecordingService.EXTRA_NORTH, Float.NaN);
            float up = intent.getFloatExtra(RecordingService.EXTRA_UP, Float.NaN);
            float pressure = intent.getFloatExtra(RecordingService.EXTRA_PRESSURE_HPA, Float.NaN);
            float baroAltitude = intent.getFloatExtra(RecordingService.EXTRA_BARO_ALTITUDE, Float.NaN);
            double latitude = intent.getDoubleExtra(RecordingService.EXTRA_LATITUDE, Double.NaN);
            double longitude = intent.getDoubleExtra(RecordingService.EXTRA_LONGITUDE, Double.NaN);
            double gpsAltitude = intent.getDoubleExtra(RecordingService.EXTRA_GPS_ALTITUDE, Double.NaN);
            float gpsSpeed = intent.getFloatExtra(RecordingService.EXTRA_GPS_SPEED, Float.NaN);
            float gpsAccuracy = intent.getFloatExtra(RecordingService.EXTRA_GPS_ACCURACY, Float.NaN);
            int rows = intent.getIntExtra(RecordingService.EXTRA_ROWS, 0);
            renderRecording(mode, rows);
            renderAcceleration(total, vertical, deviceX, deviceY, deviceZ, east, north, up);
            renderPressure(pressure, baroAltitude);
            renderLocation(latitude, longitude, gpsAltitude, gpsSpeed, gpsAccuracy);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RecordingService.isRunning()) CsvStore.recoverPendingRecords(this);
        previewSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        previewLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        buildUi();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RecordingService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        if (RecordingService.isRunning()) renderRecording("NORMAL", 0);
        else {
            renderStopped();
            startPreview();
        }
        refreshFiles();
    }

    @Override protected void onStop() {
        stopPreview();
        unregisterReceiver(receiver);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF5F7F6);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(22), dp(18), dp(36));
        scroll.addView(root, matchWrap());

        TextView title = text("G 航迹", 30, 0xFF18201E, true);
        root.addView(title);
        TextView subtitle = text("低耗电、自适应的飞行过载记录器", 14, 0xFF5A6763, false);
        root.addView(subtitle, margins(matchWrap(), 0, 2, 0, 20));

        LinearLayout currentCard = card();
        root.addView(currentCard, margins(matchWrap(), 0, 0, 0, 16));
        currentCard.addView(sectionTitle("当前记录"));
        statusText = text("未在记录", 14, 0xFF5A6763, true);
        currentCard.addView(statusText, margins(matchWrap(), 0, 4, 0, 14));

        LinearLayout values = new LinearLayout(this);
        values.setOrientation(LinearLayout.HORIZONTAL);
        totalGText = metric("—", "合加速度");
        verticalGText = metric("—", "天地轴 G");
        values.addView(totalGText, new LinearLayout.LayoutParams(0, dp(78), 1));
        values.addView(verticalGText, new LinearLayout.LayoutParams(0, dp(78), 1));
        currentCard.addView(values, matchWrap());

        deviceAccelText = text("设备轴加速度：等待传感器", 13, 0xFF33423E, false);
        currentCard.addView(deviceAccelText, margins(matchWrap(), 0, 6, 0, 4));
        pressureText = text("气压：等待传感器", 13, 0xFF33423E, false);
        currentCard.addView(pressureText, margins(matchWrap(), 0, 4, 0, 4));
        gpsText = text("GPS：正在检查权限和卫星信号…", 13, 0xFF33423E, false);
        gpsText.setLineSpacing(dp(2), 1f);
        currentCard.addView(gpsText, margins(matchWrap(), 0, 4, 0, 8));

        vectorView = new FlightVectorView(this);
        currentCard.addView(vectorView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        coordinateText = text("东/北/天：等待姿态传感器", 13, 0xFF5A6763, false);
        coordinateText.setGravity(Gravity.CENTER);
        currentCard.addView(coordinateText, matchWrap());
        rowText = text("0 条数据", 13, 0xFF5A6763, false);
        currentCard.addView(rowText, margins(matchWrap(), 0, 8, 0, 6));

        gnssSwitch = new Switch(this);
        gnssSwitch.setText("GNSS 辅助（速度、海拔与精度）");
        gnssSwitch.setTextSize(14);
        gnssSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !RecordingService.isRunning()
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PREVIEW_LOCATION);
            } else if (!RecordingService.isRunning()) {
                startPreviewLocation();
            }
        });
        currentCard.addView(gnssSwitch, matchWrap());
        reliableSwitch = new Switch(this);
        reliableSwitch.setText("息屏可靠模式（无唤醒传感器时会增加耗电）");
        reliableSwitch.setTextSize(14);
        reliableSwitch.setChecked(true);
        currentCard.addView(reliableSwitch, margins(matchWrap(), 0, 2, 0, 10));

        recordButton = new Button(this);
        recordButton.setText("开始记录");
        recordButton.setTextSize(16);
        recordButton.setAllCaps(false);
        recordButton.setOnClickListener(v -> toggleRecording());
        currentCard.addView(recordButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView note = text("首页打开时以低频实时预览，未记录时不会写入文件。文件保存至 Download/g；在约 ±0.06 g 容差内稳定 8 秒后降低写入频率。", 12, 0xFF5A6763, false);
        currentCard.addView(note, margins(matchWrap(), 0, 10, 0, 0));

        LinearLayout savedCard = card();
        root.addView(savedCard, matchWrap());
        LinearLayout savedHeader = new LinearLayout(this);
        savedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView savedTitle = sectionTitle("已保存记录");
        savedHeader.addView(savedTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = smallButton("刷新");
        refresh.setOnClickListener(v -> refreshFiles());
        savedHeader.addView(refresh);
        savedCard.addView(savedHeader, matchWrap());
        TextView folder = text("默认目录：Download/g", 12, 0xFF5A6763, false);
        savedCard.addView(folder, margins(matchWrap(), 0, 2, 0, 8));
        fileList = vertical();
        savedCard.addView(fileList, matchWrap());
        Button openOther = new Button(this);
        openOther.setText("打开其他 CSV 文件");
        openOther.setAllCaps(false);
        openOther.setOnClickListener(v -> openDocumentPicker());
        savedCard.addView(openOther, margins(matchWrap(), 0, 10, 0, 0));
        setContentView(scroll);
    }

    private void toggleRecording() {
        if (RecordingService.isRunning()) {
            Intent stop = new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_STOP);
            startService(stop);
            recordButton.setEnabled(false);
            statusText.setText("正在保存…");
            return;
        }
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (gnssSwitch.isChecked()
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            pendingStart = true;
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            startRecordingNow();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS && pendingStart) {
            pendingStart = false;
            if (gnssSwitch.isChecked()
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                gnssSwitch.setChecked(false);
                Toast.makeText(this, "未获得定位权限，将仅记录传感器数据", Toast.LENGTH_LONG).show();
            }
            startRecordingNow();
        } else if (requestCode == REQUEST_PREVIEW_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                gnssSwitch.setChecked(false);
                gpsText.setText("GPS：未授权定位权限");
            } else {
                startPreviewLocation();
            }
        }
    }

    private void startRecordingNow() {
        stopPreview();
        Intent start = new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_START);
        start.putExtra(RecordingService.EXTRA_GNSS, gnssSwitch.isChecked());
        start.putExtra(RecordingService.EXTRA_RELIABLE, reliableSwitch.isChecked());
        startForegroundService(start);
        renderRecording("NORMAL", 0);
    }

    private void renderStopped() {
        statusText.setText("未在记录 · 实时预览");
        statusText.setTextColor(0xFF5A6763);
        totalGText.setText("—\n合加速度");
        verticalGText.setText("—\n天地轴 G");
        deviceAccelText.setText("设备轴加速度：等待传感器");
        pressureText.setText("气压：等待传感器");
        gpsText.setText("GPS：正在检查权限和卫星信号…");
        coordinateText.setText("东/北/天：等待姿态传感器");
        vectorView.setVector(Float.NaN, Float.NaN, Float.NaN);
        rowText.setText("实时预览（不写入文件）");
        recordButton.setText("开始记录");
        recordButton.setEnabled(true);
        gnssSwitch.setEnabled(true);
        reliableSwitch.setEnabled(true);
    }

    private void renderRecording(String mode, int rows) {
        String label = switch (mode == null ? "NORMAL" : mode) {
            case "ACTIVE" -> "高精度记录中";
            case "STABLE" -> "低功耗记录中";
            default -> "正常记录中";
        };
        statusText.setText("● " + label);
        statusText.setTextColor(0xFF0B6B5D);
        rowText.setText(String.format(Locale.getDefault(), "%,d 条数据", rows));
        recordButton.setText("停止并保存");
        recordButton.setEnabled(true);
        gnssSwitch.setEnabled(false);
        reliableSwitch.setEnabled(false);
    }

    private void renderAcceleration(float total, float vertical, float deviceX, float deviceY,
                                    float deviceZ, float east, float north, float up) {
        totalGText.setText((Float.isFinite(total)
                ? String.format(Locale.getDefault(), "%.3f g", total) : "—") + "\n合加速度");
        verticalGText.setText((Float.isFinite(vertical)
                ? String.format(Locale.getDefault(), "%.3f g", vertical) : "—") + "\n天地轴 G");
        if (Float.isFinite(deviceX) && Float.isFinite(deviceY) && Float.isFinite(deviceZ)) {
            deviceAccelText.setText(String.format(Locale.getDefault(),
                    "设备轴加速度：X %.3f  Y %.3f  Z %.3f m/s²", deviceX, deviceY, deviceZ));
        } else {
            deviceAccelText.setText("设备轴加速度：等待传感器");
        }
        if (Float.isFinite(east) && Float.isFinite(north) && Float.isFinite(up)) {
            coordinateText.setText(String.format(Locale.getDefault(),
                    "东 %.2f  北 %.2f  天 %.2f m/s²", east, north, up));
        } else {
            coordinateText.setText("东/北/天：设备无姿态数据");
        }
        vectorView.setVector(east, north, up);
    }

    private void renderPressure(float pressureHpa, float relativeAltitudeM) {
        if (!Float.isFinite(pressureHpa)) {
            pressureText.setText("气压：暂无数据（设备可能没有气压传感器）");
            return;
        }
        String altitude = Float.isFinite(relativeAltitudeM)
                ? String.format(Locale.getDefault(), " · 相对 %.1f m", relativeAltitudeM) : "";
        pressureText.setText(String.format(Locale.getDefault(), "气压：%.2f hPa%s",
                pressureHpa, altitude));
    }

    private void renderLocation(double latitude, double longitude, double altitudeM,
                                float speedMps, float accuracyM) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            gpsText.setText("GPS：等待定位（未启用、未授权或暂无卫星信号）");
            return;
        }
        String altitude = Double.isFinite(altitudeM)
                ? String.format(Locale.getDefault(), " · 海拔 %.1f m", altitudeM) : "";
        String speed = Float.isFinite(speedMps)
                ? String.format(Locale.getDefault(), " · %.1f m/s", speedMps) : "";
        String accuracy = Float.isFinite(accuracyM)
                ? String.format(Locale.getDefault(), " · 精度 ±%.1f m", accuracyM) : "";
        gpsText.setText(String.format(Locale.getDefault(), "GPS：%.6f, %.6f%s%s%s",
                latitude, longitude, altitude, speed, accuracy));
    }

    private void startPreview() {
        if (RecordingService.isRunning() || previewSensorManager == null) return;
        previewAccelerometer = previewSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        previewRotationSensor = previewSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        previewPressureSensor = previewSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (previewAccelerometer != null) {
            previewSensorManager.registerListener(this, previewAccelerometer,
                    200_000, 1_000_000);
        } else {
            deviceAccelText.setText("设备轴加速度：设备没有加速度计");
        }
        if (previewRotationSensor != null) {
            previewSensorManager.registerListener(this, previewRotationSensor,
                    200_000, 1_000_000);
        } else {
            coordinateText.setText("东/北/天：设备没有姿态传感器");
        }
        if (previewPressureSensor != null) {
            previewSensorManager.registerListener(this, previewPressureSensor,
                    1_000_000, 3_000_000);
        } else {
            pressureText.setText("气压：设备没有气压传感器");
        }
        startPreviewLocation();
    }

    @SuppressLint("MissingPermission")
    private void startPreviewLocation() {
        if (RecordingService.isRunning() || previewLocationManager == null) return;
        if (previewLocationActive) {
            try { previewLocationManager.removeUpdates(this); }
            catch (SecurityException ignored) { }
            previewLocationActive = false;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            gpsText.setText("GPS：未授权定位权限（打开 GNSS 辅助可授权）");
            return;
        }
        try {
            if (!previewLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsText.setText("GPS：系统定位未开启");
                return;
            }
            Location last = previewLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
            else gpsText.setText("GPS：正在搜索卫星…");
            previewLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    5_000L, 0f, this, Looper.getMainLooper());
            previewLocationActive = true;
        } catch (SecurityException | IllegalArgumentException e) {
            gpsText.setText("GPS：无法启动定位");
        }
    }

    private void stopPreview() {
        if (previewSensorManager != null) previewSensorManager.unregisterListener(this);
        if (previewLocationManager != null && previewLocationActive) {
            try { previewLocationManager.removeUpdates(this); }
            catch (SecurityException ignored) { }
        }
        previewLocationActive = false;
        hasPreviewRotation = false;
        previewBaselinePressure = Float.NaN;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (RecordingService.isRunning()) return;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(previewRotationMatrix, event.values);
                hasPreviewRotation = true;
            }
            case Sensor.TYPE_PRESSURE -> {
                float pressure = event.values[0];
                if (!Float.isFinite(previewBaselinePressure)) previewBaselinePressure = pressure;
                renderPressure(pressure, SensorManager.getAltitude(previewBaselinePressure, pressure));
            }
            case Sensor.TYPE_ACCELEROMETER -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastPreviewAccelerationUiMs < 250) return;
                lastPreviewAccelerationUiMs = now;
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                float total = (float) (Math.sqrt(x * x + y * y + z * z)
                        / SensorManager.GRAVITY_EARTH);
                float[] world = hasPreviewRotation
                        ? CoordinateMapper.deviceToWorld(previewRotationMatrix, x, y, z)
                        : new float[]{Float.NaN, Float.NaN, Float.NaN};
                float vertical = Float.isFinite(world[2])
                        ? world[2] / SensorManager.GRAVITY_EARTH : Float.NaN;
                renderAcceleration(total, vertical, x, y, z, world[0], world[1], world[2]);
            }
            default -> { }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onLocationChanged(Location location) {
        if (RecordingService.isRunning()) return;
        renderLocation(location.getLatitude(), location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN,
                location.hasSpeed() ? location.getSpeed() : Float.NaN,
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN);
    }

    @Override public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) startPreviewLocation();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) gpsText.setText("GPS：系统定位未开启");
    }

    private void refreshFiles() {
        fileList.removeAllViews();
        List<CsvStore.FileEntry> entries = CsvStore.listOwnRecords(this);
        if (entries.isEmpty()) {
            fileList.addView(text("暂无本应用创建的记录", 14, 0xFF5A6763, false), margins(matchWrap(), 0, 10, 0, 8));
            return;
        }
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (CsvStore.FileEntry entry : entries) {
            LinearLayout row = vertical();
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            row.setBackground(rounded(0xFFEAF0EE, 10));
            row.addView(text(entry.name, 14, 0xFF18201E, true));
            String info = format.format(new Date(entry.modifiedSeconds * 1000)) + " · " + humanBytes(entry.size);
            row.addView(text(info, 12, 0xFF5A6763, false));
            row.setOnClickListener(v -> openDetail(entry.uri, entry.name));
            fileList.addView(row, margins(matchWrap(), 0, 4, 0, 6));
        }
    }

    private void openDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, OPEN_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_CSV && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            openDetail(uri, "所选记录");
        }
    }

    private void openDetail(Uri uri, String name) {
        Intent intent = new Intent(this, FileDetailActivity.class);
        intent.setData(uri);
        intent.putExtra("name", name);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(Color.WHITE, 16));
        layout.setElevation(dp(2));
        return layout;
    }

    private TextView sectionTitle(String value) { return text(value, 20, 0xFF18201E, true); }

    private TextView metric(String value, String label) {
        TextView view = text(value + "\n" + label, 21, 0xFF18201E, true);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(2), 1);
        return view;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
    }
}
