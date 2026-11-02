// V1.00_RC3_Klein_Method_Data_Status.java
package org.applied_geodesy.klein_method.parameter;

/** Spaeter (Festpunktanalyse) soll der naechste Schritt nur starten, wenn dieser Status OPTIMIZED (gruen) ist. */
public enum Klein_Method_Data_Status {

	/** Alle Beobachtungstypen liegen unter ihrem Grenzwert - fertig. */
	OPTIMIZED("Messdaten optimiert", "#2ECC71"),

	/** Mindestens ein Beobachtungstyp liegt noch ueber seinem Grenzwert -
	 *  noch nicht fertig (egal ob nie gestartet, abgebrochen oder laufend). */
	NEEDS_OPTIMIZATION("Messdaten optimieren", "#F1C40F"),

	/** Obergrenzen-Sperre aktiv ODER technischer Fehler beim Lesen
	 *  (z.B. SQLException) - manuelles Eingreifen noetig. */
	ERROR("Messdaten Fehler", "#E74C3C")
	;

	private final String displayName;
	private final String colorHex;

	private Klein_Method_Data_Status(String displayName, String colorHex) {
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
// V1.00_RC3_Klein_Method_Data_Status.java
