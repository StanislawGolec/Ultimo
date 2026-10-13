package pl.ultimo.client;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders Ultimo cards, hands, and game table in the terminal.
 * Supports dual-mode: ANSI 16-color rich rendering and plain-text fallback.
 */
public class TerminalRenderer {

    // ANSI Color Codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";

    private static final String BG_RED = "\u001B[41m\u001B[97m";       // White text on Red
    private static final String BG_GREEN = "\u001B[42m\u001B[97m";     // White text on Green
    private static final String BG_BLUE = "\u001B[44m\u001B[97m";      // White text on Blue
    private static final String BG_YELLOW = "\u001B[43m\u001B[30m";    // Black text on Yellow
    private static final String BG_WILD = "\u001B[45m\u001B[97m";      // White text on Magenta

    private static final String FG_RED = "\u001B[91m";
    private static final String FG_GREEN = "\u001B[92m";
    private static final String FG_BLUE = "\u001B[94m";
    private static final String FG_YELLOW = "\u001B[93m";
    private static final String FG_WILD = "\u001B[95m";

    private final boolean ansiEnabled;

    public TerminalRenderer(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    public static TerminalRenderer colored() {
        return new TerminalRenderer(true);
    }

    public static TerminalRenderer plain() {
        return new TerminalRenderer(false);
    }

    /**
     * Formats a single card for terminal display.
     */
    public String renderCard(Card card) {
        if (card == null) {
            return "[ BRAK ]";
        }

        String label = switch (card.value()) {
            case ZERO -> "0";
            case ONE -> "1";
            case TWO -> "2";
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case SKIP -> "SKIP";
            case REVERSE -> "REV";
            case DRAW_TWO -> "+2";
            case WILD -> "WILD";
            case WILD_DRAW_FOUR -> "WILD+4";
        };

        if (!ansiEnabled) {
            return "[" + card.color() + " " + label + "]";
        }

        String bg = switch (card.color()) {
            case RED -> BG_RED;
            case GREEN -> BG_GREEN;
            case BLUE -> BG_BLUE;
            case YELLOW -> BG_YELLOW;
            case WILD -> BG_WILD;
        };

        return bg + BOLD + " " + card.color() + " " + label + " " + RESET;
    }

    /**
     * Formats the active color banner.
     */
    public String renderColorBanner(CardColor color) {
        if (color == null) {
            return "-";
        }
        if (!ansiEnabled) {
            return color.name();
        }

        String fg = switch (color) {
            case RED -> FG_RED;
            case GREEN -> FG_GREEN;
            case BLUE -> FG_BLUE;
            case YELLOW -> FG_YELLOW;
            case WILD -> FG_WILD;
        };

        return fg + BOLD + color.name() + RESET;
    }

    /**
     * Formats the player's hand with 1-based indices for quick play selection.
     */
    public String renderHand(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return "Twoja ręka jest pusta.";
        }

        String bold = ansiEnabled ? BOLD : "";
        String reset = ansiEnabled ? RESET : "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            sb.append(bold).append("[").append(i + 1).append("] ").append(reset);
            sb.append(renderCard(cards.get(i)));
            sb.append("   ");
            if ((i + 1) % 4 == 0 && i < cards.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Formats the game table view: top card, active color, opponents, and turn status.
     */
    public String renderTable(
            Card topCard,
            CardColor activeColor,
            String activePlayer,
            String myUsername,
            Map<String, Integer> opponentCardCounts) {

        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("  STÓŁ ULTIMO\n");
        sb.append("------------------------------------------------------------\n");

        sb.append("  Karta na wierzchu: ").append(renderCard(topCard)).append("\n");
        sb.append("  Aktywny kolor:     ").append(renderColorBanner(activeColor)).append("\n");

        if (opponentCardCounts != null && !opponentCardCounts.isEmpty()) {
            sb.append("  Przeciwnicy:       ");
            opponentCardCounts.forEach((name, count) -> {
                if (!name.equalsIgnoreCase(myUsername)) {
                    sb.append(name).append(" (").append(count).append(" kart)  ");
                }
            });
            sb.append("\n");
        }

        sb.append("------------------------------------------------------------\n");
        boolean isMyTurn = myUsername != null && myUsername.equalsIgnoreCase(activePlayer);
        if (isMyTurn) {
            if (ansiEnabled) {
                sb.append("  ").append(FG_GREEN).append(BOLD).append(">>> TWOJA TURA! Wybierz numer karty [1-N] lub wpisz 'd' (dociągnij) <<<").append(RESET).append("\n");
            } else {
                sb.append("  >>> TWOJA TURA! Wybierz numer karty [1-N] lub wpisz 'd' (dociągnij) <<<\n");
            }
        } else {
            sb.append("  Aktualna tura:     ").append(activePlayer != null ? activePlayer : "-").append(" (czekaj na swój ruch)\n");
        }
        sb.append("============================================================\n");

        return sb.toString();
    }

    public boolean isAnsiEnabled() {
        return ansiEnabled;
    }
}
