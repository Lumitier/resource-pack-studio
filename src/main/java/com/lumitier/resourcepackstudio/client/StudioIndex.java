package com.lumitier.resourcepackstudio.client;

import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.ScanReport;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import com.lumitier.resourcepackstudio.scan.StudioItems;
import net.minecraft.client.MinecraftClient;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Ergebnis der letzten Suche und die Auswahl pro Item.
 *
 * Die Suche laeuft im Hintergrund, das Ergebnis wird erst auf dem
 * Render-Thread uebernommen. Der Bildschirm liest also nie einen halben
 * Stand, und keine Textur wird angefasst, waehrend die Suche laeuft.
 *
 * DIE AUSWAHL WIRD GESPEICHERT in resourcepackstudio/selection.json - je
 * Item der Pfad des Packs und der Eintrag darin. Nach dem Start wird sie
 * mit der ersten Suche wieder zugeordnet. Gibt es ein Pack nicht mehr,
 * faellt das Item still auf Vanilla zurueck.
 */
public final class StudioIndex {

	private StudioIndex() {}

	private static volatile PackScanner.Result result;
	private static volatile boolean scanning;

	/** Zaehlt jede neue Suche - der Bildschirm baut daran seine Ansicht neu. */
	private static volatile int version;

	/** Item-Kennung -> ausgewaehlte Variante. Fehlt der Eintrag: Vanilla. */
	private static final Map<String, PackScanner.Variant> SELECTION = new HashMap<>();

	/**
	 * Alle Items der Sidebar in fester Reihenfolge - PvP zuerst. Vor der
	 * ersten Suche nur die PvP-Items.
	 */
	public static List<StudioItem> items() {
		List<StudioItem> l = items;
		return l != null ? l : StudioItems.all();
	}

	private static volatile List<StudioItem> items;

	public static PackScanner.Result result() {
		return result;
	}

	public static boolean scanning() {
		return scanning;
	}

	public static int version() {
		return version;
	}

	public static List<PackScanner.Variant> variants(StudioItem item) {
		PackScanner.Result r = result;
		if (r == null) {
			return List.of();
		}
		List<PackScanner.Variant> v = r.variants().get(item);
		return v == null ? List.of() : v;
	}

	public static PackScanner.Variant selected(StudioItem item) {
		return SELECTION.get(item.id());
	}

	/** null waehlt Vanilla. Speichert sofort - die Datei ist klein. */
	public static void select(StudioItem item, PackScanner.Variant v) {
		if (v == null) {
			SELECTION.remove(item.id());
		} else {
			SELECTION.put(item.id(), v);
		}
		save();
	}

	/** Alle Items zurueck auf Vanilla. Speichert sofort. */
	public static void clearSelection() {
		SELECTION.clear();
		save();
	}

	/** Gewaehlte Varianten in Sidebar-Reihenfolge - fuer das Bauen des Packs. */
	public static Map<StudioItem, PackScanner.Variant> selectionByItem() {
		Map<StudioItem, PackScanner.Variant> out = new LinkedHashMap<>();
		for (StudioItem it : items()) {
			PackScanner.Variant v = SELECTION.get(it.id());
			if (v != null) {
				out.put(it, v);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Speichern und Laden
	// ------------------------------------------------------------------

	private static boolean loaded;

	private static Path file() {
		return ResourcePackStudio.home().resolve("selection.json");
	}

	private static void save() {
		JsonObject root = new JsonObject();
		for (Map.Entry<String, PackScanner.Variant> e : SELECTION.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("pack", e.getValue().pack().path().toString());
			o.addProperty("entry", e.getValue().entry());
			root.add(e.getKey(), o);
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root),
				StandardCharsets.UTF_8);
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not save the selection.", e);
		}
	}

	/** Gespeicherte Auswahl dem frischen Suchergebnis zuordnen. Einmal pro Start. */
	private static void loadSaved(PackScanner.Result r) {
		if (!Files.isRegularFile(file())) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				return;
			}
			for (Map.Entry<StudioItem, List<PackScanner.Variant>> e : r.variants().entrySet()) {
				JsonElement el = root.getAsJsonObject().get(e.getKey().id());
				if (el == null || !el.isJsonObject()) {
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				if (!o.has("pack") || !o.has("entry")) {
					continue;
				}
				String pack = o.get("pack").getAsString();
				String entry = o.get("entry").getAsString();
				for (PackScanner.Variant v : e.getValue()) {
					if (v.pack().path().toString().equals(pack) && v.entry().equals(entry)) {
						SELECTION.put(e.getKey().id(), v);
						break;
					}
				}
			}
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not read the saved selection.", e);
		}
	}

	public static int selectionCount() {
		return SELECTION.size();
	}

	/**
	 * Neue Suche im Hintergrund. Laeuft schon eine, passiert nichts - zwei
	 * gleichzeitig wuerden sich nur gegenseitig ausbremsen.
	 */
	public static synchronized void rescan() {
		if (scanning) {
			return;
		}
		scanning = true;
		version++;
		Thread t = new Thread(StudioIndex::run, "Resource Pack Studio - Scan");
		t.setDaemon(true);
		t.start();
	}

	private static void run() {
		List<Path> roots = List.of(ResourcePackStudio.resourcePacksDir(), ResourcePackStudio.packsDir());
		PackScanner.Result r = null;
		try {
			r = PackScanner.scan(roots, ItemDiscovery.all());
			ScanReport.write(r, roots);
			int total = 0;
			for (List<PackScanner.Variant> vs : r.variants().values()) {
				total += vs.size();
			}
			ResourcePackStudio.LOGGER.info("[Resource Pack Studio] Scanned {} pack(s) in {} ms, {} texture(s).",
				r.packs().size(), r.millis(), total);
		} catch (Throwable e) {
			ResourcePackStudio.LOGGER.error("[Resource Pack Studio] Scan failed.", e);
		}
		PackScanner.Result done = r;
		MinecraftClient.getInstance().execute(() -> apply(done));
	}

	/** Auf dem Render-Thread: neues Ergebnis uebernehmen. */
	private static void apply(PackScanner.Result r) {
		if (r != null) {
			result = r;
			items = List.copyOf(r.variants().keySet());
			// Eine Auswahl, deren Pack es nicht mehr gibt, ist wertlos.
			// Zuordnung ueber Pfad und Eintrag, nicht ueber das Objekt -
			// das ist nach jeder Suche ein neues.
			SELECTION.replaceAll((id, old) -> find(r, id, old));
			SELECTION.values().removeIf(v -> v == null);
			if (!loaded) {
				loaded = true;
				loadSaved(r);
			}
		}
		Textures.clear();
		scanning = false;
		version++;
	}

	private static PackScanner.Variant find(PackScanner.Result r, String itemId, PackScanner.Variant old) {
		for (Map.Entry<StudioItem, List<PackScanner.Variant>> e : r.variants().entrySet()) {
			if (!e.getKey().id().equals(itemId)) {
				continue;
			}
			for (PackScanner.Variant v : e.getValue()) {
				if (v.pack().path().equals(old.pack().path()) && v.entry().equals(old.entry())) {
					return v;
				}
			}
		}
		return null;
	}
}
