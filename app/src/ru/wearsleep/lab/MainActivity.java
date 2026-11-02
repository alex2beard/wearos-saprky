package ru.wearsleep.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String HEART_RATE = "android.permission.health.READ_HEART_RATE";
    private static final String ADDITIONAL = "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, detail, clock, exportStatus;
    private Button test, ppgTest, night, stop, export;
    private boolean requestedNight, exporting;
    private RecordingProfile requestedProfile = RecordingProfile.HR_ONLY;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28), dp(34), dp(28), dp(36));
        scroll.addView(body);
        TextView title = label("Sleep Lab", 22, Color.rgb(141, 187, 255));
        title.setTypeface(null, Typeface.BOLD);
        body.addView(title);
        body.addView(label("Диагностика сна · " + BuildInfo.VERSION_NAME, 11, Color.LTGRAY));
        status = label("Готов к проверке", 16, Color.WHITE);
        status.setPadding(0, dp(14), 0, dp(4));
        body.addView(status);
        clock = label("", 13, Color.LTGRAY);
        body.addView(clock);
        detail = label("PPG · пульс и IBI · движения", 12, Color.LTGRAY);
        detail.setGravity(Gravity.START);
        detail.setPadding(0, dp(10), 0, dp(12));
        body.addView(detail);
        test = button(body, "Пульс + IBI · 2 мин", true);
        test.setOnClickListener(v -> requestStart(false, RecordingProfile.HR_ONLY));
        ppgTest = button(body, "PPG · 2 мин", false);
        ppgTest.setOnClickListener(v -> requestStart(false, RecordingProfile.PPG_ONLY));
        night = button(body, "Ночная запись", false);
        night.setOnClickListener(v -> requestStart(true, RecordingProfile.COMBINED));
        stop = button(body, "Остановить", false);
        stop.setOnClickListener(v -> startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP)));
        export = button(body, "Выгрузить ZIP", false);
        export.setOnClickListener(v -> chooseExport());
        exportStatus = label("", 11, Color.LTGRAY);
        body.addView(exportStatus);
        Button permissions = button(body, "Разрешения", false);
        permissions.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        Button help = button(body, "О датчиках", false);
        help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Доступ к датчикам")
                .setMessage("Нужен режим разработчика Health Sensor Service на часах. Это отдельный режим, "
                        + "не отладка ADB.\n\nSDK_POLICY_ERROR обычно означает ограничение доступа SDK. "
                        + "Версия сервиса и поддержка датчиков сохраняются в отчёте.\n\n"
                        + "В Health Platform 1.7.00.05 надпись [Dev mode] под заголовком означает, что режим уже включён.\n\n"
                        + "Сначала проверьте пульс/IBI, затем PPG отдельными двухминутными записями. "
                        + "Нулевой PPG годным не считается.\n\n"
                        + "Для ночи часы должны оставаться на запястье. Запись ограничена 12 часами; "
                        + "при заряде 8% без зарядки она завершится.\n\n"
                        + "Приложение записывает измерения; стадии сна в этой версии ещё не рассчитываются.")
                .setPositiveButton("Понятно", null).show());
        setContentView(scroll);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button button(LinearLayout parent, String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text); button.setTextSize(13); button.setAllCaps(false);
        button.setTextColor(Color.WHITE); button.setMinHeight(dp(48));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? Color.rgb(35, 83, 151) : Color.rgb(35, 38, 44)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(3), 0, dp(3));
        parent.addView(button, params);
        return button;
    }

    private List<String> sensorPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add("android.permission.ACTIVITY_RECOGNITION");
        if (Build.VERSION.SDK_INT >= 36) { permissions.add(HEART_RATE); permissions.add(ADDITIONAL); }
        else permissions.add("android.permission.BODY_SENSORS");
        return permissions;
    }

    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    private void requestStart(boolean forNight, RecordingProfile profile) {
        if (RecordingService.active || exporting) return;
        requestedNight = forNight;
        requestedProfile = profile;
        List<String> missing = new ArrayList<>();
        for (String permission : sensorPermissions()) if (!granted(permission)) missing.add(permission);
        if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.POST_NOTIFICATIONS")) {
            missing.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 10);
        else startRecording();
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request != 10) return;
        for (String permission : sensorPermissions()) {
            if (!granted(permission)) {
                status.setText("Нужен доступ к датчикам");
                Toast.makeText(this, "Проверьте разрешения приложения", Toast.LENGTH_LONG).show();
                return;
            }
        }
        startRecording();
    }

    private void startRecording() {
        if (RecordingService.active) return;
        try {
            startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.START)
                    .putExtra("night", requestedNight).putExtra("profile", requestedProfile.id));
        } catch (RuntimeException e) { status.setText("Ошибка запуска"); detail.setText(e.toString()); }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1500); }
    };

    private void update() {
        boolean running = RecordingService.active;
        SharedPreferences prefs = getSharedPreferences("state", MODE_PRIVATE);
        if (!RecordingService.status.isEmpty()) {
            status.setText(RecordingService.status); detail.setText(RecordingService.detail);
        } else if (prefs.getBoolean("recording", false)) {
            status.setText("Предыдущая запись прервана");
            detail.setText("Сохранённые данные можно выгрузить в ZIP.");
        } else if (prefs.contains("last_status")) {
            status.setText(prefs.getString("last_status", "")); detail.setText(prefs.getString("last_detail", ""));
        }
        if (running && RecordingService.startedElapsed > 0) {
            long seconds = (SystemClock.elapsedRealtime() - RecordingService.startedElapsed) / 1000;
            clock.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
        } else clock.setText("");
        test.setEnabled(!running && !exporting); ppgTest.setEnabled(!running && !exporting);
        night.setEnabled(!running && !exporting);
        stop.setEnabled(running); export.setEnabled(!running && !exporting && sessions().length > 0);
        if (!exporting) exportStatus.setText(prefs.getString("last_export", ""));
    }

    private File[] sessions() {
        File[] result = new File(getFilesDir(), "sessions").listFiles(File::isDirectory);
        if (result == null) return new File[0];
        Arrays.sort(result, Collections.reverseOrder());
        return result;
    }

    private void chooseExport() {
        File[] entries = sessions();
        if (entries.length == 0 || RecordingService.active) return;
        if (entries.length == 1) { export(entries[0]); return; }
        String[] titles = new String[entries.length];
        for (int i = 0; i < entries.length; i++) titles[i] = entries[i].getName().substring(0, 15) + " UTC";
        new AlertDialog.Builder(this).setTitle("Выберите запись")
                .setItems(titles, (dialog, which) -> export(entries[which])).show();
    }

    private void export(File session) {
        if (exporting || RecordingService.active) return;
        exporting = true;
        exportStatus.setText("Создаём ZIP…");
        update();
        new Thread(() -> {
            Uri uri = null;
            try {
                String name = "sleep-lab-" + session.getName() + ".zip";
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SleepLab");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Недоступен каталог Download");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Не удалось создать ZIP");
                    SessionStore.exportZip(session, out);
                }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                getSharedPreferences("state", MODE_PRIVATE).edit()
                        .putString("last_export", "Download/SleepLab/\n" + name).commit();
                handler.post(() -> { exporting = false; update(); Toast.makeText(this, "ZIP сохранён", Toast.LENGTH_LONG).show(); });
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                handler.post(() -> { exporting = false; update(); exportStatus.setText("Ошибка выгрузки: " + e); });
            }
        }, "sleep-export").start();
    }

    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
