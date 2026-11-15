package model;

import controller.OutlineController;
import view.CFileChooser;
import model.image.ImageEntry;
import model.image.ImageLibrary;
import model.source.BibtexWriter;
import model.source.CitationExpander;
import model.source.SourceEntry;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.TreeModel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Der {@code LATEXParser} ist für das Exportieren von Outlines im Latex-Format zuständig.
 *
 * <p>
 *   Der Grundaufbau des Dokuments ist \section \subsection \subsubsection für die drei möglichen Ebenen im Outliner.
 * </p>
 *
 * <p>
 *   Alle Einstellungen des Dokuments (Vorlage, Titelseite, Autoren, Seitenformat, Seitenumbrüche,
 *   Seitenzahlen, Logo) kommen aus den Formalitäten, siehe {@link LatexSettings}. Es gibt keine
 *   Export-Dialoge mehr. Die Klasse bietet zwei Einstiegspunkte: {@link #exportAsLatex()} fragt nur
 *   noch nach dem Speicherort (Menü-Export), {@link #exportTo(Path)} schreibt ohne jeden Dialog direkt
 *   in eine Datei (z. B. für den Build der PDF-Vorschau). Beide erzeugen exakt dasselbe Dokument.
 * </p>
 */
public class LATEXParser {

    private final OutlineController outlineController;
    private final JFrame mainWindow;
    private final TreeModel treeModel;
    private final ImageLibrary imageLibrary;

    /** Löst {@code \fig:label}-Kürzel auf; wird bei jedem Export neu erzeugt. */
    private FigureReferenceExpander figures;

    /** Löst {@code \cite:key}-Kürzel auf; wird bei jedem Export neu erzeugt. */
    private CitationExpander citations;

    /** Die brauchbaren Quellen des laufenden Exports (siehe {@link BibtexWriter#usable}). */
    private List<SourceEntry> sources = List.of();

    /** Name der BibTeX-Datei, die neben der {@code .tex}-Datei angelegt wird. */
    private static final String BIB_FILE = "references";

    /** Die Formalitäten des laufenden Exports; wird zu Beginn von {@link #exportTo(Path)} gelesen. */
    private LatexSettings settings;

    /**
     * Erstellt einen neunen {@code LATEXParser}.
     * @param outlineController der Controller für den Zugriff auf die Outline-Daten
     * @param mainWindow das Hauptfenster der Anwendung
     * @param treeModel das TreeModel des JTrees
     * @param imageLibrary der Bilderordner, aus dem Abbildungen eingefügt werden
     */
    public LATEXParser(OutlineController outlineController, JFrame mainWindow, TreeModel treeModel,
                       ImageLibrary imageLibrary) {
        this.outlineController = outlineController;
        this.mainWindow = mainWindow;
        this.treeModel = treeModel;
        this.imageLibrary = imageLibrary;
    }

    /**
     * Exportiert den Inhalt des {@link OutlineController} in LATEX-Format
     *
     * <p>
     *   Öffnet nur noch den Dialog für den Speicherort (siehe {@link #exportAsLatexDialog()}).
     *   Vorlage und alle weiteren Angaben stammen aus den Formalitäten; die eigentliche Erzeugung
     *   übernimmt {@link #exportTo(Path)}.
     * </p>
     *
     * @throws IOException wenn beim Schreiben auf der Festplatte ein fehler auftrit
     */
    public void exportAsLatex() throws IOException {

        File saveLocation = exportAsLatexDialog();
        if (saveLocation == null) {
            return;
        }

        exportTo(saveLocation.toPath());
    }

    /**
     * Schreibt den Inhalt des Outliners ohne jeden Dialog als LATEX-Dokument in die angegebene Datei.
     *
     * <p>
     *   Fehlende Verzeichnisse werden angelegt, eine vorhandene Datei wird überschrieben.
     *   Der Bilderordner wird vorher neu eingelesen und nach {@code <Zielverzeichnis>/img/}
     *   kopiert, damit {@code \includegraphics{img/...}} die Bilder findet. Kürzel wie
     *   {@code \fig:board} in den Notizen werden durch die {@code figure}-Umgebung des
     *   Bildes mit diesem Label ersetzt (siehe {@link FigureReferenceExpander}).
     *   Das Logo der Titelseite wird über sein Label aus den Formalitäten gewählt
     *   (siehe {@link #buildLogoCommand()}); ist keines gewählt, bleibt der Platz leer.
     * </p>
     *
     * @param texFile die Zieldatei
     * @throws IOException wenn beim Schreiben auf der Festplatte ein Fehler auftritt
     */
    public void exportTo(Path texFile) throws IOException {
        Objects.requireNonNull(texFile, "texFile");

        // Momentaufnahme der Formalitäten: der gesamte Export verwendet denselben Stand
        settings = outlineController.getLatexSettings();

        Path target = texFile.toAbsolutePath();
        Path directory = target.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }

        // Auch von außen in img/ kopierte Bilder berücksichtigen (Labels für Kürzel und Logo)
        imageLibrary.refresh();
        if (directory != null) {
            imageLibrary.copyImagesTo(directory);
        }
        figures = new FigureReferenceExpander(imageLibrary);

        // Quellen: Kürzel \cite:key auflösen und die .bib-Datei neben die .tex-Datei schreiben
        sources = BibtexWriter.usable(outlineController.getSources());
        citations = new CitationExpander(sources);
        if (!sources.isEmpty() && directory != null) {
            Files.writeString(directory.resolve(BIB_FILE + ".bib"), BibtexWriter.write(sources),
                    StandardCharsets.UTF_8);
        }

        String body = toLatexString((OutlineNode) treeModel.getRoot());
        String fullDocument = buildFullLatexDocument(body);

        Files.writeString(target, fullDocument);
    }

    /**
     * Erstellt einen String bestehend aus den Knoten des Outliners (siehe {@link #getNodes(OutlineNode)}).
     *
     * <p>Bei {@link PageBreakMode#BEFORE_EACH_SECTION} beginnt jeder Haupteintrag außer dem ersten auf
     * einer neuen Seite (der erste folgt direkt auf Titel bzw. Inhaltsverzeichnis).</p>
     *
     * @param node der Wurzelknoten der zu parsenden Elemente
     * @return einen String nach dem oben beschriebenen Muster
     */
    private String toLatexString(OutlineNode node) {
        StringBuilder latexStr = new StringBuilder();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (i > 0 && settings.pageBreakMode() == PageBreakMode.BEFORE_EACH_SECTION) {
                latexStr.append("\\clearpage\n\n");
            }
            latexStr.append(getNodes((OutlineNode) node.getChildAt(i)));
        }
        return latexStr.toString();
    }

    /**
     * Erstellt aus einem Knoten einen String im LATEX-Format.
     * <p>
     * Je nach Ebene des Knotens wird dieser als \section, \subsection oder \subsubsection dargestellt.
     * Der Titel des Knotens bildet dabei den Titel der Section und der Inhalt des Textfeldes bildet den Inhalt der Section.
     * </p>
     *
     * @param node Knoten der geparst werden soll
     * @return Knoten als LATEX-String im oben beschriebenen Format
     */
    private String getNodes(OutlineNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.getLevel() == 1)
            sb.append("\\section{").append(node.getTitle()).append("}\n");
        else if (node.getLevel() == 2)
            sb.append("\\subsection{").append(node.getTitle()).append("}\n");
        else
            sb.append("\\subsubsection{").append(node.getTitle()).append("}\n");

        if (!node.getContent().isEmpty() && !node.getContent().isBlank())
            sb.append(figures.expand(citations.expand(node.getContent()))).append("\n");
        sb.append("\n");

        for (int i = 0; i < node.getChildCount(); i++) {
            sb.append(getNodes((OutlineNode) node.getChildAt(i)));
        }
        return sb.toString();
    }

    /**
     * Öffnet einen Dialog, bei dem der Pfad zum Speichern des LATEX-Dokuments ermittelt wird.
     *
     * <p>Die Endung {@code .tex} wird nur angehängt, wenn der Benutzer sie nicht selbst angegeben hat.</p>
     *
     * @return Ort an dem das Dokument gespeichert werden soll, oder {@code null} bei Abbruch
     */
    private File exportAsLatexDialog() {
        CFileChooser fileChooser = new CFileChooser("Export als Latex",
                new FileNameExtensionFilter("Latex_Dateien (*.tex)", "tex")
        );

        int result = fileChooser.getSaveResult(mainWindow);
        if (result == JFileChooser.APPROVE_OPTION) {
            File chosen = fileChooser.getFile();
            if (chosen == null) {
                return null;
            }
            String path = chosen.getAbsolutePath();
            return path.toLowerCase().endsWith(".tex") ? chosen : new File(path + ".tex");
        }
        return null;
    }

    /**
     * Erstellt das komplette LATEX-Dokument in der Vorlage aus den Formalitäten
     * (siehe {@link #buildStandardLatexDocument(String)} und {@link #buildExpertLatexDocument(LatexMetadata, String)}).
     *
     * @param body die Knoten im LATEX-Format
     *
     * @return den fertigen String in der gewählten Vorlage
     */
    private String buildFullLatexDocument(String body) {

        if (settings.mode() == LatexExportMode.EXPERT) {
            return buildExpertLatexDocument(settings.metadata(), body);
        }

        return buildStandardLatexDocument(body);
    }

    /**
     * Bau eines Standard-LATEX-Dokuments
     *
     * <p>Titel ist die Überschrift des Outliners, die Autoren kommen aus den Formalitäten (mehrere werden mit
     * {@code \and} getrennt). Papierformat und Seitenzahlen folgen den Formalitäten.</p>
     *
     * @param body Knoten als LATEX-String
     * @return String des fertigen Standard-Dokuments
     */
    private String buildStandardLatexDocument(String body) {

        String rootTitle = outlineController.getRootNode().getTitle();

        String heute = "\\today";

        String authors = String.join(" \\and ",
                settings.metadata().autoren().stream().map(this::escapeLatex).toList());

        StringBuilder tex = new StringBuilder();

        tex.append(isA4() ? "\\documentclass[a4paper]{article}\n" : "\\documentclass{article}\n");
        tex.append("\\usepackage{graphicx} % Required for inserting images\n");
        tex.append("\\usepackage{float} % Platzierung [H] für Abbildungen\n");
        if (!sources.isEmpty()) {
            tex.append("\\usepackage{url} % \\url im Literaturverzeichnis\n");
        }
        tex.append("\\usepackage{listings} % Codeblöcke (lstlisting)\n");
        tex.append("\\usepackage{amsmath}\n");
        tex.append("\\usepackage{amssymb}\n");
        if (!isA4()) {
            tex.append("\\usepackage[").append(paperOptions()).append("]{geometry}\n");
        }
        tex.append("\\title{").append(escapeLatex(rootTitle)).append("}\n");
        tex.append("\\author{").append(authors).append("}\n");
        tex.append("\\date{").append(heute).append("}\n\n");

        tex.append("\\begin{document}\n");
        if (!settings.showPageNumbers()) {
            tex.append("\\pagenumbering{gobble}\n");
        }
        tex.append("\\maketitle\n\n");

        tex.append(body);
        tex.append(buildBibliography());

        tex.append("\\end{document}\n");

        return tex.toString();
    }

    /**
     * Erzeugt ein komplettes LATEX-Dokument mit Informationen wie Fachbereich, Abteilung, Autor, ... (siehe {@link LatexSettings})
     *
     * <p>Titelseite samt Inhaltsverzeichnis gibt es nur, wenn sie in den Formalitäten eingeschaltet ist.
     * Papierformat und Seitenzahlen folgen ebenfalls den Formalitäten.</p>
     *
     * <p>Das Dokument basiert auf der Klasse {@code article}; alle Zähler und Kopfzeilen-Befehle
     * sind daher auf {@code section} statt {@code chapter} ausgelegt (ein {@code chapter}-Zähler
     * existiert in {@code article} nicht und würde die Kompilierung abbrechen).</p>
     *
     * @param metadata allgemeine Informationen für das LATEX-Dokument
     * @param body die Knoten in LATEX-Form (siehe {@link #getNodes(OutlineNode)}
     * @return einen String mit einem kompletten LATEX-Dokument
     */
    private String buildExpertLatexDocument(LatexMetadata metadata, String body) {

        StringBuilder tex = new StringBuilder();

        tex.append("\\documentclass[12pt]{article}\n\n");

        tex.append("% ---- Encoding & Sprache ----\n");
        tex.append("\\usepackage[utf8]{inputenc}\n");
        tex.append("\\usepackage[T1]{fontenc}\n");
        tex.append("\\usepackage[ngerman]{babel}\n\n");

        tex.append("% ---- Grafiken, Farben, Layout ----\n");
        tex.append("\\usepackage{graphicx}\n");
        tex.append("\\usepackage{xcolor}\n");
        tex.append("\\usepackage{geometry}\n");
        tex.append("\\usepackage{calc}\n");
        tex.append("\\geometry{").append(paperOptions()).append(", margin=2.5cm}\n\n");

        tex.append("% ---- Hintergrundbild (optional, siehe \\BackgroundWave) ----\n");
        tex.append("\\usepackage{eso-pic}\n");
        tex.append("\\newcommand\\BackgroundWave{%\n");
        tex.append("\t\\put(0,0){%\n");
        tex.append("\t\t\\parbox[b][\\paperheight]{\\paperwidth}{%\n");
        tex.append("\t\t\t\\vfill\n");
        tex.append("\t\t\t\\centering\n");
        tex.append("\t\t\t\\vspace{12.0cm}\n");
        tex.append("\t\t\t\\includegraphics[width=\\paperwidth]{logo/hsel-welle-grey}%\n");
        tex.append("\t\t\t\\vfill\n");
        tex.append("}}}\n\n");

        tex.append("% ---- Fancy Boxes für Beispiele ----\n");
        tex.append("\\usepackage{etoolbox}\n");
        tex.append("\\usepackage[framemethod=TikZ]{mdframed}\n\n");
        tex.append("\\newcounter{exmp}[section]\\setcounter{exmp}{0}\n");
        tex.append("\\renewcommand{\\theexmp}{\\arabic{section}.\\arabic{exmp}}\n\n");
        tex.append("\\newenvironment{exmp}[2][]{%\n");
        tex.append("\t\\refstepcounter{exmp}\n");
        tex.append("\t\\ifstrempty{#1}%\n");
        tex.append("\t{\\mdfsetup{%\n");
        tex.append("\t\t\tframetitle={%\n");
        tex.append("\t\t\t\t\\tikz[baseline=(current bounding box.east),outer sep=0pt]\n");
        tex.append("\t\t\t\t\\node[anchor=east,rectangle,fill=blue!20]\n");
        tex.append("\t\t\t\t{\\strut Beispiel~\\theexmp};}\n");
        tex.append("\t\t}%\n");
        tex.append("\t}{\\mdfsetup{%\n");
        tex.append("\t\t\tframetitle={%\n");
        tex.append("\t\t\t\t\\tikz[baseline=(current bounding box.east),outer sep=0pt]\n");
        tex.append("\t\t\t\t\\node[anchor=east,rectangle,fill=blue!20]\n");
        tex.append("\t\t\t\t{\\strut Beispiel~\\theexmp:~#1};}%\n");
        tex.append("\t\t}%\n");
        tex.append("\t}%\n");
        tex.append("\t\\mdfsetup{%\n");
        tex.append("\t\tnobreak=true,\n");
        tex.append("\t\tinnertopmargin=4pt,linecolor=blue!20,%\n");
        tex.append("\t\tlinewidth=2pt,topline=true,%\n");
        tex.append("\t\tframetitleaboveskip=\\dimexpr-\\ht\\strutbox\\relax%\n");
        tex.append("\t}\n");
        tex.append("\t\\begin{mdframed}[]\\relax%\n");
        tex.append("\t\t\\label{#2}}{\\end{mdframed}}\n\n");

        tex.append("\\usepackage{sectsty}\n");
        tex.append("\\allsectionsfont{\\sffamily}\n\n");

        tex.append("% ---- Kopf-/Fußzeile ----\n");
        tex.append("\\usepackage{fancyhdr}\n");
        tex.append("\\pagestyle{fancy}\n");
        tex.append("\\setlength{\\headheight}{14.5pt}\n");
        tex.append("\\fancyhead{}\n");
        tex.append("\\fancyfoot{}\n");
        tex.append("\\renewcommand{\\sectionmark}[1]{\\markright{\\thesection.\\ #1}{}}\n");
        tex.append("\\fancyhead[RO,LE]{\\thepage}\n");
        tex.append("\\fancyhead[LO]{\\rightmark}\n");
        tex.append("\\fancyhead[RE]{\\leftmark}\n");
        tex.append("\\renewcommand{\\headrulewidth}{0.4pt}\n\n");
        tex.append("\\fancypagestyle{plain}{%\n");
        tex.append("\t\\fancyhf{}\n");
        tex.append("\t\\fancyhead[RO,LE]{\\thepage}\n");
        tex.append("\t\\renewcommand{\\headrulewidth}{0.4pt}\n");
        tex.append("\t\\renewcommand{\\footrulewidth}{0pt}}\n\n");

        tex.append("% ---- Listen-Spacing ----\n");
        tex.append("\\makeatletter\n");
        tex.append("\\let\\orig@Itemize =\\itemize\n");
        tex.append("\\let\\orig@Enumerate =\\enumerate\n");
        tex.append("\\let\\orig@Description =\\description\n");
        tex.append("\\def\\Nospacing{\\itemsep=0pt\\topsep=0pt\\partopsep=0pt%\n");
        tex.append("\\parskip=0pt\\parsep=0pt}\n\n");
        tex.append("\\def\\noitemsep{\n");
        tex.append("\\renewenvironment{itemize}{\\orig@Itemize\\Nospacing}{\\endlist}\n");
        tex.append("\\renewenvironment{enumerate}{\\orig@Enumerate\\Nospacing}{\\endlist}\n");
        tex.append("\\renewenvironment{description}{\\orig@Description\\Nospacing}%\n");
        tex.append("{\\endlist}\n}\n\n");
        tex.append("\\def\\doitemsep{\n");
        tex.append("\\renewenvironment{itemize}{\\orig@Itemize}{\\endlist}\n");
        tex.append("\\renewenvironment{enumerate}{\\orig@Enumerate}{\\endlist}\n");
        tex.append("\\renewenvironment{description}{\\orig@Description}{\\endlist}\n}\n");
        tex.append("\\makeatother\n\n");

        tex.append("% ---- Bildgrößen ----\n");
        tex.append("\\newlength{\\OneFig}\n");
        tex.append("\\setlength{\\OneFig}{\\textwidth -10mm}\n");
        tex.append("\\newlength{\\TwoFig}\n");
        tex.append("\\setlength{\\TwoFig}{(\\textwidth -10mm) /2}\n");
        tex.append("\\newlength{\\ThreeFig}\n");
        tex.append("\\setlength{\\ThreeFig}{(\\textwidth -10mm) /3}\n");
        tex.append("\\newlength{\\FourFig}\n");
        tex.append("\\setlength{\\FourFig}{(\\textwidth -10mm) /4}\n\n");

        tex.append("% ---- Listings ----\n");
        tex.append("\\usepackage{listings}\n");
        tex.append("\\usepackage{caption}\n");
        tex.append("\\usepackage{csquotes}\n");
        tex.append("\\usepackage{amsmath}\n");
        tex.append("\\usepackage{amssymb}\n");
        tex.append("\\usepackage{float}\n\n");
        tex.append("\\definecolor{dkgreen}{rgb}{0,.6,0}\n");
        tex.append("\\definecolor{dkblue}{rgb}{0,0,.6}\n");
        tex.append("\\definecolor{dkyellow}{cmyk}{0,0,.8,.3}\n");
        tex.append("\\lstset{\n");
        tex.append("\tlanguage=C++,\n");
        tex.append("\tframe=single,\n");
        tex.append("\tnumbers=left,\n");
        tex.append("\tnumberstyle=\\tiny\\color{gray!90!black},\n");
        tex.append("\tbasicstyle=\\ttfamily\\footnotesize,\n");
        tex.append("\ttabsize=4,\n");
        tex.append("\tshowstringspaces=false,\n");
        tex.append("\tcaptionpos=t,\n");
        tex.append("\trulecolor=\\color{lightgray!40},\n");
        tex.append("\tkeywordstyle=\\color{dkblue},\n");
        tex.append("\tstringstyle=\\color{red},\n");
        tex.append("\tcommentstyle=\\color{dkgreen}\n");
        tex.append("}\n");
        tex.append("\\DeclareCaptionFormat{listing}{\t\\par\\vskip1pt#1#2#3}\n");
        tex.append("\\captionsetup[lstlisting]\n{\n");
        tex.append("\tformat=listing,\n");
        tex.append("\tsinglelinecheck=false, \n");
        tex.append("\tmargin=1em, \t\n");
        tex.append("\tlabelsep=colon,\n");
        tex.append("\tjustification=centering\n}\n\n");

        tex.append("\\usepackage{hyperref}\n\n");

        tex.append("\\begin{document}\n\n");

        if (!settings.showPageNumbers()) {
            tex.append("\\pagenumbering{gobble}\n\n");
        }

        if (settings.coverPage()) {
            tex.append(buildTitlePage(metadata));
            tex.append("\\newpage\n\n");
            tex.append("\\thispagestyle{empty}\n");
            tex.append("\\tableofcontents\n");
            tex.append("\\newpage\n\n");
            tex.append("\\setcounter{page}{1}\n\n");
        }

        tex.append(body);
        tex.append(buildBibliography());

        tex.append("\\end{document}\n");

        return tex.toString();
    }

    /**
     * Erzeugt die Titelseite eines LATEX-Dokuments
     * @param metadata metadaten für das LATEX-Dokument
     * @return fertige Titelseite; das Logo oben links kommt aus {@link #buildLogoCommand()}
     */
    private String buildTitlePage(LatexMetadata metadata) {

        StringBuilder sb = new StringBuilder();

        sb.append("\\begin{titlepage}\n");
        sb.append("\\vspace{-0.5cm}\n");
        sb.append("\\hspace{-2.0cm}\n");
        sb.append("\\begin{tabular}{p{8.0cm} p{8.0cm}}\n");
        sb.append("  ").append(buildLogoCommand()).append(" &\n");
        sb.append("   \\parbox[b]{8.0cm}{\n");
        sb.append("     {\\large \t").append(escapeLatex(metadata.fachbereich())).append(" }\\\\\n");
        sb.append("     {\\large \t").append(escapeLatex(metadata.abteilung())).append(" }     \n");
        sb.append("    } \\\\\n");
        sb.append("   \\\\\n");
        sb.append("   \\hline\n");
        sb.append("\\end{tabular}\n");
        sb.append("%\n");
        sb.append("\\begin{center}\n");
        sb.append("\\vspace{2.5cm}%\n");
        sb.append("\\LARGE{\\textsc{ %\n");
        sb.append(escapeLatex(titleOf(metadata))).append("}}\\\\\n");
        sb.append("\\vspace{2.5cm}%\n");
        sb.append("\\LARGE{\\textsc{%\n");
        sb.append("{").append(escapeLatex(metadata.artDerArbeit())).append("} % ODER: Projektarbeit\n");
        sb.append("}}\\\\\n");
        sb.append("\\vspace{2cm}%\n");
        sb.append("\\large\n");
        sb.append("Vorgelegt von\\\\\n");

        for (String autor : metadata.autoren()) {
            sb.append(escapeLatex(autor)).append(" \\\\\n");
        }

        sb.append("\\vspace{1cm} \n");
        sb.append(escapeLatex(metadata.ort())).append(", \\today \\\\\n");
        sb.append("\\vspace{2.5cm}%\n");
        sb.append("Betreut von\\\\ ");

        for (int i = 0; i < metadata.betreuer().size(); i++) {
            sb.append(escapeLatex(metadata.betreuer().get(i)));
            if (i < metadata.betreuer().size() - 1) {
                sb.append(" \\\\ ");
            }
        }

        sb.append("\n\\end{center}\n");
        sb.append("\\normalsize\n");
        sb.append("\\end{titlepage}\n\n");

        return sb.toString();
    }

    /**
     * Liefert den LATEX-Befehl für das Logo oben links auf der Titelseite.
     *
     * <p>Welches Bild gemeint ist, steht als Label in den Formalitäten (z. B. {@code fig:logo},
     * siehe {@link LatexSettings#logoLabel()}). Es wird in der {@link ImageLibrary} gesucht; die Datei liegt
     * nach {@link #exportTo(Path)} bereits unter {@code img/} neben der
     * {@code .tex}-Datei. Das Logo wird auf 6 cm Breite und höchstens 3 cm Höhe
     * (bei erhaltenem Seitenverhältnis) skaliert, damit auch hohe Logos die Titelseite nicht sprengen.</p>
     *
     * <p>Ist kein Label eingetragen oder passt es zu keinem Bild, wird eine leere Box geliefert,
     * damit das Dokument trotzdem kompiliert und die Kopftabelle der Titelseite erhalten bleibt.</p>
     *
     * @return der {@code \includegraphics}-Befehl oder {@code \mbox{}}
     */
    private String buildLogoCommand() {
        String label = settings.logoLabel();
        if (!label.isEmpty()) {
            for (ImageEntry entry : imageLibrary.getEntries()) {
                if (label.equals(entry.getLabel())) {
                    return "\\includegraphics[width=6.0cm,height=3.0cm,keepaspectratio]{"
                            + entry.getLatexPath() + "}";
                }
            }
        }
        return "\\mbox{}";
    }

    /**
     * Das Literaturverzeichnis am Ende des Dokuments, klassisches BibTeX. Gibt es keine Quellen, bleibt es leer.
     * Aufgeführt werden die Quellen, die im Text mit {@code \cite:key} zitiert wurden.
     */
    private String buildBibliography() {
        if (sources.isEmpty()) {
            return "";
        }
        return "\n"
                + "\\ifdefined\\phantomsection\\phantomsection\\fi\n"
                + "\\addcontentsline{toc}{section}{\\refname}\n"
                + "\\bibliographystyle{plain}\n"
                + "\\bibliography{" + BIB_FILE + "}\n\n";
    }

    /** Seitenbreite der Formalitäten in Millimetern. */
    private double pageWidthMm() {
        double w = settings.pageWidth();
        String unit = settings.pageUnit() == null ? "mm" : settings.pageUnit().toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "cm" -> w * 10.0;
            case "inch", "in" -> w * 25.4;
            default -> w;
        };
    }

    /** {@code true}, wenn die Seitenbreite der von DIN A4 (210 mm) entspricht. */
    private boolean isA4() {
        return Math.abs(pageWidthMm() - 210.0) < 0.5;
    }

    /**
     * Papiergröße für das geometry-Paket. A4 wird als {@code a4paper} geschrieben, sonst wird aus der Breite
     * die Höhe im Seitenverhältnis der A-Reihe (1 : √2) berechnet.
     */
    private String paperOptions() {
        if (isA4()) {
            return "a4paper";
        }
        double widthMm = pageWidthMm();
        double heightMm = widthMm * Math.sqrt(2.0);
        return String.format(Locale.ROOT, "paperwidth=%.1fmm, paperheight=%.1fmm", widthMm, heightMm);
    }

    /** Titel der Titelseite: Angabe aus den Formalitäten, sonst die Überschrift des Outliners. */
    private String titleOf(LatexMetadata metadata) {
        String t = metadata.titel();
        if (t == null || t.isBlank()) {
            return outlineController.getRootNode().getTitle();
        }
        return t;
    }

    /**
     * Ersetzt Textelemente die im LATEX-Dokument anders dargestellt werden müssen.
     *
     * <p>Jedes Zeichen wird genau einmal ersetzt. Eine Kette von {@code replace}-Aufrufen
     * würde die geschweiften Klammern des bereits eingefügten {@code \textbackslash{}}
     * ein zweites Mal escapen.</p>
     *
     * @param input Text der in LATEX Form angepasst werden soll
     * @return angepasster Text
     */
    private String escapeLatex(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\textbackslash{}");
                case '&'  -> sb.append("\\&");
                case '%'  -> sb.append("\\%");
                case '$'  -> sb.append("\\$");
                case '#'  -> sb.append("\\#");
                case '_'  -> sb.append("\\_");
                case '{'  -> sb.append("\\{");
                case '}'  -> sb.append("\\}");
                case '~'  -> sb.append("\\textasciitilde{}");
                case '^'  -> sb.append("\\textasciicircum{}");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}