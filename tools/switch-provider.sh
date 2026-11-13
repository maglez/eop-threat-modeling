#!/usr/bin/env bash
set -euo pipefail

# SWITCH_PROVIDER_ENV_FILE is a testing knob: it lets a test point the script at a
# fixture instead of the developer's real .env. It is unset in normal use.
ENV_FILE="${SWITCH_PROVIDER_ENV_FILE:-$(cd "$(dirname "$0")/.." && pwd)/.env}"
CATALOG_URL="https://opencode.ai/zen/go/v1/models"

usage() {
  echo "Usage: $0 <zen|bedrock|go|go-free> [flags]"
  echo "       $0 go --free-only        (same as go-free)"
  echo "       $0 go-free --refresh     (rewrite the Go Free block from the live catalogue, no toggle)"
  echo "       $0 go-free --dry-run     (show what would change, touch nothing)"
  echo "  zen        — switch to OpenCode Zen provider"
  echo "  bedrock    — switch to AWS Bedrock provider"
  echo "  go         — switch to OpenCode Go provider (paid Go models)"
  echo "  go-free    — switch to OpenCode Go, free models only"
  echo "  --free-only— go target restricted to free models; implies go"
  echo "  --offline  — do not fetch the catalogue; use the existing Go Free block"
  echo "  --refresh  — refresh the Go Free block only; implies go-free, no toggle"
  echo "  --dry-run  — print the discovered free models and intended assignments; touch nothing"
}

PROVIDER=""
FREE_ONLY=false
OFFLINE=false
REFRESH=false
DRY_RUN=false

for arg in "$@"; do
  case "$arg" in
    zen|bedrock|go|go-free)
      if [ -n "$PROVIDER" ]; then echo "Error: multiple providers given ('$PROVIDER' and '$arg')" >&2; usage >&2; exit 1; fi
      PROVIDER="$arg"
      ;;
    --free-only|--free)
      FREE_ONLY=true
      ;;
    --offline)
      OFFLINE=true
      ;;
    --refresh)
      REFRESH=true
      ;;
    --dry-run)
      DRY_RUN=true
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

if [ "$REFRESH" = true ] || [ "$DRY_RUN" = true ]; then
  case "$PROVIDER" in
    go-free|"")
      PROVIDER="go-free"
      ;;
    *)
      echo "Error: --refresh/--dry-run only apply to the Go free block (got '$PROVIDER')" >&2
      usage >&2
      exit 1
      ;;
  esac
fi

if [ "$REFRESH" = true ] && [ "$OFFLINE" = true ]; then
  echo "Error: --refresh and --offline are mutually exclusive." >&2
  exit 1
fi

if [ -z "$PROVIDER" ]; then
  usage >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: $ENV_FILE not found. Copy .env.example to .env first." >&2
  exit 1
fi

# Block ranges — order in .env / .env.example is:
#   # === Zen ===  /  # === Go ===  /  # === Go Free ===  /  # === Bedrock ===  /  # AWS
# NOTE: this script holds no model IDs for Zen, Go or Bedrock — it only moves '#'
# markers. The Go Free block is the exception: it is regenerated from the live
# catalogue on every `go-free` switch (see discover_free_models).
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

# Prints the zero-cost Go model IDs, one per line, sorted. Fails loudly on a
# missing tool, a network error, an unexpected schema, or an empty result.
discover_free_models() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "Error: 'curl' is required to discover free Go models." >&2
    return 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "Error: 'jq' is required to parse the Go model catalogue." >&2
    return 1
  fi
  local json
  if ! json=$(curl -fsS -m 30 "$CATALOG_URL"); then
    echo "Error: failed to fetch $CATALOG_URL" >&2
    return 1
  fi
  if ! printf '%s' "$json" | jq -e '.data | type == "array"' >/dev/null 2>&1; then
    echo "Error: unexpected catalogue schema from $CATALOG_URL" >&2
    return 1
  fi
  local ids
  ids=$(printf '%s' "$json" | jq -r '
    .data[]
    | select(
        (.cost.text.input.price == 0)
        and ((.cost.text.input.cache.read // 0) == 0)
        and (.cost.text.output.price == 0)
      )
    | .id
  ' | sort)
  if [ -z "$ids" ]; then
    echo "Error: no zero-cost Go models found in $CATALOG_URL" >&2
    return 1
  fi
  printf '%s\n' "$ids"
}

# The Go Free block is stored commented in .env.example and becomes active only
# after a switch. Preserve whichever state it is already in, so a re-run does not
# leave the block active when the user only meant to refresh it.
go_free_prefix() {
  local existing
  existing=$(sed -n '/^# === Go Free ===/,/^# === Bedrock ===/p' "$ENV_FILE" | grep -m1 -E '^#?MODEL_[A-G]=' || true)
  case "$existing" in
    '#MODEL_'*) printf '%s' '#' ;;
    'MODEL_'*) printf '%s' '' ;;
    *) printf '%s' '#' ;;
  esac
}

# Prints the seven Go Free assignments joined by '|'. Repeats the discovered IDs
# round-robin, because the catalogue currently exposes fewer free models than the
# seven tiers. Kept in one place so --dry-run and the real rewrite cannot drift.
go_free_assignments() {
  local ids="$1" prefix="$2" count i=0 id tier joined=""
  count=$(printf '%s\n' "$ids" | wc -l | tr -d ' ')
  for tier in A B C D E F G; do
    id=$(printf '%s\n' "$ids" | sed -n "$((i % count + 1))p")
    joined="${joined}${prefix}MODEL_${tier}=opencode-go/${id}|"
    i=$((i + 1))
  done
  printf '%s' "${joined%|}"
}

rewrite_go_free_block() {
  local assignments="$1" tmp
  tmp="$(dirname "$ENV_FILE")/.tmp/switch-provider.$$.tmp"
  mkdir -p "$(dirname "$tmp")"
  GO_FREE_ASSIGNMENTS="$assignments" awk '
    BEGIN { n = split(ENVIRON["GO_FREE_ASSIGNMENTS"], arr, "|"); inserted = 0 }
    /^# === Go Free ===/ { inblock = 1; print; next }
    /^# === Bedrock ===/ {
      if (inblock && !inserted) {
        for (i = 1; i <= n; i++) print arr[i]
      }
      inblock = 0
      print
      next
    }
    inblock && $0 ~ /^#?MODEL_[A-G]=/ {
      if (!inserted) {
        for (i = 1; i <= n; i++) print arr[i]
        inserted = 1
      }
      next
    }
    { print }
  ' "$ENV_FILE" > "$tmp"
  mv "$tmp" "$ENV_FILE"
}

FREE_COUNT=0
FREE_IDS=""

if [ "$PROVIDER" = "go-free" ]; then
  if [ "$OFFLINE" = true ]; then
    echo "Offline: using the existing Go Free block; it may be stale."
    if [ "$DRY_RUN" = true ]; then
      echo
      echo "Existing Go Free block:"
      sed -n '/^# === Go Free ===/,/^# === Bedrock ===/p' "$ENV_FILE" | grep -E '^#?MODEL_[A-G]=' | sed 's/^/  /'
      echo
      echo "Dry run: no files changed."
      exit 0
    fi
  else
    FREE_IDS=$(discover_free_models)
    FREE_COUNT=$(printf '%s\n' "$FREE_IDS" | wc -l | tr -d ' ')
    echo "Discovered $FREE_COUNT free Go model(s) from $CATALOG_URL:"
    printf '%s\n' "$FREE_IDS" | sed 's/^/  - /'
    if [ "$DRY_RUN" = true ]; then
      echo
      echo "Would rewrite the Go Free block with:"
      go_free_assignments "$FREE_IDS" "$(go_free_prefix)" | tr '|' '\n' | sed 's/^/  /'
      echo
      echo "Dry run: no files changed."
      exit 0
    fi
    rewrite_go_free_block "$(go_free_assignments "$FREE_IDS" "$(go_free_prefix)")"
    if [ "$REFRESH" = true ]; then
      echo "Refreshed the Go Free block in $ENV_FILE (no provider switch)."
      exit 0
    fi
  fi
fi

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
    if [ "$OFFLINE" != true ]; then
      echo "Note: $FREE_COUNT free model(s) repeat across tiers, so family separation"
      echo "      between authors and review gates is not met. Use for exploration only."
    fi
    ;;
  bedrock)
    echo "Switching to AWS Bedrock..."
    for range in "$ZEN_RANGE" "$GO_RANGE" "$GO_FREE_RANGE"; do comment_block "$range"; done
    uncomment_block "$BEDROCK_RANGE"
    ;;
esac

echo "Done. Restart opencode for the change to take effect."
