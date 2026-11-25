# Release dependency audit — stage 5

Evidence: [build 547](https://github.com/joa-no/CodeLove/actions/runs/45311185741), dependency-audit-reports. Both normal variants resolve the same dependency coordinates; both embed variants resolve the same coordinates. These are Gradle graph entries, not proof that every entry contributes executable code.

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

## Remaining release gates

- Collect original LICENSE/NOTICE files from the resolved AAR/JAR files, including nested JARs. Absence of embedded notices is not evidence that no license applies. Check upstream sources for the exact resolved versions, including Kotlin third-party notices.
- Inventory shipped native libraries and recursively pinned source revisions. Reconcile with upstream licenses and attribution requirements.
- Establish corresponding-source distribution and a practical rebuild/relink procedure for LGPL-covered components (notably Sora Editor and OpenAL Soft). A license notice or dynamic linkage alone does not prove compliance. Consult the [LGPL 2.1, section 6](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).
- Package verified notices in the correct flavor. Ensure exported-game users can access the runtime source and license information without relying on editor licensing.
- Do not publish until these gates are addressed. No dependency upgrades or application behavior changes are part of this audit.

## Evidence from build 548

[Build 548](https://github.com/joa-no/CodeLove/actions/runs/45315647557) resolved 29 distinct Maven artifact coordinates; seven contained at least one candidate original notice. A class file named LICENSE.class was incorrectly counted as a notice and is now excluded. Missing notices require source review, not automatic classification.

Both APKs contained the same seven native shared libraries for arm64-v8a, armeabi-v7a and x86_64: SDL3, libc++_shared, liblove, libliblove, LuaJIT, Oboe and OpenAL Soft. Additional statically linked code is not identifiable from that list alone.

Pinned upstream sources:
- LÖVE: [792690b73dc0c9f84c67c118e295cae1d04195b7](https://github.com/love2d/love/tree/792690b73dc0c9f84c67c118e295cae1d04195b7)
- Megasource: [5118c13ca1dea1890e298c0ce5e410846913a5a8](https://github.com/love2d/megasource/tree/5118c13ca1dea1890e298c0ce5e410846913a5a8)

The shared asset `assets/native-license-notices.txt` supplements the existing notices with 16 original files from this exact Megasource revision, including SDL3, LuaJIT, zlib, Xiph libraries, FreeType, HarfBuzz, libmodplug and OpenAL Soft. Both APKs must contain the identical asset. OpenAL Soft's COPYING is the GNU Library GPL version 2; retain the original text and review applicable version elections and relinking obligations. This is not a complete native notice inventory: NDK libc++ and embedded LÖVE libraries still require reconciliation, as do Maven artifacts and corresponding-source/relink instructions. No library version was changed.

## Evidence from build 549

[Build 549](https://github.com/joa-no/CodeLove/actions/runs/45459608322) passed the native notice asset tests in both APK flavors. The collected dependency inventory contains 29 unique coordinates (26 editor, 20 runtime, 17 common). Seven coordinates have original embedded notice files: three AndroidX artifacts share one Apache license text, the three Bouncy Castle artifacts share one MIT text, and apksig has its own Apache notice.

Both flavors now have separate `assets/jvm-dependency-notices.txt` files. These list the exact resolved coordinates and include the verified upstream license texts for Kotlin (different versions per flavor), coroutines, Oboe, Sora, JSpecify and Bouncy Castle, plus the AndroidX license and candidate apksig text. Guava ListenableFuture and JetBrains annotations declare Apache-2.0 in their artifact metadata; the exact version source notices still need review. The next build must prove that every original notice from the resolved artifacts is present verbatim in the relevant flavor, and that the inventory and packaged asset match. This check is not an assertion that the full upstream notice obligations have been met.

Remaining gates include exact-version upstream copyright/NOTICE and Kotlin third-party code review, native libc++ and LÖVE notices, readable access to license information, and LGPL/GPL corresponding-source and practical relinking distribution. No dependency versions or app behavior were changed.

## Evidence from build 550

[Build 550](https://github.com/joa-no/CodeLove/actions/runs/45464358064) passed the JVM notice packaging and original-notice checks. Next: record exact source revisions in both APKs and verify notice preservation using the production Java export assembler. The [rebuild and relink draft](rebuild-and-relink.md) documents pending corresponding-source and modified-library tests; these remain release blockers. Source identification does not by itself establish LGPL compliance. Native NDK and remaining upstream notices are still open.

## Evidence from build 551

[Build 551](https://github.com/joa-no/CodeLove/actions/runs/45479161483) passed source revision packaging and actual Java direct-asset export notice-preservation tests in Debug and Release. A candidate source archive is now prepared in release CI from exact committed CodeLove and native submodule revisions plus Sora Editor 0.24.7 (commit ab9f2cb23aeb30ae42f0ad9e32b281c6bdbcbfc9). The source candidate is not published automatically. It is restricted to committed files, excludes unexpected signing-key file types, includes the intentionally public debug test key, and records a SHA-256 digest and revision inventory. Rebuild from the archive and modified-library tests remain open. The same run collects actual NDK notice candidates for manual reconciliation rather than treating every NDK tool notice as applicable to the shipped libc++ runtime.

## Evidence from build 552

[Build 552](https://github.com/joa-no/CodeLove/actions/runs/45487651193): Release APK build and license checks passed, but source archive creation rejected the uninitialized Sora Oniguruma submodule (`oniguruma-native/src/main/cpp/oniguruma`). Debug was cancelled by matrix fail-fast. The corrected workflow initializes Sora submodules recursively, rewriting the public SSH URL to HTTPS for this command only, without requiring an SSH key. Existing gitlink verification remains in place. No dependency versions or application code changed.

## Evidence from build 553

[Build 553](https://github.com/joa-no/CodeLove/actions/runs/45490897334) passed recursive source collection and produced the candidate source artifact (105,614,030 bytes). The actual installed NDK audit contains 14 notice candidates. The original LLVM toolchain NOTICE (130,424 bytes, SHA-256 f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1) contains the LLVM exception and libc++ notices. Preserve the entire original text as a supplemental shared asset, without asserting that every listed tool component is shipped. CI verifies the bytes in both APKs and exported games. The archive now also contains the exact generated source revision and NDK notice inputs; a separate build in the extracted directory tests that the unmodified runtime and debug editor can be rebuilt without the original checkout. This does not establish bit-for-bit reproducibility or modified LGPL-library relinking, which remains pending.

## Evidence from build 554

[Build 554](https://github.com/joa-no/CodeLove/actions/runs/45507556951) passed NDK notice preservation, source archive integrity and archive-only unmodified debug editor and unsigned runtime compilation. The next test uses the extracted source to rebuild modified Sora and OpenAL Soft and verify their markers in the proper APK flavors. It introduces an optional local Sora AAR override, retaining the published dependency for all ordinary builds. The test APK has a separate application ID and is not an official release. Installation and execution on a real Android device, final notice review, and release-source availability remain pending.

## Evidence from build 555 (attempt 2)

[Build 555](https://github.com/joa-no/CodeLove/actions/runs/45524405944) passed Debug and Release, independent archive rebuild, modified Sora AAR and OpenAL native rebuild and marker checks across all three ABIs. Attempt 1 failed on Maven Central HTTP 429 before compilation. The separately identified debug test APK was tested on Android by the maintainer, who reported normal behavior. The maintainer supplied the exported StarGuard.apk (SHA-256 89169cc9aeb77120b75daf0ef11aeecfe87b284d8aebba543a26e9a1981056cd); ZIP integrity passed, the OpenAL test marker was present in arm64-v8a, armeabi-v7a and x86_64, no Sora class descriptor or marker was found in DEX, all four runtime notice assets were present, editor license asset absent, and the source revision matched e820a1a122a7842cd9c7665b195f5d3a1e3c4f62. The game APK is test evidence only and is not committed or published. This closes the practical modified-library rebuild and maintainer smoke-test gate, not the outstanding final upstream notice and source-distribution review. Test binaries are not official releases.
