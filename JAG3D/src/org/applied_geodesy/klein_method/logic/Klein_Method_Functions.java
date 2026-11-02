// V1.00_RC3_Klein_Method_Functions.java
package org.applied_geodesy.klein_method.logic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.applied_geodesy.adjustment.network.PointType;
import org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface;
import org.applied_geodesy.klein_method.database.Klein_Method_Ep_Row;
import org.applied_geodesy.klein_method.database.Klein_Method_Observation_Uncertainty_Row;
import org.applied_geodesy.klein_method.database.Klein_Method_Point_Coordinate_Row;
import org.applied_geodesy.klein_method.database.Klein_Method_Point_Tprio_Row;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Data_Status;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Default_Values;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Device_Class;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Distribution_Type;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Format;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter;
// verschachtelter Typ-Import, seit das Enum direkt in Klein_Method_Parameter eingebettet ist.
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter.Klein_Method_Damping_Formula_Type;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Process_Phase;
import org.applied_geodesy.klein_method.logging.Klein_Method_Logger;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;
import org.applied_geodesy.util.unit.AngleUnit;
import org.applied_geodesy.util.unit.LengthUnit;
import org.applied_geodesy.util.unit.ScaleUnit;
import static org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.tr;

/**
 * Enthaelt die eigentliche Fachlogik von "Klein_Method": Bewertung der gelesenen Daten und Neuberechnung der
 * Parameter (Phase 1 Gruppenkalibrierung, Phase 2 Individualkalibrierung, EP-Einzelschritt) inklusive Grenzwert-,
 * Schwellwert- und Zusatzparameter-Logik.
 */
public class Klein_Method_Functions {
	// Teilklassen (Gliederung, siehe Ende der Klasse)
	private final Phase1_Part phase1Part = new Phase1_Part();
	private final Phase2_Part phase2Part = new Phase2_Part();
	private final Ep_Part epPart = new Ep_Part();
	private final Dynamic_Part dynamicPart = new Dynamic_Part();
	private final Tab_Check_Part tabCheckPart = new Tab_Check_Part();

	/**
	 * Uebersetzung der Oberflaeche (Stufe 1: Tab, Knoepfe, Tooltips, Statuszeilen, Dialoge). Schluessel = "k" + CRC32
	 * (8 Hex) des deutschen UTF-8-Textes, damit der Quelltext deutsch lesbar bleibt. Deutsch ist die Ausgangssprache
	 * (keine Suche). Nicht unterstuetzte Systemsprache -&gt; Englisch. Fehlt ein einzelner Text in der Sprachdatei
	 * -&gt; deutsch. Protokoll und Log bleiben IMMER deutsch (dort kein tr()).
	 *
	 * Sprachdateien: /org/applied_geodesy/klein_method/i18n/klein_method_xx.properties (UTF-8) im Klassenpfad,
	 * optional ueberschrieben durch ~/.klein_method/i18n/klein_method_xx.properties. Wirkung erst nach Neustart von
	 * JAG3D (init() nur einmal beim Start).
	 */
	public static final class Klein_Method_I18N {
		public static final String AUTO = "AUTO";
		public static final String[] LANGUAGES = { "de", "en", "pl", "cs", "fr", "es", "pt" };
		public static final String[] ENDONYMS = { "Deutsch", "English", "Polski", "\u010Ce\u0161tina", "Fran\u00E7ais", "Espa\u00F1ol", "Portugu\u00EAs" };
		private static final String RESOURCE = "/org/applied_geodesy/klein_method/i18n/klein_method_%s.properties";
		private static volatile String language = "de";
		private static volatile Map<String, String> texts = java.util.Collections.emptyMap();
		private static volatile boolean initialized = false;

		private Klein_Method_I18N() {
		}

		/** Liefert die zu verwendende Sprache fuer eine Einstellung (AUTO/Kuerzel). */
		public static String resolve(String setting) {
			String wanted = setting == null || setting.trim().isEmpty() || AUTO.equalsIgnoreCase(setting.trim())
					? Locale.getDefault().getLanguage() : setting.trim();
			wanted = wanted == null ? "" : wanted.toLowerCase(Locale.ROOT);
			for (String lang : LANGUAGES)
				if (lang.equals(wanted))
					return lang;
			return "en";
		}

		public static synchronized void init(String setting) {
			if (initialized)
				return; // Sprachwechsel erst nach Neustart
			initialized = true;
			String lang = resolve(setting);
			Map<String, String> loaded = new java.util.HashMap<String, String>();
			if (!"de".equals(lang)) {
				String resource = String.format(Locale.ROOT, RESOURCE, lang);
				try (java.io.InputStream in = Klein_Method_I18N.class.getResourceAsStream(resource)) {
					load(in, loaded);
				} catch (Exception e) {
					// Sprachdatei fehlt -> deutsche Texte
				}
				// JAG3D-Aufbau ohne kopierte Ressourcen (nur .class im Klassenpfad): Sprachdatei im Quellordner suchen
				java.io.File source = loaded.isEmpty() ? findPackageFile(resource) : null;
				if (source != null)
					try (java.io.InputStream in = new java.io.FileInputStream(source)) {
						load(in, loaded);
					} catch (Exception e) {
						// unlesbar -> deutsche Texte
					}
				java.io.File override = new java.io.File(System.getProperty("user.home"),
						".klein_method" + java.io.File.separator + "i18n" + java.io.File.separator + "klein_method_" + lang + ".properties");
				if (override.isFile()) {
					try (java.io.InputStream in = new java.io.FileInputStream(override)) {
						load(in, loaded);
					} catch (Exception e) {
						// eigene Datei unlesbar -> ignorieren
					}
				}
			}
			texts = loaded;
			language = lang;
		}

		/**
		 * Datei des Pakets (Pfad wie Ressource, z. B. "/org/applied_geodesy/klein_method/hilfe/...") direkt auf der Platte:
		 * als Datei im Klassenpfad, sonst im Quellordner des JAG3D-Projekts - ausgehend vom Ort der Klassen und vom
		 * Arbeitsverzeichnis je bis zu 6 Ebenen nach oben, dort "", "src", "JAG3D/src". null = nicht gefunden.
		 */
		public static java.io.File findPackageFile(String resource) {
			String relative = resource.startsWith("/") ? resource.substring(1) : resource;
			try {
				java.net.URL url = Klein_Method_I18N.class.getResource("/" + relative);
				if (url != null && "file".equals(url.getProtocol())) {
					java.io.File file = new java.io.File(url.toURI());
					if (file.isFile())
						return file;
				}
			} catch (Exception e) {
				// weiter suchen
			}
			List<java.io.File> starts = new ArrayList<java.io.File>();
			try {
				java.security.CodeSource code = Klein_Method_I18N.class.getProtectionDomain().getCodeSource();
				if (code != null && code.getLocation() != null)
					starts.add(new java.io.File(code.getLocation().toURI()));
			} catch (Exception e) {
				// ohne Ort der Klassen
			}
			starts.add(new java.io.File(System.getProperty("user.dir", ".")).getAbsoluteFile());
			for (java.io.File start : starts) {
				java.io.File dir = start;
				for (int level = 0; dir != null && level <= 6; level++, dir = dir.getParentFile())
					for (String prefix : new String[] { "", "src", "JAG3D" + java.io.File.separator + "src" }) {
						java.io.File file = new java.io.File(new java.io.File(dir, prefix), relative.replace('/', java.io.File.separatorChar));
						if (file.isFile())
							return file;
					}
			}
			return null;
		}

		private static void load(java.io.InputStream in, Map<String, String> target) throws java.io.IOException {
			if (in == null)
				return;
			java.util.Properties properties = new java.util.Properties();
			properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
			for (String name : properties.stringPropertyNames()) {
				String value = properties.getProperty(name);
				if (value != null && !value.isEmpty())
					target.put(name, value);
			}
		}

		public static String getLanguage() {
			return language;
		}

		public static int getTextCount() {
			return texts.size();
		}

		public static String key(String german) {
			java.util.zip.CRC32 crc = new java.util.zip.CRC32();
			crc.update(german.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return String.format(Locale.ROOT, "k%08x", crc.getValue());
		}

		/** Uebersetzt einen deutschen Oberflaechentext; unbekannt -> deutsch. */
		public static String tr(String german) {
			if (german == null || "de".equals(language) || texts.isEmpty())
				return german;
			String text = texts.get(key(german));
			return text == null ? german : text;
		}
	}

	/**
	 * Verknuepft die 6 Einzelergebnisse zu EINER Abbruchentscheidung fuer die Iterationsschleife.
	 *
	 * die Schleife laeuft jetzt so lange, bis BEIDE Phasen wirklich abgeschlossen sind
	 * (Klein_Method_Process_Phase.DONE) - das deckt sich mit recompute(), das die Phase erst dann auf DONE setzt,
	 * wenn performIndividualCalibrationStep() erfolgreich durchgelaufen ist.
	 */
	public boolean isThresholdReached(Klein_Method_Parameter parameter) {
		return parameter.getProcessPhase() == Klein_Method_Process_Phase.DONE;
	}

	/**
	 * Die 4 logPost-Grenzwerttypen, fuer die es eine Gruppenunsicherheit
	 * (σa/σb/σc) gibt und damit Phase 1 durchgefuehrt wird.
	 */
	// Dynamisch aus der Kategorie abgeleitet (siehe Klein_Method_Threshold_Type.
	// Category) statt hartcodierter Listen - ein neuer Grenzwert-Typ mit
	// Category.LOG_POST bzw. Category.EP wird dadurch automatisch
	// beruecksichtigt, ohne dass diese Datei angefasst werden muss.
	private static final Klein_Method_Threshold_Type[] LOG_POST_TYPES_FOR_GROUP_CALIBRATION =
			Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST);

	private static final Klein_Method_Threshold_Type[] EP_TYPES =
			Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.EP);

	/**
	 * Die 4 Typen, fuer die die EP-Einzelschritt-Kalibrierung (siehe performEpSingleStep()) tatsaechlich laeuft -
	 * Nivellementszug ist AUSGENOMMEN.
	 *
	 * GNSS nimmt aber bereits vollstaendig an Grenzwert-Verwaltung, GROUP_CALIBRATION und INDIVIDUAL_CALIBRATION teil
	 * (automatisch ueber Klein_Method_Threshold_Type.valuesOfCategory()) - betroffen ist NUR der manuelle
	 * EP-Einzelschritt-Button.
	 */
	// NIVELLEMENT bleibt weiterhin BEWUSST ausgenommen.
	// WICHTIG: die drei GNSS-Typen werden NICHT wie die generischen ueber readAllObservationsForEpCalibration()
	// gelesen - diese Abfrage traefe die generischen "ObservationApriori"/"Aposteriori"-Tabellen, in denen
	// GNSS-Basislinien strukturell NIE vorkommen - sie wuerde also fuer GNSS immer 0 Zeilen liefern und die
	// Erweiterung waere ein stiller Nullwert-Effekt, keine echte Anbindung. Siehe performEpSingleStep() unten: dort
	// werden GNSS-Typen ueber die neue, eigene readAllGnssObservationsForEpCalibration() gelesen (liefert pro
	// Basislinie 3 Achsen-Zeilen) und beim Zurueckschreiben ueber writeGnssIndividualUncertainty() (achsen-bewusst)
	// statt des generischen writeIndividualUncertainty() geschrieben - exakt dieselbe Disziplin wie in Phase 2 (keine
	// Vermischung der Achsen).
	private static final Klein_Method_Threshold_Type[] EP_ELIGIBLE_TYPES_FOR_SINGLE_STEP = new Klein_Method_Threshold_Type[] {
			Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST,
			Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST,
			Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST,
			Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST,
			Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST,
			Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST,
			Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST
	};

	// die 3 GNSS-Typen aus EP_ELIGIBLE_TYPES_FOR_SINGLE_STEP, separat gehalten, damit performEpSingleStep() beim
	// Einlesen pro Typ entscheiden kann, ob der generische oder der GNSS-Lesepfad verwendet werden muss (siehe
	// Kommentar oben).
	private static final java.util.Set<Klein_Method_Threshold_Type> GNSS_TYPES_FOR_SINGLE_STEP = java.util.EnumSet.of(
			Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST,
			Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST,
			Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST
	);

	/**
	 * "Nahe Null"-Schwelle fuer die Mehrheits-Erkennung (siehe
	 * performEpSingleStep()): ein EP-Wert gilt als "nahe Null", wenn er
	 * kleiner als 5% des aktuell eingestellten EP-Grenzwerts DIESES Typs
	 * ist. Kein exakter, vom User genannter Wert - plausibel gewaehlt.
	 */
	private static final double EP_NEAR_ZERO_FRACTION_OF_THRESHOLD = 0.05;

	/**
	 * Liefert den groessten der 4 aktuellen EP-Werte - fuer das
	 * Protokoll (Klein_Method_Protocol) gedacht, damit dort neben
	 * Max-logPost auch Max-EP je Zeile mitgeloggt werden kann.
	 */
	public double getMaxEpValue(Klein_Method_Parameter parameter) {
		double max = 0.0;
		for (Klein_Method_Threshold_Type type : EP_TYPES)
			if (type != Klein_Method_Threshold_Type.NIVELLEMENT_EP) // Nivellement wird nicht nach EP bewertet (kleine Redundanzanteile)
				max = Math.max(max, parameter.getEvaluationValue(type));
		return max;
	}

	/**
	 * Fuehrt den zur aktuellen Phase (Klein_Method_Process_Phase) passenden Berechnungsschritt aus und schaltet ggf.
	 * zur naechsten Phase weiter.
	 *
	 * @param outAdjustmentDetails wird IMMER befuellt (eine Zeile je einzelner sigma_a/sigma_b/sigma_c- bzw.
	 * sigma_0-Anpassung dieses Schritts) - der Aufrufer (Klein_Method_Protocol) entscheidet je nach Protokoll-Stufe,
	 * ob er die Liste tatsaechlich mit ausgibt.
	 *
	 * @param outProcessedCounts wird bei Phase 2 befuellt (Anzahl bearbeiteter Beobachtungen je Typ-Anzeigename) -
	 * Grundlage der Protokoll-Fusszeile.
	 *
	 * @return true, wenn der Schritt erfolgreich war.
	 */
	/**
	 * Ermittelt den aktuellen Ausgleichungsschritt (frei/dynamisch/fix)
	 * anhand der aktiven Punktkategorien - identische Logik wie
	 * Klein_Method.getCurrentAdjustmentStep(), hierher verschoben, damit
	 * auch Klein_Method_Iteration_Task (das keinen Zugriff auf die
	 * Klein_Method-Instanz selbst hat) den Schritt kennt, BEVOR recompute()
	 * entscheidet, ob Festpunktanalyse (DYNAMIC) oder Beobachtungs-
	 * Kalibrierung (FREE/FIXED) laeuft.
	 */
	public Klein_Method_Adjustment_Step determineCurrentAdjustmentStep(Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		Set<PointType> activeTypes = databaseHandler.readActivePointTypes();

		if (activeTypes.contains(PointType.REFERENCE_POINT))
			return Klein_Method_Adjustment_Step.FIXED;
		if (activeTypes.contains(PointType.STOCHASTIC_POINT))
			return Klein_Method_Adjustment_Step.DYNAMIC;
		if (activeTypes.contains(PointType.DATUM_POINT))
			return Klein_Method_Adjustment_Step.FREE;

		return Klein_Method_Adjustment_Step.UNDEFINED;
	}

	public boolean recompute(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
			List<String> outAdjustmentDetails, Map<String, Integer> outProcessedCounts) throws SQLException {
		if (parameter.getProcessPhase() == Klein_Method_Process_Phase.POINT_GROUP_CALIBRATION) {
			// Phase 1 laeuft GENAU EINMAL am Anfang des Laufs. Planen -> schreiben. Sind alle Gruppen im Band
			// 0,996-1,004, wird die letzte Korrektur noch geschrieben und dann zu Phase 2 gewechselt.
			Klein_Method_Dynamic_Plan plan = this.planPointGroupCalibrationStep(parameter, databaseHandler);
			parameter.setDynamicLiveText(buildDynamicLiveText(plan, true, false));
			// Stagnations-Abbruch - kein Fortschritt Richtung Band -> Lauf endet mit Meldung
			String stagnation = this.checkDynamicStagnation(parameter, plan);
			if (stagnation != null) {
				parameter.setDynamicStagnationDetails(stagnation);
				return false;
			}
			this.applyDynamicPlan(parameter, plan, databaseHandler, outAdjustmentDetails);
			if (outProcessedCounts != null)
				outProcessedCounts.put("Festpunkt-Gruppen", plan.getWriteCount());
			if (plan.isAllDone()) {
				// Sicherheitscheck: nach Phase 1 muss jeder Punkt einheitliche y0/x0/z0 haben.
				if (!parameter.isPointUniformityCheckDone()) {
					parameter.setPointUniformityCheckDone(true);
					String mismatch = this.checkPointComponentUniformity(databaseHandler);
					if (mismatch != null) {
						parameter.setPointUniformityMismatchDetails(mismatch);
						return false; // Automatik muss abbrechen, siehe Klein_Method_Iteration_Task
					}
				}
				parameter.setPointGroupCalibrationSettled(true);
				parameter.setProcessPhase(Klein_Method_Process_Phase.POINT_INDIVIDUAL_CALIBRATION);
				parameter.resetDynamicStagnation();
			}
			return true;
		}
		else if (parameter.getProcessPhase() == Klein_Method_Process_Phase.POINT_INDIVIDUAL_CALIBRATION) {
			// Phase 2 - einzelne Festpunkte, Turbo Variante b. Bewertungsgroesse ist T_prio ("A-priori bezogene
			// Teststatistik", Spalte t_prio), NICHT T_post. Sind alle Punkte fertig, endet der Lauf (DONE), ohne noch
			// zu schreiben.
			Klein_Method_Dynamic_Plan plan = this.planPointIndividualCalibrationStep(parameter, databaseHandler);
			parameter.setDynamicLiveText(buildDynamicLiveText(plan, false, parameter.isTurboEnabled()));
			String stagnation = this.checkDynamicStagnation(parameter, plan);
			if (stagnation != null) {
				parameter.setDynamicStagnationDetails(stagnation);
				return false;
			}
			this.applyDynamicPlan(parameter, plan, databaseHandler, outAdjustmentDetails);
			if (outProcessedCounts != null)
				outProcessedCounts.put("Festpunkte", plan.getWriteCount());
			if (plan.isAllDone()) {
				parameter.setPointIndividualCalibrationSettled(true);
				parameter.setProcessPhase(Klein_Method_Process_Phase.DONE);
			}
			return true;
		}
		else if (parameter.getProcessPhase() == Klein_Method_Process_Phase.GROUP_CALIBRATION) {
			boolean allGroupsCalibrated = this.performGroupCalibrationStep(parameter, databaseHandler, outAdjustmentDetails);
			if (allGroupsCalibrated)
				parameter.setProcessPhase(Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION);
			return true;
		}
		else if (parameter.getProcessPhase() == Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION) {
			// Die Automatik brach dadurch immer nach 2 Iterationen ab (1x Gruppenkalibrierung/Phasenwechsel, 1x
			// Individualkalibrierung), obwohl noch weitere Beobachtungen im Bearbeitungsbereich lagen. erst pruefen,
			// ob der (zu Beginn dieser Iteration frisch gelesene) Max-logPost-Wert schon unter dem Grenzwert liegt -
			// wenn ja, DONE; wenn nein, in INDIVIDUAL_CALIBRATION bleiben, damit die naechste Iteration erneut
			// korrigiert (der Bearbeitungsbereich verschiebt sich dabei automatisch mit dem dann niedrigeren
			// Max-logPost, siehe getMaxLogPostThreshold()/Bandbreite).
			if (this.isLogPostBelowThreshold(parameter)) {
				parameter.setProcessPhase(Klein_Method_Process_Phase.DONE);
				// die einmalige Abschluss-Kompression laeuft GENAU HIER, an der einzigen Stelle, an der die Automatik
				// fuer BEOBACHTUNGEN von INDIVIDUAL_CALIBRATION nach DONE wechselt (dieser Zweig wird wegen der
				// Bedingung oben nur EINMAL pro Lauf erreicht - siehe Klassen-kommentar von
				// performFloorEnforcementCompression() fuer die volle Begruendung, warum das keine Wiederholung/kein
				// Abdriften ausloest). mit Kaskaden-Mehrfachlauf NUR am Ende von Durchlauf 1 (als Vorbereitung fuer
				// Durchlauf 2). Ein DONE in Durchlauf 2 oder spaeter beendet die Berechnung - dort wuerde die
				// Erzwingung das Ergebnis wieder loeschen.
				if (parameter.isFloorEnforcementEnabled()
						&& (!parameter.isCascadeDoubleRunEnabled() || parameter.getCascadePassCount() == 0))
					this.performFloorEnforcementCompression(parameter, databaseHandler, outAdjustmentDetails);
				return true;
			}

			boolean ceilingExceeded = this.performIndividualCalibrationStep(parameter, databaseHandler, outAdjustmentDetails, outProcessedCounts);
			if (ceilingExceeded) {
				parameter.setIndividualUncertaintyCeilingExceeded(true);
				return false; // Automatik muss abbrechen, siehe Klein_Method_Iteration_Task
			}
			// in JEDER Iteration > 0 des Passes (= jede Iteration dieser Phase) werden sigma_a/b/c ALLER Typen anhand
			// der reellen, soeben berechneten a-posteriori Varianzfaktoren nachgeregelt. Die Wirkung beider
			// Korrekturen zeigt die naechste JAG3D-Ausgleichung. nur noch bei Schalter "Gruppen-Dämpfung in Phase 2:
			// an". Standard "aus" = Verhalten (Gruppenwerte waehrend Phase 2 fest). Befund aus dem Langprotokoll: die
			// gleichzeitige Nachregelung trieb sigma_a von 20,4 mm bis an die Untergrenze 0,5 mm und liess es danach
			// zwischen 0,5 und 2,5 mm pendeln (F zwischen 0,39 und 2,97) - synchron zum Sprung von Max-logPost von
			// ~3-5 auf 145-606. nach der Herabsetzung auf die Untergrenze (Schalter "Gruppen-sigma nach Durchlauf 1
			// auf Untergrenze") bleibt die Gruppe fest - auch die Gruppen-Daempfung in Phase 2 darf sie dann nicht
			// wieder anheben.
			if (parameter.isGroupDampingInPhase2Enabled() && !parameter.isGroupFloorLockActive()) {
				this.performGroupDampingStep(parameter, databaseHandler, outAdjustmentDetails);
				databaseHandler.commit();
			}
			// bewusst NICHT auf DONE setzen - naechste Iteration liest frisch und
			// prueft oben erneut, ob der Grenzwert inzwischen erreicht ist.
			return true;
		}
		return true; // DONE - fuer diese Iteration nichts mehr zu tun
	}

	private boolean performGroupCalibrationStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
		return this.phase1Part.performGroupCalibrationStep(parameter, databaseHandler, outAdjustmentDetails);
	}

	public int applyGroupUncertaintyFloors(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
		return this.phase1Part.applyGroupUncertaintyFloors(parameter, databaseHandler, outAdjustmentDetails);
	}

	private void performGroupDampingStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
		this.phase1Part.performGroupDampingStep(parameter, databaseHandler, outAdjustmentDetails);
	}

	private boolean performIndividualCalibrationStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails, Map<String, Integer> outProcessedCounts) throws SQLException {
		return this.phase2Part.performIndividualCalibrationStep(parameter, databaseHandler, outAdjustmentDetails, outProcessedCounts);
	}

	// zwei kleine, rein statische Schluessel-Erzeugungshelfer - GARANTIEREN, dass Registrierung
	// (performFloorEnforcementCompression(), unten) und Pruefung (performIndividual CalibrationStep(), oben) IMMER
	// exakt dasselbe Schluessel-Format verwenden.
	// Format: "OBS:<observationId>" fuer Nicht-GNSS, "GNSS:<observationId>:<achse>" fuer GNSS (beide Zeilentypen -
	// Klein_Method_Ep_Row UND Klein_Method_Observation_Uncertainty_Row - liefern denselben GnssAxis-Enum-Typ ueber
	// getAxis(), daher genuegt EINE gemeinsame GNSS-Ueberladung fuer beide Stellen).
	private static String buildClampRegistryKey(int observationId) {
		return "OBS:" + observationId;
	}

	private static String buildClampRegistryKey(int observationId, Klein_Method_Observation_Uncertainty_Row.GnssAxis axis) {
		return "GNSS:" + observationId + ":" + axis;
	}

	private void performFloorEnforcementCompression(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
		this.phase2Part.performFloorEnforcementCompression(parameter, databaseHandler, outAdjustmentDetails);
	}

	public String performEpSingleStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
		return this.epPart.performEpSingleStep(parameter, databaseHandler, outAdjustmentDetails);
	}

	/**
	 * Liefert den groessten der aktuellen logPost-Werte ALLER logPost-Typen (dynamisch aus
	 * valuesOfCategory(LOG_POST), siehe LOG_POST_TYPES_FOR_GROUP_CALIBRATION) - wird laut Vorgabe fuer die
	 * Fortschrittsanzeige UND den Bearbeitungsbereich der individuellen Kalibrierung verwendet.
	 *
	 * Dynamisch, automatisch konsistent mit kuenftigen weiteren Typen.
	 */
	public double getMaxLogPostValue(Klein_Method_Parameter parameter) {
		double max = 0.0;
		for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION)
			max = Math.max(max, parameter.getEvaluationValue(type));
		return max;
	}

	// BEWUSST NICHT umgesetzt. Der PHASE:DONE-Uebergang (isThresholdReached()/isLogPostBelowThreshold() unten) bleibt
	// unveraendert an den je-Typ-Grenzwerten (getThresholdValue(), Standard und HART ERZWUNGENER MINDESTWERT 2.45,
	// siehe Klein_Method_Threshold_Type.getMinimumAllowedValue()) verankert - NICHT an einer neuen, niedrigeren
	// 1.0-Schranke.
	/**
	 * Liefert den groessten der 4 aktuellen logPost-Grenzwerte - dient als
	 * "gruene" Zielmarke fuer den Fortschrittsbalken (in der Regel sind
	 * alle 4 gleich, z.B. 2.45, koennen aber einzeln abweichen).
	 */
	public double getMaxLogPostThreshold(Klein_Method_Parameter parameter) {
		double horizontalDirection = parameter.getThresholdValue(Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST);
		double horizontalDistance = parameter.getThresholdValue(Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST);
		double verticalDirection = parameter.getThresholdValue(Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST);
		double slopeDistance = parameter.getThresholdValue(Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST);
		return Math.max(Math.max(horizontalDirection, horizontalDistance), Math.max(verticalDirection, slopeDistance));
	}

	/**
	 * Voraussetzung fuer die Auswaehlbarkeit der Bewertungsmethode EP:
	 * der aktuelle Max-logPost-Wert muss UNTER dem zugehoerigen Grenzwert
	 * liegen (Standard 2.45). Siehe Klein_Method_Tab_Builder, wo dies bei
	 * jeder Iteration und jeder Grenzwert-Aenderung erneut geprueft wird.
	 */
	// Faktor fuer den logarithmischen Korrekturschritt der individuellen Kalibrierung (1,0 = volle Ur-Formel); nur der
	// Nachlauf verkleinert ihn (Daempfung gegen Pendeln), sonst immer 1,0
	private volatile double individualStepDamping = 1.0;

	public double getIndividualStepDamping() {
		return this.individualStepDamping;
	}

	public void setIndividualStepDamping(double damping) {
		this.individualStepDamping = Double.isFinite(damping) ? Math.max(0.0, Math.min(1.0, damping)) : 1.0;
	}

	/** true, wenn der Max-logPost auf FOLLOW_UP_TOLERANCE_DECIMALS gerundet nicht ueber dem Grenzwert liegt. */
	public boolean isLogPostWithinFollowUpTolerance(Klein_Method_Parameter parameter) {
		double current = this.getMaxLogPostValue(parameter), threshold = this.getMaxLogPostThreshold(parameter);
		if (!Double.isFinite(current) || !Double.isFinite(threshold))
			return false;
		double scale = Math.pow(10.0, Klein_Method_Default_Values.FOLLOW_UP_TOLERANCE_DECIMALS);
		return Math.round(current * scale) <= Math.round(threshold * scale);
	}

	public boolean isLogPostBelowThreshold(Klein_Method_Parameter parameter) {
		// isValueAvailable() unterscheidet echten, aus der Datenbank gelesenen Wert von der reinen Vorbelegung (siehe
		// Klein_Method_Parameter.setCurrentValue()) - das ist dieselbe, bereits bestehende Weiche wie bei der
		// ehrlichen "k.A."/"n/v (GNSS)"-Anzeige im Tab_Builder, hier nur zusaetzlich fuer die Start-Schutzweiche
		// ausgewertet. BEWUSST NICHT als Vergleich "Wert == 0.000000" umgesetzt - ein exakter
		// Fliesskomma-Gleichheitsvergleich waere fragil und wuerde einen echten, tatsaechlich gelesenen Wert von
		// genau 0.0 falsch behandeln.
		boolean anyValueRead = false;
		for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
			if (parameter.isValueAvailable(type)) {
				anyValueRead = true;
				break;
			}
		}
		if (!anyValueRead)
			return false; // noch nie gelesen -> "noch nicht unterschritten", NICHT "bereits erreicht"

		return this.getMaxLogPostValue(parameter) < this.getMaxLogPostThreshold(parameter);
	}

	// KEIN Ersatz noetig - dead code, konsequent mit entfernt statt liegengelassen.

	public double computeLogPostBandLowerBound(double maxLogPostValue, double thresholdValue, double funnelFactor) {
		return this.phase2Part.computeLogPostBandLowerBound(maxLogPostValue, thresholdValue, funnelFactor);
	}

	public double computeCurrentLogPostEtappeMin(Klein_Method_Parameter parameter) {
		return this.phase2Part.computeCurrentLogPostEtappeMin(parameter);
	}

	public String formatCurrentGnssGroupUncertaintyStatus(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler) {
		return this.phase2Part.formatCurrentGnssGroupUncertaintyStatus(parameter, databaseHandler);
	}

	public boolean isWithinLogPostBand(double observationLogPostValue, double bandLowerBound, double maxLogPostValue) {
		return this.phase2Part.isWithinLogPostBand(observationLogPostValue, bandLowerBound, maxLogPostValue);
	}

	public double computeGroupUncertainty(double oldValue, double varianceRatio, double floorValue, double bandMin, double bandMax) {
		return this.phase1Part.computeGroupUncertainty(oldValue, varianceRatio, floorValue, bandMin, bandMax);
	}

	public boolean isVarianceRatioInDampingCorridor(double varianceRatio, double bandMin, double bandMax) {
		return this.phase1Part.isVarianceRatioInDampingCorridor(varianceRatio, bandMin, bandMax);
	}

	// ====================================================================== Festpunktanalyse
	// Phase 1 (POINT_GROUP_CALIBRATION) - genau einmal am Anfang des Laufs: je Punktgruppe EIN gemeinsamer Wert (y =
	// x = z), bewertet ueber die VKS F der Punktdimension aus JAG3D (1D/2D/3D), ohne Turbo:
	// sigma_neu = sigma_alt * max(1 + log10(F); 0,3) ALLE Gruppen werden in jeder Iteration weiter Richtung 1,0
	// korrigiert, auch im Band. Ende, sobald alle Gruppen gleichzeitig im Band 0,996-1,004 liegen (oder an der
	// Untergrenze mit F < 0,996); die letzte Korrektur wird noch geschrieben, danach Phase 2.
	// Phase 2 (POINT_INDIVIDUAL_CALIBRATION) - einzelne Festpunkte, Ziel
	// T_prio = 1,000, EIN gemeinsamer Wert je Punkt:
	// sigma_neu = sigma_alt * max(1 + Turbo * log10(W); 0,3), W = max(T_prio; 0,5)
	// Fertig: T_prio im Band ODER T_prio <= 0,000001 ODER (sigma an der Untergrenze UND T_prio < 0,996). Ende, sobald
	// alle Punkte gleichzeitig fertig sind (dann wird nichts mehr geschrieben). Turbo (Variante b): ab ceil(50 %)
	// fertigen Punkten, nur bei T_prio < 0,996 (Verkleinern) - Vorhersage aus den letzten zwei Iterationen, Rueckfall
	// stufenloser Zuschlag; Ueberschwing-Sperre (1. Richtungswechsel: 5 Schritte eingefroren mit normaler Formel, 2.
	// Wechsel: gesperrt).
	// Beide Phasen: Sprungwert (fest 1D bzw. 2D/3D) -> Untergrenze 0,0001 mm, Klemmung [Untergrenze; Obergrenze].
	// Alles wird zuerst als Klein_Method_Dynamic_Plan berechnet, dann geschrieben. Debug-Fenster und Option
	// "Vorhersage: alle Punkte und Gruppen" entfernt; eingefroren = normale Formel (keine Interpolation mehr).
	// ======================================================================

	/**
	 * eine Zeile fuer das Live-Feld im Tab, aus dem gerade ausgewerteten Plan. Phase 1: "Phase 1 | Gruppen fertig d/n
	 * | F x" Phase 2: "Phase 2 | fertig d/n | T_prio min x (Punkt) / max y (Punkt) | Turbo ..." (min/max ohne Punkte
	 * mit T_prio <= 0,000001, z. B. an der Untergrenze)
	 */
	static String buildDynamicLiveText(Klein_Method_Dynamic_Plan plan, boolean phase1, boolean turboEnabled) {
		int done = 0, total = 0;
		double min = Double.NaN, max = Double.NaN;
		String minName = "", maxName = "";
		List<String> fValues = new ArrayList<String>();
		for (Klein_Method_Dynamic_Plan.Entry e : plan.getEntries()) {
			total++;
			if (e.done)
				done++;
			if (Double.isNaN(e.value))
				continue;
			if (phase1) {
				fValues.add((plan.getEntries().size() > 1 ? e.name + ": " : "") + Klein_Method_Dynamic_Plan.num(e.value, 4));
				continue;
			}
			if (e.value <= Klein_Method_Default_Values.DYNAMIC_TPOST_ZERO)
				continue;
			if (Double.isNaN(min) || e.value < min) {
				min = e.value;
				minName = e.name;
			}
			if (Double.isNaN(max) || e.value > max) {
				max = e.value;
				maxName = e.name;
			}
		}
		if (phase1)
			return "Phase 1 | Gruppen fertig " + done + "/" + total + " | F " + (fValues.isEmpty() ? "\u2013" : String.join(" / ", fValues));
		int required = (int) Math.ceil(Klein_Method_Default_Values.TURBO_ACTIVATION_FRACTION * total);
		String turbo = !turboEnabled ? "Turbo aus" : (done >= required ? "Turbo aktiv" : "Turbo ab " + required);
		String range = Double.isNaN(min) ? "T_prio \u2013"
				: "T_prio min " + Klein_Method_Dynamic_Plan.num(min, 4) + " (" + minName + ") / max "
						+ Klein_Method_Dynamic_Plan.num(max, 4) + " (" + maxName + ")";
		return "Phase 2 | fertig " + done + "/" + total + " | " + range + " | " + turbo;
	}

	/** festes Zielband der dynamischen AGL, Grenzen einschliesslich. */
	public static boolean isInDynamicBand(double value) {
		return !Double.isNaN(value)
				&& value >= Klein_Method_Default_Values.DYNAMIC_BAND_MIN
				&& value <= Klein_Method_Default_Values.DYNAMIC_BAND_MAX;
	}

	/**
	 * stufenloser Turbo-Zuschlag (Rueckfall) - lineare Interpolation zwischen den Stuetzpunkten 0,5->1,5 / 0,7->1,4 /
	 * 0,8->1,3 / 0,9->1,2 / 0,97->1,1; T_prio <= 0,5 -> 1,5; T_prio >= 0,97 -> 1,0 (aus).
	 */
	public static double computeDynamicTurboAddon(double tpost) {
		double[][] support = Klein_Method_Default_Values.DYNAMIC_TURBO_SUPPORT_POINTS;
		if (Double.isNaN(tpost) || tpost >= Klein_Method_Default_Values.DYNAMIC_TURBO_ADDON_LIMIT)
			return 1.0;
		if (tpost <= support[0][0])
			return support[0][1];
		for (int i = 0; i < support.length - 1; i++) {
			double a = support[i][0], va = support[i][1];
			double b = support[i + 1][0], vb = support[i + 1][1];
			if (tpost >= a && tpost <= b)
				return va + (vb - va) * (tpost - a) / (b - a);
		}
		return 1.0;
	}

	/**
	 * gemeinsamer Wert eines Punkts bzw. einer Gruppe (y = x = z) aus den gelesenen Komponenten: 1D -> z, 2D/3D -> y
	 * (x/z sind nach Klein_Method gleich; weichen sie ab, wird das im Plan vermerkt und beim Schreiben
	 * vereinheitlicht).
	 */
	private static double commonSigma(int dimension, Double sigmaY, Double sigmaX, Double sigmaZ) {
		Double value = dimension == 1 ? sigmaZ : sigmaY;
		if (value == null || Double.isNaN(value))
			value = dimension == 1 ? sigmaY : (sigmaX != null ? sigmaX : sigmaZ);
		return value == null ? Double.NaN : value;
	}

	/**
	 * Einzelwert eines Festpunkts (y = x = z; 1D -> z, 2D/3D -> y) oder 0, wenn keiner gesetzt ist (JAG3D verwendet
	 * dann den Gruppenwert).
	 */
	private static double individualSigma(Klein_Method_Point_Tprio_Row row) {
		Double value = row.getDimension() == 1 ? row.getSigmaZ0() : row.getSigmaY0();
		return value == null || Double.isNaN(value) || value <= 0.0 ? 0.0 : value;
	}

	private static boolean componentsDiffer(int dimension, Double sigmaY, Double sigmaX, Double sigmaZ) {
		double eps = 1e-12;
		if (dimension == 2)
			return sigmaY != null && sigmaX != null && Math.abs(sigmaY - sigmaX) > eps;
		if (dimension == 3)
			return sigmaY != null && sigmaX != null && sigmaZ != null
					&& (Math.abs(sigmaY - sigmaX) > eps || Math.abs(sigmaY - sigmaZ) > eps);
		return false;
	}

	/**
	 * Sprungwert (<=, nur beim Verkleinern), Untergrenze und Obergrenze auf einen neu berechneten Wert anwenden;
	 * gegriffene Begrenzungen werden an limits angehaengt.
	 */
	private static double applyPointLimits(double oldValue, double newValue, double jumpValue, double floor, double value,
			Klein_Method_Parameter.Klein_Method_Point_Turbo_State state, String name, StringBuilder limits) {
		double ceiling = Klein_Method_Default_Values.POINT_UNCERTAINTY_CEILING;
		if (state != null && !state.jumpLocked && jumpValue > floor && isAtPointFloor(oldValue, floor) && value > Klein_Method_Default_Values.DYNAMIC_BAND_MAX) {
			state.jumpLocked = true;
			appendLimit(limits, "Ruecksprung von der Untergrenze auf den Sprungwert " + Klein_Method_Dynamic_Plan.mm(jumpValue)
					+ " mm (danach kein Sprung mehr in diesem Lauf)");
			new Klein_Method_Logger().log("Klein_Method: " + name + " - Ruecksprung von der Untergrenze auf " + Klein_Method_Dynamic_Plan.mm(jumpValue)
					+ " mm, Wert " + Klein_Method_Dynamic_Plan.num(value, 6));
			return jumpValue;
		}
		// Sprung EINSCHLIESSLICH der Grenze (<=), nur beim Verkleinern.
		boolean jumpAllowed = (state == null || !state.jumpLocked) && value < Klein_Method_Default_Values.DYNAMIC_BAND_MIN;
		if (jumpAllowed && newValue <= jumpValue && newValue < oldValue && newValue > floor) {
			if (limits.length() > 0)
				limits.append("; ");
			limits.append("Sprungwert ").append(Klein_Method_Dynamic_Plan.mm(jumpValue)).append(" mm erreicht/unterschritten -> Untergrenze ")
					.append(Klein_Method_Dynamic_Plan.mm(floor)).append(" mm");
			newValue = floor;
		}
		if (newValue < floor) {
			if (limits.length() > 0)
				limits.append("; ");
			limits.append("Untergrenze ").append(Klein_Method_Dynamic_Plan.mm(floor)).append(" mm");
			newValue = floor;
		}
		if (newValue > ceiling) {
			if (limits.length() > 0)
				limits.append("; ");
			limits.append("Obergrenze ").append(Klein_Method_Dynamic_Plan.mm(ceiling)).append(" mm");
			newValue = ceiling;
		}
		return newValue;
	}

	private static boolean isAtPointFloor(double sigma, double floor) {
		return sigma <= floor * (1.0 + 1e-9);
	}

	public Klein_Method_Dynamic_Plan planPointGroupCalibrationStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		List<String> heights = databaseHandler.calibrateSeparateHeights();
		Klein_Method_Dynamic_Plan plan = this.dynamicPart.planPointGroupCalibrationStep(parameter, databaseHandler);
		addSeparateHeightLines(plan, heights);
		return plan;
	}

	public Klein_Method_Dynamic_Plan planPointIndividualCalibrationStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		List<String> heights = databaseHandler.calibrateSeparateHeights();
		Klein_Method_Dynamic_Plan plan = this.dynamicPart.planPointIndividualCalibrationStep(parameter, databaseHandler);
		addSeparateHeightLines(plan, heights);
		return plan;
	}

	private static void addSeparateHeightLines(Klein_Method_Dynamic_Plan plan, List<String> heights) {
		if (heights == null || heights.isEmpty())
			return;
		plan.getHeaderLines().add("Hoehe nur aus 1D: Hoehe der 3D-Anschlusspunkte getrennt kalibriert (T_z = v_z^2 / (sigma_z^2 * r_z), Band "
				+ Klein_Method_Dynamic_Plan.num(Klein_Method_Default_Values.SEPARATE_HEIGHT_BAND_MIN, 2) + " - "
				+ Klein_Method_Dynamic_Plan.num(Klein_Method_Default_Values.SEPARATE_HEIGHT_BAND_MAX, 2) + ", Untergrenze "
				+ Klein_Method_Dynamic_Plan.mm(Klein_Method_Default_Values.SEPARATE_HEIGHT_FLOOR) + " mm, Obergrenze "
				+ Klein_Method_Dynamic_Plan.mm(org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler.HEIGHT_FREE_SIGMA) + " mm)");
		plan.getHeaderLines().addAll(heights);
	}

	/** Ergebnis eines Rechenschritts (Faktor, Modus, Formel) fuer Gruppe oder Punkt. */
	private static final class DynamicStep {
		double factor;
		boolean turboUsed;
		String mode;
		String formula;
		double reaction = Double.NaN;
	}

	/**
	 * gemessene Reaktion p = -(ln Wert - ln Wert_vorher) / (ln sigma - ln sigma_vorher) aus den letzten zwei
	 * Iterationen, oder NaN (keine Historie, sigma unveraendert).
	 */
	private static double measureReaction(Klein_Method_Parameter.Klein_Method_Point_Turbo_State state, double sigma, double value) {
		if (Double.isNaN(state.lastSigma) || Double.isNaN(state.lastTpost) || state.lastSigma <= 0.0 || sigma <= 0.0
				|| state.lastTpost <= Klein_Method_Default_Values.DYNAMIC_TPOST_ZERO || value <= Klein_Method_Default_Values.DYNAMIC_TPOST_ZERO)
			return Double.NaN;
		double dlnSigma = Math.log(sigma) - Math.log(state.lastSigma);
		if (Math.abs(dlnSigma) < 1e-9)
			return Double.NaN;
		double reaction = -(Math.log(value) - Math.log(state.lastTpost)) / dlnSigma;
		return Double.isInfinite(reaction) ? Double.NaN : reaction;
	}

	/**
	 * Klammer der Intervallhalbierung fuer einen Festpunkt mit dem gerade ausgewerteten Paar (sigma, T_prio) fortschreiben;
	 * liefert das geometrische Mittel der Klammer oder NaN, solange keine gueltige Klammer besteht. Wird die Klammer enger
	 * als DYNAMIC_BRACKET_MIN_RATIO, ohne dass der Punkt ins Band kommt, hat sich das Netz verschoben - Klammer verwerfen.
	 */
	private static double updatePointBracket(Klein_Method_Parameter.Klein_Method_Point_Turbo_State state, double sigma, double t, boolean atFloor,
			double reaction) {
		if (!Double.isNaN(t) && t > 0.0 && sigma > 0.0) {
			if (Double.isNaN(state.minSigmaSeen) || sigma < state.minSigmaSeen) {
				state.minSigmaSeen = sigma;
				state.tAtMinSigma = t;
			}
			if (Double.isNaN(state.maxSigmaSeen) || sigma > state.maxSigmaSeen) {
				state.maxSigmaSeen = sigma;
				state.tAtMaxSigma = t;
			}
		}
		if (Double.isNaN(t) || !(sigma > 0.0) || isInDynamicBand(t))
			return Double.NaN;
		if (t > Klein_Method_Default_Values.DYNAMIC_BAND_MAX) {
			state.bracketSigmaTooSmall = Double.isNaN(state.bracketSigmaTooSmall) ? sigma : Math.max(state.bracketSigmaTooSmall, sigma);
			if (!Double.isNaN(state.bracketSigmaTooLarge) && state.bracketSigmaTooLarge <= state.bracketSigmaTooSmall)
				state.bracketSigmaTooLarge = Double.NaN;
		} else if (!atFloor) {
			state.bracketSigmaTooLarge = Double.isNaN(state.bracketSigmaTooLarge) ? sigma : Math.min(state.bracketSigmaTooLarge, sigma);
			if (!Double.isNaN(state.bracketSigmaTooSmall) && state.bracketSigmaTooSmall >= state.bracketSigmaTooLarge)
				state.bracketSigmaTooSmall = Double.NaN;
		}
		if (Double.isNaN(state.bracketSigmaTooSmall) || Double.isNaN(state.bracketSigmaTooLarge))
			return Double.NaN;
		if (state.bracketSigmaTooLarge / state.bracketSigmaTooSmall < Klein_Method_Default_Values.DYNAMIC_BRACKET_MIN_RATIO) {
			// sigma ist auf 0,1 % bestimmt: liegt T_prio nahe 1, gilt der Punkt als fertig (die Restabweichung kommt aus dem
			// uebrigen Netz), sonst Klammer verwerfen
			// unempfindlich: ueber den ganzen bisher abgesuchten Bereich (mindestens Faktor 1,5 in sigma) aendert sich T_prio
			// kaum (|p| < DYNAMIC_INSENSITIVE_REACTION) - die einzelne Reaktion p ist bei kleinen Schritten zu unruhig
			double range = state.maxSigmaSeen / state.minSigmaSeen;
			double longReaction = range >= Klein_Method_Default_Values.DYNAMIC_BRACKET_SETTLE_MIN_RANGE
					? Math.abs(Math.log(state.tAtMinSigma / state.tAtMaxSigma)) / Math.log(range) : Double.NaN;
			if (Math.abs(Math.log(t)) <= Klein_Method_Default_Values.DYNAMIC_BRACKET_SETTLE_LN && !Double.isNaN(longReaction)
					&& longReaction < Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_REACTION) {
				state.bracketSettled = true;
				new Klein_Method_Logger().log("Klein_Method: Festpunkt - Intervall ausgeschoepft bei sigma " + Klein_Method_Dynamic_Plan.mm(sigma)
						+ " mm, T_prio " + Klein_Method_Dynamic_Plan.num(t, 6) + " - fertig.");
				return Double.NaN;
			}
			state.bracketSigmaTooSmall = Double.NaN;
			state.bracketSigmaTooLarge = Double.NaN;
			return Double.NaN;
		}
		return Math.sqrt(state.bracketSigmaTooSmall * state.bracketSigmaTooLarge);
	}

	private static void updateDynamicState(Klein_Method_Parameter.Klein_Method_Point_Turbo_State state, double sigmaOld, double sigmaNew,
			double value, boolean turboUsed, boolean outside) {
		double lnStep = Math.log(sigmaNew / sigmaOld);
		state.lastSigma = sigmaOld;
		state.lastTpost = value;
		state.lastDirection = Double.isNaN(lnStep) || Math.abs(lnStep) < 1e-12 ? 0 : (lnStep < 0 ? -1 : 1);
		state.lastStepTurbo = turboUsed;
		state.lastOutsideBand = outside;
	}

	private String checkDynamicStagnation(Klein_Method_Parameter parameter, Klein_Method_Dynamic_Plan plan) {
		return this.dynamicPart.checkDynamicStagnation(parameter, plan);
	}

	private static void appendLimit(StringBuilder limits, String text) {
		if (limits.length() > 0)
			limits.append("; ");
		limits.append(text);
	}

	public void applyDynamicPlan(Klein_Method_Parameter parameter, Klein_Method_Dynamic_Plan plan, Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
		this.dynamicPart.applyDynamicPlan(parameter, plan, databaseHandler, outAdjustmentDetails);
	}

	public String checkPointComponentUniformity(Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		return this.dynamicPart.checkPointComponentUniformity(databaseHandler);
	}

	public boolean isGroupUncertaintyCalibrated(double varianceRatio, double currentValue, double floorValue, double bandMin, double bandMax) {
		return this.phase1Part.isGroupUncertaintyCalibrated(varianceRatio, currentValue, floorValue, bandMin, bandMax);
	}

	public double computeIndividualUncertainty(double baseValue, double absLogPostValue) {
		return this.phase2Part.computeIndividualUncertainty(baseValue, absLogPostValue);
	}

	public double computeIndividualUncertaintyBasis(Klein_Method_Threshold_Type type, double sigmaA, double sigmaB, double sigmaC, double distance) {
		return this.phase2Part.computeIndividualUncertaintyBasis(type, sigmaA, sigmaB, sigmaC, distance);
	}

	public boolean isIndividualUncertaintyCeilingExceeded(double newValue, double ceilingValue) {
		return this.phase2Part.isIndividualUncertaintyCeilingExceeded(newValue, ceilingValue);
	}

	public Klein_Method_Data_Status determineDataStatus(Klein_Method_Parameter parameter) {
		return this.tabCheckPart.determineDataStatus(parameter);
	}

	public List<String> validateGroupUncertaintiesOnTabActivation(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		return this.tabCheckPart.validateGroupUncertaintiesOnTabActivation(parameter, databaseHandler);
	}

	public List<String> validateAdditionalParameterConsistencyOnTabActivation(Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		return this.tabCheckPart.validateAdditionalParameterConsistencyOnTabActivation(databaseHandler);
	}

	/**
	 * Rechnet einen Rohwert aus der JAG3D-Datenbank (Radiant bei Winkeln,
	 * Meter bei Strecken, unitless bei ppm-Werten - bestaetigt anhand der
	 * echten JAG3D-Quelldateien AngleUnit.java/LengthUnit.java/ScaleUnit.java:
	 * jeweils "conversionFactorToRadian"/"conversionFactorToMeter" = 1.0 fuer
	 * die Pivot-Einheit) in unsere Arbeitseinheit um (mgon/gon/mm/m/ppm - das,
	 * womit Klein_Method intern rechnet und was angezeigt/protokolliert wird).
	 *
	 * WICHTIG: dies ist die EINZIGE Stelle in Klein_Method, an der Rohwerte
	 * aus der Datenbank in Kontakt mit unseren Formeln kommen (siehe
	 * performGroupCalibrationStep()/performIndividualCalibrationStep()) -
	 * die Umrechnung findet BEWUSST hier statt (nicht im Database_Handler),
	 * da hier der Beobachtungstyp bereits bekannt ist.
	 */
	private double fromDatabaseValue(double rawValue, String workingUnit) {
		switch (workingUnit) {
		case "mgon": return AngleUnit.MILLIGRADIAN.fromRadian(rawValue);
		case "gon":  return AngleUnit.GRADIAN.fromRadian(rawValue);
		case "mm":   return LengthUnit.MILLIMETER.fromMeter(rawValue);
		case "m":    return LengthUnit.METER.fromMeter(rawValue); // Faktor 1.0, der Vollstaendigkeit halber
		case "ppm":  return ScaleUnit.PARTS_PER_MILLION_WRT_ZERO.fromUnitless(rawValue);
		default:     return rawValue;
		}
	}

	/**
	 * Kehrt fromDatabaseValue() um - rechnet einen Wert in unserer
	 * Arbeitseinheit (mgon/gon/mm/m/ppm) zurueck in den Rohwert, der in die
	 * JAG3D-Datenbank geschrieben werden muss (Radiant/Meter/unitless).
	 */
	/**
	 * Untergrenzen fuer die Schlussangleichung in Datenbankeinheiten: {Gruppe a, b, c (Grenzwerte Gruppenunsicherheit),
	 * individuell (Abschluss-Kompressions-Boden)} - die Schlussangleichung unterschreitet sie nie.
	 */
	public double[] finalScalingFloors(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type) {
		double[] f = new double[4];
		f[0] = this.toDatabaseValue(parameter.getGroupUncertaintyFloorA(type), Klein_Method_Default_Values.getSigmaAUnit(type));
		f[1] = this.toDatabaseValue(parameter.getGroupUncertaintyFloorB(type), Klein_Method_Default_Values.getSigmaBUnit(type));
		f[2] = this.toDatabaseValue(parameter.getGroupUncertaintyFloorC(type), Klein_Method_Default_Values.getSigmaCUnit(type));
		f[3] = this.toDatabaseValue(Klein_Method_Default_Values.getIndividualUncertaintyCompressionFloor(type), Klein_Method_Default_Values.getIndividualUncertaintyUnit(type));
		return f;
	}

	private double toDatabaseValue(double workingValue, String workingUnit) {
		switch (workingUnit) {
		case "mgon": return AngleUnit.MILLIGRADIAN.toRadian(workingValue);
		case "gon":  return AngleUnit.GRADIAN.toRadian(workingValue);
		case "mm":   return LengthUnit.MILLIMETER.toMeter(workingValue);
		case "m":    return LengthUnit.METER.toMeter(workingValue);
		case "ppm":  return ScaleUnit.PARTS_PER_MILLION_WRT_ZERO.toUnitless(workingValue);
		default:     return workingValue;
		}
	}

	/**
	 * liest die A-PRIORI-Koordinaten (y0/x0/z0, aus "PointApriori") ALLER aktiven Punkte - bewusst a-priori, nicht
	 * a-posteriori, da dieser Scan VOR dem ersten Ausgleichungslauf laufen soll (a-posteriori-Werte gibt es dann noch
	 * nicht) - und meldet jeden Punktnamen, der MEHRFACH vorkommt (z.B. in mehreren Punktgruppen) UND dessen Lagen
	 * weiter als parameter.getFangkreisRadius() auseinanderliegen. Nur 2D (Rechtswert/ Hochwert) - Hoehenunterschiede
	 * werden hier bewusst NICHT geprueft, da "Fangkreis" im geodaetischen Sprachgebrauch eine Lage-Groesse ist.
	 *
	 * FACHLICHE EINSCHRAENKUNG (bewusst, siehe Klein_Method_Parameter. FANGKREIS_RADIUS_DEFAULT fuer die
	 * vollstaendige Begruendung): dieser Scan WARNT nur (Rueckgabeliste an den Aufrufer, siehe
	 * Klein_Method_Tab_Builder.wireStartStopButtons()) - er veraendert NICHTS in der Datenbank, nummeriert keinen
	 * Punkt um und trennt keine Verknuepfung. Die Spalten "y0"/"x0"/"z0" in "PointApriori" sind NICHT unabhaengig aus
	 * JAG3D-Quellcode verifiziert, sondern nach demselben, bereits bestaetigten Namensschema wie
	 * "sigma_y0"/"sigma_x0"/"sigma_z0" in DERSELBEN Tabelle abgeleitet (siehe readAllPointAprioriCoordinatesFor
	 * FangkreisScan() in Klein_Method_Database_Handler) - ein rein LESENDER Zugriff, daher risikoarm selbst falls der
	 * Spaltenname doch abweicht (fuehrt dann zu SQLException, nicht zu falschen Daten).
	 *
	 * @return Liste fertig formatierter Warnzeilen (leer = keine Verletzung gefunden bzw. zu wenige Koordinaten zum
	 * Vergleich vorhanden)
	 */
	// traegt zu jeder Warnzeile die konkrete "PointApriori"."id" mit, die bei bestaetigter Umbenennung angesprochen
	// werden muss (siehe Klein_Method_Tab_Builder). BEWUSSTE, offengelegte Vereinfachung: bei einem Paar wird IMMER
	// der SPAETERE der beiden verglichenen Datensaetze (rows.get(j), der in der a-priori-Namensreihenfolge zweite
	// Treffer) als Umbenennungs-Ziel vorgeschlagen - dies ist eine praktische Annahme ("der zweite Treffer ist eher
	// die versehentliche Doppelvergabe"), KEINE fachlich gesicherte Entscheidung, welcher der beiden Punkte
	// tatsaechlich falsch ist. Genau deshalb bleibt die Umbenennung an die explizite Anwender-Bestaetigung gebunden
	// (siehe renamePointForFangkreisViolation() in Klein_Method_Database_Handler) statt automatisch zu laufen.
	public static final class Klein_Method_Fangkreis_Violation {
		private final String message;
		private final int pointId;

		public Klein_Method_Fangkreis_Violation(String message, int pointId) {
			this.message = message;
			this.pointId = pointId;
		}

		public String getMessage() {
			return this.message;
		}

		public int getPointId() {
			return this.pointId;
		}
	}

	public List<Klein_Method_Fangkreis_Violation> scanForFangkreisViolations(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
		return this.tabCheckPart.scanForFangkreisViolations(parameter, databaseHandler);
	}

	// Klein_Method_Dynamic_Plan als eingebettete Klasse - damit das Paket wieder nur aus den bestehenden Dateien
	// besteht.
	/**
	 * EIN Rechenschritt der Festpunktanalyse (Phase 1 Punktgruppen bzw. Phase 2 einzelne Festpunkte), zuerst NUR
	 * berechnet, noch nicht geschrieben. Enthaelt je Gruppe/Punkt die Eingangswerte, die Formel mit eingesetzten
	 * Zahlen, die gegriffenen Begrenzungen und den Wert, der geschrieben wird.
	 *
	 * Ablauf (Klein_Method_Functions.recompute()): planen -> schreiben.
	 */
	public static class Klein_Method_Dynamic_Plan {

		/** Eine Zeile des Plans (eine Punktgruppe bzw. ein Festpunkt). */
		public static final class Entry {
			public boolean group;          // true = Punktgruppe (Phase 1), false = Festpunkt (Phase 2)
			public int id;                 // group_id bzw. Punkt-ID
			public String name;            // Anzeige
			public int dimension;          // 1/2/3
			public double sigmaOld;        // Meter, gemeinsamer Wert y = x = z
			public String valueLabel;      // "F" bzw. "T_prio"
			public double value;           // F bzw. T_prio (NaN = nicht vorhanden)
			public boolean inBand;
			public boolean done;
			public String mode = "normal";
			public String formula = "";
			public String limits = "";
			public double sigmaNew;        // Meter
			public double reaction = Double.NaN;
		public String sigmaSource = "-";
			public boolean write = true;
		}

		private final String phaseLabel;
		private final int cycle;
		private final List<String> headerLines = new ArrayList<String>();
		private final List<Entry> entries = new ArrayList<Entry>();
		private boolean allDone = false;
		private boolean finalStep = false;

		public Klein_Method_Dynamic_Plan(String phaseLabel, int cycle) {
			this.phaseLabel = phaseLabel;
			this.cycle = cycle;
		}

		public String getPhaseLabel() {
			return this.phaseLabel;
		}

		public int getCycle() {
			return this.cycle;
		}

		public List<String> getHeaderLines() {
			return this.headerLines;
		}

		public List<Entry> getEntries() {
			return this.entries;
		}

		public boolean isAllDone() {
			return this.allDone;
		}

		public void setAllDone(boolean allDone) {
			this.allDone = allDone;
		}

		/** true = letzter Schritt des Laufs (Phase 2 fertig) - es wird nichts mehr geschrieben. */
		public boolean isFinalStep() {
			return this.finalStep;
		}

		public void setFinalStep(boolean finalStep) {
			this.finalStep = finalStep;
		}

		public int getWriteCount() {
			int count = 0;
			for (Entry entry : this.entries)
				if (entry.write)
					count++;
			return count;
		}

		// ------------------------------------------------------------------
		// Formatierung (deutsches Zahlenformat, damit das Nachrechnen in Excel/
		// Notepad++ direkt mit Komma funktioniert)
		// ------------------------------------------------------------------

		public static String num(double value, int decimals) {
			if (Double.isNaN(value))
				return "k.A.";
			if (Double.isInfinite(value))
				return value > 0 ? "+unendlich" : "-unendlich";
			return String.format(Locale.GERMANY, "%." + decimals + "f", value);
		}

		/** Meter -> mm mit 5 Nachkommastellen. */
		public static String mm(double meter) {
			return num(meter * 1000.0, 5);
		}
	}

	// Umrechnungen fuer die Kartendarstellung. NUR fuer die Anzeige - die Ausgleichung nutzt nichts davon.
	// Transversale Mercatorprojektion (Krueger-Reihen 6. Ordnung, Karney 2011), UTM (GRS80, k0 = 0,9996) und
	// Gauss-Krueger (Bessel, k0 = 1, Streifen 3 Grad)
	// JAG3D-Rechtswert mit Zonen-/Streifenkennung: y = Zone * 1.000.000 + 500.000 + E
	// Datumsuebergang DHDN -> WGS84 (7 Parameter, EPSG 1777, Genauigkeit ca. 3 m)
	// Web-Mercator (OSM-Kacheln), Helmert 4 Parameter, konvexe Huelle
	public static final class Klein_Method_Geo {

		private Klein_Method_Geo() {
		}

		public static final double GRS80_A = 6378137.0;
		public static final double GRS80_F = 1.0 / 298.257222101;
		public static final double BESSEL_A = 6377397.155;
		public static final double BESSEL_F = 1.0 / 299.1528128;
		public static final double UTM_K0 = 0.9996;
		public static final double GK_K0 = 1.0;
		private static final double ARCSEC = Math.PI / (180.0 * 3600.0);

		/** Transversale Mercatorprojektion eines Ellipsoids (Krueger-Reihen). */
		public static final class TransverseMercator {
			private final double a, f, k0, e, n, bigA;
			private final double[] alpha = new double[7], beta = new double[7];

			public TransverseMercator(double a, double f, double k0) {
				this.a = a;
				this.f = f;
				this.k0 = k0;
				this.e = Math.sqrt(f * (2.0 - f));
				double n = f / (2.0 - f);
				this.n = n;
				double n2 = n * n, n3 = n2 * n, n4 = n3 * n, n5 = n4 * n, n6 = n5 * n;
				this.bigA = a / (1.0 + n) * (1.0 + n2 / 4.0 + n4 / 64.0 + n6 / 256.0);
				alpha[1] = n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800;
				alpha[2] = 13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360;
				alpha[3] = 61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440;
				alpha[4] = 49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600;
				alpha[5] = 34729 * n5 / 80640 - 3418889 * n6 / 1995840;
				alpha[6] = 212378941 * n6 / 319334400;
				beta[1] = n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800;
				beta[2] = n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720;
				beta[3] = 17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720;
				beta[4] = 4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600;
				beta[5] = 4583 * n5 / 161280 - 108847 * n6 / 3991680;
				beta[6] = 20648693 * n6 / 638668800;
			}

			/** Breite/Laenge [Grad] -> {Ost ab Mittelmeridian, Nord} [m]. */
			public double[] forward(double latDeg, double lonDeg, double lon0Deg) {
				double phi = Math.toRadians(latDeg);
				double lambda = Math.toRadians(lonDeg - lon0Deg);
				double tau = Math.tan(phi);
				double sigma = Math.sinh(this.e * atanh(this.e * tau / Math.sqrt(1.0 + tau * tau)));
				double tauP = tau * Math.sqrt(1.0 + sigma * sigma) - sigma * Math.sqrt(1.0 + tau * tau);
				double xiP = Math.atan2(tauP, Math.cos(lambda));
				double etaP = asinh(Math.sin(lambda) / Math.sqrt(tauP * tauP + Math.cos(lambda) * Math.cos(lambda)));
				double xi = xiP, eta = etaP;
				for (int j = 1; j <= 6; j++) {
					xi += alpha[j] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
					eta += alpha[j] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
				}
				return new double[] { this.k0 * this.bigA * eta, this.k0 * this.bigA * xi };
			}

			/** {Ost ab Mittelmeridian, Nord} [m] -> {Breite, Laenge} [Grad]. */
			public double[] inverse(double east, double north, double lon0Deg) {
				double xi = north / (this.k0 * this.bigA);
				double eta = east / (this.k0 * this.bigA);
				double xiP = xi, etaP = eta;
				for (int j = 1; j <= 6; j++) {
					xiP -= beta[j] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
					etaP -= beta[j] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
				}
				double sinhEtaP = Math.sinh(etaP);
				double cosXiP = Math.cos(xiP);
				double tauP = Math.sin(xiP) / Math.sqrt(sinhEtaP * sinhEtaP + cosXiP * cosXiP);
				double lambda = Math.atan2(sinhEtaP, cosXiP);
				double e2 = this.e * this.e;
				double tau = tauP;
				for (int i = 0; i < 8; i++) {
					double sigma = Math.sinh(this.e * atanh(this.e * tau / Math.sqrt(1.0 + tau * tau)));
					double tauI = tau * Math.sqrt(1.0 + sigma * sigma) - sigma * Math.sqrt(1.0 + tau * tau);
					double dTau = (tauP - tauI) / Math.sqrt(1.0 + tauI * tauI) * (1.0 + (1.0 - e2) * tau * tau)
							/ ((1.0 - e2) * Math.sqrt(1.0 + tau * tau));
					tau += dTau;
					if (Math.abs(dTau) < 1e-14)
						break;
				}
				return new double[] { Math.toDegrees(Math.atan(tau)), lon0Deg + Math.toDegrees(lambda) };
			}

			/** Punkt-Massstab der Abbildung (fuer die Pruefung des Helmert-Massstabs). */
			public double pointScale(double latDeg, double lonDeg, double lon0Deg) {
				double[] p0 = this.forward(latDeg, lonDeg, lon0Deg);
				double dLat = 1e-4;
				double[] p1 = this.forward(latDeg + dLat, lonDeg, lon0Deg);
				double gridDist = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
				double phi = Math.toRadians(latDeg);
				double e2 = this.e * this.e;
				double s = Math.sin(phi);
				double meridianRadius = this.a * (1.0 - e2) / Math.pow(1.0 - e2 * s * s, 1.5);
				return gridDist / (meridianRadius * Math.toRadians(dLat));
			}
		}

		public static final TransverseMercator UTM = new TransverseMercator(GRS80_A, GRS80_F, UTM_K0);
		public static final TransverseMercator GAUSS_KRUEGER = new TransverseMercator(BESSEL_A, BESSEL_F, GK_K0);

		private static double atanh(double x) {
			return 0.5 * Math.log((1.0 + x) / (1.0 - x));
		}

		private static double asinh(double x) {
			return Math.log(x + Math.sqrt(x * x + 1.0));
		}

		/** Zonen-/Streifennummer aus dem JAG3D-Rechtswert (0 = keine Kennung). */
		public static int zoneOfEasting(double easting) {
			return easting >= 1000000.0 ? (int) Math.floor(easting / 1000000.0) : 0;
		}

		public static double utmCentralMeridian(int zone) {
			return 6.0 * zone - 183.0;
		}

		public static int utmZoneOfLongitude(double lonDeg) {
			return Math.max(1, Math.min(60, (int) Math.floor((lonDeg + 180.0) / 6.0) + 1));
		}

		/** JAG3D-UTM (y mit Zone) -> {Breite, Laenge}, oder null ohne Zonenkennung. */
		public static double[] utmToLatLon(double y, double x) {
			int zone = zoneOfEasting(y);
			if (zone < 1 || zone > 60)
				return null;
			double east = y - zone * 1000000.0 - 500000.0;
			return UTM.inverse(east, x, utmCentralMeridian(zone));
		}

		/** {Breite, Laenge} -> JAG3D-UTM {y mit Zone, x} in der angegebenen Zone. */
		public static double[] latLonToUtm(double lat, double lon, int zone) {
			double[] en = UTM.forward(lat, lon, utmCentralMeridian(zone));
			return new double[] { zone * 1000000.0 + 500000.0 + en[0], en[1] };
		}

		/** JAG3D-Gauss-Krueger (y mit Streifen, DHDN) -> WGS84 {Breite, Laenge}, oder null. */
		public static double[] gaussKruegerToLatLon(double y, double x) {
			int strip = zoneOfEasting(y);
			if (strip < 1 || strip > 60)
				return null;
			double east = y - strip * 1000000.0 - 500000.0;
			double[] bessel = GAUSS_KRUEGER.inverse(east, x, 3.0 * strip);
			return dhdnToWgs84(bessel[0], bessel[1]);
		}

		/** WGS84 {Breite, Laenge} -> JAG3D-Gauss-Krueger {y mit Streifen, x} im angegebenen Streifen. */
		public static double[] latLonToGaussKrueger(double lat, double lon, int strip) {
			double[] bessel = wgs84ToDhdn(lat, lon);
			double[] en = GAUSS_KRUEGER.forward(bessel[0], bessel[1], 3.0 * strip);
			return new double[] { strip * 1000000.0 + 500000.0 + en[0], en[1] };
		}

		// DHDN -> WGS84, EPSG 1777 (Position-Vector-Konvention), Genauigkeit ca. 3 m
		private static final double[] DHDN_TO_WGS84 = { 598.1, 73.7, 418.2, 0.202 * ARCSEC, 0.045 * ARCSEC, -2.455 * ARCSEC, 6.7e-6 };

		public static double[] dhdnToWgs84(double lat, double lon) {
			double[] xyz = toCartesian(lat, lon, 0.0, BESSEL_A, BESSEL_F);
			double[] t = helmert7(xyz, DHDN_TO_WGS84, false);
			return toGeographic(t, GRS80_A, GRS80_F);
		}

		public static double[] wgs84ToDhdn(double lat, double lon) {
			double[] xyz = toCartesian(lat, lon, 0.0, GRS80_A, GRS80_F);
			double[] t = helmert7(xyz, DHDN_TO_WGS84, true);
			return toGeographic(t, BESSEL_A, BESSEL_F);
		}

		private static double[] helmert7(double[] p, double[] prm, boolean inverse) {
			double tx = prm[0], ty = prm[1], tz = prm[2], rx = prm[3], ry = prm[4], rz = prm[5], s = prm[6];
			if (!inverse) {
				double m = 1.0 + s;
				return new double[] {
						tx + m * (p[0] - rz * p[1] + ry * p[2]),
						ty + m * (rz * p[0] + p[1] - rx * p[2]),
						tz + m * (-ry * p[0] + rx * p[1] + p[2]) };
			}
			double m = 1.0 / (1.0 + s);
			double x = (p[0] - tx) * m, y = (p[1] - ty) * m, z = (p[2] - tz) * m;
			// Transponierte der (kleinen) Drehmatrix als Inverse
			return new double[] { x + rz * y - ry * z, -rz * x + y + rx * z, ry * x - rx * y + z };
		}

		private static double[] toCartesian(double latDeg, double lonDeg, double h, double a, double f) {
			double e2 = f * (2.0 - f);
			double phi = Math.toRadians(latDeg), lambda = Math.toRadians(lonDeg);
			double sp = Math.sin(phi);
			double bigN = a / Math.sqrt(1.0 - e2 * sp * sp);
			return new double[] { (bigN + h) * Math.cos(phi) * Math.cos(lambda), (bigN + h) * Math.cos(phi) * Math.sin(lambda),
					(bigN * (1.0 - e2) + h) * sp };
		}

		private static double[] toGeographic(double[] p, double a, double f) {
			double e2 = f * (2.0 - f);
			double lon = Math.atan2(p[1], p[0]);
			double r = Math.hypot(p[0], p[1]);
			double phi = Math.atan2(p[2], r * (1.0 - e2));
			for (int i = 0; i < 10; i++) {
				double sp = Math.sin(phi);
				double bigN = a / Math.sqrt(1.0 - e2 * sp * sp);
				double h = r / Math.cos(phi) - bigN;
				phi = Math.atan2(p[2], r * (1.0 - e2 * bigN / (bigN + h)));
			}
			return new double[] { Math.toDegrees(phi), Math.toDegrees(lon) };
		}

		// ---------------- Web-Mercator (OSM) ----------------
		public static final double TILE_SIZE = 256.0;
		public static final double MAX_WEB_MERCATOR_LAT = 85.05112878;

		/** {Breite, Laenge} -> Weltpixel bei Zoomstufe 0 (0..256). */
		public static double[] latLonToWorldPixel(double lat, double lon) {
			double la = Math.max(-MAX_WEB_MERCATOR_LAT, Math.min(MAX_WEB_MERCATOR_LAT, lat));
			double x = (lon + 180.0) / 360.0 * TILE_SIZE;
			double s = Math.sin(Math.toRadians(la));
			double y = (0.5 - Math.log((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI)) * TILE_SIZE;
			return new double[] { x, y };
		}

		/** Weltpixel bei Zoomstufe 0 -> {Breite, Laenge}. */
		public static double[] worldPixelToLatLon(double x, double y) {
			double lon = x / TILE_SIZE * 360.0 - 180.0;
			double m = Math.PI * (1.0 - 2.0 * y / TILE_SIZE);
			double lat = Math.toDegrees(Math.atan(Math.sinh(m)));
			return new double[] { lat, lon };
		}

		// ---------------- Helmert 4 Parameter ----------------
		/**
		 * Aehnlichkeitstransformation lokal (y Ost, x Nord) -> Ziel (Ost, Nord):
		 * E = a*y - b*x + c, N = b*y + a*x + d. Ab 2 Punkten; ab 3 ausgeglichen.
		 */
		public static final class Helmert2D {
			public final double a, b, c, d;
			public final double[] residuals; // je Punkt [m] (Zielsystem)
			public final double scale, rotation; // Massstab, Drehung [rad]

			private Helmert2D(double a, double b, double c, double d, double[] residuals) {
				this.a = a;
				this.b = b;
				this.c = c;
				this.d = d;
				this.residuals = residuals;
				this.scale = Math.hypot(a, b);
				this.rotation = Math.atan2(b, a);
			}

			/** null bei weniger als 2 Punkten oder zusammenfallenden Punkten. */
			public static Helmert2D fit(double[][] local, double[][] target) {
				int n = local.length;
				if (n < 2)
					return null;
				double my = 0, mx = 0, mE = 0, mN = 0;
				for (int i = 0; i < n; i++) {
					my += local[i][0];
					mx += local[i][1];
					mE += target[i][0];
					mN += target[i][1];
				}
				my /= n;
				mx /= n;
				mE /= n;
				mN /= n;
				double sxx = 0, sa = 0, sb = 0;
				for (int i = 0; i < n; i++) {
					double y = local[i][0] - my, x = local[i][1] - mx;
					double e = target[i][0] - mE, nn = target[i][1] - mN;
					sxx += y * y + x * x;
					sa += y * e + x * nn;
					sb += y * nn - x * e;
				}
				if (sxx < 1e-9)
					return null;
				double a = sa / sxx, b = sb / sxx;
				double c = mE - a * my + b * mx;
				double d = mN - b * my - a * mx;
				double[] res = new double[n];
				for (int i = 0; i < n; i++) {
					double e = a * local[i][0] - b * local[i][1] + c;
					double nn = b * local[i][0] + a * local[i][1] + d;
					res[i] = Math.hypot(e - target[i][0], nn - target[i][1]);
				}
				return new Helmert2D(a, b, c, d, res);
			}

			public double[] forward(double y, double x) {
				return new double[] { this.a * y - this.b * x + this.c, this.b * y + this.a * x + this.d };
			}

			public double[] inverse(double east, double north) {
				double q = this.a * this.a + this.b * this.b;
				double e = east - this.c, n = north - this.d;
				return new double[] { (this.a * e + this.b * n) / q, (-this.b * e + this.a * n) / q };
			}
		}

		// ---------------- Randpunkte ----------------
		/** Indizes der Punkte auf der konvexen Huelle (y Ost, x Nord), gegen den Uhrzeigersinn. */
		public static List<Integer> convexHull(double[][] pts) {
			int n = pts.length;
			List<Integer> result = new ArrayList<Integer>();
			if (n == 0)
				return result;
			Integer[] idx = new Integer[n];
			for (int i = 0; i < n; i++)
				idx[i] = i;
			java.util.Arrays.sort(idx, (p, q) -> pts[p][0] != pts[q][0] ? Double.compare(pts[p][0], pts[q][0]) : Double.compare(pts[p][1], pts[q][1]));
			if (n < 3) {
				for (Integer i : idx)
					result.add(i);
				return result;
			}
			int[] hull = new int[2 * n];
			int k = 0;
			for (int i = 0; i < n; i++) {
				while (k >= 2 && cross(pts[hull[k - 2]], pts[hull[k - 1]], pts[idx[i]]) <= 0)
					k--;
				hull[k++] = idx[i];
			}
			for (int i = n - 2, t = k + 1; i >= 0; i--) {
				while (k >= t && cross(pts[hull[k - 2]], pts[hull[k - 1]], pts[idx[i]]) <= 0)
					k--;
				hull[k++] = idx[i];
			}
			for (int i = 0; i < k - 1; i++)
				result.add(hull[i]);
			return result;
		}

		private static double cross(double[] o, double[] a, double[] b) {
			return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
		}

		// --------------- geozentrisch WGS84 ----------------
		/** geozentrisch X/Y/Z [m] (WGS84/GRS80) -> {Breite, Laenge, ellipsoidische Hoehe}. */
		public static double[] ecefToLatLonHeight(double x, double y, double z) {
			double[] latLon = toGeographic(new double[] { x, y, z }, GRS80_A, GRS80_F);
			double e2 = GRS80_F * (2.0 - GRS80_F);
			double phi = Math.toRadians(latLon[0]);
			double sp = Math.sin(phi);
			double bigN = GRS80_A / Math.sqrt(1.0 - e2 * sp * sp);
			double r = Math.hypot(x, y);
			double h = Math.abs(Math.cos(phi)) > 1e-6 ? r / Math.cos(phi) - bigN : Math.abs(z) / Math.abs(sp) - bigN * (1.0 - e2);
			return new double[] { latLon[0], latLon[1], h };
		}

		/** Vektor geozentrisch dX/dY/dZ -> {Ost, Nord, Oben} am Ort (Breite, Laenge). */
		public static double[] ecefVectorToEnu(double dx, double dy, double dz, double latDeg, double lonDeg) {
			double phi = Math.toRadians(latDeg), lambda = Math.toRadians(lonDeg);
			double sp = Math.sin(phi), cp = Math.cos(phi), sl = Math.sin(lambda), cl = Math.cos(lambda);
			return new double[] { -sl * dx + cl * dy, -sp * cl * dx - sp * sl * dy + cp * dz, cp * cl * dx + cp * sl * dy + sp * dz };
		}

		// --------------- Projektionsdefinitionen im PROJ-Format ----------------
		/**
		 * Eine Kartenprojektion aus einer PROJ-Zeile (z. B. von epsg.io, "PROJ.4").
		 * Unterstuetzt: +proj=tmerc / +proj=utm (+zone, +south), +ellps / +a +b / +a +rf,
		 * +datum=WGS84/potsdam, +towgs84 (3 oder 7 Werte, Position-Vector wie PROJ),
		 * +lat_0 +lon_0 +k/+k_0 +x_0 +y_0, +units=m. Nicht unterstuetzt: andere
		 * Projektionsarten, +nadgrids, +axis (ausser enu), andere Einheiten.
		 */
		public static final class Projection_Definition {
			public final String name;
			public final String definition;
			private final TransverseMercator tm;
			private final double lon0, falseEasting, falseNorthing, northingOfOrigin;
			private final double[] toWgs84; // null = bereits WGS84/ETRS89
			/** NTv2-Gitter (+nadgrids) statt +towgs84 - null, wenn keins. */
			private final NTv2_Grid grid;
			/** +proj=longlat - geographische Koordinaten im Startsystem (nur Export, nicht Karte). */
			private boolean geographic = false;

			private Projection_Definition(String name, String definition, double a, double f, double k0, double lat0, double lon0,
					double falseEasting, double falseNorthing, double[] toWgs84) {
				this(name, definition, a, f, k0, lat0, lon0, falseEasting, falseNorthing, toWgs84, null);
			}

			private Projection_Definition(String name, String definition, double a, double f, double k0, double lat0, double lon0,
					double falseEasting, double falseNorthing, double[] toWgs84, NTv2_Grid grid) {
				this.grid = grid;
				this.name = name;
				this.definition = definition;
				this.tm = new TransverseMercator(a, f, k0);
				this.lon0 = lon0;
				this.falseEasting = falseEasting;
				this.falseNorthing = falseNorthing;
				this.northingOfOrigin = lat0 == 0.0 ? 0.0 : this.tm.forward(lat0, lon0, lon0)[1];
				this.toWgs84 = toWgs84;
			}

			/** Rechtswert/Hochwert -> WGS84 {Breite, Laenge}; mit NTv2-Gitter null ausserhalb des Gitters. */
			public double[] toLatLon(double easting, double northing) {
				if (this.geographic)
					return this.grid != null ? this.grid.forward(northing, easting) : new double[] { northing, easting };
				double[] ll = this.tm.inverse(easting - this.falseEasting, northing - this.falseNorthing + this.northingOfOrigin, this.lon0);
				if (this.grid != null)
					return this.grid.forward(ll[0], ll[1]);
				if (this.toWgs84 == null)
					return ll;
				double[] xyz = toCartesian(ll[0], ll[1], 0.0, this.tm.a, this.tm.f);
				return toGeographic(helmert7(xyz, this.toWgs84, false), GRS80_A, GRS80_F);
			}

			/** WGS84 {Breite, Laenge} -> {Rechtswert, Hochwert}; mit NTv2-Gitter null ausserhalb des Gitters. */
			public double[] fromLatLon(double lat, double lon) {
				double[] ll = new double[] { lat, lon };
				if (this.grid != null) {
					ll = this.grid.inverse(lat, lon);
					if (ll == null)
						return null;
				}
				if (this.geographic)
					return new double[] { ll[0], ll[1] };
				else if (this.toWgs84 != null) {
					double[] xyz = toCartesian(lat, lon, 0.0, GRS80_A, GRS80_F);
					ll = toGeographic(helmert7(xyz, this.toWgs84, true), this.tm.a, this.tm.f);
				}
				double[] en = this.tm.forward(ll[0], ll[1], this.lon0);
				return new double[] { en[0] + this.falseEasting, en[1] - this.northingOfOrigin + this.falseNorthing };
			}

			/** true = Datumsuebergang mit 3/7 Parametern (ungenau, ca. 1-3 m) - NTv2-Gitter zaehlt hier nicht. */
			public boolean hasDatumShift() {
				return this.toWgs84 != null;
			}

			/** geographische Definition (+proj=longlat). */
			public boolean isGeographic() {
				return this.geographic;
			}

			/** Datumsuebergang ueber ein NTv2-Gitter (amtlich, eindeutig). */
			public boolean hasGrid() {
				return this.grid != null;
			}

			/** Dateiname des Gitters oder null. */
			public String gridName() {
				return this.grid == null ? null : this.grid.file.getName();
			}

			/** fuer den Export zulaessig: reine Umformung (ETRS89/GRS80, kein Datumsuebergang) oder NTv2-Gitter. */
			public boolean exportAllowed() {
				return this.grid != null || this.toWgs84 == null;
			}

			/** Streifen-/Zonenkennung im Rechtswert (z. B. 4 bei +x_0=4500000), 0 ohne Kennung. */
			public int eastingPrefix() {
				return this.falseEasting >= 1000000.0 ? (int) Math.floor(this.falseEasting / 1000000.0) : 0;
			}

			/**
			 * Liest eine PROJ-Zeile. Wirft IllegalArgumentException mit deutschem Grund,
			 * wenn die Definition nicht unterstuetzt oder unvollstaendig ist.
			 */
			public static Projection_Definition parse(String name, String line) {
				String text = line == null ? "" : line.trim();
				if (!text.contains("+proj="))
					throw new IllegalArgumentException("keine PROJ-Zeile (muss mit +proj= beginnen)");
				Map<String, String> p = new LinkedHashMap<String, String>();
				for (String token : text.split("\\s+")) {
					if (!token.startsWith("+"))
						continue;
					int eq = token.indexOf('=');
					if (eq < 0)
						p.put(token.substring(1).toLowerCase(java.util.Locale.ROOT), "");
					else
						p.put(token.substring(1, eq).toLowerCase(java.util.Locale.ROOT), token.substring(eq + 1));
				}
				String proj = p.get("proj");
				if (!"tmerc".equals(proj) && !"utm".equals(proj) && !"longlat".equals(proj) && !"latlong".equals(proj))
					throw new IllegalArgumentException("Projektionsart \"" + proj + "\" nicht unterstützt (nur tmerc, utm und longlat)");
				// NTv2-Gitter (+nadgrids=datei.gsb) aus dem Projektionsordner
				NTv2_Grid grid = null;
				if (p.containsKey("nadgrids")) {
					String gridName = p.get("nadgrids").split(",")[0].trim();
					if (gridName.startsWith("@"))
						gridName = gridName.substring(1);
					if (gridName.isEmpty() || "null".equalsIgnoreCase(gridName))
						throw new IllegalArgumentException("+nadgrids ohne Gitterdatei");
					java.io.File gridFile = new java.io.File(gridName);
					if (!gridFile.isAbsolute())
						gridFile = new java.io.File(projectionFolder(), gridName);
					if (!gridFile.isFile())
						throw new IllegalArgumentException("Gitterdatei \"" + gridName + "\" fehlt im Projektionsordner");
					grid = NTv2_Grid.of(gridFile);
				}
				if (p.containsKey("units") && !"m".equals(p.get("units")))
					throw new IllegalArgumentException("Einheit \"" + p.get("units") + "\" nicht unterstützt (nur m)");
				if (p.containsKey("axis") && !"enu".equals(p.get("axis")))
					throw new IllegalArgumentException("Achsfolge \"" + p.get("axis") + "\" nicht unterstützt");

				String datum = p.get("datum");
				double[] ellipsoid = null;
				double[] towgs84 = null;
				if (datum != null) {
					switch (datum.toLowerCase(java.util.Locale.ROOT)) {
					case "wgs84":
						ellipsoid = new double[] { 6378137.0, 1.0 / 298.257223563 };
						towgs84 = new double[] { 0, 0, 0, 0, 0, 0, 0 };
						break;
					case "potsdam":
						ellipsoid = new double[] { BESSEL_A, BESSEL_F };
						towgs84 = new double[] { 598.1, 73.7, 418.2, 0.202, 0.045, -2.455, 6.7 };
						break;
					default:
						if (!p.containsKey("towgs84"))
							throw new IllegalArgumentException("Datum \"" + datum + "\" unbekannt - bitte eine Definition mit +towgs84 verwenden");
					}
				}
				String ellps = p.get("ellps");
				if (ellps != null) {
					ellipsoid = ellipsoidByName(ellps);
					if (ellipsoid == null)
						throw new IllegalArgumentException("Ellipsoid \"" + ellps + "\" unbekannt - bitte +a und +rf angeben");
				}
				if (p.containsKey("a")) {
					double a = number(p, "a");
					double f;
					if (p.containsKey("rf"))
						f = 1.0 / number(p, "rf");
					else if (p.containsKey("b"))
						f = (a - number(p, "b")) / a;
					else if (p.containsKey("f"))
						f = number(p, "f");
					else
						throw new IllegalArgumentException("+a ohne +rf/+b angegeben");
					ellipsoid = new double[] { a, f };
				}
				if (ellipsoid == null)
					ellipsoid = new double[] { GRS80_A, GRS80_F };
				if (p.containsKey("towgs84")) {
					String[] parts = p.get("towgs84").split(",");
					if (parts.length != 3 && parts.length != 7)
						throw new IllegalArgumentException("+towgs84 braucht 3 oder 7 Werte");
					towgs84 = new double[7];
					for (int i = 0; i < parts.length; i++)
						towgs84[i] = Double.parseDouble(parts[i].trim());
				}
				double[] shift = null;
				if (towgs84 != null) {
					boolean zero = true;
					for (double v : towgs84)
						zero &= v == 0.0;
					if (!zero)
						shift = new double[] { towgs84[0], towgs84[1], towgs84[2], towgs84[3] * ARCSEC, towgs84[4] * ARCSEC, towgs84[5] * ARCSEC,
								towgs84[6] * 1e-6 };
				}
				else if (grid == null && Math.abs(ellipsoid[0] - GRS80_A) > 1e-3)
					throw new IllegalArgumentException("kein Datumsübergang (+towgs84) für ein Nicht-GRS80-Ellipsoid angegeben");
				if (grid != null) {
					if (Math.abs(grid.majorFrom - ellipsoid[0]) > 1e-3 || Math.abs(grid.minorFrom - ellipsoid[0] * (1.0 - ellipsoid[1])) > 1e-3)
						throw new IllegalArgumentException("Ellipsoid der Definition passt nicht zum Startsystem des Gitters " + grid.file.getName());
					if (Math.abs(grid.majorTo - GRS80_A) > 1e-3 || Math.abs(grid.minorTo - GRS80_A * (1.0 - GRS80_F)) > 1e-3)
						throw new IllegalArgumentException("Zielsystem des Gitters " + grid.file.getName() + " ist nicht ETRS89/GRS80");
					shift = null;
				}

				double k0, lat0 = 0.0, lon0, x0, y0;
				if ("longlat".equals(proj) || "latlong".equals(proj)) {
					k0 = 1.0;
					lon0 = 0.0;
					x0 = 0.0;
					y0 = 0.0;
				}
				else if ("utm".equals(proj)) {
					if (!p.containsKey("zone"))
						throw new IllegalArgumentException("+proj=utm ohne +zone");
					int zone = (int) number(p, "zone");
					if (zone < 1 || zone > 60)
						throw new IllegalArgumentException("UTM-Zone " + zone + " ungültig");
					k0 = UTM_K0;
					lon0 = utmCentralMeridian(zone);
					x0 = 500000.0;
					y0 = p.containsKey("south") ? 10000000.0 : 0.0;
				}
				else {
					k0 = p.containsKey("k") ? number(p, "k") : p.containsKey("k_0") ? number(p, "k_0") : 1.0;
					lat0 = p.containsKey("lat_0") ? number(p, "lat_0") : 0.0;
					lon0 = p.containsKey("lon_0") ? number(p, "lon_0") : 0.0;
					x0 = p.containsKey("x_0") ? number(p, "x_0") : 0.0;
					y0 = p.containsKey("y_0") ? number(p, "y_0") : 0.0;
				}
				Projection_Definition definition = new Projection_Definition(name, text, ellipsoid[0], ellipsoid[1], k0, lat0, lon0, x0, y0, shift, grid);
				definition.geographic = "longlat".equals(proj) || "latlong".equals(proj);
				return definition;
			}

			private static double number(Map<String, String> p, String key) {
				try {
					return Double.parseDouble(p.get(key));
				} catch (NumberFormatException | NullPointerException e) {
					throw new IllegalArgumentException("+" + key + " ist keine Zahl");
				}
			}

			private static double[] ellipsoidByName(String name) {
				switch (name.toLowerCase(java.util.Locale.ROOT)) {
				case "grs80":
					return new double[] { GRS80_A, GRS80_F };
				case "wgs84":
					return new double[] { 6378137.0, 1.0 / 298.257223563 };
				case "bessel":
					return new double[] { BESSEL_A, BESSEL_F };
				case "krass":
					return new double[] { 6378245.0, 1.0 / 298.3 };
				case "intl":
					return new double[] { 6378388.0, 1.0 / 297.0 };
				case "airy":
					return new double[] { 6377563.396, 1.0 / 299.3249646 };
				case "clrk66":
					return new double[] { 6378206.4, 1.0 / 294.978698214 };
				case "clrk80":
					return new double[] { 6378249.145, 1.0 / 293.465 };
				default:
					return null;
				}
			}
		}

		/**
		 * NTv2-Gitterdatei (National Transformation Version 2, binaer .gsb), z. B. NTv2_SN des GeoSN (RD/83 ->
		 * ETRS89, EPSG 6948). Verfahren nach der NTv2-Dokumentation: Verschiebungen dB/dL [Bogensekunden, dL positiv
		 * West] im dichtesten Teilgitter, das den Punkt enthaelt, bilinear interpoliert; Rueckrichtung iterativ. Nur
		 * 2D (Lage) - die Hoehe bleibt unberuehrt. Wird beim ersten Gebrauch geladen und zwischengespeichert.
		 */
		public static final class NTv2_Grid {
			private static final Map<String, NTv2_Grid> CACHE = new java.util.HashMap<String, NTv2_Grid>();

			public final java.io.File file;
			public final double majorFrom, minorFrom, majorTo, minorTo;
			private final long length, modified;
			private volatile List<Sub_Grid> roots = null;

			private static final class Sub_Grid {
				String name, parent;
				double south, north, east, west, latInc, lonInc; // Bogensekunden, Laenge positiv West
				int rows, columns;
				float[] shifts; // je Knoten dB, dL
				final List<Sub_Grid> children = new ArrayList<Sub_Grid>();

				boolean contains(double lat, double lonWest) {
					return lat >= this.south && lat <= this.north && lonWest >= this.east && lonWest <= this.west;
				}
			}

			/**
			 * Bezugssysteme laut Kopf (SYSTEM_F/SYSTEM_T) und Ausdehnung der Elterngitter {Bmin, Bmax, Lmin, Lmax}
			 * [Grad].
			 */
			public final String systemFrom, systemTo;
			public final double[] extent;

			private NTv2_Grid(java.io.File file) {
				this.file = file;
				this.length = file.length();
				this.modified = file.lastModified();
				double[] ell = new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN };
				String[] systems = new String[] { "", "" };
				double[] ext = new double[] { Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE };
				try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
					byte[] head = new byte[11 * 16];
					raf.readFully(head);
					java.nio.ByteBuffer bb = order(java.nio.ByteBuffer.wrap(head));
					java.nio.ByteOrder byteOrder = bb.order();
					int files = 0;
					for (int r = 0; r < 11; r++) {
						String key = text(head, r * 16);
						if ("GS_TYPE".equals(key) && !"SECONDS".equalsIgnoreCase(text(head, r * 16 + 8)))
							throw new IllegalArgumentException("NTv2-Datei " + file.getName() + ": GS_TYPE " + text(head, r * 16 + 8) + " nicht unterstuetzt (nur SECONDS)");
						if ("NUM_FILE".equals(key))
							files = bb.getInt(r * 16 + 8);
						if ("SYSTEM_F".equals(key))
							systems[0] = text(head, r * 16 + 8);
						if ("SYSTEM_T".equals(key))
							systems[1] = text(head, r * 16 + 8);
						int index = "MAJOR_F".equals(key) ? 0 : "MINOR_F".equals(key) ? 1 : "MAJOR_T".equals(key) ? 2 : "MINOR_T".equals(key) ? 3 : -1;
						if (index >= 0)
							ell[index] = bb.getDouble(r * 16 + 8);
					}
					// Teilgitter-Koepfe lesen (Daten ueberspringen) - Ausdehnung der Elterngitter (PARENT NONE)
					long offset = 11 * 16;
					for (int k = 0; k < files && k < 10000; k++) {
						byte[] sub = new byte[11 * 16];
						raf.seek(offset);
						raf.readFully(sub);
						java.nio.ByteBuffer sb = java.nio.ByteBuffer.wrap(sub).order(byteOrder);
						String parent = "";
						double south = 0, north = 0, east = 0, west = 0;
						int count = 0;
						for (int r = 0; r < 11; r++) {
							String key = text(sub, r * 16);
							switch (key) {
							case "PARENT": parent = text(sub, r * 16 + 8); break;
							case "S_LAT": south = sb.getDouble(r * 16 + 8); break;
							case "N_LAT": north = sb.getDouble(r * 16 + 8); break;
							case "E_LONG": east = sb.getDouble(r * 16 + 8); break;
							case "W_LONG": west = sb.getDouble(r * 16 + 8); break;
							case "GS_COUNT": count = sb.getInt(r * 16 + 8); break;
							default: break;
							}
						}
						if ("NONE".equalsIgnoreCase(parent)) {
							ext[0] = Math.min(ext[0], south / 3600.0);
							ext[1] = Math.max(ext[1], north / 3600.0);
							ext[2] = Math.min(ext[2], -west / 3600.0);
							ext[3] = Math.max(ext[3], -east / 3600.0);
						}
						offset += 11 * 16 + 16L * count;
					}
				} catch (java.io.IOException e) {
					throw new IllegalArgumentException("NTv2-Datei " + file.getName() + " nicht lesbar: " + e.getMessage());
				}
				for (double v : ell)
					if (Double.isNaN(v))
						throw new IllegalArgumentException("NTv2-Datei " + file.getName() + ": Ellipsoidangaben fehlen");
				if (ext[0] > ext[1] || ext[2] > ext[3])
					throw new IllegalArgumentException("NTv2-Datei " + file.getName() + ": kein Elterngitter gefunden");
				this.majorFrom = ell[0];
				this.minorFrom = ell[1];
				this.majorTo = ell[2];
				this.minorTo = ell[3];
				this.systemFrom = systems[0].isEmpty() ? "Startsystem" : systems[0];
				this.systemTo = systems[1];
				this.extent = ext;
			}

			/** Gitter zur Datei (Kopf sofort geprueft, Inhalt erst beim ersten Gebrauch geladen). */
			public static synchronized NTv2_Grid of(java.io.File file) {
				String key = file.getAbsolutePath();
				NTv2_Grid cached = CACHE.get(key);
				if (cached != null && cached.length == file.length() && cached.modified == file.lastModified())
					return cached;
				NTv2_Grid grid = new NTv2_Grid(file);
				CACHE.put(key, grid);
				return grid;
			}

			/** Zielsystem ETRS89/GRS80 (sonst fuer Klein_Method nicht nutzbar)? */
			public boolean targetIsGrs80() {
				return Math.abs(this.majorTo - GRS80_A) <= 1e-3 && Math.abs(this.minorTo - GRS80_A * (1.0 - GRS80_F)) <= 1e-3;
			}

			/** Startellipsoid GRS80 (dann keine Gauss-Krueger-Systeme ableiten, nur geographisch)? */
			public boolean sourceIsGrs80() {
				return Math.abs(this.majorFrom - GRS80_A) <= 1e-3;
			}

			/**
			 * wie genau deckt das Gitter einen Punkt ab (Startsystem, Grad): 0 = ausserhalb, 1 = nur Elterngitter, 2
			 * und mehr = verdichtetes Teilgitter (z. B. das Landesgebiet). Laedt das Gitter.
			 */
			public int coverage(double latDeg, double lonDeg) {
				double lat = latDeg * 3600.0, lonWest = -lonDeg * 3600.0;
				for (Sub_Grid root : this.load())
					if (root.contains(lat, lonWest)) {
						int level = 1;
						Sub_Grid g = root;
						boolean deeper = true;
						while (deeper) {
							deeper = false;
							for (Sub_Grid child : g.children)
								if (child.contains(lat, lonWest)) {
									g = child;
									level++;
									deeper = true;
									break;
								}
						}
						return level;
					}
				return 0;
			}

			private static java.nio.ByteBuffer order(java.nio.ByteBuffer buffer) {
				buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
				if (buffer.getInt(8) != 11)
					buffer.order(java.nio.ByteOrder.BIG_ENDIAN);
				if (buffer.getInt(8) != 11)
					throw new IllegalArgumentException("keine NTv2-Datei (NUM_OREC nicht 11)");
				return buffer;
			}

			private static String text(byte[] b, int offset) {
				return new String(b, offset, 8, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
			}

			private List<Sub_Grid> load() {
				List<Sub_Grid> loaded = this.roots;
				if (loaded != null)
					return loaded;
				synchronized (this) {
					if (this.roots != null)
						return this.roots;
					byte[] b;
					try {
						b = java.nio.file.Files.readAllBytes(this.file.toPath());
					} catch (java.io.IOException e) {
						throw new IllegalStateException("NTv2-Datei " + this.file.getName() + " nicht lesbar: " + e.getMessage());
					}
					java.nio.ByteBuffer bb = order(java.nio.ByteBuffer.wrap(b));
					int files = bb.getInt(2 * 16 + 8);
					int offset = 11 * 16;
					List<Sub_Grid> all = new ArrayList<Sub_Grid>();
					for (int k = 0; k < files; k++) {
						Sub_Grid g = new Sub_Grid();
						int count = 0;
						for (int r = 0; r < 11; r++, offset += 16) {
							String key = text(b, offset);
							switch (key) {
							case "SUB_NAME": g.name = text(b, offset + 8); break;
							case "PARENT": g.parent = text(b, offset + 8); break;
							case "S_LAT": g.south = bb.getDouble(offset + 8); break;
							case "N_LAT": g.north = bb.getDouble(offset + 8); break;
							case "E_LONG": g.east = bb.getDouble(offset + 8); break;
							case "W_LONG": g.west = bb.getDouble(offset + 8); break;
							case "LAT_INC": g.latInc = bb.getDouble(offset + 8); break;
							case "LONG_INC": g.lonInc = bb.getDouble(offset + 8); break;
							case "GS_COUNT": count = bb.getInt(offset + 8); break;
							default: break;
							}
						}
						g.rows = (int) Math.round((g.north - g.south) / g.latInc) + 1;
						g.columns = (int) Math.round((g.west - g.east) / g.lonInc) + 1;
						if (g.rows < 2 || g.columns < 2 || g.rows * g.columns != count)
							throw new IllegalStateException("NTv2-Datei " + this.file.getName() + ": Teilgitter " + g.name + " fehlerhaft");
						g.shifts = new float[2 * count];
						for (int i = 0; i < count; i++, offset += 16) {
							g.shifts[2 * i] = bb.getFloat(offset);
							g.shifts[2 * i + 1] = bb.getFloat(offset + 4);
						}
						all.add(g);
					}
					List<Sub_Grid> top = new ArrayList<Sub_Grid>();
					for (Sub_Grid g : all) {
						Sub_Grid parent = null;
						for (Sub_Grid candidate : all)
							if (candidate != g && candidate.name.equals(g.parent))
								parent = candidate;
						if (parent == null)
							top.add(g);
						else
							parent.children.add(g);
					}
					this.roots = top;
					return top;
				}
			}

			/** {dB, dL} in Bogensekunden (dL positiv West) am Ort des Startsystems, null ausserhalb des Gitters. */
			private double[] shift(double latDeg, double lonDeg) {
				double lat = latDeg * 3600.0, lonWest = -lonDeg * 3600.0;
				Sub_Grid g = null;
				for (Sub_Grid root : this.load())
					if (root.contains(lat, lonWest)) {
						g = root;
						break;
					}
				if (g == null)
					return null;
				boolean deeper = true;
				while (deeper) {
					deeper = false;
					for (Sub_Grid child : g.children)
						if (child.contains(lat, lonWest)) {
							g = child;
							deeper = true;
							break;
						}
				}
				double x = (lonWest - g.east) / g.lonInc, y = (lat - g.south) / g.latInc;
				int i = Math.min((int) Math.floor(x), g.columns - 2), j = Math.min((int) Math.floor(y), g.rows - 2);
				double fx = x - i, fy = y - j;
				double[] result = new double[2];
				for (int c = 0; c < 2; c++) {
					double a = g.shifts[2 * (j * g.columns + i) + c], b = g.shifts[2 * (j * g.columns + i + 1) + c];
					double d = g.shifts[2 * ((j + 1) * g.columns + i) + c], e = g.shifts[2 * ((j + 1) * g.columns + i + 1) + c];
					result[c] = a + (b - a) * fx + (d - a) * fy + (a - b - d + e) * fx * fy;
				}
				return result;
			}

			/** Startsystem -> Zielsystem (z. B. RD/83 -> ETRS89) {Breite, Laenge} [Grad], null ausserhalb. */
			public double[] forward(double latDeg, double lonDeg) {
				double[] d = this.shift(latDeg, lonDeg);
				return d == null ? null : new double[] { latDeg + d[0] / 3600.0, lonDeg - d[1] / 3600.0 };
			}

			/** Zielsystem -> Startsystem (iterativ bis 1e-12 Grad), null ausserhalb. */
			public double[] inverse(double latDeg, double lonDeg) {
				double lat = latDeg, lon = lonDeg;
				for (int k = 0; k < 20; k++) {
					double[] d = this.shift(lat, lon);
					if (d == null)
						return null;
					double nextLat = latDeg - d[0] / 3600.0, nextLon = lonDeg + d[1] / 3600.0;
					boolean done = Math.abs(nextLat - lat) < 1e-12 && Math.abs(nextLon - lon) < 1e-12;
					lat = nextLat;
					lon = nextLon;
					if (done)
						break;
				}
				return new double[] { lat, lon };
			}
		}

		/** Projektionsordner (PROJ-Definitionen und Gitterdateien) - gemeinsam fuer Karte und Koordinaten-Export. */
		public static java.io.File projectionFolder() {
			return new java.io.File(System.getProperty("user.home"), ".klein_method" + java.io.File.separator + "projektionen");
		}

		/**
		 * Legt den Projektionsordner beim ersten Mal mit den mitgelieferten Vorlagen an (nur Definitionen ohne
		 * Gitter). Gitterdateien sind NICHT im Quellcode verankert - jede *.gsb im Ordner wird beim Lesen ausgewertet
		 * (gridDefinitions). Die automatisch angelegten RD83-Vorlagen werden entfernt, wenn sie unveraendert sind.
		 *
		 * Rueckgabe: Hinweise fuers Log.
		 */
		public static List<String> ensureProjectionFolder() {
			List<String> notes = new ArrayList<String>();
			java.io.File folder = projectionFolder();
			boolean fresh = !folder.isDirectory();
			if (fresh && !folder.mkdirs())
				return notes;
			if (fresh)
				for (Map.Entry<String, String> entry : defaultProjectionDefinitions().entrySet()) {
					java.io.File file = new java.io.File(folder, entry.getKey() + ".txt");
					try {
						java.nio.file.Files.write(file.toPath(), (entry.getValue() + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
					} catch (java.io.IOException e) {
						notes.add("Vorlage nicht schreibbar: " + file + " (" + e.getMessage() + ")");
					}
				}
			for (int strip = 4; strip <= 5; strip++) {
				java.io.File old = new java.io.File(folder, "RD83 Gauss-Krueger Streifen " + strip + " NTv2_SN (EPSG 6948).txt");
				String oldLine = "+proj=tmerc +lat_0=0 +lon_0=" + (3 * strip) + " +k=1 +x_0=" + strip + "500000 +y_0=0 +ellps=bessel +nadgrids=NTv2_SN.gsb +units=m +no_defs";
				try {
					if (old.isFile() && new String(java.nio.file.Files.readAllBytes(old.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim().equals(oldLine)
							&& old.delete())
						notes.add("Vorlage aus V0.423 entfernt (jetzt automatisch aus der Gitterdatei): " + old.getName());
				} catch (java.io.IOException e) {
					// bleibt stehen - schadet nicht
				}
			}
			return notes;
		}

		/**
		 * leitet aus JEDER NTv2-Datei (*.gsb) im Projektionsordner die Koordinatensysteme ihres Startsystems ab - nur
		 * aus dem Dateikopf, nichts im Code:
		 *
		 * "<SYSTEM_F> geographisch (B/L)" auf dem Startellipsoid (immer),
		 *
		 * bei nicht-GRS80-Startellipsoid (Bessel, Krassowski ...) die Gauss-Krueger-Streifen 3 Grad, k = 1,
		 * Rechtswert mit Streifenkennziffer, deren Mittelmeridian hoechstens 1,5 Grad ausserhalb des Gitters liegt.
		 *
		 * Voraussetzung: Zielsystem des Gitters ETRS89/GRS80. Ungueltige Dateien -> errors.
		 */
		public static List<Projection_Definition> gridDefinitions(List<String> errors) {
			List<Projection_Definition> definitions = new ArrayList<Projection_Definition>();
			java.io.File[] files = projectionFolder().listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".gsb"));
			if (files == null)
				return definitions;
			java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			for (java.io.File file : files) {
				try {
					NTv2_Grid grid = NTv2_Grid.of(file);
					if (!grid.targetIsGrs80())
						throw new IllegalArgumentException("Zielsystem " + grid.systemTo + " ist nicht ETRS89/GRS80");
					String ellipsoid = String.format(java.util.Locale.ROOT, "+a=%.4f +b=%.4f", grid.majorFrom, grid.minorFrom);
					String label = " (" + file.getName() + ")";
					definitions.add(Projection_Definition.parse(grid.systemFrom + " geographisch (B/L)" + label,
							"+proj=longlat " + ellipsoid + " +nadgrids=" + file.getName() + " +no_defs"));
					if (!grid.sourceIsGrs80()) {
						int first = (int) Math.ceil((grid.extent[2] - 1.5) / 3.0), last = (int) Math.floor((grid.extent[3] + 1.5) / 3.0);
						for (int strip = Math.max(1, first); strip <= Math.min(9, last); strip++)
							definitions.add(Projection_Definition.parse(grid.systemFrom + " Gau\u00DF-Kr\u00FCger Streifen " + strip + label,
									"+proj=tmerc +lat_0=0 +lon_0=" + (3 * strip) + " +k=1 +x_0=" + strip + "500000 +y_0=0 " + ellipsoid + " +nadgrids="
											+ file.getName() + " +units=m +no_defs"));
					}
				} catch (IllegalArgumentException e) {
					if (errors != null)
						errors.add("\u201E" + file.getName() + "\u201C: " + e.getMessage());
				}
			}
			return definitions;
		}

		/**
		 * rechnet ein Gitter mit mitgelieferten Pruefdaten nach (CSV, Komma oder Semikolon, je Zeile Nummer,
		 * Start-System, Breite, Laenge, Ziel-System, Breite, Laenge - so liefern es z. B. die Landesaemter).
		 * Verwendet werden nur geographische Zeilen (Werte in Grad); die Richtung (Start -> Ziel oder umgekehrt) wird
		 * erkannt. Ebene Zeilen (Hoch-/Rechtswerte) werden uebersprungen. Rueckgabe: Ergebnistext oder null, wenn die
		 * Datei keine Pruefdaten enthaelt.
		 */
		public static String verifyGrid(NTv2_Grid grid, String fileName, String csv) {
			int rows = 0, outside = 0;
			double max = 0.0;
			for (String line : csv.split("\\r?\\n")) {
				String[] f = line.split("[;,]");
				if (f.length < 7)
					continue;
				double[] v = new double[4];
				try {
					v[0] = Double.parseDouble(f[2].trim());
					v[1] = Double.parseDouble(f[3].trim());
					v[2] = Double.parseDouble(f[5].trim());
					v[3] = Double.parseDouble(f[6].trim());
				} catch (NumberFormatException e) {
					continue;
				}
				if (Math.abs(v[0]) > 90.0 || Math.abs(v[1]) > 180.0 || Math.abs(v[2]) > 90.0 || Math.abs(v[3]) > 180.0)
					continue; // ebene Koordinaten
				double[] forward = grid.forward(v[0], v[1]);
				double[] inverse = grid.inverse(v[0], v[1]);
				if (forward == null && inverse == null) {
					outside++;
					continue;
				}
				double dForward = forward == null ? Double.MAX_VALUE : angularMetres(forward, v[2], v[3]);
				double dInverse = inverse == null ? Double.MAX_VALUE : angularMetres(inverse, v[2], v[3]);
				max = Math.max(max, Math.min(dForward, dInverse));
				rows++;
			}
			if (rows == 0 && outside == 0)
				return null;
			boolean passed = rows > 0 && max <= 0.001;
			return String.format(java.util.Locale.GERMANY, "%s mit %s: %d Punkt(e) nachgerechnet, max. Abweichung %.2f mm%s - %s", grid.file.getName(), fileName, rows,
					max * 1000.0, outside > 0 ? ", " + outside + " ausserhalb des Gitters" : "", passed ? "bestanden" : "NICHT bestanden (Grenze 1 mm)");
		}

		private static double angularMetres(double[] ll, double lat, double lon) {
			double dn = (ll[0] - lat) * Math.PI / 180.0 * 6378137.0, de = (ll[1] - lon) * Math.PI / 180.0 * 6378137.0 * Math.cos(Math.toRadians(lat));
			return Math.hypot(dn, de);
		}

		/**
		 * Gitter-Definition (Gauss-Krueger) zur Streifenkennziffer des Rechtswerts; liegen mehrere Gitter fuer den
		 * Streifen vor (z. B. Sachsen und Thueringen), gewinnt das, dessen verdichtetes Teilgitter den Punkt
		 * enthaelt.
		 */
		public static Projection_Definition bestGridDefinition(List<Projection_Definition> definitions, double y, double x) {
			if (definitions == null)
				return null;
			int prefix = zoneOfEasting(y);
			Projection_Definition best = null;
			int bestLevel = 0;
			for (Projection_Definition d : definitions) {
				if (!d.hasGrid() || d.isGeographic() || d.eastingPrefix() != prefix)
					continue;
				double[] ll = d.tm.inverse(y - d.falseEasting, x - d.falseNorthing + d.northingOfOrigin, d.lon0);
				int level = d.grid.coverage(ll[0], ll[1]);
				if (level > bestLevel) {
					bestLevel = level;
					best = d;
				}
			}
			return best;
		}

		/** liest alle *.txt-Dateien des Projektionsordners (erste Zeile ohne "#"); Fehler mit Grund in errors. */
		public static List<Projection_Definition> loadProjectionDefinitions(List<String> errors) {
			List<Projection_Definition> definitions = new ArrayList<Projection_Definition>();
			java.io.File[] files = projectionFolder().listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt"));
			if (files == null)
				files = new java.io.File[0];
			java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
			for (java.io.File file : files) {
				String name = file.getName().substring(0, file.getName().length() - 4);
				try {
					String line = null;
					for (String candidate : java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
						String trimmed = candidate.trim().replace("\uFEFF", "");
						if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
							line = trimmed;
							break;
						}
					}
					definitions.add(Projection_Definition.parse(name, line));
				} catch (IllegalArgumentException e) {
					if (errors != null)
						errors.add("\u201E" + name + "\u201C: " + e.getMessage());
				} catch (java.io.IOException e) {
					if (errors != null)
						errors.add("\u201E" + name + "\u201C: nicht lesbar (" + e.getMessage() + ")");
				}
			}
			definitions.addAll(gridDefinitions(errors));
			return definitions;
		}

		/** Mitgelieferte Vorlagen fuer den Projektionsordner: Dateiname -> PROJ-Zeile. */
		public static Map<String, String> defaultProjectionDefinitions() {
			Map<String, String> d = new LinkedHashMap<String, String>();
			d.put("ETRS89 UTM Zone 32 ohne Zonenkennung (EPSG 25832)", "+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs");
			d.put("ETRS89 UTM Zone 33 ohne Zonenkennung (EPSG 25833)", "+proj=utm +zone=33 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs");
			int[][] dhdn = { { 2, 6 }, { 3, 9 }, { 4, 12 }, { 5, 15 } };
			int[] dhdnEpsg = { 31466, 31467, 31468, 31469 };
			for (int i = 0; i < dhdn.length; i++)
				d.put("DHDN Gauss-Krueger Streifen " + dhdn[i][0] + " (EPSG " + dhdnEpsg[i] + ")",
						"+proj=tmerc +lat_0=0 +lon_0=" + dhdn[i][1] + " +k=1 +x_0=" + dhdn[i][0] + "500000 +y_0=0 +ellps=bessel "
								+ "+towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7 +units=m +no_defs");
			int[][] s4283 = { { 3, 9 }, { 4, 12 }, { 5, 15 } };
			int[] s4283Epsg = { 2397, 2398, 2399 };
			for (int i = 0; i < s4283.length; i++)
				d.put("42-83 Gauss-Krueger Streifen " + s4283[i][0] + " Krassowski (EPSG " + s4283Epsg[i] + ")",
						"+proj=tmerc +lat_0=0 +lon_0=" + s4283[i][1] + " +k=1 +x_0=" + s4283[i][0] + "500000 +y_0=0 +ellps=krass "
								+ "+towgs84=24,-123,-94,0.02,-0.25,-0.13,1.1 +units=m +no_defs");
			return d;
		}

		/** groesste Strecke zwischen zwei Punkten (Netzausdehnung) [m]. */
		public static double maxExtent(double[][] pts) {
			List<Integer> hull = convexHull(pts);
			double max = 0.0;
			for (int i = 0; i < hull.size(); i++)
				for (int j = i + 1; j < hull.size(); j++)
					max = Math.max(max, Math.hypot(pts[hull.get(i)][0] - pts[hull.get(j)][0], pts[hull.get(i)][1] - pts[hull.get(j)][1]));
			return max;
		}
	}

	/** oeffentlicher Zugang zur Einheitenumrechnung (Schwarmwissen). */
	public double toWorkingUnit(double rawValue, String workingUnit) {
		return this.fromDatabaseValue(rawValue, workingUnit);
	}

	/** oeffentlicher Zugang zur Einheitenumrechnung (Schwarmwissen). */
	public double toDatabaseUnit(double workingValue, String workingUnit) {
		return this.toDatabaseValue(workingValue, workingUnit);
	}

	// ====================================================================== Laufberichte, Erfahrungswerte,
	// 10-%-Regel fuer Startwerte, Austausch ueber GitHub. Reine Hilfsfunktionen ohne Datenbank- und
	// Oberflaechenzugriff (Datei, Rechnen, HTTP). Keine neue Datei.
	// ======================================================================
	public static final class Klein_Method_Swarm {

		private Klein_Method_Swarm() {
		}

		public static final String DEFAULT_REPOSITORY = "htw9056/klein-method-schwarm";
		/** Bisheriger privater Uebungsbetrieb (Standard bis V0.509) - wird einmalig auf den neuen Standard umgestellt. */
		public static final String PREVIOUS_DEFAULT_REPOSITORY = "htw9056/klein-method-schwarm-test";
		/** Vermittlungsdienst (Cloudflare Worker): legt Laufberichte ohne Token beim Anwender als Issue an. Leer = keiner. */
		public static final String DEFAULT_RELAY_URL = "https://klein-method-schwarm.htw9056.workers.dev";
		/** Laenge, bis zu der ein Bericht vorausgefuellt im Browser geoeffnet wird (Adresszeile). */
		public static final int BROWSER_URL_MAX_LENGTH = 7500;
		public static final String REPORT_MARKER = "KLEIN_METHOD_LAUFBERICHT format=1";
		public static final String TITLE_PREFIX = "[Laufbericht]";
		public static final String SWARM_FILE = "erfahrungswerte.txt";
		/** 10-%-Regel: Projekte duerfen sich untereinander hoechstens so stark unterscheiden (max/min). */
		public static final double MAX_RELATIVE_SPREAD = 0.10;
		/** Mindestanzahl uebereinstimmender Projekte (Startwerte) bzw. Projekte (Warnung). */
		public static final int MIN_PROJECTS = 3;
		public static final double WARN_FACTOR_DEFAULT = 3.0, WARN_FACTOR_MIN = 1.5, WARN_FACTOR_MAX = 100.0;

		/** Festpunktart (dynamische AGL) - nur Gleiches wird mit Gleichem verglichen. */
		public enum Fixed_Point_Class {
			GNSS_REFERENZ("GNSS-Referenz"), TP_AP("TP/AP"), HOEHENFESTPUNKT("H\u00F6henfestpunkt"), SONSTIGE("Sonstige");

			private final String displayName;

			Fixed_Point_Class(String displayName) {
				this.displayName = displayName;
			}

			public String getDisplayName() {
				return this.displayName;
			}

			@Override
			public String toString() {
				return Klein_Method_I18N.tr(this.displayName);
			}

			public static Fixed_Point_Class parse(String name) {
				for (Fixed_Point_Class value : values())
					if (value.name().equals(name))
						return value;
				return SONSTIGE;
			}
		}

		/** Kennungen der Punktwerte (dynamische AGL); Komponente immer "s" (je Punkt ein einheitlicher Wert). */
		public static String pointGroupType(int dimension) {
			return "PUNKTGRUPPE_" + dimension + "D";
		}

		public static String pointMedianType(int dimension) {
			return "PUNKTMEDIAN_" + dimension + "D";
		}

		public static boolean isPointType(String typeName) {
			return typeName.startsWith("PUNKTGRUPPE_") || typeName.startsWith("PUNKTMEDIAN_");
		}

		public static java.io.File baseFolder() {
			return new java.io.File(System.getProperty("user.home"), ".klein_method" + java.io.File.separator + "schwarm");
		}

		public static java.io.File reportFolder() {
			return new java.io.File(baseFolder(), "berichte");
		}

		public static java.io.File swarmCacheFile() {
			return new java.io.File(baseFolder(), "schwarm_" + SWARM_FILE);
		}

		/** Token-Datei (nie im Quellcode): ~/.klein_method/schwarm_token.txt */
		public static java.io.File tokenFile() {
			return new java.io.File(System.getProperty("user.home"), ".klein_method" + java.io.File.separator + "schwarm_token.txt");
		}

		/** Token oder null (Datei fehlt/leer). */
		public static String readToken() {
			try {
				java.io.File file = tokenFile();
				if (!file.isFile())
					return null;
				String token = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
				return token.isEmpty() ? null : token;
			} catch (java.io.IOException e) {
				return null;
			}
		}

		/** Anonyme, nicht umkehrbare Projektkennung (SHA-256 des Projektpfads, 12 Zeichen). */
		public static String projectId(String projectLocation) {
			if (projectLocation == null)
				return "unbekannt";
			try {
				java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
				byte[] hash = digest.digest(("klein_method|" + projectLocation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				StringBuilder text = new StringBuilder();
				for (int i = 0; i < 6; i++)
					text.append(String.format("%02x", hash[i] & 0xff));
				return text.toString();
			} catch (java.security.NoSuchAlgorithmException e) {
				return "unbekannt";
			}
		}

		/** Schluessel eines Erfahrungswerts: Typ|Geraeteklasse|Komponente (a/b/c). */
		public static String key(String typeName, String deviceClass, String component) {
			return typeName + "|" + deviceClass + "|" + component;
		}

		/** Bericht als Textzeilen "schluessel=wert", sortiert (Datei, Vorschau, Issue). */
		public static String toText(Map<String, String> report) {
			StringBuilder text = new StringBuilder();
			for (Map.Entry<String, String> entry : new java.util.TreeMap<String, String>(report).entrySet())
				text.append(entry.getKey()).append('=').append(entry.getValue().replace('\n', ' ').replace('\r', ' ')).append('\n');
			return text.toString();
		}

		public static Map<String, String> parseText(String text) {
			Map<String, String> report = new LinkedHashMap<String, String>();
			for (String line : text.split("\\r?\\n")) {
				int eq = line.indexOf('=');
				if (eq <= 0 || line.startsWith("#"))
					continue;
				report.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
			}
			return report;
		}

		/** Schreibt den Bericht nach berichte/Laufbericht_JJJJMMTT_HHMMSS.txt. @return Datei */
		public static java.io.File writeReport(Map<String, String> report) throws java.io.IOException {
			java.io.File folder = reportFolder();
			folder.mkdirs();
			String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
			java.io.File file = new java.io.File(folder, "Laufbericht_" + stamp + ".txt");
			java.nio.file.Files.write(file.toPath(), ("# " + REPORT_MARKER + "\n" + toText(report)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return file;
		}

		/** Alle lokalen Berichtsdateien, neueste zuerst. */
		public static List<java.io.File> reportFiles() {
			List<java.io.File> files = new ArrayList<java.io.File>();
			java.io.File[] listed = reportFolder().listFiles((dir, name) -> name.startsWith("Laufbericht_") && name.endsWith(".txt"));
			if (listed != null)
				files.addAll(java.util.Arrays.asList(listed));
			files.sort((a, b) -> b.getName().compareTo(a.getName()));
			return files;
		}

		public static Map<String, String> readReport(java.io.File file) {
			try {
				return parseText(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
			} catch (java.io.IOException e) {
				return new LinkedHashMap<String, String>();
			}
		}

		/**
		 * Erfahrungswerte aus Berichten: je Schluessel -> Projekt -> Wert des LETZTEN Laufs mit DONE in freier oder
		 * fixer Ausgleichung (Qualitaetssiegel). Aeltere Laeufe desselben Projekts zaehlen nicht. auch dynamische AGL
		 * (Punktwerte, Klasse = Festpunktart); vom Anwender verworfene Laeufe und Laeufe mit "grenzwert_erreicht=false"
		 * zaehlen nicht.
		 */
		public static Map<String, Map<String, Double>> collect(List<Map<String, String>> reports) {
			Map<String, Map<String, Double>> values = new java.util.TreeMap<String, Map<String, Double>>();
			Map<String, String> latestTime = new java.util.HashMap<String, String>();
			for (Map<String, String> report : reports) {
				if (!"true".equals(report.get("done")) || "true".equals(report.get("verworfen")))
					continue;
				// Nachlauf ohne Grenzwert ("bester Stand") zaehlt nicht; Berichte ohne den Schluessel zaehlen wie bisher
				if ("false".equals(report.get("grenzwert_erreicht")))
					continue;
				String step = report.get("stufe");
				boolean dynamic = "DYNAMIC".equals(step);
				if (!"FREE".equals(step) && !"FIXED".equals(step) && !dynamic)
					continue;
				String project = report.getOrDefault("projekt", "unbekannt");
				String deviceClass = dynamic ? report.getOrDefault("festpunktart", "SONSTIGE") : report.getOrDefault("geraeteklasse", "");
				String time = report.getOrDefault("zeit", "");
				for (Map.Entry<String, String> entry : report.entrySet()) {
					if (!entry.getKey().startsWith("sigma."))
						continue;
					String[] parts = entry.getKey().split("\\.");
					if (parts.length != 3 || isPointType(parts[1]) != dynamic)
						continue;
					double value;
					try {
						value = Double.parseDouble(entry.getValue());
					} catch (NumberFormatException e) {
						continue;
					}
					if (!(value > 0.0) || Double.isInfinite(value))
						continue;
					String k = key(parts[1], deviceClass, parts[2]);
					String timeKey = k + "#" + project;
					String previous = latestTime.get(timeKey);
					if (previous != null && previous.compareTo(time) > 0)
						continue;
					latestTime.put(timeKey, time);
					values.computeIfAbsent(k, x -> new java.util.TreeMap<String, Double>()).put(project, value);
				}
			}
			return values;
		}

		/** Schwarmdatei: Zeilen "Typ|Geraeteklasse|Komponente|Projekt=Wert". */
		public static Map<String, Map<String, Double>> parseSwarmFile(String text) {
			Map<String, Map<String, Double>> values = new java.util.TreeMap<String, Map<String, Double>>();
			for (String line : text.split("\\r?\\n")) {
				line = line.trim();
				int eq = line.indexOf('=');
				if (line.isEmpty() || line.startsWith("#") || eq <= 0)
					continue;
				String[] parts = line.substring(0, eq).split("\\|");
				if (parts.length != 4)
					continue;
				try {
					double value = Double.parseDouble(line.substring(eq + 1).trim());
					if (value > 0.0 && !Double.isInfinite(value))
						values.computeIfAbsent(key(parts[0], parts[1], parts[2]), x -> new java.util.TreeMap<String, Double>()).put(parts[3], value);
				} catch (NumberFormatException e) {
					// Zeile ungueltig - ignorieren
				}
			}
			return values;
		}

		public static Map<String, Map<String, Double>> readSwarmCache() {
			try {
				java.io.File file = swarmCacheFile();
				if (!file.isFile())
					return new java.util.TreeMap<String, Map<String, Double>>();
				return parseSwarmFile(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
			} catch (java.io.IOException e) {
				return new java.util.TreeMap<String, Map<String, Double>>();
			}
		}

		/** Lokale und Schwarm-Werte vereinigen; dasselbe Projekt zaehlt einmal (lokal hat Vorrang). */
		public static Map<String, Map<String, Double>> merge(Map<String, Map<String, Double>> local, Map<String, Map<String, Double>> swarm) {
			Map<String, Map<String, Double>> merged = new java.util.TreeMap<String, Map<String, Double>>();
			for (Map<String, Map<String, Double>> source : java.util.Arrays.asList(swarm, local))
				for (Map.Entry<String, Map<String, Double>> entry : source.entrySet())
					merged.computeIfAbsent(entry.getKey(), x -> new java.util.TreeMap<String, Double>()).putAll(entry.getValue());
			return merged;
		}

		public static double median(List<Double> values) {
			if (values.isEmpty())
				return Double.NaN;
			List<Double> sorted = new ArrayList<Double>(values);
			java.util.Collections.sort(sorted);
			int n = sorted.size();
			return n % 2 == 1 ? sorted.get(n / 2) : 0.5 * (sorted.get(n / 2 - 1) + sorted.get(n / 2));
		}

		/** Ergebnis der 10-%-Regel fuer einen Schluessel. */
		public static final class Start_Value {
			public final double value; // NaN = nicht uebernehmbar
			public final int agreeingProjects;
			public final List<Double> excludedValues;
			public final String reason;

			Start_Value(double value, int agreeingProjects, List<Double> excludedValues, String reason) {
				this.value = value;
				this.agreeingProjects = agreeingProjects;
				this.excludedValues = excludedValues;
				this.reason = reason;
			}
		}

		/**
		 * 10-%-Regel: nur Werte ANDERER Projekte; groesste Gruppe um den Median, deren Werte sich untereinander
		 * hoechstens 10 % unterscheiden (max <= 1,10 * min); mindestens 3 Projekte. Startwert = Median dieser Gruppe,
		 * Abweicher werden genannt.
		 */
		public static Start_Value tenPercentRule(Map<String, Double> byProject, String currentProject) {
			List<Double> values = new ArrayList<Double>();
			for (Map.Entry<String, Double> entry : byProject.entrySet())
				if (!entry.getKey().equals(currentProject))
					values.add(entry.getValue());
			if (values.size() < MIN_PROJECTS)
				return new Start_Value(Double.NaN, 0, new ArrayList<Double>(),
						"nur " + values.size() + " andere(s) Projekt(e) - mindestens " + MIN_PROJECTS + " nötig");
			java.util.Collections.sort(values);
			double median = median(values);
			int bestStart = -1, bestEnd = -1;
			double bestCenterDistance = Double.MAX_VALUE;
			for (int i = 0; i < values.size(); i++) {
				int j = i;
				while (j + 1 < values.size() && values.get(j + 1) <= values.get(i) * (1.0 + MAX_RELATIVE_SPREAD) + 1e-12)
					j++;
				// die Gruppe muss den Median enthalten ("Gruppe um den Median")
				if (median < values.get(i) - 1e-12 || median > values.get(j) + 1e-12)
					continue;
				int size = j - i + 1, bestSize = bestEnd - bestStart + 1;
				double center = 0.5 * (values.get(i) + values.get(j));
				double centerDistance = Math.abs(center - median);
				if (bestStart < 0 || size > bestSize || (size == bestSize && centerDistance < bestCenterDistance)) {
					bestStart = i;
					bestEnd = j;
					bestCenterDistance = centerDistance;
				}
			}
			List<Double> excluded = new ArrayList<Double>();
			if (bestStart < 0)
				return new Start_Value(Double.NaN, 0, values, "keine übereinstimmende Gruppe um den Median");
			for (int k = 0; k < values.size(); k++)
				if (k < bestStart || k > bestEnd)
					excluded.add(values.get(k));
			int size = bestEnd - bestStart + 1;
			if (size < MIN_PROJECTS)
				return new Start_Value(Double.NaN, size, excluded,
						"nur " + size + " übereinstimmende(s) Projekt(e) (max. 10 % Unterschied) - mindestens " + MIN_PROJECTS + " nötig");
			return new Start_Value(median(values.subList(bestStart, bestEnd + 1)), size, excluded, null);
		}

		/** Vergleichswert fuer die Warnung nach Phase 1: Median anderer Projekte, NaN unter 3 Projekten. */
		public static double reference(Map<String, Double> byProject, String currentProject) {
			List<Double> values = new ArrayList<Double>();
			for (Map.Entry<String, Double> entry : byProject.entrySet())
				if (!entry.getKey().equals(currentProject))
					values.add(entry.getValue());
			return values.size() < MIN_PROJECTS ? Double.NaN : median(values);
		}

		// ---------------- GitHub ----------------

		public static int downloadSwarmFile(String repository) throws java.io.IOException {
			byte[] bytes = null;
			String token = readToken();
			int tokenCode = 0;
			if (token != null) {
				java.net.HttpURLConnection api = (java.net.HttpURLConnection) new java.net.URL(
						"https://api.github.com/repos/" + repository.trim() + "/contents/" + SWARM_FILE + "?ref=main").openConnection();
				api.setConnectTimeout(10000);
				api.setReadTimeout(20000);
				api.setRequestProperty("User-Agent", "Klein_Method");
				api.setRequestProperty("Accept", "application/vnd.github.raw");
				api.setRequestProperty("Authorization", "Bearer " + token);
				api.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
				tokenCode = api.getResponseCode();
				if (tokenCode == 200) {
					try (java.io.InputStream in = api.getInputStream()) {
						bytes = in.readAllBytes();
					}
				}
			}
			if (bytes == null) {
				java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
						"https://raw.githubusercontent.com/" + repository.trim() + "/main/" + SWARM_FILE).openConnection();
				connection.setConnectTimeout(10000);
				connection.setReadTimeout(20000);
				connection.setRequestProperty("User-Agent", "Klein_Method");
				int code = connection.getResponseCode();
				if (code != 200) {
					String withToken = token == null ? " Ohne Token-Datei ist nur ein oeffentliches Repository lesbar."
							: " Abruf mit Token: HTTP " + tokenCode + (tokenCode == 401 ? " (Token abgelaufen/widerrufen)"
									: tokenCode == 403 || tokenCode == 404 ? " (Token ohne Recht \"Contents: Read-only\" fuer dieses Repository, "
											+ "oder Datei/Repository nicht vorhanden)" : "") + ".";
					if (code == 404)
						throw new java.io.IOException("Datei " + SWARM_FILE + " im Repository " + repository + " (Zweig main) nicht gefunden (HTTP 404)." + withToken
								+ " Hinweis: Die Datei entsteht erst, wenn die Schwarm-Auswertung (GitHub Actions) nach dem ersten Bericht gelaufen ist;"
								+ " sonst Schreibweise im Feld \"Repository\" prüfen.");
					throw new java.io.IOException("HTTP " + code + "." + withToken);
				}
				try (java.io.InputStream in = connection.getInputStream()) {
					bytes = in.readAllBytes();
				}
			}
			String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
			int count = 0;
			for (Map<String, Double> m : parseSwarmFile(text).values())
				count += m.size();
			baseFolder().mkdirs();
			java.nio.file.Files.write(swarmCacheFile().toPath(), bytes);
			return count;
		}

		private static String jsonString(String text) {
			StringBuilder out = new StringBuilder("\"");
			for (char c : text.toCharArray()) {
				switch (c) {
				case '"': out.append("\\\""); break;
				case '\\': out.append("\\\\"); break;
				case '\n': out.append("\\n"); break;
				case '\r': break;
				case '\t': out.append("\\t"); break;
				default:
					if (c < 0x20)
						out.append(String.format("\\u%04x", (int) c));
					else
						out.append(c);
				}
			}
			return out.append('"').toString();
		}

		/** Titel des Issues fuer einen Bericht. */
		public static String issueTitle(Map<String, String> report) {
			return TITLE_PREFIX + " " + report.getOrDefault("version", "") + " " + report.getOrDefault("stufe", "") + " "
					+ ("true".equals(report.get("done")) ? "DONE" : "ohne DONE") + " " + report.getOrDefault("zeit", "");
		}

		/** Inhalt des Issues: Kennung + Bericht im Codeblock (maschinenlesbar fuer die GitHub Action). */
		public static String issueBody(Map<String, String> report) {
			return REPORT_MARKER + "\n\nAnonymer Laufbericht von Klein_Method (JAG3D) - keine Koordinaten, keine Punkt- oder Projektnamen.\n\n```\n"
					+ toText(report) + "```\n";
		}

		/** GET mit Token, liefert {HTTP-Code, Antwort}; bei Netzfehler {-1, Meldung}. */
		private static Object[] githubGet(String path, String token) {
			try {
				java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("https://api.github.com" + path).openConnection();
				connection.setConnectTimeout(10000);
				connection.setReadTimeout(20000);
				connection.setRequestProperty("Accept", "application/vnd.github+json");
				connection.setRequestProperty("Authorization", "Bearer " + token);
				connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
				connection.setRequestProperty("User-Agent", "Klein_Method");
				int code = connection.getResponseCode();
				java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
				String response = "";
				if (stream != null)
					try (java.io.InputStream in = stream) {
						response = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
					}
				return new Object[] { code, response };
			} catch (Exception e) {
				return new Object[] { -1, e.getMessage() };
			}
		}

		/**
		 * Ursache eingrenzen - wem gehoert der Token, sieht er das Repository, darf er Issues schreiben? Nur
		 * Lesezugriffe, der Token selbst erscheint nirgends im Text.
		 */
		static String diagnoseAccess(String repository, String token) {
			StringBuilder text = new StringBuilder("Diagnose: ");
			String owner = repository.trim().contains("/") ? repository.trim().substring(0, repository.trim().indexOf('/')) : "";
			if (token == null || token.trim().isEmpty())
				return text.append("keine Token-Datei gefunden (~/.klein_method/schwarm_token.txt).").toString();
			String trimmed = token.trim();
			if (!trimmed.equals(token) || trimmed.startsWith("\uFEFF"))
				text.append("Token-Datei enthält Leerzeichen/Zeilenumbruch oder BOM am Rand. ");
			if (!trimmed.startsWith("github_pat_") && !trimmed.startsWith("ghp_"))
				text.append("Token beginnt nicht mit \"github_pat_\" - Inhalt der Token-Datei prüfen. ");
			Object[] user = githubGet("/user", trimmed);
			int userCode = (Integer) user[0];
			String login = null;
			if (userCode == 200) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"login\"\\s*:\\s*\"([^\"]+)\"").matcher((String) user[1]);
				login = m.find() ? m.group(1) : null;
			}
			if (userCode == 401)
				return text.append("Token ungültig, abgelaufen oder widerrufen (HTTP 401) - neuen Token erzeugen.").toString();
			if (login != null) {
				text.append("Token gehört zum Konto \"").append(login).append("\". ");
				if (!owner.isEmpty() && !login.equalsIgnoreCase(owner))
					text.append("Das Repository gehört aber \"").append(owner).append("\" - Token mit dem Konto ").append(owner)
							.append(" erzeugen (Resource owner = ").append(owner).append("). ");
			}
			Object[] repo = githubGet("/repos/" + repository.trim(), trimmed);
			int repoCode = (Integer) repo[0];
			if (repoCode == 200) {
				boolean issues = !((String) repo[1]).matches("(?s).*\"has_issues\"\\s*:\\s*false.*");
				if (!issues)
					return text.append("Repository sichtbar, aber Issues sind im Repository ausgeschaltet (Settings -> General -> Features -> Issues einschalten).").toString();
				return text.append("Repository für den Token sichtbar - es fehlt das Token-Recht \"Issues: Read and write\" (Token bearbeiten -> Repository permissions -> Issues).").toString();
			}
			if (repoCode == 404)
				return text.append("Der Token sieht das Repository nicht: Token bearbeiten -> \"Repository access\" -> \"Only select repositories\" -> ")
						.append(repository.trim().substring(owner.isEmpty() ? 0 : owner.length() + 1))
						.append(" auswählen (bei \"Public repositories\" sind private Repositories nicht erreichbar). Schreibweise im Feld \"Repository\" prüfen.").toString();
			if (repoCode == -1)
				return text.append("Keine Verbindung zu GitHub: ").append(repo[1]).toString();
			return text.append("Abfrage des Repositorys: HTTP ").append(repoCode).append('.').toString();
		}

		/** Legt das Issue an (Fine-grained Token, nur "Issues"). @return Adresse des Issues */
		/** Gueltige Adresse des Vermittlungsdienstes: https://..., zum Testen auch http://localhost bzw. http://127.0.0.1; leer = keiner. */
		public static boolean isValidRelayUrl(String url) {
			if (url == null)
				return false;
			String trimmed = url.trim();
			return trimmed.isEmpty() || trimmed.matches("https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?")
					|| trimmed.matches("http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?");
		}

		/**
		 * Sendet den Bericht an den Vermittlungsdienst (POST JSON {repository, title, body}). Der Dienst haelt den Token
		 * als Geheimnis, prueft den Bericht und legt das Issue an. @return Adresse des Issues
		 */
		public static String sendViaRelay(String relayUrl, String repository, String title, String body) throws java.io.IOException {
			String base = relayUrl.trim();
			while (base.endsWith("/"))
				base = base.substring(0, base.length() - 1);
			java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(base + "/bericht").openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("User-Agent", "Klein_Method");
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			byte[] payload = ("{\"repository\":" + jsonString(repository.trim()) + ",\"title\":" + jsonString(title) + ",\"body\":" + jsonString(body) + "}")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			try (java.io.OutputStream out = connection.getOutputStream()) {
				out.write(payload);
			}
			int code = connection.getResponseCode();
			java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
			String response = "";
			if (stream != null)
				try (java.io.InputStream in = stream) {
					response = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
			java.util.regex.Matcher url = java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
			if (code == 201 && url.find())
				return url.group(1);
			java.util.regex.Matcher error = java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"").matcher(response);
			String reason = error.find() ? error.group(1) : (response.length() > 200 ? response.substring(0, 200) : response);
			if (code == 429)
				throw new java.io.IOException("Vermittlungsdienst: zu viele Berichte in kurzer Zeit (HTTP 429), bitte spaeter erneut senden. " + reason);
			throw new java.io.IOException("Vermittlungsdienst " + base + ": HTTP " + code + (reason.isEmpty() ? "" : " - " + reason));
		}

		/**
		 * Sendet einen Bericht: mit Token-Datei direkt an GitHub, sonst ueber den Vermittlungsdienst.
		 * @return Adresse des Issues; IOException, wenn beides nicht moeglich ist
		 */
		public static String sendReport(String repository, String relayUrl, String title, String body) throws java.io.IOException {
			String token = readToken();
			if (token != null)
				return createIssue(repository, token, title, body);
			if (relayUrl == null || relayUrl.trim().isEmpty())
				throw new java.io.IOException("Keine Token-Datei und kein Vermittlungsdienst eingetragen.");
			return sendViaRelay(relayUrl, repository, title, body);
		}

		/** Weg, auf dem gesendet wird: "Token", "Vermittlungsdienst" oder "" (keiner). */
		public static String sendRoute(String relayUrl) {
			if (readToken() != null)
				return "Token";
			return relayUrl != null && !relayUrl.trim().isEmpty() ? "Vermittlungsdienst" : "";
		}

		/** Neues-Issue-Formular von GitHub, vorausgefuellt; null, wenn der Bericht fuer die Adresszeile zu lang ist. */
		public static String browserIssueUrl(String repository, String title, String body, boolean withBody) {
			try {
				String url = "https://github.com/" + repository.trim() + "/issues/new?title=" + java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
						+ (withBody ? "&body=" + java.net.URLEncoder.encode(body, "UTF-8").replace("+", "%20") : "");
				return url.length() <= BROWSER_URL_MAX_LENGTH ? url : null;
			} catch (java.io.UnsupportedEncodingException e) {
				return null;
			}
		}

		public static String createIssue(String repository, String token, String title, String body) throws java.io.IOException {
			java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
					"https://api.github.com/repos/" + repository.trim() + "/issues").openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(20000);
			connection.setRequestProperty("Accept", "application/vnd.github+json");
			connection.setRequestProperty("Authorization", "Bearer " + token);
			connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
			connection.setRequestProperty("User-Agent", "Klein_Method");
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			byte[] payload = ("{\"title\":" + jsonString(title) + ",\"body\":" + jsonString(body) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
			try (java.io.OutputStream out = connection.getOutputStream()) {
				out.write(payload);
			}
			int code = connection.getResponseCode();
			java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
			String response = "";
			if (stream != null)
				try (java.io.InputStream in = stream) {
					response = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
			if (code == 401 || code == 403)
				throw new java.io.IOException("Zugriff verweigert (HTTP " + code + ") - Token abgelaufen, gesperrt oder ohne Recht \"Issues: Read and write\" für " + repository);
			if (code == 404)
				throw new java.io.IOException("Repository " + repository + " nicht gefunden oder Token nicht für dieses Repository freigegeben (HTTP 404). "
						+ diagnoseAccess(repository, token));
			if (code != 201)
				throw new java.io.IOException("HTTP " + code + ": " + (response.length() > 300 ? response.substring(0, 300) : response));
			java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
			return matcher.find() ? matcher.group(1) : "https://github.com/" + repository + "/issues";
		}
	}

	/**
	 * Pruefung, ob die Projektionsreduktion in JAG3D zur Koordinatenart passt. Erkennt UTM mit Zonenkennung
	 * (Rechtswert zz + 6 Stellen), Gauss-Krueger (Streifen + 6 Stellen) und schaetzt die Streckenverzerrung aus dem
	 * mittleren Rechtswert.
	 */
	public static final class Klein_Method_Projection_Check {
		public static final double HINT_PPM = 10.0;
		private static final double EARTH_RADIUS = 6381000.0;

		private Klein_Method_Projection_Check() {
		}

		/** {Art "UTM"/"GK"/null, Zone/Streifen, mittlerer Rechtswert im Streifen [m], Verzerrung [ppm], Netzlaenge [m]}. */
		public static Object[] detect(List<double[]> yx) {
			int utm = 0, gk = 0, n = 0;
			double sumEast = 0.0;
			java.util.Map<Integer, Integer> zones = new java.util.HashMap<Integer, Integer>();
			double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
			for (double[] p : yx) {
				if (p == null || Double.isNaN(p[0]) || Double.isNaN(p[1]) || (p[0] == 0.0 && p[1] == 0.0))
					continue;
				n++;
				double y = p[0], x = p[1];
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				int zone = (int) Math.floor(y / 1.0e6);
				double east = y - zone * 1.0e6;
				if (y >= 1.0e7 && y < 6.1e7 && zone >= 1 && zone <= 60 && east > 100000.0 && east < 900000.0 && x > 0.0 && x < 1.0e7) {
					utm++;
					sumEast += east;
					zones.merge(zone, 1, Integer::sum);
				} else if (y >= 1.0e6 && y < 1.0e7 && zone >= 1 && zone <= 9 && east > 100000.0 && east < 900000.0 && x > 4.0e6 && x < 7.0e6) {
					gk++;
					sumEast += east;
					zones.merge(zone, 1, Integer::sum);
				}
			}
			if (n == 0)
				return new Object[] { null, 0, 0.0, 0.0, 0.0 };
			String kind = utm * 2 > n ? "UTM" : gk * 2 > n ? "GK" : null;
			if (kind == null)
				return new Object[] { null, 0, 0.0, 0.0, 0.0 };
			int zone = 0, best = -1;
			for (java.util.Map.Entry<Integer, Integer> e : zones.entrySet())
				if (e.getValue() > best) {
					best = e.getValue();
					zone = e.getKey();
				}
			double east = sumEast / (utm + gk);
			double d = east - 500000.0;
			double k = ("UTM".equals(kind) ? 0.9996 : 1.0) * (1.0 + d * d / (2.0 * EARTH_RADIUS * EARTH_RADIUS));
			double length = Math.hypot(maxY - minY, maxX - minX);
			return new Object[] { kind, zone, east, (k - 1.0) * 1.0e6, length };
		}

		public static String hint(List<double[]> yx, org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType current) {
			Object[] d = detect(yx);
			String kind = (String) d[0];
			if (kind == null)
				return null;
			double ppm = (Double) d[3], length = (Double) d[4];
			boolean matches = current != null && ("UTM".equals(kind) ? current == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.UTM
					: current == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.GAUSS_KRUEGER);
			if (matches || Math.abs(ppm) < HINT_PPM)
				return null;
			String kindText = "UTM".equals(kind) ? String.format(tr("UTM Zone %d"), (Integer) d[1]) : String.format(tr("Gau\u00DF-Kr\u00FCger Streifen %d"), (Integer) d[1]);
			String currentText = current == null ? "-" : current.name();
			return String.format(tr("Die Koordinaten sehen nach %s aus, in JAG3D ist als Projektion aber \u201E%s\u201C eingestellt. "
					+ "Bei einem Rechtswert von %s km betr\u00E4gt die Streckenverzerrung etwa %s ppm, auf %s also rund %s. "
					+ "Bitte in den JAG3D-Projekteinstellungen die Projektion (Reduktion) auf %s setzen und neu ausgleichen."),
					kindText, currentText, String.format(Locale.GERMANY, "%.0f", (Double) d[2] / 1000.0), String.format(Locale.GERMANY, "%+.0f", ppm),
					Klein_Method_Fixed_Point_Recommendation.formatLength(length), String.format(Locale.GERMANY, "%.1f cm", Math.abs(ppm) * 1.0e-6 * length * 100.0),
					"UTM".equals(kind) ? "UTM" : tr("Gau\u00DF-Kr\u00FCger"));
		}
	}

	/**
	 * Koordinaten-Export in einem anderen Koordinatensystem - Umformungen mit eindeutigen Formeln (gleiches Datum
	 * ETRS89/WGS84, GRS80), kein Geoidmodell. zusaetzlich die Definitionen des Projektionsordners, die eindeutig
	 * sind: reine Umformung (ETRS89/GRS80 ohne Datumsuebergang, z. B. UTM ohne Kennung) oder Datumsuebergang ueber
	 * ein amtliches NTv2-Gitter (z. B. RD/83 mit NTv2_SN). 7-Parameter-Saetze (ca. 1-3 m) bleiben fuer den Export
	 * gesperrt.
	 *
	 * Projekt lokal (ENh, P0 im Projekt) / geozentrisch: Hoehe ellipsoidisch (exakt); bei Gitter-Zielen mit Hinweis
	 * "keine NN/NHN-Hoehe".
	 *
	 * Projekt UTM mit Zonenkennung oder Gauss-Krueger mit Gitter-Definition (Streifenkennung): Hoehe = Projekthoehe
	 * unveraendert; geozentrisch/lokal daher nicht angeboten.
	 *
	 * sonst: nur das Projektsystem. JAG3D-Spaltenfolge bleibt erhalten: y-Spalte = Rechtswert/Ost/geozentrisch Y,
	 * x-Spalte = Hochwert/Nord/geozentrisch X.
	 */
	public static final class Klein_Method_Coordinate_Export {

		private Klein_Method_Coordinate_Export() {
		}

		/** Koordinatenart des Projekts (Mehrheit der Punkte). */
		public enum Source {
			LOCAL_P0("lokal (ENh) um P0"), ECEF("ETRS89/WGS84 geozentrisch"), UTM_ZONE("ETRS89/UTM mit Zonenkennung"),
			DEFINITION("Gauss-Krueger mit Gitter-Definition aus dem Projektionsordner"), OTHER("nicht eindeutig umformbar");

			public final String label;

			Source(String label) {
				this.label = label;
			}
		}

		/** Feste Zielsysteme; DEFINITION = Datei aus dem Projektionsordner (siehe Option). */
		public enum Target {
			PROJECT("Projektsystem (wie gerechnet)", "Projektsystem"),
			ECEF("ETRS89/WGS84 geozentrisch (Y/X/Z)", "ETRS89-WGS84_geozentrisch"),
			GEOGRAPHIC("ETRS89/WGS84 geographisch (B/L/h)", "ETRS89-WGS84_geographisch"),
			UTM32("ETRS89/UTM Zone 32 (mit Zonenkennung)", "ETRS89_UTM32"),
			UTM33("ETRS89/UTM Zone 33 (mit Zonenkennung)", "ETRS89_UTM33"),
			LOCAL("Lokal (ENh) um P0", "lokal_ENh"),
			DEFINITION("Projektionsdatei", "Projektionsdatei");

			public final String displayName, fileTag;

			Target(String displayName, String fileTag) {
				this.displayName = displayName;
				this.fileTag = fileTag;
			}
		}

		/** Eintrag im Dropdown - festes Ziel oder Definition aus dem Projektionsordner. */
		public static final class Option {
			public final Target target;
			public final Klein_Method_Geo.Projection_Definition definition;

			public Option(Target target, Klein_Method_Geo.Projection_Definition definition) {
				this.target = target;
				this.definition = definition;
			}

			/** gespeicherter Schluessel: Name des festen Ziels bzw. "FILE:" + Dateiname der Definition. */
			public String key() {
				return this.target == Target.DEFINITION ? "FILE:" + this.definition.name : this.target.name();
			}

			/** deutscher Anzeigename (feste Ziele werden in der Oberflaeche uebersetzt, Dateinamen nicht). */
			public String displayName() {
				if (this.target != Target.DEFINITION)
					return this.target.displayName;
				if (!this.definition.hasGrid())
					return this.definition.name;
				return this.definition.name.contains(this.definition.gridName()) ? this.definition.name + " - Transformation NTv2"
						: this.definition.name + " - Transformation NTv2 (" + this.definition.gridName() + ")";
			}

			public String fileTag() {
				return this.target == Target.DEFINITION ? this.definition.name.replaceAll("[^A-Za-z0-9._-]+", "_") : this.target.fileTag;
			}

			public boolean isGridTransformation() {
				return this.target == Target.DEFINITION && this.definition.hasGrid();
			}

			@Override
			public String toString() {
				return this.displayName();
			}
		}

		/**
		 * Gitter-Definition zur Streifenkennung im Rechtswert (Gauss-Krueger, bestes Gitter am Punkt), sonst null.
		 */
		public static Klein_Method_Geo.Projection_Definition gridDefinitionFor(double y, double x, List<Klein_Method_Geo.Projection_Definition> definitions) {
			int prefix = Klein_Method_Geo.zoneOfEasting(y);
			if (prefix < 2 || prefix > 9)
				return null;
			return Klein_Method_Geo.bestGridDefinition(definitions, y, x);
		}

		/**
		 * Ordnet einen Punkt (JAG3D y, x, z) einer Koordinatenart zu. projectLocal = im Projekt gespeichertes P0 der
		 * Klein_Method-Umrechnung oder null - nur dann gilt ein Punkt als "lokal um P0" (sonst waere jedes beliebige
		 * lokale System faelschlich umformbar). definitions = Definitionen des Projektionsordners (fuer Gauss-Krueger).
		 */
		public static Source classify(double y, double x, double z, Klein_Method_Local_System.Parameters projectLocal,
				List<Klein_Method_Geo.Projection_Definition> definitions) {
			if (Klein_Method_Local_System.isEcef(x, y, z))
				return Source.ECEF;
			int zone = Klein_Method_Geo.zoneOfEasting(y);
			double eastPart = y - zone * 1000000.0;
			if (zone >= 28 && zone <= 38 && eastPart > 100000.0 && eastPart < 900000.0 && x > 0.0 && x < 9350000.0)
				return Source.UTM_ZONE;
			if (zone >= 2 && zone <= 9 && eastPart > 100000.0 && eastPart < 900000.0 && x > 5000000.0 && x < 6200000.0
					&& gridDefinitionFor(y, x, definitions) != null)
				return Source.DEFINITION;
			if (projectLocal != null && projectLocal.valid() && Klein_Method_Local_System.inLocalSystem(projectLocal, y, x, z))
				return Source.LOCAL_P0;
			return Source.OTHER;
		}

		/** Mehrheit der Koordinatenarten (Punkte ohne vollstaendige Koordinaten zaehlen nicht). */
		public static Source detect(List<double[]> yxz, Klein_Method_Local_System.Parameters projectLocal,
				List<Klein_Method_Geo.Projection_Definition> definitions) {
			Map<Source, Integer> count = new java.util.EnumMap<Source, Integer>(Source.class);
			for (double[] p : yxz)
				count.merge(classify(p[0], p[1], p[2], projectLocal, definitions), 1, Integer::sum);
			Source best = Source.OTHER;
			int max = 0;
			for (Map.Entry<Source, Integer> e : count.entrySet())
				if (e.getValue() > max) {
					max = e.getValue();
					best = e.getKey();
				}
			return best;
		}

		/** Zielsysteme, die eindeutig ableitbar sind (Projektsystem immer zuerst, dann feste, dann Definitionen). */
		public static List<Option> available(Source source, List<Klein_Method_Geo.Projection_Definition> definitions) {
			List<Option> list = new ArrayList<Option>();
			list.add(new Option(Target.PROJECT, null));
			List<Target> fixed = new ArrayList<Target>();
			switch (source) {
			case LOCAL_P0:
				fixed.addAll(java.util.Arrays.asList(Target.ECEF, Target.GEOGRAPHIC, Target.UTM32, Target.UTM33));
				break;
			case ECEF:
				fixed.addAll(java.util.Arrays.asList(Target.GEOGRAPHIC, Target.UTM32, Target.UTM33, Target.LOCAL));
				break;
			case UTM_ZONE:
			case DEFINITION:
				fixed.addAll(java.util.Arrays.asList(Target.GEOGRAPHIC, Target.UTM32, Target.UTM33));
				break;
			default:
				return list;
			}
			for (Target t : fixed)
				list.add(new Option(t, null));
			if (definitions != null)
				for (Klein_Method_Geo.Projection_Definition d : definitions)
					if (d.exportAllowed())
						list.add(new Option(Target.DEFINITION, d));
			return list;
		}

		/** true = Hoehe ist die Projekthoehe (UTM/Gauss-Krueger-Projekt), keine ellipsoidische. */
		private static boolean projectHeight(Source source) {
			return source == Source.UTM_ZONE || source == Source.DEFINITION;
		}

		/** Spaltennamen {1, 2, 3} des Zielsystems (fuer CSV-Kopf und Hinweiszeile). */
		public static String[] columns(Option option, Source source) {
			switch (option.target) {
			case ECEF:
				return new String[] { "Y", "X", "Z" };
			case GEOGRAPHIC:
				return new String[] { "Breite", "Laenge", projectHeight(source) ? "Hoehe" : "h" };
			case DEFINITION:
				if (option.definition.isGeographic())
					return new String[] { "Breite", "Laenge", projectHeight(source) ? "Hoehe" : "h" };
				return new String[] { "Rechtswert", "Hochwert", projectHeight(source) ? "Hoehe" : "h" };
			case UTM32:
			case UTM33:
				return new String[] { "Rechtswert", "Hochwert", projectHeight(source) ? "Hoehe" : "h" };
			case LOCAL:
				return new String[] { "Ost", "Nord", "Oben" };
			default:
				return new String[] { "Rechtswert", "Hochwert", "Hoehe" };
			}
		}

		/** Hoehenangabe fuer die Hinweiszeile. */
		public static String heightNote(Option option, Source source) {
			if (option.target == Target.PROJECT)
				return "Projekthoehe";
			if (option.target == Target.ECEF)
				return "geozentrisch";
			if (option.target == Target.LOCAL)
				return "lokal Oben inkl. Additionskonstante";
			if (projectHeight(source))
				return "Projekthoehe unveraendert (keine Umrechnung)";
			return option.isGridTransformation() ? "ellipsoidisch (ETRS89/GRS80) - keine NN/NHN-Hoehe" : "ellipsoidisch (GRS80)";
		}

		/**
		 * Rechnet einen Punkt (JAG3D y, x, z) um. Liefert null, wenn der Punkt nicht zur Koordinatenart des Projekts
		 * gehoert oder ausserhalb eines Gitters liegt (dann "k.A." im Export). Geographisch: {Breite, Laenge, Hoehe} in
		 * Grad; sonst JAG3D-Spaltenfolge. targetLocal = P0 fuer das Ziel "lokal" (Projekt-P0, sonst die Einstellung der
		 * Koordinatenumrechnung).
		 */
		public static double[] convert(Source source, Option option, double y, double x, double z, Klein_Method_Local_System.Parameters projectLocal,
				Klein_Method_Local_System.Parameters targetLocal, List<Klein_Method_Geo.Projection_Definition> definitions) {
			if (option.target == Target.PROJECT)
				return new double[] { y, x, z };
			if (classify(y, x, z, projectLocal, definitions) != source)
				return null;
			double lat, lon, h;
			double[] ecef = null; // {X, Y, Z}
			switch (source) {
			case LOCAL_P0: {
				double[] p0 = projectLocal.p0();
				double[] d = projectLocal.vectorToEcef(y - projectLocal.addEast, x - projectLocal.addNorth, z - projectLocal.addUp);
				ecef = new double[] { p0[0] + d[0], p0[1] + d[1], p0[2] + d[2] };
				double[] g = Klein_Method_Geo.ecefToLatLonHeight(ecef[0], ecef[1], ecef[2]);
				lat = g[0];
				lon = g[1];
				h = g[2];
				break;
			}
			case ECEF: {
				ecef = new double[] { x, y, z };
				double[] g = Klein_Method_Geo.ecefToLatLonHeight(x, y, z);
				lat = g[0];
				lon = g[1];
				h = g[2];
				break;
			}
			case UTM_ZONE:
			case DEFINITION: {
				double[] g = source == Source.UTM_ZONE ? Klein_Method_Geo.utmToLatLon(y, x) : gridDefinitionFor(y, x, definitions).toLatLon(y, x);
				if (g == null)
					return null;
				lat = g[0];
				lon = g[1];
				h = z;
				break;
			}
			default:
				return null;
			}
			switch (option.target) {
			case ECEF:
				return ecef == null ? null : new double[] { ecef[1], ecef[0], ecef[2] };
			case GEOGRAPHIC:
				return new double[] { lat, lon, h };
			case UTM32:
			case UTM33: {
				double[] en = Klein_Method_Geo.latLonToUtm(lat, lon, option.target == Target.UTM32 ? 32 : 33);
				return new double[] { en[0], en[1], h };
			}
			case DEFINITION: {
				double[] en = option.definition.fromLatLon(lat, lon);
				return en == null ? null : new double[] { en[0], en[1], h };
			}
			case LOCAL: {
				if (ecef == null || targetLocal == null || !targetLocal.valid())
					return null;
				double[] p0 = targetLocal.p0();
				double[] d = targetLocal.vectorToLocal(ecef[0] - p0[0], ecef[1] - p0[1], ecef[2] - p0[2]);
				return new double[] { targetLocal.addEast + d[0], targetLocal.addNorth + d[1], targetLocal.addUp + d[2] };
			}
			default:
				return null;
			}
		}
	}

	/**
	 * Umrechnung geozentrisch WGS84 (X/Y/Z) <-> lokales Horizontsystem (Ost/Nord/Oben) um den Bezugspunkt P0 plus
	 * Additionskonstanten.
	 *
	 * Basislinien: nur Drehung. Alle Grundwerte editierbar; Standard = Werte aus dem VBA-Code. JAG3D-Spalten: y =
	 * Rechtswert/Ost bzw. geozentrisch Y, x = Hochwert/Nord bzw. geozentrisch X, z = Hoehe bzw. Z.
	 */
	public static final class Klein_Method_Local_System {
		public static final double DEFAULT_LATITUDE = 51.3539773354, DEFAULT_LONGITUDE = 12.3740940991, DEFAULT_HEIGHT = 178.391443967;
		public static final double DEFAULT_ADD_EAST = 200000.0, DEFAULT_ADD_NORTH = 400000.0, DEFAULT_ADD_UP = 1000.0;
		public static final int DEFAULT_DECIMALS = 4;
		/**
		 * Ausreisser beim Schwerpunkt fuer P0 (z. B. Naeherung weit daneben, Gebrauchshoehe statt ellipsoidischer Hoehe):
		 * ein Neupunkt zaehlt nicht mit, wenn er waagerecht weiter als max(CENTROID_MIN_HORIZONTAL_LIMIT,
		 * CENTROID_MAD_FACTOR x Median der waagerechten Abstaende) vom Median-Punkt liegt oder seine ellipsoidische Hoehe
		 * um mehr als max(CENTROID_MIN_HEIGHT_LIMIT, CENTROID_MAD_FACTOR x mittlere absolute Abweichung) vom Median der
		 * Hoehen abweicht [m].
		 */
		public static final double CENTROID_MIN_HORIZONTAL_LIMIT = 200.0, CENTROID_MIN_HEIGHT_LIMIT = 30.0, CENTROID_MAD_FACTOR = 5.0;
		/** Rundung von P0: Breite/Laenge auf 0,00001 Grad (ca. 1 m), ellipsoidische Hoehe auf 0,01 m. */
		public static final double P0_ROUND_DEGREES = 1.0e-5, P0_ROUND_HEIGHT = 0.01;
		/** Ab dieser Netzausdehnung [m] mit Zenitwinkeln/Nivellement: Hinweis auf JAG3D "lokal ellipsoidisch". */
		public static final double HINT_EXTENT = 1000.0;
		/**
		 * Weicht die ellipsoidische Hoehe eines umzurechnenden Punkts um mehr als diesen Wert [m] vom Median der GNSS-Punkte ab,
		 * wurde vermutlich Hoehe 0 oder eine Normalhoehe (NHN) als ellipsoidische Hoehe verwendet - Hinweis bei der Umrechnung.
		 */
		public static final double HEIGHT_CHECK_LIMIT = 20.0;
		/** Hoehenpruefung nur fuer Punkte bis zu diesem waagerechten Abstand [m] von P0 (Messgebiet, ohne ferne Referenzstationen). */
		public static final double HEIGHT_CHECK_RADIUS = 5000.0;

		private Klein_Method_Local_System() {
		}

		public static final class Parameters {
			public double latitude = DEFAULT_LATITUDE, longitude = DEFAULT_LONGITUDE, height = DEFAULT_HEIGHT;
			public double addEast = DEFAULT_ADD_EAST, addNorth = DEFAULT_ADD_NORTH, addUp = DEFAULT_ADD_UP;
			public int decimals = DEFAULT_DECIMALS;

			public Parameters copy() {
				Parameters p = new Parameters();
				p.latitude = this.latitude;
				p.longitude = this.longitude;
				p.height = this.height;
				p.addEast = this.addEast;
				p.addNorth = this.addNorth;
				p.addUp = this.addUp;
				p.decimals = this.decimals;
				return p;
			}

			/** "B;L;h;Ost;Nord;Oben;Stellen" (Projekteinstellung). */
			public String encode() {
				return this.latitude + ";" + this.longitude + ";" + this.height + ";" + this.addEast + ";" + this.addNorth + ";" + this.addUp + ";" + this.decimals;
			}

			public static Parameters decode(String text) {
				if (text == null)
					return null;
				String[] v = text.split(";");
				if (v.length < 7)
					return null;
				try {
					Parameters p = new Parameters();
					p.latitude = Double.parseDouble(v[0]);
					p.longitude = Double.parseDouble(v[1]);
					p.height = Double.parseDouble(v[2]);
					p.addEast = Double.parseDouble(v[3]);
					p.addNorth = Double.parseDouble(v[4]);
					p.addUp = Double.parseDouble(v[5]);
					p.decimals = Integer.parseInt(v[6]);
					return p.valid() ? p : null;
				} catch (NumberFormatException e) {
					return null;
				}
			}

			public boolean valid() {
				return Math.abs(this.latitude) <= 90.0 && Math.abs(this.longitude) <= 180.0 && Math.abs(this.height) < 10000.0
						&& Double.isFinite(this.addEast) && Double.isFinite(this.addNorth) && Double.isFinite(this.addUp)
						&& this.decimals >= 0 && this.decimals <= 8;
			}

			/** Geozentrische Koordinaten von P0 (GRS80) {X, Y, Z}. */
			public double[] p0() {
				return geodeticToEcef(this.latitude, this.longitude, this.height);
			}

			/** Drehmatrix wie im VBA: Zeile 1 Ost, Zeile 2 Nord, Zeile 3 Oben; Spalten X, Y, Z. */
			double[][] rotation() {
				double b = Math.toRadians(this.latitude), l = Math.toRadians(this.longitude);
				return new double[][] { { -Math.sin(l), Math.cos(l), 0.0 }, { -Math.sin(b) * Math.cos(l), -Math.sin(b) * Math.sin(l), Math.cos(b) },
						{ Math.cos(b) * Math.cos(l), Math.cos(b) * Math.sin(l), Math.sin(b) } };
			}

			public double round(double v) {
				double f = Math.pow(10.0, this.decimals);
				return Math.round(v * f) / f;
			}

			/** geozentrisch {X, Y, Z} -> lokal {Ost, Nord, Oben} inkl. Additionskonstanten (gerundet). */
			public double[] toLocal(double x, double y, double z) {
				double[] p0 = this.p0();
				double[] d = this.vectorToLocal(x - p0[0], y - p0[1], z - p0[2]);
				return new double[] { this.round(this.addEast + d[0]), this.round(this.addNorth + d[1]), this.round(this.addUp + d[2]) };
			}

			/** lokal {Ost, Nord, Oben} -> geozentrisch {X, Y, Z} (gerundet). */
			public double[] toEcef(double east, double north, double up) {
				double[] p0 = this.p0();
				double[] d = this.vectorToEcef(east - this.addEast, north - this.addNorth, up - this.addUp);
				return new double[] { this.round(p0[0] + d[0]), this.round(p0[1] + d[1]), this.round(p0[2] + d[2]) };
			}

			/** Vektor {dX, dY, dZ} -> {dOst, dNord, dOben} (ungerundet). */
			public double[] vectorToLocal(double dx, double dy, double dz) {
				double[][] r = this.rotation();
				return new double[] { r[0][0] * dx + r[0][1] * dy + r[0][2] * dz, r[1][0] * dx + r[1][1] * dy + r[1][2] * dz, r[2][0] * dx + r[2][1] * dy + r[2][2] * dz };
			}

			/** Vektor {dOst, dNord, dOben} -> {dX, dY, dZ} (transponierte Matrix, ungerundet). */
			public double[] vectorToEcef(double de, double dn, double du) {
				double[][] r = this.rotation();
				return new double[] { r[0][0] * de + r[1][0] * dn + r[2][0] * du, r[0][1] * de + r[1][1] * dn + r[2][1] * du, r[0][2] * de + r[1][2] * dn + r[2][2] * du };
			}

			/** Standardabweichungen {sX, sY, sZ} -> {sOst, sNord, sOben} (Varianzfortpflanzung ohne Korrelationen). */
			public double[] sigmaToLocal(double sx, double sy, double sz) {
				double[][] r = this.rotation();
				double[] s = new double[3];
				for (int i = 0; i < 3; i++)
					s[i] = Math.sqrt(r[i][0] * r[i][0] * sx * sx + r[i][1] * r[i][1] * sy * sy + r[i][2] * r[i][2] * sz * sz);
				return s;
			}

			public double[] sigmaToEcef(double se, double sn, double su) {
				double[][] r = this.rotation();
				double[] s = new double[3];
				for (int i = 0; i < 3; i++)
					s[i] = Math.sqrt(r[0][i] * r[0][i] * se * se + r[1][i] * r[1][i] * sn * sn + r[2][i] * r[2][i] * su * su);
				return s;
			}

			/** lokal {Ost, Nord, Oben} -> {Breite, Laenge} fuer die Karte. */
			public double[] localToLatLon(double east, double north, double up) {
				double[] p0 = this.p0();
				double[] d = this.vectorToEcef(east - this.addEast, north - this.addNorth, (Double.isFinite(up) ? up : this.addUp) - this.addUp);
				double[] llh = Klein_Method_Geo.ecefToLatLonHeight(p0[0] + d[0], p0[1] + d[1], p0[2] + d[2]);
				return new double[] { llh[0], llh[1] };
			}

			/** Liegen lokale Koordinaten plausibel um die Additionskonstanten (bis 50 km)? */
			public boolean looksLocal(double east, double north) {
				return Math.abs(east - this.addEast) < 50000.0 && Math.abs(north - this.addNorth) < 50000.0;
			}
		}

		/** Ellipsoidische Koordinaten (GRS80) -> geozentrisch {X, Y, Z}. */
		public static double[] geodeticToEcef(double latDeg, double lonDeg, double h) {
			double a = Klein_Method_Geo.GRS80_A, f = Klein_Method_Geo.GRS80_F, e2 = f * (2.0 - f);
			double b = Math.toRadians(latDeg), l = Math.toRadians(lonDeg);
			double n = a / Math.sqrt(1.0 - e2 * Math.sin(b) * Math.sin(b));
			return new double[] { (n + h) * Math.cos(b) * Math.cos(l), (n + h) * Math.cos(b) * Math.sin(l), (n * (1.0 - e2) + h) * Math.sin(b) };
		}

		private static double medianOf(double[] v) {
			if (v.length == 0)
				return 0.0;
			double[] c = v.clone();
			java.util.Arrays.sort(c);
			return c.length % 2 == 1 ? c[c.length / 2] : 0.5 * (c[c.length / 2 - 1] + c[c.length / 2]);
		}

		/**
		 * liegt ein Punkt im lokalen System dieser Parameter (bis 500 km um die Additionskonstanten, nicht
		 * geozentrisch)?
		 */
		public static boolean inLocalSystem(Parameters p, double east, double north, double up) {
			return !isEcef(north, east, up) && Math.abs(east - p.addEast) < 5.0e5 && Math.abs(north - p.addNorth) < 5.0e5;
		}

		/** Ist ein Punkt geozentrisch (Betrag 6,30-6,42 Mio. m, |Z| > 100 km)? */
		public static boolean isEcef(double x, double y, double z) {
			double norm = Math.sqrt(x * x + y * y + z * z);
			return norm > 6.30e6 && norm < 6.42e6 && Math.abs(z) > 1.0e5;
		}

		/**
		 * Bezugspunkt P0 = Schwerpunkt der uebergebenen Punkte (Aufrufer uebergibt nur Neupunkte, siehe centerPoints()):
		 * Mittel der geozentrischen Koordinaten, daraus Breite und Laenge; Hoehe = Mittel der ellipsoidischen Hoehen
		 * (liegt damit auf Gelaendehoehe, nicht unter der Kruemmung). Ausreisser in Lage oder Hoehe zaehlen nicht mit
		 * (siehe CENTROID_MAD_FACTOR). Breite/Laenge auf P0_ROUND_DEGREES, Hoehe auf P0_ROUND_HEIGHT gerundet.
		 *
		 * @param defaults    gespeicherter P0 bzw. Grundwerte (Additionskonstanten und Nachkommastellen werden uebernommen)
		 * @param maxDistance &gt; 0: liegt der Schwerpunkt hoechstens so weit von defaults entfernt, bleibt defaults
		 *                    (nur fuer den gespeicherten P0 eines schon umgerechneten Projekts); 0 = immer Schwerpunkt
		 * @return {Parameter, Abstand Schwerpunkt-defaults [m], 1 = defaults behalten / 0 = Schwerpunkt, Anzahl verwendeter
		 *         Punkte, Anzahl verworfener Punkte}
		 */
		public static Object[] chooseReference(List<double[]> ecefPoints, Parameters defaults, double maxDistance) {
			int count = ecefPoints.size();
			double[] xs = new double[count], ys = new double[count], zs = new double[count], hs = new double[count];
			for (int i = 0; i < count; i++) {
				xs[i] = ecefPoints.get(i)[0];
				ys[i] = ecefPoints.get(i)[1];
				zs[i] = ecefPoints.get(i)[2];
				hs[i] = Klein_Method_Geo.ecefToLatLonHeight(xs[i], ys[i], zs[i])[2];
			}
			// robuste Mitte: Median je Komponente; waagerechte Abstaende in deren Horizont, Hoehenabweichungen zum Median
			double mx = medianOf(xs), my = medianOf(ys), mz = medianOf(zs), mh = medianOf(hs);
			double[] mllh = Klein_Method_Geo.ecefToLatLonHeight(mx, my, mz);
			double sinB = Math.sin(Math.toRadians(mllh[0])), cosB = Math.cos(Math.toRadians(mllh[0]));
			double sinL = Math.sin(Math.toRadians(mllh[1])), cosL = Math.cos(Math.toRadians(mllh[1]));
			double[] horizontal = new double[count], heightDeviation = new double[count];
			for (int i = 0; i < count; i++) {
				double dx = xs[i] - mx, dy = ys[i] - my, dz = zs[i] - mz;
				double east = -sinL * dx + cosL * dy, north = -sinB * cosL * dx - sinB * sinL * dy + cosB * dz;
				horizontal[i] = Math.hypot(east, north);
				heightDeviation[i] = Math.abs(hs[i] - mh);
			}
			double horizontalLimit = Math.max(CENTROID_MIN_HORIZONTAL_LIMIT, CENTROID_MAD_FACTOR * medianOf(horizontal));
			double heightLimit = Math.max(CENTROID_MIN_HEIGHT_LIMIT, CENTROID_MAD_FACTOR * medianOf(heightDeviation));
			double sx = 0.0, sy = 0.0, sz = 0.0, sh = 0.0;
			int used = 0;
			for (int i = 0; i < count; i++) {
				if (horizontal[i] > horizontalLimit || heightDeviation[i] > heightLimit)
					continue;
				sx += xs[i];
				sy += ys[i];
				sz += zs[i];
				sh += hs[i];
				used++;
			}
			double[] p0 = defaults.p0();
			if (used == 0)
				return new Object[] { defaults.copy(), Double.NaN, 1, 0, count };
			double cx = sx / used, cy = sy / used, cz = sz / used;
			double distance = Math.sqrt((cx - p0[0]) * (cx - p0[0]) + (cy - p0[1]) * (cy - p0[1]) + (cz - p0[2]) * (cz - p0[2]));
			if (maxDistance > 0.0 && distance <= maxDistance)
				return new Object[] { defaults.copy(), distance, 1, used, count - used };
			double[] llh = Klein_Method_Geo.ecefToLatLonHeight(cx, cy, cz);
			Parameters p = defaults.copy();
			p.latitude = roundTo(llh[0], P0_ROUND_DEGREES);
			p.longitude = roundTo(llh[1], P0_ROUND_DEGREES);
			p.height = roundTo(sh / used, P0_ROUND_HEIGHT);
			return new Object[] { p, distance, 0, used, count - used };
		}

		private static double roundTo(double value, double step) {
			double perUnit = Math.rint(1.0 / step); // 100000 bzw. 100 - Division durch eine ganze Zahl ergibt den naechsten double
			return Math.round(value * perUnit) / perUnit;
		}

		/**
		 * Punkte fuer den Schwerpunkt P0 (geozentrisch {X, Y, Z}): ausschliesslich Neupunkte - Festpunkte haben keinen
		 * Einfluss auf P0. Neupunkte ohne geozentrische Koordinaten werden fuer diesen Zweck aus 3D-Basislinien zu
		 * geozentrischen Punkten geschaetzt (Median). Ohne Neupunkt: leere Liste.
		 *
		 * @param pointRows    {id, Dimension, y0 = Y, x0 = X, z0 = Z, Punktart} (Klein_Method_Database_Handler.readLocalConversionPoints())
		 * @param baselineRows {id, Typ, dY, dX, dZ, Start-id, Ziel-id, aktiv} (readLocalConversionBaselines())
		 */
		public static List<double[]> centerPoints(List<double[]> pointRows, List<double[]> baselineRows) {
			int newType = org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId();
			int gnss3d = org.applied_geodesy.adjustment.network.ObservationType.GNSS3D.getId();
			Map<Integer, double[]> ecef = new LinkedHashMap<Integer, double[]>();
			java.util.Set<Integer> newIds = new java.util.LinkedHashSet<Integer>();
			for (double[] r : pointRows) {
				if (r.length > 5 && (int) r[5] == newType)
					newIds.add((int) r[0]);
				if ((int) r[1] == 3 && isEcef(r[3], r[2], r[4]))
					ecef.put((int) r[0], new double[] { r[3], r[2], r[4] });
			}
			Map<Integer, List<double[]>> estimates = new LinkedHashMap<Integer, List<double[]>>();
			if (baselineRows != null)
				for (double[] b : baselineRows) {
					if (b.length < 8 || (int) b[1] != gnss3d || b[7] < 0.5)
						continue;
					int s = (int) b[5], e = (int) b[6];
					if (ecef.containsKey(s) && newIds.contains(e) && !ecef.containsKey(e)) {
						double[] p = ecef.get(s);
						estimates.computeIfAbsent(e, k -> new ArrayList<double[]>()).add(new double[] { p[0] + b[3], p[1] + b[2], p[2] + b[4] });
					} else if (ecef.containsKey(e) && newIds.contains(s) && !ecef.containsKey(s)) {
						double[] p = ecef.get(e);
						estimates.computeIfAbsent(s, k -> new ArrayList<double[]>()).add(new double[] { p[0] - b[3], p[1] - b[2], p[2] - b[4] });
					}
				}
			List<double[]> news = new ArrayList<double[]>();
			for (int id : newIds) {
				if (ecef.containsKey(id))
					news.add(ecef.get(id));
				else if (estimates.containsKey(id)) {
					List<double[]> c = estimates.get(id);
					double[] xs = new double[c.size()], ys = new double[c.size()], zs = new double[c.size()];
					for (int i = 0; i < c.size(); i++) {
						xs[i] = c.get(i)[0];
						ys[i] = c.get(i)[1];
						zs[i] = c.get(i)[2];
					}
					news.add(new double[] { medianOf(xs), medianOf(ys), medianOf(zs) });
				}
			}
			return news;
		}

		/** Einordnung der 3D-Basislinien: id -> true = geozentrisch (dX/dY/dZ), false = lokal (Ost/Nord/Oben). */
		public static final class Baseline_Classes {
			public final Map<Integer, Boolean> geocentric = new LinkedHashMap<Integer, Boolean>();
			/** ueber die Endpunkte entschieden / von Gruppe oder Nachbarn uebernommen / nur angenommen. */
			public int decided = 0, inherited = 0, assumed = 0;

			public int countGeocentric() {
				int n = 0;
				for (boolean g : this.geocentric.values())
					if (g)
						n++;
				return n;
			}
		}

		/**
		 * Ordnet jede 3D-Basislinie ein - unabhaengig von der Importreihenfolge: der Vektor wird mit der
		 * Koordinatendifferenz seiner Endpunkte verglichen, einmal geozentrisch und einmal lokal um p gedreht (die
		 * beiden Lesarten unterscheiden sich um Kilometer bis zur Vektorlaenge, Naeherungsfehler von Metern stoeren nicht).
		 * Endpunkte zaehlen, wenn sie geozentrisch sind oder im lokalen System um p liegen. Ohne brauchbare Endpunkte:
		 * Mehrheit der entschiedenen Basislinien derselben Beobachtungsgruppe, sonst die naechste entschiedene in der
		 * Importreihenfolge (Id), sonst defaultGeocentric.
		 *
		 * @param pointRows    {id, Dimension, y0, x0, z0, ...} (readLocalConversionPoints())
		 * @param baselineRows {id, Typ, y0, x0, z0, Start-id, Ziel-id, aktiv, Gruppen-id} (readLocalConversionBaselines())
		 */
		public static Baseline_Classes classifyBaselines(List<double[]> pointRows, List<double[]> baselineRows, Parameters p, boolean defaultGeocentric) {
			int gnss3d = org.applied_geodesy.adjustment.network.ObservationType.GNSS3D.getId();
			Map<Integer, double[]> ecef = new java.util.HashMap<Integer, double[]>();
			for (double[] r : pointRows) {
				if ((int) r[1] != 3)
					continue;
				if (isEcef(r[3], r[2], r[4]))
					ecef.put((int) r[0], new double[] { r[3], r[2], r[4] });
				else if (p != null && !(r[2] == 0.0 && r[3] == 0.0) && inLocalSystem(p, r[2], r[3], r[4]))
					ecef.put((int) r[0], p.toEcef(r[2], r[3], r[4]));
			}
			Baseline_Classes result = new Baseline_Classes();
			java.util.TreeMap<Integer, Boolean> decided = new java.util.TreeMap<Integer, Boolean>();
			Map<Integer, int[]> groupVotes = new java.util.HashMap<Integer, int[]>(); // {geozentrisch, lokal}
			List<double[]> open = new ArrayList<double[]>();
			for (double[] b : baselineRows) {
				if ((int) b[1] != gnss3d)
					continue;
				double[] s = ecef.get((int) b[5]), e = ecef.get((int) b[6]);
				Boolean geo = null;
				if (s != null && e != null && p != null) {
					double dX = e[0] - s[0], dY = e[1] - s[1], dZ = e[2] - s[2];
					double length = Math.sqrt(dX * dX + dY * dY + dZ * dZ);
					double[] l = p.vectorToLocal(dX, dY, dZ); // {Ost, Nord, Oben}
					double geoMisfit = Math.sqrt((b[3] - dX) * (b[3] - dX) + (b[2] - dY) * (b[2] - dY) + (b[4] - dZ) * (b[4] - dZ));
					double localMisfit = Math.sqrt((b[2] - l[0]) * (b[2] - l[0]) + (b[3] - l[1]) * (b[3] - l[1]) + (b[4] - l[2]) * (b[4] - l[2]));
					// eindeutig, wenn eine Lesart mindestens doppelt so gut passt und sich beide um mehr als 2 m unterscheiden
					// (nicht relativ zur Laenge: Vektoren fast parallel zur Drehachse unterscheiden sich nur in den kleinen Komponenten)
					double better = Math.min(geoMisfit, localMisfit), worse = Math.max(geoMisfit, localMisfit);
					if (length >= 1.0 && worse - better > 2.0 && better < 0.5 * worse)
						geo = geoMisfit < localMisfit;
				}
				if (geo != null) {
					decided.put((int) b[0], geo);
					result.geocentric.put((int) b[0], geo);
					result.decided++;
					if (b.length > 8)
						groupVotes.computeIfAbsent((int) b[8], k -> new int[2])[geo ? 0 : 1]++;
				}
				else {
					open.add(b);
					result.geocentric.put((int) b[0], defaultGeocentric); // Platzhalter, unten ersetzt
				}
			}
			for (double[] b : open) {
				int id = (int) b[0];
				int[] votes = b.length > 8 ? groupVotes.get((int) b[8]) : null;
				if (votes != null && votes[0] != votes[1]) {
					result.geocentric.put(id, votes[0] > votes[1]);
					result.inherited++;
					continue;
				}
				Map.Entry<Integer, Boolean> lower = decided.floorEntry(id), higher = decided.ceilingEntry(id);
				Map.Entry<Integer, Boolean> near = lower == null ? higher
						: higher == null ? lower : (id - lower.getKey() <= higher.getKey() - id ? lower : higher);
				if (near != null) {
					result.geocentric.put(id, near.getValue());
					result.inherited++;
				}
				else
					result.assumed++;
			}
			return result;
		}

		// ---------------- Dateien (Modus 1) ----------------

		/** Ergebnis einer Dateiumrechnung. */
		public static class File_Result {
			public java.io.File output;
			public int converted = 0, unchanged = 0;
		}

		private static boolean isNumber(String s) {
			try {
				Double.parseDouble(s);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		private static String fmt(double v, int decimals) {
			return String.format(Locale.ROOT, "%." + decimals + "f", v);
		}

		/**
		 * Zeichensatz einer Textdatei - UTF-8, sonst Windows-1252 (bzw. ISO-8859-1), z. B. Umlaute aus Excel/Editor.
		 */
		/** Kopfzeile umgerechneter Dateien (der JAG3D-Import ueberspringt Zeilen mit #). */
		public static final String FILE_MARK = "# Klein_Method:";

		/** Kopfzeile fuer eine umgerechnete Datei: Richtung, Zeitpunkt, P0 (Parameters.encode). */
		public static String fileMark(boolean toLocal, Parameters p) {
			String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
			return FILE_MARK + (toLocal ? " lokal (ENh)" : " WGS84 (geozentrisch)") + " umgerechnet am " + stamp + ", P0 " + p.encode();
		}

		/** Kopfzeile einer Datei lesen: {"lokal" bzw. "wgs84", P0 (encode), Zeile} oder null ohne Kopfzeile. */
		public static String[] readFileMark(java.io.File input) throws java.io.IOException {
			for (String line : readLines(input)) {
				String t = line.trim();
				if (!t.startsWith(FILE_MARK))
					continue;
				int p0 = t.lastIndexOf(", P0 ");
				return new String[] { t.contains(" lokal (ENh)") ? "lokal" : "wgs84", p0 > 0 ? t.substring(p0 + 5).trim() : "", t };
			}
			return null;
		}

		/** Ausgabedatei mit Kopfzeile versehen (alte Kopfzeilen entfernen, Zeichensatz beibehalten). */
		public static void stampFile(java.io.File output, String mark) throws java.io.IOException {
			byte[] bytes = java.nio.file.Files.readAllBytes(output.toPath());
			java.nio.charset.Charset charset = detectCharset(bytes);
			List<String> lines = new ArrayList<String>();
			lines.add(mark);
			for (String line : readLines(output))
				if (!line.trim().startsWith(FILE_MARK))
					lines.add(line);
			java.nio.file.Files.write(output.toPath(), lines, charset);
		}

		/** gleiche P0-Parameter (Lage auf 1e-9 Grad, Hoehe und Additionskonstanten auf 1 mm)? */
		public static boolean sameReference(Parameters a, Parameters b) {
			return a != null && b != null && Math.abs(a.latitude - b.latitude) < 1e-9 && Math.abs(a.longitude - b.longitude) < 1e-9
					&& Math.abs(a.height - b.height) < 1e-3 && Math.abs(a.addEast - b.addEast) < 1e-3 && Math.abs(a.addNorth - b.addNorth) < 1e-3
					&& Math.abs(a.addUp - b.addUp) < 1e-3;
		}

		public static java.nio.charset.Charset detectCharset(byte[] bytes) {
			try {
				java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
						.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
				return java.nio.charset.StandardCharsets.UTF_8;
			} catch (java.nio.charset.CharacterCodingException e) {
				return java.nio.charset.Charset.isSupported("windows-1252") ? java.nio.charset.Charset.forName("windows-1252")
						: java.nio.charset.StandardCharsets.ISO_8859_1;
			}
		}

		/** beliebige Textdatei zeilenweise lesen (Zeichensatz siehe detectCharset, BOM entfernt, CR/LF/CRLF). */
		public static List<String> readLines(java.io.File input) throws java.io.IOException {
			byte[] bytes = java.nio.file.Files.readAllBytes(input.toPath());
			String text = new String(bytes, detectCharset(bytes));
			if (text.startsWith("\uFEFF"))
				text = text.substring(1);
			List<String> lines = new ArrayList<String>(java.util.Arrays.asList(text.split("\\r?\\n|\\r", -1)));
			if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())
				lines.remove(lines.size() - 1);
			return lines;
		}

		/**
		 * Inhalt einer Punktdatei einordnen (Name Wert Wert Wert ...).
		 *
		 * @return {geozentrische Zeilen, sonstige Zahlenzeilen}
		 */
		public static int[] classifyPointFile(java.io.File input) throws java.io.IOException {
			int ecef = 0, other = 0;
			for (String line : readLines(input)) {
				String trimmed = line.trim();
				String[] t = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
				if (trimmed.startsWith("#") || t.length < 4 || !isNumber(t[1]) || !isNumber(t[2]) || !isNumber(t[3]))
					continue;
				if (isEcef(Double.parseDouble(t[2]), Double.parseDouble(t[1]), Double.parseDouble(t[3])))
					ecef++;
				else
					other++;
			}
			return new int[] { ecef, other };
		}

		/**
		 * Punkt- oder Basisliniendatei umrechnen. Zeilen: Name Y X Z [sY sX sZ] bzw. Start Ziel dY dX dZ [sY sX sZ]
		 * (geozentrisch) bzw. Name Ost Nord Hoehe ... (lokal). Leerzeilen, Kommentare (#) und Zeilen ohne
		 * Zahlen bleiben unveraendert; weitere Spalten (z. B. Standardabweichungen) werden unveraendert uebernommen.
		 */
		/**
		 * Epochen je Basislinie zusammenfassen.
		 *
		 * EPOCHS_SINGLE = jede Zeile einzeln, EPOCHS_MEAN = Mittelwert und Streuung wie im VBA,
		 */
		public static final int EPOCHS_SINGLE = 0, EPOCHS_MEAN = 1, EPOCHS_ROBUST = 2;
		/** Standard wie VBA V0.06: Faktor auf die Streuung 1, kleinster Wert 5 mm. */
		public static final double DEFAULT_SIGMA_FACTOR = 1.0, DEFAULT_SIGMA_MIN_MM = 5.0;
		/**
		 * Ausreissergrenze des robusten Mittels: k * 1,4826 * MAD, mindestens k * kleinster Wert. k = 3,5 entspricht dem
		 * modifizierten z-Wert nach Iglewicz/Hoaglin (|0,6745 * (x - Median) / MAD| > 3,5).
		 */
		public static final double ROBUST_K = 3.5;

		/** Ergebnis der Mittelbildung. */
		public static final class Epoch_Result extends File_Result {
			public int baselines = 0, epochs = 0, rejected = 0, single = 0;
			/** je Basislinie mit verworfenen Epochen: "Start -> Ziel: n von m verworfen (bis x mm)". */
			public final List<String> rejectedLines = new ArrayList<String>();
			/** Hin/Rueck-Pruefung A->B gegen -(B->A): Zeilen mit Differenz > 3 sigma. */
			public final List<String> roundTripWarnings = new ArrayList<String>();
			public int roundTripPairs = 0;
		}

		/**
		 * Basislinien-Datei umrechnen und die Epochen je Basislinie (Start|Ziel, Richtung zaehlt getrennt) zu einem
		 * Mittelwert mit Standardabweichung zusammenfassen. Ausgabe je Basislinie: Start Ziel d1 d2 d3 s1 s2 s3
		 * (Meter, JAG3D-Format fuer GNSS-Basislinien mit a-priori-Unsicherheiten). sigma = Faktor * Streuung der
		 * Epochen (n-1, bewusst NICHT durch Wurzel n geteilt - benachbarte Epochen sind korreliert), mindestens
		 * sigmaMin; eine einzelne Epoche bekommt sigmaMin. Robust: Median je Komponente, Epochen mit |Abweichung| >
		 * 3,5 * 1,4826 * MAD (mindestens 3,5 * sigmaMin) in einer Komponente werden verworfen (erst ab 3 Epochen).
		 * Gerechnet wird ungerundet, gerundet erst bei der Ausgabe. Kommt eine Basislinie in beiden Richtungen vor,
		 * wird A->B mit -(B->A) verglichen (Grenze 3 sigma).
		 */
		public static Epoch_Result convertBaselinesAveraged(java.io.File input, java.io.File output, boolean toLocal, Parameters p, int mode,
				double sigmaFactor, double sigmaMinMetre) throws java.io.IOException {
			Epoch_Result result = new Epoch_Result();
			result.output = output;
			Map<String, List<double[]>> groups = new LinkedHashMap<String, List<double[]>>();
			Map<String, String[]> names = new LinkedHashMap<String, String[]>();
			for (String line : readLines(input)) {
				String trimmed = line.trim();
				String[] t = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
				if (trimmed.startsWith("#") || t.length < 5 || !isNumber(t[2]) || !isNumber(t[3]) || !isNumber(t[4])) {
					if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
						result.unchanged++;
					continue;
				}
				double a = Double.parseDouble(t[2]), b = Double.parseDouble(t[3]), c = Double.parseDouble(t[4]);
				double[] r;
				if (toLocal)
					r = p.vectorToLocal(b, a, c); // geozentrisch a = dY, b = dX, c = dZ -> {dOst, dNord, dOben}
				else {
					double[] d = p.vectorToEcef(a, b, c);
					r = new double[] { d[1], d[0], d[2] }; // JAG3D-Spaltenfolge dY dX dZ
				}
				String key = t[0] + "|" + t[1];
				groups.computeIfAbsent(key, k -> new ArrayList<double[]>()).add(r);
				names.putIfAbsent(key, new String[] { t[0], t[1] });
				result.epochs++;
			}
			List<String> out = new ArrayList<String>();
			Map<String, double[]> means = new LinkedHashMap<String, double[]>();
			for (Map.Entry<String, List<double[]>> e : groups.entrySet()) {
				List<double[]> all = e.getValue();
				List<double[]> kept = all;
				if (mode == EPOCHS_ROBUST && all.size() >= 3) {
					double[] median = new double[3], limit = new double[3];
					for (int j = 0; j < 3; j++) {
						double[] v = new double[all.size()];
						for (int i = 0; i < v.length; i++)
							v[i] = all.get(i)[j];
						median[j] = medianOf(v);
						for (int i = 0; i < v.length; i++)
							v[i] = Math.abs(v[i] - median[j]);
						limit[j] = Math.max(ROBUST_K * 1.4826 * medianOf(v), ROBUST_K * sigmaMinMetre);
					}
					kept = new ArrayList<double[]>();
					double worst = 0.0;
					for (double[] r : all) {
						boolean outlier = false;
						for (int j = 0; j < 3; j++)
							if (Math.abs(r[j] - median[j]) > limit[j]) {
								outlier = true;
								worst = Math.max(worst, Math.abs(r[j] - median[j]));
							}
						if (!outlier)
							kept.add(r);
					}
					int dropped = all.size() - kept.size();
					if (dropped > 0) {
						result.rejected += dropped;
						String[] n = names.get(e.getKey());
						result.rejectedLines.add(String.format(Locale.GERMANY, "%s -> %s: %d von %d verworfen (bis %.0f mm)", n[0], n[1], dropped, all.size(),
								worst * 1000.0));
					}
				}
				double[] m = new double[6];
				for (int j = 0; j < 3; j++) {
					double sum = 0.0;
					for (double[] r : kept)
						sum += r[j];
					m[j] = sum / kept.size();
					double sigma = 0.0;
					if (kept.size() > 1) {
						double q = 0.0;
						for (double[] r : kept)
							q += (r[j] - m[j]) * (r[j] - m[j]);
						sigma = sigmaFactor * Math.sqrt(q / (kept.size() - 1));
					}
					m[3 + j] = Math.max(sigma, sigmaMinMetre);
				}
				if (all.size() == 1)
					result.single++;
				means.put(e.getKey(), m);
				String[] n = names.get(e.getKey());
				out.add(String.format(Locale.ROOT, "%-12s %-12s %16s %16s %16s %10s %10s %10s", n[0], n[1], fmt(m[0], p.decimals), fmt(m[1], p.decimals),
						fmt(m[2], p.decimals), fmt(m[3], 4), fmt(m[4], 4), fmt(m[5], 4)));
				result.baselines++;
			}
			// Hin/Rueck-Pruefung: A->B gegen -(B->A)
			String[] axis = toLocal ? new String[] { "dE", "dN", "dh" } : new String[] { "dY", "dX", "dZ" };
			for (Map.Entry<String, double[]> e : means.entrySet()) {
				String[] n = names.get(e.getKey());
				String reverse = n[1] + "|" + n[0];
				double[] back = means.get(reverse);
				if (back == null || n[0].compareTo(n[1]) > 0)
					continue; // jedes Paar nur einmal
				result.roundTripPairs++;
				double[] m = e.getValue();
				StringBuilder bad = new StringBuilder();
				for (int j = 0; j < 3; j++) {
					double diff = m[j] + back[j], limit = 3.0 * Math.hypot(m[3 + j], back[3 + j]);
					if (Math.abs(diff) > limit)
						bad.append(String.format(Locale.GERMANY, "%s %.0f mm (Grenze %.0f mm) ", axis[j], diff * 1000.0, limit * 1000.0));
				}
				if (bad.length() > 0)
					result.roundTripWarnings.add(n[0] + " <-> " + n[1] + ": Hin/Rueck-Differenz " + bad.toString().trim());
			}
			result.converted = result.baselines;
			java.nio.file.Files.write(output.toPath(), out, detectCharset(java.nio.file.Files.readAllBytes(input.toPath())));
			return result;
		}

		public static File_Result convertFile(java.io.File input, java.io.File output, boolean baselines, boolean toLocal, Parameters p) throws java.io.IOException {
			File_Result result = new File_Result();
			result.output = output;
			List<String> lines = readLines(input);
			List<String> out = new ArrayList<String>();
			int first = baselines ? 2 : 1;
			for (String line : lines) {
				String trimmed = line.trim();
				String[] t = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
				if (trimmed.startsWith("#") || t.length < first + 3 || !isNumber(t[first]) || !isNumber(t[first + 1]) || !isNumber(t[first + 2])) {
					out.add(line);
					if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
						result.unchanged++;
					continue;
				}
				double a = Double.parseDouble(t[first]), b = Double.parseDouble(t[first + 1]), c = Double.parseDouble(t[first + 2]);
				double[] r;
				if (baselines) {
					// geozentrisch: a = dY, b = dX, c = dZ ; lokal: a = dOst, b = dNord, c = dOben
					if (toLocal) {
						double[] d = p.vectorToLocal(b, a, c);
						r = new double[] { p.round(d[0]), p.round(d[1]), p.round(d[2]) };
					}
					else {
						double[] d = p.vectorToEcef(a, b, c);
						r = new double[] { p.round(d[1]), p.round(d[0]), p.round(d[2]) };
					}
				}
				else if (toLocal)
					r = p.toLocal(b, a, c);
				else {
					double[] e = p.toEcef(a, b, c);
					r = new double[] { e[1], e[0], e[2] };
				}
				StringBuilder s = new StringBuilder();
				for (int i = 0; i < first; i++)
					s.append(String.format(Locale.ROOT, "%-12s ", t[i]));
				s.append(String.format(Locale.ROOT, "%16s %16s %16s", fmt(r[0], p.decimals), fmt(r[1], p.decimals), fmt(r[2], p.decimals)));
				// weitere Spalten (z. B. Standardabweichungen, Code) unveraendert - ohne Kovarianzen waere ein Drehen nur
				// naeherungsweise und nicht umkehrbar; die Gruppenunsicherheiten kalibriert Klein_Method ohnehin
				for (int i = first + 3; i < t.length; i++)
					s.append(' ').append(t[i]);
				out.add(s.toString());
				result.converted++;
			}
			// Ausgabe im Zeichensatz der Eingabedatei
			java.nio.file.Files.write(output.toPath(), out, detectCharset(java.nio.file.Files.readAllBytes(input.toPath())));
			return result;
		}
	}

	/**
	 * zuverlaessige Naeherungskoordinaten der Neupunkte (nur freie AGL).
	 *
	 * 1) Startwerte schrittweise: GNSS-Vektor, polar von orientierten Standpunkten, Vorwaertsschnitt, lokale
	 * Standpunktsysteme (freie Stationierung), Rueckwaertsschnitt (nur Richtungen), Bogenschnitt
	 * (Spiegelpunkt-Pruefung); Hoehen aus Nivellement, trig. Hoehen, GNSS.
	 *
	 * 2) robuste Vorausgleichung (Gauss-Newton, Orientierung je Richtungssatz, IGG-III-Gewichte), Lage und Hoehe
	 * getrennt, zweimal im Wechsel.
	 *
	 * 3) Lagerung per robuster Helmert-Transformation (4 Parameter) auf die Altpunkte, Hoehe per Median.
	 *
	 * 4) Vergleich mit den vorhandenen Naeherungskoordinaten und Empfehlung ueber die Widersprueche der Beobachtungen
	 * zu beiden Koordinatensaetzen. Reine Rechnung, keine Datenbank, keine Texte mit tr().
	 */
	public static final class Klein_Method_Approximation {
		public static final int LEVELING = 1, DIRECTION = 2, HORIZONTAL_DISTANCE = 3, SLOPE_DISTANCE = 4, ZENITH_ANGLE = 5,
				GNSS1D = 6, GNSS2D = 7, GNSS3D = 8;
		private static final double TWO_PI = 2.0 * Math.PI;
		private static final double MGON = Math.PI / 200000.0;

		private Klein_Method_Approximation() {
		}

		public static final class Point {
			public final String name;
			public final boolean isNew;
			public final int dimension;
			public final double y0, x0, z0;

			public Point(String name, boolean isNew, int dimension, double y0, double x0, double z0) {
				this.name = name;
				this.isNew = isNew;
				this.dimension = dimension;
				this.y0 = y0;
				this.x0 = x0;
				this.z0 = z0;
			}

			boolean hasPosition() {
				return this.dimension != 1;
			}

			boolean hasHeight() {
				return this.dimension != 2;
			}

			public boolean positionGiven() {
				if (!this.hasPosition() || !Double.isFinite(this.y0) || !Double.isFinite(this.x0))
					return false;
				// bei Neupunkten gilt 0,000 als "kein plausibler Wert"
				return this.isNew ? this.y0 != 0.0 && this.x0 != 0.0 : !(this.y0 == 0.0 && this.x0 == 0.0);
			}

			boolean heightGiven() {
				return this.hasHeight() && Double.isFinite(this.z0) && !(this.isNew && this.z0 == 0.0);
			}

			/** Neupunkt mit 0,000 in y0 oder x0. */
			boolean positionZero() {
				return this.isNew && this.hasPosition() && (this.y0 == 0.0 || this.x0 == 0.0);
			}

			/** Neupunkt mit 0,000 in z0. */
			boolean heightZero() {
				return this.isNew && this.hasHeight() && this.z0 == 0.0;
			}
		}

		public static final class Observation {
			public final int type, group;
			public final String from, to;
			public final double ih, th, value, gy, gx, gz;
			/** Datenbank-Id (GNSS-Basislinien, fuer das Deaktivieren auffaelliger Sitzungen), sonst -1. */
			public int id = -1;

			public Observation(int type, int group, String from, String to, double ih, double th, double value, double gy, double gx, double gz) {
				this.type = type;
				this.group = group;
				this.from = from;
				this.to = to;
				this.ih = ih;
				this.th = th;
				this.value = value;
				this.gy = gy;
				this.gx = gx;
				this.gz = gz;
			}
		}

		public static final class Settings {
			public double tolPosition = 0.05, tolHeight = 0.05, grossLimit = 0.10;
			public boolean curvature = false;
			public double earthRadius = 6371000.0;
			/**
			 * Projekt im lokalen System um P0 ("lokal ellipsoidisch"): Oben ist dort nicht die Hoehe - ein Punkt im waagerechten
			 * Abstand r von P0 liegt um r^2 / 2R tiefer als seine Hoehe. Hoehenunterschiede aus Nivellement und Zenitwinkeln
			 * (entlang des Lots) werden dafuer korrigiert, die Erdkruemmung je Zielweite immer angebracht. frameEast/frameNorth
			 * = Lage von P0 im lokalen System (Additionskonstanten).
			 */
			public boolean localFrame = false;
			public double frameEast = 0.0, frameNorth = 0.0;
			/**
			 * Hoehensysteme gemischt (NHN-Hoehenfestpunkte neben ellipsoidischen GNSS-Festpunkten): 1D-Festpunkte nicht zur
			 * Hoehenlagerung verwenden - die Hoehen kommen dann nur aus einem System (GNSS), das Nivellement haengt relativ daran.
			 */
			public boolean ignoreHeightAnchors1D = false;
			public int maxUnknowns = 20000;
			/**
			 * Rangfolge Nivellement > Tachymeter > GNSS - Startwerte der Hoehe erst aus Nivellement, dann
			 * trigonometrisch, GNSS nur fuer den Rest; Startwerte der Lage erst terrestrisch, dann GNSS; in der Hoehe
			 * zaehlen wiederholte GNSS-Sessions zwischen denselben Punkten zusammen wie eine Basislinie; GNSS-Hoehe
			 * 3-fache Lageunsicherheit.
			 */
			public boolean priority = true;
		}

		public static final class PointResult {
			public final String name;
			public final int dimension;
			public final double y0, x0, z0;
			public double y = Double.NaN, x = Double.NaN, z = Double.NaN;
			public boolean positionDetermined, heightDetermined, hadPosition, ambiguous, differs, weak;
			public boolean differsPosition, differsHeight, betterPosition, betterHeight;
			/** 0,000 eingetragen (kein plausibler Wert). */
			public boolean zeroPosition, zeroHeight, hadHeight;
			/**
			 * vorhandene Naeherung liegt in einem anderen System (Lage > 100 m bzw. Hoehe > 20 m daneben, z. B.
			 * UTM/NHN statt lokal).
			 */
			public boolean foreignPosition, foreignHeight;
			/** Lage nicht eindeutig (widerspruechliche Beobachtungsgruppen) - wird nicht uebernommen. */
			public boolean unreliable;
			public double dPosition = Double.NaN, dHeight = Double.NaN, misfitOldMm = Double.NaN, misfitNewMm = Double.NaN,
					heightMisfitOldMm = Double.NaN, heightMisfitNewMm = Double.NaN;

			PointResult(Point p) {
				this.name = p.name;
				this.dimension = p.dimension;
				this.y0 = p.y0;
				this.x0 = p.x0;
				this.z0 = p.z0;
				this.hadPosition = p.positionGiven();
				this.hadHeight = p.heightGiven();
				this.zeroPosition = p.positionZero();
				this.zeroHeight = p.heightZero();
			}

			/** Nur abweichende Punkte, deren neue Koordinaten besser zu den Messungen passen (oder ohne Naeherung). */
			public boolean writePosition() {
				return this.differsPosition && this.betterPosition && this.positionDetermined && this.dimension != 1;
			}

			public boolean writeHeight() {
				return this.differsHeight && this.betterHeight && this.heightDetermined && this.dimension != 2;
			}

			public boolean adopted() {
				return this.writePosition() || this.writeHeight();
			}
		}

		public static final class Result {
			public final List<PointResult> newPoints = new ArrayList<PointResult>();
			public final List<PointResult> differing = new ArrayList<PointResult>();
			public final List<String> notDetermined = new ArrayList<String>();
			public final List<String> ambiguous = new ArrayList<String>();
			/** Neupunkte ohne Ueberbestimmung (z. B. nur einmal polar): neue Lage nicht kontrollierbar. */
			public final List<String> weak = new ArrayList<String>();
			/** Neupunkte mit 0,000 - bestimmbar (angeboten) bzw. nicht bestimmbar (bleiben). */
			public final List<String> zeroDetermined = new ArrayList<String>(), zeroNotDetermined = new ArrayList<String>();
			public final List<String> suspicious = new ArrayList<String>();
			public final List<String> frameExcluded = new ArrayList<String>();
			public final List<String> multiStartMoved = new ArrayList<String>();
			/** Neupunkte ohne eindeutige Lage (zwei Gruppen etwa gleich stark) - werden nicht uebernommen. */
			public final List<String> unreliable = new ArrayList<String>();
			/** mehrfach aufgestellte Standpunkte: Pruefung ueber die gemeinsamen Ziele. */
			public final List<Station_Check> stationChecks = new ArrayList<Station_Check>();
			/** Neupunkte, deren vorhandene Naeherung in einem anderen System liegt (immer angeboten). */
			public final List<String> foreign = new ArrayList<String>();
			/**
			 * 1D-Punkte im lokalen System um P0, deren eingetragene Lage mehr als 20 km von P0 entfernt ist (z. B. 0/0 oder UTM) -
			 * JAG3D bezieht ihre Hoehe in "lokal ellipsoidisch" auf diese Lage.
			 */
			public final List<String> oneDimensionalFar = new ArrayList<String>();
			/** in der Vorausgleichung verworfene GNSS-Basislinien je Punkt (Datenbank-Ids). */
			public final Map<String, List<Integer>> rejectedGnssIds = new java.util.HashMap<String, List<Integer>>();
			/** 1D-Festpunkte wegen gemischter Hoehensysteme nicht zur Hoehenlagerung verwendet. */
			public final List<String> heightAnchorsIgnored = new ArrayList<String>();
			/** Punkte mit widerspruechlichen Beobachtungen (viele verworfen) - konkrete Pruefhinweise. */
			public final List<Conflict> conflicts = new ArrayList<Conflict>();
			/**
			 * Richtungsgruppen mit mehreren Standpunkten {Gruppen-Id, Anzahl Standpunkte} - JAG3D hat nur eine
			 * Orientierung je Gruppe.
			 */
			public final List<int[]> mixedDirectionGroups = new ArrayList<int[]>();
			/** GNSS-Sitzungen mit Drift oder Versatz, je Punkt (bzw. Punktpaar), groesste zuerst. */
			public final List<Gnss_Session_Finding> gnssSessionFindings = new ArrayList<Gnss_Session_Finding>();
			public String frameSource = "lokal";
			public int betterCount = 0;
			public int framePoints = 0, frameHeightPoints = 0, iterations = 0, evaluatedOld = 0, evaluatedNew = 0, grossOld = 0, grossNew = 0;
			public double frameResidualMm = Double.NaN, scalePpm = Double.NaN, medianOldMm = Double.NaN, medianNewMm = Double.NaN;
			public boolean converged = true, adjusted = false, recommendNew = false, missingApproximation = false;
			public String failure = null;
		}

		/** Punkt mit vielen verworfenen Beobachtungen; rejectedByType: Beobachtungstyp -> Anzahl verworfen. */
		public static final class Conflict {
			public final String name;
			public final boolean isNew;
			public int rejected = 0, total = 0;
			public final Map<Integer, Integer> rejectedByType = new java.util.TreeMap<Integer, Integer>(), totalByType = new java.util.TreeMap<Integer, Integer>();

			Conflict(String name, boolean isNew) {
				this.name = name;
				this.isNew = isNew;
			}
		}

		/**
		 * Pruefung mehrfach aufgestellter Standpunkte (wie eine Standpunktvereinigung, aber nur als Kontrolle):
		 * Orientierungsunterschied zwischen zwei Aufstellungen aus den gemeinsamen Zielen und der groesste Rest.
		 */
		public static final class Station_Check {
			public final String station;
			public int commonTargets = 0;
			public double orientationDifference = Double.NaN, worstResidual = Double.NaN, worstCross = Double.NaN;
			public String worstTarget = null;

			Station_Check(String station) {
				this.station = station;
			}
		}

		/** Querabweichung im Ziel, ab der eine Aufstellungspruefung als auffaellig gilt (m). */
		public static final double STATION_CHECK_CROSS = 0.005;

		static void checkStations(List<Observation> observations, Result result) {
			// je Gruppe und Standpunkt: Ziel -> Lesungen; Strecken je Paar
			Map<String, Map<String, List<Double>>> sets = new java.util.LinkedHashMap<String, Map<String, List<Double>>>();
			Map<String, List<String>> setsByStation = new java.util.LinkedHashMap<String, List<String>>();
			Map<String, List<Double>> dist = new java.util.HashMap<String, List<Double>>();
			for (Observation o : observations) {
				if (o.type == DIRECTION) {
					String key = o.group + "|" + o.from;
					if (!sets.containsKey(key)) {
						sets.put(key, new java.util.LinkedHashMap<String, List<Double>>());
						setsByStation.computeIfAbsent(o.from, k -> new ArrayList<String>()).add(key);
					}
					sets.get(key).computeIfAbsent(o.to, k -> new ArrayList<Double>()).add(o.value);
				}
				else if ((o.type == HORIZONTAL_DISTANCE || o.type == SLOPE_DISTANCE) && o.value > 0.0) {
					dist.computeIfAbsent(o.from + "|" + o.to, k -> new ArrayList<Double>()).add(o.value);
					dist.computeIfAbsent(o.to + "|" + o.from, k -> new ArrayList<Double>()).add(o.value);
				}
			}
			for (Map.Entry<String, List<String>> e : setsByStation.entrySet()) {
				List<String> keys = e.getValue();
				for (int b = 1; b < keys.size(); b++) {
					Map<String, List<Double>> first = sets.get(keys.get(0)), other = sets.get(keys.get(b));
					List<String> common = new ArrayList<String>();
					for (String t : first.keySet())
						if (other.containsKey(t))
							common.add(t);
					Station_Check check = new Station_Check(e.getKey());
					check.commonTargets = common.size();
					if (!common.isEmpty()) {
						List<Double> diffs = new ArrayList<Double>();
						for (String t : common)
							diffs.add(wrap2Pi(directionMedian(other.get(t)) - directionMedian(first.get(t))));
						double mean = orientation(diffs);
						check.orientationDifference = mean;
						for (String t : common) {
							double r = wrapPi(wrap2Pi(directionMedian(other.get(t)) - directionMedian(first.get(t))) - mean);
							List<Double> ds = dist.get(e.getKey() + "|" + t);
							double cross = ds == null ? Double.NaN : Math.abs(r) * median(ds);
							// massgebend ist die Querabweichung im Ziel (wenn eine Strecke vorliegt), sonst der Winkelrest
							double key = Double.isFinite(cross) ? cross : Math.abs(r);
							double worstKey = check.worstTarget == null ? -1.0 : (Double.isFinite(check.worstCross) ? check.worstCross : check.worstResidual);
							boolean worse = key > worstKey;
							if (worse) {
								check.worstTarget = t;
								check.worstResidual = Math.abs(r);
								check.worstCross = cross;
							}
						}
					}
					result.stationChecks.add(check);
				}
			}
		}

		/** Median von Richtungslesungen (um den ersten Wert gewickelt). */
		static double directionMedian(List<Double> values) {
			double ref = values.get(0);
			List<Double> w = new ArrayList<Double>();
			for (double v : values)
				w.add(ref + wrapPi(v - ref));
			return wrap2Pi(median(w));
		}

		/** Grenzen fuer "anderes Koordinaten-/Hoehensystem" der vorhandenen Naeherung. */
		public static final double FOREIGN_POSITION = 100.0, FOREIGN_HEIGHT = 20.0;

		// ---------------- Hilfsfunktionen ----------------

		static double wrapPi(double a) {
			a = a % TWO_PI;
			if (a > Math.PI)
				a -= TWO_PI;
			else if (a <= -Math.PI)
				a += TWO_PI;
			return a;
		}

		static double wrap2Pi(double a) {
			a = a % TWO_PI;
			return a < 0.0 ? a + TWO_PI : a;
		}

		static double median(List<Double> values) {
			if (values.isEmpty())
				return Double.NaN;
			double[] v = new double[values.size()];
			for (int i = 0; i < v.length; i++)
				v[i] = values.get(i);
			java.util.Arrays.sort(v);
			int m = v.length / 2;
			return v.length % 2 == 1 ? v[m] : 0.5 * (v[m - 1] + v[m]);
		}

		static double circularMedian(List<Double> angles) {
			if (angles.isEmpty())
				return Double.NaN;
			double ref = angles.get(0);
			List<Double> d = new ArrayList<Double>(angles.size());
			for (double a : angles)
				d.add(wrapPi(a - ref));
			return wrap2Pi(ref + median(d));
		}

		/** Orientierung aus (t - r): robuster Kreis-Median (Lage II muss wie in JAG3D bereits reduziert sein). */
		static double orientation(List<Double> diffs) {
			return circularMedian(diffs);
		}

		private static long pairKey(int a, int b) {
			return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
		}

		private static long directedKey(int a, int b) {
			return ((long) a << 32) | b;
		}

		// ---------------- Rechnung ----------------

		public static Result compute(List<Point> points, List<Observation> observations, Settings settings) {
			Solver solver = new Solver(points, observations, settings == null ? new Settings() : settings);
			Result result = solver.run();
			// Richtungsgruppen mit mehreren Standpunkten (JAG3D: eine Orientierung je Gruppe)
			Map<Integer, Set<String>> stations = new java.util.TreeMap<Integer, Set<String>>();
			for (Observation o : observations)
				if (o.type == DIRECTION)
					stations.computeIfAbsent(o.group, g -> new java.util.HashSet<String>()).add(o.from);
			for (Map.Entry<Integer, Set<String>> e : stations.entrySet())
				if (e.getValue().size() > 1)
					result.mixedDirectionGroups.add(new int[] { e.getKey(), e.getValue().size() });
			checkStations(observations, result);
			checkGnssSessions(points, observations, result);
			return result;
		}

		/** Drift innerhalb einer GNSS-Sitzung bzw. Versatz einer Sitzung gegen die uebrigen, ab dem gemeldet wird (m). */
		public static final double GNSS_DRIFT_LIMIT = 0.10;
		/** Mindestzahl Basislinien einer Sitzung fuer die Driftpruefung (Versatz: GNSS_OFFSET_MIN_EPOCHS). */
		public static final int GNSS_DRIFT_MIN_EPOCHS = 10, GNSS_OFFSET_MIN_EPOCHS = 5;
		/** hoechstens so viele andere GNSS-Zeilen zwischen zwei Basislinien derselben Sitzung (Messreihenfolge = Id). */
		public static final int GNSS_SESSION_GAP = 5;

		/**
		 * GNSS-Sitzung mit Drift oder Versatz. point = auffaelliger Punkt (an mindestens zwei Partnern auffaellig) oder
		 * "A - B" fuer ein einzelnes Punktpaar.
		 */
		public static final class Gnss_Session_Finding {
			public final String point;
			public final java.util.Set<String> partners = new java.util.TreeSet<String>();
			public int sessions = 0, flagged = 0, driftEpochs = 0;
			/** groesste Drift (Betrag, m) mit Hoehenanteil (Oben, m, mit Vorzeichen) und groesster Versatz (m). */
			public double drift = 0.0, driftUp = 0.0, offset = 0.0;
			public String driftPartner = null, offsetPartner = null;

			Gnss_Session_Finding(String point) {
				this.point = point;
			}
		}

		/**
		 * Wertet je Punktpaar die 3D-Basislinien in Messreihenfolge (Id) als Zeitreihe aus. Sitzung = aufeinander
		 * folgende Zeilen desselben Paars (hoechstens GNSS_SESSION_GAP fremde Zeilen dazwischen). Drift = Median
		 * des letzten minus Median des ersten Viertels; Versatz = Sitzungs-Median minus Median aller Basislinien des
		 * Paars. Komponenten Ost/Nord/Oben (geozentrische Vektoren werden am Startpunkt gedreht).
		 */
		static void checkGnssSessions(List<Point> points, List<Observation> observations, Result result) {
			Map<String, Point> byName = new java.util.HashMap<String, Point>();
			for (Point p : points)
				byName.put(p.name, p);
			// Paar (sortiert) -> Liste {laufende Nr., E, N, U}
			Map<String, List<double[]>> series = new java.util.LinkedHashMap<String, List<double[]>>();
			int seq = 0;
			for (Observation o : observations) {
				if (o.type != GNSS3D && o.type != GNSS2D && o.type != GNSS1D)
					continue;
				seq++;
				if (o.type != GNSS3D || !Double.isFinite(o.gy) || !Double.isFinite(o.gx) || !Double.isFinite(o.gz))
					continue;
				boolean swap = o.from.compareTo(o.to) > 0;
				String key = swap ? o.to + "\u0000" + o.from : o.from + "\u0000" + o.to;
				double sign = swap ? -1.0 : 1.0;
				double[] enu = { sign * o.gy, sign * o.gx, sign * o.gz };
				Point ref = byName.get(o.from);
				if (ref != null && Klein_Method_Local_System.isEcef(ref.x0, ref.y0, ref.z0)) {
					double[] llh = Klein_Method_Geo.ecefToLatLonHeight(ref.x0, ref.y0, ref.z0);
					double la = Math.toRadians(llh[0]), lo = Math.toRadians(llh[1]);
					double dX = sign * o.gx, dY = sign * o.gy, dZ = sign * o.gz;
					enu = new double[] { -Math.sin(lo) * dX + Math.cos(lo) * dY,
							-Math.sin(la) * Math.cos(lo) * dX - Math.sin(la) * Math.sin(lo) * dY + Math.cos(la) * dZ,
							Math.cos(la) * Math.cos(lo) * dX + Math.cos(la) * Math.sin(lo) * dY + Math.sin(la) * dZ };
				}
				series.computeIfAbsent(key, k -> new ArrayList<double[]>()).add(new double[] { seq, enu[0], enu[1], enu[2] });
			}
			// je Paar: Sitzungen, Drift, Versatz
			Map<String, Gnss_Session_Finding> pairFindings = new java.util.LinkedHashMap<String, Gnss_Session_Finding>();
			for (Map.Entry<String, List<double[]>> e : series.entrySet()) {
				List<double[]> all = e.getValue();
				List<List<double[]>> sessions = new ArrayList<List<double[]>>();
				double prev = Double.NEGATIVE_INFINITY;
				for (double[] r : all) {
					if (r[0] - prev > GNSS_SESSION_GAP + 1)
						sessions.add(new ArrayList<double[]>());
					sessions.get(sessions.size() - 1).add(r);
					prev = r[0];
				}
				double[] pairMedian = componentMedians(all, 0, all.size());
				String[] names = e.getKey().split("\u0000");
				Gnss_Session_Finding f = new Gnss_Session_Finding(names[0] + " - " + names[1]);
				f.sessions = sessions.size();
				for (List<double[]> s : sessions) {
					boolean flagged = false;
					int n = s.size();
					if (n >= GNSS_DRIFT_MIN_EPOCHS) {
						int q = Math.max(3, n / 4);
						double[] a = componentMedians(s, 0, q), b = componentMedians(s, n - q, n);
						double d = Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
						if (d > GNSS_DRIFT_LIMIT) {
							flagged = true;
							if (d > f.drift) {
								f.drift = d;
								f.driftUp = b[2] - a[2];
								f.driftEpochs = n;
							}
						}
					}
					if (sessions.size() > 1 && n >= GNSS_OFFSET_MIN_EPOCHS && 2 * n < all.size()) {
						double[] m = componentMedians(s, 0, n);
						double o = Math.sqrt((m[0] - pairMedian[0]) * (m[0] - pairMedian[0]) + (m[1] - pairMedian[1]) * (m[1] - pairMedian[1])
								+ (m[2] - pairMedian[2]) * (m[2] - pairMedian[2]));
						if (o > GNSS_DRIFT_LIMIT) {
							flagged = true;
							f.offset = Math.max(f.offset, o);
						}
					}
					if (flagged)
						f.flagged++;
				}
				if (f.flagged > 0)
					pairFindings.put(e.getKey(), f);
			}
			if (pairFindings.isEmpty())
				return;
			// Zuordnung (gierig): der Punkt mit den meisten noch nicht erklaerten auffaelligen Paaren erklaert sie, wenn
			// es mindestens zwei sind - ein gestoerter Neupunkt macht so nicht auch seine Referenzstationen auffaellig
			Map<String, Gnss_Session_Finding> pointFindings = new java.util.LinkedHashMap<String, Gnss_Session_Finding>();
			java.util.Set<String> covered = new java.util.HashSet<String>();
			while (true) {
				Map<String, List<String>> open = new java.util.TreeMap<String, List<String>>();
				for (String key : pairFindings.keySet()) {
					if (covered.contains(key))
						continue;
					String[] names = key.split("\u0000");
					open.computeIfAbsent(names[0], k -> new ArrayList<String>()).add(key);
					open.computeIfAbsent(names[1], k -> new ArrayList<String>()).add(key);
				}
				String best = null;
				for (Map.Entry<String, List<String>> e : open.entrySet())
					if (best == null || e.getValue().size() > open.get(best).size())
						best = e.getKey();
				if (best == null || open.get(best).size() < 2)
					break;
				Gnss_Session_Finding pf = new Gnss_Session_Finding(best);
				for (String key : open.get(best)) {
					String[] names = key.split("\u0000");
					boolean isEnd = names[1].equals(best);
					String partner = isEnd ? names[0] : names[1];
					Gnss_Session_Finding f = pairFindings.get(key);
					pf.partners.add(partner);
					pf.sessions += f.sessions;
					pf.flagged += f.flagged;
					// Vorzeichen der Hoehe aus Sicht des Punktes (Vektor zeigt von names[0] nach names[1])
					double up = isEnd ? f.driftUp : -f.driftUp;
					if (f.drift > pf.drift) {
						pf.drift = f.drift;
						pf.driftUp = up;
						pf.driftEpochs = f.driftEpochs;
						pf.driftPartner = partner;
					}
					if (f.offset > pf.offset) {
						pf.offset = f.offset;
						pf.offsetPartner = partner;
					}
					covered.add(key);
				}
				pointFindings.put(best, pf);
			}
			result.gnssSessionFindings.addAll(pointFindings.values());
			for (Map.Entry<String, Gnss_Session_Finding> e : pairFindings.entrySet())
				if (!covered.contains(e.getKey()))
					result.gnssSessionFindings.add(e.getValue());
			result.gnssSessionFindings.sort((x, y) -> Double.compare(Math.max(y.drift, y.offset), Math.max(x.drift, x.offset)));
		}

		/** Komponenten-Median {E, N, U} der Zeilen [from, to) einer Liste {Nr., E, N, U}. */
		private static double[] componentMedians(List<double[]> rows, int from, int to) {
			double[] r = new double[3];
			for (int c = 0; c < 3; c++) {
				List<Double> v = new ArrayList<Double>(to - from);
				for (int i = from; i < to; i++)
					v.add(rows.get(i)[c + 1]);
				r[c] = median(v);
			}
			return r;
		}

		private static final class Obs {
			final Observation o;
			final int s, t;
			DirSet set;

			Obs(Observation o, int s, int t) {
				this.o = o;
				this.s = s;
				this.t = t;
			}
		}

		private static final class DirSet {
			final String key;
			final int station;
			final List<Obs> obs = new ArrayList<Obs>();
			final Map<Integer, Double> reading = new java.util.LinkedHashMap<Integer, Double>();
			double orientation = Double.NaN;
			int unknown = -1;

			DirSet(String key, int station) {
				this.key = key;
				this.station = station;
			}
		}

		/**
		 * Normalgleichung im Profilformat (Skyline) mit Umnummerierung nach Reverse-Cuthill-McKee:
		 * Speicher und Rechenzeit wachsen bei Zuegen und Netzen nur mit der Bandbreite, nicht quadratisch.
		 */
		static final class Skyline {
			final int m;
			final int[] perm, first;
			final double[][] row;

			/** @param dense Unbekannte, die mit fast allen gekoppelt ist (Massstab), wird ans Ende gestellt; -1 = keine */
			Skyline(int m, List<int[]> structure, int dense) {
				this.m = m;
				List<Set<Integer>> adj = new ArrayList<Set<Integer>>(m);
				for (int i = 0; i < m; i++)
					adj.add(new java.util.HashSet<Integer>());
				for (int[] r : structure)
					for (int a : r)
						for (int b : r)
							if (a >= 0 && b >= 0 && a != b && a != dense && b != dense)
								adj.get(a).add(b);
				// Reverse Cuthill-McKee je Zusammenhangskomponente
				int[] order = new int[m];
				boolean[] seen = new boolean[m];
				int pos = 0;
				Integer[] byDegree = new Integer[m];
				for (int i = 0; i < m; i++)
					byDegree[i] = i;
				java.util.Arrays.sort(byDegree, (a, b) -> Integer.compare(adj.get(a).size(), adj.get(b).size()));
				if (dense >= 0)
					seen[dense] = true;
				for (int start : byDegree) {
					if (seen[start])
						continue;
					java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<Integer>();
					queue.add(start);
					seen[start] = true;
					while (!queue.isEmpty()) {
						int v = queue.poll();
						order[pos++] = v;
						List<Integer> nb = new ArrayList<Integer>();
						for (int u : adj.get(v))
							if (!seen[u]) {
								seen[u] = true;
								nb.add(u);
							}
						nb.sort((a, b) -> Integer.compare(adj.get(a).size(), adj.get(b).size()));
						queue.addAll(nb);
					}
				}
				this.perm = new int[m]; // alte Nummer -> neue Nummer
				int regular = dense >= 0 ? m - 1 : m;
				for (int i = 0; i < regular; i++)
					this.perm[order[regular - 1 - i]] = i;
				if (dense >= 0)
					this.perm[dense] = m - 1;
				this.first = new int[m];
				for (int i = 0; i < m; i++)
					this.first[i] = i;
				if (dense >= 0)
					this.first[m - 1] = 0;
				for (int a = 0; a < m; a++)
					for (int b : adj.get(a)) {
						int i = this.perm[a], j = this.perm[b];
						if (j < i && j < this.first[i])
							this.first[i] = j;
					}
				this.row = new double[m][];
				for (int i = 0; i < m; i++)
					this.row[i] = new double[i - this.first[i] + 1];
			}

			long size() {
				long s = 0;
				for (double[] r : this.row)
					s += r.length;
				return s;
			}

			void clear() {
				for (double[] r : this.row)
					java.util.Arrays.fill(r, 0.0);
			}

			void addRow(double[] w, int[] idx, double[] a, double l, double p) {
				for (int k = 0; k < idx.length; k++) {
					if (idx[k] < 0)
						continue;
					int i = this.perm[idx[k]];
					w[idx[k]] += p * a[k] * l;
					for (int q = 0; q < idx.length; q++) {
						if (idx[q] < 0)
							continue;
						int j = this.perm[idx[q]];
						if (j <= i)
							this.row[i][j - this.first[i]] += p * a[k] * a[q];
					}
				}
			}

			/** Kleine Daempfung (Levenberg-Marquardt) gegen den Lagerungsdefekt freier Netzteile. */
			void damp(double factor) {
				double mean = 0;
				for (int i = 0; i < this.m; i++)
					mean += this.row[i][i - this.first[i]];
				mean = Math.max(mean / Math.max(this.m, 1), 1e-12);
				for (int i = 0; i < this.m; i++)
					this.row[i][i - this.first[i]] += factor * mean;
			}

			double[] solve(double[] wOld) {
				int m = this.m;
				double[][] L = this.row;
				for (int i = 0; i < m; i++) {
					int fi = this.first[i];
					for (int j = fi; j <= i; j++) {
						double sum = L[i][j - fi];
						int fj = this.first[j];
						int k0 = Math.max(fi, fj);
						for (int k = k0; k < j; k++)
							sum -= L[i][k - fi] * L[j][k - fj];
						if (j < i)
							L[i][j - fi] = sum / L[j][j - fj];
						else {
							if (!(sum > 0.0))
								return null;
							L[i][i - fi] = Math.sqrt(sum);
						}
					}
				}
				double[] y = new double[m];
				for (int a = 0; a < m; a++)
					y[this.perm[a]] = wOld[a];
				for (int i = 0; i < m; i++) {
					int fi = this.first[i];
					double s = y[i];
					for (int k = fi; k < i; k++)
						s -= L[i][k - fi] * y[k];
					y[i] = s / L[i][i - fi];
				}
				for (int i = m - 1; i >= 0; i--) {
					int fi = this.first[i];
					y[i] /= L[i][i - fi];
					double yi = y[i];
					for (int k = fi; k < i; k++)
						y[k] -= L[i][k - fi] * yi;
				}
				double[] x = new double[m];
				for (int a = 0; a < m; a++)
					x[a] = y[this.perm[a]];
				return x;
			}
		}

		private static final class Solver {
			final Settings settings;
			final Point[] pts;
			final Map<String, Integer> index = new java.util.HashMap<String, Integer>();
			final int n;
			final List<Obs> obs = new ArrayList<Obs>();
			final Map<String, DirSet> sets = new java.util.LinkedHashMap<String, DirSet>();
			final Map<Long, List<Double>> hd = new java.util.HashMap<Long, List<Double>>();
			final Map<Long, List<Double>> slopeOnly = new java.util.HashMap<Long, List<Double>>();
			final Map<Long, List<Double>> zenith = new java.util.HashMap<Long, List<Double>>();
			final Map<Long, Double> hdMedian = new java.util.HashMap<Long, Double>(), slopeMedian = new java.util.HashMap<Long, Double>(), zenithMedian = new java.util.HashMap<Long, Double>();
			final List<List<Integer>> distanceNeighbours = new ArrayList<List<Integer>>();
			final double[] Y, X, Z;
			final boolean[] pos, hgt, ambiguousFlag;
			final int[][] ambiguousPartners;
			final Set<Integer> rejectedPosition = new java.util.HashSet<Integer>(), rejectedHeight = new java.util.HashSet<Integer>();
			final Result result = new Result();
			Map<Integer, List<double[]>> mergeCandidates = null;
			Map<Integer, double[]> mergeKnown = null;
			final Set<Integer> anchorRejected = new java.util.HashSet<Integer>(), anchorHeightRejected = new java.util.HashSet<Integer>();
			double scale = 1.0;
			/** Lage von Altpunkten aus bestimmt (im Projektsystem) - sonst lokales Hilfssystem. */
			boolean seededFromFixed = false;
			double sigmaDir = 1.0 * MGON, sigmaDist = 0.003, sigmaGnss = 0.010, sigmaLev = 0.002, sigmaZen = 1.5 * MGON;

			/** Anzahl GNSS-Sessions je Punktpaar. */
			final Map<Long, Integer> gnssSessions = new java.util.HashMap<Long, Integer>();

			/** Gewichtsfaktor einer Beobachtung - wiederholte GNSS-Sessions zusammen wie eine Basislinie (1/n). */
			double sessionWeight(Obs ob) {
				if (!this.settings.priority || (ob.o.type != GNSS1D && ob.o.type != GNSS2D && ob.o.type != GNSS3D))
					return 1.0;
				Integer c = this.gnssSessions.get(pairKey(ob.s, ob.t));
				return c == null || c < 1 ? 1.0 : 1.0 / c;
			}

			Solver(List<Point> points, List<Observation> observations, Settings settings) {
				this.settings = settings;
				this.pts = points.toArray(new Point[0]);
				this.n = this.pts.length;
				for (int i = 0; i < this.n; i++)
					this.index.put(this.pts[i].name, i);
				this.Y = new double[this.n];
				this.X = new double[this.n];
				this.Z = new double[this.n];
				this.pos = new boolean[this.n];
				this.hgt = new boolean[this.n];
				this.ambiguousFlag = new boolean[this.n];
				this.ambiguousPartners = new int[this.n][];
				for (Observation o : observations) {
					Integer s = this.index.get(o.from), t = this.index.get(o.to);
					if (s == null || t == null || s.intValue() == t.intValue() || !Double.isFinite(o.value))
						continue;
					Obs ob = new Obs(o, s, t);
					this.obs.add(ob);
					long pk = pairKey(s, t);
					if (o.type == GNSS1D || o.type == GNSS2D || o.type == GNSS3D)
						this.gnssSessions.merge(pk, 1, Integer::sum);
					switch (o.type) {
					case DIRECTION: {
						String key = o.group + "|" + s;
						DirSet set = this.sets.get(key);
						if (set == null)
							this.sets.put(key, set = new DirSet(key, s));
						set.obs.add(ob);
						ob.set = set;
						break;
					}
					case HORIZONTAL_DISTANCE:
						if (o.value > 0.0)
							this.hd.computeIfAbsent(pk, k -> new ArrayList<Double>()).add(o.value);
						break;
					case ZENITH_ANGLE: {
						double z = o.value > Math.PI ? TWO_PI - o.value : o.value; // Lage II
						this.zenith.computeIfAbsent(directedKey(s, t), k -> new ArrayList<Double>()).add(z);
						break;
					}
					default:
						break;
					}
				}
				for (Map.Entry<Long, List<Double>> e : this.zenith.entrySet())
					this.zenithMedian.put(e.getKey(), median(e.getValue()));
				// Schraegstrecken: mit Zenitwinkel reduzieren, sonst nur als Ersatz
				for (Obs ob : this.obs) {
					if (ob.o.type != SLOPE_DISTANCE || !(ob.o.value > 0.0))
						continue;
					double z = this.zenithFor(ob.s, ob.t);
					if (Double.isFinite(z))
						this.hd.computeIfAbsent(pairKey(ob.s, ob.t), k -> new ArrayList<Double>()).add(ob.o.value * Math.sin(z));
					else
						this.slopeOnly.computeIfAbsent(pairKey(ob.s, ob.t), k -> new ArrayList<Double>()).add(ob.o.value);
				}
				for (Map.Entry<Long, List<Double>> e : this.hd.entrySet())
					this.hdMedian.put(e.getKey(), median(e.getValue()));
				for (Map.Entry<Long, List<Double>> e : this.slopeOnly.entrySet())
					this.slopeMedian.put(e.getKey(), median(e.getValue()));
				for (int i = 0; i < this.n; i++)
					this.distanceNeighbours.add(new ArrayList<Integer>());
				Set<Long> pairs = new java.util.HashSet<Long>(this.hdMedian.keySet());
				pairs.addAll(this.slopeMedian.keySet());
				for (long k : pairs) {
					int a = (int) (k >>> 32), b = (int) (k & 0xffffffffL);
					this.distanceNeighbours.get(a).add(b);
					this.distanceNeighbours.get(b).add(a);
				}
				// Richtungssaetze: Lesung je Ziel (Median, Lage II auf Lage I)
				for (DirSet set : this.sets.values()) {
					Map<Integer, List<Double>> byTarget = new java.util.LinkedHashMap<Integer, List<Double>>();
					for (Obs ob : set.obs)
						byTarget.computeIfAbsent(ob.t, k -> new ArrayList<Double>()).add(ob.o.value);
					for (Map.Entry<Integer, List<Double>> e : byTarget.entrySet()) {
						double ref = e.getValue().get(0);
						List<Double> d = new ArrayList<Double>();
						for (double v : e.getValue())
							d.add(wrapPi(v - ref));
						set.reading.put(e.getKey(), wrap2Pi(ref + median(d)));
					}
				}
			}

			double zenithFor(int s, int t) {
				Double z = this.zenithMedian.get(directedKey(s, t));
				if (z != null)
					return z;
				z = this.zenithMedian.get(directedKey(t, s));
				return z != null ? Math.PI - z : Double.NaN;
			}

			/** Horizontalstrecke zwischen zwei Punkten aus den Messungen (Median), NaN wenn keine. */
			double hdObs(int a, int b) {
				long k = pairKey(a, b);
				Double v = this.hdMedian.get(k);
				if (v != null)
					return v;
				v = this.slopeMedian.get(k);
				return v != null ? v : Double.NaN;
			}

			double curvature(double h) {
				return this.settings.curvature || this.settings.localFrame ? h * h / (2.0 * this.settings.earthRadius) : 0.0;
			}

			/** Festpunkt mit Hoehe, der die Hoehen lagert (bei gemischten Hoehensystemen ohne 1D-Festpunkte). */
			boolean heightAnchor(int i) {
				return !this.pts[i].isNew && this.pts[i].heightGiven() && !(this.settings.ignoreHeightAnchors1D && this.pts[i].dimension == 1);
			}

			/** je Punkt r^2 / 2R (r = waagerechter Abstand von P0) im lokalen System um P0, sonst 0. */
			double[] frameDrop = null;

			/** Oben(t) - Oben(s) minus Hoehenunterschied entlang des Lots: -(r_t^2 - r_s^2) / 2R. */
			double frameCorrection(int s, int t) {
				return this.frameDrop == null ? 0.0 : -(this.frameDrop[t] - this.frameDrop[s]);
			}

			/**
			 * r^2 / 2R je Punkt: 1D-Punkte mit ihrer eingetragenen Lage (wie JAG3D), sonst Lage aus der Berechnung (wenn von
			 * Altpunkten aus bestimmt), sonst eingetragene lokale Lage, sonst Mittel der Nachbarn, sonst P0.
			 */
			void updateFrameDrop(boolean computedFrame) {
				if (!this.settings.localFrame)
					return;
				double[] r2 = new double[this.n];
				java.util.Arrays.fill(r2, Double.NaN);
				for (int i = 0; i < this.n; i++) {
					double y = Double.NaN, x = Double.NaN;
					if (this.pts[i].dimension == 1) {
						// 1D: JAG3D reduziert in "lokal ellipsoidisch" ueber die eingetragene Lage - genau diese verwenden
						if (Double.isFinite(this.pts[i].y0) && Double.isFinite(this.pts[i].x0)) {
							y = this.pts[i].y0;
							x = this.pts[i].x0;
							if (Math.hypot(y - this.settings.frameEast, x - this.settings.frameNorth) > 20000.0 && !this.result.oneDimensionalFar.contains(this.pts[i].name))
								this.result.oneDimensionalFar.add(this.pts[i].name);
						}
					}
					else if (computedFrame && this.pos[i]) {
						y = this.Y[i];
						x = this.X[i];
					}
					else if (this.pts[i].hasPosition() && Double.isFinite(this.pts[i].y0) && Double.isFinite(this.pts[i].x0)
							&& Math.abs(this.pts[i].y0 - this.settings.frameEast) < 50000.0 && Math.abs(this.pts[i].x0 - this.settings.frameNorth) < 50000.0
							&& !(this.pts[i].y0 == 0.0 && this.pts[i].x0 == 0.0)) {
						y = this.pts[i].y0;
						x = this.pts[i].x0;
					}
					if (Double.isFinite(y))
						r2[i] = Math.pow(y - this.settings.frameEast, 2) + Math.pow(x - this.settings.frameNorth, 2);
				}
				for (int pass = 0; pass < 4; pass++) {
					double[] sum = new double[this.n];
					int[] cnt = new int[this.n];
					for (Obs ob : this.obs)
						for (int[] e : new int[][] { { ob.s, ob.t }, { ob.t, ob.s } })
							if (Double.isNaN(r2[e[0]]) && Double.isFinite(r2[e[1]])) {
								sum[e[0]] += Math.sqrt(r2[e[1]]);
								cnt[e[0]]++;
							}
					boolean any = false;
					for (int i = 0; i < this.n; i++)
						if (cnt[i] > 0) {
							r2[i] = Math.pow(sum[i] / cnt[i], 2);
							any = true;
						}
					if (!any)
						break;
				}
				this.frameDrop = new double[this.n];
				for (int i = 0; i < this.n; i++)
					this.frameDrop[i] = Double.isFinite(r2[i]) ? r2[i] / (2.0 * this.settings.earthRadius) : 0.0;
			}

			Result run() {
				try {
					this.greedyPosition();
					this.resolveAmbiguous();
					this.updateFrameDrop(this.seededFromFixed);
					this.greedyHeight();
					int unknowns = 0;
					for (int i = 0; i < this.n; i++)
						unknowns += (this.pos[i] ? 2 : 0) + (this.hgt[i] ? 1 : 0);
					unknowns += this.sets.size();
					if (unknowns <= this.settings.maxUnknowns) {
						for (int round = 0; round < 2; round++) {
							this.adjustPosition();
							for (int repair = 0; repair < 2 && this.repairPositions(); repair++) {
								this.greedyLoop();
								this.adjustPosition();
							}
							for (int repair = 0; repair < 3 && this.recheckPositions() > 0; repair++)
								this.adjustPosition();
							this.updateFrameDrop(this.seededFromFixed);
							this.adjustHeight();
							for (int repair = 0; repair < 2 && this.repairHeights(); repair++) {
								this.greedyHeightLoop();
								this.adjustHeight();
							}
						}
						// Punkte mit ueberwiegend verworfenen Beobachtungen von der Lage der groesseren
						// Beobachtungsgruppe aus neu rechnen (Mehrfachstart)
						if (this.multiStart()) {
							this.adjustPosition();
							for (int repair = 0; repair < 3 && this.recheckPositions() > 0; repair++)
								this.adjustPosition();
							this.adjustHeight();
							// Kontrolle: passt nach der Ausgleichung weiterhin die Mehrheit nicht, ist die Lage nicht eindeutig
							int[][] stats = this.positionRejectStats();
							for (java.util.Iterator<String> it = this.result.multiStartMoved.iterator(); it.hasNext();) {
								String name = it.next();
								int i = this.index.get(name);
								if (stats[1][i] * 2 > stats[0][i]) {
									it.remove();
									if (!this.result.unreliable.contains(name))
										this.result.unreliable.add(name);
								}
							}
						}
						this.resolveAmbiguous();
						this.result.adjusted = true;
					}
					else
						this.result.failure = "zu viele Unbekannte (" + unknowns + ") - nur Startwerte, keine Vorausgleichung";
					this.collectSuspicious();
					this.frame();
					this.compare();
				} catch (RuntimeException e) {
					this.result.failure = e.getClass().getSimpleName() + ": " + e.getMessage();
				}
				return this.result;
			}

			// ---------- 1) Startwerte Lage ----------

			void greedyPosition() {
				int seeds = 0;
				for (int i = 0; i < this.n; i++)
					if (!this.pts[i].isNew && this.pts[i].positionGiven()) {
						this.Y[i] = this.pts[i].y0;
						this.X[i] = this.pts[i].x0;
						this.pos[i] = true;
						seeds++;
					}
				this.seededFromFixed = seeds > 0;
				if (seeds == 0)
					this.seedLocal();
				this.greedyLoop();
			}

			void greedyLoop() {
				for (int round = 0; round < this.n + 2; round++) {
					int added = this.greedyRound(false);
					if (added == 0)
						added = this.greedyRound(true);
					if (added == 0)
						added = this.mergeLocalSystems();
					if (added == 0)
						break;
				}
			}

			/** Ohne Altpunkte: lokales System am Standpunkt mit den meisten Zielen. */
			void seedLocal() {
				DirSet best = null;
				int bestCount = 0;
				for (DirSet set : this.sets.values()) {
					if (!this.pts[set.station].hasPosition())
						continue;
					int c = 0;
					for (int t : set.reading.keySet())
						if (Double.isFinite(this.hdObs(set.station, t)))
							c++;
					if (c > bestCount) {
						bestCount = c;
						best = set;
					}
				}
				if (best != null) {
					this.pos[best.station] = true;
					this.Y[best.station] = 0.0;
					this.X[best.station] = 0.0;
					best.orientation = 0.0;
					return;
				}
				for (Obs ob : this.obs)
					if ((ob.o.type == GNSS2D || ob.o.type == GNSS3D) && this.pts[ob.s].hasPosition()) {
						this.pos[ob.s] = true;
						return;
					}
				for (Map.Entry<Long, Double> e : this.hdMedian.entrySet()) {
					int a = (int) (e.getKey() >>> 32), b = (int) (e.getKey() & 0xffffffffL);
					if (this.pts[a].hasPosition() && this.pts[b].hasPosition()) {
						this.pos[a] = true;
						this.pos[b] = true;
						this.X[b] = e.getValue();
						return;
					}
				}
			}

			void orientSets() {
				for (DirSet set : this.sets.values()) {
					if (!this.pos[set.station])
						continue;
					List<Double> diffs = new ArrayList<Double>();
					for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
						int t = e.getKey();
						if (!this.pos[t])
							continue;
						double dy = this.Y[t] - this.Y[set.station], dx = this.X[t] - this.X[set.station];
						if (Math.hypot(dy, dx) < 1e-6)
							continue;
						diffs.add(wrap2Pi(Math.atan2(dy, dx) - e.getValue()));
					}
					if (!diffs.isEmpty())
						set.orientation = orientation(diffs);
				}
			}

			int greedyRound(boolean allowArc) {
				// Rangfolge - erst terrestrisch (polar, Schnitte, Standpunktsysteme), GNSS nur wenn das nicht
				// weiterkommt
				if (this.settings.priority) {
					int added = this.greedyRound(false, false);
					if (added > 0)
						return added;
				}
				return this.greedyRound(allowArc, true);
			}

			int greedyRound(boolean allowArc, boolean useGnss) {
				this.orientSets();
				Map<Integer, List<double[]>> cand = new java.util.HashMap<Integer, List<double[]>>();
				// GNSS-Vektoren
				for (Obs ob : this.obs) {
					if (!useGnss || (ob.o.type != GNSS2D && ob.o.type != GNSS3D))
						continue;
					if (this.pos[ob.s] && !this.pos[ob.t] && this.pts[ob.t].hasPosition())
						cand.computeIfAbsent(ob.t, k -> new ArrayList<double[]>()).add(new double[] { this.Y[ob.s] + ob.o.gy, this.X[ob.s] + ob.o.gx });
					else if (this.pos[ob.t] && !this.pos[ob.s] && this.pts[ob.s].hasPosition())
						cand.computeIfAbsent(ob.s, k -> new ArrayList<double[]>()).add(new double[] { this.Y[ob.t] - ob.o.gy, this.X[ob.t] - ob.o.gx });
				}
				// polar und Strahlen fuer den Vorwaertsschnitt
				Map<Integer, List<double[]>> rays = new java.util.HashMap<Integer, List<double[]>>();
				for (DirSet set : this.sets.values()) {
					if (!this.pos[set.station] || Double.isNaN(set.orientation))
						continue;
					for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
						int t = e.getKey();
						if (this.pos[t] || !this.pts[t].hasPosition())
							continue;
						double az = set.orientation + e.getValue();
						double d = this.hdObs(set.station, t);
						if (Double.isFinite(d))
							cand.computeIfAbsent(t, k -> new ArrayList<double[]>())
									.add(new double[] { this.Y[set.station] + d * Math.sin(az), this.X[set.station] + d * Math.cos(az) });
						rays.computeIfAbsent(t, k -> new ArrayList<double[]>()).add(new double[] { this.Y[set.station], this.X[set.station], az, set.station });
					}
				}
				for (Map.Entry<Integer, List<double[]>> e : rays.entrySet()) {
					List<double[]> r = e.getValue();
					for (int i = 0; i < r.size(); i++)
						for (int j = i + 1; j < r.size(); j++) {
							if (r.get(i)[3] == r.get(j)[3])
								continue;
							double[] p = intersect(r.get(i), r.get(j));
							if (p != null)
								cand.computeIfAbsent(e.getKey(), k -> new ArrayList<double[]>()).add(p);
						}
				}
				// lokale Standpunktsysteme (freie Stationierung) und Rueckwaertsschnitt
				for (DirSet set : this.sets.values())
					this.localSystem(set, cand);
				// erst Punkte mit mind. zwei uebereinstimmenden Bestimmungen, einfach bestimmte erst danach
				Map<Integer, double[]> chosen = new java.util.HashMap<Integer, double[]>();
				boolean anySupported = false;
				for (Map.Entry<Integer, List<double[]>> e : cand.entrySet()) {
					int p = e.getKey();
					if (this.pos[p])
						continue;
					List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>();
					for (double[] c : e.getValue()) {
						ys.add(c[0]);
						xs.add(c[1]);
					}
					double my = median(ys), mx = median(xs);
					int support = 0;
					for (double[] c : e.getValue())
						if (Math.hypot(c[0] - my, c[1] - mx) < 0.10)
							support++;
					chosen.put(p, new double[] { my, mx, support });
					anySupported |= support >= 2;
				}
				int added = 0;
				for (Map.Entry<Integer, double[]> e : chosen.entrySet()) {
					if (anySupported && e.getValue()[2] < 2)
						continue;
					int p = e.getKey();
					this.Y[p] = e.getValue()[0];
					this.X[p] = e.getValue()[1];
					this.pos[p] = true;
					added++;
				}
				if (added > 0 || !allowArc)
					return added;
				return this.arcRound();
			}

			/**
			 * Wie die Buendeltransformation von JAG3D: lokale Standpunktsysteme ueber gemeinsame Punkte
			 * (mind. 2, starre Transformation) zusammensetzen, bis ein System mit den bekannten Punkten
			 * verbunden ist. Nur wenn polar/Schnitte/freie Stationierung nicht weiterkommen.
			 */
			int mergeLocalSystems() {
				List<Map<Integer, double[]>> systems = new ArrayList<Map<Integer, double[]>>();
				Map<Integer, double[]> known = new java.util.HashMap<Integer, double[]>();
				for (int i = 0; i < this.n; i++)
					if (this.pos[i])
						known.put(i, new double[] { this.Y[i], this.X[i] });
				this.mergeKnown = known;
				for (DirSet set : this.sets.values()) {
					if (!this.pts[set.station].hasPosition())
						continue;
					Map<Integer, double[]> local = new java.util.HashMap<Integer, double[]>();
					local.put(set.station, new double[] { 0.0, 0.0 });
					for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
						double d = this.hdObs(set.station, e.getKey());
						if (Double.isFinite(d) && this.pts[e.getKey()].hasPosition())
							local.put(e.getKey(), new double[] { d * Math.sin(e.getValue()), d * Math.cos(e.getValue()) });
					}
					if (local.size() >= 3)
						systems.add(local);
				}
				Map<Integer, List<double[]>> candidates = new java.util.HashMap<Integer, List<double[]>>();
				this.mergeCandidates = candidates;
				boolean changed = true;
				while (changed && !systems.isEmpty()) {
					changed = false;
					// 1) an die bekannten Punkte anhaengen
					for (java.util.Iterator<Map<Integer, double[]>> it = systems.iterator(); it.hasNext();) {
						Map<Integer, double[]> sys = it.next();
						if (this.attach(known, sys)) {
							it.remove();
							changed = true;
						}
					}
					if (changed)
						continue;
					// 2) zwei lokale Systeme mit den meisten gemeinsamen Punkten verbinden
					int bestA = -1, bestB = -1, bestCommon = 1;
					Map<Integer, List<Integer>> bySystem = new java.util.HashMap<Integer, List<Integer>>();
					for (int k = 0; k < systems.size(); k++)
						for (int p : systems.get(k).keySet())
							bySystem.computeIfAbsent(p, q -> new ArrayList<Integer>()).add(k);
					Map<Long, Integer> shared = new java.util.HashMap<Long, Integer>();
					for (List<Integer> list : bySystem.values())
						for (int a = 0; a < list.size(); a++)
							for (int b = a + 1; b < list.size(); b++) {
								long key = ((long) list.get(a) << 32) | list.get(b);
								int c = shared.merge(key, 1, Integer::sum);
								if (c > bestCommon) {
									bestCommon = c;
									bestA = list.get(a);
									bestB = list.get(b);
								}
							}
					if (bestA < 0)
						break;
					Map<Integer, double[]> a = systems.get(bestA), b = systems.get(bestB);
					if (this.attach(a, b)) {
						systems.remove(bestB);
						changed = true;
					}
					else {
						// nicht vereinbar (grober Fehler): kleineres System verwerfen
						systems.remove(a.size() < b.size() ? bestA : bestB);
						changed = true;
					}
				}
				this.mergeCandidates = null;
				int added = 0;
				for (Map.Entry<Integer, double[]> e : known.entrySet()) {
					int p = e.getKey();
					if (this.pos[p])
						continue;
					// Median aller Systeme, in denen der Punkt vorkommt (robust gegen einzelne grobe Richtungen)
					List<double[]> c = candidates.get(p);
					List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>();
					if (c != null)
						for (double[] v : c) {
							ys.add(v[0]);
							xs.add(v[1]);
						}
					this.Y[p] = ys.isEmpty() ? e.getValue()[0] : median(ys);
					this.X[p] = xs.isEmpty() ? e.getValue()[1] : median(xs);
					this.pos[p] = true;
					added++;
				}
				return added;
			}

			/** Lokales System in das Zielsystem einhaengen (mind. 2 gemeinsame Punkte, Rest unter 0,5 m). */
			boolean attach(Map<Integer, double[]> target, Map<Integer, double[]> sys) {
				List<double[]> from = new ArrayList<double[]>(), to = new ArrayList<double[]>();
				boolean anyNew = false;
				for (Map.Entry<Integer, double[]> e : sys.entrySet()) {
					double[] t = target.get(e.getKey());
					if (t != null) {
						from.add(e.getValue());
						to.add(t);
					}
					else
						anyNew = true;
				}
				if (from.size() < 2)
					return false;
				double[] tr = rigid(from, to);
				if (tr == null)
					return false;
				double c = Math.cos(tr[2]), s = Math.sin(tr[2]);
				List<Double> res = new ArrayList<Double>();
				for (int i = 0; i < from.size(); i++) {
					double[] l = from.get(i);
					res.add(Math.hypot(tr[0] + c * l[0] + s * l[1] - to.get(i)[0], tr[1] - s * l[0] + c * l[1] - to.get(i)[1]));
				}
				if (median(res) > 0.5)
					return false;
				for (Map.Entry<Integer, double[]> e : sys.entrySet()) {
					double[] l = e.getValue();
					double[] g = { tr[0] + c * l[0] + s * l[1], tr[1] - s * l[0] + c * l[1] };
					if (!target.containsKey(e.getKey()))
						target.put(e.getKey(), g);
					if (this.mergeCandidates != null && target == this.mergeKnown)
						this.mergeCandidates.computeIfAbsent(e.getKey(), k -> new ArrayList<double[]>()).add(g);
				}
				return true;
			}

			static double[] intersect(double[] a, double[] b) {
				double sa = Math.sin(a[2]), ca = Math.cos(a[2]), sb = Math.sin(b[2]), cb = Math.cos(b[2]);
				double det = sa * cb - ca * sb; // sin(b - a) mit Vorzeichen
				if (Math.abs(det) < Math.sin(20.0 * Math.PI / 200.0))
					return null; // schleifender Schnitt
				double dy = b[0] - a[0], dx = b[1] - a[1];
				double ta = (dy * cb - dx * sb) / det;
				if (ta <= 0.0)
					return null;
				return new double[] { a[0] + ta * sa, a[1] + ta * ca };
			}

			void localSystem(DirSet set, Map<Integer, List<double[]>> cand) {
				int s = set.station;
				if (!this.pts[s].hasPosition())
					return;
				List<Integer> members = new ArrayList<Integer>();
				List<double[]> local = new ArrayList<double[]>();
				members.add(s);
				local.add(new double[] { 0.0, 0.0 });
				List<Integer> knownTargetsNoDist = new ArrayList<Integer>();
				boolean anyUnknown = !this.pos[s];
				for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
					int t = e.getKey();
					double d = this.hdObs(s, t);
					if (Double.isFinite(d)) {
						members.add(t);
						local.add(new double[] { d * Math.sin(e.getValue()), d * Math.cos(e.getValue()) });
						anyUnknown |= !this.pos[t] && this.pts[t].hasPosition();
					}
					else if (this.pos[t])
						knownTargetsNoDist.add(t);
				}
				if (!anyUnknown)
					return;
				List<double[]> from = new ArrayList<double[]>(), to = new ArrayList<double[]>();
				for (int i = 0; i < members.size(); i++)
					if (this.pos[members.get(i)]) {
						from.add(local.get(i));
						to.add(new double[] { this.Y[members.get(i)], this.X[members.get(i)] });
					}
				if (from.size() >= 2) {
					double[] tr = rigid(from, to);
					if (tr == null)
						return;
					for (int i = 0; i < members.size(); i++) {
						int m = members.get(i);
						if (this.pos[m] || !this.pts[m].hasPosition())
							continue;
						double[] l = local.get(i);
						double c = Math.cos(tr[2]), sn = Math.sin(tr[2]);
						cand.computeIfAbsent(m, k -> new ArrayList<double[]>()).add(new double[] { tr[0] + c * l[0] + sn * l[1], tr[1] - sn * l[0] + c * l[1] });
					}
					return;
				}
				// Rueckwaertsschnitt nur aus Richtungen (Standpunkt unbekannt, mind. 3 bekannte Ziele)
				if (!this.pos[s]) {
					List<Integer> known = new ArrayList<Integer>();
					for (int t : set.reading.keySet())
						if (this.pos[t])
							known.add(t);
					if (known.size() >= 3) {
						double[] p = this.resection(set, known);
						if (p != null)
							cand.computeIfAbsent(s, k -> new ArrayList<double[]>()).add(p);
					}
				}
			}

			/**
			 * Starre Transformation (Drehung + Verschiebung, Massstab 1) lokal -> Ziel, kleinste Quadrate.
			 * Drehung um phi im Uhrzeigersinn (geodaetisch): Y = ty + cos*ly + sin*lx, X = tx - sin*ly + cos*lx.
			 * @return {ty, tx, phi} oder null
			 */
			static double[] rigid(List<double[]> from, List<double[]> to) {
				int m = from.size();
				double fy = 0, fx = 0, gy = 0, gx = 0;
				for (int i = 0; i < m; i++) {
					fy += from.get(i)[0];
					fx += from.get(i)[1];
					gy += to.get(i)[0];
					gx += to.get(i)[1];
				}
				fy /= m;
				fx /= m;
				gy /= m;
				gx /= m;
				double a = 0, b = 0, spread = 0;
				for (int i = 0; i < m; i++) {
					double ly = from.get(i)[0] - fy, lx = from.get(i)[1] - fx;
					double ty = to.get(i)[0] - gy, tx = to.get(i)[1] - gx;
					// ty = c*ly + s*lx ; tx = -s*ly + c*lx
					a += ly * ty + lx * tx; // ~ c
					b += lx * ty - ly * tx; // ~ s
					spread += ly * ly + lx * lx;
				}
				if (spread < 0.01)
					return null;
				double phi = Math.atan2(b, a);
				double c = Math.cos(phi), s = Math.sin(phi);
				return new double[] { gy - (c * fy + s * fx), gx - (-s * fy + c * fx), phi };
			}

			/** Rueckwaertsschnitt: Gauss-Newton fuer (y, x, o) von mehreren Startpunkten, bestes eindeutiges Ergebnis. */
			double[] resection(DirSet set, List<Integer> known) {
				double cy = 0, cx = 0, rad = 0;
				for (int t : known) {
					cy += this.Y[t];
					cx += this.X[t];
				}
				cy /= known.size();
				cx /= known.size();
				for (int t : known)
					rad = Math.max(rad, Math.hypot(this.Y[t] - cy, this.X[t] - cx));
				double[] best = null;
				double bestRms = Double.MAX_VALUE;
				for (int k = 0; k < 9; k++) {
					double y = cy + (k == 0 ? 0 : rad * Math.sin(k * Math.PI / 4.0));
					double x = cx + (k == 0 ? 0 : rad * Math.cos(k * Math.PI / 4.0));
					double o = Double.NaN;
					double rms = Double.MAX_VALUE;
					for (int it = 0; it < 30; it++) {
						List<Double> diffs = new ArrayList<Double>();
						for (int t : known)
							diffs.add(wrap2Pi(Math.atan2(this.Y[t] - y, this.X[t] - x) - set.reading.get(t)));
						o = orientation(diffs);
						double[][] N = new double[3][3];
						double[] w = new double[3];
						double ss = 0;
						for (int t : known) {
							double dy = this.Y[t] - y, dx = this.X[t] - x, d2 = dy * dy + dx * dx;
							if (d2 < 1e-6)
								continue;
							double v = wrapPi(set.reading.get(t) + o - Math.atan2(dy, dx));
							double[] a = { -dx / d2, dy / d2, -1.0 }; // d(t)/dy_s = -dx/d2, d(t)/dx_s = dy/d2, d/do (Beob = t - o) -> -1
							// Beobachtung r = t - o; Verbesserung: r - (t - o) = -v ... Gleichung a*dp = (r - (t-o))
							double l = wrapPi(set.reading.get(t) - (Math.atan2(dy, dx) - o));
							l = wrapPi(l);
							for (int i = 0; i < 3; i++) {
								w[i] += a[i] * l;
								for (int j = 0; j < 3; j++)
									N[i][j] += a[i] * a[j];
							}
							ss += v * v;
						}
						for (int i = 0; i < 3; i++)
							N[i][i] += 1e-12;
						double[] dp = solveSmall(N, w);
						if (dp == null)
							break;
						y += dp[0];
						x += dp[1];
						rms = Math.sqrt(ss / known.size());
						if (Math.hypot(dp[0], dp[1]) < 1e-5)
							break;
					}
					if (rms < bestRms && Double.isFinite(y) && Double.isFinite(x)) {
						bestRms = rms;
						best = new double[] { y, x };
					}
				}
				return bestRms < 20.0 * MGON ? best : null;
			}

			static double[] solveSmall(double[][] N, double[] w) {
				int m = w.length;
				double[][] a = new double[m][m + 1];
				for (int i = 0; i < m; i++) {
					System.arraycopy(N[i], 0, a[i], 0, m);
					a[i][m] = w[i];
				}
				for (int c = 0; c < m; c++) {
					int p = c;
					for (int r = c + 1; r < m; r++)
						if (Math.abs(a[r][c]) > Math.abs(a[p][c]))
							p = r;
					if (Math.abs(a[p][c]) < 1e-18)
						return null;
					double[] tmp = a[c];
					a[c] = a[p];
					a[p] = tmp;
					for (int r = 0; r < m; r++) {
						if (r == c)
							continue;
						double f = a[r][c] / a[c][c];
						for (int k = c; k <= m; k++)
							a[r][k] -= f * a[c][k];
					}
				}
				double[] x = new double[m];
				for (int i = 0; i < m; i++)
					x[i] = a[i][m] / a[i][i];
				return x;
			}

			/** Bogenschnitt aus Strecken zu bekannten Punkten - nur wenn sonst nichts geht; zwei Loesungen -> Spiegelpunkt. */
			int arcRound() {
				int added = 0;
				for (int p = 0; p < this.n; p++) {
					if (this.pos[p] || !this.pts[p].hasPosition())
						continue;
					List<Integer> partners = new ArrayList<Integer>();
					for (int q : this.distanceNeighbours.get(p))
						if (this.pos[q])
							partners.add(q);
					if (partners.size() < 2)
						continue;
					List<double[]> sols = new ArrayList<double[]>();
					for (int i = 0; i < partners.size(); i++)
						for (int j = i + 1; j < partners.size(); j++) {
							double[][] s = circles(this.Y[partners.get(i)], this.X[partners.get(i)], this.hdObs(p, partners.get(i)),
									this.Y[partners.get(j)], this.X[partners.get(j)], this.hdObs(p, partners.get(j)));
							if (s != null) {
								sols.add(s[0]);
								sols.add(s[1]);
							}
						}
					if (sols.isEmpty())
						continue;
					// Loesung mit der besten Stuetzung durch alle Strecken
					double[] best = null;
					double bestScore = Double.MAX_VALUE, secondScore = Double.MAX_VALUE;
					for (double[] c : sols) {
						double score = 0;
						for (int q : partners)
							score += Math.abs(Math.hypot(c[0] - this.Y[q], c[1] - this.X[q]) - this.hdObs(p, q));
						if (score < bestScore - 1e-9) {
							if (best != null && Math.hypot(best[0] - c[0], best[1] - c[1]) > 0.5)
								secondScore = bestScore;
							bestScore = score;
							best = c;
						}
						else if (best != null && Math.hypot(best[0] - c[0], best[1] - c[1]) > 0.5)
							secondScore = Math.min(secondScore, score);
					}
					this.Y[p] = best[0];
					this.X[p] = best[1];
					this.pos[p] = true;
					if (partners.size() == 2 || secondScore - bestScore < 0.05) {
						this.ambiguousFlag[p] = true;
						this.ambiguousPartners[p] = new int[] { partners.get(0), partners.get(1) };
					}
					added++;
					break; // ein Punkt je Runde, danach wieder polar/Schnitte
				}
				return added;
			}

			static double[][] circles(double y1, double x1, double r1, double y2, double x2, double r2) {
				double d = Math.hypot(y2 - y1, x2 - x1);
				if (d < 1e-6 || d > r1 + r2 + 0.5 || d < Math.abs(r1 - r2) - 0.5)
					return null;
				double a = (r1 * r1 - r2 * r2 + d * d) / (2.0 * d);
				double h = Math.sqrt(Math.max(r1 * r1 - a * a, 0.0));
				double ey = (y2 - y1) / d, ex = (x2 - x1) / d;
				double py = y1 + a * ey, px = x1 + a * ex;
				return new double[][] { { py + h * ex, px - h * ey }, { py - h * ex, px + h * ey } };
			}

			/** Spiegelpunkte: beide Lagen gegen alle Lage-Beobachtungen pruefen. */
			void resolveAmbiguous() {
				this.orientSets();
				for (int p = 0; p < this.n; p++) {
					if (!this.ambiguousFlag[p] || this.ambiguousPartners[p] == null)
						continue;
					int a = this.ambiguousPartners[p][0], b = this.ambiguousPartners[p][1];
					double[] mirror = reflect(this.Y[p], this.X[p], this.Y[a], this.X[a], this.Y[b], this.X[b]);
					double here = this.localMisfit(p, this.Y[p], this.X[p]);
					double there = this.localMisfit(p, mirror[0], mirror[1]);
					if (Double.isNaN(here) || Double.isNaN(there))
						continue;
					if (there + 0.05 < here) {
						this.Y[p] = mirror[0];
						this.X[p] = mirror[1];
						this.ambiguousFlag[p] = false;
					}
					else if (here + 0.05 < there)
						this.ambiguousFlag[p] = false;
				}
			}

			static double[] reflect(double py, double px, double ay, double ax, double by, double bx) {
				double ey = by - ay, ex = bx - ax, l2 = ey * ey + ex * ex;
				if (l2 < 1e-12)
					return new double[] { py, px };
				double t = ((py - ay) * ey + (px - ax) * ex) / l2;
				double fy = ay + t * ey, fx = ax + t * ex;
				return new double[] { 2 * fy - py, 2 * fx - px };
			}

			/** Summe der linearen Widersprueche (ohne die beiden Spiegel-Strecken-Partner) der Lage-Beobachtungen am Punkt p. */
			double localMisfit(int p, double y, double x) {
				double sum = 0;
				int count = 0;
				for (DirSet set : this.sets.values()) {
					if (Double.isNaN(set.orientation))
						continue;
					for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
						int t = e.getKey();
						int s = set.station;
						if (s != p && t != p)
							continue;
						if (s == p && t == p)
							continue;
						if (s == p)
							continue; // Orientierung haengt am Punkt selbst
						if (!this.pos[s])
							continue;
						double dy = y - this.Y[s], dx = x - this.X[s], d = Math.hypot(dy, dx);
						sum += Math.abs(wrapPi(e.getValue() + set.orientation - Math.atan2(dy, dx))) * d;
						count++;
					}
				}
				for (DirSet set : this.sets.values()) {
					if (set.station != p)
						continue;
					List<Double> diffs = new ArrayList<Double>();
					List<Integer> ts = new ArrayList<Integer>();
					for (Map.Entry<Integer, Double> e : set.reading.entrySet())
						if (this.pos[e.getKey()] && e.getKey() != p) {
							diffs.add(wrap2Pi(Math.atan2(this.Y[e.getKey()] - y, this.X[e.getKey()] - x) - e.getValue()));
							ts.add(e.getKey());
						}
					if (diffs.size() < 2)
						continue;
					double o = orientation(diffs);
					for (int i = 0; i < ts.size(); i++) {
						int t = ts.get(i);
						double d = Math.hypot(this.Y[t] - y, this.X[t] - x);
						sum += Math.abs(wrapPi(diffs.get(i) - o)) * d;
						count++;
					}
				}
				for (int q : this.distanceNeighbours.get(p)) {
					if (q == p || !this.pos[q])
						continue;
					double d = this.hdObs(p, q);
					if (Double.isFinite(d)) {
						sum += Math.abs(Math.hypot(y - this.Y[q], x - this.X[q]) - d);
						count++;
					}
				}
				for (Obs ob : this.obs) {
					if ((ob.o.type != GNSS2D && ob.o.type != GNSS3D) || (ob.s != p && ob.t != p))
						continue;
					int q = ob.s == p ? ob.t : ob.s;
					if (!this.pos[q])
						continue;
					double sy = ob.s == p ? y : this.Y[ob.s], sx = ob.s == p ? x : this.X[ob.s];
					double ty = ob.t == p ? y : this.Y[ob.t], tx = ob.t == p ? x : this.X[ob.t];
					sum += Math.hypot(ty - sy - ob.o.gy, tx - sx - ob.o.gx);
					count++;
				}
				return count == 0 ? Double.NaN : sum;
			}

			// ---------- 1b) Startwerte Hoehe ----------

			/** Hoehenunterschiede je Beobachtung (s -> t), NaN wenn (noch) nicht bestimmbar. */
			double heightDifference(Obs ob) {
				Observation o = ob.o;
				switch (o.type) {
				case LEVELING:
					return o.value + o.ih - o.th + this.frameCorrection(ob.s, ob.t);
				case GNSS1D:
				case GNSS3D:
					return o.gz;
				case ZENITH_ANGLE: {
					double z = o.value > Math.PI ? TWO_PI - o.value : o.value;
					double h = Double.NaN;
					if (this.pos[ob.s] && this.pos[ob.t])
						h = Math.hypot(this.Y[ob.t] - this.Y[ob.s], this.X[ob.t] - this.X[ob.s]);
					if (!(h > 0.0))
						h = this.hdMedian.getOrDefault(pairKey(ob.s, ob.t), Double.NaN);
					if (!(h > 0.0)) {
						Double sl = this.slopeMedian.get(pairKey(ob.s, ob.t));
						if (sl != null)
							h = sl * Math.sin(z);
					}
					if (!(h > 0.0) || Math.abs(Math.sin(z)) < 1e-9)
						return Double.NaN;
					return h * Math.cos(z) / Math.sin(z) + this.curvature(h) + o.ih - o.th + this.frameCorrection(ob.s, ob.t);
				}
				default:
					return Double.NaN;
				}
			}

			void greedyHeight() {
				int seeds = 0;
				for (int i = 0; i < this.n; i++)
					if (this.heightAnchor(i)) {
						this.Z[i] = this.pts[i].z0;
						this.hgt[i] = true;
						seeds++;
					}
					else if (!this.pts[i].isNew && this.pts[i].heightGiven() && !this.result.heightAnchorsIgnored.contains(this.pts[i].name))
						this.result.heightAnchorsIgnored.add(this.pts[i].name);
				List<Obs> hobs = new ArrayList<Obs>();
				for (Obs ob : this.obs)
					if (ob.o.type == LEVELING || ob.o.type == GNSS1D || ob.o.type == GNSS3D || ob.o.type == ZENITH_ANGLE)
						if (this.pts[ob.s].hasHeight() && this.pts[ob.t].hasHeight())
							hobs.add(ob);
				if (seeds == 0 && !hobs.isEmpty()) {
					this.hgt[hobs.get(0).s] = true;
					this.Z[hobs.get(0).s] = 0.0;
				}
				this.greedyHeightLoop();
			}

			void greedyHeightLoop() {
				List<Obs> hobs = new ArrayList<Obs>();
				for (Obs ob : this.obs)
					if (ob.o.type == LEVELING || ob.o.type == GNSS1D || ob.o.type == GNSS3D || ob.o.type == ZENITH_ANGLE)
						if (this.pts[ob.s].hasHeight() && this.pts[ob.t].hasHeight())
							hobs.add(ob);
				for (int round = 0; round < this.n + 1; round++) {
					Map<Integer, List<Double>> cand = new java.util.HashMap<Integer, List<Double>>();
					// Rangfolge - erst Nivellement, dann trigonometrisch, GNSS nur wenn sonst nichts weiterkommt
					for (int stage = this.settings.priority ? 1 : 3; stage <= 3 && cand.isEmpty(); stage++)
						this.heightCandidates(hobs, stage, cand);
					if (cand.isEmpty())
						break;
					for (Map.Entry<Integer, List<Double>> e : cand.entrySet()) {
						this.Z[e.getKey()] = median(e.getValue());
						this.hgt[e.getKey()] = true;
					}
				}
			}

			/** Hoehen-Kandidaten; stage 1 = Nivellement, 2 = + Zenitwinkel, 3 = alle (+ GNSS). */
			void heightCandidates(List<Obs> hobs, int stage, Map<Integer, List<Double>> cand) {
				{
					for (Obs ob : hobs) {
						int type = ob.o.type;
						if (stage == 1 && type != LEVELING)
							continue;
						if (stage == 2 && type != LEVELING && type != ZENITH_ANGLE)
							continue;
						double dh = this.heightDifference(ob);
						if (!Double.isFinite(dh))
							continue;
						if (this.hgt[ob.s] && !this.hgt[ob.t])
							cand.computeIfAbsent(ob.t, k -> new ArrayList<Double>()).add(this.Z[ob.s] + dh);
						else if (this.hgt[ob.t] && !this.hgt[ob.s])
							cand.computeIfAbsent(ob.s, k -> new ArrayList<Double>()).add(this.Z[ob.t] - dh);
					}
				}
			}

			// ---------- 2) robuste Vorausgleichung ----------

			static double huber(double u) {
				u = Math.abs(u);
				return u <= 1.5 ? 1.0 : 1.5 / u;
			}

			static double igg3(double u) {
				final double k0 = 2.5, k1 = 6.0;
				u = Math.abs(u);
				if (u <= k0)
					return 1.0;
				if (u >= k1)
					return 0.0;
				double f = (k1 - u) / (k1 - k0);
				return k0 / u * f * f;
			}

			/**
			 * Lage: Unbekannte y, x je bestimmtem Punkt, Orientierung je Richtungssatz und - wenn Altpunkte
			 * vorhanden - ein Massstab der Strecken (Projektion). Altpunkte gehen als weiche Beobachtungen
			 * (10 mm, robust) ein: lange Zuege werden so an die Altpunkte angeschlossen wie spaeter in der
			 * Ausgleichung, ein falscher Altpunkt verliert sein Gewicht.
			 */
			void adjustPosition() {
				int[] col = new int[this.n];
				int m = 0;
				for (int i = 0; i < this.n; i++) {
					col[i] = this.pos[i] ? m : -1;
					if (this.pos[i])
						m += 2;
				}
				this.orientSets();
				for (DirSet set : this.sets.values()) {
					set.unknown = -1;
					if (!this.pos[set.station])
						continue;
					for (int t : set.reading.keySet())
						if (this.pos[t]) {
							set.unknown = m++;
							break;
						}
				}
				List<Integer> anchors = new ArrayList<Integer>();
				for (int i = 0; i < this.n; i++)
					if (this.pos[i] && !this.pts[i].isNew && this.pts[i].positionGiven())
						anchors.add(i);
				if (m == 0)
					return;
				List<Obs> lobs = new ArrayList<Obs>();
				boolean distances = false;
				for (Obs ob : this.obs) {
					if (!this.pos[ob.s] || !this.pos[ob.t])
						continue;
					switch (ob.o.type) {
					case DIRECTION:
						if (ob.set.unknown >= 0)
							lobs.add(ob);
						break;
					case HORIZONTAL_DISTANCE:
					case SLOPE_DISTANCE:
						if (ob.o.value > 0.0) {
							lobs.add(ob);
							distances = true;
						}
						break;
					case GNSS2D:
					case GNSS3D:
						lobs.add(ob);
						break;
					default:
						break;
					}
				}
				int scaleCol = anchors.size() >= 2 && distances ? m++ : -1;
				this.scale = 1.0;
				// Struktur der Normalgleichung (Profil nach Cuthill-McKee)
				List<int[]> rows = new ArrayList<int[]>();
				for (Obs ob : lobs) {
					int s = ob.s, t = ob.t;
					if (ob.o.type == DIRECTION)
						rows.add(new int[] { col[s], col[s] + 1, col[t], col[t] + 1, ob.set.unknown });
					else if (ob.o.type == GNSS2D || ob.o.type == GNSS3D) {
						rows.add(new int[] { col[s], col[t] });
						rows.add(new int[] { col[s] + 1, col[t] + 1 });
					}
					else
						rows.add(new int[] { col[s], col[s] + 1, col[t], col[t] + 1, scaleCol });
				}
				for (int i = 0; i < this.n; i++)
					if (col[i] >= 0)
						rows.add(new int[] { col[i], col[i] + 1 });
				Skyline N = new Skyline(m, rows, scaleCol);
				double[] weight = new double[lobs.size()];
				double[] anchorWeight = new double[anchors.size()];
				java.util.Arrays.fill(weight, 1.0);
				java.util.Arrays.fill(anchorWeight, 1.0);
				final double sigmaAnchor = 0.010;
				boolean hard = false;
				int soft = 0;
				for (int iteration = 0; iteration < 80; iteration++) {
					this.result.iterations++;
					this.updateSigmas(lobs);
					boolean changed = false;
					for (int i = 0; i < lobs.size(); i++) {
						double u = this.standardized(lobs.get(i));
						double nw = Double.isFinite(u) ? (hard ? igg3(u) : huber(u)) : 0.0;
						changed |= Math.abs(nw - weight[i]) > 0.01;
						weight[i] = nw;
					}
					for (int a = 0; a < anchors.size(); a++) {
						int i = anchors.get(a);
						double u = Math.hypot(this.pts[i].y0 - this.Y[i], this.pts[i].x0 - this.X[i]) / sigmaAnchor;
						double nw = hard ? igg3(u) : huber(u);
						changed |= Math.abs(nw - anchorWeight[a]) > 0.01;
						anchorWeight[a] = nw;
					}
					N.clear();
					double[] w = new double[m];
					for (int k = 0; k < lobs.size(); k++) {
						if (weight[k] == 0.0)
							continue;
						Obs ob = lobs.get(k);
						int s = ob.s, t = ob.t;
						double dy = this.Y[t] - this.Y[s], dx = this.X[t] - this.X[s];
						double d2 = dy * dy + dx * dx, d = Math.sqrt(d2);
						if (ob.o.type == GNSS2D || ob.o.type == GNSS3D) {
							double sg = this.gnssSigma(ob);
							double p = weight[k] / (sg * sg);
							double ly = ob.o.gy - dy, lx = ob.o.gx - dx;
							if (!hard && Math.hypot(ly, lx) > 50.0) {
								double f = 3.0 * sg / Math.hypot(ly, lx);
								ly *= f;
								lx *= f;
								p = 1.0 / (sg * sg);
							}
							N.addRow(w, new int[] { col[s], col[t] }, new double[] { -1.0, 1.0 }, ly, p);
							N.addRow(w, new int[] { col[s] + 1, col[t] + 1 }, new double[] { -1.0, 1.0 }, lx, p);
							continue;
						}
						if (d < 1e-6)
							continue;
						if (ob.o.type == DIRECTION) {
							DirSet set = ob.set;
							double l = wrapPi(ob.o.value - (Math.atan2(dy, dx) - this.orientationValue(set)));
							double sd = this.dirSigma(d);
							double p = weight[k] / (sd * sd);
							if (!hard && Math.abs(l) > 0.5) { // grober Richtungsfehler (z. B. 200 gon): ausserhalb der Linearisierung, Wirkung begrenzen
								l = Math.copySign(3.0 * sd, l);
								p = 1.0 / (sd * sd);
							}
							N.addRow(w, new int[] { col[s], col[s] + 1, col[t], col[t] + 1, set.unknown },
									new double[] { -dx / d2, dy / d2, dx / d2, -dy / d2, -1.0 }, l, p);
						}
						else {
							double obsH = ob.o.type == SLOPE_DISTANCE ? this.reduceSlope(ob) : ob.o.value;
							if (!(obsH > 0.0))
								continue;
							double sd = this.distSigma(d);
							double l = obsH * this.scale - d, p = weight[k] / (sd * sd);
							if (!hard && Math.abs(l) > Math.max(0.5 * d, 5.0)) {
								l = Math.copySign(3.0 * sd, l);
								p = 1.0 / (sd * sd);
							}
							N.addRow(w, new int[] { col[s], col[s] + 1, col[t], col[t] + 1, scaleCol },
									new double[] { -dy / d, -dx / d, dy / d, dx / d, -obsH }, l, p);
						}
					}
					for (int a = 0; a < anchors.size(); a++) {
						int i = anchors.get(a);
						double p = anchorWeight[a] / (sigmaAnchor * sigmaAnchor);
						N.addRow(w, new int[] { col[i] }, new double[] { 1.0 }, this.pts[i].y0 - this.Y[i], p);
						N.addRow(w, new int[] { col[i] + 1 }, new double[] { 1.0 }, this.pts[i].x0 - this.X[i], p);
					}
					N.damp(1e-9);
					double[] dp = N.solve(w);
					if (dp == null) {
						this.result.converged = false;
						return;
					}
					double maxStep = 0;
					for (int i = 0; i < this.n; i++)
						if (col[i] >= 0)
							maxStep = Math.max(maxStep, Math.hypot(dp[col[i]], dp[col[i] + 1]));
					double f = maxStep > 200.0 ? 200.0 / maxStep : 1.0; // Schrittbegrenzung
					for (int i = 0; i < this.n; i++)
						if (col[i] >= 0) {
							this.Y[i] += f * dp[col[i]];
							this.X[i] += f * dp[col[i] + 1];
						}
					for (DirSet set : this.sets.values())
						if (set.unknown >= 0)
							set.orientation = wrap2Pi(this.orientationValue(set) + f * dp[set.unknown]);
					if (scaleCol >= 0)
						this.scale += f * dp[scaleCol];
					if (!hard) {
						soft++;
						if (maxStep < 0.001 || soft >= 30)
							hard = true;
						continue;
					}
					if (maxStep < 1e-4 && (!changed || iteration > 50))
						break;
					if (iteration == 79)
						this.result.converged = false;
				}
				this.rejectedPosition.clear();
				for (int i = 0; i < lobs.size(); i++)
					if (weight[i] == 0.0)
						this.rejectedPosition.add(System.identityHashCode(lobs.get(i).o));
				this.anchorRejected.clear();
				for (int a = 0; a < anchors.size(); a++)
					if (anchorWeight[a] == 0.0)
						this.anchorRejected.add(anchors.get(a));
				if (scaleCol >= 0)
					this.result.scalePpm = (this.scale - 1.0) * 1e6;
			}

			double orientationValue(DirSet set) {
				if (Double.isNaN(set.orientation))
					set.orientation = 0.0;
				return set.orientation;
			}

			double reduceSlope(Obs ob) {
				double s = ob.o.value;
				if (this.hgt[ob.s] && this.hgt[ob.t]) {
					double dh = (this.Z[ob.t] + ob.o.th) - (this.Z[ob.s] + ob.o.ih);
					return s > Math.abs(dh) ? Math.sqrt(s * s - dh * dh) : Double.NaN;
				}
				double z = this.zenithFor(ob.s, ob.t);
				return Double.isFinite(z) ? s * Math.sin(z) : s;
			}

			/** Richtung: Geraetegenauigkeit plus 1 mm Zentrier-/Punktunsicherheit (kurze Sichten). */
			double dirSigma(double d) {
				return Math.hypot(this.sigmaDir, 0.001 / Math.max(d, 1.0));
			}

			double distSigma(double d) {
				return Math.hypot(this.sigmaDist, 2e-6 * d);
			}

			/** Normierte Verbesserung einer Lage-Beobachtung (aktueller Stand). */
			double standardized(Obs ob) {
				double dy = this.Y[ob.t] - this.Y[ob.s], dx = this.X[ob.t] - this.X[ob.s], d = Math.hypot(dy, dx);
				switch (ob.o.type) {
				case DIRECTION: {
					DirSet set = ob.set;
					return wrapPi(ob.o.value + this.orientationValue(set) - Math.atan2(dy, dx)) / this.dirSigma(d);
				}
				case HORIZONTAL_DISTANCE:
					return (ob.o.value - d) / this.distSigma(d);
				case SLOPE_DISTANCE: {
					double h = this.reduceSlope(ob);
					return Double.isFinite(h) ? (h - d) / this.distSigma(d) : Double.NaN;
				}
				case GNSS2D:
				case GNSS3D:
					return Math.hypot(ob.o.gy - dy, ob.o.gx - dx) / this.gnssSigma(ob) / Math.sqrt(2.0);
				default:
					return Double.NaN;
				}
			}

			/** Streuung je Typ robust (MAD der normierten Verbesserungen) nachfuehren, mit Unter- und Obergrenze. */
			void updateSigmas(List<Obs> lobs) {
				List<Double> dir = new ArrayList<Double>(), dist = new ArrayList<Double>(), gnss = new ArrayList<Double>();
				for (Obs ob : lobs) {
					double u = this.standardized(ob);
					if (!Double.isFinite(u))
						continue;
					if (ob.o.type == DIRECTION)
						dir.add(Math.abs(u));
					else if (ob.o.type == GNSS2D || ob.o.type == GNSS3D)
						gnss.add(Math.abs(u));
					else
						dist.add(Math.abs(u));
				}
				if (dir.size() >= 5)
					this.sigmaDir = clamp(this.sigmaDir * 1.4826 * median(dir), 0.1 * MGON, 50.0 * MGON);
				if (dist.size() >= 5)
					this.sigmaDist = clamp(this.sigmaDist * 1.4826 * median(dist), 0.0005, 0.5);
				if (gnss.size() >= 5)
					this.sigmaGnss = clamp(this.sigmaGnss * 1.4826 * median(gnss), 0.001, 0.5);
			}

			static double clamp(double v, double lo, double hi) {
				return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
			}

			/** Hoehe: lineare robuste Ausgleichung der Hoehenunterschiede, Althoehen als weiche Beobachtungen (10 mm). */
			void adjustHeight() {
				int[] col = new int[this.n];
				int m = 0;
				for (int i = 0; i < this.n; i++)
					col[i] = this.hgt[i] ? m++ : -1;
				if (m == 0)
					return;
				List<Obs> hobs = new ArrayList<Obs>();
				for (Obs ob : this.obs)
					if ((ob.o.type == LEVELING || ob.o.type == GNSS1D || ob.o.type == GNSS3D || ob.o.type == ZENITH_ANGLE) && this.hgt[ob.s] && this.hgt[ob.t])
						hobs.add(ob);
				if (hobs.isEmpty())
					return;
				List<Integer> anchors = new ArrayList<Integer>();
				for (int i = 0; i < this.n; i++)
					if (this.hgt[i] && this.heightAnchor(i))
						anchors.add(i);
				List<int[]> rows = new ArrayList<int[]>();
				for (Obs ob : hobs)
					rows.add(new int[] { col[ob.s], col[ob.t] });
				Skyline N = new Skyline(m, rows, -1);
				double[] weight = new double[hobs.size()], anchorWeight = new double[anchors.size()];
				java.util.Arrays.fill(weight, 1.0);
				java.util.Arrays.fill(anchorWeight, 1.0);
				final double sigmaAnchor = 0.010;
				for (int iteration = 0; iteration < 50; iteration++) {
					N.clear();
					double[] w = new double[m];
					List<Double> lev = new ArrayList<Double>(), trig = new ArrayList<Double>();
					double[] u = new double[hobs.size()];
					for (int i = 0; i < hobs.size(); i++) {
						Obs ob = hobs.get(i);
						double dh = this.heightDifference(ob);
						if (!Double.isFinite(dh)) {
							u[i] = Double.NaN;
							continue;
						}
						double sigma = this.heightSigma(ob);
						double l = dh - (this.Z[ob.t] - this.Z[ob.s]);
						if (weight[i] > 0.0)
							N.addRow(w, new int[] { col[ob.s], col[ob.t] }, new double[] { -1.0, 1.0 }, l, weight[i] / (sigma * sigma) * this.sessionWeight(ob));
						u[i] = l / sigma;
						if (ob.o.type == LEVELING)
							lev.add(Math.abs(l));
						else if (ob.o.type == ZENITH_ANGLE)
							trig.add(Math.abs(l) / Math.max(this.horizontal(ob), 1.0));
					}
					double[] ua = new double[anchors.size()];
					for (int a = 0; a < anchors.size(); a++) {
						int i = anchors.get(a);
						double l = this.pts[i].z0 - this.Z[i];
						ua[a] = l / sigmaAnchor;
						if (anchorWeight[a] > 0.0)
							N.addRow(w, new int[] { col[i] }, new double[] { 1.0 }, l, anchorWeight[a] / (sigmaAnchor * sigmaAnchor));
					}
					N.damp(1e-9);
					double[] dz = N.solve(w);
					if (dz == null)
						return;
					double maxStep = 0;
					for (int i = 0; i < this.n; i++)
						if (col[i] >= 0) {
							this.Z[i] += dz[col[i]];
							maxStep = Math.max(maxStep, Math.abs(dz[col[i]]));
						}
					if (iteration >= 1) {
						if (lev.size() >= 5)
							this.sigmaLev = clamp(1.4826 * median(lev), 0.0003, 0.2);
						if (trig.size() >= 5)
							this.sigmaZen = clamp(1.4826 * median(trig), 0.2 * MGON, 50.0 * MGON);
					}
					boolean changed = false;
					boolean hard = iteration >= 15 || (iteration >= 3 && maxStep < 0.001);
					for (int i = 0; i < hobs.size(); i++) {
						double nw = Double.isFinite(u[i]) ? (hard ? igg3(u[i]) : huber(u[i])) : weight[i];
						changed |= Math.abs(nw - weight[i]) > 0.01;
						weight[i] = nw;
					}
					for (int a = 0; a < anchors.size(); a++) {
						double nw = hard ? igg3(ua[a]) : huber(ua[a]);
						changed |= Math.abs(nw - anchorWeight[a]) > 0.01;
						anchorWeight[a] = nw;
					}
					if (hard && maxStep < 1e-4 && (!changed || iteration > 40))
						break;
				}
				this.rejectedHeight.clear();
				for (int i = 0; i < hobs.size(); i++)
					if (weight[i] == 0.0)
						this.rejectedHeight.add(System.identityHashCode(hobs.get(i).o));
				for (int a = 0; a < anchors.size(); a++)
					if (anchorWeight[a] == 0.0)
						this.anchorHeightRejected.add(anchors.get(a));
			}

			double horizontal(Obs ob) {
				if (this.pos[ob.s] && this.pos[ob.t])
					return Math.hypot(this.Y[ob.t] - this.Y[ob.s], this.X[ob.t] - this.X[ob.s]);
				double h = this.hdObs(ob.s, ob.t);
				return Double.isFinite(h) ? h : 100.0;
			}

			double heightSigma(Obs ob) {
				switch (ob.o.type) {
				case LEVELING:
					return this.sigmaLev;
				case ZENITH_ANGLE: {
					double z = ob.o.value > Math.PI ? TWO_PI - ob.o.value : ob.o.value;
					double s = Math.max(Math.abs(Math.sin(z)), 0.1);
					return Math.hypot(this.horizontal(ob) * this.sigmaZen / (s * s), 0.002);
				}
				default:
					return Math.hypot((this.settings.priority ? 3.0 : 1.5) * this.sigmaGnss, 2e-6 * Math.sqrt(ob.o.gy * ob.o.gy + ob.o.gx * ob.o.gx + ob.o.gz * ob.o.gz));
				}
			}

			/** GNSS-Basislinie Lage: Grundanteil plus 1 ppm der Laenge (lange Basislinien). */
			double gnssSigma(Obs ob) {
				return Math.hypot(this.sigmaGnss, 1e-6 * Math.sqrt(ob.o.gy * ob.o.gy + ob.o.gx * ob.o.gx + ob.o.gz * ob.o.gz));
			}

			/**
			 * Kontrolle nach der Vorausgleichung: jeden Neupunkt aus den orientierten Standpunkten der Nachbarn
			 * (polar, Vorwaertsschnitt, GNSS) neu bestimmen.
			 *
			 * @return Anzahl versetzter Punkte
			 */
			int recheckPositions() {
				this.orientSets();
				Map<Integer, List<double[]>> cand = new java.util.HashMap<Integer, List<double[]>>();
				Map<Integer, List<double[]>> rays = new java.util.HashMap<Integer, List<double[]>>();
				for (DirSet set : this.sets.values()) {
					int s = set.station;
					if (!this.pos[s] || Double.isNaN(set.orientation))
						continue;
					for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
						int t = e.getKey();
						if (!this.pos[t] || !this.pts[t].isNew)
							continue;
						double az = set.orientation + e.getValue();
						double d = this.hdObs(s, t);
						if (Double.isFinite(d))
							cand.computeIfAbsent(t, k -> new ArrayList<double[]>()).add(new double[] { this.Y[s] + d * Math.sin(az), this.X[s] + d * Math.cos(az) });
						rays.computeIfAbsent(t, k -> new ArrayList<double[]>()).add(new double[] { this.Y[s], this.X[s], az, s });
					}
				}
				for (Map.Entry<Integer, List<double[]>> e : rays.entrySet()) {
					List<double[]> r = e.getValue();
					for (int i = 0; i < r.size(); i++)
						for (int j = i + 1; j < r.size(); j++) {
							if (r.get(i)[3] == r.get(j)[3])
								continue;
							double[] q = intersect(r.get(i), r.get(j));
							if (q != null)
								cand.computeIfAbsent(e.getKey(), k -> new ArrayList<double[]>()).add(q);
						}
				}
				for (Obs ob : this.obs) {
					if (ob.o.type != GNSS2D && ob.o.type != GNSS3D || !this.pos[ob.s] || !this.pos[ob.t])
						continue;
					if (this.pts[ob.t].isNew)
						cand.computeIfAbsent(ob.t, k -> new ArrayList<double[]>()).add(new double[] { this.Y[ob.s] + ob.o.gy, this.X[ob.s] + ob.o.gx });
					if (this.pts[ob.s].isNew)
						cand.computeIfAbsent(ob.s, k -> new ArrayList<double[]>()).add(new double[] { this.Y[ob.t] - ob.o.gy, this.X[ob.t] - ob.o.gx });
				}
				int moved = 0;
				for (Map.Entry<Integer, List<double[]>> e : cand.entrySet()) {
					int p = e.getKey();
					List<double[]> c = e.getValue();
					if (c.size() < 2)
						continue;
					List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>();
					for (double[] v : c) {
						ys.add(v[0]);
						xs.add(v[1]);
					}
					double my = median(ys), mx = median(xs);
					int support = 0, here = 0;
					for (double[] v : c) {
						if (Math.hypot(v[0] - my, v[1] - mx) < 0.05)
							support++;
						if (Math.hypot(v[0] - this.Y[p], v[1] - this.X[p]) < 0.05)
							here++;
					}
					if (support >= 2 && support > here && Math.hypot(my - this.Y[p], mx - this.X[p]) > 0.05) {
						this.Y[p] = my;
						this.X[p] = mx;
						moved++;
					}
				}
				return moved;
			}

			/** Neupunkte mit ueberwiegend verworfenen Lage-Beobachtungen zuruecksetzen. @return true wenn welche */
			boolean repairPositions() {
				int[] total = new int[this.n], rejected = new int[this.n];
				for (Obs ob : this.obs) {
					int type = ob.o.type;
					if (type != DIRECTION && type != HORIZONTAL_DISTANCE && type != SLOPE_DISTANCE && type != GNSS2D && type != GNSS3D)
						continue;
					boolean r = this.rejectedPosition.contains(System.identityHashCode(ob.o));
					for (int p : new int[] { ob.s, ob.t }) {
						total[p]++;
						if (r)
							rejected[p]++;
					}
				}
				boolean any = false;
				for (int p = 0; p < this.n; p++)
					if (this.pts[p].isNew && this.pos[p] && rejected[p] >= 2 && rejected[p] * 2 > total[p]) {
						this.pos[p] = false;
						any = true;
					}
				return any;
			}

			/**
			 * Mehrfachstart fuer Neupunkte, deren Lage-Beobachtungen ueberwiegend verworfen sind (zwei Messgruppen
			 * zeigen auf verschiedene Stellen). Kandidaten = Haeufungen der Einzelbestimmungen (GNSS-Vektoren, polar
			 * aus Richtung + Strecke) und die aktuelle Lage; gezaehlt werden die Lage-Beobachtungen am Punkt, die
			 * innerhalb der Grobfehlergrenze passen. Die Lage mit den meisten passenden Beobachtungen gewinnt, wenn
			 * sie mind. 1,5-mal so viele hat wie jede andere; sonst gilt der Punkt als unsicher (wird gemeldet, nicht
			 * uebernommen).
			 *
			 * @return true, wenn mindestens ein Punkt verlegt wurde
			 */
			/** {gesamt, verworfen} der Lage-Beobachtungen je Punkt. */
			int[][] positionRejectStats() {
				int[] total = new int[this.n], rejected = new int[this.n];
				for (Obs ob : this.obs) {
					int type = ob.o.type;
					if (type != DIRECTION && type != HORIZONTAL_DISTANCE && type != SLOPE_DISTANCE && type != GNSS2D && type != GNSS3D)
						continue;
					boolean r = this.rejectedPosition.contains(System.identityHashCode(ob.o));
					for (int q : new int[] { ob.s, ob.t }) {
						total[q]++;
						if (r)
							rejected[q]++;
					}
				}
				return new int[][] { total, rejected };
			}

			boolean multiStart() {
				int[][] stats = this.positionRejectStats();
				int[] total = stats[0], rejected = stats[1];
				double limit = this.settings.grossLimit > 0.0 ? this.settings.grossLimit : 0.10;
				this.orientSets();
				boolean moved = false;
				for (int p = 0; p < this.n; p++) {
					if (!this.pts[p].isNew || !this.pos[p] || rejected[p] < 4 || rejected[p] * 2 <= total[p])
						continue;
					// Kandidaten: Einzelbestimmungen
					List<double[]> single = new ArrayList<double[]>();
					for (Obs ob : this.obs) {
						int type = ob.o.type;
						if ((type == GNSS2D || type == GNSS3D) && (ob.s == p || ob.t == p)) {
							int other = ob.s == p ? ob.t : ob.s;
							if (!this.pos[other])
								continue;
							double sign = ob.t == p ? 1.0 : -1.0;
							double z = this.hgt[other] && type == GNSS3D ? this.Z[other] + sign * ob.o.gz : Double.NaN;
							single.add(new double[] { this.Y[other] + sign * ob.o.gy, this.X[other] + sign * ob.o.gx, z });
						}
					}
					for (DirSet set : this.sets.values()) {
						int st = set.station;
						if (st == p || !this.pos[st] || !set.reading.containsKey(p))
							continue;
						double ori = this.orientationWithout(set, p);
						double dist = this.hdObs(st, p);
						if (Double.isNaN(ori) || !(dist > 0.0))
							continue;
						double a = set.reading.get(p) + ori;
						single.add(new double[] { this.Y[st] + dist * Math.sin(a), this.X[st] + dist * Math.cos(a), Double.NaN });
					}
					List<double[]> candidates = clusterCandidates(single, limit);
					double[] current = { this.Y[p], this.X[p], this.Z[p] };
					candidates.add(0, current);
					int best = -1;
					double bestCount = -1.0;
					double[] counts = new double[candidates.size()];
					for (int c = 0; c < candidates.size(); c++) {
						counts[c] = this.fittingCount(p, candidates.get(c)[0], candidates.get(c)[1], limit);
						if (counts[c] > bestCount) {
							bestCount = counts[c];
							best = c;
						}
					}
					double second = 0.0;
					for (int c = 0; c < candidates.size(); c++)
						if (c != best && Math.hypot(candidates.get(c)[0] - candidates.get(best)[0], candidates.get(c)[1] - candidates.get(best)[1]) > limit)
							second = Math.max(second, counts[c]);
					String name = this.pts[p].name;
					if (bestCount >= 3.0 && bestCount >= 1.5 * second) {
						double[] b = candidates.get(best);
						if (Math.hypot(b[0] - current[0], b[1] - current[1]) > limit) {
							this.Y[p] = b[0];
							this.X[p] = b[1];
							if (Double.isFinite(b[2]))
								this.Z[p] = b[2];
							moved = true;
							this.result.multiStartMoved.add(name);
						}
					}
					else if (!this.result.unreliable.contains(name))
						this.result.unreliable.add(name);
				}
				return moved;
			}

			/** Orientierung eines Satzes ohne das Ziel p (aus den uebrigen bestimmten Zielen). */
			double orientationWithout(DirSet set, int p) {
				List<Double> diffs = new ArrayList<Double>();
				for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
					int t = e.getKey();
					if (t == p || !this.pos[t])
						continue;
					double dy = this.Y[t] - this.Y[set.station], dx = this.X[t] - this.X[set.station];
					if (Math.hypot(dy, dx) < 1e-6)
						continue;
					diffs.add(wrap2Pi(Math.atan2(dy, dx) - e.getValue()));
				}
				return diffs.isEmpty() ? Double.NaN : orientation(diffs);
			}

			/** Lage-Beobachtungen am Punkt p, die mit p = (y, x) innerhalb der Grenze passen (uebrige Punkte fest). */
			double fittingCount(int p, double y, double x, double limit) {
				double count = 0.0;
				Map<String, Double> ori = new java.util.HashMap<String, Double>();
				for (Obs ob : this.obs) {
					if (ob.s != p && ob.t != p)
						continue;
					int other = ob.s == p ? ob.t : ob.s;
					if (!this.pos[other])
						continue;
					double ys = ob.s == p ? y : this.Y[ob.s], xs = ob.s == p ? x : this.X[ob.s];
					double yt = ob.t == p ? y : this.Y[ob.t], xt = ob.t == p ? x : this.X[ob.t];
					double dy = yt - ys, dx = xt - xs, d = Math.hypot(dy, dx);
					double v;
					switch (ob.o.type) {
					case GNSS2D:
					case GNSS3D:
						v = Math.hypot(ob.o.gy - dy, ob.o.gx - dx);
						break;
					case HORIZONTAL_DISTANCE:
						v = ob.o.value - d;
						break;
					case SLOPE_DISTANCE: {
						double dh = this.hgt[ob.s] && this.hgt[ob.t] ? (this.Z[ob.t] + ob.o.th) - (this.Z[ob.s] + ob.o.ih) : 0.0;
						v = ob.o.value - Math.sqrt(d * d + dh * dh);
						if (!(this.hgt[ob.s] && this.hgt[ob.t])) {
							double ze = this.zenithFor(ob.s, ob.t);
							v = Double.isFinite(ze) ? ob.o.value * Math.sin(ze) - d : Double.NaN;
						}
						break;
					}
					case DIRECTION: {
						if (ob.set == null || ob.s == p || d <= 0.0) {
							v = Double.NaN; // Richtungen vom Punkt aus bestimmen v. a. die Orientierung
							break;
						}
						Double o = ori.get(ob.set.key);
						if (o == null) {
							o = this.orientationWithout(ob.set, p);
							ori.put(ob.set.key, o);
						}
						v = Double.isNaN(o) ? Double.NaN : wrapPi(ob.o.value + o - Math.atan2(dy, dx)) * d;
						break;
					}
					default:
						v = Double.NaN;
					}
					if (Double.isFinite(v) && Math.abs(v) <= limit)
						count += this.sessionWeight(ob);
				}
				return count;
			}

			/** Einzelbestimmungen zu Haeufungen (Radius = Grenze) zusammenfassen; Median je Haeufung, groesste zuerst (max. 6). */
			static List<double[]> clusterCandidates(List<double[]> single, double radius) {
				List<List<double[]>> clusters = new ArrayList<List<double[]>>();
				List<double[]> centers = new ArrayList<double[]>();
				for (double[] s : single) {
					int hit = -1;
					for (int c = 0; c < centers.size(); c++)
						if (Math.hypot(s[0] - centers.get(c)[0], s[1] - centers.get(c)[1]) <= radius) {
							hit = c;
							break;
						}
					if (hit < 0) {
						clusters.add(new ArrayList<double[]>());
						centers.add(new double[] { s[0], s[1] });
						hit = clusters.size() - 1;
					}
					clusters.get(hit).add(s);
				}
				List<double[]> out = new ArrayList<double[]>();
				List<Integer> order = new ArrayList<Integer>();
				for (int c = 0; c < clusters.size(); c++)
					order.add(c);
				order.sort((a, b) -> Integer.compare(clusters.get(b).size(), clusters.get(a).size()));
				for (int k = 0; k < Math.min(6, order.size()); k++) {
					List<double[]> cl = clusters.get(order.get(k));
					List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>(), zs = new ArrayList<Double>();
					for (double[] s : cl) {
						ys.add(s[0]);
						xs.add(s[1]);
						if (Double.isFinite(s[2]))
							zs.add(s[2]);
					}
					out.add(new double[] { median(ys), median(xs), zs.isEmpty() ? Double.NaN : median(zs) });
				}
				return out;
			}

			boolean repairHeights() {
				int[] total = new int[this.n], rejected = new int[this.n];
				for (Obs ob : this.obs) {
					int type = ob.o.type;
					if (type != LEVELING && type != ZENITH_ANGLE && type != GNSS1D && type != GNSS3D)
						continue;
					boolean r = this.rejectedHeight.contains(System.identityHashCode(ob.o));
					for (int p : new int[] { ob.s, ob.t }) {
						total[p]++;
						if (r)
							rejected[p]++;
					}
				}
				boolean any = false;
				for (int p = 0; p < this.n; p++)
					if (this.pts[p].isNew && this.hgt[p] && rejected[p] >= 2 && rejected[p] * 2 > total[p]) {
						this.hgt[p] = false;
						any = true;
					}
				return any;
			}

			void collectSuspicious() {
				Conflict[] stat = new Conflict[this.n];
				for (Obs ob : this.obs) {
					int id = System.identityHashCode(ob.o);
					boolean rejected = this.rejectedPosition.contains(id) || this.rejectedHeight.contains(id);
					for (int p : new int[] { ob.s, ob.t }) {
						if (stat[p] == null)
							stat[p] = new Conflict(this.pts[p].name, this.pts[p].isNew);
						stat[p].total++;
						stat[p].totalByType.merge(ob.o.type, 1, Integer::sum);
						if (rejected) {
							stat[p].rejected++;
							stat[p].rejectedByType.merge(ob.o.type, 1, Integer::sum);
						}
					}
					if (rejected)
						this.result.suspicious.add(this.pts[ob.s].name + " -> " + this.pts[ob.t].name + "|" + ob.o.type);
					if (rejected && ob.o.id >= 0 && (ob.o.type == GNSS1D || ob.o.type == GNSS2D || ob.o.type == GNSS3D))
						for (int p : new int[] { ob.s, ob.t })
							this.result.rejectedGnssIds.computeIfAbsent(this.pts[p].name, k -> new ArrayList<Integer>()).add(ob.o.id);
				}
				// Punkte, an denen sich zwei Beobachtungsgruppen widersprechen (mind. 4 und mind. 25 % verworfen)
				for (Conflict c : stat)
					if (c != null && c.rejected >= 4 && 4 * c.rejected >= c.total)
						this.result.conflicts.add(c);
				this.result.conflicts.sort((a, b) -> Integer.compare(b.rejected, a.rejected));
			}

			// ---------- 3) Lagerung ----------

			void frame() {
				for (int i : this.anchorRejected)
					this.result.frameExcluded.add(this.pts[i].name);
				for (int i : this.anchorHeightRejected)
					if (!this.result.frameExcluded.contains(this.pts[i].name))
						this.result.frameExcluded.add(this.pts[i].name + " (H)");
				List<Integer> refs = new ArrayList<Integer>();
				for (int i = 0; i < this.n; i++)
					if (this.pos[i] && !this.pts[i].isNew && this.pts[i].positionGiven())
						refs.add(i);
				this.result.frameSource = "Altpunkte";
				if (refs.size() < 2) {
					refs.clear();
					for (int i = 0; i < this.n; i++)
						if (this.pos[i] && this.pts[i].positionGiven())
							refs.add(i);
					this.result.frameSource = "vorhandene Koordinaten";
				}
				if (refs.size() >= 2) {
					List<Integer> used = new ArrayList<Integer>(refs);
					double[] h = null;
					for (int round = 0; round < refs.size(); round++) {
						h = helmert(used);
						if (h == null)
							break;
						double maxRes = -1, sumSq = 0;
						int worst = -1;
						List<Double> res = new ArrayList<Double>();
						for (int i : used) {
							double r = this.helmertResidual(h, i);
							res.add(r);
							sumSq += r * r;
							if (r > maxRes) {
								maxRes = r;
								worst = i;
							}
						}
						this.result.frameResidualMm = 1000.0 * Math.sqrt(sumSq / used.size());
						double limit = Math.max(0.02, 3.0 * 1.4826 * median(res));
						if (used.size() <= 2 || maxRes <= limit || used.size() * 2 <= refs.size())
							break;
						used.remove(Integer.valueOf(worst));
						if (!this.result.frameExcluded.contains(this.pts[worst].name))
							this.result.frameExcluded.add(this.pts[worst].name);
					}
					if (h != null) {
						this.result.framePoints = used.size();
						if (Double.isNaN(this.result.scalePpm))
							this.result.scalePpm = (Math.hypot(h[2], h[3]) - 1.0) * 1e6;
						for (int i = 0; i < this.n; i++)
							if (this.pos[i]) {
								double y = this.Y[i], x = this.X[i];
								this.Y[i] = h[0] + h[2] * y + h[3] * x;
								this.X[i] = h[1] - h[3] * y + h[2] * x;
							}
					}
				}
				else
					this.result.frameSource = "lokal";
				List<Double> shift = new ArrayList<Double>();
				for (int i = 0; i < this.n; i++)
					if (this.hgt[i] && this.heightAnchor(i))
						shift.add(this.pts[i].z0 - this.Z[i]);
				if (shift.isEmpty())
					for (int i = 0; i < this.n; i++)
						if (this.hgt[i] && this.pts[i].heightGiven() && this.pts[i].z0 != 0.0)
							shift.add(this.pts[i].z0 - this.Z[i]);
				this.result.frameHeightPoints = shift.size();
				if (!shift.isEmpty()) {
					double dz = median(shift);
					for (int i = 0; i < this.n; i++)
						if (this.hgt[i])
							this.Z[i] += dz;
				}
			}

			/** Aehnlichkeitstransformation berechnet -> Soll: Y = a + c*y + s*x, X = b - s*y + c*x. @return {a, b, c, s} */
			double[] helmert(List<Integer> refs) {
				double my = 0, mx = 0, gy = 0, gx = 0;
				for (int i : refs) {
					my += this.Y[i];
					mx += this.X[i];
					gy += this.pts[i].y0;
					gx += this.pts[i].x0;
				}
				int m = refs.size();
				my /= m;
				mx /= m;
				gy /= m;
				gx /= m;
				double a = 0, b = 0, q = 0;
				for (int i : refs) {
					double y = this.Y[i] - my, x = this.X[i] - mx, ty = this.pts[i].y0 - gy, tx = this.pts[i].x0 - gx;
					a += y * ty + x * tx;
					b += x * ty - y * tx;
					q += y * y + x * x;
				}
				if (q < 1e-6)
					return null;
				double c = a / q, s = b / q;
				return new double[] { gy - (c * my + s * mx), gx - (-s * my + c * mx), c, s };
			}

			double helmertResidual(double[] h, int i) {
				double y = h[0] + h[2] * this.Y[i] + h[3] * this.X[i];
				double x = h[1] - h[3] * this.Y[i] + h[2] * this.X[i];
				return Math.hypot(y - this.pts[i].y0, x - this.pts[i].x0);
			}

			// ---------- 4) Vergleich und Empfehlung ----------

			void compare() {
				Result r = this.result;
				for (int i = 0; i < this.n; i++) {
					Point p = this.pts[i];
					if (!p.isNew)
						continue;
					PointResult pr = new PointResult(p);
					pr.positionDetermined = p.hasPosition() && this.pos[i] && !"lokal".equals(r.frameSource);
					pr.heightDetermined = p.hasHeight() && this.hgt[i] && r.frameHeightPoints > 0;
					if (pr.positionDetermined) {
						pr.y = this.Y[i];
						pr.x = this.X[i];
						pr.dPosition = pr.hadPosition ? Math.hypot(pr.y - p.y0, pr.x - p.x0) : Double.NaN;
					}
					if (pr.heightDetermined) {
						pr.z = this.Z[i];
						pr.dHeight = pr.hadHeight ? Math.abs(pr.z - p.z0) : Double.NaN;
					}
					pr.ambiguous = this.ambiguousFlag[i];
					pr.unreliable = r.unreliable.contains(p.name);
					pr.weak = pr.positionDetermined && this.positionObservationCount(i) < 3;
					// vorhandene Naeherung in einem anderen System (z. B. UTM mit Zone oder NHN statt lokal) - immer
					// anbieten
					pr.foreignPosition = pr.positionDetermined && pr.hadPosition && pr.dPosition > FOREIGN_POSITION;
					pr.foreignHeight = pr.heightDetermined && pr.hadHeight && pr.dHeight > FOREIGN_HEIGHT;
					if (pr.foreignPosition || pr.foreignHeight)
						r.foreign.add(p.name);
					if (pr.weak)
						r.weak.add(p.name);
					if (pr.ambiguous)
						r.ambiguous.add(p.name);
					boolean posMissing = p.hasPosition() && !pr.positionDetermined;
					boolean hMissing = p.hasHeight() && !pr.heightDetermined;
					if ((posMissing || !p.hasPosition()) && (hMissing || !p.hasHeight()))
						r.notDetermined.add(p.name);
					// 0,000 (bzw. keine Naeherung) wird immer angeboten, unabhaengig von der Toleranz
					boolean diffPos = pr.positionDetermined
							&& (!pr.hadPosition || pr.foreignPosition || (this.settings.tolPosition > 0.0 && pr.dPosition > this.settings.tolPosition));
					boolean diffH = pr.heightDetermined
							&& (!pr.hadHeight || pr.foreignHeight || (this.settings.tolHeight > 0.0 && pr.dHeight > this.settings.tolHeight));
					if ((pr.zeroPosition && pr.positionDetermined) || (pr.zeroHeight && pr.heightDetermined))
						r.zeroDetermined.add(p.name);
					else if (pr.zeroPosition || pr.zeroHeight)
						r.zeroNotDetermined.add(p.name);
					// mehrdeutiger Punkt: liegt die vorhandene Koordinate auf der Spiegelseite, ist sie gleichwertig
					if (diffPos && pr.ambiguous && pr.hadPosition && this.ambiguousPartners[i] != null) {
						int a = this.ambiguousPartners[i][0], b = this.ambiguousPartners[i][1];
						double[] mirror = reflect(pr.y, pr.x, this.Y[a], this.X[a], this.Y[b], this.X[b]);
						if (Math.hypot(mirror[0] - p.y0, mirror[1] - p.x0) <= this.settings.tolPosition)
							diffPos = false;
					}
					pr.differsPosition = diffPos;
					pr.differsHeight = diffH;
					pr.differs = diffPos || diffH;
					if ((diffPos && !pr.hadPosition) || (diffH && !pr.hadHeight))
						r.missingApproximation = true;
					r.newPoints.add(pr);
					if (pr.differs)
						r.differing.add(pr);
				}
				if (r.differing.isEmpty())
					return;
				// Widersprueche beider Koordinatensaetze (Altpunkte jeweils mit ihren Sollkoordinaten)
				double[][] oldSet = this.coordinateSet(false), newSet = this.coordinateSet(true);
				double[] vOld = this.misfits(oldSet), vNew = this.misfits(newSet);
				Set<Integer> diffIdx = new java.util.HashSet<Integer>();
				for (PointResult pr : r.differing)
					diffIdx.add(this.index.get(pr.name));
				List<Double> mo = new ArrayList<Double>(), mn = new ArrayList<Double>();
				Map<Integer, List<Double>> po = new java.util.HashMap<Integer, List<Double>>(), pn = new java.util.HashMap<Integer, List<Double>>();
				Map<Integer, List<Double>> ho = new java.util.HashMap<Integer, List<Double>>(), hn = new java.util.HashMap<Integer, List<Double>>();
				for (int k = 0; k < this.obs.size(); k++) {
					Obs ob = this.obs.get(k);
					if (!diffIdx.contains(ob.s) && !diffIdx.contains(ob.t))
						continue;
					if (Double.isFinite(vOld[k])) {
						mo.add(Math.abs(vOld[k]));
						if (Math.abs(vOld[k]) > this.settings.grossLimit)
							r.grossOld++;
					}
					if (Double.isFinite(vNew[k])) {
						mn.add(Math.abs(vNew[k]));
						if (Math.abs(vNew[k]) > this.settings.grossLimit)
							r.grossNew++;
					}
					int type = ob.o.type;
					boolean heightType = type == LEVELING || type == ZENITH_ANGLE || type == GNSS1D || type == GNSS3D;
					boolean positionType = type != LEVELING && type != ZENITH_ANGLE && type != GNSS1D;
					for (int p : new int[] { ob.s, ob.t }) {
						if (!diffIdx.contains(p))
							continue;
						if (positionType) {
							if (Double.isFinite(vOld[k]))
								po.computeIfAbsent(p, q -> new ArrayList<Double>()).add(Math.abs(vOld[k]));
							if (Double.isFinite(vNew[k]))
								pn.computeIfAbsent(p, q -> new ArrayList<Double>()).add(Math.abs(vNew[k]));
						}
						if (heightType) {
							if (Double.isFinite(vOld[k]))
								ho.computeIfAbsent(p, q -> new ArrayList<Double>()).add(Math.abs(vOld[k]));
							if (Double.isFinite(vNew[k]))
								hn.computeIfAbsent(p, q -> new ArrayList<Double>()).add(Math.abs(vNew[k]));
						}
					}
				}
				r.evaluatedOld = mo.size();
				r.evaluatedNew = mn.size();
				r.medianOldMm = 1000.0 * median(mo);
				r.medianNewMm = 1000.0 * median(mn);
				for (PointResult pr : r.differing) {
					int i = this.index.get(pr.name);
					if (po.containsKey(i))
						pr.misfitOldMm = 1000.0 * median(po.get(i));
					if (pn.containsKey(i))
						pr.misfitNewMm = 1000.0 * median(pn.get(i));
					if (ho.containsKey(i))
						pr.heightMisfitOldMm = 1000.0 * median(ho.get(i));
					if (hn.containsKey(i))
						pr.heightMisfitNewMm = 1000.0 * median(hn.get(i));
					boolean noApproximation = pr.positionDetermined && !pr.hadPosition;
					// nur ersetzen, wenn die vorhandene Koordinate nachweislich schlecht passt (Median-Widerspruch ueber
					// 10 mm und mind. doppelt so gross) und der Punkt ueberbestimmt ist; ohne Naeherung immer
					pr.betterPosition = pr.differsPosition && !pr.unreliable && (noApproximation || pr.foreignPosition || (!pr.weak && Double.isFinite(pr.misfitNewMm)
							&& (!Double.isFinite(pr.misfitOldMm) || (pr.misfitOldMm > 10.0 && pr.misfitNewMm < 0.5 * pr.misfitOldMm))));
					boolean noHeight = !pr.hadHeight && pr.heightDetermined;
					pr.betterHeight = pr.differsHeight && !pr.unreliable && (noHeight || pr.foreignHeight || (Double.isFinite(pr.heightMisfitNewMm)
							&& (!Double.isFinite(pr.heightMisfitOldMm) || (pr.heightMisfitOldMm > 10.0 && pr.heightMisfitNewMm < 0.5 * pr.heightMisfitOldMm))));
					if (pr.adopted())
						r.betterCount++;
				}
				r.recommendNew = r.betterCount > 0 && (r.missingApproximation || !r.foreign.isEmpty() || r.grossNew <= r.grossOld);
			}

			/** Nicht verworfene Lage-Beobachtungen am Punkt (Richtungen, Strecken, GNSS-Komponenten). */
			int positionObservationCount(int p) {
				int c = 0;
				for (Obs ob : this.obs) {
					if (ob.s != p && ob.t != p)
						continue;
					int type = ob.o.type;
					if (type != DIRECTION && type != HORIZONTAL_DISTANCE && type != SLOPE_DISTANCE && type != GNSS2D && type != GNSS3D)
						continue;
					if (this.rejectedPosition.contains(System.identityHashCode(ob.o)))
						continue;
					if (type == DIRECTION && ob.s == p)
						continue; // Richtung vom Punkt aus bestimmt v. a. die Orientierung
					c += type == GNSS2D || type == GNSS3D ? 2 : 1;
				}
				return c;
			}

			/** {Y, X, Z} je Punkt: Altpunkte Soll, Neupunkte vorhanden (false) bzw. neu (true); NaN = keine Koordinate. */
			double[][] coordinateSet(boolean computed) {
				double[] y = new double[this.n], x = new double[this.n], z = new double[this.n];
				Map<String, PointResult> byName = new java.util.HashMap<String, PointResult>();
				for (PointResult pr : this.result.newPoints)
					byName.put(pr.name, pr);
				for (int i = 0; i < this.n; i++) {
					Point p = this.pts[i];
					y[i] = x[i] = z[i] = Double.NaN;
					PointResult pr = byName.get(p.name);
					if (p.isNew && computed && pr != null) {
						if (pr.positionDetermined) {
							y[i] = pr.y;
							x[i] = pr.x;
						}
						else if (p.positionGiven()) {
							y[i] = p.y0;
							x[i] = p.x0;
						}
						if (pr.heightDetermined)
							z[i] = pr.z;
						else if (p.heightGiven())
							z[i] = p.z0;
						continue;
					}
					if (p.positionGiven()) {
						y[i] = p.y0;
						x[i] = p.x0;
					}
					if (p.heightGiven())
						z[i] = p.z0;
				}
				return new double[][] { y, x, z };
			}

			/** Linearer Widerspruch je Beobachtung [m] zu einem Koordinatensatz (NaN = nicht auswertbar). */
			double[] misfits(double[][] c) {
				double[] y = c[0], x = c[1], z = c[2];
				double[] v = new double[this.obs.size()];
				java.util.Arrays.fill(v, Double.NaN);
				// Orientierung je Satz aus diesem Koordinatensatz
				Map<String, Double> orient = new java.util.HashMap<String, Double>();
				for (DirSet set : this.sets.values()) {
					List<Double> diffs = new ArrayList<Double>();
					for (Map.Entry<Integer, Double> e : set.reading.entrySet()) {
						int s = set.station, t = e.getKey();
						if (Double.isNaN(y[s]) || Double.isNaN(y[t]))
							continue;
						diffs.add(wrap2Pi(Math.atan2(y[t] - y[s], x[t] - x[s]) - e.getValue()));
					}
					if (!diffs.isEmpty())
						orient.put(set.key, orientation(diffs));
				}
				// gemeinsamer Massstab der Strecken (Projektion) robust
				List<Double> ratios = new ArrayList<Double>();
				for (Obs ob : this.obs) {
					if (ob.o.type != HORIZONTAL_DISTANCE || Double.isNaN(y[ob.s]) || Double.isNaN(y[ob.t]) || !(ob.o.value > 0.0))
						continue;
					ratios.add(Math.hypot(y[ob.t] - y[ob.s], x[ob.t] - x[ob.s]) / ob.o.value);
				}
				for (Obs ob : this.obs) {
					if (ob.o.type != SLOPE_DISTANCE || Double.isNaN(y[ob.s]) || Double.isNaN(y[ob.t]) || Double.isNaN(z[ob.s]) || Double.isNaN(z[ob.t])
							|| !(ob.o.value > 0.0))
						continue;
					double dh = (z[ob.t] + ob.o.th) - (z[ob.s] + ob.o.ih);
					ratios.add(Math.sqrt(Math.pow(Math.hypot(y[ob.t] - y[ob.s], x[ob.t] - x[ob.s]), 2) + dh * dh) / ob.o.value);
				}
				double k = ratios.size() >= 3 ? median(ratios) : 1.0;
				if (!(Math.abs(k - 1.0) < 0.001))
					k = 1.0;
				for (int i = 0; i < this.obs.size(); i++) {
					Obs ob = this.obs.get(i);
					int s = ob.s, t = ob.t;
					boolean p2 = !Double.isNaN(y[s]) && !Double.isNaN(y[t]);
					boolean h2 = !Double.isNaN(z[s]) && !Double.isNaN(z[t]);
					double dy = p2 ? y[t] - y[s] : Double.NaN, dx = p2 ? x[t] - x[s] : Double.NaN;
					double d = p2 ? Math.hypot(dy, dx) : Double.NaN;
					Observation o = ob.o;
					switch (o.type) {
					case DIRECTION: {
						Double or = ob.set == null ? null : orient.get(ob.set.key);
						if (p2 && or != null && d > 0.0)
							v[i] = wrapPi(o.value + or - Math.atan2(dy, dx)) * d;
						break;
					}
					case HORIZONTAL_DISTANCE:
						if (p2)
							v[i] = k * o.value - d;
						break;
					case SLOPE_DISTANCE:
						if (p2 && h2) {
							double dh = (z[t] + o.th) - (z[s] + o.ih);
							v[i] = k * o.value - Math.sqrt(d * d + dh * dh);
						}
						else if (p2) {
							double ze = this.zenithFor(s, t);
							if (Double.isFinite(ze))
								v[i] = k * o.value * Math.sin(ze) - d;
						}
						break;
					case ZENITH_ANGLE:
						if (p2 && h2 && d > 0.0) {
							double ze = o.value > Math.PI ? TWO_PI - o.value : o.value;
							double dh = (z[t] + o.th) - (z[s] + o.ih) - this.frameCorrection(s, t) - this.curvature(d);
							v[i] = (ze - Math.atan2(d, dh)) * Math.hypot(d, dh);
						}
						break;
					case LEVELING:
						if (h2)
							v[i] = (z[s] + o.value + o.ih - o.th + this.frameCorrection(s, t)) - z[t];
						break;
					case GNSS1D:
						if (h2)
							v[i] = o.gz - (z[t] - z[s]);
						break;
					case GNSS2D:
						if (p2)
							v[i] = Math.hypot(o.gy - dy, o.gx - dx);
						break;
					case GNSS3D:
						if (p2 && h2)
							v[i] = Math.sqrt(Math.pow(o.gy - dy, 2) + Math.pow(o.gx - dx, 2) + Math.pow(o.gz - (z[t] - z[s]), 2));
						else if (p2)
							v[i] = Math.hypot(o.gy - dy, o.gx - dx);
						break;
					default:
						break;
					}
				}
				return v;
			}
		}
	}

	/**
	 * Kongruenz der Altpunkte - Lage aus der freien Ausgleichung gegen ihre Sollkoordinaten (a priori) per ebener
	 * 4-Parameter-Helmert-Transformation, robust (groesster Rest wird schrittweise ausgeschlossen). Liefert Massstab,
	 * Drehung, Rest je Punkt, nicht passende feste Punkte und Freigabe-Vorschlaege. Reine Rechnung.
	 */
	public static final class Klein_Method_Congruence {
		public static final double SCALE_HINT_PPM = 10.0;
		public static final double FIXED_MISFIT_MM = 3.0;
		public static final double RELEASE_MIN_MM = 5.0;
		public static final double RELEASE_FACTOR = 3.0;

		private Klein_Method_Congruence() {
		}

		public static final class Result {
			public boolean available = false;
			public int points = 0, usedPoints = 0;
			public double scalePpm = Double.NaN, rotationMgon = Double.NaN, medianResidualMm = Double.NaN;
			public final java.util.Map<String, Double> residualMm = new java.util.LinkedHashMap<String, Double>();
			public final List<String> fixedMisfit = new ArrayList<String>(), release = new ArrayList<String>(), excluded = new ArrayList<String>();

			public String staleProjection = null;

			public boolean scaleIssue() {
				return this.available && this.staleProjection == null && Math.abs(this.scalePpm) > SCALE_HINT_PPM;
			}
		}

		/**
		 * @param apriori Name -> {y, x} Sollkoordinaten der Altpunkte
		 * @param free    Name -> {y, x} Lage aus der freien Ausgleichung
		 * @param fixed   Namen fester Punkte (Festpunkt oder sigma ~ 0)
		 * @param sigmaMm Name -> individuelle Unsicherheit [mm] (fuer die Freigabe), darf fehlen
		 */
		public static Result evaluate(java.util.Map<String, double[]> apriori, java.util.Map<String, double[]> free, Set<String> fixed,
				java.util.Map<String, Double> sigmaMm) {
			Result result = new Result();
			List<String> names = new ArrayList<String>();
			for (String name : apriori.keySet())
				if (free.containsKey(name))
					names.add(name);
			result.points = names.size();
			if (names.size() < 3)
				return result;
			List<String> used = new ArrayList<String>(names);
			double[] params = null;
			java.util.Map<String, Double> residuals = null;
			for (int round = 0; round < names.size(); round++) {
				params = fit(apriori, free, used);
				residuals = residuals(apriori, free, names, params);
				List<Double> usedResiduals = new ArrayList<Double>();
				for (String name : used)
					usedResiduals.add(residuals.get(name));
				double median = Klein_Method_Swarm.median(usedResiduals);
				String worst = null;
				double worstValue = 0.0;
				for (String name : used)
					if (residuals.get(name) > worstValue) {
						worstValue = residuals.get(name);
						worst = name;
					}
				if (used.size() <= 3 || worst == null || worstValue <= Math.max(RELEASE_MIN_MM, RELEASE_FACTOR * median))
					break;
				used.remove(worst);
				result.excluded.add(worst);
			}
			result.available = true;
			result.usedPoints = used.size();
			result.scalePpm = (Math.hypot(params[0], params[1]) - 1.0) * 1.0e6;
			result.rotationMgon = Math.atan2(params[1], params[0]) * 200.0 / Math.PI * 1000.0;
			List<Double> usedResiduals = new ArrayList<Double>();
			for (String name : used)
				usedResiduals.add(residuals.get(name));
			result.medianResidualMm = Klein_Method_Swarm.median(usedResiduals);
			result.residualMm.putAll(residuals);
			for (String name : names) {
				double r = residuals.get(name);
				if (fixed != null && fixed.contains(name)) {
					if (r > FIXED_MISFIT_MM)
						result.fixedMisfit.add(name);
					continue;
				}
				Double sigma = sigmaMm == null ? null : sigmaMm.get(name);
				double threshold = Math.max(RELEASE_MIN_MM, RELEASE_FACTOR * (sigma == null || Double.isNaN(sigma) ? 0.0 : Math.min(sigma, 3.0)));
				if (result.excluded.contains(name) || r > threshold)
					result.release.add(name);
			}
			return result;
		}

		/** Aehnlichkeitstransformation free -> apriori: {a, b, tx, ty} mit y' = a*y - b*x + tx, x' = b*y + a*x + ty (zentriert). */
		private static double[] fit(java.util.Map<String, double[]> apriori, java.util.Map<String, double[]> free, List<String> names) {
			double my = 0, mx = 0, ny = 0, nx = 0;
			for (String name : names) {
				my += free.get(name)[0];
				mx += free.get(name)[1];
				ny += apriori.get(name)[0];
				nx += apriori.get(name)[1];
			}
			int n = names.size();
			my /= n;
			mx /= n;
			ny /= n;
			nx /= n;
			double s1 = 0, s2 = 0, s3 = 0;
			for (String name : names) {
				double y = free.get(name)[0] - my, x = free.get(name)[1] - mx;
				double u = apriori.get(name)[0] - ny, v = apriori.get(name)[1] - nx;
				s1 += y * u + x * v;
				s2 += y * v - x * u;
				s3 += y * y + x * x;
			}
			return new double[] { s1 / s3, s2 / s3, my, mx, ny, nx };
		}

		private static java.util.Map<String, Double> residuals(java.util.Map<String, double[]> apriori, java.util.Map<String, double[]> free, List<String> names,
				double[] p) {
			java.util.Map<String, Double> result = new java.util.LinkedHashMap<String, Double>();
			for (String name : names) {
				double y = free.get(name)[0] - p[2], x = free.get(name)[1] - p[3];
				double ty = p[4] + p[0] * y - p[1] * x, tx = p[5] + p[1] * y + p[0] * x;
				result.put(name, Math.hypot(apriori.get(name)[0] - ty, apriori.get(name)[1] - tx) * 1000.0);
			}
			return result;
		}

		/** Hinweise (Oberflaechensprache) fuer Dialoge und Berichte. */
		public static List<String> messages(Result r) {
			List<String> list = new ArrayList<String>();
			if (r == null || !r.available)
				return list;
			if (r.scaleIssue())
				list.add(String.format(tr("Ma\u00DFstab zwischen freier Ausgleichung und Sollkoordinaten der Altpunkte: %s ppm (Drehung %s mgon). "
						+ "Projektionsreduktion in JAG3D pr\u00FCfen oder \u201EMa\u00DFstab berechnen\u201C einschalten."),
						String.format(Locale.GERMANY, "%+.1f", r.scalePpm), String.format(Locale.GERMANY, "%.2f", r.rotationMgon)));
			for (String name : r.fixedMisfit)
				list.add(String.format(tr("Fester Anschlusspunkt %s passt %s mm nicht zur Lagerung aus den \u00FCbrigen Altpunkten - nicht als fest verwenden."),
						name, String.format(Locale.GERMANY, "%.1f", r.residualMm.get(name))));
			if (!r.release.isEmpty()) {
				StringBuilder names = new StringBuilder();
				for (String name : r.release)
					names.append(names.length() == 0 ? "" : ", ").append(name).append(" (").append(String.format(Locale.GERMANY, "%.1f", r.residualMm.get(name))).append(" mm)");
				list.add(String.format(tr("Diese Altpunkte passen nicht zur Lagerung aus den \u00FCbrigen Altpunkten und sollten besser als Neupunkt gerechnet werden: %s"),
						names.toString()));
			}
			return list;
		}
	}

	private static final String[] SWARM_COMPONENT_NAMES = { "a", "b", "c" };

	/**
	 * Qualitaet der Gruppenkalibrierung: VKS-Band nach Redundanz, zu kleine Redundanz, Plausibilitaet gegen
	 * Datenblattwerte, robuste VKS (Ausreisser) und Modellpruefung a/c (Strecke). Nur Rechnung und Texte.
	 */
	public static final class Klein_Method_Calibration_Quality {
		/** Median von chi^2_k / k fuer k = 1, 2, 3 (Erwartungswert-Korrektur des Medians). */
		private static final double[] CHI2_MEDIAN_PER_DOF = { 0.0, 0.454936, 0.693147, 0.788700 };

		private Klein_Method_Calibration_Quality() {
		}

		/** Band [min, max], unterhalb der Redundanz R um sqrt(R/r) aufgeweitet, begrenzt auf 0,900-1,100. */
		public static double[] effectiveBand(double bandMin, double bandMax, double redundancy, double fullBandRedundancy) {
			if (!(fullBandRedundancy > 0.0) || Double.isNaN(redundancy) || !(redundancy > 0.0) || redundancy >= fullBandRedundancy)
				return new double[] { bandMin, bandMax };
			double w = Math.sqrt(fullBandRedundancy / redundancy);
			double min = Math.max(Klein_Method_Default_Values.VKS_BAND_MIN_LOWER_LIMIT, 1.0 - (1.0 - bandMin) * w);
			double max = Math.min(Klein_Method_Default_Values.VKS_BAND_MAX_UPPER_LIMIT, 1.0 + (bandMax - 1.0) * w);
			return new double[] { Math.min(min, bandMin), Math.max(max, bandMax) };
		}

		/** Protokoll-Zusatz einer Komponentenzeile (deutsch). */
		static String qualitySuffix(boolean tooSmall, double[] band, double bandMin, double bandMax, double[][] redundancies, int k) {
			double r = redundancies == null || redundancies[k] == null ? Double.NaN : redundancies[k][0];
			if (tooSmall)
				return " | Redundanz " + Klein_Method_Format.format(r, "", 1) + " zu klein - nicht kalibriert";
			if (band[0] != bandMin || band[1] != bandMax)
				return " | Band nach Redundanz " + Klein_Method_Format.format(r, "", 1) + ": " + Klein_Method_Format.format(band[0], "", 3) + "-"
						+ Klein_Method_Format.format(band[1], "", 3);
			return "";
		}

		/** {mittlere VKS = Summe omega / Summe r, robuste VKS = Median(omega/r)/Median(chi^2_k/k), n}. */
		public static double[] robustVarianceFactor(List<double[]> rows) {
			double sumOmega = 0.0, sumR = 0.0;
			List<Double> normalized = new ArrayList<Double>();
			for (double[] row : rows) {
				if (row == null || !(row[1] > 0.0))
					continue;
				sumOmega += row[0];
				sumR += row[1];
				int k = (int) Math.max(1, Math.min(3, Math.round(row[3])));
				double perDof = row[0] / row[1];
				if (row[1] / k >= 0.05)
					normalized.add(perDof / CHI2_MEDIAN_PER_DOF[k]);
			}
			double mean = sumR > 0.0 ? sumOmega / sumR : Double.NaN;
			double robust = normalized.isEmpty() ? Double.NaN : Klein_Method_Swarm.median(normalized);
			return new double[] { mean, robust, normalized.size() };
		}

		/** Punkt 5: mittlere VKS deutlich groesser als robuste -> Ausreisser vermutet (Hinweis, Protokoll, Oberflaeche). */
		static void checkRobustness(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler, Klein_Method_Threshold_Type type,
				List<String> outAdjustmentDetails) throws SQLException {
			double limit = parameter.getRobustRatioLimit();
			if (!(limit > 0.0))
				return;
			List<double[]> rows = databaseHandler.readObservationFitData(type);
			if (rows == null)
				return;
			double[] vks = robustVarianceFactor(rows);
			if (vks[2] < Klein_Method_Default_Values.QUALITY_MIN_OBSERVATIONS || !(vks[1] > 0.0))
				return;
			double ratio = vks[0] / vks[1];
			if (ratio <= limit)
				return;
			String key = type.name() + "|robust";
			String german = "VKS " + Klein_Method_Format.format(vks[0], "", 3) + " gegen robust " + Klein_Method_Format.format(vks[1], "", 3)
					+ " (Faktor " + Klein_Method_Format.format(ratio, "", 2) + ") - Ausreisser vermutet";
			boolean first = parameter.getCalibrationNotes().put(key, german) == null;
			if (outAdjustmentDetails != null)
				outAdjustmentDetails.add("ROBUSTE VKS " + type.getDisplayName() + ": " + german);
			if (first)
				parameter.getCalibrationWarnings().add(String.format(tr("%s: mittlere VKS %s, robuste VKS %s (Faktor %s). "
						+ "Vermutlich steckt ein unentdeckter grober Fehler in diesem Beobachtungstyp - die Gruppe wird sonst unn\u00F6tig aufgebl\u00E4ht. "
						+ "Bitte \u201EMax Fehler\u201C in der Karte bzw. die Bereinigung grober Fehler pr\u00FCfen."),
						tr(type.getDisplayName()).replace(" logPost", ""), Klein_Method_Format.format(vks[0], "", 3), Klein_Method_Format.format(vks[1], "", 3),
						Klein_Method_Format.format(ratio, "", 2)));
		}

		/**
		 * Punkt 3: Modellpruefung fuer terrestrische Typen. Die Aufteilung a/b/c schaetzt JAG3D
		 * bereits je Komponente (eigene VKS). Geprueft wird nur, ob eine ABGESCHALTETE Komponente
		 * fehlt: normierte Verbesserungen im fernen Streckendrittel deutlich groesser als im nahen
		 * (c fehlt) bzw. umgekehrt (a fehlt). Rueckgabe Hinweis (Oberflaechensprache) oder null.
		 */
		public static String modelCheck(Klein_Method_Threshold_Type type, List<double[]> rows, boolean aDisabled, boolean cDisabled) {
			if (rows == null || (!aDisabled && !cDisabled))
				return null;
			List<double[]> usable = new ArrayList<double[]>();
			for (double[] row : rows)
				if (row[1] >= 0.05 && row[2] > 0.0 && !Double.isNaN(row[2]))
					usable.add(row);
			if (usable.size() < 20)
				return null;
			usable.sort((x, y) -> Double.compare(x[2], y[2]));
			int third = usable.size() / 3;
			double near = 0.0, far = 0.0;
			for (int i = 0; i < third; i++) {
				near += usable.get(i)[0] / usable.get(i)[1];
				far += usable.get(usable.size() - 1 - i)[0] / usable.get(usable.size() - 1 - i)[1];
			}
			near /= third;
			far /= third;
			String name = tr(type.getDisplayName()).replace(" logPost", "");
			if (cDisabled && near > 0.0 && far / near > 2.0)
				return String.format(tr("%s: Die normierten Verbesserungen sind bei langen Strecken %s-mal so gro\u00DF wie bei kurzen. "
						+ "Der entfernungsabh\u00E4ngige Anteil (sigma_c) ist abgeschaltet - pr\u00FCfen, ob er aktiviert werden sollte."),
						name, Klein_Method_Format.format(far / near, "", 1));
			if (aDisabled && far > 0.0 && near / far > 2.0)
				return String.format(tr("%s: Die normierten Verbesserungen sind bei kurzen Strecken %s-mal so gro\u00DF wie bei langen. "
						+ "Der konstante Anteil (sigma_a) ist abgeschaltet - pr\u00FCfen, ob er aktiviert werden sollte."),
						name, Klein_Method_Format.format(near / far, "", 1));
			return null;
		}

		/** Punkt 4: Hinweis (Oberflaechensprache), wenn der Wert mehr als Faktor x Datenblattwert ist, sonst null. */
		public static String plausibility(Klein_Method_Threshold_Type type, int component, double value, String unit, double factor,
				Klein_Method_Device_Class deviceClass) {
			double typical = Klein_Method_Default_Values.getTypicalDatasheetValue(type, component, deviceClass);
			if (!(factor > 0.0) || !(typical > 0.0) || !(value > factor * typical))
				return null;
			return String.format(tr("%s sigma_%s = %s ist %s-mal so gro\u00DF wie ein typischer Datenblattwert (%s). "
					+ "Bitte Daten, Gruppeneinteilung und grobe Fehler pr\u00FCfen."),
					tr(type.getDisplayName()).replace(" logPost", ""), SWARM_COMPONENT_NAMES[component], Klein_Method_Format.format(value, unit, 2),
					Klein_Method_Format.format(value / typical, "", 1), Klein_Method_Format.format(typical, unit, 2));
		}
	}

	/**
	 * Festpunkt-Empfehlung der dynamischen Ausgleichung. Bewertung je beweglichem Anschlusspunkt nach seiner EINEN
	 * kalibrierten individuellen Unsicherheit (y0 = x0 = z0) in Ampelfarben: 1D (Nivellement) gegen die
	 * Nivellier-Grenzen (Standard 0,5 / 1,0 mm), 2D/3D gegen die Tachymeter-Grenzen (Standard 3 / 5 mm). Nivellierte
	 * 3D-Punkte erhalten nur einen Hinweis (eine Zahl fuer Lage und Hoehe). Danach Pruefung der Verteilung der Anker
	 * (gruene Punkte + echte Festpunkte): langgestreckte Netze (Laenge/Breite >= 3) entlang einer Achse (laengster
	 * Weg im minimalen Spannbaum, vereinfacht) - Luecken, Enden, keine Neupunkte ausserhalb; flaechige Netze - alle
	 * Neupunkte innerhalb der Huelle der Anker. Reine Rechnung, keine Datenbank, keine Oberflaeche (testbar).
	 */
	public static final class Klein_Method_Fixed_Point_Recommendation {
		public static final int KIND_NEW = 0, KIND_STOCHASTIC = 1, KIND_FIXED = 2;
		public static final double ELONGATION_RATIO = 3.0;
		/**
		 * Altpunkte naeher als dieser Anteil der Netzlaenge (hoechstens halber mittlerer Abstand) bilden einen
		 * Standort.
		 */
		public static final double CLUSTER_TOLERANCE_SHARE = 0.02;
		public static final double DEFAULT_TACHY_GREEN = 3.0, DEFAULT_TACHY_YELLOW = 5.0;
		public static final double DEFAULT_LEVEL_GREEN = 0.5, DEFAULT_LEVEL_YELLOW = 1.0;
		/** Stuetzpunkte in der Mitte (Lage y/x fuer 2D/3D, Hoehe z fuer 1D). */
		public static final double DEFAULT_MID_POSITION_GREEN = 5.0, DEFAULT_MID_POSITION_YELLOW = 10.0;
		public static final double DEFAULT_MID_HEIGHT_GREEN = 0.5, DEFAULT_MID_HEIGHT_YELLOW = 1.0;
		public static final int DEFAULT_END_COUNT = 3;

		public enum Rating {
			GREEN, YELLOW, RED, FIXED, NO_VALUE
		}

		private Klein_Method_Fixed_Point_Recommendation() {
		}

		/** Ein Punkt des Netzes (Koordinaten in m, NaN = fehlt; sigma in mm, NaN = fehlt). */
		public static final class Point {
			public final int id, kind, dimension;
			public final String name;
			public final double y, x, z, sigmaMm;
			public final boolean leveled;
			// Ergebnis
			public Rating rating = Rating.NO_VALUE;
			public boolean levelingLimit = false, leveled3dHint = false, outside = false;
			/** true = Stuetzpunkt in der Mitte, false = Randpunkt (Endbereich, Umrandung, Anschlussbereich). */
			public boolean middle = false;
			/** Ursache bei gelb/rot bzw. Freigabe-Vorschlag (Oberflaechensprache), sonst null. */
			public String cause = null;
			public boolean releaseSuggested = false;
			public double green = Double.NaN, yellow = Double.NaN, station = Double.NaN;
			double[] planar;

			public Point(int id, String name, int kind, int dimension, double y, double x, double z, double sigmaMm, boolean leveled) {
				this.id = id;
				this.name = name;
				this.kind = kind;
				this.dimension = dimension;
				this.y = y;
				this.x = x;
				this.z = z;
				this.sigmaMm = sigmaMm;
				this.leveled = leveled;
			}

			public boolean isAnchor() {
				return this.rating == Rating.GREEN || this.rating == Rating.FIXED;
			}
		}

		public static final class Settings {
			public double tachyGreen = DEFAULT_TACHY_GREEN, tachyYellow = DEFAULT_TACHY_YELLOW;
			public double levelGreen = DEFAULT_LEVEL_GREEN, levelYellow = DEFAULT_LEVEL_YELLOW;
			public double midPositionGreen = DEFAULT_MID_POSITION_GREEN, midPositionYellow = DEFAULT_MID_POSITION_YELLOW;
			public double midHeightGreen = DEFAULT_MID_HEIGHT_GREEN, midHeightYellow = DEFAULT_MID_HEIGHT_YELLOW;
			/** max. Luecke zwischen Festpunkten [m]; <= 0 = automatisch (2 x ueblicher Abstand). */
			public double maxGapM = 0.0;
			public int endCount = DEFAULT_END_COUNT;
			/** in der Karte markierte Anschlussbereiche (leer = keine). */
			public final List<Area> areas = new ArrayList<Area>();
			/** Kongruenz der Altpunkte gegen die freie Ausgleichung (null = nicht verfuegbar). */
			public Klein_Method_Congruence.Result congruence = null;
		}

		/**
		 * Anschlussbereich aus der Karte - Kreis, Viereck oder Polygon in geografischen Koordinaten (WGS84, Grad) und
		 * die Namen der Punkte darin (von der Karte bei jedem Laden und jeder Aenderung neu bestimmt).
		 */
		public static final class Area {
			public static final String CIRCLE = "CIRCLE", RECTANGLE = "RECTANGLE", POLYGON = "POLYGON";
			public final int id;
			public final String name, shape;
			/** Kreis: {Mitte} und radiusM; sonst Ecken {Breite, Laenge}. */
			public final List<double[]> latLon = new ArrayList<double[]>();
			public double radiusM = 0.0;
			public final List<String> pointNames = new ArrayList<String>();

			public Area(int id, String name, String shape) {
				this.id = id;
				this.name = name;
				this.shape = shape;
			}

			/** Geometrie als Text: "lat,lon;lat,lon;..." bzw. Kreis "lat,lon;r=radius". */
			public String geometryText() {
				StringBuilder text = new StringBuilder();
				for (double[] ll : this.latLon) {
					if (text.length() > 0)
						text.append(';');
					text.append(String.format(Locale.ROOT, "%.9f,%.9f", ll[0], ll[1]));
				}
				if (CIRCLE.equals(this.shape))
					text.append(String.format(Locale.ROOT, ";r=%.3f", this.radiusM));
				return text.toString();
			}

			public void parseGeometry(String text) {
				this.latLon.clear();
				if (text == null)
					return;
				for (String part : text.split(";")) {
					part = part.trim();
					if (part.startsWith("r=")) {
						this.radiusM = Double.parseDouble(part.substring(2));
						continue;
					}
					String[] ll = part.split(",");
					if (ll.length == 2)
						this.latLon.add(new double[] { Double.parseDouble(ll[0]), Double.parseDouble(ll[1]) });
				}
			}

			/** Liegt die Stelle (Breite, Laenge) im Bereich? Ebene Naeherung um den ersten Punkt (Bereiche sind klein). */
			public boolean contains(double lat, double lon) {
				if (this.latLon.isEmpty())
					return false;
				double lat0 = this.latLon.get(0)[0], lon0 = this.latLon.get(0)[1];
				double[] p = local(lat, lon, lat0, lon0);
				if (CIRCLE.equals(this.shape))
					return Math.hypot(p[0], p[1]) <= this.radiusM;
				int n = this.latLon.size();
				if (n < 3)
					return false;
				boolean inside = false;
				for (int i = 0, j = n - 1; i < n; j = i++) {
					double[] a = local(this.latLon.get(i)[0], this.latLon.get(i)[1], lat0, lon0);
					double[] b = local(this.latLon.get(j)[0], this.latLon.get(j)[1], lat0, lon0);
					if ((a[1] > p[1]) != (b[1] > p[1]) && p[0] < (b[0] - a[0]) * (p[1] - a[1]) / (b[1] - a[1]) + a[0])
						inside = !inside;
				}
				return inside;
			}

			private static double[] local(double lat, double lon, double lat0, double lon0) {
				double r = 6371000.0;
				return new double[] { Math.toRadians(lon - lon0) * r * Math.cos(Math.toRadians(lat0)), Math.toRadians(lat - lat0) * r };
			}
		}

		public static final class Result {
			public final List<Point> points = new ArrayList<Point>();
			public boolean geometryAvailable = false, elongated = false, recommended = false, maxGapAuto = true;
			public double lengthM = Double.NaN, widthM = Double.NaN, ratio = Double.NaN, typicalSpacingM = Double.NaN;
			/** ueblicher Abstand der Altpunkt-Standorte (Median, m) und von ihnen ueberdeckte Flaeche (m2). */
			public double anchorSpacingM = Double.NaN, anchorAreaM2 = 0.0;
			/** Anzahl Punkte mit Lage, die in die Netzform eingehen. */
			public int shapePoints = 0;
			public double maxGapM = Double.NaN, largestGapM = Double.NaN;
			public double stationStart = Double.NaN, stationEnd = Double.NaN;
			public int edgePoints = 0, middlePoints = 0;
			public int oldPointSites = 0;
			public int endCountA = 0, endCountB = 0, green = 0, yellow = 0, red = 0, fixed = 0, noValue = 0, newPoints = 0;
			/** Achse (langgestreckt) bzw. Huelle der Anker (flaechig) als Punktnamen in Reihenfolge. */
			public final List<String> axisNames = new ArrayList<String>(), hullNames = new ArrayList<String>();
			public final List<String> outsideNewNames = new ArrayList<String>();
			/** Gruende gegen die Empfehlung (Oberflaechensprache) und Hinweise. */
			public final List<String> reasons = new ArrayList<String>(), hints = new ArrayList<String>();
			/** je Anschlussbereich "Name: gruen/gefordert". */
			public final List<String> areaLines = new ArrayList<String>();

			public List<Point> greenStochasticPoints() {
				List<Point> list = new ArrayList<Point>();
				for (Point p : this.points)
					if (p.kind == KIND_STOCHASTIC && p.rating == Rating.GREEN)
						list.add(p);
				return list;
			}
		}

		private static String fmt(double value, int decimals) {
			return String.format(Locale.GERMANY, "%." + decimals + "f", value);
		}

		/** Laenge in m bzw. km, gut lesbar. */
		public static String formatLength(double meter) {
			if (Double.isNaN(meter))
				return "-";
			if (Math.abs(meter) < 0.5)
				return "0 m";
			return meter >= 10000.0 ? fmt(meter / 1000.0, 2) + " km" : fmt(meter, 0) + " m";
		}

		/** Bewertung und Verteilungspruefung. */
		public static Result evaluate(List<Point> input, Settings settings) {
			Result result = new Result();
			result.points.addAll(input);
			List<Point> geo = planarPoints(result.points);
			double[][] pts = null;
			double spacingAll = 0.0;
			if (geo.size() >= 3) {
				pts = new double[geo.size()][];
				for (int i = 0; i < pts.length; i++)
					pts[i] = geo.get(i).planar;
				spacingAll = medianNearestNeighbour(pts);
				double diameter = Klein_Method_Geo.maxExtent(pts);
				if (diameter > 0.0 && spacingAll > 0.0) {
					result.geometryAvailable = true;
					double cell = Math.max(2.0 * spacingAll, diameter / 500.0);
					double cellArea = occupiedCells(pts, cell) * cell * cell;
					// bei wenigen Punkten (z. B. Neupunkte ohne Naeherung) machen die groben Zellen aus einer Linie
					// ein breites Band ("flaechig"). Die konvexe Huelle ist dann die bessere Flaechenschaetzung; bei
					// dichten, gekruemmten Zuegen bleibt die Zellflaeche die kleinere.
					double hullArea = hullArea(pts);
					// die Rasterweite folgt dem dichtesten Teil des Netzes. Liegt ein dichtes Messgebiet zwischen
					// weit verteilten Altpunkten (z. B. 8 GNSS-Festpunkte ueber 85 km, Messgebiet 700 m), belegen die
					// Punkte nur wenige Zellen -> Flaeche winzig, "langgestreckt, Breite 14 m". Deshalb zusaetzlich
					// die Flaeche, die die Altpunkte ueberdecken: je Standort ein Kreis mit dem halben Abstand zum
					// naechsten Standort (Standorte: Altpunkte naeher als 2 % der Netzausdehnung zusammengefasst).
					// Ein Linienzug mit n Standorten ergibt so etwa 4n/pi: 1 und bleibt ab 3 Standorten
					// langgestreckt; die Huelle begrenzt.
					double siteTolerance = Math.max(1.0, CLUSTER_TOLERANCE_SHARE * diameter);
					List<double[]> sites = new ArrayList<double[]>();
					for (Point p : geo) {
						if (p.kind == KIND_NEW)
							continue;
						boolean merged = false;
						for (double[] s : sites)
							if (Math.hypot(s[0] - p.planar[0], s[1] - p.planar[1]) <= siteTolerance) {
								merged = true;
								break;
							}
						if (!merged)
							sites.add(p.planar);
					}
					double anchorArea = 0.0;
					if (sites.size() >= 3) {
						for (double[] s : sites) {
							double nearest = Double.POSITIVE_INFINITY;
							for (double[] o : sites)
								if (o != s)
									nearest = Math.min(nearest, Math.hypot(s[0] - o[0], s[1] - o[1]));
							anchorArea += Math.PI * 0.25 * nearest * nearest;
						}
						result.anchorSpacingM = medianNearestNeighbour(sites.toArray(new double[0][]));
					}
					result.anchorAreaM2 = anchorArea;
					double area = Math.max(Math.min(Math.max(cellArea, anchorArea), hullArea), 1e-6 * diameter * diameter);
					result.shapePoints = pts.length;
					result.widthM = area / diameter;
					result.ratio = diameter * diameter / area;
					result.lengthM = diameter;
					result.elongated = result.ratio >= ELONGATION_RATIO;
					if (result.elongated)
						prepareElongated(result, geo, pts, settings, cell);
				}
			}
			assignZones(result, geo, spacingAll, settings);
			rate(result, settings);
			if (result.geometryAvailable) {
				if (result.elongated)
					checkElongated(result, geo, settings);
				else
					checkFlat(result, geo, pts, spacingAll);
			}
			checkAreas(result, settings);
			applyCongruence(result, settings.congruence);
			if (!result.geometryAvailable)
				result.hints.add(tr("Keine auswertbaren Lagekoordinaten - die Verteilung der Festpunkte konnte nicht geprüft werden. Bitte selbst beurteilen."));
			if (result.greenStochasticPoints().isEmpty())
				result.reasons.add(0, tr("Kein beweglicher Anschlusspunkt ist grün - es gibt nichts zu übernehmen."));
			result.recommended = result.reasons.isEmpty();
			return result;
		}

		/**
		 * Randpunkte (Endbereiche bzw. aeussere Umrandung aller Altpunkte, dazu alle Punkte in markierten
		 * Anschlussbereichen) und Stuetzpunkte in der Mitte (alle uebrigen Altpunkte). Ohne Lage oder ohne
		 * bestimmbaren Endbereich gelten alle als Randpunkte.
		 */
		private static void assignZones(Result result, List<Point> geo, double spacingAll, Settings s) {
			for (Point p : result.points)
				p.middle = false;
			if (result.geometryAvailable) {
				if (result.elongated) {
					if (!Double.isNaN(result.maxGapM))
						for (Point p : geo)
							if (p.kind != KIND_NEW)
								p.middle = p.station > result.stationStart + result.maxGapM + 1e-6 && p.station < result.stationEnd - result.maxGapM - 1e-6;
				} else {
					List<Point> old = new ArrayList<Point>();
					for (Point p : geo)
						if (p.kind != KIND_NEW)
							old.add(p);
					if (old.size() >= 3) {
						double[][] oldPts = new double[old.size()][];
						for (int i = 0; i < oldPts.length; i++)
							oldPts[i] = old.get(i).planar;
						List<Integer> hull = Klein_Method_Geo.convexHull(oldPts);
						double tol = 0.1 * spacingAll;
						if (hull.size() >= 3)
							for (Point p : old) {
								double nearest = Double.POSITIVE_INFINITY;
								for (int k = 0; k < hull.size(); k++)
									nearest = Math.min(nearest, segmentDistance(p.planar, oldPts[hull.get(k)], oldPts[hull.get((k + 1) % hull.size())]));
								p.middle = nearest > tol;
							}
					}
				}
			}
			Set<String> inArea = new java.util.HashSet<String>();
			for (Area area : s.areas)
				inArea.addAll(area.pointNames);
			for (Point p : result.points)
				if (p.middle && inArea.contains(p.name))
					p.middle = false;
		}

		/** Kurzbezeichnung der Lage des Punkts (Oberflaechensprache). */
		public static String zoneLabel(Point p) {
			return p.middle ? tr("Mitte") : tr("Rand");
		}

		private static void rate(Result result, Settings s) {
			for (Point p : result.points) {
				if (p.kind == KIND_NEW) {
					result.newPoints++;
					continue;
				}
				if (p.kind == KIND_FIXED) {
					p.rating = Rating.FIXED;
					result.fixed++;
					continue;
				}
				if (p.middle)
					result.middlePoints++;
				else
					result.edgePoints++;
				p.levelingLimit = p.dimension == 1;
				if (p.middle) {
					p.green = p.levelingLimit ? s.midHeightGreen : s.midPositionGreen;
					p.yellow = p.levelingLimit ? s.midHeightYellow : s.midPositionYellow;
				} else {
					p.green = p.levelingLimit ? s.levelGreen : s.tachyGreen;
					p.yellow = p.levelingLimit ? s.levelYellow : s.tachyYellow;
				}
				if (Double.isNaN(p.sigmaMm) || !(p.sigmaMm > 0.0)) {
					p.rating = Rating.NO_VALUE;
					result.noValue++;
				} else if (p.sigmaMm <= p.green + 1e-9) {
					p.rating = Rating.GREEN;
					result.green++;
				} else if (p.sigmaMm <= p.yellow + 1e-9) {
					p.rating = Rating.YELLOW;
					result.yellow++;
				} else {
					p.rating = Rating.RED;
					result.red++;
				}
				if (p.dimension == 3 && p.leveled) {
					p.leveled3dHint = true;
					result.hints.add(String.format(tr("%s ist zusätzlich nivelliert. Bewertet wurde die gemeinsame Unsicherheit %s mm mit der Lage-Grenze (%s). "
							+ "Die nivellierte Höhe ist meist genauer - bitte selbst entscheiden, ob der Punkt als Höhenfestpunkt taugt."),
							p.name, Double.isNaN(p.sigmaMm) ? "-" : fmt(p.sigmaMm, 2), zoneLabel(p)));
				}
			}
		}

		/** Punkte mit Lage; geozentrische Netze werden in eine lokale Ebene (Ost/Nord) gedreht. */
		private static List<Point> planarPoints(List<Point> points) {
			List<Point> geo = new ArrayList<Point>();
			int ecef = 0;
			for (Point p : points) {
				p.planar = null;
				if (Double.isNaN(p.y) || Double.isNaN(p.x) || Double.isInfinite(p.y) || Double.isInfinite(p.x) || (p.y == 0.0 && p.x == 0.0))
					continue;
				geo.add(p);
				double norm = Double.isNaN(p.z) ? 0.0 : Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
				if (norm > 6.30e6 && norm < 6.42e6 && Math.abs(p.z) > 1.0e5)
					ecef++;
			}
			if (!geo.isEmpty() && ecef * 2 > geo.size()) {
				double cx = 0, cy = 0, cz = 0;
				int n = 0;
				for (Point p : geo)
					if (!Double.isNaN(p.z)) {
						cx += p.x;
						cy += p.y;
						cz += p.z;
						n++;
					}
				cx /= n;
				cy /= n;
				cz /= n;
				double[] llh = Klein_Method_Geo.ecefToLatLonHeight(cx, cy, cz);
				List<Point> converted = new ArrayList<Point>();
				for (Point p : geo) {
					if (Double.isNaN(p.z))
						continue;
					double[] enu = Klein_Method_Geo.ecefVectorToEnu(p.x - cx, p.y - cy, p.z - cz, llh[0], llh[1]);
					p.planar = new double[] { enu[0], enu[1] };
					converted.add(p);
				}
				return converted;
			}
			for (Point p : geo)
				p.planar = new double[] { p.y, p.x };
			return geo;
		}

		private static double medianNearestNeighbour(double[][] pts) {
			List<Double> nearest = new ArrayList<Double>();
			for (int i = 0; i < pts.length; i++) {
				double best = Double.POSITIVE_INFINITY;
				for (int j = 0; j < pts.length; j++)
					if (i != j) {
						double d = Math.hypot(pts[i][0] - pts[j][0], pts[i][1] - pts[j][1]);
						if (d > 1.0e-3 && d < best)
							best = d;
					}
				if (!Double.isInfinite(best))
					nearest.add(best);
			}
			return nearest.isEmpty() ? 0.0 : Klein_Method_Swarm.median(nearest);
		}

		/** Flaeche der konvexen Huelle (Gauss'sche Trapezformel). */
		private static double hullArea(double[][] pts) {
			List<Integer> hull = Klein_Method_Geo.convexHull(pts);
			if (hull.size() < 3)
				return 0.0;
			double sum = 0.0;
			for (int i = 0; i < hull.size(); i++) {
				double[] p = pts[hull.get(i)], q = pts[hull.get((i + 1) % hull.size())];
				sum += p[0] * q[1] - q[0] * p[1];
			}
			return Math.abs(sum) / 2.0;
		}

		private static int occupiedCells(double[][] pts, double cell) {
			Set<Long> cells = new java.util.HashSet<Long>();
			for (double[] p : pts)
				cells.add((long) Math.floor(p[0] / cell) * 4_000_003L + (long) Math.floor(p[1] / cell));
			return cells.size();
		}

		// ---------------- langgestreckte Netze ----------------

		/** Achse, Stationen, ueblicher Abstand der Altpunkte und max. Luecke (unabhaengig von der Ampel). */
		private static void prepareElongated(Result result, List<Point> geo, double[][] pts, Settings s, double tolerance) {
			List<Integer> axis = simplify(pts, diameterPath(pts), tolerance);
			for (Integer i : axis)
				result.axisNames.add(geo.get(i).name);
			double[] cumulative = new double[axis.size()];
			for (int k = 1; k < axis.size(); k++)
				cumulative[k] = cumulative[k - 1] + dist(pts[axis.get(k - 1)], pts[axis.get(k)]);
			double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < geo.size(); i++) {
				geo.get(i).station = station(pts[i], pts, axis, cumulative);
				start = Math.min(start, geo.get(i).station);
				end = Math.max(end, geo.get(i).station);
			}
			result.stationStart = start;
			result.stationEnd = end;
			result.lengthM = end - start;
			if (result.lengthM > 0.0)
				result.widthM = result.lengthM / Math.max(result.ratio, 1e-9);
			List<Double> oldStations = new ArrayList<Double>();
			for (Point p : geo)
				if (p.kind != KIND_NEW)
					oldStations.add(p.station);
			java.util.Collections.sort(oldStations);
			// Altpunkte, die dicht beieinanderliegen (z. B. 2-3 Punkte je Standort), zaehlen als EIN Standort - sonst
			// misst der Median die Abstaende innerhalb der Gruppen. Toleranz: 2 % der Netzlaenge, hoechstens halber
			// mittlerer Abstand der Altpunkte, mindestens 1 m.
			double meanOldSpacing = oldStations.size() > 1 ? (oldStations.get(oldStations.size() - 1) - oldStations.get(0)) / (oldStations.size() - 1) : 0.0;
			double clusterTolerance = Math.max(1.0, Math.min(CLUSTER_TOLERANCE_SHARE * (end - start), 0.5 * meanOldSpacing));
			List<Double> sites = new ArrayList<Double>();
			double sum = 0.0;
			int count = 0;
			double previous = Double.NaN;
			for (double stationValue : oldStations) {
				if (count > 0 && stationValue - previous > clusterTolerance) {
					sites.add(sum / count);
					sum = 0.0;
					count = 0;
				}
				sum += stationValue;
				count++;
				previous = stationValue;
			}
			if (count > 0)
				sites.add(sum / count);
			result.oldPointSites = sites.size();
			List<Double> steps = new ArrayList<Double>();
			for (int k = 1; k < sites.size(); k++)
				steps.add(sites.get(k) - sites.get(k - 1));
			result.typicalSpacingM = steps.isEmpty() ? Double.NaN : Klein_Method_Swarm.median(steps);
			result.maxGapAuto = !(s.maxGapM > 0.0);
			result.maxGapM = result.maxGapAuto ? 2.0 * result.typicalSpacingM : s.maxGapM;
		}

		private static void checkElongated(Result result, List<Point> geo, Settings s) {
			double start = result.stationStart, end = result.stationEnd;
			List<Point> anchors = new ArrayList<Point>();
			for (Point p : geo)
				if (p.isAnchor())
					anchors.add(p);
			if (anchors.size() < 2) {
				result.reasons.add(tr("Weniger als 2 grüne Festpunkte im Netz - die Neupunkte können nicht zwischen Festpunkten eingerechnet werden."));
				return;
			}
			if (Double.isNaN(result.maxGapM)) {
				result.reasons.add(tr("Der übliche Abstand der Altpunkte lässt sich nicht bestimmen - bitte die maximale Lücke eingeben."));
				return;
			}
			anchors.sort((a, b) -> Double.compare(a.station, b.station));
			double tol = 0.05 * result.maxGapM;
			for (Point p : anchors) {
				if (p.station <= start + result.maxGapM + 1e-6)
					result.endCountA++;
				if (p.station >= end - result.maxGapM - 1e-6)
					result.endCountB++;
			}
			if (result.endCountA < s.endCount)
				result.reasons.add(String.format(tr("Anfang (%s): nur %d grüne Festpunkte im Endbereich von %s, gefordert %d."),
						anchors.get(0).name, result.endCountA, formatLength(result.maxGapM), s.endCount));
			if (result.endCountB < s.endCount)
				result.reasons.add(String.format(tr("Ende (%s): nur %d grüne Festpunkte im Endbereich von %s, gefordert %d."),
						anchors.get(anchors.size() - 1).name, result.endCountB, formatLength(result.maxGapM), s.endCount));
			double first = anchors.get(0).station, last = anchors.get(anchors.size() - 1).station;
			List<String> before = new ArrayList<String>(), after = new ArrayList<String>();
			for (Point p : geo)
				if (p.kind == KIND_NEW) {
					if (p.station < first - tol) {
						before.add(p.name);
						p.outside = true;
					} else if (p.station > last + tol) {
						after.add(p.name);
						p.outside = true;
					}
				}
			if (!before.isEmpty())
				result.reasons.add(String.format(tr("%d Neupunkt(e) liegen vor dem ersten grünen Festpunkt (%s) und würden hinausgerechnet: %s"),
						before.size(), anchors.get(0).name, names(before)));
			if (!after.isEmpty())
				result.reasons.add(String.format(tr("%d Neupunkt(e) liegen hinter dem letzten grünen Festpunkt (%s) und würden hinausgerechnet: %s"),
						after.size(), anchors.get(anchors.size() - 1).name, names(after)));
			result.outsideNewNames.addAll(before);
			result.outsideNewNames.addAll(after);
			result.largestGapM = 0.0;
			for (int k = 1; k < anchors.size(); k++) {
				Point a = anchors.get(k - 1), b = anchors.get(k);
				double gap = b.station - a.station;
				result.largestGapM = Math.max(result.largestGapM, gap);
				if (gap > result.maxGapM + 1e-6)
					result.reasons.add(String.format(tr("Lücke zwischen %s und %s (Station %s bis %s): %s, erlaubt %s."), a.name, b.name,
							formatLength(a.station - start), formatLength(b.station - start), formatLength(gap), formatLength(result.maxGapM)));
			}
		}

		/** Ursachen fuer gelbe/rote Punkte und Freigabe-Vorschlaege aus der Kongruenzpruefung. */
		private static void applyCongruence(Result result, Klein_Method_Congruence.Result c) {
			if (c == null || !c.available)
				return;
			for (String message : Klein_Method_Congruence.messages(c))
				result.hints.add(message);
			if (c.scaleIssue())
				result.reasons.add(String.format(tr("Ma\u00DFstab %s ppm zwischen freier Ausgleichung und Sollkoordinaten - zuerst Projektion/Ma\u00DFstab kl\u00E4ren."),
						String.format(Locale.GERMANY, "%+.1f", c.scalePpm)));
			for (String name : c.fixedMisfit)
				result.reasons.add(String.format(tr("Fester Anschlusspunkt %s passt %s mm nicht - er verzerrt die Lagerung."), name,
						String.format(Locale.GERMANY, "%.1f", c.residualMm.get(name))));
			String fixedText = c.fixedMisfit.isEmpty() ? null : String.join(", ", c.fixedMisfit);
			for (Point p : result.points) {
				if (p.kind == KIND_NEW || p.name == null)
					continue;
				Double residual = c.residualMm.get(p.name);
				if (c.release.contains(p.name)) {
					p.releaseSuggested = true;
					p.cause = String.format(tr("passt %s mm nicht - besser als Neupunkt rechnen"), String.format(Locale.GERMANY, "%.1f", residual));
				} else if (p.rating == Rating.YELLOW || p.rating == Rating.RED || p.rating == Rating.NO_VALUE) {
					if (c.scaleIssue())
						p.cause = tr("Ma\u00DFstab (Projektion pr\u00FCfen)");
					else if (fixedText != null)
						p.cause = String.format(tr("fester Punkt %s passt nicht"), fixedText);
					else if (residual != null)
						p.cause = String.format(tr("Rest zur Lagerung %s mm"), String.format(Locale.GERMANY, "%.1f", residual));
				}
			}
		}

		/**
		 * jeder markierte Anschlussbereich braucht mindestens endCount gruene Festpunkte (zusaetzlich zur
		 * Verteilung).
		 */
		private static void checkAreas(Result result, Settings s) {
			if (s.areas.isEmpty())
				return;
			Map<String, Point> byName = new java.util.HashMap<String, Point>();
			for (Point p : result.points)
				if (p.name != null)
					byName.put(p.name, p);
			for (Area area : s.areas) {
				int anchors = 0, old = 0;
				for (String name : area.pointNames) {
					Point p = byName.get(name);
					if (p == null || p.kind == KIND_NEW)
						continue;
					old++;
					if (p.isAnchor())
						anchors++;
				}
				result.areaLines.add(area.name + ": " + anchors + "/" + s.endCount);
				if (old == 0)
					result.reasons.add(String.format(tr("Anschlussbereich \u201E%s\u201C enth\u00E4lt keine Altpunkte."), area.name));
				else if (anchors < s.endCount)
					result.reasons.add(String.format(tr("Anschlussbereich \u201E%s\u201C: nur %d gr\u00FCne Festpunkte, gefordert %d."), area.name, anchors, s.endCount));
			}
		}

		/** Laengster Weg im minimalen Spannbaum (Prim, O(n^2)) als Indexfolge. */
		static List<Integer> diameterPath(double[][] pts) {
			int n = pts.length;
			int[] parent = new int[n];
			double[] best = new double[n];
			boolean[] inTree = new boolean[n];
			java.util.Arrays.fill(best, Double.POSITIVE_INFINITY);
			java.util.Arrays.fill(parent, -1);
			best[0] = 0.0;
			List<List<Integer>> adjacency = new ArrayList<List<Integer>>();
			for (int i = 0; i < n; i++)
				adjacency.add(new ArrayList<Integer>());
			for (int step = 0; step < n; step++) {
				int u = -1;
				for (int i = 0; i < n; i++)
					if (!inTree[i] && (u < 0 || best[i] < best[u]))
						u = i;
				inTree[u] = true;
				if (parent[u] >= 0) {
					adjacency.get(u).add(parent[u]);
					adjacency.get(parent[u]).add(u);
				}
				for (int v = 0; v < n; v++)
					if (!inTree[v]) {
						double d = dist(pts[u], pts[v]);
						if (d < best[v]) {
							best[v] = d;
							parent[v] = u;
						}
					}
			}
			int[] previous = new int[n];
			int a = farthest(pts, adjacency, 0, previous);
			int b = farthest(pts, adjacency, a, previous);
			List<Integer> path = new ArrayList<Integer>();
			for (int v = b; v >= 0; v = previous[v])
				path.add(v);
			return path;
		}

		private static int farthest(double[][] pts, List<List<Integer>> adjacency, int start, int[] previous) {
			double[] distance = new double[pts.length];
			java.util.Arrays.fill(distance, -1.0);
			java.util.Arrays.fill(previous, -1);
			java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<Integer>();
			distance[start] = 0.0;
			stack.push(start);
			int far = start;
			while (!stack.isEmpty()) {
				int u = stack.pop();
				if (distance[u] > distance[far])
					far = u;
				for (int v : adjacency.get(u))
					if (distance[v] < 0.0) {
						distance[v] = distance[u] + dist(pts[u], pts[v]);
						previous[v] = u;
						stack.push(v);
					}
			}
			return far;
		}

		/** Douglas-Peucker: glaettet das Zickzack des Spannbaum-Wegs zur Achse. */
		static List<Integer> simplify(double[][] pts, List<Integer> path, double tolerance) {
			if (path.size() <= 2)
				return new ArrayList<Integer>(path);
			boolean[] keep = new boolean[path.size()];
			keep[0] = keep[path.size() - 1] = true;
			java.util.ArrayDeque<int[]> ranges = new java.util.ArrayDeque<int[]>();
			ranges.push(new int[] { 0, path.size() - 1 });
			while (!ranges.isEmpty()) {
				int[] r = ranges.pop();
				double worst = -1.0;
				int index = -1;
				for (int k = r[0] + 1; k < r[1]; k++) {
					double d = segmentDistance(pts[path.get(k)], pts[path.get(r[0])], pts[path.get(r[1])]);
					if (d > worst) {
						worst = d;
						index = k;
					}
				}
				if (index >= 0 && worst > tolerance) {
					keep[index] = true;
					ranges.push(new int[] { r[0], index });
					ranges.push(new int[] { index, r[1] });
				}
			}
			List<Integer> result = new ArrayList<Integer>();
			for (int k = 0; k < path.size(); k++)
				if (keep[k])
					result.add(path.get(k));
			return result;
		}

		private static double station(double[] p, double[][] pts, List<Integer> axis, double[] cumulative) {
			if (axis.size() == 1)
				return 0.0;
			double bestDistance = Double.POSITIVE_INFINITY, bestStation = 0.0;
			for (int k = 1; k < axis.size(); k++) {
				double[] a = pts[axis.get(k - 1)], b = pts[axis.get(k)];
				double dx = b[0] - a[0], dy = b[1] - a[1], length2 = dx * dx + dy * dy;
				double t = length2 > 0 ? ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2 : 0.0;
				// Verlaengerung an den Achsenenden zulassen (Punkte vor/hinter der Achse)
				if (k > 1)
					t = Math.max(t, 0.0);
				if (k < axis.size() - 1)
					t = Math.min(t, 1.0);
				double qx = a[0] + t * dx, qy = a[1] + t * dy;
				double d = Math.hypot(p[0] - qx, p[1] - qy);
				if (d < bestDistance - 1e-9) {
					bestDistance = d;
					bestStation = cumulative[k - 1] + t * Math.sqrt(length2);
				}
			}
			return bestStation;
		}

		private static double segmentDistance(double[] p, double[] a, double[] b) {
			double dx = b[0] - a[0], dy = b[1] - a[1], length2 = dx * dx + dy * dy;
			double t = length2 > 0 ? Math.max(0.0, Math.min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2)) : 0.0;
			return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
		}

		private static double dist(double[] a, double[] b) {
			return Math.hypot(a[0] - b[0], a[1] - b[1]);
		}

		// ---------------- flaechige Netze ----------------

		private static void checkFlat(Result result, List<Point> geo, double[][] pts, double spacingAll) {
			List<Point> anchors = new ArrayList<Point>();
			for (Point p : geo)
				if (p.isAnchor())
					anchors.add(p);
			if (anchors.size() < 3) {
				result.reasons.add(tr("Weniger als 3 grüne Festpunkte - bei einem flächigen Netz müssen die Neupunkte von Festpunkten umschlossen sein."));
				return;
			}
			double[][] anchorPts = new double[anchors.size()][];
			for (int i = 0; i < anchorPts.length; i++)
				anchorPts[i] = anchors.get(i).planar;
			List<Integer> hull = Klein_Method_Geo.convexHull(anchorPts);
			double area = 0.0;
			for (int k = 0; k < hull.size(); k++) {
				double[] a = anchorPts[hull.get(k)], b = anchorPts[hull.get((k + 1) % hull.size())];
				area += a[0] * b[1] - b[0] * a[1];
			}
			for (Integer i : hull)
				result.hullNames.add(anchors.get(i).name);
			if (hull.size() < 3 || Math.abs(area) / 2.0 < 1e-6) {
				result.reasons.add(tr("Die grünen Festpunkte liegen auf einer Linie - sie umschließen keine Fläche."));
				return;
			}
			double tol = 0.1 * spacingAll;
			List<String> outside = new ArrayList<String>();
			for (Point p : geo)
				if (p.kind == KIND_NEW && !insideHull(p.planar, anchorPts, hull, tol)) {
					p.outside = true;
					outside.add(p.name);
				}
			result.outsideNewNames.addAll(outside);
			if (!outside.isEmpty())
				result.reasons.add(String.format(tr("%d Neupunkt(e) liegen außerhalb der grünen Festpunkte und würden hinausgerechnet: %s"),
						outside.size(), names(outside)));
		}

		/** innerhalb der (gegen den Uhrzeigersinn orientierten) Huelle oder hoechstens tol ausserhalb. */
		private static boolean insideHull(double[] p, double[][] pts, List<Integer> hull, double tol) {
			boolean inside = true;
			for (int k = 0; k < hull.size(); k++) {
				double[] a = pts[hull.get(k)], b = pts[hull.get((k + 1) % hull.size())];
				if ((b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) < 0) {
					inside = false;
					break;
				}
			}
			if (inside)
				return true;
			for (int k = 0; k < hull.size(); k++)
				if (segmentDistance(p, pts[hull.get(k)], pts[hull.get((k + 1) % hull.size())]) <= tol)
					return true;
			return false;
		}

		private static String names(List<String> names) {
			int shown = Math.min(names.size(), 10);
			String text = String.join(", ", names.subList(0, shown));
			return names.size() > shown ? text + String.format(tr(" und %d weitere"), names.size() - shown) : text;
		}

		/** Kurzbezeichnung der Ampel in der Oberflaechensprache. */
		public static String ratingLabel(Rating rating) {
			switch (rating) {
			case GREEN:
				return tr("grün");
			case YELLOW:
				return tr("gelb");
			case RED:
				return tr("rot");
			case FIXED:
				return tr("fest");
			default:
				return tr("ohne Wert");
			}
		}
	}

	// ============================================================================================================
	/** Phase 1: Gruppenkalibrierung (Gruppenunsicherheiten sigma_a/b/c, Daempfung, Untergrenzen). */
	private final class Phase1_Part {

		/**
		 * PHASE 1: kalibriert σa/σb/σc fuer ALLE Beobachtungstypen. Jede der 3 Komponenten hat ihr EIGENES 1:σ²
		 * (bestaetigt per OCR-Auswertung der echten JAG3D-"Varianzkomponentenanalyse" UND per Fund der genauen
		 * VarianceComponentType-Konstanten in SQLManager3x.java). Gibt es fuer einen Typ mehrere Gruppen, bekommen laut
		 * Vorgabe ALLE Gruppen denselben Korrekturfaktor je Komponente (kein pro-Gruppen-1:σ²), werden aber individuell
		 * (je Gruppen-ID) gelesen und zurueckgeschrieben.
		 *
		 * die GNSS-Sonderwege (Komplett-Freeze ab Pass 2) und sind RESTLOS entfernt. ALLE Typen (GNSS 1D/2D/3D,
		 * Richtungen, Strecken, Nivellement) laufen jetzt ueber dieselbe Ur-Formel mit Zielkorridor, siehe
		 * computeGroupUncertainty():
		 *
		 * F > Max: sigma_neu = sigma_alt + sigma_alt*log10(F) (steigt) F < Min: sigma_neu = sigma_alt +
		 * sigma_alt*log10(F) (faellt) Min <= F <= Max: sigma bleibt unveraendert (Symmetrie-Ruhe)
		 *
		 * F ist dabei der reelle, aktuell von JAG3D berechnete a-posteriori Varianzfaktor ("sigma2apost" aus
		 * "VarianceComponent") der jeweiligen Komponente - bei GNSS also getrennt fuer sigma_a, sigma_b, sigma_c.
		 *
		 * Diese Methode ist der EINMALIGE Phase-1-Eichversuch je Kaskaden-Pass. Die laufende Nachregelung in JEDER
		 * weiteren Iteration uebernimmt performGroupDampingStep() (siehe recompute(), Zweig INDIVIDUAL_CALIBRATION).
		 *
		 * @return true, wenn ALLE Gruppen ALLER Typen kalibriert sind (Zielkorridor oder Untergrenze erreicht).
		 */
		private boolean performGroupCalibrationStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails) throws SQLException {
			// Phase 1: Gruppen-Skalierungsfaktor V - nur beim ERSTEN Phase-1-Schritt eines Durchlaufs. Seit Phase 1
			// (Schalter "Gruppen-Daempfung Phase 2: aus") wieder ueber mehrere Iterationen laufen kann, wuerde V sonst je
			// Phase-1-Iteration erneut multipliziert (V^n).
			boolean applyScaleFactor = !parameter.hasPhase1CalibratedThisPass();
			return this.regulateGroupUncertainties(parameter, databaseHandler, outAdjustmentDetails, applyScaleFactor, "Phase 1");
		}
		/**
		 * setzt sigma_a/b/c ALLER Gruppen ALLER Beobachtungstypen auf ihre aktuelle Untergrenze
		 * (Klein_Method_Parameter.getGroupUncertaintyFloorA()/B()/C(), je Typ und Geraeteklasse, in der UI editierbar).
		 * Komponenten mit 0 ("Parameter nicht verwenden") bleiben 0. Aufruf aus Klein_Method_Iteration_Task beim
		 * Uebergang von Durchlauf 1 zu Durchlauf 2 der Kaskade.
		 *
		 * @return Anzahl der geschriebenen Gruppen
		 */
		public int applyGroupUncertaintyFloors(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails) throws SQLException {
			int decimalPlaces = parameter.getDecimalPlaces();
			int writtenGroups = 0;
			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
				double floorA = parameter.getGroupUncertaintyFloorA(type);
				double floorB = parameter.getGroupUncertaintyFloorB(type);
				double floorC = parameter.getGroupUncertaintyFloorC(type);
				String unitA = Klein_Method_Default_Values.getSigmaAUnit(type);
				String unitB = Klein_Method_Default_Values.getSigmaBUnit(type);
				String unitC = Klein_Method_Default_Values.getSigmaCUnit(type);

				List<Integer> floorGroupIds = databaseHandler.readGroupIds(type);
				if (!floorGroupIds.isEmpty()) {
					double[] ratiosBeforeFloor = databaseHandler.readGroupVarianceRatios(type);
					if (ratiosBeforeFloor != null)
						parameter.getPass1Ratios().put(type.name(), ratiosBeforeFloor.clone());
				}
				for (Integer groupId : floorGroupIds) {
					double[] sigmaRaw = databaseHandler.readGroupUncertainty(groupId);
					double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], unitA);
					double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], unitB);
					double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], unitC);
					parameter.getPass1Sigmas().put(type.name() + "|" + groupId, new double[] { sigmaA, sigmaB, sigmaC });
					double newA = sigmaA == 0.0 ? 0.0 : floorA;
					double newB = sigmaB == 0.0 ? 0.0 : floorB;
					double newC = sigmaC == 0.0 ? 0.0 : floorC;

					databaseHandler.writeGroupUncertainty(groupId,
							Klein_Method_Functions.this.toDatabaseValue(newA, unitA),
							Klein_Method_Functions.this.toDatabaseValue(newB, unitB),
							Klein_Method_Functions.this.toDatabaseValue(newC, unitC));
					writtenGroups++;

					if (outAdjustmentDetails != null) {
						outAdjustmentDetails.add("GRUPPENUNSICHERHEIT HERABGESETZT (nach Durchlauf 1) " + type.getDisplayName() + " Gruppe " + groupId
								+ ": sigma_a " + Klein_Method_Format.format(sigmaA, unitA, decimalPlaces) + " -> " + Klein_Method_Format.format(newA, unitA, decimalPlaces)
								+ " | sigma_b " + Klein_Method_Format.format(sigmaB, unitB, decimalPlaces) + " -> " + Klein_Method_Format.format(newB, unitB, decimalPlaces)
								+ " | sigma_c " + Klein_Method_Format.format(sigmaC, unitC, decimalPlaces) + " -> " + Klein_Method_Format.format(newC, unitC, decimalPlaces));
					}
				}
			}
			databaseHandler.commit();
			return writtenGroups;
		}
		/**
		 * laufende Gruppen-Daempfung in JEDER Iteration > 0 eines Passes, d.h. in jeder Iteration der Phase 2
		 * (INDIVIDUAL_CALIBRATION). Liest die reellen, soeben von JAG3D berechneten a-posteriori Varianzfaktoren je
		 * Komponente und regelt sigma_a/b/c ueber dieselbe Ur-Formel wie Phase 1 nach.
		 *
		 * Unterschied zu Phase 1: der Gruppen-Skalierungsfaktor V wird hier BEWUSST NICHT angewendet. V ist als einmalige
		 * Skalierung je Phase-1-Eichung gedacht; wuerde er in jeder Iteration erneut multipliziert, ergaebe sich bei V !=
		 * 1.00 eine exponentielle Aufschaukelung (V^n ueber n Iterationen) - unabhaengig vom Varianzfaktor und ohne
		 * Korridor-Halt.
		 *
		 * Werte im Korridor werden NICHT geschrieben (keine unnoetigen DB-Zugriffe, echte Symmetrie-Ruhe).
		 */
		private void performGroupDampingStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails) throws SQLException {
			this.regulateGroupUncertainties(parameter, databaseHandler, outAdjustmentDetails, false, "Phase 2 / Gruppen-Daempfung");
		}
		/**
		 * gemeinsamer Kern von performGroupCalibrationStep() (Phase 1) und performGroupDampingStep() (jede Iteration der
		 * Phase 2).
		 *
		 * @param applyGroupScaleFactor true = Gruppen-Skalierungsfaktor V anwenden (nur Phase 1), false = reine
		 * Ur-Formel.
		 *
		 * @param stageLabel Protokoll-Kennung der aufrufenden Stufe.
		 *
		 * @return true, wenn ALLE Komponenten ALLER Gruppen ALLER Typen im Zielkorridor oder an ihrer Untergrenze sind.
		 */
		private boolean regulateGroupUncertainties(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails, boolean applyGroupScaleFactor, String stageLabel) throws SQLException {
			boolean allCalibrated = true;
			int decimalPlaces = parameter.getDecimalPlaces();
			// nur im Phase-1-Kalibrierungsschritt (stageLabel "Phase 1", nicht in der Gruppen-Daempfung der Phase 2) und
			// nur in freier und fixer Ausgleichung (nicht in der dynamischen Festpunktanalyse).
			boolean turboAllowed = false;
			if ("Phase 1".equals(stageLabel) && parameter.isPhase1TurboEnabled()) {
				Klein_Method_Adjustment_Step step = Klein_Method_Functions.this.determineCurrentAdjustmentStep(databaseHandler);
				turboAllowed = step == Klein_Method_Adjustment_Step.FREE || step == Klein_Method_Adjustment_Step.FIXED;
			}
			// Korridorgrenzen live aus der UI (Sektion "Grenzwerte") - einmal je Schritt gelesen, damit alle Typen und
			// Komponenten dieses Schritts garantiert gegen DENSELBEN Korridor laufen.
			double bandMin = parameter.getVksBandMin();
			double bandMax = parameter.getVksBandMax();

			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
				// Untergrenzen PRO TYP UND PRO KOMPONENTE (siehe Klein_Method_Parameter.
				// getGroupUncertaintyFloorA()/B()/C()) - beruecksichtigt automatisch die
				// aktuell gewaehlte Geraeteklasse.
				double floorA = parameter.getGroupUncertaintyFloorA(type);
				double floorB = parameter.getGroupUncertaintyFloorB(type);
				double floorC = parameter.getGroupUncertaintyFloorC(type);

				String unitA = Klein_Method_Default_Values.getSigmaAUnit(type);
				String unitB = Klein_Method_Default_Values.getSigmaBUnit(type);
				String unitC = Klein_Method_Default_Values.getSigmaCUnit(type);

				// 3 unabhaengige, reelle a-posteriori Varianzfaktoren (F_a, F_b, F_c) -
				// frisch aus der soeben gelaufenen JAG3D-Ausgleichung gelesen.
				double[] varianceRatios = databaseHandler.readGroupVarianceRatios(type);
				double varianceRatioA = varianceRatios[0];
				double varianceRatioB = varianceRatios[1];
				double varianceRatioC = varianceRatios[2];

				// Redundanz je Komponente - VKS-Band nach Redundanz (Punkt 1) und keine Kalibrierung bei zu kleiner
				// Redundanz (Punkt 2).
				double[][] redundancies = databaseHandler.readVarianceComponentRedundancies(type);
				double[][] bands = new double[3][];
				boolean[] tooSmall = new boolean[3];
				// regewichtete Wiederholungen tragen ein eigenes Sigma und zaehlen in JAG3D nicht zur Gruppen-VKS - hat eine
				// Komponente dadurch gar keine Redundanz mehr, bleibt die Gruppe unveraendert (gilt als kalibriert)
				boolean reweightedRepetitions = databaseHandler.hasReweightedRepetitions(type);
				for (int k = 0; k < 3; k++) {
					double r = redundancies == null || redundancies[k] == null ? Double.NaN : redundancies[k][0];
					bands[k] = Klein_Method_Calibration_Quality.effectiveBand(bandMin, bandMax, r, parameter.getFullBandRedundancy());
					double minRedundancy = parameter.getMinCalibrationRedundancy();
					tooSmall[k] = minRedundancy > 0.0 && !Double.isNaN(r) && r > 0.0 && r < minRedundancy;
					if (reweightedRepetitions && !Double.isNaN(r) && !(r > 0.0))
						tooSmall[k] = true;
					String noteKey = type.name() + "|" + SWARM_COMPONENT_NAMES[k] + "|";
					if (tooSmall[k])
						parameter.getCalibrationNotes().put(noteKey + "KLEIN", "Redundanz " + Klein_Method_Format.format(r, "", 1) + " < "
								+ Klein_Method_Format.format(minRedundancy, "", 1) + " - nicht kalibriert");
					else if (bands[k][0] != bandMin || bands[k][1] != bandMax)
						parameter.getCalibrationNotes().put(noteKey + "BAND", "Band " + Klein_Method_Format.format(bands[k][0], "", 3) + "-"
								+ Klein_Method_Format.format(bands[k][1], "", 3) + " (Redundanz " + Klein_Method_Format.format(r, "", 1) + ")");
				}
				// robuste VKS - einmal je Pass beim ersten Phase-1-Schritt
				if (applyGroupScaleFactor && "Phase 1".equals(stageLabel))
					Klein_Method_Calibration_Quality.checkRobustness(parameter, databaseHandler, type, outAdjustmentDetails);

				List<Integer> groupIds = databaseHandler.readGroupIds(type);

				for (Integer groupId : groupIds) {
					// JAG3D speichert intern in Radiant/Meter/unitless - SOFORT nach dem
					// Lesen in unsere Arbeitseinheit (mgon/mm/ppm) umrechnen.
					double[] sigmaRaw = databaseHandler.readGroupUncertainty(groupId);
					double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], unitA);
					double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], unitB);
					double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], unitC);

					// sigma == 0 bedeutet in JAG3D "Parameter nicht verwenden" - bleibt 0.
					boolean aDisabled = sigmaA == 0.0;
					boolean bDisabled = sigmaB == 0.0;
					boolean cDisabled = sigmaC == 0.0;

					String turboKeyBase = type.name() + "|" + groupId + "|";
					double[] turboFactors = new double[] { 1.0, 1.0, 1.0 };
					double newSigmaA = aDisabled ? 0.0 : tooSmall[0] ? sigmaA : this.computeGroupUncertaintyWithTurbo(parameter, turboAllowed, turboKeyBase + "a", sigmaA, varianceRatioA, floorA, bands[0][0], bands[0][1], turboFactors, 0);
					double newSigmaB = bDisabled ? 0.0 : tooSmall[1] ? sigmaB : this.computeGroupUncertaintyWithTurbo(parameter, turboAllowed, turboKeyBase + "b", sigmaB, varianceRatioB, floorB, bands[1][0], bands[1][1], turboFactors, 1);
					double newSigmaC = cDisabled ? 0.0 : tooSmall[2] ? sigmaC : this.computeGroupUncertaintyWithTurbo(parameter, turboAllowed, turboKeyBase + "c", sigmaC, varianceRatioC, floorC, bands[2][0], bands[2][1], turboFactors, 2);

					// Gruppen-Skalierungsfaktor V - NUR in Phase 1 (siehe performGroupDampingStep() fuer die
					// Begruendung).
					double groupScaleFactor = applyGroupScaleFactor ? parameter.getGroupScaleFactor(type) : 1.0;
					double scaledSigmaA = aDisabled ? 0.0 : tooSmall[0] ? sigmaA : newSigmaA * groupScaleFactor;
					double scaledSigmaB = bDisabled ? 0.0 : tooSmall[1] ? sigmaB : newSigmaB * groupScaleFactor;
					double scaledSigmaC = cDisabled ? 0.0 : tooSmall[2] ? sigmaC : newSigmaC * groupScaleFactor;

					boolean changed = scaledSigmaA != sigmaA || scaledSigmaB != sigmaB || scaledSigmaC != sigmaC;

					// Schreiben NUR bei echter Aenderung.
					if (changed) {
						databaseHandler.writeGroupUncertainty(groupId,
								Klein_Method_Functions.this.toDatabaseValue(scaledSigmaA, unitA),
								Klein_Method_Functions.this.toDatabaseValue(scaledSigmaB, unitB),
								Klein_Method_Functions.this.toDatabaseValue(scaledSigmaC, unitC));
					}

					if (outAdjustmentDetails != null) {
						outAdjustmentDetails.add(this.formatGroupDampingLine(stageLabel, "sigma_a", type, groupId, aDisabled,
								sigmaA, scaledSigmaA, varianceRatioA, unitA, decimalPlaces, bands[0][0], bands[0][1]) + this.formatTurboSuffix(turboFactors[0])
								+ Klein_Method_Calibration_Quality.qualitySuffix(tooSmall[0], bands[0], bandMin, bandMax, redundancies, 0));
						outAdjustmentDetails.add(this.formatGroupDampingLine(stageLabel, "sigma_b", type, groupId, bDisabled,
								sigmaB, scaledSigmaB, varianceRatioB, unitB, decimalPlaces, bands[1][0], bands[1][1]) + this.formatTurboSuffix(turboFactors[1])
								+ Klein_Method_Calibration_Quality.qualitySuffix(tooSmall[1], bands[1], bandMin, bandMax, redundancies, 1));
						outAdjustmentDetails.add(this.formatGroupDampingLine(stageLabel, "sigma_c", type, groupId, cDisabled,
								sigmaC, scaledSigmaC, varianceRatioC, unitC, decimalPlaces, bands[2][0], bands[2][1]) + this.formatTurboSuffix(turboFactors[2])
								+ Klein_Method_Calibration_Quality.qualitySuffix(tooSmall[2], bands[2], bandMin, bandMax, redundancies, 2));
					}

					// deaktivierte Komponenten (0) gelten automatisch als "kalibriert"
					boolean aCalibrated = aDisabled || tooSmall[0] || this.isGroupUncertaintyCalibrated(varianceRatioA, newSigmaA, floorA, bands[0][0], bands[0][1]);
					boolean bCalibrated = bDisabled || tooSmall[1] || this.isGroupUncertaintyCalibrated(varianceRatioB, newSigmaB, floorB, bands[1][0], bands[1][1]);
					boolean cCalibrated = cDisabled || tooSmall[2] || this.isGroupUncertaintyCalibrated(varianceRatioC, newSigmaC, floorC, bands[2][0], bands[2][1]);

					if (!aCalibrated || !bCalibrated || !cCalibrated)
						allCalibrated = false;
				}
				if (!this.calibrateRepetitionMeans(parameter, databaseHandler, outAdjustmentDetails, type, stageLabel, floorA, unitA, bandMin, bandMax))
					allCalibrated = false;
			}
			return allCalibrated;
		}
		/**
		 * Zusammenfassen und kalibrieren: Mittel mit eigener Genauigkeit (Startwert Streuung bzw. halbe Drift) tragen ein
		 * individuelles Sigma und zaehlen in JAG3D nicht zur Varianzkomponente der Messart. Ihr eigener Varianzfaktor F =
		 * Summe omega / Summe Redundanzanteile wird hier aus der soeben gelaufenen Ausgleichung bestimmt und ihre
		 * Genauigkeit mit derselben Ur-Formel wie die Gruppen nachgeregelt (Faktor max(1 + log10(F), 0,3), mindestens die
		 * Untergrenze sigma_a der Messart). Zu kleine Redundanz: unveraendert.
		 *
		 * @return true, wenn nichts zu tun ist oder F im Korridor liegt
		 */
		private boolean calibrateRepetitionMeans(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails, Klein_Method_Threshold_Type type, String stageLabel, double floorA, String unitA, double bandMin,
				double bandMax) throws SQLException {
			List<double[]> means = databaseHandler.readCalibratedRepetitionMeans(type);
			if (means.isEmpty())
				return true;
			double sumOmega = 0.0, sumR = 0.0;
			for (double[] m : means) {
				sumOmega += m[5];
				sumR += m[6];
			}
			String prefix = "WIEDERHOLUNGSMITTEL " + type.getDisplayName() + " [" + stageLabel + "]: " + means.size() + " Mittel mit eigener Genauigkeit, Redundanz "
					+ Klein_Method_Format.format(sumR, "", 2);
			double minRedundancy = parameter.getMinCalibrationRedundancy();
			if (!(sumR > 0.0) || (minRedundancy > 0.0 && sumR < minRedundancy)) {
				if (outAdjustmentDetails != null)
					outAdjustmentDetails.add(prefix + " - zu klein, Genauigkeit bleibt (Streuung)");
				return true;
			}
			double f = sumOmega / sumR;
			if (this.isVarianceRatioInDampingCorridor(f, bandMin, bandMax) || !(f > 0.0) || Double.isInfinite(f)) {
				if (outAdjustmentDetails != null)
					outAdjustmentDetails.add(prefix + " | F=" + Klein_Method_Format.format(f, "", 6) + " | Korridor erreicht");
				return true;
			}
			double factor = Math.max(1.0 + Math.log10(f), 0.3);
			double floor = Klein_Method_Functions.this.toDatabaseValue(floorA, unitA);
			int changed = 0;
			for (double[] m : means) {
				double[] sigma = new double[] { m[2], m[3], m[4] };
				boolean any = false;
				for (int c = 0; c < 3; c++) {
					if (!(sigma[c] > 0.0))
						continue;
					double next = Math.max(sigma[c] * factor, Math.min(floor, sigma[c]));
					if (next != sigma[c]) {
						sigma[c] = next;
						any = true;
					}
				}
				if (any) {
					databaseHandler.writeCalibratedRepetitionMean((int) m[0], (int) m[1], sigma);
					changed++;
				}
			}
			if (outAdjustmentDetails != null)
				outAdjustmentDetails.add(prefix + " | F=" + Klein_Method_Format.format(f, "", 6) + " | Faktor " + Klein_Method_Format.format(factor, "", 3) + " | "
						+ (changed == 0 ? "Untergrenze erreicht - unveraendert" : changed + " Mittel " + (factor > 1.0 ? "erhoeht" : "reduziert")));
			return changed == 0;
		}
		/**
		 * eine Protokollzeile je Gruppen-Komponente, mit reellem F und der tatsaechlich getroffenen Entscheidung
		 * (erhoeht/reduziert/Korridor).
		 */
		private String formatGroupDampingLine(String stageLabel, String componentName, Klein_Method_Threshold_Type type, int groupId,
				boolean disabled, double oldValue, double newValue, double varianceRatio, String unit, int decimalPlaces,
				double bandMin, double bandMax) {
			String prefix = componentName + " " + type.getDisplayName() + " Gruppe " + groupId + " [" + stageLabel + "]: ";
			if (disabled)
				return prefix + "deaktiviert (0)";

			String decision;
			if (this.isVarianceRatioInDampingCorridor(varianceRatio, bandMin, bandMax))
				decision = "Korridor erreicht - festgeklammert";
			else if (newValue > oldValue)
				decision = "erhoeht";
			else if (newValue < oldValue)
				decision = "reduziert";
			else
				decision = "unveraendert (Untergrenze/Schutzklammer)";

			return prefix + Klein_Method_Format.format(oldValue, unit, decimalPlaces) + " -> "
					+ Klein_Method_Format.format(newValue, unit, decimalPlaces)
					+ " | F=" + Klein_Method_Format.format(varianceRatio, "", 6) + " | " + decision;
		}
		/**
		 * PHASE 1 + laufende Gruppen-Daempfung - Ur-Formel fuer EINEN Gruppenunsicherheits-Parameter (σa, σb(√d) oder
		 * σc(d)), identisch fuer ALLE Beobachtungstypen:
		 *
		 * F = reeller a-posteriori Varianzfaktor ("1:σ²") dieser Komponente
		 *
		 * Min <= F <= Max: neu = alt (Symmetrie-Ruhe)
		 *
		 * sonst: Faktor = 1 + LOG10(F)
		 *
		 * Faktor = MAX(Faktor, 0.3) (Null-/Vorzeichenschutz)
		 *
		 * neu = alt * Faktor (== alt + alt*LOG10(F))
		 *
		 * neu = MAX(neu, Untergrenze)
		 *
		 * Sonderfaelle:
		 *
		 * F nicht endlich oder <= 0 (keine gueltige Varianzkomponente): alt bleibt.
		 *
		 * alt liegt bereits auf/unter der Untergrenze und F < Min: alt bleibt (die Untergrenze darf einen Wert nie nach
		 * OBEN ziehen, wenn die Formel eigentlich eine Reduktion verlangt).
		 *
		 * @param floorValue Untergrenze PRO TYP, PRO KOMPONENTE UND PRO GERAETEKLASSE (siehe
		 * Klein_Method_Parameter.getGroupUncertaintyFloorA()/B()/C()), in der UI frei editierbar.
		 */
		/**
		 * wie computeGroupUncertainty(), aber beim VERRINGERN (F unter dem Korridor) mit selbstlernendem Faktor k (1-10):
		 *
		 * sigma_neu = sigma_alt * max(1 + k*log10(F), 0,3), mindestens Untergrenze
		 *
		 * Lernregel (je Gruppe/Komponente, Zustand im Parameter):
		 *
		 * 1. Schritt: k = 1 (normale Ur-Formel).
		 *
		 * Folgeschritt, wieder Verringern: Sekanten-Prognose aus den letzten zwei Schritten - wie stark hat sich log10(F)
		 * auf die Aenderung von ln(sigma) bewegt? Daraus der Schritt, der log10(F) auf 0 (F = 1) bringen wuerde;
		 *
		 * k = dieser Schritt / Normalschritt, begrenzt auf [1, 10] und auf hoechstens k_vorher + 2 (kein Sprung von 1 auf
		 * 10 in einem Schritt).
		 *
		 * Erhoehen (F ueber dem Korridor), Korridor erreicht oder Untergrenze: k = 1, Lernzustand geloescht
		 * (Ueberschiessen -> sofort zurueck zur Ur-Formel).
		 */
		private double computeGroupUncertaintyWithTurbo(Klein_Method_Parameter parameter, boolean turboAllowed, String key,
				double oldValue, double varianceRatio, double floorValue, double bandMin, double bandMax, double[] outFactors, int index) {
			double standard = this.computeGroupUncertainty(oldValue, varianceRatio, floorValue, bandMin, bandMax);
			java.util.Map<String, double[]> states = parameter.getPhase1TurboStates();
			boolean reducing = turboAllowed && standard < oldValue && varianceRatio > 0.0 && varianceRatio < bandMin && !Double.isNaN(varianceRatio);
			if (!reducing) {
				states.remove(key);
				return standard;
			}
			double r = Math.log10(varianceRatio); // < 0
			double k = 1.0;
			double[] previous = states.get(key);
			if (previous != null && previous[0] > 0.0 && oldValue > 0.0) {
				double previousSigma = previous[0], previousR = previous[1], previousK = previous[2];
				double deltaLnSigma = Math.log(oldValue) - Math.log(previousSigma); // < 0 beim Verringern
				double deltaR = r - previousR;
				double predicted = Double.NaN;
				if (Math.abs(deltaLnSigma) > 1e-9 && deltaR > 1e-9) {
					double slope = deltaR / deltaLnSigma; // < 0: kleineres sigma -> F naeher an 1
					double neededLnSigma = -r / slope;   // < 0
					double neededRatio = Math.exp(neededLnSigma);
					predicted = (neededRatio - 1.0) / r; // Faktor k fuer sigma*(1 + k*r)
				}
				if (!Double.isNaN(predicted) && !Double.isInfinite(predicted) && predicted > 0.0)
					k = Math.min(predicted, previousK + 2.0);
				else
					k = Math.abs(r) > 0.5 * Math.abs(previousR) ? previousK + 1.0 : previousK - 1.0;
				k = Math.max(1.0, Math.min(10.0, k));
			}
			double factor = Math.max(1.0 + k * r, Klein_Method_Default_Values.GROUP_DAMPING_MIN_FACTOR);
			double turboValue = Math.max(oldValue * factor, floorValue);
			states.put(key, new double[] { oldValue, r, k });
			if (outFactors != null)
				outFactors[index] = k;
			return Math.min(turboValue, standard); // nie weniger stark verringern als die Ur-Formel
		}
		private String formatTurboSuffix(double factor) {
			return factor > 1.0001 ? " | TURBO Faktor " + Klein_Method_Format.format(factor, "", 3) : "";
		}
		public double computeGroupUncertainty(double oldValue, double varianceRatio, double floorValue, double bandMin, double bandMax) {
			if (Double.isNaN(varianceRatio) || Double.isInfinite(varianceRatio) || varianceRatio <= 0.0)
				return oldValue;

			if (this.isVarianceRatioInDampingCorridor(varianceRatio, bandMin, bandMax))
				return oldValue;

			if (varianceRatio < bandMin && oldValue <= floorValue)
				return oldValue;

			double rawFactor = 1.0 + Math.log10(varianceRatio);
			double correctionFactor = Math.max(rawFactor, Klein_Method_Default_Values.GROUP_DAMPING_MIN_FACTOR);

			double newValue = oldValue * correctionFactor;
			return Math.max(newValue, floorValue);
		}
		/**
		 * true, wenn der reelle Varianzfaktor F im Zielkorridor [bandMin; bandMax] liegt - einzige Quelle fuer den
		 * Korridor, damit Formel, Kalibrierungs-Status und Protokoll garantiert dieselben Grenzen verwenden.
		 */
		public boolean isVarianceRatioInDampingCorridor(double varianceRatio, double bandMin, double bandMax) {
			return varianceRatio >= bandMin && varianceRatio <= bandMax;
		}
		/**
		 * PHASE 1 / Gruppen-Daempfung - true, wenn diese eine Gruppenunsicherheits-Komponente (σa/σb/σc) als kalibriert
		 * gilt:
		 *
		 * F im Zielkorridor [bandMin; bandMax], ODER
		 *
		 * F < bandMin und der Wert steht an seiner Untergrenze. Steht der Wert an der Untergrenze, verlangt F aber eine
		 * ERHOEHUNG (F > bandMax), gilt er NICHT als kalibriert - er wird ja noch bewegt.
		 */
		public boolean isGroupUncertaintyCalibrated(double varianceRatio, double currentValue, double floorValue, double bandMin, double bandMax) {
			if (Double.isNaN(varianceRatio) || Double.isInfinite(varianceRatio) || varianceRatio <= 0.0)
				return true; // keine gueltige Varianzkomponente -> nichts zu regeln
			boolean inTargetRange = this.isVarianceRatioInDampingCorridor(varianceRatio, bandMin, bandMax);
			boolean atFloor = currentValue <= floorValue && varianceRatio < bandMin;
			return inTargetRange || atFloor;
		}	}

	// ============================================================================================================
	/** Phase 2: individuelle Kalibrierung (sigma_0 je Beobachtung, Bearbeitungsband, Obergrenze, Lage/Hoehe-Split). */
	private final class Phase2_Part {

		/**
		 * PHASE 2: passt die individuelle Messunsicherheit (oh0/osh0/ov0/os0) fuer alle Beobachtungen ALLER 4 Typen an,
		 * deren |logPost| aktuell im Bearbeitungsbereich liegt (siehe computeLogPostBandLowerBound()).
		 *
		 * @return true, sobald fuer IRGENDEINE Beobachtung die Obergrenze erreicht/ueberschritten wurde (siehe
		 * Obergrenzen-Regel) - in diesem Fall wird SOFORT abgebrochen (auch mitten in der Schleife), die uebrigen
		 * Beobachtungen dieses Durchlaufs werden nicht mehr bearbeitet.
		 *
		 * FUNDSTELLEN-HINWEIS: der Auftrag beschreibt eine allgemeine Weiche zwischen "Ur-Formel" (sigma_neu = sigma_alt
		 * + sigma_alt*log10(F)) und "Gedaempfter Achs-Split" (sigma_neu = sigma_alt*[1.0 + log10(F)*Anteil_i*0.4]) fuer
		 * die "Phase-2-Berechnung" allgemein. Real existiert die Achsen-Split-Formel (Anteil_i, Gewichtung ueber
		 * influence_on_position) NUR fuer GNSS-Basislinien im Verteilungsmodus PERCENTAGE (parameter.getDistribution
		 * Type(), siehe distributionToggleButton in Klein_Method_Tab_Builder) - fuer Richtungs-, Strecken- und
		 * Nivellement-Typen (nur EIN sigma_0 pro Beobachtung, keine drei gemeinsam auszuwertenden Achsen) UND fuer GNSS
		 * im Verteilungsmodus NORMAL wird schon ausnahmslos die "Ur-Formel" verwendet (computeIndividualUncertainty(),
		 * unveraendert) - dort gibt es keinen "Anteil_i" zum Auswaehlen. Die neue ComboBox "Daempfungs-Formel:" wirkt
		 * deshalb technisch korrekt NUR innerhalb des GNSS-PERCENTAGE-Zweigs (siehe dort): "Ur-Formel" umgeht dort den
		 * Achsen-Split und die Unterrelaxation vollstaendig (identisches Ergebnis zum NORMAL-Modus), "Gedaempfter
		 * Achs-Split" nutzt weiterhin unveraendert die bestehende Logik. Diese Einschraenkung ist zugleich der in
		 * Klein_Method_Damping_Formula_Type dokumentierte Fundstellen-Hinweis: die Formel-Weiche ueberschneidet sich
		 * damit inhaltlich mit der bereits bestehenden Verteilungsmodus-Auswahl (NORMAL/PERCENTAGE) - beide Schalter sind
		 * UNABHAENGIG voneinander vorhanden, ohne den bestehenden Schalter zu ersetzen oder zu verstecken.
		 *
		 * GEPRUEFT und BEWUSST NICHT umgesetzt - dieselbe Kategorie Anfrage wie die (Punkt 2)/ bereits dokumentierten
		 * Faelle, hier gegen ZWEI konkrete Teilauftraege geprueft:
		 *
		 * (1) "stmt.execute(\"SET TRANSACTION ISOLATION LEVEL READ COMMITTED;\") vor JEDEM Lese-Zugriff" - dieses
		 * SQL-Statement existiert zwar real in HSQLDB, wuerde hier aber NICHTS beheben, was nicht bereits beheben ist:
		 * Klein_Method liest UND schreibt, wie im Absatz oben ausfuehrlich hergeleitet und weiterhin gueltig,
		 * ausschliesslich ueber EINE einzige, geteilte JDBC-Verbindung (SQLManager.getInstance().getDataBase(), "KEINE
		 * eigene, zweite Connection oeffnen!"). Auf derselben Verbindung sieht jede Abfrage ihre eigenen, bereits
		 * committeten Schreibvorgaenge IMMER sofort ("read your own writes") - UNABHAENGIG vom Isolation-Level, da kein
		 * zweiter Reader/keine zweite Session existiert, gegen die sich READ COMMITTED ueberhaupt abgrenzen koennte.
		 * Staerker noch: HSQLDB verlangt fuer SET TRANSACTION ISOLATION LEVEL eine Session OHNE aktive Transaktion - ein
		 * Aufruf VOR jedem einzelnen Lese-Zugriff innerhalb der bestehenden Pro-Achse-Schleife (potenziell tausende
		 * Wiederholungen pro Automatik-Lauf) waere daher nicht nur wirkungslos, sondern zusaetzlicher SQL-Overhead ohne
		 * jeden Sicherheitsgewinn - ein Performance-Rueckschritt fuer ein bereits geloestes Problem.
		 *
		 * (2) "die Verbindung zwingen, ihre internen Java-ResultSet-Caches zu verwerfen" - im Standard-JDBC-API (und in
		 * der HSQLDB-JDBC-Implementierung) existiert kein "ResultSet-Cache" auf Connection-Ebene, der sich gezielt
		 * "verwerfen" liesse; ein `ResultSet` selbst wird pro Abfrage neu erzeugt und enthaelt ohnehin nie mehr als den
		 * zum Abfragezeitpunkt aktuellen Snapshot. Eine erfundene Methode hierfuer waere entweder ein Compile-Fehler
		 * oder, schlimmer, ein stiller Fehlschlag mit falschem Erfolgs-versprechen - exakt der bereits dokumentierte,
		 * bewusst vermiedene Fall.
		 *
		 * Der bereits real existierende, im Auftrag selbst zutreffend beschriebene Teil - COMMIT unmittelbar nach JEDEM
		 * einzelnen Schreibvorgang einer Raumachse - ist bereits an genau den beiden Schreibstellen dieser Methode
		 * umgesetzt (databaseHandler.commit(), siehe GNSS-Zweig und generischer Zweig unten) und bleibt hier unveraendert
		 * bit-exakt bestehen.
		 *
		 * UMGESETZT MIT ZWEI FUNDSTELLEN-HINWEISEN.
		 *
		 * (1) Der Auftrag nennt woertlich "obs.setEnabled(false)" als auszufuehrende Methode. Ein Objekt/eine Methode
		 * dieses Namens existiert in Klein_Method nicht - der real existierende, an dieser Stelle bereits verwendete
		 * Zugriffspfad ist databaseHandler.deactivateGnssObservation( observationId).
		 *
		 * (2) Der Auftrag beschreibt die Weiche als wirksam "bei Erreichen von logPost > 50.0 (bzw. dem 1000er-Shield)" -
		 * das trifft EXAKT auf die Trichter-Eliminierung zu. Eine Anbindung an denselben Toggle haette also nicht nur den
		 * (nicht beauftragten) Effekt gehabt, echte numerische Instabilitaeten im Gleichungssystem zuzulassen - sie
		 * widerspraeche auch dem Wortlaut des Auftrags selbst, der sich ausdruecklich und ausschliesslich auf logPost
		 * bezieht. Der Zaehler "Deaktivierte BL" bleibt bei "nein (empfohlen)" daher nur dann garantiert bei 0, wenn
		 * zusaetzlich auch keine Achsen-Uebersteuerung auftritt - ein in der Praxis seltener, aber real moeglicher
		 * Sonderfall, der hier aus Sicherheitsgruenden bewusst nicht unterdrueckt wird.
		 */
		/** hoechstens so viele Beobachtungen je Kalibrierschritt wegen erreichter Obergrenze deaktivieren - darueber Sperre wie bisher. */
		private static final int CEILING_DEACTIVATIONS_PER_STEP = 10;
		private int ceilingDeactivationsThisStep = 0;

		/**
		 * Obergrenze der individuellen Messunsicherheit erreicht: Die Beobachtung ist praktisch ein grober Fehler. Statt die
		 * ganze Automatik zu sperren, wird sie deaktiviert (rueckgaengig ueber JAG3D "enable"), mit WARNUNG im Protokoll.
		 * false, wenn das Kontingent des Schritts erschoepft ist (dann Sperre wie bisher - viele Treffer deuten auf ein
		 * grundsaetzliches Problem, nicht auf einzelne Ausreisser).
		 */
		private boolean deactivateAtCeiling(Klein_Method_Database_Handler_Interface databaseHandler, boolean gnss, int observationId,
				Klein_Method_Threshold_Type type, List<String> outAdjustmentDetails) throws SQLException {
			if (this.ceilingDeactivationsThisStep >= CEILING_DEACTIVATIONS_PER_STEP)
				return false;
			if (gnss)
				databaseHandler.deactivateGnssObservation(observationId);
			else
				databaseHandler.setTerrestrialObservationEnabled(observationId, false);
			this.ceilingDeactivationsThisStep++;
			if (outAdjustmentDetails != null)
				outAdjustmentDetails.add("WARNUNG: " + type.getDisplayName() + " Beobachtung " + observationId + " erreicht die Obergrenze der individuellen "
						+ "Messunsicherheit - als grober Fehler deaktiviert (statt Sperre der Automatik).");
			return true;
		}

		private boolean performIndividualCalibrationStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails, Map<String, Integer> outProcessedCounts) throws SQLException {
			this.ceilingDeactivationsThisStep = 0;
			int decimalPlaces = parameter.getDecimalPlaces();

			// Stagnations-Waechter - derselbe GLOBALE Max-logPost-Wert (ueber ALLE Typen, siehe getMaxLogPostValue()),
			// der auch fuer den Log-Header und isLogPostBelowThreshold() verwendet wird, wird hier EINMAL PRO AUFRUF
			// dieser Methode (also einmal pro Iteration) in die gleitende Historie aufgenommen. Bleibt er ueber
			// Klein_Method_Default_Values. STAGNATION_WINDOW_SIZE aufeinanderfolgende Iterationen innerhalb von
			// STAGNATION_EPSILON stehen (Plateau), wird der INTERNE, wirksame Trichter-Faktor
			// (parameter.getEffectiveFunnelFactor(), siehe unten) schrittweise geweitet - der vom Anwender in der UI
			// gesetzte Wert (parameter.getFunnelFactor()) bleibt davon vollstaendig unberuehrt.
			double globalMaxLogPostValue = Klein_Method_Functions.this.getMaxLogPostValue(parameter);
			parameter.recordMaxLogPostForStagnationWatch(globalMaxLogPostValue);
			if (parameter.isStagnating()) {
				parameter.widenFunnelFactorStep();
			}

			// siehe Klassenkommentar von Klein_Method_Default_Values.BACKOFF_NORMAL_MODE_WINDOW_DEFAULT fuer die
			// vollstaendige Begruendung, insbesondere das gewollte Nebeneinander mit dem Trichter-Waechter direkt
			// oberhalb - beide reagieren unabhaengig auf dasselbe Stagnations-Signal.
			// Reihenfolge zwingend: ZUERST pruefen, ob ein laufender Zwangswechsel zu Ende ist (isBackoffActive()) - das
			// muss VOR der Ausloese-Pruefung passieren, denn waehrend des Zwangswechsels liefert getDistributionType()
			// bereits NORMAL (siehe activateBackoff()), sodass die Ausloese-Bedingung ("== PERCENTAGE") in dieser Zeit
			// ohnehin nie zutrifft - waere die Reihenfolge vertauscht, wuerde ein exakt in derselben Iteration neu
			// endender und sofort wieder ausloesender Zyklus fehlerhaft uebersprungen.
			boolean justTriggeredBackoffThisIteration = false;
			if (parameter.isBackoffActive()) {
				parameter.incrementBackoffWindowCounter();
				if (!parameter.isBackoffPermanent() && parameter.getBackoffWindowCounter() >= parameter.getBackoffWindowSize()) {
					// Beruhigungsfenster vollstaendig durchlaufen - zurueck auf PERCENTAGE; in der naechsten Iteration
					// wird geprueft, ob die Rueckkehr Max-logPost verschlechtert (dann bleibt NORMAL).
					parameter.deactivateBackoff();
					parameter.setReturnCheckValue(globalMaxLogPostValue);
					parameter.resetNotImproving();
				}
			} else if (parameter.getDistributionType() == Klein_Method_Distribution_Type.PERCENTAGE
					&& !Double.isNaN(parameter.getReturnCheckValue())
					&& globalMaxLogPostValue > parameter.getReturnCheckValue()) {
				// Rueckkehr auf prozentual hat Max-logPost erhoeht -> NORMAL bis zum Ende dieses Durchlaufs
				double before = parameter.getReturnCheckValue();
				parameter.setReturnCheckValue(Double.NaN);
				parameter.activateBackoff();
				parameter.setBackoffPermanent(true);
				parameter.resetNotImproving();
				justTriggeredBackoffThisIteration = true;
				String line = "WAECHTER: Rueckkehr auf prozentual hat Max-logPost erhoeht ("
						+ Klein_Method_Format.format(before, "", parameter.getDecimalPlaces()) + " -> "
						+ Klein_Method_Format.format(globalMaxLogPostValue, "", parameter.getDecimalPlaces())
						+ ") - Verteilung bleibt bis zum Ende dieses Durchlaufs normal.";
				new Klein_Method_Logger().log("Klein_Method: " + line);
				if (outAdjustmentDetails != null)
					outAdjustmentDetails.add(line);
			} else if (parameter.getDistributionType() == Klein_Method_Distribution_Type.PERCENTAGE
					&& (parameter.isStagnating() || parameter.isOscillating() || parameter.isNotImproving())) {
				parameter.setReturnCheckValue(Double.NaN);
				if (parameter.isNotImproving() && !parameter.isStagnating() && !parameter.isOscillating() && outAdjustmentDetails != null)
					outAdjustmentDetails.add("WAECHTER: seit " + parameter.getNotImprovingCount()
							+ " Iterationen kein neuer Bestwert von Max-logPost - Verteilung voruebergehend normal.");
				parameter.resetNotImproving();
				// ZWEI unabhaengige Ausloeser - isStagnating() (echtes Plateau, < 0.05 Aenderung ueber 5 Iterationen)
				// ODER isOscillating() (Saegezahn-Wiederanstieg um >= OSCILLATION_JUMP_THRESHOLD gegenueber dem
				// Fenster-Minimum, siehe Klassenkommentar dort) - im Log trat NUR das zweite Muster auf. Zwangsweise auf
				// NORMAL umschalten (RAM-intern); bei wiederholter Ausloesung im selben Lauf wird das Fenster dabei
				// progressiv verdoppelt (siehe activateBackoff()).
				parameter.activateBackoff();
				justTriggeredBackoffThisIteration = true;
				// feuert AUSSCHLIESSLICH im Moment des tatsaechlichen Umschaltens (nicht bei jeder Iteration WAEHREND des
				// Backoff-Fensters) - sowohl auf der Konsole als auch in der separaten Klein_Method.log-Datei, siehe
				// Klein_Method_Logger.log() (NICHT dasselbe wie das Lauf-Protokoll dieser Sitzung, das ueber
				// outAdjustmentDetails/Klein_Method_Protocol laeuft - beide Wege sind bewusst unabhaengig voneinander).
				new Klein_Method_Logger().log("[Klein_Method W\u00C4CHTER V0.286] Oszillation erkannt! Schalte f\u00FCr "
						+ parameter.getBackoffWindowSize() + " Iterationen autonom auf NORMAL um.");
			}

			else if (!Double.isNaN(parameter.getReturnCheckValue()))
				parameter.setReturnCheckValue(Double.NaN); // Rueckkehr hat nicht geschadet

			// Daempfung im Modus normal (Schalter, Standard aus): pendelt Max-logPost saegezahnartig, wird nur
			// NORMAL_DAMPING_FACTOR des berechneten Schritts geschrieben (prozentual hat bereits eine eigene Daempfung)
			final boolean normalDampingActive = parameter.isNormalDampingActive();
			if (normalDampingActive && outAdjustmentDetails != null)
				outAdjustmentDetails.add("DAEMPFUNG NORMAL: Max-logPost pendelt - Schritt im Modus normal x "
						+ Klein_Method_Format.format(Klein_Method_Default_Values.NORMAL_DAMPING_FACTOR, "", 2) + ".");

			// dieselbe Ampel-Logik wie im vorherigen Kommentarblock beschrieben - GRUEN (STABIL) ausserhalb PERCENTAGE
			// oder ohne jedes Signal, GELB (TEILWEISE_INSTABIL) waehrend eines laufenden Backoff-Fensters ODER bei einem
			// Vorstufen-Anstieg >= OSCILLATION_WARNING_THRESHOLD, ROT (INSTABIL) NUR in der Iteration des tatsaechlichen
			// Ausloesens (siehe justTriggeredBackoffThisIteration oben) - rein lesend, greift NICHT in den Rechenkern
			// ein, nur fuer die Anzeige in Klein_Method_Tab_Builder. qualifiziert ueber Klein_Method_Parameter, seit das
			// Enum als Nested Enum in dieser Klasse lebt (keine eigene Datei/Import mehr).
			Klein_Method_Parameter.Klein_Method_Oscillation_Status oscillationStatus;
			if (parameter.getDistributionType() != Klein_Method_Distribution_Type.PERCENTAGE) {
				oscillationStatus = Klein_Method_Parameter.Klein_Method_Oscillation_Status.STABIL;
			} else if (justTriggeredBackoffThisIteration) {
				oscillationStatus = Klein_Method_Parameter.Klein_Method_Oscillation_Status.INSTABIL;
			} else if (parameter.isBackoffActive()
					|| parameter.getOscillationRiseFromMin() >= Klein_Method_Default_Values.OSCILLATION_WARNING_THRESHOLD) {
				oscillationStatus = Klein_Method_Parameter.Klein_Method_Oscillation_Status.TEILWEISE_INSTABIL;
			} else {
				oscillationStatus = Klein_Method_Parameter.Klein_Method_Oscillation_Status.STABIL;
			}
			parameter.setOscillationStatus(oscillationStatus);

			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
				// JETZT: jeder Typ bekommt seinen EIGENEN Bandbereich, verankert am EIGENEN aktuellen Max-logPost - ein
				// Typ, dessen eigener Wert schon unter seinem Grenzwert liegt, wird komplett uebersprungen und NIE wieder
				// angefasst, unabhaengig davon, wie hoch andere Typen noch stehen.
				double typeMaxLogPostValue = parameter.getEvaluationValue(type);
				double thresholdValue = parameter.getThresholdValue(type);

				if (typeMaxLogPostValue < thresholdValue)
					continue;

				// BEWUSST NICHT umgesetzt - mathematisch unerreichbarer, toter Code. Jeder logPost-Grenzwert-Typ hat
				// einen HART EINPROGRAMMIERTEN, erzwungenen Mindestwert von 2.45
				// (Klein_Method_Threshold_Type.getMinimumAllowedValue(), von Klein_Method_Parameter.setThresholdValue()
				// per Math.max() IMMER durchgesetzt - kann vom Anwender in der UI nicht unterschritten werden).

				// Obergrenze jetzt PRO TYP (0.999 Standard, 0.499 Nivellement, jeweils in der zum Typ passenden Einheit -
				// m bei Strecken/ Nivellement, gon bei Richtungstypen). KEINE Untergrenze mehr.
				double ceilingValue = Klein_Method_Default_Values.getIndividualUncertaintyCeiling(type);
				String unit = Klein_Method_Default_Values.getIndividualUncertaintyUnit(type);

				// derselbe physikalische Boden, den performFloorEnforcementCompression() als Hard-Clamp-Ziel schreibt
				// (siehe dort), wird hier PRO TYP einmalig mitgefuehrt - die untenstehende
				// isObservationClamped()-Pruefung braucht ihn, um zwischen "Rueckfederung nach unten" (weiterhin
				// blockiert) und "zusaetzliche Daempfung nach oben" (jetzt erlaubt) zu unterscheiden.
				double clampFloorValueForType = Klein_Method_Default_Values.getIndividualUncertaintyCompressionFloor(type);

				// getEffectiveFunnelFactor() statt getFunnelFactor() - liefert den vom Stagnations-Waechter (ggf.)
				// geweiteten Faktor, sonst unveraendert den UI-Wert (siehe Kommentar am Methodenanfang).
				// NIVELLEMENT_LOG_POST bekommt seinen EIGENEN, eng begrenzten Trichter-Faktor
				// (parameter.getNivellementFunnelFactor(), Standard 0.05, Bereich [0.01; 0.10]) statt des allgemeinen -
				// faengt systematische Schlepp-fehler in Nivellementszuegen isoliert ab, ohne gesunde Hoehenzuege zu
				// verschmieren. Der Stagnations-Waechter (getEffectiveFunnelFactor()) bleibt bewusst AUSSCHLIESSLICH fuer
				// die anderen Typen wirksam - eine automatische Weitung des enge Nivellement-Bandes wuerde genau den
				// beabsichtigten Schutz vor Schleppfehlern wieder aufheben.
				double funnelFactorForType = (type == Klein_Method_Threshold_Type.NIVELLEMENT_LOG_POST)
						? parameter.getNivellementFunnelFactor()
						: parameter.getEffectiveFunnelFactor();
				double logBasedBandLowerBound = this.computeLogPostBandLowerBound(typeMaxLogPostValue, thresholdValue, funnelFactorForType);

				// zusaetzlich zum bestehenden, logarithmischen Band wird IMMER auch die vom Anwender vorgegebene LINEARE
				// 0.20-Nachbarschaft um Max_logPost einbezogen (Formel
				// exakt: Untergrenze = Max_logPost - 0.20, einstellbar ueber parameter.getCollectiveDelta()). Die
				// TATSAECHLICH verwendete Untergrenze ist das Minimum (= der jeweils GROSSZUEGIGERE, also mehr
				// Beobachtungen einschliessende Wert) aus beiden Berechnungen - das bestehende, gehaertete Band wird
				// damit NIEMALS verkleinert, nur bei Bedarf zusaetzlich erweitert. Siehe Klassenkommentar bei
				// Klein_Method_Parameter.COLLECTIVE_DELTA_DEFAULT fuer die vollstaendige Begruendung dieser bewussten
				// additiven statt ersetzenden Umsetzung.
				double collectiveDeltaBandLowerBound = typeMaxLogPostValue - parameter.getCollectiveDelta();
				double bandLowerBound = Math.min(logBasedBandLowerBound, collectiveDeltaBandLowerBound);
				// dieselbe Ausnahme wie in computeLogPostBandLowerBound(): sobald die (jetzt ggf. durch den
				// Delta-Trichter zusaetzlich abgesenkte) Untergrenze bereits auf/unter den Grenzwert faellt, wird sie auf
				// den Grenzwert gesetzt. statt fest auf den Grenzwert auf die einstellbare Trichter-Untergrenze (Standard
				// 2.30, nie ueber dem Grenzwert) - Beobachtungen knapp unter dem Grenzwert werden damit schon
				// mitkorrigiert.
				if (bandLowerBound <= thresholdValue)
					bandLowerBound = Math.min(parameter.getFunnelFloorLogPost(), thresholdValue);

				// GNSS-Typen (GNSS1D/2D/3D) haben KEINEN einzelnen sigma_0 und KEINE "distance_0" - sie muessen ueber den
				// GNSS-eigenen, achsen-bewussten Pfad laufen.
				boolean isGnssType = type == Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST
						|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST
						|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST;

				if (isGnssType) {
					List<Klein_Method_Observation_Uncertainty_Row> gnssRows =
							databaseHandler.readGnssObservationsForIndividualCalibration(type, bandLowerBound, typeMaxLogPostValue);

					if (outProcessedCounts != null && !gnssRows.isEmpty())
						outProcessedCounts.merge(type.getDisplayName(), gnssRows.size(), Integer::sum);

					// der Gewichtungsschluessel (geometrische Basislinienkomponenten x0/y0/z0) ist vollstaendig ERSETZT
					// durch eine rein sigma-basierte Formel - PERCENTAGE-Modus braucht dafuer weiterhin ALLE DREI Achsen
					// EINER Basislinie gemeinsam (Gewicht = Verhaeltnis der A-priori-Varianzen), dafuer werden die 3 pro
					// Basislinie gelieferten Zeilen (siehe readGnssObservationsForIndividualCalibration()) zunaechst nach
					// observationId gruppiert.
					Map<Integer, List<Klein_Method_Observation_Uncertainty_Row>> rowsByBaseline = new LinkedHashMap<Integer, List<Klein_Method_Observation_Uncertainty_Row>>();
					for (Klein_Method_Observation_Uncertainty_Row row : gnssRows)
						rowsByBaseline.computeIfAbsent(row.getObservationId(), k -> new ArrayList<Klein_Method_Observation_Uncertainty_Row>()).add(row);

					boolean isPercentageMode = parameter.getDistributionType() == Klein_Method_Distribution_Type.PERCENTAGE;

					// fuer die ERSTEN 5 Basislinien dieses Aufrufs werden die real berechneten Achsen-Gewichte
					// (Anteil_X/Y/Z) UND die daraus resultierenden neuen Werte unzensiert in outAdjustmentDetails (->
					// Klein_Method_Protocol, dort bei Stufe LONG sichtbar) geschrieben - unabhaengig davon, ob der echte
					// influence_on_position-Schluessel oder das Sicherheitsnetz (useEqualThirds, exakt 1/3 bei allen 3
					// Achsen) gegriffen hat - GENAU DAS unterscheidet naemlich einen echten, geometrisch begruendeten
					// Drei-Achsen-Split von einer zufaelligen Drittelung, und ist damit direkt nachrechenbar statt
					// behauptet.

					// Lotrichtung EINMAL je Aufruf bestimmen.
					boolean isLageHoeheMode = isPercentageMode && !rowsByBaseline.isEmpty()
							&& parameter.getDampingFormulaType() == Klein_Method_Damping_Formula_Type.LAGE_HOEHE_SPLIT_V366;
					double[] localUpVector = isLageHoeheMode ? this.computeLocalUpVector(databaseHandler, outAdjustmentDetails, decimalPlaces) : null;
					// eigene Lotrichtung JE BASISLINIE aus ihrem Mittelpunkt; die Netzmitte (localUpVector) dient nur als
					// Rueckfall. Umfang der MATHE-DEBUG-Zeilen (aus / Stichprobe 5 / alle) und Zaehler.
					Klein_Method_Parameter.Klein_Method_Math_Debug_Mode mathDebugMode = parameter.getMathDebugMode();
					int[] mathDebugCounter = new int[] { 0 };
					boolean splitNormalization = parameter.isSplitNormalizationEffective();
					Map<Integer, double[]> baselineUpVectors = (isLageHoeheMode && localUpVector != null)
							? this.computeBaselineUpVectors(databaseHandler, localUpVector, outAdjustmentDetails)
							: new LinkedHashMap<Integer, double[]>();

					for (List<Klein_Method_Observation_Uncertainty_Row> baselineRows : rowsByBaseline.values()) {
						// harte Kappung GANZ AM ANFANG, noch VOR jeder Basiswert-/Gewichtsberechnung - unabhaengig vom
						// Verteilungsmodus (NORMAL wie PERCENTAGE nutzen denselben Selektions-Trichter, siehe
						// computeLogPostBandLowerBound()). Alle 3 Achsen-Zeilen einer Basislinie tragen denselben
						// |p_post|-Wert (derselbe absLogPost der ganzen Basislinie) - eine Stichprobe genuegt.
						double baselineAbsLogPost = baselineRows.get(0).getAbsoluteLogPostValue();
						if (baselineAbsLogPost > Klein_Method_Default_Values.GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD) {
							// alle 3 Zeilen derselben Basislinie tragen dieselbe observationId.
							int outlierObservationId = baselineRows.get(0).getObservationId();
							// Bei "nein (empfohlen)" (Default) bleibt die Basislinie zu 100% AKTIV - KEIN
							// databaseHandler.deactivateGnssObservation()-Aufruf, KEIN "continue": die Verarbeitung
							// faellt bewusst durch in die normale Basiswert-/Gewichtsberechnung unten, damit die
							// Basislinie tatsaechlich "ueber die Kaskade bis zum Boden gedaempft" wird statt unveraendert
							// stehen zu bleiben - exakt wie beauftragt. Der Zaehler "Deaktivierte BL"
							// (Klein_Method_Parameter.getDisabledGnssBaselineCount(), siehe Klein_Method_Tab_Builder)
							// bleibt dadurch automatisch bei 0, da er ausschliesslich reale enable=false-Datensaetze in
							// der HSQLDB zaehlt (readGnssBaselineDeactivationCounts()) - ohne echten
							// Deaktivierungs-Aufruf gibt es dort nichts zu zaehlen.
							if (parameter.isDeactivateGnssOutlierEnabled()) {
								databaseHandler.deactivateGnssObservation(outlierObservationId);
								if (outAdjustmentDetails != null) {
									outAdjustmentDetails.add("WARNUNG: GNSS-Basislinie " + outlierObservationId
											+ " wegen extremem logPost (" + Klein_Method_Format.format(baselineAbsLogPost, "", decimalPlaces)
											+ " > " + Klein_Method_Format.format(Klein_Method_Default_Values.GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD, "", decimalPlaces)
											+ ") als grobe Fehlmessung automatisch deaktiviert.");
								}
								continue;
							} else if (outAdjustmentDetails != null) {
								outAdjustmentDetails.add("HINWEIS: GNSS-Basislinie " + outlierObservationId
										+ " ueberschreitet die Trichter-Kappungsgrenze (" + Klein_Method_Format.format(baselineAbsLogPost, "", decimalPlaces)
										+ " > " + Klein_Method_Format.format(Klein_Method_Default_Values.GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD, "", decimalPlaces)
										+ "), bleibt gemaess Toggle \"Deaktivieren BL: nein\" jedoch AKTIV und wird regulaer weiter gedaempft.");
							}
						}

						Map<Klein_Method_Observation_Uncertainty_Row, Double> baseValues = new LinkedHashMap<Klein_Method_Observation_Uncertainty_Row, Double>();
						double sumOfInfluenceMagnitudes = 0.0;
						for (Klein_Method_Observation_Uncertainty_Row row : baselineRows) {
							double baseValue;
							if (row.hasNoIndividualUncertainty()) {
								// Fall 1, GNSS: dieselbe Basis-Formel wie im generischen Fall; row.getDistance() =
								// |Komponente dieser Achse| wie in JAG3D (GNSSBaseline*Group.getStdB/getStdC).
								double[] sigmaRaw = databaseHandler.readGroupUncertainty(row.getGroupId());
								double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], Klein_Method_Default_Values.getSigmaAUnit(type));
								double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], Klein_Method_Default_Values.getSigmaBUnit(type));
								double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], Klein_Method_Default_Values.getSigmaCUnit(type));
								baseValue = this.computeIndividualUncertaintyBasis(type, sigmaA, sigmaB, sigmaC, row.getDistance());
							}
							else {
								// Fall 2, GNSS: bestehenden individuellen Wert DIESER EINEN Achse
								// direkt weiterverwenden - KEINE Vermischung mit den anderen Achsen.
								baseValue = Klein_Method_Functions.this.fromDatabaseValue(row.getCurrentIndividualUncertainty(), unit);
							}
							baseValues.put(row, baseValue);
							// der Gewichtungsschluessel (baseValue^2, also die A-PRIORI-Varianz) kollabiert bei
							// baugleichen Start-Sigmas alle drei Achsen IMMER auf exakt 1/3 - unabhaengig davon, wie
							// unterschiedlich gross der TATSAECHLICHE, nach der Ausgleichung verbleibende Widerspruch je
							// Achse ist. Schluessel-Datengrundlage: der echte A-POSTERIORI-Achsenwert
							// "influence_on_position_<achse>" (bestaetigte Spalte, siehe
							// row.getInfluenceOnPositionAxis()) - differenziert auch bei identischen Start-Sigmas nach
							// dem tatsaechlichen geometrischen Widerspruch im Raum.
							// STATT des Quadrats jetzt der LINEARE Betrag (Math.abs). Mathematischer Hinweis (unabhaengig
							// vom Auftrag): das Quadrat UEBERBETONT die staerkste Achse STAERKER als der lineare Betrag -
							// die Umstellung auf |ep| verteilt die Korrektur DAHER GLEICHMAESSIGER auf alle drei Achsen
							// (naeher an 1/3), nicht staerker konzentriert auf die am staerksten gestoerte Achse.
							// Trotzdem exakt wie beauftragt umgesetzt.
							double influenceAxis = Math.abs(row.getInfluenceOnPositionAxis());
							sumOfInfluenceMagnitudes += influenceAxis;
						}

						// Sicherheitsnetz gleichmaessige Drittelung, damit niemals durch 0 geteilt wird (z.B. alle 3
						// EP-Achsenwerte == 0, etwa direkt nach dem allerersten Ausgleichungsdurchlauf, bevor ueberhaupt
						// ein A-posteriori-Widerspruch existiert).
						boolean useEqualThirds = sumOfInfluenceMagnitudes <= 0.0;

						// ALLE drei Achsenwerte dieser Basislinie werden zuerst BERECHNET, aber noch NICHT geschrieben -
						// erst nach der Ausreisser-Pruefung unten (Punkt 2 des Auftrags) steht fest, ob ueberhaupt
						// geschrieben werden darf.
						Map<Klein_Method_Observation_Uncertainty_Row, Double> newValues = new LinkedHashMap<Klein_Method_Observation_Uncertainty_Row, Double>();
						double[] baselineUp = baselineUpVectors.getOrDefault(baselineRows.get(0).getObservationId(), localUpVector);
						boolean lageHoeheApplied = isLageHoeheMode && baselineUp != null && baselineRows.size() == 3
								&& this.computeLageHoeheSplit(baselineRows, baseValues, baselineUp, newValues,
										this.allowMathDebugLine(mathDebugMode, mathDebugCounter) ? outAdjustmentDetails : null, unit, decimalPlaces, splitNormalization);
						for (Klein_Method_Observation_Uncertainty_Row row : baselineRows) {
							if (lageHoeheApplied)
								break;
							double baseValue = baseValues.get(row);
							double newValue;
							if (isPercentageMode && parameter.getDampingFormulaType() == Klein_Method_Damping_Formula_Type.UR_FORMEL_V100) {
								// "Ur-Formel" gewaehlt - direkte, ungedaempfte 1:1-Aufgewichtung OHNE Achsen-Gewichtung
								// (Anteil_i) und OHNE Unterrelaxation, exakt dieselbe Formel wie im NORMAL-Modus (siehe
								// else-Zweig unten):
								// sigma_neu = sigma_alt + sigma_alt * log10(F) Bewusst NICHT als eigene neue Formel
								// implementiert, sondern als direkter Aufruf derselben, bereits bestehenden Methode -
								// "Ur-Formel" und "NORMAL-Modus-Formel" SIND mathematisch identisch (siehe
								// Fundstellen-Hinweis im Klassenkommentar von performIndividual CalibrationStep() weiter
								// oben).
								newValue = this.computeIndividualUncertainty(baseValue, row.getAbsoluteLogPostValue());
							}
							else if (isPercentageMode) {
								// sigma_..._neu = (sigma_..._alt + sigma_..._alt*log10(F)) *
								// (|influence_on_position_..._achse| / Summe_|influence_on_position|)
								// F = |p_post| DIESER Achse (row.getAbsoluteLogPostValue(), fuer alle 3 Achsen derselben
								// Basislinie identisch) - der erste Faktor ist weiterhin exakt
								// computeIndividualUncertainty(baseValue, F), der zweite Faktor ist das Gewicht dieser
								// Achse am tatsaechlichen A-posteriori-Gesamtwiderspruch (influence_on_position, siehe
								// Klassenkommentar von row) - loest die 1/3-Blockade bei symmetrischen Start-Sigmas auf,
								// weil influence_on_position ein ECHTES Ausgleichungsergebnis ist und nicht (wie die
								// A-priori-Sigmas) je Achse identisch startet. Seit linear (Betrag) statt quadratisch
								// gewichtet. linearer Betrag statt Quadrat (siehe Kommentar oben bei
								// sumOfInfluenceMagnitudes).
								double influenceAxis = Math.abs(row.getInfluenceOnPositionAxis());
								double weight = useEqualThirds ? (1.0 / 3.0) : influenceAxis / sumOfInfluenceMagnitudes;
								// normierte Aufteilung (Option) - Korrektur der einfachen Anpassung nur VERTEILEN (Faktor
								// = 1 + log10(F) * 3 * Anteil) statt mit dem Anteil zu multiplizieren; bei gleichen
								// Anteilen identisch mit der einfachen Anpassung.
								double rawPercentageValue = splitNormalization
										? baseValue * Math.max(0.1, 1.0 + Math.log10(row.getAbsoluteLogPostValue()) * 3.0 * weight)
										: this.computeIndividualUncertainty(baseValue, row.getAbsoluteLogPostValue()) * weight;

								// Gedaempfter Schritt ("successive under-relaxation"): der tatsaechlich geschriebene Wert
								// bewegt sich nur um den Anteil GNSS_PERCENTAGE_RELAXATION_FACTOR in Richtung des frisch
								// berechneten (rohen) Werts - baseValue ist dabei IMMER der korrekte Ausgangspunkt (Fall
								// 1: Gruppenbasis, Fall 2: der zuletzt tatsaechlich geschriebene individuelle Wert dieser
								// Achse).
								double relaxationFactor = Klein_Method_Default_Values.GNSS_PERCENTAGE_RELAXATION_FACTOR;
								newValue = baseValue + relaxationFactor * (rawPercentageValue - baseValue);
							}
							else {
								// NORMAL-Modus: unveraendertes Verhalten - jede Achse vollstaendig unabhaengig korrigiert
								// (kein Gewichtungsschluessel).
								newValue = this.computeIndividualUncertainty(baseValue, row.getAbsoluteLogPostValue());
								if (normalDampingActive) // Saegezahn: nur einen Teil des Schritts schreiben
									newValue = baseValue + Klein_Method_Default_Values.NORMAL_DAMPING_FACTOR * (newValue - baseValue);
							}
							newValues.put(row, newValue);
						}

						// MATHE-DEBUG (siehe Kommentar vor der Basislinien-Schleife oben): nur im PERCENTAGE-Modus
						// sinnvoll (im NORMAL-Modus gibt es keinen Gewichtungsschluessel - jede Achse wird unabhaengig
						// ueber computeIndividualUncertainty() korrigiert, "Anteil" waere dort immer 1.0 und ohne
						// Aussagekraft).
						if (isPercentageMode && parameter.getDampingFormulaType() == Klein_Method_Damping_Formula_Type.GEDAEMPFTER_ACHS_SPLIT_V333
								&& outAdjustmentDetails != null && this.allowMathDebugLine(mathDebugMode, mathDebugCounter)) {
							int debugObservationId = baselineRows.get(0).getObservationId();
							StringBuilder debugLine = new StringBuilder();
							debugLine.append("[Klein_Method MATHE-DEBUG] Basislinie ").append(debugObservationId)
									.append(" (").append(type.getDisplayName()).append("), Gewichts-Quelle: ")
									.append(useEqualThirds ? "Drittelung-Sicherheitsnetz (sumOfInfluenceMagnitudes<=0)" : "influence_on_position-Schluessel");
							for (Klein_Method_Observation_Uncertainty_Row row : baselineRows) {
								double influenceAxis = Math.abs(row.getInfluenceOnPositionAxis());
								double weight = useEqualThirds ? (1.0 / 3.0) : influenceAxis / sumOfInfluenceMagnitudes;
								debugLine.append(" | Anteil_").append(row.getAxis()).append("=").append(Klein_Method_Format.format(weight, "", decimalPlaces))
										.append(" (EP_").append(row.getAxis()).append("=").append(Klein_Method_Format.format(influenceAxis, unit, decimalPlaces))
										.append(", sigma_neu_").append(row.getAxis()).append("=").append(Klein_Method_Format.format(newValues.get(row), unit, decimalPlaces)).append(")");
							}
							// zusaetzlich die EP-Werte MIT Vorzeichen in mm (4 Nachkommastellen), der euklidische Betrag
							// |EP| und der Winkel des EP-Vektors zu jeder Achse (0-180 Grad, aus dem vorzeichenbehafteten
							// Richtungskosinus).
							double sumOfSquares = 0.0;
							for (Klein_Method_Observation_Uncertainty_Row row : baselineRows)
								sumOfSquares += row.getInfluenceOnPositionAxis() * row.getInfluenceOnPositionAxis();
							double epMagnitude = Math.sqrt(sumOfSquares);
							debugLine.append(" || EP-Vektor:");
							for (Klein_Method_Observation_Uncertainty_Row row : baselineRows) {
								double signedMm = row.getInfluenceOnPositionAxis() * 1000.0;
								debugLine.append(" EPv_").append(row.getAxis()).append("=").append(signedMm >= 0.0 ? "+" : "")
										.append(Klein_Method_Format.format(signedMm, "mm", 4));
							}
							debugLine.append(" | Betrag=").append(Klein_Method_Format.format(epMagnitude * 1000.0, "mm", 4));
							for (Klein_Method_Observation_Uncertainty_Row row : baselineRows) {
								String angleText = "k.A.";
								if (epMagnitude > 0.0) {
									double cosine = Math.max(-1.0, Math.min(1.0, row.getInfluenceOnPositionAxis() / epMagnitude));
									angleText = Klein_Method_Format.format(Math.toDegrees(Math.acos(cosine)), "Grad", 3);
								}
								debugLine.append(" | Winkel_").append(row.getAxis()).append("=").append(angleText);
							}
							outAdjustmentDetails.add(debugLine.toString());
						}

						// Punkt 2 des NUR im Prozentual-Modus (dort kann eine extreme EP-Schieflage zwischen den Achsen
						// eine Achse Richtung 0.0 mm druecken, was in der naechsten Ausgleichung ein numerisch
						// unendliches Gewicht ergaebe) - pruefe ALLE drei berechneten Werte dieser Basislinie GEMEINSAM,
						// bevor irgendetwas geschrieben wird: ueberschreitet IRGENDEINE Achse die harte Obergrenze, oder
						// faellt IRGENDEINE Achse unter die numerische Untergrenze, wird die Gewichtsmodifikation fuer
						// die GESAMTE Basislinie verworfen und die Basislinie komplett deaktiviert (enable=false) - statt
						// (wie die bestehende, generische isIndividualUncertaintyCeilingExceeded()- Pruefung unten) die
						// GESAMTE Berechnung abzubrechen.
						boolean axisOutlierDetected = false;
						if (isPercentageMode) {
							double outlierCeiling = LengthUnit.MILLIMETER.toMeter(Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_CEILING_MM);
							double outlierFloor = LengthUnit.MILLIMETER.toMeter(Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_FLOOR_MM);
							for (double candidateValue : newValues.values()) {
								if (candidateValue > outlierCeiling || candidateValue < outlierFloor) {
									axisOutlierDetected = true;
									break;
								}
							}
						}

						// die Achsen-Uebersteuerung haengt jetzt am Schalter "Deaktivieren BL". Bei "nein" bleibt die
						// Basislinie AKTIV; die drei Achsenwerte werden in [GNSS_AXIS_OUTLIER_FLOOR_MM; _CEILING_MM]
						// geklemmt (Untergrenze numerisch zwingend - sonst unendliches Gewicht) und normal geschrieben,
						// mit HINWEIS im Protokoll.
						if (axisOutlierDetected && !parameter.isDeactivateGnssOutlierEnabled()) {
							double clampCeiling = LengthUnit.MILLIMETER.toMeter(Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_CEILING_MM);
							double clampFloor = LengthUnit.MILLIMETER.toMeter(Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_FLOOR_MM);
							for (Map.Entry<Klein_Method_Observation_Uncertainty_Row, Double> entry : newValues.entrySet())
								entry.setValue(Math.max(clampFloor, Math.min(entry.getValue(), clampCeiling)));
							if (outAdjustmentDetails != null) {
								outAdjustmentDetails.add("HINWEIS: GNSS-Basislinie " + baselineRows.get(0).getObservationId()
										+ " Achsen-Uebersteuerung - Werte auf [" + Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_FLOOR_MM + "; "
										+ Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_CEILING_MM + "] mm geklemmt, Basislinie bleibt gemaess "
										+ "\"Deaktivieren BL: nein\" AKTIV.");
							}
							axisOutlierDetected = false;
						}

						if (axisOutlierDetected) {
							// alle 3 Zeilen derselben Basislinie tragen dieselbe observationId
							// (siehe readGnssObservationsForIndividualCalibration()) - beliebige
							// der drei Zeilen liefert daher dieselbe, korrekte Basislinien-ID.
							int deactivatedObservationId = baselineRows.get(0).getObservationId();
							databaseHandler.deactivateGnssObservation(deactivatedObservationId);
							if (outAdjustmentDetails != null) {
								outAdjustmentDetails.add("WARNUNG: GNSS-Basislinie " + deactivatedObservationId
										+ " wegen Achsen-Uebersteuerung (Obergrenze/Untergrenze verletzt) automatisch deaktiviert.");
							}
							continue; // naechste Basislinie - die verworfenen newValues werden NICHT geschrieben
						}

						for (Map.Entry<Klein_Method_Observation_Uncertainty_Row, Double> entry : newValues.entrySet()) {
							Klein_Method_Observation_Uncertainty_Row row = entry.getKey();
							double newValue = entry.getValue();
							double baseValue = baseValues.get(row);

							// zaehlt JEDE GNSS-Achse, die diesen finalen Verarbeitungs-Zyklus erreicht - der Nenner der
							// Diagnose-Quote "Reelle EP-Verteilungsrate:", siehe Feldkommentar in
							// Klein_Method_Parameter.totalGnssCalculations.
							parameter.incrementTotalGnssCalculations();

							if (this.isIndividualUncertaintyCeilingExceeded(newValue, ceilingValue)) {
								// Obergrenze erreicht: grober Fehler - Basislinie deaktivieren statt die Automatik zu sperren
								// (hoechstens CEILING_DEACTIVATIONS_PER_STEP je Schritt, danach wie bisher Sperre)
								if (this.deactivateAtCeiling(databaseHandler, true, row.getObservationId(), type, outAdjustmentDetails)) {
									break;
								}
								parameter.setIndividualUncertaintyCeilingExceededType(type);
								return true; // sofortiger Abbruch, siehe Obergrenzen-Regel
							}

							if (parameter.isObservationClamped(buildClampRegistryKey(row.getObservationId(), row.getAxis()))
									&& newValue < clampFloorValueForType) {
								if (outAdjustmentDetails != null) {
									outAdjustmentDetails.add("SCHREIB-SPERRE (Hard-Clamp-Boden aktiv, Rueckfederung nach unten blockiert) sigma_0 " + type.getDisplayName()
											+ " Achse " + row.getAxis() + " Beobachtung " + row.getObservationId()
											+ ": Neuberechnung blockiert, Wert bleibt auf dem Boden fixiert.");
								}
								continue;
							}

							// Zurueckschreiben AUSSCHLIESSLICH in die Spalte DIESER EINEN Achse
							// (row.getAxis()) - kein Math.max(), keine Vermischung der Achsen.
							databaseHandler.writeGnssIndividualUncertainty(row.getObservationId(), row.getAxis(), Klein_Method_Functions.this.toDatabaseValue(newValue, unit));

							// NUR HIER, direkt nach dem tatsaechlich erfolgten Schreibvorgang UND NUR im
							// PERCENTAGE-Modus, zaehlt dies als "reeller" prozentualer Achsen-Split - der Zaehler der
							// Diagnose-Quote (siehe Feldkommentar in
							// Klein_Method_Parameter.prozentualSplittedCalculations). Im NORMAL-Modus (kein
							// Gewichtungsschluessel) oder bei durch die Schreib-Sperre blockierten Achsen (siehe continue
							// oben, wird dort NICHT erreicht) zaehlt dieser Fall bewusst NICHT mit.
							if (isPercentageMode)
								parameter.incrementProzentualSplittedCalculations();

							if (outAdjustmentDetails != null) {
								outAdjustmentDetails.add("sigma_0 " + type.getDisplayName() + " Achse " + row.getAxis()
										+ (isPercentageMode ? " (prozentual)" : "") + " Beobachtung " + row.getObservationId() + ": "
										+ Klein_Method_Format.format(baseValue, unit, decimalPlaces) + " -> " + Klein_Method_Format.format(newValue, unit, decimalPlaces));
							}
						}
					}
					continue; // dieser Typ ist mit dem GNSS-Pfad fertig behandelt
				}

				List<Klein_Method_Observation_Uncertainty_Row> rows =
						databaseHandler.readObservationsForIndividualCalibration(type, bandLowerBound, typeMaxLogPostValue);

				if (outProcessedCounts != null && !rows.isEmpty())
					outProcessedCounts.merge(type.getDisplayName(), rows.size(), Integer::sum);

				for (Klein_Method_Observation_Uncertainty_Row row : rows) {
					double baseValue;
					if (row.hasNoIndividualUncertainty()) {
						// Fall 1: kein individueller Wert -> Basis per Varianzfortpflanzung
						// aus den (bereits in Phase 1 kalibrierten) Gruppenparametern DIESER
						// Gruppe UND der Entfernung DIESER Beobachtung berechnen (siehe
						// computeIndividualUncertaintyBasis() - bestaetigt anhand des
						// JAG3D-Forenbeitrags software.applied-geodesy.org/forum/?id=13047).
						// Rohwerte aus der DB (Radiant/Meter/unitless) zuerst in unsere
						// Arbeitseinheit (mgon/mm/ppm) umrechnen - computeIndividualUncertaintyBasis()
						// erwartet genau diese Skalierung.
						double[] sigmaRaw = databaseHandler.readGroupUncertainty(row.getGroupId());
						double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], Klein_Method_Default_Values.getSigmaAUnit(type));
						double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], Klein_Method_Default_Values.getSigmaBUnit(type));
						double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], Klein_Method_Default_Values.getSigmaCUnit(type));
						baseValue = this.computeIndividualUncertaintyBasis(type, sigmaA, sigmaB, sigmaC, row.getDistance());
					}
					else {
						// Fall 2: bestehenden individuellen Wert direkt weiterverwenden -
						// auch dieser liegt in der DB als Rohwert (Radiant/Meter) vor
						baseValue = Klein_Method_Functions.this.fromDatabaseValue(row.getCurrentIndividualUncertainty(), unit);
					}

					double newValue = this.computeIndividualUncertainty(baseValue, row.getAbsoluteLogPostValue());
					if (normalDampingActive) // Saegezahn: nur einen Teil des Schritts schreiben
						newValue = baseValue + Klein_Method_Default_Values.NORMAL_DAMPING_FACTOR * (newValue - baseValue);

					if (this.isIndividualUncertaintyCeilingExceeded(newValue, ceilingValue)) {
						// Obergrenze erreicht: grober Fehler - Beobachtung deaktivieren statt die Automatik zu sperren
						if (this.deactivateAtCeiling(databaseHandler, false, row.getObservationId(), type, outAdjustmentDetails))
							continue;
						parameter.setIndividualUncertaintyCeilingExceededType(type);
						return true; // sofortiger Abbruch, siehe Obergrenzen-Regel
					}

					// Interne HSQLDB-Schreib-Sperre, ASYMMETRISCH - siehe ausfuehrliche Begruendung im GNSS-Zweig weiter
					// oben: eine Rueckfederung NACH UNTEN (newValue < Boden) bleibt knallhart blockiert, eine WEITERE
					// Daempfung NACH OBEN (newValue >= Boden) wird ab jetzt ausdruecklich zugelassen.
					if (parameter.isObservationClamped(buildClampRegistryKey(row.getObservationId()))
							&& newValue < clampFloorValueForType) {
						if (outAdjustmentDetails != null) {
							outAdjustmentDetails.add("SCHREIB-SPERRE (Hard-Clamp-Boden aktiv, Rueckfederung nach unten blockiert) sigma_0 " + type.getDisplayName()
									+ " Beobachtung " + row.getObservationId() + ": Neuberechnung blockiert, Wert bleibt auf dem Boden fixiert.");
						}
						continue;
					}

					// Vor dem Schreiben wieder zurueck in die JAG3D-Speichereinheit umrechnen
					databaseHandler.writeIndividualUncertainty(row.getObservationId(), Klein_Method_Functions.this.toDatabaseValue(newValue, unit));

					if (outAdjustmentDetails != null) {
						outAdjustmentDetails.add("sigma_0 " + type.getDisplayName() + " Beobachtung " + row.getObservationId() + ": "
								+ Klein_Method_Format.format(baseValue, unit, decimalPlaces) + " -> " + Klein_Method_Format.format(newValue, unit, decimalPlaces));
					}
				}
			}
			return false;
		}
		/**
		 * laeuft GENAU EINMAL pro Automatiklauf, exakt an der Stelle in recompute(), an der die Beobachtungs-Automatik
		 * von INDIVIDUAL_CALIBRATION nach DONE wechselt (siehe Aufrufstelle) - NICHT bei den beiden anderen
		 * DONE-Uebergaengen der Festpunktanalyse (Punkte sind eine fachlich andere Groesse mit eigenem
		 * Untergrenzen-Mechanismus, Sprungwert/Untergrenze der Festpunktanalyse, dort bewusst unangetastet). Da
		 * recompute() diesen INDIVIDUAL_CALIBRATION-Zweig nach dem Wechsel zu DONE nie wieder betritt (der Methodenrumpf
		 * faellt danach auf "return true;" am Ende von recompute() zurueck, siehe dort), kann diese Methode in einem Lauf
		 * NICHT mehrfach ausgeloest werden - kein wiederholtes Abdriften Richtung sigma_Boden bei jedem weiteren Tick.
		 *
		 * Formel: sigma_neu = sigma_Boden + (sigma_alt - sigma_Boden) * INDIVIDUAL_UNCERTAINTY_COMPRESSION_FACTOR (0.4).
		 *
		 * <= Obergrenze). Betrifft NUR Beobachtungen, die bereits einen individuellen Wert tragen
		 * (row.hasNoIndividualUncertainty()==false) - Beobachtungen ohne eigenen Wert (Fall 1, reine Gruppenbasis) werden
		 * NICHT angefasst, es gibt dort keinen "sigma_alt" zum Komprimieren.
		 *
		 * Einheiten-Disziplin: sigma_Boden (Klein_Method_Default_Values.getIndividualUncertaintyCompressionFloor()) ist
		 * bereits in der NATIVEN Arbeitseinheit des Typs angegeben (Meter bzw. Gon, siehe getIndividualUncertaintyUnit())
		 * - Umrechnung Rohwert<->Arbeits-einheit laeuft ausschliesslich ueber fromDatabaseValue()/toDatabaseValue(),
		 * exakt wie ueberall sonst in dieser Klasse.
		 *
		 * Da diese Methode weiterhin garantiert nur EINMAL pro Automatiklauf feuert (siehe Aufrufstelle in recompute()),
		 * ist ein harter Absolutwert hier unkritisch: es gibt keine Iterationskette, in der ein zu radikaler Sprung sich
		 * aufschaukeln koennte.
		 *
		 * (Auftrag "Live-UI-Reload fuer Gruppenunsicherheiten", realer
		 *
		 * Befund: das ORIGINALE JAG3D-Fenster zeigt nach diesem Schreibvorgang noch den alten Wert): GEPRUEFT und NICHT
		 * wie woertlich beauftragt umgesetzt - siehe die ausfuehrliche Begruendung in der Antwort an den Anwender zu.
		 * Kurzfassung: (1) Klein_Method's EIGENE Anzeigen (Ausgelesene Werte/Live-Modus/Messdaten-Ampel) sind
		 * NACHWEISLICH bereits aktuell - updateDiagnosticValueLabels() und refreshDataStatus() laufen bereits garantiert
		 * am Task-Ende (siehe Klein_Method_Tab_Builder.wireStartStop Buttons(), task.setOnSucceeded()). (2) "Grenzwerte
		 * Gruppenunsicherheit" zeigt AUSSCHLIESSLICH konfigurierte sigma_a/b/c-Bodenwerte je Geraete-klasse
		 * (Klein_Method_Parameter.getGroupUncertaintyFloorA/B/C(), reiner In-Memory-Konfigurationswert) - liest NIE aus
		 * der HSQLDB und kann daher gar nicht "veraltet" sein; ein Feld fuer eine live-individuelle sigma_0 (z.B.
		 * "GNSS-Basislinie 3D = 1.0") existiert dort UND sonst nirgends in Klein_Method ueberhaupt. (3) Die vom Anwender
		 * gemeinte Text-Leiche liegt folglich in JAG3Ds EIGENEM, nativen Fenster ausserhalb des Klein_Method-Tabs (z.B.
		 * die Standard-Beobachtungstabelle) - Klein_Method schreibt dort bewusst per Rohzugriff
		 * (SQLManager.getInstance().getDataBase(). getPreparedStatement(...)) direkt in die HSQLDB, UNTER UMGEHUNG von
		 * JAG3Ds eigenem ObservableList-Objektmodell - fuer einen erzwungenen Reload GENAU DIESER nativen Ansicht ist in
		 * diesem Projekt keine bestaetigte, oeffentliche JAG3D-API importiert oder bekannt (UITreeBuilder wird
		 * ausschliesslich fuer die BAUM-AUSWAHL verwendet, siehe Klein_Method.registerTab(), nicht fuer einen
		 * Daten-Reload). Eine erfundene Methode waere entweder ein Compile-Fehler oder, schlimmer, ein stiller Fehlschlag
		 * mit falschem Erfolgsversprechen - beides wird hier bewusst vermieden. Stattdessen ein einmaliger, klarer
		 * Protokoll-Hinweis unten, der den Anwender ueber genau diesen Sachverhalt informiert.
		 *
		 * GEPRUEFT und BEWUSST NICHT umgesetzt - siehe die ausfuehrliche Begruendung in der Antwort an den Anwender.
		 * Kurzfassung: der Auftrag verlangte, beim DONE-Uebergang zusaetzlich parameter.groupUncertaintyFloorA/B/C() (die
		 * Boeden von sigma_a/sigma_b/sigma_c, siehe Klein_Method_Parameter) mit den EP-Schwellwert-Konstanten (*_EP,
		 * Category.EP) zu ueberschreiben. Das waere in JEDER Hinsicht falsch: (1) die *_EP-Konstanten sind
		 * unveraen-derliche Enum-Werte einer VOELLIG ANDEREN Kategorie (EP-Gatter fuer performEpSingleStep(),
		 * Category.EP) - keine "lebenden RAM-Werte", die sich ueberschreiben liessen. (3) selbst wenn die Zielgroesse
		 * gestimmt haette: die Analyse zeigt bereits, dass "Grenzwerte Gruppenunsicherheit" NIE aus der HSQLDB liest
		 * (reiner In-Memory-Konfig-Wert) - eine Mutation haette also auch keine sichtbare "Synchronisierung" mit dem
		 * tatsaechlich geschriebenen individuellen sigma_0 bewirkt, da beide Groessen nichts miteinander zu tun haben.
		 *
		 * Klein_Method_Tab_Builder liest dieses Flag fuer eine NEUE, rein informative Anzeige in der eigenen Oberflaeche
		 * (Sektion "Ausgelesene Werte") - keine erfundene API, keine Fremdmutation, nur ein Wahrheitswert ueber eine
		 * Tatsache, die diese Methode selbst herbeigefuehrt hat.
		 *
		 * GEPRUEFT und ERNEUT BEWUSST NICHT umgesetzt - dies ist im Kern derselbe Auftrag wie, diesmal mit der
		 * Behauptung, es gaebe "den offiziellen Jag3D-Datenbank-Ladebefehl ... ueber den SQLManager oder die aktive
		 * Projekt-Instanz". Ein erneuter, gezielter Scan des gesamten 32-Dateien-Quellbaums (grep nach reload/
		 * Reload/refreshTable/reReadProject/SQLProjectManager/ProjectManager) findet KEINE solche Methode - der einzige
		 * Treffer ist dieser eigene Kommentartext. Die einzige JAG3D-UI-Referenz, die Klein_Method ueberhaupt
		 * importiert/verwendet, bleibt UITreeBuilder.getInstance().getTree()
		 * (org.applied_geodesy.jag3d.ui.tree.UITreeBuilder), und die dient ausschliesslich der Baum-SELEKTION beim
		 * Tab-Wechsel (registerTab()) - kein Daten-/Tabellen-Reload. Ein frei erfundener Aufruf auf SQLManager/"die
		 * aktive Projekt-Instanz" waere nicht nur nicht kompilierbar, sondern riskiert - falls es ihn gaebe - dass
		 * Klein_Methods eigener ProjectDatabaseStateChangeListener (projectDatabaseStateChanged()) das faelschlich als
		 * echten Projekt-wechsel interpretiert und mitten im Automatik-Lauf den Sperr-Dialog ausloest. Der ehrliche,
		 * bereits umgesetzte Ausweg bleibt 's eigene Diagnose-Zeile "Erzwungener HSQLDB-Boden:" innerhalb von
		 * Klein_Methods eigener, tatsaechlich live aktualisierter Oberflaeche - genau die Anzeige, die dieser Auftrag
		 * selbst unter Punkt 2 unangetastet erhalten wissen will.
		 *
		 * TEILWEISE umgesetzt, mit klarer Trennung der beiden Teilauftraege. Punkt 1 (harter SQL-Checkpoint
		 * COMMIT+CHECKPOINT) ist ECHTES, existierendes HSQLDB-SQL - anders als die verlangten, nicht existierenden
		 * JAG3D-APIs - und wird ueber genau denselben, bereits etablierten Zugriffspfad ausgefuehrt
		 * (databaseHandler.commitAndCheckpoint(), siehe dort). Punkt 2 (JAG3Ds "interne ObservableList-Caches loeschen
		 * und frisch einlesen") bleibt aus DEMSELBEN Grund wie
		 *
		 * abgelehnt: Klein_Method importiert und besitzt keinerlei Referenz auf dieses private JAG3D-Objektmodell - ein
		 * Aufruf dorthin waere weiterhin frei erfunden.
		 *
		 * WICHTIGE EINORDNUNG, warum Punkt 1 die vom Anwender beschriebene Symptomatik (springendes logPost, JAG3Ds
		 * natives Optionenfenster zeigt alte Werte) NICHT beheben kann: COMMIT/CHECKPOINT wirken ausschliess-lich auf die
		 * physische Persistenz der HSQLDB-Datei - sie beeinflussen NICHT JAG3Ds eigenen, In-Memory gehaltenen
		 * ObservableList-Cache. Zudem liest Klein_Method_Iteration_Task seine eigenen currentMaxLogPost/
		 * currentMaxEp-Werte bereits GARANTIERT frisch aus der HSQLDB - ein Beleg, dass innerhalb von Klein_Method selbst
		 * keine Lese-Verzoegerung besteht, die ein COMMIT/CHECKPOINT beheben koennte. Der Aufruf wird dennoch
		 * ausgefuehrt, da er real, risikoarm und an sich sinnvoll ist (unwiderrufliche Persistenz der Hard-Clamp-Werte
		 * auf Platte, unabhaengig vom Autocommit-Modus der Verbindung) - siehe Prüfprotokoll fuer die vollstaendige
		 * Einordnung.
		 *
		 * Punkt 1 WIE BEAUFTRAGT umgesetzt. WICHTIGE EINORDNUNG, warum dies aller Voraussicht nach NICHT die berichtete
		 * Symptomatik (actuallyChanged ObservationCount bleibt ueber mehrere Kaskaden-Passes hinweg bei 993 stehen)
		 * beheben wird: Klein_Method liest UND schreibt seit Projektbeginn ausschliesslich ueber EINE einzige, geteilte
		 * JDBC-Verbindung (SQLManager.getInstance().getDataBase() - siehe Klassenkommentar in
		 * Klein_Method_Database_Handler, "KEINE eigene, zweite Connection oeffnen!"). Auf DERSELBEN Verbindung sieht JEDE
		 * nachfolgende Abfrage ihre eigenen, bereits abgesetzten Schreibvorgaenge IMMER sofort - das ist
		 * Standard-JDBC-Verhalten ("read your own writes") und haengt NICHT davon ab, ob zwischendurch ein COMMIT
		 * stattfand.
		 *
		 * Die weitaus wahrscheinlichere Erklaerung fuer 993 dauerhaft "aktive" Korrekturen ueber viele Passes hinweg ist
		 * eine ECHTE algorithmische Oszillation, keine Datenbank-Sichtbarkeitsfrage: der Hard-Clamp greift nur EINMALIG
		 * beim DONE-Uebergang jedes Passes. Im DARAUFFOLGENDEN Pass berechnet die individuelle Kalibrierung (Phase 2,
		 * performIndividual CalibrationStep()) sigma_0 aus der frischen Netzausgleichung KOMPLETT NEU - fuer
		 * Beobachtungen, deren tatsaechlicher EP-/logPost-Bedarf ueber dem Boden liegt, weicht dieser neu berechnete Wert
		 * danach zwangslaeufig wieder vom Boden ab, bevor der naechste Hard-Clamp ihn erneut zurueck-zwingt - ein
		 * struktureller, in der Zwei-Phasen-Architektur selbst angelegter Regelkreis, kein Bug in der
		 * Schreib-/Lese-Reihenfolge. Bleibt die Anzahl ueber viele Passes exakt konstant bei 993, spricht das fuer
		 * dieselben 993 Beobachtungen, deren tatsaechlicher Bedarf strukturell ueber dem Boden liegt - das waere ein
		 * fachlicher Befund (Netz/Grenzwerte pruefen), keiner, den ein haeufigeres COMMIT beheben kann.
		 *
		 * Damit bleibt der Boden weiterhin ein echtes Minimum (kein Wert kann je darunter fallen), ist aber kein starres
		 * "Einfrieren" mehr - ein geodaetisches Einwegventil statt einer beidseitigen Sperre.
		 *
		 * ARCHITEKTUR-HINWEIS (Abweichung vom Wortlaut des Auftrags): der Auftrag wollte das Set direkt HIER in
		 * Klein_Method_Functions als Instanzfeld deklariert haben. Klein_Method_Functions ist jedoch eine EINZIGE, ueber
		 * die gesamte Plugin-Laufzeit wiederverwendete Instanz (siehe deren Injektion in den Konstruktor von
		 * Klein_Method_Iteration_Task) - NICHT pro Automatik-Lauf neu erzeugt. Ein hier deklariertes Set wuerde daher
		 * ohne zusaetzliche, fehleranfaellige manuelle Leerung ueber mehrere, voneinander unabhaengige Automatik-Laeufe
		 * hinweg fehlerhaft weiterbestehen und beim naechsten, komplett neuen Lauf faelschlich bereits alte Beobachtungen
		 * blockieren. Die Registry liegt deshalb stattdessen auf Klein_Method_Parameter und wird ueber denselben, bereits
		 * etablierten Mechanismus zurueckgesetzt: NICHT in resetProcessPhase() (laeuft bei JEDEM Pass-Wechsel), sondern
		 * exklusiv beim ECHTEN Start eines neuen Automatik-Laufs in Klein_Method_Iteration_Task.call()
		 * (resetClampedObservationsRegistry()). Funktional entspricht dies exakt dem Auftrag: die Sperre wirkt fuer die
		 * gesamte Kaskaden-Sitzung, genau wie beauftragt.
		 *
		 * KEIN Code-Aenderung an dieser Methode noetig/vorgenommen. Punkt 2 des Auftrags verlangte, den
		 * Hard-Clamp-Abschlusslauf fuer "alle drei stochastischen Komponenten sigma_a, b und c" auf das synchrone
		 * 1.0mm-Bodenlimit umzustellen - diese Methode hier wendet die individuelle Hard-Clamp-Untergrenze jedoch bereits
		 * auf einen EINZIGEN, fertigen sigma_0-Wert PRO BEOBACHTUNG an - eine Aufteilung in a/b/c existiert auf dieser
		 * Ebene (individuelle Messunsicherheit, Phase 2/ PHASE:DONE) gar nicht, die 3 Komponenten sigma_a/sigma_b/sigma_c
		 * sind ein rein GRUPPEN-Konzept aus Phase 1.
		 */
		private void performFloorEnforcementCompression(Klein_Method_Parameter parameter,
				Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
			int decimalPlaces = parameter.getDecimalPlaces();

			// zaehlt die tatsaechlich vorgenommenen Hard-Clamp-Ueberschreibungen dieses Laufs - nur wenn > 0, wird unten
			// der einmalige JAG3D-Reload-Hinweis angehaengt (kein irrefuehrender Hinweis bei einem Lauf ohne Treffer).
			int clampedObservationCount = 0;
			// siehe Methoden-Javadoc oben ("WICHTIGER TECHNISCHER BEFUND") - zaehlt NUR echte Korrekturen (sigma_alt wich
			// tatsaechlich von sigma_Boden ab), NICHT jeden Schreibvorgang wie clampedObservationCount.
			int actuallyChangedObservationCount = 0;
			// Rundungstoleranz gegen Gleitkomma-Rauschen aus der Einheiten-Hin-und-
			// Rueckkonvertierung (toDatabaseValue()/fromDatabaseValue()) - weit unter
			// jeder fachlich relevanten Genauigkeit (kleinster Bodenwert: 0.0001 m).
			final double CHANGED_EPSILON = 1.0e-9;

			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
				String unit = Klein_Method_Default_Values.getIndividualUncertaintyUnit(type);
				double sigmaFloor = Klein_Method_Default_Values.getIndividualUncertaintyCompressionFloor(type);

				boolean isGnssType = type == Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST
						|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST
						|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST;

				if (isGnssType) {
					// wiederverwendet dieselbe, bereits bestaetigte Lesefunktion wie
					// performEpSingleStep() (liest ALLE aktiven Basislinien dieses Typs,
					// nicht nur den Bearbeitungsbereich - hier gewollt, da die Kompression
					// unabhaengig vom Phase-2-Bearbeitungsbereich fuer ALLE bereits
					// individuell kalibrierten Werte gelten soll).
					for (Klein_Method_Ep_Row row : databaseHandler.readAllGnssObservationsForEpCalibration(type)) {
						if (row.hasNoIndividualUncertainty())
							continue; // kein sigma_alt vorhanden - nichts zu ueberschreiben
						double sigmaAlt = Klein_Method_Functions.this.fromDatabaseValue(row.getCurrentIndividualUncertaintyRaw(), unit);
						// harter Absolut-Anschlag statt weicher Daempfung - sigma_neu IST der Boden, keine
						// Konvexkombination mit sigma_alt mehr.
						double sigmaNeu = sigmaFloor;
						databaseHandler.writeGnssIndividualUncertainty(row.getObservationId(), row.getAxis(), Klein_Method_Functions.this.toDatabaseValue(sigmaNeu, unit));
						// synchroner Dauer-Flush - COMMIT direkt nach DIESEM einzelnen Schreibvorgang, nicht erst
						// gesammelt am Methodenende (siehe Javadoc oben).
						databaseHandler.commit();
						// Interne HSQLDB-Schreib-Sperre - diese Beobachtung/Achse ist ab sofort fuer den Rest der
						// Kaskaden-Sitzung gegen Ueberschreiben durch performIndividualCalibrationStep() gesperrt (siehe
						// Methoden-Javadoc oben).
						parameter.registerClampedObservation(buildClampRegistryKey(row.getObservationId(), row.getAxis()));
						clampedObservationCount++;
						if (Math.abs(sigmaAlt - sigmaNeu) > CHANGED_EPSILON)
							actuallyChangedObservationCount++;
						if (outAdjustmentDetails != null) {
							outAdjustmentDetails.add("UNTERGRENZE ERZWUNGEN (Hard-Clamp) sigma_0 " + type.getDisplayName()
									+ " Achse " + row.getAxis() + " Beobachtung " + row.getObservationId() + ": "
									+ Klein_Method_Format.format(sigmaAlt, unit, decimalPlaces) + " -> "
									+ Klein_Method_Format.format(sigmaNeu, unit, decimalPlaces) + " (Anschlag)!");
						}
					}
				}
				else {
					for (Klein_Method_Ep_Row row : databaseHandler.readAllObservationsForEpCalibration(type)) {
						if (row.hasNoIndividualUncertainty())
							continue;
						double sigmaAlt = Klein_Method_Functions.this.fromDatabaseValue(row.getCurrentIndividualUncertaintyRaw(), unit);
						// harter Absolut-Anschlag statt weicher Daempfung - sigma_neu IST der Boden, keine
						// Konvexkombination mit sigma_alt mehr.
						double sigmaNeu = sigmaFloor;
						databaseHandler.writeIndividualUncertainty(row.getObservationId(), Klein_Method_Functions.this.toDatabaseValue(sigmaNeu, unit));
						// synchroner Dauer-Flush - COMMIT direkt nach DIESEM einzelnen Schreibvorgang, nicht erst
						// gesammelt am Methodenende (siehe Javadoc oben).
						databaseHandler.commit();
						// Interne HSQLDB-Schreib-Sperre - diese Beobachtung ist ab sofort fuer den Rest der
						// Kaskaden-Sitzung gegen Ueberschreiben durch performIndividualCalibrationStep() gesperrt (siehe
						// Methoden-Javadoc oben).
						parameter.registerClampedObservation(buildClampRegistryKey(row.getObservationId()));
						clampedObservationCount++;
						if (Math.abs(sigmaAlt - sigmaNeu) > CHANGED_EPSILON)
							actuallyChangedObservationCount++;
						if (outAdjustmentDetails != null) {
							outAdjustmentDetails.add("UNTERGRENZE ERZWUNGEN (Hard-Clamp) sigma_0 " + type.getDisplayName()
									+ " Beobachtung " + row.getObservationId() + ": "
									+ Klein_Method_Format.format(sigmaAlt, unit, decimalPlaces) + " -> "
									+ Klein_Method_Format.format(sigmaNeu, unit, decimalPlaces) + " (Anschlag)!");
						}
					}
				}
			}

			// GEPRUEFT und bewusst NICHT als erzwungener Reload von JAG3Ds EIGENEM, nativem Fenster umgesetzt (kein
			// bestaetigter, oeffentlicher API-Hook dafuer im Projekt bekannt/importiert - siehe Methoden-Doc oben und die
			// ausfuehrliche Begruendung in der Antwort an den Anwender).
			if (clampedObservationCount > 0)
				parameter.setHardClampAppliedThisRun(true);

			if (clampedObservationCount > 0 && outAdjustmentDetails != null) {
				outAdjustmentDetails.add("HINWEIS: " + clampedObservationCount + " Beobachtung(en) per Hard-Clamp auf den Boden "
						+ "gesetzt. Klein_Method schreibt bewusst per Rohzugriff direkt in die HSQLDB (JAG3Ds eigenes "
						+ "Objektmodell wird dabei umgangen) - JAG3Ds EIGENE, native Fenster/Tabellen ausserhalb des "
						+ "Klein_Method-Tabs zeigen den neuen Wert deshalb ggf. erst nach einem manuellen Neuladen des "
						+ "Projekts bzw. erneutem Oeffnen der betroffenen Ansicht. Innerhalb des Klein_Method-Tabs selbst "
						+ "sind alle Anzeigen bereits aktuell.");
			}

			// das COMMIT ist jetzt bereits pro Schreibvorgang innerhalb der Schleife erfolgt (siehe oben) - hier bleibt
			// deshalb nur noch das abschliessende, weiterhin einmalige CHECKPOINT (physisches Schreiben auf die
			// Festplatte). Siehe Methoden-Javadoc oben fuer die vollstaendige Einordnung, warum dies die vom Anwender
			// berichtete Symptomatik voraussichtlich NICHT behebt.
			if (clampedObservationCount > 0) {
				databaseHandler.checkpoint();
				if (outAdjustmentDetails != null)
					outAdjustmentDetails.add("HINWEIS: synchroner Dauer-Flush aktiv - jeder einzelne Hard-Clamp-Schreibvorgang "
							+ "wurde bereits im selben Zug committet, abschliessend zusaetzlich ein finaler Datenbank-Checkpoint "
							+ "(physisches Schreiben auf die Festplatte). Dies betrifft AUSSCHLIESSLICH die Datenbank-Persistenz, "
							+ "NICHT JAG3Ds eigenen, unabhaengigen UI-Cache (siehe Hinweis oben).");
			}

			// IMMER setzen (auch 0) - siehe Getter/Setter-Javadoc in Klein_Method_Parameter fuer die Begruendung. Treibt
			// die Kaskaden-Abbruchbedingung in Klein_Method_Iteration_Task.
			parameter.setLastFloorEnforcementChangedCount(actuallyChangedObservationCount);
			if (actuallyChangedObservationCount > 0 && outAdjustmentDetails != null) {
				outAdjustmentDetails.add("HINWEIS (Kaskaden-Engine): " + actuallyChangedObservationCount + " von "
						+ clampedObservationCount + " geschriebenen Beobachtung(en) waren tatsaechliche Korrekturen "
						+ "(sigma_alt wich vom Boden ab) - bei aktiviertem Kaskaden-Mehrfachlauf wird deshalb automatisch "
						+ "ein weiterer Pass gestartet.");
			}
		}
		/**
		 * Berechnet die Untergrenze des logPost-Bearbeitungsbereichs ("oberer Bereich der Normalverteilung"): ausgehend
		 * vom aktuellen Max-logPost-Wert wird ein schmaler Bereich nach unten aufgespannt, NUR innerhalb dessen in der
		 * aktuellen Iteration Beobachtungen bearbeitet werden - nicht die gesamte Verteilung auf einmal.
		 *
		 * Untergrenze = EXP(LN(maxLogPost) - Offset)
		 *
		 * Ausnahme: sobald diese Untergrenze bereits auf/unter den Grenzwert (thresholdValue, z.B. 2.45) faellt, wird die
		 * Begrenzung nach unten auf den Grenzwert selbst gesetzt - bearbeitet werden dann alle Beobachtungen ab dem
		 * Grenzwert, aber keine statistisch unauffaelligen darunter.
		 *
		 * @return die Untergrenze des Bearbeitungsbereichs (bzw. der Grenzwert, siehe Ausnahme oben).
		 *
		 * Bei Bedarf spaeter neu einfuehren, sobald Nivellementszuege als eigener Grenzwert-Typ ergaenzt werden.
		 */
		/**
		 * LOG_POST_BAND_OFFSET_DEFAULT, immer 0.3) durch die Formel (exakt, Zehnerlogarithmus) mit einem vom Anwender
		 * einstellbaren, auf [0.2; 0.3] begrenzten Faktor ab (siehe Klein_Method_Parameter.setFunnelFactor()):
		 *
		 * Untergrenze = 10^(log10(|maxLogPost|) - funnelFactor)
		 */
		public double computeLogPostBandLowerBound(double maxLogPostValue, double thresholdValue, double funnelFactor) {
			double lowerBound = Math.pow(10, Math.log10(Math.abs(maxLogPostValue)) - funnelFactor);

			// Werden nur noch Beobachtungen ab dem Grenzwert angefasst; jede davon bekommt mindestens den Faktor
			// 1+log10(2.45) = 1.39 (NORMAL).
			if (lowerBound <= thresholdValue)
				return thresholdValue;

			return lowerBound;
		}
		// Fuer die neue UI-Anzeige "logPost-EtappeMin:" (Klein_Method_Tab_Builder.updateLogPostDynamicBandFields()) wird
		// - konsistent mit der bereits bestehenden Vereinfachung von "Live-Modus" (siehe
		// getMaxLogPostValue()/getMaxLogPostThreshold() oben, die ebenfalls je 4 Einzelwerte auf einen globalen Wert
		// verdichten) - dieselbe Formel auf die GLOBALEN Maximalwerte angewendet, statt separat je Typ.
		/**
		 * liefert die aktuell geltende, LIVE bei jeder Iteration neu berechnete Untergrenze des globalen
		 * logPost-Bearbeitungsbereichs ("das dynamisch mitschwimmende Zwischenziel des Trichters") - dieselbe Formel wie
		 * computeLogPostBandLowerBound(), angewendet auf den globalen Max-logPost-Wert (getMaxLogPostValue()), den
		 * globalen Grenzwert (getMaxLogPostThreshold()) und den aktuell wirksamen Trichter-Faktor
		 * (parameter.getEffectiveFunnelFactor()), zusaetzlich nach oben durch den "kollektiven Deltasprung" begrenzt
		 * (parameter.getCollectiveDelta()) - exakt dieselben drei Eingangsgroessen, die
		 * performIndividualCalibrationStep() je Typ intern verwendet (siehe dort).
		 *
		 * FUNDSTELLEN-HINWEIS (Praezision zur Auftragsformulierung): der Auftrag beschreibt, das Feld solle "im letzten
		 * Pass bit-genau bei 2.45 landen". Technisch trifft das NICHT zu: die Ausnahme-Klausel in
		 * computeLogPostBandLowerBound() setzt den Wert, sobald die berechnete Untergrenze auf/unter den Grenzwert
		 * faellt, nicht auf den Grenzwert selbst (z.B. "2.45"), sondern auf den fest verdrahteten Literal 1.0 - die
		 * Bereichsgrenze wird damit praktisch VOLLSTAENDIG AUFGEHOBEN (jede Beobachtung oberhalb 1.0 wird bearbeitet),
		 * nicht auf 2.45 GEKLEMMT.
		 *
		 * @return die aktuelle globale logPost-Bearbeitungs-Untergrenze (bzw. 1.0, siehe Fundstellen-Hinweis oben).
		 */
		public double computeCurrentLogPostEtappeMin(Klein_Method_Parameter parameter) {
			double maxLogPostValue = Klein_Method_Functions.this.getMaxLogPostValue(parameter);
			double thresholdValue = Klein_Method_Functions.this.getMaxLogPostThreshold(parameter);
			double funnelFactor = parameter.getEffectiveFunnelFactor();

			double logBasedBandLowerBound = this.computeLogPostBandLowerBound(maxLogPostValue, thresholdValue, funnelFactor);
			double collectiveDeltaBandLowerBound = maxLogPostValue - parameter.getCollectiveDelta();
			double bandLowerBound = Math.min(logBasedBandLowerBound, collectiveDeltaBandLowerBound);

			// dieselbe Ausnahme wie in computeLogPostBandLowerBound() selbst, hier zusaetzlich noetig, da der
			// collectiveDelta-Vergleich den Wert unter die Ausnahme-Schwelle druecken kann, OHNE dass
			// computeLogPostBandLowerBound() selbst das noch erkennen konnte - siehe identische Logik in
			// performIndividualCalibrationStep(), ~Zeile 601-607. Grenzwert statt 1.0.
			if (bandLowerBound <= thresholdValue)
				bandLowerBound = Math.min(parameter.getFunnelFloorLogPost(), thresholdValue);

			return bandLowerBound;
		}
		/**
		 * liefert den aktuellen GNSS-Gruppen-VKS-Stand (sigma_a/b/c) als fertig formatierten Protokolltext, fuer
		 * Klein_Method_Iteration_Task, die diesen Text bei JEDER Iteration mitprotokollieren soll (siehe dort). Reiner
		 * LESE-Zugriff (kein Schreiben) ueber denselben Pfad wie performGroupCalibrationStep() - repraesentativ ueber
		 * GNSS_BASELINE_3D_LOG_POST und dessen ERSTE Gruppe. Liefert "k.A." statt eines erfundenen Platzhalterwerts, wenn
		 * (noch) keine GNSS-Gruppe im Projekt existiert oder der Lesezugriff fehlschlaegt - NIE ein stiller Fehlschlag,
		 * der wie ein echter Messwert aussieht.
		 */
		public String formatCurrentGnssGroupUncertaintyStatus(Klein_Method_Parameter parameter,
				Klein_Method_Database_Handler_Interface databaseHandler) {
			Klein_Method_Threshold_Type type = Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST;
			try {
				List<Integer> groupIds = databaseHandler.readGroupIds(type);
				if (groupIds == null || groupIds.isEmpty())
					return "GNSS-VKS-Stand: k.A. (keine GNSS-Gruppe im Projekt)";

				int decimalPlaces = parameter.getDecimalPlaces();
				String unitA = Klein_Method_Default_Values.getSigmaAUnit(type);
				String unitB = Klein_Method_Default_Values.getSigmaBUnit(type);
				String unitC = Klein_Method_Default_Values.getSigmaCUnit(type);

				double[] sigmaRaw = databaseHandler.readGroupUncertainty(groupIds.get(0));
				double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], unitA);
				double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], unitB);
				double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], unitC);

				return "GNSS-VKS-Stand: a=" + Klein_Method_Format.format(sigmaA, unitA, decimalPlaces)
						+ ", b=" + Klein_Method_Format.format(sigmaB, unitB, decimalPlaces)
						+ ", c=" + Klein_Method_Format.format(sigmaC, unitC, decimalPlaces);
			} catch (SQLException e) {
				return "GNSS-VKS-Stand: k.A. (Lesefehler: " + e.getMessage() + ")";
			}
		}
		/**
		 * Prueft, ob ein einzelner |logPost|-Wert einer Beobachtung im
		 * aktuellen Bearbeitungsbereich liegt (siehe computeLogPostBandLowerBound())
		 * - nur dann soll diese Beobachtung in der aktuellen Iteration
		 * bearbeitet werden.
		 */
		public boolean isWithinLogPostBand(double observationLogPostValue, double bandLowerBound, double maxLogPostValue) {
			return observationLogPostValue >= bandLowerBound && observationLogPostValue <= maxLogPostValue;
		}
		/**
		 * PHASE 2 - Formel zur Neuberechnung der individuellen Messunsicherheit (oh0/osh0/ov0/os0) einer einzelnen
		 * Beobachtung:
		 *
		 * neu = baseValue + baseValue * LOG10(absLogPostValue)
		 *
		 * baseValue ist je nach Fall entweder die Basis aus den Gruppenparametern (Fall 1: kein individueller Wert
		 * vorhanden, siehe computeIndividualUncertaintyBasis()) oder der bestehende individuelle Wert selbst (Fall 2) -
		 * siehe performIndividualCalibrationStep().
		 *
		 * KORRIGIERT: es gibt HIER KEINE Untergrenze mehr - nur noch die Obergrenze wird separat geprueft (siehe
		 * isIndividualUncertaintyCeilingExceeded()).
		 *
		 * Eine Abschluss-Kompression, die genau beim Schreiben (PHASE:DONE) alle Werte zwangsweise Richtung einer
		 * Untergrenze verschiebt, IST ein Write-Floor - nur einmalig statt auf jeder Iteration.
		 *
		 * (2) KATEGORIE-VERWECHSLUNG: "sigma_Hierarchie" soll laut Auftrag aus den EP-Mindestwerten
		 * (Klein_Method_Threshold_Type, Kategorie EP - "influence_on_position", ein DIAGNOSE-/Korrektur-Prioritaets-Wert)
		 * stammen. sigma_alt/sigma_neu HIER sind aber die individuelle Messunsicherheit (Kategorie LOG_POST) - eine
		 * fachlich andere Groesse mit eigener, bereits bestehender Obergrenze (0.999/0.499, siehe
		 * getIndividualUncertaintyCeiling()) und ausdruecklich OHNE Untergrenze. Die beiden Werte in denselben Topf zu
		 * werfen, nur weil beide (zufaellig) in mm-Groessenordnung gedacht sind, waere fachlich nicht begruendet.
		 *
		 * (3) EINHEITEN-KONFLIKT (der schwerwiegendste, potenziell STILL KORRUMPIERENDE Fund):
		 * getIndividualUncertaintyUnit() liefert fuer sigma_0 "gon" bei den 2 Richtungstypen und "m" (METER, NICHT mm)
		 * bei allen anderen - sigma_alt/sigma_neu sind hier also in METERN bzw. GON unterwegs, nicht in Millimetern. Die
		 * vom Anwender genannten Hierarchiewerte (0.1/0.5/1.0) sind aber ausdruecklich Millimeter-Werte. Unkonvertiert
		 * als "sigma_Hierarchie" eingesetzt, waeren es de facto 0.1-1.0 METER (1000x zu gross) - bei einer Obergrenze von
		 * nur 0.999 m/0.499 m haette das die Kalibrierung sofort gegen die Obergrenze getrieben bzw. den zulaessigen
		 * Wertebereich drastisch eingeschnuert. Fuer die beiden Richtungstypen (Einheit "gon") gibt es ohnehin KEIN
		 * mm-Aequivalent in der Hierarchie - derselbe Einheiten-Konflikt wie bereits beim ersten Versuch der
		 * EP-Richtungswerte aufgedeckt.
		 */
		/**
		 * Lotrichtung (Einheits-vektor "oben") in den JAG3D-/Klein_Method-Achsen X/Y/Z. Liegen die Koordinaten
		 * geozentrisch vor (Betrag des Mittelpunkts 6,30-6,40 Mio. m), ist die Ellipsoid-normale n ~ (x, y, z/(1-e^2)) -
		 * diese Formel ist symmetrisch in x und y und damit UNABHAENGIG davon, welche JAG3D-Spalte die geozentrische X-
		 * bzw. Y-Koordinate enthaelt. Bei lokalen Koordinaten gilt die z-Achse als Lotrichtung (0,0,1). Liefert null,
		 * wenn keine ausgeglichenen Koordinaten vorliegen.
		 */
		private double[] computeLocalUpVector(Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails, int decimalPlaces) throws SQLException {
			double[] mean = databaseHandler.readMeanAposterioriPointCoordinates();
			if (mean[3] <= 0.0) {
				if (outAdjustmentDetails != null)
					outAdjustmentDetails.add("Lage/H\u00F6he-Split: keine ausgeglichenen Punktkoordinaten - Lotrichtung unbekannt, Basislinien werden mit \u201Egedämpfter Achs Split\u201C gerechnet.");
				return null;
			}
			double radius = Math.sqrt(mean[0] * mean[0] + mean[1] * mean[1] + mean[2] * mean[2]);
			double[] up;
			String source;
			if (radius > 6.30e6 && radius < 6.40e6) {
				final double e2 = 0.00669437999014; // WGS84/GRS80
				up = new double[] { mean[0], mean[1], mean[2] / (1.0 - e2) };
				source = "geozentrisch";
			} else {
				up = new double[] { 0.0, 0.0, 1.0 };
				source = "lokal (z = H\u00F6he)";
			}
			double norm = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
			for (int i = 0; i < 3; i++)
				up[i] /= norm;
			if (outAdjustmentDetails != null) {
				double latitude = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, up[2]))));
				outAdjustmentDetails.add("Lage/H\u00F6he-Split: Lotrichtung aus " + (long) mean[3] + " Punkten (" + source + ") - U_X="
						+ Klein_Method_Format.format(up[0], "", 4) + ", U_Y=" + Klein_Method_Format.format(up[1], "", 4)
						+ ", U_Z=" + Klein_Method_Format.format(up[2], "", 4) + " | geod. Breite ca. "
						+ Klein_Method_Format.format(latitude, "Grad", 3));
			}
			return up;
		}
		/**
		 * Lotrichtung JE BASISLINIE als Ellipsoidnormale im Mittelpunkt von Start- und Endpunkt (aus den ausgeglichenen
		 * Koordinaten). Nur bei geozentrischen Koordinaten; bei lokalen Koordinaten oder fehlenden Punkten gilt die
		 * Netz-Lotrichtung (Rueckfall). Protokolliert je Aufruf, wie viele Basislinien eine eigene Lotrichtung haben und
		 * wie weit diese hoechstens von der Netzmitte abweicht.
		 */
		private Map<Integer, double[]> computeBaselineUpVectors(Klein_Method_Database_Handler_Interface databaseHandler,
				double[] networkUp, List<String> outAdjustmentDetails) throws SQLException {
			final double e2 = 0.00669437999014; // WGS84/GRS80
			Map<Integer, double[]> result = new LinkedHashMap<Integer, double[]>();
			Map<Integer, double[]> midpoints = databaseHandler.readGnssBaselineMidpoints();
			double maxDeviationDeg = 0.0;
			for (Map.Entry<Integer, double[]> entry : midpoints.entrySet()) {
				double[] m = entry.getValue();
				double radius = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
				if (!(radius > 6.30e6 && radius < 6.40e6))
					continue; // lokale Koordinaten -> Netz-Lotrichtung
				double[] up = new double[] { m[0], m[1], m[2] / (1.0 - e2) };
				double norm = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
				for (int i = 0; i < 3; i++)
					up[i] /= norm;
				double cosine = up[0] * networkUp[0] + up[1] * networkUp[1] + up[2] * networkUp[2];
				maxDeviationDeg = Math.max(maxDeviationDeg, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine)))));
				result.put(entry.getKey(), up);
			}
			if (outAdjustmentDetails != null)
				outAdjustmentDetails.add("Lage/H\u00F6he-Split: " + result.size() + " von " + midpoints.size()
						+ " Basislinien mit eigener Lotrichtung (Mittelpunkt der Basislinie), gr\u00F6\u00DFte Abweichung zur Netzmitte "
						+ Klein_Method_Format.format(maxDeviationDeg, "Grad", 4) + ".");
			return result;
		}
		/**
		 * dritte Daempfungs-Formel im Modus prozentual. Statt die Korrektur nach den Achsen X/Y/Z aufzuteilen, wird sie
		 * nach LAGE (horizontal, isotrop) und HOEHE (Lotrichtung) aufgeteilt:
		 *
		 * 1. EP-Vektor v (mit Vorzeichen) in Hoehe u = v*U und Lage h = |v - u*U| zerlegen. Anteile (Summe 1, analog zum
		 * Achs Split, aber 2 statt 3 Komponenten):
		 *
		 * w_Lage = h / (h+|u|), w_Hoehe = |u| / (h+|u|). (h=u=0: je 1/2.)
		 *
		 * 3. Dieselbe gedaempfte Formel wie beim Achs Split je Komponente:
		 *
		 * roh = (sigma + sigma*log10(F)) * w, neu = sigma + 0,4*(roh - sigma).
		 *
		 * 4. Zurueck auf die Achsen: s_i^2 = (1-U_i^2) * sigma_Lage_neu^2 + U_i^2 * sigma_Hoehe_neu^2.
		 *
		 * Liefert false, wenn die 3 Achsen-Zeilen nicht vollstaendig sind.
		 */
		/**
		 * entscheidet, ob fuer diese Basislinie eine MATHE-DEBUG-Zeile geschrieben wird - "aus": nie, "Stichprobe": die
		 * ersten 5 je Aufruf (Typ/Iteration), "alle": immer.
		 */
		private boolean allowMathDebugLine(Klein_Method_Parameter.Klein_Method_Math_Debug_Mode mode, int[] counter) {
			if (mode == Klein_Method_Parameter.Klein_Method_Math_Debug_Mode.ALL)
				return true;
			if (mode == Klein_Method_Parameter.Klein_Method_Math_Debug_Mode.SAMPLE && counter[0] < 5) {
				counter[0]++;
				return true;
			}
			return false;
		}
		private boolean computeLageHoeheSplit(List<Klein_Method_Observation_Uncertainty_Row> baselineRows,
				Map<Klein_Method_Observation_Uncertainty_Row, Double> baseValues, double[] up,
				Map<Klein_Method_Observation_Uncertainty_Row, Double> newValues, List<String> outAdjustmentDetails,
				String unit, int decimalPlaces, boolean splitNormalization) {
			Klein_Method_Observation_Uncertainty_Row[] axisRows = new Klein_Method_Observation_Uncertainty_Row[3];
			for (Klein_Method_Observation_Uncertainty_Row row : baselineRows) {
				if (row.getAxis() == null)
					return false;
				axisRows[row.getAxis().ordinal()] = row;
			}
			for (Klein_Method_Observation_Uncertainty_Row row : axisRows)
				if (row == null)
					return false;

			double[] ep = new double[3];
			double[] base = new double[3];
			for (int i = 0; i < 3; i++) {
				ep[i] = axisRows[i].getInfluenceOnPositionAxis();
				base[i] = baseValues.get(axisRows[i]);
			}
			double u = ep[0] * up[0] + ep[1] * up[1] + ep[2] * up[2];
			double hx = ep[0] - u * up[0], hy = ep[1] - u * up[1], hz = ep[2] - u * up[2];
			double h = Math.sqrt(hx * hx + hy * hy + hz * hz);
			double absU = Math.abs(u);
			double weightLage, weightHoehe;
			if (h + absU <= 0.0) {
				weightLage = 0.5;
				weightHoehe = 0.5;
			} else {
				weightLage = h / (h + absU);
				weightHoehe = absU / (h + absU);
			}

			double varianceHoehe = 0.0, varianceLageSum = 0.0;
			for (int i = 0; i < 3; i++) {
				double u2 = up[i] * up[i];
				varianceHoehe += u2 * base[i] * base[i];
				varianceLageSum += (1.0 - u2) * base[i] * base[i];
			}
			double sigmaHoeheOld = Math.sqrt(varianceHoehe);
			double sigmaLageOld = Math.sqrt(varianceLageSum / 2.0);

			double absLogPost = axisRows[0].getAbsoluteLogPostValue();
			double relaxation = Klein_Method_Default_Values.GNSS_PERCENTAGE_RELAXATION_FACTOR;
			// normierte Aufteilung (Option): Faktor = 1 + log10(F) * 2 * Anteil.
			double rawLage = splitNormalization
					? sigmaLageOld * Math.max(0.1, 1.0 + Math.log10(absLogPost) * 2.0 * weightLage)
					: this.computeIndividualUncertainty(sigmaLageOld, absLogPost) * weightLage;
			double rawHoehe = splitNormalization
					? sigmaHoeheOld * Math.max(0.1, 1.0 + Math.log10(absLogPost) * 2.0 * weightHoehe)
					: this.computeIndividualUncertainty(sigmaHoeheOld, absLogPost) * weightHoehe;
			double sigmaLageNew = sigmaLageOld + relaxation * (rawLage - sigmaLageOld);
			double sigmaHoeheNew = sigmaHoeheOld + relaxation * (rawHoehe - sigmaHoeheOld);

			double[] result = new double[3];
			for (int i = 0; i < 3; i++) {
				double u2 = up[i] * up[i];
				result[i] = Math.sqrt(Math.max(0.0, (1.0 - u2) * sigmaLageNew * sigmaLageNew + u2 * sigmaHoeheNew * sigmaHoeheNew));
				newValues.put(axisRows[i], result[i]);
			}

			if (outAdjustmentDetails != null) {
				double inclination = Math.toDegrees(Math.atan2(absU, h));
				outAdjustmentDetails.add("[Klein_Method MATHE-DEBUG LH] Basislinie " + axisRows[0].getObservationId()
						+ " | Anteil_Lage=" + Klein_Method_Format.format(weightLage, "", decimalPlaces)
						+ " | Anteil_Hoehe=" + Klein_Method_Format.format(weightHoehe, "", decimalPlaces)
						+ " | Neigung_EP=" + Klein_Method_Format.format(inclination, "Grad", 3)
						+ " | Breite=" + Klein_Method_Format.format(Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, up[2])))), "Grad", 4)
						+ " | sigma_Lage " + Klein_Method_Format.format(sigmaLageOld, unit, decimalPlaces) + " -> " + Klein_Method_Format.format(sigmaLageNew, unit, decimalPlaces)
						+ " | sigma_Hoehe " + Klein_Method_Format.format(sigmaHoeheOld, unit, decimalPlaces) + " -> " + Klein_Method_Format.format(sigmaHoeheNew, unit, decimalPlaces)
						+ " | sigma_neu_X=" + Klein_Method_Format.format(result[0], unit, decimalPlaces)
						+ ", sigma_neu_Y=" + Klein_Method_Format.format(result[1], unit, decimalPlaces)
						+ ", sigma_neu_Z=" + Klein_Method_Format.format(result[2], unit, decimalPlaces));
			}
			return true;
		}
		public double computeIndividualUncertainty(double baseValue, double absLogPostValue) {
			return baseValue + baseValue * Klein_Method_Functions.this.individualStepDamping * Math.log10(absLogPostValue);
		}
		/**
		 * PHASE 2, FALL 1 - kombiniert die 3 (bereits in Phase 1 kalibrierten) Gruppenparameter σa/σb/σc UND die
		 * Entfernung DIESER Beobachtung per Varianzfortpflanzung zur Basis-Messunsicherheit dieser Beobachtung.
		 * Bestaetigt anhand des offiziellen JAG3D-Forenbeitrags (software.applied-geodesy.org/forum/?id=13047, Autor:
		 * Micha/ Entwickler von JAG3D):
		 *
		 * RICHTUNGSTYPEN (Horizontalrichtung, Vertikalrichtung - Ergebnis in GON): sigma_a liegt bereits in mgon vor
		 * (->/1000 fuer gon). sigma_b/sigma_c liegen in mm vor (->/1000 fuer m), muessen erst durch sqrt(d) bzw. d
		 * geteilt und per arcsin in ein Winkelmass (rad, dann ->gon) umgerechnet werden, BEVOR sie mit sigma_a
		 * quadratisch kombiniert werden: Basis[gon] = sqrt( sigma_a[gon]^2 + asin(sigma_b[m]/sqrt(d))_gon^2 +
		 * asin(sigma_c[m]/d)_gon^2)
		 *
		 * STRECKENTYPEN + NIVELLEMENT (Ergebnis in M, da sigma_a/sigma_b in mm ->/1000 fuer m, sigma_c in PPM - waechst
		 * PROPORTIONAL mit der Entfernung statt wie bei Richtungen zu schrumpfen, daher MULTIPLIKATION statt Division;
		 * Formel fuer sigma_c per User bestaetigt): Basis[m] = sqrt( sigma_a[m]^2 + (sigma_b[m] * sqrt(d[m]/1000))^2 +
		 * (sigma_c[ppm] * d[m] / 1e6)^2)
		 *
		 * Entfernung d: distance_0, sonst aus den Naeherungs-koordinaten
		 * (Klein_Method_Database_Handler.uncertaintyModelDistance()); GNSS je Achse |Komponente|. d == 0 -> nur sigma_a.
		 */
		public double computeIndividualUncertaintyBasis(Klein_Method_Threshold_Type type, double sigmaA, double sigmaB, double sigmaC, double distance) {
			boolean isDirectionType = type == Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST
					|| type == Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST;

			if (isDirectionType) {
				double sigmaAGon = sigmaA / 1000.0; // mgon -> gon
				double sigmaBMeter = sigmaB / 1000.0; // mm -> m
				double sigmaCMeter = sigmaC / 1000.0; // mm -> m

				double termBGon = 0.0;
				double termCGon = 0.0;
				if (distance > 0.0) {
					double ratioB = Math.min(sigmaBMeter / Math.sqrt(distance), 1.0); // asin-Definitionsbereich absichern
					double ratioC = Math.min(sigmaCMeter / distance, 1.0);
					termBGon = Math.asin(ratioB) * (200.0 / Math.PI); // rad -> gon
					termCGon = Math.asin(ratioC) * (200.0 / Math.PI);
				}
				return Math.sqrt(sigmaAGon * sigmaAGon + termBGon * termBGon + termCGon * termCGon);
			}
			else {
				double sigmaAMeter = sigmaA / 1000.0; // mm -> m
				double sigmaBMeter = sigmaB / 1000.0; // mm -> m

				double termBMeter = 0.0;
				double termCMeter = 0.0;
				if (distance > 0.0) {
					// wie JAG3D (SlopeDistanceGroup/HorizontalDistanceGroup/ DeltaZGroup/GNSSBaseline*Group.getStdB):
					// sigma_b * sqrt(d[km]). Bis faelschlich sigma_b / sqrt(d[m]) (Richtungsformel).
					termBMeter = sigmaBMeter * Math.sqrt(distance / 1000.0);
					termCMeter = sigmaC * distance / 1_000_000.0; // ppm * m / 1e6 -> m
				}
				return Math.sqrt(sigmaAMeter * sigmaAMeter + termBMeter * termBMeter + termCMeter * termCMeter);
			}
		}
		/**
		 * PHASE 2 - true, wenn die fertig berechnete individuelle Messunsicherheit
		 * ihre Obergrenze erreicht/ueberschreitet. Siehe Obergrenzen-Regel:
		 * in diesem Fall muss die Automatik SOFORT abbrechen (Klein_Method_Parameter.
		 * setIndividualUncertaintyCeilingExceeded(true)).
		 */
		public boolean isIndividualUncertaintyCeilingExceeded(double newValue, double ceilingValue) {
			return newValue >= ceilingValue;
		}	}

	// ============================================================================================================
	/** EP-Einzelschritt (Bewertungsmethode EP). */
	private final class Ep_Part {

		/**
		 * BEWERTUNGSMETHODE EP - Einzelschritt: korrigiert GENAU EINE Beobachtung pro Aufruf - die mit dem GROESSTEN
		 * aktuellen EP-Wert ueber ALLE 4 eligiblen Typen hinweg (Nivellementszug bewusst AUSGENOMMEN), deren |logPost|
		 * noch > 1.0 ist (die "Untergrenze" der logPost-Reduktion - NICHT zu verwechseln mit einer Untergrenze der
		 * individuellen Messunsicherheit, die es weiterhin nicht gibt).
		 *
		 * Ist die Beobachtung mit dem groessten EP bereits bei |logPost|<=1.0 angekommen (keine weitere Reduktion mehr
		 * moeglich), wird die NAECHST-GROESSTE EP-Beobachtung mit |logPost|>1.0 gesucht, usw.
		 *
		 * Nutzt DIESELBE Formel wie Phase 2 (Fall1 Basis aus Gruppenparametern bzw. Fall2 bestehender Wert, dann
		 * neu=alt+alt*LOG10(|logPost|)) - OHNE die Gruppenunsicherheit selbst zu veraendern. Die Obergrenze bleibt als
		 * Sicherheitsnetz aktiv (User bestaetigt).
		 *
		 * @return null bei normaler Korrektur (Detail in outAdjustmentDetails). Ein Hinweistext, wenn entweder KEINE
		 * Beobachtung mehr korrigiert werden konnte (alle bei |logPost|<=1.0) ODER die MEHRHEIT der aktuell groessten
		 * EP-Werte bereits nahe Null ist.
		 */
		public String performEpSingleStep(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler,
				List<String> outAdjustmentDetails) throws SQLException {
			int decimalPlaces = parameter.getDecimalPlaces();

			List<Klein_Method_Ep_Row> allRows = new ArrayList<Klein_Method_Ep_Row>();
			for (Klein_Method_Threshold_Type type : EP_ELIGIBLE_TYPES_FOR_SINGLE_STEP) {
				// GNSS-Typen MUESSEN ueber den GNSS-eigenen Lesepfad laufen - siehe Begruendung bei
				// EP_ELIGIBLE_TYPES_FOR_SINGLE_STEP oben.
				if (GNSS_TYPES_FOR_SINGLE_STEP.contains(type))
					allRows.addAll(databaseHandler.readAllGnssObservationsForEpCalibration(type));
				else
					allRows.addAll(databaseHandler.readAllObservationsForEpCalibration(type));
			}

			if (outAdjustmentDetails != null && databaseHandler.readActiveObservationCounts().getOrDefault(org.applied_geodesy.adjustment.network.ObservationType.LEVELING, 0) > 0)
				outAdjustmentDetails.add("EP-Einzelschritt: Nivellement wird nicht nach EP bewertet (kleine Redundanzanteile, EP dort durch die Netzform hoch) - es bleibt bei logPost.");

			if (allRows.isEmpty())
				return "Keine Beobachtungen f\u00FCr die EP-Kalibrierung gefunden.";

			// EP-Rohwerte (Meter) in mm umrechnen, absteigend sortieren
			allRows.sort((a, b) -> Double.compare(
					LengthUnit.MILLIMETER.fromMeter(b.getEpValueRaw()),
					LengthUnit.MILLIMETER.fromMeter(a.getEpValueRaw())));

			// Mehrheits-Erkennung: wie viele der Beobachtungen liegen mit ihrem
			// EP-Wert bereits unter 5% des jeweiligen Grenzwerts ("nahe Null")?
			int nearZeroCount = 0;
			for (Klein_Method_Ep_Row row : allRows) {
				double epMm = LengthUnit.MILLIMETER.fromMeter(row.getEpValueRaw());
				double epThreshold = parameter.getThresholdValue(this.getEpTypeFor(row.getType()));
				if (epMm < epThreshold * EP_NEAR_ZERO_FRACTION_OF_THRESHOLD)
					nearZeroCount++;
			}
			boolean majorityNearZero = nearZeroCount > allRows.size() / 2;

			// Erste Beobachtung mit |logPost| > 1.0 suchen (Liste ist bereits nach EP absteigend sortiert)
			Klein_Method_Ep_Row target = null;
			for (Klein_Method_Ep_Row row : allRows) {
				if (row.getAbsoluteLogPostValue() > 1.0) {
					target = row;
					break;
				}
			}

			if (target == null) {
				return "Keine weitere Anpassung m\u00F6glich: alle Beobachtungen haben bereits |logPost| = 1.0 erreicht.";
			}

			Klein_Method_Threshold_Type type = target.getType();
			String unit = Klein_Method_Default_Values.getIndividualUncertaintyUnit(type);
			double ceilingValue = Klein_Method_Default_Values.getIndividualUncertaintyCeiling(type);

			double baseValue;
			if (target.hasNoIndividualUncertainty()) {
				double[] sigmaRaw = databaseHandler.readGroupUncertainty(target.getGroupId());
				double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], Klein_Method_Default_Values.getSigmaAUnit(type));
				double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], Klein_Method_Default_Values.getSigmaBUnit(type));
				double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], Klein_Method_Default_Values.getSigmaCUnit(type));
				baseValue = Klein_Method_Functions.this.computeIndividualUncertaintyBasis(type, sigmaA, sigmaB, sigmaC, target.getDistance());
			}
			else {
				baseValue = Klein_Method_Functions.this.fromDatabaseValue(target.getCurrentIndividualUncertaintyRaw(), unit);
			}

			double newValue = Klein_Method_Functions.this.computeIndividualUncertainty(baseValue, target.getAbsoluteLogPostValue());

			if (Klein_Method_Functions.this.isIndividualUncertaintyCeilingExceeded(newValue, ceilingValue)) {
				parameter.setIndividualUncertaintyCeilingExceededType(type);
				return null; // Aufrufer erkennt den Abbruch an parameter.isIndividualUncertaintyCeilingExceeded()
			}

			// Exakt dieselbe Fallunterscheidung wie in Phase 2 (performIndividualCalibrationStep()) - keine Vermischung
			// der Achsen.
			if (target.getAxis() != null)
				databaseHandler.writeGnssIndividualUncertainty(target.getObservationId(), target.getAxis(), Klein_Method_Functions.this.toDatabaseValue(newValue, unit));
			else
				databaseHandler.writeIndividualUncertainty(target.getObservationId(), Klein_Method_Functions.this.toDatabaseValue(newValue, unit));

			if (outAdjustmentDetails != null) {
				outAdjustmentDetails.add("sigma_0 (EP-Einzelschritt) " + type.getDisplayName()
						+ (target.getAxis() != null ? " Achse " + target.getAxis() : "") + " Beobachtung " + target.getObservationId() + ": "
						+ Klein_Method_Format.format(baseValue, unit, decimalPlaces) + " -> " + Klein_Method_Format.format(newValue, unit, decimalPlaces)
						+ " (EP war " + Klein_Method_Format.format(LengthUnit.MILLIMETER.fromMeter(target.getEpValueRaw()), "mm", decimalPlaces) + ")");
			}

			if (majorityNearZero) {
				return "Hinweis: Die Mehrheit der aktuell gr\u00F6\u00DFten EP-Werte liegt bereits nahe Null - "
						+ "weitere Anpassungen sind vermutlich kaum noch n\u00F6tig.";
			}
			return null;
		}
		/**
		 * Liefert den zu einem logPost-Grenzwert-Typ passenden EP-Grenzwert-Typ
		 * (gleicher Beobachtungstyp, andere Kategorie) - fuer performEpSingleStep().
		 */
		private Klein_Method_Threshold_Type getEpTypeFor(Klein_Method_Threshold_Type logPostType) {
			switch (logPostType) {
			case HORIZONTAL_DIRECTION_LOG_POST: return Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_EP;
			case HORIZONTAL_DISTANCE_LOG_POST:  return Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_EP;
			case VERTICAL_DIRECTION_LOG_POST:   return Klein_Method_Threshold_Type.VERTICAL_DIRECTION_EP;
			case SLOPE_DISTANCE_LOG_POST:       return Klein_Method_Threshold_Type.SLOPE_DISTANCE_EP;
			case NIVELLEMENT_LOG_POST:          return Klein_Method_Threshold_Type.NIVELLEMENT_EP;
			// GNSS-Basislinien. dieser Zweig wird jetzt TATSAECHLICH erreicht.
			case GNSS_BASELINE_1D_LOG_POST:     return Klein_Method_Threshold_Type.GNSS_BASELINE_1D_EP;
			case GNSS_BASELINE_2D_LOG_POST:     return Klein_Method_Threshold_Type.GNSS_BASELINE_2D_EP;
			case GNSS_BASELINE_3D_LOG_POST:     return Klein_Method_Threshold_Type.GNSS_BASELINE_3D_EP;
			default: throw new IllegalArgumentException("Kein EP-Typ fuer: " + logPostType);
			}
		}	}

	// ============================================================================================================
	/** Dynamische Ausgleichung: Punktgruppen- und Einzelpunkt-Kalibrierung der Anschlusspunkte. */
	private final class Dynamic_Part {

		/** PHASE 1 der dynamischen AGL planen (siehe Blockkommentar oben). */
		public Klein_Method_Dynamic_Plan planPointGroupCalibrationStep(Klein_Method_Parameter parameter,
				Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
			Klein_Method_Dynamic_Plan plan = new Klein_Method_Dynamic_Plan("Phase 1 (Punktgruppen)", parameter.getInternalCycleCount() + 1);
			double minFactor = Klein_Method_Default_Values.DYNAMIC_MIN_FACTOR;
			// keine Vorhersage mehr in Phase 1 - die Option "alle Punkte und Gruppen" ist entfernt, Phase 1 rechnet nur
			// mit der Formel.
			boolean predictionEnabled = false;

			Map<Integer, Integer> groupIdToDimension = databaseHandler.readStochasticPointGroupIds();
			boolean allDone = true;
			int doneCount = 0;
			Map<Integer, int[]> individualPerGroup = new LinkedHashMap<Integer, int[]>();
			for (Klein_Method_Point_Tprio_Row row : databaseHandler.readStochasticPointTprioValues()) {
				int[] counts = individualPerGroup.computeIfAbsent(row.getGroupId(), id -> new int[2]);
				counts[0]++;
				if (individualSigma(row) > 0.0)
					counts[1]++;
			}
			for (Map.Entry<Integer, Integer> mapEntry : groupIdToDimension.entrySet()) {
				Klein_Method_Dynamic_Plan.Entry e = new Klein_Method_Dynamic_Plan.Entry();
				e.group = true;
				e.id = mapEntry.getKey();
				e.dimension = mapEntry.getValue();
				e.name = "Gruppe " + e.id;
				e.valueLabel = "F";

				// sigma alt = Gruppenunsicherheit aus den Gruppeneigenschaften ("PointGroupUncertainty"), NICHT die
				// Einzelwerte der Punkte.
				double[] groupSigma = databaseHandler.readPointGroupUncertainty(e.id);
				Double sy = e.dimension == 1 ? null : groupSigma[0];
				Double sx = e.dimension == 1 ? null : groupSigma[1];
				Double sz = e.dimension == 2 ? null : groupSigma[2];
				e.sigmaOld = commonSigma(e.dimension, sy, sx, sz);
				e.sigmaSource = "Gruppe";
				StringBuilder limits = new StringBuilder();
				if (componentsDiffer(e.dimension, sy, sx, sz))
					limits.append("Komponenten ungleich (y ").append(Klein_Method_Dynamic_Plan.mm(sy == null ? Double.NaN : sy))
							.append(" / x ").append(Klein_Method_Dynamic_Plan.mm(sx == null ? Double.NaN : sx))
							.append(" / z ").append(Klein_Method_Dynamic_Plan.mm(sz == null ? Double.NaN : sz))
							.append(" mm) -> gemeinsamer Wert aus ").append(e.dimension == 1 ? "z" : "y");

				double f = databaseHandler.readPointVarianceRatio(e.dimension);
				e.value = f;
				boolean validF = !Double.isNaN(f) && !Double.isInfinite(f) && f > 0.0;
				e.inBand = validF && isInDynamicBand(f);

				int[] individualCounts = individualPerGroup.get(e.id);
				if (Double.isNaN(e.sigmaOld) || e.sigmaOld <= 0.0) {
					e.write = false;
					e.done = true;
					e.sigmaNew = e.sigmaOld;
					e.mode = "kein sigma";
					e.formula = "-";
				} else if (individualCounts != null && individualCounts[0] > 0 && individualCounts[1] == individualCounts[0]) {
					// alle Punkte der Gruppe haben Einzelwerte -> Gruppenwert wirkungslos
					e.write = false;
					e.done = true;
					e.sigmaNew = e.sigmaOld;
					e.mode = "alle Punkte mit Einzelwert - Gruppenwert wirkungslos, nicht veraendert";
					e.formula = "sigma_neu = sigma_alt = " + Klein_Method_Dynamic_Plan.mm(e.sigmaOld) + " mm";
				} else if (!validF) {
					e.done = true;
					e.sigmaNew = e.sigmaOld;
					e.mode = "keine gueltige VKS";
					e.formula = "sigma_neu = sigma_alt = " + Klein_Method_Dynamic_Plan.mm(e.sigmaOld) + " mm";
				} else {
					double raw = 1.0 + Math.log10(f);
					double plainFactor = Math.max(raw, minFactor);
					String sOld = Klein_Method_Dynamic_Plan.mm(e.sigmaOld);
					String plainFormula = "sigma_neu = " + sOld + " * max(1 + log10(" + Klein_Method_Dynamic_Plan.num(f, 6)
							+ "); 0,3) = " + sOld + " * " + Klein_Method_Dynamic_Plan.num(plainFactor, 6);
					String plainLimit = raw < minFactor ? "Mindestfaktor 0,3 (1 + log10(F) = " + Klein_Method_Dynamic_Plan.num(raw, 6) + ")" : null;

					Klein_Method_Parameter.Klein_Method_Point_Turbo_State state = parameter.getGroupPredictionState(e.id);
					boolean outside = !e.inBand;
					this.stepFloor = parameter.getPointFloor(e.dimension);
					DynamicStep step = this.computeDynamicStep(state, e.name, e.sigmaOld, f, e.inBand, plainFactor, plainFormula, plainLimit,
							predictionEnabled, predictionEnabled && outside, false, false, Double.NaN, Double.NaN, limits);
					e.reaction = step.reaction;
					double newValue = e.sigmaOld * step.factor;
					e.formula = step.formula + " = " + Klein_Method_Dynamic_Plan.mm(newValue) + " mm";
					e.mode = step.mode;
					e.sigmaNew = applyPointLimits(e.sigmaOld, newValue, parameter.getPointJumpValue(e.dimension), parameter.getPointFloor(e.dimension), f, state, e.name, limits);
					updateDynamicState(state, e.sigmaOld, e.sigmaNew, f, step.turboUsed, outside);
					e.done = e.inBand || (isAtPointFloor(e.sigmaOld, parameter.getPointFloor(e.dimension)) && f < Klein_Method_Default_Values.DYNAMIC_BAND_MIN);
				}
				e.limits = limits.toString();
				if (e.done)
					doneCount++;
				else
					allDone = false;
				plan.getEntries().add(e);
			}

			plan.setAllDone(allDone);
			// Einzelwerte haben in JAG3D Vorrang vor dem Gruppenwert - zu Beginn der dynamischen AGL sollte es keine
			// geben (nur Hinweis, kein Abbruch).
			int individualCount = 0;
			for (Klein_Method_Point_Tprio_Row row : databaseHandler.readStochasticPointTprioValues())
				if (individualSigma(row) > 0.0)
					individualCount++;
			if (individualCount > 0) {
				String hint = "HINWEIS: " + individualCount + " Festpunkt(e) haben bereits Einzelwerte - diese haben in JAG3D Vorrang vor dem "
						+ "Gruppenwert, Phase 1 wirkt fuer sie nicht.";
				plan.getHeaderLines().add(hint);
				new Klein_Method_Logger().log("Klein_Method: " + hint);
			}
			plan.getHeaderLines().add("Band " + Klein_Method_Dynamic_Plan.num(Klein_Method_Default_Values.DYNAMIC_BAND_MIN, 3) + " - "
					+ Klein_Method_Dynamic_Plan.num(Klein_Method_Default_Values.DYNAMIC_BAND_MAX, 3) + " (Ziel 1,000) | Gruppen fertig: "
					+ doneCount + "/" + groupIdToDimension.size() + " | Formel: sigma_neu = sigma_alt * max(1 + log10(F); 0,3)"
					+ ", ohne Turbo");
			plan.getHeaderLines().add("Sprungwerte (fest, Wert <= Sprungwert -> Untergrenze, nur beim Verkleinern ausserhalb des Bands; Ruecksprung bei Wert > 1,004 an der Untergrenze): 1D " + Klein_Method_Dynamic_Plan.mm(parameter.getPointJumpValue1D()) + " mm, 2D/3D "
					+ Klein_Method_Dynamic_Plan.mm(parameter.getPointJumpValue23D()) + " mm | Untergrenze 1D "
					+ Klein_Method_Dynamic_Plan.mm(parameter.getPointFloor(1)) + " mm, 2D/3D " + Klein_Method_Dynamic_Plan.mm(parameter.getPointFloor(3)) + " mm");
			plan.getHeaderLines().add(allDone
					? "Alle Gruppen fertig - diese Korrektur wird noch geschrieben, danach beginnt Phase 2."
					: "Noch nicht alle Gruppen fertig - alle Gruppen werden korrigiert, Phase 1 laeuft weiter.");
			return plan;
		}
		/**
		 * PHASE 2 der dynamischen AGL planen (siehe Blockkommentar oben). Aktualisiert dabei den Turbo-Zustand je Punkt
		 * (Klein_Method_Parameter.getPointTurboState()).
		 */
		public Klein_Method_Dynamic_Plan planPointIndividualCalibrationStep(Klein_Method_Parameter parameter,
				Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
			Klein_Method_Dynamic_Plan plan = new Klein_Method_Dynamic_Plan("Phase 2 (einzelne Festpunkte)", parameter.getInternalCycleCount() + 1);
			List<Klein_Method_Point_Tprio_Row> points = databaseHandler.readStochasticPointTprioValues();

			double bandMin = Klein_Method_Default_Values.DYNAMIC_BAND_MIN;
			double bandMax = Klein_Method_Default_Values.DYNAMIC_BAND_MAX;
			double minFactor = Klein_Method_Default_Values.DYNAMIC_MIN_FACTOR;
			double zero = Klein_Method_Default_Values.DYNAMIC_TPOST_ZERO;

			// 1) fertige Punkte VOR diesem Schritt zaehlen -> Turbo-Aktivierung
			int doneCount = 0;
			for (Klein_Method_Point_Tprio_Row row : points) {
				Double t = row.getTprio();
				if (t == null || Double.isNaN(t))
					continue;
				double sigma = individualSigma(row);
				if (isInDynamicBand(t) || t <= zero || (sigma > 0.0 && isAtPointFloor(sigma, parameter.getPointFloor(row.getDimension())) && t < bandMin)
						|| (parameter.getPointTurboState(row.getPointId()).bracketSettled && Math.abs(Math.log(t)) <= Klein_Method_Default_Values.DYNAMIC_BRACKET_UNSETTLE_LN))
					doneCount++;
			}
			int required = (int) Math.ceil(Klein_Method_Default_Values.TURBO_ACTIVATION_FRACTION * points.size());
			boolean turboEnabled = parameter.isTurboEnabled();
			boolean turboActive = turboEnabled && !points.isEmpty() && doneCount >= required;
			boolean allDone = doneCount == points.size();
			plan.setAllDone(allDone);
			plan.setFinalStep(allDone);

			plan.getHeaderLines().add("Band " + Klein_Method_Dynamic_Plan.num(bandMin, 3) + " - "
					+ Klein_Method_Dynamic_Plan.num(bandMax, 3) + " (Ziel T_prio = 1,000) | Punkte fertig: "
					+ doneCount + "/" + points.size() + " | Turbo: " + (turboEnabled ? "ja" : "nein")
					+ (turboEnabled ? " -> aktiv ab " + required + " fertigen Punkten -> " + (turboActive ? "AKTIV" : "noch nicht aktiv") : ""));
			plan.getHeaderLines().add("Formel: sigma_neu = sigma_alt * max(1 + Turbo * log10(W); 0,3), W = max(T_prio; 0,5), Turbo = 1 ohne Turbo | "
					+ "fertig: T_prio im Band ODER T_prio <= 0,000001 ODER (Untergrenze UND T_prio < " + Klein_Method_Dynamic_Plan.num(bandMin, 3) + ")");
			plan.getHeaderLines().add("Sprungwerte (fest, Wert <= Sprungwert -> Untergrenze, nur beim Verkleinern ausserhalb des Bands; Ruecksprung bei Wert > 1,004 an der Untergrenze): 1D " + Klein_Method_Dynamic_Plan.mm(parameter.getPointJumpValue1D()) + " mm, 2D/3D "
					+ Klein_Method_Dynamic_Plan.mm(parameter.getPointJumpValue23D()) + " mm | Untergrenze 1D "
					+ Klein_Method_Dynamic_Plan.mm(parameter.getPointFloor(1)) + " mm, 2D/3D " + Klein_Method_Dynamic_Plan.mm(parameter.getPointFloor(3)) + " mm");
			plan.getHeaderLines().add(allDone
					? "Alle Punkte fertig - der Lauf endet, es wird NICHTS mehr geschrieben (Stand = zuletzt ausgewerteter Stand)."
					: "Noch nicht alle Punkte fertig - alle Punkte werden korrigiert (auch die im Band).");

			Map<Integer, double[]> groupSigmaCache = new LinkedHashMap<Integer, double[]>();
			for (Klein_Method_Point_Tprio_Row row : points) {
				Klein_Method_Dynamic_Plan.Entry e = new Klein_Method_Dynamic_Plan.Entry();
				e.group = false;
				e.id = row.getPointId();
				e.name = row.getName();
				e.dimension = row.getDimension();
				e.valueLabel = "T_prio";
				// Einzelwerte werden erst in Phase 2 geschrieben. Hat ein Punkt noch keinen Einzelwert (> 0), gilt wie in
				// JAG3D der Gruppenwert.
				StringBuilder limits = new StringBuilder();
				double individual = individualSigma(row);
				if (individual > 0.0) {
					e.sigmaOld = individual;
					e.sigmaSource = "Einzelwert";
					if (componentsDiffer(e.dimension, row.getSigmaY0(), row.getSigmaX0(), row.getSigmaZ0()))
						limits.append("Komponenten ungleich -> gemeinsamer Wert aus ").append(e.dimension == 1 ? "z" : "y");
				} else {
					double[] groupSigma = groupSigmaCache.get(row.getGroupId());
					if (groupSigma == null) {
						groupSigma = databaseHandler.readPointGroupUncertainty(row.getGroupId());
						groupSigmaCache.put(row.getGroupId(), groupSigma);
					}
					e.sigmaOld = e.dimension == 1 ? groupSigma[2] : groupSigma[0];
					e.sigmaSource = "Gruppe";
				}
				e.sigmaNew = e.sigmaOld;

				Double tObj = row.getTprio();
				double t = tObj == null ? Double.NaN : tObj;
				e.value = t;
				e.inBand = isInDynamicBand(t);

				if (Double.isNaN(t) || Double.isNaN(e.sigmaOld) || e.sigmaOld <= 0.0) {
					e.done = false;
					e.write = false;
					e.mode = Double.isNaN(t) ? "kein T_prio - nicht bewertet" : "kein sigma - nicht bewertet";
					e.formula = "-";
					e.limits = limits.toString();
					plan.getEntries().add(e);
					continue;
				}

				boolean atFloor = isAtPointFloor(e.sigmaOld, parameter.getPointFloor(e.dimension));
				Klein_Method_Parameter.Klein_Method_Point_Turbo_State state = parameter.getPointTurboState(e.id);
				// ausgeschoepfter Punkt: weicht T_prio inzwischen mehr als die Toleranz von 1 ab, wird er wieder bearbeitet
				if (state.bracketSettled && Math.abs(Math.log(t)) > Klein_Method_Default_Values.DYNAMIC_BRACKET_UNSETTLE_LN) {
					state.bracketSettled = false;
					state.bracketSigmaTooSmall = Double.NaN;
					state.bracketSigmaTooLarge = Double.NaN;
					limits.append("Intervall ausgeschoepft aufgehoben (T_prio ausserhalb der Toleranz)");
				}
				e.done = e.inBand || t <= zero || (atFloor && t < bandMin) || state.bracketSettled;
				// gemessene Reaktion p IMMER mitprotokollieren (auch ohne Vorhersage)
				e.reaction = measureReaction(state, e.sigmaOld, t);

				if (allDone) {
					// Lauf endet - nichts mehr schreiben
					e.write = false;
					e.mode = "Ende - nicht geschrieben";
					e.formula = "-";
					e.limits = limits.toString();
					plan.getEntries().add(e);
					continue;
				}

				if (state.bracketSettled) {
					e.write = false;
					e.mode = "Intervall ausgeschoepft - sigma bestimmt, T_prio haengt vom uebrigen Netz ab";
					e.formula = "sigma_neu = sigma_alt = " + Klein_Method_Dynamic_Plan.mm(e.sigmaOld) + " mm";
					e.limits = limits.toString();
					plan.getEntries().add(e);
					continue;
				}

				if (t <= zero) {
					e.write = false;
					e.mode = "T_prio <= 0,000001 - nicht bewegt";
					e.formula = "sigma_neu = sigma_alt = " + Klein_Method_Dynamic_Plan.mm(e.sigmaOld) + " mm";
					e.limits = limits.toString();
					plan.getEntries().add(e);
					continue;
				}

				double w = Math.max(t, Klein_Method_Default_Values.DYNAMIC_MIN_TPOST_WEIGHT);
				// log10 statt ln - T_post reagiert etwa quadratisch auf sigma (gemessen p ~ 2), 1 + ln(T) korrigierte
				// doppelt so stark wie noetig und liess die Punkte dauerhaft pendeln (FP10: 0,84 <-> 1,26). log10 =
				// ln/2,3 passt dazu, wie in Phase 1.
				double lnW = Math.log10(w); // Name beibehalten: jetzt log10(W)
				double plainFactor = Math.max(1.0 + lnW, minFactor);
				String sOld = Klein_Method_Dynamic_Plan.mm(e.sigmaOld);
				String plainFormula = "sigma_neu = " + sOld + " * max(1 + log10(" + Klein_Method_Dynamic_Plan.num(w, 6) + "); 0,3) = " + sOld + " * "
						+ Klein_Method_Dynamic_Plan.num(plainFactor, 6);
				String plainLimit = 1.0 + lnW < minFactor ? "Mindestfaktor 0,3" : null;
				boolean outside = !e.inBand;
				boolean reduce = t < Klein_Method_Default_Values.DYNAMIC_TURBO_PREDICTION_LIMIT;
				boolean predictionAllowed = turboActive && reduce;
				boolean addonAllowed = turboActive && reduce;

				this.stepFloor = parameter.getPointFloor(e.dimension);
				// Intervallhalbierung: ist ein sigma mit T_prio ueber und eines mit T_prio unter dem Band bekannt, liegt das
				// Ziel dazwischen - geometrisches Mittel statt vieler kleiner Formelschritte (unempfindliche Punkte)
				double bisected = updatePointBracket(state, e.sigmaOld, t, atFloor, e.reaction);
				if (!e.done && !Double.isNaN(bisected)) {
					String low = Klein_Method_Dynamic_Plan.mm(state.bracketSigmaTooSmall), high = Klein_Method_Dynamic_Plan.mm(state.bracketSigmaTooLarge);
					double floorValue = parameter.getPointFloor(e.dimension);
					e.sigmaNew = Math.min(Math.max(bisected, floorValue), Klein_Method_Default_Values.POINT_UNCERTAINTY_CEILING);
					e.mode = "Intervallhalbierung (Ziel zwischen " + low + " und " + high + " mm)";
					e.formula = "sigma_neu = sqrt(" + low + " * " + high + ") = " + Klein_Method_Dynamic_Plan.mm(e.sigmaNew) + " mm";
					e.limits = limits.toString();
					updateDynamicState(state, e.sigmaOld, e.sigmaNew, t, false, outside);
					plan.getEntries().add(e);
					continue;
				}
				DynamicStep step = this.computeDynamicStep(state, e.name, e.sigmaOld, t, e.inBand, plainFactor, plainFormula, plainLimit,
						turboEnabled, predictionAllowed, addonAllowed, turboEnabled && reduce, w, lnW, limits);
				double newValue = e.sigmaOld * step.factor;
				e.formula = step.formula + " = " + Klein_Method_Dynamic_Plan.mm(newValue) + " mm";
				e.sigmaNew = applyPointLimits(e.sigmaOld, newValue, parameter.getPointJumpValue(e.dimension), parameter.getPointFloor(e.dimension), t, state, "Festpunkt " + e.name, limits);
				e.mode = step.mode;
				e.limits = limits.toString();
				updateDynamicState(state, e.sigmaOld, e.sigmaNew, t, step.turboUsed, outside);

				plan.getEntries().add(e);
			}
			return plan;
		}
		/**
		 * gemeinsame Schrittberechnung fuer Phase 1 (Wert = F, Gruppe) und Phase 2 (Wert = T_prio, Punkt):
		 *
		 * 1. Ueberschwing-Sperre (Richtungswechsel nach Turbo-Schritt von ausserhalb des Bands: 1. Mal 5 Schritte
		 * eingefroren, 2. Mal gesperrt)
		 *
		 * 2. eingefroren -> normale Formel
		 *
		 * 3. Vorhersage erlaubt -> Faktor = exp(ln Wert / p), max. 10-fache Formelstaerke, min. 0,3; nur Phase 2, nur
		 * Verkleinern
		 *
		 * 4. sonst Zuschlag (nur Phase 2, Verkleinern, ab 50 %) bzw. normale Formel
		 */
		// Untergrenze des gerade berechneten Festpunkts bzw. der Gruppe (Meter)
		private double stepFloor = Klein_Method_Default_Values.POINT_UNCERTAINTY_FLOOR;

		private DynamicStep computeDynamicStep(Klein_Method_Parameter.Klein_Method_Point_Turbo_State state, String name, double sigma, double value,
				boolean inBand, double plainFactor, String plainFormula, String plainLimit, boolean turboEnabled, boolean predictionAllowed,
				boolean addonAllowed, boolean insensitiveAllowed, double w, double lnW, StringBuilder limits) {
			double minFactor = Klein_Method_Default_Values.DYNAMIC_MIN_FACTOR;
			DynamicStep step = new DynamicStep();
			step.factor = plainFactor;
			step.reaction = measureReaction(state, sigma, value);
			StringBuilder mode = new StringBuilder();
			int desiredDirection = plainFactor < 1.0 - 1e-12 ? -1 : (plainFactor > 1.0 + 1e-12 ? 1 : 0);

			// 1) Ueberschwing-Sperre
			if (turboEnabled && !state.locked && state.lastStepTurbo && state.lastOutsideBand
					&& state.lastDirection != 0 && desiredDirection != 0 && desiredDirection != state.lastDirection) {
				state.directionChanges++;
				if (state.directionChanges >= 2) {
					state.locked = true;
					state.freezeStepsLeft = 0;
					mode.append("TURBO gesperrt (2. Richtungswechsel); ");
					new Klein_Method_Logger().log("Klein_Method: " + name + " - TURBO gesperrt (2. Richtungswechsel), Wert "
							+ Klein_Method_Dynamic_Plan.num(value, 6));
				} else {
					state.freezeStepsLeft = Klein_Method_Default_Values.DYNAMIC_TURBO_FREEZE_STEPS;
					mode.append("TURBO eingefroren (").append(Klein_Method_Default_Values.DYNAMIC_TURBO_FREEZE_STEPS).append(" Schritte, normale Formel); ");
					new Klein_Method_Logger().log("Klein_Method: " + name + " - TURBO eingefroren ("
							+ Klein_Method_Default_Values.DYNAMIC_TURBO_FREEZE_STEPS + " Schritte, normale Formel), Wert " + Klein_Method_Dynamic_Plan.num(value, 6));
				}
			}

			String sOld = Klein_Method_Dynamic_Plan.mm(sigma);
			double maxStep = Klein_Method_Default_Values.DYNAMIC_TURBO_MAX_STRENGTH * Math.abs(Math.log(plainFactor));

			// 2) eingefroren -> normale Formel
			if (turboEnabled && state.freezeStepsLeft > 0) {
				state.freezeStepsLeft--;
				mode.append("eingefroren (noch ").append(state.freezeStepsLeft).append(") -> normale Formel");
				step.formula = plainFormula;
				if (plainLimit != null)
					appendLimit(limits, plainLimit);
				step.mode = mode.toString();
				return step;
			}

			// 3a) T_prio reagiert kaum auf das Verkleinern -> das Verkleinern endet ohnehin am Sprungwert: direkt auf die
			// Untergrenze (spart viele kleine Schritte). Gilt sofort (Turbo-Schalter ein, Punkt unter dem Band), nicht erst ab
			// TURBO_ACTIVATION_FRACTION fertigen Punkten: solange der unempfindliche Punkt wandert, kommen die anderen
			// Punkte nicht ins Band (Protokoll 05.10.2026: 30 Iterationen knapp neben dem Band).
			if (insensitiveAllowed && !state.locked && !state.jumpLocked) {
				double r = step.reaction;
				// zusaetzlich muss T_prio selbst fast stillstehen - in den ersten Iterationen bewegt sich das ganze Netz,
				// dann ist p eines einzelnen Punkts nicht aussagekraeftig
				double dlnT = Double.isNaN(state.lastTpost) || state.lastTpost <= 0.0 || value <= 0.0 ? Double.NaN
						: Math.abs(Math.log(value) - Math.log(state.lastTpost));
				if (!Double.isNaN(r) && Math.abs(r) < Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_REACTION && state.lastDirection == -1
						&& !Double.isNaN(dlnT) && dlnT < Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_TPRIO_CHANGE
						// nur bei deutlichem sigma-Schritt und deutlich unter dem Band - Punkte knapp unter dem Band machen
						// nur winzige Schritte, dort sagt ein kleines p nichts
						&& value < Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_TPRIO_MAX
						&& !Double.isNaN(state.lastSigma) && state.lastSigma > 0.0
						&& Math.abs(Math.log(sigma / state.lastSigma)) >= Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_MIN_SIGMA_STEP)
					state.insensitiveCount++;
				else
					state.insensitiveCount = 0;
				if (state.insensitiveCount >= Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_STEPS) {
					double floorValue = this.stepFloor;
					step.factor = floorValue / sigma;
					step.turboUsed = true;
					mode.append("Turbo: T_prio reagiert kaum (p = ").append(Klein_Method_Dynamic_Plan.num(r, 4)).append(", ")
							.append(state.insensitiveCount).append(" Schritte) -> Untergrenze");
					step.formula = "|p| < " + Klein_Method_Dynamic_Plan.num(Klein_Method_Default_Values.DYNAMIC_INSENSITIVE_REACTION, 2)
							+ " in " + state.insensitiveCount + " Verkleinerungen in Folge -> sigma_neu = Untergrenze " + Klein_Method_Dynamic_Plan.mm(floorValue);
					step.mode = mode.toString();
					new Klein_Method_Logger().log("Klein_Method: " + name + " - T_prio reagiert kaum auf sigma (p = " + Klein_Method_Dynamic_Plan.num(r, 4)
							+ "), direkt auf die Untergrenze, Wert " + Klein_Method_Dynamic_Plan.num(value, 6));
					return step;
				}
			} else
				state.insensitiveCount = 0;

			// 3) Vorhersage
			String fallbackReason = null;
			if (predictionAllowed && !state.locked) {
				double reaction = step.reaction;
				if (Double.isNaN(state.lastSigma) || Double.isNaN(state.lastTpost))
					fallbackReason = "erste Iteration";
				else if (Double.isNaN(reaction))
					fallbackReason = "sigma unveraendert";
				else if (reaction < Klein_Method_Default_Values.DYNAMIC_TURBO_REACTION_MIN || reaction > Klein_Method_Default_Values.DYNAMIC_TURBO_REACTION_MAX)
					fallbackReason = "Tendenz unplausibel (p = " + Klein_Method_Dynamic_Plan.num(reaction, 4) + ")";
				if (fallbackReason == null) {
					double predicted = Math.exp(Math.log(value) / reaction);
					double capped = predicted;
					double lnP = Math.log(predicted);
					if (maxStep > 0.0 && Math.abs(lnP) > maxStep) {
						capped = Math.exp(Math.signum(lnP) * maxStep);
						appendLimit(limits, "Vorhersage auf 10-fache Formelstaerke begrenzt");
					}
					if (capped < minFactor) {
						capped = minFactor;
						appendLimit(limits, "Mindestfaktor 0,3");
					}
					step.factor = capped;
					step.turboUsed = Math.abs(Math.log(capped)) > 1e-12;
					mode.append("Turbo Vorhersage (p = ").append(Klein_Method_Dynamic_Plan.num(reaction, 4)).append(")");
					step.formula = "p = -(ln(" + Klein_Method_Dynamic_Plan.num(value, 6) + ") - ln(" + Klein_Method_Dynamic_Plan.num(state.lastTpost, 6) + ")) / (ln("
							+ sOld + ") - ln(" + Klein_Method_Dynamic_Plan.mm(state.lastSigma) + ")) = " + Klein_Method_Dynamic_Plan.num(reaction, 6)
							+ "; Faktor = exp(ln(" + Klein_Method_Dynamic_Plan.num(value, 6) + ") / p) = " + Klein_Method_Dynamic_Plan.num(predicted, 6)
							+ (capped != predicted ? " -> " + Klein_Method_Dynamic_Plan.num(capped, 6) : "")
							+ "; sigma_neu = " + sOld + " * " + Klein_Method_Dynamic_Plan.num(step.factor, 6);
					step.mode = mode.toString();
					return step;
				}
			}

			// 4) Rueckfall: Zuschlag (Phase 2, Verkleinern, ab 50 %) oder normale Formel
			if (addonAllowed && !state.locked && !Double.isNaN(lnW)) {
				double addon = computeDynamicTurboAddon(value);
				double raw = 1.0 + addon * lnW;
				step.factor = Math.max(raw, minFactor);
				step.turboUsed = addon > 1.0 && step.factor < 1.0;
				mode.append("Turbo Zuschlag ").append(Klein_Method_Dynamic_Plan.num(addon, 3));
				if (fallbackReason != null)
					mode.append(" (Rueckfall: ").append(fallbackReason).append(")");
				step.formula = "sigma_neu = " + sOld + " * max(1 + " + Klein_Method_Dynamic_Plan.num(addon, 4) + " * log10(" + Klein_Method_Dynamic_Plan.num(w, 6)
						+ "); 0,3) = " + sOld + " * " + Klein_Method_Dynamic_Plan.num(step.factor, 6);
				if (raw < minFactor)
					appendLimit(limits, "Mindestfaktor 0,3");
			} else {
				if (state.locked && turboEnabled)
					mode.append("normal (Turbo gesperrt)");
				else if (fallbackReason != null)
					mode.append("normal (Vorhersage: ").append(fallbackReason).append(")");
				else
					mode.append("normal");
				step.formula = plainFormula;
				if (plainLimit != null)
					appendLimit(limits, plainLimit);
			}
			step.mode = mode.toString();
			return step;
		}
		/**
		 * Abstand der offenen Elemente zum Band als Summe |ln Wert|. Verringert er sich ueber DYNAMIC_STAGNATION_WINDOW
		 * Iterationen nicht um mindestens 1 %, liefert die Methode die Liste der offenen Punkte/Gruppen (Lauf endet),
		 * sonst null.
		 */
		private String checkDynamicStagnation(Klein_Method_Parameter parameter, Klein_Method_Dynamic_Plan plan) {
			if (plan.isAllDone())
				return null;
			double metric = 0.0;
			List<String> open = new ArrayList<String>();
			for (Klein_Method_Dynamic_Plan.Entry e : plan.getEntries()) {
				if (e.done || Double.isNaN(e.value) || e.value <= 0.0)
					continue;
				metric += Math.abs(Math.log(e.value));
				open.add(e.name + " (" + e.valueLabel + " " + Klein_Method_Dynamic_Plan.num(e.value, 6) + ", sigma "
						+ Klein_Method_Dynamic_Plan.mm(e.sigmaOld) + " mm)");
			}
			// stetige sigma-Bewegung eines offenen Elements (gleiche Richtung wie zuletzt, je Schritt mindestens
			// DYNAMIC_STAGNATION_SIGMA_MOVE) gilt als Fortschritt - z. B. ein Punkt, dessen T_prio kaum reagiert und dessen
			// sigma Schritt fuer Schritt zum Sprungwert/zur Untergrenze laeuft
			boolean moving = false;
			double minMove = Math.log(1.0 + Klein_Method_Default_Values.DYNAMIC_STAGNATION_SIGMA_MOVE);
			java.util.Map<String, Double> lastSigma = parameter.getDynamicLastSigma();
			for (Klein_Method_Dynamic_Plan.Entry e : plan.getEntries()) {
				if (e.done || !(e.sigmaOld > 0.0))
					continue;
				Double previous = lastSigma.get(e.name);
				if (previous != null && previous > 0.0 && e.sigmaNew > 0.0) {
					double before = Math.log(e.sigmaOld / previous), next = Math.log(e.sigmaNew / e.sigmaOld);
					if (Math.abs(before) >= minMove && Math.abs(next) >= minMove && before * next > 0.0)
						moving = true;
				}
				lastSigma.put(e.name, e.sigmaOld);
			}
			double best = parameter.getDynamicStagnationBest();
			if (moving && !Double.isNaN(best)) {
				parameter.setDynamicStagnationCount(0);
				return null;
			}
			if (Double.isNaN(best) || metric < best * (1.0 - Klein_Method_Default_Values.DYNAMIC_STAGNATION_MIN_IMPROVEMENT)) {
				parameter.setDynamicStagnationBest(metric);
				parameter.setDynamicStagnationCount(0);
				return null;
			}
			int count = parameter.getDynamicStagnationCount() + 1;
			parameter.setDynamicStagnationCount(count);
			if (count < Klein_Method_Default_Values.DYNAMIC_STAGNATION_WINDOW)
				return null;
			return plan.getPhaseLabel() + ": seit " + count + " Iterationen kein Fortschritt Richtung Band (Abstand "
					+ Klein_Method_Dynamic_Plan.num(metric, 6) + ", bester " + Klein_Method_Dynamic_Plan.num(best, 6) + "). Offen: "
					+ String.join("; ", open);
		}
		/**
		 * freigegebenen Plan schreiben (Punktgruppe: alle Punkte der Gruppe,
		 *
		 * Festpunkt: der einzelne Punkt; jeweils y = x = z je Dimension) und die Protokollzeilen erzeugen.
		 */
		public void applyDynamicPlan(Klein_Method_Parameter parameter, Klein_Method_Dynamic_Plan plan,
				Klein_Method_Database_Handler_Interface databaseHandler, List<String> outAdjustmentDetails) throws SQLException {
			boolean withFormula = parameter.getMathDebugMode() != Klein_Method_Parameter.Klein_Method_Math_Debug_Mode.OFF;
			if (outAdjustmentDetails != null)
				for (String header : plan.getHeaderLines())
					outAdjustmentDetails.add("DYNAMISCH " + plan.getPhaseLabel() + ": " + header);
			for (Klein_Method_Dynamic_Plan.Entry e : plan.getEntries()) {
				if (e.write) {
					if (e.group)
						databaseHandler.writePointGroupUncertaintyValues(e.id, e.dimension, e.sigmaNew, e.sigmaNew, e.sigmaNew);
					else
						databaseHandler.writePointUncertainty(e.id, e.dimension, e.sigmaNew, e.sigmaNew, e.sigmaNew);
				}
				if (outAdjustmentDetails != null) {
					String line = (e.group ? "Festpunkt-" : "Festpunkt ") + e.name + " (" + e.dimension + "D): " + e.valueLabel + " "
							+ Klein_Method_Dynamic_Plan.num(e.value, 6) + (e.inBand ? " (im Band)" : "") + (e.done ? " fertig" : "")
							+ " | p " + (Double.isNaN(e.reaction) ? "-" : Klein_Method_Dynamic_Plan.num(e.reaction, 4))
							+ " | " + e.mode + " | sigma (" + e.sigmaSource + ") " + Klein_Method_Dynamic_Plan.mm(e.sigmaOld) + " mm -> "
							+ Klein_Method_Dynamic_Plan.mm(e.sigmaNew) + " mm" + (e.write ? "" : " (nicht geschrieben)")
							+ (e.limits.isEmpty() ? "" : " | " + e.limits)
							+ (withFormula ? " | " + e.formula : "");
					outAdjustmentDetails.add(line);
				}
			}
		}
		/**
		 * FESTPUNKTANALYSE - Sicherheitscheck: prueft, ob die gerade abgeschlossene Gruppenkalibrierung (Phase 1) fuer
		 * JEDEN Punkt tatsaechlich einheitliche y0/x0/z0-Werte erzeugt hat (y0==x0 bei 2D, y0==x0==z0 bei 3D - bei 1D
		 * gibt es nur z0, nichts zu vergleichen). Toleranz 1e-9 (Gleitkomma-Rundung).
		 *
		 * @return null, wenn alles einheitlich ist; sonst eine Beschreibung der ersten gefundenen Abweichung (fuer die
		 * Hinweis-Messagebox).
		 */
		public String checkPointComponentUniformity(Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
			double epsilon = 1e-9;
			for (Klein_Method_Point_Tprio_Row row : databaseHandler.readStochasticPointTprioValues()) {
				if (row.getDimension() == 3) {
					Double y = row.getSigmaY0(), x = row.getSigmaX0(), z = row.getSigmaZ0();
					if (y != null && x != null && z != null && (Math.abs(y - x) > epsilon || Math.abs(y - z) > epsilon)) {
						return "Punkt-ID " + row.getPointId() + ": sigma_y0=" + y + ", sigma_x0=" + x + ", sigma_z0=" + z + " (sollten gleich sein)";
					}
				}
				else if (row.getDimension() == 2) {
					Double y = row.getSigmaY0(), x = row.getSigmaX0();
					if (y != null && x != null && Math.abs(y - x) > epsilon) {
						return "Punkt-ID " + row.getPointId() + ": sigma_y0=" + y + ", sigma_x0=" + x + " (sollten gleich sein)";
					}
				}
			}
			return null;
		}	}

	// ============================================================================================================
	/** Datenstatus und Pruefungen beim Tab-Klick (Gruppenunsicherheiten, Zusatzparameter, Fangkreis). */
	private final class Tab_Check_Part {

		/**
		 * Leitet den Ampel-Status der "Messdaten" aus den AKTUELL im Parameter-Objekt stehenden Werten ab.
		 *
		 * ROT hat Vorrang vor GELB: ist die Obergrenzen-Sperre aktiv, ist der Status immer ERROR, unabhaengig davon, wie
		 * die einzelnen logPost-Werte gerade stehen. Technische Lesefehler (SQLException) werden NICHT hier behandelt,
		 * sondern vom Aufrufer direkt auf ERROR gesetzt.
		 *
		 * Bei mehreren Beobachtungstypen entscheidet der SCHLECHTESTE Typ die Gesamtfarbe (ein Typ noch ueber dem
		 * Grenzwert -> gesamt gelb).
		 */
		public Klein_Method_Data_Status determineDataStatus(Klein_Method_Parameter parameter) {
			if (parameter.isIndividualUncertaintyCeilingExceeded())
				return Klein_Method_Data_Status.ERROR;

			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
				double currentValue = parameter.getEvaluationValue(type);
				double thresholdValue = parameter.getThresholdValue(type);
				if (currentValue >= thresholdValue)
					return Klein_Method_Data_Status.NEEDS_OPTIMIZATION;
			}
			return Klein_Method_Data_Status.OPTIMIZED;
		}
		/**
		 * Validierungsregel bei JEDEM Tab-Aktivieren (siehe Klein_Method.runHeadlessAdjustmentThenRefreshStatus()): fuer
		 * jede Gruppe jedes Typs wird jede Komponente (sigma_a/sigma_b/sigma_c) geprueft:
		 *
		 * Wert == 0 -> nichts tun (bewusst deaktiviert, siehe performGroupCalibrationStep()) 0 < Wert < Grenzwert ->
		 * SOFORT auf den Grenzwert korrigieren Wert >= Grenzwert -> nichts tun
		 *
		 * @return eine Liste von Hinweistexten fuer jede tatsaechlich durchgefuehrte Korrektur - der Aufrufer zeigt
		 * daraus bei Bedarf eine Hinweis-Msgbox.
		 */
		public List<String> validateGroupUncertaintiesOnTabActivation(Klein_Method_Parameter parameter, Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
			List<String> corrections = new ArrayList<String>();
			int decimalPlaces = parameter.getDecimalPlaces();

			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_GROUP_CALIBRATION) {
				double floorA = parameter.getGroupUncertaintyFloorA(type);
				double floorB = parameter.getGroupUncertaintyFloorB(type);
				double floorC = parameter.getGroupUncertaintyFloorC(type);
				String unitA = Klein_Method_Default_Values.getSigmaAUnit(type);
				String unitB = Klein_Method_Default_Values.getSigmaBUnit(type);
				String unitC = Klein_Method_Default_Values.getSigmaCUnit(type);

				for (Integer groupId : databaseHandler.readGroupIds(type)) {
					// Rohwert (Radiant/Meter/unitless) zuerst in Arbeitseinheit umrechnen -
					// die Grenzwerte (floorA/B/C) liegen in Arbeitseinheit vor (mgon/mm/ppm)
					double[] sigmaRaw = databaseHandler.readGroupUncertainty(groupId);
					double sigmaA = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[0], unitA);
					double sigmaB = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[1], unitB);
					double sigmaC = Klein_Method_Functions.this.fromDatabaseValue(sigmaRaw[2], unitC);

					double correctedA = sigmaA > 0.0 && sigmaA < floorA ? floorA : sigmaA;
					double correctedB = sigmaB > 0.0 && sigmaB < floorB ? floorB : sigmaB;
					double correctedC = sigmaC > 0.0 && sigmaC < floorC ? floorC : sigmaC;

					if (correctedA == sigmaA && correctedB == sigmaB && correctedC == sigmaC)
						continue; // keine Korrektur an dieser Gruppe noetig

					// Vor dem Schreiben wieder zurueck in die JAG3D-Speichereinheit umrechnen
					databaseHandler.writeGroupUncertainty(groupId,
							Klein_Method_Functions.this.toDatabaseValue(correctedA, unitA),
							Klein_Method_Functions.this.toDatabaseValue(correctedB, unitB),
							Klein_Method_Functions.this.toDatabaseValue(correctedC, unitC));

					if (correctedA != sigmaA)
						corrections.add("sigma_a " + type.getDisplayName() + " Gruppe " + groupId + ": "
								+ Klein_Method_Format.format(sigmaA, unitA, decimalPlaces) + " -> " + Klein_Method_Format.format(correctedA, unitA, decimalPlaces) + " (Grenzwert)");
					if (correctedB != sigmaB)
						corrections.add("sigma_b " + type.getDisplayName() + " Gruppe " + groupId + ": "
								+ Klein_Method_Format.format(sigmaB, unitB, decimalPlaces) + " -> " + Klein_Method_Format.format(correctedB, unitB, decimalPlaces) + " (Grenzwert)");
					if (correctedC != sigmaC)
						corrections.add("sigma_c " + type.getDisplayName() + " Gruppe " + groupId + ": "
								+ Klein_Method_Format.format(sigmaC, unitC, decimalPlaces) + " -> " + Klein_Method_Format.format(correctedC, unitC, decimalPlaces) + " (Grenzwert)");
				}
			}
			return corrections;
		}
		/**
		 * PRUEFT bei jedem Tab-Aktivieren: gibt es fuer einen Streckentyp (Horizontalstrecke/Schr\u00E4gstrecke) MEHRERE
		 * aktive Beobachtungsgruppen mit INKONSISTENTEM Aktivierungsstatus von Nullpunktabweichung bzw. Ma\u00DFstab
		 * (manche an, manche aus - z.B. weil jemand das direkt in JAG3D fuer nur eine Gruppe geaendert hat), wird der
		 * Parameter fuer ALLE Gruppen dieses Typs auf AUS zurueckgesetzt und eine Beschreibung der Korrektur
		 * zurueckgegeben (Aufrufer zeigt daraus eine Info-Messagebox).
		 *
		 * Bei nur einer Gruppe oder durchgehend gleichem Status (alle an ODER alle aus) passiert nichts - das ist bereits
		 * konsistent.
		 */
		public List<String> validateAdditionalParameterConsistencyOnTabActivation(Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
			List<String> corrections = new ArrayList<String>();

			for (Klein_Method_Threshold_Type distanceType : new Klein_Method_Threshold_Type[] {
					Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST, Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST }) {
				Map<Integer, boolean[]> groupStates = databaseHandler.readAdditionalParameterGroupStates(distanceType);
				if (groupStates.size() <= 1)
					continue; // nur eine (oder keine) Gruppe -> keine Inkonsistenz moeglich

				String typeName = distanceType.getDisplayName().replace(" logPost", "");

				// Index 0 = Nullpunktabweichung, 1 = Massstab - je Komponente getrennt pruefen
				for (int component = 0; component < 2; component++) {
					boolean anyEnabled = false;
					boolean anyDisabled = false;
					for (boolean[] state : groupStates.values()) {
						if (state[component])
							anyEnabled = true;
						else
							anyDisabled = true;
					}
					if (!anyEnabled || !anyDisabled)
						continue; // konsistent (alle an ODER alle aus)

					for (Integer groupId : groupStates.keySet())
						databaseHandler.writeAdditionalParameterEnabled(groupId, component == 1, false);

					String parameterName = component == 1 ? "Ma\u00DFstab" : "Nullpunktabweichung";
					corrections.add(typeName + " - " + parameterName + ": inkonsistent zwischen " + groupStates.size()
							+ " Gruppen (nicht alle gleich aktiviert) - auf \"aus\" f\u00FCr alle Gruppen zur\u00FCckgesetzt.");
				}
			}
			return corrections;
		}
		public List<Klein_Method_Fangkreis_Violation> scanForFangkreisViolations(Klein_Method_Parameter parameter,
				Klein_Method_Database_Handler_Interface databaseHandler) throws SQLException {
			List<Klein_Method_Fangkreis_Violation> violations = new ArrayList<Klein_Method_Fangkreis_Violation>();
			double fangkreisRadius = parameter.getFangkreisRadius();

			Map<String, List<Klein_Method_Point_Coordinate_Row>> rowsByName =
					new LinkedHashMap<String, List<Klein_Method_Point_Coordinate_Row>>();
			for (Klein_Method_Point_Coordinate_Row row : databaseHandler.readAllPointAprioriCoordinatesForFangkreisScan()) {
				if (!rowsByName.containsKey(row.getName()))
					rowsByName.put(row.getName(), new ArrayList<Klein_Method_Point_Coordinate_Row>());
				rowsByName.get(row.getName()).add(row);
			}

			for (Map.Entry<String, List<Klein_Method_Point_Coordinate_Row>> entry : rowsByName.entrySet()) {
				List<Klein_Method_Point_Coordinate_Row> rows = entry.getValue();
				if (rows.size() < 2)
					continue; // Punktname kommt nur einmal vor - nichts zu vergleichen

				for (int i = 0; i < rows.size(); i++) {
					for (int j = i + 1; j < rows.size(); j++) {
						Double y1 = rows.get(i).getRechtswert(), x1 = rows.get(i).getHochwert();
						Double y2 = rows.get(j).getRechtswert(), x2 = rows.get(j).getHochwert();
						if (y1 == null || x1 == null || y2 == null || x2 == null)
							continue; // ohne beide Lagekoordinaten kein Vergleich moeglich

						double distance = Math.sqrt(Math.pow(y1 - y2, 2) + Math.pow(x1 - x2, 2));
						if (distance > fangkreisRadius) {
							Integer targetId = rows.get(j).getId();
							if (targetId == null)
								continue; // sollte bei diesem Lesepfad nie vorkommen, defensiv trotzdem geprueft
							String message = "Punkt \"" + entry.getKey() + "\" (ID " + targetId + ") kommt mehrfach vor, Lagen "
									+ Klein_Method_Format.format(distance, "m", 3) + " auseinander (Fangkreis: "
									+ Klein_Method_Format.format(fangkreisRadius, "m", 3) + ").";
							violations.add(new Klein_Method_Fangkreis_Violation(message, targetId));
						}
					}
				}
			}
			return violations;
		}	}
	/**
	 * Quasigeoidmodelle (z. B. GCG2016 des BKG): Import als Gitterdatei, Verwaltung im Ordner ~/.klein_method/quasigeoide,
	 * Interpolation der Hoehenanomalie N (Normalhoehe H = ellipsoidische Hoehe h - N) und die Hoehensystem-Pruefung.
	 *
	 * Eingelesen werden ASCII-Gitter (je Zeile Breite, Laenge, N in Grad/Meter, beliebige Reihenfolge, Pseudowert fuer
	 * "ausserhalb" z. B. 999999), ISG-Gitter (Format des International Service for the Geoid, Kopf begin_of_head ...
	 * end_of_head, Werte zeilenweise von Nord nach Sued) und Zip-Archive, die eine solche Datei enthalten. Jedes Modell wird
	 * kompakt binaer abgelegt (*.kmqg) und ist danach schnell ladbar; das Original wird nicht gebraucht.
	 */
	/**
	 * Koordinaten-/Hoehenrechner (Menue Hilfe): ETRS89 UTM mit Zonenkennung / geographisch / geozentrisch, Hoehe
	 * DHHN2016 (Normalhoehe ueber dem aktiven Quasigeoid) oder ellipsoidisch (GRS80); unabhaengig vom Projekt.
	 */
	public static final class Klein_Method_Height_Calculator {
		public static final int SYSTEM_UTM = 0, SYSTEM_GEOGRAPHIC = 1, SYSTEM_GEOCENTRIC = 2;
		public static final int HEIGHT_NORMAL = 0, HEIGHT_ELLIPSOIDAL = 1;

		private Klein_Method_Height_Calculator() {
		}

		/** Ergebnis einer Zeile: Ausgabezeile (null bei Fehler), Fehlertext (null ohne Fehler), Hoehenanomalie N (NaN). */
		public static final class Line_Result {
			public String line, error;
			public double undulation = Double.NaN;
			public boolean copied;
		}

		/** Zahl mit Punkt oder Komma als Dezimaltrenner, sonst NaN. */
		static double number(String token) {
			if (token == null || token.isEmpty())
				return Double.NaN;
			String t = token.indexOf('.') < 0 ? token.replace(',', '.') : token;
			try {
				return Double.parseDouble(t);
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}

		/**
		 * eine Zeile "Punkt Wert1 Wert2 Wert3 [weitere Spalten]" umrechnen. Leere Zeilen und Kommentare (#) werden
		 * unveraendert uebernommen. zone = Zielzone (0 = wie Eingabe bzw. aus der Laenge).
		 */
		public static Line_Result convertLine(String line, int sourceSystem, int sourceHeight, int targetSystem, int targetHeight, int zone,
				Klein_Method_Quasigeoid.Model model, boolean appendUndulation) {
			Line_Result r = new Line_Result();
			String trimmed = line == null ? "" : line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				r.line = line == null ? "" : line;
				r.copied = true;
				return r;
			}
			String[] t = trimmed.split("[\\s;]+");
			if (t.length < 4) {
				r.error = tr("Zeile braucht Punkt und drei Werte");
				return r;
			}
			double a = number(t[1]), b = number(t[2]), c = number(t[3]);
			if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) {
				r.error = tr("Wert ist keine Zahl");
				return r;
			}
			double lat, lon, h;
			int sourceZone = 0;
			if (sourceSystem == SYSTEM_UTM) {
				sourceZone = Klein_Method_Geo.zoneOfEasting(a);
				double[] ll = Klein_Method_Geo.utmToLatLon(a, b);
				if (ll == null) {
					r.error = tr("Rechtswert ohne Zonenkennung (z. B. 32500000.000)");
					return r;
				}
				lat = ll[0];
				lon = ll[1];
				h = c;
			} else if (sourceSystem == SYSTEM_GEOGRAPHIC) {
				if (Math.abs(a) > 90.0 || Math.abs(b) > 180.0) {
					r.error = tr("Breite/Länge außerhalb des Wertebereichs");
					return r;
				}
				lat = a;
				lon = b;
				h = c;
			} else {
				if (!Klein_Method_Local_System.isEcef(a, b, c)) {
					r.error = tr("keine geozentrischen Koordinaten (Abstand vom Erdmittelpunkt)");
					return r;
				}
				double[] llh = Klein_Method_Geo.ecefToLatLonHeight(a, b, c);
				lat = llh[0];
				lon = llh[1];
				h = llh[2];
			}
			boolean needModel = (sourceSystem != SYSTEM_GEOCENTRIC && sourceHeight == HEIGHT_NORMAL)
					|| (targetSystem != SYSTEM_GEOCENTRIC && targetHeight == HEIGHT_NORMAL) || appendUndulation;
			double n = Double.NaN;
			if (needModel) {
				if (model == null) {
					r.error = tr("kein Quasigeoid importiert");
					return r;
				}
				n = model.undulation(lat, lon);
				if (Double.isNaN(n)) {
					r.error = tr("Punkt liegt außerhalb des Quasigeoids");
					return r;
				}
			}
			r.undulation = n;
			double ellipsoidal = sourceSystem != SYSTEM_GEOCENTRIC && sourceHeight == HEIGHT_NORMAL ? h + n : h;
			double outHeight = targetSystem != SYSTEM_GEOCENTRIC && targetHeight == HEIGHT_NORMAL ? ellipsoidal - n : ellipsoidal;
			StringBuilder out = new StringBuilder(t[0]);
			if (targetSystem == SYSTEM_UTM) {
				int z = zone > 0 ? zone : sourceZone > 0 ? sourceZone : Klein_Method_Geo.utmZoneOfLongitude(lon);
				double[] yx = Klein_Method_Geo.latLonToUtm(lat, lon, z);
				out.append(String.format(Locale.ROOT, "  %.3f  %.3f %.4f", yx[0], yx[1], outHeight));
			} else if (targetSystem == SYSTEM_GEOGRAPHIC)
				out.append(String.format(Locale.ROOT, "  %.9f  %.9f %.4f", lat, lon, outHeight));
			else {
				double[] xyz = Klein_Method_Local_System.geodeticToEcef(lat, lon, ellipsoidal);
				out.append(String.format(Locale.ROOT, "  %.4f  %.4f  %.4f", xyz[0], xyz[1], xyz[2]));
			}
			if (appendUndulation)
				out.append(String.format(Locale.ROOT, "  %.4f", n));
			for (int i = 4; i < t.length; i++)
				out.append(' ').append(t[i]);
			r.line = out.toString();
			return r;
		}
	}

	public static final class Klein_Method_Quasigeoid {
		public static final String FOLDER = "quasigeoide", EXTENSION = ".kmqg", ACTIVE_FILE = "aktiv.txt";
		/** Werte mit |N| ab hier gelten als Pseudowert "ausserhalb des Modells". */
		public static final double NO_DATA_LIMIT = 9000.0;
		/** groesster zulaessiger Betrag einer Hoehenanomalie [m] (weltweit ca. -110 ... +90 m). */
		public static final double MAX_UNDULATION = 150.0;
		private static final int MAGIC = 0x4B4D5147; // "KMQG"
		private static volatile Model cached = null;

		private Klein_Method_Quasigeoid() {
		}

		/** ein Gittermodell; Zeile 0 = suedlichste Breite, Spalte 0 = westlichste Laenge. */
		public static final class Model {
			public String name = "", version = "", source = "", license = "", imported = "";
			public double latMin, lonMin, dLat, dLon;
			public int rows, cols, missing;
			public float[] values;

			public double latMax() {
				return this.latMin + (this.rows - 1) * this.dLat;
			}
			public double lonMax() {
				return this.lonMin + (this.cols - 1) * this.dLon;
			}
			float at(int row, int col) {
				return this.values[row * this.cols + col];
			}
			/**
			 * Hoehenanomalie N an (Breite, Laenge) [Grad]: bikubische Interpolation (Catmull-Rom, 4 x 4 Gitterpunkte) wie
			 * beim BKG-Programm gintbs (bikubische Splines); am Rand oder neben fehlenden Werten bilinear. NaN ausserhalb.
			 */
			public double undulation(double lat, double lon) {
				if (this.values == null || !Double.isFinite(lat) || !Double.isFinite(lon))
					return Double.NaN;
				double r = (lat - this.latMin) / this.dLat, c = (lon - this.lonMin) / this.dLon;
				if (r < -1e-9 || c < -1e-9 || r > this.rows - 1 + 1e-9 || c > this.cols - 1 + 1e-9)
					return Double.NaN;
				int r0 = Math.min((int) Math.floor(Math.max(r, 0.0)), this.rows - 2), c0 = Math.min((int) Math.floor(Math.max(c, 0.0)), this.cols - 2);
				double tr = r - r0, tc = c - c0;
				boolean cubic = r0 >= 1 && c0 >= 1 && r0 + 2 < this.rows && c0 + 2 < this.cols;
				if (cubic) {
					double[] col = new double[4];
					for (int i = -1; i <= 2 && cubic; i++) {
						double[] p = new double[4];
						for (int j = -1; j <= 2; j++) {
							float v = this.at(r0 + i, c0 + j);
							if (Float.isNaN(v)) {
								cubic = false;
								break;
							}
							p[j + 1] = v;
						}
						if (cubic)
							col[i + 1] = catmullRom(p, tc);
					}
					if (cubic)
						return catmullRom(col, tr);
				}
				float v00 = this.at(r0, c0), v01 = this.at(r0, c0 + 1), v10 = this.at(r0 + 1, c0), v11 = this.at(r0 + 1, c0 + 1);
				if (Float.isNaN(v00) || Float.isNaN(v01) || Float.isNaN(v10) || Float.isNaN(v11))
					return Double.NaN;
				return (1 - tr) * ((1 - tc) * v00 + tc * v01) + tr * ((1 - tc) * v10 + tc * v11);
			}
			public String title() {
				return this.name + (this.version.isEmpty() ? "" : " (" + this.version + ")");
			}
		}

		private static double catmullRom(double[] p, double t) {
			return 0.5 * (2 * p[1] + (-p[0] + p[2]) * t + (2 * p[0] - 5 * p[1] + 4 * p[2] - p[3]) * t * t + (-p[0] + 3 * p[1] - 3 * p[2] + p[3]) * t * t * t);
		}

		public static java.io.File folder() {
			return new java.io.File(System.getProperty("user.home"), ".klein_method" + java.io.File.separator + FOLDER);
		}

		/** Ergebnis eines Imports: Modell, Datei und Pruefzeilen (deutsch, fuer Protokoll und Anzeige). */
		public static final class Import_Result {
			public Model model;
			public java.io.File file;
			public final List<String> checks = new ArrayList<String>();
			public boolean replaced;
		}

		/** ASCII-Gitter (Breite Laenge N je Zeile) einlesen. */
		public static Model parseAscii(java.io.BufferedReader reader) throws java.io.IOException {
			List<double[]> rows = new ArrayList<double[]>(1 << 20);
			String line;
			while ((line = reader.readLine()) != null) {
				String[] t = line.trim().split("[\\s,;]+");
				if (t.length < 3)
					continue;
				try {
					rows.add(new double[] { Double.parseDouble(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2]) });
				} catch (NumberFormatException e) {
					// Kopf- oder Kommentarzeile
				}
			}
			if (rows.size() < 4)
				throw new java.io.IOException("keine Gitterwerte (Breite Laenge N) gefunden");
			java.util.TreeSet<Double> lats = new java.util.TreeSet<Double>(), lons = new java.util.TreeSet<Double>();
			for (double[] r : rows) {
				lats.add(Math.round(r[0] * 1e7) / 1e7);
				lons.add(Math.round(r[1] * 1e7) / 1e7);
			}
			Model m = new Model();
			m.latMin = lats.first();
			m.lonMin = lons.first();
			m.dLat = (lats.last() - lats.first()) / (lats.size() - 1);
			m.dLon = (lons.last() - lons.first()) / (lons.size() - 1);
			m.rows = lats.size();
			m.cols = lons.size();
			if ((long) m.rows * m.cols != rows.size())
				throw new java.io.IOException("kein regelmaessiges Gitter (" + rows.size() + " Werte, " + m.rows + " Breiten x " + m.cols + " Laengen)");
			m.values = new float[m.rows * m.cols];
			java.util.Arrays.fill(m.values, Float.NaN);
			for (double[] r : rows) {
				int i = (int) Math.round((r[0] - m.latMin) / m.dLat), j = (int) Math.round((r[1] - m.lonMin) / m.dLon);
				if (Math.abs(r[0] - (m.latMin + i * m.dLat)) > 1e-6 || Math.abs(r[1] - (m.lonMin + j * m.dLon)) > 1e-6)
					throw new java.io.IOException("Gitterpunkt " + r[0] + " / " + r[1] + " liegt nicht im regelmaessigen Raster");
				m.values[i * m.cols + j] = Math.abs(r[2]) >= NO_DATA_LIMIT ? Float.NaN : (float) r[2];
			}
			return finish(m);
		}

		/** ISG-Gitter (International Service for the Geoid) einlesen; Winkel in Grad (dezimal). */
		public static Model parseIsg(java.io.BufferedReader reader) throws java.io.IOException {
			Map<String, String> head = new java.util.HashMap<String, String>();
			String line;
			boolean inHead = false;
			while ((line = reader.readLine()) != null) {
				String s = line.trim();
				if (s.startsWith("begin_of_head")) {
					inHead = true;
					continue;
				}
				if (s.startsWith("end_of_head"))
					break;
				if (inHead && s.contains(":")) {
					int k = s.indexOf(':');
					head.put(s.substring(0, k).trim().toLowerCase(Locale.ROOT), s.substring(k + 1).trim());
				}
			}
			String units = head.getOrDefault("isg format", "").startsWith("2") ? head.getOrDefault("coord units", "deg") : head.getOrDefault("coord units", "deg");
			if (!units.toLowerCase(Locale.ROOT).startsWith("deg"))
				throw new java.io.IOException("ISG-Datei mit Winkeln in " + units + " wird nicht unterstuetzt (nur Grad dezimal)");
			try {
				double latMax = Double.parseDouble(head.get("lat max")), lonMin = Double.parseDouble(head.get("lon min"));
				double dLat = Double.parseDouble(head.get("delta lat")), dLon = Double.parseDouble(head.get("delta lon"));
				int rows = Integer.parseInt(head.get("nrows")), cols = Integer.parseInt(head.get("ncols"));
				double noData = Double.parseDouble(head.getOrDefault("nodata", "-9999"));
				boolean cellCentered = head.getOrDefault("data ordering", "").toLowerCase(Locale.ROOT).contains("cell") || "cell".equalsIgnoreCase(head.getOrDefault("grid registration", ""));
				Model m = new Model();
				m.rows = rows;
				m.cols = cols;
				m.dLat = dLat;
				m.dLon = dLon;
				m.latMin = latMax - (rows - 1) * dLat - (cellCentered ? dLat / 2 : 0.0);
				m.lonMin = lonMin + (cellCentered ? dLon / 2 : 0.0);
				m.values = new float[rows * cols];
				int k = 0;
				while ((line = reader.readLine()) != null && k < rows * cols) {
					for (String t : line.trim().split("\\s+")) {
						if (t.isEmpty() || k >= rows * cols)
							continue;
						double v = Double.parseDouble(t);
						int row = rows - 1 - k / cols, col = k % cols; // Datei von Nord nach Sued
						m.values[row * cols + col] = v == noData || Math.abs(v) >= NO_DATA_LIMIT ? Float.NaN : (float) v;
						k++;
					}
				}
				if (k != rows * cols)
					throw new java.io.IOException("ISG-Datei unvollstaendig: " + k + " von " + rows * cols + " Werten");
				if (head.containsKey("model name"))
					m.name = head.get("model name");
				return finish(m);
			} catch (NumberFormatException | NullPointerException e) {
				throw new java.io.IOException("ISG-Kopf unvollstaendig (lat max, lon min, delta lat/lon, nrows, ncols)");
			}
		}

		private static Model finish(Model m) throws java.io.IOException {
			int missing = 0;
			for (float v : m.values)
				if (Float.isNaN(v))
					missing++;
				else if (Math.abs(v) > MAX_UNDULATION)
					throw new java.io.IOException("Wert " + v + " m ausserhalb des moeglichen Bereichs einer Hoehenanomalie");
			if (missing == m.values.length)
				throw new java.io.IOException("Gitter enthaelt nur Pseudowerte");
			m.missing = missing;
			return m;
		}

		public static void write(Model m, java.io.File file) throws java.io.IOException {
			try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(file)))) {
				out.writeInt(MAGIC);
				out.writeInt(1);
				out.writeUTF(m.name);
				out.writeUTF(m.version);
				out.writeUTF(m.source);
				out.writeUTF(m.license);
				out.writeUTF(m.imported);
				out.writeDouble(m.latMin);
				out.writeDouble(m.lonMin);
				out.writeDouble(m.dLat);
				out.writeDouble(m.dLon);
				out.writeInt(m.rows);
				out.writeInt(m.cols);
				out.writeInt(m.missing);
				for (float v : m.values)
					out.writeFloat(v);
			}
		}

		public static Model read(java.io.File file, boolean withValues) throws java.io.IOException {
			try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
				if (in.readInt() != MAGIC || in.readInt() != 1)
					throw new java.io.IOException("keine Klein_Method-Quasigeoiddatei: " + file.getName());
				Model m = new Model();
				m.name = in.readUTF();
				m.version = in.readUTF();
				m.source = in.readUTF();
				m.license = in.readUTF();
				m.imported = in.readUTF();
				m.latMin = in.readDouble();
				m.lonMin = in.readDouble();
				m.dLat = in.readDouble();
				m.dLon = in.readDouble();
				m.rows = in.readInt();
				m.cols = in.readInt();
				m.missing = in.readInt();
				if (withValues) {
					m.values = new float[m.rows * m.cols];
					for (int i = 0; i < m.values.length; i++)
						m.values[i] = in.readFloat();
				}
				return m;
			}
		}

		/** Datei des Modells (Name ohne unerlaubte Zeichen). */
		public static java.io.File fileFor(String name) {
			return new java.io.File(folder(), name.replaceAll("[^A-Za-z0-9._-]", "_") + EXTENSION);
		}

		/** alle importierten Modelle (nur Kopfdaten), nach Name sortiert. */
		public static List<Model> list() {
			List<Model> models = new ArrayList<Model>();
			java.io.File[] files = folder().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(EXTENSION));
			if (files == null)
				return models;
			java.util.Arrays.sort(files);
			for (java.io.File f : files) {
				try {
					models.add(read(f, false));
				} catch (java.io.IOException e) {
					// beschaedigte Datei - nicht anbieten
				}
			}
			return models;
		}

		/** Name des aktiven Modells oder null. */
		public static String activeName() {
			java.io.File f = new java.io.File(folder(), ACTIVE_FILE);
			if (!f.isFile())
				return null;
			try {
				String name = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
				return name.isEmpty() || !fileFor(name).isFile() ? null : name;
			} catch (java.io.IOException e) {
				return null;
			}
		}

		public static void setActive(String name) throws java.io.IOException {
			folder().mkdirs();
			java.nio.file.Files.write(new java.io.File(folder(), ACTIVE_FILE).toPath(), (name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			cached = null;
		}

		/** aktives Modell mit Werten (zwischengespeichert) oder null. */
		public static Model active() {
			String name = activeName();
			if (name == null)
				return null;
			Model m = cached;
			if (m != null && m.name.equals(name))
				return m;
			try {
				m = read(fileFor(name), true);
				cached = m;
				return m;
			} catch (java.io.IOException e) {
				return null;
			}
		}

		public static boolean delete(String name) throws java.io.IOException {
			boolean wasActive = name.equals(activeName());
			boolean deleted = java.nio.file.Files.deleteIfExists(fileFor(name).toPath());
			if (wasActive)
				setActive(null);
			cached = null;
			return deleted;
		}

		/**
		 * Datei oder Zip importieren: Gitter lesen, pruefen, kompakt im Ordner ablegen. Das erste Modell wird automatisch
		 * aktiv. Bei Zip wird die erste ASCII- (*.txt mit Zahlentripeln) bzw. ISG-Datei (*.isg, *.gri) genommen;
		 * aktualitaet.txt (Fassung) und ein CC-BY-Vermerk des BKG werden mitgelesen.
		 */
		public static Import_Result importFile(java.io.File source, String overrideName) throws java.io.IOException {
			return importFile(source, overrideName, true);
		}

		/** Modell gleichen Namens vorhanden und Ersetzen nicht freigegeben. */
		public static final class Exists_Exception extends java.io.IOException {
			private static final long serialVersionUID = 1L;
			public final String name;
			public Exists_Exception(String name) {
				super("Quasigeoidmodell '" + name + "' ist schon vorhanden");
				this.name = name;
			}
		}

		/** wie importFile(source, name); replace = false -> Exists_Exception, wenn ein Modell gleichen Namens vorhanden ist (nichts geschrieben). */
		public static Import_Result importFile(java.io.File source, String overrideName, boolean replace) throws java.io.IOException {
			Import_Result result = new Import_Result();
			Model m;
			String baseName;
			String version = "";
			boolean bkgLicense = false;
			if (source.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
				try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source)) {
					java.util.zip.ZipEntry best = null;
					int bestScore = -1;
					for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries(); e.hasMoreElements();) {
						java.util.zip.ZipEntry entry = e.nextElement();
						if (entry.isDirectory())
							continue;
						String lower = entry.getName().toLowerCase(Locale.ROOT);
						String file = lower.substring(lower.lastIndexOf('/') + 1);
						if (file.equals("aktualitaet.txt"))
							try (java.io.InputStream in = zip.getInputStream(entry)) {
								version = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim().split("\\R")[0].trim();
							}
						if (file.startsWith("ccby") || file.startsWith("quellenvermerk"))
							bkgLicense = true;
						int score = file.endsWith(".isg") || file.endsWith(".gri") ? 3 : file.endsWith(".txt") && lower.contains("ascii") ? (lower.contains("linux") ? 2 : 1)
								: file.endsWith(".txt") && !file.equals("aktualitaet.txt") ? 0 : -1;
						if (score > bestScore) {
							bestScore = score;
							best = entry;
						}
					}
					if (best == null)
						throw new java.io.IOException("im Zip keine Gitterdatei (ASCII *.txt oder ISG *.isg/*.gri) gefunden");
					String n = best.getName();
					baseName = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\.[^.]*$", "");
					try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(zip.getInputStream(best), java.nio.charset.StandardCharsets.ISO_8859_1))) {
						m = isIsgName(n) ? parseIsg(reader) : parseAscii(reader);
					}
					result.checks.add("Gitterdatei im Zip: " + n);
				}
			}
			else {
				baseName = source.getName().replaceAll("\\.[^.]*$", "");
				try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(source.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1)) {
					m = isIsgName(source.getName()) ? parseIsg(reader) : parseAscii(reader);
				}
			}
			if (overrideName != null && !overrideName.trim().isEmpty())
				m.name = overrideName.trim();
			else if (m.name == null || m.name.isEmpty())
				m.name = baseName;
			m.version = version;
			m.source = source.getName();
			m.imported = java.time.LocalDate.now().toString();
			if (bkgLicense)
				m.license = "© GeoBasis-DE / BKG " + java.time.Year.now().getValue() + " CC BY 4.0";
			// Pruefungen
			result.checks.add(String.format(Locale.GERMANY, "Gitter %d x %d (%d Werte), Schrittweite %.6f° x %.6f° (ca. %.0f m x %.0f m)", m.rows, m.cols,
					m.values.length, m.dLat, m.dLon, m.dLat * 111200.0, m.dLon * 111200.0 * Math.cos(Math.toRadians(m.latMin + 0.5 * (m.latMax() - m.latMin)))));
			result.checks.add(String.format(Locale.GERMANY, "Gebiet Breite %.4f° bis %.4f°, Länge %.4f° bis %.4f°", m.latMin, m.latMax(), m.lonMin, m.lonMax()));
			float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
			for (float v : m.values)
				if (!Float.isNaN(v)) {
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			result.checks.add(String.format(Locale.GERMANY, "N von %.3f m bis %.3f m, %d Gitterpunkte außerhalb (Pseudowert)", min, max, m.missing));
			// Kontrolle: an einem Gitterpunkt muss die Interpolation den Gitterwert liefern
			int ri = m.rows / 2, ci = m.cols / 2;
			for (int k = 0; k < m.values.length && Float.isNaN(m.at(ri, ci)); k++) {
				ri = (k / m.cols) % m.rows;
				ci = k % m.cols;
			}
			double node = m.undulation(m.latMin + ri * m.dLat, m.lonMin + ci * m.dLon);
			if (Math.abs(node - m.at(ri, ci)) > 1e-4)
				throw new java.io.IOException("Kontrolle am Gitterpunkt fehlgeschlagen (" + node + " statt " + m.at(ri, ci) + ")");
			result.checks.add(String.format(Locale.GERMANY, "Kontrolle Gitterpunkt %.5f° / %.5f°: N = %.4f m", m.latMin + ri * m.dLat, m.lonMin + ci * m.dLon, node));
			folder().mkdirs();
			result.file = fileFor(m.name);
			result.replaced = result.file.isFile();
			if (result.replaced && !replace)
				throw new Exists_Exception(m.name);
			write(m, result.file);
			result.model = m;
			cached = null;
			if (activeName() == null)
				setActive(m.name);
			return result;
		}

		private static boolean isIsgName(String name) {
			String lower = name.toLowerCase(Locale.ROOT);
			return lower.endsWith(".isg") || lower.endsWith(".gri");
		}

		/** Ergebnis der Hoehensystem-Pruefung. */
		public static final class Height_Check {
			/** Punkte, die sowohl ueber Nivellement (ab Hoehenfestpunkten) als auch ueber GNSS (ab 3D-Festpunkten) eine Hoehe haben. */
			public int common;
			/** Median von h(GNSS) - H(Nivellement) [m]; NaN ohne gemeinsame Punkte. */
			public double difference = Double.NaN;
			/** Hoehenanomalie N des aktiven Modells im Mittel der gemeinsamen Punkte [m]; NaN ohne Modell. */
			public double modelUndulation = Double.NaN;
			public String modelTitle;
			/** 0 = gleiches System, 1 = NHN und ellipsoidisch gemischt, 2 = unklar (Abweichung passt zu keinem), -1 = nicht pruefbar. */
			public int verdict = -1;
		}

		/** Lage und Hoehe eines Punkts im Projektsystem -> {Breite, Laenge, ellipsoidische bzw. Projekthoehe}; Breite/Laenge NaN, wenn unbekannt. */
		public interface Height_Frame {
			double[] latLonHeight(double y, double x, double z);
		}

		/**
		 * Hoehensystem-Pruefung: Hoehen einmal ab den Hoehenfestpunkten (1D, keine Neupunkte) nur ueber Nivellements, einmal ab
		 * den 3D-Festpunkten nur ueber 3D-GNSS-Basislinien weitergegeben. Fuer Punkte, die beide Wege erreichen, wird
		 * h(GNSS) - H(Nivellement) gebildet; der Median zeigt, ob beide Hoehensysteme zusammenpassen (0) oder sich um die
		 * Hoehenanomalie N unterscheiden (NHN gegen ellipsoidisch).
		 */
		public static Height_Check checkHeightSystems(List<Klein_Method_Approximation.Point> points, List<Klein_Method_Approximation.Observation> observations,
				Height_Frame frame, Model model) {
			Height_Check result = new Height_Check();
			Map<String, Klein_Method_Approximation.Point> byName = new java.util.HashMap<String, Klein_Method_Approximation.Point>();
			for (Klein_Method_Approximation.Point p : points)
				byName.put(p.name, p);
			Map<String, Double> leveled = new java.util.HashMap<String, Double>();
			Map<String, double[]> gnss = new java.util.HashMap<String, double[]>(); // {y, x, z} im Projektsystem
			for (Klein_Method_Approximation.Point p : points) {
				if (p.isNew || !p.heightGiven())
					continue;
				if (p.dimension == 1) {
					// 1D-Punkte haben in JAG3D trotzdem eine eingetragene Lage - ohne sie wuerde der Punkt in P0 angenommen und
					// die lokale Hoehe (Oben-Achse) um die Erdkruemmung r^2/2R falsch in eine Hoehe umgerechnet
					boolean located = Double.isFinite(p.y0) && Double.isFinite(p.x0) && !(p.y0 == 0.0 && p.x0 == 0.0);
					double[] g = frame.latLonHeight(located ? p.y0 : Double.NaN, located ? p.x0 : Double.NaN, p.z0);
					if (g != null && Double.isFinite(g[2]))
						leveled.put(p.name, g[2]);
				}
				else if (p.dimension == 3 && p.positionGiven())
					gnss.put(p.name, new double[] { p.y0, p.x0, p.z0 });
			}
			if (leveled.isEmpty() || gnss.isEmpty())
				return result;
			for (boolean changed = true; changed;) {
				changed = false;
				for (Klein_Method_Approximation.Observation o : observations) {
					if (o.type == Klein_Method_Approximation.LEVELING && Double.isFinite(o.value)) {
						if (leveled.containsKey(o.from) && !leveled.containsKey(o.to)) {
							leveled.put(o.to, leveled.get(o.from) + o.value);
							changed = true;
						}
						else if (leveled.containsKey(o.to) && !leveled.containsKey(o.from)) {
							leveled.put(o.from, leveled.get(o.to) - o.value);
							changed = true;
						}
					}
					else if (o.type == Klein_Method_Approximation.GNSS3D && Double.isFinite(o.gz)) {
						if (gnss.containsKey(o.from) && !gnss.containsKey(o.to)) {
							double[] s = gnss.get(o.from);
							gnss.put(o.to, new double[] { s[0] + o.gy, s[1] + o.gx, s[2] + o.gz });
							changed = true;
						}
						else if (gnss.containsKey(o.to) && !gnss.containsKey(o.from)) {
							double[] s = gnss.get(o.to);
							gnss.put(o.from, new double[] { s[0] - o.gy, s[1] - o.gx, s[2] - o.gz });
							changed = true;
						}
					}
				}
			}
			List<Double> differences = new ArrayList<Double>(), undulations = new ArrayList<Double>();
			for (Map.Entry<String, double[]> e : gnss.entrySet()) {
				Double h = leveled.get(e.getKey());
				if (h == null)
					continue;
				double[] g = frame.latLonHeight(e.getValue()[0], e.getValue()[1], e.getValue()[2]);
				if (g == null || !Double.isFinite(g[2]))
					continue;
				differences.add(g[2] - h);
				if (model != null && Double.isFinite(g[0]) && Double.isFinite(g[1])) {
					double n = model.undulation(g[0], g[1]);
					if (Double.isFinite(n))
						undulations.add(n);
				}
			}
			result.common = differences.size();
			if (differences.isEmpty())
				return result;
			result.difference = Klein_Method_Swarm.median(differences);
			if (!undulations.isEmpty()) {
				result.modelUndulation = Klein_Method_Swarm.median(undulations);
				result.modelTitle = model.title();
			}
			result.verdict = verdict(result.difference, result.modelUndulation);
			return result;
		}

		/**
		 * Bewertung: Differenz unter 1 m = gleiches Hoehensystem; mit Modell gemischt, wenn die Differenz hoechstens 1 m
		 * (bzw. 10 %) von N abweicht; ohne Modell gemischt, wenn sie zwischen 20 und 80 m liegt.
		 */
		public static int verdict(double difference, double modelUndulation) {
			if (!Double.isFinite(difference))
				return -1;
			if (Math.abs(difference) < 1.0)
				return 0;
			if (Double.isFinite(modelUndulation))
				return Math.abs(Math.abs(difference) - Math.abs(modelUndulation)) <= Math.max(1.0, 0.1 * Math.abs(modelUndulation)) ? 1 : 2;
			return Math.abs(difference) >= 20.0 && Math.abs(difference) <= 80.0 ? 1 : 2;
		}
	}

	/**
	 * GNSS-Stuetzung am Netzrand: In kombinierten Netzen (GNSS + Tachymeter) halten die GNSS-Punkte (und Festpunkte mit
	 * Lage) das Netz. Punkte ausserhalb ihrer Huelle werden nur ueber terrestrische Messungen extrapoliert - Massstabs-,
	 * Orientierungs- und GNSS-Fehler am Rand wirken dort ueber den Hebel (Wegdriften). Prueft je Neupunkt den Abstand
	 * ausserhalb der Huelle und ob ein Randbereich an einem einzigen Stuetzpunkt haengt.
	 */
	public static final class Klein_Method_Gnss_Support {
		/** gelb ab diesem Abstand ausserhalb der Huelle [m] bzw. Anteil des Huellendurchmessers (der groessere Wert gilt). */
		public static final double YELLOW_MIN = 10.0, RED_MIN = 50.0, RED_FRACTION = 0.10;
		/** "einseitig": zweitnaechster Stuetzpunkt mindestens so viel weiter weg als der naechste. */
		public static final double ONE_SIDED_FACTOR = 2.0;

		private Klein_Method_Gnss_Support() {
		}

		public static final class Point_Result {
			public final String name;
			public final double y, x;
			/** Abstand ausserhalb der Huelle [m] (0 = innerhalb), Abstand zum naechsten Stuetzpunkt [m]. */
			public double outside, nearestDistance, secondDistance = Double.NaN;
			public String nearest, second;
			/** 0 = grün, 1 = gelb, 2 = rot. */
			public int level;

			Point_Result(String name, double y, double x) {
				this.name = name;
				this.y = y;
				this.x = x;
			}

			/** haengt an einem einzigen Stuetzpunkt (ausserhalb der Huelle, der zweitnaechste ist deutlich weiter weg). */
			public boolean oneSided() {
				return this.outside > 0.0 && this.nearest != null && (Double.isNaN(this.secondDistance) || this.secondDistance >= ONE_SIDED_FACTOR * this.nearestDistance);
			}
		}

		public static final class Result {
			public final List<String> supports = new ArrayList<String>();
			public final List<Point_Result> points = new ArrayList<Point_Result>();
			public double hullDiameter = 0.0, redLimit = RED_MIN;
			/** Stuetzpunkt -> Punkte (gelb/rot), die einseitig an ihm haengen. */
			public final Map<String, List<String>> dependents = new java.util.TreeMap<String, List<String>>();

			public List<String> names(int level) {
				List<String> out = new ArrayList<String>();
				for (Point_Result p : this.points)
					if (p.level == level)
						out.add(p.name);
				return out;
			}

			public Point_Result worst() {
				Point_Result w = null;
				for (Point_Result p : this.points)
					if (w == null || p.outside > w.outside)
						w = p;
				return w;
			}
		}

		/**
		 * @param points   Neupunkte mit Lage {Name -> y, x} (lokal bzw. eben)
		 * @param supports Stuetzpunkte {Name -> y, x}: Netzpunkte mit aktiven GNSS-Basislinien und Festpunkte mit Lage im Netzgebiet
		 */
		public static Result evaluate(Map<String, double[]> points, Map<String, double[]> supports) {
			Result r = new Result();
			r.supports.addAll(supports.keySet());
			List<double[]> s = new ArrayList<double[]>(supports.values());
			List<double[]> hull = hull(s);
			for (int a = 0; a < s.size(); a++)
				for (int b = a + 1; b < s.size(); b++)
					r.hullDiameter = Math.max(r.hullDiameter, Math.hypot(s.get(a)[0] - s.get(b)[0], s.get(a)[1] - s.get(b)[1]));
			r.redLimit = Math.max(RED_MIN, RED_FRACTION * r.hullDiameter);
			for (Map.Entry<String, double[]> e : points.entrySet()) {
				if (supports.containsKey(e.getKey()))
					continue;
				double[] p = e.getValue();
				Point_Result pr = new Point_Result(e.getKey(), p[0], p[1]);
				double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
				for (Map.Entry<String, double[]> q : supports.entrySet()) {
					double d = Math.hypot(p[0] - q.getValue()[0], p[1] - q.getValue()[1]);
					if (d < d1) {
						d2 = d1;
						pr.second = pr.nearest;
						d1 = d;
						pr.nearest = q.getKey();
					}
					else if (d < d2) {
						d2 = d;
						pr.second = q.getKey();
					}
				}
				pr.nearestDistance = pr.nearest == null ? Double.NaN : d1;
				pr.secondDistance = pr.second == null ? Double.NaN : d2;
				pr.outside = hull.size() >= 3 ? outsideDistance(p, hull) : (Double.isNaN(pr.nearestDistance) ? Double.POSITIVE_INFINITY : pr.nearestDistance);
				pr.level = pr.outside > r.redLimit ? 2 : pr.outside > YELLOW_MIN ? 1 : 0;
				r.points.add(pr);
				if (pr.level > 0 && pr.oneSided())
					r.dependents.computeIfAbsent(pr.nearest, k -> new ArrayList<String>()).add(pr.name);
			}
			r.points.sort((a, b) -> Double.compare(b.outside, a.outside));
			return r;
		}

		/** konvexe Huelle (gegen den Uhrzeigersinn). */
		static List<double[]> hull(List<double[]> points) {
			List<double[]> p = new ArrayList<double[]>(points);
			if (p.size() < 3)
				return new ArrayList<double[]>();
			p.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
			List<double[]> h = new ArrayList<double[]>();
			for (int pass = 0; pass < 2; pass++) {
				int start = h.size();
				for (double[] q : p) {
					while (h.size() >= start + 2 && cross(h.get(h.size() - 2), h.get(h.size() - 1), q) <= 0)
						h.remove(h.size() - 1);
					h.add(q);
				}
				h.remove(h.size() - 1);
				java.util.Collections.reverse(p);
			}
			return h;
		}

		private static double cross(double[] o, double[] a, double[] b) {
			return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
		}

		/** Abstand eines Punkts ausserhalb der Huelle (0 innerhalb). */
		static double outsideDistance(double[] p, List<double[]> hull) {
			boolean inside = true;
			double d = Double.MAX_VALUE;
			for (int i = 0; i < hull.size(); i++) {
				double[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
				if (cross(a, b, p) < 0)
					inside = false;
				double ax = b[0] - a[0], ay = b[1] - a[1];
				double t = Math.max(0.0, Math.min(1.0, ((p[0] - a[0]) * ax + (p[1] - a[1]) * ay) / (ax * ax + ay * ay)));
				d = Math.min(d, Math.hypot(p[0] - a[0] - t * ax, p[1] - a[1] - t * ay));
			}
			return inside ? 0.0 : d;
		}
	}

}
// V1.00_RC3_Klein_Method_Functions.java
