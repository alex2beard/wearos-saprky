package ru.wearsleep.lab;

/** The optical trackers can be tested independently, without silently enabling the other one. */
public enum RecordingProfile {
    HR_ONLY("hr_only", "Пульс + IBI", false, true),
    PPG_ONLY("ppg_only", "PPG", true, false),
    COMBINED("combined", "PPG + пульс", true, true);

    public final String id, label;
    public final boolean ppg, heartRate;
    RecordingProfile(String id, String label, boolean ppg, boolean heartRate) {
        this.id = id; this.label = label; this.ppg = ppg; this.heartRate = heartRate;
    }

    public boolean expects(String stream) {
        return stream.equals("acc") || stream.equals("ppg") && ppg || stream.equals("hr") && heartRate;
    }

    public static RecordingProfile fromId(String id) {
        for (RecordingProfile profile : values()) if (profile.id.equals(id)) return profile;
        throw new IllegalArgumentException("Unknown recording profile: " + id);
    }
}
