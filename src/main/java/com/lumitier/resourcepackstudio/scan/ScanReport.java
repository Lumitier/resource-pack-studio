package com.lumitier.resourcepackstudio.scan;

import com.lumitier.resourcepackstudio.ResourcePackStudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Schreibt das Ergebnis der Suche als lesbaren Text.
 *
 * Zum Nachsehen, ob die Suche ein Pack gefunden hat. Der Bericht liegt in
 * resourcepackstudio/scan-report.txt im Spielordner.
 */
public final class ScanReport {

	private ScanReport() {}

	public static void write(PackScanner.Result r, List<Path> roots) {
		StringBuilder sb = new StringBuilder();
		sb.append("Resource Pack Studio - scan report\n");
		sb.append("==================================\n\n");
		sb.append("Searched folders:\n");
		for (Path p : roots) {
			sb.append("  ").append(p).append(Files.isDirectory(p) ? "" : "  (missing)").append('\n');
		}
		sb.append("\nScan took ").append(r.millis()).append(" ms.\n\n");

		sb.append("Packs found: ").append(r.packs().size()).append("\n");
		for (PackScanner.Pack p : r.packs()) {
			int n = 0;
			for (List<PackScanner.Variant> vs : r.variants().values()) {
				for (PackScanner.Variant v : vs) {
					if (v.pack() == p) {
						n++;
					}
				}
			}
			sb.append("  [").append(p.zip() ? "zip" : "dir").append("] ")
				.append(p.name())
				.append(p.prefix().isEmpty() ? "" : "  (inside folder '" + p.prefix() + "')")
				.append("  -  ").append(n).append(" item texture(s)")
				.append("  -  in ").append(p.source()).append('\n');
		}

		// Nur Items, fuer die mindestens ein Pack etwas hat - bei ueber
		// 700 Items waere der Rest nur Rauschen.
		sb.append("\nVariants per item (").append(r.variants().size()).append(" items searched, only those with a texture listed):\n");
		for (Map.Entry<StudioItem, List<PackScanner.Variant>> e : r.variants().entrySet()) {
			List<PackScanner.Variant> vs = e.getValue();
			if (vs.isEmpty()) {
				continue;
			}
			sb.append("\n").append(e.getKey().id()).append("  (").append(vs.size()).append(")\n");
			for (PackScanner.Variant v : vs) {
				sb.append("    ").append(v.pack().name())
					.append(v.legacy() ? "   [1.8 path]" : "")
					.append('\n');
			}
		}

		if (!r.problems().isEmpty()) {
			sb.append("\nProblems:\n");
			for (String s : r.problems()) {
				sb.append("  ").append(s).append('\n');
			}
		}

		Path out = ResourcePackStudio.home().resolve("scan-report.txt");
		try {
			Files.createDirectories(out.getParent());
			Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ResourcePackStudio.LOGGER.warn("[Resource Pack Studio] Could not write the scan report.", e);
		}
	}
}
