# CodeLove 1.0.0

The first public release of CodeLove: a native, code-first Lua and LÖVE workspace for Android.

## Features

- Lua and LÖVE syntax highlighting, autocomplete, parameter hints, auto-pairs, indentation and line numbers powered by Sora Editor 0.24.7.
- Direct project-folder access through Android's Storage Access Framework, file tabs and explicit saving.
- Integrated LÖVE runtime with an in-game console and collapsible, translucent runtime controls.
- Offline export of standard `.love` archives and standalone Android APKs, with configurable application identity, version, icon and individual signing keys.
- Offline generation, backup and restoration of per-game PKCS#12 signing identities.
- Portrait and landscape layouts, light/dark/system themes and English, Portuguese and Spanish localization.

## Installation

Download and install the signed **CodeLove-1.0.0.apk** from this release. Android 6.0 (API 23) is the configured minimum; compatibility with every supported device has not been verified. Native ABIs: arm64-v8a, armeabi-v7a and x86_64.

Debug and official Release signatures differ. Before uninstalling a previous build, back up your projects, per-game signing keys and passwords.

## Important notes

- Keep the original application ID and signing identity and increase the version code when updating an exported game. Lost signing keys cannot be recovered without a valid backup.
- Text editing supports files up to 512 KiB; export supports up to 512 MiB of project content and 10,000 entries, subject to available storage and archive limits.
- SAF does not guarantee atomic writes across all providers. Keep independent project backups.
- No account, hosting or cloud build service is required for offline APK export. AAB export from the app is not included.

## Source and licenses

Original editor/tooling contributions: GPL-3.0-only. Original standalone runtime contributions: zlib. LÖVE and third-party components retain their own licenses. See [LICENSING.md](https://github.com/joa-no/CodeLove/blob/702e7665da31ee321fb8bddf8b2adf240015a790/LICENSING.md).

The matching source archive, source inventory and SHA-256 checksums accompany the signed APK. The automatic GitHub source ZIP alone is not the complete corresponding-source package.

Build: [#563](https://github.com/joa-no/CodeLove/actions/runs/45665178367) · Source revision: `702e7665da31ee321fb8bddf8b2adf240015a790`.

CodeLove is an independent modified distribution based on LÖVE and is not endorsed by the LÖVE project.
