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

public final class JavaDownloader {

    private JavaDownloader() {}

    public interface ProgressListener {
        void onStart(long totalBytes);
        void onProgress(long downloadedBytes, long totalBytes);
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

    private static String detectMacArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64"; // Apple Silicon
        return "x64"; // Intel Macs
    }

    public static String ensureWindowsJava(Path baseDir, int majorVersion) throws IOException {
        return ensureWindowsJava(baseDir, majorVersion, "adoptium", null);
    }

    public static String ensureWindowsJava(Path baseDir, int majorVersion, ProgressListener progressListener) throws IOException {
        return ensureWindowsJava(baseDir, majorVersion, "adoptium", progressListener);
    }

    public static String ensureWindowsJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener) throws IOException {
        if (majorVersion <= 0) majorVersion = 21;
        String arch = detectWindowsArch();
        Path temDir = baseDir.resolve(String.format("temurin-%d-windows-%s", majorVersion, arch));
        Path graDir = baseDir.resolve(String.format("graalvm-%d-windows-%s", majorVersion, arch));
        boolean wantGraal = vendor != null && vendor.equalsIgnoreCase("graalvm");
        Path javaExe = wantGraal ? findJavaBinary(graDir) : findJavaBinary(temDir);
        if (javaExe != null && Files.isRegularFile(javaExe)) return javaExe.toAbsolutePath().toString();

        String downloadUrl = null;
        String imageTypeUsed = "jre";
        String vendorUsed = null;
        if (vendor != null && vendor.equalsIgnoreCase("graalvm")) {
            try {
                downloadUrl = fetchGraalVMDownloadLink(majorVersion, "windows", arch);
                if (downloadUrl != null) {
                    imageTypeUsed = "jdk";
                    vendorUsed = "graalvm";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve GraalVM JDK {} (windows, {}): {}", majorVersion, arch, e.toString());
            }
        }
        if (downloadUrl == null) {
            try {
                downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "windows", arch, "jre");
                if (downloadUrl != null) {
                    imageTypeUsed = "jre";
                    vendorUsed = "temurin";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JRE via assets API: {}", majorVersion, e.toString());
            }
            if (downloadUrl == null) {
                try {
                    downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "windows", arch, "jdk");
                    if (downloadUrl != null) {
                        imageTypeUsed = "jdk";
                        vendorUsed = "temurin";
                    }
                } catch (IOException e) {
                    CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JDK via assets API: {}", majorVersion, e.toString());
                }
            }
        }
        if (downloadUrl == null || vendorUsed == null) {
            throw new IOException("Unable to resolve Java " + majorVersion + " download URL for Windows " + arch + " from vendor(s)");
        }

        String vendorSlug = vendorUsed;
        Path targetDir = baseDir.resolve(String.format("%s-%d-windows-%s", vendorSlug, majorVersion, arch));
        Files.createDirectories(targetDir);
        Path zipFile = baseDir.resolve(String.format("%s-%d-windows-%s.zip", vendorSlug, majorVersion, arch));

        downloadFollowingRedirectsWithUA(downloadUrl, zipFile, progressListener);

        extractZip(zipFile, targetDir);
        normalizeExtractedRoot(targetDir, majorVersion, imageTypeUsed);

        try { Files.deleteIfExists(zipFile); } catch (IOException ignore) { }

        javaExe = findJavaBinary(targetDir);
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            throw new IOException("Downloaded Java " + majorVersion + " archive did not contain a valid java.exe");
        }
        return javaExe.toAbsolutePath().toString();
    }

    public static String ensureLinuxJava(Path baseDir, int majorVersion) throws IOException {
        return ensureLinuxJava(baseDir, majorVersion, "adoptium", null);
    }

    public static String ensureLinuxJava(Path baseDir, int majorVersion, ProgressListener progressListener) throws IOException {
        return ensureLinuxJava(baseDir, majorVersion, "adoptium", progressListener);
    }

    public static String ensureLinuxJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener) throws IOException {
        if (majorVersion <= 0) majorVersion = 21;
        String arch = detectLinuxArch();
        Path temDir = baseDir.resolve(String.format("temurin-%d-linux-%s", majorVersion, arch));
        Path graDir = baseDir.resolve(String.format("graalvm-%d-linux-%s", majorVersion, arch));
        boolean wantGraal = vendor != null && vendor.equalsIgnoreCase("graalvm");
        Path javaBin = wantGraal ? findJavaBinary(graDir) : findJavaBinary(temDir);
        if (javaBin != null && Files.isRegularFile(javaBin)) return javaBin.toAbsolutePath().toString();

        String downloadUrl = null;
        String imageTypeUsed = "jre";
        String vendorUsed = null;
        if (vendor != null && vendor.equalsIgnoreCase("graalvm")) {
            try {
                downloadUrl = fetchGraalVMDownloadLink(majorVersion, "linux", arch);
                if (downloadUrl != null) {
                    imageTypeUsed = "jdk";
                    vendorUsed = "graalvm";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve GraalVM JDK {} (linux, {}): {}", majorVersion, arch, e.toString());
            }
        }
        if (downloadUrl == null) {
            try {
                downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "linux", arch, "jre");
                if (downloadUrl != null) {
                    imageTypeUsed = "jre";
                    vendorUsed = "temurin";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JRE via assets API (linux): {}", majorVersion, e.toString());
            }
            if (downloadUrl == null) {
                try {
                    downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "linux", arch, "jdk");
                    if (downloadUrl != null) {
                        imageTypeUsed = "jdk";
                        vendorUsed = "temurin";
                    }
                } catch (IOException e) {
                    CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JDK via assets API (linux): {}", majorVersion, e.toString());
                }
            }
        }
        if (downloadUrl == null || vendorUsed == null) {
            throw new IOException("Unable to resolve Java " + majorVersion + " download URL for Linux " + arch + " from vendor(s)");
        }

        String vendorSlug = vendorUsed;
        Path targetDir = baseDir.resolve(String.format("%s-%d-linux-%s", vendorSlug, majorVersion, arch));
        Files.createDirectories(targetDir);
        Path tarGzFile = baseDir.resolve(String.format("%s-%d-linux-%s.tar.gz", vendorSlug, majorVersion, arch));

        downloadFollowingRedirectsWithUA(downloadUrl, tarGzFile, progressListener);

        extractTarGz(tarGzFile, targetDir);
        normalizeExtractedRoot(targetDir, majorVersion, imageTypeUsed);

        try { Files.deleteIfExists(tarGzFile); } catch (IOException ignore) { }

        javaBin = findJavaBinary(targetDir);
        if (javaBin == null || !Files.isRegularFile(javaBin)) {
            throw new IOException("Downloaded Java " + majorVersion + " archive did not contain a valid java binary");
        }
        return javaBin.toAbsolutePath().toString();
    }

    public static String ensureMacJava(Path baseDir, int majorVersion) throws IOException {
        return ensureMacJava(baseDir, majorVersion, "adoptium", null);
    }

    public static String ensureMacJava(Path baseDir, int majorVersion, ProgressListener progressListener) throws IOException {
        return ensureMacJava(baseDir, majorVersion, "adoptium", progressListener);
    }

    public static String ensureMacJava(Path baseDir, int majorVersion, String vendor, ProgressListener progressListener) throws IOException {
        if (majorVersion <= 0) majorVersion = 21;
        String arch = detectMacArch();
        Path temDir = baseDir.resolve(String.format("temurin-%d-mac-%s", majorVersion, arch));
        Path graDir = baseDir.resolve(String.format("graalvm-%d-mac-%s", majorVersion, arch));
        boolean wantGraal = vendor != null && vendor.equalsIgnoreCase("graalvm");
        Path javaBin = wantGraal ? findJavaBinary(graDir) : findJavaBinary(temDir);
        if (javaBin != null && Files.isRegularFile(javaBin)) return javaBin.toAbsolutePath().toString();

        String downloadUrl = null;
        String imageTypeUsed = "jre";
        String vendorUsed = null;
        if (vendor != null && vendor.equalsIgnoreCase("graalvm")) {
            try {
                downloadUrl = fetchGraalVMDownloadLink(majorVersion, "mac", arch);
                if (downloadUrl != null) {
                    imageTypeUsed = "jdk";
                    vendorUsed = "graalvm";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve GraalVM JDK {} (mac, {}): {}", majorVersion, arch, e.toString());
            }
        }
        if (downloadUrl == null) {
            try {
                downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "mac", arch, "jre");
                if (downloadUrl != null) {
                    imageTypeUsed = "jre";
                    vendorUsed = "temurin";
                }
            } catch (IOException e) {
                CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JRE via assets API (mac): {}", majorVersion, e.toString());
            }
            if (downloadUrl == null) {
                try {
                    downloadUrl = fetchAdoptiumDownloadLink(majorVersion, "mac", arch, "jdk");
                    if (downloadUrl != null) {
                        imageTypeUsed = "jdk";
                        vendorUsed = "temurin";
                    }
                } catch (IOException e) {
                    CleanroomRelauncher.LOGGER.warn("Failed to resolve Temurin {} JDK via assets API (mac): {}", majorVersion, e.toString());
                }
            }
        }
        if (downloadUrl == null || vendorUsed == null) {
            throw new IOException("Unable to resolve Java " + majorVersion + " download URL for macOS " + arch + " from vendor(s)");
        }

        String vendorSlug = vendorUsed;
        Path targetDir = baseDir.resolve(String.format("%s-%d-mac-%s", vendorSlug, majorVersion, arch));
        Files.createDirectories(targetDir);
        Path tarGzFile = baseDir.resolve(String.format("%s-%d-mac-%s.tar.gz", vendorSlug, majorVersion, arch));

        downloadFollowingRedirectsWithUA(downloadUrl, tarGzFile, progressListener);

        extractTarGz(tarGzFile, targetDir);
        normalizeExtractedRoot(targetDir, majorVersion, imageTypeUsed);

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

    private static String fetchGraalVMDownloadLink(int majorVersion, String os, String arch) throws IOException {
        String api = "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases?per_page=100";
        URL url = new URL(api);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 CleanroomRelauncher/1.0");
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