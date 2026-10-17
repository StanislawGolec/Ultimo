package pl.ultimo.core.engine;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.Deck;
import pl.ultimo.core.model.DiscardPile;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.GameState;
import pl.ultimo.core.state.TurnManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Authoritative game engine orchestrating Ultimo rules, turn progression, and card resolution.
 * Supports official rules: ULTIMO declarations and penalty challenges, Wild Draw Four legality challenges,
 * and multi-round tournament 500-point scoring.
 */
public class UltimoEngine {

    public static final int STARTING_HAND_SIZE = 7;
    public static final int DEFAULT_TARGET_SCORE = 500;

    private final List<Player> players;
    private final Random random;
    private final int targetScore;
    private final TurnManager turnManager;

    private Deck deck;
    private DiscardPile discardPile;

    private boolean isGameStarted;
    private boolean isGameOver;
    private boolean hasDrawnThisTurn;
    private Player winner;

    // Ultimo declaration state tracking
    private final Set<String> ultimoDeclared = new HashSet<>();
    private String pendingUltimoViolator = null;

    // Wild Draw Four challenge state tracking
    private PendingWildFourChallenge pendingWildChallenge = null;

    // Multi-round tournament state tracking
    private final Map<String, Integer> cumulativeScores = new LinkedHashMap<>();
    private boolean isTournamentWon = false;
    private Player tournamentWinner = null;
    private int lastRoundPointsEarned = 0;

    public record PlayResult(
            boolean success,
            String errorMessage,
            List<Card> playedCards,
            boolean isUltimo,
            boolean isGameOver,
            Player winner
    ) {
        public Card playedCard() {
            return playedCards != null && !playedCards.isEmpty()
                    ? playedCards.get(playedCards.size() - 1)
                    : null;
        }

        public static PlayResult failure(String errorMessage) {
            return new PlayResult(false, errorMessage, List.of(), false, false, null);
        }

        public static PlayResult success(List<Card> cards, boolean isUltimo, boolean isGameOver, Player winner) {
            return new PlayResult(true, null, cards, isUltimo, isGameOver, winner);
        }

        public static PlayResult success(Card card, boolean isUltimo, boolean isGameOver, Player winner) {
            return new PlayResult(true, null, List.of(card), isUltimo, isGameOver, winner);
        }
    }

    public record CatchResult(boolean success, String message, Player penalizedPlayer, int penaltyCards) {}

    public record PendingWildFourChallenge(Player offender, Player victim, boolean isGuilty, int cardCount) {}

    public record WildChallengeResult(boolean success, String message, boolean isGuilty, Player penalizedPlayer, int cardsDrawn) {}

    public UltimoEngine(List<Player> players) {
        this(players, new Random(), DEFAULT_TARGET_SCORE);
    }

    public UltimoEngine(List<Player> players, Random random) {
        this(players, random, DEFAULT_TARGET_SCORE);
    }

    public UltimoEngine(List<Player> players, Random random, int targetScore) {
        if (players == null || players.size() < 2) {
            throw new IllegalArgumentException("At least 2 players are required to start Ultimo");
        }
        this.players = List.copyOf(players);
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.targetScore = targetScore > 0 ? targetScore : DEFAULT_TARGET_SCORE;
        this.deck = Deck.createStandardDeck(random);
        this.discardPile = new DiscardPile();
        this.turnManager = new TurnManager(this.players);

        for (Player p : this.players) {
            this.cumulativeScores.put(p.getUsername(), 0);
        }
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
     * Starts a new round in a multi-round tournament, retaining cumulative scores.
     */
    public boolean startNextRound() {
        if (!isGameOver && isGameStarted) {
            return false;
        }
        if (isTournamentWon) {
            return false;
        }

        this.deck = Deck.createStandardDeck(random);
        this.discardPile = new DiscardPile();

        for (Player p : players) {
            p.getHand().clear();
        }

        for (int i = 0; i < STARTING_HAND_SIZE; i++) {
            for (Player player : players) {
                drawCardFromDeck().ifPresent(player.getHand()::addCard);
            }
        }

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
        this.isGameOver = false;
        this.hasDrawnThisTurn = false;
        this.winner = null;
        this.pendingWildChallenge = null;
        this.pendingUltimoViolator = null;
        this.ultimoDeclared.clear();

        return true;
    }

    /**
     * Attempts to play a single card from the active player's hand.
     */
    public PlayResult playCard(Player player, Card card, CardColor chosenWildColor) {
        return playCards(player, List.of(card), chosenWildColor);
    }

    /**
     * Attempts to play one or more cards (multi-card combo) from the active player's hand.
     */
    public PlayResult playCards(Player player, List<Card> cards, CardColor chosenWildColor) {
        if (!isGameStarted) {
            return PlayResult.failure("Game has not started yet");
        }
        if (isGameOver) {
            return PlayResult.failure("Game is already over");
        }
        if (cards == null || cards.isEmpty()) {
            return PlayResult.failure("No cards specified to play");
        }

        if (pendingWildChallenge != null && player.equals(pendingWildChallenge.victim())) {
            return PlayResult.failure("Must either challenge the Wild Draw Four or accept the penalty cards (draw/pass)");
        }

        // When a new turn starts, window to catch previous Ultimo violator closes
        if (pendingUltimoViolator != null && !player.getUsername().equalsIgnoreCase(pendingUltimoViolator)) {
            pendingUltimoViolator = null;
        }

        Card topCard = discardPile.getTopCard().orElse(null);
        CardColor activeColor = discardPile.getActiveColor();

        Optional<String> validationError = MoveValidator.validateMultiMove(
                player,
                turnManager.getCurrentPlayer(),
                cards,
                topCard,
                activeColor,
                chosenWildColor
        );

        if (validationError.isPresent()) {
            return PlayResult.failure(validationError.get());
        }

        // Check if playing a Wild Draw Four was legal (held no matching table color)
        boolean hasWildFour = cards.stream().anyMatch(c -> c.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR);
        boolean heldMatchingColorBeforePlay = false;
        if (hasWildFour) {
            heldMatchingColorBeforePlay = player.getHand().getCards().stream()
                    .anyMatch(c -> !c.isWild() && c.color() == activeColor);
        }

        // Determine card play order: a card that matches table must be placed first as initiator
        List<Card> orderedCards = new ArrayList<>(cards);
        if (!MoveValidator.isValidPlay(orderedCards.get(0), topCard, activeColor)) {
            for (int i = 1; i < orderedCards.size(); i++) {
                if (MoveValidator.isValidPlay(orderedCards.get(i), topCard, activeColor)) {
                    Card initiator = orderedCards.remove(i);
                    orderedCards.add(0, initiator);
                    break;
                }
            }
        }

        // Remove cards from player hand and place onto discard pile in order
        for (Card card : orderedCards) {
            player.getHand().removeCard(card);
            CardColor effectiveColor = card.isWild() ? chosenWildColor : card.color();
            discardPile.playCard(card, effectiveColor);
        }

        // Check if player has won the round
        if (player.getHand().isEmpty()) {
            Card lastCard = orderedCards.get(orderedCards.size() - 1);
            if (lastCard.value() == pl.ultimo.core.model.CardValue.DRAW_TWO) {
                drawCardsFor(turnManager.peekNextPlayer(), 2 * orderedCards.size());
            } else if (lastCard.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR) {
                drawCardsFor(turnManager.peekNextPlayer(), 4 * orderedCards.size());
            }
            this.isGameOver = true;
            this.winner = player;
            tallyRoundScores(player);
            return PlayResult.success(orderedCards, false, true, player);
        }

        boolean isUltimo = player.getHand().size() == 1;
        if (isUltimo) {
            if (!ultimoDeclared.contains(player.getUsername())) {
                pendingUltimoViolator = player.getUsername();
            }
        } else {
            ultimoDeclared.remove(player.getUsername());
        }

        // Apply combo action effects
        applyComboAction(player, orderedCards, heldMatchingColorBeforePlay);

        this.hasDrawnThisTurn = false;
        return PlayResult.success(orderedCards, isUltimo, false, null);
    }

    /**
     * Declares "ULTIMO!" for a player holding 1 or 2 cards.
     */
    public boolean declareUltimo(Player player) {
        if (!isGameStarted || isGameOver) {
            return false;
        }
        if (player.getHand().size() > 2) {
            return false;
        }

        ultimoDeclared.add(player.getUsername());
        if (player.getUsername().equalsIgnoreCase(pendingUltimoViolator)) {
            pendingUltimoViolator = null;
        }
        return true;
    }

    public boolean hasDeclaredUltimo(Player player) {
        return ultimoDeclared.contains(player.getUsername());
    }

    /**
     * An opponent calls out a player with 1 card who failed to declare ULTIMO.
     */
    public CatchResult catchUltimo(Player challenger, String targetUsername) {
        if (!isGameStarted || isGameOver) {
            return new CatchResult(false, "Game is not active", null, 0);
        }
        if (targetUsername == null || targetUsername.isBlank()) {
            return new CatchResult(false, "Target username cannot be empty", null, 0);
        }

        Player target = players.stream()
                .filter(p -> p.getUsername().equalsIgnoreCase(targetUsername.trim()))
                .findFirst()
                .orElse(null);

        if (target == null) {
            return new CatchResult(false, "Player not found: " + targetUsername, null, 0);
        }
        if (target.equals(challenger)) {
            return new CatchResult(false, "Cannot catch yourself", null, 0);
        }
        if (target.getHand().size() != 1) {
            return new CatchResult(false, target.getUsername() + " does not have exactly 1 card (has " + target.getHand().size() + ")", null, 0);
        }
        if (ultimoDeclared.contains(target.getUsername())) {
            return new CatchResult(false, target.getUsername() + " already declared ULTIMO", null, 0);
        }
        if (!target.getUsername().equalsIgnoreCase(pendingUltimoViolator)) {
            return new CatchResult(false, "Window to catch " + target.getUsername() + " has expired (turn has progressed)", null, 0);
        }

        // Penalize target with 2 cards
        drawCardsFor(target, 2);
        ultimoDeclared.remove(target.getUsername());
        pendingUltimoViolator = null;

        return new CatchResult(true, target.getUsername() + " was caught without saying ULTIMO and drew 2 penalty cards!", target, 2);
    }

    /**
     * Victim challenges the legality of the previous Wild Draw Four.
     */
    public WildChallengeResult challengeWildFour(Player challenger) {
        if (!isGameStarted || isGameOver) {
            return new WildChallengeResult(false, "Game is not active", false, null, 0);
        }
        if (pendingWildChallenge == null) {
            return new WildChallengeResult(false, "No active Wild Draw Four to challenge", false, null, 0);
        }
        if (!challenger.equals(pendingWildChallenge.victim())) {
            return new WildChallengeResult(false, "Only the targeted victim can challenge Wild Draw Four", false, null, 0);
        }

        PendingWildFourChallenge challenge = this.pendingWildChallenge;
        this.pendingWildChallenge = null;

        if (challenge.isGuilty()) {
            // Offender held matching color -> Offender draws 4 cards! Victim does not draw and turn is not skipped
            drawCardsFor(challenge.offender(), challenge.cardCount());
            return new WildChallengeResult(true,
                    challenge.offender().getUsername() + " played Wild Draw Four illegally (held matching color)! Drew "
                            + challenge.cardCount() + " cards.",
                    true, challenge.offender(), challenge.cardCount());
        } else {
            // Offender was innocent -> Challenger draws 4 + 2 penalty cards = 6 cards, and forfeits turn
            int penaltyCards = challenge.cardCount() + 2;
            drawCardsFor(challenge.victim(), penaltyCards);
            turnManager.advanceTurn(1);
            return new WildChallengeResult(true,
                    challenge.offender().getUsername() + " played Wild Draw Four legally! "
                            + challenger.getUsername() + " drew " + penaltyCards + " cards (4 + 2 penalty) and skipped turn.",
                    false, challenger, penaltyCards);
        }
    }

    public boolean acceptWildFour(Player player) {
        if (!isGameStarted || isGameOver) {
            return false;
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return false;
        }
        return resolvePendingWildChallengeIfAny(player);
    }

    private boolean resolvePendingWildChallengeIfAny(Player activePlayer) {
        if (pendingWildChallenge != null) {
            if (activePlayer.equals(pendingWildChallenge.victim())) {
                drawCardsFor(pendingWildChallenge.victim(), pendingWildChallenge.cardCount());
                pendingWildChallenge = null;
                turnManager.advanceTurn(1);
                return true;
            }
        }
        return false;
    }

    /**
     * Draws one card from the draw pile for the active player.
     */
    public Optional<Card> drawCard(Player player) {
        if (!isGameStarted || isGameOver) {
            return Optional.empty();
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return Optional.empty();
        }

        if (resolvePendingWildChallengeIfAny(player)) {
            return Optional.empty();
        }

        if (hasDrawnThisTurn) {
            return Optional.empty();
        }

        // When a turn starts, window to catch previous Ultimo violator closes
        if (pendingUltimoViolator != null && !player.getUsername().equalsIgnoreCase(pendingUltimoViolator)) {
            pendingUltimoViolator = null;
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

        if (resolvePendingWildChallengeIfAny(player)) {
            return true;
        }

        if (!hasDrawnThisTurn) {
            return false;
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

    private void tallyRoundScores(Player roundWinner) {
        int points = 0;
        for (Player p : players) {
            if (!p.equals(roundWinner)) {
                points += p.getHand().calculateTotalScore();
            }
        }
        this.lastRoundPointsEarned = points;

        int currentTotal = cumulativeScores.getOrDefault(roundWinner.getUsername(), 0) + points;
        cumulativeScores.put(roundWinner.getUsername(), currentTotal);

        if (currentTotal >= targetScore) {
            this.isTournamentWon = true;
            this.tournamentWinner = roundWinner;
        }
    }

    /**
     * Calculates final round scores. The winner receives points corresponding
     * to the sum of cards remaining in all opponents' hands.
     */
    public Optional<GameResult> getResult() {
        if (!isGameOver || winner == null) {
            return Optional.empty();
        }

        Map<String, Integer> roundScores = new LinkedHashMap<>();
        for (Player p : players) {
            if (!p.equals(winner)) {
                roundScores.put(p.getUsername(), 0);
            }
        }
        roundScores.put(winner.getUsername(), lastRoundPointsEarned);

        return Optional.of(new GameResult(
                winner,
                Collections.unmodifiableMap(roundScores),
                lastRoundPointsEarned,
                Collections.unmodifiableMap(cumulativeScores),
                isTournamentWon
        ));
    }

    private void applyComboAction(Player player, List<Card> cards, boolean wasIllegalWildFour) {
        int count = cards.size();
        Card lastCard = cards.get(cards.size() - 1);
        switch (lastCard.value()) {
            case SKIP -> turnManager.advanceTurn(1 + count);
            case REVERSE -> {
                if (turnManager.isTwoPlayerGame()) {
                    if (count % 2 == 1) {
                        turnManager.advanceTurn(2);
                    } else {
                        turnManager.advanceTurn(1);
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        turnManager.reverseDirection();
                    }
                    turnManager.advanceTurn(1);
                }
            }
            case DRAW_TWO -> {
                Player victim = turnManager.peekNextPlayer();
                drawCardsFor(victim, 2 * count);
                turnManager.skipTurn();
            }
            case WILD_DRAW_FOUR -> {
                Player victim = turnManager.peekNextPlayer();
                // Set pending challenge instead of immediately drawing to give victim the option to challenge
                this.pendingWildChallenge = new PendingWildFourChallenge(player, victim, wasIllegalWildFour, 4 * count);
                turnManager.advanceTurn(1);
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

    public int getTargetScore() {
        return targetScore;
    }

    public Map<String, Integer> getCumulativeScores() {
        return Collections.unmodifiableMap(cumulativeScores);
    }

    public boolean isTournamentWon() {
        return isTournamentWon;
    }

    public Player getTournamentWinner() {
        return tournamentWinner;
    }

    public PendingWildFourChallenge getPendingWildChallenge() {
        return pendingWildChallenge;
    }

    public String getPendingUltimoViolator() {
        return pendingUltimoViolator;
    }
}
