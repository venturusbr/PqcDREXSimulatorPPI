package br.org.venturus.drexsim.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LanguagesTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

  @Test
  void listsEveryLanguageFileByTheNameItGivesItself() {
    List<Languages.Language> languages = Languages.list();
    assertTrue(languages.contains(new Languages.Language("en-US", "English")), languages.toString());
    assertTrue(languages.contains(new Languages.Language("pt-BR", "Português")), languages.toString());
  }

  @Test
  void everyLanguageHasTheKeysAndPlaceholdersOfEnglish() {
    Map<String, String> english = english();
    for (Languages.Entry entry : Languages.readAll()) {
      String code = entry.language().code();
      Set<String> missing = new TreeSet<>(english.keySet());
      missing.removeAll(entry.messages().keySet());
      assertEquals(Set.of(), missing, code + " lacks keys");
      Set<String> extra = new TreeSet<>(entry.messages().keySet());
      extra.removeAll(english.keySet());
      assertEquals(Set.of(), extra, code + " has keys English does not");
      english.forEach((key, text) -> assertEquals(placeholders(text), placeholders(entry.messages().get(key)),
          code + " " + key + " placeholders"));
    }
  }

  @Test
  void namesEveryOperationAndFieldTheServerOffers() {
    Map<String, String> english = english();
    for (SimulatorService.Operation op : SimulatorService.OPERATIONS) {
      for (String part : List.of("label", "description", "signer")) {
        String key = "operation." + op.id() + "." + part;
        assertTrue(english.containsKey(key), key);
      }
      for (SimulatorService.Field f : op.fields()) {
        String specific = "operation." + op.id() + ".field." + f.name();
        assertTrue(english.containsKey(specific) || english.containsKey("field." + f.name()), "label of " + specific);
      }
    }
  }

  private static Map<String, String> english() {
    return Languages.readAll().stream().filter(e -> e.language().code().equals("en-US")).findFirst()
        .orElseThrow(() -> new AssertionError("no en-US.json")).messages();
  }

  private static Set<String> placeholders(String text) {
    Set<String> names = new TreeSet<>();
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      names.add(m.group(1));
    }
    return names;
  }
}
