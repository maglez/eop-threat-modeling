import { expect, test } from '@playwright/test';
import {
    DECK_SIZE,
    MINIMUM_PLAYERS_TO_START,
    closeSeats,
    createSession,
    expectGameOver,
    expectPlayerCount,
    expectedHandSizes,
    hand,
    handCards,
    joinSession,
    openSeat,
    playLegalCard,
    playOutTheDeck,
    startGame,
    waitForSeatToPlay,
    type Seat,
} from '../game';

/**
 * The core user journey, end to end, in three real browser engines (EOP-217).
 *
 * These scenarios drive the shipped artefacts through the `compose.e2e.yml` stack
 * that EOP-216 established: nothing is stubbed, no request is intercepted, and
 * every assertion is made against rendered output. They are the strongest
 * available evidence that the React bundle, Caddy, the Spring Boot API and
 * PostgreSQL work together.
 *
 * Three things the ticket assumed are not true of the application, and the
 * scenarios below are written against what it actually does. All three are
 * recorded in ADR-068 and on the story:
 *
 *  - **Three players, not two.** `GameSession.MINIMUM_PLAYERS_TO_START` is 3, so a
 *    two-player session can be formed but never started.
 *  - **A complete game is 68 plays.** The whole deck is dealt, and no control
 *    exists in the UI to end a session early, so reaching the game-over screen
 *    means playing every card. That is what the first scenario does, and it is why
 *    that one test raises its own timeout.
 *  - **A reload does not restore the game screen directly.** `App.tsx` always
 *    restores to the lobby, which then observes `IN_PROGRESS` and forwards to the
 *    game. The outcome the ticket asks for holds; the intermediate lobby is a
 *    legitimate transient rather than a defect.
 */
test.describe('Happy path: the full game lifecycle', () => {
    test('drives a three-player game from creation to the final leaderboard', async ({ browser }, testInfo) => {
        /*
         * 68 plays, each waiting on a server round trip and on the SSE doorbell
         * reaching two other contexts, cannot fit the 60s the config gives a test.
         * Raised here rather than in `playwright.config.ts` so that every other
         * scenario keeps the tighter budget, where a hang should fail fast. The
         * observed runtime is a few minutes per engine; the headroom is for a
         * contended CI runner, not for an expected duration.
         */
        test.setTimeout(15 * 60 * 1000);

        // Arrange: three isolated contexts, one per seat. Names carry the project
        // so a failure screenshot says which engine produced it, and so two
        // engines' sessions are never confused in the database the stack shares
        // for the whole run.
        const suffix = testInfo.project.name;
        const facilitator = await openSeat(browser, `Alice-${suffix}`);
        const second = await openSeat(browser, `Bob-${suffix}`);
        const third = await openSeat(browser, `Carol-${suffix}`);
        const seats: readonly Seat[] = [facilitator, second, third];

        try {
            // Act: form the session.
            const joinCode = await createSession(facilitator);
            await joinSession(second, joinCode);
            await joinSession(third, joinCode);

            // Assert: every context sees the full table. The count is inside the
            // heading's accessible name, so this is one assertion proving both
            // that the joins landed and that the doorbell propagated them.
            await expectPlayerCount(seats, MINIMUM_PLAYERS_TO_START);
            for (const seat of seats) {
                for (const other of seats) {
                    /*
                     * Located by the shipped row class rather than by the `term`
                     * role: the summary list wraps each `dt`/`dd` pair in a `div`,
                     * which is valid HTML but leaves the implicit term/definition
                     * mapping to the engine — not something to depend on when the
                     * point of the suite is that all three engines agree.
                     */
                    await expect(
                        seat.page.locator('.govuk-summary-list__row').filter({ hasText: other.displayName }),
                        `${seat.displayName}'s lobby does not list ${other.displayName}`,
                    ).toBeVisible();
                }
                await expect(
                    seat.page.getByText('Facilitator', { exact: true }),
                    `${seat.displayName}'s lobby does not mark exactly one facilitator`,
                ).toHaveCount(1);
            }

            // Act: start the game.
            await startGame(facilitator, seats);

            // Assert: the deal matches the dealer. Round-robin over the whole deck
            // gives 23/23/22 at three seats, and the total is the deck itself —
            // nothing is held back.
            const dealt = await Promise.all(seats.map(seat => handCards(seat.page).count()));
            expect(
                [...dealt].sort((left, right) => right - left),
                'the deal does not match Hands.deal',
            ).toEqual([...expectedHandSizes(seats.length)]);
            expect(
                dealt.reduce((total, held) => total + held, 0),
                'the deal did not distribute the whole deck',
            ).toBe(DECK_SIZE);

            // Act: play the game out. Every card, in whatever order the rules and
            // the deal dictate.
            await playOutTheDeck(seats, DECK_SIZE);

            // Assert: all three seats reach the terminal screen, and its
            // leaderboard names all three of them.
            for (const seat of seats) {
                await expectGameOver(seat);
                const leaderboard = seat.page.getByRole('table', { name: 'Final leaderboard' });
                await expect(
                    leaderboard.getByRole('row'),
                    `${seat.displayName}'s leaderboard does not have one row per player plus a header`,
                ).toHaveCount(seats.length + 1);
                for (const other of seats) {
                    await expect(
                        leaderboard.getByText(other.displayName, { exact: true }),
                        `${seat.displayName}'s leaderboard omits ${other.displayName}`,
                    ).toBeVisible();
                }
            }
        } finally {
            await closeSeats(seats);
        }
    });

    test('holds a two-player session at the lobby and admits a third from a fresh context', async ({
        browser,
    }, testInfo) => {
        // Arrange
        const suffix = testInfo.project.name;
        const facilitator = await openSeat(browser, `Dana-${suffix}`);
        const second = await openSeat(browser, `Erin-${suffix}`);
        const seats: Seat[] = [facilitator, second];

        try {
            // Act: two seats, one below the minimum.
            const joinCode = await createSession(facilitator);
            await joinSession(second, joinCode);
            await expectPlayerCount(seats, 2);

            /*
             * Assert: the game cannot start yet. This is a real `disabled`
             * attribute on a real button, so Playwright's actionability check
             * would also refuse to click it — the assertion states the intent
             * rather than relying on that.
             */
            await expect(
                facilitator.page.getByRole('button', { name: 'Start game' }),
                'Start game is not disabled below the minimum player count',
            ).toBeDisabled();

            /*
             * Assert: a participant has no such control at all. It is absent from
             * the DOM rather than disabled, which is the stronger property — a
             * disabled button can be re-enabled from the console.
             */
            await expect(
                second.page.getByRole('button', { name: 'Start game' }),
                'a participant was offered a Start game control',
            ).toHaveCount(0);

            /*
             * Act: a third context opens the application. `openSeat` asserts the
             * home screen, which is the point of this scenario: contexts share no
             * `sessionStorage`, so this one has nothing to restore and lands on
             * the home screen while a session it could join is already open.
             */
            const third = await openSeat(browser, `Femi-${suffix}`);
            seats.push(third);
            await joinSession(third, joinCode);

            // Assert: the third seat is visible everywhere, and the game may now start.
            await expectPlayerCount(seats, MINIMUM_PLAYERS_TO_START);
            await expect(
                facilitator.page.getByRole('button', { name: 'Start game' }),
                'Start game is not enabled at the minimum player count',
            ).toBeEnabled();
        } finally {
            await closeSeats(seats);
        }
    });

    test('resolves a completed trick and announces its winner', async ({ browser }, testInfo) => {
        // Arrange: a started three-player game.
        const suffix = testInfo.project.name;
        const facilitator = await openSeat(browser, `Gus-${suffix}`);
        const second = await openSeat(browser, `Hana-${suffix}`);
        const third = await openSeat(browser, `Iris-${suffix}`);
        const seats: readonly Seat[] = [facilitator, second, third];

        try {
            const joinCode = await createSession(facilitator);
            await joinSession(second, joinCode);
            await joinSession(third, joinCode);
            await expectPlayerCount(seats, MINIMUM_PLAYERS_TO_START);
            await startGame(facilitator, seats);

            /*
             * Act: exactly one trick. At three seats a trick is three plays, and
             * the third completes it — the server resolves it inline on that play
             * rather than waiting to be asked.
             */
            let lastToPlay: Seat = facilitator;
            for (let play = 0; play < seats.length; play += 1) {
                lastToPlay = await waitForSeatToPlay(seats);
                await playLegalCard(lastToPlay);
            }

            /*
             * Assert on the seat that just played, immediately. This scenario is
             * separate from the full game precisely because the announcement is
             * transient: it dismisses itself after five seconds. The acting
             * context has already re-fetched — `playLegalCard` waited for its hand
             * to shrink, which is the same state update that mounts this banner —
             * so the assertion is not racing the network, only the timer.
             */
            await expect(
                lastToPlay.page.getByRole('heading', { level: 2, name: 'Trick won!' }),
                'the completed trick was not announced',
            ).toBeVisible();

            const announcement = lastToPlay.page.getByText(/^Trick won by /);
            await expect(announcement).toBeVisible();
            const winner = (await announcement.textContent()) ?? '';
            expect(
                seats.some(seat => winner.includes(seat.displayName)),
                `the trick was won by nobody at the table: "${winner}"`,
            ).toBe(true);
        } finally {
            await closeSeats(seats);
        }
    });

    test('restores a player to the game after a page reload', async ({ browser }, testInfo) => {
        // Arrange: a started three-player game with a trick under way, so the
        // restored state is mid-game rather than freshly dealt.
        const suffix = testInfo.project.name;
        const facilitator = await openSeat(browser, `Jo-${suffix}`);
        const second = await openSeat(browser, `Kai-${suffix}`);
        const third = await openSeat(browser, `Lena-${suffix}`);
        const seats: readonly Seat[] = [facilitator, second, third];

        try {
            const joinCode = await createSession(facilitator);
            await joinSession(second, joinCode);
            await joinSession(third, joinCode);
            await expectPlayerCount(seats, MINIMUM_PLAYERS_TO_START);
            await startGame(facilitator, seats);

            const leader = await waitForSeatToPlay(seats);
            await playLegalCard(leader);

            const heldBefore = await handCards(facilitator.page).count();

            // Act
            await facilitator.page.reload();

            /*
             * Assert: the player is back at the table without re-entering
             * anything. The route runs through the lobby — `App.tsx` restores to
             * `lobby` unconditionally, which re-fetches, sees `IN_PROGRESS` and
             * forwards — so this waits for the hand rather than asserting on an
             * intermediate screen it would be wrong to forbid.
             */
            await expect(
                hand(facilitator.page),
                'the reloaded page did not return to the game screen',
            ).toBeVisible({ timeout: 30_000 });
            await expect(
                handCards(facilitator.page),
                'the restored hand does not hold the cards it held before the reload',
            ).toHaveCount(heldBefore);

            // Assert: no re-authentication was asked for, and the stored session
            // survived — which is the mechanism the restore actually depends on.
            await expect(
                facilitator.page.getByRole('button', { name: 'Create a session' }),
                'the reloaded page fell back to the home screen',
            ).toHaveCount(0);
            // Evaluate to a boolean, never to the stored value: `eop_session` holds
            // {playerToken, playerId, sessionId}, and a value returned into the test process
            // is recorded in the trace. Non-nullness is all this assertion needs (ADR-071).
            const hasSession = await facilitator.page.evaluate(() => window.sessionStorage.getItem('eop_session') !== null);
            expect(hasSession, 'eop_session was cleared by the reload').toBe(true);
        } finally {
            await closeSeats(seats);
        }
    });
});
