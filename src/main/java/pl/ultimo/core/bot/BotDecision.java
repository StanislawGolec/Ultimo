package pl.ultimo.core.bot;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;

import java.util.List;
import java.util.Objects;

/**
 * Immutable decision record emitted by the heuristic AI bot engine.
 *
 * @param action the chosen action to execute
 * @param cards the cards selected to play (for PLAY action)
 * @param chosenWildColor the color declared when playing a Wild card (null if not wild)
 * @param targetPlayer the username of the target player (for CATCH_ULTIMO)
 */
public record BotDecision(
        BotAction action,
        List<Card> cards,
        CardColor chosenWildColor,
        String targetPlayer
) {
    public BotDecision {
        Objects.requireNonNull(action, "action must not be null");
        cards = cards != null ? List.copyOf(cards) : List.of();
    }

    public static BotDecision play(List<Card> cards, CardColor chosenWildColor) {
        return new BotDecision(BotAction.PLAY, cards, chosenWildColor, null);
    }

    public static BotDecision play(List<Card> cards) {
        return new BotDecision(BotAction.PLAY, cards, null, null);
    }

    public static BotDecision play(Card card, CardColor chosenWildColor) {
        return new BotDecision(BotAction.PLAY, List.of(card), chosenWildColor, null);
    }

    public static BotDecision play(Card card) {
        return new BotDecision(BotAction.PLAY, List.of(card), null, null);
    }

    public static BotDecision draw() {
        return new BotDecision(BotAction.DRAW, List.of(), null, null);
    }

    public static BotDecision pass() {
        return new BotDecision(BotAction.PASS, List.of(), null, null);
    }

    public static BotDecision declareUltimo() {
        return new BotDecision(BotAction.DECLARE_ULTIMO, List.of(), null, null);
    }

    public static BotDecision catchUltimo(String targetPlayer) {
        return new BotDecision(BotAction.CATCH_ULTIMO, List.of(), null, targetPlayer);
    }

    public static BotDecision challengeWildFour() {
        return new BotDecision(BotAction.CHALLENGE_WILD_FOUR, List.of(), null, null);
    }
}
