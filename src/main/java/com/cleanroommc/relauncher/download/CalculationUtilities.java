package com.cleanroommc.relauncher.download;

import java.util.LinkedList;
import java.util.Locale;

public final class CalculationUtilities {

    private CalculationUtilities() {}

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "iB";
        return String.format(Locale.ROOT, "%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    public static String formatSpeed(double bytesPerSecond) {
        return formatBytes((long) bytesPerSecond) + "/s";
    }

    public static String formatETA(long seconds) {
        if (seconds < 0) return "--:--";
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    public static class DownloadSpeedCalculator {
        private final LinkedList<long[]> samples = new LinkedList<>();
        private static final long WINDOW_NS = 10_000_000_000L; // 10 seconds
        private double smoothedETA = -1.0;
        private static final double ETA_SMOOTHING_ALPHA = 0.05; // Lower = smoother (0.0 to 1.0)

        public void reset() {
            samples.clear();
            smoothedETA = -1.0;
        }

        public double calculateSpeed(long downloadedBytes) {
            long nowNs = System.nanoTime();
            samples.add(new long[]{nowNs, downloadedBytes});

            while (!samples.isEmpty() && (nowNs - samples.getFirst()[0]) > WINDOW_NS) {
                samples.removeFirst();
            }

            if (samples.size() > 1) {
                long[] oldest = samples.getFirst();
                long elapsedNs = nowNs - oldest[0];
                long bytesInWindow = downloadedBytes - oldest[1];
                double elapsedSec = elapsedNs / 1_000_000_000.0;
                if (elapsedSec > 0) {
                    return bytesInWindow / elapsedSec;
                }
            }
            return 0.0;
        }

        public long calculateSmoothedETA(long totalBytes, long downloadedBytes, double speed) {
            if (speed <= 0) return -1;
            long remaining = Math.max(0L, totalBytes - downloadedBytes);
            double rawETA = remaining / speed;
            
            if (smoothedETA < 0) {
                smoothedETA = rawETA;
            } else {
                smoothedETA = (ETA_SMOOTHING_ALPHA * rawETA) + ((1.0 - ETA_SMOOTHING_ALPHA) * smoothedETA);
            }
            
            return (long) Math.ceil(smoothedETA);
        }
    }
}
