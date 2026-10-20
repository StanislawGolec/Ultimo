package pl.ultimo.client;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Interactive command-line interface client for playing Ultimo over TCP.
 * Runs a background event receiver thread while the main thread handles user input.
 */
public class TerminalClientApp {

    private final String host;
    private final int port;
    private String username;
    private final TerminalRenderer renderer;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    // Client view state
    private final List<Card> myHand = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> opponentCardCounts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> disconnectedOpponents = ConcurrentHashMap.newKeySet();
    private final Map<String, String> playerConnectionTags = new ConcurrentHashMap<>();
    private Card topCard;
    private CardColor activeColor;
    private String activePlayerName;
    private boolean gameStarted;

    private String roomCode;
    private volatile String currentHudAlert;
    private volatile boolean defenseStateReceived;

    public TerminalClientApp(String host, int port, String username, boolean ansiEnabled) {
        this(host, port, username, null, ansiEnabled);
    }

    public TerminalClientApp(String host, int port, String username, String roomCode, boolean ansiEnabled) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.roomCode = (roomCode != null && !roomCode.isBlank()) ? roomCode.trim() : null;
        this.renderer = new TerminalRenderer(ansiEnabled);
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 8080;
        String name = null;
        String room = null;
        boolean ansi = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-host" -> {
                    if (i + 1 < args.length) host = args[++i];
                }
                case "-port" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
                case "-name" -> {
                    if (i + 1 < args.length) name = args[++i];
                }
                case "-code", "-room" -> {
                    if (i + 1 < args.length) room = args[++i];
                }
                case "--plain", "-plain", "--no-color" -> ansi = false;
            }
        }

        Scanner scanner = new Scanner(System.in);
        if (name == null || name.isBlank()) {
            System.out.print("Podaj swój nick gracza: ");
            name = scanner.nextLine().trim();
            if (name.isEmpty()) {
                name = "Gracz_" + (System.currentTimeMillis() % 1000);
            }
        }

        TerminalClientApp client = new TerminalClientApp(host, port, name, room, ansi);
        client.start(scanner);
    }

    public void start(Scanner scanner) {
        System.out.println("Łączenie z serwerem Ultimo pod " + host + ":" + port + " jako '" + username + "'"
                + (roomCode != null ? " [Pokój/Kod: " + roomCode + "]" : "") + "...");

        try {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            // Start background receiver thread
            Thread.ofVirtual().name("client-receiver").start(this::receiveServerEvents);

            // Send initial JOIN command
            String joinCmd = (roomCode != null && !roomCode.isBlank())
                    ? "JOIN " + username + " " + roomCode
                    : "JOIN " + username;
            sendCommand(joinCmd);

            // Run user input loop on main thread
            runInputLoop(scanner);

        } catch (IOException e) {
            System.err.println("Nie udało się połączyć z serwerem: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    void runInputLoop(Scanner scanner) {
        printHelp();

        while (isRunning.get() && scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            if (input.equalsIgnoreCase("q") || input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                System.out.println("Opuszczanie gry...");
                sendCommand("QUIT");
                break;
            }

            if (input.equalsIgnoreCase("h") || input.equalsIgnoreCase("help")) {
                printHelp();
                continue;
            }

            if (input.equalsIgnoreCase("t") || input.equalsIgnoreCase("table")) {
                printTableView();
                continue;
            }

            if (input.equalsIgnoreCase("s") || input.equalsIgnoreCase("start")) {
                sendCommand("START");
                continue;
            }

            if (input.equalsIgnoreCase("bot") || input.equalsIgnoreCase("addbot") || input.equalsIgnoreCase("add_bot")) {
                sendCommand("ADD_BOT");
                continue;
            }

            if (input.toLowerCase().startsWith("bot ") || input.toLowerCase().startsWith("addbot ")) {
                String diff = input.substring(input.indexOf(' ') + 1).trim().toUpperCase();
                sendCommand(diff.isEmpty() ? "ADD_BOT" : "ADD_BOT " + diff);
                continue;
            }

            if (input.equalsIgnoreCase("leave") || input.equalsIgnoreCase("l")) {
                sendCommand("LEAVE_ROOM");
                continue;
            }

            if (input.equalsIgnoreCase("d") || input.equalsIgnoreCase("draw")) {
                sendCommand("DRAW");
                continue;
            }

            if (input.equalsIgnoreCase("pass") || input.equalsIgnoreCase("p")) {
                sendCommand("PASS");
                continue;
            }

            if (input.equalsIgnoreCase("u") || input.equalsIgnoreCase("ultimo")) {
                sendCommand("ULTIMO");
                continue;
            }

            if (input.toLowerCase().startsWith("catch ")) {
                String target = input.substring(6).trim();
                sendCommand("CATCH_ULTIMO " + target);
                continue;
            }

            if (input.equalsIgnoreCase("challenge") || input.equalsIgnoreCase("ch")) {
                sendCommand("CHALLENGE_WILD_FOUR");
                continue;
            }

            if (input.toLowerCase().startsWith("c ") || input.toLowerCase().startsWith("chat ")) {
                String msg = input.substring(input.indexOf(' ') + 1).trim();
                sendCommand("CHAT " + msg);
                continue;
            }

            // Single or multi-card combo play by card numbers [e.g. "1", "1,2", "1 2", "play 1,2"]
            String playTarget = input;
            if (playTarget.toLowerCase().startsWith("play ")) {
                playTarget = playTarget.substring(5).trim();
            }

            if (playTarget.matches("^[0-9]+([\\s,]+[0-9]+)*$")) {
                String[] tokens = playTarget.split("[\\s,]+");
                List<Integer> selectedIndices = new ArrayList<>();
                Set<Integer> uniqueCheck = new HashSet<>();
                boolean validIndices = true;

                synchronized (myHand) {
                    for (String token : tokens) {
                        try {
                            int index = Integer.parseInt(token) - 1;
                            if (index < 0 || index >= myHand.size()) {
                                System.out.println("Niepoprawny numer karty: " + token + "! Dostępne numery: 1 do " + myHand.size());
                                validIndices = false;
                                break;
                            }
                            if (!uniqueCheck.add(index)) {
                                System.out.println("Karta #" + token + " została podana więcej niż raz! Każda karta z ręki ma swój unikalny numer.");
                                validIndices = false;
                                break;
                            }
                            selectedIndices.add(index);
                        } catch (NumberFormatException e) {
                            validIndices = false;
                            break;
                        }
                    }

                    if (!validIndices || selectedIndices.isEmpty()) {
                        continue;
                    }

                    List<Card> cardsToPlay = new ArrayList<>();
                    for (int idx : selectedIndices) {
                        cardsToPlay.add(myHand.get(idx));
                    }

                    boolean hasWild = cardsToPlay.stream().anyMatch(Card::isWild);
                    String cardsStr = cardsToPlay.stream()
                            .map(ProtocolCodec::formatCard)
                            .collect(Collectors.joining(","));

                    if (hasWild) {
                        CardColor chosenColor = promptWildColor(scanner);
                        sendCommand("PLAY " + cardsStr + " " + chosenColor.name());
                    } else {
                        sendCommand("PLAY " + cardsStr);
                    }
                }
                continue;
            }

            // Raw protocol command fallback (e.g. "PLAY RED_5")
            sendCommand(input);
        }
    }

    private CardColor promptWildColor(Scanner scanner) {
        while (true) {
            System.out.print("Wybierz kolor dla dzikiej karty [R=Czerwony, Y=Żółty, G=Zielony, B=Niebieski]: ");
            String choice = scanner.nextLine().trim().toUpperCase();
            switch (choice) {
                case "R", "RED", "CZERWONY" -> { return CardColor.RED; }
                case "Y", "YELLOW", "ZOLTY", "ŻÓŁTY" -> { return CardColor.YELLOW; }
                case "G", "GREEN", "ZIELONY" -> { return CardColor.GREEN; }
                case "B", "BLUE", "NIEBIESKI" -> { return CardColor.BLUE; }
                default -> System.out.println("Niepoprawny wybór. Wpisz R, Y, G lub B.");
            }
        }
    }

    private void receiveServerEvents() {
        try {
            String line;
            while (isRunning.get() && (line = reader.readLine()) != null) {
                handleServerLine(line);
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                System.out.println("\nPołączenie z serwerem zostało zamknięte.");
            }
        } finally {
            isRunning.set(false);
        }
    }

    void handleServerLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if ("PING".equalsIgnoreCase(trimmed)) {
            sendCommand("PONG");
            return;
        }
        try {
            ServerEvent event = ProtocolCodec.decodeEvent(trimmed);
            handleEvent(event);
        } catch (Exception e) {
            System.out.println("[Serwer]: " + line);
        }
    }

    void handleEvent(ServerEvent event) {
        switch (event) {
            case ServerEvent.Welcome welcome -> {
                System.out.println("\n*** " + welcome.message() + " ***");
                System.out.println("Twój identyfikator gracza: " + welcome.assignedPlayerId());
            }
            case ServerEvent.RoomStatus roomStatus -> {
                System.out.println("\n[LOBBY] Pokój " + roomStatus.roomId() + ": Gracze ("
                        + roomStatus.currentPlayers() + "/" + roomStatus.maxPlayers() + "): "
                        + String.join(", ", roomStatus.playerNames()));
                if (roomStatus.currentPlayers() < roomStatus.maxPlayers()) {
                    if (roomStatus.currentPlayers() >= 2) {
                        System.out.println("Możesz wpisać 'start' (lub 's'), aby rozpocząć grę w " + roomStatus.currentPlayers() + " graczy, lub 'bot' aby dodać bota AI.");
                    } else {
                        System.out.println("Oczekiwanie na dołączenie drugiego gracza (lub wpisz 'bot' aby dodać bota AI)...");
                    }
                }
            }
            case ServerEvent.TopCard top -> {
                this.topCard = top.card();
                this.activeColor = top.activeColor();
                this.gameStarted = true;
                if (!defenseStateReceived) {
                    if (topCard != null && topCard.value() == pl.ultimo.core.model.CardValue.DRAW_TWO) {
                        currentHudAlert = "⚠️ PENALTY STACK: +2 CARDS! Play a Draw card to defend or type 'd' to take penalty.";
                    } else if (topCard != null && topCard.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR) {
                        currentHudAlert = "⚠️ PENALTY STACK: +4 CARDS! Play a Draw card to defend or type 'd' to take penalty.";
                    } else if (topCard != null && topCard.value() != pl.ultimo.core.model.CardValue.SKIP) {
                        currentHudAlert = null;
                    }
                }
                printTableView();
            }
            case ServerEvent.HandUpdate handUpdate -> {
                synchronized (myHand) {
                    myHand.clear();
                    myHand.addAll(handUpdate.cards());
                }
                if (gameStarted) {
                    printTableView();
                }
            }
            case ServerEvent.TurnAnnounced turn -> {
                this.activePlayerName = turn.activePlayerName();
                if (gameStarted) {
                    printTableView();
                }
            }
            case ServerEvent.OpponentUpdate opp -> {
                opponentCardCounts.put(opp.opponentName(), opp.cardCount());
            }
            case ServerEvent.DrawResult draw -> {
                System.out.println("\n[+] Dobrałeś kartę: " + renderer.renderCard(draw.drawnCard()));
            }
            case ServerEvent.ErrorEvent err -> {
                System.out.println("\n[-] BŁĄD RUCHU: " + err.errorMessage());
            }
            case ServerEvent.ChatBroadcast chat -> {
                if (chat.message().contains("no playable moves") || chat.message().contains("auto-passed") || chat.message().contains("Turn passed")) {
                    System.out.println("\n[ℹ️ AUTO-PASS] " + chat.message());
                } else {
                    System.out.println("\n[CZAT] " + chat.sender() + ": " + chat.message());
                }
            }
            case ServerEvent.GameOver gameOver -> {
                System.out.println("\n============================================================");
                System.out.println("               *** KONIEC MECZU ULTIMO ***                  ");
                System.out.println("  Zwycięzca: " + gameOver.winnerName());
                System.out.println("============================================================\n");
                System.out.print(renderer.renderPodium(gameOver.winnerName(), Collections.emptyMap()));
            }
            case ServerEvent.ServerShutdown shutdown -> {
                System.out.println("\n[!] OSTRZEŻENIE: Serwer jest wyłączany za " + shutdown.drainSeconds() + " sekund (graceful shutdown)...");
                isRunning.set(false);
            }
            case ServerEvent.UltimoDeclared ultimo -> {
                System.out.println("\n[!] GRACZ " + ultimo.playerName() + " ZGŁOSIŁ ULTIMO! (Pozostała mu 1 karta)");
            }
            case ServerEvent.UltimoPenalty penalty -> {
                System.out.println("\n[!] KARA ULTIMO: " + penalty.penalizedPlayer() + " nie zgłosił ULTIMO i został przyłapany przez "
                        + penalty.challenger() + "! Dobiera " + penalty.penaltyCards() + " karne karty.");
            }
            case ServerEvent.ChallengeResult res -> {
                System.out.println("\n[!] WYZWANIE WILD DRAW FOUR: " + (res.isGuilty() ? "WINNY (posiadał pasujący kolor)!" : "NIEWINNY!")
                        + " Gracz " + res.penalizedPlayer() + " dobiera " + res.penaltyCards() + " kart.");
            }
            case ServerEvent.RoundOver roundOver -> {
                System.out.println("\n============================================================");
                System.out.println("                   *** KONIEC RUNDY ***                     ");
                System.out.println("  Zwycięzca rundy: " + roundOver.roundWinner() + " (+" + roundOver.pointsEarned() + " pkt)");
                System.out.println("  Punktacja turnieju (do 500 pkt): " + roundOver.tournamentScores());
                if (roundOver.isTournamentWon()) {
                    System.out.println("  *** TURNIEJ ZAKOŃCZONY! Mistrzem zostaje " + roundOver.roundWinner() + "! ***");
                } else {
                    System.out.println("  Następna runda rozpocznie się za 5 sekund...");
                }
                System.out.println("============================================================\n");
                System.out.print(renderer.renderPodium(roundOver.roundWinner(), roundOver.tournamentScores()));
            }
            case ServerEvent.RoomOwnerChanged roc -> {
                System.out.println("\n[LOBBY] Właściciel pokoju (Host): " + roc.newOwner());
            }
            case ServerEvent.RoomLeft rl -> {
                System.out.println("\n[LOBBY] Opuszczono pokój " + rl.roomId() + ". Powrót do menu głównego.");
                this.gameStarted = false;
                this.topCard = null;
                this.activeColor = null;
                this.activePlayerName = null;
                this.currentHudAlert = null;
                synchronized (myHand) {
                    myHand.clear();
                }
                opponentCardCounts.clear();
                disconnectedOpponents.clear();
                playerConnectionTags.clear();
            }
            case ServerEvent.RoomList roomList -> {
                System.out.println("\n[POKOJE PUBLICZNE]");
                if (roomList.rooms().isEmpty()) {
                    System.out.println("  Brak dostępnych otwartych pokojów.");
                } else {
                    for (ServerEvent.RoomEntry r : roomList.rooms()) {
                        System.out.println("  - " + r.roomId() + " (" + r.name() + "): " + r.currentPlayers() + "/" + r.maxPlayers() + " [" + r.status() + "]");
                    }
                }
            }
            case ServerEvent.PlayerFinished pf -> {
                System.out.println("\n🏆 [FINISH] Gracz " + pf.playerName() + " zajął " + pf.rank() + ". miejsce!");
            }
            case ServerEvent.MatchStandings ms -> {
                System.out.println("\n============================================================");
                System.out.println("                 *** TABELA KOŃCOWA ***                    ");
                for (ServerEvent.Placement p : ms.placements()) {
                    System.out.println("  Miejsce " + p.rank() + ": " + p.playerName());
                }
                System.out.println("============================================================\n");
            }
            case ServerEvent.ActionDefenseState ads -> {
                defenseStateReceived = true;
                currentHudAlert = renderer.renderDefenseBanner(ads);
                if (currentHudAlert != null) {
                    System.out.println("\n" + currentHudAlert);
                }
                if (gameStarted) {
                    printTableView();
                }
            }
            case ServerEvent.PlayerState ps -> {
                String tag = renderer.renderPlayerStateTag(ps);
                if ("DISCONNECTED".equalsIgnoreCase(ps.status())) {
                    disconnectedOpponents.add(ps.username());
                    playerConnectionTags.put(ps.username(), tag);
                    System.out.println("\n[STATUS] " + ps.username() + " " + tag);
                } else if ("RECONNECTED".equalsIgnoreCase(ps.status()) || "CONNECTED".equalsIgnoreCase(ps.status())) {
                    disconnectedOpponents.remove(ps.username());
                    playerConnectionTags.put(ps.username(), tag);
                    System.out.println("\n[STATUS] " + ps.username() + " " + tag);
                } else {
                    disconnectedOpponents.remove(ps.username());
                    playerConnectionTags.remove(ps.username());
                    opponentCardCounts.remove(ps.username());
                }
                if (gameStarted) {
                    printTableView();
                }
            }
            case ServerEvent.Pong ignored -> {}
            default -> {
                if (event != null && "PING".equalsIgnoreCase(event.type().name())) {
                    sendCommand("PONG");
                }
            }
        }
    }

    private synchronized void printTableView() {
        if (!gameStarted) {
            return;
        }

        System.out.print("\n" + renderer.renderTable(
                topCard,
                activeColor,
                activePlayerName,
                username,
                opponentCardCounts,
                currentHudAlert,
                disconnectedOpponents,
                playerConnectionTags));
        System.out.println("Twoje karty:");
        synchronized (myHand) {
            System.out.println(renderer.renderHand(myHand));
            if (myHand.size() <= 2 && !myHand.isEmpty()) {
                System.out.println(">>> 🔔 ULTIMO ALERT: Pozostało Ci <= 2 karty! Wpisz 'u' lub 'ultimo' aby zgłosić ULTIMO! <<<");
            }
        }
        System.out.print("Wybór [1-" + myHand.size() + " / kombo np. 1,2 / d / pass / help]> ");
    }

    private void sendCommand(String commandLine) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(commandLine.endsWith("\n") ? commandLine : commandLine + "\n");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Błąd wysyłania komendy: " + e.getMessage());
        }
    }

    void setWriter(BufferedWriter writer) {
        this.writer = writer;
    }

    public String getCurrentHudAlert() {
        return currentHudAlert;
    }

    public Map<String, String> getPlayerConnectionTags() {
        return Collections.unmodifiableMap(playerConnectionTags);
    }

    public Set<String> getDisconnectedOpponents() {
        return Collections.unmodifiableSet(disconnectedOpponents);
    }

    private void printHelp() {
        System.out.println("\n--- DOSTĘPNE POLECENIA ---");
        System.out.println("  [1, 2... / 1,2 / 1 2] - Zagranie karty lub komba kart o tej samej wartości (np. 1,3)");
        System.out.println("  s, start              - Rozpoczęcie meczu (gdy w pokoju jest min. 2 graczy)");
        System.out.println("  bot, addbot           - Dodanie bota AI do pokoju (tylko Host w lobby)");
        System.out.println("  leave, l              - Opuszczenie obecnego pokoju");
        System.out.println("  d, draw               - Dobranie 1 karty z talii (lub przyjęcie kary ze stosu)");
        System.out.println("  p, pass               - Spasowanie tury (dozwolone po dobraniu karty)");
        System.out.println("  u, ultimo             - Zgłoszenie ULTIMO (gdy masz 1 lub 2 karty przed ruchem)");
        System.out.println("  catch <gracz>         - Złapanie gracza, który ma 1 kartę i nie powiedział ULTIMO (+2 karty kary)");
        System.out.println("  challenge, ch         - Wyzwanie legalności zagranej karty Wild Draw Four");
        System.out.println("  c <treść>             - Wysłanie wiadomości na czacie pokojowym");
        System.out.println("  t, table              - Ponowne wyświetlenie stołu i kart");
        System.out.println("  h, help               - Wyświetlenie tej pomocy");
        System.out.println("  q, quit               - Wyjście z gry");
        System.out.println("--------------------------\n");
    }

    public void disconnect() {
        isRunning.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}
