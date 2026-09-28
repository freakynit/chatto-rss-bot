package io.chatto.rss;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FeedDatabaseTest {
    private static final String RECEIPT_A = """
            {"bot_user_id":"UA","login":"rss_hub_bot","display_name":"RSS Hub Bot",
             "api_key":"cht_BK_UA.secret","api_key_id":"legacy",
             "chatto_server_url":"https://a.example.com/",
             "requested_permissions":[],"granted_permissions":[],
             "denied_permissions":{},"requested_rooms":[],
             "added_rooms":[],"failed_rooms":{},"installed_at":"2026-01-01T00:00:00Z"}
            """;
    private static final String RECEIPT_B = """
            {"bot_user_id":"UB","login":"rss_hub_bot","display_name":"RSS Hub Bot",
             "api_key":"cht_BK_UB.secret","api_key_id":"legacy",
             "chatto_server_url":"https://b.example.com",
             "requested_permissions":[],"granted_permissions":[],
             "denied_permissions":{},"requested_rooms":[],
             "added_rooms":[],"failed_rooms":{},"installed_at":"2026-01-01T00:00:00Z"}
            """;

    @Test
    void installsRoundTripKeyedByServer() throws Exception {
        Path db = Files.createTempDirectory("rss").resolve("feeds.sqlite");
        var database = new FeedDatabase(db);
        assertThat(database.installs()).isEmpty();
        database.upsertInstall(HubInstall.parse(RECEIPT_A), RECEIPT_A);
        database.upsertInstall(HubInstall.parse(RECEIPT_B), RECEIPT_B);
        var installs = database.installs();
        assertThat(installs).hasSize(2);
        assertThat(installs.stream().map(HubInstall::serverKey).toList())
                .containsExactly("https://a.example.com", "https://b.example.com");
        // Key rotation replaces the row instead of adding one.
        database.upsertInstall(HubInstall.parse(RECEIPT_A.replace("cht_BK_UA.secret", "cht_BK_UA.rotated")), RECEIPT_A);
        assertThat(database.installs()).hasSize(2);
    }

    @Test
    void feedsAndCommandsAreScopedByServer() throws Exception {
        Path db = Files.createTempDirectory("rss").resolve("feeds.sqlite");
        var database = new FeedDatabase(db);
        database.add("https://a.example.com", "https://feed.example.com/rss", 15, "R1", "general");
        database.add("https://b.example.com", "https://feed.example.com/rss", 15, "R1", "general");
        assertThat(database.feeds()).hasSize(2);
        // Pausing one server leaves the other untouched.
        assertThat(database.pause("https://a.example.com", null, null)).isEqualTo(1);
        assertThat(database.feeds().stream().filter(f -> f.serverUrl().equals("https://b.example.com")).findFirst().orElseThrow().paused()).isFalse();
        // Commands on one server do not mark the other as done.
        database.markCommand("https://a.example.com", "E1");
        assertThat(database.commandDone("https://a.example.com", "E1")).isTrue();
        assertThat(database.commandDone("https://b.example.com", "E1")).isFalse();
    }

    @Test
    void replacesUnscopedTestingTables() throws Exception {
        Path dir = Files.createTempDirectory("rss");
        Path db = dir.resolve("feeds.sqlite");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE feeds(url TEXT NOT NULL, room_id TEXT NOT NULL, room_name TEXT NOT NULL, minutes INTEGER NOT NULL, next_poll INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(url, room_id))");
            statement.execute("INSERT INTO feeds(url,room_id,room_name,minutes) VALUES('https://feed.example.com/rss','R1','general',15)");
            statement.execute("CREATE TABLE posted(url TEXT NOT NULL, room_id TEXT NOT NULL, item_id TEXT NOT NULL, PRIMARY KEY(url, room_id, item_id))");
            statement.execute("CREATE TABLE commands(event_id TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO commands(event_id) VALUES('E1')");
        }
        var database = new FeedDatabase(db);
        var feeds = database.feeds();
        assertThat(feeds).isEmpty();
        database.add("https://a.example.com", "https://feed.example.com/rss", 15, "R1", "general");
        assertThat(database.feeds()).hasSize(1);
    }
}
