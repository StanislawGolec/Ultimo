package pl.ultimo.core.engine;

import pl.ultimo.core.model.Player;

import java.util.Map;

/**
 * Immutable report representing the final outcome of an Ultimo game.
 *
 * @param winner the winning player
 * @param finalScores map of player username to their accumulated round points
 */
public record GameResult(
        Player winner,
        Map<String, Integer> finalScores
) {
    public int getWinnerScore() {
        return (finalScores != null && winner != null)
                ? finalScores.getOrDefault(winner.getUsername(), 0)
                : 0;
    }
}
