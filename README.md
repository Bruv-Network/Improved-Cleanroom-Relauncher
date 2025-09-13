## Improved Cleanroom Relauncher

**Supports only the major Cleanroom versions via GitHub releases at the moment.**

**Supports client-side relaunching, server-side soon.**

Relaunches a Forge 1.12.2 instance with Cleanroom Loader, with a streamlined first-run experience and automatic Java setup.

Features
- Automatic Temurin Java download and setup
  - Configurable major version (default: 21)
  - Uses Adoptium assets API (prefers JRE; falls back to JDK)
- Optional vendor selection
  - Choose between Adoptium (Temurin) and GraalVM Community JDK
  - If GraalVM is selected but an asset for your OS/arch/major isn't available, it automatically falls back to Adoptium
- Cross-platform and arch-aware
  - Windows: x64, ARM64
  - Linux: x64, aarch64
  - macOS: Intel (x64), Apple Silicon (aarch64)
- First-run automation
  - Auto-selects the latest Cleanroom Loader release
  - Progress popup: "Setting Up Necessary Libraries (Only Happens Once)" with download progress
- Auto-switch Java when config javaVersion changes

First run setup
- If no Java path is configured, the relauncher downloads Temurin Java for your OS/architecture and config javaVersion (default: 21).
- A progress dialog shows the download status and then the archive is extracted automatically.
- The latest Cleanroom Loader release is selected automatically (no manual selection needed).

Configuration
- File: config/relauncher.json
- Key options:
  - javaVersion: desired major version (e.g., 21). Changing this triggers an automatic re-download and switch.
  - javaExecutablePath: set if you want to force a specific Java. If it’s not valid or mismatched with javaVersion, the relauncher will auto-download the correct one.
  - javaVendor: "adoptium" (default) or "graalvm". If "graalvm" is chosen and no matching asset is found, the relauncher falls back to Adoptium automatically.
  - The generated config file includes explanatory comments and is parsed leniently so the comments are preserved.

Cache locations
- Java is cached under: ~/.cleanroom/relauncher/java/temurin-<version>-<os>-<arch>
  - Example: ~/.cleanroom/relauncher/java/temurin-21-windows-x64
  - Example: ~/.cleanroom/relauncher/java/temurin-21-linux-aarch64
  - Example: ~/.cleanroom/relauncher/java/temurin-21-mac-aarch64
  - If vendor is GraalVM, cache path uses graalvm-<version>-<os>-<arch>
    - Example: ~/.cleanroom/relauncher/java/graalvm-21-windows-x64
  - Inside each of these, the extracted JDK/JRE directory is normalized to jdk-<major> or jdk-<major>-jre (e.g., jdk-21-jre), independent of the original archive folder name.

It offers a GUI on the client for manual configuration when the autoconfiguration fails.

Cleanroom Loader is a new loader developed from the roots of Forge Mod Loader for Minecraft 1.12.2 specifically. It boasts better performance, modern Java support, and offers better experiences for mod developers.