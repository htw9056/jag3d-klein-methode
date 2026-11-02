// V1.00_RC3_Klein_Method_Point_Tprio_Row.java
package org.applied_geodesy.klein_method.database;

/**
 * EIN Beweglicher-Anschlusspunkt mit seiner aktuellen individuellen
 * Unsicherheit (sigma_y0/x0/z0, kanonisch Meter) UND seiner Tprio
 * ("A-priori test statistic") - Grundlage fuer die individuelle
 * Punkt-Kalibrierung "Phase 2" der Festpunktanalyse
 * (Klein_Method_Functions.performPointIndividualCalibrationStep()).
 *
 * Anders als EP (3 getrennte Achsen-Werte) ist Tprio EIN kombinierter Wert
 * je Punkt (bestaetigt anhand JAG3D-Ressourcenschluessel
 * "teststatistic.apriori" - EINE Spalte, keine Achsen-Aufspaltung).
 *
 * sigmaY0/sigmaX0/sigmaZ0 koennen je nach Punktdimension (1D/2D/3D) fehlen
 * (null).
 */
public class Klein_Method_Point_Tprio_Row {

	private final int pointId;
	private final int groupId;
	private final int dimension;
	private final Double sigmaY0;
	private final Double sigmaX0;
	private final Double sigmaZ0;
	private final Double tprio;
	// Phase 2 bewertet T_post; der Punktname dient der Anzeige im Debug-Fenster/Protokoll.
	private final Double tpost;
	private final String name;

	public Klein_Method_Point_Tprio_Row(int pointId, int groupId, int dimension,
			Double sigmaY0, Double sigmaX0, Double sigmaZ0, Double tprio) {
		this(pointId, groupId, dimension, sigmaY0, sigmaX0, sigmaZ0, tprio, null, null);
	}

	public Klein_Method_Point_Tprio_Row(int pointId, int groupId, int dimension,
			Double sigmaY0, Double sigmaX0, Double sigmaZ0, Double tprio, Double tpost, String name) {
		this.tpost = tpost;
		this.name = name;
		this.pointId = pointId;
		this.groupId = groupId;
		this.dimension = dimension;
		this.sigmaY0 = sigmaY0;
		this.sigmaX0 = sigmaX0;
		this.sigmaZ0 = sigmaZ0;
		this.tprio = tprio;
	}

	public int getPointId() {
		return this.pointId;
	}

	public int getGroupId() {
		return this.groupId;
	}

	public int getDimension() {
		return this.dimension;
	}

	public Double getSigmaY0() {
		return this.sigmaY0;
	}

	public Double getSigmaX0() {
		return this.sigmaX0;
	}

	public Double getSigmaZ0() {
		return this.sigmaZ0;
	}

	public Double getTprio() {
		return this.tprio;
	}

	/** T_post ("A-posteriori test statistic") - Bewertungsgroesse der Phase 2. */
	public Double getTpost() {
		return this.tpost;
	}

	/** Punktname (Anzeige), sonst "ID <id>". */
	public String getName() {
		return this.name == null || this.name.isEmpty() ? "ID " + this.pointId : this.name;
	}
}
// V1.00_RC3_Klein_Method_Point_Tprio_Row.java
