import { expect, test, type Page } from '@playwright/test';
import {
    DECK_SIZE,
    MINIMUM_PLAYERS_TO_START,
    closeSeats,
    createSession,
    expectGameOver,
    expectPlayerCount,
    expectedHandSizes,
    handCards,
    joinSession,
    openSeat,
    playOutTheDeck,
    startGame,
    type Seat,
} from '../game';

/**
 * The end-of-game summary screen, under every data condition it can reach (EOP-219).
 *
 * These scenarios are the stakeholder-facing evidence that the final leaderboard
 * is correct: that it agrees with what the server sent, that it is scoped to the
 * session it belongs to, that its ranking and tie presentation are internally
 * consistent, and that a facilitator can start a second game from it.
 *
 * **One deck per engine.** A complete game is 68 plays (`DECK_SIZE`) and there is
 * no shorter route to the game-over screen through the UI, so the whole block
 * shares a single completed game set up in `beforeAll` and asserted from four
 * angles. Playing a deck per scenario would quadruple a suite that already takes
 * minutes per engine and would prove nothing extra. `describe.serial` is what
 * makes that safe: the scenarios run in declaration order, and the one that
 * destroys the shared fixture by starting a second game is declared last.
 *
 * Four things the ticket assumed are not true of the application. Each is
 * recorded on the story and in ADR-068, and the scenarios below are written
 * against what the code actually does:
 *
 *  - **Three players, not two.** The ticket asks for a "full 2-player game";
 *    `GameSession.MINIMUM_PLAYERS_TO_START` is 3, so a two-player session can be
 *    formed but never started and never reaches a leaderboard at all.
 *  - **There is no historical or cross-session leaderboard.** The ticket asks for
 *    a board that mixes "the current game's players AND historical rows from the
 *    seeded prior game". `GET /api/v1/sessions/{sessionId}/leaderboard` is scoped
 *    to one session, `GetLeaderboardUseCase` refuses any status but `COMPLETED`,
 *    and `GameResultJpaRepository` keeps one row per session so that the board
 *    always reflects that session's latest completed game. Seeding prior results
 *    could not change what is rendered, so the second scenario asserts the
 *    property that actually matters — isolation — rather than a mixture the
 *    application cannot produce.
 *  - **A new game returns to `IN_PROGRESS`, not to the lobby.** The ticket asks
 *    for a transition "back to LOBBY" with "all players returned to the lobby
 *    screen". `NewGameUseCase` clears tricks and hands, calls
 *    `resetToInProgress`, and immediately deals a fresh deck to the same players
 *    in the same seats; `SessionStatus.LOBBY` is never re-entered by any code
 *    path. The fourth scenario asserts the game screen and a full fresh hand.
 *  - **The flag-off scenario is dropped**, on the precedent EOP-218 set.
 *    `VITE_GAME_SCREEN_ENABLED` is a Vite build-time variable baked in at
 *    `ui/Dockerfile:40` (ADR-037), so covering it here would mean building a
 *    second image and running a second stack for every run, to prove something
 *    `ui/src/App.test.tsx:190` already asserts in milliseconds. The back-end half
 *    of the same question — `eop.features.game-over` off, so the route is absent
 *    entirely — is owned by `GameOverControllerDisabledIntegrationTest`.
 *
 * The ticket's fourth criterion also turned out to be half true of the
 * application in a way nobody had noticed. Starting a second game moves the
 * *facilitator* on, and strands every other player on the leaderboard of a game
 * that no longer exists, because `GameOverScreen` opens no session subscription.
 * That is a real defect, filed as EOP-233 and not fixed here — this story ships
 * no production code. The fourth scenario therefore pins the behaviour as it is,
 * including the page reload that is currently a participant's only way out, and
 * says so at the assertions a fix will have to invert.
 *
 * One deliberate omission of scope. Everything asserted here is browser-visible.
 * The API's own refusals — a 409 for a session that is not `COMPLETED`, a 403
 * without a credential, the 404 that distinguishes an unknown session from a
 * completed one with no recorded result — are owned by
 * `GameOverControllerIntegrationTest` and `GlobalExceptionHandlerTest:1017`,
 * which reach them far more cheaply and more exhaustively than a browser can.
 */

/** The STRIDE columns the screen renders, in the canonical order it renders them. */
const STRIDE_COLUMNS: readonly { readonly key: string; readonly label: string }[] = [
    { key: 'SPOOFING', label: 'Spoofing' },
    { key: 'TAMPERING', label: 'Tampering' },
    { key: 'REPUDIATION', label: 'Repudiation' },
    { key: 'INFORMATION_DISCLOSURE', label: 'Info. Disclosure' },
    { key: 'DENIAL_OF_SERVICE', label: 'Denial of Service' },
    { key: 'ELEVATION_OF_PRIVILEGE', label: 'Elevation of Privilege' },
];

/** A row of `LeaderboardDto`, narrowed to the fields the screen renders. */
interface LeaderboardRow {
    readonly displayName: string;
    readonly points: number;
    readonly position: number;
    readonly tied: boolean;
    readonly capturedBySuit: Readonly<Record<string, number>>;
}

/** `LeaderboardDto` as the server sends it. */
interface Leaderboard {
    readonly rows: readonly LeaderboardRow[];
    readonly sessionStatus: string;
}

/**
 * Narrows a captured response body to {@link Leaderboard}.
 *
 * Structural only — presence and `typeof` of the fields asserted against, in the
 * spirit of the front end's own boundary parsers (ADR-045). A body that does not
 * match is a failure of the API rather than of the browser, and saying so here
 * turns what would otherwise surface as an unreadable `undefined` comparison
 * deep in an assertion into one legible message.
 *
 * @param value the parsed JSON body
 * @return the same value, typed
 */
function asLeaderboard(value: unknown): Leaderboard {
    if (typeof value !== 'object' || value === null) {
        throw new Error('leaderboard response body is not an object');
    }
    const candidate = value as { rows?: unknown; sessionStatus?: unknown };
    if (typeof candidate.sessionStatus !== 'string') {
        throw new Error('LeaderboardDto.sessionStatus: expected a string');
    }
    if (!Array.isArray(candidate.rows)) {
        throw new Error('LeaderboardDto.rows: expected an array');
    }
    for (const row of candidate.rows) {
        if (typeof row !== 'object' || row === null) {
            throw new Error('LeaderboardRowDto: expected an object');
        }
        const fields = row as Record<string, unknown>;
        if (typeof fields['displayName'] !== 'string') {
            throw new Error('LeaderboardRowDto.displayName: expected a string');
        }
        for (const numeric of ['points', 'position']) {
            if (typeof fields[numeric] !== 'number') {
                throw new Error(`LeaderboardRowDto.${numeric}: expected a number`);
            }
        }
        if (typeof fields['tied'] !== 'boolean') {
            throw new Error('LeaderboardRowDto.tied: expected a boolean');
        }
        if (typeof fields['capturedBySuit'] !== 'object' || fields['capturedBySuit'] === null) {
            throw new Error('LeaderboardRowDto.capturedBySuit: expected an object');
        }
    }
    return candidate as unknown as Leaderboard;
}

/**
 * Records every leaderboard body the page itself receives.
 *
 * The point of reading the app's own response rather than issuing a request of
 * our own is that it needs no credential: `GET /leaderboard` is authenticated by
 * a player-token header, and a test that re-sent it by hand would be asserting
 * against a second request rather than against the one the screen was drawn
 * from. Capturing the real exchange gives a stronger property for less code —
 * the rendered cells are compared with the exact bytes that produced them, and
 * `sessionStatus`, which the screen does not render at all, becomes observable.
 *
 * Must be installed before the screen mounts, so it is installed before the deck
 * is played out. Bodies are collected as promises and awaited on read, so a body
 * still in flight cannot be mistaken for an absent one.
 *
 * @param page the page to observe
 * @return a reader for the most recent successful leaderboard body
 */
function captureLeaderboards(page: Page): () => Promise<Leaderboard> {
    const bodies: Promise<Leaderboard>[] = [];
    page.on('response', response => {
        if (response.request().method() !== 'GET' || !response.url().includes('/leaderboard') || !response.ok()) {
            return;
        }
        bodies.push(response.json().then(asLeaderboard));
    });
    return async () => {
        const settled = await Promise.all(bodies);
        const latest = settled.at(-1);
        if (latest === undefined) {
            throw new Error('no successful leaderboard response was captured — did the screen ever load?');
        }
        return latest;
    };
}

/**
 * The ordinal the screen shows for a position.
 *
 * A deliberate third copy of `GameOverScreen.tsx`'s own `ordinalSuffix`: reading
 * the label out of the DOM and then asserting it against itself would pass for
 * any label whatsoever, so the expectation has to be computed independently from
 * the numeric `position` the API sent.
 *
 * @param position the competition-ranking position, 1-based
 * @return the position as an English ordinal
 */
function ordinal(position: number): string {
    const lastTwo = position % 100;
    if (lastTwo >= 11 && lastTwo <= 13) {
        return `${position}th`;
    }
    switch (position % 10) {
        case 1:
            return `${position}st`;
        case 2:
            return `${position}nd`;
        case 3:
            return `${position}rd`;
        default:
            return `${position}th`;
    }
}

/** The label the screen renders in a row's position cell. */
function positionLabel(row: LeaderboardRow): string {
    return `${ordinal(row.position)}${row.tied ? ' (tied)' : ''}`;
}

test.describe.serial('Leaderboard: the end-of-game summary screen', () => {
    let seats: readonly Seat[] = [];
    let facilitator: Seat | undefined;
    let readLeaderboard: (() => Promise<Leaderboard>) | undefined;
    let otherProjects: readonly string[] = [];

    test.beforeAll(async ({ browser }, testInfo) => {
        /*
         * The whole 68-play game happens here, once, so the budget that scenario
         * 1 of the happy path needs for itself is needed by this hook instead.
         * Raised on the hook rather than on the block so that each scenario keeps
         * the config's tighter per-test budget, where a hang should fail fast.
         */
        test.setTimeout(15 * 60 * 1000);

        const suffix = testInfo.project.name;
        otherProjects = testInfo.config.projects.map(project => project.name).filter(name => name !== suffix);

        const host = await openSeat(browser, `Dana-${suffix}`);
        const second = await openSeat(browser, `Erin-${suffix}`);
        const third = await openSeat(browser, `Femi-${suffix}`);
        facilitator = host;
        seats = [host, second, third];

        const joinCode = await createSession(host);
        await joinSession(second, joinCode);
        await joinSession(third, joinCode);
        await expectPlayerCount(seats, MINIMUM_PLAYERS_TO_START);

        await startGame(host, seats);

        // Installed before the game can complete: the screen requests the
        // leaderboard as it mounts, and there is no control to make it ask again
        // once it has succeeded.
        readLeaderboard = captureLeaderboards(host.page);

        await playOutTheDeck(seats, DECK_SIZE);
        for (const seat of seats) {
            await expectGameOver(seat);
        }
    });

    test.afterAll(async () => {
        await closeSeats(seats);
    });

    test('renders every column of the final leaderboard exactly as the server sent it', async () => {
        const host = facilitator;
        const read = readLeaderboard;
        expect(host, 'the shared fixture did not produce a facilitator').toBeDefined();
        expect(read, 'no leaderboard reader was installed').toBeDefined();
        if (host === undefined || read === undefined) {
            return;
        }
        const leaderboard = await read();

        // Assert: the session really is finished. This is the one field of
        // `LeaderboardDto` the screen never renders, so it is only assertable
        // against the captured body — which is the reason for capturing one.
        expect(
            leaderboard.sessionStatus,
            'the leaderboard was served for a session that is not COMPLETED',
        ).toBe('COMPLETED');

        await expect(
            host.page.getByRole('heading', { level: 1, name: 'Game over' }),
            'the game-over screen is not headed "Game over"',
        ).toBeVisible();
        await expect(
            host.page.getByRole('heading', { level: 2, name: 'Final leaderboard' }),
            'the leaderboard section is not headed "Final leaderboard"',
        ).toBeVisible();

        const table = host.page.getByRole('table', { name: 'Final leaderboard' });
        await expect(table, 'the final leaderboard table is not rendered').toBeVisible();

        // Assert: the header names every score component the stakeholder is being
        // shown, in the canonical STRIDE order.
        const headers = table.getByRole('columnheader');
        await expect(headers, 'the leaderboard does not carry a header per column').toHaveText([
            'Position',
            'Player',
            'Total',
            ...STRIDE_COLUMNS.map(column => column.label),
        ]);

        // Assert: one row per seated player, and no more.
        const rows = table.getByRole('row');
        expect(
            leaderboard.rows.length,
            'the server did not return one leaderboard row per seated player',
        ).toBe(seats.length);
        await expect(rows, 'the table does not render a header row plus one row per player').toHaveCount(
            seats.length + 1,
        );

        // Assert: every cell of every row matches the body it was drawn from.
        // Computed from the API's numbers rather than read back out of the DOM,
        // so a screen that renders a plausible-looking wrong number fails.
        for (const row of leaderboard.rows) {
            const rendered = rows.filter({ hasText: row.displayName });
            await expect(
                rendered,
                `the leaderboard does not render exactly one row for ${row.displayName}`,
            ).toHaveCount(1);
            await expect(
                rendered.getByRole('cell'),
                `${row.displayName}'s row does not match the server's row, cell for cell`,
            ).toHaveText([
                positionLabel(row),
                row.displayName,
                String(row.points),
                ...STRIDE_COLUMNS.map(column => String(row.capturedBySuit[column.key] ?? 0)),
            ]);

            // The position cell also carries an accessible name, which is what a
            // screen-reader user is told and is not covered by the text above.
            await expect(
                rendered.getByLabel(`${positionLabel(row)} place`),
                `${row.displayName}'s position is not announced as "${positionLabel(row)} place"`,
            ).toBeVisible();
        }

        // Assert: the totals are the game, not an accumulation across games. Every
        // play scores its taker at most a threat point plus a trick point, so the
        // board can never total more than twice the deck.
        const total = leaderboard.rows.reduce((running, row) => running + row.points, 0);
        expect(total, 'the leaderboard totals more points than the deck can award').toBeLessThanOrEqual(
            DECK_SIZE * 2,
        );
        expect(total, 'the leaderboard awarded no points at all for a completed game').toBeGreaterThan(0);
    });

    test('shows only this session, never another session played into the same database', async () => {
        const host = facilitator;
        const read = readLeaderboard;
        expect(host, 'the shared fixture did not produce a facilitator').toBeDefined();
        expect(read, 'no leaderboard reader was installed').toBeDefined();
        if (host === undefined || read === undefined) {
            return;
        }
        const leaderboard = await read();
        const rendered = new Set(leaderboard.rows.map(row => row.displayName));

        // Assert: exactly our three seats, by name. This is the substance of the
        // isolation claim — the endpoint is scoped to one session id, so a board
        // that grew a fourth row would mean the query had escaped its session.
        expect(
            [...rendered].sort(),
            'the leaderboard does not name exactly the seats of this session',
        ).toEqual([...seats.map(seat => seat.displayName)].sort());

        /*
         * Assert: and none of the other engines' players, whose games completed
         * into this same database earlier in the run. Every seat name carries its
         * project as a suffix precisely so that this is checkable. Vacuous when
         * the suite is filtered to a single project with `--project=`, and
         * knowingly so: the assertion above is the one that always bites, and a
         * cross-session query is a thing the API has no route for.
         */
        for (const project of otherProjects) {
            for (const name of rendered) {
                expect(
                    name.endsWith(`-${project}`),
                    `the leaderboard names ${name}, who belongs to the ${project} session`,
                ).toBe(false);
            }
        }

        // Assert: and the table shows nothing the body did not. Read from the DOM
        // this time, so a row rendered from stale client state would be caught.
        const playerCells = host.page
            .getByRole('table', { name: 'Final leaderboard' })
            .getByRole('cell')
            .filter({ hasText: /-/ });
        const names = await playerCells.allInnerTexts();
        for (const name of names) {
            expect(
                rendered.has(name.trim()),
                `the table renders "${name.trim()}", which the server did not send`,
            ).toBe(true);
        }
    });

    test('ranks by points and marks every tie, whatever the outcome of the game', async () => {
        const read = readLeaderboard;
        expect(read, 'no leaderboard reader was installed').toBeDefined();
        if (read === undefined) {
            return;
        }
        const leaderboard = await read();
        const rows = leaderboard.rows;

        /*
         * A tie cannot be arranged. The score is derived from the whole trick
         * history on every read (`ScoreSheet`, ADR-030) rather than stored, so
         * there is no API that forces one and no row that could be seeded to fake
         * one — the ticket's "seed a tie via SQL fixture" is not available. What
         * *is* available, and is worth more, is the invariant: whatever this game
         * happened to produce, the presentation of ties must be consistent with
         * the points. These assertions hold for a three-way tie, for a clean
         * sweep, and for everything between, so the scenario is deterministic
         * without the outcome being.
         */

        // Assert: ordered by points, best first. `ScoreSheet.rank` sorts on points
        // descending and breaks ties by seat order.
        const points = rows.map(row => row.points);
        expect([...points], 'the leaderboard is not ordered by points, best first').toEqual(
            [...points].sort((left, right) => right - left),
        );

        // Assert: competition ranking. The first row is 1st, a row tying the row
        // above it repeats that position, and any other row takes its 1-based
        // index — so positions may skip after a tie but never drift.
        rows.forEach((row, index) => {
            const previous = index === 0 ? undefined : rows[index - 1];
            const expected = previous === undefined || previous.points !== row.points ? index + 1 : previous.position;
            expect(
                row.position,
                `${row.displayName} is ranked ${row.position} where competition ranking gives ${expected}`,
            ).toBe(expected);
        });

        // Assert: `tied` says exactly whether the points are shared, in both
        // directions. A board that never sets the flag would pass a one-way check.
        for (const row of rows) {
            const sharers = rows.filter(other => other.points === row.points).length;
            expect(
                row.tied,
                `${row.displayName} on ${row.points} points ${row.tied ? 'is marked tied' : 'is not marked tied'}`
                    + ` but shares that score with ${sharers - 1} other player(s)`,
            ).toBe(sharers > 1);
        }

        // Assert: nobody is presented as sole winner unless they are one. When the
        // lead is shared, every leader is at position 1 and every leader says so.
        const best = rows.filter(row => row.position === 1);
        expect(best.length, 'no player holds first place').toBeGreaterThan(0);
        for (const leader of best) {
            expect(
                leader.tied,
                `${leader.displayName} is shown as sole winner while sharing first place with ${best.length - 1} other(s)`,
            ).toBe(best.length > 1);
        }

        // Assert: and the tie is visible to the reader of the screen, not just
        // present in the payload — every row on every seat, since a shared first
        // place is exactly what a stakeholder must not misread.
        for (const seat of seats) {
            const table = seat.page.getByRole('table', { name: 'Final leaderboard' });
            for (const row of rows) {
                await expect(
                    table.getByRole('row').filter({ hasText: row.displayName }).getByLabel(
                        `${positionLabel(row)} place`,
                    ),
                    `${seat.displayName}'s screen does not announce ${row.displayName} as "${positionLabel(row)} place"`,
                ).toBeVisible();
            }
        }
    });

    /*
     * Declared last, and `describe.serial` is why that is enough: it destroys the
     * completed game the three scenarios above share.
     */
    test('lets only the facilitator start a second game, which deals a fresh deck but leaves participants behind', async () => {
        const host = facilitator;
        expect(host, 'the shared fixture did not produce a facilitator').toBeDefined();
        if (host === undefined) {
            return;
        }
        test.setTimeout(3 * 60 * 1000);

        const startNewGame = 'Start new game';

        // Assert: the control is the facilitator's alone. `POST /new-game` refuses
        // a participant with a 403, and the screen does not render the button to
        // one at all — so the refusal is never reached from a browser, and the
        // absent control is the boundary a user actually meets.
        await expect(
            host.page.getByRole('button', { name: startNewGame }),
            'the facilitator has no control to start a second game',
        ).toBeVisible();
        for (const seat of seats.filter(candidate => candidate !== host)) {
            await expect(
                seat.page.getByRole('button', { name: startNewGame }),
                `${seat.displayName} is a participant but is offered the facilitator's ${startNewGame} control`,
            ).toHaveCount(0);
        }

        // Assert: and every seat, facilitator included, can leave instead.
        for (const seat of seats) {
            await expect(
                seat.page.getByRole('button', { name: 'Leave session' }),
                `${seat.displayName} is offered no way to leave the session`,
            ).toBeVisible();
        }

        // Act.
        await host.page.getByRole('button', { name: startNewGame }).click();

        /*
         * Assert: the facilitator lands back on the game screen with a fresh hand
         * — not in the lobby. `NewGameUseCase` calls `resetToInProgress` and deals
         * in the same breath, so `LOBBY` is never re-entered by any code path and
         * there is no lobby for a player to be returned to. `App.tsx:191-200` does
         * route the facilitator through `screen: 'lobby'`, but only transitionally:
         * `LobbyScreen` observes `IN_PROGRESS` and forwards immediately, the same
         * path a mid-game page reload takes. The hand is the anchor because the
         * game screen has no `h1`.
         */
        await expect(
            handCards(host.page).first(),
            `${host.displayName} was not dealt a hand for the second game`,
        ).toBeVisible({ timeout: 30_000 });
        await expect(
            host.page.getByRole('table', { name: 'Final leaderboard' }),
            `${host.displayName} is still showing the previous game's leaderboard`,
        ).toHaveCount(0);

        const participants = seats.filter(candidate => candidate !== host);

        /*
         * Assert: and every other player is left behind on the finished game's
         * leaderboard.
         *
         * THIS PINS A DEFECT, NOT DESIRED BEHAVIOUR — see EOP-233. `GameOverScreen`
         * is the one screen in the application that opens no session subscription,
         * so a participant looking at it is never told the session went back to
         * `IN_PROGRESS`. The server does its part: `NewGameUseCase` publishes
         * `HAND_DEALT` before the 204 returns, which is what makes this a fair test
         * rather than a race — the facilitator's own navigation above happens
         * *after* that 204, so by the time its fresh hand is on screen any
         * subscriber would already have been told. There is simply no subscriber.
         *
         * When EOP-233 is fixed these two assertions will fail, and inverting them
         * to match the facilitator's is the change that closes it.
         */
        for (const seat of participants) {
            await expect(
                seat.page.getByRole('table', { name: 'Final leaderboard' }),
                `${seat.displayName} was moved on from the old leaderboard — EOP-233 may be fixed, so invert this`,
            ).toBeVisible();
            await expect(
                handCards(seat.page),
                `${seat.displayName} was shown the second game's hand — EOP-233 may be fixed, so invert this`,
            ).toHaveCount(0);
        }

        /*
         * Assert: and a reload recovers them. This is what holds EOP-233 at medium
         * rather than a lockout, and it is worth pinning in its own right, because
         * it is the only route a stranded player currently has: `App.tsx` restores
         * the seat from `sessionStorage` and the transitional lobby forwards it
         * onward, so the second game's hand was there all along and only the
         * notification was missing.
         */
        for (const seat of participants) {
            await seat.page.reload();
            await expect(
                handCards(seat.page).first(),
                `${seat.displayName} did not recover the second game's hand by reloading`,
            ).toBeVisible({ timeout: 30_000 });
        }

        // Assert: a fresh deal of the whole deck, to the same three seats. A reset
        // that failed to clear the old hands would leave the counts wrong.
        const dealt = await Promise.all(seats.map(seat => handCards(seat.page).count()));
        expect(
            [...dealt].sort((left, right) => right - left),
            'the second deal does not match Hands.deal',
        ).toEqual([...expectedHandSizes(seats.length)]);
        expect(
            dealt.reduce((running, held) => running + held, 0),
            'the second deal did not distribute the whole deck',
        ).toBe(DECK_SIZE);
    });
});
