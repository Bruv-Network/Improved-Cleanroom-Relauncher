package com.cleanroommc.relauncher.download.cache;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class CacheVerification {

    private CacheVerification() {}

    public static boolean verifyJavaArchive(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) return false;
        String name = archive.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".zip")) {
                return verifyZip(archive);
            } else if (name.endsWith(".tar.gz")) {
                return verifyTarGz(archive);
            } else {
                return true; // unknown type; assume okay
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean verifyZip(Path zipPath) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            byte[] buf = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory()) continue;
                try (InputStream in = new BufferedInputStream(zf.getInputStream(ze))) {
                    int n;
                    long read = 0;
                    while ((n = in.read(buf)) >= 0) {
                        read += n;
                    }
                    if (read < 0) return false;
                }
            }
        }
        return true;
    }

    private static boolean verifyTarGz(Path tarGzPath) throws IOException {
        try (InputStream fis = Files.newInputStream(tarGzPath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GzipCompressorInputStream gis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {
            TarArchiveEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;
                long toRead = entry.getSize();
                if (toRead < 0) toRead = Long.MAX_VALUE; // stream until EOF for unknown sizes
                while (toRead > 0) {
                    int n = tis.read(buf, 0, (int) Math.min(buf.length, Math.max(1, Math.min(toRead, (long) buf.length))));
                    if (n < 0) break;
                    toRead -= n;
                }
            }
        }
        return true;
    }
}
