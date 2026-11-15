package model.image;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verwaltet den Bilderordner {@code img/} und die Angaben zu den darin liegenden Abbildungen.
 *
 * <p>Die Bilder liegen als ganz normale Dateien in diesem Ordner, du findest sie also
 * im Datei-Browser deines Betriebssystems und kannst dort auch selbst Dateien
 * hineinkopieren; {@link #refresh()} übernimmt sie. Der Ordner liegt neben der
 * {@code .jar}-Datei, beim Start aus der IDE im Arbeitsverzeichnis
 * (siehe {@link #defaultDirectory()}).</p>
 *
 * <p>Beschriftung, Label, Breite und Platzierung stehen in der Datei
 * {@value #METADATA_FILE} im selben Ordner. Kopierst du den Ordner {@code img/}
 * woanders hin, wandern die Angaben also mit.</p>
 *
 * <p>Nur für LaTeX nutzbare Formate werden verwaltet: PNG, JPG und PDF. GIF und BMP
 * werden beim Import in PNG umgewandelt. Dateinamen werden beim Import auf
 * Buchstaben, Ziffern, {@code _} und {@code -} bereinigt, weil Leerzeichen,
 * Umlaute und Punkte in {@code \includegraphics} Probleme machen.</p>
 *
 * <p>Alle Methoden außer {@link #importFile(Path)} gehören in den Event-Dispatch-Thread.
 * {@code importFile} verändert nur Dateien und darf im Hintergrund laufen.</p>
 */
public class ImageLibrary {

    /** Name des Bilderordners. */
    public static final String FOLDER_NAME = "img";

    /** Name der Metadaten-Datei im Bilderordner. */
    public static final String METADATA_FILE = "figures.properties";

    private static final Set<String> NATIVE_FORMATS = Set.of("png", "jpg", "jpeg", "pdf");
    private static final Set<String> CONVERTIBLE_FORMATS = Set.of("gif", "bmp");
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_.\\-]*");
    private static final String[] METADATA_KEYS = {"placement", "width", "caption", "label"};

    private final Path directory;
    private final Map<String, ImageEntry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Dateinamen, deren Metadaten beim nächsten Speichern entfernt werden (gelöscht/umbenannt). */
    private final Set<String> forgotten = new HashSet<>();

    /**
     * @param directory der Bilderordner; wird bei Bedarf angelegt
     */
    public ImageLibrary(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /**
     * Bestimmt den Standard-Bilderordner: {@code img/} neben der {@code .jar}-Datei,
     * beim Start aus der IDE (Klassen in einem Verzeichnis) {@code img/} im Arbeitsverzeichnis.
     *
     * @return der Pfad des Bilderordners (existiert ggf. noch nicht)
     */
    public static Path defaultDirectory() {
        Path base = Path.of("").toAbsolutePath();
        try {
            var source = ImageLibrary.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path code = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(code) && code.getParent() != null) {
                    base = code.getParent();
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fallback: Arbeitsverzeichnis
        }
        return base.resolve(FOLDER_NAME);
    }

    /** @return der Bilderordner als absoluter Pfad */
    public Path getDirectory() {
        return directory;
    }

    /** @return alle Abbildungen, alphabetisch nach Dateinamen (Stand des letzten {@link #refresh()}) */
    public List<ImageEntry> getEntries() {
        return new ArrayList<>(entries.values());
    }

    // ── Einlesen ─────────────────────────────────────────────────────────────

    /**
     * Liest den Ordner neu ein: neue Dateien werden aufgenommen, entfernte vergessen,
     * Dateigröße und Änderungsdatum aktualisiert. Bereits bekannte Einträge bleiben
     * dieselben Objekte, ihre Angaben bleiben also erhalten.
     *
     * <p>Neuen Bildern werden Angaben aus der Metadaten-Datei zugeordnet, fehlt dort
     * etwas, wird ein eindeutiges Label aus dem Dateinamen vorgeschlagen
     * (z. B. {@code fig:ueberblick_ui}).</p>
     *
     * @return die aktuellen Abbildungen, alphabetisch sortiert
     * @throws IOException wenn der Ordner nicht angelegt oder gelesen werden kann
     */
    public List<ImageEntry> refresh() throws IOException {
        Files.createDirectories(directory);
        Properties props = loadProperties();

        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(ImageLibrary::isSupportedFile)
                    .collect(Collectors.toList());
        }

        Set<String> onDisk = new HashSet<>();
        List<ImageEntry> added = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            onDisk.add(name);
            ImageEntry entry = entries.get(name);
            if (entry == null) {
                entry = new ImageEntry(file.toAbsolutePath().normalize());
                readMetadata(entry, props);
                entries.put(name, entry);
                added.add(entry);
            }
            entry.setFileStats(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
        }
        entries.keySet().removeIf(name -> !onDisk.contains(name));

        for (ImageEntry entry : added) {
            if (entry.getLabel().isBlank() || isLabelTaken(entry.getLabel(), entry)) {
                entry.setLabel(suggestLabel(entry));
            }
        }
        if (!added.isEmpty()) {
            save();
        }
        return getEntries();
    }

    // ── Hinzufügen, Umbenennen, Löschen ──────────────────────────────────────

    /**
     * Kopiert eine Datei in den Bilderordner.
     *
     * <p>PNG, JPG und PDF werden kopiert, GIF und BMP als PNG gespeichert. Der Name wird
     * bereinigt und bei Konflikten durchnummeriert ({@code bild_2.png}). Liegt die Datei
     * bereits im Bilderordner, passiert nichts. Danach {@link #refresh()} aufrufen.</p>
     *
     * @param source die zu importierende Datei
     * @return der Pfad der Datei im Bilderordner
     * @throws IOException wenn das Format nicht unterstützt wird oder das Kopieren fehlschlägt
     */
    public Path importFile(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("Keine Datei.");
        }
        Files.createDirectories(directory);

        Path absolute = source.toAbsolutePath().normalize();
        if (directory.equals(absolute.getParent())) {
            return absolute;
        }

        String fileName = source.getFileName().toString();
        String extension = extensionOf(fileName);
        String base = sanitizeBaseName(stripExtension(fileName));

        if (NATIVE_FORMATS.contains(extension)) {
            Path target = uniqueTarget(base, extension);
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return target;
        }
        if (CONVERTIBLE_FORMATS.contains(extension)) {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                throw new IOException("Das Bild konnte nicht gelesen werden.");
            }
            Path target = uniqueTarget(base, "png");
            ImageIO.write(image, "png", target.toFile());
            return target;
        }
        throw new IOException("Das Format ." + extension + " wird von LaTeX nicht unterstützt "
                + "(erlaubt: PNG, JPG, PDF; GIF und BMP werden zu PNG umgewandelt).");
    }

    /**
     * Benennt die Bilddatei um. Beschriftung, Label und übrige Angaben bleiben erhalten.
     *
     * @param entry   die umzubenennende Abbildung
     * @param newBase der neue Name ohne Endung; wird bereinigt (siehe {@link #sanitizeBaseName(String)})
     * @throws IOException wenn der Name schon vergeben ist oder das Umbenennen fehlschlägt
     */
    public void rename(ImageEntry entry, String newBase) throws IOException {
        String oldName = entry.getFileName();
        Path target = directory.resolve(sanitizeBaseName(newBase) + "." + entry.getExtension());
        if (target.equals(entry.getFile())) {
            return;
        }
        if (Files.exists(target)) {
            throw new IOException("Eine Datei mit diesem Namen existiert bereits.");
        }
        Files.move(entry.getFile(), target);
        entries.remove(oldName);
        forgotten.add(oldName);
        entry.setFile(target);
        entries.put(entry.getFileName(), entry);
        save();
    }

    /**
     * Entfernt die Bilddatei aus dem Ordner, nach Möglichkeit in den Papierkorb des
     * Betriebssystems, sonst endgültig.
     *
     * @param entry die zu entfernende Abbildung
     * @throws IOException wenn die Datei nicht entfernt werden kann
     */
    public void delete(ImageEntry entry) throws IOException {
        boolean trashed = false;
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                trashed = Desktop.getDesktop().moveToTrash(entry.getFile().toFile());
            }
        } catch (RuntimeException ignored) {
            // dann endgültig löschen
        }
        if (!trashed) {
            Files.deleteIfExists(entry.getFile());
        }
        entries.remove(entry.getFileName());
        forgotten.add(entry.getFileName());
        save();
    }

    // ── Label ────────────────────────────────────────────────────────────────

    /**
     * Prüft, ob ein Label gültig ist: Buchstaben, Ziffern sowie {@code : _ - .}, beginnend
     * mit Buchstabe oder Ziffer. Ein leeres Label ist erlaubt (dann gibt es kein {@code \label}).
     *
     * @param label das zu prüfende Label
     * @return {@code true}, wenn gültig oder leer
     */
    public static boolean isValidLabel(String label) {
        return label == null || label.isEmpty() || LABEL_PATTERN.matcher(label).matches();
    }

    /**
     * @param label  das Label
     * @param except die Abbildung, die selbst nicht mitzählt (darf {@code null} sein)
     * @return {@code true}, wenn eine andere Abbildung bereits dieses Label trägt
     */
    public boolean isLabelTaken(String label, ImageEntry except) {
        for (ImageEntry other : entries.values()) {
            if (other != except && label.equals(other.getLabel())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schlägt ein eindeutiges Label aus dem Dateinamen vor, z. B. {@code fig:ueberblick_ui}.
     *
     * @param entry die Abbildung
     * @return ein noch nicht vergebenes Label
     */
    public String suggestLabel(ImageEntry entry) {
        String slug = stripExtension(entry.getFileName()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        String base = "fig:" + (slug.isEmpty() ? "bild" : slug);
        String candidate = base;
        for (int i = 2; isLabelTaken(candidate, entry); i++) {
            candidate = base + "_" + i;
        }
        return candidate;
    }

    // ── Speichern der Angaben ────────────────────────────────────────────────

    /**
     * Schreibt Beschriftung, Label, Breite und Platzierung aller Abbildungen in die
     * Metadaten-Datei im Bilderordner. Angaben zu Dateien, die gerade nicht im Ordner
     * liegen, bleiben erhalten.
     *
     * @throws IOException wenn die Datei nicht geschrieben werden kann
     */
    public void save() throws IOException {
        Files.createDirectories(directory);
        Properties props = loadProperties();

        for (String name : forgotten) {
            for (String key : METADATA_KEYS) {
                props.remove(name + "." + key);
            }
        }
        forgotten.clear();

        for (ImageEntry entry : entries.values()) {
            String name = entry.getFileName();
            props.setProperty(name + ".placement", entry.getPlacement().name());
            props.setProperty(name + ".width", String.valueOf(entry.getWidthPercent()));
            props.setProperty(name + ".caption", entry.getCaption());
            props.setProperty(name + ".label", entry.getLabel());
        }

        try (Writer writer = Files.newBufferedWriter(directory.resolve(METADATA_FILE), StandardCharsets.UTF_8)) {
            props.store(writer, "TeXlinr - Angaben zu den Abbildungen (Platzierung, Breite, Beschriftung, Label)");
        }
    }

    // ── Build ────────────────────────────────────────────────────────────────

    /**
     * Kopiert alle Bilder nach {@code <targetRoot>/img/}, damit LaTeX sie über
     * {@code img/dateiname.png} findet. Unveränderte Dateien werden übersprungen.
     *
     * @param targetRoot das Verzeichnis, in dem die {@code .tex}-Datei liegt
     * @throws IOException wenn das Kopieren fehlschlägt
     */
    public void copyImagesTo(Path targetRoot) throws IOException {
        Path target = targetRoot.resolve(ImageEntry.LATEX_FOLDER);
        Files.createDirectories(target);
        for (ImageEntry entry : entries.values()) {
            Path destination = target.resolve(entry.getFileName());
            boolean current = Files.exists(destination)
                    && Files.size(destination) == entry.getSize()
                    && Files.getLastModifiedTime(destination).toMillis() == entry.getLastModified();
            if (!current) {
                Files.copy(entry.getFile(), destination,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    // ── Hilfen ───────────────────────────────────────────────────────────────

    /**
     * @param fileName ein Dateiname
     * @return {@code true}, wenn die Datei importiert werden kann (PNG, JPG, PDF, GIF, BMP)
     */
    public static boolean canImport(String fileName) {
        String extension = extensionOf(fileName);
        return NATIVE_FORMATS.contains(extension) || CONVERTIBLE_FORMATS.contains(extension);
    }

    /**
     * Macht aus einem beliebigen Namen einen für LaTeX sicheren: Umlaute werden umschrieben
     * ({@code ä -> ae}, {@code ß -> ss}), alles außer Buchstaben, Ziffern, {@code _} und
     * {@code -} wird zu {@code _}.
     *
     * @param input der gewünschte Name ohne Endung
     * @return der bereinigte Name; {@code "bild"}, wenn nichts übrig bleibt
     */
    public static String sanitizeBaseName(String input) {
        if (input == null) return "bild";
        String s = input.trim()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        s = s.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "bild" : s;
    }

    /** @return die Dateiendung in Kleinbuchstaben ohne Punkt, leer wenn keine vorhanden */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static boolean isSupportedFile(Path file) {
        return NATIVE_FORMATS.contains(extensionOf(file.getFileName().toString()));
    }

    private Path uniqueTarget(String base, String extension) {
        Path target = directory.resolve(base + "." + extension);
        for (int i = 2; Files.exists(target); i++) {
            target = directory.resolve(base + "_" + i + "." + extension);
        }
        return target;
    }

    private static void readMetadata(ImageEntry entry, Properties props) {
        String name = entry.getFileName();
        entry.setPlacement(ImagePlacement.fromName(props.getProperty(name + ".placement"), ImagePlacement.AUTO));
        try {
            entry.setWidthPercent(Integer.parseInt(props.getProperty(name + ".width", "100").trim()));
        } catch (NumberFormatException ignored) {
            // Standardbreite bleibt
        }
        entry.setCaption(props.getProperty(name + ".caption", ""));
        entry.setLabel(props.getProperty(name + ".label", ""));
    }

    private Properties loadProperties() throws IOException {
        Properties props = new Properties();
        Path file = directory.resolve(METADATA_FILE);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IllegalArgumentException malformed) {
                return new Properties();
            }
        }
        return props;
    }
}