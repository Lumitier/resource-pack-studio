package com.lumitier.resourcepackstudio.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
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
 *  - credits.txt: welches Bild aus welchem Pack stammt.
 *  - pack.png, wenn ein Bild gewaehlt wurde (unveraendert uebernommen).
 *
 * ERST IN EINE TEMPORAERE DATEI, dann umbenennen: Bricht das Schreiben ab,
 * liegt kein halbes Pack in der Liste.
 *
 * Laeuft im Hintergrund. Das Ergebnis kommt auf dem Render-Thread zurueck.
 */
public final class PackBuilder {

	private PackBuilder() {}

	public static final int PACK_FORMAT = 75;

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

	/** Startet das Bauen im Hintergrund. done laeuft auf dem Render-Thread. */
	public static void build(String wantedName, Path icon, Consumer<Built> done) {
		Map<StudioItem, PackScanner.Variant> sel = StudioIndex.selectionByItem();
		Thread t = new Thread(() -> {
			Built b;
			try {
				b = write(wantedName, icon, sel);
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

	private static Built write(String wantedName, Path icon, Map<StudioItem, PackScanner.Variant> sel) throws IOException {
		if (sel.isEmpty()) {
			return new Built(null, null, 0, "Nothing selected");
		}
		Path dir = ResourcePackStudio.resourcePacksDir();
		Files.createDirectories(dir);
		String name = freeName(wantedName);
		Path target = dir.resolve(name + ".zip");
		Path tmp = dir.resolve(name + ".zip.tmp");

		// Zielpfad -> Quelle. Teilen sich zwei Items ein Bild (z. B. beide
		// goldenen Aepfel), gewinnt das spaeter gewaehlte - mehr als ein Bild
		// pro Pfad kann ein Pack nicht haben.
		Map<String, PackScanner.Variant> files = new LinkedHashMap<>();
		Map<String, StudioItem> owners = new LinkedHashMap<>();
		for (Map.Entry<StudioItem, PackScanner.Variant> e : sel.entrySet()) {
			String path = StudioItem.TEXTURES + e.getKey().modern() + ".png";
			files.put(path, e.getValue());
			owners.put(path, e.getKey());
		}

		List<String> credits = new ArrayList<>();
		int written = 0;
		try (OutputStream fos = Files.newOutputStream(tmp);
		     ZipOutputStream zip = new ZipOutputStream(fos)) {
			put(zip, "pack.mcmeta", mcmeta(name).getBytes(StandardCharsets.UTF_8));
			if (icon != null && Files.isRegularFile(icon)) {
				put(zip, "pack.png", Files.readAllBytes(icon));
			}

			for (Map.Entry<String, PackScanner.Variant> e : files.entrySet()) {
				PackScanner.Variant v = e.getValue();
				byte[] png = read(v, v.entry());
				if (png == null) {
					ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Skipped {} - could not read it from {}",
						v.entry(), v.pack().name());
					continue;
				}
				put(zip, e.getKey(), png);
				byte[] meta = read(v, v.entry() + ".mcmeta");
				if (meta != null) {
					put(zip, e.getKey() + ".mcmeta", meta);
				}
				written++;
				credits.add(owners.get(e.getKey()).id() + "  <-  " + v.pack().name());
			}

			StringBuilder sb = new StringBuilder();
			sb.append("Made with Resource Pack Studio.\n\n");
			sb.append("The textures in this pack belong to the authors of the packs they came from.\n");
			sb.append("Ask them before you share this pack.\n\n");
			for (String c : credits) {
				sb.append(c).append('\n');
			}
			put(zip, "credits.txt", sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}

		if (written == 0) {
			Files.deleteIfExists(tmp);
			return new Built(null, null, 0, "None of the selected textures could be read");
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		ResourcePackStudio.LOGGER.info("[Resource Pack Studio] Created {} with {} texture(s).", target, written);
		return new Built(target, name, written, null);
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

	private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
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
