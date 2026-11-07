package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GameSession")
class GameSessionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T10:05:00Z");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");
    private static final JoinCode CODE = new JoinCode("ABC23456");

    @Nested
    @DisplayName("opening a lobby")
    class OpeningALobby {

        @Test
        @DisplayName("seats the creating player alone, in the lobby, with equal timestamps")
        void shouldSeatTheCreatingPlayerAlone() {
            final Player facilitator = aPlayer().withJoinedAt(NOW).build();

            final GameSession session = GameSession.openLobby(SESSION_ID, CODE, facilitator, NOW);

            assertThat(session.sessionId()).isEqualTo(SESSION_ID);
            assertThat(session.joinCode()).isEqualTo(CODE);
            assertThat(session.status()).isEqualTo(SessionStatus.LOBBY);
            assertThat(session.players()).containsExactly(facilitator);
            assertThat(session.createdAt()).isEqualTo(NOW);
            assertThat(session.updatedAt()).isEqualTo(NOW);
            assertThat(session.expiresAt()).isEqualTo(NOW.plus(GameSession.SESSION_TTL));
        }

        @Test
        @DisplayName("insists the facilitator holds seat zero, because seats are dealt in order")
        void shouldInsistFacilitatorHoldsSeatZero() {
            final Player misseated = aPlayer().withSeatOrder(1).build();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GameSession.openLobby(SESSION_ID, CODE, misseated, NOW))
                    .withMessageContaining("The facilitator holds seat 0, was 1");
        }

        @Test
        @DisplayName("insists the creating player is the facilitator")
        void shouldInsistCreatingPlayerIsFacilitator() {
            final Player participant = aPlayer().withRole(PlayerRole.PARTICIPANT).build();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GameSession.openLobby(SESSION_ID, CODE, participant, NOW))
                    .withMessageContaining("The creating player is the facilitator");
        }

        @Test
        @DisplayName("rejects a null facilitator")
        void shouldRejectNullFacilitator() {
            assertThatNullPointerException()
                    .isThrownBy(() -> GameSession.openLobby(SESSION_ID, CODE, null, NOW))
                    .withMessageContaining("facilitator");
        }

        @Test
        @DisplayName("rejects a null join code, because a lobby nobody can reach is not a lobby")
        void shouldRejectNullJoinCode() {
            final Player facilitator = aPlayer().build();

            assertThatNullPointerException()
                    .isThrownBy(() -> GameSession.openLobby(SESSION_ID, null, facilitator, NOW))
                    .withMessageContaining("joinCode");
        }
    }

    @Nested
    @DisplayName("reconstituting a stored session")
    class Reconstituting {

        @Test
        @DisplayName("sorts players by seat, so a row order from the database cannot change the table")
        void shouldSortPlayersBySeat() {
            final List<Player> shuffled = List.of(aParticipant(2).build(), aPlayer().build(), aParticipant(1).build());

            final GameSession session = GameSession.reconstitute(
                    SESSION_ID, CODE, SessionStatus.LOBBY, shuffled, NOW, LATER, LATER.plusSeconds(3600));

            assertThat(session.players()).extracting(Player::seatOrder).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("rejects two players in the same seat, which would mean a lost write")
        void shouldRejectDuplicateSeats() {
            final List<Player> clashing = List.of(aParticipant(1).build(), aParticipant(1).build());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GameSession.reconstitute(
                            SESSION_ID, CODE, SessionStatus.LOBBY, clashing, NOW, LATER, LATER.plusSeconds(3600)))
                    .withMessageContaining("Two players cannot hold the same seat");
        }

        @Test
        @DisplayName("keeps a stored status the lobby flow would never reach")
        void shouldKeepStoredStatus() {
            final GameSession session = aSession().withStatus(SessionStatus.ABANDONED).build();

            assertThat(session.status()).isEqualTo(SessionStatus.ABANDONED);
        }

        @Test
        @DisplayName("hands out a player list nobody can modify")
        void shouldExposeAnUnmodifiablePlayerList() {
            final GameSession session = aSession().withPlayerCount(2).build();

            assertThat(session.players()).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("joining")
    class Joining {

        @Test
        @DisplayName("seats the joining player next and moves only the updated timestamp")
        void shouldSeatTheJoiningPlayerNext() {
            final GameSession lobby = aSession().withPlayerCount(2).build();
            final Player joining = aParticipant(lobby.nextSeatOrder()).build();

            final GameSession joined = lobby.join(joining, LATER);

            assertThat(joined.players()).hasSize(3).extracting(Player::seatOrder).containsExactly(0, 1, 2);
            assertThat(joined.createdAt()).isEqualTo(lobby.createdAt());
            assertThat(joined.updatedAt()).isEqualTo(LATER);
            assertThat(lobby.players()).hasSize(2);
        }

        @Test
        @DisplayName("offers the next free seat as the size of the table so far")
        void shouldOfferTheNextFreeSeat() {
            assertThat(aSession().withPlayerCount(1).build().nextSeatOrder()).isEqualTo(1);
            assertThat(aSession().withPlayerCount(3).build().nextSeatOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses to name a seat at a full table, rather than naming one no player may hold")
        void shouldRefuseToNameASeatAtAFullTable() {
            final GameSession full = aSession().withPlayerCount(GameSession.MAXIMUM_PLAYERS).build();

            assertThatExceptionOfType(SessionFullException.class)
                    .isThrownBy(full::nextSeatOrder)
                    .withMessageContaining("maximum of 6 players");
        }

        @Test
        @DisplayName("refuses a seventh player, because the deck deals to six")
        void shouldRefuseASeventhPlayer() {
            final GameSession full = aSession().withPlayerCount(GameSession.MAXIMUM_PLAYERS).build();
            final Player latecomer = aParticipant(GameSession.MAXIMUM_PLAYERS - 1)
                    .withPlayerId(UUID.fromString("00000000-0000-7000-8000-0000000000aa"))
                    .build();

            assertThatExceptionOfType(SessionFullException.class)
                    .isThrownBy(() -> full.join(latecomer, LATER))
                    .withMessageContaining("maximum of 6 players");
        }

        @Test
        @DisplayName("refuses a player once play has started")
        void shouldRefuseOnceStarted() {
            final GameSession started = aSession()
                    .withPlayerCount(3)
                    .withStatus(SessionStatus.IN_PROGRESS)
                    .build();
            final Player latecomer = aParticipant(3).build();

            assertThatExceptionOfType(SessionNotJoinableException.class)
                    .isThrownBy(() -> started.join(latecomer, LATER))
                    .withMessageContaining("IN_PROGRESS");
        }

        @Test
        @DisplayName("rejects a null joining player")
        void shouldRejectNullJoiningPlayer() {
            final GameSession lobby = aSession().build();

            assertThatNullPointerException().isThrownBy(() -> lobby.join(null, LATER));
        }
    }

    @Nested
    @DisplayName("starting play")
    class StartingPlay {

        @Test
        @DisplayName("moves a full-enough lobby into play")
        void shouldMoveLobbyIntoPlay() {
            final GameSession lobby = aSession().withPlayerCount(GameSession.MINIMUM_PLAYERS_TO_START).build();
            final UUID facilitatorId = lobby.players().get(0).playerId();

            final GameSession started = lobby.start(facilitatorId, LATER);

            assertThat(started.status()).isEqualTo(SessionStatus.IN_PROGRESS);
            assertThat(started.updatedAt()).isEqualTo(LATER);
            assertThat(started.createdAt()).isEqualTo(lobby.createdAt());
            assertThat(lobby.status()).isEqualTo(SessionStatus.LOBBY);
        }

        @Test
        @DisplayName("refuses a request from somebody who is not at the table")
        void shouldRefuseAStranger() {
            final GameSession lobby = aSession().withPlayerCount(3).build();
            final UUID stranger = UUID.fromString("00000000-0000-7000-8000-0000000000bb");

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> lobby.start(stranger, LATER));
        }

        @Test
        @DisplayName("refuses a participant, because only the facilitator deals")
        void shouldRefuseAParticipant() {
            final GameSession lobby = aSession().withPlayerCount(3).build();
            final UUID participantId = lobby.players().get(1).playerId();

            assertThatExceptionOfType(NotFacilitatorException.class)
                    .isThrownBy(() -> lobby.start(participantId, LATER))
                    .withMessageContaining("is not the facilitator");
        }

        @Test
        @DisplayName("refuses a second start, so a double click cannot redeal")
        void shouldRefuseASecondStart() {
            final GameSession started = aSession()
                    .withPlayerCount(3)
                    .withStatus(SessionStatus.IN_PROGRESS)
                    .build();
            final UUID facilitatorId = started.players().get(0).playerId();

            assertThatExceptionOfType(SessionNotJoinableException.class)
                    .isThrownBy(() -> started.start(facilitatorId, LATER))
                    .withMessageContaining("IN_PROGRESS");
        }

        @Test
        @DisplayName("refuses a table of two, because the game needs three")
        void shouldRefuseTooFewPlayers() {
            final GameSession lobby = aSession().withPlayerCount(2).build();
            final UUID facilitatorId = lobby.players().get(0).playerId();

            assertThatExceptionOfType(TooFewPlayersException.class)
                    .isThrownBy(() -> lobby.start(facilitatorId, LATER))
                    .withMessageContaining("has 2 players and needs at least 3");
        }
    }

    @Nested
    @DisplayName("looking a player up")
    class LookingAPlayerUp {

        @Test
        @DisplayName("finds a player by the digest of their token")
        void shouldFindPlayerByTokenDigest() {
            final Player facilitator = aPlayer().withToken("the-facilitator-token").build();
            final GameSession session = GameSession.openLobby(SESSION_ID, CODE, facilitator, NOW);

            assertThat(session.playerByTokenHash(IdentityTokenHash.of("the-facilitator-token")))
                    .contains(facilitator);
        }

        @Test
        @DisplayName("finds nobody for an unknown digest")
        void shouldFindNobodyForUnknownDigest() {
            final GameSession session = aSession().withPlayerCount(3).build();

            assertThat(session.playerByTokenHash(IdentityTokenHash.of("not-a-real-token"))).isEmpty();
        }

        @Test
        @DisplayName("finds nobody for a null digest, so a missing header is an ordinary refusal")
        void shouldFindNobodyForNullDigest() {
            assertThat(aSession().build().playerByTokenHash(null)).isEmpty();
        }

        @Test
        @DisplayName("finds a player by identifier, and nobody for null")
        void shouldFindPlayerByIdentifier() {
            final GameSession session = aSession().withPlayerCount(2).build();
            final Player seated = session.players().get(1);

            assertThat(session.playerById(seated.playerId())).contains(seated);
            assertThat(session.playerById(null)).isEmpty();
        }
    }

    @Test
    @DisplayName("prints a player count rather than the players, so a log line cannot leak a credential")
    void shouldPrintOnlyAPlayerCount() {
        final GameSession session = aSession().withPlayerCount(3).build();

        assertThat(session).hasToString(
                "GameSession[sessionId=" + SESSION_ID + ", status=LOBBY, players=3"
                        + ", expiresAt=2099-12-31T23:59:59Z]");
    }

    @Test
    @DisplayName("compares by value, so a re-read of the same row is the same session")
    void shouldCompareByValue() {
        assertThat(aSession().withPlayerCount(2).build())
                .isEqualTo(aSession().withPlayerCount(2).build())
                .hasSameHashCodeAs(aSession().withPlayerCount(2).build())
                .isNotEqualTo(aSession().withPlayerCount(3).build());
    }
}
