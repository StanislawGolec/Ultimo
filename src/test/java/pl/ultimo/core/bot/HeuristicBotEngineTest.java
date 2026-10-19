package pl.ultimo.core.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.engine.MoveValidator;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.model.PlayerHand;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Autonomous Heuristic AI Bot Engine Unit Tests (25+ Scenarios)")
class HeuristicBotEngineTest {

    private HeuristicBotEngine botEngine;
    private PlayerHand hand;

    @BeforeEach
    void setUp() {
        botEngine = new HeuristicBotEngine();
        hand = new PlayerHand();
    }

    // =========================================================================
    // 1. Skip Deflection Scenarios
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Pending Skip Deflection - Bot deflects with a single held SKIP card")
    void testSkipDeflectionSingleSkip() {
        Card redSkip = new Card(CardColor.RED, CardValue.SKIP);
        Card blueSkip = new Card(CardColor.BLUE, CardValue.SKIP);
        hand.addCard(new Card(CardColor.GREEN, CardValue.FOUR));
        hand.addCard(blueSkip);

        BotDecision decision = botEngine.decide(hand, redSkip, CardColor.RED, 0, true);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(blueSkip), decision.cards());
        assertNull(decision.chosenWildColor());
    }

    @Test
    @DisplayName("Scenario 2: Pending Skip Deflection - Bot deflects with multi-card SKIP combo")
    void testSkipDeflectionMultiSkipCombo() {
        Card redSkip = new Card(CardColor.RED, CardValue.SKIP);
        Card blueSkip = new Card(CardColor.BLUE, CardValue.SKIP);
        Card greenSkip = new Card(CardColor.GREEN, CardValue.SKIP);
        hand.addCard(blueSkip);
        hand.addCard(greenSkip);
        hand.addCard(new Card(CardColor.YELLOW, CardValue.NINE));

        BotDecision decision = botEngine.decide(hand, redSkip, CardColor.RED, 0, true);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(2, decision.cards().size());
        assertTrue(decision.cards().containsAll(List.of(blueSkip, greenSkip)));
    }

    @Test
    @DisplayName("Scenario 3: Pending Skip Deflection - Bot without SKIP card returns DRAW")
    void testSkipDeflectionNoSkipReturnsDraw() {
        Card redSkip = new Card(CardColor.RED, CardValue.SKIP);
        hand.addCard(new Card(CardColor.RED, CardValue.SEVEN));
        hand.addCard(new Card(CardColor.WILD, CardValue.WILD));

        BotDecision decision = botEngine.decide(hand, redSkip, CardColor.RED, 0, true);
        assertEquals(BotAction.DRAW, decision.action());
        assertTrue(decision.cards().isEmpty());
    }

    @Test
    @DisplayName("Scenario 4: Pending Skip Deflection - Bot without SKIP card after drawing returns PASS")
    void testSkipDeflectionNoSkipAfterDrawReturnsPass() {
        Card redSkip = new Card(CardColor.RED, CardValue.SKIP);
        hand.addCard(new Card(CardColor.RED, CardValue.SEVEN));
        hand.addCard(new Card(CardColor.BLUE, CardValue.TWO));

        BotDecision decision = botEngine.decide(
                hand, redSkip, CardColor.RED, 0, null, true, true, false
        );
        assertEquals(BotAction.PASS, decision.action());
    }

    // =========================================================================
    // 2. Draw Penalty Stacking Scenarios
    // =========================================================================

    @Test
    @DisplayName("Scenario 5: Pending Draw Two Penalty - Bot stacks matching DRAW_TWO card")
    void testDrawStackingDrawTwoOnDrawTwo() {
        Card topDrawTwo = new Card(CardColor.RED, CardValue.DRAW_TWO);
        Card yellowDrawTwo = new Card(CardColor.YELLOW, CardValue.DRAW_TWO);
        hand.addCard(new Card(CardColor.RED, CardValue.EIGHT));
        hand.addCard(yellowDrawTwo);

        BotDecision decision = botEngine.decide(hand, topDrawTwo, CardColor.RED, 2, false);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(yellowDrawTwo), decision.cards());
    }

    @Test
    @DisplayName("Scenario 6: Pending Draw Two Penalty - Bot stacks multi-card DRAW_TWO combo")
    void testDrawStackingMultiDrawTwoCombo() {
        Card topDrawTwo = new Card(CardColor.RED, CardValue.DRAW_TWO);
        Card blueDrawTwo = new Card(CardColor.BLUE, CardValue.DRAW_TWO);
        Card greenDrawTwo = new Card(CardColor.GREEN, CardValue.DRAW_TWO);
        hand.addCard(blueDrawTwo);
        hand.addCard(greenDrawTwo);
        hand.addCard(new Card(CardColor.RED, CardValue.ONE));

        BotDecision decision = botEngine.decide(hand, topDrawTwo, CardColor.RED, 2, false);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(2, decision.cards().size());
        assertTrue(decision.cards().containsAll(List.of(blueDrawTwo, greenDrawTwo)));
    }

    @Test
    @DisplayName("Scenario 7: Pending Draw Two Penalty - Bot stacks WILD_DRAW_FOUR when lacking DRAW_TWO")
    void testDrawStackingWildFourOnDrawTwo() {
        Card topDrawTwo = new Card(CardColor.RED, CardValue.DRAW_TWO);
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        hand.addCard(wildFour);
        hand.addCard(new Card(CardColor.GREEN, CardValue.FIVE));
        hand.addCard(new Card(CardColor.GREEN, CardValue.NINE));

        BotDecision decision = botEngine.decide(hand, topDrawTwo, CardColor.RED, 2, false);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(wildFour), decision.cards());
        assertEquals(CardColor.GREEN, decision.chosenWildColor());
    }

    @Test
    @DisplayName("Scenario 8: Pending Wild Draw Four Penalty - Bot stacks WILD_DRAW_FOUR")
    void testDrawStackingWildFourOnWildFour() {
        Card topWildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        hand.addCard(wildFour);
        hand.addCard(new Card(CardColor.BLUE, CardValue.SEVEN));
        hand.addCard(new Card(CardColor.BLUE, CardValue.THREE));

        BotDecision decision = botEngine.decide(
                hand, topWildFour, CardColor.RED, 4, CardValue.WILD_DRAW_FOUR, false, false, false
        );
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(wildFour), decision.cards());
        assertEquals(CardColor.BLUE, decision.chosenWildColor());
    }

    @Test
    @DisplayName("Scenario 9: Pending Wild Draw Four Penalty - Bot cannot stack DRAW_TWO on WILD_DRAW_FOUR")
    void testDrawStackingCannotStackDrawTwoOnWildFour() {
        Card topWildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        hand.addCard(new Card(CardColor.RED, CardValue.DRAW_TWO));
        hand.addCard(new Card(CardColor.RED, CardValue.FIVE));

        BotDecision decision = botEngine.decide(
                hand, topWildFour, CardColor.RED, 4, CardValue.WILD_DRAW_FOUR, false, false, false
        );
        assertEquals(BotAction.DRAW, decision.action());
    }

    @Test
    @DisplayName("Scenario 10: Pending Draw Penalty - Bot with no draw cards returns DRAW")
    void testDrawStackingNoDefendReturnsDraw() {
        Card topDrawTwo = new Card(CardColor.RED, CardValue.DRAW_TWO);
        hand.addCard(new Card(CardColor.RED, CardValue.NINE));
        hand.addCard(new Card(CardColor.WILD, CardValue.WILD));

        BotDecision decision = botEngine.decide(hand, topDrawTwo, CardColor.RED, 2, false);
        assertEquals(BotAction.DRAW, decision.action());
    }

    @Test
    @DisplayName("Scenario 11: Pending Draw Penalty - Bot with no draw cards after drawing returns PASS")
    void testDrawStackingNoDefendAfterDrawReturnsPass() {
        Card topDrawTwo = new Card(CardColor.RED, CardValue.DRAW_TWO);
        hand.addCard(new Card(CardColor.RED, CardValue.NINE));
        hand.addCard(new Card(CardColor.BLUE, CardValue.TWO));

        BotDecision decision = botEngine.decide(
                hand, topDrawTwo, CardColor.RED, 2, CardValue.DRAW_TWO, false, true, false
        );
        assertEquals(BotAction.PASS, decision.action());
    }

    // =========================================================================
    // 3. Regular Turn Card Priority & Combo Selection Scenarios
    // =========================================================================

    @Test
    @DisplayName("Scenario 12: Regular Turn - Prioritizes multi-card combo over single action card and color match")
    void testRegularTurnPrioritizesMultiCardCombo() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        Card redSeven = new Card(CardColor.RED, CardValue.SEVEN);
        Card blueSeven = new Card(CardColor.BLUE, CardValue.SEVEN);
        Card redSkip = new Card(CardColor.RED, CardValue.SKIP);
        Card redNine = new Card(CardColor.RED, CardValue.NINE);

        hand.addCard(redSkip);
        hand.addCard(redNine);
        hand.addCard(blueSeven);
        hand.addCard(redSeven);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(2, decision.cards().size());
        assertEquals(redSeven, decision.cards().get(0), "Initiator card matching table color must be first");
        assertEquals(blueSeven, decision.cards().get(1));
    }

    @Test
    @DisplayName("Scenario 13: Regular Turn - Chooses 3-card combo over 2-card combo")
    void testRegularTurnLargerComboPriority() {
        Card topCard = new Card(CardColor.RED, CardValue.FOUR);
        hand.addCard(new Card(CardColor.RED, CardValue.NINE));
        hand.addCard(new Card(CardColor.BLUE, CardValue.NINE));
        hand.addCard(new Card(CardColor.GREEN, CardValue.FOUR));
        hand.addCard(new Card(CardColor.YELLOW, CardValue.FOUR));
        hand.addCard(new Card(CardColor.BLUE, CardValue.FOUR));

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(3, decision.cards().size());
        assertTrue(decision.cards().stream().allMatch(c -> c.value() == CardValue.FOUR));
    }

    @Test
    @DisplayName("Scenario 14: Regular Turn - Equal-sized combos tie-break by higher point value")
    void testRegularTurnEqualSizeComboHigherPoints() {
        Card topCard = new Card(CardColor.RED, CardValue.ONE);
        hand.addCard(new Card(CardColor.RED, CardValue.THREE));
        hand.addCard(new Card(CardColor.BLUE, CardValue.THREE));
        hand.addCard(new Card(CardColor.RED, CardValue.EIGHT));
        hand.addCard(new Card(CardColor.GREEN, CardValue.EIGHT));

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(2, decision.cards().size());
        assertTrue(decision.cards().stream().allMatch(c -> c.value() == CardValue.EIGHT));
    }

    @Test
    @DisplayName("Scenario 15: Regular Turn - Prioritizes action card over single color match")
    void testRegularTurnActionCardOverColorMatch() {
        Card topCard = new Card(CardColor.BLUE, CardValue.FOUR);
        Card blueReverse = new Card(CardColor.BLUE, CardValue.REVERSE);
        hand.addCard(new Card(CardColor.BLUE, CardValue.NINE));
        hand.addCard(blueReverse);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.BLUE);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(blueReverse), decision.cards());
    }

    @Test
    @DisplayName("Scenario 16: Regular Turn - Action card preference order: DRAW_TWO > SKIP > REVERSE")
    void testRegularTurnActionCardPreferenceOrder() {
        Card topCard = new Card(CardColor.GREEN, CardValue.TWO);
        Card greenReverse = new Card(CardColor.GREEN, CardValue.REVERSE);
        Card greenSkip = new Card(CardColor.GREEN, CardValue.SKIP);
        Card greenDrawTwo = new Card(CardColor.GREEN, CardValue.DRAW_TWO);

        hand.addCard(greenReverse);
        hand.addCard(greenSkip);
        hand.addCard(greenDrawTwo);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.GREEN);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(greenDrawTwo), decision.cards());
    }

    @Test
    @DisplayName("Scenario 17: Regular Turn - Prioritizes color match over value match")
    void testRegularTurnColorMatchOverValueMatch() {
        Card topCard = new Card(CardColor.YELLOW, CardValue.SIX);
        Card yellowTwo = new Card(CardColor.YELLOW, CardValue.TWO);
        Card redSix = new Card(CardColor.RED, CardValue.SIX);

        hand.addCard(redSix);
        hand.addCard(yellowTwo);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.YELLOW);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(yellowTwo), decision.cards());
    }

    @Test
    @DisplayName("Scenario 18: Regular Turn - Color match selects highest face value to shed points")
    void testRegularTurnColorMatchSelectsHighestValue() {
        Card topCard = new Card(CardColor.RED, CardValue.ZERO);
        Card redTwo = new Card(CardColor.RED, CardValue.TWO);
        Card redNine = new Card(CardColor.RED, CardValue.NINE);
        Card redFive = new Card(CardColor.RED, CardValue.FIVE);

        hand.addCard(redTwo);
        hand.addCard(redNine);
        hand.addCard(redFive);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(redNine), decision.cards());
    }

    @Test
    @DisplayName("Scenario 19: Regular Turn - Prioritizes value match over Wild card to conserve Wilds")
    void testRegularTurnValueMatchOverWild() {
        Card topCard = new Card(CardColor.RED, CardValue.SEVEN);
        Card blueSeven = new Card(CardColor.BLUE, CardValue.SEVEN);
        Card wild = new Card(CardColor.WILD, CardValue.WILD);

        hand.addCard(wild);
        hand.addCard(blueSeven);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(blueSeven), decision.cards());
        assertNull(decision.chosenWildColor());
    }

    @Test
    @DisplayName("Scenario 20: Regular Turn - Prefers standard WILD over WILD_DRAW_FOUR")
    void testRegularTurnStandardWildOverWildDrawFour() {
        Card topCard = new Card(CardColor.RED, CardValue.SEVEN);
        Card wild = new Card(CardColor.WILD, CardValue.WILD);
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);

        hand.addCard(wildFour);
        hand.addCard(wild);
        hand.addCard(new Card(CardColor.YELLOW, CardValue.ONE));

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(wild), decision.cards());
        assertEquals(CardColor.YELLOW, decision.chosenWildColor());
    }

    // =========================================================================
    // 4. Wild Color Choice Scenarios
    // =========================================================================

    @Test
    @DisplayName("Scenario 21: Wild Color Choice - Selects most frequent color in remaining hand")
    void testWildColorChoiceMostFrequentColor() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        Card wild = new Card(CardColor.WILD, CardValue.WILD);

        hand.addCard(wild);
        hand.addCard(new Card(CardColor.BLUE, CardValue.ONE));
        hand.addCard(new Card(CardColor.GREEN, CardValue.TWO));
        hand.addCard(new Card(CardColor.GREEN, CardValue.THREE));
        hand.addCard(new Card(CardColor.GREEN, CardValue.FOUR));
        hand.addCard(new Card(CardColor.YELLOW, CardValue.NINE));

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(CardColor.GREEN, decision.chosenWildColor());
        assertTrue(decision.chosenWildColor().isStandardColor());
    }

    @Test
    @DisplayName("Scenario 22: Wild Color Choice - Remaining hand with only Wilds defaults to valid standard color")
    void testWildColorChoiceAllWildsDefaultsToStandardColor() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        Card wild1 = new Card(CardColor.WILD, CardValue.WILD);
        Card wild2 = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);

        hand.addCard(wild1);
        hand.addCard(wild2);

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.PLAY, decision.action());
        assertNotNull(decision.chosenWildColor());
        assertTrue(decision.chosenWildColor().isStandardColor());
        assertEquals(CardColor.RED, decision.chosenWildColor());
    }

    // =========================================================================
    // 5. No Playable Card (DRAW / PASS) Scenarios
    // =========================================================================

    @Test
    @DisplayName("Scenario 23: No Playable Card - Returns DRAW when hasDrawnThisTurn is false")
    void testNoPlayableCardReturnsDraw() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        hand.addCard(new Card(CardColor.BLUE, CardValue.ONE));
        hand.addCard(new Card(CardColor.GREEN, CardValue.TWO));
        hand.addCard(new Card(CardColor.YELLOW, CardValue.NINE));

        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.DRAW, decision.action());
        assertTrue(decision.cards().isEmpty());
    }

    @Test
    @DisplayName("Scenario 24: No Playable Card - Returns PASS when hasDrawnThisTurn is true")
    void testNoPlayableCardAfterDrawReturnsPass() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        hand.addCard(new Card(CardColor.BLUE, CardValue.ONE));
        hand.addCard(new Card(CardColor.GREEN, CardValue.TWO));

        BotDecision decision = botEngine.decide(
                hand, topCard, CardColor.RED, 0, null, false, true, false
        );
        assertEquals(BotAction.PASS, decision.action());
        assertTrue(decision.cards().isEmpty());
    }

    // =========================================================================
    // 6. ULTIMO Declaration & Catch Scenarios
    // =========================================================================

    @Test
    @DisplayName("Scenario 25: ULTIMO Detection - Emits DECLARE_ULTIMO when hand reaches 1 card and not declared")
    void testDetectsOneCardAndEmitsDeclareUltimo() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        hand.addCard(new Card(CardColor.RED, CardValue.NINE));

        assertTrue(botEngine.shouldDeclareUltimo(hand, false));
        BotDecision decision = botEngine.decide(hand, topCard, CardColor.RED);
        assertEquals(BotAction.DECLARE_ULTIMO, decision.action());
    }

    @Test
    @DisplayName("Scenario 26: ULTIMO Detection - Plays final card once ULTIMO has already been declared")
    void testPlaysFinalCardAfterUltimoDeclared() {
        Card topCard = new Card(CardColor.RED, CardValue.FIVE);
        Card finalCard = new Card(CardColor.RED, CardValue.NINE);
        hand.addCard(finalCard);

        assertFalse(botEngine.shouldDeclareUltimo(hand, true));
        BotDecision decision = botEngine.decide(
                hand, topCard, CardColor.RED, 0, null, false, false, true
        );
        assertEquals(BotAction.PLAY, decision.action());
        assertEquals(List.of(finalCard), decision.cards());
    }

    @Test
    @DisplayName("Scenario 27: Catch Detection - Detects opponent with 1 card who failed to declare ULTIMO")
    void testDetectsOpponentViolationAndEmitsCatchUltimo() {
        Player bot = new Player("p1", "BotAlpha");
        Player violator = new Player("p2", "HumanBob");

        UltimoEngine engine = new UltimoEngine(List.of(violator, bot), 101L);
        engine.start();

        // Violator has 2 cards and plays 1 without declaring ULTIMO
        violator.getHand().clear();
        Card playCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        violator.getHand().addCard(playCard);
        violator.getHand().addCard(new Card(CardColor.BLUE, CardValue.TWO));

        engine.playCard(violator, playCard, null);
        assertEquals("HumanBob", engine.getPendingUltimoViolator());

        // Bot evaluates engine state and immediately emits CATCH_ULTIMO
        BotDecision decision = botEngine.decide(engine, bot);
        assertEquals(BotAction.CATCH_ULTIMO, decision.action());
        assertEquals("HumanBob", decision.targetPlayer());

        // Execute catch on engine and verify 6-card penalty
        UltimoEngine.CatchResult catchRes = engine.catchUltimo(bot, decision.targetPlayer());
        assertTrue(catchRes.success());
        assertEquals(7, violator.getHand().size());
    }

    @Test
    @DisplayName("Scenario 28: Catch Detection - Does not emit CATCH_ULTIMO if opponent declared ULTIMO")
    void testDoesNotCatchOpponentWhoDeclaredUltimo() {
        Player bot = new Player("p1", "BotAlpha");
        Player opponent = new Player("p2", "HumanBob");
        opponent.getHand().addCard(new Card(CardColor.RED, CardValue.ONE));

        var catchOpt = botEngine.checkCatchUltimo(
                List.of(bot, opponent),
                null,
                Set.of("HumanBob"),
                "BotAlpha"
        );
        assertTrue(catchOpt.isEmpty());
    }

    @Test
    @DisplayName("Scenario 29: Legal Validation - All PLAY decisions pass MoveValidator.validateMultiMove")
    void testBotPlayDecisionsAlwaysPassMoveValidator() {
        Player p1 = new Player("p1", "Bot1");
        Player p2 = new Player("p2", "Bot2");
        UltimoEngine engine = new UltimoEngine(List.of(p1, p2), 424242L);
        engine.start();

        for (int step = 0; step < 50 && !engine.isGameOver(); step++) {
            Player current = engine.getTurnManager().getCurrentPlayer();
            BotDecision decision = botEngine.decide(engine, current);

            switch (decision.action()) {
                case DECLARE_ULTIMO -> assertTrue(engine.declareUltimo(current));
                case CATCH_ULTIMO -> assertTrue(engine.catchUltimo(current, decision.targetPlayer()).success());
                case PLAY -> {
                    Card top = engine.getDiscardPile().getTopCard().orElse(null);
                    CardColor color = engine.getDiscardPile().getActiveColor();
                    assertTrue(MoveValidator.validateMultiMove(
                            current, current, decision.cards(), top, color, decision.chosenWildColor()
                    ).isEmpty(), "Bot play decision must always be legally valid");
                    assertTrue(engine.playCards(current, decision.cards(), decision.chosenWildColor()).success());
                }
                case DRAW -> {
                    if (engine.getPendingWildChallenge() != null) {
                        engine.acceptWildFourPenalty(current);
                    } else {
                        engine.drawCard(current);
                    }
                }
                case PASS -> assertTrue(engine.passTurn(current));
                default -> fail("Unexpected action: " + decision.action());
            }
        }
    }
}
