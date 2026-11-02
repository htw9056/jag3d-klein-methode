// V1.00_RC3_Klein_Method_Threshold_Type.java
package org.applied_geodesy.klein_method.parameter;

import java.util.ArrayList;
import java.util.List;

/**
 * Benennt die einzelnen Grenzwerte von "Klein_Method". Bei Bedarf hier weitere Typen ergaenzen - der Rest des Systems
 * (Parameter, Functions, UI) arbeitet automatisch mit jedem Eintrag, der hier steht (siehe Klein_Method_Parameter,
 * das eine Map ueber alle values() dieses Enums fuehrt).
 *
 * Jeder Typ traegt seinen Anzeigenamen (fuer die Oberflaeche), eine Einheit (leerer String = einheitenlos, z.B.
 * logarithmische Werte), einen Mindestwert, der beim Editieren automatisch erzwungen wird (siehe
 * Klein_Method_Parameter.setThresholdValue), ein Flag, ob der Wert bei der Auswertung als Betrag (Math.abs) behandelt
 * werden muss - z.B. logPost/lnPost koennen negativ sein, wuerden aber sonst bei Vergleichen falsch bewertet (siehe
 * Klein_Method_Parameter.getEvaluationValue()) - sowie eine Kategorie (LOG_POST/EP).
 *
 * Die Kategorie wird von Klein_Method_Functions genutzt (getMaxLogPostValue()/
 * getMaxEpValue()/performGroupCalibrationStep()), um "alle logPost-Typen" bzw. "alle EP-Typen" dynamisch ueber
 * valuesOfCategory() abzufragen - STATT einer separat gepflegten, hartcodierten Liste dort.
 */
public enum Klein_Method_Threshold_Type {
	// logPost-Werte sind logarithmische Groessen und damit einheitenlos (Einheit "")
	// koennen negativ sein -> immer als Betrag auswerten (true)
	HORIZONTAL_DIRECTION_LOG_POST("Horizontalrichtung logPost", "", 2.45, true, Category.LOG_POST),
	HORIZONTAL_DISTANCE_LOG_POST("Horizontalstrecke logPost", "", 2.45, true, Category.LOG_POST),
	VERTICAL_DIRECTION_LOG_POST("Vertikalrichtung logPost", "", 2.45, true, Category.LOG_POST),
	SLOPE_DISTANCE_LOG_POST("Schr\u00E4gstrecke logPost", "", 2.45, true, Category.LOG_POST),
	NIVELLEMENT_LOG_POST("Nivellementszug logPost", "", 2.45, true, Category.LOG_POST),
	// GNSS-Basislinien - JAG3D fuehrt jede Komponente (dX/dY/dZ bzw. die 1D-Hoehenkomponente) als eigene skalare
	// Beobachtung, siehe Klein_Method_Default_Values.SUPPORTED_OBSERVATION_TYPES. Grenzwert analog zu den bestehenden
	// logPost-Typen (2.45, Betragsauswertung).
	GNSS_BASELINE_1D_LOG_POST("GNSS-Basislinie 1D logPost", "", 2.45, true, Category.LOG_POST),
	GNSS_BASELINE_2D_LOG_POST("GNSS-Basislinie 2D logPost", "", 2.45, true, Category.LOG_POST),
	GNSS_BASELINE_3D_LOG_POST("GNSS-Basislinie 3D logPost", "", 2.45, true, Category.LOG_POST),
	// das TODO "Mindestwert der EP-Typen noch offen" ist jetzt fuer ALLE 8 EP-Typen AUFGELOEST - 6 davon bereits, die
	// letzten 2 (HORIZONTAL_DIRECTION_EP/ VERTICAL_DIRECTION_EP) erst, nachdem der Anwender fuer diese beiden
	// explizit Millimeter-Werte nachgereicht hat.
	// WICHTIGER FUND bei der Umsetzung: EP ("influence_on_position") ist - unabhaengig vom Beobachtungstyp
	// (Richtung/Strecke/Nivellement/GNSS) - IMMER eine raeumliche Verschiebung in Millimetern, NIE eine Groesse im
	// nativen Winkel-Mass des Beobachtungstyps selbst - der Aufruf epMm =
	// LengthUnit.MILLIMETER.fromMeter(row.getEpValueRaw()) in Klein_Method_Functions.performEpSingleStep() rechnet
	// AUSNAHMSLOS in mm um, auch fuer Richtungs-/Zenitwinkel-Beobachtungen.
	// die beiden zuletzt offenen 0.0-Platzhalter sind jetzt AUFGELOEST - diesmal mit echten, ausdruecklich als
	// Millimeter benannten Werten. Damit ist das TODO "Mindestwert der EP-Typen noch offen" fuer alle 8 Typen
	// abgeschlossen.
	HORIZONTAL_DIRECTION_EP("Horizontalrichtung EP", "mm", 0.30, true, Category.EP),
	HORIZONTAL_DISTANCE_EP("Horizontalstrecke EP", "mm", 0.50, true, Category.EP),
	VERTICAL_DIRECTION_EP("Vertikalrichtung EP", "mm", 0.50, true, Category.EP),
	SLOPE_DISTANCE_EP("Schr\u00E4gstrecke EP", "mm", 0.50, true, Category.EP),
	NIVELLEMENT_EP("Nivellementszug EP", "mm", 0.10, true, Category.EP),
	// siehe GNSS_BASELINE_*_LOG_POST oben.
	GNSS_BASELINE_1D_EP("GNSS-Basislinie 1D EP", "mm", 1.00, true, Category.EP),
	GNSS_BASELINE_2D_EP("GNSS-Basislinie 2D EP", "mm", 1.00, true, Category.EP),
	GNSS_BASELINE_3D_EP("GNSS-Basislinie 3D EP", "mm", 1.00, true, Category.EP)
	;

	/** Kategorie eines Grenzwert-Typs - siehe valuesOfCategory(). */
	public enum Category {
		LOG_POST,
		EP
	}

	private final String displayName;
	private final String unit;
	private final double minimumAllowedValue;
	private final boolean evaluateAsAbsoluteValue;
	private final Category category;

	private Klein_Method_Threshold_Type(String displayName, String unit, double minimumAllowedValue, boolean evaluateAsAbsoluteValue, Category category) {
		this.displayName = displayName;
		this.unit = unit;
		this.minimumAllowedValue = minimumAllowedValue;
		this.evaluateAsAbsoluteValue = evaluateAsAbsoluteValue;
		this.category = category;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getUnit() {
		return this.unit;
	}

	public double getMinimumAllowedValue() {
		return this.minimumAllowedValue;
	}

	public boolean isEvaluateAsAbsoluteValue() {
		return this.evaluateAsAbsoluteValue;
	}

	public Category getCategory() {
		return this.category;
	}

	/**
	 * Liefert ALLE Grenzwert-Typen einer Kategorie (LOG_POST oder EP) -
	 * dynamisch aus diesem Enum abgeleitet, damit ein neuer Typ automatisch
	 * ueberall dort mitlaeuft, wo "alle logPost-Typen"/"alle EP-Typen"
	 * gebraucht werden, ohne eine zweite Liste woanders pflegen zu muessen.
	 */
	public static Klein_Method_Threshold_Type[] valuesOfCategory(Category category) {
		List<Klein_Method_Threshold_Type> result = new ArrayList<Klein_Method_Threshold_Type>();
		for (Klein_Method_Threshold_Type type : values())
			if (type.category == category)
				result.add(type);
		return result.toArray(new Klein_Method_Threshold_Type[0]);
	}

	/**
	 * Erzeugt automatisch einen erklaerenden Tooltip-Text aus den
	 * vorhandenen Angaben (Anzeigename, Einheit, Mindestwert, Betrags-
	 * auswertung) - kein zusaetzlicher Konstruktor-Parameter je Eintrag
	 * noetig, bleibt bei kuenftigen Grenzwerten automatisch konsistent.
	 */
	public String getTooltip() {
		StringBuilder tooltip = new StringBuilder("Grenzwert f\u00FCr ").append(this.displayName);
		if (!this.unit.isEmpty())
			tooltip.append(" [").append(this.unit).append("]");
		tooltip.append(".\nMindestwert: ").append(this.minimumAllowedValue);
		if (!this.unit.isEmpty())
			tooltip.append(" ").append(this.unit);
		tooltip.append(" (wird beim Editieren automatisch erzwungen).");
		if (this.evaluateAsAbsoluteValue)
			tooltip.append("\nWird als Betrag ausgewertet - negative Werte sind zul\u00E4ssig.");
		return tooltip.toString();
	}
}
// V1.00_RC3_Klein_Method_Threshold_Type.java
