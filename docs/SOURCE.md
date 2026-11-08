# Build the patcher

You can rebuild the patcher without the original game or our signing keys. It reuses the patches inside the released APK.

## Tools

- Python 3.10+, JDK 17 or 21, and Gradle 8.10.2 on your PATH.
- Android SDK Platform 35 and Build Tools 35.0.0.
- Android NDK 28.2.13676358 and CMake 3.22.1.

Set `ANDROID_HOME` to your SDK directory. The project uses Android Gradle plugin 8.7.0 and has no Gradle wrapper. The first build may need internet access to fetch dependencies.

## Build

Run these commands from the repo root in PowerShell:

```powershell
python -m venv .venv
./.venv/Scripts/python -m pip install -r requirements.txt
./.venv/Scripts/python tools/release/extract_patch_bundle.py
$bundle = (Resolve-Path ./build/patch-bundle).Path
gradle -p patcher/android :app:assembleDebug "-PpatchBundleDir=$bundle" "-PpatcherVersionCode=8" "-PpatcherVersionName=0.1.1"
```

Output: `patcher/android/app/build/outputs/apk/debug/app-debug.apk`.

The debug patcher installs alongside the released patcher under a separate package ID. This build was checked with the tool versions above.

The extractor verifies the released APK's hash, manifest signature, and patches. It refuses to overwrite `build/patch-bundle`; on later builds, reuse that folder and skip extraction. For another release, pass its trusted hash with `--apk-sha256`.

Rebuilding the patcher doesn't change gameplay: the embedded patches still produce the released game build.

### Your own release build

Use `:app:assembleRelease` with `-PpatchBundleDir`, `-PreleaseStoreFile`, and `-PreleaseKeyAlias`. Set `REDCON_PATCHER_KEYSTORE_PASSWORD` in the environment and keep your key outside the repo. Choose your own version name/code. A build signed with your key cannot update our patcher in place.

## Code layout

- `patcher/android/`: Android UI, file checks, installer, JNI bridge, and Xdelta decoder.
- `mod/src/main/java/redcon/net/`: discovery, pairing, UDP transport, lockstep, and state checks.
- `mod/src/main/java/redcon/duel/`: commanders, orders, fortress designs, and match rules.
- `mod/src/main/java/redcon/desktop/`: shared and Android UI/game integration, despite the folder's name.

The patcher checks the player's original files, applies the binary patches locally, verifies the resulting APKs, and asks Android to install them. It needs no network permission.

The gameplay source includes `AcademyCutsceneWidget.java`, `Duel.java`, and `RecoveredGame.java`, which adapt original game code. `RecoveredGame` defaults to `media/rs.pk` relative to the working directory. A full gameplay build still needs original game files, recovered JARs, and recovery, hook-rewrite, and build tools that are not included here. Release builds also set `DuelBuildFlavor.RELEASE` to `true` to remove developer controls.

## Patch tools

`tools/release/` contains the bundle extractor, `make_patch_bundle.py`, `apply_patch_bundle.py`, and their shared helpers. The command-line encoder/decoder uses the pinned Windows x86-64 Xdelta 3.2.0 binary; the Android patcher builds its decoder from source.

To reconstruct the released game on Windows, put your original base and ARM64 split in `private-input/`, extract the bundle as above, then run:

```powershell
powershell -File tools/release/fetch_xdelta.ps1
./.venv/Scripts/python tools/release/apply_patch_bundle.py --bundle build/patch-bundle --input private-input --output build/reconstructed --xdelta build/release-tools/xdelta3-3.2.0-windows-x86_64/xdelta3.exe --public-key build/patch-bundle/manifest-public-key.der
```

For a new bundle, `make_patch_bundle.py --help` lists the arguments. Supply your original and signed target APKs, a P-256 PKCS#8 manifest key, and a spec based on [patch-spec.example.json](../release-config/patch-spec.example.json). Update its target version, certificate, and build identity to match your files. Encrypted manifest keys use `REDCON_MANIFEST_KEY_PASSWORD`.

Keep signing keys, passwords, original APKs, and reconstructed game APKs out of commits. See [LICENSE](../LICENSE) and [third-party notices](../THIRD-PARTY-NOTICES.txt) for the source licenses.
