package cn.wangfan.gflight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public final class FlightVectorView extends View {
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vector = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float east = Float.NaN, north = Float.NaN, up = Float.NaN;

    public FlightVectorView(Context context) { this(context, null); }
    public FlightVectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        axis.setColor(0xFF9BA8A4);
        axis.setStrokeWidth(dp(1.5f));
        vector.setColor(0xFF0B6B5D);
        vector.setStrokeWidth(dp(4));
        vector.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFF5A6763);
        text.setTextSize(dp(12));
    }

    public void setVector(float east, float north, float up) {
        this.east = east;
        this.north = north;
        this.up = up;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * 0.50f;
        float cy = getHeight() * 0.60f;
        float s = Math.min(getWidth(), getHeight()) * 0.30f;
        drawAxis(canvas, cx, cy, cx + s, cy + s * 0.42f, "东 E");
        drawAxis(canvas, cx, cy, cx - s, cy + s * 0.42f, "北 N");
        drawAxis(canvas, cx, cy, cx, cy - s, "天 U");
        if (!Float.isFinite(east) || !Float.isFinite(north) || !Float.isFinite(up)) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("等待姿态传感器", cx, getHeight() - dp(8), text);
            return;
        }
        float scale = s / (float) (SensorScale.G * 1.5);
        float vx = cx + east * scale - north * scale;
        float vy = cy + (east + north) * scale * 0.42f - up * scale;
        canvas.drawLine(cx, cy, vx, vy, vector);
        canvas.drawCircle(vx, vy, dp(5), vector);
    }

    private void drawAxis(Canvas c, float x1, float y1, float x2, float y2, String label) {
        c.drawLine(x1, y1, x2, y2, axis);
        text.setTextAlign(x2 < x1 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(label, x2, y2, text);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private static final class SensorScale {
        static final double G = 9.80665;
    }
}
