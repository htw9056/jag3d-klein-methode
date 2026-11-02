// V1.00_RC3_Klein_Method_Process_Phase.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Uebergeordneter Ablauf von Klein_Method: erst Phase 1 (Gruppenunsicherheit kalibrieren) VOLLSTAENDIG abschliessen,
 * danach Phase 2 (individuelle Messunsicherheit im aktuellen Bearbeitungsbereich). Diese Reihenfolge gilt IMMER -
 * keine Abwechslung innerhalb der Automatik-Schleife, da ein individueller Wert die Gruppenunsicherheit fuer die
 * betroffene Beobachtung dauerhaft uebersteuert (siehe JAG3D-Verhaltensregel).
 *
 * Im Klein_Method_Protocol (eine Zeile je Iteration, mit Phase-Spalte) ist das direkt sichtbar: mehrere
 * aufeinanderfolgende GROUP_CALIBRATION-Zeilen, bevor INDIVIDUAL_CALIBRATION auftaucht.
 */
public enum Klein_Method_Process_Phase {
	/**
	 * FESTPUNKTANALYSE Phase 1: waehrend der DYNAMISCHEN AGL laeuft AUSSCHLIESSLICH die Festpunkt-analyse (nur
	 * Bewegliche Anschlusspunkte) - Gruppenunsicherheit (sigma_y0/x0/z0) je Punktgruppe kalibrieren. Die gewohnte
	 * Beobachtungs-Kalibrierung (GROUP_CALIBRATION/INDIVIDUAL_CALIBRATION) wird waehrend DYNAMIC NIE erreicht - siehe
	 * Klein_Method_Parameter.resetProcessPhase(Klein_Method_Adjustment_Step). laeuft GENAU EINMAL am Anfang des Laufs
	 * (VKS je Dimension, Band 0,996-1,004, ohne Turbo), danach POINT_INDIVIDUAL_CALIBRATION - kein Hin- und
	 * Herwechseln mehr.
	 */
	POINT_GROUP_CALIBRATION,

	/**
	 * FESTPUNKTANALYSE Phase 2 - einzelne Festpunkte ueber T_prio (Ziel 1,000, Band 0,996-1,004, ln), Turbo mit
	 * Vorhersage; endet mit DONE, sobald alle Punkte gleichzeitig fertig sind.
	 */
	POINT_INDIVIDUAL_CALIBRATION,

	/**
	 * Phase 1: σa/σb/σc je Beobachtungstyp neu kalibrieren - EIN Aufruf von performGroupCalibrationStep() je
	 * Iteration; wechselt zu INDIVIDUAL_CALIBRATION erst, wenn ALLE Typen/Gruppen entweder im Zielkorridor oder an
	 * ihrer Untergrenze sind. Seit in der Automatik EIN Versuch je Kaskaden-Pass; regelt performGroupDampingStep()
	 * die Gruppen-Sigmas zusaetzlich in jeder Iteration von INDIVIDUAL_CALIBRATION nach. NUR bei FREE/FIXED erreicht
	 * - waehrend DYNAMIC laeuft stattdessen POINT_GROUP_CALIBRATION.
	 */
	GROUP_CALIBRATION,

	/** Phase 2: individuelle Messunsicherheit (oh0/osh0/ov0/os0) fuer
	 *  Beobachtungen im aktuellen logPost-Bearbeitungsbereich anpassen. */
	INDIVIDUAL_CALIBRATION,

	/** Beide Phasen abgeschlossen - Grenzwert-Pruefung entscheidet, ob
	 *  eine weitere Iteration noetig ist. */
	DONE
	;
}
// V1.00_RC3_Klein_Method_Process_Phase.java
