// V1.00_RC3_Klein_Method_Database_Handler_Interface.java
package org.applied_geodesy.klein_method.database;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.applied_geodesy.adjustment.network.ObservationType;
import org.applied_geodesy.adjustment.network.PointType;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;

/**
 * Schnittstelle fuer Lesen/Schreiben der Klein_Method-Daten - implementiert
 * von der echten JAG3D-Datenbank-Anbindung (Klein_Method_Database_Handler).
 *
 * Dadurch merkt der Rest des Systems (Klein_Method_Iteration_Task,
 * Klein_Method_Functions, UI) NICHT, welche der beiden Quellen gerade
 * aktiv ist - der Testmodus laesst sich daher spaeter entfernen, ohne
 * hier etwas anzufassen.
 */
public interface Klein_Method_Database_Handler_Interface {

	void read(Klein_Method_Parameter parameter) throws SQLException;

	/**
	 * liefert das Verzeichnis, in dem die AKTUELL GEOEFFNETE HSQLDB-Projektdatenbank auf der Festplatte liegt -
	 * dynamisch ermittelt ueber die JDBC-Metadaten der bereits bestehenden Verbindung (siehe Klassenkommentar der
	 * Implementierung: KEINE eigene, zweite Connection).
	 */
	String getProjectDirectory();

	void write(Klein_Method_Parameter parameter) throws SQLException;

	/**
	 * Nur die Spanne der Gruppen-Messunsicherheiten (Anzeige "σa/σb(√d)/σc(d) aktuell") neu lesen - direkt
	 * nach dem Schreiben einer Iteration, damit die Anzeige nicht eine Iteration hinterherlaeuft.
	 */
	void refreshGroupUncertaintyRanges(Klein_Method_Parameter parameter) throws SQLException;

	/**
	 * PHASE 1 - liefert die 3 UNABHAENGIGEN a-posteriori Varianzfaktoren
	 * ("1:σ²") fuer sigma_a/sigma_b/sigma_c des Beobachtungstyps hinter dem
	 * angegebenen logPost-Grenzwert-Typ (bestaetigt per OCR-Auswertung der
	 * echten JAG3D-"Varianzkomponentenanalyse": jede der 3 Komponenten hat
	 * ihren EIGENEN Wert, nicht nur einen gemeinsamen Typ-Gesamtwert).
	 * Index 0=sigma_a, 1=sigma_b, 2=sigma_c. Wert ~1.0 bedeutet korrekt
	 * kalibriert. Fehlt fuer eine Komponente eine Zeile (z.B. weil ihre
	 * Redundanz 0 ist), wird dafuer der neutrale Wert 1.0 zurueckgegeben.
	 */
	double[] readGroupVarianceRatios(Klein_Method_Threshold_Type logPostType) throws SQLException;

	/**
	 * Redundanz und Anzahl Beobachtungen je Komponente a/b/c aus "VarianceComponent": {{r_a, n_a}, {r_b, n_b}, {r_c,
	 * n_c}}, fehlend = NaN. Null, wenn keine Datenbank offen ist.
	 */
	double[][] readVarianceComponentRedundancies(Klein_Method_Threshold_Type logPostType) throws SQLException;

	/**
	 * je aktiver Beobachtung dieses Typs {omega, Redundanz, Strecke [m], k}. Terrestrisch:
	 *
	 * k = 1, Strecke aus a-priori-Koordinaten von Stand- und Zielpunkt (NaN, wenn unbekannt).
	 *
	 * GNSS: Redundanz = Summe der Komponenten, k = Dimension (1/2/3), Strecke NaN. Reines Lesen.
	 */
	List<double[]> readObservationFitData(Klein_Method_Threshold_Type logPostType) throws SQLException;

	/**
	 * PHASE 1 - liefert alle Gruppen-IDs (aktive Beobachtungsgruppen) des
	 * Beobachtungstyps hinter dem angegebenen logPost-Grenzwert-Typ.
	 * Kann mehrere Eintraege liefern, wenn mehrere Gruppen desselben Typs
	 * existieren (z.B. mehrere "Horizontalrichtung"-Saetze).
	 */
	List<Integer> readGroupIds(Klein_Method_Threshold_Type logPostType) throws SQLException;

	/**
	 * PHASE 1 - liest σa, σb(√d), σc(d) einer einzelnen Gruppe.
	 * @return Array [σa, σb, σc]
	 */
	double[] readGroupUncertainty(int groupId) throws SQLException;

	/**
	 * PHASE 1 - schreibt σa, σb(√d), σc(d) einer einzelnen Gruppe zurueck.
	 */
	void writeGroupUncertainty(int groupId, double sigmaA, double sigmaB, double sigmaC) throws SQLException;

	// ===== Festpunktanalyse (dynamische Ausgleichung) =====

	/**
	 * 1:sigma^2 fuer eine Punkt-DIMENSION (1/2/3) - JAG3D fasst ALLE aktiven
	 * Punkte dieser Dimension in EINEN gemeinsamen VarianceComponent-Eintrag
	 * zusammen (bestaetigt anhand NetworkAdjustment.java,
	 * VarianceComponentType.getComponentTypeByPointDimension()) -
	 * unabhaengig von der Punktkategorie.
	 */
	double readPointVarianceRatio(int dimension) throws SQLException;

	/** Alle aktiven Punktgruppen vom Typ "Bewegliche Anschlusspunkte", group_id -> dimension (1/2/3). */
	Map<Integer, Integer> readStochasticPointGroupIds() throws SQLException;

	/**
	 * Liest sigma_y0/sigma_x0/sigma_z0 EINES repraesentativen (ersten
	 * aktiven) Punktes der Gruppe als Ausgangswert - Double.NaN je
	 * Komponente, wenn kein aktiver Punkt in der Gruppe existiert.
	 *
	 * @return double[3] = { sigmaY0, sigmaX0, sigmaZ0 }
	 */
	double[] readPointUncertaintySample(int groupId) throws SQLException;

	/**
	 * Schreibt sigma_y0/sigma_x0/sigma_z0 auf ALLE aktiven Punkte einer
	 * Gruppe (Sammel-Update, da JAG3D die Unsicherheit pro Punkt speichert,
	 * nicht pro Gruppe) - nur die zur Dimension passenden Komponenten
	 * werden tatsaechlich geschrieben (1D=nur Z, 2D=nur Y/X, 3D=alle).
	 */
	void writePointGroupUncertainty(int groupId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException;

	/**
	 * Gruppenunsicherheit einer Punktgruppe aus den JAG3D-Gruppeneigenschaften ("Unsicherheiten der beweglichen
	 * Anschlusspunkte", Tabelle "PointGroupUncertainty", Typ COMPONENT_Y/X/Z). Fehlt ein Eintrag, gilt wie in JAG3D
	 * der Standardwert (DefaultUncertainty).
	 *
	 * @return [0]=sigma y, [1]=sigma x, [2]=sigma z (Meter)
	 */
	double[] readPointGroupUncertainty(int groupId) throws SQLException;

	/**
	 * schreibt die Gruppenunsicherheit (Tabelle "PointGroupUncertainty", MERGE wie JAG3D selbst) - nur die zur
	 * Dimension passenden Komponenten (1D = z, 2D = y/x, 3D = y/x/z). Einzelwerte der Punkte bleiben unberuehrt.
	 */
	void writePointGroupUncertaintyValues(int groupId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException;

	/**
	 * PHASE 2 - liefert alle Beobachtungen des Beobachtungstyps hinter dem
	 * angegebenen logPost-Grenzwert-Typ, deren |logPost| aktuell im
	 * uebergebenen Bearbeitungsbereich liegt (siehe Klein_Method_Functions.
	 * computeLogPostBandLowerBound()/isWithinLogPostBand()).
	 */
	List<Klein_Method_Observation_Uncertainty_Row> readObservationsForIndividualCalibration(
			Klein_Method_Threshold_Type logPostType, double bandLowerBound, double bandUpperBound) throws SQLException;

	/**
	 * PHASE 2 - schreibt die neu berechnete individuelle Messunsicherheit
	 * (oh0/osh0/ov0/os0) einer einzelnen Beobachtung zurueck.
	 */
	void writeIndividualUncertainty(int observationId, double newValue) throws SQLException;

	/**
	 * GNSS-Pendant zu readObservationsForIndividualCalibration(): liefert PRO im Bearbeitungsbereich liegender
	 * GNSS-Basislinie DREI Zeilen (eine je Achse X/Y/Z, siehe Klein_Method_Observation_Uncertainty_Row. GnssAxis) -
	 * "distance" ist je Achse |Komponente|.
	 */
	List<Klein_Method_Observation_Uncertainty_Row> readGnssObservationsForIndividualCalibration(
			Klein_Method_Threshold_Type logPostType, double bandLowerBound, double bandUpperBound) throws SQLException;

	/**
	 * GNSS-Pendant zu writeIndividualUncertainty(): schreibt EINE Achse (sigma_x0/sigma_y0/sigma_z0) EINER
	 * GNSS-Basislinie zurueck - NIE alle drei gemeinsam, KEINE Vermischung der Achsen (siehe
	 * Klein_Method_Observation_Uncertainty_Row.GnssAxis).
	 */
	void writeGnssIndividualUncertainty(int observationId, Klein_Method_Observation_Uncertainty_Row.GnssAxis axis, double newValue) throws SQLException;

	/**
	 * harter, expliziter Datenbank-Checkpoint - wird EINMALIG am Ende von
	 * Klein_Method_Functions.performFloorEnforcementCompression() aufgerufen, NACHDEM alle
	 * Hard-Clamp-Schreibvorgaenge dieses Laufs abgeschlossen sind (nur wenn tatsaechlich mindestens einer
	 * stattgefunden hat). Nutzt bewusst exakt dieselbe, bereits etablierte Verbindung ueber
	 * SQLManager.getInstance().getDataBase().getPreparedStatement(sql) - KEINE eigene, zweite Connection (siehe
	 * Klassenkommentar oben). Setzt "COMMIT" (macht jede Transaktion dieses Laufs unwiderruflich endgueltig, auch
	 * falls die Verbindung entgegen der HSQLDB-Voreinstellung nicht im Autocommit-Modus laeuft) gefolgt von
	 * "CHECKPOINT" (erzwingt das physische Schreiben aller gepufferten Aenderungen aus dem HSQLDB-Log-Buffer auf die
	 * Festplatte). Siehe die ausfuehrliche Einordnung in Klein_Method_Functions.performFloorEnforcementCompression()
	 * dazu, WELCHES Problem dies tatsaechlich loest (Datenbank-Persistenz) und welches NICHT (JAG3Ds eigener,
	 * unabhaengiger UI-Cache).
	 */
	void commitAndCheckpoint() throws SQLException;

	/** Nutzt denselben Zugriffspfad wie commitAndCheckpoint(). */
	void commit() throws SQLException;

	/**
	 * reiner CHECKPOINT OHNE COMMIT - wird weiterhin EINMALIG ganz am Ende von performFloorEnforcementCompression()
	 * aufgerufen, NACHDEM alle (jetzt bereits einzeln committeten) Hard-Clamp-Schreibvorgaenge dieses Laufs
	 * abgeschlossen sind.
	 */
	void checkpoint() throws SQLException;

	/**
	 * schaltet EINE GNSS-Basislinie komplett auf "enable" = false - genutzt in Phase 2 (Prozentual-Modus), wenn die
	 * berechneten neuen sigma_x0/y0/z0-Werte dieser Basislinie eine harte Obergrenze ueberschreiten oder eine andere
	 * Achse dadurch unter eine numerische Untergrenze gedrueckt wuerde (siehe
	 * Klein_Method_Default_Values.GNSS_AXIS_OUTLIER_CEILING_MM/_FLOOR_MM und
	 * Klein_Method_Functions.performIndividualCalibrationStep()) - verhindert numerisch unendliche Gewichte (sigma ->
	 * 0) im naechsten Ausgleichungs-durchlauf, OHNE (wie die bestehende globale Obergrenzenpruefung,
	 * isIndividualUncertaintyCeilingExceeded()) die GESAMTE Berechnung abzubrechen - nur die eine gestoerte
	 * Basislinie wird deaktiviert, alle anderen laufen unveraendert weiter.
	 */
	void deactivateGnssObservation(int observationId) throws SQLException;

	/** Ids der aktiven GNSS-Basislinien eines Punkts (Start- oder Endpunkt) - fuer die Empfindlichkeit der GNSS-Stuetzung. */
	List<Integer> readActiveGnssObservationIds(String pointName) throws SQLException;

	/** GNSS-Basislinie ein-/ausschalten ("enable") - Empfindlichkeit der GNSS-Stuetzung (danach wieder eingeschaltet). */
	void setGnssObservationEnabled(int observationId, boolean enabled) throws SQLException;

	/** terrestrische Beobachtung ein-/ausschalten ("enable") - Obergrenze der individuellen Messunsicherheit erreicht. */
	void setTerrestrialObservationEnabled(int observationId, boolean enabled) throws SQLException;

	/** Namen aktiver Punkte, die an aktiven terrestrischen Beobachtungen beteiligt sind und keine Naeherungskoordinaten haben (y0 = x0 = 0). */
	List<String> readObservedPointsWithoutApproximation() throws SQLException;

	/**
	 * Prognose je Punkt aus der letzten Ausgleichung (Neupunkte, Lage 2D/3D): {Name, grosse Halbachse der
	 * Konfidenzellipse [m], sigma_z [m], y, x, z, Anzahl aktiver GNSS-Basislinien}.
	 */
	List<Object[]> readPointPrognosis() throws SQLException;

	/** ausgeglichene Koordinaten {y, x, z} eines Punkts (null, wenn nicht vorhanden). */
	double[] readAdjustedPoint(String pointName) throws SQLException;

	/** GNSS-Stationen nach Anzahl aktiver Basislinien (meistgenutzte zuerst, hoechstens limit): {Name, Anzahl, Gruppe}. */
	List<Object[]> readGnssStationUsage(int limit) throws SQLException;

	/** Median der Genauigkeiten aktiver GNSS-Basislinien {sigma_y0, sigma_z0} [m] (0 = Gruppenvorgabe). */
	double[] readActiveGnssSigmaMedian() throws SQLException;

	/** aktive GNSS-Basislinien (Beobachtung und Gruppe aktiv) fuer die Vorpruefung: {Id, Start, Ziel, dy, dx, dz}. */
	List<Object[]> readActiveGnssVectors() throws SQLException;

	/**
	 * aktive Horizontal-/Schraegstrecken und Nivellements fuer den Vergleich von Doppelmessungen:
	 * {Id, Typ-Id, Start, Ziel, Wert, Instrumentenhoehe, Reflektorhoehe}.
	 */
	List<Object[]> readActiveRepeatableObservations() throws SQLException;

	/** alle Punkte mit Koordinaten {Name, y, x, z} (ausgeglichen, ersatzweise Naeherung) - fuer die Ursachensuche. */
	List<Object[]> readAllPointCoordinates() throws SQLException;

	/** aktive terrestrische Beobachtungen: {Id, Typ-Id, Start, Ziel, Instrumentenhoehe, Zielhoehe, Wert, Strecke}. */
	List<Object[]> readActiveTerrestrialObservations() throws SQLException;

	/** Datenstand fuer den Importbericht (alle Punkte und terrestrischen Beobachtungen, auch deaktivierte): Anzahlen und Summe der Hoehen - GNSS-Mittelbildung zaehlt nicht. */
	String readImportFingerprint() throws SQLException;

	/** Anzahl aktiver GNSS-Basislinien (1D/2D/3D, Beobachtung und Gruppe aktiv). */
	int countActiveGnssBaselines() throws SQLException;

	/** aktive Schraegstrecken und Zenitwinkel: {Id, Typ-Id, Start, Ziel, Instrumentenhoehe, Zielhoehe}. */
	List<Object[]> readActiveTargetHeights() throws SQLException;

	/** Instrumenten- und Zielhoehe einer terrestrischen Beobachtung (auch deaktiviert) {ih, th} oder null. */
	double[] readObservationHeights(int observationId) throws SQLException;

	/** Vektor {dy, dx, dz} einer GNSS-Basislinie (auch deaktiviert) oder null. */
	double[] readGnssVector(int observationId) throws SQLException;

	/** ausgeglichene Koordinaten {y, x, z} eines Punkts, ersatzweise die Naeherung; null = unbekannt. */
	double[] readPointCoordinates(String name) throws SQLException;

	/** virtuelle GNSS-Basislinie fuer die Messplanung anlegen (aktiv); liefert die Id. */
	int insertVirtualGnssBaseline(int groupId, String start, String end, double[] vector, double[] sigma) throws SQLException;

	/** GNSS-Basislinie loeschen (virtuelle Basislinie der Messplanung). */
	void deleteGnssObservation(int observationId) throws SQLException;

	/** Sitzungsmittel der Zusammenfassung {Start, Ziel, sigma_y0, sigma_z0, aktiv} - leer ohne Zusammenfassung. */
	List<Object[]> readGnssSessionMeans() throws SQLException;

	/**
	 * GNSS-Sitzungen zusammenfassen: je Sitzung (aufeinanderfolgende aktive 3D-Basislinien desselben Punktpaars in derselben
	 * Gruppe, mind. 2 Epochen) eine mittlere Basislinie anlegen (Genauigkeit = Mittel der Epochen-Genauigkeiten, ohne
	 * Wurzel-n-Verkleinerung - die Epochen sind korreliert) und die Einzelepochen deaktivieren. VERAENDERT das Projekt,
	 * vollstaendig zuruecknehmbar. Vorher Sprung-/Driftpruefung je Sitzung: bei einem Sprung wird die Sitzung geteilt (je
	 * Teil ein Mittel), driftende Sitzungen erhalten ein Mittel mit Genauigkeit = halbe Drift (wiegen damit wie eine
	 * unsichere Sitzung). Liefert {mittlere Basislinien, deaktivierte Epochen, geteilte Sitzungen, driftende Sitzungen}; Befunde siehe
	 * getLastGnssMergeFindings().
	 */
	int[] mergeGnssSessions() throws SQLException;

	/** Befunde (Sprung/Drift je Sitzung, deutsch) des letzten mergeGnssSessions(). */
	List<String> getLastGnssMergeFindings();

	/** Fest-/Datums-/Anschlusspunkte (2D/3D) bei 0/0/0: {id, Name, Dimension, Punktart-Id, 1 = GNSS-Endpunkt}. */
	List<Object[]> readZeroFixedPoints() throws SQLException;

	/** Festpunkte 0/0/0 in Neupunkte umwandeln; Anzahl. */
	int repairZeroFixedPoints() throws SQLException;

	/** Umwandlung der Festpunkte 0/0/0 zuruecknehmen; Anzahl. */
	int undoZeroFixedPoints() throws SQLException;

	/** Anzahl umgewandelter Festpunkte 0/0/0 (Ruecknahme moeglich). */
	int countRepairedZeroFixedPoints() throws SQLException;

	/** Zustandsprotokoll des Projekts: Eintrag anhaengen. */
	void appendStateLog(String text) throws SQLException;

	/** Zustandsprotokoll: letzte Eintraege, aelteste zuerst. */
	List<String> readStateLog(int limit) throws SQLException;

	/** {2D/3D-Punkte geozentrisch, UTM mit Zonenkennung, lokal/projiziert, 0/0}. */
	int[] readCoordinateKindCounts() throws SQLException;

	/** nach der Projekt-Umrechnung nicht von Klein_Method umgerechnete lokale Zeilen: {Festpunkte, Neupunkte, Basislinien}. */
	List<String>[] readForeignLocalRows() throws SQLException;

	/** Ausgangsgenauigkeiten merken (nur noch nicht gemerkte Beobachtungen). */
	int captureOriginSigmas() throws SQLException;

	/** aktuelle Genauigkeiten aller Beobachtungen (Schluessel kind * 2^32 + id). */
	Map<Long, double[]> readCurrentSigmas() throws SQLException;

	/** Genauigkeiten zurueckschreiben. */
	void writeSigmas(Map<Long, double[]> sigmas) throws SQLException;

	/** Ausgangsgenauigkeiten einsetzen. */
	int applyOriginSigmas() throws SQLException;

	/** Befunde des statistischen Tests und Gruppengenauigkeiten speichern. */
	void storeStatisticalSnapshot(List<Max_Error_Row> rows, String stamp) throws SQLException;

	/** Stand fuer den statistischen Test merken (Gruppengenauigkeiten nach der Gruppenkalibrierung), alte Befunde loeschen. */
	void storeStatisticalState() throws SQLException;

	/** Ist ein Stand fuer den statistischen Test gemerkt? */
	boolean hasStatisticalState() throws SQLException;

	/** alle Gruppengenauigkeiten {Gruppe, Komponente, Wert}. */
	List<double[]> readAllGroupUncertainties() throws SQLException;

	/** Gruppengenauigkeiten zurueckschreiben (aus readAllGroupUncertainties). */
	void writeAllGroupUncertainties(List<double[]> values) throws SQLException;

	/** Einzelgenauigkeiten aller Beobachtungen merken: {0, id, sigma_0} terrestrisch, {1, id, sigma_y0, sigma_x0, sigma_z0} GNSS. */
	List<double[]> readAllObservationSigmas() throws SQLException;

	/** Einzelgenauigkeiten zurueckschreiben (aus readAllObservationSigmas). */
	void writeAllObservationSigmas(List<double[]> values) throws SQLException;

	/** gespeicherte Befunde des statistischen Tests. */
	List<Object[]> readStatisticalSnapshot() throws SQLException;

	/** Genauigkeiten auf den Stand des Tests setzen. */
	int restoreStatisticalState() throws SQLException;

	/** Befunde loeschen. */
	void clearStatisticalSnapshot() throws SQLException;

	/** Feld einer Beobachtung lesen (Liste auffaelliger Beobachtungen). */
	double readObservationField(int kind, int id, String field) throws SQLException;

	/** Feld einer Beobachtung schreiben und die Aenderung merken. */
	void editObservationField(int kind, int id, String field, double value, String stamp) throws SQLException;

	/** gemerkte Aenderungen (kind < 0 = alle). */
	List<Object[]> readFindingEdits(int kind, int id) throws SQLException;

	/** gemerkte Aenderungen zuruecknehmen (kind < 0 = alle). */
	int undoFindingEdits(int kind, int id) throws SQLException;

	/** Gesamt-Omega und Gesamtredundanz der letzten Ausgleichung (VarianceComponent GLOBAL); NaN, wenn nicht vorhanden. */
	double[] readGlobalOmega() throws SQLException;

	/** Massen-Basislinien: GNSS-Wiederholungen je Punktpaar statt nur direkt aufeinanderfolgend gruppieren, mit robustem Mittel. */
	void setRepetitionByPair(boolean byPair);

	/** Punkte umbenennen: alle Punkte {id, Name, Punktart-Id, Dimension, y0, x0, z0, y, x, z (NaN ohne Ergebnis)}. */
	List<Object[]> readRenamePoints() throws SQLException;

	/** Punkte umbenennen: Anzahl Beobachtungen je Punktname (Start oder Ziel, terrestrisch und GNSS). */
	Map<String, Integer> readObservationNameCounts() throws SQLException;

	/**
	 * Punkte umbenennen (alles gemerkt, exakt ruecknehmbar): Punkte loeschen (zusammengefuehrt), Punkte umbenennen,
	 * Naeherungskoordinaten setzen {y0, x0, z0}, Punktnamen in Beobachtungen umbenennen. Ergebnis {Punkte umbenannt,
	 * Punkte geloescht, Beobachtungen geaendert, uebersprungen}.
	 */
	int[] applyRenames(Map<String, String> observationRenames, Map<Integer, String> pointRenames, List<Integer> deletePointIds, Map<Integer, double[]> apriori,
			String batch) throws SQLException;

	/** letzte Umbenennung zuruecknehmen: {Aenderungen zurueckgesetzt, Punkte wiederhergestellt}; Kennung der Umbenennung in lastRenameBatch(). */
	int[] undoLastRename() throws SQLException;

	/** Kennung (Zeitpunkt) der letzten noch nicht zurueckgenommenen Umbenennung, null wenn keine. */
	String lastRenameBatch() throws SQLException;

	/** GNSS-Basislinien in aktiven Gruppen: {gesamt (auch deaktivierte), aktiv, Punktpaare}. */
	int[] readGnssMassStatistics() throws SQLException;

	/** Gewichtsverhaeltnis je Messart (letzte Ausgleichung, aktive Beobachtungen): {Typ-Id, Komponenten, Summe Redundanzanteile, Summe Omega}. */
	List<double[]> readWeightBalance() throws SQLException;

	/**
	 * Schlussangleichung: alle Genauigkeiten einer Messart (individuelle sigma_0 bzw. GNSS-Achsen > 0 der aktiven
	 * Beobachtungen und die Gruppenunsicherheiten der aktiven Gruppen) mit dem Faktor multiplizieren; Anzahl geaenderter Werte.
	 */
	int scaleObservationTypeUncertainties(int observationType, double factor, double[] floors) throws SQLException;

	/** aktive terrestrische Beobachtungen fuer Gruppentests: {id, Typ-Id, Start, Ziel, omega, Redundanzanteil, |ln p| (Einzeltest)}. */
	List<Object[]> readGroupTestObservations() throws SQLException;

	/** Feld einer terrestrischen Beobachtung ohne Merken setzen (Gruppentest-Versuchsrechnung; NaN = NULL). */
	void setObservationFieldQuiet(int id, String field, double value) throws SQLException;

	/** Ergebnisse der Gruppentests speichern (ersetzt die alten): je Zeile {Art, Name, Feld, Mitglieder "id,id", Anzahl, Schaetzung, T, |ln p|, MDB, signifikant, Hinweis}. */
	void storeGroupTests(List<Object[]> rows, String stamp) throws SQLException;

	/** gespeicherte Gruppentests: {Nr, Art, Name, Feld, Mitglieder, Anzahl, Schaetzung, T, |ln p|, MDB, signifikant, Zeitpunkt, Hinweis}. */
	List<Object[]> readGroupTests() throws SQLException;

	/** "Hoehe nur aus Hoehenfestpunkten (1D)": Festpunktanalyse bewertet bei 3D-Punkten nur die Lage. */
	void setHorizontalOnly3D(boolean active);

	List<String> calibrateSeparateHeights() throws SQLException;

	List<Object[]> readStochasticPointSigmas() throws SQLException;

	/** Ende der dynamischen AGL: fehlende Einzelwerte beweglicher Anschlusspunkte mit dem wirksamen Gruppenwert ergaenzen. Protokollzeilen. */
	List<String> fillMissingStochasticPointSigmas() throws SQLException;

	List<String[]> readInflatedObservations(double factor) throws SQLException;

	boolean isHorizontalOnly3D();

	/** angewendete Stufe von "Hoehe nur aus 1D": 2 dynamisch, 3 fix, 0 keine. */
	int appliedHeightDatumStage() throws SQLException;

	/** {Hoehenfestpunkte 1D, davon mit Nivellement, davon varianzfrei, 3D-Fest-/Anschlusspunkte}. */
	int[] readHeightDatumCounts() throws SQLException;

	/** "Hoehe nur aus 1D" fuer die Stufe anwenden: {behandelte 3D-Punkte, angelegte Gruppe oder -1}. */
	int[] applyHeightDatum(org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step step) throws SQLException;

	/** "Hoehe nur aus 1D" exakt zuruecknehmen; Anzahl Punkte. */
	int undoHeightDatum() throws SQLException;

	/** {Wiederholungsgruppen mit mehr als einer Messung, Messungen darin} der noch nicht behandelten Wiederholungen. */
	int[] countRepetitions(ObservationType type) throws SQLException;

	/** angewendete Behandlung wiederholter Messungen: 0 keine, 1 zusammengefasst, 2 regewichtet. */
	int appliedRepetitionMode(ObservationType type) throws SQLException;

	/** Wiederholungen behandeln (mode 1 zusammenfassen, 2 regewichten): {Gruppen, Messungen, geteilt, driftend, mittlerer Faktor, Gruppen mit Streuung als Genauigkeit}. */
	double[] applyRepetitionHandling(ObservationType type, int mode) throws SQLException;

	/** Behandlung zuruecknehmen: {Mittel geloescht, Einzelwerte aktiviert, Sigmas wiederhergestellt}. */
	int[] undoRepetitionHandling(ObservationType type) throws SQLException;

	/** Befunde (Sprung/Drift, deutsch) des letzten applyRepetitionHandling(). */
	List<String> getLastRepetitionFindings();

	/** true = die Messart hat regewichtete, von der Kalibrierung ausgenommene Beobachtungen. */
	boolean hasReweightedRepetitions(Klein_Method_Threshold_Type logPostType) throws SQLException;

	/** Mittel mit eigener Genauigkeit (zusammenfassen und kalibrieren): {kind, id, s0, s1, s2, omega, Redundanzanteil}. */
	List<double[]> readCalibratedRepetitionMeans(Klein_Method_Threshold_Type logPostType) throws SQLException;

	/** Genauigkeit eines kalibrierten Mittels schreiben. */
	void writeCalibratedRepetitionMean(int kind, int id, double[] sigma) throws SQLException;

	/** {aktive Beobachtungen, davon regewichtet, Summe Redundanzanteile, davon regewichtet}. */
	double[] readRepetitionStatistics(ObservationType type) throws SQLException;

	/** Zusammenfassung zuruecknehmen: Mittel loeschen, Einzelepochen wieder aktivieren. Liefert {geloeschte Mittel, aktivierte Epochen}. */
	int[] undoGnssSessionMerge() throws SQLException;

	/** Anzahl Sitzungen mit mind. 2 aktiven Epochen (noch zusammenfassbar). */
	int countMergeableGnssSessions() throws SQLException;

	/** true, wenn das Projekt zusammengefasste GNSS-Sitzungen enthaelt (Ruecknahme moeglich). */
	boolean hasGnssSessionMerge() throws SQLException;

	/**
	 * liest die Anzahl der aktuell auf enable=false stehenden GNSS-Basislinien sowie die Gesamtzahl aller
	 * GNSS-Basislinien (unabhaengig vom enable-Status) im geladenen Projekt und schreibt beide Werte in den
	 * uebergebenen Parameter (Klein_Method_Parameter.setDisabledGnssBaselineCount()/ setTotalGnssBaselineCount()) -
	 * fuer das Live-Label "Deaktivierte BL: X / Y" in Klein_Method_Tab_Builder. Wird im bestehenden
	 * Hintergrund-Lesezyklus read(Klein_Method_Parameter) aufgerufen, NICHT auf dem JavaFX-UI-Thread.
	 */
	void readGnssBaselineDeactivationCounts(Klein_Method_Parameter parameter) throws SQLException;

	/**
	 * Liefert alle Beobachtungstypen, die im aktuell geladenen Projekt
	 * TATSAECHLICH aktiv vorkommen (mindestens eine aktive Beobachtung in
	 * einer aktiven Gruppe) - unabhaengig davon, ob Klein_Method diesen Typ
	 * unterstuetzt. Siehe Klein_Method_Default_Values.SUPPORTED_OBSERVATION_TYPES
	 * fuer den Abgleich (Messdaten-Ampel).
	 */
	Set<ObservationType> readActiveObservationTypes() throws SQLException;

	/**
	 * Diese Methode prueft STATTDESSEN direkt "GNSSObservationApriori" (mit denselben enable=TRUE-Bedingungen auf
	 * Gruppe UND Beobachtung wie ueberall sonst in Klein_Method - siehe SQL_GNSS_OBSERVATIONS_IN_BAND) - unabhaengig
	 * von readActiveObservationTypes().
	 */
	boolean hasActiveGnssObservations() throws SQLException;

	/** globaler a-posteriori Varianzfaktor der letzten Ausgleichung (VarianceComponent GLOBAL), NaN = unbekannt. */
	double readGlobalVarianceFactor() throws SQLException;

	/**
	 * Liefert die Gesamtzahl der Beobachtungen im aktuell geladenen Projekt -
	 * dient als Datenbankgroessen-ERSATZ fuer Klein_Method_Guardian ("Waechter"),
	 * da eine echte Dateigroesse ohne bekannten Projektordner-Pfad nicht
	 * messbar ist (siehe Klein_Method_Protocol fuer dieselbe Einschraenkung).
	 */
	long readObservationCount() throws SQLException;

	/**
	 * Liefert alle Punkt-Kategorien (PointType), die im aktuell geladenen
	 * Projekt AKTIV verwendet werden (mindestens eine aktive Punktgruppe
	 * dieses Typs) - Grundlage fuer Klein_Method.getCurrentAdjustmentStep()
	 * (erkennt anhand dieser Typen, welcher der 3 Ausgleichungsschritte
	 * gerade aktiv ist).
	 */
	Set<PointType> readActivePointTypes() throws SQLException;

	/**
	 * Berechnet eine Pruefsumme (SHA-256) ueber ALLE aktuell relevanten Konfigurationswerte des Projekts, die das
	 * Ausgleichungsmodell
	 *
	 * bestimmen: Gruppenunsicherheit σa/σb/σc ALLER aktiven Gruppen, individuelle Messunsicherheit sigma_0 ALLER
	 * aktiven Beobachtungen, Aktivierungsstatus ALLER Zusatzparameter (z.B. "Additionskonstante
	 * berechnen"/Ma\u00DFstab je Gruppe) sowie Aktivierungsstatus ALLER Punktgruppen - jeweils in fester Reihenfolge.
	 * Aendert sich EIN einziger dieser Werte irgendwo (egal ob durch Klein_Method oder ausserhalb direkt in JAG3D
	 * veraendert), aendert sich auch die Pruefsumme - Grundlage fuer die Erkennung, ob ein bereits abgeschlossener
	 * Ausgleichungsschritt (frei/dynamisch/fix) seither noch gueltig ist (siehe
	 * Klein_Method.enforceAdjustmentStepOrder()).
	 */
	String computeCurrentStepChecksum() throws SQLException;

	/**
	 * Lesbarer Name des aktuell eingestellten Koordinatensystems
	 * ("ReductionDefinition"."projection_type") - "unbekannt" falls nicht
	 * ermittelbar.
	 */
	String readProjectionTypeDisplayName() throws SQLException;

	/**
	 * Speichert die Pruefsumme fuer den angegebenen Ausgleichungsschritt in der PROJEKTDATENBANK selbst (eigene
	 * Tabelle "KleinMethodStepChecksum", wird bei Bedarf automatisch angelegt) - reist dadurch automatisch mit dem
	 * Projekt mit, unabhaengig vom Projektordner-Pfad.
	 */
	void storeStepChecksum(Klein_Method_Adjustment_Step step, String checksum) throws SQLException;

	/** Liegt in der Projektdatenbank und reist mit dem Projekt mit. */
	void storeCompletedAutomaticStep(Klein_Method_Adjustment_Step step) throws SQLException;

	/** liest die gespeicherte Stufe der zuletzt fertigen Automatik - null, wenn keine Sperre besteht. */
	Klein_Method_Adjustment_Step readCompletedAutomaticStep() throws SQLException;

	/**
	 * Mittelwert der ausgeglichenen Punktkoordinaten (Tabelle "PointAposteriori") in JAG3D-Achsen - {x, y, z,
	 * Anzahl}. Anzahl 0, wenn keine Ergebnisse vorliegen.
	 */
	double[] readMeanAposterioriPointCoordinates() throws SQLException;

	/**
	 * Mittelpunkt jeder aktiven GNSS-Basislinie aus den ausgeglichenen Koordinaten von Start- und Endpunkt
	 * (JAG3D-Achsen x, y, z). Schluessel = Beobachtungs-id. Basislinien, deren Punkte keine ausgeglichenen
	 * Koordinaten haben, fehlen in der Map.
	 */
	Map<Integer, double[]> readGnssBaselineMidpoints() throws SQLException;

	String readStoredStepChecksum(Klein_Method_Adjustment_Step step) throws SQLException;

	/**
	 * EIN einzelner, schrittUNabhaengiger "letzte eigene Pruefung"-Basiswert statt eines historischen Snapshots je
	 * Ausgleichungsschritt - siehe Klein_Method_Database_Handler fuer die Begruendung.
	 */
	void storeLastVerifiedChecksum(String checksum) throws SQLException;

	String readLastVerifiedChecksum() throws SQLException;

	/**
	 * Liest die "Zusatzparameter" (Tab "Zusatzparameter" in JAG3D, Tabelle "AdditionalParameterApriori") fuer
	 * Horizontalstrecke/Schr\u00E4gstrecke: Nullpunktabweichung a und Ma\u00DFstab m. Laut Vorgabe kann nur EINE
	 * Gruppe je Typ diese Parameter aktiv haben ("enable"=true) - wird genau diese gesucht und ihr Rohwert (aus
	 * "value_0") zurueckgegeben.
	 *
	 * @return double[2] = { Nullpunktabweichung (Meter, Rohwert), Massstab (unitless, Rohwert) } - Double.NaN, wenn
	 * fuer diesen Parameter keine aktive Gruppe gefunden wurde ("k.A.").
	 */
	double[] readAdditionalParameters(Klein_Method_Threshold_Type distanceType) throws SQLException;

	/**
	 * Liefert fuer JEDE aktive Beobachtungsgruppe des angegebenen Streckentyps den Aktivierungsstatus ("enable") von
	 * Nullpunktabweichung UND Ma\u00DFstab - anders als readAdditionalParameters() (die NUR die eine "aktive" Gruppe
	 * sucht) hier IMMER ALLE Gruppen, unabhaengig vom enable-Status, damit Klein_Method erkennen kann, ob es
	 * ueberhaupt nur eine Gruppe gibt bzw. ob mehrere Gruppen einen INKONSISTENTEN Status haben.
	 *
	 * @return Map group_id -\u003E boolean[2] = { Nullpunktabweichung aktiv?, Ma\u00DFstab aktiv? }
	 */
	Map<Integer, boolean[]> readAdditionalParameterGroupStates(Klein_Method_Threshold_Type distanceType) throws SQLException;

	/**
	 * Setzt den Aktivierungsstatus ("enable") von Nullpunktabweichung ODER
	 * Ma\u00DFstab (siehe isScale) fuer EINE bestimmte Beobachtungsgruppe.
	 * Nimmt an, dass fuer jede Gruppe bereits eine Zeile in
	 * "AdditionalParameterApriori" fuer beide Zusatzparameter-Typen existiert
	 * (von JAG3D selbst beim Anlegen der Gruppe erzeugt) - reines UPDATE,
	 * kein MERGE, um "value_0" NICHT versehentlich zu ueberschreiben.
	 */
	void writeAdditionalParameterEnabled(int groupId, boolean isScale, boolean enabled) throws SQLException;

	/**
	 * Liest ALLE aktiven Beobachtungen eines Typs fuer die EP-Einzelschritt-
	 * Kalibrierung (siehe Klein_Method_Functions.performEpSingleStep()) -
	 * bewusst OHNE Bereichsfilter (anders als readObservationsForIndividualCalibration()),
	 * da die Beobachtung mit dem GROESSTEN EP-Wert ueber ALLE Typen hinweg
	 * gesucht wird, nicht nur innerhalb eines logPost-Bearbeitungsbereichs.
	 */
	List<Klein_Method_Ep_Row> readAllObservationsForEpCalibration(Klein_Method_Threshold_Type type) throws SQLException;

	/**
	 * GNSS-Pendant zu readAllObservationsForEpCalibration() - liefert PRO aktiver GNSS-Basislinie DREI Zeilen (X/Y/Z,
	 * siehe Klein_Method_Ep_Row.getAxis()), ebenfalls OHNE Bereichsfilter (volle Liste, analog zum generischen
	 * Pendant). Grund fuer eine EIGENE Methode statt eines Umbaus der
	 *
	 * bestehenden: GNSS-Basislinien liegen strukturell in eigenen Tabellen statt in
	 * "ObservationApriori"/"ObservationAposteriori" - eine generische Abfrage liefert fuer GNSS-Typen strukturell
	 * IMMER 0 Zeilen (kein Fehler, einfach die falsche Tabelle).
	 */
	List<Klein_Method_Ep_Row> readAllGnssObservationsForEpCalibration(Klein_Method_Threshold_Type type) throws SQLException;

	/**
	 * Liest die Ergebniskoordinaten (a-posteriori, Tab "Ergebnis" in JAG3D)
	 * ALLER aktiven Projektpunkte (alle Punktkategorien/Dimensionen
	 * gemeinsam) - fuer den Koordinaten-Export (Klein_Method.exportCoordinates()).
	 */
	List<Klein_Method_Point_Coordinate_Row> readAllActivePointCoordinates() throws SQLException;

	/**
	 * wie readAllActivePointCoordinates(), aber A-PRIORI ("y0"/"x0"/"z0") statt A-POSTERIORI - fuer den Vor-Scan VOR
	 * dem ersten Ausgleichungslauf, siehe Klein_Method_Functions.scanForFangkreisViolations().
	 */
	List<Klein_Method_Point_Coordinate_Row> readAllPointAprioriCoordinatesForFangkreisScan() throws SQLException;

	/**
	 * alle aktiven Punkte mit Art (Neupunkt / beweglicher Anschlusspunkt / Fest- oder Datumspunkt), Dimension,
	 * a-priori-Koordinaten [m], individueller Unsicherheit [mm] (1D: sigma_z0, sonst sigma_y0 - je Punkt einheitlich)
	 * und ob der Punkt in einem aktiven Nivellementszug vorkommt. Reines Lesen.
	 */
	List<org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> readFixedPointRecommendationPoints()
			throws SQLException;

	/** Lage aller Punkte aus der letzten freien Ausgleichung speichern (Name, y, x) - fuer die Kongruenzpruefung. */
	void storeFreeSolution(List<Klein_Method_Point_Coordinate_Row> rows) throws SQLException;

	/** gespeicherte Lage aus der freien Ausgleichung: Name -> {y, x}; leer, wenn keine vorhanden. */
	java.util.Map<String, double[]> readFreeSolution() throws SQLException;

	/** aktive Punkte fuer die Naeherungskoordinaten (Neupunkt ja/nein, Dimension, vorhandene y0/x0/z0). */
	List<org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Approximation.Point> readApproximationPoints() throws SQLException;

	/** aktive terrestrische und GNSS-Beobachtungen fuer die Naeherungskoordinaten. */
	List<org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Approximation.Observation> readApproximationObservations() throws SQLException;

	/** Erdkruemmungsreduktion eingeschaltet? {1 = ja/0 = nein, Erdradius [m]} */
	double[] readEarthCurvatureSetting() throws SQLException;

	int writeApproximations(List<org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Approximation.PointResult> points, String stamp)
			throws SQLException;

	/** letzte Uebernahme zuruecknehmen. @return Anzahl Punkte */
	int restoreApproximationBackup() throws SQLException;

	/** {Zeitpunkt, Anzahl} der gesicherten Uebernahme oder null. */
	String[] readApproximationBackupInfo() throws SQLException;

	/** alle Punkte {id, Dimension, y0, x0, z0} fuer die Umrechnung WGS84 -> lokal. */
	List<double[]> readLocalConversionPoints() throws SQLException;

	/** alle GNSS-Basislinien {id, Typ, y0, x0, z0, Startpunkt-id, Zielpunkt-id, aktiv 1/0, Gruppen-id}. */
	List<double[]> readLocalConversionBaselines() throws SQLException;

	/** Anzahl aktiver Zenitwinkel und Nivellements (Hinweis "lokal ellipsoidisch"). */
	int readZenithAndLevelingCount() throws SQLException;

	int[] applyLocalConversion(Map<Integer, double[]> points, Map<Integer, double[]> baselines, String stamp) throws SQLException;

	/** Umrechnung zuruecknehmen. @return Anzahl Punkte */
	int restoreLocalConversion() throws SQLException;

	/** {Zeitpunkt, Anzahl Punkte, Anzahl Basislinien} der Sicherung oder null. */
	String[] readLocalConversionBackupInfo() throws SQLException;

	/**
	 * Bezugspunkt (Hauptpunkt) aus JAG3D "Projektion und Reduktion": {x0 (Nord), y0 (Ost), z0, Breite [rad], Laenge
	 * [rad], Hoehe, grosse Halbachse, kleine Halbachse} oder null ohne Projekt.
	 */
	double[] readPrincipalPoint() throws SQLException;

	/** Bezugspunkt (Hauptpunkt) schreiben, Werte wie readPrincipalPoint(). */
	void writePrincipalPoint(double[] values) throws SQLException;

	/** gespeicherte freie Loesung loeschen (Koordinatensystem gewechselt). */
	void deleteFreeSolution() throws SQLException;

	/**
	 * Nachlauf auf den Grenzwert: aktuellen Stand aller von Klein_Method geschriebenen Werte sichern (Gruppenunsicherheiten,
	 * sigma_0 je Beobachtung, GNSS sigma je Achse, Haken aktiv, Zusatzparameter-Haken) mit Max-logPost, Durchlauf, Iteration.
	 */
	void storeBestState(double maxLogPost, int pass, int iteration) throws SQLException;

	/** gesicherten Stand zurueckschreiben; Ergebnis: Anzahl Beobachtungen, GNSS-Basislinien, Gruppenwerte, Zusatzparameter. */
	int[] restoreBestState() throws SQLException;

	/** gesicherter Stand: {Max-logPost, Durchlauf, Iteration} oder null, wenn keiner vorhanden ist. */
	double[] readBestStateInfo() throws SQLException;

	void deleteBestState() throws SQLException;

	/** lokale Koordinaten/Basislinien ersetzen (P0 verlegt) - ohne die Sicherung der ersten Umrechnung zu aendern. */
	int[] updateLocalCoordinates(Map<Integer, double[]> points, Map<Integer, double[]> baselines) throws SQLException;

	/**
	 * nachimportierte geozentrische Werte (Punkte/Basislinien, die noch nicht in der Sicherung der ersten Umrechnung
	 * stehen) vor dem Umrechnen zur Sicherung hinzufuegen - "zuruecknehmen" stellt dann auch sie wieder her.
	 */
	void addToLocalConversionBackup(java.util.Set<Integer> pointIds, java.util.Set<Integer> baselineIds) throws SQLException;

	/** Sicherung der Naeherungskoordinaten verwerfen. */
	void clearApproximationBackup() throws SQLException;

	/** in der Karte markierte Anschlussbereiche des Projekts (eigene Tabelle, wird bei Bedarf angelegt). */
	List<org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> readConnectionAreas() throws SQLException;

	/** alle Anschlussbereiche ersetzen (loeschen + neu schreiben). */
	void replaceConnectionAreas(List<org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> areas)
			throws SQLException;

	/**
	 * Gesamtzahl der Messachsen im Projekt - Grundlage fuer den Autopilot-Deckel, siehe
	 * Klein_Method_Parameter.getMaxGroupScaleLimit().
	 */
	int readTotalMeasurementAxisCount() throws SQLException;

	/**
	 * fuehrt die vom Anwender per Klick bestaetigte Umbenennung eines einzelnen, konkreten Punktes aus (name = name
	 * || "_KLEIN_A") - AUSSCHLIESSLICH auf explizite Bestaetigung hin, niemals automatisch.
	 */
	void renamePointForFangkreisViolation(int pointId) throws SQLException;

	/**
	 * EP-Werte (Einfluss auf Punktlage) je Komponente fuer ALLE aktiven
	 * Beweglichen Anschlusspunkte ("Festpunkte") - Grundlage fuer den
	 * Festpunktanalyse-Empfehlungsbericht ("Phase 3", Stufe 1: reiner
	 * Bericht, KEINE Projektaenderung).
	 */
	List<Klein_Method_Point_Ep_Row> readStochasticPointEpValues() throws SQLException;

	/**
	 * STUFE 2: legt eine neue Punktgruppe des angegebenen Typs an (Festpunktanalyse: "Varianzfreie Festpunkte") und gibt
	 * ihre ID zurueck. VERAENDERT das Projekt.
	 */
	int createPointGroup(String name, int dimension, org.applied_geodesy.adjustment.network.PointType type) throws SQLException;

	/**
	 * STUFE 2: verschiebt die angegebenen Punkte in eine andere Gruppe (group_id-Aenderung). VERAENDERT das Projekt.
	 */
	void movePointsToGroup(List<Integer> pointIds, int newGroupId) throws SQLException;

	/**
	 * FESTPUNKTANALYSE PHASE 2: aktuelle individuelle Unsicherheit (sigma_y0/
	 * x0/z0) UND Tprio je aktivem Beweglichem Anschlusspunkt.
	 */
	List<Klein_Method_Point_Tprio_Row> readStochasticPointTprioValues() throws SQLException;

	/**
	 * FESTPUNKTANALYSE PHASE 2: schreibt die individuelle Unsicherheit
	 * EINES einzelnen Punktes (anders als Phase 1, die auf ALLE Punkte
	 * einer Gruppe gleichzeitig schreibt).
	 */
	void writePointUncertainty(int pointId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException;

	// ----------------------------------------------------------------- Projektionsart und Passpunkte der Karte
	// ------------------------------------------------------------------

	/** Projektionsart aus "Projektion und Reduktion" (Tabelle "ReductionDefinition"), null ohne Projekt. */
	org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType readProjectionType() throws SQLException;

	/** Passpunkte der Karte: Punktname -> {Breite, Laenge} [Grad], in der Reihenfolge des Anlegens. */
	Map<String, double[]> readMapReferences() throws SQLException;

	void storeMapReference(String pointName, double latitude, double longitude) throws SQLException;

	void deleteMapReference(String pointName) throws SQLException;

	/** Einstellung der Karte im Projekt (z. B. Kartenprojektion), null wenn nicht gesetzt. */
	String readMapSetting(String key) throws SQLException;

	void storeMapSetting(String key, String value) throws SQLException;

	// ----------------------------------------------------------------- die groessten Fehler
	// ------------------------------------------------------------------

	/**
	 * Die {@code limit} Beobachtungen mit dem groessten |logPost| ("p_post") ueber ALLE
	 * aktiven Beobachtungstypen (inkl. GNSS), absteigend sortiert. Nur Anzeige.
	 */
	List<Max_Error_Row> readLargestObservationErrors(int limit) throws SQLException;

	/**
	 * Die {@code limit} beweglichen Anschlusspunkte (dynamische AGL) mit dem groessten
	 * |T_prio|, absteigend sortiert, mit der Punktverschiebung (Residuen y/x/z). Nur Anzeige.
	 */
	List<Max_Error_Row> readLargestPointErrors(int limit) throws SQLException;

	/** Status eines Eintrags: vorübergehend fuer Phase 1 herausgenommen (in JAG3D deaktiviert). */
	String GROSS_ERROR_EXCLUDED = "EXCLUDED";
	/** Status eines Eintrags: vom Anwender bewusst als "kein grober Fehler" bzw. "wieder aufnehmen" bestaetigt. */
	String GROSS_ERROR_ACCEPTED = "ACCEPTED";

	/** Alle gemerkten Eintraege (Tabelle "KleinMethodGrossError" in der Projektdatenbank). */
	List<Max_Error_Row> readGrossErrorEntries() throws SQLException;

	void storeGrossErrorEntry(Max_Error_Row row, String status) throws SQLException;

	void deleteGrossErrorEntry(Max_Error_Row row) throws SQLException;

	/** Schaltet eine Beobachtung (generisch oder GNSS-Basislinie) in JAG3D ein bzw. aus. */
	void setObservationEnabled(Max_Error_Row row, boolean enabled) throws SQLException;

	/** Speicherort der Projektdatenbank (nur fuer die anonyme Projektkennung, wird nie uebertragen), oder null. */
	String getProjectLocation();

	/** Anzahl aktiver Beobachtungen je JAG3D-Beobachtungstyp (generisch und GNSS). */
	Map<ObservationType, Integer> readActiveObservationCounts() throws SQLException;

	/** Projekteinstellung (Schluessel max. 17 Zeichen, Wert max. 64), null wenn nicht gesetzt. */
	String readProjectSetting(String key) throws SQLException;

	void storeProjectSetting(String key, String value) throws SQLException;

	/**
	 * eine Zeile der "Max Fehler"-Anzeige - eine Beobachtung (Start/Ziel) oder ein Punkt (nur Start). Einheiten wie
	 * in der Datenbank: residual in m bzw. rad.
	 */
	final class Max_Error_Row {
		/** Beobachtungstyp (JAG3D) oder null bei einem Punkt. */
		public final ObservationType observationType;
		public final String startPointName, endPointName;
		/** |logPost| (Beobachtung) bzw. |T_prio| (Punkt). */
		public final double value;
		/** Verbesserung: Betrag (Strecke/GNSS/Punkt in m, Richtung/Zenitwinkel in rad). */
		public final double residual;
		/** Datenbank-ID der Beobachtung (-1 bei Punkten). */
		public final int observationId;
		/** true = Tabelle "GNSSObservationApriori", sonst "ObservationApriori". */
		public final boolean gnss;
		/** Status eines gemerkten Eintrags (GROSS_ERROR_EXCLUDED/_ACCEPTED), sonst null. */
		public final String status;
		/**
		 * lineare Abweichung [m] - geschaetzter grober Fehler (JAG3D "gross_error", ersatzweise |v|), bei
		 * Richtung/Zenitwinkel mal Strecke Stand-Ziel (Querabweichung am Ziel), bei GNSS Betrag 3D. NaN = unbekannt.
		 */
		public final double linearError;
		/** kleinster Redundanzanteil (NaN = unbekannt). */
		public final double redundancy;
		/** JAG3D-Test: Beobachtung signifikant (nur aus readLargestObservationErrors). */
		public boolean significant;
		/** kleinster aufdeckbarer Fehler, linear [m] wie linearError (NaN = unbekannt). */
		public double mdbLinear = Double.NaN;
		/** geschaetzter grober Fehler mit Vorzeichen in Messwert-Einheit (m bzw. rad; GNSS NaN). */
		public double nablaRaw = Double.NaN;

		public Max_Error_Row(ObservationType observationType, String startPointName, String endPointName, double value, double residual) {
			this(observationType, startPointName, endPointName, value, residual, -1, false, null, Double.NaN);
		}

		public Max_Error_Row(ObservationType observationType, String startPointName, String endPointName, double value, double residual,
				int observationId, boolean gnss, String status) {
			this(observationType, startPointName, endPointName, value, residual, observationId, gnss, status, Double.NaN);
		}

		public Max_Error_Row(ObservationType observationType, String startPointName, String endPointName, double value, double residual,
				int observationId, boolean gnss, String status, double linearError) {
			this(observationType, startPointName, endPointName, value, residual, observationId, gnss, status, linearError, Double.NaN);
		}

		public Max_Error_Row(ObservationType observationType, String startPointName, String endPointName, double value, double residual,
				int observationId, boolean gnss, String status, double linearError, double redundancy) {
			this.redundancy = redundancy;
			this.observationType = observationType;
			this.startPointName = startPointName;
			this.endPointName = endPointName;
			this.value = value;
			this.residual = residual;
			this.observationId = observationId;
			this.gnss = gnss;
			this.status = status;
			this.linearError = linearError;
		}

		/** eindeutiger Schluessel einer Beobachtung ("G:12" bzw. "O:12"). */
		public String key() {
			return (this.gnss ? "G:" : "O:") + this.observationId;
		}

		public boolean isPoint() {
			return this.observationType == null;
		}

		public boolean isAngle() {
			return this.observationType == ObservationType.DIRECTION || this.observationType == ObservationType.ZENITH_ANGLE;
		}
	}
}
// V1.00_RC3_Klein_Method_Database_Handler_Interface.java
