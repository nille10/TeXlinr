package model;

import java.util.List;

/**
 * Alle Einstellungen für das erzeugte LaTeX-Dokument ("Formalitäten").
 *
 * <p>Reine Daten ohne Swing-Bezug. Sie werden im {@code FormalitiesPanel} bearbeitet, vom
 * Export ausgelesen und gehören zum Projekt (müssen also mit ihm gespeichert und geladen
 * werden). Der Datensatz ist unveränderlich; eine Änderung erzeugt einen neuen.</p>
 *
 * <p>Fehlende Werte ({@code null}) werden im Konstruktor durch Standardwerte ersetzt, damit
 * auch unvollständig geladene Projekte (z. B. ältere Dateien ohne Formalitäten) gültige
 * Einstellungen ergeben.</p>
 *
 * @param mode           die Dokumentvorlage: {@link LatexExportMode#STANDARD} oder
 *                       {@link LatexExportMode#EXPERT} (Studienarbeit); {@code CANCELLED} ist nicht zulässig
 * @param coverPage      ob in der Studienarbeit Titelseite und Inhaltsverzeichnis eingefügt werden
 * @param metadata       Titelseiten-Angaben und Autoren (die Autoren gelten auch im Standard-Dokument)
 * @param pageWidth      die Seitenbreite in der Einheit {@code pageUnit}
 * @param pageUnit       die Einheit der Seitenbreite: {@code "mm"}, {@code "cm"} oder {@code "inch"}
 * @param pageBreakMode  wie Seitenumbrüche gesetzt werden
 * @param showPageNumbers ob Seitenzahlen angezeigt werden
 */
public record LatexSettings(
        LatexExportMode mode,
        boolean coverPage,
        LatexMetadata metadata,
        int pageWidth,
        String pageUnit,
        PageBreakMode pageBreakMode,
        boolean showPageNumbers
) {

    public LatexSettings {
        if (mode == null || mode == LatexExportMode.CANCELLED) {
            mode = LatexExportMode.STANDARD;
        }
        if (metadata == null) {
            metadata = defaultMetadata();
        }
        if (pageWidth <= 0) {
            pageWidth = 210;
        }
        if (pageUnit == null || pageUnit.isBlank()) {
            pageUnit = "mm";
        }
        if (pageBreakMode == null) {
            pageBreakMode = PageBreakMode.AUTOMATIC;
        }
    }

    /**
     * @return die Standardeinstellungen für ein neues Projekt
     */
    public static LatexSettings defaults() {
        return new LatexSettings(
                LatexExportMode.STANDARD,
                true,
                defaultMetadata(),
                210,
                "mm",
                PageBreakMode.AUTOMATIC,
                true);
    }

    /**
     * Wandelt einen gespeicherten Namen in eine Dokumentvorlage um.
     *
     * @param name der Name aus {@link LatexExportMode#name()}, darf {@code null} oder ungültig sein
     * @return die Vorlage; {@link LatexExportMode#STANDARD} bei {@code null}, unbekanntem Namen oder {@code CANCELLED}
     */
    public static LatexExportMode modeFromName(String name) {
        if (name == null) {
            return LatexExportMode.STANDARD;
        }
        try {
            LatexExportMode mode = LatexExportMode.valueOf(name.trim());
            return mode == LatexExportMode.CANCELLED ? LatexExportMode.STANDARD : mode;
        } catch (IllegalArgumentException e) {
            return LatexExportMode.STANDARD;
        }
    }

    private static LatexMetadata defaultMetadata() {
        return new LatexMetadata(
                "Technik",
                "Elektrotechnik und Informatik",
                "",
                "Bericht",
                List.of(),
                "Deine Stadt",
                List.of());
    }
}