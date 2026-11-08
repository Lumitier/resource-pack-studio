package com.lumitier.resourcepackstudio.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Item in der Sidebar - und wo seine Textur in einem Pack liegen kann.
 *
 * ZWEI ORTE, NICHT EINER: Seit 1.13 heisst der Ordner textures/item und
 * viele Texturen haben neue Namen. Davor hiess er textures/items, und ein
 * Grossteil der PvP-Packs stammt aus 1.8. Ohne die alten Pfade faende die
 * Suche die Haelfte der Packs nicht.
 *
 * @param id       Kennung ohne Namensraum, wie in Minecraft (stone_sword)
 * @param group    Gruppe in der Sidebar
 * @param modern   Textur ab 1.13, relativ zu textures/ und ohne .png -
 *                 meist "item/stone_sword", bei manchen Items "block/..."
 *                 (Setzlinge, Blumen: flach im Inventar, Bild im Block-Ordner)
 * @param legacy   Texturen vor 1.13, ebenso relativ ("items/wood_sword") -
 *                 leer, wenn es das Item da nicht gab
 */
public record StudioItem(String id, String group, String modern, List<String> legacy) {

	public static final String TEXTURES = "assets/minecraft/textures/";

	/** Alle Pfade, unter denen die Textur in einem Pack liegen kann. Modern zuerst. */
	public List<Candidate> candidates() {
		List<Candidate> out = new ArrayList<>(1 + legacy.size());
		out.add(new Candidate(TEXTURES + modern + ".png", false));
		for (String l : legacy) {
			out.add(new Candidate(TEXTURES + l + ".png", true));
		}
		return out;
	}

	/** Ein moeglicher Pfad - und ob er aus dem alten 1.8-Aufbau stammt. */
	public record Candidate(String path, boolean legacy) {}
}
