package pl.ultimo.core.model;

/**
 * Represents the four standard Ultimo colors plus a special WILD color for wild cards.
 */
public enum CardColor {
    RED,
    YELLOW,
    GREEN,
    BLUE,
    WILD;

    /**
     * Checks if this color is a playable suit (i.e. not WILD).
     */
    public boolean isStandardColor() {
        return this != WILD;
    }
}
