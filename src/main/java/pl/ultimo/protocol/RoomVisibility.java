package pl.ultimo.protocol;

/**
 * Visibility scope for a game room.
 * PUBLIC: Displayed in public room listing and joinable by anyone.
 * PRIVATE: Hidden from public listing and requires access code to join.
 */
public enum RoomVisibility {
    PUBLIC,
    PRIVATE;

    public static RoomVisibility fromString(String val) {
        if (val == null) return PUBLIC;
        try {
            return valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PUBLIC;
        }
    }
}
