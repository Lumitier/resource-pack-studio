package com.lumitier.resourcepackstudio.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

/**
 * Eigene Modelle fuer einen Eintrag mit geliehenen Bildern.
 *
 * BEISPIEL Eichentreppe: Ihre Modelle (oak_stairs, _inner, _outer) zeigen
 * auf block/oak_planks. Wuerde das neue Pack einfach oak_planks ersetzen,
 * aendern sich auch alle Bretter, Stufen, Zaeune ... Stattdessen:
 *
 *  1. Das Bild kommt nach block/rps/oak_stairs/oak_planks (PackBuilder).
 *  2. Jedes Modell der Treppe wird aus Vanilla kopiert, mit dem neuen
 *     Bildpfad, nach models/block/rps/oak_stairs/...
 *  3. Der Blockzustand (blockstates/oak_stairs.json) und der Item-Eintrag
 *     (items/oak_stairs.json) werden kopiert und zeigen auf die Kopien.
 *
 * NIE WIRD EIN VANILLA-MODELL UEBERSCHRIEBEN - nur der Blockzustand und der
 * Item-Eintrag dieses einen Blocks. Gewachstes Kupfer benutzt z. B. das
 * Modell von copper_block; ueberschreiben wuerde auch normales Kupfer
 * aendern.
 *
 * GRENZE: Ersetzt wird nur, was ein Modell SELBST unter "textures" stehen
 * hat. Steht der geliehene Pfad nur in einem Eltern-Modell, bleibt der
 * Block wie in Vanilla (kommt bei Vanilla-Bloecken praktisch nicht vor).
 *
 * Laeuft im Hintergrund, liest nur aus Minecrafts Standard-Pack.
 */
final class ModelCopies {

	private ModelCopies() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * @param remap Vanilla-Bildpfad -> neuer Pfad, beide relativ zu textures/
	 * @return false, wenn kein Modell umgeleitet werden konnte
	 */
	static boolean write(ZipOutputStream zip, StudioItem item, Map<String, String> remap) throws IOException {
		ResourcePack pack = MinecraftClient.getInstance().getDefaultResourcePack();

		JsonElement state = item.block() == null ? null : read(pack, "blockstates/" + item.block() + ".json");
		JsonElement itemDef = read(pack, "items/" + item.id() + ".json");

		Set<String> models = new LinkedHashSet<>();
		collectModels(state, models);
		collectModels(itemDef, models);

		// Modell -> Kopie (nur Modelle, in denen wirklich etwas ersetzt wurde)
		Map<String, String> moved = new LinkedHashMap<>();
		for (String m : models) {
			JsonElement json = read(pack, "models/" + m + ".json");
			if (json == null || !json.isJsonObject()) {
				continue;
			}
			JsonObject copy = json.getAsJsonObject().deepCopy();
			if (!copy.has("textures") || !copy.get("textures").isJsonObject()) {
				continue;
			}
			boolean changed = false;
			JsonObject tex = copy.getAsJsonObject("textures");
			for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
				if (!e.getValue().isJsonPrimitive()) {
					continue;
				}
				String path = path(e.getValue().getAsString());
				if (path != null && remap.containsKey(path)) {
					e.setValue(new com.google.gson.JsonPrimitive("minecraft:" + remap.get(path)));
					changed = true;
				}
			}
			if (!changed) {
				continue;
			}
			String target = "block/rps/" + item.id() + "/" + m.substring(m.lastIndexOf('/') + 1);
			PackBuilder.put(zip, "assets/minecraft/models/" + target + ".json",
				GSON.toJson(copy).getBytes(StandardCharsets.UTF_8));
			moved.put(m, target);
		}
		if (moved.isEmpty()) {
			return false;
		}

		if (state != null) {
			JsonElement s = state.deepCopy();
			if (redirect(s, moved)) {
				PackBuilder.put(zip, "assets/minecraft/blockstates/" + item.block() + ".json",
					GSON.toJson(s).getBytes(StandardCharsets.UTF_8));
			}
		}
		if (itemDef != null) {
			JsonElement d = itemDef.deepCopy();
			if (redirect(d, moved)) {
				PackBuilder.put(zip, "assets/minecraft/items/" + item.id() + ".json",
					GSON.toJson(d).getBytes(StandardCharsets.UTF_8));
			}
		}
		return true;
	}

	/** Alle "model": "..."-Eintraege, ohne Namensraum ("block/oak_stairs"). */
	private static void collectModels(JsonElement e, Set<String> out) {
		if (e == null) {
			return;
		}
		if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) {
				collectModels(c, out);
			}
		} else if (e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				if (en.getKey().equals("model") && en.getValue().isJsonPrimitive()) {
					String p = path(en.getValue().getAsString());
					if (p != null) {
						out.add(p);
					}
				} else {
					collectModels(en.getValue(), out);
				}
			}
		}
	}

	/** Ersetzt "model"-Verweise auf umgeleitete Modelle. @return ob sich etwas geaendert hat */
	private static boolean redirect(JsonElement e, Map<String, String> moved) {
		boolean changed = false;
		if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) {
				changed |= redirect(c, moved);
			}
		} else if (e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				if (en.getKey().equals("model") && en.getValue().isJsonPrimitive()) {
					String p = path(en.getValue().getAsString());
					if (p != null && moved.containsKey(p)) {
						en.setValue(new com.google.gson.JsonPrimitive("minecraft:" + moved.get(p)));
						changed = true;
					}
				} else {
					changed |= redirect(en.getValue(), moved);
				}
			}
		}
		return changed;
	}

	/** "minecraft:block/x" oder "block/x" -> "block/x". Andere Namensraeume: null. */
	private static String path(String ref) {
		Identifier id = Identifier.tryParse(ref);
		return id != null && Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace()) ? id.getPath() : null;
	}

	private static JsonElement read(ResourcePack pack, String path) {
		try {
			InputSupplier<InputStream> in = pack.open(ResourceType.CLIENT_RESOURCES, Identifier.ofVanilla(path));
			if (in == null) {
				return null;
			}
			try (InputStream s = in.get();
			     InputStreamReader r = new InputStreamReader(s, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(r);
			}
		} catch (Exception e) {
			return null;
		}
	}
}
