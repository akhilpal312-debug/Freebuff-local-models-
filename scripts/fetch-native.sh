#!/usr/bin/env bash
# Fetches the native inference sources used by the optional on-device engines.
#
#   ./scripts/fetch-native.sh
#   ./gradlew assembleDebug -PwithNative=true
#
# Both projects are pinned to a release tag so the JNI shims in this directory
# match their C API. Bump the tags together with app/src/main/cpp/*.cpp.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_DIR="$ROOT/app/src/main/cpp"

LLAMA_TAG="${LLAMA_TAG:-b4739}"
WHISPER_TAG="${WHISPER_TAG:-v1.7.4}"

fetch() {
  local name="$1" url="$2" tag="$3"
  local target="$CPP_DIR/$name"
  if [ -d "$target/.git" ]; then
    echo "== $name already present, fetching tag $tag"
    git -C "$target" fetch --depth 1 origin "refs/tags/$tag:refs/tags/$tag"
    git -C "$target" checkout -q "$tag"
  else
    echo "== cloning $name at $tag"
    git clone --depth 1 --branch "$tag" "$url" "$target"
  fi
}

fetch llama.cpp https://github.com/ggerganov/llama.cpp.git "$LLAMA_TAG"
fetch whisper.cpp https://github.com/ggerganov/whisper.cpp.git "$WHISPER_TAG"

# The Android build only needs sources; strip the heavy parts.
rm -rf "$CPP_DIR/llama.cpp/examples" "$CPP_DIR/llama.cpp/tests" \
       "$CPP_DIR/whisper.cpp/examples" "$CPP_DIR/whisper.cpp/tests" \
       "$CPP_DIR/whisper.cpp/models" 2>/dev/null || true

echo
echo "Native sources ready:"
echo "  llama.cpp   $LLAMA_TAG"
echo "  whisper.cpp $WHISPER_TAG"
echo
echo "Now install the NDK and CMake, then build:"
echo "  sdkmanager 'ndk;27.2.12479018' 'cmake;3.22.1'"
echo "  ./gradlew assembleDebug -PwithNative=true"
