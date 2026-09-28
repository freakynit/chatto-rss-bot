package io.chatto.rss;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {
    private static final String VALID = """
            hub:
              bot_login: rss_hub_bot
              bot_display_name: RSS Hub Bot
              bot_description: Feeds.
              callback_public_url: https://open-proxy.space/chatto/callback
              requested_permissions: [message.read, message.read-interactions, message.post-in-interactions]
              requested_rooms: [general, Standup]
            webhook:
              port: 3000
              path: /chatto/callback
            feed:
              database_file: feeds.sqlite
              max_items_per_poll: 10
              max_article_characters: 7000
              fetch_article: true
              http_timeout_seconds: 20
            """;

    @Test
    void loadsHubAndWebhookSections() throws Exception {
        Path dir = Files.createTempDirectory("rss");
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, VALID);
        Config loaded = Config.load(config);
        assertThat(loaded.botLogin()).isEqualTo("rss_hub_bot");
        assertThat(loaded.requestedPermissions())
                .containsExactly("message.read", "message.read-interactions", "message.post-in-interactions");
        assertThat(loaded.requestedRooms()).containsExactly("general", "Standup");
        assertThat(loaded.webhookPort()).isEqualTo(3000);
        assertThat(loaded.stateFile()).isEqualTo(dir.resolve("feeds.sqlite"));
    }

    @Test
    void ignoresLegacyChattoSection() throws Exception {
        Path dir = Files.createTempDirectory("rss");
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, VALID + "chatto:\n  base_url: https://chat.example.com\n");
        Config loaded = Config.load(config);
        assertThat(loaded.botLogin()).isEqualTo("rss_hub_bot");
    }

    @Test
    void rejectsBotLoginWithoutSuffix() throws Exception {
        Path dir = Files.createTempDirectory("rss");
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, VALID.replace("rss_hub_bot", "rssbot"));
        assertThatThrownBy(() -> Config.load(config)).isInstanceOf(IllegalArgumentException.class);
    }
}
