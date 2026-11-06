package com.kadr.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure Java ranking and non-overlapping window selection, shared by all episodes. */
public final class MomentSelector {
    public static final class Sample {
        public final long timeMs;
        public final double score;
        public Sample(long timeMs, double score) { this.timeMs = timeMs; this.score = score; }
    }
    public static final class Window {
        public final long startMs, endMs;
        public final double score;
        public Window(long startMs, long endMs, double score) {
            this.startMs = startMs; this.endMs = endMs; this.score = score;
        }
    }
    private MomentSelector() {}
    public static List<Window> select(List<Sample> input, long durationMs, long lengthMs, int count, boolean calm) {
        List<Window> result = new ArrayList<>();
        if (durationMs <= 0 || lengthMs <= 0 || count <= 0 || input.isEmpty()) return result;
        long length = Math.min(durationMs, lengthMs);
        List<Sample> samples = new ArrayList<>(input);
        samples.sort(calm ? Comparator.comparingDouble(s -> s.score)
                : Comparator.comparingDouble((Sample s) -> s.score).reversed());
        for (Sample sample : samples) {
            long start = Math.max(0, Math.min(durationMs - length, sample.timeMs - length / 3));
            long end = start + length;
            boolean overlap = false;
            for (Window existing : result) {
                if (start < existing.endMs && end > existing.startMs) { overlap = true; break; }
            }
            if (!overlap) result.add(new Window(start, end, sample.score));
            if (result.size() == count) break;
        }
        result.sort(Comparator.comparingLong(w -> w.startMs));
        return result;
    }
}
