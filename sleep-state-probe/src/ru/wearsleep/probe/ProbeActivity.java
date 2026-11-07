package ru.wearsleep.probe;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.health.services.client.HealthServices;
import androidx.health.services.client.PassiveMonitoringClient;
import androidx.health.services.client.data.PassiveListenerConfig;
import androidx.health.services.client.data.UserActivityState;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ProbeActivity extends Activity {
    private static final String ACTIVITY_PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION;
    private static final int REQUEST_ACTIVITY = 1;
    private TextView status;
    private Button check, finish;
    private PassiveMonitoringClient client;
    private boolean busy;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        client = HealthServices.getClient(getApplicationContext()).getPassiveMonitoringClient();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(30), dp(24), dp(30), dp(24));
        scroll.addView(body);
        TextView title = new TextView(this);
        title.setText("Статус сна");
        title.setTextColor(0xff99c5ff);
        title.setTextSize(20);
        body.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, dp(8));
        body.addView(status, -1, -2);
        check = button(body, "Проверить");
        check.setOnClickListener(view -> checkCapabilities());
        finish = button(body, "Сохранить отчёт");
        finish.setOnClickListener(view -> finishCheck());
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy) render(ProbeLog.message(this));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button button(LinearLayout body, String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        body.addView(button, -1, -2);
        return button;
    }

    private void render(String message) {
        status.setText(message);
        check.setEnabled(!busy && !ProbeLog.active(this));
        finish.setEnabled(!busy && !ProbeLog.report(this).isEmpty());
    }

    private void done(String message) {
        busy = false;
        ProbeLog.setMessage(this, message);
        render(message);
    }

    private void checkCapabilities() {
        busy = true;
        render("Проверка Wear OS…");
        ProbeLog.begin(this);
        await(client::getCapabilitiesAsync, capabilities -> {
            ProbeLog.append(this, "supported_states=" + capabilities.getSupportedUserActivityStates());
            if (!capabilities.getSupportedUserActivityStates().contains(UserActivityState.USER_ACTIVITY_ASLEEP)) {
                done("ASLEEP недоступен");
            } else if (checkSelfPermission(ACTIVITY_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                subscribe();
            } else {
                requestPermissions(new String[]{ACTIVITY_PERMISSION}, REQUEST_ACTIVITY);
            }
        }, error -> fail("Ошибка проверки", error));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQUEST_ACTIVITY) return;
        if (checkSelfPermission(ACTIVITY_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            subscribe();
        } else {
            ProbeLog.append(this, "activity_permission_denied");
            done("Разреши физическую активность и повтори проверку");
        }
    }

    private void subscribe() {
        PassiveListenerConfig config = PassiveListenerConfig.builder()
                .setShouldUserActivityInfoBeRequested(true).build();
        ProbeLog.setActive(this, true);
        await(() -> client.setPassiveListenerServiceAsync(ProbeService.class, config), ignored -> {
            ProbeLog.append(this, "listener_registered");
            done("ASLEEP поддерживается. Слушатель запущен");
        }, error -> {
            ProbeLog.setActive(this, false);
            fail("Ошибка запуска слушателя", error);
        });
    }

    private void finishCheck() {
        busy = true;
        render("Сохраняем отчёт…");
        if (ProbeLog.active(this)) {
            await(client::flushAsync, ignored -> stopListener(), error -> {
                ProbeLog.append(this, "flush_error=" + error);
                stopListener();
            });
        } else {
            exportReport();
        }
    }

    private void stopListener() {
        await(client::clearPassiveListenerServiceAsync, ignored -> {
            ProbeLog.append(this, "listener_stopped");
            ProbeLog.setActive(this, false);
            exportReport();
        }, error -> fail("Ошибка остановки. Повтори сохранение", error));
    }

    private void exportReport() {
        ProbeLog.snapshotSamsungHealth(this);
        ProbeLog.append(this, "report_exported");
        String name = "sleep-state-probe-" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SleepLab");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Недоступен каталог Download");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Не удалось открыть файл");
                out.write(ProbeLog.report(this).getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (getContentResolver().update(uri, values, null, null) != 1) {
                throw new IOException("Не удалось завершить сохранение");
            }
            done("Отчёт сохранён в Download/SleepLab\n" + name);
        } catch (IOException | RuntimeException error) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            fail("Ошибка сохранения", error);
        }
    }

    private <T> void await(Supplier<ListenableFuture<T>> request, Consumer<T> success, Consumer<Throwable> failure) {
        try {
            ListenableFuture<T> future = request.get();
            future.addListener(() -> {
                try {
                    success.accept(future.get());
                } catch (ExecutionException error) {
                    failure.accept(error.getCause());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failure.accept(error);
                } catch (RuntimeException error) {
                    failure.accept(error);
                }
            }, getMainExecutor());
        } catch (RuntimeException error) {
            failure.accept(error);
        }
    }

    private void fail(String message, Throwable error) {
        ProbeLog.append(this, "error=" + error);
        done(message + "\n" + error.getMessage());
    }
}
