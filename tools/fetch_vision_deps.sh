#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
CPP="$ROOT/app/src/main/cpp"
mkdir -p "$ASSETS" "$CPP"

MODEL_TAG="v1.3"
MODEL_BASE="https://raw.githubusercontent.com/madeye/ad-skipper/${MODEL_TAG}/core/src/main/assets"
NCNN_VERSION="20260526"
NCNN_ARCHIVE="ncnn-${NCNN_VERSION}-android-vulkan.zip"
NCNN_URL="https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_ARCHIVE}"
NCNN_LATEST_URL="https://github.com/Tencent/ncnn/releases/latest/download/${NCNN_ARCHIVE}"
NCNN_SHA256="26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804"

fetch() {
  local primary="$1"
  local output="$2"
  local fallback="${3:-}"
  echo "GET $primary"
  if curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 "$primary" -o "$output"; then
    return 0
  fi
  if [ -n "$fallback" ]; then
    echo "Primary failed  trying fallback $fallback"
    curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 "$fallback" -o "$output"
    return 0
  fi
  return 1
}

if [ ! -s "$ASSETS/yolo.ncnn.param" ]; then
  fetch "$MODEL_BASE/yolo.ncnn.param" "$ASSETS/yolo.ncnn.param"
fi
if [ ! -s "$ASSETS/yolo.ncnn.bin" ]; then
  fetch "$MODEL_BASE/yolo.ncnn.bin" "$ASSETS/yolo.ncnn.bin"
fi

PARAM_BYTES=$(stat -c%s "$ASSETS/yolo.ncnn.param")
BIN_BYTES=$(stat -c%s "$ASSETS/yolo.ncnn.bin")
if [ "$PARAM_BYTES" -lt 4096 ]; then
  echo "YOLO param file is unexpectedly small: $PARAM_BYTES bytes" >&2
  exit 1
fi
if [ "$BIN_BYTES" -lt 1048576 ]; then
  echo "YOLO model file is unexpectedly small: $BIN_BYTES bytes" >&2
  exit 1
fi

if [ ! -f "$CPP/ncnn/arm64-v8a/lib/cmake/ncnn/ncnnConfig.cmake" ]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  fetch "$NCNN_URL" "$TMP/$NCNN_ARCHIVE" "$NCNN_LATEST_URL"
  echo "$NCNN_SHA256  $TMP/$NCNN_ARCHIVE" | sha256sum --check
  unzip -q "$TMP/$NCNN_ARCHIVE" -d "$TMP"
  test -f "$TMP/ncnn-${NCNN_VERSION}-android-vulkan/arm64-v8a/lib/cmake/ncnn/ncnnConfig.cmake"
  rm -rf "$CPP/ncnn"
  mv "$TMP/ncnn-${NCNN_VERSION}-android-vulkan" "$CPP/ncnn"
fi

test -f "$CPP/ncnn/arm64-v8a/lib/cmake/ncnn/ncnnConfig.cmake"
echo "Vision dependencies ready"
echo "YOLO param: $PARAM_BYTES bytes"
echo "YOLO model: $BIN_BYTES bytes"
echo "ncnn: $NCNN_VERSION android-vulkan"
