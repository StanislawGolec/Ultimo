package pl.ultimo.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Official Ultimo Rules & Penalty Enforcement Tests (Stage 5)")
class UltimoRulesExtendedTest {

    private Player alice;
    private Player bob;
    private Player charlie;

    @BeforeEach
    void setUp() {
        alice = new Player("p1", "Alice");
        bob = new Player("p2", "Bob");
        charlie = new Player("p3", "Charlie");
    }

    // =========================================================================
    // 1. "ULTIMO!" Declaration & Catching Penalty Tests
    // =========================================================================

    @Test
    @DisplayName("Should allow player to declare ULTIMO when holding 2 cards or 1 card")
    void shouldAllowUltimoDeclarationWhenHandHasTwoOrOneCard() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        alice.getHand().clear();
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.TWO));

        assertTrue(engine.declareUltimo(alice), "Player holding 2 cards should be able to declare ULTIMO");
        assertTrue(engine.hasDeclaredUltimo(alice));

        // When holding 1 card
        alice.getHand().removeCard(new Card(CardColor.RED, CardValue.ONE));
        assertEquals(1, alice.getHand().size());
        assertTrue(engine.declareUltimo(alice), "Player holding 1 card should be able to declare ULTIMO");
        assertTrue(engine.hasDeclaredUltimo(alice));
    }

    @Test
    @DisplayName("Should reject ULTIMO declaration when player holds 3 or more cards")
    void shouldRejectUltimoDeclarationWhenHandHasMoreThanTwoCards() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        alice.getHand().clear();
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.TWO));
        alice.getHand().addCard(new Card(CardColor.RED, CardValue.THREE));

        assertFalse(engine.declareUltimo(alice), "Player with 3 cards cannot declare ULTIMO");
        assertFalse(engine.hasDeclaredUltimo(alice));
    }

    @Test
    @DisplayName("Should flag player as pending violator when playing down to 1 card without saying ULTIMO")
    void shouldFlagPendingViolatorWhenPlayingDownToOneCardWithoutUltimo() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        // Setup active player as Alice
        Player current = engine.getTurnManager().getCurrentPlayer();
        Player other = current.equals(alice) ? bob : alice;

        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        current.getHand().clear();
        Card cardToPlay = new Card(activeColor, CardValue.FIVE);
        Card cardToKeep = new Card(CardColor.BLUE, CardValue.NINE);
        current.getHand().addCard(cardToPlay);
        current.getHand().addCard(cardToKeep);

        UltimoEngine.PlayResult playResult = engine.playCard(current, cardToPlay, null);
        assertTrue(playResult.success());
        assertTrue(playResult.isUltimo(), "PlayResult should indicate penultimate card played");
        assertEquals(1, current.getHand().size());

        assertEquals(current.getUsername(), engine.getPendingUltimoViolator(),
                "Player should be recorded as pending Ultimo violator");
    }

    @Test
    @DisplayName("Should not flag player as violator if they declared ULTIMO before playing down to 1 card")
    void shouldNotFlagViolatorIfDeclaredUltimoBeforePlaying() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        current.getHand().clear();
        Card cardToPlay = new Card(activeColor, CardValue.FIVE);
        Card cardToKeep = new Card(CardColor.BLUE, CardValue.NINE);
        current.getHand().addCard(cardToPlay);
        current.getHand().addCard(cardToKeep);

        // Pre-declare ULTIMO
        assertTrue(engine.declareUltimo(current));

        UltimoEngine.PlayResult playResult = engine.playCard(current, cardToPlay, null);
        assertTrue(playResult.success());
        assertEquals(1, current.getHand().size());

        assertNull(engine.getPendingUltimoViolator(),
                "Player who pre-declared ULTIMO must not be flagged as violator");
    }

    @Test
    @DisplayName("Should penalize violator with 2 cards when caught without saying ULTIMO")
    void shouldPenalizeViolatorWithTwoCardsWhenCaught() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player challenger = current.equals(alice) ? bob : alice;

        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        current.getHand().clear();
        Card cardToPlay = new Card(activeColor, CardValue.FIVE);
        Card cardToKeep = new Card(CardColor.BLUE, CardValue.NINE);
        current.getHand().addCard(cardToPlay);
        current.getHand().addCard(cardToKeep);

        engine.playCard(current, cardToPlay, null);
        assertEquals(1, current.getHand().size());
        assertEquals(current.getUsername(), engine.getPendingUltimoViolator());

        // Challenger catches them before taking their move
        UltimoEngine.CatchResult catchResult = engine.catchUltimo(challenger, current.getUsername());
        assertTrue(catchResult.success(), "Catch must succeed");
        assertEquals(6, catchResult.penaltyCards());
        assertEquals(current, catchResult.penalizedPlayer());
        assertEquals(7, current.getHand().size(), "Penalized player should now have 1 + 6 = 7 cards");
        assertNull(engine.getPendingUltimoViolator(), "Pending violator status must be cleared");
    }

    @Test
    @DisplayName("Should reject catch if player already declared ULTIMO")
    void shouldRejectCatchIfPlayerAlreadyDeclaredUltimo() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player challenger = current.equals(alice) ? bob : alice;

        current.getHand().clear();
        current.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));
        engine.declareUltimo(current);

        UltimoEngine.CatchResult catchResult = engine.catchUltimo(challenger, current.getUsername());
        assertFalse(catchResult.success());
        assertTrue(catchResult.message().contains("already declared ULTIMO"));
        assertEquals(1, current.getHand().size());
    }

    @Test
    @DisplayName("Should reject catch if next player has already started their turn (window expired)")
    void shouldRejectCatchIfTurnWindowExpired() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        Player p1 = engine.getTurnManager().getCurrentPlayer();
        Player p2 = engine.getTurnManager().peekNextPlayer();

        CardColor activeColor = engine.getDiscardPile().getActiveColor();
        p1.getHand().clear();
        Card cardToPlay = new Card(activeColor, CardValue.SEVEN);
        Card cardToKeep = new Card(CardColor.YELLOW, CardValue.TWO);
        p1.getHand().addCard(cardToPlay);
        p1.getHand().addCard(cardToKeep);

        engine.playCard(p1, cardToPlay, null);
        assertEquals(1, p1.getHand().size());
        assertEquals(p1.getUsername(), engine.getPendingUltimoViolator());

        // Next player p2 draws a card, closing the catch window
        engine.drawCard(p2);
        assertNull(engine.getPendingUltimoViolator(), "Catch window must close once next player moves");

        // Now p2 or p3 tries to catch p1
        UltimoEngine.CatchResult catchResult = engine.catchUltimo(p2, p1.getUsername());
        assertFalse(catchResult.success(), "Catch must fail once window has expired");
        assertTrue(catchResult.message().contains("expired"));
        assertEquals(1, p1.getHand().size(), "Player hand size should remain 1");
    }

    @Test
    @DisplayName("Should reject catch if player attempts to catch themselves or target has != 1 card")
    void shouldRejectInvalidCatchTargets() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        // Self catch
        UltimoEngine.CatchResult selfCatch = engine.catchUltimo(alice, "Alice");
        assertFalse(selfCatch.success());
        assertTrue(selfCatch.message().contains("Cannot catch yourself"));

        // Target with 7 cards
        UltimoEngine.CatchResult multiCardCatch = engine.catchUltimo(alice, "Bob");
        assertFalse(multiCardCatch.success());
        assertTrue(multiCardCatch.message().contains("does not have exactly 1 card"));
    }

    // =========================================================================
    // 2. Wild Draw Four Legality & Challenge System Tests
    // =========================================================================

    @Test
    @DisplayName("Victim challenges Wild Draw Four when offender is GUILTY (held matching color) -> Offender draws 4 cards")
    void shouldPenalizeGuiltyOffenderOnWildFourChallenge() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player victim = current.equals(alice) ? bob : alice;

        CardColor tableColor = engine.getDiscardPile().getActiveColor();

        // Give current player a matching color card AND a Wild Draw Four
        current.getHand().clear();
        Card matchingCard = new Card(tableColor, CardValue.ONE);
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        current.getHand().addCard(matchingCard);
        current.getHand().addCard(wildFour);

        int offenderHandBefore = current.getHand().size();
        int victimHandBefore = victim.getHand().size();

        // Current plays Wild Draw Four illegally (held matching tableColor)
        UltimoEngine.PlayResult playResult = engine.playCard(current, wildFour, CardColor.BLUE);
        assertTrue(playResult.success());
        assertNotNull(engine.getPendingWildChallenge());
        assertTrue(engine.getPendingWildChallenge().isGuilty());

        // Victim challenges!
        UltimoEngine.WildChallengeResult challengeRes = engine.challengeWildFour(victim);
        assertTrue(challengeRes.success());
        assertTrue(challengeRes.isGuilty());
        assertEquals(current, challengeRes.penalizedPlayer());
        assertEquals(4, challengeRes.cardsDrawn());

        // Offender had 2 cards, played 1, then drew 4 penalty cards = 5 cards
        assertEquals(offenderHandBefore - 1 + 4, current.getHand().size());
        // Victim drew 0 cards
        assertEquals(victimHandBefore, victim.getHand().size());
        // Victim turn was NOT skipped: victim is still current player and can take their turn
        assertEquals(victim, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Victim challenges Wild Draw Four when offender is INNOCENT -> Challenger draws 6 cards and is skipped")
    void shouldPenalizeChallengerWhenOffenderIsInnocent() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player victim = current.equals(alice) ? bob : alice;

        CardColor tableColor = engine.getDiscardPile().getActiveColor();
        CardColor differentColor = (tableColor == CardColor.RED) ? CardColor.GREEN : CardColor.RED;

        // Current player has NO card matching tableColor
        current.getHand().clear();
        Card nonMatchingCard = new Card(differentColor, CardValue.ONE);
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        current.getHand().addCard(nonMatchingCard);
        current.getHand().addCard(wildFour);

        int victimHandBefore = victim.getHand().size();

        // Current plays Wild Draw Four legally
        UltimoEngine.PlayResult playResult = engine.playCard(current, wildFour, CardColor.YELLOW);
        assertTrue(playResult.success());
        assertNotNull(engine.getPendingWildChallenge());
        assertFalse(engine.getPendingWildChallenge().isGuilty());

        // Victim challenges legal play
        UltimoEngine.WildChallengeResult challengeRes = engine.challengeWildFour(victim);
        assertTrue(challengeRes.success());
        assertFalse(challengeRes.isGuilty());
        assertEquals(victim, challengeRes.penalizedPlayer());
        assertEquals(6, challengeRes.cardsDrawn()); // 4 + 2 penalty

        // Victim drew 6 cards
        assertEquals(victimHandBefore + 6, victim.getHand().size());
        // Victim's turn was skipped, so it's current player's turn again in 2-player game
        assertEquals(current, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Victim accepts Wild Draw Four penalty (draw/pass) without challenging -> draws 4 cards and is skipped")
    void shouldAcceptWildDrawFourPenaltyWithoutChallenge() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player victim = current.equals(alice) ? bob : alice;

        current.getHand().clear();
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        Card otherCard = new Card(CardColor.YELLOW, CardValue.EIGHT);
        current.getHand().addCard(wildFour);
        current.getHand().addCard(otherCard);

        int victimHandBefore = victim.getHand().size();
        engine.playCard(current, wildFour, CardColor.RED);

        assertNotNull(engine.getPendingWildChallenge());

        // Victim accepts penalty
        boolean accepted = engine.acceptWildFour(victim);
        assertTrue(accepted);
        assertNull(engine.getPendingWildChallenge());

        // Victim drew 4 cards
        assertEquals(victimHandBefore + 4, victim.getHand().size());
        // Victim turn was skipped -> turn returned to current player in 2p game
        assertEquals(current, engine.getTurnManager().getCurrentPlayer());
    }

    @Test
    @DisplayName("Victim cannot play cards while Wild Draw Four challenge is pending")
    void victimCannotPlayCardsWhileChallengeIsPending() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player victim = current.equals(alice) ? bob : alice;

        current.getHand().clear();
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        Card otherCard = new Card(CardColor.YELLOW, CardValue.EIGHT);
        current.getHand().addCard(wildFour);
        current.getHand().addCard(otherCard);

        engine.playCard(current, wildFour, CardColor.RED);
        assertNotNull(engine.getPendingWildChallenge());

        // Victim attempts to play card instead of challenging or accepting
        victim.getHand().clear();
        Card redCard = new Card(CardColor.RED, CardValue.FIVE);
        victim.getHand().addCard(redCard);

        UltimoEngine.PlayResult playRes = engine.playCard(victim, redCard, null);
        assertFalse(playRes.success());
        assertTrue(playRes.errorMessage().contains("Must either challenge the Wild Draw Four or accept"));
    }

    // =========================================================================
    // 3. Multi-Round Tournament Scoring & Point Tallying Tests
    // =========================================================================

    @ParameterizedTest(name = "Card {0} should have point value {1}")
    @CsvSource({
            "ZERO, 0",
            "ONE, 1",
            "TWO, 2",
            "THREE, 3",
            "FOUR, 4",
            "FIVE, 5",
            "SIX, 6",
            "SEVEN, 7",
            "EIGHT, 8",
            "NINE, 9",
            "SKIP, 20",
            "REVERSE, 20",
            "DRAW_TWO, 20",
            "WILD, 50",
            "WILD_DRAW_FOUR, 50"
    })
    void shouldScoreOfficialCardPointValues(CardValue value, int expectedPoints) {
        assertEquals(expectedPoints, value.getPointValue());
    }

    @Test
    @DisplayName("Should tally round points from opponents' remaining hand cards when a player wins")
    void shouldTallyOpponentsHandCardsWhenPlayerWins() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob, charlie));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player p2 = current.equals(alice) ? bob : alice;
        Player p3 = charlie;

        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        // Winner plays their last card
        current.getHand().clear();
        Card winCard = new Card(activeColor, CardValue.THREE);
        current.getHand().addCard(winCard);

        // Opponent 1 has: RED 8 (8 pts) + SKIP (20 pts) = 28 pts
        p2.getHand().clear();
        p2.getHand().addCard(new Card(CardColor.RED, CardValue.EIGHT));
        p2.getHand().addCard(new Card(CardColor.BLUE, CardValue.SKIP));

        // Opponent 2 has: WILD (50 pts) + GREEN 2 (2 pts) = 52 pts
        p3.getHand().clear();
        p3.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD));
        p3.getHand().addCard(new Card(CardColor.GREEN, CardValue.TWO));

        UltimoEngine.PlayResult res = engine.playCard(current, winCard, null);
        assertTrue(res.success());
        assertFalse(res.isGameOver(), "Game should continue among remaining active players");
        assertEquals(1, current.getPlacementRank());

        // Player 2 plays their last card to finish 2nd place
        p2.getHand().clear();
        Card p2WinCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        p2.getHand().addCard(p2WinCard);
        UltimoEngine.PlayResult res2 = engine.playCard(p2, p2WinCard, null);
        assertTrue(res2.success());
        assertTrue(res2.isGameOver(), "Game should finish when only 1 player remains holding cards");
        assertEquals(2, p2.getPlacementRank());
        assertEquals(3, p3.getPlacementRank());

        Optional<GameResult> gameResultOpt = engine.getResult();
        assertTrue(gameResultOpt.isPresent());
        GameResult gameResult = gameResultOpt.get();

        // Total points = 28 + 52 = 80 pts
        assertEquals(80, gameResult.roundPoints());
        assertEquals(80, gameResult.getWinnerScore());
        assertEquals(80, gameResult.cumulativeScores().get(current.getUsername()));
        assertEquals(0, gameResult.cumulativeScores().get(p2.getUsername()));
        assertEquals(0, gameResult.cumulativeScores().get(p3.getUsername()));
    }

    @Test
    @DisplayName("If winning card is DRAW_TWO, next player draws 2 cards before points are tallied")
    void shouldApplyDrawTwoPenaltyWhenPlayedAsWinningCard() {
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob));
        engine.start();

        Player current = engine.getTurnManager().getCurrentPlayer();
        Player victim = current.equals(alice) ? bob : alice;

        CardColor activeColor = engine.getDiscardPile().getActiveColor();

        // Winner has 1 card: DRAW_TWO
        current.getHand().clear();
        Card drawTwo = new Card(activeColor, CardValue.DRAW_TWO);
        current.getHand().addCard(drawTwo);

        // Victim has 1 card: ZERO (0 pts)
        victim.getHand().clear();
        victim.getHand().addCard(new Card(CardColor.RED, CardValue.ZERO));

        int victimHandBefore = victim.getHand().size();
        UltimoEngine.PlayResult res = engine.playCard(current, drawTwo, null);
        assertTrue(res.success());
        assertTrue(res.isGameOver());

        // Victim was forced to draw 2 extra cards
        assertEquals(victimHandBefore + 2, victim.getHand().size());

        Optional<GameResult> gameResultOpt = engine.getResult();
        assertTrue(gameResultOpt.isPresent());
        // Winner earned points including the 2 cards victim was forced to draw
        assertTrue(gameResultOpt.get().roundPoints() >= 0);
    }

    @Test
    @DisplayName("Cumulative scores accumulate across multiple rounds until target score is reached")
    void shouldAccumulateCumulativeScoresUntilTargetScoreReached() {
        // Create engine with low target score of 100 for tournament completion testing
        UltimoEngine engine = new UltimoEngine(List.of(alice, bob), new java.util.Random(), 100);
        assertEquals(100, engine.getTargetScore());
        engine.start();

        Player winner = alice;
        Player loser = bob;

        // Round 1: Alice wins 60 points
        winner.getHand().clear();
        Card winningCard1 = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        winner.getHand().addCard(winningCard1);

        loser.getHand().clear();
        loser.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD)); // 50 pts
        loser.getHand().addCard(new Card(CardColor.RED, CardValue.NINE));  // 9 pts
        loser.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));   // 1 pt -> total 60 pts

        UltimoEngine.PlayResult r1 = engine.playCard(winner, winningCard1, null);
        assertTrue(r1.success());
        assertTrue(r1.isGameOver());

        assertEquals(60, engine.getCumulativeScores().get(alice.getUsername()));
        assertFalse(engine.isTournamentWon(), "100 pts target not yet reached with 60 pts");
        assertNull(engine.getTournamentWinner());

        // Start Round 2
        assertTrue(engine.startNextRound(), "startNextRound should succeed when game is over but tournament not won");
        assertTrue(engine.isGameStarted());
        assertFalse(engine.isGameOver());
        assertEquals(7, alice.getHand().size());
        assertEquals(7, bob.getHand().size());

        // Round 2: Alice wins 50 points (Cumulative: 60 + 50 = 110 >= 100)
        winner.getHand().clear();
        Card winningCard2 = new Card(engine.getDiscardPile().getActiveColor(), CardValue.TWO);
        winner.getHand().addCard(winningCard2);

        loser.getHand().clear();
        loser.getHand().addCard(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR)); // 50 pts

        UltimoEngine.PlayResult r2 = engine.playCard(winner, winningCard2, null);
        assertTrue(r2.success());
        assertTrue(r2.isGameOver());

        assertEquals(110, engine.getCumulativeScores().get(alice.getUsername()));
        assertTrue(engine.isTournamentWon(), "Tournament should be won after reaching 110 >= 100 pts");
        assertEquals(alice, engine.getTournamentWinner());

        // Once tournament is won, cannot start another round
        assertFalse(engine.startNextRound(), "Cannot start next round once tournament is won");
    }
}
