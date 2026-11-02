// V1.00_RC3_Klein_Method_Logger.java
package org.applied_geodesy.klein_method.logging;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Eigene, einfache Protokollierung fuer die Erweiterung "Klein_Method". Schreibt in eine eigene Datei
 * "Klein_Method.log" - unabhaengig von der Protokollierung des Hauptprogramms.
 *
 * liegt, sobald ein Projekt offen ist, im SELBEN Verzeichnis wie die aktive JAG3D-Projektdatenbank (dynamisch von
 * Klein_Method.projectDatabaseStateChanged() ueber setProjectDirectory() gesetzt, analog zu Klein_Method_Protocol).
 * Ist kein Projekt offen bzw. nicht ermittelbar, greift weiterhin die alte Ausweichloesung (~/.klein_method/) - siehe
 * FALLBACK_LOG_FOLDER.
 */
public class Klein_Method_Logger {

	private static final String FALLBACK_LOG_FOLDER = System.getProperty("user.home") + File.separator + ".klein_method";
	private static volatile String projectDirectoryOverride = null;
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/**
	 * siehe Klein_Method_Protocol.setProjectDirectory() - dieselbe Semantik, nur fuer das Debug-Log statt das
	 * Rechenergebnis-Protokoll. Beide werden von Klein_Method.projectDatabaseStateChanged() gemeinsam aktualisiert.
	 */
	public static void setProjectDirectory(String projectDirectory) {
		projectDirectoryOverride = projectDirectory;
	}

	private static String getLogFolder() {
		return (projectDirectoryOverride != null && !projectDirectoryOverride.isEmpty())
				? projectDirectoryOverride
				: FALLBACK_LOG_FOLDER;
	}

	/**
	 * Log und Protokoll sind reines ASCII (Umlaute als ae/oe/ue/ss): die Dateien werden mit der Standardkodierung des
	 * Systems geschrieben, Umlaute aus Anzeigenamen (z. B. "Daempfungs-Formel") erschienen sonst je nach System als "?".
	 */
	public static String ascii(String text) {
		if (text == null)
			return null;
		StringBuilder sb = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch < 128) {
				sb.append(ch);
				continue;
			}
			switch (ch) {
			case '\u00e4': sb.append("ae"); break;
			case '\u00f6': sb.append("oe"); break;
			case '\u00fc': sb.append("ue"); break;
			case '\u00c4': sb.append("Ae"); break;
			case '\u00d6': sb.append("Oe"); break;
			case '\u00dc': sb.append("Ue"); break;
			case '\u00df': sb.append("ss"); break;
			case '\u03c3': sb.append("s"); break;
			case '\u00b2': sb.append("2"); break;
			case '\u00b3': sb.append("3"); break;
			case '\u00b5': sb.append("u"); break;
			case '\u00b0': sb.append(" Grad"); break;
			case '\u00b1': sb.append("+/-"); break;
			case '\u00d7': sb.append("x"); break;
			case '\u00b7': sb.append("*"); break;
			case '\u2013': case '\u2014': case '\u2212': sb.append("-"); break;
			case '\u201e': case '\u201c': case '\u201d': sb.append('"'); break;
			case '\u201a': case '\u2018': case '\u2019': sb.append('\''); break;
			case '\u2026': sb.append("..."); break;
			case '\u2192': sb.append("->"); break;
			case '\u2264': sb.append("<="); break;
			case '\u2265': sb.append(">="); break;
			case '\u0394': sb.append("Delta"); break;
			case '\u00a0': sb.append(' '); break;
			default: {
				String base = java.text.Normalizer.normalize(String.valueOf(ch), java.text.Normalizer.Form.NFD).replaceAll("[^\\x00-\\x7F]", "");
				sb.append(base.isEmpty() ? "?" : base);
			}
			}
		}
		return sb.toString();
	}

	public void log(String message) {
		String timestamp = LocalDateTime.now().format(FORMATTER);
		String line = ascii("[" + timestamp + "] " + message);

		// Ausgabe in der Konsole (fuer die erste Testphase hilfreich)
		System.out.println(line);

		// Zusaetzlich in eigene Log-Datei schreiben - Ordner erst JETZT (pro
		// Aufruf) aufgeloest statt als static final, da sich projectDirectoryOverride
		// waehrend der Laufzeit aendern kann (Projektwechsel).
		String logFolder = getLogFolder();
		String logFilePath = logFolder + File.separator + "Klein_Method.log";

		File folder = new File(logFolder);
		if (!folder.exists())
			folder.mkdirs();

		try (PrintWriter writer = new PrintWriter(new FileWriter(logFilePath, true))) {
			writer.println(line);
		} catch (IOException e) {
			System.err.println("Klein_Method_Logger: Konnte nicht in " + logFilePath + " schreiben: " + e.getMessage());
		}
	}
}
// V1.00_RC3_Klein_Method_Logger.java
