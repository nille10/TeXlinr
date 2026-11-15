/*
KI generiert
 */

package model.source;

import java.util.EnumSet;
import java.util.Set;

import static model.source.SourceField.*;

/**
 * Die Art einer Quelle. Sie bestimmt den BibTeX-Eintragstyp, welche Felder die Maske zeigt
 * und welche davon Pflicht sind.
 */
public enum SourceType {

    BOOK("Buch", "book", false,
            set(AUTHOR, TITLE, EDITION, PUBLISHER, ADDRESS, YEAR, ISBN, URL),
            set(AUTHOR, TITLE, PUBLISHER, YEAR)),

    CHAPTER("Buchkapitel", "incollection", false,
            set(AUTHOR, TITLE, BOOKTITLE, EDITOR, PUBLISHER, ADDRESS, PAGES, YEAR, URL),
            set(AUTHOR, TITLE, BOOKTITLE, PUBLISHER, YEAR)),

    ARTICLE("Zeitschriftenartikel", "article", false,
            set(AUTHOR, TITLE, JOURNAL, VOLUME, NUMBER, PAGES, YEAR, DOI, URL),
            set(AUTHOR, TITLE, JOURNAL, YEAR)),

    CONFERENCE("Konferenzbeitrag", "inproceedings", false,
            set(AUTHOR, TITLE, BOOKTITLE, PAGES, PUBLISHER, ADDRESS, YEAR, DOI, URL),
            set(AUTHOR, TITLE, BOOKTITLE, YEAR)),

    WEBSITE("Internetseite", "misc", true,
            set(AUTHOR, TITLE, YEAR, URL, ACCESSED),
            set(TITLE, URL, ACCESSED)),

    SOFTWARE("Software / Dokumentation", "misc", true,
            set(AUTHOR, TITLE, YEAR, URL, ACCESSED, NOTE),
            set(TITLE, URL, ACCESSED)),

    THESIS("Abschlussarbeit", "mastersthesis", false,
            set(AUTHOR, TITLE, THESIS_KIND, SCHOOL, ADDRESS, YEAR, URL),
            set(AUTHOR, TITLE, SCHOOL, YEAR)),

    REPORT("Bericht / Whitepaper", "techreport", false,
            set(AUTHOR, TITLE, INSTITUTION, NUMBER, ADDRESS, YEAR, URL),
            set(AUTHOR, TITLE, INSTITUTION, YEAR)),

    MISC("Sonstiges", "misc", true,
            set(AUTHOR, TITLE, YEAR, URL, ACCESSED, NOTE),
            set(TITLE));

    private final String label;
    private final String bibType;
    private final boolean linkAsHowpublished;
    private final Set<SourceField> fields;
    private final Set<SourceField> required;

    SourceType(String label, String bibType, boolean linkAsHowpublished,
               Set<SourceField> fields, Set<SourceField> required) {
        this.label = label;
        this.bibType = bibType;
        this.linkAsHowpublished = linkAsHowpublished;
        this.fields = fields;
        this.required = required;
    }

    private static Set<SourceField> set(SourceField first, SourceField... rest) {
        return EnumSet.of(first, rest);
    }

    /** Liest eine Art anhand ihres Namens (z. B. aus einer gespeicherten Datei). */
    public static SourceType fromName(String name, SourceType fallback) {
        if (name == null) return fallback;
        try {
            return valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Beschriftung in der Auswahlliste. */
    public String label() {
        return label;
    }

    /** BibTeX-Eintragstyp ({@code book}, {@code misc} ...). Bei Abschlussarbeiten siehe {@link BibtexWriter}. */
    public String bibType() {
        return bibType;
    }

    /** {@code true}, wenn der Link wie in {@code misc}-Einträgen üblich als {@code howpublished} mit {@code url}-Befehl steht. */
    public boolean linkAsHowpublished() {
        return linkAsHowpublished;
    }

    /** Alle Felder, die bei dieser Art angezeigt und geschrieben werden. */
    public Set<SourceField> fields() {
        return fields;
    }

    public boolean hasField(SourceField field) {
        return fields.contains(field);
    }

    public boolean isRequired(SourceField field) {
        return required.contains(field);
    }

    @Override
    public String toString() {
        return label;
    }
}