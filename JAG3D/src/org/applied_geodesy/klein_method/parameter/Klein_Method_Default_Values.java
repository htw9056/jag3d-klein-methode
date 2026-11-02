// V1.00_RC3_Klein_Method_Default_Values.java
package org.applied_geodesy.klein_method.parameter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.applied_geodesy.adjustment.network.ObservationType;

/**
 * Zentrale Ablage fuer feste, projektweite Default-Werte von Klein_Method,
 * die an keiner anderen Stelle "zu Hause" sind (z.B. weil sie keinem
 * einzelnen Klein_Method_Threshold_Type zugeordnet sind, sondern fuer
 * die Berechnungslogik als Ganzes gelten).
 *
 * Bewusst als einfache Konstanten-Klasse gehalten, damit neue Default-
 * Werte spaeter einfach ergaenzt werden koennen, ohne bestehenden Code
 * anzufassen.
 */
public class Klein_Method_Default_Values {

	/**
	 * MUSS bei jeder neuen Paket-Version hier von Hand nachgezogen werden (analog zur Versionszeile jeder einzelnen
	 * Datei).
	 */
	public static final String PACKAGE_VERSION = "V1.00 RC3";

	/**
	 * Standardwert des "Grenzwert Iterationen" je Kaskaden-Pass. Der interne Zyklenzaehler wird bei jedem
	 * Pass-Neustart zurueckgesetzt (Klein_Method_Parameter.resetIterationCount()), die Grenze gilt also pro Pass.
	 */
	public static final int DEFAULT_MAX_ITERATIONS_PER_PASS = 200;

	/**
	 * absolute Obergrenze fuer "Grenzwert Iterationen". Jede Eingabe (UI-Feld, Properties-Datei) wird in
	 * Klein_Method_Parameter.setIterationThreshold() auf [0; 500] geklemmt.
	 */
	public static final int HARD_MAX_ITERATIONS_LIMIT = 500;

	public static final double LOG_POST_BAND_OFFSET_DEFAULT = 0.3;

	/**
	 * Absolute Untergrenze fuer die fertig berechnete Gruppenunsicherheit
	 * (σa/σb/σc, siehe Klein_Method_Functions.computeGroupUncertainty()) -
	 * gilt als RUECKFALLWERT fuer alle Typen ohne Geraeteklassen-spezifischen
	 * Wert (siehe getGroupUncertaintyFloor()).
	 */
	public static final double GROUP_UNCERTAINTY_FLOOR_DEFAULT = 0.5;

	/**
	 * eigene, von GROUP_UNCERTAINTY_FLOOR_DEFAULT UNABHAENGIGE Untergrenze der GNSS-Gruppenunsicherheit - gilt fuer
	 * ALLE 3 Komponenten sigma_a/sigma_b/ sigma_c gleichermassen, in der JEWEILS EIGENEN Anzeige-Einheit dieser
	 * Komponente (siehe getSigmaAUnit()/getSigmaBUnit()/getSigmaCUnit() unten - fuer GNSS: sigma_a/sigma_b in mm,
	 * sigma_c in ppm), EXAKT nach demselben, bereits etablierten Muster wie bei den 5 uebrigen Beobachtungstypen oben
	 * (identische Zahl, typspezifisch unterschiedliche Einheit je Komponente - siehe Javadoc bei
	 * getGroupUncertaintyFloor() unten). GILT UNABHAENGIG von der Geraeteklasse (GNSS-Empfaenger kennen kein
	 * "historisch" im Sinne von Klein_Method_Device_Class - dieselbe Ausnahme gilt bereits fuer
	 * INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_GNSS oben, siehe dort).
	 *
	 * Dieser Patch setzt die vom Anwender gewuenschte 1.0mm-Untergrenze trotzdem WIE BEAUFTRAGT fuer die
	 * GRUPPEN-Ebene (sigma_a/b/c) um - siehe Antwort an den Anwender fuer die vollstaendige Einordnung.
	 */
	public static final double GROUP_UNCERTAINTY_FLOOR_GNSS = 1.0;

	/**
	 * der Zielkorridor der Gruppen-Daempfung ist jetzt ueber zwei UI-Felder in der Sektion "Grenzwerte" einstellbar
	 * ("VKS-Band Min (s²):" / "VKS-Band Max (s²):", siehe Klein_Method_Parameter.setVksBandMin()/Max()). Liegt der
	 * reelle a-posteriori Varianzfaktor F einer Komponente innerhalb [Min; Max] (Grenzen eingeschlossen), bleibt
	 * sigma unveraendert.
	 *
	 * Erlaubte Bereiche: Min in [0.900; 0.996], Max in [1.004; 1.100]. Werte ausserhalb (oder nicht lesbar) werden
	 * HART auf den jeweiligen Default zurueckgesetzt.
	 */
	public static final double VKS_BAND_MIN_DEFAULT = 0.970;
	public static final double VKS_BAND_MIN_LOWER_LIMIT = 0.900;
	public static final double VKS_BAND_MIN_UPPER_LIMIT = 0.996;
	public static final double VKS_BAND_MAX_DEFAULT = 1.030;
	public static final double VKS_BAND_MAX_LOWER_LIMIT = 1.004;
	public static final double VKS_BAND_MAX_UPPER_LIMIT = 1.100;

	/**
	 * Untergrenze des Phase-2-Bearbeitungsbands, sobald sich der Trichter unten oeffnet. Beobachtungen zwischen
	 * dieser Grenze und dem Grenzwert werden bereits mitkorrigiert, damit sie nicht knapp ueber den Grenzwert
	 * zurueckrutschen. Das DONE-Kriterium (Max-logPost unter dem Grenzwert) bleibt unveraendert. UI-Feld
	 * "Trichter-Untergrenze (logPost):" in der Sektion "Grenzwerte". Erlaubt [2.00; 2.45], sonst HART auf den Default
	 * zurueck.
	 *
	 * Default 2.30. Mit prozentual + einfache Anpassung + Kaskade liefert 2.30 die besten Werte (Mittel 4,6 mm, P90
	 * 10,1 mm, Max 15,6 mm gegenueber normal 6,2 / 11,3 / 23,5 mm). Zahlen korrigiert.
	 */
	public static final double FUNNEL_FLOOR_LOG_POST_DEFAULT = 2.30;

	/**
	 * Grenze grober Fehler als lineare Abweichung einer Beobachtung. Bis einschliesslich 0,10 m "haelt die
	 * Ausgleichung aus" (gruen, wieder aufnehmen), darueber grober Fehler (rot). EINZIGES Kriterium fuer Sperre und
	 * Bereinigung - kein logPost oder anderer statistischer Wert.
	 */
	public static final double GROSS_ERROR_LINEAR_LIMIT_DEFAULT = 0.10;
	public static final double GROSS_ERROR_LINEAR_LIMIT_LOWER_LIMIT = 0.001;
	public static final double GROSS_ERROR_LINEAR_LIMIT_UPPER_LIMIT = 100.0;
	/**
	 * technische Mindestkontrolle - bei einem Redundanzanteil unter 5 % ist der geschaetzte grobe Fehler (v / r)
	 * nicht bestimmbar (r -> 0); solche Beobachtungen koennen nicht geprueft werden.
	 */
	public static final double GROSS_ERROR_MIN_REDUNDANCY = 0.05;
	/** hoechstens so viele Beobachtungen nimmt "Grobe Fehler bereinigen" in einem Durchgang heraus. */
	public static final int GROSS_ERROR_MAX_STEPS = 25;
	/**
	 * Grobe Fehler bereinigen, punktweise: gehoeren mindestens GROSS_ERROR_POINT_BLOCK_MIN Beobachtungen ueber der Grenze
	 * und davon mindestens GROSS_ERROR_POINT_SHARE zu einem gemeinsamen Punkt, werden die Beobachtungen dieses Punkts mit
	 * mindestens GROSS_ERROR_BLOCK_FRACTION der groessten Abweichung (und ueber der Grenze) in EINEM Schritt
	 * herausgenommen (hoechstens GROSS_ERROR_BLOCK_MAX) statt einzeln.
	 */
	public static final int GROSS_ERROR_POINT_BLOCK_MIN = 5;
	public static final double GROSS_ERROR_POINT_SHARE = 0.8;
	public static final double GROSS_ERROR_BLOCK_FRACTION = 0.5;
	public static final int GROSS_ERROR_BLOCK_MAX = 1000;
	/**
	 * Grobe Fehler bereinigen, verstreute Ausreisser: je Schritt die schlechteste Beobachtung und weitere ueber der Grenze
	 * mit mindestens GROSS_ERROR_BLOCK_FRACTION der groessten Abweichung, die keinen Punkt mit einer schon gewaehlten
	 * teilen (Nachbarn eines groben Fehlers sind oft nur mitverschmiert) - hoechstens so viele je Schritt.
	 */
	public static final int GROSS_ERROR_BATCH_MAX = 10;
	/**
	 * Grobe Fehler bereinigen, robuste Vorauswahl aus der Vorpruefung: nur ab so vielen Beobachtungen ueber der Grenze,
	 * hoechstens so viele in einem Schritt.
	 */
	public static final int GROSS_ERROR_ROBUST_MIN = 3;
	public static final int GROSS_ERROR_ROBUST_MAX = 50;
	/** Gegenprobe einzeln (wenn die gemeinsame scheitert): hoechstens so viele Beobachtungen einzeln wieder aufnehmen und pruefen. */
	public static final int GROSS_ERROR_SINGLE_CHECK_MAX = 30;

	/**
	 * Wiederholte Messungen (Uebergewicht) je Messart: 0 aus, 1 zusammenfassen (Mittel je Wiederholungsgruppe). 2 =
	 * regewichten (Einzel-Sigma x Wurzel(n)) ist nicht mehr waehlbar - im Vergleich an einem echten Netz lagen die
	 * Punkte bis 46 cm daneben, weil die festgehaltenen Startgenauigkeiten nicht kalibriert werden. Der Wert bleibt
	 * nur, um regewichtete Projekte zurueckzunehmen.
	 */
	/** statistischer Test: hoechstens so viele "nicht aufdeckbar"-Befunde speichern. */
	public static final int STATISTICAL_TEST_MAX_UNDETECTABLE = 200;
	/** Gruppentests auf gemeinsame Ursache: hoechstens so viele Gruppen (je 2 Versuchsrechnungen), Versuchsverschiebung [m], Irrtumswahrscheinlichkeit, delta0 (alpha 0,1 %, beta 80 %). */
	public static final int GROUP_TEST_MAX_GROUPS = 60;
	/**
	 * Massen-Basislinien (Epochen-Basislinien aus der GNSS-Auswertung): erkannt bei mehr als MASS_BASELINE_LIMIT
	 * Basislinien oder ab MASS_BASELINE_MIN Basislinien mit im Mittel mindestens MASS_EPOCHS_PER_PAIR je Punktpaar.
	 * Mehr als MASS_BASELINE_LIMIT aktive Basislinien rechnet Klein_Method nicht (JAG3D-Speichergrenze).
	 */
	public static final int MASS_BASELINE_LIMIT = 20000, MASS_BASELINE_MIN = 1000, MASS_EPOCHS_PER_PAIR = 20;
	/** Massen-Basislinien: Genauigkeit des Mittels = robuste Streuung der Epochen, mindestens MASS_SIGMA_MIN [m]; Hinweis ab MASS_SCATTER_WARN [m] Lage-Streuung. */
	public static final double MASS_SIGMA_MIN = 0.001, MASS_SCATTER_WARN = 0.10;
	public static final boolean MASS_THINNING_DEFAULT = true;
	/**
	 * Schlussangleichung nach fertiger Automatik: liegt der Varianzfaktor einer Messart (Summe Omega / Summe Redundanzanteile)
	 * unter dem VKS-Band, weil Untergrenzen die Genauigkeiten festhalten, werden alle Genauigkeiten der Messart mit Wurzel(Faktor)
	 * verkleinert und neu ausgeglichen - hoechstens FINAL_SCALING_ROUNDS Runden, je Runde mindestens FINAL_SCALING_MIN_STEP.
	 */
	public static final boolean FINAL_SCALING_DEFAULT = true;
	/**
	 * "Grobe Fehler bereinigen" automatisch zu Beginn der Automatik (freie/fixe AGL), wenn eine Beobachtung ueber der
	 * Grenze grober Fehler liegt - vor Durchlauf 1, damit auch die Gruppenwerte aus sauberen Daten geschaetzt werden.
	 */
	public static final boolean AUTO_GROSS_CLEANUP_DEFAULT = true;
	/**
	 * laengenabhaengiger Anteil der Grenze grober Fehler fuer GNSS-Basislinien (ppm): Grenze = Grenze grober Fehler +
	 * ppm * Laenge. Lange SAPOS-Linien (Troposphaere, Hoehe) werden so nicht als grober Fehler abgeschaltet.
	 */
	public static final double GROSS_ERROR_GNSS_PPM_DEFAULT = 2.0, GROSS_ERROR_GNSS_PPM_MIN = 0.0, GROSS_ERROR_GNSS_PPM_MAX = 20.0;
	/** Kontrolle am Ende der freien AGL: Genauigkeit einer Beobachtung mehr als dieses Vielfache ihres Ausgangswerts. */
	public static final double INFLATION_WARN_FACTOR = 5.0;
	/** "Hoehe nur aus Hoehenfestpunkten (1D)" in Projekten ohne eigene Einstellung: ein, angewendet nur wenn die Pruefung besteht. */
	public static final boolean HEIGHT_FROM_1D_DEFAULT = true;
	public static final int FINAL_SCALING_ROUNDS = 3;
	public static final double FINAL_SCALING_MIN_STEP = 0.5;
	public static final double GROUP_TEST_SHIFT = 0.01, GROUP_TEST_ALPHA = 0.001, GROUP_TEST_DELTA0 = 4.13;
	/** Gruppen mit groesserem kleinsten aufdeckbaren Fehler [m] gelten als nicht kontrolliert und werden nicht gelistet. */
	public static final double GROUP_TEST_MAX_MDB = 0.5;

	public static final int REPETITION_MODE_OFF = 0, REPETITION_MODE_MERGE = 1, REPETITION_MODE_REWEIGHT = 2, REPETITION_MODE_MERGE_CALIBRATE = 3;
	public static final int REPETITION_MODE_DEFAULT = REPETITION_MODE_MERGE_CALIBRATE;
	/** Mindestgrenzen fuer Sprung/Drift innerhalb einer Wiederholungsgruppe (Grenze = max(3 x Einzel-Sigma, Mindestgrenze)). */
	public static final double REPETITION_JUMP_MIN_LEVELING_M = 0.002;
	public static final double REPETITION_JUMP_MIN_DISTANCE_M = 0.005;
	public static final double REPETITION_JUMP_MIN_ANGLE_MGON = 5.0;
	/** Gegenprobe einzeln: Zeitbudget in Sekunden. */
	public static final int GROSS_ERROR_SINGLE_CHECK_SECONDS = 300;
	/**
	 * Kleinster Abstand (ms), in dem Klein_Method waehrend der Automatik die JAG3D-Anzeige (Baumauswahl,
	 * Ergebnisse, Varianzkomponenten, Gruppeneigenschaften) wie nach F5 aus der Datenbank neu laedt. Am Laufende
	 * wird immer neu geladen.
	 */
	public static final long JAG3D_VIEW_REFRESH_INTERVAL_MS = 10000;
	public static final double FUNNEL_FLOOR_LOG_POST_LOWER_LIMIT = 2.00;

	/**
	 * Obergrenze fuer das Abbruchziel ab Kaskaden-Durchlauf 3 und festes Ziel ab Durchlauf 4.
	 *
	 * Start Durchlauf 3: Ziel = bester Max-logPost aus DL 2, nur wenn dieser Bestwert unter 5.2 liegt; sonst kein
	 * Ziel in DL 3.
	 *
	 * Ab Durchlauf 4: Ziel = 5.2. Sobald Max-logPost nach einer Ausgleichung <= Ziel ist, endet die Automatik sofort
	 * mit dem aktuellen Stand - OHNE Untergrenzen-Erzwingung und ohne weiteren Durchlauf. Wirkt nur mit
	 * "Kaskaden-Mehrfachlauf: ja".
	 */
	public static final double CASCADE_BACKUP_TARGET_LOG_POST = 5.2;

	/**
	 * ab Kaskaden-Durchlauf 2 endet die Berechnung, sobald Max-logPost UNTER diesem Wert liegt (ohne
	 * Untergrenzen-Erzwingung, aktueller Stand bleibt). UI-Feld in der Sektion "Grenzwerte". Standard 5.2, erlaubt
	 * [2.00; 10.00], sonst HART auf 5.2 zurueck. Mit 2.45 entspricht das dem strengen Verhalten von.
	 */
	public static final double CASCADE_ABORT_LIMIT_DEFAULT = 5.2;
	public static final double CASCADE_ABORT_LIMIT_LOWER_LIMIT = 2.00;
	public static final double CASCADE_ABORT_LIMIT_UPPER_LIMIT = 10.00;

	/**
	 * Nachlauf auf den Grenzwert: nach Erreichen der Abbruchgrenze ab DL 2 rechnet Phase 2 weiter Richtung Grenzwert (2,45).
	 * Der beste Stand wird im Projekt gesichert; endet der Nachlauf ohne Grenzwert, wird er zurueckgeschrieben.
	 * Hoechstzahl der Nachlauf-Iterationen (0 = Nachlauf aus) und Anzahl Iterationen ohne neuen Bestwert bis zum Ende.
	 */
	public static final double FOLLOW_UP_MAX_ITERATIONS_DEFAULT = 30.0, FOLLOW_UP_MAX_ITERATIONS_MIN = 1.0, FOLLOW_UP_MAX_ITERATIONS_MAX = 200.0;
	public static final double FOLLOW_UP_PATIENCE_DEFAULT = 8.0, FOLLOW_UP_PATIENCE_MIN = 2.0, FOLLOW_UP_PATIENCE_MAX = 50.0;
	/** relative Verbesserung, ab der ein Max-logPost als neuer Bestwert zaehlt (0,1 %). */
	public static final double FOLLOW_UP_MIN_IMPROVEMENT = 0.001;
	/**
	 * Daempfung im Nachlauf: steigt der Max-logPost gegenueber der vorigen Nachlauf-Iteration wieder an, wird der
	 * logarithmische Korrekturschritt der individuellen Kalibrierung mit FOLLOW_UP_DAMPING_DOWN multipliziert (nie
	 * unter FOLLOW_UP_DAMPING_MIN), bei Verbesserung mit FOLLOW_UP_DAMPING_UP wieder vergroessert (hoechstens 1,0).
	 * Verhindert das Pendeln um den Grenzwert.
	 */
	public static final double FOLLOW_UP_DAMPING_DOWN = 0.5;
	public static final double FOLLOW_UP_DAMPING_UP = 1.5;
	public static final double FOLLOW_UP_DAMPING_MIN = 0.25;
	/** Nachlauf: Grenzwert gilt als erreicht, wenn der Max-logPost auf so viele Nachkommastellen gerundet nicht darueber liegt. */
	public static final int FOLLOW_UP_TOLERANCE_DECIMALS = 3;
	public static final double FUNNEL_FLOOR_LOG_POST_UPPER_LIMIT = 2.45;

	/**
	 * numerische Schutzklammer der Ur-Formel sigma_neu = sigma_alt * (1 + log10(F)). Fuer F <= 0.1 waere (1 +
	 * log10(F)) <= 0, sigma wuerde also 0 (= "Parameter nicht verwenden" in JAG3D) oder NEGATIV - beides zerstoert
	 * das stochastische Modell. Der Faktor wird deshalb nach unten auf diesen Wert begrenzt (max. 70 % Reduktion je
	 * Schritt). Das ist KEINE Zielfixierung, sondern nur eine Vorzeichen-/Nullschutz-Klammer; im normalen
	 * Arbeitsbereich (F > 0.2) greift sie nie.
	 */
	public static final double GROUP_DAMPING_MIN_FACTOR = 0.3;

	/**
	 * Obergrenze fuer die fertig berechnete individuelle Messunsicherheit (sigma_0) - bestaetigt vom User: 0.999 fuer
	 * linear gemessene Typen (Strecken), 0.499 fuer Nivellementszuege, 0.999 fuer Richtungstypen
	 * (Horizontalrichtung/Vertikalrichtung) - aber dort in GON statt Metern (siehe getIndividualUncertaintyUnit()).
	 * Wird sie erreicht/ueberschritten, bricht die Automatik ab (siehe
	 * Klein_Method_Parameter.isIndividualUncertaintyCeilingExceeded()).
	 *
	 * WICHTIG: seit der Korrektur der Basis-Formel (siehe Klein_Method_Functions.computeIndividualUncertaintyBasis())
	 * gibt es KEINE Untergrenze mehr fuer die individuelle Messunsicherheit.
	 */
	public static final double INDIVIDUAL_UNCERTAINTY_CEILING_DEFAULT = 0.999;
	public static final double INDIVIDUAL_UNCERTAINTY_CEILING_NIVELLEMENT = 0.499;

	// DIE EINE, ausdruecklich vom Anwender angeordnete Ausnahme von der obigen "KEINE Untergrenze mehr"-Regel - gilt
	// AUSSCHLIESSLICH fuer die einmalige Abschluss-Kompression beim Uebergang zu PHASE:DONE (siehe
	// Klein_Method_Functions.performFloorEnforcementCompression()), NICHT fuer die normale iterative Kalibrierung
	// (computeIndividualUncertainty() bleibt dort unveraendert ohne Untergrenze). Nur wirksam, wenn
	// Klein_Method_Parameter.isFloorEnforcementEnabled() true liefert (UI-Toggle "Untergrenze erzwingen", Standard
	// ja).
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_NIVELLEMENT = 0.0001; // 0.1 mm, Meter
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_HORIZONTAL_DISTANCE = 0.0005; // 0.5 mm, Meter
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_SLOPE_DISTANCE = 0.0005; // 0.5 mm, Meter
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_GNSS = 0.0010; // 1.0 mm, Meter
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_DIRECTION = 0.0003; // Gon
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_ZENITH_ANGLE = 0.0005; // Gon

	// Daempfungsfaktor der Abschluss-Kompression (Formel exakt:
	// sigma_neu = sigma_Boden + (sigma_alt - sigma_Boden) * 0.4).
	public static final double INDIVIDUAL_UNCERTAINTY_COMPRESSION_FACTOR = 0.4;

	/**
	 * Grenzwerte fuer die Festpunktanalyse: "Festpunkte" = Bewegliche Anschlusspunkte. Anders als bei
	 *
	 * Beobachtungen: Untergrenze/Obergrenze/Sprungwert sind fuer BEIDE Geraeteklassen (elektronisch/historisch)
	 * IDENTISCH - daher hier nur EIN Wert je Konstante statt zwei. Sprungwert ist je Dimension unterschiedlich
	 * (1D/2D/3D).
	 *
	 * "Sprungwert"-Mechanismus: faellt der neu berechnete Wert UNTER den Sprungwert, wird NICHT die normale (immer
	 * kleiner werdende) Korrektur angewendet, sondern direkt auf die Untergrenze gesprungen - verhindert endlose
	 * asymptotische Annaeherung an Null.
	 */
	public static final double POINT_UNCERTAINTY_FLOOR = 0.0000001;
	/**
	 * einstellbare Untergrenze der Festpunkt-Genauigkeit in der dynamischen AGL (Millimeter): Lage bzw. 2D/3D und
	 * Hoehenpunkte 1D. Ein Festpunkt, der mit den vorhandenen Messungen nicht pruefbar ist (T_prio bleibt klein), wird
	 * nicht genauer als dieser Wert angesetzt - sonst waere er praktisch starr und die Messungen (z. B. GNSS-Basislinien)
	 * haetten auf ihn keinen Einfluss mehr.
	 */
	public static final double POINT_FLOOR_POSITION_MM_DEFAULT = 1.0, POINT_FLOOR_HEIGHT_1D_MM_DEFAULT = 0.3;
	public static final double POINT_FLOOR_MM_MIN = 0.01, POINT_FLOOR_MM_MAX = 50.0;
	/** Untergrenze der Festpunkte in Projekten ohne aktive GNSS-Basislinien (Millimeter, Lage und Hoehe). */
	public static final double POINT_FLOOR_NO_GNSS_MM = 0.01;
	/** Hinweis am Ende der dynamischen AGL, wenn der globale Varianzfaktor ausserhalb dieses Bereichs liegt (T_post = T_prio / sigma0^2). */
	public static final double VARIANCE_FACTOR_HINT_MIN = 0.8, VARIANCE_FACTOR_HINT_MAX = 1.25;
	/**
	 * getrennte Hoehe der 3D-Anschlusspunkte bei "Hoehe nur aus 1D" in der dynamischen AGL: sigma_z je Punkt aus dem
	 * T_prio der Hoehe (v_z^2 / (sigma_z^2 * r_z)), ausserhalb [BAND_MIN; BAND_MAX] sigma_z_neu = sigma_z * max(1 +
	 * log10(T_z); 0,3), hoechstens x3 je Schritt, begrenzt auf [FLOOR; HEIGHT_FREE_SIGMA]. Passen die Hoehen nicht
	 * (anderes Hoehensystem), steigt sigma_z von selbst bis zur Obergrenze - die Hoehe ist dann frei.
	 */
	public static final double SEPARATE_HEIGHT_FLOOR = 0.001;
	public static final double SEPARATE_HEIGHT_BAND_MIN = 0.9, SEPARATE_HEIGHT_BAND_MAX = 1.1, SEPARATE_HEIGHT_MAX_STEP = 3.0;
	public static final double POINT_UNCERTAINTY_CEILING = 0.99999; // alle Dimensionen/Geraeteklassen gleich

	/**
	 * harte Grenzwerte, NUR fuer den GNSS-Prozentual-Modus
	 * (Klein_Method_Functions.performIndividualCalibrationStep()) - unabhaengig von der bestehenden, generischen
	 * INDIVIDUAL_UNCERTAINTY_CEILING_DEFAULT/_NIVELLEMENT (die bei Ueberschreitung die GESAMTE Berechnung abbricht).
	 * Ueberschreitet der neue sigma-Wert IRGENDEINER der drei Raumachsen einer Basislinie
	 * GNSS_AXIS_OUTLIER_CEILING_MM, oder faellt eine Achse unter GNSS_AXIS_OUTLIER_FLOOR_MM (numerisch nahe 0, Gefahr
	 * unendlicher Gewichte in der Ausgleichung), wird NUR diese eine Basislinie deaktiviert
	 * (Klein_Method_Database_Handler.deactivateGnssObservation()) - alle anderen Basislinien und die laufende
	 * Berechnung selbst bleiben unberuehrt. Werte in Millimetern (siehe Klein_Method_Functions, wo sie ueber
	 * LengthUnit.MILLIMETER.toMeter() in die interne Arbeitseinheit "m" der GNSS-Typen umgerechnet werden, bevor sie
	 * mit sigma_neu verglichen werden - sigma_neu liegt bei GNSS IMMER in Metern vor, siehe
	 * Klein_Method_Default_Values.getIndividualUncertaintyUnit()).
	 */
	public static final double GNSS_AXIS_OUTLIER_CEILING_MM = 35.0;
	public static final double GNSS_AXIS_OUTLIER_FLOOR_MM = 0.1;

	/**
	 * der Prozentual-Modus divergierte bereits ab Iteration 3 in wilden Oszillationen (27,4 -> 115,5 -> 1859,1 ->
	 * 2882,1 -> 105,5 -> ...), NICHT in einer sanften Aufschaukelung - das ist die Signatur einer ungedaempften
	 * Rueckkopplungsschleife: der Gewichtungsschluessel einer Iteration (influence_on_position) haengt vom sigma DER
	 * VORHERIGEN Iteration ab, das wiederum vom Gewicht DAVOR - ohne Daempfung schaukelt sich das systematisch auf.
	 * GNSS_PERCENTAGE_RELAXATION_FACTOR ist der neue Daempfungsfaktor (klassische "successive under-relaxation",
	 * Standardtechnik gegen genau dieses Oszillationsverhalten bei iterativer Neugewichtung): der tatsaechlich
	 * geschriebene Wert bewegt sich nur zu diesem Anteil in Richtung des frisch berechneten Werts, der Rest bleibt
	 * beim vorherigen Wert stehen (siehe Klein_Method_Functions.performIndividualCalibrationStep()). NICHT ueber die
	 * UI einstellbar - bei Bedarf in einer Folgeversion ergaenzbar, analog zum bestehenden Trichter-Faktor.
	 *
	 * dies IST bereits exakt der hier beschriebene Mechanismus - "Multiplikation des Aenderungsfaktors vor dem
	 * Zurueckschreiben mit einem festen Relaxationsfaktor" ist wortgleich die aktive successive-under-relaxation
	 * ueber genau diese Konstante (siehe Klein_Method_Functions.performIndividualCalibrationStep(): "newValue =
	 * baseValue + relaxationFactor * (rawPercentageValue - baseValue)").
	 */
	public static final double GNSS_PERCENTAGE_RELAXATION_FACTOR = 0.4;

	/**
	 * harte Kappung IM SELEKTIONS-TRICHTER selbst (Klein_Method_Functions.performIndividualCalibrationStep(),
	 * GNSS-Zweig, VOR jeder Gewichts-berechnung) - eine GNSS-Basislinie, deren |p_post| diesen Wert ueberschreitet,
	 * gilt als grobe Fehlmessung und wird SOFORT komplett deaktiviert
	 * (Klein_Method_Database_Handler.deactivateGnssObservation()), unabhaengig vom Verteilungsmodus (NORMAL wie
	 * PERCENTAGE) - Begruendung des Users: ein einzelner extremer Ausreisser haelt das globale Max-logPost hoch,
	 * wodurch computeLogPostBandLowerBound() die Untergrenze des Bearbeitungsbandes so weit anhebt, dass andere,
	 * eigentlich noch bearbeitbare Basislinien aus dem Band herausfallen. |p_post| ist in dieser Datenbank bereits
	 * log10-transformiert und EINHEITENLOS (siehe Klassenkommentar von Klein_Method_Database_Handler) - kein
	 * mm/m-Umrechnungsschritt noetig, anders als bei GNSS_AXIS_OUTLIER_CEILING_MM/ _FLOOR_MM oben.
	 */
	public static final double GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD = 1000.0;

	/**
	 * Stagnations-Waechter fuer Phase 2 (Klein_Method_Functions.performIndividualCalibrationStep(), siehe dort).
	 * Aendert sich der GLOBALE Max-logPost-Wert (Klein_Method_Functions.getMaxLogPostValue()) ueber
	 * STAGNATION_WINDOW_SIZE aufeinanderfolgende Iterationen hinweg um weniger als STAGNATION_EPSILON
	 * (Plateau-Bildung), wird der intern wirksame Trichter-Faktor (Klein_Method_Parameter.getEffectiveFunnelFactor(),
	 * NICHT der vom Anwender in der UI gesetzte Klein_Method_Parameter.getFunnelFactor()!) schrittweise um
	 * STAGNATION_FUNNEL_STEP erhoeht, bis maximal STAGNATION_FUNNEL_FACTOR_MAX - der UI-Wert selbst (mit seiner
	 * eigenen Grenze FUNNEL_FACTOR_MAX=0.3 in Klein_Method_Parameter) bleibt dabei komplett unangetastet. Sobald der
	 * globale Max-logPost wieder um mehr als STAGNATION_EPSILON IN EINEM SCHRITT sinkt ("signifikanter Fortschritt"),
	 * faellt der wirksame Faktor sofort auf den UI-Standardwert zurueck
	 * (Klein_Method_Parameter.resetWidenedFunnelFactor()).
	 */
	public static final int STAGNATION_WINDOW_SIZE = 5;
	public static final double STAGNATION_EPSILON = 0.05;
	public static final double STAGNATION_FUNNEL_STEP = 0.05;
	public static final double STAGNATION_FUNNEL_FACTOR_MAX = 0.60;
	/**
	 * Waechter freie AGL (prozentual): kein neuer Bestwert von Max-logPost (mindestens STAGNATION_NO_BEST_MIN_IMPROVEMENT
	 * relativ besser) seit so vielen Iterationen -> Wechsel auf NORMAL. Ergaenzt das Plateau-Kriterium (5 Werte
	 * innerhalb 0,05), das ein langsames Ansteigen erst spaet erkennt.
	 */
	public static final int STAGNATION_NO_BEST_ITERATIONS = 3;
	public static final double STAGNATION_NO_BEST_MIN_IMPROVEMENT = 0.01;
	/** Daempfung im Modus normal bei Saegezahn (Schalter, Standard aus): Anteil des Schritts, der geschrieben wird. */
	public static final double NORMAL_DAMPING_FACTOR = 0.5;
	/** Saegezahn: in so vielen letzten Max-logPost-Werten mindestens 2 Richtungswechsel. */
	public static final int SAWTOOTH_WINDOW = 4;

	/**
	 * reagiert auf DASSELBE Plateau-Signal wie der Trichter-Waechter oben (Klein_Method_Parameter.isStagnating(), 5
	 * Iterationen < 0.05 Aenderung) - hier jedoch mit einer ZWEITEN, unabhaengigen Reaktion: statt (nur) den Trichter
	 * zu weiten, wird der Verteilungsmodus fuer BACKOFF_NORMAL_MODE_WINDOW_DEFAULT Iterationen zwangsweise auf NORMAL
	 * umgeschaltet (nur relevant, wenn der Anwender PERCENTAGE gewaehlt hat - siehe
	 * Klein_Method_Functions.performIndividualCalibrationStep()), dann automatisch wieder auf PERCENTAGE
	 * zurueckgeschaltet. Beide Mechanismen laufen unabhaengig nebeneinander her und koennen bei derselben Stagnation
	 * gleichzeitig ausloesen - das ist beabsichtigt, aber als Designentscheidung hier festgehalten. Schwingt das Netz
	 * nach einer abgeschlossenen Beruhigungsphase IM SELBEN LAUF erneut in eine Stagnation, wird das Fenster mit
	 * BACKOFF_WINDOW_MULTIPLIER verdoppelt (5 -> 10 -> 20 -> 40), hart gedeckelt bei BACKOFF_WINDOW_MAX.
	 */
	public static final int BACKOFF_NORMAL_MODE_WINDOW_DEFAULT = 5;
	public static final double BACKOFF_WINDOW_MULTIPLIER = 2.0;
	public static final int BACKOFF_WINDOW_MAX = 50;

	/**
	 * der Backoff-Waechter (oben) feuerte in diesem 86-Iterationen-Lauf KEIN EINZIGES Mal, weil STAGNATION_EPSILON/
	 * STAGNATION_WINDOW_SIZE ein ECHTES PLATEAU verlangen (5 Iterationen mit
	 *
	 * < 0.05 Aenderung) - dieser Lauf zeigt aber ein Saegezahn-Muster ohne ein einziges Fenster dieser Art. KEIN
	 * Vorzeichenfehler und KEIN ungewollter windowCounter-Reset - die Ausloesebedingung selbst passte nur nicht zu
	 * diesem Divergenzmuster. OSCILLATION_JUMP_THRESHOLD ist der SCHWELLWERT fuer den NEUEN, davon unabhaengigen
	 * zweiten Trigger (Klein_Method_Parameter.isOscillating()): steigt der globale Max-logPost innerhalb der letzten
	 * (bis zu STAGNATION_WINDOW_SIZE) erfassten Werte um mindestens diesen Betrag gegenueber dem lokalen Minimum
	 * dieses Fensters wieder an, gilt das als Oszillation/Wiederanstieg und loest denselben Backoff aus wie eine
	 * Stagnation. 5.0 = deutlich groesser als normale Iterationsschritte im absteigenden Ast (typ. 1-3 log-Einheiten
	 * in diesem Log), aber klar innerhalb der beobachteten Saegezahn-Spruenge (typ. +10 bis +25).
	 */
	public static final double OSCILLATION_JUMP_THRESHOLD = 5.0;

	public static final double OSCILLATION_WARNING_THRESHOLD = OSCILLATION_JUMP_THRESHOLD / 2.0;
	// Sprungwerte FEST (keine Eingabefelder mehr), 2D und 3D
	// gleich: 1D 0,10 mm, 2D/3D 0,50 mm. Der Sprung gilt einschliesslich der Grenze und nur beim Verkleinern.
	public static final double POINT_UNCERTAINTY_JUMP_VALUE_1D = 0.00010; // Hoehenpunkte (sigma_z0)
	public static final double POINT_UNCERTAINTY_JUMP_VALUE_2D = 0.00050; // Lage-/Raumfestpunkte (sigma_y0/x0/z0)
	public static final double POINT_UNCERTAINTY_JUMP_VALUE_3D = 0.00050;

	/**
	 * festes Zielband der Festpunktanalyse (Phase 1: VKS F je Dimension, Phase 2: T_prio je Punkt), Ziel 1,000. Nicht
	 * editierbar - wird in denselben UI-Feldern wie das VKS-Band der freien AGL angezeigt ("VKS-Band dynamisch
	 * Min/Max"). Grenzen einschliesslich.
	 */
	public static final double DYNAMIC_BAND_MIN = 0.996;
	public static final double DYNAMIC_BAND_MAX = 1.004;
	/** Mindestfaktor je Schritt in Phase 1 und 2 der dynamischen AGL (wie Ur-Formel der freien AGL). */
	public static final double DYNAMIC_MIN_FACTOR = 0.3;
	/** W = max(T_prio; DYNAMIC_MIN_TPOST_WEIGHT) - ln(W) wird damit nie kleiner als ln(0,5). */
	public static final double DYNAMIC_MIN_TPOST_WEIGHT = 0.5;
	/** T_prio <= diesem Wert gilt als 0. */
	public static final double DYNAMIC_TPOST_ZERO = 0.000001;
	/**
	 * stufenloser Turbo-Zuschlag (Rueckfall) - Stuetzpunkte T_prio -> Turbo, linear interpoliert; T_prio <= 0,5 ->
	 * 1,5; T_prio >= 0,97 -> 1,0 (aus).
	 */
	public static final double[][] DYNAMIC_TURBO_SUPPORT_POINTS = { { 0.5, 1.5 }, { 0.7, 1.4 }, { 0.8, 1.3 }, { 0.9, 1.2 }, { 0.97, 1.1 } };
	public static final double DYNAMIC_TURBO_ADDON_LIMIT = 0.97;
	/** Turbo (Vorhersage) wirkt nur bei T_prio unter diesem Wert (= Bandunterkante). */
	public static final double DYNAMIC_TURBO_PREDICTION_LIMIT = DYNAMIC_BAND_MIN;
	/** Vorhersage-Schritt hoechstens so viel mal staerker als die normale Formel (in ln sigma). */
	public static final double DYNAMIC_TURBO_MAX_STRENGTH = 10.0;
	/** plausible gemessene Reaktion p = -dln(T)/dln(sigma) fuer die Vorhersage. */
	public static final double DYNAMIC_TURBO_REACTION_MIN = 0.02;
	public static final double DYNAMIC_TURBO_REACTION_MAX = 5.0;
	/** Ueberschwing-Sperre - Einfrierdauer nach dem 1. Richtungswechsel (Schritte). */
	public static final int DYNAMIC_TURBO_FREEZE_STEPS = 5;
	/**
	 * Stagnations-Abbruch der dynamischen AGL - verringert sich der Abstand der offenen Punkte/Gruppen zum Band
	 * (Summe |ln Wert|) ueber so viele Iterationen nicht um mindestens DYNAMIC_STAGNATION_MIN_IMPROVEMENT (relativ),
	 * endet der Lauf mit Meldung.
	 */
	public static final int DYNAMIC_STAGNATION_WINDOW = 10;
	public static final double DYNAMIC_STAGNATION_MIN_IMPROVEMENT = 0.01;
	/**
	 * keine Stagnation, solange sich sigma eines offenen Punkts/einer offenen Gruppe je Iteration noch um mindestens
	 * diesen Anteil in gleicher Richtung bewegt (z. B. Weg zum Sprungwert/zur Untergrenze).
	 */
	public static final double DYNAMIC_STAGNATION_SIGMA_MOVE = 0.02;
	/**
	 * Turbo dyn. AGL: reagiert T_prio eines Festpunkts unter dem Band auf das Verkleinern von sigma kaum (|p| unter
	 * diesem Wert, so viele Schritte in Folge), fuehrt das Verkleinern ohnehin nur zum Sprungwert -> direkt auf die
	 * Untergrenze statt in kleinen Schritten.
	 */
	public static final double DYNAMIC_INSENSITIVE_REACTION = 0.1;
	public static final int DYNAMIC_INSENSITIVE_STEPS = 3;
	/** ... und T_prio aendert sich je Schritt um weniger als diesen Anteil (|ln T - ln T_vorher|). */
	public static final double DYNAMIC_INSENSITIVE_TPRIO_CHANGE = 0.005;
	/** ... nur fuer Punkte deutlich unter dem Band (T_prio unter diesem Wert) ... */
	public static final double DYNAMIC_INSENSITIVE_TPRIO_MAX = 0.98;
	/** ... und nur bei einem deutlichen sigma-Schritt (|ln sigma - ln sigma_vorher| mindestens). */
	public static final double DYNAMIC_INSENSITIVE_MIN_SIGMA_STEP = 0.015;

	/**
	 * Intervallhalbierung der Festpunktanalyse (Phase 2): engste Klammer (sigma zu gross / sigma zu klein). Wird sie enger,
	 * ohne dass T_prio ins Band kommt, wird die Klammer verworfen und mit der normalen Formel weitergerechnet.
	 */
	public static final double DYNAMIC_BRACKET_MIN_RATIO = 1.001;

	/**
	 * Ist die Klammer ausgeschoepft und weicht T_prio um hoechstens diesen Betrag (|ln T_prio|, etwa 3 %) von 1 ab, gilt der
	 * Festpunkt als fertig: sein sigma ist bestimmt, die Restabweichung kommt aus dem uebrigen Netz.
	 */
	public static final double DYNAMIC_BRACKET_SETTLE_LN = 0.03;

	/** Mindestbereich (groesstes / kleinstes bisher ausgewertetes sigma), ueber den die Unempfindlichkeit gemessen wird. */
	public static final double DYNAMIC_BRACKET_SETTLE_MIN_RANGE = 1.5;

	/** Ein als ausgeschoepft markierter Festpunkt wird erst wieder bearbeitet, wenn |ln T_prio| diesen Wert (etwa 6 %) uebersteigt. */
	public static final double DYNAMIC_BRACKET_UNSETTLE_LN = 0.06;

	/**
	 * EP-Schwellenwerte fuer die Festpunktanalyse-Empfehlung "Phase 3, Stufe 2": ein Punkt gilt als Kandidat fuer
	 * "Varianzfreie Festpunkte (Festpunktanalyse)", wenn sein EP-Betrag UNTER diesem Wert liegt. Hoehenpunkte (1D,
	 * Nivellementszuege) haben einen ENGEREN Schwellenwert als Lage-/Raumpunkte (2D/3D).
	 */
	public static final double POINT_EP_CANDIDATE_THRESHOLD_1D_MM = 0.2;
	public static final double POINT_EP_CANDIDATE_THRESHOLD_OTHER_MM = 0.5; // 2D/3D

	/**
	 * TURBO-Funktion fuer die individuelle Festpunktkalibrierung: verstaerkt den Korrekturbetrag NUR fuer Tprio < 1,
	 *
	 * KEINE obere Kappung. Aktiv erst, wenn mindestens ⌈TURBO_ACTIVATION_FRACTION * Gesamtzahl⌉ Festpunkte bereits
	 * konvergiert sind.
	 */
	// TURBO_FACTOR_INTERCEPT/SLOPE (2,55 - 1,5*Tprio) entfallen - ersetzt durch Vorhersage + stufenlosen Zuschlag
	// (DYNAMIC_TURBO_*). Schwelle 50 % (vorher 90 %: bei wenigen Punkten erst aktiv, wenn alle fertig waren).
	public static final double TURBO_ACTIVATION_FRACTION = 0.5;

	/**
	 * Grenzwerte fuer die farbliche Hervorhebung der Zusatzparameter-Anzeige: unterhalb des Gelb-Grenzwerts normale
	 * Schriftfarbe, dazwischen gelb, ab (inkl.) dem Rot-Grenzwert rot + einmalige Hinweis-Messagebox am Ende der
	 * Berechnung. Bewertet wird jeweils der BETRAG (Vorzeichen egal).
	 */
	public static final double ADDITIONAL_PARAMETER_OFFSET_YELLOW_THRESHOLD_MM = 0.7;
	public static final double ADDITIONAL_PARAMETER_OFFSET_RED_THRESHOLD_MM = 1.2;
	public static final double ADDITIONAL_PARAMETER_SCALE_YELLOW_THRESHOLD_PPM = 15.0;
	public static final double ADDITIONAL_PARAMETER_SCALE_RED_THRESHOLD_PPM = 30.0;

	public static double getIndividualUncertaintyCeiling(Klein_Method_Threshold_Type type) {
		return type == Klein_Method_Threshold_Type.NIVELLEMENT_LOG_POST
				? INDIVIDUAL_UNCERTAINTY_CEILING_NIVELLEMENT
				: INDIVIDUAL_UNCERTAINTY_CEILING_DEFAULT;
	}

	/**
	 * liefert "sigma_Boden" der Abschluss-Kompression fuer den uebergebenen Typ, bereits in dessen nativer
	 * Arbeitseinheit (siehe getIndividualUncertaintyUnit()) - Meter fuer Strecken/Nivellement/GNSS, Gon fuer die
	 * beiden Richtungstypen. Nur fuer LOG_POST-Typen sinnvoll.
	 */
	public static double getIndividualUncertaintyCompressionFloor(Klein_Method_Threshold_Type type) {
		switch (type) {
		case NIVELLEMENT_LOG_POST:          return INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_NIVELLEMENT;
		case HORIZONTAL_DISTANCE_LOG_POST:  return INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_HORIZONTAL_DISTANCE;
		case SLOPE_DISTANCE_LOG_POST:       return INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_SLOPE_DISTANCE;
		case GNSS_BASELINE_1D_LOG_POST:
		case GNSS_BASELINE_2D_LOG_POST:
		case GNSS_BASELINE_3D_LOG_POST:     return INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_GNSS;
		case HORIZONTAL_DIRECTION_LOG_POST: return INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_DIRECTION;
		case VERTICAL_DIRECTION_LOG_POST:   return INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_ZENITH_ANGLE;
		default: throw new IllegalArgumentException("Kein Kompressions-Bodenwert fuer: " + type);
		}
	}

	/**
	 * Anzeige-/Recheneinheit der individuellen Messunsicherheit (sigma_0) -
	 * bestaetigt vom User: gon bei den beiden Richtungstypen (Horizontalrichtung,
	 * Vertikalrichtung), sonst m (Meter - NICHT mm, siehe Hinweis oben).
	 */
	public static String getIndividualUncertaintyUnit(Klein_Method_Threshold_Type type) {
		if (type == Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST
				|| type == Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST)
			return "gon";
		return "m";
	}

	/**
	 * Alle Beobachtungstypen, die Klein_Method aktuell tatsaechlich
	 * verarbeitet (siehe Klein_Method_Threshold_Type). Wird u.a. fuer die
	 * Messdaten-Ampel genutzt: enthaelt ein Projekt AKTIVE Beobachtungen
	 * eines Typs, der HIER NICHT auftaucht (z.B. GNSS-Basislinien, noch
	 * nicht implementiert), wird die Ampel auf ROT gesetzt statt faelschlich
	 * GRUEN zu zeigen, obwohl dieser Typ unberuecksichtigt bliebe. Kuenftige
	 * Beobachtungstypen, die JAG3D selbst neu einfuehrt, werden dadurch
	 * automatisch als "nicht unterstuetzt" erkannt, ohne dass diese Liste
	 * dafuer gepflegt werden muesste - nur bei ECHTER Unterstuetzung durch
	 * Klein_Method hier ergaenzen.
	 */
	public static final Set<ObservationType> SUPPORTED_OBSERVATION_TYPES = Collections.unmodifiableSet(EnumSet.of(
			ObservationType.DIRECTION,
			ObservationType.HORIZONTAL_DISTANCE,
			ObservationType.ZENITH_ANGLE,
			ObservationType.SLOPE_DISTANCE,
			ObservationType.LEVELING,
			// GNSS-Basislinien (1D/2D/3D) - JAG3D speichert jede Komponente (Delta X/Y/Z bzw. 1D-Hoehenkomponente)
			// als EIGENE skalare Beobachtung in denselben ObservationApriori/Aposteriori-Tabellen wie alle anderen
			// Typen (bestaetigt anhand des echten JAG3D-Quellcodes: GNSSBaseline/GNSSBaseline1D/2D/3D,
			// GNSSBaselineDeltaX/Y/Z... - KEINE vollbesetzte 3x3-Kovarianz-matrix auf dieser Ebene) - daher
			// symmetrisch wie die anderen skalaren Typen freigeschaltet, keine Sondermathematik noetig.
			ObservationType.GNSS1D,
			ObservationType.GNSS2D,
			ObservationType.GNSS3D
			));

	/**
	 * Untergrenze der GRUPPENunsicherheit (sigma_a/sigma_b/sigma_c) fuer die
	 * Geraeteklasse HISTORISCH - bestaetigt per OCR-Auswertung der echten
	 * JAG3D-Panels "Messunsicherheiten" (5 Screenshots, ein Wert je Typ, gilt
	 * fuer alle 3 Komponenten a/b/c gleichermassen - nur die ANGEZEIGTE
	 * EINHEIT unterscheidet sich je Komponente/Typ, siehe getSigmaAUnit()/
	 * getSigmaBUnit()/getSigmaCUnit() unten):
	 *
	 *   Horizontalrichtung: elektronisch 0.50 (mgon/mm/mm), historisch 5.00
	 *   Horizontalstrecke:  elektronisch 0.50 (mm/mm/ppm),  historisch 5.00
	 *   Vertikalrichtung:   elektronisch 0.50 (mgon/mm/mm), historisch 5.00
	 *   Schraegstrecke:     elektronisch 0.50 (mm/mm/ppm),  historisch 5.00
	 *   Nivellementszug:    elektronisch 0.10 (mm/mm/ppm),  historisch 1.00
	 *
	 * (Nivellementszug hat eigene ELEKTRONISCH+HISTORISCH-Konstanten weiter
	 * unten, da als einziger Typ auch elektronisch von GROUP_UNCERTAINTY_
	 * FLOOR_DEFAULT abweicht.)
	 */
	public static final double HISTORICAL_UNCERTAINTY_FLOOR_HORIZONTAL_DIRECTION = 5.0;
	public static final double HISTORICAL_UNCERTAINTY_FLOOR_HORIZONTAL_DISTANCE = 5.0;
	public static final double HISTORICAL_UNCERTAINTY_FLOOR_VERTICAL_DIRECTION = 5.0;
	public static final double HISTORICAL_UNCERTAINTY_FLOOR_SLOPE_DISTANCE = 5.0;

	/**
	 * Nivellementszuege sind deutlich praeziser als die anderen 4 Typen und haben deshalb - anders als bei denen -
	 * AUCH einen eigenen ELEKTRONISCH-Wert (statt des generischen GROUP_UNCERTAINTY_FLOOR_DEFAULT). Bestaetigt per
	 * OCR-Auswertung des echten JAG3D-Panels UND Vorgabe.
	 */
	public static final double HISTORICAL_UNCERTAINTY_FLOOR_NIVELLEMENT = 1.0;
	public static final double ELECTRONIC_UNCERTAINTY_FLOOR_NIVELLEMENT = 0.1;

	/**
	 * Liefert die Untergrenze der GRUPPENunsicherheit (sigma_a/sigma_b/sigma_c, "globale Werte" - das Gegenstueck zur
	 * individuellen Messunsicherheit, wird der ganzen Gruppe zugewiesen) fuer einen Beobachtungstyp UNTER
	 * BERUECKSICHTIGUNG der aktuell gewaehlten Geraeteklasse (siehe Klein_Method_Device_Class). Betrifft NICHT die
	 * individuelle Messunsicherheit (sigma_0, oh0/osh0/ov0/os0) - siehe
	 * Klein_Method_Functions.performGroupCalibrationStep().
	 *
	 * Dient nur noch als SEED/Startwert (siehe Klein_Method_Parameter-Konstruktor) - die tatsaechlich verwendeten,
	 * live editierbaren Werte liegen seit der Editierbarkeits-Erweiterung in
	 * Klein_Method_Parameter.getGroupUncertaintyFloorA()/B()/C().
	 */
	public static double getGroupUncertaintyFloor(Klein_Method_Threshold_Type type, Klein_Method_Device_Class deviceClass) {
		// GNSS-Basislinien (alle 3 Typen 1D/2D/3D) - EIGENER, von der Geraeteklasse UNABHAENGIGER Wert (siehe
		// GROUP_UNCERTAINTY_FLOOR_GNSS oben fuer die vollstaendige Herleitung/Einordnung). Muss VOR der Nivellement-
		// und der Geraeteklassen-Pruefung unten stehen, sonst wuerde GNSS weiterhin unbeabsichtigt auf
		// GROUP_UNCERTAINTY_FLOOR_DEFAULT (0.5) zurueckfallen.
		if (type == Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST
				|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST
				|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST) {
			return GROUP_UNCERTAINTY_FLOOR_GNSS;
		}

		// Nivellementszuege: EIGENE Werte fuer BEIDE Geraeteklassen (nicht nur
		// historisch), da sie inhaerent praeziser sind als die anderen Typen.
		if (type == Klein_Method_Threshold_Type.NIVELLEMENT_LOG_POST) {
			return deviceClass == Klein_Method_Device_Class.HISTORISCH
					? HISTORICAL_UNCERTAINTY_FLOOR_NIVELLEMENT
					: ELECTRONIC_UNCERTAINTY_FLOOR_NIVELLEMENT; // ELEKTRONISCH und KOMBINIERT
		}

		if (deviceClass == Klein_Method_Device_Class.HISTORISCH) {
			switch (type) {
			case HORIZONTAL_DIRECTION_LOG_POST: return HISTORICAL_UNCERTAINTY_FLOOR_HORIZONTAL_DIRECTION;
			case HORIZONTAL_DISTANCE_LOG_POST:  return HISTORICAL_UNCERTAINTY_FLOOR_HORIZONTAL_DISTANCE;
			case VERTICAL_DIRECTION_LOG_POST:   return HISTORICAL_UNCERTAINTY_FLOOR_VERTICAL_DIRECTION;
			case SLOPE_DISTANCE_LOG_POST:       return HISTORICAL_UNCERTAINTY_FLOOR_SLOPE_DISTANCE;
			default:
				break; // kein historischer Wert fuer diesen Typ definiert -> Rueckfall unten
			}
		}
		// ELEKTRONISCH, KOMBINIERT sowie der HISTORISCH-Rueckfall ohne eigenen Wert
		return GROUP_UNCERTAINTY_FLOOR_DEFAULT;
	}

	/**
	 * Anzeige-Einheit von sigma_a (additiver Anteil) - bestaetigt per
	 * OCR-Auswertung: mgon bei den beiden Winkeltypen, sonst mm.
	 */
	public static String getSigmaAUnit(Klein_Method_Threshold_Type type) {
		if (type == Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST
				|| type == Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST)
			return "mgon";
		return "mm";
	}

	/**
	 * Anzeige-Einheit von sigma_b(sqrt(d)) - bestaetigt per OCR-Auswertung:
	 * bei ALLEN 5 Typen einheitlich mm.
	 */
	public static String getSigmaBUnit(Klein_Method_Threshold_Type type) {
		return "mm";
	}

	/**
	 * Anzeige-Einheit von sigma_c(d) (entfernungsabhaengiger Anteil) - bestaetigt per OCR-Auswertung UND ppm bei den
	 * 3 Typen mit Streckencharakter (Horizontalstrecke, Schraegstrecke, Nivellementszug), mm bei den beiden
	 * Winkeltypen (Horizontalrichtung, Vertikalrichtung).
	 */
	public static String getSigmaCUnit(Klein_Method_Threshold_Type type) {
		if (type == Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST
				|| type == Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST)
			return "mm";
		return "ppm";
	}

	// --------------- Qualitaet der Gruppenkalibrierung ----------------

	/** Volles VKS-Band erst ab dieser Redundanz der Komponente; darunter Band * sqrt(R/r) aufgeweitet (max. 0,900-1,100). 0 = aus. */
	public static final double FULL_BAND_REDUNDANCY_DEFAULT = 100.0, FULL_BAND_REDUNDANCY_MIN = 5.0, FULL_BAND_REDUNDANCY_MAX = 100000.0;
	/** Unter dieser Redundanz wird eine Komponente nicht kalibriert (Wert bleibt). 0 = aus. */
	public static final double MIN_CALIBRATION_REDUNDANCY_DEFAULT = 5.0, MIN_CALIBRATION_REDUNDANCY_MAX = 100.0;
	/** Hinweis, wenn eine kalibrierte Unsicherheit mehr als Faktor x typischer Datenblattwert ist. 0 = aus. */
	public static final double PLAUSIBILITY_FACTOR_DEFAULT = 10.0, PLAUSIBILITY_FACTOR_MIN = 2.0, PLAUSIBILITY_FACTOR_MAX = 100.0;
	/** Toleranz Naeherungskoordinaten Lage/Hoehe [m] fuer die Pruefung in der freien AGL (0 = aus). */
	public static final double APPROXIMATION_TOLERANCE_DEFAULT = 0.05, APPROXIMATION_TOLERANCE_MIN = 0.005, APPROXIMATION_TOLERANCE_MAX = 5.0;
	/** Hinweis, wenn mittlere VKS / robuste VKS eines Typs diesen Wert uebersteigt (Ausreisser vermutet). 0 = aus. */
	public static final double ROBUST_RATIO_LIMIT_DEFAULT = 2.0, ROBUST_RATIO_LIMIT_MIN = 1.2, ROBUST_RATIO_LIMIT_MAX = 10.0;
	/** Mindestanzahl Beobachtungen fuer robuste VKS und Modellpruefung. */
	public static final int QUALITY_MIN_OBSERVATIONS = 10;

	/**
	 * typische Datenblattwerte moderner (elektronischer) Instrumente je Typ und Komponente in der Arbeitseinheit (a:
	 * mgon bzw. mm, b: mm, c: mm bzw. ppm), nur als Vergleich fuer die Plausibilitaetspruefung. 0 = kein
	 * Vergleichswert. Historische
	 *
	 * Geraeteklasse: x5.
	 */
	public static double getTypicalDatasheetValue(Klein_Method_Threshold_Type type, int component, Klein_Method_Device_Class deviceClass) {
		double[] abc;
		switch (type) {
		case HORIZONTAL_DIRECTION_LOG_POST:
		case VERTICAL_DIRECTION_LOG_POST:
			abc = new double[] { 0.3, 0.0, 1.0 };
			break;
		case HORIZONTAL_DISTANCE_LOG_POST:
		case SLOPE_DISTANCE_LOG_POST:
			abc = new double[] { 1.0, 0.0, 1.5 };
			break;
		case NIVELLEMENT_LOG_POST:
			abc = new double[] { 0.2, 0.3, 0.0 };
			break;
		case GNSS_BASELINE_1D_LOG_POST:
			abc = new double[] { 5.0, 0.0, 1.0 };
			break;
		case GNSS_BASELINE_2D_LOG_POST:
		case GNSS_BASELINE_3D_LOG_POST:
			abc = new double[] { 3.0, 0.0, 0.5 };
			break;
		default:
			return 0.0;
		}
		boolean historical = deviceClass == Klein_Method_Device_Class.HISTORISCH && type != Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST
				&& type != Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST && type != Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST;
		return abc[component] * (historical ? 5.0 : 1.0);
	}

	private Klein_Method_Default_Values() {
		// reine Konstanten-Klasse, keine Instanzen noetig
	}
}
// V1.00_RC3_Klein_Method_Default_Values.java
