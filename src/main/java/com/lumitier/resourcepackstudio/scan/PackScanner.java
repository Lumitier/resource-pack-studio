package com.lumitier.resourcepackstudio.scan;

import com.lumitier.resourcepackstudio.ResourcePackStudio;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Durchsucht Pack-Ordner und baut ein Verzeichnis: welches Item hat in
 * welchem Pack eine eigene Textur.
 *
 * WAS ALS PACK ZAEHLT:
 *  - ein Ordner mit pack.mcmeta
 *  - eine .zip mit pack.mcmeta ganz oben
 *  - eine .zip, in der das Pack noch in EINEM Unterordner steckt - so
 *    werden viele Packs verteilt. Minecraft selbst laedt die nicht, die
 *    Texturen darin sind aber genauso brauchbar.
 *
 * ORDNER OHNE pack.mcmeta werden weiter durchsucht. Damit funktionieren
 * sortierte Sammlungen in Unterordnern - so wie Recursive Resources sie im
 * Spiel anzeigt. Tiefe begrenzt, damit ein Verweis auf sich selbst nicht
 * endlos laeuft.
 *
 * Die AKTIVEN Packs spielen keine Rolle: Gelesen wird direkt aus den
 * Dateien, nicht aus dem, was Minecraft gerade geladen hat.
 */
public final class PackScanner {

	private static final int MAX_DEPTH = 6;
	private static final String MCMETA = "pack.mcmeta";

	private PackScanner() {}

	/** Ein gefundenes Pack. prefix ist "" oder der Unterordner in der Zip, mit "/". */
	public record Pack(String name, Path path, boolean zip, String prefix, String source) {}

	/** Eine Textur eines Items in einem Pack. */
	public record Variant(Pack pack, String entry, boolean legacy) {}

	/** Ergebnis eines Durchlaufs. */
	public record Result(List<Pack> packs, Map<StudioItem, List<Variant>> variants,
	                     List<String> problems, long millis) {}

	/** Durchsucht alle Ordner. Darf lange dauern - nie auf dem Render-Thread rufen. */
	public static Result scan(List<Path> roots, List<StudioItem> items) {
		long start = System.currentTimeMillis();
		List<Pack> packs = new ArrayList<>();
		List<String> problems = new ArrayList<>();

		for (Path root : roots) {
			if (Files.isDirectory(root)) {
				findPacks(root, root, 0, packs, problems);
			}
		}

		Map<StudioItem, List<Variant>> variants = new LinkedHashMap<>();
		for (StudioItem item : items) {
			variants.put(item, new ArrayList<>());
		}

		for (Pack pack : packs) {
			try {
				index(pack, variants);
			} catch (Exception e) {
				problems.add(pack.name() + ": " + e.getMessage());
				ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not read pack {}", pack.path(), e);
			}
		}

		return new Result(packs, variants, problems, System.currentTimeMillis() - start);
	}

	// ------------------------------------------------------------------
	// Packs finden
	// ------------------------------------------------------------------

	private static void findPacks(Path root, Path dir, int depth, List<Pack> out, List<String> problems) {
		if (depth > MAX_DEPTH) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path p : stream) {
				String file = p.getFileName().toString();
				if (Files.isDirectory(p)) {
					if (Files.isRegularFile(p.resolve(MCMETA))) {
						out.add(new Pack(clean(file), p, false, "", source(root, p)));
					} else {
						findPacks(root, p, depth + 1, out, problems);
					}
				} else if (file.toLowerCase(Locale.ROOT).endsWith(".zip")) {
					Pack zip = zipPack(root, p, problems);
					if (zip != null) {
						out.add(zip);
					}
				}
			}
		} catch (IOException e) {
			problems.add(dir + ": " + e.getMessage());
		}
	}

	/** Prueft eine Zip: Pack ganz oben, Pack in einem Unterordner, oder gar keins. */
	private static Pack zipPack(Path root, Path p, List<String> problems) {
		String name = p.getFileName().toString();
		name = clean(name.substring(0, name.length() - 4));
		try (ZipFile zip = new ZipFile(p.toFile())) {
			if (zip.getEntry(MCMETA) != null) {
				return new Pack(name, p, true, "", source(root, p));
			}
			// In einem Unterordner? Nur EINE Ebene - tiefer verschachtelt
			// ist kein Pack mehr, sondern ein Archiv voller Dinge.
			Enumeration<? extends ZipEntry> e = zip.entries();
			while (e.hasMoreElements()) {
				String n = e.nextElement().getName();
				int slash = n.indexOf('/');
				if (slash > 0 && n.equals(n.substring(0, slash + 1) + MCMETA)) {
					return new Pack(name, p, true, n.substring(0, slash + 1), source(root, p));
				}
			}
		} catch (IOException ex) {
			problems.add(name + ": not a readable zip (" + ex.getMessage() + ")");
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Texturen suchen
	// ------------------------------------------------------------------

	private static void index(Pack pack, Map<StudioItem, List<Variant>> variants) throws IOException {
		if (pack.zip()) {
			// Alle Eintraege EINMAL einlesen, dann nur noch nachschlagen.
			// Einzeln per getEntry waere bei siebzig Items mal zwei Pfaden
			// genauso schnell - so ist aber auch Gross-/Kleinschreibung
			// abgedeckt, die manche Packs falsch haben.
			// Kleingeschrieben -> wie es WIRKLICH in der Zip steht. Spaeter
			// wird die Textur per Name gelesen, und Zips unterscheiden
			// Gross- und Kleinschreibung.
			Map<String, String> names = new HashMap<>();
			try (ZipFile zip = new ZipFile(pack.path().toFile())) {
				Enumeration<? extends ZipEntry> e = zip.entries();
				while (e.hasMoreElements()) {
					String n = e.nextElement().getName();
					names.putIfAbsent(n.toLowerCase(Locale.ROOT), n);
				}
			}
			for (Map.Entry<StudioItem, List<Variant>> en : variants.entrySet()) {
				for (StudioItem.Candidate c : en.getKey().candidates()) {
					String real = names.get((pack.prefix() + c.path()).toLowerCase(Locale.ROOT));
					if (real != null) {
						en.getValue().add(new Variant(pack, real, c.legacy()));
						break;
					}
				}
			}
		} else {
			for (Map.Entry<StudioItem, List<Variant>> en : variants.entrySet()) {
				for (StudioItem.Candidate c : en.getKey().candidates()) {
					if (Files.isRegularFile(pack.path().resolve(c.path()))) {
						en.getValue().add(new Variant(pack, c.path(), c.legacy()));
						break;
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Hilfen
	// ------------------------------------------------------------------

	/**
	 * Entfernt Minecrafts Farbcodes aus dem Namen. PvP-Packs heissen oft
	 * "§b§lPack §7[16x]" - im Bericht soll lesbar "Pack [16x]" stehen.
	 */
	static String clean(String name) {
		// Leerraum zusammenfassen: Nach dem Entfernen der Farbcodes bleiben
		// oft doppelte Leerzeichen stehen.
		return name.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
	}

	/** Wo das Pack liegt, relativ zum Wurzelordner - fuer den Bericht. */
	private static String source(Path root, Path p) {
		Path rel = root.relativize(p.getParent());
		String r = rel.toString();
		String base = root.getFileName() == null ? root.toString() : root.getFileName().toString();
		return r.isEmpty() ? base : base + "/" + r.replace('\\', '/');
	}
}
