#!/usr/bin/env bash
# =============================================================================
# update-local.sh — rebuild both local Docker images and restart the app stack
# =============================================================================
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# CI builds and publishes images to GHCR on every push to `main`, but the
# local compose.app.yml defaults to `eop-threat-modeling:local` and
# `eop-ui:local` — tags that are only updated when a developer manually runs
# `docker build`.  There is no mechanism that keeps those local tags in sync
# with `main`.
#
# This gap caused a real incident (EOP-76): the running stack was built on
# Aug 17 07:25 (commit 9a90efd, EOP-68), while the database had all subsequent
# Liquibase migrations applied (including EOP-75's 68-card deck).  The app code
# expected 78 cards; the database held 68.  The deal failed silently,
# `current_leader_seat` was never written, `seatToPlay` came back `undefined`,
# and the opening player's drag was blocked by `isMyTurn = false` — the card
# snapped back with no error shown.
#
# WHAT THIS SCRIPT DOES
# ---------------------
# 1. Rebuilds `eop-threat-modeling:local` from the current working tree
#    (the backend Dockerfile at the repo root).
# 2. Rebuilds `eop-ui:local` from the current working tree
#    (the frontend Dockerfile under ui/).
# 3. Restarts `eop-app` and `eop-caddy` with --force-recreate so they pick up
#    the new images.
# 4. Leaves `eop-postgres` untouched — data is preserved.
#
# IMPORTANT: this script rebuilds from the CURRENT WORKING TREE, not from
# GHCR.  Run `git pull` first to ensure you are building from the latest main.
#
# OBSOLESCENCE
# ------------
# This script is a temporary convenience.  Once the project is wired up to AWS
# (Terraform + CD pipeline), every merge to `main` will deploy automatically
# and the local stack will no longer be the primary test target.  At that point
# this script can be deleted.
#
# USAGE
#   ./scripts/update-local.sh
#
# PREREQUISITES
# - Docker daemon running (e.g. `colima start`)
# - compose.app.yml stack already up (`docker compose -f compose.app.yml up -d`)
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> [1/4] Building backend image: eop-threat-modeling:local"
docker build -t eop-threat-modeling:local "$REPO_ROOT"

echo "==> [2/4] Building frontend image: eop-ui:local"
docker build \
  --build-arg VITE_GAME_SCREEN_ENABLED=true \
  --build-arg VITE_FOLLOW_SUIT_HINT_ENABLED=true \
  -t eop-ui:local "$REPO_ROOT/ui"

echo "==> [3/4] Restarting eop-app and eop-caddy (postgres is left untouched)"
docker compose -f "$REPO_ROOT/compose.app.yml" up -d \
  --force-recreate \
  --no-deps \
  app caddy

echo "==> [4/4] Done. Waiting for eop-app to become healthy..."
for i in $(seq 1 30); do
  STATUS="$(docker inspect --format='{{.State.Health.Status}}' eop-app 2>/dev/null || echo "missing")"
  if [ "$STATUS" = "healthy" ]; then
    echo "    eop-app is healthy."
    exit 0
  fi
  echo "    ($i/30) eop-app status: $STATUS — waiting 5s..."
  sleep 5
done

echo "ERROR: eop-app did not become healthy within 150s." >&2
echo "       Check logs with: docker logs eop-app" >&2
exit 1
