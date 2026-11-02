// V1.00_RC3_Klein_Method.java
package org.applied_geodesy.klein_method;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeView;

import org.applied_geodesy.adjustment.EstimationStateType;
import org.applied_geodesy.adjustment.network.NetworkAdjustment;
import org.applied_geodesy.adjustment.network.ObservationType;
import org.applied_geodesy.adjustment.network.sql.SQLAdjustmentManager;
import org.applied_geodesy.jag3d.sql.ProjectDatabaseStateChangeListener;
import org.applied_geodesy.jag3d.sql.ProjectDatabaseStateEvent;
import org.applied_geodesy.jag3d.sql.SQLManager;
import org.applied_geodesy.jag3d.ui.tree.TreeItemValue;
import org.applied_geodesy.jag3d.ui.tree.UITreeBuilder;
import org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler;
import org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface;
import org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row;
import org.applied_geodesy.klein_method.database.Klein_Method_Point_Coordinate_Row;
import org.applied_geodesy.klein_method.database.Klein_Method_Point_Ep_Row;
import org.applied_geodesy.klein_method.logging.Klein_Method_Logger;
import org.applied_geodesy.klein_method.logging.Klein_Method_Protocol;
import org.applied_geodesy.klein_method.logic.Klein_Method_Functions;
import static org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.tr;
import org.applied_geodesy.klein_method.logic.Klein_Method_Guardian;
import org.applied_geodesy.klein_method.logic.Klein_Method_Iteration_Task;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Distribution_Type;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Coordinate_Export_Format;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Data_Status;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Default_Values;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Format;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter_Store;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;
import org.applied_geodesy.klein_method.ui.Klein_Method_Tab_Builder;
import org.applied_geodesy.ui.dialog.OptionDialog;
import org.applied_geodesy.util.unit.LengthUnit;
import org.applied_geodesy.util.unit.ScaleUnit;

/**
 * Zentrale Fassade der Erweiterung "Klein_Method".
 * Diese Klasse ist die EINZIGE Stelle, die vom Hauptprogramm (JAG3D)
 * direkt angesprochen wird - siehe UITabPaneBuilder.java, case ROOT:
 *
 *     else if (tabType == TabType.KLEIN_METHOD)
 *         node = Klein_Method.getInstance().getNode();
 *
 * Alle Unterlogiken (Parameter, UI, Fachlogik, Logging) werden hier
 * gebuendelt, damit im Hauptprogramm nur diese eine Klasse bekannt sein muss.
 */
public class Klein_Method implements ProjectDatabaseStateChangeListener {
	// Teilklassen (Gliederung, siehe Ende der Klasse)
	private final Export_Part exportPart = new Export_Part();
	private final Conversion_Part conversionPart = new Conversion_Part();
	private final Approximation_Part approximationPart = new Approximation_Part();
	private final Recommendation_Part recommendationPart = new Recommendation_Part();
	private final Gross_Error_Part grossErrorPart = new Gross_Error_Part();
	private final Statistical_Test_Part statisticalTestPart = new Statistical_Test_Part();
	private final Findings_Part findingsPart = new Findings_Part();
	private final Group_Test_Part groupTestPart = new Group_Test_Part();
	private final Simulation_Part simulationPart = new Simulation_Part();
	private final Weight_Part weightPart = new Weight_Part();
	private final Mass_Part massPart = new Mass_Part();
	private final Rename_Part renamePart = new Rename_Part();
	private final Swarm_Part swarmPart = new Swarm_Part();
	private final Quasigeoid_Part quasigeoidPart = new Quasigeoid_Part();
	private final Gnss_Support_Part gnssSupportPart = new Gnss_Support_Part();
	private final Repetition_Part repetitionPart = new Repetition_Part();
	private final Height_Datum_Part heightDatumPart = new Height_Datum_Part();
	private final Workflow_Part workflowPart = new Workflow_Part();
	private final Measurement_Plan_Part measurementPlanPart = new Measurement_Plan_Part();
	private final Averaging_Guard_Part averagingGuardPart = new Averaging_Guard_Part();

	/** Ereignisse, die ins Zustandsprotokoll des Projekts kommen. */
	private static final String[] STATE_LOG_PREFIXES = { "KOORDINATENUMRECHNUNG", "PROJEKTION", "SYSTEM", "HOEHENSYSTEM: Hoehenfestpunkte", "NAEHERUNGSKOORDINATEN: ",
			"WIEDERHOLTE MESSUNGEN", "HOEHE NUR AUS 1D", "FESTPUNKT AN DER UNTERGRENZE", "HOEHE FREI", "FESTPUNKTE 0/0/0", "GROBE FEHLER BEREINIGEN", "AUFFAELLIGE BEOBACHTUNGEN", "STATISTISCHER TEST", "GRUPPENTESTS", "FEHLERSIMULATION", "GEWICHTSVERHAELTNIS", "MASSEN-BASISLINIEN", "PUNKTE UMBENANNT", "SCHLUSSANGLEICHUNG VKS", "VARIANZFAKTOR", "UNTERGRENZE FESTPUNKTE", "EINZELWERT ERGAENZT", "FESTPUNKT NICHT PRUEFBAR" };

	private static Klein_Method instance = new Klein_Method();

	private Klein_Method_Tab_Builder tabBuilder;
	private Klein_Method_Parameter parameter;
	private Klein_Method_Logger logger;
	private Klein_Method_Protocol protocol;

	// Wird NICHT dauerhaft gespeichert, startet bei jedem Klein_Method-Neustart/Projektwechsel wieder bei null.
	private Klein_Method_Adjustment_Step lastLoggedTransitionStep = null;
	private Klein_Method_Guardian guardian;
	private Klein_Method_Database_Handler databaseHandler;
	private Klein_Method_Functions functions;
	private Klein_Method_Parameter_Store settingsStore;
	private Klein_Method_Iteration_Task runningTask = null;

	/**
	 * Sicherheitsschaltung: true nur, solange die letzte JAG3D-Berechnung
	 * (F5) ohne Abbruch gueltige Ergebnisse geliefert hat. Der Klein_Method-
	 * Tab bleibt zwar immer anklickbar, ein Auswahlversuch wird aber
	 * abgefangen und abgewiesen, solange dieser Wert false ist (siehe
	 * registerTab() unten, Hook in UITabPaneBuilder.init()).
	 * Wird von aussen (NetworkAdjustmentDialog.java, 2 Stellen) gesetzt -
	 * false beim Start einer neuen Berechnung, true nur bei
	 * EstimationStateType.ERROR_FREE_ESTIMATION.
	 */
	private BooleanProperty validAdjustmentResultsAvailable = new SimpleBooleanProperty(false);

	// Absicherung gegen gleichzeitigen Zugriff auf die Netzausgleichung:
	// verwendet von (1) unserem eigenen Hintergrund-F5, (2) NetworkAdjustmentDialog.java
	// (manuelles F5/Button "Netzausgleichung starten") und (3) Klein_Method_Iteration_Task
	// (Automatik). ReentrantLock statt einfachem synchronized-Objekt, damit die
	// Automatik bei einer laufenden manuellen Ausgleichung automatisch WARTET
	// (lock()) statt abzubrechen - sobald das Lock frei wird, laeuft sie von
	// selbst weiter, ganz ohne eigene Pause/Resume-Zustandsverwaltung.
	private static final ReentrantLock ADJUSTMENT_LOCK = new ReentrantLock();

	// Sperre "grobe Fehler vermutet" und laufende Bereinigung
	private final BooleanProperty grossErrorsSuspected = new SimpleBooleanProperty(false);
	private final BooleanProperty grossErrorCleanupRunning = new SimpleBooleanProperty(false);
	private volatile String grossErrorGateText = "";

	public static ReentrantLock getAdjustmentLock() {
		return ADJUSTMENT_LOCK;
	}

	private Klein_Method() {
		this.init();
	}

	public static Klein_Method getInstance() {
		return instance;
	}

	private void init() {
		this.parameter = new Klein_Method_Parameter();
		this.logger = new Klein_Method_Logger();
		this.protocol = new Klein_Method_Protocol();
		this.guardian = new Klein_Method_Guardian();
		this.databaseHandler = new Klein_Method_Database_Handler();
		this.functions = new Klein_Method_Functions();
		this.settingsStore = new Klein_Method_Parameter_Store();
		this.settingsStore.load(this.parameter, this.logger); // dauerhafte Einstellungen vom letzten Mal laden
		Klein_Method_Functions.Klein_Method_I18N.init(this.parameter.getLanguage());
		this.logger.log("Klein_Method: Oberfl\u00E4chensprache = " + Klein_Method_Functions.Klein_Method_I18N.getLanguage()
				+ " (Einstellung " + this.parameter.getLanguage() + ", System " + Locale.getDefault().getLanguage() + ", "
				+ Klein_Method_Functions.Klein_Method_I18N.getTextCount() + " Texte). Protokoll bleibt deutsch.");
		this.tabBuilder = new Klein_Method_Tab_Builder(this);

		// Sicherheitsschaltung Teil 2: bei JEDEM Datenbank-Statuswechsel
		// (Projekt oeffnen, wechseln, schliessen) gelten evtl. vorhandene
		// Ergebnisse als ungueltig -> Tab sperren. Nutzt eine bereits
		// bestehende, oeffentliche Methode von SQLManager - keine
		// Aenderung an JAG3D-Dateien dafuer noetig.
		SQLManager.getInstance().addProjectDatabaseStateChangeListener(this);

		// Zustandsprotokoll im Projekt: Umrechnungen und Aenderungen am Datenstand (Ablauf-Assistent)
		Klein_Method_Protocol.eventListener = text -> {
			for (String prefix : STATE_LOG_PREFIXES)
				if (text.startsWith(prefix)) {
					try {
						if (this.isProjectOpen())
							this.databaseHandler.appendStateLog(text);
					} catch (Exception e) {
						this.logger.log("Klein_Method: Zustandsprotokoll nicht beschreibbar: " + e.getMessage());
					}
					return;
				}
		};

		this.logger.log("Klein_Method initialisiert.");
	}

	/**
	 * Startet die Iterationsschleife (lesen -> bewerten/neuberechnen ->
	 * schreiben -> Grenzwert pruefen -> wiederholen) im Hintergrund.
	 * Macht nichts, wenn bereits eine Iteration laeuft.
	 */
	public void startIteration() {
		if (this.isAutomaticTaskRunning()) {
			this.logger.log("Klein_Method: Iteration laeuft bereits, Start ignoriert.");
			return;
		}
		try {
			if (this.isProjectOpen())
				this.databaseHandler.storeProjectSetting(SETTING_AUTOMATIC_STARTED, "1");
		} catch (Exception e) {
			this.logger.log("Klein_Method: Merker Automatik gestartet nicht speicherbar: " + e.getMessage());
		}
		this.runningTask = new Klein_Method_Iteration_Task(this.parameter, this.databaseHandler, this.functions, this.logger, false);
		Thread thread = new Thread(this.runningTask, "Klein_Method_Iteration_Task");
		thread.setDaemon(true);
		thread.start();
	}

	private volatile boolean epStepRunning = false;

	/**
	 * BEWERTUNGSMETHODE EP - Einzelschritt: kein Bezug mehr zu Klein_Method_Iteration_Task/Phase1+2 - stattdessen
	 * frisches F5, dann GENAU EINE Beobachtung korrigieren (die mit dem groessten aktuellen EP-Wert, siehe
	 * Klein_Method_Functions.performEpSingleStep()), KEINE Gruppenunsicherheits-Anpassung.
	 */
	public void runEpSingleStep() {
		this.lastEpSingleStepHint = null;
		if (this.epStepRunning) {
			this.logger.log("Klein_Method: EP-Einzelschritt laeuft bereits, ignoriert.");
			return;
		}
		this.epStepRunning = true;

		Thread thread = new Thread(() -> {
			ADJUSTMENT_LOCK.lock();
			try {
				EstimationStateType result = this.runHeadlessAdjustmentSynchronously();
				if (result != EstimationStateType.ERROR_FREE_ESTIMATION) {
					this.logger.log("Klein_Method: EP-Einzelschritt - Netzausgleichung nicht fehlerfrei (" + result + ").");
					this.handleFailedAdjustment(result);
					return;
				}

				List<String> adjustmentDetails = new ArrayList<String>();
				String infoMessage = this.functions.performEpSingleStep(this.parameter, this.databaseHandler, adjustmentDetails);

				if (this.parameter.isIndividualUncertaintyCeilingExceeded()) {
					this.exitAndLockKleinMethodTab("Obergrenze erreicht (EP-Einzelschritt)",
							"Die Obergrenze der individuellen Messunsicherheit wurde bei der EP-Einzelschritt-Korrektur erreicht/"
							+ "\u00FCberschritten.\n\nKlein_Method wird deshalb verlassen und gesperrt. Bitte pr\u00FCfen Sie die betroffene "
							+ "Beobachtung manuell, danach kann die Sperre \u00FCber \u201ESperre aufheben\u201C wieder gel\u00F6st werden.");
					return;
				}

				this.databaseHandler.read(this.parameter);
				Platform.runLater(() -> this.tabBuilder.refreshDataStatus());

				int currentIterationCount = this.parameter.incrementIterationCount();

				double currentMaxLogPost = this.functions.getMaxLogPostValue(this.parameter);
				double currentMaxEp = this.functions.getMaxEpValue(this.parameter);
				Platform.runLater(() -> this.refreshLiveModeFields(currentMaxLogPost, currentMaxEp));

				String adjustmentStepLabel = this.getCurrentAdjustmentStep().getShortDisplayName();
				String guardianStatusLabel = !this.guardian.isEnabled() ? "AUS"
						: !this.guardian.hasReferenceMeasurement() ? "Referenz folgt"
						: this.guardian.getLastStatus().getDisplayName();

				StringBuilder thresholdsText = new StringBuilder();
				for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.EP)) {
					if (thresholdsText.length() > 0)
						thresholdsText.append(" | ");
					thresholdsText.append(Klein_Method_Format.format(this.parameter.getThresholdValue(type), type.getUnit(), this.parameter.getDecimalPlaces()));
				}

				StringBuilder additionalParametersText = new StringBuilder();
				for (Klein_Method_Threshold_Type distanceType : new Klein_Method_Threshold_Type[] {
						Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST, Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST }) {
					double[] raw = this.databaseHandler.readAdditionalParameters(distanceType);
					boolean hasOffset = !Double.isNaN(raw[0]);
					boolean hasScale = !Double.isNaN(raw[1]);
					if (!hasOffset && !hasScale)
						continue;

					String typeName = distanceType.getDisplayName().replace(" logPost", "");
					List<String> parts = new ArrayList<String>();
					if (hasOffset)
						parts.add("Nullpunktabweichung a: " + Klein_Method_Format.format(LengthUnit.MILLIMETER.fromMeter(raw[0]), "mm", this.parameter.getDecimalPlaces()));
					if (hasScale)
						parts.add("Ma\u00DFstab m: " + Klein_Method_Format.format(ScaleUnit.PARTS_PER_MILLION_WRT_ONE.fromUnitless(raw[1]), "ppm", this.parameter.getDecimalPlaces()));

					if (additionalParametersText.length() > 0)
						additionalParametersText.append(" | ");
					additionalParametersText.append(typeName).append(" ").append(String.join(", ", parts));
				}

				if (infoMessage != null) {
					adjustmentDetails.add("HINWEIS EP-Einzelschritt: " + infoMessage);
					this.logger.log("Klein_Method: EP-Einzelschritt - Hinweis: " + infoMessage);
					this.lastEpSingleStepHint = infoMessage;
				}

				Map<String, Integer> processedCounts = new LinkedHashMap<String, Integer>();
				if (!adjustmentDetails.isEmpty())
					processedCounts.put(this.parameter.getEvaluationMethod().getDisplayName(), 1);

				this.protocol.logStep(currentIterationCount, adjustmentStepLabel, this.parameter.getProcessPhase().name(),
						currentMaxLogPost, currentMaxEp, "Manuell", this.parameter.getEvaluationMethod().getDisplayName(),
						guardianStatusLabel, thresholdsText.toString(), adjustmentDetails, processedCounts, additionalParametersText.toString());

				this.acceptOwnChangesIntoChecksum("EP-Einzelschritt");
			}
			catch (Exception e) {
				this.logger.log("Klein_Method: Fehler im EP-Einzelschritt: " + e.getMessage());
			}
			finally {
				ADJUSTMENT_LOCK.unlock();
				this.epStepRunning = false;
				Platform.runLater(() -> {
					if (this.tabBuilder != null)
						this.tabBuilder.onEpSingleStepFinished();
				});
			}
		}, "Klein_Method_EP_Single_Step");
		thread.setDaemon(true);
		thread.start();
	}

	// letzter Hinweis des EP-Einzelschritts (statt Meldungsfenster) - wird von
	// Klein_Method_Tab_Builder.onEpSingleStepFinished() in der Statuszeile gezeigt.
	private volatile String lastEpSingleStepHint = null;

	public String getLastEpSingleStepHint() {
		return this.lastEpSingleStepHint;
	}

	private volatile boolean coordinateExportRunning = false;
	private volatile boolean pointCandidateReportRunning = false;

	public void exportCoordinates() {
		this.exportPart.exportCoordinates();
	}

	/** Koordinatenart des Projekts, gewaehltes (bzw. wirksames) Zielsystem und P0. */
	/**
	 * Hoehenauswahl beim Export (ellipsoidisch / Normalhoehe) moeglich: Projekthoehen ellipsoidisch (lokal um P0 oder
	 * geozentrisch) und Ziel mit Lage und Hoehe (geographisch, UTM, Definition). Sonst wird die Hoehe des Zielsystems geschrieben.
	 */
	public static boolean heightChoiceApplies(Klein_Method_Functions.Klein_Method_Coordinate_Export.Source source,
			Klein_Method_Functions.Klein_Method_Coordinate_Export.Target target) {
		boolean ellipsoidalSource = source == Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.LOCAL_P0
				|| source == Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.ECEF;
		return ellipsoidalSource && (target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.GEOGRAPHIC
				|| target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.UTM32 || target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.UTM33
				|| target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.DEFINITION);
	}

	public static final class Export_Plan {
		public Klein_Method_Functions.Klein_Method_Coordinate_Export.Source source = Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.OTHER;
		public Klein_Method_Functions.Klein_Method_Coordinate_Export.Target target = Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT;
		public Klein_Method_Functions.Klein_Method_Coordinate_Export.Option option = new Klein_Method_Functions.Klein_Method_Coordinate_Export.Option(Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT, null);
		public List<Klein_Method_Functions.Klein_Method_Coordinate_Export.Option> available = new ArrayList<Klein_Method_Functions.Klein_Method_Coordinate_Export.Option>();
		/** zulaessige Definitionen des Projektionsordners (reine Umformung oder NTv2-Gitter). */
		public List<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition> definitions = new ArrayList<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition>();
		public Klein_Method_Functions.Klein_Method_Local_System.Parameters projectLocal, targetLocal;
		public boolean targetLocalFromProject;
		/** kein P0 im Projekt gespeichert - Grundwerte angenommen (wie bei der P0-Verlegung). */
		public boolean projectLocalAssumed;
	}

	public Export_Plan coordinateExportPlan(List<Klein_Method_Point_Coordinate_Row> rows) throws SQLException {
		return this.exportPart.coordinateExportPlan(rows);
	}

	/** Ergebnis eines Gitter-Imports - je Gitterdatei die Systeme, dazu die automatische Pruefung. */
	public static final class Grid_Import_Result {
		public final Map<String, List<String>> systems = new LinkedHashMap<String, List<String>>();
		public final List<String> checks = new ArrayList<String>();
	}

	public Grid_Import_Result importGridFile(java.io.File source, boolean copy) throws java.io.IOException {
		return this.exportPart.importGridFile(source, copy);
	}

	public Export_Plan readCoordinateExportPlan() {
		return this.exportPart.readCoordinateExportPlan();
	}

	/**
	 * Kappt die Anzeige auf maximal MAX_DISPLAYED_CORRECTIONS Zeilen - die VOLLSTAENDIGE Liste steht trotzdem immer
	 * in der Log-Datei Klein_Method.log (siehe Aufrufer).
	 */
	private static final int MAX_DISPLAYED_CORRECTIONS = 15;

	private String buildCappedCorrectionText(List<String> corrections) {
		if (corrections.size() <= MAX_DISPLAYED_CORRECTIONS)
			return String.join("\n", corrections);

		String shown = String.join("\n", corrections.subList(0, MAX_DISPLAYED_CORRECTIONS));
		int hidden = corrections.size() - MAX_DISPLAYED_CORRECTIONS;
		return shown + "\n\u2026 und " + hidden + " weitere (vollst\u00E4ndige Liste in der Log-Datei Klein_Method.log).";
	}

	public void generatePointCandidateReport() {
		this.recommendationPart.generatePointCandidateReport();
	}

	public void promoteStablePointsToPlannedFixedPoints() {
		this.recommendationPart.promoteStablePointsToPlannedFixedPoints();
	}

	/**
	 * P0 verlegen: 1D-Punkte, deren Hoehe hoechstens so weit von der Additionskonstante "Oben" abweicht, gelten als Hoehen im
	 * lokalen System und werden mit umgerechnet [m] (NHN- oder Gebrauchshoehen liegen weit darunter und bleiben unveraendert).
	 */
	static final double LOCAL_1D_HEIGHT_WINDOW = 500.0;
	/** zuletzt fuer den Export mit Normalhoehen verwendetes Quasigeoidmodell (Name und Fassung). */
	private static final String SETTING_QUASIGEOID_USED = "QUASIGEOID_USED";
	/**
	 * Umrechnung der Hoehenfestpunkte NHN -> ellipsoidisch: "Modell|id:N;id:N;..." (verwendete Hoehenanomalie je Punkt, fuer
	 * die Ruecknahme h -> H = h - N, auch nach einem spaeteren Verlegen von P0); leer = nicht umgerechnet.
	 */
	private static final String SETTING_HEIGHT_CONVERSION = "HEIGHT_CONVERSION";
	/** Punkte, fuer die das Deaktivieren auffaelliger GNSS-Basislinien schon angeboten wurde (";A;B;"). */
	private static final String SETTING_GNSS_DEACTIVATION_OFFERED = "GNSS_DEACTIVATION_OFFERED";
	/** Punkte, deren GNSS-Basislinien in der Vorausgleichung ueberwiegend verworfen wurden (";A;B;"). */
	private static final String SETTING_GNSS_SUSPECT = "GNSS_SUSPECT";
	/** Rueckfrage "GNSS-Sitzungen zusammenfassen" abgelehnt fuer diese Anzahl Sitzungen (erst bei neuen Sitzungen erneut). */
	/** Rueckfrage "wiederholte Messungen behandeln" abgelehnt fuer diesen Stand (Signatur je Messart). */
	private static final String SETTING_REPETITION_DECLINED = "REPETITION_DECLINED";
	private static final String SETTING_MASS_CONFIRMED = "MASS_CONFIRMED";
	/** Projekt-Einstellung: die Automatik wurde in diesem Projekt schon gestartet (Kalibrierung liegt auf den Beobachtungen). */
	private static final String SETTING_AUTOMATIC_STARTED = "AUTOMATIC_STARTED";
	/** zuletzt gemeldetes Ergebnis der GNSS-Stuetzung (Schluessel) und gelbe/rote Punkte fuer Export und Karte ("Name:Stufe;..."). */
	private static final String SETTING_GNSS_SUPPORT_SHOWN = "GNSS_SUPPORT_SHOWN";
	private static final String SETTING_GNSS_SUPPORT_LEVELS = "GNSS_SUPPORT_LEVELS";
	/** gemeldetes Ergebnis der Hoehensystem-Pruefung (je Projekt nur einmal je Ergebnis). */
	private static final String SETTING_HEIGHT_SYSTEM_SHOWN = "HEIGHT_SYSTEM_SHOWN";

	/** Quasigeoidmodell importieren (Datei oder Zip); name = null -> Name aus der Datei. */
	public Klein_Method_Functions.Klein_Method_Quasigeoid.Import_Result importQuasigeoid(java.io.File source, String name) throws java.io.IOException {
		return this.quasigeoidPart.importQuasigeoid(source, name, true);
	}

	/** wie importQuasigeoid; replace = false -> Exists_Exception bei vorhandenem Modell gleichen Namens. */
	public Klein_Method_Functions.Klein_Method_Quasigeoid.Import_Result importQuasigeoid(java.io.File source, String name, boolean replace) throws java.io.IOException {
		return this.quasigeoidPart.importQuasigeoid(source, name, replace);
	}

	/** Quasigeoidmodell aktiv setzen (null = keins) - mit Log und Protokollzeile. */
	public void setActiveQuasigeoid(String name) throws java.io.IOException {
		Klein_Method_Functions.Klein_Method_Quasigeoid.setActive(name);
		this.logger.log("Klein_Method: aktives Quasigeoidmodell " + (name == null ? "keins" : "'" + name + "'") + ".");
	}

	/** Quasigeoidmodell loeschen - mit Log. */
	public boolean deleteQuasigeoid(String name) throws java.io.IOException {
		boolean deleted = Klein_Method_Functions.Klein_Method_Quasigeoid.delete(name);
		this.logger.log("Klein_Method: Quasigeoidmodell '" + name + "' " + (deleted ? "gelöscht." : "nicht gefunden."));
		return deleted;
	}

	/** Hoehensystem des geoeffneten Projekts pruefen (Hintergrund). */
	public Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check checkHeightSystems() throws SQLException {
		ADJUSTMENT_LOCK.lock();
		try {
			return this.quasigeoidPart.checkHeightSystems();
		} finally {
			ADJUSTMENT_LOCK.unlock();
		}
	}

	/** GNSS-Sitzungen jetzt zusammenfassen (Knopf/Menue, mit Rueckfrage, Hintergrund). */
	public void mergeGnssSessionsOnDemand() {
		this.repetitionPart.onDemand(true);
	}

	/** Zusammenfassung der GNSS-Sitzungen zuruecknehmen (Knopf/Menue, mit Rueckfrage, Hintergrund). */
	public void undoGnssSessionMergeOnDemand() {
		this.repetitionPart.onDemand(false);
	}

	/** Ablauf-Assistent oeffnen (FX): Stand im Hintergrund pruefen, dann anzeigen. */
	public void openWorkflowAssistant() {
		this.workflowPart.open();
	}

	/**
	 * Vor dem Start der Automatik (FX): rote Schritte des Ablaufs werden ohne Rueckfrage in Log und Protokoll vermerkt.
	 * true = nicht starten, false = starten (immer).
	 */
	public boolean checkWorkflowBeforeStart() {
		return this.workflowPart.checkBeforeStart();
	}

	/** "Hoehe nur aus Hoehenfestpunkten (1D)" im Projekt eingeschaltet? (Projekteinstellung) */
	public boolean isHeightFrom1DEnabled() {
		return this.heightDatumPart.isEnabled();
	}

	/** Schalter "Hoehe nur aus Hoehenfestpunkten (1D)" (FX): pruefen, anwenden bzw. zuruecknehmen im Hintergrund; done(Ergebnis-Schalterstand). */
	public void setHeightFrom1DOnDemand(boolean on, java.util.function.Consumer<Boolean> done) {
		this.heightDatumPart.toggle(on, done);
	}

	/** Einstellung "Wiederholte Messungen" geaendert (FX): sofort anwenden anbieten (still, wenn nichts zu tun ist). */
	public void offerRepetitionsAfterSettingChange() {
		this.repetitionPart.offerAfterSettingChange();
	}

	/**
	 * Vor dem Start der Automatik (FX): weicht die angewendete Behandlung wiederholter Messungen von der Einstellung ab,
	 * Rueckfrage. true = jetzt nicht starten (abgebrochen bzw. Start folgt nach dem Anwenden ueber restart).
	 */
	public boolean checkRepetitionsBeforeStart(Runnable restart) {
		return this.repetitionPart.checkBeforeStart(restart);
	}

	/** Hoehenfestpunkte (1D, keine Neupunkte) mit dem aktiven Quasigeoid in ellipsoidische Hoehen umrechnen (h = H + N); Ergebnistext. */
	public String convertFixedHeightsToEllipsoidal() throws SQLException {
		ADJUSTMENT_LOCK.lock();
		try {
			return this.quasigeoidPart.convertFixedHeights(true);
		} finally {
			ADJUSTMENT_LOCK.unlock();
		}
	}

	/** Umrechnung der Hoehenfestpunkte zuruecknehmen (H = h - N mit dem damals verwendeten N); Ergebnistext. */
	public String restoreFixedHeights() throws SQLException {
		ADJUSTMENT_LOCK.lock();
		try {
			return this.quasigeoidPart.convertFixedHeights(false);
		} finally {
			ADJUSTMENT_LOCK.unlock();
		}
	}

	/** true, wenn die Hoehenfestpunkte des geoeffneten Projekts umgerechnet sind (Ruecknahme moeglich). */
	public boolean isFixedHeightConversionStored() {
		try {
			String s = this.isProjectOpen() ? this.databaseHandler.readProjectSetting(SETTING_HEIGHT_CONVERSION) : null;
			return s != null && !s.isEmpty();
		} catch (SQLException e) {
			return false;
		}
	}

	/** Anzeigetext der Hoehensystem-Pruefung (always = auch bei "passt"/"nicht pruefbar"). */
	public String describeHeightCheck(Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check check, boolean always) {
		return this.quasigeoidPart.describe(check, always);
	}

	// --------------- Tab waehrend der Initialisierung sperren ----------------

	private int initialisationLocks = 0; // nur im FX-Thread veraendert

	/** Inhalt des Klein_Method-Tabs sperren (alle Knoepfe/Felder), Aufruf aus beliebigem Thread. */
	private void beginInitialisationLock() {
		Runnable lock = () -> {
			this.initialisationLocks++;
			if (this.kleinMethodTab != null && this.kleinMethodTab.getContent() != null)
				this.kleinMethodTab.getContent().setDisable(true);
		};
		if (Platform.isFxApplicationThread())
			lock.run();
		else
			Platform.runLater(lock);
	}

	private int initialisationReleasesPending = 0; // nur im FX-Thread veraendert

	/** Ende einer Initialisierung (FX-Thread): Freigabe vormerken und versuchen. */
	private void endInitialisationLock() {
		this.initialisationReleasesPending++;
		this.tryReleaseInitialisationLock();
	}

	/** Sperre aufheben, sobald keine Meldung (Dialog) mehr offen ist; sonst nach dem Schliessen erneut pruefen. FX-Thread. */
	private void tryReleaseInitialisationLock() {
		boolean dialogOpen = false;
		for (javafx.stage.Window window : new ArrayList<javafx.stage.Window>(javafx.stage.Window.getWindows())) {
			if (!window.isShowing() || window.getScene() == null || !(window.getScene().getRoot() instanceof javafx.scene.control.DialogPane))
				continue;
			dialogOpen = true;
			window.showingProperty().addListener(new javafx.beans.value.ChangeListener<Boolean>() {
				@Override
				public void changed(javafx.beans.value.ObservableValue<? extends Boolean> observable, Boolean wasShowing, Boolean showing) {
					if (!showing) {
						observable.removeListener(this);
						Platform.runLater(Klein_Method.this::tryReleaseInitialisationLock);
					}
				}
			});
		}
		if (dialogOpen || this.initialisationReleasesPending == 0)
			return;
		this.initialisationLocks = Math.max(0, this.initialisationLocks - this.initialisationReleasesPending);
		this.initialisationReleasesPending = 0;
		if (this.initialisationLocks == 0 && this.kleinMethodTab != null && this.kleinMethodTab.getContent() != null)
			this.kleinMethodTab.getContent().setDisable(false);
	}

	// --------------- Laufbericht automatisch teilen ----------------

	/** Entscheidung "automatisch teilen" - gilt, solange JAG3D geoeffnet ist (null = noch nicht gefragt). */
	private static volatile Boolean autoShareDecision = null;

	// --------------- Koordinatenumrechnung WGS84 (geozentrisch) <-> lokal (ENh) ----------------

	/** Projekteinstellung: Parameter der Projekt-Umrechnung ("B;L;h;Ost;Nord;Oben;Stellen"), leer = nicht umgerechnet. */
	public static final String SETTING_LOCAL_SYSTEM = "LOCAL_SYSTEM";
	/** "Hoehe nur aus Hoehenfestpunkten (1D)" (dyn./fixe AGL): "1" = ein. */
	static final String SETTING_HEIGHT_FROM_1D = "HEIGHT_FROM_1D";
	/** Projekteinstellung: automatische Umrechnung abgelehnt. */
	private static final String SETTING_LOCAL_DECLINED = "LOCAL_SYSTEM_DECLINED";

	public void openCoordinateConversion() {
		this.conversionPart.openCoordinateConversion();
	}

	/** Ergebnis der Pruefung eines Projekts fuer die Umrechnung. */
	public static final class Local_Conversion_Plan {
		public Klein_Method_Functions.Klein_Method_Local_System.Parameters parameters;
		public boolean defaultsUsed;
		/** true = P0 aus einer frueheren Umrechnung dieses Projekts (weitere geozentrische Punkte nachimportiert). */
		public boolean storedUsed;
		/** gespeicherter, aber veralteter P0 (kein Punkt mehr in seinem System) - ignoriert; sonst null. */
		public String staleP0;
		public double distance = Double.NaN, extent = 0.0;
		public final Map<Integer, double[]> points = new LinkedHashMap<Integer, double[]>(), baselines = new LinkedHashMap<Integer, double[]>();
		public int otherPoints = 0, otherBaselines = 0, zenithLeveling = 0;
		/** 3D-Basislinien, die schon lokal sind (bleiben unveraendert) bzw. nur angenommen eingeordnet wurden. */
		public int localBaselines = 0, unclearBaselines = 0;
		/** Punkte mit verdaechtiger ellipsoidischer Hoehe (id -> h) und Median der GNSS-Punkte. */
		public final Map<Integer, Double> heightSuspects = new LinkedHashMap<Integer, Double>();
		public final List<String> heightSuspectNames = new ArrayList<String>();
		public double referenceHeight = Double.NaN;
		/** Neupunkte im Schwerpunkt P0 bzw. wegen grossen Abstands zum Median nicht mitgezaehlt. */
		public int centroidPoints = 0, centroidDropped = 0;
		public String notPossible = null;
		/** 1D-Punkte, die mit umgerechnet werden: id -> Quelle der Lage (0 UTM, 1 Gauss-Krueger, 2 geographisch, 3 unbekannt = P0). */
		public final Map<Integer, Integer> oneDimensional = new LinkedHashMap<Integer, Integer>();
		public final List<String> oneDimensionalUnknownNames = new ArrayList<String>();
	}

	/**
	 * eigenes Hauptmenue "Klein_Method" in der JAG3D-Menueleiste (vor "Hilfe"), ohne Aenderung an JAG3D-Dateien.
	 * Laeuft nach dem Aufbau des Hauptfensters (Platform.runLater aus registerTab). Die Eintraege bleiben auch ohne
	 * geoeffnetes Projekt anwaehlbar (wie GeoTra).
	 */
	private void installMenu() {
		try {
			javafx.scene.control.MenuBar bar = org.applied_geodesy.jag3d.ui.menu.UIMenuBuilder.getInstance().getMenuBar();
			if (bar == null)
				return;
			for (javafx.scene.control.Menu m : bar.getMenus())
				if (MENU_ID.equals(m.getId()))
					return;
			javafx.scene.control.Menu menu = new javafx.scene.control.Menu("Klein_Method");
			menu.setMnemonicParsing(false);
			menu.setId(MENU_ID);
			javafx.scene.control.MenuItem conversion = new javafx.scene.control.MenuItem(tr("Koordinatenumrechnung WGS84 ↔ lokal (ENh) …"));
			conversion.setMnemonicParsing(false);
			// JAG3D sperrt ohne geoeffnetes Projekt alle Menueeintraege ausser bestimmten Modulen (MenuItemType nicht oeffentlich) -
			// der Eintrag bleibt trotzdem anwaehlbar, auch vor dem Import
			conversion.disableProperty().addListener((obs, was, now) -> {
				if (now)
					conversion.setDisable(false);
			});
			conversion.setOnAction(event -> this.openCoordinateConversion());
			// Naeherungskoordinaten im Vorfeld (braucht ein Projekt - JAG3D sperrt die Eintraege ohne Projekt selbst)
			javafx.scene.control.MenuItem approximation = new javafx.scene.control.MenuItem(tr("Näherungskoordinaten berechnen und prüfen …"));
			approximation.setMnemonicParsing(false);
			approximation.setOnAction(event -> this.checkApproximationsFromMenu());
			javafx.scene.control.MenuItem approximationUndo = new javafx.scene.control.MenuItem(tr("Letzte Übernahme der Näherungskoordinaten zurücknehmen …"));
			approximationUndo.setMnemonicParsing(false);
			approximationUndo.setOnAction(event -> this.restoreApproximationsOnDemand());
			javafx.scene.control.MenuItem gnssSupport = new javafx.scene.control.MenuItem(tr("GNSS-Stützung am Netzrand prüfen (Empfindlichkeit) …"));
			gnssSupport.setMnemonicParsing(false);
			gnssSupport.setOnAction(event -> this.gnssSupportPart.sensitivityFromMenu());
			javafx.scene.control.MenuItem precheck = new javafx.scene.control.MenuItem(tr("Vorprüfung grober Fehler (Schleifenschlüsse, Doppelmessungen) …"));
			precheck.setMnemonicParsing(false);
			precheck.setOnAction(event -> this.runGrossErrorPrecheckFromMenu());
			javafx.scene.control.MenuItem statisticalTest = new javafx.scene.control.MenuItem(tr("Statistischer Test (kleine grobe Fehler) …"));
			statisticalTest.setMnemonicParsing(false);
			statisticalTest.setOnAction(event -> this.startStatisticalTest());
			javafx.scene.control.MenuItem weight = new javafx.scene.control.MenuItem(tr("Gewichtsverhältnis der Messarten …"));
			weight.setMnemonicParsing(false);
			weight.setOnAction(event -> this.openWeightBalance());
			javafx.scene.control.MenuItem rename = new javafx.scene.control.MenuItem(tr("Punkte umbenennen …"));
			rename.setMnemonicParsing(false);
			rename.setOnAction(event -> this.openRenameWindow());
			javafx.scene.control.MenuItem workflow = new javafx.scene.control.MenuItem(tr("Ablauf-Assistent …"));
			workflow.setMnemonicParsing(false);
			workflow.setOnAction(event -> this.openWorkflowAssistant());
			javafx.scene.control.MenuItem importReport = new javafx.scene.control.MenuItem(tr("Importbericht (Plausibilität der Daten) …"));
			importReport.setMnemonicParsing(false);
			importReport.setOnAction(event -> this.openImportReport());
			boolean noProject = !this.isProjectOpen();
			precheck.setDisable(noProject);
			statisticalTest.setDisable(noProject);
			weight.setDisable(noProject);
			rename.setDisable(noProject);
			importReport.setDisable(noProject);
			approximation.setDisable(noProject);
			approximationUndo.setDisable(noProject);
			gnssSupport.setDisable(noProject);
			javafx.scene.control.MenuItem gnssMerge = new javafx.scene.control.MenuItem(tr("Wiederholte Messungen behandeln …"));
			gnssMerge.setMnemonicParsing(false);
			gnssMerge.setOnAction(event -> this.mergeGnssSessionsOnDemand());
			javafx.scene.control.MenuItem gnssMergeUndo = new javafx.scene.control.MenuItem(tr("Behandlung wiederholter Messungen zurücknehmen …"));
			gnssMergeUndo.setMnemonicParsing(false);
			gnssMergeUndo.setOnAction(event -> this.undoGnssSessionMergeOnDemand());
			gnssMerge.setDisable(noProject);
			gnssMergeUndo.setDisable(noProject);
			javafx.scene.control.MenuItem plan = new javafx.scene.control.MenuItem(tr("Prognose und Messplanung (GNSS nachmessen/ergänzen) …"));
			plan.setMnemonicParsing(false);
			plan.setOnAction(event -> this.measurementPlanPart.openFromMenu());
			plan.setDisable(noProject);
			javafx.scene.control.MenuItem help = new javafx.scene.control.MenuItem(tr("Hilfe …"));
			help.setMnemonicParsing(false);
			help.setOnAction(event -> this.openHelp());
			this.keepEnabled(help);
			workflow.setDisable(!this.isProjectOpen());
			menu.getItems().addAll(workflow, new javafx.scene.control.SeparatorMenuItem(), conversion, this.heightCalculatorMenuItem(), new javafx.scene.control.SeparatorMenuItem(), approximation, approximationUndo, new javafx.scene.control.SeparatorMenuItem(),
					gnssMerge, gnssMergeUndo, gnssSupport, importReport, precheck, statisticalTest, weight, rename, plan, new javafx.scene.control.SeparatorMenuItem(), help);
			int index = Math.max(0, bar.getMenus().size() - 1); // vor "Hilfe"
			bar.getMenus().add(index, menu);
			this.installHelpInJag3dMenu(bar, help);
			this.averagingGuardPart.install(bar);
			this.logger.log("Klein_Method: Menue \"Klein_Method\" in der Menueleiste eingerichtet.");
		} catch (Throwable e) {
			this.logger.log("Klein_Method: Menue konnte nicht eingerichtet werden: " + e);
		}
	}

	private static final String MENU_ID = "klein-method-menu";
	private static final String HELP_ITEM_ID = "klein-method-help";

	/**
	 * JAG3D sperrt ohne geoeffnetes Projekt alle Menueeintraege ausser bestimmten Kennungen - die Hilfe soll immer
	 * erreichbar sein.
	 */
	private void keepEnabled(javafx.scene.control.MenuItem item) {
		item.disableProperty().addListener((obs, was, now) -> {
			if (now)
				item.setDisable(false);
		});
	}

	/**
	 * Hilfe zusaetzlich im JAG3D-Menue "Hilfe" (erkannt am Eintrag mit Kennung ABOUT, sonst das letzte Menue) unter
	 * Trennlinie, mit Taste F1. Ohne JAG3D-Hilfemenue liegt F1 auf dem Eintrag im Klein_Method-Menue.
	 */
	private void installHelpInJag3dMenu(javafx.scene.control.MenuBar bar, javafx.scene.control.MenuItem klein_Method_Help) {
		javafx.scene.input.KeyCodeCombination f1 = new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.F1);
		javafx.scene.control.Menu jag3dHelp = null;
		for (javafx.scene.control.Menu m : bar.getMenus()) {
			if (MENU_ID.equals(m.getId()))
				continue;
			for (javafx.scene.control.MenuItem item : m.getItems())
				if (item.getUserData() != null && "ABOUT".equals(String.valueOf(item.getUserData())))
					jag3dHelp = m;
		}
		if (jag3dHelp == null && !bar.getMenus().isEmpty() && !MENU_ID.equals(bar.getMenus().get(bar.getMenus().size() - 1).getId()))
			jag3dHelp = bar.getMenus().get(bar.getMenus().size() - 1);
		if (jag3dHelp == null) {
			klein_Method_Help.setAccelerator(f1);
			this.logger.log("Klein_Method: JAG3D-Menue Hilfe nicht gefunden - Hilfe nur im Klein_Method-Menue (F1).");
			return;
		}
		for (javafx.scene.control.MenuItem item : jag3dHelp.getItems())
			if (HELP_ITEM_ID.equals(item.getId()))
				return;
		javafx.scene.control.MenuItem help = new javafx.scene.control.MenuItem(tr("Klein_Method – Hilfe …"));
		help.setMnemonicParsing(false);
		help.setId(HELP_ITEM_ID);
		help.setAccelerator(f1);
		help.setOnAction(event -> this.openHelp());
		this.keepEnabled(help);
		jag3dHelp.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), help);
		this.logger.log("Klein_Method: Hilfe im JAG3D-Menue Hilfe eingerichtet (F1).");
	}

	/** Menueeintrag Koordinaten-/Hoehenrechner (Menue KleinMethod, auch ohne Projekt). */
	private javafx.scene.control.MenuItem heightCalculatorMenuItem() {
		javafx.scene.control.MenuItem calculator = new javafx.scene.control.MenuItem(tr("Koordinaten-/Höhenrechner (DHHN2016 ↔ ellipsoidisch) …"));
		calculator.setMnemonicParsing(false);
		calculator.setId(HELP_ITEM_ID + "-calculator");
		calculator.setOnAction(event -> this.openHeightCalculator());
		this.keepEnabled(calculator);
		return calculator;
	}

	/** Koordinaten-/Hoehenrechner oeffnen (unabhaengig vom Projekt). */
	public void openHeightCalculator() {
		Klein_Method_Tab_Builder.Klein_Method_Height_Calculator_Window.open(this);
	}

	private boolean checkLocalConversion(boolean force) {
		return this.conversionPart.checkLocalConversion(force);
	}

	public boolean isApproximationOrConversionRunning() {
		return this.conversionPart.isApproximationOrConversionRunning();
	}

	public void convertProjectOnDemand() {
		this.conversionPart.convertProjectOnDemand();
	}

	public void restoreProjectConversionOnDemand() {
		this.conversionPart.restoreProjectConversionOnDemand();
	}

	private static final String SETTING_P0_DECLINED = "LOCAL_P0_DECLINED";
	/** Wert von SETTING_P0_DECLINED: Verlegen in den Schwerpunkt der Neupunkte abgelehnt. */
	private static final String P0_DECLINED_CENTROID = "schwerpunkt";

	/** ab diesem Abstand Netzmitte-P0 (m) wird bei Zenitwinkeln/Nivellement automatisch gefragt. */
	private static final double P0_AUTO_DISTANCE = 1000.0;

	public void relocateP0OnDemand() {
		this.conversionPart.relocateP0OnDemand();
	}

	private boolean relocateP0(boolean force) {
		return this.conversionPart.relocateP0(force);
	}

	/** gespeicherte Antwort "Bezugspunkt nicht anpassen" (Wert = P0 der Projekteinstellung, fuer den abgelehnt wurde). */
	private static final String SETTING_PRINCIPAL_POINT_DECLINED = "LOCAL_PRINCIPAL_POINT_DECLINED";

	private void checkPrincipalPoint() {
		this.conversionPart.checkPrincipalPoint();
	}

	public String describeProjectConversionState() {
		return this.conversionPart.describeProjectConversionState();
	}

	// --------------- Naeherungskoordinaten der Neupunkte (freie AGL) ----------------

	private static final String SETTING_APPROXIMATION_HASH = "APPROX_CHECK_HASH";
	/** zuletzt gemeldeter Stand der GNSS-Sitzungspruefung (Meldung nur bei neuem Befund). */
	private static final String SETTING_GNSS_SESSION_SHOWN = "GNSS_SESSION_CHECK_SHOWN";
	private volatile boolean approximationCheckRunning = false;

	private boolean checkApproximationCoordinates(boolean force) {
		return this.approximationPart.checkApproximationCoordinates(force);
	}

	private boolean checkApproximationCoordinates(boolean force, boolean anyStep) {
		return this.approximationPart.checkApproximationCoordinates(force, anyStep);
	}

	private static String approximationTypeName(int type) {
		switch (type) {
		case Klein_Method_Functions.Klein_Method_Approximation.LEVELING:
			return tr("Nivellement");
		case Klein_Method_Functions.Klein_Method_Approximation.DIRECTION:
			return tr("Richtung");
		case Klein_Method_Functions.Klein_Method_Approximation.HORIZONTAL_DISTANCE:
			return tr("Horizontalstrecke");
		case Klein_Method_Functions.Klein_Method_Approximation.SLOPE_DISTANCE:
			return tr("Schrägstrecke");
		case Klein_Method_Functions.Klein_Method_Approximation.ZENITH_ANGLE:
			return tr("Zenitwinkel");
		default:
			return "GNSS";
		}
	}

	private static String metres(double value) {
		return Double.isFinite(value) ? String.format(Locale.GERMANY, "%.3f m", value) : "–";
	}

	private static String millimetres(double value) {
		return Double.isFinite(value) ? String.format(Locale.GERMANY, "%.1f mm", value) : "–";
	}

	private static String approximationTypeNameGerman(int type) {
		switch (type) {
		case Klein_Method_Functions.Klein_Method_Approximation.LEVELING: return "Nivellement";
		case Klein_Method_Functions.Klein_Method_Approximation.DIRECTION: return "Richtung";
		case Klein_Method_Functions.Klein_Method_Approximation.HORIZONTAL_DISTANCE: return "Horizontalstrecke";
		case Klein_Method_Functions.Klein_Method_Approximation.SLOPE_DISTANCE: return "Schraegstrecke";
		case Klein_Method_Functions.Klein_Method_Approximation.ZENITH_ANGLE: return "Zenitwinkel";
		default: return "GNSS";
		}
	}

	private static double approximationSortKey(Klein_Method_Functions.Klein_Method_Approximation.PointResult p) {
		if ((!p.hadPosition && p.positionDetermined) || (!p.hadHeight && p.heightDetermined))
			return 1e9;
		double a = Double.isFinite(p.dPosition) ? p.dPosition : 0.0, b = Double.isFinite(p.dHeight) ? p.dHeight : 0.0;
		return Math.max(a, b);
	}

	private void showApproximationInfo(String text) {
		this.approximationPart.showApproximationInfo(text);
	}

	private boolean approximationBusy() {
		return this.approximationPart.approximationBusy();
	}

	public void checkApproximationsOnDemand() {
		this.approximationPart.checkApproximationsOnDemand();
	}

	public void checkApproximationsFromMenu() {
		this.approximationPart.checkApproximationsFromMenu();
	}

	public boolean isProjectOpen() {
		try {
			return SQLManager.getInstance().hasDatabase() && SQLManager.getInstance().getDataBase().isOpen();
		} catch (Exception e) {
			return false;
		}
	}

	private void scheduleApproximationAfterProjectOpen() {
		this.approximationPart.scheduleApproximationAfterProjectOpen();
	}

	public void restoreApproximationsOnDemand() {
		this.approximationPart.restoreApproximationsOnDemand();
	}

	// --------------- Projektion, freie Loesung, Kongruenz der Altpunkte ----------------

	private volatile String projectionHint = null;
	private final Set<String> projectionHintShown = java.util.Collections.synchronizedSet(new HashSet<String>());

	public String getProjectionHint() {
		return this.recommendationPart.getProjectionHint();
	}

	private void checkProjection() {
		this.recommendationPart.checkProjection();
	}

	private void checkProjection(boolean showAlert) {
		this.recommendationPart.checkProjection(showAlert);
	}

	public void storeFreeSolution(Klein_Method_Adjustment_Step step) {
		this.recommendationPart.storeFreeSolution(step);
	}

	private static final String SETTING_FREE_SOLUTION_PROJECTION = "FREE_SOLUTION_PROJECTION";

	public boolean confirmBeforeDynamicRun(Klein_Method_Adjustment_Step step) {
		return this.recommendationPart.confirmBeforeDynamicRun(step);
	}

	// --------------- Kalibrierungsnachweis ----------------

	/** Gruppenunsicherheiten beim Laufstart je "TYP|Gruppe" [Arbeitseinheit]. */
	private final Map<String, double[]> calibrationStartSigmas = new java.util.concurrent.ConcurrentHashMap<String, double[]>();
	private volatile String calibrationStartTime = null;

	public void captureCalibrationStart(Klein_Method_Adjustment_Step step) {
		this.recommendationPart.captureCalibrationStart(step);
	}

	public void writeCalibrationProof(Klein_Method_Adjustment_Step step, String endReason, boolean done) {
		this.recommendationPart.writeCalibrationProof(step, endReason, done);
	}

	public void createCalibrationProofOnDemand() {
		this.recommendationPart.createCalibrationProofOnDemand();
	}

	private static String offText(double value, int decimals) {
		return value > 0.0 ? Klein_Method_Format.format(value, "", decimals) : tr("aus");
	}

	// --------------- Festpunkt-Empfehlung - Einstellungen und Rechnung ----------------

	public static final String SETTING_FPE_TACHY_GREEN = "FPE_TACHY_G", SETTING_FPE_TACHY_YELLOW = "FPE_TACHY_Y";
	public static final String SETTING_FPE_LEVEL_GREEN = "FPE_NIV_G", SETTING_FPE_LEVEL_YELLOW = "FPE_NIV_Y";
	public static final String SETTING_FPE_MAX_GAP = "FPE_MAX_GAP", SETTING_FPE_END_COUNT = "FPE_END_COUNT";
	public static final String SETTING_FPE_MID_POS_GREEN = "FPE_MID_L_G", SETTING_FPE_MID_POS_YELLOW = "FPE_MID_L_Y";
	public static final String SETTING_FPE_MID_HEIGHT_GREEN = "FPE_MID_H_G", SETTING_FPE_MID_HEIGHT_YELLOW = "FPE_MID_H_Y";
	private static final String SETTING_FPE_APPLIED = "FPE_APPLIED";
	private final Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings recommendationSettings =
			new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings();
	private volatile Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result lastRecommendation;

	public boolean isDynamicStepShown() {
		return this.recommendationPart.isDynamicStepShown();
	}

	public Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings getRecommendationSettings() {
		return this.recommendationPart.getRecommendationSettings();
	}

	public Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result getLastRecommendation() {
		return this.recommendationPart.getLastRecommendation();
	}

	public void setRecommendationValue(String key, double value) {
		this.recommendationPart.setRecommendationValue(key, value);
	}

	private void loadRecommendationSettings() {
		this.recommendationPart.loadRecommendationSettings();
	}

	/** Rechnet die Empfehlung aus der Datenbank (Sperre wird selbst genommen). */
	private volatile String lastRecommendationShape = null;

	public Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result computeFixedPointRecommendation() throws SQLException {
		return this.recommendationPart.computeFixedPointRecommendation();
	}

	public void refreshFixedPointRecommendation() {
		this.recommendationPart.refreshFixedPointRecommendation();
	}

	public String recommendationSummary(Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result r) {
		return this.recommendationPart.recommendationSummary(r);
	}

	/**
	 * Bricht eine laufende Iteration ab (falls vorhanden).
	 */
	public void stopIteration() {
		if (this.runningTask != null)
			this.runningTask.cancel();
	}

	/**
	 * Aktuell laufender Task, z.B. um sich an messageProperty() zu haengen
	 * (Fortschrittsanzeige in der Oberflaeche).
	 */
	public Klein_Method_Iteration_Task getRunningTask() {
		return this.runningTask;
	}

	/**
	 * Liefert den kompletten Inhalt (JavaFX-Node), der im Tab
	 * "Klein_Method" angezeigt wird.
	 */
	public Node getNode() {
		return this.tabBuilder.getPane();
	}

	public Klein_Method_Parameter getParameter() {
		return this.parameter;
	}

	public Klein_Method_Logger getLogger() {
		return this.logger;
	}

	public Klein_Method_Protocol getProtocol() {
		return this.protocol;
	}

	public Klein_Method_Guardian getGuardian() {
		return this.guardian;
	}

	/**
	 * Aktualisiert die Optik des Waechter-Toggle-Buttons anhand des aktuellen
	 * Klein_Method_Guardian-Zustands. Duenne Delegation an den Tab_Builder,
	 * damit auch NetworkAdjustmentDialog.java (manueller F5-Pfad) das nach
	 * einer Messung ausloesen kann, OHNE Klein_Method_Tab_Builder selbst
	 * importieren zu muessen. MUSS aus dem JavaFX-UI-Thread heraus
	 * aufgerufen werden (siehe Platform.runLater() an den Aufrufstellen).
	 */
	public void refreshGuardianAppearance() {
		if (this.tabBuilder != null)
			this.tabBuilder.updateGuardianAppearance();
	}

	/**
	 * Aktualisiert die 2 Live-Modus-Felder (aktueller Max-logPost/Max-EP)
	 * anhand derselben Werte, die auch ins Protokoll geschrieben werden
	 * (siehe Klein_Method_Iteration_Task). Duenne Delegation an den
	 * Tab_Builder, analog zu refreshGuardianAppearance(). MUSS aus dem
	 * JavaFX-UI-Thread heraus aufgerufen werden.
	 */
	public void refreshLiveModeFields(double maxLogPost, double maxEp) {
		if (this.tabBuilder != null) {
			this.tabBuilder.updateLiveModeFields(maxLogPost, maxEp);
			// Iterationszaehler und "Ausgelesene Werte" (inkl. Gruppen-Messunsicherheit) nach jeder Iteration, auch ohne Live-Modus
			this.tabBuilder.refreshRunDisplay();
		}
	}

	/**
	 * Aktualisiert das Ausgleichungsschritt-Label (Text: frei/dynamisch/fix)
	 * anhand des uebergebenen Schritts. Duenne Delegation an den Tab_Builder,
	 * analog zu refreshGuardianAppearance()/refreshLiveModeFields(). MUSS
	 * aus dem JavaFX-UI-Thread heraus aufgerufen werden.
	 */
	public void refreshAdjustmentStepLabel(Klein_Method_Adjustment_Step step) {
		if (this.tabBuilder != null)
			this.tabBuilder.applyAdjustmentStep(step);
	}

	/**
	 * Duenne Delegation an den Tab_Builder, analog zu refreshAdjustmentStepLabel(). MUSS aus dem JavaFX-UI-Thread
	 * heraus aufgerufen werden.
	 */
	public void refreshDistributionVisibility(boolean visible) {
		if (this.tabBuilder != null)
			this.tabBuilder.applyDistributionVisibility(visible);
	}

	/**
	 * die EINZIGE Schutzweiche fuer den Verteilung-Toggle (normal/prozentual) - Kontext-Vorauswahl UND
	 * Sicherheits-Deaktivierung. Duenne Delegation an den Tab_Builder, MUSS aus dem JavaFX-UI-Thread heraus
	 * aufgerufen werden - identisch zu den anderen refresh...()-Methoden.
	 *
	 * Vorauswahl: currentStep == FREE -> PERCENTAGE ("prozentual") currentStep == FIXED -> NORMAL ("normal")
	 *
	 * Deaktivierung: der Toggle ist bedienbar, sobald AKTIVE GNSS-Basislinien im Projekt vorhanden sind
	 * (hasActiveGnssBaselines) - UNABHAENGIG vom aktuellen currentStep (FREE/DYNAMIC/FIXED/UNDEFINED). Nur wenn GAR
	 * KEINE aktiven GNSS-Basislinien im Projekt vorhanden sind, bleibt er deaktiviert/ ausgegraut (kein sinnvoller
	 * Zustand ohne GNSS-Daten).
	 */
	public void refreshDistributionState(Klein_Method_Adjustment_Step currentStep, boolean hasActiveGnssBaselines) {
		this.parameter.setPointFloorGnssActive(hasActiveGnssBaselines);
		if (this.tabBuilder == null)
			return;

		// Gilt ausschliesslich die Auswahl des Anwenders bzw. der gespeicherte Wert (Standard NORMAL). Die
		// Deaktivierung ohne GNSS-Daten bleibt unveraendert.

		// Die Vorauswahl oben (PERCENTAGE bei FREE, NORMAL bei FIXED) bleibt als reine Komfort-Empfehlung bestehen,
		// erzwingt aber nichts mehr.
		boolean enabled = hasActiveGnssBaselines;
		this.tabBuilder.applyDistributionEnabled(enabled);
	}

	/**
	 * true, wenn AKTIVE Beobachtungen vom Typ GNSS-Basisline (1D/2D/3D) im Projekt vorhanden sind - egal ob allein
	 * oder in Kombination mit anderen (terrestrischen) Messdaten. Bestaetigte ObservationType-Konstanten (per
	 * Websuche im echten JAG3D-Quellcode, SQLManager.java): GNSS1D, GNSS2D, GNSS3D.
	 */
	private boolean hasActiveGnssBaselines() throws SQLException {
		// Ueber eine direkte, eigene Datenbank-Abfrage (siehe
		// Klein_Method_Database_Handler.hasActiveGnssObservations()), unabhaengig von readActiveObservationTypes().
		return this.databaseHandler.hasActiveGnssObservations();
	}

	/**
	 * Liefert einen kurzen, hilfreichen Hinweis, was der User als NAECHSTES
	 * tun sollte - nur wenn der aktuelle Schritt gerade erfolgreich
	 * abgeschlossen ist (Messdaten-Ampel gruen). Leerer String sonst.
	 */
	private String buildNextStepHint(Klein_Method_Adjustment_Step step, Klein_Method_Data_Status dataStatus) {
		// Fertig ist die Festpunktanalyse erst, wenn die Automatik in dieser Stufe regulaer mit DONE geendet hat
		// (Start-Sperre gesetzt).
		if (step == Klein_Method_Adjustment_Step.DYNAMIC)
			return this.parameter.isAutomaticRunCompleted() ? DYNAMIC_DONE_HINT : "";
		if (dataStatus != Klein_Method_Data_Status.OPTIMIZED)
			return "";

		switch (step) {
		case FREE:
			return "Die freie Ausgleichung ist fertig! N\u00E4chster Schritt: verschiebe die Datumsgebenden Festpunkte zu den "
					+ "Beweglichen Anschlusspunkten (Festpunktanalyse / dynamische Ausgleichung).";
		case FIXED:
			return "Die fixe Ausgleichung ist fertig! Alle 3 Ausgleichungsschritte sind abgeschlossen.";
		default:
			return "";
		}
	}

	private static final String DYNAMIC_DONE_HINT = "Die Festpunktanalyse (dynamische Ausgleichung) ist fertig! N\u00E4chster Schritt: "
			+ "verschiebe die Punkte zu den Varianzfreien Festpunkten (fixe Ausgleichung).";

	/**
	 * Aktualisiert das Naechster-Schritt-Hinweis-Label im Tab. Duenne
	 * Delegation an den Tab_Builder, analog zu refreshAdjustmentStepLabel().
	 * MUSS aus dem JavaFX-UI-Thread heraus aufgerufen werden.
	 */
	public void refreshNextStepHint(String hintText) {
		if (this.tabBuilder != null)
			this.tabBuilder.applyNextStepHint(hintText);
	}

	public Klein_Method_Database_Handler getDatabaseHandler() {
		return this.databaseHandler;
	}

	public Klein_Method_Functions getFunctions() {
		return this.functions;
	}

	/**
	 * Liest frisch aus der Datenbank und liefert den aktuellen Ampel-Status
	 * der Messdaten (siehe Klein_Method_Data_Status). FUER SPAETERE MODULE
	 * gedacht (z.B. "Festpunktanalyse"), die vor dem eigenen Start pruefen
	 * sollen, ob Klein_Method fertig ist:
	 *
	 *   if (Klein_Method.getInstance().getCurrentDataStatus() == Klein_Method_Data_Status.OPTIMIZED) { ... }
	 *
	 * Synchron (blockierend) - NICHT aus dem JavaFX-UI-Thread heraus
	 * aufrufen, da die Datenbankabfrage etwas dauern kann (siehe
	 * Klein_Method_Tab_Builder.refreshDataStatus() fuer die asynchrone
	 * Variante, die von der Oberflaeche selbst genutzt wird).
	 */
	public Klein_Method_Data_Status getCurrentDataStatus() throws SQLException {
		this.databaseHandler.read(this.parameter);
		return this.functions.determineDataStatus(this.parameter);
	}

	/**
	 * Erkennt anhand der im Projekt AKTIVEN Punktkategorien (PointType),
	 * welcher der 3 Ausgleichungsschritte gerade aktiv ist (siehe
	 * Klein_Method_Adjustment_Step). Prioritaet (vom User bestaetigt):
	 * REFERENCE_POINT (Varianzfreie Festpunkte) > STOCHASTIC_POINT (Bewegliche
	 * Anschlusspunkte) > DATUM_POINT (Datumsgebende Neupunkte).
	 *
	 * Synchron (blockierend) - NICHT aus dem JavaFX-UI-Thread heraus aufrufen.
	 */
	/**
	 * nach einer regulaer fertigen Automatik (Phase DONE) die aktuelle Stufe (frei/dynamisch/ fix) in der
	 * Projektdatenbank merken und "Klein_Method starten" sperren.
	 */
	public void markAutomaticRunCompleted() {
		Klein_Method_Adjustment_Step step = null;
		try {
			step = this.getCurrentAdjustmentStep();
			this.databaseHandler.storeCompletedAutomaticStep(step);
		} catch (SQLException e) {
			this.logger.log("Klein_Method: Konnte Stufe der fertigen Automatik nicht speichern: " + e.getMessage());
		}
		this.parameter.setAutomaticRunCompleted(true);
		final Klein_Method_Adjustment_Step doneStep = step;
		Runnable finish = () -> {
			// Vergleichsdatei der Behandlung wiederholter Messungen (Regewichten/Zusammenfassen)
			this.repetitionPart.writeComparisonFileAsync();
			// Hinweis der dynamischen AGL sofort am Laufende zeigen (JavaFX-Thread)
			if (doneStep == Klein_Method_Adjustment_Step.DYNAMIC)
				this.refreshNextStepHint(DYNAMIC_DONE_HINT);
		};
		this.logger.log("Klein_Method: Automatik fertig (DONE) in Stufe " + (step == null ? "unbekannt" : step.getShortDisplayName())
				+ " - Start gesperrt bis Stufenwechsel oder \"Sperre aufheben\".");
		finish.run();
	}

	/** Schlussangleichung der Genauigkeiten an das VKS-Band (Sperre beim Aufrufer); Log und Protokoll; Textzeilen der Aenderungen. */
	public List<String> runFinalScaling() {
		try {
			List<String> lines = this.weightPart.finalScaling();
			if (lines.isEmpty()) {
				this.logger.log("Klein_Method: Schlussangleichung VKS - alle Messarten im Band oder an ihrer Untergrenze, nichts geaendert.");
				return lines;
			}
			for (String line : lines)
				this.logger.log("Klein_Method: Schlussangleichung VKS " + line);
			this.protocol.logEvent("SCHLUSSANGLEICHUNG VKS: " + String.join("; ", lines));
			return lines;
		} catch (Exception e) {
			this.logger.log("Klein_Method: Schlussangleichung VKS fehlgeschlagen: " + e.getMessage());
			return new ArrayList<String>();
		}
	}

	/** hebt die Start-Sperre bewusst auf. */
	public void clearAutomaticRunCompleted() {
		try {
			this.databaseHandler.storeCompletedAutomaticStep(null);
		} catch (SQLException e) {
			this.logger.log("Klein_Method: Konnte Start-Sperre nicht aus der Projektdatenbank entfernen: " + e.getMessage());
		}
		this.parameter.setAutomaticRunCompleted(false);
		try {
			if (this.getCurrentAdjustmentStep() == Klein_Method_Adjustment_Step.DYNAMIC)
				Platform.runLater(() -> this.refreshNextStepHint(""));
		} catch (SQLException e) {
			this.logger.log("Klein_Method: Ausgleichungsstufe nicht ermittelbar: " + e.getMessage());
		}
	}

	/**
	 * wertet die Start-Sperre aus. Ist inzwischen eine andere Stufe eingestellt (z.B. freie -> dynamische oder fixe
	 * Ausgleichung), wird die Sperre aufgehoben und aus der Projektdatenbank entfernt. Bei nicht eindeutiger Stufe
	 * (UNDEFINED) bleibt eine bestehende Sperre erhalten. Aufruf beim Tab-Aktivieren und beim Projektwechsel.
	 */
	public void evaluateCompletedStepLock(String trigger) {
		boolean locked = false;
		try {
			// erst lesen, wenn das Projektschema existiert (neues Projekt: noch nicht angelegt)
			if (SQLManager.getInstance().hasDatabase() && SQLManager.getInstance().getDataBase().isOpen() && this.databaseHandler.hasProjectTables()) {
				Klein_Method_Adjustment_Step completedStep = this.databaseHandler.readCompletedAutomaticStep();
				if (completedStep != null) {
					Klein_Method_Adjustment_Step currentStep = this.getCurrentAdjustmentStep();
					if (currentStep == Klein_Method_Adjustment_Step.UNDEFINED || currentStep == completedStep) {
						locked = true;
					} else {
						this.databaseHandler.storeCompletedAutomaticStep(null);
						this.logger.log("Klein_Method: Stufenwechsel " + completedStep.getShortDisplayName() + " -> "
								+ currentStep.getShortDisplayName() + " erkannt (" + trigger + ") - Start wieder freigegeben.");
					}
				}
			}
		} catch (SQLException e) {
			this.logger.log("Klein_Method: Start-Sperre konnte nicht ausgewertet werden (" + trigger + "): " + e.getMessage());
		}
		this.parameter.setAutomaticRunCompleted(locked);
		Platform.runLater(() -> {
			if (this.tabBuilder != null)
				this.tabBuilder.refreshAutomationAvailability();
		});
	}

	public Klein_Method_Adjustment_Step getCurrentAdjustmentStep() throws SQLException {
		// An Klein_Method_Functions delegiert - identische Logik, nur einmal gepflegt.
		return this.functions.determineCurrentAdjustmentStep(this.databaseHandler);
	}

	/**
	 * Watchdog: hat sich seit dem LETZTEN eigenen Klein_Method-Aufruf etwas an den
	 * Gruppenunsicherheits-/individuellen Messunsicherheits-Werten oder den Zusatzparameter-Haken veraendert
	 * (unabhaengig vom aktuellen Ausgleichungsschritt)?
	 *
	 * PRUEFSUMMEN-MECHANISMUS: bei JEDEM Tab-Aktivieren wird eine SHA-256-Pruefsumme ueber ALLE Gruppenunsicherheits-
	 * UND individuellen Messunsicherheits-Werte sowie Zusatzparameter-Haken berechnet und in einer eigenen Tabelle
	 * DIREKT IN DER PROJEKTDATENBANK gespeichert (siehe
	 * Klein_Method_Database_Handler_Interface.storeLastVerifiedChecksum()) - reist dadurch automatisch mit dem
	 * Projekt mit UND ueberlebt auch einen Klein_Method-Neustart. Weicht die NEU berechnete Pruefsumme von der
	 * zuletzt gespeicherten ab, gilt das als unerwarteter Sprung - die Baseline wird TROTZDEM sofort auf den neuen
	 * Stand nachgezogen (einmaliger Hinweis, keine dauerhafte Sperre wegen desselben Sprungs).
	 *
	 * Bei einem Verstoss wird Klein_Method verlassen und gesperrt (siehe exitAndLockKleinMethodTab()). NUR wirksam,
	 * wenn der Klein_Method-Tab gerade aktiv ist (wie bei handleFailedAdjustment()).
	 *
	 * Bei jedem NEU erkannten Zustandswechsel (currentStep unterscheidet sich vom zuletzt protokollierten Schritt,
	 * siehe lastLoggedTransitionStep) wird zusaetzlich eine eigene Protokollzeile geschrieben: "SHA-256
	 * Pr\u00FCfsumme: Wert ... | Status <vorheriger Schritt> bestanden/ nicht bestanden" - siehe
	 * Klein_Method_Protocol.logStepTransition().
	 */
	private boolean enforceAdjustmentStepOrder(Klein_Method_Adjustment_Step currentStep, Klein_Method_Data_Status dataStatus,
			String checksumBeforeOwnCorrections) throws SQLException {
		if (currentStep == Klein_Method_Adjustment_Step.UNDEFINED) {
			// Baseline trotzdem nachziehen (eigene Korrekturen beim Tab-Aktivieren)
			this.databaseHandler.storeLastVerifiedChecksum(this.databaseHandler.computeCurrentStepChecksum());
			return true; // keine eindeutige Konfiguration -> keine Aussage moeglich, nicht blockieren
		}

		String currentChecksum = this.databaseHandler.computeCurrentStepChecksum();
		String lastVerifiedChecksum = this.databaseHandler.readLastVerifiedChecksum();

		boolean orderViolated = false;
		String violationReason = "";
		boolean passedForLog = true;

		// verglichen wird der Stand VOR Klein_Methods eigenen Korrekturen beim Tab-Aktivieren
		String comparedChecksum = checksumBeforeOwnCorrections != null ? checksumBeforeOwnCorrections : currentChecksum;
		if (lastVerifiedChecksum != null && !lastVerifiedChecksum.equals(comparedChecksum)) {
			orderViolated = true;
			passedForLog = false;
			violationReason = "Mindestens EINE der folgenden Gr\u00F6\u00DFen hat sich seit dem letzten Klein_Method-Aufruf "
					+ "unerwartet ver\u00E4ndert:\n"
					+ "\u2022 Gruppen- und/oder individuelle Messunsicherheiten\n"
					+ "\u2022 Zusatzparameter-Haken (Additionskonstante/Ma\u00DFstab berechnen)\n\n"
					+ "(Punktgruppen-Verschiebungen und Koordinatensystem-Wechsel allein l\u00F6sen das bewusst NICHT mehr aus.)";
		}

		// Baseline IMMER auf den aktuellen Stand nachziehen - auch nach einer
		// erkannten Abweichung, damit sie nicht bei jedem weiteren Tab-Besuch
		// erneut gemeldet wird (soll ein einmaliger Hinweis sein, keine
		// dauerhafte Sperre wegen desselben, bereits gesehenen Sprungs).
		this.databaseHandler.storeLastVerifiedChecksum(currentChecksum);

		// NUR beim ERSTEN Erkennen dieses Zustandswechsels protokollieren
		// (nicht bei jedem erneuten Tab-Aktivieren/Hintergrund-F5, solange
		// der Schritt unveraendert bleibt) - siehe lastLoggedTransitionStep.
		if (this.lastLoggedTransitionStep != currentStep) {
			this.protocol.logStepTransition(currentChecksum, currentStep.getShortDisplayName(), passedForLog);
			this.lastLoggedTransitionStep = currentStep;
		}

		if (orderViolated) {
			this.exitAndLockKleinMethodTab("Falsche Reihenfolge der Ausgleichung",
					"Die Ausgleichungsschritte m\u00FCssen immer in dieser Reihenfolge durchlaufen werden:\n\n"
					+ "1. Messdaten [freie Ausgleichung]\n2. Festpunktanalyse [dynamische Ausgleichung]\n3. Messdaten [fixe Ausgleichung]\n\n"
					+ violationReason + "\n\n"
					+ "Klein_Method wird deshalb verlassen und gesperrt. Bitte pr\u00FCfen Sie die verwendeten Festpunkte im Projektbaum "
					+ "sowie die Messunsicherheiten des vorherigen Schritts.");
			return false;
		}
		return true;
	}

	/**
	 * Speichert die aktuellen dauerhaften Einstellungen (Grenzwerte,
	 * Grenzwert Iterationen, Bewertungsmethode, Verteilung, Turbo) sofort
	 * in die Properties-Datei. Von Klein_Method_Tab_Builder nach jeder
	 * Aenderung aufzurufen.
	 */
	public void saveSettings() {
		this.settingsStore.save(this.parameter, this.logger);
	}

	/**
	 * true, solange die letzte JAG3D-Berechnung (F5) ohne Abbruch gueltige
	 * Ergebnisse geliefert hat. Siehe validAdjustmentResultsAvailableProperty()
	 * fuer die Bindung des Tabs.
	 */
	public boolean isValidAdjustmentResultsAvailable() {
		return this.validAdjustmentResultsAvailable.get();
	}

	/**
	 * Von NetworkAdjustmentDialog.java aus zu setzen: false beim Start
	 * einer neuen Berechnung, true nur bei erfolgreichem Abschluss
	 * (EstimationStateType.ERROR_FREE_ESTIMATION).
	 */
	public void setValidAdjustmentResultsAvailable(boolean valid) {
		this.validAdjustmentResultsAvailable.set(valid);
		this.logger.log("Klein_Method: gueltige Ergebnisse " + (valid ? "verfuegbar - Tab freigeschaltet." : "nicht (mehr) verfuegbar - Tab gesperrt."));
	}

	/**
	 * Beobachtbare Eigenschaft der Sicherheitsschaltung. Fuer die direkte
	 * Tab-Absicherung siehe registerTab() - diese Methode ist zusaetzlich
	 * fuer andere Stellen nuetzlich, die den Status beobachten wollen
	 * (z.B. UI-Elemente innerhalb des Klein_Method-Tabs selbst).
	 */
	public BooleanProperty validAdjustmentResultsAvailableProperty() {
		return this.validAdjustmentResultsAvailable;
	}

	/**
	 * Reagiert auf JEDEN Datenbank-Statuswechsel (Projekt geoeffnet,
	 * gewechselt, wird geschlossen, geschlossen) - in allen Faellen
	 * gelten evtl. vorhandene Berechnungsergebnisse als ungueltig fuer
	 * Klein_Method, der Tab wird gesperrt. Freigeschaltet wird er
	 * ausschliesslich ueber eine erfolgreiche neue Berechnung
	 * (siehe setValidAdjustmentResultsAvailable() / NetworkAdjustmentDialog.java).
	 */
	@Override
	public void projectDatabaseStateChanged(ProjectDatabaseStateEvent evt) {
		this.setValidAdjustmentResultsAvailable(false);
		this.protocol.resetForNewProject();
		this.guardian.resetForNewProject();
		this.lastLoggedTransitionStep = null;
		this.approximationsRestoredPending = false;
		this.databaseHandler.setHorizontalOnly3D(false); // "Hoehe nur aus 1D" gilt erst wieder nach dem Tab-Klick

		// bei JEDEM DB-Statuswechsel (Projekt geoeffnet, gewechselt, wird geschlossen, geschlossen - siehe
		// Klassenkommentar dieser Methode oben) das aktuell gueltige Projektverzeichnis neu ermitteln und an
		// Protokoll UND Debug-Log weiterreichen. Liefert getProjectDirectory() null (kein Projekt offen bzw.
		// Verzeichnis nicht ermittelbar, z.B. Server-/Netzwerk-DB), fallen beide automatisch auf ihre alte feste
		// Ausweichloesung zurueck.
		String projectDirectory = this.databaseHandler.getProjectDirectory();
		Klein_Method_Protocol.setProjectDirectory(projectDirectory);
		Klein_Method_Logger.setProjectDirectory(projectDirectory);

		this.parameter.resetIterationCount();

		// Ein erneut geoeffnetes, bereits kalibriertes Projekt bleibt damit gesperrt.
		this.evaluateCompletedStepLock("Projekt geoeffnet/gewechselt");

		// neues Projekt - JAG3D meldet "geoeffnet", bevor die Projekttabellen angelegt sind
		boolean projectTablesReady = evt.getEventType() != org.applied_geodesy.jag3d.sql.ProjectDatabaseStateType.OPENED
				|| this.databaseHandler.hasProjectTables();
		if (!projectTablesReady) {
			this.parameter.resetProcessPhase(Klein_Method_Adjustment_Step.UNDEFINED);
			this.logger.log("Klein_Method: Projekt wird angelegt - Ausgleichungsstufe und Pruefungen folgen nach dem Import (Tab-Klick).");
			Platform.runLater(() -> this.refreshAdjustmentStepLabel(Klein_Method_Adjustment_Step.UNDEFINED));
		} else try {
			Klein_Method_Adjustment_Step newStep = this.getCurrentAdjustmentStep();
			this.parameter.resetProcessPhase(newStep);
			this.logger.log("Klein_Method: Projekt geoeffnet/gewechselt - Ausgleichungsstufe " + newStep.getShortDisplayName()
					+ ", Ablaufphase " + this.parameter.getProcessPhase() + ".");
			Platform.runLater(() -> this.refreshAdjustmentStepLabel(newStep));
		} catch (SQLException e) {
			this.logger.log("Klein_Method: Ausgleichungsstufe nach Projektwechsel nicht ermittelbar: " + e.getMessage());
		}

		// Ein Zeitfenster statt eines Einmal-Verbrauchs: JEDE Tab-Aktivierung innerhalb von 3 Sekunden nach einem
		// Projektwechsel wird still umgeleitet, nicht nur die allererste.
		this.lastProjectSwitchTimestamp = System.currentTimeMillis();
		org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler.invalidateRepetitionCache();
		if (evt.getEventType() == org.applied_geodesy.jag3d.sql.ProjectDatabaseStateType.OPENED && projectTablesReady) {
			int orphans = org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler.prepareRepetitionTableAfterOpen();
			if (orphans > 0) {
				String line = "WIEDERHOLTE MESSUNGEN: " + orphans + " Merkeintraege ohne vorhandene Beobachtung verworfen (Projekt neu importiert oder aus Vorlage) - Pruefsummen-Stand der frueheren Daten zurueckgesetzt";
				this.logger.log("Klein_Method: " + line);
				this.protocol.logEvent(line);
			}
		}

		// Naeherungskoordinaten im Vorfeld (nach dem Oeffnen, nicht erst beim Tab-Klick)
		if (evt.getEventType() == org.applied_geodesy.jag3d.sql.ProjectDatabaseStateType.OPENED && projectTablesReady)
			this.scheduleApproximationAfterProjectOpen();
	}

	private volatile long lastProjectSwitchTimestamp = 0L;
	private static final long PROJECT_SWITCH_SUPPRESS_WINDOW_MS = 3000L;

	// ---------------------------------------------------------------------------------------------------------------
	// Ladekreis: laufende Hintergrundarbeit von Klein_Method (ohne die Automatik selbst). Jede Arbeit startet ueber
	// busyThread() und meldet sich im finally wieder ab - auch bei Fehlern. Anzeige im Reiterkopf und in der Statuszeile
	// (Klein_Method_Tab_Builder.Busy_Indicator); Zaehler und Text nur auf dem FX-Thread aendern.
	// eigene Halteklasse: wird beim ersten Zugriff angelegt - unabhaengig von der Reihenfolge der statischen Felder von
	// Klein_Method (die Instanz entsteht schon beim Laden der Klasse und baut dabei den Tab samt Ladekreis auf)
	private static final class Busy_State {
		static final java.util.Map<Thread, String> WORK = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Thread, String>());
		static final javafx.beans.property.IntegerProperty COUNT = new javafx.beans.property.SimpleIntegerProperty(0);
		static final javafx.beans.property.StringProperty TEXT = new javafx.beans.property.SimpleStringProperty("");
	}

	/**
	 * Thread fuer Hintergrundarbeit mit Anzeige des Ladekreises, solange die Arbeit laeuft. Der Ladekreis bleibt
	 * ausgeblendet, waehrend die Automatik laeuft oder ein Fenster auf eine Antwort wartet (siehe Busy_Indicator).
	 */
	public static Thread busyThread(Runnable work, String name) {
		return new Thread(() -> {
			Thread self = Thread.currentThread();
			Busy_State.WORK.put(self, busyLabel(name));
			publishBusy();
			try {
				work.run();
			} finally {
				Busy_State.WORK.remove(self);
				publishBusy();
			}
		}, name);
	}

	/** Anzahl laufender Hintergrundarbeiten mit Ladekreis (FX-Thread). */
	public static javafx.beans.property.ReadOnlyIntegerProperty busyCountProperty() {
		return Busy_State.COUNT;
	}

	/** Bezeichnung der zuletzt gestarteten laufenden Hintergrundarbeit (FX-Thread). */
	public static javafx.beans.property.ReadOnlyStringProperty busyTextProperty() {
		return Busy_State.TEXT;
	}

	/** aktueller Stand ohne FX-Thread (Pruefungen). */
	public static int busyWorkCount() {
		return Busy_State.WORK.size();
	}

	private static void publishBusy() {
		Platform.runLater(() -> {
			int count;
			String last = "";
			synchronized (Busy_State.WORK) {
				count = Busy_State.WORK.size();
				for (String label : Busy_State.WORK.values())
					last = label;
			}
			Busy_State.COUNT.set(count);
			Busy_State.TEXT.set(count > 1 ? last + " " + String.format(tr("(und %d weitere)"), count - 1) : last);
		});
	}

	/** Anzeigetext je Hintergrundarbeit (Thread-Name). */
	private static String busyLabel(String name) {
		switch (name == null ? "" : name) {
		case "Klein_Method_Headless_Adjustment": return tr("Ausgleichung und Prüfungen nach dem Tab-Klick …");
		case "Klein_Method_Locked_Tab_Conversion":
		case "Klein_Method_Local_Conversion": return tr("Umrechnung ins lokale System …");
		case "Klein_Method_Local_Conversion_Restore": return tr("Umrechnung wird zurückgenommen …");
		case "Klein_Method_Local_Relocate": return tr("Bezugspunkt P0 wird verlegt …");
		case "Klein_Method_Coordinate_Export": return tr("Koordinaten werden exportiert …");
		case "Klein_Method_Approximation_Check":
		case "Klein_Method_Approximation_Menu":
		case "Klein_Method_Approximation_Open": return tr("Näherungskoordinaten werden geprüft …");
		case "Klein_Method_Approximation_Restore": return tr("Näherungskoordinaten werden zurückgenommen …");
		case "Klein_Method_Point_Candidate_Report":
		case "Klein_Method_Point_Promotion":
		case "Klein_Method_Fixed_Point_Class":
		case "Klein_Method_Area_Recommendation":
		case "Klein_Method_Recommendation_Setting": return tr("Festpunkte werden bewertet …");
		case "Klein_Method_Calibration_Proof": return tr("Kalibrierungsnachweis wird geschrieben …");
		case "Klein_Method_Gross_Error_Cleanup":
		case "Klein_Method_Gross_Error_Finish": return tr("Grobe Fehler werden bereinigt …");
		case "Klein_Method_Gross_Error_Precheck": return tr("Vorprüfung grober Fehler …");
		case "Klein_Method_Swarm_Start_Values":
		case "Klein_Method_Swarm_Write":
		case "Klein_Method_Swarm_Download":
		case "Klein_Method_Swarm_Share":
		case "Klein_Method_Swarm_Point_Start_Values":
		case "Klein_Method_Swarm_Point_Write":
		case "Klein_Method_Swarm_View": return tr("Schwarmwissen wird abgerufen …");
		case "Klein_Method_Gnss_Support": return tr("GNSS-Stützung wird geprüft …");
		case "Klein_Method_Averaging_Guard": return tr("Mittelung wird geprüft …");
		case "Klein_Method_Import_Report":
		case "Klein_Method_Derive_1D":
		case "Klein_Method_Data_Status_Check": return tr("Import wird geprüft …");
		case "Klein_Method_Measurement_Plan": return tr("Messplan wird berechnet …");
		case "Klein_Method_Statistical_Test":
		case "Klein_Method_Statistical_Report":
		case "Klein_Method_Findings_Load":
		case "Klein_Method_Findings_Action": return tr("Statistischer Test läuft …");
		case "Klein_Method_Error_Simulation": return tr("Fehlersimulation läuft …");
		case "Klein_Method_Weight_Balance": return tr("Gewichtsverhältnis wird geprüft …");
		case "Klein_Method_Mass_Thinning": return tr("Massen-Basislinien werden ausgedünnt …");
		case "Klein_Method_Workflow_Action":
		case "Klein_Method_Workflow": return tr("Ablauf wird geprüft …");
		case "Klein_Method_Height_Datum":
		case "Klein_Method_Height_Check":
		case "Klein_Method_Height_Conversion": return tr("Höhen werden geprüft …");
		case "Klein_Method_Repetitions_Start":
		case "Klein_Method_Repetitions_Setting":
		case "Klein_Method_Repetitions":
		case "Klein_Method_Repetition_Comparison": return tr("Wiederholte Messungen werden behandelt …");
		case "Klein_Method_Rename_Preview":
		case "Klein_Method_Rename": return tr("Punkte werden umbenannt …");
		case "Klein_Method_Quasigeoid_Import":
		case "Klein_Method_Grid_Import": return tr("Quasigeoid wird eingelesen …");
		case "Klein_Method_Map_Database": return tr("Karte wird vorbereitet …");
		case "Klein_Method_Diagnostic_Read": return tr("Werte werden ausgelesen …");
		case "Klein_Method_Additional_Parameter_Write": return tr("Zusatzparameter werden geschrieben …");
		default: return tr("Klein_Method arbeitet im Hintergrund …");
		}
	}

	/** Reiter von Klein_Method im JAG3D-Fenster (null vor registerTab()). */
	public Tab getKleinMethodTab() {
		return this.kleinMethodTab;
	}

	private Tab kleinMethodTab;
	private TabPane hostTabPane;
	// siehe projectDatabaseStateChanged(): unterdrueckt automatische
	// Tab-Wiederherstellungen innerhalb eines Zeitfensters nach einem
	// Projektwechsel (nicht nur die allererste, siehe dortiger Kommentar).
	private Tab lastValidTab;

	// solange die Automatik (Klein_Method-eigene Iteration) laeuft, darf NICHT in einen anderen Tab/Baumbereich
	// gewechselt werden - zeigt stattdessen einen Hinweis und bleibt auf dem Klein_Method-Tab. Wird von
	// attachTaskCallbacks() beim Start/Ende der Automatik gesetzt.
	private volatile boolean automatikRunning = false;

	public void setAutomatikRunning(boolean running) {
		this.automatikRunning = running;
		this.updateProjectTreeLock();
	}

	/** true, solange Automatik oder "Grobe Fehler bereinigen" laeuft (Baum und Tab-Wechsel gesperrt). */
	public boolean isLongRunActive() {
		return this.automatikRunning || this.grossErrorCleanupRunning.get();
	}

	// Klein_Method aktualisiert gerade selbst die JAG3D-Anzeige (Tab-Wechsel dabei stammt nicht vom Anwender), FX-Thread
	private boolean jag3dViewRefreshing = false;

	// eine Sperr-Meldung ist gerade offen - weitere Ausloeser werden verworfen (nur im FX-Thread veraendert)
	private boolean longRunHintShowing = false;

	/**
	 * Projektbaum links (Festpunkte, Messdaten, ...) waehrend Automatik/"Grobe Fehler bereinigen" sperren, danach
	 * wieder freigeben. Aufruf aus beliebigem Thread.
	 */
	private void updateProjectTreeLock() {
		Runnable apply = () -> {
			try {
				UITreeBuilder treeBuilder = UITreeBuilder.getInstance();
				TreeView<TreeItemValue> tree = treeBuilder != null ? treeBuilder.getTree() : null;
				if (tree == null)
					return;
				boolean busy = this.isLongRunActive();
				if (tree.isDisable() != busy) {
					tree.setDisable(busy);
					this.logger.log("Klein_Method: Projektbaum " + (busy ? "gesperrt (Automatik/Bereinigung laeuft)." : "freigegeben."));
				}
			} catch (Throwable t) {
				this.logger.log("Klein_Method: Projektbaum konnte nicht ge-/entsperrt werden: " + t.getMessage());
			}
		};
		if (Platform.isFxApplicationThread())
			apply.run();
		else
			Platform.runLater(apply);
	}

	/** Hinweis "Automatik laeuft" - hoechstens einer gleichzeitig. FX-Thread. */
	private void showLongRunHintOnce() {
		if (this.longRunHintShowing)
			return;
		this.longRunHintShowing = true;
		try {
			Alert alert = new Alert(AlertType.WARNING);
			alert.setTitle(tr("Klein_Method - Automatik l\u00E4uft"));
			alert.setHeaderText(tr("Wechsel nicht m\u00F6glich, solange die Automatik l\u00E4uft"));
			alert.setContentText(tr("Bitte zuerst die laufende Berechnung abwarten oder \u00FCber \u201EStop\u201C beenden, "
					+ "bevor Sie den Tab wechseln oder im Baum navigieren."));
			alert.showAndWait();
		} finally {
			this.longRunHintShowing = false;
		}
	}

	/** true, solange die Automatik laeuft (Start-Knopf bleibt dann gesperrt). */
	public boolean isAutomatikRunning() {
		return this.automatikRunning;
	}

	/**
	 * true, solange der Automatik-Task laeuft - aus jedem Thread aufrufbar (Task.isRunning() ist nur im FX-Thread
	 * erlaubt; ausserhalb gilt der Task als laufend, bis er beendet ist).
	 */
	public boolean isAutomaticTaskRunning() {
		Klein_Method_Iteration_Task task = this.runningTask;
		if (task == null)
			return false;
		if (Platform.isFxApplicationThread())
			return task.isRunning();
		return this.automatikRunning || !task.isDone();
	}

	// Zeitpunkt der letzten Aktualisierung der JAG3D-Anzeige (fuer den Mindestabstand waehrend der Automatik)
	private volatile long lastJag3dViewRefreshMillis = 0L;

	/**
	 * Laedt die JAG3D-Anzeige (aktuelle Baumauswahl mit Ergebnissen, Varianzkomponenten und
	 * Gruppeneigenschaften) aus der Datenbank neu - derselbe Aufruf, den JAG3D nach F5 macht
	 * (UITreeBuilder.handleTreeSelections()). Ohne ihn zeigt JAG3D nach den Hintergrund-Ausgleichungen von Klein_Method
	 * bis zum naechsten Klick oder F5 den alten Stand, obwohl die Datenbank laengst aktuell ist.
	 *
	 * Laeuft auf dem JavaFX-Thread und nur, wenn gerade keine Ausgleichung/Schreibphase das Ausgleichungs-Lock haelt
	 * (sonst wird es beim naechsten Aufruf nachgeholt).
	 *
	 * @param force true = sofort (Laufende), false = hoechstens alle JAG3D_VIEW_REFRESH_INTERVAL_MS (waehrend des Laufs)
	 */
	public void refreshJag3dView(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - this.lastJag3dViewRefreshMillis < Klein_Method_Default_Values.JAG3D_VIEW_REFRESH_INTERVAL_MS)
			return;
		this.lastJag3dViewRefreshMillis = now;
		Platform.runLater(() -> {
			boolean locked = false;
			try {
				locked = force ? ADJUSTMENT_LOCK.tryLock(2, java.util.concurrent.TimeUnit.SECONDS) : ADJUSTMENT_LOCK.tryLock();
				if (!locked) {
					this.lastJag3dViewRefreshMillis = 0L; // Rechnung schreibt gerade - beim naechsten Aufruf nachholen
					return;
				}
				if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
					return;
				UITreeBuilder treeBuilder = UITreeBuilder.getInstance();
				if (treeBuilder != null) {
					this.jag3dViewRefreshing = true;
					treeBuilder.handleTreeSelections();
				}
			} catch (Throwable t) {
				this.logger.log("Klein_Method: JAG3D-Anzeige konnte nicht aktualisiert werden: " + t.getMessage());
			} finally {
				this.jag3dViewRefreshing = false;
				if (locked)
					ADJUSTMENT_LOCK.unlock();
			}
		});
	}

	/**
	 * "Weiche" Sperre statt harter Tab-Deaktivierung: der Tab bleibt
	 * IMMER anklickbar (ein deaktivierter JavaFX-Tab wuerde gar keine
	 * Klicks mehr durchlassen, dann koennte kein Hinweis gezeigt werden).
	 * Stattdessen wird jeder Auswahlwechsel abgefangen: ohne gueltige
	 * Berechnung erscheint ein Hinweisdialog und die Auswahl springt
	 * automatisch zum vorherigen Tab zurueck.
	 *
	 * Einmalig aus UITabPaneBuilder.init() aufzurufen, direkt nach dem
	 * Erzeugen des Klein_Method-Tabs (Hook).
	 */
	public void registerTab(Tab tab, TabPane tabPane) {
		this.kleinMethodTab = tab;
		this.hostTabPane = tabPane;
		this.grossErrorCleanupRunning.addListener((obs, wasRunning, running) -> this.updateProjectTreeLock());
		Platform.runLater(this::installMenu);

		tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
			if (this.isLongRunActive()) {
				// Automatik/"Grobe Fehler bereinigen" laeuft: nicht von Klein_Method weg (Baum ist ohnehin gesperrt),
				// zurueck zum Tab, hoechstens EINE Meldung; die Rueckkehr auf den Tab loest keine Hintergrund-Ausgleichung aus.
				if (oldTab == this.kleinMethodTab && newTab != this.kleinMethodTab) {
					final boolean byViewRefresh = this.jag3dViewRefreshing;
					Platform.runLater(() -> {
						if (!this.hostTabPane.getTabs().contains(this.kleinMethodTab)) {
							// Tab-Menge gehoert zu einem anderen Baumknoten - Wurzel waehlen, dann ist Klein_Method wieder da
							TreeView<TreeItemValue> tree = UITreeBuilder.getInstance().getTree();
							if (tree != null && tree.getRoot() != null)
								tree.getSelectionModel().select(tree.getRoot());
						}
						if (this.hostTabPane.getTabs().contains(this.kleinMethodTab))
							this.hostTabPane.getSelectionModel().select(this.kleinMethodTab);
						// Wechsel durch die Anzeige-Aktualisierung von Klein_Method selbst: still zuruecksetzen
						if (!byViewRefresh)
							this.showLongRunHintOnce();
					});
				}
				return;
			}
			if (newTab == this.kleinMethodTab && !this.isValidAdjustmentResultsAvailable() && this.headlessTabRefreshRunning) {
				// Ergebnisse werden gerade im Hintergrund neu gerechnet (Tab-Klick) - kein Hinweis, kein
				// Zuruecksetzen; der laufende Lauf schaltet den Tab frei.
				this.logger.log("Klein_Method: Tab-Auswahl waehrend der Hintergrund-Ausgleichung - kein Sperr-Hinweis.");
			}
			else if (newTab == this.kleinMethodTab && !this.isValidAdjustmentResultsAvailable()) {
				boolean withinProjectSwitchWindow = System.currentTimeMillis() - this.lastProjectSwitchTimestamp < PROJECT_SWITCH_SUPPRESS_WINDOW_MS;
				// geozentrische Punkte (z. B. gerade WGS84-Festpunkte und -Basislinien importiert): zuerst die Umrechnung
				// anbieten - ohne sie ist auch F5 sinnlos; sonst der bisherige Hinweis
				if (!withinProjectSwitchWindow)
					this.offerConversionOrShowLockedHint();
				final Tab fallbackTab = this.lastValidTab != null ? this.lastValidTab : oldTab;
				if (fallbackTab != null && fallbackTab != this.kleinMethodTab) {
					Platform.runLater(() -> this.hostTabPane.getSelectionModel().select(fallbackTab));
				}
			}
			else if (newTab == this.kleinMethodTab) {
				// Da der Nutzer zwischen dem letzten F5 und jetzt Rohdaten in ANDEREN Tabs veraendert haben KANN
				// (validAdjustmentResultsAvailable erkennt das nicht), wird zuerst eine Netzausgleichung im
				// Hintergrund (ohne sichtbaren Dialog) neu ausgefuehrt, damit garantiert aktuelle Ergebnisse
				// vorliegen - ERST DANACH die Messdaten-Ampel ermitteln (siehe
				// runHeadlessAdjustmentThenRefreshStatus()).
				this.runHeadlessAdjustmentThenRefreshStatus();
			}
			else {
				this.lastValidTab = newTab;
			}
		});
	}

	/**
	 * Fuehrt die eigentliche Netzausgleichung SYNCHRON (blockierend) aus - der reine Rechenkern ohne Dialog, ohne
	 * eigenes Lock und ohne eigenen Hintergrund-Thread.
	 *
	 * Wiederverwendet von (1) runHeadlessAdjustmentThenRefreshStatus() (Tab-Klick, eigener Thread+Lock drumherum) und
	 * (2) Klein_Method_Iteration_Task (Automatik - laeuft JEDE Iteration erneut, damit logPost/1:σ² garantiert
	 * aktuell sind, bevor neu kalibriert wird).
	 *
	 * FUNDSTELLEN-HINWEIS - dies, nicht Klein_Method_Functions.java, ist die tatsaechliche zentrale Schnittstelle,
	 * die nach JEDER vollzogenen JAG3D-Normalgleichungs-ausgleichung (F5, manuell UND automatisch) getriggert wird,
	 * BEVOR irgendein Aufrufer - insbesondere Klein_Method_Iteration_Task's Phase 2 - die Tabellen ausliest (siehe
	 * Wiederverwendungs-Hinweis oben). Punkt 1 daher HIER umgesetzt statt in Klein_Method_Functions.java.
	 *
	 * Wie beauftragt UMGESETZT: COMMIT unmittelbar gefolgt von CHECKPOINT direkt nach
	 * dataBaseManager.saveResults()/clear() - also nach JEDEM F5-Lauf, bevor irgendein nachfolgender Lesezugriff
	 * stattfindet. Beide Befehle sind ECHTES, existierendes HSQLDB-SQL und werden ueber den bereits etablierten
	 * Zugriffspfad ausgefuehrt.
	 *
	 * NICHT umgesetzt: "SET SCRIPT FORMAT BINARY;". Dieser Befehl flusht KEINE Daten - er stellt lediglich das
	 * Ziel-Dateiformat (TEXT vs. BINARY) fuer eine spaetere, hier gar nicht ausgefuehrte SCRIPT-Sicherung um. Ihn
	 * unbeauftragt/dauerhaft auf JAG3Ds eigener, von Klein_Method nur mitbenutzter Projekt-Datenbank zu setzen,
	 * wuerde das Format von deren eigener .script-Persistenzdatei fuer ALLE, auch JAG3D-eigene, zukuenftige
	 * Sicherungen aendern - ein Nebeneffekt ausserhalb von Klein_Method_Umfang/Verantwortung, ohne jeden Nutzen fuer
	 * den eigentlichen Zweck (physisches Flushing).
	 *
	 * WICHTIGE EINORDNUNG: Klein_Method liest UND schreibt seit Projektbeginn ausschliess-lich ueber EINE einzige,
	 * geteilte JDBC-Verbindung (SQLManager.getInstance(). getDataBase()). Auf DERSELBEN Verbindung sieht jede
	 * nachfolgende Abfrage ihre eigenen, bereits abgesetzten Schreibvorgaenge IMMER sofort ("read your own writes",
	 * Standard-JDBC-Verhalten) - unabhaengig von COMMIT-/ CHECKPOINT-Haeufigkeit. Ein "asynchroner Schatten-Puffer",
	 * der veraltete Werte an Klein_Methods eigenen Waechter/Phase-2-Lesezugriff liefert, ist innerhalb von
	 * Klein_Method selbst damit technisch nicht moeglich. COMMIT/ CHECKPOINT wirken ausschliesslich auf die physische
	 * Festplatten-Persistenz
	 *
	 * sie sind real, risikoarm und sinnvoll (unwiderrufliche Persistenz nach jedem F5-Lauf), werden aber aller
	 * Voraussicht nach NICHT die berichtete Einfrier-Symptomatik bei Max-logPost 4.2867 beheben, da diese nicht auf
	 * Datenbank-Sichtbarkeit zurueckgehen kann. Der Aufruf erfolgt dennoch vollstaendig wie beauftragt.
	 *
	 * @return das Ergebnis der Ausgleichung (ERROR_FREE_ESTIMATION = Erfolg)
	 */
	public EstimationStateType runHeadlessAdjustmentSynchronously() throws Exception {
		long startMillis = System.currentTimeMillis();
		if (!this.massPart.guard())
			return EstimationStateType.INTERRUPT; // Massen-Basislinien: mehr aktive Basislinien als JAG3D verarbeiten kann

		SQLAdjustmentManager dataBaseManager = SQLManager.getInstance().getAdjustmentManager();
		SQLManager.getInstance().checkNumberOfObersvationsPerUnknownParameter();

		NetworkAdjustment adjustment = dataBaseManager.getNetworkAdjustment();
		EstimationStateType result = adjustment.estimateModel();
		if (result == EstimationStateType.ERROR_FREE_ESTIMATION)
			this.approximationsRestoredPending = false;

		dataBaseManager.saveResults();
		dataBaseManager.clear();

		// erzwungener physischer HSQLDB-Flush direkt nach JEDEM F5-Lauf, VOR jedem nachfolgenden Lesezugriff
		// (Waechter-Messung unten, Klein_Method_Iteration_Task Phase 2). Siehe Methoden-Javadoc oben fuer die
		// vollstaendige technische Einordnung.
		this.databaseHandler.commit();
		this.databaseHandler.checkpoint();

		long durationMillis = System.currentTimeMillis() - startMillis;
		try {
			long observationCount = this.databaseHandler.readObservationCount();
			this.guardian.recordMeasurement(durationMillis, observationCount);
			Platform.runLater(() -> this.refreshGuardianAppearance());
		} catch (SQLException e) {
			// Waechter-Messung ist eine reine Zusatzfunktion - ein Fehler hier
			// darf die eigentliche Ausgleichung (deren Ergebnis "result" ja
			// bereits feststeht) nicht zum Scheitern bringen, nur protokollieren.
			this.logger.log("Klein_Method: Waechter-Messung fehlgeschlagen: " + e.getMessage());
		}

		return result;
	}

	/**
	 * Bei jeder erfolgreichen Ausgleichung (ERROR_FREE_ESTIMATION) zusaetzlich
	 * zu pruefen: enthaelt das Projekt AKTIVE Beobachtungen eines von
	 * Klein_Method nicht unterstuetzten Typs? Siehe handleUnsupportedObservationTypesDetected().
	 *
	 * @return true, wenn ALLE aktiven Typen unterstuetzt werden (also alles ok)
	 */
	private boolean checkSupportedObservationTypesOrLock() throws SQLException {
		Set<ObservationType> activeTypes = this.databaseHandler.readActiveObservationTypes();
		Set<ObservationType> unsupportedTypes = new HashSet<ObservationType>(activeTypes);
		unsupportedTypes.removeAll(Klein_Method_Default_Values.SUPPORTED_OBSERVATION_TYPES);

		if (!unsupportedTypes.isEmpty()) {
			this.handleUnsupportedObservationTypesDetected(unsupportedTypes);
			return false;
		}
		return true;
	}

	/**
	 * Wie runHeadlessAdjustmentSynchronously() aufgerufen wird, wenn beim
	 * Anklicken des Klein_Method-Tabs zuerst eine Netzausgleichung im
	 * Hintergrund neu ausgefuehrt werden soll, OHNE sichtbaren Dialog
	 * (bewusste Entscheidung, damit das Anklicken des Klein_Method-Tabs
	 * nicht durch ein Popup unterbrochen wird). Absichtlich unabhaengig
	 * von NetworkAdjustmentDialog.java implementiert (dessen AdjustmentTask
	 * ist eine private innere Klasse) - nutzt dieselben oeffentlichen
	 * JAG3D-Klassen direkt.
	 *
	 * Nach erfolgreichem Abschluss (ERROR_FREE_ESTIMATION) wird die
	 * Messdaten-Ampel neu ermittelt (Klein_Method_Tab_Builder.refreshDataStatus()).
	 * Schlaegt die Ausgleichung fehl, wird die Ampel direkt auf ROT gesetzt,
	 * OHNE die (dann ja ungueltigen) Werte erst noch zu lesen.
	 */
	// true, solange die Hintergrund-Ausgleichung nach einem Tab-Klick laeuft - eine erneute Auswahl des Tabs in
	// dieser Zeit (JAG3D baut die Tab-Menge nach F5/ Baumauswahl per Platform.runLater neu auf) zeigt KEINEN
	// Sperr-Hinweis mehr.
	private volatile boolean headlessTabRefreshRunning = false;

	private void runHeadlessAdjustmentThenRefreshStatus() {
		if (this.headlessTabRefreshRunning) {
			this.logger.log("Klein_Method: Tab erneut ausgewaehlt, Hintergrund-Ausgleichung laeuft bereits - kein zweiter Lauf.");
			return;
		}
		this.headlessTabRefreshRunning = true;
		this.setValidAdjustmentResultsAvailable(false); // wie beim manuellen F5: waehrend der Berechnung ungueltig
		this.beginInitialisationLock();

		Thread thread = busyThread(() -> {
			ADJUSTMENT_LOCK.lock();
			try {
				// geozentrische Koordinaten mit 3D-GNSS-Basislinien -> Umrechnung ins lokale System anbieten
				this.checkLocalConversion(false);
				this.relocateP0(false);
				// "lokal ellipsoidisch": Bezugspunkt muss zu P0 passen
				this.checkPrincipalPoint();
				// Wechsel-/Aufstellpunkte des Nivellements ohne Lage in die Mitte ihrer Nachbarn (Karte, Pruefungen, 1D-Hoehen)
				this.importReportPart.deriveOnTabClick();
				// Nivellement und GNSS im selben Hoehensystem? (Hinweis einmal je Projekt und Ergebnis)
				this.quasigeoidPart.checkHeightSystemsOnTabClick();
				// Importbericht: Plausibilitaet der Daten bei neuem Datenstand (Fenster nur mit roten Punkten)
				this.importReportPart.checkOnTabClick();
				// in der freien AGL zuerst die Naeherungskoordinaten der Neupunkte pruefen (einmal je Datenstand),
				// damit die folgende Ausgleichung bereits mit den gewaehlten Naeherungen rechnet
				boolean approximationsWritten = this.checkApproximationCoordinates(false);
				// GNSS-Sitzungen zusammenfassen (Einstellung "Ja (empfohlen)") - nach der Naeherungspruefung, die die Epochen
				// fuer die Drift-Pruefung braucht
				this.repetitionPart.offerOnTabClick();
				// "Hoehe nur aus 1D": zur aktuellen Ausgleichungsstufe passend anwenden bzw. zuruecknehmen
				this.heightDatumPart.ensureOnTabClick();
				EstimationStateType result = this.runHeadlessAdjustmentSynchronously();
				// Probe: rechnet die Ausgleichung mit den gerade uebernommenen Naeherungen nicht, die Punkte mit den groessten
				// Aenderungen nennen (statt erst in der Automatik zu scheitern)
				if (approximationsWritten && result != EstimationStateType.ERROR_FREE_ESTIMATION)
					this.approximationPart.reportFailedTrial(result);
				// Ablauf-Assistent: Stand neu bewerten (Startsperre, Anzeige im Reiter)
				this.workflowPart.recompute();

				if (result == EstimationStateType.ERROR_FREE_ESTIMATION) {
					// GNSS-Stuetzung am Netzrand (Wegdriften) - Hinweis einmal je Ergebnis
					this.gnssSupportPart.checkAfterAdjustment();
					if (this.checkSupportedObservationTypesOrLock()) {
						String checksumBeforeOwnCorrections = this.databaseHandler.computeCurrentStepChecksum();

						// Validierungsregel bei jedem Tab-Aktivieren: manuell eingetragene
						// Gruppenunsicherheiten 0<Wert<Grenzwert werden sofort auf den
						// Grenzwert korrigiert (Wert=0 bleibt unangetastet - deaktiviert,
						// siehe Klein_Method_Functions.performGroupCalibrationStep()).
						List<String> corrections = this.functions.validateGroupUncertaintiesOnTabActivation(this.parameter, this.databaseHandler);
						if (!corrections.isEmpty()) {
							this.logger.log("Klein_Method: " + corrections.size() + " Gruppenunsicherheit(en) beim Tab-Aktivieren auf den Grenzwert korrigiert:");
							for (String correction : corrections)
								this.logger.log("Klein_Method:   " + correction);

							// Auf eine handhabbare Anzahl Zeilen gekappt, die VOLLSTAENDIGE Liste steht trotzdem in
							// der Log-Datei Klein_Method.log (siehe oben, this.logger.log()).
							String correctionText = this.buildCappedCorrectionText(corrections);
							Platform.runLater(() -> {
								Alert alert = new Alert(AlertType.INFORMATION);
								alert.setTitle(tr("Klein_Method - Gruppenunsicherheiten korrigiert"));
								alert.setHeaderText(tr("Manuell eingetragene Werte unter dem Grenzwert wurden korrigiert"));
								alert.setContentText(tr("Folgende Gruppenunsicherheiten lagen zwischen 0 und ihrem Grenzwert und wurden "
										+ "automatisch auf den Grenzwert gesetzt:\n\n") + correctionText);
								alert.showAndWait();
							});
						}

						// bei jedem Tab-Aktivieren pruefen, ob die neuen Zusatzparameter-Haken
						// (Additionskonstante/Massstab berechnen) zwischen MEHREREN Gruppen desselben Streckentyps
						// inkonsistent sind (z.B. weil jemand das direkt in JAG3D nur fuer eine Gruppe geaendert hat)
						// - falls ja, wird auf "aus" zurueckgesetzt.
						List<String> additionalParameterCorrections = this.functions.validateAdditionalParameterConsistencyOnTabActivation(this.databaseHandler);
						if (!additionalParameterCorrections.isEmpty()) {
							this.logger.log("Klein_Method: " + additionalParameterCorrections.size() + " inkonsistente(r) Zusatzparameter-Haken beim Tab-Aktivieren auf \"aus\" zur\u00FCckgesetzt:");
							for (String correction : additionalParameterCorrections)
								this.logger.log("Klein_Method:   " + correction);

							String correctionText = this.buildCappedCorrectionText(additionalParameterCorrections);
							Platform.runLater(() -> {
								Alert alert = new Alert(AlertType.WARNING);
								alert.setTitle(tr("Klein_Method - Zusatzparameter inkonsistent"));
								alert.setHeaderText(tr("Additionskonstante/Ma\u00DFstab waren zwischen mehreren Gruppen nicht einheitlich"));
								alert.setContentText(correctionText);
								alert.showAndWait();
							});
						}

						// Deshalb hier automatisch ein weiteres, frisches F5 anstossen, BEVOR irgendetwas als
						// gueltig/ aktuell angezeigt wird - konsistent mit der bestehenden "weiche Sperre, alles
						// automatisch im Hintergrund"-Philosophie, keine zusaetzliche manuelle Aktion noetig.
						if (!corrections.isEmpty() || !additionalParameterCorrections.isEmpty()) {
							this.logger.log("Klein_Method: Korrekturen erfolgt - stosse automatisch eine erneute Netzausgleichung an, "
									+ "damit die Ergebnisse konsistent zu den korrigierten Werten sind.");
							result = this.runHeadlessAdjustmentSynchronously();
							if (result != EstimationStateType.ERROR_FREE_ESTIMATION) {
								this.logger.log("Klein_Method: Erneute Netzausgleichung nach Korrektur fehlgeschlagen - Klein_Method bleibt gesperrt.");
								return;
							}
						}

						// Reihenfolge der Ausgleichungsschritte pruefen (freie -> dynamische ->
						// fixe Ausgleichung, siehe getCurrentAdjustmentStep()/enforceAdjustmentStepOrder()).
						// Dafuer muessen die Messdaten frisch gelesen sein, um den aktuellen Ampel-
						// Status zu kennen - passiert hier synchron (wir sind bereits im Hintergrund-Thread).
						this.databaseHandler.read(this.parameter);
						Klein_Method_Data_Status dataStatus = this.functions.determineDataStatus(this.parameter);
						Klein_Method_Adjustment_Step currentStep = this.getCurrentAdjustmentStep();
						// Ablaufphase an die aktuelle Stufe anpassen (Stufenwechsel im selben Projekt)
						if (this.parameter.alignProcessPhaseToStep(currentStep))
							this.logger.log("Klein_Method: Ausgleichungsstufe " + currentStep.getShortDisplayName()
									+ " passt nicht zur Ablaufphase - auf " + this.parameter.getProcessPhase() + " zurueckgesetzt.");
						Platform.runLater(() -> this.refreshAdjustmentStepLabel(currentStep));
						// Start-Sperre nach fertiger Automatik je Stufe auswerten (Stufe gewechselt -> Start wieder
						// frei).
						this.evaluateCompletedStepLock("Tab aktiviert");
						this.evaluateGrossErrorGate(currentStep);
						Platform.runLater(() -> this.refreshNextStepHint(this.buildNextStepHint(currentStep, dataStatus)));

						// Hier zusaetzlich bei JEDEM Tab-Aktivieren.
						Platform.runLater(() -> this.tabBuilder.updateEvaluationMethodAvailability());

						// Der Schutz bei fehlenden freien GNSS-Daten laeuft ab jetzt AUSSCHLIESSLICH ueber
						// refreshDistributionState() (Deaktivierung/Ausgrauen), siehe dort.
						boolean hasGnssBaselines = this.hasActiveGnssBaselines();
						Platform.runLater(() -> this.refreshDistributionState(currentStep, hasGnssBaselines));

						// Zusatzparameter (Nullpunktabweichung/Massstab) bei jedem Tab-Aktivieren automatisch mit
						// aktualisieren - kein eigener Button mehr; tabBuilder.refreshAdditionalParameters() liest
						// synchron (wir sind bereits im Hintergrund-Thread) und aktualisiert die Labels selbst ueber
						// Platform.runLater().
						try {
							this.tabBuilder.refreshAdditionalParameters();
						} catch (SQLException e) {
							this.logger.log("Klein_Method: Konnte Zusatzparameter nicht lesen: " + e.getMessage());
						}

						// Koordinatensystem-Anzeige - endlich mit echter Datenquelle statt dauerhaftem Platzhalter
						// "unbekannt".
						this.tabBuilder.refreshCoordinateSystem();

						if (this.enforceAdjustmentStepOrder(currentStep, dataStatus, checksumBeforeOwnCorrections)) {
							this.loadSwarmFixedPointClass();
							this.loadRecommendationSettings();
							this.checkProjection();
							this.setValidAdjustmentResultsAvailable(true);
							this.refreshFixedPointRecommendation();
							Platform.runLater(() -> {
								this.tabBuilder.refreshDataStatus();
								this.tabBuilder.refreshSwarmView();
							});
						}
						// sonst: bewusst gesperrt lassen, enforceAdjustmentStepOrder() hat
						// bereits verlassen+gesperrt (siehe dort).
					}
					// sonst: bewusst gesperrt lassen (validAdjustmentResultsAvailable
					// NICHT auf true setzen), bis Klein_Method diese Typen unterstuetzt -
					// siehe handleUnsupportedObservationTypesDetected() (bereits ausgeloest).
				}
				else if (result == EstimationStateType.INTERRUPT && this.massPart.isBlocked())
					this.logger.log("Klein_Method: Hintergrund-Ausgleichung wegen Massen-Basislinien nicht gestartet (Hinweis angezeigt, Ablauf-Assistent Schritt 6).");
				else {
					this.logger.log("Klein_Method: automatische Netzausgleichung im Hintergrund lieferte kein fehlerfreies Ergebnis (" + result + ").");
					this.handleFailedAdjustment(result);
				}
			}
			catch (Exception e) {
				this.logger.log("Klein_Method: Fehler bei automatischer Netzausgleichung im Hintergrund: " + e.getMessage());
				this.exitAndLockKleinMethodTab("Netzausgleichung fehlgeschlagen",
						"Bei der automatischen Netzausgleichung im Hintergrund ist ein Fehler aufgetreten:\n" + e.getMessage()
						+ "\n\nKlein_Method wird deshalb verlassen und gesperrt. Bitte die Ursache manuell kl\u00E4ren und "
						+ "danach die Netzausgleichung (F5) erneut ausf\u00FChren.");
			}
			finally {
				this.headlessTabRefreshRunning = false;
				ADJUSTMENT_LOCK.unlock();
				// erst wieder bedienbar, wenn alle Meldungen dieser Pruefung geschlossen sind (Platform.runLater ist
				// FIFO - Meldungen, die der Lauf angestossen hat, sind dann bereits offen)
				Platform.runLater(this::endInitialisationLock);
			}
		}, "Klein_Method_Headless_Adjustment");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Wird aufgerufen, sobald erkannt wird, dass eine Netzausgleichung
	 * FEHLGESCHLAGEN bzw. DIVERGIERT ist - egal ob durch unseren eigenen
	 * Hintergrund-F5 (siehe runHeadlessAdjustmentThenRefreshStatus()) oder
	 * durch ein manuelles F5/"Netzausgleichung starten" (Hook in
	 * NetworkAdjustmentDialog.java, direkt nach dem switch(result)).
	 *
	 * Greift NUR, wenn der Klein_Method-Tab GERADE aktiv ist - ist er das
	 * nicht, reicht die bestehende weiche Sperre (validAdjustmentResultsAvailable
	 * bleibt false, naechster Versuch den Tab zu oeffnen wird ohnehin
	 * abgewiesen) voellig aus, ein zusaetzlicher Hinweis waere dann nur
	 * verwirrend, weil der Nutzer Klein_Method ja gar nicht ansieht.
	 *
	 * Verhalten (wie vom Nutzer vorgegeben): Klein_Method wird verlassen
	 * UND bleibt gesperrt, der Nutzer muss die Ursache manuell klaeren.
	 * Wiederverwendet JAG3Ds eigenes Fehlerdialog-System (OptionDialog),
	 * damit die Meldung optisch/inhaltlich konsistent zum normalen
	 * F5-Fehlerdialog bleibt.
	 */
	/**
	 * true, wenn der Klein_Method-Tab GERADE der ausgewaehlte/aktive Tab ist - zentrale Pruefung, wiederverwendet
	 * u.a. von handleFailedAdjustment() und der F5-/"Netzausgleichung starten"-Sperre in
	 * NetworkAdjustmentDialog.show().
	 */
	public boolean isKleinMethodTabActive() {
		return this.hostTabPane != null && this.hostTabPane.getSelectionModel().getSelectedItem() == this.kleinMethodTab;
	}

	private volatile boolean approximationsRestoredPending = false;

	public void handleFailedAdjustment(EstimationStateType result) {
		if (!this.isKleinMethodTabActive())
			return;

		boolean diverged = result == EstimationStateType.NO_CONVERGENCE || result == EstimationStateType.ROBUST_ESTIMATION_FAILED;

		// Texte ueber tr() (Oberflaechensprache), Log bleibt deutsch
		String header = diverged ? tr("Netzausgleichung divergiert") : tr("Netzausgleichung fehlgeschlagen");
		String message = (diverged
				? tr("Die Iteration der Netzausgleichung ist divergiert (Abbruchschranke erreicht, ohne zu konvergieren).")
				: String.format(tr("Die Netzausgleichung wurde mit einem Fehler abgebrochen (%s)."), result))
				+ "\n\n" + tr("Klein_Method wird deshalb verlassen und gesperrt. Bitte die Ursache manuell kl\u00E4ren und "
				+ "danach die Netzausgleichung (F5) erneut ausf\u00FChren.");
		// nach "Letzte Uebernahme zuruecknehmen" passen die alten Naeherungen oft nicht - die automatische Pruefung
		// fragt fuer diesen Stand bewusst nicht erneut, deshalb hier der Weg zur Neuberechnung
		if (this.approximationsRestoredPending) {
			message += "\n\n" + tr("Hinweis: Zuvor wurden Näherungskoordinaten zurückgenommen. Passen sie nicht zu den Messungen, "
					+ "„Näherungskoordinaten jetzt prüfen“ (Knopf im Tab oder Menü Klein_Method) neu berechnen lassen.");
			this.logger.log("Klein_Method: Ausgleichung nach Ruecknahme der Naeherungskoordinaten nicht fehlerfrei (" + result + ") - Hinweis auf Neupruefung gezeigt.");
		}

		this.exitAndLockKleinMethodTab(header, message);
	}

	/**
	 * Wird aufgerufen, wenn nach einer erfolgreichen Netzausgleichung
	 * erkannt wird, dass das Projekt AKTIV Beobachtungen eines Typs
	 * enthaelt, den Klein_Method (noch) nicht unterstuetzt (z.B. GNSS-
	 * Basislinien, Nivellement - siehe Klein_Method_Default_Values.
	 * SUPPORTED_OBSERVATION_TYPES) - auch bei einer MISCHUNG aus
	 * unterstuetzten und nicht unterstuetzten Typen. Zeigt eine Hinweis-
	 * Msgbox mit den konkreten (unbekannten) Typnamen und sperrt
	 * Klein_Method wie bei einer fehlgeschlagenen Ausgleichung.
	 */
	private void handleUnsupportedObservationTypesDetected(Set<ObservationType> unsupportedTypes) {
		StringBuilder typeNames = new StringBuilder();
		for (ObservationType type : unsupportedTypes) {
			if (typeNames.length() > 0)
				typeNames.append(", ");
			typeNames.append(type.name());
		}

		this.logger.log("Klein_Method: nicht unterstuetzte Beobachtungstypen im Projekt aktiv gefunden: " + typeNames + " - Klein_Method wird gesperrt.");

		this.exitAndLockKleinMethodTab(
				"Nicht unterst\u00FCtzte Messdaten gefunden",
				"Das Projekt enth\u00E4lt aktive Beobachtungen folgender, von Klein_Method (noch) nicht "
				+ "unterst\u00FCtzter Typen:\n\n" + typeNames + "\n\n"
				+ "Diese Beobachtungen w\u00FCrden bei der Bewertung nicht ber\u00FCcksichtigt - die Messdaten-Ampel "
				+ "k\u00F6nnte deshalb f\u00E4lschlich Gr\u00FCn anzeigen. Klein_Method wird daher verlassen und gesperrt, "
				+ "bis diese Typen unterst\u00FCtzt werden bzw. nicht mehr im Projekt aktiv sind.");
	}

	private void exitAndLockKleinMethodTab(String header, String message) {
		Platform.runLater(() -> {
			OptionDialog.showErrorDialog(tr("Klein_Method"), header, message);
			if (this.tabBuilder != null)
				this.tabBuilder.applyDataStatus(Klein_Method_Data_Status.ERROR);
			final Tab fallbackTab = this.lastValidTab;
			if (this.hostTabPane != null && fallbackTab != null && fallbackTab != this.kleinMethodTab)
				this.hostTabPane.getSelectionModel().select(fallbackTab);
		});
	}

	/**
	 * nach Klein_Methods EIGENEN Schreibvorgaengen (Automatik, Einzelschritt, EP-Einzelschritt,
	 * Zusatzparameter-Haken) die Pruefsumme sofort als neue Baseline speichern.
	 */
	public void acceptOwnChangesIntoChecksum(String reason) {
		try {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;
			this.databaseHandler.storeLastVerifiedChecksum(this.databaseHandler.computeCurrentStepChecksum());
			this.logger.log("Klein_Method: Pruefsumme nach eigener Aenderung (" + reason + ") als neue Baseline gespeichert.");
		} catch (SQLException e) {
			this.logger.log("Klein_Method: Pruefsumme nach eigener Aenderung (" + reason + ") nicht speicherbar: " + e.getMessage());
		}
	}

	public BooleanProperty grossErrorsSuspectedProperty() {
		return this.grossErrorPart.grossErrorsSuspectedProperty();
	}

	public boolean isGrossErrorsSuspected() {
		return this.grossErrorPart.isGrossErrorsSuspected();
	}

	public BooleanProperty grossErrorCleanupRunningProperty() {
		return this.grossErrorPart.grossErrorCleanupRunningProperty();
	}

	public String getGrossErrorGateText() {
		return this.grossErrorPart.getGrossErrorGateText();
	}

	private static String describeObservation(Max_Error_Row row) {
		return Klein_Method_Tab_Builder.Klein_Method_Map_Window.observationTypeName(row.observationType) + " " + row.startPointName
				+ " \u2013 " + row.endPointName + " | v = " + Klein_Method_Tab_Builder.Klein_Method_Map_Window.formatMaxErrorResidual(row);
	}

	/** lineare Abweichung als Text ("0,234 m" bzw. "nicht bestimmbar"). */
	private static String formatLinear(double linearError) {
		return Double.isNaN(linearError) ? "nicht bestimmbar"
				: String.format(Locale.GERMANY, linearError < 10.0 ? "%.3f m" : "%.1f m", linearError);
	}

	public void evaluateGrossErrorGate(Klein_Method_Adjustment_Step step) {
		this.grossErrorPart.evaluateGrossErrorGate(step);
	}

	public void startGrossErrorCleanup() {
		this.grossErrorPart.startGrossErrorCleanup();
	}

	/** Automatik vor Durchlauf 1: grobe Fehler ohne Fenster bereinigen; Anzahl abgeschaltet, -1 = nichts zu tun. */
	public int runAutomaticGrossErrorCleanup() {
		return this.grossErrorPart.runAutomatic();
	}

	/** Kontrolle grober Fehler am Laufende der freien/fixen AGL (Protokollzeilen). */
	public List<String> grossErrorEndOfRunCheck() {
		return this.grossErrorPart.endOfRunCheck();
	}

	/** Automatik-Start: Ausgangsgenauigkeiten merken, statistischen Test fuer diesen Lauf vormerken (Lock beim Aufrufer). */
	public void statisticalTestCaptureOrigin() {
		this.statisticalTestPart.captureOrigin();
	}

	/** Uebergang Gruppen- -> Einzelkalibrierung: statistischer Test mit den Ausgangsgenauigkeiten (Lock beim Aufrufer). */
	public void statisticalSnapshotAfterGroupCalibration(List<String> details) {
		this.statisticalTestPart.snapshot(details);
	}

	/** Bericht des statistischen Tests (Fenster). */
	/** Punkte umbenennen: Fenster oeffnen. */
	public void openRenameWindow() {
		Klein_Method_Tab_Builder.Klein_Method_Rename_Window.open(this);
	}

	/** Punkte umbenennen: Vorschau. */
	public List<RenameRow> previewRename(RenameSpec spec) throws SQLException {
		return this.renamePart.preview(spec);
	}

	/** Punkte umbenennen: gewaehlte Zeilen ausfuehren. */
	public String applyRename(List<RenameRow> rows, RenameSpec spec) throws SQLException {
		return this.renamePart.apply(rows, spec);
	}

	/** Punkte umbenennen: letzte Umbenennung zuruecknehmen. */
	public String undoLastRename() throws SQLException {
		return this.renamePart.undo();
	}

	/** Gewichtsverhaeltnis-Ampel anzeigen. */
	public void openWeightBalance() {
		this.weightPart.open();
	}

	/** Gewichtsverhaeltnis je Messart als Text und Ampel ({0 gruen, 1 gelb, 2 rot}, Text). */
	public Object[] weightBalance() throws SQLException {
		List<Weight_Part.Row> rows = this.weightPart.compute();
		return new Object[] { this.weightPart.worst(rows), this.weightPart.text(rows) };
	}

	/** Separate Funktion: statistischen Test rechnen (Hintergrund) und den Bericht zeigen. */
	public void startStatisticalTest() {
		this.statisticalTestPart.runOnDemand();
	}

	public void openStatisticalTestReport() {
		this.statisticalTestPart.openReport(false);
	}

	/** Liste auffaelliger Beobachtungen: Befunde lesen. */
	public List<Finding> readFindings() throws SQLException {
		return this.findingsPart.read();
	}

	/** Liste: Aktion disable/enable/correct/downweight/undo fuer die gewaehlten Zeilen. */
	public String applyFindingAction(List<Finding> rows, String action) throws SQLException {
		return this.findingsPart.apply(rows, action);
	}

	/** Liste: Instrumenten-/Zielhoehe setzen (NaN = unveraendert). */
	public String setFindingHeights(Finding f, double instrument, double reflector) throws SQLException {
		return this.findingsPart.heights(f, instrument, reflector);
	}

	/** Liste: alle Aenderungen zuruecknehmen. */
	public int undoAllFindingEdits() throws SQLException {
		return this.findingsPart.undoAll();
	}

	/** Liste: neu testen. */
	public String retestFindings() throws Exception {
		return this.findingsPart.retest();
	}

	/** Liste: Gruppentests auf gemeinsame Ursache rechnen (dauert: 2 Ausgleichungen je Gruppe). */
	public String runGroupTests() throws Exception {
		return this.groupTestPart.run();
	}

	/**
	 * Fehlersimulation (Monte Carlo) am eigenen Netz: sizes = Fehlergroessen [m], trials = Versuche je Groesse und Art,
	 * kinds = {Strecken, Richtungen, Zenitwinkel, Prismenkonstante je Ziel, Instrumentenhoehe je Standpunkt}.
	 */
	public String runErrorSimulation(double[] sizes, int trials, boolean[] kinds, java.util.function.Consumer<String> progress) throws Exception {
		return this.simulationPart.run(sizes, trials, kinds, progress);
	}

	/** Anzahl Ausgleichungen der Fehlersimulation (fuer die Dauer-Anzeige). */
	public static int errorSimulationAdjustments(double[] sizes, int trials, boolean[] kinds) {
		int n = 0;
		for (int k = 0; k < kinds.length; k++)
			if (kinds[k])
				n += sizes.length * trials * (k < 3 ? 1 : 3);
		return n + 2;
	}

	/** Liste: CSV schreiben. */
	public void exportFindingsCsv(java.io.File file, List<Finding> list) throws java.io.IOException {
		this.findingsPart.exportCsv(file, list);
	}

	/** Liste auffaelliger Beobachtungen oeffnen. */
	public void openFindingsList() {
		Klein_Method_Tab_Builder.Klein_Method_Findings_Window.open(this);
	}

	/** Liste oeffnen und die Gruppentests starten. */
	public void openFindingsListWithGroupTests() {
		Klein_Method_Tab_Builder.Klein_Method_Findings_Window.openWithGroupTests(this);
	}

	/** "Statistisch auffaellige bereinigen" (mit Rueckfrage). */
	public void startStatisticalCleanup() {
		this.statisticalTestPart.askAndStartCleanup();
	}

	private static String joinDescriptions(List<Max_Error_Row> rows) {
		StringBuilder text = new StringBuilder();
		for (Max_Error_Row row : rows)
			text.append("• ").append(describeObservation(row)).append("\n");
		return text.toString().trim();
	}

	/**
	 * Nach dem Oeffnen: liegt noch eine Sicherung vor (Durchlauf 1 unter dem Grenzwert oder Nachlauf-Bestwert; Lauf
	 * wurde unterbrochen, z. B. Absturz), den
	 * gesicherten besten Stand anbieten. Ja = zurueckschreiben (Ergebnisse danach ungueltig, bei aktivem Tab neu
	 * ausgeglichen), Nein = Sicherung verwerfen. Aufruf im Hintergrund-Thread.
	 */
	void offerBestStateRecovery() {
		double[] info;
		ADJUSTMENT_LOCK.lock();
		try {
			if (!this.databaseHandler.hasProjectTables())
				return;
			info = this.databaseHandler.readBestStateInfo();
		} catch (Exception e) {
			this.logger.log("Klein_Method: Sicherung des Nachlaufs nicht lesbar: " + e.getMessage());
			return;
		} finally {
			ADJUSTMENT_LOCK.unlock();
		}
		if (info == null)
			return;
		this.logger.log("Klein_Method: Sicherung eines unterbrochenen Nachlaufs gefunden (Max-logPost "
				+ String.format(Locale.GERMANY, "%.2f", info[0]) + ", Durchlauf " + (int) info[1] + ", Iteration " + (int) info[2] + ").");
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		boolean[] confirmed = new boolean[] { false };
		Platform.runLater(() -> {
			try {
				Alert alert = new Alert(AlertType.CONFIRMATION);
				alert.setTitle(tr("Klein_Method - Nachlauf"));
				alert.setHeaderText(tr("Unterbrochener Lauf: besten Stand zurückschreiben?"));
				alert.setContentText(String.format(tr("Der letzte Automatik-Lauf wurde unterbrochen. "
						+ "Gesichert ist der beste Stand dieses Laufs mit Max-logPost %s (Durchlauf %d, Iteration %d).\n\n"
						+ "OK = diesen Stand zurückschreiben, Abbrechen = Sicherung verwerfen und den aktuellen Stand behalten."),
						String.format(Locale.GERMANY, "%.2f", info[0]), (int) info[1], (int) info[2]));
				alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
				confirmed[0] = alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
			} finally {
				latch.countDown();
			}
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			return;
		}
		ADJUSTMENT_LOCK.lock();
		try {
			if (confirmed[0]) {
				int[] counts = this.databaseHandler.restoreBestState();
				this.logger.log("Klein_Method: bester Stand des unterbrochenen Nachlaufs zurueckgeschrieben (" + counts[0] + " Beobachtungen, "
						+ counts[1] + " GNSS-Basislinien, " + counts[2] + " Gruppenwerte, " + counts[3] + " Zusatzparameter).");
			} else
				this.logger.log("Klein_Method: Sicherung des unterbrochenen Nachlaufs verworfen.");
			this.databaseHandler.deleteBestState();
			this.databaseHandler.commit();
			if (confirmed[0])
				this.acceptOwnChangesIntoChecksum("Nachlauf-Sicherung zurueckgeschrieben"); // eigene Werte, kein Reihenfolgefehler
		} catch (Exception e) {
			this.logger.log("Klein_Method: Sicherung des Nachlaufs nicht verarbeitbar: " + e.getMessage());
			return;
		} finally {
			ADJUSTMENT_LOCK.unlock();
		}
		if (confirmed[0]) {
			Platform.runLater(() -> this.setValidAdjustmentResultsAvailable(false));
			if (this.isKleinMethodTabActive())
				this.runHeadlessAdjustmentThenRefreshStatus();
		}
	}

	/**
	 * Die {@code count} Beobachtungen mit dem groessten logPost als Textliste (Hinweis am Ende des Nachlaufs,
	 * nur Anzeige auf dem eigenen Rechner). Leerer Text, wenn nichts lesbar ist.
	 */
	public String describeLargestLogPost(int count) {
		try {
			StringBuilder text = new StringBuilder();
			for (Max_Error_Row row : this.databaseHandler.readLargestObservationErrors(count))
				text.append("• ").append(describeObservation(row)).append(" | logPost ")
						.append(String.format(Locale.GERMANY, "%.2f", row.value)).append("\n");
			return text.toString().trim();
		} catch (Exception e) {
			this.logger.log("Klein_Method: groesste logPost-Werte nicht lesbar: " + e.getMessage());
			return "";
		}
	}

	public Set<String> askGrossErrorAssignment(List<Max_Error_Row> excluded) {
		return this.grossErrorPart.askGrossErrorAssignment(excluded);
	}

	public void handleFailedAdjustmentDuringRun(EstimationStateType result, String detail) {
		this.grossErrorPart.handleFailedAdjustmentDuringRun(result, detail);
	}

	private static final Klein_Method_Threshold_Type[] SWARM_TYPES =
			Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST);
	private static final String[] SWARM_COMPONENTS = { "a", "b", "c" };

	public String currentSwarmProjectId() {
		return this.swarmPart.currentSwarmProjectId();
	}

	private static String swarmUnit(Klein_Method_Threshold_Type type, int component) {
		return component == 0 ? Klein_Method_Default_Values.getSigmaAUnit(type)
				: component == 1 ? Klein_Method_Default_Values.getSigmaBUnit(type) : Klein_Method_Default_Values.getSigmaCUnit(type);
	}

	private static String swarmTypeName(Klein_Method_Threshold_Type type) {
		return type.getDisplayName().replace(" logPost", "");
	}

	public void writeRunReport(Klein_Method_Adjustment_Step step, String endReason, boolean done, double runtimeSeconds, boolean discarded) {
		this.swarmPart.writeRunReport(step, endReason, done, runtimeSeconds, discarded);
	}

	public String buildSwarmOverviewText(boolean dynamic) {
		return this.swarmPart.buildSwarmOverviewText(dynamic);
	}

	private static int countProjects(Map<String, Map<String, Double>> values) {
		Set<String> projects = new HashSet<String>();
		for (Map<String, Double> m : values.values())
			projects.addAll(m.keySet());
		return projects.size();
	}

	public boolean checkSwarmPlausibilityAfterPhase1() {
		return this.swarmPart.checkSwarmPlausibilityAfterPhase1();
	}

	public void startSwarmStartValues() {
		this.swarmPart.startSwarmStartValues();
	}

	public void downloadSwarmExperience() {
		this.swarmPart.downloadSwarmExperience();
	}

	public void refreshSwarmExperienceQuietly() {
		this.swarmPart.refreshSwarmExperienceQuietly();
	}

	public void shareLatestRunReport() {
		this.swarmPart.shareLatestRunReport();
	}

	// --------------- Schwarmwissen in der dynamischen AGL ----------------

	private volatile Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class swarmFixedPointClass =
			Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class.SONSTIGE;
	private final Map<Integer, Double> dynamicPhase1Sigma = new java.util.concurrent.ConcurrentHashMap<Integer, Double>();
	private static final String SETTING_FIXED_POINT_CLASS = "FPART";

	public Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class getSwarmFixedPointClass() {
		return this.swarmPart.getSwarmFixedPointClass();
	}

	public void setSwarmFixedPointClass(Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class value) {
		this.swarmPart.setSwarmFixedPointClass(value);
	}

	private void loadSwarmFixedPointClass() {
		this.swarmPart.loadSwarmFixedPointClass();
	}

	public void recordDynamicPhase1Values() {
		this.swarmPart.recordDynamicPhase1Values();
	}

	public void clearDynamicPhase1Values() {
		this.swarmPart.clearDynamicPhase1Values();
	}

	// Gruppenwerte (Median je Typ und Komponente) und VKS am Ende von Durchlauf 1, vor "Gruppen-sigma = Untergrenze"
	private final Map<Klein_Method_Threshold_Type, double[]> pass1GroupSigma = new java.util.concurrent.ConcurrentHashMap<Klein_Method_Threshold_Type, double[]>();
	private final Map<Klein_Method_Threshold_Type, double[]> pass1GroupRatios = new java.util.concurrent.ConcurrentHashMap<Klein_Method_Threshold_Type, double[]>();

	public void recordPass1GroupValues() {
		this.swarmPart.recordPass1GroupValues();
	}

	public boolean checkSwarmPointsAfterPhase2() {
		return this.swarmPart.checkSwarmPointsAfterPhase2();
	}

	/**
	 * Tab-Klick ohne gueltige Ergebnisse: enthaelt das Projekt geozentrische 3D-Punkte, im Hintergrund die Umrechnung
	 * (und danach die Naeherungskoordinaten) anbieten und nach der Umrechnung die Netzausgleichung rechnen - der Tab
	 * wird damit frei. Sonst (oder bei "Nicht umrechnen") der Hinweis "zuerst F5".
	 */
	private void offerConversionOrShowLockedHint() {
		if (!this.isProjectOpen() || this.conversionPart.approximationBusyForLock()) {
			this.showLockedHint();
			return;
		}
		this.approximationCheckRunning = true;
		Thread thread = busyThread(() -> {
			boolean converted = false, geocentric = false;
			ADJUSTMENT_LOCK.lock();
			try {
				geocentric = this.conversionPart.hasGeocentricData();
				if (geocentric) {
					this.logger.log("Klein_Method: Tab-Klick ohne gueltige Ergebnisse, geozentrische Punkte oder 3D-Basislinien im Projekt - Umrechnung wird angeboten.");
					converted = this.checkLocalConversion(false);
					if (converted)
						this.checkApproximationCoordinates(false);
				}
			} catch (Exception e) {
				this.logger.log("Klein_Method: Pruefung auf geozentrische Punkte nicht moeglich: " + e.getMessage());
			} finally {
				ADJUSTMENT_LOCK.unlock();
				this.approximationCheckRunning = false;
			}
			if (converted) {
				this.logger.log("Klein_Method: Projekt umgerechnet - Netzausgleichung im Hintergrund, danach ist der Tab verfuegbar.");
				this.runHeadlessAdjustmentThenRefreshStatus();
			} else
				Platform.runLater(this::showLockedHint);
		}, "Klein_Method_Locked_Tab_Conversion");
		thread.setDaemon(true);
		thread.start();
	}

	private void showLockedHint() {
		Alert alert = new Alert(AlertType.INFORMATION);
		alert.setTitle(tr("Klein_Method"));
		alert.setHeaderText(tr("Klein_Method ist noch nicht verf\u00FCgbar"));
		alert.setContentText(tr("F\u00FCr Klein_Method werden g\u00FCltige Berechnungsergebnisse ben\u00F6tigt.\n"
				+ "Bitte zuerst eine Netzausgleichung (F5) erfolgreich durchf\u00FChren."));
		alert.showAndWait();
	}

	// ============================================================================================================
	/** Koordinaten-Export (Zielsysteme, Dateiformate) und Import von Gitterdateien (NTv2). */
	private final class Export_Part {

		/**
		 * Exportiert die Ergebniskoordinaten (a-posteriori, Tab "Ergebnis") ALLER aktiven Projektpunkte in eine Textdatei
		 * - nur, wenn gerade gueltige Ergebnisse vorliegen (isValidAdjustmentResultsAvailable()).
		 *
		 * Speicherort: derselbe Ordner wie das Protokoll (Klein_Method_Protocol.getProtocolFolderPath()) -
		 * AUSWEICHLOESUNG mangels bekannter Projektordner-Pfad-API, wie beim Protokoll selbst.
		 *
		 * Dateiname: <Zeitstempel>_Koordinaten_<Ausgleichungsart>.txt, z.B.
		 * "2026-07-01_12-56-45_Koordinaten_frei_AGL.txt" (Doppelpunkte im Zeitstempel durch Bindestriche ersetzt -
		 * Windows erlaubt keine Doppelpunkte in Dateinamen, gleiches Muster wie beim Protokoll).
		 *
		 * Format: Punktnummer/Rechtswert/Hochwert/Hoehe/Code, Leerzeichen-getrennt, Spalten rechtsbuendig untereinander,
		 * nach Punktnummer aufsteigend sortiert (bereits per SQL ORDER BY), fehlende Werte als "k.A.", Nachkommastellen
		 * aus der bestehenden Einstellung.
		 *
		 * Zielsystem per Dropdown (nur eindeutige Umformungen ohne Datumstransformation, siehe
		 * Klein_Method_Functions.Klein_Method_Coordinate_Export).
		 */
		public void exportCoordinates() {
			if (!Klein_Method.this.isValidAdjustmentResultsAvailable()) {
				Alert alert = new Alert(AlertType.WARNING);
				alert.setTitle(tr("Klein_Method - Koordinaten-Export"));
				alert.setHeaderText(null);
				alert.setContentText(tr("Export nicht m\u00F6glich: es liegen aktuell keine g\u00FCltigen Ausgleichungsergebnisse vor."));
				alert.showAndWait();
				return;
			}
			if (Klein_Method.this.coordinateExportRunning) {
				Klein_Method.this.logger.log("Klein_Method: Koordinaten-Export laeuft bereits, ignoriert.");
				return;
			}
			Klein_Method.this.coordinateExportRunning = true;

			Thread thread = busyThread(() -> {
				try {
					Klein_Method_Adjustment_Step step = Klein_Method.this.getCurrentAdjustmentStep();
					Klein_Method_Coordinate_Export_Format format = Klein_Method.this.parameter.getCoordinateExportFormat();
					List<Klein_Method_Point_Coordinate_Row> projectRows = Klein_Method.this.databaseHandler.readAllActivePointCoordinates();
					// Umformung in das gewaehlte Zielsystem
					Export_Plan plan = this.coordinateExportPlan(projectRows);
					List<Klein_Method_Point_Coordinate_Row> rows = new ArrayList<Klein_Method_Point_Coordinate_Row>();
					int notConverted = 0;
					// Normalhoehen (H = h - N) nur, wo die Hoehe ellipsoidisch vorliegt (Quelle lokal um P0 oder geozentrisch) und das
					// Zielsystem eine Hoehenspalte mit Lage hat (geographisch, UTM, Definitionen)
					Klein_Method_Functions.Klein_Method_Quasigeoid.Model geoidModel = this.normalHeightModel(plan);
					Klein_Method_Functions.Klein_Method_Coordinate_Export.Option geographic = new Klein_Method_Functions.Klein_Method_Coordinate_Export.Option(
							Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.GEOGRAPHIC, null);
					int outsideModel = 0;
					for (Klein_Method_Point_Coordinate_Row r : projectRows) {
						double[] c = null;
						if (r.getRechtswert() != null && r.getHochwert() != null && r.getHoehe() != null)
							c = Klein_Method_Functions.Klein_Method_Coordinate_Export.convert(plan.source, plan.option, r.getRechtswert(), r.getHochwert(), r.getHoehe(),
									plan.projectLocal, plan.targetLocal, plan.definitions);
						if (c != null && geoidModel != null) {
							double[] g = Klein_Method_Functions.Klein_Method_Coordinate_Export.convert(plan.source, geographic, r.getRechtswert(), r.getHochwert(), r.getHoehe(),
									plan.projectLocal, plan.targetLocal, plan.definitions);
							double n = g == null ? Double.NaN : geoidModel.undulation(g[0], g[1]);
							if (Double.isFinite(n))
								c = new double[] { c[0], c[1], c[2] - n };
							else {
								rows.add(new Klein_Method_Point_Coordinate_Row(r.getName(), r.getCode(), c[0], c[1], null));
								outsideModel++;
								continue;
							}
						}
						if (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT)
							rows.add(r);
						else if (c == null) {
							rows.add(new Klein_Method_Point_Coordinate_Row(r.getName(), r.getCode(), null, null, null));
							notConverted++;
						}
						else
							rows.add(new Klein_Method_Point_Coordinate_Row(r.getName(), r.getCode(), c[0], c[1], c[2]));
					}
					int decimals = Klein_Method.this.parameter.getDecimalPlaces();
					boolean geographicTarget = plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.GEOGRAPHIC
							|| (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.DEFINITION && plan.option.definition.isGeographic());
					int planarDecimals = geographicTarget ? Math.max(9, decimals + 5) : decimals;
					String note = plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT ? null : this.coordinateExportNote(plan);
					if (note != null) {
						String levels = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_GNSS_SUPPORT_LEVELS);
						List<String> red = new ArrayList<String>();
						if (levels != null)
							for (String item : levels.split(";"))
								if (item.endsWith(":2"))
									red.add(item.substring(0, item.length() - 2));
						if (!red.isEmpty())
							note += "\n# GNSS-Stuetzung: " + red.size() + " Punkt(e) ausserhalb der GNSS-Stuetzung (extrapoliert, nur ueber Tachymeter bestimmt): "
									+ String.join(", ", red.subList(0, Math.min(40, red.size()))) + (red.size() > 40 ? ", ..." : "");
					}
					if (note != null && geoidModel != null)
						note = note.replace(" | Hoehe: " + Klein_Method_Functions.Klein_Method_Coordinate_Export.heightNote(plan.option, plan.source),
								" | Hoehe: Normalhoehe NHN (H = h - N)");
					if (note != null && geoidModel != null)
						note += "\n# Hoehen: Normalhoehen H = h - N, Hoehenanomalie N aus Quasigeoidmodell " + geoidModel.title()
								+ (geoidModel.license.isEmpty() ? "" : " - " + geoidModel.license) + (outsideModel > 0 ? " (" + outsideModel + " Punkt(e) ausserhalb des Modells: Hoehe k.A.)" : "");
					String content = format == Klein_Method_Coordinate_Export_Format.CSV
							? this.buildCoordinateCsvContent(rows, planarDecimals, Klein_Method_Functions.Klein_Method_Coordinate_Export.columns(plan.option, plan.source), note)
							: this.buildCoordinateFileContent(rows, planarDecimals, note);

					String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
					String fileName = timestamp + "_Koordinaten_" + this.getAdjustmentStepFileSuffix(step)
							+ (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT ? "" : "_" + plan.option.fileTag())
							+ (geoidModel != null ? "_NH" : "") + "." + format.getFileExtension();

					File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
					if (!folder.exists())
						folder.mkdirs();
					File file = new File(folder, fileName);

					try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
						writer.print(content);
					}

					Klein_Method.this.logger.log("Klein_Method: " + rows.size() + " Koordinate(n) exportiert nach " + file.getAbsolutePath() + " - System: "
							+ plan.option.displayName() + (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT ? "" : " (aus " + plan.source.label + ")")
							+ (notConverted > 0 ? ", " + notConverted + " Punkt(e) nicht umformbar (k.A.)" : "")
							+ (geoidModel != null ? ", Normalhoehen mit " + geoidModel.title() + (outsideModel > 0 ? " (" + outsideModel + " ausserhalb)" : "") : ""));
					if (geoidModel != null && Klein_Method.this.isProjectOpen())
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_QUASIGEOID_USED, geoidModel.title());
					int skipped = notConverted;
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Koordinaten-Export"));
						alert.setHeaderText(null);
						alert.setContentText(rows.size() + tr(" Punkte exportiert nach:\n") + file.getAbsolutePath()
								+ (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT ? "" : "\n" + tr("System: ")
										+ (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.DEFINITION ? plan.option.displayName() : tr(plan.target.displayName)))
								+ (skipped > 0 ? "\n" + String.format(tr("%d Punkt(e) liegen nicht im Projektsystem oder außerhalb des Gitters und wurden nicht umgeformt (k.A.)."), skipped) : ""));
						alert.showAndWait();
					});
				}
				catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Koordinaten-Export fehlgeschlagen: " + e.getMessage());
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.ERROR);
						alert.setTitle(tr("Klein_Method - Koordinaten-Export"));
						alert.setHeaderText(null);
						alert.setContentText(tr("Export fehlgeschlagen: ") + e.getMessage());
						alert.showAndWait();
					});
				}
				finally {
					Klein_Method.this.coordinateExportRunning = false;
				}
			}, "Klein_Method_Coordinate_Export");
			thread.setDaemon(true);
			thread.start();
		}
		private String getAdjustmentStepFileSuffix(Klein_Method_Adjustment_Step step) {
			switch (step) {
			case FREE: return "frei_AGL";
			case DYNAMIC: return "dynamisch_AGL";
			case FIXED: return "fix_AGL";
			default: return "unbekannt_AGL";
			}
		}
		/**
		 * Baut den Dateiinhalt: Leerzeichen-getrennt, Spalten rechtsbuendig
		 * (Spaltenbreite je Spalte dynamisch aus dem laengsten Wert dieser
		 * Spalte ermittelt), kein Header, keine Zeile am Ende leer.
		 */
		private String buildCoordinateFileContent(List<Klein_Method_Point_Coordinate_Row> rows, int planarDecimals, String note) {
			int decimalPlaces = Klein_Method.this.parameter.getDecimalPlaces();

			String[] nameCol = new String[rows.size()];
			String[] rechtswertCol = new String[rows.size()];
			String[] hochwertCol = new String[rows.size()];
			String[] hoeheCol = new String[rows.size()];
			String[] codeCol = new String[rows.size()];

			for (int i = 0; i < rows.size(); i++) {
				Klein_Method_Point_Coordinate_Row row = rows.get(i);
				nameCol[i] = row.getName() == null || row.getName().isEmpty() ? "k.A." : row.getName();
				// Fuer den Export explizit Locale.US erzwingen, damit der Punkt als Dezimaltrenner immer verwendet wird
				// (unabhaengig von der Systemsprache).
				rechtswertCol[i] = row.getRechtswert() == null ? "k.A." : String.format(Locale.US, "%." + planarDecimals + "f", row.getRechtswert());
				hochwertCol[i] = row.getHochwert() == null ? "k.A." : String.format(Locale.US, "%." + planarDecimals + "f", row.getHochwert());
				hoeheCol[i] = row.getHoehe() == null ? "k.A." : String.format(Locale.US, "%." + decimalPlaces + "f", row.getHoehe());
				codeCol[i] = row.getCode() == null || row.getCode().isEmpty() ? "k.A." : row.getCode();
			}

			int nameWidth = this.maxLength(nameCol);
			int rechtswertWidth = this.maxLength(rechtswertCol);
			int hochwertWidth = this.maxLength(hochwertCol);
			int hoeheWidth = this.maxLength(hoeheCol);
			int codeWidth = this.maxLength(codeCol);

			StringBuilder content = new StringBuilder();
			if (note != null)
				content.append(note).append(System.lineSeparator());
			for (int i = 0; i < rows.size(); i++) {
				content.append(String.format(Locale.US, "%" + nameWidth + "s %" + rechtswertWidth + "s %" + hochwertWidth + "s %" + hoeheWidth + "s %" + codeWidth + "s",
						nameCol[i], rechtswertCol[i], hochwertCol[i], hoeheCol[i], codeCol[i]));
				content.append(System.lineSeparator());
			}
			return content.toString();
		}
		/**
		 * CSV-Variante des Koordinaten-Exports. Komma als Feldtrenner, Kopfzeile, "." als Dezimaltrenner - unabhaengig
		 * von der Systemsprache.
		 */
		private String buildCoordinateCsvContent(List<Klein_Method_Point_Coordinate_Row> rows, int planarDecimals, String[] columns, String note) {
			int decimalPlaces = Klein_Method.this.parameter.getDecimalPlaces();

			StringBuilder content = new StringBuilder();
			if (note != null)
				content.append(note).append(System.lineSeparator());
			content.append("Name," + columns[0] + "," + columns[1] + "," + columns[2] + ",Code").append(System.lineSeparator());

			for (Klein_Method_Point_Coordinate_Row row : rows) {
				String name = row.getName() == null || row.getName().isEmpty() ? "" : row.getName();
				String rechtswert = row.getRechtswert() == null ? "" : String.format(Locale.US, "%." + planarDecimals + "f", row.getRechtswert());
				String hochwert = row.getHochwert() == null ? "" : String.format(Locale.US, "%." + planarDecimals + "f", row.getHochwert());
				String hoehe = row.getHoehe() == null ? "" : String.format(Locale.US, "%." + decimalPlaces + "f", row.getHoehe());
				String code = row.getCode() == null || row.getCode().isEmpty() ? "" : row.getCode();

				content.append(String.join(",", name, rechtswert, hochwert, hoehe, code));
				content.append(System.lineSeparator());
			}
			return content.toString();
		}
		/**
		 * ermittelt Koordinatenart und die eindeutig ableitbaren Zielsysteme. Das gewaehlte System gilt nur, wenn es fuer
		 * dieses Projekt verfuegbar ist, sonst das Projektsystem (Anzeige im Dropdown und Export identisch).
		 */
		public Export_Plan coordinateExportPlan(List<Klein_Method_Point_Coordinate_Row> rows) throws SQLException {
			Export_Plan plan = new Export_Plan();
			plan.projectLocal = Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
			if (plan.projectLocal == null) {
				// Projekt ohne gespeichertes P0 (z. B. mit dem VBA-Tool umgerechnet) - Grundwerte annehmen, wenn das Netz
				// geodaetisch bezogen ist (3D-GNSS-Basislinien) und die Netzmitte hoechstens 50 km von P0 liegt (wie
				// relocateP0)
				Klein_Method_Functions.Klein_Method_Local_System.Parameters defaults = Klein_Method.this.parameter.getLocalSystem().copy();
				List<Double> easts = new ArrayList<Double>(), norths = new ArrayList<Double>();
				for (Klein_Method_Point_Coordinate_Row r : rows)
					if (r.getRechtswert() != null && r.getHochwert() != null && r.getHoehe() != null
							&& Klein_Method_Functions.Klein_Method_Local_System.inLocalSystem(defaults, r.getRechtswert(), r.getHochwert(), r.getHoehe())) {
						easts.add(r.getRechtswert() - defaults.addEast);
						norths.add(r.getHochwert() - defaults.addNorth);
					}
				boolean gnss3d = false;
				for (double[] b : Klein_Method.this.databaseHandler.readLocalConversionBaselines())
					gnss3d |= (int) b[1] == ObservationType.GNSS3D.getId();
				if (gnss3d && easts.size() >= 3 && Math.hypot(Klein_Method_Functions.Klein_Method_Swarm.median(easts),
						Klein_Method_Functions.Klein_Method_Swarm.median(norths)) <= 50000.0) {
					plan.projectLocal = defaults;
					plan.projectLocalAssumed = true;
				}
			}
			plan.targetLocalFromProject = plan.projectLocal != null && !plan.projectLocalAssumed;
			plan.targetLocal = plan.projectLocal != null ? plan.projectLocal : Klein_Method.this.parameter.getLocalSystem();
			List<double[]> yxz = new ArrayList<double[]>();
			for (Klein_Method_Point_Coordinate_Row r : rows)
				if (r.getRechtswert() != null && r.getHochwert() != null && r.getHoehe() != null)
					yxz.add(new double[] { r.getRechtswert(), r.getHochwert(), r.getHoehe() });
			// Definitionen des Projektionsordners (Gitter-Vorlagen werden angelegt, sobald die Gitterdatei dort liegt)
			for (String note : Klein_Method_Functions.Klein_Method_Geo.ensureProjectionFolder())
				Klein_Method.this.logger.log("Klein_Method: Projektionsordner - " + note);
			List<String> errors = new ArrayList<String>();
			for (Klein_Method_Functions.Klein_Method_Geo.Projection_Definition d : Klein_Method_Functions.Klein_Method_Geo.loadProjectionDefinitions(errors))
				if (d.exportAllowed())
					plan.definitions.add(d);
			plan.source = Klein_Method_Functions.Klein_Method_Coordinate_Export.detect(yxz, plan.projectLocal, plan.definitions);
			plan.available = Klein_Method_Functions.Klein_Method_Coordinate_Export.available(plan.source, plan.definitions);
			String wanted = Klein_Method.this.parameter.getCoordinateExportTarget();
			plan.option = plan.available.get(0);
			for (Klein_Method_Functions.Klein_Method_Coordinate_Export.Option o : plan.available)
				if (o.key().equals(wanted))
					plan.option = o;
			plan.target = plan.option.target;
			return plan;
		}
		/**
		 * nimmt eine NTv2-Datei (*.gsb) oder das Zip des Landesamts an. Jede .gsb wird geprueft (NTv2, GS_TYPE SECONDS,
		 * Zielsystem ETRS89/GRS80) und in den Projektionsordner gelegt - dort wird sie im Original gelesen; weitere
		 * Einstellungen gibt es nicht (die Systeme entstehen aus dem Dateikopf). Liegen im Zip Pruefdaten (CSV mit
		 * geographischen Start-/Zielkoordinaten), wird das Gitter sofort damit nachgerechnet.
		 */
		public Grid_Import_Result importGridFile(java.io.File source, boolean copy) throws java.io.IOException {
			Grid_Import_Result result = new Grid_Import_Result();
			java.io.File folder = Klein_Method_Functions.Klein_Method_Geo.projectionFolder();
			Klein_Method_Functions.Klein_Method_Geo.ensureProjectionFolder();
			List<java.io.File> imported = new ArrayList<java.io.File>();
			List<String> csvTexts = new ArrayList<String>();
			if (source.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
				try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source)) {
					java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
					while (entries.hasMoreElements()) {
						java.util.zip.ZipEntry entry = entries.nextElement();
						if (entry.isDirectory())
							continue;
						String name = new java.io.File(entry.getName()).getName();
						String lower = name.toLowerCase(Locale.ROOT);
						if (lower.endsWith(".gsb")) {
							java.io.File temporary = java.io.File.createTempFile("klein_method_grid", ".gsb");
							temporary.deleteOnExit();
							try (java.io.InputStream in = zip.getInputStream(entry)) {
								java.nio.file.Files.copy(in, temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
							}
							imported.add(this.storeGridFile(temporary, name, folder, true));
							temporary.delete();
						}
						else if (lower.endsWith(".csv")) {
							try (java.io.InputStream in = zip.getInputStream(entry)) {
								csvTexts.add(name + "\u0000" + new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
							}
						}
					}
				}
				if (imported.isEmpty())
					throw new java.io.IOException("Im Zip ist keine NTv2-Datei (*.gsb) - nur das Binaerformat .gsb wird unterstuetzt.");
			}
			else
				imported.add(this.storeGridFile(source, source.getName(), folder, copy));

			List<String> errors = new ArrayList<String>();
			List<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition> definitions = Klein_Method_Functions.Klein_Method_Geo.gridDefinitions(errors);
			for (java.io.File file : imported) {
				for (String error : errors)
					if (error.contains(file.getName()))
						throw new java.io.IOException(error);
				List<String> names = new ArrayList<String>();
				for (Klein_Method_Functions.Klein_Method_Geo.Projection_Definition d : definitions)
					if (file.getName().equals(d.gridName()))
						names.add(d.name);
				result.systems.put(file.getName(), names);
				Klein_Method_Functions.Klein_Method_Geo.NTv2_Grid grid = Klein_Method_Functions.Klein_Method_Geo.NTv2_Grid.of(file);
				for (String csv : csvTexts) {
					int cut = csv.indexOf('\u0000');
					String check = Klein_Method_Functions.Klein_Method_Geo.verifyGrid(grid, csv.substring(0, cut), csv.substring(cut + 1));
					if (check != null)
						result.checks.add(check);
				}
				Klein_Method.this.logger.log("Klein_Method: Gitterdatei " + file.getName() + " (" + grid.systemFrom + " -> " + grid.systemTo + ") im Projektionsordner - Systeme: "
						+ String.join(", ", names));
			}
			for (String check : result.checks)
				Klein_Method.this.logger.log("Klein_Method: Gitterpruefung - " + check);
			return result;
		}
		/** eine .gsb pruefen und unter ihrem Namen in den Projektionsordner legen (copy = false: liegt schon dort). */
		private java.io.File storeGridFile(java.io.File file, String name, java.io.File folder, boolean copy) throws java.io.IOException {
			Klein_Method_Functions.Klein_Method_Geo.NTv2_Grid header;
			try {
				header = Klein_Method_Functions.Klein_Method_Geo.NTv2_Grid.of(file);
			} catch (IllegalArgumentException e) {
				throw new java.io.IOException(name + ": " + e.getMessage());
			}
			if (!header.targetIsGrs80())
				throw new java.io.IOException(name + ": Zielsystem " + header.systemTo + " ist nicht ETRS89/GRS80 - Gitter nicht verwendbar.");
			java.io.File target = new java.io.File(folder, name);
			if (copy) {
				java.io.File temporary = new java.io.File(folder, name + ".tmp");
				java.nio.file.Files.copy(file.toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return target;
		}
		/** fuer das Dropdown (Hintergrund-Thread): verfuegbare Zielsysteme des geoeffneten Projekts. */
		public Export_Plan readCoordinateExportPlan() {
			try {
				return this.coordinateExportPlan(Klein_Method.this.databaseHandler.readAllActivePointCoordinates());
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Koordinatensysteme fuer den Export nicht ermittelbar: " + e.getMessage());
				return new Export_Plan();
			}
		}
		/** Hinweiszeile fuer den Dateikopf (deutsch, "#" vorn). */
		/**
		 * aktives Quasigeoidmodell fuer den Export mit Normalhoehen - nur wenn eingeschaltet, die Projekthoehen ellipsoidisch
		 * sind (lokal um P0 oder geozentrisch) und das Ziel eine Hoehe neben Lage hat (geographisch, UTM, Definition); sonst null.
		 */
		private Klein_Method_Functions.Klein_Method_Quasigeoid.Model normalHeightModel(Export_Plan plan) {
			if (!Klein_Method.this.parameter.isCoordinateExportNormalHeight())
				return null;
			return heightChoiceApplies(plan.source, plan.target) ? Klein_Method_Functions.Klein_Method_Quasigeoid.active() : null;
		}

		private String coordinateExportNote(Export_Plan plan) {
			String[] col = Klein_Method_Functions.Klein_Method_Coordinate_Export.columns(plan.option, plan.source);
			StringBuilder note = new StringBuilder("# Koordinatensystem: ").append(plan.option.displayName());
			if (plan.option.isGridTransformation())
				note.append(" - Datumsuebergang (Transformation) ueber das NTv2-Gitter ").append(plan.option.definition.gridName())
						.append(", PROJ: ").append(plan.option.definition.definition);
			else if (plan.source == Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.DEFINITION)
				note.append(" - Datum ETRS89, GRS80; Startsystem ueber NTv2-Gitter in ETRS89 transformiert");
			else if (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.DEFINITION)
				note.append(" - Datum wie die Festpunkte (ETRS89 bzw. WGS84), GRS80; nur Umformung, PROJ: ").append(plan.option.definition.definition);
			else if (plan.target != Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.LOCAL)
				note.append(" - Datum wie die Festpunkte (ETRS89 bzw. WGS84), GRS80; nur Umformung, keine Datumstransformation");
			note.append(" | umgeformt aus: ").append(plan.source.label);
			if (plan.source == Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.DEFINITION) {
				java.util.Set<String> used = new java.util.LinkedHashSet<String>();
				for (Klein_Method_Functions.Klein_Method_Geo.Projection_Definition d : plan.definitions)
					if (d.hasGrid())
						used.add(d.name + " (" + d.gridName() + ")");
				note.append(" ").append(used);
			}
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = plan.source == Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.LOCAL_P0 ? plan.projectLocal
					: plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.LOCAL ? plan.targetLocal : null;
			if (p != null)
				note.append(String.format(Locale.US, " (P0 B=%.10f L=%.10f h=%.4f, Additionskonstanten %.3f/%.3f/%.3f%s)", p.latitude, p.longitude, p.height,
						p.addEast, p.addNorth, p.addUp, plan.projectLocalAssumed && p == plan.projectLocal
								? ", ANGENOMMEN: Grundwerte, kein P0 im Projekt gespeichert - bitte P0 ins Netz legen"
								: plan.source == Klein_Method_Functions.Klein_Method_Coordinate_Export.Source.LOCAL_P0 || plan.targetLocalFromProject ? ", aus dem Projekt"
										: ", Einstellung der Koordinatenumrechnung"));
			note.append(" | Hoehe: ").append(Klein_Method_Functions.Klein_Method_Coordinate_Export.heightNote(plan.option, plan.source));
			note.append(" | Spalten: Name ").append(col[0]).append(' ').append(col[1]).append(' ').append(col[2]).append(" Code");
			if (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.GEOGRAPHIC
					|| (plan.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.DEFINITION && plan.option.definition.isGeographic()))
				note.append(" (Grad dezimal)");
			return note.toString();
		}
		private int maxLength(String[] values) {
			int max = 0;
			for (String value : values)
				max = Math.max(max, value.length());
			return max;
		}	}

	// ============================================================================================================
	/** Umrechnung des Projekts ins lokale System (ENh um P0), P0-Verlegung und Ruecknahme. */
	private final class Conversion_Part {

		/** Menue JAG3D / Knopf im Tab: Fenster "Koordinatenumrechnung" (auch ohne Projekt). */
		public void openCoordinateConversion() {
			Klein_Method_Tab_Builder.Klein_Method_Conversion_Window.open(Klein_Method.this);
		}
		/** Projekt pruefen (DB offen, Sperre beim Aufrufer). */
		private Local_Conversion_Plan planLocalConversion() throws SQLException {
			Local_Conversion_Plan plan = new Local_Conversion_Plan();
			List<double[]> ecef = new ArrayList<double[]>();
			List<double[]> rows = Klein_Method.this.databaseHandler.readLocalConversionPoints();
			for (double[] r : rows) {
				// r = {id, Dimension, y0 (= geozentrisch Y), x0 (= X), z0 (= Z)}
				if ((int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]))
					ecef.add(r);
				else
					plan.otherPoints++;
			}
			Klein_Method_Functions.Klein_Method_Local_System.Parameters stored = Klein_Method_Functions.Klein_Method_Local_System.Parameters
					.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
			// gespeicherter P0 veraltet? Kein Punkt liegt mehr in seinem lokalen System (alle Daten neu, geozentrisch bzw. UTM -
			// z. B. neue Daten in ein frueheres Projekt importiert). Dann wie ein neues Projekt: P0 neu im Schwerpunkt bestimmen.
			// 1D-Punkte genau in P0 (Lage unbekannt) zaehlen nicht.
			if (stored != null && !ecef.isEmpty()) {
				boolean anyLocal = false;
				for (double[] r : rows) {
					boolean atP0 = Math.abs(r[2] - stored.addEast) < 1.0e-3 && Math.abs(r[3] - stored.addNorth) < 1.0e-3;
					if ((r[2] == 0.0 && r[3] == 0.0) || ((int) r[1] == 1 && atP0))
						continue;
					if ((int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]))
						continue;
					anyLocal |= stored.looksLocal(r[2], r[3]);
				}
				if (!anyLocal) {
					plan.staleP0 = stored.encode();
					Klein_Method.this.logger.log("Klein_Method: gespeicherter P0 " + stored.encode() + " gehoert zu frueheren Daten (kein Punkt mehr im lokalen System) - P0 wird neu bestimmt.");
					Klein_Method.this.getProtocol().logEvent("KOORDINATENUMRECHNUNG: gespeicherter P0 " + stored.encode() + " veraltet (kein Punkt im lokalen System) - neu bestimmt wie bei einem neuen Projekt");
					stored = null;
				}
			}
			// jede 3D-Basislinie einzeln einordnen - unabhaengig von der Importreihenfolge wird nur umgerechnet, was noch geozentrisch ist
			List<double[]> baselineRows = Klein_Method.this.databaseHandler.readLocalConversionBaselines();
			Klein_Method_Functions.Klein_Method_Local_System.Baseline_Classes classes = this.classifyBaselines(rows, baselineRows, stored, !ecef.isEmpty());
			int geocentricBaselines = classes.countGeocentric();
			// 1D-Hoehenpunkte, die noch nicht lokal sind (erste Umrechnung: alle; spaeter: nachimportierte)
			List<double[]> oneD = new ArrayList<double[]>();
			for (double[] r : rows)
				if ((int) r[1] == 1 && this.oneDimensionalCandidate(r, stored))
					oneD.add(r);
			if (ecef.isEmpty() && (stored == null || (geocentricBaselines == 0 && oneD.isEmpty()))) {
				plan.notPossible = tr("Keine geozentrischen 3D-Punkte im Projekt - es gibt nichts umzurechnen.");
				return plan;
			}
			List<double[]> geocentricRows = new ArrayList<double[]>();
			for (double[] b : baselineRows)
				if (Boolean.TRUE.equals(classes.geocentric.get((int) b[0])))
					geocentricRows.add(b);
			plan.zenithLeveling = Klein_Method.this.databaseHandler.readZenithAndLevelingCount();
			// P0 = Schwerpunkt der Neupunkte (ggf. ueber geozentrische Basislinien geschaetzt); Festpunkte zaehlen nicht. Ein
			// schon umgerechnetes Projekt behaelt seinen gespeicherten P0 (die lokalen Punkte passen nur dazu).
			List<double[]> center = Klein_Method_Functions.Klein_Method_Local_System.centerPoints(rows, geocentricRows);
			if (stored != null) {
				plan.parameters = stored.copy();
				plan.distance = 0.0;
				if (!center.isEmpty())
					plan.distance = (Double) Klein_Method_Functions.Klein_Method_Local_System.chooseReference(center, stored, Double.MAX_VALUE)[1];
			}
			else if (center.isEmpty()) {
				// noch kein Neupunkt im Projekt: Grundwerte; sobald Neupunkte da sind, wird das Verlegen angeboten
				plan.parameters = Klein_Method.this.parameter.getLocalSystem().copy();
				plan.defaultsUsed = true;
			}
			else {
				Object[] choice = Klein_Method_Functions.Klein_Method_Local_System.chooseReference(center, Klein_Method.this.parameter.getLocalSystem(), 0.0);
				plan.parameters = (Klein_Method_Functions.Klein_Method_Local_System.Parameters) choice[0];
				plan.distance = (Double) choice[1];
				plan.centroidPoints = (Integer) choice[3];
				plan.centroidDropped = (Integer) choice[4];
			}
			plan.storedUsed = stored != null;
			double[][] planar = new double[ecef.size()][];
			for (int i = 0; i < ecef.size(); i++) {
				double[] r = ecef.get(i);
				double[] local = plan.parameters.toLocal(r[3], r[2], r[4]);
				plan.points.put((int) r[0], new double[] { local[0], local[1], local[2] }); // y0 = Ost, x0 = Nord, z0 = Oben
				planar[i] = new double[] { local[0], local[1] };
			}
			plan.extent = planar.length > 1 ? Klein_Method_Functions.Klein_Method_Geo.maxExtent(planar) : 0.0;
			this.networkPlanar = planar;
			// 1D-Punkte: Lage aus der eingetragenen Lage (UTM, Gauss-Krueger, geographisch), Hoehe wie eingetragen; lokal
			// ist Z die Koordinate senkrecht zur Tangentialebene in P0 (Hoehe minus Erdkruemmungsanteil)
			if (!oneD.isEmpty()) {
				Map<Integer, String> names = Klein_Method.this.databaseHandler.readPointNames();
				for (double[] r : oneD) {
					double[] local = this.oneDimensionalToLocal(r, plan.parameters);
					if (local == null)
						continue;
					plan.points.put((int) r[0], new double[] { local[0], local[1], local[2] });
					plan.oneDimensional.put((int) r[0], (int) local[3]);
					plan.otherPoints = Math.max(0, plan.otherPoints - 1);
					if ((int) local[3] == 3)
						plan.oneDimensionalUnknownNames.add(names.getOrDefault((int) r[0], "#" + (int) r[0]));
				}
			}
			plan.unclearBaselines = classes.assumed;
			for (double[] b : baselineRows) {
				// b = {id, Typ, y0 = dY, x0 = dX, z0 = dZ}
				if ((int) b[1] == ObservationType.GNSS3D.getId() && !Boolean.TRUE.equals(classes.geocentric.get((int) b[0])))
					plan.localBaselines++;
				else if ((int) b[1] == ObservationType.GNSS3D.getId()) {
					double[] d = plan.parameters.vectorToLocal(b[3], b[2], b[4]);
					plan.baselines.put((int) b[0], new double[] { plan.parameters.round(d[0]), plan.parameters.round(d[1]), plan.parameters.round(d[2]) });
				}
				else
					plan.otherBaselines++;
			}
			// Hoehenpruefung im Messgebiet: Punkte, deren ellipsoidische Hoehe stark von den GNSS-Punkten abweicht (Hoehe 0 oder NHN statt
			// ellipsoidisch) - im lokalen System verschiebt ein Hoehenfehler auch die Lage (Fehler x Abstand zu P0 / Erdradius)
			java.util.Set<Integer> gnssIds = new HashSet<Integer>();
			for (double[] b : baselineRows)
				if ((int) b[1] == ObservationType.GNSS3D.getId() && b.length > 6) {
					gnssIds.add((int) b[5]);
					gnssIds.add((int) b[6]);
				}
			Map<Integer, Double> heights = new LinkedHashMap<Integer, Double>();
			List<Double> gnssHeights = new ArrayList<Double>(), allHeights = new ArrayList<Double>();
			for (double[] r : ecef) {
				// nur Punkte im Messgebiet (bis HEIGHT_CHECK_RADIUS um P0) - ferne Referenzstationen haben echte Hoehenunterschiede
				double[] local = plan.parameters.toLocal(r[3], r[2], r[4]);
				if (Math.hypot(local[0] - plan.parameters.addEast, local[1] - plan.parameters.addNorth) > Klein_Method_Functions.Klein_Method_Local_System.HEIGHT_CHECK_RADIUS)
					continue;
				double h = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(r[3], r[2], r[4])[2];
				heights.put((int) r[0], h);
				allHeights.add(h);
				if (gnssIds.contains((int) r[0]))
					gnssHeights.add(h);
			}
			if (!allHeights.isEmpty()) {
				plan.referenceHeight = Klein_Method_Functions.Klein_Method_Swarm.median(gnssHeights.size() >= 3 ? gnssHeights : allHeights);
				for (Map.Entry<Integer, Double> e : heights.entrySet())
					if (Math.abs(e.getValue() - plan.referenceHeight) > Klein_Method_Functions.Klein_Method_Local_System.HEIGHT_CHECK_LIMIT)
						plan.heightSuspects.put(e.getKey(), e.getValue());
				if (!plan.heightSuspects.isEmpty()) {
					Map<Integer, String> names = Klein_Method.this.databaseHandler.readPointNames();
					for (Integer id : plan.heightSuspects.keySet())
						plan.heightSuspectNames.add(names.getOrDefault(id, "#" + id));
				}
			}
			return plan;
		}
		/**
		 * Projekt pruefen und nach Bestaetigung umrechnen (Hintergrund, ADJUSTMENT_LOCK beim Aufrufer). Automatisch
		 * (force = false) nur bei geozentrischen Punkten MIT 3D-GNSS-Basislinien, nicht bei JAG3D-Projektion "lokal
		 * ellipsoidisch", nicht nach einer Umrechnung und nicht nach "Nicht umrechnen".
		 *
		 * @return true, wenn umgerechnet wurde
		 */
		private boolean checkLocalConversion(boolean force) {
			try {
				if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen()) {
					if (force)
						Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet - die Projekt-Umrechnung braucht ein geöffnetes JAG3D-Projekt."));
					return false;
				}
				if (!this.hasGnssBaselines()) {
					this.refuseWithoutGnss("Umrechnung ins lokale System", force);
					return false;
				}
				// auch bei "lokal ellipsoidisch": JAG3D erwartet dort ebenfalls lokale Koordinaten um den Bezugspunkt
				if (!force) {
					// schon umgerechnet: nur, wenn wieder geozentrische Punkte dazugekommen sind (dann mit demselben P0)
					// (Punkte oder 3D-Basislinien - die Reihenfolge des Imports spielt keine Rolle)
					if (Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM)) != null
							&& !this.hasGeocentricData())
						return false;
					// "Nicht umrechnen" gilt nur, solange das Projekt noch nie umgerechnet ist - geozentrische Daten neben lokalen
					// waeren falsch, darum wird dann wieder gefragt
					if ("true".equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_DECLINED))
							&& Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM)) == null)
						return false;
				}
				Local_Conversion_Plan plan = this.planLocalConversion();
				if (plan.notPossible != null) {
					if (force)
						Klein_Method.this.showApproximationInfo(plan.notPossible);
					return false;
				}
				// noch nie umgerechnet: automatisch nur mit geozentrischen 3D-Basislinien; schon umgerechnet: auch nachimportierte Punkte
				if (!force && plan.baselines.isEmpty() && !plan.storedUsed)
					return false;
				if (plan.points.isEmpty() && plan.baselines.isEmpty())
					return false;
				Klein_Method.this.logger.log("Klein_Method: Koordinatenumrechnung - " + plan.points.size() + " geozentrische Punkte, " + plan.baselines.size()
						+ " geozentrische 3D-Basislinien (" + plan.localBaselines + " schon lokal" + (plan.unclearBaselines > 0 ? ", davon " + plan.unclearBaselines
								+ " ohne Anhalt angenommen" : "") + "), P0 " + (plan.storedUsed ? "aus frueherer Umrechnung" : plan.defaultsUsed ? "Grundwerte (kein Neupunkt)" : "Schwerpunkt aus " + plan.centroidPoints + " Neupunkten"
								+ (plan.centroidDropped > 0 ? " (" + plan.centroidDropped + " Neupunkte mit stark abweichender Lage oder Hoehe nicht mitgezaehlt)" : "")) + ", Abstand Schwerpunkt-P0 "
						+ String.format(Locale.GERMANY, "%.0f", plan.distance) + " m.");
				if (!this.askLocalConversion(plan)) {
					if (!force)
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_LOCAL_DECLINED, "true");
					Klein_Method.this.logger.log("Klein_Method: Koordinatenumrechnung vom Anwender abgelehnt.");
					if (plan.storedUsed)
						Klein_Method.this.showApproximationInfo(String.format(tr("Achtung: Das Projekt ist schon ins lokale System umgerechnet. %d Punkt(e) und %d "
								+ "3D-Basislinie(n) bleiben jetzt geozentrisch - zusammen mit den lokalen Werten rechnen Näherungen und Ausgleichung falsch. "
								+ "Klein_Method fragt beim nächsten Öffnen, Tab-Klick oder über „Näherungskoordinaten berechnen und prüfen“ (Menü Klein_Method) erneut - "
								+ "oder diese Daten deaktivieren."),
								plan.points.size(), plan.baselines.size()));
					return false;
				}
				String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
				// nachimportierte Punkte: Sicherung der ersten Umrechnung bleibt ("zuruecknehmen" bezieht sich auf sie)
				int[] count;
				if (plan.storedUsed && Klein_Method.this.databaseHandler.readLocalConversionBackupInfo() != null) {
					// nachimportierte geozentrische Werte zur Sicherung - "zuruecknehmen" stellt auch sie wieder her
					Klein_Method.this.databaseHandler.addToLocalConversionBackup(plan.points.keySet(), plan.baselines.keySet());
					count = Klein_Method.this.databaseHandler.updateLocalCoordinates(plan.points, plan.baselines);
				}
				else
					count = Klein_Method.this.databaseHandler.applyLocalConversion(plan.points, plan.baselines, stamp);
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_LOCAL_SYSTEM, plan.parameters.encode());
				this.writePrincipalPoint(plan.parameters, "Umrechnung");
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_LOCAL_DECLINED, "");
				Klein_Method.this.databaseHandler.deleteFreeSolution();
				Klein_Method.this.databaseHandler.clearApproximationBackup();
				Klein_Method.this.logger.log("Klein_Method: Projekt in lokales System (ENh) umgerechnet: " + count[0] + " Punkte, " + count[1] + " Basislinien, P0 "
						+ plan.parameters.encode() + ", Projektion " + (Klein_Method.this.databaseHandler.readProjectionType() == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_ELLIPSOIDAL
								? "lokal ellipsoidisch, Bezugspunkt = P0" : "lokal kartesisch") + " (Sicherung " + stamp + ").");
				Klein_Method.this.getProtocol().logEvent("KOORDINATENUMRECHNUNG: " + count[0] + " Punkte, " + count[1] + " Basislinien nach lokal (ENh), P0 " + plan.parameters.encode());
				if (!plan.heightSuspects.isEmpty())
					Klein_Method.this.logger.log("Klein_Method: Umrechnung - " + plan.heightSuspects.size() + " Punkt(e) mit ellipsoidischer Hoehe mehr als "
							+ Klein_Method_Functions.Klein_Method_Local_System.HEIGHT_CHECK_LIMIT + " m neben den GNSS-Punkten (Median "
							+ String.format(Locale.GERMANY, "%.2f", plan.referenceHeight) + " m): " + String.join(", ", plan.heightSuspectNames) + ".");
				Platform.runLater(Klein_Method_Tab_Builder.Klein_Method_Map_Window::notifyDataChanged);
				return true;
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Koordinatenumrechnung nicht moeglich: " + e.getMessage());
				if (force)
					Klein_Method.this.showApproximationInfo(tr("Vorgang fehlgeschlagen: ") + e.getMessage());
				return false;
			}
		}
		/**
		 * Bezugspunkt (Hauptpunkt) fuer JAG3D "Projektion und Reduktion" aus P0: x0 = Nord-, y0 = Ost-, z0 = Oben-Zuschlag
		 * (lokale Koordinaten von P0), Breite/Laenge [rad], Hoehe, GRS80. Bei "lokal kartesisch" ohne Wirkung, bei "lokal
		 * ellipsoidisch" Grundlage der Lotrichtungs-Reduktion.
		 */
		private double[] principalPointFor(Klein_Method_Functions.Klein_Method_Local_System.Parameters p) {
			double a = Klein_Method_Functions.Klein_Method_Geo.GRS80_A;
			double b = a * (1.0 - Klein_Method_Functions.Klein_Method_Geo.GRS80_F);
			return new double[] { p.addNorth, p.addEast, p.addUp, Math.toRadians(p.latitude), Math.toRadians(p.longitude), p.height, a, b };
		}
		private void writePrincipalPoint(Klein_Method_Functions.Klein_Method_Local_System.Parameters p, String reason) throws SQLException {
			double[] v = this.principalPointFor(p);
			Klein_Method.this.databaseHandler.writePrincipalPoint(v);
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_PRINCIPAL_POINT_DECLINED, "");
			String line = "BEZUGSPUNKT (Projektion und Reduktion) gesetzt (" + reason + "): x0 " + String.format(Locale.GERMANY, "%.3f", v[0]) + " m, y0 "
					+ String.format(Locale.GERMANY, "%.3f", v[1]) + " m, z0 " + String.format(Locale.GERMANY, "%.3f", v[2]) + " m, B "
					+ String.format(Locale.GERMANY, "%.10f", p.latitude) + "°, L " + String.format(Locale.GERMANY, "%.10f", p.longitude) + "°, h "
					+ String.format(Locale.GERMANY, "%.3f", p.height) + " m, GRS80";
			Klein_Method.this.logger.log("Klein_Method: " + line);
			Klein_Method.this.getProtocol().logEvent(line);
		}
		/**
		 * eingestelltes Standard-System (P0), wenn das Projekt keinen gespeicherten P0 hat, aber mindestens 3 lokale
		 * 3D-Punkte darin und keine geozentrischen Punkte enthaelt - sonst null.
		 */
		private Klein_Method_Functions.Klein_Method_Local_System.Parameters assumedLocalSystem() throws SQLException {
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = Klein_Method.this.parameter.getLocalSystem().copy();
			int inside = 0;
			for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints()) {
				if ((int) r[1] != 3)
					continue;
				if (Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]))
					return null;
				if (Klein_Method_Functions.Klein_Method_Local_System.inLocalSystem(p, r[2], r[3], r[4]))
					inside++;
			}
			return inside >= 3 ? p : null;
		}
		/** true, wenn der Bezugspunkt im Projekt zu P0 passt (1 mm, 1e-9 rad, Halbachsen 1 mm). */
		private boolean principalPointMatches(double[] current, double[] wanted) {
			if (current == null)
				return true;
			for (int i = 0; i < 8; i++) {
				double tolerance = (i == 3 || i == 4) ? 1e-9 : 0.001;
				if (Math.abs(current[i] - wanted[i]) > tolerance)
					return false;
			}
			return true;
		}
		/**
		 * Tab-Klick (Hintergrund, ADJUSTMENT_LOCK): Projektion "lokal ellipsoidisch" mit P0 von Klein_Method, aber der
		 * Bezugspunkt in "Projektion und Reduktion" passt nicht -> Rueckfrage, auf P0 setzen. "Nein" wird je P0 gemerkt.
		 */
		public void checkPrincipalPoint() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				if (!this.hasGnssBaselines()) {
					this.refuseWithoutGnss("Projektion/Bezugspunkt anpassen", false);
					return;
				}
				String encoded = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM);
				Klein_Method_Functions.Klein_Method_Local_System.Parameters p = Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(encoded);
				// von Klein_Method umgerechnetes Projekt noch "lokal kartesisch": automatisch auf "lokal ellipsoidisch" mit
				// Bezugspunkt = P0 umstellen (Zenitwinkel, Richtungen, Strecken und Nivellement werden dann auf die
				// Lotrichtung jedes Punkts reduziert; reine GNSS-Netze rechnen identisch)
				if (p != null && Klein_Method.this.databaseHandler.readProjectionType()
						== org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_CARTESIAN) {
					Klein_Method.this.databaseHandler.writeProjectionType(org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_ELLIPSOIDAL);
					this.writePrincipalPoint(p, "Projektion automatisch auf lokal ellipsoidisch umgestellt");
					Klein_Method.this.logger.log("Klein_Method: Projektion von lokal kartesisch auf lokal ellipsoidisch umgestellt (Bezugspunkt = P0 "
							+ p.encode() + ").");
					Klein_Method.this.getProtocol().logEvent("PROJEKTION: lokal kartesisch -> lokal ellipsoidisch, Bezugspunkt = P0 " + p.encode());
					Klein_Method.this.showApproximationInfo(String.format(Locale.GERMANY, tr("Die Projektion wurde auf „lokal ellipsoidisch“ umgestellt, Bezugspunkt = P0 "
							+ "(B %.5f°, L %.5f°, h %.2f m). Zenitwinkel, Richtungen, Strecken und Nivellement werden damit auf die Lotrichtung jedes Punkts reduziert."),
							p.latitude, p.longitude, p.height));
					Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
					return;
				}
				if (Klein_Method.this.databaseHandler.readProjectionType() != org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_ELLIPSOIDAL)
					return;
				if (p == null)
					p = this.assumedLocalSystem(); // ohne Umrechnung: Punkte liegen im eingestellten Standard-System
				if (p == null)
					return;
				double[] current = Klein_Method.this.databaseHandler.readPrincipalPoint();
				double[] wanted = this.principalPointFor(p);
				if (this.principalPointMatches(current, wanted))
					return;
				if (p.encode().equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_PRINCIPAL_POINT_DECLINED)))
					return;
				String content = String.format(tr("Das Projekt steht auf „lokal ellipsoidisch“. Der Bezugspunkt (Hauptpunkt) in „Projektion und Reduktion“ passt nicht "
						+ "zum P0, mit dem Klein_Method die Koordinaten umgerechnet hat. JAG3D berechnet daraus die Lotrichtung jedes Punkts - mit dem falschen "
						+ "Bezugspunkt sind Zenitwinkel, Richtungen und Strecken falsch reduziert.\n\n"
						+ "Im Projekt: x0 %s m, y0 %s m, z0 %s m, B %s°, L %s°, h %s m\n"
						+ "P0 (Klein_Method): x0 %s m, y0 %s m, z0 %s m, B %s°, L %s°, h %s m, GRS80\n\n"
						+ "Bezugspunkt auf P0 setzen?"),
						String.format(Locale.GERMANY, "%.3f", current[0]), String.format(Locale.GERMANY, "%.3f", current[1]), String.format(Locale.GERMANY, "%.3f", current[2]),
						String.format(Locale.GERMANY, "%.8f", Math.toDegrees(current[3])), String.format(Locale.GERMANY, "%.8f", Math.toDegrees(current[4])),
						String.format(Locale.GERMANY, "%.3f", current[5]),
						String.format(Locale.GERMANY, "%.3f", wanted[0]), String.format(Locale.GERMANY, "%.3f", wanted[1]), String.format(Locale.GERMANY, "%.3f", wanted[2]),
						String.format(Locale.GERMANY, "%.8f", p.latitude), String.format(Locale.GERMANY, "%.8f", p.longitude), String.format(Locale.GERMANY, "%.3f", p.height));
				java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
				boolean[] yes = new boolean[] { false };
				Platform.runLater(() -> {
					try {
						ButtonType set = new ButtonType(tr("Auf P0 setzen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
						ButtonType keep = new ButtonType(tr("Nicht ändern"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
						Alert alert = new Alert(AlertType.CONFIRMATION, "", set, keep);
						alert.setTitle(tr("Klein_Method - Projektion und Reduktion"));
						alert.setHeaderText(tr("Bezugspunkt passt nicht zu P0"));
						TextArea area = new TextArea(content);
						area.setEditable(false);
						area.setWrapText(true);
						area.setPrefRowCount(10);
						area.setPrefColumnCount(72);
						alert.getDialogPane().setContent(area);
						alert.setResizable(true);
						yes[0] = alert.showAndWait().filter(button -> button == set).isPresent();
					} finally {
						latch.countDown();
					}
				});
				latch.await();
				if (yes[0])
					this.writePrincipalPoint(p, "Tab-Klick, lokal ellipsoidisch");
				else {
					Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_PRINCIPAL_POINT_DECLINED, p.encode());
					Klein_Method.this.logger.log("Klein_Method: Bezugspunkt passt nicht zu P0 - vom Anwender nicht geaendert (gilt fuer diesen P0).");
				}
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Bezugspunkt nicht pruefbar: " + e.getMessage());
			}
		}
		/** Netzmitte fuer "P0 ins Netz legen": nur die Neupunkte, wenn es mindestens 3 sind, sonst alle Punkte. */
		private List<double[]> newPointCenter(List<double[]> rows, List<double[]> ecef) {
			int newType = org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId();
			List<double[]> news = new ArrayList<double[]>();
			for (int i = 0; i < rows.size(); i++)
				if (rows.get(i).length > 5 && (int) rows.get(i)[5] == newType)
					news.add(ecef.get(i));
			return news; // nur Neupunkte - Festpunkte haben keinen Einfluss auf P0
		}
		/**
		 * Einordnung der 3D-Basislinien (geozentrisch/lokal) ueber ihre Endpunkte; P0 = gespeicherter P0, sonst die
		 * Grundwerte. Ohne jeden Anhalt: geozentrisch nur, wenn das Projekt noch nie umgerechnet wurde und geozentrische
		 * Punkte hat.
		 */
		private Klein_Method_Functions.Klein_Method_Local_System.Baseline_Classes classifyBaselines(List<double[]> rows, List<double[]> baselineRows,
				Klein_Method_Functions.Klein_Method_Local_System.Parameters stored, boolean geocentricPoints) {
			return Klein_Method_Functions.Klein_Method_Local_System.classifyBaselines(rows, baselineRows,
					stored != null ? stored : Klein_Method.this.parameter.getLocalSystem(), stored == null && geocentricPoints);
		}
		/** Anzahl der 3D-Basislinien, die (noch) geozentrisch sind. */
		int countGeocentricBaselines() throws SQLException {
			List<double[]> rows = Klein_Method.this.databaseHandler.readLocalConversionPoints();
			boolean geocentricPoints = false;
			for (double[] r : rows)
				geocentricPoints |= (int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]);
			List<double[]> baselineRows = Klein_Method.this.databaseHandler.readLocalConversionBaselines();
			Klein_Method_Functions.Klein_Method_Local_System.Baseline_Classes classes = this.classifyBaselines(rows, baselineRows,
					Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM)),
					geocentricPoints);
			int n = 0; // nur aktive - deaktivierte geozentrische Basislinien stoeren die Rechnung nicht
			for (double[] b : baselineRows)
				if (b[7] > 0.5 && Boolean.TRUE.equals(classes.geocentric.get((int) b[0])))
					n++;
			return n;
		}
		/** true, wenn das Projekt geozentrische 3D-Punkte oder geozentrische 3D-Basislinien enthaelt (Umrechnung noetig). */
		/**
		 * Projektion, Reduktion, P0 und Umrechnung ins lokale System werden nur angefasst, wenn das Projekt aktive
		 * GNSS-Basislinien enthaelt. Reine Tachymeter-/Nivellementnetze behalten ihre Einstellungen.
		 */
		boolean hasGnssBaselines() {
			try {
				return Klein_Method.this.databaseHandler.countActiveGnssBaselines() > 0;
			} catch (SQLException e) {
				return false;
			}
		}
		private final Set<String> refusedWithoutGnss = new HashSet<String>();
		/** ohne GNSS-Basislinien: einmal je Sitzung loggen, bei Menueaufruf Hinweis zeigen. */
		private void refuseWithoutGnss(String what, boolean force) {
			if (this.refusedWithoutGnss.add(what)) {
				Klein_Method.this.logger.log("Klein_Method: " + what + " - keine aktiven GNSS-Basislinien im Projekt, Projektion und Reduktion bleiben unveraendert.");
				Klein_Method.this.getProtocol().logEvent("SYSTEM: " + what + " uebersprungen - keine GNSS-Basislinien, Projektion und Reduktion unveraendert");
			}
			if (force)
				Klein_Method.this.showApproximationInfo(tr("Keine aktiven GNSS-Basislinien im Projekt - Projektion und Reduktion bleiben unverändert. "
						+ "Die Umrechnung ins lokale System und das Verlegen von P0 gibt es nur für Netze mit GNSS-Basislinien."));
		}
		boolean hasGeocentricData() throws SQLException {
			if (!this.hasGnssBaselines())
				return false;
			if (this.hasGeocentricPoints() || this.countGeocentricBaselines() > 0)
				return true;
			// schon umgerechnet: nachimportierte 1D-Hoehenpunkte mit Hoehe/Lage im Ausgangssystem
			Klein_Method_Functions.Klein_Method_Local_System.Parameters stored = Klein_Method_Functions.Klein_Method_Local_System.Parameters
					.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
			if (stored != null)
				for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints())
					if ((int) r[1] == 1 && this.oneDimensionalCandidate(r, stored))
						return true;
			return false;
		}
		/**
		 * 1D-Punkt r = {id, Dimension, y0, x0, z0, Punktart} noch umzurechnen? Nein, wenn nichts eingetragen ist oder er
		 * (Projekt schon umgerechnet) schon lokal ist: Hoehe nahe der Additionskonstante "Oben" und Lage lokal bzw. 0/0.
		 */
		private boolean oneDimensionalCandidate(double[] r, Klein_Method_Functions.Klein_Method_Local_System.Parameters stored) {
			double y = r[2], x = r[3], z = r[4];
			if (y == 0.0 && x == 0.0 && z == 0.0)
				return false;
			if (stored != null && Math.abs(z - stored.addUp) <= LOCAL_1D_HEIGHT_WINDOW && (stored.looksLocal(y, x) || (y == 0.0 && x == 0.0)))
				return false;
			if (stored != null && z == 0.0 && stored.looksLocal(y, x))
				return false;
			return true;
		}
		/**
		 * 1D-Punkt ins lokale System: {Ost, Nord, Oben, Quelle}. Lage aus der eingetragenen Lage - UTM (mit Zone), Gauss-
		 * Krueger (mit Streifen, DHDN), UTM ohne Zone (Zone von P0), geographisch (Laenge/Breite in Grad) -, nur wenn
		 * hoechstens 100 km von P0; sonst Lage unbekannt -> P0 (Quelle 3). Hoehe wie eingetragen (Hoehensystem bleibt;
		 * NHN -> ellipsoidisch danach ueber "Hoehenfestpunkte umrechnen"). Hoehe 0 bleibt 0 (Neupunkt, Naeherung folgt).
		 * null = nichts umzurechnen.
		 */
		/** Lage der umzurechnenden 3D-Punkte im lokalen System (fuer die 1D-Lagepruefung), sonst leer. */
		private double[][] networkPlanar = new double[0][];
		/** 1D-Lage brauchbar: hoechstens 100 km von P0 oder von einem 3D-Punkt des Netzes entfernt. */
		private boolean nearNetwork(double[] local, Klein_Method_Functions.Klein_Method_Local_System.Parameters p) {
			if (Math.hypot(local[0] - p.addEast, local[1] - p.addNorth) <= 100000.0)
				return true;
			for (double[] q : this.networkPlanar)
				if (q != null && Math.hypot(local[0] - q[0], local[1] - q[1]) <= 100000.0)
					return true;
			return false;
		}
		private double[] oneDimensionalToLocal(double[] r, Klein_Method_Functions.Klein_Method_Local_System.Parameters p) {
			double y = r[2], x = r[3], z = r[4];
			List<double[]> candidates = new ArrayList<double[]>();
			if (!(y == 0.0 && x == 0.0) && p.looksLocal(y, x)) {
				// Lage schon lokal (Ost/Nord um P0), nur die Hoehe ist noch im Ausgangssystem: Breite/Laenge so bestimmen,
				// dass der Punkt MIT seiner Hoehe bei Ost/Nord liegt (die Lotrichtung weicht von der P0-Normalen ab)
				double east = y, north = x;
				double[] g = null;
				for (int i = 0; i < 4; i++) {
					double[] e = p.toEcef(east, north, p.addUp);
					g = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(e[0], e[1], e[2]);
					double[] c = Klein_Method_Functions.Klein_Method_Local_System.geodeticToEcef(g[0], g[1], z == 0.0 ? p.height : z);
					double[] l = p.toLocal(c[0], c[1], c[2]);
					east += y - l[0];
					north += x - l[1];
				}
				candidates.add(new double[] { g[0], g[1], 4 });
			}
			if (!(y == 0.0 && x == 0.0)) {
				double[] ll = Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(y, x);
				if (ll != null)
					candidates.add(new double[] { ll[0], ll[1], 0 });
				ll = Klein_Method_Functions.Klein_Method_Geo.gaussKruegerToLatLon(y, x);
				if (ll != null)
					candidates.add(new double[] { ll[0], ll[1], 1 });
				if (y > 100000.0 && y < 900000.0 && x > 1000000.0 && x < 9400000.0) {
					int zone = (int) Math.floor((p.longitude + 180.0) / 6.0) + 1;
					ll = Klein_Method_Functions.Klein_Method_Geo.UTM.inverse(y - 500000.0, x, Klein_Method_Functions.Klein_Method_Geo.utmCentralMeridian(zone));
					if (ll != null)
						candidates.add(new double[] { ll[0], ll[1], 0 });
				}
				if (Math.abs(y) <= 180.0 && Math.abs(x) <= 90.0)
					candidates.add(new double[] { x, y, 2 });
			}
			for (double[] c : candidates) {
				if (!Double.isFinite(c[0]) || !Double.isFinite(c[1]))
					continue;
				double[] ecef = Klein_Method_Functions.Klein_Method_Local_System.geodeticToEcef(c[0], c[1], z == 0.0 ? p.height : z);
				double[] local = p.toLocal(ecef[0], ecef[1], ecef[2]);
				if (this.nearNetwork(local, p))
					return new double[] { local[0], local[1], z == 0.0 ? 0.0 : local[2], c[2] };
			}
			if (z == 0.0)
				return null;
			double[] ecef = Klein_Method_Functions.Klein_Method_Local_System.geodeticToEcef(p.latitude, p.longitude, z);
			double[] local = p.toLocal(ecef[0], ecef[1], ecef[2]);
			return new double[] { local[0], local[1], local[2], 3 };
		}
		/**
		 * Anzahl geozentrischer 3D-Basislinien neben lokalen 3D-Punkten (gemischter Stand, z. B. nach "Nicht
		 * umrechnen") - damit waere jede Rechnung falsch. 0 = kein gemischter Stand.
		 */
		int mixedGeocentricBaselines() throws SQLException {
			boolean localPoints = false;
			for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints())
				localPoints |= (int) r[1] == 3 && !(r[2] == 0.0 && r[3] == 0.0) && !Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]);
			return localPoints ? this.countGeocentricBaselines() : 0;
		}
		/** true, wenn das Projekt geozentrische 3D-Punkte enthaelt (Umrechnung moeglich). */
		boolean hasGeocentricPoints() throws SQLException {
			for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints())
				if ((int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]))
					return true;
			return false;
		}
		boolean approximationBusyForLock() {
			return Klein_Method.this.approximationCheckRunning || Klein_Method.this.headlessTabRefreshRunning;
		}
		/** Rueckfrage mit allen Angaben; wartet. */
		private boolean askLocalConversion(Local_Conversion_Plan plan) throws InterruptedException {
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = plan.parameters;
			double[] p0 = p.p0();
			StringBuilder text = new StringBuilder();
			text.append(String.format(tr("Geozentrische WGS84-Koordinaten erkannt: %d Punkte und %d 3D-GNSS-Basislinien. "
					+ "Sie können in ein lokales Horizontsystem (Ost/Nord/Oben) um den Bezugspunkt P0 umgerechnet werden."), plan.points.size() - plan.oneDimensional.size(),
					plan.baselines.size()));
			if (!plan.oneDimensional.isEmpty()) {
				int[] bySource = new int[5];
				for (int source : plan.oneDimensional.values())
					bySource[source]++;
				text.append("\n\n").append(String.format(tr("Dazu %d Höhenpunkt(e) (1D): Lage aus UTM %d, Gauß-Krüger %d, geographisch %d, schon lokal %d. Die Höhe wird "
						+ "so umgerechnet, wie sie eingetragen ist (NHN bleibt NHN) - gemischte Höhensysteme danach wie gewohnt mit „Höhenfestpunkte umrechnen …“ (Quasigeoid)."),
						plan.oneDimensional.size(), bySource[0], bySource[1], bySource[2], bySource[4]));
				if (bySource[3] > 0)
					text.append("\n").append(String.format(tr("Achtung: %d Höhenpunkt(e) ohne verwertbare Lage (z. B. 0/0) - Lage = P0 angenommen. Im lokalen System hängt die "
							+ "Höhe von der Lage ab (Erdkrümmung, bei 2 km Abstand zu P0 rund 0,3 m) - bitte die Lage (UTM) eintragen: %s"), bySource[3],
							String.join(", ", plan.oneDimensionalUnknownNames.subList(0, Math.min(10, plan.oneDimensionalUnknownNames.size())))));
			}
			if (plan.staleP0 != null)
				text.append("\n\n").append(tr("Hinweis: Der im Projekt gespeicherte P0 gehört zu früheren Daten - kein Punkt liegt mehr in seinem lokalen System. "
						+ "Er wird nicht verwendet; P0 wird wie bei einem neuen Projekt im Netz bestimmt."));
			text.append("\n\n");
			if (plan.storedUsed)
				text.append(String.format(tr("P0 = P0 der früheren Umrechnung dieses Projekts (Netzschwerpunkt %s km entfernt) - die schon umgerechneten Punkte passen damit zusammen."),
						String.format(Locale.GERMANY, "%.1f", plan.distance / 1000.0)));
			else if (plan.defaultsUsed)
				text.append(tr("Im Projekt gibt es noch keine Neupunkte - P0 = eingestellte Grundwerte. Sobald Neupunkte vorhanden sind, bietet Klein_Method an, "
						+ "P0 in ihren Schwerpunkt zu legen."));
			else {
				text.append(String.format(tr("P0 = Schwerpunkt der %d Neupunkte (Mittel der geozentrischen Koordinaten, Höhe = Mittel der ellipsoidischen Höhen; "
						+ "Breite/Länge auf 0,00001°, Höhe auf 0,01 m gerundet). Festpunkte zählen nicht mit. "
						+ "Er wird im Projekt gespeichert, damit die Koordinaten bei späteren Rechnungen nicht springen."), plan.centroidPoints));
				if (plan.centroidDropped > 0)
					text.append(' ').append(String.format(tr("%d Neupunkt(e) weichen in Lage oder Höhe stark von den übrigen ab und zählen nicht mit - bitte ihre Näherungskoordinaten prüfen."),
							plan.centroidDropped));
			}
			text.append("\n").append(String.format(Locale.GERMANY, "P0: B %.10f°  L %.10f°  h %.3f m  |  X %.4f  Y %.4f  Z %.4f", p.latitude, p.longitude, p.height, p0[0], p0[1], p0[2]));
			text.append("\n").append(String.format(tr("Additionskonstanten: Ost %s m, Nord %s m, Höhe %s m; %d Nachkommastellen."),
					String.format(Locale.GERMANY, "%.3f", p.addEast), String.format(Locale.GERMANY, "%.3f", p.addNorth), String.format(Locale.GERMANY, "%.3f", p.addUp), p.decimals));
			if (plan.otherPoints > 0 || plan.otherBaselines > 0)
				text.append("\n\n").append(String.format(tr("Nicht umgerechnet: %d Punkt(e) ohne geozentrische 3D-Koordinaten, %d Basislinie(n) 1D/2D."), plan.otherPoints, plan.otherBaselines));
			if (plan.localBaselines > 0)
				text.append("\n").append(String.format(tr("%d 3D-Basislinie(n) sind schon lokal (über ihre Endpunkte erkannt) und bleiben unverändert - die Reihenfolge des Imports spielt keine Rolle."),
						plan.localBaselines));
			if (!plan.heightSuspects.isEmpty()) {
				List<String> shown = plan.heightSuspectNames.subList(0, Math.min(8, plan.heightSuspectNames.size()));
				text.append("\n\n").append(String.format(tr("Achtung Höhen: %d Punkt(e) haben eine ellipsoidische Höhe, die mehr als %s m von den GNSS-Punkten "
						+ "(Median %s m) abweicht: %s. Vermutlich wurde Höhe 0 oder eine NHN-Höhe als ellipsoidische Höhe umgerechnet. Im lokalen System verschiebt "
						+ "das auch die Lage (je 100 m Höhenfehler ca. 1,6 cm bei 1 km Abstand zu P0). Bitte diese Punkte mit ellipsoidischen Höhen umrechnen."),
						plan.heightSuspects.size(), String.format(Locale.GERMANY, "%.0f", Klein_Method_Functions.Klein_Method_Local_System.HEIGHT_CHECK_LIMIT),
						String.format(Locale.GERMANY, "%.2f", plan.referenceHeight), String.join(", ", shown) + (plan.heightSuspectNames.size() > shown.size() ? " …" : "")));
			}
			text.append("\n\n").append(tr("Umrechnen: Punkte und Basislinien werden im Projekt ersetzt, die Projektion wird auf „lokal ellipsoidisch“ gestellt, "
					+ "der Bezugspunkt in „Projektion und Reduktion“ auf P0 - JAG3D reduziert Zenitwinkel, Richtungen, Strecken und Nivellement damit auf die Lotrichtung "
					+ "jedes Punkts. Die alten Werte werden gesichert („Projekt-Umrechnung zurücknehmen“ im Fenster Koordinatenumrechnung). Die Karte rechnet das "
					+ "lokale System mit denselben Parametern zurück."));
			String content = text.toString();
			java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			boolean[] yes = new boolean[] { false };
			Platform.runLater(() -> {
				try {
					ButtonType convert = new ButtonType(tr("Umrechnen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType keep = new ButtonType(tr("Nicht umrechnen"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					Alert alert = new Alert(AlertType.CONFIRMATION, "", convert, keep);
					alert.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
					alert.setHeaderText(tr("Projekt in lokales System (ENh) umrechnen?"));
					TextArea area = new TextArea(content);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(16);
					area.setPrefColumnCount(80);
					alert.getDialogPane().setContent(area);
					alert.setResizable(true);
					yes[0] = alert.showAndWait().filter(button -> button == convert).isPresent();
				} finally {
					latch.countDown();
				}
			});
			latch.await();
			return yes[0];
		}
		/** laeuft gerade eine Naeherungs- oder Umrechnungsaktion? (Fenster Koordinatenumrechnung) */
		public boolean isApproximationOrConversionRunning() {
			return Klein_Method.this.approximationCheckRunning;
		}
		private boolean conversionBusy() {
			return Klein_Method.this.approximationBusy();
		}
		/** Fenster: Projekt pruefen und umrechnen (Hintergrund). */
		public void convertProjectOnDemand() {
			if (this.conversionBusy()) {
				Klein_Method.this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Klein_Method.this.approximationCheckRunning = true;
			Thread thread = busyThread(() -> {
				boolean converted = false;
				ADJUSTMENT_LOCK.lock();
				try {
					converted = this.checkLocalConversion(true);
				} finally {
					ADJUSTMENT_LOCK.unlock();
					Klein_Method.this.approximationCheckRunning = false;
				}
				if (converted && Klein_Method.this.isKleinMethodTabActive())
					Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
			}, "Klein_Method_Local_Conversion");
			thread.setDaemon(true);
			thread.start();
		}
		/** Fenster: Projekt-Umrechnung zuruecknehmen (Hintergrund, mit Rueckfrage). */
		public void restoreProjectConversionOnDemand() {
			if (this.conversionBusy()) {
				Klein_Method.this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Klein_Method.this.approximationCheckRunning = true;
			final boolean[] restored = new boolean[] { false };
			Thread thread = busyThread(() -> {
				try {
					String[] info;
					ADJUSTMENT_LOCK.lock();
					try {
						info = SQLManager.getInstance().hasDatabase() && SQLManager.getInstance().getDataBase().isOpen() ? Klein_Method.this.databaseHandler.readLocalConversionBackupInfo() : null;
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
					if (info == null) {
						Klein_Method.this.showApproximationInfo(tr("Es gibt keine gesicherte Projekt-Umrechnung (oder kein Projekt ist geöffnet)."));
						return;
					}
					java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
					boolean[] confirmed = new boolean[] { false };
					Platform.runLater(() -> {
						try {
							Alert alert = new Alert(AlertType.CONFIRMATION);
							alert.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
							alert.setHeaderText(tr("Projekt-Umrechnung zurücknehmen?"));
							alert.setContentText(String.format(tr("%s Punkte und %s Basislinien werden auf die Werte vor der Umrechnung vom %s zurückgesetzt, "
									+ "die Projektion auf die vorherige Einstellung."), info[1], info[2], info[0]));
							alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
							confirmed[0] = alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
						} finally {
							latch.countDown();
						}
					});
					latch.await();
					if (!confirmed[0])
						return;
					ADJUSTMENT_LOCK.lock();
					try {
						// zusammengefasste GNSS-Sitzungen entstanden nach der Umrechnung (lokal) - zuerst zuruecknehmen, sonst blieben
						// lokale Mittel neben den wiederhergestellten geozentrischen Epochen
						Klein_Method.this.repetitionPart.undoGnss();
						int count = Klein_Method.this.databaseHandler.restoreLocalConversion();
						// Stand wie importiert: Zusammenfassen nicht sofort wieder anbieten (erst bei neuen Sitzungen bzw. ueber das Menue)
						Klein_Method.this.repetitionPart.declineCurrent();
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_LOCAL_SYSTEM, "");
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_LOCAL_DECLINED, "true"); // nicht sofort wieder fragen
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_P0_DECLINED, P0_DECLINED_CENTROID);
						Klein_Method.this.databaseHandler.deleteFreeSolution();
						Klein_Method.this.databaseHandler.clearApproximationBackup();
						Klein_Method.this.logger.log("Klein_Method: Koordinatenumrechnung zurueckgenommen (" + count + " Punkte).");
						Klein_Method.this.getProtocol().logEvent("KOORDINATENUMRECHNUNG: zurueckgenommen (" + count + " Punkte)");
						Klein_Method.this.showApproximationInfo(String.format(tr("Projekt-Umrechnung zurückgenommen: %d Punkte auf den Stand vor der Umrechnung zurückgesetzt."), count));
						restored[0] = true;
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
					Platform.runLater(Klein_Method_Tab_Builder.Klein_Method_Map_Window::notifyDataChanged);
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Ruecknahme der Koordinatenumrechnung fehlgeschlagen: " + e.getMessage());
					Klein_Method.this.showApproximationInfo(tr("Vorgang fehlgeschlagen: ") + e.getMessage());
				} finally {
					Klein_Method.this.approximationCheckRunning = false;
				}
				if (restored[0]) {
					Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
					if (Klein_Method.this.isKleinMethodTabActive())
						Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
				}
			}, "Klein_Method_Local_Conversion_Restore");
			thread.setDaemon(true);
			thread.start();
		}
		/** Knopf "P0 ins Netz legen und neu umrechnen ..." (Hintergrund, mit Rueckfrage). */
		public void relocateP0OnDemand() {
			if (this.conversionBusy()) {
				Klein_Method.this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			if (!Klein_Method.this.isProjectOpen()) {
				Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet."));
				return;
			}
			Klein_Method.this.approximationCheckRunning = true;
			Thread thread = busyThread(() -> {
				boolean relocated = false;
				ADJUSTMENT_LOCK.lock();
				try {
					relocated = this.relocateP0(true);
				} finally {
					ADJUSTMENT_LOCK.unlock();
					Klein_Method.this.approximationCheckRunning = false;
				}
				if (relocated && Klein_Method.this.isKleinMethodTabActive())
					Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
			}, "Klein_Method_Local_Relocate");
			thread.setDaemon(true);
			thread.start();
		}
		/**
		 * P0 in die Netzmitte legen (Median der lokalen Punkte, geozentrisch auf 1000 m gerundet). Rechnet die aktuellen
		 * lokalen Werte direkt um (alt lokal -> geozentrisch -> neu lokal); uebernommene Naeherungen bleiben erhalten.
		 *
		 * force = false (Automatik beim Oeffnen/Tab-Klick): nur mit Zenitwinkeln/Nivellement, Netzmitte > 1 km von P0,
		 * nicht bei "lokal ellipsoidisch" und nicht nach "Nicht jetzt". Aufruf im Hintergrund mit ADJUSTMENT_LOCK.
		 *
		 * @return true, wenn umgerechnet wurde
		 */
		private boolean relocateP0(boolean force) {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return false;
				if (!this.hasGnssBaselines()) {
					this.refuseWithoutGnss("P0 ins Netz legen", force);
					return false;
				}
				// eine Ablehnung gilt nur fuer die Regel "Schwerpunkt der Neupunkte" (fruehere Ablehnungen galten der Netzmitte aller Punkte)
				if (!force && P0_DECLINED_CENTROID.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_P0_DECLINED)))
					return false;
				Klein_Method_Functions.Klein_Method_Local_System.Parameters oldP = Klein_Method_Functions.Klein_Method_Local_System.Parameters
						.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
				boolean assumed = oldP == null;
				if (assumed)
					oldP = Klein_Method.this.parameter.getLocalSystem().copy();
				List<double[]> rows = new ArrayList<double[]>(), ecef = new ArrayList<double[]>();
				// 1D-Hoehen im alten lokalen System (Oben-Wert nahe der Additionskonstante): Hoehe mit umrechnen, Lage bleibt
				List<double[]> heights1D = new ArrayList<double[]>();
				int skipped = 0, geocentric = 0;
				for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints()) {
					// r = {id, Dimension, y0 = Ost, x0 = Nord, z0 = Oben}
					if ((int) r[1] == 3 && r[2] == 0.0 && r[3] == 0.0 && r[4] == 0.0) {
						// ohne Naeherungskoordinaten: zaehlt nicht zum Schwerpunkt und bleibt 0 (Naeherung folgt danach)
						skipped++;
						continue;
					}
					if ((int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]))
						geocentric++;
					if ((int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.inLocalSystem(oldP, r[2], r[3], r[4])) {
						rows.add(r);
						ecef.add(oldP.toEcef(r[2], r[3], r[4])); // {X, Y, Z}
					}
					else if ((int) r[1] == 1 && ((r[4] != 0.0 && Math.abs(r[4] - oldP.addUp) <= LOCAL_1D_HEIGHT_WINDOW)
							|| (oldP.looksLocal(r[2], r[3]) && !(r[2] == 0.0 && r[3] == 0.0))))
						heights1D.add(r);
					else
						skipped++;
				}
				if (rows.size() < (force ? 1 : 3) || (!force && geocentric > 0)) {
					if (force)
						Klein_Method.this.showApproximationInfo(tr("Keine lokalen 3D-Punkte im Projekt gefunden."));
					return false;
				}
				// noch geozentrische 3D-Basislinien (nachimportiert): erst umrechnen, sonst wuerden sie wie lokale gedreht
				List<double[]> allPoints = Klein_Method.this.databaseHandler.readLocalConversionPoints();
				List<double[]> baselineRows = Klein_Method.this.databaseHandler.readLocalConversionBaselines();
				Klein_Method_Functions.Klein_Method_Local_System.Baseline_Classes classes = Klein_Method_Functions.Klein_Method_Local_System.classifyBaselines(allPoints,
						baselineRows, oldP, false);
				if (classes.countGeocentric() > 0) {
					Klein_Method.this.logger.log("Klein_Method: P0 ins Netz legen - " + classes.countGeocentric() + " 3D-Basislinien noch geozentrisch, zuerst umrechnen.");
					if (force)
						Klein_Method.this.showApproximationInfo(String.format(tr("%d 3D-Basislinie(n) sind noch geozentrisch - bitte zuerst ins lokale System umrechnen "
								+ "(„Näherungskoordinaten berechnen und prüfen“ im Menü Klein_Method bietet das an)."), classes.countGeocentric()));
					return false;
				}
				List<double[]> center = this.newPointCenter(rows, ecef);
				if (center.isEmpty()) {
					if (force)
						Klein_Method.this.showApproximationInfo(tr("Keine Neupunkte mit lokalen 3D-Koordinaten im Projekt - P0 bleibt unverändert."));
					return false;
				}
				Object[] choice = Klein_Method_Functions.Klein_Method_Local_System.chooseReference(center, oldP, 0.0);
				Klein_Method_Functions.Klein_Method_Local_System.Parameters newP = (Klein_Method_Functions.Klein_Method_Local_System.Parameters) choice[0];
				if (newP == null || !newP.valid()) {
					// nie ein ungueltiges P0 speichern (z. B. Hoehe ueber 10 km) - P0 bleibt
					Klein_Method.this.logger.log("Klein_Method: P0 ins Netz legen - berechneter Bezugspunkt ungueltig ("
							+ (newP == null ? "keiner" : newP.encode()) + "), P0 bleibt unveraendert.");
					if (force)
						Klein_Method.this.showApproximationInfo(tr("Der Schwerpunkt der Neupunkte ergibt keinen gültigen Bezugspunkt - P0 bleibt unverändert. Bitte die Näherungskoordinaten der Neupunkte prüfen."));
					return false;
				}
				int centroidDropped = (Integer) choice[4];
				// Abstand des Schwerpunkts der Neupunkte von P0 (fuer Automatik-Grenze und Neigung der Oben-Achse)
				double distance = (Double) choice[1];
				if (!force && distance <= P0_AUTO_DISTANCE)
					return false;
				if (!force && assumed) {
					// angenommene Grundwerte nur, wenn das Netz geodaetisch bezogen ist (3D-GNSS-Basislinien) und nahe P0 liegt (< 50 km) -
					// ein frei gewaehltes lokales System (z. B. 1000/5000) ohne GNSS wird nie umgerechnet
					boolean gnss3d = false;
					for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionBaselines())
						gnss3d |= (int) r[1] == ObservationType.GNSS3D.getId();
					if (!gnss3d || distance > 50000.0)
						return false;
				}
				double[] a = oldP.p0(), b = newP.p0();
				double shift = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
				if (shift < 1.0) {
					if (force)
						Klein_Method.this.showApproximationInfo(tr("P0 liegt bereits im Schwerpunkt der Neupunkte - nichts zu tun."));
					return false;
				}
				Map<Integer, double[]> points = new LinkedHashMap<Integer, double[]>(), baselines = new LinkedHashMap<Integer, double[]>();
				for (int i = 0; i < rows.size(); i++) {
					double[] e = ecef.get(i);
					points.put((int) rows.get(i)[0], newP.toLocal(e[0], e[1], e[2]));
				}
				// 1D-Punkte: lokale Lage mit umrechnen (alt lokal -> geozentrisch -> neu lokal; JAG3D reduziert das Nivellement in
				// "lokal ellipsoidisch" ueber die Lage), die Hoehe nur, wenn sie im lokalen System liegt (sonst z. B. 0 bleibt);
				// ohne lokale Lage (z. B. UTM) gilt die Hoehe an P0 alt und nur sie wird ersetzt
				for (double[] r : heights1D) {
					boolean local = oldP.looksLocal(r[2], r[3]) && !(r[2] == 0.0 && r[3] == 0.0);
					boolean height = r[4] != 0.0 && Math.abs(r[4] - oldP.addUp) <= LOCAL_1D_HEIGHT_WINDOW;
					double[] e = oldP.toEcef(local ? r[2] : oldP.addEast, local ? r[3] : oldP.addNorth, height ? r[4] : oldP.addUp);
					double[] n = newP.toLocal(e[0], e[1], e[2]);
					points.put((int) r[0], new double[] { local ? n[0] : r[2], local ? n[1] : r[3], height ? n[2] : r[4] });
				}
				for (double[] r : baselineRows) {
					// r = {id, Typ, y0 = dOst, x0 = dNord, z0 = dOben}
					if ((int) r[1] != ObservationType.GNSS3D.getId())
						continue;
					double[] d = oldP.vectorToEcef(r[2], r[3], r[4]); // {dX, dY, dZ}
					double[] v = newP.vectorToLocal(d[0], d[1], d[2]);
					baselines.put((int) r[0], new double[] { newP.round(v[0]), newP.round(v[1]), newP.round(v[2]) });
				}
				// Rueckfrage (wartet)
				// Neigung der Oben-Achse aus dem waagerechten Abstand des Schwerpunkts von P0
				double[] centroidLocal = oldP.toLocal(b[0], b[1], b[2]);
				double horizontal = Math.hypot(centroidLocal[0] - oldP.addEast, centroidLocal[1] - oldP.addNorth);
				double tiltMgon = horizontal / 6371000.0 * 200000.0 / Math.PI;
				StringBuilder text = new StringBuilder();
				text.append(String.format(tr("Der Schwerpunkt der Neupunkte liegt %s km von P0 entfernt. Dort ist die Oben-Achse des lokalen Systems um ca. %s mgon gegen das Lot geneigt - "
						+ "Zenitwinkel und Nivellement beziehen sich aber auf das Lot. Das erzeugt systematische Höhenwidersprüche zu GNSS (bei 100 m Zielweite ca. %s mm)."),
						String.format(Locale.GERMANY, "%.1f", horizontal / 1000.0), String.format(Locale.GERMANY, "%.0f", tiltMgon),
						String.format(Locale.GERMANY, "%.0f", tiltMgon * Math.PI / 200000.0 * 100.0 * 1000.0)));
				if (Klein_Method.this.databaseHandler.readProjectionType() == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_ELLIPSOIDAL)
					text.append(' ').append(tr("Bei „lokal ellipsoidisch“ berücksichtigt JAG3D diese Neigung über den Bezugspunkt - P0 im Schwerpunkt der Neupunkte hält die Reduktionen trotzdem klein; der Bezugspunkt wird mit verlegt."));
				text.append("\n\n").append(String.format(tr("P0 wird in den Schwerpunkt der %d Neupunkte gelegt (Festpunkte zählen nicht; Breite/Länge auf 0,00001°, "
						+ "Höhe auf 0,01 m gerundet; %s km verschoben). %d Punkte und %d 3D-Basislinien "
						+ "werden direkt umgerechnet (alt lokal → geozentrisch → neu lokal); übernommene Näherungskoordinaten bleiben erhalten."),
						(Integer) choice[3], String.format(Locale.GERMANY, "%.1f", shift / 1000.0), points.size() - heights1D.size(), baselines.size()));
				if (!heights1D.isEmpty())
					text.append(' ').append(String.format(tr("Dazu werden %d 1D-Punkt(e) (lokale Lage bzw. Höhe) mit umgerechnet."), heights1D.size()));
				if (centroidDropped > 0)
					text.append(' ').append(String.format(tr("%d Neupunkt(e) weichen in Lage oder Höhe stark von den übrigen ab und zählen nicht mit - bitte ihre Näherungskoordinaten prüfen."),
							centroidDropped));
				text.append("\n").append(String.format(Locale.GERMANY, tr("Neues P0: B %.10f°  L %.10f°  h %.3f m"), newP.latitude, newP.longitude, newP.height));
				if (assumed)
					text.append("\n\n").append(String.format(Locale.GERMANY, tr("Annahme: Die Koordinaten wurden vorab mit den Grundwerten umgerechnet (z. B. VBA-Tool): "
							+ "B %.10f°, L %.10f°, h %.3f m, Additionskonstanten %.0f / %.0f / %.0f m. Stimmt das nicht, bitte „Nicht jetzt“ wählen."),
							oldP.latitude, oldP.longitude, oldP.height, oldP.addEast, oldP.addNorth, oldP.addUp));
				if (skipped > 0)
					text.append("\n").append(String.format(tr("%d Punkt(e) nicht im lokalen 3D-System - bleiben unverändert."), skipped));
				text.append("\n\n").append(tr("Rücknahme: „Projekt-Umrechnung zurücknehmen“ im Fenster Koordinatenumrechnung."));
				String content = text.toString();
				java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
				boolean[] confirmed = new boolean[] { false };
				Platform.runLater(() -> {
					try {
						ButtonType yes = new ButtonType(tr("P0 ins Netz legen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
						ButtonType no = new ButtonType(tr("Nicht jetzt"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
						Alert alert = new Alert(AlertType.CONFIRMATION, "", yes, no);
						alert.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
						alert.setHeaderText(tr("P0 ins Netz legen und neu umrechnen?"));
						TextArea area = new TextArea(content);
						area.setEditable(false);
						area.setWrapText(true);
						area.setPrefRowCount(12);
						area.setPrefColumnCount(72);
						alert.getDialogPane().setContent(area);
						alert.setResizable(true);
						confirmed[0] = alert.showAndWait().filter(button -> button == yes).isPresent();
					} finally {
						latch.countDown();
					}
				});
				latch.await();
				if (!confirmed[0]) {
					if (!force)
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_P0_DECLINED, P0_DECLINED_CENTROID);
					Klein_Method.this.logger.log("Klein_Method: P0 ins Netz legen - vom Anwender abgelehnt (Abstand " + String.format(Locale.GERMANY, "%.0f", distance) + " m).");
					return false;
				}
				String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
				int[] count;
				if (Klein_Method.this.databaseHandler.readLocalConversionBackupInfo() == null)
					count = Klein_Method.this.databaseHandler.applyLocalConversion(points, baselines, stamp);
				else
					count = Klein_Method.this.databaseHandler.updateLocalCoordinates(points, baselines); // Sicherung der ersten Umrechnung bleibt
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_LOCAL_SYSTEM, newP.encode());
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_P0_DECLINED, "");
				this.writePrincipalPoint(newP, "P0 verlegt");
				Klein_Method.this.databaseHandler.deleteFreeSolution();
				Klein_Method.this.databaseHandler.clearApproximationBackup();
				Klein_Method.this.logger.log("Klein_Method: P0 ins Netz verlegt (" + (force ? "Knopf" : "automatisch") + ", Abstand " + String.format(Locale.GERMANY, "%.0f", distance)
						+ " m, verschoben " + String.format(Locale.GERMANY, "%.0f", shift) + " m" + (assumed ? ", bisheriges P0 = Grundwerte angenommen" : "") + "): "
						+ count[0] + " Punkte" + (heights1D.isEmpty() ? "" : " (davon " + heights1D.size() + " 1D-Punkte)") + ", " + count[1] + " Basislinien, P0 " + newP.encode() + ".");
				Klein_Method.this.getProtocol().logEvent("KOORDINATENUMRECHNUNG: P0 ins Netz verlegt, " + count[0] + " Punkte, " + count[1] + " Basislinien, P0 " + newP.encode());
				if (force)
					Klein_Method.this.showApproximationInfo(String.format(tr("P0 verlegt: %d Punkte und %d Basislinien neu umgerechnet."), count[0], count[1]));
				Platform.runLater(Klein_Method_Tab_Builder.Klein_Method_Map_Window::notifyDataChanged);
				return true;
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: P0 verlegen fehlgeschlagen: " + e.getMessage());
				if (force)
					Klein_Method.this.showApproximationInfo(tr("Vorgang fehlgeschlagen: ") + e.getMessage());
				return false;
			}
		}
		/** Fuer das Fenster: Stand des geoeffneten Projekts (Hintergrund). */
		public String describeProjectConversionState() {
			try {
				if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
					return tr("Kein Projekt geöffnet.");
				ADJUSTMENT_LOCK.lock();
				try {
					Klein_Method_Functions.Klein_Method_Local_System.Parameters p = Klein_Method_Functions.Klein_Method_Local_System.Parameters
							.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
					if (p != null)
						return String.format(Locale.GERMANY, tr("Projekt ist in das lokale System umgerechnet. P0 des Projekts: B %.6f° (%.5f gon), L %.6f° (%.5f gon), "
								+ "h %.2f m - so steht er auch als Bezugspunkt in JAG3D „Projektion und Reduktion“ (JAG3D zeigt gon). Projektion: %s.\n"
								+ "Die Grundwerte oben gelten nur für neue Projekte."), p.latitude, p.latitude / 0.9, p.longitude, p.longitude / 0.9, p.height,
								Klein_Method.this.databaseHandler.readProjectionTypeDisplayName());
					int ecef = 0;
					for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints())
						if ((int) r[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(r[3], r[2], r[4]))
							ecef++;
					return ecef > 0 ? String.format(tr("Projekt enthält %d geozentrische Punkte - Umrechnung möglich."), ecef)
							: tr("Projekt enthält keine geozentrischen Punkte.");
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
			} catch (Exception e) {
				return tr("Stand nicht lesbar: ") + e.getMessage();
			}
		}	}

	// ============================================================================================================
	/** Naeherungskoordinaten: Pruefung, Rueckfrage, Uebernahme, Ruecknahme. */
	private final class Approximation_Part {

		/** Pruefsumme des Datenstands (Punkte, Beobachtungen, Toleranzen) - geprueft wird nur, wenn sie sich geaendert hat. */
		private String approximationFingerprint(List<Klein_Method_Functions.Klein_Method_Approximation.Point> points,
				List<Klein_Method_Functions.Klein_Method_Approximation.Observation> observations) {
			java.util.zip.CRC32 crc = new java.util.zip.CRC32();
			List<String> lines = new ArrayList<String>();
			for (Klein_Method_Functions.Klein_Method_Approximation.Point p : points)
				lines.add(p.name + "|" + p.isNew + "|" + p.dimension + "|" + p.y0 + "|" + p.x0 + "|" + p.z0);
			java.util.Collections.sort(lines);
			for (String line : lines)
				crc.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			for (Klein_Method_Functions.Klein_Method_Approximation.Observation o : observations)
				crc.update((o.type + "|" + o.group + "|" + o.from + "|" + o.to + "|" + o.ih + "|" + o.th + "|" + o.value + "|" + o.gy + "|" + o.gx + "|" + o.gz)
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			crc.update((Klein_Method.this.parameter.getApproximationTolerancePosition() + "|" + Klein_Method.this.parameter.getApproximationToleranceHeight() + "|" + Klein_Method.this.parameter.isApproximationPriority())
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return Long.toHexString(crc.getValue()) + "-" + points.size() + "-" + observations.size();
		}
		/** Pruefsumme nach einer Aenderung neu lesen und speichern (keine erneute Frage fuer diesen Stand). */
		private void storeApproximationFingerprint() throws SQLException {
			String hash = this.approximationFingerprint(Klein_Method.this.databaseHandler.readApproximationPoints(), Klein_Method.this.databaseHandler.readApproximationObservations());
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_APPROXIMATION_HASH, hash);
		}
		/**
		 * Naeherungskoordinaten der Neupunkte pruefen (nur freie AGL). Aufruf im Hintergrund mit ADJUSTMENT_LOCK.
		 *
		 * @param force true = auf Anforderung (auch bei unveraendertem Datenstand, mit Rueckmeldung)
		 *
		 * @return true, wenn Koordinaten geschrieben wurden
		 */
		private boolean checkApproximationCoordinates(boolean force) {
			return this.checkApproximationCoordinates(force, false);
		}
		/**
		 * anyStep = true (Menue "Klein_Method") rechnet in jeder Ausgleichungsstufe; die Automatik nur in der freien AGL.
		 */
		private boolean checkApproximationCoordinates(boolean force, boolean anyStep) {
			try {
				Klein_Method_Adjustment_Step step = Klein_Method.this.getCurrentAdjustmentStep();
				// fixe/dynamische AGL: die Automatik ergaenzt nur fehlende Naeherungen (0,000 bzw. anderes System) - vorhandene,
				// plausible Naeherungen bleiben unangetastet (die Ausgleichung baut darauf auf)
				boolean missingOnly = !anyStep && step != Klein_Method_Adjustment_Step.FREE;
				if (Klein_Method.this.parameter.getApproximationTolerancePosition() == 0.0 && Klein_Method.this.parameter.getApproximationToleranceHeight() == 0.0) {
					if (force)
						this.showApproximationInfo(tr("Beide Toleranzen stehen auf 0 - die Prüfung ist ausgeschaltet."));
					return false;
				}
				List<Klein_Method_Functions.Klein_Method_Approximation.Point> points = Klein_Method.this.databaseHandler.readApproximationPoints();
				List<Klein_Method_Functions.Klein_Method_Approximation.Observation> observations = Klein_Method.this.databaseHandler.readApproximationObservations();
				int newPoints = 0;
				for (Klein_Method_Functions.Klein_Method_Approximation.Point p : points)
					if (p.isNew)
						newPoints++;
				if (newPoints == 0 || observations.isEmpty()) {
					if (force)
						this.showApproximationInfo(tr("Keine Neupunkte bzw. keine Beobachtungen - nichts zu prüfen."));
					return false;
				}
				// geozentrische 3D-Basislinien neben lokalen Punkten (z. B. nach "Nicht umrechnen"): jede Naeherung waere falsch
				int mixed = Klein_Method.this.conversionPart.mixedGeocentricBaselines();
				if (mixed > 0) {
					Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten nicht geprueft - " + mixed + " 3D-Basislinien noch geozentrisch neben lokalen Punkten.");
					if (force)
						this.showApproximationInfo(String.format(tr("%d 3D-Basislinie(n) sind noch geozentrisch, die Punkte aber lokal - so passen Näherungen und "
								+ "Ausgleichung nicht. Bitte zuerst ins lokale System umrechnen (Menü Klein_Method „Näherungskoordinaten berechnen und prüfen“) "
								+ "oder diese Basislinien deaktivieren."), mixed));
					return false;
				}
				String hash = this.approximationFingerprint(points, observations);
				if (!force && hash.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_APPROXIMATION_HASH))) {
					Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten - Datenstand unveraendert, keine erneute Pruefung.");
					return false;
				}
				Klein_Method_Functions.Klein_Method_Approximation.Settings settings = new Klein_Method_Functions.Klein_Method_Approximation.Settings();
				settings.tolPosition = Klein_Method.this.parameter.getApproximationTolerancePosition();
				settings.tolHeight = Klein_Method.this.parameter.getApproximationToleranceHeight();
				settings.priority = Klein_Method.this.parameter.isApproximationPriority();
				settings.grossLimit = Klein_Method.this.parameter.getGrossErrorLinearLimit() > 0.0 ? Klein_Method.this.parameter.getGrossErrorLinearLimit() : 0.10;
				double[] curvature = Klein_Method.this.databaseHandler.readEarthCurvatureSetting();
				settings.curvature = curvature[0] > 0.0;
				settings.earthRadius = curvature[1];
				// lokales System um P0 mit "lokal ellipsoidisch": Hoehenunterschiede entlang des Lots auf die Oben-Achse beziehen
				Klein_Method_Functions.Klein_Method_Local_System.Parameters localSystem = Klein_Method_Functions.Klein_Method_Local_System.Parameters
						.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
				if (localSystem != null && Klein_Method.this.databaseHandler.readProjectionType() == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_ELLIPSOIDAL) {
					settings.localFrame = true;
					settings.frameEast = localSystem.addEast;
					settings.frameNorth = localSystem.addNorth;
				}
				// Hoehensysteme gemischt (NHN neben ellipsoidisch): Hoehen nur aus einem System - 1D-Festpunkte nicht zur Lagerung
				try {
					Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check heightCheck = Klein_Method.this.quasigeoidPart.checkHeightSystems();
					settings.ignoreHeightAnchors1D = heightCheck.verdict == 1;
					if (settings.ignoreHeightAnchors1D)
						Klein_Method.this.logger.log("Klein_Method: Naeherung | Hoehensysteme gemischt (h - H = " + String.format(Locale.GERMANY, "%.2f", heightCheck.difference)
								+ " m) - Hoehenfestpunkte (1D) nicht zur Lagerung, Hoehen nur aus GNSS, Nivellement relativ angehaengt.");
				} catch (SQLException e) {
					settings.ignoreHeightAnchors1D = false;
				}
				long start = System.currentTimeMillis();
				Klein_Method_Functions.Klein_Method_Approximation.Result result = Klein_Method_Functions.Klein_Method_Approximation.compute(points, observations, settings);
				long millis = System.currentTimeMillis() - start;
				if (settings.localFrame)
					Klein_Method.this.logger.log("Klein_Method: Naeherung | Hoehen im lokalen System um P0: Nivellement und Zenitwinkel auf die Oben-Achse bezogen (r^2/2R).");
				Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten berechnet (" + millis + " ms): " + result.newPoints.size() + " Neupunkte, " + result.differing.size()
						+ " abweichend, " + result.betterCount + " mit neuen Koordinaten besser, nicht bestimmbar " + result.notDetermined.size() + ", Lagerung "
						+ result.frameSource + " (" + result.framePoints + " Punkte, Rest " + String.format(Locale.GERMANY, "%.1f", result.frameResidualMm)
						+ " mm, Massstab " + String.format(Locale.GERMANY, "%.1f", result.scalePpm) + " ppm), auffaellige Beobachtungen " + result.suspicious.size()
						+ (result.failure == null ? "" : ", Hinweis: " + result.failure) + ".");
				if (missingOnly) {
					// nur Neupunkte ohne brauchbare Naeherung anbieten
					Set<String> missing = new HashSet<String>(result.zeroDetermined);
					missing.addAll(result.foreign);
					result.differing.removeIf(r -> !missing.contains(r.name));
					Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten (" + step + "): " + result.differing.size()
							+ " Neupunkt(e) ohne brauchbare Naeherung (0,000 bzw. anderes System) - nur diese werden angeboten.");
					if (result.differing.isEmpty()) {
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_APPROXIMATION_HASH, hash);
						if (force)
							this.showApproximationInfo(tr("Alle Neupunkte haben brauchbare Näherungen - in der fixen bzw. dynamischen Ausgleichung werden nur fehlende "
									+ "Näherungen (0,000 oder anderes System) ergänzt."));
						return false;
					}
				}
				this.logApproximationDetails(result, settings);
				this.reportGnssSessions(result);
				if (result.differing.isEmpty()) {
					Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_APPROXIMATION_HASH, hash);
					if (force)
						this.showApproximationInfo(String.format(tr("Alle %d Neupunkte stimmen innerhalb der Toleranz mit der neuen Berechnung überein - nichts zu tun."),
								result.newPoints.size()) + this.approximationHints(result));
					else if (this.gnssSessionsNew)
						this.showApproximationInfo(this.gnssSessionText(result));
					return false;
				}
				boolean useNew = this.askApproximationDecision(result, settings);
				boolean written = false;
				if (useNew) {
					String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
					// fuer die Probe-Ausgleichung: groesste Aenderungen merken
					List<Klein_Method_Functions.Klein_Method_Approximation.PointResult> changes = new ArrayList<Klein_Method_Functions.Klein_Method_Approximation.PointResult>(result.differing);
					changes.sort((p, q) -> Double.compare(this.change(q), this.change(p)));
					this.lastWrittenChanges.clear();
					for (int i = 0; i < Math.min(8, changes.size()); i++) {
						Klein_Method_Functions.Klein_Method_Approximation.PointResult p = changes.get(i);
						this.lastWrittenChanges.add(p.name + String.format(Locale.GERMANY, " (%s %.3f m)", tr("Änderung"), this.change(p)));
					}
					int count = Klein_Method.this.databaseHandler.writeApproximations(result.differing, stamp);
					Klein_Method.this.approximationsRestoredPending = false;
					written = count > 0;
					Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten von " + count + " Neupunkt(en) uebernommen (alte Werte gesichert, " + stamp + ").");
					Klein_Method.this.getProtocol().logEvent("NAEHERUNGSKOORDINATEN: " + count + " Neupunkt(e) neu uebernommen (Sicherung " + stamp + ")");
				}
				else {
					Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten - Anwender behaelt die vorhandenen Werte.");
					Klein_Method.this.getProtocol().logEvent("NAEHERUNGSKOORDINATEN: vorhandene Werte behalten (" + result.differing.size() + " Abweichung(en))");
				}
				this.offerGnssDeactivation(result);
				this.storeApproximationFingerprint();
				return written;
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Pruefung der Naeherungskoordinaten nicht moeglich: " + e.getMessage());
				if (force)
					this.showApproximationInfo(tr("Prüfung nicht möglich: ") + e.getMessage());
				return false;
			}
		}
		/** Ausgleichung nach dem Uebernehmen der Naeherungen gescheitert: Log, Protokoll, Hinweis mit den groessten Aenderungen. */
		void reportFailedTrial(EstimationStateType state) {
			String list = String.join(", ", this.lastWrittenChanges);
			Klein_Method.this.logger.log("Klein_Method: Ausgleichung nach Uebernahme der Naeherungen nicht erfolgreich (" + state + ") - groesste Aenderungen: " + list);
			Klein_Method.this.getProtocol().logEvent("NAEHERUNGSKOORDINATEN: Probe-Ausgleichung " + state + ", groesste Aenderungen " + list);
			String text = String.format(tr("Die Ausgleichung rechnet mit den gerade übernommenen Näherungskoordinaten nicht (%s). Am meisten geändert haben sich: %s.\n\n"
					+ "Bitte dort Punktnummern, Antennen-/Reflektorhöhen und die Messungen prüfen. Rücknahme der Näherungen: Menü Klein_Method "
					+ "„Letzte Übernahme der Näherungskoordinaten zurücknehmen …“."), String.valueOf(state), list);
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.WARNING);
				alert.setTitle(tr("Klein_Method - Näherungskoordinaten"));
				alert.setHeaderText(tr("Probe-Ausgleichung nicht erfolgreich"));
				alert.setContentText(text);
				alert.show();
			});
		}
		/** Hinweise (nicht bestimmbar, mehrdeutig, nicht ueberbestimmt, Altpunkte, auffaellige Beobachtungen). */
		private String approximationHints(Klein_Method_Functions.Klein_Method_Approximation.Result result) {
			StringBuilder text = new StringBuilder();
			String nl = "\n";
			if (!result.notDetermined.isEmpty())
				text.append(nl).append(nl).append(tr("Nicht bestimmbar (behalten ihre Werte): ")).append(this.joinCapped(result.notDetermined, 10));
			if (!result.zeroNotDetermined.isEmpty())
				text.append(nl).append(nl).append(tr("Mit 0,000 eingetragen, aber aus den Messungen nicht bestimmbar (bleiben unver\u00E4ndert): "))
						.append(this.joinCapped(result.zeroNotDetermined, 10));
			if (!result.ambiguous.isEmpty())
				text.append(nl).append(nl).append(tr("Mehrdeutig (nur zwei Strecken, Spiegelpunkt möglich): ")).append(this.joinCapped(result.ambiguous, 10));
			if (!result.weak.isEmpty())
				text.append(nl).append(nl).append(tr("Nicht überbestimmt (neue Lage nicht kontrollierbar, wird nur ohne vorhandene Näherung übernommen): "))
						.append(this.joinCapped(result.weak, 10));
			if (!result.frameExcluded.isEmpty())
				text.append(nl).append(nl).append(tr("Altpunkte, die nicht zu den Messungen passen (für die Lagerung nicht verwendet): "))
						.append(this.joinCapped(result.frameExcluded, 10));
			if (!result.heightAnchorsIgnored.isEmpty())
				text.append(nl).append(nl).append(tr("Höhensysteme gemischt (NHN neben ellipsoidisch): Die Höhenfestpunkte wurden für die Näherungen nicht "
						+ "verwendet - die Höhen kommen nur aus GNSS, das Nivellement hängt relativ daran. Für die Ausgleichung die Höhenfestpunkte "
						+ "umrechnen („Quasigeoide verwalten …“ → „Höhenfestpunkte umrechnen …“): ")).append(this.joinCapped(result.heightAnchorsIgnored, 10));
			if (!result.oneDimensionalFar.isEmpty())
				text.append(nl).append(nl).append(String.format(tr("%d 1D-Punkt(e) liegen laut eingetragener Lage mehr als 20 km von P0 entfernt (z. B. Lage 0/0 oder UTM). "
						+ "In „lokal ellipsoidisch“ bezieht JAG3D das Nivellement auf diese Lage - bitte eine Lage im Messgebiet eintragen: "),
						result.oneDimensionalFar.size())).append(this.joinCapped(result.oneDimensionalFar, 10));
			if (!result.suspicious.isEmpty()) {
				List<String> lines = new ArrayList<String>();
				for (String entry : result.suspicious) {
					int bar = entry.lastIndexOf('|');
					int type = 0;
					try {
						type = Integer.parseInt(entry.substring(bar + 1));
					} catch (NumberFormatException e) {
						type = 0;
					}
					lines.add(entry.substring(0, bar).replace(" -> ", " → ") + " (" + approximationTypeName(type) + ")");
				}
				text.append(nl).append(nl).append(tr("Auffällige Beobachtungen (in der Vorausgleichung verworfen - bitte prüfen): ")).append(this.joinCapped(lines, 12));
			}
			// konkrete Pruefhinweise
			for (int i = 0; i < Math.min(5, result.conflicts.size()); i++) {
				Klein_Method_Functions.Klein_Method_Approximation.Conflict c = result.conflicts.get(i);
				List<String> parts = new ArrayList<String>();
				for (Map.Entry<Integer, Integer> e : c.totalByType.entrySet())
					parts.add(approximationTypeName(e.getKey()) + " " + c.rejectedByType.getOrDefault(e.getKey(), 0) + "/" + e.getValue());
				text.append(nl).append(nl).append(String.format(tr("Widersprüchliche Beobachtungen an Punkt %s: %d von %d verworfen (%s). Die Messungen zeigen auf "
						+ "verschiedene Stellen - bitte Exzentrizität, Antennen-/Reflektorhöhe oder Punktverwechslung prüfen."), c.name, c.rejected, c.total,
						String.join(", ", parts)));
				if (c.isNew && 2 * c.rejected > c.total)
					text.append(' ').append(tr("Die berechnete Lage dieses Punktes ist unsicher."));
			}
			if (result.conflicts.size() > 5)
				text.append(nl).append(String.format(tr(" … und %d weitere"), result.conflicts.size() - 5));
			if (!result.multiStartMoved.isEmpty())
				text.append(nl).append(nl).append(tr("Neu gerechnet von der Lage der größeren Beobachtungsgruppe aus (vorher passten die meisten Beobachtungen nicht): "))
						.append(this.joinCapped(result.multiStartMoved, 10));
			if (!result.unreliable.isEmpty())
				text.append(nl).append(nl).append(tr("Lage nicht eindeutig - die Beobachtungen am Punkt widersprechen sich, keine Lage passt zur Mehrheit (wird nicht übernommen, bitte Messungen prüfen): "))
						.append(this.joinCapped(result.unreliable, 10));
			if (!result.stationChecks.isEmpty()) {
				List<String> lines = new ArrayList<String>();
				for (Klein_Method_Functions.Klein_Method_Approximation.Station_Check c : result.stationChecks) {
					if (c.commonTargets < 3) {
						lines.add(String.format(tr("%s: nur %d gemeinsame(s) Ziel(e) - keine Kontrolle möglich"), c.station, c.commonTargets));
						continue;
					}
					String cross = Double.isFinite(c.worstCross) ? String.format(Locale.GERMANY, " = %.1f mm", c.worstCross * 1000.0) : "";
					boolean noticeable = Double.isFinite(c.worstCross) && c.worstCross > Klein_Method_Functions.Klein_Method_Approximation.STATION_CHECK_CROSS;
					lines.add(String.format(tr("%s: %d gemeinsame Ziele, größter Rest %s %s mgon%s quer"), c.station, c.commonTargets, c.worstTarget,
							String.format(Locale.GERMANY, "%.1f", c.worstResidual * 200000.0 / Math.PI), cross) + (noticeable ? tr(" - auffällig") : ""));
				}
				text.append(nl).append(nl).append(tr("Mehrfach aufgestellte Standpunkte (Kontrolle über die gemeinsamen Ziele, jede Aufstellung behält ihre eigene Orientierung): "));
				for (int i = 0; i < Math.min(10, lines.size()); i++)
					text.append(nl).append("  ").append(lines.get(i));
				if (lines.size() > 10)
					text.append(nl).append(String.format(tr(" … und %d weitere"), lines.size() - 10));
			}
			if (!result.mixedDirectionGroups.isEmpty()) {
				int maxStations = 0;
				for (int[] g : result.mixedDirectionGroups)
					maxStations = Math.max(maxStations, g[1]);
				text.append(nl).append(nl).append(String.format(tr("%d Richtungsgruppe(n) enthalten Richtungen von mehreren Standpunkten (bis zu %d). JAG3D rechnet je "
						+ "Gruppe nur EINE Orientierung - so passt keine Ausgleichung. Richtungen neu importieren mit „Einstellungen → Importeinstellungen → "
						+ "Horizontalrichtungen“ (standpunktweise Gruppierung)."), result.mixedDirectionGroups.size(), maxStations));
			}
			String sessions = this.gnssSessionText(result);
			if (sessions != null)
				text.append(nl).append(nl).append(sessions);
			if (result.failure != null)
				text.append(nl).append(nl).append(tr("Hinweis: ")).append(result.failure);
			return text.toString();
		}
		/** true = der letzte Befund der GNSS-Sitzungspruefung ist neu (noch nicht gemeldet). */
		private boolean gnssSessionsNew = false;
		/** groesste Aenderungen der zuletzt uebernommenen Naeherungen (fuer den Hinweis nach einer gescheiterten Probe-Ausgleichung). */
		final List<String> lastWrittenChanges = new ArrayList<String>();

		/** Aenderung einer Naeherung (Lage und Hoehe zusammen; 0,000-Werte und Werte im anderen System zaehlen nicht). */
		private double change(Klein_Method_Functions.Klein_Method_Approximation.PointResult p) {
			double d = 0.0;
			if (Double.isFinite(p.dPosition) && !p.zeroPosition && !p.foreignPosition)
				d = Math.max(d, p.dPosition);
			if (Double.isFinite(p.dHeight) && !p.zeroHeight && !p.foreignHeight)
				d = Math.max(d, Math.abs(p.dHeight));
			return d;
		}

		/**
		 * GNSS-Sitzungen mit Drift/Versatz, deren Basislinien an einem Punkt ueberwiegend (>= 80 %, mind. 4) in der
		 * Vorausgleichung verworfen wurden, waehrend der Punkt ueber mind. 3 terrestrische Beobachtungen bestimmbar bleibt:
		 * Deaktivieren anbieten (veraendert das Projekt, nur nach Rueckfrage, je Befund einmal).
		 */
		private void offerGnssDeactivation(Klein_Method_Functions.Klein_Method_Approximation.Result result) throws Exception {
			java.util.Set<String> sessionPoints = new java.util.HashSet<String>();
			for (Klein_Method_Functions.Klein_Method_Approximation.Gnss_Session_Finding f : result.gnssSessionFindings) {
				// Befund je Punkt ("A") oder je Punktpaar ("A - B")
				sessionPoints.add(f.point);
				sessionPoints.addAll(java.util.Arrays.asList(f.point.split(" - ")));
			}
			String offered = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_GNSS_DEACTIVATION_OFFERED);
			offered = offered == null ? "" : offered;
			// verdaechtige GNSS-Punkte (ueberwiegend verworfen) fuer die Pruefung der GNSS-Stuetzung merken
			StringBuilder suspects = new StringBuilder(";");
			for (Klein_Method_Functions.Klein_Method_Approximation.Conflict c : result.conflicts) {
				int t = 0, rj = 0;
				for (int type : new int[] { 6, 7, 8 }) {
					t += c.totalByType.getOrDefault(type, 0);
					rj += c.rejectedByType.getOrDefault(type, 0);
				}
				if (rj >= 4 && 5 * rj >= 4 * t)
					suspects.append(c.name).append(';');
			}
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_GNSS_SUSPECT, suspects.toString());
			for (Klein_Method_Functions.Klein_Method_Approximation.Conflict c : result.conflicts) {
				if (!sessionPoints.contains(c.name) || offered.contains(";" + c.name + ";"))
					continue;
				int total = 0, rejected = 0;
				for (int type : new int[] { 6, 7, 8 }) {
					total += c.totalByType.getOrDefault(type, 0);
					rejected += c.rejectedByType.getOrDefault(type, 0);
				}
				List<Integer> ids = result.rejectedGnssIds.get(c.name);
				// nur wenn der Punkt ohne diese Basislinien bestimmbar bleibt (mind. 3 nicht verworfene terrestrische Beobachtungen)
				int otherKept = (c.total - total) - (c.rejected - rejected);
				if (ids == null || rejected < 4 || 5 * rejected < 4 * total || otherKept < 3)
					continue;
				// letzter GNSS-Stuetzpunkt eines Randbereichs: Deaktivieren wuerde den Rand ganz von GNSS abhaengen - nicht anbieten
				List<String> edge = Klein_Method.this.gnssSupportPart.dependentsIfRemoved(c.name);
				if (!edge.isEmpty()) {
					offered = (offered.isEmpty() ? ";" : offered) + c.name + ";";
					Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_GNSS_DEACTIVATION_OFFERED, offered);
					Klein_Method.this.logger.log("Klein_Method: GNSS-Basislinien an " + c.name + " auffaellig, aber " + c.name + " ist der letzte GNSS-Stuetzpunkt fuer "
							+ edge.size() + " Randpunkt(e) - Deaktivieren nicht angeboten, GNSS dort nachmessen.");
					String hint = String.format(tr("Am Punkt %s widersprechen %d von %d GNSS-Basislinien den übrigen Messungen, und die GNSS-Sitzungen zeigen Drift "
							+ "oder Versatz. %s ist aber der letzte GNSS-Stützpunkt für den Randbereich (%d Punkte, z. B. %s) - ohne ihn würde der Rand nur "
							+ "über Tachymetermessungen weitergetragen und wegdriften. Deaktivieren wird deshalb nicht angeboten.\n\nEmpfehlung: GNSS an %s "
							+ "oder am äußersten Randpunkt neu messen bzw. die Auswertung der Sitzung prüfen."), c.name, rejected, total, c.name, edge.size(),
							String.join(", ", edge.subList(0, Math.min(5, edge.size()))), c.name);
					this.showApproximationInfo(hint);
					continue;
				}
				offered = (offered.isEmpty() ? ";" : offered) + c.name + ";";
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_GNSS_DEACTIVATION_OFFERED, offered);
				final List<Integer> deactivate = new ArrayList<Integer>(new java.util.LinkedHashSet<Integer>(ids));
				String text = String.format(tr("Am Punkt %s widersprechen %d von %d GNSS-Basislinien den übrigen Messungen, und die GNSS-Sitzungen zeigen "
						+ "Drift oder Versatz. Diese %d Basislinien deaktivieren? Die Lage des Punkts kommt dann aus den übrigen Messungen. "
						+ "Wieder aktivieren: in JAG3D bei den Basislinien den Haken „enable“ setzen."), c.name, rejected, total, deactivate.size());
				java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
				boolean[] confirmed = new boolean[] { false };
				Platform.runLater(() -> {
					try {
						ButtonType yes = new ButtonType(tr("Basislinien deaktivieren"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
						ButtonType no = new ButtonType(tr("Nicht jetzt"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
						Alert alert = new Alert(AlertType.CONFIRMATION, text, yes, no);
						alert.setTitle(tr("Klein_Method - GNSS-Sitzungen"));
						alert.setHeaderText(tr("Auffällige GNSS-Basislinien deaktivieren?"));
						confirmed[0] = alert.showAndWait().filter(button -> button == yes).isPresent();
					} finally {
						latch.countDown();
					}
				});
				latch.await();
				if (!confirmed[0]) {
					Klein_Method.this.logger.log("Klein_Method: GNSS-Basislinien an " + c.name + " deaktivieren - vom Anwender abgelehnt.");
					continue;
				}
				for (int id : deactivate)
					Klein_Method.this.databaseHandler.deactivateGnssObservation(id);
				Klein_Method.this.logger.log("Klein_Method: " + deactivate.size() + " GNSS-Basislinien an " + c.name + " deaktiviert (Ids " + deactivate + ").");
				Klein_Method.this.getProtocol().logEvent("GNSS-SITZUNGEN: " + deactivate.size() + " Basislinien an " + c.name + " deaktiviert (" + rejected + " von " + total
						+ " in der Vorausgleichung verworfen)");
				Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
				Klein_Method.this.refreshJag3dView(true);
			}
		}
		/**
		 * Befund der GNSS-Sitzungspruefung ins Protokoll (nur bei neuem Befund) und merken, ob er neu ist - dann
		 * meldet die Hintergrundpruefung ihn auch ohne abweichende Naeherungen einmal im Fenster.
		 */
		private void reportGnssSessions(Klein_Method_Functions.Klein_Method_Approximation.Result result) throws SQLException {
			this.gnssSessionsNew = false;
			if (result.gnssSessionFindings.isEmpty())
				return;
			StringBuilder key = new StringBuilder();
			for (Klein_Method_Functions.Klein_Method_Approximation.Gnss_Session_Finding f : result.gnssSessionFindings)
				key.append(f.point).append(';');
			String shown = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_GNSS_SESSION_SHOWN);
			if (key.toString().equals(shown))
				return;
			this.gnssSessionsNew = true;
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_GNSS_SESSION_SHOWN, key.toString());
			for (Klein_Method_Functions.Klein_Method_Approximation.Gnss_Session_Finding f : result.gnssSessionFindings)
				Klein_Method.this.getProtocol().logEvent("GNSS-SITZUNGEN: " + this.gnssSessionLine(f, false));
		}
		/** Hinweis GNSS-Sitzungen mit Drift oder Versatz (Fenster), null ohne Befund. */
		private String gnssSessionText(Klein_Method_Functions.Klein_Method_Approximation.Result result) {
			if (result.gnssSessionFindings.isEmpty())
				return null;
			String nl = System.lineSeparator();
			StringBuilder text = new StringBuilder(String.format(tr("GNSS-Sitzungen mit Drift oder Versatz über %s m (3D-Basislinien je Punktpaar in "
					+ "Messreihenfolge ausgewertet):"), String.format(Locale.GERMANY, "%.2f", Klein_Method_Functions.Klein_Method_Approximation.GNSS_DRIFT_LIMIT)));
			List<Klein_Method_Functions.Klein_Method_Approximation.Gnss_Session_Finding> list = result.gnssSessionFindings;
			for (int i = 0; i < Math.min(8, list.size()); i++)
				text.append(nl).append("  ").append(this.gnssSessionLine(list.get(i), true));
			if (list.size() > 8)
				text.append(nl).append(String.format(tr(" … und %d weitere"), list.size() - 8));
			text.append(nl).append(tr("Wandert ein Punkt innerhalb einer Sitzung, deutet das auf nicht gelöste Mehrdeutigkeiten (Float-Lösung), Mehrwege oder "
					+ "eine bewegte Antenne hin. Ein Versatz einer Sitzung gegen die übrigen deutet auf eine andere Aufstellung, Antennenhöhe oder "
					+ "Punktverwechslung hin. Bitte die Auswertung dieser Sitzungen prüfen - die Ausgleichung kann solche Fehler nur teilweise erkennen."));
			return text.toString();
		}
		/** eine Zeile je Befund; translated = Fenster (tr), sonst deutsch fuers Log. */
		private String gnssSessionLine(Klein_Method_Functions.Klein_Method_Approximation.Gnss_Session_Finding f, boolean translated) {
			List<String> parts = new ArrayList<String>();
			if (f.drift > 0.0)
				parts.add(String.format(translated ? tr("Drift bis %s m in %d Basislinien (Höhe %s m%s)") : "Drift bis %s m in %d Basislinien (Hoehe %s m%s)",
						String.format(Locale.GERMANY, "%.2f", f.drift), f.driftEpochs, String.format(Locale.GERMANY, "%+.2f", f.driftUp),
						f.driftPartner == null ? "" : String.format(translated ? tr(", zu %s") : ", zu %s", f.driftPartner)));
			if (f.offset > 0.0)
				parts.add(String.format(translated ? tr("Versatz einer Sitzung bis %s m%s") : "Versatz einer Sitzung bis %s m%s",
						String.format(Locale.GERMANY, "%.2f", f.offset),
						f.offsetPartner == null ? "" : " (" + String.format(translated ? tr("zu %s") : "zu %s", f.offsetPartner) + ")"));
			String head = f.partners.isEmpty() ? f.point
					: String.format(translated ? tr("Punkt %s, an %d Stationen") : "Punkt %s, an %d Stationen", f.point, f.partners.size());
			return head + ": " + String.join("; ", parts) + " - " + String.format(translated ? tr("%d von %d Sitzungen auffällig") : "%d von %d Sitzungen auffaellig",
					f.flagged, f.sessions);
		}
		/**
		 * Einzelheiten der Naeherungsberechnung zusaetzlich in die Klein_Method.log (Projektordner) - alles, was sonst
		 * nur im Fenster steht, ungekuerzt und immer deutsch. Keine Koordinaten, nur Punktnamen, Abweichungen und
		 * Widersprueche. Geht nicht ins Schwarmwissen.
		 */
		private void logApproximationDetails(Klein_Method_Functions.Klein_Method_Approximation.Result result,
				Klein_Method_Functions.Klein_Method_Approximation.Settings settings) {
			List<String> lines = new ArrayList<String>();
			lines.add(String.format(Locale.GERMANY, "Toleranz Lage %.3f m / Hoehe %.3f m, Rangfolge %s, Lagerung %s auf %d Punkte (Hoehe %d), Rest %s, Massstab %s, "
					+ "Iterationen %d%s", settings.tolPosition, settings.tolHeight, settings.priority ? "ein" : "aus", result.frameSource, result.framePoints,
					result.frameHeightPoints, millimetres(result.frameResidualMm), Double.isFinite(result.scalePpm) ? String.format(Locale.GERMANY, "%.1f ppm", result.scalePpm) : "-",
					result.iterations, result.converged ? "" : " (nicht konvergiert)"));
			lines.add(String.format(Locale.GERMANY, "Widerspruch an den abweichenden Punkten (Median) vorhanden %s / neu %s, grobe Widersprueche (> %.3f m) vorhanden %d / neu %d, "
					+ "Empfehlung: %s", millimetres(result.medianOldMm), millimetres(result.medianNewMm), settings.grossLimit, result.grossOld, result.grossNew,
					result.recommendNew && result.betterCount > 0 ? "neue verwenden" : "vorhandene behalten"));
			if (!result.gnssSessionFindings.isEmpty()) {
				lines.add(String.format(Locale.GERMANY, "GNSS-Sitzungen mit Drift oder Versatz (> %.2f m, %d Befund(e)):",
						Klein_Method_Functions.Klein_Method_Approximation.GNSS_DRIFT_LIMIT, result.gnssSessionFindings.size()));
				for (Klein_Method_Functions.Klein_Method_Approximation.Gnss_Session_Finding f : result.gnssSessionFindings)
					lines.add("  " + this.gnssSessionLine(f, false));
			}
			List<Klein_Method_Functions.Klein_Method_Approximation.PointResult> sorted = new ArrayList<Klein_Method_Functions.Klein_Method_Approximation.PointResult>(result.differing);
			sorted.sort((a, b) -> Double.compare(approximationSortKey(b), approximationSortKey(a)));
			if (!sorted.isEmpty())
				lines.add("Abweichende Neupunkte (" + sorted.size() + ", vorhanden -> neu):");
			for (Klein_Method_Functions.Klein_Method_Approximation.PointResult pr : sorted) {
				StringBuilder line = new StringBuilder("  ").append(pr.name).append(": ");
				if (pr.zeroPosition)
					line.append("Lage 0,000 eingetragen");
				else if (!pr.hadPosition && pr.positionDetermined)
					line.append("keine Naeherung vorhanden");
				else
					line.append("Lage ").append(metres(pr.dPosition)).append(pr.foreignPosition ? " (anderes System)" : "");
				if (pr.zeroHeight)
					line.append(", Hoehe 0,000 eingetragen");
				else
					line.append(", Hoehe ").append(metres(pr.dHeight)).append(pr.foreignHeight ? " (anderes System)" : "");
				line.append(" | Widerspruch vorhanden/neu ").append(millimetres(pr.differsPosition ? pr.misfitOldMm : pr.heightMisfitOldMm)).append(" / ")
						.append(millimetres(pr.differsPosition ? pr.misfitNewMm : pr.heightMisfitNewMm));
				if (pr.weak)
					line.append(" | nicht ueberbestimmt");
				if (pr.unreliable)
					line.append(" | Lage nicht eindeutig");
				line.append(" | ").append(pr.adopted() ? "wird uebernommen" : "bleibt");
				lines.add(line.toString());
			}
			this.addApproximationLogList(lines, "Anderes System (vorhandene Naeherung > 100 m Lage bzw. > 20 m Hoehe daneben)", result.foreign);
			this.addApproximationLogList(lines, "0,000 eingetragen, bestimmbar (angeboten)", result.zeroDetermined);
			this.addApproximationLogList(lines, "1D-Punkte mit Lage > 20 km von P0 (JAG3D reduziert das Nivellement ueber diese Lage)", result.oneDimensionalFar);
			this.addApproximationLogList(lines, "Hoehenfestpunkte wegen gemischter Hoehensysteme nicht zur Lagerung", result.heightAnchorsIgnored);
			this.addApproximationLogList(lines, "0,000 eingetragen, nicht bestimmbar (bleiben)", result.zeroNotDetermined);
			this.addApproximationLogList(lines, "Nicht bestimmbar (behalten ihre Werte)", result.notDetermined);
			this.addApproximationLogList(lines, "Mehrdeutig (nur zwei Strecken, Spiegelpunkt moeglich)", result.ambiguous);
			this.addApproximationLogList(lines, "Nicht ueberbestimmt", result.weak);
			this.addApproximationLogList(lines, "Altpunkte nicht fuer die Lagerung verwendet (passen nicht zu den Messungen)", result.frameExcluded);
			this.addApproximationLogList(lines, "Mehrfachstart: von der Lage der groesseren Beobachtungsgruppe neu gerechnet", result.multiStartMoved);
			this.addApproximationLogList(lines, "Lage nicht eindeutig (nicht uebernommen, Messungen pruefen)", result.unreliable);
			if (!result.suspicious.isEmpty()) {
				// gleiche Beobachtung (z. B. wiederholte GNSS-Sessions) zusammengefasst mit Anzahl
				Map<String, Integer> counted = new java.util.LinkedHashMap<String, Integer>();
				for (String entry : result.suspicious) {
					int bar = entry.lastIndexOf('|');
					int type = 0;
					try {
						type = Integer.parseInt(entry.substring(bar + 1));
					} catch (NumberFormatException | StringIndexOutOfBoundsException e) {
						type = 0;
					}
					counted.merge((bar < 0 ? entry : entry.substring(0, bar)) + " (" + approximationTypeNameGerman(type) + ")", 1, Integer::sum);
				}
				lines.add("Auffaellige Beobachtungen (in der Vorausgleichung verworfen, " + result.suspicious.size() + ", gleiche zusammengefasst):");
				for (Map.Entry<String, Integer> e : counted.entrySet())
					lines.add("  " + e.getKey() + (e.getValue() > 1 ? " x" + e.getValue() : ""));
			}
			for (Klein_Method_Functions.Klein_Method_Approximation.Conflict c : result.conflicts) {
				List<String> parts = new ArrayList<String>();
				for (Map.Entry<Integer, Integer> e : c.totalByType.entrySet())
					parts.add(approximationTypeNameGerman(e.getKey()) + " " + c.rejectedByType.getOrDefault(e.getKey(), 0) + "/" + e.getValue());
				lines.add("Widerspruechliche Beobachtungen an " + (c.isNew ? "Neupunkt " : "Altpunkt ") + c.name + ": " + c.rejected + " von " + c.total
						+ " verworfen (" + String.join(", ", parts) + ")" + (c.isNew && 2 * c.rejected > c.total ? " - berechnete Lage unsicher" : ""));
			}
			if (!result.stationChecks.isEmpty()) {
				lines.add("Mehrfach aufgestellte Standpunkte (Kontrolle ueber gemeinsame Ziele):");
				for (Klein_Method_Functions.Klein_Method_Approximation.Station_Check c : result.stationChecks) {
					if (c.commonTargets < 3) {
						lines.add("  " + c.station + ": nur " + c.commonTargets + " gemeinsame(s) Ziel(e) - keine Kontrolle moeglich");
						continue;
					}
					boolean noticeable = Double.isFinite(c.worstCross) && c.worstCross > Klein_Method_Functions.Klein_Method_Approximation.STATION_CHECK_CROSS;
					lines.add(String.format(Locale.GERMANY, "  %s: %d gemeinsame Ziele, groesster Rest %s %s mgon = %s quer%s", c.station,
							c.commonTargets, c.worstTarget, Double.isFinite(c.worstResidual) ? String.format(Locale.GERMANY, "%.1f", c.worstResidual * 200000.0 / Math.PI) : "-",
							Double.isFinite(c.worstCross) ? millimetres(c.worstCross * 1000.0) : "-", noticeable ? " - auffaellig" : ""));
				}
			}
			for (int[] g : result.mixedDirectionGroups)
				lines.add("Richtungsgruppe " + g[0] + " enthaelt Richtungen von " + g[1] + " Standpunkten (JAG3D: nur eine Orientierung je Gruppe)");
			if (result.failure != null)
				lines.add("Hinweis: " + result.failure);
			for (String line : lines)
				Klein_Method.this.logger.log("Klein_Method: Naeherung | " + line);
		}
		private void addApproximationLogList(List<String> lines, String title, List<String> values) {
			if (!values.isEmpty())
				lines.add(title + " (" + values.size() + "): " + String.join(", ", values));
		}
		private String joinCapped(List<String> values, int max) {
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < Math.min(max, values.size()); i++)
				text.append(i == 0 ? "" : ", ").append(values.get(i));
			if (values.size() > max)
				text.append(String.format(tr(" … und %d weitere"), values.size() - max));
			return text.toString();
		}
		/** Pop-up mit Empfehlung und Begruendung; wartet auf die Entscheidung. @return true = neue Koordinaten verwenden */
		private boolean askApproximationDecision(Klein_Method_Functions.Klein_Method_Approximation.Result result,
				Klein_Method_Functions.Klein_Method_Approximation.Settings settings) throws InterruptedException {
			String nl = "\n";
			StringBuilder text = new StringBuilder();
			text.append(String.format(tr("Neu berechnet aus allen Beobachtungen (Startwerte aus polaren Bestimmungen, Schnitten und Standpunktsystemen, "
					+ "danach robuste Vorausgleichung; Lagerung auf %d Altpunkte, Rest %s). %d von %d Neupunkten weichen um mehr als %s (Lage) bzw. %s (Höhe) ab."),
					result.framePoints, millimetres(result.frameResidualMm), result.differing.size(), result.newPoints.size(), metres(settings.tolPosition),
					metres(settings.tolHeight)));
			if ("vorhandene Koordinaten".equals(result.frameSource))
				text.append(' ').append(tr("Keine Altpunkte mit Lage vorhanden - gelagert auf die vorhandenen Koordinaten aller Punkte."));
			if (!result.zeroDetermined.isEmpty())
				text.append(nl).append(nl).append(String.format(tr("%d Neupunkt(e) haben 0,000 als N\u00E4herungskoordinate eingetragen - das ist kein plausibler Wert. "
						+ "Die neu berechneten Werte werden zur \u00DCbernahme angeboten."), result.zeroDetermined.size()));
			if (!result.foreign.isEmpty())
				text.append(nl).append(nl).append(String.format(tr("%d Neupunkt(e) liegen mehr als 100 m (Lage) bzw. 20 m (Höhe) neben der neuen Berechnung - "
						+ "die vorhandene Näherung stammt vermutlich aus einem anderen System (z. B. UTM mit Zonenkennung oder NHN-Höhe statt lokal). "
						+ "Die neu berechneten Werte werden immer zur Übernahme angeboten: "), result.foreign.size())).append(this.joinCapped(result.foreign, 12));
			text.append(nl).append(nl);
			if (result.recommendNew) {
				text.append(tr("Empfehlung: NEUE Näherungskoordinaten verwenden.")).append(nl);
				text.append(String.format(tr("Begründung: %d Punkt(e) passen mit den neuen Koordinaten deutlich besser zu den Messungen. "
						+ "Mittlerer Widerspruch (Median) der Beobachtungen an den abweichenden Punkten: %s statt %s; Widersprüche über %s: %d statt %d."),
						result.betterCount, millimetres(result.medianNewMm), millimetres(result.medianOldMm), metres(settings.grossLimit), result.grossNew, result.grossOld));
				if (result.missingApproximation)
					text.append(' ').append(tr("Einige Neupunkte haben noch keine Näherungskoordinaten."));
			}
			else {
				text.append(tr("Empfehlung: VORHANDENE Näherungskoordinaten behalten.")).append(nl);
				if (result.betterCount == 0)
					text.append(tr("Begründung: Die vorhandenen Koordinaten passen genauso gut oder besser zu den Messungen. Die Abweichung entsteht vermutlich "
							+ "durch Messungen, die die neue Rechnung als grob fehlerhaft verworfen hat, oder durch schwach bestimmte Punkte - bitte die unten "
							+ "genannten Beobachtungen prüfen."));
				else
					text.append(String.format(tr("Begründung: Mit den neuen Koordinaten entstehen mehr grobe Widersprüche (%d statt %d)."), result.grossNew,
							result.grossOld));
			}
			text.append(nl).append(nl).append(String.format(tr("„Neue verwenden“ überschreibt die Näherungskoordinaten von %d Punkt(en) - nur die, deren neue "
					+ "Koordinaten nachweislich besser passen. Die alten Werte werden gesichert und lassen sich mit „Letzte Übernahme zurücknehmen“ wiederherstellen."),
					result.betterCount));
			List<Klein_Method_Functions.Klein_Method_Approximation.PointResult> sorted = new ArrayList<Klein_Method_Functions.Klein_Method_Approximation.PointResult>(result.differing);
			sorted.sort((a, b) -> Double.compare(approximationSortKey(b), approximationSortKey(a)));
			text.append(nl).append(nl).append(tr("Größte Abweichungen (vorhanden → neu):"));
			for (int i = 0; i < Math.min(15, sorted.size()); i++) {
				Klein_Method_Functions.Klein_Method_Approximation.PointResult p = sorted.get(i);
				text.append(nl).append(p.name).append(": ");
				if (p.zeroPosition)
					text.append(tr("Lage 0,000 eingetragen"));
				else if (!p.hadPosition && p.positionDetermined)
					text.append(tr("keine N\u00E4herung vorhanden"));
				else
					text.append(tr("Lage ")).append(metres(p.dPosition)).append(p.foreignPosition ? tr(" (anderes System)") : "");
				if (p.zeroHeight)
					text.append(", ").append(tr("H\u00F6he 0,000 eingetragen"));
				else
					text.append(", ").append(tr("H\u00F6he ")).append(metres(p.dHeight)).append(p.foreignHeight ? tr(" (anderes System)") : "");
				text.append(" | ").append(tr("Widerspruch vorhanden/neu: ")).append(millimetres(p.differsPosition ? p.misfitOldMm : p.heightMisfitOldMm)).append(" / ")
						.append(millimetres(p.differsPosition ? p.misfitNewMm : p.heightMisfitNewMm));
				text.append(" | ").append(p.adopted() ? tr("wird übernommen") : tr("bleibt"));
			}
			if (sorted.size() > 15)
				text.append(nl).append(String.format(tr(" … und %d weitere"), sorted.size() - 15));
			text.append(this.approximationHints(result));
			String content = text.toString();
			boolean recommendNew = result.recommendNew && result.betterCount > 0;
			boolean anyAdoptable = result.betterCount > 0;
			java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			boolean[] useNew = new boolean[] { false };
			Platform.runLater(() -> {
				try {
					ButtonType newButton = new ButtonType(tr("Neue verwenden") + (recommendNew ? tr(" (empfohlen)") : ""),
							javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType keepButton = new ButtonType(tr("Vorhandene behalten") + (recommendNew ? "" : tr(" (empfohlen)")),
							javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					Alert alert = new Alert(AlertType.CONFIRMATION, "", newButton, keepButton);
					alert.setTitle(tr("Klein_Method - Näherungskoordinaten"));
					alert.setHeaderText(tr("Näherungskoordinaten der Neupunkte weichen ab"));
					TextArea area = new TextArea(content);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(18);
					area.setPrefColumnCount(80);
					alert.getDialogPane().setContent(area);
					alert.setResizable(true);
					((javafx.scene.control.Button) alert.getDialogPane().lookupButton(newButton)).setDefaultButton(recommendNew);
					((javafx.scene.control.Button) alert.getDialogPane().lookupButton(keepButton)).setDefaultButton(!recommendNew);
					alert.getDialogPane().lookupButton(newButton).setDisable(!anyAdoptable);
					useNew[0] = alert.showAndWait().filter(button -> button == newButton).isPresent();
				} finally {
					latch.countDown();
				}
			});
			latch.await();
			return useNew[0];
		}
		private void showApproximationInfo(String text) {
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Näherungskoordinaten"));
				alert.setHeaderText(null);
				TextArea area = new TextArea(text);
				area.setEditable(false);
				area.setWrapText(true);
				area.setPrefRowCount(Math.min(14, 3 + text.length() / 80));
				area.setPrefColumnCount(70);
				alert.getDialogPane().setContent(area);
				alert.setResizable(true);
				alert.show();
			});
		}
		private boolean approximationBusy() {
			return Klein_Method.this.approximationCheckRunning || Klein_Method.this.headlessTabRefreshRunning || Klein_Method.this.automatikRunning
					|| Klein_Method.this.isAutomaticTaskRunning() || Klein_Method.this.epStepRunning;
		}
		/** Knopf "Naeherungskoordinaten jetzt pruefen" (Hintergrund). */
		public void checkApproximationsOnDemand() {
			if (this.approximationBusy()) {
				this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Klein_Method.this.approximationCheckRunning = true;
			Thread thread = busyThread(() -> {
				boolean written = false;
				ADJUSTMENT_LOCK.lock();
				try {
					written = this.checkApproximationCoordinates(true);
				} finally {
					ADJUSTMENT_LOCK.unlock();
					Klein_Method.this.approximationCheckRunning = false;
				}
				if (written)
					Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus(); // Ergebnisse mit den neuen Naeherungen auffrischen
			}, "Klein_Method_Approximation_Check");
			thread.setDaemon(true);
			thread.start();
		}
		/**
		 * Menue "Klein_Method" -> Naeherungskoordinaten berechnen und pruefen - im Vorfeld, ohne den Tab zu oeffnen, in
		 * jeder Ausgleichungsstufe. Gleicher Ablauf wie der Knopf im Tab (Fenster mit Empfehlung, Sicherung).
		 */
		public void checkApproximationsFromMenu() {
			if (!Klein_Method.this.isProjectOpen()) {
				this.showApproximationInfo(tr("Kein Projekt geöffnet."));
				return;
			}
			if (this.approximationBusy()) {
				this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Thread thread = busyThread(() -> this.runApproximationBlocking(), "Klein_Method_Approximation_Menu");
			thread.setDaemon(true);
			thread.start();
		}

		/** Ablauf "Naeherungskoordinaten berechnen und pruefen" (Hintergrund-Thread, wartet auf die Rueckfragen). */
		void runApproximationBlocking() {
			Klein_Method.this.approximationCheckRunning = true;
			boolean written = false;
			ADJUSTMENT_LOCK.lock();
			try {
				Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten - Pruefung ueber das Menue gestartet.");
				// wie beim Oeffnen: zuerst Umrechnung ins lokale System bzw. P0-Verlegung anbieten (falls noetig)
				boolean converted = Klein_Method.this.checkLocalConversion(false);
				converted |= Klein_Method.this.relocateP0(false);
				Klein_Method.this.checkPrincipalPoint();
				written = this.checkApproximationCoordinates(true, true) || converted;
				if (converted)
					Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
			} finally {
				ADJUSTMENT_LOCK.unlock();
				Klein_Method.this.approximationCheckRunning = false;
			}
			if (written && Klein_Method.this.isKleinMethodTabActive())
				Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
		}
		/**
		 * nach dem Oeffnen eines Projekts automatisch im Hintergrund - wie beim Tab-Klick zuerst die Umrechnung ins
		 * lokale System (falls geozentrisch mit 3D-GNSS), dann die Naeherungskoordinaten (nur freie AGL, einmal je
		 * Datenstand). Kurz verzoegert, damit JAG3D das Projekt fertig geladen hat; laeuft der Tab-Lauf schon, uebernimmt
		 * der.
		 */
		private void scheduleApproximationAfterProjectOpen() {
			Thread thread = busyThread(() -> {
				try {
					Thread.sleep(2000L);
				} catch (InterruptedException e) {
					return;
				}
				if (!Klein_Method.this.isProjectOpen() || this.approximationBusy())
					return;
				Klein_Method.this.offerBestStateRecovery();
				Klein_Method.this.approximationCheckRunning = true;
				ADJUSTMENT_LOCK.lock();
				try {
					Klein_Method.this.logger.log("Klein_Method: Projekt geoeffnet - Pruefung Koordinatenumrechnung und Naeherungskoordinaten im Hintergrund.");
					Klein_Method.this.checkLocalConversion(false);
					Klein_Method.this.relocateP0(false);
					Klein_Method.this.checkPrincipalPoint();
					this.checkApproximationCoordinates(false, false);
					// Massen-Basislinien schon nach dem Oeffnen ausduennen (Schalter ein) - sonst rechnet eine F5 vor dem
					// ersten Tab-Klick mit allen Epochen
					if (Klein_Method.this.parameter.isMassThinning() && Klein_Method.this.massPart.prepare()) {
						String mass = Klein_Method.this.massPart.thin();
						if (mass != null)
							Klein_Method.this.showApproximationInfo(mass);
					}
				} finally {
					ADJUSTMENT_LOCK.unlock();
					Klein_Method.this.approximationCheckRunning = false;
				}
			}, "Klein_Method_Approximation_Open");
			thread.setDaemon(true);
			thread.start();
		}
		/** Knopf "Letzte Uebernahme zuruecknehmen" (Hintergrund, mit Rueckfrage). */
		public void restoreApproximationsOnDemand() {
			if (this.approximationBusy()) {
				this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Klein_Method.this.approximationCheckRunning = true;
			Thread thread = busyThread(() -> {
				boolean restored = false;
				try {
					String[] info;
					ADJUSTMENT_LOCK.lock();
					try {
						info = Klein_Method.this.databaseHandler.readApproximationBackupInfo();
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
					if (info == null) {
						this.showApproximationInfo(tr("Es gibt keine gesicherte Übernahme von Näherungskoordinaten."));
						return;
					}
					java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
					boolean[] confirmed = new boolean[] { false };
					Platform.runLater(() -> {
						try {
							Alert alert = new Alert(AlertType.CONFIRMATION);
							alert.setTitle(tr("Klein_Method - Näherungskoordinaten"));
							alert.setHeaderText(tr("Letzte Übernahme zurücknehmen?"));
							alert.setContentText(String.format(tr("Die Näherungskoordinaten von %s Neupunkt(en) werden auf die Werte vor der Übernahme vom %s zurückgesetzt."),
									info[1], info[0]));
							alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
							confirmed[0] = alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
						} finally {
							latch.countDown();
						}
					});
					latch.await();
					if (!confirmed[0])
						return;
					ADJUSTMENT_LOCK.lock();
					try {
						int count = Klein_Method.this.databaseHandler.restoreApproximationBackup();
						this.storeApproximationFingerprint(); // zurueckgesetzter Stand wird nicht erneut angefragt
						restored = count > 0;
						Klein_Method.this.approximationsRestoredPending = restored;
						Klein_Method.this.logger.log("Klein_Method: Naeherungskoordinaten von " + count + " Neupunkt(en) auf die gesicherten Werte zurueckgesetzt.");
						Klein_Method.this.getProtocol().logEvent("NAEHERUNGSKOORDINATEN: letzte Uebernahme zurueckgenommen (" + count + " Punkt(e))");
						this.showApproximationInfo(String.format(tr("%d Neupunkt(e) auf die gesicherten Näherungskoordinaten zurückgesetzt."), count));
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Ruecknahme der Naeherungskoordinaten fehlgeschlagen: " + e.getMessage());
					this.showApproximationInfo(tr("Vorgang fehlgeschlagen: ") + e.getMessage());
				} finally {
					Klein_Method.this.approximationCheckRunning = false;
				}
				if (restored && Klein_Method.this.isKleinMethodTabActive())
					Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
			}, "Klein_Method_Approximation_Restore");
			thread.setDaemon(true);
			thread.start();
		}	}

	// ============================================================================================================
	/** Festpunkt-Empfehlung, Projektion und Kongruenz der Altpunkte, Kalibrierungsnachweis. */
	private final class Recommendation_Part {

		/**
		 * Bericht der Festpunktanalyse. Bewertet jeden beweglichen Anschlusspunkt nach seiner kalibrierten individuellen
		 * Unsicherheit (eine Zahl je Punkt) in Ampelfarben und prueft die Verteilung der gruenen Festpunkte
		 * (langgestreckt: Luecken, Enden, Ueberhang; flaechig: Neupunkte innerhalb der Festpunkte) - siehe
		 * Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation. Der EP-Wert steht nur noch zur Information im
		 * Bericht. VERAENDERT NICHTS am Projekt.
		 */
		public void generatePointCandidateReport() {
			if (!Klein_Method.this.isValidAdjustmentResultsAvailable()) {
				Alert alert = new Alert(AlertType.WARNING);
				alert.setTitle(tr("Klein_Method - Festpunktanalyse-Bericht"));
				alert.setHeaderText(null);
				alert.setContentText(tr("Bericht nicht möglich: es liegen aktuell keine gültigen Ausgleichungsergebnisse vor."));
				alert.showAndWait();
				return;
			}
			if (Klein_Method.this.pointCandidateReportRunning) {
				Klein_Method.this.logger.log("Klein_Method: Festpunktanalyse-Bericht laeuft bereits, ignoriert.");
				return;
			}
			Klein_Method.this.pointCandidateReportRunning = true;

			Thread thread = busyThread(() -> {
				try {
					Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result result = this.computeFixedPointRecommendation();
					Map<Integer, Double> epByPoint = new java.util.HashMap<Integer, Double>();
					ADJUSTMENT_LOCK.lock();
					try {
						for (Klein_Method_Point_Ep_Row row : Klein_Method.this.databaseHandler.readStochasticPointEpValues())
							epByPoint.put(row.getPointId(), row.getEpMagnitude());
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
					String content = this.buildRecommendationReportContent(result, epByPoint);

					String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
					String fileName = timestamp + "_Festpunktanalyse_Empfehlung.txt";
					File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
					if (!folder.exists())
						folder.mkdirs();
					File file = new File(folder, fileName);
					try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
						writer.print(content);
					}
					Klein_Method.this.logger.log("Klein_Method: Festpunkt-Empfehlung " + (result.recommended ? "EMPFOHLEN" : "NICHT empfohlen") + " (gruen "
							+ result.green + ", gelb " + result.yellow + ", rot " + result.red + ", Gruende " + result.reasons.size() + "), Bericht: "
							+ file.getAbsolutePath());

					Platform.runLater(() -> {
						if (Klein_Method.this.tabBuilder != null)
							Klein_Method.this.tabBuilder.updateRecommendationView();
						Alert alert = new Alert(result.recommended ? AlertType.INFORMATION : AlertType.WARNING);
						alert.setTitle(tr("Klein_Method - Festpunktanalyse-Bericht"));
						alert.setHeaderText(result.recommended ? tr("Festpunkte empfohlen") : tr("Festpunkte nicht empfohlen"));
						StringBuilder text = new StringBuilder(this.recommendationSummary(result));
						if (!result.reasons.isEmpty())
							text.append("\n\n").append(tr("Gründe:")).append('\n').append(this.cappedList(result.reasons, 8));
						if (!result.hints.isEmpty())
							text.append("\n\n").append(tr("Hinweise:")).append('\n').append(this.cappedList(result.hints, 4));
						text.append("\n\n").append(tr("Bericht:")).append(' ').append(file.getAbsolutePath());
						text.append("\n\n").append(tr("Dies ist NUR ein Bericht - es wurden KEINE Gruppen angelegt oder Punkte verschoben."));
						TextArea area = new TextArea(text.toString());
						area.setEditable(false);
						area.setWrapText(true);
						area.setPrefRowCount(16);
						area.setPrefColumnCount(70);
						alert.getDialogPane().setContent(area);
						alert.setResizable(true);
						alert.showAndWait();
					});
				}
				catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Festpunktanalyse-Bericht fehlgeschlagen: " + e.getMessage());
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.ERROR);
						alert.setTitle(tr("Klein_Method - Festpunktanalyse-Bericht"));
						alert.setHeaderText(null);
						alert.setContentText(tr("Bericht fehlgeschlagen: ") + e.getMessage());
						alert.showAndWait();
					});
				}
				finally {
					Klein_Method.this.pointCandidateReportRunning = false;
				}
			}, "Klein_Method_Point_Candidate_Report");
			thread.setDaemon(true);
			thread.start();
		}
		private String cappedList(List<String> lines, int max) {
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < Math.min(max, lines.size()); i++)
				text.append("• ").append(lines.get(i)).append('\n');
			if (lines.size() > max)
				text.append(String.format(tr("… und %d weitere (siehe Bericht)"), lines.size() - max)).append('\n');
			return text.toString().trim();
		}
		/**
		 * legt eine neue Punktgruppe "Varianzfreie Festpunkte (Festpunktanalyse)" vom Typ Varianzfreie Festpunkte an,
		 * traegt sie sofort in den JAG3D-Baum ein und verschiebt NUR die GRUENEN Punkte dorthin - und NUR, wenn die Festpunkt-Empfehlung insgesamt "empfohlen" lautet
		 * (Grenzwerte UND Verteilung). Die kalibrierten Unsicherheiten bleiben unveraendert. Nur per Knopfdruck +
		 * Sicherheitsabfrage, NIE automatisch. Der Gruppenname bleibt deutsch (steht im Projekt).
		 */
		public void promoteStablePointsToPlannedFixedPoints() {
			if (!Klein_Method.this.isValidAdjustmentResultsAvailable()) {
				Alert alert = new Alert(AlertType.WARNING);
				alert.setTitle(tr("Klein_Method - Festpunktanalyse"));
				alert.setHeaderText(null);
				alert.setContentText(tr("Nicht möglich: es liegen aktuell keine gültigen Ausgleichungsergebnisse vor."));
				alert.showAndWait();
				return;
			}
			if (Klein_Method.this.pointCandidateReportRunning) {
				Klein_Method.this.logger.log("Klein_Method: Festpunktanalyse-Vorgang laeuft bereits, ignoriert.");
				return;
			}
			Klein_Method.this.pointCandidateReportRunning = true;

			Thread thread = busyThread(() -> {
				try {
					Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result result = this.computeFixedPointRecommendation();
					Platform.runLater(() -> {
						if (Klein_Method.this.tabBuilder != null)
							Klein_Method.this.tabBuilder.updateRecommendationView();
					});
					List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> candidates = result.greenStochasticPoints();
					if (!result.recommended || candidates.isEmpty()) {
						Klein_Method.this.logger.log("Klein_Method: Empfehlung nicht uebernommen - nicht empfohlen (" + result.reasons.size() + " Grund/Gruende).");
						Platform.runLater(() -> {
							Alert alert = new Alert(AlertType.WARNING);
							alert.setTitle(tr("Klein_Method - Festpunktanalyse"));
							alert.setHeaderText(tr("Festpunkte nicht empfohlen - es wurde nichts verändert."));
							alert.setContentText(this.cappedList(result.reasons, 8));
							alert.showAndWait();
						});
						return;
					}
					int stochastic = result.green + result.yellow + result.red + result.noValue;

					java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
					boolean[] confirmed = new boolean[] { false };
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.CONFIRMATION);
						alert.setTitle(tr("Klein_Method - Festpunktanalyse: Projektänderung"));
						alert.setHeaderText(tr("Achtung: Dies verändert Ihr Projekt dauerhaft!"));
						String text = String.format(tr("%d von %d Beweglichen Anschlusspunkten sind grün und werden verschoben."), candidates.size(), stochastic)
								+ tr("\n\nDiese Punkte werden aus ihrer bisherigen Gruppe entfernt und in eine neue Gruppe "
								+ "„Varianzfreie Festpunkte (Festpunktanalyse)“ unter „Varianzfreie Festpunkte“ verschoben. Danach erkennt "
								+ "Klein_Method die fixe Ausgleichung. Diese Änderung lässt sich NICHT automatisch "
								+ "rückgängig machen - nur manuell in JAG3D.\n\nWirklich fortfahren?");
						if (!result.hints.isEmpty())
							text += "\n\n" + tr("Hinweise:") + "\n" + this.cappedList(result.hints, 4);
						alert.setContentText(text);
						alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
						confirmed[0] = alert.showAndWait().filter(bt -> bt == ButtonType.OK).isPresent();
						latch.countDown();
					});
					latch.await();
					if (!confirmed[0]) {
						Klein_Method.this.logger.log("Klein_Method: Festpunktanalyse-Übernahme vom User abgebrochen.");
						return;
					}

					Map<Integer, List<Integer>> idsByDimension = new java.util.TreeMap<Integer, List<Integer>>();
					for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point point : candidates)
						idsByDimension.computeIfAbsent(point.dimension, d -> new ArrayList<Integer>()).add(point.id);
					boolean multipleDimensions = idsByDimension.size() > 1;
					StringBuilder resultText = new StringBuilder();
					Map<Integer, String> newGroups = new java.util.LinkedHashMap<Integer, String>();
					Map<Integer, Integer> newGroupDimensions = new java.util.HashMap<Integer, Integer>();
					int totalMoved = 0;
					ADJUSTMENT_LOCK.lock();
					try {
						for (Map.Entry<Integer, List<Integer>> entry : idsByDimension.entrySet()) {
							int dimension = entry.getKey();
							String groupName = "Varianzfreie Festpunkte (Festpunktanalyse)" + (multipleDimensions ? " " + dimension + "D" : "");
							int newGroupId = Klein_Method.this.databaseHandler.createPointGroup(groupName, dimension,
									org.applied_geodesy.adjustment.network.PointType.REFERENCE_POINT);
							Klein_Method.this.databaseHandler.movePointsToGroup(entry.getValue(), newGroupId);
							newGroups.put(newGroupId, groupName);
							newGroupDimensions.put(newGroupId, dimension);
							totalMoved += entry.getValue().size();
							resultText.append("- „").append(groupName).append("“: ")
									.append(String.format(tr("%d Punkt(e)"), entry.getValue().size())).append('\n');
							Klein_Method.this.logger.log("Klein_Method: " + entry.getValue().size() + " Punkt(e) in neue Gruppe \"" + groupName + "\" (id=" + newGroupId + ") verschoben.");
						}
						Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_FPE_APPLIED, "true");
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}

					// neue Gruppe sofort im JAG3D-Baum anzeigen (nur Oberflaeche - die Datenbank ist schon geschrieben), danach die
					// aktuelle Auswahl wie nach F5 neu laden, damit die alten Gruppen ohne die verschobenen Punkte erscheinen
					Platform.runLater(() -> {
						try {
							UITreeBuilder treeBuilder = UITreeBuilder.getInstance();
							for (Map.Entry<Integer, String> group : newGroups.entrySet()) {
								int dim = newGroupDimensions.get(group.getKey());
								org.applied_geodesy.jag3d.ui.tree.TreeItemType directory = dim == 1 ? org.applied_geodesy.jag3d.ui.tree.TreeItemType.REFERENCE_POINT_1D_DIRECTORY
										: dim == 2 ? org.applied_geodesy.jag3d.ui.tree.TreeItemType.REFERENCE_POINT_2D_DIRECTORY
												: org.applied_geodesy.jag3d.ui.tree.TreeItemType.REFERENCE_POINT_3D_DIRECTORY;
								treeBuilder.addItem(directory, group.getKey(), group.getValue(), true, false);
							}
						} catch (Exception e) {
							Klein_Method.this.logger.log("Klein_Method: neue Festpunktgruppe nicht im JAG3D-Baum eingetragen (sichtbar nach erneutem Oeffnen): " + e.getMessage());
						}
						Klein_Method.this.setValidAdjustmentResultsAvailable(false);
					});
					Klein_Method.this.refreshJag3dView(true);
					Klein_Method.this.getProtocol().logEvent("FESTPUNKTANALYSE: " + totalMoved + " Punkt(e) in neue Gruppe(n) " + newGroups.values()
							+ " (Varianzfreie Festpunkte) verschoben");

					final int totalMovedFinal = totalMoved;
					final String resultTextFinal = resultText.toString();
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Festpunktanalyse"));
						alert.setHeaderText(totalMovedFinal + tr(" Punkt(e) verschoben"));
						alert.setContentText(resultTextFinal);
						alert.showAndWait();
					});
				}
				catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Festpunktanalyse-Übernahme fehlgeschlagen: " + e.getMessage());
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.ERROR);
						alert.setTitle(tr("Klein_Method - Festpunktanalyse"));
						alert.setHeaderText(null);
						alert.setContentText(tr("Vorgang fehlgeschlagen: ") + e.getMessage());
						alert.showAndWait();
					});
				}
				finally {
					Klein_Method.this.pointCandidateReportRunning = false;
				}
			}, "Klein_Method_Point_Promotion");
			thread.setDaemon(true);
			thread.start();
		}
		public String getProjectionHint() {
			return Klein_Method.this.projectionHint;
		}
		/** Beim Laden des Projekts (Hintergrund, Sperre beim Aufrufer): Projektion gegen Koordinatenart pruefen, einmal je Projekt melden. */
		private void checkProjection() {
			this.checkProjection(true);
		}
		/**
		 * Projektion frisch aus der Datenbank pruefen - auch vor jeder dynamischen Automatik, damit eine in JAG3D
		 * geaenderte Projektion sofort beruecksichtigt wird.
		 *
		 * @param showAlert Hinweisfenster zeigen (einmal je Projekt)
		 */
		private void checkProjection(boolean showAlert) {
			if (Klein_Method.this.tabBuilder != null)
				Klein_Method.this.tabBuilder.refreshCoordinateSystem();
			try {
				List<double[]> yx = new ArrayList<double[]>();
				for (Klein_Method_Point_Coordinate_Row row : Klein_Method.this.databaseHandler.readAllPointAprioriCoordinatesForFangkreisScan())
					if (row.getRechtswert() != null && row.getHochwert() != null)
						yx.add(new double[] { row.getRechtswert(), row.getHochwert() });
				String hint = Klein_Method_Functions.Klein_Method_Projection_Check.hint(yx, Klein_Method.this.databaseHandler.readProjectionType());
				Klein_Method.this.projectionHint = hint;
				if (hint == null || !showAlert)
					return;
				Object[] detected = Klein_Method_Functions.Klein_Method_Projection_Check.detect(yx);
				Klein_Method.this.logger.log("Klein_Method: Projektion passt nicht zur Koordinatenart (" + detected[0] + " " + detected[1] + ", Verzerrung "
						+ String.format(Locale.GERMANY, "%+.0f", (Double) detected[3]) + " ppm, eingestellt " + Klein_Method.this.databaseHandler.readProjectionType() + ").");
				String project = String.valueOf(Klein_Method.this.databaseHandler.getProjectLocation());
				if (Klein_Method.this.projectionHintShown.add(project))
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.WARNING);
						alert.setTitle(tr("Klein_Method - Projektion prüfen"));
						alert.setHeaderText(tr("Projektionsreduktion passt nicht zu den Koordinaten"));
						alert.setContentText(hint);
						alert.show();
					});
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Projektionspruefung nicht moeglich: " + e.getMessage());
			}
		}
		/** Laufende der freien Ausgleichung (Aufruf aus Klein_Method_Iteration_Task): Lage aller Punkte fuer die Kongruenzpruefung merken. */
		public void storeFreeSolution(Klein_Method_Adjustment_Step step) {
			if (step != Klein_Method_Adjustment_Step.FREE)
				return;
			ADJUSTMENT_LOCK.lock();
			try {
				List<Klein_Method_Point_Coordinate_Row> rows = Klein_Method.this.databaseHandler.readAllActivePointCoordinates();
				Klein_Method.this.databaseHandler.storeFreeSolution(rows);
				org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType projection = Klein_Method.this.databaseHandler.readProjectionType();
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_FREE_SOLUTION_PROJECTION, projection == null ? "" : projection.name());
				Klein_Method.this.logger.log("Klein_Method: Lage aus der freien Ausgleichung gespeichert (" + rows.size() + " Punkte) - fuer die Kongruenzpruefung der Altpunkte.");
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Lage der freien Ausgleichung nicht speicherbar: " + e.getMessage());
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
		}
		/** Kongruenz der Altpunkte (freie Lage gegen Sollkoordinaten); null, wenn keine freie Loesung oder geozentrische Koordinaten. */
		private Klein_Method_Functions.Klein_Method_Congruence.Result computeCongruence(List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> points) throws SQLException {
			Map<String, double[]> free = Klein_Method.this.databaseHandler.readFreeSolution();
			if (free.isEmpty())
				return null;
			Map<String, double[]> apriori = new LinkedHashMap<String, double[]>();
			Map<String, Double> sigma = new java.util.HashMap<String, Double>();
			Set<String> fixed = new HashSet<String>();
			int ecef = 0;
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point p : points) {
				if (p.kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_NEW || p.name == null || Double.isNaN(p.y) || Double.isNaN(p.x))
					continue;
				double norm = Double.isNaN(p.z) ? 0.0 : Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
				if (norm > 6.30e6 && norm < 6.42e6)
					ecef++;
				apriori.put(p.name, new double[] { p.y, p.x });
				sigma.put(p.name, p.sigmaMm);
				if (p.kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_FIXED || (p.sigmaMm >= 0.0 && p.sigmaMm < 0.01))
					fixed.add(p.name);
			}
			if (ecef * 2 > apriori.size())
				return null; // geozentrisch: ebene Helmert-Pruefung nicht sinnvoll
			Klein_Method_Functions.Klein_Method_Congruence.Result result = Klein_Method_Functions.Klein_Method_Congruence.evaluate(apriori, free, fixed, sigma);
			if (result != null && result.available) {
				org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType current = Klein_Method.this.databaseHandler.readProjectionType();
				String currentName = current == null ? "" : current.name();
				String stored = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_FREE_SOLUTION_PROJECTION);
				boolean known = stored != null;
				boolean differs = known && !stored.equals(currentName);
				boolean likely = !known && Math.abs(result.scalePpm) > Klein_Method_Functions.Klein_Method_Congruence.SCALE_HINT_PPM
						&& current != null && current != org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.LOCAL_CARTESIAN
						&& Klein_Method.this.projectionHint == null;
				if (differs || likely) {
					result.staleProjection = differs
							? String.format(tr("Die gespeicherte Lage der freien Ausgleichung wurde mit der Projektion \u201E%s\u201C gerechnet, eingestellt ist jetzt \u201E%s\u201C. "
									+ "Der Ma\u00DFstab zu den Sollkoordinaten ist deshalb nicht aussagekr\u00E4ftig (%s ppm) - f\u00FCr eine Ma\u00DFstabspr\u00FCfung die freie Ausgleichung neu rechnen."),
									stored.isEmpty() ? "?" : stored, currentName, String.format(Locale.GERMANY, "%+.1f", result.scalePpm))
							: String.format(tr("Die gespeicherte Lage der freien Ausgleichung stammt vermutlich noch aus einer Rechnung vor der Umstellung der Projektion "
									+ "(Ma\u00DFstab %s ppm, eingestellt ist jetzt \u201E%s\u201C) - f\u00FCr eine Ma\u00DFstabspr\u00FCfung die freie Ausgleichung neu rechnen."),
									String.format(Locale.GERMANY, "%+.1f", result.scalePpm), currentName);
					Klein_Method.this.logger.log("Klein_Method: Massstab der freien Loesung veraltet (freie AGL mit " + (known ? stored : "unbekannt") + ", jetzt " + currentName
							+ ", " + String.format(Locale.GERMANY, "%+.1f", result.scalePpm) + " ppm) - Massstabs-Hinweis unterdrueckt.");
				}
			}
			return result;
		}
		/**
		 * Start der dynamischen Automatik (Aufruf aus Klein_Method_Iteration_Task, Hintergrund): Hinweise zu Projektion
		 * und Anschlusspunkten. Gibt es Hinweise, wartet die Automatik auf "Weiter" (dynamische AGL rechnen) oder
		 * "Abbrechen" (nichts rechnen).
		 *
		 * @return true = rechnen, false = abgebrochen
		 */
		public boolean confirmBeforeDynamicRun(Klein_Method_Adjustment_Step step) {
			if (Klein_Method.this.tabBuilder != null)
				Klein_Method.this.tabBuilder.refreshCoordinateSystem();
			if (step != Klein_Method_Adjustment_Step.DYNAMIC)
				return true;
			List<String> messages = new ArrayList<String>();
			String staleNote = null;
			try {
				this.checkProjection(false);
				if (Klein_Method.this.projectionHint != null)
					messages.add(Klein_Method.this.projectionHint);
				Klein_Method_Functions.Klein_Method_Congruence.Result congruence = this.computeCongruence(Klein_Method.this.databaseHandler.readFixedPointRecommendationPoints());
				if (congruence != null) {
					staleNote = congruence.staleProjection;
					Klein_Method.this.logger.log("Klein_Method: Kongruenz Altpunkte - Massstab " + String.format(Locale.GERMANY, "%+.1f", congruence.scalePpm) + " ppm, Drehung "
							+ String.format(Locale.GERMANY, "%.2f", congruence.rotationMgon) + " mgon, feste Punkte passen nicht: " + congruence.fixedMisfit
							+ ", Freigabe-Vorschlag: " + congruence.release);
					messages.addAll(Klein_Method_Functions.Klein_Method_Congruence.messages(congruence));
				}
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Pruefung vor der dynamischen Ausgleichung nicht moeglich: " + e.getMessage());
				return true;
			}
			if (messages.isEmpty())
				return true;
			if (staleNote != null)
				messages.add(staleNote);
			String text = String.join("\n\n", messages);
			java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			boolean[] proceed = new boolean[] { false };
			Platform.runLater(() -> {
				try {
					ButtonType continueButton = new ButtonType(tr("Weiter"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType cancelButton = new ButtonType(tr("Abbrechen"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					Alert alert = new Alert(AlertType.WARNING, "", continueButton, cancelButton);
					alert.setTitle(tr("Klein_Method - Hinweis vor der dynamischen Ausgleichung"));
					alert.setHeaderText(tr("Anschlusspunkte bitte pr\u00FCfen"));
					TextArea area = new TextArea(text + "\n\n" + tr("Weiter: die dynamische Ausgleichung wird trotzdem gerechnet.\n"
							+ "Abbrechen: es wird nichts berechnet - erst Projektion bzw. Anschlusspunkte in JAG3D pr\u00FCfen und danach neu starten."));
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(12);
					area.setPrefColumnCount(70);
					alert.getDialogPane().setContent(area);
					alert.setResizable(true);
					proceed[0] = alert.showAndWait().filter(button -> button == continueButton).isPresent();
				} finally {
					latch.countDown();
				}
			});
			try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			Klein_Method.this.logger.log("Klein_Method: Hinweis vor der dynamischen Ausgleichung - Anwender: " + (proceed[0] ? "Weiter" : "Abbrechen"));
			return proceed[0];
		}
		/** Laufstart (Aufruf aus Klein_Method_Iteration_Task, DB offen): Startwerte merken, Hinweise leeren. */
		public void captureCalibrationStart(Klein_Method_Adjustment_Step step) {
			Klein_Method.this.parameter.getCalibrationNotes().clear();
			Klein_Method.this.parameter.getCalibrationWarnings().clear();
			Klein_Method.this.calibrationStartSigmas.clear();
			Klein_Method.this.parameter.getPass1Sigmas().clear();
			Klein_Method.this.parameter.getPass1Ratios().clear();
			Klein_Method.this.calibrationStartTime = null;
			if (step != Klein_Method_Adjustment_Step.FREE && step != Klein_Method_Adjustment_Step.FIXED)
				return;
			try {
				for (Klein_Method_Threshold_Type type : SWARM_TYPES)
					for (Integer groupId : Klein_Method.this.databaseHandler.readGroupIds(type)) {
						double[] raw = Klein_Method.this.databaseHandler.readGroupUncertainty(groupId);
						double[] values = new double[3];
						for (int k = 0; k < 3; k++)
							values[k] = Klein_Method.this.functions.toWorkingUnit(raw[k], swarmUnit(type, k));
						Klein_Method.this.calibrationStartSigmas.put(type.name() + "|" + groupId, values);
					}
				Klein_Method.this.calibrationStartTime = LocalDateTime.now().withNano(0).toString().replace('T', ' ');
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Startwerte fuer den Kalibrierungsnachweis nicht lesbar: " + e.getMessage());
			}
		}
		/** Laufende (Aufruf aus Klein_Method_Iteration_Task): Nachweis fuer freie/fixe Ausgleichung schreiben. */
		public void writeCalibrationProof(Klein_Method_Adjustment_Step step, String endReason, boolean done) {
			if (step != Klein_Method_Adjustment_Step.FREE && step != Klein_Method_Adjustment_Step.FIXED)
				return;
			try {
				File file = this.saveCalibrationProof(step, endReason, done);
				Klein_Method.this.logger.log("Klein_Method: Kalibrierungsnachweis geschrieben: " + file.getAbsolutePath());
				Klein_Method.this.protocol.logEvent("KALIBRIERUNGSNACHWEIS: " + file.getName());
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Kalibrierungsnachweis nicht schreibbar: " + e.getMessage());
			}
		}
		/** Knopf "Kalibrierungsnachweis erstellen": aktueller Stand (Startwerte aus dem letzten Lauf, sonst "-"). */
		public void createCalibrationProofOnDemand() {
			Thread thread = busyThread(() -> {
				try {
					Klein_Method_Adjustment_Step step;
					ADJUSTMENT_LOCK.lock();
					try {
						step = Klein_Method.this.getCurrentAdjustmentStep();
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
					File file = this.saveCalibrationProof(step, tr("auf Anforderung (aktueller Stand)"), Klein_Method.this.functions.isThresholdReached(Klein_Method.this.parameter));
					Klein_Method.this.logger.log("Klein_Method: Kalibrierungsnachweis auf Anforderung: " + file.getAbsolutePath());
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Kalibrierungsnachweis"));
						alert.setHeaderText(tr("Kalibrierungsnachweis erstellt"));
						alert.setContentText(file.getAbsolutePath());
						alert.showAndWait();
					});
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Kalibrierungsnachweis fehlgeschlagen: " + e.getMessage());
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.ERROR);
						alert.setTitle(tr("Klein_Method - Kalibrierungsnachweis"));
						alert.setHeaderText(null);
						alert.setContentText(tr("Nachweis fehlgeschlagen: ") + e.getMessage());
						alert.showAndWait();
					});
				}
			}, "Klein_Method_Calibration_Proof");
			thread.setDaemon(true);
			thread.start();
		}
		private File saveCalibrationProof(Klein_Method_Adjustment_Step step, String endReason, boolean done) throws Exception {
			String content;
			ADJUSTMENT_LOCK.lock();
			try {
				content = this.buildCalibrationProof(step, endReason, done);
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
			if (!folder.exists())
				folder.mkdirs();
			File file = new File(folder, timestamp + "_Kalibrierungsnachweis.txt");
			try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
				writer.print(content);
			}
			return file;
		}
		/**
		 * Tabelle je Typ und Komponente (gleiche Gruppen zusammengefasst), mit Startwert, Ergebnis nach Durchlauf 1 und
		 * Endwert, dazu robuste VKS, Modellpruefung und Plausibilitaet (Oberflaechensprache). Spalten "nach DL1"/"VKS
		 * DL1", zusammengefasste Gruppen, klarere Status.
		 */
		private String buildCalibrationProof(Klein_Method_Adjustment_Step step, String endReason, boolean done) throws SQLException {
			String nl = System.lineSeparator();
			Klein_Method_Parameter p = Klein_Method.this.parameter;
			StringBuilder text = new StringBuilder();
			text.append(tr("Kalibrierungsnachweis der Gruppenunsicherheiten")).append(" (Klein_Method ").append(Klein_Method_Default_Values.PACKAGE_VERSION).append(')').append(nl);
			text.append(LocalDateTime.now().withNano(0).toString().replace('T', ' ')).append(nl).append(nl);
			text.append(tr("Ausgleichungsschritt:")).append(' ').append(step == null ? "-" : tr(step.getDisplayName())).append(nl);
			text.append(tr("Laufende:")).append(' ').append(endReason).append(done && !endReason.contains("DONE") ? " (DONE)" : "").append(nl);
			text.append(tr("Laufstart:")).append(' ').append(Klein_Method.this.calibrationStartTime == null ? "-" : Klein_Method.this.calibrationStartTime).append(nl);
			text.append(tr("Ger\u00E4teklasse:")).append(' ').append(tr(p.getDeviceClass().getDisplayName())).append(nl);
			text.append(String.format(tr("VKS-Band %s - %s; volles Band ab Redundanz %s; Mindestredundanz %s; Plausibilit\u00E4t Faktor %s; robuste VKS Faktor %s"),
					Klein_Method_Format.format(p.getVksBandMin(), "", 3), Klein_Method_Format.format(p.getVksBandMax(), "", 3),
					offText(p.getFullBandRedundancy(), 0), offText(p.getMinCalibrationRedundancy(), 1), offText(p.getPlausibilityFactor(), 1),
					offText(p.getRobustRatioLimit(), 2))).append(nl);
			if (Klein_Method.this.projectionHint != null)
				text.append(tr("ACHTUNG:")).append(' ').append(Klein_Method.this.projectionHint).append(nl);
			boolean floorReset = !p.getPass1Sigmas().isEmpty();
			if (floorReset)
				text.append(tr("Hinweis: Nach Durchlauf 1 wurden die Gruppenunsicherheiten auf die Untergrenze gesetzt (Kaskade, \u201EGruppen-\u03C3 nach DL1\u201C). "
						+ "Das eigentliche Kalibrierergebnis steht in der Spalte \u201Enach DL1\u201C; \u201EEnde\u201C ist der Stand nach Durchlauf 2, "
						+ "in dem die individuellen Messunsicherheiten die Feinanpassung \u00FCbernehmen.")).append(nl);
			text.append(nl);

			List<String[]> table = new ArrayList<String[]>();
			table.add(new String[] { tr("Typ"), tr("Gruppe(n)"), tr("Komp."), tr("Start"), tr("nach DL1"), tr("VKS DL1"), tr("Ende"), tr("VKS"), tr("Band"),
					tr("Redundanz"), tr("Anzahl"), tr("Status") });
			List<String> hints = new ArrayList<String>();
			List<String> robustLines = new ArrayList<String>();
			for (Klein_Method_Threshold_Type type : SWARM_TYPES) {
				List<Integer> groupIds = Klein_Method.this.databaseHandler.readGroupIds(type);
				if (groupIds.isEmpty())
					continue;
				double[] ratios = Klein_Method.this.databaseHandler.readGroupVarianceRatios(type);
				double[] ratiosDl1 = p.getPass1Ratios().get(type.name());
				double[][] redundancies = Klein_Method.this.databaseHandler.readVarianceComponentRedundancies(type);
				boolean[] disabledEverywhere = { true, true, true };
				// je Gruppe die 3 Zeilen; gleiche Gruppen (identische Zeilen) werden zusammengefasst
				List<Integer> runIds = new ArrayList<Integer>();
				List<String[]> runRows = null;
				String runSignature = null;
				List<Object[]> runs = new ArrayList<Object[]>();
				for (Integer groupId : groupIds) {
					double[] raw = Klein_Method.this.databaseHandler.readGroupUncertainty(groupId);
					double[] start = Klein_Method.this.calibrationStartSigmas.get(type.name() + "|" + groupId);
					double[] dl1 = p.getPass1Sigmas().get(type.name() + "|" + groupId);
					List<String[]> rows = new ArrayList<String[]>();
					for (int k = 0; k < 3; k++) {
						String unit = swarmUnit(type, k);
						double end = Klein_Method.this.functions.toWorkingUnit(raw[k], unit);
						if (end != 0.0)
							disabledEverywhere[k] = false;
						double r = redundancies == null ? Double.NaN : redundancies[k][0];
						double n = redundancies == null ? Double.NaN : redundancies[k][1];
						double[] band = Klein_Method_Functions.Klein_Method_Calibration_Quality.effectiveBand(p.getVksBandMin(), p.getVksBandMax(), r, p.getFullBandRedundancy());
						boolean changedByFloor = dl1 != null && dl1[k] != 0.0 && Math.abs(dl1[k] - end) > 1e-9;
						String status;
						if (end == 0.0)
							status = tr("deaktiviert");
						else if (p.getMinCalibrationRedundancy() > 0.0 && r > 0.0 && r < p.getMinCalibrationRedundancy())
							status = tr("Redundanz zu klein - nicht kalibriert") + (changedByFloor ? " (" + tr("Wert = Untergrenze nach DL1") + ")" : "");
						else if (ratios[k] >= band[0] && ratios[k] <= band[1])
							status = band[0] != p.getVksBandMin() || band[1] != p.getVksBandMax() ? tr("im Band (nach Redundanz aufgeweitet)") : tr("im Band");
						else if (ratios[k] < band[0])
							status = changedByFloor ? tr("Untergrenze nach DL1 (Feinanpassung individuell)") : tr("unter dem Band (Untergrenze)");
						else
							status = tr("\u00FCber dem Band");
						rows.add(new String[] { tr(type.getDisplayName()).replace(" logPost", ""), "", SWARM_COMPONENTS[k],
								start == null ? "-" : Klein_Method_Format.format(start[k], unit, 3),
								dl1 == null ? "-" : Klein_Method_Format.format(dl1[k], unit, 3),
								dl1 == null || ratiosDl1 == null || dl1[k] == 0.0 ? "-" : Klein_Method_Format.format(ratiosDl1[k], "", 3),
								Klein_Method_Format.format(end, unit, 3),
								end == 0.0 ? "-" : Klein_Method_Format.format(ratios[k], "", 3),
								end == 0.0 ? "-" : Klein_Method_Format.format(band[0], "", 3) + "-" + Klein_Method_Format.format(band[1], "", 3),
								Double.isNaN(r) ? "-" : Klein_Method_Format.format(r, "", 1), Double.isNaN(n) ? "-" : String.valueOf((long) n), status });
						if (end != 0.0) {
							double judged = dl1 != null && dl1[k] != 0.0 ? dl1[k] : end; // Plausibilitaet am eigentlichen Kalibrierergebnis
							String plausibility = Klein_Method_Functions.Klein_Method_Calibration_Quality.plausibility(type, k, judged, unit, p.getPlausibilityFactor(),
									p.getDeviceClass());
							if (plausibility != null && !hints.contains(plausibility))
								hints.add(plausibility);
						}
					}
					StringBuilder signature = new StringBuilder();
					for (String[] row : rows)
						signature.append(String.join("|", row)).append('#');
					if (runSignature != null && runSignature.equals(signature.toString())) {
						runIds.add(groupId);
					} else {
						if (runRows != null)
							runs.add(new Object[] { new ArrayList<Integer>(runIds), runRows });
						runIds.clear();
						runIds.add(groupId);
						runRows = rows;
						runSignature = signature.toString();
					}
				}
				if (runRows != null)
					runs.add(new Object[] { new ArrayList<Integer>(runIds), runRows });
				for (Object[] run : runs) {
					@SuppressWarnings("unchecked")
					List<Integer> ids = (List<Integer>) run[0];
					@SuppressWarnings("unchecked")
					List<String[]> rows = (List<String[]>) run[1];
					String label = ids.size() == 1 ? String.valueOf(ids.get(0))
							: ids.get(0) + "-" + ids.get(ids.size() - 1) + " (" + ids.size() + "\u00D7)";
					for (String[] row : rows) {
						row[1] = label;
						table.add(row);
					}
				}
				List<double[]> fitRows = Klein_Method.this.databaseHandler.readObservationFitData(type);
				double[] vks = Klein_Method_Functions.Klein_Method_Calibration_Quality.robustVarianceFactor(fitRows);
				if (vks[2] >= Klein_Method_Default_Values.QUALITY_MIN_OBSERVATIONS && vks[1] > 0.0) {
					double ratio = vks[0] / vks[1];
					boolean suspicious = p.getRobustRatioLimit() > 0.0 && ratio > p.getRobustRatioLimit();
					robustLines.add(String.format(tr("%s: mittlere VKS %s, robuste VKS %s, Verh\u00E4ltnis %s (%d Beobachtungen)%s"),
							tr(type.getDisplayName()).replace(" logPost", ""), Klein_Method_Format.format(vks[0], "", 3), Klein_Method_Format.format(vks[1], "", 3),
							Klein_Method_Format.format(ratio, "", 2), (int) vks[2], suspicious ? " - " + tr("Ausrei\u00DFer vermutet") : ""));
				}
				// Schlusskontrolle - End-VKS deutlich unter dem Band -> Genauigkeiten zu pessimistisch
				for (int k = 0; k < 3; k++) {
					if (disabledEverywhere[k] || !(ratios[k] > 0.0) || ratios[k] >= p.getVksBandMin())
						continue;
					double r = redundancies == null ? Double.NaN : redundancies[k][0];
					if (!Double.isNaN(r) && p.getMinCalibrationRedundancy() > 0.0 && r < p.getMinCalibrationRedundancy())
						continue;
					hints.add(String.format(tr("%s sigma_%s: End-VKS %s liegt unter dem Band - die angegebenen Genauigkeiten sind etwa um den Faktor %s zu pessimistisch "
							+ "(Untergrenze bzw. individuelle Messunsicherheiten zu gro\u00DF). Untergrenze pr\u00FCfen."),
							tr(type.getDisplayName()).replace(" logPost", ""), SWARM_COMPONENTS[k], Klein_Method_Format.format(ratios[k], "", 3),
							Klein_Method_Format.format(1.0 / Math.sqrt(ratios[k]), "", 2)));
				}
				String model = Klein_Method_Functions.Klein_Method_Calibration_Quality.modelCheck(type, fitRows, disabledEverywhere[0], disabledEverywhere[2]);
				if (model != null)
					hints.add(model);
			}
			int[] width = new int[table.get(0).length];
			for (String[] row : table)
				for (int k = 0; k < row.length; k++)
					width[k] = Math.max(width[k], row[k].length());
			for (String[] row : table) {
				StringBuilder line = new StringBuilder();
				for (int k = 0; k < row.length; k++)
					line.append(String.format("%-" + width[k] + "s", row[k])).append("  ");
				text.append(line.toString().replaceAll("\\s+$", "")).append(nl);
			}
			text.append(nl).append(tr("Robuste VKS je Beobachtungstyp (Median statt Mittel):")).append(nl);
			if (robustLines.isEmpty())
				text.append("  -").append(nl);
			for (String line : robustLines)
				text.append("  ").append(line).append(nl);
			text.append(nl).append(tr("Hinweise (Plausibilit\u00E4t gegen typische Datenblattwerte, Modellpr\u00FCfung a/c, Schlusskontrolle VKS):")).append(nl);
			if (hints.isEmpty())
				text.append("  ").append(tr("keine")).append(nl);
			for (String hint : hints)
				text.append("  - ").append(hint).append(nl);
			text.append(nl).append(tr("Die Aufteilung auf a/b/c sch\u00E4tzt JAG3D je Komponente mit eigener VKS; die Modellpr\u00FCfung meldet nur abgeschaltete Anteile, die zu fehlen scheinen."))
					.append(nl);
			return text.toString();
		}
		/** true = der Tab zeigt gerade die dynamische Ausgleichung (ohne Datenbankzugriff). */
		public boolean isDynamicStepShown() {
			return Klein_Method.this.tabBuilder != null && Klein_Method.this.tabBuilder.isDynamicStepVisible();
		}
		public Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings getRecommendationSettings() {
			return Klein_Method.this.recommendationSettings;
		}
		public Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result getLastRecommendation() {
			return Klein_Method.this.lastRecommendation;
		}
		/** Wert setzen, im Projekt speichern und die Empfehlung neu rechnen (Hintergrund). */
		public void setRecommendationValue(String key, double value) {
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings s = Klein_Method.this.recommendationSettings;
			switch (key) {
			case SETTING_FPE_TACHY_GREEN: s.tachyGreen = value; break;
			case SETTING_FPE_TACHY_YELLOW: s.tachyYellow = value; break;
			case SETTING_FPE_LEVEL_GREEN: s.levelGreen = value; break;
			case SETTING_FPE_LEVEL_YELLOW: s.levelYellow = value; break;
			case SETTING_FPE_MAX_GAP: s.maxGapM = value; break;
			case SETTING_FPE_END_COUNT: s.endCount = (int) Math.round(value); break;
			case SETTING_FPE_MID_POS_GREEN: s.midPositionGreen = value; break;
			case SETTING_FPE_MID_POS_YELLOW: s.midPositionYellow = value; break;
			case SETTING_FPE_MID_HEIGHT_GREEN: s.midHeightGreen = value; break;
			case SETTING_FPE_MID_HEIGHT_YELLOW: s.midHeightYellow = value; break;
			default: return;
			}
			Thread thread = busyThread(() -> {
				ADJUSTMENT_LOCK.lock();
				try {
					Klein_Method.this.databaseHandler.storeProjectSetting(key, String.valueOf(value));
					Klein_Method.this.logger.log("Klein_Method: Festpunkt-Empfehlung " + key + " = " + value);
				} catch (SQLException e) {
					Klein_Method.this.logger.log("Klein_Method: Einstellung " + key + " nicht speicherbar: " + e.getMessage());
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				this.refreshFixedPointRecommendation();
			}, "Klein_Method_Recommendation_Setting");
			thread.setDaemon(true);
			thread.start();
		}
		/** Einstellungen des Projekts lesen (Hintergrund-Thread, Sperre beim Aufrufer); fehlend = Standard. */
		private void loadRecommendationSettings() {
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings s = Klein_Method.this.recommendationSettings;
			s.tachyGreen = this.readSettingNumber(SETTING_FPE_TACHY_GREEN, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_TACHY_GREEN);
			s.tachyYellow = this.readSettingNumber(SETTING_FPE_TACHY_YELLOW, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_TACHY_YELLOW);
			s.levelGreen = this.readSettingNumber(SETTING_FPE_LEVEL_GREEN, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_LEVEL_GREEN);
			s.levelYellow = this.readSettingNumber(SETTING_FPE_LEVEL_YELLOW, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_LEVEL_YELLOW);
			s.maxGapM = this.readSettingNumber(SETTING_FPE_MAX_GAP, 0.0);
			s.midPositionGreen = this.readSettingNumber(SETTING_FPE_MID_POS_GREEN, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_MID_POSITION_GREEN);
			s.midPositionYellow = this.readSettingNumber(SETTING_FPE_MID_POS_YELLOW, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_MID_POSITION_YELLOW);
			s.midHeightGreen = this.readSettingNumber(SETTING_FPE_MID_HEIGHT_GREEN, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_MID_HEIGHT_GREEN);
			s.midHeightYellow = this.readSettingNumber(SETTING_FPE_MID_HEIGHT_YELLOW, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_MID_HEIGHT_YELLOW);
			s.endCount = (int) Math.round(this.readSettingNumber(SETTING_FPE_END_COUNT, Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.DEFAULT_END_COUNT));
		}
		private double readSettingNumber(String key, double fallback) {
			try {
				String value = Klein_Method.this.databaseHandler.readProjectSetting(key);
				return value == null ? fallback : Double.parseDouble(value.trim());
			} catch (SQLException | NumberFormatException e) {
				return fallback;
			}
		}
		public Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result computeFixedPointRecommendation() throws SQLException {
			List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> points;
			ADJUSTMENT_LOCK.lock();
			try {
				points = Klein_Method.this.databaseHandler.readFixedPointRecommendationPoints();
				Klein_Method_Functions.Klein_Method_Congruence.Result congruence = this.computeCongruence(points);
				synchronized (Klein_Method.this.recommendationSettings) {
					Klein_Method.this.recommendationSettings.congruence = congruence;
				}
				List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> areas = Klein_Method.this.databaseHandler.readConnectionAreas();
				synchronized (Klein_Method.this.recommendationSettings) {
					Klein_Method.this.recommendationSettings.areas.clear();
					Klein_Method.this.recommendationSettings.areas.addAll(areas);
				}
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result result;
			synchronized (Klein_Method.this.recommendationSettings) {
				result = Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.evaluate(points, Klein_Method.this.recommendationSettings);
			}
			// Netzform nachvollziehbar protokollieren (nur bei Aenderung)
			String shape = (result.elongated ? "langgestreckt" : "flaechig") + ", Punkte mit Lage " + result.shapePoints + " von " + points.size()
					+ ", Verhaeltnis " + String.format(Locale.GERMANY, "%.1f", result.ratio) + ", Laenge " + String.format(Locale.GERMANY, "%.0f", result.lengthM)
					+ " m, Breite " + String.format(Locale.GERMANY, "%.0f", result.widthM) + " m"
					+ ", Altpunkt-Standorte: Abstand " + (Double.isNaN(result.anchorSpacingM) ? "k. A." : String.format(Locale.GERMANY, "%.0f m", result.anchorSpacingM))
					+ ", Flaeche " + String.format(Locale.GERMANY, "%.1f", result.anchorAreaM2 / 1.0e6) + " km2";
			if (!shape.equals(Klein_Method.this.lastRecommendationShape)) {
				Klein_Method.this.lastRecommendationShape = shape;
				Klein_Method.this.logger.log("Klein_Method: Festpunkt-Empfehlung - Netzform " + shape + ".");
			}
			Klein_Method.this.lastRecommendation = result;
			return result;
		}
		/** Nach einer Ausgleichung (nur dynamische AGL): Empfehlung still neu rechnen und die Anzeige aktualisieren. */
		public void refreshFixedPointRecommendation() {
			boolean dynamic;
			try {
				dynamic = Klein_Method.this.getCurrentAdjustmentStep() == Klein_Method_Adjustment_Step.DYNAMIC;
			} catch (SQLException e) {
				dynamic = false;
			}
			if (!dynamic) {
				Klein_Method.this.lastRecommendation = null;
			} else {
				try {
					this.computeFixedPointRecommendation();
				} catch (Exception e) {
					Klein_Method.this.lastRecommendation = null;
					Klein_Method.this.logger.log("Klein_Method: Festpunkt-Empfehlung nicht berechenbar: " + e.getMessage());
				}
			}
			Platform.runLater(() -> {
				if (Klein_Method.this.tabBuilder != null)
					Klein_Method.this.tabBuilder.updateRecommendationView();
			});
		}
		/** Kurzfassung (Oberflaechensprache) fuer Tab und Dialog. */
		public String recommendationSummary(Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result r) {
			StringBuilder text = new StringBuilder();
			text.append(String.format(tr("Ampel: %d grün, %d gelb, %d rot"), r.green, r.yellow, r.red));
			if (r.noValue > 0)
				text.append(String.format(tr(", %d ohne Wert"), r.noValue));
			if (r.fixed > 0)
				text.append(String.format(tr(", %d bereits fest"), r.fixed));
			if (r.middlePoints > 0)
				text.append(String.format(tr(" (Rand %d, Mitte %d)"), r.edgePoints, r.middlePoints));
			text.append('\n');
			if (!r.geometryAvailable)
				text.append(tr("Netzform: nicht bestimmbar"));
			else if (r.elongated) {
				text.append(String.format(tr("Netzform: langgestreckt (Länge %s, etwa %s : 1)"),
						Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.formatLength(r.lengthM), String.format(Locale.GERMANY, "%.0f", r.ratio)));
				text.append('\n').append(String.format(tr("Üblicher Abstand der Altpunkte %s, max. Lücke %s (%s), größte Lücke %s"),
						Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.formatLength(r.typicalSpacingM),
						Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.formatLength(r.maxGapM),
						r.maxGapAuto ? tr("automatisch") : tr("eingegeben"),
						Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.formatLength(r.largestGapM)));
				if (r.oldPointSites > 0)
					text.append(String.format(tr(" (Altpunkte an %d Standorten)"), r.oldPointSites));
				text.append('\n').append(String.format(tr("Grüne Festpunkte am Anfang / Ende: %d / %d (gefordert %d)"), r.endCountA, r.endCountB,
						Klein_Method.this.recommendationSettings.endCount));
			} else
				text.append(String.format(tr("Netzform: flächig (etwa %s : 1) - Neupunkte müssen innerhalb der grünen Festpunkte liegen"),
						String.format(Locale.GERMANY, "%.1f", r.ratio)));
			if (!r.areaLines.isEmpty())
				text.append('\n').append(tr("Anschlussbereiche (gr\u00FCne Festpunkte / gefordert):")).append(' ').append(String.join(", ", r.areaLines));
			return text.toString();
		}
		/** Berichtsdatei in der Oberflaechensprache (Punktnamen nur lokal). */
		private String buildRecommendationReportContent(Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result r, Map<Integer, Double> epByPoint) {
			String nl = System.lineSeparator();
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings s = Klein_Method.this.recommendationSettings;
			StringBuilder content = new StringBuilder();
			content.append(tr("Festpunktanalyse - Festpunkt-Empfehlung")).append(" (Klein_Method ").append(Klein_Method_Default_Values.PACKAGE_VERSION).append(')').append(nl);
			content.append(LocalDateTime.now().withNano(0).toString().replace('T', ' ')).append(nl).append(nl);
			content.append(tr("Ergebnis:")).append(' ').append(r.recommended ? tr("FESTPUNKTE EMPFOHLEN") : tr("FESTPUNKTE NICHT EMPFOHLEN")).append(nl);
			content.append(String.format(tr("Grenzen der individuellen Unsicherheit (grün / gelb): Rand - Tachymeter (2D/3D) %s / %s mm, Nivellement (1D) %s / %s mm; "
					+ "Mitte (Stützpunkte) - Lage (2D/3D) %s / %s mm, Höhe (1D) %s / %s mm"),
					String.format(Locale.GERMANY, "%.2f", s.tachyGreen), String.format(Locale.GERMANY, "%.2f", s.tachyYellow),
					String.format(Locale.GERMANY, "%.2f", s.levelGreen), String.format(Locale.GERMANY, "%.2f", s.levelYellow),
					String.format(Locale.GERMANY, "%.2f", s.midPositionGreen), String.format(Locale.GERMANY, "%.2f", s.midPositionYellow),
					String.format(Locale.GERMANY, "%.2f", s.midHeightGreen), String.format(Locale.GERMANY, "%.2f", s.midHeightYellow))).append(nl);
			content.append(this.recommendationSummary(r).replace("\n", nl)).append(nl).append(nl);
			if (Klein_Method.this.projectionHint != null)
				content.append(tr("ACHTUNG:")).append(' ').append(Klein_Method.this.projectionHint).append(nl).append(nl);
			if (!r.reasons.isEmpty()) {
				content.append(tr("Gründe gegen die Empfehlung:")).append(nl);
				for (String reason : r.reasons)
					content.append("  - ").append(reason).append(nl);
				content.append(nl);
			}
			if (!r.hints.isEmpty()) {
				content.append(tr("Hinweise:")).append(nl);
				for (String hint : r.hints)
					content.append("  - ").append(hint).append(nl);
				content.append(nl);
			}
			content.append(tr("Nur grüne Punkte werden bei „Empfehlung übernehmen“ verschoben. Der EP-Wert steht nur zur Information da."))
					.append(nl).append(nl);

			List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> rows = new ArrayList<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point>();
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point p : r.points)
				if (p.kind != Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_NEW || p.outside)
					rows.add(p);
			rows.sort((a, b) -> {
				if (r.elongated && !Double.isNaN(a.station) && !Double.isNaN(b.station))
					return Double.compare(a.station, b.station);
				return String.valueOf(a.name).compareTo(String.valueOf(b.name));
			});
			String[][] table = new String[rows.size() + 1][];
			table[0] = new String[] { tr("Punkt"), tr("Art"), tr("Lage im Netz"), tr("Dim."), tr("σ [mm]"), tr("Grenze grün/gelb [mm]"), tr("Ampel"),
					r.elongated ? tr("Station") : "", tr("EP [mm]"), tr("Hinweis") };
			for (int i = 0; i < rows.size(); i++) {
				Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point p = rows.get(i);
				boolean isNew = p.kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_NEW;
				String kind = isNew ? tr("Neupunkt") : p.kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_FIXED ? tr("Festpunkt")
						: p.levelingLimit ? tr("Nivellement") : tr("Tachymeter");
				Double ep = epByPoint.get(p.id);
				String hint = p.leveled3dHint ? tr("zusätzlich nivelliert") : isNew ? tr("außerhalb - würde hinausgerechnet") : "";
				if (p.cause != null)
					hint = hint.isEmpty() ? p.cause : hint + "; " + p.cause;
				table[i + 1] = new String[] { p.name == null ? "-" : p.name, kind,
						isNew || p.kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_FIXED ? "-"
								: Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.zoneLabel(p), p.dimension + "D",
						Double.isNaN(p.sigmaMm) ? "-" : String.format(Locale.GERMANY, "%.2f", p.sigmaMm),
						Double.isNaN(p.green) ? "-" : String.format(Locale.GERMANY, "%.2f / %.2f", p.green, p.yellow),
						isNew ? "-" : Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.ratingLabel(p.rating),
						r.elongated && !Double.isNaN(p.station) ? Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.formatLength(p.station) : "",
						ep == null ? "-" : String.format(Locale.GERMANY, "%.3f", ep), hint };
			}
			int[] width = new int[table[0].length];
			for (String[] row : table)
				for (int k = 0; k < row.length; k++)
					width[k] = Math.max(width[k], row[k].length());
			for (String[] row : table) {
				StringBuilder line = new StringBuilder();
				for (int k = 0; k < row.length; k++) {
					if (width[k] == 0)
						continue;
					line.append(String.format("%-" + width[k] + "s", row[k])).append("  ");
				}
				content.append(line.toString().replaceAll("\\s+$", "")).append(nl);
			}
			return content.toString();
		}	}

	// ============================================================================================================
	/** Grobe Fehler: Sperre, Bereinigung (schrittweise Herausnahme), Zuordnung vor Phase 2. */
	private final class Gross_Error_Part {

		/** true = Bereinigung nach dem statistischen Test (JAG3D-Signifikanz) statt nach der Grenze in Metern. */
		private volatile boolean statistical = false;

		/** true = Aufruf aus der Automatik: kein Bestaetigungsfenster, alle Herausgenommenen gelten als grobe Fehler. */
		private volatile boolean automaticMode = false;
		// Laenge je GNSS-Basislinie (m) fuer die laengenabhaengige Grenze, je Pruefung neu
		private final Map<Integer, Double> gnssLengths = new java.util.concurrent.ConcurrentHashMap<Integer, Double>();

		/**
		 * Grenze grober Fehler fuer diese Beobachtung (m): bei GNSS-Basislinien zusaetzlich ppm * Laenge, sonst die
		 * eingestellte Grenze.
		 */
		private double limitFor(Max_Error_Row row, double limit) {
			double ppm = Klein_Method.this.parameter.getGrossErrorGnssPpm();
			if (row == null || !row.gnss || !(ppm > 0.0))
				return limit;
			Double length = this.gnssLengths.get(row.observationId);
			if (length == null) {
				double l = 0.0;
				try {
					double[] v = Klein_Method.this.databaseHandler.readGnssVector(row.observationId);
					if (v != null)
						l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
				} catch (SQLException e) {
					l = 0.0;
				}
				length = Double.isFinite(l) ? l : 0.0;
				this.gnssLengths.put(row.observationId, length);
			}
			return limit + ppm * 1e-6 * length;
		}
		private boolean above(Max_Error_Row row, double limit) {
			return this.statistical ? row.significant : !Double.isNaN(row.linearError) && row.linearError > this.limitFor(row, limit);
		}
		private boolean below(Max_Error_Row row, double limit) {
			return this.statistical ? !row.significant : !Double.isNaN(row.linearError) && row.linearError <= this.limitFor(row, limit);
		}

		/** true = nach F5 liegt eine (nicht bestaetigte) Beobachtung ueber der Grenze grober Fehler. */
		public BooleanProperty grossErrorsSuspectedProperty() {
			return Klein_Method.this.grossErrorsSuspected;
		}
		public boolean isGrossErrorsSuspected() {
			return Klein_Method.this.grossErrorsSuspected.get();
		}
		public BooleanProperty grossErrorCleanupRunningProperty() {
			return Klein_Method.this.grossErrorCleanupRunning;
		}
		/** Kurzbeschreibung des Verdachts fuer die Statuszeile (leer ohne Verdacht). */
		public String getGrossErrorGateText() {
			return Klein_Method.this.grossErrorGateText;
		}
		/** alle nicht bestaetigten Beobachtungen ueber der Grenze, groesste lineare Abweichung zuerst. */
		private List<Max_Error_Row> unacceptedObservationsAbove(Set<String> skipKeys, double limit) throws SQLException {
			Set<String> accepted = new HashSet<String>();
			for (Max_Error_Row entry : Klein_Method.this.databaseHandler.readGrossErrorEntries())
				if (Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED.equals(entry.status))
					accepted.add(entry.key());
			List<Max_Error_Row> rows = new ArrayList<Max_Error_Row>();
			for (Max_Error_Row row : Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE))
				if (this.above(row, limit) && !accepted.contains(row.key()) && !skipKeys.contains(row.key()))
					rows.add(row);
			// Reihenfolge nach Teststatistik (|logPost|, beruecksichtigt die Kontrollierbarkeit) - die Grenze in Metern
			// entscheidet nur, ob eine Beobachtung ueberhaupt in Frage kommt
			rows.sort((a, b) -> {
				int c = Double.compare(b.value, a.value);
				return c != 0 ? c : Double.compare(b.linearError, a.linearError);
			});
			return rows;
		}
		/**
		 * Punkt, an dem mindestens GROSS_ERROR_POINT_SHARE der Beobachtungen ueber der Grenze haengen (und mindestens
		 * GROSS_ERROR_POINT_BLOCK_MIN), sonst null.
		 */
		private String dominantPoint(List<Max_Error_Row> over) {
			if (over.size() < Klein_Method_Default_Values.GROSS_ERROR_POINT_BLOCK_MIN)
				return null;
			Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
			for (Max_Error_Row row : over) {
				counts.merge(row.startPointName, 1, Integer::sum);
				if (!row.endPointName.equals(row.startPointName))
					counts.merge(row.endPointName, 1, Integer::sum);
			}
			String best = null;
			int bestCount = 0;
			for (Map.Entry<String, Integer> e : counts.entrySet())
				if (e.getValue() > bestCount) {
					best = e.getKey();
					bestCount = e.getValue();
				}
			return bestCount >= Klein_Method_Default_Values.GROSS_ERROR_POINT_BLOCK_MIN && bestCount >= Klein_Method_Default_Values.GROSS_ERROR_POINT_SHARE * over.size()
					? best : null;
		}
		/** gemeinsamer Punkt der herausgenommenen Beobachtungen fuer das Bestaetigungsfenster (mind. 5 und 80 %), sonst null. */
		private String dominantPointText(List<Max_Error_Row> removed) {
			String point = this.dominantPoint(removed);
			if (point == null)
				return null;
			int n = 0;
			for (Max_Error_Row row : removed)
				if (point.equals(row.startPointName) || point.equals(row.endPointName))
					n++;
			return String.format(tr("%d der %d Beobachtungen gehören zu Punkt %s - vermutlich ist der Punkt selbst (Aufstellung, Antennenhöhe, "
					+ "Exzentrizität, Punktverwechslung) in einzelnen Messabschnitten gestört, nicht jede Basislinie für sich."), n, removed.size(), point);
		}
		/**
		 * aktive Beobachtung mit der GROESSTEN LINEAREN ABWEICHUNG [m], die nicht bestaetigt (ACCEPTED) und nicht in
		 * skipKeys ist, oder null. Kein logPost oder anderer statistischer Wert. Nicht bestimmbare Werte (NaN) zaehlen
		 * nicht. DB offen.
		 */
		private Max_Error_Row findWorstUnacceptedObservation(Set<String> skipKeys) throws SQLException {
			Set<String> accepted = new HashSet<String>();
			for (Max_Error_Row entry : Klein_Method.this.databaseHandler.readGrossErrorEntries())
				if (Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED.equals(entry.status))
					accepted.add(entry.key());
			Max_Error_Row worst = null;
			for (Max_Error_Row row : Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE))
				if (!Double.isNaN(row.linearError) && !accepted.contains(row.key()) && !skipKeys.contains(row.key())
						&& (worst == null || row.linearError > worst.linearError))
					worst = row;
			return worst;
		}
		/**
		 * prueft nach F5 (Hintergrund-Thread, Datenbank offen), ob grobe Fehler vermutet
		 *
		 * werden: nur freie und fixe Ausgleichung, groesste lineare Abweichung einer nicht bestaetigten Beobachtung ueber
		 * der Grenze [m]. Setzt die Sperre und aktualisiert die Oberflaeche.
		 */
		public void evaluateGrossErrorGate(Klein_Method_Adjustment_Step step) {
			boolean suspected = false;
			String text = "";
			try {
				if ((step == Klein_Method_Adjustment_Step.FREE || step == Klein_Method_Adjustment_Step.FIXED)
						&& SQLManager.getInstance().hasDatabase() && SQLManager.getInstance().getDataBase().isOpen()) {
					this.gnssLengths.clear();
					double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
					Max_Error_Row worst = this.worstAbove(limit);
					if (worst != null) {
						suspected = true;
						text = "GROBE FEHLER VERMUTET: " + describeObservation(worst) + " | Fehler \u2248 " + formatLinear(worst.linearError)
								+ " (Grenze " + formatLinear(this.limitFor(worst, limit)) + ") - zuerst \u201EGrobe Fehler bereinigen\u201C.";
					}
				}
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Pruefung auf grobe Fehler fehlgeschlagen: " + e.getMessage());
			}
			final boolean finalSuspected = suspected;
			final String finalText = text;
			if (suspected)
				Klein_Method.this.logger.log("Klein_Method: " + text);
			Platform.runLater(() -> {
				Klein_Method.this.grossErrorGateText = finalText;
				Klein_Method.this.grossErrorsSuspected.set(finalSuspected);
				if (Klein_Method.this.tabBuilder != null)
					Klein_Method.this.tabBuilder.refreshAutomationAvailability();
			});
		}
		/**
		 * "Grobe Fehler bereinigen" - schrittweise (Data Snooping): jeweils die schlechteste nicht bestaetigte
		 * Beobachtung vorübergehend deaktivieren, neu ausgleichen, bis keine mehr ueber der Grenze liegt. Danach
		 * Bestaetigungsfenster (Anwender entscheidet je Beobachtung).
		 */
		public void startGrossErrorCleanup() {
			this.start(false);
		}
		/** groesste lineare Abweichung ueber der (laengenabhaengigen) Grenze, nicht bestaetigt, oder null. DB offen. */
		private Max_Error_Row worstAbove(double limit) throws SQLException {
			Max_Error_Row worst = null;
			for (Max_Error_Row row : this.unacceptedObservationsAbove(new HashSet<String>(), limit))
				if (worst == null || row.linearError > worst.linearError)
					worst = row;
			return worst;
		}
		/**
		 * aus der Automatik (eigener Thread, vor Durchlauf 1): liegt eine Beobachtung ueber der Grenze grober Fehler,
		 * "Grobe Fehler bereinigen" ohne Bestaetigungsfenster ausfuehren - alle herausgenommenen Beobachtungen gelten
		 * als grobe Fehler (Protokoll, Zustandsprotokoll, Ruecknahme ueber die Bestaetigung moeglich). Vor der Pruefung
		 * wird immer selbst ausgeglichen, damit die Verbesserungen zum aktuellen Stand passen. Rueckgabe: Anzahl
		 * abgeschaltet, -1 = nichts zu tun, -2 = Pruefung nicht moeglich (Ausgleichung ohne gueltiges Ergebnis).
		 */
		public int runAutomatic() {
			if (Klein_Method.this.grossErrorCleanupRunning.get())
				return -1;
			try {
				this.gnssLengths.clear();
				this.statistical = false;
				if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
					return -1;
				// Verbesserungen muessen zum aktuellen Stand passen (Import ohne F5, zusammengefasste Wiederholungen,
				// geaenderte Genauigkeiten): deshalb vor der Pruefung immer selbst ausgleichen
				EstimationStateType state = this.adjustQuietly();
				if (state != EstimationStateType.ERROR_FREE_ESTIMATION) {
					Klein_Method.this.protocol.logEvent("GROBE FEHLER (AUTOMATIK): Pruefung nicht moeglich - Ausgleichung vor Durchlauf 1 ohne gueltiges Ergebnis ("
							+ state + "), grobe Fehler bitte von Hand bereinigen.");
					Klein_Method.this.logger.log("Klein_Method: automatische Pruefung grober Fehler uebersprungen - Ausgleichung " + state + ".");
					return -2;
				}
				Max_Error_Row worst = this.worstAbove(Klein_Method.this.parameter.getGrossErrorLinearLimit());
				if (worst == null) {
					Klein_Method.this.protocol.logEvent("GROBE FEHLER (AUTOMATIK): vor Durchlauf 1 geprueft - keine Beobachtung ueber der Grenze "
							+ formatLinear(Klein_Method.this.parameter.getGrossErrorLinearLimit())
							+ (Klein_Method.this.parameter.getGrossErrorGnssPpm() > 0.0 ? " + " + String.format(Locale.GERMANY, "%.1f", Klein_Method.this.parameter.getGrossErrorGnssPpm())
									+ " ppm x Laenge (GNSS)" : ""));
					return -1;
				}
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: automatische Pruefung grober Fehler fehlgeschlagen: " + e.getMessage());
				return -2;
			}
			Klein_Method.this.protocol.logEvent("GROBE FEHLER BEREINIGEN (AUTOMATIK): vor Durchlauf 1, Grenze " + formatLinear(Klein_Method.this.parameter.getGrossErrorLinearLimit())
					+ (Klein_Method.this.parameter.getGrossErrorGnssPpm() > 0.0 ? " + " + String.format(Locale.GERMANY, "%.1f", Klein_Method.this.parameter.getGrossErrorGnssPpm())
							+ " ppm x Laenge (GNSS)" : ""));
			Klein_Method.this.grossErrorCleanupRunning.set(true);
			this.automaticMode = true;
			this.automaticRemoved = 0;
			try {
				this.runGrossErrorCleanup();
			} finally {
				this.automaticMode = false;
				Klein_Method.this.grossErrorCleanupRunning.set(false);
			}
			return this.automaticRemoved;
		}
		private volatile int automaticRemoved = 0;

		/**
		 * Kontrolle am Laufende der freien/fixen AGL: Beobachtungen noch ueber der Grenze grober Fehler (keine Abschaltung)
		 * und Beobachtungen, deren Genauigkeit durch die Kalibrierung auf mehr als INFLATION_WARN_FACTOR ihres
		 * Ausgangswerts gewachsen ist. Protokollzeilen.
		 */
		public List<String> endOfRunCheck() {
			List<String> lines = new ArrayList<String>();
			try {
				this.gnssLengths.clear();
				this.statistical = false;
				double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
				List<Max_Error_Row> over = this.unacceptedObservationsAbove(new HashSet<String>(), limit);
				for (Max_Error_Row row : over.subList(0, Math.min(10, over.size())))
					lines.add("GROBE FEHLER NACH DER AUTOMATIK: " + describeObservation(row) + " | Fehler \u2248 " + formatLinear(row.linearError) + " (Grenze "
							+ formatLinear(this.limitFor(row, limit)) + ") - \u201EGrobe Fehler bereinigen\u201C pruefen.");
				if (over.size() > 10)
					lines.add("GROBE FEHLER NACH DER AUTOMATIK: insgesamt " + over.size() + " Beobachtungen ueber der Grenze.");
				List<String[]> inflated = Klein_Method.this.databaseHandler.readInflatedObservations(Klein_Method_Default_Values.INFLATION_WARN_FACTOR);
				for (String[] row : inflated.subList(0, Math.min(10, inflated.size())))
					lines.add("STARK AUFGEBLAEHT: " + row[0] + " - Genauigkeit " + row[1] + " statt " + row[2] + " (x " + row[3]
							+ ") - die Kalibrierung hat einen moeglichen groben Fehler aufgefangen, Beobachtung pruefen.");
				if (inflated.size() > 10)
					lines.add("STARK AUFGEBLAEHT: insgesamt " + inflated.size() + " Beobachtungen ueber dem " + Klein_Method_Default_Values.INFLATION_WARN_FACTOR + "-fachen Ausgangswert.");
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Kontrolle grober Fehler am Laufende fehlgeschlagen: " + e.getMessage());
			}
			return lines;
		}
		public void start(boolean statisticalMode) {
			if (Klein_Method.this.grossErrorCleanupRunning.get() || Klein_Method.this.isAutomaticTaskRunning() || Klein_Method.this.epStepRunning)
				return;
			this.statistical = statisticalMode;
			Klein_Method.this.grossErrorCleanupRunning.set(true);
			Klein_Method.this.setValidAdjustmentResultsAvailable(false);
			Thread thread = busyThread(this::runGrossErrorCleanup, "Klein_Method_Gross_Error_Cleanup");
			thread.setDaemon(true);
			thread.start();
		}
		private void runGrossErrorCleanup() {
			final List<Max_Error_Row> removed = new ArrayList<Max_Error_Row>();
			final List<Max_Error_Row> notRemovable = new ArrayList<Max_Error_Row>();
			final List<Max_Error_Row> restored = new ArrayList<Max_Error_Row>();
			Set<String> skip = new HashSet<String>();
			String failure = null;
			double remainingWorst = Double.NaN;
			boolean stepLimitReached = false;
			double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
			Gross_Error_Precheck_Part.Result precheck = null;
			List<Max_Error_Row> uncontrolled = new ArrayList<Max_Error_Row>();
			List<String[]> diagnosis = new ArrayList<String[]>();
			ADJUSTMENT_LOCK.lock();
			try {
				if (this.statistical) {
					int restoredSigmas = Klein_Method.this.databaseHandler.restoreStatisticalState();
					Klein_Method.this.protocol.logEvent("GROBE FEHLER BEREINIGEN (STATISTISCH): Start, Kriterium JAG3D-Test signifikant; Genauigkeiten auf den Stand des Tests "
							+ "zurueckgesetzt (" + restoredSigmas + " Werte)");
				} else
					Klein_Method.this.protocol.logEvent("GROBE FEHLER BEREINIGEN: Start, Grenze lineare Abweichung " + formatLinear(limit));
				try {
					precheck = Klein_Method.this.grossErrorPrecheckPart.run();
					Klein_Method.this.grossErrorPrecheckPart.log(precheck);
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Vorpruefung grober Fehler fehlgeschlagen: " + e.getMessage());
				}
				Max_Error_Row lastRemoved = null;
				List<Max_Error_Row> lastBlock = null;
				List<Max_Error_Row> companions = new ArrayList<Max_Error_Row>();
				boolean blockAllowed = true;
				int steps = 0;
				// robuste Vorauswahl: was die Vorpruefung VOR der Ausgleichung als auffaellig erkannt hat (Schleifenschluesse,
				// Einzelbasislinien, Doppelmessungen - nicht verschmiert) und jetzt ueber der Grenze liegt, gemeinsam heraus;
				// alle so gefundenen kommen am Ende in die Gegenprobe
				if (precheck != null && this.unacceptedObservationsAbove(skip, limit).size() >= Klein_Method_Default_Values.GROSS_ERROR_ROBUST_MIN) {
					List<Max_Error_Row> robust = this.robustPreSelection(precheck, skip, limit);
					if (robust.size() > 1) {
						for (Max_Error_Row row : robust)
							Klein_Method.this.databaseHandler.setObservationEnabled(row, false);
						removed.addAll(robust);
						companions.addAll(robust);
						lastBlock = robust;
						steps++;
						Klein_Method.this.protocol.logEvent("  " + steps + ". robuste Vorauswahl (Vorprüfung auffällig und über der Grenze): " + robust.size()
								+ " Beobachtungen gemeinsam vorübergehend herausgenommen (Gegenprobe am Ende)");
						for (Max_Error_Row row : robust)
							Klein_Method.this.protocol.logEvent("      " + describeObservation(row) + " | Fehler ≈ " + formatLinear(row.linearError));
						Klein_Method.this.logger.log("Klein_Method: Grobe Fehler bereinigen - robuste Vorauswahl, " + robust.size() + " Beobachtungen gemeinsam herausgenommen.");
					}
				}
				while (true) {
					EstimationStateType result;
					String message = null;
					try {
						result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
					} catch (Exception e) {
						result = null;
						message = e.getMessage();
					}
					if (result != EstimationStateType.ERROR_FREE_ESTIMATION) {
						if (lastBlock != null) {
							// Block macht das Netz unbestimmbar -> zuruecknehmen und einzeln weiter
							for (Max_Error_Row row : lastBlock) {
								Klein_Method.this.databaseHandler.setObservationEnabled(row, true);
								removed.remove(row);
								companions.remove(row);
							}
							Klein_Method.this.protocol.logEvent("  Block von " + lastBlock.size() + " Beobachtungen nicht herausnehmbar (Netz sonst nicht bestimmbar) - weiter einzeln");
							lastBlock = null;
							blockAllowed = false;
							continue;
						}
						if (lastRemoved != null) {
							// ohne diese Beobachtung ist das Netz nicht bestimmbar -> zuruecknehmen
							Klein_Method.this.databaseHandler.setObservationEnabled(lastRemoved, true);
							removed.remove(lastRemoved);
							notRemovable.add(lastRemoved);
							skip.add(lastRemoved.key());
							Klein_Method.this.protocol.logEvent("  nicht herausnehmbar (Netz sonst nicht bestimmbar): " + describeObservation(lastRemoved));
							lastRemoved = null;
							continue;
						}
						failure = "Netzausgleichung " + (result == null ? "mit Fehler abgebrochen: " + message : "nicht fehlerfrei (" + result + ")");
						break;
					}
					lastRemoved = null;
					lastBlock = null;
					List<Max_Error_Row> over = this.unacceptedObservationsAbove(skip, limit);
					Max_Error_Row worst = over.isEmpty() ? this.findWorstUnacceptedObservation(skip) : over.get(0);
					remainingWorst = worst == null ? Double.NaN : worst.linearError;
					if (worst == null || !this.above(worst, limit))
						break;
					if (steps >= Klein_Method_Default_Values.GROSS_ERROR_MAX_STEPS) {
						stepLimitReached = true;
						break;
					}
					// viele Beobachtungen ueber der Grenze an EINEM Punkt (z. B. gestoerte Messabschnitte) -> in einem Schritt
					String commonPoint = blockAllowed ? this.dominantPoint(over) : null;
					if (commonPoint != null) {
						double maxLinear = 0.0;
						for (Max_Error_Row row : over)
							maxLinear = Math.max(maxLinear, row.linearError);
						double blockLimit = Math.max(this.statistical ? 0.0 : limit, Klein_Method_Default_Values.GROSS_ERROR_BLOCK_FRACTION * maxLinear);
						List<Max_Error_Row> block = new ArrayList<Max_Error_Row>();
						for (Max_Error_Row row : over)
							if (row.linearError >= blockLimit && (commonPoint.equals(row.startPointName) || commonPoint.equals(row.endPointName))
									&& block.size() < Klein_Method_Default_Values.GROSS_ERROR_BLOCK_MAX)
								block.add(row);
						if (block.size() > 1) {
							for (Max_Error_Row row : block)
								Klein_Method.this.databaseHandler.setObservationEnabled(row, false);
							removed.addAll(block);
							for (Max_Error_Row row : block)
								if (row != worst)
									companions.add(row);
							lastBlock = block;
							steps++;
							Klein_Method.this.protocol.logEvent("  " + steps + ". Punkt " + commonPoint + ": " + block.size() + " Beobachtungen über "
									+ formatLinear(blockLimit) + " gemeinsam vorübergehend herausgenommen (größte " + describeObservation(worst) + " | Fehler ≈ "
									+ formatLinear(worst.linearError) + ")");
							Klein_Method.this.logger.log("Klein_Method: Grobe Fehler bereinigen - Punkt " + commonPoint + ", " + block.size()
									+ " Beobachtungen gemeinsam herausgenommen.");
							continue;
						}
					}
					// verstreute, voneinander unabhaengige Ausreisser (kein gemeinsamer Punkt) -> mehrere in einem Schritt
					List<Max_Error_Row> batch = blockAllowed ? this.independentBatch(over, worst, limit) : java.util.Collections.<Max_Error_Row>emptyList();
					if (batch.size() > 1) {
						for (Max_Error_Row row : batch)
							Klein_Method.this.databaseHandler.setObservationEnabled(row, false);
						removed.addAll(batch);
						companions.addAll(batch.subList(1, batch.size()));
						lastBlock = batch;
						steps++;
						Klein_Method.this.protocol.logEvent("  " + steps + ". " + batch.size() + " voneinander unabhängige Beobachtungen über der Grenze "
								+ "(Teststatistik mindestens halb so groß wie die größte) gemeinsam vorübergehend herausgenommen (stärkste "
								+ describeObservation(worst) + " | Fehler ≈ " + formatLinear(worst.linearError) + ")");
						Klein_Method.this.logger.log("Klein_Method: Grobe Fehler bereinigen - " + batch.size() + " unabhaengige Beobachtungen gemeinsam herausgenommen.");
						continue;
					}
					Klein_Method.this.databaseHandler.setObservationEnabled(worst, false);
					removed.add(worst);
					lastRemoved = worst;
					steps++;
					Klein_Method.this.protocol.logEvent("  " + steps + ". vor\u00FCbergehend herausgenommen: " + describeObservation(worst) + " | Fehler \u2248 " + formatLinear(worst.linearError));
					Klein_Method.this.logger.log("Klein_Method: Grobe Fehler bereinigen - herausgenommen: " + describeObservation(worst));
				}
				// Wiederaufnahme-Pruefung: zusaetzlich die einzeln herausgenommenen GNSS-Basislinien, deren Widerspruch zu den
				// neuen Koordinaten (ohne sie gerechnet) unter der Grenze liegt
				List<Max_Error_Row> candidates = new ArrayList<Max_Error_Row>(companions);
				if (failure == null && !stepLimitReached)
					for (Max_Error_Row row : removed)
						if (row.gnss && !this.statistical && !candidates.contains(row)) {
							double misclosure = this.externalGnssMisclosure(row);
							if (!Double.isNaN(misclosure) && misclosure <= this.limitFor(row, limit))
								candidates.add(row);
						}
				if (failure == null && !stepLimitReached && !candidates.isEmpty()) {
					failure = this.crossCheckCompanions(candidates, removed, restored, skip, limit);
					Max_Error_Row worst = this.findWorstUnacceptedObservation(skip);
					remainingWorst = worst == null ? Double.NaN : worst.linearError;
				}
				// kaum kontrollierte Beobachtungen: dort waere ein grober Fehler nicht erkennbar
				for (Max_Error_Row row : Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE))
					if (!Double.isNaN(row.redundancy) && row.redundancy < Klein_Method_Default_Values.GROSS_ERROR_MIN_REDUNDANCY)
						uncontrolled.add(row);
				if (failure == null)
					try {
						this.diagnoseCauses(removed, diagnosis);
					} catch (SQLException e) {
						Klein_Method.this.logger.log("Klein_Method: Ursachensuche fehlgeschlagen: " + e.getMessage());
					}
				for (String[] line : diagnosis)
					Klein_Method.this.protocol.logEvent("  Mögliche Ursache: " + line[1]);
				if (!uncontrolled.isEmpty())
					Klein_Method.this.protocol.logEvent("  kaum kontrolliert (Redundanzanteil < " + Math.round(100 * Klein_Method_Default_Values.GROSS_ERROR_MIN_REDUNDANCY)
							+ " %), grobe Fehler dort nicht erkennbar: " + uncontrolled.size() + " Beobachtungen, z. B. " + joinDescriptions(uncontrolled.subList(0, Math.min(5, uncontrolled.size()))).replace("\n", "; "));
				Klein_Method.this.databaseHandler.commit();
			} catch (SQLException e) {
				failure = "Datenbankfehler: " + e.getMessage();
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			final String finalFailure = failure;
			final double finalRemaining = remainingWorst;
			final boolean finalStepLimit = stepLimitReached;
			final Gross_Error_Precheck_Part.Result finalPrecheck = precheck;
			if (this.automaticMode) {
				// Automatik: kein Fenster - alle Herausgenommenen als grobe Fehler bestaetigen, bei Fehler alles zuruecknehmen
				Set<String> keys = new HashSet<String>();
				if (failure == null)
					for (Max_Error_Row row : removed)
						keys.add(row.key());
				for (String[] line : diagnosis)
					Klein_Method.this.logger.log("Klein_Method: Grobe Fehler (Automatik) - moegliche Ursache: " + line[1]);
				if (failure != null)
					Klein_Method.this.protocol.logEvent("GROBE FEHLER BEREINIGEN (AUTOMATIK): abgebrochen - " + failure);
				this.automaticRemoved = keys.size();
				this.finishGrossErrorCleanup(removed, notRemovable, keys, failure != null, false);
				return;
			}
			Platform.runLater(() -> this.showGrossErrorCleanupDialog(removed, notRemovable, restored, finalPrecheck, uncontrolled, diagnosis, finalRemaining, finalStepLimit,
					finalFailure));
		}
		/**
		 * die schlechteste Beobachtung und weitere ueber der Grenze mit mindestens GROSS_ERROR_BLOCK_FRACTION der
		 * groessten Abweichung, die keinen Punkt mit einer schon gewaehlten teilen; hoechstens GROSS_ERROR_BATCH_MAX.
		 * over absteigend sortiert, erstes Element = worst.
		 */
		private List<Max_Error_Row> independentBatch(List<Max_Error_Row> over, Max_Error_Row worst, double limit) {
			List<Max_Error_Row> batch = new ArrayList<Max_Error_Row>();
			if (over.isEmpty() || over.get(0) != worst)
				return batch;
			// over: alle ueber der Grenze, nach Teststatistik absteigend
			double statisticLimit = Klein_Method_Default_Values.GROSS_ERROR_BLOCK_FRACTION * worst.value;
			Set<String> usedPoints = new HashSet<String>();
			for (Max_Error_Row row : over) {
				if (batch.size() >= Klein_Method_Default_Values.GROSS_ERROR_BATCH_MAX || row.value < statisticLimit)
					break;
				String start = String.valueOf(row.startPointName), end = String.valueOf(row.endPointName);
				if (usedPoints.contains(start) || usedPoints.contains(end))
					continue;
				batch.add(row);
				usedPoints.add(start);
				usedPoints.add(end);
			}
			return batch;
		}
		/**
		 * Robuste Vorauswahl: Beobachtungen, die die Vorpruefung vor der Ausgleichung als auffaellig erkannt hat
		 * (verdaechtiges Punktpaar, Einzelbasislinie, Doppelmessung) und die jetzt ueber der Grenze liegen - nach
		 * Teststatistik, hoechstens GROSS_ERROR_ROBUST_MAX. Die Vorpruefung arbeitet ohne Ausgleichung, ein grober
		 * Fehler verschmiert dort nicht auf die Nachbarn. DB offen.
		 */
		private List<Max_Error_Row> robustPreSelection(Gross_Error_Precheck_Part.Result precheck, Set<String> skip, double limit) throws SQLException {
			List<Max_Error_Row> block = new ArrayList<Max_Error_Row>();
			for (Max_Error_Row row : this.unacceptedObservationsAbove(skip, limit))
				if (precheck.isSuspect(row) && block.size() < Klein_Method_Default_Values.GROSS_ERROR_ROBUST_MAX)
					block.add(row);
			return block;
		}

		/**
		 * Ursachensuche fuer herausgenommene GNSS-Basislinien aus ihrem Widerspruch zu den neuen Koordinaten (ohne sie
		 * gerechnet, also nicht verschmiert): Punktverwechslung (der Vektor fuehrt zu einem anderen Punkt) und
		 * Antennenhoehe (Abweichung fast nur in der Hoehe; mehrere Basislinien einer Station mit gleichem Vorzeichen).
		 * Je Zeile {Oberflaeche, Protokoll}.
		 */
		private void diagnoseCauses(List<Max_Error_Row> removed, List<String[]> diagnosis) throws SQLException {
			double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
			Map<String, double[]> points = new java.util.HashMap<String, double[]>();
			Map<String, List<double[]>> heightByStation = new LinkedHashMap<String, List<double[]>>();
			Map<String, List<String>> baselinesByStation = new java.util.HashMap<String, List<String>>();
			this.diagnoseTerrestrialHeights(removed, diagnosis);
			for (Max_Error_Row row : removed) {
				if (!row.gnss)
					continue;
				if (points.isEmpty())
					for (Object[] p : Klein_Method.this.databaseHandler.readAllPointCoordinates())
						points.put((String) p[0], new double[] { (Double) p[1], (Double) p[2], (Double) p[3] });
				double[] v = Klein_Method.this.databaseHandler.readGnssVector(row.observationId);
				double[] s = points.get(row.startPointName), e = points.get(row.endPointName);
				if (v == null || s == null || e == null)
					continue;
				double[] w = { e[0] - s[0] - v[0], e[1] - s[1] - v[1], e[2] - s[2] - v[2] };
				double size = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
				if (!(size > limit))
					continue;
				String baseline = row.startPointName + " - " + row.endPointName;
				// Punktverwechslung: Vektor von Start fuehrt zu einem anderen Punkt bzw. endet an einem anderen Startpunkt
				String[] swap = this.nearestOther(points, new double[] { s[0] + v[0], s[1] + v[1], s[2] + v[2] }, row.startPointName, row.endPointName, size);
				if (swap != null) {
					diagnosis.add(new String[] { String.format(tr("%s: Vektor passt zu Punkt %s statt %s (Abstand %s) - Punktverwechslung?"), baseline, swap[0],
							row.endPointName, swap[1]), String.format("%s: Vektor passt zu Punkt %s statt %s (Abstand %s) - Punktverwechslung?", baseline, swap[0],
									row.endPointName, swap[1]) });
					continue;
				}
				swap = this.nearestOther(points, new double[] { e[0] - v[0], e[1] - v[1], e[2] - v[2] }, row.startPointName, row.endPointName, size);
				if (swap != null) {
					diagnosis.add(new String[] { String.format(tr("%s: Vektor passt zu Punkt %s statt %s (Abstand %s) - Punktverwechslung?"), baseline, swap[0],
							row.startPointName, swap[1]), String.format("%s: Vektor passt zu Punkt %s statt %s (Abstand %s) - Punktverwechslung?", baseline, swap[0],
									row.startPointName, swap[1]) });
					continue;
				}
				// Hoehe: Abweichung fast nur in Z (lokales System: senkrecht zur Tangentialebene)
				if (Math.abs(w[2]) >= 0.8 * size) {
					// aus Sicht der Station: Fehler am Ziel = +w, am Start = -w
					heightByStation.computeIfAbsent(row.endPointName, k -> new ArrayList<double[]>()).add(new double[] { w[2] });
					heightByStation.computeIfAbsent(row.startPointName, k -> new ArrayList<double[]>()).add(new double[] { -w[2] });
					baselinesByStation.computeIfAbsent(row.endPointName, k -> new ArrayList<String>()).add(baseline);
					baselinesByStation.computeIfAbsent(row.startPointName, k -> new ArrayList<String>()).add(baseline);
				}
			}
			// Stationen mit den meisten Hoehen-Abweichungen zuerst; eine Basislinie erklaert nur eine Station (die
			// Gegenstation einer fehlerhaften Antennenhoehe erscheint sonst ebenfalls auffaellig)
			Set<String> explained = new HashSet<String>();
			Map<String, Integer> targetCounts = null;
			List<String> stations = new ArrayList<String>(heightByStation.keySet());
			stations.sort((x, y) -> Integer.compare(heightByStation.get(y).size(), heightByStation.get(x).size()));
			for (String station : stations) {
				List<double[]> list = new ArrayList<double[]>();
				List<String> lines = new ArrayList<String>();
				List<String> baselines = baselinesByStation.get(station);
				for (int i = 0; i < baselines.size(); i++)
					if (!explained.contains(baselines.get(i))) {
						list.add(heightByStation.get(station).get(i));
						lines.add(baselines.get(i));
					}
				if (list.size() < 2)
					continue;
				double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
				for (double[] d : list) {
					min = Math.min(min, d[0]);
					max = Math.max(max, d[0]);
					sum += d[0];
				}
				double mean = sum / list.size();
				boolean sameSign = min > 0 || max < 0;
				if (sameSign && max - min <= 0.3 * Math.abs(mean) + 0.02) {
					String m = formatLinear(mean);
					diagnosis.add(new String[] { String.format(tr("Station %s: %d Basislinien weichen fast nur in der Höhe ab (im Mittel %s) - Antennenhöhe an %s prüfen"),
							station, list.size(), m, station), String.format("Station %s: %d Basislinien weichen fast nur in der Hoehe ab (im Mittel %s) - Antennenhoehe an %s pruefen",
									station, list.size(), m, station) });
					// mehr Zenitwinkel als Basislinien an diesem Punkt: eine falsche Zielhoehe der Zenitwinkel zieht die Hoehe ebenso
					int zenithTo = 0;
					if (targetCounts == null) {
						targetCounts = new java.util.HashMap<String, Integer>();
						for (Object[] o : Klein_Method.this.databaseHandler.readActiveTargetHeights())
							if ((Integer) o[1] == ObservationType.ZENITH_ANGLE.getId())
								targetCounts.merge((String) o[3], 1, Integer::sum);
					}
					zenithTo = targetCounts.getOrDefault(station, 0);
					if (zenithTo >= 3)
						diagnosis.add(new String[] { String.format(tr("  oder: Zielhöhe der %d Zenitwinkel nach %s prüfen - sie bestimmen dessen Höhe mit"), zenithTo, station),
								String.format("  oder: Zielhoehe der %d Zenitwinkel nach %s pruefen - sie bestimmen dessen Hoehe mit", zenithTo, station) });
					explained.addAll(lines);
				}
			}
			Set<String> reported = new HashSet<String>();
			for (List<String> list : baselinesByStation.values())
				for (String baseline : list)
					if (!explained.contains(baseline) && reported.add(baseline)) {
						diagnosis.add(new String[] { String.format(tr("%s: Abweichung fast nur in der Höhe - Antennenhöhe an einem der beiden Punkte prüfen"), baseline),
								String.format("%s: Abweichung fast nur in der Hoehe - Antennenhoehe an einem der beiden Punkte pruefen", baseline) });
					}
		}

		/**
		 * Ursachensuche Tachymeter (Zenitwinkel): fehlende Zielhoehen (Zielhoehe 0 bei gesetzter Instrumentenhoehe bei der
		 * Mehrheit), gleich grosse Hoehenabweichung aller Zenitwinkel zu einem Ziel (Zielhoehe) bzw. von einer Station
		 * (Instrumentenhoehe). Je Zeile {Oberflaeche, Protokoll}.
		 */
		private void diagnoseTerrestrialHeights(List<Max_Error_Row> removed, List<String[]> diagnosis) throws SQLException {
			List<double[]> zenith = new ArrayList<double[]>(); // {linear, ih, th}
			Map<String, List<double[]>> byTarget = new LinkedHashMap<String, List<double[]>>(), byStation = new LinkedHashMap<String, List<double[]>>();
			Map<double[], String> targetOf = new java.util.IdentityHashMap<double[], String>();
			Set<double[]> explained = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<double[], Boolean>());
			int zero = 0;
			for (Max_Error_Row row : removed) {
				if (row.gnss || row.observationType != ObservationType.ZENITH_ANGLE || Double.isNaN(row.linearError))
					continue;
				double[] heights = Klein_Method.this.databaseHandler.readObservationHeights(row.observationId);
				if (heights == null)
					continue;
				double[] item = { row.linearError, heights[0], heights[1] };
				zenith.add(item);
				if (heights[1] == 0.0 && heights[0] > 0.0)
					zero++;
				targetOf.put(item, row.endPointName);
				byTarget.computeIfAbsent(row.endPointName, k -> new ArrayList<double[]>()).add(item);
				byStation.computeIfAbsent(row.startPointName, k -> new ArrayList<double[]>()).add(item);
			}
			if (zenith.isEmpty())
				return;
			if (zero >= 5 && zero >= 0.5 * zenith.size()) {
				String m = formatLinear(this.median(zenith));
				diagnosis.add(new String[] { String.format(tr("%d von %d herausgenommenen Zenitwinkeln haben Zielhöhe 0 (Abweichung im Median %s) - vermutlich fehlen die "
						+ "Zielhöhen im Import; bitte mit Zielhöhen neu importieren und diese Zenitwinkel nicht als grobe Fehler übernehmen"), zero, zenith.size(), m),
						String.format("%d von %d herausgenommenen Zenitwinkeln haben Zielhoehe 0 (Abweichung im Median %s) - vermutlich fehlen die Zielhoehen im Import",
								zero, zenith.size(), m) });
				return;
			}
			int listed = 0;
			for (boolean target : new boolean[] { true, false })
				for (Map.Entry<String, List<double[]>> e : (target ? byTarget : byStation).entrySet()) {
					// Station: nur Zenitwinkel, die kein Ziel schon erklaert, und mindestens 3 verschiedene Ziele
					List<double[]> list = new ArrayList<double[]>();
					Set<String> targets = new HashSet<String>();
					for (double[] d : e.getValue())
						if (target || !explained.contains(d)) {
							list.add(d);
							targets.add(targetOf.get(d));
						}
					double median = this.median(list);
					if (list.size() < 3 || median < 0.3 || listed >= 10 || (!target && targets.size() < 3))
						continue;
					boolean consistent = true;
					for (double[] d : list)
						consistent &= Math.abs(d[0] - median) <= 0.3 * median;
					if (!consistent)
						continue;
					double entered = list.get(0)[target ? 2 : 1];
					listed++;
					if (target)
						explained.addAll(list);
					String m = formatLinear(median), h = String.format(Locale.GERMANY, "%.3f", entered);
					diagnosis.add(target
							? new String[] { String.format(tr("Ziel %s: %d Zenitwinkel weichen gleich weit ab (≈ %s) - Zielhöhe an %s prüfen (eingetragen %s m)"), e.getKey(), list.size(), m,
									e.getKey(), h), String.format("Ziel %s: %d Zenitwinkel weichen gleich weit ab (%s) - Zielhoehe an %s pruefen (eingetragen %s m)", e.getKey(), list.size(), m, e.getKey(), h) }
							: new String[] { String.format(tr("Station %s: %d Zenitwinkel weichen gleich weit ab (≈ %s) - Instrumentenhöhe an %s prüfen (eingetragen %s m)"), e.getKey(),
									list.size(), m, e.getKey(), h), String.format("Station %s: %d Zenitwinkel weichen gleich weit ab (%s) - Instrumentenhoehe an %s pruefen (eingetragen %s m)",
											e.getKey(), list.size(), m, e.getKey(), h) });
				}
		}

		private double median(List<double[]> list) {
			double[] v = new double[list.size()];
			for (int i = 0; i < v.length; i++)
				v[i] = list.get(i)[0];
			java.util.Arrays.sort(v);
			return v.length == 0 ? Double.NaN : v.length % 2 == 1 ? v[v.length / 2] : 0.5 * (v[v.length / 2 - 1] + v[v.length / 2]);
		}

		/**
		 * naechster andere Punkt (nicht a/b) zur Position q, wenn er deutlich besser passt: Abstand hoechstens die halbe
		 * Grenze grober Fehler (mind. 2 cm) und weniger als ein Viertel des bisherigen Widerspruchs. {Name, Abstand} oder null.
		 */
		private String[] nearestOther(Map<String, double[]> points, double[] q, String a, String b, double misclosure) {
			double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
			String best = null;
			double bestDistance = Double.POSITIVE_INFINITY;
			for (Map.Entry<String, double[]> p : points.entrySet()) {
				if (p.getKey().equals(a) || p.getKey().equals(b))
					continue;
				double[] c = p.getValue();
				double d = Math.sqrt((c[0] - q[0]) * (c[0] - q[0]) + (c[1] - q[1]) * (c[1] - q[1]) + (c[2] - q[2]) * (c[2] - q[2]));
				if (d < bestDistance) {
					bestDistance = d;
					best = p.getKey();
				}
			}
			return best != null && bestDistance <= Math.max(0.02, 0.5 * limit) && bestDistance < 0.25 * misclosure ? new String[] { best, formatLinear(bestDistance) } : null;
		}

		/**
		 * Widerspruch einer (deaktivierten) GNSS-Basislinie zu den aktuellen Koordinaten [m] (Betrag 3D), NaN = unbekannt.
		 * Die Ausgleichung lief ohne sie - der Wert ist also nicht verschmiert.
		 */
		private double externalGnssMisclosure(Max_Error_Row row) throws SQLException {
			double[] vector = Klein_Method.this.databaseHandler.readGnssVector(row.observationId);
			double[] start = Klein_Method.this.databaseHandler.readPointCoordinates(row.startPointName);
			double[] end = Klein_Method.this.databaseHandler.readPointCoordinates(row.endPointName);
			if (vector == null || start == null || end == null)
				return Double.NaN;
			double dy = end[0] - start[0] - vector[0], dx = end[1] - start[1] - vector[1], dz = end[2] - start[2] - vector[2];
			return Math.sqrt(dy * dy + dx * dx + dz * dz);
		}
		/** aktive Beobachtungen mit linearer Abweichung, Schluessel = key(). DB offen. */
		private Map<String, Max_Error_Row> currentObservationErrors() throws SQLException {
			Map<String, Max_Error_Row> rows = new java.util.HashMap<String, Max_Error_Row>();
			for (Max_Error_Row row : Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE))
				rows.put(row.key(), row);
			return rows;
		}
		private EstimationStateType adjustQuietly() {
			try {
				return Klein_Method.this.runHeadlessAdjustmentSynchronously();
			} catch (Exception e) {
				return null;
			}
		}
		/**
		 * Gegenprobe: die in Gruppen nur mitgenommenen Beobachtungen (nicht die jeweils schlechteste) wieder aufnehmen und
		 * neu ausgleichen. Liegen sie jetzt unter der Grenze, bleiben sie drin - aber nur, wenn danach keine Beobachtung
		 * mehr ueber der Grenze liegt. Sonst alles wie vor der Gegenprobe. Rueckgabe: Fehlertext oder null.
		 */
		private String crossCheckCompanions(List<Max_Error_Row> companions, List<Max_Error_Row> removed, List<Max_Error_Row> restored, Set<String> skip,
				double limit) throws SQLException {
			for (Max_Error_Row row : companions)
				Klein_Method.this.databaseHandler.setObservationEnabled(row, true);
			boolean accepted = false;
			List<Max_Error_Row> back = new ArrayList<Max_Error_Row>();
			if (this.adjustQuietly() == EstimationStateType.ERROR_FREE_ESTIMATION) {
				Map<String, Max_Error_Row> now = this.currentObservationErrors();
				List<Max_Error_Row> again = new ArrayList<Max_Error_Row>();
				for (Max_Error_Row row : companions) {
					Max_Error_Row current = now.get(row.key());
					if (current != null && this.below(current, limit))
						back.add(row);
					else
						again.add(row);
				}
				if (!back.isEmpty()) {
					boolean valid = true;
					if (!again.isEmpty()) {
						for (Max_Error_Row row : again)
							Klein_Method.this.databaseHandler.setObservationEnabled(row, false);
						valid = this.adjustQuietly() == EstimationStateType.ERROR_FREE_ESTIMATION;
					}
					accepted = valid && this.unacceptedObservationsAbove(skip, limit).isEmpty();
				}
			}
			if (accepted) {
				restored.addAll(back);
				removed.removeAll(back);
				Klein_Method.this.protocol.logEvent("  Gegenprobe: " + back.size() + " von " + companions.size()
						+ " geprueften Beobachtungen liegen jetzt unter der Grenze und sind wieder aufgenommen");
				for (Max_Error_Row row : back)
					Klein_Method.this.protocol.logEvent("    wieder aufgenommen: " + describeObservation(row));
				return null;
			}
			for (Max_Error_Row row : companions)
				Klein_Method.this.databaseHandler.setObservationEnabled(row, false);
			EstimationStateType result = this.adjustQuietly();
			if (result != EstimationStateType.ERROR_FREE_ESTIMATION)
				return "Netzausgleichung nach der Gegenprobe nicht fehlerfrei (" + result + ")";
			// gemeinsam nicht moeglich (meist, weil ein echter grober Fehler darunter die anderen wieder verzerrt): einzeln
			// pruefen - jede Beobachtung allein wieder aufnehmen und behalten, wenn danach nichts ueber der Grenze liegt
			List<Max_Error_Row> single = this.crossCheckSingly(companions, skip, limit);
			if (!single.isEmpty()) {
				restored.addAll(single);
				removed.removeAll(single);
				Klein_Method.this.protocol.logEvent("  Gegenprobe einzeln: " + single.size() + " von " + companions.size()
						+ " geprueften Beobachtungen liegen allein wieder aufgenommen unter der Grenze und bleiben drin");
				for (Max_Error_Row row : single)
					Klein_Method.this.protocol.logEvent("    wieder aufgenommen: " + describeObservation(row));
			}
			else
				Klein_Method.this.protocol.logEvent("  Gegenprobe: keine der " + companions.size() + " geprueften Beobachtungen wieder aufnehmbar");
			result = this.adjustQuietly();
			return result == EstimationStateType.ERROR_FREE_ESTIMATION ? null : "Netzausgleichung nach der Gegenprobe nicht fehlerfrei (" + result + ")";
		}
		/**
		 * Einzelne Gegenprobe: Kandidaten (hoechstens GROSS_ERROR_SINGLE_CHECK_MAX bzw. GROSS_ERROR_SINGLE_CHECK_SECONDS, kleinste
		 * Abweichung bei der Entdeckung zuerst) einzeln wieder aufnehmen; bleibt die Beobachtung unter der Grenze und liegt danach keine andere darueber,
		 * bleibt sie drin, sonst wird sie wieder herausgenommen. Rueckgabe: die wieder aufgenommenen.
		 */
		private List<Max_Error_Row> crossCheckSingly(List<Max_Error_Row> candidates, Set<String> skip, double limit) throws SQLException {
			List<Max_Error_Row> order = new ArrayList<Max_Error_Row>(candidates);
			order.sort((x, y) -> Double.compare(Double.isNaN(x.linearError) ? Double.MAX_VALUE : x.linearError, Double.isNaN(y.linearError) ? Double.MAX_VALUE : y.linearError));
			List<Max_Error_Row> back = new ArrayList<Max_Error_Row>();
			int tested = 0;
			long start = System.currentTimeMillis();
			for (Max_Error_Row row : order) {
				// grosse Netze: Zeitbudget, damit die Bereinigung nicht unverhaeltnismaessig lange dauert
				if (tested++ >= Klein_Method_Default_Values.GROSS_ERROR_SINGLE_CHECK_MAX
						|| System.currentTimeMillis() - start > Klein_Method_Default_Values.GROSS_ERROR_SINGLE_CHECK_SECONDS * 1000L) {
					Klein_Method.this.protocol.logEvent("  Gegenprobe einzeln nach " + (tested - 1) + " Beobachtungen beendet (Grenze Anzahl/Zeit)");
					break;
				}
				Klein_Method.this.databaseHandler.setObservationEnabled(row, true);
				boolean keep = false;
				if (this.adjustQuietly() == EstimationStateType.ERROR_FREE_ESTIMATION) {
					Max_Error_Row current = this.currentObservationErrors().get(row.key());
					keep = current != null && this.below(current, limit) && this.unacceptedObservationsAbove(skip, limit).isEmpty();
				}
				if (keep)
					back.add(row);
				else
					Klein_Method.this.databaseHandler.setObservationEnabled(row, false);
			}
			return back;
		}
		/** Bestaetigungsfenster (JavaFX-Thread); die Umsetzung laeuft danach im Hintergrund. */
		private void showGrossErrorCleanupDialog(List<Max_Error_Row> removed, List<Max_Error_Row> notRemovable, List<Max_Error_Row> restored,
				Gross_Error_Precheck_Part.Result precheck, List<Max_Error_Row> uncontrolled, List<String[]> diagnosis, double remainingWorst,
				boolean stepLimitReached, String failure) {
			final Set<String> confirmedKeys = new HashSet<String>();
			boolean cancelled = false;
			if (failure != null) {
				OptionDialog.showErrorDialog(tr("Klein_Method"), tr("Grobe Fehler bereinigen abgebrochen"),
						failure + tr("\n\nAlle in diesem Durchgang herausgenommenen Beobachtungen werden wieder aktiviert."));
				cancelled = true;
			} else if (removed.isEmpty()) {
				Alert alert = new Alert(AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Grobe Fehler bereinigen"));
				alert.setHeaderText(tr("Keine Beobachtung über der Grenze grober Fehler"));
				alert.setContentText((notRemovable.isEmpty() ? "" : tr("Nicht herausnehmbar (sonst Netz nicht bestimmbar), werden in Phase 2 behandelt:\n")
						+ joinDescriptions(notRemovable) + "\n\n") + (precheck == null ? "" : String.join("\n\n", precheck.warnings) + (precheck.warnings.isEmpty() ? "" : "\n\n")
								+ precheck.summary() + "\n\n")
						+ this.uncontrolledText(uncontrolled) + tr("Klein_Method kann gestartet werden."));
				alert.showAndWait();
			} else {
				javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
				dialog.setTitle(tr("Klein_Method - Grobe Fehler bereinigen"));
				dialog.setHeaderText(removed.size() + tr(" vermutete(r) grobe(r) Fehler gefunden - bitte bestätigen"));
				javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8);
				javafx.scene.control.Label intro = new javafx.scene.control.Label(
						tr("Schrittweise wurde die auffälligste Beobachtung (größte Teststatistik unter denen über der Grenze) vorübergehend herausgenommen "
						+ "und neu gerechnet, bis keine mehr über der Grenze liegt (Reihenfolge = Entdeckung). Ein grober Fehler macht oft auch gesunde "
						+ "Nachbarn auffällig - deshalb schrittweise. In einem Schritt gemeinsam herausgenommen werden: bei Häufung an einem Punkt "
						+ "(mindestens 5 und 80 %) die stärksten davon; sonst bis zu 10 voneinander unabhängige Ausreißer ohne gemeinsamen Punkt. "
						+ "Eine Gegenprobe am Ende nimmt Beobachtungen wieder auf, die danach unter der Grenze liegen.\n\n"
						+ "Angehakt = grober Fehler: bleibt für Phase 1 (Gruppenkalibrierung) außen vor (in JAG3D vorübergehend "
						+ "deaktiviert). Vor Phase 2 fragt Klein_Method nach, was damit geschehen soll.\n"
						+ "Nicht angehakt = kein grober Fehler: wird sofort wieder aufgenommen und nicht mehr gemeldet."));
				intro.setWrapText(true);
				intro.setMaxWidth(640);
				box.getChildren().add(intro);
				if (precheck != null && !precheck.warnings.isEmpty()) {
					javafx.scene.control.Label warningLabel = new javafx.scene.control.Label(String.join("\n\n", precheck.warnings));
					warningLabel.setWrapText(true);
					warningLabel.setMaxWidth(640);
					warningLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #B71C1C; -fx-background-color: #FFEBEE; -fx-padding: 6;");
					box.getChildren().add(0, warningLabel);
				}
				if (precheck != null) {
					javafx.scene.control.Label precheckLabel = new javafx.scene.control.Label(precheck.summary()
							+ tr(" Markiert mit „Vorprüfung auffällig“ = schon vor der Ausgleichung erkennbar (Schleifenschluss oder Doppelmessung) - "
							+ "ein starker Hinweis auf einen echten groben Fehler. Bericht im Protokoll."));
					precheckLabel.setWrapText(true);
					precheckLabel.setMaxWidth(640);
					box.getChildren().add(precheckLabel);
				}
				String pointText = this.dominantPointText(removed);
				if (pointText != null) {
					javafx.scene.control.Label pointLabel = new javafx.scene.control.Label(pointText);
					pointLabel.setWrapText(true);
					pointLabel.setMaxWidth(640);
					pointLabel.setStyle("-fx-font-weight: bold;");
					box.getChildren().add(pointLabel);
				}
				List<javafx.scene.control.CheckBox> checkBoxes = new ArrayList<javafx.scene.control.CheckBox>();
				box.getChildren().add(this.linearLegendLabel());
				for (int i = 0; i < removed.size(); i++) {
					Max_Error_Row row = removed.get(i);
					javafx.scene.control.CheckBox checkBox = new javafx.scene.control.CheckBox((i + 1) + ". " + describeObservation(row)
							+ (precheck != null && precheck.isSuspect(row) ? " - " + tr("Vorprüfung auffällig") : ""));
					// Vorauswahl nach linearer Abweichung (bis Richtwert = kein grober Fehler)
					checkBox.setSelected(!this.isLinearErrorAcceptable(row));
					checkBox.setTooltip(new javafx.scene.control.Tooltip(tr("Werte zum Zeitpunkt der Entdeckung. Angehakt = grober Fehler (bleibt vorerst deaktiviert).\n"
							+ "Vorauswahl nach der linearen Abweichung (farbige Angabe rechts).")));
					checkBoxes.add(checkBox);
					javafx.scene.layout.HBox line = new javafx.scene.layout.HBox(12, checkBox, this.linearErrorLabel(row));
					line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
					box.getChildren().add(line);
				}
				StringBuilder footer = new StringBuilder();
				if (!diagnosis.isEmpty()) {
					footer.append(tr("Mögliche Ursachen (aus den Widersprüchen zu den neuen Koordinaten):")).append("\n");
					for (String[] line : diagnosis)
						footer.append("  ").append(line[0]).append("\n");
					footer.append("\n");
				}
				footer.append(this.uncontrolledText(uncontrolled));
				if (!restored.isEmpty())
					footer.append(tr("Nach der Gegenprobe wieder aufgenommen (jetzt unter der Grenze):\n")).append(joinDescriptions(restored)).append("\n");
				if (!notRemovable.isEmpty())
					footer.append("Nicht herausnehmbar (sonst Netz nicht bestimmbar), werden in Phase 2 behandelt:\n").append(joinDescriptions(notRemovable)).append("\n");
				if (stepLimitReached)
					footer.append("Hinweis: Höchstzahl von ").append(Klein_Method_Default_Values.GROSS_ERROR_MAX_STEPS)
							.append(" Schritten erreicht - danach erneut „Grobe Fehler bereinigen“.\n");
				if (!Double.isNaN(remainingWorst))
					footer.append("Gr\u00F6\u00DFte verbleibende lineare Abweichung: ").append(formatLinear(remainingWorst));
				if (footer.length() > 0) {
					javafx.scene.control.Label footerLabel = new javafx.scene.control.Label(footer.toString());
					footerLabel.setWrapText(true);
					footerLabel.setMaxWidth(640);
					box.getChildren().add(footerLabel);
				}
				javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(box);
				scroll.setFitToWidth(true);
				scroll.setPrefViewportHeight(Math.min(520, 160 + 28 * removed.size()));
				scroll.setPrefViewportWidth(660);
				dialog.getDialogPane().setContent(scroll);
				ButtonType applyType = new ButtonType(tr("Übernehmen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
				ButtonType cancelType = new ButtonType(tr("Abbrechen (alles wieder aktivieren)"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
				dialog.getDialogPane().getButtonTypes().addAll(applyType, cancelType);
				java.util.Optional<ButtonType> answer = dialog.showAndWait();
				cancelled = !answer.isPresent() || answer.get() != applyType;
				if (!cancelled)
					for (int i = 0; i < removed.size(); i++)
						if (checkBoxes.get(i).isSelected())
							confirmedKeys.add(removed.get(i).key());
			}
			final boolean finalCancelled = cancelled;
			final boolean adjustmentFailed = failure != null;
			Thread thread = busyThread(() -> this.finishGrossErrorCleanup(removed, notRemovable, confirmedKeys, finalCancelled, adjustmentFailed),
					"Klein_Method_Gross_Error_Finish");
			thread.setDaemon(true);
			thread.start();
		}
		/** Hinweis auf kaum kontrollierte Beobachtungen (Oberflaeche) oder "". */
		private String uncontrolledText(List<Max_Error_Row> uncontrolled) {
			if (uncontrolled == null || uncontrolled.isEmpty())
				return "";
			return String.format(tr("Kaum kontrolliert (Redundanzanteil unter %d %%) - ein grober Fehler wäre dort nicht erkennbar, bitte im Feld "
					+ "absichern (zusätzliche Messung): %d Beobachtungen, z. B.\n%s"), Math.round(100 * Klein_Method_Default_Values.GROSS_ERROR_MIN_REDUNDANCY),
					uncontrolled.size(), joinDescriptions(uncontrolled.subList(0, Math.min(5, uncontrolled.size())))) + "\n\n";
		}
		/** true = lineare Abweichung bekannt und hoechstens der Richtwert (haelt die Ausgleichung aus). */
		private boolean isLinearErrorAcceptable(Max_Error_Row row) {
			return !Double.isNaN(row.linearError) && row.linearError <= Klein_Method.this.parameter.getGrossErrorLinearLimit();
		}
		/** farbige Angabe der linearen Abweichung (gruen bis Richtwert, rot darueber, grau unbekannt). */
		private javafx.scene.control.Label linearErrorLabel(Max_Error_Row row) {
			boolean known = !Double.isNaN(row.linearError);
			boolean ok = this.isLinearErrorAcceptable(row);
			javafx.scene.control.Label label = new javafx.scene.control.Label(known
					? tr("Fehler \u2248 ") + String.format(Locale.GERMANY, row.linearError < 10.0 ? "%.3f m" : "%.1f m", row.linearError) : tr("Fehler: k. A."));
			label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
			label.setStyle("-fx-font-weight: bold; -fx-padding: 1 6 1 6; -fx-background-radius: 4; "
					+ (!known ? "-fx-text-fill: #616161; -fx-background-color: #EEEEEE;"
							: ok ? "-fx-text-fill: #1B5E20; -fx-background-color: #C8E6C9;" : "-fx-text-fill: #B71C1C; -fx-background-color: #FFCDD2;"));
			label.setTooltip(new javafx.scene.control.Tooltip(tr("Lineare Abweichung: gesch\u00E4tzter grober Fehler aus JAG3D (ersatzweise Verbesserung v).\n"
					+ "Richtung/Zenitwinkel: Winkelfehler \u00D7 Strecke Stand\u2013Ziel (Querabweichung am Ziel), GNSS: L\u00E4nge des 3D-Fehlervektors.\n"
					+ "Gr\u00FCn = bis ") + String.format(Locale.GERMANY, "%.3f", Klein_Method.this.parameter.getGrossErrorLinearLimit())
					+ tr(" m, h\u00E4lt die Ausgleichung aus; rot = gr\u00F6\u00DFer, grober Fehler. Grenze grober Fehler (m) im Tab einstellbar.")));
			return label;
		}
		private javafx.scene.control.Label linearLegendLabel() {
			javafx.scene.control.Label legend = new javafx.scene.control.Label(tr("Grenze grober Fehler (lineare Abweichung): bis ")
					+ String.format(Locale.GERMANY, "%.3f", Klein_Method.this.parameter.getGrossErrorLinearLimit())
					+ tr(" m gr\u00FCn = h\u00E4lt die Ausgleichung aus (vorgew\u00E4hlt: wieder aufnehmen), dar\u00FCber rot = grober Fehler "
					+ "(vorgew\u00E4hlt: bleibt heraus). Die Vorauswahl l\u00E4sst sich \u00E4ndern."));
			legend.setWrapText(true);
			legend.setMaxWidth(640);
			legend.setStyle("-fx-font-style: italic;");
			return legend;
		}
		private void finishGrossErrorCleanup(List<Max_Error_Row> removed, List<Max_Error_Row> notRemovable, Set<String> confirmedKeys, boolean cancelled,
				boolean adjustmentFailed) {
			boolean valid = false;
			Klein_Method_Adjustment_Step step = null;
			ADJUSTMENT_LOCK.lock();
			try {
				if (this.statistical && !cancelled)
					Klein_Method.this.databaseHandler.clearStatisticalSnapshot(); // Befunde behandelt - neuer Test beim naechsten Automatik-Lauf
				int reactivated = 0;
				for (Max_Error_Row row : removed) {
					if (!cancelled && confirmedKeys.contains(row.key())) {
						Klein_Method.this.databaseHandler.storeGrossErrorEntry(row, Klein_Method_Database_Handler_Interface.GROSS_ERROR_EXCLUDED);
						Klein_Method.this.protocol.logEvent("  bestätigt als grober Fehler (bis Phase 2 deaktiviert): " + describeObservation(row));
					} else {
						Klein_Method.this.databaseHandler.setObservationEnabled(row, true);
						reactivated++;
						if (!cancelled) {
							Klein_Method.this.databaseHandler.storeGrossErrorEntry(row, Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED);
							Klein_Method.this.protocol.logEvent("  kein grober Fehler (wieder aufgenommen): " + describeObservation(row));
						}
					}
				}
				if (!cancelled)
					for (Max_Error_Row row : notRemovable)
						Klein_Method.this.databaseHandler.storeGrossErrorEntry(row, Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED);
				if (cancelled)
					Klein_Method.this.protocol.logEvent("GROBE FEHLER BEREINIGEN: abgebrochen - alle herausgenommenen Beobachtungen wieder aktiv.");
				Klein_Method.this.databaseHandler.commit();
				EstimationStateType result = adjustmentFailed ? null : EstimationStateType.ERROR_FREE_ESTIMATION;
				if (reactivated > 0)
					result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
				valid = result == EstimationStateType.ERROR_FREE_ESTIMATION;
				if (valid) {
					Klein_Method.this.databaseHandler.read(Klein_Method.this.parameter);
					step = Klein_Method.this.getCurrentAdjustmentStep();
				}
				Klein_Method.this.acceptOwnChangesIntoChecksum("Grobe Fehler bereinigen");
				Klein_Method.this.protocol.logEvent("GROBE FEHLER BEREINIGEN: fertig, " + confirmedKeys.size() + " als grober Fehler bestätigt, Max-logPost jetzt "
						+ String.format(Locale.GERMANY, "%.2f", Klein_Method.this.functions.getMaxLogPostValue(Klein_Method.this.parameter)));
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Grobe Fehler bereinigen - Abschluss fehlgeschlagen: " + e.getMessage());
				valid = false;
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			if (valid)
				this.evaluateGrossErrorGate(step);
			// Deaktivierte Beobachtungen und neue Ergebnisse auch in der JAG3D-Anzeige (wie nach F5)
			Klein_Method.this.refreshJag3dView(true);
			final boolean finalValid = valid;
			Platform.runLater(() -> {
				Klein_Method.this.grossErrorCleanupRunning.set(false);
				Klein_Method.this.setValidAdjustmentResultsAvailable(finalValid);
				if (Klein_Method.this.tabBuilder != null) {
					if (finalValid)
						Klein_Method.this.tabBuilder.refreshDataStatus();
					else
						Klein_Method.this.tabBuilder.applyDataStatus(Klein_Method_Data_Status.ERROR);
					Klein_Method.this.tabBuilder.refreshAutomationAvailability();
				}
				Klein_Method_Tab_Builder.Klein_Method_Map_Window.notifyDataChanged();
				if (!finalValid && !adjustmentFailed)
					OptionDialog.showErrorDialog(tr("Klein_Method"), tr("Netzausgleichung nach dem Bereinigen fehlgeschlagen"),
							tr("Bitte die Netzausgleichung (F5) erneut ausführen."));
			});
		}
		/**
		 * Zuordnung vor Phase 2 (Aufruf aus Klein_Method_Iteration_Task, Hintergrund-Thread, OHNE gehaltene
		 * Ausgleichungs-Sperre). Zeigt die vor Phase 1 herausgenommenen groben Fehler und wartet auf die Entscheidung.
		 * @return Schluessel der Beobachtungen, die wieder aufgenommen werden
		 */
		public Set<String> askGrossErrorAssignment(List<Max_Error_Row> excluded) {
			java.util.concurrent.CompletableFuture<Set<String>> future = new java.util.concurrent.CompletableFuture<Set<String>>();
			Platform.runLater(() -> {
				Set<String> reactivate = new HashSet<String>();
				try {
					javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
					dialog.setTitle(tr("Klein_Method - Zuordnung vor Phase 2"));
					dialog.setHeaderText(tr("Phase 1 ist fertig. Was soll mit den ") + excluded.size() + tr(" herausgenommenen groben Fehler(n) geschehen?"));
					javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
					grid.setHgap(10);
					grid.setVgap(6);
					javafx.scene.control.Label intro = new javafx.scene.control.Label(
							tr("Wieder aufnehmen: die Beobachtung rechnet in Phase 2 mit und wird einzeln kalibriert. Ist sie wirklich falsch, "
							+ "erreicht sie die Obergrenze der individuellen Messunsicherheit - Klein_Method hält dann mit Meldung an.\n"
							+ "Deaktiviert lassen: bleibt in JAG3D ausgeschaltet (z. B. zum Berichtigen von Zielpunkt, Reflektor- oder "
							+ "Antennenhöhe; danach in JAG3D wieder einschalten)."));
					intro.setWrapText(true);
					intro.setMaxWidth(640);
					grid.add(intro, 0, 0, 3, 1);
					grid.add(this.linearLegendLabel(), 0, excluded.size() + 1, 3, 1);
					List<javafx.scene.control.ComboBox<String>> choices = new ArrayList<javafx.scene.control.ComboBox<String>>();
					for (int i = 0; i < excluded.size(); i++) {
						javafx.scene.control.Label label = new javafx.scene.control.Label(describeObservation(excluded.get(i)));
						javafx.scene.control.ComboBox<String> choice = new javafx.scene.control.ComboBox<String>();
						choice.getItems().addAll("Deaktiviert lassen", "Wieder aufnehmen");
						choice.setValue(this.isLinearErrorAcceptable(excluded.get(i)) ? "Wieder aufnehmen" : "Deaktiviert lassen");
						choice.setTooltip(new javafx.scene.control.Tooltip(tr("Deaktiviert lassen = bleibt außen vor; Wieder aufnehmen = rechnet ab Phase 2 mit.")));
						choices.add(choice);
						grid.add(label, 0, i + 1);
						grid.add(this.linearErrorLabel(excluded.get(i)), 1, i + 1);
						grid.add(choice, 2, i + 1);
					}
					javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(grid);
					scroll.setFitToWidth(true);
					scroll.setPrefViewportHeight(Math.min(520, 150 + 32 * excluded.size()));
					scroll.setPrefViewportWidth(760);
					dialog.getDialogPane().setContent(scroll);
					dialog.getDialogPane().getButtonTypes().add(new ButtonType(tr("Weiter mit Phase 2"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
					dialog.showAndWait();
					for (int i = 0; i < excluded.size(); i++)
						if ("Wieder aufnehmen".equals(choices.get(i).getValue()))
							reactivate.add(excluded.get(i).key());
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Zuordnungsfenster vor Phase 2 fehlgeschlagen: " + e.getMessage());
				}
				future.complete(reactivate);
			});
			try {
				return future.get();
			} catch (Exception e) {
				return new HashSet<String>();
			}
		}
		/**
		 * fehlgeschlagene Ausgleichung waehrend eines Laufs (Automatik/Einzelschritt) wird IMMER gemeldet - auch wenn der
		 * Tab gerade nicht aktiv ist.
		 */
		public void handleFailedAdjustmentDuringRun(EstimationStateType result, String detail) {
			if (Klein_Method.this.isKleinMethodTabActive() && result != null) {
				Klein_Method.this.handleFailedAdjustment(result);
				return;
			}
			boolean diverged = result == EstimationStateType.NO_CONVERGENCE || result == EstimationStateType.ROBUST_ESTIMATION_FAILED;
			String header = diverged ? "Netzausgleichung divergiert" : "Netzausgleichung fehlgeschlagen";
			String message = (result == null ? "Fehler: " + detail : diverged
					? "Die Iteration der Netzausgleichung ist divergiert (Abbruchschranke erreicht, ohne zu konvergieren)."
					: "Die Netzausgleichung wurde mit einem Fehler abgebrochen (" + result + ").")
					+ "\n\nDie Automatik wurde beendet. Bitte die Ursache klären und danach die Netzausgleichung (F5) erneut ausführen.";
			if (Klein_Method.this.isKleinMethodTabActive()) {
				Klein_Method.this.exitAndLockKleinMethodTab(header, message);
				return;
			}
			Platform.runLater(() -> {
				OptionDialog.showErrorDialog(tr("Klein_Method"), header, message);
				if (Klein_Method.this.tabBuilder != null)
					Klein_Method.this.tabBuilder.applyDataStatus(Klein_Method_Data_Status.ERROR);
			});
		}	}

	// ============================================================================================================
	/** Schwarmwissen: Laufbericht, Teilen, Erfahrungswerte, Startwerte und Warnungen. */
	private final class Swarm_Part {

		/** Nach einem erfolgreichen Lauf (DONE, nicht verworfen): beim ersten Mal fragen, danach still senden. */
		private void autoShareAfterRun(java.io.File file) {
			if (Boolean.FALSE.equals(autoShareDecision))
				return;
			String relayUrl = Klein_Method.this.parameter.getSwarmRelayUrl();
			if (Klein_Method_Functions.Klein_Method_Swarm.sendRoute(relayUrl).isEmpty()) {
				Klein_Method.this.logger.log("Klein_Method: Laufbericht nicht automatisch geteilt - keine Token-Datei und kein Vermittlungsdienst eingetragen.");
				return;
			}
			if (Boolean.TRUE.equals(autoShareDecision)) {
				this.shareRunReportSilently(file);
				return;
			}
			String repository = Klein_Method.this.parameter.getSwarmRepository();
			Platform.runLater(() -> {
				if (autoShareDecision != null) { // inzwischen entschieden
					if (autoShareDecision)
						this.shareRunReportSilently(file);
					return;
				}
				ButtonType yes = new ButtonType(tr("Ja"), javafx.scene.control.ButtonBar.ButtonData.YES);
				ButtonType no = new ButtonType(tr("Nein"), javafx.scene.control.ButtonBar.ButtonData.NO);
				Alert alert = new Alert(AlertType.CONFIRMATION, "", yes, no);
				alert.setTitle(tr("Klein_Method - Laufbericht teilen"));
				alert.setHeaderText(tr("Laufbericht automatisch teilen?"));
				alert.setContentText(String.format(tr("Der Lauf ist erfolgreich beendet. Soll der anonyme Laufbericht jetzt und nach jedem weiteren erfolgreichen Lauf "
						+ "automatisch im Hintergrund an das Schwarmwissen (%s) gesendet werden?\n\n"
						+ "Ja: wird ohne weitere Meldung gesendet, solange JAG3D geöffnet ist.\n"
						+ "Nein: es wird nichts gesendet und bis zum Neustart von JAG3D nicht mehr gefragt.\n\n"
						+ "Keine Koordinaten, keine Punkt- oder Projektnamen."), repository));
				boolean answer = alert.showAndWait().filter(button -> button == yes).isPresent();
				autoShareDecision = answer;
				Klein_Method.this.logger.log("Klein_Method: Laufbericht automatisch teilen - Anwender: " + (answer ? "Ja" : "Nein") + " (gilt bis zum Neustart von JAG3D).");
				Klein_Method.this.protocol.logEvent("SCHWARMWISSEN: automatisch teilen = " + (answer ? "ja" : "nein") + " (bis Neustart von JAG3D)");
				if (answer)
					this.shareRunReportSilently(file);
			});
		}
		/** Bericht still senden (Hintergrund): mit Token direkt, sonst ueber den Vermittlungsdienst; Ergebnis nur in Log und Protokoll. */
		private void shareRunReportSilently(java.io.File file) {
			String repository = Klein_Method.this.parameter.getSwarmRepository();
			String relayUrl = Klein_Method.this.parameter.getSwarmRelayUrl();
			Thread thread = new Thread(() -> {
				String message;
				try {
					Map<String, String> report = Klein_Method_Functions.Klein_Method_Swarm.readReport(file);
					String route = Klein_Method_Functions.Klein_Method_Swarm.sendRoute(relayUrl);
					String url = Klein_Method_Functions.Klein_Method_Swarm.sendReport(repository, relayUrl, Klein_Method_Functions.Klein_Method_Swarm.issueTitle(report),
							Klein_Method_Functions.Klein_Method_Swarm.issueBody(report));
					message = "Laufbericht automatisch gesendet (" + route + "): " + url;
				} catch (Exception e) {
					message = "Laufbericht automatisch senden fehlgeschlagen: " + e.getMessage();
				}
				Klein_Method.this.logger.log("Klein_Method: Schwarmwissen - " + message);
				Klein_Method.this.protocol.logEvent("SCHWARMWISSEN: " + message);
			}, "Klein_Method_Swarm_Auto_Share");
			thread.setDaemon(true);
			thread.start();
		}
		/** Anonyme Kennung des offenen Projekts (DB offen). */
		public String currentSwarmProjectId() {
			return Klein_Method_Functions.Klein_Method_Swarm.projectId(Klein_Method.this.databaseHandler.getProjectLocation());
		}
		/**
		 * Aktuelle Gruppenunsicherheiten je Typ [Arbeitseinheit], Median ueber die Gruppen des Typs;
		 * 0 = Komponente deaktiviert. Nur Typen mit aktiven Gruppen. DB offen, Sperre beim Aufrufer.
		 */
		private Map<Klein_Method_Threshold_Type, double[]> readCurrentGroupSigmas() throws SQLException {
			Map<Klein_Method_Threshold_Type, double[]> result = new LinkedHashMap<Klein_Method_Threshold_Type, double[]>();
			for (Klein_Method_Threshold_Type type : SWARM_TYPES) {
				List<Integer> groupIds = Klein_Method.this.databaseHandler.readGroupIds(type);
				if (groupIds.isEmpty())
					continue;
				double[] values = new double[3];
				for (int k = 0; k < 3; k++) {
					List<Double> list = new ArrayList<Double>();
					for (Integer groupId : groupIds)
						list.add(Klein_Method.this.functions.toWorkingUnit(Klein_Method.this.databaseHandler.readGroupUncertainty(groupId)[k], swarmUnit(type, k)));
					values[k] = Klein_Method_Functions.Klein_Method_Swarm.median(list);
				}
				result.put(type, values);
			}
			return result;
		}
		/** Erfahrungswerte: eigene Berichte + zuletzt abgerufene Schwarmdatei. */
		private Map<String, Map<String, Double>> swarmExperience() {
			List<Map<String, String>> reports = new ArrayList<Map<String, String>>();
			for (java.io.File file : Klein_Method_Functions.Klein_Method_Swarm.reportFiles())
				reports.add(Klein_Method_Functions.Klein_Method_Swarm.readReport(file));
			return Klein_Method_Functions.Klein_Method_Swarm.merge(Klein_Method_Functions.Klein_Method_Swarm.collect(reports),
					Klein_Method_Functions.Klein_Method_Swarm.readSwarmCache());
		}
		/** Laufbericht zusammenstellen (keine Koordinaten, keine Namen). DB offen, Sperre beim Aufrufer. */
		private Map<String, String> buildRunReport(Klein_Method_Adjustment_Step step, String endReason, boolean done, double runtimeSeconds,
				boolean discarded) throws SQLException {
			Map<String, String> report = new LinkedHashMap<String, String>();
			if (discarded)
				report.put("verworfen", "true");
			Klein_Method_Parameter p = Klein_Method.this.parameter;
			report.put("format", "1");
			report.put("version", Klein_Method_Default_Values.PACKAGE_VERSION);
			report.put("zeit", LocalDateTime.now().withNano(0).toString());
			report.put("stufe", step == null ? "UNDEFINED" : step.name());
			report.put("ende", endReason);
			report.put("done", String.valueOf(done));
			report.put("projekt", this.currentSwarmProjectId());
			report.put("geraeteklasse", p.getDeviceClass().name());
			List<Klein_Method_Point_Coordinate_Row> points = Klein_Method.this.databaseHandler.readAllPointAprioriCoordinatesForFangkreisScan();
			List<double[]> planar = new ArrayList<double[]>();
			for (Klein_Method_Point_Coordinate_Row row : points)
				if (row.getRechtswert() != null && row.getHochwert() != null)
					planar.add(new double[] { row.getRechtswert(), row.getHochwert() });
			report.put("netz.punkte", String.valueOf(points.size()));
			report.put("netz.ausdehnung_km", planar.isEmpty() ? "0"
					: String.valueOf(Math.round(Klein_Method_Functions.Klein_Method_Geo.maxExtent(planar.toArray(new double[0][])) / 1000.0)));
			for (Map.Entry<ObservationType, Integer> entry : Klein_Method.this.databaseHandler.readActiveObservationCounts().entrySet())
				report.put("beobachtungen." + entry.getKey().name(), String.valueOf(entry.getValue()));
			report.put("einst.logpost_grenzwert", String.valueOf(p.getThresholdValue(SWARM_TYPES[0])));
			report.put("einst.vks_band", p.getVksBandMin() + "-" + p.getVksBandMax());
			report.put("einst.grenze_grober_fehler_m", String.valueOf(p.getGrossErrorLinearLimit()));
			report.put("einst.iterationsgrenze", String.valueOf(p.getIterationThreshold()));
			report.put("einst.verteilung", p.getUserDistributionType().name());
			report.put("einst.daempfungsformel", p.getDampingFormulaType().name());
			report.put("einst.kaskade", String.valueOf(p.isCascadeDoubleRunEnabled()));
			report.put("einst.untergrenze_erzwingen", String.valueOf(p.isFloorEnforcementEnabled()));
			report.put("einst.gruppen_sigma_nach_dl1", String.valueOf(p.isGroupFloorAfterPass1Enabled()));
			report.put("einst.gruppen_daempfung_phase2", String.valueOf(p.isGroupDampingInPhase2Enabled()));
			report.put("verlauf.iterationen", String.valueOf(p.getIterationCount()));
			report.put("verlauf.zyklen", String.valueOf(p.getInternalCycleCount()));
			report.put("verlauf.kaskaden_durchlauf", String.valueOf(p.getCascadePassCount() + 1));
			report.put("verlauf.rechenzeit_s", String.valueOf(Math.round(runtimeSeconds)));
			report.put("ergebnis.max_logpost", String.valueOf(Klein_Method.this.functions.getMaxLogPostValue(p)));
			report.put("ergebnis.max_ep", String.valueOf(Klein_Method.this.functions.getMaxEpValue(p)));
			report.put("nachlauf", p.getFollowUpOutcome());
			int excluded = 0, accepted = 0;
			for (Max_Error_Row entry : Klein_Method.this.databaseHandler.readGrossErrorEntries()) {
				if (Klein_Method_Database_Handler_Interface.GROSS_ERROR_EXCLUDED.equals(entry.status))
					excluded++;
				else
					accepted++;
			}
			report.put("ergebnis.grobe_fehler_heraus", String.valueOf(excluded));
			report.put("ergebnis.grobe_fehler_verworfen", String.valueOf(accepted));
			if (step == Klein_Method_Adjustment_Step.DYNAMIC) {
				// dynamische AGL - Punktwerte statt der (unveraenderten) Beobachtungsgruppen
				report.put("festpunktart", Klein_Method.this.swarmFixedPointClass.name());
				Map<Integer, List<Object[]>> stochasticPoints = this.readStochasticPointSigmas();
				int strongly = 0;
				for (Map.Entry<Integer, List<Object[]>> entry : stochasticPoints.entrySet()) {
					int dimension = entry.getKey();
					List<Double> values = new ArrayList<Double>();
					for (Object[] point : entry.getValue())
						values.add((Double) point[1]);
					double median = Klein_Method_Functions.Klein_Method_Swarm.median(values);
					java.util.Collections.sort(values);
					double p90 = values.get(Math.min(values.size() - 1, (int) Math.ceil(0.9 * values.size()) - 1));
					for (Double value : values)
						if (value > 3.0 * median)
							strongly++;
					report.put("sigma." + Klein_Method_Functions.Klein_Method_Swarm.pointMedianType(dimension) + ".s", String.valueOf(median));
					report.put("punkte." + dimension + "D.anzahl", String.valueOf(values.size()));
					report.put("punkte." + dimension + "D.p90", String.valueOf(p90));
					Double phase1 = Klein_Method.this.dynamicPhase1Sigma.get(dimension);
					if (phase1 != null)
						report.put("sigma." + Klein_Method_Functions.Klein_Method_Swarm.pointGroupType(dimension) + ".s", String.valueOf(phase1));
				}
				report.put("punkte.stark_vergroessert", String.valueOf(strongly));
				// Kennzahlen der Festpunkt-Empfehlung (nur Laengen/Anzahlen, keine Koordinaten/Namen)
				try {
					Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result fpe = Klein_Method.this.computeFixedPointRecommendation();
					report.put("fpe.empfohlen", String.valueOf(fpe.recommended));
					report.put("fpe.gruen", String.valueOf(fpe.green));
					report.put("fpe.gelb", String.valueOf(fpe.yellow));
					report.put("fpe.rot", String.valueOf(fpe.red));
					report.put("fpe.gruende", String.valueOf(fpe.reasons.size()));
					if (fpe.geometryAvailable) {
						report.put("fpe.netzform", fpe.elongated ? "langgestreckt" : "flaechig");
						report.put("fpe.verhaeltnis", String.valueOf(Math.round(fpe.ratio * 10.0) / 10.0));
						if (fpe.elongated) {
							report.put("fpe.laenge_m", String.valueOf(Math.round(fpe.lengthM)));
							report.put("fpe.abstand_altpunkte_m", String.valueOf(Math.round(fpe.typicalSpacingM)));
							report.put("fpe.max_luecke_m", String.valueOf(Math.round(fpe.maxGapM)));
							report.put("fpe.max_luecke_automatisch", String.valueOf(fpe.maxGapAuto));
							report.put("fpe.groesste_luecke_m", String.valueOf(Math.round(fpe.largestGapM)));
							report.put("fpe.enden", fpe.endCountA + "/" + fpe.endCountB);
						}
					}
					report.put("fpe.grenzen_mm", Klein_Method.this.recommendationSettings.tachyGreen + "/" + Klein_Method.this.recommendationSettings.tachyYellow + "/"
							+ Klein_Method.this.recommendationSettings.levelGreen + "/" + Klein_Method.this.recommendationSettings.levelYellow);
					report.put("fpe.grenzen_mitte_mm", Klein_Method.this.recommendationSettings.midPositionGreen + "/" + Klein_Method.this.recommendationSettings.midPositionYellow + "/"
							+ Klein_Method.this.recommendationSettings.midHeightGreen + "/" + Klein_Method.this.recommendationSettings.midHeightYellow);
					report.put("fpe.rand_mitte", fpe.edgePoints + "/" + fpe.middlePoints);
					report.put("fpe.uebernommen", String.valueOf("true".equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_FPE_APPLIED))));
				} catch (Exception e) {
					report.put("fpe.fehler", "true");
				}
				report.put("einheit.punkte", "mm");
				report.put("einst.turbo", String.valueOf(p.isTurboEnabled()));
				return report;
			}
			// mit "Gruppen-sigma nach DL1 = Untergrenze" stehen am Laufende nur die Untergrenzen in den Gruppen - der
			// Schwarm lernte so nur die Untergrenzen.
			boolean fromPass1 = !Klein_Method.this.pass1GroupSigma.isEmpty();
			report.put("gruppen_sigma_quelle", fromPass1 ? "ende_durchlauf1" : "laufende");
			// der Schwarm zaehlt nur Laeufe, die den logPost-Grenzwert erreicht haben (Schluessel fehlt in Berichten vor V0.432)
			report.put("grenzwert_erreicht", String.valueOf(Klein_Method.this.functions.isLogPostBelowThreshold(p)));
			Map<Klein_Method_Threshold_Type, double[]> sigmas = fromPass1 ? Klein_Method.this.pass1GroupSigma : this.readCurrentGroupSigmas();
			for (Map.Entry<Klein_Method_Threshold_Type, double[]> entry : sigmas.entrySet()) {
				Klein_Method_Threshold_Type type = entry.getKey();
				double[] ratios = fromPass1 ? Klein_Method.this.pass1GroupRatios.get(type) : Klein_Method.this.databaseHandler.readGroupVarianceRatios(type);
				if (ratios == null)
					ratios = new double[] { Double.NaN, Double.NaN, Double.NaN };
				for (int k = 0; k < 3; k++) {
					report.put("sigma." + type.name() + "." + SWARM_COMPONENTS[k], String.valueOf(entry.getValue()[k]));
					report.put("einheit." + type.name() + "." + SWARM_COMPONENTS[k], swarmUnit(type, k));
					report.put("vks." + type.name() + "." + SWARM_COMPONENTS[k], String.valueOf(ratios[k]));
				}
			}
			return report;
		}
		/** Laufbericht am Ende eines Automatik-Laufs schreiben (Aufruf aus Klein_Method_Iteration_Task, ohne Sperre). */
		public void writeRunReport(Klein_Method_Adjustment_Step step, String endReason, boolean done, double runtimeSeconds, boolean discarded) {
			ADJUSTMENT_LOCK.lock();
			try {
				if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
					return;
				java.io.File file = Klein_Method_Functions.Klein_Method_Swarm.writeReport(this.buildRunReport(step, endReason, done, runtimeSeconds, discarded));
				if (done && !discarded)
					this.autoShareAfterRun(file);
				Klein_Method.this.protocol.logEvent("LAUFBERICHT (Schwarmwissen) gespeichert: " + file.getName() + (!done ? " - ohne DONE, z\u00E4hlt nicht als Erfahrungswert"
						: discarded ? " - vom Anwender verworfen, z\u00E4hlt nicht als Erfahrungswert" : ""));
				Klein_Method.this.logger.log("Klein_Method: Laufbericht gespeichert: " + file.getAbsolutePath());
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Laufbericht nicht speicherbar: " + e.getMessage());
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			Platform.runLater(() -> {
				if (Klein_Method.this.tabBuilder != null)
					Klein_Method.this.tabBuilder.refreshSwarmView();
			});
		}
		/** Uebersicht fuer den Bereich "Schwarmwissen" (Hintergrund-Thread, ohne DB-Sperre ausser Projektkennung). */
		public String buildSwarmOverviewText(boolean dynamic) {
			String project = "unbekannt";
			try {
				project = this.currentSwarmProjectId();
			} catch (Exception e) {
				// ohne Projekt
			}
			Map<String, Map<String, Double>> local = new java.util.TreeMap<String, Map<String, Double>>();
			List<Map<String, String>> reports = new ArrayList<Map<String, String>>();
			for (java.io.File file : Klein_Method_Functions.Klein_Method_Swarm.reportFiles())
				reports.add(Klein_Method_Functions.Klein_Method_Swarm.readReport(file));
			local = Klein_Method_Functions.Klein_Method_Swarm.collect(reports);
			Map<String, Map<String, Double>> swarm = Klein_Method_Functions.Klein_Method_Swarm.readSwarmCache();
			Map<String, Map<String, Double>> merged = Klein_Method_Functions.Klein_Method_Swarm.merge(local, swarm);
			StringBuilder text = new StringBuilder();
			text.append(reports.size()).append(" eigene Laufberichte, ").append(countProjects(local)).append(" Projekt(e) mit DONE lokal, ")
					.append(countProjects(swarm)).append(" Projekt(e) aus dem Schwarm (zuletzt abgerufen).\n");
			merged.keySet().removeIf(key -> Klein_Method_Functions.Klein_Method_Swarm.isPointType(key.split("\\|")[0]) != dynamic);
			if (merged.isEmpty())
				return text.append("Noch keine Erfahrungswerte f\u00FCr die " + (dynamic ? "dynamische" : "freie/fixe")
						+ " Ausgleichung - sie entstehen nach Automatik-L\u00E4ufen mit DONE.").toString();
			if (dynamic) {
				text.append(String.format("%-30s %-16s %12s %8s %s%n", "Punktwert", "Festpunktart", "Median", "Projekte", "Startwert (10-%-Regel, andere Projekte)"));
				for (Map.Entry<String, Map<String, Double>> entry : merged.entrySet()) {
					String[] parts = entry.getKey().split("\\|");
					String label = parts[0].startsWith("PUNKTGRUPPE_") ? "Punktgruppe " + parts[0].substring(12) + " (nach Phase 1)"
							: "Punkte " + parts[0].substring(12) + " (Median am Ende)";
					double median = Klein_Method_Functions.Klein_Method_Swarm.median(new ArrayList<Double>(entry.getValue().values()));
					Klein_Method_Functions.Klein_Method_Swarm.Start_Value start = Klein_Method_Functions.Klein_Method_Swarm.tenPercentRule(entry.getValue(), project);
					text.append(String.format(Locale.GERMANY, "%-30s %-16s %9.3f mm %6d   %s%n", label,
							Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class.parse(parts[1]).getDisplayName(), median, entry.getValue().size(),
							!parts[0].startsWith("PUNKTGRUPPE_") ? "(Vergleichswert f\u00FCr die Warnung nach Phase 2)"
									: Double.isNaN(start.value) ? "\u2013 (" + start.reason + ")"
											: String.format(Locale.GERMANY, "%.3f mm aus %d Projekten", start.value, start.agreeingProjects)));
				}
				return text.toString();
			}
			text.append(String.format("%-28s %-12s %-3s %12s %8s %s%n", "Typ", "Ger\u00E4teklasse", "", "Median", "Projekte", "Startwert (10-%-Regel, andere Projekte)"));
			for (Map.Entry<String, Map<String, Double>> entry : merged.entrySet()) {
				String[] parts = entry.getKey().split("\\|");
				Klein_Method_Threshold_Type type;
				try {
					type = Klein_Method_Threshold_Type.valueOf(parts[0]);
				} catch (IllegalArgumentException e) {
					continue;
				}
				int component = java.util.Arrays.asList(SWARM_COMPONENTS).indexOf(parts[2]);
				String unit = component < 0 ? "" : swarmUnit(type, component);
				double median = Klein_Method_Functions.Klein_Method_Swarm.median(new ArrayList<Double>(entry.getValue().values()));
				Klein_Method_Functions.Klein_Method_Swarm.Start_Value start = Klein_Method_Functions.Klein_Method_Swarm.tenPercentRule(entry.getValue(), project);
				text.append(String.format(Locale.GERMANY, "%-28s %-12s σ%-2s %9.3f %-3s %6d   %s%n", swarmTypeName(type), parts[1], parts[2], median, unit,
						entry.getValue().size(), Double.isNaN(start.value) ? "– (" + start.reason + ")"
								: String.format(Locale.GERMANY, "%.3f %s aus %d Projekten", start.value, unit, start.agreeingProjects)));
			}
			return text.toString();
		}
		/**
		 * Warnung nach Phase 1 (Aufruf aus Klein_Method_Iteration_Task, ohne Sperre): liegt ein Gruppenwert
		 * mehr als Warnfaktor x Erfahrungswert (Median anderer Projekte, mind. 3) darueber, haelt die Automatik
		 * an. @return true = weiterrechnen
		 */
		public boolean checkSwarmPlausibilityAfterPhase1() {
			List<String> findings = new ArrayList<String>();
			double factor = Klein_Method.this.parameter.getSwarmWarnFactor();
			ADJUSTMENT_LOCK.lock();
			try {
				String project = this.currentSwarmProjectId();
				Map<String, Map<String, Double>> experience = this.swarmExperience();
				String deviceClass = Klein_Method.this.parameter.getDeviceClass().name();
				for (Map.Entry<Klein_Method_Threshold_Type, double[]> entry : this.readCurrentGroupSigmas().entrySet())
					for (int k = 0; k < 3; k++) {
						double current = entry.getValue()[k];
						Map<String, Double> byProject = experience.get(Klein_Method_Functions.Klein_Method_Swarm.key(entry.getKey().name(), deviceClass, SWARM_COMPONENTS[k]));
						if (current <= 0.0 || byProject == null)
							continue;
						double reference = Klein_Method_Functions.Klein_Method_Swarm.reference(byProject, project);
						if (!Double.isNaN(reference) && reference > 0.0 && current > factor * reference) {
							String unit = swarmUnit(entry.getKey(), k);
							findings.add(String.format(Locale.GERMANY, "%s σ%s = %.3f %s, üblich %.3f %s (Median aus %d Projekten) - Faktor %.1f",
									swarmTypeName(entry.getKey()), SWARM_COMPONENTS[k], current, unit, reference, unit, byProject.size(), current / reference));
						}
					}
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Schwarmwissen-Pruefung nach Phase 1 nicht moeglich: " + e.getMessage());
				return true;
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			if (findings.isEmpty())
				return true;
			for (String finding : findings)
				Klein_Method.this.protocol.logEvent("WARNUNG SCHWARMWISSEN nach Phase 1: " + finding);
			java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<Boolean>();
			Platform.runLater(() -> {
				boolean proceed = false;
				try {
					Alert alert = new Alert(AlertType.WARNING);
					alert.setTitle(tr("Klein_Method - Schwarmwissen"));
					alert.setHeaderText(tr("Gruppenunsicherheit nach Phase 1 deutlich über der Erfahrung"));
					alert.setContentText(String.join("\n", findings) + tr("\n\nMögliche Ursachen: grobe Fehler (Knopf „Grobe Fehler bereinigen“, Karte "
							+ "„Max Fehler“) oder falsche Geräteklasse. Warnfaktor: ") + String.format(Locale.GERMANY, "%.1f", factor)
							+ tr(" (im Bereich „Schwarmwissen“ einstellbar)."));
					ButtonType continueType = new ButtonType(tr("Weiter"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType abortType = new ButtonType(tr("Abbrechen"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					alert.getButtonTypes().setAll(continueType, abortType);
					alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
					java.util.Optional<ButtonType> answer = alert.showAndWait();
					proceed = answer.isPresent() && answer.get() == continueType;
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Schwarmwissen-Fenster fehlgeschlagen: " + e.getMessage());
					proceed = true;
				}
				future.complete(proceed);
			});
			try {
				boolean proceed = future.get();
				Klein_Method.this.protocol.logEvent("WARNUNG SCHWARMWISSEN: Anwender wählte " + (proceed ? "Weiter" : "Abbrechen"));
				return proceed;
			} catch (Exception e) {
				return true;
			}
		}
		/** Knopf "Erfahrungswerte als Startwerte uebernehmen": Vorschau mit 10-%-Regel, dann schreiben. */
		public void startSwarmStartValues() {
			if (Klein_Method.this.tabBuilder != null && Klein_Method.this.tabBuilder.isDynamicStepVisible()) {
				this.startSwarmPointStartValues();
				return;
			}
			Thread thread = busyThread(() -> {
				List<Object[]> rows = new ArrayList<Object[]>(); // type, component, current, Start_Value, unit
				String failure = null;
				ADJUSTMENT_LOCK.lock();
				try {
					String project = this.currentSwarmProjectId();
					Map<String, Map<String, Double>> experience = this.swarmExperience();
					String deviceClass = Klein_Method.this.parameter.getDeviceClass().name();
					for (Map.Entry<Klein_Method_Threshold_Type, double[]> entry : this.readCurrentGroupSigmas().entrySet())
						for (int k = 0; k < 3; k++) {
							if (entry.getValue()[k] <= 0.0)
								continue; // Komponente deaktiviert - bleibt 0
							Map<String, Double> byProject = experience.getOrDefault(
									Klein_Method_Functions.Klein_Method_Swarm.key(entry.getKey().name(), deviceClass, SWARM_COMPONENTS[k]), new java.util.TreeMap<String, Double>());
							rows.add(new Object[] { entry.getKey(), k, entry.getValue()[k],
									Klein_Method_Functions.Klein_Method_Swarm.tenPercentRule(byProject, project), swarmUnit(entry.getKey(), k) });
						}
				} catch (SQLException e) {
					failure = e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final String finalFailure = failure;
				Platform.runLater(() -> this.showSwarmStartValueDialog(rows, finalFailure));
			}, "Klein_Method_Swarm_Start_Values");
			thread.setDaemon(true);
			thread.start();
		}
		private void showSwarmStartValueDialog(List<Object[]> rows, String failure) {
			if (failure != null) {
				OptionDialog.showErrorDialog(tr("Klein_Method"), tr("Schwarmwissen"), tr("Gruppenwerte nicht lesbar: ") + failure);
				return;
			}
			StringBuilder text = new StringBuilder();
			int usable = 0;
			for (Object[] row : rows) {
				Klein_Method_Threshold_Type type = (Klein_Method_Threshold_Type) row[0];
				int k = (Integer) row[1];
				Klein_Method_Functions.Klein_Method_Swarm.Start_Value start = (Klein_Method_Functions.Klein_Method_Swarm.Start_Value) row[3];
				String unit = (String) row[4];
				text.append(String.format(Locale.GERMANY, "%s σ%s: %.3f %s → ", swarmTypeName(type), SWARM_COMPONENTS[k], (Double) row[2], unit));
				if (Double.isNaN(start.value))
					text.append("bleibt (").append(start.reason).append(")");
				else {
					usable++;
					double floor = k == 0 ? Klein_Method.this.parameter.getGroupUncertaintyFloorA(type) : k == 1 ? Klein_Method.this.parameter.getGroupUncertaintyFloorB(type)
							: Klein_Method.this.parameter.getGroupUncertaintyFloorC(type);
					text.append(String.format(Locale.GERMANY, "%.3f %s aus %d übereinstimmenden Projekten", Math.max(start.value, floor), unit, start.agreeingProjects));
					if (start.value < floor)
						text.append(String.format(Locale.GERMANY, " (auf Untergrenze %.3f angehoben)", floor));
				}
				if (!start.excludedValues.isEmpty()) {
					text.append(" | weggelassen (mehr als 10 % abweichend):");
					for (Double value : start.excludedValues)
						text.append(String.format(Locale.GERMANY, " %.3f", value));
				}
				text.append('\n');
			}
			if (rows.isEmpty())
				text.append("Keine Beobachtungsgruppen mit aktiver Gruppenunsicherheit gefunden.");
			javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Erfahrungswerte als Startwerte"));
			dialog.setHeaderText(usable + tr(" Wert(e) übernehmbar - nur Projekte, die sich untereinander höchstens 10 % unterscheiden (mind. ")
					+ Klein_Method_Functions.Klein_Method_Swarm.MIN_PROJECTS + tr(", andere Projekte als dieses)"));
			javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text.toString());
			area.setEditable(false);
			area.setWrapText(true);
			area.setPrefColumnCount(90);
			area.setPrefRowCount(Math.min(18, Math.max(6, rows.size() + 2)));
			dialog.getDialogPane().setContent(area);
			ButtonType applyType = new ButtonType(tr("Übernehmen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
			dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
			dialog.getDialogPane().lookupButton(applyType).setDisable(usable == 0);
			java.util.Optional<ButtonType> answer = dialog.showAndWait();
			if (!answer.isPresent() || answer.get() != applyType)
				return;
			Thread thread = busyThread(() -> {
				int written = 0;
				ADJUSTMENT_LOCK.lock();
				try {
					Map<Klein_Method_Threshold_Type, double[]> targets = new LinkedHashMap<Klein_Method_Threshold_Type, double[]>();
					for (Object[] row : rows) {
						Klein_Method_Functions.Klein_Method_Swarm.Start_Value start = (Klein_Method_Functions.Klein_Method_Swarm.Start_Value) row[3];
						if (Double.isNaN(start.value))
							continue;
						Klein_Method_Threshold_Type type = (Klein_Method_Threshold_Type) row[0];
						int k = (Integer) row[1];
						double floor = k == 0 ? Klein_Method.this.parameter.getGroupUncertaintyFloorA(type) : k == 1 ? Klein_Method.this.parameter.getGroupUncertaintyFloorB(type)
								: Klein_Method.this.parameter.getGroupUncertaintyFloorC(type);
						targets.computeIfAbsent(type, t -> new double[] { Double.NaN, Double.NaN, Double.NaN })[k] = Math.max(start.value, floor);
					}
					for (Map.Entry<Klein_Method_Threshold_Type, double[]> entry : targets.entrySet()) {
						Klein_Method_Threshold_Type type = entry.getKey();
						for (Integer groupId : Klein_Method.this.databaseHandler.readGroupIds(type)) {
							double[] raw = Klein_Method.this.databaseHandler.readGroupUncertainty(groupId);
							double[] next = raw.clone();
							for (int k = 0; k < 3; k++)
								if (raw[k] != 0.0 && !Double.isNaN(entry.getValue()[k]))
									next[k] = Klein_Method.this.functions.toDatabaseUnit(entry.getValue()[k], swarmUnit(type, k));
							Klein_Method.this.databaseHandler.writeGroupUncertainty(groupId, next[0], next[1], next[2]);
							written++;
						}
						Klein_Method.this.protocol.logEvent(String.format(Locale.GERMANY, "SCHWARMWISSEN Startwerte %s: σa %s | σb %s | σc %s", swarmTypeName(type),
								Double.isNaN(entry.getValue()[0]) ? "unverändert" : String.format(Locale.GERMANY, "%.3f", entry.getValue()[0]),
								Double.isNaN(entry.getValue()[1]) ? "unverändert" : String.format(Locale.GERMANY, "%.3f", entry.getValue()[1]),
								Double.isNaN(entry.getValue()[2]) ? "unverändert" : String.format(Locale.GERMANY, "%.3f", entry.getValue()[2])));
					}
					Klein_Method.this.databaseHandler.commit();
					Klein_Method.this.acceptOwnChangesIntoChecksum("Schwarmwissen-Startwerte");
				} catch (SQLException e) {
					Klein_Method.this.logger.log("Klein_Method: Startwerte aus Schwarmwissen nicht schreibbar: " + e.getMessage());
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final int finalWritten = written;
				Klein_Method.this.logger.log("Klein_Method: Startwerte aus Schwarmwissen in " + written + " Gruppe(n) geschrieben.");
				// a-priori-Werte geaendert -> Ergebnisse neu rechnen (wie beim Tab-Aktivieren)
				Platform.runLater(() -> {
					if (Klein_Method.this.tabBuilder != null)
						Klein_Method.this.tabBuilder.setStatusText("Startwerte aus Erfahrung in " + finalWritten + " Gruppe(n) übernommen - Netz wird neu ausgeglichen.");
					Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
				});
			}, "Klein_Method_Swarm_Write");
			thread.setDaemon(true);
			thread.start();
		}
		/**
		 * Beim Start der Automatik: Erfahrungswerte still abrufen (ohne Meldung). Ergebnis nur in Log und Protokoll;
		 * ohne Verbindung bleibt die zuletzt abgerufene Datei gueltig. Laeuft im Thread der Automatik.
		 */
		public void refreshSwarmExperienceQuietly() {
			String repository = Klein_Method.this.parameter.getSwarmRepository();
			String line;
			try {
				int count = Klein_Method_Functions.Klein_Method_Swarm.downloadSwarmFile(repository);
				line = "SCHWARMWISSEN: " + count + " Erfahrungswert(e) beim Start abgerufen (" + repository + ")";
			} catch (Exception e) {
				line = "SCHWARMWISSEN: Abruf beim Start nicht moeglich (" + e.getMessage() + ") - es gilt der zuletzt abgerufene Stand";
			}
			Klein_Method.this.logger.log("Klein_Method: " + line);
			Klein_Method.this.protocol.logEvent(line);
			Platform.runLater(() -> {
				if (Klein_Method.this.tabBuilder != null)
					Klein_Method.this.tabBuilder.refreshSwarmView();
			});
		}
		/** Knopf "Erfahrungswerte abrufen": Schwarmdatei aus dem Repository laden (ohne Schluessel). */
		public void downloadSwarmExperience() {
			String repository = Klein_Method.this.parameter.getSwarmRepository();
			Thread thread = busyThread(() -> {
				String message;
				boolean ok;
				try {
					int count = Klein_Method_Functions.Klein_Method_Swarm.downloadSwarmFile(repository);
					message = count + " Erfahrungswert(e) aus " + repository + " abgerufen.";
					ok = true;
				} catch (Exception e) {
					message = "Abruf aus " + repository + " fehlgeschlagen: " + e.getMessage();
					ok = false;
				}
				Klein_Method.this.logger.log("Klein_Method: Schwarmwissen - " + message);
				final String finalMessage = message;
				final boolean finalOk = ok;
				Platform.runLater(() -> {
					Alert alert = new Alert(finalOk ? AlertType.INFORMATION : AlertType.WARNING);
					alert.setTitle(tr("Klein_Method - Schwarmwissen"));
					alert.setHeaderText(finalOk ? tr("Erfahrungswerte abgerufen") : tr("Abruf nicht möglich"));
					alert.setContentText(finalMessage + (finalOk ? "" : tr("\n\nInternetverbindung und Repository-Name prüfen (Datei ") 
							+ Klein_Method_Functions.Klein_Method_Swarm.SWARM_FILE + tr(" im Zweig main).")));
					alert.showAndWait();
					if (Klein_Method.this.tabBuilder != null)
						Klein_Method.this.tabBuilder.refreshSwarmView();
				});
			}, "Klein_Method_Swarm_Download");
			thread.setDaemon(true);
			thread.start();
		}
		/** Knopf "Laufbericht teilen": neuesten Bericht in der Vorschau zeigen, nach "Senden" als Issue anlegen. */
		public void shareLatestRunReport() {
			List<java.io.File> files = Klein_Method_Functions.Klein_Method_Swarm.reportFiles();
			if (files.isEmpty()) {
				OptionDialog.showErrorDialog(tr("Klein_Method"), tr("Laufbericht teilen"), tr("Noch kein Laufbericht vorhanden."));
				return;
			}
			String relayUrl = Klein_Method.this.parameter.getSwarmRelayUrl();
			java.io.File file = files.get(0);
			Map<String, String> report = Klein_Method_Functions.Klein_Method_Swarm.readReport(file);
			String repository = Klein_Method.this.parameter.getSwarmRepository();
			String title = Klein_Method_Functions.Klein_Method_Swarm.issueTitle(report);
			String body = Klein_Method_Functions.Klein_Method_Swarm.issueBody(report);
			javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Laufbericht teilen"));
			dialog.setHeaderText(tr("Vorschau: genau dieser Text wird als öffentliches Issue an ") + repository + tr(" gesendet.\n"
					+ "Keine Koordinaten, keine Punkt- oder Projektnamen. Datei: ") + file.getName()
					+ (tr("true").equals(report.get("done")) ? "" : tr("\nHinweis: Lauf ohne DONE - wird im Schwarm nicht als Erfahrungswert gezählt.")));
			javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(title + "\n\n" + body);
			area.setEditable(false);
			area.setPrefColumnCount(80);
			area.setPrefRowCount(20);
			dialog.getDialogPane().setContent(area);
			ButtonType sendType = new ButtonType(tr("Senden"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
			dialog.getDialogPane().getButtonTypes().addAll(sendType, ButtonType.CANCEL);
			java.util.Optional<ButtonType> answer = dialog.showAndWait();
			if (!answer.isPresent() || answer.get() != sendType)
				return;
			Thread thread = busyThread(() -> {
				String message;
				boolean ok;
				String route = Klein_Method_Functions.Klein_Method_Swarm.sendRoute(relayUrl);
				try {
					String url = Klein_Method_Functions.Klein_Method_Swarm.sendReport(repository, relayUrl, title, body);
					message = "Laufbericht gesendet (" + route + "): " + url;
					ok = true;
				} catch (Exception e) {
					message = "Senden fehlgeschlagen: " + e.getMessage();
					ok = false;
				}
				Klein_Method.this.logger.log("Klein_Method: Schwarmwissen - " + message);
				Klein_Method.this.protocol.logEvent("SCHWARMWISSEN: " + message);
				final String finalMessage = message;
				final boolean finalOk = ok;
				Platform.runLater(() -> {
					if (finalOk) {
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Laufbericht teilen"));
						alert.setHeaderText(tr("Gesendet - danke!"));
						alert.setContentText(finalMessage);
						alert.showAndWait();
						return;
					}
					ButtonType browserType = new ButtonType(tr("Im Browser senden"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					Alert alert = new Alert(AlertType.WARNING, "", browserType, ButtonType.CANCEL);
					alert.setTitle(tr("Klein_Method - Laufbericht teilen"));
					alert.setHeaderText(tr("Nicht gesendet"));
					alert.setContentText(finalMessage + "\n\n" + tr("Stattdessen im Browser senden? GitHub öffnet ein ausgefülltes Formular; "
							+ "zum Absenden ist ein kostenloses GitHub-Konto nötig. Es wird genau der Text aus der Vorschau gesendet."));
					if (alert.showAndWait().filter(button -> button == browserType).isPresent())
						this.shareInBrowser(repository, title, body);
				});
			}, "Klein_Method_Swarm_Share");
			thread.setDaemon(true);
			thread.start();
		}
		/** Neues-Issue-Formular im Browser oeffnen; zu langer Bericht -> Zwischenablage und leeres Formular mit Titel. */
		private void shareInBrowser(String repository, String title, String body) {
			String url = Klein_Method_Functions.Klein_Method_Swarm.browserIssueUrl(repository, title, body, true);
			boolean viaClipboard = url == null;
			if (viaClipboard) {
				url = Klein_Method_Functions.Klein_Method_Swarm.browserIssueUrl(repository, title, body, false);
				javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
				content.putString(body);
				javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
			}
			final String finalUrl = url;
			Klein_Method.this.logger.log("Klein_Method: Schwarmwissen - Laufbericht im Browser senden" + (viaClipboard ? " (Text in der Zwischenablage)" : "") + ".");
			Klein_Method.this.protocol.logEvent("SCHWARMWISSEN: Laufbericht im Browser senden" + (viaClipboard ? " (Text in der Zwischenablage)" : ""));
			Thread thread = new Thread(() -> {
				boolean opened = false;
				try {
					if (finalUrl != null && java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
						java.awt.Desktop.getDesktop().browse(new java.net.URI(finalUrl));
						opened = true;
					}
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Browser nicht oeffenbar: " + e.getMessage());
				}
				final boolean finalOpened = opened;
				Platform.runLater(() -> {
					if (finalOpened && !viaClipboard)
						return;
					Alert info = new Alert(AlertType.INFORMATION);
					info.setTitle(tr("Klein_Method - Laufbericht teilen"));
					info.setHeaderText(tr("Im Browser senden"));
					String text = (viaClipboard ? tr("Der Bericht ist für die Adresszeile zu lang und liegt jetzt in der Zwischenablage. "
							+ "Bitte im geöffneten Formular in das Textfeld einfügen und absenden.") + "\n\n" : "")
							+ (finalOpened ? "" : tr("Der Browser ließ sich nicht öffnen. Bitte diese Adresse im Browser aufrufen:") + "\n" + finalUrl);
					javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(6);
					info.getDialogPane().setContent(area);
					info.showAndWait();
				});
			}, "Klein_Method_Swarm_Browser");
			thread.setDaemon(true);
			thread.start();
		}
		public Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class getSwarmFixedPointClass() {
			return Klein_Method.this.swarmFixedPointClass;
		}
		/** Festpunktart setzen und im Projekt speichern. */
		public void setSwarmFixedPointClass(Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class value) {
			if (value == null || value == Klein_Method.this.swarmFixedPointClass)
				return;
			Klein_Method.this.swarmFixedPointClass = value;
			Thread thread = busyThread(() -> {
				ADJUSTMENT_LOCK.lock();
				try {
					Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_FIXED_POINT_CLASS, value.name());
					Klein_Method.this.logger.log("Klein_Method: Festpunktart auf " + value.getDisplayName() + " gesetzt.");
				} catch (SQLException e) {
					Klein_Method.this.logger.log("Klein_Method: Festpunktart nicht speicherbar: " + e.getMessage());
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				Platform.runLater(() -> {
					if (Klein_Method.this.tabBuilder != null)
						Klein_Method.this.tabBuilder.refreshSwarmView();
				});
			}, "Klein_Method_Fixed_Point_Class");
			thread.setDaemon(true);
			thread.start();
		}
		/** Festpunktart des Projekts lesen (Hintergrund-Thread, Sperre beim Aufrufer). */
		private void loadSwarmFixedPointClass() {
			try {
				Klein_Method.this.swarmFixedPointClass = Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class.parse(
						Klein_Method.this.databaseHandler.readProjectSetting(SETTING_FIXED_POINT_CLASS));
			} catch (SQLException e) {
				Klein_Method.this.swarmFixedPointClass = Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class.SONSTIGE;
			}
		}
		/**
		 * Punktunsicherheiten der beweglichen Anschlusspunkte je Dimension: {Name, sigma [mm], Punkt-ID}.
		 * Je Punkt ein Wert (1D: z, sonst y - einheitlich je Punkt, siehe Sicherheitscheck). DB offen.
		 */
		private Map<Integer, List<Object[]>> readStochasticPointSigmas() throws SQLException {
			Map<Integer, List<Object[]>> result = new java.util.TreeMap<Integer, List<Object[]>>();
			for (org.applied_geodesy.klein_method.database.Klein_Method_Point_Tprio_Row row : Klein_Method.this.databaseHandler.readStochasticPointTprioValues()) {
				Double raw = row.getDimension() == 1 ? row.getSigmaZ0() : row.getSigmaY0();
				if (raw == null || !(raw > 0.0))
					continue;
				result.computeIfAbsent(row.getDimension(), d -> new ArrayList<Object[]>())
						.add(new Object[] { row.getName(), raw * 1000.0, row.getPointId() });
			}
			return result;
		}
		/** Nach Phase 1 der dynamischen AGL: Gruppenwert je Dimension merken (Aufruf aus dem Lauf, ohne Sperre). */
		public void recordDynamicPhase1Values() {
			ADJUSTMENT_LOCK.lock();
			try {
				Klein_Method.this.dynamicPhase1Sigma.clear();
				for (Map.Entry<Integer, List<Object[]>> entry : this.readStochasticPointSigmas().entrySet()) {
					List<Double> values = new ArrayList<Double>();
					for (Object[] point : entry.getValue())
						values.add((Double) point[1]);
					Klein_Method.this.dynamicPhase1Sigma.put(entry.getKey(), Klein_Method_Functions.Klein_Method_Swarm.median(values));
				}
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Punktwerte nach Phase 1 nicht lesbar: " + e.getMessage());
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
		}
		/** Beim Start eines neuen Laufs: gemerkte Phase-1-Werte verwerfen. */
		public void clearDynamicPhase1Values() {
			Klein_Method.this.dynamicPhase1Sigma.clear();
			Klein_Method.this.pass1GroupSigma.clear();
			Klein_Method.this.pass1GroupRatios.clear();
		}
		/**
		 * unmittelbar bevor die Gruppenunsicherheiten nach Durchlauf 1 auf die Untergrenze gesetzt werden, die gelernten
		 * Gruppenwerte und VKS fuer den Laufbericht (Schwarmwissen) merken. Aufruf aus dem Lauf.
		 */
		public void recordPass1GroupValues() {
			ADJUSTMENT_LOCK.lock();
			try {
				Klein_Method.this.pass1GroupSigma.clear();
				Klein_Method.this.pass1GroupRatios.clear();
				Map<Klein_Method_Threshold_Type, double[]> sigmas = this.readCurrentGroupSigmas();
				for (Map.Entry<Klein_Method_Threshold_Type, double[]> entry : sigmas.entrySet())
					Klein_Method.this.pass1GroupRatios.put(entry.getKey(), Klein_Method.this.databaseHandler.readGroupVarianceRatios(entry.getKey()));
				Klein_Method.this.pass1GroupSigma.putAll(sigmas);
				StringBuilder text = new StringBuilder();
				for (Map.Entry<Klein_Method_Threshold_Type, double[]> entry : sigmas.entrySet())
					text.append(text.length() == 0 ? "" : "; ").append(entry.getKey().name()).append(' ')
							.append(String.format(Locale.GERMANY, "%.4f/%.4f/%.4f", entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]));
				Klein_Method.this.logger.log("Klein_Method: Gruppenwerte am Ende von Durchlauf 1 fuer den Laufbericht gemerkt (a/b/c): " + text);
			} catch (SQLException e) {
				Klein_Method.this.pass1GroupSigma.clear();
				Klein_Method.this.pass1GroupRatios.clear();
				Klein_Method.this.logger.log("Klein_Method: Gruppenwerte nach Durchlauf 1 nicht lesbar - Laufbericht nutzt die Werte am Laufende: " + e.getMessage());
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
		}
		/**
		 * Warnung nach Phase 2 der dynamischen AGL: Festpunkte, deren Unsicherheit mehr als Warnfaktor x
		 * uebliche Punktunsicherheit (Median anderer Projekte gleicher Festpunktart/Dimension, mind. 3) betraegt.
		 * @return true = Ergebnis zaehlt als Erfahrung, false = vom Anwender verworfen
		 */
		public boolean checkSwarmPointsAfterPhase2() {
			List<String> findings = new ArrayList<String>();
			double factor = Klein_Method.this.parameter.getSwarmWarnFactor();
			ADJUSTMENT_LOCK.lock();
			try {
				String project = this.currentSwarmProjectId();
				Map<String, Map<String, Double>> experience = this.swarmExperience();
				for (Map.Entry<Integer, List<Object[]>> entry : this.readStochasticPointSigmas().entrySet()) {
					Map<String, Double> byProject = experience.get(Klein_Method_Functions.Klein_Method_Swarm.key(
							Klein_Method_Functions.Klein_Method_Swarm.pointMedianType(entry.getKey()), Klein_Method.this.swarmFixedPointClass.name(), "s"));
					if (byProject == null)
						continue;
					double reference = Klein_Method_Functions.Klein_Method_Swarm.reference(byProject, project);
					if (Double.isNaN(reference) || !(reference > 0.0))
						continue;
					for (Object[] point : entry.getValue()) {
						double value = (Double) point[1];
						if (value > factor * reference)
							findings.add(String.format(Locale.GERMANY, "%s (%dD) σ = %.1f mm, üblich %.1f mm (%s, Median aus %d Projekten) - Faktor %.1f",
									point[0], entry.getKey(), value, reference, Klein_Method.this.swarmFixedPointClass.getDisplayName(), byProject.size(), value / reference));
					}
				}
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Schwarmwissen-Pruefung nach Phase 2 nicht moeglich: " + e.getMessage());
				return true;
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			if (findings.isEmpty())
				return true;
			for (String finding : findings)
				Klein_Method.this.protocol.logEvent("WARNUNG SCHWARMWISSEN nach Phase 2: " + finding);
			java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<Boolean>();
			Platform.runLater(() -> {
				boolean keep = true;
				try {
					Alert alert = new Alert(AlertType.WARNING);
					alert.setTitle(tr("Klein_Method - Schwarmwissen"));
					alert.setHeaderText(tr("Festpunkte deutlich über der üblichen Punktunsicherheit"));
					alert.setContentText(String.join("\n", findings) + tr("\n\nPunkt bewegt oder Koordinate falsch? Bitte die Punkte in JAG3D prüfen.\n"
							+ "Weiter = Ergebnis zählt als Erfahrungswert. Abbrechen = dieser Lauf zählt nicht als Erfahrung "
							+ "(die berechneten Werte bleiben im Projekt)."));
					ButtonType continueType = new ButtonType(tr("Weiter"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType abortType = new ButtonType(tr("Abbrechen"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					alert.getButtonTypes().setAll(continueType, abortType);
					alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
					java.util.Optional<ButtonType> answer = alert.showAndWait();
					keep = !answer.isPresent() || answer.get() == continueType;
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Schwarmwissen-Fenster fehlgeschlagen: " + e.getMessage());
				}
				future.complete(keep);
			});
			try {
				boolean keep = future.get();
				Klein_Method.this.protocol.logEvent("WARNUNG SCHWARMWISSEN nach Phase 2: Anwender wählte " + (keep ? "Weiter" : "Abbrechen (nicht als Erfahrung)"));
				return keep;
			} catch (Exception e) {
				return true;
			}
		}
		/** Startwerte fuer Phase 1 der dynamischen AGL (Punktgruppen je Dimension), 10-%-Regel. */
		private void startSwarmPointStartValues() {
			Thread thread = busyThread(() -> {
				List<Object[]> rows = new ArrayList<Object[]>(); // dimension, current median, Start_Value, points
				String failure = null;
				ADJUSTMENT_LOCK.lock();
				try {
					String project = this.currentSwarmProjectId();
					Map<String, Map<String, Double>> experience = this.swarmExperience();
					for (Map.Entry<Integer, List<Object[]>> entry : this.readStochasticPointSigmas().entrySet()) {
						List<Double> values = new ArrayList<Double>();
						for (Object[] point : entry.getValue())
							values.add((Double) point[1]);
						Map<String, Double> byProject = experience.getOrDefault(Klein_Method_Functions.Klein_Method_Swarm.key(
								Klein_Method_Functions.Klein_Method_Swarm.pointGroupType(entry.getKey()), Klein_Method.this.swarmFixedPointClass.name(), "s"),
								new java.util.TreeMap<String, Double>());
						rows.add(new Object[] { entry.getKey(), Klein_Method_Functions.Klein_Method_Swarm.median(values),
								Klein_Method_Functions.Klein_Method_Swarm.tenPercentRule(byProject, project), entry.getValue() });
					}
				} catch (SQLException e) {
					failure = e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final String finalFailure = failure;
				Platform.runLater(() -> this.showSwarmPointStartValueDialog(rows, finalFailure));
			}, "Klein_Method_Swarm_Point_Start_Values");
			thread.setDaemon(true);
			thread.start();
		}
		@SuppressWarnings("unchecked")
		private void showSwarmPointStartValueDialog(List<Object[]> rows, String failure) {
			if (failure != null) {
				OptionDialog.showErrorDialog(tr("Klein_Method"), tr("Schwarmwissen"), tr("Punktwerte nicht lesbar: ") + failure);
				return;
			}
			StringBuilder text = new StringBuilder("Festpunktart: " + Klein_Method.this.swarmFixedPointClass.getDisplayName() + "\n\n");
			int usable = 0;
			for (Object[] row : rows) {
				Klein_Method_Functions.Klein_Method_Swarm.Start_Value start = (Klein_Method_Functions.Klein_Method_Swarm.Start_Value) row[2];
				text.append(String.format(Locale.GERMANY, "Bewegliche Anschlusspunkte %dD (%d Punkte): %.3f mm → ", (Integer) row[0],
						((List<Object[]>) row[3]).size(), (Double) row[1]));
				if (Double.isNaN(start.value))
					text.append("bleibt (").append(start.reason).append(")");
				else {
					usable++;
					text.append(String.format(Locale.GERMANY, "%.3f mm aus %d übereinstimmenden Projekten (für alle Punkte dieser Dimension)",
							Math.max(0.01, Math.min(999.99, start.value)), start.agreeingProjects));
				}
				if (!start.excludedValues.isEmpty()) {
					text.append(" | weggelassen (mehr als 10 % abweichend):");
					for (Double value : start.excludedValues)
						text.append(String.format(Locale.GERMANY, " %.3f", value));
				}
				text.append('\n');
			}
			if (rows.isEmpty())
				text.append("Keine beweglichen Anschlusspunkte mit Unsicherheit gefunden.");
			javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Erfahrungswerte als Startwerte (dynamische AGL)"));
			dialog.setHeaderText(usable + tr(" Wert(e) übernehmbar - nur Projekte gleicher Festpunktart, die sich untereinander höchstens 10 % unterscheiden "
					+ "(mind. ") + Klein_Method_Functions.Klein_Method_Swarm.MIN_PROJECTS + tr(", andere Projekte als dieses)"));
			javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text.toString());
			area.setEditable(false);
			area.setWrapText(true);
			area.setPrefColumnCount(90);
			area.setPrefRowCount(Math.max(6, rows.size() + 4));
			dialog.getDialogPane().setContent(area);
			ButtonType applyType = new ButtonType(tr("Übernehmen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
			dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);
			dialog.getDialogPane().lookupButton(applyType).setDisable(usable == 0);
			java.util.Optional<ButtonType> answer = dialog.showAndWait();
			if (!answer.isPresent() || answer.get() != applyType)
				return;
			Thread thread = busyThread(() -> {
				int written = 0;
				ADJUSTMENT_LOCK.lock();
				try {
					for (Object[] row : rows) {
						Klein_Method_Functions.Klein_Method_Swarm.Start_Value start = (Klein_Method_Functions.Klein_Method_Swarm.Start_Value) row[2];
						if (Double.isNaN(start.value))
							continue;
						int dimension = (Integer) row[0];
						double meter = Math.max(0.00001, Math.min(0.99999, start.value / 1000.0)); // Klemmung wie Phase 1
						for (Object[] point : (List<Object[]>) row[3]) {
							Klein_Method.this.databaseHandler.writePointUncertainty((Integer) point[2], dimension, meter, meter, meter);
							written++;
						}
						Klein_Method.this.protocol.logEvent(String.format(Locale.GERMANY, "SCHWARMWISSEN Startwerte bewegliche Anschlusspunkte %dD (%s): %.3f mm",
								dimension, Klein_Method.this.swarmFixedPointClass.getDisplayName(), meter * 1000.0));
					}
					Klein_Method.this.databaseHandler.commit();
					Klein_Method.this.acceptOwnChangesIntoChecksum("Schwarmwissen-Startwerte Punkte");
				} catch (SQLException e) {
					Klein_Method.this.logger.log("Klein_Method: Punkt-Startwerte aus Schwarmwissen nicht schreibbar: " + e.getMessage());
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final int finalWritten = written;
				Platform.runLater(() -> {
					if (Klein_Method.this.tabBuilder != null)
						Klein_Method.this.tabBuilder.setStatusText("Startwerte aus Erfahrung für " + finalWritten + " Punkt(e) übernommen - Netz wird neu ausgeglichen.");
					Klein_Method.this.runHeadlessAdjustmentThenRefreshStatus();
				});
			}, "Klein_Method_Swarm_Point_Write");
			thread.setDaemon(true);
			thread.start();
		}	}
	// ============================================================================================================
	/** Quasigeoidmodelle: Import, Verwaltung, Hoehensystem-Pruefung (siehe Klein_Method_Functions.Klein_Method_Quasigeoid). */
	private final class Quasigeoid_Part {

		/** Modell importieren (Datei oder Zip), Ergebnis ins Protokoll; wirft bei ungueltiger Datei. */
		Klein_Method_Functions.Klein_Method_Quasigeoid.Import_Result importQuasigeoid(java.io.File source, String name, boolean replace) throws java.io.IOException {
			Klein_Method_Functions.Klein_Method_Quasigeoid.Import_Result result = Klein_Method_Functions.Klein_Method_Quasigeoid.importFile(source, name, replace);
			Klein_Method.this.logger.log("Klein_Method: Quasigeoid importiert: " + result.model.title() + " aus " + source.getName() + " -> " + result.file.getAbsolutePath()
					+ (result.replaced ? " (ersetzt)" : "") + " | " + String.join(" | ", result.checks));
			if (Klein_Method.this.isProjectOpen())
				Klein_Method.this.getProtocol().logEvent("QUASIGEOID: " + result.model.title() + " importiert (" + String.join("; ", result.checks) + ")");
			return result;
		}

		/** Hoehensystem des geoeffneten Projekts pruefen (Hintergrund, ADJUSTMENT_LOCK beim Aufrufer oder hier). */
		Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check checkHeightSystems() throws SQLException {
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = Klein_Method_Functions.Klein_Method_Local_System.Parameters
					.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
			Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Frame frame = (y, x, z) -> {
				if (p != null) {
					double east = Double.isFinite(y) ? y - p.addEast : 0.0, north = Double.isFinite(x) ? x - p.addNorth : 0.0;
					double[] p0 = p.p0();
					double[] d = p.vectorToEcef(east, north, z - p.addUp);
					double[] g = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(p0[0] + d[0], p0[1] + d[1], p0[2] + d[2]);
					return g;
				}
				double[] ll = Double.isFinite(y) && Double.isFinite(x) ? Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(y, x) : null;
				return new double[] { ll == null ? Double.NaN : ll[0], ll == null ? Double.NaN : ll[1], z };
			};
			return Klein_Method_Functions.Klein_Method_Quasigeoid.checkHeightSystems(Klein_Method.this.databaseHandler.readApproximationPoints(),
					Klein_Method.this.databaseHandler.readApproximationObservations(), frame, Klein_Method_Functions.Klein_Method_Quasigeoid.active());
		}

		/** Text der Pruefung (Anzeige); null, wenn nichts zu melden ist und nicht ausdruecklich gefragt wurde. */
		String describe(Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check c, boolean always) {
			if (c.verdict == -1)
				return always ? tr("Höhensysteme nicht prüfbar: Es gibt keinen Punkt, der sowohl über Nivellements (ab Höhenfestpunkten) als auch über "
						+ "3D-GNSS-Basislinien (ab 3D-Festpunkten) eine Höhe erhält.") : null;
			String model = Double.isFinite(c.modelUndulation)
					? String.format(Locale.GERMANY, tr("Höhenanomalie N laut %s: %.3f m."), c.modelTitle, c.modelUndulation)
					: tr("Kein Quasigeoidmodell aktiv - für eine sichere Aussage bitte ein Modell importieren (z. B. GCG2016 des BKG).");
			String base = String.format(Locale.GERMANY, tr("GNSS-Höhe minus Nivellementshöhe: %.3f m (Median aus %d Punkten, die über beide Wege erreichbar sind)."),
					c.difference, c.common) + " " + model;
			switch (c.verdict) {
			case 0:
				return always ? tr("Höhensysteme passen zusammen.") + " " + base : null;
			case 1:
				return tr("Höhensysteme gemischt: Die Höhenfestpunkte des Nivellements sind offenbar Normalhöhen (NHN), die GNSS-Festpunkte "
						+ "ellipsoidische Höhen.") + " " + base + "\n\n" + tr("So passen Nivellement und GNSS nicht zusammen - die Ausgleichung divergiert oder "
						+ "verteilt den Unterschied auf die Messungen. Abhilfe: Höhenfestpunkte in ellipsoidische Höhen umrechnen (h = H + N) "
						+ "oder alle Höhen im selben System einlesen.");
			default:
				return tr("Höhensysteme passen nicht zusammen:") + " " + base + " " + tr("Bitte Höhenfestpunkte, Instrumenten- und Reflektorhöhen prüfen.");
			}
		}

		/**
		 * Hoehenfestpunkte (1D, keine Neupunkte, Hoehe gegeben) umrechnen: toEllipsoidal = true NHN -> ellipsoidisch (h = H + N,
		 * N aus dem aktiven Modell, je Punkt gesichert), false = Ruecknahme mit dem gesicherten N. Im lokalen System um P0 exakt
		 * ueber geozentrische Koordinaten (Lage des Punkts, ohne Lage P0), sonst (UTM) H + N. Sperre beim Aufrufer.
		 */
		String convertFixedHeights(boolean toEllipsoidal) throws SQLException {
			if (!Klein_Method.this.isProjectOpen())
				throw new IllegalStateException(tr("Kein Projekt geöffnet."));
			String stored = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_HEIGHT_CONVERSION);
			boolean converted = stored != null && !stored.isEmpty();
			Klein_Method_Functions.Klein_Method_Quasigeoid.Model model = Klein_Method_Functions.Klein_Method_Quasigeoid.active();
			if (toEllipsoidal && converted)
				throw new IllegalStateException(tr("Die Höhenfestpunkte sind schon in ellipsoidische Höhen umgerechnet."));
			if (!toEllipsoidal && !converted)
				throw new IllegalStateException(tr("Es gibt keine Umrechnung der Höhenfestpunkte zum Zurücknehmen."));
			if (toEllipsoidal && model == null)
				throw new IllegalStateException(tr("Kein Quasigeoidmodell aktiv - bitte zuerst eines importieren (z. B. GCG2016 des BKG)."));
			Map<Integer, Double> undo = new LinkedHashMap<Integer, Double>();
			String modelTitle = toEllipsoidal ? model.title() : stored.substring(0, Math.max(0, stored.indexOf('|')));
			if (!toEllipsoidal)
				for (String item : stored.substring(stored.indexOf('|') + 1).split(";")) {
					String[] kv = item.split(":");
					if (kv.length == 2)
						undo.put(Integer.parseInt(kv[0]), Double.parseDouble(kv[1]));
				}
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = Klein_Method_Functions.Klein_Method_Local_System.Parameters
					.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
			Map<Integer, double[]> points = new LinkedHashMap<Integer, double[]>();
			StringBuilder backup = new StringBuilder(modelTitle).append('|');
			int outside = 0;
			double minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
			for (double[] r : Klein_Method.this.databaseHandler.readLocalConversionPoints()) {
				// r = {id, Dimension, y0, x0, z0, Punktart}
				int id = (int) r[0];
				if (toEllipsoidal ? (int) r[1] != 1 || (int) r[5] == org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId() || r[4] == 0.0 : !undo.containsKey(id))
					continue;
				double y = r[2], x = r[3], z = r[4], lat, lon, h;
				if (p != null) {
					double east = p.looksLocal(y, x) ? y : p.addEast, north = p.looksLocal(y, x) ? x : p.addNorth;
					double[] ecef = p.toEcef(east, north, z);
					double[] g = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(ecef[0], ecef[1], ecef[2]);
					lat = g[0];
					lon = g[1];
					h = g[2];
				}
				else {
					double[] ll = Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(y, x);
					lat = ll == null ? Double.NaN : ll[0];
					lon = ll == null ? Double.NaN : ll[1];
					h = z;
				}
				double n = toEllipsoidal ? (Double.isFinite(lat) ? model.undulation(lat, lon) : Double.NaN) : -undo.get(id);
				if (!Double.isFinite(n)) {
					outside++;
					continue;
				}
				double zNew;
				if (p != null) {
					double[] ecef = Klein_Method_Functions.Klein_Method_Local_System.geodeticToEcef(lat, lon, h + n);
					double[] local = p.toLocal(ecef[0], ecef[1], ecef[2]);
					// Lage bleibt wie eingetragen; die Hoehe gilt an der (ggf. angenommenen) Lage
					zNew = local[2];
				}
				else
					zNew = Math.round((z + n) * 1.0e4) / 1.0e4;
				points.put(id, new double[] { y, x, zNew });
				if (toEllipsoidal) {
					backup.append(id).append(':').append(String.format(Locale.ROOT, "%.4f", n)).append(';');
					minN = Math.min(minN, n);
					maxN = Math.max(maxN, n);
				}
			}
			if (points.isEmpty())
				throw new IllegalStateException(toEllipsoidal ? tr("Keine Höhenfestpunkte (1D, keine Neupunkte) mit Höhe im Projekt gefunden.")
						: tr("Die umgerechneten Höhenfestpunkte sind nicht mehr im Projekt."));
			if (toEllipsoidal && backup.length() > Klein_Method_Database_Handler.SETTING_VALUE_LENGTH)
				throw new IllegalStateException(tr("Zu viele Höhenfestpunkte für die Sicherung der Umrechnung."));
			Klein_Method.this.databaseHandler.updateLocalCoordinates(points, new LinkedHashMap<Integer, double[]>());
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_HEIGHT_CONVERSION, toEllipsoidal ? backup.toString() : "");
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_HEIGHT_SYSTEM_SHOWN, "");
			Klein_Method.this.databaseHandler.deleteFreeSolution();
			Klein_Method.this.databaseHandler.clearApproximationBackup();
			Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
			Klein_Method.this.refreshJag3dView(true);
			String range = toEllipsoidal ? String.format(Locale.GERMANY, "%.3f bis %.3f m", minN, maxN) : "";
			Klein_Method.this.logger.log("Klein_Method: Hoehenfestpunkte " + (toEllipsoidal ? "NHN -> ellipsoidisch (h = H + N, N " + range + ")" : "zurueck nach NHN (H = h - N)")
					+ ": " + points.size() + " Punkte, Modell " + modelTitle + (outside > 0 ? ", " + outside + " ausserhalb des Modells unveraendert" : "") + ".");
			Klein_Method.this.getProtocol().logEvent("HOEHENSYSTEM: Hoehenfestpunkte " + (toEllipsoidal ? "in ellipsoidische Hoehen umgerechnet (h = H + N, N " + range + ")"
					: "zurueck in Normalhoehen (H = h - N)") + ", " + points.size() + " Punkte, " + modelTitle);
			return toEllipsoidal
					? String.format(tr("%d Höhenfestpunkt(e) in ellipsoidische Höhen umgerechnet (h = H + N, N %s, Modell %s)."), points.size(), range, modelTitle)
							+ (outside > 0 ? " " + String.format(tr("%d Punkt(e) außerhalb des Modells bleiben unverändert."), outside) : "")
							+ "\n\n" + tr("Die Ergebnisse sind damit ungültig - bitte neu ausgleichen. Für Normalhöhen im Ergebnis beim Koordinaten-Export "
									+ "„Höhe: Normalhöhe NHN“ wählen. Rücknahme: „Quasigeoide verwalten …“ → „Umrechnung zurücknehmen“.")
					: String.format(tr("%d Höhenfestpunkt(e) zurück in Normalhöhen umgerechnet (H = h - N, Modell %s)."), points.size(), modelTitle);
		}

		/** Tab-Klick (Hintergrund, Sperre beim Aufrufer): pruefen, ins Log; Hinweis einmal je Projekt und Ergebnis. */
		void checkHeightSystemsOnTabClick() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check c = this.checkHeightSystems();
				if (c.verdict == -1)
					return;
				Klein_Method.this.logger.log("Klein_Method: Hoehensystem-Pruefung - " + c.common + " gemeinsame Punkte, h(GNSS) - H(Nivellement) = "
						+ String.format(Locale.GERMANY, "%.3f", c.difference) + " m, N(Modell) = "
						+ (Double.isFinite(c.modelUndulation) ? String.format(Locale.GERMANY, "%.3f m (%s)", c.modelUndulation, c.modelTitle) : "kein Modell")
						+ ", Bewertung " + (c.verdict == 0 ? "gleiches System" : c.verdict == 1 ? "NHN und ellipsoidisch gemischt" : "unklar") + ".");
				String text = this.describe(c, false);
				if (text == null)
					return;
				String key = c.verdict + ";" + Math.round(c.difference);
				if (key.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_HEIGHT_SYSTEM_SHOWN)))
					return;
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_HEIGHT_SYSTEM_SHOWN, key);
				Klein_Method.this.getProtocol().logEvent("HOEHENSYSTEM: " + text.replace('\n', ' '));
				String stored = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_HEIGHT_CONVERSION);
				if (c.verdict == 1 && Klein_Method_Functions.Klein_Method_Quasigeoid.active() != null && (stored == null || stored.isEmpty())) {
					// Umrechnung anbieten (veraendert das Projekt - nur nach Bestaetigung)
					java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
					boolean[] confirmed = new boolean[] { false };
					Platform.runLater(() -> {
						try {
							ButtonType yes = new ButtonType(tr("Höhenfestpunkte umrechnen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
							ButtonType no = new ButtonType(tr("Nicht jetzt"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
							Alert alert = new Alert(AlertType.CONFIRMATION, "", yes, no);
							alert.setTitle(tr("Klein_Method - Höhensysteme"));
							alert.setHeaderText(tr("Höhenfestpunkte in ellipsoidische Höhen umrechnen?"));
							TextArea area = new TextArea(text + "\n\n" + tr("„Höhenfestpunkte umrechnen“ rechnet die Höhenfestpunkte des Nivellements mit dem aktiven "
									+ "Quasigeoid in ellipsoidische Höhen um (h = H + N). Danach passen Nivellement und GNSS zusammen. Für Normalhöhen im Ergebnis "
									+ "beim Koordinaten-Export „Höhe: Normalhöhe NHN“ wählen. Rücknahme jederzeit über „Quasigeoide verwalten …“."));
							area.setEditable(false);
							area.setWrapText(true);
							area.setPrefRowCount(10);
							area.setPrefColumnCount(72);
							alert.getDialogPane().setContent(area);
							alert.setResizable(true);
							confirmed[0] = alert.showAndWait().filter(button -> button == yes).isPresent();
						} finally {
							latch.countDown();
						}
					});
					latch.await();
					if (!confirmed[0]) {
						Klein_Method.this.logger.log("Klein_Method: Umrechnung der Hoehenfestpunkte - vom Anwender abgelehnt.");
						return;
					}
					String result = this.convertFixedHeights(true);
					Platform.runLater(() -> {
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Höhensysteme"));
						alert.setHeaderText(null);
						alert.setContentText(result);
						alert.show();
					});
					return;
				}
				Platform.runLater(() -> {
					Alert alert = new Alert(AlertType.WARNING);
					alert.setTitle(tr("Klein_Method - Höhensysteme"));
					alert.setHeaderText(tr("Nivellement und GNSS in verschiedenen Höhensystemen?"));
					alert.setContentText(text);
					alert.show();
				});
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Hoehensystem-Pruefung nicht moeglich: " + e.getMessage());
			}
		}
	}

	// ============================================================================================================
	/** GNSS-Stuetzung am Netzrand (Wegdriften kombinierter Netze): Pruefung, Hinweis mit Loesungswegen, Empfindlichkeit. */
	private final class Gnss_Support_Part {

		/**
		 * Pruefung aus den Naeherungskoordinaten: Neupunkte mit Lage, Stuetzpunkte = Netzpunkte mit aktiven GNSS-Basislinien
		 * (ohne exclude) und Festpunkte mit Lage im Netzgebiet (Referenzstationen weit ausserhalb zaehlen nicht).
		 */
		Klein_Method_Functions.Klein_Method_Gnss_Support.Result evaluate(java.util.Set<String> exclude) throws SQLException {
			List<Klein_Method_Functions.Klein_Method_Approximation.Point> points = Klein_Method.this.databaseHandler.readApproximationPoints();
			java.util.Set<String> gnss = new java.util.HashSet<String>();
			for (Klein_Method_Functions.Klein_Method_Approximation.Observation o : Klein_Method.this.databaseHandler.readApproximationObservations())
				if (o.type >= 6 && o.type <= 8) {
					gnss.add(o.from);
					gnss.add(o.to);
				}
			Map<String, double[]> net = new LinkedHashMap<String, double[]>();
			double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
			for (Klein_Method_Functions.Klein_Method_Approximation.Point p : points)
				if (p.isNew && p.dimension >= 2 && p.positionGiven()) {
					net.put(p.name, new double[] { p.y0, p.x0 });
					minY = Math.min(minY, p.y0);
					maxY = Math.max(maxY, p.y0);
					minX = Math.min(minX, p.x0);
					maxX = Math.max(maxX, p.x0);
				}
			Map<String, double[]> supports = new LinkedHashMap<String, double[]>();
			if (net.isEmpty())
				return Klein_Method_Functions.Klein_Method_Gnss_Support.evaluate(net, supports);
			double margin = 0.2 * Math.max(maxY - minY, maxX - minX) + 50.0;
			for (Klein_Method_Functions.Klein_Method_Approximation.Point p : points) {
				if (p.dimension < 2 || !p.positionGiven() || (exclude != null && exclude.contains(p.name)))
					continue;
				boolean inArea = p.y0 >= minY - margin && p.y0 <= maxY + margin && p.x0 >= minX - margin && p.x0 <= maxX + margin;
				if (inArea && (gnss.contains(p.name) || !p.isNew))
					supports.put(p.name, new double[] { p.y0, p.x0 });
			}
			return Klein_Method_Functions.Klein_Method_Gnss_Support.evaluate(net, supports);
		}

		/** true, wenn das Projekt mindestens eine GNSS-Basislinie enthaelt. */
		boolean hasGnssBaselines() throws SQLException {
			for (Klein_Method_Functions.Klein_Method_Approximation.Observation o : Klein_Method.this.databaseHandler.readApproximationObservations())
				if (o.type >= 6 && o.type <= 8)
					return true;
			return false;
		}

		/** Randpunkte, die ohne den Stuetzpunkt name zusaetzlich gelb/rot wuerden und an ihm haengen. */
		List<String> dependentsIfRemoved(String name) {
			try {
				Klein_Method_Functions.Klein_Method_Gnss_Support.Result with = this.evaluate(null);
				if (!with.supports.contains(name))
					return new ArrayList<String>();
				Klein_Method_Functions.Klein_Method_Gnss_Support.Result without = this.evaluate(java.util.Collections.singleton(name));
				Map<String, Integer> before = new java.util.HashMap<String, Integer>();
				for (Klein_Method_Functions.Klein_Method_Gnss_Support.Point_Result p : with.points)
					before.put(p.name, p.level);
				List<String> out = new ArrayList<String>(with.dependents.getOrDefault(name, new ArrayList<String>()));
				for (Klein_Method_Functions.Klein_Method_Gnss_Support.Point_Result p : without.points)
					if (p.level == 2 && before.getOrDefault(p.name, 2) < 2 && !out.contains(p.name))
						out.add(p.name);
				return out;
			} catch (SQLException e) {
				return new ArrayList<String>();
			}
		}

		private java.util.Set<String> suspects() throws SQLException {
			java.util.Set<String> out = new java.util.TreeSet<String>();
			String s = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_GNSS_SUSPECT);
			if (s != null)
				for (String n : s.split(";"))
					if (!n.isEmpty())
						out.add(n);
			return out;
		}

		/** Meldetext mit Loesungswegen; null, wenn alles innerhalb der Stuetzung liegt. */
		String describe(Klein_Method_Functions.Klein_Method_Gnss_Support.Result r, java.util.Set<String> suspects) {
			List<String> red = r.names(2), yellow = r.names(1);
			if (red.isEmpty() && yellow.isEmpty())
				return null;
			Klein_Method_Functions.Klein_Method_Gnss_Support.Point_Result worst = r.worst();
			StringBuilder t = new StringBuilder(String.format(tr("%d Neupunkt(e) liegen außerhalb der GNSS-Stützung (Hülle der %d GNSS- bzw. Festpunkte, "
					+ "Durchmesser %s m) und werden nur über Tachymetermessungen weitergetragen - dort droht das Netz wegzudriften. Rot (mehr als %s m "
					+ "außerhalb): %d, gelb: %d. Am weitesten außerhalb: %s mit %s m."), red.size() + yellow.size(), r.supports.size(),
					String.format(Locale.GERMANY, "%.0f", r.hullDiameter), String.format(Locale.GERMANY, "%.0f", r.redLimit), red.size(), yellow.size(), worst.name,
					String.format(Locale.GERMANY, "%.0f", worst.outside)));
			for (Map.Entry<String, List<String>> e : r.dependents.entrySet()) {
				List<String> d = e.getValue();
				t.append("\n").append(String.format(tr("Einseitig gestützt: %d Randpunkt(e) hängen allein an %s (z. B. %s)."), d.size(), e.getKey(),
						String.join(", ", d.subList(0, Math.min(5, d.size())))));
				if (suspects.contains(e.getKey()))
					t.append(' ').append(String.format(tr("Die GNSS-Messungen von %s sind auffällig - ein Fehler dort wird am Rand nicht kontrolliert, sondern über "
							+ "den Hebel weitergetragen."), e.getKey()));
			}
			t.append("\n\n").append(tr("Lösungswege:")).append("\n").append(String.format(tr("1. GNSS nachmessen am äußersten Punkt (%s) - die einzige echte Lösung."),
					worst.name));
			t.append("\n").append(tr("2. Prüfen, ob für die roten Punkte GNSS-Basislinien gemessen, aber nicht importiert wurden."));
			t.append("\n").append(tr("3. Festpunkte im Randbereich als Bewegliche Anschlusspunkte einführen (dynamische AGL)."));
			t.append("\n").append(tr("4. Maßstab der Strecken nicht frei schätzen, wenn das Instrument kalibriert ist - der freie Maßstab wird am Rand nur "
					+ "durch GNSS bestimmt."));
			t.append("\n").append(tr("5. Wenn nichts davon geht: Die Randpunkte sind in Protokoll, Karte und Koordinaten-Export als „außerhalb der GNSS-Stützung“ "
					+ "gekennzeichnet. Wie stark der Rand an einzelnen Stützpunkten hängt, zeigt Menü Klein_Method „GNSS-Stützung am Netzrand prüfen“."));
			return t.toString();
		}

		/** nach erfolgreicher Ausgleichung (Tab-Klick, Sperre beim Aufrufer): pruefen, speichern, Hinweis einmal je Ergebnis. */
		void checkAfterAdjustment() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				// ohne GNSS-Basislinien gibt es keine GNSS-Stuetzung - Pruefung und Hinweis entfallen
				if (!this.hasGnssBaselines()) {
					Klein_Method_Tab_Builder.Klein_Method_Map_Window.gnssSupportLevels = new java.util.HashMap<String, Integer>();
					return;
				}
				Klein_Method_Functions.Klein_Method_Gnss_Support.Result r = this.evaluate(null);
				if (r.supports.isEmpty())
					return;
				StringBuilder levels = new StringBuilder();
				for (Klein_Method_Functions.Klein_Method_Gnss_Support.Point_Result p : r.points)
					if (p.level > 0 && levels.length() < Klein_Method_Database_Handler.SETTING_VALUE_LENGTH - 300)
						levels.append(p.name).append(':').append(p.level).append(';');
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_GNSS_SUPPORT_LEVELS, levels.toString());
				Map<String, Integer> map = new java.util.HashMap<String, Integer>();
				for (Klein_Method_Functions.Klein_Method_Gnss_Support.Point_Result p : r.points)
					if (p.level > 0)
						map.put(p.name, p.level);
				Klein_Method_Tab_Builder.Klein_Method_Map_Window.gnssSupportLevels = map;
				java.util.Set<String> suspects = this.suspects();
				String text = this.describe(r, suspects);
				Klein_Method.this.logger.log("Klein_Method: GNSS-Stuetzung - " + r.supports.size() + " Stuetzpunkte, Huelle " + String.format(Locale.GERMANY, "%.0f", r.hullDiameter)
						+ " m, rot " + r.names(2).size() + ", gelb " + r.names(1).size() + (r.dependents.isEmpty() ? "" : ", einseitig an " + r.dependents.keySet())
						+ (r.worst() == null ? "" : ", am weitesten ausserhalb " + r.worst().name + " " + String.format(Locale.GERMANY, "%.0f", r.worst().outside) + " m") + ".");
				if (text == null)
					return;
				String key = r.names(2).size() + ";" + r.dependents.keySet() + ";" + suspects;
				if (key.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_GNSS_SUPPORT_SHOWN)))
					return;
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_GNSS_SUPPORT_SHOWN, key);
				Klein_Method.this.getProtocol().logEvent("GNSS-STUETZUNG: rot " + r.names(2) + ", gelb " + r.names(1).size() + ", einseitig " + r.dependents.keySet()
						+ (suspects.isEmpty() ? "" : ", GNSS auffaellig " + suspects));
				Platform.runLater(() -> {
					Alert alert = new Alert(AlertType.WARNING);
					alert.setTitle(tr("Klein_Method - GNSS-Stützung am Netzrand"));
					alert.setHeaderText(tr("Netzrand nur über Tachymeter bestimmt - Gefahr des Wegdriftens"));
					TextArea area = new TextArea(text);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(14);
					area.setPrefColumnCount(76);
					alert.getDialogPane().setContent(area);
					alert.setResizable(true);
					alert.show();
					Klein_Method_Tab_Builder.Klein_Method_Map_Window.reloadIfOpen();
				});
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Pruefung der GNSS-Stuetzung nicht moeglich: " + e.getMessage());
			}
		}

		/** Menue: Empfindlichkeit je einseitigem Stuetzpunkt (GNSS kurz aus, ausgleichen, Randpunkte vergleichen, wieder ein). */
		void sensitivityFromMenu() {
			if (!Klein_Method.this.isProjectOpen() || Klein_Method.this.approximationBusy()) {
				Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet bzw. gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Thread thread = busyThread(() -> {
				String text;
				ADJUSTMENT_LOCK.lock();
				try {
					text = this.sensitivity();
				} catch (Exception e) {
					text = tr("Prüfung nicht möglich: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				Klein_Method.this.showApproximationInfo(text);
			}, "Klein_Method_Gnss_Support");
			thread.setDaemon(true);
			thread.start();
		}

		/** Empfindlichkeit (Sperre beim Aufrufer); Ergebnistext. */
		String sensitivity() throws Exception {
			Klein_Method_Functions.Klein_Method_Gnss_Support.Result r = this.evaluate(null);
			String base = this.describe(r, this.suspects());
			if (r.dependents.isEmpty())
				return (base == null ? tr("Alle Neupunkte liegen innerhalb der GNSS-Stützung.") : base) + "\n\n" + tr("Kein Randbereich hängt an einem einzelnen GNSS-Punkt - keine Empfindlichkeit zu rechnen.");
			StringBuilder t = new StringBuilder(base).append("\n\n").append(tr("Empfindlichkeit (Ausgleichung jeweils ohne die GNSS-Basislinien des Stützpunkts):"));
			List<Map.Entry<String, List<String>>> anchors = new ArrayList<Map.Entry<String, List<String>>>(r.dependents.entrySet());
			anchors.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
			EstimationStateType reference = Klein_Method.this.runHeadlessAdjustmentSynchronously();
			Map<String, double[]> ref = this.adjusted();
			for (int k = 0; k < Math.min(3, anchors.size()); k++) {
				String anchor = anchors.get(k).getKey();
				List<String> dependents = anchors.get(k).getValue();
				List<Integer> ids = Klein_Method.this.databaseHandler.readActiveGnssObservationIds(anchor);
				if (ids.isEmpty()) {
					t.append("\n").append(String.format(tr("%s: Festpunkt ohne GNSS - nicht prüfbar."), anchor));
					continue;
				}
				EstimationStateType state;
				Map<String, double[]> moved;
				try {
					for (int id : ids)
						Klein_Method.this.databaseHandler.setGnssObservationEnabled(id, false);
					state = Klein_Method.this.runHeadlessAdjustmentSynchronously();
					moved = this.adjusted();
				} finally {
					for (int id : ids)
						Klein_Method.this.databaseHandler.setGnssObservationEnabled(id, true);
				}
				if (state != EstimationStateType.ERROR_FREE_ESTIMATION || reference != EstimationStateType.ERROR_FREE_ESTIMATION) {
					t.append("\n").append(String.format(tr("%s: ohne seine %d GNSS-Basislinien rechnet die Ausgleichung nicht (%s) - der Randbereich ist dann nicht "
							+ "bestimmbar."), anchor, ids.size(), String.valueOf(state)));
					continue;
				}
				double max = 0.0;
				String maxName = null;
				for (String d : dependents) {
					double[] a = ref.get(d), b = moved.get(d);
					if (a == null || b == null)
						continue;
					double shift = Math.hypot(b[0] - a[0], b[1] - a[1]);
					if (shift > max) {
						max = shift;
						maxName = d;
					}
				}
				t.append("\n").append(String.format(tr("%s: ohne seine %d GNSS-Basislinien verschieben sich die %d Randpunkte um bis zu %s cm in der Lage (%s)."),
						anchor, ids.size(), dependents.size(), String.format(Locale.GERMANY, "%.1f", 100.0 * max), maxName == null ? "-" : maxName));
				Klein_Method.this.logger.log("Klein_Method: GNSS-Stuetzung Empfindlichkeit " + anchor + ": " + ids.size() + " Basislinien aus, Randpunkte bis "
						+ String.format(Locale.GERMANY, "%.3f", max) + " m (" + maxName + ").");
			}
			// Ergebnis wieder mit allen Basislinien
			Klein_Method.this.runHeadlessAdjustmentSynchronously();
			Klein_Method.this.getProtocol().logEvent("GNSS-STUETZUNG Empfindlichkeit: " + t.toString().substring(t.lastIndexOf(":") + 1).replace('\n', ' ').trim());
			return t.toString();
		}

		private Map<String, double[]> adjusted() throws SQLException {
			Map<String, double[]> m = new java.util.HashMap<String, double[]>();
			for (Klein_Method_Point_Coordinate_Row r : Klein_Method.this.databaseHandler.readAllActivePointCoordinates())
				if (r.getRechtswert() != null && r.getHochwert() != null)
					m.put(r.getName(), new double[] { r.getRechtswert(), r.getHochwert() });
			return m;
		}
	}




	// ============================================================================================================
	/**
	 * Vor der JAG3D-Mittelwertbildung (Menue "Mittelwertbildung", Strg+G): JAG3D erkennt Zenitwinkel und Richtungen der
	 * II. Lage nur ueber die Naeherungskoordinaten. Fehlen sie (0/0), wird die II. Lage nicht reduziert und es entstehen
	 * Abweichungen um 200 gon. Der Menueeintrag wird zur Laufzeit erkannt (Kennung AVERAGE) und umhuellt - ohne Aenderung
	 * an JAG3D-Dateien. Nicht gefunden: alles wie bisher (Log).
	 */
	private final class Averaging_Guard_Part {

		void install(javafx.scene.control.MenuBar bar) {
			javafx.scene.control.MenuItem item = null;
			for (javafx.scene.control.Menu menu : bar.getMenus()) {
				item = this.find(menu);
				if (item != null)
					break;
			}
			if (item == null) {
				Klein_Method.this.logger.log("Klein_Method: Menueeintrag Mittelwertbildung (AVERAGE) nicht gefunden - keine Pruefung der Naeherungskoordinaten davor.");
				return;
			}
			if ("KleinMethodGuard".equals(item.getId()))
				return;
			javafx.event.EventHandler<javafx.event.ActionEvent> original = item.getOnAction();
			if (original == null)
				return;
			item.setId("KleinMethodGuard");
			item.setOnAction(event -> this.guard(() -> original.handle(event)));
			Klein_Method.this.logger.log("Klein_Method: Pruefung der Naeherungskoordinaten vor der JAG3D-Mittelwertbildung eingerichtet.");
		}

		private javafx.scene.control.MenuItem find(javafx.scene.control.Menu menu) {
			for (javafx.scene.control.MenuItem item : menu.getItems()) {
				if (item instanceof javafx.scene.control.Menu) {
					javafx.scene.control.MenuItem found = this.find((javafx.scene.control.Menu) item);
					if (found != null)
						return found;
				} else if (item.getUserData() != null && "AVERAGE".equals(String.valueOf(item.getUserData())))
					return item;
			}
			return null;
		}

		/** FX-Thread: fehlende Naeherungen? dann Rueckfrage, Naeherungen rechnen, danach die Mittelwertbildung oeffnen. */
		private void guard(Runnable openAveraging) {
			List<String> missing;
			try {
				missing = Klein_Method.this.isProjectOpen() ? Klein_Method.this.databaseHandler.readObservedPointsWithoutApproximation() : new ArrayList<String>();
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Pruefung vor der Mittelwertbildung nicht moeglich: " + e.getMessage());
				missing = new ArrayList<String>();
			}
			if (missing.isEmpty() || Klein_Method.this.approximationBusy()) {
				openAveraging.run();
				return;
			}
			Klein_Method.this.logger.log("Klein_Method: Mittelwertbildung - " + missing.size() + " Punkt(e) mit Messungen ohne Naeherungskoordinaten: "
					+ String.join(", ", missing.subList(0, Math.min(10, missing.size()))));
			ButtonType compute = new ButtonType(tr("Erst Näherungen berechnen (empfohlen)"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
			ButtonType anyway = new ButtonType(tr("Trotzdem mitteln"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
			Alert alert = new Alert(AlertType.CONFIRMATION, String.format(tr("Für %d Punkt(e) mit Messungen fehlen Näherungskoordinaten (z. B. %s). Ohne sie erkennt "
					+ "JAG3D Zenitwinkel und Richtungen der II. Lage nicht - die Mittelwertbildung meldet dann Abweichungen um 200 gon, obwohl die Messungen "
					+ "stimmen.\n\nJetzt zuerst die Näherungskoordinaten berechnen? Danach öffnet sich die Mittelwertbildung."), missing.size(),
					String.join(", ", missing.subList(0, Math.min(5, missing.size())))), compute, anyway, ButtonType.CANCEL);
			alert.setTitle(tr("Klein_Method - Mittelwertbildung"));
			alert.setHeaderText(tr("Näherungskoordinaten fehlen"));
			java.util.Optional<ButtonType> answer = alert.showAndWait();
			if (!answer.isPresent() || answer.get() == ButtonType.CANCEL)
				return;
			if (answer.get() == anyway) {
				Klein_Method.this.logger.log("Klein_Method: Mittelwertbildung ohne Naeherungskoordinaten - vom Anwender gewaehlt.");
				openAveraging.run();
				return;
			}
			Thread thread = busyThread(() -> {
				Klein_Method.this.approximationPart.runApproximationBlocking();
				int still;
				try {
					still = Klein_Method.this.databaseHandler.readObservedPointsWithoutApproximation().size();
				} catch (Exception e) {
					still = -1;
				}
				Klein_Method.this.logger.log("Klein_Method: Mittelwertbildung - nach der Naeherungsberechnung noch ohne Naeherung: " + still);
				final int remaining = still;
				Platform.runLater(() -> {
					if (remaining > 0)
						Klein_Method.this.showApproximationInfo(String.format(tr("Für %d Punkt(e) fehlen weiterhin Näherungskoordinaten - Zenitwinkel und Richtungen "
								+ "der II. Lage an diesen Punkten können in der Mittelwertbildung als Überschreitung erscheinen."), remaining));
					openAveraging.run();
				});
			}, "Klein_Method_Averaging_Guard");
			thread.setDaemon(true);
			thread.start();
		}
	}

	// ============================================================================================================
	/**
	 * Prognose und Messplanung: Genauigkeit je Punkt aus der Ausgleichung (grosse Halbachse der Konfidenzellipse) und
	 * rechnerisch eingefuegte GNSS-Sitzungen (virtuelle Basislinien, danach geloescht) - welche Nachmessung bringt am
	 * meisten? Veraendert keine Daten.
	 */
	/** Hilfe im Paket: Klein_Method_Hilfe_<Sprache>.html (alle Sprachen im selben Ordner, gegenseitig verlinkt). */
	static String helpResource(String language) {
		return "/org/applied_geodesy/klein_method/hilfe/Klein_Method_Hilfe_" + (language == null ? "de" : language) + ".html";
	}

	/**
	 * Hilfe direkt aus dem Paket im Standardbrowser oeffnen - ohne Kopie: als Datei im Klassenpfad oder im Quellordner
	 * des JAG3D-Projekts (JAG3D-Aufbau ohne kopierte Ressourcen). Oberflaechensprache, sonst englisch, sonst deutsch.
	 * Nur wenn die Hilfe ausschliesslich in einer JAR-Datei liegt: in den Temp-Ordner des Systems (beim Beenden geloescht).
	 */
	public void openHelp() {
		Thread thread = new Thread(() -> {
			String language = org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.getLanguage();
			java.io.File file = null;
			try {
				for (String lang : new String[] { language, "en", "de" }) {
					file = org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.findPackageFile(helpResource(lang));
					if (file != null)
						break;
				}
				if (file == null)
					file = this.extractHelpFromJar(language);
				if (file == null)
					throw new java.io.IOException(tr("Hilfedatei nicht gefunden - erwartet im Ordner …\\src\\org\\applied_geodesy\\klein_method\\hilfe des JAG3D-Projekts"));
				this.logger.log("Klein_Method: Hilfe geoeffnet: " + file.getAbsolutePath());
				if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
					java.awt.Desktop.getDesktop().browse(file.toURI());
				else
					this.showApproximationInfo(String.format(tr("Die Hilfe liegt hier (bitte im Browser öffnen): %s"), file.getAbsolutePath()));
			} catch (Exception e) {
				this.logger.log("Klein_Method: Hilfe nicht zu oeffnen: " + e.getMessage());
				this.showApproximationInfo(String.format(tr("Die Hilfe konnte nicht geöffnet werden: %s - Datei: %s"), e.getMessage(),
						file == null ? "-" : file.getAbsolutePath()));
			}
		}, "Klein_Method_Help");
		thread.setDaemon(true);
		thread.start();
	}

	/** Hilfe nur in einer JAR-Datei: alle Sprachen in den Temp-Ordner des Systems (beim Beenden geloescht); null = keine. */
	private java.io.File extractHelpFromJar(String language) throws java.io.IOException {
		java.io.File folder = new java.io.File(System.getProperty("java.io.tmpdir"), "Klein_Method_Hilfe_" + Klein_Method_Default_Values.PACKAGE_VERSION);
		Map<String, java.io.File> files = new java.util.HashMap<String, java.io.File>();
		for (String lang : org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.LANGUAGES)
			try (java.io.InputStream in = Klein_Method.class.getResourceAsStream(helpResource(lang))) {
				if (in == null)
					continue;
				folder.mkdirs();
				folder.deleteOnExit();
				java.io.File file = new java.io.File(folder, "Klein_Method_Hilfe_" + lang + ".html");
				java.nio.file.Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				file.deleteOnExit();
				files.put(lang, file);
			}
		// Beispiel-ZIPs aus Kapitel 0 neben die Hilfe legen (relative Links)
		String base = helpResource("de");
		base = base.substring(0, base.lastIndexOf('/') + 1);
		for (String zip : HELP_EXAMPLE_ZIPS)
			try (java.io.InputStream in = Klein_Method.class.getResourceAsStream(base + zip)) {
				if (in == null)
					continue;
				java.io.File file = new java.io.File(folder, zip);
				java.nio.file.Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				file.deleteOnExit();
			}
		return files.containsKey(language) ? files.get(language) : files.containsKey("en") ? files.get("en") : files.get("de");
	}

	/** Beispieldaten der Hilfe (Kapitel 0), liegen im Ordner hilfe neben den HTML-Dateien. */
	private static final String[] HELP_EXAMPLE_ZIPS = { "Klein_Method_Beispiel_A_1D_Nivellement.zip", "Klein_Method_Beispiel_B_2D_Lage.zip",
			"Klein_Method_Beispiel_C_3D_Tachymeter_Nivellement.zip", "Klein_Method_Beispiel_D_3D_GNSS.zip", "Klein_Method_Beispiel_E_3D_GNSS_Tachymeter_Nivellement.zip" };

	private final Import_Report_Part importReportPart = new Import_Report_Part();

	/** Menue "Importbericht": Plausibilitaet der Daten (Hintergrund), Fenster, Protokoll und Textdatei. */
	public void openImportReport() {
		this.importReportPart.openFromMenu();
	}

	/** Importbericht als Text (Oberflaechensprache) - fuer Tests und Menue; DB offen. */
	public String computeImportReportText() throws SQLException {
		return String.join("\n", this.importReportPart.compute().uiLines);
	}

	/** Lage der 1D-Neupunkte aus dem Nivellement ableiten (ohne Rueckfrage); Anzahl gesetzter Punkte. */
	public int deriveOneDimensionalPositions() throws SQLException {
		ADJUSTMENT_LOCK.lock();
		try {
			return this.importReportPart.deriveOneDimensionalPositions(true);
		} finally {
			ADJUSTMENT_LOCK.unlock();
		}
	}

	/**
	 * Importbericht: Plausibilitaet der importierten Daten VOR der Ausgleichung, mit Ampel je Pruefung - Namen,
	 * gegenseitige Zenitwinkel (Hoehenunterschied hin und zurueck), Lage I/II, Instrumenten- und Zielhoehen, Einheiten,
	 * Koordinaten- und Hoehensysteme, Lage der 1D-Punkte. Fehler in den Daten erscheinen sonst erst in der Ausgleichung als
	 * scheinbare grobe Fehler. Aendert keine Daten (ausser auf Wunsch die Lage der 1D-Neupunkte).
	 */
	private final class Import_Report_Part {

		/** Projekteinstellung: Datenstand des zuletzt automatisch gezeigten Berichts. */
		private static final String SETTING_FINGERPRINT = "import_report_fp";
		private static final double REFRACTION_COEFFICIENT = 0.13, EARTH_RADIUS = 6381000.0;
		private static final int OK = 0, HINT = 1, WARN = 2;
		private static final int MAX_LISTED = 10;

		final class Report {
			final List<String> uiLines = new ArrayList<String>(), protocolLines = new ArrayList<String>();
			int level = OK, warnings = 0, hints = 0;
			/** 1D-Neupunkte mit Platzhalter-Lage, deren Lage aus dem Nivellement ableitbar ist. */
			int derivable = 0;

			/** Pruefung mit Ampel; german = Titel (Format). */
			void check(int level, String german, Object... args) {
				this.level = Math.max(this.level, level);
				if (level == WARN)
					this.warnings++;
				else if (level == HINT)
					this.hints++;
				String tagUi = level == WARN ? tr("[ROT]") : level == HINT ? tr("[GELB]") : tr("[GRÜN]");
				String tagDe = level == WARN ? "[ROT]" : level == HINT ? "[GELB]" : "[GRUEN]";
				this.uiLines.add(tagUi + " " + String.format(Locale.GERMANY, tr(german), args));
				this.protocolLines.add(tagDe + " " + String.format(Locale.GERMANY, german, args));
			}

			void detail(String text) {
				this.uiLines.add("      " + text);
				this.protocolLines.add("      " + text);
			}
		}

		private String linear(double meter) {
			return String.format(Locale.GERMANY, "%.3f m", meter);
		}

		private String list(java.util.Collection<String> items) {
			List<String> shown = new ArrayList<String>();
			for (String item : items) {
				if (shown.size() >= MAX_LISTED) {
					shown.add("…");
					break;
				}
				shown.add(item);
			}
			return String.join(", ", shown);
		}

		Report compute() throws SQLException {
			Report r = new Report();
			Map<Integer, String> names = Klein_Method.this.databaseHandler.readPointNames();
			List<double[]> points = Klein_Method.this.databaseHandler.readLocalConversionPoints(); // {id, dim, y0, x0, z0, Punktart}
			List<Object[]> terrestrial = Klein_Method.this.databaseHandler.readActiveTerrestrialObservations();
			List<Object[]> gnss = Klein_Method.this.databaseHandler.readActiveGnssVectors();
			Set<String> pointNames = new HashSet<String>(names.values());
			Map<String, Integer> usage = new java.util.HashMap<String, Integer>();
			Set<String> missing = new java.util.TreeSet<String>();
			for (Object[] o : terrestrial)
				for (String n : new String[] { (String) o[2], (String) o[3] }) {
					usage.merge(n, 1, Integer::sum);
					if (!pointNames.contains(n))
						missing.add(n);
				}
			for (Object[] o : gnss)
				for (String n : new String[] { (String) o[1], (String) o[2] }) {
					usage.merge(n, 1, Integer::sum);
					if (!pointNames.contains(n))
						missing.add(n);
				}
			r.uiLines.add(String.format(tr("IMPORTBERICHT (Plausibilität vor der Ausgleichung): %d Punkte, %d terrestrische Beobachtungen, %d GNSS-Basislinien"),
					points.size(), terrestrial.size(), gnss.size()));
			r.protocolLines.add(String.format("IMPORTBERICHT: %d Punkte, %d terrestrische Beobachtungen, %d GNSS-Basislinien", points.size(), terrestrial.size(), gnss.size()));
			// ---------- 0. Fest-/Datums-/Anschlusspunkte ohne Koordinaten (0/0/0) - als Festpunkt im Erdmittelpunkt bzw. bei
			// 0/0 sprengen sie das Netz; meist doppelt importiert (Name schon als GNSS-Punkt vorhanden) oder Neupunkte
			List<Object[]> zeroFixed = Klein_Method.this.databaseHandler.readZeroFixedPoints();
			if (!zeroFixed.isEmpty()) {
				List<String> zeroNames = new ArrayList<String>();
				int atGnss = 0;
				for (Object[] z : zeroFixed) {
					zeroNames.add((String) z[1]);
					atGnss += (Integer) z[4];
				}
				r.check(WARN, "Festpunkte ohne Koordinaten (0/0/0): %d Fest-/Datums-/Anschlusspunkt(e) - als Neupunkte importieren oder im Ablauf-Assistenten umwandeln",
						zeroFixed.size());
				r.detail(this.list(zeroNames));
				if (atGnss > 0)
					r.detail(String.format(tr("%d davon sind Endpunkte von GNSS-Basislinien - vermutlich wurde der Name doppelt importiert und die GNSS-Koordinaten übersprungen."),
							atGnss));
			}
			// ---------- 1. Namen
			if (!missing.isEmpty()) {
				r.check(WARN, "Namen: %d Punkt(e) in Beobachtungen, aber ohne Koordinaten im Projekt", missing.size());
				r.detail(this.list(missing));
			}
			Set<String> unused = new java.util.TreeSet<String>();
			Set<String> variants = new java.util.TreeSet<String>();
			for (String n : pointNames) {
				if (!usage.containsKey(n))
					unused.add(n);
				int u = n.indexOf('_');
				if (u > 0 && u < n.length() - 1 && pointNames.contains(n.substring(u + 1)))
					variants.add(n + " → " + n.substring(u + 1));
			}
			if (!unused.isEmpty()) {
				r.check(HINT, "Namen: %d Punkt(e) ohne Beobachtungen", unused.size());
				r.detail(this.list(unused));
			}
			if (!variants.isEmpty()) {
				r.check(HINT, "Namen: %d Hilfs-/Aufstellpunkt(e) mit dem Namen eines vorhandenen Punkts (eigene Punkte, z. B. Nivellement-Aufstellungen)", variants.size());
				r.detail(this.list(variants));
			}
			if (missing.isEmpty() && unused.isEmpty())
				r.check(OK, "Namen: alle Punkte haben Koordinaten und Beobachtungen");
			// ---------- 2. Gegenseitige Zenitwinkel: Hoehenunterschied hin + zurueck = 0
			int zenithType = ObservationType.ZENITH_ANGLE.getId(), slopeType = ObservationType.SLOPE_DISTANCE.getId();
			Map<String, double[]> slope = new java.util.HashMap<String, double[]>(); // Start\u0001Ziel -> {Summe, n}
			for (Object[] o : terrestrial)
				if ((Integer) o[1] == slopeType && (Double) o[6] > 0.0) {
					double[] a = slope.computeIfAbsent(o[2] + "\u0001" + o[3], k -> new double[2]);
					a[0] += (Double) o[6];
					a[1]++;
				}
			Map<String, double[]> dh = new java.util.HashMap<String, double[]>(); // {Summe dh, n, Strecke}
			List<Double> zenithValues = new ArrayList<Double>();
			Map<String, double[]> faces = new java.util.HashMap<String, double[]>(); // Station\u0001Ziel -> {Summe I, n I, Summe II, n II}
			int zeroTarget = 0, heightRows = 0;
			Map<String, Set<Long>> stationHeights = new LinkedHashMap<String, Set<Long>>();
			for (Object[] o : terrestrial) {
				int type = (Integer) o[1];
				double ih = (Double) o[4], th = (Double) o[5];
				if (type == zenithType || type == slopeType) {
					heightRows++;
					if (th == 0.0 && ih > 0.0)
						zeroTarget++;
				}
				if (type != ObservationType.LEVELING.getId())
					stationHeights.computeIfAbsent((String) o[2], k -> new java.util.TreeSet<Long>()).add(Math.round(ih * 1000.0));
				if (type != zenithType)
					continue;
				double z = (Double) o[6];
				double zGon = z * 200.0 / Math.PI;
				zenithValues.add(zGon);
				double[] f = faces.computeIfAbsent(o[2] + "\u0001" + o[3], k -> new double[4]);
				if (zGon > 200.0) {
					f[2] += zGon;
					f[3]++;
					z = 2.0 * Math.PI - z; // Lage II auf Lage I
				}
				else {
					f[0] += zGon;
					f[1]++;
				}
				double s = (Double) o[7];
				double[] sl = slope.get(o[2] + "\u0001" + o[3]);
				if (!(s > 0.0) && sl != null)
					s = sl[0] / sl[1];
				if (!(s > 0.0))
					continue;
				double[] a = dh.computeIfAbsent(o[2] + "\u0001" + o[3], k -> new double[3]);
				// Erdkruemmung und Refraktion (k = 0,13): gehen bei hin und zurueck nicht heraus, sondern addieren sich
				double horizontal = s * Math.sin(z);
				a[0] += s * Math.cos(z) + (1.0 - REFRACTION_COEFFICIENT) * horizontal * horizontal / (2.0 * EARTH_RADIUS) + ih - th;
				a[1]++;
				a[2] = s;
			}
			int pairs = 0;
			List<Object[]> flagged = new ArrayList<Object[]>();
			Set<String> done = new HashSet<String>();
			for (Map.Entry<String, double[]> e : dh.entrySet()) {
				String[] ab = e.getKey().split("\u0001", -1);
				String back = ab[1] + "\u0001" + ab[0];
				if (!dh.containsKey(back) || done.contains(back))
					continue;
				done.add(e.getKey());
				pairs++;
				double[] f = e.getValue(), g = dh.get(back);
				double w = f[0] / f[1] + g[0] / g[1];
				double tolerance = 0.03 + 1.0e-4 * f[2];
				if (Math.abs(w) > tolerance)
					flagged.add(new Object[] { ab[0] + " ↔ " + ab[1], w, f[2] });
			}
			flagged.sort((x, y) -> Double.compare(Math.abs((Double) y[1]), Math.abs((Double) x[1])));
			if (pairs == 0)
				r.check(OK, "Gegenseitige Zenitwinkel: keine (nicht prüfbar)");
			else if (flagged.isEmpty())
				r.check(OK, "Gegenseitige Zenitwinkel: %d Paare, Höhenunterschied hin und zurück passt", pairs);
			else {
				double[] ws = new double[flagged.size()];
				for (int i = 0; i < ws.length; i++)
					ws[i] = (Double) flagged.get(i)[1];
				java.util.Arrays.sort(ws);
				r.check(flagged.size() >= 3 && flagged.size() >= 0.2 * pairs ? WARN : HINT,
						"Gegenseitige Zenitwinkel: %d von %d Paaren widersprechen sich (Höhenunterschied hin + zurück, Median %s) - Instrumenten- oder Zielhöhe prüfen",
						flagged.size(), pairs, this.linear(ws[ws.length / 2]));
				List<String> shown = new ArrayList<String>();
				for (Object[] f : flagged)
					shown.add(String.format(Locale.GERMANY, "%s %s", f[0], this.linear((Double) f[1])));
				r.detail(this.list(shown));
			}
			// ---------- 3. Lage I/II (Indexfehler)
			List<String> index = new ArrayList<String>();
			int bothFaces = 0;
			for (Map.Entry<String, double[]> e : faces.entrySet()) {
				double[] f = e.getValue();
				if (f[1] == 0 || f[3] == 0)
					continue;
				bothFaces++;
				double i = (f[0] / f[1] + f[2] / f[3] - 400.0) / 2.0 * 1000.0; // mgon
				if (Math.abs(i) > 10.0)
					index.add(String.format(Locale.GERMANY, "%s %.1f mgon", e.getKey().replace("\u0001", " → "), i));
			}
			if (bothFaces == 0)
				r.check(OK, "Lage I/II: keine Zenitwinkel in beiden Lagen (schon gemittelt) - nicht prüfbar");
			else if (index.isEmpty())
				r.check(OK, "Lage I/II: %d Ziele in beiden Lagen, Indexfehler unter 10 mgon", bothFaces);
			else {
				r.check(HINT, "Lage I/II: %d von %d Zielen mit Indexfehler über 10 mgon", index.size(), bothFaces);
				r.detail(this.list(index));
			}
			// ---------- 4. Instrumenten- und Zielhoehen
			List<String> multi = new ArrayList<String>();
			for (Map.Entry<String, Set<Long>> e : stationHeights.entrySet())
				if (e.getValue().size() > 1) {
					List<String> hs = new ArrayList<String>();
					for (long h : e.getValue())
						hs.add(String.format(Locale.GERMANY, "%.3f", h / 1000.0));
					multi.add(e.getKey() + " (" + String.join(" / ", hs) + " m)");
				}
			if (!multi.isEmpty()) {
				r.check(HINT, "Instrumentenhöhen: %d Station(en) mit mehreren Instrumentenhöhen (mehrere Aufstellungen? sonst prüfen)", multi.size());
				r.detail(this.list(multi));
			}
			// rot nur, wenn fast alle Zielhoehen 0 sind oder die gegenseitigen Zenitwinkel es bestaetigen (Zielmarken ohne
			// Reflektorhoehe sind sonst normal)
			boolean contradiction = flagged.size() >= 3 && flagged.size() >= 0.2 * pairs;
			if (zeroTarget >= 10 && (zeroTarget >= 0.9 * heightRows || (zeroTarget >= 0.5 * heightRows && contradiction)))
				r.check(WARN, "Zielhöhen: %d von %d Schrägstrecken/Zenitwinkeln mit Zielhöhe 0 bei gesetzter Instrumentenhöhe - fehlen die Zielhöhen im Import?",
						zeroTarget, heightRows);
			else if (zeroTarget > 0)
				r.check(HINT, "Zielhöhen: %d von %d Schrägstrecken/Zenitwinkeln mit Zielhöhe 0 (z. B. Zielmarken ohne Reflektorhöhe)", zeroTarget, heightRows);
			else if (heightRows > 0)
				r.check(OK, "Zielhöhen: alle %d Schrägstrecken/Zenitwinkel mit Zielhöhe", heightRows);
			// ---------- 5. Einheiten und Groessenordnungen
			if (!zenithValues.isEmpty()) {
				java.util.Collections.sort(zenithValues);
				double median = zenithValues.get(zenithValues.size() / 2);
				double faceOne = median > 200.0 ? 400.0 - median : median;
				if (faceOne > 84.0 && faceOne < 94.5)
					r.check(WARN, "Einheiten: Zenitwinkel im Median %s gon - wurden Grad als gon importiert?", String.format(Locale.GERMANY, "%.2f", faceOne));
				else
					r.check(OK, "Einheiten: Zenitwinkel im Median %s gon", String.format(Locale.GERMANY, "%.2f", faceOne));
			}
			int nonPositive = 0, longLines = 0;
			for (Object[] o : terrestrial)
				if ((Integer) o[1] == slopeType || (Integer) o[1] == ObservationType.HORIZONTAL_DISTANCE.getId()) {
					if (!((Double) o[6] > 0.0))
						nonPositive++;
					else if ((Double) o[6] > 5000.0)
						longLines++;
				}
			if (nonPositive > 0)
				r.check(WARN, "Strecken: %d Strecke(n) mit Länge 0 oder negativ", nonPositive);
			if (longLines > 0)
				r.check(HINT, "Strecken: %d Strecke(n) über 5 km - Einheit (mm statt m?) prüfen", longLines);
			List<String> zeroHeights = new ArrayList<String>();
			int newPoint = org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId();
			for (double[] p : points)
				if ((int) p[5] != newPoint && p[4] == 0.0 && (int) p[1] != 2)
					zeroHeights.add(names.getOrDefault((int) p[0], "#" + (int) p[0]));
			if (!zeroHeights.isEmpty()) {
				r.check(HINT, "Höhen: %d Fest-/Datumspunkt(e) mit Höhe 0", zeroHeights.size());
				r.detail(this.list(zeroHeights));
			}
			// ---------- 6. Systeme
			try {
				int mixed = Klein_Method.this.conversionPart.mixedGeocentricBaselines();
				if (mixed > 0)
					r.check(WARN, "Koordinatensystem: %d geozentrische 3D-Basislinie(n) neben lokalen Punkten - Umrechnung ausführen", mixed);
				else if (Klein_Method.this.conversionPart.hasGeocentricData())
					r.check(HINT, "Koordinatensystem: geozentrische Daten - die Umrechnung ins lokale System wird beim Tab-Klick angeboten");
				else if (!Klein_Method.this.conversionPart.hasGnssBaselines() && Klein_Method.this.conversionPart.hasGeocentricPoints())
					r.check(HINT, "Koordinatensystem: geozentrische Punkte, aber keine GNSS-Basislinien - keine Umrechnung, Projektion und Reduktion bleiben unverändert");
				else
					r.check(OK, "Koordinatensystem: einheitlich");
			} catch (Exception e) {
				// ohne Systempruefung
			}
			try {
				Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check c = Klein_Method.this.checkHeightSystems();
				if ((c.verdict == 1 || c.verdict == 2) && Double.isFinite(c.difference) && Math.abs(c.difference) > 1.0)
					r.check(WARN, "Höhensystem: GNSS-Höhe minus Nivellementshöhe %s an %d Punkten (Quasigeoid N %s) - Höhenfestpunkte prüfen", this.linear(c.difference),
							c.common, Double.isFinite(c.modelUndulation) ? this.linear(c.modelUndulation) : "-");
				else if (c.verdict == 0)
					r.check(OK, "Höhensystem: GNSS und Nivellement passen zusammen (%d Punkte)", c.common);
			} catch (Exception e) {
				// ohne Hoehenpruefung
			}
			// ---------- 6b. P0 weit ausserhalb des Netzes (z. B. Projekt mit Einstellungen frueherer Daten)
			try {
				Klein_Method_Functions.Klein_Method_Local_System.Parameters lp = Klein_Method_Functions.Klein_Method_Local_System.Parameters
						.decode(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_LOCAL_SYSTEM));
				if (lp != null) {
					List<Double> es = new ArrayList<Double>(), ns = new ArrayList<Double>();
					for (double[] p : points)
						if ((int) p[1] >= 2 && (int) p[5] == newPoint && !(p[2] == 0.0 && p[3] == 0.0) && Math.abs(p[2]) < 1.0e6 && Math.abs(p[3]) < 1.0e7
								&& !((int) p[1] == 3 && Klein_Method_Functions.Klein_Method_Local_System.isEcef(p[3], p[2], p[4]))) {
							es.add(p[2]);
							ns.add(p[3]);
						}
					if (es.size() >= 3) {
						double d = Math.hypot(Klein_Method_Functions.Klein_Method_Swarm.median(es) - lp.addEast, Klein_Method_Functions.Klein_Method_Swarm.median(ns) - lp.addNorth);
						if (d > 50000.0)
							r.check(WARN, "P0 liegt %s km außerhalb des Netzes - Projekt mit Einstellungen früherer Daten? Neues Projekt anlegen oder „P0 ins Netz legen“",
									String.format(Locale.GERMANY, "%.0f", d / 1000.0));
					}
				}
			} catch (Exception e) {
				// ohne P0-Pruefung
			}
			// ---------- 7. Lage der 1D-Punkte
			Map<String, Integer> shared = this.sharedPositions(points, names);
			int placeholders = 0;
			for (double[] p : points)
				if ((int) p[1] == 1 && this.isPlaceholder(p, shared))
					placeholders++;
			r.derivable = this.deriveOneDimensionalPositions(false);
			// reines Nivellementnetz (keine 2D/3D-Punkte): die Lage der 1D-Punkte spielt keine Rolle
			boolean spatial = false;
			for (double[] p : points)
				spatial |= (int) p[1] >= 2;
			if (placeholders > 0 && spatial)
				r.check(HINT, "1D-Punkte: %d ohne eigene Lage (0/0 oder mehrere an derselben Stelle); für %d Neupunkte lässt sich die Lage aus dem Nivellement ableiten",
						placeholders, r.derivable);
			// ---------- 8. Lage der 1D-Punkte gegen die Zielweiten des Nivellements
			this.checkLevelingPositions(r, points, names, shared, terrestrial);
			r.uiLines.add(String.format(tr("Ergebnis: %d rot, %d gelb."), r.warnings, r.hints));
			r.protocolLines.add(String.format("Ergebnis: %d rot, %d gelb", r.warnings, r.hints));
			return r;
		}

		/** Anzahl verschiedener Punkte je Lage (1D) - Aufstellungen "Praefix_X" zaehlen als X, liegen also nicht "mehrfach" dort. */
		private Map<String, Integer> sharedPositions(List<double[]> points, Map<Integer, String> names) {
			Map<String, Set<String>> bases = new java.util.HashMap<String, Set<String>>();
			for (double[] p : points)
				if ((int) p[1] == 1) {
					String n = names.getOrDefault((int) p[0], "#" + (int) p[0]);
					int u = n.indexOf('_');
					bases.computeIfAbsent(Math.round(p[2] * 1000) + "/" + Math.round(p[3] * 1000), k -> new HashSet<String>())
							.add(u > 0 && u < n.length() - 1 ? n.substring(u + 1) : n);
				}
			Map<String, Integer> shared = new java.util.HashMap<String, Integer>();
			for (Map.Entry<String, Set<String>> e : bases.entrySet())
				shared.put(e.getKey(), e.getValue().size());
			return shared;
		}

		/**
		 * Lage der 1D-Punkte passt nicht zum Nivellement: Ueber die Zielweiten ist der Weg zu Nachbarpunkten mit eigener
		 * Lage bekannt (obere Grenze fuer den Abstand). Liegt ein Punkt deutlich weiter weg als dieser Weg, steht seine Lage
		 * in einem anderen System (z. B. mit einem anderen P0 umgerechnet) - dann ist meist auch seine Hoehe falsch.
		 */
		private void checkLevelingPositions(Report r, List<double[]> points, Map<Integer, String> names, Map<String, Integer> shared, List<Object[]> terrestrial) {
			Map<String, double[]> own = new java.util.HashMap<String, double[]>(); // Name -> {y, x, Dimension, Punktart}
			for (double[] p : points) {
				String n = names.get((int) p[0]);
				if (n == null || (p[2] == 0.0 && p[3] == 0.0) || ((int) p[1] == 1 && this.isPlaceholder(p, shared)))
					continue;
				own.put(n, new double[] { p[2], p[3], p[1], p[5] });
			}
			Map<String, Map<String, Double>> edges = new java.util.HashMap<String, Map<String, Double>>();
			int leveling = ObservationType.LEVELING.getId();
			for (Object[] o : terrestrial)
				if ((Integer) o[1] == leveling && (Double) o[7] > 0.0) {
					String a = (String) o[2], b = (String) o[3];
					double d = (Double) o[7];
					edges.computeIfAbsent(a, k -> new java.util.HashMap<String, Double>()).merge(b, d, Math::min);
					edges.computeIfAbsent(b, k -> new java.util.HashMap<String, Double>()).merge(a, d, Math::min);
				}
			if (edges.isEmpty())
				return;
			int newPoint = org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId();
			List<String> fixed = new ArrayList<String>(), loose = new ArrayList<String>();
			int checked = 0;
			for (Map.Entry<String, double[]> start : own.entrySet()) {
				if ((int) start.getValue()[2] != 1 || !edges.containsKey(start.getKey()))
					continue;
				// kuerzeste Wege ueber das Nivellement bis 10 km zu Punkten mit eigener Lage; 3D-Punkte (GNSS/Tachymeter) haben
				// Vorrang - andere Hoehenfestpunkte koennen mit demselben falschen P0 umgerechnet sein und passen dann
				// untereinander scheinbar
				Map<String, Double> best = new java.util.HashMap<String, Double>();
				java.util.PriorityQueue<Object[]> queue = new java.util.PriorityQueue<Object[]>((x, y) -> Double.compare((Double) x[1], (Double) y[1]));
				queue.add(new Object[] { start.getKey(), 0.0 });
				List<double[]> spatial = new ArrayList<double[]>(), oneD = new ArrayList<double[]>(); // {Abstand, Weg}
				while (!queue.isEmpty() && spatial.size() < 4 && spatial.size() + oneD.size() < 12) {
					Object[] e = queue.poll();
					String n = (String) e[0];
					double l = (Double) e[1];
					if (best.containsKey(n))
						continue;
					best.put(n, l);
					double[] q = own.get(n);
					// Vergleichspunkte: 3D-Punkte und Hoehenfestpunkte - 1D-Neupunkte haben oft nur abgeleitete Lagen
					if (q != null && !n.equals(start.getKey()) && !((int) q[2] == 1 && (int) q[3] == newPoint)) {
						double[] s = start.getValue();
						if (Math.abs(q[0] - s[0]) < 1.0e6 && Math.abs(q[1] - s[1]) < 1.0e6) // gleiche Koordinatenart
							((int) q[2] == 1 ? oneD : spatial).add(new double[] { Math.hypot(q[0] - s[0], q[1] - s[1]), l });
					}
					for (Map.Entry<String, Double> next : edges.getOrDefault(n, java.util.Collections.<String, Double>emptyMap()).entrySet())
						if (!best.containsKey(next.getKey()) && l + next.getValue() <= 10000.0)
							queue.add(new Object[] { next.getKey(), l + next.getValue() });
				}
				List<double[]> used = spatial.isEmpty() ? oneD : spatial;
				int anchors = used.size(), contradictions = 0;
				double worst = 0.0, worstPath = 0.0;
				for (double[] a : used)
					if (a[0] > 1.2 * a[1] + 50.0) {
						contradictions++;
						if (a[0] - a[1] > worst - worstPath) {
							worst = a[0];
							worstPath = a[1];
						}
					}
				if (anchors == 0)
					continue;
				checked++;
				if (contradictions * 2 > anchors) {
					String text = String.format(Locale.GERMANY, "%s (%.0f m statt höchstens %.0f m)", start.getKey(), worst, worstPath);
					if ((int) start.getValue()[3] == newPoint)
						loose.add(text);
					else
						fixed.add(text);
				}
			}
			if (!fixed.isEmpty()) {
				r.check(WARN, "Lage der Höhenfestpunkte: %d Punkt(e) liegen weiter von ihren Nivellement-Nachbarn entfernt, als die Zielweiten erlauben - Lage (und meist auch Höhe) in einem anderen System oder mit einem anderen P0 umgerechnet?", fixed.size());
				r.detail(this.list(fixed));
			}
			if (!loose.isEmpty()) {
				r.check(HINT, "Lage der 1D-Neupunkte: %d Punkt(e) passen nicht zu den Zielweiten des Nivellements (Lage ableiten lassen oder prüfen)", loose.size());
				r.detail(this.list(loose));
			}
			if (fixed.isEmpty() && loose.isEmpty() && checked > 0)
				r.check(OK, "Lage der 1D-Punkte: %d Punkte passen zu den Zielweiten des Nivellements", checked);
		}

		private boolean isPlaceholder(double[] p, Map<String, Integer> shared) {
			return (p[2] == 0.0 && p[3] == 0.0) || shared.getOrDefault(Math.round(p[2] * 1000) + "/" + Math.round(p[3] * 1000), 0) >= 3;
		}

		/**
		 * Lage der 1D-Neupunkte ohne eigene Lage aus dem Nivellement: Mitte der ueber das Nivellement verbundenen Punkte
		 * (iterativ, Anker = Punkte mit eigener Lage). Hoehe bleibt.
		 * write = false: nur zaehlen. Liefert die Anzahl (ableitbarer bzw. gesetzter) Punkte. DB offen.
		 */
		int deriveOneDimensionalPositions(boolean write) throws SQLException {
			Map<Integer, String> names = Klein_Method.this.databaseHandler.readPointNames();
			List<double[]> points = Klein_Method.this.databaseHandler.readLocalConversionPoints();
			Map<String, Integer> shared = this.sharedPositions(points, names);
			int newPoint = org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId();
			Map<String, double[]> anchor = new java.util.HashMap<String, double[]>();
			Map<String, double[]> free = new LinkedHashMap<String, double[]>(); // Name -> {id, y, x, z}
			for (double[] p : points) {
				String n = names.get((int) p[0]);
				if (n == null)
					continue;
				if ((int) p[1] == 1 && (int) p[5] == newPoint && this.isPlaceholder(p, shared))
					free.put(n, new double[] { p[0], p[2], p[3], p[4] });
				else if (!(p[2] == 0.0 && p[3] == 0.0) && ((int) p[1] >= 2 || !this.isPlaceholder(p, shared)))
					anchor.put(n, new double[] { p[2], p[3] });
			}
			if (free.isEmpty())
				return 0;
			Map<String, Set<String>> neighbours = new java.util.HashMap<String, Set<String>>();
			for (Object[] o : Klein_Method.this.databaseHandler.readActiveRepeatableObservations())
				if ((Integer) o[1] == ObservationType.LEVELING.getId()) {
					neighbours.computeIfAbsent((String) o[2], k -> new HashSet<String>()).add((String) o[3]);
					neighbours.computeIfAbsent((String) o[3], k -> new HashSet<String>()).add((String) o[2]);
				}
			// jeder Punkt ohne Lage in die Mitte seiner Nivellement-Nachbarn (Aufstellpunkt zwischen Rueck- und Vorblick,
			// Wechselpunkt zwischen den Aufstellungen) - Gauss-Seidel bis 1 mm, ergibt eine gleichmaessige Verteilung entlang
			// der Linie zwischen den Punkten mit eigener Lage
			Map<String, double[]> position = new java.util.HashMap<String, double[]>();
			for (int iteration = 0; iteration < 20000; iteration++) {
				double change = 0.0;
				for (String n : free.keySet()) {
					double sy = 0, sx = 0;
					int k = 0;
					for (String m : neighbours.getOrDefault(n, java.util.Collections.<String>emptySet())) {
						double[] q = anchor.containsKey(m) ? anchor.get(m) : position.get(m);
						if (q == null)
							continue;
						sy += q[0];
						sx += q[1];
						k++;
					}
					if (k == 0)
						continue;
					double[] old = position.get(n), next = { sy / k, sx / k };
					change = Math.max(change, old == null ? Double.MAX_VALUE : Math.hypot(old[0] - next[0], old[1] - next[1]));
					position.put(n, next);
				}
				if (change < 1.0e-3)
					break;
			}
			if (!write)
				return position.size();
			Map<Integer, double[]> update = new LinkedHashMap<Integer, double[]>();
			for (Map.Entry<String, double[]> e : position.entrySet()) {
				double[] f = free.get(e.getKey());
				update.put((int) f[0], new double[] { Math.round(e.getValue()[0] * 1.0e4) / 1.0e4, Math.round(e.getValue()[1] * 1.0e4) / 1.0e4, f[3] });
			}
			if (update.isEmpty())
				return 0;
			Klein_Method.this.databaseHandler.updateLocalCoordinates(update, new LinkedHashMap<Integer, double[]>());
			Klein_Method.this.databaseHandler.commit();
			Klein_Method.this.logger.log("Klein_Method: Lage von " + update.size() + " 1D-Neupunkten aus dem Nivellement abgeleitet.");
			Klein_Method.this.protocol.logEvent("IMPORTBERICHT: Lage von " + update.size() + " 1D-Neupunkten aus dem Nivellement abgeleitet (Hoehe unveraendert)");
			Klein_Method.this.refreshJag3dView(true);
			return update.size();
		}

		/**
		 * Tab-Klick (Sperre beim Aufrufer): 1D-Neupunkte ohne eigene Lage bekommen die Lage aus dem Nivellement - nur in
		 * Projekten mit 2D/3D-Punkten (reines Nivellement: Lage ohne Bedeutung).
		 */
		void deriveOnTabClick() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				boolean spatial = false;
				for (double[] p : Klein_Method.this.databaseHandler.readLocalConversionPoints())
					spatial |= (int) p[1] >= 2;
				if (spatial)
					this.deriveOneDimensionalPositions(true);
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Lage der 1D-Punkte nicht abgeleitet: " + e.getMessage());
			}
		}

		/** Bericht ins Protokoll und als Textdatei; liefert die Datei oder null. */
		private File save(Report r) {
			for (String line : r.protocolLines)
				Klein_Method.this.protocol.logEvent(line);
			try {
				String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
				File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
				if (!folder.exists())
					folder.mkdirs();
				File file = new File(folder, timestamp + "_Importbericht.txt");
				try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
					writer.print(String.join(System.lineSeparator(), r.uiLines));
				}
				return file;
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Importbericht nicht gespeichert: " + e.getMessage());
				return null;
			}
		}

		/** Datenstand (Anzahl Punkte/Beobachtungen und Summen der Hoehen) - neuer Import = neuer Stand. */
		private String fingerprint() throws SQLException {
			return Klein_Method.this.databaseHandler.readImportFingerprint();
		}

		/** Tab-Klick (Sperre beim Aufrufer): bei neuem Datenstand pruefen; mit roten Punkten Fenster zeigen. */
		void checkOnTabClick() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				String fp = this.fingerprint();
				if (fp.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_FINGERPRINT)))
					return;
				Report r = this.compute();
				File file = this.save(r);
				Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_FINGERPRINT, fp);
				Klein_Method.this.logger.log("Klein_Method: Importbericht - " + r.warnings + " rot, " + r.hints + " gelb" + (file == null ? "" : " - " + file.getName()));
				if (r.level == WARN)
					this.show(r, file);
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Importbericht nicht moeglich: " + e.getMessage());
			}
		}

		void openFromMenu() {
			Thread thread = busyThread(() -> {
				ADJUSTMENT_LOCK.lock();
				try {
					Report r = this.compute();
					this.show(r, this.save(r));
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Importbericht nicht moeglich: " + e.getMessage());
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
			}, "Klein_Method_Import_Report");
			thread.setDaemon(true);
			thread.start();
		}

		/** Fenster (JavaFX-Thread); bei ableitbaren 1D-Lagen mit Knopf zum Uebernehmen. */
		private void show(Report r, File file) {
			String text = String.join("\n", r.uiLines) + (file == null ? "" : "\n\n" + String.format(tr("Gespeichert: %s"), file.getAbsolutePath()));
			Platform.runLater(() -> {
				ButtonType derive = new ButtonType(String.format(tr("Lage von %d 1D-Neupunkten aus dem Nivellement übernehmen"), r.derivable),
						javafx.scene.control.ButtonBar.ButtonData.LEFT);
				Alert alert = new Alert(r.level == WARN ? AlertType.WARNING : AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Importbericht"));
				alert.setHeaderText(r.level == WARN ? tr("Rote Punkte in den Daten - bitte vor der Ausgleichung prüfen")
						: r.level == HINT ? tr("Daten plausibel, einzelne Hinweise") : tr("Daten plausibel"));
				TextArea area = new TextArea(text);
				area.setEditable(false);
				area.setWrapText(true);
				area.setPrefRowCount(Math.min(26, 4 + r.uiLines.size() * 2));
				area.setPrefColumnCount(95);
				alert.getDialogPane().setContent(area);
				alert.setResizable(true);
				alert.getButtonTypes().setAll(ButtonType.OK);
				if (r.derivable > 0)
					alert.getButtonTypes().add(0, derive);
				java.util.Optional<ButtonType> answer = alert.showAndWait();
				if (answer.isPresent() && answer.get() == derive) {
					Thread thread = busyThread(() -> {
						try {
							int n = Klein_Method.this.deriveOneDimensionalPositions();
							Klein_Method.this.showApproximationInfo(String.format(tr("Lage von %d 1D-Neupunkten aus dem Nivellement übernommen (Höhe unverändert)."), n));
						} catch (Exception e) {
							Klein_Method.this.logger.log("Klein_Method: 1D-Lagen nicht abgeleitet: " + e.getMessage());
						}
					}, "Klein_Method_Derive_1D");
					thread.setDaemon(true);
					thread.start();
				}
			});
		}
	}

	private final Gross_Error_Precheck_Part grossErrorPrecheckPart = new Gross_Error_Precheck_Part();

	/** Menue "Vorpruefung grober Fehler": Bericht im Fenster und im Protokoll (Hintergrund, aendert keine Daten). */
	public void runGrossErrorPrecheckFromMenu() {
		Thread thread = busyThread(() -> {
			String text;
			try {
				Gross_Error_Precheck_Part.Result result = this.grossErrorPrecheckPart.run();
				this.grossErrorPrecheckPart.log(result);
				text = String.join("\n", result.uiLines);
			} catch (Exception e) {
				this.logger.log("Klein_Method: Vorpruefung grober Fehler fehlgeschlagen: " + e.getMessage());
				text = String.format(tr("Vorprüfung nicht möglich: %s"), e.getMessage());
			}
			final String finalText = text;
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Vorprüfung grober Fehler"));
				alert.setHeaderText(tr("Schleifenschlüsse und Doppelmessungen (vor der Ausgleichung)"));
				TextArea area = new TextArea(finalText);
				area.setEditable(false);
				area.setWrapText(true);
				area.setPrefRowCount(Math.min(24, 4 + finalText.split("\n").length));
				area.setPrefColumnCount(90);
				alert.getDialogPane().setContent(area);
				alert.setResizable(true);
				alert.show();
			});
		}, "Klein_Method_Gross_Error_Precheck");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Vorpruefung grober Fehler VOR der Ausgleichung - dort verschmiert ein grober Fehler noch nicht auf die Nachbarn:
	 * GNSS-Schleifenschluesse (Dreiecke aus den aktiven Basislinien, je Punktpaar der Median), einzelne Basislinien weit
	 * weg vom Median ihres Punktpaars, Doppelmessungen (Strecken mit gleichen Hoehen, Nivellement hin/zurueck).
	 * Grenze = Grenze grober Fehler; bei Dreiecken zusaetzlich 1 ppm des Umfangs. Aendert keine Daten.
	 */
	private final class Gross_Error_Precheck_Part {

		/** hoechstens so viele Dreiecke auswerten (grosse Netze). */
		private static final int MAX_TRIANGLES = 200000;
		/** verdaechtiges Punktpaar: mindestens so viele auffaellige Dreiecke und dieser Anteil seiner Dreiecke. */
		private static final int SUSPECT_MIN_FLAGGED = 2;
		private static final double SUSPECT_SHARE = 0.6;
		private static final int MAX_LISTED = 10;

		final class Result {
			final List<String> uiLines = new ArrayList<String>(), protocolLines = new ArrayList<String>();
			/** verdaechtige Punktpaare (pairKey). */
			final Set<String> suspectPairs = new HashSet<String>();
			/** auffaellige Einzelbeobachtungen: "G"+Id (GNSS) bzw. "T"+Id (terrestrisch). */
			final Set<String> suspectIds = new HashSet<String>();
			int vectors, pairs, triangles, flaggedTriangles, singleOutliers, repeatGroups, flaggedRepeats;
			/** Warnungen vor der Bereinigung (Zielhoehen, Hoehensystem) - Oberflaeche. */
			final List<String> warnings = new ArrayList<String>();

			void line(String german, Object... args) {
				this.uiLines.add(String.format(Locale.GERMANY, tr(german), args));
				this.protocolLines.add(String.format(Locale.GERMANY, german, args));
			}

			boolean isSuspect(Max_Error_Row row) {
				// auffaellige Punktpaare stammen aus GNSS-Schleifen - sie machen nur GNSS-Basislinien verdaechtig, nicht die
				// Tachymetermessungen zwischen denselben Punkten
				return (row.gnss && this.suspectPairs.contains(pairKey(row.startPointName, row.endPointName)))
						|| this.suspectIds.contains((row.gnss ? "G" : "T") + row.observationId);
			}

			String summary() {
				return String.format(Locale.GERMANY, tr("Vorprüfung: %d von %d GNSS-Dreiecken über der Toleranz, %d verdächtige Punktpaare, "
						+ "%d Basislinien weit weg vom Mittel ihres Punktpaars, %d von %d Doppelmessungen auffällig."), this.flaggedTriangles, this.triangles,
						this.suspectPairs.size(), this.singleOutliers, this.flaggedRepeats, this.repeatGroups);
			}
		}

		String pairKey(String a, String b) {
			return a.compareTo(b) <= 0 ? a + "\u0001" + b : b + "\u0001" + a;
		}

		private String linear(double meter) {
			return String.format(Locale.GERMANY, "%.3f m", meter);
		}

		private double[] oriented(Map<String, double[]> median, String from, String to) {
			double[] v = median.get(this.pairKey(from, to));
			return from.compareTo(to) < 0 ? v : new double[] { -v[0], -v[1], -v[2] };
		}

		private double norm(double[] v) {
			return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		}

		Result run() throws SQLException {
			double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
			Result r = new Result();
			// ---------- GNSS: je Punktpaar (Richtung a -> b mit a < b) alle aktiven Basislinien
			Map<String, List<double[]>> byPair = new LinkedHashMap<String, List<double[]>>();
			for (Object[] v : Klein_Method.this.databaseHandler.readActiveGnssVectors()) {
				String s = (String) v[1], e = (String) v[2];
				if (s == null || e == null || s.equals(e))
					continue;
				r.vectors++;
				double sign = s.compareTo(e) < 0 ? 1.0 : -1.0;
				byPair.computeIfAbsent(this.pairKey(s, e), k -> new ArrayList<double[]>())
						.add(new double[] { sign * (Double) v[3], sign * (Double) v[4], sign * (Double) v[5], (Integer) v[0] });
			}
			r.pairs = byPair.size();
			Map<String, double[]> median = new java.util.HashMap<String, double[]>();
			Map<String, Set<String>> neighbours = new java.util.HashMap<String, Set<String>>();
			List<Object[]> singles = new ArrayList<Object[]>();
			for (Map.Entry<String, List<double[]>> entry : byPair.entrySet()) {
				List<double[]> list = entry.getValue();
				double[] m = new double[3];
				for (int k = 0; k < 3; k++) {
					double[] values = new double[list.size()];
					for (int i = 0; i < values.length; i++)
						values[i] = list.get(i)[k];
					java.util.Arrays.sort(values);
					m[k] = values.length % 2 == 1 ? values[values.length / 2] : 0.5 * (values[values.length / 2 - 1] + values[values.length / 2]);
				}
				median.put(entry.getKey(), m);
				String[] ab = entry.getKey().split("\u0001", -1);
				neighbours.computeIfAbsent(ab[0], k -> new HashSet<String>()).add(ab[1]);
				neighbours.computeIfAbsent(ab[1], k -> new HashSet<String>()).add(ab[0]);
				if (list.size() < 3)
					continue;
				for (double[] v : list) {
					double d = this.norm(new double[] { v[0] - m[0], v[1] - m[1], v[2] - m[2] });
					if (d > limit) {
						r.singleOutliers++;
						r.suspectIds.add("G" + (int) v[3]);
						singles.add(new Object[] { ab[0], ab[1], d, list.size() });
					}
				}
			}
			// ---------- Dreiecke
			Map<String, int[]> pairStat = new java.util.HashMap<String, int[]>();
			List<Object[]> flagged = new ArrayList<Object[]>();
			outer: for (Map.Entry<String, Set<String>> na : neighbours.entrySet()) {
				String a = na.getKey();
				for (String b : na.getValue()) {
					if (a.compareTo(b) >= 0)
						continue;
					for (String c : neighbours.get(b)) {
						if (b.compareTo(c) >= 0 || !na.getValue().contains(c))
							continue;
						double[] vab = this.oriented(median, a, b), vbc = this.oriented(median, b, c), vca = this.oriented(median, c, a);
						double w = this.norm(new double[] { vab[0] + vbc[0] + vca[0], vab[1] + vbc[1] + vca[1], vab[2] + vbc[2] + vca[2] });
						double tolerance = limit + 1.0E-6 * (this.norm(vab) + this.norm(vbc) + this.norm(vca));
						boolean bad = w > tolerance;
						r.triangles++;
						for (String key : new String[] { this.pairKey(a, b), this.pairKey(b, c), this.pairKey(c, a) }) {
							int[] stat = pairStat.computeIfAbsent(key, k -> new int[2]);
							stat[1]++;
							if (bad)
								stat[0]++;
						}
						if (bad) {
							r.flaggedTriangles++;
							flagged.add(new Object[] { a, b, c, w, tolerance });
						}
						if (r.triangles >= MAX_TRIANGLES)
							break outer;
					}
				}
			}
			List<Map.Entry<String, int[]>> suspects = new ArrayList<Map.Entry<String, int[]>>();
			for (Map.Entry<String, int[]> e : pairStat.entrySet())
				if (e.getValue()[0] >= SUSPECT_MIN_FLAGGED && e.getValue()[0] >= SUSPECT_SHARE * e.getValue()[1])
					suspects.add(e);
			suspects.sort((x, y) -> {
				int c = Double.compare((double) y.getValue()[0] / y.getValue()[1], (double) x.getValue()[0] / x.getValue()[1]);
				return c != 0 ? c : Integer.compare(y.getValue()[0], x.getValue()[0]);
			});
			for (Map.Entry<String, int[]> e : suspects)
				r.suspectPairs.add(e.getKey());
			flagged.sort((x, y) -> Double.compare((Double) y[3], (Double) x[3]));
			singles.sort((x, y) -> Double.compare((Double) y[2], (Double) x[2]));
			// ---------- Doppelmessungen
			Map<String, List<double[]>> repeats = new LinkedHashMap<String, List<double[]>>();
			Map<String, String> repeatNames = new java.util.HashMap<String, String>();
			for (Object[] o : Klein_Method.this.databaseHandler.readActiveRepeatableObservations()) {
				int type = (Integer) o[1];
				String s = (String) o[2], e = (String) o[3];
				if (s == null || e == null || s.equals(e))
					continue;
				double value = (Double) o[4];
				long ih = Math.round((Double) o[5] * 1000.0), th = Math.round((Double) o[6] * 1000.0);
				String key, kind;
				if (type == ObservationType.LEVELING.getId()) {
					key = "L" + this.pairKey(s, e);
					value = s.compareTo(e) < 0 ? value : -value;
					kind = "Nivellement";
				} else if (type == ObservationType.HORIZONTAL_DISTANCE.getId()) {
					key = "H" + this.pairKey(s, e);
					kind = "Horizontalstrecke";
				} else {
					// Schraegstrecke nur bei gleichen Instrument-/Reflektorhoehen vergleichbar (Gegenrichtung: Hoehen getauscht)
					key = s.compareTo(e) < 0 ? "S" + s + "\u0001" + e + "\u0001" + ih + "\u0001" + th : "S" + e + "\u0001" + s + "\u0001" + th + "\u0001" + ih;
					kind = "Schrägstrecke";
				}
				repeats.computeIfAbsent(key, k -> new ArrayList<double[]>()).add(new double[] { value, (Integer) o[0] });
				repeatNames.put(key, kind + "\u0001" + (s.compareTo(e) < 0 ? s + " - " + e : e + " - " + s));
			}
			List<Object[]> repeatFlags = new ArrayList<Object[]>();
			for (Map.Entry<String, List<double[]>> e : repeats.entrySet()) {
				List<double[]> list = e.getValue();
				if (list.size() < 2)
					continue;
				r.repeatGroups++;
				double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
				for (double[] v : list) {
					min = Math.min(min, v[0]);
					max = Math.max(max, v[0]);
				}
				if (max - min > limit) {
					r.flaggedRepeats++;
					for (double[] v : list)
						r.suspectIds.add("T" + (int) v[1]);
					String[] name = repeatNames.get(e.getKey()).split("\u0001", -1);
					repeatFlags.add(new Object[] { name[0], name[1], list.size(), max - min });
				}
			}
			repeatFlags.sort((x, y) -> Double.compare((Double) y[3], (Double) x[3]));
			// ---------- Zielhoehen von Schraegstrecken und Zenitwinkeln: fehlen sie (0 bei gesetzter Instrumentenhoehe), zielt
			// jeder Zenitwinkel um die Zielhoehe zu tief - das sieht in der Ausgleichung aus wie viele grobe Fehler
			int heightRows = 0, zeroTarget = 0;
			Map<String, double[]> targetRange = new LinkedHashMap<String, double[]>();
			for (Object[] o : Klein_Method.this.databaseHandler.readActiveTargetHeights()) {
				double ih = (Double) o[4], th = (Double) o[5];
				heightRows++;
				if (th == 0.0 && ih > 0.0)
					zeroTarget++;
				double[] range = targetRange.computeIfAbsent(o[2] + " - " + o[3], k -> new double[] { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY });
				range[0] = Math.min(range[0], th);
				range[1] = Math.max(range[1], th);
			}
			List<String> varying = new ArrayList<String>();
			for (Map.Entry<String, double[]> e : targetRange.entrySet())
				if (e.getValue()[1] - e.getValue()[0] > 0.05)
					varying.add(String.format(Locale.GERMANY, "%s: %.3f / %.3f m", e.getKey(), e.getValue()[0], e.getValue()[1]));
			// fast alle (Zielmarken ohne Reflektorhoehe sind in Teilen eines Netzes normal)
			boolean targetsMissing = zeroTarget >= 10 && zeroTarget >= 0.9 * heightRows;
			// ---------- Hoehensystem (Hoehenfestpunkte gegen GNSS)
			String heightWarning = null, heightWarningProtocol = null;
			try {
				Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check c = Klein_Method.this.checkHeightSystems();
				if ((c.verdict == 1 || c.verdict == 2) && Double.isFinite(c.difference) && Math.abs(c.difference) > 1.0) {
					String n = Double.isFinite(c.modelUndulation) ? this.linear(c.modelUndulation) : "-";
					heightWarning = String.format(tr("Höhensysteme passen nicht zusammen: GNSS-Höhe minus Nivellementshöhe %s (Quasigeoid N %s) an %d Punkten. "
							+ "Höhenfestpunkte prüfen, bevor grobe Fehler bereinigt werden - als Datumspunkte verschieben und verbiegen sie das Netz in der Höhe."),
							this.linear(c.difference), n, c.common);
					heightWarningProtocol = String.format(Locale.GERMANY, "Hoehensysteme passen nicht zusammen: GNSS-Hoehe minus Nivellementshoehe %s (N %s) an %d Punkten",
							this.linear(c.difference), n, c.common);
				}
			} catch (Exception e) {
				// ohne Hoehenpruefung weiter
			}
			// ---------- Bericht
			r.line("VORPRÜFUNG GROBER FEHLER (vor der Ausgleichung, Grenze %s)", this.linear(limit));
			r.line("GNSS: %d aktive Basislinien, %d Punktpaare, %d Dreiecke, davon %d über der Toleranz (Grenze + 1 ppm des Umfangs).",
					r.vectors, r.pairs, r.triangles, r.flaggedTriangles);
			if (!suspects.isEmpty()) {
				r.line("Verdächtige Punktpaare (in den meisten ihrer Dreiecke auffällig):");
				for (int i = 0; i < Math.min(MAX_LISTED, suspects.size()); i++) {
					String[] ab = suspects.get(i).getKey().split("\u0001", -1);
					r.line("  %s - %s: %d von %d Dreiecken auffällig", ab[0], ab[1], suspects.get(i).getValue()[0], suspects.get(i).getValue()[1]);
				}
			}
			if (!flagged.isEmpty()) {
				r.line("Größte Schleifenschlüsse:");
				for (int i = 0; i < Math.min(MAX_LISTED, flagged.size()); i++) {
					Object[] f = flagged.get(i);
					r.line("  %s - %s - %s: %s (Toleranz %s)", f[0], f[1], f[2], this.linear((Double) f[3]), this.linear((Double) f[4]));
				}
			}
			if (!singles.isEmpty()) {
				r.line("Einzelne Basislinien weit weg vom Median ihres Punktpaars: %d", r.singleOutliers);
				for (int i = 0; i < Math.min(MAX_LISTED, singles.size()); i++) {
					Object[] f = singles.get(i);
					r.line("  %s - %s: %s Abstand zum Median (%d Basislinien)", f[0], f[1], this.linear((Double) f[2]), f[3]);
				}
			}
			r.line("Doppelmessungen: %d Gruppen (Strecken mit gleichen Höhen, Nivellement hin/zurück), davon %d mit Spannweite über der Grenze.",
					r.repeatGroups, r.flaggedRepeats);
			for (int i = 0; i < Math.min(MAX_LISTED, repeatFlags.size()); i++) {
				Object[] f = repeatFlags.get(i);
				String kind = (String) f[0];
				String kindUi = "Nivellement".equals(kind) ? tr("Nivellement") : "Horizontalstrecke".equals(kind) ? tr("Horizontalstrecke") : tr("Schrägstrecke");
				r.uiLines.add(String.format(Locale.GERMANY, tr("  %s %s: %d Messungen, Spannweite %s"), kindUi, f[1], f[2], this.linear((Double) f[3])));
				r.protocolLines.add(String.format(Locale.GERMANY, "  %s %s: %d Messungen, Spannweite %s", kind, f[1], f[2], this.linear((Double) f[3])));
			}
			r.line("Zielhöhen: %d Schrägstrecken/Zenitwinkel, davon %d mit Zielhöhe 0 bei gesetzter Instrumentenhöhe; %d Punktpaare mit wechselnder Zielhöhe.",
					heightRows, zeroTarget, varying.size());
			if (targetsMissing) {
				r.line("ACHTUNG: Die Zielhöhen fehlen vermutlich im Import (Zielhöhe 0). Jeder Zenitwinkel zielt dann um die Zielhöhe zu tief - "
						+ "das erscheint als sehr viele grobe Fehler. Bitte die Messdaten mit Zielhöhen neu importieren.");
				r.warnings.add(String.format(tr("Zielhöhen fehlen vermutlich: %d von %d Schrägstrecken/Zenitwinkeln haben Zielhöhe 0 bei gesetzter Instrumentenhöhe. "
						+ "Jeder Zenitwinkel zielt dann um die Zielhöhe (typisch 1,3–1,7 m) zu tief - das erscheint als sehr viele grobe Fehler. "
						+ "Bitte die Messdaten mit Zielhöhen neu importieren, statt zu bereinigen."), zeroTarget, heightRows));
			}
			for (int i = 0; i < Math.min(MAX_LISTED, varying.size()); i++)
				r.line("  wechselnde Zielhöhe %s", varying.get(i));
			if (heightWarning != null) {
				r.uiLines.add(heightWarning);
				r.protocolLines.add(heightWarningProtocol);
				r.warnings.add(heightWarning);
			}
			if (r.flaggedTriangles == 0 && r.singleOutliers == 0 && r.flaggedRepeats == 0 && !targetsMissing && heightWarning == null)
				r.line("Keine Auffälligkeiten.");
			else
				r.line("Hinweis: Die Vorprüfung ändert nichts. Auffälliges zuerst im Feldbuch prüfen (Punktname, Antennen-/Instrumentenhöhe); „Grobe Fehler bereinigen“ markiert diese Beobachtungen.");
			return r;
		}

		void log(Result r) {
			for (String line : r.protocolLines)
				Klein_Method.this.protocol.logEvent(line);
			Klein_Method.this.logger.log("Klein_Method: Vorpruefung grober Fehler - " + r.flaggedTriangles + "/" + r.triangles + " Dreiecke, " + r.suspectPairs.size()
					+ " verdaechtige Punktpaare, " + r.singleOutliers + " Einzelbasislinien, " + r.flaggedRepeats + "/" + r.repeatGroups + " Doppelmessungen auffaellig.");
		}
	}

	public void openMeasurementPlan() {
		this.measurementPlanPart.openFromMenu();
	}

	private final class Measurement_Plan_Part {

		/** Kandidaten: gleich gut, wenn ihr Gewinn mindestens diesen Anteil des besten erreicht. */
		private static final double EQUIVALENT_SHARE = 0.9;
		/** Einstufung der Prognose: gelb ab diesem Vielfachen des Medians, rot ab RED_FACTOR. */
		private static final double YELLOW_FACTOR = 1.2, RED_FACTOR = 1.5;
		/** Genauigkeit einer guten Sitzung (kurze Basislinie) [m]: Lage, Hoehe. */
		private static final double SHORT_SIGMA_POSITION = 0.005, SHORT_SIGMA_HEIGHT = 0.010;

		void openFromMenu() {
			if (!Klein_Method.this.isProjectOpen() || Klein_Method.this.approximationBusy()
					|| Klein_Method.this.isAutomaticTaskRunning()) {
				Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet bzw. gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			List<Object[]> stations;
			try {
				stations = Klein_Method.this.databaseHandler.readGnssStationUsage(15);
			} catch (SQLException e) {
				Klein_Method.this.showApproximationInfo(tr("Prüfung nicht möglich: ") + e.getMessage());
				return;
			}
			if (stations.isEmpty()) {
				Klein_Method.this.showApproximationInfo(tr("Keine aktiven GNSS-Basislinien im Projekt - für die Messplanung wird eine GNSS-Station als Bezug benötigt."));
				return;
			}
			javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Prognose und Messplanung"));
			dialog.setHeaderText(tr("Welche zusätzliche GNSS-Sitzung bringt am meisten?"));
			javafx.scene.control.ComboBox<String> reference = new javafx.scene.control.ComboBox<String>();
			for (Object[] st : stations)
				reference.getItems().add(st[0] + " (" + st[1] + ")");
			reference.setValue(reference.getItems().get(0));
			reference.setTooltip(new javafx.scene.control.Tooltip(tr("Station, von der die gedachte neue Sitzung ausgeht (in Klammern: Anzahl aktiver Basislinien). "
					+ "Voreinstellung: die meistgenutzte Station.")));
			javafx.scene.control.ComboBox<String> accuracy = new javafx.scene.control.ComboBox<String>();
			accuracy.getItems().addAll(tr("Gute Sitzung, kurze Basislinie (5 mm Lage, 10 mm Höhe)"), tr("Wie die bisherigen Sitzungen (Median)"));
			accuracy.setValue(accuracy.getItems().get(0));
			accuracy.setTooltip(new javafx.scene.control.Tooltip(tr("Genauigkeit der gedachten Sitzung. Lange Basislinien zu fernen Stationen sind oft viel "
					+ "ungenauer (1 ppm sind bei 30 km schon 30 mm) - „Wie die bisherigen Sitzungen“ zeigt, was eine weitere Sitzung dieser Art bringt.")));
			javafx.scene.control.ComboBox<Integer> count = new javafx.scene.control.ComboBox<Integer>();
			count.getItems().addAll(5, 10, 15, 20, 30);
			count.setValue(10);
			count.setTooltip(new javafx.scene.control.Tooltip(tr("Anzahl der geprüften Kandidaten (schwächste Punkte mit höchstens einer GNSS-Basislinie). "
					+ "Je Kandidat eine Ausgleichung, also einige Sekunden.")));
			javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
			grid.setHgap(8);
			grid.setVgap(8);
			grid.add(new javafx.scene.control.Label(tr("Bezugsstation:")), 0, 0);
			grid.add(reference, 1, 0);
			grid.add(new javafx.scene.control.Label(tr("Genauigkeit der Sitzung:")), 0, 1);
			grid.add(accuracy, 1, 1);
			grid.add(new javafx.scene.control.Label(tr("Kandidaten:")), 0, 2);
			grid.add(count, 1, 2);
			dialog.getDialogPane().setContent(grid);
			ButtonType start = new ButtonType(tr("Berechnen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
			dialog.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
			if (!dialog.showAndWait().filter(b -> b == start).isPresent())
				return;
			int selected = Math.max(0, reference.getItems().indexOf(reference.getValue()));
			String station = (String) stations.get(selected)[0];
			int group = (Integer) stations.get(selected)[2];
			boolean shortBaseline = accuracy.getItems().indexOf(accuracy.getValue()) <= 0;
			int candidates = count.getValue() == null ? 10 : count.getValue();
			Klein_Method.this.showApproximationInfo(tr("Messplanung läuft im Hintergrund (je Kandidat eine Ausgleichung) - das Ergebnis erscheint anschließend."));
			Thread thread = busyThread(() -> {
				String text;
				ADJUSTMENT_LOCK.lock();
				try {
					text = this.compute(station, group, shortBaseline, candidates);
				} catch (Exception e) {
					text = tr("Prüfung nicht möglich: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				Klein_Method.this.showApproximationInfo(text);
			}, "Klein_Method_Measurement_Plan");
			thread.setDaemon(true);
			thread.start();
		}

		/**
		 * Relative Prognose [m] je Punkt: Die Ellipsen der freien Ausgleichung enthalten die Unsicherheit der Lagerung, die
		 * allen Punkten gemeinsam ist (ohne Kalibrierung bis in den Meterbereich). Massgeblich fuer das Wegdriften ist der
		 * Anteil darueber: sqrt(e^2 - e_min^2) mit e_min = Ellipse des am besten bestimmten Neupunkts. Index 1 = relativ,
		 * Index 7 = Ellipse wie von JAG3D.
		 */
		private Map<String, Object[]> prognosis() throws SQLException {
			Map<String, Object[]> m = new java.util.LinkedHashMap<String, Object[]>();
			double min = Double.MAX_VALUE;
			for (Object[] r : Klein_Method.this.databaseHandler.readPointPrognosis())
				if ((Double) r[1] > 0) {
					m.put((String) r[0], java.util.Arrays.copyOf(r, 8));
					min = Math.min(min, (Double) r[1]);
				}
			for (Object[] r : m.values()) {
				double e = (Double) r[1];
				r[7] = e;
				r[1] = Math.sqrt(Math.max(0.0, e * e - min * min));
			}
			return m;
		}

		private double mean(Map<String, Object[]> m) {
			double s = 0;
			for (Object[] r : m.values())
				s += (Double) r[1];
			return m.isEmpty() ? 0 : s / m.size();
		}

		/** Berechnung (Sperre beim Aufrufer); Ergebnistext, zusaetzlich als Datei im Protokollordner. */
		String compute(String station, int group, boolean shortBaseline, int candidates) throws Exception {
			EstimationStateType state = Klein_Method.this.runHeadlessAdjustmentSynchronously();
			if (state != EstimationStateType.ERROR_FREE_ESTIMATION)
				return String.format(tr("Die Ausgleichung rechnet nicht (%s) - Prognose und Messplanung brauchen ein gültiges Ergebnis."), String.valueOf(state));
			Map<String, Object[]> base = this.prognosis();
			if (base.isEmpty())
				return tr("Keine Neupunkte mit Fehlerellipse - nichts zu prognostizieren.");
			List<Double> sorted = new ArrayList<Double>();
			for (Object[] r : base.values())
				sorted.add((Double) r[1]);
			java.util.Collections.sort(sorted);
			double median = sorted.get(sorted.size() / 2);
			Map<String, Integer> levels = new java.util.HashMap<String, Integer>();
			List<Map.Entry<String, Object[]>> ranked = new ArrayList<Map.Entry<String, Object[]>>(base.entrySet());
			ranked.sort((a, b) -> Double.compare((Double) b.getValue()[1], (Double) a.getValue()[1]));
			StringBuilder t = new StringBuilder();
			if (!"1".equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_AUTOMATIC_STARTED)))
				t.append(tr("HINWEIS: Die Automatik ist in diesem Projekt noch nicht gelaufen - die Genauigkeiten sind nicht kalibriert, die Werte nur vorläufig. "
						+ "Nach der Automatik erneut aufrufen.")).append("\n\n");
			t.append(tr("PROGNOSE (relative Fehlerellipse: Anteil über dem am besten bestimmten Neupunkt, aus der aktuellen Ausgleichung; gelb ab 1,2-fach, "
					+ "rot ab 1,5-fach des Medians):")).append("\n");
			t.append(String.format(tr("Median %s mm, Mittel %s mm, %d Punkte."), mm(median), mm(this.mean(base)), base.size())).append("\n");
			int listed = 0;
			for (Map.Entry<String, Object[]> e : ranked) {
				double ellipse = (Double) e.getValue()[1];
				int level = ellipse >= RED_FACTOR * median ? 2 : ellipse >= YELLOW_FACTOR * median ? 1 : 0;
				if (level > 0)
					levels.put(e.getKey(), level);
				if (listed < 10) {
					t.append(String.format("  %-12s %7s mm (%s mm)  %s  GNSS %d", e.getKey(), mm(ellipse), mm((Double) e.getValue()[7]), level == 2 ? tr("rot") : level == 1 ? tr("gelb") : "",
							(Integer) e.getValue()[6])).append("\n");
					listed++;
				}
			}
			// Kandidaten: schwaechste Punkte mit hoechstens einer aktiven GNSS-Basislinie
			double[] ref = Klein_Method.this.databaseHandler.readAdjustedPoint(station);
			double[] sigma;
			if (shortBaseline)
				sigma = new double[] { SHORT_SIGMA_POSITION, SHORT_SIGMA_POSITION, SHORT_SIGMA_HEIGHT };
			else {
				double[] med = Klein_Method.this.databaseHandler.readActiveGnssSigmaMedian();
				sigma = new double[] { med[0], med[0], med[1] };
			}
			t.append("\n").append(String.format(tr("MESSPLANUNG: je Kandidat eine gedachte GNSS-Sitzung %s → Punkt (Genauigkeit Lage %s mm, Höhe %s mm), danach Ausgleichung:"),
					station, sigma[0] > 0 ? mm(sigma[0]) : tr("Gruppe"), sigma[2] > 0 ? mm(sigma[2]) : tr("Gruppe"))).append("\n");
			List<double[]> gains = new ArrayList<double[]>();
			List<String> names = new ArrayList<String>();
			if (ref == null)
				t.append(String.format(tr("Station %s hat keine ausgeglichenen Koordinaten - Messplanung nicht möglich."), station)).append("\n");
			else {
				for (Map.Entry<String, Object[]> e : ranked) {
					if (names.size() >= candidates)
						break;
					if ((Integer) e.getValue()[6] > 1 || e.getKey().equals(station))
						continue;
					Object[] r = e.getValue();
					double[] vector = { (Double) r[3] - ref[0], (Double) r[4] - ref[1], (Double) r[5] - ref[2] };
					int id = Klein_Method.this.databaseHandler.insertVirtualGnssBaseline(group, station, e.getKey(), vector, sigma);
					Map<String, Object[]> after;
					EstimationStateType s2;
					try {
						s2 = Klein_Method.this.runHeadlessAdjustmentSynchronously();
						after = this.prognosis();
					} finally {
						Klein_Method.this.databaseHandler.deleteGnssObservation(id);
					}
					if (s2 != EstimationStateType.ERROR_FREE_ESTIMATION || after.get(e.getKey()) == null)
						continue;
					int better = 0;
					double worst = 0;
					for (Map.Entry<String, Object[]> a : after.entrySet()) {
						Object[] b = base.get(a.getKey());
						if (b != null && (Double) a.getValue()[1] < 0.8 * (Double) b[1])
							better++;
						worst = Math.max(worst, (Double) a.getValue()[1]);
					}
					names.add(e.getKey());
					gains.add(new double[] { (Double) r[1], (Double) after.get(e.getKey())[1], this.mean(base) - this.mean(after), better, worst });
				}
				// Ergebnis mit den echten Beobachtungen wiederherstellen
				Klein_Method.this.runHeadlessAdjustmentSynchronously();
			}
			if (names.isEmpty())
				t.append(tr("Keine geeigneten Kandidaten (alle schwachen Punkte haben schon mehrere GNSS-Basislinien).")).append("\n");
			else {
				Integer[] order = new Integer[names.size()];
				for (int k = 0; k < order.length; k++)
					order[k] = k;
				java.util.Arrays.sort(order, (a, b) -> Double.compare(gains.get(b)[2], gains.get(a)[2]));
				double best = gains.get(order[0])[2];
				List<String> equivalent = new ArrayList<String>();
				for (int k : order) {
					double[] g = gains.get(k);
					t.append(String.format(tr("  %-12s eigene Ellipse %s → %s mm | Mittel aller Punkte −%s mm | %d Punkte ≥ 20 %% besser | schlechtester danach %s mm"),
							names.get(k), mm(g[0]), mm(g[1]), mm(g[2]), (int) g[3], mm(g[4]))).append("\n");
					if (best > 0 && g[2] >= EQUIVALENT_SHARE * best)
						equivalent.add(names.get(k));
				}
				t.append("\n").append(tr("EMPFEHLUNG:")).append(" ");
				double[] top = gains.get(order[0]);
				if (top[3] == 0 && top[1] > 0.9 * top[0])
					t.append(tr("Eine weitere Sitzung dieser Genauigkeit bringt praktisch nichts - genauere Sitzungen (kurze Basislinie, längere Beobachtung) prüfen."));
				else if (equivalent.size() > 1)
					t.append(String.format(tr("Eine Sitzung auf einem dieser Punkte bringt praktisch denselben Gewinn - den am besten zugänglichen wählen: %s."), String.join(", ", equivalent)));
				else
					t.append(String.format(tr("Eine Sitzung auf %s bringt am meisten."), equivalent.isEmpty() ? names.get(order[0]) : equivalent.get(0)));
				t.append("\n");
			}
			// Nachmess-Liste: deaktivierte und sehr unsichere Sitzungsmittel
			List<Object[]> means = Klein_Method.this.databaseHandler.readGnssSessionMeans();
			if (!means.isEmpty()) {
				List<Double> sig = new ArrayList<Double>();
				for (Object[] m : means)
					if ((Boolean) m[4] && (Double) m[2] > 0)
						sig.add((Double) m[2]);
				java.util.Collections.sort(sig);
				double medianSigma = sig.isEmpty() ? 0 : sig.get(sig.size() / 2);
				StringBuilder remeasure = new StringBuilder();
				int n = 0;
				for (Object[] m : means) {
					boolean off = !(Boolean) m[4];
					boolean uncertain = medianSigma > 0 && (Double) m[2] > 2 * medianSigma;
					if ((off || uncertain) && n < 15) {
						remeasure.append(String.format("  %s - %s: %s", m[0], m[1], off ? tr("Sitzung deaktiviert (grober Fehler)")
								: String.format(tr("sehr unsicher (Lage %s mm, Median %s mm)"), mm((Double) m[2]), mm(medianSigma)))).append("\n");
						n++;
					}
				}
				t.append("\n").append(tr("NACHMESSEN (Sitzungsmittel deaktiviert oder mehr als doppelt so unsicher wie der Median):")).append("\n");
				t.append(n == 0 ? "  " + tr("keine") + "\n" : remeasure.toString());
			}
			Klein_Method_Tab_Builder.Klein_Method_Map_Window.prognosisLevels = levels;
			Platform.runLater(Klein_Method_Tab_Builder.Klein_Method_Map_Window::notifyDataChanged);
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
			if (!folder.exists())
				folder.mkdirs();
			File file = new File(folder, timestamp + "_Messplanung.txt");
			try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
				writer.print(t.toString());
			}
			Klein_Method.this.logger.log("Klein_Method: Prognose/Messplanung: Median Ellipse " + mm(median) + " mm, " + levels.size() + " Punkte gelb/rot, "
					+ names.size() + " Kandidaten gerechnet (Bezug " + station + ") - " + file.getName());
			Klein_Method.this.getProtocol().logEvent("MESSPLANUNG: " + names.size() + " Kandidaten, Bezug " + station + ", Datei " + file.getName());
			return t.append("\n").append(String.format(tr("Gespeichert: %s"), file.getAbsolutePath())).toString();
		}

		private String mm(double meter) {
			return String.format(Locale.GERMANY, "%.1f", meter * 1000);
		}
	}

	// ============================================================================================================
	/**
	 * Ablauf-Assistent: die 7 Schritte vom Import bis zur Freigabe der Automatik, jeder mit Ampel, Begruendung und - wo
	 * moeglich - einer Aktion. Neu bewertet beim Tab-Klick und nach jeder Aktion; rote Schritte werden beim Start ohne
	 * Rueckfrage in Log und Protokoll vermerkt. Dazu das Zustandsprotokoll des Projekts (was wurde wann umgerechnet/geaendert).
	 */
	/**
	 * Statistischer Test vor der Einzelkalibrierung. Die Einzelkalibrierung der Automatik erhoeht die Genauigkeit
	 * auffaelliger Beobachtungen, bis kein Test mehr anspricht - kleine grobe Fehler (unter der Grenze in Metern)
	 * werden so still abgewichtet. Deshalb: beim Automatik-Start die Ausgangsgenauigkeiten merken, nach der
	 * Gruppenkalibrierung einmal mit diesen Ausgangsgenauigkeiten ausgleichen, die JAG3D-Testwerte (Signifikanz,
	 * geschaetzter Fehler, kleinster aufdeckbarer Fehler) speichern und danach den Lauf unveraendert fortsetzen.
	 */
	private final class Statistical_Test_Part {
		private volatile boolean pending = false;

		void captureOrigin() {
			this.pending = false;
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				int n = Klein_Method.this.databaseHandler.captureOriginSigmas();
				this.pending = true;
				if (n > 0)
					Klein_Method.this.logger.log("Klein_Method: statistischer Test - Ausgangsgenauigkeiten von " + n + " Beobachtungen gemerkt.");
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Ausgangsgenauigkeiten nicht gemerkt: " + e.getMessage());
			}
		}

		/**
		 * Uebergang Gruppen- -> Einzelkalibrierung: nur den Stand fuer den Test merken (Gruppengenauigkeiten; die
		 * Ausgangsgenauigkeiten sind seit dem Start gemerkt). Der Test selbst laeuft nicht mehr im Lauf, sondern auf Knopfdruck
		 * ("Statistischer Test ..."), damit die Automatik nicht unterbrochen wird.
		 */
		void snapshot(List<String> details) {
			if (!this.pending)
				return;
			this.pending = false;
			try {
				Klein_Method.this.databaseHandler.storeStatisticalState();
				String line = "STATISTISCHER TEST: Stand nach der Gruppenkalibrierung gemerkt (Test auf Knopfdruck \"Statistischer Test ...\")";
				if (details != null)
					details.add(line);
				Klein_Method.this.logger.log("Klein_Method: " + line);
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Stand fuer den statistischen Test nicht gemerkt: " + e.getMessage());
			}
		}

		/** Test mit den gerade in der Datenbank stehenden Genauigkeiten ausgleichen und die Befunde speichern (Sperre beim Aufrufer). */
		private void test(String label) throws Exception {
			EstimationStateType result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
			double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
			List<Max_Error_Row> found = new ArrayList<Max_Error_Row>();
			int significant = 0, small = 0, undetectable = 0;
			if (result == EstimationStateType.ERROR_FREE_ESTIMATION) {
				Set<String> accepted = new HashSet<String>();
				for (Max_Error_Row entry : Klein_Method.this.databaseHandler.readGrossErrorEntries())
					if (Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED.equals(entry.status))
						accepted.add(entry.key());
				for (Max_Error_Row row : Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE)) {
					if (accepted.contains(row.key()))
						continue;
					if (row.significant) {
						found.add(row);
						significant++;
						if (Double.isNaN(row.linearError) || row.linearError <= limit)
							small++;
					} else if (!Double.isNaN(row.mdbLinear) && row.mdbLinear > limit && undetectable < Klein_Method_Default_Values.STATISTICAL_TEST_MAX_UNDETECTABLE) {
						found.add(row);
						undetectable++;
					}
				}
			}
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			Klein_Method.this.databaseHandler.storeStatisticalSnapshot(found, stamp);
			String line = result != EstimationStateType.ERROR_FREE_ESTIMATION ? "STATISTISCHER TEST: Ausgleichung mit Ausgangsgenauigkeiten nicht fehlerfrei (" + result + ")"
					: String.format(Locale.ROOT, "STATISTISCHER TEST (" + label + "): %d signifikant, davon %d unter der Grenze %s; "
							+ "%d mit kleinstem aufdeckbaren Fehler ueber der Grenze", significant, small, formatLinear(limit), undetectable);
			Klein_Method.this.protocol.logEvent(line);
			int shown = 0;
			for (Max_Error_Row row : found)
				if (row.significant && shown++ < 30)
					Klein_Method.this.protocol.logEvent("  signifikant: " + describeObservation(row) + " | Fehler ≈ " + formatLinear(row.linearError)
							+ String.format(Locale.ROOT, " | |logPost| %.1f | r %.0f %%", row.value, 100.0 * row.redundancy));
			Klein_Method.this.logger.log("Klein_Method: " + line);
		}

		/**
		 * Separate Funktion "Statistischer Test ...": Genauigkeiten auf den gemerkten Stand (Ausgangsgenauigkeiten,
		 * Gruppen nach der Gruppenkalibrierung) setzen - ohne gemerkten Stand die aktuellen -, testen, Befunde speichern,
		 * danach den aktuellen Stand exakt wiederherstellen und neu ausgleichen. Bericht danach im Fenster.
		 */
		void runOnDemand() {
			Thread thread = busyThread(() -> {
				String error = null;
				ADJUSTMENT_LOCK.lock();
				Map<Long, double[]> current = null;
				List<double[]> groups = null;
				boolean wasValid = Klein_Method.this.isValidAdjustmentResultsAvailable();
				try {
					if (!Klein_Method.this.isProjectOpen())
						error = tr("Kein Projekt geöffnet.");
					else if (Klein_Method.this.isAutomaticTaskRunning() || Klein_Method.this.grossErrorCleanupRunning.get())
						error = tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen.");
					else {
						current = Klein_Method.this.databaseHandler.readCurrentSigmas();
						groups = Klein_Method.this.databaseHandler.readAllGroupUncertainties();
						boolean state = Klein_Method.this.databaseHandler.hasStatisticalState();
						if (state)
							Klein_Method.this.databaseHandler.restoreStatisticalState();
						else
							Klein_Method.this.databaseHandler.storeStatisticalState();
						Klein_Method.this.setValidAdjustmentResultsAvailable(false);
						this.test(state ? "nach der Gruppenkalibrierung, Ausgangsgenauigkeiten" : "aktuelle Genauigkeiten, noch keine Automatik");
					}
				} catch (Exception e) {
					error = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
					Klein_Method.this.logger.log("Klein_Method: statistischer Test fehlgeschlagen: " + e.getMessage());
				} finally {
					try {
						if (current != null) {
							Klein_Method.this.databaseHandler.writeSigmas(current);
							Klein_Method.this.databaseHandler.writeAllGroupUncertainties(groups);
							Klein_Method.this.databaseHandler.commit();
							EstimationStateType again = Klein_Method.this.runHeadlessAdjustmentSynchronously();
							Klein_Method.this.setValidAdjustmentResultsAvailable(wasValid && again == EstimationStateType.ERROR_FREE_ESTIMATION);
							Klein_Method.this.acceptOwnChangesIntoChecksum("Statistischer Test (Stand wiederhergestellt)");
						}
					} catch (Exception e) {
						Klein_Method.this.logger.log("Klein_Method: Genauigkeiten nach dem statistischen Test nicht zurueckgeschrieben: " + e.getMessage());
					} finally {
						ADJUSTMENT_LOCK.unlock();
					}
				}
				if (error != null) {
					Klein_Method.this.showApproximationInfo(error);
					return;
				}
				this.openReport(false);
			}, "Klein_Method_Statistical_Test");
			thread.setDaemon(true);
			thread.start();
		}

		/** Befunde: {signifikant gesamt, davon noch aktiv, nicht aufdeckbar}. */
		int[] counts() {
			int[] c = new int[3];
			try {
				for (Object[] r : Klein_Method.this.databaseHandler.readStatisticalSnapshot())
					if ((Boolean) r[9]) {
						c[0]++;
						if ((Boolean) r[13])
							c[1]++;
					} else
						c[2]++;
			} catch (Exception e) {
				// ohne Befunde
			}
			return c;
		}

		private String sigmaText(int typeId, double sigma) {
			ObservationType type = ObservationType.getEnumByValue(typeId);
			if (!(sigma > 0.0))
				return tr("Gruppenwert");
			if (type == ObservationType.DIRECTION || type == ObservationType.ZENITH_ANGLE)
				return String.format(Locale.GERMANY, "%.2f mgon", sigma * 200000.0 / Math.PI);
			return String.format(Locale.GERMANY, "%.1f mm", sigma * 1000.0);
		}

		/** Empfehlung des letzten Berichts: REPORT_CLOSE, REPORT_CLEAN oder REPORT_GROUPS. */
		volatile int lastRecommendation = REPORT_CLOSE;
		static final int REPORT_CLOSE = 0, REPORT_CLEAN = 1, REPORT_GROUPS = 2;
		/** Haeufung: ab so vielen Auffaelligkeiten an einem Punkt und mindestens diesem Anteil wird ein gemeinsamer Fehler vermutet. */
		static final int CLUSTER_MIN = 3;
		static final double CLUSTER_SHARE = 0.4;

		String reportText() throws SQLException {
			List<Object[]> rows = Klein_Method.this.databaseHandler.readStatisticalSnapshot();
			this.lastRecommendation = REPORT_CLOSE;
			double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
			StringBuilder t = new StringBuilder();
			if (rows.isEmpty())
				return tr("Keine statistisch auffälligen Beobachtungen gespeichert. Der Test läuft auf Knopfdruck („Statistischer Test …“) mit dem Stand nach der Gruppenkalibrierung (Ausgangsgenauigkeiten).");
			t.append(String.format(tr("Test vom %s nach der Gruppenkalibrierung, mit den Ausgangsgenauigkeiten (vor der Einzelkalibrierung)."), rows.get(0)[10])).append("\n\n");
			int sig = 0, small = 0, absorbed = 0, open = 0;
			Map<String, Integer> perPoint = new java.util.LinkedHashMap<String, Integer>();
			StringBuilder list = new StringBuilder(), undetect = new StringBuilder();
			int undetectCount = 0;
			for (Object[] r : rows) {
				Max_Error_Row row = new Max_Error_Row(ObservationType.getEnumByValue((Integer) r[2]), (String) r[3], (String) r[4], (Double) r[5], 0.0, (Integer) r[1],
						(Integer) r[0] == 1, null, (Double) r[6], (Double) r[8]);
				String name = Klein_Method_Tab_Builder.Klein_Method_Map_Window.observationTypeName(row.observationType) + " " + row.startPointName + " – " + row.endPointName;
				if ((Boolean) r[9]) {
					sig++;
					double linear = (Double) r[6];
					if (Double.isNaN(linear) || linear <= limit)
						small++;
					double before = (Double) r[11], now = (Double) r[12];
					boolean weighted = now > 0.0 && (before > 0.0 ? now > 1.5 * before : true);
					if (weighted)
						absorbed++;
					boolean active = (Boolean) r[13];
					if (!weighted && active)
						open++;
					if (active) {
						perPoint.merge(row.startPointName, 1, Integer::sum);
						if (row.endPointName != null && !row.endPointName.equals(row.startPointName))
							perPoint.merge(row.endPointName, 1, Integer::sum);
					}
					list.append(!weighted && active ? "► " : "• ").append(name).append(": ").append(tr("Fehler")).append(" ≈ ")
							.append(Double.isNaN(linear) ? tr("nicht bestimmbar") : String.format(Locale.GERMANY, "%.1f cm", linear * 100.0))
							.append(String.format(Locale.GERMANY, ", |logPost| %.1f, r %.0f %%", (Double) r[5], 100.0 * (Double) r[8]));
					if (weighted)
						list.append(", ").append(String.format(tr("von der Automatik abgewichtet: σ %s → %s"), this.sigmaText((Integer) r[2], before),
								this.sigmaText((Integer) r[2], now)));
					if (!active)
						list.append(" (").append(tr("deaktiviert")).append(")");
					else if (!weighted)
						list.append(" – ").append(tr("nicht abgewichtet, wirkt mit vollem Gewicht"));
					list.append("\n");
				} else {
					if (undetectCount++ < 15)
						undetect.append("• ").append(name).append(": ").append(tr("kleinster aufdeckbarer Fehler")).append(" ")
								.append(String.format(Locale.GERMANY, "%.1f cm", (Double) r[7] * 100.0)).append("\n");
				}
			}
			// Empfehlung: Haeufung an einem Punkt (gemeinsamer Fehler) vor nicht abgewichteten Einzelfehlern
			String cluster = null;
			int clusterCount = 0, activeSig = 0;
			for (Object[] r : rows)
				if ((Boolean) r[9] && (Boolean) r[13])
					activeSig++;
			for (Map.Entry<String, Integer> e : perPoint.entrySet())
				if (e.getValue() > clusterCount) {
					clusterCount = e.getValue();
					cluster = e.getKey();
				}
			StringBuilder advice = new StringBuilder();
			if (cluster != null && clusterCount >= CLUSTER_MIN && clusterCount >= CLUSTER_SHARE * activeSig) {
				this.lastRecommendation = REPORT_GROUPS;
				advice.append(String.format(tr("Empfehlung: Häufung an Punkt %s (%d von %d Auffälligkeiten) – eher ein gemeinsamer Fehler (Zentrierung, Instrumenten- "
						+ "oder Zielhöhe, exzentrisches Ziel) als Einzelfehler. Zuerst „Gruppentests …“ starten; einzelnes Bereinigen würde die Ursache nur verdecken."),
						cluster, clusterCount, activeSig));
			} else if (open > 0) {
				this.lastRecommendation = REPORT_CLEAN;
				advice.append(String.format(tr("Empfehlung: %d auffällige Beobachtung(en) hat die Automatik nicht abgewichtet (► in der Liste) – sie wirken mit vollem "
						+ "Gewicht. „Statistisch auffällige bereinigen …“ prüft sie mit Gegenprobe."), open));
			} else
				advice.append(tr("Empfehlung: Für die Koordinaten kein Handlungsbedarf – die Automatik hat alle auffälligen Beobachtungen bereits abgewichtet, "
						+ "eine Häufung an einem Punkt gibt es nicht. „Schließen“ genügt; Bereinigen würde sie nur noch ganz herausnehmen (Unterschied meist unter 1 mm)."));
			if (sig > 0)
				t.append(advice).append("\n\n");
			t.append(String.format(tr("%d Beobachtungen statistisch auffällig, davon %d unter der Grenze grober Fehler (%s) – die Bereinigung nach Grenze findet sie nicht; "
					+ "%d hat die Automatik danach abgewichtet."), sig, small, String.format(Locale.GERMANY, "%.2f m", limit), absorbed)).append("\n\n");
			if (sig > 0)
				t.append(list).append("\n");
			if (undetectCount > 0)
				t.append(String.format(tr("%d Beobachtungen sind kaum kontrolliert: Ein grober Fehler unter dem kleinsten aufdeckbaren Fehler (über der Grenze) bliebe unentdeckt – "
						+ "im Feld absichern oder zusätzlich messen:"), undetectCount)).append("\n").append(undetect).append(undetectCount > 15 ? "• …\n" : "").append("\n");
			t.append(tr("„Statistisch auffällige bereinigen …“ setzt die Genauigkeiten auf den Stand des Tests zurück und nimmt die auffälligen Beobachtungen schrittweise mit "
					+ "Gegenprobe heraus; du bestätigst jede einzeln. Danach die Automatik neu starten."));
			t.append("\n\n").append(tr("Fehler, die mehrere Beobachtungen gemeinsam betreffen (Prismen-/Additionskonstante, Instrumenten- oder Zielhöhe), "
					+ "sieht der Einzeltest oft nicht: in der Liste „Gruppentests“ starten."));
			return t.toString();
		}

		/** Bericht anzeigen (afterRun = nur bei signifikanten, noch aktiven Befunden). */
		void openReport(boolean afterRun) {
			Thread thread = busyThread(() -> {
				String text;
				int[] c;
				ADJUSTMENT_LOCK.lock();
				try {
					if (!Klein_Method.this.isProjectOpen())
						return;
					c = this.counts();
					if (afterRun && c[1] == 0)
						return;
					text = this.reportText();
				} catch (Exception e) {
					text = tr("Bericht nicht lesbar: ") + e.getMessage();
					c = new int[3];
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final String message = text;
				final boolean cleanable = c[1] > 0;
				final int recommendation = this.lastRecommendation;
				Platform.runLater(() -> {
					Alert alert = new Alert(cleanable && recommendation != REPORT_CLOSE ? AlertType.WARNING : AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Statistischer Test"));
					alert.setHeaderText(tr("Kleine grobe Fehler (statistischer Test vor der Einzelkalibrierung)"));
					javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(message);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(22);
					area.setPrefColumnCount(90);
					alert.getDialogPane().setContent(area);
					String recommended = " " + tr("(empfohlen)");
					ButtonType clean = new ButtonType(tr("Statistisch auffällige bereinigen …") + (recommendation == REPORT_CLEAN ? recommended : ""));
					ButtonType list = new ButtonType(tr("Liste öffnen …"));
					ButtonType groups = new ButtonType(tr("Gruppentests …") + (recommendation == REPORT_GROUPS ? recommended : ""));
					ButtonType close = new ButtonType(tr("Schließen") + (recommendation == REPORT_CLOSE ? recommended : ""), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					if (cleanable)
						alert.getButtonTypes().setAll(list, groups, clean, close);
					else
						alert.getButtonTypes().setAll(list, groups, close);
					// empfohlener Knopf = Standardknopf (Enter), die anderen nicht
					for (ButtonType type : alert.getButtonTypes()) {
						javafx.scene.Node node = alert.getDialogPane().lookupButton(type);
						if (node instanceof javafx.scene.control.Button)
							((javafx.scene.control.Button) node).setDefaultButton(type.getText().endsWith(recommended));
					}
					java.util.Optional<ButtonType> choice = alert.showAndWait();
					if (choice.filter(b -> b == clean).isPresent())
						this.askAndStartCleanup();
					else if (choice.filter(b -> b == groups).isPresent())
						Klein_Method.this.openFindingsListWithGroupTests();
					else if (choice.filter(b -> b == list).isPresent())
						Klein_Method.this.openFindingsList();
				});
			}, "Klein_Method_Statistical_Report");
			thread.setDaemon(true);
			thread.start();
		}

		void askAndStartCleanup() {
			Platform.runLater(() -> {
				if (Klein_Method.this.isAutomaticTaskRunning() || Klein_Method.this.grossErrorCleanupRunning.get()) {
					Klein_Method.this.showApproximationInfo(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
					return;
				}
				if (this.counts()[1] == 0) {
					Klein_Method.this.showApproximationInfo(tr("Keine statistisch auffälligen Beobachtungen offen - zuerst „Statistischer Test …“ rechnen."));
					return;
				}
				Alert alert = new Alert(AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL);
				alert.setTitle(tr("Klein_Method - Statistischer Test"));
				alert.setHeaderText(tr("Statistisch auffällige Beobachtungen bereinigen?"));
				alert.setContentText(tr("Die Genauigkeiten werden auf den Stand des Tests zurückgesetzt (Gruppen kalibriert, Einzelgenauigkeiten wie zu Beginn der Automatik). "
						+ "Dann nimmt Klein_Method die signifikanten Beobachtungen schrittweise mit Gegenprobe heraus; du bestätigst jede einzeln. "
						+ "Die Ergebnisse der Automatik sind danach ungültig - bitte die Automatik neu starten."));
				if (alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
					Klein_Method.this.logger.log("Klein_Method: statistisch auffaellige bereinigen gestartet.");
					Klein_Method.this.grossErrorPart.start(true);
				}
			});
		}
	}

	/** eine Zeile der Liste auffaelliger Beobachtungen (Befund des statistischen Tests). */
	public static final class Finding {
		public int kind, id, typeId;
		public String start, end, typeName, cause = "", edits = "";
		public double logPost, linear, mdb, redundancy, nabla, sigmaBefore, sigmaNow;
		public boolean significant, active;
		/** Gruppentest: geaendertes Feld (value_0, instrument_height, reflector_height) und Mitglieder; null bei Einzelbefunden. */
		public String groupField = null;
		public int[] members = new int[0];

		public boolean group() {
			return this.groupField != null;
		}
		public boolean gnss() {
			return this.kind == 1;
		}
		public boolean angle() {
			return this.typeId == ObservationType.DIRECTION.getId() || this.typeId == ObservationType.ZENITH_ANGLE.getId();
		}
		/** Status fuer die Anzeige. */
		public String status() {
			if (!this.active)
				return tr("deaktiviert");
			return this.edits.isEmpty() ? tr("offen") : tr("bearbeitet");
		}
	}

	/**
	 * Liste auffaelliger Beobachtungen: Befunde des statistischen Tests ansehen, Ursache vermuten, deaktivieren,
	 * Messwert um den geschaetzten Fehler korrigieren, Instrumenten-/Zielhoehe aendern, abwichten, einzeln oder ganz
	 * zuruecknehmen, neu testen. Jede Aenderung wird gemerkt ("KleinMethodFindingEdit") und im Zustandsprotokoll
	 * vermerkt; danach sind die Ergebnisse ungueltig.
	 */
	private final class Findings_Part {

		private String stamp() {
			return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		}

		/** Befunde lesen (Lock beim Aufrufer oder hier). */
		List<Finding> read() throws SQLException {
			List<Finding> list = new ArrayList<Finding>();
			ADJUSTMENT_LOCK.lock();
			try {
				if (!Klein_Method.this.isProjectOpen())
					return list;
				Map<String, StringBuilder> edits = new java.util.HashMap<String, StringBuilder>();
				for (Object[] e : Klein_Method.this.databaseHandler.readFindingEdits(-1, 0)) {
					StringBuilder sb = edits.computeIfAbsent(e[0] + ":" + e[1], k -> new StringBuilder());
					String name = this.fieldName((String) e[2]);
					if (sb.indexOf(name) < 0)
						sb.append(sb.length() > 0 ? ", " : "").append(name);
				}
				for (Object[] r : Klein_Method.this.databaseHandler.readStatisticalSnapshot()) {
					Finding f = new Finding();
					f.kind = (Integer) r[0];
					f.id = (Integer) r[1];
					f.typeId = (Integer) r[2];
					f.start = (String) r[3];
					f.end = (String) r[4];
					f.logPost = (Double) r[5];
					f.linear = (Double) r[6];
					f.mdb = (Double) r[7];
					f.redundancy = (Double) r[8];
					f.significant = (Boolean) r[9];
					f.sigmaBefore = (Double) r[11];
					f.sigmaNow = (Double) r[12];
					f.active = (Boolean) r[13];
					f.nabla = (Double) r[14];
					f.typeName = Klein_Method_Tab_Builder.Klein_Method_Map_Window.observationTypeName(ObservationType.getEnumByValue(f.typeId));
					StringBuilder e = edits.get(f.kind + ":" + f.id);
					f.edits = e == null ? "" : e.toString();
					list.add(f);
				}
				this.causes(list);
				list.addAll(Klein_Method.this.groupTestPart.findings(edits));
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			return list;
		}

		private String fieldName(String field) {
			switch (field) {
			case "enable": return tr("Aktivierung");
			case "value_0": return tr("Messwert");
			case "instrument_height": return tr("Instrumentenhöhe");
			case "reflector_height": return tr("Zielhöhe");
			default: return tr("Genauigkeit");
			}
		}

		/** vermutete Ursachen aus Haeufungen signifikanter Befunde mit gleichem Vorzeichen. */
		void causes(List<Finding> list) {
			int zenith = ObservationType.ZENITH_ANGLE.getId(), leveling = ObservationType.LEVELING.getId(), direction = ObservationType.DIRECTION.getId();
			int slope = ObservationType.SLOPE_DISTANCE.getId(), horizontal = ObservationType.HORIZONTAL_DISTANCE.getId();
			Map<String, List<Finding>> groups = new java.util.LinkedHashMap<String, List<Finding>>();
			for (Finding f : list) {
				if (!f.significant || f.gnss() || Double.isNaN(f.nabla))
					continue;
				String sign = f.nabla >= 0 ? "+" : "-";
				if (f.typeId == zenith || f.typeId == leveling) {
					groups.computeIfAbsent("S|" + f.start + "|" + sign, k -> new ArrayList<Finding>()).add(f);
					groups.computeIfAbsent("T|" + f.end + "|" + sign, k -> new ArrayList<Finding>()).add(f);
				} else if (f.typeId == slope || f.typeId == horizontal)
					groups.computeIfAbsent("P|" + f.end + "|" + sign, k -> new ArrayList<Finding>()).add(f);
				else if (f.typeId == direction)
					groups.computeIfAbsent("O|" + f.start + "|" + sign, k -> new ArrayList<Finding>()).add(f);
			}
			for (Map.Entry<String, List<Finding>> g : groups.entrySet()) {
				if (g.getValue().size() < 2)
					continue;
				String[] key = g.getKey().split("\\|");
				String text;
				if ("S".equals(key[0]))
					text = String.format(tr("Instrumentenhöhe an %s prüfen (%d Messungen, gleiches Vorzeichen)"), key[1], g.getValue().size());
				else if ("T".equals(key[0]))
					text = String.format(tr("Zielhöhe an %s prüfen (%d Messungen, gleiches Vorzeichen)"), key[1], g.getValue().size());
				else if ("P".equals(key[0])) {
					double mean = 0;
					for (Finding f : g.getValue())
						mean += f.nabla / g.getValue().size();
					text = String.format(tr("Prismen-/Additionskonstante am Ziel %s prüfen (%d Strecken, im Mittel %s mm)"), key[1], g.getValue().size(),
							String.format(Locale.GERMANY, "%+.1f", mean * 1000.0));
				} else
					text = String.format(tr("Orientierung bzw. Satzzuordnung an %s prüfen (%d Richtungen)"), key[1], g.getValue().size());
				for (Finding f : g.getValue())
					if (f.cause.isEmpty())
						f.cause = text;
			}
			// einzelne Strecke mit typischer Prismenkonstante
			for (Finding f : list)
				if (f.cause.isEmpty() && f.significant && !f.gnss() && (f.typeId == slope || f.typeId == horizontal) && !Double.isNaN(f.nabla))
					for (double c : new double[] { 0.0175, 0.0300, 0.0344 })
						if (Math.abs(Math.abs(f.nabla) - c) < 0.002) {
							f.cause = String.format(tr("Prismenkonstante verwechselt? (Fehler ≈ %s mm)"), String.format(Locale.GERMANY, "%.1f", c * 1000.0));
							break;
						}
		}

		private void changed(String what) {
			Klein_Method.this.acceptOwnChangesIntoChecksum("Liste auffaelliger Beobachtungen"); // eigene, gewollte Aenderung - kein Reihenfolgefehler
			Klein_Method.this.setValidAdjustmentResultsAvailable(false);
			Klein_Method.this.protocol.logEvent("AUFFAELLIGE BEOBACHTUNGEN: " + what);
			Klein_Method.this.logger.log("Klein_Method: Liste auffaelliger Beobachtungen - " + what);
		}

		private String describe(Finding f) {
			if (f.group())
				return "Gruppe " + f.typeName;
			return f.typeName + " " + f.start + " - " + f.end;
		}

		/** Aktion fuer die gewaehlten Zeilen: disable, enable, correct, downweight, undo. Rueckgabe: Ergebnistext. */
		String apply(List<Finding> rows, String action) throws SQLException {
			int n = 0, skipped = 0;
			String stamp = this.stamp();
			ADJUSTMENT_LOCK.lock();
			try {
				for (Finding f : rows) {
					if (f.group()) {
						int[] r = Klein_Method.this.groupTestPart.apply(f, action, stamp);
						n += r[0];
						skipped += r[1];
						continue;
					}
					switch (action) {
					case "disable":
					case "enable":
						Klein_Method.this.databaseHandler.editObservationField(f.kind, f.id, "enable", "enable".equals(action) ? 1.0 : 0.0, stamp);
						n++;
						break;
					case "correct":
						if (f.gnss() || Double.isNaN(f.nabla)) {
							skipped++;
							break;
						}
						double value = Klein_Method.this.databaseHandler.readObservationField(0, f.id, "value_0");
						Klein_Method.this.databaseHandler.editObservationField(0, f.id, "value_0", value - f.nabla, stamp);
						n++;
						break;
					case "downweight":
						if (f.gnss()) {
							double s = Double.isNaN(f.linear) ? 0.0 : f.linear;
							for (String field : new String[] { "sigma_y0", "sigma_x0", "sigma_z0" }) {
								double now = Klein_Method.this.databaseHandler.readObservationField(1, f.id, field);
								if (s > now)
									Klein_Method.this.databaseHandler.editObservationField(1, f.id, field, s, stamp);
							}
							n++;
						} else if (!Double.isNaN(f.nabla)) {
							double now = Klein_Method.this.databaseHandler.readObservationField(0, f.id, "sigma_0");
							double s = Math.abs(f.nabla);
							if (!(now >= s))
								Klein_Method.this.databaseHandler.editObservationField(0, f.id, "sigma_0", s, stamp);
							n++;
						} else
							skipped++;
						break;
					case "undo":
						n += Klein_Method.this.databaseHandler.undoFindingEdits(f.kind, f.id) > 0 ? 1 : 0;
						break;
					default:
						throw new IllegalArgumentException(action);
					}
				}
				Klein_Method.this.databaseHandler.commit();
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			String what = String.format(Locale.ROOT, "%s - %d Beobachtungen%s: %s", action, n, skipped > 0 ? " (" + skipped + " nicht moeglich)" : "",
					rows.size() <= 5 ? String.join("; ", rows.stream().map(this::describe).toArray(String[]::new)) : rows.size() + " gewaehlt");
			if (n > 0)
				this.changed(what);
			String label = "disable".equals(action) ? tr("deaktiviert") : "enable".equals(action) ? tr("wieder aktiviert")
					: "correct".equals(action) ? tr("um den geschätzten Fehler korrigiert") : "downweight".equals(action) ? tr("abgewichtet (σ = geschätzter Fehler)")
							: tr("zurückgenommen");
			return String.format(tr("%d Beobachtungen %s."), n, label) + (skipped > 0 ? " " + String.format(tr("%d nicht möglich (GNSS bzw. ohne geschätzten Fehler)."), skipped) : "");
		}

		/** Instrumenten- und Zielhoehe einer terrestrischen Beobachtung setzen (NaN = unveraendert). */
		String heights(Finding f, double instrument, double reflector) throws SQLException {
			if (f.group())
				return tr("Nur für einzelne Beobachtungen - bei Gruppen „Messwert korrigieren“ verwenden.");
			if (f.gnss())
				return tr("Bei GNSS-Basislinien nicht möglich.");
			String stamp = this.stamp();
			ADJUSTMENT_LOCK.lock();
			try {
				if (!Double.isNaN(instrument))
					Klein_Method.this.databaseHandler.editObservationField(0, f.id, "instrument_height", instrument, stamp);
				if (!Double.isNaN(reflector))
					Klein_Method.this.databaseHandler.editObservationField(0, f.id, "reflector_height", reflector, stamp);
				Klein_Method.this.databaseHandler.commit();
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			this.changed(String.format(Locale.ROOT, "Hoehen geaendert %s: ih %s, th %s", this.describe(f), Double.isNaN(instrument) ? "-" : String.valueOf(instrument),
					Double.isNaN(reflector) ? "-" : String.valueOf(reflector)));
			return tr("Höhen geändert.");
		}

		int undoAll() throws SQLException {
			int n;
			ADJUSTMENT_LOCK.lock();
			try {
				n = Klein_Method.this.databaseHandler.undoFindingEdits(-1, 0);
				Klein_Method.this.databaseHandler.commit();
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			if (n > 0)
				this.changed("alle Aenderungen zurueckgenommen (" + n + ")");
			return n;
		}

		/**
		 * neu testen: Genauigkeiten auf den Stand des Tests (Ausgangsgenauigkeiten, Gruppen nach der Kalibrierung), ausgleichen,
		 * Befunde neu speichern. Die Ergebnisse der Automatik sind danach ungueltig.
		 */
		String retest() throws Exception {
			ADJUSTMENT_LOCK.lock();
			try {
				Klein_Method.this.databaseHandler.restoreStatisticalState();
				Klein_Method.this.setValidAdjustmentResultsAvailable(false);
				EstimationStateType result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
				if (result != EstimationStateType.ERROR_FREE_ESTIMATION)
					return String.format(tr("Ausgleichung nicht fehlerfrei (%s) - Befunde unverändert."), String.valueOf(result));
				double limit = Klein_Method.this.parameter.getGrossErrorLinearLimit();
				List<Max_Error_Row> found = new ArrayList<Max_Error_Row>();
				int significant = 0, undetectable = 0;
				Set<String> accepted = new HashSet<String>();
				for (Max_Error_Row entry : Klein_Method.this.databaseHandler.readGrossErrorEntries())
					if (Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED.equals(entry.status))
						accepted.add(entry.key());
				// bearbeitete Beobachtungen bleiben in der Liste (auch deaktivierte), damit ihre Aenderung sichtbar und ruecknehmbar bleibt
				Set<String> edited = new HashSet<String>();
				for (Object[] e : Klein_Method.this.databaseHandler.readFindingEdits(-1, 0))
					edited.add(e[0] + ":" + e[1]);
				List<Object[]> previous = Klein_Method.this.databaseHandler.readStatisticalSnapshot();
				Map<String, Max_Error_Row> current = new java.util.HashMap<String, Max_Error_Row>();
				for (Max_Error_Row row : Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE)) {
					current.put((row.gnss ? 1 : 0) + ":" + row.observationId, row);
					if (accepted.contains(row.key()))
						continue;
					if (row.significant) {
						found.add(row);
						significant++;
					} else if (!Double.isNaN(row.mdbLinear) && row.mdbLinear > limit && undetectable < Klein_Method_Default_Values.STATISTICAL_TEST_MAX_UNDETECTABLE) {
						found.add(row);
						undetectable++;
					} else if (edited.contains((row.gnss ? 1 : 0) + ":" + row.observationId))
						found.add(row);
				}
				for (Object[] p : previous) {
					String key = p[0] + ":" + p[1];
					if (edited.contains(key) && !current.containsKey(key)) {
						// deaktiviert: alten Befund behalten
						Max_Error_Row old = new Max_Error_Row(ObservationType.getEnumByValue((Integer) p[2]), (String) p[3], (String) p[4], (Double) p[5], 0.0,
								(Integer) p[1], (Integer) p[0] == 1, null, (Double) p[6], (Double) p[8]);
						old.significant = (Boolean) p[9];
						old.mdbLinear = (Double) p[7];
						old.nablaRaw = (Double) p[14];
						found.add(old);
					}
				}
				Klein_Method.this.databaseHandler.storeStatisticalSnapshot(found, this.stamp());
				Klein_Method.this.databaseHandler.commit();
				Klein_Method.this.acceptOwnChangesIntoChecksum("Statistischer Test neu getestet");
				String line = String.format(Locale.ROOT, "STATISTISCHER TEST (neu getestet nach Bearbeitung): %d signifikant, %d kaum kontrolliert", significant, undetectable);
				Klein_Method.this.protocol.logEvent(line);
				Klein_Method.this.logger.log("Klein_Method: " + line);
				return String.format(tr("Neu getestet: %d Beobachtungen signifikant. Die Ergebnisse der Automatik sind ungültig - nach der Bearbeitung die Automatik neu starten."),
						significant);
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
		}

		/** Liste als CSV (Semikolon, UTF-8). */
		void exportCsv(java.io.File file, List<Finding> list) throws java.io.IOException {
			StringBuilder t = new StringBuilder("Messart;Standpunkt;Ziel;Status;signifikant;Fehler_cm;logPost;Redundanz_%;MDB_cm;sigma_vorher;sigma_jetzt;Ursache;Bearbeitet\n");
			for (Finding f : list)
				t.append(String.join(";", f.typeName, f.start, f.end, f.status(), f.significant ? "ja" : "nein", fmt(f.linear * 100.0), fmt(f.logPost), fmt(f.redundancy * 100.0),
						fmt(f.mdb * 100.0), fmt(f.sigmaBefore), fmt(f.sigmaNow), f.cause.replace(';', ','), f.edits.replace(';', ','))).append("\n");
			java.nio.file.Files.write(file.toPath(), t.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}

		private String fmt(double v) {
			return Double.isNaN(v) ? "" : String.format(Locale.GERMANY, "%.4f", v);
		}
	}

	/**
	 * Gruppentests auf gemeinsame Ursache: je Gruppe (Strecken zu einem Ziel = Prismen-/Additionskonstante,
	 * Beobachtungen eines Standpunkts = Instrumentenhoehe, Beobachtungen zu einem Ziel = Zielhoehe) wird das gemeinsame
	 * Feld versuchsweise um +d und -d verschoben und neu ausgeglichen. Aus Omega(0), Omega(+d), Omega(-d) folgt streng
	 * (erweitertes Modell, inkl. Korrelationen und Punktmitbewegung): Omega(x) = Omega0 - 2 g x + h x^2, Schaetzung des
	 * Fehlers e = -g/h, Teststatistik T = g^2 / h (Chi^2, 1 Freiheitsgrad), kleinster aufdeckbarer Gruppenfehler
	 * delta0 / sqrt(h). Getestet wird im Stand des statistischen Tests (Ausgangsgenauigkeiten); danach werden die
	 * Genauigkeiten und Felder exakt zurueckgesetzt.
	 */
	private final class Group_Test_Part {

		final class Group {
			final String kind, name, field;
			final List<Integer> members = new ArrayList<Integer>();
			int zenith = 0;
			double omega = 0, redundancy = 0, single = 0;
			String singleName = null;

			Group(String kind, String name, String field) {
				this.kind = kind;
				this.name = name;
				this.field = field;
			}

			double score() {
				return this.redundancy > 1e-9 ? this.omega / this.redundancy : 0.0;
			}
		}

		private double omega() throws Exception {
			EstimationStateType result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
			if (result != EstimationStateType.ERROR_FREE_ESTIMATION)
				return Double.NaN;
			return Klein_Method.this.databaseHandler.readGlobalOmega()[0];
		}

		List<Group> groups(List<Object[]> observations) {
			int zenith = ObservationType.ZENITH_ANGLE.getId(), leveling = ObservationType.LEVELING.getId();
			int slope = ObservationType.SLOPE_DISTANCE.getId(), horizontal = ObservationType.HORIZONTAL_DISTANCE.getId();
			Map<String, Group> map = new java.util.LinkedHashMap<String, Group>();
			for (Object[] o : observations) {
				int id = (Integer) o[0], type = (Integer) o[1];
				String start = (String) o[2], end = (String) o[3];
				double omega = (Double) o[4], r = (Double) o[5], logp = (Double) o[6];
				if (type == leveling)
					continue;
				String name = Klein_Method_Tab_Builder.Klein_Method_Map_Window.observationTypeName(ObservationType.getEnumByValue(type)) + " " + start + " – " + end;
				if (type == slope || type == horizontal) {
					Group g = map.computeIfAbsent("P|" + end, k -> new Group("P", end, "value_0"));
					g.members.add(id);
					g.omega += omega;
					g.redundancy += r;
					if (logp > g.single) {
						g.single = logp;
						g.singleName = name;
					}
				}
				Group s = map.computeIfAbsent("S|" + start, k -> new Group("S", start, "instrument_height"));
				Group t = map.computeIfAbsent("T|" + end, k -> new Group("T", end, "reflector_height"));
				for (Group g : new Group[] { s, t }) {
					g.members.add(id);
					if (type == zenith || type == slope) {
						g.omega += omega;
						g.redundancy += r;
						if (logp > g.single) {
							g.single = logp;
							g.singleName = name;
						}
					}
					if (type == zenith)
						g.zenith++;
				}
			}
			List<Group> list = new ArrayList<Group>();
			for (Group g : map.values())
				if ("P".equals(g.kind) ? g.members.size() >= 2 : g.zenith >= 2)
					list.add(g);
			list.sort((a, b) -> Double.compare(b.score(), a.score()));
			return list;
		}

		private void shift(Map<Integer, Double> old, String field, double d) throws SQLException {
			for (Map.Entry<Integer, Double> e : old.entrySet()) {
				double v = e.getValue();
				Klein_Method.this.databaseHandler.setObservationFieldQuiet(e.getKey(), field, (Double.isNaN(v) ? 0.0 : v) + d);
			}
		}

		String run() throws Exception {
			long t0 = System.currentTimeMillis();
			List<Object[]> results = new ArrayList<Object[]>();
			int tested = 0, significant = 0, available = 0;
			StringBuilder lines = new StringBuilder();
			Map<Long, double[]> current = null; List<double[]> groupValues = null;
			ADJUSTMENT_LOCK.lock();
			try {
				if (!Klein_Method.this.isProjectOpen())
					return tr("Kein Projekt geöffnet.");
				boolean snapshot = Klein_Method.this.databaseHandler.hasStatisticalState();
				current = Klein_Method.this.databaseHandler.readCurrentSigmas();
				groupValues = Klein_Method.this.databaseHandler.readAllGroupUncertainties();
				if (snapshot)
					Klein_Method.this.databaseHandler.restoreStatisticalState();
				Klein_Method.this.setValidAdjustmentResultsAvailable(false);
				double omega0 = this.omega();
				double[] global = Klein_Method.this.databaseHandler.readGlobalOmega();
				if (Double.isNaN(omega0))
					return tr("Ausgleichung nicht fehlerfrei - Gruppentests nicht möglich.");
				double s02 = global[1] > 0.5 ? Math.max(1.0, omega0 / global[1]) : 1.0;
				List<Group> groups = this.groups(Klein_Method.this.databaseHandler.readGroupTestObservations());
				available = groups.size();
				double d = Klein_Method_Default_Values.GROUP_TEST_SHIFT;
				double limit = Math.log(1.0 / Klein_Method_Default_Values.GROUP_TEST_ALPHA);
				for (Group g : groups) {
					if (tested >= Klein_Method_Default_Values.GROUP_TEST_MAX_GROUPS)
						break;
					Map<Integer, Double> old = new java.util.LinkedHashMap<Integer, Double>();
					for (int id : g.members)
						old.put(id, Klein_Method.this.databaseHandler.readObservationField(0, id, g.field));
					double plus, minus;
					try {
						this.shift(old, g.field, d);
						plus = this.omega();
						this.shift(old, g.field, -d);
						minus = this.omega();
					} finally {
						for (Map.Entry<Integer, Double> e : old.entrySet())
							Klein_Method.this.databaseHandler.setObservationFieldQuiet(e.getKey(), g.field, e.getValue());
					}
					tested++;
					if (Double.isNaN(plus) || Double.isNaN(minus))
						continue;
					double h = (plus + minus - 2.0 * omega0) / (2.0 * d * d);
					double gg = (minus - plus) / (4.0 * d);
					if (!(h > 1e-6))
						continue; // Gruppenfehler wird vollstaendig von Unbekannten aufgenommen (nicht kontrollierbar)
					double estimate = -gg / h;
					double t = gg * gg / h / s02;
					double logp = Math.abs(org.applied_geodesy.adjustment.statistic.TestStatistic.getLogarithmicProbabilityValue(t, 1));
					double mdb = Klein_Method_Default_Values.GROUP_TEST_DELTA0 * Math.sqrt(s02 / h);
					if (mdb > Klein_Method_Default_Values.GROUP_TEST_MAX_MDB)
						continue; // praktisch nicht kontrolliert (z. B. Zielhoehe eines Neupunkts ohne weitere Hoehenbeziehung)
					boolean sig = logp > limit;
					// ein einzelner grober Fehler erklaert die Gruppe besser als ein gemeinsamer Fehler
					String note = sig && g.single >= logp ? g.singleName : null;
					if (note != null)
						sig = false;
					StringBuilder ids = new StringBuilder();
					for (int id : g.members)
						ids.append(ids.length() > 0 ? "," : "").append(id);
					results.add(new Object[] { g.kind, g.name, g.field, ids.toString(), g.members.size(), estimate, t, logp, mdb, sig, note });
					if (sig) {
						significant++;
						lines.append(String.format(Locale.ROOT, "  signifikant: %s %s (%d Beobachtungen) | gemeinsamer Fehler %+.1f mm | |ln p| %.1f | MDB %.1f mm%n",
								"P".equals(g.kind) ? "Strecken zum Ziel" : "S".equals(g.kind) ? "Instrumentenhoehe Standpunkt" : "Zielhoehe Ziel", g.name, g.members.size(),
								estimate * 1000.0, logp, mdb * 1000.0));
					}
				}
				Klein_Method.this.databaseHandler.storeGroupTests(results, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
				Klein_Method.this.databaseHandler.commit();
			} finally {
				try {
					if (current != null) {
						Klein_Method.this.databaseHandler.writeSigmas(current);
						Klein_Method.this.databaseHandler.writeAllGroupUncertainties(groupValues);
						Klein_Method.this.databaseHandler.commit();
						EstimationStateType again = Klein_Method.this.runHeadlessAdjustmentSynchronously();
						Klein_Method.this.setValidAdjustmentResultsAvailable(again == EstimationStateType.ERROR_FREE_ESTIMATION);
						Klein_Method.this.acceptOwnChangesIntoChecksum("Gruppentests");
					}
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Genauigkeiten nach den Gruppentests nicht zurueckgeschrieben: " + e.getMessage());
				}
				ADJUSTMENT_LOCK.unlock();
			}
			String line = String.format(Locale.ROOT, "GRUPPENTESTS (gemeinsame Ursache): %d von %d Gruppen geprueft, %d signifikant (%.0f s)", tested, available, significant,
					(System.currentTimeMillis() - t0) / 1000.0);
			Klein_Method.this.protocol.logEvent(line);
			for (String l : lines.toString().split("\\R"))
				if (!l.isEmpty())
					Klein_Method.this.protocol.logEvent(l);
			Klein_Method.this.logger.log("Klein_Method: " + line);
			String text = String.format(tr("Gruppentests: %d von %d Gruppen geprüft, %d mit signifikantem gemeinsamen Fehler."), tested, available, significant);
			if (tested < available)
				text += " " + String.format(tr("Geprüft wurden die %d Gruppen mit den größten Verbesserungen."), tested);
			return text;
		}

		/**
		 * eine Gruppe im aktuellen Zustand um offset verfaelscht testen (Fehlersimulation): {Schaetzung, T, MDB, |ln p|};
		 * null, wenn eine Ausgleichung scheitert oder die Gruppe nicht kontrolliert ist. Felder danach exakt zurueck.
		 */
		double[] evaluate(Group g, double offset, double s02) throws Exception {
			Map<Integer, Double> old = new java.util.LinkedHashMap<Integer, Double>();
			for (int id : g.members)
				old.put(id, Klein_Method.this.databaseHandler.readObservationField(0, id, g.field));
			double d = Klein_Method_Default_Values.GROUP_TEST_SHIFT, o0, plus, minus;
			try {
				this.shift(old, g.field, offset);
				o0 = this.omega();
				this.shift(old, g.field, offset + d);
				plus = this.omega();
				this.shift(old, g.field, offset - d);
				minus = this.omega();
			} finally {
				for (Map.Entry<Integer, Double> e : old.entrySet())
					Klein_Method.this.databaseHandler.setObservationFieldQuiet(e.getKey(), g.field, e.getValue());
			}
			if (Double.isNaN(o0) || Double.isNaN(plus) || Double.isNaN(minus))
				return null;
			double h = (plus + minus - 2.0 * o0) / (2.0 * d * d), gg = (minus - plus) / (4.0 * d);
			if (!(h > 1e-6))
				return null;
			double mdb = Klein_Method_Default_Values.GROUP_TEST_DELTA0 * Math.sqrt(s02 / h);
			if (mdb > Klein_Method_Default_Values.GROUP_TEST_MAX_MDB)
				return null;
			double t = gg * gg / h / s02;
			return new double[] { -gg / h, t, mdb, Math.abs(org.applied_geodesy.adjustment.statistic.TestStatistic.getLogarithmicProbabilityValue(t, 1)) };
		}

		String label(String kind, String name) {
			return "P".equals(kind) ? String.format(tr("Strecken zum Ziel %s"), name)
					: "S".equals(kind) ? String.format(tr("Instrumentenhöhe Standpunkt %s"), name) : String.format(tr("Zielhöhe Ziel %s"), name);
		}

		/** gespeicherte Gruppentests als Zeilen der Liste. */
		List<Finding> findings(Map<String, StringBuilder> edits) throws SQLException {
			List<Finding> list = new ArrayList<Finding>();
			for (Object[] r : Klein_Method.this.databaseHandler.readGroupTests()) {
				Finding f = new Finding();
				String kind = (String) r[1], name = (String) r[2];
				f.kind = 2;
				f.id = (Integer) r[0];
				f.typeId = -1;
				f.groupField = (String) r[3];
				String[] ids = ((String) r[4]).isEmpty() ? new String[0] : ((String) r[4]).split(",");
				f.members = new int[ids.length];
				for (int i = 0; i < ids.length; i++)
					f.members[i] = Integer.parseInt(ids[i].trim());
				f.typeName = this.label(kind, name);
				f.start = "S".equals(kind) ? name : "";
				f.end = "S".equals(kind) ? "" : name;
				f.nabla = (Double) r[6];
				f.linear = Math.abs(f.nabla);
				f.logPost = (Double) r[8];
				f.mdb = (Double) r[9];
				f.redundancy = Double.NaN;
				f.sigmaBefore = Double.NaN;
				f.sigmaNow = Double.NaN;
				f.significant = (Boolean) r[10];
				f.active = true;
				String note = (String) r[12];
				String mm = String.format(Locale.GERMANY, "%+.1f mm", f.nabla * 1000.0);
				f.cause = "P".equals(kind) ? String.format(tr("gemeinsamer Fehler %s: Prismen-/Additionskonstante am Ziel prüfen (%d Strecken)"), mm, f.members.length)
						: "S".equals(kind) ? String.format(tr("gemeinsamer Fehler %s: Instrumentenhöhe prüfen (%d Beobachtungen)"), mm, f.members.length)
								: String.format(tr("gemeinsamer Fehler %s: Zielhöhe prüfen (%d Beobachtungen)"), mm, f.members.length);
				if (!note.isEmpty())
					f.cause = String.format(tr("eher Einzelfehler: %s (Einzeltest stärker als der Gruppentest)"), note);
				int edited = 0;
				for (int id : f.members)
					if (edits.containsKey("0:" + id))
						edited++;
				f.edits = edited == 0 ? "" : String.format(tr("%d von %d bearbeitet"), edited, f.members.length);
				list.add(f);
			}
			return list;
		}

		/** Aktion fuer eine Gruppenzeile: {ausgefuehrt, nicht moeglich}. correct = gemeinsames Feld um den geschaetzten Fehler korrigieren. */
		int[] apply(Finding f, String action, String stamp) throws SQLException {
			int n = 0;
			switch (action) {
			case "disable":
			case "enable":
				for (int id : f.members)
					Klein_Method.this.databaseHandler.editObservationField(0, id, "enable", "enable".equals(action) ? 1.0 : 0.0, stamp);
				return new int[] { f.members.length, 0 };
			case "correct":
				if (Double.isNaN(f.nabla))
					return new int[] { 0, 1 };
				for (int id : f.members) {
					double v = Klein_Method.this.databaseHandler.readObservationField(0, id, f.groupField);
					Klein_Method.this.databaseHandler.editObservationField(0, id, f.groupField, (Double.isNaN(v) ? 0.0 : v) - f.nabla, stamp);
					n++;
				}
				return new int[] { n, 0 };
			case "undo":
				for (int id : f.members)
					n += Klein_Method.this.databaseHandler.undoFindingEdits(0, id) > 0 ? 1 : 0;
				return new int[] { n, 0 };
			default:
				return new int[] { 0, 1 };
			}
		}
	}

	/**
	 * Fehlersimulation (Monte Carlo) am eigenen Netz: im Stand des statistischen Tests werden nacheinander zufaellig
	 * gewaehlte Beobachtungen (bzw. Gruppen) um eine bekannte Groesse verfaelscht, ausgeglichen und geprueft, ob der
	 * Fehler gefunden wird (Einzeltest: Beobachtung signifikant; Gruppentest: Gruppe signifikant). Richtungen und
	 * Zenitwinkel erhalten den Fehler als Querabweichung am Ziel (Fehler / Strecke). Jeder Versuch wird exakt
	 * zurueckgesetzt; am Ende Genauigkeiten wie vorher und erneute Ausgleichung. Fester Startwert - wiederholbar.
	 */
	private final class Simulation_Part {

		final String[] names = { "Strecken", "Richtungen", "Zenitwinkel", "Prismenkonstante je Ziel", "Instrumentenhoehe je Standpunkt" };

		private boolean isType(Max_Error_Row r, int kind) {
			ObservationType t = r.observationType;
			if (kind == 0)
				return t == ObservationType.SLOPE_DISTANCE || t == ObservationType.HORIZONTAL_DISTANCE;
			if (kind == 1)
				return t == ObservationType.DIRECTION;
			return t == ObservationType.ZENITH_ANGLE;
		}

		String run(double[] sizes, int trials, boolean[] kinds, java.util.function.Consumer<String> progress) throws Exception {
			long t0 = System.currentTimeMillis();
			java.util.Random random = new java.util.Random(486L);
			Map<Long, double[]> current = null; List<double[]> groupValues = null;
			// je Art und Groesse: {Versuche, gefunden, eindeutig (groesster Test), Summe Schaetzung, Summe MDB}
			double[][][] stat = new double[kinds.length][sizes.length][5];
			int total = errorSimulationAdjustments(sizes, trials, kinds), done = 0;
			ADJUSTMENT_LOCK.lock();
			try {
				if (!Klein_Method.this.isProjectOpen())
					return tr("Kein Projekt geöffnet.");
				current = Klein_Method.this.databaseHandler.readCurrentSigmas();
				groupValues = Klein_Method.this.databaseHandler.readAllGroupUncertainties();
				if (Klein_Method.this.databaseHandler.hasStatisticalState())
					Klein_Method.this.databaseHandler.restoreStatisticalState();
				Klein_Method.this.setValidAdjustmentResultsAvailable(false);
				if (Klein_Method.this.runHeadlessAdjustmentSynchronously() != EstimationStateType.ERROR_FREE_ESTIMATION)
					return tr("Ausgleichung nicht fehlerfrei - Fehlersimulation nicht möglich.");
				double[] global = Klein_Method.this.databaseHandler.readGlobalOmega();
				double s02 = global[1] > 0.5 ? Math.max(1.0, global[0] / global[1]) : 1.0;
				List<Max_Error_Row> base = Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE);
				List<Group_Test_Part.Group> groups = Klein_Method.this.groupTestPart.groups(Klein_Method.this.databaseHandler.readGroupTestObservations());
				for (int k = 0; k < kinds.length; k++) {
					if (!kinds[k])
						continue;
					List<Max_Error_Row> candidates = new ArrayList<Max_Error_Row>();
					List<Group_Test_Part.Group> groupCandidates = new ArrayList<Group_Test_Part.Group>();
					if (k < 3) {
						for (Max_Error_Row r : base)
							if (!r.gnss && !r.significant && r.redundancy > 0.05 && this.isType(r, k)
									&& (k == 0 || r.nablaRaw != 0.0 && Double.isFinite(r.nablaRaw) && Double.isFinite(r.linearError)))
								candidates.add(r);
					} else
						for (Group_Test_Part.Group g : groups)
							if ((k == 3 ? "P" : "S").equals(g.kind))
								groupCandidates.add(g);
					if (candidates.isEmpty() && groupCandidates.isEmpty())
						continue;
					for (int si = 0; si < sizes.length; si++) {
						double e = sizes[si];
						for (int trial = 0; trial < trials; trial++) {
							if (progress != null)
								progress.accept(String.format(tr("Fehlersimulation: %d von %d Ausgleichungen …"), done, total));
							double[] st = stat[k][si];
							if (k < 3) {
								Max_Error_Row r = candidates.get(random.nextInt(candidates.size()));
								double sign = random.nextBoolean() ? 1.0 : -1.0;
								double delta = k == 0 ? e : e / (r.linearError / Math.abs(r.nablaRaw));
								double old = Klein_Method.this.databaseHandler.readObservationField(0, r.observationId, "value_0");
								EstimationStateType result;
								List<Max_Error_Row> rows;
								try {
									Klein_Method.this.databaseHandler.setObservationFieldQuiet(r.observationId, "value_0", old + sign * delta);
									result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
									rows = result == EstimationStateType.ERROR_FREE_ESTIMATION ? Klein_Method.this.databaseHandler.readLargestObservationErrors(Integer.MAX_VALUE)
											: new ArrayList<Max_Error_Row>();
								} finally {
									Klein_Method.this.databaseHandler.setObservationFieldQuiet(r.observationId, "value_0", old);
								}
								done++;
								if (rows.isEmpty())
									continue;
								st[0]++;
								Max_Error_Row mine = null, top = null;
								for (Max_Error_Row x : rows) {
									if (!x.gnss && x.observationId == r.observationId)
										mine = x;
									if (x.significant && (top == null || x.value > top.value))
										top = x;
								}
								if (mine != null && mine.significant) {
									st[1]++;
									if (top == mine)
										st[2]++;
								}
								if (mine != null) {
									st[3] += Double.isFinite(mine.linearError) ? mine.linearError : 0.0;
									st[4] += Double.isFinite(mine.mdbLinear) ? mine.mdbLinear : 0.0;
								}
							} else {
								Group_Test_Part.Group g = groupCandidates.get(random.nextInt(groupCandidates.size()));
								double sign = random.nextBoolean() ? 1.0 : -1.0;
								double[] r = Klein_Method.this.groupTestPart.evaluate(g, sign * e, s02);
								done += 3;
								if (r == null)
									continue;
								st[0]++;
								if (r[3] > Math.log(1.0 / Klein_Method_Default_Values.GROUP_TEST_ALPHA)) {
									st[1]++;
									st[2]++;
								}
								st[3] += Math.abs(r[0]);
								st[4] += r[2];
							}
						}
					}
				}
			} finally {
				try {
					if (current != null) {
						Klein_Method.this.databaseHandler.writeSigmas(current);
						Klein_Method.this.databaseHandler.writeAllGroupUncertainties(groupValues);
						Klein_Method.this.databaseHandler.commit();
						EstimationStateType again = Klein_Method.this.runHeadlessAdjustmentSynchronously();
						Klein_Method.this.setValidAdjustmentResultsAvailable(again == EstimationStateType.ERROR_FREE_ESTIMATION);
						Klein_Method.this.acceptOwnChangesIntoChecksum("Fehlersimulation");
					}
				} catch (Exception ex) {
					Klein_Method.this.logger.log("Klein_Method: Genauigkeiten nach der Fehlersimulation nicht zurueckgeschrieben: " + ex.getMessage());
				}
				ADJUSTMENT_LOCK.unlock();
			}
			StringBuilder text = new StringBuilder(), log = new StringBuilder();
			String[] labels = { tr("Strecken"), tr("Richtungen"), tr("Zenitwinkel"), tr("Prismenkonstante je Ziel"), tr("Instrumentenhöhe je Standpunkt") };
			text.append(String.format(tr("Fehlersimulation: %d Versuche je Fehlergröße, %.0f s. Gefunden = signifikant (Einzeltest bzw. Gruppentest); in Klammern: davon größter Test im Netz."),
					trials, (System.currentTimeMillis() - t0) / 1000.0)).append("\n\n");
			for (int k = 0; k < kinds.length; k++) {
				if (!kinds[k])
					continue;
				text.append(labels[k]).append(":\n");
				log.append(String.format(Locale.ROOT, "  %-32s", this.names[k]));
				boolean any = false;
				for (int si = 0; si < sizes.length; si++) {
					double[] st = stat[k][si];
					String size = String.format(Locale.GERMANY, "%.1f cm", sizes[si] * 100.0);
					if (st[0] == 0) {
						log.append(String.format(Locale.ROOT, " | %.1f cm: -", sizes[si] * 100.0));
						continue;
					}
					any = true;
					double found = 100.0 * st[1] / st[0], unique = 100.0 * st[2] / st[0];
					text.append(String.format(Locale.GERMANY, "  %-8s %s %3.0f %% (%3.0f %%) · %s %.1f cm · %s %.1f cm%n", size, tr("gefunden"), found, unique, tr("geschätzt"),
							100.0 * st[3] / st[0], tr("kleinster aufdeckbarer Fehler"), 100.0 * st[4] / st[0]));
					log.append(String.format(Locale.ROOT, " | %.1f cm: %.0f %% (%.0f %%) n=%d", sizes[si] * 100.0, found, unique, (int) st[0]));
				}
				if (!any)
					text.append("  ").append(tr("keine geeigneten Beobachtungen im Netz")).append("\n");
				log.append("\n");
			}
			String line = String.format(Locale.ROOT, "FEHLERSIMULATION (Monte Carlo, %d Versuche je Groesse, Startwert 486): %.0f s", trials, (System.currentTimeMillis() - t0) / 1000.0);
			Klein_Method.this.protocol.logEvent(line);
			for (String l : log.toString().split("\\R"))
				if (!l.trim().isEmpty())
					Klein_Method.this.protocol.logEvent(l);
			Klein_Method.this.logger.log("Klein_Method: " + line + "\n" + log);
			return text.toString();
		}
	}

	/**
	 * Gewichtsverhaeltnis-Ampel: je Messart Anteil an der Bestimmung der Unbekannten (Summe 1 - r), mittlerer
	 * Redundanzanteil und Varianzfaktor (Omega / Summe r) aus der letzten gueltigen Ausgleichung. Rot: praktisch
	 * unkontrolliert (r < 0,05) oder Gewicht grob falsch (Faktor > 4 bzw. < 0,25); gelb: schwach kontrolliert
	 * (r < 0,2), Faktor ausserhalb 0,5 - 2 oder Uebergewicht (mehr als 70 % der Bestimmung bei mehreren Messarten).
	 */
	private final class Weight_Part {

		final class Row {
			String name;
			int n, status;
			double share, redundancy, factor;
			final List<String> notes = new ArrayList<String>();
		}

		List<Row> compute() throws SQLException {
			List<Row> rows = new ArrayList<Row>();
			if (!Klein_Method.this.isProjectOpen())
				return rows;
			double total = 0;
			List<double[]> data = Klein_Method.this.databaseHandler.readWeightBalance();
			int leveling = ObservationType.LEVELING.getId();
			for (double[] d : data)
				if ((int) d[0] != leveling)
					total += Math.max(0.0, d[1] - d[2]); // Nivellement bestimmt ein eigenes Hoehen-Teilnetz (Wechselpunkte) - nicht mitgezaehlt
			for (double[] d : data) {
				if (d[1] < 1)
					continue;
				Row r = new Row();
				ObservationType type = ObservationType.getEnumByValue((int) d[0]);
				r.name = type == null ? String.valueOf((int) d[0]) : Klein_Method_Tab_Builder.Klein_Method_Map_Window.observationTypeName(type);
				r.n = (int) d[1];
				boolean level = (int) d[0] == leveling;
				r.share = level ? Double.NaN : total > 0 ? 100.0 * Math.max(0.0, d[1] - d[2]) / total : 0.0;
				r.redundancy = d[2] / d[1];
				r.factor = d[2] > 0.5 ? d[3] / d[2] : Double.NaN;
				r.status = Workflow_Part.GREEN;
				if (level && r.n >= 3 && r.redundancy < 0.2) {
					r.status = Workflow_Part.YELLOW;
					r.notes.add(tr("schwach kontrolliert - bei Wechselpunkten bauartbedingt, Fehler nur über Schleifenschlüsse erkennbar"));
				} else if (r.n >= 3 && r.redundancy < 0.05) {
					r.status = Workflow_Part.RED;
					r.notes.add(tr("praktisch unkontrolliert"));
				} else if (r.n >= 3 && r.redundancy < 0.2) {
					r.status = Workflow_Part.YELLOW;
					r.notes.add(tr("schwach kontrolliert"));
				}
				if (Double.isFinite(r.factor)) {
					if (r.factor > 4.0 || r.factor < 0.25) {
						r.status = Workflow_Part.RED;
						r.notes.add(r.factor > 1 ? tr("Gewicht viel zu hoch (Genauigkeit zu optimistisch)") : tr("Gewicht viel zu niedrig (Genauigkeit zu pessimistisch)"));
					} else if (r.factor > 2.0 || r.factor < 0.5) {
						r.status = Math.max(r.status, Workflow_Part.YELLOW);
						r.notes.add(r.factor > 1 ? tr("Gewicht zu hoch") : tr("Gewicht zu niedrig"));
					}
				}
				rows.add(r);
			}
			if (rows.size() >= 2)
				for (Row r : rows)
					if (r.share > 70.0 && rows.stream().filter(x -> Double.isFinite(x.share)).count() >= 2) {
						r.status = Math.max(r.status, Workflow_Part.YELLOW);
						r.notes.add(tr("Übergewicht - bestimmt das Netz fast allein"));
					}
			rows.sort((a, b) -> Double.compare(Double.isNaN(b.share) ? -1 : b.share, Double.isNaN(a.share) ? -1 : a.share));
			return rows;
		}

		int worst(List<Row> rows) {
			int w = Workflow_Part.GREEN;
			for (Row r : rows)
				w = Math.max(w, r.status);
			return w;
		}

		String text(List<Row> rows) {
			StringBuilder t = new StringBuilder();
			t.append(tr("Anteil = Anteil der Messart an der Bestimmung der Koordinaten (Summe 1 − Redundanzanteil). Redundanz = mittlerer Redundanzanteil (Kontrolle). "
					+ "Faktor = Varianzfaktor der Messart (1 = Gewicht passt). Das Nivellement bildet ein eigenes Höhen-Teilnetz und zählt beim Anteil nicht mit.")).append("\n\n");
			t.append(String.format(Locale.ROOT, "%-24s %6s %9s %10s %7s  %s%n", tr("Messart"), "n", tr("Anteil"), tr("Redundanz"), tr("Faktor"), tr("Ampel")));
			for (Row r : rows)
				t.append(String.format(Locale.GERMANY, "%-24s %6d %9s %9.0f %% %7s  %s%s%n", r.name, r.n,
						Double.isFinite(r.share) ? String.format(Locale.GERMANY, "%.1f %%", r.share) : tr("eigenes"), 100.0 * r.redundancy,
						Double.isFinite(r.factor) ? String.format(Locale.GERMANY, "%.2f", r.factor) : "-",
						r.status == Workflow_Part.RED ? tr("rot") : r.status == Workflow_Part.YELLOW ? tr("gelb") : tr("grün"), r.notes.isEmpty() ? "" : " – " + String.join(", ", r.notes)));
			if (rows.isEmpty())
				t.append(tr("Keine Ergebnisse - zuerst ausgleichen (Klein_Method-Tab anklicken)."));
			else
				t.append("\n").append(tr("Bei Übergewicht einer Messart prägen deren Fehler die Koordinaten fast ungeprüft. Gegenmittel: andere Messarten ergänzen, "
						+ "Gewichte mit der Automatik kalibrieren, bei Rot zuerst die Genauigkeiten der Messart prüfen."));
			return t.toString();
		}

		void protocol(List<Row> rows) {
			StringBuilder b = new StringBuilder("GEWICHTSVERHAELTNIS:");
			for (Row r : rows)
				b.append(String.format(Locale.ROOT, " %s n=%d Anteil %s r %.0f %% Faktor %s %s;", r.name, r.n, Double.isFinite(r.share) ? String.format(Locale.ROOT, "%.1f %%", r.share) : "-", 100.0 * r.redundancy,
						Double.isFinite(r.factor) ? String.format(Locale.ROOT, "%.2f", r.factor) : "-", r.status == Workflow_Part.RED ? "ROT" : r.status == Workflow_Part.YELLOW ? "GELB" : "GRUEN"));
			Klein_Method.this.protocol.logEvent(b.toString());
			Klein_Method.this.logger.log("Klein_Method: " + b);
		}

		/**
		 * Schlussangleichung: Messarten, deren Varianzfaktor unter dem (redundanzabhaengigen) VKS-Band liegt, weil Untergrenzen
		 * ihre Genauigkeiten festhalten, werden mit Wurzel(Faktor) verkleinert und neu ausgeglichen (Sperre beim Aufrufer).
		 * Liefert die Textzeilen der Aenderungen (leer = nichts angeglichen).
		 */
		/** logPost-Typ zu einem JAG3D-Beobachtungstyp (fuer die Untergrenzen), sonst null. */
		Klein_Method_Threshold_Type logPostTypeOf(int observationType) {
			ObservationType t = ObservationType.getEnumByValue(observationType);
			if (t == null)
				return null;
			switch (t) {
			case DIRECTION:           return Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST;
			case HORIZONTAL_DISTANCE: return Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST;
			case ZENITH_ANGLE:        return Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST;
			case SLOPE_DISTANCE:      return Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST;
			case LEVELING:            return Klein_Method_Threshold_Type.NIVELLEMENT_LOG_POST;
			case GNSS1D:              return Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST;
			case GNSS2D:              return Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST;
			case GNSS3D:              return Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST;
			default:                  return null;
			}
		}

		List<String> finalScaling() throws Exception {
			List<String> lines = new ArrayList<String>();
			Klein_Method_Parameter p = Klein_Method.this.parameter;
			Map<Integer, Double> total = new java.util.LinkedHashMap<Integer, Double>();
			Map<Integer, Double> first = new java.util.LinkedHashMap<Integer, Double>(), last = new java.util.LinkedHashMap<Integer, Double>();
			// der logPost-Grenzwert bleibt die harte Bedingung: eine Runde, die Max-logPost ueber den Grenzwert (bzw. den
			// Wert vor der Schlussangleichung, falls hoeher) hebt, wird exakt zurueckgenommen
			Klein_Method.this.databaseHandler.read(p);
			double guard = Math.max(Klein_Method.this.functions.getMaxLogPostThreshold(p), Klein_Method.this.functions.getMaxLogPostValue(p)) * (1.0 + 1e-9);
			for (int round = 0; round < Klein_Method_Default_Values.FINAL_SCALING_ROUNDS; round++) {
				boolean changed = false;
				List<double[]> sigmaSnapshot = Klein_Method.this.databaseHandler.readAllObservationSigmas();
				List<double[]> groupSnapshot = Klein_Method.this.databaseHandler.readAllGroupUncertainties();
				Map<Integer, Double> roundSteps = new java.util.LinkedHashMap<Integer, Double>();
				for (double[] d : Klein_Method.this.databaseHandler.readWeightBalance()) {
					int type = (int) d[0];
					double redundancy = d[2], factor = redundancy > 0.0 ? d[3] / redundancy : Double.NaN;
					if (!Double.isFinite(factor) || factor <= 0.0 || redundancy < Math.max(1.0, p.getMinCalibrationRedundancy()))
						continue;
					double[] band = Klein_Method_Functions.Klein_Method_Calibration_Quality.effectiveBand(p.getVksBandMin(), p.getVksBandMax(), redundancy,
							p.getFullBandRedundancy());
					first.putIfAbsent(type, factor);
					last.put(type, factor);
					if (factor >= band[0])
						continue;
					double step = Math.max(Klein_Method_Default_Values.FINAL_SCALING_MIN_STEP, Math.sqrt(factor));
					Klein_Method_Threshold_Type logPost = logPostTypeOf(type);
					if (logPost == null)
						continue;
					double[] floors = Klein_Method.this.functions.finalScalingFloors(p, logPost);
					if (Klein_Method.this.databaseHandler.scaleObservationTypeUncertainties(type, step, floors) > 0) {
						total.merge(type, step, (x, y) -> x * y);
						roundSteps.put(type, step);
						changed = true;
					}
				}
				if (!changed)
					break;
				Klein_Method.this.databaseHandler.commit();
				EstimationStateType result = Klein_Method.this.runHeadlessAdjustmentSynchronously();
				if (result != EstimationStateType.ERROR_FREE_ESTIMATION) {
					lines.add("Ausgleichung nicht fehlerfrei (" + result + ") - Schlussangleichung abgebrochen");
					break;
				}
				Klein_Method.this.databaseHandler.read(p);
				double maxLogPost = Klein_Method.this.functions.getMaxLogPostValue(p);
				if (maxLogPost > guard) {
					Klein_Method.this.databaseHandler.writeAllObservationSigmas(sigmaSnapshot);
					Klein_Method.this.databaseHandler.writeAllGroupUncertainties(groupSnapshot);
					Klein_Method.this.databaseHandler.commit();
					for (Map.Entry<Integer, Double> e : roundSteps.entrySet()) {
						double rest = total.get(e.getKey()) / e.getValue();
						if (Math.abs(rest - 1.0) < 1e-12)
							total.remove(e.getKey());
						else
							total.put(e.getKey(), rest);
					}
					EstimationStateType back = Klein_Method.this.runHeadlessAdjustmentSynchronously();
					Klein_Method.this.databaseHandler.read(p);
					lines.add(String.format(Locale.ROOT, "Runde %d zurueckgenommen: Max-logPost waere %.3f > Grenzwert %.3f - Genauigkeiten wie vor der Runde%s", round + 1,
							maxLogPost, guard / (1.0 + 1e-9), back == EstimationStateType.ERROR_FREE_ESTIMATION ? "" : " (Ausgleichung " + back + ")"));
					break;
				}
			}
			if (total.isEmpty()) {
				if (!lines.isEmpty())
					Klein_Method.this.setValidAdjustmentResultsAvailable(true);
				return lines;
			}
			for (double[] d : Klein_Method.this.databaseHandler.readWeightBalance())
				if (d[2] > 0.0)
					last.put((int) d[0], d[3] / d[2]);
			for (Map.Entry<Integer, Double> e : total.entrySet()) {
				ObservationType type = ObservationType.getEnumByValue(e.getKey());
				lines.add(String.format(Locale.ROOT, "%s: Faktor %.3f -> %.3f, Genauigkeiten x %.3f (hoechstens bis zur Untergrenze)", type == null ? String.valueOf(e.getKey()) : type.name(),
						first.get(e.getKey()), last.getOrDefault(e.getKey(), Double.NaN), e.getValue()));
			}
			Klein_Method.this.acceptOwnChangesIntoChecksum("Schlussangleichung VKS");
			Klein_Method.this.setValidAdjustmentResultsAvailable(true);
			return lines;
		}

		void open() {
			Thread thread = busyThread(() -> {
				String text;
				int w = Workflow_Part.GREEN;
				ADJUSTMENT_LOCK.lock();
				try {
					if (!Klein_Method.this.isValidAdjustmentResultsAvailable())
						text = tr("Keine Ergebnisse - zuerst ausgleichen (Klein_Method-Tab anklicken).");
					else {
						List<Row> rows = this.compute();
						w = this.worst(rows);
						text = this.text(rows);
						this.protocol(rows);
					}
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final String message = text;
				final int status = w;
				Platform.runLater(() -> {
					Alert alert = new Alert(status == Workflow_Part.GREEN ? AlertType.INFORMATION : AlertType.WARNING);
					alert.setTitle(tr("Klein_Method - Gewichtsverhältnis"));
					alert.setHeaderText(status == Workflow_Part.RED ? tr("Gewichtsverhältnis: rot") : status == Workflow_Part.YELLOW ? tr("Gewichtsverhältnis: gelb") : tr("Gewichtsverhältnis: grün"));
					javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(message);
					area.setEditable(false);
					area.setWrapText(true);
					area.setStyle("-fx-font-family: monospace;");
					area.setPrefRowCount(14);
					area.setPrefColumnCount(100);
					alert.getDialogPane().setContent(area);
					alert.showAndWait();
				});
			}, "Klein_Method_Weight_Balance");
			thread.setDaemon(true);
			thread.start();
		}
	}

	/**
	 * Massen-Basislinien: Epochen-Basislinien (z. B. 1-5 s ueber 30 min zu mehreren SAPOS-Stationen) erkennen und vor der
	 * ersten Berechnung je Punktpaar zu einem robusten Mittel zusammenfassen (Median/MAD, Ausreisser verworfen, Genauigkeit
	 * nicht durch Wurzel(n) geteilt). Schalter ein: automatisch; aus: Berechnung gesperrt, bis "Jetzt ausduennen".
	 * Mehr als MASS_BASELINE_LIMIT aktive Basislinien werden nie gerechnet.
	 */
	private final class Mass_Part {
		private volatile boolean busy = false, blockedLogged = false;
		private volatile int[] last = new int[3];
		private volatile boolean blocked = false;

		boolean isBlocked() {
			return this.blocked;
		}

		int[] stats() {
			try {
				this.last = Klein_Method.this.databaseHandler.readGnssMassStatistics();
			} catch (Exception e) {
				this.last = new int[3];
			}
			return this.last;
		}

		boolean detected(int[] s) {
			return s[0] > Klein_Method_Default_Values.MASS_BASELINE_LIMIT
					|| s[0] >= Klein_Method_Default_Values.MASS_BASELINE_MIN && s[2] > 0 && s[0] >= (long) Klein_Method_Default_Values.MASS_EPOCHS_PER_PAIR * s[2];
		}

		/** vor jeder Wiederholungs-Auswertung: Gruppierung je Punktpaar einschalten, wenn Massendaten vorliegen. */
		boolean prepare() {
			boolean mass = Klein_Method.this.isProjectOpen() && this.detected(this.stats());
			Klein_Method.this.databaseHandler.setRepetitionByPair(mass);
			return mass;
		}

		/** gewuenschter Modus einer Messart unter Massendaten (GNSS: mindestens zusammenfassen, wenn Schalter ein oder bestaetigt). */
		int effectiveMode(ObservationType type, int mode, boolean mass, int applied) {
			boolean gnss = type == ObservationType.GNSS1D || type == ObservationType.GNSS2D || type == ObservationType.GNSS3D;
			if (!mass || !gnss)
				return mode;
			boolean confirmed = false;
			try {
				confirmed = "1".equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_MASS_CONFIRMED));
			} catch (Exception e) {
				// nicht bestaetigt
			}
			if (!Klein_Method.this.parameter.isMassThinning() && !confirmed)
				return applied != 0 ? applied : Klein_Method_Default_Values.REPETITION_MODE_OFF; // Schalter aus: nichts automatisch, Bestehendes bleibt
			if (mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE || mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE)
				return mode;
			// Einstellung "Aus"/"Regewichten" gilt, solange JAG3D die Menge rechnen kann; darueber wird zusammengefasst
			return this.last[0] > Klein_Method_Default_Values.MASS_BASELINE_LIMIT ? Klein_Method_Default_Values.REPETITION_MODE_MERGE : mode;
		}

		/**
		 * vor jeder Ausgleichung (Sperre beim Aufrufer): true = rechnen erlaubt. Zu viele aktive Basislinien: mit Schalter
		 * zuerst ausduennen, sonst sperren.
		 */
		boolean guard() {
			if (this.busy || !Klein_Method.this.isProjectOpen())
				return true;
			int[] s = this.stats();
			if (s[1] <= Klein_Method_Default_Values.MASS_BASELINE_LIMIT) {
				this.blockedLogged = false;
				this.blocked = false;
				return true;
			}
			if (Klein_Method.this.parameter.isMassThinning()) {
				String text = this.thin();
				if (text != null)
					Klein_Method.this.showApproximationInfo(text);
				s = this.stats();
				if (s[1] <= Klein_Method_Default_Values.MASS_BASELINE_LIMIT) {
					this.blocked = false;
					return true;
				}
			}
			this.blocked = true;
			if (!this.blockedLogged) {
				this.blockedLogged = true;
				String line = String.format(Locale.ROOT, "MASSEN-BASISLINIEN: %d aktive GNSS-Basislinien (Grenze %d) - Ausgleichung gesperrt%s", s[1],
						Klein_Method_Default_Values.MASS_BASELINE_LIMIT, Klein_Method.this.parameter.isMassThinning() ? " (Ausduennen reicht nicht)" : ", Schalter aus - zuerst ausduennen");
				Klein_Method.this.logger.log("Klein_Method: " + line);
				Klein_Method.this.protocol.logEvent(line);
				Klein_Method.this.showApproximationInfo(String.format(tr("Massen-Basislinien: %d aktive GNSS-Basislinien - mehr als %d rechnet Klein_Method nicht. "
						+ "Bitte im Ablauf-Assistenten (Schritt 6) „Jetzt ausdünnen“ wählen oder den Schalter „Massen-Basislinien automatisch ausdünnen“ einschalten."),
						s[1], Klein_Method_Default_Values.MASS_BASELINE_LIMIT));
			}
			return false;
		}

		/** ausduennen (Sperre beim Aufrufer): GNSS-Messarten je Punktpaar robust zusammenfassen; Ergebnistext oder null. */
		String thin() {
			if (this.busy)
				return null;
			this.busy = true;
			long t0 = System.currentTimeMillis();
			try {
				int[] before = this.stats();
				if (!this.detected(before))
					return null;
				Klein_Method.this.databaseHandler.setRepetitionByPair(true);
				List<Object[]> todo = new ArrayList<Object[]>();
				for (Object[] t : Klein_Method.this.repetitionPart.pending()) {
					ObservationType type = (ObservationType) t[0];
					if (type == ObservationType.GNSS1D || type == ObservationType.GNSS2D || type == ObservationType.GNSS3D)
						todo.add(t);
				}
				if (todo.isEmpty())
					return null;
				String text = Klein_Method.this.repetitionPart.apply(todo);
				int[] after = this.stats();
				String line = String.format(Locale.ROOT, "MASSEN-BASISLINIEN ausgeduennt: %d Basislinien (%d aktiv, %d Punktpaare) -> %d aktiv, %d Epochen als Ausreisser verworfen, %.0f s",
						before[0], before[1], before[2], after[1], Klein_Method.this.repetitionPart.lastRejected, (System.currentTimeMillis() - t0) / 1000.0);
				Klein_Method.this.logger.log("Klein_Method: " + line);
				Klein_Method.this.protocol.logEvent(line);
				return String.format(tr("Massen-Basislinien ausgedünnt: %d aktive Basislinien → %d Mittel (je Punktpaar ein robustes Mittel, %d Epochen als Ausreißer verworfen)."),
						before[1], after[1], Klein_Method.this.repetitionPart.lastRejected) + "\n\n" + text;
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Massen-Basislinien nicht ausgeduennt: " + e.getMessage());
				return tr("Vorgang fehlgeschlagen: ") + e.getMessage();
			} finally {
				this.busy = false;
			}
		}

		/** Ablauf-Assistent / Menue: ausduennen auch bei ausgeschaltetem Schalter (bestaetigt fuer dieses Projekt). */
		void thinOnDemand() {
			Thread thread = busyThread(() -> {
				String text;
				ADJUSTMENT_LOCK.lock();
				try {
					Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_MASS_CONFIRMED, "1");
					text = this.thin();
					if (text == null)
						text = tr("Keine Massen-Basislinien zum Ausdünnen.");
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				Klein_Method.this.showApproximationInfo(text);
			}, "Klein_Method_Mass_Thinning");
			thread.setDaemon(true);
			thread.start();
		}
	}

	/** eine Zeile der Vorschau "Punkte umbenennen". */
	public static final class RenameRow {
		public String oldName, newName, note = "";
		public boolean point, selected = true;
		public int observations, status; // 0 gruen, 1 gelb, 2 rot
		public double distance = Double.NaN;
		/** Zusammenfuehrung mit weiteren Punkten zum selben neuen Namen. */
		public boolean merge;
	}

	/** Umbenennungsregel: Suchen/Ersetzen (Text oder regulaerer Ausdruck) oder Sonderregel. */
	public static final class RenameSpec {
		public String search = "", replace = "", filter = "";
		public boolean regex = true;
		/** 0 Suchen/Ersetzen, 1 Nullen auffuellen auf n Stellen, 2 Nummer um n verschieben. */
		public int special = 0, n = 0;
		/** 0 Punkte und Messungen, 1 nur Messungen, 2 nur Punkte. */
		public int scope = 0;
		public double toleranceHorizontal = 0.03, toleranceHeight = 0.05;
		/** Zusammenfuehren: false = bevorzugten Punkt behalten, true = Mittel der Naeherungskoordinaten (nur Neupunkte). */
		public boolean meanApproximation = false;

		public String apply(String name) {
			if (name == null)
				return null;
			if (!this.filter.isEmpty() && !name.contains(this.filter))
				return name;
			if (this.special == 1 || this.special == 2) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile(this.special == 1 ? "^(\\D*)(\\d+)(.*)$" : "^(.*?)(\\d+)(\\D*)$").matcher(name);
				if (!m.matches())
					return name;
				String digits = m.group(2);
				if (this.special == 1) {
					StringBuilder b = new StringBuilder(digits);
					while (b.length() < this.n)
						b.insert(0, '0');
					return m.group(1) + b + m.group(3);
				}
				long value = Long.parseLong(digits) + this.n;
				if (value < 0)
					return name;
				String text = String.valueOf(value);
				if (digits.startsWith("0"))
					while (text.length() < digits.length())
						text = "0" + text;
				return m.group(1) + text + m.group(3);
			}
			if (this.search.isEmpty())
				return name;
			return this.regex ? name.replaceAll(this.search, this.replace) : name.replace(this.search, this.replace);
		}
	}

	/**
	 * Punkte umbenennen: Vorschau (alt -> neu, Zusammenfuehrungen mit Abstandspruefung), Ausfuehren mit Merken jeder
	 * Aenderung, exakte Ruecknahme. Zusammengefuehrt wird nur, was im Rahmen der Toleranz derselbe Punkt ist; Festpunkte
	 * mit abweichenden Koordinaten nie.
	 */
	private final class Rename_Part {

		final class PointInfo {
			int id, type, dimension;
			String name;
			double[] xyz;

			boolean fixed() {
				return this.type != org.applied_geodesy.adjustment.network.PointType.NEW_POINT.getId();
			}
		}

		private Map<String, PointInfo> points() throws SQLException {
			Map<String, PointInfo> map = new java.util.LinkedHashMap<String, PointInfo>();
			boolean results = Klein_Method.this.isValidAdjustmentResultsAvailable();
			for (Object[] r : Klein_Method.this.databaseHandler.readRenamePoints()) {
				PointInfo p = new PointInfo();
				p.id = (Integer) r[0];
				p.name = (String) r[1];
				p.type = (Integer) r[2];
				p.dimension = (Integer) r[3];
				double y0 = (Double) r[4], x0 = (Double) r[5], z0 = (Double) r[6], y = (Double) r[7];
				if (results && !Double.isNaN(y))
					p.xyz = new double[] { y, (Double) r[8], (Double) r[9] };
				else if (!(y0 == 0.0 && x0 == 0.0 && (p.dimension != 1 || z0 == 0.0)))
					p.xyz = new double[] { y0, x0, z0 };
				map.put(p.name, p);
			}
			return map;
		}

		/** groesster Abstand {Lage, Hoehe} zwischen den Punkten (NaN = nicht pruefbar). */
		private double[] spread(List<PointInfo> members) {
			double horizontal = 0, height = 0;
			for (int i = 0; i < members.size(); i++)
				for (int j = i + 1; j < members.size(); j++) {
					PointInfo a = members.get(i), b = members.get(j);
					if (a.xyz == null || b.xyz == null)
						return new double[] { Double.NaN, Double.NaN };
					if (a.dimension != 1 && b.dimension != 1)
						horizontal = Math.max(horizontal, Math.hypot(a.xyz[0] - b.xyz[0], a.xyz[1] - b.xyz[1]));
					if (a.dimension != 2 && b.dimension != 2)
						height = Math.max(height, Math.abs(a.xyz[2] - b.xyz[2]));
				}
			return new double[] { horizontal, height };
		}

		List<RenameRow> preview(RenameSpec spec) throws SQLException {
			List<RenameRow> rows = new ArrayList<RenameRow>();
			if (!Klein_Method.this.isProjectOpen())
				return rows;
			Map<String, PointInfo> points = this.points();
			Map<String, Integer> observations = Klein_Method.this.databaseHandler.readObservationNameCounts();
			java.util.Set<String> names = new java.util.TreeSet<String>();
			if (spec.scope != 1)
				names.addAll(points.keySet());
			if (spec.scope != 2)
				names.addAll(observations.keySet());
			Map<String, List<RenameRow>> byNew = new java.util.LinkedHashMap<String, List<RenameRow>>();
			for (String name : names) {
				String to = spec.apply(name);
				if (to == null || to.equals(name))
					continue;
				RenameRow r = new RenameRow();
				r.oldName = name;
				r.newName = to.trim();
				r.point = spec.scope != 1 && points.containsKey(name);
				r.observations = spec.scope != 2 ? observations.getOrDefault(name, 0) : 0;
				if (r.newName.isEmpty()) {
					r.status = 2;
					r.selected = false;
					r.note = tr("neuer Name leer");
				}
				rows.add(r);
				byNew.computeIfAbsent(r.newName, k -> new ArrayList<RenameRow>()).add(r);
			}
			java.util.Set<String> renamedAway = new HashSet<String>();
			for (RenameRow r : rows)
				if (r.point)
					renamedAway.add(r.oldName);
			for (Map.Entry<String, List<RenameRow>> e : byNew.entrySet()) {
				List<PointInfo> members = new ArrayList<PointInfo>();
				for (RenameRow r : e.getValue())
					if (r.point)
						members.add(points.get(r.oldName));
				PointInfo existing = points.get(e.getKey());
				if (existing != null && !renamedAway.contains(e.getKey()))
					members.add(existing);
				for (RenameRow r : e.getValue()) {
					if (r.status == 2)
						continue;
					if (spec.scope == 1 && existing == null && !points.containsKey(r.oldName))
						continue; // nur Messungen, ohne Punkte - einfache Umbenennung
					if (spec.scope == 1 && existing == null) {
						r.status = 1;
						r.note = tr("kein Punkt mit dem neuen Namen - Punkt behält den alten Namen");
						continue;
					}
					if (spec.scope == 2 && r.observations == 0 && observations.getOrDefault(r.oldName, 0) > 0) {
						r.status = 1;
						r.note = String.format(tr("Messungen behalten den alten Namen (%d)"), observations.get(r.oldName));
					}
				}
				if (members.size() < 2) {
					for (RenameRow r : e.getValue())
						if (r.status == 0)
							r.note = tr("umbenennen");
					continue;
				}
				// Bezug: vorhandener Punkt mit dem neuen Namen, sonst der bevorzugte (Festpunkt, Dimension, Koordinaten); jede Zeile einzeln dagegen
				PointInfo reference = existing != null && !renamedAway.contains(e.getKey()) ? existing : null;
				if (reference == null)
					for (PointInfo p : members)
						if (reference == null || score(p, null) > score(reference, null))
							reference = p;
				for (RenameRow r : e.getValue()) {
					r.merge = true;
					if (r.status == 2)
						continue;
					PointInfo own = points.get(r.oldName);
					if (own == null || own == reference) {
						if (r.status == 0)
							r.note = String.format(tr("bleibt erhalten, %d Punkte werden mit ihm zusammengeführt"), members.size() - 1);
						continue;
					}
					double[] d = this.spread(java.util.Arrays.asList(reference, own));
					boolean far = !(d[0] <= spec.toleranceHorizontal) || !(d[1] <= spec.toleranceHeight);
					r.distance = Double.isNaN(d[0]) ? Double.NaN : Math.max(d[0], d[1]);
					if (Double.isNaN(d[0])) {
						r.status = 1;
						r.selected = false;
						r.note = String.format(tr("Zusammenführen mit %s - nicht prüfbar (Koordinaten fehlen)"), reference.name);
					} else if (far && own.fixed() && reference.fixed()) {
						r.status = 2;
						r.selected = false;
						r.note = String.format(tr("Festpunkte mit abweichenden Koordinaten (Lage %s, Höhe %s) - nie zusammenführen"), mm(d[0]), mm(d[1]));
					} else if (far) {
						r.status = 2;
						r.selected = false;
						r.note = String.format(tr("Exzenter? Lage %s, Höhe %s von %s - nicht zusammenführen"), mm(d[0]), mm(d[1]), reference.name);
					} else
						r.note = String.format(tr("derselbe Punkt wie %s (Lage %s, Höhe %s) - zusammenführen"), reference.name, mm(d[0]), mm(d[1]));
				}
			}
			return rows;
		}

		private String mm(double v) {
			return Double.isNaN(v) ? "-" : v >= 1.0 ? String.format(Locale.GERMANY, "%.3f m", v) : String.format(Locale.GERMANY, "%.1f cm", v * 100.0);
		}

		/** ausfuehren (gewaehlte Zeilen); Ergebnistext. */
		String apply(List<RenameRow> rows, RenameSpec spec) throws SQLException {
			List<RenameRow> chosen = new ArrayList<RenameRow>();
			for (RenameRow r : rows)
				if (r.selected && !r.newName.isEmpty())
					chosen.add(r);
			if (chosen.isEmpty())
				return tr("Nichts gewählt.");
			String batch = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			int[] r;
			int merged = 0;
			ADJUSTMENT_LOCK.lock();
			try {
				Map<String, PointInfo> points = this.points();
				Map<String, String> observationRenames = new java.util.HashMap<String, String>();
				Map<Integer, String> pointRenames = new java.util.LinkedHashMap<Integer, String>();
				List<Integer> delete = new ArrayList<Integer>();
				Map<Integer, double[]> apriori = new java.util.HashMap<Integer, double[]>();
				Map<String, List<PointInfo>> groups = new java.util.LinkedHashMap<String, List<PointInfo>>();
				java.util.Set<String> renamedAway = new HashSet<String>();
				for (RenameRow row : chosen) {
					if (spec.scope != 2)
						observationRenames.put(row.oldName, row.newName);
					if (spec.scope != 1 && points.containsKey(row.oldName)) {
						groups.computeIfAbsent(row.newName, k -> new ArrayList<PointInfo>()).add(points.get(row.oldName));
						renamedAway.add(row.oldName);
					}
				}
				for (Map.Entry<String, List<PointInfo>> g : groups.entrySet()) {
					List<PointInfo> members = new ArrayList<PointInfo>(g.getValue());
					PointInfo existing = points.get(g.getKey());
					if (existing != null && !renamedAway.contains(g.getKey()))
						members.add(existing);
					// behalten: Festpunkt vor Neupunkt, hoehere Dimension, mit Koordinaten, sonst der vorhandene bzw. erste
					PointInfo keep = members.get(0);
					for (PointInfo p : members)
						if (score(p, existing) > score(keep, existing))
							keep = p;
					if (members.size() > 1) {
						merged += members.size() - 1;
						if (spec.meanApproximation) {
							boolean allNew = true;
							double[] mean = new double[3];
							int n = 0;
							for (PointInfo p : members)
								if (p.fixed())
									allNew = false;
							if (allNew) {
								for (Object[] raw : Klein_Method.this.databaseHandler.readRenamePoints())
									for (PointInfo p : members)
										if (p.id == (Integer) raw[0] && !((Double) raw[4] == 0.0 && (Double) raw[5] == 0.0)) {
											for (int c = 0; c < 3; c++)
												mean[c] += (Double) raw[4 + c];
											n++;
										}
								if (n > 0) {
									for (int c = 0; c < 3; c++)
										mean[c] /= n;
									apriori.put(keep.id, mean);
								}
							}
						}
					}
					for (PointInfo p : members)
						if (p != keep)
							delete.add(p.id);
					if (!keep.name.equals(g.getKey()))
						pointRenames.put(keep.id, g.getKey());
				}
				r = Klein_Method.this.databaseHandler.applyRenames(observationRenames, pointRenames, delete, apriori, batch);
				Klein_Method.this.databaseHandler.clearStatisticalSnapshot();
				Klein_Method.this.databaseHandler.deleteFreeSolution();
				Klein_Method.this.databaseHandler.commit();
				Klein_Method.this.acceptOwnChangesIntoChecksum("Punkte umbenannt");
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			this.changed();
			String line = String.format(Locale.ROOT, "PUNKTE UMBENANNT (%s): %d Punkte umbenannt, %d zusammengefuehrt (geloescht), %d Beobachtungen geaendert%s; Regel %s -> %s",
					batch, r[0], r[1], r[2], r[3] > 0 ? ", " + r[3] + " uebersprungen" : "", spec.special == 1 ? "Nullen auffuellen " + spec.n
							: spec.special == 2 ? "Nummer +" + spec.n : "'" + spec.search + "'", spec.special != 0 ? "" : "'" + spec.replace + "'");
			Klein_Method.this.protocol.logEvent(line);
			Klein_Method.this.logger.log("Klein_Method: " + line);
			return String.format(tr("%d Punkte umbenannt, %d Punkte zusammengeführt, %d Beobachtungen geändert."), r[0], r[1], r[2])
					+ (r[3] > 0 ? " " + String.format(tr("%d übersprungen (Kongruenzpaar bzw. Lotabweichung schon vorhanden)."), r[3]) : "") + "\n"
					+ tr("Die Ergebnisse sind damit ungültig - bitte neu ausgleichen. „Letzte Umbenennung zurücknehmen“ stellt alles exakt wieder her.");
		}

		private int score(PointInfo p, PointInfo existing) {
			return (p.fixed() ? 1000 : 0) + p.dimension * 100 + (p.xyz != null ? 10 : 0) + (p == existing ? 1 : 0);
		}

		String undo() throws SQLException {
			int[] r;
			String batch;
			ADJUSTMENT_LOCK.lock();
			try {
				batch = Klein_Method.this.databaseHandler.lastRenameBatch();
				if (batch == null)
					return tr("Keine Umbenennung zum Zurücknehmen.");
				r = Klein_Method.this.databaseHandler.undoLastRename();
				Klein_Method.this.databaseHandler.clearStatisticalSnapshot();
				Klein_Method.this.databaseHandler.deleteFreeSolution();
				Klein_Method.this.databaseHandler.commit();
				Klein_Method.this.acceptOwnChangesIntoChecksum("Umbenennung zurueckgenommen");
			} finally {
				ADJUSTMENT_LOCK.unlock();
			}
			this.changed();
			String line = String.format(Locale.ROOT, "PUNKTE UMBENANNT zurueckgenommen (%s): %d Aenderungen zurueckgesetzt, %d Punkte wiederhergestellt", batch, r[0], r[1]);
			Klein_Method.this.protocol.logEvent(line);
			Klein_Method.this.logger.log("Klein_Method: " + line);
			return String.format(tr("Umbenennung vom %s zurückgenommen: %d Änderungen zurückgesetzt, %d Punkte wiederhergestellt."), batch, r[0], r[1]);
		}

		private void changed() {
			Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
			Klein_Method.this.refreshJag3dView(true);
			Platform.runLater(Klein_Method_Tab_Builder.Klein_Method_Map_Window::notifyDataChanged);
		}
	}

	private final class Workflow_Part {

		static final int GRAY = -1, GREEN = 0, YELLOW = 1, RED = 2;

		/** hoechstens 10 Namen, Rest als Anzahl. */
		private String names(List<String> list) {
			return String.join(", ", list.subList(0, Math.min(10, list.size()))) + (list.size() > 10 ? ", … (+" + (list.size() - 10) + ")" : "");
		}

		final class Step {
			final String title;
			int status = GREEN;
			final List<String> lines = new ArrayList<String>();
			final List<String> actionLabels = new ArrayList<String>();
			final List<Runnable> actions = new ArrayList<Runnable>();

			Step(String title) {
				this.title = title;
			}

			void worst(int s) {
				this.status = Math.max(this.status, s);
			}

			void action(String label, Runnable run) {
				this.actionLabels.add(label);
				this.actions.add(run);
			}
		}

		private volatile List<Step> last = null;

		/** Neu bewerten (Hintergrund, Sperre beim Aufrufer). */
		List<Step> recompute() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return null;
				List<Step> steps = this.evaluate();
				this.last = steps;
				int red = 0, yellow = 0;
				for (Step st : steps) {
					if (st.status == RED)
						red++;
					else if (st.status == YELLOW)
						yellow++;
				}
				Klein_Method.this.logger.log("Klein_Method: Ablauf-Assistent - " + red + " rot, " + yellow + " gelb.");
				final int r = red, y = yellow;
				Platform.runLater(() -> {
					if (Klein_Method.this.tabBuilder != null)
						Klein_Method.this.tabBuilder.updateWorkflowStatus(r, y);
				});
				return steps;
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Ablauf-Assistent nicht auswertbar: " + e.getMessage());
				return null;
			}
		}

		private String redOrYellowLines(List<String> uiLines, int max) {
			StringBuilder b = new StringBuilder();
			int n = 0;
			for (String line : uiLines)
				if ((line.startsWith(tr("[ROT]")) || line.startsWith(tr("[GELB]"))) && n++ < max)
					b.append(b.length() == 0 ? "" : "\n").append(line);
			return b.toString();
		}

		private List<Step> evaluate() throws Exception {
			List<Step> steps = new ArrayList<Step>();
			Klein_Method_Database_Handler h = Klein_Method.this.databaseHandler;
			// 1 Import und Plausibilitaet
			Step s1 = new Step(tr("1 Import und Plausibilität"));
			Import_Report_Part.Report report = Klein_Method.this.importReportPart.compute();
			s1.worst(report.level == 2 ? RED : report.level == 1 ? YELLOW : GREEN);
			String rl = this.redOrYellowLines(report.uiLines, 6);
			s1.lines.add(report.level == 0 ? tr("Importbericht ohne Befund.") : rl);
			List<Object[]> zero = h.readZeroFixedPoints();
			if (!zero.isEmpty())
				s1.action(String.format(tr("%d Festpunkte 0/0/0 in Neupunkte umwandeln"), zero.size()), () -> this.repairZeroFixed(true));
			else if (h.countRepairedZeroFixedPoints() > 0) {
				s1.lines.add(String.format(tr("%d Festpunkte (0/0/0) wurden in Neupunkte umgewandelt."), h.countRepairedZeroFixedPoints()));
				s1.action(tr("Umwandlung zurücknehmen"), () -> this.repairZeroFixed(false));
			}
			s1.action(tr("Importbericht öffnen"), Klein_Method.this::openImportReport);
			steps.add(s1);
			// 2 Koordinatensysteme
			Step s2 = new Step(tr("2 Koordinatensysteme"));
			int[] kinds = h.readCoordinateKindCounts();
			boolean gnss = h.hasActiveGnssObservations();
			String local = h.readProjectSetting(SETTING_LOCAL_SYSTEM);
			boolean converted = local != null && !local.isEmpty();
			s2.lines.add(String.format(tr("2D/3D-Punkte: %d geozentrisch, %d UTM mit Zonenkennung, %d lokal/projiziert, %d ohne Koordinaten (0/0); GNSS-Basislinien: %s."),
					kinds[0], kinds[1], kinds[2], kinds[3], gnss ? tr("ja") : tr("nein")));
			if (kinds[0] > 0 && !gnss) {
				s2.worst(RED);
				s2.lines.add(tr("Geozentrische Punkte ohne GNSS-Basislinien - ohne GNSS rechnet Klein_Method nicht um; Punkte im Projektsystem liefern."));
			} else if (kinds[0] > 0 && (kinds[1] > 0 || (kinds[2] > 0 && !converted))) {
				s2.worst(RED);
				s2.lines.add(tr("Geozentrische und projizierte/lokale 3D-Punkte gemischt - alle 3D-Punkte im selben System liefern (GNSS: geozentrisch), nichts vorab "
						+ "selbst umrechnen."));
			}
			if (kinds[3] > 0)
				s2.lines.add(tr("Punkte ohne Koordinaten erhalten im Schritt 5 Näherungskoordinaten."));
			steps.add(s2);
			// 3 Umrechnung ins lokale System
			Step s3 = new Step(tr("3 Umrechnung ins lokale System (nur mit GNSS)"));
			if (!gnss)
				s3.lines.add(tr("Nicht nötig - keine GNSS-Basislinien; Projektion und Reduktion bleiben unverändert."));
			else if (!converted) {
				s3.worst(kinds[0] > 0 ? RED : YELLOW);
				s3.lines.add(tr("Noch nicht ins lokale System (ENh) umgerechnet."));
				if (kinds[0] == 0 && kinds[2] > 0)
					s3.lines.add(tr("Die Punkte liegen schon lokal/projiziert vor (vorab umgerechnet?) - deren P0 kennt Klein_Method nicht. Sicherer: "
							+ "geozentrisch importieren und hier umrechnen lassen."));
				s3.action(tr("Projekt prüfen und umrechnen"), Klein_Method.this::convertProjectOnDemand);
			} else {
				Klein_Method_Functions.Klein_Method_Local_System.Parameters p = Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(local);
				String[] info = h.readLocalConversionBackupInfo();
				s3.lines.add(String.format(tr("Umgerechnet%s, P0 %s."), info != null && info.length > 0 && info[0] != null ? " " + tr("am") + " " + info[0] : "",
						p == null ? local : String.format(Locale.GERMANY, "B %.5f° L %.5f° h %.2f m", p.latitude, p.longitude, p.height)));
				if (kinds[0] > 0) {
					s3.worst(RED);
					s3.lines.add(String.format(tr("%d geozentrische Punkte nach der Umrechnung - nachträglich importiert? Tab erneut anklicken (Umrechnung)."), kinds[0]));
					s3.action(tr("Projekt prüfen und umrechnen"), Klein_Method.this::convertProjectOnDemand);
				}
				// Sperre gegen mehrfache/fremde Umrechnung: lokale Werte, die Klein_Method nicht selbst umgerechnet hat
				List<String>[] foreign = h.readForeignLocalRows();
				if (!foreign[0].isEmpty() || !foreign[2].isEmpty()) {
					s3.worst(RED);
					if (!foreign[0].isEmpty())
						s3.lines.add(String.format(tr("%d Fest-/Anschlusspunkt(e) nach der Umrechnung lokal importiert (nicht von Klein_Method umgerechnet, P0 unbekannt "
								+ "- doppelt oder mit anderem P0 umgerechnet?): %s"), foreign[0].size(), this.names(foreign[0])));
					if (!foreign[2].isEmpty())
						s3.lines.add(String.format(tr("%d GNSS-Basislinie(n) nach der Umrechnung importiert und nicht von Klein_Method umgerechnet (P0 unbekannt): %s"),
								foreign[2].size(), this.names(foreign[2])));
					s3.lines.add(tr("Diese Daten löschen und geozentrisch (WGS84 X/Y/Z) neu importieren - Klein_Method rechnet sie dann mit demselben P0 um."));
					Klein_Method.this.logger.log("Klein_Method: Ablauf - fremd umgerechnete lokale Daten: " + foreign[0].size() + " Festpunkte, " + foreign[1].size()
							+ " Neupunkte, " + foreign[2].size() + " Basislinien");
				}
				if (!foreign[1].isEmpty()) {
					s3.worst(YELLOW);
					s3.lines.add(String.format(tr("%d Neupunkt(e) mit lokalen Näherungen aus einer fremden Umrechnung - Näherungen prüfen (Schritt 5): %s"),
							foreign[1].size(), this.names(foreign[1])));
				}
				if (p != null) {
					List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>();
					for (double[] r : h.readLocalConversionPoints())
						if ((int) r[1] >= 2 && Math.abs(r[2]) < 1.0e6 && !(r[2] == 0.0 && r[3] == 0.0)) {
							ys.add(r[2]);
							xs.add(r[3]);
						}
					if (!ys.isEmpty()) {
						java.util.Collections.sort(ys);
						java.util.Collections.sort(xs);
						double km = Math.hypot(ys.get(ys.size() / 2) - p.addEast, xs.get(xs.size() / 2) - p.addNorth) / 1000.0;
						if (km > 50.0) {
							s3.worst(RED);
							s3.lines.add(String.format(tr("P0 liegt %s km außerhalb des Netzes."), Klein_Method_Format.format(km, "", 1)));
							s3.action(tr("P0 ins Netz legen"), Klein_Method.this::relocateP0OnDemand);
						}
					}
				}
			}
			steps.add(s3);
			// 4 Hoehen
			Step s4 = new Step(tr("4 Höhensystem"));
			Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check hc = Klein_Method.this.quasigeoidPart.checkHeightSystems();
			String conversion = h.readProjectSetting(SETTING_HEIGHT_CONVERSION);
			boolean heightsConverted = conversion != null && !conversion.isEmpty();
			if (hc == null || hc.verdict == -1) {
				s4.status = GRAY;
				s4.lines.add(tr("Nicht prüfbar - keine Punkte mit Höhe sowohl über GNSS als auch über Nivellement."));
			} else {
				s4.lines.add(String.format(tr("%d gemeinsame Punkte: GNSS-Höhe minus Nivellementshöhe %s m."), hc.common, Klein_Method_Format.format(hc.difference, "", 3)));
				if (hc.verdict == 1) {
					s4.worst(RED);
					s4.lines.add(tr("NHN und ellipsoidische Höhen gemischt - Höhenfestpunkte mit dem Quasigeoid umrechnen."));
					if (!heightsConverted && Klein_Method_Functions.Klein_Method_Quasigeoid.active() != null)
						s4.action(tr("Höhenfestpunkte umrechnen"), () -> this.background(() -> Klein_Method.this.convertFixedHeightsToEllipsoidal()));
				} else if (hc.verdict == 2) {
					s4.worst(YELLOW);
					s4.lines.add(tr("Unklar - die Differenz passt weder zu gleichem System noch zur Höhenanomalie. Höhenfestpunkte und GNSS-Höhen prüfen."));
				}
			}
			if (heightsConverted)
				s4.lines.add(String.format(tr("Höhenfestpunkte sind umgerechnet (Modell %s) - eine zweite Umrechnung ist gesperrt."),
						conversion.substring(0, Math.max(0, conversion.indexOf('|')))));
			Klein_Method_Adjustment_Step step = Klein_Method.this.getCurrentAdjustmentStep();
			if ((step == Klein_Method_Adjustment_Step.DYNAMIC || step == Klein_Method_Adjustment_Step.FIXED) && Klein_Method.this.heightDatumPart.isEnabled())
				s4.lines.add(tr("„Höhe nur aus Höhenfestpunkten (1D)“ ist eingeschaltet."));
			steps.add(s4);
			// 5 Naeherungskoordinaten
			Step s5 = new Step(tr("5 Näherungskoordinaten"));
			List<String> missing = h.readObservedPointsWithoutApproximation();
			if (!missing.isEmpty()) {
				s5.worst(RED);
				s5.lines.add(String.format(tr("%d gemessene Punkte ohne Näherungskoordinaten: %s"), missing.size(),
						String.join(", ", missing.subList(0, Math.min(10, missing.size()))) + (missing.size() > 10 ? ", …" : "")));
				s5.action(tr("Näherungskoordinaten berechnen"), Klein_Method.this::checkApproximationsOnDemand);
			} else
				s5.lines.add(tr("Alle gemessenen Punkte haben Näherungskoordinaten."));
			steps.add(s5);
			// 6 Wiederholte Messungen
			Step s6 = new Step(tr("6 Wiederholte Messungen"));
			List<Object[]> todo = Klein_Method.this.repetitionPart.pending();
			List<String> applied = new ArrayList<String>();
			for (ObservationType type : Klein_Method_Parameter.REPETITION_TYPES)
				if (h.appliedRepetitionMode(type) != 0)
					applied.add(repetitionTypeName(type));
			if (!applied.isEmpty())
				s6.lines.add(tr("Zusammengefasst: ") + String.join(", ", applied) + ".");
			int[] mass = Klein_Method.this.massPart.stats();
			if (Klein_Method.this.massPart.detected(mass)) {
				boolean blocked = mass[1] > Klein_Method_Default_Values.MASS_BASELINE_LIMIT;
				s6.worst(blocked ? RED : YELLOW);
				s6.lines.add(String.format(tr("Massen-Basislinien erkannt: %d GNSS-Basislinien, davon %d aktiv, %d Punktpaare."), mass[0], mass[1], mass[2])
						+ (blocked ? " " + String.format(tr("Mehr als %d aktive - die Ausgleichung ist gesperrt, bis ausgedünnt ist."), Klein_Method_Default_Values.MASS_BASELINE_LIMIT) : ""));
				if (blocked || !todo.isEmpty())
					s6.action(tr("Jetzt ausdünnen"), Klein_Method.this.massPart::thinOnDemand);
			}
			if (!todo.isEmpty()) {
				s6.worst(YELLOW);
				s6.lines.add(tr("Offen (wird beim Tab-Klick bzw. vor dem Start automatisch angewendet):") + "\n" + Klein_Method.this.repetitionPart.describe(todo).trim());
				s6.action(tr("Jetzt anwenden …"), Klein_Method.this::mergeGnssSessionsOnDemand);
			} else if (applied.isEmpty())
				s6.lines.add(tr("Keine Wiederholungen vorhanden bzw. Einstellung „Aus“."));
			steps.add(s6);
			// 7 Freigabe
			Step s7 = new Step(tr("7 Freigabe der Automatik"));
			boolean anyRed = false;
			for (Step st : steps)
				anyRed |= st.status == RED;
			if (anyRed) {
				s7.worst(RED);
				s7.lines.add(tr("Gesperrt - zuerst die roten Schritte beheben."));
			} else if (Klein_Method.this.isGrossErrorsSuspected()) {
				s7.worst(RED);
				s7.lines.add(tr("Grobe Fehler vermutet - zuerst „Grobe Fehler bereinigen“."));
			} else if (!Klein_Method.this.isValidAdjustmentResultsAvailable()) {
				s7.worst(YELLOW);
				s7.lines.add(tr("Noch keine gültige Ausgleichung - den Klein_Method-Tab anklicken."));
			} else
				s7.lines.add(tr("Frei - „Klein_Method starten“."));
			int[] statistical = Klein_Method.this.statisticalTestPart.counts();
			if (statistical[1] > 0) {
				s7.worst(YELLOW);
				s7.lines.add(String.format(tr("%d statistisch auffällige Beobachtungen (kleine grobe Fehler) aus dem letzten Test offen."), statistical[1]));
				s7.action(tr("Liste auffälliger Beobachtungen …"), Klein_Method.this::openFindingsList);
				s7.action(tr("Bericht statistischer Test"), Klein_Method.this::openStatisticalTestReport);
				s7.action(tr("Statistisch auffällige bereinigen …"), Klein_Method.this::startStatisticalCleanup);
			} else if (Klein_Method.this.isValidAdjustmentResultsAvailable()) {
				s7.lines.add(tr("Statistischer Test (kleine grobe Fehler): auf Knopfdruck - keine offenen Befunde bzw. noch nicht gerechnet."));
				s7.action(tr("Statistischer Test …"), Klein_Method.this::startStatisticalTest);
			}
			if (Klein_Method.this.isValidAdjustmentResultsAvailable())
				try {
					List<Weight_Part.Row> weights = Klein_Method.this.weightPart.compute();
					int w = Klein_Method.this.weightPart.worst(weights);
					if (w != GREEN) {
						s7.worst(YELLOW);
						List<String> names = new ArrayList<String>();
						for (Weight_Part.Row r : weights)
							if (r.status != GREEN)
								names.add(r.name + " (" + String.join(", ", r.notes) + ")");
						s7.lines.add(String.format(tr("Gewichtsverhältnis %s: %s"), w == RED ? tr("rot") : tr("gelb"), String.join("; ", names)));
						s7.action(tr("Gewichtsverhältnis …"), Klein_Method.this::openWeightBalance);
					}
				} catch (SQLException e) {
					// ohne Gewichtsverhaeltnis
				}
			steps.add(s7);
			return steps;
		}

		/** Aktion im Hintergrund mit Sperre, Ergebnistext anzeigen, danach neu bewerten und Assistenten wieder oeffnen. */
		private void background(java.util.concurrent.Callable<String> work) {
			Thread thread = busyThread(() -> {
				String text;
				ADJUSTMENT_LOCK.lock();
				try {
					text = work.call();
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				if (text != null)
					Klein_Method.this.showApproximationInfo(text);
				this.open();
			}, "Klein_Method_Workflow_Action");
			thread.setDaemon(true);
			thread.start();
		}

		private void repairZeroFixed(boolean repair) {
			this.background(() -> {
				int n = repair ? Klein_Method.this.databaseHandler.repairZeroFixedPoints() : Klein_Method.this.databaseHandler.undoZeroFixedPoints();
				Klein_Method.this.databaseHandler.deleteFreeSolution();
				Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
				Klein_Method.this.refreshJag3dView(true);
				Klein_Method.this.logger.log("Klein_Method: Festpunkte 0/0/0 " + (repair ? "in Neupunkte umgewandelt" : "Umwandlung zurueckgenommen") + ": " + n + ".");
				Klein_Method.this.getProtocol().logEvent("FESTPUNKTE 0/0/0: " + n + (repair ? " in Neupunkte umgewandelt" : " zurueck in die Festpunktgruppe"));
				return repair ? String.format(tr("%d Festpunkte ohne Koordinaten sind jetzt Neupunkte (Gruppe „%s“). Als Nächstes Näherungskoordinaten berechnen (Schritt 5)."),
						n, Klein_Method_Database_Handler.ZERO_FIXED_GROUP_NAME) : String.format(tr("%d Punkte zurück in ihre Festpunktgruppe."), n);
			});
		}

		/** Assistent: im Hintergrund bewerten, dann anzeigen (aus jedem Thread). */
		void open() {
			Thread thread = busyThread(() -> {
				List<Step> steps;
				List<String> stateLog;
				ADJUSTMENT_LOCK.lock();
				try {
					steps = this.recompute();
					stateLog = Klein_Method.this.isProjectOpen() ? Klein_Method.this.databaseHandler.readStateLog(40) : new ArrayList<String>();
				} catch (Exception e) {
					steps = null;
					stateLog = new ArrayList<String>();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				if (steps == null) {
					Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet."));
					return;
				}
				final List<Step> finalSteps = steps;
				final List<String> finalLog = stateLog;
				Platform.runLater(() -> this.show(finalSteps, finalLog));
			}, "Klein_Method_Workflow");
			thread.setDaemon(true);
			thread.start();
		}

		private void show(List<Step> steps, List<String> stateLog) {
			Alert alert = new Alert(AlertType.INFORMATION);
			alert.setTitle(tr("Klein_Method - Ablauf-Assistent"));
			alert.setHeaderText(tr("Ablauf vom Import bis zur Automatik - jeder Schritt muss grün (oder grau) sein"));
			javafx.scene.layout.VBox rows = new javafx.scene.layout.VBox(10);
			Runnable[] chosen = new Runnable[1];
			for (Step st : steps) {
				javafx.scene.control.Label dot = new javafx.scene.control.Label("●");
				dot.setStyle("-fx-font-size: 18px; -fx-text-fill: " + (st.status == RED ? "#E53935" : st.status == YELLOW ? "#F9A825" : st.status == GRAY ? "#9E9E9E" : "#43A047") + ";");
				javafx.scene.control.Label title = new javafx.scene.control.Label(st.title);
				title.setStyle("-fx-font-weight: bold;");
				javafx.scene.control.Label text = new javafx.scene.control.Label(String.join("\n", st.lines));
				text.setWrapText(true);
				text.setMaxWidth(640);
				javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8);
				for (int i = 0; i < st.actions.size(); i++) {
					javafx.scene.control.Button b = new javafx.scene.control.Button(st.actionLabels.get(i));
					Runnable action = st.actions.get(i);
					b.setOnAction(e -> {
						chosen[0] = action;
						alert.close();
					});
					buttons.getChildren().add(b);
				}
				javafx.scene.layout.VBox body = new javafx.scene.layout.VBox(3, title, text);
				if (!buttons.getChildren().isEmpty())
					body.getChildren().add(buttons);
				rows.getChildren().add(new javafx.scene.layout.HBox(8, dot, body));
			}
			TextArea log = new TextArea(stateLog.isEmpty() ? tr("(noch keine Einträge)") : String.join("\n", stateLog));
			log.setEditable(false);
			log.setPrefRowCount(7);
			log.setWrapText(false);
			javafx.scene.control.Label logTitle = new javafx.scene.control.Label(tr("Zustandsprotokoll des Projekts (Umrechnungen und Änderungen am Datenstand):"));
			logTitle.setStyle("-fx-font-weight: bold;");
			javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(rows);
			scroll.setFitToWidth(true);
			scroll.setPrefViewportHeight(430);
			scroll.setPrefViewportWidth(700);
			alert.getDialogPane().setContent(new javafx.scene.layout.VBox(10, scroll, logTitle, log));
			alert.setResizable(true);
			alert.showAndWait();
			if (chosen[0] != null)
				chosen[0].run();
		}

		/** siehe Klein_Method.checkWorkflowBeforeStart(). FX-Thread. */
		boolean checkBeforeStart() {
			List<Step> steps = this.last;
			if (steps == null)
				return false;
			List<String> red = new ArrayList<String>();
			for (Step st : steps)
				if (st.status == RED && !st.title.startsWith("7"))
					red.add(st.title + ": " + String.join(" ", st.lines).replace('\n', ' '));
			if (red.isEmpty())
				return false;
			// kein Fenster: Start immer, rote Schritte nur in Log und Protokoll (Ablauf-Assistent zeigt sie weiter)
			Klein_Method.this.logger.log("Klein_Method: Start trotz roter Schritte im Ablauf (" + red.size() + ").");
			Klein_Method.this.getProtocol().logEvent("ABLAUF: Start trotz " + red.size() + " roter Schritte - " + String.join(" | ", red));
			return false;
		}
	}

	// ============================================================================================================
	/**
	 * "Hoehe nur aus Hoehenfestpunkten (1D)" - nur dynamische und fixe AGL. Die 3D-Fest-/Anschlusspunkte geben dann nur
	 * die Lage vor (sigma_z0 = 10 m, Hoehe frei), die Hoehe kommt ausschliesslich aus den 1D-Hoehenfestpunkten ueber das
	 * Nivellement. Projekteinstellung; Anwendung je Stufe beim Tab-Klick, Ruecknahme exakt (Merktabelle).
	 */
	private final class Height_Datum_Part {

		private Boolean enabledCache = null;
		private String enabledFor = null;
		/** im Projekt ausdruecklich ein- oder ausgeschaltet (sonst Standard: ein, sofern verfuegbar). */
		private boolean explicit = false;

		boolean isEnabled() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return false;
				String key = String.valueOf(System.identityHashCode(SQLManager.getInstance().getDataBase()));
				if (this.enabledCache == null || !key.equals(this.enabledFor)) {
					String value = Klein_Method.this.databaseHandler.readProjectSetting(SETTING_HEIGHT_FROM_1D);
					this.explicit = value != null;
					this.enabledCache = value == null ? Klein_Method_Default_Values.HEIGHT_FROM_1D_DEFAULT : "1".equals(value);
					this.enabledFor = key;
				}
				return this.enabledCache;
			} catch (Exception e) {
				return false;
			}
		}

		private void store(boolean on) throws SQLException {
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_HEIGHT_FROM_1D, on ? "1" : "0");
			this.enabledCache = on;
			this.explicit = true;
			this.enabledFor = String.valueOf(System.identityHashCode(SQLManager.getInstance().getDataBase()));
		}

		/** Pruefung vor dem Anwenden (Sperre beim Aufrufer): {Fehler (rot), Hinweise}. */
		private List<List<String>> check(Klein_Method_Adjustment_Step step) throws SQLException {
			List<String> errors = new ArrayList<String>(), warnings = new ArrayList<String>();
			int[] c = Klein_Method.this.databaseHandler.readHeightDatumCounts();
			if (c[0] == 0)
				errors.add(tr("Keine Höhenfestpunkte (1D) als Fest- bzw. Anschlusspunkte im Projekt - die Höhe hätte keinen Bezug."));
			else {
				if (c[1] == 0)
					errors.add(tr("Kein Höhenfestpunkt ist über ein Nivellement mit dem Netz verbunden."));
				else if (c[1] < c[0])
					warnings.add(String.format(tr("%d von %d Höhenfestpunkten ohne Nivellement - sie tragen nichts zur Höhe bei."), c[0] - c[1], c[0]));
				if (c[0] < 3)
					warnings.add(String.format(tr("Nur %d Höhenfestpunkt(e) - empfohlen sind mindestens 3, damit ein fehlerhafter Höhenfestpunkt auffällt."), c[0]));
			}
			if (step == Klein_Method_Adjustment_Step.FIXED && c[2] == 0)
				errors.add(tr("Fixe AGL: kein Höhenfestpunkt (1D) ist varianzfrei - ohne ihn wäre die Höhe frei."));
			if (c[3] == 0)
				warnings.add(tr("Keine 3D-Fest- bzw. Anschlusspunkte - die Einstellung ändert nichts."));
			try {
				Klein_Method_Functions.Klein_Method_Quasigeoid.Height_Check hc = Klein_Method.this.quasigeoidPart.checkHeightSystems();
				if (hc != null && hc.verdict == 1)
					errors.add(String.format(tr("Höhensysteme gemischt: GNSS-Höhe minus Nivellementshöhe %s m - die Höhenfestpunkte (NHN) zuerst "
							+ "mit dem Quasigeoid in ellipsoidische Höhen umrechnen."), Klein_Method_Format.format(hc.difference, "", 3)));
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Hoehe nur aus 1D - Hoehensystem nicht pruefbar: " + e.getMessage());
			}
			List<List<String>> result = new ArrayList<List<String>>();
			result.add(errors);
			result.add(warnings);
			return result;
		}

		/** zur Stufe passend anwenden (Sperre beim Aufrufer); Text oder null. */
		private String applyFor(Klein_Method_Adjustment_Step step) throws SQLException {
			int[] r = Klein_Method.this.databaseHandler.applyHeightDatum(step);
			Klein_Method.this.databaseHandler.setHorizontalOnly3D(Klein_Method.this.databaseHandler.appliedHeightDatumStage() == 2);
			String stage = step == Klein_Method_Adjustment_Step.FIXED ? "fix" : "dynamisch";
			boolean fixedStep = step == Klein_Method_Adjustment_Step.FIXED;
			Klein_Method.this.logger.log("Klein_Method: Hoehe nur aus Hoehenfestpunkten (1D) angewendet (" + stage + "): " + r[0] + " 3D-Punkte, "
					+ (fixedStep ? "Hoehe sigma_z0 = " + Klein_Method_Database_Handler.HEIGHT_FREE_SIGMA + " m" : "Lage wie bisher, Hoehe getrennt kalibriert (1 mm bis "
							+ Klein_Method_Database_Handler.HEIGHT_FREE_SIGMA + " m)")
					+ (r[1] > 0 ? ", Gruppe \"" + Klein_Method_Database_Handler.HEIGHT_DATUM_GROUP_NAME + "\"" : "") + ".");
			Klein_Method.this.getProtocol().logEvent("HOEHE NUR AUS 1D: angewendet (" + stage + "), " + r[0] + (fixedStep ? " 3D-Punkte nur fuer die Lage"
					: " 3D-Punkte, Hoehe getrennt kalibriert"));
			if (r[0] == 0)
				return null;
			return fixedStep ? String.format(tr("Höhe nur aus Höhenfestpunkten (1D): %d 3D-Punkte bestimmen jetzt nur die Lage (Höhe frei)."), r[0])
					: String.format(tr("Höhe nur aus Höhenfestpunkten (1D): %d 3D-Punkte – die Lage wird wie bisher kalibriert, die Höhe getrennt davon. Passt die gegebene Höhe nicht zum Netz, wird sie frei."), r[0]);
		}

		private int undo(String reason) throws SQLException {
			int n = Klein_Method.this.databaseHandler.undoHeightDatum();
			Klein_Method.this.databaseHandler.setHorizontalOnly3D(false);
			if (n > 0) {
				Klein_Method.this.logger.log("Klein_Method: Hoehe nur aus 1D zurueckgenommen (" + reason + "): " + n + " 3D-Punkte wie vorher.");
				Klein_Method.this.getProtocol().logEvent("HOEHE NUR AUS 1D: zurueckgenommen (" + reason + "), " + n + " 3D-Punkte");
			}
			return n;
		}

		/** Tab-Klick (Hintergrund, Sperre beim Aufrufer). */
		void ensureOnTabClick() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				int applied = Klein_Method.this.databaseHandler.appliedHeightDatumStage();
				if (!this.isEnabled()) {
					if (applied != 0)
						this.undo("ausgeschaltet");
					Klein_Method.this.databaseHandler.setHorizontalOnly3D(false);
					return;
				}
				Klein_Method_Adjustment_Step step = Klein_Method.this.getCurrentAdjustmentStep();
				int wanted = step == Klein_Method_Adjustment_Step.DYNAMIC ? 2 : step == Klein_Method_Adjustment_Step.FIXED ? 3 : 0;
				if (applied != 0 && applied != wanted)
					this.undo("Stufenwechsel");
				if (wanted == 0)
					return;
				List<List<String>> check = this.check(step);
				if (!check.get(0).isEmpty()) {
					if (Klein_Method.this.databaseHandler.appliedHeightDatumStage() != 0)
						this.undo("Pruefung nicht bestanden");
					Klein_Method.this.logger.log("Klein_Method: Hoehe nur aus 1D nicht angewendet" + (this.explicit ? "" : " (Standard, nicht verfuegbar)") + ": "
							+ String.join(" | ", check.get(0)));
					if (this.explicit)
						Klein_Method.this.showApproximationInfo(tr("„Höhe nur aus Höhenfestpunkten (1D)“ ist eingeschaltet, kann aber nicht angewendet werden:")
							+ "\n\n• " + String.join("\n• ", check.get(0)));
					return;
				}
				String text = this.applyFor(step);
				if (text != null)
					Klein_Method.this.showApproximationInfo(text + (check.get(1).isEmpty() ? "" : "\n\n• " + String.join("\n• ", check.get(1))));
				Klein_Method.this.databaseHandler.setHorizontalOnly3D(Klein_Method.this.databaseHandler.appliedHeightDatumStage() == 2);
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: Hoehe nur aus 1D - Tab-Klick nicht moeglich: " + e.getMessage());
			}
		}

		/** Schalter (FX): im Hintergrund pruefen und anwenden bzw. zuruecknehmen. */
		void toggle(boolean on, java.util.function.Consumer<Boolean> done) {
			if (!Klein_Method.this.isProjectOpen() || Klein_Method.this.isAutomatikRunning()) {
				Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet bzw. gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				if (done != null)
					done.accept(!on);
				return;
			}
			Thread thread = busyThread(() -> {
				String text = null;
				boolean result = on;
				ADJUSTMENT_LOCK.lock();
				try {
					Klein_Method_Adjustment_Step step = Klein_Method.this.getCurrentAdjustmentStep();
					boolean active = step == Klein_Method_Adjustment_Step.DYNAMIC || step == Klein_Method_Adjustment_Step.FIXED;
					if (on) {
						List<List<String>> check = active ? this.check(step) : null;
						if (check != null && !check.get(0).isEmpty()) {
							result = false;
							text = tr("„Höhe nur aus Höhenfestpunkten (1D)“ nicht eingeschaltet:") + "\n\n• " + String.join("\n• ", check.get(0));
						} else {
							this.store(true);
							if (!active)
								text = tr("Eingeschaltet - wirkt erst in der dynamischen bzw. fixen AGL (beim Tab-Klick dort).");
							else {
								String applied = this.applyFor(step);
								text = (applied != null ? applied : tr("Eingeschaltet.")) + (check.get(1).isEmpty() ? "" : "\n\n• " + String.join("\n• ", check.get(1)))
										+ "\n\n" + tr("Die Ergebnisse sind damit ungültig - bitte neu ausgleichen bzw. die Automatik neu starten.");
							}
						}
					} else {
						this.store(false);
						int n = this.undo("ausgeschaltet");
						text = n > 0 ? String.format(tr("Ausgeschaltet: %d 3D-Punkte bestimmen wieder Lage und Höhe (Werte wie vorher)."), n) : null;
					}
					if (text != null) {
						Klein_Method.this.databaseHandler.deleteFreeSolution();
						Klein_Method.this.databaseHandler.storeLastVerifiedChecksum(Klein_Method.this.databaseHandler.computeCurrentStepChecksum());
						Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
						Klein_Method.this.refreshJag3dView(true);
					}
				} catch (Exception e) {
					result = !on;
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final boolean finalResult = result;
				if (text != null)
					Klein_Method.this.showApproximationInfo(text);
				if (done != null)
					Platform.runLater(() -> done.accept(finalResult));
			}, "Klein_Method_Height_Datum");
			thread.setDaemon(true);
			thread.start();
		}
	}

	// ============================================================================================================
	/**
	 * Wiederholte Messungen (Uebergewicht) je Messart: direkt aufeinanderfolgende Messungen desselben Standpunkt/Ziel
	 * (GNSS: dieselbe Sitzung) sind nicht unabhaengig - als einzelne Beobachtungen wiegen sie etwa Wurzel(n)-fach zu
	 * schwer. Je Messart einstellbar: "Regewichten" (Einzel-Sigma x Wurzel(n), von der Kalibrierung ausgenommen) oder
	 * "Zusammenfassen" (ein Mittel je Wiederholungsgruppe, Einzelwerte deaktiviert). Beides zuruecknehmbar.
	 */
	private final class Repetition_Part {

		/** in apply()/undo() gesetzt: danach (ohne Sperre) Hinweis, dass die Kalibrierung nicht mehr passt. */
		private volatile boolean changed = false;

		/**
		 * nach Aenderung der wiederholten Messungen: lief die Automatik in diesem Projekt schon, Hinweis in Protokoll und
		 * Statuszeile - kein Fenster, kein selbststaendiger Neustart (der Anwender startet die Automatik selbst).
		 */
		void reportChangeIfAutomaticRan() {
			if (!this.changed)
				return;
			this.changed = false;
			try {
				if (!"1".equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_AUTOMATIC_STARTED)))
					return;
			} catch (Exception e) {
				return;
			}
			Klein_Method.this.logger.log("Klein_Method: wiederholte Messungen geaendert, Automatik lief bereits - Hinweis ohne Neustart.");
			Klein_Method.this.protocol.logEvent("WIEDERHOLTE MESSUNGEN geaendert - die Kalibrierung gehoert zu den bisherigen Beobachtungen, Automatik bei Bedarf neu starten");
			Platform.runLater(() -> {
				if (Klein_Method.this.tabBuilder != null)
					Klein_Method.this.tabBuilder.setStatusText(tr("Wiederholte Messungen geändert - die Kalibrierung gehört zu den bisherigen Beobachtungen. "
							+ "Bei Bedarf „Klein_Method starten“."));
			});
		}

		/** offene Aufgaben je Messart: {Messart, gewuenschter Modus, angewendeter Modus, neue Gruppen, neue Messungen}. */
		private List<Object[]> pending() throws SQLException {
			List<Object[]> todo = new ArrayList<Object[]>();
			boolean mass = Klein_Method.this.massPart.prepare();
			for (ObservationType type : Klein_Method_Parameter.REPETITION_TYPES) {
				int applied = Klein_Method.this.databaseHandler.appliedRepetitionMode(type);
				int mode = Klein_Method.this.massPart.effectiveMode(type, Klein_Method.this.parameter.getRepetitionMode(type), mass, applied);
				boolean switchMode = applied != 0 && applied != mode;
				if (mode == Klein_Method_Default_Values.REPETITION_MODE_OFF && !switchMode)
					continue;
				int[] count = mode == Klein_Method_Default_Values.REPETITION_MODE_OFF ? new int[2] : Klein_Method.this.databaseHandler.countRepetitions(type);
				if (switchMode || count[0] > 0)
					todo.add(new Object[] { type, mode, applied, count[0], count[1] });
			}
			return todo;
		}

		private String signature(List<Object[]> todo) {
			StringBuilder s = new StringBuilder();
			for (Object[] t : todo)
				s.append(((ObservationType) t[0]).name()).append(':').append(t[1]).append(':').append(t[2]).append(':').append(t[3]).append(';');
			return s.toString();
		}

		/** Tab-Klick (Hintergrund, Sperre beim Aufrufer): offene Aufgaben mit Rueckfrage behandeln. */
		void offerOnTabClick() {
			try {
				if (!Klein_Method.this.isProjectOpen())
					return;
				// Massen-Basislinien zuerst (eigener Bericht mit verworfenen Epochen), danach die uebrigen Messarten wie bisher
				if (Klein_Method.this.massPart.prepare() && Klein_Method.this.massPart.effectiveMode(ObservationType.GNSS3D,
						Klein_Method.this.parameter.getRepetitionMode(ObservationType.GNSS3D), true, 0) != Klein_Method_Default_Values.REPETITION_MODE_OFF) {
					String mass = Klein_Method.this.massPart.thin();
					if (mass != null)
						Klein_Method.this.showApproximationInfo(mass);
				}
				List<Object[]> todo = this.pending();
				if (todo.isEmpty())
					return;
				String signature = this.signature(todo);
				if (signature.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_REPETITION_DECLINED)))
					return;
				// automatisch (ohne Rueckfrage) - Info danach, Ruecknahme jederzeit
				String text = this.apply(todo);
				Klein_Method.this.showApproximationInfo(tr("Übergewicht wiederholter Messungen automatisch behandelt:") + "\n\n" + text);
				this.reportChangeIfAutomaticRan();
			} catch (Exception e) {
				Klein_Method.this.logger.log("Klein_Method: wiederholte Messungen konnten nicht behandelt werden: " + e.getMessage());
			}
		}

		private String describe(List<Object[]> todo) {
			StringBuilder lines = new StringBuilder();
			for (Object[] t : todo) {
				ObservationType type = (ObservationType) t[0];
				int mode = (Integer) t[1], applied = (Integer) t[2], groups = (Integer) t[3], count = (Integer) t[4];
				lines.append("• ").append(repetitionTypeName(type)).append(": ");
				if (applied != 0 && applied != mode)
					lines.append(String.format(tr("%s → %s (Umstellung)"), repetitionModeName(applied), repetitionModeName(mode)));
				else
					lines.append(String.format(tr("%d Wiederholungsgruppen (%d Messungen) → %s"), groups, count, repetitionModeName(mode)));
				lines.append("\n");
			}
			return lines.toString();
		}

		/** Rueckfrage (wartet; aus einem Hintergrund-Thread). */
		private boolean confirm(List<Object[]> todo) throws InterruptedException {
			String text = tr("Direkt aufeinanderfolgende Messungen desselben Standpunkts und Ziels (GNSS: Epochen einer Sitzung, Lasertracker: "
					+ "Messreihen auf einen Punkt) sind nicht unabhängig. Als einzelne Beobachtungen wiegen sie etwa Wurzel(n)-fach zu schwer und "
					+ "drängen die übrigen Messungen an den Rand.\n\nZusammenfassen: je Wiederholungsgruppe ein Mittel, die Einzelwerte werden "
					+ "deaktiviert (nicht gelöscht); Genauigkeit mindestens die Streuung der Gruppe.\n"
					+ "Sprünge und Drift innerhalb einer Gruppe werden vorher geprüft. Rücknahme jederzeit über Menü Klein_Method.\n\n")
					+ this.describe(todo);
			java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			boolean[] confirmed = new boolean[] { false };
			Platform.runLater(() -> {
				try {
					ButtonType yes = new ButtonType(tr("Ausführen (empfohlen)"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType no = new ButtonType(tr("Nicht jetzt"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					Alert alert = new Alert(AlertType.CONFIRMATION, "", yes, no);
					alert.setTitle(tr("Klein_Method - Wiederholte Messungen"));
					alert.setHeaderText(tr("Wiederholte Messungen behandeln?"));
					TextArea area = new TextArea(text);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(12);
					area.setPrefColumnCount(72);
					alert.getDialogPane().setContent(area);
					alert.setResizable(true);
					confirmed[0] = alert.showAndWait().filter(button -> button == yes).isPresent();
				} finally {
					latch.countDown();
				}
			});
			latch.await();
			return confirmed[0];
		}

		/** einmalige Freigabe fuer den Start nach dem Anwenden (FX-Thread). */
		private boolean startCheckPassed = false;

		/** true = Behandlung weicht von der Einstellung ab (Umstellung) - auch nach "Nicht jetzt" erneut nachfragen. */
		private boolean hasSwitch(List<Object[]> todo) {
			for (Object[] t : todo) {
				int mode = (Integer) t[1], applied = (Integer) t[2];
				if (applied != 0 && applied != mode)
					return true;
			}
			return false;
		}

		/** siehe Klein_Method.checkRepetitionsBeforeStart(). FX-Thread. */
		boolean checkBeforeStart(Runnable restart) {
			if (this.startCheckPassed) {
				this.startCheckPassed = false;
				return false;
			}
			if (!Klein_Method.this.isProjectOpen())
				return false;
			List<Object[]> todo;
			String signature;
			try {
				todo = this.pending();
				if (todo.isEmpty())
					return false;
				signature = this.signature(todo);
				if (!this.hasSwitch(todo) && signature.equals(Klein_Method.this.databaseHandler.readProjectSetting(SETTING_REPETITION_DECLINED)))
					return false;
			} catch (SQLException e) {
				Klein_Method.this.logger.log("Klein_Method: Start-Pruefung wiederholte Messungen nicht moeglich: " + e.getMessage());
				return false;
			}
			Klein_Method.this.logger.log("Klein_Method: Start - Einstellung wiederholte Messungen noch nicht angewendet (" + signature + "), wird vorher angewendet.");
			Thread thread = busyThread(() -> {
				String text = null;
				boolean valid = false;
				ADJUSTMENT_LOCK.lock();
				try {
					text = this.apply(todo);
					this.changed = false; // Start folgt direkt - kein zusaetzliches Neustart-Angebot
					valid = Klein_Method.this.runHeadlessAdjustmentSynchronously() == EstimationStateType.ERROR_FREE_ESTIMATION;
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				final boolean startNow = valid;
				final String message = text;
				Platform.runLater(() -> {
					if (startNow) {
						this.startCheckPassed = true;
						Klein_Method.this.logger.log("Klein_Method: wiederholte Messungen vor dem Start angewendet - Automatik startet.");
						if (message != null)
							Klein_Method.this.getProtocol().logEvent("WIEDERHOLTE MESSUNGEN: vor dem Start der Automatik angewendet");
						restart.run();
						this.startCheckPassed = false;
					} else if (message != null)
						Klein_Method.this.showApproximationInfo(message + "\n\n" + tr("Die Automatik wurde nicht gestartet - bitte die Ausgleichung prüfen."));
				});
			}, "Klein_Method_Repetitions_Start");
			thread.setDaemon(true);
			thread.start();
			return true;
		}

		/** siehe Klein_Method.offerRepetitionsAfterSettingChange(). */
		void offerAfterSettingChange() {
			if (!Klein_Method.this.isProjectOpen() || Klein_Method.this.isAutomatikRunning() || Klein_Method.this.approximationBusy())
				return;
			Thread thread = busyThread(() -> {
				String text = null;
				ADJUSTMENT_LOCK.lock();
				try {
					List<Object[]> todo = this.pending();
					if (todo.isEmpty())
						return;
					text = tr("Übergewicht wiederholter Messungen automatisch behandelt:") + "\n\n" + this.apply(todo);
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				if (text != null)
					Klein_Method.this.showApproximationInfo(text);
				this.reportChangeIfAutomaticRan();
			}, "Klein_Method_Repetitions_Setting");
			thread.setDaemon(true);
			thread.start();
		}

		/** verworfene Epochen (Massen-Basislinien, robustes Mittel) der letzten Behandlung. */
		volatile int lastRejected = 0;

		/** behandeln (Sperre beim Aufrufer); Ergebnistext. */
		String apply(List<Object[]> todo) throws SQLException {
			StringBuilder text = new StringBuilder();
			this.lastRejected = 0;
			Klein_Method.this.massPart.prepare();
			for (Object[] t : todo) {
				ObservationType type = (ObservationType) t[0];
				int mode = (Integer) t[1], applied = (Integer) t[2];
				if (applied != 0 && applied != mode) {
					int[] u = Klein_Method.this.databaseHandler.undoRepetitionHandling(type);
					Klein_Method.this.logger.log("Klein_Method: wiederholte Messungen " + type.name() + " - bisherige Behandlung zurueckgenommen: " + u[0]
							+ " Mittel geloescht, " + u[1] + " Einzelwerte aktiviert, " + u[2] + " Unsicherheiten wiederhergestellt.");
					Klein_Method.this.getProtocol().logEvent("WIEDERHOLTE MESSUNGEN " + type.name() + ": Umstellung, bisherige Behandlung zurueckgenommen (" + u[0]
							+ " Mittel geloescht, " + u[1] + " Einzelwerte aktiviert, " + u[2] + " Unsicherheiten wiederhergestellt)");
					if (mode == Klein_Method_Default_Values.REPETITION_MODE_OFF)
						text.append(String.format(tr("%s: Behandlung zurückgenommen."), repetitionTypeName(type))).append("\n");
				}
				if (mode == Klein_Method_Default_Values.REPETITION_MODE_OFF)
					continue;
				double[] r = Klein_Method.this.databaseHandler.applyRepetitionHandling(type, mode);
				this.lastRejected += r.length > 7 ? (int) r[7] : 0;
				List<String> scatteredPairs = new ArrayList<String>();
				for (String finding : Klein_Method.this.databaseHandler.getLastRepetitionFindings()) {
					Klein_Method.this.logger.log("Klein_Method: Wiederholung " + finding);
					if (finding.startsWith("STARK STREUEND ")) {
						scatteredPairs.add(finding.substring("STARK STREUEND ".length(), finding.indexOf(':')));
						Klein_Method.this.getProtocol().logEvent("WIEDERHOLTE MESSUNGEN " + type.name() + ": " + finding);
					}
				}
				if (!scatteredPairs.isEmpty())
					text.append(String.format(tr("%s: %d Punktpaar(e) mit stark streuenden Epochen (Lage über 10 cm) - Float-Lösung oder Antenne bewegt? "
							+ "Ihre Mittel bekommen die Streuung als Genauigkeit: %s"), repetitionTypeName(type), scatteredPairs.size(),
							String.join(", ", scatteredPairs.subList(0, Math.min(8, scatteredPairs.size()))) + (scatteredPairs.size() > 8 ? ", …" : "")))
							.append("\n");
				String modeText = mode == Klein_Method_Default_Values.REPETITION_MODE_REWEIGHT ? "regewichtet"
						: mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE ? "zusammengefasst (kalibriert)" : "zusammengefasst";
				Klein_Method.this.logger.log(String.format(Locale.ROOT, "Klein_Method: wiederholte Messungen %s %s: %d Gruppen, %d Messungen, %d geteilt (Sprung), "
						+ "%d driftend, mittlerer Faktor %.2f", type.name(), modeText, (int) r[0], (int) r[1], (int) r[2], (int) r[3], r[4]));
				Klein_Method.this.getProtocol().logEvent(String.format(Locale.ROOT, "WIEDERHOLTE MESSUNGEN %s %s: %d Gruppen, %d Messungen, %d geteilt, "
						+ "%d driftend%s", type.name(), modeText, (int) r[0], (int) r[1], (int) r[2], (int) r[3],
						mode == Klein_Method_Default_Values.REPETITION_MODE_REWEIGHT ? String.format(Locale.ROOT, ", mittlerer Faktor Wurzel(n) %.2f", r[4]) : ""));
				if (mode == Klein_Method_Default_Values.REPETITION_MODE_REWEIGHT)
					text.append(String.format(tr("%s: %d Wiederholungsgruppen (%d Messungen) regewichtet, mittlerer Faktor %s."), repetitionTypeName(type),
							(int) r[0], (int) r[1], String.format(Locale.GERMANY, "%.2f", r[4]))).append("\n");
				else
					text.append(String.format(tr("%s: %d Mittel aus %d Messungen gebildet, Einzelwerte deaktiviert."), repetitionTypeName(type),
							(int) r[0], (int) r[1])).append("\n");
				if (r[2] > 0 || r[3] > 0)
					text.append("   ").append(String.format(tr("Sprung-/Driftprüfung: %d Gruppen geteilt, %d driftende Gruppen mit halber Drift als "
							+ "Genauigkeit - Einzelheiten im Log."), (int) r[2], (int) r[3])).append("\n");
				if (r.length > 6 && mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE)
					Klein_Method.this.getProtocol().logEvent("WIEDERHOLTE MESSUNGEN " + type.name() + ": " + (int) r[6]
							+ " Mittel mit eigener Genauigkeit (Streuung/Drift) werden in der Automatik kalibriert");
				if (r.length > 5 && r[5] > 0) {
					text.append("   ").append(String.format(tr("%d stark streuende Gruppen: Genauigkeit des Mittels = Streuung der Einzelmessungen."), (int) r[5]))
							.append("\n");
					if (mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE)
						text.append("   ").append(tr("Die Streuung ist nur der Startwert - die Automatik kalibriert diese Mittel aus ihren Verbesserungen nach."))
								.append("\n");
					Klein_Method.this.logger.log("Klein_Method: wiederholte Messungen " + type.name() + ": " + (int) r[5] + " Gruppen mit Streuung als Genauigkeit.");
				}
			}
			this.afterChange();
			this.changed = true;
			return text.append("\n").append(tr("Die Ergebnisse sind damit ungültig - bitte neu ausgleichen.")).toString();
		}

		/** alle Behandlungen zuruecknehmen (Sperre beim Aufrufer); Ergebnistext oder null, wenn nichts zurueckzunehmen war. */
		String undoAll() throws SQLException {
			int deleted = 0, enabled = 0, restored = 0;
			for (ObservationType type : Klein_Method_Parameter.REPETITION_TYPES) {
				if (Klein_Method.this.databaseHandler.appliedRepetitionMode(type) == 0)
					continue;
				int[] u = Klein_Method.this.databaseHandler.undoRepetitionHandling(type);
				deleted += u[0];
				enabled += u[1];
				restored += u[2];
				Klein_Method.this.getProtocol().logEvent("WIEDERHOLTE MESSUNGEN " + type.name() + ": zurueckgenommen (" + u[0] + " Mittel geloescht, " + u[1]
						+ " Einzelwerte aktiviert, " + u[2] + " Unsicherheiten wiederhergestellt)");
			}
			if (deleted + enabled + restored == 0)
				return null;
			this.afterChange();
			this.changed = true;
			// nach der Ruecknahme nicht sofort wieder anbieten
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_REPETITION_DECLINED, this.signature(this.pending()));
			Klein_Method.this.logger.log("Klein_Method: wiederholte Messungen zurueckgenommen: " + deleted + " Mittel geloescht, " + enabled + " Einzelwerte aktiviert, "
					+ restored + " Unsicherheiten wiederhergestellt.");
			return String.format(tr("Zurückgenommen: %d Mittel gelöscht, %d Einzelwerte wieder aktiviert, %d Unsicherheiten wiederhergestellt."), deleted, enabled,
					restored);
		}

		/** nur GNSS zuruecknehmen (vor der Ruecknahme der lokalen Umrechnung). */
		void undoGnss() throws SQLException {
			for (ObservationType type : new ObservationType[] { ObservationType.GNSS1D, ObservationType.GNSS2D, ObservationType.GNSS3D })
				if (Klein_Method.this.databaseHandler.appliedRepetitionMode(type) != 0) {
					int[] u = Klein_Method.this.databaseHandler.undoRepetitionHandling(type);
					Klein_Method.this.logger.log("Klein_Method: vor der Ruecknahme der Umrechnung wiederholte Messungen " + type.name() + " zurueckgenommen ("
							+ u[0] + " Mittel geloescht).");
				}
		}

		/** Merkwert "abgelehnt" auf den aktuellen Stand setzen (nach Ruecknahme der Umrechnung). */
		void declineCurrent() throws SQLException {
			Klein_Method.this.databaseHandler.storeProjectSetting(SETTING_REPETITION_DECLINED, this.signature(this.pending()));
		}

		private void afterChange() throws SQLException {
			Klein_Method.this.databaseHandler.deleteFreeSolution();
			// eigene Aenderung der Unsicherheiten - keine "falsche Reihenfolge" melden
			Klein_Method.this.databaseHandler.storeLastVerifiedChecksum(Klein_Method.this.databaseHandler.computeCurrentStepChecksum());
			Platform.runLater(() -> Klein_Method.this.setValidAdjustmentResultsAvailable(false));
			Klein_Method.this.refreshJag3dView(true);
			Platform.runLater(Klein_Method_Tab_Builder.Klein_Method_Map_Window::notifyDataChanged);
		}

		/** Knopf/Menue (Hintergrund): behandeln bzw. zuruecknehmen mit Rueckfrage. */
		void onDemand(boolean apply) {
			if (!Klein_Method.this.isProjectOpen() || Klein_Method.this.approximationBusy()
					|| Klein_Method.this.isAutomaticTaskRunning()) {
				Klein_Method.this.showApproximationInfo(tr("Kein Projekt geöffnet bzw. gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Thread thread = busyThread(() -> {
				String text = null;
				ADJUSTMENT_LOCK.lock();
				try {
					if (apply) {
						List<Object[]> todo = this.pending();
						if (todo.isEmpty())
							text = tr("Keine (neuen) wiederholten Messungen für die eingestellte Behandlung - nichts zu tun.");
						else if (this.confirm(todo))
							text = this.apply(todo);
					} else if (this.confirmUndo()) {
						text = this.undoAll();
						if (text == null)
							text = tr("Es gibt keine Behandlung wiederholter Messungen zum Zurücknehmen.");
					}
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
				if (text != null)
					Klein_Method.this.showApproximationInfo(text);
				this.reportChangeIfAutomaticRan();
			}, "Klein_Method_Repetitions");
			thread.setDaemon(true);
			thread.start();
		}

		private boolean confirmUndo() throws InterruptedException {
			java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			boolean[] confirmed = new boolean[] { false };
			Platform.runLater(() -> {
				try {
					ButtonType yes = new ButtonType(tr("Zurücknehmen"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
					ButtonType no = new ButtonType(tr("Nicht jetzt"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
					Alert alert = new Alert(AlertType.CONFIRMATION, tr("Alle Behandlungen wiederholter Messungen zurücknehmen? Angelegte Mittel werden gelöscht, "
							+ "Einzelwerte wieder aktiviert und regewichtete Unsicherheiten auf den vorherigen Wert gesetzt."), yes, no);
					alert.setTitle(tr("Klein_Method - Wiederholte Messungen"));
					alert.setHeaderText(tr("Zurücknehmen?"));
					confirmed[0] = alert.showAndWait().filter(button -> button == yes).isPresent();
				} finally {
					latch.countDown();
				}
			});
			latch.await();
			return confirmed[0];
		}

		/**
		 * Vergleichsdatei am Ende einer fertigen Automatik: Behandlung je Messart, Redundanzanteile (Gewicht im Netz) und
		 * ausgeglichene Koordinaten - zum Vergleich der Varianten "Regewichten" und "Zusammenfassen". Nur lokal im
		 * Protokollordner.
		 */
		void writeComparisonFileAsync() {
			Thread thread = busyThread(() -> {
				ADJUSTMENT_LOCK.lock();
				try {
					if (!Klein_Method.this.isProjectOpen())
						return;
					StringBuilder t = new StringBuilder();
					String nl = System.lineSeparator();
					t.append("KLEIN_METHOD ").append(Klein_Method_Default_Values.PACKAGE_VERSION).append(" - VERGLEICH WIEDERHOLTE MESSUNGEN").append(nl);
					t.append("Erstellt: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))).append(nl);
					// Vergleichbar sind nur Laeufe derselben Ausgleichungsstufe
					String stepText;
					try {
						Klein_Method_Adjustment_Step step = Klein_Method.this.getCurrentAdjustmentStep();
						stepText = step == null ? "nicht eindeutig" : step.name();
					} catch (Exception e) {
						stepText = "unbekannt";
					}
					t.append("Ausgleichungsstufe: ").append(stepText).append(nl);
					t.append(String.format(Locale.ROOT, "Max-logPost am Laufende: %.4f (Grenzwert %.3f)", Klein_Method.this.functions.getMaxLogPostValue(Klein_Method.this.parameter),
							Klein_Method.this.functions.getMaxLogPostThreshold(Klein_Method.this.parameter))).append(nl).append(nl);
					t.append(String.format(Locale.ROOT, "%-22s %-15s %-15s %8s %8s %10s %8s %10s", "Messart", "Einstellung", "angewendet", "aktiv",
							"regew.", "Summe r", "Anteil%", "r regew.")).append(nl);
					double total = 0;
					List<Object[]> rows = new ArrayList<Object[]>();
					for (ObservationType type : Klein_Method_Parameter.REPETITION_TYPES) {
						double[] s = Klein_Method.this.databaseHandler.readRepetitionStatistics(type);
						if (s[0] == 0)
							continue;
						total += s[2];
						rows.add(new Object[] { type, s });
					}
					for (Object[] row : rows) {
						ObservationType type = (ObservationType) row[0];
						double[] s = (double[]) row[1];
						int applied = Klein_Method.this.databaseHandler.appliedRepetitionMode(type);
						t.append(String.format(Locale.ROOT, "%-22s %-15s %-15s %8d %8d %10.2f %8.1f %10.2f", type.name(),
								repetitionModeKey(Klein_Method.this.parameter.getRepetitionMode(type)), applied == 0 ? "-" : repetitionModeKey(applied),
								(int) s[0], (int) s[1], s[2], total > 0 ? 100.0 * s[2] / total : 0.0, s[3])).append(nl);
					}
					t.append(String.format(Locale.ROOT, "%-22s %-15s %-15s %8s %8s %10.2f", "Summe", "", "", "", "", total)).append(nl).append(nl);
					t.append("AUSGEGLICHENE KOORDINATEN (Punkt, Rechtswert/Ost, Hochwert/Nord, Hoehe)").append(nl);
					for (Klein_Method_Point_Coordinate_Row p : Klein_Method.this.databaseHandler.readAllActivePointCoordinates())
						t.append(String.format(Locale.ROOT, "%-16s %16s %16s %12s", p.getName(), fmt(p.getRechtswert()), fmt(p.getHochwert()), fmt(p.getHoehe())))
								.append(nl);
					String modes = "";
					for (ObservationType type : Klein_Method_Parameter.REPETITION_TYPES)
						modes += Klein_Method.this.parameter.getRepetitionMode(type);
					String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
					File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
					if (!folder.exists())
						folder.mkdirs();
					File file = new File(folder, timestamp + "_Vergleich_Wiederholungen_" + modes + ".txt");
					try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
						writer.print(t.toString());
					}
					Klein_Method.this.logger.log("Klein_Method: Vergleichsdatei wiederholte Messungen geschrieben: " + file.getName());
					Klein_Method.this.getProtocol().logEvent("WIEDERHOLTE MESSUNGEN: Vergleichsdatei " + file.getName());
				} catch (Exception e) {
					Klein_Method.this.logger.log("Klein_Method: Vergleichsdatei wiederholte Messungen nicht moeglich: " + e.getMessage());
				} finally {
					ADJUSTMENT_LOCK.unlock();
				}
			}, "Klein_Method_Repetition_Comparison");
			thread.setDaemon(true);
			thread.start();
		}

		private String fmt(Double value) {
			return value == null ? "-" : String.format(Locale.ROOT, "%.4f", value);
		}
	}

	/** Anzeigename einer Messart (Oberflaeche). */
	public static String repetitionTypeName(ObservationType type) {
		switch (type) {
		case DIRECTION: return tr("Horizontalrichtungen");
		case ZENITH_ANGLE: return tr("Zenitwinkel");
		case HORIZONTAL_DISTANCE: return tr("Horizontalstrecken");
		case SLOPE_DISTANCE: return tr("Schrägstrecken");
		case LEVELING: return tr("Nivellement");
		case GNSS1D: return tr("GNSS-Basislinien 1D");
		case GNSS2D: return tr("GNSS-Basislinien 2D");
		default: return tr("GNSS-Basislinien 3D");
		}
	}

	/** Anzeigename eines Modus (Oberflaeche). */
	public static String repetitionModeName(int mode) {
		return mode == Klein_Method_Default_Values.REPETITION_MODE_REWEIGHT ? tr("Regewichten")
				: mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE ? tr("Zusammenfassen + Kalibrieren")
				: mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE ? tr("Zusammenfassen") : tr("Aus");
	}

	/** Modus fuer Datei/Protokoll (deutsch, ASCII). */
	static String repetitionModeKey(int mode) {
		return mode == Klein_Method_Default_Values.REPETITION_MODE_REWEIGHT ? "regewichten"
				: mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE ? "zusammenf+kalib"
				: mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE ? "zusammenfassen" : "aus";
	}

}
// V1.00_RC3_Klein_Method.java
