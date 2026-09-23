package io.chatto.rss;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQLite state for subscriptions, delivered items, and handled commands. */
final class FeedDatabase {
    record Feed(String url, int minutes, String roomId, String roomName, long nextPoll, boolean paused) {}
    private final String jdbcUrl;

    FeedDatabase(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath();
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS feeds (url TEXT NOT NULL, room_id TEXT NOT NULL, room_name TEXT NOT NULL, minutes INTEGER NOT NULL, next_poll INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(url, room_id))");
            boolean hasPaused = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(feeds)")) {
                while (columns.next()) if ("paused".equals(columns.getString("name"))) hasPaused = true;
            }
            if (!hasPaused) statement.execute("ALTER TABLE feeds ADD COLUMN paused INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE TABLE IF NOT EXISTS posted (url TEXT NOT NULL, room_id TEXT NOT NULL, item_id TEXT NOT NULL, PRIMARY KEY(url, room_id, item_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS commands (event_id TEXT PRIMARY KEY)");
        }
    }

    private Connection open() throws SQLException {
        var connection = DriverManager.getConnection(jdbcUrl);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    synchronized void add(String url, int minutes, String roomId, String roomName) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement(
                "INSERT INTO feeds(url,room_id,room_name,minutes,next_poll,paused) VALUES(?,?,?,?,0,0) ON CONFLICT(url,room_id) DO UPDATE SET minutes=excluded.minutes,room_name=excluded.room_name,next_poll=0,paused=0")) {
            statement.setString(1, url); statement.setString(2, roomId); statement.setString(3, roomName); statement.setInt(4, minutes);
            statement.executeUpdate();
        }
    }

    synchronized int remove(String url, String roomId) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("DELETE FROM feeds WHERE url=? AND room_id=?")) {
            statement.setString(1, url); statement.setString(2, roomId);
            return statement.executeUpdate();
        }
    }

    synchronized int pause(String url, String roomId) throws SQLException {
        String sql = url == null ? "UPDATE feeds SET paused=1 WHERE paused=0" :
                "UPDATE feeds SET paused=1 WHERE url=? AND room_id=? AND paused=0";
        try (var connection = open(); var statement = connection.prepareStatement(sql)) {
            if (url != null) { statement.setString(1, url); statement.setString(2, roomId); }
            return statement.executeUpdate();
        }
    }

    synchronized boolean paused(Feed feed) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT paused FROM feeds WHERE url=? AND room_id=?")) {
            statement.setString(1, feed.url()); statement.setString(2, feed.roomId());
            try (var rows = statement.executeQuery()) { return !rows.next() || rows.getInt(1) != 0; }
        }
    }

    synchronized List<Feed> feeds() throws SQLException {
        var result = new ArrayList<Feed>();
        try (var connection = open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT url,minutes,room_id,room_name,next_poll,paused FROM feeds ORDER BY room_name,url")) {
            while (rows.next()) result.add(new Feed(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getInt(6) != 0));
        }
        return result;
    }

    synchronized boolean posted(Feed feed, String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT 1 FROM posted WHERE url=? AND room_id=? AND item_id=?")) {
            statement.setString(1, feed.url()); statement.setString(2, feed.roomId()); statement.setString(3, id);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    synchronized void markPosted(Feed feed, String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("INSERT OR IGNORE INTO posted(url,room_id,item_id) VALUES(?,?,?)")) {
            statement.setString(1, feed.url()); statement.setString(2, feed.roomId()); statement.setString(3, id);
            statement.executeUpdate();
        }
    }

    synchronized void nextPoll(Feed feed, long time) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("UPDATE feeds SET next_poll=? WHERE url=? AND room_id=?")) {
            statement.setLong(1, time); statement.setString(2, feed.url()); statement.setString(3, feed.roomId());
            statement.executeUpdate();
        }
    }

    synchronized boolean commandDone(String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT 1 FROM commands WHERE event_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    synchronized void markCommand(String id) throws SQLException {
        try (var connection = open(); var statement = connection.prepareStatement("INSERT OR IGNORE INTO commands(event_id) VALUES(?)")) {
            statement.setString(1, id); statement.executeUpdate();
        }
    }
}
