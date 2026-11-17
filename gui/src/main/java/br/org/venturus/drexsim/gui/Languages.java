package br.org.venturus.drexsim.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The interface's languages: one JSON file per locale in {@code /public/i18n} ({@code en-US.json},
 * {@code pt-BR.json}, ...), each naming itself in {@code "_language"}. Adding a file adds a language.
 */
final class Languages {

  static final String DIRECTORY = "/public/i18n";

  /** A language file: its locale tag (the file name) and the name it gives itself. */
  record Language(String code, String name) {}

  private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*");
  private static final ObjectMapper JSON = new ObjectMapper();

  private Languages() {}

  /** Every language file on the classpath, sorted by name. */
  static List<Language> list() {
    return readAll().stream().map(Entry::language).sorted(Comparator.comparing(Language::name)).toList();
  }

  /** A language file and its messages, for checks. */
  record Entry(Language language, Map<String, String> messages) {}

  static List<Entry> readAll() {
    URL url = Languages.class.getResource(DIRECTORY);
    if (url == null) {
      return List.of();
    }
    try {
      URI uri = url.toURI();
      if ("jar".equals(uri.getScheme())) {
        FileSystem fs;
        try {
          fs = FileSystems.newFileSystem(uri, Map.of());
        } catch (FileSystemAlreadyExistsException e) {
          fs = FileSystems.getFileSystem(uri);
        }
        return read(fs.getPath(DIRECTORY));
      }
      return read(Path.of(uri));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Entry> read(Path dir) throws IOException {
    List<Entry> entries = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.sorted().toList()) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".json")) {
          continue;
        }
        String code = fileName.substring(0, fileName.length() - ".json".length());
        if (!LOCALE.matcher(code).matches()) {
          continue;
        }
        JsonNode root = JSON.readTree(Files.readAllBytes(file));
        Map<String, String> messages = new java.util.TreeMap<>();
        root.fields().forEachRemaining(e -> messages.put(e.getKey(), e.getValue().asText()));
        entries.add(new Entry(new Language(code, messages.getOrDefault("_language", code)), messages));
      }
    }
    return entries;
  }
}
