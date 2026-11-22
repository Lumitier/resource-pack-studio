package com.lumitier.resourcepackstudio.client;

import com.lumitier.lumilib.client.Draw;
import com.lumitier.lumilib.client.Surface;
import com.lumitier.lumilib.client.Theme;
import com.lumitier.lumilib.client.Ui;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pixel-Editor fuer Texturen.
 *
 * AUFBAU (wie in gaengigen Malprogrammen):
 *   links   Werkzeugleiste: Stift, Radierer, Linie, Rechteck, Ellipse,
 *           Fuellen, Farbe aufnehmen, Verschieben; darunter Pinselgroesse
 *           und "gefuellt" fuer Formen.
 *   Mitte   Zeichenflaeche mit Zoom (Mausrad, zum Mauszeiger hin),
 *           Verschieben (mittlere Maustaste oder Leertaste + Ziehen),
 *           Pixelraster ab 6-fachem Zoom, Spiegeln an der Mittelachse.
 *           Darunter die Bilder (Partikel-Animation, Bloecke mit mehreren
 *           Seiten, animierte Texturen) und eine Statuszeile.
 *   rechts  Farben: Feld fuer Saettigung/Helligkeit, Farbton, Deckkraft,
 *           Hex-Eingabe, Haupt- und Zweitfarbe (Links-/Rechtsklick), die
 *           Farben im Bild, zuletzt benutzte; darunter Aufloesung,
 *           "Unsichtbar machen" (Partikel ausschalten), Abbrechen/Speichern.
 *
 * TASTEN: B Stift, E Radierer, L Linie, R Rechteck, O Ellipse, G Fuellen,
 * I Farbe, M Verschieben, X Farben tauschen, [ und ] Pinselgroesse,
 * # Raster, Strg+Z/Strg+Y rueckgaengig/wiederholen, Strg+S speichern,
 * Shift bei Formen: Quadrat/Kreis/45 Grad, Shift beim Fuellen: alle
 * Pixel dieser Farbe, Alt+Klick: Farbe aufnehmen.
 *
 * WOHIN: ins Pack "My Drawings" im Ordner des Studios. Das Studio durchsucht
 * es wie jedes andere Pack - die Zeichnung ist danach eine Variante, die
 * sich waehlen und ins eigene Pack bauen laesst.
 */
public final class EditorScreen extends Screen {

	/** Name des Packs, in dem die Zeichnungen landen. */
	public static final String DRAWINGS = "My Drawings";

	private static final int[] SIZES = {16, 32, 64, 128, 256, 512};
	private static final float[] ZOOMS = {0.25F, 0.5F, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64};

	private enum Tool {
		PEN(GLFW.GLFW_KEY_B, "B"), ERASER(GLFW.GLFW_KEY_E, "E"), LINE(GLFW.GLFW_KEY_L, "L"),
		RECT(GLFW.GLFW_KEY_R, "R"), ELLIPSE(GLFW.GLFW_KEY_O, "O"), FILL(GLFW.GLFW_KEY_G, "G"),
		PICK(GLFW.GLFW_KEY_I, "I"), MOVE(GLFW.GLFW_KEY_M, "M");

		final int key;
		final String keyName;

		Tool(int key, String keyName) {
			this.key = key;
			this.keyName = keyName;
		}

		boolean shape() {
			return this == LINE || this == RECT || this == ELLIPSE;
		}
	}

	/** Werkzeug-Symbole, 10x10, Pixel fuer Pixel - passt zum Look von Minecraft. */
	private static final String[][] GLYPHS = {
		{"........##", ".......#.#", "......#.#.", ".....#.#..", "....#.#...",
			"...#.#....", "..#.#.....", ".##.......", "###.......", "##........"},
		{"..........", "....#####.", "...#....##", "..#....#.#", ".#....#..#",
			"######..#.", "#....#.#..", "#....##...", "######....", ".........."},
		{"#.........", ".#........", "..#.......", "...#......", "....#.....",
			".....#....", "......#...", ".......#..", "........#.", ".........#"},
		{"##########", "#........#", "#........#", "#........#", "#........#",
			"#........#", "#........#", "#........#", "#........#", "##########"},
		{"...####...", ".##....##.", ".#......#.", "#........#", "#........#",
			"#........#", "#........#", ".#......#.", ".##....##.", "...####..."},
		{"....#.....", "...#.#....", "..#...#...", ".#.....#..", "#.......#.",
			".#.....###", "..#...####", "...#.#.###", "....#...#.", ".........."},
		{".......##.", "......####", ".....#####", "......###.", ".....#.#..",
			"....#.....", "...#......", "..#.......", ".#........", "#........."},
		{"....##....", "...####...", "....##....", ".#..##..#.", "##########",
			"##########", ".#..##..#.", "....##....", "...####...", "....##...."},
	};

	// ------------------------------------------------------------------
	// Zustand
	// ------------------------------------------------------------------

	private final Screen parent;
	private final StudioItem item;
	private final String title;

	/** Die Bilder: je Teil eins, oder je Bild einer animierten Textur. */
	private final List<NativeImage> frames = new ArrayList<>();
	private final List<String> frameNames = new ArrayList<>();
	/** Animierte Textur (Bilder untereinander): beim Speichern wieder zusammensetzen. */
	private final boolean strip;
	/** Die .png.mcmeta der Quelle (Animation), oder null. */
	private final byte[] stripMeta;
	private int frame;

	/** Was auf dem Bildschirm zu sehen ist: eine Kopie des aktuellen Bilds. */
	private NativeImage view;
	private NativeImageBackedTexture viewTexture;
	private final Identifier viewId = Identifier.of(ResourcePackStudio.MOD_ID, "editor/canvas");
	private boolean viewDirty = true;

	private NativeImage sv;
	private NativeImageBackedTexture svTexture;
	private final Identifier svId = Identifier.of(ResourcePackStudio.MOD_ID, "editor/sv");
	private float svHue = -1.0F;

	private Tool tool = Tool.PEN;
	private int brush = 1;
	private boolean filledShapes;
	private boolean mirror;
	private boolean grid = true;

	private int primary = 0xFF000000;
	private int secondary = 0x00000000;
	private float hue, sat, val;
	private final Deque<Integer> recent = new ArrayDeque<>();
	private int[] imageColours = new int[0];
	private boolean coloursDirty = true;

	private record Snap(int frame, int w, int h, int[] px) {}

	private final Deque<List<Snap>> undo = new ArrayDeque<>();
	private final Deque<List<Snap>> redo = new ArrayDeque<>();
	private static final int UNDO_MAX = 60;

	// Ziehen
	private boolean painting;
	private int paintColour;
	private int startPx, startPy, lastPx = -1, lastPy = -1;
	private int[] dragBase;
	private boolean panning;
	private boolean spaceDown;
	private int dragSv = 0; // 1 Feld, 2 Farbton, 3 Deckkraft

	// Ansicht
	private float zoom = 8;
	private float panX, panY;
	private boolean fitted;

	// Hex-Eingabe
	private boolean hexFocus;
	private final StringBuilder hexText = new StringBuilder();

	private String message = "";
	private boolean messageWarn;

	// Lage aus dem letzten Bild
	private int vx, vy, vw, vh; // Zeichenbereich
	private int px, py, pw, ph; // ganzes Fenster
	private int toolX, toolY;
	private int rightX, rightW;
	private final List<int[]> hits = new ArrayList<>(); // {x, y, w, h, action}
	private String tooltip;
	private int tooltipX, tooltipY;

	private static final int TOOL_BTN = 24;
	private static final int BTN_H = 18;
	private static final int GAP = 4;
	private static final int RIGHT_W = 158;
	private static final int TOOL_W = 32;

	public EditorScreen(Screen parent, StudioItem item, PackScanner.Variant start, String name) {
		super(Text.translatable("resourcepackstudio.editor.title"));
		this.parent = parent;
		this.item = item;
		this.title = name;

		List<StudioItem.Part> parts = item.parts();
		boolean isStrip = false;
		byte[] meta = null;
		for (int i = 0; i < parts.size(); i++) {
			NativeImage img = loadPart(item, start, i);
			if (img == null) {
				img = blank(16, 16);
			}
			String label = parts.size() == 1 ? "" : partLabel(parts.get(i).modern(), i);
			int w = img.getWidth();
			int h = img.getHeight();
			if (parts.size() == 1 && h > w && h % w == 0) {
				// Animiert: Bilder untereinander. Einzeln bearbeiten, beim
				// Speichern wieder zusammensetzen - samt Animationsdatei.
				isStrip = true;
				meta = readSource(item, start, 0, ".mcmeta");
				for (int f = 0; f < h / w; f++) {
					NativeImage part = new NativeImage(w, w, true);
					for (int y = 0; y < w; y++) {
						for (int x = 0; x < w; x++) {
							part.setColorArgb(x, y, img.getColorArgb(x, f * w + y));
						}
					}
					frames.add(part);
					frameNames.add(Integer.toString(f + 1));
				}
				img.close();
			} else {
				frames.add(img);
				frameNames.add(label);
			}
		}
		this.strip = isStrip;
		this.stripMeta = meta;
		setPrimary(0xFF000000);

		this.sv = new NativeImage(64, 64, true);
		this.svTexture = new NativeImageBackedTexture(() -> "Resource Pack Studio colour field", sv);
		MinecraftClient.getInstance().getTextureManager().registerTexture(svId, svTexture);
	}

	/** "particle/explosion_3" -> "4" (Partikel), "block/oak_log_top" -> "oak log top". */
	private String partLabel(String modern, int index) {
		if (item.particle()) {
			return Integer.toString(index + 1);
		}
		String n = modern.substring(modern.lastIndexOf('/') + 1).replace('_', ' ');
		return n.isEmpty() ? Integer.toString(index + 1) : n;
	}

	/**
	 * Was laesst sich bearbeiten? Alles, was aus Bildern besteht - Items,
	 * Bloecke (jede Seite einzeln), Partikel (jedes Bild der Animation),
	 * HUD, Texturen. Nicht: Sounds und Himmel (ganze Ordner).
	 */
	public static boolean editable(StudioItem item) {
		return !item.sound() && !item.sky() && !item.parts().isEmpty();
	}

	private static NativeImage blank(int w, int h) {
		NativeImage img = new NativeImage(w, h, true);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setColorArgb(x, y, 0);
			}
		}
		return img;
	}

	// ------------------------------------------------------------------
	// Laden und Speichern
	// ------------------------------------------------------------------

	private static NativeImage loadPart(StudioItem item, PackScanner.Variant v, int part) {
		byte[] data = readSource(item, v, part, "");
		if (data == null) {
			return null;
		}
		try {
			return NativeImage.read(data);
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not read the image for {}", item.id(), e);
			return null;
		}
	}

	/**
	 * Datei eines Teils aus dem gewaehlten Pack, sonst aus Vanilla. suffix ""
	 * fuer das Bild, ".mcmeta" fuer seine Animationsdatei.
	 */
	private static byte[] readSource(StudioItem item, PackScanner.Variant v, int part, String suffix) {
		try {
			if (v != null && part < v.entries().size() && v.entries().get(part) != null) {
				String entry = v.entries().get(part) + suffix;
				if (v.pack().zip()) {
					try (ZipFile zip = new ZipFile(v.pack().path().toFile())) {
						ZipEntry e = zip.getEntry(entry);
						if (e != null) {
							try (InputStream in = zip.getInputStream(e)) {
								return in.readAllBytes();
							}
						}
					}
				} else {
					Path p = v.pack().path().resolve(entry);
					if (Files.isRegularFile(p)) {
						return Files.readAllBytes(p);
					}
				}
				if (!suffix.isEmpty()) {
					return null;
				}
			}
			InputSupplier<InputStream> in = MinecraftClient.getInstance().getDefaultResourcePack()
				.open(ResourceType.CLIENT_RESOURCES,
					Identifier.ofVanilla("textures/" + item.parts().get(part).modern() + ".png" + suffix));
			if (in != null) {
				try (InputStream s = in.get()) {
					return s.readAllBytes();
				}
			}
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not load {} for {}", suffix, item.id(), e);
		}
		return null;
	}

	/** Pfad der Zeichnung im Pack "My Drawings" (erster Teil). */
	public static Path drawingFile(StudioItem item) {
		return drawingFile(item, 0);
	}

	private static Path drawingFile(StudioItem item, int part) {
		return drawingsPack().resolve("assets/minecraft/textures/" + item.parts().get(part).modern() + ".png");
	}

	public static Path drawingsPack() {
		return ResourcePackStudio.packsDir().resolve(DRAWINGS);
	}

	/** Das Pack "My Drawings" anlegen, falls es noch keins gibt. */
	public static Path ensureDrawingsPack() throws java.io.IOException {
		Path pack = drawingsPack();
		Files.createDirectories(pack);
		Path meta = pack.resolve("pack.mcmeta");
		if (!Files.isRegularFile(meta)) {
			Files.writeString(meta, "{\n  \"pack\": {\n    \"description\": \"Drawings made in Resource Pack Studio\",\n"
				+ "    \"min_format\": " + PackBuilder.PACK_FORMAT + ",\n    \"max_format\": " + PackBuilder.PACK_FORMAT
				+ "\n  }\n}\n", StandardCharsets.UTF_8);
		}
		return pack;
	}

	/**
	 * Eine PNG von der Festplatte als Bild fuer diesen Eintrag uebernehmen -
	 * landet wie eine Zeichnung in "My Drawings". @return Fehler-Schluessel
	 * oder null.
	 */
	public static String importPng(StudioItem item, Path file) {
		try {
			byte[] data = Files.readAllBytes(file);
			try (NativeImage img = NativeImage.read(data)) {
				if (img.getWidth() < 1 || img.getHeight() < 1) {
					return "resourcepackstudio.icon.not_png";
				}
			}
			Path pack = ensureDrawingsPack();
			Path target = drawingFile(item, 0);
			Files.createDirectories(target.getParent());
			Files.write(target, data);
			StudioIndex.selectAfterScan(item.id(), pack);
			StudioIndex.rescan();
			return null;
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not import {}", file, e);
			return "resourcepackstudio.icon.not_png";
		}
	}

	private void save() {
		try {
			Path pack = ensureDrawingsPack();
			if (strip) {
				int w = frames.get(0).getWidth();
				try (NativeImage joined = new NativeImage(w, w * frames.size(), true)) {
					for (int f = 0; f < frames.size(); f++) {
						NativeImage src = frames.get(f);
						for (int y = 0; y < w; y++) {
							for (int x = 0; x < w; x++) {
								joined.setColorArgb(x, f * w + y, src.getColorArgb(x, y));
							}
						}
					}
					Path file = drawingFile(item, 0);
					Files.createDirectories(file.getParent());
					joined.writeTo(file);
					if (stripMeta != null) {
						Files.write(file.resolveSibling(file.getFileName() + ".mcmeta"), stripMeta);
					}
				}
			} else {
				for (int i = 0; i < frames.size(); i++) {
					Path file = drawingFile(item, i);
					Files.createDirectories(file.getParent());
					frames.get(i).writeTo(file);
				}
			}
			StudioIndex.selectAfterScan(item.id(), pack);
			StudioIndex.rescan();
			MinecraftClient.getInstance().setScreen(parent);
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.error("[Resource Pack Studio] Could not save the drawing.", e);
			message = Text.translatable("resourcepackstudio.editor.save_failed").getString();
			messageWarn = true;
		}
	}

	@Override
	public void removed() {
		var tm = MinecraftClient.getInstance().getTextureManager();
		tm.destroyTexture(viewId);
		tm.destroyTexture(svId);
		for (NativeImage img : frames) {
			img.close();
		}
		frames.clear();
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private NativeImage cur() {
		return frames.get(frame);
	}

	// ------------------------------------------------------------------
	// Farben
	// ------------------------------------------------------------------

	private void setPrimary(int argb) {
		this.primary = argb;
		float[] hsv = toHsv(argb);
		// Grau hat keinen Farbton - den alten behalten, sonst springt der Regler.
		if (hsv[1] > 0.0F && hsv[2] > 0.0F) {
			this.hue = hsv[0];
		}
		this.sat = hsv[1];
		this.val = hsv[2];
		if (!hexFocus) {
			hexText.setLength(0);
			hexText.append(hex(argb));
		}
	}

	private void fromHsv() {
		int a = primary >>> 24;
		this.primary = (a << 24) | (hsvToRgb(hue, sat, val) & 0xFFFFFF);
		if (!hexFocus) {
			hexText.setLength(0);
			hexText.append(hex(primary));
		}
	}

	private static String hex(int argb) {
		int a = argb >>> 24;
		return a == 255 ? String.format("%06X", argb & 0xFFFFFF) : String.format("%06X%02X", argb & 0xFFFFFF, a);
	}

	private static float[] toHsv(int argb) {
		float r = ((argb >> 16) & 255) / 255.0F;
		float g = ((argb >> 8) & 255) / 255.0F;
		float b = (argb & 255) / 255.0F;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h = 0.0F;
		if (d > 0.0F) {
			if (max == r) {
				h = ((g - b) / d) % 6.0F;
			} else if (max == g) {
				h = (b - r) / d + 2.0F;
			} else {
				h = (r - g) / d + 4.0F;
			}
			h /= 6.0F;
			if (h < 0.0F) {
				h += 1.0F;
			}
		}
		return new float[] {h, max == 0.0F ? 0.0F : d / max, max};
	}

	private static int hsvToRgb(float h, float s, float v) {
		float hh = (h % 1.0F) * 6.0F;
		int i = (int) Math.floor(hh);
		float f = hh - i;
		float p = v * (1 - s);
		float q = v * (1 - s * f);
		float t = v * (1 - s * (1 - f));
		float r, g, b;
		switch (i) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}

	private void remember(int c) {
		if ((c >>> 24) == 0) {
			return;
		}
		recent.remove(c);
		recent.addFirst(c);
		while (recent.size() > 10) {
			recent.removeLast();
		}
	}

	/** Die Farben im aktuellen Bild, haeufigste zuerst, hoechstens 30. */
	private void collectColours() {
		NativeImage img = cur();
		java.util.HashMap<Integer, Integer> count = new java.util.HashMap<>();
		int step = Math.max(1, (img.getWidth() * img.getHeight()) / 65536);
		int i = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if (i++ % step != 0) {
					continue;
				}
				int c = img.getColorArgb(x, y);
				if ((c >>> 24) != 0) {
					count.merge(c, 1, Integer::sum);
				}
			}
		}
		this.imageColours = count.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(30)
			.mapToInt(java.util.Map.Entry::getKey)
			.toArray();
		coloursDirty = false;
	}

	// ------------------------------------------------------------------
	// Zeichnen auf den Bildschirm
	// ------------------------------------------------------------------

	private void syncView() {
		NativeImage img = cur();
		var tm = MinecraftClient.getInstance().getTextureManager();
		if (view == null || view.getWidth() != img.getWidth() || view.getHeight() != img.getHeight()) {
			// Andere Groesse: neue Textur. destroyTexture schliesst auch das alte Bild.
			tm.destroyTexture(viewId);
			view = new NativeImage(img.getWidth(), img.getHeight(), true);
			viewTexture = new NativeImageBackedTexture(() -> "Resource Pack Studio editor", view);
			tm.registerTexture(viewId, viewTexture);
		}
		view.copyFrom(img);
		viewTexture.upload();
		viewDirty = false;
	}

	private void syncSv() {
		if (svHue == hue) {
			return;
		}
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				sv.setColorArgb(x, y, hsvToRgb(hue, x / 63.0F, 1.0F - y / 63.0F));
			}
		}
		svTexture.upload();
		svHue = hue;
	}

	private void fit() {
		NativeImage img = cur();
		float best = ZOOMS[0];
		for (float z : ZOOMS) {
			if (img.getWidth() * z <= vw - 24 && img.getHeight() * z <= vh - 24) {
				best = z;
			}
		}
		zoom = best;
		panX = (vw - img.getWidth() * zoom) / 2.0F;
		panY = (vh - img.getHeight() * zoom) / 2.0F;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		hits.clear();
		tooltip = null;
		if (viewDirty) {
			syncView();
		}
		if (coloursDirty) {
			collectColours();
		}
		syncSv();

		int pad = 10;
		this.pw = Math.min(this.width - 16, 760);
		this.ph = Math.min(this.height - 16, 440);
		this.px = (this.width - pw) / 2;
		this.py = (this.height - ph) / 2;
		Surface.card(ctx, px, py, pw, ph, Theme.PANEL | 0xFF000000);

		// Kopf: Titel links, rechts Rueckgaengig, Wiederholen, Raster, Spiegeln.
		int headH = 26;
		Ui.drawCenteredIn(ctx, this.textRenderer,
			Ui.bold(Ui.fitBold(this.textRenderer, title, pw / 2)), px + pad, py + 4, headH,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT);
		int hx = px + pw - pad;
		hx = headButton(ctx, hx, py + 4 + (headH - BTN_H) / 2, "mirror", mirror, mouseX, mouseY, 10);
		hx = headButton(ctx, hx, py + 4 + (headH - BTN_H) / 2, "grid", grid, mouseX, mouseY, 11);
		hx = headButton(ctx, hx, py + 4 + (headH - BTN_H) / 2, "redo", false, mouseX, mouseY, 12);
		headButton(ctx, hx, py + 4 + (headH - BTN_H) / 2, "undo", false, mouseX, mouseY, 13);
		int top = py + 4 + headH + 4;
		Draw.hLine(ctx, px + pad, top - 3, pw - pad * 2, Theme.DIVIDER);

		// Spalten
		this.toolX = px + pad;
		this.toolY = top + 2;
		this.rightW = RIGHT_W;
		this.rightX = px + pw - pad - rightW;
		int framesH = frames.size() > 1 ? 26 : 0;
		int statusH = 14;
		this.vx = toolX + TOOL_W + 6;
		this.vy = top + 2;
		this.vw = rightX - 8 - vx;
		this.vh = py + ph - pad - vy - framesH - statusH;
		if (!fitted) {
			fit();
			fitted = true;
		}

		renderTools(ctx, mouseX, mouseY);
		renderCanvas(ctx, mouseX, mouseY);
		if (framesH > 0) {
			renderFrames(ctx, vx, vy + vh + 4, vw, mouseX, mouseY);
		}
		renderStatus(ctx, vx, py + ph - pad - statusH + 3, mouseX, mouseY);
		renderRight(ctx, mouseX, mouseY);

		if (tooltip != null) {
			int tw = Ui.widthSmall(this.textRenderer, tooltip) + 12;
			int tx = Math.min(tooltipX + 10, this.width - tw - 4);
			int ty = tooltipY + 12;
			Surface.row(ctx, tx, ty, tw, 16, Theme.PANEL | 0xFF000000);
			Draw.roundedOutline(ctx, tx, ty, tw, 16, 3, Theme.DIVIDER);
			Ui.drawCenteredIn(ctx, this.textRenderer, Ui.small(tooltip), tx + 6, ty, 16,
				Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT);
		}
	}

	private int headButton(DrawContext ctx, int right, int y, String key, boolean on, int mx, int my, int action) {
		String label = Text.translatable("resourcepackstudio.editor." + key).getString();
		int w = Ui.width(this.textRenderer, label) + 16;
		int x = right - w;
		boolean off = (action == 12 && redo.isEmpty()) || (action == 13 && undo.isEmpty());
		button(ctx, x, y, w, BTN_H, label, on, off, mx, my);
		hits.add(new int[] {x, y, w, BTN_H, action});
		return x - GAP;
	}

	private void renderTools(DrawContext ctx, int mx, int my) {
		Tool[] tools = Tool.values();
		int x = toolX + (TOOL_W - TOOL_BTN) / 2;
		int y = toolY;
		for (int i = 0; i < tools.length; i++) {
			boolean on = tool == tools[i];
			boolean hov = Draw.inside(mx, my, x, y, TOOL_BTN, TOOL_BTN);
			Surface.row(ctx, x, y, TOOL_BTN, TOOL_BTN, on ? Theme.TOGGLE_ON : hov ? Theme.FIELD_HOVER : Theme.FIELD);
			glyph(ctx, GLYPHS[i], x + 2, y + 2, 2, on ? 0xFFFFFFFF : Theme.TEXT);
			if (hov) {
				tip(Text.translatable("resourcepackstudio.editor.tool." + tools[i].name().toLowerCase(Locale.ROOT)).getString()
					+ "  (" + tools[i].keyName + ")", mx, my);
			}
			hits.add(new int[] {x, y, TOOL_BTN, TOOL_BTN, 100 + i});
			y += TOOL_BTN + 3;
		}
		y += 6;
		Draw.hLine(ctx, toolX + 4, y - 4, TOOL_W - 8, Theme.DIVIDER);
		// Pinselgroesse: Zahl, darueber/darunter + und -.
		int bx = toolX + (TOOL_W - TOOL_BTN) / 2;
		smallButton(ctx, bx, y, TOOL_BTN, 14, "+", mx, my, 20);
		y += 16;
		String size = brush + "px";
		int sw = Ui.widthSmall(this.textRenderer, size);
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.small(size), toolX + (TOOL_W - sw) / 2, y, 14,
			Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT_MUTED);
		if (Draw.inside(mx, my, toolX, y, TOOL_W, 14)) {
			tip(Text.translatable("resourcepackstudio.editor.brush").getString() + "  ([ ])", mx, my);
		}
		y += 16;
		smallButton(ctx, bx, y, TOOL_BTN, 14, "-", mx, my, 21);
		y += 20;
		if (tool == Tool.RECT || tool == Tool.ELLIPSE) {
			boolean hov = Draw.inside(mx, my, bx, y, TOOL_BTN, TOOL_BTN);
			Surface.row(ctx, bx, y, TOOL_BTN, TOOL_BTN, filledShapes ? Theme.TOGGLE_ON : hov ? Theme.FIELD_HOVER : Theme.FIELD);
			int c = filledShapes ? 0xFFFFFFFF : Theme.TEXT;
			if (tool == Tool.RECT) {
				ctx.fill(bx + 6, y + 6, bx + 18, y + 18, c);
			} else {
				Draw.dot(ctx, bx + 12, y + 12, 6, c);
			}
			if (hov) {
				tip(Text.translatable("resourcepackstudio.editor.filled").getString(), mx, my);
			}
			hits.add(new int[] {bx, y, TOOL_BTN, TOOL_BTN, 22});
		}
	}

	private void renderCanvas(DrawContext ctx, int mx, int my) {
		NativeImage img = cur();
		int iw = img.getWidth();
		int ih = img.getHeight();
		Surface.row(ctx, vx, vy, vw, vh, 0xFF101114);
		ctx.enableScissor(vx, vy, vx + vw, vy + vh);
		int ox = vx + Math.round(panX);
		int oy = vy + Math.round(panY);
		int dw = Math.round(iw * zoom);
		int dh = Math.round(ih * zoom);
		// Karomuster fuer Durchsichtiges.
		int cell = 8;
		int x0 = Math.max(ox, vx);
		int y0 = Math.max(oy, vy);
		int x1 = Math.min(ox + dw, vx + vw);
		int y1 = Math.min(oy + dh, vy + vh);
		for (int cy = y0 - ((y0 - oy) % cell); cy < y1; cy += cell) {
			for (int cx = x0 - ((x0 - ox) % cell); cx < x1; cx += cell) {
				boolean light = (((cx - ox) / cell) + ((cy - oy) / cell)) % 2 == 0;
				ctx.fill(Math.max(cx, x0), Math.max(cy, y0), Math.min(cx + cell, x1), Math.min(cy + cell, y1),
					light ? 0xFF3A3D44 : 0xFF2C2F35);
			}
		}
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, viewId, ox, oy, 0.0F, 0.0F, dw, dh, iw, ih, iw, ih);

		// Raster ab 6-fachem Zoom.
		if (grid && zoom >= 6) {
			int line = 0x30FFFFFF;
			for (int x = 0; x <= iw; x++) {
				int sx = ox + Math.round(x * zoom);
				if (sx >= vx && sx < vx + vw) {
					ctx.fill(sx, Math.max(oy, vy), sx + 1, Math.min(oy + dh, vy + vh), line);
				}
			}
			for (int y = 0; y <= ih; y++) {
				int sy = oy + Math.round(y * zoom);
				if (sy >= vy && sy < vy + vh) {
					ctx.fill(Math.max(ox, vx), sy, Math.min(ox + dw, vx + vw), sy + 1, line);
				}
			}
		}
		if (mirror) {
			int mxl = ox + Math.round(iw * zoom / 2.0F);
			ctx.fill(mxl, Math.max(oy, vy), mxl + 1, Math.min(oy + dh, vy + vh), 0xA0F0B040);
		}
		Draw.roundedOutline(ctx, ox - 1, oy - 1, dw + 2, dh + 2, 0, 0x60FFFFFF);

		// Pinsel unter der Maus.
		int[] p = pixelAt(mx, my);
		if (p != null && !panning && !spaceDown) {
			int n = (tool == Tool.PEN || tool == Tool.ERASER) ? brush : 1;
			int off = (n - 1) / 2;
			int hx = ox + Math.round((p[0] - off) * zoom);
			int hy = oy + Math.round((p[1] - off) * zoom);
			int hs = Math.max(1, Math.round(n * zoom));
			Draw.roundedOutline(ctx, hx, hy, hs, hs, 0, 0xE0FFFFFF);
		}
		ctx.disableScissor();
		Draw.roundedOutline(ctx, vx, vy, vw, vh, 3, Theme.DIVIDER);
	}

	private void renderFrames(DrawContext ctx, int x, int y, int w, int mx, int my) {
		String label = Text.translatable(item.particle() || strip
			? "resourcepackstudio.editor.frames" : "resourcepackstudio.editor.parts").getString();
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.small(label), x, y, BTN_H,
			Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT_MUTED);
		int bx = x + Ui.widthSmall(this.textRenderer, label) + 8;
		for (int i = 0; i < frames.size(); i++) {
			String n = frameNames.get(i);
			int bw = Math.max(BTN_H + 2, Ui.width(this.textRenderer, n) + 12);
			if (bx + bw > x + w) {
				break;
			}
			button(ctx, bx, y, bw, BTN_H, n, i == frame, false, mx, my);
			hits.add(new int[] {bx, y, bw, BTN_H, 300 + i});
			bx += bw + 3;
		}
	}

	private void renderStatus(DrawContext ctx, int x, int y, int mx, int my) {
		NativeImage img = cur();
		StringBuilder sb = new StringBuilder();
		int[] p = pixelAt(mx, my);
		if (p != null) {
			sb.append(p[0]).append(", ").append(p[1]).append("    ");
		}
		sb.append(img.getWidth()).append(" x ").append(img.getHeight()).append("    ");
		sb.append(Math.round(zoom * 100)).append(" %");
		if (!message.isEmpty()) {
			sb.append("    ").append(message);
		}
		Ui.drawTop(ctx, this.textRenderer, Ui.small(Ui.fitSmall(this.textRenderer, sb.toString(), vw)),
			x, y, Ui.TOP_SMALL, messageWarn ? Theme.WARN : Theme.TEXT_FAINT);
	}

	private void renderRight(DrawContext ctx, int mx, int my) {
		int x = rightX;
		int w = rightW;
		int y = vy;

		// Farbfeld (Saettigung waagrecht, Helligkeit senkrecht) und rechts der Farbton.
		int fieldW = w - 22;
		int fieldH = 84;
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, svId, x, y, 0.0F, 0.0F, fieldW, fieldH, 64, 64, 64, 64);
		Draw.roundedOutline(ctx, x - 1, y - 1, fieldW + 2, fieldH + 2, 1, Theme.DIVIDER);
		int sxp = x + Math.round(sat * (fieldW - 1));
		int syp = y + Math.round((1.0F - val) * (fieldH - 1));
		Draw.roundedOutline(ctx, sxp - 3, syp - 3, 7, 7, 3, 0xFFFFFFFF);
		Draw.roundedOutline(ctx, sxp - 2, syp - 2, 5, 5, 2, 0xFF000000);
		hits.add(new int[] {x, y, fieldW, fieldH, 400});

		int hx = x + fieldW + 6;
		for (int i = 0; i < fieldH; i++) {
			ctx.fill(hx, y + i, hx + 16, y + i + 1, hsvToRgb(i / (float) fieldH, 1.0F, 1.0F));
		}
		int hy = y + Math.round(hue * (fieldH - 1));
		Draw.roundedOutline(ctx, hx - 2, hy - 2, 20, 5, 1, 0xFFFFFFFF);
		hits.add(new int[] {hx, y, 16, fieldH, 401});
		y += fieldH + 6;

		// Deckkraft
		int aw = w;
		for (int cx = 0; cx < aw; cx += 6) {
			ctx.fill(x + cx, y, Math.min(x + cx + 6, x + aw), y + 5, (cx / 6) % 2 == 0 ? 0xFF3A3D44 : 0xFF2C2F35);
			ctx.fill(x + cx, y + 5, Math.min(x + cx + 6, x + aw), y + 10, (cx / 6) % 2 == 1 ? 0xFF3A3D44 : 0xFF2C2F35);
		}
		for (int i = 0; i < aw; i++) {
			int a = Math.round(i * 255.0F / (aw - 1));
			ctx.fill(x + i, y, x + i + 1, y + 10, (a << 24) | (primary & 0xFFFFFF));
		}
		int ax = x + Math.round((primary >>> 24) / 255.0F * (aw - 1));
		Draw.roundedOutline(ctx, ax - 2, y - 2, 5, 14, 1, 0xFFFFFFFF);
		hits.add(new int[] {x, y, aw, 10, 402});
		if (Draw.inside(mx, my, x, y, aw, 10)) {
			tip(Text.translatable("resourcepackstudio.editor.alpha").getString() + "  " + Math.round((primary >>> 24) / 2.55F) + " %", mx, my);
		}
		y += 16;

		// Haupt- und Zweitfarbe, daneben Hex.
		// Zweitfarbe hinten rechts unten, Hauptfarbe vorne - wie in Malprogrammen.
		swatch(ctx, x + 6, y + 6, 22, secondary);
		swatch(ctx, x, y, 22, primary);
		hits.add(new int[] {x, y, 28, 28, 403});
		if (Draw.inside(mx, my, x, y, 28, 28)) {
			tip(Text.translatable("resourcepackstudio.editor.swap").getString() + "  (X)", mx, my);
		}
		int fx = x + 36;
		int fw = w - 36;
		int fh = 18;
		boolean hov = Draw.inside(mx, my, fx, y + 5, fw, fh);
		Surface.row(ctx, fx, y + 5, fw, fh, hexFocus ? Theme.FIELD_FOCUS : hov ? Theme.FIELD_HOVER : Theme.FIELD);
		String shown = "#" + hexText + (hexFocus && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(shown), fx + 6, y + 5, fh,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, hexFocus ? Theme.TEXT : Theme.TEXT_MUTED);
		hits.add(new int[] {fx, y + 5, fw, fh, 404});
		y += 34;

		// Farben im Bild
		y = palette(ctx, x, y, w, "resourcepackstudio.editor.in_image", imageColours, 500, mx, my);
		int[] rec = recent.stream().mapToInt(Integer::intValue).toArray();
		if (rec.length > 0) {
			y = palette(ctx, x, y, w, "resourcepackstudio.editor.recent", rec, 600, mx, my);
		}

		// Unten: Aufloesung, Aktionen, Speichern.
		int bottom = py + ph - 10;
		int by = bottom - BTN_H;
		int half = (w - GAP) / 2;
		button(ctx, x, by, half, BTN_H, Text.translatable("resourcepackstudio.create.cancel").getString(), false, false, mx, my);
		hits.add(new int[] {x, by, half, BTN_H, 30});
		button(ctx, x + half + GAP, by, half, BTN_H, Text.translatable("resourcepackstudio.editor.save").getString(), true, false, mx, my);
		hits.add(new int[] {x + half + GAP, by, half, BTN_H, 31});
		by -= BTN_H + GAP;
		button(ctx, x, by, half, BTN_H, Text.translatable("resourcepackstudio.editor.clear").getString(), false, false, mx, my);
		hits.add(new int[] {x, by, half, BTN_H, 32});
		if (Draw.inside(mx, my, x, by, half, BTN_H)) {
			tip(Text.translatable("resourcepackstudio.editor.clear.tip").getString(), mx, my);
		}
		button(ctx, x + half + GAP, by, half, BTN_H, Text.translatable("resourcepackstudio.editor.hide").getString(), false, false, mx, my);
		hits.add(new int[] {x + half + GAP, by, half, BTN_H, 33});
		if (Draw.inside(mx, my, x + half + GAP, by, half, BTN_H)) {
			tip(Text.translatable(item.particle()
				? "resourcepackstudio.editor.hide.particle" : "resourcepackstudio.editor.hide.tip").getString(), mx, my);
		}

		NativeImage img = cur();
		boolean square = !strip && frames.stream().allMatch(f -> f.getWidth() == f.getHeight());
		if (square) {
			by -= BTN_H + GAP + 2;
			int rw = (w - GAP * 5) / 6;
			for (int i = SIZES.length - 1; i >= 0; i--) {
				int rx = x + i * (rw + GAP);
				button(ctx, rx, by, rw, BTN_H, Integer.toString(SIZES[i]), img.getWidth() == SIZES[i], false, mx, my);
				hits.add(new int[] {rx, by, rw, BTN_H, 700 + i});
			}
			Ui.drawTop(ctx, this.textRenderer, Ui.small(Text.translatable("resourcepackstudio.editor.resolution").getString()),
				x, by - Ui.SMALL_H - 5, Ui.TOP_SMALL, Theme.TEXT_MUTED);
		}
	}

	private int palette(DrawContext ctx, int x, int y, int w, String key, int[] colours, int action, int mx, int my) {
		Ui.drawTop(ctx, this.textRenderer, Ui.small(Text.translatable(key).getString()), x, y, Ui.TOP_SMALL, Theme.TEXT_MUTED);
		y += Ui.SMALL_H + 5;
		int cell = 13;
		int per = Math.max(1, (w + 2) / (cell + 2));
		for (int i = 0; i < colours.length; i++) {
			int cx = x + (i % per) * (cell + 2);
			int cy = y + (i / per) * (cell + 2);
			swatch(ctx, cx, cy, cell, colours[i]);
			if (colours[i] == primary) {
				Draw.roundedOutline(ctx, cx - 1, cy - 1, cell + 2, cell + 2, 1, 0xFFFFFFFF);
			}
			hits.add(new int[] {cx, cy, cell, cell, action + i});
			if (Draw.inside(mx, my, cx, cy, cell, cell)) {
				tip("#" + hex(colours[i]), mx, my);
			}
		}
		int rows = colours.length == 0 ? 0 : (colours.length + per - 1) / per;
		return y + rows * (cell + 2) + 6;
	}

	/** Farbfeld mit Karomuster darunter, damit man Durchsichtiges sieht. */
	private void swatch(DrawContext ctx, int x, int y, int s, int argb) {
		if (s <= 0) {
			return;
		}
		int h = s / 2;
		ctx.fill(x, y, x + h, y + h, 0xFF3A3D44);
		ctx.fill(x + h, y, x + s, y + h, 0xFF2C2F35);
		ctx.fill(x, y + h, x + h, y + s, 0xFF2C2F35);
		ctx.fill(x + h, y + h, x + s, y + s, 0xFF3A3D44);
		ctx.fill(x, y, x + s, y + s, argb);
		Draw.roundedOutline(ctx, x - 1, y - 1, s + 2, s + 2, 1, Theme.DIVIDER);
	}

	private void glyph(DrawContext ctx, String[] rows, int x, int y, int scale, int argb) {
		for (int r = 0; r < rows.length; r++) {
			String row = rows[r];
			for (int c = 0; c < row.length(); c++) {
				if (row.charAt(c) == '#') {
					ctx.fill(x + c * scale, y + r * scale, x + (c + 1) * scale, y + (r + 1) * scale, argb);
				}
			}
		}
	}

	private void button(DrawContext ctx, int x, int y, int w, int h, String label, boolean on, boolean off, int mx, int my) {
		boolean hov = !off && Draw.inside(mx, my, x, y, w, h);
		Surface.row(ctx, x, y, w, h, on ? Theme.TOGGLE_ON : hov ? Theme.FIELD_HOVER : Theme.FIELD);
		String t = Ui.fit(this.textRenderer, label, w - 6);
		int tw = Ui.width(this.textRenderer, t);
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(t), x + (w - tw) / 2, y, h,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, on ? 0xFFFFFFFF : off ? Theme.TEXT_FAINT : Theme.TEXT);
	}

	private void smallButton(DrawContext ctx, int x, int y, int w, int h, String label, int mx, int my, int action) {
		button(ctx, x, y, w, h, label, false, false, mx, my);
		hits.add(new int[] {x, y, w, h, action});
	}

	private void tip(String text, int mx, int my) {
		this.tooltip = text;
		this.tooltipX = mx;
		this.tooltipY = my;
	}

	/** Bildpunkt unter der Maus, oder null ausserhalb des Bilds. */
	private int[] pixelAt(double mx, double my) {
		if (mx < vx || my < vy || mx >= vx + vw || my >= vy + vh) {
			return null;
		}
		int x = (int) Math.floor((mx - vx - Math.round(panX)) / zoom);
		int y = (int) Math.floor((my - vy - Math.round(panY)) / zoom);
		NativeImage img = cur();
		if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
			return null;
		}
		return new int[] {x, y};
	}

	/** Wie pixelAt, aber auch ausserhalb - fuer Formen, die ueber den Rand gezogen werden. */
	private int[] pixelAtLoose(double mx, double my) {
		NativeImage img = cur();
		int x = (int) Math.floor((mx - vx - Math.round(panX)) / zoom);
		int y = (int) Math.floor((my - vy - Math.round(panY)) / zoom);
		return new int[] {Math.max(0, Math.min(img.getWidth() - 1, x)), Math.max(0, Math.min(img.getHeight() - 1, y))};
	}

	// ------------------------------------------------------------------
	// Eingabe
	// ------------------------------------------------------------------

	private static boolean down(int key) {
		long win = MinecraftClient.getInstance().getWindow().getHandle();
		return GLFW.glfwGetKey(win, key) == GLFW.GLFW_PRESS;
	}

	private static boolean shift() {
		return down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean alt() {
		return down(GLFW.GLFW_KEY_LEFT_ALT) || down(GLFW.GLFW_KEY_RIGHT_ALT);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		int button = click.button();
		hexFocus = false;

		// Verschieben der Ansicht: mittlere Taste oder Leertaste.
		if (Draw.inside(mx, my, vx, vy, vw, vh)
				&& (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || (spaceDown && button == GLFW.GLFW_MOUSE_BUTTON_LEFT))) {
			panning = true;
			return true;
		}

		for (int i = hits.size() - 1; i >= 0; i--) {
			int[] h = hits.get(i);
			if (Draw.inside(mx, my, h[0], h[1], h[2], h[3])) {
				action(h[4], mx, my, button);
				return true;
			}
		}

		int[] p = pixelAt(mx, my);
		if (p == null || (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
			return super.mouseClicked(click, doubled);
		}
		boolean right = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
		NativeImage img = cur();
		if (tool == Tool.PICK || alt()) {
			int c = img.getColorArgb(p[0], p[1]);
			if (right) {
				secondary = c;
			} else {
				setPrimary(c);
			}
			return true;
		}
		paintColour = tool == Tool.ERASER ? 0 : right ? secondary : primary;
		pushUndo(List.of(frame));
		if (tool == Tool.FILL) {
			int from = img.getColorArgb(p[0], p[1]);
			if (shift()) {
				replaceAll(from, paintColour);
			} else {
				floodFill(p[0], p[1], from, paintColour);
				if (mirror) {
					floodFill(img.getWidth() - 1 - p[0], p[1], img.getColorArgb(img.getWidth() - 1 - p[0], p[1]), paintColour);
				}
			}
			changed();
			remember(paintColour);
			return true;
		}
		painting = true;
		startPx = p[0];
		startPy = p[1];
		lastPx = lastPy = -1;
		if (tool.shape() || tool == Tool.MOVE) {
			dragBase = pixels(img);
		}
		if (tool == Tool.PEN || tool == Tool.ERASER) {
			strokeTo(p[0], p[1]);
		} else if (tool.shape()) {
			drawShape(p[0], p[1]);
		}
		remember(paintColour);
		return true;
	}

	private void action(int a, double mx, double my, int button) {
		if (a >= 100 && a < 100 + Tool.values().length) {
			tool = Tool.values()[a - 100];
		} else if (a >= 300 && a < 300 + frames.size()) {
			frame = a - 300;
			viewDirty = true;
			coloursDirty = true;
		} else if (a == 400 || a == 401 || a == 402) {
			dragSv = a - 399;
			pickColour(mx, my);
		} else if (a == 403) {
			swapColours();
		} else if (a == 404) {
			hexFocus = true;
		} else if (a >= 500 && a < 600) {
			pickPalette(imageColours, a - 500, button);
		} else if (a >= 600 && a < 700) {
			pickPalette(recent.stream().mapToInt(Integer::intValue).toArray(), a - 600, button);
		} else if (a >= 700 && a < 700 + SIZES.length) {
			resize(SIZES[a - 700]);
		} else {
			switch (a) {
				case 10 -> mirror = !mirror;
				case 11 -> grid = !grid;
				case 12 -> redo();
				case 13 -> undo();
				case 20 -> brush = Math.min(16, brush + 1);
				case 21 -> brush = Math.max(1, brush - 1);
				case 22 -> filledShapes = !filledShapes;
				case 30 -> close();
				case 31 -> save();
				case 32 -> {
					pushUndo(List.of(frame));
					clear(cur());
					changed();
				}
				case 33 -> {
					// Unsichtbar machen: alle Bilder durchsichtig. Bei einem
					// Partikel heisst das: im Spiel ausgeschaltet.
					List<Integer> all = new ArrayList<>();
					for (int i = 0; i < frames.size(); i++) {
						all.add(i);
					}
					pushUndo(all);
					for (NativeImage f : frames) {
						clear(f);
					}
					changed();
					message = Text.translatable("resourcepackstudio.editor.hidden").getString();
					messageWarn = false;
				}
				default -> {
				}
			}
		}
	}

	private void pickPalette(int[] colours, int i, int button) {
		if (i < 0 || i >= colours.length) {
			return;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			secondary = colours[i];
		} else {
			setPrimary(colours[i]);
		}
		if (tool == Tool.ERASER || tool == Tool.PICK || tool == Tool.MOVE) {
			tool = Tool.PEN;
		}
	}

	private void swapColours() {
		int s = secondary;
		secondary = primary;
		setPrimary(s);
	}

	/** Farbfeld, Farbton oder Deckkraft unter der Maus uebernehmen. */
	private void pickColour(double mx, double my) {
		int x = rightX;
		int fieldW = rightW - 22;
		int fieldH = 84;
		int y = vy;
		if (dragSv == 1) {
			sat = clamp01((float) (mx - x) / (fieldW - 1));
			val = 1.0F - clamp01((float) (my - y) / (fieldH - 1));
			fromHsv();
		} else if (dragSv == 2) {
			hue = Math.min(0.999F, clamp01((float) (my - y) / (fieldH - 1)));
			fromHsv();
		} else if (dragSv == 3) {
			int a = Math.round(clamp01((float) (mx - x) / (rightW - 1)) * 255);
			primary = (a << 24) | (primary & 0xFFFFFF);
			if (!hexFocus) {
				hexText.setLength(0);
				hexText.append(hex(primary));
			}
		}
		if (tool == Tool.ERASER || tool == Tool.PICK || tool == Tool.MOVE) {
			tool = Tool.PEN;
		}
	}

	private static float clamp01(float f) {
		return Math.max(0.0F, Math.min(1.0F, f));
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (panning) {
			panX += (float) dx;
			panY += (float) dy;
			return true;
		}
		if (dragSv != 0) {
			pickColour(click.x(), click.y());
			return true;
		}
		if (painting) {
			int[] p = pixelAtLoose(click.x(), click.y());
			if (tool == Tool.PEN || tool == Tool.ERASER) {
				int[] q = pixelAt(click.x(), click.y());
				if (q != null) {
					strokeTo(q[0], q[1]);
				}
			} else if (tool.shape()) {
				drawShape(p[0], p[1]);
			} else if (tool == Tool.MOVE) {
				moveBy(p[0] - startPx, p[1] - startPy);
			}
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(Click click) {
		painting = false;
		panning = false;
		dragSv = 0;
		dragBase = null;
		lastPx = lastPy = -1;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!Draw.inside(mouseX, mouseY, vx, vy, vw, vh) || vertical == 0) {
			return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
		}
		int i = 0;
		while (i < ZOOMS.length - 1 && ZOOMS[i] < zoom) {
			i++;
		}
		int next = vertical > 0 ? Math.min(ZOOMS.length - 1, i + 1) : Math.max(0, i - 1);
		if (vertical < 0 && ZOOMS[i] > zoom) {
			next = Math.max(0, i - 1);
		}
		float nz = ZOOMS[next];
		// Der Punkt unter der Maus bleibt stehen.
		float ix = (float) (mouseX - vx - panX) / zoom;
		float iy = (float) (mouseY - vy - panY) / zoom;
		zoom = nz;
		panX = (float) (mouseX - vx) - ix * zoom;
		panY = (float) (mouseY - vy) - iy * zoom;
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();
		boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean sh = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		if (hexFocus) {
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (hexText.length() > 0) {
					hexText.setLength(hexText.length() - 1);
				}
				return true;
			}
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				applyHex();
				hexFocus = false;
				return true;
			}
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				hexFocus = false;
				hexText.setLength(0);
				hexText.append(hex(primary));
				return true;
			}
			if (input.isPaste()) {
				String clip = MinecraftClient.getInstance().keyboard.getClipboard();
				if (clip != null) {
					hexText.setLength(0);
					for (char c : clip.replace("#", "").trim().toCharArray()) {
						if (Character.digit(c, 16) >= 0 && hexText.length() < 8) {
							hexText.append(Character.toUpperCase(c));
						}
					}
					applyHex();
				}
				return true;
			}
		}
		if (ctrl && key == GLFW.GLFW_KEY_Z) {
			if (sh) {
				redo();
			} else {
				undo();
			}
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_Y) {
			redo();
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_S) {
			save();
			return true;
		}
		if (!ctrl && !hexFocus) {
			if (key == GLFW.GLFW_KEY_SPACE) {
				spaceDown = true;
				return true;
			}
			for (Tool t : Tool.values()) {
				if (t.key == key) {
					tool = t;
					return true;
				}
			}
			if (key == GLFW.GLFW_KEY_X) {
				swapColours();
				return true;
			}
			if (key == GLFW.GLFW_KEY_LEFT_BRACKET) {
				brush = Math.max(1, brush - 1);
				return true;
			}
			if (key == GLFW.GLFW_KEY_RIGHT_BRACKET) {
				brush = Math.min(16, brush + 1);
				return true;
			}
			if (key == GLFW.GLFW_KEY_0 || key == GLFW.GLFW_KEY_KP_0) {
				fit();
				return true;
			}
			if (frames.size() > 1 && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
				frame = (frame + (key == GLFW.GLFW_KEY_RIGHT ? 1 : frames.size() - 1)) % frames.size();
				viewDirty = true;
				coloursDirty = true;
				return true;
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_SPACE) {
			spaceDown = false;
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (!hexFocus) {
			// "#" schaltet das Raster um.
			String s = input.asString();
			if ("#".equals(s)) {
				grid = !grid;
				return true;
			}
			return super.charTyped(input);
		}
		String s = input.asString();
		if (s != null) {
			for (char c : s.toCharArray()) {
				if (Character.digit(c, 16) >= 0 && hexText.length() < 8) {
					hexText.append(Character.toUpperCase(c));
				}
			}
			if (hexText.length() == 6 || hexText.length() == 8) {
				applyHex();
			}
		}
		return true;
	}

	private void applyHex() {
		String h = hexText.toString();
		try {
			if (h.length() == 6) {
				int rgb = Integer.parseInt(h, 16);
				boolean keep = hexFocus;
				hexFocus = true;
				setPrimary((primary & 0xFF000000) | rgb);
				hexFocus = keep;
			} else if (h.length() == 8) {
				long v = Long.parseLong(h, 16);
				int rgb = (int) (v >>> 8) & 0xFFFFFF;
				int a = (int) (v & 0xFF);
				boolean keep = hexFocus;
				hexFocus = true;
				setPrimary((a << 24) | rgb);
				hexFocus = keep;
			}
		} catch (NumberFormatException ignored) {
		}
	}

	// ------------------------------------------------------------------
	// Werkzeuge
	// ------------------------------------------------------------------

	private void changed() {
		viewDirty = true;
		coloursDirty = true;
		redo.clear();
		message = "";
	}

	private void plot(int x, int y, int c) {
		NativeImage img = cur();
		int n = (tool == Tool.PEN || tool == Tool.ERASER) ? brush : 1;
		int off = (n - 1) / 2;
		for (int dy = 0; dy < n; dy++) {
			for (int dx = 0; dx < n; dx++) {
				set(img, x - off + dx, y - off + dy, c);
				if (mirror) {
					set(img, img.getWidth() - 1 - (x - off + dx), y - off + dy, c);
				}
			}
		}
	}

	private static void set(NativeImage img, int x, int y, int c) {
		if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
			img.setColorArgb(x, y, c);
		}
	}

	/** Linie vom letzten Punkt - so entstehen beim schnellen Ziehen keine Luecken. */
	private void strokeTo(int x, int y) {
		if (lastPx < 0) {
			plot(x, y, paintColour);
		} else {
			line(lastPx, lastPy, x, y, paintColour);
		}
		lastPx = x;
		lastPy = y;
		changed();
	}

	private void line(int x0, int y0, int x1, int y1, int c) {
		int dx = Math.abs(x1 - x0);
		int dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		while (true) {
			plot(x0, y0, c);
			if (x0 == x1 && y0 == y1) {
				break;
			}
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x0 += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	/** Form vom Startpunkt bis (x, y): zuerst das Bild vom Anfang wiederherstellen. */
	private void drawShape(int x, int y) {
		NativeImage img = cur();
		restore(img, dragBase);
		int x0 = startPx;
		int y0 = startPy;
		if (shift()) {
			int dx = x - x0;
			int dy = y - y0;
			if (tool == Tool.LINE) {
				// Auf 0, 45 oder 90 Grad einrasten.
				if (Math.abs(dx) > 2 * Math.abs(dy)) {
					dy = 0;
				} else if (Math.abs(dy) > 2 * Math.abs(dx)) {
					dx = 0;
				} else {
					int m = Math.max(Math.abs(dx), Math.abs(dy));
					dx = Integer.signum(dx) * m;
					dy = Integer.signum(dy) * m;
				}
			} else {
				int m = Math.max(Math.abs(dx), Math.abs(dy));
				dx = (dx < 0 ? -1 : 1) * m;
				dy = (dy < 0 ? -1 : 1) * m;
			}
			x = x0 + dx;
			y = y0 + dy;
		}
		switch (tool) {
			case LINE -> line(x0, y0, x, y, paintColour);
			case RECT -> rect(Math.min(x0, x), Math.min(y0, y), Math.max(x0, x), Math.max(y0, y), paintColour);
			case ELLIPSE -> ellipse(Math.min(x0, x), Math.min(y0, y), Math.max(x0, x), Math.max(y0, y), paintColour);
			default -> {
			}
		}
		changed();
	}

	private void rect(int x0, int y0, int x1, int y1, int c) {
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				if (filledShapes || y == y0 || y == y1 || x == x0 || x == x1) {
					plot(x, y, c);
				}
			}
		}
	}

	/** Ellipse im Rechteck: Pixel, deren Mitte innen liegt; Rand = innen mit Nachbar aussen. */
	private void ellipse(int x0, int y0, int x1, int y1, int c) {
		double cx = (x0 + x1) / 2.0 + 0.5;
		double cy = (y0 + y1) / 2.0 + 0.5;
		double rx = (x1 - x0 + 1) / 2.0;
		double ry = (y1 - y0 + 1) / 2.0;
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				if (!inside(x, y, cx, cy, rx, ry)) {
					continue;
				}
				boolean edge = !inside(x - 1, y, cx, cy, rx, ry) || !inside(x + 1, y, cx, cy, rx, ry)
					|| !inside(x, y - 1, cx, cy, rx, ry) || !inside(x, y + 1, cx, cy, rx, ry);
				if (filledShapes || edge) {
					plot(x, y, c);
				}
			}
		}
	}

	private static boolean inside(int x, int y, double cx, double cy, double rx, double ry) {
		double dx = (x + 0.5 - cx) / rx;
		double dy = (y + 0.5 - cy) / ry;
		return dx * dx + dy * dy <= 1.0;
	}

	/** Flaeche gleicher Farbe fuellen, ohne Rekursion (512x512 waeren zu tief). */
	private void floodFill(int x0, int y0, int from, int to) {
		if (from == to) {
			return;
		}
		NativeImage img = cur();
		ArrayDeque<int[]> q = new ArrayDeque<>();
		q.add(new int[] {x0, y0});
		while (!q.isEmpty()) {
			int[] p = q.poll();
			int x = p[0];
			int y = p[1];
			if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight() || img.getColorArgb(x, y) != from) {
				continue;
			}
			img.setColorArgb(x, y, to);
			q.add(new int[] {x + 1, y});
			q.add(new int[] {x - 1, y});
			q.add(new int[] {x, y + 1});
			q.add(new int[] {x, y - 1});
		}
	}

	/** Shift + Fuellen: jede Stelle mit dieser Farbe, im ganzen Bild. */
	private void replaceAll(int from, int to) {
		NativeImage img = cur();
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if (img.getColorArgb(x, y) == from) {
					img.setColorArgb(x, y, to);
				}
			}
		}
	}

	/** Verschieben mit Umlauf: Was rechts hinausgeht, kommt links wieder herein. */
	private void moveBy(int dx, int dy) {
		NativeImage img = cur();
		int w = img.getWidth();
		int h = img.getHeight();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int sx = Math.floorMod(x - dx, w);
				int sy = Math.floorMod(y - dy, h);
				img.setColorArgb(x, y, dragBase[sy * w + sx]);
			}
		}
		changed();
	}

	private static void clear(NativeImage img) {
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				img.setColorArgb(x, y, 0);
			}
		}
	}

	private static int[] pixels(NativeImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out[y * w + x] = img.getColorArgb(x, y);
			}
		}
		return out;
	}

	private static void restore(NativeImage img, int[] px) {
		int w = img.getWidth();
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < w; x++) {
				img.setColorArgb(x, y, px[y * w + x]);
			}
		}
	}

	/** Aufloesung aller Bilder aendern - naechster Nachbar, scharfe Pixel. */
	private void resize(int size) {
		if (cur().getWidth() == size) {
			return;
		}
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			all.add(i);
		}
		pushUndo(all);
		for (int i = 0; i < frames.size(); i++) {
			NativeImage src = frames.get(i);
			NativeImage next = new NativeImage(size, size, true);
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					next.setColorArgb(x, y, src.getColorArgb(x * src.getWidth() / size, y * src.getHeight() / size));
				}
			}
			src.close();
			frames.set(i, next);
		}
		changed();
		fit();
	}

	// ------------------------------------------------------------------
	// Rueckgaengig / Wiederholen
	// ------------------------------------------------------------------

	private List<Snap> snap(List<Integer> which) {
		List<Snap> out = new ArrayList<>();
		for (int i : which) {
			NativeImage img = frames.get(i);
			out.add(new Snap(i, img.getWidth(), img.getHeight(), pixels(img)));
		}
		return out;
	}

	private void pushUndo(List<Integer> which) {
		undo.push(snap(which));
		while (undo.size() > UNDO_MAX) {
			undo.removeLast();
		}
		redo.clear();
	}

	private void undo() {
		List<Snap> s = undo.poll();
		if (s != null) {
			redo.push(snap(s.stream().map(Snap::frame).toList()));
			apply(s);
		}
	}

	private void redo() {
		List<Snap> s = redo.poll();
		if (s != null) {
			undo.push(snap(s.stream().map(Snap::frame).toList()));
			apply(s);
		}
	}

	private void apply(List<Snap> snaps) {
		boolean resized = false;
		for (Snap s : snaps) {
			NativeImage img = frames.get(s.frame());
			if (img.getWidth() != s.w() || img.getHeight() != s.h()) {
				img.close();
				img = new NativeImage(s.w(), s.h(), true);
				frames.set(s.frame(), img);
				resized = true;
			}
			restore(img, s.px());
		}
		viewDirty = true;
		coloursDirty = true;
		if (resized) {
			fit();
		}
	}
}
