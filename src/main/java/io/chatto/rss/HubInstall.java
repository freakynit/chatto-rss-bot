package io.chatto.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * One Bot Hub install receipt, keyed by the Chatto server it belongs to.
 * Field names match the hub callback JSON (snake_case). The bot never
 * configures a server URL: it connects with {@code chattoServerUrl} from
 * each receipt, so a single instance can serve any number of servers.
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

    /** Normalized server URL used as the install key (no trailing slash). */
    String serverKey() {
        return normalizeServerUrl(chattoServerUrl);
    }

    static String normalizeServerUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Install receipt has no chatto_server_url");
        String normalized = url.strip();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        URI uri = URI.create(normalized);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("Install receipt has an invalid chatto_server_url: " + url);
        }
        return normalized;
    }

    /** Parses and validates a hub callback payload. */
    static HubInstall parse(JsonNode payload) {
        HubInstall install;
        try {
            install = MAPPER.treeToValue(payload, HubInstall.class);
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid hub install receipt: " + error.getMessage(), error);
        }
        return validated(install);
    }

    /** Parses and validates a hub callback JSON body. */
    static HubInstall parse(String json) {
        try {
            return parse((JsonNode) MAPPER.readTree(json));
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid hub install receipt: " + error.getMessage(), error);
        }
    }

    private static HubInstall validated(HubInstall install) {
        if (install.botUserId() == null || install.botUserId().isBlank()) {
            throw new IllegalArgumentException("Install receipt has no bot_user_id");
        }
        if (install.apiKey() == null || install.apiKey().isBlank()) {
            throw new IllegalArgumentException("Install receipt has no api_key");
        }
        normalizeServerUrl(install.chattoServerUrl());
        return install;
    }
}
