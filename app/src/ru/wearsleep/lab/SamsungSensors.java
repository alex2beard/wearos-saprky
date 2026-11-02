package ru.wearsleep.lab;

import android.content.Context;
import android.os.SystemClock;
import com.samsung.android.service.health.tracking.ConnectionListener;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;
import com.samsung.android.service.health.tracking.data.PpgType;
import com.samsung.android.service.health.tracking.data.ValueKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed SDK adapter; it never substitutes Android sensor data for a missing Samsung stream. */
public final class SamsungSensors {
    public interface Sink {
        void connected(String capabilities);
        void batch(String stream, List<Row> rows);
        void error(String source, String detail);
        void flushed(String stream);
    }

    public static final class Row {
        public final long timestamp, receivedWall, receivedElapsed;
        public final String csv;
        public final boolean valid;
        public final int bpm, ibiCount, ibiValid;
        public final Integer rawValue, rawStatus;
        Row(long timestamp, long wall, long elapsed, String csv, boolean valid,
            int bpm, int ibiCount, int ibiValid, Integer rawValue, Integer rawStatus) {
            this.timestamp = timestamp; receivedWall = wall; receivedElapsed = elapsed;
            this.csv = csv; this.valid = valid; this.bpm = bpm;
            this.ibiCount = ibiCount; this.ibiValid = ibiValid;
            this.rawValue = rawValue; this.rawStatus = rawStatus;
        }
    }

    private final Sink sink;
    private HealthTrackingService service;
    private final Map<String, HealthTracker> trackers = new LinkedHashMap<>();
    private volatile boolean closed;

    public SamsungSensors(Context context, Sink sink) {
        this.sink = sink;
        service = new HealthTrackingService(new ConnectionListener() {
            @Override public void onConnectionSuccess() {
                if (closed) return;
                try {
                    sink.connected(service.getTrackingCapability().getSupportHealthTrackerTypes().toString());
                } catch (Exception e) { sink.error("capabilities", e.toString()); }
            }
            @Override public void onConnectionEnded() {
                if (!closed) sink.error("connection", "Соединение с Health Sensor Service завершено");
            }
            @Override public void onConnectionFailed(HealthTrackerException e) {
                if (!closed) sink.error("connection", "code=" + e.getErrorCode() + "; " + e);
            }
        }, context);
    }

    public void connect() { service.connectService(); }

    public void begin(RecordingProfile profile) {
        List<HealthTrackerType> supported = service.getTrackingCapability().getSupportHealthTrackerTypes();
        List<HealthTrackerType> required = new ArrayList<>();
        required.add(HealthTrackerType.ACCELEROMETER_CONTINUOUS);
        if (profile.ppg) required.add(HealthTrackerType.PPG_CONTINUOUS);
        if (profile.heartRate) required.add(HealthTrackerType.HEART_RATE_CONTINUOUS);
        for (HealthTrackerType type : required) {
            if (!supported.contains(type)) throw new IllegalStateException("Не поддерживается " + type);
        }
        add("acc", service.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS));
        if (profile.heartRate) add("hr", service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS));
        if (profile.ppg) add("ppg", service.getHealthTracker(HealthTrackerType.PPG_CONTINUOUS,
                Collections.singleton(PpgType.GREEN)));
    }

    private void add(final String stream, HealthTracker tracker) {
        trackers.put(stream, tracker);
        tracker.setEventListener(new HealthTracker.TrackerEventListener() {
            @Override public void onDataReceived(List<DataPoint> points) {
                if (closed || points == null || points.isEmpty()) return;
                final long wall = System.currentTimeMillis();
                final long elapsed = SystemClock.elapsedRealtimeNanos();
                try {
                    List<Row> rows = new ArrayList<>(points.size());
                    for (DataPoint point : points) rows.add(convert(stream, point, wall, elapsed));
                    sink.batch(stream, rows);
                } catch (Exception e) { sink.error(stream, "Чтение данных: " + e); }
            }
            @Override public void onFlushCompleted() { if (!closed) sink.flushed(stream); }
            @Override public void onError(HealthTracker.TrackerError error) {
                if (!closed) sink.error(stream, String.valueOf(error));
            }
        });
    }

    private static Row convert(String stream, DataPoint point, long wall, long elapsed) {
        long timestamp = point.getTimestamp();
        if (stream.equals("ppg")) {
            Integer value = point.getValue(ValueKey.PpgSet.PPG_GREEN);
            Integer status = point.getValue(ValueKey.PpgSet.GREEN_STATUS);
            return new Row(timestamp, wall, elapsed,
                    Csv.line(timestamp, wall, elapsed, value, status),
                    SensorQuality.ppg(value, status), 0, 0, 0, value, status);
        }
        if (stream.equals("acc")) {
            Integer x = point.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X);
            Integer y = point.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y);
            Integer z = point.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z);
            return new Row(timestamp, wall, elapsed, Csv.line(timestamp, wall, elapsed, x, y, z),
                    x != null && y != null && z != null, 0, 0, 0, null, null);
        }
        Integer bpm = point.getValue(ValueKey.HeartRateSet.HEART_RATE);
        Integer status = point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS);
        List<Integer> intervals = point.getValue(ValueKey.HeartRateSet.IBI_LIST);
        List<Integer> statuses = point.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST);
        int count = intervals == null ? 0 : intervals.size(), good = 0;
        for (int i = 0; i < count; i++) {
            if (intervals.get(i) != null && intervals.get(i) > 0 && statuses != null
                    && i < statuses.size() && statuses.get(i) != null && statuses.get(i) == 0) good++;
        }
        boolean valid = SensorQuality.heartRate(bpm, status);
        return new Row(timestamp, wall, elapsed,
                Csv.line(timestamp, wall, elapsed, bpm, status, Csv.list(intervals), Csv.list(statuses)),
                valid, valid ? bpm : 0, count, good, bpm, status);
    }

    public List<String> flush() {
        List<String> waiting = new ArrayList<>();
        for (Map.Entry<String, HealthTracker> entry : trackers.entrySet()) {
            if (entry.getValue().flush()) waiting.add(entry.getKey());
        }
        return waiting;
    }

    public void close() {
        closed = true;
        for (HealthTracker tracker : trackers.values()) {
            try { tracker.unsetEventListener(); } catch (RuntimeException ignored) {}
        }
        trackers.clear();
        try { service.disconnectService(); } catch (RuntimeException ignored) {}
    }
}
