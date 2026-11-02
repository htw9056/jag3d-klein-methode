// V1.00_RC3_Klein_Method_Distribution_Type.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Verteilung von "Klein_Method": normal oder prozentual.
 * Standard: NORMAL.
 */
public enum Klein_Method_Distribution_Type {
	NORMAL("normal"),
	PERCENTAGE("prozentual")
	;

	private final String displayName;

	private Klein_Method_Distribution_Type(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return this.displayName;
	}
}
// V1.00_RC3_Klein_Method_Distribution_Type.java
