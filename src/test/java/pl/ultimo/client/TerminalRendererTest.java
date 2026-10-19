package pl.ultimo.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerminalRenderer Unit Tests")
class TerminalRendererTest {

    @Test
    @DisplayName("Plain text renderer should format cards without ANSI escape sequences")
    void plainTextRendererFormatsCardsCleanly() {
        TerminalRenderer renderer = TerminalRenderer.plain();
        assertFalse(renderer.isAnsiEnabled());

        Card redFive = new Card(CardColor.RED, CardValue.FIVE);
        String rendered = renderer.renderCard(redFive);

        assertEquals("[RED 5]", rendered);
        assertFalse(rendered.contains("\u001B"));

        Card wild = new Card(CardColor.WILD, CardValue.WILD);
        assertEquals("[WILD WILD]", renderer.renderCard(wild));

        Card drawTwo = new Card(CardColor.GREEN, CardValue.DRAW_TWO);
        assertEquals("[GREEN +2]", renderer.renderCard(drawTwo));
    }

    @Test
    @DisplayName("Colored renderer should include ANSI escape codes")
    void coloredRendererIncludesAnsiCodes() {
        TerminalRenderer renderer = TerminalRenderer.colored();
        assertTrue(renderer.isAnsiEnabled());

        Card blueSkip = new Card(CardColor.BLUE, CardValue.SKIP);
        String rendered = renderer.renderCard(blueSkip);

        assertTrue(rendered.contains("\u001B[44m")); // Blue background
        assertTrue(rendered.contains("\u001B[0m"));  // Reset code
        assertTrue(rendered.contains("SKIP"));
    }

    @Test
    @DisplayName("Hand renderer should produce 1-based indexed card list")
    void handRendererProducesIndexedList() {
        TerminalRenderer renderer = TerminalRenderer.plain();
        List<Card> hand = List.of(
                new Card(CardColor.RED, CardValue.ONE),
                new Card(CardColor.YELLOW, CardValue.SEVEN)
        );

        String rendered = renderer.renderHand(hand);
        assertTrue(rendered.contains("[1] [RED 1]"));
        assertTrue(rendered.contains("[2] [YELLOW 7]"));
    }

    @Test
    @DisplayName("Table renderer should format top card, active color, and opponent counts")
    void tableRendererFormatsFullView() {
        TerminalRenderer renderer = TerminalRenderer.plain();
        Card topCard = new Card(CardColor.RED, CardValue.THREE);
        Map<String, Integer> opponents = Map.of("Bob", 5, "Charlie", 7);

        String table = renderer.renderTable(topCard, CardColor.RED, "Alice", "Alice", opponents);

        assertTrue(table.contains("STÓŁ ULTIMO"));
        assertTrue(table.contains("[RED 3]"));
        assertTrue(table.contains("Bob (5 kart)"));
        assertTrue(table.contains("Charlie (7 kart)"));
        assertTrue(table.contains("TWOJA TURA!"));
    }

    @Test
    @DisplayName("Table renderer should display HUD alert banner when penalty or deflection is active")
    void tableRendererDisplaysHudAlertBanner() {
        TerminalRenderer plainRenderer = TerminalRenderer.plain();
        Card topCard = new Card(CardColor.BLUE, CardValue.DRAW_TWO);
        Map<String, Integer> opponents = Map.of("Bob", 3);

        String alertText = "⚠️ PENALTY STACK: +4 CARDS! Play a Draw card to defend or click Draw to take penalty.";
        String plainTable = plainRenderer.renderTable(topCard, CardColor.BLUE, "Bob", "Alice", opponents, alertText);

        assertTrue(plainTable.contains(alertText), "Plain table must include HUD alert text");

        TerminalRenderer coloredRenderer = TerminalRenderer.colored();
        String coloredTable = coloredRenderer.renderTable(topCard, CardColor.BLUE, "Bob", "Alice", opponents, alertText);
        assertTrue(coloredTable.contains(alertText), "Colored table must include HUD alert text");
        assertTrue(coloredTable.contains("\u001B[43m"), "Colored table HUD alert must include yellow background ANSI code");
    }

    @Test
    @DisplayName("Opponent with 1 card should display red CATCH alert prompt in terminal")
    void opponentWithOneCardDisplaysCatchAlert() {
        TerminalRenderer plainRenderer = TerminalRenderer.plain();
        Card topCard = new Card(CardColor.GREEN, CardValue.FIVE);
        Map<String, Integer> opponents = Map.of("Dave", 1, "Eve", 4);

        String table = plainRenderer.renderTable(topCard, CardColor.GREEN, "Eve", "Alice", opponents);
        assertTrue(table.contains("Dave (1 KARTA! [catch Dave])"), "Table should format 1-card opponent with catch prompt");
        assertTrue(table.contains("Eve (4 kart)"), "Non-1-card opponent formatted normally");
    }

    @Test
    @DisplayName("Podium renderer should format 1st place winner and runners-up standings")
    void podiumRendererFormatsStandings() {
        TerminalRenderer renderer = TerminalRenderer.plain();
        Map<String, Integer> scores = new java.util.LinkedHashMap<>();
        scores.put("Alice", 520);
        scores.put("Bob", 310);
        scores.put("Charlie", 120);

        String podium = renderer.renderPodium("Alice", scores);
        assertTrue(podium.contains("🥇 1st PLACE (Winner): Alice"));
        assertTrue(podium.contains("🥈 2nd PLACE: Bob (310 pkt)"));
        assertTrue(podium.contains("🥉 3rd PLACE: Charlie (120 pkt)"));
    }
}
