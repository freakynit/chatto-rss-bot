# Chatto RSS Bot

A Java 21 bot that publishes articles from any number of RSS 2.0, RDF RSS, and Atom feeds to Chatto channels. Subscriptions and delivered item IDs live in a local SQLite database.

## Setup

1. Install Java 21 and Maven. Clone Chatto Java SDK (unofficial) https://github.com/freakynit/chatto-java-sdk and install it : `mvn install`.
2. Create a Chatto bot account and API key. In the bot's permission settings, explicitly grant:
   - Membership in `#general` and every channel where it will receive commands or publish feeds.
   - `message.read-interactions` in channels where users will mention it. This lets it read the mentioned message and its thread. `message.read` also works, but grants broader message access.
   - `message.post` in channels where it will reply or publish feeds. This allows root posts and thread replies.

   Bots do not inherit `everyone` or role permissions, and their effective access is limited by their owner's permissions. Also enable the bot's direct-mention notification policy in command channels; that is a notification setting, not a permission. Set `CHATTO_BASE_URL` and `CHATTO_TOKEN` in `.env` beside `config.yaml` or in the process environment. The token must start with `cht_BK_`.
3. Run `mvn exec:java`. Use `--config /path/to/config.yaml` for another configuration. `--once` polls current subscriptions once and exits without listening for commands.

Mention the bot in Chatto to manage feeds:

```
<@botname> add https://example.com/feed.xml
<@botname> add https://example.com/feed.xml 30 channel-1
<@botname> add https://example.com/feed.xml 30 team news channel
<@botname> add https://example.com/feed.xml channel-2
<@botname> pause https://example.com/feed.xml channel-1
<@botname> pause
<@botname> list
<@botname> remove https://example.com/feed.xml channel-1
```

`add` defaults to 15 minutes and `#general`. The interval accepts 1 to 10080 minutes. The channel name is the remaining text after the URL and optional interval, so it can contain spaces; surrounding whitespace is trimmed. If the first word after the URL is numeric, it is treated as the interval. `pause` and `remove` also take the remaining text as the channel name. Channel names are exact and case sensitive; a leading `#` is optional. A subscription is unique by feed URL and channel, so adding it again updates its interval and resumes it if paused. `pause` with no arguments pauses every subscription; `pause <feed-url> [channel name]` pauses one, defaulting to `#general`. Paused subscriptions remain in SQLite and appear as paused in `list`. Add them again to resume. `remove` defaults to `#general`. Commands are accepted from direct mentions and replies appear in the command's thread.

Feed polling starts immediately after `add`. On the first poll, the bot posts up to `max_items_per_poll` recent entries, oldest first. Each feed keeps its own interval. A failed feed or item is logged and retried later without stopping other feeds. If an article page is unavailable, the bot uses the feed summary. Keep `feeds.sqlite` across restarts to preserve subscriptions and delivery history.

## Configuration

`config.yaml` sets the Chatto URL and token, SQLite path (`feed.database_file`), item limit, article length, article fetching, and HTTP timeout. Add subscriptions and choose their channels through bot commands. Relative SQLite paths use the YAML directory. `${NAME}` requires an environment variable; `${NAME:-default}` supplies a default. Process environment values take precedence over `.env`.

Subscriptions are stored in SQLite and are created through bot commands. The old `posted-items.txt` file is not imported, so previously posted entries may be sent once when moving to SQLite.

A confirmed Chatto post is recorded immediately in SQLite. A crash or database failure between Chatto accepting the post and the record write can still cause a duplicate on retry. SQLite and Chatto outages are logged; the process keeps polling and reconnecting. Startup requires a valid config, bot token, and writable database.
