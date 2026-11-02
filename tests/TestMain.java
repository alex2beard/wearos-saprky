package ru.wearsleep.lab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

public final class TestMain {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(Csv.line(1, "x,y", "a\"b", null).equals("1,\"x,y\",\"a\"\"b\",\n"), "CSV escaping and null");
        check(Csv.list(Arrays.asList(800, 810, null, 790)).equals("800;810;;790"), "IBI list positions");
        StreamStats continuous = new StreamStats(200);
        for (int i = 0; i < 250; i++) continuous.add(10000 + i * 40, i % 10 != 0);
        check(continuous.count == 250 && continuous.valid == 225 && continuous.invalid == 25, "Quality counters");
        check(continuous.gaps == 0 && continuous.maxGapMs == 40, "Batch delivery does not create source gaps");
        check(Math.abs(continuous.frequencyHz() - 25) < 0.0001, "PPG source frequency");
        StreamStats gaps = new StreamStats(200);
        for (long timestamp : new long[] {1000, 1040, 1040, 1020, 2040}) gaps.add(timestamp, true);
        check(gaps.duplicates == 1 && gaps.backwards == 1 && gaps.gaps == 1 && gaps.maxGapMs == 1020, "Gap/duplicate/reordering detection");
        check(new StreamStats(5000).frequencyHz() == 0, "Empty stream frequency");
        check(!SensorQuality.ppg(0, 0), "A zero PPG value is invalid even when the SDK status is zero");
        check(SensorQuality.ppg(12345, 0) && !SensorQuality.ppg(12345, -1), "PPG status validation");
        check(!SensorQuality.ppg(null, 0) && !SensorQuality.ppg(12345, null), "Missing PPG values are invalid");
        check(!SensorQuality.heartRate(0, 0) && !SensorQuality.heartRate(60, 0)
                && SensorQuality.heartRate(60, 1), "Only successful nonzero heart rate is accepted");
        SensorQuality.Range range = new SensorQuality.Range();
        check(!range.changes(), "No variation in an empty PPG stream");
        range.add(0); range.add(0);
        check(range.zeroSamples == 2 && !range.changes(), "Zero PPG stream is identified");
        range.add(100); range.add(110);
        check(range.changes() && range.minimum == 0 && range.maximum == 110, "PPG dynamic range");
        check(SensorQuality.onWrist(1f) == 1 && SensorQuality.onWrist(0f) == 0
                && SensorQuality.onWrist(Float.NaN) == null, "Off-body state is not guessed from unknown values");
        check(RecordingProfile.HR_ONLY.expects("hr") && RecordingProfile.HR_ONLY.expects("acc")
                && !RecordingProfile.HR_ONLY.expects("ppg"), "Heart-rate test does not enable PPG");
        check(RecordingProfile.PPG_ONLY.expects("ppg") && RecordingProfile.PPG_ONLY.expects("acc")
                && !RecordingProfile.PPG_ONLY.expects("hr"), "PPG test does not enable heart rate");
        check(RecordingProfile.fromId("combined").heartRate && RecordingProfile.fromId("combined").ppg,
                "Combined recording keeps both optical streams");
        check(RecordingProfile.PPG_PROBE.expects("ppg") && !RecordingProfile.PPG_PROBE.expects("acc")
                && !RecordingProfile.PPG_PROBE.expects("hr"), "On-demand PPG has no concurrent continuous tracker");
        check(RecordingProfile.PPG_PROBE.onDemand && RecordingProfile.PPG_PROBE.durationMs(false) == 30000,
                "On-demand probe is foreground-only and limited to 30 seconds");
        check(RecordingProfile.select(true, "ppg_probe") == RecordingProfile.HR_ONLY
                && RecordingProfile.select(true, "combined") == RecordingProfile.HR_ONLY,
                "Night mode cannot activate the unverified PPG tracker");
        check(RecordingProfile.select(false, null) == RecordingProfile.HR_ONLY
                && RecordingProfile.select(false, "ppg_probe") == RecordingProfile.PPG_PROBE,
                "Test profile selection and default");
        check(RecordingProfile.HR_ONLY.durationMs(true) == 43200000L
                && RecordingProfile.HR_ONLY.durationMs(false) == 120000L, "Night and heart-rate test limits");
        SensorQuality.PpgChannel channel = new SensorQuality.PpgChannel();
        channel.add(0, 0); channel.add(100, 0); channel.add(100, 0); channel.add(200, -1);
        check(channel.valid == 2 && !channel.changes(), "Startup zeros and error outliers do not establish PPG variation");
        channel.add(110, 0);
        check(channel.changes() && channel.raw.zeroSamples == 1, "Valid PPG variation is tracked separately from raw values");
        File temporary = Files.createTempDirectory("sleep-lab-test").toFile();
        File directory = new File(temporary, "session");
        try {
            SessionStore store = new SessionStore(directory);
            for (int i = 0; i < 1000; i++) store.write("ppg", Csv.line(i * 40, 100000, 200000, i, 0, i + 10, 0, null, null));
            store.write("hr", Csv.line(0, 100000, 200000, 60, 1, "1000;1001", "0;0"));
            store.event(100000, 200000, "test", "comma, quote\" newline\n");
            store.metadata("{\"state\":\"recording\"}");
            store.flush();
            store.close();
            store.metadata("{\"state\":\"completed\"}");
            check(new String(Files.readAllBytes(new File(directory, "metadata.json").toPath()), StandardCharsets.UTF_8)
                    .equals("{\"state\":\"completed\"}"), "Atomic metadata replacement");
            try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(new File(directory, "ppg.csv.gz").toPath()))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) >= 0) bytes.write(buffer, 0, n);
                String rows = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                check(rows.split("\n").length == 1001, "All sensor rows survive compression");
                check(rows.contains("39960,100000,200000,999,0,1009,0,,\n"), "All PPG channels and missing values survive close");
            }
            File archive = new File(temporary, "export.zip");
            SessionStore.exportZip(directory, Files.newOutputStream(archive.toPath()));
            try (ZipFile zip = new ZipFile(archive)) {
                check(zip.size() == 5, "Export includes three streams, events and metadata");
                check(zip.getEntry("metadata.json") != null && zip.getEntry("ppg.csv.gz") != null, "Export filenames");
            }
            System.out.println("PASS: " + checks + " Java checks");
        } finally {
            Files.walk(temporary.toPath()).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }
}
