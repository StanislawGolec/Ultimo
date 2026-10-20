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

    @Test
    @DisplayName("Web client must contain Room Access Code UI and copy controls")
    void webClientContainsRoomAccessCodeUI() {
        String html = WebClientAssets.INDEX_HTML;

        // Room Code lobby input
        assertTrue(html.contains("id=\"roomCodeInput\""), "Lobby bar must include Room Code input");
        assertTrue(html.contains("id=\"roomChip\""), "HTML must include room chip header container");
        assertTrue(html.contains("id=\"copyCodeBtn\""), "HTML must include copy room code button");
        assertTrue(html.contains("copyActiveRoomCode()"), "HTML must contain copy room code handler");
        assertTrue(html.contains("activeAccessCode"), "JavaScript must track active room access code");
    }

    @Test
    @DisplayName("Web client must contain interactive SHOUT ULTIMO and CATCH ULTIMO controls")
    void webClientContainsShoutAndCatchUltimoControls() {
        String html = WebClientAssets.INDEX_HTML;

        // Golden animated SHOUT ULTIMO button
        assertTrue(html.contains("id=\"shoutUltimoBtn\""), "HTML must include shoutUltimoBtn");
        assertTrue(html.contains(".btn-shout-ultimo"), "CSS must style animated golden shout button");
        assertTrue(html.contains("shoutUltimo()"), "JS must provide shoutUltimo function");
        assertTrue(html.contains("bouncePulse"), "CSS must define bounce animation for shout button");

        // Red flashing CATCH ULTIMO button for opponents
        assertTrue(html.contains(".btn-catch-ultimo"), "CSS must style red flashing catch button");
        assertTrue(html.contains("catchUltimo("), "JS must define catchUltimo handler for 1-card opponents");
        assertTrue(html.contains("flashAlert"), "CSS must define flash alert keyframes");
    }

    @Test
    @DisplayName("Web client must contain Action Defending & Stacking Visual HUD")
    void webClientContainsActionDefendingAndStackingHUD() {
        String html = WebClientAssets.INDEX_HTML;

        assertTrue(html.contains("id=\"actionHudBanner\""), "Table container must include actionHudBanner");
        assertTrue(html.contains(".action-hud-banner"), "CSS must style actionHudBanner");
        assertTrue(html.contains(".action-hud-banner.stack"), "CSS must style draw penalty stack HUD banner");
        assertTrue(html.contains(".action-hud-banner.deflect"), "CSS must style skip deflection HUD banner");
        assertTrue(html.contains("PENALTY STACK:"), "JS must render penalty stack text");
        assertTrue(html.contains("SKIP DEFLECTED!"), "JS must render skip deflected text");
    }

    @Test
    @DisplayName("Web client must contain Auto-Pass toast notification system")
    void webClientContainsAutoPassNotificationSystem() {
        String html = WebClientAssets.INDEX_HTML;

        assertTrue(html.contains("id=\"toastContainer\""), "HTML must include toastContainer");
        assertTrue(html.contains(".toast-container"), "CSS must style toast container");
        assertTrue(html.contains(".toast.autopass"), "CSS must style auto-pass toast indicator");
        assertTrue(html.contains("showToast("), "JS must provide showToast helper");
    }

    @Test
    @DisplayName("Web client must contain Multi-Tier Podium Standings and Spectator Mode modals")
    void webClientContainsPodiumAndSpectatorModals() {
        String html = WebClientAssets.INDEX_HTML;

        // Spectator mode modal
        assertTrue(html.contains("id=\"spectatorModal\""), "HTML must contain spectator modal");
        assertTrue(html.contains("id=\"spectatorTitle\""), "HTML must contain spectator title");
        assertTrue(html.contains("isPlayerFinished"), "JS must track spectator finish state");

        // Podium standings modal
        assertTrue(html.contains("id=\"podiumModal\""), "HTML must contain podium standings modal");
        assertTrue(html.contains("id=\"podiumLeaderboard\""), "HTML must contain podium leaderboard");
        assertTrue(html.contains(".podium-row.rank-1"), "CSS must style 1st place gold row");
        assertTrue(html.contains(".podium-row.rank-2"), "CSS must style 2nd place silver row");
        assertTrue(html.contains(".podium-row.rank-3"), "CSS must style 3rd place bronze row");
        assertTrue(html.contains(".podium-row.rank-last"), "CSS must style last place row");
    }

    @Test
    @DisplayName("Web client must use CSS clamp() and safe area insets for 400x800 to 4K Ultrawide adaptation")
    void webClientUsesResponsiveClampAndSafeArea() {
        String html = WebClientAssets.INDEX_HTML;

        // CSS clamp() usage
        assertTrue(html.contains("clamp("), "CSS must use clamp() for fluid responsive scaling");
        assertTrue(html.contains("viewport-fit=cover"), "Viewport meta must specify viewport-fit=cover for notch devices");
        assertTrue(html.contains("max-height: 500px"), "CSS must provide media query for mobile landscape orientations");
    }

    @Test
    @DisplayName("Web client must consume Protocol v2 DEFENSE_STATE and PLAYER_STATE events instead of chat scraping")
    void webClientConsumesProtocolV2DefenseAndPlayerStateEvents() {
        String html = WebClientAssets.INDEX_HTML;

        assertTrue(html.contains("case 'DEFENSE_STATE':"), "JS must handle Protocol v2 DEFENSE_STATE wire event");
        assertTrue(html.contains("case 'PLAYER_STATE':"), "JS must handle Protocol v2 PLAYER_STATE wire event");
        assertTrue(html.contains("case 'PING':"), "JS must reply to PING with PONG");
        assertFalse(html.contains("disconnected. They have 2 minutes to rejoin."),
                "JS must not scrape chat broadcast strings for player disconnection");
        assertFalse(html.contains("has reconnected!"),
                "JS must not scrape chat broadcast strings for player reconnection");
    }

    @Test
    @DisplayName("Web client must include real-time turn countdown timer progress bar, Add AI Bot button, and Round Intermission modal")
    void webClientContainsTurnCountdownBotControlsAndRoundIntermissionModal() {
        String html = WebClientAssets.INDEX_HTML;

        // Turn countdown progress bar
        assertTrue(html.contains("id=\"turnTimerContainer\""), "HTML must contain turnTimerContainer");
        assertTrue(html.contains("id=\"turnTimerBar\""), "HTML must contain turnTimerBar");
        assertTrue(html.contains("startTurnCountdown("), "JS must provide startTurnCountdown(timeoutMs) handler");

        // Host one-click Add AI Bot button
        assertTrue(html.contains("id=\"addBotBtn\""), "Lobby bar must include addBotBtn");
        assertTrue(html.contains("Add AI Bot"), "Button label must include 'Add AI Bot'");
        assertTrue(html.contains("addAiBot()"), "JS must provide addAiBot() handler");
        assertTrue(html.contains("id=\"botDifficultySelect\""), "Lobby bar must include botDifficultySelect");
        assertTrue(html.contains("sendCommand('ADD_BOT"), "addAiBot() must send ADD_BOT command");

        // Round Intermission modal between tournament rounds
        assertTrue(html.contains("id=\"roundIntermissionModal\""), "HTML must include roundIntermissionModal");
        assertTrue(html.contains("id=\"intermissionTitle\""), "Modal must include intermissionTitle");
        assertTrue(html.contains("Round 1 Over! Next round in 5s..."), "Modal must display round intermission countdown message");
        assertTrue(html.contains("id=\"intermissionScoresList\""), "Modal must display cumulative tournament scores list");
        assertTrue(html.contains("showRoundIntermissionModal("), "JS must invoke showRoundIntermissionModal on in-progress ROUND_OVER");
    }

    @Test
    @DisplayName("Web client must enforce strict Action Defense card playable restrictions and UI button state")
    void webClientEnforcesActionDefendingCardRestrictionsAndUI() {
        String html = WebClientAssets.INDEX_HTML;

        // isPlayableCard defense filtering
        assertTrue(html.contains("if (defenseStateActive && currentDefenseTarget === myUsername)"),
                "isPlayableCard must check active defense against current player");
        assertTrue(html.contains("currentDefenseType === 'SKIP'"),
                "isPlayableCard must handle SKIP defense");
        assertTrue(html.contains("currentDefenseType === 'DRAW_TWO'"),
                "isPlayableCard must handle DRAW_TWO defense");
        assertTrue(html.contains("currentDefenseType === 'WILD_DRAW_FOUR'"),
                "isPlayableCard must handle WILD_DRAW_FOUR defense");

        // isComboPlayable defense filtering
        assertTrue(html.contains("return tokens.every(t => isPlayableCard(t));"),
                "isComboPlayable must require all combo cards to pass isPlayableCard during active defense");

        // pass and draw button enablements during defense
        assertTrue(html.contains("drawBtn.disabled = false;"),
                "updateTurnUI must enable drawBtn during active defense");
        assertTrue(html.contains("passBtn.disabled = false;"),
                "updateTurnUI must enable passBtn during active defense");
    }

    @Test
    @DisplayName("Web client must contain Leave Room button, confirmation handler, and handle ROOM_LEFT event")
    void webClientContainsLeaveRoomControlsAndEventHandling() {
        String html = WebClientAssets.INDEX_HTML;

        // Leave Room button and style
        assertTrue(html.contains("id=\"leaveRoomBtn\""), "HTML must contain leaveRoomBtn");
        assertTrue(html.contains(".btn-leave"), "CSS must style .btn-leave");
        assertTrue(html.contains("leaveRoom()"), "JS must provide leaveRoom() handler");
        assertTrue(html.contains("sendCommand('LEAVE_ROOM')"), "leaveRoom() must send LEAVE_ROOM command");

        // Event handler for ROOM_LEFT
        assertTrue(html.contains("case 'ROOM_LEFT':"), "JS must handle ROOM_LEFT server event");
        assertTrue(html.contains("Left room. Join another room or quick play!"), "UI must inform user when room is left");
    }

    @Test
    @DisplayName("Web client must contain contextual header controls with Player Badge and navigation mode state machine")
    void webClientContainsContextualHeaderNavigationControls() {
        String html = WebClientAssets.INDEX_HTML;

        // Player Badge container
        assertTrue(html.contains("id=\"playerBadge\""), "HTML must include playerBadge container");
        assertTrue(html.contains("id=\"playerBadgeText\""), "HTML must include playerBadgeText span");
        assertTrue(html.contains(".player-chip"), "CSS must style player badge chip");

        // Contextual header switching functions
        assertTrue(html.contains("setNavigationMode("), "JS must define setNavigationMode handler");
        assertTrue(html.contains("updateRoomChipDisplay("), "JS must define updateRoomChipDisplay handler");
        assertTrue(html.contains("activeAccessCode || currentRoomId"),
                "copyActiveRoomCode must copy accessCode for private rooms or currentRoomId for public rooms");
    }

    @Test
    @DisplayName("Web client must enforce strict bounded viewport layout and chat log retention capping")
    void webClientEnforcesBoundedViewportAndChatPinning() {
        String html = WebClientAssets.INDEX_HTML;

        // Viewport height locking on desktop
        assertTrue(html.contains("height: 100vh; max-height: 100vh; display: flex; flex-direction: column; overflow: hidden;"),
                "Body must enforce strict 100vh viewport bounding without window scrolling");
        assertTrue(html.contains("flex-shrink: 0;"), "Header and chat box must prevent vertical collapsing");

        // Main grid and table flex sizing
        assertTrue(html.contains(".table-container {"), "Table container must be styled");
        assertTrue(html.contains(".sidebar {"), "Sidebar must be styled");
        assertTrue(html.contains("min-height: 0;"), "Main grid and flex children must define min-height: 0 to prevent intrinsic overflow expansion");

        // Log container internal scrolling and overscroll behavior
        assertTrue(html.contains(".log-container {"), "Log container must be styled");
        assertTrue(html.contains("overscroll-behavior: contain;"), "Log container must contain overscroll chaining");

        // Rolling DOM retention capping
        assertTrue(html.contains("logContainer.children.length > 200"),
                "logMessage must cap chat DOM retention to prevent memory bloat");
        assertTrue(html.contains("logContainer.removeChild(logContainer.firstChild);"),
                "logMessage must evict oldest log nodes when cap exceeded");
    }
}
