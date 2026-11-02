package ru.wearsleep.lab;

/** Statistics use source timestamps, so batched delivery is not counted as a sensor gap. */
public final class StreamStats {
    public long count, valid, invalid, duplicates, backwards, gaps, maxGapMs;
    public long firstTimestamp = Long.MIN_VALUE, lastTimestamp = Long.MIN_VALUE;
    private long previousTimestamp = Long.MIN_VALUE;
    private final long gapThresholdMs;

    public StreamStats(long gapThresholdMs) { this.gapThresholdMs = gapThresholdMs; }

    public void add(long timestamp, boolean good) {
        count++;
        if (good) valid++; else invalid++;
        if (firstTimestamp == Long.MIN_VALUE) firstTimestamp = timestamp;
        if (previousTimestamp != Long.MIN_VALUE) {
            long gap = timestamp - previousTimestamp;
            if (gap == 0) duplicates++;
            if (gap < 0) backwards++;
            if (gap > gapThresholdMs) gaps++;
            maxGapMs = Math.max(maxGapMs, gap);
        }
        previousTimestamp = timestamp;
        lastTimestamp = timestamp;
    }

    public double frequencyHz() {
        return count > 1 && lastTimestamp > firstTimestamp
                ? (count - 1) * 1000.0 / (lastTimestamp - firstTimestamp) : 0;
    }
}
