package com.cleanroommc.relauncher.download.java;

import com.cleanroommc.relauncher.CleanroomRelauncher;
import com.cleanroommc.relauncher.download.cache.CacheVerification;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.BitSet;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class JavaDownloader {

    public static final int MINIMUM_JAVA_VERSION = 21;
    public static final int DEFAULT_JAVA_VERSION = 24;
    private static final int MAX_DOWNLOAD_RETRIES = 3;
    private static final long CHUNK_SIZE = 4L * 1024L * 1024L; // 4 MiB
    private static final int CHUNK_TIMEOUT_MINUTES = 10;
    private static final int CHUNK_RETRY_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int REDIRECT_LIMIT = 7;
    private static final int TEST_RANGE_TIMEOUT_MS = 15_000;
    private static final String USER_AGENT = "Mozilla/5.0 CleanroomRelauncher/1.0";

    private JavaDownloader() {}

    public interface ProgressListener {
        void onStart(long totalBytes);
        void onProgress(long downloadedBytes, long totalBytes);
        default void onRetryScheduled(int attempt, int maxAttempts, long delayMs) {}
    }

    private static String detectArch() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        
        if (osName.contains("mac")) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"uname", "-m"});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        line = line.trim().toLowerCase(Locale.ROOT);
                        if (line.equals("arm64") || line.equals("aarch64")) {
                            return "aarch64";
                        }
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                CleanroomRelauncher.LOGGER.warn("Failed to detect Mac architecture via uname, falling back to os.arch: {}", e.toString());
            }
        }
        
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x64";
    }

    public static String ensureWindowsJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener) throws IOException {
        return ensureJava(baseDir, majorVersion, vendor, progressListener, "windows", ".zip");
    }

    public static String ensureLinuxJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener) throws IOException {
        return ensureJava(baseDir, majorVersion, vendor, progressListener, "linux", ".tar.gz");
    }

    public static String ensureMacJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener) throws IOException {
        return ensureJava(baseDir, majorVersion, vendor, progressListener, "mac", ".tar.gz");
    }

    private static String ensureJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener, String os, String archiveExt) throws IOException {
        if (majorVersion <= 0) majorVersion = DEFAULT_JAVA_VERSION;
        String arch = detectArch();
        
        Path temDir = baseDir.resolve(String.format("temurin-%d-%s-%s", majorVersion, os, arch));
        Path graDir = baseDir.resolve(String.format("graalvm-%d-%s-%s", majorVersion, os, arch));
        boolean wantGraal = vendor != null && vendor.equalsIgnoreCase("graalvm");
        Path javaBin = wantGraal ? findJavaBinary(graDir) : findJavaBinary(temDir);
        if (javaBin != null && Files.isRegularFile(javaBin)) {
            return javaBin.toAbsolutePath().toString();
        }

        DownloadInfo downloadInfo = resolveDownloadUrl(majorVersion, os, arch, vendor);
        
        String vendorSlug = downloadInfo.vendorUsed;
        Path targetDir = baseDir.resolve(String.format("%s-%d-%s-%s", vendorSlug, majorVersion, os, arch));
        Files.createDirectories(targetDir);
        Path archiveFile = baseDir.resolve(String.format("%s-%d-%s-%s%s", vendorSlug, majorVersion, os, arch, archiveExt));

        cleanupStalePartialFiles(baseDir, archiveFile.getFileName().toString());
        
        downloadWithVerification(downloadInfo.downloadUrl, archiveFile, progressListener, MAX_DOWNLOAD_RETRIES);

        if (archiveExt.equals(".zip")) {
            extractZip(archiveFile, targetDir);
        } else {
            extractTarGz(archiveFile, targetDir);
        }
        normalizeExtractedRoot(targetDir, majorVersion, downloadInfo.imageTypeUsed);

        try { Files.deleteIfExists(archiveFile); } catch (IOException ignore) { }

        javaBin = findJavaBinary(targetDir);
        if (javaBin == null || !Files.isRegularFile(javaBin)) {
            String binaryName = os.equals("windows") ? "java.exe" : "java";
            throw new IOException("Downloaded Java " + majorVersion + " archive did not contain a valid " + binaryName);
        }
        return javaBin.toAbsolutePath().toString();
    }

    private static class DownloadInfo {
        final String downloadUrl;
        final String imageTypeUsed;
        final String vendorUsed;
        
        DownloadInfo(String downloadUrl, String imageTypeUsed, String vendorUsed) {
            this.downloadUrl = downloadUrl;
            this.imageTypeUsed = imageTypeUsed;
            this.vendorUsed = vendorUsed;
        }
    }

    private static DownloadInfo resolveDownloadUrl(int majorVersion, String os, String arch, String vendor) throws IOException {
        String downloadUrl = null;
        String imageTypeUsed = "jre";
        String vendorUsed = null;
        
        if (vendor != null && vendor.equalsIgnoreCase("graalvm")) {
            try {
                downloadUrl = fetchGraalVMDownloadLink(majorVersion, os, arch);
                if (downloadUrl != null) {
                    imageTypeUsed = "jdk";
                    vendorUsed = "graalvm";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve GraalVM JDK {} ({}, {}): {}", majorVersion, os, arch, e.toString());
            }
        }
        
        if (downloadUrl == null) {
            try {
                downloadUrl = fetchAdoptiumDownloadLink(majorVersion, os, arch, "jre");
                if (downloadUrl != null) {
                    imageTypeUsed = "jre";
                    vendorUsed = "temurin";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JRE via assets API ({}): {}", majorVersion, os, e.toString());
            }
            
            if (downloadUrl == null) {
                try {
                    downloadUrl = fetchAdoptiumDownloadLink(majorVersion, os, arch, "jdk");
                    if (downloadUrl != null) {
                        imageTypeUsed = "jdk";
                        vendorUsed = "temurin";
                    }
                } catch (IOException e) {
                    CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JDK via assets API ({}): {}", majorVersion, os, e.toString());
                }
            }
        }
        
        if (downloadUrl == null || vendorUsed == null) {
            throw new IOException("Unable to resolve Java " + majorVersion + " download URL for " + os + " " + arch + " from vendor(s)");
        }
        
        return new DownloadInfo(downloadUrl, imageTypeUsed, vendorUsed);
    }

    private static Path findJavaBinary(Path root) throws IOException {
        if (!Files.isDirectory(root)) return null;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals("java") || name.equalsIgnoreCase("java.exe");
                    })
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void normalizeExtractedRoot(Path targetDir, int majorVersion, String imageType) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir)) {
            Path root = null;
            int count = 0;
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    root = p;
                    count++;
                }
            }
            if (count == 1 && root != null) {
                String desired = imageType != null && imageType.equalsIgnoreCase("jre")
                        ? String.format("jdk-%d-jre", majorVersion)
                        : String.format("jdk-%d", majorVersion);
                Path desiredPath = targetDir.resolve(desired);
                if (!Files.exists(desiredPath)) {
                    try {
                        Files.move(root, desiredPath, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(root, desiredPath);
                    }
                }
            }
        }
    }

    private static void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(zipFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream os = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void extractTarGz(Path tarGzFile, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(tarGzFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GzipCompressorInputStream gis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir)) {
                    throw new IOException("Tar entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream os = Files.newOutputStream(outPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = tis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                    // Try to preserve executable bit for binaries
                    try {
                        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                        if (Files.getFileStore(outPath).supportsFileAttributeView("posix")) {
                            Files.setPosixFilePermissions(outPath, perms);
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
    }

    private static String fetchAdoptiumDownloadLink(int majorVersion, String os, String arch, String imageType) throws IOException {
        String api = String.format(
                Locale.ROOT,
                "https://api.adoptium.net/v3/assets/latest/%d/hotspot?architecture=%s&heap_size=normal&image_type=%s&os=%s&vendor=eclipse",
                majorVersion, arch, imageType, os
        );
        URL url = new URL(api);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Unexpected HTTP status " + code + " from assets API: " + api);
        }
        try (InputStream in = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(in)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonArray()) return null;
            JsonArray arr = root.getAsJsonArray();
            if (arr.size() == 0) return null;
            // Try to find a ZIP asset
            String zipLink = null;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                JsonObject pkg = null;
                if (obj.has("binary")) {
                    JsonObject binary = obj.getAsJsonObject("binary");
                    if (binary != null && binary.has("package")) {
                        pkg = binary.getAsJsonObject("package");
                    }
                }
                if (pkg == null && obj.has("package")) {
                    pkg = obj.getAsJsonObject("package");
                }
                if (pkg == null) continue;
                String link = pkg.has("link") ? pkg.get("link").getAsString() : null;
                String name = pkg.has("name") ? pkg.get("name").getAsString() : null;
                if ((name != null && (name.toLowerCase(Locale.ROOT).endsWith(".zip") || name.toLowerCase(Locale.ROOT).endsWith(".tar.gz"))) ||
                        (link != null && (link.toLowerCase(Locale.ROOT).endsWith(".zip") || link.toLowerCase(Locale.ROOT).endsWith(".tar.gz")))) {
                    zipLink = link;
                    break;
                }
            }
            if (zipLink != null) return zipLink;

            // Fallback to the first available link if no explicit ZIP found
            JsonObject first = arr.get(0).getAsJsonObject();
            if (first.has("binary")) {
                JsonObject binary = first.getAsJsonObject("binary");
                if (binary != null && binary.has("package")) {
                    JsonObject pkg = binary.getAsJsonObject("package");
                    if (pkg != null && pkg.has("link")) {
                        return pkg.get("link").getAsString();
                    }
                }
            }
            if (first.has("package")) {
                JsonObject pkg = first.getAsJsonObject("package");
                if (pkg != null && pkg.has("link")) {
                    return pkg.get("link").getAsString();
                }
            }
            return null;
        }
    }

    private static String fetchGraalVMDownloadLink(int majorVersion, String os, String arch) throws IOException {
        String api = "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases?per_page=100";
        URL url = new URL(api);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Unexpected HTTP status " + code + " from GitHub releases API");
        }
        String graalOs = os.equals("mac") ? "macos" : os; // windows, linux, macos
        String ext = os.equals("windows") ? ".zip" : ".tar.gz";
        String archKey = arch; // x64 or aarch64
        try (InputStream in = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(in)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonArray()) return null;
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject rel = el.getAsJsonObject();
                if (!rel.has("tag_name")) continue;
                String tag = rel.get("tag_name").getAsString();
                if (tag == null) continue;
                // Tags are like: jdk-21.0.4+8, jdk-21.0.3+7, etc.
                if (!tag.startsWith("jdk-" + majorVersion + ".") && !tag.equals("jdk-" + majorVersion)) continue;
                if (!rel.has("assets")) continue;
                JsonArray assets = rel.getAsJsonArray("assets");
                for (JsonElement ae : assets) {
                    if (!ae.isJsonObject()) continue;
                    JsonObject asset = ae.getAsJsonObject();
                    String name = asset.has("name") ? asset.get("name").getAsString() : null;
                    String dl = asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : null;
                    if (name == null || dl == null) continue;
                    // Example: graalvm-community-jdk-21.0.4_windows-x64_bin.zip
                    //          graalvm-community-jdk-21.0.4_macos-aarch64_bin.tar.gz
                    String probe = "_" + graalOs + "-" + archKey + "_";
                    if (name.startsWith("graalvm-community-jdk-") && name.contains(probe) && name.endsWith(ext)) {
                        return dl;
                    }
                }
            }
            return null;
        }
    }

    private static void downloadWithVerification(String urlStr, Path dest, ProgressListener listener, int maxRetries) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String finalUrl = resolveFinalURL(urlStr);
                ProbeInfo info = probeServer(finalUrl);
                boolean canMulti = info.totalBytes > 0 && info.acceptRanges && testRangeSupport(finalUrl);
                if (canMulti) {
                    if (listener != null) listener.onStart(info.totalBytes);
                    downloadMultiChunk(finalUrl, dest, info.totalBytes, listener);
                } else {
                    // Fallback to single-stream with resume
                    downloadFollowingRedirectsWithUA(finalUrl, dest, listener);
                }
                // Verify archive integrity; if fails, delete and retry
                if (!CacheVerification.verifyJavaArchive(dest)) {
                    CleanroomRelauncher.LOGGER.warn("Archive verification failed for {}. Retrying download...", dest.getFileName().toString());
                    try { Files.deleteIfExists(dest); } catch (IOException ignore) {}
                    last = new IOException("Archive verification failed");
                    continue;
                }
                return; // success
            } catch (IOException e) {
                last = e;
                if (attempt == maxRetries) break;
                long backoff = (long) (2000L * Math.pow(2, attempt));
                long jitter = (long) (backoff * 0.2 * Math.random());
                long sleep = backoff + jitter;
                if (listener != null) {
                    long remaining = sleep;
                    while (remaining > 0) {
                        try { listener.onRetryScheduled(attempt + 1, maxRetries + 1, remaining); } catch (Throwable ignored) {}
                        long tick = Math.min(1000L, remaining);
                        try { Thread.sleep(tick); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        remaining -= tick;
                    }
                } else {
                    try { Thread.sleep(sleep); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw last != null ? last : new IOException("Download failed after verification retries: " + urlStr);
    }

    private static class ProbeInfo {
        final long totalBytes; final boolean acceptRanges; final String finalUrl;
        ProbeInfo(long t, boolean a, String u) { totalBytes = t; acceptRanges = a; finalUrl = u; }
    }

    private static String resolveFinalURL(String urlStr) throws IOException {
        String current = urlStr;
        for (int i = 0; i < REDIRECT_LIMIT; i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(CONNECT_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location header from " + current);
                    current = location;
                    continue;
                }
                return current;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("Too many redirects while resolving: " + urlStr);
    }

    private static ProbeInfo probeServer(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String fin = resolveFinalURL(urlStr);
                return probeServer(fin);
            }
            long total = -1L;
            try { total = Long.parseLong(conn.getHeaderField("Content-Length")); } catch (Exception ignore) { total = -1L; }
            boolean ranges = false;
            String ar = conn.getHeaderField("Accept-Ranges");
            if (ar != null && ar.toLowerCase(Locale.ROOT).contains("bytes")) ranges = true;
            return new ProbeInfo(total, ranges, urlStr);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean testRangeSupport(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TEST_RANGE_TIMEOUT_MS);
            conn.setReadTimeout(TEST_RANGE_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            return code == 206;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void downloadFollowingRedirectsWithUA(String urlStr, Path dest, ProgressListener listener) throws IOException {
        String current = urlStr;
        Path temp = dest.resolveSibling(dest.getFileName().toString() + ".part");
        if (!Files.exists(temp)) {
            Files.createDirectories(temp.getParent());
            Files.createFile(temp);
        }

        for (int i = 0; i < REDIRECT_LIMIT; i++) {
            long existing = 0L;
            try { existing = Files.size(temp); } catch (IOException ignore) { existing = 0L; }
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "application/octet-stream");
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existing + "-");
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location header from " + current);
                    current = location;
                    continue;
                }
                if (code == 206 || code == 200) {
                    long total = -1L;
                    if (code == 206) {
                        String cr = conn.getHeaderField("Content-Range");
                        if (cr != null && cr.startsWith("bytes ")) {
                            int slash = cr.indexOf('/');
                            if (slash > 0 && slash + 1 < cr.length()) {
                                try { total = Long.parseLong(cr.substring(slash + 1).trim()); } catch (Exception ignore) { total = -1L; }
                            }
                        }
                        if (total <= 0) {
                            long remaining = -1L;
                            try { remaining = Long.parseLong(conn.getHeaderField("Content-Length")); } catch (Exception ignore) { remaining = -1L; }
                            if (remaining > 0) total = remaining + existing; else total = -1L;
                        }
                    } else {
                        try { total = Long.parseLong(conn.getHeaderField("Content-Length")); } catch (Exception ignore) { total = -1L; }
                        existing = 0L;
                    }
                    if (listener != null) listener.onStart(total);
                    try (InputStream in = new BufferedInputStream(conn.getInputStream());
                         OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE, existing > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buf = new byte[8192];
                        long downloaded = existing;
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            out.write(buf, 0, n);
                            downloaded += n;
                            if (listener != null) listener.onProgress(downloaded, total);
                        }
                    }
                    try {
                        Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    CleanroomRelauncher.LOGGER.info("Downloaded Java from {}", current);
                    return;
                }
                throw new IOException("Unexpected HTTP status " + code + " from " + current);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("Too many redirects while downloading: " + urlStr);
    }

    // Multi-chunk download with post-download verification and automatic retry.
    private static void downloadMultiChunk(String urlStr, Path dest, long totalBytes, ProgressListener listener) throws IOException {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threads = Math.min(8, cores * 2);
        int totalChunks = (int) ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);

        Path temp = dest.resolveSibling(dest.getFileName().toString() + ".part");
        Path metaFile = dest.resolveSibling(dest.getFileName().toString() + ".part.meta");
        Files.createDirectories(dest.getParent());

        BitSet completedChunks = new BitSet(totalChunks);
        long alreadyDownloaded = 0L;

        if (Files.exists(temp) && Files.exists(metaFile)) {
            try {
                Properties meta = new Properties();
                try (InputStream mis = Files.newInputStream(metaFile)) {
                    meta.load(mis);
                }
                long savedTotal = Long.parseLong(meta.getProperty("totalBytes", "0"));
                int savedChunks = Integer.parseInt(meta.getProperty("totalChunks", "0"));
                if (savedTotal == totalBytes && savedChunks == totalChunks) {
                    String chunksHex = meta.getProperty("completedChunks", "");
                    if (!chunksHex.isEmpty()) {
                        byte[] chunkBytes = hexToBytes(chunksHex);
                        completedChunks = BitSet.valueOf(chunkBytes);
                        for (int i = 0; i < totalChunks; i++) {
                            if (completedChunks.get(i)) {
                                long start = (long) i * CHUNK_SIZE;
                                long end = Math.min(totalBytes - 1, (i + 1) * CHUNK_SIZE - 1);
                                alreadyDownloaded += (end - start + 1);
                            }
                        }
                        CleanroomRelauncher.LOGGER.info("Resuming multi-chunk download: {} of {} chunks completed, {} bytes already downloaded",
                                completedChunks.cardinality(), totalChunks, alreadyDownloaded);
                    }
                } else {
                    CleanroomRelauncher.LOGGER.warn("Metadata mismatch for {}. Starting fresh download.", dest.getFileName().toString());
                    completedChunks.clear();
                    alreadyDownloaded = 0L;
                }
            } catch (Exception e) {
                CleanroomRelauncher.LOGGER.warn("Failed to read download metadata: {}. Starting fresh.", e.toString());
                completedChunks.clear();
                alreadyDownloaded = 0L;
            }
        }

        if (!Files.exists(temp)) {
            try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
                raf.setLength(totalBytes);
            }
        }

        final BitSet[] completedChunksRef = {completedChunks};
        final long finalTotalBytes = totalBytes;
        final int finalTotalChunks = totalChunks;
        final Path finalMetaFile = metaFile;

        AtomicLong downloaded = new AtomicLong(alreadyDownloaded);
        AtomicBoolean failed = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            if (completedChunks.get(i)) {
                continue;
            }
            final int chunkIndex = i;
            final long start = (long) i * CHUNK_SIZE;
            final long end = Math.min(totalBytes - 1, (i + 1) * CHUNK_SIZE - 1);
            futures.add(pool.submit((Callable<Void>) () -> {
                int attempt = 0;
                IOException last = null;
                while (attempt < CHUNK_RETRY_ATTEMPTS && !failed.get()) {
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(urlStr);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        conn.setReadTimeout(CHUNK_TIMEOUT_MINUTES * 60_000);
                        conn.setRequestProperty("User-Agent", USER_AGENT);
                        conn.setRequestProperty("Accept", "application/octet-stream");
                        conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                        int code = conn.getResponseCode();
                        if (code != 206 && code != 200) throw new IOException("Unexpected HTTP " + code + " for range " + start + "-" + end);
                        try (InputStream in = new BufferedInputStream(conn.getInputStream());
                             RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
                            raf.seek(start);
                            byte[] buf = new byte[8192];
                            long toRead = (end - start + 1);
                            while (toRead > 0) {
                                int n = in.read(buf, 0, (int) Math.min(buf.length, toRead));
                                if (n < 0) break;
                                raf.write(buf, 0, n);
                                toRead -= n;
                                long cur = downloaded.addAndGet(n);
                                if (listener != null) {
                                    try { listener.onProgress(cur, finalTotalBytes); } catch (Throwable ignored) {}
                                }
                                if (failed.get()) break;
                            }
                            if (toRead > 0 && !failed.get()) throw new IOException("Early EOF for chunk " + start + "-" + end);
                        }
                        synchronized (completedChunksRef) {
                            completedChunksRef[0].set(chunkIndex);
                            saveMetadata(finalMetaFile, finalTotalBytes, finalTotalChunks, completedChunksRef[0]);
                        }
                        return null;
                    } catch (IOException e) {
                        last = e;
                        attempt++;
                        try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
                failed.set(true);
                throw last != null ? last : new IOException("Failed to download chunk " + start + "-" + end);
            }));
        }

        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get(CHUNK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.set(true);
                pool.shutdownNow();
                throw new IOException("Interrupted while downloading", e);
            } catch (ExecutionException e) {
                failed.set(true);
                pool.shutdownNow();
                throw new IOException("Chunk task failed", e.getCause());
            } catch (TimeoutException e) {
                failed.set(true);
                pool.shutdownNow();
                throw new IOException("Timed out waiting for chunk", e);
            }
        }

        if (failed.get()) {
            throw new IOException("Multi-chunk download failed");
        }

        try {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        try { Files.deleteIfExists(metaFile); } catch (IOException ignore) {}
        CleanroomRelauncher.LOGGER.info("Downloaded (multi-chunk) Java from {}", urlStr);
    }

    private static void saveMetadata(Path metaFile, long totalBytes, int totalChunks, BitSet completedChunks) {
        try {
            Properties meta = new Properties();
            meta.setProperty("totalBytes", String.valueOf(totalBytes));
            meta.setProperty("totalChunks", String.valueOf(totalChunks));
            byte[] chunkBytes = completedChunks.toByteArray();
            meta.setProperty("completedChunks", bytesToHex(chunkBytes));
            try (OutputStream out = Files.newOutputStream(metaFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                meta.store(out, "Multi-chunk download metadata");
            }
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.warn("Failed to save download metadata: {}", e.toString());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void cleanupStalePartialFiles(Path baseDir, String expectedFileName) {
        if (!Files.exists(baseDir)) {
            return;
        }
        
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(path -> {
                String name = path.getFileName().toString();
                return (name.endsWith(".part") || name.endsWith(".part.meta")) && 
                       !name.startsWith(expectedFileName);
            }).forEach(staleFile -> {
                try {
                    Files.deleteIfExists(staleFile);
                    CleanroomRelauncher.LOGGER.info("Cleaned up stale partial file: {}", staleFile.getFileName());
                } catch (IOException e) {
                    CleanroomRelauncher.LOGGER.warn("Failed to delete stale partial file {}: {}", staleFile.getFileName(), e.toString());
                }
            });
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.warn("Failed to cleanup stale partial files: {}", e.toString());
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}