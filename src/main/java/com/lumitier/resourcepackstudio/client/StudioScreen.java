package com.lumitier.resourcepackstudio.client;

import com.lumitier.lumilib.client.Draw;
import com.lumitier.lumilib.client.Metrics;
import com.lumitier.lumilib.client.ScaledScreen;
import com.lumitier.lumilib.client.SearchField;
import com.lumitier.lumilib.client.Surface;
import com.lumitier.lumilib.client.Theme;
import com.lumitier.lumilib.client.Ui;
import com.lumitier.lumilib.client.gpu.Backdrop;
import com.lumitier.lumilib.client.gpu.Sdf;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import com.lumitier.resourcepackstudio.scan.StudioItems;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Das Studio: links die Items, rechts alle Texturen, die die Packs dafuer
 * haben.
 *
 * AUFBAU WIE DER LUMILIB-HUB - gleiche Masse, gleiche Reihenfolge beim
 * Zeichnen (Bildschirmkopie, Unschaerfe, Abdunklung, Schatten, Glas,
 * Sidebar). Dadurch sieht das Studio aus wie ein Teil des Hubs.
 *
 * DIE AKTIVEN PACKS SPIELEN KEINE ROLLE: Die Bilder in der Sidebar kommen
 * aus Minecrafts Standard-Pack, die der Varianten direkt aus den Dateien
 * (siehe Textures).
 *
 * GLEICHE GROESSE AUF JEDER GUI-STUFE: ScaledScreen aus LumiLib, wie der
 * Hub selbst.
 *
 * KEINE NEUEN OBJEKTE PRO BILD: Namen, Zaehler und gekuerzte Pack-Namen
 * werden einmal berechnet - bei init(), nach jeder Suche und beim Klicken.
 * render() liest nur.
 */
public final class StudioScreen extends ScaledScreen {

	private static final int PREVIEW = 48;
	private static final int CARD_PAD = 8;
	private static final int CARD_H = CARD_PAD + PREVIEW + 6 + Ui.SMALL_H + Ui.SMALL_D + CARD_PAD;
	private static final int CARD_MIN_W = 76;
	private static final int CARD_GAP = Metrics.CARD_GAP;
	private static final int ITEM_ICON = 16;
	private static final int GROUP_H = Metrics.SECTION_H;

	/** Zuletzt offenes Item - beim naechsten Oeffnen steht man wieder dort. */
	private static String lastItem;

	private final Screen parent;
	/** Alle Items - PvP zuerst, dann der Rest. Wechselt nach der ersten Suche. */
	private List<StudioItem> items = StudioIndex.items();
	/** Anzeige-Reihenfolge: PvP wie vorgegeben, der Rest A-Z nach Namen. */
	private int[] order = new int[0];
	private final SearchField search = new SearchField();
	/** Kleingeschriebene Namen - fuer die Suche, einmal berechnet. */
	private String[] itemKeys = new String[0];
	private String noMatchLabel = "";
	private int searchY;

	// Lage - wie im Hub
	private int px, py, pw, ph;
	private int sx, sy, sw, sh;
	private int cx, cy, cw, ch;
	private int listTop, listBottom;
	private int sideTop, sideBottom;
	private int cols, colW;

	// Sidebar-Zeilen: Gruppenkopf (item == -1) oder Item
	private String[] rowLabel = new String[0];
	private int[] rowItem = new int[0];
	private int[] rowY = new int[0];
	private int[] rowH = new int[0];
	private int sideHeight;
	private int sideScroll;

	// Inhalt
	private int selected;
	private int scroll;
	private int contentHeight;

	// Zwischenspeicher, neu gebaut bei Aenderungen
	private int builtVersion = Integer.MIN_VALUE;
	private String[] itemNames = new String[0];
	private String[] itemCounts = new String[0];
	@SuppressWarnings("unchecked")
	private List<PackScanner.Variant>[] variants = new List[0];
	private String[] cardNames = new String[0];
	private String headerTitle = "";
	private String headerCount = "";
	private String sideTitle = "";
	private String sideSub = "";
	private String vanillaLabel = "";
	private String emptyLabel = "";
	private String scanningLabel = "";
	private String rescanLabel = "";
	private String failedLabel = "";

	// Knopf "Rescan" oben rechts - Lage fuer Klicks
	private int rescanX, rescanY, rescanW, rescanH;
	// Knopf "Alles abwaehlen" links daneben - nur, wenn etwas gewaehlt ist
	private int clearX, clearW;
	private String clearLabel = "";

	// ---- Pack erstellen ------------------------------------------------
	private static final int BTN_H = 20;

	/** Was gerade ueber dem Studio liegt. */
	private enum Dialog { NONE, NAME, WORKING, DONE, ERROR, CLEAR }

	private Dialog dialog = Dialog.NONE;
	private final NameField nameField = new NameField();
	private final PackIcon icon = new PackIcon();
	/** Zeile unter dem Icon: Hinweis oder Fehler. */
	private String iconMsg = "";
	private boolean iconMsgWarn;
	private int chooseX, chooseY, chooseW, removeX, removeW;
	private String chooseLabel = "";
	private String removeLabel = "";
	private String iconLabel = "";

	/** Dialog nicht durchsichtig - das Glas darunter verwirrte. */
	private static final int DIALOG_FILL = 0xFFF2EFEC;
	private static final int ICON_BOX = 32;
	private PackBuilder.Built built;
	private String createLabel = "";

	// Lage fuer Klicks
	private int createX, createY, createW;
	private int dlgX, dlgY, dlgW, dlgH;
	private int okX, okY, okW;
	private int noX, noY, noW;
	private String okLabel = "";
	private String noLabel = "";
	private String dlgTitle = "";
	private String dlgLine1 = "";
	private String dlgLine2 = "";
	private String dlgLine3 = "";

	public StudioScreen(Screen parent) {
		super(Text.translatable("resourcepackstudio.studio.title"));
		this.parent = parent;
		this.selected = Math.max(0, indexOf(lastItem));
	}

	// ------------------------------------------------------------------
	// Aufbau
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		// ZUERST: legt width/height auf die eigene Groesse.
		super.init();
		this.pw = Math.max(280, (int) (this.width * Metrics.PANEL_W));
		this.ph = Math.max(180, (int) (this.height * Metrics.PANEL_H));
		this.px = (this.width - this.pw) / 2;
		this.py = (this.height - this.ph) / 2;

		int pad = Metrics.PAD;
		this.sw = Math.max(110, (int) (this.pw * Metrics.SIDEBAR_W));
		this.sx = this.px + pad;
		this.sy = this.py + pad;
		this.sh = this.ph - pad * 2;

		this.cx = this.sx + this.sw + pad;
		this.cy = this.py + pad;
		this.cw = this.px + this.pw - pad - this.cx;
		this.ch = this.sh;

		this.listTop = cy + Metrics.TITLE_H + 6;
		this.listBottom = cy + ch;

		this.cols = Math.max(1, (cw + CARD_GAP) / (CARD_MIN_W + CARD_GAP));
		this.colW = (cw - CARD_GAP * (cols - 1)) / cols;

		// Kopf der Sidebar: Titel, eine kleine Zeile darunter, dann die Suche.
		int headH = Ui.advance(Ui.BODY_H, Ui.BODY_D, 2) + Ui.SMALL_H + Ui.SMALL_D;
		this.searchY = sy + Metrics.PAD + headH + 8;
		this.sideTop = searchY + Metrics.SEARCH_H + 9 + 1;
		// Unten in der Sidebar: "Create texture pack". Die Liste endet davor.
		this.createX = sx + Metrics.PAD - 4;
		this.createW = sw - Metrics.PAD * 2 + 8;
		this.createY = sy + sh - Metrics.PAD - BTN_H;
		this.sideBottom = createY - 9;

		buildNames();
		buildRows();
		this.noMatchLabel = Text.translatable("resourcepackstudio.studio.no_match").getString();
		this.vanillaLabel = Text.translatable("resourcepackstudio.studio.vanilla").getString();
		this.emptyLabel = Text.translatable("resourcepackstudio.studio.empty").getString();
		this.scanningLabel = Text.translatable("resourcepackstudio.studio.scanning").getString();
		this.rescanLabel = Text.translatable("resourcepackstudio.studio.rescan").getString();
		this.clearLabel = Text.translatable("resourcepackstudio.clear.button").getString();
		this.failedLabel = Text.translatable("resourcepackstudio.studio.failed").getString();
		this.sideTitle = Text.translatable("resourcepackstudio.studio.title").getString();

		this.builtVersion = Integer.MIN_VALUE;
		this.scroll = 0;

		if (StudioIndex.result() == null && !StudioIndex.scanning()) {
			StudioIndex.rescan();
		}
	}

	private int indexOf(String id) {
		if (id == null) {
			return 0;
		}
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).id().equals(id)) {
				return i;
			}
		}
		return 0;
	}

	/**
	 * Namen in der Spielsprache und die Reihenfolge - einmal pro init() und
	 * wenn die Item-Liste wechselt.
	 *
	 * NAME UEBER DAS ITEM, nicht "item.minecraft." + Kennung: Bei Tueren,
	 * Setzlingen usw. heisst der Schluessel "block.minecraft.".
	 */
	private void buildNames() {
		int n = items.size();
		this.itemNames = new String[n];
		this.itemKeys = new String[n];
		for (int i = 0; i < n; i++) {
			StudioItem it = items.get(i);
			if (it.sound()) {
				// Pfad ohne den Gruppenordner: "damage/hit1" -> "hit1".
				String rel = it.id().substring(StudioItems.SOUND_ID.length());
				int slash = rel.indexOf('/');
				itemNames[i] = slash > 0 ? rel.substring(slash + 1) : rel;
				itemKeys[i] = rel.replace('_', ' ').replace('/', ' ').toLowerCase(Locale.ROOT) + " " + rel;
				continue;
			}
			String key = Registries.ITEM.get(Identifier.ofVanilla(it.id())).getTranslationKey();
			itemNames[i] = Text.translatable(key).getString();
			// Name UND Kennung: "gold" findet so auch "Golden Sword".
			itemKeys[i] = (itemNames[i] + " " + it.id().replace('_', ' ')).toLowerCase(Locale.ROOT);
		}
		// PvP-Gruppen behalten ihre Reihenfolge, "other" wird alphabetisch.
		Integer[] o = new Integer[n];
		for (int i = 0; i < n; i++) {
			o[i] = i;
		}
		Arrays.sort(o, (a, b) -> {
			boolean oa = StudioItems.OTHER.equals(items.get(a).group());
			boolean ob = StudioItems.OTHER.equals(items.get(b).group());
			if (oa != ob) {
				return oa ? 1 : -1;
			}
			if (!oa) {
				return Integer.compare(a, b);
			}
			return String.CASE_INSENSITIVE_ORDER.compare(itemNames[a], itemNames[b]);
		});
		this.order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = o[i];
		}
	}

	// ---- Aufklappbare Kategorien -------------------------------------
	//
	// Wie bei Mod Menu: oben "Blocks", dann "Items" (spaeter "Sounds"), je
	// mit Pfeil zum Auf- und Zuklappen. Waehrend gesucht wird, sind alle
	// offen - sonst saehe man Treffer in zugeklappten Kategorien nicht.

	// Reihenfolge in der Sidebar: Items, Blocks, Sounds.
	private static final int CAT_ITEMS = 0;
	private static final int CAT_BLOCKS = 1;
	private static final int CAT_SOUNDS = 2;
	private static final int CAT_COUNT = 3;

	/** rowItem-Wert fuer den Kopf einer Kategorie: -10 - Kategorie. */
	private static final int CAT_ROW = -10;

	/**
	 * Offene Kategorien. Bei jedem Oeffnen des Studios ist alles zu - auch
	 * nach einem Neustart. Ein Fensterwechsel (init) aendert nichts daran.
	 */
	private final java.util.Set<Integer> expanded = new java.util.HashSet<>();

	private String[] rowCount = new String[0];

	private static int category(StudioItem it) {
		return it.sound() ? CAT_SOUNDS : it.cube() ? CAT_BLOCKS : CAT_ITEMS;
	}

	private static final String[] CAT_KEYS = {
		"resourcepackstudio.studio.cat.items",
		"resourcepackstudio.studio.cat.blocks",
		"resourcepackstudio.studio.cat.sounds"};

	private void buildRows() {
		buildRows(true);
	}

	/** "damage" -> "Damage", "entity" -> "Entity". */
	private static String folderLabel(String folder) {
		return folder.isEmpty() ? "" : Character.toUpperCase(folder.charAt(0)) + folder.substring(1);
	}

	/**
	 * Kategorien, Gruppenkoepfe und Eintraege untereinander, gefiltert nach
	 * der Suche. Nur neu gebaut, wenn sich etwas aendert - nicht pro Bild.
	 */
	private void buildRows(boolean resetScroll) {
		String q = search.text().trim().toLowerCase(Locale.ROOT);
		List<String> labels = new ArrayList<>();
		List<String> counts = new ArrayList<>();
		List<Integer> idx = new ArrayList<>();
		for (int cat = 0; cat < CAT_COUNT; cat++) {
			List<Integer> hits = new ArrayList<>();
			for (int k = 0; k < order.length; k++) {
				int i = order[k];
				if (category(items.get(i)) == cat && (q.isEmpty() || itemKeys[i].contains(q))) {
					hits.add(i);
				}
			}
			if (hits.isEmpty() && !q.isEmpty()) {
				continue;
			}
			labels.add(Text.translatable(CAT_KEYS[cat]).getString());
			counts.add(Integer.toString(hits.size()));
			idx.add(CAT_ROW - cat);
			if (q.isEmpty() && !expanded.contains(cat)) {
				continue;
			}
			// Gruppenkoepfe bei Items (Schwerter, Aexte ... Weitere) und
			// Sounds (Ordner: damage, entity, random ...).
			String group = null;
			for (int i : hits) {
				StudioItem it = items.get(i);
				if (cat != CAT_BLOCKS && !it.group().equals(group)) {
					group = it.group();
					labels.add(cat == CAT_SOUNDS
						? folderLabel(group.substring(StudioItems.SOUND_GROUP.length()))
						: Text.translatable("resourcepackstudio.studio.group." + group).getString());
					counts.add(null);
					idx.add(-1);
				}
				labels.add(null);
				counts.add(null);
				idx.add(i);
			}
		}
		int n = labels.size();
		this.rowLabel = labels.toArray(new String[0]);
		this.rowCount = counts.toArray(new String[0]);
		this.rowItem = new int[n];
		this.rowY = new int[n];
		this.rowH = new int[n];
		int y = 0;
		for (int i = 0; i < n; i++) {
			rowItem[i] = idx.get(i);
			// Luft vor jeder Kategorie ausser der ersten, etwas weniger vor Gruppen.
			if (i > 0 && rowItem[i] <= CAT_ROW) {
				y += 6;
			} else if (i > 0 && rowItem[i] == -1 && rowItem[i - 1] > CAT_ROW) {
				y += 4;
			}
			rowY[i] = y;
			rowH[i] = rowItem[i] == -1 ? GROUP_H : Metrics.ROW_H;
			y += rowH[i];
		}
		this.sideHeight = y;
		if (resetScroll) {
			this.sideScroll = 0;
		}
	}

	/** Nach einer neuen Suche: Varianten und Zaehler neu holen. */
	@SuppressWarnings("unchecked")
	private void rebuild() {
		this.builtVersion = StudioIndex.version();
		// Nach der ersten Suche kommen die uebrigen Items dazu.
		if (StudioIndex.items() != items) {
			String keep = items.isEmpty() ? null : items.get(selected).id();
			this.items = StudioIndex.items();
			this.selected = indexOf(keep);
			buildNames();
			buildRows();
		}
		this.variants = new List[items.size()];
		this.itemCounts = new String[items.size()];
		for (int i = 0; i < items.size(); i++) {
			variants[i] = StudioIndex.variants(items.get(i));
			itemCounts[i] = variants[i].isEmpty() ? "" : Integer.toString(variants[i].size());
		}
		refreshLabels();
	}

	/** Texte, die von Auswahl und Ergebnis abhaengen. */
	private void refreshLabels() {
		PackScanner.Result r = StudioIndex.result();
		int packs = r == null ? 0 : r.packs().size();
		this.sideSub = Text.translatable("resourcepackstudio.studio.summary",
			packs, StudioIndex.selectionCount()).getString();
		this.createLabel = Text.translatable("resourcepackstudio.create.button").getString();

		StudioItem cur = items.get(selected);
		this.headerTitle = cur.sound() ? cur.id().substring(StudioItems.SOUND_ID.length()) : itemNames[selected];
		int n = variants.length > selected ? variants[selected].size() : 0;
		String key = cur.sound() ? "resourcepackstudio.studio.count.sound" : "resourcepackstudio.studio.count";
		this.headerCount = Text.translatable(key + (n == 1 ? ".one" : ".many"), n).getString();

		// Karte 0 ist Vanilla, danach die Packs.
		int tw = colW - CARD_PAD * 2;
		List<PackScanner.Variant> vs = variants.length > selected ? variants[selected] : List.of();
		this.cardNames = new String[vs.size() + 1];
		cardNames[0] = Ui.fitSmall(this.textRenderer, vanillaLabel, tw);
		for (int i = 0; i < vs.size(); i++) {
			cardNames[i + 1] = Ui.fitSmall(this.textRenderer, vs.get(i).pack().name(), tw);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}

	/** Eine PNG aufs Fenster gezogen: im Namens-Dialog wird sie das Pack-Icon. */
	@Override
	public void onFilesDropped(List<java.nio.file.Path> paths) {
		if (dialog == Dialog.NAME && !paths.isEmpty()) {
			setIconMsg(icon.set(paths.get(0)));
		}
	}

	/** Vorschauen belegen Grafikspeicher - beim Verlassen freigeben. */
	@Override
	public void removed() {
		Textures.clear();
		SoundPreview.stop();
		icon.clear();
		lastItem = items.get(selected).id();
	}

	// ------------------------------------------------------------------
	// Zeichnen
	// ------------------------------------------------------------------

	@Override
	protected void renderScaled(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// Reihenfolge wie im Hub - Bildschirmkopie ZUERST, sonst bricht das
		// Glas sich selbst.
		Backdrop.capture();

		if (builtVersion != StudioIndex.version()) {
			rebuild();
		}

		maybeBlur(ctx);
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		Surface.shadowPanel(ctx, px, py, pw, ph,
			Theme.SHADOW_SPREAD, Theme.SHADOW_OFFSET, Theme.SHADOW);
		if (!Surface.refractPanel(ctx, px, py, pw, ph, Theme.PANEL)
			&& !Surface.glassPanel(ctx, px, py, pw, ph, Theme.PANEL)) {
			Surface.panel(ctx, px, py, pw, ph, Theme.PANEL);
			Surface.innerPanel(ctx, px, py, pw, ph, Theme.INNER);
			Surface.bevelPanel(ctx, px, py, pw, ph, Theme.BEVEL_LIGHT, Theme.BEVEL_DARK);
			Surface.rimPanel(ctx, px, py, pw, ph, Theme.RIM);
		}
		if (!Surface.glassCard(ctx, sx, sy, sw, sh, Theme.SIDEBAR)) {
			Surface.card(ctx, sx, sy, sw, sh, Theme.SIDEBAR);
		}

		// Liegt ein Dialog darueber, reagiert darunter nichts auf die Maus.
		int mx = dialog == Dialog.NONE ? mouseX : -1;
		int my = dialog == Dialog.NONE ? mouseY : -1;
		renderSidebar(ctx, mx, my);
		renderCreateButton(ctx, mx, my);
		renderContent(ctx, mx, my);
		if (dialog != Dialog.NONE) {
			renderDialog(ctx, mouseX, mouseY);
		}
		// Kein super.render - das macht ScaledScreen.
	}

	// ------------------------------------------------------------------
	// Pack erstellen
	// ------------------------------------------------------------------

	private void renderCreateButton(DrawContext ctx, int mouseX, int mouseY) {
		boolean any = StudioIndex.selectionCount() > 0;
		boolean hover = any && Draw.inside(mouseX, mouseY, createX, createY, createW, BTN_H);
		Draw.hLine(ctx, sx + Metrics.PAD, createY - 5, sw - Metrics.PAD * 2, Theme.DIVIDER);
		int fill = !any ? Theme.FIELD : (hover ? 0xFF2B97F0 : Theme.TOGGLE_ON);
		Surface.row(ctx, createX, createY, createW, BTN_H, fill);
		Ui.drawCenteredIn(ctx, this.textRenderer,
			Ui.bold(Ui.fitBold(this.textRenderer, createLabel, createW - 12)),
			createX + (createW - Math.min(Ui.widthBold(this.textRenderer, createLabel), createW - 12)) / 2,
			createY, BTN_H, Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, any ? 0xFFFFFFFF : Theme.TEXT_FAINT);
	}

	/** Dialog oeffnen: Name eingeben. */
	private void openNameDialog() {
		this.nameField.reset(PackBuilder.defaultName());
		this.dialog = Dialog.NAME;
		this.dlgTitle = Text.translatable("resourcepackstudio.create.title").getString();
		this.dlgLine1 = Text.translatable("resourcepackstudio.create.name").getString();
		int n = StudioIndex.selectionCount();
		this.dlgLine2 = Text.translatable(n == 1 ? "resourcepackstudio.create.count.one"
			: "resourcepackstudio.create.count.many", n).getString();
		this.dlgLine3 = "";
		this.okLabel = Text.translatable("resourcepackstudio.create.create").getString();
		this.noLabel = Text.translatable("resourcepackstudio.create.cancel").getString();
		this.iconLabel = Text.translatable("resourcepackstudio.icon.label").getString();
		this.chooseLabel = Text.translatable("resourcepackstudio.icon.choose").getString();
		this.removeLabel = Text.translatable("resourcepackstudio.icon.remove").getString();
		setIconMsg(null);
	}

	/** null: normaler Hinweis. Sonst Uebersetzungsschluessel eines Fehlers. */
	private void setIconMsg(String errorKey) {
		this.iconMsgWarn = errorKey != null;
		this.iconMsg = Text.translatable(errorKey != null ? errorKey : "resourcepackstudio.icon.hint").getString();
	}

	private void onIconPicked(String errorKey) {
		setIconMsg(errorKey);
	}

	private void openClearDialog() {
		int n = StudioIndex.selectionCount();
		this.dialog = Dialog.CLEAR;
		this.dlgTitle = Text.translatable("resourcepackstudio.clear.title").getString();
		this.dlgLine1 = "";
		this.dlgLine2 = Text.translatable(n == 1 ? "resourcepackstudio.clear.desc.one"
			: "resourcepackstudio.clear.desc.many", n).getString();
		this.dlgLine3 = "";
		this.okLabel = Text.translatable("resourcepackstudio.clear.confirm").getString();
		this.noLabel = Text.translatable("resourcepackstudio.create.cancel").getString();
	}

	private void startBuild() {
		this.dialog = Dialog.WORKING;
		this.dlgTitle = Text.translatable("resourcepackstudio.create.working").getString();
		this.dlgLine1 = "";
		this.dlgLine2 = "";
		this.dlgLine3 = "";
		PackBuilder.build(nameField.text(), icon.path(), this::onBuilt);
	}

	private void onBuilt(PackBuilder.Built b) {
		this.built = b;
		if (b.ok()) {
			this.dialog = Dialog.DONE;
			this.dlgTitle = Text.translatable("resourcepackstudio.create.done").getString();
			this.dlgLine1 = b.name();
			this.dlgLine2 = Text.translatable("resourcepackstudio.create.saved",
				b.textures(), b.file().getFileName().toString()).getString();
			this.dlgLine3 = Text.translatable("resourcepackstudio.create.enable.desc").getString();
			this.okLabel = Text.translatable("resourcepackstudio.create.enable").getString();
			this.noLabel = Text.translatable("resourcepackstudio.create.close").getString();
		} else {
			this.dialog = Dialog.ERROR;
			this.dlgTitle = Text.translatable("resourcepackstudio.create.failed").getString();
			this.dlgLine1 = b.error();
			this.dlgLine2 = Text.translatable("resourcepackstudio.studio.failed").getString();
			this.dlgLine3 = "";
			this.okLabel = "";
			this.noLabel = Text.translatable("resourcepackstudio.create.close").getString();
		}
	}

	private void renderDialog(DrawContext ctx, int mouseX, int mouseY) {
		// Neue Ebene: Sonst laege die Schrift der Seite darunter UEBER der
		// Dialogflaeche - Minecraft zeichnet Text einer Ebene zuletzt.
		ctx.createNewRootLayer();
		ctx.fill(px, py, px + pw, py + ph, 0x88000000);

		int pad = 14;
		this.dlgW = Math.min(300, pw - 40);
		int lineH = Ui.advance(Ui.BODY_H, Ui.BODY_D, 4);
		int h = pad + Ui.TITLE_H + Ui.TITLE_D + 10;
		if (dialog == Dialog.NAME) {
			h += Ui.SMALL_H + Ui.SMALL_D + 6 + BTN_H + 12           // Name
				+ Ui.SMALL_H + Ui.SMALL_D + 6 + ICON_BOX + 6         // Icon
				+ Ui.SMALL_H + Ui.SMALL_D + 12                       // Hinweis
				+ Ui.SMALL_H + Ui.SMALL_D;                           // Anzahl
		} else {
			if (!dlgLine1.isEmpty()) h += lineH;
			if (!dlgLine2.isEmpty()) h += Ui.advance(Ui.SMALL_H, Ui.SMALL_D, 6);
			if (!dlgLine3.isEmpty()) h += Ui.advance(Ui.SMALL_H, Ui.SMALL_D, 6) * 2;
		}
		if (dialog != Dialog.WORKING) {
			h += 14 + BTN_H;
		}
		h += pad;
		this.dlgH = h;
		this.dlgX = px + (pw - dlgW) / 2;
		this.dlgY = py + (ph - dlgH) / 2;

		Surface.shadowCard(ctx, dlgX, dlgY, dlgW, dlgH, 6, 3, Theme.SHADOW);
		if (!Surface.glassCard(ctx, dlgX, dlgY, dlgW, dlgH, DIALOG_FILL)) {
			Surface.card(ctx, dlgX, dlgY, dlgW, dlgH, DIALOG_FILL);
		}

		int x = dlgX + pad;
		int w = dlgW - pad * 2;
		int y = dlgY + pad;
		Ui.drawTop(ctx, this.textRenderer, Ui.title(Ui.fit(this.textRenderer, dlgTitle, w)),
			x, y, Ui.TOP_TITLE, Theme.TEXT);
		y += Ui.TITLE_H + Ui.TITLE_D + 10;

		if (dialog == Dialog.NAME) {
			Ui.drawTop(ctx, this.textRenderer, Ui.small(dlgLine1), x, y, Ui.TOP_SMALL, Theme.TEXT_MUTED);
			y += Ui.SMALL_H + Ui.SMALL_D + 6;
			nameField.render(ctx, this.textRenderer, x, y, w, BTN_H);
			y += BTN_H + 12;

			// Pack-Icon: Vorschau, "Bild waehlen", ggf. "Entfernen".
			Ui.drawTop(ctx, this.textRenderer, Ui.small(iconLabel), x, y, Ui.TOP_SMALL, Theme.TEXT_MUTED);
			y += Ui.SMALL_H + Ui.SMALL_D + 6;
			Surface.row(ctx, x, y, ICON_BOX, ICON_BOX, Theme.FIELD_FOCUS);
			if (icon.texture() != null) {
				ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon.texture(), x + 2, y + 2, 0.0F, 0.0F,
					ICON_BOX - 4, ICON_BOX - 4, ICON_BOX - 4, ICON_BOX - 4);
			}
			int by0 = y + (ICON_BOX - BTN_H) / 2;
			this.chooseX = x + ICON_BOX + 10;
			this.chooseY = by0;
			this.chooseW = Ui.width(this.textRenderer, chooseLabel) + 24;
			boolean ch = !icon.choosing() && Draw.inside(mouseX, mouseY, chooseX, chooseY, chooseW, BTN_H);
			Surface.row(ctx, chooseX, chooseY, chooseW, BTN_H, ch ? Theme.FIELD_HOVER : Theme.FIELD);
			Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(chooseLabel), chooseX + 12, chooseY, BTN_H,
				Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, icon.choosing() ? Theme.TEXT_FAINT : Theme.TEXT);
			if (icon.path() != null) {
				this.removeX = chooseX + chooseW + 6;
				this.removeW = Ui.width(this.textRenderer, removeLabel) + 24;
				boolean rh = Draw.inside(mouseX, mouseY, removeX, chooseY, removeW, BTN_H);
				Surface.row(ctx, removeX, chooseY, removeW, BTN_H, rh ? Theme.FIELD_HOVER : Theme.FIELD);
				Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(removeLabel), removeX + 12, chooseY, BTN_H,
					Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT);
			} else {
				this.removeW = 0;
			}
			y += ICON_BOX + 6;
			Ui.drawTop(ctx, this.textRenderer, Ui.small(Ui.fitSmall(this.textRenderer, iconMsg, w)),
				x, y, Ui.TOP_SMALL, iconMsgWarn ? Theme.WARN : Theme.TEXT_FAINT);
			y += Ui.SMALL_H + Ui.SMALL_D + 12;

			Ui.drawTop(ctx, this.textRenderer, Ui.small(Ui.fitSmall(this.textRenderer, dlgLine2, w)),
				x, y, Ui.TOP_SMALL, Theme.TEXT_FAINT);
			y += Ui.SMALL_H + Ui.SMALL_D;
		} else {
			if (!dlgLine1.isEmpty()) {
				Ui.drawTop(ctx, this.textRenderer, Ui.bold(Ui.fitBold(this.textRenderer, dlgLine1, w)),
					x, y, Ui.TOP_BODY, dialog == Dialog.ERROR ? Theme.WARN : Theme.TEXT);
				y += lineH;
			}
			if (!dlgLine2.isEmpty()) {
				Ui.drawTop(ctx, this.textRenderer, Ui.small(Ui.fitSmall(this.textRenderer, dlgLine2, w)),
					x, y, Ui.TOP_SMALL, Theme.TEXT_MUTED);
				y += Ui.advance(Ui.SMALL_H, Ui.SMALL_D, 6);
			}
			if (!dlgLine3.isEmpty()) {
				// Zwei Zeilen Platz: Hinweis, was "Enable pack now" tut.
				List<String> lines = Ui.wrap(this.textRenderer, dlgLine3, w);
				for (int i = 0; i < Math.min(2, lines.size()); i++) {
					Ui.drawTop(ctx, this.textRenderer, Ui.small(lines.get(i)), x, y, Ui.TOP_SMALL, Theme.TEXT_FAINT);
					y += Ui.advance(Ui.SMALL_H, Ui.SMALL_D, 6);
				}
			}
		}

		if (dialog == Dialog.WORKING) {
			okW = noW = 0;
			return;
		}

		// Knoepfe rechts unten: [Abbrechen/Schliessen] [Hauptaktion]
		int by = dlgY + dlgH - pad - BTN_H;
		int right = dlgX + dlgW - pad;
		if (!okLabel.isEmpty()) {
			this.okW = Ui.widthBold(this.textRenderer, okLabel) + 24;
			this.okX = right - okW;
			this.okY = by;
			boolean hov = Draw.inside(mouseX, mouseY, okX, okY, okW, BTN_H);
			// Abwaehlen ist rot - es verwirft etwas. Alles andere blau.
			int fill = dialog == Dialog.CLEAR
				? (hov ? 0xFFC8382F : Theme.WARN)
				: (hov ? 0xFF2B97F0 : Theme.TOGGLE_ON);
			Surface.row(ctx, okX, okY, okW, BTN_H, fill);
			Ui.drawCenteredIn(ctx, this.textRenderer, Ui.bold(okLabel), okX + 12, okY, BTN_H,
				Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, 0xFFFFFFFF);
			right = okX - 8;
		} else {
			this.okW = 0;
		}
		this.noW = Ui.width(this.textRenderer, noLabel) + 24;
		this.noX = right - noW;
		this.noY = by;
		boolean hov = Draw.inside(mouseX, mouseY, noX, noY, noW, BTN_H);
		Surface.row(ctx, noX, noY, noW, BTN_H, hov ? Theme.FIELD_HOVER : Theme.FIELD);
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(noLabel), noX + 12, noY, BTN_H,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT);
	}

	/** Klick, waehrend ein Dialog offen ist. Alles andere ist gesperrt. */
	private boolean dialogClick(double mx, double my) {
		if (dialog == Dialog.WORKING) {
			return true;
		}
		if (dialog == Dialog.NAME) {
			if (!icon.choosing() && Draw.inside(mx, my, chooseX, chooseY, chooseW, BTN_H)) {
				icon.choose(this::onIconPicked);
				return true;
			}
			if (removeW > 0 && Draw.inside(mx, my, removeX, chooseY, removeW, BTN_H)) {
				icon.clear();
				setIconMsg(null);
				return true;
			}
		}
		if (okW > 0 && Draw.inside(mx, my, okX, okY, okW, BTN_H)) {
			if (dialog == Dialog.NAME) {
				startBuild();
			} else if (dialog == Dialog.CLEAR) {
				StudioIndex.clearSelection();
				refreshLabels();
				dialog = Dialog.NONE;
			} else if (dialog == Dialog.DONE && built != null && built.file() != null) {
				PackBuilder.enable(built.file());
				dialog = Dialog.NONE;
			}
			return true;
		}
		if (noW > 0 && Draw.inside(mx, my, noX, noY, noW, BTN_H)) {
			dialog = Dialog.NONE;
			return true;
		}
		return true;
	}

	/** Wie im Hub: im Hauptmenue hat Vanilla schon geblurrt, in der Welt nicht. */
	private void maybeBlur(DrawContext ctx) {
		if (this.client == null || this.client.world == null) {
			return;
		}
		try {
			ctx.applyBlur();
		} catch (IllegalStateException ignored) {
		}
	}

	private void renderSidebar(DrawContext ctx, int mouseX, int mouseY) {
		int x = sx + Metrics.PAD;
		int w = sw - Metrics.PAD * 2;
		int y = sy + Metrics.PAD;

		Ui.drawTop(ctx, this.textRenderer, Ui.bold(Ui.fitBold(this.textRenderer, sideTitle, w)),
			x, y, Ui.TOP_BODY, Theme.TEXT);
		Ui.drawTop(ctx, this.textRenderer, Ui.small(Ui.fitSmall(this.textRenderer, sideSub, w)),
			x, y + Ui.advance(Ui.BODY_H, Ui.BODY_D, 2), Ui.TOP_SMALL, Theme.TEXT_FAINT);
		this.search.render(ctx, this.textRenderer, x - 4, searchY, w + 8, Metrics.SEARCH_H, mouseX, mouseY);
		Draw.hLine(ctx, x, sideTop - 5, w, Theme.DIVIDER);

		int maxScroll = Math.max(0, sideHeight - (sideBottom - sideTop));
		if (sideScroll > maxScroll) {
			sideScroll = maxScroll;
		}

		// Etwas breiter als die Zeilen: Die Hover-Flaeche ragt 4 Pixel ueber.
		ctx.enableScissor(sx, sideTop, sx + sw, sideBottom);
		Sdf.clip(new ScreenRect(sx, sideTop, sw, sideBottom - sideTop));
		boolean inList = mouseY >= sideTop && mouseY < sideBottom;
		for (int i = 0; i < rowItem.length; i++) {
			int ry = sideTop + rowY[i] - sideScroll;
			if (ry + rowH[i] < sideTop || ry > sideBottom) {
				continue;
			}
			int item = rowItem[i];
			if (item <= CAT_ROW) {
				drawCategoryRow(ctx, i, CAT_ROW - item, x, ry, w,
					inList && Draw.inside(mouseX, mouseY, x - 4, ry, w + 8, Metrics.ROW_H));
				continue;
			}
			if (item < 0) {
				Ui.drawTop(ctx, this.textRenderer, Ui.small(rowLabel[i]),
					x, ry + GROUP_H - Ui.SMALL_H - 4, Ui.TOP_SMALL, Theme.TEXT_FAINT);
				continue;
			}
			drawItemRow(ctx, item, x, ry, w, inList && Draw.inside(mouseX, mouseY, x - 4, ry, w + 8, Metrics.ROW_H));
		}
		if (rowItem.length == 0) {
			Ui.drawTop(ctx, this.textRenderer, Ui.text(Ui.fit(this.textRenderer, noMatchLabel, w)),
				x, sideTop + 4, Ui.TOP_BODY, Theme.TEXT_FAINT);
		}
		Sdf.clip(null);
		ctx.disableScissor();
	}

	/** Kopf einer Kategorie: Pfeil, fetter Name, rechts die Anzahl. */
	private void drawCategoryRow(DrawContext ctx, int row, int cat, int x, int y, int w, boolean hover) {
		if (hover) {
			Surface.row(ctx, x - 4, y, w + 8, Metrics.ROW_H, Theme.ROW_HOVER);
		}
		boolean open = !search.text().isBlank() || expanded.contains(cat);
		// Pfeil aus Linien: zu = nach rechts, offen = nach unten. Genau
		// auf der Mitte der Zeile (Hoehe 21: Mitte zwischen Pixel 10 und 11).
		int top = y + (Metrics.ROW_H - 9) / 2;
		if (open) {
			for (int i = 0; i < 5; i++) {
				ctx.fill(x + i, top + 2 + i, x + 9 - i, top + 3 + i, Theme.TEXT_MUTED);
			}
		} else {
			for (int i = 0; i < 5; i++) {
				ctx.fill(x + 2 + i, top + i, x + 3 + i, top + 9 - i, Theme.TEXT_MUTED);
			}
		}
		// drawCenteredIn: gleich viel Luft ueber und unter der Schrift -
		// wie "All Mods" im Hub. drawCenteredLine richtet nach der
		// Schreiblinie aus, das sass hier sichtbar zu tief.
		String count = rowCount[row] == null ? "" : rowCount[row];
		int cw = Ui.widthSmall(this.textRenderer, count);
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.small(count), x + w - cw, y, Metrics.ROW_H,
			Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT_FAINT);
		Ui.drawCenteredIn(ctx, this.textRenderer,
			Ui.bold(Ui.fitBold(this.textRenderer, rowLabel[row], w - 14 - cw - 6)), x + 14, y, Metrics.ROW_H,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT);
	}

	private void drawItemRow(DrawContext ctx, int item, int x, int y, int w, boolean hover) {
		boolean sel = item == selected;
		if (sel) {
			Surface.row(ctx, x - 4, y, w + 8, Metrics.ROW_H, Theme.ROW_SELECTED);
		} else if (hover) {
			Surface.row(ctx, x - 4, y, w + 8, Metrics.ROW_H, Theme.ROW_HOVER);
		}
		int mid = y + Metrics.ROW_H / 2;

		boolean snd = items.get(item).sound();
		if (!snd) {
			drawPreview(ctx, items.get(item), null, x, mid - ITEM_ICON / 2, ITEM_ICON);
		}

		// Rechts: Anzahl der Varianten, davor ein Punkt, wenn eine
		// gewaehlt ist (also nicht mehr Vanilla).
		String count = itemCounts.length > item ? itemCounts[item] : "";
		int right = x + w;
		if (!count.isEmpty()) {
			int cwid = Ui.widthSmall(this.textRenderer, count);
			right -= cwid;
			Ui.drawCenteredLine(ctx, this.textRenderer, Ui.small(count), right, mid,
				Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT_FAINT);
			right -= 5;
		}
		if (StudioIndex.selected(items.get(item)) != null) {
			Draw.dot(ctx, right - 2, mid, 2, Theme.TOGGLE_ON);
			right -= 8;
		}

		int tx = snd ? x + 4 : x + ITEM_ICON + Metrics.ROW_ICON_GAP;
		String label = Ui.fit(this.textRenderer, itemNames[item], right - tx - 2);
		Ui.drawCenteredLine(ctx, this.textRenderer, Ui.text(label), tx, mid,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, sel ? Theme.TEXT : Theme.TEXT_MUTED);
	}

	private void renderContent(DrawContext ctx, int mouseX, int mouseY) {
		int headY = cy + 10;
		Ui.drawTop(ctx, this.textRenderer, Ui.title(headerTitle), cx, headY, Ui.TOP_TITLE, Theme.TEXT);
		if (StudioIndex.result() != null) {
			Ui.drawTop(ctx, this.textRenderer, Ui.small(headerCount),
				cx + Ui.widthTitle(this.textRenderer, headerTitle) + 8,
				headY + Ui.TITLE_H - Ui.SMALL_H, Ui.TOP_SMALL, Theme.TEXT_FAINT);
		}

		// Knopf oben rechts: neu suchen. Waehrend einer Suche steht dort,
		// dass gesucht wird, und er tut nichts.
		boolean scanning = StudioIndex.scanning();
		String btn = scanning ? scanningLabel : rescanLabel;
		this.rescanH = Metrics.SEARCH_H;
		this.rescanW = Ui.width(this.textRenderer, btn) + 20;
		this.rescanX = cx + cw - rescanW;
		this.rescanY = cy + (Metrics.TITLE_H - rescanH) / 2;
		boolean btnHover = !scanning && Draw.inside(mouseX, mouseY, rescanX, rescanY, rescanW, rescanH);
		Surface.row(ctx, rescanX, rescanY, rescanW, rescanH, btnHover ? Theme.FIELD_HOVER : Theme.FIELD);
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(btn), rescanX + 10, rescanY, rescanH,
			Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, scanning ? Theme.TEXT_FAINT : Theme.TEXT_MUTED);

		if (StudioIndex.selectionCount() > 0) {
			this.clearW = Ui.width(this.textRenderer, clearLabel) + 20;
			this.clearX = rescanX - 6 - clearW;
			boolean ch = Draw.inside(mouseX, mouseY, clearX, rescanY, clearW, rescanH);
			Surface.row(ctx, clearX, rescanY, clearW, rescanH, ch ? Theme.FIELD_HOVER : Theme.FIELD);
			Ui.drawCenteredIn(ctx, this.textRenderer, Ui.text(clearLabel), clearX + 10, rescanY, rescanH,
				Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT_MUTED);
		} else {
			this.clearW = 0;
		}

		PackScanner.Result r = StudioIndex.result();
		if (r == null) {
			// Noch nie fertig gesucht: entweder laeuft es, oder es ist
			// schiefgegangen (dann steht der Grund im Log).
			Ui.drawTop(ctx, this.textRenderer, Ui.text(scanning ? scanningLabel : failedLabel),
				cx, listTop + 4, Ui.TOP_BODY, Theme.TEXT_FAINT);
			return;
		}

		List<PackScanner.Variant> vs = variants.length > selected ? variants[selected] : List.of();
		int cards = vs.size() + 1;
		int rows = (cards + cols - 1) / cols;
		int extra = vs.isEmpty() ? Ui.advance(Ui.BODY_H, Ui.BODY_D, 0) + 12 : 0;
		this.contentHeight = rows * (CARD_H + CARD_GAP) + extra;
		int gt = gridTop();
		int maxScroll = Math.max(0, contentHeight - (listBottom - gt));
		if (scroll > maxScroll) {
			scroll = maxScroll;
		}

		StudioItem item = items.get(selected);
		PackScanner.Variant chosen = StudioIndex.selected(item);
		boolean inList = mouseY >= gt && mouseY < listBottom;

		if (item.sound()) {
			renderVolume(ctx, mouseX, mouseY);
		}

		ctx.enableScissor(cx, gt, cx + cw, listBottom);
		Sdf.clip(new ScreenRect(cx, gt, cw, listBottom - gt));
		for (int i = 0; i < cards; i++) {
			int bx = cx + (i % cols) * (colW + CARD_GAP);
			int by = gt + (i / cols) * (CARD_H + CARD_GAP) - scroll;
			if (by + CARD_H < gt || by > listBottom) {
				continue;
			}
			PackScanner.Variant v = i == 0 ? null : vs.get(i - 1);
			boolean isChosen = v == chosen;
			boolean hover = inList && Draw.inside(mouseX, mouseY, bx, by, colW, CARD_H);
			drawCard(ctx, bx, by, item, v, i < cardNames.length ? cardNames[i] : "", isChosen, hover);
		}
		if (vs.isEmpty()) {
			Ui.drawTop(ctx, this.textRenderer,
				Ui.text(Ui.fit(this.textRenderer, emptyLabel, cw)),
				cx, gt + CARD_H + CARD_GAP + 6 - scroll, Ui.TOP_BODY, Theme.TEXT_FAINT);
		}
		Sdf.clip(null);
		ctx.disableScissor();
	}

	// ---- Lautstaerke der Sound-Vorschau ---------------------------------

	private static final int VOL_ROW = 26;
	private static final int VOL_TRACK_W = 140;
	private int volTrackX, volY;
	private boolean volDragging;

	/** Oberkante der Karten: bei Sounds unter der Lautstaerke-Zeile. */
	private int gridTop() {
		return !items.isEmpty() && items.get(selected).sound() ? listTop + VOL_ROW : listTop;
	}

	/** "Preview volume" mit Schieber, 0 bis 200 %. */
	private void renderVolume(DrawContext ctx, int mouseX, int mouseY) {
		String label = Text.translatable("resourcepackstudio.sound.volume").getString();
		int h = 20;
		this.volY = listTop;
		int mid = volY + h / 2;
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.small(label), cx, volY, h,
			Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT_MUTED);
		this.volTrackX = cx + Ui.widthSmall(this.textRenderer, label) + 12;
		float f = SoundPreview.volume() / SoundPreview.MAX_VOLUME;
		int filled = Math.round(VOL_TRACK_W * f);
		Draw.roundedRect(ctx, volTrackX, mid - 2, VOL_TRACK_W, 4, 2, Theme.TOGGLE_OFF);
		Draw.roundedRect(ctx, volTrackX, mid - 2, Math.max(4, filled), 4, 2, Theme.TOGGLE_ON);
		boolean hover = volDragging || Draw.inside(mouseX, mouseY, volTrackX - 6, volY, VOL_TRACK_W + 12, h);
		Draw.dot(ctx, volTrackX + filled, mid, hover ? 6 : 5, 0xFFFFFFFF);
		Draw.dot(ctx, volTrackX + filled, mid, hover ? 4 : 3, Theme.TOGGLE_ON);
		String pct = Math.round(SoundPreview.volume() * 100) + " %";
		Ui.drawCenteredIn(ctx, this.textRenderer, Ui.small(pct), volTrackX + VOL_TRACK_W + 12, volY, h,
			Ui.SMALL_H, Ui.SMALL_D, Ui.TOP_SMALL, Theme.TEXT_MUTED);
	}

	private void setVolumeFromMouse(double mx) {
		float f = (float) ((mx - volTrackX) / VOL_TRACK_W);
		SoundPreview.setVolume(Math.max(0.0F, Math.min(1.0F, f)) * SoundPreview.MAX_VOLUME);
	}

	@Override
	protected boolean scaledMouseDragged(Click click, double dx, double dy) {
		if (volDragging) {
			setVolumeFromMouse(click.x());
			return true;
		}
		return super.scaledMouseDragged(click, dx, dy);
	}

	@Override
	protected boolean scaledMouseReleased(Click click) {
		volDragging = false;
		return super.scaledMouseReleased(click);
	}

	private void drawCard(DrawContext ctx, int x, int y, StudioItem item, PackScanner.Variant v,
	                      String name, boolean chosen, boolean hover) {
		Surface.shadowCard(ctx, x, y, colW, CARD_H, 3, 2, Theme.SHADOW_CARD);
		int fill = chosen ? Theme.ROW_SELECTED : (hover ? Theme.CARD_HOVER : Theme.CARD);
		if (!Surface.glassCard(ctx, x, y, colW, CARD_H, fill)) {
			Surface.card(ctx, x, y, colW, CARD_H, fill);
		}
		if (chosen) {
			Draw.roundedOutline(ctx, x, y, colW, CARD_H, 6, Theme.TOGGLE_ON);
		}

		int ix = x + (colW - PREVIEW) / 2;
		int iy = y + CARD_PAD;
		drawPreview(ctx, item, v, ix, iy, PREVIEW);

		int nw = Ui.widthSmall(this.textRenderer, name);
		Ui.drawTop(ctx, this.textRenderer, Ui.small(name),
			x + (colW - nw) / 2, iy + PREVIEW + 6, Ui.TOP_SMALL,
			chosen ? Theme.TEXT : Theme.TEXT_MUTED);
	}

	/**
	 * Vorschau eines Eintrags: flach als Bild, ein Block als Wuerfel.
	 *
	 * @param v Variante aus einem Pack, oder null fuer Vanilla. Hat das Pack
	 *          einen Teil nicht (oder ist er fremd, siehe Part.own), wird
	 *          dort das Vanilla-Bild gezeigt - genau so saehe es im Spiel aus.
	 */
	private static void drawPreview(DrawContext ctx, StudioItem item, PackScanner.Variant v,
	                                int x, int y, int size) {
		if (item.sound()) {
			drawPlay(ctx, x, y, size);
			return;
		}
		if (!item.cube()) {
			Textures.Tex t = tex(item, v, 0);
			if (t != null) {
				int frame = Math.min(t.w(), t.h());
				ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0.0F, 0.0F,
					size, size, frame, frame, t.w(), t.h());
			}
			return;
		}
		// Blockmodell wie im Inventar, aus seinen Quadern. Projektion:
		// Norden links, Osten rechts, oben oben. Ein voller Wuerfel fuellt
		// genau size x size.
		for (StudioItem.Box b : item.boxes()) {
			float k = size / 32.0F;
			float dx = b.x2() - b.x1();
			float dy = b.y2() - b.y1();
			float dz = b.z2() - b.z1();
			float ox = px(x, size, b.x1(), b.z1());
			float oy = py(y, size, b.x1(), b.y2(), b.z1());
			if (b.up() != null) {
				// Ecke hinten links (z2) als Ursprung, zweite Richtung nach
				// Norden. Andersherum waere die Flaeche gespiegelt - und
				// gespiegelte Flaechen wirft Minecraft beim Zeichnen weg
				// (deshalb fehlte die Oberseite).
				float tx = px(x, size, b.x1(), b.z2());
				float ty = py(y, size, b.x1(), b.y2(), b.z2());
				face(ctx, tex(item, v, b.up().part()), b.up(), tx, ty,
					dx * k, dx * k / 2, -dz * k, dz * k / 2, 0xFFFFFFFF);
			}
			if (b.north() != null) {
				face(ctx, tex(item, v, b.north().part()), b.north(), ox, oy,
					dx * k, dx * k / 2, 0, dy * k, 0xFFCFCFCF);
			}
			if (b.east() != null) {
				float ex = px(x, size, b.x2(), b.z1());
				float ey = py(y, size, b.x2(), b.y2(), b.z1());
				face(ctx, tex(item, v, b.east().part()), b.east(), ex, ey,
					dz * k, -dz * k / 2, 0, dy * k, 0xFFA8A8A8);
			}
		}
	}

	/** Bildschirm-x eines Modellpunkts (x Osten, z Sueden). */
	private static float px(int x0, int size, float x, float z) {
		return x0 + size / 2.0F + (z - 16 + x) * size / 32.0F;
	}

	/** Bildschirm-y eines Modellpunkts. */
	private static float py(int y0, int size, float x, float y, float z) {
		return y0 + size / 2.0F - (16 - x + z) * size / 64.0F + (16 - y) * size / 32.0F;
	}

	/** Abspielknopf: helle Flaeche mit Dreieck. Ein Klick auf die Karte waehlt und spielt. */
	private static void drawPlay(DrawContext ctx, int x, int y, int size) {
		int d = size - 8;
		int bx = x + 4;
		int by = y + 4;
		if (!Surface.glassCard(ctx, bx, by, d, d, Theme.FIELD_FOCUS)) {
			Surface.row(ctx, bx, by, d, d, Theme.FIELD_FOCUS);
		}
		// Dreieck nach rechts, Spalte fuer Spalte.
		int th = d / 2;
		int tx = bx + d / 2 - th / 4;
		int midY = by + d / 2;
		for (int i = 0; i < th / 2 + 1; i++) {
			int half = th / 2 - i;
			ctx.fill(tx + i, midY - half, tx + i + 1, midY + half + 1, Theme.TEXT);
		}
	}

	private static Textures.Tex tex(StudioItem item, PackScanner.Variant v, int part) {
		if (v != null && part < v.entries().size() && v.entries().get(part) != null) {
			Textures.Tex t = Textures.variant(v, part);
			// Laedt noch: lieber kurz Vanilla als ein Loch in der Vorschau.
			return t != null ? t : Textures.vanilla(item, part);
		}
		return Textures.vanilla(item, part);
	}

	/** Wiederverwendet - keine neue Matrix pro Flaeche und Bild. */
	private static final org.joml.Matrix3x2f FACE = new org.joml.Matrix3x2f();

	/**
	 * Eine Seite eines Quaders: der Bildausschnitt der Seite, verzerrt auf
	 * origin + u*(ax,ay) + v*(bx,by). Gezeichnet wird ein 16x16-Quadrat,
	 * daher /16. Animierte Bilder: nur das erste Einzelbild.
	 */
	private static void face(DrawContext ctx, Textures.Tex t, StudioItem.Face f, float ox, float oy,
	                         float ax, float ay, float bx, float by, int color) {
		if (t == null) {
			return;
		}
		int frame = Math.min(t.w(), t.h());
		float uMin = Math.min(f.u1(), f.u2());
		float vMin = Math.min(f.v1(), f.v2());
		int u = Math.round(uMin / 16.0F * t.w());
		int vv = Math.round(vMin / 16.0F * frame);
		int rw = Math.round(Math.abs(f.u2() - f.u1()) / 16.0F * t.w());
		int rh = Math.round(Math.abs(f.v2() - f.v1()) / 16.0F * frame);
		if (rw <= 0 || rh <= 0) {
			return;
		}
		ctx.getMatrices().pushMatrix();
		FACE.set(ax / 16.0F, ay / 16.0F, bx / 16.0F, by / 16.0F, ox, oy);
		ctx.getMatrices().mul(FACE);
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), 0, 0, u, vv,
			16, 16, rw, rh, t.w(), t.h(), color);
		ctx.getMatrices().popMatrix();
	}

	// ------------------------------------------------------------------
	// Eingabe
	// ------------------------------------------------------------------

	@Override
	protected boolean scaledMouseClicked(Click click, boolean doubled) {
		if (super.scaledMouseClicked(click, doubled)) {
			return true;
		}
		if (click.button() != 0) {
			return false;
		}
		double mx = click.x();
		double my = click.y();

		if (dialog != Dialog.NONE) {
			return dialogClick(mx, my);
		}

		// Pack erstellen - nur, wenn etwas gewaehlt ist.
		if (Draw.inside(mx, my, createX, createY, createW, BTN_H)) {
			if (StudioIndex.selectionCount() > 0) {
				this.search.setFocused(false);
				openNameDialog();
			}
			return true;
		}

		// Suchfeld: Klick hinein gibt den Fokus, Klick daneben nimmt ihn.
		if (this.search.mouseClicked(mx, my)) {
			return true;
		}

		// Sidebar
		if (Draw.inside(mx, my, sx, sideTop, sw, sideBottom - sideTop)) {
			int x = sx + Metrics.PAD;
			int w = sw - Metrics.PAD * 2;
			for (int i = 0; i < rowItem.length; i++) {
				int ry = sideTop + rowY[i] - sideScroll;
				if (rowItem[i] <= CAT_ROW) {
					// Kategorie auf- oder zuklappen. Waehrend einer Suche ist
					// alles offen - dann bewirkt der Klick nichts.
					if (Draw.inside(mx, my, x - 4, ry, w + 8, Metrics.ROW_H)) {
						if (search.text().isBlank()) {
							int cat = CAT_ROW - rowItem[i];
							if (!expanded.remove(cat)) {
								expanded.add(cat);
							}
							buildRows(false);
						}
						return true;
					}
					continue;
				}
				if (rowItem[i] < 0) {
					continue;
				}
				if (Draw.inside(mx, my, x - 4, ry, w + 8, Metrics.ROW_H)) {
					if (selected != rowItem[i]) {
						selected = rowItem[i];
						lastItem = items.get(selected).id();
						scroll = 0;
						refreshLabels();
					}
					return true;
				}
			}
			return false;
		}

		// Alles abwaehlen - mit Rueckfrage, das laesst sich nicht rueckgaengig machen.
		if (clearW > 0 && Draw.inside(mx, my, clearX, rescanY, clearW, rescanH)) {
			this.search.setFocused(false);
			openClearDialog();
			return true;
		}

		// Rescan
		if (!StudioIndex.scanning() && Draw.inside(mx, my, rescanX, rescanY, rescanW, rescanH)) {
			StudioIndex.rescan();
			return true;
		}

		// Karten
		// Lautstaerke-Schieber (nur bei Sounds)
		if (StudioIndex.result() != null && items.get(selected).sound()
				&& Draw.inside(mx, my, volTrackX - 6, volY, VOL_TRACK_W + 12, 20)) {
			volDragging = true;
			setVolumeFromMouse(mx);
			return true;
		}

		int gt = gridTop();
		if (StudioIndex.result() != null && Draw.inside(mx, my, cx, gt, cw, listBottom - gt)) {
			List<PackScanner.Variant> vs = variants.length > selected ? variants[selected] : List.of();
			int cards = vs.size() + 1;
			for (int i = 0; i < cards; i++) {
				int bx = cx + (i % cols) * (colW + CARD_GAP);
				int by = gt + (i / cols) * (CARD_H + CARD_GAP) - scroll;
				if (Draw.inside(mx, my, bx, by, colW, CARD_H)) {
					StudioItem cur = items.get(selected);
					PackScanner.Variant v = i == 0 ? null : vs.get(i - 1);
					// Sound: Klick waehlt UND spielt ab - egal wo auf der Karte.
					if (cur.sound()) {
						if (v == null) {
							SoundPreview.playVanilla(cur);
						} else {
							SoundPreview.playVariant(v);
						}
					}
					StudioIndex.select(cur, v);
					refreshLabels();
					return true;
				}
			}
		}
		return false;
	}

	@Override
	protected boolean scaledMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (dialog != Dialog.NONE) {
			return true;
		}
		int step = (int) (vertical * 18);
		if (mouseX < cx) {
			int max = Math.max(0, sideHeight - (sideBottom - sideTop));
			sideScroll = Math.max(0, Math.min(max, sideScroll - step));
			return true;
		}
		int max = Math.max(0, contentHeight - (listBottom - gridTop()));
		scroll = Math.max(0, Math.min(max, scroll - step));
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (dialog != Dialog.NONE) {
			int key = input.getKeycode();
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				if (dialog != Dialog.WORKING) {
					dialog = Dialog.NONE;
				}
				return true;
			}
			if (dialog == Dialog.NAME) {
				if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
					startBuild();
					return true;
				}
				nameField.keyPressed(input);
			}
			// Nichts durchlassen: Tasten gehoeren dem Dialog, nicht dem
			// Suchfeld darunter.
			return true;
		}
		// Das Suchfeld zuerst. Escape leert es bzw. nimmt den Fokus - erst
		// ein weiteres Escape schliesst das Studio.
		String before = search.text();
		if (this.search.keyPressed(input)) {
			if (!before.equals(search.text())) {
				buildRows();
			}
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (dialog != Dialog.NONE) {
			if (dialog == Dialog.NAME) {
				nameField.charTyped(input);
			}
			return true;
		}
		if (this.search.charTyped(input)) {
			buildRows();
			return true;
		}
		return super.charTyped(input);
	}
}
