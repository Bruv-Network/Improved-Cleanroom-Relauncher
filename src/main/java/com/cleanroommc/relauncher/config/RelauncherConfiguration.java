package com.cleanroommc.relauncher.config;

import com.cleanroommc.relauncher.CleanroomRelauncher;
import com.cleanroommc.relauncher.download.java.JavaDownloader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import net.minecraft.launchwrapper.Launch;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class RelauncherConfiguration {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final File FILE = new File(Launch.minecraftHome, "config/relauncher.json");

    public static RelauncherConfiguration read() {
        if (!FILE.exists()) {
            return new RelauncherConfiguration();
        }
        try (Reader baseReader = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
            JsonReader reader = new JsonReader(baseReader);
            reader.setLenient(true); // allow // and /* */ comments
            return GSON.fromJson(reader, RelauncherConfiguration.class);
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.error("Unable to read config", e);
            return new RelauncherConfiguration();
        }
    }

    @SerializedName("selectedVersion")
    private String cleanroomVersion;
    @SerializedName("latestVersion")
    private String latestCleanroomVersion;
    @SerializedName("javaPath")
    private String javaExecutablePath;
    @SerializedName("args")
    private String javaArguments = "";
    @SerializedName("javaVersion")
    private int javaVersion = JavaDownloader.DEFAULT_JAVA_VERSION;
    @SerializedName("javaVendor")
    private String javaVendor = "adoptium";
    @SerializedName("darkMode")
    private boolean darkMode = true;
    @SerializedName("autoUpdate")
    private boolean autoUpdate = false;

    public String getCleanroomVersion() {
        return cleanroomVersion;
    }

    public String getLatestCleanroomVersion() {
        return latestCleanroomVersion;
    }

    public String getJavaExecutablePath() {
        return javaExecutablePath;
    }

    public String getJavaArguments() {
        return javaArguments;
    }

    public int getJavaVersion() {
        return javaVersion <= 0 ? JavaDownloader.DEFAULT_JAVA_VERSION : javaVersion;
    }

    public String getJavaVendor() {
        return (javaVendor == null || javaVendor.isEmpty()) ? "adoptium" : javaVendor.toLowerCase();
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    public void setCleanroomVersion(String cleanroomVersion) {
        this.cleanroomVersion = cleanroomVersion;
    }

    public void setLatestCleanroomVersion(String latestCleanroomVersion) {
        this.latestCleanroomVersion = latestCleanroomVersion;
    }

    public void setJavaExecutablePath(String javaExecutablePath) {
        this.javaExecutablePath = javaExecutablePath.replace("\\\\", "/");
    }

    public void setJavaArguments(String javaArguments) {
        this.javaArguments = javaArguments;
    }

    public void setJavaVersion(int javaVersion) {
        this.javaVersion = javaVersion;
    }

    public void setJavaVendor(String javaVendor) {
        this.javaVendor = (javaVendor == null || javaVendor.isEmpty()) ? "adoptium" : javaVendor.toLowerCase();
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    public void setAutoUpdate(boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
    }

    public void save() {
        FILE.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            String nl = System.lineSeparator();
            // Header
            writer.write("// Cleanroom Relauncher configuration" + nl);
            writer.write("// This file is parsed leniently; comments are allowed and preserved on save." + nl + nl);

            // Begin JSON object
            writer.write("{" + nl);

            // selectedVersion
            writer.write("  // Cleanroom version to launch (auto-selected on first run)" + nl);
            writer.write("  \"selectedVersion\": " + (cleanroomVersion == null ? "null" : ("\"" + escapeJson(cleanroomVersion) + "\"")) + "," + nl);

            // latestVersion
            writer.write("  // Latest Cleanroom version seen by the relauncher (informational)" + nl);
            writer.write("  \"latestVersion\": " + (latestCleanroomVersion == null ? "null" : ("\"" + escapeJson(latestCleanroomVersion) + "\"")) + "," + nl);

            // javaVendor
            writer.write("  // Preferred Java vendor: \"adoptium\" (Temurin) or \"graalvm\" (GraalVM Community JDK)." + nl);
            writer.write("  // If chosen vendor is unavailable for your OS/arch/version, the relauncher falls back to Adoptium automatically." + nl);
            writer.write("  \"javaVendor\": \"" + escapeJson(getJavaVendor()) + "\"," + nl);

            // javaVersion
            writer.write("  // Java major version to use (e.g., " + JavaDownloader.DEFAULT_JAVA_VERSION + "). Changing this triggers auto re-download and switch." + nl);
            writer.write("  \"javaVersion\": " + getJavaVersion() + "," + nl);

            // darkMode
            writer.write("  // UI theme: dark mode on/off (default: true). When enabled, all relauncher UI uses a dark theme." + nl);
            writer.write("  \"darkMode\": " + (isDarkMode() ? "true" : "false") + "," + nl);

            // autoUpdate
            writer.write("  // Automatically switch to the latest Cleanroom release on launch." + nl);
            writer.write("  // When true, the relauncher will always select the latest release, skipping update prompts." + nl);
            writer.write("  \"autoUpdate\": " + (isAutoUpdate() ? "true" : "false") + "," + nl);

            // javaPath
            writer.write("  // Optional absolute path to a Java executable. Leave null/empty to let the relauncher manage Java automatically." + nl);
            String jp = getJavaExecutablePath();
            writer.write("  \"javaPath\": " + ((jp == null || jp.isEmpty()) ? "null" : ("\"" + escapeJson(jp) + "\"")) + "," + nl);

            // args
            writer.write("  // Optional JVM arguments appended when launching Cleanroom." + nl);
            String ja = getJavaArguments();
            writer.write("  \"args\": " + (ja == null ? "\"\"" : ("\"" + escapeJson(ja) + "\"")) + nl);

            writer.write("}" + nl);
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.error("Unable to save config", e);
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

}
