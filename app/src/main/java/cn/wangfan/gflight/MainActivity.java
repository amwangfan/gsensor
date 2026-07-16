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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int OPEN_CSV = 41;

    private TextView statusText;
    private TextView totalGText;
    private TextView verticalGText;
    private TextView rowText;
    private TextView coordinateText;
    private Button recordButton;
    private Switch gnssSwitch;
    private Switch reliableSwitch;
    private LinearLayout fileList;
    private FlightVectorView vectorView;
    private boolean pendingStart;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String error = intent.getStringExtra(RecordingService.EXTRA_ERROR);
            if (error != null) Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            String mode = intent.getStringExtra(RecordingService.EXTRA_MODE);
            if ("STOPPED".equals(mode)) {
                renderStopped();
                refreshFiles();
                return;
            }
            float total = intent.getFloatExtra(RecordingService.EXTRA_TOTAL_G, Float.NaN);
            float vertical = intent.getFloatExtra(RecordingService.EXTRA_VERTICAL_G, Float.NaN);
            float east = intent.getFloatExtra(RecordingService.EXTRA_EAST, Float.NaN);
            float north = intent.getFloatExtra(RecordingService.EXTRA_NORTH, Float.NaN);
            float up = intent.getFloatExtra(RecordingService.EXTRA_UP, Float.NaN);
            int rows = intent.getIntExtra(RecordingService.EXTRA_ROWS, 0);
            renderRecording(mode, total, vertical, east, north, up, rows);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RecordingService.isRunning()) CsvStore.recoverPendingRecords(this);
        buildUi();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RecordingService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        if (RecordingService.isRunning()) renderRecording("NORMAL", Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN, 0);
        else renderStopped();
        refreshFiles();
    }

    @Override protected void onStop() {
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

        vectorView = new FlightVectorView(this);
        currentCard.addView(vectorView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        coordinateText = text("东/北/天：等待记录", 13, 0xFF5A6763, false);
        coordinateText.setGravity(Gravity.CENTER);
        currentCard.addView(coordinateText, matchWrap());
        rowText = text("0 条数据", 13, 0xFF5A6763, false);
        currentCard.addView(rowText, margins(matchWrap(), 0, 8, 0, 6));

        gnssSwitch = new Switch(this);
        gnssSwitch.setText("GNSS 辅助（速度、海拔与精度）");
        gnssSwitch.setTextSize(14);
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
        TextView note = text("文件保存至 Download/g。稳定 8 秒后降低写入频率，检测到变化时自动提升至最高 10 条/秒。", 12, 0xFF5A6763, false);
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
        }
    }

    private void startRecordingNow() {
        Intent start = new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_START);
        start.putExtra(RecordingService.EXTRA_GNSS, gnssSwitch.isChecked());
        start.putExtra(RecordingService.EXTRA_RELIABLE, reliableSwitch.isChecked());
        startForegroundService(start);
        renderRecording("NORMAL", Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0);
    }

    private void renderStopped() {
        statusText.setText("未在记录");
        statusText.setTextColor(0xFF5A6763);
        totalGText.setText("—\n合加速度");
        verticalGText.setText("—\n天地轴 G");
        coordinateText.setText("东/北/天：等待记录");
        vectorView.setVector(Float.NaN, Float.NaN, Float.NaN);
        recordButton.setText("开始记录");
        recordButton.setEnabled(true);
        gnssSwitch.setEnabled(true);
        reliableSwitch.setEnabled(true);
    }

    private void renderRecording(String mode, float total, float vertical,
                                 float east, float north, float up, int rows) {
        String label = switch (mode == null ? "NORMAL" : mode) {
            case "ACTIVE" -> "高精度记录中";
            case "STABLE" -> "低功耗记录中";
            default -> "正常记录中";
        };
        statusText.setText("● " + label);
        statusText.setTextColor(0xFF0B6B5D);
        totalGText.setText((Float.isFinite(total) ? String.format(Locale.getDefault(), "%.3f g", total) : "—") + "\n合加速度");
        verticalGText.setText((Float.isFinite(vertical) ? String.format(Locale.getDefault(), "%.3f g", vertical) : "—") + "\n天地轴 G");
        if (Float.isFinite(east)) {
            coordinateText.setText(String.format(Locale.getDefault(), "东 %.2f  北 %.2f  天 %.2f m/s²", east, north, up));
        }
        vectorView.setVector(east, north, up);
        rowText.setText(String.format(Locale.getDefault(), "%,d 条数据", rows));
        recordButton.setText("停止并保存");
        recordButton.setEnabled(true);
        gnssSwitch.setEnabled(false);
        reliableSwitch.setEnabled(false);
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
