/*
KI generiert
 */

package model.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Erzeugt aus den Quellen der Eingabemaske den Text der {@code .bib}-Datei.
 *
 * <p>Geschrieben werden nur Felder, die zur Art der Quelle gehören und nicht leer sind. Das Format
 * entspricht klassischem BibTeX, wie in {@code @misc} mit {@code howpublished} und {@code note}.</p>
 *
 * <ul>
 *   <li>Autoren: eine Person pro Zeile. {@code Vorname Nachname} wird zu {@code Nachname, Vorname},
 *       eine Organisation steht in doppelten Klammern ({@code {{Oracle Corporation}}}).</li>
 *   <li>{@code & % # _ $} in Texten werden für LaTeX maskiert; ausgeglichene geschweifte Klammern
 *       bleiben erhalten (Schutz der Großschreibung, z. B. {@code {FlatLaf}}).</li>
 *   <li>Seitenbereiche {@code 12-15} werden zu {@code 12--15}.</li>
 *   <li>"Abgerufen am" und "Notiz" landen gemeinsam im Feld {@code note}.</li>
 * </ul>
 */
public final class BibtexWriter {

    private BibtexWriter() {
    }

    /**
     * Die Quellen, die in die {@code .bib}-Datei kommen: Einträge ohne gültigen Zitierschlüssel und
     * spätere Einträge mit schon vergebenem Schlüssel (BibTeX unterscheidet Groß-/Kleinschreibung
     * nicht) werden ausgelassen, weil sie BibTeX sonst mit Fehlern abbrechen lassen.
     */
    public static List<SourceEntry> usable(List<SourceEntry> entries) {
        List<SourceEntry> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (entries == null) return result;
        for (SourceEntry e : entries) {
            if (!SourceEntry.isValidKey(e.getKey())) continue;
            if (!seen.add(e.getKey().toLowerCase(Locale.ROOT))) continue;
            result.add(e);
        }
        return result;
    }

    /** Alle brauchbaren Quellen (siehe {@link #usable}) als Inhalt einer {@code .bib}-Datei, durch Leerzeilen getrennt. */
    public static String write(List<SourceEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (SourceEntry e : usable(entries)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(write(e));
        }
        return sb.toString();
    }

    /** Eine einzelne Quelle als BibTeX-Eintrag (endet mit Zeilenumbruch). */
    public static String write(SourceEntry entry) {
        SourceType type = entry.getType();
        String kind = entry.get(SourceField.THESIS_KIND).strip();
        boolean doctoral = type == SourceType.THESIS && isDoctoral(kind);

        String bibType = type.bibType();
        if (type == SourceType.THESIS) {
            bibType = doctoral ? "phdthesis" : "mastersthesis";
        }

        Map<String, String> out = new LinkedHashMap<>();
        List<String> noteParts = new ArrayList<>();

        for (SourceField f : SourceField.values()) {
            if (!type.hasField(f)) continue;
            String v = entry.get(f).strip();
            if (v.isEmpty()) continue;

            switch (f) {
                case AUTHOR -> out.put("author", "{" + people(v, entry.isCorporateAuthor()) + "}");
                case EDITOR -> out.put("editor", "{" + people(v, false) + "}");
                case URL -> {
                    String url = v.replaceAll("\\s+", "");
                    if (type.linkAsHowpublished()) {
                        out.put("howpublished", "{\\url{" + url + "}}");
                    } else {
                        out.put("url", "{" + url + "}");
                    }
                }
                case DOI -> out.put("doi", "{" + v.replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "") + "}");
                case PAGES -> out.put("pages", "{" + escape(v).replaceAll("(?<=\\d)\\s*[-–—]+\\s*(?=\\d)", "--") + "}");
                case THESIS_KIND -> {
                    if (!doctoral) out.put("type", "{" + escape(v) + "}");
                }
                case ACCESSED -> noteParts.add("Abgerufen am: " + escape(v));
                case NOTE -> noteParts.add(escape(v));
                default -> out.put(f.bibName(), "{" + escape(v) + "}");
            }
        }
        if (!noteParts.isEmpty()) {
            out.put("note", "{" + String.join("; ", noteParts) + "}");
        }

        int width = out.keySet().stream().mapToInt(String::length).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        sb.append('@').append(bibType).append('{').append(entry.getKey()).append(",\n");
        int i = 0;
        for (Map.Entry<String, String> field : out.entrySet()) {
            sb.append("  ").append(field.getKey())
                    .append(" ".repeat(width - field.getKey().length()))
                    .append(" = ").append(field.getValue());
            sb.append(++i < out.size() ? ",\n" : "\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isDoctoral(String kind) {
        String k = kind.toLowerCase(Locale.ROOT);
        return k.contains("diss") || k.contains("phd") || k.contains("promotion");
    }

    // ── Personen ─────────────────────────────────────────────────────────────

    private static String people(String text, boolean corporate) {
        List<String> parts = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (corporate) {
                parts.add("{" + escape(t) + "}");
            } else if (t.startsWith("{") && t.endsWith("}")) {
                parts.add(t);
            } else {
                parts.add(escape(surnameFirst(t)));
            }
        }
        return String.join(" and ", parts);
    }

    /** {@code Erich Gamma} wird zu {@code Gamma, Erich}; Namen mit Komma bleiben unverändert. */
    private static String surnameFirst(String name) {
        if (name.contains(",")) return name;
        int space = name.lastIndexOf(' ');
        if (space < 0) return name;
        return name.substring(space + 1) + ", " + name.substring(0, space).strip();
    }

    // ── Maskieren ────────────────────────────────────────────────────────────

    /** Maskiert LaTeX-Sonderzeichen; ausgeglichene geschweifte Klammern bleiben stehen. */
    static String escape(String input) {
        boolean balanced = bracesBalanced(input);
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\textbackslash{}");
                case '&' -> sb.append("\\&");
                case '%' -> sb.append("\\%");
                case '$' -> sb.append("\\$");
                case '#' -> sb.append("\\#");
                case '_' -> sb.append("\\_");
                case '{' -> sb.append(balanced ? "{" : "\\{");
                case '}' -> sb.append(balanced ? "}" : "\\}");
                case '\n', '\r' -> sb.append(' ');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean bracesBalanced(String s) {
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}' && --depth < 0) return false;
        }
        return depth == 0;
    }
}