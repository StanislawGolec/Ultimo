package pl.ultimo.core.engine;

import pl.ultimo.core.model.Player;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable report representing the final outcome of an Ultimo game or round.
 *
 * @param winner the winning player
 * @param finalScores map of player username to their accumulated round points
 * @param roundPoints points earned in the finished round
 * @param cumulativeScores cumulative tournament score map
 * @param isTournamentWon true if target tournament score (e.g. 500) has been reached
 */
public record GameResult(
        Player winner,
        Map<String, Integer> finalScores,
        int roundPoints,
        Map<String, Integer> cumulativeScores,
        boolean isTournamentWon
) {
    public GameResult(Player winner, Map<String, Integer> finalScores) {
        this(winner, finalScores,
                (finalScores != null && winner != null) ? finalScores.getOrDefault(winner.getUsername(), 0) : 0,
                finalScores != null ? finalScores : Collections.emptyMap(),
                false);
    }

    public int getWinnerScore() {
        return (finalScores != null && winner != null)
                ? finalScores.getOrDefault(winner.getUsername(), 0)
                : 0;
    }
}
