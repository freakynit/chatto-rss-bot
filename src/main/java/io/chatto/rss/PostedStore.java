package io.chatto.rss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Stores posted feed identities and flushes each successful post to disk. */
final class PostedStore {
    private final Path path;
    private final Set<String> posted = new HashSet<>();

    PostedStore(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) posted.addAll(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    boolean contains(String id) { return posted.contains(id); }

    void add(String id) throws IOException {
        if (posted.contains(id)) return;
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        // The single poll worker writes one identity at a time after Chatto confirms the post.
        Files.writeString(path, id + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        posted.add(id);
    }
}
