#!/usr/bin/env bash
set -euo pipefail

# SWITCH_PROVIDER_ENV_FILE is a testing knob: it lets a test point the script at a
# fixture instead of the developer's real .env. It is unset in normal use.
ENV_FILE="${SWITCH_PROVIDER_ENV_FILE:-$(cd "$(dirname "$0")/.." && pwd)/.env}"
CATALOG_URL="https://opencode.ai/zen/go/v1/models"
CONFIG_JSON="$(cd "$(dirname "$0")/.." && pwd)/.opencode/opencode.json"

usage() {
  echo "Usage: $0 <zen|bedrock|go|go-free|go-cheap|agents> [flags]"
  echo "       $0 go --free-only        (same as go-free)"
  echo "       $0 go --cheap            (same as go-cheap)"
  echo "       $0 go-free --refresh     (rewrite the Go Free block from the live catalogue, no toggle)"
  echo "       $0 go-free --dry-run     (show what would change, touch nothing)"
  echo "       $0 agents                (print the model assigned to each agent, then exit)"
  echo "  zen        — switch to OpenCode Zen provider"
  echo "  bedrock    — switch to AWS Bedrock provider"
  echo "  go         — switch to OpenCode Go provider (paid Go models)"
  echo "  go-free    — switch to OpenCode Go, free models only"
  echo "  go-cheap   — switch to OpenCode Go, cheap (discounted) paid models only"
  echo "  agents     — report which model each agent is pinned to; makes no change"
  echo "  --free-only— go target restricted to free models; implies go"
  echo "  --cheap    — go target restricted to cheap/discounted models; implies go"
  echo "  --offline  — do not fetch the catalogue; use the existing Go Free/Cheap block"
  echo "  --refresh  — refresh the Go Free/Cheap block only; implies go-free/go-cheap, no toggle"
  echo "  --dry-run  — print the discovered models and intended assignments; touch nothing"
}

PROVIDER=""
FREE_ONLY=false
CHEAP=false
OFFLINE=false
REFRESH=false
DRY_RUN=false

for arg in "$@"; do
  case "$arg" in
    zen|bedrock|go|go-free|go-cheap|agents)
      if [ -n "$PROVIDER" ]; then echo "Error: multiple providers given ('$PROVIDER' and '$arg')" >&2; usage >&2; exit 1; fi
      PROVIDER="$arg"
      ;;
    --free-only|--free)
      FREE_ONLY=true
      ;;
    --cheap)
      CHEAP=true
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

if [ "$FREE_ONLY" = true ] && [ "$CHEAP" = true ]; then
  echo "Error: --free-only and --cheap are mutually exclusive." >&2
  usage >&2
  exit 1
fi

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

if [ "$CHEAP" = true ]; then
  case "$PROVIDER" in
    go|go-cheap|"")
      PROVIDER="go-cheap"
      ;;
    *)
      echo "Error: --cheap only applies to the Go provider (got '$PROVIDER')" >&2
      usage >&2
      exit 1
      ;;
  esac
fi

if [ "$REFRESH" = true ] || [ "$DRY_RUN" = true ]; then
  case "$PROVIDER" in
    go-free|go-cheap)
      ;;
    "")
      PROVIDER="go-free"
      ;;
    *)
      echo "Error: --refresh/--dry-run only apply to the Go free/cheap blocks (got '$PROVIDER')" >&2
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
#   # === Zen ===  /  # === Go ===  /  # === Go Free ===  /  # === Go Cheap ===  /  # === Bedrock ===  /  # AWS
# NOTE: this script holds no model IDs for Zen, Go or Bedrock — it only moves '#'
# markers. The Go Free and Go Cheap blocks are the exception: they are regenerated
# from the live catalogue on every `go-free` / `go-cheap` switch (see
# discover_free_models / discover_cheap_models).
ZEN_RANGE='/^# === Zen ===/,/^# === Go ===/'
GO_RANGE='/^# === Go ===/,/^# === Go Free ===/'
GO_FREE_RANGE='/^# === Go Free ===/,/^# === Go Cheap ===/'
GO_CHEAP_RANGE='/^# === Go Cheap ===/,/^# === Bedrock ===/'
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
for marker in '# === Zen ===' '# === Go ===' '# === Go Free ===' '# === Go Cheap ===' '# === Bedrock ===' '# AWS'; do
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

# per-1M-token input price (USD) ceiling for the "cheap" tier. A model is cheap if
# 0 < input.price <= CHEAP_MAX_INPUT (so the $0 free models are excluded). This
# includes the Contributor models (discounted in exchange for training consent) and
# the cheap "flash"/"luna"/"haiku" variants. Change under review, not by hand.
CHEAP_MAX_INPUT=0.20

# Prints the cheap (non-free, low-price) Go models as "id|input_price|output_price"
# lines, sorted by input price then id. Prices are USD per 1M tokens.
discover_cheap_models() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "Error: 'curl' is required to discover cheap Go models." >&2
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
  local models
  models=$(printf '%s' "$json" | jq -r --arg max "$CHEAP_MAX_INPUT" '
    .data[]
    | select(
        (.cost.text.input.price > 0)
        and (.cost.text.input.price <= ($max | tonumber))
      )
    | [.id, (.cost.text.input.price | tostring), (.cost.text.output.price | tostring)]
    | join("|")
  ' | sort -t'|' -k2,2n -k1,1)
  if [ -z "$models" ]; then
    echo "Error: no cheap Go models found (input <= $CHEAP_MAX_INPUT) in $CATALOG_URL" >&2
    return 1
  fi
  printf '%s\n' "$models"
}

# The Go Free block is stored commented in .env.example and becomes active only
# after a switch. Preserve whichever state it is already in, so a re-run does not
# leave the block active when the user only meant to refresh it.
go_free_prefix() {
  local existing
  existing=$(sed -n '/^# === Go Free ===/,/^# === Go Cheap ===/p' "$ENV_FILE" | grep -m1 -E '^#?MODEL_[A-G]=' || true)
  case "$existing" in
    '#MODEL_'*) printf '%s' '#' ;;
    'MODEL_'*) printf '%s' '' ;;
    *) printf '%s' '#' ;;
  esac
}

# Same as go_free_prefix, for the Go Cheap block (delimited by Go Cheap -> Bedrock).
go_cheap_prefix() {
  local existing
  existing=$(sed -n '/^# === Go Cheap ===/,/^# === Bedrock ===/p' "$ENV_FILE" | grep -m1 -E '^#?MODEL_[A-G]=' || true)
  case "$existing" in
    '#MODEL_'*) printf '%s' '#' ;;
    'MODEL_'*) printf '%s' '' ;;
    *) printf '%s' '#' ;;
  esac
}

# Prints the seven assignments joined by '|' for the given model IDs. Repeats the
# IDs round-robin when there are fewer IDs than the seven tiers (the free case),
# or takes the first seven when there are more (the cheap case). Kept in one place
# so --dry-run and the real rewrite cannot drift.
round_robin_assignments() {
  local ids="$1" prefix="$2" count i=0 id tier joined=""
  count=$(printf '%s\n' "$ids" | wc -l | tr -d ' ')
  for tier in A B C D E F G; do
    id=$(printf '%s\n' "$ids" | sed -n "$((i % count + 1))p")
    joined="${joined}${prefix}MODEL_${tier}=opencode-go/${id}|"
    i=$((i + 1))
  done
  printf '%s' "${joined%|}"
}

# Prints the seven tiers with their assigned cheap model and its per-1M-token
# price. $1 = "id|in|out" lines (sorted). Uses the same round-robin indexing as
# round_robin_assignments so the printed prices match what gets written.
print_cheap_prices() {
  local models="$1" count i=0 line id pin pout
  count=$(printf '%s\n' "$models" | wc -l | tr -d ' ')
  for tier in A B C D E F G; do
    line=$(printf '%s\n' "$models" | sed -n "$((i % count + 1))p")
    id=${line%%|*}
    pin=${line#*|}; pin=${pin%%|*}
    pout=${line##*|}
    printf '  MODEL_%s  opencode-go/%s  $%s in / $%s out\n' "$tier" "$id" "$pin" "$pout"
    i=$((i + 1))
  done
}

rewrite_go_free_block() {
  local assignments="$1" tmp
  tmp="$(dirname "$ENV_FILE")/.tmp/switch-provider.$$.tmp"
  mkdir -p "$(dirname "$tmp")"
  GO_FREE_ASSIGNMENTS="$assignments" awk '
    BEGIN { n = split(ENVIRON["GO_FREE_ASSIGNMENTS"], arr, "|"); inserted = 0 }
    /^# === Go Free ===/ { inblock = 1; print; next }
    /^# === Go Cheap ===/ {
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

rewrite_go_cheap_block() {
  local assignments="$1" tmp
  tmp="$(dirname "$ENV_FILE")/.tmp/switch-provider.$$.tmp"
  mkdir -p "$(dirname "$tmp")"
  GO_CHEAP_ASSIGNMENTS="$assignments" awk '
    BEGIN { n = split(ENVIRON["GO_CHEAP_ASSIGNMENTS"], arr, "|"); inserted = 0 }
    /^# === Go Cheap ===/ { inblock = 1; print; next }
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

# Prints the model ID assigned to MODEL_<tier> in the active (uncommented) block,
# or nothing if the tier is unset. Uses grep + sed rather than an associative
# array so the script still runs under macOS' default bash 3.2.
active_value() {
  grep -E "^MODEL_$1=" "$ENV_FILE" 2>/dev/null | sed "s/^MODEL_$1=//" || true
}

# Reports which model each agent is pinned to. The agent -> tier mapping is read
# from .opencode/opencode.json (the authoritative source) rather than hardcoded,
# and the tier -> model value comes from the active block in .env, so the report
# always reflects what a restart of opencode would actually use.
print_agent_mapping() {
  echo
  echo "Model assigned to each agent (active block):"
  if [ ! -f "$CONFIG_JSON" ]; then
    echo "  (warning: $CONFIG_JSON not found — cannot map agents to tiers)" >&2
    return 0
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "  (warning: 'jq' not found — cannot map agents to tiers)" >&2
    return 0
  fi
  local tiers_agents value agents tier
  tiers_agents=$(jq -r '.agent | to_entries[] | [(.value.model | sub("\\{env:"; "") | sub("\\}"; "")), .key] | @tsv' "$CONFIG_JSON")
  for tier in A B C D E F G; do
    value=$(active_value "$tier")
    printf '  MODEL_%s  %s\n' "$tier" "${value:-<unset>}"
    if [ "$tier" = "D" ]; then
      printf '           (small_model — no agent assigned)\n'
      continue
    fi
    agents=$(printf '%s\n' "$tiers_agents" | awk -F'\t' -v t="MODEL_$tier" '$1==t {print $2}' | sort | awk '{ if (NR>1) printf ", "; printf "%s", $0 }')
    if [ -n "$agents" ]; then
      printf '           %s\n' "$agents"
    else
      printf '           (no agent assigned)\n'
    fi
  done
  echo "  Note: MODEL_A is also the default/main model; MODEL_D is the small_model."
}

FREE_COUNT=0
FREE_IDS=""

if [ "$PROVIDER" = "go-free" ]; then
  if [ "$OFFLINE" = true ]; then
    echo "Offline: using the existing Go Free block; it may be stale."
    if [ "$DRY_RUN" = true ]; then
      echo
      echo "Existing Go Free block:"
      sed -n '/^# === Go Free ===/,/^# === Go Cheap ===/p' "$ENV_FILE" | grep -E '^#?MODEL_[A-G]=' | sed 's/^/  /'
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
      round_robin_assignments "$FREE_IDS" "$(go_free_prefix)" | tr '|' '\n' | sed 's/^/  /'
      echo
      echo "Dry run: no files changed."
      exit 0
    fi
    rewrite_go_free_block "$(round_robin_assignments "$FREE_IDS" "$(go_free_prefix)")"
    if [ "$REFRESH" = true ]; then
      echo "Refreshed the Go Free block in $ENV_FILE (no provider switch)."
      exit 0
    fi
  fi
fi

if [ "$PROVIDER" = "go-cheap" ]; then
  if [ "$OFFLINE" = true ]; then
    echo "Offline: using the existing Go Cheap block; prices may be stale."
    if [ "$DRY_RUN" = true ]; then
      echo
      echo "Existing Go Cheap block:"
      sed -n '/^# === Go Cheap ===/,/^# === Bedrock ===/p' "$ENV_FILE" | grep -E '^#?MODEL_[A-G]=' | sed 's/^/  /'
      echo
      echo "Dry run: no files changed."
      exit 0
    fi
  else
    CHEAP_MODELS=$(discover_cheap_models)
    CHEAP_IDS=$(printf '%s\n' "$CHEAP_MODELS" | cut -d'|' -f1)
    CHEAP_COUNT=$(printf '%s\n' "$CHEAP_MODELS" | wc -l | tr -d ' ')
    echo "Discovered $CHEAP_COUNT cheap Go model(s) (input <= \$$CHEAP_MAX_INPUT per 1M tokens) from $CATALOG_URL:"
    printf '%s\n' "$CHEAP_MODELS" | awk -F'|' '{ printf "  - %s  $%s in / $%s out\n", $1, $2, $3 }'
    echo
    echo "Selected cheap models (one per tier, USD per 1M tokens):"
    print_cheap_prices "$CHEAP_MODELS"
    if [ "$DRY_RUN" = true ]; then
      echo
      echo "Dry run: no files changed."
      exit 0
    fi
    rewrite_go_cheap_block "$(round_robin_assignments "$CHEAP_IDS" "$(go_cheap_prefix)")"
    if [ "$REFRESH" = true ]; then
      echo "Refreshed the Go Cheap block in $ENV_FILE (no provider switch)."
      exit 0
    fi
  fi
fi

if [ "$PROVIDER" = "agents" ]; then
  print_agent_mapping
  exit 0
fi

case "$PROVIDER" in
  zen)
    echo "Switching to OpenCode Zen..."
    for range in "$GO_RANGE" "$GO_FREE_RANGE" "$GO_CHEAP_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$ZEN_RANGE"
    ;;
  go)
    echo "Switching to OpenCode Go..."
    for range in "$ZEN_RANGE" "$GO_FREE_RANGE" "$GO_CHEAP_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$GO_RANGE"
    ;;
  go-free)
    echo "Switching to OpenCode Go (free models only)..."
    for range in "$ZEN_RANGE" "$GO_RANGE" "$GO_CHEAP_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$GO_FREE_RANGE"
    if [ "$OFFLINE" != true ]; then
      echo "Note: $FREE_COUNT free model(s) repeat across tiers, so family separation"
      echo "      between authors and review gates is not met. Use for exploration only."
    fi
    ;;
  go-cheap)
    echo "Switching to OpenCode Go (cheap models only)..."
    for range in "$ZEN_RANGE" "$GO_RANGE" "$GO_FREE_RANGE" "$BEDROCK_RANGE"; do comment_block "$range"; done
    uncomment_block "$GO_CHEAP_RANGE"
    ;;
  bedrock)
    echo "Switching to AWS Bedrock..."
    for range in "$ZEN_RANGE" "$GO_RANGE" "$GO_FREE_RANGE" "$GO_CHEAP_RANGE"; do comment_block "$range"; done
    uncomment_block "$BEDROCK_RANGE"
    ;;
esac

print_agent_mapping

echo "Done. Restart opencode for the change to take effect."
