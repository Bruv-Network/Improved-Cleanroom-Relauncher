package com.cleanroommc.relauncher.download;

import com.cleanroommc.relauncher.CleanroomRelauncher;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GlobalDownloader {

    public static final GlobalDownloader INSTANCE = new GlobalDownloader();

    private static final int MAX_DOWNLOAD_THREADS = 8;
    private static final int MAX_RETRIES = 3;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final String USER_AGENT = "Mozilla/5.0 CleanroomRelauncher/1.0";

    private final Set<String> queuedFiles = Collections.synchronizedSet(new HashSet<>());
    private final List<DownloadTask> downloadTasks = Collections.synchronizedList(new ArrayList<>());
    private volatile TaskProgressListener progressListener;
    private final AtomicLong totalBytesAcrossFiles = new AtomicLong(0);
    private final AtomicLong downloadedBytesAcrossFiles = new AtomicLong(0);
    private final CalculationUtilities.DownloadSpeedCalculator speedCalculator = new CalculationUtilities.DownloadSpeedCalculator();

    public interface TaskProgressListener {
        void onTotal(int totalFiles, long totalBytes);
        void onProgress(int completedFiles, int totalFiles, long downloadedBytes, long totalBytes, double speed, long eta);
    }

    public void setProgressListener(TaskProgressListener listener) {
        this.progressListener = listener;
    }

    public void from(String source, File destination) {
        String destPath = destination.getAbsolutePath();
        
        synchronized (queuedFiles) {
            if (queuedFiles.contains(destPath)) {
                CleanroomRelauncher.LOGGER.debug("Skipping duplicate download: {}", destPath);
                return;
            }
            queuedFiles.add(destPath);
        }
        
        DownloadTask task = new DownloadTask(source, destination);
        downloadTasks.add(task);
    }

    public void immediatelyFrom(String source, File destination) {
        String destPath = destination.getAbsolutePath();
        
        synchronized (queuedFiles) {
            if (queuedFiles.contains(destPath)) {
                CleanroomRelauncher.LOGGER.debug("File already queued: {}", destPath);
                return;
            }
            queuedFiles.add(destPath);
        }

        try {
            downloadFile(source, destination.toPath(), MAX_RETRIES, null);
            CleanroomRelauncher.LOGGER.debug("Downloaded {} to {}", source, destPath);
        } catch (IOException e) {
            throw new RuntimeException(String.format("Unable to download %s to %s", source, destination), e);
        }
    }

    public void blockUntilFinished() {
        int totalTasks = downloadTasks.size();
        if (totalTasks == 0) {
            CleanroomRelauncher.LOGGER.info("No library downloads queued, all files already cached");
            return;
        }
        
        CleanroomRelauncher.LOGGER.info("Starting download of {} library files...", totalTasks);

        totalBytesAcrossFiles.set(0);
        downloadedBytesAcrossFiles.set(0);
        speedCalculator.reset();

        TaskProgressListener listener = this.progressListener;

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.min(MAX_DOWNLOAD_THREADS, cores * 2);
        ExecutorService sizeCheckExecutor = Executors.newFixedThreadPool(threads);
        List<Future<Long>> sizeFutures = new ArrayList<>();

        for (DownloadTask task : downloadTasks) {
            sizeFutures.add(sizeCheckExecutor.submit(() -> getFileSize(task.source)));
        }
        
        sizeCheckExecutor.shutdown();
        for (Future<Long> future : sizeFutures) {
            try {
                long size = future.get();
                if (size > 0) {
                    totalBytesAcrossFiles.addAndGet(size);
                }
            } catch (Exception e) {
                CleanroomRelauncher.LOGGER.debug("Failed to get file size: {}", e.toString());
            }
        }

        long totalBytes = totalBytesAcrossFiles.get();
        if (listener != null) {
            listener.onTotal(totalTasks, totalBytes);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger lastReported = new AtomicInteger(0);
        List<Future<Void>> futures = new ArrayList<>();

        ProgressCallback progressCallback = (bytesDownloaded) -> {
            long current = downloadedBytesAcrossFiles.addAndGet(bytesDownloaded);
            if (listener != null && totalBytes > 0) {
                double speed = speedCalculator.calculateSpeed(current);
                long eta = speedCalculator.calculateSmoothedETA(totalBytes, current, speed);
                listener.onProgress(completed.get(), totalTasks, current, totalBytes, speed, eta);
            }
        };

        for (DownloadTask task : downloadTasks) {
            futures.add(executor.submit(() -> {
                try {
                    downloadFile(task.source, task.destination.toPath(), MAX_RETRIES, progressCallback);
                    int nowCompleted = completed.incrementAndGet();
                    int percentage = (nowCompleted * 100) / totalTasks;
                    
                    int last = lastReported.get();
                    if (percentage % 10 == 0 && percentage != last && lastReported.compareAndSet(last, percentage)) {
                        CleanroomRelauncher.LOGGER.info("Download Progress: {} / {} files | {}% completed.", nowCompleted, totalTasks, percentage);
                    }
                    
                    CleanroomRelauncher.LOGGER.debug("Downloaded {} to {}", task.source, task.destination.getAbsolutePath());
                } catch (IOException e) {
                    throw new RuntimeException(String.format("Failed to download %s to %s", task.source, task.destination), e);
                }
                return null;
            }));
        }

        executor.shutdown();
        
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                throw new RuntimeException("Download interrupted", e);
            } catch (ExecutionException e) {
                executor.shutdownNow();
                throw new RuntimeException("Download failed", e.getCause());
            }
        }

        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    CleanroomRelauncher.LOGGER.error("Executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        CleanroomRelauncher.LOGGER.info("All {} library files downloaded successfully", totalTasks);
        
        downloadTasks.clear();
        queuedFiles.clear();
    }

    private static long getFileSize(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            
            int code = conn.getResponseCode();
            if (code == 200) {
                try {
                    return Long.parseLong(conn.getHeaderField("Content-Length"));
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return -1;
    }

    private static void downloadFile(String urlStr, Path dest, int maxRetries, ProgressCallback progressCallback) throws IOException {
        IOException lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Files.createDirectories(dest.getParent());
                
                URL url = new URL(urlStr);
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new IOException("HTTP " + code + " from " + urlStr);
                    }
                    
                    long expectedSize = -1;
                    try {
                        expectedSize = Long.parseLong(conn.getHeaderField("Content-Length"));
                    } catch (Exception ignore) {}
                    
                    Path temp = dest.resolveSibling(dest.getFileName().toString() + ".tmp");
                    try (InputStream in = new BufferedInputStream(conn.getInputStream());
                         OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                        byte[] buffer = new byte[8192];
                        int n;
                        long downloaded = 0;
                        while ((n = in.read(buffer)) >= 0) {
                            out.write(buffer, 0, n);
                            downloaded += n;
                            if (progressCallback != null) {
                                progressCallback.onProgress(n);
                            }
                        }
                        
                        if (expectedSize > 0 && downloaded != expectedSize) {
                            throw new IOException(String.format("Size mismatch: expected %d bytes, got %d bytes", expectedSize, downloaded));
                        }
                    }
                    
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
                    return;
                    
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            } catch (IOException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long backoff = (long) (1000L * Math.pow(2, attempt));
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted during retry", ie);
                    }
                }
            }
        }
        
        throw lastException != null ? lastException : new IOException("Download failed: " + urlStr);
    }

    @FunctionalInterface
    private interface ProgressCallback {
        void onProgress(long bytesDownloaded);
    }

    private static class DownloadTask {
        final String source;
        final File destination;
        
        DownloadTask(String source, File destination) {
            this.source = source;
            this.destination = destination;
        }
    }
}
