package ru.wearsleep.lab;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public final class PermissionsInfoActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        TextView text = new TextView(this);
        int padding = Math.round(30 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextColor(Color.WHITE); text.setTextSize(14);
        text.setText("Sleep Lab\n\nРазрешения нужны для записи пульса, интервалов между ударами, "
                + "оптического сигнала PPG и движений часов.\n\n"
                + "Запись запускается вами и продолжается с уведомлением при выключенном экране. "
                + "Измерения хранятся на часах. Кнопка «Выгрузить ZIP» сохраняет выбранную запись "
                + "в Download/SleepLab.\n\n"
                + "У приложения нет доступа к интернету. Автоматическая передача данных не выполняется.");
        scroll.addView(text); setContentView(scroll);
    }
}
