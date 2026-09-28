# Project Guide

This Java 21 Chatto RSS bot uses the sibling `chatto-java-sdk` and is provisioned
exclusively through the Bot Hub. `config.yaml` holds hub registration values
(login `rss_hub_bot`, permissions `message.read` / `message.read-interactions` /
`message.post-in-interactions`, rooms `general` + `Standup`, public callback URL),
the local webhook listener (`port` 3000, `path` `/chatto/callback`), and feed
settings. `${NAME}`/`${NAME:-default}` placeholders expand from the process
environment only; there is no `.env` support. The bot configures no Chatto server
or token: every install receipt (server URL + API key) arrives via hub callbacks
and is stored in the `installations` table in SQLite (`feed.database_file`,
gitignored).

`HubCallbackServer` (JDK `HttpServer`, daemon threads) accepts hub install POSTs,
requires `bot_user_id`/`api_key`/`chatto_server_url`, and upserts the receipt into
SQLite keyed by normalized server URL. `HubInstall` parses receipts (snake_case
via Jackson naming strategy) and normalizes server URLs. `RssBot` keeps one
`ChattoClient` per installed server in a single process: callbacks and a periodic
sync connect new servers and rotate changed keys without a restart. Notification
listeners are registered per client, so each command is handled with that server's
client, bot identity, and subscriptions. `FeedDatabase` scopes `installations`,
`feeds`, `posted`, and `commands` by server URL. Reinstall/rotation
callbacks take effect immediately for the affected server.

`FeedReader` handles RSS 2.0, RDF RSS, and Atom. It disables XML external entities, skips malformed entries, and supports common summary, content, link, and date fields. `HttpSource` limits responses to 2 MB and applies a request timeout. `ArticleExtractor` extracts linked article paragraphs when enabled. `PostFormatter` respects Chatto's message size limit. Target channel names resolve through each server's visible room list.

Known permission boundary (verified live): the three granted permissions cover
mention reads and in-thread replies, but channel root posts need `message.post`
and fail with `permission_denied`. Feed publishing to channels therefore requires
adding `message.post` in the hub and reinstalling.

Removed: `chatto.base_url` config, `hub.base_url` config, `hub.install_file` config, file-based install
receipts (`hub-install.json`), `PostedStore`/`posted-items.txt` (superseded by
the SQLite `posted` table).
