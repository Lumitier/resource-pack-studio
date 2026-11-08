package com.lumitier.resourcepackstudio.client;

import com.lumitier.lumilib.api.LumiLibApi;
import com.lumitier.lumilib.api.LumiMod;
import com.lumitier.lumilib.api.SettingsPage;
import com.lumitier.resourcepackstudio.ResourcePackStudio;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;

/**
 * Eintrag im LumiLib-Hub.
 *
 * Das Studio selbst ist ein eigener Bildschirm - zu gross fuer eine
 * Einstellungsseite. Die Seite im Hub hat deshalb nur Knoepfe: Studio
 * oeffnen, neu suchen, Ordner oeffnen. Echte Einstellungen kommen in
 * Phase 4.
 */
public final class LumiLibCompat {

	private LumiLibCompat() {}

	public static void register() {
		ModContainer container = FabricLoader.getInstance()
			.getModContainer(ResourcePackStudio.MOD_ID).orElse(null);
		String version = container == null ? ""
			: container.getMetadata().getVersion().getFriendlyString();
		String name = container == null ? "Resource Pack Studio"
			: container.getMetadata().getName();
		String summary = container == null ? ""
			: container.getMetadata().getDescription();

		LumiLibApi.register(new LumiMod(
			ResourcePackStudio.MOD_ID,
			Text.literal(name),
			version,
			Text.literal(summary),
			null,
			StudioScreen::new,
			LumiLibCompat::settings));
	}

	private static SettingsPage settings() {
		return SettingsPage.builder(ResourcePackStudio.MOD_ID)
			.section("studio")
			.action("open", LumiLibCompat::open)
			.action("rescan", StudioIndex::rescan)
			.action("open_folder", LumiLibCompat::openFolder)
			.build();
	}

	private static void open() {
		MinecraftClient client = MinecraftClient.getInstance();
		client.setScreen(new StudioScreen(client.currentScreen));
	}

	/** Eigener Pack-Ordner - dort hinein kann man Packs legen, die nur das Studio sieht. */
	private static void openFolder() {
		try {
			Files.createDirectories(ResourcePackStudio.packsDir());
			Util.getOperatingSystem().open(ResourcePackStudio.packsDir().toFile());
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not open {}",
				ResourcePackStudio.packsDir(), e);
		}
	}
}
