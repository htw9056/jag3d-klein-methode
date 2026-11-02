// V1.00_RC3_Klein_Method_Guardian.java
package org.applied_geodesy.klein_method.logic;

import org.applied_geodesy.klein_method.parameter.Klein_Method_Guardian_Status;

/**
 * "Waechter" fuer groesser werdende Projekte (z.B. viele GNSS-Basislinien) -
 * misst bei JEDER Netzausgleichung (F5, egal ob manuell oder automatisch
 * ausgeloest) Dauer UND Beobachtungsanzahl (als Ersatz fuer die Datenbank-
 * groesse - eine echte Dateigroesse ist ohne bekannten Projektordner-Pfad
 * nicht messbar, siehe Klein_Method_Protocol fuer dieselbe Einschraenkung).
 *
 * Da feste Schwellen PC-abhaengig waeren (unterschiedliche Leistung, sogar
 * derselbe PC mit unterschiedlicher Auslastung), wird die ERSTE Messung bei
 * aktiviertem Waechter als REFERENZ gespeichert. Alle weiteren Messungen
 * werden relativ dazu bewertet (Verhaeltnis aktuell/Referenz, jeweils
 * getrennt fuer Dauer und Beobachtungsanzahl - der SCHLECHTERE der beiden
 * Werte entscheidet).
 *
 * Farbverlauf (siehe computeColorFraction()): 1.0x=gruen, 2.0x=gelb,
 * 4.0x=rot, dazwischen fliessend interpoliert - plausible, aber bewusst
 * einfache Grenzen (siehe Klassenkommentar in Klein_Method_Guardian_Status).
 */
public class Klein_Method_Guardian {

	private static final double COLOR_RATIO_GREEN = 1.0;
	private static final double COLOR_RATIO_YELLOW = 2.0;
	private static final double COLOR_RATIO_RED = 4.0;

	private boolean enabled = true; // Standard: an

	private Long referenceDurationMillis = null;
	private Long referenceObservationCount = null;

	private double lastRatio = 1.0;
	private Klein_Method_Guardian_Status lastStatus = Klein_Method_Guardian_Status.STABIL;

	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * Anders als beim Protokoll-Toggle gibt es hier KEINE Sperre - der
	 * Waechter kann jederzeit ein-/ausgeschaltet werden (reine Anzeige/
	 * Warnfunktion, kein Datenverlust-Risiko beim Umschalten).
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Bei Projektwechsel aufzurufen: Referenzmessung verwerfen, damit die
	 * naechste Messung im neuen Projekt wieder als NEUE Referenz gilt.
	 */
	public void resetForNewProject() {
		this.referenceDurationMillis = null;
		this.referenceObservationCount = null;
		this.lastRatio = 1.0;
		this.lastStatus = Klein_Method_Guardian_Status.STABIL;
	}

	/**
	 * Traegt eine neue Messung (Dauer einer F5-Berechnung + aktuelle
	 * Beobachtungsanzahl) ein. Tut nichts, wenn der Waechter aus ist. Die
	 * ERSTE Messung (nach Aktivierung bzw. nach Projektwechsel) wird zur
	 * Referenz, liefert also immer STABIL/1.0x zurueck.
	 */
	public void recordMeasurement(long durationMillis, long observationCount) {
		if (!this.enabled)
			return;

		if (this.referenceDurationMillis == null) {
			this.referenceDurationMillis = Math.max(durationMillis, 1L);
			this.referenceObservationCount = Math.max(observationCount, 1L);
			this.lastRatio = 1.0;
			this.lastStatus = Klein_Method_Guardian_Status.STABIL;
			return;
		}

		double durationRatio = durationMillis / (double) this.referenceDurationMillis;
		double observationCountRatio = observationCount / (double) this.referenceObservationCount;

		this.lastRatio = Math.max(durationRatio, observationCountRatio); // schlechterer Wert entscheidet
		this.lastStatus = this.deriveStatus(this.lastRatio);
	}

	private Klein_Method_Guardian_Status deriveStatus(double ratio) {
		if (ratio < COLOR_RATIO_YELLOW)
			return Klein_Method_Guardian_Status.STABIL;
		if (ratio < COLOR_RATIO_RED)
			return Klein_Method_Guardian_Status.KRITISCH;
		return Klein_Method_Guardian_Status.FEHLER;
	}

	public Klein_Method_Guardian_Status getLastStatus() {
		return this.lastStatus;
	}

	public double getLastRatio() {
		return this.lastRatio;
	}

	public boolean hasReferenceMeasurement() {
		return this.referenceDurationMillis != null;
	}

	/**
	 * Liefert einen Wert zwischen 0.0 (rein gruen) und 1.0 (rein rot) fuer
	 * den FLIESSENDEN Farbverlauf auf dem Toggle-Button - unabhaengig von
	 * den 3 diskreten Text-Zustaenden (STABIL/KRITISCH/FEHLER).
	 */
	public double computeColorFraction() {
		if (this.lastRatio <= COLOR_RATIO_GREEN)
			return 0.0;
		if (this.lastRatio >= COLOR_RATIO_RED)
			return 1.0;
		return (this.lastRatio - COLOR_RATIO_GREEN) / (COLOR_RATIO_RED - COLOR_RATIO_GREEN);
	}
}
// V1.00_RC3_Klein_Method_Guardian.java
