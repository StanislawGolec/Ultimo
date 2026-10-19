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

    @Test
    @DisplayName("Disconnected opponent should display offline grace tag in table view")
    void disconnectedOpponentDisplaysOfflineTag() {
        TerminalRenderer plainRenderer = TerminalRenderer.plain();
        Card topCard = new Card(CardColor.BLUE, CardValue.SEVEN);
        Map<String, Integer> opponents = Map.of("Bob", 4, "Charlie", 6);
        java.util.Set<String> disconnected = java.util.Set.of("Bob");

        String plainTable = plainRenderer.renderTable(topCard, CardColor.BLUE, "Charlie", "Alice", opponents, null, disconnected);
        assertTrue(plainTable.contains("Bob [DISCONNECTED - 2m grace] (4 kart)"),
                "Plain table must format disconnected opponent with offline grace tag");
        assertFalse(plainTable.contains("Charlie [DISCONNECTED"),
                "Connected opponent must not display disconnected tag");

        TerminalRenderer coloredRenderer = TerminalRenderer.colored();
        String coloredTable = coloredRenderer.renderTable(topCard, CardColor.BLUE, "Charlie", "Alice", opponents, null, disconnected);
        assertTrue(coloredTable.contains("[DISCONNECTED - 2m grace]"),
                "Colored table must include offline tag");
        assertTrue(coloredTable.contains("\u001B[93m"),
                "Colored table must include yellow ANSI code for disconnect warning tag");
    }

    @Test
    @DisplayName("Protocol v2 ActionDefenseState should format penalty stack and skip deflection HUD banners")
    void protocolV2ActionDefenseStateFormatsBanners() {
        TerminalRenderer renderer = TerminalRenderer.plain();

        var drawStack = new pl.ultimo.protocol.ServerEvent.ActionDefenseState(CardValue.DRAW_TWO, 6, "Bob");
        assertEquals(
                "⚠️ PENALTY STACK: +6 CARDS! Play a Draw card to defend or type 'd' to take penalty.",
                renderer.renderDefenseBanner(drawStack));

        var skipDeflect = new pl.ultimo.protocol.ServerEvent.ActionDefenseState(CardValue.SKIP, 1, "Charlie");
        assertEquals(
                "🛡️ SKIP DEFLECTED to Charlie!",
                renderer.renderDefenseBanner(skipDeflect));

        var noneState = new pl.ultimo.protocol.ServerEvent.ActionDefenseState(null, 0, "-");
        assertNull(renderer.renderDefenseBanner(noneState));
    }

    @Test
    @DisplayName("Protocol v2 PlayerState should format real-time DISCONNECTED grace and RECONNECTED tags")
    void protocolV2PlayerStateFormatsRealTimeTags() {
        TerminalRenderer plain = TerminalRenderer.plain();

        var discState = new pl.ultimo.protocol.ServerEvent.PlayerState("Bob", "DISCONNECTED", 95);
        assertEquals("[DISCONNECTED - 95s grace]", plain.renderPlayerStateTag(discState));

        var recState = new pl.ultimo.protocol.ServerEvent.PlayerState("Charlie", "RECONNECTED", 0);
        assertEquals("[RECONNECTED]", plain.renderPlayerStateTag(recState));

        Card topCard = new Card(CardColor.RED, CardValue.DRAW_TWO);
        Map<String, Integer> opponents = new java.util.LinkedHashMap<>();
        opponents.put("Bob", 4);
        opponents.put("Charlie", 2);

        var defenseState = new pl.ultimo.protocol.ServerEvent.ActionDefenseState(CardValue.DRAW_TWO, 4, "Bob");
        Map<String, pl.ultimo.protocol.ServerEvent.PlayerState> playerStates = Map.of(
                "Bob", discState,
                "Charlie", recState
        );

        String table = plain.renderTable(topCard, CardColor.RED, "Bob", "Alice", opponents, defenseState, playerStates);
        assertTrue(table.contains("⚠️ PENALTY STACK: +4 CARDS! Play a Draw card to defend or type 'd' to take penalty."));
        assertTrue(table.contains("Bob [DISCONNECTED - 95s grace] (4 kart)"));
        assertTrue(table.contains("Charlie [RECONNECTED] (2 kart)"));
    }

    @Test
    @DisplayName("TerminalClientApp should handle PING heartbeat, Protocol v2 events, addbot, and graceful QUIT")
    void terminalClientHandlesProtocolV2AndCommands() {
        TerminalClientApp client = new TerminalClientApp("localhost", 8080, "Alice", false);
        java.io.StringWriter out = new java.io.StringWriter();
        client.setWriter(new java.io.BufferedWriter(out));

        // 1. Server PING -> immediate PONG\n reply
        client.handleServerLine("PING");
        assertEquals("PONG\n", out.toString());

        // 2. Protocol v2 DEFENSE_STATE and PLAYER_STATE
        client.handleServerLine("DEFENSE_STATE WILD_DRAW_FOUR 8 Bob");
        assertEquals(
                "⚠️ PENALTY STACK: +8 CARDS! Play a Draw card to defend or type 'd' to take penalty.",
                client.getCurrentHudAlert());

        client.handleServerLine("DEFENSE_STATE SKIP 1 Charlie");
        assertEquals("🛡️ SKIP DEFLECTED to Charlie!", client.getCurrentHudAlert());

        client.handleServerLine("PLAYER_STATE Bob DISCONNECTED 110");
        assertEquals("[DISCONNECTED - 110s grace]", client.getPlayerConnectionTags().get("Bob"));
        assertTrue(client.getDisconnectedOpponents().contains("Bob"));

        client.handleServerLine("PLAYER_STATE Bob RECONNECTED 0");
        assertEquals("[RECONNECTED]", client.getPlayerConnectionTags().get("Bob"));
        assertFalse(client.getDisconnectedOpponents().contains("Bob"));

        // 3. Input commands: bot, addbot, and quit sending ADD_BOT and QUIT\n
        out.getBuffer().setLength(0);
        java.util.Scanner scanner = new java.util.Scanner("bot\naddbot\nq\n");
        client.runInputLoop(scanner);
        assertEquals("ADD_BOT\nADD_BOT\nQUIT\n", out.toString());
    }
}
