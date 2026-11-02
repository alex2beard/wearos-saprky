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
        File temporary = Files.createTempDirectory("sleep-lab-test").toFile();
        File directory = new File(temporary, "session");
        try {
            SessionStore store = new SessionStore(directory);
            for (int i = 0; i < 1000; i++) store.write("ppg", Csv.line(i * 40, 100000, 200000, i, 0));
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
                check(rows.contains("39960,100000,200000,999,0\n"), "Last sample survives close");
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
