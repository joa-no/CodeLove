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
