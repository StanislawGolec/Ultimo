package pl.ultimo.core.engine;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.Deck;
import pl.ultimo.core.model.DiscardPile;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.GameState;
import pl.ultimo.core.state.TurnManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Authoritative game engine orchestrating Ultimo rules, turn progression, and card resolution.
 * Completely decoupled from networking.
 */
public class UltimoEngine {

    public static final int STARTING_HAND_SIZE = 7;

    private final List<Player> players;
    private final Deck deck;
    private final DiscardPile discardPile;
    private final TurnManager turnManager;
    private final Random random;

    private boolean isGameStarted;
    private boolean isGameOver;
    private boolean hasDrawnThisTurn;
    private Player winner;

    public record PlayResult(
            boolean success,
            String errorMessage,
            Card playedCard,
            boolean isUltimo,
            boolean isGameOver,
            Player winner
    ) {
        public static PlayResult failure(String errorMessage) {
            return new PlayResult(false, errorMessage, null, false, false, null);
        }

        public static PlayResult success(Card card, boolean isUltimo, boolean isGameOver, Player winner) {
            return new PlayResult(true, null, card, isUltimo, isGameOver, winner);
        }
    }

    public UltimoEngine(List<Player> players) {
        this(players, new Random());
    }

    public UltimoEngine(List<Player> players, Random random) {
        if (players == null || players.size() < 2) {
            throw new IllegalArgumentException("At least 2 players are required to start Ultimo");
        }
        this.players = List.copyOf(players);
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.deck = Deck.createStandardDeck(random);
        this.discardPile = new DiscardPile();
        this.turnManager = new TurnManager(this.players);
    }

    /**
     * Initializes the match: deals starting hands and flips the first card onto the discard pile.
     */
    public void start() {
        if (isGameStarted) {
            throw new IllegalStateException("Game has already started");
        }

        // Deal 7 cards to each player
        for (int i = 0; i < STARTING_HAND_SIZE; i++) {
            for (Player player : players) {
                drawCardFromDeck().ifPresent(player.getHand()::addCard);
            }
        }

        // Flip starting card; if action card or wild is drawn as first card, return to deck
        // ensuring fair start where every player starts with 7 cards and player 0 plays first
        Card startingCard;
        do {
            startingCard = drawCardFromDeck().orElseThrow(() -> new IllegalStateException("Deck ran out of cards during setup"));
            if (!startingCard.value().isNumber()) {
                deck.replenishFromDiscard(List.of(startingCard));
                startingCard = null;
            }
        } while (startingCard == null);

        discardPile.playCard(startingCard, startingCard.color());

        this.isGameStarted = true;
        this.hasDrawnThisTurn = false;
    }

    /**
     * Attempts to play a card from the active player's hand.
     */
    public PlayResult playCard(Player player, Card card, CardColor chosenWildColor) {
        if (!isGameStarted) {
            return PlayResult.failure("Game has not started yet");
        }
        if (isGameOver) {
            return PlayResult.failure("Game is already over");
        }

        Card topCard = discardPile.getTopCard().orElse(null);
        CardColor activeColor = discardPile.getActiveColor();

        Optional<String> validationError = MoveValidator.validateMove(
                player,
                turnManager.getCurrentPlayer(),
                card,
                topCard,
                activeColor,
                chosenWildColor
        );

        if (validationError.isPresent()) {
            return PlayResult.failure(validationError.get());
        }

        // Remove card from player hand and place on discard pile
        player.getHand().removeCard(card);
        CardColor effectiveColor = card.isWild() ? chosenWildColor : card.color();
        discardPile.playCard(card, effectiveColor);

        // Check if player has won
        if (player.getHand().isEmpty()) {
            this.isGameOver = true;
            this.winner = player;
            return PlayResult.success(card, false, true, player);
        }

        boolean isUltimo = player.getHand().size() == 1;

        // Apply card action effects
        applyCardAction(card);

        this.hasDrawnThisTurn = false;
        return PlayResult.success(card, isUltimo, false, null);
    }

    /**
     * Draws one card from the draw pile for the active player.
     * Can only be performed once per turn.
     */
    public Optional<Card> drawCard(Player player) {
        if (!isGameStarted || isGameOver) {
            return Optional.empty();
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return Optional.empty();
        }
        if (hasDrawnThisTurn) {
            return Optional.empty();
        }

        Optional<Card> drawn = drawCardFromDeck();
        drawn.ifPresent(card -> {
            player.getHand().addCard(card);
            this.hasDrawnThisTurn = true;
        });

        return drawn;
    }

    /**
     * Passes the turn after drawing a card.
     */
    public boolean passTurn(Player player) {
        if (!isGameStarted || isGameOver) {
            return false;
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return false;
        }
        if (!hasDrawnThisTurn) {
            return false; // In Ultimo, player may only pass after drawing
        }

        turnManager.advanceTurn();
        this.hasDrawnThisTurn = false;
        return true;
    }

    /**
     * Creates an immutable snapshot of current game state for serialization and broadcasting.
     */
    public GameState captureSnapshot() {
        Map<String, Integer> cardCounts = new LinkedHashMap<>();
        for (Player p : players) {
            cardCounts.put(p.getUsername(), p.getHand().size());
        }

        return new GameState(
                turnManager.getCurrentPlayer().getUsername(),
                discardPile.getTopCard().orElse(null),
                discardPile.getActiveColor(),
                turnManager.getDirection(),
                Collections.unmodifiableMap(cardCounts),
                deck.size(),
                isGameOver,
                winner != null ? winner.getUsername() : null
        );
    }

    /**
     * Calculates final round scores. The winner receives points corresponding
     * to the sum of cards remaining in all opponents' hands.
     */
    public Optional<GameResult> getResult() {
        if (!isGameOver || winner == null) {
            return Optional.empty();
        }

        int totalWinnerPoints = 0;
        Map<String, Integer> scores = new LinkedHashMap<>();

        for (Player p : players) {
            if (!p.equals(winner)) {
                totalWinnerPoints += p.getHand().calculateTotalScore();
                scores.put(p.getUsername(), 0);
            }
        }
        scores.put(winner.getUsername(), totalWinnerPoints);

        return Optional.of(new GameResult(winner, Collections.unmodifiableMap(scores)));
    }

    private void applyCardAction(Card card) {
        switch (card.value()) {
            case SKIP -> turnManager.skipTurn();
            case REVERSE -> {
                if (turnManager.isTwoPlayerGame()) {
                    turnManager.skipTurn(); // In 2-player Ultimo, Reverse acts as a Skip
                } else {
                    turnManager.reverseDirection();
                    turnManager.advanceTurn();
                }
            }
            case DRAW_TWO -> {
                Player victim = turnManager.peekNextPlayer();
                drawCardsFor(victim, 2);
                turnManager.skipTurn();
            }
            case WILD_DRAW_FOUR -> {
                Player victim = turnManager.peekNextPlayer();
                drawCardsFor(victim, 4);
                turnManager.skipTurn();
            }
            case WILD, ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE -> turnManager.advanceTurn();
        }
    }

    private void drawCardsFor(Player target, int count) {
        for (int i = 0; i < count; i++) {
            drawCardFromDeck().ifPresent(target.getHand()::addCard);
        }
    }

    private Optional<Card> drawCardFromDeck() {
        if (deck.isEmpty()) {
            List<Card> recycled = discardPile.extractCardsForReplenish();
            deck.replenishFromDiscard(recycled);
        }
        return deck.draw();
    }

    public List<Player> getPlayers() {
        return players;
    }

    public TurnManager getTurnManager() {
        return turnManager;
    }

    public DiscardPile getDiscardPile() {
        return discardPile;
    }

    public Deck getDeck() {
        return deck;
    }

    public boolean isGameStarted() {
        return isGameStarted;
    }

    public boolean isGameOver() {
        return isGameOver;
    }

    public Player getWinner() {
        return winner;
    }

    public boolean hasDrawnThisTurn() {
        return hasDrawnThisTurn;
    }
}
