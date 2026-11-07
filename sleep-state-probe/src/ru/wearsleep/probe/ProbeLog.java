package ru.wearsleep.probe;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class ProbeLog {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sleep_state_probe", Context.MODE_PRIVATE);
    }

    static synchronized void begin(Context context) {
        String header = "Sleep state probe " + BuildInfo.VERSION_NAME + "\n"
                + "model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " build=" + Build.DISPLAY + "\n"
                + "timezone=" + ZoneId.systemDefault() + "\n";
        save(prefs(context).edit().putString("log", header).putBoolean("active", false)
                .remove("message"));
        append(context, "check_started");
        snapshotSamsungHealth(context);
    }

    static synchronized void append(Context context, String event) {
        save(prefs(context).edit().putString("log", report(context) + Instant.now()
                + " uptime_ms=" + SystemClock.elapsedRealtime() + " " + event + "\n"));
    }

    static String report(Context context) {
        return prefs(context).getString("log", "");
    }

    static boolean active(Context context) {
        return prefs(context).getBoolean("active", false);
    }

    static void setActive(Context context, boolean active) {
        save(prefs(context).edit().putBoolean("active", active));
    }

    static String message(Context context) {
        return prefs(context).getString("message", "Нажми «Проверить»");
    }

    static void setMessage(Context context, String message) {
        save(prefs(context).edit().putString("message", message));
    }

    private static void save(SharedPreferences.Editor change) {
        if (!change.commit()) Log.e("WearSleepProbe", "Could not persist diagnostic state");
    }

    static void snapshotSamsungHealth(Context context) {
        try {
            PackageInfo app = context.getPackageManager().getPackageInfo(
                    "com.samsung.android.wear.shealth", PackageManager.GET_PERMISSIONS);
            List<String> granted = new ArrayList<>();
            if (app.requestedPermissions != null && app.requestedPermissionsFlags != null) {
                for (int i = 0; i < app.requestedPermissions.length; i++) {
                    String permission = app.requestedPermissions[i];
                    if ((app.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                            && (permission.equals(Manifest.permission.ACTIVITY_RECOGNITION)
                            || permission.startsWith("android.permission.BODY_SENSORS")
                            || permission.startsWith("android.permission.health.")
                            || permission.equals("com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"))) {
                        granted.add(permission);
                    }
                }
            }
            append(context, "samsung_health enabled=" + app.applicationInfo.enabled + " health_grants=" + granted);
        } catch (PackageManager.NameNotFoundException e) {
            append(context, "samsung_health package_not_found");
        }
    }
}
