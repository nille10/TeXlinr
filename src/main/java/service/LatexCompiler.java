/*

KI generiert

 */

package service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Kompiliert eine {@code .tex}-Datei mit der lokal installierten TeX-Distribution
 * (TeX Live, MiKTeX, MacTeX) zu einem PDF.
 *
 * <p>Bevorzugt wird {@code latexmk}, das Anzahl der Läufe sowie BibTeX/Biber
 * selbstständig steuert und bei Folge-Builds nur neu übersetzt, was nötig ist.
 * Ist {@code latexmk} nicht verfügbar, wird ein einfacher Fallback verwendet
 * (Engine, ggf. BibTeX/Biber, Engine, Engine).</p>
 *
 * <p>Die Klasse enthält keine Swing-Abhängigkeit und blockiert, bis der Build
 * fertig ist. Sie gehört deshalb in einen Hintergrund-Thread
 * (z. B. {@code SwingWorker}).</p>
 */
public final class LatexCompiler {

    /** Verfügbare TeX-Engines. */
    public enum Engine {
        PDFLATEX("pdflatex", "-pdf"),
        XELATEX("xelatex", "-xelatex"),
        LUALATEX("lualatex", "-lualatex");

        private final String binary;
        private final String latexmkFlag;

        Engine(String binary, String latexmkFlag) {
            this.binary = binary;
            this.latexmkFlag = latexmkFlag;
        }
    }

    /**
     * Ergebnis eines Builds.
     *
     * @param success {@code true}, wenn der Build erfolgreich war und das PDF existiert
     * @param pdf     Pfad zum erzeugten PDF (bei Misserfolg ggf. {@code null})
     * @param errors  die wichtigsten Fehlerzeilen aus dem Log (höchstens 15)
     * @param log     die komplette Ausgabe aller aufgerufenen Programme
     */
    public record Result(boolean success, Path pdf, List<String> errors, String log) {}

    private static final long TIMEOUT_SECONDS = 180;
    private static final Pattern FILE_LINE_ERROR = Pattern.compile("^.*\\.(tex|sty|cls|bib|bbl):\\d+:.*");

    private final Engine engine;

    public LatexCompiler() {
        this(Engine.PDFLATEX);
    }

    public LatexCompiler(Engine engine) {
        this.engine = engine;
    }

    /**
     * Kompiliert die angegebene Datei. Das Arbeitsverzeichnis ist das Verzeichnis
     * der {@code .tex}-Datei, dort landen auch PDF und Hilfsdateien.
     *
     * <p>Wiederholte Builds sind robust gegen Änderungen der Vorlage: Hat sich die Präambel
     * (Dokumentklasse, Pakete) seit dem letzten erfolgreichen Build geändert, werden die
     * Hilfsdateien ({@code .aux}, {@code .toc}, {@code .out} ...) vorher gelöscht, weil veraltete
     * Dateien einer anderen Vorlage sonst Fehler auslösen können. Schlägt ein Build trotzdem
     * fehl, wird einmal nach vollständiger Bereinigung neu versucht.</p>
     *
     * @param texFile die zu kompilierende {@code .tex}-Datei
     * @return das Ergebnis; wirft keine Exception für TeX-Fehler, sondern liefert {@code success == false}
     * @throws InterruptedException wenn der aufrufende Thread unterbrochen wird
     */
    public Result compile(Path texFile) throws InterruptedException {
        Path tex = texFile.toAbsolutePath();
        Path dir = tex.getParent();
        String name = tex.getFileName().toString();
        String base = name.replaceFirst("\\.tex$", "");
        Path pdf = dir.resolve(base + ".pdf");
        Path marker = dir.resolve(base + ".texlinr-preamble");
        StringBuilder log = new StringBuilder();

        try {
            String preamble = preambleOf(tex);
            boolean cleaned = false;

            if (!preamble.equals(readQuietly(marker))) {
                removeAuxiliaryFiles(dir, base, log, "Vorlage geändert");
                cleaned = true;
            }

            Boolean ok = build(dir, name, base, log);
            if (ok == null) {
                return new Result(false, null, List.of(
                        "Keine TeX-Installation gefunden (weder latexmk noch " + engine.binary + "). "
                                + "Bitte TeX Live, MiKTeX oder MacTeX installieren und prüfen, "
                                + "ob das Programm im PATH liegt."), "");
            }

            if (!(ok && Files.exists(pdf)) && !cleaned) {
                removeAuxiliaryFiles(dir, base, log, "Build fehlgeschlagen, neuer Versuch");
                ok = build(dir, name, base, log);
            }

            boolean success = Boolean.TRUE.equals(ok) && Files.exists(pdf);
            if (success) {
                Files.writeString(marker, preamble, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(marker);
            }
            return new Result(success, success ? pdf : null, extractErrors(log.toString()), log.toString());
        } catch (IOException e) {
            return new Result(false, null, List.of("TeX konnte nicht gestartet werden: " + e.getMessage()),
                    log.toString());
        }
    }

    /** Ein Build-Durchlauf. {@code null}, wenn keine TeX-Installation gefunden wurde. */
    private Boolean build(Path dir, String name, String base, StringBuilder log)
            throws IOException, InterruptedException {
        if (works("latexmk", dir)) {
            return run(List.of("latexmk", engine.latexmkFlag,
                    "-interaction=nonstopmode", "-halt-on-error",
                    "-file-line-error", "-synctex=1", name), dir, log) == 0;
        }
        if (works(engine.binary, dir)) {
            log.append("[latexmk nicht gefunden, verwende ").append(engine.binary).append("]\n");
            return runManually(dir, name, base, log);
        }
        return null;
    }

    /** Alles vor {@code \begin{document}}: Dokumentklasse, Pakete, Seitenformat. */
    private static String preambleOf(Path tex) throws IOException {
        String text = Files.readString(tex, StandardCharsets.UTF_8);
        int i = text.indexOf("\\begin{document}");
        return i < 0 ? text : text.substring(0, i);
    }

    private static String readQuietly(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Löscht die von TeX erzeugten Hilfsdateien (nicht die {@code .tex}-Datei selbst). */
    private static void removeAuxiliaryFiles(Path dir, String base, StringBuilder log, String reason) {
        String[] endings = {".aux", ".toc", ".out", ".lof", ".lot", ".log", ".fls", ".fdb_latexmk",
                ".synctex.gz", ".bbl", ".blg", ".bcf", ".run.xml", ".pdf", ".xdv"};
        log.append("[Hilfsdateien werden gelöscht: ").append(reason).append("]\n");
        for (String ending : endings) {
            try {
                Files.deleteIfExists(dir.resolve(base + ending));
            } catch (IOException ignored) {
                // gesperrte Datei (z. B. von einem PDF-Viewer): der Build zeigt dann den eigentlichen Fehler
            }
        }
    }

    // ── Fallback ohne latexmk ────────────────────────────────────────────────

    private boolean runManually(Path dir, String name, String base, StringBuilder log)
            throws IOException, InterruptedException {
        List<String> latex = List.of(engine.binary, "-interaction=nonstopmode",
                "-halt-on-error", "-file-line-error", "-synctex=1", name);

        if (run(latex, dir, log) != 0) return false;

        Path aux = dir.resolve(base + ".aux");
        if (Files.exists(dir.resolve(base + ".bcf")) && works("biber", dir)) {
            run(List.of("biber", base), dir, log);
        } else if (Files.exists(aux) && new String(Files.readAllBytes(aux), StandardCharsets.ISO_8859_1)
                .contains("\\bibdata") && works("bibtex", dir)) {
            run(List.of("bibtex", base), dir, log);
        }

        return run(latex, dir, log) == 0 && run(latex, dir, log) == 0;
    }

    // ── Prozess-Hilfen ───────────────────────────────────────────────────────

    /** Prüft, ob ein Programm gestartet werden kann ({@code --version} liefert Exit-Code 0). */
    private boolean works(String executable, Path dir) throws InterruptedException {
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "--version")
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            extendPath(pb.environment());
            Process p = pb.start();
            p.getOutputStream().close();
            Thread drain = new Thread(() -> {
                try { p.getInputStream().readAllBytes(); } catch (IOException ignored) { }
            }, "latex-probe");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Startet ein Programm, sammelt dessen Ausgabe im Log und liefert den Exit-Code (-1 bei Zeitüberschreitung). */
    private int run(List<String> command, Path dir, StringBuilder log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        extendPath(pb.environment());

        synchronized (log) {
            log.append("$ ").append(String.join(" ", command)).append('\n');
        }

        Process p = pb.start();
        p.getOutputStream().close(); // TeX bekommt EOF statt auf Eingabe zu warten

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (log) {
                        log.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // Prozess beendet
            }
        }, "latex-output");
        reader.setDaemon(true);
        reader.start();

        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            reader.join(2000);
            synchronized (log) {
                log.append("\n[Abbruch: Zeitlimit von ").append(TIMEOUT_SECONDS).append(" s überschritten]\n");
            }
            return -1;
        }
        reader.join(2000);
        return p.exitValue();
    }

    /** GUI-Programme erben unter macOS nicht den Shell-PATH; typische TeX-Pfade werden ergänzt. */
    private static void extendPath(Map<String, String> env) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        String extra = String.join(File.pathSeparator,
                "/Library/TeX/texbin", "/opt/homebrew/bin", "/usr/local/bin");
        String path = env.get("PATH");
        env.put("PATH", path == null || path.isEmpty() ? extra : path + File.pathSeparator + extra);
    }

    // ── Fehlerauswertung ─────────────────────────────────────────────────────

    private static List<String> extractErrors(String log) {
        Set<String> found = new LinkedHashSet<>();
        for (String line : log.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("!") || FILE_LINE_ERROR.matcher(t).matches()) {
                found.add(t);
                if (found.size() >= 15) break;
            }
        }
        return new ArrayList<>(found);
    }
}