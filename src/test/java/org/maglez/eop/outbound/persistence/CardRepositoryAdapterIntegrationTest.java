package org.maglez.eop.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.Rank;
import org.maglez.eop.domain.StrideCategory;
import org.maglez.eop.application.port.in.PageQuery;
import org.maglez.eop.application.port.in.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the migrations actually apply and that the entity matches the schema they
 * created.
 *
 * <p>This is not incidental coverage. Hibernate runs with {@code ddl-auto=validate},
 * so if a changeset and the entity disagree on a single column the context fails
 * to start — which makes booting the context the assertion that matters most here.
 */
@SpringBootTest
@DisplayName("Card persistence")
class CardRepositoryAdapterIntegrationTest {

    /**
     * The physical printed deck has 68 cards after removing Aces. Tampering starts at rank 3
     * (11 cards), Elevation of Privilege starts at rank 5 (9 cards), all other suits run 2–K
     * (12 cards each). 4 × 12 + 11 + 9 = 68.
     */
    private static final int DECK_SIZE = 68;

    /**
     * Expected card count per suit in the printed deck.
     * Suits not listed here hold the standard 12 cards (2–K, no Ace).
     */
    private static final Map<StrideCategory, Integer> CARDS_PER_SUIT;

    static {
        CARDS_PER_SUIT = new EnumMap<>(StrideCategory.class);
        for (final StrideCategory suit : StrideCategory.values()) {
            CARDS_PER_SUIT.put(suit, 12);
        }
        CARDS_PER_SUIT.put(StrideCategory.TAMPERING, 11);
        CARDS_PER_SUIT.put(StrideCategory.ELEVATION_OF_PRIVILEGE, 9);
    }

    /** The largest page the contract allows, so one request returns the whole deck. */
    private static final PageQuery WHOLE_DECK = new PageQuery(0, PageQuery.MAX_SIZE);

    @Autowired
    private CardRepositoryAdapter adapter;

    @Autowired
    private CardJpaRepository jpaRepository;

    /**
     * Counts per suit rather than only the total, because a total of 68 is also
     * reachable with an uneven distribution. The printed deck is intentionally
     * uneven: Tampering has 11 cards (starts at 3) and Elevation of Privilege has
     * 9 cards (starts at 5).
     */
    @Test
    @DisplayName("the deck holds sixty-eight cards matching the printed deck's suit distribution")
    void shouldSeedCorrectCardsPerSuit() {
        final PageResult<Card> page = adapter.findAll(WHOLE_DECK);

        assertThat(page.totalElements()).isEqualTo(DECK_SIZE);

        final Map<StrideCategory, Long> bySuit = page.content().stream()
                .collect(Collectors.groupingBy(Card::suit, Collectors.counting()));

        assertThat(bySuit).hasSize(StrideCategory.values().length);
        CARDS_PER_SUIT.forEach((suit, expected) ->
                assertThat(bySuit.get(suit))
                        .as("card count for suit %s", suit)
                        .isEqualTo(expected.longValue()));
    }

    @Test
    @DisplayName("Tampering starts at rank 3 — rank 2 was omitted from the printed deck")
    void shouldNotContainTamperingTwo() {
        final List<Card> tampering = adapter.findAll(WHOLE_DECK).content().stream()
                .filter(card -> card.suit() == StrideCategory.TAMPERING)
                .toList();

        assertThat(tampering).extracting(Card::rank).doesNotContain(Rank.TWO);
        assertThat(tampering).extracting(Card::rank).contains(Rank.THREE);
    }

    @Test
    @DisplayName("Elevation of Privilege starts at rank 5 — ranks 2, 3, 4 were omitted from the printed deck")
    void shouldNotContainElevationOfPrivilegeTwoThreeFour() {
        final List<Card> eop = adapter.findAll(WHOLE_DECK).content().stream()
                .filter(card -> card.suit() == StrideCategory.ELEVATION_OF_PRIVILEGE)
                .toList();

        assertThat(eop).extracting(Card::rank).doesNotContain(Rank.TWO, Rank.THREE, Rank.FOUR);
        assertThat(eop).extracting(Card::rank).contains(Rank.FIVE);
    }

    @Test
    @DisplayName("cards come back in STRIDE acronym order, which alphabetical suit sorting would not give")
    void shouldReturnCardsInDeckOrder() {
        final List<Card> deck = adapter.findAll(WHOLE_DECK).content();

        assertThat(deck.getFirst().suit()).isEqualTo(StrideCategory.SPOOFING);
        assertThat(deck.getLast().suit()).isEqualTo(StrideCategory.ELEVATION_OF_PRIVILEGE);
        assertThat(deck.stream().map(Card::suit).distinct().toList())
                .containsExactly(StrideCategory.values());
    }

    @Test
    @DisplayName("within a suit, cards ascend by rank — the ordering trick resolution depends on")
    void shouldOrderBySuitThenAscendingRank() {
        final List<Card> spoofing = adapter.findAll(WHOLE_DECK).content().stream()
                .filter(card -> card.suit() == StrideCategory.SPOOFING)
                .toList();

        // Spoofing is a full suit (2–K), so all ranks must be present in order.
        assertThat(spoofing).extracting(Card::rank).containsExactly(Rank.values());
    }

    @Test
    @DisplayName("the persisted suit ordering has not drifted from the enum")
    void shouldKeepPersistedSuitOrderInStepWithTheEnum() {
        final List<CardJpaEntity> rows = jpaRepository.findAll();

        assertThat(rows).hasSize(DECK_SIZE);
        assertThat(rows).allSatisfy(row -> assertThat(row.suitOrder()).isEqualTo(row.suit().deckOrder()));
    }

    @Test
    @DisplayName("pages are real: a page size of two yields thirty-four pages over the deck")
    void shouldPaginateAtTheDatabase() {
        final PageResult<Card> firstPage = adapter.findAll(new PageQuery(0, 2));

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalPages()).isEqualTo(DECK_SIZE / 2);
        // First two cards are Spoofing 2 and Spoofing 3 (Spoofing is a full suit).
        assertThat(firstPage.content())
                .extracting(Card::rank)
                .containsExactly(Rank.TWO, Rank.THREE);
        assertThat(adapter.findAll(new PageQuery(DECK_SIZE / 2, 2)).content()).isEmpty();
    }

    @Test
    @DisplayName("a seeded card round-trips through the domain type intact")
    void shouldReadASeededCardById() {
        // Elevation of Privilege, King: the trump suit's highest card.
        final UUID trumpKing = UUID.fromString("f4ea3e6e-5cd5-53d0-a32d-b9f389069b74");

        final Optional<Card> found = adapter.findById(trumpKing);

        assertThat(found).isPresent();
        assertThat(found.get().suit()).isEqualTo(StrideCategory.ELEVATION_OF_PRIVILEGE);
        assertThat(found.get().rank()).isEqualTo(Rank.KING);
        assertThat(found.get().isTrump()).isTrue();
        assertThat(found.get().threatPrompt())
                .isEqualTo("An attacker can inject a command that the system will run at a higher privilege level");
    }

    @Test
    @DisplayName("no placeholder card from the first migration survives")
    void shouldNotRetainAnyPlaceholderCard() {
        final List<UUID> placeholders = List.of(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("66666666-6666-4666-8666-666666666666"));

        assertThat(placeholders).allSatisfy(id -> assertThat(adapter.findById(id)).isEmpty());
    }

    @Test
    @DisplayName("threat prompts are unique, so no card duplicates another card's text")
    void shouldSeedDistinctThreatPrompts() {
        final List<Card> deck = adapter.findAll(WHOLE_DECK).content();

        assertThat(deck.stream().collect(Collectors.toMap(Card::threatPrompt, Function.identity(), (a, b) -> a)))
                .hasSize(DECK_SIZE);
    }

    @Test
    @DisplayName("an unknown identifier is absent, not an error, at this layer")
    void shouldReturnEmptyForAnUnknownIdentifier() {
        assertThat(adapter.findById(UUID.fromString("99999999-9999-4999-8999-999999999999"))).isEmpty();
    }
}
