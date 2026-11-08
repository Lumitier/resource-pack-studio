package com.lumitier.resourcepackstudio.client;

import com.lumitier.resourcepackstudio.ResourcePackStudio;
import com.lumitier.resourcepackstudio.scan.PackScanner;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Spielt einen Sound zum Vergleichen ab - aus einem Pack oder aus Vanilla.
 *
 * WARUM NICHT UEBER MINECRAFTS SOUND-SYSTEM: Das spielt nur, was in den
 * AKTIVEN Packs steht. Hier soll aber genau die Datei aus einem bestimmten,
 * nicht aktiven Pack zu hoeren sein.
 *
 * WIE: Entpackt wird mit Minecrafts eigenem OGG-Leser (OggAudioStream) zu
 * rohem Ton, abgespielt ueber Javas Tonausgabe. Ein neuer Sound beendet den
 * vorigen.
 *
 * STILLE DRUMHERUM: Solange die Vorschau laeuft, sind alle Minecraft-Sounds
 * pausiert (Musik, Regen, Mobs ...). Danach laufen sie weiter.
 *
 * LAUTSTAERKE: eigener Regler im Studio, 0 bis 200 %, unabhaengig von
 * Minecrafts Einstellungen. Ueber 100 % ist nur so viel moeglich, wie die
 * Tonausgabe zulaesst (meist etwa doppelt so laut).
 *
 * Laeuft in einem eigenen Thread - nichts davon blockiert das Spiel.
 */
final class SoundPreview {

	private SoundPreview() {}

	private static volatile SourceDataLine current;
	private static volatile int ticket;

	static final float MAX_VOLUME = 2.0F;
	private static volatile float volume = 1.0F;

	/** Sind Minecrafts Sounds gerade von uns pausiert? Nur Render-Thread. */
	private static boolean muted;

	static float volume() {
		return volume;
	}

	static void setVolume(float v) {
		volume = v;
		SourceDataLine l = current;
		if (l != null) {
			setGain(l, v);
		}
	}

	/** Vanilla-Fassung eines Sounds abspielen. */
	static void playVanilla(StudioItem item) {
		play(() -> {
			InputSupplier<InputStream> in = MinecraftClient.getInstance().getDefaultResourcePack()
				.open(ResourceType.CLIENT_RESOURCES, Identifier.ofVanilla(item.modern() + ".ogg"));
			if (in == null) {
				return null;
			}
			try (InputStream s = in.get()) {
				return s.readAllBytes();
			}
		});
	}

	/** Fassung aus einem Pack abspielen. */
	static void playVariant(PackScanner.Variant v) {
		String entry = v.entry();
		play(() -> {
			if (v.pack().zip()) {
				try (ZipFile zip = new ZipFile(v.pack().path().toFile())) {
					ZipEntry e = zip.getEntry(entry);
					if (e == null) {
						return null;
					}
					try (InputStream s = zip.getInputStream(e)) {
						return s.readAllBytes();
					}
				}
			}
			return Files.readAllBytes(v.pack().path().resolve(entry));
		});
	}

	/** Laufenden Sound beenden und Minecrafts Sounds wieder laufen lassen (Studio zu). */
	static void stop() {
		halt();
		unmute();
	}

	private static void halt() {
		ticket++;
		SourceDataLine l = current;
		current = null;
		if (l != null) {
			l.stop();
			l.close();
		}
	}

	private static void mute() {
		if (!muted) {
			MinecraftClient.getInstance().getSoundManager().pauseAllExcept();
			muted = true;
		}
	}

	private static void unmute() {
		if (muted) {
			MinecraftClient.getInstance().getSoundManager().resumeAll();
			muted = false;
		}
	}

	private interface Source {
		byte[] read() throws Exception;
	}

	/** Render-Thread. */
	private static void play(Source source) {
		halt();
		mute();
		int my = ticket;
		Thread t = new Thread(() -> {
			try {
				byte[] ogg = source.read();
				if (ogg == null || my != ticket) {
					finished(my);
					return;
				}
				AudioFormat format;
				byte[] pcm;
				try (OggAudioStream stream = new OggAudioStream(new ByteArrayInputStream(ogg))) {
					format = stream.getFormat();
					pcm = littleEndian(stream.readAll(), format);
				}
				if (my != ticket) {
					return;
				}
				SourceDataLine line = AudioSystem.getSourceDataLine(format);
				line.open(format);
				setGain(line, volume);
				current = line;
				line.start();
				line.write(pcm, 0, pcm.length);
				line.drain();
				line.close();
			} catch (Throwable e) {
				ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not play the sound.", e);
			}
			finished(my);
		}, "Resource Pack Studio - Sound");
		t.setDaemon(true);
		t.start();
	}

	/** Fertig gespielt: Minecraft wieder laut - aber nur, wenn kein neuerer Sound laeuft. */
	private static void finished(int my) {
		MinecraftClient.getInstance().execute(() -> {
			if (my == ticket) {
				current = null;
				unmute();
			}
		});
	}

	/**
	 * Minecrafts Puffer hat die Byte-Reihenfolge des Rechners, das Format
	 * sagt "little endian". Auf ueblichen PCs ist beides gleich - umgestellt
	 * wird nur, wenn nicht.
	 */
	private static byte[] littleEndian(ByteBuffer buf, AudioFormat format) {
		byte[] out = new byte[buf.remaining()];
		if (format.getSampleSizeInBits() != 16 || buf.order() == ByteOrder.LITTLE_ENDIAN
			|| format.isBigEndian()) {
			buf.get(out);
			return out;
		}
		for (int i = 0; i + 1 < out.length; i += 2) {
			short s = buf.getShort();
			out[i] = (byte) s;
			out[i + 1] = (byte) (s >> 8);
		}
		return out;
	}

	private static void setGain(SourceDataLine line, float volume) {
		if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
			return;
		}
		FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
		float db = volume <= 0.0001F ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
		gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
	}
}
