# Project Guide

This Java 21 Chatto RSS bot uses the sibling `chatto-java-sdk` and is provisioned
exclusively through the Bot Hub. `config.yaml` holds hub registration values
(login `rss_hub_bot`, permissions `message.read` / `message.read-interactions` /
`message.post-in-interactions`, rooms `general` + `Standup`, public callback URL),
the local webhook listener (`port` 3000, `path` `/chatto/callback`), the Chatto
server URL, and feed settings. `${NAME}`/`${NAME:-default}` placeholders expand
from the process environment only; there is no `.env` support (`Dotenv`, `.env`,
`.env.example` were removed). The token is never configured: it arrives via the
hub callback and is read from `hub.install_file` (`hub-install.json`, gitignored).

`HubCallbackServer` (JDK `HttpServer`, daemon threads) accepts the hub install POST,
validates `bot_user_id`/`api_key`, and atomically persists the pretty-printed payload.
`HubInstall` parses that receipt (snake_case via Jackson naming strategy).
`RssBot` starts the listener first, then loads the receipt — or waits for it (5 s
poll) with dashboard instructions, failing fast under `--once`. It warns on startup
about denied permissions, failed rooms, login mismatches, and server-URL mismatches,
then runs the original loop: direct-mention commands (`add`, `pause`, `remove`,
`list`) via realtime notification occurrences, plus per-subscription polling every
five seconds. `FeedDatabase` stores subscriptions, pause state, posted entry IDs,
and processed command IDs in SQLite. Reinstall/rotation callbacks are saved and
take effect on restart.

`FeedReader` handles RSS 2.0, RDF RSS, and Atom. It disables XML external entities, skips malformed entries, and supports common summary, content, link, and date fields. `HttpSource` limits responses to 2 MB and applies a request timeout. `ArticleExtractor` extracts linked article paragraphs when enabled. `PostFormatter` respects Chatto's message size limit. Target channel names resolve through the SDK's visible room list.

Known permission boundary (verified live): the three granted permissions cover
mention reads and in-thread replies, but channel root posts need `message.post`
and fail with `permission_denied`. Feed publishing to channels therefore requires
adding `message.post` in the hub and reinstalling.
