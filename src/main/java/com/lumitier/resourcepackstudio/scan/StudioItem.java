package com.lumitier.resourcepackstudio.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Eintrag in der Sidebar - ein flaches Item oder ein Block - und wo
 * seine Texturen in einem Pack liegen koennen.
 *
 * TEILE: Ein flaches Item hat genau ein Bild. Ein Block kann mehrere haben
 * (Holzstamm: Seite und Schnittflaeche, TNT: oben, unten, Seite). Jedes
 * Bild ist ein Teil mit eigenem Pfad und eigenen alten 1.8-Namen.
 *
 * ZWEI ORTE PRO TEIL: Seit 1.13 heissen die Ordner textures/item und
 * textures/block und viele Texturen haben neue Namen. Davor hiessen sie
 * textures/items und textures/blocks - und ein Grossteil der PvP-Packs
 * stammt aus 1.8.
 *
 * @param id     Kennung ohne Namensraum, wie in Minecraft (stone_sword)
 * @param group  Gruppe in der Sidebar
 * @param parts  die Bilder, mindestens eins
 * @param boxes  bei Bloecken: die Quader des Modells fuer die Vorschau, in
 *               Zeichenreihenfolge (hinten zuerst). null bei flachen Items.
 * @param block  bei Bloecken: Kennung des Blocks (fuer blockstates/), sonst null
 */
public record StudioItem(String id, String group, List<Part> parts, List<Box> boxes, String block) {

	public static final String TEXTURES = "assets/minecraft/textures/";
	public static final String ASSETS = "assets/minecraft/";

	/** Sounds haben als Pfad "sounds/..." - alles andere sind Bilder. */
	public static final String SOUNDS = "sounds/";

	/**
	 * Ein Bild.
	 *
	 * @param modern Pfad ab 1.13, relativ zu textures/ und ohne .png
	 *               ("item/stone_sword", "block/oak_log_top")
	 * @param legacy Pfade vor 1.13, ebenso relativ ("items/wood_sword")
	 * @param own    gehoert das Bild zu DIESEM Eintrag? Eigene Bilder landen
	 *               im neuen Pack an ihrem normalen Pfad. Geliehene (die
	 *               Eichentreppe benutzt die Eichenbretter) bekommen eine
	 *               eigene Kopie samt eigenem Modell - so aendert eine
	 *               gewaehlte Treppe nicht nebenbei alle Bretter.
	 */
	public record Part(String modern, List<String> legacy, boolean own) {

		/** Alle Pfade, unter denen das Bild in einem Pack liegen kann. Modern zuerst. */
		public List<Candidate> candidates() {
			List<Candidate> out = new ArrayList<>(1 + legacy.size());
			out.add(new Candidate(file(modern), false));
			for (String l : legacy) {
				out.add(new Candidate(file(l), true));
			}
			return out;
		}

		/** Pfad im Pack: Bilder unter textures/ als .png, Sounds als .ogg. */
		public static String file(String path) {
			return path.startsWith(SOUNDS) ? ASSETS + path + ".ogg" : TEXTURES + path + ".png";
		}
	}

	/** Ein moeglicher Pfad - und ob er aus dem alten 1.8-Aufbau stammt. */
	public record Candidate(String path, boolean legacy) {}

	/**
	 * Ein Quader des Blockmodells, Koordinaten 0 bis 16 wie in Minecrafts
	 * Modelldateien. Nur die drei Seiten, die in der Vorschau sichtbar sind.
	 */
	public record Box(float x1, float y1, float z1, float x2, float y2, float z2,
	                  Face up, Face north, Face east) {}

	/**
	 * Eine Seite: welcher Teil, welcher Ausschnitt des Bildes (0 bis 16,
	 * unabhaengig von der Aufloesung). null in Box heisst: Diese Seite hat
	 * das Modell nicht. turn: Ausschnitt in Vierteldrehungen gedreht (0 bis
	 * 3) - Bett-Seiten liegen im Bild quer bzw. auf dem Kopf.
	 * tint: "tintindex" aus dem Modell, -1 ohne. Gras und Blaetter sind im
	 * Bild grau und werden erst beim Zeichnen eingefaerbt.
	 */
	public record Face(int part, float u1, float v1, float u2, float v2, int turn, int tint) {

		public Face(int part, float u1, float v1, float u2, float v2, int turn) {
			this(part, u1, v1, u2, v2, turn, -1);
		}

		public Face(int part, float u1, float v1, float u2, float v2) {
			this(part, u1, v1, u2, v2, 0, -1);
		}
	}

	/** Flaches Item mit einem Bild. */
	public static StudioItem flat(String id, String group, String modern, List<String> legacy) {
		return new StudioItem(id, group, List.of(new Part(modern, legacy, true)), null, null);
	}

	/** Ein Block mit 3D-Vorschau. */
	public boolean cube() {
		return boxes != null;
	}

	/** Ein Sound statt eines Bildes. */
	public boolean sound() {
		return parts.get(0).modern().startsWith(SOUNDS);
	}

	/** Hauptbild - das erste. */
	public String modern() {
		return parts.get(0).modern();
	}

	/** Hat dieser Eintrag geliehene Bilder? Dann braucht das neue Pack eigene Modelle. */
	public boolean borrows() {
		for (Part p : parts) {
			if (!p.own()) {
				return true;
			}
		}
		return false;
	}
}
