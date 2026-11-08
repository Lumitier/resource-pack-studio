package com.lumitier.resourcepackstudio.client;

import com.lumitier.lumilib.client.Draw;
import com.lumitier.lumilib.client.Surface;
import com.lumitier.lumilib.client.Theme;
import com.lumitier.lumilib.client.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

/**
 * Eingabefeld fuer den Pack-Namen im LumiLib-Look.
 *
 * Bewusst einfach: Schreibmarke immer am Ende, Backspace loescht, Strg+V
 * fuegt ein. Mehr braucht ein Name nicht. Leer zeigt es den Platzhalter (den Namen, der
 * dann benutzt wird).
 */
final class NameField {

	static final int MAX = 48;

	private final StringBuilder text = new StringBuilder();
	private String placeholder = "";

	String text() {
		return text.toString();
	}

	void reset(String placeholder) {
		this.text.setLength(0);
		this.placeholder = placeholder;
	}

	void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h) {
		if (!Surface.glassCard(ctx, x, y, w, h, Theme.FIELD_FOCUS)) {
			Surface.row(ctx, x, y, w, h, Theme.FIELD_FOCUS);
		}
		int tx = x + 8;
		int mid = y + h / 2;
		int room = w - 16;
		if (text.length() == 0) {
			Ui.drawCenteredLine(ctx, tr, Ui.text(Ui.fit(tr, placeholder, room)),
				tx, mid, Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT_FAINT);
		} else {
			String shown = Ui.fit(tr, text.toString(), room);
			Ui.drawCenteredLine(ctx, tr, Ui.text(shown),
				tx, mid, Ui.BODY_H, Ui.BODY_D, Ui.TOP_BODY, Theme.TEXT);
			tx += Ui.width(tr, shown);
		}
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			Draw.vLine(ctx, Math.min(tx + 1, x + w - 8),
				mid - (Ui.BODY_H + Ui.BODY_D) / 2, Ui.BODY_H + Ui.BODY_D, Theme.TEXT);
		}
	}

	/** @return true, wenn die Taste verbraucht wurde. */
	boolean keyPressed(KeyInput input) {
		// Strg+V: Zeilenumbrueche und Steuerzeichen werden zu Leerzeichen,
		// abgeschnitten wird bei der Hoechstlaenge.
		if (input.isPaste()) {
			paste(MinecraftClient.getInstance().keyboard.getClipboard());
			return true;
		}
		if (input.getKeycode() == GLFW.GLFW_KEY_BACKSPACE) {
			if (text.length() > 0) {
				text.setLength(text.length() - 1);
			}
			return true;
		}
		return false;
	}

	/** Text einfuegen - Zeilenumbrueche werden zu Leerzeichen, gekuerzt auf MAX. */
	void paste(String clip) {
		if (clip == null) {
			return;
		}
		String clean = clip.replaceAll("\\p{Cntrl}+", " ").strip();
		int room = MAX - text.length();
		text.append(clean, 0, Math.max(0, Math.min(room, clean.length())));
	}

	boolean charTyped(CharInput input) {
		if (!input.isValidChar()) {
			return false;
		}
		String s = input.asString();
		if (s == null || s.isEmpty() || text.length() + s.length() > MAX) {
			return false;
		}
		text.append(s);
		return true;
	}
}
