// V1.00_RC3_Klein_Method_Point_Ep_Row.java
package org.applied_geodesy.klein_method.database;

/**
 * EIN Beweglicher-Anschlusspunkt mit seinem EP-Wert (Einfluss auf die Punktlage, "influence on position") je
 * Komponente - Grundlage fuer den Festpunktanalyse-Empfehlungsbericht (Klein_Method.generatePointCandidateReport(),
 * Stufe 1) UND fuer die Punkt-Auswahl bei Stufe 2 (Klein_Method.promoteStablePointsToPlannedFixedPoints()).
 *
 * epY/epX/epZ sind BEREITS in mm umgerechnet - koennen je nach Punktdimension (1D/2D/3D) fehlen (null), werden dann
 * bei der kombinierten Betragsbildung als 0 behandelt.
 *
 * epMagnitude = sqrt(epY^2 + epX^2 + epZ^2) in mm.
 */
public class Klein_Method_Point_Ep_Row {

	private final int pointId;
	private final int groupId;
	private final int dimension;
	private final String name;
	private final String groupName;
	private final Double epY;
	private final Double epX;
	private final Double epZ;
	private final double epMagnitude;

	public Klein_Method_Point_Ep_Row(int pointId, int groupId, int dimension, String name, String groupName,
			Double epY, Double epX, Double epZ) {
		this.pointId = pointId;
		this.groupId = groupId;
		this.dimension = dimension;
		this.name = name;
		this.groupName = groupName;
		this.epY = epY;
		this.epX = epX;
		this.epZ = epZ;

		double y = epY == null ? 0.0 : epY;
		double x = epX == null ? 0.0 : epX;
		double z = epZ == null ? 0.0 : epZ;
		this.epMagnitude = Math.sqrt(y * y + x * x + z * z);
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

	public String getName() {
		return this.name;
	}

	public String getGroupName() {
		return this.groupName;
	}

	public Double getEpY() {
		return this.epY;
	}

	public Double getEpX() {
		return this.epX;
	}

	public Double getEpZ() {
		return this.epZ;
	}

	public double getEpMagnitude() {
		return this.epMagnitude;
	}
}
// V1.00_RC3_Klein_Method_Point_Ep_Row.java
