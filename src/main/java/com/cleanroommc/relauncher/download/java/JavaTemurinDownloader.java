package com.cleanroommc.relauncher.download.java;

import com.cleanroommc.relauncher.CleanroomRelauncher;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class JavaTemurinDownloader {

    private JavaTemurinDownloader() {}

    public interface ProgressListener {
        void onStart(long totalBytes);
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private static String detectLinuxArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        // common x64 aliases: amd64, x86_64
        return "x64";
    }

    private static String detectWindowsArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        // common x64 aliases on Windows too
        return "x64";
    }

    public static String ensureWindowsJava(Path baseDir, int majorVersion) throws IOException {
        return ensureWindowsJava(baseDir, majorVersion, null);
    }

    public static String ensureWindowsJava(Path baseDir, int majorVersion, ProgressListener progressListener) throws IOException {
        if (majorVersion <= 0) majorVersion = 21;
        String arch = detectWindowsArch();
        Path targetDir = baseDir.resolve(String.format("temurin-%d-windows-%s", majorVersion, arch));
        Path javaExe = findJavaBinary(targetDir);
        if (javaExe != null && Files.isRegularFile(javaExe)) {
            return javaExe.toAbsolutePath().toString();
        }

        Files.createDirectories(targetDir);
        Path zipFile = baseDir.resolve(String.format("temurin-%d-windows-%s.zip", majorVersion, arch));

        // Resolve a concrete download URL via Adoptium assets API. Prefer JRE, fallback to JDK.
        String downloadUrl = null;
        try {
            downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "windows", arch, "jre");
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JRE via assets API: {}", majorVersion, e.toString());
        }
        if (downloadUrl == null) {
            try {
                downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "windows", arch, "jdk");
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JDK via assets API: {}", majorVersion, e.toString());
            }
        }
        if (downloadUrl == null) {
            throw new IOException("Unable to resolve Temurin Java " + majorVersion + " download URL for Windows " + arch + " from Adoptium API");
        }

        downloadFollowingRedirectsWithUA(downloadUrl, zipFile, progressListener);

        extractZip(zipFile, targetDir);

        // Clean up archive to save space
        try { Files.deleteIfExists(zipFile); } catch (IOException ignore) { }

        javaExe = findJavaBinary(targetDir);
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            throw new IOException("Downloaded Java " + majorVersion + " archive did not contain a valid java.exe");
        }
        return javaExe.toAbsolutePath().toString();
    }

    public static String ensureLinuxJava(Path baseDir, int majorVersion) throws IOException {
        return ensureLinuxJava(baseDir, majorVersion, null);
    }

    public static String ensureLinuxJava(Path baseDir, int majorVersion, ProgressListener progressListener) throws IOException {
        if (majorVersion <= 0) majorVersion = 21;
        String arch = detectLinuxArch();
        Path targetDir = baseDir.resolve(String.format("temurin-%d-linux-%s", majorVersion, arch));
        Path javaBin = findJavaBinary(targetDir);
        if (javaBin != null && Files.isRegularFile(javaBin)) {
            return javaBin.toAbsolutePath().toString();
        }

        Files.createDirectories(targetDir);
        Path tarGzFile = baseDir.resolve(String.format("temurin-%d-linux-%s.tar.gz", majorVersion, arch));

        String downloadUrl = null;
        try {
            downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "linux", arch, "jre");
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JRE via assets API (linux): {}", majorVersion, e.toString());
        }
        if (downloadUrl == null) {
            try {
                downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "linux", arch, "jdk");
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JDK via assets API (linux): {}", majorVersion, e.toString());
            }
        }
        if (downloadUrl == null) {
            throw new IOException("Unable to resolve Temurin Java " + majorVersion + " download URL for Linux " + arch + " from Adoptium API");
        }

        downloadFollowingRedirectsWithUA(downloadUrl, tarGzFile, progressListener);

        extractTarGz(tarGzFile, targetDir);

        try { Files.deleteIfExists(tarGzFile); } catch (IOException ignore) { }

        javaBin = findJavaBinary(targetDir);
        if (javaBin == null || !Files.isRegularFile(javaBin)) {
            throw new IOException("Downloaded Java " + majorVersion + " archive did not contain a valid java binary");
        }
        return javaBin.toAbsolutePath().toString();
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
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 CleanroomRelauncher/1.0");
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

    private static void downloadFollowingRedirectsWithUA(String urlStr, Path dest, ProgressListener listener) throws IOException {
        String current = urlStr;
        for (int i = 0; i < 7; i++) { // follow up to 7 redirects
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CleanroomRelauncher/1.0");
            conn.setRequestProperty("Accept", "application/octet-stream");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                if (location == null) throw new IOException("Redirect without Location header from " + current);
                current = location;
                continue;
            }
            if (code == 200) {
                long total = -1;
                try {
                    total = Long.parseLong(conn.getHeaderField("Content-Length"));
                } catch (Exception ignore) { total = -1; }
                if (listener != null) listener.onStart(total);
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    long downloaded = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (listener != null) listener.onProgress(downloaded, total);
                    }
                }
                CleanroomRelauncher.LOGGER.info("Downloaded Java from {}", current);
                return;
            }
            throw new IOException("Unexpected HTTP status " + code + " from " + current);
        }
        throw new IOException("Too many redirects while downloading: " + urlStr);
    }
}
