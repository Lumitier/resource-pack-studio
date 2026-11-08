package com.lumitier.resourcepackstudio.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Die PvP-Items oben in der Sidebar - von Hand gepflegt, mit den alten
 * 1.8-Namen. Alle anderen Items ermittelt ItemDiscovery im Spiel.
 *
 * DIE ALTEN NAMEN SIND NACHGESCHLAGEN, NICHT GERATEN: Mit 1.13 hat Mojang
 * viele Texturen umbenannt ("The Flattening"). Wo der alte Name anders
 * lautet, steht er hier ausdruecklich; sonst gilt der neue Name auch im
 * alten Ordner textures/items. Items, die es vor 1.13 nicht gab
 * (Netherite, Kupfer, Armbrust, Streitkolben ...), haben keinen alten Pfad.
 *
 * Die modernen Namen sind gegen die Texturen von Minecraft 1.21.11
 * abgeglichen.
 */
public final class StudioItems {

	private StudioItems() {}

	public static final String SWORDS = "swords";
	public static final String AXES = "axes";
	public static final String PICKAXES = "pickaxes";
	public static final String ARMOR = "armor";
	public static final String COMBAT = "combat";
	public static final String UTILITY = "utility";
	/** Alle uebrigen Items - werden im Spiel ermittelt, siehe ItemDiscovery. */
	public static final String OTHER = "other";

	private static final List<StudioItem> ALL = build();

	public static List<StudioItem> all() {
		return ALL;
	}

	private static List<StudioItem> build() {
		List<StudioItem> l = new ArrayList<>();

		// Werkzeuge: Holz und Gold hiessen bis 1.12 "wood_" und "gold_".
		for (String tool : new String[] {"sword", "axe", "pickaxe"}) {
			String group = tool.equals("sword") ? SWORDS : tool.equals("axe") ? AXES : PICKAXES;
			l.add(item("wooden_" + tool, group, "wood_" + tool));
			l.add(item("stone_" + tool, group, "stone_" + tool));
			l.add(item("copper_" + tool, group));
			l.add(item("iron_" + tool, group, "iron_" + tool));
			l.add(item("golden_" + tool, group, "gold_" + tool));
			l.add(item("diamond_" + tool, group, "diamond_" + tool));
			l.add(item("netherite_" + tool, group));
		}

		// Ruestung: Gold hiess "gold_", Kette hiess schon "chainmail_".
		String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
		for (String p : pieces) {
			l.add(item("leather_" + p, ARMOR, "leather_" + p));
			l.add(item("chainmail_" + p, ARMOR, "chainmail_" + p));
			l.add(item("copper_" + p, ARMOR));
			l.add(item("iron_" + p, ARMOR, "iron_" + p));
			l.add(item("golden_" + p, ARMOR, "gold_" + p));
			l.add(item("diamond_" + p, ARMOR, "diamond_" + p));
			l.add(item("netherite_" + p, ARMOR));
		}

		// Kampf
		l.add(item("ender_pearl", COMBAT, "ender_pearl"));
		l.add(item("golden_apple", COMBAT, "apple_golden"));
		l.add(item("bow", COMBAT, "bow_standby"));
		l.add(item("arrow", COMBAT, "arrow"));
		l.add(new StudioItem("crossbow", COMBAT, "item/crossbow_standby", List.of()));
		l.add(item("potion", COMBAT, "potion_bottle_drinkable"));
		l.add(item("splash_potion", COMBAT, "potion_bottle_splash"));
		l.add(item("lingering_potion", COMBAT));
		// 1.11/1.12 hiess die Textur "totem".
		l.add(item("totem_of_undying", COMBAT, "totem"));
		l.add(item("fishing_rod", COMBAT, "fishing_rod_uncast"));
		l.add(item("snowball", COMBAT, "snowball"));
		l.add(item("egg", COMBAT, "egg"));
		l.add(item("mace", COMBAT));
		l.add(item("wind_charge", COMBAT));

		// Nuetzliches
		l.add(item("cooked_beef", UTILITY, "beef_cooked"));
		l.add(item("golden_carrot", UTILITY, "carrot_golden"));
		l.add(item("water_bucket", UTILITY, "bucket_water"));
		l.add(item("lava_bucket", UTILITY, "bucket_lava"));
		l.add(item("bucket", UTILITY, "bucket_empty"));
		l.add(item("experience_bottle", UTILITY, "experience_bottle"));
		l.add(item("flint_and_steel", UTILITY, "flint_and_steel"));
		l.add(item("shears", UTILITY, "shears"));

		return Collections.unmodifiableList(l);
	}

	private static StudioItem item(String id, String group, String... legacy) {
		List<String> old = new ArrayList<>(legacy.length);
		for (String l : legacy) {
			old.add("items/" + l);
		}
		return new StudioItem(id, group, "item/" + id, List.copyOf(old));
	}
}
