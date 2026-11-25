# Dependency inventory and validation

CodeLove 1.0.0 was built from revision `702e7665da31ee321fb8bddf8b2adf240015a790` in [build #563](https://github.com/joa-no/CodeLove/actions/runs/45665178367). The signed APK, corresponding source, revision inventory and SHA-256 checksums are available in the [official release](https://github.com/joa-no/CodeLove/releases/tag/v1.0.0).

## JVM dependencies

The following resolved dependency inventory was reviewed during the release audit. These are Gradle graph entries, not proof that every entry contributes executable code. See the flavor-specific JVM notices for exact artifact coordinates.

| Dependency family | Editor | Standalone runtime |
| --- | --- | --- |
| Sora Editor | 0.24.7 | absent |
| apksig | 8.13.2 | absent |
| Bouncy Castle bcprov/bcpkix/bcutil | 1.86 | absent |
| JSpecify | 1.0.0 | absent |
| Kotlin stdlib/common | 2.4.10 | 2.2.10 |
| Kotlin coroutines | 1.6.4 | 1.6.4 |
| JetBrains annotations | 13.0 | 13.0 |
| AndroidX core | 1.15.0 | 1.15.0 |
| AndroidX annotation | 1.11.0 | 1.8.1 |
| AndroidX collection | 1.6.0 | 1.4.2 |
| AndroidX concurrent-futures | 1.1.0 | 1.1.0 |
| Guava listenablefuture | 1.0 | 1.0 |
| Oboe | 1.9.3 | 1.9.3 |

Other AndroidX groups in both graphs: annotation-experimental 1.4.1, arch.core 2.2.0, interpolator 1.0.0, lifecycle 2.6.2, profileinstaller 1.3.0, startup 1.1.1, tracing 1.2.0 and versionedparcelable 1.1.1.

## Native dependencies and notices

The APKs were inspected for SDL3, libc++_shared, liblove, libliblove, LuaJIT, Oboe and OpenAL Soft across arm64-v8a, armeabi-v7a and x86_64. Additional statically linked code is not identifiable from that list alone.

Pinned upstream sources:

- [LÖVE](https://github.com/love2d/love/tree/792690b73dc0c9f84c67c118e295cae1d04195b7)
- [Megasource](https://github.com/love2d/megasource/tree/5118c13ca1dea1890e298c0ce5e410846913a5a8)
- [Sora Editor 0.24.7](https://github.com/Rosemoe/sora-editor/tree/ab9f2cb23aeb30ae42f0ad9e32b281c6bdbcbfc9)

Original license notices from the pinned dependencies, Kotlin and NDK are packaged in the applicable APK assets. CI also generates the exact-version Guava ListenableFuture 1.0 and JetBrains Annotations 13.0 source notice supplement using `tools/collect_jvm_source_notices.py`. Their source JAR SHA-256 digests are:

| Artifact | SHA-256 |
| --- | --- |
| Guava ListenableFuture 1.0 | `3d1bd8d4a39b293612a40e547ec51d9ce34fa638d7adeae83871cdbe2923b161` |
| JetBrains Annotations 13.0 | `42a5e144b8e81d50d6913d1007b695e62e614705268d8cf9f13dbdc478c2c68e` |

The entire Kotlin and NDK notice inventories do not imply that every listed component is shipped.

## Validation

| Evidence | Verified checks |
| --- | --- |
| [Build #555](https://github.com/joa-no/CodeLove/actions/runs/45524405944) | Modified Sora AAR and OpenAL rebuild, markers across all three native ABIs |
| [Build #558](https://github.com/joa-no/CodeLove/actions/runs/45557449911) | Exact-version JVM notices, byte-preserving export and source archive rebuild |
| [Build #562](https://github.com/joa-no/CodeLove/actions/runs/45642338715) | Integrated baseline, release signing, source rebuild and relinking |
| [Export smoke test #23](https://github.com/joa-no/CodeLove/actions/runs/45642338733) | Developer-only Gradle export |
| [Game signing key tests #5](https://github.com/joa-no/CodeLove/actions/runs/45642338584) | Signing identity and backup/restore |
| [Offline APK prototype #20](https://github.com/joa-no/CodeLove/actions/runs/45642338945) | APK assembly and validation |
| [Build #563](https://github.com/joa-no/CodeLove/actions/runs/45665178367) | Official APK signature, packaged notices, editor/runtime separation, archive-only rebuild and modified LGPL libraries |

The separately identified modified-library test APK was tested on Android. The exported StarGuard test APK contained the OpenAL marker across all three ABIs, no Sora classes or marker, and the applicable runtime notices. It was not an official release.

See [rebuild and relink instructions](rebuild-and-relink.md) and [licensing scope](../LICENSING.md). The full historical audit is preserved in [Git history](https://github.com/joa-no/CodeLove/blob/3fcbf4e7dacad2f667e095cbd334de7f36239e40/docs/dependency-audit.md). These checks are technical evidence, not legal certification, a full vulnerability audit or a guarantee of device compatibility.
