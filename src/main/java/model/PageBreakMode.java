package model;

/**
 * Wie Seitenumbrüche im LaTeX-Dokument gesetzt werden.
 *
 * <p>Reine Daten ohne Swing-Bezug. {@link #toString()} liefert die Beschriftung für
 * Auswahlfelder, {@link #name()} eignet sich zum Speichern.</p>
 */
public enum PageBreakMode {

    /** LaTeX entscheidet selbst. */
    AUTOMATIC("Automatisch"),
    /** Jeder Haupteintrag (\section) beginnt auf einer neuen Seite. */
    BEFORE_EACH_SECTION("Vor jedem Haupteintrag"),
    /** Es werden keine automatischen Umbrüche eingefügt, Umbrüche setzt der Benutzer selbst im Text. */
    MANUAL("Manuell");

    private final String label;

    PageBreakMode(String label) {
        this.label = label;
    }

    /**
     * Wandelt einen gespeicherten Namen zurück in den Enum-Wert.
     *
     * @param name     der Name aus {@link #name()}, darf {@code null} oder ungültig sein
     * @param fallback der Wert, der bei {@code null} oder unbekanntem Namen zurückgegeben wird
     * @return der passende Wert oder {@code fallback}
     */
    public static PageBreakMode fromName(String name, PageBreakMode fallback) {
        if (name == null) return fallback;
        try {
            return valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}