package com.lumitier.resourcepackstudio.scan;

import java.util.HashMap;
import java.util.Map;

/**
 * Alte 1.8-Namen von Block-Texturen.
 *
 * Mit 1.13 wurden viele Block-Texturen umbenannt (planks_oak -> oak_planks,
 * wool_colored_white -> white_wool). Hier stehen die, bei denen sich der
 * Name geaendert hat und die in PvP-Packs typischerweise vorkommen. Alle
 * anderen werden unter demselben Namen im alten Ordner blocks/ gesucht.
 *
 * Quelle: die Dateinamen in textures/blocks von Minecraft 1.8.9.
 */
public final class LegacyBlocks {

	private LegacyBlocks() {}

	private static final Map<String, String> NAMES = new HashMap<>();

	private static final String[] COLORS = {
		"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
		"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

	static {
		for (String c : COLORS) {
			// 1.8 nannte Hellgrau "silver".
			String old = c.equals("light_gray") ? "silver" : c;
			NAMES.put(c + "_wool", "wool_colored_" + old);
			NAMES.put(c + "_stained_glass", "glass_" + old);
			NAMES.put(c + "_terracotta", "hardened_clay_stained_" + old);
		}
		NAMES.put("terracotta", "hardened_clay");

		String[][] woods = {
			{"oak", "oak"}, {"spruce", "spruce"}, {"birch", "birch"},
			{"jungle", "jungle"}, {"acacia", "acacia"}, {"dark_oak", "big_oak"}};
		for (String[] w : woods) {
			NAMES.put(w[0] + "_planks", "planks_" + w[1]);
			NAMES.put(w[0] + "_log", "log_" + w[1]);
			NAMES.put(w[0] + "_log_top", "log_" + w[1] + "_top");
			NAMES.put(w[0] + "_leaves", "leaves_" + w[1]);
		}

		NAMES.put("granite", "stone_granite");
		NAMES.put("polished_granite", "stone_granite_smooth");
		NAMES.put("diorite", "stone_diorite");
		NAMES.put("polished_diorite", "stone_diorite_smooth");
		NAMES.put("andesite", "stone_andesite");
		NAMES.put("polished_andesite", "stone_andesite_smooth");

		NAMES.put("stone_bricks", "stonebrick");
		NAMES.put("mossy_stone_bricks", "stonebrick_mossy");
		NAMES.put("cracked_stone_bricks", "stonebrick_cracked");
		NAMES.put("chiseled_stone_bricks", "stonebrick_carved");
		NAMES.put("mossy_cobblestone", "cobblestone_mossy");

		NAMES.put("sandstone", "sandstone_normal");
		NAMES.put("chiseled_sandstone", "sandstone_carved");
		NAMES.put("cut_sandstone", "sandstone_smooth");
		NAMES.put("red_sandstone", "red_sandstone_normal");
		NAMES.put("chiseled_red_sandstone", "red_sandstone_carved");
		NAMES.put("cut_red_sandstone", "red_sandstone_smooth");

		NAMES.put("grass_block_side", "grass_side");
		NAMES.put("grass_block_top", "grass_top");
		NAMES.put("grass_block_side_overlay", "grass_side_overlay");
		NAMES.put("podzol_side", "dirt_podzol_side");
		NAMES.put("podzol_top", "dirt_podzol_top");

		NAMES.put("snow", "snow");
		NAMES.put("packed_ice", "ice_packed");
		NAMES.put("bricks", "brick");
		NAMES.put("nether_bricks", "nether_brick");
		NAMES.put("furnace_front", "furnace_front_off");
		NAMES.put("wet_sponge", "sponge_wet");
		NAMES.put("slime_block", "slime");
		NAMES.put("prismarine", "prismarine_rough");
		NAMES.put("dark_prismarine", "prismarine_dark");
		NAMES.put("jack_o_lantern", "pumpkin_face_on");
		NAMES.put("carved_pumpkin", "pumpkin_face_off");
		NAMES.put("redstone_lamp", "redstone_lamp_off");
		NAMES.put("note_block", "noteblock");
		NAMES.put("magma_block", "magma");
		NAMES.put("chiseled_quartz_block", "quartz_block_chiseled");
		NAMES.put("chiseled_quartz_block_top", "quartz_block_chiseled_top");
		NAMES.put("quartz_pillar", "quartz_block_lines");
		NAMES.put("quartz_pillar_top", "quartz_block_lines_top");
	}

	/** Alter Pfad relativ zu textures/ fuer einen neuen ("block/oak_planks" -> "blocks/planks_oak"). */
	public static String legacy(String modern) {
		if (!modern.startsWith("block/")) {
			return modern.startsWith("item/") ? "items/" + modern.substring(5) : null;
		}
		String name = modern.substring(6);
		return "blocks/" + NAMES.getOrDefault(name, name);
	}
}
