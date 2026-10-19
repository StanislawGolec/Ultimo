package pl.ultimo.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.protocol.ServerEvent;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Stage 12: Multi-Tier Placement (Podium) & 6-Card ULTIMO Catch Penalty Tests")
class UltimoPodiumAndCatchTest {

    private Player alice;
    private Player bob;
    private Player charlie;
    private Player david;

    @BeforeEach
    void setUp() {
        alice = new Player("p-1", "Alice");
        bob = new Player("p-2", "Bob");
        charlie = new Player("p-3", "Charlie");
        david = new Player("p-4", "David");
    }

    @Test
    @DisplayName("testFourPlayerMatchContinuationUntilOneRemaining: 4 players continue until only 1 remains with full podium rankings")
    void testFourPlayerMatchContinuationUntilOneRemaining() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie, david), new Random(42));
        engine.start();

        // Ensure order is Alice -> Bob -> Charlie -> David
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername());
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        // 1. Alice sheds her last card -> 1st Place!
        alice.getHand().clear();
        Card aliceWin = new Card(activeColor, CardValue.ONE);
        alice.getHand().addCard(aliceWin);

        UltimoEngine.PlayResult r1 = engine.playCard(alice, aliceWin, null);
        assertTrue(r1.success(), "Alice play must succeed");
        assertFalse(r1.isGameOver(), "Game must NOT terminate while 3 active players remain");
        assertTrue(alice.isFinished(), "Alice must be marked finished");
        assertEquals(1, alice.getPlacementRank(), "Alice should be awarded 1st place");
        assertEquals(3, engine.getActivePlayersCount(), "3 active players should remain");
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn should advance to Bob");

        // 2. Bob sheds his last card -> 2nd Place!
        bob.getHand().clear();
        Card bobWin = new Card(aliceWin.color(), CardValue.TWO);
        bob.getHand().addCard(bobWin);

        UltimoEngine.PlayResult r2 = engine.playCard(bob, bobWin, null);
        assertTrue(r2.success(), "Bob play must succeed");
        assertFalse(r2.isGameOver(), "Game must NOT terminate while 2 active players remain");
        assertTrue(bob.isFinished(), "Bob must be marked finished");
        assertEquals(2, bob.getPlacementRank(), "Bob should be awarded 2nd place");
        assertEquals(2, engine.getActivePlayersCount(), "2 active players should remain");
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn should advance to Charlie");

        // 3. Charlie sheds his last card -> 3rd Place! David is assigned 4th place (loser), game concludes!
        charlie.getHand().clear();
        Card charlieWin = new Card(bobWin.color(), CardValue.THREE);
        charlie.getHand().addCard(charlieWin);

        UltimoEngine.PlayResult r3 = engine.playCard(charlie, charlieWin, null);
        assertTrue(r3.success(), "Charlie play must succeed");
        assertTrue(r3.isGameOver(), "Game must conclude when only 1 active player remains");
        assertTrue(charlie.isFinished(), "Charlie must be marked finished");
        assertEquals(3, charlie.getPlacementRank(), "Charlie should be awarded 3rd place");

        // David (the remaining active player) gets 4th place
        assertTrue(david.isFinished(), "David must be marked finished as last-man-standing");
        assertEquals(4, david.getPlacementRank(), "David should be awarded 4th place (loser)");
        assertTrue(engine.isGameOver(), "Engine state must be GAME_OVER");
        assertEquals(alice, engine.getWinner(), "Alice is the 1st place winner");

        // Verify match standings list
        List<ServerEvent.Placement> standings = engine.getMatchStandings();
        assertEquals(4, standings.size(), "Match standings must have all 4 placements");
        assertEquals(new ServerEvent.Placement(1, "Alice"), standings.get(0));
        assertEquals(new ServerEvent.Placement(2, "Bob"), standings.get(1));
        assertEquals(new ServerEvent.Placement(3, "Charlie"), standings.get(2));
        assertEquals(new ServerEvent.Placement(4, "David"), standings.get(3));

        // Verify snapshot standings
        assertEquals(4, engine.captureSnapshot().standings().size());
    }

    @Test
    @DisplayName("testUltimoCatchDrawsSixCards: Caught player draws 6 penalty cards bringing hand up to 7 cards")
    void testUltimoCatchDrawsSixCards() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player challenger = current.equals(alice) ? bob : alice;
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        // Player plays down to 1 card without saying ULTIMO
        current.getHand().clear();
        Card cardToPlay = new Card(activeColor, CardValue.FOUR);
        Card cardToKeep = new Card(CardColor.YELLOW, CardValue.SEVEN);
        current.getHand().addCard(cardToPlay);
        current.getHand().addCard(cardToKeep);

        UltimoEngine.PlayResult playResult = engine.playCard(current, cardToPlay, null);
        assertTrue(playResult.success());
        assertEquals(1, current.getHand().size());
        assertEquals(current.getUsername(), engine.getPendingUltimoViolator());

        // Challenger catches them before their next move
        UltimoEngine.CatchResult catchResult = engine.catchUltimo(challenger, current.getUsername());
        assertTrue(catchResult.success(), "Catch must succeed");
        assertEquals(6, catchResult.penaltyCards(), "Must draw exactly 6 penalty cards");
        assertEquals(current, catchResult.penalizedPlayer());
        assertEquals(7, current.getHand().size(), "Penalized player should now hold 1 + 6 = 7 cards");
        assertNull(engine.getPendingUltimoViolator(), "Pending violator status must be cleared");
    }

    @Test
    @DisplayName("testCatchWindowClosesOnNextMove: Opponent fails to catch before next player moves -> catch rejected")
    void testCatchWindowClosesOnNextMove() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        Player p1 = engine.getTurnManager().getCurrentPlayer();
        Player p2 = engine.getTurnManager().peekNextPlayer();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        p1.getHand().clear();
        Card cardToPlay = new Card(activeColor, CardValue.FIVE);
        Card cardToKeep = new Card(CardColor.BLUE, CardValue.SIX);
        p1.getHand().addCard(cardToPlay);
        p1.getHand().addCard(cardToKeep);

        engine.playCard(p1, cardToPlay, null);
        assertEquals(1, p1.getHand().size());
        assertEquals(p1.getUsername(), engine.getPendingUltimoViolator());

        // Player 2 takes their move by drawing a card, which closes the catch window
        engine.drawCard(p2);
        assertNull(engine.getPendingUltimoViolator(), "Catch window must close once next player moves");

        // Attempting to catch after next player's move must fail
        UltimoEngine.CatchResult catchResult = engine.catchUltimo(p2, p1.getUsername());
        assertFalse(catchResult.success(), "Catch attempt after turn progression must fail");
        assertTrue(catchResult.message().contains("expired"), "Message should mention expired window");
        assertEquals(1, p1.getHand().size(), "Hand size must remain 1");
    }

    @Test
    @DisplayName("Winning play with SKIP action card deflects block to next active player before winner exits rotation")
    void testWinningPlayWithSkipCardDeflectsBeforeExiting() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie, david));
        engine.start();

        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        // Alice plays SKIP as winning card
        alice.getHand().clear();
        Card skipCard = new Card(activeColor, CardValue.SKIP);
        alice.getHand().addCard(skipCard);

        UltimoEngine.PlayResult r = engine.playCard(alice, skipCard, null);
        assertTrue(r.success());
        assertTrue(alice.isFinished());
        assertEquals(1, alice.getPlacementRank());

        // Bob should be skipped! Turn should advance past Bob to Charlie!
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername(),
                "Bob should be skipped by Alice's winning Skip card, landing on Charlie");
    }

    @Test
    @DisplayName("Winning play with DRAW_TWO forces next player to draw cards before winner exits rotation")
    void testWinningPlayWithDrawTwoForcesVictimDraw() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie, david));
        engine.start();

        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        int bobCardsBefore = bob.getHand().size();

        // Alice plays DRAW_TWO as winning card
        alice.getHand().clear();
        Card drawTwo = new Card(activeColor, CardValue.DRAW_TWO);
        alice.getHand().addCard(drawTwo);

        UltimoEngine.PlayResult r = engine.playCard(alice, drawTwo, null);
        assertTrue(r.success());
        assertTrue(alice.isFinished());
        assertEquals(1, alice.getPlacementRank());

        // Bob draws 2 cards and Bob's turn is skipped -> Charlie plays next
        assertEquals(bobCardsBefore + 2, bob.getHand().size(), "Bob should have drawn 2 cards");
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername(),
                "Bob's turn should be skipped after drawing penalty, landing on Charlie");
    }

    @Test
    @DisplayName("startNextRound clears podium placement state and resets player finish status")
    void testStartNextRoundResetsPodium() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        alice.getHand().clear();
        Card winCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        alice.getHand().addCard(winCard);

        engine.playCard(alice, winCard, null);
        assertTrue(engine.isGameOver());
        assertTrue(alice.isFinished());
        assertTrue(bob.isFinished());

        // Start next round
        assertTrue(engine.startNextRound());
        assertFalse(engine.isGameOver());
        assertFalse(alice.isFinished());
        assertFalse(bob.isFinished());
        assertEquals(0, alice.getPlacementRank());
        assertEquals(0, bob.getPlacementRank());
        assertTrue(engine.getMatchStandings().isEmpty());
    }
}
