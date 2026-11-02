// V1.00_RC3_Klein_Method_Protocol_Level.java
package org.applied_geodesy.klein_method.parameter;

public enum Klein_Method_Protocol_Level {
	NONE("keine"),
	SHORT("kurz"),
	LONG("lang")
	;

	private final String displayName;

	private Klein_Method_Protocol_Level(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return this.displayName;
	}
}
// V1.00_RC3_Klein_Method_Protocol_Level.java
