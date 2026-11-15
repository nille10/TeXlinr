package model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import model.image.ImageLibrary;
import model.image.ImageEntry;

/**
 * Ersetzt Abbildungs-Kürzel im Text durch den fertigen LaTeX-Code der Abbildung.
 *
 * <p>Schreibst du in einer Notiz {@code \fig:board}, wobei {@code fig:board} das Label
 * eines Bildes aus der {@link ImageLibrary} ist, wird beim Export daraus die komplette
 * {@code figure}-Umgebung mit Platzierung, Breite, Beschriftung und Label
 * (siehe {@link ImageEntry#toLatex()}). Der Text in der Gliederung bleibt dabei
 * unverändert, ersetzt wird nur im erzeugten LaTeX-Dokument.</p>
 *
 * <p>Regeln:</p>
 * <ul>
 *   <li>Als Kürzel gelten nur Labels mit Doppelpunkt ({@code fig:board}), damit sie nie mit
 *       echten LaTeX-Befehlen wie {@code \ref} oder {@code \label} verwechselt werden.</li>
 *   <li>Auf das Label darf kein weiteres Label-Zeichen folgen, ein Punkt am Satzende ist
 *       aber erlaubt: {@code "siehe \fig:board."} funktioniert.</li>
 *   <li>{@code \\} (Zeilenumbruch) wird nie als Anfang eines Kürzels gelesen.</li>
 *   <li>Beginnt ein Kürzel mit {@code \fig:}, passt aber zu keinem Bild, erscheint im PDF
 *       eine gut sichtbare Fehlermeldung, statt dass LaTeX mit "Undefined control sequence"
 *       abbricht.</li>
 *   <li>Die Abbildung beginnt immer in einer neuen Zeile.</li>
 * </ul>
 *
 * <p>Ein Expander ist eine Momentaufnahme der Labels zum Zeitpunkt der Erzeugung; für
 * jeden Export einen neuen verwenden.</p>
 */
public final class FigureReferenceExpander {

    private static final String UNKNOWN_PREFIX = "fig:";

    /** Bilder mit Kürzel-tauglichem Label, längste Labels zuerst (damit {@code fig:a_b} vor {@code fig:a} gewinnt). */
    private final List<ImageEntry> candidates;

    /**
     * @param library die Bibliothek, deren Labels aufgelöst werden
     */
    public FigureReferenceExpander(ImageLibrary library) {
        this.candidates = library.getEntries().stream()
                .filter(entry -> entry.getLabel().contains(":"))
                .sorted(Comparator.comparingInt((ImageEntry entry) -> entry.getLabel().length()).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Ersetzt alle Abbildungs-Kürzel in einem Text.
     *
     * @param text der Text einer Notiz, darf {@code null} sein
     * @return der Text mit eingesetzten {@code figure}-Umgebungen; nie {@code null}
     */
    public String expand(String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf('\\') < 0) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length() + 128);
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }

            // "\\" ist ein Zeilenumbruch und nie der Anfang eines Kürzels
            if (i + 1 < length && text.charAt(i + 1) == '\\') {
                out.append("\\\\");
                i += 2;
                continue;
            }

            ImageEntry match = findEntryAt(text, i + 1);
            if (match != null) {
                appendBlock(out, match.toLatex());
                i += 1 + match.getLabel().length();
                continue;
            }

            String unknown = findUnknownReferenceAt(text, i + 1);
            if (unknown != null) {
                appendBlock(out, "\\textbf{[Abbildung " + unknown.replace("_", "\\_") + " nicht gefunden]}\n");
                i += 1 + unknown.length();
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private ImageEntry findEntryAt(String text, int start) {
        for (ImageEntry entry : candidates) {
            String label = entry.getLabel();
            if (text.startsWith(label, start)) {
                int end = start + label.length();
                if (end >= text.length() || !isLabelCharacter(text.charAt(end))) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** Liefert das Kürzel (ohne Backslash), wenn es mit {@code fig:} beginnt, sonst {@code null}. */
    private static String findUnknownReferenceAt(String text, int start) {
        if (!text.startsWith(UNKNOWN_PREFIX, start)) {
            return null;
        }
        int end = start + UNKNOWN_PREFIX.length();
        while (end < text.length() && isLabelCharacter(text.charAt(end))) {
            end++;
        }
        return end > start + UNKNOWN_PREFIX.length() ? text.substring(start, end) : null;
    }

    /** Zeichen, die zu einem Label gehören; der Punkt zählt bewusst nicht dazu (Satzende). */
    private static boolean isLabelCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == ':' || c == '_' || c == '-';
    }

    private static void appendBlock(StringBuilder out, String block) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append(block);
    }
}