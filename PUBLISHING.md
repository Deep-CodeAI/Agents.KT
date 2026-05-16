# Publishing to Maven Central

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

Artifacts land in `~/.m2/repository/ai/deep-code/agents-kt/0.5.0/`.

### 2. Generate checksums and create bundle

```bash
SRC=~/.m2/repository/ai/deep-code/agents-kt/0.5.0
DEST=build/bundle/ai/deep-code/agents-kt/0.5.0
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
zip -r ../agents-kt-0.5.0-bundle.zip ai/
```

The ZIP must contain the full path: `ai/deep-code/agents-kt/0.5.0/...`

## Upload to Central Portal

1. Go to [central.sonatype.com](https://central.sonatype.com) → **Deployments** → **Publish Component**
2. **Deployment Name:** `ai.deep-code:agents-kt:0.5.0`
3. **Description:** `Typed Kotlin DSL framework for AI agent systems`
4. Upload `build/agents-kt-0.5.0-bundle.zip`
5. Wait for validation to pass
6. Click **Publish**

Propagation to Maven Central search takes 10-30 minutes after publishing.

## Bundle Contents

Each artifact needs: the file itself, `.asc` (GPG signature), `.md5`, and `.sha1`.

```
ai/deep-code/agents-kt/0.5.0/
  agents-kt-0.5.0.jar
  agents-kt-0.5.0.jar.asc
  agents-kt-0.5.0.jar.md5
  agents-kt-0.5.0.jar.sha1
  agents-kt-0.5.0-sources.jar
  agents-kt-0.5.0-sources.jar.asc
  agents-kt-0.5.0-sources.jar.md5
  agents-kt-0.5.0-sources.jar.sha1
  agents-kt-0.5.0-javadoc.jar
  agents-kt-0.5.0-javadoc.jar.asc
  agents-kt-0.5.0-javadoc.jar.md5
  agents-kt-0.5.0-javadoc.jar.sha1
  agents-kt-0.5.0.pom
  agents-kt-0.5.0.pom.asc
  agents-kt-0.5.0.pom.md5
  agents-kt-0.5.0.pom.sha1
  agents-kt-0.5.0.module
  agents-kt-0.5.0.module.asc
  agents-kt-0.5.0.module.md5
  agents-kt-0.5.0.module.sha1
```

## Version Bump

For the next release, update `version` in `build.gradle.kts` and repeat the process.
