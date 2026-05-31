#!/usr/bin/env bash
#
# Run agents-kt's Linux sandbox tests (the #2892 bwrap/firejail ProcessSandbox
# backend, tagged `linux_only`) from macOS, inside a Lima Linux VM.
#
# Why: macOS has no Linux kernel, so bwrap/firejail can't run natively. Lima
# gives us a *real* Linux VM (not a container), so user-namespaces / setuid work
# like production — no `--privileged` hacks. The host repo is mounted 1:1, so the
# same paths work inside the VM.
#
# Prereqs (macOS):  brew install lima
# Usage:
#   scripts/lima-test.sh                     # runs ./gradlew linuxSandboxTest in the VM
#   scripts/lima-test.sh test --tests "X"    # runs any gradle command in the VM
#
# Env: LIMA_VM (default: agents-kt) — the Lima instance name.
set -euo pipefail

VM="${LIMA_VM:-agents-kt}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_ARGS=("$@")
[ ${#GRADLE_ARGS[@]} -eq 0 ] && GRADLE_ARGS=("linuxSandboxTest" "--console=plain")

if ! command -v limactl >/dev/null 2>&1; then
  echo "ERROR: lima not installed. On macOS:  brew install lima" >&2
  exit 1
fi

# 1. Ensure the VM exists. Ubuntu image, host mounts made writable so Gradle can
#    write build/. First-run also installs JDK 21 + bubblewrap + firejail.
if ! limactl list -q 2>/dev/null | grep -qx "$VM"; then
  echo "==> creating Lima VM '$VM' (Ubuntu, writable mounts) — first run only ..."
  limactl start --yes --name="$VM" --set '.mounts |= map(.writable = true)' template://ubuntu
  echo "==> installing JDK 21 + bubblewrap + firejail in the VM ..."
  limactl shell "$VM" sudo env DEBIAN_FRONTEND=noninteractive bash -c \
    'apt-get update -qq && apt-get install -y -qq openjdk-21-jdk-headless bubblewrap firejail'
else
  limactl start "$VM" >/dev/null 2>&1 || true   # start if stopped; no-op if running
fi

# 2. Run Gradle inside the VM at the same repo path (Lima mounts host paths 1:1).
echo "==> [lima:$VM] ./gradlew ${GRADLE_ARGS[*]}"
limactl shell "$VM" bash -lc "cd '$REPO' && ./gradlew ${GRADLE_ARGS[*]}"
