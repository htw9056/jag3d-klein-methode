// V1.00_RC3_Klein_Method_Observation_Uncertainty_Row.java
package org.applied_geodesy.klein_method.database;

/**
 * Traegt die fuer PHASE 2 (individuelle Kalibrierung) noetigen Angaben
 * einer einzelnen Beobachtung, die aktuell im logPost-Bearbeitungsbereich
 * liegt (siehe Klein_Method_Database_Handler_Interface.
 * readObservationsForIndividualCalibration()).
 *
 * Bewusst als einfache, unveraenderliche Datenklasse gehalten (kein
 * Verhalten) - reiner Transport zwischen Database_Handler und Functions.
 */
public class Klein_Method_Observation_Uncertainty_Row {

	private final int observationId;
	private final int groupId;
	private final Double currentIndividualUncertainty; // null, wenn oh0/osh0/... leer ("") bzw. 0 ist
	private final double absoluteLogPostValue;
	private final double distance; // "distance_0" (Meter) - noetig fuer die korrekte Basis-Formel
	// (Varianzfortpflanzung mit sigma_b/sqrt(d) bzw. sigma_c/d oder sigma_c*d/1e6 je nach Typ)

	/**
	 * GNSS-Basislinien haben KEINEN einzelnen sigma_0, sondern drei unabhaengige Achsen-Unsicherheiten
	 * ("sigma_x0"/"sigma_y0"/"sigma_z0" in "GNSSObservationApriori"). Um Phase 2 (Individualkalibrierung) ohne
	 * Vermischung der Achsen auf GNSS zu erweitern, traegt eine Zeile optional eine Achsen-Markierung: null =
	 * generischer Fall (unveraendert, EIN sigma_0), X/Y/Z = diese Zeile repraesentiert GENAU EINE Achse EINER
	 * GNSS-Basislinie - Basis-/Neuwert-Berechnung und Rueckschreiben laufen dann pro Achse UNABHAENGIG (kein
	 * Math.max()/keine Vermischung der drei Achsen - siehe
	 * Klein_Method_Functions.performIndividualCalibrationStep()).
	 */
	public enum GnssAxis { X, Y, Z }

	private final GnssAxis axis; // null = generischer (nicht-GNSS) Fall

	private final double influenceOnPositionAxis;

	public Klein_Method_Observation_Uncertainty_Row(int observationId, int groupId, Double currentIndividualUncertainty, double absoluteLogPostValue, double distance) {
		this(observationId, groupId, currentIndividualUncertainty, absoluteLogPostValue, distance, null, 0.0);
	}

	// GNSS-Variante - zusaetzlich mit Achsen-Markierung.
	public Klein_Method_Observation_Uncertainty_Row(int observationId, int groupId, Double currentIndividualUncertainty, double absoluteLogPostValue, double distance, GnssAxis axis) {
		this(observationId, groupId, currentIndividualUncertainty, absoluteLogPostValue, distance, axis, 0.0);
	}

	// GNSS-Variante MIT dem EP-Achsenwert (influence_on_position_<achse>) dieser Zeile - Grundlage des neuen, echten
	// Widerspruchs-basierten Gewichtungsschluessels im PERCENTAGE-Modus (siehe
	// Klein_Method_Functions.performIndividualCalibrationStep()).
	public Klein_Method_Observation_Uncertainty_Row(int observationId, int groupId, Double currentIndividualUncertainty, double absoluteLogPostValue, double distance, GnssAxis axis, double influenceOnPositionAxis) {
		this.observationId = observationId;
		this.groupId = groupId;
		this.currentIndividualUncertainty = currentIndividualUncertainty;
		this.absoluteLogPostValue = absoluteLogPostValue;
		this.distance = distance;
		this.axis = axis;
		this.influenceOnPositionAxis = influenceOnPositionAxis;
	}

	public int getObservationId() {
		return this.observationId;
	}

	public int getGroupId() {
		return this.groupId;
	}

	/**
	 * true, wenn KEIN individueller Wert eingetragen ist (Fall 1 der
	 * Formel: oh0_alt = "" oder 0 -> Basis aus Gruppenparametern nutzen).
	 */
	public boolean hasNoIndividualUncertainty() {
		return this.currentIndividualUncertainty == null || this.currentIndividualUncertainty <= 0.0;
	}

	public double getCurrentIndividualUncertainty() {
		return this.currentIndividualUncertainty == null ? 0.0 : this.currentIndividualUncertainty;
	}

	/**
	 * |logPost|-Wert dieser Beobachtung - IMMER als Betrag geliefert,
	 * unabhaengig davon, ob die SQL-Abfrage bereits ABS() bildet
	 * (Verteidigung auf zwei Ebenen, analog zu Klein_Method_Ep_Row).
	 */
	public double getAbsoluteLogPostValue() {
		return Math.abs(this.absoluteLogPostValue);
	}

	public double getDistance() {
		return this.distance;
	}

	// null bei generischen (nicht-GNSS) Zeilen.
	public GnssAxis getAxis() {
		return this.axis;
	}

	// 0.0 bei generischen (nicht-GNSS) Zeilen (dort ohne Bedeutung).
	public double getInfluenceOnPositionAxis() {
		return this.influenceOnPositionAxis;
	}
}
// V1.00_RC3_Klein_Method_Observation_Uncertainty_Row.java
