package io.chatto.rss;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RssContentTest {
    @Test void parsesFeedAndExtractsArticle() throws Exception {
        String feed = """
                <rss version="2.0"><channel><title>News</title>
                <item><title>One</title><link>https://example.org/one</link><guid>abc</guid>
                <description>&lt;p&gt;Short &amp;amp; clear&lt;/p&gt;</description></item>
                </channel></rss>
                """;
        var item = FeedReader.parse(feed).getFirst();
        assertThat(item.id()).isEqualTo("abc");
        assertThat(item.description()).isEqualTo("Short & clear");
        String page = "<html><body><nav><p>Menu</p></nav><article><div class='article-content'>" +
                "<p>First paragraph.</p><p>Second <b>paragraph</b>.</p></div></article></body></html>";
        assertThat(ArticleExtractor.extract(page)).isEqualTo("First paragraph.\n\nSecond paragraph.");
        assertThat(PostFormatter.format(item, ArticleExtractor.extract(page), 100))
                .contains("First paragraph.").contains("https://example.org/one").doesNotContain("Menu");
    }

    @Test void rejectsXmlEntitiesAndLimitsMessageBytes() throws Exception {
        assertThatThrownBy(() -> FeedReader.parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>"))
                .isInstanceOf(Exception.class);
        var item = new FeedReader.Item("a", "Title", "https://example.org/a", "fallback", "");
        String post = PostFormatter.format(item, "Ä".repeat(9000), 9000);
        assertThat(post.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(10_000);
        assertThat(post).contains("[Article text shortened]");
    }

    @Test void extractsDeutschlandfunkArticleBlocks() {
        String page = "<article><div class='article-content'><div class='article-details-text'>" +
                "Dazu schreibt die <a href='https://example.org'>Zeitung</a>.</div>" +
                "<div class='article-details-text'>Weiterer Text.</div></div></article>";
        assertThat(ArticleExtractor.extract(page)).isEqualTo("Dazu schreibt die Zeitung.\n\nWeiterer Text.");
    }

    @Test void persistsPostedIdentity() throws Exception {
        var path = Files.createTempDirectory("rss-store-test").resolve("posted.txt");
        var store = new PostedStore(path);
        assertThat(store.contains("abc")).isFalse();
        store.add("abc");
        store.add("abc");
        assertThat(Files.readAllLines(path)).containsExactly("abc");
        assertThat(new PostedStore(path).contains("abc")).isTrue();
    }

    @Test void loadsYamlWithDotenv() throws Exception {
        var folder = Files.createTempDirectory("rss-config-test");
        Files.writeString(folder.resolve(".env"), "CHATTO_TOKEN=cht_BK_local\nCHATTO_ROOM_NAME=#general\n");
        Files.writeString(folder.resolve("config.yaml"), """
                chatto:
                  base_url: ${CHATTO_BASE_URL:-http://localhost:4001}
                  token: ${CHATTO_TOKEN}
                  room_name: ${CHATTO_ROOM_NAME}
                feed:
                  url: https://example.org/feed.xml
                  poll_interval_minutes: 5
                  max_items_per_poll: 3
                  max_article_characters: 500
                  fetch_article: true
                  state_file: posted.txt
                  http_timeout_seconds: 2
                """);
        var config = Config.load(folder.resolve("config.yaml"));
        assertThat(config.roomName()).isEqualTo("general");
        assertThat(config.roomId()).isEmpty();
        assertThat(config.stateFile()).isEqualTo(folder.resolve("posted.txt"));
        assertThat(config.fetchArticle()).isTrue();
    }

    @Test void stripsOnlyLeadingHashFromRoomName() {
        assertThat(RoomResolver.normalize(" #Team News ")).isEqualTo("Team News");
        assertThat(RoomResolver.normalize("Team #News")).isEqualTo("Team #News");
        assertThatThrownBy(() -> RoomResolver.normalize("# ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void keepsRoomIdConfigurationAndRejectsTwoDestinations() throws Exception {
        var folder = Files.createTempDirectory("rss-room-config-test");
        var path = folder.resolve("config.yaml");
        String base = """
                chatto:
                  base_url: http://localhost:4001
                  token: cht_BK_local
                  room_id: room-1
                feed:
                  url: https://example.org/feed.xml
                  poll_interval_minutes: 5
                  max_items_per_poll: 3
                  max_article_characters: 500
                  fetch_article: false
                  state_file: posted.txt
                  http_timeout_seconds: 2
                """;
        Files.writeString(path, base);
        assertThat(Config.load(path).roomId()).isEqualTo("room-1");
        Files.writeString(path, base.replace("room_id: room-1", "room_id: room-1\n  room_name: '#general'"));
        assertThatThrownBy(() -> Config.load(path)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }
}
