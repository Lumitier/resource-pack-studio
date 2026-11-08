package com.lumitier.resourcepackstudio.client;

import com.lumitier.resourcepackstudio.ResourcePackStudio;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Das Bild fuer das neue Pack (pack.png).
 *
 * AUSWAHL UEBER DAS DATEIFENSTER DES SYSTEMS: Minecraft bringt dafuer
 * TinyFileDialogs aus LWJGL mit (es zeigt damit selbst Fehlermeldungen).
 * Das Fenster wird in einem eigenen Thread geoeffnet - sonst stuende das
 * Spiel still, solange es offen ist.
 *
 * ALTERNATIV: eine PNG auf das Minecraft-Fenster ziehen (StudioScreen).
 *
 * PRUEFUNG wie bei Minecraft selbst: Die Pack-Auswahl zeigt nur QUADRATISCHE
 * Bilder an. Ein anderes Format waere still kaputt - deshalb wird es gleich
 * hier abgelehnt.
 *
 * Alles ausser dem Dateifenster laeuft auf dem Render-Thread.
 */
final class PackIcon {

	private static final Identifier TEX = Identifier.of(ResourcePackStudio.MOD_ID, "pack_icon_preview");
	private static final int MAX_SIZE = 1024;

	private Path path;
	private boolean registered;
	private volatile boolean choosing;

	Path path() {
		return path;
	}

	/** Vorschau, oder null wenn keins gewaehlt ist. */
	Identifier texture() {
		return registered ? TEX : null;
	}

	boolean choosing() {
		return choosing;
	}

	/**
	 * Dateifenster oeffnen. result bekommt null bei Erfolg oder Abbruch,
	 * sonst den Fehlertext - auf dem Render-Thread.
	 */
	void choose(Consumer<String> result) {
		if (choosing) {
			return;
		}
		choosing = true;
		Thread t = new Thread(() -> {
			String picked = null;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(1);
				filters.put(stack.UTF8("*.png"));
				filters.flip();
				picked = TinyFileDialogs.tinyfd_openFileDialog("Pack icon", null, filters, "PNG image", false);
			} catch (Throwable e) {
				ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not open the file dialog.", e);
			}
			String p = picked;
			MinecraftClient.getInstance().execute(() -> {
				choosing = false;
				result.accept(p == null ? null : set(Path.of(p)));
			});
		}, "Resource Pack Studio - Icon");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Bild uebernehmen. @return null bei Erfolg, sonst ein Fehler-Schluessel
	 * (Uebersetzung). Render-Thread.
	 */
	String set(Path p) {
		if (p == null || !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
			|| !Files.isRegularFile(p)) {
			return "resourcepackstudio.icon.not_png";
		}
		NativeImage img;
		try (InputStream in = Files.newInputStream(p)) {
			img = NativeImage.read(in);
		} catch (Exception e) {
			return "resourcepackstudio.icon.not_png";
		}
		if (img.getWidth() != img.getHeight()) {
			img.close();
			return "resourcepackstudio.icon.not_square";
		}
		if (img.getWidth() > MAX_SIZE) {
			img.close();
			return "resourcepackstudio.icon.too_big";
		}
		clear();
		MinecraftClient.getInstance().getTextureManager()
			.registerTexture(TEX, new NativeImageBackedTexture(() -> "Resource Pack Studio icon", img));
		this.registered = true;
		this.path = p;
		return null;
	}

	void clear() {
		if (registered) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(TEX);
		}
		registered = false;
		path = null;
	}
}
