package pl.ultimo.core.engine;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.model.PlayerHand;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure domain validator enforcing official Ultimo move legality rules.
 */
public final class MoveValidator {

    private MoveValidator() {
        // Utility class
    }

    /**
     * Checks if a card is legally playable against the active table state.
     *
     * @param candidateCard the card the player intends to play
     * @param topCard the top card on the discard pile
     * @param activeColor the required color (either topCard's color or chosen wild color)
     * @return true if the play is legally valid
     */
    public static boolean isValidPlay(Card candidateCard, Card topCard, CardColor activeColor) {
        if (candidateCard == null || topCard == null || activeColor == null) {
            return false;
        }

        if (candidateCard.isWild()) {
            return true;
        }

        return candidateCard.color() == activeColor || candidateCard.value() == topCard.value();
    }

    /**
     * Comprehensive validation of an attempted move by a player.
     *
     * @return empty Optional if valid, or an Optional containing a descriptive error reason
     */
    public static Optional<String> validateMove(
            Player player,
            Player activePlayer,
            Card card,
            Card topCard,
            CardColor activeColor,
            CardColor chosenWildColor) {

        if (!player.equals(activePlayer)) {
            return Optional.of("It is not your turn");
        }

        if (!player.getHand().contains(card)) {
            return Optional.of("You do not hold card: " + card);
        }

        if (card.isWild()) {
            if (chosenWildColor == null || !chosenWildColor.isStandardColor()) {
                return Optional.of("Must select a valid color (RED, YELLOW, GREEN, BLUE) for wild card");
            }
        } else if (!isValidPlay(card, topCard, activeColor)) {
            return Optional.of("Card " + card + " cannot be played on " + topCard + " (active color: " + activeColor + ")");
        }

        return Optional.empty();
    }

    /**
     * Comprehensive validation of an attempted multi-card combo move by a player.
     * All cards must share the same face value, the player must hold all cards in hand,
     * and at least one card in the combo must match the current table state.
     *
     * @return empty Optional if valid, or an Optional containing a descriptive error reason
     */
    public static Optional<String> validateMultiMove(
            Player player,
            Player activePlayer,
            List<Card> cards,
            Card topCard,
            CardColor activeColor,
            CardColor chosenWildColor) {

        if (!player.equals(activePlayer)) {
            return Optional.of("It is not your turn");
        }

        if (cards == null || cards.isEmpty()) {
            return Optional.of("No cards specified to play");
        }

        if (cards.size() == 1) {
            return validateMove(player, activePlayer, cards.get(0), topCard, activeColor, chosenWildColor);
        }

        // Verify player holds all specified cards (accounting for duplicates)
        Map<Card, Long> requiredCounts = cards.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<Card, Long> handCounts = player.getHand().getCards().stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        for (Map.Entry<Card, Long> entry : requiredCounts.entrySet()) {
            long inHand = handCounts.getOrDefault(entry.getKey(), 0L);
            if (inHand < entry.getValue()) {
                return Optional.of("You do not hold card: " + entry.getKey());
            }
        }

        // All cards in a combo must share the same card value
        CardValue firstValue = cards.get(0).value();
        boolean allSameValue = cards.stream().allMatch(c -> c.value() == firstValue);
        if (!allSameValue) {
            return Optional.of("All cards in a combo must share the same face value");
        }

        // At least one card in the combo must match the current table
        boolean canMatchTable = cards.stream().anyMatch(c -> isValidPlay(c, topCard, activeColor));
        if (!canMatchTable) {
            return Optional.of("None of the cards in the combo (" + cards + ") can be played on " + topCard + " (active color: " + activeColor + ")");
        }

        // Validate wild color declaration if any card in the combo is wild
        boolean hasWild = cards.stream().anyMatch(Card::isWild);
        if (hasWild) {
            if (chosenWildColor == null || !chosenWildColor.isStandardColor()) {
                return Optional.of("Must select a valid color (RED, YELLOW, GREEN, BLUE) for wild card");
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if a player has any legally playable cards in hand.
     */
    public static boolean hasPlayableCard(PlayerHand hand, Card topCard, CardColor activeColor) {
        if (hand == null || topCard == null || activeColor == null) {
            return false;
        }
        return hand.getCards().stream()
                .anyMatch(card -> isValidPlay(card, topCard, activeColor));
    }
}
