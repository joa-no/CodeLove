# Rebuilding and relinking CodeLove and the game runtime

Status: draft release procedure; the unmodified build is CI-tested. Build 555 (attempt 2) passed the modified-library rebuild, and the maintainer reported normal Android behavior with the separately identified test APK. A source link alone is not a complete corresponding-source distribution.

## Identify the source

Read `META-INF/CODELOVE-SOURCE.txt` from the APK as a ZIP archive. It records the exact CodeLove revision and pinned native submodules. Checkout that revision, not the latest branch:

```sh
git clone https://github.com/joa-no/CodeLove.git
cd CodeLove
git checkout <revision-from-apk>
git submodule update --init --recursive
```

Requirements: JDK 17, Android SDK platform 37, Android NDK 27.3.13750724, CMake 3.21+ and Python 3.9+. For exact release build inputs see app/build.gradle, gradle/wrapper/gradle-wrapper.properties and .github/workflows/main.yml.

## Standalone runtime / OpenAL Soft

OpenAL Soft source is in `app/src/main/cpp/megasource/libs/openal-soft`. Its original COPYING is included in the native notices; preserve applicable LGPL terms. Modify the library there and rebuild the standalone runtime with the same Android ABI configuration. Follow the workflow's placeholder creation and `assembleEmbedNoRecordRelease` command. Replace the embedded game with the original game assets using the offline export tooling. Keep the original game content and notices, and sign the rebuilt APK with a key you control.

## CodeLove editor / Sora Editor

Sora Editor 0.24.7 is used only in the editor. Its upstream source is https://github.com/Rosemoe/sora-editor/tree/0.24.7. A local build of the compatible modified library can be used in place of the normalImplementation Maven dependency; the optional Gradle property `-Pcodelove.localSoraAar=/absolute/path/editor-release.aar` substitutes a locally built AAR. Its pinned upstream transitive dependencies are declared explicitly. The test below validates this route. Build the offline template first, then `assembleNormalRecordDebug` or an independently signed release. Do not use the official CodeLove private signing key; a different signature normally requires a separate installation or uninstalling the original. Back up projects and game signing keys before uninstalling anything.

## Distribution gate

When distributing the release binaries, supply the corresponding source and build/relink materials through the same release location, including the exact submodule sources and relevant library sources and build inputs, with preserved notices. A default GitHub source ZIP may omit submodules and is not sufficient by itself. The interface-compatible modified Sora and OpenAL rebuild and Android smoke test passed in build 555. Before publication, attach the matching source archive, SHA256SUMS and source-inventory.json to the same GitHub Release as the signed APK. Do not rely on expiring Actions artifacts. A game distributor must also preserve the runtime notices and meet applicable redistribution requirements for the actual game APK. No obligation to publish independently authored game content merely because it was created with CodeLove is asserted.

See https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html (especially section 6) and the original OpenAL Soft COPYING. No new license restrictions, network service, or access to private signing keys are introduced by these instructions.

## Candidate source package (CI)

The release CI generates a `codelove-source-candidate` artifact from the exact checkout and its native submodules, with the Sora source under `third-party/sora-editor/`. Verify `SHA256SUMS` and `source-inventory.json` before using it. Extract and enter the `codelove/` directory; the native submodule contents are already present, so no Git checkout is needed for that part. Do not run the provenance-generation script in a Git-less archive; the original revision inventory supplies provenance for this candidate. Build 554 independently rebuilt the archive; full corresponding-source compliance still requires final review. The intentionally public debug key is included only to permit test builds; the release signing secrets are never part of this package.

The candidate now includes the two generated source-identification and NDK notice files. For an unmodified archive rebuild, use `bash tools/rebuild_source_candidate.sh <candidate-directory> <empty-extraction-directory>` from the checkout, or follow the script's commands in the extracted codelove directory. The rebuild uses a configured Android SDK/NDK and resolves Gradle dependencies as needed; it does not require a private signing key or the original Git checkout. The unmodified compilation test is not a claim of bit-identical output; the modified-library smoke test is documented separately.

## Modified library test (CI)

After the unmodified archive rebuild, run `bash tools/test_lgpl_relink.sh <extracted-directory> <test-output-directory>` from the original checkout (or use the script from the extracted source). It edits the extracted Sora CodeEditor source to add a harmless marker method, and the OpenAL renderer identification string. It builds Sora's AAR with its own Gradle wrapper, substitutes that AAR only in the editor, and rebuilds the runtime using the modified OpenAL source. It checks the resulting DEX and all shipped OpenAL ABI binaries for markers and ensures Sora is absent from the embedded game runtime. The test APK uses application ID org.codelove.relinktest and the public debug key; it is not the official release. The test changes are deliberately isolated and never enter the official APK or the source archive. To build your own modified version, make your changes in the extracted source, run Sora's :editor:assembleRelease task, and use the local AAR property when building CodeLove. The maintainer subsequently tested the separate APK on Android and reported normal operation; the exported StarGuard.apk was inspected and contained modified OpenAL binaries for all three ABIs and no Sora classes. See dependency-audit.md for evidence.

The test APK and its exported games are validation artifacts, not official release binaries. Their embedded revision refers to the original source checkout; the isolated test changes are described and reproduced by tools/test_lgpl_relink.sh. Distribute official binaries with the matching unmodified source package and relevant notices.

For a fresh Git checkout, generate the exact-version JVM source notice supplement with `python3 tools/collect_jvm_source_notices.py` before building (requires network access to Maven Central and curl). An extracted source candidate already contains the generated supplement; do not regenerate it for an unmodified rebuild. This is a developer/CI step only, not an Android runtime network requirement.

Build 558 completed the planned exact-version notice and rebuild checks. For the official release, regenerate the source candidate from the same commit as the signed APK and publish both together. The source artifact from build 558 corresponds only to its own revision, not later commits.
