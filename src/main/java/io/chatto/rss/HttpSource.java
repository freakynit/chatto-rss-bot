package io.chatto.rss;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.time.Duration;

/** Bounded HTTP reader for RSS XML and linked article pages. */
final class HttpSource {
    private static final int MAX_BYTES = 2_000_000;
    private final HttpClient client;
    private final Duration timeout;

    HttpSource(int timeoutSeconds) {
        timeout = Duration.ofSeconds(timeoutSeconds);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    String get(String url) throws IOException, InterruptedException {
        URI uri = requireHttpUrl(url);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "ChattoRssBot/0.1 (+RSS article publisher)")
                .header("Accept", "application/rss+xml, application/xml, text/html;q=0.9, */*;q=0.5")
                .GET().build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " for " + uri.getHost());
            }
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("HTTP response exceeds 2 MB");
            String header = response.headers().firstValue("Content-Type").orElse("");
            var charset = java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*['\"]?([A-Za-z0-9._-]+)").matcher(header);
            String encoding = charset.find() ? charset.group(1) : null;
            if (encoding == null) {
                String declaration = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.US_ASCII);
                charset = java.util.regex.Pattern.compile("(?i)<\\?xml[^>]*encoding\\s*=\\s*['\"]([^'\"]+)").matcher(declaration);
                if (charset.find()) encoding = charset.group(1);
            }
            try { return new String(bytes, encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)); }
            catch (IllegalArgumentException badCharset) { return new String(bytes, StandardCharsets.UTF_8); }
        }
    }

    static URI requireHttpUrl(String url) {
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                uri.getHost() == null) throw new IllegalArgumentException("Expected an HTTP(S) URL");
        return uri;
    }
}
