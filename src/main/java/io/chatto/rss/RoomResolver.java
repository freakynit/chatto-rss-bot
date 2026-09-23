package io.chatto.rss;

import io.chatto.sdk.ChattoClient;

import java.util.List;

/** Resolves a visible Chatto room by exact name, allowing a leading display #. */
final class RoomResolver {
    static String normalize(String configuredName) {
        String name = configuredName.trim();
        if (name.startsWith("#")) name = name.substring(1).trim();
        if (name.isBlank()) throw new IllegalArgumentException("chatto.room_name must not be empty");
        return name;
    }

    static String resolve(ChattoClient chatto, Config config) {
        if (!config.roomId().isBlank()) {
            chatto.rooms().fetch(config.roomId());
            return config.roomId();
        }
        List<io.chatto.sdk.resource.Room> matches = chatto.rooms().list().stream()
                .filter(room -> room.name().equals(config.roomName()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Chatto room not found or not visible: " + config.roomName());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Multiple visible Chatto rooms have name: " + config.roomName());
        }
        return matches.getFirst().id();
    }
}
