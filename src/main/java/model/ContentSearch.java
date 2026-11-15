/*
KI generiert
 */

package model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sucht ein Wort im Inhalt (Notizen) aller Knoten der Gliederung.
 *
 * <p>Die Suche ist unabhängig von Groß-/Kleinschreibung, findet auch Teile von Wörtern und läuft
 * in Dokumentreihenfolge (Knoten von oben nach unten, innerhalb eines Knotens von vorne nach hinten).
 * Der unsichtbare Wurzelknoten wird nicht durchsucht. Eine Suche ist eine Momentaufnahme; nach
 * Änderungen am Text einfach eine neue Suche anlegen, sie ist auch bei großen Dokumenten schnell.</p>
 */
public final class ContentSearch {

    /** Ein Treffer: der Knoten sowie Beginn und Länge im Inhalt dieses Knotens. */
    public record Hit(OutlineNode node, int start, int length) {
    }

    /** Obergrenze, damit ein einzelner Buchstabe in einem riesigen Dokument nicht den Speicher füllt. */
    public static final int MAX_HITS = 20_000;

    private final String query;
    private final List<Hit> hits = new ArrayList<>();
    /** Position jedes Knotens in Dokumentreihenfolge, auch für Knoten ohne Treffer. */
    private final Map<OutlineNode, Integer> order = new IdentityHashMap<>();
    private boolean truncated;

    /**
     * @param root  die Wurzel der Gliederung
     * @param query das gesuchte Wort; leer oder {@code null} findet nichts
     */
    public ContentSearch(OutlineNode root, String query) {
        this.query = query == null ? "" : query;
        if (root != null) {
            walk(root, root);
        }
    }

    private void walk(OutlineNode root, OutlineNode node) {
        order.put(node, order.size());
        if (node != root && !query.isEmpty() && !truncated) {
            collect(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(root, (OutlineNode) node.getChildAt(i));
        }
    }

    private void collect(OutlineNode node) {
        String content = node.getContent();
        if (content == null || content.length() < query.length()) return;

        int last = content.length() - query.length();
        int i = 0;
        while (i <= last) {
            if (content.regionMatches(true, i, query, 0, query.length())) {
                if (hits.size() >= MAX_HITS) {
                    truncated = true;
                    return;
                }
                hits.add(new Hit(node, i, query.length()));
                i += query.length();
            } else {
                i++;
            }
        }
    }

    public String query() {
        return query;
    }

    public List<Hit> hits() {
        return hits;
    }

    public int size() {
        return hits.size();
    }

    /** {@code true}, wenn es mehr Treffer gibt, als {@link #MAX_HITS} gesammelt wurden. */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Index des ersten Treffers ab der angegebenen Stelle (diese eingeschlossen); geht es hinter dem
     * letzten Treffer nicht weiter, beginnt die Suche wieder vorne.
     *
     * @return der Index, oder {@code -1}, wenn es keine Treffer gibt
     */
    public int indexAtOrAfter(OutlineNode node, int offset) {
        if (hits.isEmpty()) return -1;
        int position = order.getOrDefault(node, 0);
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            int o = order.get(h.node());
            if (o > position || (o == position && h.start() >= offset)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Index des letzten Treffers vor der angegebenen Stelle (diese ausgeschlossen); geht es vor dem
     * ersten Treffer nicht weiter, beginnt die Suche wieder hinten.
     *
     * @return der Index, oder {@code -1}, wenn es keine Treffer gibt
     */
    public int indexBefore(OutlineNode node, int offset) {
        if (hits.isEmpty()) return -1;
        int position = order.getOrDefault(node, 0);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            int o = order.get(h.node());
            if (o < position || (o == position && h.start() < offset)) {
                return i;
            }
        }
        return hits.size() - 1;
    }
}