#!/usr/bin/env bash
#
# GUARD 7/7 — MODEL CATALOG URL HEALTH
# ===================================
# A public byte-range request must return HTTP 206. Transport/network failures,
# invalid or gated URLs, and remote-service errors are reported separately.
#
set -euo pipefail

URLS=(
  "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
  "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
  "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"
)

echo "=== Verifying Catalog Models Consistency & Defaults ==="
if ! diff -q configs/models.yaml app/src/main/assets/aniob_config/models.yaml >/dev/null 2>&1 || \
   ! diff -q configs/models.yaml android-agent/configs/models.yaml >/dev/null 2>&1; then
  echo "ERROR: Catalog models.yaml files drift across mirrored locations."
  exit 1
fi
echo "OK: All 3 catalog copies agree."

DECLARED_IDS=$(grep -E '^[[:space:]]*-[[:space:]]*id:' configs/models.yaml | sed -E 's/.*id:[[:space:]]*"?([^"]*)"?/\1/')
for key in ram_4gb ram_6gb ram_8gb ram_8gb_coding ram_8gb_balanced; do
  val=$(grep -E "^[[:space:]]*${key}:" configs/models.yaml | sed -E "s/.*${key}:[[:space:]]*\"?([^\"]*)\"?/\1/")
  if [ -z "$val" ]; then
    echo "ERROR: Missing default mapping for key: $key"
    exit 1
  fi
  if ! grep -qxF "$val" <<< "$DECLARED_IDS"; then
    echo "ERROR: Default '$key' points to undeclared model ID '$val'"
    exit 1
  fi
  echo "OK: Default '$key' -> '$val' (verified declared)"
done

echo "=== Verifying Catalog Model URLs (Unauthenticated HTTP 206 Range Check) ==="
NETWORK_FAILURES=0
NOT_FOUND_FAILURES=0
ACCESS_FAILURES=0
SERVICE_FAILURES=0
OTHER_FAILURES=0

for url in "${URLS[@]}"; do
  # Follow redirects and request only byte 0. Preserve curl's exit status separately
  # from the HTTP status so a transport error never becomes a malformed "000000".
  if status=$(curl --silent --show-error --location --output /dev/null \
      --write-out '%{http_code}' --range 0-0 \
      --connect-timeout 15 --max-time 60 "$url"); then
    curl_status=0
  else
    curl_status=$?
  fi

  if [ "${status:-000}" = "000" ] || [ "$curl_status" -ne 0 ]; then
    echo "ERROR: Network/transport unavailable (curl exit $curl_status; HTTP ${status:-000}); URL was not validated."
    echo "       Check runner DNS, proxy, firewall, and remote connectivity; this is not evidence the URL is invalid."
    echo "       URL: $url"
    NETWORK_FAILURES=$((NETWORK_FAILURES + 1))
  elif [ "$status" = "206" ]; then
    echo "OK: [HTTP 206] $url"
  elif [ "$status" = "404" ]; then
    echo "ERROR: Model URL not found (HTTP 404)."
    echo "       URL: $url"
    NOT_FOUND_FAILURES=$((NOT_FOUND_FAILURES + 1))
  elif [ "$status" = "401" ] || [ "$status" = "403" ]; then
    echo "ERROR: Model URL is gated or access denied (HTTP $status)."
    echo "       URL: $url"
    ACCESS_FAILURES=$((ACCESS_FAILURES + 1))
  elif [ "$status" = "429" ]; then
    echo "ERROR: Model host rate-limited the check (HTTP 429); retry later."
    echo "       URL: $url"
    SERVICE_FAILURES=$((SERVICE_FAILURES + 1))
  elif [[ "$status" =~ ^5[0-9][0-9]$ ]]; then
    echo "ERROR: Model host returned a server error (HTTP $status); retry after checking service status."
    echo "       URL: $url"
    SERVICE_FAILURES=$((SERVICE_FAILURES + 1))
  else
    echo "ERROR: Unexpected model URL response (HTTP $status; expected 206)."
    echo "       URL: $url"
    OTHER_FAILURES=$((OTHER_FAILURES + 1))
  fi
done

TOTAL_FAILURES=$((NETWORK_FAILURES + NOT_FOUND_FAILURES + ACCESS_FAILURES + SERVICE_FAILURES + OTHER_FAILURES))
if [ "$TOTAL_FAILURES" -ne 0 ]; then
  echo "FAIL: catalog URL validation failed (network=$NETWORK_FAILURES, not-found=$NOT_FOUND_FAILURES, access=$ACCESS_FAILURES, server=$SERVICE_FAILURES, other=$OTHER_FAILURES)."
  exit 1
fi

echo "SUCCESS: All catalog URLs verified healthy with unauthenticated range support."
