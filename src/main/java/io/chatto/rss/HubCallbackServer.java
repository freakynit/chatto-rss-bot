package io.chatto.rss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal webhook listener for Bot Hub install callbacks.
 * Every valid POST is upserted into SQLite keyed by its Chatto server URL,
 * so one bot instance accumulates installs on any number of servers and
 * the delivered {@code api_key} values survive restarts.
 */
final class HubCallbackServer {
    private static final Logger LOG = Logger.getLogger(HubCallbackServer.class.getName());
    private static final int MAX_BODY = 1024 * 1024;

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    private HubCallbackServer(HttpServer server) {
        this.server = server;
    }

    static HubCallbackServer start(int port, String path, FeedDatabase database, Consumer<HubInstall> onInstall) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        var callback = new HubCallbackServer(server);
        server.createContext(path, exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 200, "{\"ok\":true,\"service\":\"chatto-rss-bot\"}");
                    return;
                }
                byte[] body;
                try {
                    body = exchange.getRequestBody().readAllBytes();
                } catch (IOException error) {
                    send(exchange, 400, "{\"ok\":false,\"error\":\"cannot read body\"}");
                    return;
                }
                if (body.length > MAX_BODY) {
                    send(exchange, 413, "{\"ok\":false,\"error\":\"body too large\"}");
                    return;
                }
                JsonNode payload;
                try {
                    payload = callback.mapper.readTree(body);
                } catch (IOException error) {
                    send(exchange, 400, "{\"ok\":false,\"error\":\"invalid JSON\"}");
                    return;
                }
                HubInstall install;
                try {
                    install = HubInstall.parse(payload);
                } catch (IllegalArgumentException error) {
                    send(exchange, 400, "{\"ok\":false,\"error\":\"" + error.getMessage().replace("\"", "'") + "\"}");
                    return;
                }
                String raw = new String(body, StandardCharsets.UTF_8);
                try {
                    database.upsertInstall(install, raw);
                } catch (Exception error) {
                    LOG.log(Level.WARNING, "Could not store hub install receipt", error);
                    send(exchange, 500, "{\"ok\":false}");
                    return;
                }
                LOG.info("Saved hub install receipt for " + install.login()
                        + " (" + install.botUserId() + ") on " + install.serverKey()
                        + ": granted=" + install.grantedPermissions()
                        + " denied=" + install.deniedPermissions().size()
                        + " rooms=" + install.addedRooms().size()
                        + " failedRooms=" + install.failedRooms().size());
                try {
                    onInstall.accept(install);
                } catch (Exception error) {
                    LOG.log(Level.WARNING, "Stored receipt but could not apply it", error);
                }
                send(exchange, 200, "{\"ok\":true}");
            } catch (Exception error) {
                LOG.log(Level.WARNING, "Callback handling failed", error);
                try {
                    send(exchange, 500, "{\"ok\":false}");
                } catch (IOException ignored) {
                }
            }
        });
        server.createContext("/health", exchange -> {
            try {
                send(exchange, 200, "{\"ok\":true,\"service\":\"chatto-rss-bot\"}");
            } catch (IOException ignored) {
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            var thread = new Thread(r, "hub-callback");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        LOG.info("Hub callback listener on :" + port + path + " -> SQLite installs");
        return callback;
    }

    void stop() {
        server.stop(0);
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
