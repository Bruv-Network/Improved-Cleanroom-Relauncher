package com.cleanroommc.relauncher;

import com.cleanroommc.javautils.JavaUtils;
import com.cleanroommc.javautils.api.JavaVersion;
import com.cleanroommc.relauncher.config.RelauncherConfiguration;
import com.cleanroommc.relauncher.download.CleanroomRelease;
import com.cleanroommc.relauncher.download.GlobalDownloader;
import com.cleanroommc.relauncher.download.cache.CleanroomCache;
import com.cleanroommc.relauncher.download.java.JavaDownloader;
import com.cleanroommc.relauncher.download.schema.Version;
import com.cleanroommc.relauncher.gui.RelauncherGUI;
import com.cleanroommc.relauncher.gui.SetupProgressDialog;
import com.cleanroommc.relauncher.download.CalculationUtilities;
import com.google.gson.Gson;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.cleanroomrelauncher.ExitVMBypass;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.ProcessIdUtil;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CleanroomRelauncher {

    public static final Logger LOGGER = LogManager.getLogger("CleanroomRelauncher");
    public static final Gson GSON = new Gson();
    public static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), ".cleanroom", "relauncher");

    public static RelauncherConfiguration CONFIG = RelauncherConfiguration.read();

    public CleanroomRelauncher() { }

    private static Integer extractTemurinMajorFromPath(String javaPath) {
        if (javaPath == null || javaPath.isEmpty()) return null;
        String normalized = javaPath.replace('\\', '/');
        try {
            Pattern pat = Pattern.compile("(temurin|graalvm)-(\\d+)-(windows|linux|mac)-(x64|aarch64)", Pattern.CASE_INSENSITIVE);
            Matcher m = pat.matcher(normalized);
            if (m.find()) {
                return Integer.parseInt(m.group(2));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractVendorFromPath(String javaPath) {
        if (javaPath == null || javaPath.isEmpty()) return null;
        String normalized = javaPath.replace('\\', '/');
        try {
            Pattern pat = Pattern.compile("(temurin|graalvm)-(\\d+)-(windows|linux|mac)-(x64|aarch64)", Pattern.CASE_INSENSITIVE);
            Matcher m = pat.matcher(normalized);
            if (m.find()) {
                return normalizeVendorName(m.group(1).toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String normalizeVendorName(String vendor) {
        if (vendor == null || vendor.isEmpty()) return "adoptium";
        String lower = vendor.toLowerCase(Locale.ROOT);
        if (lower.equals("temurin") || lower.equals("adoptium")) {
            return "adoptium";
        }
        return lower;
    }

    private static boolean isCleanroom() {
        try {
            Class.forName("com.cleanroommc.boot.Main");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void replaceCerts() {
        if (JavaVersion.parseOrThrow(System.getProperty("java.version")).build() <= 101) {
            try (InputStream is = CleanroomRelauncher.class.getResource("/cacerts").openStream()) {
                File cacertsCopy = File.createTempFile("cacerts", "");
                cacertsCopy.deleteOnExit();
                FileUtils.copyInputStreamToFile(is, cacertsCopy);
                System.setProperty("javax.net.ssl.trustStore", cacertsCopy.getAbsolutePath());
                CleanroomRelauncher.LOGGER.info("Successfully replaced CA Certs.");
            } catch (Exception e) {
                throw new RuntimeException("Unable to replace CA Certs!", e);
            }
        }
    }

    private static List<CleanroomRelease> releases() {
        try {
            return CleanroomRelease.queryAll();
        } catch (IOException e) {
            throw new RuntimeException("Unable to query Cleanroom's releases and no cached releases found.", e);
        }
    }

    private static List<Version> versions(CleanroomCache cache) {
        try {
            return cache.download(); // Blocking
        } catch (IOException e) {
            throw new RuntimeException("Unable to grab CleanroomVersion to relaunch.", e);
        }
    }

    private static String getOrExtract() {
        String manifestFile = "META-INF/MANIFEST.MF";
        String wrapperDirectory = "wrapper/com/cleanroommc/relauncher/wrapper";
        String wrapperFile = wrapperDirectory + "/RelaunchMainWrapper.class";

        File relauncherJarFile = JavaUtils.jarLocationOf(CleanroomRelauncher.class);

        try (FileSystem containerFs = FileSystems.newFileSystem(relauncherJarFile.toPath(), null)) {
            String originalHash;
            try (InputStream is = Files.newInputStream(containerFs.getPath(manifestFile))) {
                originalHash = new Manifest(is).getMainAttributes().getValue("WrapperHash");
            } catch (Throwable t) {
                throw new RuntimeException("Unable to read original hash of the wrapper class file", t);
            }

            Path cachedWrapperDirectory = CleanroomRelauncher.CACHE_DIR.resolve(wrapperDirectory);
            Path cachedWrapperFile = CleanroomRelauncher.CACHE_DIR.resolve(wrapperFile);

            boolean skip = false;

            if (Files.exists(cachedWrapperFile)) {
                try (InputStream is = Files.newInputStream(cachedWrapperFile)) {
                    String cachedHash = DigestUtils.md5Hex(is);
                    if (originalHash.equals(cachedHash)) {
                        CleanroomRelauncher.LOGGER.warn("Hashes matched, no need to copy from jar again.");
                        skip = true;
                    }
                } catch (Throwable t) {
                    CleanroomRelauncher.LOGGER.error("Unable to calculate MD5 hash to compare.", t);
                }
            }

            if (!skip) {
                if (Files.exists(cachedWrapperDirectory)) {
                    try (Stream<Path> stream = Files.walk(cachedWrapperDirectory)) {
                        stream.filter(Files::isRegularFile).map(Path::toFile).forEach(File::delete);
                    }
                } else {
                    Files.createDirectories(cachedWrapperDirectory);
                }
                Path wrapperJarDirectory = containerFs.getPath("/wrapper/");
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(wrapperJarDirectory)) {
                    for (Path path : stream) {
                        Path to = cachedWrapperFile.resolveSibling(path.getFileName().toString());
                        Files.copy(path, to);
                        CleanroomRelauncher.LOGGER.debug("Moved {} to {}", path.toAbsolutePath().toString(), to.toAbsolutePath().toString());
                    }
                }
            }

            return CleanroomRelauncher.CACHE_DIR.resolve("wrapper").toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Unable to extract relauncher's jar file", e);
        }
    }

    static void run() {
        if (isCleanroom()) {
            LOGGER.info("Cleanroom detected. No need to relaunch!");
            return;
        }

        replaceCerts();

        List<CleanroomRelease> releases = releases();
        CleanroomRelease latestRelease = releases.get(0);

        LOGGER.info("{} cleanroom releases were queried.", releases.size());
  
        CleanroomRelease selected = null;
        String selectedVersion = CONFIG.getCleanroomVersion();
        String notedLatestVersion = CONFIG.getLatestCleanroomVersion();
        String javaPath = CONFIG.getJavaExecutablePath();
        String javaArgs = CONFIG.getJavaArguments();
        boolean autoUpdate = CONFIG.isAutoUpdate();
        boolean needsNotifyLatest = notedLatestVersion == null || !notedLatestVersion.equals(latestRelease.name);
        if (autoUpdate) {
            selected = latestRelease;
            needsNotifyLatest = false;
        } else if (selectedVersion != null) {
            selected = releases.stream().filter(cr -> cr.name.equals(selectedVersion)).findFirst().orElse(null);
        }
        if (javaPath != null && !new File(javaPath).isFile()) {
            javaPath = null;
        }
//        if (javaArgs == null) {
//            javaArgs = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
//        }
        // Perform initial setup if either selection or Java path is missing.
        boolean didAutoSetup = false;
        AtomicReference<SetupProgressDialog> setupDialogRef = new AtomicReference<>(null);
        // Determine desired Java version from config and auto-switch if current path differs
        int desiredJava = CONFIG.getJavaVersion();
        String desiredVendor = normalizeVendorName(CONFIG.getJavaVendor());
        Integer currentJavaFromPath = extractTemurinMajorFromPath(javaPath);
        if (javaPath != null && (currentJavaFromPath == null || currentJavaFromPath.intValue() != desiredJava)) {
            LOGGER.info("Configured Java version {} differs from current Java path ({}). Switching to {} {}...", desiredJava, javaPath, desiredVendor, desiredJava);
            javaPath = null; // trigger auto-setup to fetch the desired Java version
        }
        String currentVendorFromPath = normalizeVendorName(extractVendorFromPath(javaPath));
        if (javaPath != null && currentVendorFromPath != null && !currentVendorFromPath.equalsIgnoreCase(desiredVendor)) {
            LOGGER.info("Configured Java vendor '{}' differs from current vendor '{}' at {}. Switching vendor and re-downloading if necessary...", desiredVendor, currentVendorFromPath, javaPath);
            javaPath = null; // trigger auto-setup to fetch the desired vendor distribution
        }
        boolean initialSetupNeeded = (selected == null) || (javaPath == null);
        if (initialSetupNeeded) {
            didAutoSetup = true;
            if (selected == null) {
                LOGGER.info("No Cleanroom version selected. Auto-selecting latest release {}.", latestRelease.name);
                selected = latestRelease;
            }
            if (javaPath == null) {
                LOGGER.info("No Java path configured. Preparing Java {} for this OS (auto-download)...", desiredJava);
                {
                    SetupProgressDialog dlg = SetupProgressDialog.show("Setting Up Necessary Libraries (Only Happens Once)");
                    setupDialogRef.set(dlg);
                    String vendorName = (desiredVendor != null && desiredVendor.equalsIgnoreCase("graalvm")) ? "GraalVM" : "Temurin";
                    dlg.setMessage("Downloading Java " + desiredJava + " (" + vendorName + ")...");
                    dlg.setIndeterminate(true);
                }
                try {
                    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                    if (os.contains("win")) {
                        javaPath = JavaDownloader.ensureWindowsJava(
                                CleanroomRelauncher.CACHE_DIR.resolve("java"),
                                desiredJava,
                                desiredVendor,
                                new JavaDownloader.ProgressListener() {
                                    private long total = -1;
                                    private final CalculationUtilities.DownloadSpeedCalculator speedCalc = new CalculationUtilities.DownloadSpeedCalculator();
                                    
                                    @Override
                                    public void onStart(long totalBytes) {
                                        this.total = totalBytes;
                                        speedCalc.reset();
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            if (totalBytes > 0) {
                                                dlg.setIndeterminate(false);
                                                dlg.setProgress(0, "0 B/s  ETA --:--");
                                            } else {
                                                dlg.setIndeterminate(true);
                                            }
                                        }
                                    }
                                    @Override
                                    public void onProgress(long downloadedBytes, long totalBytes) {
                                        if (total > 0) {
                                            int pct = (int) ((downloadedBytes * 100L) / total);
                                            SetupProgressDialog dlg = setupDialogRef.get();
                                            if (dlg != null) {
                                                double speed = speedCalc.calculateSpeed(downloadedBytes);
                                                long eta = speedCalc.calculateSmoothedETA(total, downloadedBytes, speed);
                                                dlg.setIndeterminate(false);
                                                dlg.setProgressPercent(pct);
                                                dlg.setMessage(String.format(
                                                        "Downloading Java %d (%s) - %s - ETA: %s",
                                                        desiredJava,
                                                        desiredVendor,
                                                        CalculationUtilities.formatSpeed(speed),
                                                        CalculationUtilities.formatETA(eta)
                                                ));
                                            }
                                        }
                                    }
                                    @Override
                                    public void onRetryScheduled(int attempt, int maxAttempts, long delayMs) {
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            int secs = (int) Math.ceil(delayMs / 1000.0);
                                            String text = String.format(Locale.ROOT, "Retry %d/%d in %ds", attempt, maxAttempts, secs);
                                            dlg.setProgress(0, text);
                                        }
                                    }
                                }
                        );
                    } else if (os.contains("mac")) {
                        javaPath = JavaDownloader.ensureMacJava(
                                CleanroomRelauncher.CACHE_DIR.resolve("java"),
                                desiredJava,
                                desiredVendor,
                                new JavaDownloader.ProgressListener() {
                                    private CalculationUtilities.DownloadSpeedCalculator speedCalculator = new CalculationUtilities.DownloadSpeedCalculator();
                                    private long total = -1;
                                    @Override
                                    public void onStart(long totalBytes) {
                                        this.total = totalBytes;
                                        speedCalculator.reset();
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            if (totalBytes > 0) {
                                                dlg.setIndeterminate(false);
                                                dlg.setProgress(0, "0 B/s  ETA --:--");
                                            } else {
                                                dlg.setIndeterminate(true);
                                            }
                                        }
                                    }
                                    @Override
                                    public void onProgress(long downloadedBytes, long totalBytes) {
                                        if (total > 0) {
                                            int pct = (int) ((downloadedBytes * 100L) / total);
                                            SetupProgressDialog dlg = setupDialogRef.get();
                                            if (dlg != null) {
                                                double speed = speedCalculator.calculateSpeed(downloadedBytes);
                                                long eta = speedCalculator.calculateSmoothedETA(total, downloadedBytes, speed);
                                                dlg.setIndeterminate(false);
                                                dlg.setProgressPercent(pct);
                                                dlg.setMessage(String.format(
                                                        "Downloading Java %d (%s) - %s - ETA: %s",
                                                        desiredJava,
                                                        desiredVendor,
                                                        CalculationUtilities.formatSpeed(speed),
                                                        CalculationUtilities.formatETA(eta)
                                                ));
                                            }
                                        }
                                    }
                                    @Override
                                    public void onRetryScheduled(int attempt, int maxAttempts, long delayMs) {
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            int secs = (int) Math.ceil(delayMs / 1000.0);
                                            String text = String.format(Locale.ROOT, "Retry %d/%d in %ds", attempt, maxAttempts, secs);
                                            dlg.setProgress(0, text);
                                        }
                                    }
                                }
                        );
                    } else if (os.contains("nux") || os.contains("nix") || os.contains("aix") || os.contains("linux")) {
                        javaPath = JavaDownloader.ensureLinuxJava(
                                CleanroomRelauncher.CACHE_DIR.resolve("java"),
                                desiredJava,
                                desiredVendor,
                                new JavaDownloader.ProgressListener() {
                                    private CalculationUtilities.DownloadSpeedCalculator speedCalculator = new CalculationUtilities.DownloadSpeedCalculator();
                                    private long total = -1;
                                    @Override
                                    public void onStart(long totalBytes) {
                                        total = totalBytes;
                                        speedCalculator.reset();
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            if (totalBytes > 0) {
                                                dlg.setIndeterminate(false);
                                                dlg.setProgress(0, "0 B/s  ETA --:--");
                                            } else {
                                                dlg.setIndeterminate(true);
                                            }
                                        }
                                    }
                                    @Override
                                    public void onProgress(long downloadedBytes, long totalBytes) {
                                        if (total > 0) {
                                            int pct = (int) ((downloadedBytes * 100L) / total);
                                            SetupProgressDialog dlg = setupDialogRef.get();
                                            if (dlg != null) {
                                                double speed = speedCalculator.calculateSpeed(downloadedBytes);
                                                long eta = speedCalculator.calculateSmoothedETA(total, downloadedBytes, speed);
                                                dlg.setIndeterminate(false);
                                                dlg.setProgressPercent(pct);
                                                dlg.setMessage(String.format(
                                                        "Downloading Java %d (%s) - %s - ETA: %s",
                                                        desiredJava,
                                                        desiredVendor,
                                                        CalculationUtilities.formatSpeed(speed),
                                                        CalculationUtilities.formatETA(eta)
                                                ));
                                            }
                                        }
                                    }
                                    @Override
                                    public void onRetryScheduled(int attempt, int maxAttempts, long delayMs) {
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            int secs = (int) Math.ceil(delayMs / 1000.0);
                                            String text = String.format(Locale.ROOT, "Retry %d/%d in %ds", attempt, maxAttempts, secs);
                                            dlg.setProgress(0, text);
                                        }
                                    }
                                }
                        );
                    } else {
                        LOGGER.warn("Auto Temurin Java download is not implemented for this OS: {}. Falling back to manual selection.", os);
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to auto-download Java {}: {}", desiredJava, e.toString());
                }
            }

            // Persist whatever we were able to auto-resolve
            if (selected != null) CONFIG.setCleanroomVersion(selected.name);
            CONFIG.setLatestCleanroomVersion(latestRelease.name);
            if (javaPath != null) CONFIG.setJavaExecutablePath(javaPath);
            CONFIG.setJavaArguments(javaArgs);
            // persist desired java version/vendor so users can change them in config pre-first-run
            CONFIG.setJavaVersion(CONFIG.getJavaVersion());
            CONFIG.setJavaVendor(CONFIG.getJavaVendor());
            CONFIG.save();
            // If Java download failed, ensure any progress dialog is closed before falling back to GUI
            if (javaPath == null) {
                SetupProgressDialog dlg = setupDialogRef.getAndSet(null);
                if (dlg != null) dlg.close();
            }
        }

        // Show GUI only if still missing required selections, or if no auto-setup occurred and a newer latest is available
        boolean shouldShowGui = (selected == null || javaPath == null) || (!didAutoSetup && needsNotifyLatest && !autoUpdate);
        if (shouldShowGui) {
            {
                SetupProgressDialog dlg = setupDialogRef.getAndSet(null); // safety: ensure dialog is not left open when showing GUI
                if (dlg != null) dlg.close();
            }
            final CleanroomRelease fSelected = selected;
            final String fJavaPath = javaPath;
            final String fJavaArgs = javaArgs;
            RelauncherGUI gui = RelauncherGUI.show(releases, $ -> {
                $.selected = fSelected;
                $.javaPath = fJavaPath;
                $.javaArgs = fJavaArgs;
                $.autoUpdate = CleanroomRelauncher.CONFIG.isAutoUpdate();
            });

            selected = gui.selected;
            javaPath = gui.javaPath;
            javaArgs = gui.javaArgs;
            if (gui.autoUpdate) {
                selected = latestRelease;
            }

            CONFIG.setCleanroomVersion(selected.name);
            CONFIG.setLatestCleanroomVersion(latestRelease.name);
            CONFIG.setJavaExecutablePath(javaPath);
            CONFIG.setJavaArguments(javaArgs);
            CONFIG.setAutoUpdate(gui.autoUpdate);

            CONFIG.save();
        }

        if (!didAutoSetup && !shouldShowGui && selected != null) {
            CONFIG.setCleanroomVersion(selected.name);
            CONFIG.setLatestCleanroomVersion(latestRelease.name);
            if (javaPath != null) CONFIG.setJavaExecutablePath(javaPath);
            CONFIG.setJavaArguments(javaArgs);
            CONFIG.save();
        }

        CleanroomCache releaseCache = CleanroomCache.of(selected);

        LOGGER.info("Preparing Cleanroom v{} and its libraries...", selected.name);
        SetupProgressDialog dlg = setupDialogRef.get();
        if (dlg == null) {
            dlg = SetupProgressDialog.show("Setting Up Necessary Libraries (Only Happens Once)");
            setupDialogRef.set(dlg);
        }
        dlg.setMessage("Downloading Cleanroom libraries...");
        dlg.setIndeterminate(false);
        dlg.setProgressPercent(0);
        final SetupProgressDialog finalDlg = dlg;
        GlobalDownloader.INSTANCE.setProgressListener(new GlobalDownloader.TaskProgressListener() {
            private int totalFiles = 0;
            private long totalBytes = 0;
            
            @Override
            public void onTotal(int totalFiles, long totalBytes) {
                this.totalFiles = Math.max(1, totalFiles);
                this.totalBytes = totalBytes;
                finalDlg.setProgressPercent(0);
            }
            
            @Override
            public void onProgress(int completedFiles, int totalFiles, long downloadedBytes, long totalBytes, double speed, long eta) {
                int pct = totalBytes > 0 
                    ? (int) ((downloadedBytes * 100L) / totalBytes)
                    : (int) ((completedFiles * 100.0f) / Math.max(1, totalFiles));
                finalDlg.setProgressPercent(pct);
                
                if (totalBytes > 0 && speed > 0) {
                    finalDlg.setMessage(String.format(
                        "Downloading libraries - %d/%d files - %s - ETA: %s",
                        completedFiles,
                        totalFiles,
                        CalculationUtilities.formatSpeed(speed),
                        CalculationUtilities.formatETA(eta)
                    ));
                } else {
                    finalDlg.setMessage(String.format(
                        "Downloading libraries - %d/%d files",
                        completedFiles,
                        totalFiles
                    ));
                }
            }
        });
        List<Version> versions = versions(releaseCache);
        GlobalDownloader.INSTANCE.setProgressListener(null);
        SetupProgressDialog closeDlg = setupDialogRef.getAndSet(null);
        if (closeDlg != null) closeDlg.close();

        String wrapperClassPath = getOrExtract();

        LOGGER.info("Preparing to relaunch Cleanroom v{}", selected.name);
        List<String> arguments = new ArrayList<>();
        arguments.add(javaPath);

        arguments.add("-cp");
        String libraryClassPath = versions.stream()
                .map(version -> version.libraryPaths)
                .flatMap(Collection::stream)
                .collect(Collectors.joining(File.pathSeparator));

        String fullClassPath = wrapperClassPath + File.pathSeparator + libraryClassPath;
        arguments.add(fullClassPath); // Ensure this is not empty

//        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
//            if (!argument.startsWith("-Djava.library.path")) {
//                arguments.add(argument);
//            }
//        }

        if (javaArgs != null && !javaArgs.isEmpty()) {
            Collections.addAll(arguments, javaArgs.split(" "));
        }

        arguments.add("-Dcleanroom.relauncher.parent=" + ProcessIdUtil.getProcessId());
        arguments.add("-Dcleanroom.relauncher.mainClass=" + versions.get(0).mainClass);
        arguments.add("-Djava.library.path=" + versions.stream().map(version -> version.nativesPaths).flatMap(Collection::stream).collect(Collectors.joining(File.pathSeparator)));

        arguments.add("com.cleanroommc.relauncher.wrapper.RelaunchMainWrapper");

        // Forward any extra game launch arguments
        for (Map.Entry<String, String> launchArgument : ((Map<String, String>) Launch.blackboard.get("launchArgs")).entrySet()) {
            arguments.add(launchArgument.getKey());
            arguments.add(launchArgument.getValue());
        }

        arguments.add("--tweakClass");
        arguments.add("net.minecraftforge.fml.common.launcher.FMLTweaker"); // Fixme, gather from Version?

        LOGGER.debug("Relauncher arguments:");
        for (String arg: arguments) {
            LOGGER.debug(arg);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        processBuilder.directory(null);
        processBuilder.inheritIO();

        try {
            Process process = processBuilder.start();

            int exitCode = process.waitFor();
            LOGGER.info("Process exited with code: {}", exitCode);
            ExitVMBypass.exit(exitCode);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
