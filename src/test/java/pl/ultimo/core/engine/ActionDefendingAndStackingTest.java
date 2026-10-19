package pl.ultimo.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 Domain Unit Test Suite:
 * Action Card Defending (Skip Deflection), Card Stacking (+2 / +4), and Auto-Pass Engine.
 */
@DisplayName("Stage 11: Action Defending, Card Stacking & Auto-Pass Unit Tests")
class ActionDefendingAndStackingTest {

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
    @DisplayName("testSkipDeflectionChain: Player 1 plays Skip, Player 2 deflects with Skip, Player 3 has no Skip and is skipped")
    void testSkipDeflectionChain() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), true, true);
        engine.start();

        // Discard pile matches RED
        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        // 1. Alice plays RED SKIP
        Card aliceSkip = new Card(CardColor.RED, CardValue.SKIP);
        alice.getHand().addCard(aliceSkip);

        UltimoEngine.PlayResult r1 = engine.playCard(alice, aliceSkip, null);
        assertTrue(r1.success(), "Alice playing Skip must succeed");
        assertTrue(engine.isPendingSkipDeflection(), "Skip deflection should be pending");
        assertEquals("Bob", engine.getSkipTarget().getUsername(), "Bob must be the targeted victim");
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn should move to Bob to allow defense");

        // 2. Bob defends by playing BLUE SKIP
        Card bobSkip = new Card(CardColor.BLUE, CardValue.SKIP);
        bob.getHand().addCard(bobSkip);

        UltimoEngine.PlayResult r2 = engine.playCard(bob, bobSkip, null);
        assertTrue(r2.success(), "Bob playing Skip to deflect must succeed");
        assertTrue(engine.isPendingSkipDeflection(), "Skip deflection should still be active and forwarded");
        assertEquals("Charlie", engine.getSkipTarget().getUsername(), "Charlie should now be the targeted victim");
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn should move to Charlie");

        // 3. Charlie has no Skip and accepts the skip
        boolean accepted = engine.acceptSkip(charlie);
        assertTrue(accepted, "Charlie accepting skip should succeed");
        assertFalse(engine.isPendingSkipDeflection(), "Skip deflection should be consumed");
        assertNull(engine.getSkipTarget(), "Skip target should be reset");

        // Turn advances to Alice
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn should advance to Alice after Charlie is skipped");
    }

    @Test
    @DisplayName("testSkipDeflectionFailsWithNonSkipCard: Player under skip attempts non-Skip move -> forfeits turn")
    void testSkipDeflectionFailsWithNonSkipCard() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        Card aliceSkip = new Card(CardColor.RED, CardValue.SKIP);
        alice.getHand().addCard(aliceSkip);
        engine.playCard(alice, aliceSkip, null);

        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Bob attempts to play RED SEVEN instead of a SKIP
        Card bobSeven = new Card(CardColor.RED, CardValue.SEVEN);
        bob.getHand().addCard(bobSeven);

        UltimoEngine.PlayResult r2 = engine.playCard(bob, bobSeven, null);
        assertFalse(r2.success(), "Playing non-skip card while under Skip deflection must fail");
        assertTrue(bob.getHand().contains(bobSeven), "The rejected card must remain in hand");

        // Bob's turn was forfeited, skip consumed, turn advanced to Charlie
        assertFalse(engine.isPendingSkipDeflection());
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testSkipDeflectionInTwoPlayerGame: 2-player match deflecting Skip returns block to Player 1")
    void testSkipDeflectionInTwoPlayerGame() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        // Alice plays RED SKIP -> targets Bob
        Card aliceSkip = new Card(CardColor.RED, CardValue.SKIP);
        alice.getHand().addCard(aliceSkip);
        engine.playCard(alice, aliceSkip, null);
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Bob defends with GREEN SKIP -> deflects back to Alice
        Card bobSkip = new Card(CardColor.GREEN, CardValue.SKIP);
        bob.getHand().addCard(bobSkip);
        UltimoEngine.PlayResult r2 = engine.playCard(bob, bobSkip, null);
        assertTrue(r2.success());
        assertEquals("Alice", engine.getSkipTarget().getUsername());
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Alice has no Skip and passes -> Alice is skipped, turn goes back to Bob
        assertTrue(engine.passTurn(alice));
        assertFalse(engine.isPendingSkipDeflection());
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testDrawTwoStackingChain: Player 1 plays +2, Player 2 plays +2, Player 3 plays +2, Player 4 draws 6 cards")
    void testDrawTwoStackingChain() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie, david), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FOUR));

        // 1. Alice plays RED DRAW_TWO (+2)
        Card aDraw = new Card(CardColor.RED, CardValue.DRAW_TWO);
        alice.getHand().addCard(aDraw);
        UltimoEngine.PlayResult r1 = engine.playCard(alice, aDraw, null);
        assertTrue(r1.success());
        assertEquals(2, engine.getPendingDrawPenalty());
        assertEquals(CardValue.DRAW_TWO, engine.getPendingDrawType());
        assertEquals("Bob", engine.getDrawTarget().getUsername());
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());

        // 2. Bob plays BLUE DRAW_TWO (+2) -> accumulates to 4
        int bobHandBefore = bob.getHand().size();
        Card bDraw = new Card(CardColor.BLUE, CardValue.DRAW_TWO);
        bob.getHand().addCard(bDraw);
        UltimoEngine.PlayResult r2 = engine.playCard(bob, bDraw, null);
        assertTrue(r2.success());
        assertEquals(bobHandBefore, bob.getHand().size(), "Bob should not draw any cards when defending");
        assertEquals(4, engine.getPendingDrawPenalty());
        assertEquals("Charlie", engine.getDrawTarget().getUsername());
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername());

        // 3. Charlie plays GREEN DRAW_TWO (+2) -> accumulates to 6
        int charlieHandBefore = charlie.getHand().size();
        Card cDraw = new Card(CardColor.GREEN, CardValue.DRAW_TWO);
        charlie.getHand().addCard(cDraw);
        UltimoEngine.PlayResult r3 = engine.playCard(charlie, cDraw, null);
        assertTrue(r3.success());
        assertEquals(charlieHandBefore, charlie.getHand().size(), "Charlie should not draw cards when defending");
        assertEquals(6, engine.getPendingDrawPenalty());
        assertEquals("David", engine.getDrawTarget().getUsername());
        assertEquals("David", engine.getTurnManager().getCurrentPlayer().getUsername());

        // 4. David has no Draw Two and accepts the penalty
        int davidHandBefore = david.getHand().size();
        int penaltyDrawn = engine.acceptDrawPenalty(david);
        assertEquals(6, penaltyDrawn, "David must draw 6 accumulated cards");
        assertEquals(davidHandBefore + 6, david.getHand().size(), "David's hand must increase by 6");
        assertEquals(0, engine.getPendingDrawPenalty(), "Pending penalty must reset to 0");
        assertNull(engine.getDrawTarget(), "Draw target must reset to null");

        // David's turn is forfeited/skipped, so turn advances to Alice
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn should advance to Alice after David draws penalty");
    }

    @Test
    @DisplayName("testDrawTwoStackingWithCombo: Combo of 2x Draw Two (+4) stacked with 1x Draw Two (+2) -> 6 cards")
    void testDrawTwoStackingWithCombo() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FOUR));

        // Alice plays combo of 2x DRAW_TWO (+4 penalty)
        Card r1 = new Card(CardColor.RED, CardValue.DRAW_TWO);
        Card r2 = new Card(CardColor.YELLOW, CardValue.DRAW_TWO);
        alice.getHand().addCard(r1);
        alice.getHand().addCard(r2);
        UltimoEngine.PlayResult res = engine.playCards(alice, List.of(r1, r2), null);
        assertTrue(res.success());
        assertEquals(4, engine.getPendingDrawPenalty());
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Bob stacks 1x GREEN DRAW_TWO (+2) -> total 6
        Card g1 = new Card(CardColor.GREEN, CardValue.DRAW_TWO);
        bob.getHand().addCard(g1);
        assertTrue(engine.playCard(bob, g1, null).success());
        assertEquals(6, engine.getPendingDrawPenalty());
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Charlie calls drawCard() -> pulls 6 penalty cards and forfeits turn
        int charlieHandBefore = charlie.getHand().size();
        UltimoEngine.DrawResult drawRes = engine.drawCardWithResult(charlie);
        assertEquals(6, drawRes.cardsDrawn());
        assertEquals(charlieHandBefore + 6, charlie.getHand().size());
        assertEquals(0, engine.getPendingDrawPenalty());

        // Turn is with Alice
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testDrawStackingVictimCannotPlayNonDrawCard: Under penalty, playing non-draw card forces penalty draw and forfeits turn")
    void testDrawStackingVictimCannotPlayNonDrawCard() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FOUR));

        Card aDraw = new Card(CardColor.RED, CardValue.DRAW_TWO);
        alice.getHand().addCard(aDraw);
        engine.playCard(alice, aDraw, null);

        Card bobOne = new Card(CardColor.RED, CardValue.ONE);
        bob.getHand().addCard(bobOne);
        int bobHandBefore = bob.getHand().size();

        UltimoEngine.PlayResult res = engine.playCard(bob, bobOne, null);
        assertFalse(res.success(), "Playing non-draw card under draw penalty must fail");
        assertEquals(bobHandBefore + 2, bob.getHand().size(), "Bob must be forced to draw the 2 penalty cards");
        assertEquals(0, engine.getPendingDrawPenalty());

        // Turn advanced to Charlie
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testWildDrawFourStacking: Stacking WILD_DRAW_FOUR on WILD_DRAW_FOUR accumulates +8 penalty")
    void testWildDrawFourStacking() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.YELLOW, CardValue.NINE));

        // Alice has no yellow cards, plays Wild Draw Four (with extra cards so match continues)
        alice.getHand().clear();
        Card w1 = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        alice.getHand().addCard(w1);
        alice.getHand().addCard(new Card(CardColor.GREEN, CardValue.SEVEN));
        UltimoEngine.PlayResult r1 = engine.playCard(alice, w1, CardColor.BLUE);
        assertTrue(r1.success());
        assertEquals(4, engine.getPendingDrawPenalty());
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Bob has another Wild Draw Four and stacks it
        bob.getHand().clear();
        Card w2 = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        bob.getHand().addCard(w2);
        bob.getHand().addCard(new Card(CardColor.GREEN, CardValue.EIGHT));
        UltimoEngine.PlayResult r2 = engine.playCard(bob, w2, CardColor.RED);
        assertTrue(r2.success());
        assertEquals(8, engine.getPendingDrawPenalty());
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Charlie accepts penalty
        int charlieBefore = charlie.getHand().size();
        int drawn = engine.acceptDrawPenalty(charlie);
        assertEquals(8, drawn);
        assertEquals(charlieBefore + 8, charlie.getHand().size());
        assertEquals(0, engine.getPendingDrawPenalty());
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testProgressiveStackingWildDrawFourOnDrawTwo: Wild Draw Four can defend against Draw Two (+6 total)")
    void testProgressiveStackingWildDrawFourOnDrawTwo() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.ONE));

        // Alice plays RED DRAW_TWO (+2)
        Card d2 = new Card(CardColor.RED, CardValue.DRAW_TWO);
        alice.getHand().addCard(d2);
        engine.playCard(alice, d2, null);
        assertEquals(2, engine.getPendingDrawPenalty());

        // Bob defends with WILD_DRAW_FOUR (+4) (has extra card so match continues)
        bob.getHand().clear();
        Card w4 = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        bob.getHand().addCard(w4);
        bob.getHand().addCard(new Card(CardColor.GREEN, CardValue.SIX));
        UltimoEngine.PlayResult r2 = engine.playCard(bob, w4, CardColor.GREEN);
        assertTrue(r2.success());
        assertEquals(6, engine.getPendingDrawPenalty());
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Charlie accepts penalty -> draws 6 cards
        int charlieBefore = charlie.getHand().size();
        assertEquals(6, engine.acceptDrawPenalty(charlie));
        assertEquals(charlieBefore + 6, charlie.getHand().size());
    }

    @Test
    @DisplayName("testAutoPassWhenDrawnCardNotPlayable: Player with no moves draws unplayable card -> auto-pass asserted, turn advances")
    void testAutoPassWhenDrawnCardNotPlayable() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), true, true);
        engine.start();

        // Top card is RED FIVE
        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        // Alice only holds BLUE ZERO (unplayable on RED FIVE)
        alice.getHand().clear();
        alice.getHand().addCard(new Card(CardColor.BLUE, CardValue.ZERO));

        // Drain deck and place unplayable GREEN SEVEN as next card
        while (!engine.getDeck().isEmpty()) {
            engine.getDeck().draw();
        }
        engine.getDeck().replenishFromDiscard(List.of(new Card(CardColor.GREEN, CardValue.SEVEN)));

        // Alice draws
        UltimoEngine.DrawResult drawRes = engine.drawCardWithResult(alice);
        assertTrue(drawRes.card().isPresent());
        assertEquals(new Card(CardColor.GREEN, CardValue.SEVEN), drawRes.card().get());
        assertTrue(drawRes.autoPassed(), "autoPassed flag must be true when player has no legal moves");
        assertTrue(engine.wasLastDrawAutoPassed());
        assertEquals(2, alice.getHand().size());

        // Turn automatically moved to Bob without manual PASS
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testNoAutoPassWhenDrawnCardIsPlayable: Player draws playable card -> turn remains active with player")
    void testNoAutoPassWhenDrawnCardIsPlayable() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), true, true);
        engine.start();

        // Top card is RED FIVE
        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        // Alice only holds BLUE ZERO
        alice.getHand().clear();
        alice.getHand().addCard(new Card(CardColor.BLUE, CardValue.ZERO));

        // Deck supplies RED SEVEN (playable on RED FIVE)
        while (!engine.getDeck().isEmpty()) {
            engine.getDeck().draw();
        }
        engine.getDeck().replenishFromDiscard(List.of(new Card(CardColor.RED, CardValue.SEVEN)));

        // Alice draws
        UltimoEngine.DrawResult drawRes = engine.drawCardWithResult(alice);
        assertTrue(drawRes.card().isPresent());
        assertEquals(new Card(CardColor.RED, CardValue.SEVEN), drawRes.card().get());
        assertFalse(drawRes.autoPassed(), "autoPassed flag must be false when drawn card is playable");
        assertFalse(engine.wasLastDrawAutoPassed());
        assertTrue(engine.hasDrawnThisTurn());

        // Turn remains with Alice
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername());

        // Alice can now play the drawn card
        UltimoEngine.PlayResult playRes = engine.playCard(alice, drawRes.card().get(), null);
        assertTrue(playRes.success());
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testNoAutoPassWhenPlayerAlreadyHasPlayableCard: Player already holds playable card in hand -> turn remains with player")
    void testNoAutoPassWhenPlayerAlreadyHasPlayableCard() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), true, true);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        // Alice holds RED NINE (already playable!)
        alice.getHand().clear();
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.NINE));

        // Deck supplies unplayable BLUE ONE
        while (!engine.getDeck().isEmpty()) {
            engine.getDeck().draw();
        }
        engine.getDeck().replenishFromDiscard(List.of(new Card(CardColor.BLUE, CardValue.ONE)));

        UltimoEngine.DrawResult drawRes = engine.drawCardWithResult(alice);
        assertTrue(drawRes.card().isPresent());
        assertFalse(drawRes.autoPassed(), "Must not auto-pass because Alice holds a playable RED NINE in hand");
        assertEquals("Alice", engine.getTurnManager().getCurrentPlayer().getUsername());
        assertTrue(engine.hasDrawnThisTurn());

        // Alice can legally pass manually
        assertTrue(engine.passTurn(alice));
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("testClassicModeRestoresImmediateSkipAndDraw: When action defending is disabled, Skip and Draw Two immediately skip victim")
    void testClassicModeRestoresImmediateSkipAndDraw() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie), false, false);
        engine.start();

        engine.getDiscardPile().resetWithCard(new Card(CardColor.RED, CardValue.FIVE));

        // 1. Skip immediately skips next player without defense
        Card skip = new Card(CardColor.RED, CardValue.SKIP);
        alice.getHand().addCard(skip);
        UltimoEngine.PlayResult r1 = engine.playCard(alice, skip, null);
        assertTrue(r1.success());
        assertFalse(engine.isPendingSkipDeflection());
        assertEquals("Charlie", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn must skip Bob immediately in classic mode");

        // 2. Draw Two immediately forces victim to draw and skips their turn
        Card drawTwo = new Card(CardColor.RED, CardValue.DRAW_TWO);
        charlie.getHand().addCard(drawTwo);
        int aliceBefore = alice.getHand().size();
        UltimoEngine.PlayResult r2 = engine.playCard(charlie, drawTwo, null);
        assertTrue(r2.success());
        assertEquals(0, engine.getPendingDrawPenalty());
        assertEquals(aliceBefore + 2, alice.getHand().size(), "Alice must immediately receive 2 cards in classic mode");
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername(), "Turn must advance past Alice to Bob in classic mode");
    }
}
