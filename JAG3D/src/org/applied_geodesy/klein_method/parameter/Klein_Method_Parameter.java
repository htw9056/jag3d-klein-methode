// V1.00_RC3_Klein_Method_Parameter.java
package org.applied_geodesy.klein_method.parameter;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * Haelt die Parameter und Grenzwerte der Erweiterung "Klein_Method".
 * Fuer jeden Klein_Method_Threshold_Type (siehe dort) wird sowohl der
 * aktuelle Wert (aus der letzten Berechnung) als auch der zugehoerige
 * Grenzwert vorgehalten.
 *
 * Spaeter erweiterbar um Laden/Speichern (z.B. Klein_Method.properties).
 */
public class Klein_Method_Parameter {

	public static final int ITERATION_THRESHOLD_DEFAULT = Klein_Method_Default_Values.DEFAULT_MAX_ITERATIONS_PER_PASS;
	public static final int ITERATION_THRESHOLD_MAX = Klein_Method_Default_Values.HARD_MAX_ITERATIONS_LIMIT;

	public static final double FUNNEL_FACTOR_DEFAULT = 0.30;
	public static final double FUNNEL_FACTOR_MIN = 0.2;
	public static final double FUNNEL_FACTOR_MAX = 0.3;

	// eigener, eng begrenzter Trichter-Faktor NUR fuer NIVELLEMENT_LOG_POST - faengt systematische Schleppfehler in
	// Nivellementszuegen isoliert ab, ohne den allgemeinen Trichter-Faktor (der weiterhin fuer alle anderen Typen
	// gilt) zu beeinflussen. Bereich/Standard exakt wie vom Anwender vorgegeben.
	public static final double NIVELLEMENT_FUNNEL_FACTOR_DEFAULT = 0.05;
	public static final double NIVELLEMENT_FUNNEL_FACTOR_MIN = 0.01;
	public static final double NIVELLEMENT_FUNNEL_FACTOR_MAX = 0.10;

	// Radius (Meter) fuer den Vor-Scan gleichnamiger Punkte VOR dem ersten Ausgleichungslauf (siehe
	// Klein_Method_Functions.scanForFangkreisViolations() und Klein_Method_Tab_Builder.wireStartStopButtons()).
	// WICHTIG (bewusste Abweichung vom woertlichen Auftrag, hier dokumentiert statt stillschweigend eine autonome
	// Datenbank-Manipulation vorzutaeuschen): dieser Vor-Scan WARNT nur (Protokollzeile + Alert-Dialog) - er
	// nummeriert KEINE Punkte autonom um und trennt KEINE Verknuepfungen in der HSQLDB. Wertebereich bewusst
	// grosszuegig gewaehlt (User hat nur den Standardwert vorgegeben, nicht die Ober-/Untergrenze).
	// Standardwert von 1.0 m auf 0.1 m (10 cm) verschaerft - fuer hochpraezise Ingenieur-Ausgleichungen angemessener.
	public static final double FANGKREIS_RADIUS_DEFAULT = 0.1;
	public static final double FANGKREIS_RADIUS_MIN = 0.01;
	public static final double FANGKREIS_RADIUS_MAX = 10.0;

	// siehe Klein_Method_Functions.performIndividualCalibrationStep() fuer die Anwendung. WICHTIGER HINWEIS (bewusste
	// Abweichung, hier offengelegt statt stillschweigend den bestehenden, bewaehrten Mechanismus zu ersetzen):
	// Klein_Method besitzt bereits einen ECHTEN kollektiven Bearbeitungsbereich pro Typ
	// (computeLogPostBandLowerBound(), logarithmisch, ueber den einstellbaren Trichter-Faktor gesteuert, inkl.
	// Stagnations-Weitung) - ALLE Beobachtungen innerhalb dieses Bandes werden bereits JETZT im selben
	// Iterationsschritt gemeinsam gedaempft (siehe readObservationsForIndividualCalibration()/
	// readGnssObservationsForIndividualCalibration()). Der hier neu ergaenzte, LINEARE Delta-Trichter ERSETZT diesen
	// bewaehrten, ueber ca. 10 Versionsstaende gehaerteten Mechanismus NICHT, sondern ERWEITERT ihn nur: die
	// tatsaechlich verwendete Untergrenze ist das Minimum aus beiden Berechnungen (siehe
	// Klein_Method_Functions.performIndividualCalibrationStep()). Die vom Anwender geforderte 0.20-Nachbarschaft um
	// Max_logPost wird dadurch GARANTIERT immer kollektiv erfasst, ohne den bestehenden, adaptiven Mechanismus
	// (Trichter-Faktor, Stagnations-Weitung, Nivellement-Sonderfall) zu verkleinern oder zu deaktivieren - eine
	// vollstaendige Ersetzung waere ein unkalkulierbares Regressionsrisiko fuer das bereits fein austarierte
	// Konvergenzverhalten gewesen.
	public static final double COLLECTIVE_DELTA_DEFAULT = 0.20;
	public static final double COLLECTIVE_DELTA_MIN = 0.05;
	public static final double COLLECTIVE_DELTA_MAX = 1.00;

	// Universal-Formel fuer den Gruppen-Faktor V - siehe Klein_Method_Functions.performGroupCalibrationStep() fuer
	// die Anwendung.
	public static final double GROUP_SCALE_FACTOR_MIN = 1.00;
	public static final double GROUP_SCALE_FACTOR_DEFAULT = 1.00;

	// dynamischer "Autopilot"-Deckel - Standard 100.00, wird per Math.ceil((Messachsen - 1500000) / 1500000) * 50
	// progressiv erhoeht, sobald readTotalMeasurementAxisCount() >= 1.500.000 liefert.
	public static final double GROUP_SCALE_FACTOR_BASE_LIMIT = 100.00;
	public static final int AUTOPILOT_AXIS_THRESHOLD = 1_500_000;
	public static final double AUTOPILOT_STEP = 50.0;

	private Map<Klein_Method_Threshold_Type, Double> currentValues = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private Map<Klein_Method_Threshold_Type, Double> thresholdValues = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private Map<Klein_Method_Threshold_Type, Boolean> valueAvailable = new EnumMap<>(Klein_Method_Threshold_Type.class);
	/**
	 * aktuelle Gruppen-Messunsicherheiten je logPost-Typ als Spanne ueber alle aktiven Gruppen, Rohwerte aus JAG3D
	 * (Radiant/Meter/einheitenlos): {min a, max a, min b, max b, min c, max c}; fehlt ein Typ, gibt es keine Gruppe.
	 * Gefuellt im Hintergrund-Lesezyklus (Klein_Method_Database_Handler.read()), nur Anzeige.
	 */
	private final Map<Klein_Method_Threshold_Type, double[]> groupUncertaintyRanges = new java.util.concurrent.ConcurrentHashMap<Klein_Method_Threshold_Type, double[]>();
	private Klein_Method_Evaluation_Method evaluationMethod = Klein_Method_Evaluation_Method.LOG_POST;

	// Iterationszaehler: startet bei 0, wird nach jeder Schleife +1 erhoeht,
	// manuell per Reset-Button wieder auf 0 setzbar. "volatile", da der
	// Zaehler aus dem Hintergrund-Thread (Iteration_Task) geschrieben und
	// aus der Oberflaeche gelesen wird.
	private volatile int iterationCount = 0;
	private volatile int iterationThreshold = ITERATION_THRESHOLD_DEFAULT;
	private volatile double funnelFactor = FUNNEL_FACTOR_DEFAULT;
	// Zielkorridor der Gruppen-Daempfung, live aus der UI (Sektion "Grenzwerte"), siehe setVksBandMin()/Max().
	private volatile double vksBandMin = Klein_Method_Default_Values.VKS_BAND_MIN_DEFAULT;
	private volatile double vksBandMax = Klein_Method_Default_Values.VKS_BAND_MAX_DEFAULT;
	// Trichter-Untergrenze (logPost), siehe setFunnelFloorLogPost().
	private volatile double funnelFloorLogPost = Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_DEFAULT;
	// Abbruchgrenze ab Kaskaden-Durchlauf 2, siehe setCascadeAbortLimit().
	private volatile double cascadeAbortLimit = Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_DEFAULT;
	private volatile double nivellementFunnelFactor = NIVELLEMENT_FUNNEL_FACTOR_DEFAULT;
	private volatile double fangkreisRadius = FANGKREIS_RADIUS_DEFAULT;
	private volatile double collectiveDelta = COLLECTIVE_DELTA_DEFAULT;

	// pro Beobachtungstyp EIN Gruppen-Faktor V (Sektion 4-Gitter, Standard 1.00 je Typ - siehe Konstruktor fuer die
	// Vorbefuellung).
	private Map<Klein_Method_Threshold_Type, Double> groupScaleFactor = new EnumMap<>(Klein_Method_Threshold_Type.class);

	// zuletzt (beim Start-Klick) ermittelte Gesamtzahl der Messachsen im Projekt
	// (Klein_Method_Database_Handler.readTotalMeasurementAxisCount())
	// Grundlage fuer den Autopilot-Deckel (getMaxGroupScaleLimit()). 0, solange noch nie gemessen (dann gilt
	// GROUP_SCALE_FACTOR_BASE_LIMIT unveraendert).
	private volatile int lastMeasurementAxisCount = 0;

	// Stagnations-Waechter-Zustand - siehe Klein_Method_Default_Values.STAGNATION_* fuer die vollstaendige
	// Begruendung. "widenedFunnelFactor" ist NaN, solange kein Stagnations-Widening aktiv ist (dann gilt schlicht der
	// UI-Wert "funnelFactor") - andernfalls der aktuell erhoehte, INTERNE Wert, der NIEMALS in "funnelFactor" selbst
	// zurueckgeschrieben wird (die UI-Anzeige des Trichter-Faktor-Feldes bleibt dadurch unveraendert beim vom
	// Anwender gesetzten Wert).
	private final LinkedList<Double> maxLogPostHistory = new LinkedList<Double>();
	// Waechter: bester Max-logPost seit der letzten Umschaltung und Iterationen ohne neuen Bestwert
	private volatile double watchBest = Double.NaN;
	private volatile int watchNoBestCount = 0;
	// letzte Max-logPost-Werte (ungeloescht) fuer die Saegezahn-Erkennung
	private final LinkedList<Double> recentMaxLogPost = new LinkedList<Double>();
	// Rueckkehr auf prozentual pruefen: Max-logPost beim Zurueckschalten (NaN = keine Pruefung offen)
	private volatile double returnCheckValue = Double.NaN;
	// true = Rueckkehr hat geschadet, NORMAL bleibt bis zum Ende des Durchlaufs
	private volatile boolean backoffPermanent = false;
	// Daempfung im Modus normal bei Saegezahn (Einstellung, Standard aus)
	private volatile boolean normalDampingEnabled = false;
	// dynamische AGL: letztes sigma je offenem Punkt/Gruppe (Stagnations-Ausnahme bei stetiger Bewegung)
	private final java.util.Map<String, Double> dynamicLastSigma = new java.util.HashMap<String, Double>();
	private volatile double widenedFunnelFactor = Double.NaN;

	// zuletzt gelesene Anzahl deaktivierter bzw. insgesamt vorhandener GNSS-Basislinien im aktuell geladenen Projekt
	// (siehe Klein_Method_Database_Handler.readGnssBaselineDeactivationCounts()) - fuer das Live-Label "Deaktivierte
	// BL: X / Y" in Klein_Method_Tab_Builder. "disabled" zaehlt ALLE aktuell auf enable=false stehenden
	// GNSS-Basislinien, NICHT nur die von der Kappungsgrenze betroffenen - die Datenbank haelt keinen Grund fuer
	// "enable=false" fest, daher ist diese Unterscheidung aus der HSQLDB heraus nicht zuverlaessig moeglich (siehe
	// Ruecksprache dazu).
	private volatile int disabledGnssBaselineCount = 0;
	private volatile int totalGnssBaselineCount = 0;

	// temporaerer, RAM-interner Zwangswechsel auf Klein_Method_Distribution_Type.NORMAL bei erkannter
	// Oszillation/Stagnation im PERCENTAGE-Modus - siehe Klein_Method_Functions.performIndividualCalibrationStep()
	// und Klein_Method_Default_Values.BACKOFF_*. "backoffActive" true, solange der Zwangswechsel laeuft;
	// "backoffWindowSize" die aktuelle Dauer (Iterationen) dieses Zwangswechsels - verdoppelt bei jeder ERNEUTEN
	// Ausloesung im selben Lauf (progressive Aufweitung), hart gedeckelt bei
	// Klein_Method_Default_Values.BACKOFF_WINDOW_MAX. Der persistierte, vom Anwender in der UI gewaehlte
	// "distributionType" selbst (siehe Feld oben) wird durch den Zwangswechsel tatsaechlich VERAENDERT - NICHT ueber
	// Klein_Method_Parameter_Store gespeichert, da dort ausschliesslich explizite setDistributionType()-Aufrufe aus
	// der UI persistiert werden, nie dieser interne Automatik-Zwangswechsel.
	private volatile boolean backoffActive = false;
	private volatile int backoffWindowSize = Klein_Method_Default_Values.BACKOFF_NORMAL_MODE_WINDOW_DEFAULT;
	private volatile int backoffWindowCounter = 0;
	private volatile boolean backoffEverActivatedThisRun = false;

	// zuletzt berechneter Ampel-Zustand fuer das Label "Oszillations-Status" in Klein_Method_Tab_Builder - wird in
	// Klein_Method_Functions.performIndividualCalibrationStep() gesetzt (NICHT auf dem JavaFX-UI-Thread), analog zum
	// bestehenden Muster bei disabledGnssBaselineCount.
	private volatile Klein_Method_Oscillation_Status oscillationStatus = Klein_Method_Oscillation_Status.STABIL;

	// rein interner, NICHT im UI/Protokoll sichtbarer Sicherheitszaehler - zaehlt IMMER, unabhaengig von der
	// aktuellen Phase, jede aeussere Automatik-Schleifenrunde mit. Dient ausschliesslich der "Grenzwert
	// Iterationen"-Notbremse in Klein_Method_Iteration_Task, damit eine laengere GROUP_CALIBRATION- bzw.
	// POINT_GROUP_CALIBRATION-Phase trotz des ausgesetzten sichtbaren Zaehlers weiterhin sicher abbrechen kann.
	private volatile int internalCycleCount = 0;

	// Aktuelle Phase des uebergeordneten Ablaufs (siehe Klein_Method_Process_Phase):
	// GROUP_CALIBRATION zuerst, danach INDIVIDUAL_CALIBRATION - gilt IMMER in dieser
	// Reihenfolge. Wird bei jedem neuen Automatik-Lauf auf GROUP_CALIBRATION
	// zurueckgesetzt (siehe resetProcessPhase()-Aufrufstellen in Klein_Method_Iteration_Task).
	private volatile Klein_Method_Process_Phase processPhase = Klein_Method_Process_Phase.GROUP_CALIBRATION;

	// Sperrflag: wird gesetzt, sobald PHASE 2 fuer eine Beobachtung die
	// Obergrenze (0.499/0.999) erreicht/ueberschreitet. Solange true,
	// duerfen weder Automatik noch Einzelschritt aktiviert werden (siehe
	// Klein_Method_Tab_Builder) - erst manuelles Zuruecksetzen (z.B. nach
	// Bereinigen der Ursache) hebt die Sperre wieder auf.
	private volatile boolean individualUncertaintyCeilingExceeded = false;

	// Merkt sich, WELCHER Grenzwert-Typ die Sperre ausgeloest hat (fuer die
	// Pop-up-Meldung) - null, solange die Sperre nicht aktiv ist.
	private volatile Klein_Method_Threshold_Type individualUncertaintyCeilingExceededType = null;

	// KEIN Ersatzfeld dafuer noetig.
	private volatile boolean phase1CalibratedThisPass = false;

	// Waehrend Phase 1 bleibt dieser Wert bei 0 - dort werden KEINE einzelnen Beobachtungen/Achsen verarbeitet,
	// sondern nur die Gruppenunsicherheiten neu kalibriert (fachlich korrektes Verhalten, kein Bug). Standardwert 0
	// (vor der ersten Iteration bzw. wenn keine Automatik laeuft), wie beauftragt "standardmaessig mit 0
	// vorbefuellt".
	private volatile int elementsProcessedLastIteration = 0;

	// Standard PERCENTAGE (prozentual) - bestes Ergebnis der Vergleichslaeufe vom 25.09.2026.
	private Klein_Method_Distribution_Type distributionType = Klein_Method_Distribution_Type.PERCENTAGE;
	private Klein_Method_Distribution_Type userDistributionType = Klein_Method_Distribution_Type.PERCENTAGE;
	private Klein_Method_Coordinate_Export_Format coordinateExportFormat = Klein_Method_Coordinate_Export_Format.TEXT;
	// Zielsystem des Koordinaten-Exports (siehe Klein_Method_Functions.Klein_Method_Coordinate_Export) - als
	// Schluessel (fester Name, z. B. "UTM33", oder "FILE:" + Definitionsname aus dem Projektionsordner)
	private String coordinateExportTarget = "PROJECT";
	// Basislinien-Umrechnung - Epochen je Basislinie (0 einzeln, 1 Mittel wie VBA V0.06, 2 robust), Faktor,
	// Mindestwert [mm]
	private int baselineEpochMode = 2;
	private double baselineSigmaFactor = 1.0, baselineSigmaMinMm = 5.0;
	private String language = "AUTO";
	private volatile boolean simpleView = false;
	// Qualitaet der Gruppenkalibrierung (siehe Klein_Method_Default_Values)
	private volatile double fullBandRedundancy = Klein_Method_Default_Values.FULL_BAND_REDUNDANCY_DEFAULT;
	// Nachlauf auf den Grenzwert (siehe Klein_Method_Default_Values.FOLLOW_UP_*)
	private volatile double followUpMaxIterations = Klein_Method_Default_Values.FOLLOW_UP_MAX_ITERATIONS_DEFAULT;
	private volatile double followUpPatience = Klein_Method_Default_Values.FOLLOW_UP_PATIENCE_DEFAULT;
	private volatile double pointFloorPositionMillimeter = Klein_Method_Default_Values.POINT_FLOOR_POSITION_MM_DEFAULT;
	private volatile double pointFloorHeight1DMillimeter = Klein_Method_Default_Values.POINT_FLOOR_HEIGHT_1D_MM_DEFAULT;
	/** Ergebnis des Nachlaufs im letzten Automatik-Lauf: "aus", "nicht_noetig", "erreicht", "bester_stand". */
	private volatile String followUpOutcome = "aus";
	private volatile double minCalibrationRedundancy = Klein_Method_Default_Values.MIN_CALIBRATION_REDUNDANCY_DEFAULT;
	private volatile double plausibilityFactor = Klein_Method_Default_Values.PLAUSIBILITY_FACTOR_DEFAULT;
	/** Parameter der Koordinatenumrechnung WGS84 <-> lokal (ENh); Standard = Werte aus dem VBA-Code. */
	private final org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Local_System.Parameters localSystem =
			new org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Local_System.Parameters();

	public org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Local_System.Parameters getLocalSystem() {
		return this.localSystem;
	}

	private volatile double approximationTolerancePosition = Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_DEFAULT;
	private volatile double approximationToleranceHeight = Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_DEFAULT;
	private volatile double robustRatioLimit = Klein_Method_Default_Values.ROBUST_RATIO_LIMIT_DEFAULT;
	/**
	 * Hinweise der Kalibrierung dieses Laufs, Schluessel "TYP|a/b/c|ART" bzw. "TYP|robust" -> Text (deutsch,
	 * Protokoll).
	 */
	private final Map<String, String> calibrationNotes = new java.util.concurrent.ConcurrentHashMap<String, String>();
	/**
	 * Kalibrierergebnis am Ende von Durchlauf 1 (vor dem Setzen auf die Untergrenze): "TYP|Gruppe" -> sigma a/b/c
	 * [Arbeitseinheit], "TYP" -> VKS a/b/c.
	 */
	private final Map<String, double[]> pass1Sigmas = new java.util.concurrent.ConcurrentHashMap<String, double[]>();
	private final Map<String, double[]> pass1Ratios = new java.util.concurrent.ConcurrentHashMap<String, double[]>();
	/** neue Warnungen fuer die Oberflaeche (Oberflaechensprache), werden vom Lauf abgeholt. */
	private final java.util.concurrent.ConcurrentLinkedQueue<String> calibrationWarnings = new java.util.concurrent.ConcurrentLinkedQueue<String>();
	private Klein_Method_Device_Class deviceClass = Klein_Method_Device_Class.ELEKTRONISCH;
	private boolean turboEnabled = true;
	// Standard "ja" - steuert Klein_Method_Functions.performFloorEnforcementCompression(), siehe dort fuer die
	// vollstaendige fachliche Begruendung dieser bewussten, einmaligen Ausnahme von der sonst geltenden "keine
	// Untergrenze"-Regel. persistenter UI-Zustand des Toggles. Entspricht dem vom Anwender benannten Steuerungs-Feld
	// "isKaskadeActive" - hier konsistent zum bestehenden Namensmuster (floorEnforcementEnabled) benannt. Feldname
	// bewusst unveraendert ("cascadeDoubleRunEnabled") - hat nur den UI-Text und die Ablaufsteuerung erweitert
	// (unbegrenzt viele Passes statt genau zwei), nicht die grundsaetzliche Bedeutung dieses Schalters ("Kaskade
	// an/aus"). Gesteuert/gelesen von Klein_Method_Iteration_Task, siehe dort fuer die vollstaendige Ablaufsteuerung.
	private boolean cascadeDoubleRunEnabled = true;

	// persistenter UI-Zustand des neuen Toggles "Deaktivieren BL:", Standard "nein (empfohlen)" (also false) - WIE
	// BEAUFTRAGT. Steuert AUSSCHLIESSLICH die Trichter-Eliminierung (der Hard-Outlier-Check gegen
	// GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD, siehe Klein_Method_Functions.performIndividual CalibrationStep()) - siehe
	// dort fuer den ausfuehrlichen Fundstellen-Hinweis, warum der separate Achsen-Uebersteuerungs-Check (numerische
	// Sicherung gegen Gewicht->Unendlich in Verteilungsmodus PERCENTAGE) davon UNBERUEHRT bleibt.
	private boolean deactivateGnssOutlierEnabled = false;
	private volatile boolean approximationPriority = true;
	private volatile boolean massThinning = Klein_Method_Default_Values.MASS_THINNING_DEFAULT;
	private volatile boolean finalScaling = Klein_Method_Default_Values.FINAL_SCALING_DEFAULT;
	private volatile boolean autoGrossErrorCleanup = Klein_Method_Default_Values.AUTO_GROSS_CLEANUP_DEFAULT;
	private volatile double grossErrorGnssPpm = Klein_Method_Default_Values.GROSS_ERROR_GNSS_PPM_DEFAULT;

	/** "Grobe Fehler bereinigen" automatisch zu Beginn der Automatik. */
	public boolean isAutoGrossErrorCleanup() {
		return this.autoGrossErrorCleanup;
	}

	public void setAutoGrossErrorCleanup(boolean value) {
		this.autoGrossErrorCleanup = value;
	}

	/** laengenabhaengiger Anteil der Grenze grober Fehler fuer GNSS-Basislinien (ppm). */
	public double getGrossErrorGnssPpm() {
		return this.grossErrorGnssPpm;
	}

	public double setGrossErrorGnssPpm(double value) {
		this.grossErrorGnssPpm = validated(value, Klein_Method_Default_Values.GROSS_ERROR_GNSS_PPM_MIN, Klein_Method_Default_Values.GROSS_ERROR_GNSS_PPM_MAX,
				Klein_Method_Default_Values.GROSS_ERROR_GNSS_PPM_DEFAULT, true);
		return this.grossErrorGnssPpm;
	}

	/** Schlussangleichung der Genauigkeiten an das VKS-Band nach fertiger Automatik. */
	public boolean isFinalScaling() {
		return this.finalScaling;
	}

	public void setFinalScaling(boolean finalScaling) {
		this.finalScaling = finalScaling;
	}
	/** eigene Vorlagen "Punkte umbenennen": je Vorlage Name\u001fSuchen\u001fErsetzen\u001fregex(1/0), getrennt durch \u001e. */
	private volatile String renameTemplates = "";

	public String getRenameTemplates() {
		return this.renameTemplates;
	}

	public void setRenameTemplates(String renameTemplates) {
		this.renameTemplates = renameTemplates == null ? "" : renameTemplates;
	}

	/** Massen-Basislinien vor der ersten Berechnung automatisch ausduennen (aus = Berechnung gesperrt, bis ausgeduennt). */
	public boolean isMassThinning() {
		return this.massThinning;
	}

	public void setMassThinning(boolean massThinning) {
		this.massThinning = massThinning;
	}
	// Schalter "Gruppen-Dämpfung in Phase 2". false (Standard) = Verhalten wie Phase 1 kalibriert sigma_a/b/c ueber
	// so viele Iterationen wie noetig bis in den VKS-Korridor (oder an die Untergrenze), danach bleiben die
	// Gruppenwerte waehrend Phase 2 unveraendert. true = Verhalten Phase 1 genau ein Versuch je Durchlauf, danach
	// Nachregelung in JEDER Iteration.
	private boolean groupDampingInPhase2Enabled = false;
	// Schalter, Standard true. Wirkt NUR bei Kaskaden-Mehrfachlauf (der zweite Durchlauf entsteht nur mit
	// "Untergrenze
	// erzwingen: ja"). Nach dem ersten DONE werden sigma_a/b/c aller Gruppen auf ihre Untergrenze gesetzt; in allen
	// weiteren Durchlaeufen bleibt Phase 1 gesperrt, damit der Wert nicht wieder hochgeregelt wird.
	private boolean groupFloorAfterPass1Enabled = true;
	// NaN = noch kein Wert.
	private volatile double bestMaxLogPost = Double.NaN;
	private volatile int bestMaxLogPostPass = 0;
	private volatile int bestMaxLogPostIteration = 0;
	// Kaskaden-Abbruchziel (NaN = keins) und Flag "Ziel erreicht, Lauf endet".
	private volatile double cascadeTargetLogPost = Double.NaN;
	private volatile boolean cascadeTargetReached = false;
	// Sperrt ab dann Phase 1 und die Gruppen-Daempfung in Phase 2. Zurueckgesetzt bei jedem echten Lauf-Start.
	private volatile boolean groupFloorLockActive = false;

	private boolean floorEnforcementEnabled = true;

	// true, sobald Klein_Method_Functions.performFloorEnforcementCompression() im AKTUELLEN Lauf tatsaechlich
	// mindestens eine Beobachtung per Hard-Clamp ueber-schrieben hat (clampedObservationCount > 0) - siehe dort. Wird
	// bei jedem neuen Automatik-/Einzelschritt-Lauf in resetProcessPhase() zurueckgesetzt, damit ein "ANSCHLAG OK"
	// aus einem fruehreren Lauf nicht faelschlich fuer den aktuellen Lauf stehen bleibt (identisches
	// Zuruecksetzungsmuster wie z.B. maxLogPostHistory/backoffEverActivatedThisRun dort).
	private boolean hardClampAppliedThisRun = false;

	// ersetzt das Flag "cascadeFirstPassDone" (das genau EINEN Neustart erlaubte) durch einen Zaehler, da jetzt
	// beliebig viele Passes hintereinander laufen sollen, bis Konvergenz erreicht ist. Zaehlt die Anzahl bereits
	// gestarteter Kaskaden-Neustarts DIESES Laufs (0 = noch kein Neustart, d.h. Pass 1 laeuft noch). Siehe dort
	// (MAX_CASCADE_PASSES) fuer das Sicherheitslimit.
	private int cascadePassCount = 0;

	// das zuletzt von Klein_Method_Functions.performFloorEnforcement Compression() ermittelte "tatsaechlich
	// korrigiert"-Ergebnis. Steuert die Kaskaden-Abbruchbedingung in Klein_Method_Iteration_Task: > 0 -> naechster
	// Pass, == 0 -> echte Konvergenz, Kaskade endet. Wird beim echten Start eines neuen Laufs auf 0 zurueckgesetzt
	// (siehe Klein_Method_Iteration_Task.call()), damit ein Lauf ganz ohne aktivierte Untergrenzen-Erzwingung (kein
	// Aufruf von performFloorEnforcementCompression() ueberhaupt) korrekt als "nichts zu korrigieren" gilt und die
	// Kaskade nach Pass 1 sauber stoppt.
	private int lastFloorEnforcementChangedCount = 0;

	// fuer die GESAMTE Kaskaden-Sitzung (alle Passes dieses Laufs) gueltige Ausschlussliste bereits per Hard-Clamp
	// auf den Boden gesetzter Beobachtungen/Achsen - Schluessel-Format "OBS:<observationId>" bzw.
	// "GNSS:<observationId>:
	// <achse>", siehe Klein_Method_Functions fuer die Erzeugung/Pruefung. Ein dort deklariertes Set wuerde also OHNE
	// zusaetzliche manuelle Leerung ueber mehrere, komplett unabhaengige Automatik-Laeufe hinweg weiterbestehen.
	// Genau wie cascadePassCount/ lastFloorEnforcementChangedCount oben gehoert dieser Zustand deshalb hierher, auf
	// das PRO-LAUF-Objekt Klein_Method_Parameter, und wird ueber denselben, bereits etablierten Reset-Mechanismus
	// behandelt: NICHT in resetProcessPhase() (das laeuft auch bei JEDEM Pass-Wechsel), sondern exklusiv beim ECHTEN
	// Start eines neuen Automatik-Laufs in Klein_Method_Iteration_Task.call().
	private final Set<String> clampedObservationsRegistry = new HashSet<String>();

	// sitzungsweite RAM-Zaehlschleife fuer die neue Diagnose-Zeile "Reelle EP-Verteilungsrate:" (siehe
	// Klein_Method_Tab_Builder). ARCHITEKTUR-HINWEIS (dieselbe Begruendung wie bei
	// clampedObservationsRegistry/cascadePassCount oben, siehe dort): der Auftrag wollte diese beiden Zaehler direkt
	// in Klein_Method_Functions als Instanzfeld deklariert haben - Klein_Method_Functions ist jedoch die EINZIGE,
	// ueber die gesamte Plugin-Laufzeit wiederverwendete Instanz, NICHT pro Automatik-Lauf neu erzeugt. Dort
	// deklariert, wuerden beide Zaehler ohne zusaetzliche manuelle Leerung ueber mehrere, voneinander unabhaengige
	// Automatik-Laeufe hinweg fehlerhaft weiterbestehen - exakt der Fehler, den der Auftrag selbst mit "bei jedem
	// Klick auf Start (Sitzungs-Init) im RAM auf 0 zurueckgesetzt" vermeiden will. Die Zaehler liegen deshalb auf
	// diesem PRO-LAUF-Objekt und werden ueber denselben, bereits etablierten Reset-Mechanismus behandelt: exklusiv
	// beim ECHTEN Start eines neuen Automatik-Laufs in Klein_Method_Iteration_Task.call() (siehe dort).
	// totalGnssCalculations: zaehlt JEDE GNSS-Achse (X/Y/Z einer Basislinie), die in
	// Klein_Method_Functions.performIndividualCalibrationStep() den finalen Achsen-Verarbeitungs-Zyklus erreicht
	// (Nenner der Quote).
	private int totalGnssCalculations = 0;
	private int prozentualSplittedCalculations = 0;

	private int decimalPlaces = Klein_Method_Format.DEFAULT_DECIMAL_PLACES;

	// Editierbare Untergrenzen der Gruppenunsicherheit (sigma_a/sigma_b/sigma_c),
	// getrennt nach Geraeteklasse (Elektronisch/Historisch - Kombiniert nutzt
	// beim Lesen/Schreiben immer den Elektronisch-Satz, siehe getActiveGroupUncertaintyFloors())
	// und Beobachtungstyp. Initial mit den Klein_Method_Default_Values-Konstanten
	// befuellt (siehe Konstruktor), danach frei editierbar (UI: Typ-Auswahl + 3
	// Felder sigma_a/sigma_b/sigma_c, wechselt automatisch mit der aktuell
	// gewaehlten Geraeteklasse). Index 0=sigma_a, 1=sigma_b, 2=sigma_c.
	private Map<Klein_Method_Threshold_Type, double[]> electronicGroupUncertaintyFloors = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private Map<Klein_Method_Threshold_Type, double[]> historicalGroupUncertaintyFloors = new EnumMap<>(Klein_Method_Threshold_Type.class);

	public Klein_Method_Parameter() {
		// Default-Grenzwerte fuer ALLE Klein_Method_Threshold_Type-Eintraege
		// (aktuell 10: 5x logPost + 5x EP) - vorlaeufig, bis konkrete Werte
		// feststehen. 0.0 bedeutet hier "noch nicht festgelegt".
		for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.values()) {
			this.currentValues.put(type, 0.0);
			this.thresholdValues.put(type, 0.0);
			this.valueAvailable.put(type, false); // noch nichts gelesen -> "k.A."
			this.groupScaleFactor.put(type, GROUP_SCALE_FACTOR_DEFAULT);
		}

		// Reproduziert per echtem Protokollauszug des Users.
		this.setSharedLogPostThreshold(0.0); // wird durch die Klemmung automatisch auf 2.45 angehoben

		// Gruppenunsicherheits-Grenzwerte fuer die 5 logPost-Typen mit den
		// bestaetigten Default-Werten vorbefuellen (alle 3 Komponenten
		// starten mit derselben Zahl, siehe Klein_Method_Default_Values.
		// getGroupUncertaintyFloor() - danach unabhaengig editierbar).
		for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST)) {
			double electronicSeed = Klein_Method_Default_Values.getGroupUncertaintyFloor(type, Klein_Method_Device_Class.ELEKTRONISCH);
			double historicalSeed = Klein_Method_Default_Values.getGroupUncertaintyFloor(type, Klein_Method_Device_Class.HISTORISCH);
			this.electronicGroupUncertaintyFloors.put(type, new double[] { electronicSeed, electronicSeed, electronicSeed });
			this.historicalGroupUncertaintyFloors.put(type, new double[] { historicalSeed, historicalSeed, historicalSeed });
		}
	}

	public double getCurrentValue(Klein_Method_Threshold_Type type) {
		return this.currentValues.get(type);
	}

	/**
	 * Liefert den aktuellen Wert fuer Vergleiche/Auswertungen - bei Typen
	 * mit isEvaluateAsAbsoluteValue()==true (z.B. logPost/lnPost, koennen
	 * negativ sein) wird automatisch der Betrag gebildet, damit negative
	 * Werte nicht faelschlich als "klein"/"gut" durchgehen.
	 *
	 * Diese Methode ist die zentrale Stelle fuer sowas - bitte fuer
	 * Vergleiche/Grenzwertpruefungen IMMER diese statt getCurrentValue()
	 * verwenden.
	 */
	public double getEvaluationValue(Klein_Method_Threshold_Type type) {
		double value = this.getCurrentValue(type);
		return type.isEvaluateAsAbsoluteValue() ? Math.abs(value) : value;
	}

	public void setCurrentValue(Klein_Method_Threshold_Type type, double value) {
		this.currentValues.put(type, value);
		this.valueAvailable.put(type, true); // echter, aus der Projektdatenbank gelesener Wert
	}

	/**
	 * Ueberladung fuer Werte aus der Datenbank, die fehlen koennen (SQL
	 * NULL bzw. kein Eintrag vorhanden). Rueckfallebene laut Vorgabe:
	 * fehlender Wert -> als 0 bewerten. Fuer die Anzeige wird zusaetzlich
	 * gemerkt, dass es sich dabei NICHT um einen echten Wert handelt
	 * (siehe isValueAvailable() / "k.A." in der Oberflaeche).
	 */
	public void setCurrentValue(Klein_Method_Threshold_Type type, Double value) {
		this.currentValues.put(type, value != null ? value : 0.0);
		this.valueAvailable.put(type, value != null);
	}

	public boolean isValueAvailable(Klein_Method_Threshold_Type type) {
		return Boolean.TRUE.equals(this.valueAvailable.get(type));
	}

	public double getThresholdValue(Klein_Method_Threshold_Type type) {
		return this.thresholdValues.get(type);
	}

	/**
	 * Setzt den Grenzwert fuer den angegebenen Typ. Unterschreitet der
	 * Wert den Mindestwert des Typs (z.B. 0.5mm bei den logPost-Typen),
	 * wird automatisch auf den Mindestwert korrigiert.
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setThresholdValue(Klein_Method_Threshold_Type type, double value) {
		double correctedValue = Math.max(value, type.getMinimumAllowedValue());
		this.thresholdValues.put(type, correctedValue);
		return correctedValue;
	}

	/**
	 * Setzt EINEN gemeinsamen logPost-Grenzwert fuer ALLE 4 logPost-Typen gleichzeitig - der logPost-Grenzwert ist im
	 * Kern ein statistisches Signifikanzkriterium und haengt NICHT vom gemessenen Groessentyp ab
	 * (Richtung/Strecke/Zenitwinkel), daher auf Wunsch zusammengefasst statt 4 einzeln pflegbarer (aber ohnehin immer
	 * identischer) Werte. Intern bleiben die 4 Klein_Method_Threshold_Type-Eintraege bestehen (werden fuer die
	 * Kalibrierung pro Typ gebraucht), sie werden hier nur synchron gehalten. Die EP-Grenzwerte sind davon NICHT
	 * betroffen.
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setSharedLogPostThreshold(double value) {
		double correctedValue = value;
		for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST))
			correctedValue = this.setThresholdValue(type, value);
		return correctedValue;
	}

	/**
	 * Liefert den gemeinsamen logPost-Grenzwert - da alle 4 logPost-Typen
	 * synchron gehalten werden (siehe setSharedLogPostThreshold()), reicht
	 * es, den ersten abzufragen.
	 */
	public double getSharedLogPostThreshold() {
		return this.getThresholdValue(Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST)[0]);
	}

	public Klein_Method_Evaluation_Method getEvaluationMethod() {
		return this.evaluationMethod;
	}

	public void setEvaluationMethod(Klein_Method_Evaluation_Method evaluationMethod) {
		this.evaluationMethod = evaluationMethod;
	}

	public int getIterationCount() {
		return this.iterationCount;
	}

	/**
	 * Erhoeht den Iterationszaehler um 1 (nach jeder Schleife aufzurufen).
	 * @return der neue Zaehlerstand
	 */
	public int incrementIterationCount() {
		return ++this.iterationCount;
	}

	/**
	 * erhoeht den rein internen Sicherheitszaehler (nicht im UI/Protokoll sichtbar) - siehe Feldkommentar bei
	 * internalCycleCount. Von Klein_Method_Iteration_Task bei JEDER aeusseren Schleifenrunde aufzurufen, unabhaengig
	 * von der aktuellen Phase.
	 *
	 * @return der neue interne Zaehlerstand
	 */
	public int incrementInternalCycleCount() {
		return ++this.internalCycleCount;
	}

	/** nur den internen Sicherheitszaehler zuruecksetzen (Beginn des Nachlaufs auf den Grenzwert: eigenes Budget). */
	public void resetInternalCycleCount() {
		this.internalCycleCount = 0;
	}

	public int getInternalCycleCount() {
		return this.internalCycleCount;
	}

	/**
	 * Setzt den Iterationszaehler manuell auf 0 zurueck (Reset-Button).
	 */
	// true, sobald eine Automatik mit Phase DONE endet. Solange true, ist "Klein_Method starten" gesperrt
	// (Einzelschritt bleibt aktiv). Freigabe nur noch bei Wechsel der Ausgleichungsstufe (frei/dynamisch/fix) oder
	// per "Reset"; die Stufe steht in der Projektdatenbank (Klein_Method.evaluateCompletedStepLock()), dieses Feld
	// ist nur der aktuelle Arbeitswert.
	private volatile boolean automaticRunCompleted = false;

	public boolean isAutomaticRunCompleted() {
		return this.automaticRunCompleted;
	}

	public void setAutomaticRunCompleted(boolean completed) {
		this.automaticRunCompleted = completed;
	}

	public void resetIterationCount() {
		this.iterationCount = 0;
		// beide Zaehler gemeinsam zuruecksetzen - der interne Sicherheitszaehler gehoert fachlich zum selben "neuer
		// Automatik-Lauf beginnt"-Ereignis wie der sichtbare Iterationszaehler.
		this.internalCycleCount = 0;
	}

	public int getIterationThreshold() {
		return this.iterationThreshold;
	}

	/**
	 * Setzt den Grenzwert Iterationen. Ueberschreitet der Wert die Obergrenze, wird automatisch auf die Obergrenze
	 * korrigiert. Werte unter 0 werden auf 0 korrigiert.
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public int setIterationThreshold(int value) {
		int correctedValue = Math.max(0, Math.min(value, ITERATION_THRESHOLD_MAX));
		this.iterationThreshold = correctedValue;
		return correctedValue;
	}

	public double getCascadeAbortLimit() {
		return this.cascadeAbortLimit;
	}

	/**
	 * Abbruchgrenze ab Durchlauf 2. Erlaubt [2.00; 10.00], sonst (auch NaN) HART auf den Default 5.2 zurueck.
	 *
	 * @return der tatsaechlich gespeicherte Wert
	 */
	public double setCascadeAbortLimit(double value) {
		boolean valid = !Double.isNaN(value)
				&& value >= Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_LOWER_LIMIT
				&& value <= Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_UPPER_LIMIT;
		this.cascadeAbortLimit = valid ? value : Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_DEFAULT;
		return this.cascadeAbortLimit;
	}

	public double getFunnelFloorLogPost() {
		return this.funnelFloorLogPost;
	}

	// Schwarmwissen - GitHub-Repository (Konto/Name, aenderbar) und Warnfaktor nach Phase 1
	private volatile String swarmRepository = org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.DEFAULT_REPOSITORY;
	private volatile double swarmWarnFactor = org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_DEFAULT;

	public String getSwarmRepository() {
		return this.swarmRepository;
	}

	/** "Konto/Repository"; ungueltig (leer, kein "/", Leerzeichen) -> Standard. @return gespeicherter Wert */
	public String setSwarmRepository(String value) {
		String trimmed = value == null ? "" : value.trim();
		boolean valid = trimmed.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
		this.swarmRepository = valid ? trimmed : org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.DEFAULT_REPOSITORY;
		return this.swarmRepository;
	}

	// Schwarmwissen - Vermittlungsdienst zum Senden ohne Token (leer = keiner, dann nur Token oder Browser)
	private volatile String swarmRelayUrl = org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.DEFAULT_RELAY_URL;

	public String getSwarmRelayUrl() {
		return this.swarmRelayUrl;
	}

	/** https://... oder leer; ungueltig -> bisheriger Wert bleibt. @return gespeicherter Wert */
	public String setSwarmRelayUrl(String value) {
		if (org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.isValidRelayUrl(value))
			this.swarmRelayUrl = value.trim();
		return this.swarmRelayUrl;
	}

	public double getSwarmWarnFactor() {
		return this.swarmWarnFactor;
	}

	/** erlaubt [1,5; 100], sonst Standard 3. @return gespeicherter Wert */
	public double setSwarmWarnFactor(double value) {
		boolean valid = !Double.isNaN(value) && value >= org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_MIN
				&& value <= org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_MAX;
		this.swarmWarnFactor = valid ? value : org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_DEFAULT;
		return this.swarmWarnFactor;
	}

	// Richtwert lineare Abweichung [m]
	private volatile double grossErrorLinearLimit = Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_DEFAULT;

	public double getGrossErrorLinearLimit() {
		return this.grossErrorLinearLimit;
	}

	/** ausserhalb [0,001; 100] m oder NaN -> Standard 0,10 m. @return gespeicherter Wert */
	public double setGrossErrorLinearLimit(double value) {
		boolean valid = !Double.isNaN(value) && value >= Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_LOWER_LIMIT
				&& value <= Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_UPPER_LIMIT;
		this.grossErrorLinearLimit = valid ? value : Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_DEFAULT;
		return this.grossErrorLinearLimit;
	}

	/**
	 * setzt die Trichter-Untergrenze (logPost). Erlaubt [2.00; 2.45]
	 * (Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_LOWER_LIMIT/_UPPER_LIMIT). Alles ausserhalb - auch NaN -
	 * wird HART auf den Default 2.30 zurueckgesetzt.
	 *
	 * @return der tatsaechlich gespeicherte Wert
	 */
	public double setFunnelFloorLogPost(double value) {
		boolean valid = !Double.isNaN(value)
				&& value >= Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_LOWER_LIMIT
				&& value <= Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_UPPER_LIMIT;
		this.funnelFloorLogPost = valid ? value : Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_DEFAULT;
		return this.funnelFloorLogPost;
	}

	public double getVksBandMin() {
		return this.vksBandMin;
	}

	public double getVksBandMax() {
		return this.vksBandMax;
	}

	/**
	 * setzt die Korridor-Untergrenze "VKS-Band Min (s²)". Erlaubt ist [0.900; 0.996]
	 * (Klein_Method_Default_Values.VKS_BAND_MIN_LOWER_LIMIT/ _UPPER_LIMIT). Alles ausserhalb - auch NaN/Unendlich -
	 * wird HART auf den Default 0.970 zurueckgesetzt.
	 *
	 * @return der tatsaechlich gespeicherte Wert
	 */
	public double setVksBandMin(double value) {
		boolean valid = !Double.isNaN(value)
				&& value >= Klein_Method_Default_Values.VKS_BAND_MIN_LOWER_LIMIT
				&& value <= Klein_Method_Default_Values.VKS_BAND_MIN_UPPER_LIMIT;
		this.vksBandMin = valid ? value : Klein_Method_Default_Values.VKS_BAND_MIN_DEFAULT;
		return this.vksBandMin;
	}

	/**
	 * setzt die Korridor-Obergrenze "VKS-Band Max (s²)". Erlaubt ist [1.004; 1.100]. Alles ausserhalb wird HART auf
	 * den Default 1.030 zurueckgesetzt. Da Min <= 0.996 < 1.004 <= Max, ist Min < Max immer garantiert - eine
	 * gegenseitige Pruefung beider Felder ist nicht noetig.
	 *
	 * @return der tatsaechlich gespeicherte Wert
	 */
	public double setVksBandMax(double value) {
		boolean valid = !Double.isNaN(value)
				&& value >= Klein_Method_Default_Values.VKS_BAND_MAX_LOWER_LIMIT
				&& value <= Klein_Method_Default_Values.VKS_BAND_MAX_UPPER_LIMIT;
		this.vksBandMax = valid ? value : Klein_Method_Default_Values.VKS_BAND_MAX_DEFAULT;
		return this.vksBandMax;
	}

	public double getFunnelFactor() {
		return this.funnelFactor;
	}

	/**
	 * Setzt den Trichter-Faktor (Bandbreite der Phase-2-Bearbeitung im logarithmischen Bereich, siehe
	 * Klein_Method_Functions.computeLogPostBandLowerBound()). Hart begrenzt auf [0.2; 0.3] - Werte darunter werden
	 * auf 0.2, Werte darueber auf 0.3 korrigiert.
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setFunnelFactor(double value) {
		double correctedValue = Math.max(FUNNEL_FACTOR_MIN, Math.min(value, FUNNEL_FACTOR_MAX));
		this.funnelFactor = correctedValue;
		return correctedValue;
	}

	public double getNivellementFunnelFactor() {
		return this.nivellementFunnelFactor;
	}

	/**
	 * analog zu setFunnelFactor(), aber fuer den separaten, Nivellement-eigenen Trichter-Faktor - hart begrenzt auf
	 * [NIVELLEMENT_FUNNEL_FACTOR_MIN; NIVELLEMENT_FUNNEL_FACTOR_MAX].
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setNivellementFunnelFactor(double value) {
		double correctedValue = Math.max(NIVELLEMENT_FUNNEL_FACTOR_MIN, Math.min(value, NIVELLEMENT_FUNNEL_FACTOR_MAX));
		this.nivellementFunnelFactor = correctedValue;
		return correctedValue;
	}

	public double getFangkreisRadius() {
		return this.fangkreisRadius;
	}

	/**
	 * hart begrenzt auf [FANGKREIS_RADIUS_MIN; FANGKREIS_RADIUS_MAX].
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setFangkreisRadius(double value) {
		double correctedValue = Math.max(FANGKREIS_RADIUS_MIN, Math.min(value, FANGKREIS_RADIUS_MAX));
		this.fangkreisRadius = correctedValue;
		return correctedValue;
	}

	public double getCollectiveDelta() {
		return this.collectiveDelta;
	}

	/**
	 * hart begrenzt auf [COLLECTIVE_DELTA_MIN; COLLECTIVE_DELTA_MAX]. Siehe
	 * Klein_Method_Functions.performIndividualCalibrationStep() fuer die additive (nicht ersetzende) Anwendung.
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setCollectiveDelta(double value) {
		double correctedValue = Math.max(COLLECTIVE_DELTA_MIN, Math.min(value, COLLECTIVE_DELTA_MAX));
		this.collectiveDelta = correctedValue;
		return correctedValue;
	}

	public int getMeasurementAxisCount() {
		return this.lastMeasurementAxisCount;
	}

	public void setMeasurementAxisCount(int count) {
		this.lastMeasurementAxisCount = count;
	}

	/**
	 * 100.00, solange lastMeasurementAxisCount < AUTOPILOT_AXIS_THRESHOLD (1.500.000) - sonst progressiv in glatten
	 * 50er-Schritten erhoeht:
	 *
	 * maxScaleLimit = 100 + (Math.ceil((Messachsen - 1500000) / 1500000.0) * 50)
	 */
	public double getMaxGroupScaleLimit() {
		if (this.lastMeasurementAxisCount < AUTOPILOT_AXIS_THRESHOLD)
			return GROUP_SCALE_FACTOR_BASE_LIMIT;
		double overshoot = this.lastMeasurementAxisCount - AUTOPILOT_AXIS_THRESHOLD;
		double steps = Math.ceil(overshoot / (double) AUTOPILOT_AXIS_THRESHOLD);
		return GROUP_SCALE_FACTOR_BASE_LIMIT + (steps * AUTOPILOT_STEP);
	}

	/** siehe groupUncertaintyRanges: {min a, max a, min b, max b, min c, max c} in Rohwerten, null = keine Gruppe. */
	public double[] getGroupUncertaintyRange(Klein_Method_Threshold_Type type) {
		return this.groupUncertaintyRanges.get(type);
	}

	public void setGroupUncertaintyRanges(Map<Klein_Method_Threshold_Type, double[]> ranges) {
		this.groupUncertaintyRanges.clear();
		this.groupUncertaintyRanges.putAll(ranges);
	}

	public double getGroupScaleFactor(Klein_Method_Threshold_Type type) {
		Double value = this.groupScaleFactor.get(type);
		return value != null ? value : GROUP_SCALE_FACTOR_DEFAULT;
	}

	/**
	 * hart begrenzt auf [GROUP_SCALE_FACTOR_MIN; getMaxGroupScaleLimit()]
	 *
	 * die Obergrenze ist dynamisch (Autopilot), daher KEINE statische MAX-Konstante wie bei den anderen Feldern
	 * dieser Klasse.
	 *
	 * @return der tatsaechlich gespeicherte Wert (nach evtl. Korrektur)
	 */
	public double setGroupScaleFactor(Klein_Method_Threshold_Type type, double value) {
		double correctedValue = Math.max(GROUP_SCALE_FACTOR_MIN, Math.min(value, this.getMaxGroupScaleLimit()));
		this.groupScaleFactor.put(type, correctedValue);
		return correctedValue;
	}

	/**
	 * erfasst den aktuellen globalen Max-logPost-Wert (Klein_Method_Functions.getMaxLogPostValue()) fuer den
	 * Stagnations-Waechter und pflegt die gleitende Historie (Fenstergroesse Klein_Method_Default_Values.
	 * STAGNATION_WINDOW_SIZE). Erkennt zusaetzlich einen "signifikanten Fortschritt" (Abfall gegenueber dem zuletzt
	 * erfassten Wert um mehr als STAGNATION_EPSILON) und setzt in diesem Fall sofort den geweiteten Trichter-Faktor
	 * zurueck (resetWidenedFunnelFactor()).
	 *
	 * @param newValue der aktuelle globale Max-logPost-Wert dieser Iteration
	 */
	public void recordMaxLogPostForStagnationWatch(double newValue) {
		if (!this.maxLogPostHistory.isEmpty()) {
			double previousValue = this.maxLogPostHistory.getLast();
			if (previousValue - newValue > Klein_Method_Default_Values.STAGNATION_EPSILON) {
				// signifikanter Fortschritt: Trichter sofort wieder auf UI-Standard
				this.resetWidenedFunnelFactor();
				this.maxLogPostHistory.clear();
			}
		}
		this.maxLogPostHistory.addLast(newValue);
		while (this.maxLogPostHistory.size() > Klein_Method_Default_Values.STAGNATION_WINDOW_SIZE) {
			this.maxLogPostHistory.removeFirst();
		}
		if (Double.isNaN(this.watchBest) || newValue < this.watchBest * (1.0 - Klein_Method_Default_Values.STAGNATION_NO_BEST_MIN_IMPROVEMENT)) {
			this.watchBest = newValue;
			this.watchNoBestCount = 0;
		} else
			this.watchNoBestCount++;
		this.recentMaxLogPost.addLast(newValue);
		while (this.recentMaxLogPost.size() > Klein_Method_Default_Values.SAWTOOTH_WINDOW)
			this.recentMaxLogPost.removeFirst();
	}

	/** true, wenn seit STAGNATION_NO_BEST_ITERATIONS Iterationen kein neuer Bestwert von Max-logPost erreicht wurde. */
	public boolean isNotImproving() {
		return this.watchNoBestCount >= Klein_Method_Default_Values.STAGNATION_NO_BEST_ITERATIONS;
	}

	/** Zaehler "ohne neuen Bestwert" neu beginnen (nach jeder Umschaltung des Verteilungsmodus). */
	public void resetNotImproving() {
		this.watchBest = Double.NaN;
		this.watchNoBestCount = 0;
	}

	public int getNotImprovingCount() {
		return this.watchNoBestCount;
	}

	/** true, wenn Max-logPost in den letzten SAWTOOTH_WINDOW Werten mindestens zweimal die Richtung gewechselt hat. */
	public boolean isSawtooth() {
		if (this.recentMaxLogPost.size() < Klein_Method_Default_Values.SAWTOOTH_WINDOW)
			return false;
		Double[] v = this.recentMaxLogPost.toArray(new Double[0]);
		int changes = 0;
		for (int i = 2; i < v.length; i++) {
			double d1 = v[i - 1] - v[i - 2], d2 = v[i] - v[i - 1];
			if (d1 * d2 < 0.0)
				changes++;
		}
		return changes >= 2;
	}

	public boolean isNormalDampingEnabled() {
		return this.normalDampingEnabled;
	}

	public void setNormalDampingEnabled(boolean enabled) {
		this.normalDampingEnabled = enabled;
	}

	/** Daempfung im Modus normal wirkt gerade (Schalter ein, Saegezahn erkannt). */
	public boolean isNormalDampingActive() {
		return this.normalDampingEnabled && this.isSawtooth();
	}

	public double getReturnCheckValue() {
		return this.returnCheckValue;
	}

	public void setReturnCheckValue(double value) {
		this.returnCheckValue = value;
	}

	public boolean isBackoffPermanent() {
		return this.backoffPermanent;
	}

	public void setBackoffPermanent(boolean permanent) {
		this.backoffPermanent = permanent;
	}

	public java.util.Map<String, Double> getDynamicLastSigma() {
		return this.dynamicLastSigma;
	}

	/**
	 * true, wenn die Historie bereits die volle Fenstergroesse erreicht hat
	 * UND sich der globale Max-logPost innerhalb dieses Fensters um weniger
	 * als Klein_Method_Default_Values.STAGNATION_EPSILON bewegt hat (Plateau).
	 */
	public boolean isStagnating() {
		if (this.maxLogPostHistory.size() < Klein_Method_Default_Values.STAGNATION_WINDOW_SIZE) {
			return false;
		}
		double minValue = Double.POSITIVE_INFINITY;
		double maxValue = Double.NEGATIVE_INFINITY;
		for (double value : this.maxLogPostHistory) {
			minValue = Math.min(minValue, value);
			maxValue = Math.max(maxValue, value);
		}
		return (maxValue - minValue) < Klein_Method_Default_Values.STAGNATION_EPSILON;
	}

	/**
	 * erkennt - unabhaengig von isStagnating() - ein Saegezahn-Wiederanstiegsmuster, das kein Plateau ist, aber
	 * ebenso einen Backoff rechtfertigt. true, wenn der zuletzt erfasste globale Max-logPost-Wert um mindestens
	 * OSCILLATION_JUMP_THRESHOLD ueber dem Minimum der aktuellen (bis zu STAGNATION_WINDOW_SIZE grossen) Historie
	 * liegt - bewusst OHNE die "volle Fenstergroesse erforderlich"-Bedingung von isStagnating(), damit ein schneller
	 * Wiederanstieg direkt nach einem Einbruch (z.B. durch eine Masse an Kappungs-Deaktivierungen) auch mit einer
	 * noch kurzen Historie erkannt wird.
	 */
	public boolean isOscillating() {
		if (this.maxLogPostHistory.size() < 2) {
			return false;
		}
		double minValue = Double.POSITIVE_INFINITY;
		for (double value : this.maxLogPostHistory) {
			minValue = Math.min(minValue, value);
		}
		double lastValue = this.maxLogPostHistory.getLast();
		return (lastValue - minValue) >= Klein_Method_Default_Values.OSCILLATION_JUMP_THRESHOLD;
	}

	/**
	 * liefert den rohen Anstieg (letzter Wert minus Fenster-Minimum) OHNE Schwellwertvergleich - fuer die
	 * Ampel-Vorstufe TEILWEISE_INSTABIL (Vergleich gegen Klein_Method_Default_Values.OSCILLATION_WARNING_THRESHOLD
	 * durch den Aufrufer, siehe Klein_Method_Functions.performIndividualCalibrationStep()). 0.0, solange die Historie
	 * noch keine 2 Werte enthaelt (kein Anstieg berechenbar).
	 */
	public double getOscillationRiseFromMin() {
		if (this.maxLogPostHistory.size() < 2) {
			return 0.0;
		}
		double minValue = Double.POSITIVE_INFINITY;
		for (double value : this.maxLogPostHistory) {
			minValue = Math.min(minValue, value);
		}
		double lastValue = this.maxLogPostHistory.getLast();
		return lastValue - minValue;
	}

	public Klein_Method_Oscillation_Status getOscillationStatus() {
		return this.oscillationStatus;
	}

	public void setOscillationStatus(Klein_Method_Oscillation_Status oscillationStatus) {
		this.oscillationStatus = oscillationStatus;
	}

	/**
	 * Erhoeht den INTERNEN, geweiteten Trichter-Faktor um
	 * Klein_Method_Default_Values.STAGNATION_FUNNEL_STEP, ausgehend vom
	 * aktuell wirksamen Faktor (getEffectiveFunnelFactor()) - also entweder
	 * vom UI-Wert (erste Weitung) oder vom bereits geweiteten Wert (weitere
	 * Weitungen) - begrenzt auf maximal STAGNATION_FUNNEL_FACTOR_MAX. Der
	 * UI-Wert (funnelFactor) selbst bleibt davon unberuehrt.
	 */
	public void widenFunnelFactorStep() {
		double baseValue = this.getEffectiveFunnelFactor();
		double widened = baseValue + Klein_Method_Default_Values.STAGNATION_FUNNEL_STEP;
		this.widenedFunnelFactor = Math.min(widened, Klein_Method_Default_Values.STAGNATION_FUNNEL_FACTOR_MAX);
	}

	/**
	 * Der tatsaechlich fuer die Phase-2-Bandberechnung (computeLogPostBandLowerBound())
	 * zu verwendende Trichter-Faktor: der geweitete Wert, falls der
	 * Stagnations-Waechter aktuell eine Weitung vorgenommen hat, sonst der
	 * unveraenderte UI-Wert (getFunnelFactor()).
	 */
	public double getEffectiveFunnelFactor() {
		return Double.isNaN(this.widenedFunnelFactor) ? this.getFunnelFactor() : this.widenedFunnelFactor;
	}

	/**
	 * Setzt den geweiteten Trichter-Faktor zurueck (NaN) - ab sofort gilt
	 * wieder ausschliesslich der UI-Wert. Wird sowohl bei signifikantem
	 * Fortschritt (siehe recordMaxLogPostForStagnationWatch()) als auch bei
	 * jedem neuen Automatik-Lauf (resetProcessPhase()) aufgerufen.
	 */
	public void resetWidenedFunnelFactor() {
		this.widenedFunnelFactor = Double.NaN;
	}

	/**
	 * true, solange der Verteilungsmodus wegen erkannter Oszillation/Stagnation temporaer (RAM-intern) auf NORMAL
	 * zwangsgeschaltet ist.
	 */
	public boolean isBackoffActive() {
		return this.backoffActive;
	}

	public int getBackoffWindowSize() {
		return this.backoffWindowSize;
	}

	public int getBackoffWindowCounter() {
		return this.backoffWindowCounter;
	}

	/**
	 * Loest den Zwangswechsel aus: setzt distributionType (RAM-intern, siehe Klassenkommentar oben) auf NORMAL,
	 * aktiviert backoffActive und setzt den Fensterzaehler auf 0 zurueck.
	 */
	public void activateBackoff() {
		if (this.backoffEverActivatedThisRun) {
			double widened = this.backoffWindowSize * Klein_Method_Default_Values.BACKOFF_WINDOW_MULTIPLIER;
			this.backoffWindowSize = (int) Math.min(widened, Klein_Method_Default_Values.BACKOFF_WINDOW_MAX);
		}
		this.backoffEverActivatedThisRun = true;
		this.distributionType = Klein_Method_Distribution_Type.NORMAL;
		this.backoffActive = true;
		this.backoffWindowCounter = 0;
	}

	/**
	 * Zaehlt eine im Zwangs-NORMAL-Modus verbrachte Iteration mit - von
	 * performIndividualCalibrationStep() bei jedem Aufruf waehrend
	 * isBackoffActive()==true aufzurufen.
	 */
	public void incrementBackoffWindowCounter() {
		this.backoffWindowCounter++;
	}

	/**
	 * Beendet den Zwangswechsel: schaltet distributionType (RAM-intern) zurueck auf PERCENTAGE, setzt backoffActive
	 * auf false und den Fensterzaehler zurueck. Das bereits (ggf. progressiv geweitete) backoffWindowSize bleibt fuer
	 * den Rest des Laufs bestehen, damit eine erneute Ausloesung korrekt weiter verdoppelt.
	 */
	public void deactivateBackoff() {
		// Zurueck auf die Auswahl des Anwenders.
		this.distributionType = this.userDistributionType;
		this.backoffActive = false;
		this.backoffWindowCounter = 0;
	}

	/**
	 * Anzahl der GNSS-Basislinien, die im geladenen Projekt aktuell auf enable=false stehen (ALLE, unabhaengig vom
	 * Grund - siehe Klassenkommentar der Felder unten) bzw. die Gesamtzahl der GNSS-Basislinien im Projekt. Wird
	 * waehrend des bestehenden Hintergrund-Lesezyklus (Klein_Method_Database_Handler.read()) aktualisiert, NICHT auf
	 * dem JavaFX-UI-Thread.
	 */
	public int getDisabledGnssBaselineCount() {
		return this.disabledGnssBaselineCount;
	}

	public void setDisabledGnssBaselineCount(int value) {
		this.disabledGnssBaselineCount = value;
	}

	public int getTotalGnssBaselineCount() {
		return this.totalGnssBaselineCount;
	}

	public void setTotalGnssBaselineCount(int value) {
		this.totalGnssBaselineCount = value;
	}

	public boolean hasPhase1CalibratedThisPass() {
		return this.phase1CalibratedThisPass;
	}

	/**
	 * Von Klein_Method_Iteration_Task unmittelbar NACH einem tatsaechlich
	 * ausgefuehrten Phase-1-Kalibrierungsschritt dieses Passes aufzurufen -
	 * sperrt Phase 1 damit fuer den REST dieses einen Passes.
	 */
	public void markPhase1CalibratedThisPass() {
		this.phase1CalibratedThisPass = true;
	}

	/**
	 * Setzt das Flag zurueck - bei JEDEM Pass-Start aufzurufen (echter Automatik-
	 * Lauf-Start UND jeder automatische Kaskaden-Neustart, siehe Klein_Method_
	 * Iteration_Task), damit der neue Pass wieder mit einem frischen, einmaligen
	 * Phase-1-Eichversuch beginnt.
	 */
	public void resetPhase1CalibratedThisPass() {
		this.phase1CalibratedThisPass = false;
		this.phase1TurboStates.clear();
	}

	// beschleunigt das VERRINGERN der Gruppenunsicherheiten in Phase 1 (nur freie und fixe Ausgleichung), siehe
	// Klein_Method_Functions.computeGroupUncertaintyWithTurbo(). Standard ja.
	private boolean phase1TurboEnabled = true;

	public boolean isPhase1TurboEnabled() {
		return this.phase1TurboEnabled;
	}

	public void setPhase1TurboEnabled(boolean phase1TurboEnabled) {
		this.phase1TurboEnabled = phase1TurboEnabled;
	}

	private final java.util.Map<String, double[]> phase1TurboStates = new java.util.concurrent.ConcurrentHashMap<String, double[]>();

	public java.util.Map<String, double[]> getPhase1TurboStates() {
		return this.phase1TurboStates;
	}

	public int getElementsProcessedLastIteration() {
		return this.elementsProcessedLastIteration;
	}

	public void setElementsProcessedLastIteration(int value) {
		this.elementsProcessedLastIteration = value;
	}

	/**
	 * Setzt den Zaehler zurueck (0) - bei jedem echten Automatik-Lauf-Start
	 * aufzurufen, damit kein Wert aus einem fruehreren Lauf stehen bleibt, bevor
	 * die erste Iteration des neuen Laufs ihn ohnehin ueberschreibt.
	 */
	public void resetElementsProcessedLastIteration() {
		this.elementsProcessedLastIteration = 0;
	}

	public Klein_Method_Process_Phase getProcessPhase() {
		return this.processPhase;
	}

	public void setProcessPhase(Klein_Method_Process_Phase processPhase) {
		this.processPhase = processPhase;
	}

	/**
	 * Setzt den Ablauf auf den Anfang zurueck (Phase 1) - bei jedem neuen
	 * Automatik-Lauf aufzurufen, damit wieder mit der Gruppenkalibrierung
	 * begonnen wird.
	 */
	/**
	 * Setzt die Phase auf den Beginn des zum aktuellen Ausgleichungsschritt passenden Ablaufs zurueck: bei DYNAMIC
	 * startet die Festpunktanalyse (POINT_GROUP_CALIBRATION, NUR Punkte) - die gewohnte Beobachtungs-Kalibrierung
	 * findet waehrend der dynamischen AGL GAR NICHT statt.
	 */
	/**
	 * passt die Ablaufphase an die tatsaechliche Ausgleichungsstufe an.
	 *
	 * @return true, wenn zurueckgesetzt wurde
	 */
	public boolean alignProcessPhaseToStep(Klein_Method_Adjustment_Step step) {
		if (step == null || step == Klein_Method_Adjustment_Step.UNDEFINED)
			return false;
		boolean pointPhase = this.processPhase == Klein_Method_Process_Phase.POINT_GROUP_CALIBRATION
				|| this.processPhase == Klein_Method_Process_Phase.POINT_INDIVIDUAL_CALIBRATION;
		boolean dynamic = step == Klein_Method_Adjustment_Step.DYNAMIC;
		if (pointPhase != dynamic) {
			this.resetProcessPhase(step);
			return true;
		}
		return false;
	}

	public void resetProcessPhase(Klein_Method_Adjustment_Step step) {
		this.phase1TurboStates.clear();
		this.pointTurboStates.clear();
		this.groupPredictionStates.clear();
		this.resetDynamicStagnation();
		this.dynamicStagnationDetails = null;
		this.dynamicLiveText = null;
		this.processPhase = step == Klein_Method_Adjustment_Step.DYNAMIC
				? Klein_Method_Process_Phase.POINT_GROUP_CALIBRATION
				: Klein_Method_Process_Phase.GROUP_CALIBRATION;
		// Zirkulations-Flags bei jedem neuen Lauf zuruecksetzen.
		this.pointGroupCalibrationSettled = false;
		this.pointIndividualCalibrationSettled = false;
		this.pointUniformityCheckDone = false;
		this.pointUniformityMismatchDetails = null;
		// Historie und geweiteten Trichter-Faktor bei jedem neuen Automatik-Lauf zuruecksetzen, damit ein Plateau aus
		// einem vorherigen Lauf nicht in den neuen Lauf hinein "nachwirkt".
		this.maxLogPostHistory.clear();
		this.recentMaxLogPost.clear();
		this.resetNotImproving();
		this.returnCheckValue = Double.NaN;
		this.backoffPermanent = false;
		this.resetWidenedFunnelFactor();
		if (this.backoffActive) {
			this.deactivateBackoff();
		}
		this.backoffWindowSize = Klein_Method_Default_Values.BACKOFF_NORMAL_MODE_WINDOW_DEFAULT;
		this.backoffEverActivatedThisRun = false;
		// "ANSCHLAG OK" darf sich nur auf den JEWEILS AKTUELLEN Lauf beziehen - bei jedem neuen Lauf zuruecksetzen,
		// siehe Feldkommentar.
		this.hardClampAppliedThisRun = false;
	}

	// true, sobald performPointGroupCalibrationStep() zuletzt NICHTS mehr
	// korrigieren musste (Zielband/Untergrenze fuer ALLE Gruppen erreicht).
	// Wird bei jeder tatsaechlichen Korrektur wieder auf false gesetzt -
	// siehe Klein_Method_Functions.recompute().
	private volatile boolean pointGroupCalibrationSettled = false;
	private volatile boolean pointIndividualCalibrationSettled = false;

	// Vorgabe: Sicherheitscheck VOR dem Wechsel zu Phase 2). Wird bei jedem neuen Lauf zurueckgesetzt und danach NUR
	// EINMAL pro Lauf durchgefuehrt - ab Phase 2 werden die Komponenten naemlich ABSICHTLICH individuell
	// unterschiedlich (Tprio-abhaengig), ein erneuter Check dort waere sinnlos.
	private volatile boolean pointUniformityCheckDone = false;

	// Bei Abbruch durch recompute()==false gesetzt und von
	// Klein_Method_Iteration_Task ausgelesen, um die richtige Fehlermeldung
	// anzuzeigen - unterscheidet die Obergrenzen-Sperre (Beobachtungen) von
	// der neuen Komponenten-Uniformitaets-Sperre (Festpunktanalyse).
	private volatile String pointUniformityMismatchDetails = null;

	public boolean isPointUniformityCheckDone() {
		return this.pointUniformityCheckDone;
	}

	public void setPointUniformityCheckDone(boolean done) {
		this.pointUniformityCheckDone = done;
	}

	public String getPointUniformityMismatchDetails() {
		return this.pointUniformityMismatchDetails;
	}

	public void setPointUniformityMismatchDetails(String details) {
		this.pointUniformityMismatchDetails = details;
	}

	public boolean isPointGroupCalibrationSettled() {
		return this.pointGroupCalibrationSettled;
	}

	public void setPointGroupCalibrationSettled(boolean settled) {
		this.pointGroupCalibrationSettled = settled;
	}

	public boolean isPointIndividualCalibrationSettled() {
		return this.pointIndividualCalibrationSettled;
	}

	public void setPointIndividualCalibrationSettled(boolean settled) {
		this.pointIndividualCalibrationSettled = settled;
	}

	public boolean isIndividualUncertaintyCeilingExceeded() {
		return this.individualUncertaintyCeilingExceeded;
	}

	public void setIndividualUncertaintyCeilingExceeded(boolean exceeded) {
		this.individualUncertaintyCeilingExceeded = exceeded;
	}

	public Klein_Method_Threshold_Type getIndividualUncertaintyCeilingExceededType() {
		return this.individualUncertaintyCeilingExceededType;
	}

	public void setIndividualUncertaintyCeilingExceededType(Klein_Method_Threshold_Type type) {
		this.individualUncertaintyCeilingExceededType = type;
	}

	/**
	 * Hebt die Obergrenzen-Sperre wieder auf - ausschliesslich ueber den
	 * Button "Sperre aufheben" in der Oberfläche auszuloesen, NICHT
	 * automatisch (siehe Vorgabe: Automatik/Einzelschritt bleiben
	 * gesperrt, bis der Nutzer die Ursache manuell bereinigt UND die
	 * Sperre bewusst aufhebt).
	 */
	public void resetIndividualUncertaintyCeilingExceeded() {
		this.individualUncertaintyCeilingExceeded = false;
		this.individualUncertaintyCeilingExceededType = null;
	}

	public Klein_Method_Distribution_Type getDistributionType() {
		return this.distributionType;
	}

	/** setzt die Auswahl des Anwenders (UI-Schalter, gespeicherte Einstellung). */
	public void setDistributionType(Klein_Method_Distribution_Type distributionType) {
		this.userDistributionType = distributionType;
		this.distributionType = distributionType;
		this.backoffActive = false;
		this.backoffWindowCounter = 0;
	}

	/**
	 * Auswahl des Anwenders (unabhaengig von einem gerade aktiven Oszillationswaechter). getDistributionType()
	 * liefert den WIRKSAMEN Wert.
	 */
	public Klein_Method_Distribution_Type getUserDistributionType() {
		return this.userDistributionType;
	}

	// Welche der beiden real vorhandenen Phase-2-Formeln (siehe Klassenkommentar des verschachtelten Enums fuer die
	// vollstaendige Wirkungs-Begruendung/den Fundstellen-Hinweis) im GNSS-PERCENTAGE-Zweig von
	// performIndividualCalibrationStep() angewendet wird. Standard: GEDAEMPFTER_ACHS_SPLIT_V333 ("gedämpfter Achs
	// Split"). Standard "gedaempfter Achs Split" (GEDAEMPFTER_ACHS_SPLIT_V333). Achsweises sigma_0 bildet die
	// systematisch schwaechere GNSS-Hoehe ab (X/Z gross, Y klein); Pruefung an kombinierten GNSS-/terrestrischen
	// Netzen steht aus.
	private Klein_Method_Damping_Formula_Type dampingFormulaType = Klein_Method_Damping_Formula_Type.GEDAEMPFTER_ACHS_SPLIT_V333;

	public Klein_Method_Damping_Formula_Type getDampingFormulaType() {
		return this.dampingFormulaType;
	}

	public void setDampingFormulaType(Klein_Method_Damping_Formula_Type dampingFormulaType) {
		this.dampingFormulaType = dampingFormulaType;
	}

	/** Einsteigeransicht - nur Start, Ampel, Empfehlung und Nachweis sichtbar. */
	public boolean isSimpleView() {
		return this.simpleView;
	}

	public void setSimpleView(boolean simpleView) {
		this.simpleView = simpleView;
	}

	// --------------- Qualitaet der Gruppenkalibrierung ----------------

	private static double validated(double value, double min, double max, double fallback, boolean zeroAllowed) {
		if (Double.isNaN(value) || Double.isInfinite(value))
			return fallback;
		if (zeroAllowed && value == 0.0)
			return 0.0;
		return value >= min && value <= max ? value : fallback;
	}

	public double getFollowUpMaxIterations() {
		return this.followUpMaxIterations;
	}

	/** Hoechstzahl der Nachlauf-Iterationen, 0 = Nachlauf aus. */
	public double setFollowUpMaxIterations(double value) {
		this.followUpMaxIterations = Math.rint(validated(value, Klein_Method_Default_Values.FOLLOW_UP_MAX_ITERATIONS_MIN,
				Klein_Method_Default_Values.FOLLOW_UP_MAX_ITERATIONS_MAX, Klein_Method_Default_Values.FOLLOW_UP_MAX_ITERATIONS_DEFAULT, true));
		return this.followUpMaxIterations;
	}

	public double getFollowUpPatience() {
		return this.followUpPatience;
	}

	/** Iterationen ohne neuen Bestwert, nach denen der Nachlauf endet. */
	public double setFollowUpPatience(double value) {
		this.followUpPatience = Math.rint(validated(value, Klein_Method_Default_Values.FOLLOW_UP_PATIENCE_MIN,
				Klein_Method_Default_Values.FOLLOW_UP_PATIENCE_MAX, Klein_Method_Default_Values.FOLLOW_UP_PATIENCE_DEFAULT, false));
		return this.followUpPatience;
	}

	/** Untergrenze der Festpunkt-Genauigkeit Lage/2D/3D in der dynamischen AGL (Millimeter). */
	public double getPointFloorPositionMillimeter() {
		return this.pointFloorPositionMillimeter;
	}

	public double setPointFloorPositionMillimeter(double value) {
		this.pointFloorPositionMillimeter = validated(value, Klein_Method_Default_Values.POINT_FLOOR_MM_MIN, Klein_Method_Default_Values.POINT_FLOOR_MM_MAX,
				Klein_Method_Default_Values.POINT_FLOOR_POSITION_MM_DEFAULT, false);
		return this.pointFloorPositionMillimeter;
	}

	/** Untergrenze der Festpunkt-Genauigkeit Hoehenpunkte 1D in der dynamischen AGL (Millimeter). */
	public double getPointFloorHeight1DMillimeter() {
		return this.pointFloorHeight1DMillimeter;
	}

	public double setPointFloorHeight1DMillimeter(double value) {
		this.pointFloorHeight1DMillimeter = validated(value, Klein_Method_Default_Values.POINT_FLOOR_MM_MIN, Klein_Method_Default_Values.POINT_FLOOR_MM_MAX,
				Klein_Method_Default_Values.POINT_FLOOR_HEIGHT_1D_MM_DEFAULT, false);
		return this.pointFloorHeight1DMillimeter;
	}

	/**
	 * Untergrenze je Punktdimension in Meter: die eingestellten Werte gelten nur bei aktiven GNSS-Basislinien im Projekt,
	 * sonst POINT_FLOOR_NO_GNSS_MM (Lage und Hoehe).
	 */
	public double getPointFloor(int dimension) {
		if (!this.pointFloorGnssActive)
			return Klein_Method_Default_Values.POINT_FLOOR_NO_GNSS_MM / 1000.0;
		return (dimension == 1 ? this.pointFloorHeight1DMillimeter : this.pointFloorPositionMillimeter) / 1000.0;
	}

	// aktive GNSS-Basislinien im Projekt (gesetzt beim Start der Automatik und beim Tab-Klick)
	private volatile boolean pointFloorGnssActive = true;

	public boolean isPointFloorGnssActive() {
		return this.pointFloorGnssActive;
	}

	public void setPointFloorGnssActive(boolean active) {
		this.pointFloorGnssActive = active;
	}

	public String getFollowUpOutcome() {
		return this.followUpOutcome;
	}

	public void setFollowUpOutcome(String outcome) {
		this.followUpOutcome = outcome;
	}

	public double getFullBandRedundancy() {
		return this.fullBandRedundancy;
	}

	public double setFullBandRedundancy(double value) {
		this.fullBandRedundancy = validated(value, Klein_Method_Default_Values.FULL_BAND_REDUNDANCY_MIN, Klein_Method_Default_Values.FULL_BAND_REDUNDANCY_MAX,
				Klein_Method_Default_Values.FULL_BAND_REDUNDANCY_DEFAULT, true);
		return this.fullBandRedundancy;
	}

	public double getMinCalibrationRedundancy() {
		return this.minCalibrationRedundancy;
	}

	public double setMinCalibrationRedundancy(double value) {
		this.minCalibrationRedundancy = validated(value, 0.0, Klein_Method_Default_Values.MIN_CALIBRATION_REDUNDANCY_MAX,
				Klein_Method_Default_Values.MIN_CALIBRATION_REDUNDANCY_DEFAULT, true);
		return this.minCalibrationRedundancy;
	}

	public double getPlausibilityFactor() {
		return this.plausibilityFactor;
	}

	public double setPlausibilityFactor(double value) {
		this.plausibilityFactor = validated(value, Klein_Method_Default_Values.PLAUSIBILITY_FACTOR_MIN, Klein_Method_Default_Values.PLAUSIBILITY_FACTOR_MAX,
				Klein_Method_Default_Values.PLAUSIBILITY_FACTOR_DEFAULT, true);
		return this.plausibilityFactor;
	}

	/** Toleranz Lage der Naeherungskoordinaten [m], 0 = keine Pruefung der Lage. */
	public double getApproximationTolerancePosition() {
		return this.approximationTolerancePosition;
	}

	public double setApproximationTolerancePosition(double value) {
		this.approximationTolerancePosition = validated(value, Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_MIN,
				Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_MAX, Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_DEFAULT, true);
		return this.approximationTolerancePosition;
	}

	/** Toleranz Hoehe der Naeherungskoordinaten [m], 0 = keine Pruefung der Hoehe. */
	public double getApproximationToleranceHeight() {
		return this.approximationToleranceHeight;
	}

	public double setApproximationToleranceHeight(double value) {
		this.approximationToleranceHeight = validated(value, Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_MIN,
				Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_MAX, Klein_Method_Default_Values.APPROXIMATION_TOLERANCE_DEFAULT, true);
		return this.approximationToleranceHeight;
	}

	public double getRobustRatioLimit() {
		return this.robustRatioLimit;
	}

	public double setRobustRatioLimit(double value) {
		this.robustRatioLimit = validated(value, Klein_Method_Default_Values.ROBUST_RATIO_LIMIT_MIN, Klein_Method_Default_Values.ROBUST_RATIO_LIMIT_MAX,
				Klein_Method_Default_Values.ROBUST_RATIO_LIMIT_DEFAULT, true);
		return this.robustRatioLimit;
	}

	public Map<String, String> getCalibrationNotes() {
		return this.calibrationNotes;
	}

	public Map<String, double[]> getPass1Sigmas() {
		return this.pass1Sigmas;
	}

	public Map<String, double[]> getPass1Ratios() {
		return this.pass1Ratios;
	}

	public java.util.Queue<String> getCalibrationWarnings() {
		return this.calibrationWarnings;
	}

	/** Oberflaechensprache: "AUTO" oder Sprachkuerzel (de/en/pl/cs/fr/es/pt). */
	public String getLanguage() {
		return this.language;
	}

	public void setLanguage(String language) {
		String value = language == null ? "" : language.trim();
		if (value.equalsIgnoreCase("AUTO") || value.isEmpty()) {
			this.language = "AUTO";
			return;
		}
		for (String lang : new String[] { "de", "en", "pl", "cs", "fr", "es", "pt" })
			if (lang.equalsIgnoreCase(value)) {
				this.language = lang;
				return;
			}
		this.language = "AUTO"; // unbekannt -> automatisch
	}

	/** Koordinaten-Export: Hoehen als Normalhoehe H = h - N (aktives Quasigeoidmodell) statt ellipsoidisch. */
	private boolean coordinateExportNormalHeight = false;

	public boolean isCoordinateExportNormalHeight() {
		return this.coordinateExportNormalHeight;
	}

	public void setCoordinateExportNormalHeight(boolean coordinateExportNormalHeight) {
		this.coordinateExportNormalHeight = coordinateExportNormalHeight;
	}

	public Klein_Method_Coordinate_Export_Format getCoordinateExportFormat() {
		return this.coordinateExportFormat;
	}

	public void setCoordinateExportFormat(Klein_Method_Coordinate_Export_Format coordinateExportFormat) {
		this.coordinateExportFormat = coordinateExportFormat;
	}

	public int getBaselineEpochMode() {
		return this.baselineEpochMode;
	}

	public void setBaselineEpochMode(int mode) {
		if (mode >= 0 && mode <= 2)
			this.baselineEpochMode = mode;
	}

	public double getBaselineSigmaFactor() {
		return this.baselineSigmaFactor;
	}

	public void setBaselineSigmaFactor(double factor) {
		if (Double.isFinite(factor) && factor > 0.0 && factor <= 100.0)
			this.baselineSigmaFactor = factor;
	}

	public double getBaselineSigmaMinMm() {
		return this.baselineSigmaMinMm;
	}

	public void setBaselineSigmaMinMm(double mm) {
		if (Double.isFinite(mm) && mm > 0.0 && mm <= 1000.0)
			this.baselineSigmaMinMm = mm;
	}

	public String getCoordinateExportTarget() {
		return this.coordinateExportTarget;
	}

	public void setCoordinateExportTarget(String key) {
		if (key != null && !key.trim().isEmpty())
			this.coordinateExportTarget = key.trim();
	}

	public Klein_Method_Device_Class getDeviceClass() {
		return this.deviceClass;
	}

	public void setDeviceClass(Klein_Method_Device_Class deviceClass) {
		this.deviceClass = deviceClass;
	}

	/**
	 * Liefert das 3er-Array [sigma_a, sigma_b, sigma_c] der AKTUELL gewaehlten
	 * Geraeteklasse (this.deviceClass) fuer den angegebenen Typ - KOMBINIERT
	 * nutzt dabei immer den Elektronisch-Satz (bestaetigte Vorgabe). Direkt
	 * das gespeicherte Array (keine Kopie) - Aenderungen daran wirken sich
	 * sofort aus, siehe setGroupUncertaintyFloorA()/B()/C().
	 */
	private double[] getActiveGroupUncertaintyFloors(Klein_Method_Threshold_Type type) {
		Map<Klein_Method_Threshold_Type, double[]> map = this.deviceClass == Klein_Method_Device_Class.HISTORISCH
				? this.historicalGroupUncertaintyFloors
				: this.electronicGroupUncertaintyFloors; // ELEKTRONISCH und KOMBINIERT
		return map.get(type);
	}

	public double getGroupUncertaintyFloorA(Klein_Method_Threshold_Type type) {
		return this.getActiveGroupUncertaintyFloors(type)[0];
	}

	public double getGroupUncertaintyFloorB(Klein_Method_Threshold_Type type) {
		return this.getActiveGroupUncertaintyFloors(type)[1];
	}

	public double getGroupUncertaintyFloorC(Klein_Method_Threshold_Type type) {
		return this.getActiveGroupUncertaintyFloors(type)[2];
	}

	public void setGroupUncertaintyFloorA(Klein_Method_Threshold_Type type, double value) {
		this.getActiveGroupUncertaintyFloors(type)[0] = value;
	}

	public void setGroupUncertaintyFloorB(Klein_Method_Threshold_Type type, double value) {
		this.getActiveGroupUncertaintyFloors(type)[1] = value;
	}

	public void setGroupUncertaintyFloorC(Klein_Method_Threshold_Type type, double value) {
		this.getActiveGroupUncertaintyFloors(type)[2] = value;
	}

	/**
	 * Wie getActiveGroupUncertaintyFloors(), aber fuer eine EXPLIZIT
	 * angegebene Geraeteklasse statt der aktuell gewaehlten - fuer
	 * Klein_Method_Parameter_Store gedacht, der BEIDE Saetze (Elektronisch
	 * UND Historisch) unabhaengig von der aktuellen Auswahl speichern/laden
	 * muss. KOMBINIERT ist hier nicht sinnvoll (es hat keinen eigenen Satz),
	 * wird wie ELEKTRONISCH behandelt.
	 */
	public double[] getGroupUncertaintyFloors(Klein_Method_Threshold_Type type, Klein_Method_Device_Class deviceClass) {
		Map<Klein_Method_Threshold_Type, double[]> map = deviceClass == Klein_Method_Device_Class.HISTORISCH
				? this.historicalGroupUncertaintyFloors
				: this.electronicGroupUncertaintyFloors;
		return map.get(type);
	}

	public boolean isTurboEnabled() {
		return this.turboEnabled;
	}

	public void setTurboEnabled(boolean turboEnabled) {
		this.turboEnabled = turboEnabled;
	}

	// ----------------------------------------------------------------- Sprungwerte, Debug-Fenster, Turbo-Zustand je
	// Festpunkt. ------------------------------------------------------------------

	// Sprungwerte fest - 1D 0,10 mm, 2D/3D 0,50 mm (keine Eingabefelder).

	/** Sprungwert 1D in Meter (fest 0,10 mm). */
	public double getPointJumpValue1D() {
		return Klein_Method_Default_Values.POINT_UNCERTAINTY_JUMP_VALUE_1D;
	}

	/** Sprungwert 2D/3D in Meter (fest 0,50 mm). */
	public double getPointJumpValue23D() {
		return Klein_Method_Default_Values.POINT_UNCERTAINTY_JUMP_VALUE_2D;
	}

	/** Sprungwert je Punktdimension in Meter (1D eigener Wert, 2D/3D gemeinsam). */
	public double getPointJumpValue(int dimension) {
		return dimension == 1 ? this.getPointJumpValue1D() : this.getPointJumpValue23D();
	}

	public double getPointJumpValue1DMillimeter() {
		return this.getPointJumpValue1D() * 1000.0;
	}

	public double getPointJumpValue23DMillimeter() {
		return this.getPointJumpValue23D() * 1000.0;
	}

	/**
	 * Turbo-Zustand EINES Festpunkts in Phase 2 der dynamischen AGL (je Lauf).
	 * Alle sigma-Werte in Meter, T = T_prio der Ausgleichung MIT diesem sigma.
	 */
	public static final class Klein_Method_Point_Turbo_State {
		/** letztes ausgewertetes Paar (sigma, T) - Grundlage der Vorhersage. */
		public double lastSigma = Double.NaN;
		public double lastTpost = Double.NaN;
		/** Richtung des letzten Schritts: -1 verkleinert, +1 vergroessert, 0 keiner. */
		public int lastDirection = 0;
		public boolean lastStepTurbo = false;
		/** true, wenn der Punkt bei der letzten Auswertung ausserhalb des Bands lag. */
		public boolean lastOutsideBand = false;
		/** Anzahl gezaehlter Richtungswechsel (nur von ausserhalb des Bands nach Turbo-Schritt). */
		public int directionChanges = 0;
		/** verbleibende eingefrorene Schritte. */
		public int freezeStepsLeft = 0;
		/** true = Turbo fuer diesen Punkt bis zum Ende des Laufs gesperrt. */
		public boolean locked = false;
		/** true nach einem Ruecksprung von der Untergrenze - kein Sprung mehr in diesem Lauf. */
		public boolean jumpLocked = false;
		/** aufeinanderfolgende Verkleinerungen, bei denen T_prio kaum reagiert hat (|p| klein). */
		public int insensitiveCount = 0;
		/**
		 * Klammer fuer die Intervallhalbierung: groesstes sigma mit T_prio ueber dem Band (sigma zu klein) und kleinstes
		 * sigma mit T_prio unter dem Band (sigma zu gross); NaN = noch nicht bekannt.
		 */
		public double bracketSigmaTooSmall = Double.NaN;
		public double bracketSigmaTooLarge = Double.NaN;
		/** true, wenn die Klammer ausgeschoepft ist und T_prio nahe 1 liegt - sigma ist bestimmt, der Punkt gilt als fertig. */
		public boolean bracketSettled = false;
		/** kleinstes und groesstes bisher ausgewertetes sigma mit zugehoerigem T_prio - Empfindlichkeit ueber den ganzen Weg. */
		public double minSigmaSeen = Double.NaN, tAtMinSigma = Double.NaN, maxSigmaSeen = Double.NaN, tAtMaxSigma = Double.NaN;
	}

	private final java.util.Map<Integer, Klein_Method_Point_Turbo_State> pointTurboStates =
			new java.util.concurrent.ConcurrentHashMap<Integer, Klein_Method_Point_Turbo_State>();

	public Klein_Method_Point_Turbo_State getPointTurboState(int pointId) {
		return this.pointTurboStates.computeIfAbsent(pointId, id -> new Klein_Method_Point_Turbo_State());
	}

	/** Live-Werte der dynamischen AGL (eine Zeile, null = noch keine Iteration). */
	private volatile String dynamicLiveText = null;

	public String getDynamicLiveText() {
		return this.dynamicLiveText;
	}

	public void setDynamicLiveText(String text) {
		this.dynamicLiveText = text;
	}

	/**
	 * Sprung-Zustand je Punktgruppe (Phase 1), gleiche Struktur wie bei Festpunkten (T = F). keine Vorhersage mehr in
	 * Phase 1.
	 */
	private final java.util.Map<Integer, Klein_Method_Point_Turbo_State> groupPredictionStates =
			new java.util.concurrent.ConcurrentHashMap<Integer, Klein_Method_Point_Turbo_State>();

	public Klein_Method_Point_Turbo_State getGroupPredictionState(int groupId) {
		return this.groupPredictionStates.computeIfAbsent(groupId, id -> new Klein_Method_Point_Turbo_State());
	}

	/** Stagnations-Abbruch: bester Abstand zum Band (Summe |ln Wert| der offenen Elemente) und Zaehler ohne Fortschritt. */
	private volatile double dynamicStagnationBest = Double.NaN;
	private volatile int dynamicStagnationCount = 0;
	private volatile String dynamicStagnationDetails = null;

	public void resetDynamicStagnation() {
		this.dynamicStagnationBest = Double.NaN;
		this.dynamicStagnationCount = 0;
		this.dynamicLastSigma.clear();
	}

	public double getDynamicStagnationBest() {
		return this.dynamicStagnationBest;
	}

	public void setDynamicStagnationBest(double value) {
		this.dynamicStagnationBest = value;
	}

	public int getDynamicStagnationCount() {
		return this.dynamicStagnationCount;
	}

	public void setDynamicStagnationCount(int count) {
		this.dynamicStagnationCount = count;
	}

	/** Gesetzt, wenn der Lauf wegen Stagnation endet (Liste der offenen Punkte/Gruppen), sonst null. */
	public String getDynamicStagnationDetails() {
		return this.dynamicStagnationDetails;
	}

	public void setDynamicStagnationDetails(String details) {
		this.dynamicStagnationDetails = details;
	}

	public boolean isFloorEnforcementEnabled() {
		return this.floorEnforcementEnabled;
	}

	/**
	 * schaltet die einmalige Abschluss-Kompression beim Uebergang zu PHASE:DONE ein/aus (Standard ja) - siehe
	 * Klein_Method_Functions.performFloorEnforcementCompression().
	 */
	public void setFloorEnforcementEnabled(boolean floorEnforcementEnabled) {
		this.floorEnforcementEnabled = floorEnforcementEnabled;
	}

	public boolean isCascadeDoubleRunEnabled() {
		return this.cascadeDoubleRunEnabled;
	}

	public void setCascadeDoubleRunEnabled(boolean cascadeDoubleRunEnabled) {
		this.cascadeDoubleRunEnabled = cascadeDoubleRunEnabled;
	}

	/**
	 * steuert, ob die Trichter-Eliminierung (GNSS-Basislinie mit |p_post| > GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD) die
	 * Beobachtung tatsaechlich deaktiviert ("ja") oder ausschliesslich weiter gedaempft, aber NIEMALS inaktiv
	 * geschaltet wird ("nein (empfohlen)", Standard) - siehe
	 * Klein_Method_Functions.performIndividualCalibrationStep().
	 */
	public boolean isGroupFloorAfterPass1Enabled() {
		return this.groupFloorAfterPass1Enabled;
	}

	public void setGroupFloorAfterPass1Enabled(boolean groupFloorAfterPass1Enabled) {
		this.groupFloorAfterPass1Enabled = groupFloorAfterPass1Enabled;
	}

	public boolean isGroupFloorLockActive() {
		return this.groupFloorLockActive;
	}

	public void setGroupFloorLockActive(boolean groupFloorLockActive) {
		this.groupFloorLockActive = groupFloorLockActive;
	}

	// bei "ja" verteilen die Split-Formeln (Achs Split, Lage/Hoehe-Split) die Korrektur der einfachen Anpassung nur
	// nach den Anteilen: Faktor_i = 1 + log10(F) * k * Anteil_i (k = 3 bzw. 2), statt sie mit dem Anteil zu
	// multiplizieren. dreistufig statt ja/nein. "automatisch (empfohlen)" = Lage/Hoehe-Split MIT Normierung, Achs
	// Split OHNE (erprobter Stand); "ja"/"nein" erzwingen die Einstellung fuer beide Split-Formeln
	// (Vergleichslaeufe).
	private Klein_Method_Split_Normalization_Mode splitNormalizationMode = Klein_Method_Split_Normalization_Mode.AUTO;

	public Klein_Method_Split_Normalization_Mode getSplitNormalizationMode() {
		return this.splitNormalizationMode;
	}

	public void setSplitNormalizationMode(Klein_Method_Split_Normalization_Mode mode) {
		this.splitNormalizationMode = mode == null ? Klein_Method_Split_Normalization_Mode.AUTO : mode;
	}

	/** tatsaechlich wirksame Normierung fuer die gerade gewaehlte Daempfungs-Formel. */
	public boolean isSplitNormalizationEffective() {
		switch (this.splitNormalizationMode) {
		case ON:
			return true;
		case OFF:
			return false;
		default:
			// "automatisch" = Normierung fuer BEIDE Split-Formeln.
			return this.getDampingFormulaType() != Klein_Method_Damping_Formula_Type.UR_FORMEL_V100;
		}
	}

	public static enum Klein_Method_Split_Normalization_Mode {
		AUTO("automatisch (empfohlen)"),
		ON("ja"),
		OFF("nein");

		private final String displayName;

		private Klein_Method_Split_Normalization_Mode(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return this.displayName;
		}

		@Override
		public String toString() {
			return this.displayName;
		}
	}

	// Umfang der MATHE-DEBUG-Zeilen je Basislinie. Standard "aus".
	private Klein_Method_Math_Debug_Mode mathDebugMode = Klein_Method_Math_Debug_Mode.OFF;

	public Klein_Method_Math_Debug_Mode getMathDebugMode() {
		return this.mathDebugMode;
	}

	public void setMathDebugMode(Klein_Method_Math_Debug_Mode mathDebugMode) {
		this.mathDebugMode = mathDebugMode == null ? Klein_Method_Math_Debug_Mode.OFF : mathDebugMode;
	}

	public static enum Klein_Method_Math_Debug_Mode {
		OFF("aus"),
		SAMPLE("Stichprobe (5 je Iteration)"),
		ALL("alle");

		private final String displayName;

		private Klein_Method_Math_Debug_Mode(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return this.displayName;
		}

		@Override
		public String toString() {
			return this.displayName;
		}
	}

	public boolean isGroupDampingInPhase2Enabled() {
		return this.groupDampingInPhase2Enabled;
	}

	public void setGroupDampingInPhase2Enabled(boolean groupDampingInPhase2Enabled) {
		this.groupDampingInPhase2Enabled = groupDampingInPhase2Enabled;
	}

	/** GNSS-Sitzungen zusammenfassen: je Sitzung eine mittlere Basislinie statt aller Epochen (Standard: ja, empfohlen). */
	private boolean gnssSessionMerge = true;

	public boolean isGnssSessionMerge() {
		return this.gnssSessionMerge;
	}

	public void setGnssSessionMerge(boolean gnssSessionMerge) {
		this.gnssSessionMerge = gnssSessionMerge;
	}

	/** Messarten mit eigenem Schalter "Wiederholte Messungen" (Reihenfolge der Oberflaeche). */
	public static final org.applied_geodesy.adjustment.network.ObservationType[] REPETITION_TYPES = {
			org.applied_geodesy.adjustment.network.ObservationType.DIRECTION, org.applied_geodesy.adjustment.network.ObservationType.ZENITH_ANGLE,
			org.applied_geodesy.adjustment.network.ObservationType.HORIZONTAL_DISTANCE, org.applied_geodesy.adjustment.network.ObservationType.SLOPE_DISTANCE,
			org.applied_geodesy.adjustment.network.ObservationType.LEVELING, org.applied_geodesy.adjustment.network.ObservationType.GNSS1D,
			org.applied_geodesy.adjustment.network.ObservationType.GNSS2D, org.applied_geodesy.adjustment.network.ObservationType.GNSS3D };

	private final Map<org.applied_geodesy.adjustment.network.ObservationType, Integer> repetitionModes =
			new java.util.concurrent.ConcurrentHashMap<org.applied_geodesy.adjustment.network.ObservationType, Integer>();

	/** Behandlung wiederholter Messungen der Messart (Klein_Method_Default_Values.REPETITION_MODE_*). */
	public int getRepetitionMode(org.applied_geodesy.adjustment.network.ObservationType type) {
		Integer mode = this.repetitionModes.get(type);
		return mode == null ? Klein_Method_Default_Values.REPETITION_MODE_DEFAULT : mode;
	}

	public void setRepetitionMode(org.applied_geodesy.adjustment.network.ObservationType type, int mode) {
		if (type != null && mode >= Klein_Method_Default_Values.REPETITION_MODE_OFF && mode <= Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE)
			// "regewichten" (frueher waehlbar) gilt als "zusammenfassen"
			this.repetitionModes.put(type, mode == Klein_Method_Default_Values.REPETITION_MODE_REWEIGHT ? Klein_Method_Default_Values.REPETITION_MODE_MERGE : mode);
	}

	/** Naeherungskoordinaten mit Rangfolge Nivellement > Tachymeter > GNSS (Standard ein). */
	public boolean isApproximationPriority() {
		return this.approximationPriority;
	}

	public void setApproximationPriority(boolean approximationPriority) {
		this.approximationPriority = approximationPriority;
	}

	public boolean isDeactivateGnssOutlierEnabled() {
		return this.deactivateGnssOutlierEnabled;
	}

	public void setDeactivateGnssOutlierEnabled(boolean deactivateGnssOutlierEnabled) {
		this.deactivateGnssOutlierEnabled = deactivateGnssOutlierEnabled;
	}

	public boolean isHardClampAppliedThisRun() {
		return this.hardClampAppliedThisRun;
	}

	/**
	 * wird ausschliesslich von Klein_Method_Functions.performFloorEnforcementCompression() auf true gesetzt, wenn
	 * dieser Lauf tatsaechlich mindestens eine Beobachtung per Hard-Clamp ueberschrieben hat. Steuert die neue
	 * Diagnose-Zeile "Erzwungener HSQLDB-Boden:" in Klein_Method_Tab_Builder (Sektion "Ausgelesene Werte").
	 */
	public void setHardClampAppliedThisRun(boolean hardClampAppliedThisRun) {
		this.hardClampAppliedThisRun = hardClampAppliedThisRun;
	}

	public double getBestMaxLogPost() {
		return this.bestMaxLogPost;
	}

	public int getBestMaxLogPostPass() {
		return this.bestMaxLogPostPass;
	}

	public int getBestMaxLogPostIteration() {
		return this.bestMaxLogPostIteration;
	}

	public double getCascadeTargetLogPost() {
		return this.cascadeTargetLogPost;
	}

	public void setCascadeTargetLogPost(double cascadeTargetLogPost) {
		this.cascadeTargetLogPost = cascadeTargetLogPost;
	}

	public boolean isCascadeTargetReached() {
		return this.cascadeTargetReached;
	}

	public void setCascadeTargetReached(boolean cascadeTargetReached) {
		this.cascadeTargetReached = cascadeTargetReached;
	}

	public void resetBestMaxLogPost() {
		this.bestMaxLogPost = Double.NaN;
		this.bestMaxLogPostPass = 0;
		this.bestMaxLogPostIteration = 0;
	}

	public void recordBestMaxLogPost(double maxLogPost, int pass, int iteration) {
		if (Double.isNaN(maxLogPost))
			return;
		if (Double.isNaN(this.bestMaxLogPost) || maxLogPost < this.bestMaxLogPost) {
			this.bestMaxLogPost = maxLogPost;
			this.bestMaxLogPostPass = pass;
			this.bestMaxLogPostIteration = iteration;
		}
	}

	public int getCascadePassCount() {
		return this.cascadePassCount;
	}

	/**
	 * von Klein_Method_Iteration_Task aufgerufen, unmittelbar bevor ein weiterer Kaskaden-Pass gestartet wird.
	 *
	 * @return der neue Zaehlerstand (1 = dies ist der Start von Pass 2, usw.)
	 */
	public int incrementCascadePassCount() {
		return ++this.cascadePassCount;
	}

	/**
	 * NUR beim echten Start eines neuen Automatik-Laufs aufzurufen (siehe Feldkommentar) - NICHT bei jedem einzelnen
	 * Kaskaden-Pass.
	 */
	public void resetCascadePassCount() {
		this.cascadePassCount = 0;
	}

	public int getLastFloorEnforcementChangedCount() {
		return this.lastFloorEnforcementChangedCount;
	}

	/**
	 * von Klein_Method_Functions.performFloorEnforcementCompression() am Ende JEDES Aufrufs gesetzt (siehe dort) -
	 * unabhaengig davon, ob der Wert 0 oder > 0 ist.
	 */
	public void setLastFloorEnforcementChangedCount(int lastFloorEnforcementChangedCount) {
		this.lastFloorEnforcementChangedCount = lastFloorEnforcementChangedCount;
	}

	public void registerClampedObservation(String registryKey) {
		this.clampedObservationsRegistry.add(registryKey);
	}

	public boolean isObservationClamped(String registryKey) {
		return this.clampedObservationsRegistry.contains(registryKey);
	}

	/**
	 * NUR beim echten Start eines neuen Automatik-Laufs aufzurufen (siehe Feldkommentar) - NICHT bei jedem einzelnen
	 * Kaskaden-Pass, sonst wuerde die Sperre genau in dem Moment wieder aufgehoben, in dem sie wirken soll.
	 */
	public void resetClampedObservationsRegistry() {
		this.clampedObservationsRegistry.clear();
	}

	/**
	 * von Klein_Method_Functions.performIndividualCalibrationStep() fuer JEDE GNSS-Achse (X/Y/Z) aufgerufen, die den
	 * finalen Achsen-Verarbeitungs-Zyklus erreicht - unabhaengig vom Verteilungsmodus und unabhaengig davon, ob am
	 * Ende tatsaechlich geschrieben wird. Bildet den Nenner der Diagnose-Quote "Reelle EP-Verteilungsrate:".
	 */
	public void incrementTotalGnssCalculations() {
		this.totalGnssCalculations++;
	}

	public int getTotalGnssCalculations() {
		return this.totalGnssCalculations;
	}

	/** Bildet den Zaehler der Diagnose-Quote "Reelle EP-Verteilungsrate:". */
	public void incrementProzentualSplittedCalculations() {
		this.prozentualSplittedCalculations++;
	}

	public int getProzentualSplittedCalculations() {
		return this.prozentualSplittedCalculations;
	}

	/**
	 * NUR beim echten Start eines neuen Automatik-Laufs aufzurufen (siehe Feldkommentar) - NICHT bei jedem einzelnen
	 * Kaskaden-Pass, sonst wuerde die Quote nur den letzten Pass statt der gesamten Sitzung zeigen.
	 */
	public void resetGnssPercentageDiagnosticCounters() {
		this.totalGnssCalculations = 0;
		this.prozentualSplittedCalculations = 0;
	}

	public int getDecimalPlaces() {
		return this.decimalPlaces;
	}

	/**
	 * Wird ueber Klein_Method_Format.clampDecimalPlaces() automatisch auf
	 * den erlaubten Bereich (3-6) begrenzt - kann also nie einen ungueltigen
	 * Wert annehmen, egal was uebergeben wird (z.B. aus einer fehlerhaften
	 * Properties-Datei).
	 */
	public void setDecimalPlaces(int decimalPlaces) {
		this.decimalPlaces = Klein_Method_Format.clampDecimalPlaces(decimalPlaces);
	}

	/**
	 * Voller Funktionsumfang unveraendert uebernommen (Anzeigetext + Hintergrund-UND Textfarbe je Zustand) - siehe
	 * Klassenkommentar der einzelnen Konstanten unten fuer die vollstaendige fachliche Begruendung.
	 *
	 * Referenziert von aussen (andere Klassen desselben Pakets) als
	 * "Klein_Method_Parameter.Klein_Method_Oscillation_Status" - unqualifiziert (nur
	 * "Klein_Method_Oscillation_Status") weiterhin gueltig innerhalb dieser Datei selbst (siehe
	 * getOscillationStatus()/ setOscillationStatus() oben sowie das Feld "oscillationStatus").
	 *
	 * (Auftrag "gleiche Darstellung der Farben und Texte", per Screenshot-Vergleich Waechter-Badge "Stabil" vs.
	 */
	public static enum Klein_Method_Oscillation_Status {

		/** Kein Stagnations-/Oszillationssignal aktiv - Detektor meldet "sauber". */
		STABIL("Stabil", "#2ECC71", "white"),

		/**
		 * Vorstufe: entweder laeuft aktuell ein Backoff-Fenster (Netz befindet
		 * sich bereits im erzwungenen NORMAL-Pfad und "beruhigt" sich noch),
		 * oder der globale Max-logPost ist seit dem letzten Fenster-Minimum
		 * bereits um mindestens Klein_Method_Default_Values.
		 * OSCILLATION_WARNING_THRESHOLD gestiegen, hat aber die volle
		 * Ausloese-Schwelle OSCILLATION_JUMP_THRESHOLD noch nicht erreicht.
		 */
		TEILWEISE_INSTABIL("Teilweise instabil", "#F1C40F", "black"),

		/**
		 * Voller Trigger: GENAU in der Iteration, in der isStagnating() oder isOscillating() erstmals anschlug und
		 * activateBackoff() ausgeloest hat - bewusst NUR fuer diese eine Iteration, danach faellt die Anzeige auf
		 * TEILWEISE_INSTABIL zurueck (der Zwangswechsel selbst laeuft ja bereits).
		 */
		INSTABIL("Instabil", "#E74C3C", "white"),
		;

		private final String displayName;
		private final String colorHex;
		private final String textFillCssColor;

		private Klein_Method_Oscillation_Status(String displayName, String colorHex, String textFillCssColor) {
			this.displayName = displayName;
			this.colorHex = colorHex;
			this.textFillCssColor = textFillCssColor;
		}

		public String getDisplayName() {
			return this.displayName;
		}

		public String getColorHex() {
			return this.colorHex;
		}

		public String getTextFillCssColor() {
			return this.textFillCssColor;
		}
	}

	/**
	 * das in einer eigenen Datei Klein_Method_Damping_Formula_Type.java deklarierte Enum ist ab hier RESTLOS als
	 * verschachtelte, oeffentliche, statische Struktur direkt in Klein_Method_Parameter eingebettet - exakt
	 * demselben, bereits bestehenden Muster folgend wie Klein_Method_Oscillation_Status direkt oberhalb. Welche der
	 * zwei real im Rechenkern vorhandenen Phase-2-Daempfungsformeln bei der GNSS-Individualkalibrierung im
	 * Verteilungs-modus PERCENTAGE angewendet wird (siehe Klein_Method_Functions.performIndividualCalibrationStep(),
	 * GNSS-Zweig, isPercentageMode-Block). Standard: GEDAEMPFTER_ACHS_SPLIT_V333 ("gedämpfter Achs Split").
	 *
	 * FUNDSTELLEN-HINWEIS: diese Weiche wirkt AUSSCHLIESSLICH innerhalb des GNSS-PERCENTAGE-Zweigs. Fuer alle anderen
	 * Beobachtungstypen (Richtung, Strecke, Nivellement) UND fuer GNSS im Verteilungsmodus NORMAL wird ohnehin
	 * ausnahmslos bereits UR_FORMEL_V100 verwendet (computeIndividualUncertainty(), keine Achsen-Gewichtung
	 * "Anteil_i" moeglich/vorhanden, da diese Typen keine drei gemeinsam auszuwertenden Achsen besitzen) - die
	 * Auswahl hat dort keine zusaetzliche Wirkung.
	 *
	 * FUNDSTELLEN-HINWEIS (Compiler-Diagnose): ein top-level Enum im selben Package benoetigt nach den
	 * Java-Sprachregeln KEINEN Import und keine gesonderte Aufloesung - "cannot be resolved to a type" bei einer
	 * sonst unveraenderten, korrekt benannten Datei im selben Package/Source-Ordner deutet typischerweise auf einen
	 * Eclipse-Projektzustand hin, nicht auf einen Sprachfehler. Diese Embedding-Umsetzung behebt jedes denkbare
	 * Szenario ohnehin zuverlaessig (kein zweites .java-Dateipaar mehr, das separat im Build-Path landen muesste) und
	 * wird daher wie beauftragt vollstaendig umgesetzt.
	 */
	public static enum Klein_Method_Damping_Formula_Type {
		// Anzeigetexte geaendert Empfehlungs-Reihenfolge in Klammern im Anzeigetext, Grundlage Vergleich Projekt 22
		// 000 BL vom 27.09.2026.
		UR_FORMEL_V100("einfache Anpassung (3. Empfehlung)"),
		GEDAEMPFTER_ACHS_SPLIT_V333("gedämpfter Achs Split (1. Empfehlung)"),
		// Aufteilung nach Lage (horizontal, isotrop) und Hoehe (Lotrichtung) statt nach den Achsen X/Y/Z.
		LAGE_HOEHE_SPLIT_V366("Lage/Höhe-Split (2. Empfehlung)")
		;

		private final String displayName;

		private Klein_Method_Damping_Formula_Type(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return this.displayName;
		}

		@Override
		public String toString() {
			return this.displayName;
		}
	}
}
// V1.00_RC3_Klein_Method_Parameter.java
