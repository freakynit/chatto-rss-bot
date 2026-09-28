package io.chatto.rss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal webhook listener for the Bot Hub install callback.
 * Every valid POST is persisted verbatim (pretty-printed) to the install file,
 * so the delivered {@code api_key} survives restarts.
 */
final class HubCallbackServer {
    private static final Logger LOG = Logger.getLogger(HubCallbackServer.class.getName());
    private static final int MAX_BODY = 1024 * 1024;

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    private HubCallbackServer(HttpServer server) {
        this.server = server;
    }

    static HubCallbackServer start(int port, String path, Path installFile, Consumer<HubInstall> onInstall) throws IOException {
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
                String botUserId = text(payload, "bot_user_id");
                String apiKey = text(payload, "api_key");
                if (botUserId.isBlank() || apiKey.isBlank()) {
                    send(exchange, 400, "{\"ok\":false,\"error\":\"missing bot_user_id or api_key\"}");
                    return;
                }
                Path parent = installFile.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Path tmp = installFile.resolveSibling(installFile.getFileName() + ".tmp");
                Files.writeString(tmp, callback.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
                Files.move(tmp, installFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                LOG.info("Saved hub install receipt for " + text(payload, "login")
                        + " (" + botUserId + "): granted=" + payload.path("granted_permissions")
                        + " denied=" + payload.path("denied_permissions").size()
                        + " rooms=" + payload.path("added_rooms").size()
                        + " failedRooms=" + payload.path("failed_rooms").size());
                try {
                    onInstall.accept(HubInstall.load(installFile).orElseThrow());
                } catch (Exception error) {
                    LOG.log(Level.WARNING, "Saved receipt but could not parse it", error);
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
        LOG.info("Hub callback listener on :" + port + path + " -> " + installFile);
        return callback;
    }

    void stop() {
        server.stop(0);
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? "" : child.asText("");
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
