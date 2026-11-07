# Runtime View

Dynamic behaviour of the session lifecycle, in Mermaid `sequenceDiagram` form. The
static counterpart — what exists and how it is wired — is
[`C4-Diagrams.md`](C4-Diagrams.md).

Everything here reflects the code as it stands after **EOP-92** (`Hands.deal` deals every card
again, so hands are uneven and the final trick is short again at 3, 5 and 6 players — no sequence
below states hand-size arithmetic, so none of them changes; ADR-023 as amended by EOP-92), on top of
**EOP-82** (all three `eop.features` flags now
ship `true`, so every sequence below is live in the default configuration — ADR-042; the
client-side handling of a leaderboard 404 is recorded in ADR-042 rather than duplicated here), on
top of **EOP-65** (game-over leaderboard,
new-game reset — ADR-039), on top of **EOP-15 Slice C** (end-of-game
transitions), on top of Slice B's score route, Slice A's pure-domain scoring, EOP-14 Slice E
(end of hand, the state-of-play read and the three broadcasts), Slice D's trick-play HTTP
routes, Slice C2's use-case layer, Slice C1's persistence layer (Liquibase changeset `005`),
Slice B's trick-play schema (changeset `004`) and the client-address resolution from EOP-26
(ADR-021). Sequences 1 to 3 are the EOP-10 session lifecycle and **neither Slice D nor Slice E
alters them.** Sequences 4, 5 and 6 are dealing, playing and resolving. Slice E changes all
three: each now ends in an SSE broadcast, and sequence 6 records the end of a hand instead of
writing a placeholder. Sequence 7 is the score read (EOP-15 Slice B). Sequence 8 is the
end-of-game transition (EOP-15 Slice C).

**They now begin at a caller that exists.** Three earlier versions of this paragraph said the
opposite — that Slice C2 added no route, no controller method and no DTO, and that the first
participant of each new sequence was drawn as a caller a later slice would supply. Slice D supplies
it, and Slice E adds a fifth route: `TrickController` maps
`POST /api/v1/sessions/{sessionId}/deal`,
`GET /api/v1/sessions/{sessionId}/hand`, `POST /api/v1/sessions/{sessionId}/plays`,
`GET /api/v1/sessions/{sessionId}/tricks/current` and
`POST /api/v1/sessions/{sessionId}/tricks/current/resolve`, each returning a transport record rather
than a domain object. EOP-15 Slice B adds `GET /api/v1/sessions/{sessionId}/score` via
`ScoreController`, and Slice C adds `POST /api/v1/sessions/{sessionId}/end` via
`EndSessionController`. All three controllers and all seven use-case beans exist only while
`eop.features.trick-play` is `true`, which `application.yml` has set as the shipped default since
EOP-70 ([ADR-040](../adr/ADR-040-trick-play-flag-on.md)), so the routes answer for real. The flag
stayed `false` from Slice D until EOP-70 for reasons that were not about gameplay, recorded in
[ADR-028](../adr/ADR-028-end-of-hand-without-release-or-score.md); the flag and its
`@ConditionalOnProperty` guards remain in place, so the position is still testable in both
directions. The refusals were always real:
`GlobalExceptionHandler` maps every exception drawn below, including
`NoTrickToResolveException` at `GlobalExceptionHandler.java:484` and `TrickNotCompleteException` at
`:518`, both 409, joined in Slice E by `HandCompleteException` at `:449`, also 409, and in Slice C
by `SessionNotInProgressException` at `:226`, also 409.

There is deliberately **no seventh sequence for `GET /api/v1/sessions/{sessionId}/hand`.** A
sequence diagram earns its place when the order of interactions is the thing worth pinning, and
that read has one: resolve the caller, load the hands, return the caller's own. Its one interesting
property is an ordering already drawn in sequences 4 to 6 — authorisation precedes the repository
call, so a failed credential never reaches storage — and its substantive property is a prohibition
rather than an interaction, namely that no route, use case or DTO returns a hand the caller does not
hold, which [ADR-027](../adr/ADR-027-singleton-subresource-naming.md) records because a diagram
cannot record an absence.

Slice B changed the *failure mode* of writes that already have sequences below: a forged seat and a
ghost player are rejected by the database rather than reaching it, which
[ADR-023](../adr/ADR-023-deal-remainder-and-turn-order.md) records — in the second and fourth of
the four Slice C obligations under *What changeset `004` deliberately does not enforce* — as owing
a use-case-level rejection, so the caller sees a 403-shaped refusal rather than a constraint
violation. **Slice C2 discharges that on the play path by construction rather than by a check, and
Slice D preserves it across the HTTP boundary.**
`PlayCardCommand` has no seat component and no player component at all
(`PlayCardCommand.java:35-41`, argued at `:10-17`), so `PlayCardService` derives the acting seat
from the resolved player (`PlayCardService.java:154-167`): a caller cannot name a seat it does not
hold, which makes the 403 with `NotYourSeatException` inexpressible from the outside rather than
merely refused. `PlayCardRequest` — the request record Slice D adds — carries no seat, player, suit
or rank either, so the property survives the one change that could have quietly undone it, and a
test posts a body naming all four for an impostor and asserts the published play still carries the
token's seat and the deck's suit. A caller outside the session is refused with
`PlayerNotInSessionException` (`PlayCardService.java:162-175`), a 404 chosen so the status does not
itself disclose that the session exists. If a future slice adds or alters an interaction, this
paragraph is the first thing to correct — one version of it claimed EOP-10 two stories after that
stopped being the whole truth, the version before last claimed Slice C1 one slice after the same
thing, the one before this claimed the caller did not exist for a whole slice after Slice D built it,
and Slice E shipped a controller with a fifth route and three broadcasts while this whole file still
described four routes and total silence.

Where a sequence has a weakness, the prose says so rather than leaving the diagram to imply
everything is fine.

---

## 1. The reconnect path — a re-read, never a replay

This is the most important sequence in the application, and the one whose absence from
the documentation was most costly: it is load-bearing in **both** ADR-014 and ADR-019,
and neither ADR draws it.

A player refreshes, or their laptop wakes, or the container was rebuilt underneath
them. They call `GET /api/v1/sessions/{sessionId}` and get the complete current state
back. **No events are replayed, because none were ever kept.**

```mermaid
sequenceDiagram
    autonumber
    participant BR as Browser tab
    participant CY as Caddy
    participant SC as SessionController
    participant RP as ResolvePlayerService
    participant GS as GetSessionStateService
    participant SRA as SessionRepositoryAdapter
    participant DB as PostgreSQL

    Note over BR: Refresh. The SSE connection is gone.<br/>The token is still in sessionStorage.

    BR->>CY: GET /api/v1/sessions/{sessionId}<br/>X-EoP-Player-Token
    CY->>SC: proxied, same origin

    SC->>RP: execute(sessionId, playerToken)
    RP->>SRA: findById(sessionId)

    SRA->>DB: SELECT from game_session WHERE id = ?
    DB-->>SRA: row, or empty
    SRA->>DB: SELECT from player WHERE game_session_id = ?<br/>ORDER BY seat_order ASC
    DB-->>SRA: seated players
    SRA-->>RP: GameSession aggregate

    alt session unknown
        RP-->>SC: SessionNotFoundException
        SC-->>BR: 404 problem+json
    else token missing or unrecognised
        Note over RP: SHA-256 the presented token,<br/>compare digests. Absent and wrong<br/>are refused identically.
        RP-->>SC: PlayerNotRecognisedException
        SC-->>BR: 403 problem+json
    else recognised
        RP-->>SC: ResolvedPlayer
        SC->>GS: execute(resolved)
        GS-->>SC: session state
        SC-->>BR: 200 SessionStateDto<br/>status, players, seat order, roles
    end

    Note over BR,DB: Both reads are database reads.<br/>No registry, cache or subscriber list contributes.

    BR->>CY: GET /api/v1/sessions/{sessionId}/events
    Note over BR: Only now re-subscribe.<br/>State first, stream second.
```

### Why this shape, and what it buys

**The reconnect path and the first-load path are the same request.** There is no
separate recovery endpoint and no recovery-only code. Consequently the recovery path is
exercised by every page load, rather than only when something has already gone wrong —
which is the opposite of how recovery code normally rots. This was the property ADR-014
was aiming at; this sequence is it, made concrete.

**`Last-Event-ID` is deliberately not honoured.** The browser sends it on an automatic
SSE reconnect, and the server ignores it. Honouring it would mean persisting an event
log — a second source of truth alongside the game state it describes — and after a
restart the log would be empty anyway. The EOP-8 spike confirmed the failure directly:
the event counter had reset to zero and the subscriber list was empty, so a client
reconnecting with `Last-Event-ID: 47` was asking for events the server had no memory
of.

**State first, stream second.** The client fetches state and only then subscribes. The
reverse order has a gap: an event arriving between subscribing and the state response
being rendered describes a change the client cannot yet place. Fetching first means the
stream only ever reports changes *after* a known-good baseline. There is still a
narrow window — an event fired between the state read committing and the subscription
being registered is lost — and the cost is bounded precisely because events carry no
state: the client's remedy is another re-read, which is this same sequence.

**Both reads are database reads, on purpose.** `SessionRepositoryAdapter.assemble`
reads the session row and then the player rows. Nothing is served from the emitter
registry or from any cache, which is what makes the first request after a restart
behave exactly like the thousandth request before it.

**403 rather than 401, and identical refusals.** There is no authentication scheme here
— no realm, nothing to retry differently — so a 401 with `WWW-Authenticate` would
advertise a challenge that does not exist. A missing token and a wrong token produce the
same 403, so the endpoint cannot be used to confirm which tokens are real.

### The custody and recovery path — both halves now work

**EOP-11 delivered both halves.** The token is written to `sessionStorage` under
`eop_session` on admission, and `App.tsx` rehydrates straight into the lobby on boot
(after checking the feature flag), so "a player refreshes and resumes" is an observable
behaviour of the product rather than a property of the server exercised only by tests
and `curl -H` (ADR-015).

The *failure* arm also works. The 403 that an expired session produces routinely under
ADR-036's 24-hour TTL is detected by branching on the numeric `status` carried by
`ApiError` — `err instanceof ApiError && (err.status === 403 || err.status === 404)` —
in both the `refreshSession` callback and the SSE subscription error handler in
`LobbyScreen.tsx` (the `onError` callback passed to `subscribeToSession` in `api.ts`;
`api.ts` constructs the `ApiError` and invokes the caller's `onError`, while the status
branching and the `onSessionEnd()` call live in `LobbyScreen`). Either path calls `onSessionEnd()`, which does
`sessionStorage.removeItem(STORAGE_KEY)` and returns the player to the home screen.
The re-read half of "a re-read, never a replay" works; the give-up half works too.

EOP-105 added a **third** route to that same exit, so the two error paths above are no
longer the whole set. `refreshSession` now also gives up when the re-read succeeds but
reports a terminal status: `sessionData.status === 'COMPLETED'` calls `onSessionEnd()`
and returns, before the `IN_PROGRESS` one-shot. That case is not an error — the fetch
returned 200 — which is why it needed its own branch rather than falling out of the
`ApiError` handling. It is reachable whenever the facilitator ends a session while
somebody is still sitting in the lobby: the `game-completed` doorbell arrives, the
lobby re-reads, and the status it gets back is one no lobby view can render. Before
EOP-105 `COMPLETED` was not even in the client's `SessionStatus` union, so that player
was stranded on a screen showing neither a Start button (gated on `LOBBY`) nor a "game
has started" notice (gated on `IN_PROGRESS`).

So the count to carry forward is **three** `onSessionEnd()` triggers in `LobbyScreen`,
not two: the `COMPLETED` branch, the 403/404 branch in `refreshSession`'s `catch`, and
the 403/404 branch in the SSE `onError`. `ABANDONED` deliberately has no branch — the
expiry sweep sets it and deletes the row in the same transaction, so the next fetch is
a 404 and the second route already covers it (see `SessionStatus`'s javadoc for why
that is a property of the only path that writes it today, not of the state itself).

**EOP-108 gave an HTTP `200` a new way to fail, and routed it to an outcome that already
existed rather than adding a fourth `onSessionEnd()` trigger — and that routing is the whole
point.** Be precise about what is new: the *outcome* (message rendered, player stays put) is not,
since any non-403/404 failure — a `500`, a dropped connection — has always reached the same
`catch`, hit `setError` and fallen through the 403/404 test. What EOP-108 added is a new **cause**
able to reach it, and an unprecedented one: a response the server considers successful. Since ADR-045 every
response body is parsed rather than asserted, so a `200` whose body is out of contract now
throws `ContractViolationError extends ApiError` with a synthetic `status = 502` instead of
flowing into React state. That reaches the same `catch`, and because `setError(message)` runs
*before* the `err.status === 403 || err.status === 404` test, a `502` renders the message
through the GOV.UK error summary and then falls through: the player stays in the lobby with
the previously validated state still rendered beneath the error, rather than being ejected.

Keeping it out of the give-up set is the correct call, and for a reason the three existing
triggers do not share. Those three are all judgements that *the session is over* — expired,
deleted, or terminal — and discarding the token is the right response to each. A contract
violation says nothing about the session; it says the **server sent something the client
cannot read**, which is very likely transient (a version skew between a deployed bundle and a
deployed API) and is certainly not grounds for destroying a token that may still be perfectly
valid. Calling `onSessionEnd()` here would convert a recoverable read failure into
irrecoverable data loss on the next `getSession` retry.

Two further properties of this outcome, both deliberate: the lifecycle transitions
(`status === 'COMPLETED'` → `onSessionEnd()`, and the `IN_PROGRESS` one-shot) sit *after* the
parse inside the same `try`, so a rejected payload cannot fire one — which is what makes the
"three triggers" count above still exact rather than merely approximately true. And the state
left on screen is stale-but-valid, never partial: ADR-045 explicitly rejected degrading to a
partial render, because dropping the offending field and rendering the rest would reinstate the
exact defect EOP-108 was raised to remove, an out-of-contract value producing a plausible UI.

So: **three** `onSessionEnd()` triggers, and **four** distinguishable outcomes of a
`refreshSession` call. Because the second count was previously asserted without being
enumerated — which makes it uncheckable, the defect this document exists to avoid — here it
is in full:

1. **Re-read succeeds, session live** — `setSession(sessionData)`, lobby re-renders. If the
   status is `IN_PROGRESS` the one-shot `onGameStarted` handoff fires as part of this outcome;
   it advances the view rather than ending the session, so it is not a fourth trigger.
2. **Re-read succeeds, status terminal** — `COMPLETED` calls `onSessionEnd()`. Trigger 1.
3. **Re-read fails with 403/404** — expired or deleted; `catch` calls `onSessionEnd()`.
   Trigger 2. (Trigger 3 is the same test in the SSE `onError`, not in this handler.)
4. **Re-read fails any other way** — a `500`, a transport error, or since EOP-108 a `200`
   whose body is out of contract: message rendered, token kept, player stays put.

Anyone extending this handler should keep the two counts separate, and should extend the
enumeration above rather than incrementing a bare number.

One asymmetry is worth stating plainly, because it is a consequence of the client
holding its own lifecycle rather than reading the server's. `COMPLETED` reaches the
client two ways and they land in different places. Observed by `GameScreen`, it arrives
through the `onGameOver` callback and `App.tsx` moves to `game-over`, which shows the
leaderboard. Observed by `LobbyScreen`, it goes to `onSessionEnd()` and thence to
`home`, discarding the stored token. That is intentional rather than an oversight: a
player still in the lobby never entered the game and has no score on the leaderboard,
so there is nothing for `game-over` to show them. The rule, stated in full as ADR-009
Decision 4, is that `view.screen` is the client's own state machine and the only thing
that decides which screen renders; `session.status` is consulted for two purposes and no
others — to *gate* what a screen renders while it is mounted (`LobbyScreen.tsx:267`
gates the Start button on `LOBBY`, `:279` gates the started-notice on `IN_PROGRESS`), and
to *leave* a screen the server has moved past. It is never the subject of a lookup that
maps a server state onto a screen name. Note that leaving a screen necessarily names
where you land — the lobby's `IN_PROGRESS` exit reaches `game`, though only when
`VITE_GAME_SCREEN_ENABLED` is `true`, since `App.tsx` gates that `setView` on the flag and
the container default is `false` (`ui/Dockerfile`); with the flag off the read leaves
nothing and `:279`'s gate shows the started-notice instead. Either way that is the same
transition seen from the other side, not a violation.

---

## 2. The SSE stream — subscribe, heartbeat, publish

Three interleaved lifecycles on one connection. The ordering in the first four steps is
the part worth being precise about.

```mermaid
sequenceDiagram
    autonumber
    participant BR as Browser tab
    participant SC as SessionController
    participant RP as ResolvePlayerService
    participant PUB as SseSessionEventPublisher
    participant HB as sse-heartbeat daemon
    participant POOL as sse-send-N pool
    participant JU as JoinSessionService

    rect rgb(238, 244, 250)
    Note over BR,PUB: Subscribe
    BR->>SC: GET /api/v1/sessions/{id}/events<br/>X-EoP-Player-Token
    SC->>RP: execute(sessionId, playerToken)

    alt session or player not found
        RP-->>SC: SessionNotFoundException
        SC-->>BR: 404 problem+json
    else player not authorized
        RP-->>SC: PlayerNotRecognisedException
        SC-->>BR: 403 problem+json
        Note over BR,SC: A problem detail, NOT an empty stream.<br/>resolvePlayerService runs BEFORE subscribe.
    else recognised
        RP-->>SC: ResolvedPlayer
        SC->>PUB: subscribe(sessionId)
        alt subscriber cap reached
            PUB-->>SC: TooManySubscribersException
            SC-->>BR: 429 problem+json
        else cap not exceeded
            PUB->>PUB: new SseEmitter(EMITTER_TIMEOUT_MILLIS) — 10-minute container timeout
            PUB->>PUB: computeIfAbsent(sessionId) then add emitter
            PUB->>PUB: register onCompletion, onTimeout and onError to deregister
            PUB-->>BR: 200 text/event-stream, held open
        end
    end
    end

    rect rgb(245, 245, 238)
    Note over HB,BR: Heartbeat — every eop.realtime.heartbeat-interval
    loop forever, on a daemon thread
        HB->>PUB: beat()
        loop for each emitter
            PUB->>POOL: execute(send task)
            POOL->>BR: emitter.send(heartbeat comment)
            alt write succeeds
                Note over POOL: subscriber still alive
            else write throws
                POOL->>PUB: forgetOne(emitter)
                Note over PUB: Two ways a dead subscriber is ever discovered:<br/>(1) heartbeat sweep — a write fails with<br/>IOException/IllegalStateException, triggering forgetOne<br/>(2) 10-minute emitter timeout — onTimeout fires forgetOne directly.
            end
        end
    end
    end

    rect rgb(240, 247, 240)
    Note over JU,BR: Publish
    JU->>PUB: publish(SessionEvent)
    PUB->>PUB: look up the emitter list for that sessionId
    loop each subscriber of that session only
        PUB->>POOL: execute(send task)
        POOL->>BR: emitter.send(event)
    end
    Note over BR: The event is a notification, not state.<br/>The client re-reads via sequence 1.
    end
```

### Why `resolvePlayerService` runs before `subscribe`

The handler body is two statements, in this order, and the order is the design:

```java
resolvePlayerService.execute(sessionId, playerToken);
return sessionEventPublisher.subscribe(sessionId);
```

Subscribing first and authorising afterwards would mean the response had already
committed as `200 text/event-stream` before the refusal was known. The only remaining
way to reject would be to complete the stream with an error — or worse, to leave it open
and never write to it. An unrecognised caller would receive **an empty stream that looks
like a quiet lobby**, which is indistinguishable from a working connection to a session
where nothing is happening. That is the single most confusing failure this endpoint
could have.

Resolving first means the refusal happens while the response is still a normal HTTP
exchange, so it goes through `GlobalExceptionHandler` and arrives as an RFC 9457
problem detail with a 403 — the same refusal every other endpoint gives.

The credential arrives in the `X-EoP-Player-Token` **header**, and there is no
query-parameter fallback and no code path that would read one. The cost is that the
browser's `EventSource` cannot set headers, so the client must stream with `fetch`; the
alternative was writing a bearer credential into the proxy access log, the browser
history and the address bar of a screen being shared during the very meeting this game
is played in (ADR-019).

### Why the emitter has a 10-minute timeout

`new SseEmitter(EMITTER_TIMEOUT_MILLIS)` sets a 10-minute container timeout (ADR-034). Three reasons:

- **Stale connections are reclaimed.** A client that closes a browser tab without sending a TCP FIN
  (e.g., a laptop lid closed mid-game, a network partition, a crash) is otherwise invisible until
  the heartbeat thread next attempts a write. A 10-minute ceiling means the servlet container also
  closes the async request, returning the file descriptor regardless of whether the heartbeat has
  noticed.

- **Live connections are unaffected.** The heartbeat runs every `eop.realtime.heartbeat-interval`
  (a few seconds). Each successful send resets the container's idle timer, so a well-behaved
  subscriber receiving heartbeats will never reach the 10-minute ceiling.

- **Forced reconnect every 10 minutes is acceptable.** A client that hits the ceiling reconnects
  with `GET /api/v1/sessions/{sessionId}/events`. The `retry:` field written at subscribe time
  tells the browser to wait three seconds before reconnecting, giving a restart-safe reconnect
  without hammering the server.

`spring.mvc.async.request-timeout` is set to `600000` ms so the servlet container's own deadline
matches the emitter timeout exactly. Without this, the container can close the async request before
the emitter fires its own timeout, producing a misleading error log.

### What this sequence cannot do

**A dead subscriber is invisible until the next write or timeout.** The `alt` branch in the
heartbeat block is the *primary* place a broken connection is discovered — the heartbeat
write fails and `forgetOne` is called. The 10-minute emitter timeout provides a backstop:
`onTimeout` fires `forgetOne` directly, without waiting for a heartbeat write to fail.
Between the moment a client disappears and the next heartbeat (or at most ten minutes via
timeout), the registry still lists it. The
subscriber list therefore **over-reports** — measured in the EOP-8 spike, where two
already-dead clients were still counted as two — and must never be used as a presence
list or as an input to a game rule. `connectionStatus` in the state DTO is a display
hint that will sometimes say `CONNECTED` about someone who has gone away.

**A restart drops every subscriber and there is nothing to resume from.** The registry is
in memory and the heartbeat thread is a daemon. On restart both are gone, every client
reconnects, and every reconnect runs sequence 1 from scratch.

**Events are broadcast to a session, not to a player.** Every subscriber of a session
receives every event for it, including the player whose own action caused it. Clients
must tolerate seeing their own change echoed back — once as the HTTP response, once as
the event.

---

## 3. Create, join, start — and where concurrency is actually settled

Included because it is where [ADR-020](../adr/ADR-020-session-concurrency-control.md)
becomes visible, which no other diagram shows.

```mermaid
sequenceDiagram
    autonumber
    participant F as Facilitator
    participant P2 as Player 2
    participant CY as Caddy
    participant SC as SessionController
    participant CAR as ClientAddressResolver
    participant CLIM as InMemorySessionCreationLimiter
    participant LIM as InMemoryJoinAttemptLimiter
    participant JU as JoinSessionService
    participant SRA as SessionRepositoryAdapter
    participant DB as PostgreSQL
    participant PUB as SseSessionEventPublisher

     F->>CY: POST /api/v1/sessions (body ≤ 16 KB enforced by Caddy)
     alt body > 16 KB
         CY-->>F: 413 Request Entity Too Large
     else body within limit
         CY->>SC: POST /api/v1/sessions (proxied)
         SC->>CAR: of(request)
         Note over SC,CAR: X-Forwarded-For is read only if the peer is on<br/>eop.web.trusted-proxies — empty by default.<br/>Otherwise getRemoteAddr wins (ADR-021).
         CAR-->>SC: canonical client address
         SC->>CLIM: checkAllowed(clientAddress) — best-effort pre-check
         alt allowance exhausted (window full)
             CLIM-->>SC: RateLimitedException
             SC-->>F: 429 with Retry-After
         else table saturated (10 000 distinct keys, new address)
             Note over CLIM: Evict aged-out windows first.<br/>If still full, fail-closed: refuse before any DB work (EOP-19, ADR-033).
             CLIM-->>SC: RateLimitedException
             SC-->>F: 429 with Retry-After
         else permitted
             SC->>CLIM: recordCreation(clientAddress) — atomic gate, before DB write
             alt slot refused (race: two threads both cleared checkAllowed)
                 CLIM-->>SC: RateLimitedException
                 SC-->>F: 429 with Retry-After
             else slot reserved
                 SC->>SRA: createLobby(session)
                 SRA->>DB: INSERT game_session, then INSERT player seat 0
                 alt uq_game_session_join_code violated
                     DB-->>SRA: DataIntegrityViolationException
                     SRA-->>SC: JoinCodeUnavailableException
                     Note over SC,SRA: Retry with a fresh code, bounded attempts.<br/>The database decides, not a pre-insert SELECT.
                     opt all five attempts collided
                         SC->>CLIM: refundCreation(clientAddress) — slot returned on failure
                         SC-->>F: 503 with Retry-After — capacity, not fault (EOP-17)
                     end
                 else inserted
                     DB-->>SRA: ok
                 end
                 SRA-->>SC: GameSession
                 SC-->>F: 201 sessionId, joinCode, identityToken
                 Note over F: The token plaintext leaves the server<br/>exactly once, here.
             end
         end
     end

    P2->>SC: POST /api/v1/sessions/{joinCode}/players
    SC->>CAR: of(request)
    Note over SC,CAR: X-Forwarded-For is read only if the peer is on<br/>eop.web.trusted-proxies — empty by default.<br/>Otherwise getRemoteAddr wins. The caller cannot<br/>choose the key the throttle counts against (ADR-021).
    CAR-->>SC: canonical client address
    SC->>JU: execute(joinCode, displayName, clientAddress)
    JU->>LIM: checkAllowed — best-effort pre-check, per IP and per code
    alt allowance exhausted (window full)
        LIM-->>JU: TooManyJoinAttemptsException
        JU-->>SC: refused
        SC-->>P2: 429 with Retry-After
    else table saturated (10 000 distinct keys, new address)
        Note over LIM: Evict aged-out windows first.<br/>If still full, fail-closed: refuse before any DB work (EOP-18).
        LIM-->>JU: TooManyJoinAttemptsException
        JU-->>SC: refused
        SC-->>P2: 429 with Retry-After
    else permitted
        JU->>SRA: findByJoinCode — normalised, upper-cased
        alt no such code
            SRA-->>JU: empty
            JU->>LIM: recordFailure — atomic check-and-record under synchronized(window)<br/>Both address and code windows always recorded (EOP-18)
            Note over LIM: recordFailure throws TooManyJoinAttemptsException if either<br/>window is exhausted after the atomic prune-check-add.<br/>429 supersedes the domain exception (404/409) — throttled<br/>callers receive 429 regardless of session state (EOP-18).
            JU-->>SC: UnknownJoinCodeException
            SC-->>P2: 404 — byte-for-byte identical for every unusable code
        else found
            JU->>SRA: seatPlayer(sessionId, player, occurredAt)
            SRA->>DB: UPDATE game_session SET updated_at, version = version + 1<br/>WHERE id = ? AND status = LOBBY
            Note over SRA,DB: Compare-and-set. This UPDATE takes the row lock<br/>held to end of transaction — THIS is what<br/>serialises two people joining the same instant.
            alt 0 rows affected
                SRA->>DB: one disambiguating read
                SRA-->>JU: SessionNotJoinableException or SessionNotFoundException
                JU-->>SC: refused
                SC-->>P2: 409 or 404
            else 1 row affected
                SRA->>DB: INSERT player
                alt uq_player_session_seat violated
                    DB-->>SRA: DataIntegrityViolationException
                    SRA-->>JU: SeatAlreadyTakenException
                    Note over SRA: Two joiners cannot both take seat 3.<br/>The constraint decides — retry takes the next seat.
                    opt all eight attempts lost their race
                        JU-->>SC: refused
                        SC-->>P2: 409 — the lobby filled while you were joining (EOP-17)
                    end
                else inserted
                    DB-->>SRA: ok
                end
            end
            JU->>PUB: publish(player-joined)
            PUB->>F: event player-joined
            JU-->>SC: SessionAdmission
            SC-->>P2: 200 sessionId, identityToken, seatOrder
        end
    end

    F->>SC: POST /api/v1/sessions/{sessionId}/start
    Note over SC: 403 if the caller is not the facilitator.<br/>409 if fewer than three players.
    SC->>SRA: recordStarted(sessionId, occurredAt)
    SRA->>DB: UPDATE game_session SET status = IN_PROGRESS<br/>WHERE id = ? AND status = LOBBY
    alt 0 rows affected
        SRA-->>SC: already started, or gone
        SC-->>F: 409 or 404
        Note over SRA,DB: A double-clicked start cannot emit<br/>two game-started events.
    else 1 row affected
        SRA-->>SC: ok
        SC->>PUB: publish(game-started)
        PUB->>P2: event game-started
        SC-->>F: 200
    end
```

### What to take from this one

**The `UPDATE` before the `INSERT` is not housekeeping.** It looks like it only bumps
`updated_at`. It is in fact the lock acquisition that orders concurrent joiners on the
parent row before any of them inserts a child row, and it is the most
deletable-looking load-bearing statement in the story. Removing it would leave "two
players join while the facilitator starts" as a genuine race.

**Rows affected is the entire protocol.** One means the caller's assumption held; zero
means the world moved. Zero is not a statement failure — it is an answer, disambiguated
into 404 or 409 by one extra read that is only ever paid on a path already failing.

**Two guarantees come from the schema, not from Java.** `uq_game_session_join_code` and
`uq_player_session_seat` make duplicate codes and double-booked seats impossible. The
adapter writes optimistically and interprets the violation. A pre-insert `SELECT` would
narrow the race window without closing it.

**The limiter is checked first, and it is one of two controls.** Eight Crockford base32
characters is exactly forty bits, which bounds how much of the keyspace a blind search
must cover -- the limiter bounds how fast any one address covers it, and EOP-24 widened
the code from six characters precisely because thirty bits left the limiter carrying
that on its own. The 404 for
an unusable code is byte-for-byte identical whether the code never existed, was
mistyped, or belonged to an abandoned session, so the endpoint is not an oracle. But the
limiter's counters are in process memory, so **immediately after a restart every
attacker starts with a full allowance** (ADR-019).

**And the key it counts against is resolved before it, deliberately.** A throttle whose
bucket the caller can choose is not a throttle. Until EOP-26 `X-Forwarded-For` was
believed from anyone, so rotating it once per request gave a fresh empty window every
time; the header is now read only from a peer on the `eop.web.trusted-proxies`
allow-list, which is empty unless a deployment says otherwise (ADR-021).

**The creation limiter uses the same address resolution and the same reserve-before-work
principle.** `InMemorySessionCreationLimiter` (EOP-19, ADR-033) reserves a slot
*before* the database write and refunds it if the use case fails — so a refused request
never commits a row. The body-size cap (16 KB) is enforced by Caddy before the request
reaches the application; there is no application-side cap (the `max-swallow-size`
property does not cap request bodies and was deliberately not added).

---

## 4. Dealing the hands — a second write that claims the deal

The first of the three sequences EOP-14 Slice C2 owed this document. It begins where
sequence 3 ends: the session is already `IN_PROGRESS`, because
`StartSessionService` committed that transition in a separate request.
[ADR-025](../adr/ADR-025-dealing-is-its-own-use-case.md) argues that seam.

```mermaid
sequenceDiagram
    autonumber
    participant CL as TrickController — POST /api/v1/sessions/{sessionId}/deal
    participant DH as DealHandsService
    participant RP as ResolvePlayerService
    participant HD as HandDealer
    participant CRA as CardRepositoryAdapter
    participant SH as SecureRandomDeckShuffler
    participant IDG as IdentifierGenerator
    participant TPRA as TrickPlayRepositoryAdapter
    participant DB as PostgreSQL
    participant EV as SessionEventPublisher

    Note over CL,EV: Precondition: the session is already IN_PROGRESS.<br/>Sequence 3 put it there, in an earlier request and<br/>an earlier transaction (ADR-025).

    CL->>DH: execute(sessionId, playerToken)

    DH->>RP: execute(sessionId, playerToken)
    Note over DH,RP: The first statement of the method,<br/>DealHandsService.java:93. Nothing is<br/>read, shuffled or written before it.
    RP-->>DH: ResolvedPlayer, or 404 SessionNotFoundException<br/>or 403 PlayerNotRecognisedException

    alt not the facilitator
        DH-->>CL: 403 NotFacilitatorException
    else may deal
        DH->>HD: deal(session)
        Note over DH,HD: EOP-190 moved the deal itself into HandDealer,<br/>which NewGameService calls too. DealHandsService<br/>authorises and delegates and does nothing else.<br/>HandDealer authorises nobody, so it is never<br/>reached from an adapter (ADR-024).
        alt fewer than three seated
            HD-->>DH: TooFewPlayersException
            DH-->>CL: 409 TooFewPlayersException
        else enough seated
            HD->>CRA: findWholeDeck()
            CRA->>DB: SELECT from card ORDER BY suit, rank
            DB-->>CRA: every row, canonical order
            CRA-->>HD: the whole deck as domain Cards
            HD->>SH: shuffle(deck)
            Note over SH: SecureRandom, no seed anywhere.<br/>Copies the list — the input is never mutated.
            SH-->>HD: a permuted copy
            loop each seated player, in seat order
                HD->>IDG: nextIdentifier()
            end
            HD->>HD: Hands.deal(shuffled, seats)
            Note over HD: Pure domain. The remainder rule and the<br/>opening leader come from the entity,<br/>not from this collaborator (ADR-023).

            HD->>TPRA: recordDeal(sessionId, hands, openingLeaderSeat, now)
            TPRA->>DB: UPDATE game_session SET current_leader_seat = ?<br/>WHERE id = ? AND status = IN_PROGRESS<br/>AND current_leader_seat IS NULL
            Note over TPRA,DB: The compare-and-set. "current_leader_seat IS NULL"<br/>*is* "not yet dealt", and this UPDATE takes the<br/>session row lock before any hand row — which is<br/>what serialises two simultaneous deals (ADR-020).
            alt 0 rows affected
                TPRA->>DB: one disambiguating read
                TPRA-->>HD: SessionNotFoundException, SessionNotJoinableException<br/>or HandAlreadyDealtException
                HD-->>DH: the same exception, unwrapped
                DH-->>CL: 404 or 409
            else 1 row affected
                loop each dealt seat
                    TPRA->>DB: INSERT hand, then one INSERT per card held
                end
                TPRA-->>HD: void
                HD->>EV: publish(HAND_DEALT, sessionId, now)
                Note over HD,EV: EOP-14 Slice E, HandDealer.java:143.<br/>After the write returns, so the broadcast can only<br/>describe a durable deal. Carries no card and no seat:<br/>a subscriber learns *that* hands exist and re-reads<br/>sequence 1 or GET /hand to learn what it holds (ADR-027).
                HD-->>DH: nothing — the deal returns void
                DH-->>CL: nothing — the deal returns void
            end
        end
    end
```

### What this sequence settles, and the window it leaves

**The deal is a second write, and nothing in the diagram hides that.** Between the
`UPDATE` in sequence 3 that sets `status = IN_PROGRESS` and the `UPDATE` here that claims
`current_leader_seat`, there is a state the product has never had before: **started but
undealt**. It is observable — every trick-play read answers `HandNotDealtException` with a
409 — and it is recoverable, because the `current_leader_seat IS NULL` predicate makes a
repeated deal idempotent rather than a double deal. ADR-025 records both, and now also
records that the seam is a *choice* with a stated cost rather than a physical necessity.

**No pre-check guards a state the conditional write already arbitrates.** There is no
"is it started?" read and no "is it already dealt?" read before `recordDeal`. Adding
either would introduce a check-then-act window that the single conditional `UPDATE` does
not have, and would make the refusal depend on a read taken at a different instant from
the write it justifies (ADR-020).

**The shuffle is drawn as a participant because it is a security control.** Deck
composition is published reference data, so a predictable permutation is a predictable
hand. `SecureRandomDeckShuffler` takes no seed in any constructor, and `HandDealer` holds
the `DeckShuffler` port rather than a `java.util.Random`, so no caller can weaken the
choice by passing a seeded generator.

**The whole deck is read in one call, deliberately not a page.** `findWholeDeck()` returns
every card in canonical order (suit, then ascending rank); randomising is the use case's
job. A paginated deal would be one forgotten loop away from a truncated deck, and a
canonical order means a test can pin a deal by pinning the shuffler.

**The deal is broadcast, after it is durable.** `HandDealer` takes a
`SessionEventPublisher` and publishes `HAND_DEALT` at `HandDealer.java:143` (anchor:
`HAND_DEALT`), on the line after `recordDeal` returns. EOP-190 moved both the write and the
announcement out of `DealHandsService` and into that collaborator, so `NewGameService` deals
by calling it rather than by holding a second copy of these six lines — the ordering below is
therefore now asserted once and relied on twice. The ordering is the decision: a broadcast that preceded
the write could announce a deal that then rolled back, and a publisher that threw could
fail a request whose write had already succeeded. What the event does *not* carry is any
part of the deal — no seat, no card, no count — so a subscriber still re-reads to learn
what it holds, which is what keeps a per-player hand off a fan-out transport
([ADR-027](../adr/ADR-027-singleton-subresource-naming.md)). Slice D minted the name in the
contract; Slice E published it, closing the first half of decision 8 of
[ADR-025](../adr/ADR-025-dealing-is-its-own-use-case.md), which is amended in place to say
so.

---

## 5. Playing a card — build the play, then open the trick

The ordering in the "build, then write" block is the whole point of this sequence, and it
is what commit `fcb6fd5` fixed: the candidate `TrickPlay` is constructed **before**
`openTrick` commits a trick row, so a play that the domain will refuse for a bad note or
a bad component name cannot leave an open trick behind it.

```mermaid
sequenceDiagram
    autonumber
    participant CL as TrickController — POST /api/v1/sessions/{sessionId}/plays
    participant PC as PlayCardService
    participant RP as ResolvePlayerService
    participant CRA as CardRepositoryAdapter
    participant TJ as TrickJournal
    participant TPRA as TrickPlayRepositoryAdapter
    participant DB as PostgreSQL
    participant EV as SessionEventPublisher

    CL->>PC: execute(PlayCardCommand)
    Note over CL,PC: The command carries sessionId, token, cardId,<br/>threatLinked, components and notes.<br/>No seat, no playerId, no suit, no rank.

    PC->>RP: execute(sessionId, playerToken)
    RP-->>PC: ResolvedPlayer, or 404 / 403
    Note over PC: The acting seat and player id are taken from the<br/>resolved player and from nowhere else<br/>(PlayCardService.java:154-167).

    PC->>TPRA: findBySessionId(sessionId)
    TPRA->>DB: SELECT hand and hand_card for the session
    TPRA-->>PC: Hands, or empty which is 409 HandNotDealtException
    Note over PC: A resolved player holding no seat in those hands<br/>is 404 PlayerNotInSessionException — the status<br/>does not confirm the session exists<br/>(PlayCardService.java:162-175).
    PC->>PC: hands.allEmpty() → 409 HandCompleteException
    Note over PC: EOP-14 Slice E, PlayCardService.java:165-177.<br/>Asked before the card is even looked up, so a play<br/>into a played-out hand is told the hand is over<br/>rather than that its card is not in it.

    PC->>CRA: findById(cardId)
    CRA->>DB: SELECT from card WHERE id = ?
    CRA-->>PC: Card, or empty which is 404 CardNotFoundException
    Note over PC,CRA: The card is looked up against two independent<br/>authorities: the deck supplies suit and rank,<br/>and the hand must actually hold it.

    rect rgb(245, 245, 238)
    Note over PC,TPRA: Pre-flight — two guards, nothing written yet
    PC->>PC: hand.resolve(card) → 422 CardNotInHandException
    PC->>TPRA: findCurrentLeaderSeat(sessionId)
    TPRA-->>PC: the seat to lead, or empty which is 409 HandNotDealtException
    PC->>TJ: currentTrick(sessionId)
    TJ->>TPRA: findCurrentTrick(sessionId)
    TPRA-->>TJ: the latest trick, or empty
    TJ-->>PC: the latest trick, or empty
    alt opening a new trick
        PC->>PC: actingSeat is not leaderSeat → 409 OutOfTurnException
    end
    end

    rect rgb(240, 247, 240)
    Note over PC,DB: Build, then write
    PC->>PC: build the candidate TrickPlay
    Note over PC: Built BEFORE any write (commit fcb6fd5). An over-long<br/>note or an unnamed component now fails here,<br/>with no trick row left behind.
    alt opening a new trick
        PC->>TJ: openTrick(sessionId, Trick.open(id, sequence + 1, leaderSeat), leaderSeat, now)
        TJ->>TPRA: openTrick(sessionId, trick, leaderSeat, now)
        TPRA->>DB: UPDATE game_session WHERE current_leader_seat = ?<br/>AND status = IN_PROGRESS
        TPRA->>DB: INSERT trick
        Note over TJ: Opening a trick announces nothing. The play that<br/>opens it announces immediately below, so a client<br/>is never told about a trick with no play in it.
    end
    PC->>PC: trick.acceptPlay(actingSeat, candidate, hands)
    Note over PC: Follow suit, one play per seat, one play per card.<br/>On an opening play none of the three can fire.
    PC->>TJ: appendPlay(sessionId, trickId, leaderSeat, accepted, now)
    TJ->>TPRA: appendPlay(sessionId, trickId, leaderSeat, accepted)
    TPRA->>DB: UPDATE game_session — the same compare-and-set
    TPRA->>DB: DELETE from hand_card — 0 rows is 422 CardNotInHandException
    TPRA->>DB: INSERT trick_play, then one row per named component
    TJ->>EV: publish(CARD_PLAYED, sessionId, now)
    Note over TJ,EV: EOP-14 Slice E, since EOP-190 at TrickJournal.java:134.<br/>Appended and announced in the one call, after the write returns,<br/>and carrying no card, no seat and no trick — a subscriber is told<br/>a play happened and re-reads GET /tricks/current to learn what it was.
    end

    PC-->>CL: the updated Trick
    Note over PC,CL: A complete trick is NOT resolved here.<br/>Resolution is sequence 6, a separate request.
```

### Why the pre-flight duplicates the domain, and why that is not belt-and-braces

**The two pre-flight guards buy ordering, not safety.** `Trick.acceptPlay` and `Hand`
already refuse a card the seat does not hold and a play out of turn; checking first
changes nothing about what is *permitted*. What it changes is what is *left behind*.
Without the turn-order guard, an out-of-turn opening play would commit a trick row and
only then be refused, leaving an open trick nobody may play into. Without
`hand.resolve(card)`, a play of a card the seat does not hold would surface as an
`IllegalStateException` and a 500 instead of a 422. Slice E added a third guard above the
rect, `hands.allEmpty()` at `PlayCardService.java:165-177`, for the same reason one step
earlier: a play into a hand that has been played out now answers 409 `HandCompleteException`
rather than reporting that some particular card is not held, which was the only answer
available before the end of a hand had a name.

**The leader seat always comes from the read, never from the caller.** It is the
compare-and-set witness of ADR-020: `openTrick`, `appendPlay` and `recordResolution` all
compare `current_leader_seat` against the value this use case read, so a stale request
loses the race rather than overwriting it. A caller-supplied witness would let a client
choose which race it wins.

**The window that remains, and what closes it.** `openTrick` still commits before
`acceptPlay` runs, so a refusal from `acceptPlay` on an *opening* play would still orphan
a trick row. That path is argued closed by exhaustion over `Trick.acceptPlay`'s current
refusals — on an opening play there is no led suit to follow and the trick is empty, so the
duplication invariants in `Trick`'s constructor have nothing to duplicate; `NotYourSeatException`
is inexpressible because the candidate is built with the acting seat itself; `PlayerMismatchException`
cannot fire because the seat-to-player map is frozen once the session leaves `LOBBY`; and the
remainder are pre-flighted above. The
argument is only as good as that list: **any refusal added to `acceptPlay` or to `Trick`'s
constructor reopens
the window**, which is why ADR-025 records the reasoning rather than only the conclusion — as a
table, row by row against the method, in its decision 9.

**The play is broadcast, after the writes.** Since EOP-190 the `SessionEventPublisher`
is held by `TrickJournal` rather than by `PlayCardService`, and the journal appends and
announces in the one call: `CARD_PLAYED` is published at
`TrickJournal.java:134` (anchor: `CARD_PLAYED`), once `appendPlay` has returned. The event names the session and nothing else: not the card,
not the seat, not the trick. That is what makes it safe on a fan-out transport where
every subscriber of a session receives every event — the other players learn *that* the
table moved and re-read `GET /tricks/current` to learn whose turn it now is, and a
per-player hand never crosses the stream ([ADR-027](../adr/ADR-027-singleton-subresource-naming.md)).
This closes the second half of decision 8 of
[ADR-025](../adr/ADR-025-dealing-is-its-own-use-case.md), amended in place to say so.
Delivery is not ordered and not guaranteed, so a client that missed an event is in
exactly the position it was in before Slice E: it re-reads.

---

## 6. Resolving a trick — mechanical, and open to any member

```mermaid
sequenceDiagram
    autonumber
    participant CL as TrickController — POST /api/v1/sessions/{sessionId}/tricks/current/resolve
    participant RT as ResolveTrickService
    participant RP as ResolvePlayerService
    participant TPRA as TrickPlayRepositoryAdapter
    participant DB as PostgreSQL
    participant EV as SessionEventPublisher

    CL->>RT: execute(sessionId, playerToken)

    RT->>RP: execute(sessionId, playerToken)
    RP-->>RT: ResolvedPlayer, or 404 / 403
    Note over RT,RP: Membership only — the resolved player is not<br/>otherwise used (ResolveTrickService.java:127).<br/>Any member may resolve, because resolution is<br/>arithmetic and gating it on the facilitator<br/>would stall a table whose facilitator dropped.

    RT->>TPRA: findBySessionId(sessionId)
    TPRA-->>RT: Hands, or empty which is 409 HandNotDealtException
    RT->>TPRA: findCurrentTrick(sessionId)
    TPRA-->>RT: the latest trick, or empty which is 409 NoTrickToResolveException

    alt the trick already has a winner
        RT-->>CL: 409 TrickAlreadyResolvedException
    else a seat holding cards has not played
        RT-->>CL: 409 TrickNotCompleteException — names the seat still to play
    else complete
        RT->>RT: trick.resolved() — highest card of the led suit takes it
        RT->>RT: the winning play must be one of this trick's plays,<br/>or 422 WinningPlayNotInTrickException (ResolveTrickService.java:146-157)
        RT->>RT: nextLeaderSeat = OptionalInt — the next seat still holding<br/>cards, or empty when the hand is played out
        Note over RT: EOP-14 Slice E, ResolveTrickService.java:154:<br/>final var nextLeaderSeat = resolved.nextLeaderSeat(seatsHoldingCards)<br/>The placeholder is gone. The port's next-leader parameter<br/>widened from int to OptionalInt (TrickRepository.java:163-164),<br/>so "nobody left to lead" is now a value the port can carry<br/>instead of a winning seat standing in for one.
        RT->>TPRA: recordResolution(sessionId, resolved, leaderSeat, nextLeaderSeat, now)
        TPRA->>DB: UPDATE game_session SET current_leader_seat = next, or NULL<br/>WHERE current_leader_seat = leaderSeat<br/>AND status = IN_PROGRESS
        Note over TPRA,DB: NULL is the end of the hand, recorded rather than<br/>released or scored at this point: the status transitions to<br/>COMPLETED in the next step when nextLeaderSeat is empty<br/>(EOP-15 Slice C, ADR-032). Changeset 005's CHECK<br/>already permitted NULL, so no migration was needed.
        TPRA->>DB: UPDATE trick SET winner_play_id = ?<br/>WHERE id = ? AND winner_play_id IS NULL
        Note over TPRA,DB: 0 rows on the second UPDATE is a replay, not a fault:<br/>when the leader also won, the first UPDATE is<br/>idempotent and lets a repeat through. Answered 409.
        RT->>EV: publish(TRICK_RESOLVED, sessionId, now)
        Note over RT,EV: EOP-14 Slice E, ResolveTrickService.java:140.<br/>After the write returns, and naming no winner and no<br/>seat — a subscriber re-reads GET /tricks/current, which<br/>is also where it learns that the hand is complete.
        RT-->>CL: the resolved Trick
    end
```

### What this sequence says about authority and about end of hand

**Two different authorisation answers, on purpose.** Dealing is facilitator-only;
resolving is open to any member. The difference is that dealing chooses something —
who holds what — while resolving computes something that is already determined by the
cards on the table. Requiring the facilitator to resolve would add a way for a table to
stop making progress without adding a way for it to be cheated.

**`TrickNotCompleteException` discloses a seat number, and that is deliberate.** The
refusal names the seat still to play so a client can say whose turn it is without a
second request. A seat ordinal is not a secret — the state DTO already lists every seat
to every member — and the refusal is only reachable by a caller already resolved as a
member of that session (ADR-005 for the problem-detail shape).

**End of hand is recognised here, and the placeholder is gone.** When no seat holds a
card, `Trick.nextLeaderSeat(seatsHoldingCards)` returns an empty `OptionalInt` and
`ResolveTrickService.java:154` passes it straight through, because
`TrickRepository.recordResolution`'s next-leader parameter widened from `int` to
`OptionalInt` (`TrickRepository.java:163-164`). `advanceLeaderSeat` then writes
`current_leader_seat = NULL`. No migration was needed: changeset `005`'s CHECK already
permitted null. Before Slice E the same situation wrote the winning seat as a stand-in,
which was harmless only because nobody could play — it was a placeholder rather than a
meaning, and [ADR-025](../adr/ADR-025-dealing-is-its-own-use-case.md)'s consequences
pinned it to that one line, now amended in place to record its deletion.

**What that NULL costs, and what it does not buy.** The column now carries two meanings —
"not yet dealt" and "played out, nobody leads" — so it is no longer the whole deal-once
gate on its own; a second deal into a played-out session is refused one statement later by
`uq_hand_session_seat`, as the same 409, and
[ADR-020](../adr/ADR-020-session-concurrency-control.md) is amended with both that
narrowing and the sixth answer `sessionMoved` gained to tell the two null states apart.
What the NULL does *not* do on its own is finish the game: the session status stays
`IN_PROGRESS` until `ResolveTrickService` calls `SessionRepository.recordCompleted` in the
same execution, after `recordResolution` returns. A client learns the hand is over from
`handComplete` on `GET .../tricks/current` — `GetTrickStateService.java:114`, computed from
`Hands.allEmpty()` — or from a 409 `HandCompleteException` if it tries to play anyway. The
`COMPLETED` transition and the `GAME_COMPLETED` event are sequence 8 below;
[ADR-032](../adr/ADR-032-end-of-game-transitions.md) records the design choices and the
two-transaction race that the auto-complete branch tolerates.

---

## 7. Reading the score — derived on every read, gated by nothing but membership

```mermaid
sequenceDiagram
    autonumber
    participant CL as ScoreController — GET /api/v1/sessions/{sessionId}/score
    participant GS as GetScoreService
    participant RP as ResolvePlayerService
    participant TPRA as TrickPlayRepositoryAdapter
    participant DB as PostgreSQL
    participant SS as ScoreSheet — entity

    CL->>GS: execute(sessionId, playerToken)

    GS->>RP: execute(sessionId, playerToken)
    RP-->>GS: ResolvedPlayer, or 404 SessionNotFoundException / 403 PlayerNotRecognisedException
    Note over GS,RP: This is the whole gate, and it is a complete one:<br/>a token is only ever matched against the players of<br/>the session it was presented for, so a stranger who<br/>guesses a session identifier is refused here without<br/>any further seat check (ADR-015, ADR-024). The resolved<br/>session also carries its players, which is why this use<br/>case reads no session repository of its own.

    GS->>TPRA: findTricks(sessionId)
    TPRA->>DB: SELECT * FROM trick WHERE game_session_id = ? ORDER BY sequence ASC
    TPRA->>DB: SELECT * FROM trick_play WHERE trick_id IN (…)
    TPRA->>DB: SELECT * FROM card WHERE id IN (…)
    TPRA->>DB: SELECT * FROM trick_play_component WHERE trick_play_id IN (…) ORDER BY trick_play_id, ordinal
    Note over TPRA,DB: Four reads for the whole session — the tricks, then their<br/>plays, cards and components one batch each. Mapping the<br/>single-trick assembler over every row would have cost three<br/>reads per trick: 1 + 3 × 23 = seventy in a twenty-three-trick<br/>hand, which is what a three-player 68-card deal runs to.<br/>No predicate on winner_play_id — whether a trick is finished<br/>is a question the trick answers about itself, and a second<br/>authority in SQL would disagree the moment that changed.
    TPRA-->>GS: List<Trick>, the whole history, unresolved tricks included

    GS->>SS: ScoreSheet.of(session.players(), tricks)

    alt the stored game contradicts itself
        SS-->>GS: ScoreNotDerivableException, carrying a typed Reason
        GS-->>CL: 500 — the reason and the identifiers are logged,<br/>the body says only that the request could not be completed
    else the game is consistent
        SS->>SS: one point for a linked threat, one for taking the trick<br/>an unresolved trick contributes threat points but no trick point
        SS->>SS: competition ranking — 7, 5, 5, 2 hold positions 1, 2, 2, 4<br/>a shared first place is shown as a tie, never broken
        SS-->>GS: ScoreSheet — rows and standings
        GS-->>CL: the sheet, rendered as ScoreSheetDto
    end
```

### What this sequence settles, and what it deliberately does not

**Nothing is accumulated, so nothing can drift.** The score is a pure function of the plays
and each trick's winner, recomputed on every read. There is no counter to increment on the
play path, so there is no second transaction to fail independently and no total that can be
wrong with no read able to detect it. The cost is bounded by the rules of the game rather than
by a limit in code — a 68-card deck is at most 68 rows and at most six standings — which is
why ADR-030 accepts the recomputation and declines to cache it. Since EOP-88 a limit in code does apply to this route,
but it bounds the *rate* of reads rather than the cost of any one of them:
`ReadRateLimitInterceptor` counts this `GET` along with every other read under
`/api/v1` against a sixty-second per-address window and answers 429 once it is
full (ADR-051). What a single read costs is still bounded only by the rules of
the game.

**There is no 409 on this path, and its absence is the interesting part.** Every other read
in this document can be asked before the state it reports exists: `GET /tricks/current` answers
`HandNotDealtException` before the deal, because there is no state of play to report. A score
before the deal is different — everybody on nothing is a *true* answer, not a missing one. That
is why this use case reaches neither `HandRepository` nor the session's status: it has nothing
to refuse. `GetScoreUseCaseTest` pins it, and so does the HTTP test that reads a score from a
started table before a card is played.

**A contradiction is a server fault, not the caller's mistake.** The eight refusals inside
`ScoreSheet` and `ScoredPlay` all mean the stored game disagrees with itself — a play attributed
to a seat nobody occupies, two tricks claiming one sequence number. None is actionable by any
caller, so all eight became one `ScoreNotDerivableException` mapped to 500 with a body
byte-identical to the no-tampering-card fault. Before slice B they were `IllegalArgumentException`,
which the global handler answers **400 with the message echoed** — so a data contradiction would
have been reported as a bad request and would have echoed a player identifier back to whoever
guessed the session. The reason survives in the log, where it is useful; nothing survives in the
response, where it would only be a hint.

**This sequence does not end the game.** Reading a score changes no state and moves no session
out of `IN_PROGRESS` or `COMPLETED`. Sequence 6 records the end of a *hand* by writing a NULL
leader seat; this one reports what that hand was worth. Sequence 8 below shows how the session
reaches `COMPLETED` — automatically when the last trick resolves, or early via the facilitator's
`POST /end`.

---

## 8. Ending the game — automatic on last trick, or early by the facilitator

Two paths lead to `COMPLETED`. The automatic path fires inside `ResolveTrickService` when
`nextLeaderSeat` is empty (sequence 6 above). The facilitator path is a separate HTTP call.
Both are gated on `eop.features.trick-play`.

```mermaid
sequenceDiagram
    autonumber
    participant CL as EndSessionController — POST /api/v1/sessions/{sessionId}/end
    participant ES as EndSessionService
    participant RP as ResolvePlayerService
    participant SRA as SessionRepositoryAdapter
    participant DB as PostgreSQL
    participant EV as SseSessionEventPublisher

    CL->>ES: execute(sessionId, playerToken)

    ES->>RP: execute(sessionId, playerToken)
    RP-->>ES: ResolvedPlayer, or 404 SessionNotFoundException / 403 PlayerNotRecognisedException
    Note over ES,RP: Same gate as every other write: credential resolved first,<br/>before any mutable state is touched. A stranger gets 403<br/>before learning anything about the session.

    ES->>ES: session.complete(requestedBy, now)
    Note over ES: GameSession.complete() checks in order:<br/>1. player recognised (PlayerNotRecognisedException)<br/>2. player is facilitator (NotFacilitatorException — 403)<br/>3. status == IN_PROGRESS (SessionNotInProgressException — 409)<br/>Authz precedes the state check: a participant probing /end<br/>gets 403 regardless of session state and learns nothing about it.

    ES->>SRA: recordCompleted(sessionId, now)
    SRA->>DB: UPDATE game_session SET status = COMPLETED, updated_at = now,<br/>version = version + 1<br/>WHERE id = ? AND status = IN_PROGRESS
    Note over SRA,DB: Compare-and-swap: 0 rows updated means the session moved.<br/>SRA reads the row to distinguish gone (404) from<br/>already-completed (409). A double-/end race loses the<br/>second update cleanly.

    ES->>EV: publish(GAME_COMPLETED, sessionId, now)
    Note over ES,EV: After the write returns. Clients streaming<br/>GET /sessions/{sessionId}/events receive game-completed<br/>and re-read GET /sessions/{sessionId} to see COMPLETED.

    ES-->>CL: void
    CL-->>CL: 204 No Content
```

### Automatic path (inside sequence 6)

When `nextLeaderSeat` is empty after `recordResolution` commits, `ResolveTrickService`
calls `recordCompleted` and publishes `GAME_COMPLETED` in the same execution — but in a
**second, separate transaction**. If a facilitator's `POST /end` wins the `advanceStatus`
race in the window between the two commits, `recordCompleted` finds zero rows and would
throw `SessionNotInProgressException`. The session is already `COMPLETED` — the desired
outcome — so the auto-complete branch catches that exception and treats it as success.
The trick resolution was already durably committed and `TRICK_RESOLVED` already published;
the caller receives the resolved trick normally. ADR-032 records this choice.

### What this sequence settles

**Authz precedes state.** `GameSession.complete()` checks facilitator status before
checking `IN_PROGRESS`, so a participant probing `/end` gets 403 regardless of session
state and learns nothing about it.

**Score is still readable after early end.** `GET /score` is status-agnostic; it returns
the score of the plays made, which may be a partial game. The facilitator ends early
because the group has reached a conclusion, not because the score is meaningless.

**`GAME_COMPLETED` carries no payload.** Consistent with ADR-014: the event names the
session and the time; a subscriber re-reads `GET /sessions/{sessionId}` to see `COMPLETED`
and `GET /sessions/{sessionId}/score` for the final standings.

### What the client does with `COMPLETED` (EOP-105)

The sequence above stops at the doorbell, which is where the server's responsibility
ends but not where the flow does. Two different clients re-read and take two different
actions, and which one a given player gets depends on the screen they were on when the
facilitator ended the session:

| Player was on | Re-read happens in | Destination |
|---|---|---|
| `GameScreen` (playing) | the trick/`game-completed` handling | `game-over` — leaderboard and final standings |
| `LobbyScreen` (never joined the play) | `refreshSession`, on the same doorbell | `home`, via `onSessionEnd()`, which clears the stored token |

That divergence is deliberate. A player still in the lobby has no plays and therefore no
row worth showing on a leaderboard, so returning them to the front door is the honest
outcome rather than a score screen full of zeroes. The governing rule, stated in full in
"The custody and recovery path" above and as ADR-009 Decision 4: `view.screen` is the
client's own state machine and the only thing deciding which screen renders, and
`session.status` is read to *gate* what a mounted screen shows and to *leave* a screen
the server has moved past — never as a lookup mapping a server state onto a screen name.

Before EOP-105 the lobby had no branch at all here, because `COMPLETED` was missing from
the client's `SessionStatus` union — so this doorbell left that player on a lobby screen
with neither a Start button nor a "game has started" notice, indefinitely.

---

## Sequence 9 — Expired-session sweep (EOP-22)

**Precondition:** `eop.features.session-lifecycle=true` (flag off → `ExpiredSessionSweepScheduler`
bean absent, sweep never fires). One or more sessions have `expires_at < now`.

**Actors:** `ExpiredSessionSweepScheduler` (Spring `@Scheduled` adapter), `SweepExpiredSessionsService`,
`SessionRepositoryAdapter`, PostgreSQL.

```mermaid
sequenceDiagram
    participant SCHED as ExpiredSessionSweepScheduler
    participant UC as SweepExpiredSessionsService
    participant SRA as SessionRepositoryAdapter
    participant DB as PostgreSQL

    Note over SCHED: @Scheduled fires every eop.sweep.interval-ms (default 1 h)<br/>initial delay eop.sweep.initial-delay-ms (default 5 min)<br/>bean absent when eop.features.session-lifecycle is off

    SCHED->>UC: execute()
    Note over UC: Instant.now(clock) — uses injected Clock,<br/>not a direct Instant.now() call

    UC->>SRA: findExpiredSessionIds(now)
    SRA->>DB: SELECT s.id FROM game_session s<br/>WHERE s.expires_at < :before<br/>AND s.status <> 'ABANDONED'
    DB-->>SRA: [id1, id2, ...]
    SRA-->>UC: List<UUID>

    loop for each expired session id
        UC->>SRA: abandonAndDelete(id)
        Note over SRA: Single @Transactional — mark then delete<br/>in one commit — no ABANDONED row survives
        SRA->>DB: UPDATE game_session SET status = 'ABANDONED',<br/>version = version + 1 WHERE id = :id
        SRA->>DB: DELETE FROM game_session WHERE id = :id<br/>(cascades to player, hand, trick, trick_play, trick_play_component)
        DB-->>SRA: void
        SRA-->>UC: void
        Note over UC: Per-session try/catch: a failure on one session<br/>is logged at WARN and the sweep continues.<br/>The failed session remains eligible next cycle.
    end

    UC-->>SCHED: void
    Note over SCHED: Next fire scheduled after interval-ms from completion
```

### What this sequence settles

**The sweep is a background driver, not a domain rule.** `SweepExpiredSessionsService` owns the
policy (which sessions to delete, how to handle per-session failures); `ExpiredSessionSweepScheduler`
owns only the trigger. The use case is testable without Spring; the scheduler is a thin adapter.

**Expiry is enforced at the `ResolvePlayerService` boundary.** `ResolvePlayerService` rejects any
request whose session has `expiresAt < now(clock)` with 403 `"Session expired"`. The sweep then
removes the row so it does not accumulate indefinitely. The two checks are independent: the guard
fires on every authenticated request; the sweep fires on a schedule.

**Accepted limitation — the join path is not guarded.** `JoinSessionService` does not check
`session.expiresAt()`. A player can join an expired `LOBBY` session via join code and receive a
token; that token will be rejected by `ResolvePlayerService` on the next call. The gap is
documented in ADR-036 and deferred to a future story.

**The ABANDONED write is not observable.** `markAbandoned` and `deleteById` share a transaction, so
no row ever commits with `status = ABANDONED`. The UPDATE exists to bump the version column (optimistic
locking) and to make the intent legible in the code; the net committed effect is the delete. The
`LOG.info` line in `SweepExpiredSessionsService` is the only audit record that a session was swept.

**Single-instance assumption.** Two replicas sweeping the same ID set concurrently will both attempt
the same deletes; the second attempt is silently ignored — `deleteById` on a missing row is a no-op
(`findById(id).ifPresent(this::delete)`), so no exception is thrown and no warning is logged. The
application is deployed as a single container (ADR-012, ADR-016), so this is not a current concern,
but it is an unstated precondition for any future multi-instance deployment. ADR-036 records the
assumption and defers distributed coordination to a future story.

**Clock authority.** `expires_at` is set by the domain at `openLobby()` time using the injected
`Clock` (`GameSession.java:106`). The database column carries a `defaultValueComputed` expression
only as a fallback for rows inserted outside the domain (e.g. test fixtures via raw JDBC). The
sweep compares against the same application JVM `Clock`. Under clock skew between the JVM and the
database, the DB default may produce a slightly different expiry than the domain would — but the
domain path is authoritative for all production inserts. ADR-036 §2 records this as a known
limitation.

## Sequence 10 — Browser play-a-card round trip (EOP-60)

The `GameScreen` component (behind `VITE_GAME_SCREEN_ENABLED`) uses the SSE doorbell
pattern from ADR-014: the event carries no payload; the client re-fetches authoritative
state on every notification. This sequence shows one complete card-play cycle from the
player's gesture to the re-rendered table.

**Precondition:** session is `IN_PROGRESS`, hands have been dealt, it is the player's
turn (`seatToPlay` matches the player's seat).

```mermaid
sequenceDiagram
    autonumber
    participant P as Player (browser)
    participant G as GameScreen
    participant API as REST API (Caddy → Spring)
    participant SSE as SseEmitter (all subscribers)
    participant DB as PostgreSQL

    Note over P,G: Drag-and-drop (pointer events) or<br/>click-to-select + "Play selected card" button<br/>(keyboard / screen-reader path — ADR-038)

    P->>G: pointerup over drop zone, or Enter/Space + button click
    G->>API: POST /api/v1/sessions/{sessionId}/plays<br/>X-EoP-Player-Token: {token}<br/>body: { cardId }
    Note over API: ResolvePlayerService derives acting seat<br/>from token — no seat in the request body.<br/>PlayCardService checks turn order, removes<br/>card from hand, appends play.
    API->>DB: DELETE hand_card, INSERT trick_play (one transaction)
    DB-->>API: ok
    API-->>G: 200 TrickDto (own response)
    API->>SSE: broadcast CARD_PLAYED (no payload — ADR-014)

    Note over G: Re-fetch on own response AND on doorbell.<br/>Both paths call refreshGameState().

    SSE-->>G: doorbell event (other players' GameScreen instances)

    G->>API: GET /api/v1/sessions/{sessionId}/hand<br/>GET /api/v1/sessions/{sessionId}/tricks/current<br/>GET /api/v1/sessions/{sessionId}<br/>(Promise.all — three parallel reads)
    API->>DB: SELECT hand_card, trick, trick_play, game_session
    DB-->>API: authoritative state
    API-->>G: HandDto, TrickStateDto, SessionStateDto

    alt trick is complete (all seats played)
        G-->>P: winner banner (role="alert"), "Start next trick" button for winner
        Note over G: Auto-dismiss after 5 s via setTimeout.<br/>Winner calls POST .../tricks/current/resolve<br/>to advance to the next trick.
    else trick still open
        G-->>P: re-render table — updated hand, played cards in trick zone,<br/>aria-live region announces whose turn it is
    end
```

### What this sequence settles

**The client never trusts its own play.** The `200 TrickDto` from the POST is used only
to trigger a re-fetch; the re-fetch is what drives the render. This means the table
always reflects the database, not an optimistic local state.

**The doorbell is the same mechanism as the lobby.** `subscribeToSession` in `api.ts`
uses `fetch` + `AbortController` (not `EventSource`) so the player token can travel as a
header rather than a query parameter — the same reasoning as ADR-015 and ADR-019.

**Turn enforcement is server-side.** The client disables cards and hides the play button
when `seatToPlay` does not match the player's seat, but this is a UX convenience only.
`PlayCardService` derives the acting seat from the resolved token and throws
`OutOfTurnException` (409) if it does not match the current leader seat. A caller cannot
name a seat it does not hold — `PlayCardRequest` carries no seat field.

**The winner banner fires on `trickState.complete`, not on a client-side count.** The
`complete` flag on `TrickStateDto` is computed by `GetTrickStateService` from
`Hands.allEmpty()` and the trick's play count; the client reads it, never derives it.

---

## Sequence 11 — Game-over leaderboard (EOP-65)

`GET /api/v1/sessions/{sessionId}/leaderboard` — available only when `eop.features.game-over=true`.

```mermaid
sequenceDiagram
    participant Browser
    participant ReadRateLimitInterceptor
    participant GameOverController
    participant GetLeaderboardService
    participant ResolvePlayerService
    participant SessionRepository
    participant GameResultRepository
    participant TrickRepository

    Browser->>ReadRateLimitInterceptor: GET /leaderboard (X-Player-Token)
    ReadRateLimitInterceptor->>ReadRateLimitInterceptor: admit(ClientAddressResolver.of(request))
    Note over ReadRateLimitInterceptor: Counted in preHandle, so a refusal costs no query at all.<br/>Keyed on the resolved client address, never a request header and never the player token (EOP-88, ADR-051).
    ReadRateLimitInterceptor-->>Browser: 429 problem+json with Retry-After (window full)
    ReadRateLimitInterceptor->>GameOverController: preHandle true, request proceeds
    GameOverController->>GetLeaderboardService: execute(sessionId, playerToken)
    GetLeaderboardService->>ResolvePlayerService: execute(sessionId, playerToken)
    ResolvePlayerService->>SessionRepository: findById(sessionId)
    SessionRepository-->>ResolvePlayerService: session (else 404 Session not found)
    ResolvePlayerService-->>GetLeaderboardService: ResolvedPlayer
    GetLeaderboardService->>GetLeaderboardService: check status == COMPLETED (else 409)
    GetLeaderboardService->>GameResultRepository: findBySessionId(sessionId)
    GameResultRepository-->>GetLeaderboardService: GameResult (else 404 Game result not recorded)
    GetLeaderboardService->>TrickRepository: findTricks(sessionId)
    TrickRepository-->>GetLeaderboardService: List<Trick>
    GetLeaderboardService-->>GameOverController: LeaderboardResult(sessionStatus, scoreSheet)
    GameOverController-->>Browser: 200 LeaderboardDto (scores derived from live tricks, ADR-030)
```

**The read is refused before the handler is reached when the window is full** (EOP-88, ADR-051).
`ReadRateLimitInterceptor` counts every `GET` and `HEAD` under `/api/v1` against a sixty-second
window keyed on the resolved client address, so a refusal is served without touching the database.
That matters more on this route than on any other, because the handler behind it re-derives every
score from the whole trick history on each call. The interceptor throws `RateLimitedException` and
the single `@ControllerAdvice` renders the 429, the `application/problem+json` body and the
`Retry-After` header, so nothing about error rendering is duplicated at this layer. The event stream
is the one read excluded from the limiter — its cost is bounded by the subscriber cap instead
(ADR-034), which leaves SSE reconnection unlimited.

**Scores are always derived from the live trick history** (ADR-030). The `GameResult` row is used
only to confirm a result was recorded; `ScoreSheet.standings()` and
`ScoreSheet.capturedBySuitByPlayer()` compute the actual numbers. The row is read as an existence
gate and deliberately **not** returned — until EOP-87 `LeaderboardResult` carried it as a non-null
component that no consumer read.

The `sessionStatus` in the response is the status read off the resolved session and carried out
through `LeaderboardResult`, not a literal written by the adapter. Until EOP-87 `GameOverController`
wrote `"COMPLETED"` itself, which was correct only because `GetLeaderboardService` refuses every
other status one step earlier — a temporal coupling that would have started lying silently the
moment that guard was relaxed.

---

## Sequence 12 — New-game reset (EOP-65)

`POST /api/v1/sessions/{sessionId}/new-game` — facilitator only, COMPLETED sessions only.
Non-atomic four-step sequence (ADR-039).

```mermaid
sequenceDiagram
    participant Browser
    participant GameOverController
    participant NewGameService
    participant ResolvePlayerService
    participant TrickRepository
    participant HandRepository
    participant SessionRepository
    participant SessionEventPublisher

    Browser->>GameOverController: POST /new-game (X-Player-Token)
    GameOverController->>NewGameService: execute(sessionId, playerToken)
    NewGameService->>ResolvePlayerService: execute(sessionId, playerToken)
    ResolvePlayerService-->>NewGameService: ResolvedPlayer
    NewGameService->>NewGameService: check facilitator role (else 403)
    NewGameService->>NewGameService: check status == COMPLETED (else 409)
    Note over NewGameService: Non-atomic steps begin (ADR-039)
    NewGameService->>TrickRepository: clearTricksForNewGame(sessionId)
    NewGameService->>HandRepository: clearHandsForNewGame(sessionId)
    NewGameService->>SessionRepository: resetToInProgress(sessionId, now)
    Note over SessionRepository: CAS: WHERE status='COMPLETED'
    NewGameService->>HandRepository: recordDeal(sessionId, hands, leaderSeat, now)
    NewGameService->>SessionEventPublisher: publish(HAND_DEALT)
    GameOverController-->>Browser: 204 No Content
```

**Non-atomicity:** the four steps (clear tricks, clear hands, reset status, re-deal) are separate
port calls. A partial failure leaves the session in an inconsistent state, but the facilitator can
retry — the CAS on `resetToInProgress` prevents a double-reset. See ADR-039 for the accepted
trade-off.

---

## Related

- [`C4-Diagrams.md`](C4-Diagrams.md) — the containers and components these sequences move through
- [ADR-014](../adr/ADR-014-realtime-transport.md) — SSE, the mandatory heartbeat, and why reconnection re-reads
- [ADR-019](../adr/ADR-019-session-lifecycle-and-join-codes.md) — the five routes, header-only stream auth, and the join code
- [ADR-020](../adr/ADR-020-session-concurrency-control.md) — compare-and-set on `status`, the row lock, and the unique constraints
- [ADR-021](../adr/ADR-021-trusted-proxy-forwarded-for.md) — how `clientAddress` is decided, and why the header is ignored by default
- [ADR-015](../adr/ADR-015-player-identity.md) — the token, its digest, and the client half that is not built yet
- [ADR-005](../adr/ADR-005-error-handling-strategy.md) — where every refusal above becomes a problem detail
- [ADR-023](../adr/ADR-023-deal-remainder-and-turn-order.md) — the remainder rule, turn order, and what the schema deliberately does not enforce
- [ADR-024](../adr/ADR-024-trick-play-persistence-boundary.md) — the trick-play ports, and why authorisation is the use case's job
- [ADR-025](../adr/ADR-025-dealing-is-its-own-use-case.md) — why dealing is its own use case, and the started-but-undealt window
- [ADR-027](../adr/ADR-027-singleton-subresource-naming.md) — why a hand is read per player, which is why no broadcast above carries one
- [ADR-028](../adr/ADR-028-end-of-hand-without-release-or-score.md) — why the end of a hand is reported but neither released nor scored, and why the flag stays off
- [ADR-030](../adr/ADR-030-scoring-is-derived-not-accumulated.md) — scores are always derived from tricks, never read from stored standings
- [ADR-032](../adr/ADR-032-end-of-game-transitions.md) — end-of-game design choices: auto-complete placement, no new DB column, two-transaction race, facilitator-only early end
- [ADR-036](../adr/ADR-036-session-expiry-and-sweep.md) — session expiry TTL, the sweep scheduler, and the single-instance deployment assumption
- [ADR-039](../adr/ADR-039-new-game-reset.md) — new-game reset: COMPLETED→IN_PROGRESS via non-atomic multi-step sequence
- [`docs/api/openapi.yml`](../api/openapi.yml) — the authored contract for all seven trick-play routes
