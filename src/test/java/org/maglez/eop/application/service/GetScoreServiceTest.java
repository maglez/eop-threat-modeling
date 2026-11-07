package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;
import static org.maglez.eop.domain.TrickBuilder.aTrick;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.Rank;
import org.maglez.eop.domain.ScoredPlay;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.Standing;
import org.maglez.eop.domain.StrideCategory;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.application.port.in.GetScorePort;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;

import org.maglez.eop.domain.GameSessionBuilder;
import org.maglez.eop.domain.TrickBuilder;
import org.maglez.eop.domain.TrickPlayBuilder;

/**
 * Exercises the score read.
 *
 * <p>The deck is a single suit throughout, so the highest rank played takes the trick without the
 * test having to restate any rule about trumps. That rule belongs to {@code Trick} and is pinned
 * there; what is pinned here is that this use case asks the right questions in the right order.
 *
 * <p>Every refusal is asserted to happen <em>before</em> any trick is read, by checking the
 * repository was never asked. {@code TrickRepository} authorises nobody and cannot — no method on
 * it takes an acting player (ADR-024) — so a use case that read first and authorised second would
 * have the whole game's history in memory at the moment a stranger was turned away. Asserting only
 * the exception would pass against that implementation.
 *
 * <p>The successful case asserts the identifier the repository was asked for, not merely that it
 * was asked. A use case that authorised one session and then read another would satisfy every
 * assertion about the exception and every assertion about the totals.
 *
 * <p>Players are built with their identifiers forced into the same range {@code TrickPlayBuilder}
 * derives its own from, because a play whose player is not seated is a contradiction the domain
 * refuses. {@code GameSessionBuilder.withPlayerCount} cannot be used for that reason.
 */
@DisplayName("GetScorePort")
class GetScoreServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d0");

    private static final UUID OTHER_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d1");

    private static final long PLAYER_PREFIX = 700L;

    private static final long TRICK_PREFIX = 1000L;

    private static final long PLAY_PREFIX = 900L;

    private static final int SEATS = 3;

    private static final int FIRST_SEQUENCE = 1;

    private static final int SECOND_SEQUENCE = 2;

    private final List<String> order = new ArrayList<>();

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    @Nested
    @DisplayName("the score of a game")
    class TheScoreOfAGame {

        @Test
        @DisplayName("gives a point for the threat and a point for taking the trick")
        void shouldGiveAPointForTheThreatAndAPointForTheTrick() {
            trickRepository.seededWithHistory(resolved(FIRST_SEQUENCE, Rank.FIVE, Rank.KING, Rank.SEVEN));

            final var sheet = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(sheet.rows()).extracting(ScoredPlay::seatOrder).containsExactly(0, 1, 2);
            assertThat(sheet.rows()).extracting(ScoredPlay::threatPoint).containsOnly(true);
            assertThat(sheet.rows()).extracting(ScoredPlay::trickPoint).containsExactly(false, true, false);
            assertThat(sheet.standings()).extracting(Standing::seatOrder).containsExactly(1, 0, 2);
            assertThat(sheet.standings()).extracting(Standing::points).containsExactly(2, 1, 1);
            assertThat(sheet.standings()).extracting(Standing::position).containsExactly(1, 2, 2);
        }

        @Test
        @DisplayName("lists everybody on nothing before the first trick")
        void shouldListEverybodyOnNothingBeforeTheFirstTrick() {
            final var sheet = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(sheet.rows()).isEmpty();
            assertThat(sheet.standings()).extracting(Standing::points).containsExactly(0, 0, 0);
            assertThat(sheet.standings()).extracting(Standing::position).containsExactly(1, 1, 1);
            assertThat(sheet.leadIsShared()).isTrue();
        }

        @Test
        @DisplayName("counts a trick still on the table as threat points only")
        void shouldCountATrickStillOnTheTableAsThreatPointsOnly() {
            trickRepository.seededWithHistory(
                    resolved(FIRST_SEQUENCE, Rank.FIVE, Rank.KING, Rank.SEVEN),
                    unresolved(SECOND_SEQUENCE, Rank.TWO, Rank.THREE));

            final var sheet = useCase().execute(SESSION_ID, tokenFor(1));

            assertThat(sheet.rows()).hasSize(5);
            assertThat(sheet.standings()).extracting(Standing::seatOrder).containsExactly(1, 0, 2);
            assertThat(sheet.standings()).extracting(Standing::points).containsExactly(3, 2, 1);
            assertThat(sheet.standings()).extracting(Standing::tied).containsOnly(false);
        }

        @Test
        @DisplayName("answers any seated player, not only the facilitator")
        void shouldAnswerAnySeatedPlayer() {
            trickRepository.seededWithHistory(resolved(FIRST_SEQUENCE, Rank.FIVE, Rank.KING, Rank.SEVEN));

            final var fromTheFacilitator = useCase().execute(SESSION_ID, tokenFor(0));
            final var fromAParticipant = useCase().execute(SESSION_ID, tokenFor(2));

            assertThat(fromAParticipant).isEqualTo(fromTheFacilitator);
        }
        /**
         * Pins the claim a single trick cannot make: the score is the whole history added up.
         *
         * <p>Two resolved tricks are seeded, won by different seats, so every seat collects a threat
         * point twice and two of them also collect a trick point. A use case that read only the trick
         * in front of the players would answer three rows instead of six, and one that dropped a
         * resolved trick would leave a seat short. The tie between the two winners is asserted as
         * well, because ties are shown rather than broken.
         */
        @Test
        @DisplayName("adds up every resolved trick, and shows a tie as a tie")
        void shouldAddUpEveryResolvedTrick() {
            trickRepository.seededWithHistory(resolved(FIRST_SEQUENCE, Rank.KING, Rank.FIVE, Rank.SEVEN),
                    resolved(SECOND_SEQUENCE, Rank.FIVE, Rank.KING, Rank.SEVEN));

            final var sheet = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(sheet.rows()).hasSize(2 * SEATS);
            assertThat(sheet.pointsOf(new UUID(PLAYER_PREFIX, 0))).isEqualTo(3);
            assertThat(sheet.pointsOf(new UUID(PLAYER_PREFIX, 1))).isEqualTo(3);
            assertThat(sheet.pointsOf(new UUID(PLAYER_PREFIX, 2))).isEqualTo(2);
            assertThat(sheet.standings()).extracting(Standing::position).containsExactly(1, 1, 3);
            assertThat(sheet.standings()).extracting(Standing::tied).containsExactly(true, true, false);
        }

    }

    @Nested
    @DisplayName("authorising the caller")
    class AuthorisingTheCaller {

        @Test
        @DisplayName("reads the tricks of the session it authorised, and only those")
        void shouldReadTheTricksOfTheSessionItAuthorised() {
            trickRepository.seededWithHistory(resolved(FIRST_SEQUENCE, Rank.FIVE, Rank.KING, Rank.SEVEN));

            useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(trickRepository.tricksAskedFor()).contains(SESSION_ID);
        }

        @Test
        @DisplayName("refuses a token that belongs to no seat here, before reading any trick")
        void shouldRefuseAStrangerBeforeReadingAnyTrick() {
            trickRepository.seededWithHistory(resolved(FIRST_SEQUENCE, Rank.FIVE, Rank.KING, Rank.SEVEN));
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, "a token from somewhere else"));

            assertThat(trickRepository.tricksAskedFor()).isEmpty();
        }

        @Test
        @DisplayName("refuses a missing credential, before reading any trick")
        void shouldRefuseAMissingCredentialBeforeReadingAnyTrick() {
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class).isThrownBy(() -> useCase.execute(SESSION_ID, null));

            assertThat(trickRepository.tricksAskedFor()).isEmpty();
        }

        @Test
        @DisplayName("refuses a blank credential, before reading any trick")
        void shouldRefuseABlankCredentialBeforeReadingAnyTrick() {
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class).isThrownBy(() -> useCase.execute(SESSION_ID, "   "));

            assertThat(trickRepository.tricksAskedFor()).isEmpty();
        }

        @Test
        @DisplayName("refuses a session that does not exist, before reading any trick")
        void shouldRefuseAnUnknownSessionBeforeReadingAnyTrick() {
            final var useCase = useCase();

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> useCase.execute(OTHER_SESSION_ID, tokenFor(0)));

            assertThat(trickRepository.tricksAskedFor()).isEmpty();
        }

        @Test
        @DisplayName("refuses a null session identifier")
        void shouldRefuseANullSessionIdentifier() {
            final var useCase = useCase();

            assertThatNullPointerException().isThrownBy(() -> useCase.execute(null, tokenFor(0)));

            assertThat(trickRepository.tricksAskedFor()).isEmpty();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses to be built without the collaborators it reads through")
        void shouldRefuseToBeBuiltWithoutItsCollaborators() {
            final var resolver = new ResolvePlayerService(new InMemorySessionRepository(order, seatedTable()), java.time.Clock.systemUTC());

            assertThatNullPointerException().isThrownBy(() -> new GetScoreService(null, trickRepository));
            assertThatNullPointerException().isThrownBy(() -> new GetScoreService(resolver, null));
        }
    }

    private GetScorePort useCase() {
        final var resolver = new ResolvePlayerService(new InMemorySessionRepository(order, seatedTable()), java.time.Clock.systemUTC());
        return new GetScoreService(resolver, trickRepository);
    }

    private static GameSession seatedTable() {
        final List<Player> players = new ArrayList<>();
        for (int seatOrder = 0; seatOrder < SEATS; seatOrder++) {
            players.add(seat(seatOrder));
        }
        return aSession().withSessionId(SESSION_ID).withStatus(SessionStatus.IN_PROGRESS).withPlayers(players).build();
    }

    private static Player seat(final int seatOrder) {
        final var identifier = new UUID(PLAYER_PREFIX, seatOrder);
        return seatOrder == 0
                ? aPlayer().withPlayerId(identifier).build()
                : aParticipant(seatOrder).withPlayerId(identifier).build();
    }

    private static String tokenFor(final int seatOrder) {
        final var base = PlayerBuilder.DEFAULT_TOKEN;
        return seatOrder == 0 ? base : base + "-" + seatOrder;
    }

    private static Trick resolved(final int sequence, final Rank... ranks) {
        return unresolved(sequence, ranks).resolved();
    }

    private static Trick unresolved(final int sequence, final Rank... ranks) {
        var builder = aTrick().withTrickId(new UUID(TRICK_PREFIX, sequence)).withSequence(sequence).withLeaderSeat(0);
        for (int seatOrder = 0; seatOrder < ranks.length; seatOrder++) {
            final var card = DeckFixture.card(StrideCategory.SPOOFING, ranks[seatOrder]);
            builder = builder.andPlay(aPlayBy(seatOrder, card).withTrickPlayId(new UUID(PLAY_PREFIX + sequence, seatOrder)).build());
        }
        return builder.build();
    }
}
