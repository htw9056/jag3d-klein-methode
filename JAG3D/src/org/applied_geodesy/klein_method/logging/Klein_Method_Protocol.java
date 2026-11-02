// V1.00_RC3_Klein_Method_Protocol.java
package org.applied_geodesy.klein_method.logging;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.applied_geodesy.klein_method.parameter.Klein_Method_Default_Values;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Protocol_Level;

/**
 * Stufen-gesteuertes Rechenergebnis-Protokoll (keine/kurz/lang, siehe Klein_Method_Protocol_Level) fuer ein gesamtes
 * JAG3D-Projekt - komplett unabhaengig vom allgemeinen Debug-Log (Klein_Method_Logger). Protokolliert wird NUR, was
 * aus Klein_Method heraus entsteht.
 *
 * Verhalten (wie vorgegeben):
 *
 * Standard: SHORT ("kurz")
 *
 * Stufe wird NICHT dauerhaft gespeichert - startet bei jedem Programmstart/neuen Projekt wieder auf Standard
 *
 * Der Zeitstempel fuer den Dateinamen wird erst beim ERSTEN Start einer Berechnung (Automatik oder Einzelschritt) bei
 * Stufe != NONE gesetzt
 *
 * Die Stufen-Auswahl ist IMMER \u00E4nderbar, auch waehrend einer bereits laufenden/protokollierten Berechnung - ein
 * Wechsel mitten in einer Protokolldatei kann zu uneinheitlichen Zeilen fuehren (z.B. Detailzeilen erst ab dem
 * Wechsel zu "lang"), das wird in Kauf genommen
 *
 * Bei jedem Projektwechsel wird alles zurueckgesetzt
 *
 * Neuester Eintrag steht OBEN, Spalten durch Leerzeichen ausgerichtet
 *
 * Jede Iterationszeile beginnt mit der aktuell erkannten Ausgleichungsart (z.B. "freie Ausgleichung", siehe
 * logStep())
 *
 * Bei einem erkannten ZUSTANDSWECHSEL zwischen zwei Ausgleichungsschritten (z.B. freie -> dynamische Ausgleichung)
 * wird eine EIGENE Zeile mit der SHA-256-Pruefsumme und bestanden/nicht bestanden eingefuegt (siehe
 * logStepTransition(), aufgerufen aus Klein_Method.enforceAdjustmentStepOrder())
 *
 * Die ueber die gesamte Sitzung aufsummierte Anzahl bearbeiteter Beobachtungen je Typ steht NICHT (mehr) in einer
 * eigenen Fusszeile, sondern wird an JEDER Zeile ganz am Ende mit angehaengt (nach den Zusatzparametern), jeweils mit
 * Bezeichnung vor dem Zaehler (z.B. "bearbeitete Horizontalrichtung: 5")
 *
 * die Protokolldatei landet jetzt, sobald ein Projekt offen ist, IM SELBEN Verzeichnis wie die aktive
 * JAG3D-Projektdatenbank selbst (dynamisch per Klein_Method_Database_Handler.getProjectDirectory() ermittelt, von
 * Klein_Method.projectDatabaseStateChanged() bei JEDEM DB-Statuswechsel neu gesetzt via setProjectDirectory()). Ist
 * aktuell kein Projekt offen bzw. laesst sich das Verzeichnis nicht ermitteln (z.B. Server-/Netzwerk-Datenbank),
 * greift weiterhin die alte AUSWEICHLOESUNG im Einstellungsordner (~/.klein_method/logs) - siehe
 * FALLBACK_PROTOCOL_FOLDER.
 *
 * ACHTUNG: HSQLDB legt im selben Verzeichnis eigene Dateien unter dem Basisnamen der Datenbank an
 * (<projektname>.script/ .properties/.data/.log/.lck). Klein_Method-Dateinamen beginnen bewusst immer mit
 * "Klein_Method" bzw. einem Zeitstempel (siehe buildNewLogFilePath() und Klein_Method.exportCoordinates()) und
 * kollidieren daher NICHT mit den HSQLDB-eigenen Dateien - AUSSER das Projekt selbst heisst zufaellig exakt
 * "Klein_Method" (dann waere z.B. "Klein_Method.log" mit HSQLDBs eigenem Transaktionslog gleichnamig). Dieser
 * Randfall ist nicht softwareseitig abgefangen.
 */
public class Klein_Method_Protocol {

	private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final DateTimeFormatter LINE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String HEADER_PREFIX = "Paketversion:";

	// AUSWEICHLOESUNG, nur solange (noch) kein Projektverzeichnis dynamisch bekannt ist (siehe Klassenkommentar
	// oben).
	private static final String FALLBACK_PROTOCOL_FOLDER = System.getProperty("user.home") + File.separator + ".klein_method" + File.separator + "logs";

	private static volatile String projectDirectoryOverride = null;

	// _FIX: Standard von SHORT auf LONG umgestellt, da die MATHE-DEBUG-Zeilen zwingend Stufe LONG voraussetzen und
	// ein vergessener manueller Umschalt-Klick sonst zu unvollstaendigen Protokollen fuehrt.
	// Klein_Method_Tab_Builder's ComboBox liest ihren Startwert direkt von getLevel() (kein zweiter, separat zu
	// pflegender Vorbelegungs-Ort), zieht diese Umstellung also automatisch mit.
	private Klein_Method_Protocol_Level level = Klein_Method_Protocol_Level.LONG;
	private String currentLogFilePath = null;

	// Ueber die gesamte Sitzung aufsummierte Anzahl bearbeiteter Beobachtungen
	// je Typ-Anzeigename (z.B. "Horizontalrichtung") - Grundlage der Fusszeile.
	// Bewusst im Speicher gefuehrt statt jedes Mal aus der Datei zurueckgelesen,
	// robuster als Text-Parsing der bestehenden Fusszeile.
	private final Map<String, Integer> cumulativeProcessedCounts = new LinkedHashMap<String, Integer>();

	public Klein_Method_Protocol_Level getLevel() {
		return this.level;
	}

	public boolean isEnabled() {
		return this.level != Klein_Method_Protocol_Level.NONE;
	}

	/**
	 * von aussen (nur Klein_Method.projectDatabaseStateChanged()) aufzurufen, wann immer sich das
	 * JAG3D-Projekt-Datenbankverzeichnis aendert. null bedeutet "aktuell kein Projekt offen bzw. nicht ermittelbar" -
	 * dann greift wieder die feste Ausweichloesung.
	 */
	public static void setProjectDirectory(String projectDirectory) {
		projectDirectoryOverride = projectDirectory;
	}

	public static String getProtocolFolderPath() {
		return (projectDirectoryOverride != null && !projectDirectoryOverride.isEmpty())
				? projectDirectoryOverride
				: FALLBACK_PROTOCOL_FOLDER;
	}

	/**
	 * Setzt die Protokollierungsstufe - IMMER wirksam, auch waehrend einer bereits laufenden/protokollierten
	 * Berechnung. Ein Stufenwechsel mitten in einer Protokolldatei kann dadurch zu uneinheitlichen Zeilen fuehren
	 * (z.B. Detailzeilen nur ab dem Wechsel zu "lang") - das ist bewusst in Kauf genommen.
	 */
	public void setLevel(Klein_Method_Protocol_Level level) {
		this.level = level;
	}

	/**
	 * Bei jedem Projektwechsel aufzurufen: setzt Stufe auf Standard zurueck, vergisst alte Protokolldatei UND die
	 * aufsummierten Zaehler (neues Projekt = neue Zaehlung von vorn).
	 */
	public void resetForNewProject() {
		// _FIX: Standard hier ebenfalls auf LONG - sonst wuerde jeder Projektwechsel/JAG3D-Neustart den neuen
		// Vorbelegungs-Standard aus dem Feld oben sofort wieder rueckgaengig machen.
		this.level = Klein_Method_Protocol_Level.LONG;
		this.currentLogFilePath = null;
		this.cumulativeProcessedCounts.clear();
	}

	/**
	 * Protokolliert EINE abgeschlossene Iteration (kein Zwischenschritt). Legt beim allerersten Aufruf (fuer dieses
	 * Projekt) die Protokolldatei an. Tut NICHTS bei Stufe NONE.
	 *
	 * Spaltenreihenfolge der Hauptzeile (vom Nutzer festgelegt, jeder Wert mit erklaerendem Praefix - nur
	 * Ausgleichungsart und Zeitstempel bleiben ohne Praefix): Ausgleichungsart | Zeitstempel | Iteration: .. |
	 * Grenzwerte: .. | Max-logPost: .. | Max-EP: .. | Automatik/Manuell: .. |
	 *
	 * Berechnungsmethode: .. | Phase: .. | Waechter-Zustand: ..
	 *
	 * @param adjustmentStepLabel Kurzbezeichnung des AKTUELL erkannten Ausgleichungsschritts (z.B. "freie
	 * Ausgleichung", siehe Klein_Method_Adjustment_Step.getShortDisplayName()) - steht GANZ VORN in der Zeile
	 *
	 * @param triggerLabel "Automatik" oder "Manuell" (Einzelschritt)
	 *
	 * @param evaluationMethodLabel "logPost" oder "EP"
	 *
	 * @param guardianStatusLabel Waechter-Zustand als Text
	 *
	 * @param thresholdsText die aktuell verwendeten Grenzwerte, bereits fertig formatiert (siehe Klein_Method_Format)
	 *
	 * @param adjustmentDetails NUR bei Stufe LONG ausgewertet: eine Zeile je einzelner sigma_a/sigma_b/sigma_c- bzw.
	 * sigma_0-Anpassung dieser Iteration, bereits fertig formatiert uebergeben. Bei SHORT/NONE ignoriert - der
	 * Aufrufer kann die Liste trotzdem immer mitgeben, ohne selbst auf die Stufe Ruecksicht nehmen zu muessen.
	 *
	 * @param processedCountsThisIteration Anzahl bearbeiteter Beobachtungen dieser Iteration je Typ-Anzeigename (z.B.
	 * "Horizontalrichtung"), fliesst IMMER (auch bei SHORT) in die kumulierten Zaehler ein, die am Ende JEDER Zeile
	 * mit ausgegeben werden.
	 *
	 * @param additionalParametersText Nullpunktabweichung/Ma\u00DFstab fuer Horizontalstrecke/Schr\u00E4gstrecke,
	 * bereits fertig formatiert \u00FCbergeben (siehe Klein_Method_Format) - wird IMMER am ENDE der Zeile angehaengt,
	 * unabhaengig von der Stufe, direkt gefolgt von den kumulierten Zaehlern.
	 */
	public void logStep(int iteration, String adjustmentStepLabel, String phase, double maxLogPost, double maxEp,
			String triggerLabel, String evaluationMethodLabel, String guardianStatusLabel, String thresholdsText,
			List<String> adjustmentDetails, Map<String, Integer> processedCountsThisIteration, String additionalParametersText) {
		if (this.level == Klein_Method_Protocol_Level.NONE)
			return;

		if (this.currentLogFilePath == null) {
			this.currentLogFilePath = this.buildNewLogFilePath();
		}

		// Zaehler ZUERST aktualisieren, damit die in dieser Zeile ausgegebene
		// Summe die aktuelle Iteration bereits mit einschliesst.
		if (processedCountsThisIteration != null) {
			for (Map.Entry<String, Integer> entry : processedCountsThisIteration.entrySet())
				this.cumulativeProcessedCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}

		// Kumulierte, ueber die gesamte Sitzung aufsummierte Zaehler je Typ - KEINE eigene Fusszeile mehr, sondern an
		// JEDER Zeile ans Ende angehaengt, jeweils mit Bezeichnung vor dem Zaehler (z.B. "bearbeitete
		// Horizontalrichtung: 5").
		StringBuilder cumulativeCountsText = new StringBuilder();
		for (Map.Entry<String, Integer> entry : this.cumulativeProcessedCounts.entrySet()) {
			if (cumulativeCountsText.length() > 0)
				cumulativeCountsText.append(" | ");
			cumulativeCountsText.append("bearbeitete ").append(entry.getKey()).append(": ").append(entry.getValue());
		}

		String lineSuffix = additionalParametersText == null ? "" : additionalParametersText;
		if (cumulativeCountsText.length() > 0)
			lineSuffix = (lineSuffix.isEmpty() ? "" : lineSuffix + " | ") + cumulativeCountsText;

		String timestamp = LocalDateTime.now().format(LINE_TIMESTAMP_FORMATTER);
		String mainLine = String.format("%-30s %-20s %-14s %-40s %-24s %-24s %-22s %-24s %-30s %-20s %s",
				adjustmentStepLabel,
				timestamp,
				"Iteration: " + iteration,
				"Grenzwerte: " + thresholdsText,
				"Max-logPost: " + String.format("%.4f", maxLogPost),
				"Max-EP: " + String.format("%.4f", maxEp),
				"Automatik/Manuell: " + triggerLabel,
				"Berechnungsmethode: " + evaluationMethodLabel,
				"Phase: " + phase,
				"W\u00E4chter-Zustand: " + guardianStatusLabel,
				lineSuffix);

		List<String> detailLines = new ArrayList<String>();
		if (this.level == Klein_Method_Protocol_Level.LONG && adjustmentDetails != null) {
			for (String detail : adjustmentDetails)
				detailLines.add("    " + detail); // eingerueckt, damit optisch als Detail zur Hauptzeile erkennbar
		}

		this.writeProtocolFile(mainLine, detailLines);
	}

	/**
	 * Protokolliert einen ERKANNTEN ZUSTANDSWECHSEL zwischen zwei Ausgleichungsschritten (z.B. freie -> dynamische
	 * Ausgleichung) als EIGENE, deutlich erkennbare Zeile - unabhaengig von der normalen Iterationszeile (logStep())
	 * und unabhaengig von der Protokoll-Stufe (wird IMMER geschrieben, solange Protokoll nicht auf "keine" steht, da
	 * ein Schrittwechsel ein einmaliges, wichtiges Ereignis ist statt einer Routine-Iteration).
	 *
	 * @param checksum die fuer den Vergleich verwendete SHA-256-Pruefsumme
	 * (Klein_Method_Database_Handler_Interface.computeCurrentStepChecksum())
	 *
	 * @param previousStepShortName Kurzbezeichnung des GEPRUEFTEN vorherigen Schritts (z.B. "freie Ausgleichung",
	 * siehe Klein_Method_Adjustment_Step.getShortDisplayName())
	 *
	 * @param passed true, wenn die Pruefsumme des vorherigen Schritts noch mit der gespeicherten uebereinstimmt
	 * (Reihenfolge gueltig)
	 */
	public void logStepTransition(String checksum, String previousStepShortName, boolean passed) {
		if (this.level == Klein_Method_Protocol_Level.NONE || this.currentLogFilePath == null)
			return;

		String timestamp = LocalDateTime.now().format(LINE_TIMESTAMP_FORMATTER);
		String transitionLine = timestamp + "  SHA-256 Pr\u00FCfsumme: Wert " + checksum
				+ " | Status " + previousStepShortName + " " + (passed ? "bestanden" : "nicht bestanden");

		this.writeProtocolFile(transitionLine, new ArrayList<String>());
	}

	/**
	 * eigene Ereigniszeile (z.B. "Grobe Fehler bereinigen", Laufende mit Grund) - unabhaengig von der
	 * Protokoll-Stufe, nur nicht bei "keine". Legt die Datei bei Bedarf an.
	 */
	/** Zuhoerer fuer Ereignisse (Zustandsprotokoll im Projekt) - unabhaengig von der Protokollstufe. */
	public static volatile java.util.function.Consumer<String> eventListener = null;

	public void logEvent(String text) {
		java.util.function.Consumer<String> listener = eventListener;
		if (listener != null)
			try {
				listener.accept(text);
			} catch (RuntimeException e) {
				// Zustandsprotokoll ist Zusatz - nie den Ablauf stoeren
			}
		if (this.level == Klein_Method_Protocol_Level.NONE)
			return;
		if (this.currentLogFilePath == null)
			this.currentLogFilePath = this.buildNewLogFilePath();
		String timestamp = LocalDateTime.now().format(LINE_TIMESTAMP_FORMATTER);
		this.writeProtocolFile(timestamp + "  " + text, new ArrayList<String>());
	}

	private String buildNewLogFilePath() {
		String protocolFolder = getProtocolFolderPath();
		File folder = new File(protocolFolder);
		if (!folder.exists())
			folder.mkdirs();

		String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
		return protocolFolder + File.separator + "Klein_Method_" + timestamp + ".log";
	}

	/**
	 * Schreibt die neue Hauptzeile (plus ggf. Detailzeilen bei LONG) direkt NACH der Kopfzeile (neuester Eintrag
	 * zuerst nach der Kopfzeile, Prepend-Schreiben). Die Kopfzeile ("Paketversion: ...") steht IMMER ganz oben in der
	 * Datei, bleibt beim Prepend-Schreiben unveraendert erhalten und wird nur beim allerersten Schreiben dieser Datei
	 * neu erzeugt - Wunsch, damit sich aus der Log-Datei selbst ablesen laesst, welche Paketversion sie erzeugt hat.
	 */
	private synchronized void writeProtocolFile(String mainLine, List<String> detailLines) {
		List<String> existingLines = new ArrayList<String>();
		File file = new File(this.currentLogFilePath);

		if (file.exists()) {
			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String line;
				while ((line = reader.readLine()) != null)
					existingLines.add(line);
			} catch (IOException e) {
				System.err.println("Klein_Method_Protocol: Konnte bestehendes Protokoll nicht lesen: " + e.getMessage());
			}
		}

		// vorhandene Kopfzeile erkennen (steht immer ganz oben) und aus den
		// restlichen Zeilen herausloesen - sie wird gleich separat wieder an
		// erster Stelle geschrieben, damit sie NICHT durch neue Iterations-
		// zeilen nach unten verdraengt wird.
		String headerLine;
		if (!existingLines.isEmpty() && existingLines.get(0).startsWith(HEADER_PREFIX))
			headerLine = existingLines.remove(0);
		else
			headerLine = HEADER_PREFIX + " " + Klein_Method_Default_Values.PACKAGE_VERSION;

		try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
			writer.println(headerLine);
			writer.println(Klein_Method_Logger.ascii(mainLine));
			for (String detailLine : detailLines)
				writer.println(Klein_Method_Logger.ascii(detailLine));
			for (String line : existingLines)
				writer.println(line);
		} catch (IOException e) {
			System.err.println("Klein_Method_Protocol: Konnte Protokoll nicht schreiben: " + e.getMessage());
		}
	}
}
// V1.00_RC3_Klein_Method_Protocol.java
