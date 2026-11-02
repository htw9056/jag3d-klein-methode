// V1.00_RC3_Klein_Method_Format.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Zentrale Formatierung fuer ALLE angezeigten Zahlenwerte in Klein_Method
 * (Grenzwerte, Ausgelesene Werte, Live-Modus, Protokoll, ...) - damit
 * Nachkommastellen und Einheiten-Anzeige an EINER Stelle geregelt sind und
 * ueberall automatisch konsistent bleiben.
 *
 * Nachkommastellen sind einstellbar (3/4/5/6, Standard 4) - siehe
 * Klein_Method_Parameter.getDecimalPlaces()/setDecimalPlaces() - und werden
 * IMMER mit Nullen aufgefuellt (z.B. bei 4 Nachkommastellen: "0.0050"),
 * die Einheit wird IMMER mit angezeigt (auch wenn leer, dann ohne
 * Einheiten-Suffix - z.B. logPost ist einheitenlos).
 */
public class Klein_Method_Format {

	public static final int MIN_DECIMAL_PLACES = 3;
	public static final int MAX_DECIMAL_PLACES = 6;
	public static final int DEFAULT_DECIMAL_PLACES = 4;

	private Klein_Method_Format() {
		// reine Utility-Klasse, keine Instanzen noetig
	}

	/**
	 * Formatiert einen Wert mit der angegebenen Anzahl Nachkommastellen und
	 * haengt die Einheit an (z.B. "0.0050 m"). Ist die Einheit leer (z.B.
	 * logPost, EP-Werte sind teils einheitenlos), wird nur die Zahl
	 * zurueckgegeben, ohne abschliessendes Leerzeichen.
	 */
	public static String format(double value, String unit, int decimalPlaces) {
		int clampedDecimalPlaces = clampDecimalPlaces(decimalPlaces);
		String numberPart = String.format("%." + clampedDecimalPlaces + "f", value);
		return (unit == null || unit.isEmpty()) ? numberPart : numberPart + " " + unit;
	}

	/**
	 * Stellt sicher, dass die Nachkommastellen-Einstellung immer im
	 * erlaubten Bereich (3-6) liegt - Sicherheitsnetz, falls z.B. ein
	 * fehlerhafter Wert aus der Properties-Datei geladen wird.
	 */
	public static int clampDecimalPlaces(int decimalPlaces) {
		if (decimalPlaces < MIN_DECIMAL_PLACES)
			return MIN_DECIMAL_PLACES;
		if (decimalPlaces > MAX_DECIMAL_PLACES)
			return MAX_DECIMAL_PLACES;
		return decimalPlaces;
	}
}
// V1.00_RC3_Klein_Method_Format.java
