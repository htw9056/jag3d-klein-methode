// V1.00_RC3_Klein_Method_Guardian_Status.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Zustand des "Waechters" (siehe Klein_Method_Guardian) - abgeleitet aus dem
 * Verhaeltnis der aktuellen Messung (Dauer + Beobachtungsanzahl als
 * Datenbankgroessen-Ersatz) zur REFERENZMESSUNG (erste F5-Berechnung mit
 * aktiviertem Waechter). Der Farbverlauf auf dem Toggle-Button selbst ist
 * FLIESSEND (siehe Klein_Method_Guardian.computeColorFraction()) - dieser
 * Enum liefert nur die zusaetzliche TEXT-Bezeichnung fuer Tooltip/Protokoll.
 */
public enum Klein_Method_Guardian_Status {
	/** Verhaeltnis zur Referenz < 2.0 - alles im normalen Rahmen. */
	STABIL("Stabil", "#2ECC71"),

	/** Verhaeltnis zur Referenz 2.0 bis < 4.0 - wird spuerbar langsamer/groesser. */
	KRITISCH("Kritisch", "#F1C40F"),

	/** Verhaeltnis zur Referenz >= 4.0 - deutlich schlechter als die Referenz. */
	FEHLER("Fehler", "#E74C3C")
	;

	private final String displayName;
	private final String colorHex;

	private Klein_Method_Guardian_Status(String displayName, String colorHex) {
		this.displayName = displayName;
		this.colorHex = colorHex;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getColorHex() {
		return this.colorHex;
	}
}
// V1.00_RC3_Klein_Method_Guardian_Status.java
