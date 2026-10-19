package pl.ultimo.core.bot;

/**
 * Authoritative action types emitted by the autonomous heuristic bot engine.
 */
public enum BotAction {
    /** Play one or more cards from hand (single card or multi-card combo). */
    PLAY,
    /** Draw a card from the deck when no playable card is available or to accept penalty. */
    DRAW,
    /** Pass turn after drawing an unplayable card. */
    PASS,
    /** Declare ULTIMO when reaching 1 card. */
    DECLARE_ULTIMO,
    /** Call out an opponent with 1 card who failed to declare ULTIMO. */
    CATCH_ULTIMO,
    /** Challenge the legality of a Wild Draw Four played by the previous opponent. */
    CHALLENGE_WILD_FOUR
}
