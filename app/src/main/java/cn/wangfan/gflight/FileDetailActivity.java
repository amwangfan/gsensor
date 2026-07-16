package cn.wangfan.gflight;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FileDetailActivity extends Activity {
    private TextView summary;
    private TextView sourceInfo;
    private FlightChartView chart;
    private Uri uri;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = getIntent().getData();
        buildUi(getIntent().getStringExtra("name"));
        if (uri == null) {
            summary.setText("没有可读取的文件地址");
            return;
        }
        new Thread(this::loadFile, "gflight-csv-reader").start();
    }

    private void buildUi(String name) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F7F6);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setAllCaps(false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(dp(100), dp(46)));

        TextView title = text(name == null ? "飞行记录" : name, 23, 0xFF18201E, true);
        root.addView(title, margins(matchWrap(), 0, 12, 0, 12));

        LinearLayout card = card();
        root.addView(card, margins(matchWrap(), 0, 0, 0, 14));
        summary = text("正在读取…", 16, 0xFF18201E, false);
        summary.setLineSpacing(dp(4), 1);
        card.addView(summary);
        sourceInfo = text("", 12, 0xFF5A6763, false);
        card.addView(sourceInfo, margins(matchWrap(), 0, 10, 0, 0));

        LinearLayout chartCard = card();
        root.addView(chartCard, margins(matchWrap(), 0, 0, 0, 14));
        chartCard.addView(text("G 值曲线", 19, 0xFF18201E, true));
        chart = new FlightChartView(this);
        chartCard.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)));

        TextView caution = text("说明：手机测得的是设备所受比力。天地轴依赖手机姿态传感器；若手机在机舱内移动、旋转或受到桌面振动，结果并不等同于经校准的飞机结构过载。GNSS 与气压仅作辅助参照。", 13, 0xFF5A6763, false);
        root.addView(caution, margins(matchWrap(), 2, 0, 2, 14));

        Button share = new Button(this);
        share.setText("分享 CSV 文件");
        share.setAllCaps(false);
        share.setOnClickListener(v -> shareFile());
        root.addView(share, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        setContentView(scroll);
    }

    private void loadFile() {
        try {
            Parsed parsed = parse(uri);
            runOnUiThread(() -> showParsed(parsed));
        } catch (Exception e) {
            runOnUiThread(() -> summary.setText("读取失败：" + (e.getMessage() == null ? "文件格式不受支持" : e.getMessage())));
        }
    }

    private Parsed parse(Uri uri) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("无法打开文件");
        Parsed p = new Parsed();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024)) {
            String line;
            int totalIndex = -1, verticalIndex = -1, elapsedIndex = -1;
            int pressureIndex = -1, gpsIndex = -1;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    if (p.metadata.length() < 800) p.metadata.append(line.substring(1).trim()).append('\n');
                    continue;
                }
                if (line.trim().isEmpty()) continue;
                String[] cells = line.split(",", -1);
                if (totalIndex < 0) {
                    totalIndex = indexOf(cells, "total_g");
                    verticalIndex = indexOf(cells, "vertical_g");
                    elapsedIndex = indexOf(cells, "elapsed_ms");
                    pressureIndex = indexOf(cells, "pressure_hpa");
                    gpsIndex = indexOf(cells, "gps_altitude_m");
                    if (totalIndex < 0) throw new IOException("不是受支持的 GFlight CSV");
                    continue;
                }
                float total = parseFloat(cells, totalIndex);
                float vertical = parseFloat(cells, verticalIndex);
                long elapsed = parseLong(cells, elapsedIndex);
                if (!Float.isFinite(total)) continue;
                p.rows++;
                p.durationMs = Math.max(p.durationMs, elapsed);
                p.minG = Math.min(p.minG, total);
                p.maxG = Math.max(p.maxG, total);
                p.sumG += total;
                if (Float.isFinite(vertical)) {
                    p.minVertical = Math.min(p.minVertical, vertical);
                    p.maxVertical = Math.max(p.maxVertical, vertical);
                }
                if (Float.isFinite(parseFloat(cells, pressureIndex))) p.pressureRows++;
                if (Float.isFinite(parseFloat(cells, gpsIndex))) p.gpsRows++;
                p.chart.add(total, vertical, p.rows);
            }
        }
        if (p.rows == 0) throw new IOException("文件中没有有效读数");
        return p;
    }

    private void showParsed(Parsed p) {
        String verticalRange = Float.isFinite(p.minVertical)
                ? String.format(Locale.getDefault(), "%.3f ～ %.3f g", p.minVertical, p.maxVertical)
                : "无姿态数据";
        summary.setText(String.format(Locale.getDefault(),
                "时长　%s\n读数　%,d 条\n合加速度　%.3f ～ %.3f g\n平均值　%.3f g\n天地轴　%s",
                duration(p.durationMs), p.rows, p.minG, p.maxG, p.sumG / p.rows, verticalRange));
        sourceInfo.setText(String.format(Locale.getDefault(), "GNSS 有效行：%,d　气压有效行：%,d\n%s",
                p.gpsRows, p.pressureRows, p.metadata.toString().trim()));
        chart.setData(p.chart.total, p.chart.vertical);
    }

    private void shareFile() {
        if (uri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(share, "分享飞行记录")); }
        catch (RuntimeException e) { Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show(); }
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) if (name.equals(header[i].trim())) return i;
        return -1;
    }

    private static float parseFloat(String[] cells, int index) {
        if (index < 0 || index >= cells.length) return Float.NaN;
        try { return Float.parseFloat(cells[index]); }
        catch (NumberFormatException e) { return Float.NaN; }
    }

    private static long parseLong(String[] cells, int index) {
        if (index < 0 || index >= cells.length) return 0;
        try { return Long.parseLong(cells[index]); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String duration(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        layout.setBackground(background);
        layout.setElevation(dp(2));
        return layout;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class Parsed {
        long rows;
        long durationMs;
        float minG = Float.POSITIVE_INFINITY;
        float maxG = Float.NEGATIVE_INFINITY;
        double sumG;
        float minVertical = Float.POSITIVE_INFINITY;
        float maxVertical = Float.NEGATIVE_INFINITY;
        long pressureRows;
        long gpsRows;
        final StringBuilder metadata = new StringBuilder();
        final CompactSeries chart = new CompactSeries();
    }

    private static final class CompactSeries {
        private static final int LIMIT = 5_000;
        final List<Float> total = new ArrayList<>();
        final List<Float> vertical = new ArrayList<>();
        private int stride = 1;

        void add(float totalG, float verticalG, long row) {
            if (row % stride != 0) return;
            total.add(totalG);
            vertical.add(verticalG);
            if (total.size() > LIMIT) {
                compact(total);
                compact(vertical);
                stride *= 2;
            }
        }

        private static void compact(List<Float> values) {
            int write = 0;
            for (int read = 0; read < values.size(); read += 2) values.set(write++, values.get(read));
            while (values.size() > write) values.remove(values.size() - 1);
        }
    }
}
