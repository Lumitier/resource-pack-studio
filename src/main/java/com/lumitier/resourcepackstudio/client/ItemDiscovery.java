package com.lumitier.resourcepackstudio.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import com.lumitier.resourcepackstudio.scan.StudioItems;
import net.minecraft.client.MinecraftClient;
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
import java.util.HashSet;
import java.util.List;
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
 * BLOECKE FEHLEN NOCH: Stein, Bretter usw. haben kein layer0, sondern ein
 * 3D-Modell mit mehreren Bildern. Die kommen in einer eigenen Phase.
 * Setzlinge, Blumen, Tueren usw. sind flach und deshalb schon dabei - ihr
 * Bild liegt teils im Block-Ordner.
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
		for (Item item : Registries.ITEM) {
			if (item == Items.AIR) {
				continue;
			}
			Identifier id = Registries.ITEM.getId(item);
			if (!Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace()) || known.contains(id.getPath())) {
				continue;
			}
			String tex;
			try {
				tex = flatTexture(pack, id.getPath());
			} catch (Throwable e) {
				tex = null;
			}
			if (tex == null) {
				skipped++;
				continue;
			}
			// 1.8 hatte "items" und "blocks" statt "item" und "block". Gleicher
			// Name im alten Ordner wird mitgesucht - fuer umbenannte Texturen
			// gibt es nur bei den PvP-Items eine Liste.
			String legacy = tex.startsWith("item/") ? "items/" + tex.substring(5)
				: tex.startsWith("block/") ? "blocks/" + tex.substring(6) : null;
			out.add(new StudioItem(id.getPath(), StudioItems.OTHER, tex,
				legacy == null ? List.of() : List.of(legacy)));
		}
		ResourcePackStudio.LOGGER.info("[Resource Pack Studio] {} flat item(s) found, {} skipped (blocks and special models).",
			out.size(), skipped);
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
