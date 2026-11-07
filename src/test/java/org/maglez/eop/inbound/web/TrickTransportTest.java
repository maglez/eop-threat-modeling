package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.domain.HandBuilder.aHand;
import static org.maglez.eop.domain.TrickBuilder.aTrick;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.Hand;
import org.maglez.eop.domain.Rank;
import org.maglez.eop.domain.StrideCategory;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickPlay;
import org.maglez.eop.domain.TrickPlayBuilder;
import org.maglez.eop.application.port.in.TrickState;
import org.maglez.eop.domain.HandBuilder;
import org.maglez.eop.domain.TrickBuilder;

/**
 * Exercises the transport objects the trick-play routes publish.
 *
 * <p>Several assertions here are about what the wire does <em>not</em> carry, and every one of them is
 * asserted against real serialised JSON rather than against the record's components. A record component that
 * is null is not the same thing as an absent field: the difference is a Jackson setting, the setting
 * is on these two classes rather than on the application, and a client distinguishing a resolved
 * trick from an unresolved one by the presence of {@code winningSeat} is relying on it. Asserting the
 * component is null would pass just as well against {@code "winningSeat": null}, which would tell
 * that client the wrong thing.
 *
 * <p>The most important assertion is that mapping an unresolved trick does not throw.
 * {@link Trick#winningSeat()} refuses to answer before resolution, so the mapping asks
 * {@link Trick#winner()} instead and lets an absent winner mean an absent field. That is easy to
 * "simplify" into a direct call, and the failure would be a 500 on the ordinary response to every
 * play but the last of a trick.
 *
 * <p>The state of play is published by a separate record from the trick itself, and the reason is
 * asserted here rather than only argued in a comment: three of its five fields are absent at ordinary
 * moments, and the two seat answers it carries come from different authorities — the cards on the table
 * and the seat the session row records as leading. Nothing reconciles them on the way out, so a state
 * holding two different seats is published as two different seats. A mapping that quietly preferred one
 * would take away the only check a client has on either.
 */
@DisplayName("Trick-play transport objects")
class TrickTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID HAND_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    private static final UUID TRICK_ID = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

    private static final Card LOW_SPOOF = DeckFixture.card(StrideCategory.SPOOFING, Rank.THREE);

    private static final Card HIGH_SPOOF = DeckFixture.card(StrideCategory.SPOOFING, Rank.KING);

    private static final int SECOND_SEAT = 1;

    private static final int SEQUENCE = 4;

    @Test
    @DisplayName("a hand publishes its own count, taken from the cards rather than alongside them")
    void shouldPublishAHand() {
        final Hand hand = aHand()
                .withHandId(HAND_ID)
                .withPlayerId(PLAYER_ID)
                .withCards(LOW_SPOOF, HIGH_SPOOF)
                .build();

        final HandDto dto = HandDto.from(hand);

        assertThat(dto.handId()).isEqualTo(HAND_ID.toString());
        assertThat(dto.playerId()).isEqualTo(PLAYER_ID.toString());
        assertThat(dto.cardCount()).isEqualTo(2);
        assertThat(dto.cards()).hasSize(dto.cardCount());
        assertThat(dto.cards()).extracting(CardDto::rank).containsExactly("THREE", "KING");
    }

    @Test
    @DisplayName("a hand emptied by play is published as an empty hand, not as a missing one")
    void shouldPublishAnEmptyHand() {
        final Hand hand = aHand().withHandId(HAND_ID).withPlayerId(PLAYER_ID).withCards(List.of()).build();

        final HandDto dto = HandDto.from(hand);

        assertThat(dto.cardCount()).isZero();
        assertThat(dto.cards()).isEmpty();
    }

    @Test
    @DisplayName("a hand copies the cards it is given, so a caller cannot alter one after publishing it")
    void shouldCopyTheCardsGivenToAHand() {
        final List<CardDto> mutable = new ArrayList<>(List.of(CardDto.from(LOW_SPOOF)));
        final HandDto dto = new HandDto(HAND_ID.toString(), PLAYER_ID.toString(), 1, mutable);

        mutable.clear();

        assertThat(dto.cards()).hasSize(1);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> dto.cards().add(CardDto.from(HIGH_SPOOF)));
    }

    @Test
    @DisplayName("a play publishes the seat it was made from and the instant it was recorded")
    void shouldPublishAPlay() {
        final TrickPlay play = aPlayBy(SECOND_SEAT, LOW_SPOOF)
                .withThreatLinked(true)
                .withComponents(List.of("Payments API", "Ledger"))
                .withNotes("Signed with a key nobody rotated.")
                .build();

        final TrickPlayDto dto = TrickPlayDto.from(play);

        assertThat(dto.trickPlayId()).isEqualTo(play.trickPlayId().toString());
        assertThat(dto.playerId()).isEqualTo(play.playerId().toString());
        assertThat(dto.seatOrder()).isEqualTo(SECOND_SEAT);
        assertThat(dto.card().cardId()).isEqualTo(LOW_SPOOF.cardId().toString());
        assertThat(dto.threatLinked()).isTrue();
        assertThat(dto.components()).containsExactly("Payments API", "Ledger");
        assertThat(dto.notes()).isEqualTo("Signed with a key nobody rotated.");
        assertThat(dto.playedAt()).isEqualTo(TrickPlayBuilder.PLAYED_AT.toString());
    }

    @Test
    @DisplayName("a play with no note omits the field rather than sending a null one")
    void shouldOmitAnAbsentNote() throws Exception {
        final TrickPlay play = aPlayBy(0, LOW_SPOOF).withNotes(null).build();

        final TrickPlayDto dto = TrickPlayDto.from(play);

        assertThat(dto.notes()).isNull();
        assertThat(MAPPER.writeValueAsString(dto)).doesNotContain("notes");
    }

    @Test
    @DisplayName("an unlinked play is published as an ordinary play with no components")
    void shouldPublishAnUnlinkedPlay() {
        final TrickPlay play = aPlayBy(0, LOW_SPOOF)
                .withThreatLinked(false)
                .withComponents(List.of())
                .build();

        final TrickPlayDto dto = TrickPlayDto.from(play);

        assertThat(dto.threatLinked()).isFalse();
        assertThat(dto.components()).isEmpty();
    }

    @Test
    @DisplayName("an unresolved trick is mapped without asking which seat took it")
    void shouldMapAnUnresolvedTrickWithoutAskingForTheWinner() throws Exception {
        final Trick trick = aTrick()
                .withTrickId(TRICK_ID)
                .withSequence(SEQUENCE)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, LOW_SPOOF).build())
                .withWinner(null)
                .build();

        final TrickDto dto = TrickDto.from(trick);

        assertThat(dto.trickId()).isEqualTo(TRICK_ID.toString());
        assertThat(dto.sequence()).isEqualTo(SEQUENCE);
        assertThat(dto.leaderSeat()).isZero();
        assertThat(dto.ledSuit()).isEqualTo("SPOOFING");
        assertThat(dto.plays()).extracting(TrickPlayDto::seatOrder).containsExactly(0);
        assertThat(dto.winningSeat()).as("nobody has taken it yet").isNull();
        assertThat(MAPPER.writeValueAsString(dto))
                .as("the absence of the field is how a client tells an unresolved trick from a resolved one")
                .doesNotContain("winningSeat");
    }

    @Test
    @DisplayName("a resolved trick publishes the seat that took it")
    void shouldPublishTheWinningSeat() {
        final TrickPlay taken = aPlayBy(SECOND_SEAT, HIGH_SPOOF).build();
        final Trick trick = aTrick()
                .withTrickId(TRICK_ID)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, LOW_SPOOF).build(), taken)
                .withWinner(taken)
                .build();

        final TrickDto dto = TrickDto.from(trick);

        assertThat(dto.winningSeat()).isEqualTo(SECOND_SEAT);
        assertThat(dto.plays()).hasSize(2);
    }

    @Test
    @DisplayName("a trick nobody has led into carries no led suit")
    void shouldOmitTheLedSuitOfAnEmptyTrick() throws Exception {
        final Trick trick = Trick.open(TRICK_ID, 1, SECOND_SEAT);

        final TrickDto dto = TrickDto.from(trick);

        assertThat(dto.ledSuit()).isNull();
        assertThat(dto.plays()).isEmpty();
        assertThat(dto.leaderSeat()).isEqualTo(SECOND_SEAT);
        assertThat(MAPPER.writeValueAsString(dto)).doesNotContain("ledSuit");
    }

    @Test
    @DisplayName("a trick copies the plays it is given")
    void shouldCopyThePlaysGivenToATrick() {
        final List<TrickPlayDto> mutable = new ArrayList<>(List.of(TrickPlayDto.from(aPlayBy(0, LOW_SPOOF).build())));
        final TrickDto dto = new TrickDto(TRICK_ID.toString(), 1, 0, "SPOOFING", mutable, null);

        mutable.clear();

        assertThat(dto.plays()).hasSize(1);
    }

    @Test
    @DisplayName("the state of play names the seat still to play and omits the seat that leads next")
    void shouldPublishTheSeatStillToPlay() throws Exception {
        final Trick trick = aTrick()
                .withTrickId(TRICK_ID)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, LOW_SPOOF).build())
                .withWinner(null)
                .build();
        final TrickState state =
                new TrickState(Optional.of(trick), OptionalInt.of(SECOND_SEAT), false, OptionalInt.empty(), false);

        final TrickStateDto dto = TrickStateDto.from(state);

        assertThat(dto.trick()).isNotNull();
        assertThat(dto.trick().trickId()).isEqualTo(TRICK_ID.toString());
        assertThat(dto.seatToPlay()).isEqualTo(SECOND_SEAT);
        assertThat(dto.complete()).isFalse();
        assertThat(dto.nextLeaderSeat()).as("no seat leads next until the trick is resolved").isNull();
        assertThat(dto.handComplete()).isFalse();
        assertThat(MAPPER.writeValueAsString(dto))
                .as("an absent field is how a client tells an unresolved trick from a resolved one")
                .doesNotContain("nextLeaderSeat");
    }

    @Test
    @DisplayName("the state of play before the first lead carries a seat to play and no trick")
    void shouldPublishAStateWithNoTrick() throws Exception {
        final TrickState state = new TrickState(Optional.empty(), OptionalInt.of(0), false, OptionalInt.empty(), false);

        final TrickStateDto dto = TrickStateDto.from(state);

        assertThat(dto.trick()).isNull();
        assertThat(dto.seatToPlay()).isZero();
        assertThat(MAPPER.writeValueAsString(dto))
                .as("the opening lead is still owed, so the field a client reads is the seat and not the trick")
                .doesNotContain("\"trick\"");
    }

    @Test
    @DisplayName("a played-out hand names no seat at all, and still says so with both booleans")
    void shouldPublishASpentHandWithoutNamingASeat() throws Exception {
        final TrickPlay taken = aPlayBy(SECOND_SEAT, HIGH_SPOOF).build();
        final Trick trick = aTrick()
                .withTrickId(TRICK_ID)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, LOW_SPOOF).build(), taken)
                .withWinner(taken)
                .build();
        final TrickState state =
                new TrickState(Optional.of(trick), OptionalInt.empty(), true, OptionalInt.empty(), true);

        final TrickStateDto dto = TrickStateDto.from(state);

        assertThat(dto.seatToPlay()).isNull();
        assertThat(dto.nextLeaderSeat()).isNull();
        assertThat(dto.complete()).isTrue();
        assertThat(dto.handComplete()).isTrue();

        final String json = MAPPER.writeValueAsString(dto);
        assertThat(json).doesNotContain("seatToPlay").doesNotContain("nextLeaderSeat");
        assertThat(json)
                .as("the two answers a client always gets are the pair the contract marks required")
                .contains("\"complete\":true")
                .contains("\"handComplete\":true");
    }

    @Test
    @DisplayName("two seat answers that disagree are published as they are, not reconciled")
    void shouldNotReconcileTheTwoSeatAnswers() {
        final TrickPlay taken = aPlayBy(SECOND_SEAT, HIGH_SPOOF).build();
        final Trick trick = aTrick()
                .withTrickId(TRICK_ID)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, LOW_SPOOF).build(), taken)
                .withWinner(taken)
                .build();
        final TrickState state =
                new TrickState(Optional.of(trick), OptionalInt.of(0), true, OptionalInt.of(SECOND_SEAT), false);

        final TrickStateDto dto = TrickStateDto.from(state);

        assertThat(dto.seatToPlay()).isZero();
        assertThat(dto.nextLeaderSeat())
                .as("a disagreement between the session row and the cards is a defect a client may notice")
                .isEqualTo(SECOND_SEAT);
    }
}
