package ru.wearsleep.lab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RecordingService extends Service implements SamsungSensors.Sink, SensorEventListener {
    public static final String START = "ru.wearsleep.lab.START", STOP = "ru.wearsleep.lab.STOP";
    public static volatile boolean active;
    public static volatile boolean foregroundOnly, activityResumed;
    public static volatile String status = "", detail = "";
    public static volatile long startedElapsed;
    private HandlerThread thread;
    private Handler worker;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private PowerManager power;
    private SessionStore store;
    private SamsungSensors sensors;
    private SensorManager contactSensorManager;
    private boolean contactSensorRegistered;
    private Integer wornState, latestHrStatus;
    private RecordingProfile profile = RecordingProfile.HR_ONLY;
    private final SensorQuality.PpgChannel[] ppgChannels = {
            new SensorQuality.PpgChannel(), new SensorQuality.PpgChannel(), new SensorQuality.PpgChannel()};
    private static final String[] PPG_NAMES = {"green", "ir", "red"};
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && foregroundOnly) {
                worker.post(() -> stop("probe_screen_off"));
            }
        }
    };
    private final Map<String, Long> hrStatuses = new LinkedHashMap<>();
    private final Map<String, StreamStats> stats = new LinkedHashMap<>();
    private final Map<String, Long> receivedAt = new LinkedHashMap<>();
    private final Set<String> pendingFlush = new HashSet<>();
    private boolean started, stopping, finished, connected;
    private String mode, capabilities = "", lastError = "", stopReason = "";
    private long startedWall, durationMs, lastPublish;
    private int batteryStart, latestBpm;
    private long ibiCount, ibiValid;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("sleep-recorder");
        thread.start();
        worker = new Handler(thread.getLooper());
        power = (PowerManager) getSystemService(POWER_SERVICE);
        NotificationChannel channel = new NotificationChannel("recording", "Запись датчиков",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            String reason = intent.getStringExtra("reason");
            worker.post(() -> stop("probe_left_foreground".equals(reason) ? reason : "user"));
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || started) return START_NOT_STICKY;
        started = true;
        mode = intent.getBooleanExtra("night", false) ? "night" : "test";
        try {
            profile = RecordingProfile.select(mode.equals("night"), intent.getStringExtra("profile"));
            durationMs = profile.durationMs(mode.equals("night"));
            foregroundOnly = profile.onDemand;
            if (foregroundOnly && !activityResumed) throw new IllegalStateException("Для теста PPG откройте приложение");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
            } else startForeground(1, notification());
            active = true;
            if (profile.onDemand) {
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED);
                else registerReceiver(screenReceiver, filter);
                screenReceiverRegistered = true;
            }
            status = "Подключение датчиков…";
            detail = "";
            worker.post(this::begin);
        } catch (RuntimeException e) {
            status = "Не удалось запустить запись";
            detail = e.toString();
            active = false;
            foregroundOnly = false;
            saveMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecordingService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "recording").setSmallIcon(R.drawable.ic_sleep)
                .setContentTitle("Sleep Lab — запись датчиков")
                .setContentText(profile.label + (profile.expects("acc") ? " · движения" : " · 30 с"))
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build();
    }

    private void begin() {
        try {
            File root = new File(getFilesDir(), "sessions");
            if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Недоступно хранилище");
            if (root.getUsableSpace() < 50 * 1024 * 1024L) {
                throw new IllegalStateException("Для записи нужно 50 МБ свободного места");
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            String id = format.format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8);
            store = new SessionStore(new File(root, id));
            stats.put("ppg", new StreamStats(200));
            stats.put("hr", new StreamStats(5000));
            stats.put("acc", new StreamStats(200));
            startedWall = System.currentTimeMillis();
            startedElapsed = SystemClock.elapsedRealtime();
            batteryStart = batteryPercent();
            getSharedPreferences("state", MODE_PRIVATE).edit().putString("last_session", id)
                    .putBoolean("recording", true).commit();
            store.metadata(metadata("connecting").toString(2));
            event("start", "mode=" + mode + ";profile=" + profile.id);
            environmentEvent();
            observeWristState();
            sensors = new SamsungSensors(getApplicationContext(), this);
            sensors.connect();
            worker.postDelayed(() -> {
                if (!connected && !finished && !stopping) {
                    lastError = "Health Sensor Service не ответил за 30 секунд";
                    stop("connection_timeout");
                }
            }, 30000);
            worker.postDelayed(tick, 20000);
            worker.postDelayed(() -> stop("duration"), durationMs);
        } catch (Exception | LinkageError e) { fail("start", e.toString()); }
    }

    /** A short CPU lock only protects an arriving batch until it is written; no all-night lock. */
    private void enqueue(Runnable job) {
        PowerManager.WakeLock lock = null;
        if (power != null && !power.isInteractive()) {
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepLab:batch");
            lock.acquire(15000);
        }
        final PowerManager.WakeLock batchLock = lock;
        boolean accepted = worker.post(() -> {
            try { if (!finished) job.run(); }
            finally { if (batchLock != null && batchLock.isHeld()) batchLock.release(); }
        });
        if (!accepted && batchLock != null && batchLock.isHeld()) batchLock.release();
    }

    @Override public void connected(String available) {
        enqueue(() -> {
            if (stopping) return;
            try {
                capabilities = available;
                event("capabilities", available);
                if (profile.onDemand && (!activityResumed || !power.isInteractive())) {
                    stop("probe_left_foreground"); return;
                }
                sensors.begin(profile);
                connected = true;
                status = "Запись идёт";
                store.metadata(metadata("recording").toString(2));
                publish(true);
            } catch (Exception | LinkageError e) { fail("trackers", e.toString()); }
        });
    }

    @Override public void batch(String stream, List<SamsungSensors.Row> rows) {
        enqueue(() -> {
            if (store == null) return;
            try {
                StreamStats meter = stats.get(stream);
                for (SamsungSensors.Row row : rows) {
                    store.write(stream, row.csv);
                    meter.add(row.timestamp, row.valid);
                    if (stream.equals("ppg")) {
                        for (int i = 0; i < ppgChannels.length; i++) {
                            ppgChannels[i].add(row.ppgValues[i], row.ppgStatuses[i]);
                        }
                    }
                    if (stream.equals("hr")) {
                        latestHrStatus = row.rawStatus;
                        String key = String.valueOf(row.rawStatus);
                        hrStatuses.put(key, hrStatuses.containsKey(key) ? hrStatuses.get(key) + 1 : 1L);
                        latestBpm = row.bpm;
                        ibiCount += row.ibiCount;
                        ibiValid += row.ibiValid;
                    }
                }
                receivedAt.put(stream, SystemClock.elapsedRealtime());
                publish(false);
                if (!stopping && SystemClock.elapsedRealtime() - startedElapsed >= durationMs) stop("duration");
            } catch (Exception e) { fail("write", e.toString()); }
        });
    }

    @Override public void error(String source, String message) {
        enqueue(() -> fail(source, message));
    }

    @Override public void flushed(String stream) {
        enqueue(() -> {
            if (stopping && pendingFlush.remove(stream) && pendingFlush.isEmpty()) {
                worker.postDelayed(this::finish, 250);
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (finished || stopping || store == null) return;
            try {
                environmentEvent();
                store.flush();
                store.metadata(metadata(connected ? "recording" : "connecting").toString(2));
                if (SystemClock.elapsedRealtime() - startedElapsed >= durationMs) {
                    stop("duration"); return;
                }
                if (store.directory.getUsableSpace() < 15 * 1024 * 1024L) {
                    stop("low_storage"); return;
                }
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                if (batteryPercent() >= 0 && batteryPercent() <= 8 && plugged == 0) {
                    stop("low_battery"); return;
                }
                publish(true);
                worker.postDelayed(this, 20000);
            } catch (Exception e) { fail("storage", e.toString()); }
        }
    };

    private void environmentEvent() throws Exception {
        event("environment", "battery=" + batteryPercent() + ";interactive=" + power.isInteractive()
                + ";idle=" + power.isDeviceIdleMode() + ";power_save=" + power.isPowerSaveMode());
    }

    private int batteryPercent() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level >= 0 && scale > 0 ? level * 100 / scale : -1;
    }

    private void observeWristState() throws Exception {
        contactSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor offBody = contactSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        if (offBody == null) { event("wrist_sensor", "unavailable"); return; }
        try {
            contactSensorRegistered = contactSensorManager.registerListener(this, offBody,
                    SensorManager.SENSOR_DELAY_NORMAL, worker);
            event("wrist_sensor", "registered=" + contactSensorRegistered + ";name=" + offBody.getName());
        } catch (RuntimeException e) { event("wrist_sensor", e.toString()); }
    }

    @Override public void onSensorChanged(SensorEvent sensorEvent) {
        if (finished || store == null || sensorEvent.values.length == 0) return;
        float value = sensorEvent.values[0];
        wornState = SensorQuality.onWrist(value);
        try {
            event("wrist_state", "raw=" + value + ";sensor_elapsed_ns=" + sensorEvent.timestamp);
            publish(true);
        } catch (Exception e) { fail("wrist_log", e.toString()); }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void event(String name, String text) throws Exception {
        if (store != null) store.event(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), name, text);
    }

    private void fail(String source, String text) {
        lastError = source + ": " + text;
        try { event("error", lastError); } catch (Exception ignored) {}
        stop("error");
    }

    private void stop(String reason) {
        if (finished || stopping) return;
        stopping = true;
        stopReason = reason;
        status = "Сохраняем запись…";
        try {
            event("stop_requested", reason);
            // On-demand tracking ends immediately; it must not outlive the 30-second foreground probe.
            if (sensors != null && !profile.onDemand) pendingFlush.addAll(sensors.flush());
            event("flush_waiting", pendingFlush.toString());
            if (pendingFlush.isEmpty()) finish();
            else worker.postDelayed(() -> {
                if (!finished) {
                    try { event("flush_timeout", pendingFlush.toString()); } catch (Exception ignored) {}
                    finish();
                }
            }, 5000);
        } catch (Exception e) {
            lastError = lastError + " flush: " + e;
            finish();
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        worker.removeCallbacks(tick);
        if (sensors != null) sensors.close();
        if (contactSensorManager != null) contactSensorManager.unregisterListener(this);
        if (connected && lastError.isEmpty()) {
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<String, StreamStats> entry : stats.entrySet()) {
                if (profile.expects(entry.getKey()) && entry.getValue().valid == 0) {
                    missing.append(entry.getKey()).append(' ');
                }
            }
            if (missing.length() > 0) lastError = "Нет годных измерений: " + missing;
            else if (profile.ppg && !ppgChanges()) lastError = "PPG не изменяется: сигнал не подтверждён";
        }
        try {
            event("finish", stopReason);
            if (store != null) {
                store.close();
                store.metadata(metadata(lastError.isEmpty() ? "completed" : "error").toString(2));
            }
        } catch (Exception e) { lastError = lastError + " save: " + e; }
        publish(true);
        status = lastError.isEmpty() ? "Запись завершена" : "Запись остановлена с ошибкой";
        if (!lastError.isEmpty()) detail += "\n\n" + lastError;
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("recording", false)
                .putString("last_status", status).putString("last_detail", detail).commit();
        main.post(() -> {
            active = false;
            foregroundOnly = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void publish(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPublish < 1500) return;
        lastPublish = now;
        StringBuilder text = new StringBuilder(profile.label).append('\n');
        text.append("На руке: ").append(wornState == null ? "нет данных" : wornState == 1 ? "да" : "нет").append('\n');
        for (Map.Entry<String, StreamStats> entry : stats.entrySet()) {
            if (!profile.expects(entry.getKey())) continue;
            String name = entry.getKey().equals("ppg") ? "PPG" : entry.getKey().equals("hr") ? "Пульс" : "Движения";
            StreamStats meter = entry.getValue();
            text.append(name).append(": ").append(meter.count).append(" / ").append(meter.valid).append(" годных\n");
        }
        if (profile.heartRate) {
            text.append("IBI: ").append(ibiCount).append(" / ").append(ibiValid).append(" годных\n");
            text.append("Пульс сейчас: ").append(latestBpm > 0 ? latestBpm + " уд/мин" : "—");
            text.append("\nСтатус HR: ").append(latestHrStatus == null ? "—" : latestHrStatus);
        }
        if (profile.ppg) {
            for (int i = 0; i < (profile.onDemand ? 3 : 1); i++) {
                text.append('\n').append(PPG_NAMES[i]).append(": ").append(ppgChannels[i].valid)
                        .append(" годных, нулей ").append(ppgChannels[i].raw.zeroSamples);
            }
            if (profile.onDemand && !finished) text.append("\n30 с: оставьте приложение открытым");
        }
        detail = text.toString();
    }

    private boolean ppgChanges() {
        for (SensorQuality.PpgChannel channel : ppgChannels) if (channel.changes()) return true;
        return false;
    }

    private JSONObject metadata(String state) throws Exception {
        JSONObject out = new JSONObject();
        out.put("schema", 1).put("app_version", BuildInfo.VERSION_NAME).put("sdk_version", "1.4.1")
                .put("session_state", state).put("mode", mode).put("started_wall_ms", startedWall)
                .put("profile", profile.id).put("wrist_sensor_registered", contactSensorRegistered)
                .put("on_wrist", wornState == null ? JSONObject.NULL : wornState == 1)
                .put("started_elapsed_ms", startedElapsed).put("snapshot_wall_ms", System.currentTimeMillis())
                .put("snapshot_elapsed_ms", SystemClock.elapsedRealtime())
                .put("duration_limit_ms", durationMs).put("battery_start_percent", batteryStart)
                .put("battery_snapshot_percent", batteryPercent()).put("stop_reason", stopReason)
                .put("error", lastError).put("capabilities", capabilities)
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android_sdk", Build.VERSION.SDK_INT).put("android_release", Build.VERSION.RELEASE)
                .put("firmware", Build.DISPLAY).put("timezone", TimeZone.getDefault().getID())
                .put("source_clock", "Samsung DataPoint.getTimestamp(): milliseconds; values unchanged")
                .put("ibi_timing", "Lists are preserved on their original SDK datapoint, including batched lists; no individual beat timestamps are invented")
                .put("accelerometer_scale_ms2_per_raw", 9.81 / (16383.75 / 4.0))
                .put("ppg_channels", profile.ppg ? profile.onDemand ? "GREEN,IR,RED" : "GREEN" : "")
                .put("ppg_tracker", !profile.ppg ? JSONObject.NULL : profile.onDemand ? "PPG_ON_DEMAND" : "PPG_CONTINUOUS")
                .put("foreground_only", profile.onDemand)
                .put("ppg_validity", "Nonzero ADC and SDK status 0 in at least one selected channel; variation uses valid samples only")
                .put("ibi_count", ibiCount).put("ibi_valid", ibiValid);
        JSONArray expected = new JSONArray();
        for (String stream : stats.keySet()) if (profile.expects(stream)) expected.put(stream);
        out.put("expected_streams", expected);
        SensorQuality.Range ppgRange = ppgChannels[0].raw;
        out.put("ppg_zero_samples", ppgRange.zeroSamples).put("ppg_changes", ppgChanges())
                .put("ppg_minimum", ppgRange.minimum == null ? JSONObject.NULL : ppgRange.minimum)
                .put("ppg_maximum", ppgRange.maximum == null ? JSONObject.NULL : ppgRange.maximum)
                .put("hr_status_counts", new JSONObject(hrStatuses));
        JSONObject channels = new JSONObject();
        for (int i = 0; i < ppgChannels.length; i++) {
            SensorQuality.PpgChannel channel = ppgChannels[i];
            channels.put(PPG_NAMES[i], new JSONObject().put("samples", channel.raw.samples)
                    .put("valid", channel.valid).put("zero_samples", channel.raw.zeroSamples)
                    .put("minimum", channel.raw.minimum == null ? JSONObject.NULL : channel.raw.minimum)
                    .put("maximum", channel.raw.maximum == null ? JSONObject.NULL : channel.raw.maximum)
                    .put("signal_changes", channel.changes()));
        }
        out.put("ppg_channel_stats", channels);
        JSONObject permissions = new JSONObject();
        String[] permissionNames = Build.VERSION.SDK_INT >= 36 ? new String[] {
                "android.permission.ACTIVITY_RECOGNITION", "android.permission.health.READ_HEART_RATE",
                "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        } : new String[] {"android.permission.ACTIVITY_RECOGNITION", "android.permission.BODY_SENSORS"};
        for (String permission : permissionNames) {
            permissions.put(permission, checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        }
        out.put("granted_permissions", permissions);
        JSONObject meters = new JSONObject();
        for (Map.Entry<String, StreamStats> entry : stats.entrySet()) {
            StreamStats m = entry.getValue();
            JSONObject meter = new JSONObject().put("samples", m.count).put("valid", m.valid)
                    .put("invalid", m.invalid).put("duplicates", m.duplicates).put("backwards", m.backwards)
                    .put("gaps", m.gaps).put("max_gap_ms", m.maxGapMs).put("observed_hz", m.frequencyHz());
            if (m.count != 0) meter.put("first_timestamp_ms", m.firstTimestamp).put("last_timestamp_ms", m.lastTimestamp);
            Long last = receivedAt.get(entry.getKey());
            if (last != null) meter.put("last_received_elapsed_ms", last);
            meters.put(entry.getKey(), meter);
        }
        out.put("streams", meters);
        try {
            out.put("sensor_service_version", getPackageManager()
                    .getPackageInfo("com.samsung.android.service.health", 0).versionName);
        } catch (Exception ignored) {}
        JSONArray inventory = new JSONArray();
        SensorManager manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
            inventory.put(new JSONObject().put("name", sensor.getName()).put("type", sensor.getStringType())
                    .put("vendor", sensor.getVendor()).put("wakeup", sensor.isWakeUpSensor())
                    .put("fifo_max", sensor.getFifoMaxEventCount()));
        }
        return out.put("android_sensor_inventory", inventory);
    }

    private void saveMessage() {
        getSharedPreferences("state", MODE_PRIVATE).edit().putString("last_status", status)
                .putString("last_detail", detail).apply();
    }

    @Override public void onDestroy() {
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver);
        if (worker != null) worker.post(() -> {
            if (started && !finished && active) {
                lastError = "Служба записи завершена системой";
                stopReason = "service_destroyed";
                finish();
            }
            thread.quitSafely();
        });
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
