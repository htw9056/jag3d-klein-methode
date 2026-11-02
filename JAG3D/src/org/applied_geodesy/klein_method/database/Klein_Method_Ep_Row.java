// V1.00_RC3_Klein_Method_Ep_Row.java
package org.applied_geodesy.klein_method.database;

import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;

/**
 * Eine einzelne Beobachtung im Kontext der EP-Einzelschritt-Kalibrierung (siehe
 * Klein_Method_Functions.performEpSingleStep()) - anders als Klein_Method_Observation_Uncertainty_Row (Phase 2,
 * logPost-Bearbeitungs-bereich) wird hier IMMER die volle Liste aller aktiven Beobachtungen eines Typs benoetigt
 * (kein Bereichsfilter), da die Beobachtung mit dem GROESSTEN EP-Wert ueber ALLE Typen hinweg gesucht wird.
 *
 * optional um eine GNSS-Achsen-Markierung erweitert - null bei den 5 generischen (nicht-GNSS) Typen, X/Y/Z bei einer
 * GNSS-Basislinie (dort repraesentiert JEDE Zeile GENAU EINE Achse EINER Basislinie, wie in Phase 2). Wird von
 * performEpSingleStep() gebraucht, um beim Zurueckschreiben zu entscheiden, ob der generische
 * (writeIndividualUncertainty()) oder der achsen-bewusste GNSS-Schreibpfad (writeGnssIndividualUncertainty())
 * verwendet werden muss - KEINE Vermischung der Achsen, exakt dieselbe Disziplin wie in Phase 2.
 */
public class Klein_Method_Ep_Row {

	private final int observationId;
	private final int groupId;
	private final Klein_Method_Threshold_Type type;
	private final double epValueRaw; // Rohwert aus der DB (Meter, kanonisch)
	private final double absoluteLogPostValue;
	private final Double currentIndividualUncertaintyRaw; // null, wenn oh0/osh0/... leer ("") bzw. 0 ist (Rohwert, Radiant/Meter)
	private final double distance;
	private final org.applied_geodesy.klein_method.database.Klein_Method_Observation_Uncertainty_Row.GnssAxis axis;

	public Klein_Method_Ep_Row(int observationId, int groupId, Klein_Method_Threshold_Type type, double epValueRaw,
			double absoluteLogPostValue, Double currentIndividualUncertaintyRaw, double distance) {
		this(observationId, groupId, type, epValueRaw, absoluteLogPostValue, currentIndividualUncertaintyRaw, distance, null);
	}

	// GNSS-Variante MIT Achsen-Markierung - siehe Klassenkommentar oben.
	public Klein_Method_Ep_Row(int observationId, int groupId, Klein_Method_Threshold_Type type, double epValueRaw,
			double absoluteLogPostValue, Double currentIndividualUncertaintyRaw, double distance,
			org.applied_geodesy.klein_method.database.Klein_Method_Observation_Uncertainty_Row.GnssAxis axis) {
		this.observationId = observationId;
		this.groupId = groupId;
		this.type = type;
		this.epValueRaw = epValueRaw;
		this.absoluteLogPostValue = absoluteLogPostValue;
		this.currentIndividualUncertaintyRaw = currentIndividualUncertaintyRaw;
		this.distance = distance;
		this.axis = axis;
	}

	public int getObservationId() {
		return this.observationId;
	}

	public int getGroupId() {
		return this.groupId;
	}

	public Klein_Method_Threshold_Type getType() {
		return this.type;
	}

	/**
	 * EP-Rohwert (Meter, kanonisch) - IMMER als Betrag geliefert, unabhaengig davon, ob die SQL-Abfrage bereits ABS()
	 * bildet.
	 */
	public double getEpValueRaw() {
		return Math.abs(this.epValueRaw);
	}

	/**
	 * |logPost|-Wert dieser Beobachtung - IMMER als Betrag geliefert,
	 * unabhaengig davon, ob die SQL-Abfrage bereits ABS() bildet
	 * (Verteidigung auf zwei Ebenen, wie bei getEpValueRaw()).
	 */
	public double getAbsoluteLogPostValue() {
		return Math.abs(this.absoluteLogPostValue);
	}

	public boolean hasNoIndividualUncertainty() {
		return this.currentIndividualUncertaintyRaw == null || this.currentIndividualUncertaintyRaw <= 0.0;
	}

	public double getCurrentIndividualUncertaintyRaw() {
		return this.currentIndividualUncertaintyRaw == null ? 0.0 : this.currentIndividualUncertaintyRaw;
	}

	public double getDistance() {
		return this.distance;
	}

	// null bei generischen (nicht-GNSS) Zeilen.
	public org.applied_geodesy.klein_method.database.Klein_Method_Observation_Uncertainty_Row.GnssAxis getAxis() {
		return this.axis;
	}
}
// V1.00_RC3_Klein_Method_Ep_Row.java
