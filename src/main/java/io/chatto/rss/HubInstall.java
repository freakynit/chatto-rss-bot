package io.chatto.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Bot Hub install receipt saved from the hub callback POST.
 * Field names match the hub callback JSON (snake_case).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HubInstall(String botUserId, String login, String displayName, String apiKey, String apiKeyId,
                  String chattoServerUrl, List<String> requestedPermissions, List<String> grantedPermissions,
                  Map<String, String> deniedPermissions, List<String> requestedRooms,
                  List<RoomRef> addedRooms, Map<String, String> failedRooms, String installedAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomRef(String id, String name) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    HubInstall {
        if (requestedPermissions == null) requestedPermissions = List.of();
        if (grantedPermissions == null) grantedPermissions = List.of();
        if (deniedPermissions == null) deniedPermissions = Map.of();
        if (requestedRooms == null) requestedRooms = List.of();
        if (addedRooms == null) addedRooms = List.of();
        if (failedRooms == null) failedRooms = Map.of();
    }

    static Optional<HubInstall> load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            HubInstall install = MAPPER.readValue(Files.readString(file), HubInstall.class);
            if (install.apiKey() == null || install.apiKey().isBlank()) {
                throw new IOException("Install receipt has no api_key: " + file);
            }
            return Optional.of(install);
        } catch (IOException error) {
            throw new IOException("Cannot parse hub install receipt " + file + ": " + error.getMessage(), error);
        }
    }
}
