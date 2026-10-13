package pl.ultimo.core.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages cards that have been played onto the table.
 * Tracks the current active card and the declared color (for wild cards).
 */
public class DiscardPile {

    private final Deque<Card> playedCards = new ArrayDeque<>();
    private CardColor activeColor;

    public void playCard(Card card, CardColor chosenColor) {
        Objects.requireNonNull(card, "card must not be null");
        if (card.isWild()) {
            if (chosenColor == null || !chosenColor.isStandardColor()) {
                throw new IllegalArgumentException("Must declare a valid standard color when playing a wild card");
            }
            this.activeColor = chosenColor;
        } else {
            this.activeColor = card.color();
        }
        playedCards.addFirst(card);
    }

    public Optional<Card> getTopCard() {
        return Optional.ofNullable(playedCards.peekFirst());
    }

    public CardColor getActiveColor() {
        return activeColor;
    }

    /**
     * Retains the current top card on the pile and removes all remaining
     * cards so they can be recycled back into the draw deck.
     */
    public List<Card> extractCardsForReplenish() {
        if (playedCards.size() <= 1) {
            return List.of();
        }

        Card topCard = playedCards.pollFirst();
        List<Card> recycledCards = new ArrayList<>(playedCards);
        playedCards.clear();
        playedCards.addFirst(topCard);

        return recycledCards;
    }

    public int size() {
        return playedCards.size();
    }
}
