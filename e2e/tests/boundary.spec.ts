import { expect, test } from '@playwright/test';
import {
    MAXIMUM_PLAYERS,
    MINIMUM_PLAYERS_TO_START,
    closeSeats,
    createSession,
    expectJoinRefused,
    expectPlayerCount,
    handCards,
    joinSession,
    lobbyPlayerNames,
    openSeat,
    startGame,
    type Seat,
} from '../game';

/**
 * Boundary conditions and error paths, end to end, in three real browser engines (EOP-218).
 *
 * Where the happy path (EOP-217) shows the application working, these scenarios show it
 * refusing. Every assertion is made against rendered output through the `compose.e2e.yml`
 * stack: nothing is stubbed and no request is intercepted, so a refusal asserted here is a
 * refusal a user would actually see.
 *
 * **Four of the six scenarios the ticket originally specified asserted behaviour this
 * application does not have.** They were rewritten against the source before any test was
 * written, and the rewrite is recorded on the story. Restoring the earlier wording would
 * reintroduce tests that fail against correct code:
 *
 *  - **The table holds six, so the *seventh* join is refused** — not the fourth.
 *    `GameSession.MAXIMUM_PLAYERS` is 6.
 *  - **Duplicate display names are admitted, not rejected.** No uniqueness check exists
 *    anywhere in the domain or the use cases. `EOP-230` decided to keep it that way, so
 *    Scenario 2 pins a deliberate decision rather than merely observed behaviour — see the
 *    2026-09-08 amendment to ADR-015, which weighs rejecting a collision and disambiguating
 *    for display only, and rejects both.
 *  - **A player who closes their tab mid-game is locked out permanently.** The token is the
 *    entire identity (ADR-015) and it lives in tab-scoped `sessionStorage`, so re-joining is
 *    the only way back and an `IN_PROGRESS` session refuses new players. `EOP-231` decides
 *    whether that should change.
 *  - **Too few players cannot be tested through the UI at all.** `LobbyScreen.tsx:37`
 *    disables the start button below three seats, so the server's 409 is unreachable from a
 *    browser. Scenario 6 asserts the control's state, which is the boundary a user meets;
 *    the 409 belongs to `StartSessionUseCaseTest`. `EOP-232` covers that duplicated literal.
 *
 * A fifth scenario the ticket asked for — the `VITE_GAME_SCREEN_ENABLED` fallback — is
 * deliberately absent. The flag is substituted at build time (ADR-037), so exercising it
 * here would mean a second UI image and a second stack per run to assert what
 * `ui/src/App.test.tsx:190` already asserts in milliseconds.
 *
 * **What this suite may not be cited for.** Both rate limiters run at `Integer.MAX_VALUE` in
 * this stack, because Caddy forwards one client address for every browser and the whole
 * suite would otherwise share a single bucket. Throttling evidence comes from
 * `SessionControllerIntegrationTest` and `ReadRateLimitIntegrationTest`, never from here.
 * Certificate verification is off, so this is not evidence that TLS is configured correctly
 * either.
 */

/*
 * The exact `detail` strings from the server's problem documents, quoted from
 * `GlobalExceptionHandler`. The front end renders `detail` verbatim — `api.ts`'s
 * `problemMessage` prefers it over `title` — so these are what a user reads, and asserting
 * the exact text is what catches a handler being rewired to a different exception.
 */
const SESSION_FULL = 'This session has no available seats. Try a different join code.';
const NOT_IN_LOBBY = 'This session is no longer in the lobby.';
const NO_SUCH_SESSION = 'No session matches that join code.';

/*
 * A well-formed code that matches no session: eight characters, every one in
 * `JoinCode.ALPHABET` (Crockford base32).
 */
const UNKNOWN_CODE = 'ZZZZZZZZ';

/*
 * A code that cannot be parsed at all. `U` is excluded from the alphabet and, unlike `O`,
 * `I` and `L`, is deliberately not folded onto a legal character — so this is refused for
 * being malformed rather than reinterpreted.
 */
const MALFORMED_CODE = 'UUUUUUUU';

test.describe('Boundary: joining a session', () => {
    test('refuses the seventh player and leaves the full table undisturbed', async ({ browser }) => {
        /*
         * Seven browser contexts and six sequential joins do not fit the default per-test
         * budget.
         */
        test.setTimeout(180_000);

        const names = ['Bob', 'Carol', 'Dan', 'Erin', 'Frank'];
        const facilitator = await openSeat(browser, 'Alice');
        const others: Seat[] = [];
        let latecomer: Seat | undefined;

        try {
            for (const name of names) {
                others.push(await openSeat(browser, name));
            }
            const seated = [facilitator, ...others];
            const joinCode = await createSession(facilitator);

            /*
             * Sequentially, one seat at a time. `JoinSessionUseCase` reads a fullness
             * snapshot at the top of each attempt and retries a lost seat race only
             * `MAXIMUM_PLAYERS + 2` times, so six simultaneous joins can exhaust that budget
             * and surface `SeatAlreadyTakenException` instead of the refusal under test.
             * Joining in turn keeps the contended path out of this scenario.
             */
            for (const seat of others) {
                await joinSession(seat, joinCode);
            }
            await expectPlayerCount(seated, MAXIMUM_PLAYERS);

            latecomer = await openSeat(browser, 'Grace');
            const message = await expectJoinRefused(latecomer, joinCode);
            expect(message, 'the seventh player was not told the session is full').toBe(SESSION_FULL);

            /*
             * The refusal must also be inert. A seventh seat that was created and then
             * rolled back would still show the message above, so the table is re-counted
             * afterwards to prove nothing was admitted.
             */
            await expectPlayerCount(seated, MAXIMUM_PLAYERS);
            const finalNames = await lobbyPlayerNames(facilitator.page);
            expect(finalNames, 'the refused player appears in the lobby').not.toContain('Grace');
        } finally {
            const opened = [facilitator, ...others];
            await closeSeats(latecomer === undefined ? opened : [...opened, latecomer]);
        }
    });

    test('admits a second player using a name already taken', async ({ browser }) => {
        /*
         * Written to fail loudly if uniqueness is ever introduced without revisiting it:
         * the assertion is that the duplicate *is* seated, so adding a rule breaks this test
         * rather than leaving a stale one passing. `EOP-230` decided to admit the duplicate,
         * recorded in the 2026-09-08 amendment to ADR-015, so inverting this assertion means
         * amending that ADR first.
         */
        const facilitator = await openSeat(browser, 'Alice');
        const impostor = await openSeat(browser, 'Alice');

        try {
            const joinCode = await createSession(facilitator);
            await joinSession(impostor, joinCode);
            await expectPlayerCount([facilitator, impostor], 2);

            const names = await lobbyPlayerNames(facilitator.page);
            expect(
                names.filter(name => name === 'Alice'),
                `the lobby does not list Alice twice: ${JSON.stringify(names)}`,
            ).toHaveLength(2);
        } finally {
            await closeSeats([facilitator, impostor]);
        }
    });

    test('answers an unknown code and a malformed code identically', async ({ browser }) => {
        /*
         * The equality assertion is the point of this scenario, not the individual messages.
         * `JoinCode.parse` returns an empty optional rather than throwing so that "that is
         * not a code" and "no session has that code" are indistinguishable, and
         * `JoinSessionUseCase.execute` honours it by throwing the same no-argument
         * `UnknownJoinCodeException` on both paths. Anything that told the two apart would
         * turn the join endpoint into an oracle confirming which codes are real — exactly
         * the help an attacker enumerating a forty-bit keyspace wants (ADR-019).
         */
        const stranger = await openSeat(browser, 'Heidi');

        try {
            const unknown = await expectJoinRefused(stranger, UNKNOWN_CODE);
            expect(unknown, 'a well-formed unknown code was not reported as no such session').toBe(
                NO_SUCH_SESSION,
            );

            await stranger.page.goto('/');
            const malformed = await expectJoinRefused(stranger, MALFORMED_CODE);
            expect(
                malformed,
                'a malformed code is distinguishable from an unknown one, which makes the join endpoint an enumeration oracle',
            ).toBe(unknown);
        } finally {
            await closeSeats([stranger]);
        }
    });
});

test.describe('Boundary: losing a seat', () => {
    test('locks out a player who closes the tab once the game is under way', async ({ browser }) => {
        test.setTimeout(120_000);

        const facilitator = await openSeat(browser, 'Alice');
        const leaver = await openSeat(browser, 'Bob');
        const stayer = await openSeat(browser, 'Carol');
        let returning: Seat | undefined;

        try {
            const joinCode = await createSession(facilitator);
            await joinSession(leaver, joinCode);
            await joinSession(stayer, joinCode);
            await expectPlayerCount([facilitator, leaver, stayer], MINIMUM_PLAYERS_TO_START);
            await startGame(facilitator, [facilitator, leaver, stayer]);

            /*
             * A participant leaves rather than the facilitator, so that the refusal cannot be
             * confused with anything role-specific.
             */
            await leaver.context.close();

            returning = await openSeat(browser, 'Bob');
            const message = await expectJoinRefused(returning, joinCode);
            expect(message, 'a player returning mid-game was not told the lobby has closed').toBe(
                NOT_IN_LOBBY,
            );

            for (const seat of [facilitator, stayer]) {
                await expect(
                    handCards(seat.page).first(),
                    `${seat.displayName} lost their hand when another player left`,
                ).toBeVisible();
            }
        } finally {
            /*
             * `leaver` is closed again here on purpose. Closing a context twice is a no-op, and
             * listing it means a failure before the deliberate close still cleans it up rather
             * than leaning on Playwright's process teardown.
             */
            await closeSeats(
                returning === undefined
                    ? [facilitator, leaver, stayer]
                    : [facilitator, leaver, stayer, returning],
            );
        }
    });

    test('gives a returning player a new seat rather than their old one', async ({ browser }) => {
        /*
         * The same loss of `sessionStorage`, but while the session is still in the lobby, so
         * the join is accepted. It does not restore the original seat: `nextSeatOrder`
         * returns the current player count, and nothing maps a display name back to a seat,
         * because the token is the only identity (ADR-015) and seat order is assigned once
         * and never recomputed (ADR-019). The observable result is a third player in a
         * two-person session and a ghost seat nobody holds a token for.
         *
         * This is pinned as observed behaviour, not endorsed. `EOP-231` decides whether it
         * should change and must update this scenario in the same change.
         */
        const facilitator = await openSeat(browser, 'Alice');
        const leaver = await openSeat(browser, 'Bob');
        let returning: Seat | undefined;

        try {
            const joinCode = await createSession(facilitator);
            await joinSession(leaver, joinCode);
            await expectPlayerCount([facilitator, leaver], 2);

            await leaver.context.close();

            returning = await openSeat(browser, 'Bob');
            await joinSession(returning, joinCode);

            await expectPlayerCount([facilitator, returning], 3);
            const names = await lobbyPlayerNames(facilitator.page);
            expect(
                names.filter(name => name === 'Bob'),
                `Bob was restored to his seat instead of taking a new one: ${JSON.stringify(names)}`,
            ).toHaveLength(2);
        } finally {
            await closeSeats(returning === undefined ? [facilitator, leaver] : [facilitator, leaver, returning]);
        }
    });
});

test.describe('Boundary: starting a game', () => {
    test('enables the start control only once the minimum is seated', async ({ browser }) => {
        test.setTimeout(120_000);

        const facilitator = await openSeat(browser, 'Alice');
        const second = await openSeat(browser, 'Bob');
        const third = await openSeat(browser, 'Carol');

        try {
            const joinCode = await createSession(facilitator);
            await joinSession(second, joinCode);
            await expectPlayerCount([facilitator, second], 2);

            /*
             * The server's own `TooFewPlayersException` is unreachable from a browser because
             * of this disabled attribute, so the attribute *is* the boundary a user meets.
             * Asserting it here and the 409 in `StartSessionUseCaseTest` covers both sides of
             * a rule the two layers state separately (`EOP-232`).
             */
            const start = facilitator.page.getByRole('button', { name: 'Start game' });
            await expect(
                start,
                `Start game is enabled with only 2 of ${MINIMUM_PLAYERS_TO_START} players seated`,
            ).toBeDisabled();

            await joinSession(third, joinCode);
            await expectPlayerCount([facilitator, second, third], MINIMUM_PLAYERS_TO_START);
            await expect(
                start,
                'Start game is still disabled at the minimum player count',
            ).toBeEnabled();

            await startGame(facilitator, [facilitator, second, third]);
        } finally {
            await closeSeats([facilitator, second, third]);
        }
    });
});
