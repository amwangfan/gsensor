package cn.wangfan.gflight;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class CsvStore implements AutoCloseable {
    public static final String RELATIVE_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/g/";

    public static final class FileEntry {
        public final Uri uri;
        public final String name;
        public final long size;
        public final long modifiedSeconds;

        FileEntry(Uri uri, String name, long size, long modifiedSeconds) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.modifiedSeconds = modifiedSeconds;
        }
    }

    private final ContentResolver resolver;
    private final Uri uri;
    private final BufferedWriter writer;
    private int rows;
    private int unflushedRows;
    private boolean closed;

    public CsvStore(Context context, String accelerometerName, boolean hasRotation,
                    boolean hasPressure, boolean gnssEnabled) throws IOException {
        resolver = context.getContentResolver();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "gflight_" + stamp + ".csv");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法在 Download/g 中创建文件");
        OutputStream stream = resolver.openOutputStream(uri, "w");
        if (stream == null) {
            resolver.delete(uri, null, null);
            throw new IOException("无法打开记录文件");
        }
        writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 32 * 1024);
        writeMetadata(accelerometerName, hasRotation, hasPressure, gnssEnabled);
    }

    private void writeMetadata(String accelerometerName, boolean hasRotation,
                               boolean hasPressure, boolean gnssEnabled) throws IOException {
        writer.write("# GFlight CSV v1\n");
        writer.write("# created_utc=" + isoUtc(System.currentTimeMillis()) + "\n");
        writer.write("# device=" + clean(Build.MANUFACTURER + " " + Build.MODEL) + "\n");
        writer.write("# accelerometer=" + clean(accelerometerName) + "\n");
        writer.write("# world_frame=Android ENU (east,north,up); NaN when rotation vector is unavailable\n");
        writer.write("# optional_sources=rotation:" + hasRotation + ",pressure:" + hasPressure
                + ",gnss:" + gnssEnabled + "\n");
        writer.write("elapsed_ms,utc_epoch_ms,mode,device_x_mps2,device_y_mps2,device_z_mps2,"
                + "east_mps2,north_mps2,up_mps2,total_g,vertical_g,pressure_hpa,baro_relative_alt_m,"
                + "latitude,longitude,gps_altitude_m,gps_speed_mps,gps_accuracy_m,gps_vertical_accuracy_m,"
                + "gps_accel_mps2,sensor_accuracy\n");
        writer.flush();
    }

    public synchronized void write(Reading r) throws IOException {
        if (closed) return;
        writer.write(String.format(Locale.US,
                "%d,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.7f,%.7f,%.3f,%.3f,"
                        + "%.8f,%.8f,%.3f,%.3f,%.3f,%.3f,%.5f,%d%n",
                r.elapsedMs, r.epochMs, r.mode.name(),
                r.deviceX, r.deviceY, r.deviceZ, r.east, r.north, r.up,
                r.totalG, r.verticalG, r.pressureHpa, r.baroRelativeAltM,
                r.latitude, r.longitude, r.gpsAltitudeM, r.gpsSpeedMps,
                r.gpsAccuracyM, r.gpsVerticalAccuracyM, r.gpsAccelMps2, r.sensorAccuracy));
        rows++;
        unflushedRows++;
        if (unflushedRows >= 20) {
            writer.flush();
            unflushedRows = 0;
        }
    }

    public Uri getUri() { return uri; }
    public int getRows() { return rows; }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            failure = e;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        if (failure != null) throw failure;
    }

    public static List<FileEntry> listOwnRecords(Context context) {
        List<FileEntry> result = new ArrayList<>();
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/g%", "gflight_%.csv"};
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            while (cursor.moveToNext()) {
                Uri item = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol));
                result.add(new FileEntry(item, cursor.getString(nameCol), cursor.getLong(sizeCol),
                        cursor.getLong(modifiedCol)));
            }
        } catch (RuntimeException ignored) {
            // SAF remains available if an OEM MediaStore does not permit this query.
        }
        return result;
    }

    /** Makes a crash-interrupted CSV visible again on the next clean app launch. */
    public static void recoverPendingRecords(Context context) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND "
                + MediaStore.MediaColumns.IS_PENDING + "=1";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/g%", "gflight_%.csv"};
        try {
            context.getContentResolver().update(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values, selection, args);
        } catch (RuntimeException ignored) { }
    }

    private static String isoUtc(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMs));
    }

    private static String clean(String value) {
        return value == null ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').replace(',', ';');
    }
}
