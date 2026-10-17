# Ultimo: High-Performance Authoritative Multiplayer Game Server

[![Java Version](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Virtual Threads](https://img.shields.io/badge/Concurrency-Virtual%20Threads%20%28Loom%29-blue.svg)](https://openjdk.org/jeps/444)
[![Tests](https://img.shields.io/badge/Tests-81%2F81%20Passing%20%28100%25%29-brightgreen.svg)](https://junit.org/junit5/)
[![WebSockets](https://img.shields.io/badge/WebSockets-RFC%206455%20Pure%20Java-9cf.svg)](#web-client--browser-gameplay)
[![Dependencies](https://img.shields.io/badge/Runtime%20Dependencies-0%20%28Pure%20Java%20StdLib%29-success.svg)](#architectural-highlights)
[![Docker](https://img.shields.io/badge/Docker-Multi--stage%20Alpine-2496ED.svg)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

An authoritative multiplayer card game server (inspired by Uno) built with **Java 21**, **Virtual Threads (Project Loom)**, **RFC 6455 WebSockets**, and **raw TCP sockets**. Built without external networking or web frameworks to maintain zero runtime dependencies, low memory footprint, and complete control over thread lifecycles and protocol framing.

---

## Table of Contents

- [Architectural Highlights](#architectural-highlights)
- [Design Decisions & Architecture Comparison](#design-decisions--architecture-comparison)
- [System Architecture](#system-architecture)
  - [1. Component Diagram](#1-component-diagram)
  - [2. Actor Pattern Concurrency Model](#2-actor-pattern-concurrency-model)
  - [3. Anti-Cheat: Authoritative Fog of War](#3-anti-cheat-authoritative-fog-of-war)
- [Wire Protocol Specification](#wire-protocol-specification)
  - [Client Commands](#client-commands)
  - [Server Events](#server-events)
  - [Card Token Format](#card-token-format)
- [Domain Engine & Game Rules](#domain-engine--game-rules)
- [Web Client & Browser Gameplay](#web-client--browser-gameplay)
- [Terminal Client & Dual-Mode UI](#terminal-client--dual-mode-ui)
- [Cloud Deployment & Hosting](#cloud-deployment--hosting)
- [Project Directory Structure](#project-directory-structure)
- [Quick Start](#quick-start)
  - [Prerequisites](#prerequisites)
  - [Option A: Web Browser (Instant Play)](#option-a-web-browser-instant-play)
  - [Option B: Native Scripts (Zero Build Tools)](#option-b-native-scripts-zero-build-tools)
  - [Option C: Maven](#option-c-maven)
  - [Option D: Docker & Docker Compose](#option-d-docker--docker-compose)
  - [Option E: Raw TCP Terminal (Netcat / Telnet)](#option-e-raw-tcp-terminal-netcat--telnet)
- [Testing & Quality Assurance](#testing--quality-assurance)
- [License](#license)

---

## Architectural Highlights

- **Zero Runtime Frameworks:** Built with the standard Java 21 runtime library (`java.net`, `java.util.concurrent`, `java.io`). No external HTTP, serialization, or networking dependencies.
- **Virtual Threads (Project Loom):** Every incoming TCP client connection is handled by a lightweight Virtual Thread (`Executors.newVirtualThreadPerTaskExecutor()`), achieving high concurrent connection scalability with minimal OS thread overhead.
- **Actor-Pattern Concurrency:** Rather than wrapping mutable game state with coarse synchronized blocks or lock contention, each active room executes a dedicated single-threaded event loop fed by a thread-safe `LinkedBlockingQueue`. This eliminates race conditions and deadlocks by design.
- **Authoritative Server with Fog of War:** Complete protection against client-side tampering or packet inspection. The server computes all moves, deck shuffles, and penalties. Opponents' cards are withheld; clients receive only their own hand and table metadata.
- **Custom Line-Delimited Protocol:** Clean, stateless, human-readable text protocol (`\n`-terminated), easily debuggable via Telnet, Netcat, or custom bots.
- **Unified Transport Multiplexer & RFC 6455 WebSockets:** A single TCP port multiplexes raw TCP socket connections, RFC 6455 WebSockets, and embedded HTML5 web assets with zero external dependencies.
- **Cloud-Native & Cross-Platform:** Complete containerization via multi-stage Alpine Dockerfile, Generational ZGC optimization (`-XX:+UseZGC -XX:+ZGenerational`), and native cross-platform runner scripts for Windows (`.bat`) and Linux/macOS (`.sh`).

---

## Design Decisions & Architecture Comparison

Comparing this implementation approach to conventional framework-based web stacks:

| Dimension | Conventional Framework Stack (e.g. Spring Boot) | Ultimo Architecture |
| :--- | :--- | :--- |
| **Networking** | High-level framework abstraction | Raw TCP sockets (`ServerSocket`, `Socket`) + in-house RFC 6455 WebSocket engine |
| **Concurrency** | Platform thread pool or reactive callbacks | Java 21 Virtual Threads (Loom) + Actor pattern event loop |
| **State Synchronization** | Coarse locks or database transactions | Single-writer Actor mailbox (`LinkedBlockingQueue`) per room |
| **State Authority** | Often delegates validation to client | Authoritative server state with Fog of War (opponent hands masked) |
| **Runtime Footprint** | 250MB+ RSS baseline memory | Lightweight footprint (<60MB RSS) with Generational ZGC |
| **Wire Protocol** | Generic JSON serialization | Line-delimited ASCII protocol with zero serialization overhead |
| **Test Suite** | Mocked controller integration tests | 80+ unit, concurrency, domain, and end-to-end integration tests |

---

## System Architecture

### 1. Component Diagram

```mermaid
flowchart TD
    subgraph Clients["Clients"]
        C1["Client 1 (CLI / Netcat)"]
        C2["Client 2 (CLI / Netcat)"]
    end

    subgraph Network["Network Layer (Virtual Threads)"]
        Acc["SocketAcceptor (Platform Thread)"]
        S1["ClientSession 1 (Virtual Thread)"]
        S2["ClientSession 2 (Virtual Thread)"]
    end

    subgraph Matchmaking["Matchmaking & Orchestration"]
        RM["RoomManager"]
    end

    subgraph Rooms["Room Layer (Actor Model)"]
        GR["GameRoom Actor Loop (Single Worker Thread)"]
        Q[("Mailbox Queue (LinkedBlockingQueue)")]
    end

    subgraph Domain["Authoritative Domain Engine"]
        Engine["UltimoEngine"]
        TM["TurnManager"]
        MV["MoveValidator"]
        Deck["Deck (108 Cards)"]
    end

    C1 -->|"Raw TCP:8080"| Acc
    C2 -->|"Raw TCP:8080"| Acc
    Acc -->|"Spawn"| S1
    Acc -->|"Spawn"| S2
    S1 -->|"Register"| RM
    S2 -->|"Register"| RM
    RM -->|"Route to Room"| GR
    S1 -->|"Enqueue Command"| Q
    S2 -->|"Enqueue Command"| Q
    Q -->|"Sequential Drain"| GR
    GR -->|"Validate & Mutate"| Engine
    Engine --> TM
    Engine --> MV
    Engine --> Deck
    GR -->|"Filtered Fog-of-War Events"| S1
    GR -->|"Filtered Fog-of-War Events"| S2
```

### 2. Actor Pattern Concurrency Model

Rather than sharing state across reader threads and struggling with lock contention or deadlocks, `GameRoom` acts as an autonomous Actor:

```mermaid
sequenceDiagram
    autonumber
    participant Client1 as Client 1 (Alice)
    participant Session1 as ClientSession 1 [V-Thread]
    participant Mailbox as Room Mailbox [LinkedBlockingQueue]
    participant Actor as Room Actor Loop [Worker Thread]
    participant Engine as UltimoEngine [Authoritative]
    participant Session2 as ClientSession 2 [V-Thread]
    participant Client2 as Client 2 (Bob)

    Client1->>Session1: TCP Wire: "PLAY RED_5\n"
    Session1->>Mailbox: offer(CommandContext(session, PLAY))
    Note over Mailbox,Actor: Commands ordered deterministically (FIFO)
    Actor->>Mailbox: take()
    Actor->>Engine: playCard("Alice", RED_5, null)
    Engine-->>Actor: Success (turn advances to Bob)
    Actor->>Session1: Send: TABLE_UPDATE RED_5 RED
    Actor->>Session1: Send: HAND [remaining cards]
    Session1-->>Client1: TCP Wire: TABLE_UPDATE ...
    Actor->>Session2: Send: TABLE_UPDATE RED_5 RED
    Actor->>Session2: Send: HAND [Bob's hand only]
    Session2-->>Client2: TCP Wire: TABLE_UPDATE ...
```

### 3. Anti-Cheat: Authoritative Fog of War

In Ultimo, the server is the absolute source of truth. Player cards remain strictly on the server:

```mermaid
flowchart LR
    ServerState["Server State (Authoritative)<br/>• Alice: [RED_7, BLUE_SKIP]<br/>• Bob: [YELLOW_3, WILD]<br/>• Draw Deck: 84 cards"]

    FOW{"Fog of War Filter"}
    ServerState --> FOW

    AliceView["Sent to Alice:<br/>• Hand: [RED_7, BLUE_SKIP]<br/>• Opponents: Bob (2 cards)"]
    BobView["Sent to Bob:<br/>• Hand: [YELLOW_3, WILD]<br/>• Opponents: Alice (2 cards)"]

    FOW --> AliceView
    FOW --> BobView
```

Neither player has any network packet or memory access to what cards the opponent holds.

---

## Wire Protocol Specification

The protocol is bidirectional, UTF-8 encoded, and line-delimited with `\n`.

### Client Commands

| Command | Arguments | Description | Example |
| :--- | :--- | :--- | :--- |
| `JOIN` | `<username>` | Registers session identity and enters matchmaking | `JOIN Alice` |
| `START` | _(none)_ | Starts match manually when at least 2 players are in lobby | `START` |
| `PLAY` | `<card_token> [color]` | Plays a card from hand (color mandatory for wild cards) | `PLAY RED_7`<br/>`PLAY WILD_WILD BLUE` |
| `DRAW` | _(none)_ | Draws a card from the deck | `DRAW` |
| `PASS` | _(none)_ | Passes turn after drawing | `PASS` |
| `CHAT` | `<message>` | Broadcasts a chat message to room participants | `CHAT Good game!` |
| `PING` | _(none)_ | Heartbeat probe | `PING` |
| `QUIT` | _(none)_ | Gracefully leaves room and disconnects | `QUIT` |

### Server Events

| Event | Format | Description |
| :--- | :--- | :--- |
| `WELCOME` | `WELCOME <session_id> <message>` | Acknowledges connection and registration |
| `ROOM_STATUS`| `ROOM_STATUS <roomId> <current> <max> <p1,p2...>` | Broadcasts lobby status and connected players |
| `TOP_CARD` | `TOP_CARD <card_token> <active_color>` | Updates active discard card and table color |
| `TURN_ANNOUNCED` | `TURN_ANNOUNCED <active_player_name>` | Announces which player has the current turn |
| `HAND_UPDATE`| `HAND_UPDATE <card1> <card2> ...` | Sends player's private hand (Fog of War) |
| `OPPONENT_UPDATE`| `OPPONENT_UPDATE <opponent_name> <count>` | Broadcasts opponent's updated card count |
| `DRAW_RESULT`| `DRAW_RESULT <card_token>` | Reports card drawn to drawer |
| `CHAT_BROADCAST` | `CHAT_BROADCAST <sender> <message>` | Relays broadcast chat message or system alert |
| `GAME_OVER` | `GAME_OVER <winner_name>` | Declares match conclusion and winner name |
| `ERROR` | `ERROR <message>` | Reports syntax, domain, or turn validation error |
| `PONG` | `PONG` | Heartbeat response |

### Card Token Format

Cards are formatted as `<COLOR>_<VALUE>`:
- **Colors:** `RED`, `GREEN`, `BLUE`, `YELLOW`, `WILD`
- **Values:** `0`-`9`, `SKIP`, `REVERSE`, `DRAW_TWO`, `WILD`, `DRAW_FOUR`
- *Examples:* `RED_0`, `BLUE_SKIP`, `GREEN_DRAW_TWO`, `YELLOW_REVERSE`, `WILD_WILD`, `WILD_DRAW_FOUR`.

---

## Domain Engine & Game Rules

- **Full 108-Card Standard Deck:**
  - 19 Red, 19 Green, 19 Blue, 19 Yellow cards (one `0`, two of `1-9`).
  - 8 Skip, 8 Reverse, 8 Draw Two cards (2 of each color).
  - 4 Wild cards and 4 Wild Draw Four cards.
- **Fair Opening Discard:** If the initial revealed discard card is an action or wild card, it is returned and reshuffled until a standard number card (`0-9`) opens the table.
- **Two-Player Reverse Rule:** When only two players are in a match, playing `REVERSE` functions identically to `SKIP` (the current player immediately retains the turn).
- **Automated Discard Reshuffle:** When the draw deck is depleted, the discard pile (excluding the active top card) is automatically shuffled to replenish the deck.
- **Forfeit & Disconnect Handling:** If an opponent disconnects during an active game, remaining players win by forfeit rather than deadlocking the match loop.

---

## Web Client & Browser Gameplay

In addition to terminal clients, Ultimo ships with an embedded single-page application ([`WebClientAssets`](file:///e:/MeineProjekte/Ultimo/src/main/java/pl/ultimo/server/net/WebClientAssets.java)) that allows anyone to play directly in a web browser without installing anything:
- **Zero-Dependency Single-Page App:** Beautiful dark-felt card table, CSS animated cards, active color indicator, turn banners, and real-time chat.
- **RFC 6455 WebSockets in Pure Java:** Custom frame encoder/decoder ([`WebSocketFrameCodec`](file:///e:/MeineProjekte/Ultimo/src/main/java/pl/ultimo/server/net/WebSocketFrameCodec.java)) implemented without Netty, Undertow, or third-party libraries.
- **Cross-Platform Play:** CLI players and Web Browser players play together in the exact same game rooms in real time!
- **Instant Access:** Open `http://localhost:8080` in your web browser while the server is running.

---

## Terminal Client & Dual-Mode UI

The project includes an interactive terminal client ([`TerminalClientApp`](file:///e:/MeineProjekte/Ultimo/src/main/java/pl/ultimo/client/TerminalClientApp.java)) featuring:
- **Dual-Mode Rendering ([`TerminalRenderer`](file:///e:/MeineProjekte/Ultimo/src/main/java/pl/ultimo/client/TerminalRenderer.java)):** Full ANSI color cards on modern consoles (Windows Terminal, Linux, macOS) with automatic or `--plain` plaintext fallback (`[R-7]`, `[B-SKIP]`) for legacy environments.
- **Quick-Play Numeric Input:** Choose cards directly by number (`1-N`) rather than typing out token names.
- **Interactive Wild Color Picker:** Prompts for color declaration (`1:RED, 2:GREEN, 3:BLUE, 4:YELLOW`).
- **Asynchronous Wire Listener:** Real-time updates without blocking player input.

---

## Cloud Deployment & Hosting

Ultimo is designed for one-click cloud deployment. See the comprehensive [DEPLOYMENT.md](DEPLOYMENT.md) guide for details:
- **Google Cloud Run (Serverless):** Automated zero-cost hosting via `./cloudrun-deploy.sh` or `cloudrun-deploy.bat`.
- **Linux VPS (Docker Compose):** Instant single-command orchestration via `docker compose up -d`.
- **Production Reverse Proxy:** Ready-to-use Caddy and NGINX configurations with automated SSL/TLS (`wss://`).

---

## Project Directory Structure

```text
pl.ultimo
├── client
│   ├── TerminalClientApp.java       # Interactive CLI client with async event loop
│   └── TerminalRenderer.java        # ANSI & Plain-text dual-mode card renderer
├── core
│   ├── engine
│   │   ├── MoveValidator.java       # Rule validation & legal play verification
│   │   ├── TurnManager.java         # Clockwise/Counter-clockwise turn logic
│   │   └── UltimoEngine.java        # Pure domain state engine & action executor
│   ├── model
│   │   ├── Card.java                # Immutable card representation
│   │   ├── CardColor.java           # Card colors enum (RED, GREEN, BLUE, YELLOW, WILD)
│   │   └── CardValue.java           # Card faces (0-9, SKIP, REVERSE, DRAW_TWO, WILD, DRAW_FOUR)
│   └── state
│       ├── Deck.java                # 108-card deck shuffling & replenish logic
│       ├── GameState.java           # Immutable state snapshot
│       ├── Hand.java                # Player card container
│       └── Player.java              # Player domain entity
├── protocol
│   ├── ClientCommand.java           # Strongly-typed client command records
│   ├── ProtocolCodec.java           # Wire protocol serialization / deserialization
│   ├── ProtocolException.java       # Wire protocol syntax exception
│   └── ServerEvent.java             # Strongly-typed server event records
└── server
    ├── ServerApp.java               # Server bootstrap & connection orchestrator
    ├── config
    │   └── ServerConfig.java        # Server parameters & configuration record
    ├── net
    │   ├── ClientSession.java       # Universal TCP/WebSocket virtual-thread session
    │   ├── ConnectionListener.java  # Connection lifecycle event listener
    │   ├── SocketAcceptor.java      # TCP socket listener
    │   ├── WebClientAssets.java     # Embedded HTML5/CSS/JS single-page web app
    │   └── WebSocketFrameCodec.java # Pure Java RFC 6455 WebSocket frame encoder/decoder
    └── room
        ├── GameRoom.java            # Room Actor loop with LinkedBlockingQueue mailbox
        └── RoomManager.java         # Matchmaking & room lifecycle manager
```

---

## Quick Start

### Prerequisites
- **Java 21 LTS** (JDK 21 or higher)
- Optional: **Docker** & **Docker Compose**

### Option A: Web Browser (Instant Play)

1. Launch the server:
   ```cmd
   run-server.bat
   ```
   *(or `./run-server.sh` on Linux/macOS)*

2. Open your web browser and go to:
   ```text
   http://localhost:8080
   ```
3. Enter your username, click **Join Match**, and play in the browser! Open another tab to test multiplayer.

---

### Option B: Native Scripts (Zero Build Tools)

Pre-configured launch scripts are included for instant execution on any OS:

#### Windows
```cmd
:: 1. Launch the server (Window 1)
run-server.bat

:: 2. Launch Client 1 (Window 2)
run-client.bat

:: 3. Launch Client 2 (Window 3)
run-client.bat
```

#### Linux & macOS
```bash
# Make scripts executable
chmod +x run-server.sh run-client.sh

# 1. Launch the server (Terminal 1)
./run-server.sh

# 2. Launch Client 1 (Terminal 2)
./run-client.sh

# 3. Launch Client 2 (Terminal 3)
./run-client.sh
```

---

### Option C: Maven

```bash
# 1. Run all 80 tests
mvn clean test

# 2. Package standalone JAR
mvn clean package

# 3. Run server
java -jar target/ultimo-server.jar

# 4. Run terminal client
java -cp target/ultimo-server.jar pl.ultimo.client.TerminalClientApp
```

---

### Option D: Docker & Docker Compose

Launch the containerized server in one command:

```bash
# Build and run with Docker Compose
docker compose up --build
```

Or build and run the lightweight Alpine container manually:

```bash
# Build multi-stage image
docker build -t ultimo-server .

# Run on port 8080
docker run -p 8080:8080 --name ultimo-server ultimo-server
```

---

### Option E: Raw TCP Terminal (Netcat / Telnet)

Because the wire protocol is plain line-delimited text, you can connect directly with standard OS networking utilities:

```bash
nc localhost 8080
```
Then type:
```text
JOIN Alice
CHAT Hello from raw TCP!
DRAW
PASS
QUIT
```

---

## Testing & Quality Assurance

The codebase includes **80 automated unit, concurrency, domain, and end-to-end integration tests**:

- **Domain Tests:** Complete card distributions (108 cards), turn progression, reverse direction in 2-player vs. 3-player, skip logic, deck replenishment.
- **Protocol Codec Tests:** Round-trip serialization/deserialization, malformed syntax resilience, wild color validation.
- **Concurrency & Actor Tests:** Virtual thread connection handling, room mailbox FIFO ordering, client disconnect handling, room cleanup.
- **Anti-Cheat Verification:** Strict assertion that events broadcast to opponents mask private hand contents (Fog of War).
- **RFC 6455 WebSocket Tests:** Frame decoding/encoding, masked client frame parsing, official RFC 6455 SHA-1 handshake vector verification.
- **WebSocket & HTTP Integration Tests:** Full end-to-end WebSocket tests using Java 21's native `java.net.http.WebSocket`, static HTML5 client delivery, and `/health` probe verification.
- **End-to-End Game Flow:** Complete socket-level multiplayer simulation from connection and matchmaking to game completion.

```bash
# Running tests with standalone JUnit Platform launcher:
java -jar .tools/junit-platform-console-standalone-1.10.2.jar \
     execute --class-path "target/classes;target/test-classes" --scan-class-path
```

All 80 tests execute in ~2.5 seconds.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
