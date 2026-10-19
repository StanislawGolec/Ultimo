package pl.ultimo.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Deck;
import pl.ultimo.core.model.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Multi-Round 500-Point Tournament & Deterministic Seed Domain Tests")
class UltimoTournamentDomainTest {

    private Player alice;
    private Player bob;
    private Player charlie;
    private Player diana;

    @BeforeEach
    void setUp() {
        alice = new Player("p1", "Alice");
        bob = new Player("p2", "Bob");
        charlie = new Player("p3", "Charlie");
        diana = new Player("p4", "Diana");
    }

    @Test
    @DisplayName("Default tournament target score is 500 points")
    void testDefaultTargetScoreIs500() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        assertEquals(500, engine.getTargetScore());
        assertEquals(500, UltimoEngine.DEFAULT_TARGET_SCORE);
        assertFalse(engine.isTournamentWon());
        assertNull(engine.getTournamentWinner());
    }

    @Test
    @DisplayName("Multi-round tournament accumulates scores across 4 rounds until 500 points is reached")
    void testMultiRoundTournamentProgressionTo500Points() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), 12345L);
        engine.start();

        // --- Round 1: Alice wins 160 points ---
        forceRoundWin(engine, alice, bob, List.of(
                new Card(CardColor.WILD, CardValue.WILD),           // 50
                new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), // 50
                new Card(CardColor.WILD, CardValue.WILD),           // 50
                new Card(CardColor.RED, CardValue.FIVE),            // 5
                new Card(CardColor.BLUE, CardValue.FIVE)            // 5 -> total 160
        ));

        assertTrue(engine.isGameOver());
        assertEquals(160, engine.getCumulativeScores().get("Alice"));
        assertEquals(0, engine.getCumulativeScores().get("Bob"));
        assertFalse(engine.isTournamentWon());
        assertNull(engine.getTournamentWinner());

        // Start Round 2
        assertTrue(engine.startNextRound());
        assertFalse(engine.isGameOver());
        assertEquals(0, alice.getPlacementRank());
        assertEquals(0, bob.getPlacementRank());
        assertFalse(alice.isFinished());
        assertFalse(bob.isFinished());
        assertTrue(engine.getFinishedPlayers().isEmpty());
        assertTrue(engine.getMatchStandings().isEmpty());
        assertEquals(160, engine.getCumulativeScores().get("Alice"));
        assertEquals(alice, engine.getTurnManager().getCurrentPlayer(), "Previous round winner starts next round");

        // --- Round 2: Bob wins 190 points ---
        // Advance turn to Bob first
        Card alicePassCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        alice.getHand().clear();
        alice.getHand().addCard(alicePassCard);
        alice.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD));           // 50
        alice.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR)); // 50
        alice.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD));           // 50
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.DRAW_TWO));        // 20
        alice.getHand().addCard(new Card(CardColor.BLUE, CardValue.SKIP));           // 20 -> 190 remaining after playing ONE
        engine.playCard(alice, alicePassCard, null);

        bob.getHand().clear();
        Card bobWinCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.TWO);
        bob.getHand().addCard(bobWinCard);
        UltimoEngine.PlayResult r2 = engine.playCard(bob, bobWinCard, null);
        assertTrue(r2.success());
        assertTrue(r2.isGameOver());

        assertEquals(160, engine.getCumulativeScores().get("Alice"));
        assertEquals(190, engine.getCumulativeScores().get("Bob"));
        assertFalse(engine.isTournamentWon());
        assertNull(engine.getTournamentWinner());

        // Start Round 3 (Bob starts as R2 winner)
        assertTrue(engine.startNextRound());
        assertEquals(bob, engine.getTurnManager().getCurrentPlayer());

        // Advance turn from Bob to Alice
        bob.getHand().clear();
        Card bobPlay = new Card(engine.getDiscardPile().getActiveColor(), CardValue.THREE);
        bob.getHand().addCard(bobPlay);
        bob.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD));           // 50
        bob.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR)); // 50
        bob.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD));           // 50
        bob.getHand().addCard(new Card(CardColor.GREEN, CardValue.REVERSE));       // 20
        bob.getHand().addCard(new Card(CardColor.YELLOW, CardValue.NINE));         // 9
        bob.getHand().addCard(new Card(CardColor.YELLOW, CardValue.ONE));          // 1 -> 180 pts
        engine.playCard(bob, bobPlay, null);

        // --- Round 3: Alice wins 180 points (Cumulative: 160 + 180 = 340) ---
        alice.getHand().clear();
        Card aliceWin3 = new Card(engine.getDiscardPile().getActiveColor(), CardValue.FOUR);
        alice.getHand().addCard(aliceWin3);
        UltimoEngine.PlayResult r3 = engine.playCard(alice, aliceWin3, null);
        assertTrue(r3.success());
        assertTrue(r3.isGameOver());
        assertEquals(340, engine.getCumulativeScores().get("Alice"));
        assertEquals(190, engine.getCumulativeScores().get("Bob"));
        assertFalse(engine.isTournamentWon());

        // Start Round 4 (Alice starts as R3 winner)
        assertTrue(engine.startNextRound());
        assertEquals(alice, engine.getTurnManager().getCurrentPlayer());

        // --- Round 4: Alice wins 170 points (Cumulative: 340 + 170 = 510 >= 500) ---
        forceRoundWin(engine, alice, bob, List.of(
                new Card(CardColor.WILD, CardValue.WILD),           // 50
                new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), // 50
                new Card(CardColor.WILD, CardValue.WILD),           // 50
                new Card(CardColor.RED, CardValue.SKIP)             // 20 -> total 170
        ));

        assertTrue(engine.isGameOver());
        assertEquals(510, engine.getCumulativeScores().get("Alice"));
        assertEquals(190, engine.getCumulativeScores().get("Bob"));
        assertTrue(engine.isTournamentWon(), "Tournament must be marked won when reaching >= 500 points");
        assertEquals(alice, engine.getTournamentWinner());

        Optional<GameResult> finalResult = engine.getResult();
        assertTrue(finalResult.isPresent());
        assertTrue(finalResult.get().isTournamentWon());
        assertEquals(170, finalResult.get().roundPoints());
        assertEquals(510, finalResult.get().cumulativeScores().get("Alice"));

        // Verify startNextRound is rejected once tournament is won
        assertFalse(engine.startNextRound(), "Cannot start another round after tournament is won");
    }

    @Test
    @DisplayName("4-player podium ranks, finishedPlayers, and matchStandings reset cleanly across rounds")
    void testFourPlayerPodiumResetAcrossTournamentRounds() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie, diana), 42L);
        engine.start();
        engine.setupPodiumScenario();

        // Give Diana extra high-value cards so she is the last player remaining and provides points to Alice
        diana.getHand().clear();
        diana.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD)); // 50 pts
        diana.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR)); // 50 pts

        // Alice finishes 1st
        assertTrue(engine.playCard(alice, new Card(CardColor.RED, CardValue.ONE), null).success());
        assertEquals(1, alice.getPlacementRank());
        assertTrue(alice.isFinished());
        assertFalse(engine.isGameOver());

        // Bob finishes 2nd
        assertTrue(engine.playCard(bob, new Card(CardColor.RED, CardValue.ONE), null).success());
        assertEquals(2, bob.getPlacementRank());
        assertTrue(bob.isFinished());
        assertFalse(engine.isGameOver());

        // Charlie finishes 3rd -> Diana automatically ranked 4th and round ends
        UltimoEngine.PlayResult r3 = engine.playCard(charlie, new Card(CardColor.RED, CardValue.ONE), null);
        assertTrue(r3.success());
        assertTrue(r3.isGameOver());
        assertEquals(3, charlie.getPlacementRank());
        assertEquals(4, diana.getPlacementRank());
        assertTrue(charlie.isFinished());
        assertTrue(diana.isFinished());
        assertEquals(4, engine.getFinishedPlayers().size());
        assertEquals(4, engine.getMatchStandings().size());

        int aliceRound1Score = engine.getCumulativeScores().get("Alice");
        assertTrue(aliceRound1Score >= 100);

        // Start Round 2 and verify clean podium reset
        assertTrue(engine.startNextRound());
        for (Player p : List.of(alice, bob, charlie, diana)) {
            assertEquals(0, p.getPlacementRank(), p.getUsername() + " rank must reset to 0");
            assertFalse(p.isFinished(), p.getUsername() + " finished flag must reset to false");
            assertEquals(7, p.getHand().size(), p.getUsername() + " must be dealt 7 cards");
        }
        assertTrue(engine.getFinishedPlayers().isEmpty());
        assertTrue(engine.getMatchStandings().isEmpty());
        assertEquals(4, engine.getActivePlayersCount());
        assertEquals(aliceRound1Score, engine.getCumulativeScores().get("Alice"), "Cumulative score must persist");
    }

    @Test
    @DisplayName("Cannot start next round while current round is still active")
    void testCannotStartNextRoundWhileCurrentRoundInProgress() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), 99L);
        engine.start();
        assertFalse(engine.isGameOver());
        assertFalse(engine.startNextRound());
    }

    @Test
    @DisplayName("Deterministic seed produces 100% identical card dealing, starting card, and draw order")
    void testDeterministicSeedIdenticalDealingAndStartingCard() {
        long seed = 987654321L;

        Player a1 = new Player("p1", "Alice");
        Player b1 = new Player("p2", "Bob");
        Player c1 = new Player("p3", "Charlie");
        UltimoEngine engine1 = UltimoEngine.createDeterministic(List.of(a1, b1, c1), seed);
        engine1.start();

        Player a2 = new Player("p1", "Alice");
        Player b2 = new Player("p2", "Bob");
        Player c2 = new Player("p3", "Charlie");
        UltimoEngine engine2 = UltimoEngine.createDeterministic(List.of(a2, b2, c2), seed);
        engine2.start();

        // Hands must match card-for-card
        assertEquals(a1.getHand().getCards(), a2.getHand().getCards());
        assertEquals(b1.getHand().getCards(), b2.getHand().getCards());
        assertEquals(c1.getHand().getCards(), c2.getHand().getCards());

        // Starting discard top card and active color must match
        assertEquals(engine1.getDiscardPile().getTopCard(), engine2.getDiscardPile().getTopCard());
        assertEquals(engine1.getDiscardPile().getActiveColor(), engine2.getDiscardPile().getActiveColor());

        // Remaining deck draw sequence must be 100% identical
        while (!engine1.getDeck().isEmpty()) {
            assertEquals(engine1.getDeck().draw(), engine2.getDeck().draw());
        }
        assertTrue(engine2.getDeck().isEmpty());
    }

    @Test
    @DisplayName("Deterministic seed in Deck produces identical reshuffling when replenished from discard pile")
    void testDeterministicSeedReshufflingFromDiscardPile() {
        long seed = 20261019L;
        Deck deck1 = Deck.createStandardDeck(seed);
        Deck deck2 = Deck.createStandardDeck(seed);

        List<Card> drawn1 = new ArrayList<>();
        List<Card> drawn2 = new ArrayList<>();
        while (!deck1.isEmpty()) {
            drawn1.add(deck1.draw().orElseThrow());
            drawn2.add(deck2.draw().orElseThrow());
        }
        assertEquals(drawn1, drawn2);

        // Replenish both empty decks with the drawn cards
        deck1.replenishFromDiscard(drawn1);
        deck2.replenishFromDiscard(drawn2);

        assertEquals(108, deck1.size());
        assertEquals(108, deck2.size());

        // Verify reshuffled order is 100% identical
        while (!deck1.isEmpty()) {
            assertEquals(deck1.draw(), deck2.draw());
        }
    }

    @Test
    @DisplayName("Deterministic seed maintains identical dealing across multiple tournament rounds")
    void testDeterministicMultiRoundContinuationWithSeed() {
        long seed = 777777L;

        Player a1 = new Player("p1", "Alice");
        Player b1 = new Player("p2", "Bob");
        UltimoEngine engine1 = new UltimoEngine(List.of(a1, b1), seed);
        engine1.start();
        forceRoundWin(engine1, a1, b1, List.of(new Card(CardColor.RED, CardValue.NINE)));
        assertTrue(engine1.startNextRound());

        Player a2 = new Player("p1", "Alice");
        Player b2 = new Player("p2", "Bob");
        UltimoEngine engine2 = new UltimoEngine(List.of(a2, b2), seed);
        engine2.start();
        forceRoundWin(engine2, a2, b2, List.of(new Card(CardColor.RED, CardValue.NINE)));
        assertTrue(engine2.startNextRound());

        assertEquals(a1.getHand().getCards(), a2.getHand().getCards());
        assertEquals(b1.getHand().getCards(), b2.getHand().getCards());
        assertEquals(engine1.getDiscardPile().getTopCard(), engine2.getDiscardPile().getTopCard());
    }

    @Test
    @DisplayName("Winning with DRAW_TWO forces victim to draw penalty cards before tallyRoundScores calculates points")
    void testTallyRoundScoresIncludesDrawTwoWinningPenalty() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), 555L);
        engine.start();

        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        alice.getHand().clear();
        Card winningDrawTwo = new Card(activeColor, CardValue.DRAW_TWO);
        alice.getHand().addCard(winningDrawTwo);

        bob.getHand().clear();
        bob.getHand().addCard(new Card(CardColor.RED, CardValue.FIVE)); // 5 pts

        // Push 2 known high-value cards to top of deck so Bob draws them as penalty
        engine.getDeck().pushTop(new Card(CardColor.WILD, CardValue.WILD)); // 50 pts
        engine.getDeck().pushTop(new Card(CardColor.BLUE, CardValue.SKIP)); // 20 pts

        UltimoEngine.PlayResult result = engine.playCard(alice, winningDrawTwo, null);
        assertTrue(result.success());
        assertTrue(result.isGameOver());
        assertEquals(3, bob.getHand().size());

        // Total points must be 5 + 20 + 50 = 75
        assertEquals(75, engine.getCumulativeScores().get("Alice"));
        assertEquals(75, engine.getResult().orElseThrow().roundPoints());
    }

    private void forceRoundWin(UltimoEngine engine, Player winner, Player loser, List<Card> loserCards) {
        winner.getHand().clear();
        Card winningCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        winner.getHand().addCard(winningCard);

        loser.getHand().clear();
        for (Card c : loserCards) {
            loser.getHand().addCard(c);
        }

        UltimoEngine.PlayResult res = engine.playCard(winner, winningCard, null);
        assertTrue(res.success());
    }
}
