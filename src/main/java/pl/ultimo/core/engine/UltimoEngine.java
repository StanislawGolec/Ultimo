package pl.ultimo.core.engine;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Deck;
import pl.ultimo.core.model.DiscardPile;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.GameState;
import pl.ultimo.core.state.TurnManager;
import pl.ultimo.protocol.ServerEvent;

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
    // Multi-tier podium and placement tracking
    private int nextRank = 1;
    private final List<Player> finishedPlayers = new ArrayList<>();
    private final List<ServerEvent.Placement> matchStandings = new ArrayList<>();

    // Action Defending (Skip Deflection & Draw Stacking) and Auto-Pass state
    private boolean actionDefendingEnabled = false;
    private boolean autoPassEnabled = false;

    private boolean pendingSkipDeflection = false;
    private Player skipTarget = null;

    private int pendingDrawPenalty = 0;
    private CardValue pendingDrawType = null;
    private Player drawTarget = null;
    private DrawResult lastDrawResult = null;

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

    public record DrawResult(Optional<Card> card, boolean autoPassed, int cardsDrawn) {}

    public UltimoEngine(List<Player> players) {
        this(players, new Random(), DEFAULT_TARGET_SCORE, false, false);
    }

    public UltimoEngine(List<Player> players, boolean actionDefendingEnabled) {
        this(players, new Random(), DEFAULT_TARGET_SCORE, actionDefendingEnabled, true);
    }

    public UltimoEngine(List<Player> players, boolean actionDefendingEnabled, boolean autoPassEnabled) {
        this(players, new Random(), DEFAULT_TARGET_SCORE, actionDefendingEnabled, autoPassEnabled);
    }

    public UltimoEngine(List<Player> players, Random random) {
        this(players, random, DEFAULT_TARGET_SCORE, false, false);
    }

    public UltimoEngine(List<Player> players, Random random, int targetScore) {
        this(players, random, targetScore, false, false);
    }

    public UltimoEngine(List<Player> players, long seed) {
        this(players, new Random(seed), DEFAULT_TARGET_SCORE, false, false);
    }

    public UltimoEngine(List<Player> players, long seed, int targetScore) {
        this(players, new Random(seed), targetScore, false, false);
    }

    public UltimoEngine(List<Player> players, long seed, boolean actionDefendingEnabled, boolean autoPassEnabled) {
        this(players, new Random(seed), DEFAULT_TARGET_SCORE, actionDefendingEnabled, autoPassEnabled);
    }

    public UltimoEngine(List<Player> players, long seed, int targetScore, boolean actionDefendingEnabled, boolean autoPassEnabled) {
        this(players, new Random(seed), targetScore, actionDefendingEnabled, autoPassEnabled);
    }

    public static UltimoEngine createDeterministic(List<Player> players, long seed) {
        return new UltimoEngine(players, seed);
    }

    public static UltimoEngine createDeterministic(List<Player> players, long seed, int targetScore) {
        return new UltimoEngine(players, seed, targetScore);
    }

    public static UltimoEngine createDeterministic(List<Player> players, long seed, int targetScore, boolean actionDefendingEnabled, boolean autoPassEnabled) {
        return new UltimoEngine(players, seed, targetScore, actionDefendingEnabled, autoPassEnabled);
    }

    public UltimoEngine(List<Player> players, Random random, int targetScore, boolean actionDefendingEnabled, boolean autoPassEnabled) {
        if (players == null || players.size() < 2) {
            throw new IllegalArgumentException("At least 2 players are required to start Ultimo");
        }
        this.players = new ArrayList<>(players);
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.targetScore = targetScore > 0 ? targetScore : DEFAULT_TARGET_SCORE;
        this.deck = Deck.createDeckForPlayerCount(this.players.size(), random);
        this.discardPile = new DiscardPile();
        this.turnManager = new TurnManager(this.players);
        this.actionDefendingEnabled = actionDefendingEnabled;
        this.autoPassEnabled = autoPassEnabled;

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
        for (Map.Entry<String, Integer> entry : cumulativeScores.entrySet()) {
            if (entry.getValue() >= targetScore) {
                this.isTournamentWon = true;
                if (this.tournamentWinner == null) {
                    this.tournamentWinner = players.stream()
                            .filter(p -> p.getUsername().equals(entry.getKey()))
                            .findFirst()
                            .orElse(null);
                }
                return false;
            }
        }

        this.deck = Deck.createDeckForPlayerCount(this.players.size(), random);
        this.discardPile = new DiscardPile();
        this.turnManager.reset();

        this.nextRank = 1;
        this.finishedPlayers.clear();
        this.matchStandings.clear();
        for (Player p : players) {
            p.getHand().clear();
            p.setPlacementRank(0);
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

        Player previousWinner = this.winner;
        this.turnManager.reset(previousWinner != null ? previousWinner : players.getFirst());

        this.isGameStarted = true;
        this.isGameOver = false;
        this.hasDrawnThisTurn = false;
        this.winner = null;
        this.lastRoundPointsEarned = 0;
        this.pendingWildChallenge = null;
        this.pendingUltimoViolator = null;
        this.ultimoDeclared.clear();
        this.pendingSkipDeflection = false;
        this.skipTarget = null;
        this.pendingDrawPenalty = 0;
        this.pendingDrawType = null;
        this.drawTarget = null;
        this.lastDrawResult = null;

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
            boolean canStackWildFour = actionDefendingEnabled && cards.stream().allMatch(c -> c.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR);
            if (!canStackWildFour) {
                return PlayResult.failure("Must either challenge the Wild Draw Four or accept the penalty cards (draw/pass)");
            }
        }

        if (actionDefendingEnabled && pendingSkipDeflection && player.equals(skipTarget)) {
            boolean canDefend = cards.stream().allMatch(c -> c.value() == pl.ultimo.core.model.CardValue.SKIP);
            if (!canDefend) {
                acceptSkip(player);
                return PlayResult.failure("Must defend with a SKIP card or pass/draw to accept the skip; turn forfeited");
            }
        }

        if (actionDefendingEnabled && pendingDrawPenalty > 0 && player.equals(drawTarget)) {
            boolean canDefend = cards.stream().allMatch(c -> c.value() == pendingDrawType || c.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR);
            if (!canDefend) {
                int penalty = acceptDrawPenalty(player);
                return PlayResult.failure("Must defend with a " + pendingDrawType + " card or draw the accumulated penalty (" + penalty + " cards); turn forfeited");
            }
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

        // Check if player has shed their last card (podium finish)
        if (player.getHand().isEmpty()) {
            int rank = nextRank++;
            player.setPlacementRank(rank);
            finishedPlayers.add(player);
            matchStandings.add(new ServerEvent.Placement(rank, player.getUsername()));

            Card lastCard = orderedCards.get(orderedCards.size() - 1);
            int count = orderedCards.size();

            // Apply action effect to next active player before winner exits rotation
            if (lastCard.value() == pl.ultimo.core.model.CardValue.DRAW_TWO) {
                int penalty = 2 * count + (actionDefendingEnabled ? pendingDrawPenalty : 0);
                Player victim = turnManager.peekNextPlayer();
                drawCardsFor(victim, penalty);
                pendingDrawPenalty = 0;
                turnManager.skipTurn();
            } else if (lastCard.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR) {
                int penalty = 4 * count + (actionDefendingEnabled ? pendingDrawPenalty : 0);
                Player victim = turnManager.peekNextPlayer();
                drawCardsFor(victim, penalty);
                pendingDrawPenalty = 0;
                turnManager.skipTurn();
            } else if (lastCard.value() == pl.ultimo.core.model.CardValue.SKIP) {
                turnManager.advanceTurn(1 + count);
            } else if (lastCard.value() == pl.ultimo.core.model.CardValue.REVERSE) {
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
            } else {
                turnManager.advanceTurn(1);
            }

            if (this.winner == null) {
                this.winner = player;
                tallyRoundScores(player);
            }

            // Check if only 1 active player remains
            if (getActivePlayersCount() <= 1) {
                for (Player remaining : players) {
                    if (!remaining.isFinished()) {
                        int finalRank = nextRank++;
                        remaining.setPlacementRank(finalRank);
                        finishedPlayers.add(remaining);
                        matchStandings.add(new ServerEvent.Placement(finalRank, remaining.getUsername()));
                        break;
                    }
                }
                this.isGameOver = true;
                this.hasDrawnThisTurn = false;
                return PlayResult.success(orderedCards, false, true, this.winner);
            } else {
                this.hasDrawnThisTurn = false;
                return PlayResult.success(orderedCards, false, false, null);
            }
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
        if (player.getHand().size() > 2 || player.getHand().isEmpty()) {
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

        // Penalize target with 6 cards
        drawCardsFor(target, 6);
        ultimoDeclared.remove(target.getUsername());
        pendingUltimoViolator = null;

        return new CatchResult(true, target.getUsername() + " was caught without saying ULTIMO and drew 6 penalty cards!", target, 6);
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
        this.pendingDrawPenalty = 0;
        this.pendingDrawType = null;
        this.drawTarget = null;

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

    public boolean acceptSkip(Player player) {
        if (!isGameStarted || isGameOver) {
            return false;
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return false;
        }
        if (!pendingSkipDeflection || !player.equals(skipTarget)) {
            return false;
        }
        pendingSkipDeflection = false;
        skipTarget = null;
        turnManager.advanceTurn(1);
        this.hasDrawnThisTurn = false;
        return true;
    }

    public int acceptDrawPenalty(Player player) {
        if (!isGameStarted || isGameOver) {
            return 0;
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return 0;
        }
        if (pendingDrawPenalty <= 0 || !player.equals(drawTarget)) {
            return 0;
        }
        int count = pendingDrawPenalty;
        drawCardsFor(player, count);
        pendingDrawPenalty = 0;
        pendingDrawType = null;
        drawTarget = null;
        pendingWildChallenge = null;
        turnManager.advanceTurn(1);
        this.hasDrawnThisTurn = false;
        return count;
    }

    private boolean resolvePendingWildChallengeIfAny(Player activePlayer) {
        if (pendingWildChallenge != null) {
            if (activePlayer.equals(pendingWildChallenge.victim())) {
                drawCardsFor(pendingWildChallenge.victim(), pendingWildChallenge.cardCount());
                pendingWildChallenge = null;
                pendingDrawPenalty = 0;
                pendingDrawType = null;
                drawTarget = null;
                turnManager.advanceTurn(1);
                this.hasDrawnThisTurn = false;
                return true;
            }
        }
        return false;
    }

    /**
     * Draws one card from the draw pile with full status result (autoPass, penalty cards drawn).
     */
    public DrawResult drawCardWithResult(Player player) {
        if (!isGameStarted || isGameOver) {
            this.lastDrawResult = new DrawResult(Optional.empty(), false, 0);
            return this.lastDrawResult;
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            this.lastDrawResult = new DrawResult(Optional.empty(), false, 0);
            return this.lastDrawResult;
        }

        if (pendingWildChallenge != null && player.equals(pendingWildChallenge.victim())) {
            int count = pendingWildChallenge.cardCount();
            acceptWildFour(player);
            this.lastDrawResult = new DrawResult(Optional.empty(), false, count);
            return this.lastDrawResult;
        }

        if (actionDefendingEnabled && pendingDrawPenalty > 0 && player.equals(drawTarget)) {
            int count = acceptDrawPenalty(player);
            this.lastDrawResult = new DrawResult(Optional.empty(), false, count);
            return this.lastDrawResult;
        }

        if (actionDefendingEnabled && pendingSkipDeflection && player.equals(skipTarget)) {
            Optional<Card> drawn = drawCardFromDeck();
            drawn.ifPresent(player.getHand()::addCard);
            pendingSkipDeflection = false;
            skipTarget = null;
            turnManager.advanceTurn(1);
            this.hasDrawnThisTurn = false;
            this.lastDrawResult = new DrawResult(drawn, false, 1);
            return this.lastDrawResult;
        }

        if (hasDrawnThisTurn) {
            this.lastDrawResult = new DrawResult(Optional.empty(), false, 0);
            return this.lastDrawResult;
        }

        // When a turn starts, window to catch previous Ultimo violator closes
        if (pendingUltimoViolator != null && !player.getUsername().equalsIgnoreCase(pendingUltimoViolator)) {
            pendingUltimoViolator = null;
        }

        Optional<Card> drawn = drawCardFromDeck();
        if (drawn.isEmpty()) {
            this.lastDrawResult = new DrawResult(Optional.empty(), false, 0);
            return this.lastDrawResult;
        }

        player.getHand().addCard(drawn.get());
        this.hasDrawnThisTurn = true;

        if (autoPassEnabled) {
            Card topCard = discardPile.getTopCard().orElse(null);
            CardColor activeColor = discardPile.getActiveColor();
            boolean hasAnyPlayable = MoveValidator.hasPlayableCard(player.getHand(), topCard, activeColor);
            if (!hasAnyPlayable) {
                passTurn(player);
                this.lastDrawResult = new DrawResult(drawn, true, 1);
                return this.lastDrawResult;
            }
        }

        this.lastDrawResult = new DrawResult(drawn, false, 1);
        return this.lastDrawResult;
    }

    /**
     * Draws one card from the draw pile for the active player.
     */
    public Optional<Card> drawCard(Player player) {
        return drawCardWithResult(player).card();
    }

    public DrawResult draw(Player player) {
        return drawCardWithResult(player);
    }

    public DrawResult getLastDrawResult() {
        return lastDrawResult;
    }

    public boolean wasLastDrawAutoPassed() {
        return lastDrawResult != null && lastDrawResult.autoPassed();
    }

    /**
     * Passes the turn after drawing a card, or yields to a pending skip/draw penalty.
     */
    public boolean passTurn(Player player) {
        if (!isGameStarted || isGameOver) {
            return false;
        }
        if (!player.equals(turnManager.getCurrentPlayer())) {
            return false;
        }

        if (pendingWildChallenge != null && player.equals(pendingWildChallenge.victim())) {
            return acceptWildFour(player);
        }

        if (actionDefendingEnabled && pendingSkipDeflection && player.equals(skipTarget)) {
            return acceptSkip(player);
        }

        if (actionDefendingEnabled && pendingDrawPenalty > 0 && player.equals(drawTarget)) {
            acceptDrawPenalty(player);
            return true;
        }

        if (!hasDrawnThisTurn) {
            return false;
        }

        if (pendingUltimoViolator != null && !player.getUsername().equalsIgnoreCase(pendingUltimoViolator)) {
            pendingUltimoViolator = null;
        }

        turnManager.advanceTurn(1);
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
                winner != null ? winner.getUsername() : null,
                List.copyOf(matchStandings)
        );
    }

    public int tallyRoundScores(Player roundWinner) {
        if (roundWinner == null) {
            return 0;
        }
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
        return points;
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
            case SKIP -> {
                if (actionDefendingEnabled) {
                    this.pendingSkipDeflection = true;
                    this.skipTarget = turnManager.peekNextPlayer();
                    turnManager.advanceTurn(1);
                } else {
                    turnManager.advanceTurn(1 + count);
                }
            }
            case REVERSE -> {
                this.pendingSkipDeflection = false;
                this.skipTarget = null;
                this.pendingDrawPenalty = 0;
                this.pendingDrawType = null;
                this.drawTarget = null;
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
                if (actionDefendingEnabled) {
                    this.pendingDrawPenalty += 2 * count;
                    this.pendingDrawType = CardValue.DRAW_TWO;
                    this.drawTarget = turnManager.peekNextPlayer();
                    turnManager.advanceTurn(1);
                } else {
                    Player victim = turnManager.peekNextPlayer();
                    drawCardsFor(victim, 2 * count);
                    turnManager.skipTurn();
                }
            }
            case WILD_DRAW_FOUR -> {
                if (actionDefendingEnabled) {
                    this.pendingDrawPenalty += 4 * count;
                    this.pendingDrawType = CardValue.WILD_DRAW_FOUR;
                    this.drawTarget = turnManager.peekNextPlayer();
                    this.pendingWildChallenge = new PendingWildFourChallenge(player, drawTarget, wasIllegalWildFour, pendingDrawPenalty);
                    turnManager.advanceTurn(1);
                } else {
                    Player victim = turnManager.peekNextPlayer();
                    // Set pending challenge instead of immediately drawing to give victim the option to challenge
                    this.pendingWildChallenge = new PendingWildFourChallenge(player, victim, wasIllegalWildFour, 4 * count);
                    turnManager.advanceTurn(1);
                }
            }
            case WILD, ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE -> {
                this.pendingSkipDeflection = false;
                this.skipTarget = null;
                this.pendingDrawPenalty = 0;
                this.pendingDrawType = null;
                this.drawTarget = null;
                turnManager.advanceTurn();
            }
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

    public boolean isActionDefendingEnabled() {
        return actionDefendingEnabled;
    }

    public void setActionDefendingEnabled(boolean actionDefendingEnabled) {
        this.actionDefendingEnabled = actionDefendingEnabled;
    }

    public boolean isAutoPassEnabled() {
        return autoPassEnabled;
    }

    public void setAutoPassEnabled(boolean autoPassEnabled) {
        this.autoPassEnabled = autoPassEnabled;
    }

    public boolean isPendingSkipDeflection() {
        return pendingSkipDeflection;
    }

    public Player getSkipTarget() {
        return skipTarget;
    }

    public int getPendingDrawPenalty() {
        return pendingDrawPenalty;
    }

    public CardValue getPendingDrawType() {
        return pendingDrawType;
    }

    public Player getDrawTarget() {
        return drawTarget;
    }

    /**
     * Removes a player from the game engine (e.g. upon disconnect timeout or forfeit).
     * If the removed player was the active player, the turn advances to the next player.
     * If only one player remains, the game finishes with forfeit victory.
     *
     * @param player the player to remove
     * @return true if the removed player was currently taking their turn
     */
    public synchronized boolean removePlayer(Player player) {
        if (player == null) {
            return false;
        }
        boolean wasCurrent = turnManager.removePlayer(player);
        players.remove(player);
        ultimoDeclared.remove(player.getUsername());
        if (pendingUltimoViolator != null && pendingUltimoViolator.equalsIgnoreCase(player.getUsername())) {
            pendingUltimoViolator = null;
        }
        if (hasDrawnThisTurn && wasCurrent) {
            hasDrawnThisTurn = false;
        }
        if (players.size() <= 1 && isGameStarted && !isGameOver) {
            isGameOver = true;
            if (players.size() == 1) {
                winner = players.getFirst();
            }
        }
        return wasCurrent;
    }

    public int getActivePlayersCount() {
        return turnManager.getActivePlayerCount();
    }

    public List<Player> getFinishedPlayers() {
        return Collections.unmodifiableList(finishedPlayers);
    }

    public List<ServerEvent.Placement> getMatchStandings() {
        return Collections.unmodifiableList(matchStandings);
    }

    /**
     * Test harness setup: prepares hands and discard pile for tactical action defense testing
     * (Draw Stacking and Skip Deflection).
     */
    public synchronized void setupDefenseScenario() {
        if (!isGameStarted || players.size() < 4) {
            return;
        }
        this.deck = Deck.createDeckForPlayerCount(this.players.size(), random);
        this.discardPile = new DiscardPile();
        this.discardPile.playCard(new Card(CardColor.RED, CardValue.FIVE), CardColor.RED);
        this.pendingSkipDeflection = false;
        this.skipTarget = null;
        this.pendingDrawPenalty = 0;
        this.pendingDrawType = null;
        this.drawTarget = null;
        this.hasDrawnThisTurn = false;
        this.turnManager.reset(players.get(0));

        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.getHand().clear();
            p.setPlacementRank(0);
            if (i == 0) {
                p.getHand().addCard(new Card(CardColor.RED, CardValue.DRAW_TWO));
                p.getHand().addCard(new Card(CardColor.RED, CardValue.SKIP));
                p.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));
            } else if (i == 1) {
                p.getHand().addCard(new Card(CardColor.BLUE, CardValue.DRAW_TWO));
                p.getHand().addCard(new Card(CardColor.BLUE, CardValue.SKIP));
                p.getHand().addCard(new Card(CardColor.BLUE, CardValue.TWO));
            } else {
                p.getHand().addCard(new Card(CardColor.GREEN, CardValue.ONE));
                p.getHand().addCard(new Card(CardColor.YELLOW, CardValue.TWO));
            }
        }
    }

    /**
     * Test harness setup: prepares hands and discard pile for Last-Man-Standing podium verification.
     * All players are given exactly 1 playable card, and turn is reset to player 0.
     */
    public synchronized void setupPodiumScenario() {
        if (!isGameStarted || players.isEmpty()) {
            return;
        }
        this.discardPile = new DiscardPile();
        this.discardPile.playCard(new Card(CardColor.RED, CardValue.ONE), CardColor.RED);
        this.pendingSkipDeflection = false;
        this.skipTarget = null;
        this.pendingDrawPenalty = 0;
        this.pendingDrawType = null;
        this.drawTarget = null;
        this.hasDrawnThisTurn = false;
        this.nextRank = 1;
        this.finishedPlayers.clear();
        this.matchStandings.clear();
        this.winner = null;
        this.isGameOver = false;
        this.turnManager.reset(players.get(0));

        for (Player p : players) {
            p.getHand().clear();
            p.setPlacementRank(0);
            p.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));
        }
    }
}

