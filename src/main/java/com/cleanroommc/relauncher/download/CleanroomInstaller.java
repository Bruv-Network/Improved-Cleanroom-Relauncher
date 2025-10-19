package com.cleanroommc.relauncher.download;

import com.cleanroommc.relauncher.download.cache.CleanroomCache;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

public class CleanroomInstaller implements CleanroomZipArtifact {

    public static CleanroomInstaller of(String version, Path location) {
        return new CleanroomInstaller(version, location);
    }

    private final String version;
    private final Path location;

    private CleanroomInstaller(String version, Path location) {
        this.version = version;
        this.location = location;
    }

    @Override
    public void install(String url) {
        if (!Files.exists(this.location)) {
            GlobalDownloader.INSTANCE.immediatelyFrom(url, this.location.toFile());
        }
    }

    @Override
    public void extract(CleanroomCache cache) throws IOException {
        try (FileSystem jar = FileSystems.newFileSystem(this.location, null)) {
            Path versionJsonPath = jar.getPath("/version.json");
            if (versionJsonPath != null && Files.exists(versionJsonPath)) {
                Files.copy(versionJsonPath, cache.getVersionJson(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Path universalJarPath = jar.getPath("/maven/com/cleanroommc/cleanroom/" + this.version + "/cleanroom-" + this.version + ".jar");
            if (universalJarPath != null && Files.exists(universalJarPath)) {
                Files.copy(universalJarPath, cache.getUniversalJar(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

}
