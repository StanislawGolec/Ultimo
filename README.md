# Ultimo: High-Performance Authoritative Multiplayer Game Server

[![Java Version](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Virtual Threads](https://img.shields.io/badge/Concurrency-Virtual%20Threads%20%28Loom%29-blue.svg)](https://openjdk.org/jeps/444)
[![Tests](https://img.shields.io/badge/Tests-311%2F311%20Passing%20%28100%25%29-brightgreen.svg)](https://junit.org/junit5/)
[![WebSockets](https://img.shields.io/badge/WebSockets-RFC%206455%20Pure%20Java-9cf.svg)](#web-client--browser-gameplay)
[![Dependencies](https://img.shields.io/badge/Runtime%20Dependencies-0%20%28Pure%20Java%20StdLib%29-success.svg)](#architectural-highlights)
[![Docker](https://img.shields.io/badge/Docker-Multi--stage%20Alpine-2496ED.svg)](https://www.docker.com/)
[![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions%20Matrix-2088FF.svg?logo=githubactions&logoColor=white)](.github/workflows/ci.yml)
[![Google Cloud Run](https://img.shields.io/badge/Cloud%20Run-Production%20Ready-4285F4.svg?logo=googlecloud&logoColor=white)](#cloud-deployment--hosting)
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
- [Observability, Metrics & SRE Health Probes](#observability-metrics--sre-health-probes)
- [Performance Benchmarking & Profiling Results](#performance-benchmarking--profiling-results)
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
- [CI/CD Automation Pipeline](#cicd-automation-pipeline)
- [Testing & Quality Assurance](#testing--quality-assurance)
- [License](#license)

---

## Architectural Highlights

- **Zero Runtime Frameworks:** Built with the standard Java 21 runtime library (`java.net`, `java.util.concurrent`, `java.io`). No external HTTP, serialization, or networking dependencies.
- **Virtual Threads (Project Loom):** Every incoming TCP client connection is handled by a lightweight Virtual Thread (`Executors.newVirtualThreadPerTaskExecutor()`), achieving high concurrent connection scalability with minimal OS thread overhead.
- **Actor-Pattern Concurrency:** Rather than wrapping mutable game state with coarse synchronized blocks or lock contention, each active room executes a dedicated single-threaded event loop driven by a sealed `RoomEnvelope` mailbox (`LinkedBlockingQueue`). This guarantees zero monitor lock contention, zero carrier thread pinning, and deterministic FIFO event processing.
- **Authoritative Server with Fog of War:** Complete protection against client-side tampering or packet inspection. The server computes all moves, deck shuffles, and penalties. Opponents' cards are withheld; clients receive only their own hand and table metadata.
- **Custom Line-Delimited Wire Protocol v2:** High-performance, human-readable text protocol (`\n`-terminated) featuring real-time defense state broadcasting, player connection status tags, bounded 8KB line reading, and cryptographic reconnect credentials.
- **Built-in Autonomous Heuristic AI Bots:** Multi-tier AI opponents (`EASY`, `NORMAL`, `HARD`) executing in-memory on virtual threads without external dependencies or heavy model runners.
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
| `JOIN` | `<username> [room_id] [code] [cap]` | Registers session identity and enters matchmaking | `JOIN Alice` |
| `CREATE_ROOM` | `<name> <PUBLIC\|PRIVATE> [code] [cap]` | Creates a targeted public or private room | `CREATE_ROOM VIP PRIVATE 1234 4` |
| `LIST_ROOMS` | _(none)_ | Requests live list of active public rooms | `LIST_ROOMS` |
| `START` | _(none)_ | Host starts match when at least 2 players in room | `START` |
| `KICK` | `<target_player>` | Host removes a player from the room | `KICK Bob` |
| `TRANSFER_OWNERSHIP` | `<target_player>` | Host transfers ownership privilege | `TRANSFER_OWNERSHIP Bob` |
| `PLAY` | `<card_token> [color]` | Plays a card from hand (color mandatory for wild cards) | `PLAY RED_7`<br/>`PLAY WILD_WILD BLUE` |
| `DRAW` | _(none)_ | Draws a card from the deck | `DRAW` |
| `PASS` | _(none)_ | Passes turn after drawing | `PASS` |
| `ULTIMO` | _(none)_ | Declares penultimate card in hand | `ULTIMO` |
| `CATCH_ULTIMO` | `<target_player>` | Challenges undeclared single card | `CATCH_ULTIMO Bob` |
| `CHALLENGE_WILD_FOUR` | _(none)_ | Challenges previous Wild Draw Four play legality | `CHALLENGE_WILD_FOUR` |
| `CHAT` | `<message>` | Broadcasts a chat message to room participants | `CHAT Good game!` |
| `PING` | _(none)_ | Heartbeat probe | `PING` |
| `RECONNECT` | `<username> <room_id> <token>` | Reclaims seat and private hand snapshot within 120s grace period | `RECONNECT Alice room-1 9f8a7c2b...` |
| `QUIT` | _(none)_ | Gracefully leaves room, closes session, and disconnects | `QUIT` |

### Server Events

| Event | Format | Description |
| :--- | :--- | :--- |
| `WELCOME` | `WELCOME <session_id> <reconnect_token> <message>` | Acknowledges connection and provides cryptographic reconnect credentials |
| `ROOM_STATUS`| `ROOM_STATUS <roomId> <current> <max> <p1,p2...>` | Broadcasts lobby status and connected players |
| `ROOM_LIST` | `ROOM_LIST <count> [rooms...]` | Reports available public rooms |
| `ROOM_OWNER_CHANGED` | `ROOM_OWNER_CHANGED <new_owner>` | Broadcasts host transfer |
| `TOP_CARD` | `TOP_CARD <card_token> <active_color>` | Updates active discard card and table color |
| `TURN_ANNOUNCED` | `TURN_ANNOUNCED <active_player_name>` | Announces which player has the current turn |
| `HAND_UPDATE`| `HAND_UPDATE <card1> <card2> ...` | Sends player's private hand (Fog of War) |
| `OPPONENT_UPDATE`| `OPPONENT_UPDATE <opponent_name> <count>` | Broadcasts opponent's updated card count |
| `DEFENSE_STATE` | `DEFENSE_STATE <defense_type> <penalty_cards> <target_player>` | Broadcasts active penalty stack (+2/+4) or skip deflection state |
| `PLAYER_STATE` | `PLAYER_STATE <username> <CONNECTED\|DISCONNECTED\|RECONNECTED> [grace_sec]` | Broadcasts connection lifecycle status and remaining disconnect grace time |
| `PLAYER_FINISHED` | `PLAYER_FINISHED <player_name> <rank>` | Broadcasts player finishing and placement rank |
| `MATCH_STANDINGS` | `MATCH_STANDINGS <rank:player>...` | Broadcasts final ordered placement standings for tournament round |
| `DRAW_RESULT`| `DRAW_RESULT <card_token>` | Reports card drawn to drawer |
| `ULTIMO_ANNOUNCED` | `ULTIMO_ANNOUNCED <player_name>` | Broadcasts Ultimo declaration |
| `CHALLENGE_RESULT` | `CHALLENGE_RESULT <challenger> <target> <GUILTY\|INNOCENT> <draws>` | Challenge outcome |
| `ROUND_OVER` | `ROUND_OVER <winner> <pts> <WON\|IN_PROGRESS> <scores>` | Concludes round and reports tournament points |
| `CHAT_BROADCAST` | `CHAT_BROADCAST <sender> <message>` | Relays broadcast chat message or system alert |
| `SERVER_SHUTDOWN`| `SERVER_SHUTDOWN <drain_seconds>` | Notifies clients of graceful server SIGTERM drain |
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

## Observability, Metrics & SRE Health Probes

Ultimo embeds zero-overhead observability directly into the standard HTTP transport multiplexer without any external monitoring agents:

- **Prometheus Text Exposition 0.0.4 (`GET /metrics`):**
  - Lock-free counter and gauge telemetry utilizing `java.util.concurrent.atomic.LongAdder` and `AtomicLong`.
  - Metrics exported:
    - `ultimo_connections_total`: Total inbound TCP and WebSocket connections opened.
    - `ultimo_active_connections`: Live active client sessions.
    - `ultimo_active_rooms`: Number of active concurrent game rooms.
    - `ultimo_websocket_upgrades_total`: Total successful RFC 6455 upgrades.
    - `ultimo_events_emitted_total`: Wire protocol events dispatched to clients.
    - `ultimo_commands_processed_total`: Wire protocol commands decoded and executed.
    - `ultimo_command_duration_seconds_bucket`: Lock-free cumulative latency histogram.
    - JVM Memory: `jvm_memory_used_bytes`, `jvm_memory_max_bytes`.
- **Kubernetes & Cloud Run Lifecycle Probes:**
  - `GET /health` and `GET /healthz`: Immediate liveness probe (200 OK) confirming TCP acceptor responsiveness.
  - `GET /ready`: Coordinated readiness probe returning `200 OK` during active serving, immediately transitioning to `503 Service Unavailable` upon receiving a SIGTERM shutdown signal to cleanly drain ingress load balancer traffic.
- **Graceful SIGTERM Drain Sequence:**
  - Registered via `Runtime.getRuntime().addShutdownHook`.
  - Broadcasts `SERVER_SHUTDOWN <drainSeconds>` to all active clients.
  - Drains active games within the grace window before cleanly terminating sockets.

---

## Performance Benchmarking & Profiling Results

Microbenchmarks and high-concurrency stress tests are executed via `src/test/java/pl/ultimo/benchmark/` to verify codec serialization speed and Loom virtual thread scaling:

### 1. Codec Throughput (Ops / Sec & Latency)

| Benchmark Target | Operations / Sec | Latency per Operation | Allocations |
| :--- | :--- | :--- | :--- |
| **`WebSocketFrameCodec.writeTextFrame`** | **~7,314,000 ops/sec** | **136.7 ns/op** | Minimal heap buffer |
| **`WebSocketFrameCodec.decodeFrame`** | **~5,664,000 ops/sec** | **176.5 ns/op** | Zero-copy header slice |
| **`ProtocolCodec.encodeEvent`** | **~2,284,000 ops/sec** | **437.6 ns/op** | Single ASCII line |
| **`ProtocolCodec.decodeCommand`** | **~1,088,000 ops/sec** | **918.5 ns/op** | Stateless token parse |

### 2. High-Concurrency Virtual Thread Scaling & Memory Footprint

- **Virtual Threads Tested:** 1,000 concurrent client sessions exchanging wire protocol commands.
- **Success Rate:** 100% (0 session drops, 0 thread starvation).
- **Resident Heap Footprint:** **~51 MB** (strictly adhering to the $< 60$ MB ceiling).
- **GC Performance:** Optimized for Java 21 Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) with sub-millisecond pause times.
- **Project Loom Thread Pinning Audit:** Zero synchronized monitor blocks around socket I/O. Execution with `-Djdk.tracePinnedThreads=full` confirms **zero pinned carrier threads**.

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
│   ├── bot
│   │   ├── BotAction.java           # Immutable bot action representation
│   │   ├── BotDecision.java         # Autonomous decision record with thinking latency
│   │   └── HeuristicBotEngine.java  # Multi-tier heuristic AI strategy engine
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
    ├── metrics
    │   ├── MetricsExporter.java     # Prometheus text format serialization
    │   └── MetricsRegistry.java     # Zero-allocation lock-free telemetry registry
    ├── net
    │   ├── ClientSession.java       # Universal TCP/WebSocket virtual-thread session
    │   ├── ConnectionListener.java  # Connection lifecycle event listener
    │   ├── SocketAcceptor.java      # TCP socket listener
    │   ├── WebClientAssets.java     # Embedded HTML5/CSS/JS single-page web app
    │   └── WebSocketFrameCodec.java # Pure Java RFC 6455 WebSocket frame encoder/decoder
    └── room
        ├── GameRoom.java            # Room Actor loop with sealed RoomEnvelope mailbox
        ├── RoomEnvelope.java        # Sealed message envelope hierarchy
        ├── RoomManager.java         # Matchmaking & room lifecycle manager
        └── RoomState.java           # Room lifecycle state machine
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
# 1. Run all 311 automated tests
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

## CI/CD Automation Pipeline

The repository includes a GitHub Actions matrix workflow ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) running on every commit and pull request:
- **Matrix OS Validation:** Compiles and executes the test suite across both `ubuntu-latest` and `windows-latest`.
- **JDK 21 Temurin Setup:** Tests against official Eclipse Temurin JDK 21.
- **Loom Pinning Detection:** Enforces `-Djdk.tracePinnedThreads=full` in Maven Surefire to ensure no commit reintroduces virtual thread carrier pinning.
- **Multi-Stage Alpine Docker Build:** Assembles minimal production Docker container and tests live `/healthz`, `/ready`, and `/metrics` probe responses inside the CI environment.
- **Cloud Run Deployment Dry-Run:** Validates deployment scripts and descriptors for Google Cloud Run.

---

## Testing & Quality Assurance

The codebase includes **311 automated unit, concurrency, domain, and end-to-end integration tests**:

- **Official Ultimo Rules Extended Tests (30 tests):** Complete tournament scoring up to 500 points, penultimate card `ULTIMO` declarations, `CATCH_ULTIMO` penalties, and `CHALLENGE_WILD_FOUR` bluff legality checks.
- **Autonomous Heuristic Bot Engine Tests (16 tests):** Tactical card valuation, penalty stack defense, dominant color frequency analysis, challenge heuristics, and virtual-thread bot game loops.
- **Line-Delimited Protocol v2 & Keepalive Tests (17 tests):** 8KB bounded line reader DoS defense, `DEFENSE_STATE` and `PLAYER_STATE` wire codecs, cryptographic token reconnect under simulated TCP RST, and idle TCP heartbeat keepalive survival exceeding 90 seconds.
- **Chaos Resilience & Cross-Ecosystem Tests (6 tests):** Network jitter (150-200ms delay), abrupt TCP drops (RST emulation via `soLinger(true, 0)`), targeted room auto-reconnection, and polyglot TCP + WebSocket interoperability.
- **RFC 6455 WebSocket Security & Fuzzing Tests (40 tests):** Mandatory client frame masking enforcement (Close 1002), 64KB DoS payload limits (Close 1009), control frame size limits, RSV bit validation, and random byte fuzzing stream resilience.
- **Virtual Thread Concurrency & Pinning Tests (10 tests):** Virtual thread connection scalability, sealed `RoomEnvelope` mailbox processing, dedicated outbound serializer queues, idle connection watchdog reaper, and zero monitor locks in `pl.ultimo.server`.
- **SRE Graceful Shutdown & Health Probe Tests (3 tests):** Coordinated SIGTERM drain sequence, transition of `/ready` to 503, and `SERVER_SHUTDOWN` event broadcasting.
- **Prometheus Metrics Text Exposition Tests (7 tests):** Lock-free counter/gauge updates (`ultimo_connections_total`, etc.), cumulative histogram latency tracking, and valid Prometheus 0.0.4 text format validation.
- **Codec Microbenchmarks & Load Tests (3 tests):** Verification of $>1,000,000$ ops/sec codec throughput and 1,000 concurrent virtual threads under 60MB heap memory.

```bash
# Running tests with standalone JUnit Platform launcher:
java "-Djdk.tracePinnedThreads=full" -jar .tools/junit-platform-console-standalone-1.10.2.jar \
     execute --class-path "target/classes;target/test-classes" --scan-class-path
```

All 311 tests execute cleanly with zero carrier pinning warnings.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
