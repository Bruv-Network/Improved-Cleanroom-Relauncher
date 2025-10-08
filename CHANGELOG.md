# Changelog

## [0.3.3] - 2025-10-08

### Fixed
- MacOS Architecture Detection

(Delete config to download the correct version)

## Upstream Changes
### Added
- Respect the launcher's original heap space setting arguments (-Xms, -Xmx) if none is specified in the configuration

### Fixed
- Compatibility with newer MMC pack formats (starting from Cleanroom 0.3.19)
- Encode and decode configuration with UTF-8
- Some Linux installs not being detected properly (updated JavaUtils to 1.1.3)

### Changed
- Thanks to @ZZZank, using MethodHandles to call the wrapper class's main method instead of standard reflection, allows for a cleaner stacktrace

## [0.3.2] - 2025-10-04

### TLDR:
Faster more reliable downloads, auto-update, and auto-cleanup.

### Added
- Multi-chunk parallel Java downloads
- Multi-file parallel library downloads
- Better download speed and ETA tracking for all downloads
- Better download resuming and retrying
- Java download verification
- Auto-Update
- Automatic cleanup of unused temp setup files
- Taskbar icon during download

### Fixed
- Library progress not showing

### Changed
- Default Java version from 21 to 24

## [0.3.1] - 2025-09-13

### Added
- GraalVM support
- Dark mode
- Download Speed and ETA
- Automatic retries
- Partial download resuming

## [0.3.0] - 2025-08-28

### Added
- Java 21 auto-download.
- Architecture detection.
- Full Windows and Linux support for Java auto-download.
- MacOS is untested, if it doesn't work please report it on github with the log file.

## [0.3.0] - 2025-07-08

### Added
- 1hr time-to-live for cached releases, this should be temporary and a better solution may come

### Fixed
- Arguments being piped into relaunched process
- Improved performance with wrapper, as it triggered Windows Defender and hiked CPU percentage

### Changed
- Added exclamation mark at the beginning of file for Forge to load relauncher's coremod earlier
- Simplified checking and replacing of wrapper classes if it ever updates

## [0.2.6] - 2025-05-10

### Fixed
- Arguments not being passed to the relaunching process properly (thanks to @PrototypeTrousers for the fix)

## [0.2.5] - 2025-04-24

### Fixed
- Arguments being left out of the relaunching process

## [0.2.4] - 2025-04-10

### Fixed
- Fix some Java installs not being detected properly on non-Windows systems

### Changed
- Updated JavaUtils

## [0.2.3] - 2025-04-09

### Fixed
- Edge-case when creating file when parent directory may not exist

## [0.2.2] - 2025-04-08

### Fixed
- Some java paths not being properly recognized and parsed

### Changed
- Updated JavaUtils

## [0.2.1] - 2025-04-06

### Added
- More useful debug, logs and dialog messages

### Fixed
- Window not closed properly after the first relaunch

### Changed
- Disallowed Java installs below Java 21 from being detected
- Disallowed Java installs below Java 21 from working

## [0.2.0] - 2025-04-04

### Added
- Auto Java Detection
- Java 9+ Process API usage to check if parent process is alive, much more reliable than older method
- Certs fix for older Java 8 users
- Relauncher now pops up if a newer Cleanroom version is fetched

### Changed
- Configuration now moved to `config/relauncher.json`

### Fixed
- Various GUI scaling, position issues

## [0.1.2] - 2025-03-13

### Fixed
- Malformed jar + wrapper locations being used

## [0.1.1] - 2025-03-12

### Added
- Wrapper class as entrypoint to ensure the relaunched process exits properly when the original process is killed
- Dropdown selector for Cleanroom versions
- Java path selector with a file chooser dialog
- Logo as icon
- Focusing on text field and un-focusing working
- Log when relauncher is exited without specifying a release
- Overhauled component sizing/scaling thanks to @tttsaurus

### Changed
- Relauncher window now has a system conforming look and feel

### Fixed
- The relauncher now appears on the taskbar

## [0.1.0] - 2025-03-07

### Added
- Relaunches Cleanroom's major versions
