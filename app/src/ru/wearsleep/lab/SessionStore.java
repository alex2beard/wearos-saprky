package ru.wearsleep.lab;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** All writes run on the recording worker. Open streams are flushed every 20 seconds. */
public final class SessionStore implements AutoCloseable {
    public final File directory;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private BufferedWriter events;

    public SessionStore(File directory) throws IOException {
        this.directory = directory;
        if (!directory.mkdirs()) throw new IOException("Cannot create session directory");
        try {
            open("ppg", "source_timestamp_ms,received_wall_ms,received_elapsed_ns,green_adc,green_status");
            open("hr", "source_timestamp_ms,received_wall_ms,received_elapsed_ns,bpm,hr_status,ibi_ms,ibi_status");
            open("acc", "source_timestamp_ms,received_wall_ms,received_elapsed_ns,x_raw,y_raw,z_raw");
            events = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(directory, "events.csv")), StandardCharsets.UTF_8));
            events.write("wall_ms,elapsed_ns,event,detail\n");
            events.flush();
        } catch (IOException e) { close(); throw e; }
    }

    private void open(String name, String header) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                new FileOutputStream(new File(directory, name + ".csv.gz")), 32768, true),
                StandardCharsets.UTF_8), 65536);
        writers.put(name, writer);
        writer.write(header + "\n");
    }

    public void write(String stream, String row) throws IOException { writers.get(stream).write(row); }

    public void event(long wallMs, long elapsedNs, String name, String detail) throws IOException {
        events.write(Csv.line(wallMs, elapsedNs, name, detail));
        events.flush();
    }

    public void flush() throws IOException {
        for (BufferedWriter writer : writers.values()) writer.flush();
        if (events != null) events.flush();
    }

    public void metadata(String json) throws IOException {
        File temporary = new File(directory, "metadata.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temporary.renameTo(new File(directory, "metadata.json"))) {
            throw new IOException("Cannot save metadata");
        }
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        for (BufferedWriter writer : writers.values()) {
            try { writer.close(); } catch (IOException e) { failure = e; }
        }
        writers.clear();
        if (events != null) {
            try { events.close(); } catch (IOException e) { failure = e; }
            events = null;
        }
        if (failure != null) throw failure;
    }

    public static void exportZip(File directory, OutputStream output) throws IOException {
        File[] files = directory.listFiles(File::isFile);
        if (files == null) throw new IOException("Session directory is unavailable");
        Arrays.sort(files);
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setLevel(0); // Sensor streams are already gzip compressed.
            byte[] buffer = new byte[65536];
            for (File file : files) {
                if (file.getName().endsWith(".tmp")) continue;
                zip.putNextEntry(new ZipEntry(file.getName()));
                try (FileInputStream in = new FileInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
    }
}
