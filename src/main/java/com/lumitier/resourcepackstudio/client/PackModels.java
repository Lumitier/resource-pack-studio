package com.lumitier.resourcepackstudio.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.StudioItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Eigene 3D-Modelle, die ein Pack fuer ein Item mitbringt - ein 3D-
 * Streitkolben, Kristalle, ein Schwert mit Griff ...
 *
 * WARUM: Solche Packs zeichnen ihre Textur fuer IHR Modell. Kopiert das
 * Studio nur das Bild, liegt es im neuen Pack auf dem flachen Vanilla-
 * Modell - und sieht kaputt aus. Hier wird erkannt, ob das Pack fuer das
 * Item ein Modell mit Quadern hat. Dann
 *  - zeigt die Vorschau es in 3D (wie die Bloecke, aus seinen Quadern),
 *  - kopiert der PackBuilder Item-Eintrag, Modelle und alle Bilder des
 *    Modells mit.
 *
 * WO SUCHEN: zuerst die Item-Definition des Packs (items/<id>.json, seit
 * 1.21.4) mit allen Modellen darin - viele Packs nehmen im Inventar das
 * flache Bild und nur in der Hand das 3D-Modell, gesucht wird das erste
 * mit Quadern. Sonst models/item/<id>.json, wie es aeltere Packs machen.
 *
 * GRENZEN: Gedrehte Quader werden ungedreht gezeichnet - die Vorschau ist
 * eine Annaeherung, das Pack im Spiel stimmt. Alte 1.8-Packs bleiben aussen
 * vor; ihre Modelle zeigen auf Bildpfade, die es seit 1.13 nicht mehr gibt.
 */
public final class PackModels {

	private PackModels() {}

	/**
	 * Ein Modell aus einem Pack.
	 *
	 * @param boxes    Quader in Zeichenreihenfolge; Face.part ist ein Index in textures
	 * @param textures Bilder im Pack, als Eintrag in der Zip bzw. Pfad im Ordner
	 * @param files    alles, was ins neue Pack muss (Item-Eintrag, Modelle, Bilder) -
	 *                 Eintrag im Quell-Pack -> Pfad im neuen Pack
	 */
	public record Model(List<StudioItem.Box> boxes, List<String> textures, Map<String, String> files) {}

	private static final int MAX_PARENTS = 8;

	/** Bereits gepruefte Varianten. Optional.empty(): flach, kein eigenes Modell. */
	private static final Map<PackScanner.Variant, Optional<Model>> CACHE = new ConcurrentHashMap<>();
	private static final Set<PackScanner.Variant> PENDING = ConcurrentHashMap.newKeySet();

	/**
	 * Zwei Leser fuer die Vorschau statt eines Threads pro Karte - bei 40
	 * Packs waeren das sonst 40 Threads, die gleichzeitig Zips oeffnen.
	 */
	private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "Resource Pack Studio - Models");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Verzeichnis jedes Packs (kleingeschrieben -> echter Eintrag), einmal
	 * gelesen. Beim Bauen wird fuer JEDES Item nach einem Modell gesucht -
	 * ohne diesen Speicher wuerde jede grosse Zip hunderte Male komplett
	 * durchgegangen.
	 */
	private static final Map<java.nio.file.Path, Map<String, String>> INDEX = new ConcurrentHashMap<>();

	/**
	 * Fuer die Vorschau: das Modell, oder null solange es geprueft wird oder
	 * wenn es keins gibt. Liest nie auf dem Render-Thread.
	 */
	public static Model preview(StudioItem item, PackScanner.Variant v) {
		if (v == null || v.legacy() || item.cube() || item.sound() || item.sky()
				|| item.texture() || item.particle()) {
			return null;
		}
		Optional<Model> m = CACHE.get(v);
		if (m != null) {
			return m.orElse(null);
		}
		if (PENDING.add(v)) {
			IO.execute(() -> {
				try {
					CACHE.put(v, Optional.ofNullable(load(item, v)));
				} finally {
					PENDING.remove(v);
				}
			});
		}
		return null;
	}

	/** Neue Suche: alles vergessen. */
	public static void clear() {
		CACHE.clear();
		INDEX.clear();
	}

	/**
	 * Liest das Modell. Fuer den PackBuilder (laeuft ohnehin im Hintergrund)
	 * und fuer preview(). null: Das Pack hat fuer das Item kein Modell mit Quadern.
	 */
	public static Model load(StudioItem item, PackScanner.Variant v) {
		if (v == null || v.legacy()) {
			return null;
		}
		String id = itemId(item);
		if (id == null) {
			return null;
		}
		try (Source src = new Source(v.pack())) {
			Map<String, String> files = new java.util.LinkedHashMap<>();
			List<String> candidates = new ArrayList<>();
			String defPath = "assets/minecraft/items/" + id + ".json";
			JsonElement def = src.json(defPath);
			if (def != null) {
				collectModels(def, candidates, 0);
			}
			candidates.add("minecraft:item/" + id);

			for (String start : candidates) {
				Model m = model(src, start, files);
				if (m != null) {
					if (def != null) {
						files.put(src.entry(defPath), defPath);
					}
					return new Model(m.boxes(), m.textures(), files);
				}
				files.clear();
			}
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.debug("[Resource Pack Studio] Could not read the model of {} in {}",
				id, v.pack().name(), e);
		}
		return null;
	}

	/** Kennung des Items, oder null, wenn der Eintrag kein Item ist. */
	private static String itemId(StudioItem item) {
		String id = item.id();
		if (id.indexOf(':') >= 0) {
			return null;
		}
		return id;
	}

	/** Alle Modell-Verweise einer Item-Definition, in der Reihenfolge, in der sie stehen. */
	private static void collectModels(JsonElement node, List<String> out, int depth) {
		if (node == null || depth > 12) {
			return;
		}
		if (node.isJsonArray()) {
			for (JsonElement e : node.getAsJsonArray()) {
				collectModels(e, out, depth + 1);
			}
			return;
		}
		if (!node.isJsonObject()) {
			return;
		}
		JsonObject o = node.getAsJsonObject();
		JsonElement m = o.get("model");
		if (m != null && m.isJsonPrimitive() && !out.contains(m.getAsString())) {
			out.add(m.getAsString());
		}
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (!e.getKey().equals("model") || !e.getValue().isJsonPrimitive()) {
				collectModels(e.getValue(), out, depth + 1);
			}
		}
	}

	private static Model model(Source src, String start, Map<String, String> files) throws IOException {
		Map<String, String> textures = new HashMap<>();
		JsonArray elements = null;
		String model = start;
		for (int i = 0; model != null && i < MAX_PARENTS; i++) {
			String[] ns = split(model);
			String path = "assets/" + ns[0] + "/models/" + ns[1] + ".json";
			JsonElement m = src.json(path);
			if (m == null || !m.isJsonObject()) {
				// Nicht im Pack: ein Vanilla-Modell. Dort stehen fuer
				// Items keine eigenen Quader - weiter oben muss es sie geben.
				break;
			}
			files.put(src.entry(path), path);
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

		List<String> texList = new ArrayList<>();
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
			StudioItem.Face up = face(src, faces, "up", textures, texList, from[0], from[2], to[0], to[2]);
			StudioItem.Face north = face(src, faces, "north", textures, texList,
				16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]);
			StudioItem.Face east = face(src, faces, "east", textures, texList,
				16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]);
			if (up == null && north == null && east == null) {
				continue;
			}
			boxes.add(new StudioItem.Box(from[0], from[1], from[2], to[0], to[1], to[2], up, north, east));
		}
		if (boxes.isEmpty()) {
			return null;
		}
		// Hinten zuerst - wie bei den Bloecken.
		boxes.sort((a, b) -> Float.compare(
			(a.x1() + a.x2()) + (a.y1() + a.y2()) - (a.z1() + a.z2()),
			(b.x1() + b.x2()) + (b.y1() + b.y2()) - (b.z1() + b.z2())));

		// Alle Bilder des Modells ins neue Pack - auch die, die in der
		// Vorschau nicht zu sehen sind (Unterseite, Partikel).
		for (String t : textures.values()) {
			String p = resolve(t, textures);
			if (p != null) {
				texturePath(src, p, files);
			}
		}
		for (String entry : texList) {
			files.putIfAbsent(entry, src.relative(entry));
		}
		return new Model(boxes, texList, files);
	}

	private static StudioItem.Face face(Source src, JsonObject faces, String side, Map<String, String> textures,
	                                    List<String> texList, float u1, float v1, float u2, float v2) throws IOException {
		JsonElement f = faces.get(side);
		if (f == null || !f.isJsonObject()) {
			return null;
		}
		JsonObject o = f.getAsJsonObject();
		String ref = o.has("texture") ? o.get("texture").getAsString() : null;
		String p = ref == null ? null : resolve(ref, textures);
		if (p == null) {
			return null;
		}
		String entry = texturePath(src, p, null);
		if (entry == null) {
			// Bild nicht im Pack: In der Vorschau leer lassen. Im Spiel
			// nimmt Minecraft das Vanilla-Bild.
			return null;
		}
		int index = texList.indexOf(entry);
		if (index < 0) {
			texList.add(entry);
			index = texList.size() - 1;
		}
		float[] uv = vec4(o.get("uv"));
		if (uv != null) {
			u1 = uv[0];
			v1 = uv[1];
			u2 = uv[2];
			v2 = uv[3];
		}
		int rot = o.has("rotation") ? ((o.get("rotation").getAsInt() / 90) % 4 + 4) % 4 : 0;
		return new StudioItem.Face(index, u1, v1, u2, v2, rot);
	}

	/**
	 * Eintrag des Bildes im Pack, oder null, wenn das Pack es nicht hat.
	 * Mit files != null wird es (samt .mcmeta) zum Kopieren vorgemerkt.
	 */
	private static String texturePath(Source src, String texture, Map<String, String> files) throws IOException {
		String[] ns = split(texture);
		String path = "assets/" + ns[0] + "/textures/" + ns[1] + ".png";
		String entry = src.find(path);
		if (entry != null && files != null) {
			files.put(entry, path);
			String meta = src.find(path + ".mcmeta");
			if (meta != null) {
				files.put(meta, path + ".mcmeta");
			}
		}
		return entry;
	}

	/** "#layer0" ueber die Bildliste aufloesen; null bei Endlosschleife oder fehlendem Eintrag. */
	private static String resolve(String ref, Map<String, String> textures) {
		String r = ref;
		for (int i = 0; r != null && r.startsWith("#") && i < 8; i++) {
			r = textures.get(r.substring(1));
		}
		return r == null || r.startsWith("#") ? null : r;
	}

	private static String[] split(String id) {
		int c = id.indexOf(':');
		return c < 0 ? new String[] {"minecraft", id} : new String[] {id.substring(0, c), id.substring(c + 1)};
	}

	private static float[] vec(JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	private static float[] vec4(JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 4) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
	}

	/**
	 * Liest aus einem Pack, Zip oder Ordner, ohne auf Gross-/Kleinschreibung
	 * zu achten - manche Packs schreiben ihre Dateinamen anders, als die
	 * Modelle sie nennen, und Minecraft verzeiht das bei Ordnern unter Windows.
	 */
	private static final class Source implements AutoCloseable {
		private final PackScanner.Pack pack;
		private final ZipFile zip;
		private Map<String, String> lower;

		Source(PackScanner.Pack pack) throws IOException {
			this.pack = pack;
			this.zip = pack.zip() ? new ZipFile(pack.path().toFile()) : null;
		}

		/** Echter Eintrag fuer einen Pfad unter assets/, oder null. */
		String find(String path) {
			String want = (pack.prefix() + path).toLowerCase(java.util.Locale.ROOT);
			if (lower == null) {
				lower = INDEX.computeIfAbsent(pack.path(), k -> readIndex());
			}
			return lower.get(want);
		}

		private Map<String, String> readIndex() {
			Map<String, String> lower = new HashMap<>();
			{
				if (zip != null) {
					java.util.Enumeration<? extends ZipEntry> en = zip.entries();
					while (en.hasMoreElements()) {
						String n = en.nextElement().getName();
						lower.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), n);
					}
				} else {
					Path assets = pack.path().resolve("assets");
					if (Files.isDirectory(assets)) {
						try (java.util.stream.Stream<Path> walk = Files.walk(assets, 16)) {
							walk.filter(Files::isRegularFile).forEach(f -> {
								String rel = pack.path().relativize(f).toString().replace('\\', '/');
								lower.putIfAbsent(rel.toLowerCase(java.util.Locale.ROOT), rel);
							});
						} catch (IOException ignored) {
						}
					}
				}
			}
			return lower;
		}

		/** Eintrag fuer einen Pfad, wie er im Pack steht - auch wenn es ihn nicht gibt. */
		String entry(String path) {
			String e = find(path);
			return e != null ? e : pack.prefix() + path;
		}

		/** Pfad im neuen Pack: Eintrag ohne den Unterordner des Quell-Packs. */
		String relative(String entry) {
			return entry.startsWith(pack.prefix()) ? entry.substring(pack.prefix().length()) : entry;
		}

		JsonElement json(String path) throws IOException {
			String e = find(path);
			if (e == null) {
				return null;
			}
			byte[] data = bytes(e);
			if (data == null) {
				return null;
			}
			try {
				return JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
			} catch (Exception bad) {
				return null;
			}
		}

		byte[] bytes(String entry) throws IOException {
			if (zip != null) {
				ZipEntry ze = zip.getEntry(entry);
				if (ze == null) {
					return null;
				}
				try (InputStream in = zip.getInputStream(ze)) {
					return in.readAllBytes();
				}
			}
			Path p = pack.path().resolve(entry);
			return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
		}

		@Override
		public void close() throws IOException {
			if (zip != null) {
				zip.close();
			}
		}
	}
}
