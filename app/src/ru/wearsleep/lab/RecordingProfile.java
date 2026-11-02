package ru.wearsleep.lab;

/** The optical trackers can be tested independently, without silently enabling the other one. */
public enum RecordingProfile {
    HR_ONLY("hr_only", "Пульс + IBI", false, true),
    PPG_ONLY("ppg_only", "PPG", true, false),
    COMBINED("combined", "PPG + пульс", true, true),
    PPG_PROBE("ppg_probe", "PPG 100 Гц", true, false, true);

    public final String id, label;
    public final boolean ppg, heartRate, onDemand;
    RecordingProfile(String id, String label, boolean ppg, boolean heartRate) {
        this(id, label, ppg, heartRate, false);
    }
    RecordingProfile(String id, String label, boolean ppg, boolean heartRate, boolean onDemand) {
        this.id = id; this.label = label; this.ppg = ppg; this.heartRate = heartRate;
        this.onDemand = onDemand;
    }

    public boolean expects(String stream) {
        return stream.equals("acc") && !onDemand || stream.equals("ppg") && ppg || stream.equals("hr") && heartRate;
    }

    public long durationMs(boolean night) {
        return onDemand ? 30000L : night ? 12 * 60 * 60 * 1000L : 120000L;
    }

    /** Night mode deliberately uses the combination already verified on the target watch. */
    public static RecordingProfile select(boolean night, String id) {
        return night || id == null ? HR_ONLY : fromId(id);
    }

    public static RecordingProfile fromId(String id) {
        for (RecordingProfile profile : values()) if (profile.id.equals(id)) return profile;
        throw new IllegalArgumentException("Unknown recording profile: " + id);
    }
}
