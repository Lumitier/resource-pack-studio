package com.lumitier.resourcepackstudio;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class ResourcePackStudio {

	public static final String MOD_ID = "resourcepackstudio";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private ResourcePackStudio() {}

	/** Ordner der Mod im Spielverzeichnis - fuer Bericht und eigene Packs. */
	public static Path home() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID);
	}

	/**
	 * Eigener Pack-Ordner: Packs, die man durchsuchen, aber nicht in
	 * Minecrafts Pack-Liste haben will.
	 */
	public static Path packsDir() {
		return home().resolve("packs");
	}

	/** Minecrafts eigener Pack-Ordner. */
	public static Path resourcePacksDir() {
		return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
	}
}
