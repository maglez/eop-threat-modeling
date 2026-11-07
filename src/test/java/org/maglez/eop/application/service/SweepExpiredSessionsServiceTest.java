package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.port.in.SweepExpiredSessionsPort;
import org.maglez.eop.application.port.out.SessionRepository;

/**
 * Unit tests for {@link SweepExpiredSessionsPort}.
 *
 * <p>The use case asks the repository for expired IDs, calls
 * {@code abandonAndDelete} for each one, and continues past per-session
 * failures. These tests verify that contract without a Spring context.
 */
@DisplayName("SweepExpiredSessionsPort")
class SweepExpiredSessionsServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final UUID SESSION_A = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID SESSION_B = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID SESSION_C = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

    private final SessionRepository repository = mock(SessionRepository.class);
    private final SweepExpiredSessionsPort useCase = new SweepExpiredSessionsService(repository, FIXED_CLOCK);

    @Test
    @DisplayName("calls abandonAndDelete for every expired session returned by the repository")
    void shouldAbandonAndDeleteAllExpiredSessions() {
        when(repository.findExpiredSessionIds(FIXED_NOW))
                .thenReturn(List.of(SESSION_A, SESSION_B));

        useCase.execute();

        verify(repository).abandonAndDelete(SESSION_A);
        verify(repository).abandonAndDelete(SESSION_B);
    }

    @Test
    @DisplayName("does not call abandonAndDelete when there are no expired sessions")
    void shouldSkipDeleteWhenNoExpiredSessions() {
        when(repository.findExpiredSessionIds(any(Instant.class)))
                .thenReturn(List.of());

        useCase.execute();

        verify(repository, never()).abandonAndDelete(any());
    }

    @Test
    @DisplayName("continues sweeping remaining sessions after a per-session failure")
    void shouldContinueAfterPerSessionFailure() {
        when(repository.findExpiredSessionIds(any(Instant.class)))
                .thenReturn(List.of(SESSION_A, SESSION_B, SESSION_C));
        doThrow(new RuntimeException("transient DB error"))
                .when(repository).abandonAndDelete(SESSION_B);

        assertThatNoException().isThrownBy(useCase::execute);

        verify(repository).abandonAndDelete(SESSION_A);
        verify(repository).abandonAndDelete(SESSION_B);
        verify(repository).abandonAndDelete(SESSION_C);
    }

    @Test
    @DisplayName("passes the fixed-clock instant to findExpiredSessionIds")
    void shouldQueryWithTheClockInstant() {
        when(repository.findExpiredSessionIds(FIXED_NOW))
                .thenReturn(List.of());

        useCase.execute();

        verify(repository).findExpiredSessionIds(FIXED_NOW);
    }
}
