package io.chatto.rss;

import io.chatto.sdk.ChattoClient;
import io.chatto.sdk.ChattoClientOptions;
import io.chatto.sdk.builder.MessageBuilder;
import io.chatto.sdk.model.NotificationOccurrenceData;
import io.chatto.sdk.resource.Message;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-instance bot serving any number of Chatto servers. Each hub install
 * receipt (SQLite, keyed by server URL) gets its own {@link ChattoClient};
 * incoming commands are answered on the server they arrived from and feed
 * subscriptions are scoped per server.
 */
public final class RssBot {
    private static final Logger LOG = Logger.getLogger(RssBot.class.getName());

    /** Live connection for one installed server. */
    private static final class ServerSession {
        final HubInstall install;
        final String serverUrl;
        final ChattoClient chatto;
        final String botId;
        final String botLogin;
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicBoolean connecting = new AtomicBoolean();
        volatile long nextConnectionAttempt;

        ServerSession(HubInstall install, ChattoClient chatto, String botId, String botLogin) {
            this.install = install;
            this.serverUrl = install.serverKey();
            this.chatto = chatto;
            this.botId = botId;
            this.botLogin = botLogin;
        }
    }

    private final Config config;
    private final FeedDatabase database;
    private final HttpSource http;
    private final Map<String, ServerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> connectBackoff = new ConcurrentHashMap<>();

    private RssBot(Config config, FeedDatabase database) {
        this.config = config;
        this.database = database;
        this.http = new HttpSource(config.httpTimeoutSeconds());
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception error) { LOG.log(Level.SEVERE, "RSS bot could not start", error); }
    }

    private static void run(String[] args) throws Exception {
        boolean once = false;
        Path configPath = Path.of("config.yaml");
        for (int i = 0; i < args.length; i++) {
            if ("--once".equals(args[i])) once = true;
            else if ("--config".equals(args[i]) && i + 1 < args.length) configPath = Path.of(args[++i]);
            else throw new IllegalArgumentException("Usage: RssBot [--config path] [--once]");
        }
        Config config = Config.load(configPath);
        FeedDatabase database = new FeedDatabase(config.stateFile());
        var bot = new RssBot(config, database);
        HubCallbackServer.start(config.webhookPort(), config.webhookPath(), database, bot::onInstallReceived);
        bot.syncInstalls(once);
        bot.waitForInstall(once);
        if (once) { bot.poll(); return; }
        LOG.info("RSS bot started on " + bot.sessions.size() + " server(s)");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                bot.syncInstalls(false);
                bot.poll();
                long now = System.currentTimeMillis();
                for (ServerSession session : bot.sessions.values()) {
                    if (!session.connected.get() && !session.connecting.get() && now >= session.nextConnectionAttempt) {
                        bot.connect(session);
                        session.nextConnectionAttempt = now + 30_000;
                    }
                }
            } catch (Exception error) { LOG.log(Level.WARNING, "RSS polling cycle failed", error); }
            try { Thread.sleep(5_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        for (ServerSession session : bot.sessions.values()) {
            try { session.chatto.disconnect(); } catch (Exception ignored) { }
        }
    }

    /**
     * Loads the hub installs, waiting for the dashboard install when none
     * exist yet. {@code --once} fails fast instead of waiting.
     */
    private void waitForInstall(boolean once) throws Exception {
        if (!database.installs().isEmpty()) return;
        String howto = "Register login " + config.botLogin() + " in the hub dashboard (" + config.hubBaseUrl()
                + "/dashboard) with callback " + config.callbackPublicUrl() + " and install it.";
        if (once) throw new IllegalStateException("No hub installs in SQLite. " + howto);
        LOG.warning("No hub installs yet. " + howto + " Waiting for the callback...");
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(5_000);
            syncInstalls(false);
            if (!database.installs().isEmpty()) {
                LOG.info("Hub install receipt arrived.");
                return;
            }
        }
        throw new InterruptedException("Interrupted while waiting for hub install");
    }

    /** Applies a freshly received hub callback: connects new servers, rotates changed keys. */
    private synchronized void onInstallReceived(HubInstall install) {
        try {
            var current = sessions.get(install.serverKey());
            if (current != null && current.install.apiKey().equals(install.apiKey())) {
                LOG.info("Hub reinstall received for " + install.login() + " on " + install.serverKey() + "; key unchanged.");
                return;
            }
            if (current != null) {
                try { current.chatto.disconnect(); } catch (Exception ignored) { }
                sessions.remove(install.serverKey());
            }
            connectSession(install, false);
        } catch (Exception error) {
            LOG.log(Level.WARNING, "Could not apply hub install for " + install.serverKey(), error);
        }
    }

    /** Connects installs missing from memory and rotates sessions whose key changed. */
    private synchronized void syncInstalls(boolean once) throws Exception {
        for (HubInstall install : database.installs()) {
            var current = sessions.get(install.serverKey());
            if (current == null) {
                long notBefore = connectBackoff.getOrDefault(install.serverKey(), 0L);
                if (!once && System.currentTimeMillis() < notBefore) continue;
                connectSession(install, once);
            } else if (!current.install.apiKey().equals(install.apiKey())) {
                LOG.info("API key changed for " + install.serverKey() + "; reconnecting.");
                try { current.chatto.disconnect(); } catch (Exception ignored) { }
                sessions.remove(install.serverKey());
                connectSession(install, once);
            }
        }
    }

    private void connectSession(HubInstall install, boolean once) throws Exception {
        if (!install.apiKey().startsWith("cht_BK_")) {
            throw new IllegalStateException("Hub receipt api_key for " + install.serverKey() + " is not a bot API key (cht_BK_...)");
        }
        if (!install.login().equals(config.botLogin())) {
            LOG.warning("Receipt login " + install.login() + " differs from config hub.bot_login "
                    + config.botLogin() + "; using the receipt.");
        }
        for (var denied : install.deniedPermissions().entrySet()) {
            LOG.warning("Hub denied permission " + denied.getKey() + " on " + install.serverKey() + ": " + denied.getValue());
        }
        for (var failed : install.failedRooms().entrySet()) {
            LOG.warning("Hub could not add room " + failed.getKey() + " on " + install.serverKey() + ": " + failed.getValue());
        }
        LOG.info("Hub install on " + install.serverKey() + ": granted=" + install.grantedPermissions() + " rooms="
                + install.addedRooms().stream().map(HubInstall.RoomRef::name).toList());
        var chatto = new ChattoClient(new ChattoClientOptions(install.serverKey(), install.apiKey()));
        io.chatto.sdk.model.UserData profile;
        try {
            profile = chatto.viewer().fetch().user().profile();
        } catch (Exception error) {
            if (once) throw error;
            LOG.log(Level.WARNING, "Chatto unavailable at " + install.serverKey() + "; will retry", error);
            connectBackoff.put(install.serverKey(), System.currentTimeMillis() + 30_000);
            try { chatto.disconnect(); } catch (Exception ignored) { }
            return;
        }
        if (!profile.isBot()) throw new IllegalStateException("Chatto token for " + install.serverKey() + " does not belong to a bot account");
        var session = new ServerSession(install, chatto, profile.id(), profile.login());
        chatto.onNotificationOccurrencesReplace(event -> {
            for (var occurrence : event.occurrences()) handleSafely(session, occurrence);
        });
        chatto.onReady(() -> session.connected.set(true));
        chatto.onDisconnect(() -> session.connected.set(false));
        chatto.onError(error -> LOG.log(Level.WARNING, "Chatto connection error on " + session.serverUrl, error));
        sessions.put(session.serverUrl, session);
        connectBackoff.remove(session.serverUrl);
        connect(session);
        LOG.info("Connected to " + session.serverUrl + " as " + session.botLogin);
    }

    private void connect(ServerSession session) {
        if (!session.connecting.compareAndSet(false, true)) return;
        try {
            session.chatto.connect().whenComplete((ignored, error) -> {
                session.connecting.set(false);
                if (error != null) LOG.log(Level.WARNING, "Chatto connection failed on " + session.serverUrl + "; will retry", error);
            });
        } catch (Exception error) {
            session.connecting.set(false);
            LOG.log(Level.WARNING, "Chatto connection failed on " + session.serverUrl + "; will retry", error);
        }
    }

    private void handleSafely(ServerSession session, NotificationOccurrenceData occurrence) {
        if (!"DIRECT_MENTION_RECEIVED".equals(occurrence.cause()) || occurrence.message() == null) return;
        try { handle(session, occurrence); }
        catch (Exception error) { LOG.log(Level.WARNING, "Could not handle bot command " + occurrence.id() + " on " + session.serverUrl, error); }
    }

    private void handle(ServerSession session, NotificationOccurrenceData occurrence) throws Exception {
        var ref = occurrence.message();
        if (database.commandDone(session.serverUrl, ref.eventId())) return;
        Message message = session.chatto.messages().fetch(ref.roomId(), ref.eventId());
        if (message.author() != null && session.botId.equals(message.author().id())) {
            database.markCommand(session.serverUrl, ref.eventId());
            return;
        }
        String command = stripMention(session, message.content());
        if (command == null) return;
        String answer;
        try { answer = execute(session, command); }
        catch (IllegalArgumentException error) { answer = error.getMessage() + "\nUsage: @" + session.botLogin + " add <feed-url> [minutes] [channel name]"; }
        // Persist the command before replying so a repeated notification cannot modify subscriptions again.
        database.markCommand(session.serverUrl, ref.eventId());
        try { message.reply(answer); }
        catch (Exception error) { LOG.log(Level.WARNING, "Command completed, but reply failed on " + session.serverUrl, error); }
    }

    private String stripMention(ServerSession session, String body) {
        if (body == null) return null;
        String text = body.strip();
        for (String prefix : List.of("<@" + session.botId + ">", "<@" + session.botLogin + ">", "@" + session.botLogin)) {
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) return text.substring(prefix.length()).strip();
        }
        return null;
    }

    private String execute(ServerSession session, String text) throws Exception {
        String[] parts = text.strip().split("\\s+", 4);
        if (parts.length == 0 || parts[0].isBlank() || "help".equalsIgnoreCase(parts[0]))
            return "Commands: add <feed-url> [minutes] [channel name], pause [feed-url] [channel name], remove <feed-url> [channel name], list";
        switch (parts[0].toLowerCase()) {
            case "add" -> {
                if (parts.length < 2) throw new IllegalArgumentException("Invalid add command.");
                String url = HttpSource.requireHttpUrl(parts[1]).toString();
                int minutes = 15;
                String roomName = "general";
                if (parts.length >= 3) {
                    if (parts[2].matches("\\d+")) {
                        try { minutes = Integer.parseInt(parts[2]); }
                        catch (NumberFormatException error) { throw new IllegalArgumentException("Interval is too large."); }
                        if (parts.length == 4) roomName = parts[3];
                    } else {
                        roomName = parts[2] + (parts.length == 4 ? " " + parts[3] : "");
                    }
                }
                if (minutes < 1 || minutes > 10_080) throw new IllegalArgumentException("Interval must be 1 to 10080 minutes.");
                roomName = RoomResolver.normalize(roomName);
                String roomId = resolveRoom(session, roomName);
                database.add(session.serverUrl, url, minutes, roomId, roomName);
                return "Added " + url + " to #" + roomName + " every " + minutes + " minutes.";
            }
            case "remove" -> {
                if (parts.length < 2) throw new IllegalArgumentException("Invalid remove command.");
                String url = HttpSource.requireHttpUrl(parts[1]).toString();
                String roomName = RoomResolver.normalize(parts.length >= 3 ? parts[2] + (parts.length == 4 ? " " + parts[3] : "") : "general");
                String roomId = resolveRoom(session, roomName);
                return database.remove(session.serverUrl, url, roomId) > 0 ? "Removed " + url + " from #" + roomName + "." : "No matching subscription in #" + roomName + ".";
            }
            case "pause" -> {
                if (parts.length == 1) {
                    int count = database.pause(session.serverUrl, null, null);
                    return count == 0 ? "All subscriptions are already paused, or none exist." : "Paused " + count + " subscription(s). Add a feed again to resume it.";
                }
                if (parts.length > 4) throw new IllegalArgumentException("Invalid pause command.");
                String url = HttpSource.requireHttpUrl(parts[1]).toString();
                String roomName = RoomResolver.normalize(parts.length >= 3 ? parts[2] + (parts.length == 4 ? " " + parts[3] : "") : "general");
                String roomId = resolveRoom(session, roomName);
                return database.pause(session.serverUrl, url, roomId) > 0 ? "Paused " + url + " in #" + roomName + ". Add it again to resume." :
                        "No active matching subscription in #" + roomName + ".";
            }
            case "list" -> {
                if (parts.length != 1) throw new IllegalArgumentException("Invalid list command.");
                var feeds = database.feeds().stream().filter(feed -> feed.serverUrl().equals(session.serverUrl)).toList();
                if (feeds.isEmpty()) return "No feed subscriptions.";
                var answer = new StringBuilder("Feed subscriptions:\n");
                for (var feed : feeds) {
                    String line = "#" + feed.roomName() + " | " + feed.minutes() + " min" + (feed.paused() ? " | paused" : "") + " | " + feed.url() + "\n";
                    if (answer.length() + line.length() > 9_000) { answer.append("…more subscriptions omitted"); break; }
                    answer.append(line);
                }
                return answer.toString().stripTrailing();
            }
            default -> throw new IllegalArgumentException("Unknown command.");
        }
    }

    private String resolveRoom(ServerSession session, String name) {
        var rooms = session.chatto.rooms().list().stream().filter(room -> room.name().equals(name)).toList();
        if (rooms.size() != 1) throw new IllegalArgumentException("Expected exactly one visible channel named #" + name);
        return rooms.getFirst().id();
    }

    /** Polls only subscriptions whose interval has elapsed, on every connected server. */
    void poll() throws Exception {
        for (var feed : database.feeds()) {
            if (feed.paused() || database.paused(feed)) continue;
            if (feed.nextPoll() > System.currentTimeMillis()) continue;
            var session = sessions.get(feed.serverUrl());
            if (session == null) continue;
            try {
                poll(session, feed);
                database.nextPoll(feed, System.currentTimeMillis() + Duration.ofMinutes(feed.minutes()).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                LOG.log(Level.WARNING, "Feed poll failed: " + feed.url() + " on " + feed.serverUrl(), error);
                database.nextPoll(feed, System.currentTimeMillis() + Duration.ofMinutes(Math.min(feed.minutes(), 5)).toMillis());
            }
        }
    }

    private void poll(ServerSession session, FeedDatabase.Feed feed) throws Exception {
        List<FeedReader.Item> entries = FeedReader.parse(http.get(feed.url()), feed.url());
        List<FeedReader.Item> selected = new ArrayList<>(entries.subList(0, Math.min(entries.size(), config.maxItems())));
        Collections.reverse(selected);
        for (FeedReader.Item item : selected) {
            if (database.paused(feed)) return;
            if (database.posted(feed, item.id())) continue;
            try {
                String article = "";
                if (config.fetchArticle()) {
                    try { article = ArticleExtractor.extract(http.get(item.link())); }
                    catch (InterruptedException interrupted) { throw interrupted; }
                    catch (Exception error) { LOG.log(Level.FINE, "Article fetch failed; using feed description", error); }
                }
                String body = PostFormatter.format(item, article, config.maxArticleCharacters());
                synchronized (database) {
                    if (database.paused(feed)) return;
                    session.chatto.messages().send(feed.roomId(), MessageBuilder.content(body));
                    try { database.markPosted(feed, item.id()); }
                    catch (SQLException error) { throw new IllegalStateException("Published item could not be saved", error); }
                }
            } catch (InterruptedException interrupted) { throw interrupted; }
            catch (IllegalStateException error) { throw error; }
            catch (Exception error) { LOG.log(Level.WARNING, "Could not publish item from " + feed.url() + " on " + feed.serverUrl(), error); }
        }
    }
}
