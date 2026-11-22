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
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Einfacher Zeichen-Editor fuer eine Textur.
 *
 * WAS ER KANN: Stift, Radierer, Farbe aufnehmen, Fuellen, Rueckgaengig
 * (Strg+Z). Aufloesung 16 bis 512 - beim Wechsel wird das Bild Pixel fuer
 * Pixel hoch- bzw. heruntergerechnet, nicht weichgezeichnet. Gestartet wird
 * mit einer Kopie dessen, was gerade gewaehlt ist (Pack oder Vanilla).
 *
 * WOHIN ES GESPEICHERT WIRD: in ein eigenes Pack "My Drawings" im Ordner
 * des Studios (resourcepackstudio/packs). Das Studio durchsucht diesen
 * Ordner ohnehin - die Zeichnung ist danach eine Variante wie die aus
 * jedem anderen Pack, laesst sich waehlen und ins eigene Pack bauen. Pro
 * Eintrag gibt es eine Zeichnung; erneut speichern ueberschreibt sie.
 *
 * Nur fuer Eintraege mit genau einem quadratischen Bild (Items, viele
 * Texturen). Bloecke mit mehreren Seiten und Faltboegen bleiben aussen vor.
 */
public final class EditorScreen extends Screen {

	/** Name des Packs, in dem die Zeichnungen landen. */
	public static final String DRAWINGS = "My Drawings";

	private static final int[] SIZES = {16, 32, 64, 128, 256, 512};

	/** Farben zum Anklicken - Minecraft-artige Toene, dazu Schwarz, Weiss, Grau. */
	private static final int[] PALETTE = {
		0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFFFFFF,
		0xFF5C3A1E, 0xFF8B5A2B, 0xFFC08A4E, 0xFFB02E26, 0xFFF9801D,
		0xFFFED83D, 0xFF80C71F, 0xFF5E7C16, 0xFF169C9C, 0xFF3AB3DA,
		0xFF3C44AA, 0xFF8932B8, 0xFFC74EBD, 0xFFF38BAA, 0xFF4AEDD9};

	private enum Tool { PEN, ERASER, PICK, FILL }

	private final Screen parent;
	private final StudioItem item;
	private final String title;
	private NativeImage canvas;
	private NativeImageBackedTexture texture;
	private final Identifier textureId = Identifier.of(ResourcePackStudio.MOD_ID, "editor/canvas");
	private int res = 16;
	private Tool tool = Tool.PEN;
	private int colour = 0xFF000000;
	private final Deque<int[]> undo = new ArrayDeque<>();
	private static final int UNDO_MAX = 40;
	private boolean painting;
	private int lastPx = -1, lastPy = -1;
	private String message = "";
	private boolean messageWarn;

	// Lage aus dem letzten Bild, fuer Klicks.
	private int canvasX, canvasY, canvasSize;
	private int toolsX, toolsY;
	private static final int BTN_H = 20;
	private static final int GAP = 4;

	public EditorScreen(Screen parent, StudioItem item, PackScanner.Variant start, String name) {
		super(Text.translatable("resourcepackstudio.editor.title"));
		this.parent = parent;
		this.item = item;
		this.title = Text.translatable("resourcepackstudio.editor.title").getString() + " - " + name;
		NativeImage src = loadStart(item, start);
		int size = 16;
		if (src != null) {
			int w = src.getWidth();
			int h = src.getHeight();
			this.animated = h > w && h % w == 0;
			this.unsupported = w != h && !animated;
			size = Math.min(src.getWidth(), src.getHeight());
			size = nearestSize(size);
		}
		this.res = size;
		this.canvas = new NativeImage(res, res, true);
		if (src != null) {
			resample(src, Math.min(src.getWidth(), src.getHeight()), canvas, res);
			src.close();
		}
		this.texture = new NativeImageBackedTexture(() -> "Resource Pack Studio editor", canvas);
		MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);
	}

	/**
	 * Kann dieser Eintrag im Editor bearbeitet werden? Items, und unter
	 * Texturen nur Item- und Blockbilder. HUD-Bilder (Hotbar 182x22) und
	 * Oberflaechen sind nicht quadratisch - die wuerde der Editor zerstoeren.
	 */
	public static boolean editable(StudioItem item) {
		if (item.parts().size() != 1 || item.cube() || item.sound() || item.sky()
				|| item.particle() || item.hud() || item.modern().startsWith("entity/")) {
			return false;
		}
		return !item.texture() || item.modern().startsWith("item/") || item.modern().startsWith("block/");
	}

	/**
	 * Nicht quadratisch und keine Animation (Bilder untereinander): Dann
	 * laesst sich das Bild nicht sinnvoll bearbeiten - Speichern ist aus.
	 */
	private boolean unsupported;
	/** Animiert: nur das erste Bild wird bearbeitet, gespeichert ohne Animation. */
	private boolean animated;

	private static int nearestSize(int size) {
		int best = 16;
		for (int s : SIZES) {
			if (s <= size) {
				best = s;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// Laden und Speichern
	// ------------------------------------------------------------------

	private static NativeImage loadStart(StudioItem item, PackScanner.Variant v) {
		try {
			if (v != null && v.entries().get(0) != null) {
				String entry = v.entries().get(0);
				if (v.pack().zip()) {
					try (ZipFile zip = new ZipFile(v.pack().path().toFile())) {
						ZipEntry e = zip.getEntry(entry);
						if (e != null) {
							try (InputStream in = zip.getInputStream(e)) {
								return NativeImage.read(in);
							}
						}
					}
				} else {
					try (InputStream in = Files.newInputStream(v.pack().path().resolve(entry))) {
						return NativeImage.read(in);
					}
				}
			}
			InputSupplier<InputStream> in = MinecraftClient.getInstance().getDefaultResourcePack()
				.open(ResourceType.CLIENT_RESOURCES, Identifier.ofVanilla("textures/" + item.modern() + ".png"));
			if (in != null) {
				try (InputStream s = in.get()) {
					return NativeImage.read(s);
				}
			}
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not load the start image for {}", item.id(), e);
		}
		return null;
	}

	/**
	 * Pixel fuer Pixel umrechnen (naechster Nachbar) - beim Hochrechnen
	 * werden aus einem Pixel scharfe Bloecke, wie es zu Minecraft passt.
	 * Nur das erste Quadrat der Quelle: Animationen liegen untereinander.
	 */
	private static void resample(NativeImage src, int srcSize, NativeImage dst, int dstSize) {
		for (int y = 0; y < dstSize; y++) {
			int sy = y * srcSize / dstSize;
			for (int x = 0; x < dstSize; x++) {
				int sx = x * srcSize / dstSize;
				dst.setColorArgb(x, y, src.getColorArgb(sx, sy));
			}
		}
	}

	/** Pfad der Zeichnung im Pack "My Drawings". */
	public static Path drawingFile(StudioItem item) {
		return drawingsPack().resolve("assets/minecraft/textures/" + item.modern() + ".png");
	}

	public static Path drawingsPack() {
		return ResourcePackStudio.packsDir().resolve(DRAWINGS);
	}

	private void save() {
		if (unsupported) {
			return;
		}
		try {
			Path pack = drawingsPack();
			Files.createDirectories(pack);
			Path meta = pack.resolve("pack.mcmeta");
			if (!Files.isRegularFile(meta)) {
				Files.writeString(meta, "{\n  \"pack\": {\n    \"description\": \"Drawings made in Resource Pack Studio\",\n"
					+ "    \"min_format\": " + PackBuilder.PACK_FORMAT + ",\n    \"max_format\": " + PackBuilder.PACK_FORMAT
					+ "\n  }\n}\n", StandardCharsets.UTF_8);
			}
			Path file = drawingFile(item);
			Files.createDirectories(file.getParent());
			canvas.writeTo(file);
			// Nach der naechsten Suche gleich auswaehlen.
			StudioIndex.selectAfterScan(item.id(), pack);
			StudioIndex.rescan();
			MinecraftClient.getInstance().setScreen(parent);
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.error("[Resource Pack Studio] Could not save the drawing.", e);
			this.message = Text.translatable("resourcepackstudio.editor.save_failed").getString();
			this.messageWarn = true;
		}
	}

	@Override
	public void removed() {
		MinecraftClient.getInstance().getTextureManager().destroyTexture(textureId);
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	// ------------------------------------------------------------------
	// Zeichnen
	// ------------------------------------------------------------------

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int pad = 12;
		int panelW = Math.min(this.width - 24, 520);
		int panelH = Math.min(this.height - 24, 360);
		int px = (this.width - panelW) / 2;
		int py = (this.height - panelH) / 2;
		Surface.card(ctx, px, py, panelW, panelH, Theme.PANEL | 0xFF000000);

		Ui.drawTop(ctx, this.textRenderer, Ui.title(Ui.fit(this.textRenderer, title, panelW - pad * 2)),
			px + pad, py + pad, Ui.TOP_TITLE, Theme.TEXT);
		int top = py + pad + Ui.TITLE_H + Ui.TITLE_D + 10;

		// Links die Zeichenflaeche, quadratisch, so gross wie es passt.
		int side = 150;
		this.canvasSize = Math.max(64, Math.min(panelH - (top - py) - pad, panelW - side - pad * 3));
		this.canvasX = px + pad;
		this.canvasY = top;
		// Karomuster fuer Durchsichtiges, 8 Bildschirmpixel gross.
		for (int cyy = 0; cyy < canvasSize; cyy += 8) {
			for (int cxx = 0; cxx < canvasSize; cxx += 8) {
				ctx.fill(canvasX + cxx, canvasY + cyy, Math.min(canvasX + cxx + 8, canvasX + canvasSize),
					Math.min(canvasY + cyy + 8, canvasY + canvasSize),
					((cxx + cyy) / 8) % 2 == 0 ? 0xFFDADADA : 0xFFB4B4B4);
			}
		}
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, textureId, canvasX, canvasY, 0.0F, 0.0F,
			canvasSize, canvasSize, res, res, res, res);
		Draw.roundedOutline(ctx, canvasX - 1, canvasY - 1, canvasSize + 2, canvasSize + 2, 2, Theme.DIVIDER);
		// Pixel unter der Maus hervorheben.
		int[] hp = pixelAt(mouseX, mouseY);
		if (hp != null) {
			float k = canvasSize / (float) res;
			int hx = canvasX + (int) Math.floor(hp[0] * k);
			int hy = canvasY + (int) Math.floor(hp[1] * k);
			Draw.roundedOutline(ctx, hx, hy, Math.max(1, Math.round(k)), Math.max(1, Math.round(k)), 0, 0xC0FFFFFF);
		}

		// Rechts: Werkzeuge, Farben, Aufloesung, Speichern.
		this.toolsX = canvasX + canvasSize + pad;
		this.toolsY = top;
		int w = px + panelW - pad - toolsX;
		int y = toolsY;
		label(ctx, "resourcepackstudio.editor.tools", toolsX, y);
		y += Ui.SMALL_H + 6;
		Tool[] tools = Tool.values();
		int tw = (w - GAP) / 2;
		for (int i = 0; i < tools.length; i++) {
			int bx = toolsX + (i % 2) * (tw + GAP);
			int by = y + (i / 2) * (BTN_H + GAP);
			button(ctx, bx, by, tw, "resourcepackstudio.editor.tool." + tools[i].name().toLowerCase(java.util.Locale.ROOT),
				tool == tools[i], mouseX, mouseY);
		}
		y += 2 * (BTN_H + GAP) + 6;

		label(ctx, "resourcepackstudio.editor.colour", toolsX, y);
		y += Ui.SMALL_H + 6;
		int cell = Math.max(8, (w - 4 * 2) / 5);
		for (int i = 0; i < PALETTE.length; i++) {
			int bx = toolsX + (i % 5) * (cell + 2);
			int by = y + (i / 5) * (cell + 2);
			ctx.fill(bx, by, bx + cell, by + cell, PALETTE[i]);
			if (PALETTE[i] == colour) {
				Draw.roundedOutline(ctx, bx - 1, by - 1, cell + 2, cell + 2, 1, Theme.TOGGLE_ON);
			}
		}
		y += 4 * (cell + 2) + 4;
		// Aktuelle Farbe gross, mit Hex daneben.
		ctx.fill(toolsX, y, toolsX + 20, y + 12, colour);
		Draw.roundedOutline(ctx, toolsX - 1, y - 1, 22, 14, 1, Theme.DIVIDER);
		Ui.drawTop(ctx, this.textRenderer, Ui.small(String.format("#%06X", colour & 0xFFFFFF)),
			toolsX + 26, y + 2, Ui.TOP_SMALL, Theme.TEXT_MUTED);
		y += 12 + 10;

		label(ctx, "resourcepackstudio.editor.resolution", toolsX, y);
		y += Ui.SMALL_H + 6;
		int rw = (w - GAP * 2) / 3;
		for (int i = 0; i < SIZES.length; i++) {
			int bx = toolsX + (i % 3) * (rw + GAP);
			int by = y + (i / 3) * (BTN_H + GAP);
			buttonText(ctx, bx, by, rw, String.valueOf(SIZES[i]), res == SIZES[i], mouseX, mouseY);
		}
		y += 2 * (BTN_H + GAP) + 6;

		// Unten: Abbrechen und Speichern.
		int by = py + panelH - pad - BTN_H;
		int half = (w - GAP) / 2;
		button(ctx, toolsX, by, half, "resourcepackstudio.create.cancel", false, mouseX, mouseY);
		button(ctx, toolsX + half + GAP, by, half, "resourcepackstudio.editor.save", !unsupported, mouseX, mouseY);
		String hint = !message.isEmpty() ? message
			: unsupported ? Text.translatable("resourcepackstudio.editor.not_square").getString()
			: animated ? Text.translatable("resourcepackstudio.editor.animated").getString()
			: Text.translatable("resourcepackstudio.editor.hint").getString();
		boolean warn = messageWarn || unsupported || animated;
		java.util.List<String> lines = Ui.wrap(this.textRenderer, hint, w);
		int hy0 = by - 6 - lines.size() * (Ui.SMALL_H + 4);
		for (int i = 0; i < lines.size(); i++) {
			if (hy0 + i * (Ui.SMALL_H + 4) > y) {
				Ui.drawTop(ctx, this.textRenderer, Ui.small(lines.get(i)), toolsX, hy0 + i * (Ui.SMALL_H + 4),
					Ui.TOP_SMALL, warn ? Theme.WARN : Theme.TEXT_FAINT);
			}
		}
	}

	private void label(DrawContext ctx, String key, int x, int y) {
		Ui.drawTop(ctx, this.textRenderer, Ui.small(Text.translatable(key).getString()), x, y,
			Ui.TOP_SMALL, Theme.TEXT_MUTED);
	}

	private void button(DrawContext ctx, int x, int y, int w, String key, boolean on, int mx, int my) {
		buttonText(ctx, x, y, w, Text.translatable(key).getString(), on, mx, my);
	}

	private void buttonText(DrawContext ctx, int x, int y, int w, String label, boolean on, int mx, int my) {
		boolean hov = Draw.inside(mx, my, x, y, w, BTN_H);
		int fill = on ? Theme.TOGGLE_ON : (hov ? Theme.FIELD_HOVER : Theme.FIELD);
		Surface.row(ctx, x, y, w, BTN_H, fill);
		String t = Ui.fit(this.textRenderer, label, w - 8);
		int tw = Ui.width(this.textRenderer, t);
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(t), x + (w - tw) / 2, y, BTN_H,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, on ? 0xFFFFFFFF : Theme.TEXT);
	}

	/** Bildpunkt unter der Maus, oder null ausserhalb der Flaeche. */
	private int[] pixelAt(double mx, double my) {
		if (mx < canvasX || my < canvasY || mx >= canvasX + canvasSize || my >= canvasY + canvasSize) {
			return null;
		}
		int x = (int) ((mx - canvasX) * res / canvasSize);
		int y = (int) ((my - canvasY) * res / canvasSize);
		return new int[] {Math.min(res - 1, x), Math.min(res - 1, y)};
	}

	// ------------------------------------------------------------------
	// Eingabe
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		int[] p = pixelAt(mx, my);
		if (p != null) {
			// Rechtsklick nimmt immer die Farbe auf - wie in vielen Malprogrammen.
			if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT || tool == Tool.PICK) {
				colour = canvas.getColorArgb(p[0], p[1]) | 0xFF000000;
				if (tool == Tool.PICK) {
					tool = Tool.PEN;
				}
				return true;
			}
			pushUndo();
			if (tool == Tool.FILL) {
				fill(p[0], p[1], canvas.getColorArgb(p[0], p[1]), colour);
				texture.upload();
				return true;
			}
			painting = true;
			lastPx = lastPy = -1;
			paintTo(p[0], p[1]);
			return true;
		}
		return clickControls(mx, my) || super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (painting) {
			int[] p = pixelAt(click.x(), click.y());
			if (p != null) {
				paintTo(p[0], p[1]);
			}
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(Click click) {
		painting = false;
		lastPx = lastPy = -1;
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		if (ctrl && input.key() == GLFW.GLFW_KEY_Z) {
			popUndo();
			return true;
		}
		if (ctrl && input.key() == GLFW.GLFW_KEY_S) {
			save();
			return true;
		}
		return super.keyPressed(input);
	}

	private boolean clickControls(double mx, double my) {
		int pad = 12;
		int panelW = Math.min(this.width - 24, 520);
		int panelH = Math.min(this.height - 24, 360);
		int px = (this.width - panelW) / 2;
		int py = (this.height - panelH) / 2;
		int w = px + panelW - pad - toolsX;
		int y = toolsY + Ui.SMALL_H + 6;
		Tool[] tools = Tool.values();
		int tw = (w - GAP) / 2;
		for (int i = 0; i < tools.length; i++) {
			if (Draw.inside(mx, my, toolsX + (i % 2) * (tw + GAP), y + (i / 2) * (BTN_H + GAP), tw, BTN_H)) {
				tool = tools[i];
				return true;
			}
		}
		y += 2 * (BTN_H + GAP) + 6 + Ui.SMALL_H + 6;
		int cell = Math.max(8, (w - 4 * 2) / 5);
		for (int i = 0; i < PALETTE.length; i++) {
			if (Draw.inside(mx, my, toolsX + (i % 5) * (cell + 2), y + (i / 5) * (cell + 2), cell, cell)) {
				colour = PALETTE[i];
				if (tool == Tool.ERASER || tool == Tool.PICK) {
					tool = Tool.PEN;
				}
				return true;
			}
		}
		y += 4 * (cell + 2) + 4 + 12 + 10 + Ui.SMALL_H + 6;
		int rw = (w - GAP * 2) / 3;
		for (int i = 0; i < SIZES.length; i++) {
			if (Draw.inside(mx, my, toolsX + (i % 3) * (rw + GAP), y + (i / 3) * (BTN_H + GAP), rw, BTN_H)) {
				setResolution(SIZES[i]);
				return true;
			}
		}
		int by = py + panelH - pad - BTN_H;
		int half = (w - GAP) / 2;
		if (Draw.inside(mx, my, toolsX, by, half, BTN_H)) {
			close();
			return true;
		}
		if (Draw.inside(mx, my, toolsX + half + GAP, by, half, BTN_H)) {
			save();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Werkzeuge
	// ------------------------------------------------------------------

	/** Linie vom letzten Punkt - so entstehen beim schnellen Ziehen keine Luecken. */
	private void paintTo(int x, int y) {
		int c = tool == Tool.ERASER ? 0x00000000 : colour;
		if (lastPx < 0) {
			canvas.setColorArgb(x, y, c);
		} else {
			int dx = Math.abs(x - lastPx);
			int dy = -Math.abs(y - lastPy);
			int sx = lastPx < x ? 1 : -1;
			int sy = lastPy < y ? 1 : -1;
			int err = dx + dy;
			int cx = lastPx;
			int cy = lastPy;
			while (true) {
				canvas.setColorArgb(cx, cy, c);
				if (cx == x && cy == y) {
					break;
				}
				int e2 = 2 * err;
				if (e2 >= dy) {
					err += dy;
					cx += sx;
				}
				if (e2 <= dx) {
					err += dx;
					cy += sy;
				}
			}
		}
		lastPx = x;
		lastPy = y;
		texture.upload();
	}

	/** Flaeche gleicher Farbe fuellen, ohne Rekursion (512x512 waeren zu tief). */
	private void fill(int x0, int y0, int from, int to) {
		if (from == to) {
			return;
		}
		ArrayDeque<int[]> q = new ArrayDeque<>();
		q.add(new int[] {x0, y0});
		while (!q.isEmpty()) {
			int[] p = q.poll();
			int x = p[0];
			int y = p[1];
			if (x < 0 || y < 0 || x >= res || y >= res || canvas.getColorArgb(x, y) != from) {
				continue;
			}
			canvas.setColorArgb(x, y, to);
			q.add(new int[] {x + 1, y});
			q.add(new int[] {x - 1, y});
			q.add(new int[] {x, y + 1});
			q.add(new int[] {x, y - 1});
		}
	}

	private void setResolution(int size) {
		if (size == res) {
			return;
		}
		pushUndo();
		NativeImage next = new NativeImage(size, size, true);
		resample(canvas, res, next, size);
		replaceCanvas(next, size);
	}

	/**
	 * Neues Bild anderer Groesse: eine neue Textur anmelden. Die alte ist
	 * auf der Grafikkarte genau so gross wie das alte Bild - hineinladen
	 * laesst sich dort nur ein gleich grosses. destroyTexture schliesst
	 * die alte samt Bild.
	 */
	private void replaceCanvas(NativeImage next, int size) {
		this.res = size;
		this.canvas = next;
		var tm = MinecraftClient.getInstance().getTextureManager();
		tm.destroyTexture(textureId);
		this.texture = new NativeImageBackedTexture(() -> "Resource Pack Studio editor", next);
		tm.registerTexture(textureId, texture);
	}

	private void pushUndo() {
		int[] snap = new int[res * res + 1];
		snap[0] = res;
		for (int y = 0; y < res; y++) {
			for (int x = 0; x < res; x++) {
				snap[1 + y * res + x] = canvas.getColorArgb(x, y);
			}
		}
		undo.push(snap);
		while (undo.size() > UNDO_MAX) {
			undo.removeLast();
		}
	}

	private void popUndo() {
		int[] snap = undo.poll();
		if (snap == null) {
			return;
		}
		int size = snap[0];
		NativeImage img = size == res ? canvas : new NativeImage(size, size, true);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				img.setColorArgb(x, y, snap[1 + y * size + x]);
			}
		}
		if (img != canvas) {
			replaceCanvas(img, size);
		} else {
			texture.upload();
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
