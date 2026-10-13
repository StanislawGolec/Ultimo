package pl.ultimo.core.model;

import java.util.Objects;

/**
 * Domain entity representing a participant in an Ultimo game session.
 */
public class Player {

    private final String id;
    private final String username;
    private final PlayerHand hand;

    public Player(String id, String username) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.username = Objects.requireNonNull(username, "username must not be null");
        this.hand = new PlayerHand();
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public PlayerHand getHand() {
        return hand;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Player player)) return false;
        return id.equals(player.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return username + " (id=" + id + ")";
    }
}
