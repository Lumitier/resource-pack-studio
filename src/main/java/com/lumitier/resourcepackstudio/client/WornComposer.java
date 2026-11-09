package com.lumitier.resourcepackstudio.client;

import com.lumitier.resourcepackstudio.scan.EntityModels;
import com.lumitier.resourcepackstudio.scan.StudioItem;
import com.lumitier.resourcepackstudio.scan.StudioItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Setzt Lage 1 der getragenen Ruestung aus mehreren Packs zusammen.
 *
 * WARUM: Helm, Brustplatte und Schuhe liegen in EINEM Bild
 * (entity/equipment/humanoid/<material>.png). Waehlt man den Helm aus Pack A
 * und die Brust aus Pack B, muss daraus ein neues Bild werden: Vanilla als
 * Grundlage, darueber die Bereiche aus den gewaehlten Packs (siehe
 * EntityModels.regions).
 *
 * AUFLOESUNG: Das Ergebnis bekommt die groesste vorkommende Breite. Kleinere
 * Bilder werden scharf (ohne Weichzeichnen) hochgerechnet. Alle Bilder haben
 * das Seitenverhaeltnis 2:1 (64x32, 128x64 ...).
 *
 * Laeuft im Hintergrund beim Erstellen des Packs.
 */
final class WornComposer {

	private WornComposer() {}

	/** Ein gewaehltes Teil: welche Bereiche, aus welchen Bytes. */
	record Piece(EntityModels.Piece piece, byte[] png) {}

	/** Ist das ein Teil, dessen Bild zusammengesetzt werden muss? */
	static EntityModels.Piece pieceOf(StudioItem item) {
		if (!item.id().startsWith(StudioItems.WORN_ID)) {
			return null;
		}
		String id = item.id();
		if (id.endsWith("_helmet")) {
			return EntityModels.Piece.HELMET;
		}
		if (id.endsWith("_chestplate")) {
			return EntityModels.Piece.CHESTPLATE;
		}
		if (id.endsWith("_boots")) {
			return EntityModels.Piece.BOOTS;
		}
		return null;
	}

	/**
	 * @param modern Pfad des Bildes relativ zu textures/ (Vanilla als Grundlage)
	 * @return das fertige PNG
	 */
	static byte[] compose(String modern, List<Piece> pieces) throws IOException {
		BufferedImage base = readVanilla(modern);
		List<BufferedImage> srcs = new ArrayList<>();
		int width = base == null ? 64 : base.getWidth();
		for (Piece p : pieces) {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(p.png()));
			srcs.add(img);
			if (img != null) {
				width = Math.max(width, img.getWidth());
			}
		}
		int height = width / 2;
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		// Kein Weichzeichnen: Standard ist "naechster Nachbar".
		if (base != null) {
			g.drawImage(base, 0, 0, width, height, null);
		}
		for (int i = 0; i < pieces.size(); i++) {
			BufferedImage src = srcs.get(i);
			if (src == null) {
				continue;
			}
			float sx = src.getWidth() / 64.0F;
			float sy = src.getHeight() / 32.0F;
			float dx = width / 64.0F;
			float dy = height / 32.0F;
			for (int[] r : EntityModels.regions(pieces.get(i).piece())) {
				int x1 = Math.round(r[0] * dx);
				int y1 = Math.round(r[1] * dy);
				int x2 = Math.round((r[0] + r[2]) * dx);
				int y2 = Math.round((r[1] + r[3]) * dy);
				// Erst leeren - sonst schiene Vanilla durch durchsichtige
				// Stellen des Pack-Bildes.
				g.setComposite(AlphaComposite.Clear);
				g.fillRect(x1, y1, x2 - x1, y2 - y1);
				g.setComposite(AlphaComposite.SrcOver);
				g.drawImage(src, x1, y1, x2, y2,
					Math.round(r[0] * sx), Math.round(r[1] * sy),
					Math.round((r[0] + r[2]) * sx), Math.round((r[1] + r[3]) * sy), null);
			}
		}
		g.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		return bytes.toByteArray();
	}

	private static BufferedImage readVanilla(String modern) {
		try {
			InputSupplier<InputStream> in = MinecraftClient.getInstance().getDefaultResourcePack()
				.open(ResourceType.CLIENT_RESOURCES, Identifier.ofVanilla("textures/" + modern + ".png"));
			if (in == null) {
				return null;
			}
			try (InputStream s = in.get()) {
				return ImageIO.read(s);
			}
		} catch (Throwable e) {
			return null;
		}
	}
}
