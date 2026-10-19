package pl.ultimo.core.bot;

import pl.ultimo.core.engine.MoveValidator;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.model.PlayerHand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure domain logic analyzer and heuristic AI bot player for Ultimo.
 * Evaluates hand cards against table state to make optimal, legal game moves:
 * - Emits CATCH_ULTIMO when opponents hold 1 card without declaring ULTIMO.
 * - Emits DECLARE_ULTIMO when bot hand reaches 1 card.
 * - Deflects incoming Skips with SKIP card combos.
 * - Stacks Draw Two / Wild Draw Four penalties when action defending is active.
 * - Prioritizes multi-card face value combos, then action cards, then color matches.
 * - Selects the most frequent remaining color when playing Wild cards.
 * - Returns DRAW (or PASS after drawing) when no moves are available.
 */
public class HeuristicBotEngine {

    public static final HeuristicBotEngine INSTANCE = new HeuristicBotEngine();

    public HeuristicBotEngine() {
        // Stateless heuristic engine
    }

    /**
     * Autonomous decision evaluator taking full game engine context.
     * Evaluates in priority:
     * 1. Immediate catch of opponents with 1 card who have not declared ULTIMO.
     * 2. ULTIMO declaration if bot holds 1 card and has not declared.
     * 3. Defense against incoming Skip deflection or Draw penalty stacking.
     * 4. Regular turn card play, draw, or pass.
     */
    public BotDecision decide(UltimoEngine engine, Player bot) {
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(bot, "bot must not be null");

        // 1. Check if any opponent can be caught for failing to declare ULTIMO
        Optional<BotDecision> catchDecision = checkCatchUltimo(engine, bot);
        if (catchDecision.isPresent()) {
            return catchDecision.get();
        }

        // 2. Check if bot holds 1 card and needs to declare ULTIMO
        if (bot.getHand().size() == 1 && !engine.hasDeclaredUltimo(bot)) {
            return BotDecision.declareUltimo();
        }

        // 3. If targeted by a Wild Draw Four in classic mode (no stacking), draw to accept penalty
        if (engine.getPendingWildChallenge() != null
                && bot.equals(engine.getPendingWildChallenge().victim())
                && !engine.isActionDefendingEnabled()) {
            return BotDecision.draw();
        }

        Card topCard = engine.getDiscardPile().getTopCard().orElse(null);
        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        int pendingDrawPenalty = engine.getPendingDrawPenalty();
        CardValue pendingDrawType = engine.getPendingDrawType();
        boolean pendingSkipDeflection = engine.isPendingSkipDeflection();
        boolean hasDrawnThisTurn = engine.hasDrawnThisTurn();
        boolean declaredUltimo = engine.hasDeclaredUltimo(bot);

        return decide(
                bot.getHand(),
                topCard,
                activeColor,
                pendingDrawPenalty,
                pendingDrawType,
                pendingSkipDeflection,
                hasDrawnThisTurn,
                declaredUltimo
        );
    }

    /**
     * Full signature evaluator operating directly on state POJOs.
     */
    public BotDecision decide(
            PlayerHand hand,
            Card topCard,
            CardColor activeColor,
            int pendingDrawPenalty,
            CardValue pendingDrawType,
            boolean pendingSkipDeflection,
            boolean hasDrawnThisTurn,
            boolean declaredUltimo
    ) {
        Objects.requireNonNull(hand, "hand must not be null");

        // 1. Check if hand has exactly 1 card and ULTIMO has not been declared
        if (hand.size() == 1 && !declaredUltimo) {
            return BotDecision.declareUltimo();
        }

        // 2. Check Skip Deflection
        if (pendingSkipDeflection) {
            return evaluateSkipDeflection(hand, hasDrawnThisTurn);
        }

        // 3. Check Draw Penalty Stacking
        if (pendingDrawPenalty > 0) {
            return evaluateDrawStacking(hand, topCard, pendingDrawType, hasDrawnThisTurn);
        }

        // 4. Regular Turn Move Evaluation
        return evaluateRegularTurn(hand, topCard, activeColor, hasDrawnThisTurn);
    }

    /**
     * Convenience overload for evaluating moves with draw penalty and skip deflection flags.
     */
    public BotDecision decide(
            PlayerHand hand,
            Card topCard,
            CardColor activeColor,
            int pendingDrawPenalty,
            boolean pendingSkipDeflection
    ) {
        return decide(hand, topCard, activeColor, pendingDrawPenalty, null, pendingSkipDeflection, false, false);
    }

    /**
     * Convenience overload for standard turn evaluation without pending action penalties.
     */
    public BotDecision decide(PlayerHand hand, Card topCard, CardColor activeColor) {
        return decide(hand, topCard, activeColor, 0, null, false, false, false);
    }

    /**
     * Checks if an opponent has 1 card without declaring ULTIMO and emits CATCH_ULTIMO.
     */
    public Optional<BotDecision> checkCatchUltimo(UltimoEngine engine, Player bot) {
        if (engine == null || bot == null || !engine.isGameStarted() || engine.isGameOver()) {
            return Optional.empty();
        }
        String violator = engine.getPendingUltimoViolator();
        if (violator != null && !violator.equalsIgnoreCase(bot.getUsername())) {
            return Optional.of(BotDecision.catchUltimo(violator));
        }
        return Optional.empty();
    }

    /**
     * Checks opponents list for any player holding 1 card who failed to declare ULTIMO.
     */
    public Optional<BotDecision> checkCatchUltimo(
            List<Player> players,
            String pendingUltimoViolator,
            Set<String> declaredUltimo,
            String botUsername
    ) {
        if (pendingUltimoViolator != null && !pendingUltimoViolator.equalsIgnoreCase(botUsername)) {
            return Optional.of(BotDecision.catchUltimo(pendingUltimoViolator));
        }
        if (players != null) {
            for (Player p : players) {
                if (!p.getUsername().equalsIgnoreCase(botUsername) && p.getHand().size() == 1) {
                    if (declaredUltimo == null || !declaredUltimo.contains(p.getUsername())) {
                        return Optional.of(BotDecision.catchUltimo(p.getUsername()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if the bot should declare ULTIMO.
     */
    public boolean shouldDeclareUltimo(PlayerHand hand, boolean alreadyDeclared) {
        return hand != null && hand.size() == 1 && !alreadyDeclared;
    }

    /**
     * Determines whether playing the specified decision cards will leave exactly 1 card in hand.
     */
    public boolean willLeaveOneCard(PlayerHand hand, BotDecision decision) {
        if (hand == null || decision == null || decision.action() != BotAction.PLAY) {
            return false;
        }
        return (hand.size() - decision.cards().size()) == 1;
    }

    /**
     * Evaluates Skip Deflection: checks for any held SKIP cards.
     * Playing multiple SKIP cards deflects the skip and skips subsequent players.
     */
    private BotDecision evaluateSkipDeflection(PlayerHand hand, boolean hasDrawnThisTurn) {
        List<Card> skipCards = hand.getCards().stream()
                .filter(c -> c.value() == CardValue.SKIP)
                .toList();

        if (!skipCards.isEmpty()) {
            return BotDecision.play(skipCards, null);
        }

        return hasDrawnThisTurn ? BotDecision.pass() : BotDecision.draw();
    }

    /**
     * Evaluates Draw Stacking: checks for matching DRAW_TWO or WILD_DRAW_FOUR cards.
     */
    private BotDecision evaluateDrawStacking(
            PlayerHand hand,
            Card topCard,
            CardValue pendingDrawType,
            boolean hasDrawnThisTurn
    ) {
        CardValue drawType = pendingDrawType;
        if (drawType == null && topCard != null) {
            drawType = topCard.value();
        }

        // If stacked on WILD_DRAW_FOUR, can only stack WILD_DRAW_FOUR
        if (drawType == CardValue.WILD_DRAW_FOUR) {
            List<Card> wildFours = hand.getCards().stream()
                    .filter(c -> c.value() == CardValue.WILD_DRAW_FOUR)
                    .toList();

            if (!wildFours.isEmpty()) {
                CardColor chosenColor = selectMostFrequentColor(hand, wildFours);
                return BotDecision.play(wildFours, chosenColor);
            }
            return hasDrawnThisTurn ? BotDecision.pass() : BotDecision.draw();
        }

        // For DRAW_TWO penalty: prefer matching DRAW_TWO cards, then WILD_DRAW_FOUR
        List<Card> drawTwos = hand.getCards().stream()
                .filter(c -> c.value() == CardValue.DRAW_TWO)
                .toList();

        if (!drawTwos.isEmpty()) {
            return BotDecision.play(drawTwos, null);
        }

        List<Card> wildFours = hand.getCards().stream()
                .filter(c -> c.value() == CardValue.WILD_DRAW_FOUR)
                .toList();

        if (!wildFours.isEmpty()) {
            CardColor chosenColor = selectMostFrequentColor(hand, wildFours);
            return BotDecision.play(wildFours, chosenColor);
        }

        return hasDrawnThisTurn ? BotDecision.pass() : BotDecision.draw();
    }

    /**
     * Evaluates regular turn plays with strict priority hierarchy:
     * 1. Multi-card combos of matching face value (size >= 2).
     * 2. Action cards (DRAW_TWO > SKIP > REVERSE).
     * 3. Color matches (highest point value number cards).
     * 4. Value matches (highest point value number cards matching topCard value).
     * 5. Wild cards (standard WILD preferred over WILD_DRAW_FOUR).
     * 6. Draw (or Pass if already drawn).
     */
    private BotDecision evaluateRegularTurn(
            PlayerHand hand,
            Card topCard,
            CardColor activeColor,
            boolean hasDrawnThisTurn
    ) {
        if (hand.isEmpty()) {
            return BotDecision.pass();
        }

        CardColor effectiveColor = activeColor != null ? activeColor : (topCard != null ? topCard.color() : CardColor.RED);

        // --- Priority 1: Multi-card combos of matching face value (size >= 2) ---
        Optional<List<Card>> bestCombo = findBestMultiCardCombo(hand, topCard, effectiveColor);
        if (bestCombo.isPresent()) {
            List<Card> combo = bestCombo.get();
            boolean hasWild = combo.stream().anyMatch(Card::isWild);
            CardColor wildColor = hasWild ? selectMostFrequentColor(hand, combo) : null;
            return BotDecision.play(combo, wildColor);
        }

        // --- Priority 2: Action cards (DRAW_TWO > SKIP > REVERSE) ---
        Optional<Card> bestActionCard = findBestPlayableActionCard(hand, topCard, effectiveColor);
        if (bestActionCard.isPresent()) {
            return BotDecision.play(bestActionCard.get());
        }

        // --- Priority 3: Color matches (single number cards matching active table color) ---
        Optional<Card> bestColorMatch = findBestColorMatch(hand, effectiveColor);
        if (bestColorMatch.isPresent()) {
            return BotDecision.play(bestColorMatch.get());
        }

        // --- Priority 4: Value matches (number cards matching topCard value) ---
        if (topCard != null) {
            Optional<Card> bestValueMatch = findBestValueMatch(hand, topCard);
            if (bestValueMatch.isPresent()) {
                return BotDecision.play(bestValueMatch.get());
            }
        }

        // --- Priority 5: Wild cards (WILD preferred over WILD_DRAW_FOUR) ---
        Optional<Card> wildCard = findPlayableWildCard(hand);
        if (wildCard.isPresent()) {
            Card wild = wildCard.get();
            CardColor chosenColor = selectMostFrequentColor(hand, List.of(wild));
            return BotDecision.play(wild, chosenColor);
        }

        // --- Priority 6: No moves available -> Draw (or Pass after drawing) ---
        return hasDrawnThisTurn ? BotDecision.pass() : BotDecision.draw();
    }

    /**
     * Finds the optimal multi-card combo of matching face value.
     * Orders the returned combo so that the card legally matching the table is placed first (initiator).
     */
    private Optional<List<Card>> findBestMultiCardCombo(PlayerHand hand, Card topCard, CardColor activeColor) {
        Map<CardValue, List<Card>> groups = new LinkedHashMap<>();
        for (Card card : hand.getCards()) {
            groups.computeIfAbsent(card.value(), k -> new ArrayList<>()).add(card);
        }

        List<List<Card>> validCombos = new ArrayList<>();
        for (Map.Entry<CardValue, List<Card>> entry : groups.entrySet()) {
            List<Card> cardsOfValue = entry.getValue();
            if (cardsOfValue.size() >= 2) {
                // Find if any card in this group satisfies MoveValidator.isValidPlay
                int matchIndex = -1;
                for (int i = 0; i < cardsOfValue.size(); i++) {
                    if (MoveValidator.isValidPlay(cardsOfValue.get(i), topCard, activeColor)) {
                        matchIndex = i;
                        break;
                    }
                }

                if (matchIndex != -1) {
                    List<Card> orderedCombo = new ArrayList<>(cardsOfValue);
                    if (matchIndex != 0) {
                        Card initiator = orderedCombo.remove(matchIndex);
                        orderedCombo.add(0, initiator);
                    }
                    validCombos.add(orderedCombo);
                }
            }
        }

        if (validCombos.isEmpty()) {
            return Optional.empty();
        }

        // Sort combos: largest size first, then non-wild over wild, then highest point value
        validCombos.sort((a, b) -> {
            if (b.size() != a.size()) {
                return Integer.compare(b.size(), a.size());
            }
            boolean aWild = a.get(0).isWild();
            boolean bWild = b.get(0).isWild();
            if (aWild != bWild) {
                return aWild ? 1 : -1; // non-wild first
            }
            int pointsA = a.stream().mapToInt(c -> c.value().getPointValue()).sum();
            int pointsB = b.stream().mapToInt(c -> c.value().getPointValue()).sum();
            return Integer.compare(pointsB, pointsA);
        });

        return Optional.of(validCombos.get(0));
    }

    /**
     * Finds the best playable single action card (DRAW_TWO > SKIP > REVERSE).
     */
    private Optional<Card> findBestPlayableActionCard(PlayerHand hand, Card topCard, CardColor activeColor) {
        List<Card> playableActions = hand.getCards().stream()
                .filter(c -> c.value().isAction())
                .filter(c -> MoveValidator.isValidPlay(c, topCard, activeColor))
                .toList();

        if (playableActions.isEmpty()) {
            return Optional.empty();
        }

        // Preference order: DRAW_TWO > SKIP > REVERSE
        return playableActions.stream().min(Comparator.comparingInt(c -> switch (c.value()) {
            case DRAW_TWO -> 1;
            case SKIP -> 2;
            case REVERSE -> 3;
            default -> 4;
        }));
    }

    /**
     * Finds the best playable single number card matching the active table color (highest point value first).
     */
    private Optional<Card> findBestColorMatch(PlayerHand hand, CardColor activeColor) {
        return hand.getCards().stream()
                .filter(c -> !c.isWild() && c.value().isNumber())
                .filter(c -> c.color() == activeColor)
                .max(Comparator.comparingInt(c -> c.value().getPointValue()));
    }

    /**
     * Finds the best playable single number card matching the topCard value (highest point value first).
     */
    private Optional<Card> findBestValueMatch(PlayerHand hand, Card topCard) {
        return hand.getCards().stream()
                .filter(c -> !c.isWild() && c.value().isNumber())
                .filter(c -> c.value() == topCard.value())
                .max(Comparator.comparingInt(c -> c.value().getPointValue()));
    }

    /**
     * Finds a playable wild card, preferring standard WILD over WILD_DRAW_FOUR to avoid challenges.
     */
    private Optional<Card> findPlayableWildCard(PlayerHand hand) {
        Optional<Card> regularWild = hand.getCards().stream()
                .filter(c -> c.value() == CardValue.WILD)
                .findFirst();
        if (regularWild.isPresent()) {
            return regularWild;
        }

        return hand.getCards().stream()
                .filter(c -> c.value() == CardValue.WILD_DRAW_FOUR)
                .findFirst();
    }

    /**
     * Selects the most frequent color in the bot's remaining hand after excluding the cards being played.
     * Ties are broken deterministically in standard order (RED > YELLOW > GREEN > BLUE).
     */
    public CardColor selectMostFrequentColor(PlayerHand hand, List<Card> cardsBeingPlayed) {
        if (hand == null) {
            return CardColor.RED;
        }

        List<Card> remaining = new ArrayList<>(hand.getCards());
        if (cardsBeingPlayed != null) {
            for (Card c : cardsBeingPlayed) {
                remaining.remove(c);
            }
        }

        Map<CardColor, Integer> counts = new EnumMap<>(CardColor.class);
        counts.put(CardColor.RED, 0);
        counts.put(CardColor.YELLOW, 0);
        counts.put(CardColor.GREEN, 0);
        counts.put(CardColor.BLUE, 0);

        for (Card c : remaining) {
            if (!c.isWild() && c.color().isStandardColor()) {
                counts.put(c.color(), counts.get(c.color()) + 1);
            }
        }

        CardColor best = CardColor.RED;
        int maxCount = -1;
        for (CardColor color : List.of(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)) {
            int count = counts.get(color);
            if (count > maxCount) {
                maxCount = count;
                best = color;
            }
        }

        return best;
    }

    /**
     * Convenience overload evaluating color frequency across entire hand.
     */
    public CardColor selectMostFrequentColor(PlayerHand hand) {
        return selectMostFrequentColor(hand, List.of());
    }
}
