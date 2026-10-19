package pl.ultimo.server.net;

/**
 * Embedded HTML5/CSS/JavaScript single-page application for the Ultimo web client.
 * Self-contained with zero external CDN dependencies.
 * Adaptive from mobile portrait (400x800) to 4K Ultrawide displays.
 */
public final class WebClientAssets {

    public static final String INDEX_HTML = getHtmlPage();
    public static final byte[] INDEX_HTML_BYTES = INDEX_HTML.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private WebClientAssets() {
    }

    public static byte[] getHtmlBytes() {
        return INDEX_HTML_BYTES;
    }

    public static String getHtmlPage() {
        return getHtmlMarkup() + getHtmlScript();
    }

    private static String getHtmlMarkup() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
  <title>Ultimo Multiplayer | Web Edition</title>
  <style>
    :root {
      --bg: #0f172a;
      --felt: #064e3b;
      --panel: #1e293b;
      --text: #f8fafc;
      --muted: #94a3b8;
      --accent: #38bdf8;
      --red: #ef4444;
      --green: #22c55e;
      --blue: #3b82f6;
      --yellow: #eab308;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
    body { background: var(--bg); color: var(--text); min-height: 100vh; display: flex; flex-direction: column; overflow-x: hidden; }
    header { background: var(--panel); padding: clamp(8px, 1.2vh, 16px) clamp(12px, 2vw, 32px); display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; flex-wrap: wrap; gap: 10px; }
    .brand { font-size: clamp(1.2rem, 2vw, 1.8rem); font-weight: 800; letter-spacing: 1px; color: #f59e0b; display: flex; align-items: center; gap: 8px; }
    .badge { font-size: 0.75rem; padding: 3px 8px; border-radius: 9999px; background: #3b82f6; color: white; text-transform: uppercase; font-weight: 700; }
    .lobby-bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .room-chip { background: #1e293b; border: 1px solid #38bdf8; color: #38bdf8; padding: 4px 10px; border-radius: 9999px; font-size: 0.8rem; font-weight: 700; display: flex; align-items: center; gap: 6px; box-shadow: 0 0 10px rgba(56, 189, 248, 0.2); }
    .btn-copy { background: #0284c7; color: white; border: none; padding: 2px 8px; border-radius: 4px; font-size: 0.72rem; font-weight: 700; cursor: pointer; transition: background 0.15s; }
    .btn-copy:hover { background: #0369a1; }
    input[type="text"] { background: #0f172a; border: 1px solid #475569; color: white; padding: 8px 12px; border-radius: 6px; outline: none; font-size: 0.95rem; }
    input[type="text"]:focus { border-color: var(--accent); }
    button { background: #2563eb; color: white; border: none; padding: 8px 16px; border-radius: 6px; font-weight: 600; cursor: pointer; transition: background 0.15s, transform 0.1s; }
    button:hover:not(:disabled) { background: #1d4ed8; transform: translateY(-1px); }
    button:disabled { opacity: 0.4; cursor: not-allowed; }
    .btn-draw { background: #d97706; }
    .btn-draw:hover:not(:disabled) { background: #b45309; }
    .btn-pass { background: #475569; }
    .btn-pass:hover:not(:disabled) { background: #334155; }
    .btn-start { background: #16a34a; }
    .btn-bot { background: #0d9488; color: white; }
    .btn-bot:hover:not(:disabled) { background: #0f766e; }
    .btn-create { background: #8b5cf6; }
    .btn-create:hover:not(:disabled) { background: #7c3aed; }
    .btn-browse { background: #0284c7; }
    .btn-browse:hover:not(:disabled) { background: #0369a1; }
    .btn-kick { background: #ef4444; color: white; padding: 2px 6px; border-radius: 4px; font-size: 0.75rem; margin-left: 6px; cursor: pointer; border: none; }
    .btn-kick:hover { background: #dc2626; }
    .btn-promote { background: #eab308; color: black; padding: 2px 6px; border-radius: 4px; font-size: 0.75rem; margin-left: 4px; cursor: pointer; border: none; font-weight: bold; }
    .btn-promote:hover { background: #ca8a04; }
    .btn-shout-ultimo { background: linear-gradient(135deg, #f59e0b 0%, #fbbf24 50%, #d97706 100%); color: #0f172a; font-weight: 900; font-size: clamp(0.75rem, 0.9vw, 0.95rem); padding: 6px 14px; border-radius: 9999px; border: 2px solid #fef08a; box-shadow: 0 0 14px rgba(245, 158, 11, 0.8); cursor: pointer; animation: bouncePulse 1s infinite alternate; text-transform: uppercase; letter-spacing: 0.5px; }
    @keyframes bouncePulse { 0% { transform: scale(1); box-shadow: 0 0 10px rgba(245, 158, 11, 0.6); } 100% { transform: scale(1.06); box-shadow: 0 0 20px rgba(251, 191, 36, 1); } }
    .btn-catch-ultimo { background: #dc2626; color: white; font-weight: 800; font-size: 0.7rem; padding: 3px 8px; border-radius: 6px; border: 1px solid #f87171; cursor: pointer; margin-left: 4px; animation: flashAlert 0.8s infinite; }
    @keyframes flashAlert { 0%, 100% { background: #dc2626; box-shadow: 0 0 4px #ef4444; } 50% { background: #991b1b; box-shadow: 0 0 12px #f87171; } }
    .host-badge { background: #eab308; color: #0f172a; font-weight: 800; font-size: 0.7rem; padding: 2px 6px; border-radius: 4px; margin-left: 4px; }
    .room-table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 0.9rem; text-align: left; }
    .room-table th, .room-table td { padding: 8px 12px; border-bottom: 1px solid #334155; }
    .room-table th { background: #0f172a; color: #94a3b8; }
    .modal select, .modal input[type="text"] { background: #0f172a; border: 1px solid #475569; color: white; padding: 8px 12px; border-radius: 6px; outline: none; }
    main { flex: 1; display: grid; grid-template-columns: 1fr 340px; gap: 16px; padding: clamp(8px, 1.5vh, 20px); max-width: clamp(1000px, 92vw, 2560px); margin: 0 auto; width: 100%; }
    @media (max-width: 900px) { main { grid-template-columns: 1fr; } }
    .table-container { background: radial-gradient(circle, var(--felt) 0%, #022c22 100%); border-radius: 16px; border: 3px solid #047857; box-shadow: inset 0 0 40px rgba(0,0,0,0.6); display: flex; flex-direction: column; justify-content: space-between; padding: clamp(10px, 1.8vh, 24px); min-height: clamp(340px, 62vh, 850px); position: relative; }
    .opponents-bar { display: flex; justify-content: center; gap: 8px; min-height: 52px; max-height: 130px; overflow-y: auto; flex-wrap: wrap; padding: 4px; align-content: flex-start; }
    .opponent-card { background: rgba(15, 23, 42, 0.85); border: 1px solid #334155; border-radius: 8px; padding: 4px 10px; display: flex; flex-direction: row; align-items: center; gap: 8px; transition: all 0.15s ease; font-size: 0.85rem; }
    .opponent-card.turn { border-color: #facc15; background: rgba(250, 204, 21, 0.15); box-shadow: 0 0 12px rgba(250, 204, 21, 0.5); }
    .opponent-card.disconnected { border-color: #f59e0b; opacity: 0.75; background: rgba(30, 27, 75, 0.6); }
    .badge-disconnected { background: #78350f; color: #fde68a; border: 1px solid #d97706; padding: 2px 6px; border-radius: 4px; font-size: 0.72rem; font-weight: 700; display: inline-block; animation: pulseDisconnect 2s infinite ease-in-out; }
    @keyframes pulseDisconnect { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.6; transform: scale(0.97); } }
    .opponent-name { font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 110px; }
    .opponent-badge { background: #1e293b; border: 1px solid #475569; border-radius: 9999px; padding: 2px 8px; font-size: 0.75rem; font-weight: 700; color: #38bdf8; white-space: nowrap; }
    .opponent-card.turn .opponent-badge { background: #facc15; color: #0f172a; border-color: #facc15; }
    .action-hud-banner { padding: 8px 18px; border-radius: 10px; font-weight: 800; font-size: clamp(0.85rem, 1.2vw, 1.05rem); text-align: center; margin: 4px auto; width: fit-content; max-width: 90%; animation: hudPulse 1.2s infinite alternate; z-index: 5; }
    .action-hud-banner.stack { background: rgba(220, 38, 38, 0.25); border: 2px solid #ef4444; color: #fca5a5; box-shadow: 0 0 20px rgba(239, 68, 68, 0.5); }
    .action-hud-banner.deflect { background: rgba(14, 165, 233, 0.25); border: 2px solid #38bdf8; color: #7dd3fc; box-shadow: 0 0 20px rgba(56, 189, 248, 0.5); }
    @keyframes hudPulse { 0% { transform: scale(1); opacity: 0.9; } 100% { transform: scale(1.03); opacity: 1; } }
    .center-table { display: flex; align-items: center; justify-content: center; gap: clamp(16px, 4vw, 50px); margin: auto; }
    .pile-box { display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .pile-label { font-size: 0.8rem; font-weight: 700; color: #a7f3d0; text-transform: uppercase; letter-spacing: 0.5px; }
    .deck-pile { width: clamp(62px, 6.5vw, 100px); height: clamp(90px, 9.8vw, 145px); border-radius: 10px; background: repeating-linear-gradient(45deg, #1e293b, #1e293b 10px, #0f172a 10px, #0f172a 20px); border: 2px solid #64748b; display: flex; align-items: center; justify-content: center; cursor: pointer; box-shadow: 0 8px 16px rgba(0,0,0,0.4); font-weight: 800; font-size: clamp(0.85rem, 1.2vw, 1.1rem); color: #94a3b8; transition: transform 0.15s; }
    .deck-pile:hover { transform: scale(1.05); }
    .turn-banner { background: rgba(15, 23, 42, 0.9); border: 2px solid #38bdf8; border-radius: 9999px; padding: 6px 20px; font-weight: 700; text-align: center; margin: 8px auto; width: fit-content; font-size: clamp(0.85rem, 1vw, 1rem); }
    .turn-banner.my-turn { border-color: #22c55e; background: rgba(34, 197, 94, 0.25); animation: pulse 1.5s infinite; }
    .turn-timer-container { width: clamp(200px, 50%, 420px); margin: 2px auto 8px auto; text-align: center; }
    .turn-timer-track { width: 100%; height: 8px; background: rgba(15, 23, 42, 0.85); border: 1px solid #334155; border-radius: 9999px; overflow: hidden; }
    .turn-timer-bar { height: 100%; width: 100%; background: linear-gradient(90deg, #22c55e, #eab308, #ef4444); transition: width 0.1s linear; border-radius: 9999px; }
    .turn-timer-text { font-size: 0.72rem; color: #cbd5e1; font-weight: 700; display: block; margin-top: 2px; }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
    .hand-area { background: rgba(15, 23, 42, 0.6); border-radius: 12px; padding: clamp(8px, 1.2vh, 14px); }
    .hand-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; flex-wrap: wrap; gap: 8px; }
    .hand-cards { display: flex; gap: clamp(6px, 1vw, 12px); overflow-x: auto; padding: 8px 4px 14px 4px; justify-content: center; min-height: clamp(105px, 12vw, 170px); }
    .card { width: clamp(62px, 6.5vw, 100px); height: clamp(90px, 9.8vw, 145px); border-radius: 10px; border: 3px solid white; display: flex; flex-direction: column; justify-content: space-between; padding: 5px; box-shadow: 0 6px 12px rgba(0,0,0,0.35); cursor: pointer; transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s; user-select: none; flex-shrink: 0; position: relative; }
    .card:hover { transform: translateY(-10px); box-shadow: 0 14px 20px rgba(0,0,0,0.5); z-index: 10; }
    .card.playable { border-color: #fef08a; box-shadow: 0 0 14px rgba(254, 240, 138, 0.8), 0 0 4px white; }
    .card.selected { transform: translateY(-20px) scale(1.05); border-color: #38bdf8; box-shadow: 0 0 22px rgba(56, 189, 248, 0.95), 0 15px 25px rgba(0,0,0,0.6); z-index: 25; }
    .btn-play { background: #22c55e; color: white; padding: 6px 14px; border-radius: 6px; font-weight: 700; cursor: pointer; border: 1px solid #16a34a; transition: all 0.15s; }
    .btn-play:hover { transform: scale(1.05); background: #16a34a; }
    .card-top { font-size: clamp(0.7rem, 0.85vw, 0.9rem); font-weight: 900; }
    .card-center { font-size: clamp(1.1rem, 1.5vw, 1.6rem); font-weight: 900; align-self: center; margin: auto; background: white; border-radius: 50%; width: clamp(34px, 4vw, 54px); height: clamp(34px, 4vw, 54px); display: flex; align-items: center; justify-content: center; color: black; box-shadow: inset 0 0 4px rgba(0,0,0,0.4); text-align: center; line-height: 1; }
    .card-bottom { font-size: clamp(0.7rem, 0.85vw, 0.9rem); font-weight: 900; align-self: flex-end; transform: rotate(180deg); }
    .card-red { background: var(--red); color: white; }
    .card-green { background: var(--green); color: white; }
    .card-blue { background: var(--blue); color: white; }
    .card-yellow { background: var(--yellow); color: black; }
    .card-wild { background: linear-gradient(135deg, var(--red) 25%, var(--blue) 25% 50%, var(--yellow) 50% 75%, var(--green) 75%); color: white; }
    .card-wild .card-center { background: #0f172a; color: white; }
    .sidebar { background: var(--panel); border-radius: 16px; border: 1px solid #334155; display: flex; flex-direction: column; overflow: hidden; height: 100%; min-height: 480px; }
    .sidebar-header { padding: 12px 16px; border-bottom: 1px solid #334155; font-weight: 700; display: flex; justify-content: space-between; align-items: center; }
    .log-container { flex: 1; padding: 12px; overflow-y: auto; display: flex; flex-direction: column; gap: 6px; font-size: 0.85rem; }
    .log-item { padding: 6px 10px; border-radius: 6px; background: #0f172a; border-left: 3px solid #3b82f6; word-break: break-word; }
    .log-item.chat { border-left-color: #a855f7; }
    .log-item.error { border-left-color: #ef4444; color: #fca5a5; }
    .log-item.win { border-left-color: #eab308; background: #422006; font-weight: bold; }
    .log-item.autopass { border-left-color: #f59e0b; background: rgba(245, 158, 11, 0.15); color: #fde68a; font-weight: 600; }
    .chat-box { padding: 10px; border-top: 1px solid #334155; display: flex; gap: 8px; }
    .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.75); display: none; align-items: center; justify-content: center; z-index: 100; backdrop-filter: blur(4px); }
    .modal { background: var(--panel); border: 1px solid #475569; border-radius: 12px; padding: 24px; max-width: 440px; width: 90%; text-align: center; box-shadow: 0 20px 40px rgba(0,0,0,0.6); }
    .modal-title { font-size: 1.25rem; font-weight: 800; margin-bottom: 16px; }
    .color-options { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .btn-color { height: 50px; font-weight: 800; font-size: 1.1rem; border-radius: 8px; }
    .toast-container { position: fixed; bottom: 24px; right: 24px; display: flex; flex-direction: column; gap: 10px; z-index: 200; pointer-events: none; }
    .toast { background: rgba(15, 23, 42, 0.95); border: 1px solid #38bdf8; border-left: 4px solid #38bdf8; color: #f8fafc; padding: 12px 18px; border-radius: 8px; font-size: 0.9rem; font-weight: 600; box-shadow: 0 10px 25px rgba(0,0,0,0.5); display: flex; align-items: center; gap: 10px; animation: toastSlideIn 0.3s ease; pointer-events: auto; }
    .toast.autopass { border-color: #f59e0b; border-left-color: #f59e0b; }
    .toast.disconnected { border-color: #f59e0b; border-left-color: #f59e0b; color: #fef3c7; }
    .toast.reconnect { border-color: #10b981; border-left-color: #10b981; color: #d1fae5; }
    @keyframes toastSlideIn { from { transform: translateX(100%); opacity: 0; } to { transform: translateX(0); opacity: 1; } }
    .podium-row { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; border-radius: 8px; background: rgba(15, 23, 42, 0.8); border: 1px solid #334155; font-weight: 700; }
    .podium-row.rank-1 { border-color: #facc15; background: rgba(250, 204, 21, 0.15); color: #fef08a; }
    .podium-row.rank-2 { border-color: #94a3b8; background: rgba(148, 163, 184, 0.15); color: #f1f5f9; }
    .podium-row.rank-3 { border-color: #b45309; background: rgba(180, 83, 9, 0.15); color: #fdba74; }
    .podium-row.rank-last { border-color: #ef4444; background: rgba(239, 68, 68, 0.15); color: #fca5a5; }
    @media (max-height: 500px) {
      .table-container { min-height: 300px; padding: 8px; }
      .deck-pile, .card { width: 55px; height: 80px; }
      .opponents-bar { max-height: 70px; }
    }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      ULTIMO
      <span class="badge">Web Client</span>
    </div>
    <div class="lobby-bar">
      <input type="text" id="usernameInput" placeholder="Enter username..." value="Player1">
      <input type="text" id="roomCodeInput" placeholder="Room Code (optional)" style="width: 140px;">
      <button id="joinBtn">Quick Play</button>
      <button id="openCreateBtn" class="btn-create">Create Room</button>
      <button id="openBrowserBtn" class="btn-browse">Browse</button>
      <button id="addBotBtn" class="btn-bot" style="display: none;" onclick="addAiBot()">🤖 Add AI Bot</button>
      <button id="startBtn" class="btn-start" style="display: none;">Start Match</button>
      <div id="roomChip" class="room-chip" style="display: none;">
        <span id="roomChipText">Room: -</span>
        <button id="copyCodeBtn" class="btn-copy" style="display: none;" onclick="copyActiveRoomCode()" title="Copy Room Code">📋 Copy Code</button>
      </div>
      <span id="connStatus" style="font-size: 0.85rem; color: #94a3b8;">Disconnected</span>
    </div>
  </header>

  <main>
    <div class="table-container">
      <div class="opponents-bar" id="opponentsBar">
        <div class="opponent-card">
          <div class="opponent-name">Waiting for players...</div>
          <div class="opponent-count">Lobby open</div>
        </div>
      </div>

      <div id="actionHudBanner" class="action-hud-banner" style="display: none;"></div>

      <div class="turn-banner" id="turnBanner">Connect and click 'Quick Play' to start</div>
      <div class="turn-timer-container" id="turnTimerContainer" style="display: none;">
        <div class="turn-timer-track">
          <div class="turn-timer-bar" id="turnTimerBar"></div>
        </div>
        <span class="turn-timer-text" id="turnTimerText"></span>
      </div>

      <div class="center-table">
        <div class="pile-box">
          <div class="pile-label">Draw Pile</div>
          <div class="deck-pile" id="deckPile" title="Click to draw a card">ULTIMO</div>
        </div>
        <div class="pile-box">
          <div class="pile-label">Active Discard</div>
          <div id="discardCardContainer">
            <div class="card card-red"><div class="card-center">?</div></div>
          </div>
        </div>
      </div>

      <div class="hand-area">
        <div class="hand-header">
          <div style="font-weight: 700;">Your Hand (<span id="handCount">0</span>)</div>
          <div style="display: flex; gap: 8px; align-items: center;">
            <button id="shoutUltimoBtn" class="btn-shout-ultimo" style="display: none;" onclick="shoutUltimo()">🔔 SHOUT ULTIMO!</button>
            <button class="btn-play" id="playBtn" style="display: none;">Play Card</button>
            <button class="btn-draw" id="drawBtn" disabled>Draw Card</button>
            <button class="btn-pass" id="passBtn" disabled>Pass Turn</button>
          </div>
        </div>
        <div class="hand-cards" id="handCards"></div>
      </div>
    </div>

    <aside class="sidebar">
      <div class="sidebar-header">
        <span>Game Events & Chat</span>
        <span id="roomInfo" style="color: var(--muted); font-size: 0.8rem; display: none;"></span>
      </div>
      <div class="log-container" id="logContainer">
        <div class="log-item">Welcome to Ultimo! Choose your username and join the matchmaking queue.</div>
      </div>
      <div class="chat-box">
        <input type="text" id="chatInput" placeholder="Type a chat message..." style="flex: 1;">
        <button id="sendChatBtn">Send</button>
      </div>
    </aside>
  </main>

  <div class="modal-overlay" id="colorModal">
    <div class="modal">
      <div class="modal-title">Choose Wild Card Color</div>
      <div class="color-options">
        <button class="btn-color" style="background: var(--red);" onclick="selectWildColor('RED')">RED</button>
        <button class="btn-color" style="background: var(--green);" onclick="selectWildColor('GREEN')">GREEN</button>
        <button class="btn-color" style="background: var(--blue);" onclick="selectWildColor('BLUE')">BLUE</button>
        <button class="btn-color" style="background: var(--yellow); color: black;" onclick="selectWildColor('YELLOW')">YELLOW</button>
      </div>
      <button onclick="closeColorModal()" style="background: #475569; width: 100%;">Cancel</button>
    </div>
  </div>

  <div class="modal-overlay" id="createRoomModal">
    <div class="modal" style="text-align: left;">
      <div class="modal-title" style="text-align: center;">Create New Room</div>
      <div style="margin-bottom: 12px;">
        <label style="display:block; font-size: 0.85rem; color: #94a3b8; margin-bottom: 4px;">Room Name</label>
        <input type="text" id="createRoomName" placeholder="My Room" value="Casual Table" style="width: 100%;">
      </div>
      <div style="margin-bottom: 12px;">
        <label style="display:block; font-size: 0.85rem; color: #94a3b8; margin-bottom: 4px;">Visibility</label>
        <select id="createRoomVisibility" style="width: 100%;" onchange="toggleAccessCodeField()">
          <option value="PUBLIC">Public (Listed in Room Browser)</option>
          <option value="PRIVATE">Private (Requires Access Code)</option>
        </select>
      </div>
      <div id="accessCodeGroup" style="margin-bottom: 12px; display: none;">
        <label style="display:block; font-size: 0.85rem; color: #94a3b8; margin-bottom: 4px;">Access Code</label>
        <input type="text" id="createRoomCode" placeholder="e.g. 1234" style="width: 100%;">
      </div>
      <div style="margin-bottom: 16px;">
        <label style="display:block; font-size: 0.85rem; color: #94a3b8; margin-bottom: 4px;">Max Players</label>
        <select id="createRoomMaxPlayers" style="width: 100%;">
          <option value="4" selected>4 Players (Classic)</option>
          <option value="2">2 Players (Duel)</option>
          <option value="8">8 Players</option>
          <option value="16">16 Players (Mega Table)</option>
        </select>
      </div>
      <div style="display: flex; gap: 8px;">
        <button id="confirmCreateRoomBtn" style="flex: 1; background: #16a34a;">Create & Host</button>
        <button onclick="closeCreateRoomModal()" style="background: #475569;">Cancel</button>
      </div>
    </div>
  </div>

  <div class="modal-overlay" id="roomBrowserModal">
    <div class="modal" style="max-width: 600px; text-align: left;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
        <div class="modal-title" style="margin-bottom: 0;">Public Rooms</div>
        <button id="refreshRoomsBtn" style="background: #0284c7; padding: 4px 10px; font-size: 0.85rem;">↻ Refresh</button>
      </div>
      <div style="max-height: 300px; overflow-y: auto;">
        <table class="room-table">
          <thead>
            <tr><th>Room</th><th>Players</th><th>Status</th><th>Action</th></tr>
          </thead>
          <tbody id="roomListBody">
            <tr><td colspan="4" style="text-align: center; color: #94a3b8;">Loading rooms...</td></tr>
          </tbody>
        </table>
      </div>
      <button onclick="closeRoomBrowserModal()" style="background: #475569; width: 100%; margin-top: 14px;">Close</button>
    </div>
  </div>

  <div class="modal-overlay" id="spectatorModal">
    <div class="modal" style="max-width: 480px; text-align: center;">
      <div style="font-size: 3rem; margin-bottom: 8px;">🏆</div>
      <div class="modal-title" style="color: #facc15; font-size: 1.5rem;" id="spectatorTitle">1st PLACE!</div>
      <p style="color: #94a3b8; margin-bottom: 16px;">You finished! Spectating remaining players...</p>
      <button onclick="closeSpectatorModal()" style="background: #2563eb; width: 100%;">Spectate Live Match</button>
    </div>
  </div>

  <div class="modal-overlay" id="roundIntermissionModal">
    <div class="modal" style="max-width: 500px; text-align: center;">
      <div style="font-size: 2.6rem; margin-bottom: 6px;">⏳🏆</div>
      <div class="modal-title" style="color: #38bdf8; font-size: 1.45rem;" id="intermissionTitle">Round 1 Over! Next round in 5s...</div>
      <div id="intermissionWinnerInfo" style="color: #fef08a; font-weight: 700; margin-bottom: 12px;"></div>
      <div id="intermissionScoresList" style="display: flex; flex-direction: column; gap: 8px; margin: 14px 0;"></div>
      <button onclick="closeIntermissionModal()" style="background: #334155; width: 100%; font-size: 0.95rem; padding: 8px;">Dismiss</button>
    </div>
  </div>

  <div class="modal-overlay" id="podiumModal">
    <div class="modal" style="max-width: 520px; text-align: center;">
      <div style="font-size: 3rem; margin-bottom: 8px;">🥇🥈🥉</div>
      <div class="modal-title" style="color: #facc15; font-size: 1.6rem;">Match Standings</div>
      <div id="podiumLeaderboard" style="display: flex; flex-direction: column; gap: 8px; margin: 16px 0;"></div>
      <button onclick="closePodiumModal()" style="background: #16a34a; width: 100%; font-size: 1rem; padding: 10px;">Return to Lobby</button>
    </div>
  </div>

  <div id="toastContainer" class="toast-container"></div>
""";
    }

    private static String getHtmlScript() {
        return """
  <script>
    let ws = null;
    let myUsername = '';
    let currentRoomId = '';
    let activeAccessCode = '';
    let currentRoomOwner = '';
    let lastPlayerNames = [];
    let lastCurCount = 0;
    let lastMaxCount = 0;
    let pendingWildCard = null;
    let pendingWildCards = null;
    let selectedIndices = new Set();
    let currentTurnPlayer = '';
    let currentTopCard = null;
    let currentActiveColor = 'RED';
    let gameStarted = false;
    let isPlayerFinished = false;
    let hasDrawnThisTurn = false;
    let currentHandTokens = [];
    let currentRoundNumber = 0;
    let intermissionInterval = null;
    let turnTimerInterval = null;
    let turnTimeoutMs = 0;
    let turnDeadlineMs = 0;
    let defenseStateActive = false;
    let currentDefenseType = null;
    let currentDefensePenalty = 0;
    let currentDefenseTarget = null;
    const opponentCounts = new Map();
    const disconnectedOpponents = new Set();
    const playerGraceSeconds = new Map();

    const joinBtn = document.getElementById('joinBtn');
    const roomCodeInput = document.getElementById('roomCodeInput');
    const roomChip = document.getElementById('roomChip');
    const roomChipText = document.getElementById('roomChipText');
    const copyCodeBtn = document.getElementById('copyCodeBtn');
    const openCreateBtn = document.getElementById('openCreateBtn');
    const openBrowserBtn = document.getElementById('openBrowserBtn');
    const confirmCreateRoomBtn = document.getElementById('confirmCreateRoomBtn');
    const refreshRoomsBtn = document.getElementById('refreshRoomsBtn');
    const addBotBtn = document.getElementById('addBotBtn');
    const startBtn = document.getElementById('startBtn');
    const usernameInput = document.getElementById('usernameInput');
    const connStatus = document.getElementById('connStatus');
    const logContainer = document.getElementById('logContainer');
    const chatInput = document.getElementById('chatInput');
    const sendChatBtn = document.getElementById('sendChatBtn');
    const handCards = document.getElementById('handCards');
    const handCount = document.getElementById('handCount');
    const opponentsBar = document.getElementById('opponentsBar');
    const turnBanner = document.getElementById('turnBanner');
    const turnTimerContainer = document.getElementById('turnTimerContainer');
    const turnTimerBar = document.getElementById('turnTimerBar');
    const turnTimerText = document.getElementById('turnTimerText');
    const actionHudBanner = document.getElementById('actionHudBanner');
    const discardCardContainer = document.getElementById('discardCardContainer');
    const playBtn = document.getElementById('playBtn');
    const drawBtn = document.getElementById('drawBtn');
    const passBtn = document.getElementById('passBtn');
    const shoutUltimoBtn = document.getElementById('shoutUltimoBtn');
    const deckPile = document.getElementById('deckPile');
    const roomInfo = document.getElementById('roomInfo');
    const colorModal = document.getElementById('colorModal');
    const createRoomModal = document.getElementById('createRoomModal');
    const roomBrowserModal = document.getElementById('roomBrowserModal');
    const spectatorModal = document.getElementById('spectatorModal');
    const spectatorTitle = document.getElementById('spectatorTitle');
    const roundIntermissionModal = document.getElementById('roundIntermissionModal');
    const intermissionTitle = document.getElementById('intermissionTitle');
    const intermissionWinnerInfo = document.getElementById('intermissionWinnerInfo');
    const intermissionScoresList = document.getElementById('intermissionScoresList');
    const podiumModal = document.getElementById('podiumModal');
    const podiumLeaderboard = document.getElementById('podiumLeaderboard');
    const toastContainer = document.getElementById('toastContainer');

    function connect() {
      const loc = window.location;
      const wsProtocol = loc.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${wsProtocol}//${loc.host}/ws`;

      connStatus.textContent = 'Connecting...';
      connStatus.style.color = '#facc15';

      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        connStatus.textContent = 'Connected';
        connStatus.style.color = '#22c55e';
        logMessage('Connected to Ultimo WebSocket server', 'info');
      };

      ws.onmessage = (event) => {
        const line = event.data.trim();
        if (line) handleServerMessage(line);
      };

      ws.onclose = () => {
        connStatus.textContent = 'Disconnected';
        connStatus.style.color = '#ef4444';
        logMessage('Disconnected from server', 'error');
      };

      ws.onerror = (err) => {
        connStatus.textContent = 'Error';
        connStatus.style.color = '#ef4444';
      };
    }

    function sendCommand(cmd) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(cmd);
      } else {
        logMessage('Socket not connected', 'error');
      }
    }

    function showToast(msg, type = 'info') {
      const toast = document.createElement('div');
      toast.className = `toast ${type}`;
      toast.textContent = msg;
      toastContainer.appendChild(toast);
      setTimeout(() => {
        toast.style.transition = 'opacity 0.4s ease, transform 0.4s ease';
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(10px)';
        setTimeout(() => toast.remove(), 400);
      }, 4500);
    }

    function shoutUltimo() {
      sendCommand('ULTIMO');
      showToast('🔔 You shouted ULTIMO!', 'info');
      logMessage('You shouted ULTIMO!', 'info');
    }

    function catchUltimo(target) {
      if (!target) return;
      sendCommand(`CATCH_ULTIMO ${target}`);
      logMessage(`Attempted to catch ${target} without ULTIMO!`, 'info');
    }

    function addAiBot() {
      sendCommand('ADD_BOT');
      logMessage('Requested to add an AI Bot to the room', 'info');
    }

    function startTurnCountdown(timeoutMs) {
      stopTurnCountdown();
      if (!timeoutMs || timeoutMs <= 0) return;
      turnTimeoutMs = timeoutMs;
      turnDeadlineMs = Date.now() + timeoutMs;
      if (turnTimerContainer) turnTimerContainer.style.display = 'block';
      if (turnTimerBar) turnTimerBar.style.width = '100%';
      updateTurnCountdownTick();
      turnTimerInterval = setInterval(updateTurnCountdownTick, 100);
    }

    function updateTurnCountdownTick() {
      const remainingMs = Math.max(0, turnDeadlineMs - Date.now());
      const pct = turnTimeoutMs > 0 ? Math.max(0, Math.min(100, (remainingMs / turnTimeoutMs) * 100)) : 0;
      if (turnTimerBar) {
        turnTimerBar.style.width = `${pct.toFixed(1)}%`;
      }
      if (turnTimerText) {
        turnTimerText.textContent = `${Math.ceil(remainingMs / 1000)}s`;
      }
      if (remainingMs <= 0) {
        stopTurnCountdown();
      }
    }

    function stopTurnCountdown() {
      if (turnTimerInterval) {
        clearInterval(turnTimerInterval);
        turnTimerInterval = null;
      }
      if (turnTimerContainer) {
        turnTimerContainer.style.display = 'none';
      }
    }

    function closeIntermissionModal() {
      if (intermissionInterval) {
        clearInterval(intermissionInterval);
        intermissionInterval = null;
      }
      if (roundIntermissionModal) {
        roundIntermissionModal.style.display = 'none';
      }
    }

    function showRoundIntermissionModal(roundNum, winner, pts, scoresStr) {
      closeIntermissionModal();
      let secLeft = 5;
      if (intermissionTitle) {
        intermissionTitle.textContent = `Round ${roundNum} Over! Next round in ${secLeft}s...`;
      }
      if (intermissionWinnerInfo) {
        intermissionWinnerInfo.textContent = `Round Winner: ${winner} (+${pts} pts)`;
      }
      if (intermissionScoresList) {
        intermissionScoresList.innerHTML = '';
        if (scoresStr && scoresStr !== '-') {
          const scorePairs = scoresStr.split(',').map(s => {
            const [name, score] = s.split(':');
            return { name, score: parseInt(score, 10) || 0 };
          }).filter(p => p.name);
          scorePairs.sort((a, b) => b.score - a.score);
          scorePairs.forEach((p, idx) => {
            const row = document.createElement('div');
            const rankClass = idx === 0 ? 'rank-1' : (idx === 1 ? 'rank-2' : (idx === 2 ? 'rank-3' : ''));
            row.className = `podium-row ${rankClass}`;
            row.innerHTML = `<span>#${idx + 1} ${p.name}</span><span>${p.score} pts</span>`;
            intermissionScoresList.appendChild(row);
          });
        }
      }
      if (roundIntermissionModal) {
        roundIntermissionModal.style.display = 'flex';
      }
      intermissionInterval = setInterval(() => {
        secLeft--;
        if (secLeft <= 0) {
          closeIntermissionModal();
        } else if (intermissionTitle) {
          intermissionTitle.textContent = `Round ${roundNum} Over! Next round in ${secLeft}s...`;
        }
      }, 1000);
    }

    function copyActiveRoomCode() {
      if (!activeAccessCode) return;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(activeAccessCode).then(() => {
          copyCodeBtn.textContent = '✓ Copied!';
          setTimeout(() => { copyCodeBtn.textContent = '📋 Copy Code'; }, 2000);
        }).catch(() => {
          prompt('Copy room code:', activeAccessCode);
        });
      } else {
        prompt('Copy room code:', activeAccessCode);
      }
    }

    function closeSpectatorModal() {
      spectatorModal.style.display = 'none';
    }

    function closePodiumModal() {
      podiumModal.style.display = 'none';
    }

    joinBtn.addEventListener('click', () => {
      const name = usernameInput.value.trim();
      const code = roomCodeInput ? roomCodeInput.value.trim() : '';
      if (!name) return;
      myUsername = name;
      if (code) activeAccessCode = code;
      const cmd = code ? `JOIN ${name} ${code}` : `JOIN ${name}`;
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        connect();
        setTimeout(() => sendCommand(cmd), 400);
      } else {
        sendCommand(cmd);
      }
      joinBtn.disabled = true;
      usernameInput.disabled = true;
      if (roomCodeInput) roomCodeInput.disabled = true;
    });

    openCreateBtn.addEventListener('click', () => {
      createRoomModal.style.display = 'flex';
    });

    function closeCreateRoomModal() {
      createRoomModal.style.display = 'none';
    }

    function toggleAccessCodeField() {
      const vis = document.getElementById('createRoomVisibility').value;
      const group = document.getElementById('accessCodeGroup');
      group.style.display = (vis === 'PRIVATE') ? 'block' : 'none';
    }

    confirmCreateRoomBtn.addEventListener('click', () => {
      const name = document.getElementById('createRoomName').value.trim() || 'My Room';
      const vis = document.getElementById('createRoomVisibility').value;
      const code = document.getElementById('createRoomCode').value.trim();
      const max = document.getElementById('createRoomMaxPlayers').value;
      if (vis === 'PRIVATE' && !code) {
        alert('Please enter an access code for private room');
        return;
      }
      if (vis === 'PRIVATE') {
        activeAccessCode = code;
      } else {
        activeAccessCode = '';
      }
      const myName = usernameInput.value.trim() || 'Player1';
      myUsername = myName;

      const createCmd = (vis === 'PRIVATE' && code)
          ? `CREATE_ROOM ${name} ${vis} ${code} ${max}`
          : `CREATE_ROOM ${name} ${vis} ${max}`;

      if (!ws || ws.readyState !== WebSocket.OPEN) {
        connect();
        setTimeout(() => {
          sendCommand(`JOIN ${myName}`);
          setTimeout(() => sendCommand(createCmd), 300);
        }, 400);
      } else {
        sendCommand(`JOIN ${myName}`);
        setTimeout(() => sendCommand(createCmd), 300);
      }
      closeCreateRoomModal();
      joinBtn.disabled = true;
      usernameInput.disabled = true;
      if (roomCodeInput) roomCodeInput.disabled = true;
    });

    openBrowserBtn.addEventListener('click', () => {
      roomBrowserModal.style.display = 'flex';
      sendCommand('LIST_ROOMS');
    });

    function closeRoomBrowserModal() {
      roomBrowserModal.style.display = 'none';
    }

    refreshRoomsBtn.addEventListener('click', () => {
      sendCommand('LIST_ROOMS');
    });

    function renderRoomList(data) {
      const body = document.getElementById('roomListBody');
      body.innerHTML = '';
      if (!data || data === '-') {
        body.innerHTML = '<tr><td colspan="4" style="text-align: center; color: #94a3b8;">No public rooms open. Create one!</td></tr>';
        return;
      }

      const rooms = data.split(' ');
      rooms.forEach(r => {
        if (!r.trim()) return;
        const parts = r.split(':');
        if (parts.length >= 4) {
          const roomId = parts[0];
          const name = parts[1].replace(/_/g, ' ');
          const cap = parts[2];
          const status = parts[3];

          const tr = document.createElement('tr');
          tr.innerHTML = `
            <td><strong>${name}</strong><br><span style="font-size: 0.75rem; color: #94a3b8;">${roomId}</span></td>
            <td>${cap}</td>
            <td><span class="badge" style="background: ${status === 'WAITING' ? '#16a34a' : '#d97706'}; font-size: 0.7rem;">${status}</span></td>
            <td><button style="padding: 4px 10px; font-size: 0.8rem; background: #2563eb;" onclick="joinSpecificRoom('${roomId}')">Join</button></td>
          `;
          body.appendChild(tr);
        }
      });
    }

    function joinSpecificRoom(roomId) {
      closeRoomBrowserModal();
      const myName = usernameInput.value.trim() || 'Player1';
      myUsername = myName;
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        connect();
        setTimeout(() => sendCommand(`JOIN ${myName} ${roomId}`), 400);
      } else {
        sendCommand(`JOIN ${myName} ${roomId}`);
      }
      joinBtn.disabled = true;
      usernameInput.disabled = true;
      if (roomCodeInput) roomCodeInput.disabled = true;
    }

    function kickPlayer(target) {
      if (!target || !confirm(`Kick player ${target}?`)) return;
      sendCommand(`KICK ${target}`);
      logMessage(`Kicked player: ${target}`, 'info');
    }

    function transferOwnership(target) {
      if (!target || !confirm(`Make ${target} the new Host?`)) return;
      sendCommand(`TRANSFER_OWNERSHIP ${target}`);
      logMessage(`Transferred host to: ${target}`, 'info');
    }

    startBtn.addEventListener('click', () => {
      sendCommand('START');
      startBtn.disabled = true;
    });

    drawBtn.addEventListener('click', () => {
      sendCommand('DRAW');
    });

    passBtn.addEventListener('click', () => {
      sendCommand('PASS');
    });

    deckPile.addEventListener('click', () => {
      if (!drawBtn.disabled) sendCommand('DRAW');
    });

    playBtn.addEventListener('click', () => {
      playSelectedCards();
    });

    sendChatBtn.addEventListener('click', sendChat);
    chatInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') sendChat();
    });

    function sendChat() {
      const msg = chatInput.value.trim();
      if (!msg) return;
      sendCommand(`CHAT ${msg}`);
      chatInput.value = '';
    }

    function logMessage(text, type = 'info') {
      const div = document.createElement('div');
      div.className = `log-item ${type}`;
      div.textContent = text;
      logContainer.appendChild(div);
      logContainer.scrollTop = logContainer.scrollHeight;
    }

    function renderLobby(playerNames, curCount, maxCount) {
      if (gameStarted) return;
      const isOwner = (currentRoomOwner === myUsername);
      if (addBotBtn) {
        addBotBtn.style.display = (isOwner && curCount < maxCount) ? 'inline-block' : 'none';
        addBotBtn.disabled = (curCount >= maxCount);
      }
      turnBanner.className = 'turn-banner';
      if (curCount < 2) {
        turnBanner.textContent = `Lobby (${curCount}/${maxCount}): Waiting for players...`;
        startBtn.style.display = 'none';
      } else {
        if (isOwner) {
          turnBanner.textContent = `👑 You are Host (${curCount}/${maxCount} players ready)`;
          startBtn.style.display = 'inline-block';
          startBtn.disabled = false;
        } else {
          turnBanner.textContent = `Waiting for Host (${currentRoomOwner || 'host'}) to start match...`;
          startBtn.style.display = 'none';
        }
      }

      opponentsBar.innerHTML = '';
      playerNames.forEach(pName => {
        const isMe = pName === myUsername;
        const isHost = pName === currentRoomOwner;
        const div = document.createElement('div');
        div.className = 'opponent-card';
        let hostHtml = isHost ? '<span class="host-badge">👑 Host</span>' : '';
        let actionsHtml = '';
        if (isOwner && !isMe) {
          actionsHtml = `<button class="btn-kick" onclick="kickPlayer('${pName}')" title="Kick Player">❌</button>`
                      + `<button class="btn-promote" onclick="transferOwnership('${pName}')" title="Make Host">👑</button>`;
        }
        div.innerHTML = `<span class="opponent-name">${pName}</span>${hostHtml}<span class="opponent-badge">${isMe ? '(You)' : 'Ready'}</span>${actionsHtml}`;
        opponentsBar.appendChild(div);
      });
    }

    function handleServerMessage(raw) {
      const parts = raw.split(' ');
      const type = parts[0];

      switch(type) {
        case 'PING':
          sendCommand('PONG');
          break;

        case 'WELCOME':
          logMessage(`Server: ${parts.slice(2).join(' ')}`, 'info');
          usernameInput.style.borderColor = '#334155';
          break;

        case 'ROOM_OWNER_CHANGED': {
          currentRoomOwner = parts[1];
          logMessage(`Room Host: ${currentRoomOwner}`, 'info');
          if (!gameStarted && lastPlayerNames.length > 0) {
            renderLobby(lastPlayerNames, lastCurCount, lastMaxCount);
          }
          break;
        }

        case 'ROOM_LIST': {
          renderRoomList(parts.slice(1).join(' '));
          break;
        }

        case 'ROOM_STATUS': {
          const roomId = parts[1];
          const curCount = parseInt(parts[2], 10);
          const maxCount = parseInt(parts[3], 10);
          const rawPlayers = parts[4] || '';
          const playerNames = (rawPlayers && rawPlayers !== '-') ? rawPlayers.split(',') : [];

          if (!currentRoomOwner && playerNames.length > 0) {
            currentRoomOwner = playerNames[0];
          }
          lastPlayerNames = playerNames;
          lastCurCount = curCount;
          lastMaxCount = maxCount;

          if (roomId && roomId !== '-' && roomId !== 'null') {
            currentRoomId = roomId;
            roomInfo.style.display = 'inline';
            let label = `Room: ${roomId} (${curCount}/${maxCount})`;
            if (activeAccessCode) {
              label = `Room: ${roomId} [CODE: ${activeAccessCode}]`;
              copyCodeBtn.style.display = 'inline-block';
            } else {
              copyCodeBtn.style.display = 'none';
            }
            roomInfo.textContent = label;
            roomChipText.textContent = label;
            roomChip.style.display = 'flex';
          }
          logMessage(`Room ${roomId}: ${playerNames.join(', ')} joined`, 'info');

          renderLobby(playerNames, curCount, maxCount);
          break;
        }

        case 'HAND_UPDATE': {
          const cardTokens = parts.slice(1).filter(t => t.trim().length > 0);
          selectedIndices.clear();
          if (cardTokens.length > 0 && isPlayerFinished) {
            isPlayerFinished = false;
            closeSpectatorModal();
          }
          renderHand(cardTokens);

          if (gameStarted && cardTokens.length === 0 && !isPlayerFinished) {
            isPlayerFinished = true;
            spectatorTitle.textContent = '🏆 1st PLACE! You finished!';
            spectatorModal.style.display = 'flex';
            drawBtn.disabled = true;
            passBtn.disabled = true;
            if (playBtn) playBtn.style.display = 'none';
            if (shoutUltimoBtn) shoutUltimoBtn.style.display = 'none';
            logMessage('🏆 You shed your last card! You are now spectating.', 'win');
          }
          break;
        }

        case 'TOP_CARD': {
          gameStarted = true;
          startBtn.style.display = 'none';
          if (addBotBtn) addBotBtn.style.display = 'none';
          closeIntermissionModal();
          const newTopCard = parts[1];
          if (newTopCard !== currentTopCard) {
            currentTopCard = newTopCard;
            hasDrawnThisTurn = false;
          }
          currentActiveColor = parts[2] || 'RED';
          discardCardContainer.innerHTML = '';
          discardCardContainer.appendChild(createCardElement(currentTopCard, currentActiveColor));

          if (!defenseStateActive) {
            actionHudBanner.className = 'action-hud-banner';
            actionHudBanner.style.display = 'none';
          }

          updateTurnUI();
          break;
        }

        case 'TURN_ANNOUNCED': {
          gameStarted = true;
          startBtn.style.display = 'none';
          if (addBotBtn) addBotBtn.style.display = 'none';
          const newTurnPlayer = parts[1];
          const timeoutMs = parts[2] ? parseInt(parts[2], 10) : 0;
          if (newTurnPlayer !== currentTurnPlayer) {
            currentTurnPlayer = newTurnPlayer;
            hasDrawnThisTurn = false;
            selectedIndices.clear();
          }
          if (timeoutMs > 0) {
            startTurnCountdown(timeoutMs);
          } else {
            stopTurnCountdown();
          }
          updateTurnUI();
          renderOpponents();
          break;
        }

        case 'DEFENSE_STATE': {
          const defType = (parts[1] || 'NONE').toUpperCase();
          const penaltyCount = parseInt(parts[2], 10) || 0;
          const targetPlayer = parts[3] || '';
          if ((defType === 'DRAW_TWO' || defType === 'WILD_DRAW_FOUR') && penaltyCount > 0) {
            defenseStateActive = true;
            currentDefenseType = defType;
            currentDefensePenalty = penaltyCount;
            currentDefenseTarget = targetPlayer;
            actionHudBanner.className = 'action-hud-banner stack';
            if (targetPlayer === myUsername) {
              actionHudBanner.textContent = `⚠️ PENALTY STACK: +${penaltyCount} CARDS! Play a Draw card to defend or click Draw/Pass to accept penalty.`;
            } else {
              actionHudBanner.textContent = `⚠️ PENALTY STACK: +${penaltyCount} CARDS on ${targetPlayer}! Defending...`;
            }
            actionHudBanner.style.display = 'block';
          } else if (defType === 'SKIP') {
            defenseStateActive = true;
            currentDefenseType = 'SKIP';
            currentDefensePenalty = 1;
            currentDefenseTarget = targetPlayer;
            actionHudBanner.className = 'action-hud-banner deflect';
            if (targetPlayer === myUsername) {
              actionHudBanner.textContent = `🛡️ SKIP DEFLECTED! Incoming on you: Play SKIP to deflect or click Pass/Draw to accept.`;
            } else {
              actionHudBanner.textContent = `🛡️ SKIP DEFLECTED! Shifted to ${targetPlayer}! Defending...`;
            }
            actionHudBanner.style.display = 'block';
          } else {
            defenseStateActive = false;
            currentDefenseType = null;
            currentDefensePenalty = 0;
            currentDefenseTarget = null;
            actionHudBanner.className = 'action-hud-banner';
            actionHudBanner.style.display = 'none';
          }
          updateTurnUI();
          break;
        }

        case 'PLAYER_STATE': {
          const pName = parts[1];
          const pStatus = (parts[2] || '').toUpperCase();
          const graceSec = parseInt(parts[3], 10) || 120;
          if (pStatus === 'DISCONNECTED') {
            disconnectedOpponents.add(pName);
            playerGraceSeconds.set(pName, graceSec);
            renderOpponents();
            showToast(`⚠️ ${pName} disconnected (${graceSec}s grace period).`, 'disconnected');
          } else if (pStatus === 'RECONNECTED' || pStatus === 'CONNECTED') {
            disconnectedOpponents.delete(pName);
            playerGraceSeconds.delete(pName);
            renderOpponents();
            showToast(`✅ ${pName} reconnected!`, 'reconnect');
          } else if (pStatus === 'REMOVED' || pStatus === 'EXPIRED' || pStatus === 'FORFEITED') {
            disconnectedOpponents.delete(pName);
            playerGraceSeconds.delete(pName);
            opponentCounts.delete(pName);
            renderOpponents();
            showToast(`❌ ${pName} left the match (${pStatus}).`, 'disconnected');
          }
          break;
        }

        case 'OPPONENT_UPDATE': {
          const oppName = parts[1];
          const oppCount = parseInt(parts[2], 10);
          opponentCounts.set(oppName, oppCount);
          renderOpponents();
          break;
        }

        case 'DRAW_RESULT': {
          hasDrawnThisTurn = true;
          if (parts[1]) {
            logMessage(`You drew a card: ${parts[1]}`, 'info');
          }
          updateTurnUI();
          break;
        }

        case 'CHAT_BROADCAST': {
          const sender = parts[1];
          const msg = parts.slice(2).join(' ');
          logMessage(`[${sender}]: ${msg}`, sender === 'GAME' ? 'win' : 'chat');

          if (sender === 'GAME') {
            if (msg.includes('Room Access Code:')) {
              activeAccessCode = msg.substring(msg.indexOf('Room Access Code:') + 17).trim();
              if (currentRoomId && currentRoomId !== '-') {
                const label = `Room: ${currentRoomId} [CODE: ${activeAccessCode}]`;
                roomInfo.textContent = label;
                roomChipText.textContent = label;
                copyCodeBtn.style.display = 'inline-block';
              }
            }
            if (msg.includes('no playable moves') || msg.includes('auto-passed') || msg.includes('Turn passed')) {
              showToast(`ℹ️ ${msg}`, 'autopass');
              logMessage(`ℹ️ ${msg}`, 'autopass');
            }
          }
          break;
        }

        case 'GAME_OVER': {
          const winner = parts[1];
          defenseStateActive = false;
          currentDefenseType = null;
          currentDefensePenalty = 0;
          currentDefenseTarget = null;
          if (actionHudBanner) {
            actionHudBanner.className = 'action-hud-banner';
            actionHudBanner.style.display = 'none';
          }
          stopTurnCountdown();
          closeIntermissionModal();
          logMessage(`★ GAME OVER! Winner: ${winner} ★`, 'win');
          turnBanner.className = 'turn-banner my-turn';
          turnBanner.textContent = `★ GAME OVER! Winner: ${winner} ★`;
          drawBtn.disabled = true;
          passBtn.disabled = true;
          if (shoutUltimoBtn) shoutUltimoBtn.style.display = 'none';
          if (playBtn) playBtn.style.display = 'none';
          renderPodiumUI(winner, null);
          break;
        }

        case 'ROUND_OVER': {
          const winner = parts[1];
          const pts = parts[2];
          const status = parts[3];
          const scoresToken = parts[4] || '';
          currentRoundNumber++;
          defenseStateActive = false;
          currentDefenseType = null;
          currentDefensePenalty = 0;
          currentDefenseTarget = null;
          if (actionHudBanner) {
            actionHudBanner.className = 'action-hud-banner';
            actionHudBanner.style.display = 'none';
          }
          stopTurnCountdown();
          logMessage(`Round ${currentRoundNumber} Over! Winner: ${winner} (+${pts} pts)`, 'win');
          if (status === 'WON') {
            closeIntermissionModal();
            renderPodiumUI(winner, scoresToken);
          } else {
            showRoundIntermissionModal(currentRoundNumber, winner, pts, scoresToken);
          }
          break;
        }

        case 'ERROR': {
          const errMsg = parts.slice(1).join(' ');
          logMessage(`Error: ${errMsg}`, 'error');
          if (errMsg.includes('kicked')) {
            alert('You have been kicked from the room by the host.');
            gameStarted = false;
            defenseStateActive = false;
            currentDefenseType = null;
            currentDefensePenalty = 0;
            currentDefenseTarget = null;
            if (actionHudBanner) {
              actionHudBanner.className = 'action-hud-banner';
              actionHudBanner.style.display = 'none';
            }
            stopTurnCountdown();
            startBtn.style.display = 'none';
            if (addBotBtn) addBotBtn.style.display = 'none';
            opponentsBar.innerHTML = '';
            handCards.innerHTML = '';
            handCount.textContent = '0';
            roomInfo.style.display = 'none';
            roomChip.style.display = 'none';
            turnBanner.className = 'turn-banner';
            turnBanner.textContent = 'Kicked from room. Join another room or quick play!';
            joinBtn.disabled = false;
            usernameInput.disabled = false;
            if (roomCodeInput) roomCodeInput.disabled = false;
          } else if (errMsg.includes('NAME_TAKEN') || errMsg.includes('INVALID_USERNAME') || !gameStarted) {
            joinBtn.disabled = false;
            usernameInput.disabled = false;
            if (roomCodeInput) roomCodeInput.disabled = false;
            usernameInput.style.borderColor = '#ef4444';
            usernameInput.focus();
            myUsername = '';
          }
          break;
        }

        case 'SERVER_SHUTDOWN': {
          const drainSec = parts[1] || '5';
          stopTurnCountdown();
          logMessage(`⚠️ SERVER SHUTDOWN: Server is shutting down in ${drainSec}s (graceful drain)!`, 'error');
          turnBanner.className = 'turn-banner';
          turnBanner.style.borderColor = '#ef4444';
          turnBanner.style.color = '#ef4444';
          turnBanner.textContent = `⚠️ Server shutting down in ${drainSec}s...`;
          drawBtn.disabled = true;
          passBtn.disabled = true;
          startBtn.style.display = 'none';
          if (addBotBtn) addBotBtn.style.display = 'none';
          break;
        }
      }
    }

    function renderPodiumUI(winner, scoresStr) {
      podiumLeaderboard.innerHTML = '';
      const winnerRow = document.createElement('div');
      winnerRow.className = 'podium-row rank-1';
      winnerRow.innerHTML = `<span>🥇 1st PLACE</span><span>${winner} (Winner)</span>`;
      podiumLeaderboard.appendChild(winnerRow);

      if (scoresStr && scoresStr !== '-') {
        const scorePairs = scoresStr.split(',').map(s => {
          const [name, score] = s.split(':');
          return { name, score: parseInt(score, 10) || 0 };
        }).filter(p => p.name && p.name !== winner);

        scorePairs.sort((a, b) => b.score - a.score);
        scorePairs.forEach((p, idx) => {
          const row = document.createElement('div');
          const rank = idx + 2;
          const isLast = (idx === scorePairs.length - 1);
          let medal = isLast ? '💀 Last' : (rank === 2 ? '🥈 2nd' : (rank === 3 ? '🥉 3rd' : `${rank}th`));
          let rankClass = isLast ? 'rank-last' : (rank === 2 ? 'rank-2' : (rank === 3 ? 'rank-3' : ''));
          row.className = `podium-row ${rankClass}`;
          row.innerHTML = `<span>${medal}</span><span>${p.name} (${p.score} pts)</span>`;
          podiumLeaderboard.appendChild(row);
        });
      } else {
        let rank = 2;
        opponentCounts.forEach((cnt, opp) => {
          if (opp !== winner) {
            const row = document.createElement('div');
            const medal = (rank === 2) ? '🥈 2nd' : (rank === 3 ? '🥉 3rd' : `${rank}th`);
            const rankClass = (rank === 2) ? 'rank-2' : (rank === 3 ? 'rank-3' : 'rank-last');
            row.className = `podium-row ${rankClass}`;
            row.innerHTML = `<span>${medal}</span><span>${opp} (${cnt} cards)</span>`;
            podiumLeaderboard.appendChild(row);
            rank++;
          }
        });
      }
      podiumModal.style.display = 'flex';
    }

    function isPlayableCard(token) {
      if (!gameStarted || !myUsername || currentTurnPlayer !== myUsername || !token || isPlayerFinished) return false;

      if (defenseStateActive && currentDefenseTarget === myUsername) {
        const val = getCardValue(token);
        if (currentDefenseType === 'SKIP') {
          return val === 'SKIP';
        }
        if (currentDefenseType === 'DRAW_TWO') {
          return val === 'DRAW_TWO' || val === 'WILD_DRAW_FOUR';
        }
        if (currentDefenseType === 'WILD_DRAW_FOUR' || currentDefenseType === 'DRAW_FOUR') {
          return val === 'WILD_DRAW_FOUR';
        }
        return false;
      }

      if (token.startsWith('WILD')) return true;

      const sep = token.indexOf('_');
      const colorPart = sep > 0 ? token.substring(0, sep) : token;
      const valPart = sep > 0 ? token.substring(sep + 1) : '';

      if (colorPart === currentActiveColor) return true;

      if (currentTopCard) {
        const topSep = currentTopCard.indexOf('_');
        const topVal = topSep > 0 ? currentTopCard.substring(topSep + 1) : '';
        if (valPart && topVal && valPart === topVal) return true;
      }
      return false;
    }

    function updateTurnUI() {
      if (!gameStarted) return;

      if (isPlayerFinished) {
        turnBanner.className = 'turn-banner';
        turnBanner.textContent = `Spectating match... Current turn: ${currentTurnPlayer}`;
        drawBtn.disabled = true;
        passBtn.disabled = true;
        if (playBtn) playBtn.style.display = 'none';
        if (shoutUltimoBtn) shoutUltimoBtn.style.display = 'none';
        return;
      }

      if (shoutUltimoBtn) {
        if (currentHandTokens.length <= 2 && currentHandTokens.length > 0) {
          shoutUltimoBtn.style.display = 'inline-block';
        } else {
          shoutUltimoBtn.style.display = 'none';
        }
      }

      if (myUsername && currentTurnPlayer === myUsername) {
        turnBanner.className = 'turn-banner my-turn';
        if (defenseStateActive && currentDefenseTarget === myUsername) {
          if (currentDefenseType === 'SKIP') {
            turnBanner.textContent = `🛡️ DEFEND SKIP (Play SKIP or Pass/Draw to accept)`;
          } else {
            turnBanner.textContent = `⚠️ DEFEND PENALTY (+${currentDefensePenalty} cards or Draw/Pass to accept)`;
          }
          drawBtn.disabled = false;
          passBtn.disabled = false;
        } else {
          turnBanner.textContent = `★ YOUR TURN! (${currentActiveColor} active) ★`;
          drawBtn.disabled = hasDrawnThisTurn;
          passBtn.disabled = !hasDrawnThisTurn;
        }
      } else {
        turnBanner.className = 'turn-banner';
        if (defenseStateActive && currentDefenseTarget && currentDefenseTarget !== '-') {
          if (currentDefenseType === 'SKIP') {
            turnBanner.textContent = `🛡️ Turn: ${currentTurnPlayer} (Defending Skip)`;
          } else {
            turnBanner.textContent = `⚠️ Turn: ${currentTurnPlayer} (Defending +${currentDefensePenalty} Stack)`;
          }
        } else {
          turnBanner.textContent = `Turn: ${currentTurnPlayer} | Active Color: ${currentActiveColor}`;
        }
        drawBtn.disabled = true;
        passBtn.disabled = true;
      }

      // Re-render hand to refresh playable glow highlights
      if (currentHandTokens.length > 0) {
        renderHand(currentHandTokens);
      }
    }

    function renderOpponents() {
      if (!gameStarted) return;
      opponentsBar.innerHTML = '';
      opponentCounts.forEach((count, name) => {
        if (name !== myUsername) {
          const isTurn = name === currentTurnPlayer;
          const isDisc = disconnectedOpponents.has(name);
          const graceSec = playerGraceSeconds.get(name) || 120;
          const div = document.createElement('div');
          div.className = `opponent-card ${isTurn ? 'turn' : ''} ${isDisc ? 'disconnected' : ''}`;
          let catchHtml = '';
          if (count === 1 && !isDisc) {
            catchHtml = `<button class="btn-catch-ultimo" onclick="catchUltimo('${name}')" title="Catch ${name} without saying ULTIMO!">🚨 CATCH ULTIMO!</button>`;
          }
          let discBadgeHtml = isDisc ? `<span class="badge-disconnected" title="Player disconnected (${graceSec}s grace period)">⚠️ [DISCONNECTED - ${graceSec}s grace]</span>` : '';
          div.innerHTML = `<span class="opponent-name">${name}</span>${discBadgeHtml}<span class="opponent-badge">${count} 🂠</span>${catchHtml}`;
          opponentsBar.appendChild(div);
        }
      });
    }

    function getCardValue(token) {
      if (!token) return '';
      if (token.startsWith('WILD')) {
        return token.includes('DRAW') ? 'WILD_DRAW_FOUR' : 'WILD';
      }
      const sep = token.indexOf('_');
      return sep > 0 ? token.substring(sep + 1) : token;
    }

    function isComboPlayable(tokens) {
      if (!gameStarted || !myUsername || currentTurnPlayer !== myUsername || !tokens || tokens.length === 0 || isPlayerFinished) return false;
      const firstVal = getCardValue(tokens[0]);
      const allSameVal = tokens.every(t => getCardValue(t) === firstVal);
      if (!allSameVal) return false;
      if (defenseStateActive && currentDefenseTarget === myUsername) {
        return tokens.every(t => isPlayableCard(t));
      }
      return tokens.some(t => isPlayableCard(t));
    }

    function updatePlayBtnUI() {
      if (!playBtn) return;
      if (selectedIndices.size === 0 || currentTurnPlayer !== myUsername || isPlayerFinished) {
        playBtn.style.display = 'none';
        return;
      }

      playBtn.style.display = 'inline-block';
      const selectedTokens = Array.from(selectedIndices).map(i => currentHandTokens[i]);
      const playable = selectedTokens.length === 1
          ? isPlayableCard(selectedTokens[0])
          : isComboPlayable(selectedTokens);

      if (selectedTokens.length === 1) {
        playBtn.textContent = 'Play Card';
      } else {
        playBtn.textContent = `Play Combo (${selectedTokens.length} cards)`;
      }

      playBtn.disabled = !playable;
      if (playable) {
        playBtn.style.background = '#22c55e';
        playBtn.style.opacity = '1';
        playBtn.style.cursor = 'pointer';
      } else {
        playBtn.style.background = '#64748b';
        playBtn.style.opacity = '0.6';
        playBtn.style.cursor = 'not-allowed';
      }
    }

    function renderHand(cardTokens) {
      currentHandTokens = cardTokens;
      handCards.innerHTML = '';
      handCount.textContent = cardTokens.length;

      if (shoutUltimoBtn) {
        if (gameStarted && !isPlayerFinished && cardTokens.length <= 2 && cardTokens.length > 0) {
          shoutUltimoBtn.style.display = 'inline-block';
        } else {
          shoutUltimoBtn.style.display = 'none';
        }
      }

      cardTokens.forEach((cardToken, index) => {
        const el = createCardElement(cardToken);
        if (isPlayableCard(cardToken)) {
          el.classList.add('playable');
        }
        if (selectedIndices.has(index)) {
          el.classList.add('selected');
        }
        el.onclick = () => onCardClicked(index, cardToken);
        el.ondblclick = () => onCardDoubleClicked(index, cardToken);
        handCards.appendChild(el);
      });
      updatePlayBtnUI();
    }

    function onCardClicked(index, cardToken) {
      if (!gameStarted || isPlayerFinished) return;
      if (currentTurnPlayer !== myUsername) {
        logMessage('Not your turn! Wait for ' + currentTurnPlayer, 'error');
        return;
      }

      if (selectedIndices.has(index)) {
        selectedIndices.delete(index);
      } else {
        if (selectedIndices.size > 0) {
          const firstIdx = selectedIndices.values().next().value;
          const firstVal = getCardValue(currentHandTokens[firstIdx]);
          const thisVal = getCardValue(cardToken);
          if (firstVal !== thisVal) {
            selectedIndices.clear();
          }
        }
        selectedIndices.add(index);
      }

      const children = handCards.children;
      for (let i = 0; i < children.length; i++) {
        if (selectedIndices.has(i)) {
          children[i].classList.add('selected');
        } else {
          children[i].classList.remove('selected');
        }
      }

      updatePlayBtnUI();
    }

    function onCardDoubleClicked(index, cardToken) {
      if (!gameStarted || currentTurnPlayer !== myUsername || isPlayerFinished) return;
      if (!selectedIndices.has(index)) {
        selectedIndices.clear();
        selectedIndices.add(index);
      }
      playSelectedCards();
    }

    function playSelectedCards() {
      if (!gameStarted || currentTurnPlayer !== myUsername || isPlayerFinished) return;
      const selectedTokens = Array.from(selectedIndices).map(i => currentHandTokens[i]);
      if (selectedTokens.length === 0) return;

      const playable = selectedTokens.length === 1
          ? isPlayableCard(selectedTokens[0])
          : isComboPlayable(selectedTokens);

      if (!playable) {
        logMessage('Selected card(s) cannot be legally played on current discard pile', 'error');
        return;
      }

      const hasWild = selectedTokens.some(t => t.startsWith('WILD'));
      if (hasWild) {
        pendingWildCards = selectedTokens;
        colorModal.style.display = 'flex';
        return;
      }

      const cmd = `PLAY ${selectedTokens.join(',')}`;
      hasDrawnThisTurn = false;
      selectedIndices.clear();
      updatePlayBtnUI();
      sendCommand(cmd);
    }

    function selectWildColor(color) {
      if (pendingWildCards && pendingWildCards.length > 0) {
        hasDrawnThisTurn = false;
        selectedIndices.clear();
        updatePlayBtnUI();
        sendCommand(`PLAY ${pendingWildCards.join(',')} ${color}`);
        pendingWildCards = null;
      } else if (pendingWildCard) {
        hasDrawnThisTurn = false;
        selectedIndices.clear();
        updatePlayBtnUI();
        sendCommand(`PLAY ${pendingWildCard} ${color}`);
        pendingWildCard = null;
      }
      closeColorModal();
    }

    function closeColorModal() {
      colorModal.style.display = 'none';
      pendingWildCard = null;
      pendingWildCards = null;
    }

    function createCardElement(token, overrideColor = null) {
      if (!token) return document.createElement('div');

      const sep = token.indexOf('_');
      const colorPart = sep > 0 ? token.substring(0, sep) : token;
      let valPart = sep > 0 ? token.substring(sep + 1) : '';

      if (colorPart === 'WILD') {
        if (valPart.includes('DRAW_FOUR') || valPart.includes('DRAW4')) {
          valPart = 'DRAW_FOUR';
        } else {
          valPart = 'WILD';
        }
      }

      const numberMap = {
        'ZERO': '0', 'ONE': '1', 'TWO': '2', 'THREE': '3', 'FOUR': '4',
        'FIVE': '5', 'SIX': '6', 'SEVEN': '7', 'EIGHT': '8', 'NINE': '9',
        '0': '0', '1': '1', '2': '2', '3': '3', '4': '4',
        '5': '5', '6': '6', '7': '7', '8': '8', '9': '9'
      };

      let displayVal = valPart;
      if (numberMap[valPart] !== undefined) {
        displayVal = numberMap[valPart];
      } else if (valPart === 'DRAW_TWO') {
        displayVal = '+2';
      } else if (valPart === 'DRAW_FOUR' || valPart === 'WILD_DRAW_FOUR') {
        displayVal = '+4';
      } else if (valPart === 'WILD') {
        displayVal = '★';
      } else if (valPart === 'SKIP') {
        displayVal = '⊘';
      } else if (valPart === 'REVERSE') {
        displayVal = '⇄';
      }

      const card = document.createElement('div');
      let cssClass = 'card card-red';
      const c = overrideColor || colorPart;
      if (c === 'GREEN') cssClass = 'card card-green';
      else if (c === 'BLUE') cssClass = 'card card-blue';
      else if (c === 'YELLOW') cssClass = 'card card-yellow';
      else if (colorPart === 'WILD') cssClass = 'card card-wild';

      card.className = cssClass;
      card.innerHTML = `
        <div class="card-top">${displayVal}</div>
        <div class="card-center">${displayVal}</div>
        <div class="card-bottom">${displayVal}</div>
      `;
      return card;
    }

    // Auto-connect on page load
    window.addEventListener('DOMContentLoaded', connect);
  </script>
</body>
</html>
""";
    }
}
