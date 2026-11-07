package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.ConnectionStatus;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerRole;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.CreateSessionPort;
import org.maglez.eop.application.port.in.SessionAdmission;
import org.maglez.eop.application.port.out.FixedIdentityTokenGenerator;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.QueuedJoinCodeGenerator;
import org.maglez.eop.domain.JoinCode;

/**
 * Tests {@link CreateSessionPort}.
 *
 * <p>The interesting behaviour is not the happy path but the collision retry. A
 * join code is eight Crockford characters, so two facilitators opening a lobby at
 * the same moment can be handed the same one. The use case must retry with a
 * fresh code while keeping the credential it already minted: a caller told one
 * token and then a different one would have no way to know which is live.
 *
 * <p>JUnit's {@code @DisplayName} annotation and the domain's display name type
 * share a simple name. The annotation is imported and the domain type appears
 * only through {@link #FACILITATOR_NAME}, so neither has to be spelled out at
 * every use.
 */
@DisplayName("CreateSessionPort")
class CreateSessionServiceTest {

    /** Matches {@code CreateSessionPort.MAXIMUM_CODE_ATTEMPTS}, which is private: pinning it here is the point. */
    private static final int CODE_ATTEMPTS = 5;

    private static final Instant NOW = Instant.parse("2026-02-01T09:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final org.maglez.eop.domain.DisplayName FACILITATOR_NAME =
            org.maglez.eop.domain.DisplayName.of("Ada");

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID SESSION_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID SESSION_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private static final String TOKEN = "facilitator-plaintext-token";
    private static final String FIRST_CODE = "ABC23456";
    private static final String SECOND_CODE = "DEF56789";

    private final InMemorySessionRepository repository = new InMemorySessionRepository();

    @Test
    @DisplayName("opens a lobby whose only player is the facilitator in seat zero")
    void shouldOpenALobbyWithTheFacilitatorSeated() {
        final CreateSessionPort useCase = useCaseIssuing(
                new QueuedIdentifierGenerator(PLAYER_ID, SESSION_A),
                new QueuedJoinCodeGenerator(FIRST_CODE));

        final SessionAdmission admission = useCase.execute(FACILITATOR_NAME);

        assertThat(admission.playerId()).isEqualTo(PLAYER_ID);
        assertThat(admission.playerToken()).isEqualTo(TOKEN);

        final GameSession session = admission.session();
        assertThat(session.sessionId()).isEqualTo(SESSION_A);
        assertThat(session.joinCode().value()).isEqualTo(FIRST_CODE);
        assertThat(session.status()).isEqualTo(SessionStatus.LOBBY);
        assertThat(session.createdAt()).isEqualTo(NOW);
        assertThat(session.updatedAt()).isEqualTo(NOW);

        assertThat(session.players()).hasSize(1);
        final Player facilitator = session.players().get(0);
        assertThat(facilitator.playerId()).isEqualTo(PLAYER_ID);
        assertThat(facilitator.displayName()).isEqualTo(FACILITATOR_NAME);
        assertThat(facilitator.seatOrder()).isZero();
        assertThat(facilitator.role()).isEqualTo(PlayerRole.FACILITATOR);
        assertThat(facilitator.connectionStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(facilitator.joinedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("stores the lobby, so a join by code can find it")
    void shouldStoreTheLobby() {
        final CreateSessionPort useCase = useCaseIssuing(
                new QueuedIdentifierGenerator(PLAYER_ID, SESSION_A),
                new QueuedJoinCodeGenerator(FIRST_CODE));

        useCase.execute(FACILITATOR_NAME);

        assertThat(repository.createLobbyCalls()).isEqualTo(1);
        assertThat(repository.findById(SESSION_A)).isPresent();
        assertThat(repository.findByJoinCode(new org.maglez.eop.domain.JoinCode(FIRST_CODE))).isPresent();
    }

    @Test
    @DisplayName("seats the digest of the credential, never the credential")
    void shouldSeatOnlyTheDigest() {
        final CreateSessionPort useCase = useCaseIssuing(
                new QueuedIdentifierGenerator(PLAYER_ID, SESSION_A),
                new QueuedJoinCodeGenerator(FIRST_CODE));

        final SessionAdmission admission = useCase.execute(FACILITATOR_NAME);

        final IdentityTokenHash seated = admission.session().players().get(0).identityTokenHash();
        assertThat(seated).isEqualTo(IdentityTokenHash.of(TOKEN));
        assertThat(seated.value()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("retries a colliding join code, reusing the credential it already minted")
    void shouldRetryACollidingJoinCode() {
        repository.rejectNextJoinCodes(1);
        final CreateSessionPort useCase = useCaseIssuing(
                new QueuedIdentifierGenerator(PLAYER_ID, SESSION_A, SESSION_B),
                new QueuedJoinCodeGenerator(FIRST_CODE, SECOND_CODE));

        final SessionAdmission admission = useCase.execute(FACILITATOR_NAME);

        assertThat(admission.session().sessionId()).isEqualTo(SESSION_B);
        assertThat(admission.session().joinCode().value()).isEqualTo(SECOND_CODE);
        assertThat(admission.playerId()).isEqualTo(PLAYER_ID);
        assertThat(admission.playerToken()).isEqualTo(TOKEN);
        assertThat(repository.createLobbyCalls()).isEqualTo(2);
        assertThat(repository.findById(SESSION_A)).isEmpty();
    }

    @Test
    @DisplayName("gives up after five collisions rather than looping forever")
    void shouldGiveUpAfterFiveCollisions() {
        repository.rejectNextJoinCodes(CODE_ATTEMPTS);
        final QueuedJoinCodeGenerator codes =
                new QueuedJoinCodeGenerator("ABC23456", "DEF56789", "GHJ89ABC", "KMN0BCDE", "PQR1DEFG");
        final QueuedIdentifierGenerator identifiers = new QueuedIdentifierGenerator(
                PLAYER_ID,
                UUID.fromString("00000000-0000-7000-8000-000000000011"),
                UUID.fromString("00000000-0000-7000-8000-000000000012"),
                UUID.fromString("00000000-0000-7000-8000-000000000013"),
                UUID.fromString("00000000-0000-7000-8000-000000000014"),
                UUID.fromString("00000000-0000-7000-8000-000000000015"));
        final CreateSessionPort useCase = useCaseIssuing(identifiers, codes);

        assertThatExceptionOfType(JoinCodeUnavailableException.class)
                .isThrownBy(() -> useCase.execute(FACILITATOR_NAME))
                .withMessageContaining("already in use");

        assertThat(repository.createLobbyCalls()).isEqualTo(CODE_ATTEMPTS);
        assertThat(codes.issued()).isEqualTo(CODE_ATTEMPTS);
        assertThat(identifiers.issued()).isEqualTo(CODE_ATTEMPTS + 1);
    }

    @Test
    @DisplayName("refuses a null display name, because an unnamed player cannot be shown at the table")
    void shouldRejectANullDisplayName() {
        final CreateSessionPort useCase = useCaseIssuing(
                new QueuedIdentifierGenerator(PLAYER_ID, SESSION_A),
                new QueuedJoinCodeGenerator(FIRST_CODE));

        assertThatNullPointerException().isThrownBy(() -> useCase.execute(null));

        assertThat(repository.createLobbyCalls()).isZero();
    }

    private CreateSessionPort useCaseIssuing(final QueuedIdentifierGenerator identifiers,
                                                final QueuedJoinCodeGenerator codes) {
        return new CreateSessionService(repository, identifiers, codes, new FixedIdentityTokenGenerator(TOKEN), FIXED);
    }
}
