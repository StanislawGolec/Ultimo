package pl.ultimo.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the collection of cards held by a player.
 */
public class PlayerHand {

    private final List<Card> cards = new ArrayList<>();

    public void addCard(Card card) {
        cards.add(Objects.requireNonNull(card, "card must not be null"));
    }

    public boolean removeCard(Card card) {
        return cards.remove(card);
    }

    public boolean contains(Card card) {
        return cards.contains(card);
    }

    public List<Card> getCards() {
        return Collections.unmodifiableList(cards);
    }

    /**
     * Calculates the sum of points of all cards in hand using official Ultimo point values.
     */
    public int calculateTotalScore() {
        return cards.stream()
                .mapToInt(card -> card.value().getPointValue())
                .sum();
    }

    public int size() {
        return cards.size();
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    public void clear() {
        cards.clear();
    }
}
