package io.chatto.rss;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite state for hub installs, subscriptions, delivered items, and handled commands.
 * Every row is scoped by normalized Chatto server URL so one bot instance can
 * serve installs on any number of servers.
 */
final class FeedDatabase {
    private static final Logger LOG = Logger.getLogger(FeedDatabase.class.getName());

    record Feed(String serverUrl, String url, int minutes, String roomId, String roomName, long nextPoll, boolean paused) {}
    private final String jdbcUrl;

    FeedDatabase(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath();
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS installations(server_url TEXT PRIMARY KEY, bot_user_id TEXT NOT NULL DEFAULT '', login TEXT NOT NULL DEFAULT '', api_key TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL DEFAULT 0)");
            ensureScopedTable(statement, "feeds",
                    "server_url TEXT NOT NULL DEFAULT '', url TEXT NOT NULL, room_id TEXT NOT NULL, room_name TEXT NOT NULL, minutes INTEGER NOT NULL, next_poll INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0",
                    "server_url,url,room_id");
            ensureScopedTable(statement, "posted",
                    "server_url TEXT NOT NULL DEFAULT '', url TEXT NOT NULL, room_id TEXT NOT NULL, item_id TEXT NOT NULL",
                    "server_url,url,room_id,item_id");
            ensureScopedTable(statement, "commands",
                    "server_url TEXT NOT NULL DEFAULT '', event_id TEXT NOT NULL",
                    "server_url,event_id");
        }
    }

    /**
     * Creates a server-scoped table, replacing any pre-scoping single-server
     * table left over from testing (its rows are not preserved).
     */
    private static void ensureScopedTable(java.sql.Statement statement, String table,
                                          String scopedColumns, String scopedPk) throws SQLException {
        boolean exists;
        try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            exists = rows.next();
        }
        if (!exists) {
            statement.execute("CREATE TABLE " + table + "(" + scopedColumns + ", PRIMARY KEY(" + scopedPk + "))");
            return;
        }
        boolean hasServer = false;
        try (var columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) if ("server_url".equals(columns.getString("name"))) hasServer = true;
        }
        if (hasServer) return;
        statement.execute("DROP TABLE " + table);
        statement.execute("CREATE TABLE " + table + "(" + scopedColumns + ", PRIMARY KEY(" + scopedPk + "))");
    }

    private Connection open() throws SQLException {
        var connection = DriverManager.getConnection(jdbcUrl);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    synchronized void upsertInstall(HubInstall install, String rawPayload) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement(
                "INSERT INTO installations(server_url,bot_user_id,login,api_key,payload,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(server_url) DO UPDATE SET bot_user_id=excluded.bot_user_id,login=excluded.login,api_key=excluded.api_key,payload=excluded.payload,updated_at=excluded.updated_at")) {
            statement.setString(1, install.serverKey());
            statement.setString(2, install.botUserId());
            statement.setString(3, install.login() == null ? "" : install.login());
            statement.setString(4, install.apiKey());
            statement.setString(5, rawPayload);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    synchronized List<HubInstall> installs() throws SQLException {
        var result = new ArrayList<HubInstall>();
        try (var connection = open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT payload FROM installations ORDER BY server_url")) {
            while (rows.next()) {
                try {
                    result.add(HubInstall.parse(rows.getString(1)));
                } catch (IllegalArgumentException error) {
                    LOG.log(Level.WARNING, "Skipping corrupt hub install receipt in SQLite", error);
                }
            }
        }
        return result;
    }

    synchronized void add(String serverUrl, String url, int minutes, String roomId, String roomName) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement(
                "INSERT INTO feeds(server_url,url,room_id,room_name,minutes,next_poll,paused) VALUES(?,?,?,?,?,0,0) ON CONFLICT(server_url,url,room_id) DO UPDATE SET minutes=excluded.minutes,room_name=excluded.room_name,next_poll=0,paused=0")) {
            statement.setString(1, serverUrl); statement.setString(2, url); statement.setString(3, roomId); statement.setString(4, roomName); statement.setInt(5, minutes);
            statement.executeUpdate();
        }
    }

    synchronized int remove(String serverUrl, String url, String roomId) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("DELETE FROM feeds WHERE server_url=? AND url=? AND room_id=?")) {
            statement.setString(1, serverUrl); statement.setString(2, url); statement.setString(3, roomId);
            return statement.executeUpdate();
        }
    }

    synchronized int pause(String serverUrl, String url, String roomId) throws SQLException {
        String sql = url == null ? "UPDATE feeds SET paused=1 WHERE server_url=? AND paused=0" :
                "UPDATE feeds SET paused=1 WHERE server_url=? AND url=? AND room_id=? AND paused=0";
        try (var connection = open(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, serverUrl);
            if (url != null) { statement.setString(2, url); statement.setString(3, roomId); }
            return statement.executeUpdate();
        }
    }

    synchronized boolean paused(Feed feed) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT paused FROM feeds WHERE server_url=? AND url=? AND room_id=?")) {
            statement.setString(1, feed.serverUrl()); statement.setString(2, feed.url()); statement.setString(3, feed.roomId());
            try (var rows = statement.executeQuery()) { return !rows.next() || rows.getInt(1) != 0; }
        }
    }

    synchronized List<Feed> feeds() throws SQLException {
        var result = new ArrayList<Feed>();
        try (var connection = open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT server_url,url,minutes,room_id,room_name,next_poll,paused FROM feeds ORDER BY server_url,room_name,url")) {
            while (rows.next()) result.add(new Feed(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4), rows.getString(5), rows.getLong(6), rows.getInt(7) != 0));
        }
        return result;
    }

    synchronized boolean posted(Feed feed, String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT 1 FROM posted WHERE server_url=? AND url=? AND room_id=? AND item_id=?")) {
            statement.setString(1, feed.serverUrl()); statement.setString(2, feed.url()); statement.setString(3, feed.roomId()); statement.setString(4, id);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    synchronized void markPosted(Feed feed, String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("INSERT OR IGNORE INTO posted(server_url,url,room_id,item_id) VALUES(?,?,?,?)")) {
            statement.setString(1, feed.serverUrl()); statement.setString(2, feed.url()); statement.setString(3, feed.roomId()); statement.setString(4, id);
            statement.executeUpdate();
        }
    }

    synchronized void nextPoll(Feed feed, long time) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("UPDATE feeds SET next_poll=? WHERE server_url=? AND url=? AND room_id=?")) {
            statement.setLong(1, time); statement.setString(2, feed.serverUrl()); statement.setString(3, feed.url()); statement.setString(4, feed.roomId());
            statement.executeUpdate();
        }
    }

    synchronized boolean commandDone(String serverUrl, String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT 1 FROM commands WHERE server_url=? AND event_id=?")) {
            statement.setString(1, serverUrl); statement.setString(2, id);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    synchronized void markCommand(String serverUrl, String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("INSERT OR IGNORE INTO commands(server_url,event_id) VALUES(?,?)")) {
            statement.setString(1, serverUrl); statement.setString(2, id); statement.executeUpdate();
        }
    }
}
