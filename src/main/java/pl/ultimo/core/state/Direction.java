package pl.ultimo.core.state;

/**
 * Defines the play rotation direction in an Ultimo match.
 */
public enum Direction {
    CLOCKWISE(1),
    COUNTER_CLOCKWISE(-1);

    private final int step;

    Direction(int step) {
        this.step = step;
    }

    public int getStep() {
        return step;
    }

    public Direction toggle() {
        return this == CLOCKWISE ? COUNTER_CLOCKWISE : CLOCKWISE;
    }
}
