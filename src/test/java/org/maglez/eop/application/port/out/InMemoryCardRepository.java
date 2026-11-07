package org.maglez.eop.application.port.out;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.in.PageQuery;
import org.maglez.eop.application.port.in.PageResult;

/**
 * In-memory stand-in for the card port.
 *
 * <p>Hand written rather than mocked. The use cases under test are thin, so what
 * matters is that they pass the right query through and interpret the right
 * answer; a real list makes that readable, whereas a mock would mostly restate
 * the implementation back at itself.
 */
public final class InMemoryCardRepository implements CardRepository {

    private final Map<UUID, Card> cards = new LinkedHashMap<>();

    private PageQuery lastQuery;

    public InMemoryCardRepository(final Card... seed) {
        for (final Card card : seed) {
            cards.put(card.cardId(), card);
        }
    }

    @Override
    public PageResult<Card> findAll(final PageQuery query) {
        this.lastQuery = query;
        final List<Card> all = List.copyOf(cards.values());
        final int from = Math.min(query.page() * query.size(), all.size());
        final int to = Math.min(from + query.size(), all.size());
        return new PageResult<>(all.subList(from, to), query.page(), query.size(), all.size());
    }

    @Override
    public Optional<Card> findById(final UUID cardId) {
        return Optional.ofNullable(cards.get(cardId));
    }

    /**
     * Every seeded card, in the order it was seeded.
     *
     * <p>A {@link LinkedHashMap} backs this double precisely so that seed order is
     * the order handed back. The port promises canonical order, and a test that
     * seeds in canonical order therefore gets it, without this class having to
     * know what canonical means — that knowledge belongs to the adapter and its
     * sort, not to a stand-in.
     */
    @Override
    public List<Card> findWholeDeck() {
        return List.copyOf(cards.values());
    }

    /**
     * The query the use case actually passed down, so a test can prove it was not
     * quietly rewritten on the way through.
     *
     * @return the last query received, or null if none
     */
    public PageQuery lastQuery() {
        return lastQuery;
    }
}
