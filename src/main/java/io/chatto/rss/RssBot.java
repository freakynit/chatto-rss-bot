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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Polls independent subscriptions and accepts direct-mention management commands. */
public final class RssBot {
    private static final Logger LOG = Logger.getLogger(RssBot.class.getName());
    private final Config config;
    private final HttpSource http;
    private final ChattoClient chatto;
    private final FeedDatabase database;
    private final String botId;
    private final String botLogin;
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();

    private RssBot(Config config, ChattoClient chatto, FeedDatabase database, String botId, String botLogin) {
        this.config = config;
        this.http = new HttpSource(config.httpTimeoutSeconds());
        this.chatto = chatto;
        this.database = database;
        this.botId = botId;
        this.botLogin = botLogin;
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
        var chatto = new ChattoClient(new ChattoClientOptions(config.baseUrl(), config.token()));
        io.chatto.sdk.model.UserData profile;
        while (true) {
            try { profile = chatto.viewer().fetch().user().profile(); break; }
            catch (Exception error) {
                if (once) throw error;
                LOG.log(Level.WARNING, "Chatto unavailable at startup; retrying in 30 seconds", error);
                Thread.sleep(30_000);
            }
        }
        if (!profile.isBot()) throw new IllegalStateException("Chatto token does not belong to a bot account");
        var database = new FeedDatabase(config.stateFile());
        var bot = new RssBot(config, chatto, database, profile.id(), profile.login());
        if (once) { bot.poll(); return; }
        chatto.onNotificationOccurrencesReplace(event -> {
            for (var occurrence : event.occurrences()) bot.handleSafely(occurrence);
        });
        chatto.onReady(() -> bot.connected.set(true));
        chatto.onDisconnect(() -> bot.connected.set(false));
        chatto.onError(error -> LOG.log(Level.WARNING, "Chatto connection error", error));
        bot.connect();
        LOG.info("RSS bot started");
        long nextConnectionAttempt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                bot.poll();
                long now = System.currentTimeMillis();
                if (!bot.connected.get() && !bot.connecting.get() && now >= nextConnectionAttempt) {
                    bot.connect();
                    nextConnectionAttempt = now + 30_000;
                }
            } catch (Exception error) { LOG.log(Level.WARNING, "RSS polling cycle failed", error); }
            try { Thread.sleep(5_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        chatto.disconnect();
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        try {
            chatto.connect().whenComplete((ignored, error) -> {
                connecting.set(false);
                if (error != null) LOG.log(Level.WARNING, "Chatto connection failed; will retry", error);
            });
        } catch (Exception error) {
            connecting.set(false);
            LOG.log(Level.WARNING, "Chatto connection failed; will retry", error);
        }
    }

    private void handleSafely(NotificationOccurrenceData occurrence) {
        if (!"DIRECT_MENTION_RECEIVED".equals(occurrence.cause()) || occurrence.message() == null) return;
        try { handle(occurrence); }
        catch (Exception error) { LOG.log(Level.WARNING, "Could not handle bot command " + occurrence.id(), error); }
    }

    private void handle(NotificationOccurrenceData occurrence) throws Exception {
        var ref = occurrence.message();
        if (database.commandDone(ref.eventId())) return;
        Message message = chatto.messages().fetch(ref.roomId(), ref.eventId());
        if (message.author() != null && botId.equals(message.author().id())) {
            database.markCommand(ref.eventId());
            return;
        }
        String command = stripMention(message.content());
        if (command == null) return;
        String answer;
        try { answer = execute(command); }
        catch (IllegalArgumentException error) { answer = error.getMessage() + "\nUsage: @" + botLogin + " add <feed-url> [minutes] [channel name]"; }
        // Persist the command before replying so a repeated notification cannot modify subscriptions again.
        database.markCommand(ref.eventId());
        try { message.reply(answer); }
        catch (Exception error) { LOG.log(Level.WARNING, "Command completed, but reply failed", error); }
    }

    private String stripMention(String body) {
        if (body == null) return null;
        String text = body.strip();
        for (String prefix : List.of("<@" + botId + ">", "<@" + botLogin + ">", "@" + botLogin)) {
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) return text.substring(prefix.length()).strip();
        }
        return null;
    }

    private String execute(String text) throws Exception {
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
                String roomId = resolveRoom(roomName);
                database.add(url, minutes, roomId, roomName);
                return "Added " + url + " to #" + roomName + " every " + minutes + " minutes.";
            }
            case "remove" -> {
                if (parts.length < 2) throw new IllegalArgumentException("Invalid remove command.");
                String url = HttpSource.requireHttpUrl(parts[1]).toString();
                String roomName = RoomResolver.normalize(parts.length >= 3 ? parts[2] + (parts.length == 4 ? " " + parts[3] : "") : "general");
                String roomId = resolveRoom(roomName);
                return database.remove(url, roomId) > 0 ? "Removed " + url + " from #" + roomName + "." : "No matching subscription in #" + roomName + ".";
            }
            case "pause" -> {
                if (parts.length == 1) {
                    int count = database.pause(null, null);
                    return count == 0 ? "All subscriptions are already paused, or none exist." : "Paused " + count + " subscription(s). Add a feed again to resume it.";
                }
                if (parts.length > 4) throw new IllegalArgumentException("Invalid pause command.");
                String url = HttpSource.requireHttpUrl(parts[1]).toString();
                String roomName = RoomResolver.normalize(parts.length >= 3 ? parts[2] + (parts.length == 4 ? " " + parts[3] : "") : "general");
                String roomId = resolveRoom(roomName);
                return database.pause(url, roomId) > 0 ? "Paused " + url + " in #" + roomName + ". Add it again to resume." :
                        "No active matching subscription in #" + roomName + ".";
            }
            case "list" -> {
                if (parts.length != 1) throw new IllegalArgumentException("Invalid list command.");
                var feeds = database.feeds();
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

    private String resolveRoom(String name) {
        var rooms = chatto.rooms().list().stream().filter(room -> room.name().equals(name)).toList();
        if (rooms.size() != 1) throw new IllegalArgumentException("Expected exactly one visible channel named #" + name);
        return rooms.getFirst().id();
    }

    /** Polls only subscriptions whose interval has elapsed. */
    void poll() throws Exception {
        for (var feed : database.feeds()) {
            if (feed.paused() || database.paused(feed)) continue;
            if (feed.nextPoll() > System.currentTimeMillis()) continue;
            try {
                poll(feed);
                database.nextPoll(feed, System.currentTimeMillis() + Duration.ofMinutes(feed.minutes()).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                LOG.log(Level.WARNING, "Feed poll failed: " + feed.url(), error);
                database.nextPoll(feed, System.currentTimeMillis() + Duration.ofMinutes(Math.min(feed.minutes(), 5)).toMillis());
            }
        }
    }

    private void poll(FeedDatabase.Feed feed) throws Exception {
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
                    chatto.messages().send(feed.roomId(), MessageBuilder.content(body));
                    try { database.markPosted(feed, item.id()); }
                    catch (SQLException error) { throw new IllegalStateException("Published item could not be saved", error); }
                }
            } catch (InterruptedException interrupted) { throw interrupted; }
            catch (IllegalStateException error) { throw error; }
            catch (Exception error) { LOG.log(Level.WARNING, "Could not publish item from " + feed.url(), error); }
        }
    }
}
