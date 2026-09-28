# Chatto RSS Bot

A Java 21 bot that publishes articles from any number of RSS 2.0, RDF RSS, and Atom feeds to Chatto channels. Subscriptions and delivered item IDs live in a local SQLite database.

This bot is provisioned through the **Chatto Bot Hub** (unofficial OAuth proxy).
There is no static token or server configuration: on install the hub creates the Chatto bot,
grants permissions, adds it to rooms, and POSTs the API key to this bot's webhook,
which saves it to the `installations` table in SQLite. One bot instance serves
installs on any number of Chatto servers.

## Setup

1. Install Java 21 and Maven. Install the sibling SDK: `mvn install` in `chatto-java-sdk`.
2. Start the Bot Hub and create a hub account.
3. In the hub dashboard, register a bot with these values (mirrored in `config.yaml`):
   - Login `rss_hub_bot`, display name `RSS Hub Bot`, description of your choice.
   - Permissions: `message.read`, `message.read-interactions`, `message.post-in-interactions`.
   - Rooms: `general`, `Standup`.
   - Callback URL: the public URL of this bot's webhook, e.g.
     `https://open-proxy.space/chatto/callback` (webhook `port`/`path` in `config.yaml`).
4. Start this bot: `mvn exec:java`. It listens for the hub callback and waits if no
   install has happened yet. Then click **Install** in the hub: the receipt
   (server URL, API key, granted/denied permissions, added/failed rooms) is saved to
   SQLite and the bot connects. Repeat on any other server with the same callback
   URL — no restart needed; each server gets its own connection and the bot replies
   on the server each message came from.
5. Mention the bot in Chatto to manage feeds:

```
@rss_hub_bot add https://example.com/feed.xml
@rss_hub_bot add https://example.com/feed.xml 30 channel-1
@rss_hub_bot add https://example.com/feed.xml channel-2
@rss_hub_bot pause https://example.com/feed.xml channel-1
@rss_hub_bot pause
@rss_hub_bot list
@rss_hub_bot remove https://example.com/feed.xml channel-1
```

`add` defaults to 15 minutes and `#general`. The interval accepts 1 to 10080 minutes. The channel name is the remaining text after the URL and optional interval, so it can contain spaces; surrounding whitespace is trimmed. If the first word after the URL is numeric, it is treated as the interval. `pause` and `remove` also take the remaining text as the channel name. Channel names are exact and case sensitive; a leading `#` is optional. A subscription is unique by feed URL and channel, so adding it again updates its interval and resumes it if paused. `pause` with no arguments pauses every subscription; `pause <feed-url> [channel name]` pauses one, defaulting to `#general`. Paused subscriptions remain in SQLite and appear as paused in `list`. Add them again to resume. `remove` defaults to `#general`. Commands are accepted from direct mentions and replies appear in the command's thread.

Feed polling starts immediately after `add`. On the first poll, the bot posts up to `max_items_per_poll` recent entries, oldest first. Each feed keeps its own interval. A failed feed or item is logged and retried later without stopping other feeds. If an article page is unavailable, the bot uses the feed summary. Keep `feeds.sqlite` across restarts to preserve subscriptions and delivery history.

## Permissions reality check (verified live)

With the three hub-granted permissions the bot can:

- read mentions and their threads (`message.read-interactions`),
- reply inside mentioned threads (`message.post-in-interactions`),
- resolve/list the rooms it belongs to.

It can **not** publish feed items as channel root messages — that needs `message.post`
(root posts return `permission_denied`). To make publishing work, tick `message.post`
in the hub bot form and reinstall; the running bot picks up hub reinstall callbacks
on restart.

## Configuration

`config.yaml` sets the hub registration values, the webhook listener (`port`/`path`),
and feed settings (`feed.database_file`, item limit, article
length, article fetching, HTTP timeout). `${NAME}` requires a process environment
variable; `${NAME:-default}` supplies a default. There is no `.env` support.
Relative paths (`feed.database_file`) resolve against the YAML
directory. The SQLite file holds installs (live API keys) plus subscriptions —
it is gitignored, never commit it.

Run `mvn exec:java`. Use `--config /path/to/config.yaml` for another configuration.
`--once` polls current subscriptions once and exits without listening (requires an
existing install; it fails fast instead of waiting for the callback).

Subscriptions are stored in SQLite, scoped per Chatto server, and are created through bot commands. `add`, `pause`, `remove`, and `list` always act on the server the command arrived from; bare `pause` pauses only that server's subscriptions.

A confirmed Chatto post is recorded immediately in SQLite. A crash or database failure between Chatto accepting the post and the record write can still cause a duplicate on retry. SQLite and Chatto outages are logged; the process keeps polling and reconnecting. Startup requires a valid config and at least one hub install (or waits for one); the webhook listener always runs so hub reinstalls/rotations are saved and applied, including installs on new servers without a restart.

## Tests

```bash
mvn test
```
