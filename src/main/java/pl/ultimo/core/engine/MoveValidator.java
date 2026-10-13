package pl.ultimo.core.engine;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.model.PlayerHand;

import java.util.Optional;

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
