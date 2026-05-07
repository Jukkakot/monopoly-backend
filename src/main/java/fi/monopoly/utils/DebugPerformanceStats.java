package fi.monopoly.utils;

import java.util.List;
import java.util.Locale;

public final class DebugPerformanceStats {
    private final Metric frameMetric = new Metric();
    private final Metric computerStepMetric = new Metric();
    private final Metric historyLayoutMetric = new Metric();
    private final Metric gameViewMetric = new Metric();

    public void recordFrame(long nanos) {
        frameMetric.record(nanos);
    }

    public void recordComputerStep(long nanos) {
        computerStepMetric.record(nanos);
    }

    public void recordHistoryLayout(long nanos) {
        historyLayoutMetric.record(nanos);
    }

    public void recordGameViewBuild(long nanos) {
        gameViewMetric.record(nanos);
    }

    public List<String> overlayLines(float fps, long tintedImageCopies) {
        return List.of(
                "FPS " + formatOneDecimal(fps),
                "Frame avg/max " + frameMetric.formatSummary(),
                "Bot step avg/max " + computerStepMetric.formatSummary(),
                "History avg/max " + historyLayoutMetric.formatSummary(),
                "GameView avg/max " + gameViewMetric.formatSummary(),
                "Tinted copies " + tintedImageCopies
        );
    }

    private static String formatOneDecimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static final class Metric {
        private long count;
        private long totalNanos;
        private long maxNanos;

        private void record(long nanos) {
            if (nanos <= 0) {
                return;
            }
            count++;
            totalNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
        }

        private String formatSummary() {
            if (count == 0) {
                return "- / -";
            }
            return formatMillis(totalNanos / (double) count) + " / " + formatMillis(maxNanos);
        }

        private String formatMillis(double nanos) {
            return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
        }
    }
}
