package pl.ultimo.core.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Player;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurnManager Domain Unit Tests")
class TurnManagerTest {

    @Test
    @DisplayName("Turn should advance sequentially in clockwise direction")
    void turnShouldAdvanceClockwise() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");
        Player charlie = new Player("3", "Charlie");

        TurnManager turnManager = new TurnManager(List.of(alice, bob, charlie));
        assertEquals("Alice", turnManager.getCurrentPlayer().getUsername());

        turnManager.advanceTurn();
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());

        turnManager.advanceTurn();
        assertEquals("Charlie", turnManager.getCurrentPlayer().getUsername());

        turnManager.advanceTurn();
        assertEquals("Alice", turnManager.getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("Skip turn should skip next player and give turn to player after")
    void skipTurnShouldSkipPlayer() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");
        Player charlie = new Player("3", "Charlie");

        TurnManager turnManager = new TurnManager(List.of(alice, bob, charlie));
        assertEquals("Alice", turnManager.getCurrentPlayer().getUsername());

        turnManager.skipTurn();
        assertEquals("Charlie", turnManager.getCurrentPlayer().getUsername(), "Bob should have been skipped");
    }

    @Test
    @DisplayName("Reverse direction should switch from clockwise to counter-clockwise")
    void reverseDirectionShouldCycleBackwards() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");
        Player charlie = new Player("3", "Charlie");

        TurnManager turnManager = new TurnManager(List.of(alice, bob, charlie));
        assertEquals(Direction.CLOCKWISE, turnManager.getDirection());

        turnManager.reverseDirection();
        assertEquals(Direction.COUNTER_CLOCKWISE, turnManager.getDirection());

        // Moving counter-clockwise from Alice (index 0) in 3 players lands on Charlie (index 2)
        turnManager.advanceTurn();
        assertEquals("Charlie", turnManager.getCurrentPlayer().getUsername());

        turnManager.advanceTurn();
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("Peek next player should not modify current player index")
    void peekNextPlayerShouldNotChangeTurn() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");

        TurnManager turnManager = new TurnManager(List.of(alice, bob));
        assertEquals("Alice", turnManager.getCurrentPlayer().getUsername());

        Player peeked = turnManager.peekNextPlayer();
        assertEquals("Bob", peeked.getUsername());
        assertEquals("Alice", turnManager.getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("TurnManager requires at least two players")
    void shouldThrowWhenFewerThanTwoPlayers() {
        assertThrows(IllegalArgumentException.class, () -> new TurnManager(List.of(new Player("1", "Solo"))));
        assertThrows(IllegalArgumentException.class, () -> new TurnManager(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TurnManager(null));
    }
}
