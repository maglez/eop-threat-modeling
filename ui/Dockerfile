# syntax=docker/dockerfile:1

# Front-end image: build the static bundle with Node, serve it with Caddy.
#
# Two stages so that Node, npm and ~280 development dependencies never reach
# the runtime image. Exactly 6 packages are production dependencies — verify
# with `npm ls --all --omit=dev` against the full `npm ls --all` tree. Treat the
# dev figure as indicative, not pinned: it moves with every toolchain bump and
# with the build platform's optional binaries, so the number installed inside
# this linux image differs from the number on a developer's machine.
# What ships is a web server and a directory of files.

FROM node:22-alpine AS build

WORKDIR /build

# Manifests first so that the dependency layer is only invalidated when the
# dependencies themselves change, not when a component is edited.
COPY package.json package-lock.json ./

# `npm ci` installs exactly the locked tree and fails if the lockfile and
# manifest disagree. `npm install` would silently resolve a different tree,
# which is the wrong behaviour for a reproducible build.
RUN npm ci

COPY tsconfig.json vite.config.ts index.html ./
COPY scripts ./scripts
COPY src ./src

# Vite build-time feature flags — baked into the JS bundle at build time.
# Pass --build-arg VITE_GAME_SCREEN_ENABLED=true (or false) to override.
# Default is false (fail-closed) so that a plain `docker build` ships a dark
# build; the CI pipeline and scripts/update-local.sh both pass the flag
# explicitly to control the deployed position.
#
# Both ARG and ENV are required for each flag. Vite's loadEnv reads process.env,
# which a build ARG alone does not populate, and ui/.dockerignore is default-deny
# with no .env* allow-list — so ARG plus ENV is the only channel a flag has into
# the bundle (ADR-037, EOP-78 amendment).
ARG VITE_GAME_SCREEN_ENABLED=false
ENV VITE_GAME_SCREEN_ENABLED=$VITE_GAME_SCREEN_ENABLED
ARG VITE_FOLLOW_SUIT_HINT_ENABLED=false
ENV VITE_FOLLOW_SUIT_HINT_ENABLED=$VITE_FOLLOW_SUIT_HINT_ENABLED

# The prebuild hook copies the GOV.UK Design System fonts and images into
# public/ before Vite runs. Without it the stylesheet's absolute asset paths
# 404 at runtime and the page renders in fallback system fonts, which looks
# like a styling choice rather than a missing file.
RUN npm run build

# Quality gates (typecheck, lint, test) deliberately do NOT run here. They
# run as their own pipeline job, so a failing test fails the pipeline with a
# readable report instead of failing an image build with a Docker log.

FROM caddy:2-alpine AS runtime

# The official Caddy image runs as root and ships no unprivileged user, so create
# one. The same identifiers are used as the application image for consistency.
RUN addgroup -S -g 10001 web && adduser -S -u 10001 -G web -H -D web

# The base image ships a caddy user and group. Running the web server as
# root to avoid one port mapping is not a trade worth making.
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /build/dist /srv

# Caddy binds 8080, not 443, because an unprivileged process cannot bind a
# port below 1024. The container stack maps host 443 to this (EOP-21).
EXPOSE 8080

USER web

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-check-certificate -q -O /dev/null https://localhost:8080/index.html || exit 1

CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
