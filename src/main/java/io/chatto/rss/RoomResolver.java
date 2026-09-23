package io.chatto.rss;

/** Resolves a visible Chatto room by exact name, allowing a leading display #. */
final class RoomResolver {
    static String normalize(String channelName) {
        String name = channelName.trim();
        if (name.startsWith("#")) name = name.substring(1).trim();
        if (name.isBlank()) throw new IllegalArgumentException("Channel name must not be empty");
        return name;
    }
}
