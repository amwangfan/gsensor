package cn.wangfan.gflight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class FlightChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint totalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint verticalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Float> total = Collections.emptyList();
    private List<Float> vertical = Collections.emptyList();
    private float yMin = 0.7f, yMax = 1.3f;

    public FlightChartView(Context context) { this(context, null); }
    public FlightChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        grid.setColor(0xFFD6DEDB);
        grid.setStrokeWidth(dp(1));
        totalPaint.setColor(0xFF0B6B5D);
        totalPaint.setStrokeWidth(dp(2));
        totalPaint.setStyle(Paint.Style.STROKE);
        verticalPaint.setColor(0xFFE07A3F);
        verticalPaint.setStrokeWidth(dp(1.5f));
        verticalPaint.setStyle(Paint.Style.STROKE);
        text.setColor(0xFF5A6763);
        text.setTextSize(dp(11));
    }

    public void setData(List<Float> total, List<Float> vertical) {
        this.total = total == null ? Collections.emptyList() : total;
        this.vertical = vertical == null ? Collections.emptyList() : vertical;
        yMin = 0.7f;
        yMax = 1.3f;
        for (Float value : this.total) include(value);
        for (Float value : this.vertical) include(value);
        float padding = Math.max(0.05f, (yMax - yMin) * 0.08f);
        yMin -= padding;
        yMax += padding;
        invalidate();
    }

    private void include(Float value) {
        if (value == null || !Float.isFinite(value)) return;
        yMin = Math.min(yMin, value);
        yMax = Math.max(yMax, value);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(42), right = getWidth() - dp(12);
        float top = dp(22), bottom = getHeight() - dp(30);
        if (right <= left || bottom <= top) return;
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
            float value = yMax - (yMax - yMin) * i / 4f;
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", value), left - dp(5), y + dp(4), text);
        }
        drawSeries(canvas, total, totalPaint, left, top, right, bottom);
        drawSeries(canvas, vertical, verticalPaint, left, top, right, bottom);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(totalPaint.getColor());
        canvas.drawText("合加速度", left, dp(14), text);
        text.setColor(verticalPaint.getColor());
        canvas.drawText("天地轴", left + dp(64), dp(14), text);
        text.setColor(0xFF5A6763);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("记录时间 →", (left + right) / 2, getHeight() - dp(6), text);
    }

    private void drawSeries(Canvas canvas, List<Float> values, Paint paint,
                            float left, float top, float right, float bottom) {
        if (values.size() < 2) return;
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < values.size(); i++) {
            float value = values.get(i);
            if (!Float.isFinite(value)) { started = false; continue; }
            float x = left + (right - left) * i / (values.size() - 1f);
            float y = bottom - (value - yMin) / (yMax - yMin) * (bottom - top);
            if (!started) { path.moveTo(x, y); started = true; }
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
