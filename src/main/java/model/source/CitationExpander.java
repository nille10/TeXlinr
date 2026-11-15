/*
KI generiert
 */

package model.source;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ersetzt Zitier-Kürzel im Text durch den LaTeX-Befehl {@code \cite}.
 *
 * <p>Schreibst du in einer Notiz {@code \cite:Gamma1995}, entsteht im erzeugten LaTeX-Dokument
 * {@code \cite{Gamma1995}}. Mehrere Quellen trennst du mit Komma ohne Leerzeichen:
 * {@code \cite:Gamma1995,Fowler2002}. Der Text in der Gliederung bleibt unverändert.</p>
 *
 * <p>Regeln wie bei den Abbildungs-Kürzeln: Ein Punkt am Satzende gehört nicht zum Schlüssel
 * ({@code "siehe \cite:Gamma1995."}), und ein Schlüssel, den es nicht gibt, erscheint im PDF als
 * gut sichtbare Meldung statt als "?" im Text.</p>
 */
public final class CitationExpander {

    private static final String PREFIX = "\\cite:";

    private final Set<String> knownKeys = new HashSet<>();

    /**
     * @param sources die Quellen, deren Schlüssel gültig sind (siehe {@link BibtexWriter#usable})
     */
    public CitationExpander(List<SourceEntry> sources) {
        for (SourceEntry e : sources) {
            knownKeys.add(e.getKey().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * @param text der Text einer Notiz, darf {@code null} sein
     * @return der Text mit {@code \cite{...}}; nie {@code null}
     */
    public String expand(String text) {
        if (text == null) return "";
        if (!text.contains(PREFIX)) return text;

        StringBuilder out = new StringBuilder(text.length() + 32);
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith(PREFIX, i) && !isBackslashBefore(text, i)) {
                int end = i + PREFIX.length();
                int keysEnd = readKeys(text, end);
                if (keysEnd > end) {
                    appendCitation(out, text.substring(end, keysEnd));
                    i = keysEnd;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    private void appendCitation(StringBuilder out, String keyList) {
        StringBuilder known = new StringBuilder();
        for (String key : keyList.split(",")) {
            if (knownKeys.contains(key.toLowerCase(Locale.ROOT))) {
                if (known.length() > 0) known.append(',');
                known.append(key);
            } else {
                out.append("\\textbf{[Quelle ").append(key.replace("_", "\\_")).append(" nicht gefunden]}");
            }
        }
        if (known.length() > 0) {
            out.append("\\cite{").append(known).append('}');
        }
    }

    /** Liest {@code key} oder {@code key1,key2,...} ab {@code start}; liefert das Ende (gleich {@code start}, wenn keiner da ist). */
    private static int readKeys(String text, int start) {
        int pos = start;
        while (true) {
            int keyEnd = readKey(text, pos);
            if (keyEnd == pos) {
                return pos == start ? start : pos - 1; // ein hängendes Komma gehört nicht dazu
            }
            pos = keyEnd;
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
            } else {
                return pos;
            }
        }
    }

    private static int readKey(String text, int start) {
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            boolean letter = Character.isLetterOrDigit(c);
            boolean inner = c == '_' || c == ':' || c == '-' || c == '.';
            if (letter) {
                end++;
            } else if (inner && end > start && end + 1 < text.length() && isKeyChar(text.charAt(end + 1))) {
                end++; // Sonderzeichen nur mitten im Schlüssel, nie am Ende (Satzzeichen)
            } else {
                break;
            }
        }
        return end;
    }

    private static boolean isKeyChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** {@code \\cite:} (Zeilenumbruch gefolgt von Text) ist kein Kürzel. */
    private static boolean isBackslashBefore(String text, int i) {
        return i > 0 && text.charAt(i - 1) == '\\';
    }
}