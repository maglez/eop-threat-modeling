#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/.env"

usage() {
  echo "Usage: $0 <zen|bedrock|go|go-free> [--free-only]"
  echo "       $0 go --free-only   (same as go-free)"
  echo "  zen        — switch to OpenCode Zen provider"
  echo "  bedrock    — switch to AWS Bedrock provider"
  echo "  go         — switch to OpenCode Go provider (paid Go models)"
  echo "  go-free    — switch to OpenCode Go, free models only"
  echo "  --free-only— go target restricted to free models; implies go"
}

PROVIDER=""
FREE_ONLY=false

for arg in "$@"; do
  case "$arg" in
    zen|bedrock|go|go-free)
      if [ -n "$PROVIDER" ]; then echo "Error: multiple providers given ('$PROVIDER' and '$arg')" >&2; usage >&2; exit 1; fi
      PROVIDER="$arg"
      ;;
    --free-only|--free)
      FREE_ONLY=true
      ;;
    -h|--help|help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown argument '$arg'" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [ "$FREE_ONLY" = true ]; then
  case "$PROVIDER" in
    go|go-free|"")
      PROVIDER="go-free"
      ;;
    *)
      echo "Error: --free-only only applies to the Go provider (got '$PROVIDER')" >&2
      usage >&2
      exit 1
      ;;
  esac
fi

if [ -z "$PROVIDER" ]; then
  usage >&2
  exit 1
fi

# Block ranges — order in .env / .env.example is:
#   # === Zen ===  /  # === Go ===  /  # === Go Free ===  /  # === Bedrock ===  /  # AWS
# NOTE: this script holds no model IDs — it only moves '#' markers.
ZEN_RANGE='/^# === Zen ===/,/^# === Go ===/'
GO_RANGE='/^# === Go ===/,/^# === Go Free ===/'
GO_FREE_RANGE='/^# === Go Free ===/,/^# === Bedrock ===/'
BEDROCK_RANGE='/^# === Bedrock ===/,/^# AWS/'

comment_block() {
  sed -i '' -e "$1{
    /^MODEL_/s/^/#/
  }" "$ENV_FILE"
}

uncomment_block() {
  sed -i '' -e "$1{
    /^#MODEL_/s/^#//
  }" "$ENV_FILE"
}

# Fail loudly (never silently no-op) if the marker lines are missing.
for marker in '# === Zen ===' '# === Go ===' '# === Go Free ===' '# === Bedrock ===' '# AWS'; do
  if ! grep -q "^$(printf '%s' "$marker" | sed 's/[][\.*^$/]/\\&/g')" "$ENV_FILE"; then
    echo "Error: marker '$marker' not found in $ENV_FILE" >&2
    echo "Sync the provider blocks from .env.example (keep every model line at column 0)." >&2
    exit 1
  fi
done

case "$PROVIDER" in
  zen)
    echo "Switching to OpenCode Zen..."
    for range in "$GO_RANGE" "$GO_FREE_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$ZEN_RANGE"
    ;;
  go)
    echo "Switching to OpenCode Go..."
    for range in "$ZEN_RANGE" "$GO_FREE_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$GO_RANGE"
    ;;
  go-free)
    echo "Switching to OpenCode Go (free models only)..."
    for range in "$ZEN_RANGE" "$GO_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$GO_FREE_RANGE"
    echo "Note: only 3 free Go models exist at the time of writing, so tiers repeat;"
    echo "      family separation between authors and review gates is not met."
    ;;
  bedrock)
    echo "Switching to AWS Bedrock..."
    for range in "$ZEN_RANGE" "$GO_RANGE" "$GO_FREE_RANGE"; do comment_block "$range"; done
    uncomment_block "$BEDROCK_RANGE"
    ;;
esac

echo "Done. Restart opencode for the change to take effect."
