package com.lumitier.resourcepackstudio.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D-Vorschau fuer Dinge, deren Bild ein "Faltbogen" ist: Kisten, Betten,
 * Shulkerkisten, Banner, Schild, Koepfe und getragene Ruestung.
 *
 * Die Bilder sind wie bei Minecrafts Wesen-Modellen aufgeteilt: Fuer einen
 * Quader mit Breite w, Hoehe h und Tiefe d an der Bildstelle (U, V) liegt
 *   oben       bei (U+d,     V)     w x d
 *   vorne      bei (U+d,     V+d)   w x h
 *   Seite      bei (U+d+w,   V+d)   d x h
 * Das ist seit Jahren dasselbe Schema - auch 1.8-Packs folgen ihm.
 *
 * Die Masse sind nach Minecrafts Modellen abgeschrieben, fuer die Vorschau
 * in den Wuerfel 0..16 eingepasst. Nur eine Vorschau - Minecraft zeichnet
 * im Spiel selbst.
 */
public final class EntityModels {

	private EntityModels() {}

	/** Quader mit Seiten aus dem Faltbogen (Bildgroesse tw x th in Pixeln). */
	private static StudioItem.Box box(float x1, float y1, float z1, float x2, float y2, float z2,
	                                  int part, float u, float v, float w, float h, float d,
	                                  float tw, float th) {
		float su = 16.0F / tw;
		float sv = 16.0F / th;
		StudioItem.Face up = new StudioItem.Face(part, (u + d) * su, v * sv, (u + d + w) * su, (v + d) * sv);
		StudioItem.Face front = new StudioItem.Face(part, (u + d) * su, (v + d) * sv,
			(u + d + w) * su, (v + d + h) * sv);
		StudioItem.Face side = new StudioItem.Face(part, (u + d + w) * su, (v + d) * sv,
			(u + d + w + d) * su, (v + d + h) * sv);
		return new StudioItem.Box(x1, y1, z1, x2, y2, z2, up, front, side);
	}

	/**
	 * Kiste (64x64): Unterteil, Deckel, Schloss vorne.
	 *
	 * DECKEL OBEN = seine UNTERSEITE im Bild: Minecraft legt das Kisten-
	 * Modell auf den Kopf und dreht es beim Zeichnen zurueck. Die Stelle,
	 * die nach dem Schema "oben" waere, ist die Innenseite des Deckels -
	 * deshalb sah die Kiste offen aus.
	 */
	public static List<StudioItem.Box> chest() {
		float s = 16.0F / 64;
		List<StudioItem.Box> l = new ArrayList<>();
		l.add(box(1, 0, 1, 15, 10, 15, 0, 0, 19, 14, 10, 14, 64, 64));
		StudioItem.Box lid = box(1, 10, 1, 15, 15, 15, 0, 0, 0, 14, 5, 14, 64, 64);
		StudioItem.Face lidTop = new StudioItem.Face(0, 28 * s, 0, 42 * s, 14 * s);
		l.add(new StudioItem.Box(lid.x1(), lid.y1(), lid.z1(), lid.x2(), lid.y2(), lid.z2(),
			lidTop, lid.north(), lid.east()));
		l.add(box(7, 7, 0, 9, 11, 1, 0, 0, 0, 2, 4, 1, 64, 64));
		return l;
	}

	/** Shulkerkiste (64x64): Unterteil und Deckel. */
	public static List<StudioItem.Box> shulker() {
		List<StudioItem.Box> l = new ArrayList<>();
		l.add(box(0, 0, 0, 16, 8, 16, 0, 0, 28, 16, 8, 16, 64, 64));
		l.add(box(0, 4, 0, 16, 16, 16, 0, 0, 0, 16, 12, 16, 64, 64));
		return l;
	}

	/**
	 * Ganzes Bett (64x64, zwei Bloecke lang), auf die halbe Groesse
	 * verkleinert: Kopfteil mit Kissen links hinten, Fussteil rechts vorne,
	 * die lange Seite zum Betrachter, vier Beine. So aehnlich zeigt es auch
	 * Minecraft im Inventar.
	 *
	 * Bild: Kopfteil bei (0,0), Fussteil bei (0,22), je 16x16x6 - im Spiel
	 * liegend, deshalb ist die "Vorderseite" des Quaders die Liegeflaeche.
	 * Das Bett liegt hier laengs der x-Achse, deshalb sind Liegeflaeche und
	 * lange Seite gedreht (turn). Ausserhalb des Spiels nachgerechnet. Beine 3x3x3 bei (50,0), (50,6), (50,12),
	 * (50,18).
	 */
	public static List<StudioItem.Box> bed() {
		float s = 16.0F / 64;
		List<StudioItem.Box> l = new ArrayList<>();
		float legH = 1.5F;
		float top = legH + 3;
		// Beine (hinten zuerst)
		l.add(box(0, 0, 10.5F, 1.5F, legH, 12, 0, 50, 0, 3, 3, 3, 64, 64));
		l.add(box(14.5F, 0, 10.5F, 16, legH, 12, 0, 50, 6, 3, 3, 3, 64, 64));
		l.add(box(0, 0, 4, 1.5F, legH, 5.5F, 0, 50, 12, 3, 3, 3, 64, 64));
		l.add(box(14.5F, 0, 4, 16, legH, 5.5F, 0, 50, 18, 3, 3, 3, 64, 64));
		// Kopfteil: oben Matratze mit Kissen, vorne die lange Seite.
		// Lange Seite aus dem LINKEN Seitenstreifen (0,6): Der rechte liegt
		// im Bild gespiegelt - Holzrahmen waere oben statt unten.
		l.add(new StudioItem.Box(0, legH, 4, 8, top, 12,
			new StudioItem.Face(0, 6 * s, 6 * s, 22 * s, 22 * s, 1),
			new StudioItem.Face(0, 0, 6 * s, 6 * s, 22 * s, 1),
			null));
		// Fussteil: oben Matratze, vorne die lange Seite, rechts das
		// Fussende (im Bild auf dem Kopf, deshalb halbe Drehung).
		l.add(new StudioItem.Box(8, legH, 4, 16, top, 12,
			new StudioItem.Face(0, 6 * s, 28 * s, 22 * s, 44 * s, 1),
			new StudioItem.Face(0, 0, 28 * s, 6 * s, 44 * s, 1),
			new StudioItem.Face(0, 22 * s, 22 * s, 38 * s, 28 * s, 2)));
		return l;
	}

	/** Banner (64x64): das Tuch, duenn, mittig. */
	public static List<StudioItem.Box> banner() {
		return List.of(box(4, 0, 7.5F, 12, 16, 8.5F, 0, 0, 0, 20, 40, 1, 64, 64));
	}

	/** Schild (64x64): die Platte. */
	public static List<StudioItem.Box> shield() {
		return List.of(box(3.6F, 0, 7.5F, 12.4F, 16, 8.5F, 0, 0, 0, 12, 22, 1, 64, 64));
	}

	/** Kopf (8x8x8 bei 0,0): tw x th ist die Groesse des Vanilla-Bildes. */
	public static List<StudioItem.Box> head(int tw, int th) {
		return List.of(box(2, 0, 2, 14, 12, 14, 0, 0, 0, 8, 8, 8, tw, th));
	}

	// ------------------------------------------------------------------
	// Getragene Ruestung an einer Figur
	// ------------------------------------------------------------------

	public enum Piece { HELMET, CHESTPLATE, LEGGINGS, BOOTS }

	/** Masse der Figur in Pixeln (Minecraft), eingepasst in 0..16. */
	private static final float S = 0.45F;

	private static StudioItem.Box worn(float x1, float y1, float z1, float x2, float y2, float z2, float grow,
	                                   int part, float u, float v, float w, float h, float d) {
		return box(px(x1 - grow), py(y1 - grow), pz(z1 - grow), px(x2 + grow), py(y2 + grow), pz(z2 + grow),
			part, u, v, w, h, d, 64, 32);
	}

	private static float px(float x) {
		return 8 + (x - 4) * S;
	}

	private static float py(float y) {
		return (y + 1) * S;
	}

	private static float pz(float z) {
		return 8 + (z - 2) * S;
	}

	/**
	 * Ein Ruestungsteil an der Figur. part ist der Teil mit dem passenden
	 * Bild (Lage 1 fuer Helm, Brust, Schuhe; Lage 2 fuer die Hose).
	 */
	public static List<StudioItem.Box> armor(Piece piece, int part) {
		List<StudioItem.Box> l = new ArrayList<>();
		switch (piece) {
			case HELMET -> l.add(worn(0, 24, -2, 8, 32, 6, 1, part, 0, 0, 8, 8, 8));
			case CHESTPLATE -> {
				l.add(worn(-4, 12, 0, 0, 24, 4, 1, part, 40, 16, 4, 12, 4));
				l.add(worn(0, 12, 0, 8, 24, 4, 1, part, 16, 16, 8, 12, 4));
				l.add(worn(8, 12, 0, 12, 24, 4, 1, part, 40, 16, 4, 12, 4));
			}
			case LEGGINGS -> {
				l.add(worn(0, 0, 0, 4, 12, 4, 0.5F, part, 0, 16, 4, 12, 4));
				l.add(worn(4, 0, 0, 8, 12, 4, 0.5F, part, 0, 16, 4, 12, 4));
				l.add(worn(0, 12, 0, 8, 24, 4, 0.5F, part, 16, 16, 8, 12, 4));
			}
			case BOOTS -> {
				l.add(worn(0, 0, 0, 4, 12, 4, 1, part, 0, 16, 4, 12, 4));
				l.add(worn(4, 0, 0, 8, 12, 4, 1, part, 0, 16, 4, 12, 4));
			}
		}
		// Hinten zuerst - wie bei den Bloecken.
		l.sort((a, b) -> Float.compare(
			(a.x1() + a.x2()) + (a.y1() + a.y2()) - (a.z1() + a.z2()),
			(b.x1() + b.x2()) + (b.y1() + b.y2()) - (b.z1() + b.z2())));
		return l;
	}

	/**
	 * Wie armor, aber jeder Quader zweimal: erst Teil 0 (Grundtextur), direkt
	 * danach Teil 1 (Overlay) mit derselben Form. Fuer Leder - so liegt das
	 * Overlay immer auf SEINEM Quader und nie auf einem davor.
	 */
	public static List<StudioItem.Box> armorWithOverlay(Piece piece) {
		List<StudioItem.Box> base = armor(piece, 0);
		List<StudioItem.Box> over = armor(piece, 1);
		List<StudioItem.Box> l = new ArrayList<>(base.size() * 2);
		for (int i = 0; i < base.size(); i++) {
			l.add(base.get(i));
			l.add(over.get(i));
		}
		return l;
	}

	/**
	 * Bereiche eines Teils in Lage 1 (64x32-Raster): x, y, Breite, Hoehe.
	 * Helm, Brust und Schuhe teilen sich EIN Bild - beim Erstellen wird es
	 * aus diesen Bereichen der gewaehlten Packs zusammengesetzt.
	 */
	public static int[][] regions(Piece piece) {
		return switch (piece) {
			case HELMET -> new int[][] {{0, 0, 64, 16}};
			case CHESTPLATE -> new int[][] {{16, 16, 24, 16}, {40, 16, 16, 16}};
			case BOOTS -> new int[][] {{0, 16, 16, 16}};
			case LEGGINGS -> new int[][] {{0, 0, 64, 32}};
		};
	}
}
