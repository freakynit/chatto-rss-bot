package io.chatto.rss;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RssBotHttpTest {
    @Test void publishesLinkedArticleOnceInConfiguredThread() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/feed", exchange -> {
            String link = "http://127.0.0.1:" + server.getAddress().getPort() + "/article";
            respond(exchange, 200, "<rss version='2.0'><channel><item><guid>item-1</guid><title>News</title>" +
                    "<link>" + link + "</link><description>Summary</description></item></channel></rss>");
        });
        server.createContext("/article", exchange -> respond(exchange, 200,
                "<article><p>Article body from the linked page.</p></article>"));
        server.createContext("/api/connect/chatto.api.v1.MessageService/CreateMessage", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"message":{"id":"message-1","roomId":"room-1","createdAt":"2026-09-23T00:00:00Z","actorId":"bot-1","body":"ok"}}
                    """);
        });
        server.createContext("/api/connect/chatto.api.v1.ViewerService/GetViewer", exchange ->
                respond(exchange, 200, """
                        {"user":{"profile":{"id":"bot-1","login":"rss_bot","bot":{"ownerUserId":"owner-1"}}}}
                        """));
        server.createContext("/api/connect/chatto.api.v1.MessageService/GetMessage", exchange ->
                respond(exchange, 200, """
                        {"message":{"id":"root-1","roomId":"room-1","createdAt":"2026-09-23T00:00:00Z","actorId":"owner-1","body":"root"}}
                        """));
        server.createContext("/api/connect/chatto.api.v1.RoomDirectoryService/ListRooms", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"offset\":0")) {
                respond(exchange, 200, """
                        {"rooms":[{"room":{"id":"other","name":"other","kind":"ROOM_KIND_CHANNEL"}}],"page":{"hasMore":true}}
                        """);
            } else {
                respond(exchange, 200, """
                        {"rooms":[{"room":{"id":"room-1","name":"general","kind":"ROOM_KIND_CHANNEL"}}],"page":{"hasMore":false}}
                        """);
            }
        });
        server.createContext("/api/connect/chatto.api.v1.UserService/GetUser", exchange -> respond(exchange, 404, "{}"));
        server.createContext("/api/connect/chatto.api.v1.RoomDirectoryService/GetRoom", exchange -> respond(exchange, 404, "{}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var state = Files.createTempDirectory("rss-bot-http-test").resolve("posted.txt");
            var config = state.getParent().resolve("config.yaml");
            Files.writeString(config, """
                    chatto:
                      base_url: %s
                      token: cht_BK_test
                      room_name: '#general'
                      thread_root_event_id: root-1
                    feed:
                      url: %s/feed
                      poll_interval_minutes: 1
                      max_items_per_poll: 10
                      max_article_characters: 1000
                      fetch_article: true
                      state_file: posted.txt
                      http_timeout_seconds: 5
                    """.formatted(base, base));
            RssBot.main(new String[]{"--config", config.toString(), "--once"});
            RssBot.main(new String[]{"--config", config.toString(), "--once"});
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst()).contains("Article body from the linked page.")
                    .contains("\"roomId\":\"room-1\"")
                    .contains("\"threadRootEventId\":\"root-1\"");
            assertThat(Files.readAllLines(state)).hasSize(1);
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
