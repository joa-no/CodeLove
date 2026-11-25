# Offline Android game export

CodeLove exports independently signed Android APKs for LÖVE games on the device, without hosting, a build server or an installed Android SDK.

## Exporting a game

Open a project containing `main.lua`, save your changes and select **Export APK**. Configure the game name, application ID, version name and code, and launcher icon. Generate or restore the game's PKCS#12 signing key, then save the APK through Android's document picker. CodeLove also exports standard `.love` archives.

## Signing and backups

- Keys are associated with the Android application ID, not the project folder.
- New keys require a password of at least 12 characters. CodeLove does not store the password.
- Local keys are kept in the app's private no-backup directory. Keep a separate `.p12` backup and its password before uninstalling CodeLove or clearing its data.
- Existing keys are not automatically overwritten. Restore a key when the application ID has no local signing identity.
- For game updates, retain the application ID and signing identity and increase the version code.

## Implementation

The export pipeline is located in `app/src/normal/java/org/love2d/android/`.

1. CI bundles an unsigned standalone LÖVE runtime template in CodeLove.
2. `GameExportArchive` reads the project via SAF, requires `main.lua`, validates paths and limits, and prepares a temporary `.love` archive.
3. `OfflineGameApk.writeUnsignedAssets` embeds the game, updates the manifest and icon, preserves native library alignment and adds `META-INF/CODELOVE.txt`.
4. `OfflineApkSigner` signs the APK with the game's PKCS#12 identity and verifies its signature.
5. `GameExportApk` saves the APK to the user-selected destination and verifies the written bytes.

Export does not modify the original project files. Exported games contain the standalone runtime, not the CodeLove editor or Sora Editor.

## Compatibility

The offline export flow, game installation and updates, and signing-key backup and restoration have been tested in CI and on Android, including a game with images and audio. Compatibility with every game and device is not guaranteed.

LÖVE may display a version warning if `t.version` in `conf.lua` differs from the bundled runtime. This does not necessarily indicate an export failure. CodeLove does not automatically change that setting.

The in-app exporter produces APKs, not AABs. AAB generation is available separately in the Android build system.

See [dependency inventory and validation](dependency-audit.md) and [runtime licensing and rebuild instructions](rebuild-and-relink.md).
