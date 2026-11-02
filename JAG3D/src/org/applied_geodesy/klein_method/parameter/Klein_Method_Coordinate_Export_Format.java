// V1.00_RC3_Klein_Method_Coordinate_Export_Format.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Format fuer den Koordinaten-Export: ueber ein Dropdown in der Oberflaeche waehlbar, bewusst als Enum statt reinem
 * Boolean, damit spaeter weitere Formate einfach ergaenzt werden koennen (User kuendigte das ausdruecklich an).
 */
public enum Klein_Method_Coordinate_Export_Format {
	TEXT("Text (.txt)", "txt"),
	CSV("CSV (.csv)", "csv")
	;

	private final String displayName;
	private final String fileExtension;

	private Klein_Method_Coordinate_Export_Format(String displayName, String fileExtension) {
		this.displayName = displayName;
		this.fileExtension = fileExtension;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getFileExtension() {
		return this.fileExtension;
	}
}
// V1.00_RC3_Klein_Method_Coordinate_Export_Format.java
