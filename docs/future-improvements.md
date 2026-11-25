# Future improvements

Deferred by the maintainer after the pre-release audit; not part of CodeLove 1.0.0.

- Improve recovery if an Android process is killed during a SAF save, without introducing a persistent working copy of projects.
- Reject malformed UTF-8 or provide explicit encoding handling before editing files.
- Reject or disambiguate duplicate document names and invalid provider-supplied paths before constructing SAF path mappings.
- Isolate official release signing from external pull request builds.
- Review and remove only confirmed dead code, including possible obsolete template-import and legacy local-project paths.
- Extend security and dependency audits, including repository history and secret scanning.

These are follow-up investigations or hardening improvements, not claims that all risks have been reproduced. Do not implement them as part of the 1.0.0 release.
