package com.lumitier.resourcepackstudio.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.EntityModels;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Baut aus der Auswahl ein Resource Pack - als .zip im resourcepacks-Ordner.
 *
 * INHALT:
 *  - pack.mcmeta mit Format 75 (Minecraft 1.21.11, aus der version.json
 *    des Spiels), im neuen Aufbau mit min_format/max_format wie Vanillas
 *    eigene Packs.
 *  - je gewaehltem Item die Textur unter ihrem Pfad von 1.21.11. Bilder aus
 *    1.8-Packs landen dabei automatisch am neuen Ort mit neuem Namen
 *    (items/wood_sword.png -> item/wooden_sword.png).
 *  - eine .png.mcmeta daneben, wenn die Quelle eine hat (Animationen).
 *  - Himmel: der ganze Ordner der Dimension (sky1.properties, Bilder ...)
 *    unter optifine/sky/<dimension>/ - dort liest Skyboxify ihn.
 *  - credits.txt: welches Bild aus welchem Pack stammt.
 *  - pack.png, wenn ein Bild gewaehlt wurde (unveraendert uebernommen).
 *
 *  - rps-selection.json: was woher gewaehlt war. Damit laesst sich das
 *    Pack spaeter im Studio wieder oeffnen und weiterbearbeiten.
 *
 * ALS .ZIP ODER ALS ORDNER: Ein Ordner laesst sich von Hand nachbearbeiten,
 * eine Zip ist eine einzige Datei zum Weitergeben. Gebaut wird immer erst
 * die Zip; fuer einen Ordner wird sie danach ausgepackt.
 *
 * ERST IN EINE TEMPORAERE DATEI, dann umbenennen: Bricht das Schreiben ab,
 * liegt kein halbes Pack in der Liste. Beim Bearbeiten eines eigenen
 * Packs wird das alte erst geloescht, wenn das neue fertig ist.
 *
 * Laeuft im Hintergrund. Das Ergebnis kommt auf dem Render-Thread zurueck.
 */
public final class PackBuilder {

	private PackBuilder() {}

	public static final int PACK_FORMAT = 75;

	/** Ein zu kopierendes Bild: aus welcher Variante, welcher Eintrag, fuer welches Item. */
	private record Source(PackScanner.Variant variant, String entry, String item) {}

	/** Ergebnis: entweder Datei und Name, oder ein Fehler. */
	public record Built(Path file, String name, int textures, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	/** Name ohne Eingabe: "<Spieler> Texture Pack". */
	public static String defaultName() {
		String player = "Player";
		try {
			player = MinecraftClient.getInstance().getSession().getUsername();
		} catch (Throwable ignored) {
		}
		return player + " Texture Pack";
	}

	/**
	 * Freier Dateiname: "Name", sonst "Name (1)", "Name (2)" ...
	 * Zeichen, die Windows im Dateinamen nicht erlaubt, werden ersetzt.
	 */
	public static String freeName(String wanted) {
		String base = wanted == null ? "" : wanted.trim();
		if (base.isEmpty()) {
			base = defaultName();
		}
		base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[. ]+$", "");
		if (base.isEmpty()) {
			base = defaultName();
		}
		if (base.length() > 64) {
			base = base.substring(0, 64).trim();
		}
		Path dir = ResourcePackStudio.resourcePacksDir();
		String name = base;
		for (int n = 1; taken(dir, name); n++) {
			name = base + " (" + n + ")";
		}
		return name;
	}

	private static boolean taken(Path dir, String name) {
		return Files.exists(dir.resolve(name + ".zip")) || Files.exists(dir.resolve(name));
	}

	/**
	 * Fortschritt fuer den Ladekreis: wie viele Dateien geschrieben sind und
	 * wie viele es insgesamt werden. Nur vom Bau-Thread geschrieben, vom
	 * Bildschirm gelesen - zwei volatile Zahlen reichen dafuer.
	 */
	public static volatile int progressDone;
	public static volatile int progressTotal;

	/**
	 * Startet das Bauen im Hintergrund. done laeuft auf dem Render-Thread.
	 *
	 * @param folder  als Ordner statt als .zip speichern
	 * @param replace ein eigenes Pack, das gerade bearbeitet wird - heisst
	 *                das neue genauso, ersetzt es das alte. Sonst null.
	 */
	public static void build(String wantedName, Path icon, boolean folder, PackScanner.Pack replace,
	                         Consumer<Built> done) {
		Map<StudioItem, PackScanner.Variant> sel = StudioIndex.selectionByItem();
		progressDone = 0;
		progressTotal = Math.max(1, sel.size());
		Thread t = new Thread(() -> {
			Built b;
			try {
				b = write(wantedName, icon, sel, folder, replace);
			} catch (Throwable e) {
				ResourcePackStudio.LOGGER.error("[Resource Pack Studio] Could not create the pack.", e);
				b = new Built(null, null, 0, String.valueOf(e.getMessage()));
			}
			Built result = b;
			MinecraftClient.getInstance().execute(() -> done.accept(result));
		}, "Resource Pack Studio - Build");
		t.setDaemon(true);
		t.start();
	}

	/** Wuerde dieser Name das bearbeitete Pack treffen? Dann wird es ersetzt statt "Name (1)". */
	public static boolean replaces(String wantedName, PackScanner.Pack replace) {
		return replace != null && wantedName != null
			&& clean(wantedName).equalsIgnoreCase(clean(replace.name()));
	}

	private static String clean(String s) {
		return s.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[. ]+$", "");
	}

	private static Built write(String wantedName, Path icon, Map<StudioItem, PackScanner.Variant> sel,
	                           boolean folder, PackScanner.Pack replace) throws IOException {
		if (sel.isEmpty()) {
			return new Built(null, null, 0, "Nothing selected");
		}
		Path dir = ResourcePackStudio.resourcePacksDir();
		Files.createDirectories(dir);
		boolean replacing = replaces(wantedName, replace);
		String name = replacing ? replace.path().getFileName().toString().replaceAll("(?i)\\.zip$", "")
			: freeName(wantedName);
		Path target = dir.resolve(folder ? name : name + ".zip");
		Path tmp = dir.resolve(name + ".zip.tmp");

		// Zielpfad -> Quelle. Teilen sich zwei Items ein Bild (z. B. beide
		// goldenen Aepfel), gewinnt das spaeter gewaehlte - mehr als ein Bild
		// pro Pfad kann ein Pack nicht haben.
		// Bei Bloecken: jedes Bild, das das Pack hat (Stamm UND
		// Schnittflaeche). Was das Pack nicht hat, bleibt Vanilla.
		//
		// EIGENE Bilder an ihren normalen Pfad. GELIEHENE (Eichentreppe ->
		// Eichenbretter) an einen eigenen Pfad unter block/rps/<eintrag>/,
		// dazu eigene Modelle und Blockzustaende, die darauf zeigen. So
		// aendert eine gewaehlte Treppe nur die Treppe.
		Map<String, Source> files = new LinkedHashMap<>();
		// Dateien aus 3D-Modellen: Zielpfad -> Quelle, unveraendert kopiert.
		Map<String, Source> raw = new LinkedHashMap<>();
		Map<StudioItem, Map<String, String>> remaps = new LinkedHashMap<>();
		// Ruestungs-Lage 1 (Helm, Brust, Schuhe): Bildpfad -> gewaehlte Teile.
		// Wird unten zu EINEM Bild zusammengesetzt (WornComposer).
		Map<String, List<Object[]>> worn = new LinkedHashMap<>();
		// Himmel: Dimension -> gewaehlte Variante. Ein ganzer Ordner, kein Bild.
		Map<String, PackScanner.Variant> skies = new LinkedHashMap<>();
		for (Map.Entry<StudioItem, PackScanner.Variant> e : sel.entrySet()) {
			StudioItem item = e.getKey();
			if (item.sky()) {
				skies.put(item.id().substring(com.lumitier.resourcepackstudio.scan.StudioItems.SKY_ID.length()),
					e.getValue());
				continue;
			}
			List<StudioItem.Part> parts = item.parts();
			EntityModels.Piece piece = WornComposer.pieceOf(item);
			if (piece != null) {
				List<String> entries = e.getValue().entries();
				for (int i = 0; i < parts.size() && i < entries.size(); i++) {
					if (entries.get(i) != null) {
						worn.computeIfAbsent(parts.get(i).modern(), k -> new ArrayList<>())
							.add(new Object[] {piece, e.getValue(), entries.get(i), item.id()});
					}
				}
				continue;
			}
			// Eigenes 3D-Modell im Pack (3D-Streitkolben ...): Item-Eintrag,
			// Modelle und alle Bilder des Modells mitnehmen. Sonst laege die
			// Textur des Modells auf dem flachen Vanilla-Item.
			if (!item.cube() && !item.sound() && !item.texture() && !item.particle()) {
				PackModels.Model model = PackModels.load(item, e.getValue());
				if (model != null) {
					for (Map.Entry<String, String> f : model.files().entrySet()) {
						raw.put(f.getValue(), new Source(e.getValue(), f.getKey(), item.id()));
					}
				}
			}
			List<String> entries = e.getValue().entries();
			Map<String, String> remap = new LinkedHashMap<>();
			for (int i = 0; i < parts.size() && i < entries.size(); i++) {
				if (entries.get(i) == null) {
					continue;
				}
				String path = parts.get(i).modern();
				if (!parts.get(i).own()) {
					String copy = "block/rps/" + item.id() + "/" + path.substring(path.lastIndexOf('/') + 1);
					remap.put(path, copy);
					path = copy;
				}
				files.put(StudioItem.Part.file(path),
					new Source(e.getValue(), entries.get(i), item.id()));
			}
			if (!remap.isEmpty()) {
				remaps.put(item, remap);
			}
		}

		List<String> credits = new ArrayList<>();
		int written = 0;
		progressTotal = Math.max(1, files.size() + worn.size() + skies.size() + remaps.size() + (folder ? 1 : 0));
		progressDone = 0;
		try (OutputStream fos = Files.newOutputStream(tmp);
		     ZipOutputStream zip = new ZipOutputStream(fos)) {
			put(zip, "pack.mcmeta", mcmeta(name).getBytes(StandardCharsets.UTF_8));
			if (icon != null && Files.isRegularFile(icon)) {
				put(zip, "pack.png", Files.readAllBytes(icon));
			}

			for (Map.Entry<String, Source> e : files.entrySet()) {
				Source src = e.getValue();
				PackScanner.Variant v = src.variant();
				byte[] png = read(v, src.entry());
				if (png == null) {
					ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Skipped {} - could not read it from {}",
						src.entry(), v.pack().name());
					continue;
				}
				put(zip, e.getKey(), png);
				progressDone++;
				byte[] meta = read(v, src.entry() + ".mcmeta");
				if (meta != null) {
					put(zip, e.getKey() + ".mcmeta", meta);
				}
				written++;
				String file = e.getKey().substring(StudioItem.ASSETS.length());
				credits.add(src.item() + " (" + file + ")  <-  " + v.pack().name());
			}

			for (Map.Entry<String, Source> e : raw.entrySet()) {
				if (files.containsKey(e.getKey())) {
					continue;                       // Das Bild selbst kam schon oben.
				}
				byte[] data = read(e.getValue().variant(), e.getValue().entry());
				if (data != null) {
					put(zip, e.getKey(), data);
				}
			}

			for (Map.Entry<String, List<Object[]>> e : worn.entrySet()) {
				List<WornComposer.Piece> pieces = new ArrayList<>();
				for (Object[] o : e.getValue()) {
					PackScanner.Variant v = (PackScanner.Variant) o[1];
					byte[] png = read(v, (String) o[2]);
					if (png != null) {
						pieces.add(new WornComposer.Piece((EntityModels.Piece) o[0], png));
						credits.add(o[3] + " (" + e.getKey() + ")  <-  " + v.pack().name());
					}
				}
				if (!pieces.isEmpty()) {
					put(zip, StudioItem.Part.file(e.getKey()), WornComposer.compose(e.getKey(), pieces));
					written++;
				}
				progressDone++;
			}

			for (Map.Entry<String, PackScanner.Variant> e : skies.entrySet()) {
				int n = writeSky(zip, e.getKey(), e.getValue());
				progressDone++;
				if (n > 0) {
					written++;
					credits.add("sky " + e.getKey() + " (" + n + " file(s))  <-  " + e.getValue().pack().name());
				}
			}

			for (Map.Entry<StudioItem, Map<String, String>> e : remaps.entrySet()) {
				progressDone++;
				if (!ModelCopies.write(zip, e.getKey(), e.getValue())) {
					ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] {}: no model to redirect - "
						+ "its own textures were copied, but it keeps the vanilla look.", e.getKey().id());
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append(PackScanner.OWN_MARK).append("\n\n");
			sb.append("The textures in this pack belong to the authors of the packs they came from.\n");
			sb.append("Ask them before you share this pack.\n\n");
			for (String c : credits) {
				sb.append(c).append('\n');
			}
			put(zip, "credits.txt", sb.toString().getBytes(StandardCharsets.UTF_8));
			put(zip, PackScanner.SELECTION_FILE, selectionJson(sel).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}

		if (written == 0) {
			Files.deleteIfExists(tmp);
			return new Built(null, null, 0, "None of the selected textures could be read");
		}
		Path unpacked = null;
		if (folder) {
			// Neben dem Ziel auspacken, erst danach umbenennen - auch hier
			// soll nie ein halber Ordner in der Liste stehen.
			unpacked = dir.resolve(name + ".rps-tmp");
			deleteTree(unpacked);
			unzip(tmp, unpacked);
			Files.deleteIfExists(tmp);
			progressDone = progressTotal;
		}
		// Das neue Pack ist fertig - jetzt erst das bearbeitete alte weg.
		// Ist es gerade in Minecraft aktiv, haelt Windows die Datei fest und
		// sie laesst sich nicht loeschen. Dann bekommt das neue einen
		// freien Namen, statt dass die ganze Arbeit verloren geht.
		if (replacing) {
			try {
				deleteTree(replace.path());
			} catch (IOException locked) {
				ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not replace {} (in use?) - saving under a new name.",
					replace.path());
				name = freeName(wantedName);
				target = dir.resolve(folder ? name : name + ".zip");
			}
		}
		deleteTree(target);
		Files.move(folder ? unpacked : tmp, target, StandardCopyOption.REPLACE_EXISTING);
		ResourcePackStudio.LOGGER.info("[Resource Pack Studio] Created {} with {} texture(s).", target, written);
		return new Built(target, name, written, null);
	}

	/**
	 * Die Auswahl als JSON - dasselbe Format wie selection.json, dazu der
	 * Name des Quell-Packs, falls es spaeter nicht mehr da ist.
	 */
	private static String selectionJson(Map<StudioItem, PackScanner.Variant> sel) {
		JsonObject root = new JsonObject();
		for (Map.Entry<StudioItem, PackScanner.Variant> e : sel.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("pack", e.getValue().pack().path().toString());
			o.addProperty("entry", e.getValue().entry());
			o.addProperty("from", e.getValue().pack().name());
			root.add(e.getKey().id(), o);
		}
		return new GsonBuilder().setPrettyPrinting().create().toJson(root);
	}

	private static void unzip(Path zipFile, Path into) throws IOException {
		Files.createDirectories(into);
		Path base = into.toAbsolutePath().normalize();
		try (ZipFile z = new ZipFile(zipFile.toFile())) {
			java.util.Enumeration<? extends ZipEntry> en = z.entries();
			while (en.hasMoreElements()) {
				ZipEntry ze = en.nextElement();
				Path out = base.resolve(ze.getName()).normalize();
				// Nur, was wir selbst geschrieben haben - trotzdem nie ausserhalb.
				if (!out.startsWith(base)) {
					continue;
				}
				if (ze.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				Files.createDirectories(out.getParent());
				try (InputStream in = z.getInputStream(ze)) {
					Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Datei oder Ordner samt Inhalt loeschen. Fehlt er, passiert nichts. */
	private static void deleteTree(Path p) throws IOException {
		if (p == null || !Files.exists(p)) {
			return;
		}
		if (!Files.isDirectory(p)) {
			Files.delete(p);
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
			List<Path> all = walk.sorted(java.util.Comparator.reverseOrder()).toList();
			for (Path f : all) {
				Files.delete(f);
			}
		}
	}

	/**
	 * Kopiert den Himmel-Ordner einer Dimension aus dem Pack - alle Dateien
	 * darin, auch in Unterordnern. Landet immer unter optifine/sky/, auch
	 * wenn das Pack noch den alten MCPatcher-Ordner hatte; Verweise in den
	 * .properties werden dafuer mit angepasst.
	 *
	 * @return wie viele Dateien kopiert wurden
	 */
	private static int writeSky(ZipOutputStream zip, String dim, PackScanner.Variant v) throws IOException {
		String entry = v.entry();
		int slash = entry.lastIndexOf('/');
		if (slash < 0) {
			return 0;
		}
		String folder = entry.substring(0, slash + 1);
		String target = "assets/minecraft/optifine/sky/" + dim + "/";
		Map<String, byte[]> files = new LinkedHashMap<>();
		if (v.pack().zip()) {
			try (ZipFile z = new ZipFile(v.pack().path().toFile())) {
				java.util.Enumeration<? extends ZipEntry> en = z.entries();
				while (en.hasMoreElements()) {
					ZipEntry ze = en.nextElement();
					if (!ze.isDirectory() && ze.getName().startsWith(folder)) {
						try (InputStream in = z.getInputStream(ze)) {
							files.put(ze.getName().substring(folder.length()), in.readAllBytes());
						}
					}
				}
			}
		} else {
			Path dir = v.pack().path().resolve(folder);
			if (Files.isDirectory(dir)) {
				try (java.util.stream.Stream<Path> walk = Files.walk(dir, 4)) {
					for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
						files.put(dir.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
					}
				}
			}
		}
		for (Map.Entry<String, byte[]> f : files.entrySet()) {
			byte[] data = f.getValue();
			if (f.getKey().endsWith(".properties")) {
				data = new String(data, StandardCharsets.ISO_8859_1)
					.replace("mcpatcher/sky", "optifine/sky")
					.getBytes(StandardCharsets.ISO_8859_1);
			}
			put(zip, target + f.getKey(), data);
		}
		return files.size();
	}

	private static String mcmeta(String name) {
		JsonObject pack = new JsonObject();
		pack.addProperty("description", name + " - made with Resource Pack Studio");
		pack.addProperty("min_format", PACK_FORMAT);
		pack.addProperty("max_format", PACK_FORMAT);
		JsonObject root = new JsonObject();
		root.add("pack", pack);
		return new GsonBuilder().setPrettyPrinting().create().toJson(root);
	}

	static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(data);
		zip.closeEntry();
	}

	/** Liest eine Datei aus dem Pack. null, wenn es sie nicht gibt. */
	private static byte[] read(PackScanner.Variant v, String entry) {
		try {
			if (v.pack().zip()) {
				try (ZipFile z = new ZipFile(v.pack().path().toFile())) {
					ZipEntry e = z.getEntry(entry);
					if (e == null) {
						return null;
					}
					try (InputStream in = z.getInputStream(e)) {
						return in.readAllBytes();
					}
				}
			}
			Path p = v.pack().path().resolve(entry);
			return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
		} catch (IOException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Aktivieren
	// ------------------------------------------------------------------

	/**
	 * Schaltet das neue Pack ein und alle bisher aktiven Packs aus dem
	 * resourcepacks-Ordner ab. Eingebaute Packs (Vanilla, Mod-Ressourcen,
	 * Programmer Art, High Contrast) bleiben, wie sie sind.
	 *
	 * Danach laedt Minecraft die Texturen neu - genau wie beim Klick auf
	 * "Fertig" in der Pack-Auswahl.
	 *
	 * @return false, wenn Minecraft das neue Pack nicht findet
	 */
	public static boolean enable(Path file) {
		MinecraftClient client = MinecraftClient.getInstance();
		ResourcePackManager m = client.getResourcePackManager();
		m.scanPacks();
		String fileName = file.getFileName().toString();
		String id = null;
		for (ResourcePackProfile p : m.getProfiles()) {
			if (p.getId().startsWith("file/") && p.getId().endsWith(fileName)) {
				id = p.getId();
				break;
			}
		}
		if (id == null) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Minecraft does not list {} - not enabled.", fileName);
			return false;
		}
		List<String> keep = new ArrayList<>();
		for (ResourcePackProfile p : m.getEnabledProfiles()) {
			if (!p.getId().startsWith("file/") || p.isRequired()) {
				keep.add(p.getId());
			}
		}
		// Zuletzt = ganz oben = hoechste Prioritaet.
		keep.add(id);
		m.setEnabledProfiles(keep);
		client.options.refreshResourcePacks(m);
		return true;
	}
}
