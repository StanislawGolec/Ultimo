package pl.ultimo.server.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebClientAssets Responsive UI Unit Tests")
class WebClientAssetsTest {

    @Test
    @DisplayName("Web client assets must contain responsive opponents-bar layout supporting up to 16 players")
    void webClientContainsResponsiveOpponentsBar() {
        String html = WebClientAssets.INDEX_HTML;
        assertNotNull(html);
        assertFalse(html.isBlank());

        // Responsive opponents-bar and chip styling
        assertTrue(html.contains(".opponents-bar"), "HTML must style .opponents-bar");
        assertTrue(html.contains("flex-wrap: wrap"), "Opponents bar must wrap chips for large player tables");
        assertTrue(html.contains("overflow-y: auto"), "Opponents bar must scroll vertically if many opponents join");
        assertTrue(html.contains(".opponent-badge"), "HTML must style compact opponent badges");
        assertTrue(html.contains(".opponent-card.turn"), "HTML must style active turn glow indicator");

        // Card icon indicator
        assertTrue(html.contains("🂠"), "HTML must render card symbols in opponent badge");

        // Room creation and browsing UI elements
        assertTrue(html.contains("createRoomModal"), "HTML must contain createRoomModal");
        assertTrue(html.contains("roomBrowserModal"), "HTML must contain roomBrowserModal");
        assertTrue(html.contains("btn-create"), "HTML must contain Create Room button");
        assertTrue(html.contains("btn-browse"), "HTML must contain Browse button");
        assertTrue(html.contains("host-badge"), "HTML must contain host badge");
        assertTrue(html.contains("btn-kick"), "HTML must contain host kick button");
        assertTrue(html.contains("btn-promote"), "HTML must contain host transfer ownership button");

        // UTF-8 byte serialization
        byte[] bytes = WebClientAssets.INDEX_HTML_BYTES;
        assertNotNull(bytes);
        assertEquals(html.getBytes(StandardCharsets.UTF_8).length, bytes.length);
    }
}
