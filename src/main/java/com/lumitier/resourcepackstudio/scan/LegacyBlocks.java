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
	/** Items mit anderem 1.8-Namen. VOR dem static-Block - sonst waere sie dort noch null. */
	private static final Map<String, String> ITEMS = new HashMap<>();

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

		// Setzlinge und Blumen
		NAMES.put("oak_sapling", "sapling_oak");
		NAMES.put("spruce_sapling", "sapling_spruce");
		NAMES.put("birch_sapling", "sapling_birch");
		NAMES.put("jungle_sapling", "sapling_jungle");
		NAMES.put("acacia_sapling", "sapling_acacia");
		NAMES.put("dark_oak_sapling", "sapling_roofed_oak");
		NAMES.put("dandelion", "flower_dandelion");
		NAMES.put("poppy", "flower_rose");
		NAMES.put("blue_orchid", "flower_blue_orchid");
		NAMES.put("allium", "flower_allium");
		NAMES.put("azure_bluet", "flower_houstonia");
		NAMES.put("red_tulip", "flower_tulip_red");
		NAMES.put("orange_tulip", "flower_tulip_orange");
		NAMES.put("white_tulip", "flower_tulip_white");
		NAMES.put("pink_tulip", "flower_tulip_pink");
		NAMES.put("oxeye_daisy", "flower_oxeye_daisy");
		NAMES.put("short_grass", "tallgrass");
		NAMES.put("dead_bush", "deadbush");
		NAMES.put("sugar_cane", "reeds");
		NAMES.put("lily_pad", "waterlily");
		NAMES.put("cobweb", "web");
		NAMES.put("torch", "torch_on");
		NAMES.put("redstone_torch", "redstone_torch_on");
		NAMES.put("rail", "rail_normal");
		NAMES.put("red_mushroom", "mushroom_red");
		NAMES.put("brown_mushroom", "mushroom_brown");

		// Items, deren Bild seit 1.13 anders heisst
		String[][] items = {
			{"porkchop", "porkchop_raw"}, {"cooked_porkchop", "porkchop_cooked"},
			{"beef", "beef_raw"}, {"chicken", "chicken_raw"}, {"cooked_chicken", "chicken_cooked"},
			{"mutton", "mutton_raw"}, {"cooked_mutton", "mutton_cooked"},
			{"rabbit", "rabbit_raw"}, {"cooked_rabbit", "rabbit_cooked"},
			{"cod", "fish_cod_raw"}, {"cooked_cod", "fish_cod_cooked"},
			{"salmon", "fish_salmon_raw"}, {"cooked_salmon", "fish_salmon_cooked"},
			{"glistering_melon_slice", "melon_speckled"}, {"melon_slice", "melon"},
			{"baked_potato", "potato_baked"}, {"poisonous_potato", "potato_poisonous"},
			{"book", "book_normal"}, {"writable_book", "book_writable"},
			{"written_book", "book_written"}, {"enchanted_book", "book_enchanted"},
			{"firework_rocket", "fireworks"}, {"fire_charge", "fireball"},
			{"redstone", "redstone_dust"}, {"slime_ball", "slimeball"},
			{"fermented_spider_eye", "spider_eye_fermented"},
			{"minecart", "minecart_normal"}, {"oak_boat", "boat"},
			{"wheat_seeds", "seeds_wheat"}, {"pumpkin_seeds", "seeds_pumpkin"},
			{"melon_seeds", "seeds_melon"},
			{"ink_sac", "dye_powder_black"}, {"bone_meal", "dye_powder_white"},
			{"lapis_lazuli", "dye_powder_blue"}, {"cocoa_beans", "dye_powder_brown"},
			{"oak_door", "door_wood"}, {"iron_door", "door_iron"},
			{"oak_sign", "sign"}, {"glass_bottle", "potion_bottle_empty"},
			{"gold_nugget", "gold_nugget"}, {"clock", "clock"}, {"compass", "compass"}};
		for (String[] it : items) {
			ITEMS.put(it[0], it[1]);
		}
	}

	/** Alter Pfad relativ zu textures/ fuer einen neuen ("block/oak_planks" -> "blocks/planks_oak"). */
	public static String legacy(String modern) {
		if (!modern.startsWith("block/")) {
			if (!modern.startsWith("item/")) {
				return null;
			}
			String name = modern.substring(5);
			return "items/" + ITEMS.getOrDefault(name, name);
		}
		String name = modern.substring(6);
		return "blocks/" + NAMES.getOrDefault(name, name);
	}
}
