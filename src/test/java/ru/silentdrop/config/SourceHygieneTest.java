package ru.silentdrop.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source level regression tests. They guard the mistakes that cannot be caught
 * without a running server.
 *
 * The most important one: a YamlConfiguration must never be installed as its own
 * defaults. MemorySection#get() falls back to getDefault() and a self reference
 * turns every lookup of a missing key into infinite recursion (StackOverflowError
 * on the first command execution).
 */
class SourceHygieneTest {

    private static List<Path> sources() throws IOException {
        File dir = new File("src/main/java");
        if (!dir.isDirectory()) {
            dir = new File("SilentDrop/src/main/java");
        }
        assertTrue(dir.isDirectory(), "source directory not found");
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            List<Path> out = new ArrayList<Path>();
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (p.toString().endsWith(".java")) out.add(p);
            }
            return out;
        }
    }

    @Test
    @DisplayName("No YamlConfiguration is used as its own defaults (StackOverflowError guard)")
    void noSelfDefaults() throws IOException {
        Pattern call = Pattern.compile("([A-Za-z_][\\w.]*)\\s*\\.\\s*setDefaults\\(\\s*([^)]*)\\)");
        List<String> problems = new ArrayList<String>();

        for (Path path : sources()) {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Matcher matcher = call.matcher(source);
            while (matcher.find()) {
                String receiver = matcher.group(1).trim();
                String argument = matcher.group(2).trim();
                String receiverLeaf = receiver.contains(".")
                        ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver;
                String argumentLeaf = argument.contains(".")
                        ? argument.substring(argument.lastIndexOf('.') + 1) : argument;
                if (receiverLeaf.equals(argumentLeaf)) {
                    problems.add(path.getFileName() + ": " + matcher.group(0));
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "a configuration is set as its own defaults (infinite recursion): " + problems);
    }

    @Test
    @DisplayName("The sources contain no hardcoded UI texts (everything goes through messages_*.yml)")
    void noHardcodedMessages() throws IOException {
        // A player visible string is a colored literal sent straight to sendMessage.
        Pattern colored = Pattern.compile(
                "sendMessage\\(\\s*Text\\.color\\(\\s*\"[^\"]*&[0-9a-fA-F]"
                        + "|sendMessage\\(\\s*\"[^\"]*&[0-9a-fA-F]");
        List<String> problems = new ArrayList<String>();

        for (Path path : sources()) {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Matcher matcher = colored.matcher(source);
            while (matcher.find()) {
                problems.add(path.getFileName() + ": " + matcher.group(0));
            }
        }
        assertTrue(problems.isEmpty(),
                "hardcoded colored message, move it into messages_en.yml: " + problems);
    }

    @Test
    @DisplayName("The sources are pure ASCII except the bundled language files")
    void sourcesAreEnglish() throws IOException {
        Pattern nonLatin = Pattern.compile("[\\u0400-\\u04FF\\u00C0-\\u024F\\u0400-\\u0530]");
        List<String> problems = new ArrayList<String>();

        for (Path path : sources()) {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            int line = 0;
            for (String l : source.split("\n")) {
                line++;
                if (nonLatin.matcher(l).find()) {
                    problems.add(path.getFileName() + ":" + line);
                }
            }
        }
        assertTrue(problems.isEmpty(), "non-English text in the sources: " + problems);
    }
}
