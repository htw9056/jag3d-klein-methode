// V1.00_RC3_Klein_Method_Adjustment_Step.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Die 3 moeglichen Ausgleichungsschritte, IMMER in dieser Reihenfolge zu
 * durchlaufen (nicht alle zwingend, aber niemals in anderer Reihenfolge):
 *
 *   1. FREE    - Messdaten [freie Ausgleichung]
 *   2. DYNAMIC - Festpunktanalyse [dynamische Ausgleichung]
 *   3. FIXED   - Messdaten [fixe Ausgleichung]
 *
 * Wird anhand der im Projekt AKTIVEN Punktkategorien erkannt (siehe
 * Klein_Method.getCurrentAdjustmentStep(), PointType aus JAG3D):
 *
 *   REFERENCE_POINT aktiv?      -> FIXED   (gewinnt immer, auch mit STOCHASTIC_POINT zusammen)
 *   sonst STOCHASTIC_POINT aktiv? -> DYNAMIC
 *   sonst DATUM_POINT aktiv?    -> FREE
 *   sonst                       -> UNDEFINED (keine erkennbare/eindeutige Konfiguration)
 */
public enum Klein_Method_Adjustment_Step {
	FREE("Messdaten [freie Ausgleichung]", "freie Ausgleichung"),
	DYNAMIC("Festpunktanalyse [dynamische Ausgleichung]", "dynamische Ausgleichung"),
	FIXED("Messdaten [fixe Ausgleichung]", "fixe Ausgleichung"),
	UNDEFINED("Nicht eindeutig erkennbar", "nicht eindeutig")
	;

	private final String displayName;
	private final String shortDisplayName;

	private Klein_Method_Adjustment_Step(String displayName, String shortDisplayName) {
		this.displayName = displayName;
		this.shortDisplayName = shortDisplayName;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	/**
	 * Kurzform ohne Praefix (z.B. "freie Ausgleichung" statt "Messdaten [freie Ausgleichung]") - fuer die
	 * Protokollzeile.
	 */
	public String getShortDisplayName() {
		return this.shortDisplayName;
	}
}
// V1.00_RC3_Klein_Method_Adjustment_Step.java
