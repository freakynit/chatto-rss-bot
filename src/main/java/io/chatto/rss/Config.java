package io.chatto.rss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validated runtime settings loaded from YAML after .env and process environment expansion. */
record Config(String baseUrl, String token, String roomId, String roomName, String threadRootEventId, String feedUrl,
              int pollMinutes, int maxItems, int maxArticleCharacters, boolean fetchArticle,
              Path stateFile, int httpTimeoutSeconds) {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    static Config load(Path path) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Map<String, String> variables = new HashMap<>(Dotenv.read(directory.resolve(".env")));
        variables.putAll(System.getenv());
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(path));
        JsonNode chatto = required(root, "chatto");
        JsonNode feed = required(root, "feed");
        String token = string(chatto, "token", variables);
        if (!token.startsWith("cht_BK_") || token.equals("cht_BK_replace_me")) {
            throw new IllegalArgumentException("chatto.token must be a bot API key (cht_BK_...)");
        }
        String url = optional(feed, "url", variables);
        if (!url.isBlank()) HttpSource.requireHttpUrl(url);
        String baseUrl = string(chatto, "base_url", variables);
        HttpSource.requireHttpUrl(baseUrl);
        int poll = positive(feed, "poll_interval_minutes", variables);
        int maxItems = positive(feed, "max_items_per_poll", variables);
        int maxChars = positive(feed, "max_article_characters", variables);
        int timeout = positive(feed, "http_timeout_seconds", variables);
        String fetchSetting = string(feed, "fetch_article", variables);
        if (!fetchSetting.equals("true") && !fetchSetting.equals("false")) {
            throw new IllegalArgumentException("fetch_article must be true or false");
        }
        if (maxItems > 1000) throw new IllegalArgumentException("max_items_per_poll must be <= 1000");
        if (maxChars > 9000) throw new IllegalArgumentException("max_article_characters must be <= 9000");
        String state = optional(feed, "database_file", variables);
        if (state.isBlank()) state = "feeds.sqlite";
        Path stateFile = Path.of(state);
        if (!stateFile.isAbsolute()) stateFile = directory.resolve(stateFile);
        String roomId = optional(chatto, "room_id", variables);
        String roomName = optional(chatto, "room_name", variables);
        if (!roomId.isBlank() && !roomName.isBlank()) throw new IllegalArgumentException("Set only one of chatto.room_name or chatto.room_id");
        if (!roomName.isBlank()) roomName = RoomResolver.normalize(roomName);
        return new Config(baseUrl, token, roomId, roomName,
                optional(chatto, "thread_root_event_id", variables), url, poll, maxItems, maxChars,
                Boolean.parseBoolean(fetchSetting), stateFile, timeout);
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode child = node == null ? null : node.get(key);
        if (child == null || child.isNull()) throw new IllegalArgumentException("Missing config: " + key);
        return child;
    }

    private static String string(JsonNode node, String key, Map<String, String> variables) {
        String value = expand(required(node, key).asText(), variables).trim();
        if (value.isEmpty() || value.startsWith("replace_with_")) throw new IllegalArgumentException("Set config: " + key);
        return value;
    }

    private static String optional(JsonNode node, String key, Map<String, String> variables) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? "" : expand(value.asText(), variables).trim();
    }

    private static String expand(String raw, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(raw);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            if (value == null || value.isEmpty()) value = matcher.group(2);
            if (value == null) throw new IllegalArgumentException("Missing environment variable: " + matcher.group(1));
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static int positive(JsonNode node, String key, Map<String, String> variables) {
        int value;
        try { value = Integer.parseInt(string(node, key, variables)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(key + " must be a positive integer", error); }
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
}
