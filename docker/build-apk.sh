#!/usr/bin/env bash
# Run Gradle inside the diplay-builder image. Usage: docker/build-apk.sh [gradle tasks...]
# Optional host env: DIPLAY_AUTH_ASSETS_DIR, ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD,
# ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD, IMAGE, GRADLE_CACHE_DIR.
# Memory (defaults suit a shared host with ~3 GB free): GRADLE_HEAP (2g), GRADLE_WORKERS (2),
# KOTLIN_IN_PROCESS (true: compile Kotlin inside Gradle instead of a separate ~1-2 GB daemon).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE:-diplay-builder}"
CACHE="${GRADLE_CACHE_DIR:-$ROOT/../.diplay-gradle-cache}"
mkdir -p "$CACHE"

args=(--rm -t --user "$(id -u):$(id -g)" -v "$ROOT:/workspace" -v "$(realpath "$CACHE"):/gradle" -w /workspace)

if [[ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" ]]; then
  args+=(-v "$(realpath "$DIPLAY_AUTH_ASSETS_DIR"):/mnt/diplay-assets:ro"
         -e DIPLAY_AUTH_ASSETS_DIR=/mnt/diplay-assets)
fi

if [[ -n "${ANDROID_KEYSTORE_PATH:-}" ]]; then
  args+=(-v "$(realpath "$ANDROID_KEYSTORE_PATH"):/mnt/release.jks:ro"
         -e ANDROID_KEYSTORE_PATH=/mnt/release.jks
         -e ANDROID_KEYSTORE_PASSWORD -e ANDROID_KEY_ALIAS -e ANDROID_KEY_PASSWORD)
fi

[[ -n "${MAVEN_CENTRAL_MIRROR:-}" ]] && args+=(-e MAVEN_CENTRAL_MIRROR)

memory=(-Dorg.gradle.jvmargs="-Xmx${GRADLE_HEAP:-2g} -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
        --max-workers="${GRADLE_WORKERS:-2}")
[[ "${KOTLIN_IN_PROCESS:-true}" == true ]] && memory+=(-Pkotlin.compiler.execution.strategy=in-process)

if (( $# == 0 )); then
  set -- :mobile:assembleRelease
  # A release without the identity builds fine but cannot connect to an iPhone.
  [[ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" ]] || echo "WARNING: DIPLAY_AUTH_ASSETS_DIR is not set; the APK will not include the CarPlay identity." >&2
  [[ -n "${ANDROID_KEYSTORE_PATH:-}" ]] || echo "WARNING: ANDROID_KEYSTORE_PATH is not set; run 'source ~/diplay-keys/keystore.env' first." >&2
fi

echo "${args[@]}"
exec docker run "${args[@]}" "$IMAGE" ./gradlew --no-daemon "${memory[@]}" \
  --init-script /workspace/docker/maven-mirror.init.gradle "$@"
