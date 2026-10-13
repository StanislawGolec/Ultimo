package pl.ultimo.core.model;

import java.util.Objects;

/**
 * Immutable representation of a single Ultimo card.
 *
 * @param color the color of the card
 * @param value the face value or action type of the card
 */
public record Card(CardColor color, CardValue value) {

    public Card {
        Objects.requireNonNull(color, "color must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (value.isWild() && color != CardColor.WILD) {
            throw new IllegalArgumentException("Wild cards must have WILD color");
        }
        if (!value.isWild() && color == CardColor.WILD) {
            throw new IllegalArgumentException("Standard cards cannot have WILD color");
        }
    }

    public boolean isWild() {
        return value.isWild();
    }

    public boolean isAction() {
        return value.isAction();
    }

    @Override
    public String toString() {
        return color + "_" + value;
    }
}
