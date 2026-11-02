// V1.00_RC3_Klein_Method_Database_Handler.java
package org.applied_geodesy.klein_method.database;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.applied_geodesy.adjustment.network.DefaultUncertainty;
import org.applied_geodesy.adjustment.network.ObservationGroupUncertaintyType;
import org.applied_geodesy.adjustment.network.PointGroupUncertaintyType;
import org.applied_geodesy.adjustment.network.ObservationType;
import org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType;
import org.applied_geodesy.adjustment.network.ParameterType;
import org.applied_geodesy.adjustment.network.PointType;
import org.applied_geodesy.adjustment.network.VarianceComponentType;
import org.applied_geodesy.jag3d.sql.SQLManager;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step;
import org.applied_geodesy.klein_method.logic.Klein_Method_Functions;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Default_Values;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Format;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;
import org.applied_geodesy.util.unit.LengthUnit;

/**
 * Liest und schreibt die Parameter/Daten von "Klein_Method" aus bzw. in die JAG3D-Projektdatenbank (HSQLDB).
 *
 * WICHTIG: Nutzt bewusst dieselbe Datenbank-Anbindung wie JAG3D selbst
 * (SQLManager.getInstance().getDataBase().getPreparedStatement(sql)), um Sperrkonflikte (Locking) der eingebetteten
 * HSQLDB zu vermeiden. KEINE eigene, zweite Connection oeffnen!
 *
 * Fachliche Herleitung (siehe Projektnotizen):
 *
 * Protokollauszug: p_post_min=-11,223038 stimmt exakt mit dem in JAG3D angezeigten log(Ppost)=-11.22 ueberein).
 *
 * "EP" = Spalte "influence_on_position" in derselben Tabelle (JAG3D-
 *
 * Tooltip: "Einfluss einer unentdeckten Modellstoerung auf die Punktlage"), ebenfalls IMMER als Betrag auszuwerten.
 *
 * GEODAETISCH STRENGE BESTAETIGUNG: die Zuordnung NIVELLEMENT_LOG_POST/_EP -> ObservationType.LEVELING(id=1) in
 * getObservationType() unten ist OFFIZIELL VERIFIZIERT UND BESTAETIGT - kein angenommener, sondern ein am echten
 * JAG3D-Kern-Quellcode belegter Wert. Ebenso bestaetigt: DIRECTION(2), HORIZONTAL_DISTANCE(3), SLOPE_DISTANCE(4),
 * ZENITH_ANGLE(5), GNSS1D(6), GNSS2D(7), GNSS3D(8) - damit sind ALLE 8 uebertragenen Typen dieses gesamten Pakets
 * namentlich UND per numerischer ID am echten Enum bestaetigt, keiner mehr nur "analog angenommen".
 *
 * Zuordnung Beobachtungstyp -> Klein_Method-Grenzwert:
 *
 * Horizontalrichtung -> ObservationType.DIRECTION
 *
 * Horizontalstrecke -> ObservationType.HORIZONTAL_DISTANCE
 *
 * Vertikalrichtung -> ObservationType.ZENITH_ANGLE (bestaetigt)
 *
 * BEWUSST NICHT umgesetzt: "enable" ist die einzige Bedingung, mit der Klein_Method deaktivierte/ausgeschlossene
 * Beobachtungen von der Kalibrierung fernhaelt - ein Wegfall dieser Bedingung wuerde in JEDEM Projekt (nicht nur im
 * GNSS-Testfall) versehentlich deaktivierte Messungen in log(Ppost)/EP einfliessen lassen. Stattdessen: Funnel-Debug
 * (debugFunnel()), das PRO Beobachtungstyp Schritt fuer Schritt ausgibt, an welcher Stelle die Zeilenzahl auf 0
 * faellt (Gruppe vorhanden? Gruppe enable? Beobachtung vorhanden? Beobachtung enable? Aposteriori-JOIN ueberhaupt
 * vorhanden - UNGEFILTERT, zum Ausschluss eines ID-Join-Fehlers?). Das identifiziert die tatsaechliche Ursache, statt
 * sie zu vermuten.
 *
 * BEWUSST NICHT umgesetzt: ohne zu wissen, WARUM group_id und ObservationGroup.id auseinanderlaufen, wuerde ein
 * "toleranteres Matching" Beobachtungen potenziell der FALSCHEN Gruppe zuordnen - in einem geodaetischen QS-Werkzeug
 * ein Sicherheitsrisiko statt einer Reparatur. Stattdessen: zwei weitere Debug-Ausgaben, die die tatsaechlichen
 * group_id-Werte auf BEIDEN Seiten des JOINs offenlegen - die ObservationGroup.id(s), die SQL_GROUP_IDS fuer den Typ
 * liefert, UND die komplette Menge der group_id-Werte, die in ObservationApriori tatsaechlich vorkommen. Der Abgleich
 * beider Listen zeigt eindeutig, ob es sich um einen echten ID-Bruch handelt oder die Gruppe schlicht leer ist.
 *
 * Stattdessen: eine sichere, rein lesende Standard-HSQLDB-Metaabfrage, die die ECHTEN, tatsaechlich existierenden
 * Tabellennamen der aktuell geoeffneten Datenbank auflistet - ersetzt jedes weitere Raten von Tabellennamen durch
 * einen Fakt.
 *
 * DEBUG-ERWEITERUNG: Die Sonde hat "GNSSObservationApriori"/ "GNSSObservationAposteriori" als real existierende, von
 * "ObservationApriori"/ "ObservationAposteriori" getrennte Tabellen bestaetigt (echter Konsolen-auszug, 52 Tabellen,
 * verifiziert). Bevor readLogPost()/readEp() dorthin umgeleitet werden, fehlen noch die ECHTEN Spaltennamen dieser
 * beiden Tabellen - ein SQL-Statement gegen geratene Spaltennamen (group_id, p_post, ...) wuerde entweder gar nicht
 * erst kompilieren/ausfuehren (SQLException bei unbekannter Spalte) oder schlimmer: eine zufaellig gleichnamige, aber
 * inhaltlich andere Spalte als "logPost" anzeigen.
 *
 * Daher: dieselbe sichere Systemkatalog-Technik, jetzt auf Spaltenebene (INFORMATION_SCHEMA.SYSTEM_COLUMNS) fuer
 * genau diese zwei Tabellen - liefert die echten Spaltennamen+Typen als Grundlage fuer die SQL-Umleitung in der
 * naechsten Version.
 *
 * ABWEICHUNG VOM Auftrag, bewusst: der Auftrag wollte die Gruppen-Einschraenkung als fest verdrahtetes "group_id = 0"
 * implementiert haben. group_id=0 ist in der einen getesteten Datenbank zufaellig die ID der (einzigen) aktiven
 * GNSS3D-Gruppe - das ist aber kein stabiler Fakt, sondern ein Artefakt DIESES einen Testprojekts, und unterscheidet
 * nicht zwischen GNSS1D/2D/3D. Ein hart verdrahtetes "group_id = 0" wuerde bei jedem anderen Projekt (andere
 * Gruppen-ID) und bei GNSS1D/2D (jeweils eigene Gruppe/ID) leise falsche oder leere Werte liefern. Stattdessen:
 * derselbe JOIN "GNSSObservationApriori"."group_id" = "ObservationGroup". "id" AND "ObservationGroup"."type" =? wie
 * beim bereits bewaehrten, generischen SQL_MAX_LOG_POST/SQL_MAX_EP - liefert in diesem Testprojekt exakt dieselbe
 * group_id=0, ist aber korrekt fuer jedes Projekt und fuer alle drei GNSS-Typen einzeln.
 *
 * PHASE-2-UMLEITUNG: readLogPost()/readEp() (Phase 1) lasen bereits korrekt aus den GNSS-Tabellen, aber die
 * Individualkalibrierung (Phase 2, SQL_OBSERVATIONS_IN_BAND/SQL_WRITE_INDIVIDUAL_UNCERTAINTY) griff weiterhin NUR auf
 * die generischen "ObservationApriori"/"ObservationAposteriori"-Tabellen zu - fuer GNSS strukturell 0 Treffer, siehe
 * _GNSS_Erfolgsanalyse.md.
 *
 * ABWEICHUNG VOM Auftrag, bewusst: der Auftrag wollte einen Math.max() ueber die 3 Achsen AUCH beim Zurueckschreiben.
 * Das ist NICHT umgesetzt, weil es keine gueltige Zielspalte dafuer gibt - "GNSSObservationApriori" hat KEIN
 * gemeinsames "sigma_0", sondern drei UNABHAENGIGE Spalten "sigma_x0"/"sigma_y0"/"sigma_z0". STATTDESSEN: jede Achse
 * wird als EIGENSTAENDIGE Zeile behandelt (Klein_Method_Observation_Uncertainty_Row.GnssAxis), unabhaengig kalibriert
 * und unabhaengig in ihre eigene Spalte zurueckgeschrieben (readGnssObservationsForIndividualCalibration()/
 * writeGnssIndividualUncertainty()) - keine Vermischung der Achsen.
 *
 * "distance" je Achsen-Zeile: JAG3D setzt fuer jede Komponente distanceForUncertaintyModel = |Komponente| (x0/y0/z0).
 * Bis stand hier immer 0.0, sigma_b/sigma_c der Gruppe fielen damit weg.
 *
 * EP bei GNSS-Basislinien: anders als bei den generischen Beobachtungen gibt es KEINE einzelne kombinierte Spalte
 * "influence_on_position", sondern drei Achsen-Spalten "influence_on_position_x"/"_y"/"_z" (eine GNSS-Basislinie hat
 * 3 raeumliche Komponenten in EINER Zeile). Wie vom User beauftragt: der ausgegebene EP-Wert ist das Maximum ueber
 * alle drei Achsen (je Achse zuerst MAX(ABS(...)) ueber alle Zeilen in SQL, danach Math.max() der drei Achsen-Maxima
 * in Java) - analog zum bereits bestehenden achsenweisen Muster bei "PointAposteriori" weiter unten in dieser Klasse.
 */
public class Klein_Method_Database_Handler implements Klein_Method_Database_Handler_Interface {
	// Teilklassen (Gliederung, siehe Ende der Klasse)
	/** Filter: von der Kalibrierung ausgenommene (regewichtete) Beobachtungen. */
	static final String REPETITION_FILTER_TERRESTRIAL = " AND \"ObservationApriori\".\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodRepetition\" "
			+ "WHERE \"kind\" = 0 AND \"role\" = 2)";
	static final String REPETITION_FILTER_GNSS = " AND \"GNSSObservationApriori\".\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodRepetition\" "
			+ "WHERE \"kind\" = 1 AND \"role\" = 2)";

	private final Calibration_Part calibrationPart = new Calibration_Part();
	private final Status_Part statusPart = new Status_Part();
	private final Coordinate_Part coordinatePart = new Coordinate_Part();
	private final Settings_Part settingsPart = new Settings_Part();
	private final Gross_Error_Part grossErrorPart = new Gross_Error_Part();
	private final Debug_Part debugPart = new Debug_Part();

	/**
	 * liefert das Verzeichnis der aktuell offenen HSQLDB-Projektdatenbank. Nutzt bewusst KEINEN eigenen
	 * "connection.getMetaData().getURL()"-Zugriff auf eine neu angeforderte Connection (die Klasse haelt ohnehin nie
	 * ein eigenes java.sql.Connection-Feld, siehe Klassenkommentar oben: "KEINE eigene, zweite Connection oeffnen!")
	 * - stattdessen wird PreparedStatement.getConnection() verwendet (garantierter Standard-JDBC-Methodenaufruf aus
	 * java.sql.Statement), auf einem PreparedStatement, das ueber exakt denselben, bereits ueberall in dieser Klasse
	 * bewaehrten Weg erzeugt wird (SQLManager.getInstance().getDataBase().getPreparedStatement(sql)). So ist
	 * sichergestellt, dass es dieselbe, von JAG3D bereits offene Verbindung ist - ohne auf eine (hier nicht
	 * verifizierbare) eigene getConnection()-Methode der JAG3D-eigenen DataBase-Wrapperklasse angewiesen zu sein.
	 * SQL_DEBUG_ALL_TABLE_NAMES ist eine bereits bestehende, bestaetigt funktionierende Abfrage - sie wird hier nur
	 * als Traeger fuer die Connection wiederverwendet, ihr Ergebnis wird nicht ausgewertet.
	 */
	@Override
	public String getProjectDirectory() {
		if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
			return null;

		try {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_ALL_TABLE_NAMES);
			String jdbcUrl = stmt.getConnection().getMetaData().getURL();
			return this.extractDirectoryFromHsqldbFileUrl(jdbcUrl);
		} catch (SQLException e) {
			return null;
		}
	}

	/**
	 * Zerlegt eine HSQLDB-JDBC-URL im dokumentierten Datei-Modus-Format (z. B.
	 * "jdbc:hsqldb:file:/pfad/zum/projekt/projektname;user=SA;...") in das
	 * ENTHALTENDE Verzeichnis. HSQLDB legt im Datei-Modus mehrere Dateien
	 * (.script/.properties/.data/.log/.lck) unter genau diesem Basis-Dateinamen
	 * an - der Pfad OHNE die letzte Ebene (den Basisnamen) ist somit das
	 * gesuchte Projektverzeichnis. Liefert null bei einer Nicht-Datei-URL (z. B.
	 * "jdbc:hsqldb:hsql://..." - Server-/Netzwerk-Modus ohne lokalen Pfad) oder
	 * wenn die URL null ist.
	 */
	private String extractDirectoryFromHsqldbFileUrl(String jdbcUrl) {
		if (jdbcUrl == null)
			return null;

		int fileMarker = jdbcUrl.indexOf("hsqldb:file:");
		if (fileMarker < 0)
			return null; // z.B. Server-/Netzwerk-Modus - kein lokaler Dateipfad vorhanden

		String pathPart = jdbcUrl.substring(fileMarker + "hsqldb:file:".length());
		int semicolon = pathPart.indexOf(';');
		if (semicolon >= 0)
			pathPart = pathPart.substring(0, semicolon);

		if (pathPart.isEmpty())
			return null;

		File dbBaseFile = new File(pathPart);
		File parentDir = dbBaseFile.getParentFile();
		return parentDir != null ? parentDir.getAbsolutePath() : null;
	}

	// SQL fuer logPost: groesster |p_post|-Wert je Beobachtungstyp, nur ueber
	// aktivierte Gruppen/Beobachtungen. KEIN LOG10() mehr - "p_post" ist in
	// dieser Datenbank bereits log10-transformiert (siehe Klassendoku oben).
	private static final String SQL_MAX_LOG_POST =
			"SELECT MAX(ABS(\"p_post\")) AS \"max_log_post\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"ObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"p_post\" IS NOT NULL" + REPETITION_FILTER_TERRESTRIAL;

	// SQL fuer EP: groesster |influence_on_position|-Wert je Beobachtungstyp
	private static final String SQL_MAX_EP =
			"SELECT MAX(ABS(\"influence_on_position\")) AS \"max_ep\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"ObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"influence_on_position\" IS NOT NULL" + REPETITION_FILTER_TERRESTRIAL;

	// dieselben zwei Kennzahlen (logPost/EP), aber gegen die GNSS-eigenen Tabellen
	// "GNSSObservationApriori"/"GNSSObservationAposteriori" statt gegen die generischen
	// "ObservationApriori"/"ObservationAposteriori"-Tabellen, in denen GNSS-Basislinien strukturell nie eine Zeile
	// haben.
	private static final String SQL_MAX_LOG_POST_GNSS =
			"SELECT MAX(ABS(\"GNSSObservationAposteriori\".\"p_post\")) AS \"max_log_post\" " +
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"GNSSObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"GNSSObservationAposteriori\".\"p_post\" IS NOT NULL" + REPETITION_FILTER_GNSS;

	// EP bei GNSS: KEINE einzelne "influence_on_position"-Spalte, sondern drei
	// Achsen-Spalten (_x/_y/_z) - je Achse ein eigenes MAX(ABS(...)); die
	// Zusammenfuehrung zum Achsen-Maximum passiert in Java (readEpGnss()).
	private static final String SQL_MAX_EP_GNSS =
			"SELECT MAX(ABS(\"GNSSObservationAposteriori\".\"influence_on_position_x\")) AS \"max_ep_x\", " +
			"MAX(ABS(\"GNSSObservationAposteriori\".\"influence_on_position_y\")) AS \"max_ep_y\", " +
			"MAX(ABS(\"GNSSObservationAposteriori\".\"influence_on_position_z\")) AS \"max_ep_z\" " +
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"GNSSObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ?" + REPETITION_FILTER_GNSS;

	// DEBUG: Zeilenzaehler fuer die GNSS-eigenen Tabellen (analog zu SQL_COUNT_MATCHING_LOG_POST_ROWS, nur eben auf
	// "GNSSObservationApriori"/ "GNSSObservationAposteriori" bezogen statt auf die generischen Tabellen).
	private static final String SQL_COUNT_MATCHING_GNSS_ROWS =
			"SELECT COUNT(*) AS \"cnt\" " +
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"GNSSObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ?";

	private static final String SQL_COUNT_MATCHING_GNSS_LOG_POST_ROWS_NON_NULL =
			SQL_COUNT_MATCHING_GNSS_ROWS + " AND \"GNSSObservationAposteriori\".\"p_post\" IS NOT NULL";

	// PHASE 1: a-posteriori Varianzfaktor ("1:σ²") je Beobachtungstyp,
	// aus der Tabelle "VarianceComponent" (type = VarianceComponentType.getId())
	private static final String SQL_VARIANCE_RATIO =
			"SELECT \"sigma2apost\" FROM \"VarianceComponent\" WHERE \"type\" = ?";

	// PHASE 1: aktive Gruppen-IDs eines Beobachtungstyps
	// Anzeige "Ausgelesene Werte": Spanne der Gruppen-Messunsicherheiten je Beobachtungstyp und Komponente (aktive Gruppen)
	private static final String SQL_GROUP_UNCERTAINTY_RANGES =
			"SELECT \"ObservationGroup\".\"type\" AS \"obs_type\", \"ObservationGroupUncertainty\".\"type\" AS \"component\", " +
			"MIN(\"ObservationGroupUncertainty\".\"value\") AS \"min_value\", MAX(\"ObservationGroupUncertainty\".\"value\") AS \"max_value\" " +
			"FROM \"ObservationGroupUncertainty\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationGroupUncertainty\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"GROUP BY \"ObservationGroup\".\"type\", \"ObservationGroupUncertainty\".\"type\"";

	private static final String SQL_GROUP_IDS =
			"SELECT \"id\" FROM \"ObservationGroup\" WHERE \"type\" = ? AND \"enable\" = TRUE";

	// PHASE 1: σa/σb/σc einer einzelnen Gruppe
	private static final String SQL_READ_GROUP_UNCERTAINTY =
			"SELECT \"type\", \"value\" FROM \"ObservationGroupUncertainty\" WHERE \"group_id\" = ?";

	// PHASE 1: σa/σb/σc einer einzelnen Gruppe zurueckschreiben (analog zu
	// SQLManager.saveUncertainty(), gleiches MERGE-Muster)
	private static final String SQL_WRITE_GROUP_UNCERTAINTY =
			"MERGE INTO \"ObservationGroupUncertainty\" USING (VALUES (CAST(? AS INT), CAST(? AS INT), ?)) " +
			"AS \"vals\" (\"group_id\", \"type\", \"value\") " +
			"ON \"ObservationGroupUncertainty\".\"group_id\" = \"vals\".\"group_id\" AND \"ObservationGroupUncertainty\".\"type\" = \"vals\".\"type\" " +
			"WHEN MATCHED THEN UPDATE SET \"ObservationGroupUncertainty\".\"value\" = \"vals\".\"value\" " +
			"WHEN NOT MATCHED THEN INSERT (\"group_id\", \"type\", \"value\") VALUES (\"vals\".\"group_id\", \"vals\".\"type\", \"vals\".\"value\")";

	// PHASE 2: Beobachtungen eines Typs, deren |logPost| im uebergebenen
	// Bearbeitungsbereich liegt - inkl. group_id (fuer die Gruppen-Basis,
	// Fall 1 der Formel) und aktuellem sigma_0 (individueller Wert, Fall 2).
	// KEIN LOG10() mehr - siehe SQL_MAX_LOG_POST.
	private static final String SQL_OBSERVATIONS_IN_BAND =
			"SELECT \"ObservationApriori\".\"id\" AS \"id\", \"ObservationApriori\".\"group_id\" AS \"group_id\", " +
			"\"ObservationApriori\".\"sigma_0\" AS \"sigma_0\", ABS(\"p_post\") AS \"abs_log_post\", " +
			"\"ObservationApriori\".\"distance_0\" AS \"distance_0\", " +
			"\"PS\".\"x0\" AS \"ps_x\", \"PS\".\"y0\" AS \"ps_y\", \"PS\".\"z0\" AS \"ps_z\", " +
			"\"PE\".\"x0\" AS \"pe_x\", \"PE\".\"y0\" AS \"pe_y\", \"PE\".\"z0\" AS \"pe_z\", " +
			"\"ObservationApriori\".\"instrument_height\" AS \"ih\", \"ObservationApriori\".\"reflector_height\" AS \"th\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"LEFT JOIN \"PointApriori\" AS \"PS\" ON \"PS\".\"name\" = \"ObservationApriori\".\"start_point_name\" " +
			"LEFT JOIN \"PointApriori\" AS \"PE\" ON \"PE\".\"name\" = \"ObservationApriori\".\"end_point_name\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"ObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"p_post\" IS NOT NULL " +
			"AND ABS(\"p_post\") >= ? AND ABS(\"p_post\") <= ?" + REPETITION_FILTER_TERRESTRIAL;

	// PHASE 2: individuelle Messunsicherheit (oh0/osh0/ov0/os0) einer
	// einzelnen Beobachtung zurueckschreiben
	private static final String SQL_WRITE_INDIVIDUAL_UNCERTAINTY =
			"UPDATE \"ObservationApriori\" SET \"sigma_0\" = ? WHERE \"id\" = ?";

	// zwei parameterlose Utility-Befehle fuer commitAndCheckpoint() - beide reine HSQLDB-Server-Kommandos, keine
	// Tabellen-/Spaltennamen involviert.
	private static final String SQL_COMMIT = "COMMIT";
	private static final String SQL_CHECKPOINT = "CHECKPOINT";

	// GNSS-Pendant zu SQL_OBSERVATIONS_IN_BAND. KEIN "distance_0" - GNSSObservationApriori hat diese Spalte nicht -
	// die drei Achsen-Unsicherheiten (sigma_x0/y0/z0) werden stattdessen direkt mitgelesen, damit
	// Klein_Method_Functions daraus PRO ACHSE eine eigene Klein_Method_Observation_Uncertainty_Row bauen kann.
	private static final String SQL_GNSS_OBSERVATIONS_IN_BAND =
			"SELECT \"GNSSObservationApriori\".\"id\" AS \"id\", \"GNSSObservationApriori\".\"group_id\" AS \"group_id\", " +
			"ABS(\"GNSSObservationAposteriori\".\"p_post\") AS \"abs_log_post\", " +
			"\"GNSSObservationApriori\".\"sigma_x0\" AS \"sigma_x0\", " +
			"\"GNSSObservationApriori\".\"sigma_y0\" AS \"sigma_y0\", " +
			"\"GNSSObservationApriori\".\"sigma_z0\" AS \"sigma_z0\", " +
			"\"GNSSObservationApriori\".\"x0\" AS \"comp_x\", \"GNSSObservationApriori\".\"y0\" AS \"comp_y\", \"GNSSObservationApriori\".\"z0\" AS \"comp_z\", " +
			"\"GNSSObservationAposteriori\".\"influence_on_position_x\" AS \"ep_x\", " +
			"\"GNSSObservationAposteriori\".\"influence_on_position_y\" AS \"ep_y\", " +
			"\"GNSSObservationAposteriori\".\"influence_on_position_z\" AS \"ep_z\" " +
			// zusaetzlich "influence_on_position_x"/"_y"/"_z" ergaenzt - dieselben drei, bereits an anderer Stelle in
			// dieser Klasse (SQL_MAX_EP_GNSS) bestaetigten Spalten, jetzt PRO BASISLINIE statt nur als Maximum -
			// Grundlage des neuen, residuen-nahen Gewichtungsschluessels (siehe
			// Klein_Method_Functions.performIndividualCalibrationStep()).
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"GNSSObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"GNSSObservationAposteriori\".\"p_post\" IS NOT NULL " +
			"AND ABS(\"GNSSObservationAposteriori\".\"p_post\") >= ? AND ABS(\"GNSSObservationAposteriori\".\"p_post\") <= ?" + REPETITION_FILTER_GNSS;

	// GNSS-Pendant zu SQL_WRITE_INDIVIDUAL_UNCERTAINTY - DREI eigene Statements (eine je Achse), NIE gemeinsam
	// verwendet - jeder Aufruf von writeGnssIndividualUncertainty() waehlt GENAU EINES per Achsen-Parameter.
	private static final String SQL_WRITE_GNSS_INDIVIDUAL_UNCERTAINTY_X =
			"UPDATE \"GNSSObservationApriori\" SET \"sigma_x0\" = ? WHERE \"id\" = ?";
	private static final String SQL_WRITE_GNSS_INDIVIDUAL_UNCERTAINTY_Y =
			"UPDATE \"GNSSObservationApriori\" SET \"sigma_y0\" = ? WHERE \"id\" = ?";
	private static final String SQL_WRITE_GNSS_INDIVIDUAL_UNCERTAINTY_Z =
			"UPDATE \"GNSSObservationApriori\" SET \"sigma_z0\" = ? WHERE \"id\" = ?";

	// schaltet eine GESAMTE GNSS-Basislinie auf "enable" = false - dieselbe Spalte, dieselbe Tabelle wie bei allen
	// anderen "enable"-Pruefungen dieser Klasse (WHERE \"...\".\"enable\" = TRUE), also eine bestaetigt existierende
	// Spalte - kein neuer, ungeprueft geratener Spaltenname.
	private static final String SQL_DEACTIVATE_GNSS_OBSERVATION =
			"UPDATE \"GNSSObservationApriori\" SET \"enable\" = FALSE WHERE \"id\" = ?";
	// GNSS-Sitzungen zusammenfassen (Spalten bestaetigt aus JAG3D SQLDatabase.java "GNSSObservationApriori")
	private static final String SQL_CREATE_GNSS_MERGE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodGnssSessionMerge\" (\"id\" INTEGER NOT NULL, \"role\" SMALLINT NOT NULL, PRIMARY KEY (\"id\"))";
	private static final String SQL_ACTIVE_GNSS3D_FOR_MERGE =
			"SELECT \"GNSSObservationApriori\".\"id\", \"GNSSObservationApriori\".\"group_id\", \"GNSSObservationApriori\".\"start_point_name\", "
			+ "\"GNSSObservationApriori\".\"end_point_name\", \"GNSSObservationApriori\".\"y0\", \"GNSSObservationApriori\".\"x0\", \"GNSSObservationApriori\".\"z0\", "
			+ "\"GNSSObservationApriori\".\"sigma_y0\", \"GNSSObservationApriori\".\"sigma_x0\", \"GNSSObservationApriori\".\"sigma_z0\" FROM \"GNSSObservationApriori\" "
			+ "JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" "
			+ "WHERE \"GNSSObservationApriori\".\"enable\" = TRUE AND \"ObservationGroup\".\"type\" = ? "
			+ "AND \"GNSSObservationApriori\".\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodGnssSessionMerge\") ORDER BY \"GNSSObservationApriori\".\"id\" ASC";
	private static final String SQL_INSERT_GNSS_MEAN =
			"INSERT INTO \"GNSSObservationApriori\" (\"id\", \"group_id\", \"start_point_name\", \"end_point_name\", \"y0\", \"x0\", \"z0\", "
			+ "\"sigma_y0\", \"sigma_x0\", \"sigma_z0\", \"enable\") VALUES (DEFAULT, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)";
	private static final String SQL_INSERT_GNSS_MERGE_ROLE = "INSERT INTO \"KleinMethodGnssSessionMerge\" (\"id\", \"role\") VALUES (?, ?)";
	private static final String SQL_READ_GNSS_MERGE = "SELECT \"id\", \"role\" FROM \"KleinMethodGnssSessionMerge\"";
	private static final String SQL_DELETE_GNSS_OBSERVATION = "DELETE FROM \"GNSSObservationApriori\" WHERE \"id\" = ?";
	private static final String SQL_CLEAR_GNSS_MERGE = "DELETE FROM \"KleinMethodGnssSessionMerge\"";
	private static final String SQL_COUNT_GNSS_MERGE = "SELECT COUNT(*) FROM \"KleinMethodGnssSessionMerge\"";
	private static final String SQL_ACTIVE_GNSS_IDS_OF_POINT =
			"SELECT \"id\" FROM \"GNSSObservationApriori\" WHERE \"enable\" = TRUE AND (\"start_point_name\" = ? OR \"end_point_name\" = ?) ORDER BY \"id\" ASC";

	// Alle Beobachtungstypen, die im Projekt aktiv vorkommen (mind. eine
	// aktive Beobachtung in einer aktiven Gruppe) - fuer den Abgleich mit
	// Klein_Method_Default_Values.SUPPORTED_OBSERVATION_TYPES (Messdaten-Ampel)
	private static final String SQL_ACTIVE_OBSERVATION_TYPES =
			"SELECT DISTINCT \"ObservationGroup\".\"type\" AS \"type\" " +
			"FROM \"ObservationGroup\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND EXISTS (SELECT 1 FROM \"ObservationApriori\" " +
			"WHERE \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"AND \"ObservationApriori\".\"enable\" = TRUE)";

	// SQL_ACTIVE_OBSERVATION_TYPES (oben) prueft EXISTS ausschliesslich gegen die generische/terrestrische
	// "ObservationApriori"
	// GNSS-Basislinien liegen in der EIGENEN Tabelle "GNSSObservationApriori" und werden dort NIE erfasst. Eigene,
	// direkte Abfrage dagegen - dieselben enable=TRUE-Bedingungen wie SQL_GNSS_OBSERVATIONS_IN_BAND (Gruppe UND
	// Beobachtung), damit "aktiv" ueberall in Klein_Method dieselbe Bedeutung hat.
	private static final String SQL_ACTIVE_GNSS_OBSERVATION_COUNT =
			"SELECT COUNT(*) AS \"cnt\" FROM \"GNSSObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"GNSSObservationApriori\".\"enable\" = TRUE";

	// Gesamtzahl ALLER GNSS-Basislinien im Projekt, UNABHAENGIG vom enable-Status (bewusst OHNE die WHERE-Bedingungen
	// von SQL_ACTIVE_GNSS_OBSERVATION_COUNT oben) - dient als Nenner "Y" fuer das Label "Deaktivierte BL: X / Y".
	private static final String SQL_TOTAL_GNSS_OBSERVATION_COUNT =
			"SELECT COUNT(*) AS \"cnt\" FROM \"GNSSObservationApriori\"";

	// Anzahl der GNSS-Basislinien mit enable=FALSE - zaehlt ALLE deaktivierten Basislinien (gleich aus welchem Grund
	// - siehe Klassenkommentar von Klein_Method_Parameter.disabledGnssBaselineCount), dient als Zaehler "X" fuer das
	// Label "Deaktivierte BL: X / Y".
	private static final String SQL_DISABLED_GNSS_OBSERVATION_COUNT =
			"SELECT COUNT(*) AS \"cnt\" FROM \"GNSSObservationApriori\" " +
			"WHERE \"GNSSObservationApriori\".\"enable\" = FALSE";

	/**
	 * Ordnet einem logPost-Grenzwert-Typ den zugehoerigen JAG3D-
	 * ObservationType zu (siehe Klassenkommentar).
	 */
	private ObservationType getObservationType(Klein_Method_Threshold_Type logPostType) {
		switch (logPostType) {
		case HORIZONTAL_DIRECTION_LOG_POST: return ObservationType.DIRECTION;
		case HORIZONTAL_DISTANCE_LOG_POST:  return ObservationType.HORIZONTAL_DISTANCE;
		case VERTICAL_DIRECTION_LOG_POST:   return ObservationType.ZENITH_ANGLE;
		case SLOPE_DISTANCE_LOG_POST:       return ObservationType.SLOPE_DISTANCE;
		// OFFIZIELL VERIFIZIERT UND BESTAETIGT - per echter ObservationType.java (Michael Loesler, GPLv3, vom
		// Anwender als Quelldatei bereitgestellt): LEVELING(1) ist exakt der Wert, den diese Zuordnung erwartet.
		case NIVELLEMENT_LOG_POST:          return ObservationType.LEVELING;
		// GNSS-Basislinien - bestaetigt per echtem JAG3D-Quellcode (ObservationType.java): GNSS1D=6, GNSS2D=7,
		// GNSS3D=8.
		case GNSS_BASELINE_1D_LOG_POST:     return ObservationType.GNSS1D;
		case GNSS_BASELINE_2D_LOG_POST:     return ObservationType.GNSS2D;
		case GNSS_BASELINE_3D_LOG_POST:     return ObservationType.GNSS3D;
		default: throw new IllegalArgumentException("Kein logPost-Typ mit Beobachtungstyp-Zuordnung: " + logPostType);
		}
	}

	/**
	 * Ordnet einem logPost-Grenzwert-Typ die zugehoerige JAG3D-VarianceComponentType-Komponente zu (fuer die Tabelle
	 * "VarianceComponent").
	 */
	private VarianceComponentType getVarianceComponentTypeA(Klein_Method_Threshold_Type logPostType) {
		switch (logPostType) {
		case HORIZONTAL_DIRECTION_LOG_POST: return VarianceComponentType.DIRECTION_ZERO_POINT_OFFSET_COMPONENT;
		case HORIZONTAL_DISTANCE_LOG_POST:  return VarianceComponentType.HORIZONTAL_DISTANCE_ZERO_POINT_OFFSET_COMPONENT;
		case VERTICAL_DIRECTION_LOG_POST:   return VarianceComponentType.ZENITH_ANGLE_ZERO_POINT_OFFSET_COMPONENT;
		case SLOPE_DISTANCE_LOG_POST:       return VarianceComponentType.SLOPE_DISTANCE_ZERO_POINT_OFFSET_COMPONENT;
		case NIVELLEMENT_LOG_POST:          return VarianceComponentType.LEVELING_ZERO_POINT_OFFSET_COMPONENT;
		// GNSS-Basislinien - bestaetigt per echtem JAG3D-Quellcode (VarianceComponentType.java, Paket
		// org.applied_geodesy.adjustment.network).
		case GNSS_BASELINE_1D_LOG_POST:     return VarianceComponentType.GNSS1D_ZERO_POINT_OFFSET_COMPONENT;
		case GNSS_BASELINE_2D_LOG_POST:     return VarianceComponentType.GNSS2D_ZERO_POINT_OFFSET_COMPONENT;
		case GNSS_BASELINE_3D_LOG_POST:     return VarianceComponentType.GNSS3D_ZERO_POINT_OFFSET_COMPONENT;
		default: throw new IllegalArgumentException("Kein logPost-Typ mit Varianzkomponenten-Zuordnung: " + logPostType);
		}
	}

	private VarianceComponentType getVarianceComponentTypeB(Klein_Method_Threshold_Type logPostType) {
		switch (logPostType) {
		case HORIZONTAL_DIRECTION_LOG_POST: return VarianceComponentType.DIRECTION_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case HORIZONTAL_DISTANCE_LOG_POST:  return VarianceComponentType.HORIZONTAL_DISTANCE_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case VERTICAL_DIRECTION_LOG_POST:   return VarianceComponentType.ZENITH_ANGLE_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case SLOPE_DISTANCE_LOG_POST:       return VarianceComponentType.SLOPE_DISTANCE_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case NIVELLEMENT_LOG_POST:          return VarianceComponentType.LEVELING_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case GNSS_BASELINE_1D_LOG_POST:     return VarianceComponentType.GNSS1D_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case GNSS_BASELINE_2D_LOG_POST:     return VarianceComponentType.GNSS2D_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		case GNSS_BASELINE_3D_LOG_POST:     return VarianceComponentType.GNSS3D_SQUARE_ROOT_DISTANCE_DEPENDENT_COMPONENT;
		default: throw new IllegalArgumentException("Kein logPost-Typ mit Varianzkomponenten-Zuordnung: " + logPostType);
		}
	}

	private VarianceComponentType getVarianceComponentTypeC(Klein_Method_Threshold_Type logPostType) {
		switch (logPostType) {
		case HORIZONTAL_DIRECTION_LOG_POST: return VarianceComponentType.DIRECTION_DISTANCE_DEPENDENT_COMPONENT;
		case HORIZONTAL_DISTANCE_LOG_POST:  return VarianceComponentType.HORIZONTAL_DISTANCE_DISTANCE_DEPENDENT_COMPONENT;
		case VERTICAL_DIRECTION_LOG_POST:   return VarianceComponentType.ZENITH_ANGLE_DISTANCE_DEPENDENT_COMPONENT;
		case SLOPE_DISTANCE_LOG_POST:       return VarianceComponentType.SLOPE_DISTANCE_DISTANCE_DEPENDENT_COMPONENT;
		case NIVELLEMENT_LOG_POST:          return VarianceComponentType.LEVELING_DISTANCE_DEPENDENT_COMPONENT;
		case GNSS_BASELINE_1D_LOG_POST:     return VarianceComponentType.GNSS1D_DISTANCE_DEPENDENT_COMPONENT;
		case GNSS_BASELINE_2D_LOG_POST:     return VarianceComponentType.GNSS2D_DISTANCE_DEPENDENT_COMPONENT;
		case GNSS_BASELINE_3D_LOG_POST:     return VarianceComponentType.GNSS3D_DISTANCE_DEPENDENT_COMPONENT;
		default: throw new IllegalArgumentException("Kein logPost-Typ mit Varianzkomponenten-Zuordnung: " + logPostType);
		}
	}

	public void read(Klein_Method_Parameter parameter) throws SQLException {
		this.calibrationPart.read(parameter);
	}

	@Override
	public void refreshGroupUncertaintyRanges(Klein_Method_Parameter parameter) throws SQLException {
		if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
			return;
		this.calibrationPart.readGroupUncertaintyRanges(parameter);
	}

	// DEBUG: Hilfsabfrage fuer die ECHTE Anzahl zugrunde liegender Beobachtungszeilen. Das Haupt-ResultSet von
	// SQL_MAX_LOG_POST/SQL_MAX_EP ist ein Aggregat (MAX(...)) und liefert IMMER genau 1 Zeile zurueck - auch bei 0
	// Treffern (dann mit NULL) - "die reale Zeilenanzahl des ResultSets" waere dort also IMMER 1 und damit nicht
	// aussagekraeftig. Diese COUNT(*)-Hilfsabfrage (gleiche WHERE-Bedingung, ohne Aggregat) liefert die tatsaechlich
	// fuer observationType.getId() gefundene Zeilenzahl.
	private static final String SQL_COUNT_MATCHING_LOG_POST_ROWS =
			"SELECT COUNT(*) AS \"cnt\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"ObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ?";

	private static final String SQL_COUNT_MATCHING_LOG_POST_ROWS_NON_NULL =
			SQL_COUNT_MATCHING_LOG_POST_ROWS + " AND \"p_post\" IS NOT NULL";

	private static final String SQL_COUNT_MATCHING_EP_ROWS_NON_NULL =
			SQL_COUNT_MATCHING_LOG_POST_ROWS + " AND \"influence_on_position\" IS NOT NULL";

	// DEBUG: Funnel-Abfragen, die OHNE jede enable-Einschraenkung bzw. schrittweise MIT wachsender Einschraenkung
	// zaehlen, um die Stelle zu finden, an der die Zeilenzahl fuer einen Beobachtungstyp auf 0 faellt.
	private static final String SQL_DEBUG_GROUP_COUNT_ANY =
			"SELECT COUNT(*) AS \"cnt\" FROM \"ObservationGroup\" WHERE \"type\" = ?";

	private static final String SQL_DEBUG_GROUP_COUNT_ENABLED =
			SQL_DEBUG_GROUP_COUNT_ANY + " AND \"enable\" = TRUE";

	private static final String SQL_DEBUG_APRIORI_COUNT_ANY =
			"SELECT COUNT(*) AS \"cnt\" FROM \"ObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"type\" = ?";

	private static final String SQL_DEBUG_APRIORI_COUNT_ENABLED =
			SQL_DEBUG_APRIORI_COUNT_ANY + " AND \"ObservationGroup\".\"enable\" = TRUE AND \"ObservationApriori\".\"enable\" = TRUE";

	// UNGEFILTERT (kein enable, keine p_post/influence_on_position-Bedingung) -
	// testet AUSSCHLIESSLICH, ob der ID-JOIN zwischen "ObservationAposteriori"
	// und "ObservationApriori" fuer diesen Beobachtungstyp ueberhaupt jemals
	// eine Zeile liefert (Ausschluss eines strukturellen JOIN-Fehlers).
	private static final String SQL_DEBUG_APOSTERIORI_JOIN_COUNT_ANY =
			"SELECT COUNT(*) AS \"cnt\" FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"type\" = ?";

	// DEBUG: legt die group_id-Werte auf BEIDEN Seiten des JOINs offen, um einen echten ID-Bruch von einer schlicht
	// leeren Gruppe zu unterscheiden.
	private static final String SQL_DEBUG_GROUP_IDS_FOR_TYPE =
			"SELECT \"id\" FROM \"ObservationGroup\" WHERE \"type\" = ? AND \"enable\" = TRUE";

	private static final String SQL_DEBUG_DISTINCT_GROUP_IDS_IN_APRIORI =
			"SELECT DISTINCT \"group_id\" FROM \"ObservationApriori\" ORDER BY \"group_id\" ASC";

	private static final String SQL_DEBUG_APRIORI_TOTAL_COUNT =
			"SELECT COUNT(*) AS \"cnt\" FROM \"ObservationApriori\"";

	// DEBUG: Standard-HSQLDB-Systemkatalog-Abfrage, die ALLE tatsaechlich existierenden Tabellennamen der aktuell
	// geoeffneten Datenbank auflistet - rein lesend, kein Bezug zu irgendeinem konkreten (evtl. falsch vermuteten)
	// Tabellennamen.
	private static final String SQL_DEBUG_ALL_TABLE_NAMES =
			"SELECT \"TABLE_NAME\" FROM \"INFORMATION_SCHEMA\".\"SYSTEM_TABLES\" " +
			"WHERE \"TABLE_TYPE\" = 'TABLE' ORDER BY \"TABLE_NAME\"";

	// DEBUG (temporaer): Standard-HSQLDB-Systemkatalog-Abfrage fuer die ECHTEN Spaltennamen+Typen einer konkreten
	// Tabelle - rein lesend, kein Bezug zu irgendeinem geratenen Spaltennamen.
	private static final String SQL_DEBUG_COLUMNS_FOR_TABLE =
			"SELECT \"COLUMN_NAME\", \"TYPE_NAME\" FROM \"INFORMATION_SCHEMA\".\"SYSTEM_COLUMNS\" " +
			"WHERE \"TABLE_NAME\" = ? ORDER BY \"ORDINAL_POSITION\"";

	private int debugCountRows(String countSql, int observationTypeId) throws SQLException {
		return this.debugPart.debugCountRows(countSql, observationTypeId);
	}

	// summiert die Gesamtzahl der Messachsen ueber alle 8 LOG_POST-Typen (dieselbe Typmenge wie
	// performGroupCalibrationStep()) - je Zeile 1 Achse bei den 5 generischen Typen
	// (SQL_COUNT_MATCHING_LOG_POST_ROWS), je Zeile 3 Achsen (X/Y/Z) bei den 3 GNSS-Typen
	// (SQL_COUNT_MATCHING_GNSS_ROWS)
	// dieselbe "3 Achsen pro Basislinie"-Konvention wie ueberall sonst bei GNSS in diesem Projekt (siehe z.B.
	// readAllGnssObservationsForEpCalibration()). Nutzt ausschliesslich bereits an anderer Stelle bestaetigte
	// COUNT-Abfragen
	// keine neue, unbestaetigte SQL-Struktur.
	private static final Klein_Method_Threshold_Type[] LOG_POST_TYPES_FOR_AXIS_COUNT =
			Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST);

	@Override
	public int readTotalMeasurementAxisCount() throws SQLException {
		return this.calibrationPart.readTotalMeasurementAxisCount();
	}

	public void write(Klein_Method_Parameter parameter) throws SQLException {
		this.calibrationPart.write(parameter);
	}

	@Override
	public double[] readGroupVarianceRatios(Klein_Method_Threshold_Type logPostType) throws SQLException {
		return this.calibrationPart.readGroupVarianceRatios(logPostType);
	}

	// Redundanz je Varianzkomponente
	static final String SQL_VARIANCE_REDUNDANCY =
			"SELECT \"redundancy\", \"number_of_observations\" FROM \"VarianceComponent\" WHERE \"type\" = ?";

	@Override
	public double[][] readVarianceComponentRedundancies(Klein_Method_Threshold_Type logPostType) throws SQLException {
		return this.calibrationPart.readVarianceComponentRedundancies(logPostType);
	}

	// Daten je Beobachtung fuer robuste VKS und Modellpruefung
	static final String SQL_OBSERVATION_FIT_DATA =
			"SELECT \"ObservationAposteriori\".\"omega\" AS \"omega\", \"ObservationAposteriori\".\"redundancy\" AS \"redundancy\", " +
			"\"S\".\"y0\" AS \"sy\", \"S\".\"x0\" AS \"sx\", \"E\".\"y0\" AS \"ey\", \"E\".\"x0\" AS \"ex\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"LEFT JOIN \"PointApriori\" AS \"S\" ON \"S\".\"name\" = \"ObservationApriori\".\"start_point_name\" " +
			"LEFT JOIN \"PointApriori\" AS \"E\" ON \"E\".\"name\" = \"ObservationApriori\".\"end_point_name\" " +
			"WHERE \"ObservationGroup\".\"type\" = ? AND \"ObservationGroup\".\"enable\" = TRUE AND \"ObservationApriori\".\"enable\" = TRUE";
	static final String SQL_GNSS_FIT_DATA =
			"SELECT \"GNSSObservationAposteriori\".\"omega\" AS \"omega\", \"GNSSObservationAposteriori\".\"redundancy_y\" AS \"ry\", " +
			"\"GNSSObservationAposteriori\".\"redundancy_x\" AS \"rx\", \"GNSSObservationAposteriori\".\"redundancy_z\" AS \"rz\" " +
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"type\" = ? AND \"ObservationGroup\".\"enable\" = TRUE AND \"GNSSObservationApriori\".\"enable\" = TRUE";

	@Override
	public List<double[]> readObservationFitData(Klein_Method_Threshold_Type logPostType) throws SQLException {
		return this.calibrationPart.readObservationFitData(logPostType);
	}

	@Override
	public List<Integer> readGroupIds(Klein_Method_Threshold_Type logPostType) throws SQLException {
		return this.calibrationPart.readGroupIds(logPostType);
	}

	@Override
	public double[] readGroupUncertainty(int groupId) throws SQLException {
		return this.calibrationPart.readGroupUncertainty(groupId);
	}

	@Override
	public void writeGroupUncertainty(int groupId, double sigmaA, double sigmaB, double sigmaC) throws SQLException {
		this.calibrationPart.writeGroupUncertainty(groupId, sigmaA, sigmaB, sigmaC);
	}

	// ===== Festpunktanalyse (dynamische Ausgleichung, Bewegliche Anschlusspunkte) =====
	//
	// WICHTIGE ARCHITEKTUR-ERKENNTNIS (bestaetigt anhand der echten JAG3D-
	// Quelldatei NetworkAdjustment.java): anders als bei Beobachtungen gibt
	// es KEINE separate "PointGroupUncertainty"-Tabelle - jeder einzelne
	// Punkt traegt seine eigene a-priori Unsicherheit direkt in
	// "PointApriori" (Spalten "sigma_y0"/"sigma_x0"/"sigma_z0" - Spaltenname
	// per Konvention/UI-Ressourcenschluessel "sigma.y0" erschlossen, NICHT
	// 1:1 aus SQLManager.java bestaetigt). Die "Gruppenunsicherheit"-Ansicht
	// in JAG3D ist nur ein Sammel-Editor, der beim Speichern denselben Wert
	// auf ALLE Punkte der Gruppe schreibt.
	//
	// Der 1:sigma^2-Wert dagegen ist ECHT nur je DIMENSION (1D/2D/3D)
	// gebildet - NetworkAdjustment.java akkumuliert Omega/Redundanz ALLER
	// Punkte gleicher Dimension in EINEN gemeinsamen VarianceComponent-Eintrag
	// (VarianceComponentType.getComponentTypeByPointDimension(dim)),
	// UNABHAENGIG von der Punktkategorie (Neupunkte/Datumspunkte/Anschluss-
	// punkte/Festpunkte werden NICHT getrennt ausgewiesen, wenn sie dieselbe
	// Dimension haben). Bedeutet: das 1:sigma^2 fuer "Raumfestpunkte" spiegelt
	// ALLE aktiven 3D-Punkte wider, nicht nur die Beweglichen Anschlusspunkte.

	private static final String SQL_POINT_VARIANCE_RATIO =
			"SELECT \"sigma2apost\" FROM \"VarianceComponent\" WHERE \"type\" = ?";

	@Override
	public double readPointVarianceRatio(int dimension) throws SQLException {
		if (dimension == 3 && this.horizontalOnly3D && hasOpenDatabase()) {
			// nur die Lage der beweglichen 3D-Anschlusspunkte (Hoehe ist frei)
			double omega = 0, redundancy = 0;
			for (double[] v : this.readHorizontalPointStatistics().values()) {
				omega += v[1];
				redundancy += v[2];
			}
			if (redundancy > 1e-6)
				return omega / redundancy;
		}
		return this.calibrationPart.readPointVarianceRatio(dimension);
	}

	private static final String SQL_STOCHASTIC_POINT_GROUP_IDS =
			"SELECT \"id\", \"dimension\" FROM \"PointGroup\" WHERE \"type\" = ? AND \"enable\" = TRUE ORDER BY \"id\" ASC";

	@Override
	public Map<Integer, Integer> readStochasticPointGroupIds() throws SQLException {
		return this.calibrationPart.readStochasticPointGroupIds();
	}

	// Liest die Unsicherheit EINES repraesentativen Punktes der Gruppe (erster
	// aktiver Punkt) als Ausgangswert fuer die Gruppenkalibrierung - stimmt
	// nur dann mit ALLEN Punkten der Gruppe ueberein, wenn die Gruppe bereits
	// konsistent ist (z.B. weil Klein_Method sie selbst zuletzt geschrieben hat).
	private static final String SQL_READ_POINT_UNCERTAINTY_SAMPLE =
			"SELECT \"sigma_y0\", \"sigma_x0\", \"sigma_z0\" FROM \"PointApriori\" " +
			"WHERE \"group_id\" = ? AND \"enable\" = TRUE ORDER BY \"id\" ASC";

	@Override
	public double[] readPointUncertaintySample(int groupId) throws SQLException {
		return this.calibrationPart.readPointUncertaintySample(groupId);
	}

	// Schreibt EINEN Unsicherheitswert auf ALLE aktiven Punkte einer Gruppe -
	// component: 'y'/'x'/'z'. Bewusst als Sammel-UPDATE (nicht pro einzelnem
	// Punkt einzeln), da es in JAG3D keine separate Gruppentabelle gibt.
	private static final String SQL_WRITE_POINT_UNCERTAINTY_Y =
			"UPDATE \"PointApriori\" SET \"sigma_y0\" = ? WHERE \"group_id\" = ? AND \"enable\" = TRUE";
	private static final String SQL_WRITE_POINT_UNCERTAINTY_X =
			"UPDATE \"PointApriori\" SET \"sigma_x0\" = ? WHERE \"group_id\" = ? AND \"enable\" = TRUE";
	private static final String SQL_WRITE_POINT_UNCERTAINTY_Z =
			"UPDATE \"PointApriori\" SET \"sigma_z0\" = ? WHERE \"group_id\" = ? AND \"enable\" = TRUE";

	@Override
	public void writePointGroupUncertainty(int groupId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException {
		// "Hoehe nur aus 1D" (dynamisch): die Hoehe der 3D-Anschlusspunkte wird getrennt kalibriert - hier nur die Lage
		if (dimension == 3 && this.horizontalOnly3D)
			this.calibrationPart.writePointGroupUncertainty(groupId, 2, sigmaY, sigmaX, Double.NaN);
		else
			this.calibrationPart.writePointGroupUncertainty(groupId, dimension, sigmaY, sigmaX, sigmaZ);
	}

	// Gruppenunsicherheit der beweglichen Anschlusspunkte - Tabelle "PointGroupUncertainty" (Gruppeneigenschaften in
	// JAG3D), nicht die Einzelwerte in "PointApriori".
	private static final String SQL_READ_POINT_GROUP_UNCERTAINTY =
			"SELECT \"type\", \"value\" FROM \"PointGroupUncertainty\" WHERE \"group_id\" = ?";
	private static final String SQL_MERGE_POINT_GROUP_UNCERTAINTY =
			"MERGE INTO \"PointGroupUncertainty\" USING (VALUES (CAST(? AS INT), CAST(? AS INT), CAST(? AS DOUBLE))) "
			+ "AS \"vals\" (\"group_id\", \"type\", \"value\") ON \"PointGroupUncertainty\".\"group_id\" = \"vals\".\"group_id\" "
			+ "AND \"PointGroupUncertainty\".\"type\" = \"vals\".\"type\" "
			+ "WHEN MATCHED THEN UPDATE SET \"PointGroupUncertainty\".\"value\" = \"vals\".\"value\" "
			+ "WHEN NOT MATCHED THEN INSERT VALUES \"vals\".\"group_id\", \"vals\".\"type\", \"vals\".\"value\"";

	@Override
	public double[] readPointGroupUncertainty(int groupId) throws SQLException {
		return this.calibrationPart.readPointGroupUncertainty(groupId);
	}

	@Override
	public void writePointGroupUncertaintyValues(int groupId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException {
		this.calibrationPart.writePointGroupUncertaintyValues(groupId, dimension, sigmaY, sigmaX, sigmaZ);
	}

	@Override
	public List<Klein_Method_Observation_Uncertainty_Row> readObservationsForIndividualCalibration( Klein_Method_Threshold_Type logPostType, double bandLowerBound, double bandUpperBound) throws SQLException {
		return this.calibrationPart.readObservationsForIndividualCalibration(logPostType, bandLowerBound, bandUpperBound);
	}

	private static final double UNCERTAINTY_DISTANCE_EPS = 1e-10;

	@Override
	public void writeIndividualUncertainty(int observationId, double newValue) throws SQLException {
		this.calibrationPart.writeIndividualUncertainty(observationId, newValue);
	}

	@Override
	public List<Klein_Method_Observation_Uncertainty_Row> readGnssObservationsForIndividualCalibration( Klein_Method_Threshold_Type logPostType, double bandLowerBound, double bandUpperBound) throws SQLException {
		return this.calibrationPart.readGnssObservationsForIndividualCalibration(logPostType, bandLowerBound, bandUpperBound);
	}

	@Override
	public void writeGnssIndividualUncertainty(int observationId, Klein_Method_Observation_Uncertainty_Row.GnssAxis axis, double newValue) throws SQLException {
		this.calibrationPart.writeGnssIndividualUncertainty(observationId, axis, newValue);
	}

	@Override
	public void commit() throws SQLException {
		this.calibrationPart.commit();
	}

	@Override
	public void checkpoint() throws SQLException {
		this.calibrationPart.checkpoint();
	}

	@Override
	public void commitAndCheckpoint() throws SQLException {
		this.calibrationPart.commitAndCheckpoint();
	}

	/** Sitzungen (aufeinanderfolgend, gleiches Punktpaar, gleiche Gruppe) der aktiven 3D-Basislinien: je Sitzung die Zeilen. */
	private List<List<double[]>> readGnssSessions(List<String[]> names) throws SQLException {
		List<List<double[]>> sessions = new ArrayList<List<double[]>>();
		// Mittel und wegen Sprung/Drift einzeln belassene Epochen stehen in der Rollentabelle und zaehlen nicht mehr
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_GNSS3D_FOR_MERGE);
		stmt.setInt(1, ObservationType.GNSS3D.getId());
		ResultSet rs = stmt.executeQuery();
		String lastKey = null;
		List<double[]> run = null;
		while (rs.next()) {
			String key = rs.getInt(2) + "|" + rs.getString(3) + "|" + rs.getString(4);
			if (!key.equals(lastKey)) {
				run = new ArrayList<double[]>();
				sessions.add(run);
				names.add(new String[] { rs.getString(3), rs.getString(4) });
				lastKey = key;
			}
			// {id, Gruppe, y0, x0, z0, sigma_y0, sigma_x0, sigma_z0}
			run.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10) });
		}
		return sessions;
	}

	/** Mindestens so viele Epochen je Teilstueck einer geteilten Sitzung. */
	private static final int GNSS_SESSION_MIN_SEGMENT = 3;
	/** Sprung-/Driftpruefung erst ab so vielen Epochen. */
	private static final int GNSS_SESSION_MIN_TEST = 2 * GNSS_SESSION_MIN_SEGMENT;
	/** Grenze fuer Sprung bzw. Gesamtdrift innerhalb einer Sitzung: Faktor x Epochen-Sigma, ... */
	private static final double GNSS_SESSION_JUMP_FACTOR = 3.0;
	/**
	 * ... mindestens jedoch [m] - wie die Driftpruefung der Naeherungskoordinaten (GNSS_DRIFT_LIMIT). Echte Epochen
	 * wandern innerhalb einer Sitzung um einige Zentimeter (korreliert); erst darueber ist es ein Sprung bzw. eine Drift.
	 */
	private static final double GNSS_SESSION_JUMP_MIN = 0.10;

	private List<String> lastGnssMergeFindings = new ArrayList<String>();

	@Override
	public List<String> getLastGnssMergeFindings() {
		return new ArrayList<String>(this.lastGnssMergeFindings);
	}

	/**
	 * Sprung-/Driftpruefung einer Sitzung (Zeilen {id, Gruppe, y0, x0, z0, sigma_y0, sigma_x0, sigma_z0}): je Komponente
	 * bester Sprung (Differenz der Teilmittel, beide Teile mind. GNSS_SESSION_MIN_SEGMENT Epochen) und Gesamtdrift der
	 * Ausgleichsgeraden, jeweils bezogen auf max(GNSS_SESSION_JUMP_FACTOR x Epochen-Sigma, GNSS_SESSION_JUMP_MIN).
	 * Liefert {Art, Teilungsindex, Groesse [m], Verhaeltnis zur Grenze}: Art 0 unauffaellig, 1 Sprung (teilen), 2 Drift
	 * (einzeln lassen). Ob Sprung oder Drift: das Modell mit der kleineren Restquadratsumme.
	 */
	static double[] testGnssSession(List<double[]> run) {
		return testGnssSession(run, GNSS_SESSION_JUMP_MIN);
	}

	/** wie testGnssSession(run), Mindestgrenze je Messart [m bzw. rad]. */
	static double[] testGnssSession(List<double[]> run, double jumpMin) {
		int n = run.size();
		if (n < GNSS_SESSION_MIN_TEST)
			return new double[] { 0, 0, 0, 0 };
		double bestJumpRatio = 0, bestJump = 0, rssJump = 0, rssLinear = 0, driftRatio = 0, drift = 0;
		int bestSplit = 0;
		double[] limit = new double[3];
		for (int c = 0; c < 3; c++) {
			double sigma = 0;
			for (double[] r : run)
				sigma += r[5 + c] / n;
			limit[c] = Math.max(GNSS_SESSION_JUMP_FACTOR * sigma, jumpMin);
		}
		// Sprung: ein gemeinsamer Teilungsindex fuer alle Komponenten
		// Vorsummen (zentriert, numerisch stabil): Mittel und Quadratsumme beider Teile in O(1) je Teilungsindex
		double[][] cs = new double[3][n + 1], cq = new double[3][n + 1];
		double[] ref = new double[3];
		for (int c = 0; c < 3; c++) {
			ref[c] = run.get(0)[2 + c];
			for (int i = 0; i < n; i++) {
				double x = run.get(i)[2 + c] - ref[c];
				cs[c][i + 1] = cs[c][i] + x;
				cq[c][i + 1] = cq[c][i] + x * x;
			}
		}
		for (int k = GNSS_SESSION_MIN_SEGMENT; k <= n - GNSS_SESSION_MIN_SEGMENT; k++) {
			double ratio = 0, rss = 0, size = 0;
			for (int c = 0; c < 3; c++) {
				double s1 = cs[c][k], s2 = cs[c][n] - cs[c][k];
				double m1 = s1 / k, m2 = s2 / (n - k);
				double q = (cq[c][n] - s1 * m1 - s2 * m2) / (limit[c] * limit[c]);
				rss += Math.max(0.0, q);
				if (Math.abs(m1 - m2) / limit[c] > ratio) {
					ratio = Math.abs(m1 - m2) / limit[c];
					size = Math.abs(m1 - m2);
				}
			}
			if (ratio > bestJumpRatio) {
				bestJumpRatio = ratio;
				bestJump = size;
				bestSplit = k;
				rssJump = rss;
			}
		}
		// Drift: Ausgleichsgerade ueber den Epochenindex
		double tm = (n - 1) / 2.0, stt = 0;
		for (int i = 0; i < n; i++)
			stt += (i - tm) * (i - tm);
		for (int c = 0; c < 3; c++) {
			double mean = 0, sty = 0;
			for (double[] r : run)
				mean += r[2 + c] / n;
			for (int i = 0; i < n; i++)
				sty += (i - tm) * (run.get(i)[2 + c] - mean);
			double slope = sty / stt;
			for (int i = 0; i < n; i++) {
				double v = (run.get(i)[2 + c] - mean - slope * (i - tm)) / limit[c];
				rssLinear += v * v;
			}
			double total = Math.abs(slope) * (n - 1);
			if (total / limit[c] > driftRatio) {
				driftRatio = total / limit[c];
				drift = total;
			}
		}
		if (bestJumpRatio <= 1 && driftRatio <= 1)
			return new double[] { 0, 0, 0, 0 };
		if (bestJumpRatio > 1 && rssJump <= rssLinear)
			return new double[] { 1, bestSplit, bestJump, bestJumpRatio };
		return new double[] { 2, 0, drift, driftRatio };
	}

	/**
	 * Halbe Gesamtdrift [m] je Komponente (Ausgleichsgerade ueber den Epochenindex) - wird bei driftenden Sitzungen als
	 * Genauigkeit des Mittels angesetzt, soweit die Komponente die Grenze ueberschreitet; sonst 0 (Epochen-Genauigkeit).
	 */
	static double[] gnssSessionDriftHalfRange(List<double[]> run) {
		return gnssSessionDriftHalfRange(run, GNSS_SESSION_JUMP_MIN);
	}

	/** wie gnssSessionDriftHalfRange(run), Mindestgrenze je Messart. */
	static double[] gnssSessionDriftHalfRange(List<double[]> run, double jumpMin) {
		int n = run.size();
		double[] half = new double[3];
		if (n < 2)
			return half;
		double tm = (n - 1) / 2.0, stt = 0;
		for (int i = 0; i < n; i++)
			stt += (i - tm) * (i - tm);
		for (int c = 0; c < 3; c++) {
			double mean = 0, sty = 0, sigma = 0;
			for (double[] r : run) {
				mean += r[2 + c] / n;
				sigma += r[5 + c] / n;
			}
			for (int i = 0; i < n; i++)
				sty += (i - tm) * (run.get(i)[2 + c] - mean);
			double total = Math.abs(sty / stt) * (n - 1);
			if (total > Math.max(GNSS_SESSION_JUMP_FACTOR * sigma, jumpMin))
				half[c] = total / 2;
		}
		return half;
	}

	/** Sitzung rekursiv an Spruengen teilen; driftende Teile gesondert (Mittel mit Drift-Genauigkeit). */
	private void segmentGnssSession(List<double[]> run, List<List<double[]>> toMerge, List<List<double[]>> keepSingle, String label, int depth) {
		double[] t = testGnssSession(run);
		if (t[0] == 1 && depth < 10) {
			int k = (int) t[1];
			this.lastGnssMergeFindings.add(String.format(java.util.Locale.ROOT, "%s: Sprung %.1f mm nach Epoche %d von %d - Sitzung geteilt", label, t[2] * 1000, k, run.size()));
			this.segmentGnssSession(new ArrayList<double[]>(run.subList(0, k)), toMerge, keepSingle, label, depth + 1);
			this.segmentGnssSession(new ArrayList<double[]>(run.subList(k, run.size())), toMerge, keepSingle, label, depth + 1);
		} else if (t[0] == 2) {
			this.lastGnssMergeFindings.add(String.format(java.util.Locale.ROOT, "%s: Drift %.1f mm ueber %d Epochen - Mittel mit Genauigkeit = halbe Drift (wiegt wie eine unsichere Sitzung)", label, t[2] * 1000, run.size()));
			keepSingle.add(run);
		} else if (run.size() >= 2)
			toMerge.add(run);
	}

	@Override
	public int[] mergeGnssSessions() throws SQLException {
		this.lastGnssMergeFindings = new ArrayList<String>();
		if (!hasOpenDatabase())
			return new int[] { 0, 0, 0, 0 };
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		List<String[]> names = new ArrayList<String[]>();
		List<List<double[]>> sessions = this.readGnssSessions(names);
		PreparedStatement insert = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_GNSS_MEAN);
		PreparedStatement role = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_GNSS_MERGE_ROLE);
		PreparedStatement off = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEACTIVATE_GNSS_OBSERVATION);
		int merged = 0, epochs = 0, split = 0, single = 0;
		for (int s = 0; s < sessions.size(); s++) {
			List<double[]> session = sessions.get(s);
			if (session.size() < 2)
				continue;
			List<List<double[]>> toMerge = new ArrayList<List<double[]>>(), keepSingle = new ArrayList<List<double[]>>();
			this.segmentGnssSession(session, toMerge, keepSingle, names.get(s)[0] + " - " + names.get(s)[1], 0);
			if (toMerge.size() > 1)
				split++;
			// driftende Sitzungen: ebenfalls EIN Mittel, aber mit Genauigkeit = halbe Drift je betroffener Komponente. Als
			// Einzelepochen wogen sie so viel wie viele Sitzungen und setzten die Drift durch (Simulator S5: 330 mm Hoehe).
			single += keepSingle.size();
			List<List<double[]>> all = new ArrayList<List<double[]>>(toMerge);
			all.addAll(keepSingle);
			for (int m = 0; m < all.size(); m++) {
				List<double[]> run = all.get(m);
				double[] mean = new double[8];
				for (double[] r : run)
					for (int k = 2; k < 8; k++)
						mean[k] += r[k] / run.size();
				if (m >= toMerge.size()) {
					double[] half = gnssSessionDriftHalfRange(run);
					for (int c = 0; c < 3; c++)
						if (half[c] > 0)
							mean[5 + c] = Math.max(mean[5 + c], half[c]);
				}
				insert.setInt(1, (int) run.get(0)[1]);
				insert.setString(2, names.get(s)[0]);
				insert.setString(3, names.get(s)[1]);
				for (int k = 2; k < 8; k++)
					insert.setDouble(k + 2, mean[k]);
				insert.execute();
				int newId = SQLManager.getInstance().getDataBase().getLastInsertId();
				role.setInt(1, newId);
				role.setInt(2, 1);
				role.execute();
				for (double[] r : run) {
					off.setInt(1, (int) r[0]);
					off.execute();
					role.setInt(1, (int) r[0]);
					role.setInt(2, 0);
					role.execute();
					epochs++;
				}
				merged++;
			}
		}
		return new int[] { merged, epochs, split, single };
	}

	@Override
	public int[] undoGnssSessionMerge() throws SQLException {
		if (!hasOpenDatabase())
			return new int[] { 0, 0 };
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		List<Integer> created = new ArrayList<Integer>(), deactivated = new ArrayList<Integer>();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_GNSS_MERGE).executeQuery();
		while (rs.next())
			(rs.getInt(2) == 1 ? created : deactivated).add(rs.getInt(1));
		PreparedStatement delete = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_GNSS_OBSERVATION);
		int deleted = 0, enabled = 0;
		for (int id : created) {
			delete.setInt(1, id);
			deleted += delete.executeUpdate();
		}
		PreparedStatement on = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_SET_GNSS_OBSERVATION_ENABLED);
		for (int id : deactivated) {
			on.setBoolean(1, true);
			on.setInt(2, id);
			enabled += on.executeUpdate();
		}
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CLEAR_GNSS_MERGE).execute();
		return new int[] { deleted, enabled };
	}

	@Override
	public int countMergeableGnssSessions() throws SQLException {
		if (!hasOpenDatabase())
			return 0;
		int n = 0;
		for (List<double[]> run : this.readGnssSessions(new ArrayList<String[]>()))
			if (run.size() >= 2)
				n++;
		return n;
	}

	@Override
	public boolean hasGnssSessionMerge() throws SQLException {
		if (!hasOpenDatabase())
			return false;
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_COUNT_GNSS_MERGE).executeQuery();
		return rs.next() && rs.getInt(1) > 0;
	}

	// ============================================================================================================
	// Wiederholte Messungen (Uebergewicht): je Messart "Regewichten" (Einzel-Sigma x Wurzel(n), von der Kalibrierung
	// ausgenommen) oder "Zusammenfassen" (eine Mittelbeobachtung je Wiederholungsgruppe, Einzelwerte deaktiviert).
	// Wiederholungsgruppe = direkt aufeinanderfolgende aktive Zeilen derselben Gruppe mit gleichem Standpunkt/Ziel
	// (terrestrisch zusaetzlich gleiche Instrumenten- und Zielhoehe). Merktabelle "KleinMethodRepetition":
	// kind 0 terrestrisch / 1 GNSS, role 0 deaktivierte Einzelbeobachtung, 1 angelegtes Mittel, 2 regewichtet
	// (s0..s2 = alte gespeicherte Sigmas).

	/** sorgt fuer die Merktabelle (Wiederholungs-Filter in der Abfrage) und liefert die Abfrage unveraendert. */
	private static String withRepetitionTable(String sql) throws SQLException {
		ensureRepetitionTable();
		return sql;
	}

	static final int REP_KIND_TERRESTRIAL = 0, REP_KIND_GNSS = 1;
	static final int REP_ROLE_SINGLE_OFF = 0, REP_ROLE_MEAN = 1, REP_ROLE_REWEIGHTED = 2, REP_ROLE_MEAN_CALIBRATED = 3;
	private static final String SQL_CREATE_REPETITION = "CREATE TABLE IF NOT EXISTS \"KleinMethodRepetition\" (\"kind\" SMALLINT NOT NULL, "
			+ "\"id\" INTEGER NOT NULL, \"obs_type\" INTEGER NOT NULL, \"role\" SMALLINT NOT NULL, \"s0\" DOUBLE, \"s1\" DOUBLE, \"s2\" DOUBLE, "
			+ "PRIMARY KEY (\"kind\", \"id\"))";
	private static final String SQL_INSERT_REPETITION = "INSERT INTO \"KleinMethodRepetition\" (\"kind\", \"id\", \"obs_type\", \"role\", \"s0\", \"s1\", \"s2\") "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?)";
	private static final String SQL_READ_REPETITION_OF_TYPE = "SELECT \"kind\", \"id\", \"role\", \"s0\", \"s1\", \"s2\" FROM \"KleinMethodRepetition\" "
			+ "WHERE \"obs_type\" = ?";
	private static final String SQL_DELETE_REPETITION_OF_TYPE = "DELETE FROM \"KleinMethodRepetition\" WHERE \"obs_type\" = ?";
	private static final String SQL_READ_FROZEN = "SELECT \"kind\", \"id\" FROM \"KleinMethodRepetition\" WHERE \"role\" = 2";
	// alle aktiven Zeilen plus die von Klein_Method fuer eine Wiederholung deaktivierten (sie bestimmen die Nachbarschaft
	// mit); letzte Spalte: bereits behandelt (1) - Gruppen mit behandelten Zeilen werden uebersprungen
	private static final String SQL_TERRESTRIAL_FOR_REPETITION =
			"SELECT o.\"id\", o.\"group_id\", o.\"start_point_name\", o.\"end_point_name\", o.\"instrument_height\", o.\"reflector_height\", "
			+ "o.\"value_0\", o.\"sigma_0\", o.\"distance_0\", PS.\"y0\", PS.\"x0\", PS.\"z0\", PE.\"y0\", PE.\"x0\", PE.\"z0\", "
			+ "CASE WHEN r.\"id\" IS NULL THEN 0 ELSE 1 END "
			+ "FROM \"ObservationApriori\" o JOIN \"ObservationGroup\" g ON o.\"group_id\" = g.\"id\" "
			+ "LEFT JOIN \"KleinMethodRepetition\" r ON r.\"kind\" = 0 AND r.\"id\" = o.\"id\" "
			+ "LEFT JOIN \"PointApriori\" PS ON PS.\"name\" = o.\"start_point_name\" LEFT JOIN \"PointApriori\" PE ON PE.\"name\" = o.\"end_point_name\" "
			+ "WHERE (o.\"enable\" = TRUE OR r.\"role\" = 0) AND g.\"enable\" = TRUE AND g.\"type\" = ? ORDER BY o.\"id\" ASC";
	private static final String SQL_GNSS_FOR_REPETITION =
			"SELECT o.\"id\", o.\"group_id\", o.\"start_point_name\", o.\"end_point_name\", o.\"y0\", o.\"x0\", o.\"z0\", "
			+ "o.\"sigma_y0\", o.\"sigma_x0\", o.\"sigma_z0\", CASE WHEN r.\"id\" IS NULL AND m.\"id\" IS NULL THEN 0 ELSE 1 END "
			+ "FROM \"GNSSObservationApriori\" o JOIN \"ObservationGroup\" g ON o.\"group_id\" = g.\"id\" "
			+ "LEFT JOIN \"KleinMethodRepetition\" r ON r.\"kind\" = 1 AND r.\"id\" = o.\"id\" "
			+ "LEFT JOIN \"KleinMethodGnssSessionMerge\" m ON m.\"id\" = o.\"id\" "
			+ "WHERE (o.\"enable\" = TRUE OR r.\"role\" = 0 OR m.\"role\" = 0) AND g.\"enable\" = TRUE AND g.\"type\" = ? ORDER BY o.\"id\" ASC";
	private static final String SQL_GROUP_UNCERTAINTY_ROWS = "SELECT \"type\", \"value\" FROM \"ObservationGroupUncertainty\" WHERE \"group_id\" = ?";
	private static final String SQL_INSERT_TERRESTRIAL_MEAN = "INSERT INTO \"ObservationApriori\" (\"id\", \"group_id\", \"start_point_name\", "
			+ "\"end_point_name\", \"instrument_height\", \"reflector_height\", \"value_0\", \"sigma_0\", \"distance_0\", \"enable\") "
			+ "VALUES (DEFAULT, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)";
	private static final String SQL_SET_TERRESTRIAL_ENABLED = "UPDATE \"ObservationApriori\" SET \"enable\" = ? WHERE \"id\" = ?";
	private static final String SQL_DELETE_TERRESTRIAL = "DELETE FROM \"ObservationApriori\" WHERE \"id\" = ?";
	private static final String SQL_SET_GNSS_SIGMAS = "UPDATE \"GNSSObservationApriori\" SET \"sigma_y0\" = ?, \"sigma_x0\" = ?, \"sigma_z0\" = ? WHERE \"id\" = ?";

	private static volatile String repetitionTableKey = null;
	private volatile java.util.Set<Long> frozenRepetitionKeys = null;
	private volatile String frozenRepetitionFor = null;

	private static String currentDatabaseKey() {
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		return System.identityHashCode(db) + "|" + db.getURI();
	}

	/** Merktabelle anlegen (einmal je geoeffnetem Projekt); vor jeder Abfrage mit Wiederholungs-Filter. */
	static void ensureRepetitionTable() throws SQLException {
		if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
			return;
		String key = currentDatabaseKey();
		if (key.equals(repetitionTableKey))
			return;
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_REPETITION).execute();
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		repetitionTableKey = key;
		purgeOrphanRepetitions();
	}

	/**
	 * Merkeintraege, deren Beobachtung es nicht mehr gibt (Projekt neu importiert, Vorlage aus einem frueheren Projekt),
	 * verwerfen. Sonst gilt eine Messart als schon zusammengefasst und Massen-Basislinien werden nicht ausgeduennt.
	 */
	private static final String SQL_PURGE_ORPHAN_TERRESTRIAL = "DELETE FROM \"KleinMethodRepetition\" r WHERE r.\"kind\" = 0 "
			+ "AND NOT EXISTS (SELECT 1 FROM \"ObservationApriori\" o WHERE o.\"id\" = r.\"id\")";
	private static final String SQL_PURGE_ORPHAN_GNSS = "DELETE FROM \"KleinMethodRepetition\" r WHERE r.\"kind\" = 1 "
			+ "AND NOT EXISTS (SELECT 1 FROM \"GNSSObservationApriori\" o WHERE o.\"id\" = r.\"id\")";
	private static final String SQL_PURGE_ORPHAN_GNSS_MERGE = "DELETE FROM \"KleinMethodGnssSessionMerge\" m "
			+ "WHERE NOT EXISTS (SELECT 1 FROM \"GNSSObservationApriori\" o WHERE o.\"id\" = m.\"id\")";
	private static volatile int lastOrphanRepetitionsPurged = 0;

	private static void purgeOrphanRepetitions() {
		int n = 0;
		try {
			org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
			n += db.getPreparedStatement(SQL_PURGE_ORPHAN_TERRESTRIAL).executeUpdate();
			n += db.getPreparedStatement(SQL_PURGE_ORPHAN_GNSS).executeUpdate();
			n += db.getPreparedStatement(SQL_PURGE_ORPHAN_GNSS_MERGE).executeUpdate();
		} catch (SQLException e) {
			// Projekt ohne Beobachtungstabellen: nichts zu bereinigen
		}
		if (n > 0) {
			// Pruefsummen-Baseline und erledigte Stufe gehoeren zu den frueheren Daten - sonst sperrt die
			// Reihenfolgepruefung den Start der Automatik ("Falsche Reihenfolge")
			try {
				SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodStepChecksum\"").executeUpdate();
			} catch (SQLException e) {
				// Tabelle noch nicht angelegt
			}
		}
		lastOrphanRepetitionsPurged = n;
	}

	/** nach dem Oeffnen: Merktabelle pruefen und verwaiste Eintraege verwerfen; Rueckgabe = Anzahl verworfen. */
	public static int prepareRepetitionTableAfterOpen() {
		try {
			ensureRepetitionTable();
		} catch (SQLException e) {
			return 0;
		}
		return takeOrphanRepetitionsPurged();
	}

	/** Anzahl der beim letzten Oeffnen verworfenen verwaisten Merkeintraege (und setzt sie zurueck). */
	public static int takeOrphanRepetitionsPurged() {
		int n = lastOrphanRepetitionsPurged;
		lastOrphanRepetitionsPurged = 0;
		return n;
	}

	/** nach einem Projektwechsel: Merktabelle beim naechsten Zugriff erneut pruefen. */
	public static void invalidateRepetitionCache() {
		repetitionTableKey = null;
	}

	/** true = regewichtete Beobachtung, Kalibrierung darf ihr Sigma nicht aendern. */
	boolean isRepetitionFrozen(int kind, int id) {
		try {
			if (!hasOpenDatabase())
				return false;
			ensureRepetitionTable();
			String key = currentDatabaseKey();
			java.util.Set<Long> keys = this.frozenRepetitionKeys;
			if (keys == null || !key.equals(this.frozenRepetitionFor)) {
				keys = new HashSet<Long>();
				ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_FROZEN).executeQuery();
				while (rs.next())
					keys.add(((long) rs.getInt(1) << 32) | (rs.getInt(2) & 0xffffffffL));
				this.frozenRepetitionKeys = keys;
				this.frozenRepetitionFor = key;
			}
			return keys.contains(((long) kind << 32) | (id & 0xffffffffL));
		} catch (SQLException e) {
			return false;
		}
	}

	private static boolean isGnssType(ObservationType type) {
		return type == ObservationType.GNSS1D || type == ObservationType.GNSS2D || type == ObservationType.GNSS3D;
	}

	/** Mindestgrenze fuer Sprung/Drift innerhalb einer Wiederholungsgruppe [m bzw. rad]. */
	static double repetitionJumpMin(ObservationType type) {
		switch (type) {
		case GNSS1D: case GNSS2D: case GNSS3D: return GNSS_SESSION_JUMP_MIN;
		case LEVELING: return Klein_Method_Default_Values.REPETITION_JUMP_MIN_LEVELING_M;
		case DIRECTION: case ZENITH_ANGLE: return Klein_Method_Default_Values.REPETITION_JUMP_MIN_ANGLE_MGON * Math.PI / 200000.0;
		default: return Klein_Method_Default_Values.REPETITION_JUMP_MIN_DISTANCE_M;
		}
	}

	/** eine Beobachtung einer Wiederholungsgruppe. */
	private static final class Rep_Observation {
		int id, group;
		boolean handled;
		String from, to;
		double ih, th, distance0;
		final double[] value = new double[3], stored = new double[3], effective = new double[3];
	}

	/** Gruppenunsicherheit (a, b, c) roh mit den JAG3D-Standardwerten fuer fehlende/ungueltige Eintraege. */
	private double[] repetitionGroupSigma(int groupId, ObservationType type, Map<Integer, double[]> cache) throws SQLException {
		double[] s = cache.get(groupId);
		if (s != null)
			return s;
		double a = Double.NaN, b = Double.NaN, c = Double.NaN;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GROUP_UNCERTAINTY_ROWS);
		stmt.setInt(1, groupId);
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			int t = rs.getInt(1);
			double v = rs.getDouble(2);
			if (t == ObservationGroupUncertaintyType.ZERO_POINT_OFFSET.getId())
				a = v;
			else if (t == ObservationGroupUncertaintyType.SQUARE_ROOT_DISTANCE_DEPENDENT.getId())
				b = v;
			else if (t == ObservationGroupUncertaintyType.DISTANCE_DEPENDENT.getId())
				c = v;
		}
		double da, db, dc;
		switch (type) {
		case LEVELING:
			da = DefaultUncertainty.getUncertaintyLevelingZeroPointOffset(); db = DefaultUncertainty.getUncertaintyLevelingSquareRootDistanceDependent();
			dc = DefaultUncertainty.getUncertaintyLevelingDistanceDependent(); break;
		case DIRECTION: case ZENITH_ANGLE:
			da = DefaultUncertainty.getUncertaintyAngleZeroPointOffset(); db = DefaultUncertainty.getUncertaintyAngleSquareRootDistanceDependent();
			dc = DefaultUncertainty.getUncertaintyAngleDistanceDependent(); break;
		case HORIZONTAL_DISTANCE: case SLOPE_DISTANCE:
			da = DefaultUncertainty.getUncertaintyDistanceZeroPointOffset(); db = DefaultUncertainty.getUncertaintyDistanceSquareRootDistanceDependent();
			dc = DefaultUncertainty.getUncertaintyDistanceDistanceDependent(); break;
		default:
			da = DefaultUncertainty.getUncertaintyGNSSZeroPointOffset(); db = DefaultUncertainty.getUncertaintyGNSSSquareRootDistanceDependent();
			dc = DefaultUncertainty.getUncertaintyGNSSDistanceDependent(); break;
		}
		s = new double[] { Double.isNaN(a) || a <= 0 ? da : a, Double.isNaN(b) || b < 0 ? db : b, Double.isNaN(c) || c < 0 ? dc : c };
		cache.put(groupId, s);
		return s;
	}

	/** Sigma aus dem Gruppenmodell wie JAG3D (ObservationGroup.getStd), roh. */
	static double repetitionModelSigma(ObservationType type, double[] abc, double distance) {
		double sa = abc[0], sb, sc;
		if (type == ObservationType.DIRECTION || type == ObservationType.ZENITH_ANGLE) {
			sb = distance > 0 ? abc[1] / Math.sqrt(distance) : 0.0;
			sc = distance > 0 ? abc[2] / distance : 0.0;
		} else {
			sb = abc[1] * Math.sqrt(Math.max(0.0, distance) / 1000.0);
			sc = abc[2] * Math.max(0.0, distance);
		}
		return Math.sqrt(sa * sa + sb * sb + sc * sc);
	}

	/** aktive, noch nicht behandelte Beobachtungen einer Messart, zu Wiederholungsgruppen zusammengefasst. */
	private List<List<Rep_Observation>> readRepetitionRuns(ObservationType type, boolean withSigma) throws SQLException {
		ensureRepetitionTable();
		List<List<Rep_Observation>> runs = new ArrayList<List<Rep_Observation>>();
		boolean gnss = isGnssType(type);
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(gnss ? SQL_GNSS_FOR_REPETITION : SQL_TERRESTRIAL_FOR_REPETITION);
		stmt.setInt(1, type.getId());
		ResultSet rs = stmt.executeQuery();
		Map<Integer, double[]> groupSigma = new java.util.HashMap<Integer, double[]>();
		Map<String, List<Rep_Observation>> byPair = new java.util.HashMap<String, List<Rep_Observation>>();
		String lastKey = null;
		List<Rep_Observation> run = null;
		while (rs.next()) {
			Rep_Observation o = new Rep_Observation();
			o.id = rs.getInt(1);
			o.group = rs.getInt(2);
			o.from = rs.getString(3);
			o.to = rs.getString(4);
			o.handled = rs.getInt(gnss ? 11 : 16) == 1;
			String key;
			if (gnss) {
				for (int c = 0; c < 3; c++) {
					o.value[c] = rs.getDouble(5 + c);
					o.stored[c] = rs.getDouble(8 + c);
				}
				key = o.group + "|" + o.from + "|" + o.to;
			} else {
				o.ih = rs.getDouble(5);
				o.th = rs.getDouble(6);
				o.value[0] = rs.getDouble(7);
				o.stored[0] = rs.getDouble(8);
				o.distance0 = rs.getDouble(9);
				if (!(o.distance0 > UNCERTAINTY_DISTANCE_EPS)) {
					double sy = rs.getDouble(10); boolean missing = rs.wasNull();
					double sx = rs.getDouble(11); missing |= rs.wasNull();
					double sz = rs.getDouble(12); missing |= rs.wasNull();
					double ey = rs.getDouble(13); missing |= rs.wasNull();
					double ex = rs.getDouble(14); missing |= rs.wasNull();
					double ez = rs.getDouble(15); missing |= rs.wasNull();
					if (!missing) {
						double dy = ey - sy, dx = ex - sx, dz = (ez + o.th) - (sz + o.ih);
						boolean threeD = type == ObservationType.SLOPE_DISTANCE || type == ObservationType.ZENITH_ANGLE;
						o.distance0 = threeD ? Math.sqrt(dy * dy + dx * dx + dz * dz) : Math.hypot(dy, dx);
					} else
						o.distance0 = 0.0;
				}
				key = o.group + "|" + o.from + "|" + o.to + "|" + String.format(java.util.Locale.ROOT, "%.4f|%.4f", o.ih, o.th);
			}
			if (withSigma && !o.handled) {
				double[] abc = this.repetitionGroupSigma(o.group, type, groupSigma);
				for (int c = 0; c < (gnss ? 3 : 1); c++) {
					double distance = gnss ? Math.abs(o.value[c]) : o.distance0;
					o.effective[c] = o.stored[c] > 0 ? o.stored[c] : repetitionModelSigma(type, abc, distance);
				}
			}
			// behandelte Zeilen trennen nur (eigene Gruppe), sie verbinden keine Nachbarn
			if (o.handled)
				key = "#" + o.id;
			if (gnss && this.repetitionByPair && !o.handled) {
				// Massen-Basislinien: Epochen verschiedener Stationen wechseln sich ab - je Punktpaar sammeln (Reihenfolge bleibt)
				// ohne Gruppen-Id: JAG3D legt beim Import mit Gruppentrennung je Wechsel des Standpunkts eine neue Gruppe an
				String pairKey = o.from + "|" + o.to;
				List<Rep_Observation> pair = byPair.get(pairKey);
				if (pair == null) {
					pair = new ArrayList<Rep_Observation>();
					byPair.put(pairKey, pair);
					runs.add(pair);
				}
				pair.add(o);
				lastKey = null;
				continue;
			}
			if (!key.equals(lastKey)) {
				run = new ArrayList<Rep_Observation>();
				runs.add(run);
				lastKey = key;
			}
			run.add(o);
		}
		// Gruppen mit bereits behandelten Zeilen (Mittel, deaktivierte Einzelwerte, regewichtete) sind erledigt
		List<List<Rep_Observation>> open = new ArrayList<List<Rep_Observation>>();
		for (List<Rep_Observation> r : runs) {
			boolean handled = false;
			for (Rep_Observation o : r)
				handled |= o.handled;
			if (!handled)
				open.add(r);
		}
		runs = open;
		// Richtungen: Werte einer Gruppe um 0/400 gon stetig machen (bezogen auf den ersten Wert)
		if (type == ObservationType.DIRECTION)
			for (List<Rep_Observation> r : runs)
				for (Rep_Observation o : r)
					o.value[0] = r.get(0).value[0] + Math.IEEEremainder(o.value[0] - r.get(0).value[0], 2.0 * Math.PI);
		return runs;
	}

	/** {Gruppen mit mehr als einer Beobachtung, Beobachtungen darin} der noch nicht behandelten Wiederholungen. */
	@Override
	public int[] countRepetitions(ObservationType type) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return new int[] { 0, 0 };
		int groups = 0, observations = 0;
		for (List<Rep_Observation> run : this.readRepetitionRuns(type, false))
			if (run.size() >= 2) {
				groups++;
				observations += run.size();
			}
		return new int[] { groups, observations };
	}

	/** angewendete Behandlung der Messart: 0 keine, 1 zusammengefasst, 2 regewichtet, 3 zusammengefasst und kalibriert. */
	@Override
	public int appliedRepetitionMode(ObservationType type) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		ensureRepetitionTable();
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_REPETITION_OF_TYPE);
		stmt.setInt(1, type.getId());
		ResultSet rs = stmt.executeQuery();
		int mode = 0;
		while (rs.next())
			mode = Math.max(mode, rs.getInt(3) == REP_ROLE_REWEIGHTED ? 2 : rs.getInt(3) == REP_ROLE_MEAN_CALIBRATED ? 3 : 1);
		if (mode == 0 && type == ObservationType.GNSS3D && this.hasGnssSessionMerge())
			mode = 1;
		return mode;
	}

	private List<String> lastRepetitionFindings = new ArrayList<String>();

	@Override
	public List<String> getLastRepetitionFindings() {
		return new ArrayList<String>(this.lastRepetitionFindings);
	}

	/** Wiederholungsgruppe an Spruengen teilen; driftende Teile gesondert (wie segmentGnssSession). */
	private void segmentRepetition(List<Rep_Observation> run, List<List<Rep_Observation>> normal, List<List<Rep_Observation>> drifting,
			ObservationType type, String label, int depth) {
		double jumpMin = repetitionJumpMin(type);
		List<double[]> rows = new ArrayList<double[]>();
		for (Rep_Observation o : run)
			rows.add(new double[] { o.id, o.group, o.value[0], o.value[1], o.value[2], o.effective[0], o.effective[1], o.effective[2] });
		double[] t = testGnssSession(rows, jumpMin);
		boolean angle = type == ObservationType.DIRECTION || type == ObservationType.ZENITH_ANGLE;
		String unit = angle ? "mgon" : "mm";
		double scale = angle ? 200000.0 / Math.PI : 1000.0;
		if (t[0] == 1 && depth < 10) {
			int k = (int) t[1];
			this.lastRepetitionFindings.add(String.format(java.util.Locale.ROOT, "%s %s: Sprung %.1f %s nach Messung %d von %d - Gruppe geteilt",
					type.name(), label, t[2] * scale, unit, k, run.size()));
			this.segmentRepetition(new ArrayList<Rep_Observation>(run.subList(0, k)), normal, drifting, type, label, depth + 1);
			this.segmentRepetition(new ArrayList<Rep_Observation>(run.subList(k, run.size())), normal, drifting, type, label, depth + 1);
		} else if (t[0] == 2) {
			this.lastRepetitionFindings.add(String.format(java.util.Locale.ROOT, "%s %s: Drift %.1f %s ueber %d Messungen - Genauigkeit = halbe Drift",
					type.name(), label, t[2] * scale, unit, run.size()));
			drifting.add(run);
		} else if (run.size() >= 2)
			normal.add(run);
	}

	/** halbe Drift je Komponente einer driftenden Gruppe (0 = Komponente unauffaellig). */
	private static double[] repetitionHalfDrift(List<Rep_Observation> run, ObservationType type) {
		List<double[]> rows = new ArrayList<double[]>();
		for (Rep_Observation o : run)
			rows.add(new double[] { o.id, o.group, o.value[0], o.value[1], o.value[2], o.effective[0], o.effective[1], o.effective[2] });
		return gnssSessionDriftHalfRange(rows, repetitionJumpMin(type));
	}

	/**
	 * Wiederholungen einer Messart behandeln: mode 1 zusammenfassen, 2 regewichten, 3 zusammenfassen und Mittel mit eigener
	 * Genauigkeit (Streuung/Drift) in der Automatik kalibrieren. Liefert {Gruppen, Beobachtungen, geteilt (Sprung),
	 * driftend, mittlerer Faktor Wurzel(n), stark streuend, Mittel mit eigener Genauigkeit}.
	 */
	@Override
	public double[] applyRepetitionHandling(ObservationType type, int mode) throws SQLException {
		this.lastRepetitionFindings = new ArrayList<String>();
		if (!hasOpenDatabase() || !this.hasProjectTables() || (mode != 1 && mode != 2 && mode != 3))
			return new double[7];
		boolean gnss = isGnssType(type);
		int kind = gnss ? REP_KIND_GNSS : REP_KIND_TERRESTRIAL;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		PreparedStatement mark = db.getPreparedStatement(SQL_INSERT_REPETITION);
		int groups = 0, observations = 0, split = 0, drift = 0, scatterGroups = 0, ownSigma = 0, rejected = 0;
		double sumFactor = 0;
		for (List<Rep_Observation> run : this.readRepetitionRuns(type, true)) {
			if (run.size() < 2)
				continue;
			List<List<Rep_Observation>> normal = new ArrayList<List<Rep_Observation>>(), drifting = new ArrayList<List<Rep_Observation>>();
			this.segmentRepetition(run, normal, drifting, type, run.get(0).from + " - " + run.get(0).to, 0);
			if (normal.size() + drifting.size() > 1)
				split++;
			drift += drifting.size();
			List<List<Rep_Observation>> all = new ArrayList<List<Rep_Observation>>(normal);
			all.addAll(drifting);
			for (int m = 0; m < all.size(); m++) {
				List<Rep_Observation> part = all.get(m);
				int n = part.size();
				if (n < 2)
					continue;
				double[] half = m >= normal.size() ? repetitionHalfDrift(part, type) : new double[3];
				groups++;
				observations += n;
				if (mode == 2) {
					double factor = Math.sqrt(n);
					sumFactor += factor;
					for (Rep_Observation o : part) {
						double[] sigma = new double[3];
						for (int c = 0; c < (gnss ? 3 : 1); c++)
							sigma[c] = Math.max(o.effective[c], half[c]) * factor;
						if (gnss) {
							PreparedStatement set = db.getPreparedStatement(SQL_SET_GNSS_SIGMAS);
							set.setDouble(1, sigma[0]);
							set.setDouble(2, sigma[1]);
							set.setDouble(3, sigma[2]);
							set.setInt(4, o.id);
							set.execute();
						} else {
							PreparedStatement set = db.getPreparedStatement(SQL_WRITE_INDIVIDUAL_UNCERTAINTY);
							set.setDouble(1, sigma[0]);
							set.setInt(2, o.id);
							set.execute();
						}
						this.markRepetition(mark, kind, o.id, type, REP_ROLE_REWEIGHTED, o.stored);
					}
				} else {
					sumFactor += 1.0;
					List<Rep_Observation> use = gnss && this.repetitionByPair ? robustSubset(part) : part;
					rejected += part.size() - use.size();
					n = use.size();
					double[] mean = new double[3], meanStored = new double[3], meanEffective = new double[3];
					boolean[] individual = new boolean[] { true, true, true };
					double distance = 0;
					for (Rep_Observation o : use) {
						for (int c = 0; c < 3; c++) {
							mean[c] += o.value[c] / n;
							meanStored[c] += o.stored[c] / n;
							meanEffective[c] += o.effective[c] / n;
							if (!(o.stored[c] > 0))
								individual[c] = false;
						}
						distance += o.distance0 / n;
					}
					// Genauigkeit des Mittels = die einer Einzelmessung (NICHT durch Wurzel(n) geteilt): individuell gemittelt
					// bzw. 0 = Gruppenwert; driftende Gruppe: halbe Drift, dann alle Komponenten ausdruecklich (GNSS)
					double[] sigma = new double[3];
					boolean driftSigma = false, scattered = false;
					boolean massPair = gnss && this.repetitionByPair;
					// nur die Komponente, die wirklich driftet bzw. streut, bekommt eine eigene Genauigkeit - die uebrigen
					// behalten ihren Wert bzw. 0 = Gruppenwert und werden mit der Gruppe weiter kalibriert (frueher wurden
					// dann alle drei Komponenten mit dem Gruppenmodell DIESES Zeitpunkts fest eingetragen und eingefroren)
					for (int c = 0; c < 3; c++) {
						sigma[c] = individual[c] ? meanStored[c] : 0.0;
						if (half[c] > 0 && half[c] > meanEffective[c]) {
							sigma[c] = half[c];
							driftSigma = true;
						}
					}
					if (massPair && n >= 3) {
						// Massen-Basislinien: Genauigkeit je Komponente = robuste Streuung der verwendeten Epochen (1,4826 MAD,
						// nicht durch Wurzel(n) geteilt - die Epochen sind zeitlich korreliert), mindestens 1 mm; Drift zaehlt mit
						double[] robust = robustScatter(use);
						for (int c = 0; c < 3; c++)
							sigma[c] = Math.max(Math.max(robust[c], Klein_Method_Default_Values.MASS_SIGMA_MIN), half[c]);
						driftSigma = true;
						double horizontal = Math.hypot(robust[0], robust[1]);
						if (horizontal > Klein_Method_Default_Values.MASS_SCATTER_WARN) {
							scattered = true;
							this.lastRepetitionFindings.add(String.format(java.util.Locale.ROOT, "STARK STREUEND %s - %s: Epochen streuen in der Lage %.0f mm, "
									+ "Hoehe %.0f mm (%d Epochen) - Float-Loesung oder Antenne bewegt? Genauigkeit des Mittels = Streuung",
									first0(part).from, first0(part).to, horizontal * 1000.0, robust[2] * 1000.0, n));
						}
					}
					// stark streuende Gruppe (ohne Sprung/Drift): Genauigkeit mindestens die Streuung der Einzelmessungen
					if (!massPair && n >= 3)
						for (int c = 0; c < (gnss ? 3 : 1); c++) {
							double sum2 = 0;
							for (Rep_Observation o : use)
								sum2 += (o.value[c] - mean[c]) * (o.value[c] - mean[c]);
							double scatter = Math.sqrt(sum2 / (n - 1));
							if (scatter > meanEffective[c] && meanEffective[c] > 0) {
								sigma[c] = scatter;
								driftSigma = true;
								scattered = true;
							}
						}
					if (scattered)
						scatterGroups++;
					if (type == ObservationType.DIRECTION) {
						double wrapped = Math.IEEEremainder(mean[0], 2.0 * Math.PI);
						mean[0] = wrapped < 0 ? wrapped + 2.0 * Math.PI : wrapped;
					}
					Rep_Observation first = part.get(0);
					int newId;
					if (gnss) {
						PreparedStatement insert = db.getPreparedStatement(SQL_INSERT_GNSS_MEAN);
						insert.setInt(1, first.group);
						insert.setString(2, first.from);
						insert.setString(3, first.to);
						for (int c = 0; c < 3; c++) {
							insert.setDouble(4 + c, mean[c]);
							insert.setDouble(7 + c, sigma[c]);
						}
						insert.execute();
						newId = db.getLastInsertId();
					} else {
						PreparedStatement insert = db.getPreparedStatement(SQL_INSERT_TERRESTRIAL_MEAN);
						insert.setInt(1, first.group);
						insert.setString(2, first.from);
						insert.setString(3, first.to);
						insert.setDouble(4, first.ih);
						insert.setDouble(5, first.th);
						insert.setDouble(6, mean[0]);
						insert.setDouble(7, sigma[0]);
						insert.setDouble(8, first.distance0 > UNCERTAINTY_DISTANCE_EPS ? distance : 0.0);
						insert.execute();
						newId = db.getLastInsertId();
					}
					// zusammenfassen und kalibrieren: alle Mittel als solche gemerkt (Modus erkennbar), s0 = 1 bei eigener Genauigkeit
					if (mode == 3) {
						if (driftSigma)
							ownSigma++;
						this.markRepetition(mark, kind, newId, type, REP_ROLE_MEAN_CALIBRATED, new double[] { driftSigma ? 1.0 : 0.0, 0.0, 0.0 });
					} else
						this.markRepetition(mark, kind, newId, type, REP_ROLE_MEAN, new double[3]);
					for (Rep_Observation o : part) {
						if (gnss) {
							PreparedStatement off = db.getPreparedStatement(SQL_DEACTIVATE_GNSS_OBSERVATION);
							off.setInt(1, o.id);
							off.execute();
						} else {
							PreparedStatement off = db.getPreparedStatement(SQL_SET_TERRESTRIAL_ENABLED);
							off.setBoolean(1, false);
							off.setInt(2, o.id);
							off.execute();
						}
						this.markRepetition(mark, kind, o.id, type, REP_ROLE_SINGLE_OFF, o.stored);
					}
				}
			}
		}
		this.frozenRepetitionKeys = null;
		this.calibrationPart.deleteBestState();
		return new double[] { groups, observations, split, drift, groups > 0 ? sumFactor / groups : 0.0, scatterGroups, ownSigma, rejected };
	}

	/**
	 * robuste Auswahl der Epochen eines Punktpaars (Massen-Basislinien): je Komponente Median und MAD, verworfen wird jede
	 * Epoche, die in einer Komponente mehr als 3 robuste Streuungen (mindestens 1 mm) vom Median abweicht. Unter 10 Epochen
	 * unveraendert; bleibt weniger als die Haelfte, unveraendert (dann ist das Paar insgesamt unruhig - Streuung als Genauigkeit).
	 */
	private static Rep_Observation first0(List<Rep_Observation> part) {
		return part.get(0);
	}

	/** robuste Streuung (1,4826 x MAD) je Komponente. */
	static double[] robustScatter(List<Rep_Observation> part) {
		int n = part.size();
		double[] out = new double[3];
		if (n < 2)
			return out;
		for (int c = 0; c < 3; c++) {
			double[] v = new double[n];
			for (int i = 0; i < n; i++)
				v[i] = part.get(i).value[c];
			java.util.Arrays.sort(v);
			double median = n % 2 == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
			for (int i = 0; i < n; i++)
				v[i] = Math.abs(part.get(i).value[c] - median);
			java.util.Arrays.sort(v);
			out[c] = 1.4826 * (n % 2 == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]));
		}
		return out;
	}

	static List<Rep_Observation> robustSubset(List<Rep_Observation> part) {
		int n = part.size();
		if (n < 10)
			return part;
		double[] median = new double[3], limit = new double[3];
		for (int c = 0; c < 3; c++) {
			double[] v = new double[n];
			for (int i = 0; i < n; i++)
				v[i] = part.get(i).value[c];
			java.util.Arrays.sort(v);
			median[c] = n % 2 == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
			for (int i = 0; i < n; i++)
				v[i] = Math.abs(part.get(i).value[c] - median[c]);
			java.util.Arrays.sort(v);
			double mad = n % 2 == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
			limit[c] = Math.max(3.0 * 1.4826 * mad, 0.001);
		}
		List<Rep_Observation> keep = new ArrayList<Rep_Observation>();
		for (Rep_Observation o : part) {
			boolean ok = true;
			for (int c = 0; c < 3; c++)
				ok &= Math.abs(o.value[c] - median[c]) <= limit[c];
			if (ok)
				keep.add(o);
		}
		return keep.size() * 2 >= n ? keep : part;
	}

	private void markRepetition(PreparedStatement mark, int kind, int id, ObservationType type, int role, double[] stored) throws SQLException {
		mark.setInt(1, kind);
		mark.setInt(2, id);
		mark.setInt(3, type.getId());
		mark.setInt(4, role);
		for (int c = 0; c < 3; c++)
			mark.setDouble(5 + c, stored[c]);
		mark.execute();
	}

	/** Behandlung einer Messart zuruecknehmen: {Mittel geloescht, Einzelwerte aktiviert, Sigmas wiederhergestellt}. */
	@Override
	public int[] undoRepetitionHandling(ObservationType type) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return new int[3];
		ensureRepetitionTable();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		PreparedStatement read = db.getPreparedStatement(SQL_READ_REPETITION_OF_TYPE);
		read.setInt(1, type.getId());
		ResultSet rs = read.executeQuery();
		List<double[]> rows = new ArrayList<double[]>();
		while (rs.next())
			rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6) });
		int deleted = 0, enabled = 0, restored = 0;
		for (double[] r : rows) {
			boolean gnss = (int) r[0] == REP_KIND_GNSS;
			int id = (int) r[1], role = (int) r[2];
			if (role == REP_ROLE_MEAN || role == REP_ROLE_MEAN_CALIBRATED) {
				PreparedStatement delete = db.getPreparedStatement(gnss ? SQL_DELETE_GNSS_OBSERVATION : SQL_DELETE_TERRESTRIAL);
				delete.setInt(1, id);
				deleted += delete.executeUpdate(); // nur tatsaechlich vorhandene Zeilen zaehlen (Altreste aus Projektkopien)
			} else if (role == REP_ROLE_SINGLE_OFF) {
				PreparedStatement on = db.getPreparedStatement(gnss ? SQL_SET_GNSS_OBSERVATION_ENABLED : SQL_SET_TERRESTRIAL_ENABLED);
				on.setBoolean(1, true);
				on.setInt(2, id);
				enabled += on.executeUpdate();
			} else if (role == REP_ROLE_REWEIGHTED) {
				if (gnss) {
					PreparedStatement set = db.getPreparedStatement(SQL_SET_GNSS_SIGMAS);
					set.setDouble(1, r[3]);
					set.setDouble(2, r[4]);
					set.setDouble(3, r[5]);
					set.setInt(4, id);
					set.execute();
				} else {
					PreparedStatement set = db.getPreparedStatement(SQL_WRITE_INDIVIDUAL_UNCERTAINTY);
					set.setDouble(1, r[3]);
					set.setInt(2, id);
					set.execute();
				}
				restored++;
			}
		}
		PreparedStatement clear = db.getPreparedStatement(SQL_DELETE_REPETITION_OF_TYPE);
		clear.setInt(1, type.getId());
		clear.execute();
		if (type == ObservationType.GNSS3D && this.hasGnssSessionMerge()) {
			int[] legacy = this.undoGnssSessionMerge();
			deleted += legacy[0];
			enabled += legacy[1];
		}
		this.frozenRepetitionKeys = null;
		this.calibrationPart.deleteBestState();
		return new int[] { deleted, enabled, restored };
	}

	// ============================================================================================================
	// Festpunkte ohne Koordinaten (0/0/0) und Zustandsprotokoll (Ablauf-Assistent)

	public static final String ZERO_FIXED_GROUP_NAME = "Klein_Method Neupunkte (aus Festpunkten 0/0/0)";
	private static final String SQL_CREATE_ZERO_FIXED = "CREATE TABLE IF NOT EXISTS \"KleinMethodZeroFixed\" (\"id\" INTEGER NOT NULL, \"group_id\" INTEGER NOT NULL, "
			+ "PRIMARY KEY (\"id\"))";
	private static final String SQL_CREATE_STATE_LOG = "CREATE TABLE IF NOT EXISTS \"KleinMethodStateLog\" (\"id\" INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
			+ "\"stamp\" VARCHAR(19) NOT NULL, \"text\" VARCHAR(2000) NOT NULL)";

	/**
	 * Fest-, Datums- und Anschlusspunkte (2D/3D) ohne Koordinaten (Lage 0/0, bei 3D auch Hoehe 0): {id, Name, Dimension,
	 * Punktart-Id, 1 = Endpunkt von GNSS-Basislinien}.
	 */
	@Override
	public List<Object[]> readZeroFixedPoints() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		Set<String> gnss = new HashSet<String>();
		ResultSet g = db.getPreparedStatement("SELECT o.\"start_point_name\", o.\"end_point_name\" FROM \"GNSSObservationApriori\" o JOIN \"ObservationGroup\" og "
				+ "ON og.\"id\" = o.\"group_id\" WHERE o.\"enable\" = TRUE AND og.\"enable\" = TRUE").executeQuery();
		while (g.next()) {
			gnss.add(g.getString(1));
			gnss.add(g.getString(2));
		}
		PreparedStatement stmt = db.getPreparedStatement("SELECT p.\"id\", p.\"name\", pg.\"dimension\", pg.\"type\" FROM \"PointApriori\" p JOIN \"PointGroup\" pg ON pg.\"id\" = p.\"group_id\" "
				+ "WHERE pg.\"enable\" = TRUE AND p.\"enable\" = TRUE AND pg.\"dimension\" IN (2, 3) AND pg.\"type\" IN (?, ?, ?) "
				+ "AND p.\"y0\" = 0 AND p.\"x0\" = 0 AND (pg.\"dimension\" = 2 OR p.\"z0\" = 0) ORDER BY p.\"name\"");
		stmt.setInt(1, PointType.REFERENCE_POINT.getId());
		stmt.setInt(2, PointType.DATUM_POINT.getId());
		stmt.setInt(3, PointType.STOCHASTIC_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), gnss.contains(rs.getString(2)) ? 1 : 0 });
		return rows;
	}

	/** Festpunkte bei 0/0/0 in Neupunkte gleicher Dimension umwandeln (eigene Gruppe je Dimension); Anzahl. */
	@Override
	public int repairZeroFixedPoints() throws SQLException {
		List<Object[]> rows = this.readZeroFixedPoints();
		if (rows.isEmpty())
			return 0;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_ZERO_FIXED).execute();
		Map<Integer, Integer> groupByDimension = new java.util.HashMap<Integer, Integer>();
		PreparedStatement mark = db.getPreparedStatement("INSERT INTO \"KleinMethodZeroFixed\" (\"id\", \"group_id\") SELECT \"id\", \"group_id\" FROM \"PointApriori\" WHERE \"id\" = ?");
		PreparedStatement move = db.getPreparedStatement(SQL_MOVE_POINT_TO_GROUP);
		for (Object[] row : rows) {
			int dimension = (Integer) row[2];
			Integer group = groupByDimension.get(dimension);
			if (group == null) {
				group = this.createPointGroup(ZERO_FIXED_GROUP_NAME + " " + dimension + "D", dimension, PointType.NEW_POINT);
				groupByDimension.put(dimension, group);
			}
			mark.setInt(1, (Integer) row[0]);
			mark.execute();
			move.setInt(1, group);
			move.setInt(2, (Integer) row[0]);
			move.execute();
		}
		return rows.size();
	}

	/** Umwandlung der Festpunkte 0/0/0 zuruecknehmen (zurueck in die alte Gruppe, solange der Punkt noch in der Klein_Method-Gruppe steht). */
	@Override
	public int undoZeroFixedPoints() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_ZERO_FIXED).execute();
		int n = db.getPreparedStatement("UPDATE \"PointApriori\" p SET \"group_id\" = (SELECT z.\"group_id\" FROM \"KleinMethodZeroFixed\" z WHERE z.\"id\" = p.\"id\") "
				+ "WHERE p.\"id\" IN (SELECT \"id\" FROM \"KleinMethodZeroFixed\") AND p.\"group_id\" IN (SELECT \"id\" FROM \"PointGroup\" WHERE \"name\" LIKE '"
				+ ZERO_FIXED_GROUP_NAME + "%')").executeUpdate();
		db.getPreparedStatement("DELETE FROM \"KleinMethodZeroFixed\"").execute();
		db.getPreparedStatement("DELETE FROM \"PointGroup\" WHERE \"name\" LIKE '" + ZERO_FIXED_GROUP_NAME + "%' AND \"id\" NOT IN (SELECT \"group_id\" FROM \"PointApriori\")").execute();
		return n;
	}

	/** Anzahl der umgewandelten Festpunkte 0/0/0 (Ruecknahme moeglich). */
	@Override
	public int countRepairedZeroFixedPoints() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_ZERO_FIXED).execute();
		ResultSet rs = db.getPreparedStatement("SELECT COUNT(*) FROM \"KleinMethodZeroFixed\"").executeQuery();
		return rs.next() ? rs.getInt(1) : 0;
	}

	/** Eintrag im Zustandsprotokoll des Projekts (was wurde wann umgerechnet/geaendert). */
	@Override
	public void appendStateLog(String text) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_STATE_LOG).execute();
		PreparedStatement stmt = db.getPreparedStatement("INSERT INTO \"KleinMethodStateLog\" (\"stamp\", \"text\") VALUES (?, ?)");
		stmt.setString(1, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
		stmt.setString(2, text.length() > 2000 ? text.substring(0, 2000) : text);
		stmt.execute();
	}

	/** die letzten Eintraege des Zustandsprotokolls, aelteste zuerst ("Datum Uhrzeit  Text"). */
	@Override
	public List<String> readStateLog(int limit) throws SQLException {
		List<String> lines = new ArrayList<String>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return lines;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_STATE_LOG).execute();
		ResultSet rs = db.getPreparedStatement("SELECT \"stamp\", \"text\" FROM \"KleinMethodStateLog\" ORDER BY \"id\" DESC LIMIT " + Math.max(1, limit)).executeQuery();
		while (rs.next())
			lines.add(0, rs.getString(1) + "  " + rs.getString(2));
		return lines;
	}

	/** {Anzahl 2D/3D-Punkte geozentrisch, UTM mit Zonenkennung, lokal/projiziert, 0/0} - a priori, aktive Punkte. */
	@Override
	public int[] readCoordinateKindCounts() throws SQLException {
		int[] r = new int[4];
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return r;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT p.\"y0\", p.\"x0\", p.\"z0\", pg.\"dimension\" FROM \"PointApriori\" p JOIN \"PointGroup\" pg "
				+ "ON pg.\"id\" = p.\"group_id\" WHERE pg.\"enable\" = TRUE AND p.\"enable\" = TRUE AND pg.\"dimension\" IN (2, 3)").executeQuery();
		while (rs.next()) {
			double y = rs.getDouble(1), x = rs.getDouble(2), z = rs.getDouble(3);
			double radius = Math.sqrt(y * y + x * x + z * z);
			int zone = (int) Math.floor(y / 1.0e6);
			if (y == 0.0 && x == 0.0)
				r[3]++;
			else if (rs.getInt(4) == 3 && radius > 6.30e6 && radius < 6.42e6)
				r[0]++;
			else if (zone >= 1 && zone <= 60 && y - zone * 1.0e6 > 100000.0 && y - zone * 1.0e6 < 900000.0 && x > 0.0 && x < 9350000.0)
				r[1]++;
			else
				r[2]++;
		}
		return r;
	}

	/**
	 * Sperre gegen mehrfache bzw. fremde Umrechnung: nach der Projekt-Umrechnung lokal vorliegende Punkte (2D/3D) und
	 * GNSS-Basislinien, die Klein_Method nicht selbst umgerechnet hat (nicht in der Sicherung der Umrechnung, keine von
	 * Klein_Method gebildeten Mittel, keine Naeherung) - sie stammen aus einer anderen Umrechnung mit unbekanntem P0.
	 * Ergebnis: {Festpunkte (Namen), Neupunkte (Namen), Basislinien (Start-Ziel)}; leer, wenn das Projekt nicht von
	 * Klein_Method umgerechnet ist.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public List<String>[] readForeignLocalRows() throws SQLException {
		List<String>[] r = new List[] { new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>() };
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return r;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_POINTS).execute();
		db.getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_BASELINES).execute();
		db.getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_INFO).execute();
		db.getPreparedStatement(SQL_CREATE_APPROXIMATION_BACKUP).execute();
		db.getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		ensureRepetitionTable();
		ResultSet info = db.getPreparedStatement("SELECT COUNT(*) FROM \"KleinMethodLocalBackupInfo\"").executeQuery();
		if (!info.next() || info.getInt(1) == 0)
			return r;
		PreparedStatement points = db.getPreparedStatement("SELECT p.\"name\", p.\"y0\", p.\"x0\", p.\"z0\", g.\"type\" FROM \"PointApriori\" p "
				+ "JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" WHERE g.\"enable\" = TRUE AND p.\"enable\" = TRUE AND g.\"dimension\" IN (2, 3) "
				+ "AND NOT (p.\"y0\" = 0 AND p.\"x0\" = 0) AND p.\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodLocalBackupPoint\") "
				+ "AND p.\"name\" NOT IN (SELECT \"name\" FROM \"KleinMethodApproxBackup\") ORDER BY p.\"name\"");
		ResultSet rs = points.executeQuery();
		while (rs.next()) {
			if (Klein_Method_Functions.Klein_Method_Local_System.isEcef(rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)))
				continue; // noch geozentrisch - wird beim naechsten Tab-Klick mit demselben P0 umgerechnet
			r[rs.getInt(5) == PointType.NEW_POINT.getId() ? 1 : 0].add(rs.getString(1));
		}
		ResultSet bl = db.getPreparedStatement("SELECT a.\"start_point_name\", a.\"end_point_name\" FROM \"GNSSObservationApriori\" a "
				+ "JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" WHERE g.\"enable\" = TRUE AND a.\"enable\" = TRUE "
				+ "AND a.\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodLocalBackupBaseline\") "
				+ "AND a.\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodRepetition\" WHERE \"kind\" = 1) "
				+ "AND a.\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodGnssSessionMerge\") ORDER BY a.\"id\"").executeQuery();
		while (bl.next())
			r[2].add(bl.getString(1) + "-" + bl.getString(2));
		return r;
	}

	// ============================================================================================================
	// Statistischer Test vor der Einzelkalibrierung: Ausgangsgenauigkeiten (Stand beim ersten Automatik-Start),
	// Befunde und Gruppengenauigkeiten nach der Gruppenkalibrierung. Die Einzelkalibrierung erhoeht die Genauigkeit
	// kleiner grober Fehler, bis kein Test mehr anspricht - getestet wird deshalb mit den Ausgangsgenauigkeiten.

	private static final String SQL_CREATE_SIGMA_ORIGIN = "CREATE TABLE IF NOT EXISTS \"KleinMethodSigmaOrigin\" (\"kind\" SMALLINT NOT NULL, \"id\" INTEGER NOT NULL, "
			+ "\"s0\" DOUBLE, \"s1\" DOUBLE, \"s2\" DOUBLE, PRIMARY KEY (\"kind\", \"id\"))";
	private static final String SQL_CREATE_STAT_TEST = "CREATE TABLE IF NOT EXISTS \"KleinMethodStatTest\" (\"kind\" SMALLINT NOT NULL, \"id\" INTEGER NOT NULL, "
			+ "\"obs_type\" INTEGER NOT NULL, \"start_name\" VARCHAR(256), \"end_name\" VARCHAR(256), \"log_post\" DOUBLE, \"linear\" DOUBLE, \"mdb\" DOUBLE, "
			+ "\"redundancy\" DOUBLE, \"sig\" BOOLEAN NOT NULL, \"stamp\" VARCHAR(19) NOT NULL, PRIMARY KEY (\"kind\", \"id\"))";
	private static final String SQL_CREATE_STAT_GROUP = "CREATE TABLE IF NOT EXISTS \"KleinMethodStatGroup\" (\"group_id\" INTEGER NOT NULL, \"type\" INTEGER NOT NULL, "
			+ "\"value\" DOUBLE, PRIMARY KEY (\"group_id\", \"type\"))";

	private void ensureStatisticalTables() throws SQLException {
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_SIGMA_ORIGIN).execute();
		db.getPreparedStatement(SQL_CREATE_STAT_TEST).execute();
		db.getPreparedStatement(SQL_CREATE_STAT_GROUP).execute();
		db.getPreparedStatement(SQL_CREATE_FINDING_EDIT).execute();
		db.getPreparedStatement(SQL_CREATE_GROUP_TEST).execute();
		ResultSet rs = db.getPreparedStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'KleinMethodStatTest' AND COLUMN_NAME = 'nabla'").executeQuery();
		if (rs.next() && rs.getInt(1) == 0)
			db.getPreparedStatement("ALTER TABLE \"KleinMethodStatTest\" ADD COLUMN \"nabla\" DOUBLE").execute();
	}

	private static final String SQL_CREATE_FINDING_EDIT = "CREATE TABLE IF NOT EXISTS \"KleinMethodFindingEdit\" (\"nr\" INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
			+ "\"kind\" SMALLINT NOT NULL, \"id\" INTEGER NOT NULL, \"field\" VARCHAR(32) NOT NULL, \"old_value\" DOUBLE, \"new_value\" DOUBLE, \"stamp\" VARCHAR(19) NOT NULL)";
	private static final java.util.Set<String> EDITABLE_TERRESTRIAL = new HashSet<String>(java.util.Arrays.asList("value_0", "instrument_height", "reflector_height", "sigma_0", "enable"));
	private static final java.util.Set<String> EDITABLE_GNSS = new HashSet<String>(java.util.Arrays.asList("sigma_y0", "sigma_x0", "sigma_z0", "enable"));

	private static String editableTable(int kind, String field) {
		if (kind == 0 && EDITABLE_TERRESTRIAL.contains(field))
			return "ObservationApriori";
		if (kind == 1 && EDITABLE_GNSS.contains(field))
			return "GNSSObservationApriori";
		throw new IllegalArgumentException("Feld nicht editierbar: " + field);
	}

	/** Feld einer Beobachtung lesen (enable als 1/0); NaN, wenn nicht vorhanden. */
	@Override
	public double readObservationField(int kind, int id, String field) throws SQLException {
		String table = editableTable(kind, field);
		ResultSet rs;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"" + field + "\" FROM \"" + table + "\" WHERE \"id\" = ?");
		stmt.setInt(1, id);
		rs = stmt.executeQuery();
		if (!rs.next())
			return Double.NaN;
		if ("enable".equals(field))
			return rs.getBoolean(1) ? 1.0 : 0.0;
		double v = rs.getDouble(1);
		return rs.wasNull() ? Double.NaN : v;
	}

	/** Feld einer Beobachtung schreiben und die Aenderung merken (Ruecknahme). */
	@Override
	public void editObservationField(int kind, int id, String field, double value, String stamp) throws SQLException {
		this.ensureStatisticalTables();
		double old = this.readObservationField(kind, id, field);
		String table = editableTable(kind, field);
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("UPDATE \"" + table + "\" SET \"" + field + "\" = ? WHERE \"id\" = ?");
		if ("enable".equals(field))
			stmt.setBoolean(1, value != 0.0);
		else
			stmt.setDouble(1, value);
		stmt.setInt(2, id);
		stmt.execute();
		PreparedStatement log = SQLManager.getInstance().getDataBase().getPreparedStatement("INSERT INTO \"KleinMethodFindingEdit\" (\"kind\", \"id\", \"field\", "
				+ "\"old_value\", \"new_value\", \"stamp\") VALUES (?, ?, ?, ?, ?, ?)");
		log.setInt(1, kind);
		log.setInt(2, id);
		log.setString(3, field);
		log.setDouble(4, old);
		log.setDouble(5, value);
		log.setString(6, stamp);
		log.execute();
	}

	/** gemerkte Aenderungen: {kind, id, Feld, alt, neu, Zeitpunkt}, aelteste zuerst; kind < 0 = alle. */
	@Override
	public List<Object[]> readFindingEdits(int kind, int id) throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		this.ensureStatisticalTables();
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"kind\", \"id\", \"field\", \"old_value\", \"new_value\", \"stamp\" "
				+ "FROM \"KleinMethodFindingEdit\" WHERE (? < 0 OR (\"kind\" = ? AND \"id\" = ?)) ORDER BY \"nr\" ASC");
		stmt.setInt(1, kind);
		stmt.setInt(2, kind);
		stmt.setInt(3, id);
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getString(6) });
		return rows;
	}

	/** Aenderungen zuruecknehmen (neueste zuerst); kind < 0 = alle. Anzahl zurueckgenommener Aenderungen. */
	@Override
	public int undoFindingEdits(int kind, int id) throws SQLException {
		List<Object[]> edits = this.readFindingEdits(kind, id);
		int n = 0;
		for (int i = edits.size() - 1; i >= 0; i--) {
			Object[] e = edits.get(i);
			int k = (Integer) e[0], oid = (Integer) e[1];
			String field = (String) e[2];
			double old = (Double) e[3];
			String table = editableTable(k, field);
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("UPDATE \"" + table + "\" SET \"" + field + "\" = ? WHERE \"id\" = ?");
			if ("enable".equals(field))
				stmt.setBoolean(1, old != 0.0);
			else if (Double.isNaN(old))
				stmt.setNull(1, java.sql.Types.DOUBLE);
			else
				stmt.setDouble(1, old);
			stmt.setInt(2, oid);
			n += stmt.executeUpdate();
		}
		PreparedStatement del = SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodFindingEdit\" WHERE (? < 0 OR (\"kind\" = ? AND \"id\" = ?))");
		del.setInt(1, kind);
		del.setInt(2, kind);
		del.setInt(3, id);
		del.execute();
		return n;
	}


	/** Ausgangsgenauigkeiten merken (nur Beobachtungen, die noch keinen Eintrag haben); Anzahl neu gemerkt. */
	@Override
	public int captureOriginSigmas() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		this.ensureStatisticalTables();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		int n = db.getPreparedStatement("INSERT INTO \"KleinMethodSigmaOrigin\" (\"kind\", \"id\", \"s0\", \"s1\", \"s2\") SELECT 0, o.\"id\", o.\"sigma_0\", 0, 0 "
				+ "FROM \"ObservationApriori\" o WHERE NOT EXISTS (SELECT 1 FROM \"KleinMethodSigmaOrigin\" r WHERE r.\"kind\" = 0 AND r.\"id\" = o.\"id\")").executeUpdate();
		n += db.getPreparedStatement("INSERT INTO \"KleinMethodSigmaOrigin\" (\"kind\", \"id\", \"s0\", \"s1\", \"s2\") SELECT 1, o.\"id\", o.\"sigma_y0\", o.\"sigma_x0\", "
				+ "o.\"sigma_z0\" FROM \"GNSSObservationApriori\" o WHERE NOT EXISTS (SELECT 1 FROM \"KleinMethodSigmaOrigin\" r WHERE r.\"kind\" = 1 AND r.\"id\" = o.\"id\")")
				.executeUpdate();
		return n;
	}

	/** aktuelle Genauigkeiten aller Beobachtungen: Schluessel kind * 2^32 + id -> {s0, s1, s2}. */
	@Override
	public Map<Long, double[]> readCurrentSigmas() throws SQLException {
		Map<Long, double[]> map = new java.util.HashMap<Long, double[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return map;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		ResultSet rs = db.getPreparedStatement("SELECT \"id\", \"sigma_0\" FROM \"ObservationApriori\"").executeQuery();
		while (rs.next())
			map.put((long) rs.getInt(1), new double[] { rs.getDouble(2), 0, 0 });
		rs = db.getPreparedStatement("SELECT \"id\", \"sigma_y0\", \"sigma_x0\", \"sigma_z0\" FROM \"GNSSObservationApriori\"").executeQuery();
		while (rs.next())
			map.put((1L << 32) + rs.getInt(1), new double[] { rs.getDouble(2), rs.getDouble(3), rs.getDouble(4) });
		return map;
	}

	/** Genauigkeiten zurueckschreiben (aus readCurrentSigmas). */
	@Override
	public void writeSigmas(Map<Long, double[]> sigmas) throws SQLException {
		if (!hasOpenDatabase())
			return;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		PreparedStatement t = db.getPreparedStatement(SQL_WRITE_INDIVIDUAL_UNCERTAINTY);
		PreparedStatement g = db.getPreparedStatement(SQL_SET_GNSS_SIGMAS);
		for (Map.Entry<Long, double[]> e : sigmas.entrySet()) {
			int id = (int) (e.getKey() & 0xffffffffL);
			if (e.getKey() >= (1L << 32)) {
				g.setDouble(1, e.getValue()[0]);
				g.setDouble(2, e.getValue()[1]);
				g.setDouble(3, e.getValue()[2]);
				g.setInt(4, id);
				g.execute();
			} else {
				t.setDouble(1, e.getValue()[0]);
				t.setInt(2, id);
				t.execute();
			}
		}
	}

	/** Ausgangsgenauigkeiten einsetzen (alle gemerkten Beobachtungen); Anzahl geschrieben. */
	@Override
	public int applyOriginSigmas() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		this.ensureStatisticalTables();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		int n = db.getPreparedStatement("UPDATE \"ObservationApriori\" o SET \"sigma_0\" = (SELECT r.\"s0\" FROM \"KleinMethodSigmaOrigin\" r WHERE r.\"kind\" = 0 "
				+ "AND r.\"id\" = o.\"id\") WHERE o.\"id\" IN (SELECT \"id\" FROM \"KleinMethodSigmaOrigin\" WHERE \"kind\" = 0)").executeUpdate();
		n += db.getPreparedStatement("UPDATE \"GNSSObservationApriori\" o SET \"sigma_y0\" = (SELECT r.\"s0\" FROM \"KleinMethodSigmaOrigin\" r WHERE r.\"kind\" = 1 "
				+ "AND r.\"id\" = o.\"id\"), \"sigma_x0\" = (SELECT r.\"s1\" FROM \"KleinMethodSigmaOrigin\" r WHERE r.\"kind\" = 1 AND r.\"id\" = o.\"id\"), "
				+ "\"sigma_z0\" = (SELECT r.\"s2\" FROM \"KleinMethodSigmaOrigin\" r WHERE r.\"kind\" = 1 AND r.\"id\" = o.\"id\") "
				+ "WHERE o.\"id\" IN (SELECT \"id\" FROM \"KleinMethodSigmaOrigin\" WHERE \"kind\" = 1)").executeUpdate();
		return n;
	}

	@Override
	public void storeStatisticalState() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return;
		this.ensureStatisticalTables();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement("DELETE FROM \"KleinMethodStatTest\"").execute();
		db.getPreparedStatement("DELETE FROM \"KleinMethodStatGroup\"").execute();
		db.getPreparedStatement("INSERT INTO \"KleinMethodStatGroup\" (\"group_id\", \"type\", \"value\") SELECT \"group_id\", \"type\", \"value\" "
				+ "FROM \"ObservationGroupUncertainty\"").execute();
	}

	@Override
	public boolean hasStatisticalState() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return false;
		this.ensureStatisticalTables();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT COUNT(*) FROM \"KleinMethodStatGroup\"").executeQuery();
		return rs.next() && rs.getInt(1) > 0;
	}

	@Override
	public List<double[]> readAllGroupUncertainties() throws SQLException {
		List<double[]> rows = new ArrayList<double[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"group_id\", \"type\", \"value\" FROM \"ObservationGroupUncertainty\"")
				.executeQuery();
		while (rs.next())
			rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(3) });
		return rows;
	}

	@Override
	public void writeAllGroupUncertainties(List<double[]> values) throws SQLException {
		if (!hasOpenDatabase() || values == null)
			return;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("UPDATE \"ObservationGroupUncertainty\" SET \"value\" = ? "
				+ "WHERE \"group_id\" = ? AND \"type\" = ?");
		for (double[] v : values) {
			stmt.setDouble(1, v[2]);
			stmt.setInt(2, (int) v[0]);
			stmt.setInt(3, (int) v[1]);
			stmt.execute();
		}
	}

	/** Befunde (signifikant bzw. nicht aufdeckbar) und die aktuellen Gruppengenauigkeiten speichern. */
	@Override
	public void storeStatisticalSnapshot(List<Max_Error_Row> rows, String stamp) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return;
		this.ensureStatisticalTables();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement("DELETE FROM \"KleinMethodStatTest\"").execute();
		db.getPreparedStatement("DELETE FROM \"KleinMethodStatGroup\"").execute();
		db.getPreparedStatement("INSERT INTO \"KleinMethodStatGroup\" (\"group_id\", \"type\", \"value\") SELECT \"group_id\", \"type\", \"value\" "
				+ "FROM \"ObservationGroupUncertainty\"").execute();
		PreparedStatement insert = db.getPreparedStatement("INSERT INTO \"KleinMethodStatTest\" (\"kind\", \"id\", \"obs_type\", \"start_name\", \"end_name\", "
				+ "\"log_post\", \"linear\", \"mdb\", \"redundancy\", \"sig\", \"stamp\", \"nabla\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
		for (Max_Error_Row r : rows) {
			insert.setInt(1, r.gnss ? 1 : 0);
			insert.setInt(2, r.observationId);
			insert.setInt(3, r.observationType.getId());
			insert.setString(4, r.startPointName);
			insert.setString(5, r.endPointName);
			insert.setDouble(6, r.value);
			insert.setDouble(7, r.linearError);
			insert.setDouble(8, r.mdbLinear);
			insert.setDouble(9, r.redundancy);
			insert.setBoolean(10, r.significant);
			insert.setString(11, stamp);
			insert.setDouble(12, r.nablaRaw);
			insert.execute();
		}
	}

	/**
	 * gespeicherte Befunde: je Zeile {kind, id, Typ-Id, Start, Ziel, |logPost|, linear, MDB, Redundanz, signifikant,
	 * Zeitpunkt, Genauigkeit vorher (Ausgangsstand, max. Komponente), Genauigkeit jetzt, aktiv}.
	 */
	@Override
	public List<Object[]> readStatisticalSnapshot() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		this.ensureStatisticalTables();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT t.\"kind\", t.\"id\", t.\"obs_type\", t.\"start_name\", t.\"end_name\", "
				+ "t.\"log_post\", t.\"linear\", t.\"mdb\", t.\"redundancy\", t.\"sig\", t.\"stamp\", "
				+ "GREATEST(COALESCE(r.\"s0\", 0), COALESCE(r.\"s1\", 0), COALESCE(r.\"s2\", 0)), "
				+ "CASE WHEN t.\"kind\" = 0 THEN o.\"sigma_0\" ELSE GREATEST(g.\"sigma_y0\", g.\"sigma_x0\", g.\"sigma_z0\") END, "
				+ "CASE WHEN t.\"kind\" = 0 THEN o.\"enable\" ELSE g.\"enable\" END, t.\"nabla\" "
				+ "FROM \"KleinMethodStatTest\" t LEFT JOIN \"KleinMethodSigmaOrigin\" r ON r.\"kind\" = t.\"kind\" AND r.\"id\" = t.\"id\" "
				+ "LEFT JOIN \"ObservationApriori\" o ON t.\"kind\" = 0 AND o.\"id\" = t.\"id\" LEFT JOIN \"GNSSObservationApriori\" g ON t.\"kind\" = 1 AND g.\"id\" = t.\"id\" "
				+ "ORDER BY t.\"sig\" DESC, t.\"log_post\" DESC").executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
					rs.getDouble(9), rs.getBoolean(10), rs.getString(11), rs.getDouble(12), rs.getDouble(13), rs.getObject(14) != null && rs.getBoolean(14),
					rs.getObject(15) == null ? Double.NaN : rs.getDouble(15) });
		return rows;
	}

	/** Genauigkeiten auf den Stand des Tests setzen: Ausgangsgenauigkeiten und Gruppengenauigkeiten nach der Gruppenkalibrierung. */
	@Override
	public int restoreStatisticalState() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		this.ensureStatisticalTables();
		int n = this.applyOriginSigmas();
		n += SQLManager.getInstance().getDataBase().getPreparedStatement("UPDATE \"ObservationGroupUncertainty\" u SET \"value\" = (SELECT g.\"value\" FROM "
				+ "\"KleinMethodStatGroup\" g WHERE g.\"group_id\" = u.\"group_id\" AND g.\"type\" = u.\"type\") WHERE EXISTS (SELECT 1 FROM \"KleinMethodStatGroup\" g "
				+ "WHERE g.\"group_id\" = u.\"group_id\" AND g.\"type\" = u.\"type\")").executeUpdate();
		this.calibrationPart.deleteBestState();
		return n;
	}

	@Override
	public void clearStatisticalSnapshot() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return;
		this.ensureStatisticalTables();
		SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodStatTest\"").execute();
		SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodGroupTest\"").execute();
	}

	// Gruppentests auf gemeinsame Ursache (Prismen-/Additionskonstante je Ziel, Instrumentenhoehe je Standpunkt, Zielhoehe je Ziel)

	private static final String SQL_CREATE_GROUP_TEST = "CREATE TABLE IF NOT EXISTS \"KleinMethodGroupTest\" (\"nr\" INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
			+ "\"gkind\" VARCHAR(1) NOT NULL, \"name\" VARCHAR(256), \"field\" VARCHAR(32) NOT NULL, \"members\" LONGVARCHAR, \"n\" INTEGER, \"estimate\" DOUBLE, "
			+ "\"tstat\" DOUBLE, \"logp\" DOUBLE, \"mdb\" DOUBLE, \"sig\" BOOLEAN NOT NULL, \"stamp\" VARCHAR(19) NOT NULL, \"note\" VARCHAR(512))";

	@Override
	public double[] readGlobalOmega() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return new double[] { Double.NaN, Double.NaN };
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"omega\", \"redundancy\" FROM \"VarianceComponent\" WHERE \"type\" = 0").executeQuery();
		if (!rs.next())
			return new double[] { Double.NaN, Double.NaN };
		return new double[] { rs.getDouble(1), rs.getDouble(2) };
	}

	private volatile boolean repetitionByPair = false;

	// ===== Punkte umbenennen (Vorlagen, Zusammenfuehren, exakte Ruecknahme) =====

	private static final String SQL_CREATE_RENAME_LOG = "CREATE TABLE IF NOT EXISTS \"KleinMethodRenameLog\" (\"nr\" INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
			+ "\"batch\" VARCHAR(19) NOT NULL, \"tbl\" VARCHAR(64) NOT NULL, \"row_id\" INTEGER NOT NULL, \"col\" VARCHAR(64) NOT NULL, \"kind\" VARCHAR(1) NOT NULL, "
			+ "\"old_value\" LONGVARCHAR, \"new_value\" LONGVARCHAR)";
	private static final String SQL_CREATE_RENAME_DELETED = "CREATE TABLE IF NOT EXISTS \"KleinMethodRenameDeleted\" (\"nr\" INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
			+ "\"batch\" VARCHAR(19) NOT NULL, \"tbl\" VARCHAR(64) NOT NULL, \"row_data\" LONGVARCHAR NOT NULL)";
	private static final String[][] RENAME_NAME_COLUMNS = { { "ObservationApriori", "start_point_name" }, { "ObservationApriori", "end_point_name" },
			{ "GNSSObservationApriori", "start_point_name" }, { "GNSSObservationApriori", "end_point_name" },
			{ "CongruenceAnalysisPointPairApriori", "start_point_name" }, { "CongruenceAnalysisPointPairApriori", "end_point_name" }, { "VerticalDeflectionApriori", "name" } };

	private void ensureRenameTables() throws SQLException {
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_RENAME_LOG).execute();
		db.getPreparedStatement(SQL_CREATE_RENAME_DELETED).execute();
	}

	@Override
	public List<Object[]> readRenamePoints() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT p.\"id\", p.\"name\", g.\"type\", g.\"dimension\", p.\"y0\", p.\"x0\", p.\"z0\", "
				+ "a.\"y\", a.\"x\", a.\"z\" FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" LEFT JOIN \"PointAposteriori\" a ON a.\"id\" = p.\"id\" "
				+ "ORDER BY p.\"name\"").executeQuery();
		while (rs.next()) {
			double y = rs.getDouble(8);
			boolean none = rs.wasNull();
			rows.add(new Object[] { rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
					none ? Double.NaN : y, none ? Double.NaN : rs.getDouble(9), none ? Double.NaN : rs.getDouble(10) });
		}
		return rows;
	}

	@Override
	public Map<String, Integer> readObservationNameCounts() throws SQLException {
		Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return counts;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		for (String table : new String[] { "ObservationApriori", "GNSSObservationApriori" })
			for (String column : new String[] { "start_point_name", "end_point_name" }) {
				ResultSet rs = db.getPreparedStatement("SELECT \"" + column + "\", COUNT(*) FROM \"" + table + "\" GROUP BY \"" + column + "\"").executeQuery();
				while (rs.next())
					counts.merge(rs.getString(1), rs.getInt(2), Integer::sum);
			}
		return counts;
	}

	private void logRename(String batch, String table, int id, String column, String kind, String oldValue, String newValue) throws SQLException {
		PreparedStatement log = SQLManager.getInstance().getDataBase().getPreparedStatement("INSERT INTO \"KleinMethodRenameLog\" (\"batch\", \"tbl\", \"row_id\", \"col\", \"kind\", "
				+ "\"old_value\", \"new_value\") VALUES (?, ?, ?, ?, ?, ?, ?)");
		log.setString(1, batch);
		log.setString(2, table);
		log.setInt(3, id);
		log.setString(4, column);
		log.setString(5, kind);
		log.setString(6, oldValue);
		log.setString(7, newValue);
		log.execute();
	}

	/** Zeile als Text sichern: je Spalte "Name\u001fTypnummer\u001fWert" (Wert leer = NULL), getrennt durch \u001e. */
	private static String serializeRow(ResultSet rs) throws SQLException {
		java.sql.ResultSetMetaData md = rs.getMetaData();
		StringBuilder b = new StringBuilder();
		for (int c = 1; c <= md.getColumnCount(); c++) {
			Object v = rs.getObject(c);
			if (c > 1)
				b.append('\u001e');
			b.append(md.getColumnName(c)).append('\u001f').append(md.getColumnType(c)).append('\u001f').append(v == null ? "\u0000" : String.valueOf(v));
		}
		return b.toString();
	}

	private static boolean tableExists(String table) throws SQLException {
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '" + table + "'").executeQuery();
		return rs.next() && rs.getInt(1) > 0;
	}

	@Override
	public int[] applyRenames(Map<String, String> observationRenames, Map<Integer, String> pointRenames, List<Integer> deletePointIds, Map<Integer, double[]> apriori,
			String batch) throws SQLException {
		int[] r = new int[4];
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return r;
		this.ensureRenameTables();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		// 1) zusammengefuehrte Punkte sichern und loeschen (Ergebnisse haengen per ON DELETE CASCADE daran)
		for (int id : deletePointIds) {
			ResultSet rs = db.getPreparedStatement("SELECT * FROM \"PointApriori\" WHERE \"id\" = " + id).executeQuery();
			if (!rs.next())
				continue;
			PreparedStatement keep = db.getPreparedStatement("INSERT INTO \"KleinMethodRenameDeleted\" (\"batch\", \"tbl\", \"row_data\") VALUES (?, ?, ?)");
			keep.setString(1, batch);
			keep.setString(2, "PointApriori");
			keep.setString(3, serializeRow(rs));
			keep.execute();
			db.getPreparedStatement("DELETE FROM \"PointApriori\" WHERE \"id\" = " + id).execute();
			r[1]++;
		}
		// 2) Punkte umbenennen, zweistufig (Ketten wie A->B, B->C verletzen sonst "UniquePointName")
		Map<Integer, String> oldNames = new java.util.HashMap<Integer, String>();
		for (Map.Entry<Integer, String> e : pointRenames.entrySet()) {
			ResultSet rs = db.getPreparedStatement("SELECT \"name\" FROM \"PointApriori\" WHERE \"id\" = " + e.getKey()).executeQuery();
			if (!rs.next())
				continue;
			oldNames.put(e.getKey(), rs.getString(1));
			PreparedStatement tmp = db.getPreparedStatement("UPDATE \"PointApriori\" SET \"name\" = ? WHERE \"id\" = ?");
			tmp.setString(1, "~KM~" + e.getKey());
			tmp.setInt(2, e.getKey());
			tmp.execute();
		}
		for (Map.Entry<Integer, String> e : pointRenames.entrySet()) {
			if (!oldNames.containsKey(e.getKey()))
				continue;
			PreparedStatement set = db.getPreparedStatement("UPDATE \"PointApriori\" SET \"name\" = ? WHERE \"id\" = ?");
			set.setString(1, e.getValue());
			set.setInt(2, e.getKey());
			set.execute();
			this.logRename(batch, "PointApriori", e.getKey(), "name", "S", oldNames.get(e.getKey()), e.getValue());
			r[0]++;
		}
		// 3) Naeherungskoordinaten (Mittel beim Zusammenfuehren)
		for (Map.Entry<Integer, double[]> e : apriori.entrySet()) {
			ResultSet rs = db.getPreparedStatement("SELECT \"y0\", \"x0\", \"z0\" FROM \"PointApriori\" WHERE \"id\" = " + e.getKey()).executeQuery();
			if (!rs.next())
				continue;
			String[] cols = { "y0", "x0", "z0" };
			for (int c = 0; c < 3; c++) {
				this.logRename(batch, "PointApriori", e.getKey(), cols[c], "D", String.valueOf(rs.getDouble(c + 1)), String.valueOf(e.getValue()[c]));
				PreparedStatement set = db.getPreparedStatement("UPDATE \"PointApriori\" SET \"" + cols[c] + "\" = ? WHERE \"id\" = ?");
				set.setDouble(1, e.getValue()[c]);
				set.setInt(2, e.getKey());
				set.execute();
			}
		}
		// 4) Punktnamen in Beobachtungen, Kongruenz-Punktpaaren und Lotabweichungen - erst alle Zeilen bestimmen, dann je id aendern
		if (!observationRenames.isEmpty()) {
			for (String[] tc : RENAME_NAME_COLUMNS) {
				if (!tableExists(tc[0]))
					continue;
				List<Object[]> todo = new ArrayList<Object[]>();
				ResultSet rs = db.getPreparedStatement("SELECT \"id\", \"" + tc[1] + "\" FROM \"" + tc[0] + "\"").executeQuery();
				while (rs.next()) {
					String to = observationRenames.get(rs.getString(2));
					if (to != null)
						todo.add(new Object[] { rs.getInt(1), rs.getString(2), to });
				}
				for (Object[] t : todo) {
					try {
						PreparedStatement set = db.getPreparedStatement("UPDATE \"" + tc[0] + "\" SET \"" + tc[1] + "\" = ? WHERE \"id\" = ?");
						set.setString(1, (String) t[2]);
						set.setInt(2, (Integer) t[0]);
						set.execute();
						this.logRename(batch, tc[0], (Integer) t[0], tc[1], "S", (String) t[1], (String) t[2]);
						if (tc[0].startsWith("Observation") || tc[0].startsWith("GNSS"))
							r[2]++;
					} catch (SQLException e) {
						r[3]++; // z. B. doppeltes Kongruenz-Punktpaar bzw. Lotabweichung - unveraendert
					}
				}
			}
			// Klein_Method-Sicherung der Naeherungen ist namensbezogen
			if (tableExists("KleinMethodApproxBackup")) {
				List<Object[]> todo = new ArrayList<Object[]>();
				ResultSet rs = db.getPreparedStatement("SELECT \"name\" FROM \"KleinMethodApproxBackup\"").executeQuery();
				while (rs.next()) {
					String to = observationRenames.get(rs.getString(1));
					if (to != null)
						todo.add(new Object[] { rs.getString(1), to });
				}
				for (Object[] t : todo)
					try {
						PreparedStatement set = db.getPreparedStatement("UPDATE \"KleinMethodApproxBackup\" SET \"name\" = ? WHERE \"name\" = ?");
						set.setString(1, (String) t[1]);
						set.setString(2, (String) t[0]);
						set.execute();
						this.logRename(batch, "KleinMethodApproxBackup", -1, (String) t[0], "N", (String) t[0], (String) t[1]);
					} catch (SQLException e) {
						r[3]++;
					}
			}
		}
		return r;
	}

	@Override
	public String lastRenameBatch() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return null;
		this.ensureRenameTables();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT MAX(\"batch\") FROM (SELECT \"batch\" FROM \"KleinMethodRenameLog\" UNION ALL "
				+ "SELECT \"batch\" FROM \"KleinMethodRenameDeleted\") t").executeQuery();
		return rs.next() ? rs.getString(1) : null;
	}

	@Override
	public int[] undoLastRename() throws SQLException {
		int[] r = new int[2];
		String batch = this.lastRenameBatch();
		if (batch == null)
			return r;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		PreparedStatement read = db.getPreparedStatement("SELECT \"tbl\", \"row_id\", \"col\", \"kind\", \"old_value\", \"new_value\" FROM \"KleinMethodRenameLog\" "
				+ "WHERE \"batch\" = ? ORDER BY \"nr\" DESC");
		read.setString(1, batch);
		ResultSet rs = read.executeQuery();
		List<Object[]> log = new ArrayList<Object[]>();
		while (rs.next())
			log.add(new Object[] { rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6) });
		// Punktnamen zweistufig zurueck
		for (Object[] l : log)
			if ("PointApriori".equals(l[0]) && "name".equals(l[2])) {
				PreparedStatement tmp = db.getPreparedStatement("UPDATE \"PointApriori\" SET \"name\" = ? WHERE \"id\" = ?");
				tmp.setString(1, "~KM~" + l[1]);
				tmp.setInt(2, (Integer) l[1]);
				tmp.execute();
			}
		for (Object[] l : log) {
			String table = (String) l[0], column = (String) l[2], kind = (String) l[3];
			PreparedStatement set;
			if ("N".equals(kind)) {
				set = db.getPreparedStatement("UPDATE \"" + table + "\" SET \"name\" = ? WHERE \"name\" = ?");
				set.setString(1, (String) l[4]);
				set.setString(2, (String) l[5]);
			} else {
				set = db.getPreparedStatement("UPDATE \"" + table + "\" SET \"" + column + "\" = ? WHERE \"id\" = ?");
				if ("D".equals(kind))
					set.setDouble(1, Double.parseDouble((String) l[4]));
				else
					set.setString(1, (String) l[4]);
				set.setInt(2, (Integer) l[1]);
			}
			try {
				set.execute();
				r[0]++;
			} catch (SQLException e) {
				// Zeile inzwischen geloescht - uebergehen
			}
		}
		// geloeschte Punkte wieder anlegen (gleiche id)
		PreparedStatement deleted = db.getPreparedStatement("SELECT \"tbl\", \"row_data\" FROM \"KleinMethodRenameDeleted\" WHERE \"batch\" = ? ORDER BY \"nr\" ASC");
		deleted.setString(1, batch);
		rs = deleted.executeQuery();
		List<String[]> rows = new ArrayList<String[]>();
		while (rs.next())
			rows.add(new String[] { rs.getString(1), rs.getString(2) });
		for (String[] row : rows) {
			String[] parts = row[1].split("\u001e", -1);
			StringBuilder cols = new StringBuilder(), marks = new StringBuilder();
			for (int i = 0; i < parts.length; i++) {
				cols.append(i > 0 ? ", " : "").append('"').append(parts[i].split("\u001f", -1)[0]).append('"');
				marks.append(i > 0 ? ", " : "").append('?');
			}
			PreparedStatement insert = db.getPreparedStatement("INSERT INTO \"" + row[0] + "\" (" + cols + ") VALUES (" + marks + ")");
			for (int i = 0; i < parts.length; i++) {
				String[] f = parts[i].split("\u001f", -1);
				int type = Integer.parseInt(f[1]);
				String v = f[2];
				if ("\u0000".equals(v))
					insert.setNull(i + 1, type);
				else if (type == java.sql.Types.DOUBLE || type == java.sql.Types.FLOAT || type == java.sql.Types.REAL || type == java.sql.Types.NUMERIC || type == java.sql.Types.DECIMAL)
					insert.setDouble(i + 1, Double.parseDouble(v));
				else if (type == java.sql.Types.INTEGER || type == java.sql.Types.SMALLINT || type == java.sql.Types.TINYINT)
					insert.setInt(i + 1, Integer.parseInt(v));
				else if (type == java.sql.Types.BIGINT)
					insert.setLong(i + 1, Long.parseLong(v));
				else if (type == java.sql.Types.BOOLEAN || type == java.sql.Types.BIT)
					insert.setBoolean(i + 1, Boolean.parseBoolean(v));
				else
					insert.setString(i + 1, v);
			}
			insert.execute();
			r[1]++;
		}
		PreparedStatement del = db.getPreparedStatement("DELETE FROM \"KleinMethodRenameLog\" WHERE \"batch\" = ?");
		del.setString(1, batch);
		del.execute();
		del = db.getPreparedStatement("DELETE FROM \"KleinMethodRenameDeleted\" WHERE \"batch\" = ?");
		del.setString(1, batch);
		del.execute();
		return r;
	}

	@Override
	public void setRepetitionByPair(boolean byPair) {
		this.repetitionByPair = byPair;
	}

	@Override
	public int[] readGnssMassStatistics() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return new int[3];
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT COUNT(*), SUM(CASE WHEN a.\"enable\" = TRUE THEN 1 ELSE 0 END), "
				+ "COUNT(DISTINCT a.\"start_point_name\" || '|' || a.\"end_point_name\") FROM \"GNSSObservationApriori\" a JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" "
				+ "WHERE g.\"enable\" = TRUE").executeQuery();
		if (!rs.next())
			return new int[3];
		return new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3) };
	}

	@Override
	public List<double[]> readWeightBalance() throws SQLException {
		List<double[]> rows = new ArrayList<double[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		ResultSet rs = db.getPreparedStatement("SELECT g.\"type\", COUNT(*), SUM(ABS(p.\"redundancy\")), SUM(ABS(p.\"omega\")) FROM \"ObservationApriori\" a "
				+ "JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" JOIN \"ObservationAposteriori\" p ON p.\"id\" = a.\"id\" "
				+ "WHERE g.\"enable\" = TRUE AND a.\"enable\" = TRUE GROUP BY g.\"type\"").executeQuery();
		while (rs.next())
			rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4) });
		rs = db.getPreparedStatement("SELECT g.\"type\", COUNT(*), SUM(ABS(COALESCE(p.\"redundancy_y\", 0)) + ABS(COALESCE(p.\"redundancy_x\", 0)) + ABS(COALESCE(p.\"redundancy_z\", 0))), "
				+ "SUM(ABS(p.\"omega\")) FROM \"GNSSObservationApriori\" a JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" "
				+ "JOIN \"GNSSObservationAposteriori\" p ON p.\"id\" = a.\"id\" WHERE g.\"enable\" = TRUE AND a.\"enable\" = TRUE GROUP BY g.\"type\"").executeQuery();
		while (rs.next()) {
			int type = rs.getInt(1);
			int dim = type == ObservationType.GNSS3D.getId() ? 3 : type == ObservationType.GNSS2D.getId() ? 2 : 1;
			rows.add(new double[] { type, rs.getInt(2) * dim, rs.getDouble(3), rs.getDouble(4) });
		}
		return rows;
	}

	@Override
	public int scaleObservationTypeUncertainties(int observationType, double factor, double[] floors) throws SQLException {
		// floors (Datenbankeinheiten): {Gruppe a, Gruppe b, Gruppe c, individuell} - nie darunter; Werte an bzw. unter der
		// Untergrenze bleiben unveraendert (nur Werte darueber werden verkleinert, hoechstens bis zur Untergrenze)
		if (!hasOpenDatabase() || !this.hasProjectTables() || !(factor > 0.0) || !Double.isFinite(factor))
			return 0;
		double[] f = floors == null ? new double[4] : floors;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		boolean gnss = observationType == ObservationType.GNSS1D.getId() || observationType == ObservationType.GNSS2D.getId()
				|| observationType == ObservationType.GNSS3D.getId();
		String groups = "SELECT \"id\" FROM \"ObservationGroup\" WHERE \"enable\" = TRUE AND \"type\" = ?";
		int changed = 0;
		PreparedStatement stmt;
		for (String column : gnss ? new String[] { "sigma_y0", "sigma_x0", "sigma_z0" } : new String[] { "sigma_0" }) {
			stmt = db.getPreparedStatement("UPDATE \"" + (gnss ? "GNSSObservationApriori" : "ObservationApriori") + "\" SET \"" + column + "\" = GREATEST(\""
					+ column + "\" * ?, ?) WHERE \"enable\" = TRUE AND \"" + column + "\" > ? AND \"group_id\" IN (" + groups + ")");
			stmt.setDouble(1, factor);
			stmt.setDouble(2, f[3]);
			stmt.setDouble(3, Math.max(f[3], 0.0) * (1.0 + 1e-9));
			stmt.setInt(4, observationType);
			changed += stmt.executeUpdate();
		}
		for (int c = 0; c < 3; c++) {
			stmt = db.getPreparedStatement("UPDATE \"ObservationGroupUncertainty\" SET \"value\" = GREATEST(\"value\" * ?, ?) WHERE \"type\" = ? AND \"value\" > ? "
					+ "AND \"group_id\" IN (" + groups + ")");
			stmt.setDouble(1, factor);
			stmt.setDouble(2, f[c]);
			stmt.setInt(3, ObservationGroupUncertaintyType.ZERO_POINT_OFFSET.getId() + c);
			stmt.setDouble(4, Math.max(f[c], 0.0) * (1.0 + 1e-9));
			stmt.setInt(5, observationType);
			changed += stmt.executeUpdate();
		}
		return changed;
	}

	@Override
	public List<Object[]> readGroupTestObservations() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT a.\"id\", g.\"type\", a.\"start_point_name\", a.\"end_point_name\", "
				+ "COALESCE(p.\"omega\", 0), COALESCE(p.\"redundancy\", 0), COALESCE(p.\"p_post\", 0), COALESCE(p.\"p_prio\", 0) FROM \"ObservationApriori\" a JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" "
				+ "LEFT JOIN \"ObservationAposteriori\" p ON p.\"id\" = a.\"id\" WHERE g.\"enable\" = TRUE AND a.\"enable\" = TRUE ORDER BY a.\"id\"").executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), Math.abs(rs.getDouble(5)), Math.abs(rs.getDouble(6)),
					Math.max(Math.abs(rs.getDouble(7)), Math.abs(rs.getDouble(8))) });
		return rows;
	}

	@Override
	public void setObservationFieldQuiet(int id, String field, double value) throws SQLException {
		String table = editableTable(0, field);
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("UPDATE \"" + table + "\" SET \"" + field + "\" = ? WHERE \"id\" = ?");
		if (Double.isNaN(value))
			stmt.setNull(1, java.sql.Types.DOUBLE);
		else
			stmt.setDouble(1, value);
		stmt.setInt(2, id);
		stmt.execute();
	}

	@Override
	public void storeGroupTests(List<Object[]> rows, String stamp) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_GROUP_TEST).execute();
		db.getPreparedStatement("DELETE FROM \"KleinMethodGroupTest\"").execute();
		PreparedStatement insert = db.getPreparedStatement("INSERT INTO \"KleinMethodGroupTest\" (\"gkind\", \"name\", \"field\", \"members\", \"n\", \"estimate\", "
				+ "\"tstat\", \"logp\", \"mdb\", \"sig\", \"stamp\", \"note\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
		for (Object[] r : rows) {
			insert.setString(1, (String) r[0]);
			insert.setString(2, (String) r[1]);
			insert.setString(3, (String) r[2]);
			insert.setString(4, (String) r[3]);
			insert.setInt(5, (Integer) r[4]);
			for (int i = 5; i <= 8; i++)
				insert.setDouble(i + 1, (Double) r[i]);
			insert.setBoolean(10, (Boolean) r[9]);
			insert.setString(11, stamp);
			insert.setString(12, r.length > 10 ? (String) r[10] : null);
			insert.execute();
		}
	}

	@Override
	public List<Object[]> readGroupTests() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		db.getPreparedStatement(SQL_CREATE_GROUP_TEST).execute();
		ResultSet rs = db.getPreparedStatement("SELECT \"nr\", \"gkind\", \"name\", \"field\", \"members\", \"n\", \"estimate\", \"tstat\", \"logp\", \"mdb\", \"sig\", "
				+ "\"stamp\", \"note\" FROM \"KleinMethodGroupTest\" ORDER BY \"sig\" DESC, \"logp\" DESC").executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getDouble(7), rs.getDouble(8),
					rs.getDouble(9), rs.getDouble(10), rs.getBoolean(11), rs.getString(12), rs.getString(13) == null ? "" : rs.getString(13) });
		return rows;
	}

	// ============================================================================================================
	// Hoehe nur aus Hoehenfestpunkten (1D) - dynamische und fixe AGL. 3D-Fest-/Anschlusspunkte bestimmen dann nur die
	// Lage: Hoehe sigma_z0 = HEIGHT_FREE_SIGMA (frei). Dynamisch: die 3D-Anschlusspunkte bleiben, nur sigma_z0 wird
	// gesetzt, die Festpunktanalyse bewertet bei 3D-Punkten nur noch die Lage. Fix: die varianzfreien 3D-Festpunkte
	// kommen in eine eigene Gruppe beweglicher Anschlusspunkte (Lage HEIGHT_FIXED_SIGMA = praktisch fest). Merktabelle
	// "KleinMethodHeightDatum" (alter Gruppe, alte Sigmas, Stufe) - Ruecknahme exakt.

	public static final double HEIGHT_FREE_SIGMA = 10.0, HEIGHT_FIXED_SIGMA = 0.00001;
	public static final String HEIGHT_DATUM_GROUP_NAME = "Klein_Method 3D-Festpunkte Lage (Hoehe frei)";
	private static final String SQL_CREATE_HEIGHT_DATUM = "CREATE TABLE IF NOT EXISTS \"KleinMethodHeightDatum\" (\"id\" INTEGER NOT NULL, "
			+ "\"group_id\" INTEGER NOT NULL, \"s_y\" DOUBLE, \"s_x\" DOUBLE, \"s_z\" DOUBLE, \"stage\" SMALLINT NOT NULL, PRIMARY KEY (\"id\"))";
	private static final String SQL_ACTIVE_3D_OF_TYPE = "SELECT p.\"id\", p.\"group_id\", p.\"sigma_y0\", p.\"sigma_x0\", p.\"sigma_z0\" FROM \"PointApriori\" p "
			+ "JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" WHERE g.\"type\" = ? AND g.\"dimension\" = 3 AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE "
			+ "AND p.\"id\" NOT IN (SELECT \"id\" FROM \"KleinMethodHeightDatum\")";
	private static final String SQL_INSERT_HEIGHT_DATUM = "INSERT INTO \"KleinMethodHeightDatum\" (\"id\", \"group_id\", \"s_y\", \"s_x\", \"s_z\", \"stage\") VALUES (?, ?, ?, ?, ?, ?)";
	private static final String SQL_SET_POINT_SIGMAS = "UPDATE \"PointApriori\" SET \"sigma_y0\" = ?, \"sigma_x0\" = ?, \"sigma_z0\" = ? WHERE \"id\" = ?";
	private static final String SQL_SET_POINT_SIGMA_Z = "UPDATE \"PointApriori\" SET \"sigma_z0\" = ? WHERE \"id\" = ?";
	private static final String SQL_READ_POINT_SIGMA_Z = "SELECT \"sigma_z0\" FROM \"PointApriori\" WHERE \"id\" = ?";

	/** true = dynamische AGL mit "Hoehe nur aus 1D": Festpunktanalyse bewertet bei 3D-Punkten nur die Lage. */
	private volatile boolean horizontalOnly3D = false;

	@Override
	public void setHorizontalOnly3D(boolean active) {
		this.horizontalOnly3D = active;
	}

	@Override
	public boolean isHorizontalOnly3D() {
		return this.horizontalOnly3D;
	}

	private static void ensureHeightDatumTable() throws SQLException {
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_HEIGHT_DATUM).execute();
	}

	/** angewendete Stufe (2 dynamisch, 3 fix) bzw. 0 = nicht angewendet. */
	@Override
	public int appliedHeightDatumStage() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		ensureHeightDatumTable();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT MAX(\"stage\") FROM \"KleinMethodHeightDatum\"").executeQuery();
		return rs.next() ? rs.getInt(1) : 0;
	}

	/**
	 * Kennzahlen fuer die Pruefung: {Hoehenfestpunkte 1D (varianzfrei oder beweglich), davon mit Nivellement, davon
	 * varianzfrei, 3D-Fest-/Anschlusspunkte}.
	 */
	@Override
	public int[] readHeightDatumCounts() throws SQLException {
		int[] r = new int[4];
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return r;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		PreparedStatement stmt = db.getPreparedStatement("SELECT p.\"name\", g.\"type\" FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" "
				+ "WHERE g.\"dimension\" = 1 AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE AND g.\"type\" IN (?, ?)");
		stmt.setInt(1, PointType.REFERENCE_POINT.getId());
		stmt.setInt(2, PointType.STOCHASTIC_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		Set<String> heightPoints = new HashSet<String>();
		while (rs.next()) {
			heightPoints.add(rs.getString(1));
			r[0]++;
			if (rs.getInt(2) == PointType.REFERENCE_POINT.getId())
				r[2]++;
		}
		PreparedStatement lev = db.getPreparedStatement("SELECT o.\"start_point_name\", o.\"end_point_name\" FROM \"ObservationApriori\" o JOIN \"ObservationGroup\" g "
				+ "ON g.\"id\" = o.\"group_id\" WHERE g.\"type\" = ? AND g.\"enable\" = TRUE AND o.\"enable\" = TRUE");
		lev.setInt(1, ObservationType.LEVELING.getId());
		ResultSet lr = lev.executeQuery();
		Set<String> leveled = new HashSet<String>();
		while (lr.next()) {
			leveled.add(lr.getString(1));
			leveled.add(lr.getString(2));
		}
		for (String name : heightPoints)
			if (leveled.contains(name))
				r[1]++;
		PreparedStatement d3 = db.getPreparedStatement("SELECT COUNT(*) FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" "
				+ "WHERE g.\"dimension\" = 3 AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE AND g.\"type\" IN (?, ?)");
		d3.setInt(1, PointType.REFERENCE_POINT.getId());
		d3.setInt(2, PointType.STOCHASTIC_POINT.getId());
		ResultSet dr = d3.executeQuery();
		if (dr.next())
			r[3] = dr.getInt(1);
		// in der Merktabelle (schon behandelte 3D-Punkte) mitzaehlen
		ensureHeightDatumTable();
		ResultSet mr = db.getPreparedStatement("SELECT COUNT(*) FROM \"KleinMethodHeightDatum\"").executeQuery();
		if (mr.next() && r[3] == 0)
			r[3] = mr.getInt(1);
		return r;
	}

	/** anwenden fuer die Stufe (DYNAMIC/FIXED): {behandelte 3D-Punkte, angelegte Gruppe oder -1}. */
	@Override
	public int[] applyHeightDatum(Klein_Method_Adjustment_Step step) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables() || (step != Klein_Method_Adjustment_Step.DYNAMIC && step != Klein_Method_Adjustment_Step.FIXED))
			return new int[] { 0, -1 };
		ensureHeightDatumTable();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		boolean fixed = step == Klein_Method_Adjustment_Step.FIXED;
		PreparedStatement read = db.getPreparedStatement(SQL_ACTIVE_3D_OF_TYPE);
		read.setInt(1, (fixed ? PointType.REFERENCE_POINT : PointType.STOCHASTIC_POINT).getId());
		ResultSet rs = read.executeQuery();
		List<double[]> rows = new ArrayList<double[]>();
		while (rs.next())
			rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5) });
		if (rows.isEmpty())
			return new int[] { 0, -1 };
		int groupId = -1;
		if (fixed)
			groupId = this.createPointGroup(HEIGHT_DATUM_GROUP_NAME, 3, PointType.STOCHASTIC_POINT);
		PreparedStatement mark = db.getPreparedStatement(SQL_INSERT_HEIGHT_DATUM);
		for (double[] row : rows) {
			int id = (int) row[0];
			mark.setInt(1, id);
			mark.setInt(2, (int) row[1]);
			mark.setDouble(3, row[2]);
			mark.setDouble(4, row[3]);
			mark.setDouble(5, row[4]);
			mark.setInt(6, fixed ? 3 : 2);
			mark.execute();
			if (fixed) {
				PreparedStatement set = db.getPreparedStatement(SQL_SET_POINT_SIGMAS);
				set.setDouble(1, HEIGHT_FIXED_SIGMA);
				set.setDouble(2, HEIGHT_FIXED_SIGMA);
				set.setDouble(3, HEIGHT_FREE_SIGMA);
				set.setInt(4, id);
				set.execute();
				PreparedStatement move = db.getPreparedStatement(SQL_MOVE_POINT_TO_GROUP);
				move.setInt(1, groupId);
				move.setInt(2, id);
				move.execute();
			}
			// dynamisch: sigma_z0 bleibt (Startwert) - die Hoehe wird getrennt kalibriert (calibrateSeparateHeights)
		}
		return new int[] { rows.size(), groupId };
	}

	/** zuruecknehmen: Gruppe und Sigmas exakt wie vorher; leere Klein_Method-Gruppe loeschen. Liefert die Anzahl Punkte. */
	@Override
	public int undoHeightDatum() throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return 0;
		ensureHeightDatumTable();
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		ResultSet rs = db.getPreparedStatement("SELECT \"id\", \"group_id\", \"s_y\", \"s_x\", \"s_z\", \"stage\" FROM \"KleinMethodHeightDatum\"").executeQuery();
		List<double[]> rows = new ArrayList<double[]>();
		while (rs.next())
			rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6) });
		int restored = 0;
		// fix: Gruppe nur zuruecksetzen, solange der Punkt noch in der Klein_Method-Gruppe steht (sonst hat der Anwender ihn
		// verschoben); dynamisch: nur sigma_z0 (die Lage-Sigmas kalibriert die Festpunktanalyse weiter, die Gruppe kann
		// inzwischen z. B. "geplante varianzfreie Festpunkte" sein)
		PreparedStatement restoreFixed = db.getPreparedStatement("UPDATE \"PointApriori\" SET \"group_id\" = ?, \"sigma_y0\" = ?, \"sigma_x0\" = ?, \"sigma_z0\" = ? "
				+ "WHERE \"id\" = ? AND \"group_id\" IN (SELECT \"id\" FROM \"PointGroup\" WHERE \"name\" = ?)");
		PreparedStatement restoreZ = db.getPreparedStatement(SQL_SET_POINT_SIGMA_Z);
		for (double[] row : rows) {
			if ((int) row[5] == 3) {
				restoreFixed.setInt(1, (int) row[1]);
				restoreFixed.setDouble(2, row[2]);
				restoreFixed.setDouble(3, row[3]);
				restoreFixed.setDouble(4, row[4]);
				restoreFixed.setInt(5, (int) row[0]);
				restoreFixed.setString(6, HEIGHT_DATUM_GROUP_NAME);
				restored += restoreFixed.executeUpdate();
			} else if (Math.abs(row[4] - row[2]) < 1e-12) {
				// vorher einheitlich (y = x = z, wie die Festpunktanalyse es verlangt): Hoehe = aktueller Lagewert
				restored += db.getPreparedStatement("UPDATE \"PointApriori\" SET \"sigma_z0\" = \"sigma_y0\" WHERE \"id\" = " + (int) row[0]).executeUpdate();
			} else {
				restoreZ.setDouble(1, row[4]);
				restoreZ.setInt(2, (int) row[0]);
				restored += restoreZ.executeUpdate();
			}
		}
		db.getPreparedStatement("DELETE FROM \"KleinMethodHeightDatum\"").execute();
		PreparedStatement del = db.getPreparedStatement("DELETE FROM \"PointGroup\" WHERE \"name\" = ? AND \"id\" NOT IN (SELECT \"group_id\" FROM \"PointApriori\")");
		del.setString(1, HEIGHT_DATUM_GROUP_NAME);
		del.execute();
		return restored;
	}

	/**
	 * Lage-Kennwerte der beweglichen 3D-Anschlusspunkte aus der letzten Ausgleichung: je Punkt-ID {T_prio der Lage =
	 * Mittel von v^2/(sigma^2 r) ueber y und x, Omega Lage, Redundanz Lage}. Ersetzt in der dynamischen AGL mit "Hoehe
	 * nur aus 1D" die 3D-Werte von JAG3D, die die freie Hoehe (sigma_z0 = 10 m) mitzaehlen.
	 */
	/**
	 * aktive Beobachtungen, deren Genauigkeit mehr als factor-mal so gross ist wie ihr Ausgangswert
	 * (KleinMethodSigmaOrigin), absteigend nach Faktor: {Beschreibung, jetzt, Ausgang, Faktor} (Anzeigeeinheiten mm/mgon).
	 */
	@Override
	public List<String[]> readInflatedObservations(double factor) throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return new ArrayList<String[]>();
		try {
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT g.\"type\", a.\"start_point_name\", a.\"end_point_name\", a.\"sigma_0\", o.\"s0\" "
					+ "FROM \"ObservationApriori\" a JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" JOIN \"KleinMethodSigmaOrigin\" o ON o.\"kind\" = 0 AND o.\"id\" = a.\"id\" "
					+ "WHERE a.\"enable\" = TRUE AND g.\"enable\" = TRUE AND o.\"s0\" > 0 AND a.\"sigma_0\" > " + factor + " * o.\"s0\"").executeQuery();
			while (rs.next()) {
				ObservationType type = ObservationType.getEnumByValue(rs.getInt(1));
				boolean angle = type == ObservationType.DIRECTION || type == ObservationType.ZENITH_ANGLE;
				double scale = angle ? 200000.0 / Math.PI : 1000.0;
				String unit = angle ? " mgon" : " mm";
				rows.add(new Object[] { rs.getDouble(4) / rs.getDouble(5), (type == null ? "?" : type.name()) + " " + rs.getString(2) + " - " + rs.getString(3),
						String.format(java.util.Locale.GERMANY, "%.2f", rs.getDouble(4) * scale) + unit, String.format(java.util.Locale.GERMANY, "%.2f", rs.getDouble(5) * scale) + unit });
			}
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT a.\"start_point_name\", a.\"end_point_name\", a.\"sigma_y0\", a.\"sigma_x0\", a.\"sigma_z0\", "
					+ "o.\"s0\", o.\"s1\", o.\"s2\" FROM \"GNSSObservationApriori\" a JOIN \"KleinMethodSigmaOrigin\" o ON o.\"kind\" = 1 AND o.\"id\" = a.\"id\" "
					+ "WHERE a.\"enable\" = TRUE").executeQuery();
			while (rs.next()) {
				double best = 0.0, now = 0.0, origin = 0.0;
				for (int c = 0; c < 3; c++) {
					double n = rs.getDouble(3 + c), o0 = rs.getDouble(6 + c);
					if (o0 > 0.0 && n / o0 > best) {
						best = n / o0;
						now = n;
						origin = o0;
					}
				}
				if (best > factor)
					rows.add(new Object[] { best, "GNSS " + rs.getString(1) + " - " + rs.getString(2), String.format(java.util.Locale.GERMANY, "%.1f mm", now * 1000.0),
							String.format(java.util.Locale.GERMANY, "%.1f mm", origin * 1000.0) });
			}
		} catch (SQLException e) {
			// ohne Merktabelle (alte Projekte) keine Kontrolle
			return new ArrayList<String[]>();
		}
		rows.sort((x, y) -> Double.compare((Double) y[0], (Double) x[0]));
		List<String[]> result = new ArrayList<String[]>();
		for (Object[] r : rows)
			result.add(new String[] { (String) r[1], (String) r[2], (String) r[3], String.format(java.util.Locale.GERMANY, "%.1f", (Double) r[0]) });
		return result;
	}

	@Override
	public List<double[]> readAllObservationSigmas() throws SQLException {
		List<double[]> rows = new ArrayList<double[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"id\", \"sigma_0\" FROM \"ObservationApriori\"").executeQuery();
		while (rs.next())
			rows.add(new double[] { 0, rs.getInt(1), rs.getDouble(2) });
		rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"id\", \"sigma_y0\", \"sigma_x0\", \"sigma_z0\" FROM \"GNSSObservationApriori\"").executeQuery();
		while (rs.next())
			rows.add(new double[] { 1, rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4) });
		return rows;
	}

	@Override
	public void writeAllObservationSigmas(List<double[]> values) throws SQLException {
		if (values == null || !hasOpenDatabase())
			return;
		PreparedStatement terrestrial = SQLManager.getInstance().getDataBase().getPreparedStatement("UPDATE \"ObservationApriori\" SET \"sigma_0\" = ? WHERE \"id\" = ?");
		PreparedStatement gnss = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_SET_GNSS_SIGMAS);
		// HSQLDB: executeBatch() ohne addBatch() wirft "statement is not in batch mode" (z. B. Projekt ohne GNSS)
		int terrestrialCount = 0, gnssCount = 0;
		for (double[] v : values) {
			if (v[0] == 0) {
				terrestrial.setDouble(1, v[2]);
				terrestrial.setInt(2, (int) v[1]);
				terrestrial.addBatch();
				terrestrialCount++;
			} else {
				gnss.setDouble(1, v[2]);
				gnss.setDouble(2, v[3]);
				gnss.setDouble(3, v[4]);
				gnss.setInt(4, (int) v[1]);
				gnss.addBatch();
				gnssCount++;
			}
		}
		if (terrestrialCount > 0)
			terrestrial.executeBatch();
		if (gnssCount > 0)
			gnss.executeBatch();
	}

	// Fingerabdruck der zuletzt fuer die Hoehe ausgewerteten Ausgleichung - je Ausgleichung hoechstens ein Schritt
	private double separateHeightFingerprint = Double.NaN;

	/**
	 * "Hoehe nur aus 1D" in der dynamischen AGL: sigma_z0 der 3D-Anschlusspunkte getrennt von der Lage aus dem T_prio der
	 * Hoehe kalibrieren (T_z = v_z^2 / (sigma_z^2 * r_z)). Passen die Hoehen, pendelt sich sigma_z auf einen realistischen
	 * Wert ein; passen sie nicht (anderes Hoehensystem), steigt es bis HEIGHT_FREE_SIGMA. Je Ausgleichung hoechstens ein
	 * Schritt. Rueckgabe: Protokollzeilen.
	 */
	@Override
	public List<String> calibrateSeparateHeights() throws SQLException {
		List<String> lines = new ArrayList<String>();
		if (!this.horizontalOnly3D || !hasOpenDatabase())
			return lines;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT p.\"id\", p.\"name\", p.\"sigma_z0\", p.\"z0\", "
				+ "a.\"z\", a.\"redundancy_z\", p.\"group_id\" FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" "
				+ "JOIN \"PointAposteriori\" a ON a.\"id\" = p.\"id\" WHERE g.\"type\" = ? AND g.\"dimension\" = 3 AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE "
				+ "ORDER BY p.\"id\"");
		stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		List<Object[]> rows = new ArrayList<Object[]>();
		double fingerprint = 0.0;
		while (rs.next()) {
			rows.add(new Object[] { rs.getInt(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7) });
			fingerprint += rs.getDouble(5) * 1000.0 + rs.getDouble(6);
		}
		if (rows.isEmpty() || fingerprint == this.separateHeightFingerprint)
			return lines;
		this.separateHeightFingerprint = fingerprint;
		PreparedStatement write = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_SET_POINT_SIGMA_Z);
		for (Object[] row : rows) {
			double sigma = (Double) row[2];
			if (!(sigma > 0.0))
				sigma = this.readPointGroupUncertainty((Integer) row[6])[2];
			double v = (Double) row[4] - (Double) row[3], r = (Double) row[5];
			if (!(sigma > 0.0) || r < 1e-6)
				continue;
			double t = v * v / (sigma * sigma * r);
			if (t >= Klein_Method_Default_Values.SEPARATE_HEIGHT_BAND_MIN && t <= Klein_Method_Default_Values.SEPARATE_HEIGHT_BAND_MAX)
				continue;
			double factor = t < 1e-12 ? Klein_Method_Default_Values.DYNAMIC_MIN_FACTOR
					: Math.min(Math.max(1.0 + Math.log10(t), Klein_Method_Default_Values.DYNAMIC_MIN_FACTOR), Klein_Method_Default_Values.SEPARATE_HEIGHT_MAX_STEP);
			double next = Math.max(Klein_Method_Default_Values.SEPARATE_HEIGHT_FLOOR, Math.min(HEIGHT_FREE_SIGMA, sigma * factor));
			if (Math.abs(next - sigma) < 1e-12)
				continue;
			write.setDouble(1, next);
			write.setInt(2, (Integer) row[0]);
			write.execute();
			lines.add(String.format(java.util.Locale.GERMANY, "HOEHE GETRENNT %s: T_z %.4f, sigma_z %.2f mm -> %.2f mm", row[1], t, sigma * 1000.0, next * 1000.0));
		}
		return lines;
	}

	/**
	 * Zustand der Festpunkte nach der dynamischen AGL: {Name, Dimension, sigma Lage bzw. 1D (m), sigma_z (m)} fuer
	 * Hinweise im Laufbericht.
	 */
	@Override
	public List<Object[]> readStochasticPointSigmas() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT p.\"name\", g.\"dimension\", p.\"sigma_y0\", p.\"sigma_z0\", a.\"t_prio\" "
				+ "FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" LEFT JOIN \"PointAposteriori\" a ON a.\"id\" = p.\"id\" "
				+ "WHERE g.\"type\" = ? AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE ORDER BY p.\"name\"");
		stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			double tPrio = rs.getDouble(5);
			rows.add(new Object[] { rs.getString(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.wasNull() ? Double.NaN : tPrio });
		}
		return rows;
	}

	/**
	 * Ende der dynamischen AGL: bewegliche Anschlusspunkte ohne Einzelwert (sigma = 0, in JAG3D gilt dann der Gruppenwert)
	 * bekommen den wirksamen Gruppenwert als Einzelwert - je Dimension y/x (2D), z (1D) bzw. y/x/z (3D). Rechnerisch
	 * unveraendert, aber kein Feld bleibt leer und eine spaetere Aenderung des Gruppenwerts wirkt nicht still mit.
	 * Rueckgabe: Protokollzeilen.
	 */
	@Override
	public List<String> fillMissingStochasticPointSigmas() throws SQLException {
		List<String> lines = new ArrayList<String>();
		if (!hasOpenDatabase())
			return lines;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT p.\"id\", p.\"name\", p.\"group_id\", g.\"dimension\", "
				+ "p.\"sigma_y0\", p.\"sigma_x0\", p.\"sigma_z0\", a.\"t_prio\" FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" "
				+ "LEFT JOIN \"PointAposteriori\" a ON a.\"id\" = p.\"id\" WHERE g.\"type\" = ? AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE ORDER BY p.\"name\"");
		stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		List<Object[]> todo = new ArrayList<Object[]>();
		Map<Integer, double[]> groups = new java.util.HashMap<Integer, double[]>();
		while (rs.next()) {
			int dimension = rs.getInt(4);
			double sy = rs.getDouble(5), sx = rs.getDouble(6), sz = rs.getDouble(7);
			double tPrio = rs.getDouble(8);
			if (rs.wasNull())
				tPrio = Double.NaN;
			boolean needY = dimension != 1 && !(sy > 0.0), needX = dimension != 1 && !(sx > 0.0), needZ = dimension != 2 && !(sz > 0.0);
			if (!needY && !needX && !needZ)
				continue;
			int groupId = rs.getInt(3);
			double[] g = groups.get(groupId);
			if (g == null) {
				g = this.readPointGroupUncertainty(groupId);
				groups.put(groupId, g);
			}
			todo.add(new Object[] { rs.getInt(1), rs.getString(2), dimension, needY ? g[0] : sy, needX ? g[1] : sx, needZ ? g[2] : sz, needY, needX, needZ, tPrio });
		}
		PreparedStatement update = SQLManager.getInstance().getDataBase().getPreparedStatement(
				"UPDATE \"PointApriori\" SET \"sigma_y0\" = ?, \"sigma_x0\" = ?, \"sigma_z0\" = ? WHERE \"id\" = ?");
		for (Object[] t : todo) {
			int dimension = (Integer) t[2];
			double sy = (Double) t[3], sx = (Double) t[4], sz = (Double) t[5];
			update.setDouble(1, dimension == 1 ? 0.0 : sy);
			update.setDouble(2, dimension == 1 ? 0.0 : sx);
			update.setDouble(3, dimension == 2 ? 0.0 : sz);
			update.setInt(4, (Integer) t[0]);
			update.executeUpdate();
			StringBuilder parts = new StringBuilder();
			if ((Boolean) t[6])
				parts.append(" y ").append(Klein_Method_Format.format(sy * 1000.0, "", 3)).append(" mm");
			if ((Boolean) t[7])
				parts.append(" x ").append(Klein_Method_Format.format(sx * 1000.0, "", 3)).append(" mm");
			if ((Boolean) t[8])
				parts.append(" z ").append(Klein_Method_Format.format(sz * 1000.0, "", 3)).append(" mm");
			double tPrio = (Double) t[9];
			lines.add("EINZELWERT ERGAENZT: " + t[1] + " (" + dimension + "D)" + parts + " = Gruppenwert"
					+ (Double.isNaN(tPrio) ? " - kein T_prio" : tPrio <= Klein_Method_Default_Values.DYNAMIC_TPOST_ZERO
							? " - T_prio " + Klein_Method_Format.format(tPrio, "", 3) + ", mit den Messungen nicht pruefbar" : " - T_prio " + Klein_Method_Format.format(tPrio, "", 3)));
		}
		return lines;
	}

	private Map<Integer, double[]> readHorizontalPointStatistics() throws SQLException {
		Map<Integer, double[]> map = new java.util.HashMap<Integer, double[]>();
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT p.\"id\", p.\"y0\", p.\"x0\", "
				+ "CASEWHEN(p.\"sigma_y0\" > 0, p.\"sigma_y0\", NULL), CASEWHEN(p.\"sigma_x0\" > 0, p.\"sigma_x0\", NULL), a.\"y\", a.\"x\", a.\"redundancy_y\", a.\"redundancy_x\" "
				+ "FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" JOIN \"PointAposteriori\" a ON a.\"id\" = p.\"id\" "
				+ "WHERE g.\"type\" = ? AND g.\"dimension\" = 3 AND g.\"enable\" = TRUE AND p.\"enable\" = TRUE");
		stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			double sy = rs.getDouble(4);
			boolean noY = rs.wasNull();
			double sx = rs.getDouble(5);
			boolean noX = rs.wasNull();
			if (noY || noX)
				continue;
			double vy = rs.getDouble(6) - rs.getDouble(2), vx = rs.getDouble(7) - rs.getDouble(3);
			double ry = rs.getDouble(8), rx = rs.getDouble(9);
			double omega = vy * vy / (sy * sy) + vx * vx / (sx * sx);
			double t = 0;
			int n = 0;
			if (ry > 1e-6) {
				t += vy * vy / (sy * sy * ry);
				n++;
			}
			if (rx > 1e-6) {
				t += vx * vx / (sx * sx * rx);
				n++;
			}
			map.put(rs.getInt(1), new double[] { n > 0 ? t / n : Double.NaN, omega, ry + rx });
		}
		return map;
	}

	/**
	 * Mittel mit eigener Genauigkeit (zusammenfassen und kalibrieren) der Messart mit Ergebnis der letzten Ausgleichung: je
	 * Mittel {kind, id, sigma (terrestrisch) bzw. sigma_y, sigma_x, sigma_z (GNSS), omega, Redundanzanteil}.
	 */
	@Override
	public List<double[]> readCalibratedRepetitionMeans(Klein_Method_Threshold_Type logPostType) throws SQLException {
		List<double[]> rows = new ArrayList<double[]>();
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return rows;
		ensureRepetitionTable();
		ObservationType type = this.getObservationType(logPostType);
		boolean gnss = isGnssType(type);
		String sql = gnss
				? "SELECT a.\"id\", a.\"sigma_y0\", a.\"sigma_x0\", a.\"sigma_z0\", p.\"omega\", p.\"redundancy_y\" + p.\"redundancy_x\" + p.\"redundancy_z\" "
						+ "FROM \"KleinMethodRepetition\" r JOIN \"GNSSObservationApriori\" a ON a.\"id\" = r.\"id\" "
						+ "JOIN \"GNSSObservationAposteriori\" p ON p.\"id\" = a.\"id\" JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" "
						+ "WHERE r.\"kind\" = 1 AND r.\"role\" = 3 AND r.\"s0\" > 0 AND r.\"obs_type\" = ? AND a.\"enable\" = TRUE AND g.\"enable\" = TRUE"
				: "SELECT a.\"id\", a.\"sigma_0\", 0, 0, p.\"omega\", p.\"redundancy\" "
						+ "FROM \"KleinMethodRepetition\" r JOIN \"ObservationApriori\" a ON a.\"id\" = r.\"id\" "
						+ "JOIN \"ObservationAposteriori\" p ON p.\"id\" = a.\"id\" JOIN \"ObservationGroup\" g ON g.\"id\" = a.\"group_id\" "
						+ "WHERE r.\"kind\" = 0 AND r.\"role\" = 3 AND r.\"s0\" > 0 AND r.\"obs_type\" = ? AND a.\"enable\" = TRUE AND g.\"enable\" = TRUE";
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(sql);
		stmt.setInt(1, type.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			double omega = rs.getDouble(5);
			boolean noResult = rs.wasNull();
			double r = rs.getDouble(6);
			noResult |= rs.wasNull();
			if (noResult)
				continue;
			rows.add(new double[] { gnss ? REP_KIND_GNSS : REP_KIND_TERRESTRIAL, rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
					Math.abs(omega), Math.abs(r) });
		}
		return rows;
	}

	/** Genauigkeit eines kalibrierten Mittels schreiben (terrestrisch nur sigma[0]). */
	@Override
	public void writeCalibratedRepetitionMean(int kind, int id, double[] sigma) throws SQLException {
		if (!hasOpenDatabase())
			return;
		org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
		if (kind == REP_KIND_GNSS) {
			PreparedStatement set = db.getPreparedStatement(SQL_SET_GNSS_SIGMAS);
			set.setDouble(1, sigma[0]);
			set.setDouble(2, sigma[1]);
			set.setDouble(3, sigma[2]);
			set.setInt(4, id);
			set.execute();
		} else {
			PreparedStatement set = db.getPreparedStatement(SQL_WRITE_INDIVIDUAL_UNCERTAINTY);
			set.setDouble(1, sigma[0]);
			set.setInt(2, id);
			set.execute();
		}
	}

	/** true = in dieser Messart gibt es regewichtete (von der Kalibrierung ausgenommene) Beobachtungen. */
	@Override
	public boolean hasReweightedRepetitions(Klein_Method_Threshold_Type logPostType) throws SQLException {
		if (!hasOpenDatabase())
			return false;
		ensureRepetitionTable();
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(
				"SELECT COUNT(*) FROM \"KleinMethodRepetition\" WHERE \"role\" = 2 AND \"obs_type\" = ?");
		stmt.setInt(1, this.getObservationType(logPostType).getId());
		ResultSet rs = stmt.executeQuery();
		return rs.next() && rs.getInt(1) > 0;
	}

	/**
	 * Kennzahlen je Messart fuer die Vergleichsdatei: {aktive Beobachtungen, davon regewichtet, Summe Redundanzanteile,
	 * Summe Redundanz der regewichteten}. GNSS: Redundanz = Summe der drei Achsen.
	 */
	@Override
	public double[] readRepetitionStatistics(ObservationType type) throws SQLException {
		if (!hasOpenDatabase() || !this.hasProjectTables())
			return new double[4];
		ensureRepetitionTable();
		boolean gnss = isGnssType(type);
		String sql = gnss
				? "SELECT o.\"id\", COALESCE(p.\"redundancy_y\", 0) + COALESCE(p.\"redundancy_x\", 0) + COALESCE(p.\"redundancy_z\", 0), "
						+ "CASE WHEN r.\"id\" IS NULL THEN 0 ELSE 1 END FROM \"GNSSObservationApriori\" o "
						+ "JOIN \"ObservationGroup\" g ON o.\"group_id\" = g.\"id\" LEFT JOIN \"GNSSObservationAposteriori\" p ON p.\"id\" = o.\"id\" "
						+ "LEFT JOIN \"KleinMethodRepetition\" r ON r.\"kind\" = 1 AND r.\"id\" = o.\"id\" AND r.\"role\" = 2 "
						+ "WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"type\" = ?"
				: "SELECT o.\"id\", COALESCE(p.\"redundancy\", 0), CASE WHEN r.\"id\" IS NULL THEN 0 ELSE 1 END FROM \"ObservationApriori\" o "
						+ "JOIN \"ObservationGroup\" g ON o.\"group_id\" = g.\"id\" LEFT JOIN \"ObservationAposteriori\" p ON p.\"id\" = o.\"id\" "
						+ "LEFT JOIN \"KleinMethodRepetition\" r ON r.\"kind\" = 0 AND r.\"id\" = o.\"id\" AND r.\"role\" = 2 "
						+ "WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"type\" = ?";
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(sql);
		stmt.setInt(1, type.getId());
		ResultSet rs = stmt.executeQuery();
		double[] s = new double[4];
		while (rs.next()) {
			double r = rs.getDouble(2);
			boolean frozen = rs.getInt(3) == 1;
			s[0]++;
			s[2] += r;
			if (frozen) {
				s[1]++;
				s[3] += r;
			}
		}
		return s;
	}

	@Override
	public List<Integer> readActiveGnssObservationIds(String pointName) throws SQLException {
		List<Integer> ids = new ArrayList<Integer>();
		if (!hasOpenDatabase())
			return ids;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_GNSS_IDS_OF_POINT);
		stmt.setString(1, pointName);
		stmt.setString(2, pointName);
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			ids.add(rs.getInt(1));
		return ids;
	}

	@Override
	public void setGnssObservationEnabled(int observationId, boolean enabled) throws SQLException {
		if (!hasOpenDatabase())
			return;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_SET_GNSS_OBSERVATION_ENABLED);
		stmt.setBoolean(1, enabled);
		stmt.setInt(2, observationId);
		stmt.execute();
	}

	@Override
	public void deactivateGnssObservation(int observationId) throws SQLException {
		this.calibrationPart.deactivateGnssObservation(observationId);
	}

	@Override
	public void readGnssBaselineDeactivationCounts(Klein_Method_Parameter parameter) throws SQLException {
		this.calibrationPart.readGnssBaselineDeactivationCounts(parameter);
	}

	@Override
	public Set<ObservationType> readActiveObservationTypes() throws SQLException {
		return this.statusPart.readActiveObservationTypes();
	}

	@Override
	public double readGlobalVarianceFactor() throws SQLException {
		if (!hasOpenDatabase())
			return Double.NaN;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_VARIANCE_RATIO);
		stmt.setInt(1, VarianceComponentType.GLOBAL.getId());
		ResultSet rs = stmt.executeQuery();
		if (rs.next()) {
			double value = rs.getDouble("sigma2apost");
			if (!rs.wasNull() && Double.isFinite(value) && value > 0.0)
				return value;
		}
		return Double.NaN;
	}

	@Override
	public boolean hasActiveGnssObservations() throws SQLException {
		return this.statusPart.hasActiveGnssObservations();
	}

	private static final String SQL_OBSERVATION_COUNT =
			"SELECT COUNT(*) AS \"cnt\" FROM \"ObservationApriori\"";

	@Override
	public long readObservationCount() throws SQLException {
		return this.statusPart.readObservationCount();
	}

	private static final String SQL_ACTIVE_POINT_TYPES =
			"SELECT DISTINCT \"type\" FROM \"PointGroup\" WHERE \"enable\" = TRUE AND EXISTS ("
			+ "SELECT 1 FROM \"PointApriori\" WHERE \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" "
			+ "AND \"PointApriori\".\"enable\" = TRUE)";

	@Override
	public Set<PointType> readActivePointTypes() throws SQLException {
		return this.statusPart.readActivePointTypes();
	}

	// Eigene Tabelle in der JAG3D-Projektdatenbank selbst - reist automatisch
	// mit dem Projekt mit (siehe Klein_Method_Database_Handler_Interface.
	// storeStepChecksum()). "IF NOT EXISTS" ist HSQLDB-Standard-Syntax.
	private static final String SQL_CREATE_CHECKSUM_TABLE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodStepChecksum\" (" +
			"\"step\" VARCHAR(20) NOT NULL, \"checksum\" VARCHAR(64) NOT NULL, PRIMARY KEY (\"step\"))";

	private static final String SQL_MERGE_CHECKSUM =
			"MERGE INTO \"KleinMethodStepChecksum\" USING (VALUES (CAST(? AS VARCHAR(20)), CAST(? AS VARCHAR(64)))) " +
			"AS \"vals\" (\"step\", \"checksum\") " +
			"ON \"KleinMethodStepChecksum\".\"step\" = \"vals\".\"step\" " +
			"WHEN MATCHED THEN UPDATE SET \"KleinMethodStepChecksum\".\"checksum\" = \"vals\".\"checksum\" " +
			"WHEN NOT MATCHED THEN INSERT (\"step\", \"checksum\") VALUES (\"vals\".\"step\", \"vals\".\"checksum\")";

	private static final String SQL_READ_CHECKSUM =
			"SELECT \"checksum\" FROM \"KleinMethodStepChecksum\" WHERE \"step\" = ?";

	// Alle Gruppenunsicherheits-Werte ALLER aktiven Gruppen, feste Reihenfolge
	private static final String SQL_ALL_GROUP_UNCERTAINTIES_FOR_CHECKSUM =
			"SELECT \"ObservationGroupUncertainty\".\"group_id\", \"ObservationGroupUncertainty\".\"type\", \"ObservationGroupUncertainty\".\"value\" " +
			"FROM \"ObservationGroupUncertainty\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationGroupUncertainty\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"ORDER BY \"ObservationGroupUncertainty\".\"group_id\" ASC, \"ObservationGroupUncertainty\".\"type\" ASC";

	// Alle individuellen Messunsicherheiten ALLER aktiven Beobachtungen, feste Reihenfolge
	private static final String SQL_ALL_INDIVIDUAL_UNCERTAINTIES_FOR_CHECKSUM =
			"SELECT \"ObservationApriori\".\"id\", \"ObservationApriori\".\"sigma_0\" " +
			"FROM \"ObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationApriori\".\"enable\" = TRUE AND \"ObservationGroup\".\"enable\" = TRUE " +
			"ORDER BY \"ObservationApriori\".\"id\" ASC";

	// Wird z.B. waehrend/nach einem validierten Ausgleichungsschritt in einer anderen JAG3D-Ansicht (z.B.
	// Schraegstrecken-Eigenschaften) ein Zusatzparameter neu aktiviert, aendert sich das Ausgleichungsmodell (ein
	// zusaetzlicher Parameter wird geschaetzt), OHNE dass sich die Pruefsumme aendert - der vorherige Schritt gilt
	// dadurch faelschlich weiterhin als gueltig bestanden.
	private static final String SQL_ALL_ADDITIONAL_PARAMETERS_FOR_CHECKSUM =
			"SELECT \"AdditionalParameterApriori\".\"id\", \"AdditionalParameterApriori\".\"group_id\", " +
			"\"AdditionalParameterApriori\".\"type\", \"AdditionalParameterApriori\".\"enable\" " +
			"FROM \"AdditionalParameterApriori\" " +
			"JOIN \"ObservationGroup\" ON \"AdditionalParameterApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"ORDER BY \"AdditionalParameterApriori\".\"id\" ASC";

	// VERWANDTE LUECKE (im Zuge derselben Pruefung gefunden): gibt es MEHRERE Punktgruppen DERSELBEN Kategorie (z.B.
	// zwei "Datumsgebende Neupunkte"- Gruppen), aendert das Deaktivieren einer davon NICHT zwingend den von
	// getCurrentAdjustmentStep() erkannten Schritt (die Kategorie bleibt ja vorhanden) - das Modell aendert sich
	// trotzdem (weniger Datumspunkte).

	// Koordinatensystem-Anzeige im Tab "Optionen" - zeigt den echten Wert statt des fruehen Platzhalters "unbekannt".
	private static final String SQL_PROJECTION_TYPE =
			"SELECT \"projection_type\" FROM \"ReductionDefinition\" WHERE \"id\" = 1";

	@Override
	public String readProjectionTypeDisplayName() throws SQLException {
		return this.statusPart.readProjectionTypeDisplayName();
	}

	private void ensureChecksumTableExists() throws SQLException {
		this.statusPart.ensureChecksumTableExists();
	}

	@Override
	public String computeCurrentStepChecksum() throws SQLException {
		return this.statusPart.computeCurrentStepChecksum();
	}

	@Override
	public void storeStepChecksum(Klein_Method_Adjustment_Step step, String checksum) throws SQLException {
		this.statusPart.storeStepChecksum(step, checksum);
	}

	@Override
	public String readStoredStepChecksum(Klein_Method_Adjustment_Step step) throws SQLException {
		return this.statusPart.readStoredStepChecksum(step);
	}

	// statt IMMER gegen den historischen Snapshot vom Abschluss der freien AGL zu vergleichen, wird jetzt nur noch
	// GEGEN DIE LETZTE EIGENE PRUEFUNG verglichen ("hat sich seit dem letzten Tab-Besuch ein ECHTER Sprung
	// ereignet"). Nutzt dieselbe Tabelle, aber mit EINEM festen Schluessel statt einem Schluessel je
	// Ausgleichungsschritt.
	private static final String LAST_VERIFIED_CHECKSUM_KEY = "LAST_VERIFIED";

	@Override
	public void storeLastVerifiedChecksum(String checksum) throws SQLException {
		this.statusPart.storeLastVerifiedChecksum(checksum);
	}

	// Liegt in derselben Projekt-Tabelle wie die Pruefsummen (fester Schluessel), reist also mit dem Projekt mit.
	// Leerer Wert = keine Sperre.
	private static final String COMPLETED_STEP_KEY = "AUTO_DONE_STEP";

	@Override
	public void storeCompletedAutomaticStep(Klein_Method_Adjustment_Step step) throws SQLException {
		this.statusPart.storeCompletedAutomaticStep(step);
	}

	@Override
	public Klein_Method_Adjustment_Step readCompletedAutomaticStep() throws SQLException {
		return this.statusPart.readCompletedAutomaticStep();
	}

	// Mittel der ausgeglichenen Koordinaten fuer die Lotrichtung. Spalten "x","y","z" bestaetigt aus JAG3D
	// SQLDatabase.java (CREATE TABLE "PointAposteriori").
	private static final String SQL_MEAN_APOSTERIORI_POINT =
			"SELECT AVG(\"x\") AS \"mx\", AVG(\"y\") AS \"my\", AVG(\"z\") AS \"mz\", COUNT(*) AS \"cnt\" FROM \"PointAposteriori\"";

	// ausgeglichene Koordinaten je Punktname und Start-/Endpunkt je aktiver GNSS-Basislinie (Spalten bestaetigt aus
	// JAG3D SQLDatabase.java).
	private static final String SQL_APOSTERIORI_POINTS_BY_NAME =
			"SELECT \"PointApriori\".\"name\" AS \"name\", \"PointAposteriori\".\"x\" AS \"x\", \"PointAposteriori\".\"y\" AS \"y\", \"PointAposteriori\".\"z\" AS \"z\" "
			+ "FROM \"PointApriori\" JOIN \"PointAposteriori\" ON \"PointAposteriori\".\"id\" = \"PointApriori\".\"id\"";
	private static final String SQL_GNSS_BASELINE_ENDPOINTS =
			"SELECT \"id\", \"start_point_name\", \"end_point_name\" FROM \"GNSSObservationApriori\" WHERE \"enable\" = TRUE";

	@Override
	public Map<Integer, double[]> readGnssBaselineMidpoints() throws SQLException {
		return this.statusPart.readGnssBaselineMidpoints();
	}

	@Override
	public double[] readMeanAposterioriPointCoordinates() throws SQLException {
		return this.statusPart.readMeanAposterioriPointCoordinates();
	}

	@Override
	public String readLastVerifiedChecksum() throws SQLException {
		return this.statusPart.readLastVerifiedChecksum();
	}

	// Zusatzparameter (Tab "Zusatzparameter" in JAG3D) - nur AKTIVE ("enable"=TRUE) Eintraege beruecksichtigen, laut
	// Vorgabe kann ohnehin nur eine Gruppe je Typ diese Parameter aktiv haben.
	private static final String SQL_ADDITIONAL_PARAMETERS =
			"SELECT \"AdditionalParameterApriori\".\"type\", \"AdditionalParameterAposteriori\".\"value\" " +
			"FROM \"AdditionalParameterApriori\" " +
			"JOIN \"ObservationGroup\" ON \"AdditionalParameterApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"LEFT JOIN \"AdditionalParameterAposteriori\" ON \"AdditionalParameterApriori\".\"id\" = \"AdditionalParameterAposteriori\".\"id\" " +
			"WHERE \"ObservationGroup\".\"type\" = ? AND \"AdditionalParameterApriori\".\"enable\" = TRUE";

	@Override
	public double[] readAdditionalParameters(Klein_Method_Threshold_Type distanceType) throws SQLException {
		return this.calibrationPart.readAdditionalParameters(distanceType);
	}

	// Liefert ALLE aktiven Gruppen eines Streckentyps mit ihrem enable-Status
	// fuer Nullpunktabweichung/Massstab - anders als SQL_ADDITIONAL_PARAMETERS
	// OHNE "enable"=TRUE-Filter, damit auch deaktivierte Gruppen sichtbar sind
	// (fuer die Konsistenzpruefung/Checkbox-Logik der neuen Zusatzparameter-Haken).
	private static final String SQL_ADDITIONAL_PARAMETER_GROUP_STATES =
			"SELECT \"AdditionalParameterApriori\".\"group_id\", \"AdditionalParameterApriori\".\"type\", \"AdditionalParameterApriori\".\"enable\" " +
			"FROM \"AdditionalParameterApriori\" " +
			"JOIN \"ObservationGroup\" ON \"AdditionalParameterApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"type\" = ? AND \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"AdditionalParameterApriori\".\"type\" IN (?, ?) " +
			"ORDER BY \"AdditionalParameterApriori\".\"group_id\" ASC";

	@Override
	public Map<Integer, boolean[]> readAdditionalParameterGroupStates(Klein_Method_Threshold_Type distanceType) throws SQLException {
		return this.calibrationPart.readAdditionalParameterGroupStates(distanceType);
	}

	private static final String SQL_WRITE_ADDITIONAL_PARAMETER_ENABLED =
			"UPDATE \"AdditionalParameterApriori\" SET \"enable\" = ? WHERE \"group_id\" = ? AND \"type\" = ?";

	@Override
	public void writeAdditionalParameterEnabled(int groupId, boolean isScale, boolean enabled) throws SQLException {
		this.calibrationPart.writeAdditionalParameterEnabled(groupId, isScale, enabled);
	}

	private static final String SQL_ALL_OBSERVATIONS_FOR_EP =
			"SELECT \"ObservationApriori\".\"id\" AS \"id\", \"ObservationApriori\".\"group_id\" AS \"group_id\", " +
			"ABS(\"ObservationAposteriori\".\"influence_on_position\") AS \"ep\", ABS(\"p_post\") AS \"abs_log_post\", " +
			"\"ObservationApriori\".\"sigma_0\" AS \"sigma_0\", \"ObservationApriori\".\"distance_0\" AS \"distance_0\", " +
			"\"PS\".\"x0\" AS \"ps_x\", \"PS\".\"y0\" AS \"ps_y\", \"PS\".\"z0\" AS \"ps_z\", " +
			"\"PE\".\"x0\" AS \"pe_x\", \"PE\".\"y0\" AS \"pe_y\", \"PE\".\"z0\" AS \"pe_z\", " +
			"\"ObservationApriori\".\"instrument_height\" AS \"ih\", \"ObservationApriori\".\"reflector_height\" AS \"th\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"LEFT JOIN \"PointApriori\" AS \"PS\" ON \"PS\".\"name\" = \"ObservationApriori\".\"start_point_name\" " +
			"LEFT JOIN \"PointApriori\" AS \"PE\" ON \"PE\".\"name\" = \"ObservationApriori\".\"end_point_name\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE AND \"ObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"p_post\" IS NOT NULL AND \"influence_on_position\" IS NOT NULL" + REPETITION_FILTER_TERRESTRIAL;

	@Override
	public List<Klein_Method_Ep_Row> readAllObservationsForEpCalibration(Klein_Method_Threshold_Type type) throws SQLException {
		return this.calibrationPart.readAllObservationsForEpCalibration(type);
	}

	// GNSS-Pendant zu SQL_ALL_OBSERVATIONS_FOR_EP - dieselbe Tabellen-/Spaltenauswahl wie
	// SQL_GNSS_OBSERVATIONS_IN_BAND, aber OHNE den Bereichsfilter ("AND ABS(p_post) >=? AND ABS(p_post) <= ?") -
	// analog zum Unterschied SQL_OBSERVATIONS_IN_BAND vs. SQL_ALL_OBSERVATIONS_FOR_EP beim generischen Pendant.
	private static final String SQL_ALL_GNSS_OBSERVATIONS_FOR_EP =
			"SELECT \"GNSSObservationApriori\".\"id\" AS \"id\", \"GNSSObservationApriori\".\"group_id\" AS \"group_id\", " +
			"ABS(\"GNSSObservationAposteriori\".\"p_post\") AS \"abs_log_post\", " +
			"\"GNSSObservationApriori\".\"sigma_x0\" AS \"sigma_x0\", " +
			"\"GNSSObservationApriori\".\"sigma_y0\" AS \"sigma_y0\", " +
			"\"GNSSObservationApriori\".\"sigma_z0\" AS \"sigma_z0\", " +
			"\"GNSSObservationApriori\".\"x0\" AS \"comp_x\", \"GNSSObservationApriori\".\"y0\" AS \"comp_y\", \"GNSSObservationApriori\".\"z0\" AS \"comp_z\", " +
			"\"GNSSObservationAposteriori\".\"influence_on_position_x\" AS \"ep_x\", " +
			"\"GNSSObservationAposteriori\".\"influence_on_position_y\" AS \"ep_y\", " +
			"\"GNSSObservationAposteriori\".\"influence_on_position_z\" AS \"ep_z\" " +
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE " +
			"AND \"GNSSObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationGroup\".\"type\" = ? " +
			"AND \"GNSSObservationAposteriori\".\"p_post\" IS NOT NULL" + REPETITION_FILTER_GNSS;

	@Override
	public List<Klein_Method_Ep_Row> readAllGnssObservationsForEpCalibration(Klein_Method_Threshold_Type type) throws SQLException {
		return this.calibrationPart.readAllGnssObservationsForEpCalibration(type);
	}

	// Ergebniskoordinaten ALLER aktiven Punkte, unabhaengig von Kategorie/
	// Dimension - "y"/"x"/"z" sind die A-POSTERIORI-Spalten (Tab "Ergebnis"),
	// NICHT die a-priori "y0"/"x0"/"z0" - koennen je nach Punktdimension
	// fehlen (LEFT JOIN, daher NULL moeglich -> "k.A." beim Export).
	private static final String SQL_ALL_POINT_COORDINATES =
			"SELECT \"PointApriori\".\"name\" AS \"name\", \"PointApriori\".\"code\" AS \"code\", " +
			"\"PointAposteriori\".\"y\" AS \"y\", \"PointAposteriori\".\"x\" AS \"x\", \"PointAposteriori\".\"z\" AS \"z\" " +
			"FROM \"PointApriori\" " +
			"JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" " +
			"LEFT JOIN \"PointAposteriori\" ON \"PointApriori\".\"id\" = \"PointAposteriori\".\"id\" " +
			"WHERE \"PointGroup\".\"enable\" = TRUE AND \"PointApriori\".\"enable\" = TRUE " +
			"ORDER BY \"PointApriori\".\"name\" ASC";

	@Override
	public List<Klein_Method_Point_Coordinate_Row> readAllActivePointCoordinates() throws SQLException {
		return this.coordinatePart.readAllActivePointCoordinates();
	}

	// A-PRIORI-Pendant zu SQL_ALL_POINT_COORDINATES - dieselbe Tabellen- /Gruppenauswahl, aber "y0"/"x0"/"z0"
	// (A-PRIORI-Spalten, "PointApriori") statt "y"/"x"/"z" (A-POSTERIORI, "PointAposteriori") - noetig, weil der
	// Fangkreis-Scan VOR dem ersten Ausgleichungslauf laufen soll, es also noch keine A-POSTERIORI-Werte gibt. Kein
	// LEFT JOIN auf "PointAposteriori" mehr noetig, da "y0"/"x0"/"z0" bereits Teil von "PointApriori" selbst sind.
	// EINSCHRAENKUNG (siehe ausfuehrliche Begruendung in Klein_Method_Functions.scanForFangkreisViolations()): die
	// Spaltennamen "y0"/"x0"/"z0" sind NICHT unabhaengig am JAG3D-Quellcode verifiziert, sondern nach demselben,
	// bereits bestaetigten Namensschema wie "sigma_y0"/"sigma_x0"/"sigma_z0" in DERSELBEN Tabelle (siehe
	// SQL_STOCHASTIC_POINT_APRIORI_UNCERTAINTIES) abge-leitet - rein LESENDER Zugriff, daher risikoarm selbst falls
	// doch abweichend.
	private static final String SQL_ALL_POINT_APRIORI_COORDINATES_FOR_FANGKREIS =
			"SELECT \"PointApriori\".\"id\" AS \"id\", \"PointApriori\".\"name\" AS \"name\", \"PointApriori\".\"code\" AS \"code\", " +
			"\"PointApriori\".\"y0\" AS \"y\", \"PointApriori\".\"x0\" AS \"x\", \"PointApriori\".\"z0\" AS \"z\" " +
			"FROM \"PointApriori\" " +
			"JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" " +
			"WHERE \"PointGroup\".\"enable\" = TRUE AND \"PointApriori\".\"enable\" = TRUE " +
			"ORDER BY \"PointApriori\".\"name\" ASC";

	// Festpunkt-Empfehlung - Punkte mit Art, Dimension, Koordinaten, Unsicherheit, nivelliert ja/nein
	static final String SQL_FIXED_POINT_RECOMMENDATION_POINTS =
			"SELECT \"P\".\"id\" AS \"id\", \"P\".\"name\" AS \"name\", \"G\".\"type\" AS \"type\", \"G\".\"dimension\" AS \"dimension\", " +
			"\"P\".\"y0\" AS \"y\", \"P\".\"x0\" AS \"x\", \"P\".\"z0\" AS \"z\", \"P\".\"sigma_y0\" AS \"sigma_y0\", \"P\".\"sigma_z0\" AS \"sigma_z0\", " +
			"CASE WHEN EXISTS (SELECT 1 FROM \"ObservationApriori\" AS \"O\" JOIN \"ObservationGroup\" AS \"OG\" ON \"O\".\"group_id\" = \"OG\".\"id\" " +
			"WHERE \"OG\".\"type\" = ? AND \"OG\".\"enable\" = TRUE AND \"O\".\"enable\" = TRUE " +
			"AND (\"O\".\"start_point_name\" = \"P\".\"name\" OR \"O\".\"end_point_name\" = \"P\".\"name\")) THEN TRUE ELSE FALSE END AS \"leveled\" " +
			"FROM \"PointApriori\" AS \"P\" " +
			"JOIN \"PointGroup\" AS \"G\" ON \"P\".\"group_id\" = \"G\".\"id\" " +
			"WHERE \"G\".\"enable\" = TRUE AND \"P\".\"enable\" = TRUE " +
			"ORDER BY \"P\".\"name\" ASC";

	@Override
	public List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> readFixedPointRecommendationPoints() throws SQLException {
		return this.coordinatePart.readFixedPointRecommendationPoints();
	}

	// Lage aus der letzten freien Ausgleichung (Kongruenzpruefung der Altpunkte)
	static final String SQL_CREATE_FREE_SOLUTION_TABLE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodFreeSolution\" (\"name\" VARCHAR(256) NOT NULL, \"y\" DOUBLE NOT NULL, \"x\" DOUBLE NOT NULL, PRIMARY KEY (\"name\"))";
	static final String SQL_DELETE_FREE_SOLUTION = "DELETE FROM \"KleinMethodFreeSolution\"";
	static final String SQL_INSERT_FREE_SOLUTION = "INSERT INTO \"KleinMethodFreeSolution\" (\"name\", \"y\", \"x\") VALUES (?, ?, ?)";
	static final String SQL_READ_FREE_SOLUTION = "SELECT \"name\", \"y\", \"x\" FROM \"KleinMethodFreeSolution\"";

	@Override
	public void storeFreeSolution(List<Klein_Method_Point_Coordinate_Row> rows) throws SQLException {
		this.coordinatePart.storeFreeSolution(rows);
	}

	@Override
	public java.util.Map<String, double[]> readFreeSolution() throws SQLException {
		return this.coordinatePart.readFreeSolution();
	}

	// Naeherungskoordinaten (eigene robuste Berechnung, Pruefung in der freien AGL)
	static final String SQL_APPROXIMATION_POINTS =
			"SELECT \"PointApriori\".\"name\", \"PointApriori\".\"y0\", \"PointApriori\".\"x0\", \"PointApriori\".\"z0\", " +
			"\"PointGroup\".\"type\", \"PointGroup\".\"dimension\" FROM \"PointApriori\" " +
			"JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" " +
			"WHERE \"PointApriori\".\"enable\" = TRUE AND \"PointGroup\".\"enable\" = TRUE";
	static final String SQL_APPROXIMATION_OBSERVATIONS =
			"SELECT \"ObservationApriori\".\"group_id\", \"ObservationGroup\".\"type\", \"ObservationApriori\".\"start_point_name\", " +
			"\"ObservationApriori\".\"end_point_name\", \"ObservationApriori\".\"instrument_height\", \"ObservationApriori\".\"reflector_height\", " +
			"\"ObservationApriori\".\"value_0\" FROM \"ObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationApriori\".\"enable\" = TRUE AND \"ObservationGroup\".\"enable\" = TRUE " +
			"ORDER BY \"ObservationApriori\".\"id\" ASC";
	static final String SQL_APPROXIMATION_GNSS =
			"SELECT \"GNSSObservationApriori\".\"group_id\", \"ObservationGroup\".\"type\", \"GNSSObservationApriori\".\"start_point_name\", " +
			"\"GNSSObservationApriori\".\"end_point_name\", \"GNSSObservationApriori\".\"y0\", \"GNSSObservationApriori\".\"x0\", " +
			"\"GNSSObservationApriori\".\"z0\", \"GNSSObservationApriori\".\"id\" FROM \"GNSSObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"GNSSObservationApriori\".\"enable\" = TRUE AND \"ObservationGroup\".\"enable\" = TRUE " +
			"ORDER BY \"GNSSObservationApriori\".\"id\" ASC";
	static final String SQL_EARTH_CURVATURE =
			"SELECT COUNT(*) AS \"n\" FROM \"ReductionTask\" WHERE \"reduction_id\" = 1 AND \"type\" = 4";
	// JAG3D hat "earth_radius" 2021 durch Ellipsoid (major_axis/minor_axis) und Bezugsbreite ersetzt - Radius wie
	// JAG3D Reduction.getEarthRadius() = Radius der konformen Kugel in der Bezugsbreite
	static final String SQL_EARTH_RADIUS = "SELECT \"major_axis\", \"minor_axis\", \"reference_latitude\" FROM \"ReductionDefinition\" WHERE \"id\" = 1";
	static final String SQL_CREATE_APPROXIMATION_BACKUP =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodApproxBackup\" (\"name\" VARCHAR(256) NOT NULL, \"y0\" DOUBLE NOT NULL, " +
			"\"x0\" DOUBLE NOT NULL, \"z0\" DOUBLE NOT NULL, \"stamp\" VARCHAR(32) NOT NULL, PRIMARY KEY (\"name\"))";
	static final String SQL_DELETE_APPROXIMATION_BACKUP = "DELETE FROM \"KleinMethodApproxBackup\"";
	static final String SQL_INSERT_APPROXIMATION_BACKUP =
			"INSERT INTO \"KleinMethodApproxBackup\" (\"name\", \"y0\", \"x0\", \"z0\", \"stamp\") " +
			"SELECT \"name\", \"y0\", \"x0\", \"z0\", ? FROM \"PointApriori\" WHERE \"name\" = ?";
	static final String SQL_READ_APPROXIMATION_BACKUP = "SELECT \"name\", \"y0\", \"x0\", \"z0\", \"stamp\" FROM \"KleinMethodApproxBackup\"";
	static final String SQL_UPDATE_APPROXIMATION_POSITION = "UPDATE \"PointApriori\" SET \"y0\" = ?, \"x0\" = ? WHERE \"name\" = ?";
	static final String SQL_UPDATE_APPROXIMATION_HEIGHT = "UPDATE \"PointApriori\" SET \"z0\" = ? WHERE \"name\" = ?";
	static final String SQL_UPDATE_APPROXIMATION_ALL = "UPDATE \"PointApriori\" SET \"y0\" = ?, \"x0\" = ?, \"z0\" = ? WHERE \"name\" = ?";

	@Override
	public List<Klein_Method_Functions.Klein_Method_Approximation.Point> readApproximationPoints() throws SQLException {
		return this.coordinatePart.readApproximationPoints();
	}

	@Override
	public List<Klein_Method_Functions.Klein_Method_Approximation.Observation> readApproximationObservations() throws SQLException {
		return this.coordinatePart.readApproximationObservations();
	}

	@Override
	public double[] readEarthCurvatureSetting() throws SQLException {
		return this.coordinatePart.readEarthCurvatureSetting();
	}

	/** Wie JAG3D Ellipsoid.getRadiusOfConformalSphere (Breite im Bogenmass); Kugel: a. */
	static double conformalSphereRadius(double majorAxis, double minorAxis, double latitude) {
		if (!(majorAxis > 0.0) || !(minorAxis > 0.0))
			return 0.0;
		if (Math.abs(majorAxis - minorAxis) < 1.0e-9)
			return majorAxis;
		double e2 = (majorAxis * majorAxis - minorAxis * minorAxis) / (majorAxis * majorAxis);
		double sin = Math.sin(latitude);
		return majorAxis * Math.sqrt(1.0 - e2) / (1.0 - e2 * sin * sin);
	}

	@Override
	public int writeApproximations(List<Klein_Method_Functions.Klein_Method_Approximation.PointResult> points, String stamp) throws SQLException {
		return this.coordinatePart.writeApproximations(points, stamp);
	}

	@Override
	public int restoreApproximationBackup() throws SQLException {
		return this.coordinatePart.restoreApproximationBackup();
	}

	@Override
	public String[] readApproximationBackupInfo() throws SQLException {
		return this.coordinatePart.readApproximationBackupInfo();
	}

	// Koordinatenumrechnung WGS84 (geozentrisch) -> lokal (ENh) im Projekt
	static final String SQL_POINT_NAMES = "SELECT \"id\", \"name\" FROM \"PointApriori\"";
	static final String SQL_LOCAL_POINTS =
			"SELECT \"PointApriori\".\"id\", \"PointGroup\".\"dimension\", \"PointApriori\".\"y0\", \"PointApriori\".\"x0\", \"PointApriori\".\"z0\", " +
			"\"PointGroup\".\"type\" FROM \"PointApriori\" JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\"";
	/** einzelnen Punkt bzw. eine Basislinie zur Sicherung der Umrechnung hinzufuegen, wenn noch nicht enthalten. */
	static final String SQL_ADD_LOCAL_BACKUP_POINT =
			"INSERT INTO \"KleinMethodLocalBackupPoint\" (\"id\", \"y0\", \"x0\", \"z0\") SELECT \"id\", \"y0\", \"x0\", \"z0\" FROM \"PointApriori\" " +
			"WHERE \"id\" = ? AND NOT EXISTS (SELECT 1 FROM \"KleinMethodLocalBackupPoint\" WHERE \"id\" = ?)";
	static final String SQL_ADD_LOCAL_BACKUP_BASELINE =
			"INSERT INTO \"KleinMethodLocalBackupBaseline\" (\"id\", \"y0\", \"x0\", \"z0\") SELECT \"id\", \"y0\", \"x0\", \"z0\" FROM \"GNSSObservationApriori\" " +
			"WHERE \"id\" = ? AND NOT EXISTS (SELECT 1 FROM \"KleinMethodLocalBackupBaseline\" WHERE \"id\" = ?)";
	static final String SQL_LOCAL_BASELINES =
			"SELECT \"GNSSObservationApriori\".\"id\", \"ObservationGroup\".\"type\", \"GNSSObservationApriori\".\"y0\", \"GNSSObservationApriori\".\"x0\", " +
			"\"GNSSObservationApriori\".\"z0\", COALESCE(\"PS\".\"id\", -1), COALESCE(\"PE\".\"id\", -1), \"GNSSObservationApriori\".\"enable\", \"GNSSObservationApriori\".\"group_id\" " +
			"FROM \"GNSSObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"LEFT JOIN \"PointApriori\" AS \"PS\" ON \"PS\".\"name\" = \"GNSSObservationApriori\".\"start_point_name\" " +
			"LEFT JOIN \"PointApriori\" AS \"PE\" ON \"PE\".\"name\" = \"GNSSObservationApriori\".\"end_point_name\"";
	static final String SQL_ZENITH_LEVELING_COUNT =
			"SELECT COUNT(*) AS \"n\" FROM \"ObservationApriori\" JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationApriori\".\"enable\" = TRUE AND \"ObservationGroup\".\"enable\" = TRUE AND \"ObservationGroup\".\"type\" IN (1, 5)";
	static final String SQL_CREATE_LOCAL_BACKUP_POINTS =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodLocalBackupPoint\" (\"id\" INTEGER NOT NULL, \"y0\" DOUBLE NOT NULL, \"x0\" DOUBLE NOT NULL, " +
			"\"z0\" DOUBLE NOT NULL, PRIMARY KEY (\"id\"))";
	static final String SQL_CREATE_LOCAL_BACKUP_BASELINES =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodLocalBackupBaseline\" (\"id\" INTEGER NOT NULL, \"y0\" DOUBLE NOT NULL, \"x0\" DOUBLE NOT NULL, " +
			"\"z0\" DOUBLE NOT NULL, PRIMARY KEY (\"id\"))";
	static final String SQL_READ_PRINCIPAL_POINT = "SELECT \"x0\", \"y0\", \"z0\", \"reference_latitude\", \"reference_longitude\", "
			+ "\"reference_height\", \"major_axis\", \"minor_axis\" FROM \"ReductionDefinition\" WHERE \"id\" = 1";
	static final String SQL_UPDATE_PRINCIPAL_POINT = "UPDATE \"ReductionDefinition\" SET \"x0\" = ?, \"y0\" = ?, \"z0\" = ?, "
			+ "\"reference_latitude\" = ?, \"reference_longitude\" = ?, \"reference_height\" = ?, \"major_axis\" = ?, \"minor_axis\" = ? WHERE \"id\" = 1";
	/** Bezugspunkt (Hauptpunkt) vor der ersten Umrechnung - fuer "Projekt-Umrechnung zuruecknehmen". */
	static final String SQL_CREATE_LOCAL_BACKUP_REDUCTION =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodLocalBackupReduction\" (\"id\" INTEGER NOT NULL, \"x0\" DOUBLE, \"y0\" DOUBLE, \"z0\" DOUBLE, "
			+ "\"reference_latitude\" DOUBLE, \"reference_longitude\" DOUBLE, \"reference_height\" DOUBLE, \"major_axis\" DOUBLE, \"minor_axis\" DOUBLE, "
			+ "PRIMARY KEY (\"id\"))";
	static final String SQL_BACKUP_REDUCTION = "INSERT INTO \"KleinMethodLocalBackupReduction\" (\"id\", \"x0\", \"y0\", \"z0\", \"reference_latitude\", "
			+ "\"reference_longitude\", \"reference_height\", \"major_axis\", \"minor_axis\") SELECT \"id\", \"x0\", \"y0\", \"z0\", \"reference_latitude\", "
			+ "\"reference_longitude\", \"reference_height\", \"major_axis\", \"minor_axis\" FROM \"ReductionDefinition\" WHERE \"id\" = 1";
	static final String SQL_READ_BACKUP_REDUCTION = "SELECT \"x0\", \"y0\", \"z0\", \"reference_latitude\", \"reference_longitude\", "
			+ "\"reference_height\", \"major_axis\", \"minor_axis\" FROM \"KleinMethodLocalBackupReduction\" WHERE \"id\" = 1";
	static final String SQL_CREATE_LOCAL_BACKUP_INFO =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodLocalBackupInfo\" (\"id\" INTEGER NOT NULL, \"stamp\" VARCHAR(32) NOT NULL, " +
			"\"projection_type\" INTEGER NOT NULL, PRIMARY KEY (\"id\"))";
	static final String SQL_UPDATE_POINT_ALL = "UPDATE \"PointApriori\" SET \"y0\" = ?, \"x0\" = ?, \"z0\" = ? WHERE \"id\" = ?";
	static final String SQL_UPDATE_BASELINE_ALL = "UPDATE \"GNSSObservationApriori\" SET \"y0\" = ?, \"x0\" = ?, \"z0\" = ? WHERE \"id\" = ?";
	static final String SQL_UPDATE_PROJECTION = "UPDATE \"ReductionDefinition\" SET \"projection_type\" = ? WHERE \"id\" = 1";
	static final String SQL_DELETE_FREE_SOLUTION_IF_EXISTS = "DELETE FROM \"KleinMethodFreeSolution\"";

	@Override
	public List<double[]> readLocalConversionPoints() throws SQLException {
		return this.coordinatePart.readLocalConversionPoints();
	}

	/** Punktnummern je id (fuer Hinweise der Umrechnung). */
	public Map<Integer, String> readPointNames() throws SQLException {
		Map<Integer, String> names = new java.util.HashMap<Integer, String>();
		if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
			return names;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_POINT_NAMES).executeQuery();
		while (rs.next())
			names.put(rs.getInt(1), rs.getString(2));
		return names;
	}

	@Override
	public List<double[]> readLocalConversionBaselines() throws SQLException {
		return this.coordinatePart.readLocalConversionBaselines();
	}

	@Override
	public int readZenithAndLevelingCount() throws SQLException {
		return this.coordinatePart.readZenithAndLevelingCount();
	}

	@Override
	public int[] applyLocalConversion(Map<Integer, double[]> points, Map<Integer, double[]> baselines, String stamp) throws SQLException {
		return this.coordinatePart.applyLocalConversion(points, baselines, stamp);
	}

	@Override
	public int restoreLocalConversion() throws SQLException {
		return this.coordinatePart.restoreLocalConversion();
	}

	@Override
	public String[] readLocalConversionBackupInfo() throws SQLException {
		return this.coordinatePart.readLocalConversionBackupInfo();
	}

	@Override
	public double[] readPrincipalPoint() throws SQLException {
		return this.coordinatePart.readPrincipalPoint();
	}

	@Override
	public void writePrincipalPoint(double[] values) throws SQLException {
		this.coordinatePart.writePrincipalPoint(values);
	}

	@Override
	public int[] updateLocalCoordinates(Map<Integer, double[]> points, Map<Integer, double[]> baselines) throws SQLException {
		return this.coordinatePart.updateLocalCoordinates(points, baselines);
	}

	@Override
	public void addToLocalConversionBackup(java.util.Set<Integer> pointIds, java.util.Set<Integer> baselineIds) throws SQLException {
		this.coordinatePart.addToLocalConversionBackup(pointIds, baselineIds);
	}

	@Override
	public void clearApproximationBackup() throws SQLException {
		this.coordinatePart.clearApproximationBackup();
	}

	@Override
	public void deleteFreeSolution() throws SQLException {
		this.coordinatePart.deleteFreeSolution();
	}

	@Override
	public void storeBestState(double maxLogPost, int pass, int iteration) throws SQLException {
		this.calibrationPart.storeBestState(maxLogPost, pass, iteration);
	}

	@Override
	public int[] restoreBestState() throws SQLException {
		return this.calibrationPart.restoreBestState();
	}

	@Override
	public double[] readBestStateInfo() throws SQLException {
		return this.calibrationPart.readBestStateInfo();
	}

	@Override
	public void deleteBestState() throws SQLException {
		this.calibrationPart.deleteBestState();
	}

	// Anschlussbereiche aus der Karte (Geometrie in WGS84-Grad, Punktnamen je Bereich)
	static final String SQL_CREATE_AREA_TABLE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodConnectionArea\" (\"id\" INTEGER NOT NULL, \"name\" VARCHAR(256) NOT NULL, " +
			"\"shape\" VARCHAR(16) NOT NULL, \"geometry\" VARCHAR(65536) NOT NULL, \"points\" VARCHAR(1048576) NOT NULL, PRIMARY KEY (\"id\"))";
	static final String SQL_READ_AREAS =
			"SELECT \"id\", \"name\", \"shape\", \"geometry\", \"points\" FROM \"KleinMethodConnectionArea\" ORDER BY \"id\" ASC";
	static final String SQL_DELETE_AREAS = "DELETE FROM \"KleinMethodConnectionArea\"";
	static final String SQL_INSERT_AREA =
			"INSERT INTO \"KleinMethodConnectionArea\" (\"id\", \"name\", \"shape\", \"geometry\", \"points\") VALUES (?, ?, ?, ?, ?)";
	/** Trennzeichen der Punktnamen (kommt in Punktnamen praktisch nie vor). */
	private static final String AREA_NAME_SEPARATOR = "\u001F";

	@Override
	public List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> readConnectionAreas() throws SQLException {
		return this.coordinatePart.readConnectionAreas();
	}

	@Override
	public void replaceConnectionAreas(List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> areas) throws SQLException {
		this.coordinatePart.replaceConnectionAreas(areas);
	}

	@Override
	public List<Klein_Method_Point_Coordinate_Row> readAllPointAprioriCoordinatesForFangkreisScan() throws SQLException {
		return this.coordinatePart.readAllPointAprioriCoordinatesForFangkreisScan();
	}

	// fuehrt die vom Anwender EXPLIZIT per Klick bestaetigte Umbenennung aus - wird NUR aus dem Button-Handler in
	// Klein_Method_Tab_Builder aufgerufen, NIE automatisch/beim blossen Scan. "id" ist parametrisiert (?), nur der
	// feste Suffix "_KLEIN_A" steht als SQL-Literal im Text.
	private static final String SQL_RENAME_POINT_FOR_FANGKREIS_VIOLATION =
			"UPDATE \"PointApriori\" SET \"name\" = \"name\" || '_KLEIN_A' WHERE \"id\" = ?";

	@Override
	public void renamePointForFangkreisViolation(int pointId) throws SQLException {
		this.coordinatePart.renamePointForFangkreisViolation(pointId);
	}

	// ===== Festpunktanalyse "Phase 3" - Empfehlungsbericht =====
	// EP je Achse ("influence on position") ist bei Punkten - anders als bei Beobachtungen (EINE kombinierte Spalte
	// "influence_on_position") - PRO KOMPONENTE aufgeteilt. Spaltenname per JAG3D-Ressourcenschluessel bestaetigt
	// ("UIPointTableBuilder.tableheader.influenceonposition.y" -> Anzeige "EPy") - exakter SQL-Spaltenname NICHT 1:1
	// aus SQLManager.java verifiziert, hier nach demselben Muster wie bei Beobachtungen ("influence_on_position") +
	// Achsen-Suffix abgeleitet. Reines LESEN, daher risikoarm selbst falls der Spaltenname doch abweicht.
	private static final String SQL_STOCHASTIC_POINT_EP_VALUES =
			"SELECT \"PointApriori\".\"id\" AS \"point_id\", \"PointApriori\".\"group_id\" AS \"group_id\", " +
			"\"PointGroup\".\"dimension\" AS \"dimension\", \"PointApriori\".\"name\" AS \"name\", \"PointGroup\".\"name\" AS \"group_name\", " +
			"\"PointAposteriori\".\"influence_on_position_y\" AS \"ep_y\", " +
			"\"PointAposteriori\".\"influence_on_position_x\" AS \"ep_x\", " +
			"\"PointAposteriori\".\"influence_on_position_z\" AS \"ep_z\" " +
			"FROM \"PointApriori\" " +
			"JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" " +
			"LEFT JOIN \"PointAposteriori\" ON \"PointApriori\".\"id\" = \"PointAposteriori\".\"id\" " +
			"WHERE \"PointGroup\".\"type\" = ? AND \"PointGroup\".\"enable\" = TRUE AND \"PointApriori\".\"enable\" = TRUE " +
			"ORDER BY \"PointApriori\".\"name\" ASC";

	@Override
	public List<Klein_Method_Point_Ep_Row> readStochasticPointEpValues() throws SQLException {
		return this.calibrationPart.readStochasticPointEpValues();
	}

	// ===== Festpunktanalyse "Phase 3", STUFE 2 =====
	// SQL zum Anlegen einer neuen Punktgruppe/Verschieben von Punkten NICHT 1:1 an echtem JAG3D-Quellcode verifiziert
	// - im Gegensatz zu Stufe 1 (reines Lesen) veraendert das hier tatsaechlich die Projektstruktur.

	private static final String SQL_INSERT_POINT_GROUP =
			"INSERT INTO \"PointGroup\" (\"name\", \"type\", \"dimension\", \"enable\") VALUES (?, ?, ?, TRUE)";
	private static final String SQL_FIND_POINT_GROUP_BY_NAME =
			"SELECT \"id\" FROM \"PointGroup\" WHERE \"name\" = ? AND \"type\" = ? AND \"dimension\" = ? ORDER BY \"id\" DESC";

	@Override
	public int createPointGroup(String name, int dimension, PointType type) throws SQLException {
		return this.calibrationPart.createPointGroup(name, dimension, type);
	}

	private static final String SQL_MOVE_POINT_TO_GROUP =
			"UPDATE \"PointApriori\" SET \"group_id\" = ? WHERE \"id\" = ?";

	@Override
	public void movePointsToGroup(List<Integer> pointIds, int newGroupId) throws SQLException {
		this.calibrationPart.movePointsToGroup(pointIds, newGroupId);
	}

	private static final String SQL_STOCHASTIC_POINT_TPRIO_VALUES =
			"SELECT \"PointApriori\".\"id\" AS \"point_id\", \"PointApriori\".\"group_id\" AS \"group_id\", " +
			"\"PointGroup\".\"dimension\" AS \"dimension\", " +
			"\"PointApriori\".\"sigma_y0\" AS \"sigma_y0\", \"PointApriori\".\"sigma_x0\" AS \"sigma_x0\", \"PointApriori\".\"sigma_z0\" AS \"sigma_z0\", " +
			"\"PointAposteriori\".\"t_prio\" AS \"tprio\", " +
			// T_post (Bewertungsgroesse Phase 2) und Punktname (Anzeige)
			"\"PointAposteriori\".\"t_post\" AS \"tpost\", \"PointApriori\".\"name\" AS \"point_name\" " +
			"FROM \"PointApriori\" " +
			"JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" " +
			"LEFT JOIN \"PointAposteriori\" ON \"PointApriori\".\"id\" = \"PointAposteriori\".\"id\" " +
			"WHERE \"PointGroup\".\"type\" = ? AND \"PointGroup\".\"enable\" = TRUE AND \"PointApriori\".\"enable\" = TRUE " +
			"ORDER BY \"PointApriori\".\"id\" ASC";

	@Override
	public List<Klein_Method_Point_Tprio_Row> readStochasticPointTprioValues() throws SQLException {
		List<Klein_Method_Point_Tprio_Row> rows = this.calibrationPart.readStochasticPointTprioValues();
		if (!this.horizontalOnly3D)
			return rows;
		// 3D-Punkte: T_prio nur der Lage, sigma_z0 wie die Lage gemeldet (die Hoehe ist bewusst frei)
		Map<Integer, double[]> horizontal = this.readHorizontalPointStatistics();
		List<Klein_Method_Point_Tprio_Row> result = new ArrayList<Klein_Method_Point_Tprio_Row>();
		for (Klein_Method_Point_Tprio_Row row : rows) {
			if (row.getDimension() != 3) {
				result.add(row);
				continue;
			}
			double[] h = horizontal.get(row.getPointId());
			Double tprio = h != null && Double.isFinite(h[0]) ? h[0] : row.getTprio();
			result.add(new Klein_Method_Point_Tprio_Row(row.getPointId(), row.getGroupId(), 3, row.getSigmaY0(), row.getSigmaX0(), row.getSigmaY0(), tprio,
					row.getTpost(), row.getName()));
		}
		return result;
	}

	private static final String SQL_WRITE_POINT_UNCERTAINTY_INDIVIDUAL_Y =
			"UPDATE \"PointApriori\" SET \"sigma_y0\" = ? WHERE \"id\" = ?";
	private static final String SQL_WRITE_POINT_UNCERTAINTY_INDIVIDUAL_X =
			"UPDATE \"PointApriori\" SET \"sigma_x0\" = ? WHERE \"id\" = ?";
	private static final String SQL_WRITE_POINT_UNCERTAINTY_INDIVIDUAL_Z =
			"UPDATE \"PointApriori\" SET \"sigma_z0\" = ? WHERE \"id\" = ?";

	@Override
	public void writePointUncertainty(int pointId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException {
		// "Hoehe nur aus 1D": die Hoehe der 3D-Anschlusspunkte bleibt frei
		// "Hoehe nur aus 1D" (dynamisch): sigma_z0 bleibt, die Hoehe wird getrennt kalibriert (calibrateSeparateHeights)
		if (dimension == 3 && this.horizontalOnly3D) {
			PreparedStatement read = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_POINT_SIGMA_Z);
			read.setInt(1, pointId);
			ResultSet rs = read.executeQuery();
			sigmaZ = rs.next() ? rs.getDouble(1) : 0.0;
		}
		this.calibrationPart.writePointUncertainty(pointId, dimension, sigmaY, sigmaX, sigmaZ);
	}

	// ====================================================================== Projektionsart und Passpunkte der Karte.
	// Eigene Tabelle in der Projektdatenbank (reist mit dem Projekt mit), NICHT Teil der Reihenfolge-Pruefsumme. Nur
	// Anzeige - die Ausgleichung liest nichts davon.
	// ======================================================================

	private static final String SQL_CREATE_MAP_REFERENCE_TABLE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodMapReference\" (" +
			"\"point_name\" VARCHAR(256) NOT NULL, \"latitude\" DOUBLE NOT NULL, \"longitude\" DOUBLE NOT NULL, " +
			"\"sort_order\" BIGINT NOT NULL, PRIMARY KEY (\"point_name\"))";

	private static final String SQL_MERGE_MAP_REFERENCE =
			"MERGE INTO \"KleinMethodMapReference\" USING (VALUES (CAST(? AS VARCHAR(256)), CAST(? AS DOUBLE), CAST(? AS DOUBLE), CAST(? AS BIGINT))) " +
			"AS \"vals\" (\"point_name\", \"latitude\", \"longitude\", \"sort_order\") " +
			"ON \"KleinMethodMapReference\".\"point_name\" = \"vals\".\"point_name\" " +
			"WHEN MATCHED THEN UPDATE SET \"KleinMethodMapReference\".\"latitude\" = \"vals\".\"latitude\", " +
			"\"KleinMethodMapReference\".\"longitude\" = \"vals\".\"longitude\" " +
			"WHEN NOT MATCHED THEN INSERT (\"point_name\", \"latitude\", \"longitude\", \"sort_order\") " +
			"VALUES (\"vals\".\"point_name\", \"vals\".\"latitude\", \"vals\".\"longitude\", \"vals\".\"sort_order\")";

	private static final String SQL_READ_MAP_REFERENCES =
			"SELECT \"point_name\", \"latitude\", \"longitude\" FROM \"KleinMethodMapReference\" ORDER BY \"sort_order\" ASC";

	private static final String SQL_DELETE_MAP_REFERENCE =
			"DELETE FROM \"KleinMethodMapReference\" WHERE \"point_name\" = ?";

	private static boolean hasOpenDatabase() {
		return SQLManager.getInstance().hasDatabase() && SQLManager.getInstance().getDataBase().isOpen();
	}

	/**
	 * true, wenn im aktuellen Schema die JAG3D-Projekttabellen existieren. Beim Anlegen eines neuen Projekts meldet
	 * JAG3D "geoeffnet", bevor das Schema angelegt ist - Lesezugriffe scheitern dann ("object not found") und eigene
	 * Tabellen landeten im Schema PUBLIC.
	 */
	public boolean hasProjectTables() {
		if (!hasOpenDatabase())
			return false;
		try {
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(
					"SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = CURRENT_SCHEMA AND TABLE_NAME = 'PointGroup'").executeQuery();
			return rs.next() && rs.getInt(1) > 0;
		} catch (SQLException e) {
			return false;
		}
	}

	@Override
	public ProjectionType readProjectionType() throws SQLException {
		return this.settingsPart.readProjectionType();
	}

	/** Projektion in JAG3D "Projektion und Reduktion" setzen. */
	public void writeProjectionType(ProjectionType type) throws SQLException {
		if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
			return;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_PROJECTION);
		stmt.setInt(1, type.getId());
		stmt.execute();
	}

	@Override
	public Map<String, double[]> readMapReferences() throws SQLException {
		return this.settingsPart.readMapReferences();
	}

	@Override
	public void storeMapReference(String pointName, double latitude, double longitude) throws SQLException {
		this.settingsPart.storeMapReference(pointName, latitude, longitude);
	}

	@Override
	public void deleteMapReference(String pointName) throws SQLException {
		this.settingsPart.deleteMapReference(pointName);
	}

	@Override
	public String readMapSetting(String key) throws SQLException {
		return this.settingsPart.readMapSetting(key);
	}

	@Override
	public void storeMapSetting(String key, String value) throws SQLException {
		this.settingsPart.storeMapSetting(key, value);
	}

	// ====================================================================== die groessten Fehler. Reine
	// Lesezugriffe, nur aktive Gruppen/Beobachtungen/Punkte.
	// ======================================================================

	private static final String SQL_LARGEST_OBSERVATION_ERRORS =
			"SELECT \"ObservationApriori\".\"id\" AS \"id\", \"ObservationGroup\".\"type\" AS \"type\", \"ObservationApriori\".\"start_point_name\" AS \"start_name\", " +
			"\"ObservationApriori\".\"end_point_name\" AS \"end_name\", ABS(\"ObservationAposteriori\".\"p_post\") AS \"abs_log_post\", " +
			"\"ObservationAposteriori\".\"residual\" AS \"residual\", \"ObservationAposteriori\".\"gross_error\" AS \"gross_error\", " +
			"\"ObservationAposteriori\".\"redundancy\" AS \"redundancy\", " +
			"\"ObservationAposteriori\".\"significant\" AS \"significant\", \"ObservationAposteriori\".\"minimal_detectable_bias\" AS \"mdb\", " +
			"\"S\".\"y0\" AS \"sy\", \"S\".\"x0\" AS \"sx\", \"S\".\"z0\" AS \"sz\", \"E\".\"y0\" AS \"ey\", \"E\".\"x0\" AS \"ex\", \"E\".\"z0\" AS \"ez\" " +
			"FROM \"ObservationAposteriori\" " +
			"JOIN \"ObservationApriori\" ON \"ObservationApriori\".\"id\" = \"ObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"LEFT JOIN \"PointApriori\" AS \"S\" ON \"S\".\"name\" = \"ObservationApriori\".\"start_point_name\" " +
			"LEFT JOIN \"PointApriori\" AS \"E\" ON \"E\".\"name\" = \"ObservationApriori\".\"end_point_name\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE AND \"ObservationApriori\".\"enable\" = TRUE " +
			"AND \"ObservationAposteriori\".\"p_post\" IS NOT NULL " +
			"ORDER BY ABS(\"ObservationAposteriori\".\"p_post\") DESC";

	private static final String SQL_LARGEST_GNSS_ERRORS =
			"SELECT \"GNSSObservationApriori\".\"id\" AS \"id\", \"ObservationGroup\".\"type\" AS \"type\", \"GNSSObservationApriori\".\"start_point_name\" AS \"start_name\", " +
			"\"GNSSObservationApriori\".\"end_point_name\" AS \"end_name\", ABS(\"GNSSObservationAposteriori\".\"p_post\") AS \"abs_log_post\", " +
			"\"GNSSObservationAposteriori\".\"residual_y\" AS \"residual_y\", \"GNSSObservationAposteriori\".\"residual_x\" AS \"residual_x\", " +
			"\"GNSSObservationAposteriori\".\"residual_z\" AS \"residual_z\", " +
			"\"GNSSObservationAposteriori\".\"gross_error_y\" AS \"gross_error_y\", \"GNSSObservationAposteriori\".\"gross_error_x\" AS \"gross_error_x\", " +
			"\"GNSSObservationAposteriori\".\"gross_error_z\" AS \"gross_error_z\", " +
			"\"GNSSObservationAposteriori\".\"significant\" AS \"significant\", \"GNSSObservationAposteriori\".\"minimal_detectable_bias_y\" AS \"mdb_y\", " +
			"\"GNSSObservationAposteriori\".\"minimal_detectable_bias_x\" AS \"mdb_x\", \"GNSSObservationAposteriori\".\"minimal_detectable_bias_z\" AS \"mdb_z\", " +
			"LEAST(\"GNSSObservationAposteriori\".\"redundancy_y\", \"GNSSObservationAposteriori\".\"redundancy_x\", " +
			"\"GNSSObservationAposteriori\".\"redundancy_z\") AS \"redundancy\" " +
			"FROM \"GNSSObservationAposteriori\" " +
			"JOIN \"GNSSObservationApriori\" ON \"GNSSObservationApriori\".\"id\" = \"GNSSObservationAposteriori\".\"id\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE AND \"GNSSObservationApriori\".\"enable\" = TRUE " +
			"AND \"GNSSObservationAposteriori\".\"p_post\" IS NOT NULL " +
			"ORDER BY ABS(\"GNSSObservationAposteriori\".\"p_post\") DESC";

	private static final String SQL_LARGEST_POINT_ERRORS =
			"SELECT \"PointApriori\".\"name\" AS \"point_name\", ABS(\"PointAposteriori\".\"t_prio\") AS \"abs_tprio\", " +
			"\"PointAposteriori\".\"residual_y\" AS \"residual_y\", \"PointAposteriori\".\"residual_x\" AS \"residual_x\", " +
			"\"PointAposteriori\".\"residual_z\" AS \"residual_z\" " +
			"FROM \"PointApriori\" " +
			"JOIN \"PointGroup\" ON \"PointApriori\".\"group_id\" = \"PointGroup\".\"id\" " +
			"JOIN \"PointAposteriori\" ON \"PointApriori\".\"id\" = \"PointAposteriori\".\"id\" " +
			"WHERE \"PointGroup\".\"type\" = ? AND \"PointGroup\".\"enable\" = TRUE AND \"PointApriori\".\"enable\" = TRUE " +
			"AND \"PointAposteriori\".\"t_prio\" IS NOT NULL " +
			"ORDER BY ABS(\"PointAposteriori\".\"t_prio\") DESC";

	@Override
	public List<Max_Error_Row> readLargestObservationErrors(int limit) throws SQLException {
		return this.grossErrorPart.readLargestObservationErrors(limit);
	}

	@Override
	public List<Max_Error_Row> readLargestPointErrors(int limit) throws SQLException {
		return this.grossErrorPart.readLargestPointErrors(limit);
	}

	// ====================================================================== gemerkte grobe Fehler. Eigene Tabelle in
	// der Projektdatenbank (reist mit dem Projekt mit).
	// ======================================================================

	private static final String SQL_CREATE_GROSS_ERROR_TABLE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodGrossError\" (" +
			"\"kind\" CHAR(1) NOT NULL, \"observation_id\" INTEGER NOT NULL, \"status\" VARCHAR(16) NOT NULL, " +
			"\"type\" INTEGER NOT NULL, \"start_point_name\" VARCHAR(256) NOT NULL, \"end_point_name\" VARCHAR(256) NOT NULL, " +
			"\"log_post\" DOUBLE NOT NULL, \"residual\" DOUBLE NOT NULL, \"linear_error\" DOUBLE, PRIMARY KEY (\"kind\", \"observation_id\"))";

	// Spalte "linear_error" in einer angelegten Tabelle nachruesten
	private static final String SQL_GROSS_ERROR_LINEAR_COLUMN_EXISTS =
			"SELECT COUNT(*) AS \"cnt\" FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'KleinMethodGrossError' AND COLUMN_NAME = 'linear_error'";
	private static final String SQL_ADD_GROSS_ERROR_LINEAR_COLUMN =
			"ALTER TABLE \"KleinMethodGrossError\" ADD COLUMN \"linear_error\" DOUBLE";

	private static final String SQL_READ_GROSS_ERRORS =
			"SELECT \"kind\", \"observation_id\", \"status\", \"type\", \"start_point_name\", \"end_point_name\", \"log_post\", \"residual\", \"linear_error\" " +
			"FROM \"KleinMethodGrossError\" ORDER BY \"log_post\" DESC";

	private static final String SQL_MERGE_GROSS_ERROR =
			"MERGE INTO \"KleinMethodGrossError\" USING (VALUES (CAST(? AS CHAR(1)), CAST(? AS INTEGER), CAST(? AS VARCHAR(16)), CAST(? AS INTEGER), " +
			"CAST(? AS VARCHAR(256)), CAST(? AS VARCHAR(256)), CAST(? AS DOUBLE), CAST(? AS DOUBLE), CAST(? AS DOUBLE))) " +
			"AS \"vals\" (\"kind\", \"observation_id\", \"status\", \"type\", \"start_point_name\", \"end_point_name\", \"log_post\", \"residual\", \"linear_error\") " +
			"ON \"KleinMethodGrossError\".\"kind\" = \"vals\".\"kind\" AND \"KleinMethodGrossError\".\"observation_id\" = \"vals\".\"observation_id\" " +
			"WHEN MATCHED THEN UPDATE SET \"KleinMethodGrossError\".\"status\" = \"vals\".\"status\", \"KleinMethodGrossError\".\"log_post\" = \"vals\".\"log_post\", " +
			"\"KleinMethodGrossError\".\"residual\" = \"vals\".\"residual\", \"KleinMethodGrossError\".\"linear_error\" = \"vals\".\"linear_error\" " +
			"WHEN NOT MATCHED THEN INSERT (\"kind\", \"observation_id\", \"status\", \"type\", \"start_point_name\", \"end_point_name\", \"log_post\", \"residual\", \"linear_error\") " +
			"VALUES (\"vals\".\"kind\", \"vals\".\"observation_id\", \"vals\".\"status\", \"vals\".\"type\", \"vals\".\"start_point_name\", " +
			"\"vals\".\"end_point_name\", \"vals\".\"log_post\", \"vals\".\"residual\", \"vals\".\"linear_error\")";

	private static final String SQL_DELETE_GROSS_ERROR =
			"DELETE FROM \"KleinMethodGrossError\" WHERE \"kind\" = ? AND \"observation_id\" = ?";

	private static final String SQL_OBSERVATION_ENABLED =
			"SELECT \"enable\" FROM \"ObservationApriori\" WHERE \"id\" = ?";
	private static final String SQL_GNSS_OBSERVATION_ENABLED =
			"SELECT \"enable\" FROM \"GNSSObservationApriori\" WHERE \"id\" = ?";
	private static final String SQL_SET_OBSERVATION_ENABLED =
			"UPDATE \"ObservationApriori\" SET \"enable\" = ? WHERE \"id\" = ?";
	private static final String SQL_SET_GNSS_OBSERVATION_ENABLED =
			"UPDATE \"GNSSObservationApriori\" SET \"enable\" = ? WHERE \"id\" = ?";

	private static double readNullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? Double.NaN : value;
	}

	@Override
	public List<Max_Error_Row> readGrossErrorEntries() throws SQLException {
		return this.grossErrorPart.readGrossErrorEntries();
	}

	@Override
	public void storeGrossErrorEntry(Max_Error_Row row, String status) throws SQLException {
		this.grossErrorPart.storeGrossErrorEntry(row, status);
	}

	@Override
	public void deleteGrossErrorEntry(Max_Error_Row row) throws SQLException {
		this.grossErrorPart.deleteGrossErrorEntry(row);
	}

	private static final String SQL_POINT_PROGNOSIS =
			"SELECT a.\"name\", p.\"confidence_ellipse_major_axis\", p.\"sigma_z\", p.\"y\", p.\"x\", p.\"z\", "
			+ "(SELECT COUNT(*) FROM \"GNSSObservationApriori\" o WHERE o.\"enable\" = TRUE AND (o.\"start_point_name\" = a.\"name\" OR o.\"end_point_name\" = a.\"name\")) "
			+ "FROM \"PointApriori\" a JOIN \"PointGroup\" g ON g.\"id\" = a.\"group_id\" JOIN \"PointAposteriori\" p ON p.\"id\" = a.\"id\" "
			+ "WHERE a.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"type\" = ? AND g.\"dimension\" >= 2";
	private static final String SQL_ADJUSTED_POINT =
			"SELECT p.\"y\", p.\"x\", p.\"z\" FROM \"PointApriori\" a JOIN \"PointAposteriori\" p ON p.\"id\" = a.\"id\" WHERE a.\"name\" = ?";
	private static final String SQL_GNSS_STATION_USAGE =
			"SELECT u.\"n\", COUNT(*), MIN(u.\"g\") FROM (SELECT \"start_point_name\" AS \"n\", \"group_id\" AS \"g\" FROM \"GNSSObservationApriori\" WHERE \"enable\" = TRUE "
			+ "UNION ALL SELECT \"end_point_name\" AS \"n\", \"group_id\" AS \"g\" FROM \"GNSSObservationApriori\" WHERE \"enable\" = TRUE) u "
			+ "GROUP BY u.\"n\" ORDER BY COUNT(*) DESC";
	private static final String SQL_GNSS_SIGMA_MEDIAN =
			"SELECT MEDIAN(\"sigma_y0\"), MEDIAN(\"sigma_z0\") FROM \"GNSSObservationApriori\" WHERE \"enable\" = TRUE";
	private static final String SQL_GNSS_SESSION_MEANS =
			"SELECT a.\"start_point_name\", a.\"end_point_name\", a.\"sigma_y0\", a.\"sigma_z0\", a.\"enable\" FROM \"GNSSObservationApriori\" a "
			+ "JOIN (SELECT \"id\", \"role\" FROM \"KleinMethodGnssSessionMerge\" UNION ALL SELECT \"id\", \"role\" FROM \"KleinMethodRepetition\" "
			+ "WHERE \"kind\" = 1) m ON m.\"id\" = a.\"id\" WHERE m.\"role\" IN (1, 3) ORDER BY a.\"id\" ASC";

	@Override
	public List<Object[]> readPointPrognosis() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_POINT_PROGNOSIS);
		stmt.setInt(1, PointType.NEW_POINT.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7) });
		return rows;
	}

	@Override
	public double[] readAdjustedPoint(String pointName) throws SQLException {
		if (!hasOpenDatabase())
			return null;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ADJUSTED_POINT);
		stmt.setString(1, pointName);
		ResultSet rs = stmt.executeQuery();
		return rs.next() ? new double[] { rs.getDouble(1), rs.getDouble(2), rs.getDouble(3) } : null;
	}

	@Override
	public List<Object[]> readGnssStationUsage(int limit) throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GNSS_STATION_USAGE).executeQuery();
		while (rs.next() && rows.size() < limit)
			rows.add(new Object[] { rs.getString(1), rs.getInt(2), rs.getInt(3) });
		return rows;
	}

	@Override
	public double[] readActiveGnssSigmaMedian() throws SQLException {
		if (!hasOpenDatabase())
			return new double[] { 0, 0 };
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GNSS_SIGMA_MEDIAN).executeQuery();
		return rs.next() ? new double[] { rs.getDouble(1), rs.getDouble(2) } : new double[] { 0, 0 };
	}

	private static final String SQL_ACTIVE_GNSS_VECTORS =
			"SELECT o.\"id\", o.\"start_point_name\", o.\"end_point_name\", o.\"y0\", o.\"x0\", o.\"z0\" FROM \"GNSSObservationApriori\" o "
			+ "JOIN \"ObservationGroup\" g ON g.\"id\" = o.\"group_id\" WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"type\" = ?";
	private static final String SQL_ACTIVE_REPEATABLE_OBSERVATIONS =
			"SELECT o.\"id\", g.\"type\", o.\"start_point_name\", o.\"end_point_name\", o.\"value_0\", o.\"instrument_height\", o.\"reflector_height\" "
			+ "FROM \"ObservationApriori\" o JOIN \"ObservationGroup\" g ON g.\"id\" = o.\"group_id\" "
			+ "WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"type\" IN (?, ?, ?)";

	private static final String SQL_DIAGNOSIS_POINT_POSITIONS =
			"SELECT a.\"name\", COALESCE(p.\"y\", a.\"y0\"), COALESCE(p.\"x\", a.\"x0\"), COALESCE(p.\"z\", a.\"z0\") FROM \"PointApriori\" a "
			+ "LEFT JOIN \"PointAposteriori\" p ON p.\"id\" = a.\"id\" WHERE a.\"enable\" = TRUE";

	@Override
	public List<Object[]> readAllPointCoordinates() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DIAGNOSIS_POINT_POSITIONS).executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4) });
		return rows;
	}

	private static final String SQL_ACTIVE_TERRESTRIAL =
			"SELECT o.\"id\", g.\"type\", o.\"start_point_name\", o.\"end_point_name\", o.\"instrument_height\", o.\"reflector_height\", o.\"value_0\", o.\"distance_0\" "
			+ "FROM \"ObservationApriori\" o JOIN \"ObservationGroup\" g ON g.\"id\" = o.\"group_id\" WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE";

	private static final String SQL_IMPORT_FINGERPRINT = "SELECT (SELECT COUNT(*) FROM \"PointApriori\"), COUNT(*), "
			+ "COALESCE(SUM(\"instrument_height\" + \"reflector_height\"), 0) FROM \"ObservationApriori\"";

	private static final String SQL_COUNT_ACTIVE_GNSS_BASELINES = "SELECT COUNT(*) FROM \"GNSSObservationApriori\" o JOIN \"ObservationGroup\" g "
			+ "ON g.\"id\" = o.\"group_id\" WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE";

	@Override
	public int countActiveGnssBaselines() throws SQLException {
		if (!hasOpenDatabase())
			return 0;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_COUNT_ACTIVE_GNSS_BASELINES).executeQuery();
		return rs.next() ? rs.getInt(1) : 0;
	}

	@Override
	public String readImportFingerprint() throws SQLException {
		if (!hasOpenDatabase())
			return "";
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_IMPORT_FINGERPRINT).executeQuery();
		return rs.next() ? rs.getLong(1) + "/" + rs.getLong(2) + "/" + Math.round(rs.getDouble(3) * 1000.0) : "";
	}

	@Override
	public List<Object[]> readActiveTerrestrialObservations() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_TERRESTRIAL).executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8) });
		return rows;
	}

	private static final String SQL_ACTIVE_TARGET_HEIGHTS =
			"SELECT o.\"id\", g.\"type\", o.\"start_point_name\", o.\"end_point_name\", o.\"instrument_height\", o.\"reflector_height\" "
			+ "FROM \"ObservationApriori\" o JOIN \"ObservationGroup\" g ON g.\"id\" = o.\"group_id\" "
			+ "WHERE o.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"type\" IN (?, ?)";
	private static final String SQL_OBSERVATION_HEIGHTS =
			"SELECT \"instrument_height\", \"reflector_height\" FROM \"ObservationApriori\" WHERE \"id\" = ?";

	@Override
	public List<Object[]> readActiveTargetHeights() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_TARGET_HEIGHTS);
		stmt.setInt(1, ObservationType.SLOPE_DISTANCE.getId());
		stmt.setInt(2, ObservationType.ZENITH_ANGLE.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getDouble(6) });
		return rows;
	}

	@Override
	public double[] readObservationHeights(int observationId) throws SQLException {
		if (!hasOpenDatabase())
			return null;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_OBSERVATION_HEIGHTS);
		stmt.setInt(1, observationId);
		ResultSet rs = stmt.executeQuery();
		return rs.next() ? new double[] { rs.getDouble(1), rs.getDouble(2) } : null;
	}

	private static final String SQL_GNSS_VECTOR =
			"SELECT \"y0\", \"x0\", \"z0\" FROM \"GNSSObservationApriori\" WHERE \"id\" = ?";
	private static final String SQL_POINT_COORDINATES =
			"SELECT COALESCE(p.\"y\", a.\"y0\"), COALESCE(p.\"x\", a.\"x0\"), COALESCE(p.\"z\", a.\"z0\") FROM \"PointApriori\" a "
			+ "LEFT JOIN \"PointAposteriori\" p ON p.\"id\" = a.\"id\" WHERE a.\"name\" = ?";

	@Override
	public double[] readGnssVector(int observationId) throws SQLException {
		if (!hasOpenDatabase())
			return null;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GNSS_VECTOR);
		stmt.setInt(1, observationId);
		ResultSet rs = stmt.executeQuery();
		return rs.next() ? new double[] { rs.getDouble(1), rs.getDouble(2), rs.getDouble(3) } : null;
	}

	@Override
	public double[] readPointCoordinates(String name) throws SQLException {
		if (!hasOpenDatabase())
			return null;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_POINT_COORDINATES);
		stmt.setString(1, name);
		ResultSet rs = stmt.executeQuery();
		return rs.next() ? new double[] { rs.getDouble(1), rs.getDouble(2), rs.getDouble(3) } : null;
	}

	@Override
	public List<Object[]> readActiveGnssVectors() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_GNSS_VECTORS);
		stmt.setInt(1, ObservationType.GNSS3D.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6) });
		return rows;
	}

	@Override
	public List<Object[]> readActiveRepeatableObservations() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_REPEATABLE_OBSERVATIONS);
		stmt.setInt(1, ObservationType.HORIZONTAL_DISTANCE.getId());
		stmt.setInt(2, ObservationType.SLOPE_DISTANCE.getId());
		stmt.setInt(3, ObservationType.LEVELING.getId());
		ResultSet rs = stmt.executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7) });
		return rows;
	}

	@Override
	public int insertVirtualGnssBaseline(int groupId, String start, String end, double[] vector, double[] sigma) throws SQLException {
		PreparedStatement insert = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_GNSS_MEAN);
		insert.setInt(1, groupId);
		insert.setString(2, start);
		insert.setString(3, end);
		for (int k = 0; k < 3; k++) {
			insert.setDouble(4 + k, vector[k]);
			insert.setDouble(7 + k, sigma[k]);
		}
		insert.execute();
		return SQLManager.getInstance().getDataBase().getLastInsertId();
	}

	@Override
	public void deleteGnssObservation(int observationId) throws SQLException {
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_GNSS_OBSERVATION);
		stmt.setInt(1, observationId);
		stmt.execute();
	}

	@Override
	public List<Object[]> readGnssSessionMeans() throws SQLException {
		List<Object[]> rows = new ArrayList<Object[]>();
		if (!hasOpenDatabase())
			return rows;
		SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GNSS_MERGE).execute();
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_GNSS_SESSION_MEANS)).executeQuery();
		while (rs.next())
			rows.add(new Object[] { rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getBoolean(5) });
		return rows;
	}

	private static final String SQL_OBSERVED_POINTS_WITHOUT_APPROXIMATION =
			"SELECT p.\"name\" FROM \"PointApriori\" p JOIN \"PointGroup\" g ON g.\"id\" = p.\"group_id\" "
			+ "WHERE p.\"enable\" = TRUE AND g.\"enable\" = TRUE AND g.\"dimension\" >= 2 AND p.\"y0\" = 0 AND p.\"x0\" = 0 "
			+ "AND (p.\"name\" IN (SELECT o.\"start_point_name\" FROM \"ObservationApriori\" o WHERE o.\"enable\" = TRUE) "
			+ "OR p.\"name\" IN (SELECT o.\"end_point_name\" FROM \"ObservationApriori\" o WHERE o.\"enable\" = TRUE)) ORDER BY p.\"name\" ASC";

	@Override
	public List<String> readObservedPointsWithoutApproximation() throws SQLException {
		List<String> names = new ArrayList<String>();
		if (!hasOpenDatabase())
			return names;
		ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_OBSERVED_POINTS_WITHOUT_APPROXIMATION).executeQuery();
		while (rs.next())
			names.add(rs.getString(1));
		return names;
	}

	@Override
	public void setTerrestrialObservationEnabled(int observationId, boolean enabled) throws SQLException {
		if (!hasOpenDatabase())
			return;
		PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_SET_OBSERVATION_ENABLED);
		stmt.setBoolean(1, enabled);
		stmt.setInt(2, observationId);
		stmt.execute();
	}

	@Override
	public void setObservationEnabled(Max_Error_Row row, boolean enabled) throws SQLException {
		this.grossErrorPart.setObservationEnabled(row, enabled);
	}

	@Override
	public String getProjectLocation() {
		if (!hasOpenDatabase())
			return null;
		try {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_ALL_TABLE_NAMES);
			String url = stmt.getConnection().getMetaData().getURL();
			if (url == null)
				return null;
			int semicolon = url.indexOf(';');
			return semicolon >= 0 ? url.substring(0, semicolon) : url;
		} catch (SQLException e) {
			return null;
		}
	}

	private static final String SQL_ACTIVE_OBSERVATION_COUNTS =
			"SELECT \"ObservationGroup\".\"type\" AS \"type\", COUNT(*) AS \"cnt\" FROM \"ObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"ObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE AND \"ObservationApriori\".\"enable\" = TRUE GROUP BY \"ObservationGroup\".\"type\"";
	private static final String SQL_ACTIVE_GNSS_OBSERVATION_COUNTS =
			"SELECT \"ObservationGroup\".\"type\" AS \"type\", COUNT(*) AS \"cnt\" FROM \"GNSSObservationApriori\" " +
			"JOIN \"ObservationGroup\" ON \"GNSSObservationApriori\".\"group_id\" = \"ObservationGroup\".\"id\" " +
			"WHERE \"ObservationGroup\".\"enable\" = TRUE AND \"GNSSObservationApriori\".\"enable\" = TRUE GROUP BY \"ObservationGroup\".\"type\"";

	@Override
	public Map<ObservationType, Integer> readActiveObservationCounts() throws SQLException {
		return this.statusPart.readActiveObservationCounts();
	}

	@Override
	public String readProjectSetting(String key) throws SQLException {
		return this.settingsPart.readProjectSetting(key);
	}

	@Override
	public void storeProjectSetting(String key, String value) throws SQLException {
		this.settingsPart.storeProjectSetting(key, value);
	}

	// Eigene Tabelle mit ausreichend Platz; Werte aus der alten Tabelle werden weiter gelesen (Altprojekte),
	// geschrieben wird nur noch in die neue.
	public static final int SETTING_KEY_LENGTH = 128, SETTING_VALUE_LENGTH = 16384;

	private static final String SQL_CREATE_SETTING_TABLE =
			"CREATE TABLE IF NOT EXISTS \"KleinMethodProjectSetting\" (\"key\" VARCHAR(" + SETTING_KEY_LENGTH + ") NOT NULL, "
					+ "\"value\" VARCHAR(" + SETTING_VALUE_LENGTH + ") NOT NULL, PRIMARY KEY (\"key\"))";

	private static final String SQL_MERGE_SETTING =
			"MERGE INTO \"KleinMethodProjectSetting\" USING (VALUES (CAST(? AS VARCHAR(" + SETTING_KEY_LENGTH + ")), CAST(? AS VARCHAR(" + SETTING_VALUE_LENGTH + ")))) "
					+ "AS \"vals\" (\"key\", \"value\") ON \"KleinMethodProjectSetting\".\"key\" = \"vals\".\"key\" "
					+ "WHEN MATCHED THEN UPDATE SET \"KleinMethodProjectSetting\".\"value\" = \"vals\".\"value\" "
					+ "WHEN NOT MATCHED THEN INSERT (\"key\", \"value\") VALUES (\"vals\".\"key\", \"vals\".\"value\")";

	private static final String SQL_READ_SETTING =
			"SELECT \"value\" FROM \"KleinMethodProjectSetting\" WHERE \"key\" = ?";

	// ============================================================================================================
	/** Messwerte, Ausgleichungsergebnisse und Unsicherheiten lesen/schreiben (Kalibrierung). */
	private final class Calibration_Part {

		// ---------------- Nachlauf auf den Grenzwert: bester Stand ----------------
		// Eigene Tabellen im Projekt (reisen mit, ueberstehen einen Abbruch von JAG3D). Gesichert wird alles, was die
		// Automatik in der freien/fixen Ausgleichung schreibt.

		private final String[] BEST_STATE_TABLES = { "KleinMethodBestObservation", "KleinMethodBestGnss", "KleinMethodBestGroup",
				"KleinMethodBestParameter", "KleinMethodBestInfo" };

		private void ensureBestStateTables() throws SQLException {
			org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
			db.getPreparedStatement("CREATE TABLE IF NOT EXISTS \"KleinMethodBestObservation\" (\"id\" INTEGER NOT NULL PRIMARY KEY, "
					+ "\"sigma_0\" DOUBLE NOT NULL, \"enable\" BOOLEAN NOT NULL)").execute();
			db.getPreparedStatement("CREATE TABLE IF NOT EXISTS \"KleinMethodBestGnss\" (\"id\" INTEGER NOT NULL PRIMARY KEY, "
					+ "\"sigma_y0\" DOUBLE NOT NULL, \"sigma_x0\" DOUBLE NOT NULL, \"sigma_z0\" DOUBLE NOT NULL, \"enable\" BOOLEAN NOT NULL)").execute();
			db.getPreparedStatement("CREATE TABLE IF NOT EXISTS \"KleinMethodBestGroup\" (\"group_id\" INTEGER NOT NULL, \"type\" INTEGER NOT NULL, "
					+ "\"value\" DOUBLE NOT NULL, PRIMARY KEY (\"group_id\", \"type\"))").execute();
			db.getPreparedStatement("CREATE TABLE IF NOT EXISTS \"KleinMethodBestParameter\" (\"id\" INTEGER NOT NULL PRIMARY KEY, "
					+ "\"enable\" BOOLEAN NOT NULL)").execute();
			db.getPreparedStatement("CREATE TABLE IF NOT EXISTS \"KleinMethodBestInfo\" (\"id\" INTEGER NOT NULL PRIMARY KEY, "
					+ "\"max_log_post\" DOUBLE NOT NULL, \"pass\" INTEGER NOT NULL, \"iteration\" INTEGER NOT NULL, \"stamp\" VARCHAR(32) NOT NULL)").execute();
		}

		public void storeBestState(double maxLogPost, int pass, int iteration) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureBestStateTables();
			this.deleteBestState();
			org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
			db.getPreparedStatement("INSERT INTO \"KleinMethodBestObservation\" SELECT \"id\", \"sigma_0\", \"enable\" FROM \"ObservationApriori\"").execute();
			db.getPreparedStatement("INSERT INTO \"KleinMethodBestGnss\" SELECT \"id\", \"sigma_y0\", \"sigma_x0\", \"sigma_z0\", \"enable\" "
					+ "FROM \"GNSSObservationApriori\"").execute();
			db.getPreparedStatement("INSERT INTO \"KleinMethodBestGroup\" SELECT \"group_id\", \"type\", \"value\" FROM \"ObservationGroupUncertainty\"").execute();
			db.getPreparedStatement("INSERT INTO \"KleinMethodBestParameter\" SELECT \"id\", \"enable\" FROM \"AdditionalParameterApriori\"").execute();
			PreparedStatement info = db.getPreparedStatement("INSERT INTO \"KleinMethodBestInfo\" VALUES (1, ?, ?, ?, ?)");
			info.setDouble(1, maxLogPost);
			info.setInt(2, pass);
			info.setInt(3, iteration);
			info.setString(4, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
			info.execute();
		}

		public int[] restoreBestState() throws SQLException {
			if (!hasOpenDatabase())
				return new int[4];
			this.ensureBestStateTables();
			org.applied_geodesy.util.sql.DataBase db = SQLManager.getInstance().getDataBase();
			int observations = db.getPreparedStatement("MERGE INTO \"ObservationApriori\" USING \"KleinMethodBestObservation\" AS \"b\" "
					+ "ON \"ObservationApriori\".\"id\" = \"b\".\"id\" WHEN MATCHED THEN UPDATE SET "
					+ "\"ObservationApriori\".\"sigma_0\" = \"b\".\"sigma_0\", \"ObservationApriori\".\"enable\" = \"b\".\"enable\"").executeUpdate();
			int baselines = db.getPreparedStatement("MERGE INTO \"GNSSObservationApriori\" USING \"KleinMethodBestGnss\" AS \"b\" "
					+ "ON \"GNSSObservationApriori\".\"id\" = \"b\".\"id\" WHEN MATCHED THEN UPDATE SET "
					+ "\"GNSSObservationApriori\".\"sigma_y0\" = \"b\".\"sigma_y0\", \"GNSSObservationApriori\".\"sigma_x0\" = \"b\".\"sigma_x0\", "
					+ "\"GNSSObservationApriori\".\"sigma_z0\" = \"b\".\"sigma_z0\", \"GNSSObservationApriori\".\"enable\" = \"b\".\"enable\"").executeUpdate();
			int groups = db.getPreparedStatement("MERGE INTO \"ObservationGroupUncertainty\" USING \"KleinMethodBestGroup\" AS \"b\" "
					+ "ON \"ObservationGroupUncertainty\".\"group_id\" = \"b\".\"group_id\" AND \"ObservationGroupUncertainty\".\"type\" = \"b\".\"type\" "
					+ "WHEN MATCHED THEN UPDATE SET \"ObservationGroupUncertainty\".\"value\" = \"b\".\"value\"").executeUpdate();
			int parameters = db.getPreparedStatement("MERGE INTO \"AdditionalParameterApriori\" USING \"KleinMethodBestParameter\" AS \"b\" "
					+ "ON \"AdditionalParameterApriori\".\"id\" = \"b\".\"id\" WHEN MATCHED THEN UPDATE SET "
					+ "\"AdditionalParameterApriori\".\"enable\" = \"b\".\"enable\"").executeUpdate();
			return new int[] { observations, baselines, groups, parameters };
		}

		public double[] readBestStateInfo() throws SQLException {
			if (!hasOpenDatabase() || !Klein_Method_Database_Handler.this.hasProjectTables())
				return null;
			this.ensureBestStateTables();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(
					"SELECT \"max_log_post\", \"pass\", \"iteration\" FROM \"KleinMethodBestInfo\" WHERE \"id\" = 1").executeQuery();
			return rs.next() ? new double[] { rs.getDouble(1), rs.getInt(2), rs.getInt(3) } : null;
		}

		public void deleteBestState() throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureBestStateTables();
			for (String table : BEST_STATE_TABLES)
				SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"" + table + "\"").execute();
		}

		/**
		 * Liest die aktuellen Daten aus der JAG3D-Projektdatenbank und
		 * uebertraegt sie in das Parameter-Objekt.
		 */
		public void read(Klein_Method_Parameter parameter) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;

			// die Diagnose-Sonden (Trichter/Tabellennamen/ Spaltennamen/Gruppen-IDs) haben ihren Zweck erfuellt - die
			// Ursache (GNSS-Daten liegen in eigenen Tabellen) ist gefunden und behoben (siehe
			// readLogPostGnss()/readEpGnss() unten). Deshalb hier entfernt, um die Konsole nicht laenger mit geloesten
			// Fragen zu fluten. Die Methoden selbst (debugPrintColumnsForTable() etc.) bleiben in der Klasse erhalten,
			// falls spaeter erneut Schema-Fragen auftauchen.

			this.readLogPost(parameter, Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_LOG_POST, ObservationType.DIRECTION);
			this.readLogPost(parameter, Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST, ObservationType.HORIZONTAL_DISTANCE);
			this.readLogPost(parameter, Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST, ObservationType.ZENITH_ANGLE);
			this.readLogPost(parameter, Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST, ObservationType.SLOPE_DISTANCE);
			this.readLogPost(parameter, Klein_Method_Threshold_Type.NIVELLEMENT_LOG_POST, ObservationType.LEVELING);
			// GNSS-Basislinien - jetzt ueber die GNSS-eigenen Tabellen (readLogPostGnss() statt readLogPost(), siehe
			// Klassenkommentar).
			this.readLogPostGnss(parameter, Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST, ObservationType.GNSS1D);
			this.readLogPostGnss(parameter, Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST, ObservationType.GNSS2D);
			this.readLogPostGnss(parameter, Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST, ObservationType.GNSS3D);

			this.readEp(parameter, Klein_Method_Threshold_Type.HORIZONTAL_DIRECTION_EP, ObservationType.DIRECTION);
			this.readEp(parameter, Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_EP, ObservationType.HORIZONTAL_DISTANCE);
			this.readEp(parameter, Klein_Method_Threshold_Type.VERTICAL_DIRECTION_EP, ObservationType.ZENITH_ANGLE);
			this.readEp(parameter, Klein_Method_Threshold_Type.SLOPE_DISTANCE_EP, ObservationType.SLOPE_DISTANCE);
			this.readEp(parameter, Klein_Method_Threshold_Type.NIVELLEMENT_EP, ObservationType.LEVELING);
			// dito fuer EP (readEpGnss() statt readEp()).
			this.readEpGnss(parameter, Klein_Method_Threshold_Type.GNSS_BASELINE_1D_EP, ObservationType.GNSS1D);
			this.readEpGnss(parameter, Klein_Method_Threshold_Type.GNSS_BASELINE_2D_EP, ObservationType.GNSS2D);
			this.readEpGnss(parameter, Klein_Method_Threshold_Type.GNSS_BASELINE_3D_EP, ObservationType.GNSS3D);

			// Deaktivierungs-Zaehler im selben Hintergrund-Lesezyklus mitfuehren (NICHT auf dem JavaFX-UI-Thread lesen -
			// siehe Klein_Method_Tab_Builder.updateDiagnosticValueLabels()).
			this.readGnssBaselineDeactivationCounts(parameter);
			// aktuelle Gruppen-Messunsicherheiten (Spanne je Typ) fuer die Anzeige - im selben Hintergrund-Lesezyklus
			this.readGroupUncertaintyRanges(parameter);
		}
		/** Spanne min-max der Gruppen-Messunsicherheiten (a, b, c) je logPost-Typ ueber alle aktiven Gruppen (Rohwerte). */
		void readGroupUncertaintyRanges(Klein_Method_Parameter parameter) throws SQLException {
			Map<Integer, Klein_Method_Threshold_Type> byObservationType = new java.util.HashMap<Integer, Klein_Method_Threshold_Type>();
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.values())
				if (type.getCategory() == Klein_Method_Threshold_Type.Category.LOG_POST) {
					ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(type);
					if (observationType != null)
						byObservationType.put(observationType.getId(), type);
				}
			Map<Klein_Method_Threshold_Type, double[]> ranges = new java.util.EnumMap<Klein_Method_Threshold_Type, double[]>(Klein_Method_Threshold_Type.class);
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GROUP_UNCERTAINTY_RANGES).executeQuery();
			while (rs.next()) {
				Klein_Method_Threshold_Type type = byObservationType.get(rs.getInt("obs_type"));
				int component = rs.getInt("component");
				int index = component == ObservationGroupUncertaintyType.ZERO_POINT_OFFSET.getId() ? 0
						: component == ObservationGroupUncertaintyType.SQUARE_ROOT_DISTANCE_DEPENDENT.getId() ? 1
								: component == ObservationGroupUncertaintyType.DISTANCE_DEPENDENT.getId() ? 2 : -1;
				if (type == null || index < 0)
					continue;
				double[] range = ranges.computeIfAbsent(type, t -> new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN });
				range[2 * index] = rs.getDouble("min_value");
				range[2 * index + 1] = rs.getDouble("max_value");
			}
			parameter.setGroupUncertaintyRanges(ranges);
		}
		public int readTotalMeasurementAxisCount() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return 0;

			int total = 0;
			for (Klein_Method_Threshold_Type type : LOG_POST_TYPES_FOR_AXIS_COUNT) {
				ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(type);
				boolean isGnssType = type == Klein_Method_Threshold_Type.GNSS_BASELINE_1D_LOG_POST
						|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_2D_LOG_POST
						|| type == Klein_Method_Threshold_Type.GNSS_BASELINE_3D_LOG_POST;
				if (isGnssType)
					total += 3 * Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_GNSS_ROWS, observationType.getId());
				else
					total += Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_LOG_POST_ROWS, observationType.getId());
			}
			return total;
		}
		private void readLogPost(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, ObservationType observationType) throws SQLException {
			try {
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_MAX_LOG_POST));
				stmt.setInt(1, observationType.getId());
				ResultSet rs = stmt.executeQuery();

				// DEBUG-INJEKTION: observationType.getId() und die tatsaechliche Beobachtungszeilenzahl VOR der
				// UI-Zuweisung ausgeben.
				int totalRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_LOG_POST_ROWS, observationType.getId());
				int nonNullRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_LOG_POST_ROWS_NON_NULL, observationType.getId());
				System.out.println("[Klein_Method DEBUG V0.262] readLogPost: type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + ")"
						+ " - Beobachtungszeilen gesamt (enable=TRUE)=" + totalRows
						+ ", davon mit p_post IS NOT NULL=" + nonNullRows);

				Double value = null;
				if (rs.next()) {
					double result = rs.getDouble("max_log_post");
					if (!rs.wasNull())
						value = result;
				}
				System.out.println("[Klein_Method DEBUG V0.262] readLogPost: type=" + type.name()
						+ " -> max_log_post=" + (value == null ? "NULL" : value));

				// Rueckfallebene: fehlender Wert (keine Beobachtungen/kein Ergebnis) -> 0
				parameter.setCurrentValue(type, value);
			} catch (SQLException e) {
				System.out.println("[Klein_Method DEBUG V0.262] readLogPost: SQLException bei type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + "): " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}
		private void readEp(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, ObservationType observationType) throws SQLException {
			try {
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_MAX_EP));
				stmt.setInt(1, observationType.getId());
				ResultSet rs = stmt.executeQuery();

				// DEBUG-INJEKTION: observationType.getId() und die tatsaechliche Beobachtungszeilenzahl VOR der
				// UI-Zuweisung ausgeben.
				int totalRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_LOG_POST_ROWS, observationType.getId());
				int nonNullRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_EP_ROWS_NON_NULL, observationType.getId());
				System.out.println("[Klein_Method DEBUG V0.262] readEp: type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + ")"
						+ " - Beobachtungszeilen gesamt (enable=TRUE)=" + totalRows
						+ ", davon mit influence_on_position IS NOT NULL=" + nonNullRows);

				Double value = null;
				if (rs.next()) {
					double result = rs.getDouble("max_ep");
					if (!rs.wasNull())
						// EP ("influence_on_position") ist eine Laenge, JAG3D speichert intern
						// in Meter (bestaetigt anhand von LengthUnit.java) - unsere EP-Grenzwerte
						// sind aber in mm definiert (siehe Klein_Method_Threshold_Type) -> umrechnen
						value = LengthUnit.MILLIMETER.fromMeter(result);
				}
				System.out.println("[Klein_Method DEBUG V0.262] readEp: type=" + type.name()
						+ " -> max_ep(mm)=" + (value == null ? "NULL" : value));

				// Rueckfallebene: fehlender Wert -> 0
				parameter.setCurrentValue(type, value);
			} catch (SQLException e) {
				System.out.println("[Klein_Method DEBUG V0.262] readEp: SQLException bei type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + "): " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}
		// GNSS-Pendant zu readLogPost() - identische Logik, aber gegen SQL_MAX_LOG_POST_GNSS (Tabellen
		// "GNSSObservationApriori"/ "GNSSObservationAposteriori" statt der generischen Tabellen).
		private void readLogPostGnss(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, ObservationType observationType) throws SQLException {
			try {
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_MAX_LOG_POST_GNSS));
				stmt.setInt(1, observationType.getId());
				ResultSet rs = stmt.executeQuery();

				int totalRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_GNSS_ROWS, observationType.getId());
				int nonNullRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_GNSS_LOG_POST_ROWS_NON_NULL, observationType.getId());
				System.out.println("[Klein_Method DEBUG V0.268] readLogPostGnss: type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + ")"
						+ " - GNSS-Basislinien gesamt (enable=TRUE)=" + totalRows
						+ ", davon mit p_post IS NOT NULL=" + nonNullRows);

				Double value = null;
				if (rs.next()) {
					double result = rs.getDouble("max_log_post");
					if (!rs.wasNull())
						value = result;
				}
				System.out.println("[Klein_Method DEBUG V0.268] readLogPostGnss: type=" + type.name()
						+ " -> max_log_post=" + (value == null ? "NULL" : value));

				// Rueckfallebene: fehlender Wert (keine Beobachtungen/kein Ergebnis) -> 0
				parameter.setCurrentValue(type, value);
			} catch (SQLException e) {
				System.out.println("[Klein_Method DEBUG V0.268] readLogPostGnss: SQLException bei type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + "): " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}
		// GNSS-Pendant zu readEp(). Abweichend von readEp(): keine einzelne "influence_on_position"-Spalte, sondern drei
		// Achsen-Spalten (_x/_y/_z) - SQL liefert je Achse bereits das MAX(ABS(...)) ueber alle Zeilen, hier wird nur
		// noch das Maximum der drei Achsen-Maxima gebildet.
		private void readEpGnss(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, ObservationType observationType) throws SQLException {
			try {
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_MAX_EP_GNSS));
				stmt.setInt(1, observationType.getId());
				ResultSet rs = stmt.executeQuery();

				int totalRows = Klein_Method_Database_Handler.this.debugCountRows(SQL_COUNT_MATCHING_GNSS_ROWS, observationType.getId());
				System.out.println("[Klein_Method DEBUG V0.268] readEpGnss: type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + ")"
						+ " - GNSS-Basislinien gesamt (enable=TRUE)=" + totalRows);

				Double value = null;
				if (rs.next()) {
					Double maxX = null, maxY = null, maxZ = null;
					double x = rs.getDouble("max_ep_x");
					if (!rs.wasNull())
						maxX = x;
					double y = rs.getDouble("max_ep_y");
					if (!rs.wasNull())
						maxY = y;
					double z = rs.getDouble("max_ep_z");
					if (!rs.wasNull())
						maxZ = z;

					System.out.println("[Klein_Method DEBUG V0.268] readEpGnss: type=" + type.name()
							+ " -> max_ep_x(m)=" + (maxX == null ? "NULL" : maxX)
							+ ", max_ep_y(m)=" + (maxY == null ? "NULL" : maxY)
							+ ", max_ep_z(m)=" + (maxZ == null ? "NULL" : maxZ));

					boolean any = false;
					double combined = 0.0;
					if (maxX != null) { combined = Math.max(combined, maxX); any = true; }
					if (maxY != null) { combined = Math.max(combined, maxY); any = true; }
					if (maxZ != null) { combined = Math.max(combined, maxZ); any = true; }
					if (any)
						// influence_on_position ist eine Laenge, JAG3D speichert intern in
						// Meter (siehe SQL_MAX_EP/LengthUnit.java) - Grenzwerte sind in mm.
						value = LengthUnit.MILLIMETER.fromMeter(combined);
				}
				System.out.println("[Klein_Method DEBUG V0.268] readEpGnss: type=" + type.name()
						+ " -> max_ep(mm)=" + (value == null ? "NULL" : value));

				// Rueckfallebene: fehlender Wert -> 0
				parameter.setCurrentValue(type, value);
			} catch (SQLException e) {
				System.out.println("[Klein_Method DEBUG V0.268] readEpGnss: SQLException bei type=" + type.name()
						+ " observationType=" + observationType.name() + " (id=" + observationType.getId() + "): " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}
		/**
		 * Schreibt die aktuellen Werte aus dem Parameter-Objekt zurueck in die JAG3D-Projektdatenbank.
		 *
		 * Sobald das feststeht, hier analog zu read() mit einem PreparedStatement (UPDATE/INSERT) ergaenzen - Verbindung
		 * genauso ueber SQLManager.getInstance().getDataBase().getPreparedStatement(sql).
		 */
		public void write(Klein_Method_Parameter parameter) throws SQLException {
			// Platzhalter - noch keine echte Datenbankabfrage, siehe TODO oben.
		}
		public double[] readGroupVarianceRatios(Klein_Method_Threshold_Type logPostType) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return new double[] { 1.0, 1.0, 1.0 }; // neutral, keine Kalibrierung ausloesen, wenn keine DB offen ist

			VarianceComponentType[] componentTypes = new VarianceComponentType[] {
					Klein_Method_Database_Handler.this.getVarianceComponentTypeA(logPostType),
					Klein_Method_Database_Handler.this.getVarianceComponentTypeB(logPostType),
					Klein_Method_Database_Handler.this.getVarianceComponentTypeC(logPostType)
			};

			double[] ratios = new double[] { 1.0, 1.0, 1.0 };
			for (int i = 0; i < componentTypes.length; i++) {
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_VARIANCE_RATIO);
				stmt.setInt(1, componentTypes[i].getId());
				ResultSet rs = stmt.executeQuery();

				if (rs.next()) {
					double value = rs.getDouble("sigma2apost");
					if (!rs.wasNull())
						// Defensive Betragsbildung, konsistent mit p_post/influence_on_position -
						// "sigma2apost" ist als Verhaeltnis von Quadratsummen mathematisch zwar
						// immer >= 0, es gibt aber (anders als bei p_post) keine Bestaetigung aus
						// echtem JAG3D-Quellcode dafuer, WIE der Wert in dieser DB tatsaechlich
						// gespeichert wird - daher zur Sicherheit trotzdem Math.abs().
						ratios[i] = Math.abs(value);
					// sonst: keine Redundanz/Zeile fuer diese Komponente -> neutral (1.0) bleibt
				}
			}
			return ratios;
		}
		public double[][] readVarianceComponentRedundancies(Klein_Method_Threshold_Type logPostType) throws SQLException {
			if (!hasOpenDatabase())
				return null;
			VarianceComponentType[] componentTypes = new VarianceComponentType[] {
					Klein_Method_Database_Handler.this.getVarianceComponentTypeA(logPostType), Klein_Method_Database_Handler.this.getVarianceComponentTypeB(logPostType), Klein_Method_Database_Handler.this.getVarianceComponentTypeC(logPostType) };
			double[][] result = new double[3][];
			for (int i = 0; i < 3; i++) {
				result[i] = new double[] { Double.NaN, Double.NaN };
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_VARIANCE_REDUNDANCY);
				stmt.setInt(1, componentTypes[i].getId());
				ResultSet rs = stmt.executeQuery();
				if (rs.next()) {
					double r = rs.getDouble("redundancy");
					if (!rs.wasNull())
						result[i][0] = Math.abs(r);
					int n = rs.getInt("number_of_observations");
					if (!rs.wasNull())
						result[i][1] = n;
				}
			}
			return result;
		}
		public List<double[]> readObservationFitData(Klein_Method_Threshold_Type logPostType) throws SQLException {
			List<double[]> rows = new ArrayList<double[]>();
			if (!hasOpenDatabase())
				return rows;
			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(logPostType);
			boolean gnss = observationType == ObservationType.GNSS1D || observationType == ObservationType.GNSS2D || observationType == ObservationType.GNSS3D;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(gnss ? SQL_GNSS_FIT_DATA : SQL_OBSERVATION_FIT_DATA);
			stmt.setInt(1, observationType.getId());
			ResultSet rs = stmt.executeQuery();
			int k = observationType == ObservationType.GNSS3D ? 3 : observationType == ObservationType.GNSS2D ? 2 : 1;
			while (rs.next()) {
				double omega = rs.getDouble("omega");
				if (gnss) {
					double r = Math.abs(rs.getDouble("ry")) + Math.abs(rs.getDouble("rx")) + Math.abs(rs.getDouble("rz"));
					rows.add(new double[] { Math.abs(omega), r, Double.NaN, k });
				} else {
					double r = rs.getDouble("redundancy");
					double sy = rs.getDouble("sy");
					boolean known = !rs.wasNull();
					double sx = rs.getDouble("sx");
					known &= !rs.wasNull();
					double ey = rs.getDouble("ey");
					known &= !rs.wasNull();
					double ex = rs.getDouble("ex");
					known &= !rs.wasNull();
					double distance = known ? Math.hypot(ey - sy, ex - sx) : Double.NaN;
					rows.add(new double[] { Math.abs(omega), Math.abs(r), distance, 1 });
				}
			}
			return rows;
		}
		public List<Integer> readGroupIds(Klein_Method_Threshold_Type logPostType) throws SQLException {
			List<Integer> groupIds = new ArrayList<Integer>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return groupIds;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(logPostType);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GROUP_IDS);
			stmt.setInt(1, observationType.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next())
				groupIds.add(rs.getInt("id"));

			return groupIds;
		}
		public double[] readGroupUncertainty(int groupId) throws SQLException {
			// Reihenfolge im Ergebnis-Array: [0]=sigmaA, [1]=sigmaB, [2]=sigmaC
			double[] result = new double[3];

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_GROUP_UNCERTAINTY);
			stmt.setInt(1, groupId);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int type = rs.getInt("type");
				double value = rs.getDouble("value");

				if (type == ObservationGroupUncertaintyType.ZERO_POINT_OFFSET.getId())
					result[0] = value;
				else if (type == ObservationGroupUncertaintyType.SQUARE_ROOT_DISTANCE_DEPENDENT.getId())
					result[1] = value;
				else if (type == ObservationGroupUncertaintyType.DISTANCE_DEPENDENT.getId())
					result[2] = value;
			}
			return result;
		}
		public void writeGroupUncertainty(int groupId, double sigmaA, double sigmaB, double sigmaC) throws SQLException {
			this.writeSingleGroupUncertaintyValue(groupId, ObservationGroupUncertaintyType.ZERO_POINT_OFFSET, sigmaA);
			this.writeSingleGroupUncertaintyValue(groupId, ObservationGroupUncertaintyType.SQUARE_ROOT_DISTANCE_DEPENDENT, sigmaB);
			this.writeSingleGroupUncertaintyValue(groupId, ObservationGroupUncertaintyType.DISTANCE_DEPENDENT, sigmaC);
		}
		private void writeSingleGroupUncertaintyValue(int groupId, ObservationGroupUncertaintyType type, double value) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_WRITE_GROUP_UNCERTAINTY);
			stmt.setInt(1, groupId);
			stmt.setInt(2, type.getId());
			stmt.setDouble(3, value);
			stmt.execute();
		}
		public double readPointVarianceRatio(int dimension) throws SQLException {
			VarianceComponentType vcType = VarianceComponentType.getComponentTypeByPointDimension(dimension);
			if (vcType == null)
				return 1.0; // unbekannte Dimension -> neutral, wie bei fehlender Zeile

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_POINT_VARIANCE_RATIO);
			stmt.setInt(1, vcType.getId());
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				double value = rs.getDouble("sigma2apost");
				if (!rs.wasNull())
					return Math.abs(value); // defensiv, analog zu readGroupVarianceRatios()
			}
			return 1.0; // keine Zeile -> neutral
		}
		public Map<Integer, Integer> readStochasticPointGroupIds() throws SQLException {
			Map<Integer, Integer> groupIdToDimension = new LinkedHashMap<Integer, Integer>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return groupIdToDimension;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_STOCHASTIC_POINT_GROUP_IDS);
			stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next())
				groupIdToDimension.put(rs.getInt("id"), rs.getInt("dimension"));

			return groupIdToDimension;
		}
		public double[] readPointUncertaintySample(int groupId) throws SQLException {
			// Reihenfolge: [0]=sigmaY0, [1]=sigmaX0, [2]=sigmaZ0
			double[] result = new double[] { Double.NaN, Double.NaN, Double.NaN };

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_POINT_UNCERTAINTY_SAMPLE);
			stmt.setInt(1, groupId);
			ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				result[0] = rs.getDouble("sigma_y0");
				result[1] = rs.getDouble("sigma_x0");
				result[2] = rs.getDouble("sigma_z0");
			}
			return result;
		}
		public void writePointGroupUncertainty(int groupId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException {
			// Nur die zur Dimension passenden Komponenten schreiben (1D=nur Z,
			// 2D=nur Y/X, 3D=alle) - siehe screenshot-bestaetigtes UI-Verhalten.
			if (dimension == 2 || dimension == 3) {
				this.writeSinglePointUncertaintyValue(SQL_WRITE_POINT_UNCERTAINTY_Y, groupId, sigmaY);
				this.writeSinglePointUncertaintyValue(SQL_WRITE_POINT_UNCERTAINTY_X, groupId, sigmaX);
			}
			if (dimension == 1 || dimension == 3) {
				this.writeSinglePointUncertaintyValue(SQL_WRITE_POINT_UNCERTAINTY_Z, groupId, sigmaZ);
			}
		}
		public double[] readPointGroupUncertainty(int groupId) throws SQLException {
			double[] result = new double[] { DefaultUncertainty.getUncertaintyY(), DefaultUncertainty.getUncertaintyX(), DefaultUncertainty.getUncertaintyZ() };
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return result;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_POINT_GROUP_UNCERTAINTY);
			stmt.setInt(1, groupId);
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				PointGroupUncertaintyType type = PointGroupUncertaintyType.getEnumByValue(rs.getInt("type"));
				double value = rs.getDouble("value");
				if (type == null || rs.wasNull() || value <= 0.0)
					continue;
				if (type == PointGroupUncertaintyType.COMPONENT_Y)
					result[0] = value;
				else if (type == PointGroupUncertaintyType.COMPONENT_X)
					result[1] = value;
				else if (type == PointGroupUncertaintyType.COMPONENT_Z)
					result[2] = value;
			}
			return result;
		}
		public void writePointGroupUncertaintyValues(int groupId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException {
			if (dimension == 2 || dimension == 3) {
				this.mergePointGroupUncertainty(groupId, PointGroupUncertaintyType.COMPONENT_Y, sigmaY);
				this.mergePointGroupUncertainty(groupId, PointGroupUncertaintyType.COMPONENT_X, sigmaX);
			}
			if (dimension == 1 || dimension == 3)
				this.mergePointGroupUncertainty(groupId, PointGroupUncertaintyType.COMPONENT_Z, sigmaZ);
		}
		private void mergePointGroupUncertainty(int groupId, PointGroupUncertaintyType type, double value) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_POINT_GROUP_UNCERTAINTY);
			stmt.setInt(1, groupId);
			stmt.setInt(2, type.getId());
			stmt.setDouble(3, value);
			stmt.execute();
		}
		private void writeSinglePointUncertaintyValue(String sql, int groupId, double value) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(sql);
			stmt.setDouble(1, value);
			stmt.setInt(2, groupId);
			stmt.execute();
		}
		public List<Klein_Method_Observation_Uncertainty_Row> readObservationsForIndividualCalibration(
				Klein_Method_Threshold_Type logPostType, double bandLowerBound, double bandUpperBound) throws SQLException {
			List<Klein_Method_Observation_Uncertainty_Row> rows = new ArrayList<Klein_Method_Observation_Uncertainty_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(logPostType);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_OBSERVATIONS_IN_BAND));
			stmt.setInt(1, observationType.getId());
			stmt.setDouble(2, bandLowerBound);
			stmt.setDouble(3, bandUpperBound);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int observationId = rs.getInt("id");
				int groupId = rs.getInt("group_id");

				double sigma0Value = rs.getDouble("sigma_0");
				Double currentIndividualUncertainty = rs.wasNull() ? null : sigma0Value;

				double absLogPost = rs.getDouble("abs_log_post");

				// Entfernung fuer das Unsicherheitsmodell wie JAG3D (ObservationGroup.getStdB/getStdC): distance_0, sonst
				// aus den Naeherungskoordinaten (2D bzw. 3D je nach Beobachtungstyp).
				double distance = this.uncertaintyModelDistance(rs, logPostType);

				rows.add(new Klein_Method_Observation_Uncertainty_Row(observationId, groupId, currentIndividualUncertainty, absLogPost, distance));
			}
			return rows;
		}
		// Entfernung fuer das JAG3D-Unsicherheitsmodell. JAG3D nutzt distance_0, wenn eingetragen (> 0), sonst die aus
		// den Naeherungs-koordinaten berechnete Entfernung: 3D bei Schraegstrecke und Zenitwinkel, 2D bei Richtung,
		// Horizontalstrecke und Hoehenunterschied.
		private double uncertaintyModelDistance(ResultSet rs, Klein_Method_Threshold_Type type) throws SQLException {
			double distance = rs.getDouble("distance_0");
			if (!rs.wasNull() && distance > UNCERTAINTY_DISTANCE_EPS)
				return distance;
			double sx = rs.getDouble("ps_x"); boolean missing = rs.wasNull();
			double sy = rs.getDouble("ps_y"); missing |= rs.wasNull();
			double sz = rs.getDouble("ps_z"); missing |= rs.wasNull();
			double ex = rs.getDouble("pe_x"); missing |= rs.wasNull();
			double ey = rs.getDouble("pe_y"); missing |= rs.wasNull();
			double ez = rs.getDouble("pe_z"); missing |= rs.wasNull();
			if (missing)
				return 0.0;
			// 3D wie Observation.getApproximatedCalculatedDistance3D() ohne
			// Lotabweichung: Zielhoehe und Instrumentenhoehe gehen in dz ein.
			double dx = ex - sx, dy = ey - sy, dz = (ez + rs.getDouble("th")) - (sz + rs.getDouble("ih"));
			boolean threeD = type == Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST
					|| type == Klein_Method_Threshold_Type.VERTICAL_DIRECTION_LOG_POST;
			return threeD ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.hypot(dx, dy);
		}
		public void writeIndividualUncertainty(int observationId, double newValue) throws SQLException {
			if (Klein_Method_Database_Handler.this.isRepetitionFrozen(REP_KIND_TERRESTRIAL, observationId))
				return; // regewichtete Wiederholung - von der Kalibrierung ausgenommen
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_WRITE_INDIVIDUAL_UNCERTAINTY);
			stmt.setDouble(1, newValue);
			stmt.setInt(2, observationId);
			stmt.execute();
		}
		// GNSS-Pendant zu readObservationsForIndividualCalibration() - liefert PRO GNSS-Basislinie im Bearbeitungsbereich
		// DREI Zeilen (X/Y/Z), distance je Achse = |Komponente| wie in JAG3D.
		public List<Klein_Method_Observation_Uncertainty_Row> readGnssObservationsForIndividualCalibration(
				Klein_Method_Threshold_Type logPostType, double bandLowerBound, double bandUpperBound) throws SQLException {
			List<Klein_Method_Observation_Uncertainty_Row> rows = new ArrayList<Klein_Method_Observation_Uncertainty_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(logPostType);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_GNSS_OBSERVATIONS_IN_BAND));
			stmt.setInt(1, observationType.getId());
			stmt.setDouble(2, bandLowerBound);
			stmt.setDouble(3, bandUpperBound);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int observationId = rs.getInt("id");
				int groupId = rs.getInt("group_id");
				double absLogPost = rs.getDouble("abs_log_post");

				// jede Spalte EINZELN abfragen und SOFORT ihr eigenes wasNull() lesen -
				// rs.wasNull() bezieht sich immer nur auf den ZULETZT gelesenen Wert.
				double sigmaX0 = rs.getDouble("sigma_x0");
				Double currentX = rs.wasNull() ? null : sigmaX0;
				double sigmaY0 = rs.getDouble("sigma_y0");
				Double currentY = rs.wasNull() ? null : sigmaY0;
				double sigmaZ0 = rs.getDouble("sigma_z0");
				Double currentZ = rs.wasNull() ? null : sigmaZ0;
				// JAG3D nimmt bei GNSS je Achse |Komponente| als Entfernung fuer sigma_b/sigma_c (GNSSBaseline:
				// distanceForUncertaintyModel = |obs|).
				double compX = rs.getDouble("comp_x");
				double compY = rs.getDouble("comp_y");
				double compZ = rs.getDouble("comp_z");

				// echte EP-Achsenwerte (influence_on_position_<achse>) dieser Basis-linie - NULL (kein Wert) wird
				// defensiv als 0.0 behandelt (identische Konvention wie beim bestehenden EP-Auslesen in readEpGnss()).
				double epX = rs.getDouble("ep_x");
				if (rs.wasNull()) epX = 0.0;
				double epY = rs.getDouble("ep_y");
				if (rs.wasNull()) epY = 0.0;
				double epZ = rs.getDouble("ep_z");
				if (rs.wasNull()) epZ = 0.0;

				// distance je Achse = |Komponente| (siehe compX/compY/compZ). der neue Gewichtungs-schluessel (siehe
				// Klein_Method_Functions.performIndividualCalibration Step()) nutzt die hier zusaetzlich gelesenen
				// EP-Achsenwerte (epX/Y/Z).
				rows.add(new Klein_Method_Observation_Uncertainty_Row(observationId, groupId, currentX, absLogPost, Math.abs(compX), Klein_Method_Observation_Uncertainty_Row.GnssAxis.X, epX));
				rows.add(new Klein_Method_Observation_Uncertainty_Row(observationId, groupId, currentY, absLogPost, Math.abs(compY), Klein_Method_Observation_Uncertainty_Row.GnssAxis.Y, epY));
				rows.add(new Klein_Method_Observation_Uncertainty_Row(observationId, groupId, currentZ, absLogPost, Math.abs(compZ), Klein_Method_Observation_Uncertainty_Row.GnssAxis.Z, epZ));
			}
			return rows;
		}
		// GNSS-Pendant zu writeIndividualUncertainty() - schreibt GENAU EINE Achse (nie alle drei gemeinsam, kein
		// Math.max()/keine Vermischung).
		public void writeGnssIndividualUncertainty(int observationId, Klein_Method_Observation_Uncertainty_Row.GnssAxis axis, double newValue) throws SQLException {
			if (Klein_Method_Database_Handler.this.isRepetitionFrozen(REP_KIND_GNSS, observationId))
				return; // regewichtete Wiederholung - von der Kalibrierung ausgenommen
			String sql;
			switch (axis) {
			case X: sql = SQL_WRITE_GNSS_INDIVIDUAL_UNCERTAINTY_X; break;
			case Y: sql = SQL_WRITE_GNSS_INDIVIDUAL_UNCERTAINTY_Y; break;
			case Z: sql = SQL_WRITE_GNSS_INDIVIDUAL_UNCERTAINTY_Z; break;
			default: throw new IllegalArgumentException("Unbekannte GNSS-Achse: " + axis);
			}
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(sql);
			stmt.setDouble(1, newValue);
			stmt.setInt(2, observationId);
			stmt.execute();
		}
		// commitAndCheckpoint() in die beiden Einzelbefehle aufgeteilt -
		// Klein_Method_Functions.performFloorEnforcementCompression() braucht jetzt COMMIT nach JEDEM einzelnen
		// Schreibvorgang, aber CHECKPOINT weiterhin nur EINMAL ganz am Ende (siehe dort). commitAndCheckpoint() selbst
		// bleibt unveraendert als Komfort-Wrapper bestehen - Klein_Method_Iteration_Task nutzt ihn beim
		// Kaskaden-Pass-Wechsel weiterhin unveraendert (Punkt 2 dieses
		// Auftrags: dort bewusst NICHT angefasst).
		public void commit() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;
			PreparedStatement commitStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_COMMIT);
			commitStmt.execute();
		}
		public void checkpoint() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;
			PreparedStatement checkpointStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CHECKPOINT);
			checkpointStmt.execute();
		}
		// siehe Interface fuer die vollstaendige Begruendung. Reihenfolge bewusst COMMIT vor CHECKPOINT (CHECKPOINT
		// schreibt den aktuellen, committeten Stand auf die Festplatte - ohne vorheriges COMMIT wuerde ein CHECKPOINT auf
		// einer nicht-autocommit-Verbindung ggf. einen unvollstaendigen Stand sichern). Beide ueber exakt dieselbe,
		// bereits etablierte Verbindung (SQLManager.getInstance().getDataBase().getPreparedStatement(sql)) - keine zweite
		// Connection, kein neuer Zugriffspfad. intern jetzt eine einfache Weiterleitung an die beiden neuen
		// Einzelmethoden oben.
		public void commitAndCheckpoint() throws SQLException {
			this.commit();
			this.checkpoint();
		}
		// siehe Klein_Method_Database_Handler_Interface fuer die vollstaendige Begruendung - deaktiviert die GESAMTE
		// Basislinie (alle 3 Achsen auf einmal, ueber die Gruppen-Fremdbeziehung "id"), NICHT nur eine Achse - "enable"
		// existiert nur einmal je Basislinien-Zeile in "GNSSObservationApriori", nicht pro Achse.
		public void deactivateGnssObservation(int observationId) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEACTIVATE_GNSS_OBSERVATION);
			stmt.setInt(1, observationId);
			stmt.execute();
		}
		// siehe Interface fuer die vollstaendige Begruendung - zwei einfache COUNT(*)-Abfragen, beide ausschliesslich
		// gegen "GNSSObservationApriori" (keine Gruppen-Verknuepfung noetig, da "enable" hier direkt je Basislinie
		// gefuehrt wird - anders als bei SQL_ACTIVE_GNSS_OBSERVATION_COUNT, die zusaetzlich die Gruppen-Aktivierung
		// mitpruefen muss).
		public void readGnssBaselineDeactivationCounts(Klein_Method_Parameter parameter) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen()) {
				parameter.setDisabledGnssBaselineCount(0);
				parameter.setTotalGnssBaselineCount(0);
				return;
			}

			PreparedStatement totalStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_TOTAL_GNSS_OBSERVATION_COUNT);
			ResultSet totalRs = totalStmt.executeQuery();
			int totalCount = 0;
			if (totalRs.next())
				totalCount = totalRs.getInt("cnt");

			PreparedStatement disabledStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DISABLED_GNSS_OBSERVATION_COUNT);
			ResultSet disabledRs = disabledStmt.executeQuery();
			int disabledCount = 0;
			if (disabledRs.next())
				disabledCount = disabledRs.getInt("cnt");

			parameter.setDisabledGnssBaselineCount(disabledCount);
			parameter.setTotalGnssBaselineCount(totalCount);
		}
		public double[] readAdditionalParameters(Klein_Method_Threshold_Type distanceType) throws SQLException {
			double[] result = new double[] { Double.NaN, Double.NaN }; // { Nullpunktabweichung, Massstab }
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return result;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(distanceType);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ADDITIONAL_PARAMETERS);
			stmt.setInt(1, observationType.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int type = rs.getInt("type");
				double value = rs.getDouble("value"); // a-posteriori (Ergebnis) - NICHT value_0 (a-priori/Startwert)
				if (rs.wasNull())
					continue; // noch kein Ergebnis vorhanden (z.B. Parameter gerade erst aktiviert, noch keine Ausgleichung gelaufen) -> "k.A."

				// UNVERIFIZIERT: ParameterType.ZERO_POINT_OFFSET/SCALE analog zum
				// Namensmuster der anderen JAG3D-Enums angenommen (bestaetigt nur
				// ueber die i18n-Sprachdatei-Schluessel "zero_point_offset"/"scale",
				// nicht am echten ParameterType.java-Quellcode).
				if (type == ParameterType.ZERO_POINT_OFFSET.getId())
					result[0] = value;
				else if (type == ParameterType.SCALE.getId())
					result[1] = value;
			}
			return result;
		}
		public Map<Integer, boolean[]> readAdditionalParameterGroupStates(Klein_Method_Threshold_Type distanceType) throws SQLException {
			Map<Integer, boolean[]> result = new LinkedHashMap<Integer, boolean[]>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return result;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(distanceType);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ADDITIONAL_PARAMETER_GROUP_STATES);
			stmt.setInt(1, observationType.getId());
			stmt.setInt(2, ParameterType.ZERO_POINT_OFFSET.getId());
			stmt.setInt(3, ParameterType.SCALE.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int groupId = rs.getInt("group_id");
				int type = rs.getInt("type");
				boolean enabled = rs.getBoolean("enable");
				boolean[] state = result.computeIfAbsent(groupId, id -> new boolean[] { false, false });
				if (type == ParameterType.ZERO_POINT_OFFSET.getId())
					state[0] = enabled;
				else if (type == ParameterType.SCALE.getId())
					state[1] = enabled;
			}
			return result;
		}
		public void writeAdditionalParameterEnabled(int groupId, boolean isScale, boolean enabled) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_WRITE_ADDITIONAL_PARAMETER_ENABLED);
			stmt.setBoolean(1, enabled);
			stmt.setInt(2, groupId);
			stmt.setInt(3, isScale ? ParameterType.SCALE.getId() : ParameterType.ZERO_POINT_OFFSET.getId());
			stmt.execute();
		}
		public List<Klein_Method_Ep_Row> readAllObservationsForEpCalibration(Klein_Method_Threshold_Type type) throws SQLException {
			List<Klein_Method_Ep_Row> rows = new ArrayList<Klein_Method_Ep_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(type);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_ALL_OBSERVATIONS_FOR_EP));
			stmt.setInt(1, observationType.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int observationId = rs.getInt("id");
				int groupId = rs.getInt("group_id");
				double ep = rs.getDouble("ep");
				double absLogPost = rs.getDouble("abs_log_post");

				double sigma0Value = rs.getDouble("sigma_0");
				Double currentIndividualUncertainty = rs.wasNull() ? null : sigma0Value;

				// Entfernung fuer das Unsicherheitsmodell wie JAG3D (ObservationGroup.getStdB/getStdC): distance_0, sonst
				// aus den Naeherungskoordinaten (2D bzw. 3D je nach Beobachtungstyp).
				double distance = this.uncertaintyModelDistance(rs, type);

				rows.add(new Klein_Method_Ep_Row(observationId, groupId, type, ep, absLogPost, currentIndividualUncertainty, distance));
			}
			return rows;
		}
		public List<Klein_Method_Ep_Row> readAllGnssObservationsForEpCalibration(Klein_Method_Threshold_Type type) throws SQLException {
			List<Klein_Method_Ep_Row> rows = new ArrayList<Klein_Method_Ep_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			ObservationType observationType = Klein_Method_Database_Handler.this.getObservationType(type);

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(withRepetitionTable(SQL_ALL_GNSS_OBSERVATIONS_FOR_EP));
			stmt.setInt(1, observationType.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int observationId = rs.getInt("id");
				int groupId = rs.getInt("group_id");
				double absLogPost = rs.getDouble("abs_log_post");

				// jede Spalte EINZELN abfragen und SOFORT ihr eigenes wasNull() lesen -
				// exakt dieselbe Vorsicht wie in readGnssObservationsForIndividualCalibration().
				double sigmaX0 = rs.getDouble("sigma_x0");
				Double currentX = rs.wasNull() ? null : sigmaX0;
				double sigmaY0 = rs.getDouble("sigma_y0");
				Double currentY = rs.wasNull() ? null : sigmaY0;
				double sigmaZ0 = rs.getDouble("sigma_z0");
				Double currentZ = rs.wasNull() ? null : sigmaZ0;
				// JAG3D nimmt bei GNSS je Achse |Komponente| als Entfernung fuer sigma_b/sigma_c (GNSSBaseline:
				// distanceForUncertaintyModel = |obs|).
				double compX = rs.getDouble("comp_x");
				double compY = rs.getDouble("comp_y");
				double compZ = rs.getDouble("comp_z");

				double epX = rs.getDouble("ep_x");
				if (rs.wasNull()) epX = 0.0;
				double epY = rs.getDouble("ep_y");
				if (rs.wasNull()) epY = 0.0;
				double epZ = rs.getDouble("ep_z");
				if (rs.wasNull()) epZ = 0.0;

				// distance je Achse = |Komponente| (siehe compX/compY/compZ).
				rows.add(new Klein_Method_Ep_Row(observationId, groupId, type, epX, absLogPost, currentX, Math.abs(compX), Klein_Method_Observation_Uncertainty_Row.GnssAxis.X));
				rows.add(new Klein_Method_Ep_Row(observationId, groupId, type, epY, absLogPost, currentY, Math.abs(compY), Klein_Method_Observation_Uncertainty_Row.GnssAxis.Y));
				rows.add(new Klein_Method_Ep_Row(observationId, groupId, type, epZ, absLogPost, currentZ, Math.abs(compZ), Klein_Method_Observation_Uncertainty_Row.GnssAxis.Z));
			}
			return rows;
		}
		public List<Klein_Method_Point_Ep_Row> readStochasticPointEpValues() throws SQLException {
			List<Klein_Method_Point_Ep_Row> rows = new ArrayList<Klein_Method_Point_Ep_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_STOCHASTIC_POINT_EP_VALUES);
			stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int pointId = rs.getInt("point_id");
				int groupId = rs.getInt("group_id");
				int dimension = rs.getInt("dimension");
				String name = rs.getString("name");
				String groupName = rs.getString("group_name");

				double epYRaw = rs.getDouble("ep_y");
				Double epY = rs.wasNull() ? null : Math.abs(LengthUnit.MILLIMETER.fromMeter(epYRaw));

				double epXRaw = rs.getDouble("ep_x");
				Double epX = rs.wasNull() ? null : Math.abs(LengthUnit.MILLIMETER.fromMeter(epXRaw));

				double epZRaw = rs.getDouble("ep_z");
				Double epZ = rs.wasNull() ? null : Math.abs(LengthUnit.MILLIMETER.fromMeter(epZRaw));

				rows.add(new Klein_Method_Point_Ep_Row(pointId, groupId, dimension, name, groupName, epY, epX, epZ));
			}
			return rows;
		}
		public int createPointGroup(String name, int dimension, PointType type) throws SQLException {
			PreparedStatement insertStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_POINT_GROUP);
			insertStmt.setString(1, name);
			insertStmt.setInt(2, type.getId());
			insertStmt.setInt(3, dimension);
			insertStmt.execute();

			// Generierte ID ueber Rueckfrage ermitteln (hoechste passende id =
			// die soeben eingefuegte Zeile, da IDs aufsteigend vergeben werden) -
			// robuster als sich auf eine bestimmte JDBC-generated-keys-API der
			// SQLManager-Kapselung zu verlassen, die wir nicht kennen.
			PreparedStatement findStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_FIND_POINT_GROUP_BY_NAME);
			findStmt.setString(1, name);
			findStmt.setInt(2, type.getId());
			findStmt.setInt(3, dimension);
			ResultSet rs = findStmt.executeQuery();
			if (rs.next())
				return rs.getInt("id");
			throw new SQLException("Neu angelegte Punktgruppe \"" + name + "\" konnte nicht wiedergefunden werden.");
		}
		public void movePointsToGroup(List<Integer> pointIds, int newGroupId) throws SQLException {
			for (Integer pointId : pointIds) {
				PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MOVE_POINT_TO_GROUP);
				stmt.setInt(1, newGroupId);
				stmt.setInt(2, pointId);
				stmt.execute();
			}
		}
		public List<Klein_Method_Point_Tprio_Row> readStochasticPointTprioValues() throws SQLException {
			List<Klein_Method_Point_Tprio_Row> rows = new ArrayList<Klein_Method_Point_Tprio_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_STOCHASTIC_POINT_TPRIO_VALUES);
			stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int pointId = rs.getInt("point_id");
				int groupId = rs.getInt("group_id");
				int dimension = rs.getInt("dimension");

				double sigmaY0Raw = rs.getDouble("sigma_y0");
				Double sigmaY0 = rs.wasNull() ? null : sigmaY0Raw;

				double sigmaX0Raw = rs.getDouble("sigma_x0");
				Double sigmaX0 = rs.wasNull() ? null : sigmaX0Raw;

				double sigmaZ0Raw = rs.getDouble("sigma_z0");
				Double sigmaZ0 = rs.wasNull() ? null : sigmaZ0Raw;

				double tprioRaw = rs.getDouble("tprio");
				Double tprio = rs.wasNull() ? null : tprioRaw;

				double tpostRaw = rs.getDouble("tpost");
				Double tpost = rs.wasNull() ? null : tpostRaw;

				String pointName = rs.getString("point_name");

				rows.add(new Klein_Method_Point_Tprio_Row(pointId, groupId, dimension, sigmaY0, sigmaX0, sigmaZ0, tprio, tpost, pointName));
			}
			return rows;
		}
		public void writePointUncertainty(int pointId, int dimension, double sigmaY, double sigmaX, double sigmaZ) throws SQLException {
			if (dimension == 2 || dimension == 3) {
				PreparedStatement stmtY = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_WRITE_POINT_UNCERTAINTY_INDIVIDUAL_Y);
				stmtY.setDouble(1, sigmaY);
				stmtY.setInt(2, pointId);
				stmtY.execute();

				PreparedStatement stmtX = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_WRITE_POINT_UNCERTAINTY_INDIVIDUAL_X);
				stmtX.setDouble(1, sigmaX);
				stmtX.setInt(2, pointId);
				stmtX.execute();
			}
			if (dimension == 1 || dimension == 3) {
				PreparedStatement stmtZ = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_WRITE_POINT_UNCERTAINTY_INDIVIDUAL_Z);
				stmtZ.setDouble(1, sigmaZ);
				stmtZ.setInt(2, pointId);
				stmtZ.execute();
			}
		}	}

	// ============================================================================================================
	/** Datenstand: aktive Typen, Ausgleichungsstufe, Pruefsummen, Mittelwerte. */
	private final class Status_Part {

		public Set<ObservationType> readActiveObservationTypes() throws SQLException {
			Set<ObservationType> activeTypes = new HashSet<ObservationType>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return activeTypes;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_OBSERVATION_TYPES);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int typeId = rs.getInt("type");
				for (ObservationType observationType : ObservationType.values()) {
					if (observationType.getId() == typeId) {
						activeTypes.add(observationType);
						break;
					}
				}
			}
			return activeTypes;
		}
		// siehe Klassenkommentar von SQL_ACTIVE_GNSS_OBSERVATION_COUNT - unabhaengig von readActiveObservationTypes(), da
		// diese GNSS-Basislinien strukturell nicht erfassen kann.
		public boolean hasActiveGnssObservations() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return false;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_GNSS_OBSERVATION_COUNT);
			ResultSet rs = stmt.executeQuery();
			if (rs.next())
				return rs.getLong("cnt") > 0;
			return false;
		}
		public long readObservationCount() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return 0L;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_OBSERVATION_COUNT);
			ResultSet rs = stmt.executeQuery();
			if (rs.next())
				return rs.getLong("cnt");
			return 0L;
		}
		public Set<PointType> readActivePointTypes() throws SQLException {
			Set<PointType> activeTypes = new HashSet<PointType>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return activeTypes;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ACTIVE_POINT_TYPES);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int typeId = rs.getInt("type");
				for (PointType pointType : PointType.values()) {
					if (pointType.getId() == typeId) {
						activeTypes.add(pointType);
						break;
					}
				}
			}
			return activeTypes;
		}
		public String readProjectionTypeDisplayName() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return "unbekannt";

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_PROJECTION_TYPE);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return "unbekannt";

			ProjectionType projectionType = ProjectionType.getEnumByValue(rs.getInt("projection_type"));
			if (projectionType == null)
				return "unbekannt";

			switch (projectionType) {
			case GAUSS_KRUEGER:
				return "Gau\u00DF-Kr\u00FCger";
			case UTM:
				return "UTM";
			case LOCAL_CARTESIAN:
				return "Lokal/kartesisch";
			default:
				return projectionType.name();
			}
		}
		private void ensureChecksumTableExists() throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_CHECKSUM_TABLE);
			stmt.execute();
		}
		public String computeCurrentStepChecksum() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return "";

			StringBuilder canonical = new StringBuilder();

			PreparedStatement groupStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ALL_GROUP_UNCERTAINTIES_FOR_CHECKSUM);
			ResultSet groupRs = groupStmt.executeQuery();
			while (groupRs.next()) {
				canonical.append(groupRs.getInt("group_id")).append(':')
						.append(groupRs.getInt("type")).append(':')
						.append(groupRs.getDouble("value")).append(';');
			}

			PreparedStatement individualStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ALL_INDIVIDUAL_UNCERTAINTIES_FOR_CHECKSUM);
			ResultSet individualRs = individualStmt.executeQuery();
			while (individualRs.next()) {
				double sigma0 = individualRs.getDouble("sigma_0");
				if (individualRs.wasNull())
					sigma0 = 0.0; // "leer" behandeln wie 0 - konsistent zu hasNoIndividualUncertainty()
				canonical.append(individualRs.getInt("id")).append(':').append(sigma0).append(';');
			}

			PreparedStatement additionalParamStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ALL_ADDITIONAL_PARAMETERS_FOR_CHECKSUM);
			ResultSet additionalParamRs = additionalParamStmt.executeQuery();
			while (additionalParamRs.next()) {
				canonical.append(additionalParamRs.getInt("id")).append(':')
						.append(additionalParamRs.getInt("group_id")).append(':')
						.append(additionalParamRs.getInt("type")).append(':')
						.append(additionalParamRs.getBoolean("enable")).append(';');
			}

			// Genau in diesem Zustand (freie AGL erfolgreich, logPost unter dem Grenzwert) muessen Festpunkte planmaessig
			// von "Datumsgebende Neupunkte" zu "Bewegliche Anschlusspunkte" verschoben werden koennen, OHNE dass das
			// Ergebnis der freien AGL dadurch als ungueltig gilt - der User ist sich bewusst, dass sich dadurch die
			// beteiligten Punkte aendern, das ist der gewollte naechste Schritt zur dynamischen AGL, keine fehlerhafte
			// Manipulation.

			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] hashBytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
				StringBuilder hex = new StringBuilder();
				for (byte b : hashBytes)
					hex.append(String.format("%02x", b));
				return hex.toString();
			} catch (NoSuchAlgorithmException e) {
				// SHA-256 ist Teil jeder Standard-JRE, sollte praktisch nie auftreten
				throw new SQLException("Klein_Method: SHA-256 nicht verfuegbar: " + e.getMessage());
			}
		}
		public void storeStepChecksum(Klein_Method_Adjustment_Step step, String checksum) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;

			this.ensureChecksumTableExists();

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_CHECKSUM);
			stmt.setString(1, step.name());
			stmt.setString(2, checksum);
			stmt.execute();
		}
		public String readStoredStepChecksum(Klein_Method_Adjustment_Step step) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return null;

			this.ensureChecksumTableExists();

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_CHECKSUM);
			stmt.setString(1, step.name());
			ResultSet rs = stmt.executeQuery();
			if (rs.next())
				return rs.getString("checksum");
			return null;
		}
		public void storeLastVerifiedChecksum(String checksum) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;

			this.ensureChecksumTableExists();

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_CHECKSUM);
			stmt.setString(1, LAST_VERIFIED_CHECKSUM_KEY);
			stmt.setString(2, checksum);
			stmt.execute();
		}
		public void storeCompletedAutomaticStep(Klein_Method_Adjustment_Step step) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;

			this.ensureChecksumTableExists();

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_CHECKSUM);
			stmt.setString(1, COMPLETED_STEP_KEY);
			stmt.setString(2, step == null ? "" : step.name());
			stmt.execute();
		}
		public Klein_Method_Adjustment_Step readCompletedAutomaticStep() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return null;

			this.ensureChecksumTableExists();

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_CHECKSUM);
			stmt.setString(1, COMPLETED_STEP_KEY);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return null;
			String value = rs.getString("checksum");
			if (value == null || value.isEmpty())
				return null;
			try {
				return Klein_Method_Adjustment_Step.valueOf(value);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		public Map<Integer, double[]> readGnssBaselineMidpoints() throws SQLException {
			Map<Integer, double[]> midpoints = new LinkedHashMap<Integer, double[]>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return midpoints;

			Map<String, double[]> coordinatesByName = new LinkedHashMap<String, double[]>();
			PreparedStatement pointStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_APOSTERIORI_POINTS_BY_NAME);
			ResultSet pointRs = pointStmt.executeQuery();
			while (pointRs.next()) {
				String name = pointRs.getString("name");
				if (name != null && !coordinatesByName.containsKey(name))
					coordinatesByName.put(name, new double[] { pointRs.getDouble("x"), pointRs.getDouble("y"), pointRs.getDouble("z") });
			}

			PreparedStatement baselineStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GNSS_BASELINE_ENDPOINTS);
			ResultSet baselineRs = baselineStmt.executeQuery();
			while (baselineRs.next()) {
				double[] start = coordinatesByName.get(baselineRs.getString("start_point_name"));
				double[] end = coordinatesByName.get(baselineRs.getString("end_point_name"));
				if (start == null || end == null)
					continue;
				midpoints.put(baselineRs.getInt("id"), new double[] {
						0.5 * (start[0] + end[0]), 0.5 * (start[1] + end[1]), 0.5 * (start[2] + end[2]) });
			}
			return midpoints;
		}
		public double[] readMeanAposterioriPointCoordinates() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return new double[] { 0.0, 0.0, 0.0, 0.0 };

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MEAN_APOSTERIORI_POINT);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return new double[] { 0.0, 0.0, 0.0, 0.0 };
			long count = rs.getLong("cnt");
			if (count <= 0)
				return new double[] { 0.0, 0.0, 0.0, 0.0 };
			return new double[] { rs.getDouble("mx"), rs.getDouble("my"), rs.getDouble("mz"), count };
		}
		public String readLastVerifiedChecksum() throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return null;

			this.ensureChecksumTableExists();

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_CHECKSUM);
			stmt.setString(1, LAST_VERIFIED_CHECKSUM_KEY);
			ResultSet rs = stmt.executeQuery();
			if (rs.next())
				return rs.getString("checksum");
			return null; // noch nie geprueft in diesem Projekt
		}
		public Map<ObservationType, Integer> readActiveObservationCounts() throws SQLException {
			Map<ObservationType, Integer> counts = new LinkedHashMap<ObservationType, Integer>();
			if (!hasOpenDatabase())
				return counts;
			for (String sql : new String[] { SQL_ACTIVE_OBSERVATION_COUNTS, SQL_ACTIVE_GNSS_OBSERVATION_COUNTS }) {
				ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(sql).executeQuery();
				while (rs.next()) {
					ObservationType type = ObservationType.getEnumByValue(rs.getInt("type"));
					if (type != null)
						counts.merge(type, rs.getInt("cnt"), Integer::sum);
				}
			}
			return counts;
		}	}

	// ============================================================================================================
	/** Koordinaten: Export, freie Loesung, Naeherungen, Umrechnung (mit Sicherungen), Anschlussbereiche, Fangkreis. */
	private final class Coordinate_Part {

		public List<Klein_Method_Point_Coordinate_Row> readAllActivePointCoordinates() throws SQLException {
			List<Klein_Method_Point_Coordinate_Row> rows = new ArrayList<Klein_Method_Point_Coordinate_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ALL_POINT_COORDINATES);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				String name = rs.getString("name");
				String code = rs.getString("code");

				double y = rs.getDouble("y");
				Double rechtswert = rs.wasNull() ? null : y;

				double x = rs.getDouble("x");
				Double hochwert = rs.wasNull() ? null : x;

				double z = rs.getDouble("z");
				Double hoehe = rs.wasNull() ? null : z;

				rows.add(new Klein_Method_Point_Coordinate_Row(name, code, rechtswert, hochwert, hoehe));
			}
			return rows;
		}
		public List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> readFixedPointRecommendationPoints() throws SQLException {
			List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point> points =
					new ArrayList<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point>();
			if (!hasOpenDatabase())
				return points;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_FIXED_POINT_RECOMMENDATION_POINTS);
			stmt.setInt(1, ObservationType.LEVELING.getId());
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				PointType type = PointType.getEnumByValue(rs.getInt("type"));
				int kind = type == PointType.NEW_POINT ? Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_NEW
						: type == PointType.STOCHASTIC_POINT ? Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_STOCHASTIC
						: Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_FIXED;
				int dimension = rs.getInt("dimension");
				double y = rs.getDouble("y");
				if (rs.wasNull())
					y = Double.NaN;
				double x = rs.getDouble("x");
				if (rs.wasNull())
					x = Double.NaN;
				double z = rs.getDouble("z");
				if (rs.wasNull())
					z = Double.NaN;
				double sigmaY = rs.getDouble("sigma_y0");
				if (rs.wasNull())
					sigmaY = Double.NaN;
				double sigmaZ = rs.getDouble("sigma_z0");
				if (rs.wasNull())
					sigmaZ = Double.NaN;
				double sigmaMm = (dimension == 1 ? sigmaZ : sigmaY) * 1000.0; // DB kanonisch in m
				boolean leveled = rs.getBoolean("leveled");
				points.add(new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point(rs.getInt("id"), rs.getString("name"), kind,
						dimension, y, x, z, kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_STOCHASTIC ? sigmaMm : Double.NaN, leveled));
			}
			return points;
		}
		public void storeFreeSolution(List<Klein_Method_Point_Coordinate_Row> rows) throws SQLException {
			if (!hasOpenDatabase())
				return;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_FREE_SOLUTION_TABLE).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_FREE_SOLUTION).execute();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_FREE_SOLUTION);
			java.util.Set<String> seen = new java.util.HashSet<String>();
			for (Klein_Method_Point_Coordinate_Row row : rows) {
				if (row.getName() == null || row.getRechtswert() == null || row.getHochwert() == null || !seen.add(row.getName()))
					continue;
				stmt.setString(1, row.getName().length() > 256 ? row.getName().substring(0, 256) : row.getName());
				stmt.setDouble(2, row.getRechtswert());
				stmt.setDouble(3, row.getHochwert());
				stmt.execute();
			}
		}
		public java.util.Map<String, double[]> readFreeSolution() throws SQLException {
			java.util.Map<String, double[]> result = new java.util.LinkedHashMap<String, double[]>();
			if (!hasOpenDatabase())
				return result;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_FREE_SOLUTION_TABLE).execute();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_FREE_SOLUTION).executeQuery();
			while (rs.next())
				result.put(rs.getString("name"), new double[] { rs.getDouble("y"), rs.getDouble("x") });
			return result;
		}
		public List<Klein_Method_Functions.Klein_Method_Approximation.Point> readApproximationPoints() throws SQLException {
			List<Klein_Method_Functions.Klein_Method_Approximation.Point> points = new ArrayList<Klein_Method_Functions.Klein_Method_Approximation.Point>();
			if (!hasOpenDatabase())
				return points;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_APPROXIMATION_POINTS).executeQuery();
			while (rs.next()) {
				PointType type = PointType.getEnumByValue(rs.getInt("type"));
				if (type == null)
					continue;
				points.add(new Klein_Method_Functions.Klein_Method_Approximation.Point(rs.getString("name"), type == PointType.NEW_POINT,
						rs.getInt("dimension"), rs.getDouble("y0"), rs.getDouble("x0"), rs.getDouble("z0")));
			}
			return points;
		}
		public List<Klein_Method_Functions.Klein_Method_Approximation.Observation> readApproximationObservations() throws SQLException {
			List<Klein_Method_Functions.Klein_Method_Approximation.Observation> observations = new ArrayList<Klein_Method_Functions.Klein_Method_Approximation.Observation>();
			if (!hasOpenDatabase())
				return observations;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_APPROXIMATION_OBSERVATIONS).executeQuery();
			while (rs.next()) {
				int type = rs.getInt("type");
				if (type < 1 || type > 5)
					continue;
				observations.add(new Klein_Method_Functions.Klein_Method_Approximation.Observation(type, rs.getInt("group_id"), rs.getString("start_point_name"),
						rs.getString("end_point_name"), rs.getDouble("instrument_height"), rs.getDouble("reflector_height"), rs.getDouble("value_0"), 0.0, 0.0, 0.0));
			}
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_APPROXIMATION_GNSS).executeQuery();
			while (rs.next()) {
				int type = rs.getInt("type");
				if (type < 6 || type > 8)
					continue;
				Klein_Method_Functions.Klein_Method_Approximation.Observation o = new Klein_Method_Functions.Klein_Method_Approximation.Observation(type,
						rs.getInt("group_id"), rs.getString("start_point_name"), rs.getString("end_point_name"), 0.0, 0.0, 1.0, rs.getDouble("y0"),
						rs.getDouble("x0"), rs.getDouble("z0"));
				o.id = rs.getInt("id");
				observations.add(o);
			}
			return observations;
		}
		public double[] readEarthCurvatureSetting() throws SQLException {
			double[] result = { 0.0, 6371000.0 };
			if (!hasOpenDatabase())
				return result;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_EARTH_CURVATURE).executeQuery();
			if (rs.next())
				result[0] = rs.getInt("n") > 0 ? 1.0 : 0.0;
			try {
				rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_EARTH_RADIUS).executeQuery();
				if (rs.next()) {
					double radius = conformalSphereRadius(rs.getDouble("major_axis"), rs.getDouble("minor_axis"), rs.getDouble("reference_latitude"));
					if (radius > 6.0e6 && radius < 6.5e6)
						result[1] = radius;
				}
			} catch (SQLException e) {
				// aeltere/abweichende Projektdatenbank: mittlerer Erdradius genuegt fuer Naeherungskoordinaten
			}
			return result;
		}
		public int writeApproximations(List<Klein_Method_Functions.Klein_Method_Approximation.PointResult> points, String stamp) throws SQLException {
			if (!hasOpenDatabase())
				return 0;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_APPROXIMATION_BACKUP).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_APPROXIMATION_BACKUP).execute();
			PreparedStatement backup = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_APPROXIMATION_BACKUP);
			PreparedStatement position = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_APPROXIMATION_POSITION);
			PreparedStatement height = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_APPROXIMATION_HEIGHT);
			int count = 0;
			for (Klein_Method_Functions.Klein_Method_Approximation.PointResult point : points) {
				boolean writePosition = point.writePosition(), writeHeight = point.writeHeight();
				if (!writePosition && !writeHeight)
					continue;
				backup.setString(1, stamp);
				backup.setString(2, point.name);
				backup.execute();
				if (writePosition) {
					position.setDouble(1, point.y);
					position.setDouble(2, point.x);
					position.setString(3, point.name);
					position.execute();
				}
				if (writeHeight) {
					height.setDouble(1, point.z);
					height.setString(2, point.name);
					height.execute();
				}
				count++;
			}
			return count;
		}
		public int restoreApproximationBackup() throws SQLException {
			if (!hasOpenDatabase())
				return 0;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_APPROXIMATION_BACKUP).execute();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_APPROXIMATION_BACKUP).executeQuery();
			List<Object[]> rows = new ArrayList<Object[]>();
			while (rs.next())
				rows.add(new Object[] { rs.getString("name"), rs.getDouble("y0"), rs.getDouble("x0"), rs.getDouble("z0") });
			PreparedStatement update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_APPROXIMATION_ALL);
			for (Object[] row : rows) {
				update.setDouble(1, (Double) row[1]);
				update.setDouble(2, (Double) row[2]);
				update.setDouble(3, (Double) row[3]);
				update.setString(4, (String) row[0]);
				update.execute();
			}
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_APPROXIMATION_BACKUP).execute();
			return rows.size();
		}
		public String[] readApproximationBackupInfo() throws SQLException {
			if (!hasOpenDatabase())
				return null;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_APPROXIMATION_BACKUP).execute();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_APPROXIMATION_BACKUP).executeQuery();
			int count = 0;
			String stamp = null;
			while (rs.next()) {
				count++;
				stamp = rs.getString("stamp");
			}
			return count == 0 ? null : new String[] { stamp, String.valueOf(count) };
		}
		private void ensureLocalBackupTables() throws SQLException {
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_POINTS).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_BASELINES).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_INFO).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_LOCAL_BACKUP_REDUCTION).execute();
		}
		public double[] readPrincipalPoint() throws SQLException {
			if (!hasOpenDatabase() || !Klein_Method_Database_Handler.this.hasProjectTables())
				return null;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_PRINCIPAL_POINT).executeQuery();
			if (!rs.next())
				return null;
			double[] v = new double[8];
			for (int i = 0; i < 8; i++)
				v[i] = rs.getDouble(i + 1);
			return v;
		}
		public void writePrincipalPoint(double[] values) throws SQLException {
			if (!hasOpenDatabase() || values == null || values.length < 8)
				return;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_PRINCIPAL_POINT);
			for (int i = 0; i < 8; i++)
				stmt.setDouble(i + 1, values[i]);
			stmt.execute();
		}
		public List<double[]> readLocalConversionPoints() throws SQLException {
			List<double[]> rows = new ArrayList<double[]>();
			if (!hasOpenDatabase())
				return rows;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_LOCAL_POINTS).executeQuery();
			while (rs.next()) // {id, Dimension, y0, x0, z0, Punktart}
				rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6) });
			return rows;
		}
		public List<double[]> readLocalConversionBaselines() throws SQLException {
			List<double[]> rows = new ArrayList<double[]>();
			if (!hasOpenDatabase())
				return rows;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_LOCAL_BASELINES).executeQuery();
			while (rs.next()) // {id, Typ, y0, x0, z0, Startpunkt-id, Zielpunkt-id, aktiv 1/0, Gruppen-id}
				rows.add(new double[] { rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getInt(6), rs.getInt(7),
						rs.getBoolean(8) ? 1 : 0, rs.getInt(9) });
			return rows;
		}
		public int readZenithAndLevelingCount() throws SQLException {
			if (!hasOpenDatabase())
				return 0;
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ZENITH_LEVELING_COUNT).executeQuery();
			return rs.next() ? rs.getInt("n") : 0;
		}
		public int[] applyLocalConversion(Map<Integer, double[]> points, Map<Integer, double[]> baselines, String stamp) throws SQLException {
			if (!hasOpenDatabase())
				return new int[] { 0, 0 };
			this.ensureLocalBackupTables();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupPoint\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupBaseline\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupInfo\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupReduction\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_BACKUP_REDUCTION).execute();
			PreparedStatement info = SQLManager.getInstance().getDataBase().getPreparedStatement(
					"INSERT INTO \"KleinMethodLocalBackupInfo\" (\"id\", \"stamp\", \"projection_type\") VALUES (1, ?, ?)");
			ProjectionType projection = Klein_Method_Database_Handler.this.readProjectionType();
			info.setString(1, stamp);
			info.setInt(2, projection == null ? ProjectionType.LOCAL_CARTESIAN.getId() : projection.getId());
			info.execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(
					"INSERT INTO \"KleinMethodLocalBackupPoint\" (\"id\", \"y0\", \"x0\", \"z0\") SELECT \"id\", \"y0\", \"x0\", \"z0\" FROM \"PointApriori\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(
					"INSERT INTO \"KleinMethodLocalBackupBaseline\" (\"id\", \"y0\", \"x0\", \"z0\") SELECT \"id\", \"y0\", \"x0\", \"z0\" FROM \"GNSSObservationApriori\"").execute();
			PreparedStatement update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_POINT_ALL);
			for (Map.Entry<Integer, double[]> e : points.entrySet()) {
				update.setDouble(1, e.getValue()[0]);
				update.setDouble(2, e.getValue()[1]);
				update.setDouble(3, e.getValue()[2]);
				update.setInt(4, e.getKey());
				update.execute();
			}
			update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_BASELINE_ALL);
			for (Map.Entry<Integer, double[]> e : baselines.entrySet()) {
				update.setDouble(1, e.getValue()[0]);
				update.setDouble(2, e.getValue()[1]);
				update.setDouble(3, e.getValue()[2]);
				update.setInt(4, e.getKey());
				update.execute();
			}
			// immer "lokal ellipsoidisch" (lokale Koordinaten um den Bezugspunkt = P0): JAG3D reduziert Zenitwinkel, Richtungen,
			// Strecken und Nivellement dann auf die Lotrichtung jedes Punkts; reine GNSS-Netze rechnen identisch wie "lokal kartesisch"
			if (projection != ProjectionType.LOCAL_ELLIPSOIDAL) {
				PreparedStatement projectionStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_PROJECTION);
				projectionStmt.setInt(1, ProjectionType.LOCAL_ELLIPSOIDAL.getId());
				projectionStmt.execute();
			}
			return new int[] { points.size(), baselines.size() };
		}
		public int restoreLocalConversion() throws SQLException {
			if (!hasOpenDatabase())
				return 0;
			this.ensureLocalBackupTables();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"projection_type\" FROM \"KleinMethodLocalBackupInfo\" WHERE \"id\" = 1").executeQuery();
			if (!rs.next())
				return 0;
			int projectionId = rs.getInt(1);
			List<double[]> rows = new ArrayList<double[]>();
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"id\", \"y0\", \"x0\", \"z0\" FROM \"KleinMethodLocalBackupPoint\"").executeQuery();
			while (rs.next())
				rows.add(new double[] { rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4) });
			PreparedStatement update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_POINT_ALL);
			for (double[] r : rows) {
				update.setDouble(1, r[1]);
				update.setDouble(2, r[2]);
				update.setDouble(3, r[3]);
				update.setInt(4, (int) r[0]);
				update.execute();
			}
			List<double[]> lines = new ArrayList<double[]>();
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"id\", \"y0\", \"x0\", \"z0\" FROM \"KleinMethodLocalBackupBaseline\"").executeQuery();
			while (rs.next())
				lines.add(new double[] { rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4) });
			update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_BASELINE_ALL);
			for (double[] r : lines) {
				update.setDouble(1, r[1]);
				update.setDouble(2, r[2]);
				update.setDouble(3, r[3]);
				update.setInt(4, (int) r[0]);
				update.execute();
			}
			PreparedStatement projectionStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_PROJECTION);
			projectionStmt.setInt(1, projectionId);
			projectionStmt.execute();
			// Bezugspunkt (Hauptpunkt) wie vor der Umrechnung - Sicherungen vor V0.436 haben keinen, dann bleibt er
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_BACKUP_REDUCTION).executeQuery();
			if (rs.next()) {
				double[] v = new double[8];
				for (int i = 0; i < 8; i++)
					v[i] = rs.getDouble(i + 1);
				this.writePrincipalPoint(v);
			}
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupReduction\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupPoint\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupBaseline\"").execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement("DELETE FROM \"KleinMethodLocalBackupInfo\"").execute();
			return rows.size();
		}
		public String[] readLocalConversionBackupInfo() throws SQLException {
			if (!hasOpenDatabase())
				return null;
			this.ensureLocalBackupTables();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT \"stamp\" FROM \"KleinMethodLocalBackupInfo\" WHERE \"id\" = 1").executeQuery();
			if (!rs.next())
				return null;
			String stamp = rs.getString(1);
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT COUNT(*) FROM \"KleinMethodLocalBackupPoint\"").executeQuery();
			int points = rs.next() ? rs.getInt(1) : 0;
			rs = SQLManager.getInstance().getDataBase().getPreparedStatement("SELECT COUNT(*) FROM \"KleinMethodLocalBackupBaseline\"").executeQuery();
			int baselines = rs.next() ? rs.getInt(1) : 0;
			return new String[] { stamp, String.valueOf(points), String.valueOf(baselines) };
		}
		public int[] updateLocalCoordinates(Map<Integer, double[]> points, Map<Integer, double[]> baselines) throws SQLException {
			if (!hasOpenDatabase())
				return new int[] { 0, 0 };
			PreparedStatement update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_POINT_ALL);
			for (Map.Entry<Integer, double[]> e : points.entrySet()) {
				update.setDouble(1, e.getValue()[0]);
				update.setDouble(2, e.getValue()[1]);
				update.setDouble(3, e.getValue()[2]);
				update.setInt(4, e.getKey());
				update.execute();
			}
			update = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_UPDATE_BASELINE_ALL);
			for (Map.Entry<Integer, double[]> e : baselines.entrySet()) {
				update.setDouble(1, e.getValue()[0]);
				update.setDouble(2, e.getValue()[1]);
				update.setDouble(3, e.getValue()[2]);
				update.setInt(4, e.getKey());
				update.execute();
			}
			return new int[] { points.size(), baselines.size() };
		}
		public void addToLocalConversionBackup(java.util.Set<Integer> pointIds, java.util.Set<Integer> baselineIds) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureLocalBackupTables();
			PreparedStatement point = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ADD_LOCAL_BACKUP_POINT);
			for (int id : pointIds) {
				point.setInt(1, id);
				point.setInt(2, id);
				point.execute();
			}
			PreparedStatement baseline = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ADD_LOCAL_BACKUP_BASELINE);
			for (int id : baselineIds) {
				baseline.setInt(1, id);
				baseline.setInt(2, id);
				baseline.execute();
			}
		}
		public void clearApproximationBackup() throws SQLException {
			if (!hasOpenDatabase())
				return;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_APPROXIMATION_BACKUP).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_APPROXIMATION_BACKUP).execute();
		}
		public void deleteFreeSolution() throws SQLException {
			if (!hasOpenDatabase())
				return;
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_FREE_SOLUTION_TABLE).execute();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_FREE_SOLUTION_IF_EXISTS).execute();
		}
		private void ensureAreaTableExists() throws SQLException {
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_AREA_TABLE).execute();
		}
		public List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> readConnectionAreas() throws SQLException {
			List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> areas =
					new ArrayList<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area>();
			if (!hasOpenDatabase())
				return areas;
			this.ensureAreaTableExists();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_AREAS).executeQuery();
			while (rs.next()) {
				Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area = new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area(
						rs.getInt("id"), rs.getString("name"), rs.getString("shape"));
				try {
					area.parseGeometry(rs.getString("geometry"));
				} catch (NumberFormatException e) {
					continue; // beschaedigter Eintrag - ueberspringen
				}
				String points = rs.getString("points");
				if (points != null && !points.isEmpty())
					for (String name : points.split(AREA_NAME_SEPARATOR, -1))
						if (!name.isEmpty())
							area.pointNames.add(name);
				areas.add(area);
			}
			return areas;
		}
		public void replaceConnectionAreas(List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> areas) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureAreaTableExists();
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_AREAS).execute();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_INSERT_AREA);
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area : areas) {
				stmt.setInt(1, area.id);
				stmt.setString(2, area.name.length() > 256 ? area.name.substring(0, 256) : area.name);
				stmt.setString(3, area.shape);
				stmt.setString(4, area.geometryText());
				stmt.setString(5, String.join(AREA_NAME_SEPARATOR, area.pointNames));
				stmt.execute();
			}
		}
		public List<Klein_Method_Point_Coordinate_Row> readAllPointAprioriCoordinatesForFangkreisScan() throws SQLException {
			List<Klein_Method_Point_Coordinate_Row> rows = new ArrayList<Klein_Method_Point_Coordinate_Row>();
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return rows;

			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ALL_POINT_APRIORI_COORDINATES_FOR_FANGKREIS);
			ResultSet rs = stmt.executeQuery();

			while (rs.next()) {
				int id = rs.getInt("id");
				String name = rs.getString("name");
				String code = rs.getString("code");

				double y = rs.getDouble("y");
				Double rechtswert = rs.wasNull() ? null : y;

				double x = rs.getDouble("x");
				Double hochwert = rs.wasNull() ? null : x;

				double z = rs.getDouble("z");
				Double hoehe = rs.wasNull() ? null : z;

				rows.add(new Klein_Method_Point_Coordinate_Row(name, code, rechtswert, hochwert, hoehe, id));
			}
			return rows;
		}
		public void renamePointForFangkreisViolation(int pointId) throws SQLException {
			if (!SQLManager.getInstance().hasDatabase() || !SQLManager.getInstance().getDataBase().isOpen())
				return;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_RENAME_POINT_FOR_FANGKREIS_VIOLATION);
			stmt.setInt(1, pointId);
			stmt.execute();
		}	}

	// ============================================================================================================
	/** Einstellungen im Projekt: Projektion, Karte, Projekteinstellungen. */
	private final class Settings_Part {

		// Punktnamen bis 256 Zeichen wie in JAG3D; Altprojekte mit kuerzerer Spalte werden beim ersten Zugriff erweitert
		private void ensureMapReferenceTableExists() throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_MAP_REFERENCE_TABLE);
			stmt.execute();
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(
					"SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = CURRENT_SCHEMA "
							+ "AND TABLE_NAME = 'KleinMethodMapReference' AND COLUMN_NAME = 'point_name'").executeQuery();
			if (rs.next() && rs.getInt(1) < 256)
				SQLManager.getInstance().getDataBase().getPreparedStatement(
						"ALTER TABLE \"KleinMethodMapReference\" ALTER COLUMN \"point_name\" SET DATA TYPE VARCHAR(256)").execute();
		}
		public ProjectionType readProjectionType() throws SQLException {
			if (!hasOpenDatabase())
				return null;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_PROJECTION_TYPE);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return null;
			return ProjectionType.getEnumByValue(rs.getInt("projection_type"));
		}
		public Map<String, double[]> readMapReferences() throws SQLException {
			Map<String, double[]> references = new LinkedHashMap<String, double[]>();
			if (!hasOpenDatabase())
				return references;
			this.ensureMapReferenceTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_MAP_REFERENCES);
			ResultSet rs = stmt.executeQuery();
			while (rs.next())
				references.put(rs.getString("point_name"), new double[] { rs.getDouble("latitude"), rs.getDouble("longitude") });
			return references;
		}
		public void storeMapReference(String pointName, double latitude, double longitude) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureMapReferenceTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_MAP_REFERENCE);
			stmt.setString(1, pointName);
			stmt.setDouble(2, latitude);
			stmt.setDouble(3, longitude);
			stmt.setLong(4, System.currentTimeMillis());
			stmt.execute();
		}
		public void deleteMapReference(String pointName) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureMapReferenceTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_MAP_REFERENCE);
			stmt.setString(1, pointName);
			stmt.execute();
		}
		// Kartenprojektion u. ae.: Einstellungstabelle "KleinMethodProjectSetting", Praefix "MAP_".
		public String readMapSetting(String key) throws SQLException {
			return this.readSetting("MAP_" + key);
		}
		public void storeMapSetting(String key, String value) throws SQLException {
			this.storeSetting("MAP_" + key, value);
		}
		// allgemeine Projekteinstellungen im Schluessel/Wert-Speicher (Praefix "KM_")
		public String readProjectSetting(String key) throws SQLException {
			return this.readSetting("KM_" + key);
		}
		public void storeProjectSetting(String key, String value) throws SQLException {
			this.storeSetting("KM_" + key, value);
		}
		private void ensureSettingTableExists() throws SQLException {
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_SETTING_TABLE).execute();
		}
		/** Einstellung lesen - zuerst neue Tabelle, sonst (Altprojekt) die alte; leer = null. */
		private String readSetting(String fullKey) throws SQLException {
			if (!hasOpenDatabase())
				return null;
			this.ensureSettingTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_SETTING);
			stmt.setString(1, fullKey);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				String value = rs.getString("value");
				return value == null || value.isEmpty() ? null : value;
			}
			if (fullKey.length() > 20)
				return null; // passte nie in die alte Tabelle
			Klein_Method_Database_Handler.this.ensureChecksumTableExists();
			PreparedStatement old = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_CHECKSUM);
			old.setString(1, fullKey);
			ResultSet oldRs = old.executeQuery();
			if (!oldRs.next())
				return null;
			String value = oldRs.getString("checksum");
			return value == null || value.isEmpty() ? null : value;
		}
		/** Einstellung schreiben (neue Tabelle; zu lange Werte werden mit klarer Meldung abgewiesen). */
		private void storeSetting(String fullKey, String value) throws SQLException {
			if (!hasOpenDatabase())
				return;
			String text = value == null ? "" : value;
			if (fullKey.length() > SETTING_KEY_LENGTH || text.length() > SETTING_VALUE_LENGTH)
				throw new SQLException("Einstellung " + fullKey + " zu lang (" + text.length() + " Zeichen)");
			this.ensureSettingTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_SETTING);
			stmt.setString(1, fullKey);
			stmt.setString(2, text);
			stmt.execute();
		}	}

	// ============================================================================================================
	/** Groesste Fehler und Tabelle der groben Fehler. */
	private final class Gross_Error_Part {

		public List<Max_Error_Row> readLargestObservationErrors(int limit) throws SQLException {
			List<Max_Error_Row> rows = new ArrayList<Max_Error_Row>();
			if (!hasOpenDatabase() || limit <= 0)
				return rows;
			// je Tabelle die groessten "limit" Zeilen, danach gemeinsam sortieren
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_LARGEST_OBSERVATION_ERRORS);
			ResultSet rs = stmt.executeQuery();
			int count = 0;
			while (count < limit && rs.next()) {
				ObservationType type = ObservationType.getEnumByValue(rs.getInt("type"));
				double residual = Math.abs(rs.getDouble("residual"));
				if (type == ObservationType.DIRECTION || type == ObservationType.ZENITH_ANGLE) {
					// Winkel auf (-pi, pi] zurueckfuehren (Richtungssatz-Orientierung)
					residual = Math.abs(Math.IEEEremainder(residual, 2.0 * Math.PI));
				}
				// lineare Abweichung aus dem geschaetzten groben Fehler (ersatzweise |v|)
				double nabla = Math.abs(rs.getDouble("gross_error"));
				boolean isAngle = type == ObservationType.DIRECTION || type == ObservationType.ZENITH_ANGLE;
				if (isAngle && nabla > 0.0)
					nabla = Math.abs(Math.IEEEremainder(nabla, 2.0 * Math.PI));
				if (!(nabla > 0.0) || Double.isInfinite(nabla))
					nabla = residual;
				double linear = nabla;
				if (isAngle) {
					double sy = rs.getDouble("sy");
					boolean known = !rs.wasNull();
					double dy = rs.getDouble("ey") - sy;
					known &= !rs.wasNull();
					double dx = rs.getDouble("ex") - rs.getDouble("sx"), dz = rs.getDouble("ez") - rs.getDouble("sz");
					double distance = type == ObservationType.DIRECTION ? Math.hypot(dy, dx) : Math.sqrt(dy * dy + dx * dx + dz * dz);
					linear = known && distance > 0.0 ? nabla * distance : Double.NaN;
				}
				// kaum kontrollierte Beobachtung (Redundanzanteil < 5 %) - der geschaetzte grobe Fehler (v / r) ist dort
				// nicht aussagekraeftig -> lineare Abweichung "nicht bestimmbar"
				double redundancy = rs.getDouble("redundancy");
				double mdb = Math.abs(rs.getDouble("mdb"));
				boolean mdbKnown = !rs.wasNull() && Double.isFinite(mdb);
				double mdbLinear = !mdbKnown ? Double.NaN : !isAngle ? mdb : !Double.isNaN(linear) && nabla > 0.0 ? mdb * linear / nabla : Double.NaN;
				if (isAngle && mdbKnown) {
					double sy = rs.getDouble("sy"), dy = rs.getDouble("ey") - sy, dx = rs.getDouble("ex") - rs.getDouble("sx"), dz = rs.getDouble("ez") - rs.getDouble("sz");
					double distance = type == ObservationType.DIRECTION ? Math.hypot(dy, dx) : Math.sqrt(dy * dy + dx * dx + dz * dz);
					mdbLinear = distance > 0.0 ? mdb * distance : Double.NaN;
				}
				if (redundancy < Klein_Method_Default_Values.GROSS_ERROR_MIN_REDUNDANCY)
					linear = Double.NaN;
				Max_Error_Row row = new Max_Error_Row(type, rs.getString("start_name"), rs.getString("end_name"), rs.getDouble("abs_log_post"), residual,
						rs.getInt("id"), false, null, linear, redundancy);
				row.significant = rs.getBoolean("significant");
				row.mdbLinear = mdbLinear;
				double signed = rs.getDouble("gross_error");
				if (!rs.wasNull() && Double.isFinite(signed))
					row.nablaRaw = isAngle ? Math.IEEEremainder(signed, 2.0 * Math.PI) : signed;
				rows.add(row);
				count++;
			}
			stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_LARGEST_GNSS_ERRORS);
			rs = stmt.executeQuery();
			count = 0;
			while (count < limit && rs.next()) {
				ObservationType type = ObservationType.getEnumByValue(rs.getInt("type"));
				double residual = Math.sqrt(Math.pow(rs.getDouble("residual_y"), 2) + Math.pow(rs.getDouble("residual_x"), 2)
						+ Math.pow(rs.getDouble("residual_z"), 2));
				double nabla = Math.sqrt(Math.pow(rs.getDouble("gross_error_y"), 2) + Math.pow(rs.getDouble("gross_error_x"), 2)
						+ Math.pow(rs.getDouble("gross_error_z"), 2));
				double linear = nabla > 0.0 && !Double.isInfinite(nabla) ? nabla : residual;
				double redundancy = rs.getDouble("redundancy");
				if (redundancy < Klein_Method_Default_Values.GROSS_ERROR_MIN_REDUNDANCY)
					linear = Double.NaN;
				Max_Error_Row row = new Max_Error_Row(type, rs.getString("start_name"), rs.getString("end_name"), rs.getDouble("abs_log_post"), residual,
						rs.getInt("id"), true, null, linear, redundancy);
				row.significant = rs.getBoolean("significant");
				double my = rs.getDouble("mdb_y"), mx = rs.getDouble("mdb_x"), mz = rs.getDouble("mdb_z");
				double mdb = Math.sqrt(my * my + mx * mx + mz * mz);
				row.mdbLinear = Double.isFinite(mdb) && mdb > 0.0 ? mdb : Double.NaN;
				rows.add(row);
				count++;
			}
			rows.sort((a, b) -> Double.compare(b.value, a.value));
			return rows.size() > limit ? new ArrayList<Max_Error_Row>(rows.subList(0, limit)) : rows;
		}
		public List<Max_Error_Row> readLargestPointErrors(int limit) throws SQLException {
			List<Max_Error_Row> rows = new ArrayList<Max_Error_Row>();
			if (!hasOpenDatabase() || limit <= 0)
				return rows;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_LARGEST_POINT_ERRORS);
			stmt.setInt(1, PointType.STOCHASTIC_POINT.getId());
			ResultSet rs = stmt.executeQuery();
			while (rows.size() < limit && rs.next()) {
				double shift = Math.sqrt(Math.pow(rs.getDouble("residual_y"), 2) + Math.pow(rs.getDouble("residual_x"), 2)
						+ Math.pow(rs.getDouble("residual_z"), 2));
				rows.add(new Max_Error_Row(null, rs.getString("point_name"), null, rs.getDouble("abs_tprio"), shift));
			}
			return rows;
		}
		private void ensureGrossErrorTableExists() throws SQLException {
			SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_CREATE_GROSS_ERROR_TABLE).execute();
			// Tabelle ohne "linear_error" nachruesten
			ResultSet rs = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_GROSS_ERROR_LINEAR_COLUMN_EXISTS).executeQuery();
			if (rs.next() && rs.getInt("cnt") == 0)
				SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_ADD_GROSS_ERROR_LINEAR_COLUMN).execute();
		}
		private Boolean readObservationEnabled(boolean gnss, int observationId) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(gnss ? SQL_GNSS_OBSERVATION_ENABLED : SQL_OBSERVATION_ENABLED);
			stmt.setInt(1, observationId);
			ResultSet rs = stmt.executeQuery();
			return rs.next() ? rs.getBoolean("enable") : null;
		}
		public List<Max_Error_Row> readGrossErrorEntries() throws SQLException {
			List<Max_Error_Row> rows = new ArrayList<Max_Error_Row>();
			if (!hasOpenDatabase())
				return rows;
			this.ensureGrossErrorTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_READ_GROSS_ERRORS);
			ResultSet rs = stmt.executeQuery();
			List<Max_Error_Row> stale = new ArrayList<Max_Error_Row>();
			while (rs.next()) {
				boolean gnss = "G".equals(rs.getString("kind"));
				Max_Error_Row row = new Max_Error_Row(ObservationType.getEnumByValue(rs.getInt("type")), rs.getString("start_point_name"),
						rs.getString("end_point_name"), rs.getDouble("log_post"), rs.getDouble("residual"), rs.getInt("observation_id"), gnss,
						rs.getString("status"), readNullableDouble(rs, "linear_error"));
				rows.add(row);
			}
			for (Max_Error_Row row : rows) {
				Boolean enabled = this.readObservationEnabled(row.gnss, row.observationId);
				if (enabled == null || (GROSS_ERROR_EXCLUDED.equals(row.status) && enabled))
					stale.add(row);
			}
			for (Max_Error_Row row : stale)
				this.deleteGrossErrorEntry(row);
			rows.removeAll(stale);
			return rows;
		}
		public void storeGrossErrorEntry(Max_Error_Row row, String status) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureGrossErrorTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_MERGE_GROSS_ERROR);
			stmt.setString(1, row.gnss ? "G" : "O");
			stmt.setInt(2, row.observationId);
			stmt.setString(3, status);
			stmt.setInt(4, row.observationType == null ? 0 : row.observationType.getId());
			stmt.setString(5, row.startPointName == null ? "" : row.startPointName);
			stmt.setString(6, row.endPointName == null ? "" : row.endPointName);
			stmt.setDouble(7, row.value);
			stmt.setDouble(8, row.residual);
			if (Double.isNaN(row.linearError))
				stmt.setNull(9, java.sql.Types.DOUBLE);
			else
				stmt.setDouble(9, row.linearError);
			stmt.execute();
		}
		public void deleteGrossErrorEntry(Max_Error_Row row) throws SQLException {
			if (!hasOpenDatabase())
				return;
			this.ensureGrossErrorTableExists();
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DELETE_GROSS_ERROR);
			stmt.setString(1, row.gnss ? "G" : "O");
			stmt.setInt(2, row.observationId);
			stmt.execute();
		}
		public void setObservationEnabled(Max_Error_Row row, boolean enabled) throws SQLException {
			if (!hasOpenDatabase() || row.observationId < 0)
				return;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(row.gnss ? SQL_SET_GNSS_OBSERVATION_ENABLED : SQL_SET_OBSERVATION_ENABLED);
			stmt.setBoolean(1, enabled);
			stmt.setInt(2, row.observationId);
			stmt.execute();
		}	}

	// ============================================================================================================
	/** Debug-Ausgaben auf der Konsole. */
	private final class Debug_Part {

		// DEBUG (temporaer): druckt die ECHTEN Spaltennamen+Typen einer konkreten Tabelle - hier fuer die real
		// bestaetigten Tabellen "GNSSObservationApriori"/"GNSSObservationAposteriori", als Grundlage fuer die
		// SQL-Umleitung, statt Spaltennamen zu erraten.
		private void debugPrintColumnsForTable(String tableName) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_COLUMNS_FOR_TABLE);
			stmt.setString(1, tableName);
			ResultSet rs = stmt.executeQuery();
			StringBuilder columns = new StringBuilder();
			int count = 0;
			while (rs.next()) {
				if (columns.length() > 0)
					columns.append(", ");
				columns.append(rs.getString("COLUMN_NAME")).append(" (").append(rs.getString("TYPE_NAME")).append(")");
				count++;
			}
			System.out.println("[Klein_Method DEBUG V0.267] Spalten von \"" + tableName + "\" (" + count + " St\u00FCck): [" + columns + "]");
		}
		// DEBUG: druckt EINMAL pro read()-Aufruf die komplette, ECHTE Tabellenliste der aktuell geoeffneten Datenbank -
		// ersetzt jedes Raten von Tabellennamen (GNSSBaselineApriori, Coordinate-Aposteriori, etc.) durch einen
		// belastbaren Fakt aus dem Systemkatalog.
		private void debugPrintAllTableNames() throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_ALL_TABLE_NAMES);
			ResultSet rs = stmt.executeQuery();
			StringBuilder names = new StringBuilder();
			int count = 0;
			while (rs.next()) {
				if (names.length() > 0)
					names.append(", ");
				names.append(rs.getString("TABLE_NAME"));
				count++;
			}
			System.out.println("[Klein_Method DEBUG V0.266] Echte Tabellen in der aktuell geoeffneten Datenbank ("
					+ count + " St\u00FCck): [" + names + "]");
		}
		// DEBUG: druckt die ObservationGroup.id(s), die SQL_GROUP_IDS fuer diesen Typ liefert (= die "linke Seite" des
		// JOINs, dieselbe Abfrage wie in readGroupIds()).
		private void debugPrintGroupIdsForType(ObservationType observationType) throws SQLException {
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_GROUP_IDS_FOR_TYPE);
			stmt.setInt(1, observationType.getId());
			ResultSet rs = stmt.executeQuery();
			StringBuilder ids = new StringBuilder();
			while (rs.next()) {
				if (ids.length() > 0)
					ids.append(", ");
				ids.append(rs.getInt("id"));
			}
			System.out.println("[Klein_Method DEBUG V0.265] ObservationGroup.id(s) fuer " + observationType.name()
					+ " (id=" + observationType.getId() + "), enable=TRUE: [" + ids + "]");
		}
		// DEBUG: druckt EINMAL pro read()-Aufruf die komplette Menge der group_id-Werte, die tatsaechlich in
		// "ObservationApriori" vorkommen (= die "rechte Seite" des JOINs, project-weit, unabhaengig vom Beobachtungstyp),
		// plus die Gesamtzeilenzahl der Tabelle. Abgleich mit debugPrintGroupIdsForType() zeigt sofort, ob die gesuchte
		// ObservationGroup.id darin ueberhaupt vorkommt.
		private void debugPrintAprioriGroupIdUniverse() throws SQLException {
			PreparedStatement totalStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_APRIORI_TOTAL_COUNT);
			ResultSet totalRs = totalStmt.executeQuery();
			int totalRows = totalRs.next() ? totalRs.getInt("cnt") : 0;
			PreparedStatement stmt = SQLManager.getInstance().getDataBase().getPreparedStatement(SQL_DEBUG_DISTINCT_GROUP_IDS_IN_APRIORI);
			ResultSet rs = stmt.executeQuery();
			StringBuilder ids = new StringBuilder();
			int distinctCount = 0;
			while (rs.next()) {
				if (ids.length() > 0)
					ids.append(", ");
				ids.append(rs.getInt("group_id"));
				distinctCount++;
			}
			System.out.println("[Klein_Method DEBUG V0.265] \"ObservationApriori\" GESAMT: " + totalRows
					+ " Zeile(n), " + distinctCount + " unterschiedliche group_id-Werte: [" + ids + "]");
		}
		// DEBUG: gibt PRO Beobachtungstyp einen vollstaendigen Trichter aus - von "Gruppe existiert ueberhaupt" bis
		// "Aposteriori-Zeile via ID-JOIN vorhanden, UNGEFILTERT" - damit auf einen Blick erkennbar ist, an WELCHER Stufe
		// die Zeilenzahl auf 0 faellt, statt das nur zu vermuten.
		private void debugFunnel(ObservationType observationType) throws SQLException {
			int groupAny       = this.debugCountRows(SQL_DEBUG_GROUP_COUNT_ANY, observationType.getId());
			int groupEnabled   = this.debugCountRows(SQL_DEBUG_GROUP_COUNT_ENABLED, observationType.getId());
			int aprioriAny     = this.debugCountRows(SQL_DEBUG_APRIORI_COUNT_ANY, observationType.getId());
			int aprioriEnabled = this.debugCountRows(SQL_DEBUG_APRIORI_COUNT_ENABLED, observationType.getId());
			int aposterioriAny = this.debugCountRows(SQL_DEBUG_APOSTERIORI_JOIN_COUNT_ANY, observationType.getId());
			System.out.println("[Klein_Method DEBUG V0.263] Funnel " + observationType.name()
					+ " (id=" + observationType.getId() + "): "
					+ "ObservationGroup(type)=" + groupAny
					+ " -> ObservationGroup(type,enable=TRUE)=" + groupEnabled
					+ " -> ObservationApriori(join,egal enable)=" + aprioriAny
					+ " -> ObservationApriori(join,beide enable=TRUE)=" + aprioriEnabled
					+ " -> ObservationAposteriori(id-JOIN,UNGEFILTERT)=" + aposterioriAny);
		}
		// DEBUG (temporaer): fuehrt eine COUNT(*)-Abfrage aus und gibt das Ergebnis zurueck (0 bei keiner Treffer-Zeile).
		private int debugCountRows(String countSql, int observationTypeId) throws SQLException {
			PreparedStatement countStmt = SQLManager.getInstance().getDataBase().getPreparedStatement(countSql);
			countStmt.setInt(1, observationTypeId);
			ResultSet countRs = countStmt.executeQuery();
			return countRs.next() ? countRs.getInt("cnt") : 0;
		}	}
}
// V1.00_RC3_Klein_Method_Database_Handler.java
