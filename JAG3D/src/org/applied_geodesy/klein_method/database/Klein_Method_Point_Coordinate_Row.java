// V1.00_RC3_Klein_Method_Point_Coordinate_Row.java
package org.applied_geodesy.klein_method.database;

/**
 * Eine einzelne Ergebniskoordinate (a-posteriori, Tab "Ergebnis" in JAG3D)
 * fuer den Koordinaten-Export (siehe Klein_Method.exportCoordinates()).
 * Rechtswert/Hochwert/Hoehe koennen je nach Punktdimension (1D/2D/3D)
 * fehlen (null) - werden beim Export dann als "k.A." ausgegeben.
 *
 * Zuordnung (deutsche Vermessungskonvention, in JAG3D-Spalten "y"/"x"/"z"):
 * Rechtswert = Spalte "y", Hochwert = Spalte "x", Hoehe = Spalte "z".
 */
public class Klein_Method_Point_Coordinate_Row {

	private final String name; // Punktnummer
	private final String code;
	private final Double rechtswert; // Spalte "y"
	private final Double hochwert;   // Spalte "x"
	private final Double hoehe;      // Spalte "z"

	// optionales "PointApriori"."id" - null fuer den bestehenden a-posteriori-Export-Pfad, gesetzt fuer den
	// Fangkreis-Vor-Scan (readAllPointAprioriCoordinatesForFangkreisScan()), wo die id noetig ist, um bei
	// bestaetigter Umbenennung GENAU DIESEN Punkt anzusprechen (siehe
	// Klein_Method_Database_Handler.renamePointForFangkreisViolation()). Alter 5-Argumente-Konstruktor bleibt
	// unveraendert bestehen (delegiert mit id=null) - exakt dasselbe Muster wie bei Klein_Method_Ep_Row's optionalem
	// "axis"-Feld.
	private final Integer id;

	public Klein_Method_Point_Coordinate_Row(String name, String code, Double rechtswert, Double hochwert, Double hoehe) {
		this(name, code, rechtswert, hochwert, hoehe, null);
	}

	public Klein_Method_Point_Coordinate_Row(String name, String code, Double rechtswert, Double hochwert, Double hoehe, Integer id) {
		this.name = name;
		this.code = code;
		this.rechtswert = rechtswert;
		this.hochwert = hochwert;
		this.hoehe = hoehe;
		this.id = id;
	}

	public String getName() {
		return this.name;
	}

	public Integer getId() {
		return this.id;
	}

	public String getCode() {
		return this.code;
	}

	public Double getRechtswert() {
		return this.rechtswert;
	}

	public Double getHochwert() {
		return this.hochwert;
	}

	public Double getHoehe() {
		return this.hoehe;
	}
}
// V1.00_RC3_Klein_Method_Point_Coordinate_Row.java
