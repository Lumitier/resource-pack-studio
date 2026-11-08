package com.lumitier.resourcepackstudio.client;

import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Laedt Vorschaubilder - aus den Packs und aus Vanilla.
 *
 * VANILLA KOMMT AUS MINECRAFTS EIGENEM STANDARD-PACK, nicht ueber die
 * normale Texturverwaltung. Die liefert, was die AKTIVEN Packs vorgeben -
 * die Sidebar soll aber immer Vanilla zeigen, egal was gerade an ist.
 *
 * PRO TEIL: Ein Block hat mehrere Bilder (oben, Seite ...). Jedes Item bzw.
 * jede Variante hat deshalb ein Feld mit einem Platz pro Teil.
 *
 * LADEN IM HINTERGRUND, ANMELDEN AUF DEM RENDER-THREAD: Das Lesen aus
 * Zips dauert, das soll kein Bild ruckeln lassen. Bis ein Bild da ist,
 * liefern vanilla() und variant() null und der Bildschirm zeichnet nichts.
 *
 * KEINE NEUEN OBJEKTE PRO BILD: Schluessel sind das Item bzw. die Variante
 * selbst. Feld und Ladeauftrag entstehen nur beim ersten Mal.
 *
 * AUFRAEUMEN: Jede Vorschau belegt Grafikspeicher. clear() gibt alle frei -
 * beim Schliessen des Studios und nach jeder neuen Suche.
 *
 * Alles hier ausser dem eigentlichen Lesen laeuft auf dem Render-Thread.
 */
public final class Textures {

	private Textures() {}

	/** Eine geladene Vorschau. h > w heisst: animiert, Einzelbilder untereinander. */
	public record Tex(Identifier id, int w, int h) {}

	/** Platzhalter fuer "laesst sich nicht lesen" - damit nicht endlos neu versucht wird. */
	private static final Tex FAILED = new Tex(null, 0, 0);

	/** Platzhalter fuer "wird gerade geladen". */
	private static final Tex LOADING = new Tex(null, -1, -1);

	private static final Map<Object, Tex[]> LOADED = new HashMap<>();

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Resource Pack Studio - Textures");
		t.setDaemon(true);
		return t;
	});

	/** Zaehlt hoch, damit jede Textur eine eigene Kennung bekommt. */
	private static int counter;

	/**
	 * Wird bei jedem clear() erhoeht. Ein Ladevorgang, der vor dem
	 * Aufraeumen begonnen hat, meldet sein Ergebnis danach nicht mehr an.
	 */
	private static int generation;

	private interface Loader {
		NativeImage load() throws Exception;
	}

	/** Vanilla-Bild eines Teils, oder null solange es laedt oder fehlt. */
	public static Tex vanilla(StudioItem item, int part) {
		Tex[] slots = slots(item, item.parts().size());
		Tex t = slots[part];
		if (t != null) {
			return usable(t);
		}
		String modern = item.parts().get(part).modern();
		start(slots, part, item, () -> {
			InputSupplier<InputStream> in = MinecraftClient.getInstance().getDefaultResourcePack()
				.open(ResourceType.CLIENT_RESOURCES, Identifier.ofVanilla("textures/" + modern + ".png"));
			if (in == null) {
				return null;
			}
			try (InputStream s = in.get()) {
				return NativeImage.read(s);
			}
		});
		return null;
	}

	/**
	 * Bild eines Teils aus einem Pack, oder null solange es laedt, fehlt oder
	 * das Pack diesen Teil gar nicht hat (dann gilt Vanilla).
	 */
	public static Tex variant(PackScanner.Variant v, int part) {
		String entry = v.entries().get(part);
		if (entry == null) {
			return null;
		}
		Tex[] slots = slots(v, v.entries().size());
		Tex t = slots[part];
		if (t != null) {
			return usable(t);
		}
		start(slots, part, v, () -> {
			if (v.pack().zip()) {
				try (ZipFile zip = new ZipFile(v.pack().path().toFile())) {
					ZipEntry e = zip.getEntry(entry);
					if (e == null) {
						return null;
					}
					try (InputStream s = zip.getInputStream(e)) {
						return NativeImage.read(s);
					}
				}
			}
			try (InputStream s = Files.newInputStream(v.pack().path().resolve(entry))) {
				return NativeImage.read(s);
			}
		});
		return null;
	}

	private static Tex[] slots(Object key, int size) {
		Tex[] s = LOADED.get(key);
		if (s == null) {
			s = new Tex[size];
			LOADED.put(key, s);
		}
		return s;
	}

	private static Tex usable(Tex t) {
		return t.id() == null ? null : t;
	}

	private static void start(Tex[] slots, int part, Object key, Loader loader) {
		slots[part] = LOADING;
		int gen = generation;
		IO.execute(() -> {
			NativeImage img = null;
			try {
				img = loader.load();
			} catch (Throwable e) {
				ResourcePackStudio.LOGGER.debug("[Resource Pack Studio] Could not load preview {}#{}", key, part, e);
			}
			NativeImage done = img;
			MinecraftClient.getInstance().execute(() -> register(slots, part, done, gen));
		});
	}

	/** Render-Thread: fertiges Bild als Textur anmelden. */
	private static void register(Tex[] slots, int part, NativeImage img, int gen) {
		if (gen != generation) {
			// Inzwischen aufgeraeumt - das Bild gehoert zu einem alten Stand.
			if (img != null) {
				img.close();
			}
			return;
		}
		if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
			if (img != null) {
				img.close();
			}
			slots[part] = FAILED;
			return;
		}
		Identifier id = Identifier.of(ResourcePackStudio.MOD_ID, "preview/" + (counter++));
		int w = img.getWidth();
		int h = img.getHeight();
		MinecraftClient.getInstance().getTextureManager()
			.registerTexture(id, new NativeImageBackedTexture(() -> "Resource Pack Studio preview", img));
		slots[part] = new Tex(id, w, h);
	}

	/** Alle Vorschauen freigeben. Render-Thread. */
	public static void clear() {
		generation++;
		List<Identifier> ids = new ArrayList<>();
		for (Tex[] slots : LOADED.values()) {
			for (Tex t : slots) {
				if (t != null && t.id() != null) {
					ids.add(t.id());
				}
			}
		}
		LOADED.clear();
		for (Identifier id : ids) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		}
	}
}
