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
 * LADEN IM HINTERGRUND, ANMELDEN AUF DEM RENDER-THREAD: Das Lesen aus
 * Zips dauert, das soll kein Bild ruckeln lassen. Die fertige Textur
 * anmelden darf aber nur der Render-Thread. Bis ein Bild da ist, liefern
 * vanilla() und variant() null und der Bildschirm zeichnet nichts.
 *
 * KEINE NEUEN OBJEKTE PRO BILD: Schluessel sind das Item bzw. die Variante
 * selbst, kein zusammengesetzter Text. Der Ladeauftrag wird nur beim
 * ersten Mal gebaut.
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

	private static final Map<Object, Tex> LOADED = new HashMap<>();

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

	/** Vanilla-Bild eines Items, oder null solange es laedt oder fehlt. */
	public static Tex vanilla(StudioItem item) {
		Tex t = LOADED.get(item);
		if (t != null) {
			return usable(t);
		}
		start(item, () -> {
			InputSupplier<InputStream> in = MinecraftClient.getInstance().getDefaultResourcePack()
				.open(ResourceType.CLIENT_RESOURCES,
					Identifier.ofVanilla("textures/" + item.modern() + ".png"));
			if (in == null) {
				return null;
			}
			try (InputStream s = in.get()) {
				return NativeImage.read(s);
			}
		});
		return null;
	}

	/** Bild einer Variante aus einem Pack, oder null solange es laedt oder fehlt. */
	public static Tex variant(PackScanner.Variant v) {
		Tex t = LOADED.get(v);
		if (t != null) {
			return usable(t);
		}
		start(v, () -> {
			if (v.pack().zip()) {
				try (ZipFile zip = new ZipFile(v.pack().path().toFile())) {
					ZipEntry e = zip.getEntry(v.entry());
					if (e == null) {
						return null;
					}
					try (InputStream s = zip.getInputStream(e)) {
						return NativeImage.read(s);
					}
				}
			}
			try (InputStream s = Files.newInputStream(v.pack().path().resolve(v.entry()))) {
				return NativeImage.read(s);
			}
		});
		return null;
	}

	private static Tex usable(Tex t) {
		return t.id() == null ? null : t;
	}

	private static void start(Object key, Loader loader) {
		LOADED.put(key, LOADING);
		int gen = generation;
		IO.execute(() -> {
			NativeImage img = null;
			try {
				img = loader.load();
			} catch (Throwable e) {
				ResourcePackStudio.LOGGER.debug("[Resource Pack Studio] Could not load preview {}", key, e);
			}
			NativeImage done = img;
			MinecraftClient.getInstance().execute(() -> register(key, done, gen));
		});
	}

	/** Render-Thread: fertiges Bild als Textur anmelden. */
	private static void register(Object key, NativeImage img, int gen) {
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
			LOADED.put(key, FAILED);
			return;
		}
		Identifier id = Identifier.of(ResourcePackStudio.MOD_ID, "preview/" + (counter++));
		int w = img.getWidth();
		int h = img.getHeight();
		MinecraftClient.getInstance().getTextureManager()
			.registerTexture(id, new NativeImageBackedTexture(() -> "Resource Pack Studio preview", img));
		LOADED.put(key, new Tex(id, w, h));
	}

	/** Alle Vorschauen freigeben. Render-Thread. */
	public static void clear() {
		generation++;
		List<Identifier> ids = new ArrayList<>();
		for (Tex t : LOADED.values()) {
			if (t.id() != null) {
				ids.add(t.id());
			}
		}
		LOADED.clear();
		for (Identifier id : ids) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		}
	}
}
