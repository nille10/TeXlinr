/*
KI generiert
 */

package model.source;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eine Quelle des Literaturverzeichnisses: Art, Zitierschlüssel und die Feldwerte der Eingabemaske.
 *
 * <p>Die Werte aller Felder bleiben beim Wechsel der Art erhalten; geschrieben werden nur die Felder
 * der aktuellen Art (siehe {@link BibtexWriter}).</p>
 */
public final class SourceEntry {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_:.\\-]*");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");

    private String key = "";
    /** Zuletzt automatisch erzeugter Schlüssel; solange {@code key} ihm entspricht, wird er mitgeführt. */
    private String autoKey = "";
    private SourceType type;
    private boolean corporateAuthor;
    private final Map<SourceField, String> values = new EnumMap<>(SourceField.class);

    public SourceEntry(SourceType type) {
        this.type = type == null ? SourceType.BOOK : type;
    }

    // ── Zugriff ──────────────────────────────────────────────────────────────

    public String getKey() { return key; }

    public void setKey(String key) { this.key = key == null ? "" : key.trim(); }

    public String getAutoKey() { return autoKey; }

    public void setAutoKey(String autoKey) { this.autoKey = autoKey == null ? "" : autoKey; }

    public SourceType getType() { return type; }

    public void setType(SourceType type) { this.type = type == null ? SourceType.BOOK : type; }

    /** {@code true}, wenn der Autor eine Organisation ist (wird dann nicht in Nachname/Vorname zerlegt). */
    public boolean isCorporateAuthor() { return corporateAuthor; }

    public void setCorporateAuthor(boolean corporateAuthor) { this.corporateAuthor = corporateAuthor; }

    /** @return der Wert des Feldes, nie {@code null} */
    public String get(SourceField field) {
        return values.getOrDefault(field, "");
    }

    public void set(SourceField field, String value) {
        if (value == null || value.isEmpty()) {
            values.remove(field);
        } else {
            values.put(field, value);
        }
    }

    public SourceEntry copy() {
        SourceEntry c = new SourceEntry(type);
        c.key = key;
        c.autoKey = autoKey;
        c.corporateAuthor = corporateAuthor;
        c.values.putAll(values);
        return c;
    }

    // ── Prüfung ──────────────────────────────────────────────────────────────

    /** @return die leeren Pflichtfelder der aktuellen Art, in Reihenfolge der Maske */
    public List<SourceField> missingRequired() {
        List<SourceField> missing = new ArrayList<>();
        for (SourceField f : SourceField.values()) {
            if (type.isRequired(f) && get(f).isBlank()) {
                missing.add(f);
            }
        }
        return missing;
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    // ── Schlüsselvorschlag ───────────────────────────────────────────────────

    /**
     * Schlägt einen Zitierschlüssel vor: bei Internetseiten, Software und Sonstigem aus dem Titel
     * ({@code FlatLaf}), sonst aus Nachname des ersten Autors und Jahr ({@code Gamma1995}).
     */
    public String suggestKey() {
        String year = firstYear();
        String fromTitle = camel(get(SourceField.TITLE), 2);
        String fromAuthor = firstAuthorToken();

        String key;
        if (type.linkAsHowpublished()) {
            key = !fromTitle.isEmpty() ? fromTitle : fromAuthor + year;
        } else if (!fromAuthor.isEmpty()) {
            key = fromAuthor + year;
        } else {
            key = fromTitle + year;
        }
        return key.isEmpty() ? "Quelle" : key;
    }

    private String firstYear() {
        Matcher m = YEAR_PATTERN.matcher(get(SourceField.YEAR));
        return m.find() ? m.group() : "";
    }

    private String firstAuthorToken() {
        String authors = get(SourceField.AUTHOR).strip();
        if (authors.isEmpty()) return "";
        String first = authors.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        if (corporateAuthor) {
            return camel(first, 2);
        }
        String surname;
        int comma = first.indexOf(',');
        if (comma >= 0) {
            surname = first.substring(0, comma);
        } else {
            int space = first.lastIndexOf(' ');
            surname = space >= 0 ? first.substring(space + 1) : first;
        }
        return ascii(surname);
    }

    /** Die ersten {@code maxWords} Wörter zusammengeschrieben, jedes mit großem Anfangsbuchstaben. */
    private static String camel(String text, int maxWords) {
        StringBuilder sb = new StringBuilder();
        int words = 0;
        for (String w : text.split("[^\\p{L}\\p{N}]+")) {
            String a = ascii(w);
            if (a.isEmpty()) continue;
            sb.append(Character.toUpperCase(a.charAt(0))).append(a.substring(1));
            if (++words >= maxWords) break;
        }
        return sb.toString();
    }

    private static String ascii(String s) {
        String n = Normalizer.normalize(s.replace("ß", "ss"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replaceAll("[^A-Za-z0-9]", "");
    }
}