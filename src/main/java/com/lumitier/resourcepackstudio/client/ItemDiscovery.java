package com.lumitier.resourcepackstudio.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.LegacyBlocks;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import com.lumitier.resourcepackstudio.scan.StudioItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ermittelt ALLE flachen Items des Spiels - also alles, was im Inventar als
 * einzelnes Bild erscheint.
 *
 * WOHER DAS BILD KOMMT steht in Minecrafts eigenen Dateien, und genau die
 * werden hier gelesen - aus dem Standard-Pack, nie aus den aktiven Packs:
 *   items/apple.json        -> Modell "item/apple"
 *   models/item/apple.json  -> layer0 = "item/apple"
 * Bei Items mit mehreren Zustaenden (Uhr, Kompass, Bogen, Armbrust) wird
 * der Grundzustand genommen: fallback bzw. on_false, sonst der erste Fall.
 *
 * BLOECKE: Hat das Modell kein layer0, werden seine Quader gelesen (Wuerfel,
 * Treppe, Stufe, Zaun ...) - fuer die Vorschau die Seiten oben, Norden und
 * Osten mit ihren Bildern. Alle Bilder des Blocks werden Teile. Was nicht
 * dem Block selbst gehoert (Treppe -> Bretter), ist ein geliehener Teil;
 * siehe StudioItem.Part.own.
 *
 * Setzlinge, Blumen, Tueren usw. sind flach - ihr Bild liegt teils im
 * Block-Ordner.
 *
 * NUR VANILLA: Items anderer Mods werden uebersprungen.
 *
 * Laeuft im Hintergrund (Suche), liest nur und haelt nichts fest.
 */
public final class ItemDiscovery {

	private ItemDiscovery() {}

	/** Wie tief einer Kette von Eltern-Modellen gefolgt wird. */
	private static final int MAX_PARENTS = 8;

	/** PvP-Items zuerst, dann alle anderen flachen Items. */
	public static List<StudioItem> all() {
		List<StudioItem> curated = StudioItems.all();
		List<StudioItem> out = new ArrayList<>(curated);
		Set<String> known = new HashSet<>();
		for (StudioItem it : curated) {
			known.add(it.id());
		}

		ResourcePack pack;
		try {
			pack = MinecraftClient.getInstance().getDefaultResourcePack();
		} catch (Throwable e) {
			pack = null;
		}
		if (pack == null) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Default resource pack not available - only the PvP items are listed.");
			return out;
		}

		int skipped = 0;
		Set<List<String>> seenSpecial = new HashSet<>();
		int cubes = 0;
		for (Item item : Registries.ITEM) {
			if (item == Items.AIR) {
				continue;
			}
			Identifier id = Registries.ITEM.getId(item);
			if (!Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace()) || known.contains(id.getPath())) {
				continue;
			}
			StudioItem found;
			try {
				String tex = flatTexture(pack, id.getPath());
				found = tex != null
					? StudioItem.flat(id.getPath(), StudioItems.OTHER, tex, legacyOf(tex))
					: block(pack, item, id.getPath());
				if (found == null) {
					found = special(pack, item, id.getPath(), seenSpecial);
				}
			} catch (Throwable e) {
				found = null;
			}
			if (found == null) {
				skipped++;
				continue;
			}
			if (found.cube()) {
				cubes++;
			}
			out.add(found);
		}
		int sounds = addSounds(pack, out);
		ResourcePackStudio.LOGGER.info("[Resource Pack Studio] {} entries: {} block(s), {} sound(s), the rest items. {} item(s) skipped (chests, beds, heads ...).",
			out.size(), cubes, sounds, skipped);
		return out;
	}

	/**
	 * Bild eines Items relativ zu textures/ ("item/apple"), oder null, wenn
	 * es kein flaches Item ist.
	 */
	private static String flatTexture(ResourcePack pack, String itemId) throws Exception {
		JsonElement def = read(pack, Identifier.ofVanilla("items/" + itemId + ".json"));
		if (def == null || !def.isJsonObject()) {
			return null;
		}
		String model = baseModel(def.getAsJsonObject().get("model"), 0);
		for (int i = 0; model != null && i < MAX_PARENTS; i++) {
			Identifier mid = Identifier.tryParse(model);
			if (mid == null) {
				return null;
			}
			JsonElement m = read(pack, Identifier.of(mid.getNamespace(), "models/" + mid.getPath() + ".json"));
			if (m == null || !m.isJsonObject()) {
				return null;
			}
			JsonObject o = m.getAsJsonObject();
			if (o.has("textures") && o.get("textures").isJsonObject()) {
				JsonElement l0 = o.getAsJsonObject("textures").get("layer0");
				if (l0 != null && l0.isJsonPrimitive()) {
					Identifier t = Identifier.tryParse(l0.getAsString());
					if (t == null || !Identifier.DEFAULT_NAMESPACE.equals(t.getNamespace())) {
						return null;
					}
					String p = t.getPath();
					return p.startsWith("item/") || p.startsWith("block/") ? p : null;
				}
			}
			JsonElement parent = o.get("parent");
			model = parent != null && parent.isJsonPrimitive() ? parent.getAsString() : null;
		}
		return null;
	}

	/**
	 * Alle Sounds aus Minecrafts Standard-Pack (assets/minecraft/sounds).
	 * Ein Eintrag pro Datei - Packs ersetzen Sounds dateiweise. Gruppe ist
	 * der oberste Ordner (damage, entity, random ...).
	 */
	private static int addSounds(ResourcePack pack, List<StudioItem> out) {
		java.util.TreeSet<String> paths = new java.util.TreeSet<>();
		try {
			pack.findResources(ResourceType.CLIENT_RESOURCES, Identifier.DEFAULT_NAMESPACE, "sounds",
				(id, supplier) -> {
					String p = id.getPath();
					if (p.endsWith(".ogg")) {
						paths.add(p.substring(0, p.length() - 4));
					}
				});
		} catch (Throwable e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not list the vanilla sounds.", e);
		}
		for (String p : paths) {
			// p = "sounds/damage/hit1"
			String rel = p.substring(StudioItem.SOUNDS.length());
			int slash = rel.indexOf('/');
			String group = StudioItems.SOUND_GROUP + (slash > 0 ? rel.substring(0, slash) : "");
			out.add(StudioItem.flat(StudioItems.SOUND_ID + rel, group, p, List.of()));
		}
		return paths.size();
	}

	/** 1.8 hatte "items"/"blocks" statt "item"/"block" - und teils andere Namen. */
	private static List<String> legacyOf(String modern) {
		String l = LegacyBlocks.legacy(modern);
		return l == null ? List.of() : List.of(l);
	}

	/**
	 * Ein Block mit 3D-Vorschau, oder null, wenn sein Modell keine Quader
	 * hat (Kisten, Betten, Schilder - die zeichnet Minecraft anders).
	 */
	private static StudioItem block(ResourcePack pack, Item item, String itemId) throws Exception {
		JsonElement def = read(pack, Identifier.ofVanilla("items/" + itemId + ".json"));
		if (def == null || !def.isJsonObject()) {
			return null;
		}
		String model = baseModel(def.getAsJsonObject().get("model"), 0);

		// Kette der Modelle ablaufen: Bilder sammeln (das Kind gewinnt) und
		// das erste Modell mit Elementen merken.
		Map<String, String> textures = new HashMap<>();
		JsonArray elements = null;
		for (int i = 0; model != null && i < MAX_PARENTS; i++) {
			Identifier mid = Identifier.tryParse(model);
			if (mid == null) {
				return null;
			}
			JsonElement m = read(pack, Identifier.of(mid.getNamespace(), "models/" + mid.getPath() + ".json"));
			if (m == null || !m.isJsonObject()) {
				break;
			}
			JsonObject o = m.getAsJsonObject();
			if (o.has("textures") && o.get("textures").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("textures").entrySet()) {
					if (e.getValue().isJsonPrimitive()) {
						textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
					}
				}
			}
			if (elements == null && o.has("elements") && o.get("elements").isJsonArray()) {
				elements = o.getAsJsonArray("elements");
			}
			JsonElement parent = o.get("parent");
			model = parent != null && parent.isJsonPrimitive() ? parent.getAsString() : null;
		}
		if (elements == null || elements.isEmpty()) {
			return null;
		}

		// Quader einlesen. Teile entstehen dabei in der Reihenfolge, in der
		// sie zuerst gebraucht werden.
		List<String> paths = new ArrayList<>();
		List<StudioItem.Box> boxes = new ArrayList<>();
		for (JsonElement el : elements) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject e = el.getAsJsonObject();
			float[] from = vec(e.get("from"));
			float[] to = vec(e.get("to"));
			if (from == null || to == null || !e.has("faces") || !e.get("faces").isJsonObject()) {
				continue;
			}
			JsonObject faces = e.getAsJsonObject("faces");
			// Standard-Ausschnitte wie in Minecraft, wenn "uv" fehlt.
			StudioItem.Face up = face(faces, "up", textures, paths,
				from[0], from[2], to[0], to[2]);
			StudioItem.Face north = face(faces, "north", textures, paths,
				16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]);
			StudioItem.Face east = face(faces, "east", textures, paths,
				16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]);
			if (up == null && north == null && east == null) {
				continue;
			}
			boxes.add(new StudioItem.Box(from[0], from[1], from[2], to[0], to[1], to[2], up, north, east));
		}
		if (boxes.isEmpty()) {
			return null;
		}
		// Hinten zuerst: Je weiter nach Osten, oben und Norden, desto naeher
		// am Betrachter. So verdecken vordere Quader die hinteren richtig.
		boxes.sort((a, b) -> Float.compare(
			(a.x1() + a.x2()) + (a.y1() + a.y2()) - (a.z1() + a.z2()),
			(b.x1() + b.x2()) + (b.y1() + b.y2()) - (b.z1() + b.z2())));

		// Uebrige Bilder des Modells (Partikel, Unterseite ...) auch als Teile.
		for (String v : textures.values()) {
			String p = resolve(v, textures);
			if (p != null && !paths.contains(p)) {
				paths.add(p);
			}
		}

		// Eigene Bilder: der Name passt zum Block ("oak_log" -> oak_log,
		// oak_log_top; "snow_block" -> snow). Alles andere ist geliehen
		// (Eichentreppe -> Eichenbretter, Werkbank -> Bretter unten).
		List<StudioItem.Part> parts = new ArrayList<>();
		for (String p : paths) {
			String name = p.substring(p.lastIndexOf('/') + 1);
			boolean own = name.startsWith(itemId) || itemId.startsWith(name);
			parts.add(new StudioItem.Part(p, legacyOf(p), own));
		}

		String block = item instanceof BlockItem bi ? Registries.BLOCK.getId(bi.getBlock()).getPath() : null;
		return new StudioItem(itemId, StudioItems.OTHER, List.copyOf(parts), List.copyOf(boxes), block);
	}

	private static float[] vec(JsonElement arr) {
		if (arr == null || !arr.isJsonArray() || arr.getAsJsonArray().size() != 3) {
			return null;
		}
		float[] v = new float[3];
		for (int i = 0; i < 3; i++) {
			JsonElement e = arr.getAsJsonArray().get(i);
			if (!e.isJsonPrimitive()) {
				return null;
			}
			v[i] = e.getAsFloat();
		}
		return v;
	}

	/** Eine Seite: Bild aufloesen, Teil-Nummer vergeben, Ausschnitt lesen. */
	private static StudioItem.Face face(JsonObject faces, String side, Map<String, String> textures,
	                                    List<String> paths, float du1, float dv1, float du2, float dv2) {
		JsonElement f = faces.get(side);
		if (f == null || !f.isJsonObject()) {
			return null;
		}
		String tex = faceTexture(faces, side, textures);
		if (tex == null) {
			return null;
		}
		int part = paths.indexOf(tex);
		if (part < 0) {
			paths.add(tex);
			part = paths.size() - 1;
		}
		float[] uv = {du1, dv1, du2, dv2};
		JsonElement u = f.getAsJsonObject().get("uv");
		if (u != null && u.isJsonArray() && u.getAsJsonArray().size() == 4) {
			for (int i = 0; i < 4; i++) {
				uv[i] = u.getAsJsonArray().get(i).getAsFloat();
			}
		}
		return new StudioItem.Face(part, uv[0], uv[1], uv[2], uv[3]);
	}

	private static String faceTexture(JsonObject faces, String side, Map<String, String> textures) {
		JsonElement f = faces.get(side);
		if (f == null || !f.isJsonObject() || !f.getAsJsonObject().has("texture")) {
			return null;
		}
		return resolve(f.getAsJsonObject().get("texture").getAsString(), textures);
	}

	/** "#side" -> "block/oak_log" ueber die Bildliste. null, wenn es kein Block-Bild ist. */
	private static String resolve(String ref, Map<String, String> textures) {
		String v = ref;
		for (int i = 0; v != null && v.startsWith("#") && i < 10; i++) {
			v = textures.get(v.substring(1));
		}
		if (v == null || v.startsWith("#")) {
			return null;
		}
		Identifier t = Identifier.tryParse(v);
		if (t == null || !Identifier.DEFAULT_NAMESPACE.equals(t.getNamespace())) {
			return null;
		}
		return t.getPath().startsWith("block/") ? t.getPath() : null;
	}

	/**
	 * Sucht im Item-Eintrag das Modell des Grundzustands.
	 *
	 * "special" (Schild, Kisten, Koepfe ...) wird bewusst NICHT aufgeloest:
	 * Diese Items zeichnet Minecraft aus mehreren Teilen, ein einzelnes
	 * Bild gibt es nicht.
	 */
	private static String baseModel(JsonElement node, int depth) {
		if (node == null || !node.isJsonObject() || depth > 8) {
			return null;
		}
		JsonObject o = node.getAsJsonObject();
		String type = o.has("type") ? o.get("type").getAsString() : "";
		if (type.endsWith("special")) {
			return null;
		}
		if (type.endsWith(":model") || type.equals("model")) {
			JsonElement m = o.get("model");
			return m != null && m.isJsonPrimitive() ? m.getAsString() : null;
		}
		// Je nach Ansicht verschieden (Fernrohr, Dreizack): die Fassung fuer
		// das Inventar ("gui") gewinnt - in der Hand sind sie 3D.
		JsonElement gui = guiCase(o);
		if (gui != null) {
			String r = baseModel(gui, depth + 1);
			if (r != null) {
				return r;
			}
		}
		for (String key : new String[] {"fallback", "on_false"}) {
			String r = baseModel(o.get(key), depth + 1);
			if (r != null) {
				return r;
			}
		}
		for (String key : new String[] {"cases", "entries", "models"}) {
			JsonElement arr = o.get(key);
			if (arr != null && arr.isJsonArray()) {
				JsonArray a = arr.getAsJsonArray();
				for (JsonElement e : a) {
					// cases/entries: {model: {...}}, models: {...} direkt
					JsonElement inner = e.isJsonObject() && e.getAsJsonObject().has("model")
						&& e.getAsJsonObject().get("model").isJsonObject()
						? e.getAsJsonObject().get("model") : e;
					String r = baseModel(inner, depth + 1);
					if (r != null) {
						return r;
					}
				}
			}
		}
		return null;
	}

	/** Bei select nach display_context: das Modell fuer "gui", sonst null. */
	private static JsonElement guiCase(JsonObject o) {
		JsonElement prop = o.get("property");
		JsonElement cases = o.get("cases");
		if (prop == null || !prop.isJsonPrimitive() || !prop.getAsString().endsWith("display_context")
			|| cases == null || !cases.isJsonArray()) {
			return null;
		}
		for (JsonElement c : cases.getAsJsonArray()) {
			if (!c.isJsonObject()) {
				continue;
			}
			JsonElement when = c.getAsJsonObject().get("when");
			boolean gui = when != null && (when.isJsonArray()
				? when.getAsJsonArray().contains(new com.google.gson.JsonPrimitive("gui"))
				: when.isJsonPrimitive() && when.getAsString().equals("gui"));
			if (gui) {
				return c.getAsJsonObject().get("model");
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Sondermodelle: Kisten, Betten, Shulker, Banner, Koepfe, Schild ...
	// ------------------------------------------------------------------

	/**
	 * Items, die Minecraft aus einem Wesen-Modell zeichnet. Ihre Bilder
	 * liegen unter textures/entity/ und sind "Faltbogen" - alle Seiten auf
	 * einem Bild. Die Vorschau zeigt den ganzen Bogen.
	 *
	 * Welche Bilder: aus dem Eintrag "texture" des Sondermodells, oder fest
	 * je Art (Schild, Banner, Koepfe). Nur Bilder, die es in Vanilla gibt.
	 * Spielerkoepfe fehlen - die zeigen einen Skin, kein Pack-Bild.
	 */
	private static StudioItem special(ResourcePack pack, Item item, String itemId, Set<List<String>> seen) throws Exception {
		JsonElement def = read(pack, Identifier.ofVanilla("items/" + itemId + ".json"));
		if (def == null || !def.isJsonObject()) {
			return null;
		}
		JsonObject sp = findSpecial(def.getAsJsonObject().get("model"), 0);
		if (sp == null) {
			return null;
		}
		String type = sp.has("type") ? path(sp.get("type").getAsString()) : "";
		String tex = sp.has("texture") ? path(sp.get("texture").getAsString()) : null;
		List<String> textures = new ArrayList<>();
		switch (type) {
			case "chest" -> {
				if (tex != null) {
					textures.add("entity/chest/" + tex);
					textures.add("entity/chest/" + tex + "_left");
					textures.add("entity/chest/" + tex + "_right");
				}
			}
			case "bed" -> {
				if (tex != null) {
					textures.add("entity/bed/" + tex);
				}
			}
			case "shulker_box" -> {
				if (tex != null) {
					textures.add("entity/shulker/" + tex);
				}
			}
			case "banner" -> textures.add("entity/banner_base");
			case "shield" -> {
				textures.add("entity/shield_base_nopattern");
				textures.add("entity/shield_base");
			}
			case "conduit" -> {
				textures.add("entity/conduit/base");
				textures.add("entity/conduit/cage");
			}
			case "decorated_pot" -> {
				textures.add("entity/decorated_pot/decorated_pot_base");
				textures.add("entity/decorated_pot/decorated_pot_side");
			}
			case "head" -> {
				String kind = sp.has("kind") ? sp.get("kind").getAsString() : "";
				String t = switch (kind) {
					case "skeleton" -> "entity/skeleton/skeleton";
					case "wither_skeleton" -> "entity/skeleton/wither_skeleton";
					case "zombie" -> "entity/zombie/zombie";
					case "creeper" -> "entity/creeper/creeper";
					case "piglin" -> "entity/piglin/piglin";
					case "dragon" -> "entity/enderdragon/dragon";
					default -> null;
				};
				if (t != null) {
					textures.add(t);
				}
			}
			default -> {
				// Kupfergolem-Statue u. ae.: "texture" ist ein voller Pfad.
				if (tex != null && tex.startsWith("textures/") && tex.endsWith(".png")) {
					textures.add(tex.substring(9, tex.length() - 4));
				}
			}
		}
		// Nur, was es wirklich gibt.
		textures.removeIf(t -> !exists(pack, "textures/" + t + ".png"));
		if (textures.isEmpty() || !seen.add(List.copyOf(textures))) {
			// Leer - oder dieselben Bilder wie ein Eintrag davor (16 Banner
			// teilen sich ein Bild, gewachste Statuen die ungewachsten).
			return null;
		}
		List<StudioItem.Part> parts = new ArrayList<>();
		for (String t : textures) {
			parts.add(new StudioItem.Part(t, List.of(), true));
		}
		String block = item instanceof BlockItem bi ? Registries.BLOCK.getId(bi.getBlock()).getPath() : null;
		return new StudioItem(itemId, StudioItems.OTHER, List.copyOf(parts), null, block);
	}

	/** Erstes Sondermodell im Item-Eintrag (Grundzustand bevorzugt). */
	private static JsonObject findSpecial(JsonElement node, int depth) {
		if (node == null || !node.isJsonObject() || depth > 8) {
			return null;
		}
		JsonObject o = node.getAsJsonObject();
		String type = o.has("type") ? o.get("type").getAsString() : "";
		if (type.endsWith("special")) {
			JsonElement m = o.get("model");
			return m != null && m.isJsonObject() ? m.getAsJsonObject() : null;
		}
		for (String key : new String[] {"fallback", "on_false"}) {
			JsonObject r = findSpecial(o.get(key), depth + 1);
			if (r != null) {
				return r;
			}
		}
		JsonElement cases = o.get("cases");
		if (cases != null && cases.isJsonArray()) {
			for (JsonElement c : cases.getAsJsonArray()) {
				if (c.isJsonObject()) {
					JsonObject r = findSpecial(c.getAsJsonObject().get("model"), depth + 1);
					if (r != null) {
						return r;
					}
				}
			}
		}
		return null;
	}

	private static String path(String ref) {
		Identifier id = Identifier.tryParse(ref);
		return id == null ? ref : id.getPath();
	}

	private static boolean exists(ResourcePack pack, String path) {
		try {
			return pack.open(ResourceType.CLIENT_RESOURCES, Identifier.ofVanilla(path)) != null;
		} catch (Throwable e) {
			return false;
		}
	}

	private static JsonElement read(ResourcePack pack, Identifier id) throws Exception {
		InputSupplier<InputStream> in = pack.open(ResourceType.CLIENT_RESOURCES, id);
		if (in == null) {
			return null;
		}
		try (InputStream s = in.get();
		     InputStreamReader r = new InputStreamReader(s, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r);
		}
	}
}
