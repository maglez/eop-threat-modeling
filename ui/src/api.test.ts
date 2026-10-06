/**
 * Unit tests for subscribeToSession and dealHands in api.ts.
 *
 * subscribeToSession covers all six branches of the function:
 *  1. Non-ok response → onError(ApiError) with numeric status
 *  2. res.body is null → silent return (no callbacks)
 *  3. Stream ends cleanly (done === true) → no callbacks
 *  4. data: frame present → onEvent() called
 *  5. AbortError suppressed on teardown (abort() called)
 *  6. Non-AbortError network error → onError(Error)
 *
 * dealHands covers:
 *  1. 204 No Content → resolves void
 *  2. Non-ok response → throws ApiError with correct status
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  subscribeToSession,
  dealHands,
  fetchCards,
  createSession,
  joinSession,
  getSession,
  startGame,
  endSession,
  PLAYER_TOKEN_HEADER,
  getLeaderboard,
  fetchHand,
  getTrickState,
  playCard,
  resolveTrick,
  ApiError,
  ContractViolationError,
  PLAYER_ROLES,
  SESSION_STATUSES,
  CONNECTION_STATUSES,
  STRIDE_CATEGORIES,
  SUIT_LABELS,
  isPlayerRole,
  isSessionStatus,
  isConnectionStatus,
  isStrideCategory,
} from './api';

// Helper: build a minimal Response-like object
function makeResponse(
  ok: boolean,
  status: number,
  body: ReadableStream<Uint8Array> | null,
  jsonFn?: () => Promise<unknown>,
): Response {
  return {
    ok,
    status,
    statusText: ok ? 'OK' : 'Error',
    body,
    json: jsonFn ?? (() => Promise.resolve({})),
    headers: new Headers(),
  } as unknown as Response;
}

// Helper: build a ReadableStream that yields one chunk then closes
function streamOf(chunk: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let sent = false;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (!sent) {
        sent = true;
        controller.enqueue(encoder.encode(chunk));
      } else {
        controller.close();
      }
    },
  });
}

// Helper: build a ReadableStream that never closes (simulates a live SSE connection)
function neverEndingStream(): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({ pull() { /* never resolves */ } });
}

describe('subscribeToSession', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('calls onError with ApiError when response is not ok (403)', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(false, 403, null, () =>
        Promise.resolve({ title: 'Forbidden', detail: 'The session has expired. Please start a new session.' })
      ))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    const firstCall = onError.mock.calls[0];
    expect(firstCall).toBeDefined();
    const err = firstCall?.[0] as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(403);
    expect(err.message).toBe('The session has expired. Please start a new session.');
    expect(onEvent).not.toHaveBeenCalled();

    controller.abort();
  });

  it('calls onError with ApiError when response is not ok (404)', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(false, 404, null, () =>
        Promise.resolve({ title: 'Session not found', detail: 'No session matches that join code.' })
      ))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    const firstCall404 = onError.mock.calls[0];
    expect(firstCall404).toBeDefined();
    const err = firstCall404?.[0] as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(404);
    expect(onEvent).not.toHaveBeenCalled();

    controller.abort();
  });

  it('does not call onEvent or onError when res.body is null', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, null))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    // Give the async run() time to complete
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(onEvent).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('does not call onEvent or onError when stream ends cleanly with no data frames', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    // Stream yields a heartbeat comment (no 'data:' prefix) then closes
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, streamOf(': heartbeat\n\n')))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await new Promise(resolve => setTimeout(resolve, 50));

    expect(onEvent).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('calls onEvent when a data: frame arrives in the stream', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, streamOf('data: session-updated\n\n')))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onEvent).toHaveBeenCalledOnce();
    });

    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('suppresses AbortError when abort() is called (teardown path)', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    // Never-ending stream so we can abort mid-read
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, neverEndingStream()))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    // Abort immediately
    controller.abort();

    // Give the catch handler time to run
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(onError).not.toHaveBeenCalled();
    expect(onEvent).not.toHaveBeenCalled();
  });

  it('calls onError with a plain Error on unexpected network failure', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    const networkError = new TypeError('Failed to fetch');
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(networkError)));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    const firstCallNet = onError.mock.calls[0];
    expect(firstCallNet).toBeDefined();
    const err = firstCallNet?.[0] as Error;
    expect(err).toBeInstanceOf(Error);
    expect(err.message).toBe('Failed to fetch');
    expect(onEvent).not.toHaveBeenCalled();

    controller.abort();
  });

  /*
   * EOP-224. The server keeps no event history, so a doorbell published before a
   * subscriber existed is lost to that subscriber for ever. `onOpen` is the only
   * signal a caller gets that the stream is live and that a catch-up read is now
   * guaranteed to observe everything published earlier.
   */
  it('calls onOpen exactly once when the stream is accepted, before any data frame', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();
    const onOpen = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, streamOf('event: cards-dealt\ndata: {}\n\n')))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError, onOpen);

    await vi.waitFor(() => {
      expect(onEvent).toHaveBeenCalledOnce();
    });

    expect(onOpen).toHaveBeenCalledOnce();
    // Ordering matters: a catch-up read issued after the first frame would not be
    // a catch-up at all.
    expect(onOpen.mock.invocationCallOrder[0]).toBeLessThan(onEvent.mock.invocationCallOrder[0] ?? 0);
    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });

  it('does not call onOpen when the subscription is refused', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();
    const onOpen = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(false, 403, null, () =>
        Promise.resolve({ title: 'Forbidden', detail: 'The session has expired. Please start a new session.' })
      ))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError, onOpen);

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledOnce();
    });

    expect(onOpen).not.toHaveBeenCalled();

    controller.abort();
  });

  it('remains usable without an onOpen callback', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();

    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve(makeResponse(true, 200, streamOf('data: {}\n\n')))
    ));

    const controller = subscribeToSession('session-1', 'token-abc', onEvent, onError);

    await vi.waitFor(() => {
      expect(onEvent).toHaveBeenCalledOnce();
    });

    expect(onError).not.toHaveBeenCalled();

    controller.abort();
  });
});

describe('dealHands', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('resolves void when server returns 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 204,
        statusText: 'No Content',
        json: () => Promise.resolve({}),
        headers: new Headers(),
      } as unknown as Response)
    ));

    await expect(dealHands('session-1', 'token-abc')).resolves.toBeUndefined();

    const fetchMock = vi.mocked(fetch);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/sessions/session-1/deal');
    expect(options.method).toBe('POST');
  });

  it('throws ApiError with correct status when server returns non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 409,
        statusText: 'Conflict',
        json: () => Promise.resolve({ title: 'Cards already dealt', detail: 'Cards have already been dealt for this session.' }),
        headers: new Headers(),
      } as unknown as Response)
    ));

    await expect(dealHands('session-1', 'token-abc')).rejects.toThrow(ApiError);

    try {
      await dealHands('session-1', 'token-abc');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const apiErr = e as ApiError;
      expect(apiErr.status).toBe(409);
    }
  });
});

describe('endSession', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('posts to the end route with the player-token header and resolves void on 204', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 204,
        statusText: 'No Content',
        json: () => Promise.resolve({}),
        headers: new Headers(),
      } as unknown as Response)
    ));

    await expect(endSession('session-1', 'token-abc')).resolves.toBeUndefined();

    const fetchMock = vi.mocked(fetch);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/sessions/session-1/end');
    expect(options.method).toBe('POST');
    expect((options.headers as Record<string, string>)[PLAYER_TOKEN_HEADER]).toBe('token-abc');
  });

  it('throws ApiError with the status when the server refuses (409 not in progress)', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 409,
        statusText: 'Conflict',
        json: () => Promise.resolve({ title: 'Not in progress', detail: 'The session is not IN_PROGRESS.' }),
        headers: new Headers(),
      } as unknown as Response)
    ));

    await expect(endSession('session-1', 'token-abc')).rejects.toThrow(ApiError);

    try {
      await endSession('session-1', 'token-abc');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      expect((e as ApiError).status).toBe(409);
    }
  });
});

/**
 * Drift guard for the enum mirrors (EOP-105).
 *
 * These four unions are hand-maintained copies of server-side Java enums, and
 * two of them had silently drifted: `role` listed a `PLAYER` the server never
 * sends while omitting the `PARTICIPANT` it sends for every non-facilitator, and
 * `status` listed a non-existent `ENDED` while omitting `COMPLETED` and
 * `ABANDONED`. Nothing failed, because a TypeScript union is erased at runtime and
 * DTOs arrived through a type assertion rather than a parse, so every comparison
 * against the real value just evaluated false. Since EOP-108 the arrays are also
 * load-bearing at runtime — the guards below are consumed by the parsers in
 * `api.ts`, so a drifted member would now reject live payloads rather than merely
 * mis-compare them, which raises the cost of drift and is the reason this block
 * pins the members literally.
 *
 * `STRIDE_CATEGORIES` is here because the review of this story found it in the
 * same condition the other two had been in — a bare union whose comment asserted
 * parity with the server while nothing checked it. It had not drifted, which is
 * exactly why it is worth pinning now rather than after it does.
 *
 * The members are therefore kept as runtime `as const` arrays, and asserted here
 * literally rather than by iterating the array under test — a test that looped
 * over `PLAYER_ROLES` would pass no matter what that array contained. The
 * cross-artefact check against `docs/api/openapi.yml` and the Java enums lives in
 * `EnumMirrorParityTest` on the Java side, which can read all three files.
 */
describe('enum mirrors', () => {
  it('lists exactly the members of the Java PlayerRole enum', () => {
    expect([...PLAYER_ROLES]).toEqual(['FACILITATOR', 'PARTICIPANT']);
  });

  it('lists exactly the members of the Java SessionStatus enum', () => {
    expect([...SESSION_STATUSES]).toEqual(['LOBBY', 'IN_PROGRESS', 'COMPLETED', 'ABANDONED']);
  });

  it('lists exactly the members of the ConnectionStatus schema', () => {
    expect([...CONNECTION_STATUSES]).toEqual(['CONNECTED', 'DISCONNECTED']);
  });

  it('lists exactly the members of the Java StrideCategory enum, in deck order', () => {
    // Order is asserted here, unlike in EnumMirrorParityTest which compares
    // membership only: StrideCategory.deckOrder() on the server is `ordinal() + 1`,
    // so the declaration order is load-bearing and the mirror should not reshuffle
    // it even though the parity gate would tolerate that.
    expect([...STRIDE_CATEGORIES]).toEqual([
      'SPOOFING',
      'TAMPERING',
      'REPUDIATION',
      'INFORMATION_DISCLOSURE',
      'DENIAL_OF_SERVICE',
      'ELEVATION_OF_PRIVILEGE'
    ]);
  });

  it('accepts every declared member', () => {
    for (const role of PLAYER_ROLES) {
      expect(isPlayerRole(role)).toBe(true);
    }
    for (const status of SESSION_STATUSES) {
      expect(isSessionStatus(status)).toBe(true);
    }
    for (const status of CONNECTION_STATUSES) {
      expect(isConnectionStatus(status)).toBe(true);
    }
    for (const suit of STRIDE_CATEGORIES) {
      expect(isStrideCategory(suit)).toBe(true);
    }
  });

  it('labels every STRIDE category it declares', () => {
    // SUIT_LABELS is annotated Readonly<Record<StrideCategory, string>>, so tsc already
    // catches a missing key and — via excess-property checking on the object literal —
    // a key with no matching union member. The direction the compiler genuinely cannot
    // see is a key that is present but whose label is empty or otherwise falsy: `''`
    // satisfies `string`. That is what the truthiness loop below is for. The length
    // check is belt-and-braces behind tsc for the two structural directions.
    // Removing a member from BOTH the array and the map is deliberately not this
    // case's job — the loop would then iterate five truthy members and the length check
    // would read 5 === 5. That mutation is caught by the literal-equality case above,
    // whose hard-coded six-member list is the only thing here that pins the count.
    for (const suit of STRIDE_CATEGORIES) {
      expect(SUIT_LABELS[suit]).toBeTruthy();
    }
    expect(Object.keys(SUIT_LABELS)).toHaveLength(STRIDE_CATEGORIES.length);
  });

  it('rejects the two values that had drifted into the mirror', () => {
    // The exact regression this story fixes: both of these used to typecheck.
    expect(isPlayerRole('PLAYER')).toBe(false);
    expect(isSessionStatus('ENDED')).toBe(false);
  });

  it('rejects non-members, including case variants and non-strings', () => {
    expect(isPlayerRole('facilitator')).toBe(false);
    expect(isPlayerRole('')).toBe(false);
    expect(isPlayerRole(undefined)).toBe(false);
    expect(isPlayerRole(null)).toBe(false);
    expect(isPlayerRole(0)).toBe(false);

    expect(isSessionStatus('lobby')).toBe(false);
    expect(isSessionStatus('IN PROGRESS')).toBe(false);
    expect(isSessionStatus(undefined)).toBe(false);

    expect(isConnectionStatus('connected')).toBe(false);
    expect(isConnectionStatus('UNKNOWN')).toBe(false);
    expect(isConnectionStatus(undefined)).toBe(false);

    expect(isStrideCategory('spoofing')).toBe(false);
    expect(isStrideCategory('ELEVATION OF PRIVILEGE')).toBe(false);
    expect(isStrideCategory(undefined)).toBe(false);
  });
});

/**
 * Response validation at the boundary (EOP-108, ADR-045).
 *
 * Every JSON-returning helper used to end `return (await response.json()) as SomeDto`
 * — an assertion TypeScript erases at runtime, so an out-of-contract payload reached
 * React state and every comparison against it silently evaluated false. Each helper
 * now parses instead, and a payload that does not match the contract raises a
 * `ContractViolationError` (a 502 `ApiError`) rather than being admitted.
 *
 * These tests stub global `fetch` deliberately. That is the only route that reaches a
 * parser: the `parse*` functions are module-private, and a `vi.spyOn(api, …)` test
 * replaces the helper wholesale, so such a test is not evidence the boundary works.
 *
 * Fixtures below are contract-complete on purpose. The happy-path assertion is
 * `toEqual(validBody)`, which is stronger than a spot-check: because parsers
 * reconstruct rather than pass through, it fails if a parser drops a contracted field.
 */

const catalogueCard = {
  cardId: 'S2',
  suit: 'SPOOFING',
  rank: 'TWO',
  rankSymbol: '2',
  rankValue: 2,
  threatPrompt: 'An attacker could impersonate another user.',
};

const pagedCards = { content: [catalogueCard], page: 0, size: 20, totalElements: 1, totalPages: 1 };

const player = {
  playerId: 'p-1',
  displayName: 'Ada',
  seatOrder: 0,
  role: 'FACILITATOR',
  connectionStatus: 'CONNECTED',
};

const session = {
  sessionId: 's-1',
  joinCode: 'ABCD',
  status: 'LOBBY',
  players: [player],
  createdAt: '2026-08-19T10:00:00Z',
  updatedAt: '2026-08-19T10:01:00Z',
};

const admission = { playerToken: 'opaque-token', playerId: 'p-1', session };

const leaderboardRow = {
  playerId: 'p-1',
  seatOrder: 0,
  displayName: 'Ada',
  points: 3,
  position: 1,
  tied: false,
  capturedBySuit: { SPOOFING: 2, TAMPERING: 1 },
};

const leaderboard = { rows: [leaderboardRow], sessionStatus: 'IN_PROGRESS' };

const hand = { handId: 'h-1', playerId: 'p-1', cardCount: 1, cards: [catalogueCard] };

const trickPlay = {
  trickPlayId: 'tp-1',
  playerId: 'p-1',
  seatOrder: 0,
  card: catalogueCard,
  threatLinked: true,
  components: ['auth-service'],
  notes: 'session fixation',
  playedAt: '2026-08-19T10:05:00Z',
};

const trick = { trickId: 't-1', sequence: 1, leaderSeat: 0, plays: [trickPlay], ledSuit: 'SPOOFING', winningSeat: 0 };

const trickState = { complete: false, handComplete: false, trick, seatToPlay: 1, nextLeaderSeat: 0 };

/** Stubs `fetch` with a 200 response carrying `body`. */
function stubOkJson(body: unknown): void {
  vi.stubGlobal('fetch', vi.fn(() =>
    Promise.resolve({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: () => Promise.resolve(body),
      headers: new Headers(),
    } as unknown as Response)
  ));
}

/** Replaces one key of a nested clone, so a fixture can be corrupted at a single point. */
function withCorruptEnum<T>(fixture: T, mutate: (clone: Record<string, unknown>) => void): unknown {
  const clone = JSON.parse(JSON.stringify(fixture)) as Record<string, unknown>;
  mutate(clone);
  return clone;
}

interface BoundaryCase {
  readonly helper: string;
  readonly call: () => Promise<unknown>;
  readonly validBody: unknown;
  readonly corruptBody: unknown;
  /** The exact `DTO.field` path the rejection must name. */
  readonly path: string;
  /** The out-of-contract value, which must NOT appear in the message. */
  readonly badValue: string;
}

const boundaries: readonly BoundaryCase[] = [
  {
    helper: 'fetchCards',
    call: () => fetchCards(20),
    validBody: pagedCards,
    corruptBody: withCorruptEnum(pagedCards, (c) => {
      (c['content'] as Record<string, unknown>[])[0]!['suit'] = 'CHAOS';
    }),
    path: 'PagedResponse<Card>.content[0].suit',
    badValue: 'CHAOS',
  },
  {
    helper: 'createSession',
    call: () => createSession('Ada'),
    validBody: admission,
    corruptBody: withCorruptEnum(admission, (c) => {
      (c['session'] as Record<string, unknown>)['status'] = 'ENDED';
    }),
    path: 'SessionAdmissionDto.session.status',
    badValue: 'ENDED',
  },
  {
    helper: 'joinSession',
    call: () => joinSession('ABCD', 'Grace'),
    validBody: admission,
    corruptBody: withCorruptEnum(admission, (c) => {
      const s = c['session'] as Record<string, unknown>;
      (s['players'] as Record<string, unknown>[])[0]!['role'] = 'PLAYER';
    }),
    path: 'SessionAdmissionDto.session.players[0].role',
    badValue: 'PLAYER',
  },
  {
    helper: 'getSession',
    call: () => getSession('s-1', 'token'),
    validBody: session,
    corruptBody: withCorruptEnum(session, (c) => {
      c['status'] = 'WHATEVER';
    }),
    path: 'SessionStateDto.status',
    badValue: 'WHATEVER',
  },
  {
    helper: 'startGame',
    call: () => startGame('s-1', 'token'),
    validBody: session,
    corruptBody: withCorruptEnum(session, (c) => {
      (c['players'] as Record<string, unknown>[])[0]!['connectionStatus'] = 'FLAKY';
    }),
    path: 'SessionStateDto.players[0].connectionStatus',
    badValue: 'FLAKY',
  },
  {
    helper: 'getLeaderboard',
    call: () => getLeaderboard('s-1', 'token'),
    validBody: leaderboard,
    corruptBody: withCorruptEnum(leaderboard, (c) => {
      c['sessionStatus'] = 'ENDED';
    }),
    path: 'LeaderboardDto.sessionStatus',
    badValue: 'ENDED',
  },
  {
    helper: 'fetchHand',
    call: () => fetchHand('s-1', 'token'),
    validBody: hand,
    corruptBody: withCorruptEnum(hand, (c) => {
      (c['cards'] as Record<string, unknown>[])[0]!['suit'] = 'ROOT';
    }),
    path: 'HandDto.cards[0].suit',
    badValue: 'ROOT',
  },
  {
    helper: 'getTrickState',
    call: () => getTrickState('s-1', 'token'),
    validBody: trickState,
    corruptBody: withCorruptEnum(trickState, (c) => {
      const t = c['trick'] as Record<string, unknown>;
      const play = (t['plays'] as Record<string, unknown>[])[0]!;
      (play['card'] as Record<string, unknown>)['suit'] = 'CHAOS';
    }),
    path: 'TrickStateDto.trick.plays[0].card.suit',
    badValue: 'CHAOS',
  },
  {
    helper: 'playCard',
    call: () => playCard('s-1', 'token', { cardId: 'S2' }),
    validBody: trick,
    corruptBody: withCorruptEnum(trick, (c) => {
      const play = (c['plays'] as Record<string, unknown>[])[0]!;
      (play['card'] as Record<string, unknown>)['suit'] = 'PRIVILEGE_ESCALATION';
    }),
    path: 'TrickDto.plays[0].card.suit',
    badValue: 'PRIVILEGE_ESCALATION',
  },
  {
    helper: 'resolveTrick',
    call: () => resolveTrick('s-1', 'token'),
    validBody: trick,
    corruptBody: withCorruptEnum(trick, (c) => {
      const play = (c['plays'] as Record<string, unknown>[])[0]!;
      (play['card'] as Record<string, unknown>)['suit'] = 'ELEVATION OF PRIVILEGE';
    }),
    path: 'TrickDto.plays[0].card.suit',
    badValue: 'ELEVATION OF PRIVILEGE',
  },
  {
    // EOP-109: `ledSuit` is optional, so it is parsed with `optionalEnum` rather than
    // `requireEnum`. This case pins the half that optionality must not weaken — a
    // supplied value is still held to membership.
    helper: 'resolveTrick (ledSuit)',
    call: () => resolveTrick('s-1', 'token'),
    validBody: trick,
    corruptBody: withCorruptEnum(trick, (c) => {
      c['ledSuit'] = 'DIAMONDS';
    }),
    path: 'TrickDto.ledSuit',
    badValue: 'DIAMONDS',
  },
];

describe('response validation at the boundary (ADR-045)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // AC-4: one rejection case per parsed boundary.
  it.each(boundaries)(
    '$helper rejects a payload whose enum value is out of contract',
    async ({ call, corruptBody, path, badValue }) => {
      stubOkJson(corruptBody);

      await expect(call()).rejects.toThrow(ContractViolationError);

      stubOkJson(corruptBody);
      try {
        await call();
        expect.unreachable('the out-of-contract payload was admitted');
      } catch (e) {
        const err = e as ContractViolationError;
        expect(err).toBeInstanceOf(ContractViolationError);
        // Subclassing ApiError is load-bearing: every component catches that.
        expect(err).toBeInstanceOf(ApiError);
        expect(err.status).toBe(502);
        expect(err.message).toContain(`${path}: expected one of `);
        // The offending value must never be laundered into rendered output.
        expect(err.message).not.toContain(badValue);
      }
    },
  );

  it.each(boundaries)('$helper admits a contract-complete payload unchanged', async ({ call, validBody }) => {
    stubOkJson(validBody);

    await expect(call()).resolves.toEqual(validBody);
  });

  it('names the DTO, the field and the permitted members, and nothing else', async () => {
    stubOkJson({ ...session, status: 'WHATEVER' });

    await expect(getSession('s-1', 'token')).rejects.toThrow(
      'SessionStateDto.status: expected one of LOBBY, IN_PROGRESS, COMPLETED, ABANDONED',
    );
  });

  it('drops a field the server sends but no parser reads', async () => {
    // Documents the hazard ADR-045 records as review-enforced: parsers reconstruct,
    // so an unread field is silently absent rather than passed through.
    stubOkJson({ ...session, serverOnlyExtra: 'ignored' });

    const parsed = await getSession('s-1', 'token');

    expect(parsed).toEqual(session);
    expect(parsed).not.toHaveProperty('serverOnlyExtra');
  });

  it('rejects a body that is not a JSON object', async () => {
    stubOkJson([session]);

    await expect(getSession('s-1', 'token')).rejects.toThrow('SessionStateDto: expected a JSON object');
  });

  it('rejects a missing required string', async () => {
    const withoutJoinCode: Record<string, unknown> = { ...session };
    delete withoutJoinCode['joinCode'];
    stubOkJson(withoutJoinCode);

    await expect(getSession('s-1', 'token')).rejects.toThrow('SessionStateDto.joinCode: expected a string');
  });

  it('rejects a required number sent as a numeric string', async () => {
    stubOkJson({ ...hand, cardCount: '1' });

    await expect(fetchHand('s-1', 'token')).rejects.toThrow('HandDto.cardCount: expected a finite number');
  });

  it('rejects NaN, which is a number but not a finite one', async () => {
    // JSON cannot carry NaN, but a hand-rolled body or a proxy can.
    stubOkJson({ ...hand, cardCount: Number.NaN });

    await expect(fetchHand('s-1', 'token')).rejects.toThrow('HandDto.cardCount: expected a finite number');
  });

  it('rejects a required array sent as an object', async () => {
    stubOkJson({ ...session, players: { 0: player } });

    await expect(getSession('s-1', 'token')).rejects.toThrow('SessionStateDto.players: expected an array');
  });

  it('rejects a non-string element inside a string array, naming its index', async () => {
    const corrupt = withCorruptEnum(trick, (c) => {
      const play = (c['plays'] as Record<string, unknown>[])[0]!;
      play['components'] = ['auth-service', 42];
    });
    stubOkJson(corrupt);

    await expect(resolveTrick('s-1', 'token')).rejects.toThrow(
      'TrickDto.plays[0].components[1]: expected a string',
    );
  });

  it('rejects a non-numeric value inside the capturedBySuit record', async () => {
    const corrupt = withCorruptEnum(leaderboard, (c) => {
      const row = (c['rows'] as Record<string, unknown>[])[0]!;
      row['capturedBySuit'] = { SPOOFING: 'two' };
    });
    stubOkJson(corrupt);

    await expect(getLeaderboard('s-1', 'token')).rejects.toThrow(
      'LeaderboardDto.rows[0].capturedBySuit: expected every value to be a finite number',
    );
  });

  /**
   * The keys of `capturedBySuit` are chosen by the server, so they are payload.
   * Naming the offending key would reflect an attacker-influenced string into
   * the GOV.UK error summary, which `.opencode/rules/error-handling.md` forbids.
   * The distinctive key below would be unmistakable in the message if the
   * parser ever regressed to interpolating it.
   */
  it('never reflects a capturedBySuit key into the violation message', async () => {
    const corrupt = withCorruptEnum(leaderboard, (c) => {
      const row = (c['rows'] as Record<string, unknown>[])[0]!;
      row['capturedBySuit'] = { '<img src=x onerror=alert(1)>': 'two' };
    });
    stubOkJson(corrupt);

    let thrown: unknown;
    try {
      await getLeaderboard('s-1', 'token');
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(ContractViolationError);
    expect((thrown as ContractViolationError).message).toBe(
      'LeaderboardDto.rows[0].capturedBySuit: expected every value to be a finite number',
    );
    expect((thrown as ContractViolationError).message).not.toContain('img');
    expect((thrown as ContractViolationError).message).not.toContain('onerror');
  });

  /**
   * `requireNumberRecord` builds its result with `Object.create(null)`, so a
   * `__proto__` key cannot shadow an `Object.prototype` member and leave the
   * record non-coercible for a future consumer. A number-valued `__proto__`
   * would be dropped silently by a plain `{}`; here it is an own property of a
   * prototype-less object, and the global prototype is untouched either way.
   */
  it('does not let a capturedBySuit key pollute Object.prototype', async () => {
    const corrupt = withCorruptEnum(leaderboard, (c) => {
      const row = (c['rows'] as Record<string, unknown>[])[0]!;
      row['capturedBySuit'] = JSON.parse('{"__proto__":5,"toString":6,"SPOOFING":1}') as Record<string, number>;
    });
    stubOkJson(corrupt);

    const result = await getLeaderboard('s-1', 'token');

    expect(result.rows[0]!.capturedBySuit['SPOOFING']).toBe(1);
    expect(({} as Record<string, unknown>)['polluted']).toBeUndefined();
    expect(Object.getPrototypeOf(result.rows[0]!.capturedBySuit)).toBeNull();
  });

  it('accepts an optional field that is absent, and one sent as explicit null', async () => {
    stubOkJson({ complete: true, handComplete: false });
    await expect(getTrickState('s-1', 'token')).resolves.toEqual({ complete: true, handComplete: false });

    stubOkJson({ complete: true, handComplete: false, trick: null, seatToPlay: null, nextLeaderSeat: null });
    const parsed = await getTrickState('s-1', 'token');

    expect(parsed).toEqual({ complete: true, handComplete: false });
    expect(parsed).not.toHaveProperty('trick');
    expect(parsed).not.toHaveProperty('seatToPlay');
  });

  it('still rejects an optional field that is present but of the wrong type', async () => {
    stubOkJson({ complete: true, handComplete: false, seatToPlay: 'two' });

    await expect(getTrickState('s-1', 'token')).rejects.toThrow('TrickStateDto.seatToPlay: expected a finite number');
  });

  /**
   * The `exactOptionalPropertyTypes` conditional-spread idiom has two arms, and
   * the fixtures above always populate the optional fields, so only the present
   * arm was exercised. Without these three cases a mutation replacing
   * `...(x === undefined ? {} : { x })` with a plain `{ x }` survives the whole
   * suite: the property would be present-and-`undefined` rather than absent, and
   * `toEqual` treats those alike. `not.toHaveProperty` is the assertion that
   * distinguishes them, so it is the one that kills the mutant.
   */
  it('omits an absent optional notes rather than setting it undefined', async () => {
    const play: Record<string, unknown> = { ...trickPlay };
    delete play['notes'];
    stubOkJson({ ...trick, plays: [play] });

    const parsed = await playCard('s-1', 'token', { cardId: 'c-1', threatLinked: false, components: [] });

    expect(parsed.plays[0]).not.toHaveProperty('notes');
    expect(Object.hasOwn(parsed.plays[0]!, 'notes')).toBe(false);
  });

  it('omits an absent optional ledSuit and winningSeat rather than setting them undefined', async () => {
    const bare: Record<string, unknown> = { ...trick };
    delete bare['ledSuit'];
    delete bare['winningSeat'];
    stubOkJson(bare);

    const parsed = await resolveTrick('s-1', 'token');

    expect(Object.hasOwn(parsed, 'ledSuit')).toBe(false);
    expect(Object.hasOwn(parsed, 'winningSeat')).toBe(false);
  });

  it('holds a supplied ledSuit to StrideCategory membership while still allowing absence', async () => {
    // EOP-109. `optionalEnum` concedes absence and nothing else, so the two halves are
    // asserted together: an explicit null is admitted as "not present", and a value
    // that is present but out of contract is rejected naming the full member list.
    const absent: Record<string, unknown> = { ...trick, ledSuit: null };
    stubOkJson(absent);

    const parsed = await resolveTrick('s-1', 'token');
    expect(Object.hasOwn(parsed, 'ledSuit')).toBe(false);

    stubOkJson({ ...trick, ledSuit: 'spoofing' });

    await expect(resolveTrick('s-1', 'token')).rejects.toThrow(
      'TrickDto.ledSuit: expected one of SPOOFING, TAMPERING, REPUDIATION, '
        + 'INFORMATION_DISCLOSURE, DENIAL_OF_SERVICE, ELEVATION_OF_PRIVILEGE',
    );
  });

  it('omits absent optional trick, seatToPlay and nextLeaderSeat rather than setting them undefined', async () => {
    stubOkJson({ complete: false, handComplete: false });

    const parsed = await getTrickState('s-1', 'token');

    expect(Object.hasOwn(parsed, 'trick')).toBe(false);
    expect(Object.hasOwn(parsed, 'seatToPlay')).toBe(false);
    expect(Object.hasOwn(parsed, 'nextLeaderSeat')).toBe(false);
  });

  it('rejects a null response body, not only a non-object one', async () => {
    stubOkJson(null);

    await expect(getSession('s-1', 'token')).rejects.toThrow('SessionStateDto: expected a JSON object');
  });

  it('rejects a capturedBySuit that is null or an array rather than an object', async () => {
    const withNull = withCorruptEnum(leaderboard, (c) => {
      (c['rows'] as Record<string, unknown>[])[0]!['capturedBySuit'] = null;
    });
    stubOkJson(withNull);
    await expect(getLeaderboard('s-1', 'token')).rejects.toThrow(
      'LeaderboardDto.rows[0].capturedBySuit: expected a JSON object',
    );

    const withArray = withCorruptEnum(leaderboard, (c) => {
      (c['rows'] as Record<string, unknown>[])[0]!['capturedBySuit'] = [1, 2];
    });
    stubOkJson(withArray);
    await expect(getLeaderboard('s-1', 'token')).rejects.toThrow(
      'LeaderboardDto.rows[0].capturedBySuit: expected a JSON object',
    );
  });

  it('rejects Infinity as well as NaN for a required number', async () => {
    stubOkJson({ ...session, players: [{ ...player, seatOrder: Number.POSITIVE_INFINITY }] });

    await expect(getSession('s-1', 'token')).rejects.toThrow(
      'SessionStateDto.players[0].seatOrder: expected a finite number',
    );
  });

  it('rejects a required boolean sent as a string', async () => {
    stubOkJson({ ...trickState, complete: 'false' });

    await expect(getTrickState('s-1', 'token')).rejects.toThrow('TrickStateDto.complete: expected a boolean');
  });

  it('leaves a transport error as a plain ApiError, not a contract violation', async () => {
    // A 409 is the server rejecting the request, not a broken contract; the parse
    // must not run at all, and the useful status must survive.
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 409,
        statusText: 'Conflict',
        json: () => Promise.resolve({ detail: 'Game already started.' }),
        headers: new Headers(),
      } as unknown as Response)
    ));

    try {
      await startGame('s-1', 'token');
      expect.unreachable('expected startGame to reject');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      expect(e).not.toBeInstanceOf(ContractViolationError);
      expect((e as ApiError).status).toBe(409);
      expect((e as ApiError).message).toBe('Game already started.');
    }
  });

  it('degrades a non-string problem detail to the status text instead of rendering an object', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: () => Promise.resolve({ detail: { nested: 'not a string' } }),
        headers: new Headers(),
      } as unknown as Response)
    ));

    try {
      await getSession('s-1', 'token');
      expect.unreachable('expected getSession to reject');
    } catch (e) {
      expect((e as ApiError).message).toBe('Bad Request');
      expect((e as ApiError).status).toBe(400);
    }
  });
});
