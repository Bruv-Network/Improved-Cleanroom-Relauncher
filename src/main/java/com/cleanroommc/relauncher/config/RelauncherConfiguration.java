package com.cleanroommc.relauncher.config;

import com.cleanroommc.relauncher.CleanroomRelauncher;
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
    private int javaVersion = 21;
    @SerializedName("javaVendor")
    private String javaVendor = "adoptium";

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
        return javaVersion <= 0 ? 21 : javaVersion;
    }

    public String getJavaVendor() {
        return (javaVendor == null || javaVendor.isEmpty()) ? "adoptium" : javaVendor.toLowerCase();
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

    public void save() {
        FILE.getParentFile().mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            String nl = System.lineSeparator();
            writer.write("// Cleanroom Relauncher configuration" + nl);
            writer.write("// Available vendors: \"adoptium\" (Temurin), \"graalvm\" (GraalVM Community JDK)" + nl);
            writer.write("// javaVendor: preferred vendor; falls back to adoptium if unavailable for your OS/arch/version" + nl);
            writer.write("// javaVersion: major Java version to use (e.g., 21). Changing this triggers auto re-download and switch" + nl);
            writer.write("// javaPath: optional absolute path to Java executable; leave empty to auto-manage" + nl);
            writer.write("// args: optional JVM arguments appended when launching Cleanroom" + nl);
            GSON.toJson(this, writer);
        } catch (IOException e) {
            CleanroomRelauncher.LOGGER.error("Unable to save config", e);
        }
    }

}
