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

    @Test
    @DisplayName("Removing current player advances turn to next active player")
    void removingCurrentPlayerAdvancesTurn() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");
        Player charlie = new Player("3", "Charlie");

        TurnManager turnManager = new TurnManager(List.of(alice, bob, charlie));
        assertEquals("Alice", turnManager.getCurrentPlayer().getUsername());

        // Remove Alice who is current
        boolean wasCurrent = turnManager.removePlayer(alice);
        assertTrue(wasCurrent);
        assertEquals(2, turnManager.getPlayerCount());
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());

        // Now Bob takes turn and passes to Charlie
        turnManager.advanceTurn();
        assertEquals("Charlie", turnManager.getCurrentPlayer().getUsername());

        // Remove Charlie who is current
        boolean charlieWasCurrent = turnManager.removePlayer(charlie);
        assertTrue(charlieWasCurrent);
        assertEquals(1, turnManager.getPlayerCount());
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("Removing non-current player preserves active player turn")
    void removingNonCurrentPlayerPreservesActiveTurn() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");
        Player charlie = new Player("3", "Charlie");

        TurnManager turnManager = new TurnManager(List.of(alice, bob, charlie));
        turnManager.advanceTurn(); // Now Bob is current
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());

        // Remove Alice (who is at index 0, behind current)
        boolean wasCurrent = turnManager.removePlayer(alice);
        assertFalse(wasCurrent);
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());
        assertEquals(2, turnManager.getPlayerCount());

        // Remove Charlie (who is ahead of current)
        boolean charlieWasCurrent = turnManager.removePlayer(charlie);
        assertFalse(charlieWasCurrent);
        assertEquals("Bob", turnManager.getCurrentPlayer().getUsername());
        assertEquals(1, turnManager.getPlayerCount());
    }
}
