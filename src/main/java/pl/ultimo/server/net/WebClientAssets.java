package pl.ultimo.server.net;

/**
 * Embedded HTML5/CSS/JavaScript single-page application for the Ultimo web client.
 * Self-contained with zero external CDN dependencies.
 */
public final class WebClientAssets {

    private WebClientAssets() {
    }

    public static String getHtmlPage() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
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
    header { background: var(--panel); padding: 12px 24px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; }
    .brand { font-size: 1.5rem; font-weight: 800; letter-spacing: 1px; color: #f59e0b; display: flex; align-items: center; gap: 8px; }
    .badge { font-size: 0.75rem; padding: 3px 8px; border-radius: 9999px; background: #3b82f6; color: white; text-transform: uppercase; font-weight: 700; }
    .lobby-bar { display: flex; gap: 10px; align-items: center; }
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
    .btn-start:hover:not(:disabled) { background: #15803d; }
    main { flex: 1; display: grid; grid-template-columns: 1fr 340px; gap: 16px; padding: 16px; max-width: 1400px; margin: 0 auto; width: 100%; }
    @media (max-width: 900px) { main { grid-template-columns: 1fr; } }
    .table-container { background: radial-gradient(circle, var(--felt) 0%, #022c22 100%); border-radius: 16px; border: 3px solid #047857; box-shadow: inset 0 0 40px rgba(0,0,0,0.6); display: flex; flex-direction: column; justify-content: space-between; padding: 20px; min-height: 580px; position: relative; }
    .opponents-bar { display: flex; justify-content: center; gap: 16px; min-height: 80px; flex-wrap: wrap; }
    .opponent-card { background: rgba(15, 23, 42, 0.85); border: 1px solid #334155; border-radius: 10px; padding: 8px 16px; display: flex; flex-direction: column; align-items: center; gap: 4px; }
    .opponent-card.turn { border-color: #facc15; box-shadow: 0 0 15px rgba(250, 204, 21, 0.5); }
    .opponent-name { font-weight: 700; font-size: 0.9rem; }
    .opponent-count { font-size: 0.8rem; color: var(--muted); }
    .center-table { display: flex; align-items: center; justify-content: center; gap: 40px; margin: auto; }
    .pile-box { display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .pile-label { font-size: 0.8rem; font-weight: 700; color: #a7f3d0; text-transform: uppercase; letter-spacing: 0.5px; }
    .deck-pile { width: 90px; height: 130px; border-radius: 10px; background: repeating-linear-gradient(45deg, #1e293b, #1e293b 10px, #0f172a 10px, #0f172a 20px); border: 2px solid #64748b; display: flex; align-items: center; justify-content: center; cursor: pointer; box-shadow: 0 8px 16px rgba(0,0,0,0.4); font-weight: 800; font-size: 1.1rem; color: #94a3b8; transition: transform 0.15s; }
    .deck-pile:hover { transform: scale(1.05); }
    .turn-banner { background: rgba(15, 23, 42, 0.9); border: 2px solid #38bdf8; border-radius: 9999px; padding: 8px 24px; font-weight: 700; text-align: center; margin: 10px auto; width: fit-content; }
    .turn-banner.my-turn { border-color: #22c55e; background: rgba(34, 197, 94, 0.25); animation: pulse 1.5s infinite; }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
    .hand-area { background: rgba(15, 23, 42, 0.6); border-radius: 12px; padding: 14px; }
    .hand-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
    .hand-cards { display: flex; gap: 10px; overflow-x: auto; padding: 10px 4px 16px 4px; justify-content: center; min-height: 155px; }
    .card { width: 90px; height: 130px; border-radius: 10px; border: 3px solid white; display: flex; flex-direction: column; justify-content: space-between; padding: 6px; box-shadow: 0 6px 12px rgba(0,0,0,0.35); cursor: pointer; transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s; user-select: none; flex-shrink: 0; position: relative; }
    .card:hover { transform: translateY(-12px); box-shadow: 0 14px 20px rgba(0,0,0,0.5); z-index: 10; }
    .card.playable { border-color: #fef08a; box-shadow: 0 0 14px rgba(254, 240, 138, 0.8), 0 0 4px white; }
    .card.selected { transform: translateY(-24px) scale(1.05); border-color: #38bdf8; box-shadow: 0 0 22px rgba(56, 189, 248, 0.95), 0 15px 25px rgba(0,0,0,0.6); z-index: 25; }
    .btn-play { background: #22c55e; color: white; padding: 6px 14px; border-radius: 6px; font-weight: 700; cursor: pointer; border: 1px solid #16a34a; transition: all 0.15s; }
    .btn-play:hover { transform: scale(1.05); background: #16a34a; }
    .card-top { font-size: 0.85rem; font-weight: 900; }
    .card-center { font-size: 1.5rem; font-weight: 900; align-self: center; margin: auto; background: white; border-radius: 50%; width: 52px; height: 52px; display: flex; align-items: center; justify-content: center; color: black; box-shadow: inset 0 0 4px rgba(0,0,0,0.4); text-align: center; line-height: 1; }
    .card-bottom { font-size: 0.85rem; font-weight: 900; align-self: flex-end; transform: rotate(180deg); }
    .card-red { background: var(--red); color: white; }
    .card-green { background: var(--green); color: white; }
    .card-blue { background: var(--blue); color: white; }
    .card-yellow { background: var(--yellow); color: black; }
    .card-wild { background: linear-gradient(135deg, var(--red) 25%, var(--blue) 25% 50%, var(--yellow) 50% 75%, var(--green) 75%); color: white; }
    .card-wild .card-center { background: #0f172a; color: white; }
    .sidebar { background: var(--panel); border-radius: 16px; border: 1px solid #334155; display: flex; flex-direction: column; overflow: hidden; height: 100%; min-height: 580px; }
    .sidebar-header { padding: 12px 16px; border-bottom: 1px solid #334155; font-weight: 700; display: flex; justify-content: space-between; }
    .log-container { flex: 1; padding: 12px; overflow-y: auto; display: flex; flex-direction: column; gap: 6px; font-size: 0.85rem; }
    .log-item { padding: 6px 10px; border-radius: 6px; background: #0f172a; border-left: 3px solid #3b82f6; word-break: break-word; }
    .log-item.chat { border-left-color: #a855f7; }
    .log-item.error { border-left-color: #ef4444; color: #fca5a5; }
    .log-item.win { border-left-color: #eab308; background: #422006; font-weight: bold; }
    .chat-box { padding: 10px; border-top: 1px solid #334155; display: flex; gap: 8px; }
    .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.7); display: none; align-items: center; justify-content: center; z-index: 100; }
    .modal { background: var(--panel); border: 1px solid #475569; border-radius: 12px; padding: 24px; max-width: 400px; width: 90%; text-align: center; }
    .modal-title { font-size: 1.25rem; font-weight: 800; margin-bottom: 16px; }
    .color-options { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .btn-color { height: 50px; font-weight: 800; font-size: 1.1rem; border-radius: 8px; }
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
      <button id="joinBtn">Join Match</button>
      <button id="startBtn" class="btn-start" style="display: none;">Start Match</button>
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

      <div class="turn-banner" id="turnBanner">Connect and click 'Join Match' to start</div>

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
          <div style="display: flex; gap: 8px;">
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
        <span id="roomInfo" style="color: var(--muted); font-size: 0.8rem;">Room: -</span>
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

  <script>
    let ws = null;
    let myUsername = '';
    let pendingWildCard = null;
    let pendingWildCards = null;
    let selectedIndices = new Set();
    let currentTurnPlayer = '';
    let currentTopCard = null;
    let currentActiveColor = 'RED';
    let gameStarted = false;
    let hasDrawnThisTurn = false;
    let currentHandTokens = [];
    const opponentCounts = new Map();

    const joinBtn = document.getElementById('joinBtn');
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
    const discardCardContainer = document.getElementById('discardCardContainer');
    const playBtn = document.getElementById('playBtn');
    const drawBtn = document.getElementById('drawBtn');
    const passBtn = document.getElementById('passBtn');
    const deckPile = document.getElementById('deckPile');
    const roomInfo = document.getElementById('roomInfo');
    const colorModal = document.getElementById('colorModal');

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

    joinBtn.addEventListener('click', () => {
      const name = usernameInput.value.trim();
      if (!name) return;
      myUsername = name;
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        connect();
        setTimeout(() => sendCommand(`JOIN ${name}`), 400);
      } else {
        sendCommand(`JOIN ${name}`);
      }
      joinBtn.disabled = true;
      usernameInput.disabled = true;
    });

    startBtn.addEventListener('click', () => {
      sendCommand('START');
      startBtn.disabled = true;
    });

    playBtn.addEventListener('click', () => {
      if (!playBtn.disabled) playSelectedCards();
    });

    drawBtn.addEventListener('click', () => {
      if (!drawBtn.disabled) {
        selectedIndices.clear();
        updatePlayBtnUI();
        sendCommand('DRAW');
      }
    });

    deckPile.addEventListener('click', () => {
      if (!drawBtn.disabled) {
        selectedIndices.clear();
        updatePlayBtnUI();
        sendCommand('DRAW');
      }
    });

    passBtn.addEventListener('click', () => {
      if (!passBtn.disabled) {
        hasDrawnThisTurn = false;
        passBtn.disabled = true;
        selectedIndices.clear();
        updatePlayBtnUI();
        sendCommand('PASS');
      }
    });

    sendChatBtn.addEventListener('click', () => {
      const msg = chatInput.value.trim();
      if (!msg) return;
      sendCommand(`CHAT ${msg}`);
      chatInput.value = '';
    });

    chatInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') sendChatBtn.click();
    });

    function logMessage(text, type = 'info') {
      const div = document.createElement('div');
      div.className = `log-item ${type}`;
      div.textContent = text;
      logContainer.appendChild(div);
      logContainer.scrollTop = logContainer.scrollHeight;
    }

    function handleServerMessage(raw) {
      const parts = raw.split(' ');
      const type = parts[0];

      switch(type) {
        case 'WELCOME':
          logMessage(`Server: ${parts.slice(2).join(' ')}`, 'info');
          break;

        case 'ROOM_STATUS': {
          const roomId = parts[1];
          const curCount = parts[2];
          const maxCount = parts[3];
          const rawPlayers = parts[4] || '';
          const playerNames = (rawPlayers && rawPlayers !== '-') ? rawPlayers.split(',') : [];

          roomInfo.textContent = `Room: ${roomId} (${curCount}/${maxCount})`;
          logMessage(`Matchmaking: ${playerNames.join(', ')} in room`, 'info');

          if (!gameStarted) {
            turnBanner.className = 'turn-banner';
            turnBanner.textContent = `Lobby: ${curCount}/${maxCount} players joined. `
                + (playerNames.length >= 2 ? "Ready to start!" : "Waiting for players...");

            if (playerNames.length >= 2) {
              startBtn.style.display = 'inline-block';
              startBtn.textContent = `Start Match (${curCount}/${maxCount})`;
              startBtn.disabled = false;
            } else {
              startBtn.style.display = 'none';
            }

            opponentsBar.innerHTML = '';
            playerNames.forEach(pName => {
              const div = document.createElement('div');
              div.className = 'opponent-card';
              div.innerHTML = `<div class="opponent-name">${pName}</div><div class="opponent-count">${pName === myUsername ? '(You)' : 'Ready'}</div>`;
              opponentsBar.appendChild(div);
            });
          }
          break;
        }

        case 'HAND_UPDATE': {
          const cardTokens = parts.slice(1).filter(t => t.trim().length > 0);
          selectedIndices.clear();
          renderHand(cardTokens);
          break;
        }

        case 'TOP_CARD': {
          gameStarted = true;
          startBtn.style.display = 'none';
          const newTopCard = parts[1];
          if (newTopCard !== currentTopCard) {
            currentTopCard = newTopCard;
            hasDrawnThisTurn = false;
          }
          currentActiveColor = parts[2] || 'RED';
          discardCardContainer.innerHTML = '';
          discardCardContainer.appendChild(createCardElement(currentTopCard, currentActiveColor));
          updateTurnUI();
          break;
        }

        case 'TURN_ANNOUNCED': {
          gameStarted = true;
          startBtn.style.display = 'none';
          const newTurnPlayer = parts[1];
          if (newTurnPlayer !== currentTurnPlayer) {
            currentTurnPlayer = newTurnPlayer;
            hasDrawnThisTurn = false;
            selectedIndices.clear();
          }
          updateTurnUI();
          renderOpponents();
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
          break;
        }

        case 'GAME_OVER': {
          const winner = parts[1];
          logMessage(`★ GAME OVER! Winner: ${winner} ★`, 'win');
          turnBanner.className = 'turn-banner my-turn';
          turnBanner.textContent = `★ GAME OVER! Winner: ${winner} ★`;
          drawBtn.disabled = true;
          passBtn.disabled = true;
          alert(`Game Over! Winner is ${winner}!`);
          break;
        }

        case 'ERROR': {
          logMessage(`Error: ${parts.slice(1).join(' ')}`, 'error');
          break;
        }
      }
    }

    function isPlayableCard(token) {
      if (!gameStarted || currentTurnPlayer !== myUsername || !token) return false;
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

      if (currentTurnPlayer === myUsername) {
        turnBanner.className = 'turn-banner my-turn';
        turnBanner.textContent = `★ YOUR TURN! (${currentActiveColor} active) ★`;
        drawBtn.disabled = hasDrawnThisTurn;
        passBtn.disabled = !hasDrawnThisTurn;
      } else {
        turnBanner.className = 'turn-banner';
        turnBanner.textContent = `Turn: ${currentTurnPlayer} | Active Color: ${currentActiveColor}`;
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
          const div = document.createElement('div');
          div.className = `opponent-card ${name === currentTurnPlayer ? 'turn' : ''}`;
          div.innerHTML = `<div class="opponent-name">${name}</div><div class="opponent-count">${count} cards</div>`;
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
      if (!gameStarted || currentTurnPlayer !== myUsername || !tokens || tokens.length === 0) return false;
      const firstVal = getCardValue(tokens[0]);
      const allSameVal = tokens.every(t => getCardValue(t) === firstVal);
      if (!allSameVal) return false;
      return tokens.some(t => isPlayableCard(t));
    }

    function updatePlayBtnUI() {
      if (!playBtn) return;
      if (selectedIndices.size === 0 || currentTurnPlayer !== myUsername) {
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
      if (!gameStarted) return;
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
      if (!gameStarted || currentTurnPlayer !== myUsername) return;
      if (!selectedIndices.has(index)) {
        selectedIndices.clear();
        selectedIndices.add(index);
      }
      playSelectedCards();
    }

    function playSelectedCards() {
      if (!gameStarted || currentTurnPlayer !== myUsername) return;
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
        sendCommand(`PLAY ${pendingWildCards.join(',')} ${color}`);
        pendingWildCards = null;
      } else if (pendingWildCard) {
        hasDrawnThisTurn = false;
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
