# ADR-069: SSE Doorbell Catch-Up Read on Stream Open

**Status:** Accepted
**Date:** 2026-09-06
**Deciders:** @tech-lead, @architecture-guardian
**Story:** EOP-224

## Context

The real-time mechanism is a bare SSE doorbell (ADR-014, ADR-034). `GET /api/v1/sessions/{id}/events` emits `event:`/`data:` frames that carry no payload the client uses; each frame simply means "session state changed, re-read it". There is no `Last-Event-ID`, no replay, and no history on the server — `SseSessionEventPublisher` publishes to whoever is subscribed at that instant.

The client helper is `subscribeToSession` in `ui/src/api.ts` (it uses `fetch` + `ReadableStream` rather than `EventSource`, because `EventSource` cannot set the custom player-token header — ADR-015). At the time of this decision it had two and only two call sites, `ui/src/components/LobbyScreen.tsx` and `ui/src/components/GameScreen.tsx`. There is now a third — see the EOP-233 amendment.

## The Defect

Both call sites had a mount effect shaped:

```ts
const setup = async () => {
  await refreshState();                 // read FIRST
  const subscription = subscribeToSession(sessionId, playerToken, onEvent, onError);   // subscribe SECOND
  ...
};
```

Any event published in the gap between the read completing and the server registering the subscriber was **delivered to nobody and lost for ever**, because nothing re-read once the stream opened. Every mount had one such window, and the lobby→game transition (which tears down one stream and opens another) had two.

This was observed end-to-end in a three-browser E2E run (EOP-217). Aligned request timeline for one participant, from the Playwright trace (times relative to the first request of the scenario):

- `+0.553s` participant's `GET .../hand` → **409** (`HandNotDealtException` — the deal had not committed yet)
- `+0.554s` facilitator's `POST .../deal` → 204, took 23.7 ms, so the deal committed at **≈+0.578s** and the `cards-dealt` doorbell was published then
- `+0.560s` participant's replacement `GET .../events` issued, headers received **≈+0.585s**

So the doorbell was published while that participant's stream was still being established. `GameScreen`'s 409 branch sets `hand = null` and renders `Waiting for cards to be dealt...` with **no retry, no polling and no timer**, so the participant sat in that state indefinitely. The E2E assertion surfaced it as `Hana-chromium was never dealt a hand`.

**This is user-visible, not a test artefact:** a real player whose deal notification lands during the screen transition waits for ever, with no error and no way to recover but a manual reload.

## Decision

### 1. `subscribeToSession` gains an optional `onOpen` callback

The function signature changes from:

```ts
subscribeToSession(sessionId: string, playerToken: string, onEvent: () => void, onError: (e: Error) => void): Promise<() => void>
```

to:

```ts
subscribeToSession(sessionId: string, playerToken: string, onEvent: () => void, onError: (e: Error) => void, onOpen?: () => void): Promise<() => void>
```

The `onOpen` callback is invoked exactly once, immediately after the response is confirmed `ok` and **before** the body is read. It is optional so a caller with nothing to re-read may omit it.

### 2. Both call sites pass a catch-up read

- `LobbyScreen.tsx` passes `onOpen: refreshSession`
- `GameScreen.tsx` passes `onOpen: refreshGameState`

Both are the same read functions the caller already uses on every doorbell event; they are simply invoked once more at stream open.

### The correctness argument

Let `T` be the instant the server registers the subscriber; it necessarily precedes the response headers the client observes. Everything published after `T` arrives on the stream. Everything published before `T` is visible to a read issued after the headers. A caller that re-reads in `onOpen` therefore has **no interval in which a change can be both undelivered and unobserved**. This is deterministic — no polling, no timers, and no server-side replay or `Last-Event-ID` required.

### Rejected alternatives

- **Reorder to subscribe-then-read.** Rejected: it narrows the window but does not close it (an event can still land between the subscribe being issued and the read observing state), and it makes first paint wait on the stream handshake, which was measured at over six seconds under contention.

- **Poll while in the 409 waiting state.** Rejected: it fixes one symptom, leaves every other lost doorbell unfixed, and adds timers to a design whose whole point is to avoid them.

- **Server-side event replay via `Last-Event-ID`.** Rejected as disproportionate: it requires per-session event history and ordering guarantees the doorbell design deliberately avoids (ADR-034), when a single catch-up read achieves the same guarantee client-side.

## Consequences

**Positive:**

- The race window is closed deterministically. No event published while the stream is being established can be lost.
- The fix is local to the API helper and the two call sites; no server change.
- No new dependencies, no new state, no timers.

**Negative — stated plainly:**

- **`onOpen` is optional in the signature but not optional in spirit.** Any future caller of `subscribeToSession` that renders server state must pass a catch-up read, or it reintroduces the defect. This is enforced by review only — no build gate detects a missing `onOpen` at a new call site.

- **The same investigation revealed a separate, unfixed defect.** Requests belonging to a single game session block for up to a full 15-second SSE heartbeat period and are released together on the heartbeat tick. This is tracked separately and is **not** resolved by this ADR. The E2E happy-path suite does not go green on this fix alone.

  **Amended 2026-09-06 (EOP-227).** That defect is now root-caused and fixed by [ADR-070](ADR-070-jdbc-connection-leak-via-open-in-view.md), so this bullet no longer describes an open issue. Two corrections to it are worth recording, because both were reasoned and both were wrong. The blocking was **not** "requests belonging to a single game session": it was global, and a request belonging to no session at all (`GET /api/v1/cards`) stalled for 25 seconds in the same window. The appearance of per-session clustering was an artefact of `e2e/playwright.config.ts` setting `workers: 1` and `fullyParallel: false`, so only one session was ever in flight — the clusters carried no information about what the blocked resource was keyed on, and reading them as evidence of per-session keying is what excluded the connection pool from suspicion for as long as it did. Nor was the heartbeat the *cause*; it was the release mechanism, which is why it set the period so cleanly. The actual cause was one JDBC connection leaked per open SSE stream, and with that fixed the suite goes green 15 of 15.

## Related

- [ADR-014](ADR-014-realtime-transport.md) — SSE doorbell design
- [ADR-015](ADR-015-player-identity.md) — why `fetch` rather than `EventSource`
- [ADR-034](ADR-034-sse-subscriber-cap-and-emitter-timeout.md) — subscriber cap and emitter timeout
- [ADR-045](ADR-045-frontend-response-validation.md) — client-side response validation, the other boundary this fix touches
- [ADR-070](ADR-070-jdbc-connection-leak-via-open-in-view.md) — the separate defect disclosed above, root-caused and fixed under EOP-227
- `ui/src/api.ts` — the `subscribeToSession` helper
- `ui/src/components/LobbyScreen.tsx` — the first call site
- `ui/src/components/GameScreen.tsx` — the second
- `ui/src/components/GameOverScreen.tsx` — the third, added by EOP-233
- EOP-217 — the E2E run that surfaced the defect
- EOP-224 — this fix
- EOP-233 — the third call site, which adopts this decision

## Amendment, 2026-09-08 (EOP-233)

`subscribeToSession` now has **three** call sites, not two. EOP-233 gave `GameOverScreen` the session
subscription it had never had — it was the one screen in the application that opened none, which is
why a participant was stranded on the finished game's leaderboard when the facilitator started a
second game. The Context section above has been corrected to say "at the time of this decision".

**The new call site adopts this decision rather than working around it, and its own reason for needing
the catch-up read is the sharper case of the two.** `GameOverScreen` passes an `onOpen` callback that
re-reads the session, exactly as the other two do. The gap this ADR closes is not hypothetical there:
the facilitator's `POST /new-game` publishes `HAND_DEALT` and then returns its `204`, so a participant
whose stream registered a moment after that publish would receive no frame for it, ever — and unlike
the lobby, the game-over screen has no periodic re-read to fall back on. Without the `onOpen` read it
would have reproduced the very defect it was written to fix, in a narrower window.

One difference from the two original call sites, recorded because it looks like a divergence and is
not one. `LobbyScreen` and `GameScreen` do the initial read *before* subscribing, inside an async
`setup()`. `GameOverScreen` subscribes directly in its effect, because its initial read is a different
request in a different effect (`getLeaderboard`, not the session read the doorbell provokes), and
React runs effects in order regardless. The ordering property this ADR turns on — that a frame
published between the first read and the subscriber registering is recoverable — is supplied by the
`onOpen` read in all three, not by where the subscribe call sits.
