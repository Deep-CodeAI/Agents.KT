# Publishing to Maven Central

## Prerequisites

1. **Sonatype account** — register at [central.sonatype.com](https://central.sonatype.com)
2. **Namespace verified** — `ai.deep-code` verified via DNS TXT record on `deep-code.ai`
3. **GPG key** — for signing artifacts

## GPG Key Setup 

Generate a key (if you don't have one):

```bash
gpg --pinentry-mode loopback --batch --gen-key <<'EOF'
Key-Type: RSA
Key-Length: 4096
Subkey-Type: RSA
Subkey-Length: 4096
Name-Real: Konstantin Skobeltsyn
Name-Email: konstantin@skobeltsyn.com
Expire-Date: 2y
%no-protection
%commit
EOF
```

Upload public key to keyserver:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

## Gradle Credentials

Create `~/.gradle/gradle.properties`:

```properties
sonatypeUsername=your-token-username
sonatypePassword=your-token-password
signing.key=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signing.password=
```

To get the signing key value:

```bash
gpg --pinentry-mode loopback --passphrase "" --armor --export-secret-keys konstantin@skobeltsyn.com
```

Escape newlines as `\n` for the property file.

## Build & Bundle

### 1. Publish to local Maven repo

```bash
./gradlew publishMavenCentralPublicationToMavenLocal
```

Artifacts land in `~/.m2/repository/ai/deep-code/agents-kt/0.4.1/`.

### 2. Generate checksums and create bundle

```bash
SRC=~/.m2/repository/ai/deep-code/agents-kt/0.4.1
DEST=build/bundle/ai/deep-code/agents-kt/0.4.1
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
zip -r ../agents-kt-0.4.1-bundle.zip ai/
```

The ZIP must contain the full path: `ai/deep-code/agents-kt/0.4.1/...`

## Upload to Central Portal

1. Go to [central.sonatype.com](https://central.sonatype.com) → **Deployments** → **Publish Component**
2. **Deployment Name:** `ai.deep-code:agents-kt:0.4.1`
3. **Description:** `Typed Kotlin DSL framework for AI agent systems`
4. Upload `build/agents-kt-0.4.1-bundle.zip`
5. Wait for validation to pass
6. Click **Publish**

Propagation to Maven Central search takes 10-30 minutes after publishing.

## Bundle Contents

Each artifact needs: the file itself, `.asc` (GPG signature), `.md5`, and `.sha1`.

```
ai/deep-code/agents-kt/0.4.1/
  agents-kt-0.4.1.jar
  agents-kt-0.4.1.jar.asc
  agents-kt-0.4.1.jar.md5
  agents-kt-0.4.1.jar.sha1
  agents-kt-0.4.1-sources.jar
  agents-kt-0.4.1-sources.jar.asc
  agents-kt-0.4.1-sources.jar.md5
  agents-kt-0.4.1-sources.jar.sha1
  agents-kt-0.4.1-javadoc.jar
  agents-kt-0.4.1-javadoc.jar.asc
  agents-kt-0.4.1-javadoc.jar.md5
  agents-kt-0.4.1-javadoc.jar.sha1
  agents-kt-0.4.1.pom
  agents-kt-0.4.1.pom.asc
  agents-kt-0.4.1.pom.md5
  agents-kt-0.4.1.pom.sha1
  agents-kt-0.4.1.module
  agents-kt-0.4.1.module.asc
  agents-kt-0.4.1.module.md5
  agents-kt-0.4.1.module.sha1
```

## Version Bump

For the next release, update `version` in `build.gradle.kts` and repeat the process.
