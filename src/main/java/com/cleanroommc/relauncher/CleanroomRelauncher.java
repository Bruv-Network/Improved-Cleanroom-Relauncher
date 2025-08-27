package com.cleanroommc.relauncher;

import com.cleanroommc.javautils.JavaUtils;
import com.cleanroommc.javautils.api.JavaVersion;
import com.cleanroommc.relauncher.config.RelauncherConfiguration;
import com.cleanroommc.relauncher.download.CleanroomRelease;
import com.cleanroommc.relauncher.download.cache.CleanroomCache;
import com.cleanroommc.relauncher.download.schema.Version;
import com.cleanroommc.relauncher.gui.RelauncherGUI;
import com.cleanroommc.relauncher.download.java.JavaTemurinDownloader;
import com.cleanroommc.relauncher.download.GlobalDownloader;
import com.cleanroommc.relauncher.gui.SetupProgressDialog;
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
import java.lang.management.ManagementFactory;
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
            Pattern pat = Pattern.compile("temurin-(\\d+)-windows-x64", Pattern.CASE_INSENSITIVE);
            Matcher m = pat.matcher(normalized);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        boolean needsNotifyLatest = notedLatestVersion == null || !notedLatestVersion.equals(latestRelease.name);
        if (selectedVersion != null) {
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
        Integer currentJavaFromPath = extractTemurinMajorFromPath(javaPath);
        if (javaPath != null && (currentJavaFromPath == null || currentJavaFromPath.intValue() != desiredJava)) {
            LOGGER.info("Configured Java version {} differs from current Java path ({}). Switching to Temurin {}...", desiredJava, javaPath, desiredJava);
            javaPath = null; // trigger auto-setup to fetch the desired Java version
        }
        boolean initialSetupNeeded = (selected == null) || (javaPath == null);
        if (initialSetupNeeded) {
            didAutoSetup = true;
            if (selected == null) {
                LOGGER.info("No Cleanroom version selected. Auto-selecting latest release {}.", latestRelease.name);
                selected = latestRelease;
            }
            if (javaPath == null) {
                LOGGER.info("No Java path configured. Preparing Java {} for Windows (auto-download)...", desiredJava);
                {
                    SetupProgressDialog dlg = SetupProgressDialog.show("Setting Up Necessary Libraries (Only Happens Once)");
                    setupDialogRef.set(dlg);
                    dlg.setMessage("Downloading Java " + desiredJava + " (Temurin)...");
                    dlg.setIndeterminate(true);
                }
                try {
                    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                    if (os.contains("win")) {
                        javaPath = JavaTemurinDownloader.ensureWindowsJava(
                                CleanroomRelauncher.CACHE_DIR.resolve("java"),
                                desiredJava,
                                new JavaTemurinDownloader.ProgressListener() {
                                    private long total = -1;
                                    @Override
                                    public void onStart(long totalBytes) {
                                        this.total = totalBytes;
                                        SetupProgressDialog dlg = setupDialogRef.get();
                                        if (dlg != null) {
                                            if (totalBytes > 0) {
                                                dlg.setIndeterminate(false);
                                                dlg.setProgressPercent(0);
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
                                            if (dlg != null) dlg.setProgressPercent(pct);
                                        }
                                    }
                                }
                        );
                    } else {
                        LOGGER.warn("Auto Temurin Java download is currently implemented for Windows only. Falling back to manual selection for OS: {}", os);
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
            // persist desired java version so users can change it in config pre-first-run
            CONFIG.setJavaVersion(CONFIG.getJavaVersion());
            CONFIG.save();
            // If Java download failed, ensure any progress dialog is closed before falling back to GUI
            if (javaPath == null) {
                SetupProgressDialog dlg = setupDialogRef.getAndSet(null);
                if (dlg != null) dlg.close();
            }
        }

        // Show GUI only if still missing required selections, or if no auto-setup occurred and a newer latest is available
        boolean shouldShowGui = (selected == null || javaPath == null) || (!didAutoSetup && needsNotifyLatest);
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
            });

            selected = gui.selected;
            javaPath = gui.javaPath;
            javaArgs = gui.javaArgs;

            CONFIG.setCleanroomVersion(selected.name);
            CONFIG.setLatestCleanroomVersion(latestRelease.name);
            CONFIG.setJavaExecutablePath(javaPath);
            CONFIG.setJavaArguments(javaArgs);

            CONFIG.save();
        }

        CleanroomCache releaseCache = CleanroomCache.of(selected);

        LOGGER.info("Preparing Cleanroom v{} and its libraries...", selected.name);
        if (didAutoSetup) {
            if (setupDialogRef.get() == null) {
                setupDialogRef.set(SetupProgressDialog.show("Setting Up Necessary Libraries (Only Happens Once)"));
            }
            {
                SetupProgressDialog dlg = setupDialogRef.get();
                if (dlg != null) {
                    dlg.setMessage("Downloading Cleanroom libraries...");
                    dlg.setIndeterminate(false);
                    dlg.setProgressPercent(0);
                }
            }
            GlobalDownloader.INSTANCE.setProgressListener(new GlobalDownloader.TaskProgressListener() {
                private int total = 0;
                @Override
                public void onTotal(int total) {
                    this.total = Math.max(1, total);
                    SetupProgressDialog dlg = setupDialogRef.get();
                    if (dlg != null) dlg.setProgressPercent(0);
                }
                @Override
                public void onCompleted(int completed, int total) {
                    int pct = (int) ((completed * 100.0f) / Math.max(1, total));
                    SetupProgressDialog dlg = setupDialogRef.get();
                    if (dlg != null) dlg.setProgressPercent(pct);
                }
            });
        }
        List<Version> versions = versions(releaseCache);
        if (didAutoSetup) {
            SetupProgressDialog dlg = setupDialogRef.getAndSet(null);
            if (dlg != null) dlg.close();
            GlobalDownloader.INSTANCE.setProgressListener(null);
        }

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
