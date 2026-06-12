# Release runbook

This is the **ordering** a release must follow. The mechanics (GPG, bundle layout, Central
upload) live in [`PUBLISHING.md`](../PUBLISHING.md); this document exists to pin the one rule an
external review caught us breaking (#3084, de-slop epic #3083):

> **Never advertise a version that isn't resolvable on Maven Central yet.**

0.7.2 shipped with the README quickstart and the Gradle version both reading `0.7.2` while Central
still served `0.7.1` — the signed bundle had been built but not uploaded. A consumer copy-pasting
the quickstart got an unresolvable dependency. The steps below make that ordering explicit and
add a final automated gate.

## The order

1. **Bump** `version` in `build.gradle.kts`. Do **not** touch the README version snippet yet.
2. **Build & verify locally:** `./gradlew build` (full suite + detekt). For hosted-API tests that
   need a provider key, see the `live-cloud-api` note in the build file.
3. **Build the signed bundle** (see `PUBLISHING.md` → *Build & Bundle*). Confirm the GPG signature
   and that the zip's Maven path is `ai/deep-code/agents-kt/<version>/…` with no `maven-metadata`.
4. **Upload via the Central Portal** and click **Publish** (`PUBLISHING.md` → *Upload to Central
   Portal*). Propagation to `repo1.maven.org` takes ~10–30 min.
5. **Gate — confirm it's resolvable.** This is the step that was skipped:
   ```bash
   ./gradlew checkPublishedVersion
   ```
   It HEADs `ai.deep-code:agents-kt` **and** `agents-kt-ksp` at the current project version on
   Central and fails unless both return HTTP 200. It is **not** part of `check` (it needs network
   and would fail on an unreleased version during normal dev) — run it manually here, as the last
   gate before anything user-facing names the new version. Override the base URL for a mirror with
   `-PcentralBaseUrl=…`.
6. **Only now** bump the README dependency snippet to the new version (and any other user-facing
   docs / announcements). `checkReadmeVersion` (wired into `check`, #2873) keeps the README and the
   Gradle version in lockstep from this point on.
7. **Tag** the release (annotated; use `git tag -a --cleanup=verbatim -F <file>` if the message
   carries `#issue-ref` lines) and push.
8. **Immediately bump `main` to the next `-SNAPSHOT`** *(mechanically enforced — #4428:
   CI runs `checkSnapshotPolicy` on every push to `main` and fails when a non-`-SNAPSHOT`
   version is not the tagged release commit itself; release-PR CI is exempt by ref.)* (`0.7.24` released → `version = "0.7.25-SNAPSHOT"`).
   Leave the README snippet at the just-published version. This keeps post-release commits from
   masquerading under the released version's identity: `main` is always either *exactly* a
   published release (the tagged commit) or visibly `-SNAPSHOT`. `checkReadmeVersion` understands
   this state — on a `-SNAPSHOT` version it requires the README to advertise a plain release
   version strictly below the snapshot base, and exact lockstep resumes at step 6 of the next
   release.

## Why two guards

| Guard | When | Catches |
|---|---|---|
| `checkReadmeVersion` (#2873) | every `check` | README version ≠ Gradle version (release); README advertising a `-SNAPSHOT` or a version ≥ the snapshot base (dev) |
| `checkPublishedVersion` (#3084) | manual, step 5 | Gradle/advertised version not yet on Central |

The first stops the README from drifting from the build; the second stops the build (and therefore
the README, once step 6 runs) from advertising a version the world can't download. Together they
close the gap the review flagged: the advertised version is always one Maven can actually resolve.
