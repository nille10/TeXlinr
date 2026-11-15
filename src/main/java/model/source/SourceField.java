/*
KI generiert
 */

package model.source;

/**
 * Ein Eingabefeld einer Quelle (Autor, Titel, Jahr ...).
 *
 * <p>Die Reihenfolge der Konstanten ist zugleich die Reihenfolge in der Eingabemaske und in der
 * erzeugten {@code .bib}-Datei.</p>
 */
public enum SourceField {

    AUTHOR("Autor(en)", "author"),
    EDITOR("Herausgeber", "editor"),
    TITLE("Titel", "title"),
    BOOKTITLE("Buchtitel / Tagungsband", "booktitle"),
    JOURNAL("Zeitschrift", "journal"),
    THESIS_KIND("Art der Arbeit", "type"),
    SCHOOL("Hochschule", "school"),
    INSTITUTION("Institution", "institution"),
    EDITION("Auflage", "edition"),
    PUBLISHER("Verlag", "publisher"),
    ADDRESS("Ort", "address"),
    VOLUME("Band", "volume"),
    NUMBER("Nummer / Heft", "number"),
    PAGES("Seiten", "pages"),
    YEAR("Jahr", "year"),
    ISBN("ISBN", "isbn"),
    DOI("DOI", "doi"),
    URL("Link (URL)", "url"),
    ACCESSED("Abgerufen am", "note"),
    NOTE("Notiz", "note");

    private final String label;
    private final String bibName;

    SourceField(String label, String bibName) {
        this.label = label;
        this.bibName = bibName;
    }

    /** Liest ein Feld anhand seines Namens; {@code null}, wenn es das Feld nicht gibt. */
    public static SourceField fromName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Beschriftung in der Eingabemaske. */
    public String label() {
        return label;
    }

    /** Name des Feldes in BibTeX. */
    public String bibName() {
        return bibName;
    }
}