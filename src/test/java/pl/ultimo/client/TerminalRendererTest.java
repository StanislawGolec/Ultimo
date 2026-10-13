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
}
