package cn.wangfan.gflight;

public final class Reading {
    public long elapsedMs;
    public long epochMs;
    public float deviceX, deviceY, deviceZ;
    public float east, north, up;
    public float totalG, verticalG;
    public float pressureHpa = Float.NaN;
    public float baroRelativeAltM = Float.NaN;
    public double latitude = Double.NaN;
    public double longitude = Double.NaN;
    public double gpsAltitudeM = Double.NaN;
    public float gpsSpeedMps = Float.NaN;
    public float gpsAccuracyM = Float.NaN;
    public float gpsVerticalAccuracyM = Float.NaN;
    public float gpsAccelMps2 = Float.NaN;
    public int sensorAccuracy;
    public AdaptiveSampler.Mode mode = AdaptiveSampler.Mode.NORMAL;
}
