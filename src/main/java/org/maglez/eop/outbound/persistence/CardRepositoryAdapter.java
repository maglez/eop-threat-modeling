package org.maglez.eop.outbound.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.out.CardRepository;
import org.maglez.eop.application.port.in.PageQuery;
import org.maglez.eop.application.port.in.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Implements the application's card port on top of Spring Data.
 *
 * <p>This is the only class that knows both vocabularies. Spring Data types stop
 * here; the use case layer sees {@link PageQuery} and {@link PageResult} and
 * nothing else.
 */
@Repository
public class CardRepositoryAdapter implements CardRepository {

    /**
     * The canonical deck ordering: STRIDE acronym order, then ascending rank.
     * Fixed rather than client controllable — the deck has one right order, and a
     * stable order keeps pagination coherent and the tests deterministic.
     */
    private static final Sort DECK_ORDER = Sort.by(Sort.Direction.ASC, "suitOrder", "cardRank");

    private final CardJpaRepository cardJpaRepository;

    CardRepositoryAdapter(final CardJpaRepository cardJpaRepository) {
        this.cardJpaRepository = Objects.requireNonNull(cardJpaRepository, "cardJpaRepository is required");
    }

    @Override
    public PageResult<Card> findAll(final PageQuery query) {
        Objects.requireNonNull(query, "query is required");
        final Page<CardJpaEntity> page = cardJpaRepository.findAll(PageRequest.of(query.page(), query.size(), DECK_ORDER));
        return new PageResult<>(
                page.getContent().stream().map(CardJpaEntity::toDomain).toList(),
                query.page(),
                query.size(),
                page.getTotalElements());
    }

    @Override
    public Optional<Card> findById(final UUID cardId) {
        Objects.requireNonNull(cardId, "cardId is required");
        return cardJpaRepository.findById(cardId).map(CardJpaEntity::toDomain);
    }

    @Override
    public List<Card> findWholeDeck() {
        return cardJpaRepository.findAll(DECK_ORDER).stream().map(CardJpaEntity::toDomain).toList();
    }
}
