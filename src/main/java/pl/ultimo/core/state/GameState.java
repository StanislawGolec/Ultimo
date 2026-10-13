package pl.ultimo.core.state;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;

import java.util.Map;

/**
 * Immutable snapshot of the game state suitable for broadcasting or inspection.
 *
 * @param activePlayerName username of the player whose turn it currently is
 * @param topCard top card currently visible on the discard pile
 * @param activeColor effective color required for the next play (e.g. declared wild color)
 * @param direction current direction of play
 * @param opponentCardCounts map of player username to number of cards held
 * @param deckCardCount remaining cards in draw deck
 * @param isGameOver whether the match has completed
 * @param winnerName username of the winner, if game is over
 */
public record GameState(
        String activePlayerName,
        Card topCard,
        CardColor activeColor,
        Direction direction,
        Map<String, Integer> opponentCardCounts,
        int deckCardCount,
        boolean isGameOver,
        String winnerName
) {}
