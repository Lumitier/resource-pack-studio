package com.lumitier.resourcepackstudio.scan;

import com.lumitier.resourcepackstudio.ResourcePackStudio;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

	/**
	 * Was ein Pack fuer einen Eintrag hat.
	 *
	 * entries hat genau so viele Plaetze wie das Item Teile hat. null heisst:
	 * Dieses Bild hat das Pack nicht - dann gilt dort Vanilla. Bei einem
	 * flachen Item gibt es nur einen Platz.
	 */
	public record Variant(Pack pack, List<String> entries, boolean legacy) {

		/** Erster gefundener Eintrag - zum Wiedererkennen nach einer neuen Suche. */
		public String entry() {
			for (String e : entries) {
				if (e != null) {
					return e;
				}
			}
			return "";
		}

		/** Wie viele Bilder das Pack fuer diesen Eintrag hat. */
		public int found() {
			int n = 0;
			for (String e : entries) {
				if (e != null) {
					n++;
				}
			}
			return n;
		}
	}

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

		// Kandidaten EINMAL kleingeschrieben vorbereiten - nicht fuer jedes
		// Pack neu. Bei ueber 5000 Eintraegen und 20 Packs machte das
		// Zusammensetzen der Pfade einen guten Teil der Suchzeit aus.
		List<String[][]> paths = new ArrayList<>(items.size());
		List<boolean[][]> legacy = new ArrayList<>(items.size());
		for (StudioItem item : variants.keySet()) {
			List<StudioItem.Part> parts = item.parts();
			String[][] p = new String[parts.size()][];
			boolean[][] l = new boolean[parts.size()][];
			for (int i = 0; i < parts.size(); i++) {
				List<StudioItem.Candidate> cs = parts.get(i).candidates();
				p[i] = new String[cs.size()];
				l[i] = new boolean[cs.size()];
				for (int k = 0; k < cs.size(); k++) {
					p[i][k] = cs.get(k).path().toLowerCase(Locale.ROOT);
					l[i][k] = cs.get(k).legacy();
				}
			}
			paths.add(p);
			legacy.add(l);
		}

		for (Pack pack : packs) {
			try {
				index(pack, variants, paths, legacy);
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

	private static void index(Pack pack, Map<StudioItem, List<Variant>> variants,
	                          List<String[][]> paths, List<boolean[][]> legacyFlags) throws IOException {
		// Bei Zips alle Eintraege EINMAL einlesen, dann nur noch nachschlagen.
		// Kleingeschrieben -> wie es WIRKLICH in der Zip steht: Manche Packs
		// haben die Gross-/Kleinschreibung falsch, gelesen wird spaeter aber
		// mit dem echten Namen.
		//
		// ORDNER GENAUSO: einmal durchlaufen statt jede moegliche Datei
		// einzeln zu pruefen. Bei ueber 5000 Eintraegen (davon 4000 Sounds)
		// und 20 entpackten Packs waren das ueber 200.000 Dateizugriffe -
		// die Suche dauerte 4,6 statt 0,1 Sekunden.
		Map<String, String> names = new HashMap<>();
		if (pack.zip()) {
			try (ZipFile zip = new ZipFile(pack.path().toFile())) {
				Enumeration<? extends ZipEntry> e = zip.entries();
				String prefix = pack.prefix().toLowerCase(Locale.ROOT);
				while (e.hasMoreElements()) {
					String n = e.nextElement().getName();
					String lower = n.toLowerCase(Locale.ROOT);
					// Ohne den Unterordner gespeichert - dann passt der
					// Schluessel direkt zu den vorbereiteten Pfaden.
					if (lower.startsWith(prefix)) {
						names.putIfAbsent(lower.substring(prefix.length()), n);
					}
				}
			}
		} else {
			// Nur die Ordner, in denen gesucht wird - nicht das ganze Pack.
			for (String dir : new String[] {"assets/minecraft/textures", "assets/minecraft/sounds"}) {
				Path root = pack.path().resolve(dir);
				if (!Files.isDirectory(root)) {
					continue;
				}
				try (java.util.stream.Stream<Path> files = Files.walk(root, 12)) {
					files.filter(Files::isRegularFile).forEach(f -> {
						String rel = pack.path().relativize(f).toString().replace('\\', '/');
						names.putIfAbsent(rel.toLowerCase(Locale.ROOT), rel);
					});
				}
			}
		}
		int n = 0;
		for (Map.Entry<StudioItem, List<Variant>> en : variants.entrySet()) {
			String[][] p = paths.get(n);
			boolean[][] l = legacyFlags.get(n);
			n++;
			String[] found = null;
			boolean legacy = false;
			for (int i = 0; i < p.length; i++) {
				for (int k = 0; k < p[i].length; k++) {
					String real = names.get(p[i][k]);
					if (real != null) {
						if (found == null) {
							found = new String[p.length];
						}
						found[i] = real;
						legacy |= l[i][k];
						break;
					}
				}
			}
			if (found != null) {
				en.getValue().add(new Variant(pack, Collections.unmodifiableList(Arrays.asList(found)), legacy));
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
