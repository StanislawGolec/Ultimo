package pl.ultimo.core.model;

/**
 * Represents the face values and special action types of an Ultimo card.
 */
public enum CardValue {
    ZERO(0),
    ONE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    SIX(6),
    SEVEN(7),
    EIGHT(8),
    NINE(9),
    SKIP(20),
    REVERSE(20),
    DRAW_TWO(20),
    WILD(50),
    WILD_DRAW_FOUR(50);

    private final int pointValue;

    CardValue(int pointValue) {
        this.pointValue = pointValue;
    }

    public int getPointValue() {
        return pointValue;
    }

    public boolean isNumber() {
        return ordinal() <= NINE.ordinal();
    }

    public boolean isAction() {
        return this == SKIP || this == REVERSE || this == DRAW_TWO;
    }

    public boolean isWild() {
        return this == WILD || this == WILD_DRAW_FOUR;
    }
}
