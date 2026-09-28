package io.chatto.rss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validated runtime settings loaded from YAML after process-environment expansion.
 * There is no `.env` support: all `${NAME}`/`${NAME:-default}` placeholders resolve
 * from the process environment only. The Chatto token is never configured here;
 * it arrives via the Bot Hub install callback and is read from {@code hub.install_file}.
 */
record Config(String baseUrl, Path installFile, String hubBaseUrl,
              String botLogin, String botDisplayName, String botDescription, String callbackPublicUrl,
              List<String> requestedPermissions, List<String> requestedRooms,
              int webhookPort, String webhookPath,
              int maxItems, int maxArticleCharacters, boolean fetchArticle,
              Path stateFile, int httpTimeoutSeconds) {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    static Config load(Path path) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Map<String, String> variables = System.getenv();
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(path));
        JsonNode chatto = required(root, "chatto");
        JsonNode hub = required(root, "hub");
        JsonNode webhook = required(root, "webhook");
        JsonNode feed = required(root, "feed");

        String baseUrl = string(chatto, "base_url", variables);
        HttpSource.requireHttpUrl(baseUrl);

        String installRaw = string(hub, "install_file", variables);
        Path installFile = Path.of(installRaw);
        if (!installFile.isAbsolute()) installFile = directory.resolve(installFile);

        String hubBaseUrl = string(hub, "base_url", variables);
        HttpSource.requireHttpUrl(hubBaseUrl);

        String botLogin = string(hub, "bot_login", variables).toLowerCase();
        if (!botLogin.matches("[a-z0-9_]{2,32}") || !botLogin.endsWith("_bot")) {
            throw new IllegalArgumentException("hub.bot_login must be 2-32 chars [a-z0-9_] and end in _bot");
        }
        String botDisplayName = string(hub, "bot_display_name", variables);
        if (botDisplayName.length() > 32) throw new IllegalArgumentException("hub.bot_display_name must be <= 32 chars");
        String botDescription = optional(hub, "bot_description", variables);
        String callbackPublicUrl = string(hub, "callback_public_url", variables);
        HttpSource.requireHttpUrl(callbackPublicUrl);
        List<String> requestedPermissions = stringList(hub, "requested_permissions", variables);
        if (requestedPermissions.isEmpty()) throw new IllegalArgumentException("hub.requested_permissions must not be empty");
        List<String> requestedRooms = stringList(hub, "requested_rooms", variables);

        int webhookPort = positive(webhook, "port", variables);
        if (webhookPort > 65535) throw new IllegalArgumentException("webhook.port must be <= 65535");
        String webhookPath = string(webhook, "path", variables);
        if (!webhookPath.startsWith("/")) throw new IllegalArgumentException("webhook.path must start with /");

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
        return new Config(baseUrl, installFile, hubBaseUrl, botLogin, botDisplayName, botDescription,
                callbackPublicUrl, requestedPermissions, requestedRooms, webhookPort, webhookPath,
                maxItems, maxChars, Boolean.parseBoolean(fetchSetting), stateFile, timeout);
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

    private static List<String> stringList(JsonNode node, String key, Map<String, String> variables) {
        JsonNode child = required(node, key);
        if (!child.isArray()) throw new IllegalArgumentException("Config " + key + " must be a list");
        List<String> values = new ArrayList<>();
        for (JsonNode item : child) {
            String value = expand(item.asText(), variables).trim();
            if (!value.isEmpty()) values.add(value);
        }
        return List.copyOf(values);
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
