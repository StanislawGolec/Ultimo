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

    public int getActivePlayerCount() {
        int count = 0;
        for (Player p : players) {
            if (!p.isFinished()) {
                count++;
            }
        }
        return count;
    }

    public List<Player> getActivePlayers() {
        List<Player> active = new ArrayList<>();
        for (Player p : players) {
            if (!p.isFinished()) {
                active.add(p);
            }
        }
        return Collections.unmodifiableList(active);
    }

    public boolean isTwoPlayerGame() {
        return getActivePlayerCount() == 2;
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

    /**
     * Resets turn rotation back to the first player and clockwise direction for a new round.
     */
    public synchronized void reset() {
        this.currentIndex = 0;
        this.direction = Direction.CLOCKWISE;
    }

    /**
     * Resets turn rotation to the specified starting player and clockwise direction for a new round.
     */
    public synchronized void reset(Player startingPlayer) {
        this.direction = Direction.CLOCKWISE;
        int idx = (startingPlayer != null) ? players.indexOf(startingPlayer) : 0;
        this.currentIndex = (idx != -1) ? idx : 0;
    }

    /**
     * Removes a player from the rotation (e.g. upon disconnect timeout or forfeit).
     * Adjusts the current index and advances the turn to the next player if the removed player was current.
     *
     * @param player the player to remove
     * @return true if the removed player was currently taking their turn
     */
    public synchronized boolean removePlayer(Player player) {
        if (player == null) {
            return false;
        }
        int index = players.indexOf(player);
        if (index == -1) {
            return false;
        }
        boolean wasCurrent = (index == currentIndex);
        players.remove(index);
        if (players.isEmpty()) {
            currentIndex = 0;
            return wasCurrent;
        }
        if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            if (direction == Direction.COUNTER_CLOCKWISE) {
                currentIndex = (currentIndex - 1 + players.size()) % players.size();
            } else {
                currentIndex = currentIndex % players.size();
            }
        }
        return wasCurrent;
    }

    private int calculateNextIndex(int steps) {
        if (players.isEmpty()) {
            return 0;
        }
        int activeCount = getActivePlayerCount();
        if (activeCount == 0) {
            return currentIndex % players.size();
        }
        if (activeCount == 1) {
            for (int i = 0; i < players.size(); i++) {
                if (!players.get(i).isFinished()) {
                    return i;
                }
            }
            return currentIndex % players.size();
        }

        int total = players.size();
        int stepDir = direction.getStep();
        int curr = currentIndex;
        int activeStepsCount = 0;

        while (activeStepsCount < steps) {
            curr = (curr + stepDir + total) % total;
            if (!players.get(curr).isFinished()) {
                activeStepsCount++;
            }
        }
        return curr;
    }
}

