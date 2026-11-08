package com.lumitier.resourcepackstudio.client;

import com.lumitier.resourcepackstudio.ResourcePackStudio;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import java.nio.file.Files;

/**
 * Einstieg.
 *
 * Nach dem Start im Hintergrund alle Packs durchsuchen (StudioIndex) und das
 * Studio im LumiLib-Hub anmelden. Der Bericht scan-report.txt wird
 * weiterhin geschrieben - praktisch, wenn ein Pack fehlt.
 */
public final class ResourcePackStudioClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		try {
			Files.createDirectories(ResourcePackStudio.packsDir());
		} catch (Exception e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not create {}",
				ResourcePackStudio.packsDir(), e);
		}

		// ERST WENN DAS SPIEL STEHT: Die Suche liest Minecrafts eigene
		// Item-Dateien, um alle Items zu finden. Beim Laden der Mods ist
		// das Standard-Pack dafuer noch nicht sicher bereit.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> StudioIndex.rescan());

		try {
			LumiLibCompat.register();
		} catch (Throwable e) {
			ResourcePackStudio.LOGGER.error("[Resource Pack Studio] Could not register with LumiLib.", e);
		}
	}
}
