package cn.wangfan.gflight;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Locale;

public final class RecordingService extends Service implements SensorEventListener, LocationListener {
    public static final String ACTION_START = "cn.wangfan.gflight.START";
    public static final String ACTION_STOP = "cn.wangfan.gflight.STOP";
    public static final String ACTION_UPDATE = "cn.wangfan.gflight.UPDATE";
    public static final String EXTRA_GNSS = "gnss";
    public static final String EXTRA_RELIABLE = "reliable";
    public static final String EXTRA_TOTAL_G = "total_g";
    public static final String EXTRA_VERTICAL_G = "vertical_g";
    public static final String EXTRA_EAST = "east";
    public static final String EXTRA_NORTH = "north";
    public static final String EXTRA_UP = "up";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ROWS = "rows";
    public static final String EXTRA_ERROR = "error";

    private static final int NOTIFICATION_ID = 73;
    private static final String CHANNEL_ID = "gflight_recording";
    private static volatile boolean running;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor rotationSensor;
    private Sensor pressureSensor;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private AdaptiveSampler sampler;
    private CsvStore store;
    private PowerManager.WakeLock wakeLock;

    private final float[] rotationMatrix = new float[9];
    private boolean hasRotationMatrix;
    private volatile float pressureHpa = Float.NaN;
    private volatile float relativeBaroAltitudeM = Float.NaN;
    private float baselinePressureHpa = Float.NaN;
    private volatile double latitude = Double.NaN;
    private volatile double longitude = Double.NaN;
    private volatile double gpsAltitudeM = Double.NaN;
    private volatile float gpsSpeedMps = Float.NaN;
    private volatile float gpsAccuracyM = Float.NaN;
    private volatile float gpsVerticalAccuracyM = Float.NaN;
    private volatile float gpsAccelMps2 = Float.NaN;
    private float previousGpsSpeed = Float.NaN;
    private long previousGpsTimeMs;

    private long firstSensorTimestampNs;
    private long firstEpochMs;
    private long lastUiBroadcastMs;
    private long lastNotificationMs;
    private int sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private AdaptiveSampler.Mode registeredMode;
    private boolean gnssEnabled;
    private volatile boolean stopping;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sampler = new AdaptiveSampler();
        sensorThread = new HandlerThread("gflight-sensors");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        gnssEnabled = intent != null && intent.getBooleanExtra(EXTRA_GNSS, false)
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean reliable = intent != null && intent.getBooleanExtra(EXTRA_RELIABLE, true);
        startAsForeground(gnssEnabled, "正在准备传感器…");

        Sensor wakeAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
        Sensor normalAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false);
        accelerometer = reliable && wakeAccelerometer != null ? wakeAccelerometer : normalAccelerometer;
        if (accelerometer == null) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        if (accelerometer == null) {
            broadcastError("设备没有可用的加速度计");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (reliable && !accelerometer.isWakeUpSensor()) acquireWakeLock();
        try {
            store = new CsvStore(this, accelerometer.getName(), rotationSensor != null,
                    pressureSensor != null, gnssEnabled);
        } catch (IOException | RuntimeException e) {
            broadcastError("无法创建记录文件：" + safeMessage(e));
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("recording", true).apply();
        registerMotionSensors(AdaptiveSampler.Mode.NORMAL);
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, 1_000_000, 5_000_000, sensorHandler);
        }
        if (gnssEnabled) startLocation();
        updateNotification("正常采样 · 等待首个读数");
        return START_NOT_STICKY;
    }

    private void registerMotionSensors(AdaptiveSampler.Mode mode) {
        if (stopping || accelerometer == null || sensorManager == null) return;
        sensorManager.unregisterListener(this, accelerometer);
        boolean ok = sensorManager.registerListener(this, accelerometer,
                AdaptiveSampler.samplingPeriodUs(mode), AdaptiveSampler.batchingLatencyUs(mode), sensorHandler);
        if (rotationSensor != null) {
            sensorManager.unregisterListener(this, rotationSensor);
            sensorManager.registerListener(this, rotationSensor,
                    AdaptiveSampler.samplingPeriodUs(mode), AdaptiveSampler.batchingLatencyUs(mode), sensorHandler);
        }
        registeredMode = mode;
        if (!ok) broadcastError("加速度计注册失败");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                hasRotationMatrix = true;
            }
            case Sensor.TYPE_PRESSURE -> {
                pressureHpa = event.values[0];
                if (!Float.isFinite(baselinePressureHpa)) baselinePressureHpa = pressureHpa;
                relativeBaroAltitudeM = SensorManager.getAltitude(baselinePressureHpa, pressureHpa);
            }
            case Sensor.TYPE_ACCELEROMETER -> processAcceleration(event);
            default -> { }
        }
    }

    private void processAcceleration(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float totalG = (float) (Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH);
        AdaptiveSampler.Decision decision = sampler.onSample(event.timestamp, totalG);
        if (decision.modeChanged && decision.mode != registeredMode) {
            sensorHandler.post(() -> registerMotionSensors(decision.mode));
        }
        if (!decision.shouldLog || store == null) return;

        if (firstSensorTimestampNs == 0) {
            firstSensorTimestampNs = event.timestamp;
            firstEpochMs = System.currentTimeMillis();
        }
        float[] world = hasRotationMatrix
                ? CoordinateMapper.deviceToWorld(rotationMatrix, x, y, z)
                : new float[] {Float.NaN, Float.NaN, Float.NaN};

        Reading r = new Reading();
        r.elapsedMs = (event.timestamp - firstSensorTimestampNs) / 1_000_000L;
        r.epochMs = firstEpochMs + r.elapsedMs;
        r.deviceX = x;
        r.deviceY = y;
        r.deviceZ = z;
        r.east = world[0];
        r.north = world[1];
        r.up = world[2];
        r.totalG = totalG;
        r.verticalG = Float.isFinite(world[2]) ? world[2] / SensorManager.GRAVITY_EARTH : Float.NaN;
        r.pressureHpa = pressureHpa;
        r.baroRelativeAltM = relativeBaroAltitudeM;
        r.latitude = latitude;
        r.longitude = longitude;
        r.gpsAltitudeM = gpsAltitudeM;
        r.gpsSpeedMps = gpsSpeedMps;
        r.gpsAccuracyM = gpsAccuracyM;
        r.gpsVerticalAccuracyM = gpsVerticalAccuracyM;
        r.gpsAccelMps2 = gpsAccelMps2;
        r.sensorAccuracy = sensorAccuracy;
        r.mode = decision.mode;
        try {
            store.write(r);
        } catch (IOException e) {
            broadcastError("写入失败：" + safeMessage(e));
            new Handler(Looper.getMainLooper()).post(this::stopSelf);
            return;
        }
        publishProgress(r);
    }

    private void publishProgress(Reading r) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiBroadcastMs >= 500) {
            lastUiBroadcastMs = now;
            Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
            update.putExtra(EXTRA_TOTAL_G, r.totalG);
            update.putExtra(EXTRA_VERTICAL_G, r.verticalG);
            update.putExtra(EXTRA_EAST, r.east);
            update.putExtra(EXTRA_NORTH, r.north);
            update.putExtra(EXTRA_UP, r.up);
            update.putExtra(EXTRA_MODE, r.mode.name());
            update.putExtra(EXTRA_ROWS, store == null ? 0 : store.getRows());
            sendBroadcast(update);
        }
        if (now - lastNotificationMs >= 10_000) {
            lastNotificationMs = now;
            String label = switch (r.mode) {
                case ACTIVE -> "高精度";
                case NORMAL -> "正常";
                case STABLE -> "低功耗";
            };
            updateNotification(String.format(Locale.getDefault(), "%s · %.3f g · %,d 条", label,
                    r.totalG, store == null ? 0 : store.getRows()));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) sensorAccuracy = accuracy;
    }

    private void startLocation() {
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, this,
                    Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            gnssEnabled = false;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        gpsAltitudeM = location.hasAltitude() ? location.getAltitude() : Double.NaN;
        gpsSpeedMps = location.hasSpeed() ? location.getSpeed() : Float.NaN;
        gpsAccuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        gpsVerticalAccuracyM = location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : Float.NaN;
        long time = location.getElapsedRealtimeNanos() / 1_000_000L;
        if (Float.isFinite(previousGpsSpeed) && Float.isFinite(gpsSpeedMps)) {
            float seconds = (time - previousGpsTimeMs) / 1000f;
            if (seconds >= 1f && seconds <= 20f) {
                float instant = (gpsSpeedMps - previousGpsSpeed) / seconds;
                gpsAccelMps2 = Float.isFinite(gpsAccelMps2)
                        ? 0.7f * gpsAccelMps2 + 0.3f * instant : instant;
            }
        }
        previousGpsSpeed = gpsSpeedMps;
        previousGpsTimeMs = time;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    private void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GFlight:Recording");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void startAsForeground(boolean withLocation, String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (withLocation) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            int type = withLocation ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
            startForeground(NOTIFICATION_ID, notification, type);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RecordingService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(cn.wangfan.gflight.R.drawable.ic_stat_g)
                .setContentTitle("G 航迹正在记录")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止并保存", stopPending).build())
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "飞行 G 值记录",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示正在进行的后台加速度记录");
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void broadcastError(String message) {
        Intent error = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        error.putExtra(EXTRA_ERROR, message);
        sendBroadcast(error);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("recording", false).apply();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        try {
            ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this);
        } catch (SecurityException ignored) { }
        if (store != null) {
            try { store.close(); } catch (IOException ignored) { }
            store = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorThread != null) sensorThread.quitSafely();
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_MODE, "STOPPED");
        sendBroadcast(update);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
