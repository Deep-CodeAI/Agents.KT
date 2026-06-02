# Publishing to Maven Central

> **Ordering matters.** This document covers the *mechanics* (GPG, bundle, upload). For the
> *sequence* a release must follow — and the rule "never advertise a version that isn't on Central
> yet" — see [`docs/RELEASE_RUNBOOK.md`](docs/RELEASE_RUNBOOK.md). After uploading, run
> `./gradlew checkPublishedVersion` to confirm the version is resolvable before bumping the README
> or announcing.

## Prerequisites

1. **Sonatype account** — register at [central.sonatype.com](https://central.sonatype.com)
2. **Namespace verified** — `ai.deep-code` verified via DNS TXT record on `deep-code.ai`
3. **GPG key** — for signing artifacts

## GPG Key Setup

Generate a key (if you don't have one). **Use a strong passphrase** — the
private key gets stored on disk in `~/.gradle/gradle.properties` (or in
CI secrets); the passphrase is the second factor that protects it if the
file leaks.

```bash
gpg --full-generate-key
# Prompts: RSA, 4096, 2y expiry, your name + email, then enter a passphrase.
```

If you need an unattended/batch flow (e.g. building inside a fresh CI
runner), pass the passphrase via `Passphrase:` in the batch file — never
use `%no-protection`:

```bash
PASSPHRASE="$(openssl rand -base64 32)"   # generate; store in your password manager
gpg --pinentry-mode loopback --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 4096
Subkey-Type: RSA
Subkey-Length: 4096
Name-Real: Your Name
Name-Email: you@example.com
Expire-Date: 2y
Passphrase: $PASSPHRASE
%commit
EOF
```

> **Why not `%no-protection`?** An unprotected private key is a single-file
> compromise. If `~/.gradle/gradle.properties`, a CI cache, or a stray
> backup leaks the file, the attacker can sign artifacts as you with no
> further work. A passphrase forces a second factor at use-time. The
> small ergonomic cost (typing the passphrase once into your password
> manager → Gradle property) is worth it.

Upload the public key to a keyserver:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

## Gradle Credentials

Create `~/.gradle/gradle.properties` (chmod 600 — the file holds your
signing key and Sonatype token):

```properties
sonatypeUsername=your-token-username
sonatypePassword=your-token-password
signing.key=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signing.password=your-gpg-passphrase
```

Export the key value (prompted for the passphrase):

```bash
gpg --armor --export-secret-keys you@example.com
```

Escape newlines as `\n` for the property file. The passphrase set during
key generation goes into `signing.password=` verbatim.

> **Empty-passphrase fallback.** If you genuinely need an unprotected
> key for a constrained environment, use `--passphrase ""` on the export
> and leave `signing.password=` empty. Keep that key isolated — different
> identity, no upload to a keyserver bound to your real identity, scope
> it to one project. Avoid for anything that ships to Maven Central.

## Build & Bundle

### 1. Publish to local Maven repo

```bash
./gradlew publishMavenCentralPublicationToMavenLocal
```

Artifacts land in `~/.m2/repository/ai/deep-code/agents-kt/0.7.0/`.

### 2. Generate checksums and create bundle

```bash
SRC=~/.m2/repository/ai/deep-code/agents-kt/0.7.0
DEST=build/bundle/ai/deep-code/agents-kt/0.7.0
mkdir -p "$DEST"

for f in "$SRC"/agents-kt-*; do
    fname=$(basename "$f")
    cp "$f" "$DEST/$fname"
    md5 -q "$f" > "$DEST/$fname.md5"
    shasum -a 1 "$f" | awk '{print $1}' > "$DEST/$fname.sha1"
done
```

### 3. Create ZIP with Maven directory structure

```bash
cd build/bundle
zip -r ../agents-kt-0.7.0-bundle.zip ai/
```

The ZIP must contain the full path: `ai/deep-code/agents-kt/0.7.0/...`

## Upload to Central Portal

1. Go to [central.sonatype.com](https://central.sonatype.com) → **Deployments** → **Publish Component**
2. **Deployment Name:** `ai.deep-code:agents-kt:0.7.0`
3. **Description:** `Typed Kotlin DSL framework for AI agent systems`
4. Upload `build/agents-kt-0.7.0-bundle.zip`
5. Wait for validation to pass
6. Click **Publish**

Propagation to Maven Central search takes 10-30 minutes after publishing.

## Bundle Contents

Each artifact needs: the file itself, `.asc` (GPG signature), `.md5`, and `.sha1`.

```
ai/deep-code/agents-kt/0.7.0/
  agents-kt-0.7.0.jar
  agents-kt-0.7.0.jar.asc
  agents-kt-0.7.0.jar.md5
  agents-kt-0.7.0.jar.sha1
  agents-kt-0.7.0-sources.jar
  agents-kt-0.7.0-sources.jar.asc
  agents-kt-0.7.0-sources.jar.md5
  agents-kt-0.7.0-sources.jar.sha1
  agents-kt-0.7.0-javadoc.jar
  agents-kt-0.7.0-javadoc.jar.asc
  agents-kt-0.7.0-javadoc.jar.md5
  agents-kt-0.7.0-javadoc.jar.sha1
  agents-kt-0.7.0.pom
  agents-kt-0.7.0.pom.asc
  agents-kt-0.7.0.pom.md5
  agents-kt-0.7.0.pom.sha1
  agents-kt-0.7.0.module
  agents-kt-0.7.0.module.asc
  agents-kt-0.7.0.module.md5
  agents-kt-0.7.0.module.sha1
```

## Version Bump

For the next release, update `version` in `build.gradle.kts` and repeat the process.

---

## GitHub Packages (Secondary Channel)

In addition to Maven Central, the framework publishes to **GitHub Packages** at `https://maven.pkg.github.com/Deep-CodeAI/Agents.KT`. This is a secondary channel — Maven Central remains the primary public distribution.

**When to use GitHub Packages:**

| Use case | Why |
|---|---|
| CI snapshots | Maven Central doesn't accept snapshots from outside Sonatype OSSRH; GitHub Packages does. |
| PR-preview builds | Reviewers can depend on a published `0.x.y-pr<NN>-<sha>` artifact instead of doing a local build. |
| Sonatype outage redundancy | When Central is having a bad day (it happens), consumers can pin GitHub Packages temporarily. |
| Authenticated early-access | Private collaborators / partners consume pre-release builds with a GitHub token — no Nexus to operate. |
| Internal same-org use | Internal projects within the same org pull builds via `gradle.properties` auth — no waiting on the manual Sonatype publish click. |

**When NOT to use it:**

- Don't tell public consumers to depend on GitHub Packages for stable releases. Stable releases go to Central. GitHub Packages is for the use cases above.

### Publishing locally

Set credentials in `~/.gradle/gradle.properties` (NOT this repo's `gradle.properties`):

```properties
gpr.user=your-github-username
gpr.key=ghp_<personal-access-token-with-write-packages-scope>
```

Then:

```bash
./gradlew publishAllPublicationsToGitHubPackagesRepository \
          :agents-kt-ksp:publishAllPublicationsToGitHubPackagesRepository
```

Artifacts land at the [Agents.KT packages page](https://github.com/Deep-CodeAI/Agents.KT/packages).

### Consumer-side wiring

Downstream consumers need to authenticate to GitHub Packages even for *public* repo packages (that's GitHub Packages' authn model; there's nothing we can do about it).

```kotlin
// build.gradle.kts (consumer side)
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Deep-CodeAI/Agents.KT")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("ai.deep-code:agents-kt:0.6.0-SNAPSHOT")
}
```

**Token scopes:**

- **Consumer (reading packages):** PAT with `read:packages`.
- **Publisher (this repo's CI):** the auto-provisioned `GITHUB_TOKEN` inside a GitHub Actions run already has `write:packages` for same-repo packages. No PAT needed.

**Token storage:**

- Never commit a PAT to a repository's `gradle.properties`.
- For local dev: `~/.gradle/gradle.properties` (chmod 600).
- For CI in other projects: a secrets-manager / GitHub Actions secret injected as env var.

### CI workflow (not yet shipped)

The GitHub Actions workflow that auto-publishes snapshots on every push to `main` and releases on `v*.*.*` tag is **not yet committed** — it needs CI-environment verification on a controlled push before being enabled. The shape (for reference; commit when ready):

```yaml
# .github/workflows/publish-github-packages.yml (DRAFT — not yet active)
name: Publish to GitHub Packages
on:
  push:
    branches: [main]
    tags: ['v*.*.*']
jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v3
      - run: |
          ./gradlew \
            publishAllPublicationsToGitHubPackagesRepository \
            :agents-kt-ksp:publishAllPublicationsToGitHubPackagesRepository
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Snapshot-version derivation (whether to bump to `<next>-SNAPSHOT` on push to main, or use `<next>-pr<NN>-<sha>` for PR builds) is a separate decision pending discussion before the workflow goes live.

### Smoke test

After publishing, validate by creating a fresh consumer project:

```bash
mkdir /tmp/agents-kt-gpr-smoke && cd /tmp/agents-kt-gpr-smoke
gradle init --type kotlin-application
# Edit build.gradle.kts to add the GitHub Packages repo + dependency above
gradle run
```

If the consumer compiles + runs against the published snapshot, the channel works end-to-end.
