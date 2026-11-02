// V1.00_RC3_Klein_Method_Device_Class.java
package org.applied_geodesy.klein_method.parameter;

/**
 * Geraeteklasse fuer die Untergrenze der GRUPPENunsicherheit (sigma_a/ sigma_b/sigma_c, "globale Werte" - das
 * Gegenstueck zur individuellen Messunsicherheit) - siehe Klein_Method_Default_Values.getGroupUncertaintyFloor().
 *
 * HISTORISCH: groessere, fuer historische (weniger praezise) Messinstrumente passende Werte, nur fuer 4
 * Beobachtungstypen definiert (Horizontalrichtung, Horizontalstrecke, Vertikalrichtung, Schraegstrecke).
 *
 * KOMBINIERT: verhaelt sich IDENTISCH zu ELEKTRONISCH. Bleibt trotzdem als eigene, waehlbare Option bestehen (z.B.
 * falls spaeter doch noch eine echte Kombinationslogik gewuenscht wird, ohne die Oberflaeche nochmal anfassen zu
 * muessen).
 *
 * Einzelne Geraeteklassen-Unterarten (z.B. historisch: Bussole, Theodolit, geschaetzt) werden hier BEWUSST NICHT
 * abgebildet - das muss der Nutzer selbst einordnen koennen.
 */
public enum Klein_Method_Device_Class {
	ELEKTRONISCH("Elektronisch"),
	HISTORISCH("Historisch"),
	KOMBINIERT("Kombiniert")
	;

	private final String displayName;

	private Klein_Method_Device_Class(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return this.displayName;
	}
}
// V1.00_RC3_Klein_Method_Device_Class.java
