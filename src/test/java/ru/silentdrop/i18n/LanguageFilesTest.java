package ru.silentdrop.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the bundled language files consistent.
 *
 * Checks:
 *  - every language has exactly the same key set as English;
 *  - every message key used in the Java sources exists in English;
 *  - placeholders used by a language are a subset of the English ones
 *    (an unknown placeholder would silently stay in the text);
 *  - no language file accidentally contains values of another language.
 */
class LanguageFilesTest {

    private static final List<String> LANGUAGES = Arrays.asList("en", "ru", "tr");

    private static File resourcesDir() {
        File dir = new File("src/main/resources");
        if (!dir.isDirectory()) {
            dir = new File("SilentDrop/src/main/resources");
        }
        assertTrue(dir.isDirectory(), "resource directory not found: " + dir.getAbsolutePath());
        return dir;
    }

    private static Map<String, String> loadFlat(String language) throws IOException {
        File file = new File(resourcesDir(), "messages_" + language + ".yml");
        assertTrue(file.isFile(), "missing bundled language file: " + file.getName());

        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream in = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = yaml.load(reader);
        }
        assertNotNull(root, "empty language file: " + file.getName());

        Map<String, String> flat = new LinkedHashMap<String, String>();
        flatten("", root, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, out);
            } else {
                out.put(key, value == null ? "" : String.valueOf(value));
            }
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new LinkedHashSet<String>();
        Matcher matcher = Pattern.compile("%([a-z_]+)%").matcher(value);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static List<Path> javaSources() throws IOException {
        File dir = new File("src/main/java");
        if (!dir.isDirectory()) {
            dir = new File("SilentDrop/src/main/java");
        }
        assertTrue(dir.isDirectory(), "source directory not found");
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            List<Path> out = new ArrayList<Path>();
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                out.add(p);
            }
            return out;
        }
    }

    /**
     * Message keys referenced from the sources.
     *
     * Two families are recognised:
     *  - keys as the FIRST argument: msg.format("key"), msg.raw("key"), lore(msg, "key", ...)
     *  - keys as the SECOND argument: msg.send(target, "key", ...), msg.actionBar(...), msg.title(...)
     * Plus both branches of a ternary such as {@code send(p, cond ? "a.b" : "c.d")}.
     *
     * A key must contain a dot or a dash, which keeps plain string literals
     * like "on" / "off" out of the scan.
     */
    private static Set<String> keysUsedInCode() throws IOException {
        Set<String> keys = new TreeSet<String>();
        // a key is "a.b", "a-b" or a single lowercase word ("usage")
        String key = "([a-z][a-z0-9_-]*(?:[.][a-z0-9_-]+)+|[a-z][a-z0-9_-]*)";

        Pattern firstArg = Pattern.compile(
                "\\.(?:format|raw)\\(\\s*\"" + key + "\"|\\.?lore\\(\\s*[A-Za-z_][\\w.]*\\s*,\\s*\"" + key + "\"");
        Pattern secondArg = Pattern.compile(
                "(?:\\.|\\b)(?:send|actionBar|title|notify|lore|format|raw)\\("
                        + "\\s*[A-Za-z_][\\w.()\\-]*\\s*,\\s*\"" + key + "\"");
        // title(target, "title.key", "subtitle.key", ...) - the third argument is a key too
        Pattern thirdArg = Pattern.compile(
                "\\.title\\(\\s*[A-Za-z_][\\w.()\\-]*\\s*,\\s*\"" + key + "\"\\s*,\\s*\"" + key + "\"");
        // Ternaries only count when at least one side looks like a real key ("a.b" or "a-b"),
        // otherwise rule.antiFarmEnabled ? "on" : "off" would pollute the scan.
        String dottedKey = "([a-z][a-z0-9_-]*[.][a-z0-9_.-]+)";
        String plainKey = "([a-z][a-z0-9_-]*-[a-z0-9_-]+)";
        Pattern ternary = Pattern.compile(
                "\"" + dottedKey + "\"\\s*:\\s*\"([a-z][\\w.-]*)\""
                        + "|\"([a-z][\\w.-]*)\"\\s*:\\s*\"" + dottedKey + "\""
                        + "|\"" + plainKey + "\"\\s*:\\s*\"" + plainKey + "\"");

        for (Path path : javaSources()) {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            for (Pattern pattern : Arrays.asList(firstArg, secondArg)) {
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    // the firstArg pattern is an alternation, so the key can land in group 1 or 2
                    for (int g = 1; g <= matcher.groupCount(); g++) {
                        String value = matcher.group(g);
                        if (value != null) {
                            keys.add(value);
                        }
                    }
                }
            }
            Matcher thirdMatcher = thirdArg.matcher(source);
            while (thirdMatcher.find()) {
                keys.add(thirdMatcher.group(1));
                keys.add(thirdMatcher.group(2));
            }
            Matcher ternaryMatcher = ternary.matcher(source);
            while (ternaryMatcher.find()) {
                for (int g = 1; g <= ternaryMatcher.groupCount(); g++) {
                    String value = ternaryMatcher.group(g);
                    if (value != null && !value.isEmpty()) {
                        keys.add(value);
                    }
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("English bundle exists and is not empty")
    void englishBundleExists() throws IOException {
        Map<String, String> en = loadFlat("en");
        assertFalse(en.isEmpty());
        assertTrue(en.containsKey("prefix"), "prefix key is required");
        assertTrue(en.containsKey("no-permission"));
        assertTrue(en.containsKey("loot.action-bar"));
        assertTrue(en.containsKey("vault.gui-title"));
    }

    @Test
    @DisplayName("Every language has exactly the same keys as English")
    void sameKeySets() throws IOException {
        Set<String> english = new TreeSet<String>(loadFlat("en").keySet());
        for (String language : LANGUAGES) {
            if (language.equals("en")) continue;
            Set<String> current = new TreeSet<String>(loadFlat(language).keySet());

            Set<String> missing = new TreeSet<String>(english);
            missing.removeAll(current);
            Set<String> extra = new TreeSet<String>(current);
            extra.removeAll(english);

            assertTrue(missing.isEmpty(), "messages_" + language + ".yml is missing keys: " + missing);
            assertTrue(extra.isEmpty(), "messages_" + language + ".yml has unknown keys: " + extra);
            assertEquals(english.size(), current.size());
        }
    }

    @Test
    @DisplayName("No empty values where a text is expected")
    void noAccidentallyEmptyValues() throws IOException {
        List<String> allowEmpty = Arrays.asList();
        for (String language : LANGUAGES) {
            for (Map.Entry<String, String> entry : loadFlat(language).entrySet()) {
                if (allowEmpty.contains(entry.getKey())) continue;
                // an empty value disables the message; that is legal but must be intentional,
                // so the bundled files ship with texts everywhere
                assertFalse(entry.getValue().trim().isEmpty(),
                        "messages_" + language + ".yml has an empty value for '" + entry.getKey() + "'");
            }
        }
    }

    @Test
    @DisplayName("Placeholders of a translation are a subset of the English ones")
    void placeholdersMatch() throws IOException {
        Map<String, String> english = loadFlat("en");
        for (String language : LANGUAGES) {
            if (language.equals("en")) continue;
            Map<String, String> current = loadFlat(language);
            for (Map.Entry<String, String> entry : current.entrySet()) {
                Set<String> expected = placeholders(english.get(entry.getKey()));
                Set<String> actual = placeholders(entry.getValue());
                actual.removeAll(expected);
                assertTrue(actual.isEmpty(), "messages_" + language + ".yml key '" + entry.getKey()
                        + "' uses unknown placeholders " + actual);
            }
        }
    }

    @Test
    @DisplayName("Every message key used in the code exists in the English bundle")
    void codeKeysExist() throws IOException {
        Set<String> english = loadFlat("en").keySet();
        Set<String> used = keysUsedInCode();

        assertFalse(used.isEmpty(), "no message keys found in the sources - the scanner is broken");

        Set<String> missing = new TreeSet<String>(used);
        missing.removeAll(english);
        assertTrue(missing.isEmpty(), "code references message keys that do not exist: " + missing);
    }

    @Test
    @DisplayName("English keys are actually used by the code (no dead texts)")
    void noDeadKeys() throws IOException {
        Set<String> english = new TreeSet<String>(loadFlat("en").keySet());
        Set<String> used = keysUsedInCode();

        // "prefix" and "debug.header" are reserved for future/optional use
        // "prefix" is applied by the chat formatter, "protection.denied-hopper" is kept
        // for admins who want to notify about blocked hopper pickups.
        Set<String> reserved = new TreeSet<String>(Arrays.asList("prefix", "debug.header",
                "protection.denied-hopper"));

        Set<String> dead = new TreeSet<String>(english);
        dead.removeAll(used);
        dead.removeAll(reserved);
        assertTrue(dead.isEmpty(), "unused message keys: " + dead);
    }

    @Test
    @DisplayName("Language files contain no cross-language text")
    void noCrossLanguageText() throws IOException {
        Pattern cyrillic = Pattern.compile("[\\u0400-\\u04FF]");
        for (Map.Entry<String, String> entry : loadFlat("en").entrySet()) {
            assertFalse(cyrillic.matcher(entry.getValue()).find(),
                    "English bundle contains Cyrillic text in '" + entry.getKey() + "'");
        }

        Pattern turkish = Pattern.compile("[\\u011F\\u0131\\u015F\\u00E7\\u00F6\\u00FC]");
        Map<String, String> ru = loadFlat("ru");
        for (Map.Entry<String, String> entry : ru.entrySet()) {
            assertFalse(turkish.matcher(entry.getValue()).find(),
                    "Russian bundle looks Turkish in '" + entry.getKey() + "'");
        }
    }
}
