// V1.00_RC3_Klein_Method_Evaluation_Method.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Bewertungsmethode von "Klein_Method":
 * - LOG_POST: laeuft automatisch als Endlosschleife (Start/Stop)
 * - EP: laeuft NICHT automatisch, sondern nur manuell Schritt fuer
 *       Schritt ueber den Button "Einzelschritt" (bewusste Sicherheits-
 *       bremse, siehe Klein_Method_Tab_Builder)
 */
public enum Klein_Method_Evaluation_Method {
	LOG_POST("logPost"),
	EP("EP")
	;

	private final String displayName;

	private Klein_Method_Evaluation_Method(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return this.displayName;
	}
}
// V1.00_RC3_Klein_Method_Evaluation_Method.java
