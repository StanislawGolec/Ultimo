package pl.ultimo.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Deck Domain Unit Tests")
class DeckTest {

    @Test
    @DisplayName("Standard Ultimo deck should contain exactly 108 cards")
    void standardDeckShouldContain108Cards() {
        Deck deck = Deck.createStandardDeck();
        assertEquals(108, deck.size());
    }

    @Test
    @DisplayName("Standard deck must contain the exact distribution of Ultimo cards")
    void deckShouldHaveCorrectCardDistribution() {
        Deck deck = Deck.createStandardDeck();
        List<Card> cards = new ArrayList<>();
        while (!deck.isEmpty()) {
            cards.add(deck.draw().orElseThrow());
        }

        assertEquals(108, cards.size());

        // Count per card type
        long zeroCount = cards.stream().filter(c -> c.value() == CardValue.ZERO).count();
        long wildCount = cards.stream().filter(c -> c.value() == CardValue.WILD).count();
        long wildDrawFourCount = cards.stream().filter(c -> c.value() == CardValue.WILD_DRAW_FOUR).count();
        long skipCount = cards.stream().filter(c -> c.value() == CardValue.SKIP).count();
        long reverseCount = cards.stream().filter(c -> c.value() == CardValue.REVERSE).count();
        long drawTwoCount = cards.stream().filter(c -> c.value() == CardValue.DRAW_TWO).count();

        assertEquals(4, zeroCount, "Each of the 4 colors must have exactly one 0 card");
        assertEquals(4, wildCount, "Deck must have 4 Wild cards");
        assertEquals(4, wildDrawFourCount, "Deck must have 4 Wild Draw Four cards");
        assertEquals(8, skipCount, "Deck must have 8 Skip cards (2 per color)");
        assertEquals(8, reverseCount, "Deck must have 8 Reverse cards (2 per color)");
        assertEquals(8, drawTwoCount, "Deck must have 8 Draw Two cards (2 per color)");

        // 72 cards of values 1-9 (2 each per color * 9 * 4)
        long oneToNineCount = cards.stream()
                .filter(c -> c.value().isNumber() && c.value() != CardValue.ZERO)
                .count();
        assertEquals(72, oneToNineCount);
    }

    @Test
    @DisplayName("Drawing all cards should deplete the deck")
    void drawingCardsShouldDepleteDeck() {
        Deck deck = Deck.createStandardDeck();
        for (int i = 0; i < 108; i++) {
            assertTrue(deck.draw().isPresent());
        }
        assertTrue(deck.isEmpty());
        assertEquals(Optional.empty(), deck.draw());
    }

    @Test
    @DisplayName("Replenish from discard should refill an empty deck")
    void replenishFromDiscardRefillsDeck() {
        Deck deck = new Deck();
        assertTrue(deck.isEmpty());

        List<Card> discarded = List.of(
                new Card(CardColor.RED, CardValue.ONE),
                new Card(CardColor.BLUE, CardValue.FIVE)
        );

        deck.replenishFromDiscard(discarded);
        assertEquals(2, deck.size());
        assertFalse(deck.isEmpty());
    }
}
