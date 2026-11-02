// V1.00_RC3_Klein_Method_Iteration_Task.java
package org.applied_geodesy.klein_method.logic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

import org.applied_geodesy.adjustment.EstimationStateType;
import org.applied_geodesy.klein_method.Klein_Method;
import org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface;
import org.applied_geodesy.klein_method.logging.Klein_Method_Logger;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Default_Values;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Evaluation_Method;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Format;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Process_Phase;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;
import org.applied_geodesy.util.unit.LengthUnit;
import org.applied_geodesy.util.unit.ScaleUnit;
import static org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.tr;

/**
 * Fuehrt die Endlosschleife der Automatik aus. Jede AEUSSERE Schleifenrunde
 * (=eine Iteration, Klein_Method_Parameter.incrementIterationCount()) fuehrt
 * GENAU EINEN Kalibrierungsschritt der aktuellen Phase aus (siehe
 * Klein_Method_Process_Phase):
 *
 *   Phase 1 GROUP_CALIBRATION:      EIN Versuch, Gruppenunsicherheit
 *                                    (σa/σb/σc je Beobachtungstyp) neu zu
 *                                    kalibrieren. Wechselt erst zu Phase 2,
 *                                    wenn ALLE Typen/Gruppen im Zielbereich
 *                                    oder an ihrer Untergrenze sind - kann
 *                                    sich also ueber MEHRERE Iterationen
 *                                    erstrecken.
 *   Phase 2 INDIVIDUAL_CALIBRATION: individuelle Messunsicherheit
 *                                    (oh0/osh0/ov0/os0) fuer Beobachtungen
 *                                    im aktuellen logPost-Bearbeitungs-
 *                                    bereich anpassen.
 *
 * Phase 1 ist IMMER vollstaendig abgeschlossen, bevor Phase 2 beginnt -
 * aber NICHT "ein Durchlauf durch beide Phasen = eine Iteration" (siehe
 * Klein_Method_Process_Phase fuer die ausfuehrliche Erklaerung). Im
 * Klein_Method_Protocol (eine Zeile je Iteration, mit Phase-Spalte) ist
 * das direkt nachvollziehbar.
 *
 * Laeuft als javafx.concurrent.Task in einem Hintergrund-Thread, damit die
 * JAG3D-Oberflaeche waehrend der Berechnung nicht einfriert. Der Task ist
 * abbrechbar (cancel()), z.B. ueber einen zweiten Button ("Stop").
 */
public class Klein_Method_Iteration_Task extends Task<Void> {

	private static final int MAX_ITERATIONS = 10_000; // Sicherheitsnetz gegen echte Endlosschleife
	// eigenes, UNABHAENGIGES Sicherheitsnetz fuer die Anzahl automatischer Kaskaden-Neustarts DIESES Laufs -
	// notwendig, weil jeder Neustart resetIterationCount() aufruft und damit MAX_ITERATIONS oben fuer JEDEN einzelnen
	// Pass wieder von vorn zaehlt. 20 Passes sind im Normalfall weit mehr, als selbst ein grosses, traeges Massennetz
	// bis zur echten Konvergenz benoetigt.
	private static final int MAX_CASCADE_PASSES = 20;
	// reines Anzeige-Fenster fuer den jetzt vollstaendig von logPost entkoppelten Fortschrittsbalken (siehe
	// Verwendungsstelle in call()) - ohne jede fachliche Bedeutung, rein kosmetisch fuer einen wiederkehrenden "die
	// Automatik laeuft"-Puls.
	private static final int PROGRESS_PULSE_WINDOW = 20;
	private static final long DELAY_BETWEEN_ITERATIONS_MS = 200; // schont CPU/Datenbank

	private Klein_Method_Parameter parameter;
	private Klein_Method_Database_Handler_Interface databaseHandler;
	private Klein_Method_Functions functions;
	private Klein_Method_Logger logger;
	private boolean singleStepOnly;
	// Zuordnung der groben Fehler vor Phase 2 nur einmal je Lauf; Grund des Laufendes
	private boolean grossErrorAssignmentDone = false;
	private final long runStartMillis = System.currentTimeMillis();
	private String endReason = null;

	// ---- Nachlauf auf den Grenzwert (nach Erreichen der Abbruchgrenze ab DL 2) ----
	private boolean followUpStarted = false;
	private double followUpPrevious = Double.NaN;
	private boolean followUpFinished = false;
	private boolean followUpJustStarted = false;
	private double followUpBest = Double.NaN;
	private int followUpBestIteration = 0;
	private int followUpNoImprovement = 0;
	private int followUpIterations = 0;
	/** Zusatz zum Laufende ("Grenzwert nicht erreicht - bester Stand ..."), sonst null. */
	private String followUpNote = null;
	/** true = Laufende mit zurueckgeschriebenem besten Stand (Hinweisfenster am Ende). */
	private boolean followUpRestored = false;
	/** true = im Projekt liegt eine Sicherung dieses Laufs (Durchlauf-1-Stand unter dem Grenzwert oder Nachlauf-Bestwert). */
	private boolean bestStateStored = false;
	/** Max-logPost der Sicherung (NaN = keine). */
	private double bestStateValue = Double.NaN;
	/** true = der zurueckgeschriebene Stand liegt unter dem Grenzwert (z. B. Durchlauf 1). */
	private boolean restoredBelowThreshold = false;
	/** fuer das Hinweisfenster: Max-logPost nach der Schlussausgleichung, Durchlauf und Iteration des besten Stands. */
	private double followUpFinalMax = Double.NaN;
	private int followUpFinalPass = 0, followUpFinalIteration = 0;

	/**
	 * @param singleStepOnly true = genau EIN Durchlauf (lesen/bewerten/
	 *        neuberechnen/schreiben), unabhaengig vom Grenzwert - fuer
	 *        die manuelle "Einzelschritt"-Bedienung bei Bewertungsmethode EP.
	 *        false = normale Endlosschleife bis Grenzwert erreicht (logPost).
	 */
	public Klein_Method_Iteration_Task(
			Klein_Method_Parameter parameter,
			Klein_Method_Database_Handler_Interface databaseHandler,
			Klein_Method_Functions functions,
			Klein_Method_Logger logger,
			boolean singleStepOnly) {
		this.parameter = parameter;
		this.databaseHandler = databaseHandler;
		this.functions = functions;
		this.logger = logger;
		this.singleStepOnly = singleStepOnly;
	}

	@Override
	protected Void call() throws Exception {
		this.logger.log("Klein_Method: " + (this.singleStepOnly ? "Einzelschritt" : "Iteration") + " gestartet.");
		this.functions.setIndividualStepDamping(1.0);

		// ausserhalb des folgenden if-Blocks deklariert, damit die Kaskaden-Steuerung weiter unten (Pass 1 -> Pass
		// 2-Neustart) denselben, bereits ermittelten Ausgleichungsschritt wiederverwenden kann - die Ausgleichungsart
		// (FREE/FIXED/DYNAMIC) aendert sich innerhalb EINES Laufs nicht, ein zweiter
		// determineCurrentAdjustmentStep()-Aufruf fuer Pass 2 waere unnoetig. Bleibt null, wenn singleStepOnly
		// (Kaskade dort ohnehin nie ausgeloest, siehe Schleife unten).
		Klein_Method_Adjustment_Step currentStep = null;

		// Debug-Fenster (Anhalten und Bestaetigen) entfernt - die dynamische AGL schreibt jede Iteration direkt.
		try {
			return this.runIterations(currentStep);
		} finally {
			// eigene Aenderungen dieses Laufs sofort in die Pruefsummen-Baseline uebernehmen.
			Klein_Method.getInstance().acceptOwnChangesIntoChecksum(this.singleStepOnly ? "Einzelschritt" : "Automatik");
			// Am Laufende (fertig, Stop oder Fehler) JAG3D-Anzeige immer nachladen - ersetzt Stop + F5 von Hand
			Klein_Method.getInstance().refreshJag3dView(true);
		}
	}

	private Void runIterations(Klein_Method_Adjustment_Step initialStep) throws Exception {
		Klein_Method_Adjustment_Step currentStep = initialStep;

		// Bei einem neuen Automatik-Lauf den Startwert (1. Iteration = rot)
		// und den Ablauf-Phasenstatus (immer wieder bei Phase 1 beginnen)
		// zuruecksetzen. Beim Einzelschritt bleibt beides aus einem evtl.
		// laufenden Automatik-Kontext unangetastet.
		if (!this.singleStepOnly) {
			this.parameter.resetPhase1CalibratedThisPass();
			// reiner Hygiene-Reset, derselbe "echter Lauf-Start"-Zeitpunkt wie oben - funktional nicht zwingend
			// noetig (der Zaehler wird ohnehin JEDE Iteration ueberschrieben, siehe recompute()-Aufrufstelle unten),
			// verhindert aber, dass zwischen Start-Klick und der ersten abgeschlossenen Iteration kurzzeitig noch ein
			// Wert aus einem fruehreren Lauf angezeigt wird.
			this.parameter.resetElementsProcessedLastIteration();
			// derselbe "echter Lauf-Start"-Zeitpunkt wie die Resets oben - hier, vor der allerersten Iteration (=
			// "Iteration 0") dieses Automatik-Laufs, wird die aktuell gewaehlte Phase-2-Daempfungsformel einmalig
			// protokolliert (Konsole UND Klein_Method.log, siehe Klein_Method_Logger.log() - nicht dasselbe wie das
			// Lauf-Protokoll outAdjustmentDetails).
			this.logger.log("Klein_Method: Iteration 0 - aktive Phase-2-Dämpfungsformel (GNSS/PERCENTAGE): "
					+ this.parameter.getDampingFormulaType().getDisplayName());
			// Schwarmwissen bei jedem Start der Automatik frisch abrufen (still; Teilen bleibt bei der Rueckfrage)
			Klein_Method.getInstance().refreshSwarmExperienceQuietly();
			currentStep = this.functions.determineCurrentAdjustmentStep(this.databaseHandler);
			// Untergrenze der Festpunkte: eingestellte Werte nur mit aktiven GNSS-Basislinien, sonst 0,01 mm
			try {
				this.parameter.setPointFloorGnssActive(this.databaseHandler.hasActiveGnssObservations());
			} catch (SQLException e) {
				this.parameter.setPointFloorGnssActive(true);
			}
			if (currentStep == Klein_Method_Adjustment_Step.DYNAMIC)
				Klein_Method.getInstance().getProtocol().logEvent("UNTERGRENZE FESTPUNKTE: "
						+ (this.parameter.isPointFloorGnssActive()
								? "mit GNSS - Lage/2D/3D " + Klein_Method_Format.format(this.parameter.getPointFloor(3) * 1000.0, "", 2) + " mm, 1D "
										+ Klein_Method_Format.format(this.parameter.getPointFloor(1) * 1000.0, "", 2) + " mm"
								: "ohne GNSS - " + Klein_Method_Format.format(this.parameter.getPointFloor(3) * 1000.0, "", 2) + " mm"));
			// Pruefung Projektion, Massstab, feste Anschlusspunkte VOR jeder Aenderung - bei Hinweisen wartet die
			// Automatik auf "Weiter" oder "Abbrechen" (Abbrechen = nichts rechnen)
			if (!Klein_Method.getInstance().confirmBeforeDynamicRun(currentStep)) {
				this.logger.log("Klein_Method: dynamische Ausgleichung vom Anwender vor dem Start abgebrochen (Hinweis Projektion/Anschlusspunkte) - nichts berechnet.");
				Klein_Method.getInstance().getProtocol().logEvent("LAUF NICHT GESTARTET: vom Anwender abgebrochen (Hinweis Projektion/Anschlusspunkte)");
				this.updateMessage(tr("Dynamische Ausgleichung abgebrochen - es wurde nichts berechnet."));
				return null;
			}
			// grobe Fehler vor Durchlauf 1 automatisch bereinigen (freie/fixe AGL), damit auch die Gruppenwerte aus sauberen
			// Daten geschaetzt werden
			if ((currentStep == Klein_Method_Adjustment_Step.FREE || currentStep == Klein_Method_Adjustment_Step.FIXED)
					&& this.parameter.isAutoGrossErrorCleanup() && !this.isCancelled()) {
				int removed = Klein_Method.getInstance().runAutomaticGrossErrorCleanup();
				if (removed >= 0) {
					this.logger.log("Klein_Method: Grobe Fehler vor Durchlauf 1 automatisch bereinigt: " + removed + " Beobachtung(en) abgeschaltet.");
					this.updateMessage(String.format(tr("Grobe Fehler vor Durchlauf 1 automatisch bereinigt: %d Beobachtung(en) abgeschaltet."), removed));
				} else if (removed == -2)
					this.updateMessage(tr("Grobe Fehler: automatische Prüfung nicht möglich (Ausgleichung ohne gültiges Ergebnis) - bitte von Hand bereinigen."));
			}
			this.parameter.resetProcessPhase(currentStep);
			Klein_Method.getInstance().clearDynamicPhase1Values();
			Klein_Method.getInstance().captureCalibrationStart(currentStep);
			// Verteilung erst NACH resetProcessPhase() protokollieren - erst dann ist ein evtl. altes
			// Waechter-Fenster beendet und der wirksame Wert endgueltig.
			this.logger.log("Klein_Method: Start - Verteilung: " + this.parameter.getDistributionType().getDisplayName()
					+ " (Auswahl: " + this.parameter.getUserDistributionType().getDisplayName() + ")");
			// dies ist der ECHTE Start eines neuen Automatik-Laufs - hier, und ausschliesslich hier, wird die
			// Kaskaden-Zustandsmaschine fuer den neuen Lauf auf "Pass 1" zurueckgesetzt (siehe Feldkommentare in
			// Klein_Method_Parameter.cascadePassCount/lastFloorEnforcementChangedCount). Der Konvergenz-Zaehler wird
			// explizit auf 0 gesetzt, damit ein Lauf OHNE aktivierte Untergrenzen-Erzwingung
			// (performFloorEnforcementCompression() wird dann nie aufgerufen) korrekt als "nichts zu korrigieren"
			// gilt und die Kaskade nach Pass 1 sauber (und nicht mit einem veralteten Stand aus einem fruehreren
			// Lauf) endet.
			this.parameter.resetCascadePassCount();
			this.parameter.setGroupFloorLockActive(false);
			this.parameter.resetBestMaxLogPost();
			this.parameter.setCascadeTargetLogPost(Double.NaN);
			this.parameter.setCascadeTargetReached(false);
			this.parameter.setLastFloorEnforcementChangedCount(0);
			// dieselbe Stelle, derselbe Grund wie die beiden Aufrufe direkt darueber - die Hard-Clamp-Ausschlussliste
			// gilt fuer eine GESAMTE Kaskaden-Sitzung und darf deshalb NUR hier, beim echten Start eines neuen
			// Automatik-Laufs, geleert werden - niemals bei einem einzelnen Pass-Wechsel (siehe Feldkommentar in
			// Klein_Method_Parameter.clampedObservationsRegistry). Punkt 2 des Auftrags ausdruecklich "unberuehrt"
			// gelassen: keine sonstige Aenderung an dieser Klasse, insbesondere MAX_CASCADE_PASSES und die
			// Kaskaden-Ablaufsteuerung unten bleiben exakt.
			this.parameter.resetClampedObservationsRegistry();
			// dieselbe Stelle, derselbe Grund wie die Aufrufe direkt darueber - die GNSS-Prozentual-Diagnosequote
			// gilt fuer eine GESAMTE Kaskaden-Sitzung ("Sitzungs-Init", wie beauftragt) und wird deshalb NUR hier,
			// beim echten Start eines neuen Automatik-Laufs, zurueckgesetzt (siehe Feldkommentar in
			// Klein_Method_Parameter.totalGnssCalculations/prozentualSplittedCalculations).
			this.parameter.resetGnssPercentageDiagnosticCounters();
			// Nachlauf: Ergebnis zuruecksetzen, eine alte Sicherung (abgelehnte Ruecknahme) verwerfen
			this.parameter.setFollowUpOutcome(this.parameter.getFollowUpMaxIterations() > 0 ? "nicht_noetig" : "aus");
			Klein_Method.getAdjustmentLock().lock();
			try {
				this.databaseHandler.deleteBestState();
				// statistischer Test vor der Einzelkalibrierung: Ausgangsgenauigkeiten merken
				Klein_Method.getInstance().statisticalTestCaptureOrigin();
			} finally {
				Klein_Method.getAdjustmentLock().unlock();
			}
		}

		int iteration = 0;
		boolean thresholdReached = false;

		while (!thresholdReached && !this.isCancelled() && iteration < MAX_ITERATIONS) {
			iteration++;

			try {
				// Absicherung: solange eine manuelle Netzausgleichung (F5 /
				// "Netzausgleichung starten") laeuft, wartet die Automatik hier
				// automatisch (lock()), bis das Lock wieder frei ist - danach
				// laeuft sie von selbst weiter, keine eigene Pause/Resume-Logik
				// noetig (siehe Klein_Method.getAdjustmentLock()).
				// adjustmentDetails/processedCounts werden IMMER befuellt (auch bei
				// Protokoll-Stufe SHORT/NONE) - Klein_Method_Protocol entscheidet selbst,
				// ob/wie sie verwendet werden. BEWUSST hier VOR dem try-Block deklariert,
				// da sie weiter unten (Protokoll-Aufruf) auch NACH dem finally noch
				// gebraucht werden - sonst waeren sie nach Ende des try-Blocks nicht
				// mehr gueltig (Compile-Fehler "cannot be resolved to a variable").
				List<String> adjustmentDetails = new ArrayList<String>();
				Map<String, Integer> processedCounts = new LinkedHashMap<String, Integer>();
				boolean phase1JustFinished = false;
				boolean dynamicPhase1JustFinished = false;

				Klein_Method.getAdjustmentLock().lock();
				try {
					// NEU: JEDE Iteration beginnt mit einer frischen Netzausgleichung
					// (synchron, im Hintergrund, ohne sichtbaren Dialog) - sonst
					// wuerden logPost/1:σ² unveraendert aus der LETZTEN Ausgleichung
					// gelesen, obwohl Phase 1/2 gerade erst neue σ-/Unsicherheitswerte
					// geschrieben haben. Ohne diesen Schritt wuerde die Automatik nicht
					// auf Basis aktueller Ergebnisse konvergieren.
					Klein_Method.getInstance().setValidAdjustmentResultsAvailable(false);
					EstimationStateType adjustmentResult;
					try {
						adjustmentResult = Klein_Method.getInstance().runHeadlessAdjustmentSynchronously();
					} catch (Exception e) {
						this.logger.log("Klein_Method: Fehler bei der Netzausgleichung in Durchlauf " + iteration + ": " + e.getMessage());
						// immer melden, auch wenn der Tab gerade nicht aktiv ist
						Klein_Method.getInstance().handleFailedAdjustmentDuringRun(null, e.getMessage());
						this.endReason = "Netzausgleichung mit Fehler abgebrochen (" + e.getMessage() + ")";
						thresholdReached = true;
						break;
					}

					if (adjustmentResult != EstimationStateType.ERROR_FREE_ESTIMATION) {
						this.logger.log("Klein_Method: Netzausgleichung in Durchlauf " + iteration + " kein fehlerfreies Ergebnis (" + adjustmentResult + ") - Automatik wird abgebrochen.");
						Klein_Method.getInstance().handleFailedAdjustmentDuringRun(adjustmentResult, null);
						this.endReason = "Netzausgleichung nicht fehlerfrei (" + adjustmentResult + ")";
						thresholdReached = true;
						break;
					}
					Klein_Method.getInstance().setValidAdjustmentResultsAvailable(true);

					// 1) Lesen
					this.databaseHandler.read(this.parameter);
					// bester Max-logPost dieses Laufs (reine Anzeige) - frisch nach jeder Ausgleichung, mit Durchlauf
					// (1-basiert) und sichtbarer Iteration. Durchlauf 1 zaehlt NICHT mit - erfasst wird erst ab
					// Durchlauf 2.
					if (this.parameter.getCascadePassCount() >= 1)
						this.parameter.recordBestMaxLogPost(this.functions.getMaxLogPostValue(this.parameter),
								this.parameter.getCascadePassCount() + 1, this.parameter.getIterationCount());

					// Durchlauf 1 unter dem Grenzwert: diesen (kalibrierten) Stand sichern. Erreichen die weiteren
					// Durchlaeufe und der Nachlauf den Grenzwert nicht, wird er am Laufende zurueckgeschrieben.
					if (!this.singleStepOnly && currentStep != Klein_Method_Adjustment_Step.DYNAMIC && this.parameter.isCascadeDoubleRunEnabled()
							&& this.parameter.getCascadePassCount() == 0 && this.parameter.getFollowUpMaxIterations() > 0
							&& this.functions.isLogPostBelowThreshold(this.parameter)) {
						double pass1Value = this.functions.getMaxLogPostValue(this.parameter);
						int pass1Iteration = this.protocolIteration();
						if (this.storeIfBetter(pass1Value, 1, pass1Iteration))
							this.logger.log("Klein_Method: Durchlauf 1 unter dem Grenzwert - Stand gesichert (Max-logPost "
									+ Klein_Method_Format.format(pass1Value, "", this.parameter.getDecimalPlaces()) + ", Iteration " + pass1Iteration
									+ "). Er wird uebernommen, falls die weiteren Durchlaeufe den Grenzwert nicht erreichen.");
					}

					// ab Durchlauf 3 endet die Automatik, sobald Max-logPost nach dieser Ausgleichung das
					// Kaskaden-Ziel erreicht (siehe Klein_Method_Default_Values. CASCADE_BACKUP_TARGET_LOG_POST).
					// Phase direkt auf DONE: recompute() tut dann nichts (keine Individual-Korrektur, KEINE
					// Untergrenzen-Erzwingung), der aktuelle Stand bleibt in der Datenbank stehen. zusaetzlich endet
					// die Berechnung ab Durchlauf 2, sobald Max-logPost UNTER der einstellbaren Abbruchgrenze liegt
					// (Standard 5.2). Das Abbruchziel (DL 3: bester Wert aus DL 2, ab DL 4: 5.2) bleibt als weitere
					// Bedingung bestehen.
					double cascadeTarget = this.parameter.getCascadeTargetLogPost();
					if (!this.singleStepOnly && currentStep != Klein_Method_Adjustment_Step.DYNAMIC
							&& this.parameter.isCascadeDoubleRunEnabled()
							&& this.parameter.getCascadePassCount() >= 1) {
						double currentMaxLogPostForTarget = this.functions.getMaxLogPostValue(this.parameter);
						double abortLimit = this.parameter.getCascadeAbortLimit();
						String reason = null;
						if (currentMaxLogPostForTarget < abortLimit)
							reason = "ABBRUCHGRENZE AB DL 2 ERREICHT: Max-logPost "
									+ Klein_Method_Format.format(currentMaxLogPostForTarget, "", this.parameter.getDecimalPlaces())
									+ " < " + Klein_Method_Format.format(abortLimit, "", this.parameter.getDecimalPlaces());
						else if (this.parameter.getCascadePassCount() >= 2 && !Double.isNaN(cascadeTarget)
								&& currentMaxLogPostForTarget <= cascadeTarget)
							reason = "KASKADEN-ABBRUCHZIEL ERREICHT: Max-logPost "
									+ Klein_Method_Format.format(currentMaxLogPostForTarget, "", this.parameter.getDecimalPlaces())
									+ " <= Ziel " + Klein_Method_Format.format(cascadeTarget, "", this.parameter.getDecimalPlaces());
						if (reason != null && !this.followUpStarted && this.parameter.getFollowUpMaxIterations() > 0
								&& !this.functions.isLogPostBelowThreshold(this.parameter)) {
							// Nachlauf auf den Grenzwert: Stand sichern und mit Phase 2 weiterrechnen
							this.parameter.setCascadeTargetReached(true); // keine weiteren Kaskaden-Durchlaeufe
							// Iteration wie in der Protokollzeile dieser Ausgleichung (Phase 2 zaehlt erst danach hoch);
							// eine bessere Sicherung (Durchlauf 1 unter dem Grenzwert) bleibt stehen
							boolean stored = this.storeIfBetter(currentMaxLogPostForTarget, this.parameter.getCascadePassCount() + 1,
									this.parameter.getIterationCount() + 1);
							this.followUpStarted = true;
							this.followUpPrevious = currentMaxLogPostForTarget;
							this.functions.setIndividualStepDamping(1.0);
							this.followUpJustStarted = true;
							this.followUpBest = currentMaxLogPostForTarget;
							this.followUpBestIteration = this.parameter.getIterationCount() + 1;
							this.parameter.setProcessPhase(Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION);
							this.parameter.resetInternalCycleCount(); // Grenzwert Iterationen gilt fuer den Nachlauf neu
							String startLine = reason + " (Durchlauf " + (this.parameter.getCascadePassCount() + 1) + ") - NACHLAUF AUF DEN GRENZWERT "
									+ Klein_Method_Format.format(this.functions.getMaxLogPostThreshold(this.parameter), "", 2)
									+ (stored ? ": Stand gesichert" : ": Sicherung aus Durchlauf 1 (Max-logPost "
											+ Klein_Method_Format.format(this.bestStateValue, "", this.parameter.getDecimalPlaces()) + ") bleibt")
									+ ", Phase 2 rechnet weiter (hoechstens " + (int) this.parameter.getFollowUpMaxIterations()
									+ " Iterationen, Ende nach " + (int) this.parameter.getFollowUpPatience() + " Iterationen ohne neuen Bestwert).";
							adjustmentDetails.add(startLine);
							this.logger.log("Klein_Method: " + startLine);
							Klein_Method.getInstance().getProtocol().logEvent(startLine);
						} else if (reason != null && !this.followUpStarted) {
							this.parameter.setCascadeTargetReached(true);
							this.parameter.setProcessPhase(Klein_Method_Process_Phase.DONE);
							String targetLine = reason + " (Durchlauf " + (this.parameter.getCascadePassCount() + 1)
									+ ") - Automatik endet mit diesem Stand (ohne Untergrenzen-Erzwingung).";
							adjustmentDetails.add(targetLine);
							this.logger.log("Klein_Method: " + targetLine);
						}
					}

					// Nachlauf: Bewertung nach jeder Ausgleichung (nicht in der Startiteration)
					if (this.followUpStarted && !this.followUpFinished && !this.followUpJustStarted) {
						double current = this.functions.getMaxLogPostValue(this.parameter);
						this.followUpIterations++;
						boolean withinTolerance = !this.functions.isLogPostBelowThreshold(this.parameter)
								&& this.functions.isLogPostWithinFollowUpTolerance(this.parameter);
						if (withinTolerance || this.functions.isLogPostBelowThreshold(this.parameter)) {
							this.followUpFinished = true;
							this.functions.setIndividualStepDamping(1.0);
							this.databaseHandler.deleteBestState();
							this.bestStateStored = false;
							this.parameter.setProcessPhase(Klein_Method_Process_Phase.DONE);
							this.parameter.setFollowUpOutcome("erreicht");
							String line = "NACHLAUF: Grenzwert erreicht nach " + this.followUpIterations + " Iteration(en), Max-logPost "
									+ Klein_Method_Format.format(current, "", this.parameter.getDecimalPlaces())
									+ (withinTolerance ? " (gerundet auf " + Klein_Method_Default_Values.FOLLOW_UP_TOLERANCE_DECIMALS + " Nachkommastellen = Grenzwert)" : "") + ".";
							adjustmentDetails.add(line);
							this.logger.log("Klein_Method: " + line);
							Klein_Method.getInstance().getProtocol().logEvent(line);
						} else {
							// Fortschritt am eigenen Bestwert des Nachlaufs messen; gesichert wird nur, was besser ist als die
							// vorhandene Sicherung (ein Durchlauf-1-Stand unter dem Grenzwert bleibt so stehen)
							if (current < this.followUpBest * (1.0 - Klein_Method_Default_Values.FOLLOW_UP_MIN_IMPROVEMENT)) {
								boolean stored = this.storeIfBetter(current, this.parameter.getCascadePassCount() + 1, this.parameter.getIterationCount() + 1);
								this.followUpBest = current;
								this.followUpBestIteration = this.parameter.getIterationCount() + 1;
								this.followUpNoImprovement = 0;
								this.logger.log("Klein_Method: Nachlauf - neuer Bestwert Max-logPost "
										+ Klein_Method_Format.format(current, "", this.parameter.getDecimalPlaces())
										+ (stored ? ", Stand gesichert." : " (Sicherung aus Durchlauf 1 ist besser und bleibt)."));
							} else
								this.followUpNoImprovement++;
							// Daempfung gegen Pendeln: wieder angestiegen -> naechsten Schritt verkleinern, sonst wieder vergroessern
							double damping = this.functions.getIndividualStepDamping();
							double newDamping = !Double.isNaN(this.followUpPrevious) && current > this.followUpPrevious
									? Math.max(Klein_Method_Default_Values.FOLLOW_UP_DAMPING_MIN, damping * Klein_Method_Default_Values.FOLLOW_UP_DAMPING_DOWN)
									: Math.min(1.0, damping * Klein_Method_Default_Values.FOLLOW_UP_DAMPING_UP);
							this.functions.setIndividualStepDamping(newDamping);
							this.followUpPrevious = current;
							if (Math.abs(newDamping - damping) > 1e-12)
								this.logger.log("Klein_Method: Nachlauf-Daempfung: Schrittweite der individuellen Kalibrierung "
										+ Math.round(damping * 100.0) + " % -> " + Math.round(newDamping * 100.0) + " %"
										+ (newDamping < damping ? " (Max-logPost wieder angestiegen)." : "."));
							this.logger.log("Klein_Method: Nachlauf-Iteration " + this.followUpIterations + ": Max-logPost "
									+ Klein_Method_Format.format(current, "", this.parameter.getDecimalPlaces()) + ", Bestwert "
									+ Klein_Method_Format.format(this.followUpBest, "", this.parameter.getDecimalPlaces()) + " (Iteration "
									+ this.followUpBestIteration + "), ohne Verbesserung seit " + this.followUpNoImprovement + ".");
							String why = null;
							if (this.followUpNoImprovement >= (int) this.parameter.getFollowUpPatience())
								why = this.followUpNoImprovement + " Iterationen ohne neuen Bestwert, zuletzt Max-logPost "
										+ Klein_Method_Format.format(current, "", this.parameter.getDecimalPlaces());
							else if (this.followUpIterations >= (int) this.parameter.getFollowUpMaxIterations())
								why = "Hoechstzahl " + this.followUpIterations + " Nachlauf-Iterationen erreicht, zuletzt Max-logPost "
										+ Klein_Method_Format.format(current, "", this.parameter.getDecimalPlaces());
							if (why != null) {
								this.functions.setIndividualStepDamping(1.0);
								this.restoreBestStateAndAdjust(why, adjustmentDetails);
								thresholdReached = true;
								break;
							}
						}
					}
					this.followUpJustStarted = false;

					// NEUES
					// Der naechste Pass wiederholt denselben Ablauf (phase1CalibratedThisPass wird bei JEDEM
					// Pass-Start neu zurueckgesetzt, siehe Klein_Method_Parameter).
					// BEWUSST AUF FREE/FIXED BESCHRAENKT: bei DYNAMIC (Festpunktanalyse,
					// POINT_GROUP_CALIBRATION/POINT_INDIVIDUAL_CALIBRATION) ist die Beziehung zwischen den beiden
					// Phasen ein bewusst zirkulaeres Wechselspiel (settled-Flags, siehe
					// Klein_Method_Functions.recompute()) - ein hartes Sperren von POINT_GROUP_CALIBRATION wuerde
					// dieses Konvergenzkriterium strukturell zerstoeren. Zusaetzliche Absicherung
					// "!this.singleStepOnly": resetPhase1 CalibratedThisPass()/resetProcessPhase() werden im
					// Einzelschritt-Modus NIE aufgerufen (siehe init-Block oben), currentStep bliebe dort zudem
					// dauerhaft null - ohne diese Absicherung koennte ein stehen gebliebener Zustand aus einem
					// fruehreren Automatik-Lauf einen einzelnen manuellen Schritt (Bewertungsmethode EP) faelschlich
					// beeinflussen.
					Klein_Method_Process_Phase phaseBeforeThisStep = this.parameter.getProcessPhase();
					boolean isGroupPhaseThisStep = !this.singleStepOnly
							&& currentStep != Klein_Method_Adjustment_Step.DYNAMIC
							&& phaseBeforeThisStep == Klein_Method_Process_Phase.GROUP_CALIBRATION;
					// der "Ein-Versuch"-Initialisierer greift nur noch, wenn die Gruppen-Daempfung in Phase 2
					// eingeschaltet ist (dann regelt Phase 2 weiter). Bei "aus" (Standard) laeuft Phase 1 so lange,
					// bis alle Gruppen im VKS-Korridor oder an der Untergrenze sind (recompute() wechselt dann selbst
					// zu INDIVIDUAL_CALIBRATION). Abgesichert durch den internen Zyklenzaehler (Grenzwert
					// Iterationen), der auch in Phase 1 mitlaeuft.
					if (isGroupPhaseThisStep && this.parameter.isGroupDampingInPhase2Enabled()
							&& this.parameter.hasPhase1CalibratedThisPass()) {
						// die einmalige Eichung dieses Passes ist bereits erfolgt (siehe
						// markPhase1CalibratedThisPass()-Aufruf weiter unten) - Phase 1 fuer den
						// Rest des Passes ueberspringen, direkt in Phase 2.
						this.parameter.setProcessPhase(Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION);
						isGroupPhaseThisStep = false;
						this.logger.log("Klein_Method: V0.331 Phase-1-Initialisierer aktiv - einmalige "
								+ "Eichung dieses Passes bereits erfolgt, Phase 1 fuer den Rest des Passes "
								+ "gesperrt, Phase 2 uebernimmt.");
					}

					// Ab laufen ALLE Typen (inkl. GNSS) in jedem Pass ueber dieselbe Ur-Formel mit Zielkorridor aus
					// der UI (Phase 1 einmalig in Iteration 0, danach in jeder Iteration der Phase 2 ueber
					// performGroupDampingStep()).
					if (isGroupPhaseThisStep && this.parameter.getCascadePassCount() > 0) {
						this.logger.log("Klein_Method: V0.342 Gruppen-Daempfung - Pass "
								+ (this.parameter.getCascadePassCount() + 1) + ", Iteration 0: Phase-1-Eichung aller "
								+ "Gruppen-VKS (inkl. GNSS sigma_a/b/c) ueber sigma_neu = sigma_alt + sigma_alt*log10(F), "
								+ "Korridor " + Klein_Method_Format.format(this.parameter.getVksBandMin(), "", 3)
								+ "-" + Klein_Method_Format.format(this.parameter.getVksBandMax(), "", 3) + ".");
					}

					// 2) Bewerten + neu berechnen - orchestriert intern Phase 1
					// (Gruppenkalibrierung) bzw. Phase 2 (individuelle Kalibrierung)
					// gemaess this.parameter.getProcessPhase() (siehe Klein_Method_Functions.recompute()).
					// Rueckgabe false = PHASE 2 hat die Obergrenze einer individuellen
					// Messunsicherheit erreicht/ueberschritten (Obergrenzen-Regel) ->
					// sofortiger Abbruch, this.parameter.isIndividualUncertaintyCeilingExceeded()
					// bleibt bis zur manuellen Bereinigung gesetzt (sperrt Automatik UND
					// Einzelschritt, siehe Klein_Method_Tab_Builder).
					// outAdjustmentDetails/outProcessedCounts werden IMMER befuellt (auch bei
					// Protokoll-Stufe SHORT/NONE) - Klein_Method_Protocol entscheidet selbst,
					// ob/wie sie verwendet werden.
					Klein_Method_Process_Phase phaseBeforeRecompute = this.parameter.getProcessPhase();
					boolean recomputeOk = this.functions.recompute(this.parameter, this.databaseHandler, adjustmentDetails, processedCounts);
					// Gruppen kalibriert, Einzelkalibrierung beginnt: einmal je Lauf mit den Ausgangsgenauigkeiten testen
					if (!this.singleStepOnly && phaseBeforeRecompute == Klein_Method_Process_Phase.GROUP_CALIBRATION
							&& this.parameter.getProcessPhase() == Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION)
						Klein_Method.getInstance().statisticalSnapshotAfterGroupCalibration(adjustmentDetails);
					// Hinweise der Kalibrierung (robuste VKS) sofort anzeigen, ohne den Lauf anzuhalten
					for (String warning = this.parameter.getCalibrationWarnings().poll(); warning != null; warning = this.parameter.getCalibrationWarnings().poll()) {
						final String warningText = warning;
						this.logger.log("Klein_Method: Hinweis Kalibrierung angezeigt (robuste VKS).");
						Platform.runLater(() -> {
							Alert alert = new Alert(AlertType.WARNING);
							alert.setTitle(tr("Klein_Method - Hinweis zur Kalibrierung"));
							alert.setHeaderText(tr("Ausrei\u00DFer vermutet"));
							alert.setContentText(warningText + tr("\n\nDie Automatik l\u00E4uft weiter. Details im Kalibrierungsnachweis am Laufende."));
							alert.show();
						});
					}
					// Phase 1 (Gruppenkalibrierung) ist gerade fertig geworden -> Zuordnung der groben Fehler
					phase1JustFinished = phaseBeforeThisStep == Klein_Method_Process_Phase.GROUP_CALIBRATION
							&& this.parameter.getProcessPhase() == Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION;
					// Ende von Phase 1 der dynamischen AGL (Punktgruppen)
					dynamicPhase1JustFinished = phaseBeforeThisStep == Klein_Method_Process_Phase.POINT_GROUP_CALIBRATION
							&& this.parameter.getProcessPhase() == Klein_Method_Process_Phase.POINT_INDIVIDUAL_CALIBRATION;
					if (isGroupPhaseThisStep)
						this.parameter.markPhase1CalibratedThisPass();
					// nutzt die BEREITS BESTEHENDE Zaehlschleife aus performIndividualCalibrationStep() wieder -
					// processedCounts (siehe Feldkommentar direkt oberhalb dieses recompute()-Aufrufs) wird dort JE
					// Beobachtungstyp befuellt (inkl. GNSS-Achsen einzeln), IMMER, auch bei Protokoll-Stufe
					// SHORT/NONE.
					int elementsProcessedThisIteration = 0;
					for (int count : processedCounts.values())
						elementsProcessedThisIteration += count;
					this.parameter.setElementsProcessedLastIteration(elementsProcessedThisIteration);

					// erweiterte Live-Protokollierung, feuert WIE BEAUFTRAGT bei JEDER Iteration lueckenlos -
					// UNBEDINGT, unabhaengig von recomputeOk oder der aktuellen Protokoll-Stufe (Klein_Method_Logger
					// ist die immer-aktive Debug-Datei; adjustmentDetails zusaetzlich fuer Klein_Method_Protocol,
					// dort stufengesteuert wie alle anderen Zeilen). Alle 6 vom Auftrag genannten Live-Werte in einer
					// Zeile: GNSS-Gruppen-VKS-Stand (reiner Lesezugriff, siehe
					// Klein_Method_Functions.formatCurrentGnssGroupUncertaintyStatus()), Toggle "Deaktivieren BL:",
					// Toggle "Kaskaden-Mehrfachlauf:", Echtzeit-Zaehler "Elemente / Iter.:", das dynamisch
					// mitschwimmende Zwischenziel "logPost-EtappeMin:" und die aktiv gewaehlte "Daempfungs-Formel:".
					String gnssGroupUncertaintyStatus = this.functions.formatCurrentGnssGroupUncertaintyStatus(this.parameter, this.databaseHandler);
					String megaProtocolLine = "Klein_Method: " + Klein_Method_Default_Values.PACKAGE_VERSION + " Mega-Protokoll - Iteration " + iteration + " - "
							+ gnssGroupUncertaintyStatus
							+ " | Deaktivieren BL: " + (this.parameter.isDeactivateGnssOutlierEnabled() ? "ja" : "nein")
							+ " | Kaskaden-Mehrfachlauf: " + (this.parameter.isCascadeDoubleRunEnabled() ? "ja" : "nein")
							+ " | Elemente / Iter.: " + elementsProcessedThisIteration
							+ " | logPost-EtappeMin: " + Klein_Method_Format.format(
									this.functions.computeCurrentLogPostEtappeMin(this.parameter), "", this.parameter.getDecimalPlaces())
							+ " | Dämpfungs-Formel: " + this.parameter.getDampingFormulaType().getDisplayName()
							+ " | Split-Normierung: " + this.parameter.getSplitNormalizationMode().getDisplayName()
									+ " -> " + (this.parameter.isSplitNormalizationEffective() ? "ja" : "nein")
							// aktive Gruppen-Daempfung (Ur-Formel, Korridor) zusaetzlich ausgewiesen Korridor = reell
							// ausgelesene UI-Werte "VKS-Band Min/Max (s²)"
							+ " | Gruppen-Dämpfung: Ur-Formel, Korridor "
							+ Klein_Method_Format.format(this.parameter.getVksBandMin(), "", 3) + "-"
							+ Klein_Method_Format.format(this.parameter.getVksBandMax(), "", 3)
							+ " | Verteilung: " + this.parameter.getDistributionType().getDisplayName()
							+ (this.parameter.isBackoffActive() ? " (Oszillationswächter aktiv, Auswahl: "
									+ this.parameter.getUserDistributionType().getDisplayName() + ")" : "")
							+ " | Trichter-Untergrenze: " + Klein_Method_Format.format(this.parameter.getFunnelFloorLogPost(), "", 2)
							+ " | Kaskaden-Durchlauf: " + (this.parameter.getCascadePassCount() + 1)
							+ " | Bester Max-logPost: " + (Double.isNaN(this.parameter.getBestMaxLogPost()) ? "k.A."
									: Klein_Method_Format.format(this.parameter.getBestMaxLogPost(), "", this.parameter.getDecimalPlaces())
									+ " (DL " + this.parameter.getBestMaxLogPostPass() + ", Iteration " + this.parameter.getBestMaxLogPostIteration() + ")")
							+ " | Abbruchgrenze ab DL 2: " + Klein_Method_Format.format(this.parameter.getCascadeAbortLimit(), "", 2)
							+ " | Kaskaden-Abbruchziel: " + (Double.isNaN(this.parameter.getCascadeTargetLogPost()) ? "k.A."
									: Klein_Method_Format.format(this.parameter.getCascadeTargetLogPost(), "", this.parameter.getDecimalPlaces()))
							+ " | Gruppen-Dämpfung Phase 2: " + (this.parameter.isGroupDampingInPhase2Enabled() ? "an" : "aus")
							+ " | Gruppen-σ Untergrenze nach DL1: " + (this.parameter.isGroupFloorAfterPass1Enabled()
									? (this.parameter.isGroupFloorLockActive() ? "ja (aktiv)" : "ja") : "nein")
							+ " | Phase: " + this.parameter.getProcessPhase()
							// Kennwerte der dynamischen AGL
							+ (currentStep == Klein_Method_Adjustment_Step.DYNAMIC
									? " | Dyn. Band: " + Klein_Method_Format.format(Klein_Method_Default_Values.DYNAMIC_BAND_MIN, "", 3) + "-"
											+ Klein_Method_Format.format(Klein_Method_Default_Values.DYNAMIC_BAND_MAX, "", 3)
											+ " | Sprungwerte (fest) 1D/2D-3D: " + Klein_Method_Format.format(this.parameter.getPointJumpValue1DMillimeter(), "mm", 2)
											+ " / " + Klein_Method_Format.format(this.parameter.getPointJumpValue23DMillimeter(), "mm", 2)
											+ " | Turbo Phase 2: " + (this.parameter.isTurboEnabled() ? "ja" : "nein")
									: "");
					this.logger.log(megaProtocolLine);
					adjustmentDetails.add(megaProtocolLine);

					if (!recomputeOk) {
						// Stagnations-Abbruch der dynamischen AGL (kein Fortschritt Richtung Band)
						if (this.parameter.getDynamicStagnationDetails() != null) {
							String details = this.parameter.getDynamicStagnationDetails();
							String stagnationLine = "STAGNATION: " + details + " - Lauf beendet, in dieser Iteration wurde nichts geschrieben.";
							adjustmentDetails.add(stagnationLine);
							this.logger.log("Klein_Method: " + stagnationLine);
							Platform.runLater(() -> {
								Alert alert = new Alert(AlertType.INFORMATION);
								alert.setTitle(tr("Klein_Method - dynamische AGL beendet"));
								alert.setHeaderText(tr("Kein Fortschritt mehr Richtung Band 0,996 - 1,004"));
								alert.setContentText(details + tr("\n\nBitte die offenen Festpunkte in JAG3D pr\u00FCfen."));
								alert.showAndWait();
							});
							this.endReason = "Stagnation der dynamischen AGL";
							break;
						}
						if (this.parameter.getPointUniformityMismatchDetails() != null) {
							// FESTPUNKTANALYSE-SICHERHEITSCHECK: Gruppenkalibrierung hat KEINE einheitlichen
							// y0/x0/z0-Werte erzeugt - Abbruch, manuelle Pruefung noetig, bevor Phase 2 sinnvoll
							// starten kann.
							String details = this.parameter.getPointUniformityMismatchDetails();
							this.logger.log("Klein_Method: Festpunktanalyse abgebrochen - Komponenten (y0/x0/z0) nicht einheitlich: " + details);

							Platform.runLater(() -> {
								Alert alert = new Alert(AlertType.WARNING);
								alert.setTitle(tr("Klein_Method - Automatik abgebrochen"));
								alert.setHeaderText(tr("Festpunkt-Komponenten (y0/x0/z0) nicht einheitlich"));
								alert.setContentText(tr("Vor dem \u00DCbergang zur individuellen Kalibrierung wurde festgestellt, dass "
										+ "die Gruppenunsicherheit eines oder mehrerer Festpunkte NICHT in allen Komponenten "
										+ "gleich ist:\n\n") + details
										+ tr("\n\nBitte pr\u00FCfen Sie die betroffenen Punkte manuell in JAG3D."));
								alert.showAndWait();
							});
							this.endReason = "Festpunkt-Komponenten nicht einheitlich";
							break;
						}

						if ((this.followUpStarted || this.bestStateStored) && !this.followUpFinished) {
							// Nachlauf bzw. gesicherter Stand: Obergrenze erreicht -> bester Stand statt Sperre
							String type = this.parameter.getIndividualUncertaintyCeilingExceededType() != null
									? this.parameter.getIndividualUncertaintyCeilingExceededType().getDisplayName() : "unbekannt";
							this.restoreBestStateAndAdjust("Obergrenze der individuellen Messunsicherheit erreicht (" + type + ")", adjustmentDetails);
							thresholdReached = true;
							break;
						}
						String affectedType = this.parameter.getIndividualUncertaintyCeilingExceededType() != null
								? this.parameter.getIndividualUncertaintyCeilingExceededType().getDisplayName()
								: "unbekannt";

						this.logger.log("Klein_Method: Obergrenze der individuellen Messunsicherheit (" + affectedType
								+ ") erreicht/ueberschritten - Automatik abgebrochen. Manuelle Bereinigung erforderlich, "
								+ "bevor Klein_Method wieder aktiviert werden kann.");

						Platform.runLater(() -> {
							Alert alert = new Alert(AlertType.WARNING);
							alert.setTitle(tr("Klein_Method - Automatik abgebrochen"));
							alert.setHeaderText(tr("Obergrenze der individuellen Messunsicherheit erreicht"));
							alert.setContentText(tr("Fuer den Beobachtungstyp \"") + affectedType + tr("\" wurde die Obergrenze der "
									+ "individuellen Messunsicherheit erreicht bzw. ueberschritten.\n\n"
									+ "Die Automatik wurde deshalb angehalten. Bitte pruefen Sie die betroffenen Daten und "
									+ "heben Sie die Sperre danach ueber den Button \"Sperre aufheben\" im Klein_Method-Tab wieder auf."));
							alert.showAndWait();
						});
						this.endReason = "Obergrenze der individuellen Messunsicherheit erreicht (" + affectedType + ")";
						break;
					}

					// 3) Schreiben
					this.databaseHandler.write(this.parameter);
					// Anzeige "σa/σb(√d)/σc(d) aktuell" mit dem soeben geschriebenen Stand (nicht erst nach der
					// naechsten Ausgleichung)
					this.databaseHandler.refreshGroupUncertaintyRanges(this.parameter);
				}
				finally {
					Klein_Method.getAdjustmentLock().unlock();
				}
				// JAG3D-Anzeige wie nach F5 aus der Datenbank nachladen (hoechstens alle 10 s, ausserhalb des Locks)
				Klein_Method.getInstance().refreshJag3dView(false);

				// Schwarmwissen - Gruppenwert der Punkte nach Phase 1 der dynamischen AGL merken
				if (dynamicPhase1JustFinished && !this.singleStepOnly)
					Klein_Method.getInstance().recordDynamicPhase1Values();
				// Schwarmwissen - Warnung nach Phase 1, Automatik haelt an ("Weiter"/"Abbrechen")
				if (phase1JustFinished && !this.singleStepOnly && currentStep != Klein_Method_Adjustment_Step.DYNAMIC
						&& !this.grossErrorAssignmentDone && !Klein_Method.getInstance().checkSwarmPlausibilityAfterPhase1()) {
					this.endReason = "nach Warnung Schwarmwissen (Gruppenunsicherheit weit \u00FCber der Erfahrung) abgebrochen";
					thresholdReached = true;
					break;
				}
				if (phase1JustFinished && !this.singleStepOnly && currentStep != Klein_Method_Adjustment_Step.DYNAMIC
						&& !this.grossErrorAssignmentDone) {
					this.grossErrorAssignmentDone = true;
					this.handleGrossErrorAssignment(adjustmentDetails);
				}

				// 4) getrennte Zaehlung. Der interne Sicherheits-zaehler laeuft IMMER mit (jede aeussere
				// Schleifenrunde, unabhaengig von der Phase) und ist allein fuer die "Grenzwert
				// Iterationen"-Notbremse zustaendig. Die "Grenzwert Iterationen"-Pruefung selbst bleibt bewusst an
				// den INTERNEN (immer laufenden) Zaehler gekoppelt, damit eine laengere Gruppenkalibrierungs-Phase
				// weiterhin sicher abgebrochen werden kann, auch wenn der sichtbare Zaehler waehrenddessen nicht
				// mitlaeuft.
				int internalCycleCount = this.parameter.incrementInternalCycleCount();
				Klein_Method_Process_Phase currentPhaseForCounting = this.parameter.getProcessPhase();
				// Freie/fixe AGL (GROUP_CALIBRATION) unveraendert.
				boolean isGroupPhase = currentPhaseForCounting == Klein_Method_Process_Phase.GROUP_CALIBRATION;
				int currentIterationCount = isGroupPhase ? this.parameter.getIterationCount() : this.parameter.incrementIterationCount();
				boolean iterationLimitExceeded = internalCycleCount > this.parameter.getIterationThreshold();
				if (iterationLimitExceeded)
					this.logger.log("Klein_Method: Grenzwert Iterationen (" + this.parameter.getIterationThreshold()
							+ ") ueberschritten bei internem Zyklus " + internalCycleCount + " (sichtbare Iteration "
							+ currentIterationCount + ") - Automatik abgebrochen.");

				// 5) fachlichen Grenzwert pruefen (im Einzelschritt-Modus irrelevant,
				//    dort wird so oder so nach einem Durchlauf gestoppt)
				boolean naturalThresholdReached = this.singleStepOnly || iterationLimitExceeded || this.functions.isThresholdReached(this.parameter);

				// loest s starren EIN-Neustart-Mechanismus ab. MAX_CASCADE_PASSES wirkt als zusaetzliches, von der
				// eigentlichen Konvergenz-Pruefung UNABHAENGIGES Sicherheitsnetz (siehe Konstanten-Kommentar oben).
				// neue Kaskaden-Logik.
				// Erreicht ein Durchlauf DONE (Max-logPost unter dem Grenzwert) - oder ab Durchlauf 3 das
				// Kaskaden-Abbruchziel -, ENDET die Automatik.
				// Ein weiterer Durchlauf startet NUR, wenn der aktuelle Durchlauf am "Grenzwert Iterationen" endet,
				// OHNE DONE erreicht zu haben. Beim Uebergang ohne DONE laeuft KEINE Untergrenzen-Erzwingung (die ist
				// an DONE gebunden), der erreichte Stand ist Ausgangspunkt des naechsten Durchlaufs. DONE ab
				// Durchlauf 2 beendet die Berechnung. Unveraendert: Ende am Grenzwert Iterationen OHNE DONE ->
				// naechster Durchlauf.
				boolean doneReached = this.functions.isThresholdReached(this.parameter);
				boolean passEndedAtIterationLimitWithoutDone = iterationLimitExceeded && !doneReached;
				boolean firstPassDoneWithReset = doneReached && this.parameter.getCascadePassCount() == 0
						&& (this.parameter.isFloorEnforcementEnabled() || this.parameter.isGroupFloorAfterPass1Enabled());
				// KEIN Kaskaden-Mehrfachlauf in der dynamischen AGL - nach DONE startete sonst Durchlauf 2 mit Phase
				// 1, die bei bereits geschriebenen Einzelwerten wirkungslos ist und das Gruppen-sigma bis zur
				// Obergrenze 999,99 mm hochtrieb.
				if (!this.singleStepOnly && currentStep != Klein_Method_Adjustment_Step.DYNAMIC
						&& (passEndedAtIterationLimitWithoutDone || firstPassDoneWithReset)
						&& this.parameter.isCascadeDoubleRunEnabled()
						&& !this.parameter.isCascadeTargetReached()) {
					int passCount = this.parameter.incrementCascadePassCount();
					if (passCount <= MAX_CASCADE_PASSES) {
						this.databaseHandler.commitAndCheckpoint();
						this.parameter.resetIterationCount();
						this.parameter.resetProcessPhase(currentStep);
						this.parameter.resetPhase1CalibratedThisPass();

						// beim Uebergang von Durchlauf 1 zu 2 sigma_a/b/c aller Gruppen auf die Untergrenze setzen
						// (unter dem Ausgleichungs-Lock, da hier geschrieben wird). Ab dann - in jedem weiteren
						// Durchlauf - Phase 1 ueberspringen, sonst wuerde sie den Wert sofort wieder hochregeln. Nur
						// FREE/FIXED; bei DYNAMIC (Festpunktanalyse) gibt es keine Beobachtungs-Gruppenphase.
						if (this.parameter.isGroupFloorAfterPass1Enabled() && currentStep != Klein_Method_Adjustment_Step.DYNAMIC) {
							if (passCount == 1 && !this.parameter.isGroupFloorLockActive()) {
								Klein_Method.getAdjustmentLock().lock();
								try {
									Klein_Method.getInstance().recordPass1GroupValues();
									int writtenGroups = this.functions.applyGroupUncertaintyFloors(this.parameter, this.databaseHandler, adjustmentDetails);
									this.parameter.setGroupFloorLockActive(true);
									this.logger.log("Klein_Method: V0.347 Gruppenunsicherheit nach Durchlauf 1 auf Untergrenze gesetzt ("
											+ writtenGroups + " Gruppe(n)), Phase 1 fuer alle weiteren Durchlaeufe gesperrt.");
								} finally {
									Klein_Method.getAdjustmentLock().unlock();
								}
							}
							if (this.parameter.isGroupFloorLockActive())
								this.parameter.setProcessPhase(Klein_Method_Process_Phase.INDIVIDUAL_CALIBRATION);
						}

						// Kaskaden-Abbruchziel fuer den NEUEN Durchlauf festlegen. passCount == 2 -> Durchlauf 3
						// beginnt: Ziel = bester Max-logPost aus DL 1-2, nur wenn dieser unter 5.2 liegt. passCount
						// >= 3 -> Durchlauf 4 und folgende: Ziel = 5.2 (Backup).
						if (currentStep != Klein_Method_Adjustment_Step.DYNAMIC) {
							double backupTarget = Klein_Method_Default_Values.CASCADE_BACKUP_TARGET_LOG_POST;
							if (passCount == 2) {
								double best = this.parameter.getBestMaxLogPost();
								double target = (!Double.isNaN(best) && best < backupTarget) ? best : Double.NaN;
								this.parameter.setCascadeTargetLogPost(target);
								this.logger.log("Klein_Method: V0.350 Kaskaden-Abbruchziel fuer Durchlauf 3: "
										+ (Double.isNaN(target) ? "keins (bester Max-logPost nicht unter " + backupTarget + ")"
												: Klein_Method_Format.format(target, "", this.parameter.getDecimalPlaces())));
							} else if (passCount >= 3) {
								this.parameter.setCascadeTargetLogPost(backupTarget);
								this.logger.log("Klein_Method: V0.350 Kaskaden-Abbruchziel fuer Durchlauf " + (passCount + 1) + ": " + backupTarget + " (Backup).");
							}
						}

						String passChangeLine = "KASKADEN-MEHRFACHLAUF: Durchlauf " + passCount
								+ (firstPassDoneWithReset ? " mit DONE abgeschlossen"
										: " hat den Grenzwert Iterationen (" + this.parameter.getIterationThreshold() + ") ohne DONE erreicht")
								+ " (bester Max-logPost ab DL 2 "
								+ (Double.isNaN(this.parameter.getBestMaxLogPost()) ? "k.A."
										: Klein_Method_Format.format(this.parameter.getBestMaxLogPost(), "", this.parameter.getDecimalPlaces()))
								+ ") - Durchlauf " + (passCount + 1) + " wird gestartet"
								+ (Double.isNaN(this.parameter.getCascadeTargetLogPost()) ? "."
										: ", Abbruchziel " + Klein_Method_Format.format(this.parameter.getCascadeTargetLogPost(), "", this.parameter.getDecimalPlaces()) + ".");
						adjustmentDetails.add(passChangeLine);
						this.logger.log("Klein_Method: " + passChangeLine);
						thresholdReached = false;
					}
					else {
						this.logger.log("Klein_Method: Kaskaden-Mehrfachlauf - Sicherheitslimit (" + MAX_CASCADE_PASSES
								+ " Durchlaeufe) erreicht, ohne DONE oder Kaskaden-Abbruchziel - Automatik wird beendet. "
								+ "Bitte Netz/Grenzwerte manuell pruefen.");
						thresholdReached = naturalThresholdReached;
					}
				}
				else {
					thresholdReached = naturalThresholdReached;
				}

				double currentMaxLogPost = this.functions.getMaxLogPostValue(this.parameter);

				// 6) Fortschrittsbalken aktualisieren - vollstaendig entkoppelt von jeglicher logPost-Abhaengigkeit.
				// Reiner, wiederkehrender Puls ueber den sichtbaren Iterationszaehler (Modulo PROGRESS_PULSE_WINDOW)
				// - zeigt ausschliesslich "die Automatik laeuft" an, ohne jede fachliche/statistische Aussage.
				double progressFraction = (currentIterationCount % PROGRESS_PULSE_WINDOW) / (double) PROGRESS_PULSE_WINDOW;
				this.updateProgress(progressFraction, 1.0);

				this.updateMessage("Durchlauf " + iteration + " (Iteration " + currentIterationCount + "/"
						+ this.parameter.getIterationThreshold() + ")" + (thresholdReached ? " - beendet" : " - laeuft"));

				// 7) Protokoll: EINE Zeile pro abgeschlossener Iteration, keine
				// Zwischenschritte (siehe Klein_Method_Protocol). Tut nichts,
				// wenn das Protokoll aus ist.
				double currentMaxEp = this.functions.getMaxEpValue(this.parameter);

				String triggerLabel = this.singleStepOnly ? "Manuell" : "Automatik";
				String evaluationMethodLabel = this.parameter.getEvaluationMethod().getDisplayName();

				// Ausgleichungsart fuer die Protokollzeile - SQLException wird vom umgebenden catch-Block (siehe
				// unten) wie jeder andere Datenbankfehler dieser Iteration behandelt.
				String adjustmentStepLabel = Klein_Method.getInstance().getCurrentAdjustmentStep().getShortDisplayName();

				Klein_Method_Guardian guardian = Klein_Method.getInstance().getGuardian();
				String guardianStatusLabel = !guardian.isEnabled() ? "AUS"
						: !guardian.hasReferenceMeasurement() ? "Referenz folgt"
						: guardian.getLastStatus().getDisplayName();

				// verwendete Grenzwerte: die 4 Schwellen der AKTUELL aktiven
				// Bewertungsmethode (logPost oder EP), nicht alle 8 auf einmal
				StringBuilder thresholdsText = new StringBuilder();
				for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(
						this.parameter.getEvaluationMethod() == Klein_Method_Evaluation_Method.LOG_POST
								? Klein_Method_Threshold_Type.Category.LOG_POST
								: Klein_Method_Threshold_Type.Category.EP)) {
					if (thresholdsText.length() > 0)
						thresholdsText.append(" | ");
					thresholdsText.append(Klein_Method_Format.format(this.parameter.getThresholdValue(type), type.getUnit(), this.parameter.getDecimalPlaces()));
				}

				// Nullpunktabweichung/Massstab (Zusatzparameter Horizontalstrecke/ Schraegstrecke) - laut Vorgabe
				// IMMER mit ins Protokoll, unabhaengig von Stufe/Bewertungsmethode, ans Zeilenende angehaengt.
				StringBuilder additionalParametersText = new StringBuilder();
				for (Klein_Method_Threshold_Type distanceType : new Klein_Method_Threshold_Type[] {
						Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST, Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST }) {
					double[] raw = this.databaseHandler.readAdditionalParameters(distanceType);
					boolean hasOffset = !Double.isNaN(raw[0]);
					boolean hasScale = !Double.isNaN(raw[1]);
					if (!hasOffset && !hasScale)
						continue; // Typ im aktiven Projekt nicht aktiv/berechnet - ganz weglassen

					String typeName = distanceType.getDisplayName().replace(" logPost", "");
					List<String> parts = new ArrayList<String>();
					if (hasOffset) {
						double offsetMm = LengthUnit.MILLIMETER.fromMeter(raw[0]);
						parts.add("Nullpunktabweichung a: " + Klein_Method_Format.format(offsetMm, "mm", this.parameter.getDecimalPlaces()));
					}
					if (hasScale) {
						double scalePpm = ScaleUnit.PARTS_PER_MILLION_WRT_ONE.fromUnitless(raw[1]);
						parts.add("Ma\u00DFstab m: " + Klein_Method_Format.format(scalePpm, "ppm", this.parameter.getDecimalPlaces()));
					}

					if (additionalParametersText.length() > 0)
						additionalParametersText.append(" | ");
					additionalParametersText.append(typeName).append(" ").append(String.join(", ", parts));
				}

				Klein_Method.getInstance().getProtocol().logStep(
						currentIterationCount, adjustmentStepLabel, this.parameter.getProcessPhase().name(), currentMaxLogPost, currentMaxEp,
						triggerLabel, evaluationMethodLabel, guardianStatusLabel, thresholdsText.toString(),
						adjustmentDetails, processedCounts, additionalParametersText.toString());

				// Live-Modus: dieselben Werte wie im Protokoll, live in der Oberflaeche
				Platform.runLater(() -> Klein_Method.getInstance().refreshLiveModeFields(currentMaxLogPost, currentMaxEp));

			} catch (SQLException e) {
				this.logger.log("Klein_Method: Datenbankfehler in Durchlauf " + iteration + ": " + e.getMessage());
				Klein_Method.getInstance().getProtocol().logEvent("LAUF BEENDET: Datenbankfehler in Durchlauf " + iteration + " - " + e.getMessage());
				throw e; // Task bricht kontrolliert ab, Fehler wird an UI weitergereicht
			}

			if (this.singleStepOnly)
				break;

			if (!thresholdReached && !this.isCancelled()) {
				Thread.sleep(DELAY_BETWEEN_ITERATIONS_MS);
			}
		}

		// Nachlauf nicht regulaer beendet (Stopp, Grenzwert Iterationen, Fehler): besten Stand zurueckschreiben
		if (this.bestStateStored && !this.followUpFinished && !this.isCancelled() && this.functions.isThresholdReached(this.parameter)
				&& this.functions.isLogPostBelowThreshold(this.parameter)) {
			// regulaer unter dem Grenzwert fertig - Sicherung nicht mehr noetig
			try {
				this.databaseHandler.deleteBestState();
				this.databaseHandler.commit();
			} catch (SQLException e) {
				this.logger.log("Klein_Method: Sicherung nicht loeschbar: " + e.getMessage());
			}
			this.bestStateStored = false;
		}
		if ((this.followUpStarted || this.bestStateStored) && !this.followUpFinished) {
			String why = this.isCancelled() ? "manuell gestoppt"
					: this.endReason != null ? this.endReason
					: this.parameter.getInternalCycleCount() > this.parameter.getIterationThreshold() ? "Grenzwert Iterationen erreicht"
					: "Lauf vorzeitig beendet";
			this.restoreBestStateAndAdjust(why, new ArrayList<String>());
		}

		if (this.isCancelled())
			this.logger.log("Klein_Method: Iteration manuell abgebrochen nach " + iteration + " Durchlaeufen.");
		else if (iteration >= MAX_ITERATIONS)
			this.logger.log("Klein_Method: Sicherheitslimit (" + MAX_ITERATIONS + " Iterationen) erreicht, ohne Grenzwert zu erreichen.");
		else if (this.endReason != null)
			this.logger.log("Klein_Method: Lauf beendet nach " + iteration + " Iterationen: " + this.endReason + ".");
		else if (this.functions.isThresholdReached(this.parameter))
			this.logger.log("Klein_Method: Grenzwert nach " + iteration + " Iterationen erreicht.");
		else
			this.logger.log("Klein_Method: Lauf beendet nach " + iteration + " Iterationen, Grenzwert nicht erreicht.");

		// jedes Laufende bekommt eine Protokollzeile mit Grund; endet die Automatik ohne DONE an einem
		// Sicherheitslimit, zusaetzlich eine Meldung.
		boolean done = this.functions.isThresholdReached(this.parameter);
		String reason;
		boolean warn = false;
		if (this.isCancelled())
			reason = "manuell gestoppt";
		else if (this.endReason != null)
			reason = this.endReason;
		else if (this.singleStepOnly)
			reason = "Einzelschritt ausgeführt";
		else if (done && currentStep == Klein_Method_Adjustment_Step.DYNAMIC) {
			// dynamische AGL: Ziel ist T_prio der Festpunkte, die Beobachtungen werden hier nicht kalibriert
			reason = "fertig (DONE) - Festpunkte kalibriert (alle T_prio fertig)";
			if (!this.functions.isLogPostBelowThreshold(this.parameter))
				reason += ", Max-logPost ueber dem Grenzwert "
						+ Klein_Method_Format.format(this.functions.getMaxLogPostThreshold(this.parameter), "", 2)
						+ " - die Beobachtungen werden in der fixen Ausgleichung kalibriert";
		}
		else if (done)
			reason = this.followUpNote != null ? "fertig (DONE) - " + this.followUpNote : "fertig (DONE)";
		else {
			reason = iteration >= MAX_ITERATIONS ? "Sicherheitslimit (" + MAX_ITERATIONS + " Durchläufe) erreicht, ohne DONE"
					: "Grenzwert Iterationen / Kaskaden-Sicherheitslimit erreicht, ohne DONE";
			warn = true;
		}
		this.functions.setIndividualStepDamping(1.0);
		// Schlussangleichung VKS noch im Lauf (unter der Ausgleichungssperre), damit keine andere Ausgleichung dazwischenkommt
		// und Kalibrierungsnachweis/Laufbericht schon den angeglichenen Stand zeigen
		// nicht in der dynamischen AGL: dort werden die Beobachtungen bewusst nicht veraendert (sonst verschieben sich die
		// gerade kalibrierten T_prio der Festpunkte)
		if (done && !this.singleStepOnly && !this.isCancelled() && this.endReason == null && this.parameter.isFinalScaling()
				&& this.parameter.getProcessPhase() == Klein_Method_Process_Phase.DONE && currentStep != Klein_Method_Adjustment_Step.DYNAMIC) {
			Klein_Method.getAdjustmentLock().lock();
			try {
				Klein_Method.getInstance().runFinalScaling();
			} finally {
				Klein_Method.getAdjustmentLock().unlock();
			}
		}
		String endLine = "LAUF BEENDET: " + reason + " | Max-logPost "
				+ Klein_Method_Format.format(this.functions.getMaxLogPostValue(this.parameter), "", this.parameter.getDecimalPlaces())
				+ " | Phase " + this.parameter.getProcessPhase();
		Klein_Method.getInstance().getProtocol().logEvent(endLine);
		this.logger.log("Klein_Method: " + endLine);
		// freie/fixe AGL: Kontrolle grober Fehler am Laufende (nur Hinweise, keine Abschaltung)
		if ((currentStep == Klein_Method_Adjustment_Step.FREE || currentStep == Klein_Method_Adjustment_Step.FIXED) && !this.singleStepOnly && !this.isCancelled())
			for (String line : Klein_Method.getInstance().grossErrorEndOfRunCheck()) {
				Klein_Method.getInstance().getProtocol().logEvent(line);
				this.logger.log("Klein_Method: " + line);
			}
		// dynamische AGL: Festpunkte an der Lage-Untergrenze (nicht pruefbar) und freie Hoehen (Hoehensystem pruefen)
		if (currentStep == Klein_Method_Adjustment_Step.DYNAMIC && !this.singleStepOnly) {
			try {
				// fehlende Einzelwerte (in JAG3D gilt dann der Gruppenwert) mit dem wirksamen Wert ergaenzen
				for (String line : this.databaseHandler.fillMissingStochasticPointSigmas()) {
					Klein_Method.getInstance().getProtocol().logEvent(line);
					this.logger.log("Klein_Method: " + line);
				}
				boolean separate = this.databaseHandler.isHorizontalOnly3D();
				for (Object[] point : this.databaseHandler.readStochasticPointSigmas()) {
					int dimension = (Integer) point[1];
					double sigma = dimension == 1 ? (Double) point[3] : (Double) point[2];
					double floor = this.parameter.getPointFloor(dimension);
					String line = null;
					double tPrio = point.length > 4 ? (Double) point[4] : Double.NaN;
					if (sigma > 0.0 && sigma <= floor * (1.0 + 1e-6))
						line = "FESTPUNKT AN DER UNTERGRENZE: " + point[0] + " (" + dimension + "D) " + Klein_Method_Format.format(floor * 1000.0, "", 2)
								+ " mm - mit den vorhandenen Messungen nicht pruefbar, genauer wird er nicht angesetzt.";
					else if (!Double.isNaN(tPrio) && tPrio <= Klein_Method_Default_Values.DYNAMIC_TPOST_ZERO)
						line = "FESTPUNKT NICHT PRUEFBAR: " + point[0] + " (" + dimension + "D) " + Klein_Method_Format.format(sigma * 1000.0, "", 3)
								+ " mm - T_prio " + Klein_Method_Format.format(tPrio, "", 3) + ", die Messungen enthalten keine Information zu diesem Punkt; Genauigkeit unveraendert.";
					if (separate && dimension == 3 && (Double) point[3] >= org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler.HEIGHT_FREE_SIGMA * (1.0 - 1e-6))
						line = (line == null ? "" : line + " | ") + "HOEHE FREI: " + point[0] + " sigma_z an der Obergrenze "
								+ Klein_Method_Format.format(org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler.HEIGHT_FREE_SIGMA, "", 0)
								+ " m - gegebene Hoehe passt nicht zum Netz, Hoehensystem pruefen.";
					if (line != null) {
						Klein_Method.getInstance().getProtocol().logEvent(line);
						this.logger.log("Klein_Method: " + line);
					}
				}
				// T_post = T_prio / sigma0^2: die Festpunkte sind auf T_prio kalibriert - weicht der globale Varianzfaktor
				// deutlich von 1 ab, zeigt JAG3D ein anderes T_post (Ursache meist Gruppen-Untergrenzen ueber der Geraetegenauigkeit)
				double s02 = this.databaseHandler.readGlobalVarianceFactor();
				if (Double.isFinite(s02) && (s02 < Klein_Method_Default_Values.VARIANCE_FACTOR_HINT_MIN || s02 > Klein_Method_Default_Values.VARIANCE_FACTOR_HINT_MAX)) {
					String line = "VARIANZFAKTOR: sigma0^2 = " + Klein_Method_Format.format(s02, "", 3) + " - Festpunkte sind auf T_prio = 1 kalibriert, JAG3D zeigt T_post = T_prio / sigma0^2 (etwa "
							+ Klein_Method_Format.format(1.0 / s02, "", 2) + "); die Beobachtungen sind etwa um den Faktor " + Klein_Method_Format.format(Math.sqrt(1.0 / s02), "", 2)
							+ (s02 < 1.0 ? " zu pessimistisch - Gruppen-Untergrenzen (Elektronisch/Historisch) mit der Geraetegenauigkeit vergleichen."
									: " zu optimistisch - Gruppenwerte und grobe Fehler pruefen.");
					Klein_Method.getInstance().getProtocol().logEvent(line);
					this.logger.log("Klein_Method: " + line);
				}
			} catch (SQLException e) {
				this.logger.log("Klein_Method: Festpunkt-Hinweise nicht lesbar: " + e.getMessage());
			}
		}
		// Laufbericht nach jedem Automatik-Lauf (nicht beim Einzelschritt) dynamische AGL - Warnung nach Phase 2
		// (Festpunkte weit ueber der Erfahrung), Abbrechen = nicht als Erfahrung
		if (!this.singleStepOnly) {
			Klein_Method.getInstance().writeCalibrationProof(currentStep, reason, done);
			if (!this.isCancelled())
				Klein_Method.getInstance().storeFreeSolution(currentStep);
			boolean keep = currentStep != Klein_Method_Adjustment_Step.DYNAMIC || !done || this.isCancelled()
					|| Klein_Method.getInstance().checkSwarmPointsAfterPhase2();
			Klein_Method.getInstance().writeRunReport(currentStep, reason, done, (System.currentTimeMillis() - this.runStartMillis) / 1000.0, !keep);
		}
		if (this.followUpRestored && !this.isCancelled() && this.restoredBelowThreshold) {
			final String text = String.format(tr("Der logPost-Grenzwert %s war in Durchlauf %d erreicht, die weiteren Durchläufe haben ihn nicht mehr erreicht. "
					+ "Der Stand aus Durchlauf %d wurde zurückgeschrieben und noch einmal ausgeglichen: Max-logPost %s (Iteration %d).\n\n"
					+ "Die Stufe gilt damit als kalibriert."), Klein_Method_Format.format(this.functions.getMaxLogPostThreshold(this.parameter), "", 2),
					this.followUpFinalPass, this.followUpFinalPass, Klein_Method_Format.format(this.followUpFinalMax, "", this.parameter.getDecimalPlaces()),
					this.followUpFinalIteration);
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Automatik beendet"));
				alert.setHeaderText(tr("Grenzwert erreicht \u2013 Stand aus Durchlauf 1 übernommen"));
				alert.setContentText(text);
				alert.showAndWait();
			});
		} else if (this.followUpRestored && !this.isCancelled()) {
			String largest = Klein_Method.getInstance().describeLargestLogPost(3);
			final String text = String.format(tr("Der logPost-Grenzwert %s wurde nicht erreicht. Der beste Stand wurde zurückgeschrieben und noch einmal ausgeglichen: "
					+ "Max-logPost %s (Durchlauf %d, Iteration %d)."), Klein_Method_Format.format(this.functions.getMaxLogPostThreshold(this.parameter), "", 2),
					Klein_Method_Format.format(this.followUpFinalMax, "", this.parameter.getDecimalPlaces()), this.followUpFinalPass, this.followUpFinalIteration)
					+ (largest.isEmpty() ? "" : "\n\n" + tr("Beobachtungen mit dem gr\u00F6\u00DFten logPost:") + "\n" + largest)
					+ "\n\n" + tr("Liegt das an groben Fehlern (z. B. Exzentrizit\u00E4t, Antennenh\u00F6he, Punktverwechslung), bitte die Daten pr\u00FCfen \u2013 "
							+ "der Nachlauf hat sie nicht durch gr\u00F6\u00DFere Unsicherheiten \u201Eversteckt\u201C.");
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Automatik beendet"));
				alert.setHeaderText(tr("Grenzwert nicht erreicht \u2013 bester Stand \u00FCbernommen"));
				alert.setContentText(text);
				alert.showAndWait();
			});
		}
		if (warn) {
			final String warnText = reason;
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.WARNING);
				alert.setTitle(tr("Klein_Method - Automatik beendet"));
				alert.setHeaderText(tr("Automatik ohne Ergebnis beendet"));
				alert.setContentText(warnText + tr(".\n\nMax-logPost liegt noch \u00FCber dem Grenzwert. Bitte Netz, grobe Fehler "
						+ "(Knopf \u201EGrobe Fehler bereinigen\u201C, Karte \u201EMax Fehler\u201C) und Grenzwerte pr\u00FCfen."));
				alert.showAndWait();
			});
		}

		// am ENDE der Berechnung einmalig pruefen, ob Nullpunktabweichung/Massstab einer der beiden Streckentypen den
		// Rot-Grenzwert erreicht/ueberschritten haben - falls ja, EINE zusammengefasste Hinweis-Messagebox zeigen
		// (nicht pro Typ einzeln, um kein Popup-Feuerwerk auszuloesen).
		this.warnIfAdditionalParametersExceedRedThreshold();

		return null;
	}

	/** Stand im Projekt sichern, wenn er besser ist als die vorhandene Sicherung. @return true wenn gesichert */
	private boolean storeIfBetter(double maxLogPost, int pass, int iteration) throws SQLException {
		if (this.bestStateStored && !(maxLogPost < this.bestStateValue * (1.0 - Klein_Method_Default_Values.FOLLOW_UP_MIN_IMPROVEMENT)))
			return false;
		this.databaseHandler.storeBestState(maxLogPost, pass, iteration);
		this.databaseHandler.commit();
		this.bestStateStored = true;
		this.bestStateValue = maxLogPost;
		return true;
	}

	/** sichtbare Iteration, unter der die Protokollzeile dieser Ausgleichung erscheint (Gruppenphase zaehlt nicht hoch). */
	private int protocolIteration() {
		return this.parameter.getIterationCount()
				+ (this.parameter.getProcessPhase() == Klein_Method_Process_Phase.GROUP_CALIBRATION ? 0 : 1);
	}

	/**
	 * Nachlauf beenden ohne Grenzwert: gesicherten besten Stand zurueckschreiben, neu ausgleichen (damit die Ergebnisse
	 * in JAG3D genau zu diesen Werten passen), Phase DONE. @return false, wenn die Ausgleichung danach nicht fehlerfrei ist
	 */
	private boolean restoreBestStateAndAdjust(String why, List<String> adjustmentDetails) {
		this.followUpFinished = true;
		Klein_Method.getAdjustmentLock().lock();
		try {
			double[] info = this.databaseHandler.readBestStateInfo();
			if (info == null) {
				this.logger.log("Klein_Method: Nachlauf beendet (" + why + "), aber keine Sicherung gefunden - Stand bleibt.");
				return true;
			}
			int[] counts = this.databaseHandler.restoreBestState();
			this.databaseHandler.commit();
			this.parameter.resetIndividualUncertaintyCeilingExceeded();
			Klein_Method.getInstance().setValidAdjustmentResultsAvailable(false);
			EstimationStateType result = Klein_Method.getInstance().runHeadlessAdjustmentSynchronously();
			if (result != EstimationStateType.ERROR_FREE_ESTIMATION) {
				this.logger.log("Klein_Method: Nachlauf - Ausgleichung nach dem Zurueckschreiben nicht fehlerfrei (" + result + ").");
				this.endReason = "Netzausgleichung nach Zurueckschreiben des besten Stands nicht fehlerfrei (" + result + ")";
				Klein_Method.getInstance().handleFailedAdjustmentDuringRun(result, null);
				return false;
			}
			Klein_Method.getInstance().setValidAdjustmentResultsAvailable(true);
			this.databaseHandler.read(this.parameter);
			this.databaseHandler.deleteBestState();
			this.databaseHandler.commit();
			this.bestStateStored = false;
			this.parameter.setProcessPhase(Klein_Method_Process_Phase.DONE);
			this.followUpRestored = true;
			double now = this.functions.getMaxLogPostValue(this.parameter);
			this.followUpFinalMax = now;
			this.followUpFinalPass = (int) info[1];
			this.followUpFinalIteration = (int) info[2];
			this.restoredBelowThreshold = this.functions.isLogPostBelowThreshold(this.parameter);
			String thresholdText = Klein_Method_Format.format(this.functions.getMaxLogPostThreshold(this.parameter), "", 2);
			if (this.restoredBelowThreshold) {
				// gesicherter Stand (Durchlauf 1) liegt unter dem Grenzwert -> kalibriert
				this.parameter.setFollowUpOutcome("erreicht_durchlauf" + (int) info[1]);
				this.followUpNote = "Grenzwert " + thresholdText + " in Durchlauf " + (int) info[1] + " erreicht, spaeter nicht mehr (" + why
						+ ") - Stand aus Durchlauf " + (int) info[1] + " uebernommen: Max-logPost "
						+ Klein_Method_Format.format(now, "", this.parameter.getDecimalPlaces()) + " (Iteration " + (int) info[2] + ")";
			} else {
				this.parameter.setFollowUpOutcome("bester_stand");
				this.followUpNote = "Grenzwert " + thresholdText + " nicht erreicht (" + why + ") - bester Stand uebernommen: Max-logPost "
						+ Klein_Method_Format.format(now, "", this.parameter.getDecimalPlaces()) + " (Durchlauf " + (int) info[1] + ", Iteration "
						+ (int) info[2] + ")";
			}
			String line = "SICHERUNG ZURUECKGESCHRIEBEN: " + this.followUpNote + "; zurueckgeschrieben: " + counts[0] + " Beobachtungen, " + counts[1]
					+ " GNSS-Basislinien, " + counts[2] + " Gruppenwerte, " + counts[3] + " Zusatzparameter; gesichert war Max-logPost "
					+ Klein_Method_Format.format(info[0], "", this.parameter.getDecimalPlaces()) + ".";
			adjustmentDetails.add(line);
			this.logger.log("Klein_Method: " + line);
			Klein_Method.getInstance().getProtocol().logEvent(line);
			return true;
		} catch (Exception e) {
			this.logger.log("Klein_Method: Nachlauf - Zurueckschreiben des besten Stands fehlgeschlagen: " + e.getMessage());
			this.endReason = "Zurueckschreiben des besten Stands fehlgeschlagen (" + e.getMessage() + ")";
			return false;
		} finally {
			Klein_Method.getAdjustmentLock().unlock();
		}
	}

	/**
	 * Zuordnung vor Phase 2 - die mit "Grobe Fehler bereinigen" herausgenommenen Beobachtungen (Status EXCLUDED)
	 * anzeigen; gewaehlte werden wieder aktiviert (Status ACCEPTED) und rechnen ab der naechsten Ausgleichung in
	 * Phase 2 mit.
	 */
	private void handleGrossErrorAssignment(List<String> adjustmentDetails) throws SQLException {
		List<Klein_Method_Database_Handler_Interface.Max_Error_Row> excluded = new ArrayList<Klein_Method_Database_Handler_Interface.Max_Error_Row>();
		Klein_Method.getAdjustmentLock().lock();
		try {
			for (Klein_Method_Database_Handler_Interface.Max_Error_Row row : this.databaseHandler.readGrossErrorEntries())
				if (Klein_Method_Database_Handler_Interface.GROSS_ERROR_EXCLUDED.equals(row.status))
					excluded.add(row);
		} finally {
			Klein_Method.getAdjustmentLock().unlock();
		}
		if (excluded.isEmpty())
			return;
		this.updateMessage("Zuordnung der groben Fehler vor Phase 2 - bitte im Fenster entscheiden");
		java.util.Set<String> reactivate = Klein_Method.getInstance().askGrossErrorAssignment(excluded);
		Klein_Method.getAdjustmentLock().lock();
		try {
			for (Klein_Method_Database_Handler_Interface.Max_Error_Row row : excluded) {
				String line;
				if (reactivate.contains(row.key())) {
					this.databaseHandler.setObservationEnabled(row, true);
					this.databaseHandler.storeGrossErrorEntry(row, Klein_Method_Database_Handler_Interface.GROSS_ERROR_ACCEPTED);
					line = "ZUORDNUNG VOR PHASE 2: wieder aufgenommen - ";
				} else
					line = "ZUORDNUNG VOR PHASE 2: bleibt deaktiviert - ";
				line += (row.observationType == null ? "" : row.observationType.name()) + " " + row.startPointName + "-" + row.endPointName;
				adjustmentDetails.add(line);
				Klein_Method.getInstance().getProtocol().logEvent(line);
				this.logger.log("Klein_Method: " + line);
			}
			this.databaseHandler.commit();
		} finally {
			Klein_Method.getAdjustmentLock().unlock();
		}
	}

	/**
	 * Liest Nullpunktabweichung/Massstab beider Streckentypen frisch aus der DB und zeigt, falls mindestens einer der
	 * Betraege den jeweiligen Rot-Grenzwert erreicht/ueberschritten hat, EINE zusammengefasste Info-Messagebox. Die
	 * farbliche Hervorhebung selbst passiert unabhaengig davon im Tab_Builder (refreshAdditionalParameters()) bei
	 * jedem Tab-Aktivieren - diese Messagebox ist zusaetzlich, NUR am Ende einer Automatik-/Einzelschritt-Berechnung.
	 */
	private void warnIfAdditionalParametersExceedRedThreshold() {
		List<String> exceededMessages = new ArrayList<String>();
		int decimalPlaces = this.parameter.getDecimalPlaces();

		for (Klein_Method_Threshold_Type type : new Klein_Method_Threshold_Type[] {
				Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST, Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST }) {
			try {
				double[] raw = this.databaseHandler.readAdditionalParameters(type);
				double offsetMm = Double.isNaN(raw[0]) ? Double.NaN : LengthUnit.MILLIMETER.fromMeter(raw[0]);
				double scalePpm = Double.isNaN(raw[1]) ? Double.NaN : ScaleUnit.PARTS_PER_MILLION_WRT_ONE.fromUnitless(raw[1]);
				String typeName = type.getDisplayName().replace(" logPost", "");

				if (!Double.isNaN(offsetMm) && Math.abs(offsetMm) >= Klein_Method_Default_Values.ADDITIONAL_PARAMETER_OFFSET_RED_THRESHOLD_MM)
					exceededMessages.add(typeName + " - Nullpunktabweichung a: " + Klein_Method_Format.format(offsetMm, "mm", decimalPlaces));
				if (!Double.isNaN(scalePpm) && Math.abs(scalePpm) >= Klein_Method_Default_Values.ADDITIONAL_PARAMETER_SCALE_RED_THRESHOLD_PPM)
					exceededMessages.add(typeName + " - Ma\u00DFstab m: " + Klein_Method_Format.format(scalePpm, "ppm", decimalPlaces));
			} catch (SQLException e) {
				this.logger.log("Klein_Method: Konnte Zusatzparameter fuer Grenzwert-Hinweis nicht lesen (" + type.getDisplayName() + "): " + e.getMessage());
			}
		}

		if (!exceededMessages.isEmpty()) {
			String messageText = String.join("\n", exceededMessages);
			Platform.runLater(() -> {
				Alert alert = new Alert(AlertType.WARNING);
				alert.setTitle(tr("Klein_Method - Zusatzparameter auffaellig"));
				alert.setHeaderText(tr("Rot-Grenzwert bei mindestens einem Zusatzparameter erreicht/\u00FCberschritten"));
				alert.setContentText(messageText);
				alert.showAndWait();
			});
		}
	}
}
// V1.00_RC3_Klein_Method_Iteration_Task.java
