package pl.ultimo.core.state;

import pl.ultimo.core.model.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages cyclic player rotation, skips, and direction reversals according to official Ultimo rules.
 */
public class TurnManager {

    private final List<Player> players;
    private int currentIndex;
    private Direction direction;

    public TurnManager(List<Player> players) {
        if (players == null || players.size() < 2) {
            throw new IllegalArgumentException("At least 2 players are required for Ultimo");
        }
        this.players = new ArrayList<>(players);
        this.currentIndex = 0;
        this.direction = Direction.CLOCKWISE;
    }

    public Player getCurrentPlayer() {
        return players.get(currentIndex);
    }

    /**
     * Peeks at the next player in the rotation without advancing the turn.
     */
    public Player peekNextPlayer() {
        return peekNextPlayer(1);
    }

    /**
     * Peeks at the player N steps ahead in the rotation without modifying turn index.
     */
    public Player peekNextPlayer(int steps) {
        return players.get(calculateNextIndex(steps));
    }

    /**
     * Advances the turn to the next player in the current direction.
     */
    public Player advanceTurn() {
        return advanceTurn(1);
    }

    /**
     * Advances the turn by a specified number of steps in the current direction.
     */
    public Player advanceTurn(int steps) {
        currentIndex = calculateNextIndex(steps);
        return getCurrentPlayer();
    }

    /**
     * Skips the next player, advancing the turn to the player after them.
     */
    public Player skipTurn() {
        return advanceTurn(2);
    }

    /**
     * Reverses play rotation direction.
     * Note: In a 2-player match, Reverse behaves as a Skip (turn stays with the player after skip).
     */
    public void reverseDirection() {
        direction = direction.toggle();
    }

    public boolean isTwoPlayerGame() {
        return players.size() == 2;
    }

    public Direction getDirection() {
        return direction;
    }

    public List<Player> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public int getPlayerCount() {
        return players.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    private int calculateNextIndex(int steps) {
        int total = players.size();
        int delta = steps * direction.getStep();
        return (currentIndex + (delta % total) + total) % total;
    }
}
