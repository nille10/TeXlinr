/*
KI generiert
 */

package model.image;

/**
 * Platzierungsoptionen einer Abbildung in LaTeX, also der Inhalt von
 * {@code \begin{figure}[...]}.
 *
 * <p>Reine Daten ohne Swing-Bezug. {@link #toString()} liefert die Beschriftung für
 * Auswahlfelder, {@link #getCode()} den Wert für LaTeX.</p>
 */
public enum ImagePlacement {

    /** Standard: LaTeX darf die Abbildung frei platzieren. */
    AUTO("htbp", "Automatisch (htbp)"),
    /** Möglichst an der Stelle im Text. */
    HERE("h!", "Möglichst hier (h!)"),
    /** Oben auf der Seite. */
    TOP("t", "Seitenanfang (t)"),
    /** Unten auf der Seite. */
    BOTTOM("b", "Seitenende (b)"),
    /** Auf einer eigenen Seite mit Gleitobjekten. */
    FLOAT_PAGE("p", "Eigene Seite (p)"),
    /** Exakt an der Stelle; benötigt das Paket {@code float}. */
    EXACT("H", "Exakt hier (H, Paket float)");

    private final String code;
    private final String label;

    ImagePlacement(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * @return der Positionierungs-Code für LaTeX, z. B. {@code "htbp"}
     */
    public String getCode() {
        return code;
    }

    /**
     * Wandelt einen gespeicherten Namen zurück in den Enum-Wert.
     *
     * @param name     der Name aus {@link #name()}, darf {@code null} oder ungültig sein
     * @param fallback der Wert, der bei {@code null} oder unbekanntem Namen zurückgegeben wird
     * @return der passende Wert oder {@code fallback}
     */
    public static ImagePlacement fromName(String name, ImagePlacement fallback) {
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