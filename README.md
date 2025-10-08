## Improved Cleanroom Relauncher

Makes the Cleanroom Relauncher a much more seamless experience for modpack users and allows modpack creators to not have to worry about setup instructions! It will automatically download the required java version and cleanroom libraries, and configure it automatically for you!

**Supports only the major Cleanroom versions via GitHub releases at the moment.**

**Supports client-side relaunching, server-side soon.**

Relaunches a Forge 1.12.2 instance with Cleanroom Loader, with a streamlined first-run experience and automatic Java setup.

Features
- **High-Performance Parallel Downloads**
    - Multi-chunk Java downloads (up to 8 concurrent chunks with resume support)
    - Multi-file library downloads (up to 8 files simultaneously)
    - Real-time download speed and ETA tracking
    - Automatic retry with exponential backoff
    - Intelligent chunk-level resume after interruption
    - File integrity verification
- **Automatic Java download and setup**
    - Configurable major version (default: 24, minimum: 21)
    - Configurable distribution (default: Adoptium, alternative: GraalVM)
    - Automatic stale partial file cleanup
- **Cross-platform and arch-aware**
    - Windows: x64, ARM64
    - Linux: x64, aarch64
    - macOS: Intel (x64), Apple Silicon (aarch64)
- **First-run automation**
    - Auto-selects the latest Cleanroom Loader release
    - Progress popup with detailed download statistics (speed, ETA, file count)
    - Auto-switch Java when config javaVersion changes
    - Auto-update to the latest Cleanroom release on launch (configurable)
    - Thread-safe concurrent downloads with file deduplication

First run setup
- If no Java path is configured, the relauncher downloads Java for your OS/architecture and config javaVersion using parallel chunk downloads.
- A progress dialog shows detailed download statistics: speed (MB/s), ETA, percentage, and file count.
- Downloads automatically resume if interrupted - no need to start over!
- The latest Cleanroom Loader release is selected automatically (no manual selection needed).
- All libraries are downloaded in parallel (up to 8 simultaneous downloads) for faster setup.

Configuration
- File: config/relauncher.json
- Key options:
    - javaVersion: desired major version (e.g., 24). Default is 24, minimum is 21. Changing this triggers an automatic re-download and switch.
    - javaVendor: preferred distribution - "adoptium" (Temurin) or "graalvm" (GraalVM Community). Default: "adoptium".
    - javaExecutablePath: set if you want to force a specific Java. If it’s not valid or mismatched with javaVersion, the relauncher will auto-download the correct one.
    - autoUpdate: when true, always selects the latest Cleanroom release on launch and updates the selected version automatically. Default: false.

Cache locations
- Java is cached under: &lt;UserHome&gt;/.cleanroom/relauncher/java/&lt;distribution&gt;-&lt;version&gt;-&lt;os&gt;-&lt;arch&gt;
    - Example: ~/.cleanroom/relauncher/java/temurin-21-windows-x64
    - Example: ~/.cleanroom/relauncher/java/graalvm-22-linux-x64
    - Example: ~/.cleanroom/relauncher/java/graalvm-24-mac-aarch64

It offers a GUI on the client for manual configuration when the autoconfiguration fails.

Cleanroom Loader is a new loader developed from the roots of Forge Mod Loader for Minecraft 1.12.2 specifically. It boasts better performance, modern Java support, and offers better experiences for mod developers.