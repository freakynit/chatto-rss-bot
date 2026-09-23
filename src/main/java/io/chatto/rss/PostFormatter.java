package io.chatto.rss;

import java.nio.charset.StandardCharsets;

/** Formats one item as a Chatto message within the server's 10,000-byte body limit. */
final class PostFormatter {
    private static final int MAX_MESSAGE_BYTES = 10_000;

    static String format(FeedReader.Item item, String article, int maxArticleCharacters) {
        String heading = item.title().isBlank() ? "RSS article" : item.title().strip();
        String body = article == null || article.isBlank() ? item.description() : article;
        body = body.strip();
        boolean clipped = body.codePointCount(0, body.length()) > maxArticleCharacters;
        if (clipped) body = body.substring(0, body.offsetByCodePoints(0, maxArticleCharacters)).stripTrailing();
        String prefix = "**" + heading.replace("*", "\\*") + "**\n\n";
        String suffix = "\n\n" + item.link();
        if (!item.published().isBlank()) suffix += "\n" + item.published();
        String marker = "\n\n[Article text shortened]";
        if (bytes(prefix + body + (clipped ? marker : "") + suffix) > MAX_MESSAGE_BYTES) {
            clipped = true;
            int low = 0;
            int high = body.codePointCount(0, body.length());
            while (low < high) {
                int middle = (low + high + 1) / 2;
                String candidate = body.substring(0, body.offsetByCodePoints(0, middle)).stripTrailing();
                if (bytes(prefix + candidate + marker + suffix) <= MAX_MESSAGE_BYTES) low = middle;
                else high = middle - 1;
            }
            body = body.substring(0, body.offsetByCodePoints(0, low)).stripTrailing();
        }
        String result = prefix + body + (clipped ? marker : "") + suffix;
        if (bytes(result) > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("Article title or URL is too long for Chatto");
        return result;
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
