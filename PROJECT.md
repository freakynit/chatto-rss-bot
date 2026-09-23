# Project Guide

This Java 21 Chatto RSS bot uses the sibling `chatto-java-sdk`. `config.yaml` provides the Chatto connection and shared feed settings. `.env` in the same directory supplies variables, overridden by the process environment.

`RssBot` listens for direct mention notifications through the SDK, parses `add`, `pause`, `remove`, and `list` commands, and polls all due subscriptions every five seconds. Subscriptions use independent intervals. `FeedDatabase` stores subscriptions, pause state, posted entry IDs, and processed command IDs in SQLite. A bare `pause` pauses all feeds; a URL targets one subscription. Re-adding resumes a paused subscription. The poller catches failures per feed, and the notification handler catches failures per command. A failed feed is retried after at most five minutes; a failed item remains eligible at the next feed poll. A confirmed post is recorded after Chatto accepts it, so an interruption between those operations can produce a duplicate.

`FeedReader` handles RSS 2.0, RDF RSS, and Atom. It disables XML external entities, skips malformed entries, and supports common summary, content, link, and date fields. `HttpSource` limits responses to 2 MB and applies a request timeout. `ArticleExtractor` extracts linked article paragraphs when enabled. `PostFormatter` respects Chatto's message size limit. Target channel names resolve through the SDK's visible room list.

An older `feed.url` is imported as a single subscription only when creating a new SQLite database. Existing text `state_file` history is not imported.
