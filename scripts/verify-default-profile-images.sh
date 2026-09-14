#!/bin/bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/verify-default-profile-images.sh --base-url URL [--require-https] [--sha256 HASH]
  scripts/verify-default-profile-images.sh --env-file PATH [--require-https] [--sha256 HASH]

Verifies that defaults/profile-1.png through profile-4.png are anonymously
available as PNG responses. --env-file reads FILE_PUBLIC_BASE_URL without
sourcing the file.
EOF
}

BASE_URL=""
ENV_FILE=""
REQUIRE_HTTPS=false
EXPECTED_SHA256=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url)
      [ "$#" -ge 2 ] || { echo "ERROR: --base-url requires a value" >&2; exit 2; }
      BASE_URL="$2"
      shift 2
      ;;
    --env-file)
      [ "$#" -ge 2 ] || { echo "ERROR: --env-file requires a path" >&2; exit 2; }
      ENV_FILE="$2"
      shift 2
      ;;
    --require-https)
      REQUIRE_HTTPS=true
      shift
      ;;
    --sha256)
      [ "$#" -ge 2 ] || { echo "ERROR: --sha256 requires a value" >&2; exit 2; }
      EXPECTED_SHA256="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ -n "$BASE_URL" ] && [ -n "$ENV_FILE" ]; then
  echo "ERROR: use only one of --base-url and --env-file" >&2
  exit 2
fi

if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }
  BASE_URL="$(sed -n 's/^FILE_PUBLIC_BASE_URL=//p' "$ENV_FILE" | tail -n 1 | tr -d '\r')"
fi

if [ -z "$BASE_URL" ]; then
  echo "ERROR: FILE_PUBLIC_BASE_URL is empty" >&2
  exit 2
fi

case "$BASE_URL" in
  http://*|https://*) ;;
  *)
    echo "ERROR: FILE_PUBLIC_BASE_URL must be an HTTP(S) URL" >&2
    exit 2
    ;;
esac

if [ "$REQUIRE_HTTPS" = true ] && [[ "$BASE_URL" != https://* ]]; then
  echo "ERROR: deployed profile images must use HTTPS" >&2
  exit 2
fi

if [ -n "$EXPECTED_SHA256" ] && [[ ! "$EXPECTED_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "ERROR: --sha256 must be a 64-character hexadecimal digest" >&2
  exit 2
fi

while [[ "$BASE_URL" == */ ]]; do
  BASE_URL="${BASE_URL%/}"
done

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

for index in 1 2 3 4; do
  URL="$BASE_URL/defaults/profile-$index.png"
  BODY="$TMP_DIR/profile-$index.png"
  CURL_PROTOCOLS=()
  if [ "$REQUIRE_HTTPS" = true ]; then
    CURL_PROTOCOLS=(--proto '=https' --proto-redir '=https')
  fi

  CONTENT_TYPE="$(curl --fail --silent --show-error --location \
    --connect-timeout 5 --max-time 20 \
    "${CURL_PROTOCOLS[@]}" \
    --write-out '%{content_type}' --output "$BODY" "$URL" | tr '[:upper:]' '[:lower:]')"
  if [[ "$CONTENT_TYPE" != image/png* ]]; then
    echo "ERROR: $URL returned Content-Type '$CONTENT_TYPE', expected image/png" >&2
    exit 1
  fi

  SIGNATURE="$(od -An -tx1 -N8 "$BODY" | tr -d '[:space:]')"
  if [ "$SIGNATURE" != "89504e470d0a1a0a" ]; then
    echo "ERROR: $URL did not return PNG data" >&2
    exit 1
  fi

  if [ -n "$EXPECTED_SHA256" ]; then
    ACTUAL_SHA256="$(sha256sum "$BODY" | cut -d' ' -f1)"
    if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
      echo "ERROR: $URL did not match the expected image" >&2
      exit 1
    fi
  fi

  echo "OK: $URL ($CONTENT_TYPE)"
done

echo "Default profile image verification passed (4/4)."
