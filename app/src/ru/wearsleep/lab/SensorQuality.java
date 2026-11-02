package ru.wearsleep.lab;

/** Basic transport/signal checks, not medical or sleep-classification accuracy. */
public final class SensorQuality {
    private SensorQuality() {}

    public static boolean ppg(Integer value, Integer status) {
        return value != null && value != 0 && status != null && status == 0;
    }

    public static boolean heartRate(Integer value, Integer status) {
        return value != null && value > 0 && status != null && status == 1;
    }

    public static Integer onWrist(float value) {
        if (value == 1f) return 1;
        if (value == 0f) return 0;
        return null;
    }

    public static final class Range {
        public long samples, zeroSamples;
        public Integer minimum, maximum;
        public void add(Integer value) {
            if (value == null) return;
            samples++;
            if (value == 0) zeroSamples++;
            minimum = minimum == null ? value : Math.min(minimum, value);
            maximum = maximum == null ? value : Math.max(maximum, value);
        }
        public boolean changes() { return samples > 1 && !minimum.equals(maximum); }
    }
}
