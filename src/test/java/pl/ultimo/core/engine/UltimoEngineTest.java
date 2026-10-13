package pl.ultimo.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.Direction;
import pl.ultimo.core.state.GameState;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UltimoEngine Domain Unit Tests")
class UltimoEngineTest {

    private Player alice;
    private Player bob;
    private Player charlie;

    @BeforeEach
    void setUp() {
        alice = new Player("1", "Alice");
        bob = new Player("2", "Bob");
        charlie = new Player("3", "Charlie");
    }

    @Test
    @DisplayName("Starting the game deals 7 cards to each player and places one top card on discard pile")
    void startingGameDealsSevenCardsAndSetsTopCard() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        assertEquals(7, alice.getHand().size());
        assertEquals(7, bob.getHand().size());
        assertTrue(engine.isGameStarted());
        assertFalse(engine.isGameOver());

        assertTrue(engine.getDiscardPile().getTopCard().isPresent());
        assertNotNull(engine.getDiscardPile().getActiveColor());

        // 108 - 14 dealt - 1 on discard pile = 93
        assertEquals(93, engine.getDeck().size());
    }

    @Test
    @DisplayName("Playing a regular number card advances turn to the next player")
    void playingNumberCardAdvancesTurn() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Card activeTop = engine.getDiscardPile().getTopCard().orElseThrow();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        // Give current player a matching card
        Card matchingCard = new Card(activeColor, CardValue.EIGHT);
        current.getHand().addCard(matchingCard);

        int handSizeBefore = current.getHand().size();
        UltimoEngine.PlayResult result = engine.playCard(current, matchingCard, null);

        assertTrue(result.success());
        assertEquals(handSizeBefore - 1, current.getHand().size());
        assertEquals(matchingCard, engine.getDiscardPile().getTopCard().orElseThrow());

        // Turn should have advanced
        assertNotEquals(current, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Playing a Skip card skips the next player")
    void playingSkipCardSkipsNextPlayer() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        // Ensure Alice is current player
        Player current = engine.getTurnManager().getCurrentPlayer();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        Card skipCard = new Card(activeColor, CardValue.SKIP);
        current.getHand().addCard(skipCard);

        Player shouldBeSkipped = engine.getTurnManager().peekNextPlayer(1);
        Player expectedNext = engine.getTurnManager().peekNextPlayer(2);

        UltimoEngine.PlayResult result = engine.playCard(current, skipCard, null);
        assertTrue(result.success());

        assertEquals(expectedNext, engine.getTurnManager().getCurrentPlayer());
        assertNotEquals(shouldBeSkipped, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Playing a Reverse card switches direction in 3-player match")
    void playingReverseCardSwitchesDirection() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        Card reverseCard = new Card(activeColor, CardValue.REVERSE);
        current.getHand().addCard(reverseCard);

        assertEquals(Direction.CLOCKWISE, engine.getTurnManager().getDirection());
        UltimoEngine.PlayResult result = engine.playCard(current, reverseCard, null);

        assertTrue(result.success());
        assertEquals(Direction.COUNTER_CLOCKWISE, engine.getTurnManager().getDirection());
    }

    @Test
    @DisplayName("In 2-player match, Reverse card acts as a Skip card (turn returns to player)")
    void playingReverseInTwoPlayerActsAsSkip() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        Card reverseCard = new Card(activeColor, CardValue.REVERSE);
        current.getHand().addCard(reverseCard);

        UltimoEngine.PlayResult result = engine.playCard(current, reverseCard, null);
        assertTrue(result.success());

        // In 2-player Ultimo, Reverse allows the player to play again
        assertEquals(current, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Playing Draw Two card forces next player to draw 2 cards and skip their turn")
    void playingDrawTwoForcesVictimToDrawTwoAndSkip() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player victim = engine.getTurnManager().peekNextPlayer();
        Player afterVictim = engine.getTurnManager().peekNextPlayer(2);

        int victimHandBefore = victim.getHand().size();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        Card drawTwoCard = new Card(activeColor, CardValue.DRAW_TWO);
        current.getHand().addCard(drawTwoCard);

        UltimoEngine.PlayResult result = engine.playCard(current, drawTwoCard, null);
        assertTrue(result.success());

        // Victim received 2 cards
        assertEquals(victimHandBefore + 2, victim.getHand().size());

        // Victim was skipped, turn went to afterVictim
        assertEquals(afterVictim, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Playing Wild card changes active color on the table")
    void playingWildCardChangesActiveColor() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Card wildCard = new Card(CardColor.WILD, CardValue.WILD);
        current.getHand().addCard(wildCard);

        UltimoEngine.PlayResult result = engine.playCard(current, wildCard, CardColor.BLUE);
        assertTrue(result.success());

        assertEquals(CardColor.BLUE, engine.getDiscardPile().getActiveColor());
        assertEquals(wildCard, engine.getDiscardPile().getTopCard().orElseThrow());
    }

    @Test
    @DisplayName("Player can draw a card, then pass their turn")
    void playerCanDrawAndPass() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        int handBefore = current.getHand().size();

        // Player cannot pass before drawing
        assertFalse(engine.passTurn(current));

        // Player draws 1 card
        Optional<Card> drawn = engine.drawCard(current);
        assertTrue(drawn.isPresent());
        assertEquals(handBefore + 1, current.getHand().size());
        assertTrue(engine.hasDrawnThisTurn());

        // Player cannot draw a second card in the same turn
        assertTrue(engine.drawCard(current).isEmpty());

        // Player can now legally pass
        assertTrue(engine.passTurn(current));
        assertFalse(engine.hasDrawnThisTurn());
        assertNotEquals(current, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Playing the last card declares game over and calculates winner score")
    void playingLastCardWinsAndCalculatesScore() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player opponent = engine.getTurnManager().peekNextPlayer();

        // Empty current player's hand and give them exactly 1 playable card
        current.getHand().clear();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        Card lastCard = new Card(activeColor, CardValue.THREE);
        current.getHand().addCard(lastCard);

        // Give opponent known cards for score verification
        opponent.getHand().clear();
        opponent.getHand().addCard(new Card(CardColor.RED, CardValue.FIVE)); // 5 pts
        opponent.getHand().addCard(new Card(CardColor.RED, CardValue.SKIP)); // 20 pts
        opponent.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD)); // 50 pts
        // Total opponent points = 75

        UltimoEngine.PlayResult result = engine.playCard(current, lastCard, null);
        assertTrue(result.success());
        assertTrue(result.isGameOver());
        assertEquals(current, result.winner());
        assertTrue(engine.isGameOver());
        assertEquals(current, engine.getWinner());

        // Verify score calculation
        Optional<GameResult> gameResult = engine.getResult();
        assertTrue(gameResult.isPresent());
        assertEquals(75, gameResult.get().getWinnerScore());
    }

    @Test
    @DisplayName("Capture snapshot returns complete immutable GameState")
    void captureSnapshotReturnsAccurateState() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        GameState snapshot = engine.captureSnapshot();
        assertNotNull(snapshot);
        assertNotNull(snapshot.activePlayerName());
        assertNotNull(snapshot.topCard());
        assertNotNull(snapshot.activeColor());
        assertEquals(engine.getTurnManager().getDirection(), snapshot.direction());
        assertEquals(2, snapshot.opponentCardCounts().size());
        assertEquals(alice.getHand().size(), snapshot.opponentCardCounts().get("Alice"));
        assertEquals(bob.getHand().size(), snapshot.opponentCardCounts().get("Bob"));
        assertFalse(snapshot.isGameOver());
        assertNull(snapshot.winnerName());
    }
}
