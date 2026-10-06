/**
 * The card catalogue client.
 *
 * Every request is relative, never absolute. That is the whole point of the
 * single-origin topology in ADR-017: the browser talks to the same origin that
 * served the page, so there is no base URL to configure per environment and no
 * cross-origin handling anywhere in the system.
 */

/**
 * A STRIDE suit. Mirrors `org.maglez.eop.entity.StrideCategory` and the
 * `StrideCategory` schema in `docs/api/openapi.yml`.
 *
 * Declared as an `as const` array rather than a bare union of literals so that
 * the members survive compilation and `EnumMirrorParityTest` can compare them
 * with the other two artefacts. It was a bare union until EOP-105, and the
 * comment above it asserted parity with the server while nothing checked it —
 * the same unenforced invariant that let `PlayerDto.role` drift. `SUIT_LABELS`
 * below is keyed exhaustively off this union, so a drifted member would take the
 * label map with it while `tsc` stayed green.
 */
export const STRIDE_CATEGORIES = [
  "SPOOFING",
  "TAMPERING",
  "REPUDIATION",
  "INFORMATION_DISCLOSURE",
  "DENIAL_OF_SERVICE",
  "ELEVATION_OF_PRIVILEGE",
] as const;
export type StrideCategory = (typeof STRIDE_CATEGORIES)[number];

/** Narrows an unknown value to a {@link StrideCategory}. */
export function isStrideCategory(value: unknown): value is StrideCategory {
  return STRIDE_CATEGORIES.includes(value as StrideCategory);
}

/** One threat card, as published by `GET /api/v1/cards`. */
export interface Card {
  readonly cardId: string;
  readonly suit: StrideCategory;
  readonly rank: string;
  readonly rankSymbol: string;
  readonly rankValue: number;
  readonly threatPrompt: string;
}

/** A page of results, mirroring the server's paged envelope. */
export interface PagedResponse<T> {
  readonly content: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

/**
 * An error carrying the RFC 9457 problem detail the server returned, when it
 * returned one. The server never puts internal detail in a 5xx body, so `detail`
 * is deliberately treated as optional rather than assumed present.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ProblemDetail {
  readonly title?: string;
  readonly detail?: string;
}

// ---- Response validation (ADR-045) ----
//
// Every JSON-returning helper below passes its body through a `parseX` function
// rather than asserting a type onto it. An assertion is erased at compile time,
// so before EOP-108 a payload carrying `role: "ROOT"` reached React state and
// every comparison against it silently evaluated false. A parse turns that into
// a loud, diagnosable failure at the boundary.
//
// Strictness is bounded on purpose: object-ness, presence and `typeof` of
// required fields, arrays actually being arrays, and enum fields checked with the
// four `is*` guards. There is deliberately no format validation — no UUID regex,
// no ISO-8601 parsing, no range checks. So the DTOs are *structurally and
// enumerably* validated, which is a weaker claim than "validated", and the weaker
// claim is the true one. ADR-045 records why, and `.opencode/rules/error-handling.md`
// carries the directive.
//
// Parsers reconstruct rather than pass through, so a field the server sends but no
// parser reads is dropped. That is the known hazard: adding a field to a DTO
// interface without adding it here still typechecks, and the field then arrives
// `undefined`. Nothing detects it but review.
//
// The `parse*` functions are deliberately NOT exported. This module is the only
// place a response is parsed, and keeping them module-private is what makes that
// enforceable rather than merely stated: a component cannot reach for one, and a
// test cannot call one directly and mistake that for evidence the boundary works.
// They are covered through the exported helpers with global `fetch` stubbed, which
// is the only route that exercises the real boundary — a `vi.spyOn(api, …)` test
// replaces the helper wholesale and never reaches a parser at all.

/**
 * A response that was syntactically JSON but did not match the contract.
 *
 * Extends {@link ApiError} deliberately: every component already catches
 * `instanceof ApiError` and renders `.message` through `ErrorSummary`, so a
 * standalone class would slip past every existing catch and surface as a blank
 * screen. The status is a fixed 502 rather than the response's own status,
 * because the server said 200 and reusing that would misreport what happened.
 */
export class ContractViolationError extends ApiError {
  constructor(message: string) {
    super(502, message);
    this.name = "ContractViolationError";
  }
}

/**
 * Rejects a payload, naming the DTO and field but never the value.
 *
 * The offending value is deliberately excluded from the message so that a
 * response body cannot be laundered into rendered output.
 */
function violation(path: string, expectation: string): never {
  throw new ContractViolationError(`${path}: ${expectation}`);
}

function asObject(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    violation(path, "expected a JSON object");
  }
  return value as Record<string, unknown>;
}

function requireString(source: Record<string, unknown>, key: string, path: string): string {
  const value = source[key];
  if (typeof value !== "string") {
    violation(`${path}.${key}`, "expected a string");
  }
  return value;
}

function requireNumber(source: Record<string, unknown>, key: string, path: string): number {
  const value = source[key];
  if (typeof value !== "number" || !Number.isFinite(value)) {
    violation(`${path}.${key}`, "expected a finite number");
  }
  return value;
}

function requireBoolean(source: Record<string, unknown>, key: string, path: string): boolean {
  const value = source[key];
  if (typeof value !== "boolean") {
    violation(`${path}.${key}`, "expected a boolean");
  }
  return value;
}

function requireArray(source: Record<string, unknown>, key: string, path: string): readonly unknown[] {
  const value = source[key];
  if (!Array.isArray(value)) {
    violation(`${path}.${key}`, "expected an array");
  }
  return value;
}

/**
 * Narrows an enum-typed field using one of the four exported guards.
 *
 * The guard is passed in rather than the member list being re-inlined here:
 * `EnumMirrorParityTest` holds the `as const` arrays in step with the Java enums
 * and `docs/api/openapi.yml`, and a duplicated member list inside a parser would
 * be invisible to it.
 */
function requireEnum<T extends string>(
  source: Record<string, unknown>,
  key: string,
  path: string,
  guard: (value: unknown) => value is T,
  members: readonly string[],
): T {
  const value = source[key];
  if (!guard(value)) {
    violation(`${path}.${key}`, `expected one of ${members.join(", ")}`);
  }
  return value;
}

/** An absent field and an explicit `null` are both treated as "not present". */
function isAbsent(value: unknown): boolean {
  return value === undefined || value === null;
}

function optionalString(source: Record<string, unknown>, key: string, path: string): string | undefined {
  return isAbsent(source[key]) ? undefined : requireString(source, key, path);
}

function optionalNumber(source: Record<string, unknown>, key: string, path: string): number | undefined {
  return isAbsent(source[key]) ? undefined : requireNumber(source, key, path);
}

/**
 * The optional counterpart to `requireEnum`, for a contract enum that is genuinely
 * absent some of the time rather than merely nullable.
 *
 * It delegates to `requireEnum` once the field is known to be present, so a value
 * that *is* supplied is held to exactly the same membership check — "optional" here
 * means "may be absent", never "may be anything". Absence is the only concession.
 */
function optionalEnum<T extends string>(
  source: Record<string, unknown>,
  key: string,
  path: string,
  guard: (value: unknown) => value is T,
  members: readonly string[],
): T | undefined {
  return isAbsent(source[key]) ? undefined : requireEnum(source, key, path, guard, members);
}

function requireStringArray(source: Record<string, unknown>, key: string, path: string): readonly string[] {
  return requireArray(source, key, path).map((element, index) => {
    if (typeof element !== "string") {
      violation(`${path}.${key}[${index}]`, "expected a string");
    }
    return element;
  });
}

/**
 * Validates a map of string keys to numbers.
 *
 * The keys of such a map are themselves payload — the server chooses them — so
 * they must NOT reach the violation message. Naming the offending key would
 * reflect an attacker-influenced string into the GOV.UK error summary, which is
 * exactly what `.opencode/rules/error-handling.md` forbids ("names the DTO and
 * the field, never the payload"). So this deliberately does not delegate to
 * `requireNumber`, whose message interpolates the key it was given: it reports
 * only the field, and says "every value" rather than naming which one failed.
 * The cost is a slightly coarser diagnostic; the alternative is a reflection
 * vector, and a coarse message is the cheaper of the two.
 *
 * `Object.create(null)` rather than `{}` so a `__proto__`, `constructor` or
 * `toString` key in the payload cannot shadow an `Object.prototype` member and
 * leave the result non-coercible for a future consumer.
 */
function requireNumberRecord(
  source: Record<string, unknown>,
  key: string,
  path: string,
): Readonly<Record<string, number>> {
  const record = asObject(source[key], `${path}.${key}`);
  const parsed: Record<string, number> = Object.create(null) as Record<string, number>;
  for (const entryKey of Object.keys(record)) {
    const value = record[entryKey];
    if (typeof value !== "number" || !Number.isFinite(value)) {
      violation(`${path}.${key}`, "expected every value to be a finite number");
    }
    parsed[entryKey] = value;
  }
  return parsed;
}

/** Human-readable suit names, since the wire format is a shouting enum. */
export const SUIT_LABELS: Readonly<Record<StrideCategory, string>> = {
  SPOOFING: "Spoofing",
  TAMPERING: "Tampering",
  REPUDIATION: "Repudiation",
  INFORMATION_DISCLOSURE: "Information disclosure",
  DENIAL_OF_SERVICE: "Denial of service",
  ELEVATION_OF_PRIVILEGE: "Elevation of privilege",
};

/**
 * Extracts a human-readable message from an RFC 9457 problem body.
 *
 * This is not a DTO parser and deliberately never throws: it runs on a path that is
 * *already* an error, so a second failure here would replace a useful status code with
 * a `ContractViolationError` about the error body. It does still refuse to trust the
 * shape — `detail` and `title` are used only when they really are strings, so a body
 * carrying `{"detail": {...}}` degrades to the status text rather than rendering
 * `[object Object]` into the error summary.
 */
async function problemMessage(response: Response): Promise<string> {
  try {
    const problem: unknown = await response.json();
    if (typeof problem === "object" && problem !== null) {
      const source = problem as ProblemDetail;
      if (typeof source.detail === "string") return source.detail;
      if (typeof source.title === "string") return source.title;
    }
    return response.statusText;
  } catch {
    // A body that is not JSON is not an error worth surfacing on its own; the
    // status code is the useful part.
    return response.statusText;
  }
}

/** Parses one catalogue card. */
function parseCard(value: unknown, path = "Card"): Card {
  const source = asObject(value, path);
  return {
    cardId: requireString(source, "cardId", path),
    suit: requireEnum(source, "suit", path, isStrideCategory, STRIDE_CATEGORIES),
    rank: requireString(source, "rank", path),
    rankSymbol: requireString(source, "rankSymbol", path),
    rankValue: requireNumber(source, "rankValue", path),
    threatPrompt: requireString(source, "threatPrompt", path),
  };
}

/** Parses the server's paged envelope, delegating each element to `parseItem`. */
function parsePagedResponse<T>(
  value: unknown,
  parseItem: (element: unknown, path: string) => T,
  path: string,
): PagedResponse<T> {
  const source = asObject(value, path);
  return {
    content: requireArray(source, "content", path).map((element, index) =>
      parseItem(element, `${path}.content[${index}]`),
    ),
    page: requireNumber(source, "page", path),
    size: requireNumber(source, "size", path),
    totalElements: requireNumber(source, "totalElements", path),
    totalPages: requireNumber(source, "totalPages", path),
  };
}

/** Fetch one page of the card catalogue. */
export async function fetchCards(size = 20): Promise<PagedResponse<Card>> {
  const response = await fetch(`/api/v1/cards?size=${size}`, {
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parsePagedResponse(await response.json(), parseCard, "PagedResponse<Card>");
}

// Session API types

// The three enums below mirror server-side Java enums, and each one is declared
// as a runtime `as const` array with the union *derived* from it rather than as a
// bare union type. The reason is that a TypeScript union is erased at compile
// time, so a union alone cannot detect that the server sent a value the mirror
// does not list. Keeping the members at runtime is what makes the `is*` guards
// below possible, and since EOP-108 those guards are consumed by the parsers in
// this module rather than existing only for their own tests — see ADR-045.
// Two tests check the mirrors, and the split between them is deliberate.
// `api.test.ts` asserts each declared member is accepted and a non-member
// rejected, which is all a browser-side test can do: this project has no
// `@types/node` on purpose, so Vitest cannot read a file off disk. The
// cross-artefact comparison against the `enum` lists in `docs/api/openapi.yml`
// and the Java sources therefore lives in the Java suite, in
// `src/test/java/org/maglez/eop/docs/EnumMirrorParityTest.java`, which fails
// `./mvnw verify` when the three drift apart. See ADR-009 (EOP-105).

/** Mirrors `PlayerRole` (`org.maglez.eop.entity.PlayerRole`). */
export const PLAYER_ROLES = ['FACILITATOR', 'PARTICIPANT'] as const;
export type PlayerRole = (typeof PLAYER_ROLES)[number];

/** Mirrors `SessionStatus` (`org.maglez.eop.entity.SessionStatus`). */
export const SESSION_STATUSES = ['LOBBY', 'IN_PROGRESS', 'COMPLETED', 'ABANDONED'] as const;
export type SessionStatus = (typeof SESSION_STATUSES)[number];

/**
 * Mirrors the `ConnectionStatus` schema in `docs/api/openapi.yml`.
 *
 * Advisory only: the server discovers a dead connection on its next failed
 * write, so this can over-report `CONNECTED` between heartbeats. It is a display
 * hint and never an input to a game rule.
 */
export const CONNECTION_STATUSES = ['CONNECTED', 'DISCONNECTED'] as const;
export type ConnectionStatus = (typeof CONNECTION_STATUSES)[number];

export function isPlayerRole(value: unknown): value is PlayerRole {
  return PLAYER_ROLES.includes(value as PlayerRole);
}

export function isSessionStatus(value: unknown): value is SessionStatus {
  return SESSION_STATUSES.includes(value as SessionStatus);
}

export function isConnectionStatus(value: unknown): value is ConnectionStatus {
  return CONNECTION_STATUSES.includes(value as ConnectionStatus);
}

export interface PlayerDto {
  readonly playerId: string;
  readonly displayName: string;
  readonly seatOrder: number;
  readonly role: PlayerRole;
  readonly connectionStatus: ConnectionStatus;
}

export interface SessionStateDto {
  readonly sessionId: string;
  readonly joinCode: string;
  readonly status: SessionStatus;
  readonly players: readonly PlayerDto[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SessionAdmissionDto {
  readonly playerToken: string;
  readonly playerId: string;
  readonly session: SessionStateDto;
}

function parsePlayerDto(value: unknown, path = "PlayerDto"): PlayerDto {
  const source = asObject(value, path);
  return {
    playerId: requireString(source, "playerId", path),
    displayName: requireString(source, "displayName", path),
    seatOrder: requireNumber(source, "seatOrder", path),
    role: requireEnum(source, "role", path, isPlayerRole, PLAYER_ROLES),
    connectionStatus: requireEnum(source, "connectionStatus", path, isConnectionStatus, CONNECTION_STATUSES),
  };
}

function parseSessionStateDto(value: unknown, path = "SessionStateDto"): SessionStateDto {
  const source = asObject(value, path);
  return {
    sessionId: requireString(source, "sessionId", path),
    joinCode: requireString(source, "joinCode", path),
    status: requireEnum(source, "status", path, isSessionStatus, SESSION_STATUSES),
    players: requireArray(source, "players", path).map((element, index) =>
      parsePlayerDto(element, `${path}.players[${index}]`),
    ),
    createdAt: requireString(source, "createdAt", path),
    updatedAt: requireString(source, "updatedAt", path),
  };
}

function parseSessionAdmissionDto(value: unknown, path = "SessionAdmissionDto"): SessionAdmissionDto {
  const source = asObject(value, path);
  return {
    playerToken: requireString(source, "playerToken", path),
    playerId: requireString(source, "playerId", path),
    session: parseSessionStateDto(source["session"], `${path}.session`),
  };
}

// Header name constant (matches backend)
export const PLAYER_TOKEN_HEADER = 'X-EoP-Player-Token';

/**
 * Create a new session
 */
export async function createSession(displayName: string): Promise<SessionAdmissionDto> {
  const response = await fetch('/api/v1/sessions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
    body: JSON.stringify({ displayName }),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseSessionAdmissionDto(await response.json());
}

/**
 * Join an existing session
 */
export async function joinSession(joinCode: string, displayName: string): Promise<SessionAdmissionDto> {
  const response = await fetch(`/api/v1/sessions/${joinCode}/players`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
    body: JSON.stringify({ displayName }),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseSessionAdmissionDto(await response.json());
}

/**
 * Get session state
 */
export async function getSession(sessionId: string, playerToken: string): Promise<SessionStateDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseSessionStateDto(await response.json());
}

/**
 * Start the game (facilitator only)
 */
export async function startGame(sessionId: string, playerToken: string): Promise<SessionStateDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/start`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseSessionStateDto(await response.json());
}

/**
 * End the game early (facilitator only).
 *
 * The server answers `204 No Content`, so this helper reads no body and
 * therefore has no parser (ADR-045: a helper that reads no body gets no parser).
 */
export async function endSession(sessionId: string, playerToken: string): Promise<void> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/end`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
}

/**
 * Subscribe to session events via SSE.
 *
 * Uses `fetch` rather than `EventSource` because `EventSource` cannot set
 * custom request headers (ADR-015). The stream is used as a doorbell: each
 * `data:` frame signals that session state has changed; the caller is
 * responsible for re-fetching via `getSession`.
 *
 * **A doorbell is only heard by a subscriber that already exists, so `onOpen`
 * is not optional in spirit (EOP-224).** The server publishes to whoever is
 * subscribed at that instant and keeps no history — there is no
 * `Last-Event-ID` replay — so an event published before this stream was
 * registered is lost to this caller for ever. Every mount therefore has a
 * window, and a screen transition that swaps one stream for another has two.
 * `onOpen` closes it: let T be the instant the server registers this
 * subscriber, which necessarily precedes the response headers observed here.
 * Everything published after T arrives as a `data:` frame; everything
 * published before T is visible to a read issued after the headers. So a
 * caller that re-reads its state in `onOpen` has no interval in which a change
 * can be both undelivered and unobserved. No polling and no timers.
 *
 * @param sessionId  The session to subscribe to.
 * @param playerToken  The caller's credential (sent as `PLAYER_TOKEN_HEADER`).
 * @param onEvent  Called whenever a `data:` frame arrives.
 * @param onError  Called when the stream ends with a non-ok response or an
 *                 unexpected error. Receives the `ApiError` (for 4xx/5xx) or
 *                 a plain `Error` (for network failures).
 * @param onOpen  Called once, as soon as the server has accepted the
 *                subscription, and never again for the life of this stream.
 *                Callers MUST use it to re-read the state they care about —
 *                see the reconnect-gap argument above. Optional only so that
 *                a caller with genuinely nothing to re-read may omit it.
 * @returns An `AbortController` whose `abort()` method tears down the stream.
 */
export function subscribeToSession(
  sessionId: string,
  playerToken: string,
  onEvent: (eventType: string | null) => void,
  onError: (err: ApiError | Error) => void,
  onOpen?: () => void,
): AbortController {
  const controller = new AbortController();

  const run = async (): Promise<void> => {
    const res = await fetch(`/api/v1/sessions/${sessionId}/events`, {
      headers: {
        'Accept': 'text/event-stream',
        [PLAYER_TOKEN_HEADER]: playerToken,
      },
      signal: controller.signal,
    });

    if (!res.ok) {
      // A non-2xx response (e.g. 403 expired token, 404 session gone) returns a
      // JSON problem-detail body, not an SSE stream.
      const message = await problemMessage(res);
      onError(new ApiError(res.status, message));
      return;
    }

    // The subscription is live from here: the server registered this subscriber
    // before it sent these headers. Announced before the body is touched so the
    // caller's catch-up read overlaps the first frames rather than following
    // them — see the reconnect-gap argument on this function.
    onOpen?.();

    if (!res.body) return;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        // Keep the last (potentially incomplete) line in the buffer
        buffer = lines.pop() ?? '';

        let currentEventType: string | null = null;
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEventType = line.slice('event:'.length).trim();
          } else if (line.startsWith('data:')) {
            onEvent(currentEventType);
            currentEventType = null;
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
  };

  run().catch((err: unknown) => {
    // AbortError is expected on teardown — suppress it.
    if (err instanceof Error && err.name === 'AbortError') return;
    onError(err instanceof Error ? err : new Error(String(err)));
  });

  return controller;
}

// ---- Game Over API types ----

export interface LeaderboardRowDto {
  readonly playerId: string;
  readonly seatOrder: number;
  readonly displayName: string;
  readonly points: number;
  readonly position: number;
  readonly tied: boolean;
  readonly capturedBySuit: Readonly<Record<string, number>>;
}

export interface LeaderboardDto {
  readonly rows: readonly LeaderboardRowDto[];
  readonly sessionStatus: SessionStatus;
}

function parseLeaderboardRowDto(value: unknown, path = 'LeaderboardRowDto'): LeaderboardRowDto {
  const source = asObject(value, path);
  return {
    playerId: requireString(source, 'playerId', path),
    seatOrder: requireNumber(source, 'seatOrder', path),
    displayName: requireString(source, 'displayName', path),
    points: requireNumber(source, 'points', path),
    position: requireNumber(source, 'position', path),
    tied: requireBoolean(source, 'tied', path),
    capturedBySuit: requireNumberRecord(source, 'capturedBySuit', path),
  };
}

function parseLeaderboardDto(value: unknown, path = 'LeaderboardDto'): LeaderboardDto {
  const source = asObject(value, path);
  return {
    rows: requireArray(source, 'rows', path).map((row, index) =>
      parseLeaderboardRowDto(row, `${path}.rows[${index}]`),
    ),
    sessionStatus: requireEnum(source, 'sessionStatus', path, isSessionStatus, SESSION_STATUSES),
  };
}

export async function getLeaderboard(sessionId: string, playerToken: string): Promise<LeaderboardDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/leaderboard`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseLeaderboardDto(await response.json());
}

export async function startNewGame(sessionId: string, playerToken: string): Promise<void> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/new-game`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
}

// ---- Game Screen API types ----

export interface CardDto {
  readonly cardId: string;
  /**
   * Narrowed from a bare `string` to `StrideCategory` by EOP-108: `isStrideCategory`
   * already existed and `parseCardDto` below checks membership, so leaving the type
   * wide would have described the field as less certain than it now is. EOP-109 did
   * the same for `TrickDto.ledSuit`, the last field typed `string` against a
   * *mirrored* enum schema.
   *
   * `rank` below stays a bare `string`, and that is an accepted drift rather than
   * fidelity: the contract does `$ref` the `Rank` enum here, so the wide type is
   * genuinely less specific than the contract. It is accepted because the client
   * never compares or orders a rank — `rankValue` exists for comparison and the card
   * face is rendered from `rankSymbol` — leaving `rank` with exactly one consumer,
   * `cardImagePath(suit, rank)`, which returns `null` for anything it does not
   * recognise and whose every call site null-checks. EOP-109 rejected adding a `Rank`
   * mirror on those grounds; see ADR-009.
   */
  readonly suit: StrideCategory;
  readonly rank: string;
  readonly rankSymbol: string;
  readonly rankValue: number;
  readonly threatPrompt: string;
}

export interface HandDto {
  readonly handId: string;
  readonly playerId: string;
  readonly cardCount: number;
  readonly cards: readonly CardDto[];
}

export interface TrickPlayDto {
  readonly trickPlayId: string;
  readonly playerId: string;
  readonly seatOrder: number;
  readonly card: CardDto;
  readonly threatLinked: boolean;
  readonly components: readonly string[];
  readonly notes?: string;
  readonly playedAt: string;
}

export interface TrickDto {
  readonly trickId: string;
  readonly sequence: number;
  readonly leaderSeat: number;
  /**
   * Narrowed from a bare `string` to `StrideCategory` by EOP-109, matching the
   * contract, which declares it `allOf: [$ref: StrideCategory]`.
   *
   * It stays optional because the server genuinely omits it: `TrickDto` is
   * `@JsonInclude(NON_NULL)` and `ledSuit` is unset until the first card of a
   * trick is played. So the field is parsed with `optionalEnum`, not `requireEnum`
   * — absent is legal, an out-of-contract suit is not.
   */
  readonly ledSuit?: StrideCategory;
  readonly plays: readonly TrickPlayDto[];
  readonly winningSeat?: number;
}

export interface TrickStateDto {
  readonly trick?: TrickDto;
  readonly seatToPlay?: number;
  readonly complete: boolean;
  readonly nextLeaderSeat?: number;
  readonly handComplete: boolean;
}

export interface PlayCardRequest {
  readonly cardId: string;
  readonly threatLinked?: boolean;
  readonly components?: string[];
  readonly notes?: string;
}

function parseCardDto(value: unknown, path = 'CardDto'): CardDto {
  const source = asObject(value, path);
  return {
    cardId: requireString(source, 'cardId', path),
    suit: requireEnum(source, 'suit', path, isStrideCategory, STRIDE_CATEGORIES),
    rank: requireString(source, 'rank', path),
    rankSymbol: requireString(source, 'rankSymbol', path),
    rankValue: requireNumber(source, 'rankValue', path),
    threatPrompt: requireString(source, 'threatPrompt', path),
  };
}

function parseHandDto(value: unknown, path = 'HandDto'): HandDto {
  const source = asObject(value, path);
  return {
    handId: requireString(source, 'handId', path),
    playerId: requireString(source, 'playerId', path),
    cardCount: requireNumber(source, 'cardCount', path),
    cards: requireArray(source, 'cards', path).map((card, index) =>
      parseCardDto(card, `${path}.cards[${index}]`),
    ),
  };
}

/**
 * Optional fields are spread in conditionally rather than assigned `undefined`, because
 * `ui/tsconfig.json` sets `exactOptionalPropertyTypes: true`: under that flag `notes?: string`
 * means "absent or a string", and explicitly writing `notes: undefined` is a type error.
 */
function parseTrickPlayDto(value: unknown, path = 'TrickPlayDto'): TrickPlayDto {
  const source = asObject(value, path);
  const notes = optionalString(source, 'notes', path);
  return {
    trickPlayId: requireString(source, 'trickPlayId', path),
    playerId: requireString(source, 'playerId', path),
    seatOrder: requireNumber(source, 'seatOrder', path),
    card: parseCardDto(source['card'], `${path}.card`),
    threatLinked: requireBoolean(source, 'threatLinked', path),
    components: requireStringArray(source, 'components', path),
    playedAt: requireString(source, 'playedAt', path),
    ...(notes === undefined ? {} : { notes }),
  };
}

function parseTrickDto(value: unknown, path = 'TrickDto'): TrickDto {
  const source = asObject(value, path);
  const ledSuit = optionalEnum(source, 'ledSuit', path, isStrideCategory, STRIDE_CATEGORIES);
  const winningSeat = optionalNumber(source, 'winningSeat', path);
  return {
    trickId: requireString(source, 'trickId', path),
    sequence: requireNumber(source, 'sequence', path),
    leaderSeat: requireNumber(source, 'leaderSeat', path),
    plays: requireArray(source, 'plays', path).map((play, index) =>
      parseTrickPlayDto(play, `${path}.plays[${index}]`),
    ),
    ...(ledSuit === undefined ? {} : { ledSuit }),
    ...(winningSeat === undefined ? {} : { winningSeat }),
  };
}

function parseTrickStateDto(value: unknown, path = 'TrickStateDto'): TrickStateDto {
  const source = asObject(value, path);
  const trick = isAbsent(source['trick']) ? undefined : parseTrickDto(source['trick'], `${path}.trick`);
  const seatToPlay = optionalNumber(source, 'seatToPlay', path);
  const nextLeaderSeat = optionalNumber(source, 'nextLeaderSeat', path);
  return {
    complete: requireBoolean(source, 'complete', path),
    handComplete: requireBoolean(source, 'handComplete', path),
    ...(trick === undefined ? {} : { trick }),
    ...(seatToPlay === undefined ? {} : { seatToPlay }),
    ...(nextLeaderSeat === undefined ? {} : { nextLeaderSeat }),
  };
}

/**
 * Deal hands to all players (facilitator only).
 *
 * Calls POST /api/v1/sessions/{id}/deal. The server returns 204 No Content on
 * success; the caller should then fetch the hand via fetchHand().
 *
 * Named for the `dealHands` operationId in docs/api/openapi.yml, which the Java
 * adapter method and use case also carry.
 */
export async function dealHands(sessionId: string, playerToken: string): Promise<void> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/deal`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
}

/** Fetch the current player's own hand */
export async function fetchHand(sessionId: string, playerToken: string): Promise<HandDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/hand`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseHandDto(await response.json());
}

/** Get the current trick state */
export async function getTrickState(sessionId: string, playerToken: string): Promise<TrickStateDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/tricks/current`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseTrickStateDto(await response.json());
}

/** Play a card into the current trick */
export async function playCard(sessionId: string, playerToken: string, request: PlayCardRequest): Promise<TrickDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/plays`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseTrickDto(await response.json());
}

/** Resolve the current trick */
export async function resolveTrick(sessionId: string, playerToken: string): Promise<TrickDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/tricks/current/resolve`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return parseTrickDto(await response.json());
}
