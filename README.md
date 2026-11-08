# REDCON Multiplayer

An unofficial LAN multiplayer mod for REDCON on Android. 
You need your own copy of the game for it.

**1.0  [Download the patcher](https://github.com/IndexProselyte/redcon-multiplayer/releases/latest)** (2.3 MiB)

## Install

Requires Android 10 or newer, an ARM64 device, and the supported REDCON 2.2.0 base APK and ARM64 split. The patcher checks the exact files; matching the version number alone isn't enough. Allow at least 128 MiB of free space for patching, plus room for the installed app.

1. Install `redcon-pvp-patcher.apk` and open REDCON PvP Patcher.
2. Tap Use installed REDCON. If that fails, choose Choose original game files and select `base.apk` and `split_config.arm64_v8a.apk` from your own backup.
3. Tap Create and install PvP.  If Android asks, allow installs from the patcher, return, and tap the button again. Confirm the installation.
4. Open REDCON PvP. Both players need the same mod version and the same local network. Guest Wi-Fi may block connections between devices.

Patching runs on your phone. The mod installs separately from REDCON and leaves the official app and its saves alone. Campaign saves and purchases aren't transferred.

## Source

The repo includes the [patcher](patcher/android), [patch tools](tools/release), and [96 gameplay source files](mod/src/main/java/redcon). See the [build guide](docs/SOURCE.md).

The patcher is fully buildable here. The gameplay source includes the three engine adapters, but rebuilding the full mod still needs original game files and recovery/build tools that are not included.

## License

[MIT](LICENSE) for this project's code; [third-party notices](THIRD-PARTY-NOTICES.txt) cover the bundled libraries. REDCON and its original content belong to their respective owners. This project is unofficial.
