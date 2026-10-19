package pl.ultimo.core.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Represents the draw deck containing Ultimo cards.
 * Standard deck consists of exactly 108 cards:
 * - 19 Blue cards (one 0, two of 1-9)
 * - 19 Green cards (one 0, two of 1-9)
 * - 19 Red cards (one 0, two of 1-9)
 * - 19 Yellow cards (one 0, two of 1-9)
 * - 8 Draw Two cards (two in each color)
 * - 8 Reverse cards (two in each color)
 * - 8 Skip cards (two in each color)
 * - 4 Wild cards
 * - 4 Wild Draw Four cards
 */
public class Deck {

    private final Deque<Card> cards;
    private final Random random;

    public Deck() {
        this(new Random());
    }

    public Deck(Random random) {
        this.cards = new ArrayDeque<>();
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public Deck(long seed) {
        this(new Random(seed));
    }

    /**
     * Creates and shuffles a standard 108-card Ultimo deck.
     */
    public static Deck createStandardDeck() {
        return createStandardDeck(new Random());
    }

    public static Deck createStandardDeck(Random random) {
        return createMultiDeck(1, random);
    }

    public static Deck createStandardDeck(long seed) {
        return createStandardDeck(new Random(seed));
    }

    /**
     * Creates and shuffles multiple combined standard 108-card decks (e.g. 2 decks = 216 cards).
     */
    public static Deck createMultiDeck(int deckCount) {
        return createMultiDeck(deckCount, new Random());
    }

    public static Deck createMultiDeck(int deckCount, long seed) {
        return createMultiDeck(deckCount, new Random(seed));
    }

    public static Deck createMultiDeck(int deckCount, Random random) {
        if (deckCount < 1) {
            throw new IllegalArgumentException("deckCount must be at least 1");
        }
        Deck deck = new Deck(random);
        List<Card> initialCards = new ArrayList<>(108 * deckCount);

        for (int d = 0; d < deckCount; d++) {
            for (CardColor color : CardColor.values()) {
                if (!color.isStandardColor()) {
                    continue;
                }

                // Exactly one 0 per color
                initialCards.add(new Card(color, CardValue.ZERO));

                // Two of each 1-9 per color
                for (int number = 1; number <= 9; number++) {
                    CardValue val = CardValue.valueOf(getNumberName(number));
                    initialCards.add(new Card(color, val));
                    initialCards.add(new Card(color, val));
                }

                // Two of each action card per color
                initialCards.add(new Card(color, CardValue.SKIP));
                initialCards.add(new Card(color, CardValue.SKIP));
                initialCards.add(new Card(color, CardValue.REVERSE));
                initialCards.add(new Card(color, CardValue.REVERSE));
                initialCards.add(new Card(color, CardValue.DRAW_TWO));
                initialCards.add(new Card(color, CardValue.DRAW_TWO));
            }

            // Four Wild cards and four Wild Draw Four cards
            for (int i = 0; i < 4; i++) {
                initialCards.add(new Card(CardColor.WILD, CardValue.WILD));
                initialCards.add(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR));
            }
        }

        Collections.shuffle(initialCards, random);
        deck.cards.addAll(initialCards);
        return deck;
    }

    /**
     * Dynamically sizes the deck based on the number of players:
     * - 2 to 8 players: 1 standard 108-card deck.
     * - 9 to 16 players: 2 combined standard decks (216 cards).
     */
    public static Deck createDeckForPlayerCount(int playerCount) {
        return createDeckForPlayerCount(playerCount, new Random());
    }

    public static Deck createDeckForPlayerCount(int playerCount, long seed) {
        return createDeckForPlayerCount(playerCount, new Random(seed));
    }

    public static Deck createDeckForPlayerCount(int playerCount, Random random) {
        int deckCount = playerCount > 8 ? 2 : 1;
        return createMultiDeck(deckCount, random);
    }

    public void shuffle() {
        List<Card> cardList = new ArrayList<>(cards);
        Collections.shuffle(cardList, random);
        cards.clear();
        cards.addAll(cardList);
    }

    public Optional<Card> draw() {
        return Optional.ofNullable(cards.pollFirst());
    }

    public void pushTop(Card card) {
        if (card != null) {
            cards.addFirst(card);
        }
    }

    public void replenishFromDiscard(List<Card> recycledCards) {
        if (recycledCards == null || recycledCards.isEmpty()) {
            return;
        }
        List<Card> toShuffle = new ArrayList<>(recycledCards);
        Collections.shuffle(toShuffle, random);
        cards.addAll(toShuffle);
    }

    public int size() {
        return cards.size();
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    private static String getNumberName(int number) {
        return switch (number) {
            case 1 -> "ONE";
            case 2 -> "TWO";
            case 3 -> "THREE";
            case 4 -> "FOUR";
            case 5 -> "FIVE";
            case 6 -> "SIX";
            case 7 -> "SEVEN";
            case 8 -> "EIGHT";
            case 9 -> "NINE";
            default -> throw new IllegalArgumentException("Invalid number: " + number);
        };
    }
}
