package io.chatto.rss;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Reads simple KEY=VALUE lines; the file does not modify the process environment. */
final class Dotenv {
    static Map<String, String> read(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(path)) return values;
        int number = 0;
        for (String line : Files.readAllLines(path)) {
            number++;
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (text.startsWith("export ")) text = text.substring(7).trim();
            int equals = text.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException("Invalid .env line " + number);
            String key = text.substring(0, equals).trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid .env key on line " + number);
            String value = text.substring(equals + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            values.put(key, value);
        }
        return values;
    }
}
