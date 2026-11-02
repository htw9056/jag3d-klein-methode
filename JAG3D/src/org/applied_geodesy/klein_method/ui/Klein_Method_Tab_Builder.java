// V1.00_RC3_Klein_Method_Tab_Builder.java
package org.applied_geodesy.klein_method.ui;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import org.applied_geodesy.klein_method.Klein_Method;
import org.applied_geodesy.klein_method.logging.Klein_Method_Protocol;
import org.applied_geodesy.klein_method.logic.Klein_Method_Functions;
import static org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_I18N.tr;
import org.applied_geodesy.klein_method.logic.Klein_Method_Guardian;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Adjustment_Step;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Coordinate_Export_Format;
// verschachtelter Typ-Import, seit das Enum direkt in Klein_Method_Parameter eingebettet ist.
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter.Klein_Method_Damping_Formula_Type;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Data_Status;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Default_Values;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Device_Class;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Distribution_Type;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Evaluation_Method;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Format;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Parameter;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Process_Phase;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Protocol_Level;
import org.applied_geodesy.klein_method.parameter.Klein_Method_Threshold_Type;
import org.applied_geodesy.util.unit.LengthUnit;
import org.applied_geodesy.util.unit.ScaleUnit;

/**
 * Baut den Inhalt des Tabs "Klein_Method", gegliedert in drei klappbare
 * Gruppen (TitledPane), damit spaeter hinzukommende Felder/Buttons einen
 * klaren Platz haben und der Tab nicht unuebersichtlich wird:
 *
 *  - "Grenzwerte"          -> Eingabefelder (dynamisch aus Klein_Method_Threshold_Type)
 *  - "Bewertungsmethode"   -> Toggle logPost/EP
 *  - "Steuerung"           -> Status + Start/Stop (logPost) bzw. Einzelschritt (EP)
 *
 * Das Ganze steckt in einer ScrollPane, damit weiteres Wachstum (mehr
 * Grenzwerte, mehr Buttons) den Tab nicht sprengt.
 */
public class Klein_Method_Tab_Builder {

	/** verzoegertes automatisches Anwenden nach Aenderung der Einstellung "Wiederholte Messungen" (FX-Thread). */
	private final javafx.animation.PauseTransition repetitionApplyDelay = createRepetitionApplyDelay();

	private javafx.animation.PauseTransition createRepetitionApplyDelay() {
		javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
		delay.setOnFinished(event -> this.kleinMethod.offerRepetitionsAfterSettingChange());
		return delay;
	}
	// Teilklassen (Gliederung, siehe Ende der Klasse)
	private final Options_Part optionsPart = new Options_Part();
	private final View_Part viewPart = new View_Part();
	private final Diagnostic_Part diagnosticPart = new Diagnostic_Part();
	private final Status_Part statusPart = new Status_Part();
	private final Control_Part controlPart = new Control_Part();
	private final Threshold_Part thresholdPart = new Threshold_Part();
	private final Approximation_Part approximationPart = new Approximation_Part();
	private final Swarm_Part swarmPart = new Swarm_Part();

	private BorderPane pane = null;
	private Klein_Method kleinMethod;

	private Button startButton;
	private Button stopButton;
	private Button singleStepButton;
	private Button resetIterationButton;
	private Button resetCeilingLockButton;
	// "Grobe Fehler bereinigen" und Grenze grober Fehler
	private Button grossErrorCleanupButton;
	private TextField grossErrorLinearLimitField;
	// Schwarmwissen
	private javafx.scene.control.TextArea swarmOverviewArea;
	private Button swarmShareButton;
	private HBox swarmFixedPointClassRow;
	private ComboBox<Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class> swarmFixedPointClassBox;
	private boolean ignoreSwarmClassEvents = false;
	private Button exportCoordinatesButton;
	private ComboBox<Klein_Method_Coordinate_Export_Format> coordinateExportFormatComboBox;
	// Zielsystem des Koordinaten-Exports (nur eindeutige Umformungen)
	private ComboBox<Klein_Method_Functions.Klein_Method_Coordinate_Export.Option> coordinateExportSystemComboBox;
	private boolean coordinateExportSystemUpdating = false;
	private Button gridImportButton;
	private Button quasigeoidImportButton;
	private Button quasigeoidManageButton;
	/** Hoehe beim Koordinaten-Export: 0 = ellipsoidisch, 1 = Normalhoehe (Quasigeoid), 2 = wie im Zielsystem (keine Auswahl). */
	private ComboBox<Integer> exportHeightComboBox;
	private boolean exportHeightUpdating = false;
	private org.applied_geodesy.klein_method.Klein_Method.Export_Plan lastExportPlan = null;
	private Button pointCandidateReportButton;
	private Button promotePointsButton;
	private ComboBox<Klein_Method_Protocol_Level> protocolLevelComboBox;
	private Runnable groupUncertaintyFieldsRefresher;
	private HBox turboRow;
	// Live-Werte getrennt fuer freie/fixe AGL (logPost/EP) und dynamische AGL, dazu die Zeilen, die nur fuer die
	// freie/fixe AGL gelten (in DYNAMIC ausgeblendet)
	private HBox liveValuesRow;
	// Elemente, die nur die freie/fixe AGL verwendet - in der dynamischen AGL ausgeblendet
	private final java.util.List<Node> freeFixedOnlyNodes = new java.util.ArrayList<Node>();
	/** nur in der Expertenansicht sichtbar (Einsteigeransicht: Start, Ampel, Empfehlung, Nachweis). */
	private final java.util.List<Node> expertOnlyNodes = new java.util.ArrayList<Node>();
	/** Elemente nur in der freien AGL (Naeherungskoordinaten). */
	private final java.util.List<Node> freeOnlyNodes = new java.util.ArrayList<Node>();
	/** Elemente nur fuer die dynamische und fixe AGL (z. B. "Hoehe nur aus Hoehenfestpunkten"). */
	private final java.util.List<Node> dynamicFixedOnlyNodes = new java.util.ArrayList<Node>();
	private CheckBox heightFrom1DCheckBox;
	private Label workflowStatusLabel;

	/** Ampel des Ablauf-Assistenten (FX). */
	public void updateWorkflowStatus(int red, int yellow) {
		if (this.workflowStatusLabel == null)
			return;
		this.workflowStatusLabel.setText(red > 0 ? String.format(tr("Ablauf: %d rot, %d gelb - Start gesperrt"), red, yellow)
				: yellow > 0 ? String.format(tr("Ablauf: %d gelb"), yellow) : tr("Ablauf: alles grün"));
		this.workflowStatusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (red > 0 ? "#E53935" : yellow > 0 ? "#F9A825" : "#43A047") + ";");
	}
	private boolean ignoreHeightFrom1DEvents = false;
	private Label simpleViewHintLabel;
	private volatile Klein_Method_Adjustment_Step visibleStep = Klein_Method_Adjustment_Step.UNDEFINED;
	private HBox dynamicLiveValuesRow;
	private Label dynamicLiveLabel;
	private HBox dampingFormulaRowRef;
	private HBox elementsPerIterationRowRef;
	private HBox logPostEtappeMinRowRef;
	// Debug-Fenster-Schalter und VKS-Band-Felder (in DYNAMIC fest 0,996/1,004 und nicht editierbar).
	// Sprungwert-Felder entfallen (feste Werte).
	private Label vksBandMinLabel;
	private Label vksBandMaxLabel;
	private TextField vksBandMinField;
	private TextField vksBandMaxField;
	private boolean vksBandDynamicMode = false;
	private HBox distributionRow;
	private ToggleButton distributionToggleButton;
	// merkt sich den zuletzt tatsaechlich UEBERNOMMENEN Wert, um die Hinweis-Messagebox nur bei einer ECHTEN
	// Aenderung zu zeigen (nicht bei jedem Fokusverlust ohne Bearbeitung).
	private double lastAppliedFunnelFactor;
	private Label coordinateSystemLabel;
	private ToggleButton guardianToggleButton;
	private ToggleButton liveModeToggleButton;
	private Label liveLogPostLabel;
	private Label liveEpLabel;
	private Label dataStatusLabel;
	private Label adjustmentStepLabel;
	private Label nextStepHintLabel;
	private Label status;
	// Ladekreis fuer Hintergrundarbeit (Reiterkopf und Statuszeile), siehe Busy_Indicator
	private Busy_Indicator busyIndicator;
	// eine Schriftgroesse, schwarze Schrift, EINE Farbe je Bedeutung, einheitliche Breite der Wertefelder.
	private static final double UI_FONT_SIZE = 12.0;
	private static final String UI_GREEN = "#2ECC71";
	private static final String UI_YELLOW = "#F1C40F";
	/** reine Anzeigefelder (nicht editierbar): grau hinterlegt, damit sie nicht wie Eingabefelder aussehen. */
	private static final String UI_READ_ONLY_FIELD = "-fx-text-fill: black; -fx-control-inner-background: #e6e6e6; -fx-background-color: #c8c8c8, #e6e6e6; "
			+ "-fx-background-insets: 0, 1; -fx-background-radius: 3, 2;";
	private static final String UI_RED = "#E74C3C";
	private static final String UI_GREY = "#BDC3C7";
	private static final double UI_VALUE_FIELD_WIDTH = 90.0;
	private static final String UI_WIDE_FIELD = "klein.wideField";
	/** Anzeige einer Auswahlliste uebersetzt (tr des Anzeigetextes), Werte bleiben unveraendert. */
	private static <T> StringConverter<T> translatingConverter() {
		return new StringConverter<T>() {
			@Override
			public String toString(T value) {
				return value == null ? "" : tr(value.toString());
			}
			@Override
			public T fromString(String string) {
				return null; // nicht editierbar, nur Auswahl
			}
		};
	}

	/** Tooltip eines Grenzwertes in der Oberflaechensprache (Aufbau wie Klein_Method_Threshold_Type.getTooltip()). */
	private static String thresholdTooltip(Klein_Method_Threshold_Type type) {
		String unit = type.getUnit().isEmpty() ? "" : " [" + type.getUnit() + "]";
		String unitValue = type.getUnit().isEmpty() ? "" : " " + type.getUnit();
		String text = String.format(tr("Grenzwert f\u00FCr %s%s.\nMindestwert: %s%s (wird beim Editieren automatisch erzwungen)."),
				tr(type.getDisplayName()), unit, String.valueOf(type.getMinimumAllowedValue()), unitValue);
		if (type.isEvaluateAsAbsoluteValue())
			text += "\n" + tr("Wird als Betrag ausgewertet - negative Werte sind zul\u00E4ssig.");
		return text;
	}

	/** Eigenname einer Sprache (in ihrer eigenen Sprache). */
	private static String endonym(String language) {
		String[] languages = Klein_Method_Functions.Klein_Method_I18N.LANGUAGES;
		for (int i = 0; i < languages.length; i++)
			if (languages[i].equals(language))
				return Klein_Method_Functions.Klein_Method_I18N.ENDONYMS[i];
		return language;
	}

	private static final double UI_COMBO_WIDTH = 250.0;
	private static final String UI_BADGE_PADDING = " -fx-padding: 1 5 1 5;";
	// einheitliche Status-Anzeigen (Waechter, Oszillations-Status, Iterationsgrenze-Status)
	private static final double UI_STATUS_WIDTH = 160.0;
	private static final double UI_STATUS_HEIGHT = 26.0;
	private static final String UI_STATUS_SHAPE = " -fx-background-insets: 0; -fx-background-radius: 3; -fx-padding: 0; -fx-font-weight: bold;";
	// Sperre der Oberflaeche waehrend der Automatik (siehe setRunLock())
	private VBox rightColumnForRunLock;
	private final java.util.List<Node> runLockContainers = new java.util.ArrayList<Node>();
	private final java.util.List<Node> runLockDisabledNodes = new java.util.ArrayList<Node>();
	private boolean runLockActive = false;
	private static final double STATUS_LABEL_MAX_WIDTH = 320.0;
	private Label iterationCountLabel;
	// EXAKT derselbe Control-Typ/dieselbe Geometrie wie oscillationStatusValueLabel (Button, 160x26, BOLD 12,
	// zentriert, mouseTransparent) - zeigt GRUEN "Innerhalb Grenze", solange iterationCount < iterationThreshold, ROT
	// "Grenze erreicht" sobald die Grenze tatsaechlich erreicht ist (dieselbe Bedingung wie "iterationLimitExceeded"
	// in Klein_Method_Iteration_Task).
	private Button iterationLimitStatusValueLabel;

	// ein editierbares Textfeld je Beobachtungstyp fuer den Gruppen-Faktor V - siehe createGroupDampingGridSection()
	// unten.
	private Map<Klein_Method_Threshold_Type, TextField> groupScaleFactorFields = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private ProgressBar progressBar;
	private Label progressBarOverlayLabel;

	private HBox automaticButtonsBox;
	private HBox manualButtonsBox;
	private RadioButton logPostRadio;
	private RadioButton epRadio;
	private Label epLevelingNote;
	private ToggleGroup evaluationMethodGroup;

	private Map<Klein_Method_Threshold_Type, Label> diagnosticValueLabels = new EnumMap<>(Klein_Method_Threshold_Type.class);
	/** "Ausgelesene Werte": aktuelle Gruppen-Messunsicherheit je logPost-Typ {sigma_a, sigma_b, sigma_c} (Spanne min - max). */
	private Map<Klein_Method_Threshold_Type, Label[]> groupSigmaLabels = new EnumMap<>(Klein_Method_Threshold_Type.class);

	// aus Sektion "Ausgelesene Werte" ENTFERNT und in Sektion "Steuerung" (createOptionsSection()) neu aufgebaut,
	// exakt zwischen "Elemente / Iter.:" und "logPost-EtappeMin:" - siehe dort. FUNDSTELLEN-HINWEIS: der Auftrag
	// verlangt, "das Label UND das Textfeld" zu loeschen/neu zu platzieren, so als bestuende bereits ein
	// Label+TextField-Paar (wie bei "Elemente / Iter.:"/"logPost-EtappeMin:").
	private TextField deactivatedGnssBaselineCountField;

	// neue Zeile "Erzwungener HSQLDB-Boden:", direkt UNTER "Deaktivierte BL: X / Y" in derselben Sektion "Ausgelesene
	// Werte" - siehe createDiagnosticSection()/ updateHardClampStatusLabel() unten.
	private Label hardClampStatusValueLabel;

	// neue Zeile "Reelle EP-Verteilungsrate:", direkt UNTER "Erzwungener HSQLDB-Boden:" in derselben Sektion
	// "Ausgelesene Werte" - siehe createDiagnosticSection()/ updateGnssPercentageRateLabel() unten.
	private Label gnssPercentageRateValueLabel;

	// loeste die Felder "logPost-Unterg.:"/"logPost-Oberg.:" ab durch zwei neue Felder
	// "logPost-PassMax:"/"logPost-EtappeMin:", direkt UNTER "Live-Modus:" in der Sektion "Steuerung" (Optionen).
	// "logPost-PassMax:" (Feld UND Label) hier RESTLOS entfernt - kein verwaister UI-Rest. Das verbleibende
	// "logPost-EtappeMin:" ruekt fluchtsymmetrisch nach oben, direkt unter "Live-Modus:" - siehe
	// createOptionsSection()/ updateLogPostDynamicBandFields() unten.
	private TextField logPostEtappeMinField;

	// neues Live-Feld "Elemente / Iter.:", exakt vertikal UNTER "Live-Modus:" und OBERHALB von "logPost-EtappeMin:"
	// in der Sektion "Steuerung" (Optionen) - siehe createOptionsSection()/updateElementsPerIterationLabel() unten.
	private TextField elementsPerIterationField;
	// reine Anzeigefelder (nicht editierbar) - siehe updateCascadeStatusFields().
	private TextField cascadePassField;
	private TextField bestMaxLogPostField;
	private Label bestMaxLogPostInfoLabel;
	private Label cascadeTargetInfoLabel;
	private TextField cascadeTargetField;

	// neue ComboBox "Dämpfungs-Formel:", exakt vertikal UNTER "Live-Modus:" und OBERHALB von "Elemente / Iter.:" in
	// der Sektion "Steuerung" (Optionen) - siehe createOptionsSection() oben (Konstruktion) fuer die vollstaendige
	// Herleitung inkl. Fundstellen-Hinweis zur Wirkungsreichweite.
	private ComboBox<Klein_Method_Damping_Formula_Type> dampingFormulaComboBox;
	// zuletzt im Modus "prozentual" gewaehlte Formel - wird beim Zurueckschalten von "normal" auf "prozentual"
	// wiederhergestellt.
	private Klein_Method_Damping_Formula_Type userDampingFormulaChoice;

	// farbige Ampel-Anzeige fuer Klein_Method_Oscillation_Status, direkt unter der logPost/EP-Zeile in Sektion
	// "Ausgelesene Werte" - siehe createDiagnosticSection()/applyOscillationStatus() unten.
	// Feldtyp von Label auf Button umgestellt - EXAKT derselbe Control-Typ wie guardianToggleButton (ein ToggleButton
	// ist selbst ein Button), also dasselbe Standard-Skin (Modena), dieselbe Rahmenbreite/Hoehe/Padding OHNE jede
	// manuelle CSS-Geometrie - siehe applyOscillationStatus() unten, wo (wie beim Waechter-Button) NUR noch
	// -fx-background-color/-fx-text-fill gesetzt werden. Kein eigenes onAction(), setFocusTraversable(false) - rein
	// informativ, kein interaktives Element.
	private Button oscillationStatusValueLabel;

	// siehe applyIntegrityStatus()/computeCoreModuleSignature() unten fuer die fachliche Einordnung, WAS dieser Wert
	// tatsaechlich bedeutet (Live-Fingerabdruck des geladenen Rechenkerns, KEIN Abgleich gegen eine vorab hinterlegte
	// "Soll"-Signatur - siehe Klassenkommentar dort).
	private Button integrityStatusValueLabel;
	private Map<Klein_Method_Threshold_Type, Label> additionalParameterOffsetLabels = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private Map<Klein_Method_Threshold_Type, Label> additionalParameterScaleLabels = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private Map<Klein_Method_Threshold_Type, CheckBox> additionalParameterOffsetCheckBoxes = new EnumMap<>(Klein_Method_Threshold_Type.class);
	private Map<Klein_Method_Threshold_Type, CheckBox> additionalParameterScaleCheckBoxes = new EnumMap<>(Klein_Method_Threshold_Type.class);

	public Klein_Method_Tab_Builder(Klein_Method kleinMethod) {
		this.kleinMethod = kleinMethod;
		this.init();
	}

	private void init() {
		Label title = new Label(tr("Klein'sche Methode"));
		title.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		title.setStyle("-fx-font-weight: bold;");

		Label versionLabel = new Label(Klein_Method_Default_Values.PACKAGE_VERSION + tr(" @Dipl.Ing. (FH) Stefan Klein"));
		versionLabel.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
		versionLabel.setTooltip(new Tooltip(tr("Versionsstand des eingespielten Klein_Method-Pakets (Klein_Method_Default_Values.PACKAGE_VERSION).")));

		// Einsteiger-/Expertenansicht - deutlicher Umschaltknopf direkt hinter der Version
		ToggleButton expertModeButton = new ToggleButton(tr("Expertenmodus"));
		expertModeButton.setSelected(!this.kleinMethod.getParameter().isSimpleView());
		expertModeButton.setTooltip(new Tooltip(tr("Umschalten zwischen einfacher Ansicht und Expertenansicht.\n"
				+ "Gedr\u00FCckt (Expertenmodus): alle Bereiche, Grenzwerte, Optionen und Einzelschritt.\n"
				+ "Nicht gedr\u00FCckt (einfache Ansicht): nur das N\u00F6tigste - Status und Ampel, Start/Stop, grobe Fehler bereinigen, Karte,\n"
				+ "Festpunkt-Empfehlung (Ergebnis und Kn\u00F6pfe) und Kalibrierungsnachweis.\n"
				+ "Alle Einstellungen bleiben wirksam, sie sind nur ausgeblendet. Die Wahl wird gespeichert.")));
		Runnable styleExpertButton = () -> expertModeButton.setStyle(expertModeButton.isSelected()
				? "-fx-font-weight: bold; -fx-base: #1565C0; -fx-text-fill: white;"
				: "-fx-font-weight: bold; -fx-base: #FFB300;");
		styleExpertButton.run();
		expertModeButton.selectedProperty().addListener((obs, oldValue, expert) -> {
			styleExpertButton.run();
			boolean simple = !expert;
			if (simple == this.kleinMethod.getParameter().isSimpleView())
				return;
			this.kleinMethod.getParameter().setSimpleView(simple);
			this.kleinMethod.saveSettings();
			this.kleinMethod.getLogger().log("Klein_Method: Ansicht " + (simple ? "Einfach" : "Experte"));
			this.applyViewMode();
		});
		this.simpleViewHintLabel = new Label(tr("Einfache Ansicht - alle Einstellungen \u00FCber den Knopf \u201EExpertenmodus\u201C oben."));
		this.simpleViewHintLabel.setStyle("-fx-text-fill: #8D6E00; -fx-font-style: italic;");
		this.simpleViewHintLabel.setTooltip(expertModeButton.getTooltip());

		HBox titleRow = new HBox(8, title, versionLabel, expertModeButton);
		titleRow.setAlignment(Pos.BASELINE_LEFT);

		// direkt unter der Namenszeile, gleicher Control-Typ/CSS-Technik wie Waechter- und Ampel-Anzeige (Button, nur
		// -fx-background-color/-fx-text-fill per style gesetzt, kein eigenes onAction(), setFocusTraversable(false)/
		// setMouseTransparent(true) - rein informativ). BEWUSST OHNE die feste 160x26-Geometrie von
		// oscillationStatusValueLabel: ein 64-stelliger Hex-String passt darin nicht verlustfrei hinein - erzwungene
		// Breiten-Symmetrie haette hier den eigentlichen Zweck des Labels (die Signatur LESBAR zu machen) direkt
		// unterlaufen. Farb-/Text-/Fett-Symmetrie bleibt dagegen exakt erhalten (siehe applyIntegrityStatus()).
		Label integrityStatusNameLabel = new Label(tr("Integrität:"));
		this.integrityStatusValueLabel = new Button();
		this.integrityStatusValueLabel.setFocusTraversable(false);
		this.integrityStatusValueLabel.setMouseTransparent(true);
		this.integrityStatusValueLabel.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		this.integrityStatusValueLabel.setTooltip(new Tooltip(tr("SHA-256-Fingerabdruck des aktuell GELADENEN Rechenkerns "
				+ "(Klein_Method_Functions.class), live aus dem Classloader gelesen - GRÜN, wenn lesbar/berechenbar, "
				+ "ROT, wenn das Modul nicht (mehr) ladbar ist (z.B. Eclipse-Workspace nicht neu gebaut, Pfad ins Leere). "
				+ "KEIN Abgleich gegen eine vorab hinterlegte Referenz-Signatur - siehe applyIntegrityStatus() im Quellcode "
				+ "fuer die fachliche Begruendung.")));
		this.applyIntegrityStatus();
		HBox integrityStatusRow = new HBox(10, integrityStatusNameLabel, this.integrityStatusValueLabel);

		this.dataStatusLabel = new Label(tr("Messdaten: wird gepr\u00FCft ..."));
		this.dataStatusLabel.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		this.dataStatusLabel.setPadding(new Insets(4, 8, 4, 8));
		this.dataStatusLabel.setTooltip(new Tooltip(tr("Zeigt den aktuellen Optimierungsstatus der Messdaten. Wird bei jedem \u00D6ffnen dieses "
				+ "Tabs sowie nach jeder Iteration/jedem Einzelschritt frisch aus der Datenbank ermittelt.\n"
				+ "Gr\u00FCn = alle Beobachtungstypen unter ihrem Grenzwert. Gelb = mindestens ein Typ noch dr\u00FCber. "
				+ "Rot = Sperre aktiv oder Lesefehler.")));

		this.adjustmentStepLabel = new Label(tr("Ausgleichungsschritt: wird ermittelt ..."));
		this.adjustmentStepLabel.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		this.adjustmentStepLabel.setStyle("-fx-font-weight: bold;");
		this.adjustmentStepLabel.setPadding(new Insets(4, 8, 4, 8));
		this.adjustmentStepLabel.setTooltip(new Tooltip(tr("Zeigt, welcher der 3 Ausgleichungsschritte anhand der im Projektbaum aktiven "
				+ "Festpunkt-Kategorien gerade erkannt wird:\nfreie Ausgleichung (Datumsgebende Neupunkte), "
				+ "dynamische Ausgleichung/Festpunktanalyse (Bewegliche Anschlusspunkte), fixe Ausgleichung "
				+ "(Varianzfreie Festpunkte). Wird bei jedem \u00D6ffnen dieses Tabs neu ermittelt.")));

		HBox statusRow = new HBox(15, this.dataStatusLabel, this.adjustmentStepLabel);

		this.nextStepHintLabel = new Label("");
		this.nextStepHintLabel.setWrapText(true);
		this.nextStepHintLabel.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
		this.nextStepHintLabel.setStyle("-fx-text-fill: black; -fx-background-color: " + UI_GREEN + ";" + UI_BADGE_PADDING);
		this.nextStepHintLabel.setManaged(false); // nimmt keinen Platz ein, solange kein Hinweis ansteht
		this.nextStepHintLabel.setVisible(false);
		this.nextStepHintLabel.setTooltip(new Tooltip(tr("Hinweis, was als N\u00E4chstes zu tun ist - erscheint, sobald der aktuelle "
				+ "Ausgleichungsschritt erfolgreich abgeschlossen (Messdaten-Ampel gr\u00FCn) ist.")));

		TitledPane thresholdPane = new TitledPane(tr("Grenzwerte"), this.createThresholdSection());
		thresholdPane.setCollapsible(true);
		thresholdPane.setExpanded(true);

		// "Grenzwerte Gruppenunsicherheit" und "Zusatzparameter Strecken" zu EINER Sektion zusammengefasst,
		// zusaetzlich das gemeinsame logPost-Feld (umgezogen aus der alten "Grenzwerte"- Sektion, siehe
		// createSharedLogPostThresholdRow()).
		HBox sharedLogPostRow = this.createSharedLogPostThresholdRow();
		Separator sharedLogPostSeparator = new Separator(Orientation.HORIZONTAL);
		this.markFreeFixedOnly(sharedLogPostRow, sharedLogPostSeparator);
		VBox combinedGroupSection = new VBox(15,
				sharedLogPostRow,
				sharedLogPostSeparator,
				this.createGroupUncertaintySection(),
				new Separator(Orientation.HORIZONTAL),
				this.createAdditionalParameterSection());
		TitledPane groupUncertaintyPane = new TitledPane(tr("Grenzwerte Gruppenunsicherheit & Zusatzparameter"), combinedGroupSection);
		groupUncertaintyPane.setCollapsible(true);
		groupUncertaintyPane.setExpanded(false); // eingeklappt, da eher selten gebraucht - spart Platz

		TitledPane diagnosticPane = new TitledPane(tr("Ausgelesene Werte"), this.createDiagnosticSection());
		diagnosticPane.setCollapsible(true);
		diagnosticPane.setExpanded(true);

		// Sektion 4, direkt unter "Ausgelesene Werte".
		TitledPane groupDampingPane = new TitledPane(tr("Gruppen-D\u00E4mpfungs-Gitter"), this.createGroupDampingGridSection());
		groupDampingPane.setCollapsible(true);
		groupDampingPane.setExpanded(false); // eingeklappt, da Standard V=1.00 meist unveraendert bleibt
		this.markFreeFixedOnly(groupDampingPane);

		Label optionsHeader = new Label(tr("Optionen"));
		optionsHeader.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		optionsHeader.setStyle("-fx-font-weight: bold;");
		Label evaluationMethodHeader = new Label(tr("Bewertungsmethode"));
		evaluationMethodHeader.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		evaluationMethodHeader.setStyle("-fx-font-weight: bold;");
		Label iterationHeader = new Label(tr("Iterationen"));
		iterationHeader.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
		iterationHeader.setStyle("-fx-font-weight: bold;");

		Separator evaluationMethodSeparator = new Separator(Orientation.HORIZONTAL);
		HBox evaluationMethodToggle = this.createEvaluationMethodToggle();
		this.markFreeFixedOnly(evaluationMethodSeparator, evaluationMethodHeader, evaluationMethodToggle);
		VBox rightColumn = this.rightColumnForRunLock = new VBox(12,
				optionsHeader, this.createOptionsSection(),
				evaluationMethodSeparator,
				evaluationMethodHeader, evaluationMethodToggle,
				new Separator(Orientation.HORIZONTAL),
				iterationHeader, this.createIterationSection());
		rightColumn.setPadding(new Insets(0, 0, 0, 10));

		Separator controlSplitSeparator = new Separator(Orientation.VERTICAL);
		VBox leftColumn = new VBox(4, this.createControlSection());
		HBox controlSplit = new HBox(15, leftColumn, controlSplitSeparator, rightColumn);
		this.markExpertOnly(controlSplitSeparator, rightColumn);
		// "Hoehe nur aus Hoehenfestpunkten (1D)" - nur dynamische und fixe AGL
		CheckBox heightFrom1DCheckBox = this.heightFrom1DCheckBox = new CheckBox(tr("Höhe nur aus Höhenfestpunkten (1D)"));
		heightFrom1DCheckBox.setTooltip(new Tooltip(tr("Nur dynamische und fixe AGL. Ein: Die 3D-Fest- bzw. Anschlusspunkte (GNSS, Tachymeter) bestimmen nur "
				+ "noch die Lage, ihre Höhe ist frei (σz = 10 m). Die Höhe kommt ausschließlich aus den Höhenfestpunkten (1D) über das Nivellement.\n"
				+ "Dynamische AGL: die Festpunktanalyse bewertet bei 3D-Punkten nur die Lage. Fixe AGL: die varianzfreien 3D-Festpunkte kommen in "
				+ "eine eigene Gruppe, die in der Lage praktisch fest ist (σ 0,01 mm).\n"
				+ "Voraussetzung: Höhenfestpunkte im selben Höhensystem (NHN vorher mit dem Quasigeoid umrechnen), über Nivellement verbunden, "
				+ "empfohlen mindestens 3; in der fixen AGL mindestens einer varianzfrei.\nAus: alle Werte exakt wie vorher.") + "\n"
				+ tr("Standard: ein - angewendet wird es nur, wenn die Voraussetzungen erfüllt sind; sonst bleibt alles wie vorher (ohne Meldung).")));
		heightFrom1DCheckBox.setSelected(this.kleinMethod.isHeightFrom1DEnabled());
		heightFrom1DCheckBox.selectedProperty().addListener((obs, was, now) -> {
			if (this.ignoreHeightFrom1DEvents || now == null || now == this.kleinMethod.isHeightFrom1DEnabled())
				return;
			heightFrom1DCheckBox.setDisable(true);
			this.kleinMethod.setHeightFrom1DOnDemand(now, result -> {
				this.ignoreHeightFrom1DEvents = true;
				try {
					heightFrom1DCheckBox.setSelected(result);
				} finally {
					this.ignoreHeightFrom1DEvents = false;
					heightFrom1DCheckBox.setDisable(false);
				}
			});
		});
		HBox heightFrom1DRow = new HBox(8, heightFrom1DCheckBox);
		heightFrom1DRow.setPadding(new Insets(6, 0, 0, 0));
		this.dynamicFixedOnlyNodes.add(heightFrom1DRow);
		heightFrom1DRow.setVisible(false);
		heightFrom1DRow.setManaged(false);
		// Ablauf-Assistent: Knopf und Ampel (immer sichtbar)
		Button workflowButton = new Button(tr("Ablauf-Assistent …"));
		workflowButton.setTooltip(new Tooltip(tr("Prüft die 7 Schritte vom Import bis zur Automatik (Import, Koordinatensysteme, Umrechnung, Höhensystem, "
				+ "Näherungskoordinaten, wiederholte Messungen, Freigabe) mit Ampel, Begründung und Aktion; dazu das Zustandsprotokoll des Projekts.\n"
				+ "Rote Schritte sperren den Start nicht - sie werden beim Start im Protokoll vermerkt; bitte vorher hier prüfen.")));
		workflowButton.setOnAction(event -> this.kleinMethod.openWorkflowAssistant());
		this.workflowStatusLabel = new Label(tr("Ablauf: noch nicht geprüft"));
		this.workflowStatusLabel.setTooltip(workflowButton.getTooltip());
		HBox workflowRow = new HBox(10, workflowButton, this.workflowStatusLabel);
		workflowRow.setAlignment(Pos.CENTER_LEFT);
		workflowRow.setPadding(new Insets(6, 0, 0, 0));
		// direkt unter den Knoepfen der linken Spalte - keine Luecke, wenn die rechte Spalte laenger ist
		leftColumn.getChildren().addAll(heightFrom1DRow, workflowRow);
		TitledPane controlPane = new TitledPane(tr("Steuerung"), controlSplit);
		controlPane.setCollapsible(true);
		controlPane.setExpanded(true);

		// alle Bereiche mit Eingaben - Inhalte der Sektionen, nicht die Ueberschriften, damit sie weiter
		// auf-/zugeklappt werden koennen. "Ausgelesene Werte" bleibt frei (reine Anzeige).
		this.runLockContainers.clear();
		this.runLockContainers.add(rightColumn);
		this.runLockContainers.add(heightFrom1DRow);
		this.runLockContainers.add(thresholdPane.getContent());
		this.runLockContainers.add(groupUncertaintyPane.getContent());
		this.runLockContainers.add(groupDampingPane.getContent());

		// eigener Bereich; auch dynamische AGL (Punktwerte)
		TitledPane swarmPane = new TitledPane(tr("Schwarmwissen"), this.createSwarmSection());
		swarmPane.setCollapsible(true);
		swarmPane.setExpanded(false);
		this.runLockContainers.add(swarmPane.getContent());

		// Reihenfolge 1. Steuerung, 2. Ausgelesene Werte (direkt danach), erst dann die beiden Grenzwerte-Sektionen.
		this.markExpertOnly(diagnosticPane, groupDampingPane, thresholdPane, groupUncertaintyPane, swarmPane);
		VBox content = new VBox(10, titleRow, this.simpleViewHintLabel, integrityStatusRow, statusRow, this.nextStepHintLabel, controlPane, diagnosticPane, groupDampingPane, thresholdPane, groupUncertaintyPane, swarmPane);
		content.setPadding(new Insets(20, 20, 20, 20));
		this.applyViewMode();

		// einheitliche Wertefelder; horizontale Bildlaufleiste, sobald das Fenster schmaler als der Inhalt ist.
		this.normalizeValueFields(content);
		content.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
		ScrollPane scrollPane = new ScrollPane(content);
		scrollPane.setFitToWidth(true);
		scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

		this.pane = new BorderPane();
		// Einheitsschrift 12 und schwarze Schrift fuer den gesamten Tab (vererbte Farbvariablen des Standard-Designs
		// "Modena").
		this.pane.setStyle("-fx-font-size: 12px; -fx-text-base-color: black; -fx-text-background-color: black; "
				+ "-fx-text-inner-color: black; -fx-mark-color: black;");
		this.pane.setCenter(scrollPane);
	}

	private VBox createOptionsSection() {
		return this.optionsPart.createOptionsSection();
	}

	public void updateGuardianAppearance() {
		this.viewPart.updateGuardianAppearance();
	}

	public void updateLiveModeFields(double maxLogPost, double maxEp) {
		this.viewPart.updateLiveModeFields(maxLogPost, maxEp);
	}

	/**
	 * Anzeige nach jeder Iteration der Automatik (JavaFX-Thread): Iterationszaehler und "Ausgelesene Werte" inklusive
	 * σa/σb(√d)/σc(d) aktuell - direkt aus dem Lauf angestossen, unabhaengig von der Statusmeldung des Tasks.
	 */
	public void refreshRunDisplay() {
		int currentCount = this.kleinMethod.getParameter().getIterationCount();
		if (this.iterationCountLabel != null)
			this.iterationCountLabel.setText(tr("Aktuelle Iteration: ") + currentCount);
		if (this.progressBarOverlayLabel != null)
			this.progressBarOverlayLabel.setText(tr("Iteration: ") + currentCount);
		this.applyIterationLimitStatus();
		this.updateDiagnosticValueLabels();
	}

	private void applyDynamicLiveText() {
		this.viewPart.applyDynamicLiveText();
	}

	private void markFreeFixedOnly(Node... nodes) {
		this.viewPart.markFreeFixedOnly(nodes);
	}

	private void markExpertOnly(Node... nodes) {
		this.viewPart.markExpertOnly(nodes);
	}

	private void applyViewMode() {
		this.viewPart.applyViewMode();
	}

	private static void setRowShown(HBox row, boolean shown) {
		if (row == null)
			return;
		row.setVisible(shown);
		row.setManaged(shown);
	}

	private void updateProtocolToggleAvailability() {
		this.viewPart.updateProtocolToggleAvailability();
	}

	private VBox createGroupDampingGridSection() {
		return this.optionsPart.createGroupDampingGridSection();
	}

	private VBox createIterationSection() {
		return this.diagnosticPart.createIterationSection();
	}

	private VBox createDiagnosticSection() {
		return this.diagnosticPart.createDiagnosticSection();
	}

	private void applyOscillationStatus(Klein_Method_Parameter.Klein_Method_Oscillation_Status status) {
		this.diagnosticPart.applyOscillationStatus(status);
	}

	private void applyIterationLimitStatus() {
		this.diagnosticPart.applyIterationLimitStatus();
	}

	private void applyIntegrityStatus() {
		this.diagnosticPart.applyIntegrityStatus();
	}

	private String formatDeactivatedGnssBaselineCount(Klein_Method_Parameter parameter) {
		return this.diagnosticPart.formatDeactivatedGnssBaselineCount(parameter);
	}

	/**
	 * Die beiden Streckentypen, fuer die Zusatzparameter angezeigt werden -
	 * als Feld, damit sowohl createAdditionalParameterSection() als auch
	 * refreshAdditionalParameters() dieselbe Liste nutzen.
	 */
	private static final Klein_Method_Threshold_Type[] ADDITIONAL_PARAMETER_TYPES = new Klein_Method_Threshold_Type[] {
			Klein_Method_Threshold_Type.HORIZONTAL_DISTANCE_LOG_POST,
			Klein_Method_Threshold_Type.SLOPE_DISTANCE_LOG_POST
	};

	private VBox createAdditionalParameterSection() {
		return this.diagnosticPart.createAdditionalParameterSection();
	}

	public void refreshAdditionalParameters() throws SQLException {
		this.diagnosticPart.refreshAdditionalParameters();
	}

	private void updateDiagnosticValueLabels() {
		this.diagnosticPart.updateDiagnosticValueLabels();
	}

	private void updateCascadeStatusFields() {
		this.diagnosticPart.updateCascadeStatusFields();
	}

	private void updateLogPostDynamicBandFields() {
		this.diagnosticPart.updateLogPostDynamicBandFields();
	}

	private void updateElementsPerIterationLabel() {
		this.diagnosticPart.updateElementsPerIterationLabel();
	}

	/**
	 * dieser Verdacht ist inzwischen durch selbst widerlegt - Klein_Method_Database_Handler liest
	 * "influence_on_position_x"/"_y"/"_z" fuer GNSS-Basislinien jetzt aktiv (siehe SQL_GNSS_OBSERVATIONS_IN_BAND) und
	 * verwendet sie produktiv als Gewichtungsschluessel im Prozentual-Modus - der Wert wird also sehr wohl befuellt.
	 * Das separate Label ist damit gegenstandslos geworden; die GNSS-EP-Typen zeigen bei fehlendem Wert jetzt
	 * dasselbe einheitliche "k.A." wie alle anderen Typen.
	 */
	private static final java.util.Set<Klein_Method_Threshold_Type> GNSS_EP_TYPES_WITHOUT_MODEL_VALUE =
			java.util.EnumSet.of(
					Klein_Method_Threshold_Type.GNSS_BASELINE_1D_EP,
					Klein_Method_Threshold_Type.GNSS_BASELINE_2D_EP,
					Klein_Method_Threshold_Type.GNSS_BASELINE_3D_EP
			);

	public void refreshCoordinateSystem() {
		this.statusPart.refreshCoordinateSystem();
	}

	public void refreshDataStatus() {
		this.statusPart.refreshDataStatus();
	}

	private void importQuasigeoid() {
		this.statusPart.importQuasigeoid();
	}

	private void manageQuasigeoids() {
		this.statusPart.manageQuasigeoids();
	}

	private void refreshNormalHeightCheckBox() {
		this.statusPart.refreshNormalHeightCheckBox();
	}

	private void importGridFile() {
		this.statusPart.importGridFile();
	}

	public void applyDataStatus(Klein_Method_Data_Status status) {
		this.statusPart.applyDataStatus(status);
	}

	public void applyAdjustmentStep(Klein_Method_Adjustment_Step step) {
		this.statusPart.applyAdjustmentStep(step);
	}

	public void applyDistributionVisibility(boolean visible) {
		this.statusPart.applyDistributionVisibility(visible);
	}

	public void applyDistributionSelection(Klein_Method_Distribution_Type type) {
		this.statusPart.applyDistributionSelection(type);
	}

	private void updateDampingFormulaAvailability() {
		this.statusPart.updateDampingFormulaAvailability();
	}

	public void applyDistributionEnabled(boolean enabled) {
		this.statusPart.applyDistributionEnabled(enabled);
	}

	public void applyNextStepHint(String hintText) {
		this.statusPart.applyNextStepHint(hintText);
	}

	private VBox createControlSection() {
		return this.controlPart.createControlSection();
	}

	private void updateAutomationAvailability() {
		this.controlPart.updateAutomationAvailability();
	}

	private void applyStatusBadgeGeometry(javafx.scene.control.Labeled badge) {
		this.controlPart.applyStatusBadgeGeometry(badge);
	}

	private void alignRowLabels(VBox column) {
		this.controlPart.alignRowLabels(column);
	}

	private void normalizeValueFields(Node node) {
		this.controlPart.normalizeValueFields(node);
	}

	public void refreshAutomationAvailability() {
		this.controlPart.refreshAutomationAvailability();
	}

	/**
	 * Automatik neu starten (FX-Thread), z. B. nach dem Zusammenfassen der GNSS-Sitzungen: Start-Sperre einer fertigen
	 * Automatik aufheben und "Klein_Method starten" ausloesen. false, wenn Start gesperrt bleibt (laufende Automatik,
	 * vermutete grobe Fehler, Obergrenzen-Sperre, Bewertungsmethode EP).
	 */
	public boolean restartAutomatic() {
		if (this.startButton == null || this.kleinMethod.isAutomatikRunning())
			return false;
		if (this.kleinMethod.getParameter().isAutomaticRunCompleted())
			this.kleinMethod.clearAutomaticRunCompleted();
		this.controlPart.refreshAutomationAvailability();
		if (this.startButton.isDisabled())
			return false;
		this.startButton.fire();
		return true;
	}

	private HBox createEvaluationMethodToggle() {
		return this.optionsPart.createEvaluationMethodToggle();
	}

	public void updateEvaluationMethodAvailability() {
		this.optionsPart.updateEvaluationMethodAvailability();
	}

	/**
	 * true, wenn das Projekt Messarten mit EP-Bewertung enthaelt (Richtungen, Strecken, Zenitwinkel, GNSS). Reines
	 * Nivellement: false - dort sind die Redundanzanteile klein und EP ist durch die Netzform hoch. Ohne Projekt oder
	 * bei einem Lesefehler: true (nicht sperren).
	 */
	private boolean hasEpEligibleObservations() {
		try {
			if (!this.kleinMethod.isProjectOpen())
				return true;
			java.util.Map<org.applied_geodesy.adjustment.network.ObservationType, Integer> counts = this.kleinMethod.getDatabaseHandler().readActiveObservationCounts();
			if (counts.isEmpty())
				return true;
			for (java.util.Map.Entry<org.applied_geodesy.adjustment.network.ObservationType, Integer> e : counts.entrySet())
				if (e.getKey() != org.applied_geodesy.adjustment.network.ObservationType.LEVELING && e.getValue() > 0)
					return true;
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	public void onEpSingleStepFinished() {
		this.controlPart.onEpSingleStepFinished();
	}

	private HBox createSharedLogPostThresholdRow() {
		return this.thresholdPart.createSharedLogPostThresholdRow();
	}

	private HBox createThresholdSection() {
		return this.thresholdPart.createThresholdSection();
	}

	private VBox buildApproximationBox() {
		return this.approximationPart.buildApproximationBox();
	}

	private int addQualityField(GridPane grid, int row, String labelText, java.util.function.DoubleSupplier getter, java.util.function.DoubleUnaryOperator setter, int decimals, String tooltip) {
		return this.approximationPart.addQualityField(grid, row, labelText, getter, setter, decimals, tooltip);
	}

	private VBox createSwarmSection() {
		return this.swarmPart.createSwarmSection();
	}

	public void refreshSwarmView() {
		this.swarmPart.refreshSwarmView();
	}

	// --------------- Festpunkt-Empfehlung (nur dynamische Ausgleichung) ----------------

	private VBox recommendationBox;
	private Label recommendationResultLabel;
	private Label maxGapInfoLabel;
	private final Map<String, TextField> recommendationFields = new java.util.LinkedHashMap<String, TextField>();

	private static String formatRecommendationValue(String key, double value) {
		if (Klein_Method.SETTING_FPE_END_COUNT.equals(key))
			return String.valueOf((int) Math.round(value));
		if (Klein_Method.SETTING_FPE_MAX_GAP.equals(key))
			return value > 0.0 ? String.format(java.util.Locale.GERMANY, "%.0f", value) : "";
		return String.format(java.util.Locale.GERMANY, "%.2f", value);
	}

	private static String formatPlain(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', ',');
	}

	private VBox buildRecommendationBox() {
		return this.swarmPart.buildRecommendationBox();
	}

	public void updateRecommendationView() {
		this.swarmPart.updateRecommendationView();
	}

	/** true = die Oberflaeche zeigt gerade die dynamische Ausgleichung. */
	public boolean isDynamicStepVisible() {
		return this.visibleStep == Klein_Method_Adjustment_Step.DYNAMIC;
	}

	/** Statuszeile von aussen setzen. */
	public void setStatusText(String text) {
		if (this.status != null)
			this.status.setText(text);
	}

	private TextField createGrossErrorLinearLimitField(Klein_Method_Parameter parameter) {
		return this.thresholdPart.createGrossErrorLinearLimitField(parameter);
	}

	private void applyDynamicThresholdFields(boolean dynamic) {
		this.thresholdPart.applyDynamicThresholdFields(dynamic);
	}

	private VBox createGroupUncertaintySection() {
		return this.thresholdPart.createGroupUncertaintySection();
	}

	private void showMinimumCorrectedAlert(String fieldLabel, double enteredValue, double correctedValue) {
		this.thresholdPart.showMinimumCorrectedAlert(fieldLabel, enteredValue, correctedValue);
	}

	public Node getPane() {
		return this.pane;
	}

	/**
	 * Fenster "Koordinatenumrechnung" WGS84 (geozentrisch) <-> lokal (ENh). Erreichbar ueber das JAG3D-Menue "Module"
	 * (ohne Projekt, vor dem Tab) und den Knopf im Tab. Modus 1: Dateien vorab umrechnen (3 Modi per Dropdown). Modus
	 * 2: das geoeffnete JAG3D-Projekt umrechnen (auch automatisch beim Tab-Klick angeboten).
	 */
	/**
	 * Koordinaten-/Hoehenrechner (nur ueber das Menue Hilfe): Einzeleingabe und Stapel, ETRS89 UTM mit Zonenkennung /
	 * geographisch / geozentrisch, Hoehe DHHN2016 oder ellipsoidisch ueber das aktive Quasigeoid. Aendert kein Projekt.
	 */
	/**
	 * Liste auffaelliger Beobachtungen (Befunde des statistischen Tests): sortier- und filterbar, je Auswahl
	 * deaktivieren, Messwert korrigieren, Hoehen aendern, abwichten, zuruecknehmen; neu testen; CSV.
	 */
	public static final class Klein_Method_Findings_Window {
		private static Klein_Method_Findings_Window instance;
		private final Klein_Method kleinMethod;
		private final javafx.stage.Stage stage = new javafx.stage.Stage();
		private final javafx.scene.control.TableView<Klein_Method.Finding> table = new javafx.scene.control.TableView<Klein_Method.Finding>();
		private final ComboBox<String> filter = new ComboBox<String>();
		private final Label status = new Label();
		private List<Klein_Method.Finding> all = new ArrayList<Klein_Method.Finding>();

		public static void open(Klein_Method kleinMethod) {
			if (instance == null)
				instance = new Klein_Method_Findings_Window(kleinMethod);
			instance.reload(null);
			instance.stage.show();
			instance.stage.toFront();
		}

		/** Liste oeffnen und gleich die Gruppentests starten (Empfehlung des Berichts bei einer Haeufung an einem Punkt). */
		public static void openWithGroupTests(Klein_Method kleinMethod) {
			open(kleinMethod);
			instance.groupTests();
		}

		private Klein_Method_Findings_Window(Klein_Method kleinMethod) {
			this.kleinMethod = kleinMethod;
			this.stage.setTitle(tr("Klein_Method - Auffällige Beobachtungen"));
			this.table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
			this.table.setPlaceholder(new Label(tr("Keine Befunde - den statistischen Test auf Knopfdruck rechnen („Statistischer Test …“).")));
			this.column(tr("Messart"), f -> f.typeName, 150);
			this.column(tr("Standpunkt"), f -> f.start, 90);
			this.column(tr("Ziel"), f -> f.end, 90);
			this.column(tr("Ampel"), f -> f.significant ? tr("signifikant") : tr("kaum kontrolliert"), 110);
			this.numberColumn(tr("Fehler (cm)"), f -> f.significant || f.group() ? (f.group() ? f.nabla : f.linear) * 100.0 : Double.NaN, "%.1f", 80);
			this.numberColumn(tr("|logPost|"), f -> f.logPost, "%.1f", 70);
			this.numberColumn(tr("Redundanz (%)"), f -> f.redundancy * 100.0, "%.0f", 90);
			this.numberColumn(tr("MDB (cm)"), f -> f.mdb * 100.0, "%.1f", 70);
			this.column(tr("Vermutete Ursache"), f -> f.cause, 280);
			this.column(tr("Status"), f -> f.status() + (f.edits.isEmpty() ? "" : " (" + f.edits + ")"), 170);
			this.table.setTooltip(new Tooltip(tr("Befunde des statistischen Tests nach der Gruppenkalibrierung (Ausgangsgenauigkeiten). Spaltenkopf klicken = sortieren, "
					+ "Mehrfachauswahl mit Strg/Umschalt.")));
			this.filter.getItems().setAll(tr("Signifikante"), tr("Kaum kontrollierte"), tr("Alle"), tr("Gruppentests (gemeinsame Ursache)"));
			this.filter.getSelectionModel().select(0);
			this.filter.setTooltip(new Tooltip(tr("Welche Befunde die Liste zeigt.")));
			this.filter.valueProperty().addListener((o, a, b) -> this.applyFilter());
			Button disable = this.button(tr("Deaktivieren"), tr("Gewählte Beobachtungen deaktivieren (rücknehmbar)."), () -> this.action("disable"));
			Button enable = this.button(tr("Aktivieren"), tr("Gewählte Beobachtungen wieder aktivieren."), () -> this.action("enable"));
			Button correct = this.button(tr("Messwert korrigieren"), tr("Messwert um den geschätzten Fehler korrigieren (nur terrestrisch) - nur, wenn das Feldbuch den Fehler bestätigt."),
					() -> this.action("correct"));
			Button heights = this.button(tr("Höhen ändern …"), tr("Instrumenten- und Zielhöhe der gewählten Beobachtung ändern (z. B. falsche Zielhöhe laut Feldbuch)."), this::heights);
			Button weight = this.button(tr("Abwichten"), tr("Genauigkeit der gewählten Beobachtungen auf den geschätzten Fehler setzen - sie bleiben drin, wirken aber kaum noch."),
					() -> this.action("downweight"));
			Button undo = this.button(tr("Zurücknehmen"), tr("Alle Änderungen an den gewählten Beobachtungen zurücknehmen."), () -> this.action("undo"));
			Button undoAll = this.button(tr("Alles zurücknehmen"), tr("Alle Änderungen aus dieser Liste zurücknehmen."), this::undoAll);
			Button retest = this.button(tr("Neu testen"), tr("Mit den Ausgangsgenauigkeiten neu ausgleichen und die Befunde aktualisieren - zeigt, ob eine Korrektur gewirkt hat. "
					+ "Die Ergebnisse der Automatik sind danach ungültig."), this::retest);
			Button groups = this.button(tr("Gruppentests"), tr("Gruppen auf einen gemeinsamen Fehler testen: Strecken zu einem Ziel (Prismen-/Additionskonstante), "
					+ "Beobachtungen eines Standpunkts (Instrumentenhöhe) und zu einem Ziel (Zielhöhe). Findet auch Fehler, die der Einzeltest nicht sieht. "
					+ "Dauert: zwei Ausgleichungen je Gruppe. Bei Gruppenzeilen korrigiert „Messwert korrigieren“ das gemeinsame Feld aller Beobachtungen der Gruppe."),
					this::groupTests);
			Button simulation = this.button(tr("Fehlersimulation …"), tr("Monte Carlo am eigenen Netz: Fehler bekannter Größe zufällig einbauen, ausgleichen und zählen, "
					+ "wie viele gefunden werden. Jeder Versuch wird exakt zurückgesetzt; danach sind die Genauigkeiten wie vorher."), this::simulation);
			Button csv = this.button(tr("CSV …"), tr("Liste als CSV-Datei speichern (z. B. für Excel)."), this::csv);
			Button close = this.button(tr("Schließen"), tr("Fenster schließen."), this.stage::hide);
			HBox row1 = new HBox(8, new Label(tr("Anzeigen:")), this.filter, disable, enable, correct, heights, weight);
			HBox row2 = new HBox(8, undo, undoAll, retest, groups, simulation, csv, close);
			row1.setAlignment(Pos.CENTER_LEFT);
			row2.setAlignment(Pos.CENTER_LEFT);
			this.status.setWrapText(true);
			Label hint = new Label(tr("Nach Änderungen: „Neu testen“ prüft die Wirkung; danach die Automatik neu starten. Jede Änderung steht im Zustandsprotokoll und ist rücknehmbar."));
			hint.setWrapText(true);
			VBox root = new VBox(8, this.table, row1, row2, this.status, hint);
			VBox.setVgrow(this.table, javafx.scene.layout.Priority.ALWAYS);
			root.setPadding(new javafx.geometry.Insets(10));
			this.stage.setScene(new javafx.scene.Scene(root, 1280, 640));
		}

		private void column(String title, java.util.function.Function<Klein_Method.Finding, String> value, double width) {
			javafx.scene.control.TableColumn<Klein_Method.Finding, String> c = new javafx.scene.control.TableColumn<Klein_Method.Finding, String>(title);
			c.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
			c.setPrefWidth(width);
			this.table.getColumns().add(c);
		}

		private void numberColumn(String title, java.util.function.ToDoubleFunction<Klein_Method.Finding> value, String format, double width) {
			javafx.scene.control.TableColumn<Klein_Method.Finding, Number> c = new javafx.scene.control.TableColumn<Klein_Method.Finding, Number>(title);
			c.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<Number>(value.applyAsDouble(cell.getValue())));
			c.setCellFactory(col -> new javafx.scene.control.TableCell<Klein_Method.Finding, Number>() {
				@Override
				protected void updateItem(Number item, boolean empty) {
					super.updateItem(item, empty);
					this.setText(empty || item == null || Double.isNaN(item.doubleValue()) ? "" : String.format(java.util.Locale.GERMANY, format, item.doubleValue()));
				}
			});
			c.setComparator((a, b) -> Double.compare(a == null || Double.isNaN(a.doubleValue()) ? -1e300 : a.doubleValue(), b == null || Double.isNaN(b.doubleValue()) ? -1e300 : b.doubleValue()));
			c.setPrefWidth(width);
			this.table.getColumns().add(c);
		}

		private Button button(String text, String tooltip, Runnable action) {
			Button b = new Button(text);
			b.setTooltip(new Tooltip(tooltip));
			b.setOnAction(e -> action.run());
			return b;
		}

		private void applyFilter() {
			int mode = Math.max(0, this.filter.getSelectionModel().getSelectedIndex());
			List<Klein_Method.Finding> shown = new ArrayList<Klein_Method.Finding>();
			for (Klein_Method.Finding f : this.all)
				if (mode == 3 ? f.group() : !f.group() && (mode == 2 || (mode == 0) == f.significant || !f.edits.isEmpty() && mode == 0))
					shown.add(f);
			this.table.getItems().setAll(shown);
		}

		/** im Hintergrund lesen (Sperre), dann anzeigen; message = Ergebnistext der letzten Aktion. */
		private void reload(String message) {
			Thread t = Klein_Method.busyThread(() -> {
				List<Klein_Method.Finding> list;
				String error = null;
				try {
					list = this.kleinMethod.readFindings();
				} catch (Exception e) {
					list = new ArrayList<Klein_Method.Finding>();
					error = e.getMessage();
				}
				final List<Klein_Method.Finding> result = list;
				final String err = error;
				Platform.runLater(() -> {
					this.all = result;
					this.applyFilter();
					int sig = 0, open = 0, single = 0, groupSig = 0;
					for (Klein_Method.Finding f : result)
						if (f.group()) {
							if (f.significant)
								groupSig++;
						} else if (++single > 0 && f.significant) {
							sig++;
							if (f.active && f.edits.isEmpty())
								open++;
						}
					String text = String.format(tr("%d signifikante Beobachtungen, davon %d offen; %d kaum kontrolliert."), sig, open, single - sig)
							+ (groupSig > 0 ? " " + String.format(tr("%d Gruppen mit gemeinsamem Fehler."), groupSig) : "");
					this.status.setText((message == null ? "" : message + "\n") + (err == null ? text : tr("Vorgang fehlgeschlagen: ") + err));
				});
			}, "Klein_Method_Findings_Load");
			t.setDaemon(true);
			t.start();
		}

		private List<Klein_Method.Finding> selected() {
			List<Klein_Method.Finding> list = new ArrayList<Klein_Method.Finding>(this.table.getSelectionModel().getSelectedItems());
			if (list.isEmpty())
				this.status.setText(tr("Bitte zuerst eine oder mehrere Zeilen wählen."));
			return list;
		}

		private void background(java.util.concurrent.Callable<String> work) {
			if (this.kleinMethod.isAutomaticTaskRunning()) {
				this.status.setText(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			this.status.setText(tr("Bitte warten …"));
			Thread t = Klein_Method.busyThread(() -> {
				String text;
				try {
					text = work.call();
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				}
				this.reload(text);
			}, "Klein_Method_Findings_Action");
			t.setDaemon(true);
			t.start();
		}

		private void action(String action) {
			List<Klein_Method.Finding> rows = this.selected();
			if (!rows.isEmpty())
				this.background(() -> this.kleinMethod.applyFindingAction(rows, action));
		}

		private void heights() {
			List<Klein_Method.Finding> rows = this.selected();
			if (rows.isEmpty())
				return;
			Klein_Method.Finding f = rows.get(0);
			if (f.gnss()) {
				this.status.setText(tr("Bei GNSS-Basislinien nicht möglich."));
				return;
			}
			Dialog<ButtonType> dialog = new Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Höhen ändern"));
			dialog.setHeaderText(f.typeName + " " + f.start + " – " + f.end + (f.cause.isEmpty() ? "" : "\n" + f.cause));
			TextField ih = new TextField(), th = new TextField();
			ih.setPromptText(tr("unverändert"));
			th.setPromptText(tr("unverändert"));
			ih.setTooltip(new Tooltip(tr("Neue Instrumentenhöhe in m (leer = unverändert).")));
			th.setTooltip(new Tooltip(tr("Neue Zielhöhe in m (leer = unverändert).")));
			GridPane g = new GridPane();
			g.setHgap(8);
			g.setVgap(6);
			g.addRow(0, new Label(tr("Instrumentenhöhe (m):")), ih);
			g.addRow(1, new Label(tr("Zielhöhe (m):")), th);
			dialog.getDialogPane().setContent(g);
			dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
			if (!dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
				return;
			double i, t;
			try {
				i = ih.getText().trim().isEmpty() ? Double.NaN : Double.parseDouble(ih.getText().trim().replace(',', '.'));
				t = th.getText().trim().isEmpty() ? Double.NaN : Double.parseDouble(th.getText().trim().replace(',', '.'));
			} catch (NumberFormatException e) {
				this.status.setText(tr("Höhe ist keine Zahl."));
				return;
			}
			this.background(() -> this.kleinMethod.setFindingHeights(f, i, t));
		}

		private void undoAll() {
			Alert a = new Alert(AlertType.CONFIRMATION, tr("Alle Änderungen aus der Liste auffälliger Beobachtungen zurücknehmen?"), ButtonType.OK, ButtonType.CANCEL);
			a.setTitle(tr("Klein_Method - Auffällige Beobachtungen"));
			a.setHeaderText(null);
			if (a.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
				this.background(() -> String.format(tr("%d Änderungen zurückgenommen."), this.kleinMethod.undoAllFindingEdits()));
		}

		private void retest() {
			this.background(this.kleinMethod::retestFindings);
		}

		private void simulation() {
			if (this.kleinMethod.isAutomaticTaskRunning()) {
				this.status.setText(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			Dialog<ButtonType> dialog = new Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Fehlersimulation"));
			dialog.setHeaderText(tr("Wie gut findet Klein_Method Fehler in diesem Netz?"));
			TextField sizes = new TextField("1 2 3 5"), trials = new TextField("12");
			sizes.setTooltip(new Tooltip(tr("Fehlergrößen in cm, durch Leerzeichen getrennt. Bei Richtungen und Zenitwinkeln: Querabweichung am Ziel.")));
			trials.setTooltip(new Tooltip(tr("Versuche je Fehlergröße und Messart (mehr = genauer, dauert länger).")));
			String[] labels = { tr("Strecken"), tr("Richtungen"), tr("Zenitwinkel"), tr("Prismenkonstante je Ziel"), tr("Instrumentenhöhe je Standpunkt") };
			CheckBox[] boxes = new CheckBox[labels.length];
			GridPane g = new GridPane();
			g.setHgap(8);
			g.setVgap(6);
			g.addRow(0, new Label(tr("Fehlergrößen (cm):")), sizes);
			g.addRow(1, new Label(tr("Versuche je Größe:")), trials);
			for (int i = 0; i < labels.length; i++) {
				boxes[i] = new CheckBox(labels[i]);
				boxes[i].setSelected(true);
				boxes[i].setTooltip(new Tooltip(i < 3 ? tr("Einzelfehler in einer zufällig gewählten Beobachtung dieser Messart.")
						: tr("Gemeinsamer Fehler in einer zufällig gewählten Gruppe (drei Ausgleichungen je Versuch).")));
				g.add(boxes[i], 1, 2 + i);
			}
			Label estimate = new Label();
			Runnable update = () -> {
				try {
					double[] v = this.sizes(sizes.getText());
					boolean[] k = new boolean[boxes.length];
					for (int i = 0; i < k.length; i++)
						k[i] = boxes[i].isSelected();
					estimate.setText(String.format(tr("%d Ausgleichungen"), Klein_Method.errorSimulationAdjustments(v, Integer.parseInt(trials.getText().trim()), k)));
				} catch (Exception e) {
					estimate.setText(tr("Eingabe prüfen"));
				}
			};
			sizes.textProperty().addListener((o, a, b) -> update.run());
			trials.textProperty().addListener((o, a, b) -> update.run());
			for (CheckBox b : boxes)
				b.selectedProperty().addListener((o, a, c) -> update.run());
			update.run();
			g.add(estimate, 1, 2 + labels.length);
			dialog.getDialogPane().setContent(g);
			dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
			if (!dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
				return;
			final double[] v;
			final int n;
			final boolean[] k = new boolean[boxes.length];
			try {
				v = this.sizes(sizes.getText());
				n = Math.max(1, Math.min(500, Integer.parseInt(trials.getText().trim())));
			} catch (Exception e) {
				this.status.setText(tr("Eingabe prüfen"));
				return;
			}
			for (int i = 0; i < k.length; i++)
				k[i] = boxes[i].isSelected();
			this.status.setText(tr("Bitte warten …"));
			Thread t = Klein_Method.busyThread(() -> {
				String text;
				try {
					text = this.kleinMethod.runErrorSimulation(v, n, k, p -> Platform.runLater(() -> this.status.setText(p)));
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				}
				final String result = text;
				Platform.runLater(() -> {
					this.status.setText(tr("Fehlersimulation beendet."));
					Alert a = new Alert(AlertType.INFORMATION);
					a.setTitle(tr("Klein_Method - Fehlersimulation"));
					a.setHeaderText(tr("Ergebnis der Fehlersimulation"));
					javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(result);
					area.setEditable(false);
					area.setWrapText(true);
					area.setStyle("-fx-font-family: monospace;");
					area.setPrefSize(820, 420);
					a.getDialogPane().setContent(area);
					a.showAndWait();
				});
			}, "Klein_Method_Error_Simulation");
			t.setDaemon(true);
			t.start();
		}

		private double[] sizes(String text) {
			String[] parts = text.trim().replace(',', '.').split("[\\s;]+");
			double[] v = new double[parts.length];
			for (int i = 0; i < parts.length; i++) {
				v[i] = Double.parseDouble(parts[i]) / 100.0;
				if (!(v[i] > 0.0) || v[i] > 1.0)
					throw new IllegalArgumentException(parts[i]);
			}
			return v;
		}

		private void groupTests() {
			this.filter.getSelectionModel().select(3);
			this.background(this.kleinMethod::runGroupTests);
		}

		private void csv() {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Liste speichern"));
			chooser.setInitialFileName("Auffaellige_Beobachtungen.csv");
			java.io.File file = chooser.showSaveDialog(this.stage);
			if (file == null)
				return;
			try {
				this.kleinMethod.exportFindingsCsv(file, new ArrayList<Klein_Method.Finding>(this.table.getItems()));
				this.status.setText(String.format(tr("Gespeichert unter: %s"), file.getAbsolutePath()));
			} catch (java.io.IOException e) {
				this.status.setText(tr("Speichern fehlgeschlagen: ") + e.getMessage());
			}
		}
	}

	/** Punkte umbenennen: Vorlagen im Abrollmenue, Vorschau mit Abstandspruefung, Ausfuehren, Ruecknahme. */
	public static final class Klein_Method_Rename_Window {
		private static Klein_Method_Rename_Window instance;
		private final Klein_Method kleinMethod;
		private final javafx.stage.Stage stage = new javafx.stage.Stage();
		private final ComboBox<String> template = new ComboBox<String>(), scope = new ComboBox<String>(), keep = new ComboBox<String>();
		private final TextField search = new TextField(), replace = new TextField(), number = new TextField("4"), filter = new TextField(),
				tolHorizontal = new TextField("3,0"), tolHeight = new TextField("5,0");
		private final CheckBox regex = new CheckBox(tr("Regulärer Ausdruck"));
		private final javafx.scene.control.TableView<Klein_Method.RenameRow> table = new javafx.scene.control.TableView<Klein_Method.RenameRow>();
		private final Label status = new Label();
		private final List<String[]> templates = new ArrayList<String[]>();
		private Klein_Method.RenameSpec lastSpec = null;

		public static void open(Klein_Method kleinMethod) {
			if (instance == null)
				instance = new Klein_Method_Rename_Window(kleinMethod);
			instance.stage.show();
			instance.stage.toFront();
		}

		/** feste Vorlagen: {Anzeige, Suchen, Ersetzen, regex, Sonderregel, Vorgabe n}. */
		private void builtIn() {
			this.templates.clear();
			this.templates.add(new String[] { tr("Endung „-Buchstabe“ entfernen (043.905-A → 043.905)"), "-[A-Za-z]$", "", "1", "0", "" });
			this.templates.add(new String[] { tr("Endung „-Zahl“ / „_Zahl“ entfernen (P12_2 → P12)"), "[-_][0-9]+$", "", "1", "0", "" });
			this.templates.add(new String[] { tr("Endung anhängen (043.905 → 043.905-A)"), "$", "-A", "1", "0", "" });
			this.templates.add(new String[] { tr("Präfix entfernen (HB00815 → 00815)"), "^HB", "", "1", "0", "" });
			this.templates.add(new String[] { tr("Text ersetzen (043. → 044.)"), "043.", "044.", "0", "0", "" });
			this.templates.add(new String[] { tr("Führende Nullen entfernen (0042 → 42)"), "^0+(?=[0-9])", "", "1", "0", "" });
			this.templates.add(new String[] { tr("Nullen auffüllen auf n Stellen (42 → 0042)"), "", "", "1", "1", "4" });
			this.templates.add(new String[] { tr("Nummer verschieben um n (1001 → 2001)"), "", "", "1", "2", "1000" });
			this.templates.add(new String[] { tr("Eigener regulärer Ausdruck"), "", "", "1", "0", "" });
			for (String t : this.kleinMethod.getParameter().getRenameTemplates().split("\u001e"))
				if (!t.isEmpty()) {
					String[] f = t.split("\u001f", -1);
					if (f.length >= 4)
						this.templates.add(new String[] { tr("Eigene Vorlage: ") + f[0], f[1], f[2], f[3], "0", "" });
				}
			List<String> labels = new ArrayList<String>();
			for (String[] t : this.templates)
				labels.add(t[0]);
			this.template.getItems().setAll(labels);
		}

		private Klein_Method_Rename_Window(Klein_Method kleinMethod) {
			this.kleinMethod = kleinMethod;
			this.stage.setTitle(tr("Klein_Method - Punkte umbenennen"));
			this.builtIn();
			this.template.setTooltip(new Tooltip(tr("Vorlage wählen - Suchen/Ersetzen werden ausgefüllt und können angepasst werden.")));
			this.template.valueProperty().addListener((o, a, b) -> this.choose());
			this.search.setTooltip(new Tooltip(tr("Gesuchter Text bzw. regulärer Ausdruck (z. B. -[A-Z]$ = Bindestrich, ein Großbuchstabe am Ende).")));
			this.replace.setTooltip(new Tooltip(tr("Ersatz (leer = entfernen). Bei regulären Ausdrücken: $1, $2 … für Klammergruppen.")));
			this.regex.setTooltip(new Tooltip(tr("Ein: Suchen ist ein regulärer Ausdruck (Java). Aus: einfacher Text.")));
			this.regex.setSelected(true);
			this.number.setTooltip(new Tooltip(tr("n für „Nullen auffüllen“ (Stellen) bzw. „Nummer verschieben“ (Betrag, auch negativ).")));
			this.filter.setPromptText(tr("alle Namen"));
			this.filter.setTooltip(new Tooltip(tr("Nur Namen, die diesen Text enthalten (leer = alle).")));
			this.scope.getItems().setAll(tr("Punkte und Messungen"), tr("nur Messungen"), tr("nur Punkte"));
			this.scope.getSelectionModel().select(0);
			this.scope.setTooltip(new Tooltip(tr("Wo umbenannt wird. Normal: Punkte und Messungen zusammen, sonst passen Messungen und Punkte nicht mehr zueinander.")));
			this.keep.getItems().setAll(tr("Festpunkt bzw. Punkt mit Koordinaten behalten"), tr("Mittel der Näherungskoordinaten (nur Neupunkte)"));
			this.keep.getSelectionModel().select(0);
			this.keep.setTooltip(new Tooltip(tr("Beim Zusammenführen bleibt ein Punkt erhalten: Festpunkt vor Neupunkt, höhere Dimension, mit Koordinaten. "
					+ "Alternativ bekommt der Neupunkt das Mittel der Näherungskoordinaten.")));
			this.tolHorizontal.setTooltip(new Tooltip(tr("Größter Abstand in der Lage (cm), bis zu dem Punkte als derselbe Punkt gelten - darüber „Exzenter?“.")));
			this.tolHeight.setTooltip(new Tooltip(tr("Größter Höhenunterschied (cm), bis zu dem Punkte als derselbe Punkt gelten.")));
			for (TextField f : java.util.Arrays.asList(this.number, this.tolHorizontal, this.tolHeight))
				f.setPrefColumnCount(6);
			GridPane g = new GridPane();
			g.setHgap(8);
			g.setVgap(6);
			int row = 0;
			g.addRow(row++, new Label(tr("Vorlage:")), this.template);
			g.addRow(row++, new Label(tr("Suchen:")), this.search, this.regex);
			g.addRow(row++, new Label(tr("Ersetzen durch:")), this.replace, new HBox(6, new Label("n:"), this.number));
			g.addRow(row++, new Label(tr("Nur Namen mit:")), this.filter, this.scope);
			g.addRow(row++, new Label(tr("Zusammenführen:")), this.keep, new HBox(6, new Label(tr("Lage cm:")), this.tolHorizontal, new Label(tr("Höhe cm:")), this.tolHeight));
			this.template.setMaxWidth(Double.MAX_VALUE);
			this.search.setPrefColumnCount(28);
			this.table.setPlaceholder(new Label(tr("„Vorschau“ zeigt, was umbenannt wird.")));
			javafx.scene.control.TableColumn<Klein_Method.RenameRow, Boolean> use = new javafx.scene.control.TableColumn<Klein_Method.RenameRow, Boolean>(tr("Ausführen"));
			use.setCellValueFactory(cell -> {
				javafx.beans.property.SimpleBooleanProperty p = new javafx.beans.property.SimpleBooleanProperty(cell.getValue().selected);
				p.addListener((o, a, b) -> cell.getValue().selected = b);
				return p;
			});
			use.setCellFactory(javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(use));
			use.setEditable(true);
			use.setPrefWidth(80);
			this.table.getColumns().add(use);
			this.table.setEditable(true);
			this.column(tr("Alter Name"), r -> r.oldName, 150);
			this.column(tr("Neuer Name"), r -> r.newName, 150);
			this.column(tr("Punkt"), r -> r.point ? tr("ja") : "", 60);
			this.column(tr("Messungen"), r -> String.valueOf(r.observations), 80);
			this.column(tr("Ampel"), r -> r.status == 2 ? tr("rot") : r.status == 1 ? tr("gelb") : tr("grün"), 60);
			this.column(tr("Hinweis"), r -> r.note, 460);
			this.table.setRowFactory(tv -> new javafx.scene.control.TableRow<Klein_Method.RenameRow>() {
				@Override
				protected void updateItem(Klein_Method.RenameRow item, boolean empty) {
					super.updateItem(item, empty);
					this.setStyle(empty || item == null ? "" : item.status == 2 ? "-fx-background-color: #f8d7d7;" : item.status == 1 ? "-fx-background-color: #fbf0c8;"
							: item.merge ? "-fx-background-color: #dcefdc;" : "");
				}
			});
			Button previewButton = this.button(tr("Vorschau"), tr("Zeigt alle betroffenen Namen, ohne etwas zu ändern."), this::preview);
			Button applyButton = this.button(tr("Ausführen"), tr("VERÄNDERT DAS PROJEKT: die angehakten Zeilen umbenennen bzw. zusammenführen (rücknehmbar)."), this::apply);
			Button undoButton = this.button(tr("Letzte Umbenennung zurücknehmen"), tr("Stellt die letzte Umbenennung exakt wieder her, einschließlich zusammengeführter Punkte."),
					this::undo);
			Button saveButton = this.button(tr("Als Vorlage speichern …"), tr("Suchen/Ersetzen als eigene Vorlage im Abrollmenü speichern."), this::saveTemplate);
			Button close = this.button(tr("Schließen"), tr("Fenster schließen."), this.stage::hide);
			HBox buttons = new HBox(8, previewButton, applyButton, undoButton, saveButton, close);
			this.status.setWrapText(true);
			Label hint = new Label(tr("Grün: umbenennen bzw. derselbe Punkt. Rot: Exzenter? bzw. Festpunkte mit abweichenden Koordinaten - nicht angehakt. "
					+ "Gelb: nicht prüfbar - erst nach Prüfung anhaken. Danach neu ausgleichen."));
			hint.setWrapText(true);
			VBox root = new VBox(8, g, buttons, this.table, this.status, hint);
			VBox.setVgrow(this.table, javafx.scene.layout.Priority.ALWAYS);
			root.setPadding(new javafx.geometry.Insets(10));
			this.stage.setScene(new javafx.scene.Scene(root, 1100, 680));
			this.template.getSelectionModel().select(0);
		}

		private void column(String title, java.util.function.Function<Klein_Method.RenameRow, String> value, double width) {
			javafx.scene.control.TableColumn<Klein_Method.RenameRow, String> c = new javafx.scene.control.TableColumn<Klein_Method.RenameRow, String>(title);
			c.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
			c.setPrefWidth(width);
			this.table.getColumns().add(c);
		}

		private Button button(String text, String tooltip, Runnable action) {
			Button b = new Button(text);
			b.setTooltip(new Tooltip(tooltip));
			b.setOnAction(e -> action.run());
			return b;
		}

		private void choose() {
			int i = this.template.getSelectionModel().getSelectedIndex();
			if (i < 0 || i >= this.templates.size())
				return;
			String[] t = this.templates.get(i);
			this.search.setText(t[1]);
			this.replace.setText(t[2]);
			this.regex.setSelected("1".equals(t[3]));
			boolean special = !"0".equals(t[4]);
			this.search.setDisable(special);
			this.replace.setDisable(special);
			this.regex.setDisable(special);
			if (!t[5].isEmpty())
				this.number.setText(t[5]);
			this.table.getItems().clear();
		}

		private Klein_Method.RenameSpec spec() {
			Klein_Method.RenameSpec s = new Klein_Method.RenameSpec();
			int i = Math.max(0, this.template.getSelectionModel().getSelectedIndex());
			s.special = i < this.templates.size() ? Integer.parseInt(this.templates.get(i)[4]) : 0;
			s.search = this.search.getText() == null ? "" : this.search.getText();
			s.replace = this.replace.getText() == null ? "" : this.replace.getText();
			s.regex = this.regex.isSelected();
			s.filter = this.filter.getText() == null ? "" : this.filter.getText().trim();
			s.scope = Math.max(0, this.scope.getSelectionModel().getSelectedIndex());
			s.meanApproximation = this.keep.getSelectionModel().getSelectedIndex() == 1;
			s.n = Integer.parseInt(this.number.getText().trim());
			s.toleranceHorizontal = Double.parseDouble(this.tolHorizontal.getText().trim().replace(',', '.')) / 100.0;
			s.toleranceHeight = Double.parseDouble(this.tolHeight.getText().trim().replace(',', '.')) / 100.0;
			if (s.regex && s.special == 0)
				java.util.regex.Pattern.compile(s.search);
			return s;
		}

		private void preview() {
			Klein_Method.RenameSpec s;
			try {
				s = this.spec();
			} catch (Exception e) {
				this.status.setText(tr("Eingabe prüfen: ") + e.getMessage());
				return;
			}
			this.lastSpec = s;
			this.status.setText(tr("Bitte warten …"));
			Thread t = Klein_Method.busyThread(() -> {
				String text;
				List<Klein_Method.RenameRow> rows;
				try {
					rows = this.kleinMethod.previewRename(s);
					int merge = 0, red = 0, yellow = 0;
					for (Klein_Method.RenameRow r : rows) {
						if (r.merge)
							merge++;
						if (r.status == 2)
							red++;
						else if (r.status == 1)
							yellow++;
					}
					text = String.format(tr("%d Namen betroffen, davon %d zusammengeführt; %d rot, %d gelb."), rows.size(), merge, red, yellow);
				} catch (Exception e) {
					rows = new ArrayList<Klein_Method.RenameRow>();
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				}
				final List<Klein_Method.RenameRow> result = rows;
				final String message = text;
				Platform.runLater(() -> {
					this.table.getItems().setAll(result);
					this.status.setText(message);
				});
			}, "Klein_Method_Rename_Preview");
			t.setDaemon(true);
			t.start();
		}

		private void apply() {
			if (this.table.getItems().isEmpty() || this.lastSpec == null) {
				this.status.setText(tr("Zuerst „Vorschau“."));
				return;
			}
			if (this.kleinMethod.isAutomaticTaskRunning()) {
				this.status.setText(tr("Gerade läuft eine Berechnung - bitte kurz warten und erneut versuchen."));
				return;
			}
			List<Klein_Method.RenameRow> rows = new ArrayList<Klein_Method.RenameRow>(this.table.getItems());
			long n = rows.stream().filter(r -> r.selected).count();
			Alert a = new Alert(AlertType.CONFIRMATION, String.format(tr("%d Namen umbenennen bzw. zusammenführen? Rücknahme jederzeit möglich."), n), ButtonType.OK, ButtonType.CANCEL);
			a.setTitle(tr("Klein_Method - Punkte umbenennen"));
			a.setHeaderText(null);
			if (!a.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
				return;
			Klein_Method.RenameSpec s = this.lastSpec;
			this.background(() -> this.kleinMethod.applyRename(rows, s));
		}

		private void undo() {
			this.background(this.kleinMethod::undoLastRename);
		}

		private void background(java.util.concurrent.Callable<String> work) {
			this.status.setText(tr("Bitte warten …"));
			Thread t = Klein_Method.busyThread(() -> {
				String text;
				try {
					text = work.call();
				} catch (Exception e) {
					text = tr("Vorgang fehlgeschlagen: ") + e.getMessage();
				}
				final String message = text;
				Platform.runLater(() -> {
					this.table.getItems().clear();
					this.status.setText(message);
				});
			}, "Klein_Method_Rename");
			t.setDaemon(true);
			t.start();
		}

		private void saveTemplate() {
			javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog();
			d.setTitle(tr("Klein_Method - Punkte umbenennen"));
			d.setHeaderText(tr("Name der Vorlage:"));
			java.util.Optional<String> name = d.showAndWait();
			if (!name.isPresent() || name.get().trim().isEmpty())
				return;
			String entry = name.get().trim().replace('\u001f', ' ').replace('\u001e', ' ') + "\u001f" + this.search.getText() + "\u001f" + this.replace.getText() + "\u001f"
					+ (this.regex.isSelected() ? "1" : "0");
			String all = this.kleinMethod.getParameter().getRenameTemplates();
			this.kleinMethod.getParameter().setRenameTemplates(all.isEmpty() ? entry : all + "\u001e" + entry);
			this.kleinMethod.saveSettings();
			this.builtIn();
			this.template.getSelectionModel().select(this.templates.size() - 1);
			this.status.setText(tr("Vorlage gespeichert."));
		}
	}

	public static final class Klein_Method_Height_Calculator_Window {
		private static Klein_Method_Height_Calculator_Window instance;
		private final Klein_Method kleinMethod;
		private final javafx.stage.Stage stage = new javafx.stage.Stage();
		private final ComboBox<String> sourceSystem = new ComboBox<String>(), targetSystem = new ComboBox<String>();
		private final ComboBox<String> sourceHeight = new ComboBox<String>(), targetHeight = new ComboBox<String>();
		private final TextField zoneField = new TextField(), nameField = new TextField(), v1 = new TextField(), v2 = new TextField(), v3 = new TextField();
		private final Label singleResult = new Label(), modelLabel = new Label(), statusLabel = new Label();
		private final Label l1 = new Label(), l2 = new Label(), l3 = new Label();
		private final CheckBox undulationCheckBox = new CheckBox(tr("Höhenanomalie N als Spalte anhängen"));
		private final javafx.scene.control.TextArea input = new javafx.scene.control.TextArea(), output = new javafx.scene.control.TextArea();
		/** zuletzt geladene Datei und ihr Text - Ergebnis wird automatisch daneben gespeichert, solange die Eingabe unveraendert ist. */
		private java.io.File loadedFile = null;
		private String loadedText = null;

		public static void open(Klein_Method kleinMethod) {
			if (instance == null)
				instance = new Klein_Method_Height_Calculator_Window(kleinMethod);
			instance.refreshModel();
			instance.stage.show();
			instance.stage.toFront();
		}

		private Klein_Method_Height_Calculator_Window(Klein_Method kleinMethod) {
			this.kleinMethod = kleinMethod;
			this.stage.setTitle(tr("Klein_Method - Koordinaten-/Höhenrechner"));
			String[] systems = { tr("ETRS89 / UTM mit Zonenkennung (Rechtswert Hochwert)"), tr("ETRS89 geographisch (Breite Länge, Grad)"),
					tr("ETRS89 geozentrisch (X Y Z)") };
			String[] heights = { tr("DHHN2016 (Normalhöhe, Quasigeoid)"), tr("ellipsoidisch (GRS80)") };
			this.sourceSystem.getItems().setAll(systems);
			this.targetSystem.getItems().setAll(systems);
			this.sourceHeight.getItems().setAll(heights);
			this.targetHeight.getItems().setAll(heights);
			this.sourceSystem.getSelectionModel().select(0);
			this.targetSystem.getSelectionModel().select(0);
			this.sourceHeight.getSelectionModel().select(0);
			this.targetHeight.getSelectionModel().select(1);
			String systemTip = tr("Koordinatensystem der Eingabe bzw. Ausgabe. UTM: Rechtswert mit Zonenkennung (z. B. 32500000.000), Hochwert.\n"
					+ "Geographisch: Breite und Länge in Grad (dezimal). Geozentrisch: X, Y, Z in Metern (die Höhe ist darin enthalten).");
			String heightTip = tr("Höhensystem der Eingabe bzw. Ausgabe: DHHN2016 = Normalhöhe über dem aktiven Quasigeoid (h = H + N), "
					+ "ellipsoidisch = Höhe über dem GRS80-Ellipsoid. Bei geozentrischen Koordinaten ohne Bedeutung.");
			for (ComboBox<String> box : java.util.Arrays.asList(this.sourceSystem, this.targetSystem))
				box.setTooltip(new Tooltip(systemTip));
			for (ComboBox<String> box : java.util.Arrays.asList(this.sourceHeight, this.targetHeight))
				box.setTooltip(new Tooltip(heightTip));
			this.zoneField.setPromptText(tr("automatisch"));
			this.zoneField.setPrefColumnCount(6);
			this.zoneField.setTooltip(new Tooltip(tr("UTM-Zone der Ausgabe (z. B. 32 oder 33). Leer = wie die Eingabe, sonst aus der Länge.")));
			GridPane grid = new GridPane();
			grid.setHgap(8);
			grid.setVgap(6);
			grid.addRow(0, new Label(""), this.boldLabel(tr("Ausgangssystem")), this.boldLabel(tr("Zielsystem")));
			Label systemLabel = new Label(tr("Koordinaten:"));
			systemLabel.setTooltip(new Tooltip(systemTip));
			grid.addRow(1, systemLabel, this.sourceSystem, this.targetSystem);
			Label heightLabel = new Label(tr("Höhe:"));
			heightLabel.setTooltip(new Tooltip(heightTip));
			grid.addRow(2, heightLabel, this.sourceHeight, this.targetHeight);
			Label zoneLabel = new Label(tr("UTM-Zone der Ausgabe:"));
			zoneLabel.setTooltip(this.zoneField.getTooltip());
			grid.addRow(3, zoneLabel, new Label(""), this.zoneField);
			Button importButton = new Button(tr("Quasigeoid importieren …"));
			importButton.setTooltip(new Tooltip(tr("Quasigeoidmodell (z. B. GCG2016 des BKG) einmalig übernehmen; es gilt dann für alle Projekte.")));
			importButton.setOnAction(event -> this.importModel());
			this.modelLabel.setWrapText(true);
			HBox modelRow = new HBox(8, this.modelLabel, importButton);
			modelRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
			// Einzeleingabe
			this.nameField.setPrefColumnCount(10);
			for (TextField f : java.util.Arrays.asList(this.v1, this.v2, this.v3))
				f.setPrefColumnCount(13);
			this.nameField.setPromptText(tr("Punkt"));
			this.nameField.setTooltip(new Tooltip(tr("Punktname (beliebig, wird übernommen).")));
			GridPane single = new GridPane();
			single.setHgap(8);
			single.setVgap(4);
			single.addRow(0, new Label(tr("Punkt")), this.l1, this.l2, this.l3);
			single.addRow(1, this.nameField, this.v1, this.v2, this.v3);
			Button singleButton = new Button(tr("Umrechnen"));
			singleButton.setDefaultButton(true);
			singleButton.setTooltip(new Tooltip(tr("Den eingegebenen Punkt umrechnen.")));
			singleButton.setOnAction(event -> this.convertSingle());
			this.singleResult.setWrapText(true);
			this.singleResult.setStyle("-fx-font-family: monospace;");
			VBox singleBox = new VBox(6, single, new HBox(8, singleButton), this.singleResult);
			TitledPane singlePane = new TitledPane(tr("Einzeleingabe"), singleBox);
			singlePane.setCollapsible(false);
			// Stapel
			this.input.setPromptText(tr("Punkt Wert1 Wert2 Wert3 je Zeile, z. B.\nP1  32500000.000  5650000.000 100.0000"));
			this.input.setPrefRowCount(8);
			this.input.setStyle("-fx-font-family: monospace;");
			this.input.setTooltip(new Tooltip(tr("Eine Zeile je Punkt: Name und drei Werte (Leerzeichen, Tabulator oder Semikolon; Dezimalpunkt oder -komma).\n"
					+ "Weitere Spalten werden angehängt, Zeilen mit # und Leerzeilen unverändert übernommen.")));
			this.output.setEditable(false);
			this.output.setPrefRowCount(8);
			this.output.setStyle("-fx-font-family: monospace;");
			this.output.setTooltip(new Tooltip(tr("Ergebnis der Stapelumrechnung (gleiches Format wie die Eingabe).")));
			Button loadButton = new Button(tr("Datei laden …"));
			loadButton.setTooltip(new Tooltip(tr("Textdatei mit Punktzeilen in das Eingabefeld laden.")));
			loadButton.setOnAction(event -> this.loadFile());
			Button batchButton = new Button(tr("Alle umrechnen"));
			batchButton.setTooltip(new Tooltip(tr("Alle Zeilen des Eingabefelds umrechnen.")));
			batchButton.setOnAction(event -> this.convertBatch());
			Button saveButton = new Button(tr("Ergebnis speichern …"));
			saveButton.setTooltip(new Tooltip(tr("Ergebnis als Textdatei speichern.")));
			saveButton.setOnAction(event -> this.saveFile());
			this.undulationCheckBox.setTooltip(new Tooltip(tr("Hängt an jede Ergebniszeile die verwendete Höhenanomalie N (m) an.")));
			HBox batchButtons = new HBox(8, loadButton, batchButton, saveButton, this.undulationCheckBox);
			batchButtons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
			this.statusLabel.setWrapText(true);
			VBox batchBox = new VBox(6, this.input, batchButtons, this.output, this.statusLabel);
			TitledPane batchPane = new TitledPane(tr("Stapelbearbeitung"), batchBox);
			batchPane.setCollapsible(false);
			Label hint = new Label(tr("Der Rechner ändert kein Projekt. Im Projekt rechnet Klein_Method die Koordinaten selbst um (Reiter Klein_Method)."));
			hint.setWrapText(true);
			Button closeButton = new Button(tr("Schließen"));
			closeButton.setTooltip(new Tooltip(tr("Fenster schließen.")));
			closeButton.setOnAction(event -> this.stage.hide());
			for (ComboBox<String> box : java.util.Arrays.asList(this.sourceSystem, this.targetSystem, this.sourceHeight, this.targetHeight))
				box.valueProperty().addListener((obs, was, now) -> this.refreshLabels());
			this.refreshLabels();
			VBox root = new VBox(10, grid, modelRow, singlePane, batchPane, hint, closeButton);
			root.setPadding(new javafx.geometry.Insets(10));
			ScrollPane scroll = new ScrollPane(root);
			scroll.setFitToWidth(true);
			this.stage.setScene(new javafx.scene.Scene(scroll, 760, 780));
		}

		private Label boldLabel(String text) {
			Label label = new Label(text);
			label.setStyle("-fx-font-weight: bold;");
			return label;
		}

		private int system(ComboBox<String> box) {
			return Math.max(0, box.getSelectionModel().getSelectedIndex());
		}

		private void refreshLabels() {
			int s = this.system(this.sourceSystem);
			String[][] names = { { tr("Rechtswert"), tr("Hochwert"), tr("Höhe") }, { tr("Breite (°)"), tr("Länge (°)"), tr("Höhe") }, { "X", "Y", "Z" } };
			this.l1.setText(names[s][0]);
			this.l2.setText(names[s][1]);
			this.l3.setText(names[s][2]);
			this.sourceHeight.setDisable(s == Klein_Method_Functions.Klein_Method_Height_Calculator.SYSTEM_GEOCENTRIC);
			this.targetHeight.setDisable(this.system(this.targetSystem) == Klein_Method_Functions.Klein_Method_Height_Calculator.SYSTEM_GEOCENTRIC);
			this.zoneField.setDisable(this.system(this.targetSystem) != Klein_Method_Functions.Klein_Method_Height_Calculator.SYSTEM_UTM);
		}

		private void refreshModel() {
			Klein_Method_Functions.Klein_Method_Quasigeoid.Model model = Klein_Method_Functions.Klein_Method_Quasigeoid.active();
			this.modelLabel.setText(model == null ? tr("Quasigeoid: keines importiert - für DHHN2016 bitte importieren.")
					: String.format(tr("Quasigeoid: %s"), model.title()));
		}

		private int zone() {
			String z = this.zoneField.getText() == null ? "" : this.zoneField.getText().trim();
			if (z.isEmpty())
				return 0;
			try {
				int v = Integer.parseInt(z);
				return v >= 1 && v <= 60 ? v : -1;
			} catch (NumberFormatException e) {
				return -1;
			}
		}

		private Klein_Method_Functions.Klein_Method_Height_Calculator.Line_Result convert(String line, boolean appendN) {
			return Klein_Method_Functions.Klein_Method_Height_Calculator.convertLine(line, this.system(this.sourceSystem),
					this.sourceHeight.getSelectionModel().getSelectedIndex(), this.system(this.targetSystem), this.targetHeight.getSelectionModel().getSelectedIndex(),
					Math.max(0, this.zone()), Klein_Method_Functions.Klein_Method_Quasigeoid.active(), appendN);
		}

		private void convertSingle() {
			if (this.zone() < 0) {
				this.singleResult.setText(tr("UTM-Zone muss zwischen 1 und 60 liegen."));
				return;
			}
			String name = this.nameField.getText() == null || this.nameField.getText().trim().isEmpty() ? "P" : this.nameField.getText().trim().replace(' ', '_');
			Klein_Method_Functions.Klein_Method_Height_Calculator.Line_Result r = this.convert(name + " " + this.v1.getText().trim() + " " + this.v2.getText().trim()
					+ " " + this.v3.getText().trim(), false);
			if (r.error != null)
				this.singleResult.setText(tr("Fehler: ") + r.error);
			else
				this.singleResult.setText(r.line + (Double.isNaN(r.undulation) ? "" : "\n" + String.format(java.util.Locale.GERMANY, tr("Höhenanomalie N = %.4f m"), r.undulation)));
		}

		private void convertBatch() {
			if (this.zone() < 0) {
				this.statusLabel.setText(tr("UTM-Zone muss zwischen 1 und 60 liegen."));
				return;
			}
			StringBuilder out = new StringBuilder();
			int done = 0, failed = 0, lineNo = 0;
			List<String> errors = new ArrayList<String>();
			for (String line : this.input.getText().split("\\r?\\n", -1)) {
				lineNo++;
				Klein_Method_Functions.Klein_Method_Height_Calculator.Line_Result r = this.convert(line, this.undulationCheckBox.isSelected());
				if (r.error != null) {
					failed++;
					if (errors.size() < 20)
						errors.add(String.format(tr("Zeile %d: %s"), lineNo, r.error));
					out.append("# ").append(tr("FEHLER")).append(": ").append(line.trim()).append("  (").append(r.error).append(")");
				} else {
					out.append(r.line);
					if (!r.copied)
						done++;
				}
				out.append("\n");
			}
			String result = alignColumns(out.toString().replaceAll("\\n+$", "\n"));
			this.output.setText(result);
			String saved = "";
			if (this.loadedFile != null && this.loadedText != null && this.loadedText.equals(this.input.getText()) && done > 0) {
				java.io.File target = this.resultFile(this.loadedFile);
				try {
					java.nio.file.Files.write(target.toPath(), result.replace("\n", System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
					saved = "\n" + String.format(tr("Automatisch gespeichert unter: %s"), target.getAbsolutePath());
					this.kleinMethod.getLogger().log("Klein_Method: Koordinaten-/Hoehenrechner - Ergebnis gespeichert: " + target.getAbsolutePath());
				} catch (java.io.IOException e) {
					saved = "\n" + tr("Speichern fehlgeschlagen: ") + e.getMessage();
				}
			}
			this.statusLabel.setText(String.format(tr("%d Punkte umgerechnet, %d mit Fehler."), done, failed) + saved + (errors.isEmpty() ? "" : "\n" + String.join("\n", errors)));
			this.kleinMethod.getLogger().log("Klein_Method: Koordinaten-/Hoehenrechner - " + done + " Punkte umgerechnet, " + failed + " mit Fehler.");
		}

		private void loadFile() {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Punktdatei wählen"));
			chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(tr("Alle Dateien (*.*)"), "*.*", "*"));
			java.io.File file = chooser.showOpenDialog(this.stage);
			if (file == null)
				return;
			try {
				byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
				String text;
				try {
					text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
				} catch (java.nio.charset.CharacterCodingException e) {
					text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
				}
				this.input.setText(text);
				this.loadedFile = file;
				this.loadedText = this.input.getText();
				this.statusLabel.setText(String.format(tr("Geladen: %s"), file.getAbsolutePath()) + "\n"
						+ String.format(tr("Nach „Alle umrechnen“ wird das Ergebnis automatisch gespeichert als: %s"), this.resultFile(file).getName()));
			} catch (java.io.IOException e) {
				this.statusLabel.setText(tr("Datei nicht lesbar: ") + e.getMessage());
			}
		}

		/** Ergebnisdatei neben dem Original: Name + Zusatz nach Zielsystem (z. B. _ellipsoidisch), gleiche Endung. */
		private java.io.File resultFile(java.io.File source) {
			String name = source.getName();
			int dot = name.lastIndexOf('.');
			String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : ".txt";
			StringBuilder suffix = new StringBuilder();
			int sh = this.sourceHeight.getSelectionModel().getSelectedIndex(), th = this.targetHeight.getSelectionModel().getSelectedIndex();
			int ss = this.sourceSystem.getSelectionModel().getSelectedIndex(), ts = this.targetSystem.getSelectionModel().getSelectedIndex();
			if (th != sh || ss == ts)
				suffix.append(th == 1 ? "_ellipsoidisch" : "_DHHN2016");
			if (ts != ss)
				suffix.append(ts == 0 ? "_UTM" : ts == 1 ? "_geographisch" : "_geozentrisch");
			return new java.io.File(source.getParentFile(), base + suffix + ext);
		}

		/**
		 * Spalten rechtsbuendig untereinander: jede Zeile in Felder (Leerraum) zerlegen, je Spalte die groesste Breite,
		 * Felder rechtsbuendig mit zwei Leerzeichen Abstand. Kommentarzeilen (#) und Leerzeilen bleiben unveraendert.
		 */
		static String alignColumns(String text) {
			String[] lines = text.split("\n", -1);
			List<Integer> width = new ArrayList<Integer>();
			for (String line : lines) {
				String t = line.trim();
				if (t.isEmpty() || t.startsWith("#"))
					continue;
				String[] f = t.split("\\s+");
				for (int i = 0; i < f.length; i++) {
					if (width.size() <= i)
						width.add(0);
					width.set(i, Math.max(width.get(i), f[i].length()));
				}
			}
			StringBuilder out = new StringBuilder();
			for (int k = 0; k < lines.length; k++) {
				String t = lines[k].trim();
				if (t.isEmpty() || t.startsWith("#"))
					out.append(lines[k]);
				else {
					String[] f = t.split("\\s+");
					for (int i = 0; i < f.length; i++) {
						if (i > 0)
							out.append("  ");
						for (int pad = f[i].length(); pad < width.get(i); pad++)
							out.append(' ');
						out.append(f[i]);
					}
				}
				if (k < lines.length - 1)
					out.append('\n');
			}
			return out.toString();
		}

		private void saveFile() {
			if (this.output.getText() == null || this.output.getText().trim().isEmpty()) {
				this.statusLabel.setText(tr("Noch kein Ergebnis - zuerst „Alle umrechnen“."));
				return;
			}
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Ergebnis speichern"));
			java.io.File file = chooser.showSaveDialog(this.stage);
			if (file == null)
				return;
			try {
				java.nio.file.Files.write(file.toPath(), this.output.getText().replace("\n", System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				this.statusLabel.setText(String.format(tr("Gespeichert unter: %s"), file.getAbsolutePath()));
			} catch (java.io.IOException e) {
				this.statusLabel.setText(tr("Speichern fehlgeschlagen: ") + e.getMessage());
			}
		}

		private void importModel() {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Quasigeoidmodell wählen"));
			javafx.stage.FileChooser.ExtensionFilter files = new javafx.stage.FileChooser.ExtensionFilter(tr("Quasigeoid (*.zip, *.txt, *.isg, *.gri)"), "*.zip", "*.ZIP",
					"*.txt", "*.TXT", "*.isg", "*.ISG", "*.gri", "*.GRI");
			chooser.getExtensionFilters().addAll(files, new javafx.stage.FileChooser.ExtensionFilter(tr("Alle Dateien (*.*)"), "*.*", "*"));
			chooser.setSelectedExtensionFilter(files);
			java.io.File source = chooser.showOpenDialog(this.stage);
			if (source != null)
				this.runImport(source, false);
		}

		private void runImport(java.io.File source, boolean replace) {
			this.modelLabel.setText(tr("Quasigeoid wird übernommen …"));
			Thread thread = Klein_Method.busyThread(() -> {
				String text, existing = null;
				try {
					Klein_Method_Functions.Klein_Method_Quasigeoid.Import_Result result = this.kleinMethod.importQuasigeoid(source, null, replace);
					text = String.format(tr("Quasigeoidmodell „%s“ übernommen."), result.model.title());
				} catch (Klein_Method_Functions.Klein_Method_Quasigeoid.Exists_Exception e) {
					existing = e.name;
					text = null;
				} catch (Exception e) {
					text = tr("Quasigeoid nicht übernommen: ") + e.getMessage();
				}
				final String message = text, exists = existing;
				Platform.runLater(() -> {
					this.refreshModel();
					if (exists != null) {
						Alert confirm = new Alert(AlertType.CONFIRMATION, String.format(tr("Das Quasigeoidmodell „%s“ ist schon vorhanden. Ersetzen?"), exists),
								ButtonType.YES, ButtonType.NO);
						confirm.setTitle(tr("Klein_Method - Quasigeoid importieren"));
						confirm.setHeaderText(null);
						if (confirm.showAndWait().filter(b -> b == ButtonType.YES).isPresent())
							this.runImport(source, true);
						return;
					}
					this.statusLabel.setText(message);
				});
			}, "Klein_Method_Quasigeoid_Import");
			thread.setDaemon(true);
			thread.start();
		}
	}

	public static final class Klein_Method_Conversion_Window {
		private static Klein_Method_Conversion_Window instance;
		private final Klein_Method kleinMethod;
		private final javafx.stage.Stage stage = new javafx.stage.Stage();
		private final TextField latitudeField = new TextField(), longitudeField = new TextField(), heightField = new TextField();
		private final TextField eastField = new TextField(), northField = new TextField(), upField = new TextField(), decimalsField = new TextField();
		private final Label p0Label = new Label(), projectStateLabel = new Label();
		private final ComboBox<String> modeComboBox = new ComboBox<String>();
		private final javafx.scene.control.TextArea resultArea = new javafx.scene.control.TextArea();
		private final String MODE_POINTS_TO_LOCAL = tr("Punkte: WGS84 (X/Y/Z) → lokal (ENh)");
		private final String MODE_POINTS_TO_WGS84 = tr("Punkte: lokal (ENh) → WGS84 (X/Y/Z)");
		private final String MODE_BASELINES = tr("Basislinien (Richtung nach Dateiendung .BLall / .BLallLok)");
		// Epochen je Basislinie zusammenfassen (nach VBA V0.06), nur im Modus Basislinien sichtbar
		private final String EPOCHS_SINGLE = tr("einzeln (jede Zeile umrechnen)");
		private final String EPOCHS_MEAN = tr("Mittelwert mit Streuung (wie VBA V0.06)");
		private final String EPOCHS_ROBUST = tr("robuster Mittelwert (Ausreißer verwerfen, empfohlen)");
		private final ComboBox<String> epochComboBox = new ComboBox<String>();
		private final TextField sigmaFactorField = new TextField(), sigmaMinField = new TextField();

		public static void open(Klein_Method kleinMethod) {
			if (instance == null)
				instance = new Klein_Method_Conversion_Window(kleinMethod);
			instance.fillFields();
			instance.refreshProjectState();
			instance.stage.show();
			instance.stage.toFront();
		}

		private Klein_Method_Conversion_Window(Klein_Method kleinMethod) {
			this.kleinMethod = kleinMethod;
			this.stage.setTitle(tr("Klein_Method - Koordinatenumrechnung WGS84 ↔ lokal (ENh)"));

			// Parameter
			GridPane grid = new GridPane();
			grid.setHgap(8);
			grid.setVgap(4);
			int row = 0;
			row = this.addField(grid, row, tr("Breite B0 von P0 (°):"), this.latitudeField, tr("Ellipsoidische Breite des Bezugspunkts P0 (GRS80) in Grad, dezimal.\n"
					+ "Standard aus dem VBA-Code: 51,3539773354° (Wert aus Geotra)."));
			row = this.addField(grid, row, tr("Länge L0 von P0 (°):"), this.longitudeField, tr("Ellipsoidische Länge von P0 in Grad, dezimal.\n"
					+ "Standard: 12,3740940991°."));
			row = this.addField(grid, row, tr("Ellipsoidische Höhe h0 von P0 (m):"), this.heightField, tr("Ellipsoidische Höhe von P0 in Metern. Standard: 178,391443967 m.\n"
					+ "X/Y/Z von P0 werden daraus berechnet (unten angezeigt) - so passen beide Angaben immer zusammen."));
			row = this.addField(grid, row, tr("Additionskonstante Ost (m):"), this.eastField, tr("Wird zum lokalen Ostwert addiert (JAG3D-Spalte y). Standard 200 000 m."));
			row = this.addField(grid, row, tr("Additionskonstante Nord (m):"), this.northField, tr("Wird zum lokalen Nordwert addiert (JAG3D-Spalte x). Standard 400 000 m."));
			row = this.addField(grid, row, tr("Additionskonstante Höhe (m):"), this.upField, tr("Wird zur lokalen Höhe (Oben-Komponente) addiert (JAG3D-Spalte z). Standard 1 000 m."));
			row = this.addField(grid, row, tr("Nachkommastellen:"), this.decimalsField, tr("Rundung der umgerechneten Werte. Standard 4 (0,1 mm), erlaubt 0 bis 8."));
			this.p0Label.setTooltip(new Tooltip(tr("Geozentrische Koordinaten von P0, aus B0/L0/h0 berechnet (GRS80).")));
			grid.add(this.p0Label, 0, row++, 2, 1);
			Button defaultsButton = new Button(tr("Standardwerte (VBA) wiederherstellen"));
			defaultsButton.setTooltip(new Tooltip(tr("Setzt alle Grundwerte auf die Werte aus dem VBA-Code zurück und speichert sie.")));
			defaultsButton.setOnAction(event -> {
				Klein_Method_Functions.Klein_Method_Local_System.Parameters defaults = new Klein_Method_Functions.Klein_Method_Local_System.Parameters();
				this.store(defaults);
				this.fillFields();
			});
			grid.add(defaultsButton, 0, row++, 2, 1);
			TitledPane parameterPane = new TitledPane(tr("Grundwerte für neue Projekte (editierbar, dauerhaft gespeichert)"), grid);
			parameterPane.setCollapsible(false);

			// Modus 1: Dateien
			this.modeComboBox.getItems().setAll(MODE_POINTS_TO_LOCAL, MODE_POINTS_TO_WGS84, MODE_BASELINES);
			this.modeComboBox.setConverter(new StringConverter<String>() {
				@Override
				public String toString(String value) {
					return value == null ? "" : tr(value);
				}

				@Override
				public String fromString(String string) {
					return null;
				}
			});
			this.modeComboBox.setValue(MODE_POINTS_TO_LOCAL);
			this.modeComboBox.setTooltip(new Tooltip(tr("Punkte: Datei mit Name Y X Z (geozentrisch, *.Fpkt) bzw. Name Ost Nord Höhe (lokal, *.FpktLok).\n"
					+ "Basislinien: Start Ziel dY dX dZ (*.BLall) bzw. dOst dNord dOben (*.BLallLok) - die Richtung ergibt sich aus der Dateiendung.\n"
					+ "Weitere Spalten (z. B. Standardabweichungen) werden unverändert übernommen.")));
			Button fileButton = new Button(tr("Datei wählen und umrechnen …"));
			fileButton.setTooltip(new Tooltip(tr("Datei auswählen; das Ergebnis wird daneben gespeichert:\n"
					+ "Punkte → lokal: Name.FpktLok, Punkte → WGS84: Name_WGS84.Fpkt,\n"
					+ "Basislinien → lokal: Name.BLallLok, Basislinien → WGS84: Name_WGS84.BLall.\n"
					+ "Die Originaldatei bleibt unverändert. Danach wie gewohnt in JAG3D importieren.")));
			fileButton.setOnAction(event -> this.convertFile());
			// Epochen je Basislinie
			this.epochComboBox.getItems().setAll(EPOCHS_SINGLE, EPOCHS_MEAN, EPOCHS_ROBUST);
			Klein_Method_Parameter epochParameter = this.kleinMethod.getParameter();
			this.epochComboBox.setValue(this.epochComboBox.getItems().get(epochParameter.getBaselineEpochMode()));
			this.epochComboBox.setTooltip(new Tooltip(tr("Nur für Basislinien: mehrere Zeilen (Epochen/Sessions) derselben Basislinie Start → Ziel.\n"
					+ "Einzeln: jede Zeile wird umgerechnet (wie bisher).\n"
					+ "Mittelwert: eine Zeile je Basislinie mit Standardabweichung je Komponente - Streuung der Epochen (n-1), bewusst nicht durch √n "
					+ "geteilt, weil benachbarte Epochen zusammenhängen; mal Faktor, mindestens der Mindestwert. Ausgabe: Start Ziel d1 d2 d3 s1 s2 s3 "
					+ "(JAG3D-Format mit Unsicherheiten).\n"
					+ "Robust: wie Mittelwert, vorher werden Epochen verworfen, die in einer Komponente mehr als 3,5 × MAD (mindestens 3,5 × Mindestwert) "
					+ "vom Median abweichen - ein einzelner Ausreißer verfälscht den Mittelwert sonst. Verworfene Epochen werden gemeldet.\n"
					+ "A→B und B→A bleiben getrennt; kommen beide vor, wird A→B gegen -(B→A) geprüft (Grenze 3σ).\n"
					+ "Hinweis: nach dem Mitteln sieht Klein_Method keine einzelnen Sessions mehr (grobe Fehler je Session, Kalibrierung je Session).")));
			this.sigmaFactorField.setText(String.format(java.util.Locale.GERMANY, "%.2f", epochParameter.getBaselineSigmaFactor()));
			this.sigmaFactorField.setPrefColumnCount(5);
			this.sigmaFactorField.setTooltip(new Tooltip(tr("Faktor auf die Streuung der Epochen (1 = unverändert, 2 = doppelt). Standard 1.")));
			this.sigmaMinField.setText(String.format(java.util.Locale.GERMANY, "%.1f", epochParameter.getBaselineSigmaMinMm()));
			this.sigmaMinField.setPrefColumnCount(5);
			this.sigmaMinField.setTooltip(new Tooltip(tr("Kleinste Standardabweichung je Komponente in mm (auch für Basislinien mit nur einer Epoche). Standard 5 mm.")));
			Label epochLabel = new Label(tr("Epochen je Basislinie:"));
			epochLabel.setTooltip(this.epochComboBox.getTooltip());
			Label factorLabel = new Label(tr("σ-Faktor:"));
			factorLabel.setTooltip(this.sigmaFactorField.getTooltip());
			Label minLabel = new Label(tr("σ min (mm):"));
			minLabel.setTooltip(this.sigmaMinField.getTooltip());
			HBox sigmaRow = new HBox(8, factorLabel, this.sigmaFactorField, minLabel, this.sigmaMinField);
			VBox epochBox = new VBox(6, new HBox(8, epochLabel, this.epochComboBox), sigmaRow);
			Runnable epochVisibility = () -> {
				boolean baselinesMode = MODE_BASELINES.equals(this.modeComboBox.getValue());
				epochBox.setVisible(baselinesMode);
				epochBox.setManaged(baselinesMode);
				boolean averaged = !EPOCHS_SINGLE.equals(this.epochComboBox.getValue());
				sigmaRow.setVisible(averaged);
				sigmaRow.setManaged(averaged);
			};
			this.modeComboBox.valueProperty().addListener((obs, o, n) -> epochVisibility.run());
			this.epochComboBox.valueProperty().addListener((obs, o, n) -> {
				epochVisibility.run();
				if (n != null) {
					epochParameter.setBaselineEpochMode(this.epochComboBox.getItems().indexOf(n));
					this.kleinMethod.saveSettings();
				}
			});
			epochVisibility.run();
			VBox fileBox = new VBox(6, new HBox(8, new Label(tr("Umrechnung:")), this.modeComboBox), epochBox, fileButton);
			TitledPane filePane = new TitledPane(tr("Modus 1: Dateien vorab umrechnen (vor dem Import in JAG3D)"), fileBox);
			filePane.setCollapsible(false);

			// Modus 2: Projekt
			Button projectButton = new Button(tr("Projekt prüfen und umrechnen …"));
			projectButton.setTooltip(new Tooltip(tr("Rechnet die geozentrischen 3D-Punkte und 3D-GNSS-Basislinien des geöffneten JAG3D-Projekts nach Rückfrage in das\n"
					+ "lokale System um und stellt die Projektion auf „lokal ellipsoidisch“ (Bezugspunkt = P0). P0: Schwerpunkt der Neupunkte\n"
					+ "(Festpunkte zählen nicht), Breite/Länge auf 0,00001° und Höhe auf 0,01 m gerundet (wird im Projekt gespeichert);\n"
					+ "ohne Neupunkte die Grundwerte.\n"
					+ "Die alten Werte werden gesichert.\n"
					+ "Beim Anklicken des Tabs Klein_Method wird das automatisch angeboten, wenn 3D-GNSS-Basislinien erkannt werden.")));
			projectButton.setOnAction(event -> {
				this.kleinMethod.convertProjectOnDemand();
				this.refreshProjectStateLater();
			});
			Button restoreButton = new Button(tr("Projekt-Umrechnung zurücknehmen"));
			restoreButton.setTooltip(new Tooltip(tr("Setzt Punkte, Basislinien und Projektion auf die gesicherten Werte vor der Umrechnung zurück.")));
			restoreButton.setOnAction(event -> {
				this.kleinMethod.restoreProjectConversionOnDemand();
				this.refreshProjectStateLater();
			});
			// P0 eines umgerechneten Projekts in den Netzschwerpunkt legen
			Button relocateButton = new Button(tr("P0 ins Netz legen und neu umrechnen …"));
			relocateButton.setTooltip(new Tooltip(tr("Für ein bereits umgerechnetes Projekt: P0 wird in den Schwerpunkt der Neupunkte gelegt (Festpunkte zählen nicht;\n"
					+ "Breite/Länge auf 0,00001°, Höhe auf 0,01 m gerundet)\n"
					+ "und alle lokalen 3D-Punkte und 3D-Basislinien werden direkt umgerechnet. Wichtig bei Zenitwinkeln und Nivellement:\n"
					+ "in d km Abstand von P0 ist die Oben-Achse um ca. d × 10 mgon gegen das Lot geneigt (5 km: 50 mgon).\n"
					+ "Übernommene Näherungskoordinaten bleiben erhalten; „zurücknehmen“ führt weiter auf den geozentrischen Ausgangsstand.")));
			relocateButton.setOnAction(event -> {
				this.kleinMethod.relocateP0OnDemand();
				this.refreshProjectStateLater();
			});
			this.projectStateLabel.setWrapText(true);
			this.projectStateLabel.setTooltip(new Tooltip(tr("Stand des geöffneten JAG3D-Projekts.")));
			javafx.scene.layout.FlowPane projectButtons = new javafx.scene.layout.FlowPane(8, 6, projectButton, relocateButton, restoreButton);
			VBox projectBox = new VBox(6, this.projectStateLabel, projectButtons);
			TitledPane projectPane = new TitledPane(tr("Modus 2: Koordinaten im geöffneten JAG3D-Projekt umrechnen"), projectBox);
			projectPane.setCollapsible(false);

			Label hint = new Label(tr("Hinweis: „Höhe“ im lokalen System ist die Höhe über der Tangentialebene in P0 (in 1 km Entfernung ca. 8 cm unter der "
					+ "ellipsoidischen Höhe, Lotrichtung dort um 10 mgon geneigt). Klein_Method stellt die JAG3D-Projektion deshalb auf „lokal ellipsoidisch“ mit "
					+ "Bezugspunkt P0 - JAG3D reduziert dann jede Beobachtung auf die Lotrichtung ihres Punkts. Ergebnisse nach der Ausgleichung exportieren und mit "
					+ "„Punkte: lokal → WGS84“ zurückrechnen."));
			hint.setWrapText(true);
			hint.setTooltip(new Tooltip(hint.getText()));
			this.resultArea.setEditable(false);
			this.resultArea.setWrapText(true);
			this.resultArea.setPrefRowCount(6);
			this.resultArea.setTooltip(new Tooltip(tr("Ergebnis der letzten Umrechnung.")));
			Button closeButton = new Button(tr("Schließen"));
			closeButton.setTooltip(new Tooltip(tr("Fenster schließen (die Grundwerte bleiben gespeichert).")));
			closeButton.setOnAction(event -> this.stage.hide());
			VBox root = new VBox(10, parameterPane, filePane, projectPane, hint, this.resultArea, closeButton);
			root.setPadding(new javafx.geometry.Insets(10));
			ScrollPane scroll = new ScrollPane(root);
			scroll.setFitToWidth(true);
			this.stage.setScene(new javafx.scene.Scene(scroll, 720, 760));
		}

		private int addField(GridPane grid, int row, String text, TextField field, String tooltip) {
			Label label = new Label(text);
			field.setPrefWidth(180);
			field.setTooltip(new Tooltip(tooltip));
			label.setTooltip(field.getTooltip());
			field.setOnAction(event -> this.applyFields());
			field.focusedProperty().addListener((obs, was, now) -> {
				if (!now)
					this.applyFields();
			});
			grid.add(label, 0, row);
			grid.add(field, 1, row);
			return row + 1;
		}

		private void fillFields() {
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = this.kleinMethod.getParameter().getLocalSystem();
			this.latitudeField.setText(String.format(java.util.Locale.GERMANY, "%.10f", p.latitude));
			this.longitudeField.setText(String.format(java.util.Locale.GERMANY, "%.10f", p.longitude));
			this.heightField.setText(String.format(java.util.Locale.GERMANY, "%.9f", p.height));
			this.eastField.setText(String.format(java.util.Locale.GERMANY, "%.3f", p.addEast));
			this.northField.setText(String.format(java.util.Locale.GERMANY, "%.3f", p.addNorth));
			this.upField.setText(String.format(java.util.Locale.GERMANY, "%.3f", p.addUp));
			this.decimalsField.setText(String.valueOf(p.decimals));
			double[] p0 = p.p0();
			this.p0Label.setText(String.format(java.util.Locale.GERMANY, tr("P0 geozentrisch:  X %.4f   Y %.4f   Z %.4f m"), p0[0], p0[1], p0[2]));
		}

		private static double parse(TextField field) {
			try {
				return Double.parseDouble(field.getText().trim().replace(" ", "").replace(",", "."));
			} catch (RuntimeException e) {
				return Double.NaN;
			}
		}

		private static double parseKeep(TextField field, double current, String format) {
			if (field.getText().trim().equals(String.format(java.util.Locale.GERMANY, format, current)))
				return current;
			return parse(field);
		}

		private void applyFields() {
			Klein_Method_Functions.Klein_Method_Local_System.Parameters p = this.kleinMethod.getParameter().getLocalSystem().copy();
			// unveraenderte Felder behalten den gespeicherten Wert exakt (keine Rundung durch die Anzeige)
			p.latitude = parseKeep(this.latitudeField, p.latitude, "%.10f");
			p.longitude = parseKeep(this.longitudeField, p.longitude, "%.10f");
			p.height = parseKeep(this.heightField, p.height, "%.9f");
			p.addEast = parseKeep(this.eastField, p.addEast, "%.3f");
			p.addNorth = parseKeep(this.northField, p.addNorth, "%.3f");
			p.addUp = parseKeep(this.upField, p.addUp, "%.3f");
			double decimals = parse(this.decimalsField);
			p.decimals = Double.isFinite(decimals) ? (int) Math.round(decimals) : -1;
			if (!p.valid() || Double.isNaN(p.latitude) || Double.isNaN(p.longitude) || Double.isNaN(p.height)) {
				this.resultArea.setText(tr("Eingabe ungültig - der bisherige Wert bleibt (Breite ±90°, Länge ±180°, Höhe unter 10 km, "
						+ "Nachkommastellen 0 bis 8)."));
				this.fillFields();
				return;
			}
			this.store(p);
			this.fillFields();
		}

		private void store(Klein_Method_Functions.Klein_Method_Local_System.Parameters p) {
			Klein_Method_Functions.Klein_Method_Local_System.Parameters target = this.kleinMethod.getParameter().getLocalSystem();
			boolean changed = !p.encode().equals(target.encode());
			target.latitude = p.latitude;
			target.longitude = p.longitude;
			target.height = p.height;
			target.addEast = p.addEast;
			target.addNorth = p.addNorth;
			target.addUp = p.addUp;
			target.decimals = p.decimals;
			if (changed) {
				this.kleinMethod.getLogger().log("Klein_Method: Grundwerte Koordinatenumrechnung " + target.encode());
				this.kleinMethod.saveSettings();
				Klein_Method_Map_Window.notifyDataChanged(); // Karte mitziehen
			}
		}

		private void refreshProjectState() {
			Thread thread = new Thread(() -> {
				String state = this.kleinMethod.describeProjectConversionState();
				Platform.runLater(() -> this.projectStateLabel.setText(state));
			}, "Klein_Method_Conversion_State");
			thread.setDaemon(true);
			thread.start();
		}

		/** Stand nach dem Ende einer Hintergrundaktion erneut lesen. */
		private void refreshProjectStateLater() {
			Thread thread = new Thread(() -> {
				try {
					Thread.sleep(500);
					while (this.kleinMethod.isApproximationOrConversionRunning())
						Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				this.refreshProjectState();
			}, "Klein_Method_Conversion_State_Wait");
			thread.setDaemon(true);
			thread.start();
		}

		/** Modus 1: Datei waehlen, Richtung pruefen, umrechnen, daneben speichern. */
		private void showConversionLock(String text) {
			this.resultArea.setText(text);
			this.kleinMethod.getLogger().log("Klein_Method: Koordinatenumrechnung Datei gesperrt - " + text);
			Alert alert = new Alert(AlertType.WARNING, text, ButtonType.OK);
			alert.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
			alert.setHeaderText(tr("Umrechnung gesperrt"));
			alert.showAndWait();
		}

		private void convertFile() {
			this.applyFields();
			String mode = this.modeComboBox.getValue();
			boolean baselines = MODE_BASELINES.equals(mode);
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Koordinatendatei wählen"));
			// alle Dateien (*.*) zuerst und vorausgewaehlt; "*" zeigt auch Dateien ohne Endung
			javafx.stage.FileChooser.ExtensionFilter allFiles = new javafx.stage.FileChooser.ExtensionFilter(tr("Alle Dateien (*.*)"), "*.*", "*");
			chooser.getExtensionFilters().add(allFiles);
			if (baselines)
				chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(tr("Basislinien (*.BLall, *.BLallLok)"), "*.BLall", "*.BLallLok", "*.blall", "*.blalllok"));
			else if (MODE_POINTS_TO_LOCAL.equals(mode))
				chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(tr("Punkte geozentrisch (*.Fpkt)"), "*.Fpkt", "*.fpkt"));
			else
				chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(tr("Punkte lokal (*.FpktLok)"), "*.FpktLok", "*.fpktlok"));
			chooser.setSelectedExtensionFilter(allFiles);
			java.io.File input = chooser.showOpenDialog(this.stage);
			if (input == null)
				return;
			String name = input.getName(), lower = name.toLowerCase(java.util.Locale.ROOT);
			boolean toLocal;
			if (baselines) {
				if (lower.endsWith(".blalllok"))
					toLocal = false;
				else if (lower.endsWith(".blall"))
					toLocal = true;
				else {
					javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<String>(tr("WGS84 → lokal"), tr("WGS84 → lokal"),
							tr("lokal → WGS84"));
					dialog.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
					dialog.setHeaderText(tr("Die Dateiendung verrät die Richtung nicht. In welche Richtung umrechnen?"));
					java.util.Optional<String> answer = dialog.showAndWait();
					if (!answer.isPresent())
						return;
					toLocal = answer.get().equals(tr("WGS84 → lokal"));
				}
			}
			else {
				toLocal = MODE_POINTS_TO_LOCAL.equals(mode);
				// Richtung nach dem Inhalt pruefen (geozentrisch = Abstand vom Erdmittelpunkt 6,30-6,42 Mio. m),
				// nicht nach der Dateiendung - gewarnt wird nur bei einem echten Widerspruch
				int[] content;
				try {
					content = Klein_Method_Functions.Klein_Method_Local_System.classifyPointFile(input);
				} catch (Exception e) {
					this.resultArea.setText(tr("Umrechnung fehlgeschlagen: ") + e.getMessage());
					return;
				}
				if (content[0] + content[1] == 0) {
					this.resultArea.setText(tr("Die Datei enthält keine Punktzeilen (Name und drei Zahlen)."));
					return;
				}
				boolean contradiction = toLocal ? content[1] > 0 : content[0] > 0;
				// Sperre: alle Punkte schon im Zielsystem - ein zweites Umrechnen waere sicher falsch
				if (toLocal ? content[0] == 0 : content[1] == 0) {
					this.showConversionLock(toLocal ? tr("Alle Punkte der Datei sind schon lokal (nicht geozentrisch) - ein zweites Umrechnen ist gesperrt.")
							: tr("Alle Punkte der Datei sind schon geozentrisch (WGS84 X/Y/Z) - ein zweites Umrechnen ist gesperrt."));
					return;
				}
				if (contradiction) {
					Alert alert = new Alert(AlertType.CONFIRMATION, "", ButtonType.YES, ButtonType.NO);
					alert.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
					alert.setHeaderText(tr("Falsche Richtung?"));
					alert.setContentText(toLocal
							? String.format(tr("Gewählt: WGS84 → lokal. %d von %d Punkten sind aber nicht geozentrisch (vermutlich schon lokal). Trotzdem umrechnen?"),
									content[1], content[0] + content[1])
							: String.format(tr("Gewählt: lokal → WGS84. %d von %d Punkten sind aber schon geozentrisch (WGS84 X/Y/Z). Trotzdem umrechnen?"),
									content[0], content[0] + content[1]));
					((Button) alert.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
					((Button) alert.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
					if (!alert.showAndWait().filter(button -> button == ButtonType.YES).isPresent())
						return;
				}
			}
			// Sperre ueber die Kopfzeile: bereits umgerechnete Dateien nicht noch einmal umrechnen, Ruecknahme nur mit demselben P0
			String[] mark;
			try {
				mark = Klein_Method_Functions.Klein_Method_Local_System.readFileMark(input);
			} catch (Exception e) {
				this.resultArea.setText(tr("Umrechnung fehlgeschlagen: ") + e.getMessage());
				return;
			}
			if (mark != null) {
				boolean markLocal = "lokal".equals(mark[0]);
				if (markLocal == toLocal) {
					this.showConversionLock(String.format(tr("Die Datei wurde schon umgerechnet (%s) - ein zweites Umrechnen ist gesperrt."), mark[2]));
					return;
				}
				Klein_Method_Functions.Klein_Method_Local_System.Parameters fileP0 = Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(mark[1]);
				if (markLocal && !Klein_Method_Functions.Klein_Method_Local_System.sameReference(fileP0, this.kleinMethod.getParameter().getLocalSystem())) {
					this.showConversionLock(String.format(tr("Die Datei wurde mit einem anderen P0 ins lokale System umgerechnet (%s). Zurückrechnen nur mit "
							+ "demselben P0 - bitte die Grundwerte oben auf dieses P0 setzen."), mark[2]));
					return;
				}
			}
			int dot = name.lastIndexOf('.');
			String base = dot > 0 ? name.substring(0, dot) : name;
			// Epochen zusammenfassen (Dateiname wie VBA V0.06: _Mittel)
			int epochMode = baselines ? Math.max(0, this.epochComboBox.getItems().indexOf(this.epochComboBox.getValue())) : 0;
			double sigmaFactor = this.kleinMethod.getParameter().getBaselineSigmaFactor(), sigmaMinMm = this.kleinMethod.getParameter().getBaselineSigmaMinMm();
			if (epochMode != Klein_Method_Functions.Klein_Method_Local_System.EPOCHS_SINGLE) {
				try {
					sigmaFactor = Double.parseDouble(this.sigmaFactorField.getText().trim().replace(',', '.'));
					sigmaMinMm = Double.parseDouble(this.sigmaMinField.getText().trim().replace(',', '.'));
				} catch (NumberFormatException e) {
					this.resultArea.setText(tr("σ-Faktor bzw. σ min ist keine Zahl."));
					return;
				}
				if (!(sigmaFactor > 0.0 && sigmaFactor <= 100.0 && sigmaMinMm > 0.0 && sigmaMinMm <= 1000.0)) {
					this.resultArea.setText(tr("σ-Faktor muss zwischen 0 und 100 liegen, σ min zwischen 0 und 1000 mm."));
					return;
				}
				this.kleinMethod.getParameter().setBaselineSigmaFactor(sigmaFactor);
				this.kleinMethod.getParameter().setBaselineSigmaMinMm(sigmaMinMm);
				this.kleinMethod.saveSettings();
				base = base + "_Mittel";
			}
			String outName = baselines ? (toLocal ? base + ".BLallLok" : base + "_WGS84.BLall") : (toLocal ? base + ".FpktLok" : base + "_WGS84.Fpkt");
			java.io.File output = new java.io.File(input.getParentFile(), outName);
			if (output.getAbsoluteFile().equals(input.getAbsoluteFile()))
				output = new java.io.File(input.getParentFile(), base + "_umgerechnet" + outName.substring(base.length()));
			if (output.exists()) {
				Alert alert = new Alert(AlertType.CONFIRMATION, String.format(tr("Die Datei %s gibt es schon. Überschreiben?"), output.getName()), ButtonType.YES, ButtonType.NO);
				alert.setTitle(tr("Klein_Method - Koordinatenumrechnung"));
				alert.setHeaderText(null);
				if (!alert.showAndWait().filter(button -> button == ButtonType.YES).isPresent())
					return;
			}
			try {
				if (epochMode != Klein_Method_Functions.Klein_Method_Local_System.EPOCHS_SINGLE) {
					Klein_Method_Functions.Klein_Method_Local_System.Epoch_Result r = Klein_Method_Functions.Klein_Method_Local_System.convertBaselinesAveraged(input,
							output, toLocal, this.kleinMethod.getParameter().getLocalSystem().copy(), epochMode, sigmaFactor, sigmaMinMm / 1000.0);
					StringBuilder text = new StringBuilder(String.format(tr("%d Basislinien aus %d Epochen gemittelt (%s, %s).\nSpalten: Start Ziel d1 d2 d3 s1 s2 s3 (m), σ = %s × Streuung, mindestens %s mm."),
							r.baselines, r.epochs, epochMode == Klein_Method_Functions.Klein_Method_Local_System.EPOCHS_ROBUST ? tr("robust") : tr("wie VBA V0.06"),
							toLocal ? tr("WGS84 → lokal") : tr("lokal → WGS84"), String.format(java.util.Locale.GERMANY, "%.2f", sigmaFactor),
							String.format(java.util.Locale.GERMANY, "%.1f", sigmaMinMm)));
					if (r.single > 0)
						text.append("\n").append(String.format(tr("%d Basislinie(n) mit nur einer Epoche (σ = Mindestwert)."), r.single));
					if (epochMode == Klein_Method_Functions.Klein_Method_Local_System.EPOCHS_ROBUST)
						text.append("\n").append(String.format(tr("Verworfene Epochen: %d"), r.rejected)).append(r.rejectedLines.isEmpty() ? "" : "\n  " + String.join("\n  ", r.rejectedLines));
					if (r.roundTripPairs > 0)
						text.append("\n").append(String.format(tr("Hin/Rück-Prüfung: %d Basislinie(n) in beiden Richtungen, %d auffällig."), r.roundTripPairs, r.roundTripWarnings.size()))
								.append(r.roundTripWarnings.isEmpty() ? "" : "\n  " + String.join("\n  ", r.roundTripWarnings));
					if (r.unchanged > 0)
						text.append("\n").append(String.format(tr("%d Zeile(n) ohne Zahlen übersprungen."), r.unchanged));
					Klein_Method_Functions.Klein_Method_Local_System.stampFile(output,
							Klein_Method_Functions.Klein_Method_Local_System.fileMark(toLocal, this.kleinMethod.getParameter().getLocalSystem()));
					text.append("\n").append(tr("Gespeichert unter:")).append("\n").append(output.getAbsolutePath());
					text.append("\n").append(tr("Kopfzeile „# Klein_Method: …“ mit P0 geschrieben - sperrt ein zweites Umrechnen."));
					this.resultArea.setText(text.toString());
					this.kleinMethod.getLogger().log("Klein_Method: Koordinatenumrechnung Basislinien gemittelt (" + (epochMode == Klein_Method_Functions.Klein_Method_Local_System.EPOCHS_ROBUST
							? "robust" : "Mittel") + ", Faktor " + sigmaFactor + ", min " + sigmaMinMm + " mm) " + input.getName() + " -> " + output.getName() + ": "
							+ r.baselines + " Basislinien aus " + r.epochs + " Epochen, verworfen " + r.rejected + ", Hin/Rueck " + r.roundTripPairs + " Paare, auffaellig "
							+ r.roundTripWarnings.size());
					for (String line : r.rejectedLines)
						this.kleinMethod.getLogger().log("Klein_Method: Basislinien-Mittel - " + line);
					for (String line : r.roundTripWarnings)
						this.kleinMethod.getLogger().log("Klein_Method: Basislinien-Mittel - " + line);
					return;
				}
				Klein_Method_Functions.Klein_Method_Local_System.File_Result result = Klein_Method_Functions.Klein_Method_Local_System.convertFile(input, output,
						baselines, toLocal, this.kleinMethod.getParameter().getLocalSystem().copy());
				Klein_Method_Functions.Klein_Method_Local_System.stampFile(output,
						Klein_Method_Functions.Klein_Method_Local_System.fileMark(toLocal, this.kleinMethod.getParameter().getLocalSystem()));
				String text = String.format(tr("%d %s umgerechnet (%s).\nGespeichert unter:\n%s"), result.converted, baselines ? tr("Basislinien") : tr("Punkte"),
						toLocal ? tr("WGS84 → lokal") : tr("lokal → WGS84"), output.getAbsolutePath());
				if (result.unchanged > 0)
					text += "\n" + String.format(tr("%d Zeile(n) ohne Zahlen unverändert übernommen."), result.unchanged);
				text += "\n" + tr("Kopfzeile „# Klein_Method: …“ mit P0 geschrieben - sperrt ein zweites Umrechnen.");
				this.resultArea.setText(text);
				this.kleinMethod.getLogger().log("Klein_Method: Koordinatenumrechnung Datei " + input.getName() + " -> " + output.getName() + ": " + result.converted
						+ " umgerechnet, " + result.unchanged + " unveraendert, Parameter " + this.kleinMethod.getParameter().getLocalSystem().encode());
			} catch (Exception e) {
				this.resultArea.setText(tr("Umrechnung fehlgeschlagen: ") + e.getMessage());
				this.kleinMethod.getLogger().log("Klein_Method: Koordinatenumrechnung Datei fehlgeschlagen: " + e.getMessage());
			}
		}
	}

	// Kartenfenster als eingebettete Klasse. exakte Umrechnung je Punkt, geozentrisch WGS84, Projektionsordner
	// (PROJ-Zeilen), Erkennung, Plausibilitaet.
	/**
	 * Eigenes, nicht angedocktes Fenster mit OpenStreetMap-Hintergrund.
	 *
	 * Darstellung 1:1 aus dem JAG3D-Netzplot: die Ebenen des Netzplots zeichnen sich mit ihrem eigenen Code und ihren
	 * eigenen Einstellungen auf die Karte. Gesteuert wird ausschliesslich im Netzplot.
	 *
	 * exakte Umrechnung je Punkt: waehrend des Kartenzeichnens erhaelt jeder Punkt der Netzplot-Ebenen (Punkte,
	 * Beobachtungslinien, Verschiebungspfeile) seine exakt umgerechnete Lage in der Kartenebene (lokal ->
	 * Breite/Laenge -> Web-Mercator, in Metern am Bildmittelpunkt); Ellipsen und Vektoren werden mit der oertlichen
	 * Ableitung der Abbildung mitgedreht und skaliert. Direkt nach dem Zeichnen werden alle Originalwerte
	 * wiederhergestellt (alles im JavaFX-Thread, der Netzplot sieht die Umrechnung nie). Dadurch keine Verzerrung,
	 * auch bei grossen Netzen, Schrift und Symbole bleiben aufrecht.
	 *
	 * Kartenprojektion (Dropdown):
	 *
	 * Geozentrisch WGS84 (X/Y/Z; JAG3D y = Y, x = X, z = Z, Schalter "X/Y vertauscht"); Konfidenzellipsen und
	 * Verschiebungspfeile ausgeblendet (JAG3D rechnet sie nur in der X/Y-Ebene), Residuen-/Hauptkomponentenpfeile
	 * exakt (mit Z-Anteil)
	 *
	 * UTM mit Zonenkennung im Rechtswert (ETRS89)
	 *
	 * je Datei im Projektionsordner eine PROJ-Definition (tmerc/utm, +towgs84)
	 *
	 * per Klick einpassen (nur Randpunkte, 30 %, Warnung ab 5 m)
	 *
	 * Nur Anzeige - die Ausgleichung und die Pruefsumme bleiben unberuehrt.
	 */
	public static class Klein_Method_Map_Window {

		private static final double PASS_POINT_MIN_DISTANCE_FRACTION = 0.30;
		private static final double PASS_POINT_TOLERANCE_METER = 5.0;
		private static final int MAX_TILE_ZOOM = 19;
		private static final double MIN_ZOOM = 2.0, MAX_ZOOM = 20.0;
		private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
		private static final String ATTRIBUTION = "© OpenStreetMap-Mitwirkende";
		private static final long TILE_CACHE_MAX_AGE_MS = 7L * 24L * 3600L * 1000L;
		private static final String SETTING_MODE = "MODE";
		private static final String SETTING_SWAP_XY = "SWAPXY";
		// Koordinatenart zum Zeitpunkt der gespeicherten Auswahl (ECEF/UTM/PROJ/LOCAL)
		private static final String SETTING_KIND = "KIND";

		/** Koordinatenart eines einzelnen Punkts. */
		private enum Point_Kind {
			ECEF("geozentrisch"), UTM("UTM mit Zonenkennung"), PROJ("projiziert ohne Kennung"), LOCAL("lokal");

			final String label;

			Point_Kind(String label) {
				this.label = label;
			}
		}

		/** ordnet einen Punkt (y, x, z) einer Koordinatenart zu. */
		private static Point_Kind classify(double[] p) {
			double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
			if (r > 6.30e6 && r < 6.42e6) {
				// Hoehe ist unabhaengig von der X/Y-Vertauschung
				double h = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(p[1], p[0], p[2])[2];
				if (h > -500.0 && h < 9000.0)
					return Point_Kind.ECEF;
			}
			int zone = Klein_Method_Functions.Klein_Method_Geo.zoneOfEasting(p[0]);
			double eastPart = p[0] - zone * 1000000.0;
			if (zone >= 1 && zone <= 60 && eastPart > 100000.0 && eastPart < 900000.0 && p[1] > 0.0 && p[1] < 9350000.0)
				return Point_Kind.UTM;
			if (p[0] > 100000.0 && p[0] < 900000.0 && p[1] > 5000000.0 && p[1] < 6200000.0)
				return Point_Kind.PROJ;
			return Point_Kind.LOCAL;
		}

		private final Map<String, Point_Kind> kindByName = new java.util.HashMap<String, Point_Kind>();
		private Point_Kind majorityKind = Point_Kind.LOCAL;
		private String mixedKindsText = "";
		private static final double EARTH_CIRCUMFERENCE = 2.0 * Math.PI * 6378137.0;

		/** Eintrag im Dropdown "Kartenprojektion". */
		private static final class Map_Source {
			enum Kind { ECEF, UTM_ZONE, DEFINITION, LOCAL_P0, CLICK }

			final Kind kind;
			final Klein_Method_Functions.Klein_Method_Geo.Projection_Definition definition;

			Map_Source(Kind kind, Klein_Method_Functions.Klein_Method_Geo.Projection_Definition definition) {
				this.kind = kind;
				this.definition = definition;
			}

			String key() {
				String key = this.kind == Kind.DEFINITION ? "FILE:" + this.definition.name : this.kind.name();
				return key.length() > 64 ? key.substring(0, 64) : key;
			}

			@Override
			public String toString() {
				switch (this.kind) {
				case ECEF:
					return tr("Geozentrisch WGS84 (X/Y/Z)");
				case UTM_ZONE:
					return tr("UTM mit Zonenkennung im Rechtswert (ETRS89)");
				case DEFINITION:
					return this.definition.name;
				case LOCAL_P0:
					return tr("Lokal (ENh) um P0 - Klein_Method-Umrechnung");
				default:
					return tr("Per Klick einpassen");
				}
			}
		}

		/** Zeile der Passpunkt-Tabelle. */
		public static final class Pass_Point_Row {
			private final String name;
			private final double latitude, longitude, residual;

			Pass_Point_Row(String name, double latitude, double longitude, double residual) {
				this.name = name;
				this.latitude = latitude;
				this.longitude = longitude;
				this.residual = residual;
			}

			public String getName() {
				return this.name;
			}

			public String getLatitude() {
				return String.format(java.util.Locale.GERMANY, "%.6f", this.latitude);
			}

			public String getLongitude() {
				return String.format(java.util.Locale.GERMANY, "%.6f", this.longitude);
			}

			public String getResidual() {
				return Double.isNaN(this.residual) ? "–" : String.format(java.util.Locale.GERMANY, "%.1f", this.residual);
			}

			boolean isOutlier() {
				return !Double.isNaN(this.residual) && this.residual > PASS_POINT_TOLERANCE_METER;
			}
		}

		/** Originalzustand eines Netzplot-Punkts waehrend des Kartenzeichnens. */
		private static final class Saved_Point {
			final org.applied_geodesy.jag3d.ui.graphic.coordinate.WorldCoordinate coordinate;
			final double majorAxis, minorAxis, angle;
			final double[] vectors; // residualX/Y/Z, principalComponentX/Y/Z
			final boolean visible;

			Saved_Point(org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint point) {
				this.coordinate = point.getCoordinate();
				this.majorAxis = point.getMajorAxis();
				this.minorAxis = point.getMinorAxis();
				this.angle = point.getAngle();
				this.vectors = new double[] { point.getResidualX(), point.getResidualY(), point.getResidualZ(), point.getPrincipalComponentX(),
						point.getPrincipalComponentY(), point.getPrincipalComponentZ() };
				this.visible = point.isVisible();
			}
		}

		private static final String[] VECTOR_FIELDS = { "residualX", "residualY", "residualZ", "principalComponentX", "principalComponentY",
				"principalComponentZ" };

		private static Klein_Method_Map_Window instance;
		/** Ergebnis der Pruefung der GNSS-Stuetzung: Punktname -> 1 gelb / 2 rot (ausserhalb der GNSS-Stuetzung). */
		public static volatile Map<String, Integer> gnssSupportLevels = new java.util.HashMap<String, Integer>();
		/** Prognose aus der Messplanung: 1 gelb, 2 rot (Fehlerellipse ab 1,2- bzw. 1,5-fach des Medians). */
		public static volatile Map<String, Integer> prognosisLevels = new java.util.HashMap<String, Integer>();
		/** Parameter des lokalen Systems (Projekt-Umrechnung oder eingestellt). */
		private Klein_Method_Functions.Klein_Method_Local_System.Parameters localSystem = null;
		private boolean localSystemFromProject = false;

		private final Klein_Method kleinMethod;
		private final javafx.stage.Stage stage;
		private final javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(800, 600);
		private final ComboBox<Map_Source> modeComboBox = new ComboBox<Map_Source>();
		private final CheckBox swapXyCheckBox = new CheckBox("X/Y vertauscht");
		private final Label statusLabel = new Label();
		private final Label plausibilityLabel = new Label();
		private final VBox clickPanel;
		private final ComboBox<String> passPointComboBox = new ComboBox<String>();
		private final ToggleButton setPassPointButton = new ToggleButton("In Karte klicken");
		private final javafx.scene.control.TableView<Pass_Point_Row> passPointTable = new javafx.scene.control.TableView<Pass_Point_Row>();
		private final Label fitLabel = new Label();
		private final TextField gotoField = new TextField();
		// Bedienleiste wie im JAG3D-Netzplot: Verschieben (Standard) bzw. Fensterzoom per Ziehen
		private final ToggleButton toolMoveButton = new ToggleButton(), toolWindowZoomButton = new ToggleButton();
		private double[] windowZoomStart = null, windowZoomCurrent = null;
		private String lastFeatureZoomPoint = null, highlightName = null;
		private double lastFeatureZoomScale = 1.0;
		private final javafx.animation.Timeline refreshTimeline;
		// die 5 groessten Fehler, rot umrandet
		private static final int MAX_ERROR_COUNT = 5;
		private final ToggleButton maxErrorButton = new ToggleButton("Max Fehler");
		/** Messart-Symbole des Netzplots (an den Linienenden) in der Karte zeigen - Standard aus, sie liegen nicht auf den Punkten. */
		private final CheckBox symbolCheckBox = new CheckBox(tr("Messart-Symbole am Punkt"));
		/** Netzplot nach dem Kartenbild einmal neu zeichnen (zusammengefasst). */
		private boolean netzplotRedrawPending = false;
		// Festpunkt-Empfehlung in der Karte (nur dynamische Ausgleichung)
		private final ToggleButton recommendationButton = new ToggleButton(tr("Festpunkt-Empfehlung"));
		private Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result recommendation;
		// Anschlussbereiche markieren (Kreis / Viereck / Polygon), im Projekt gespeichert
		private final List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> areas = new ArrayList<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area>();
		private boolean areasLoaded = false;
		private final ToggleGroup areaToolGroup = new ToggleGroup();
		private final ToggleButton areaCircleButton = new ToggleButton(tr("Kreis"));
		private final ToggleButton areaRectangleButton = new ToggleButton(tr("Viereck"));
		private final ToggleButton areaPolygonButton = new ToggleButton(tr("Polygon"));
		private final Button areaDeleteButton = new Button(tr("Bereich l\u00F6schen"));
		private HBox areaToolbar;
		private double[] areaStartScreen, areaCurrentScreen;
		private final List<double[]> polygonLatLon = new ArrayList<double[]>();
		private final List<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row> maxErrors =
				new ArrayList<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row>();
		private boolean maxErrorsArePoints = false;
		private boolean maxErrorsLoaded = false;
		private final Map<String, double[]> localByName = new java.util.HashMap<String, double[]>();
		// "Grobe Fehler bereinigen" auch in der Karte; herausgenommene grobe Fehler gestrichelt
		private final Button grossErrorButton = new Button("Grobe Fehler bereinigen");
		private final List<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row> excludedErrors =
				new ArrayList<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row>();
		private boolean ignoreModeEvents = false;

		// Ansicht: Mittelpunkt in Weltpixeln der Zoomstufe 0, stufenloser Zoom
		private double centerX = 0.0, centerY = 0.0, zoom = 6.0;
		private boolean viewInitialized = false;
		private double pressX, pressY, lastDragX, lastDragY;
		private boolean dragged = false;

		// Netz (a-priori-Koordinaten der aktiven Punkte: y, x, z)
		private final List<String> pointNames = new ArrayList<String>();
		private double[][] localPoints = new double[0][];
		private final Map<String, Double> heightByName = new java.util.HashMap<String, Double>();
		private final java.util.Set<String> hullPointNames = new java.util.LinkedHashSet<String>();
		private double networkExtent = 0.0;
		private final Map<String, double[]> passPoints = new java.util.LinkedHashMap<String, double[]>();
		private final Map<String, String> passPointBlockReason = new java.util.HashMap<String, String>();
		private final List<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition> definitions =
				new ArrayList<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition>();
		private final List<String> definitionErrors = new ArrayList<String>();
		private Map_Source source = new Map_Source(Map_Source.Kind.CLICK, null);
		private boolean swapXy = false;
		// Halbkugel fuer "UTM mit Zonenkennung" (Sued: Hochwert mit 10 000 000 m Versatz)
		private static final String SETTING_SOUTH = "SOUTH";
		private static final double UTM_SOUTH_FALSE_NORTHING = 10000000.0;
		private final ComboBox<String> hemisphereComboBox = new ComboBox<String>();
		private final Label hemisphereLabel = new Label("Halbkugel:");
		private boolean southHemisphere = false;
		private String detectedText = "";
		private boolean autoSelectedGaussKrueger = false;
		private final java.util.Set<String> outlierNames = new java.util.HashSet<String>();
		/** weit entfernte, aber ueber Messungen verbundene Punkte (z. B. GNSS-Referenzstationen): gezeichnet, nur nicht in "Netz anzeigen". */
		private final java.util.Set<String> farConnectedNames = new java.util.HashSet<String>();
		private Klein_Method_Functions.Klein_Method_Geo.Helmert2D helmert = null;
		private int helmertZone = 0;
		private final List<String> helmertNames = new ArrayList<String>();
		private String lastLayerSignature = "";
		// Breite/Laenge je Netzplot-Punkt (schwache Schluessel: neu geladene Punkte ersetzen alte)
		private final Map<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, double[]> latLonCache =
				new java.util.WeakHashMap<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, double[]>();

		// OSM-Kacheln: Speicher-Cache (LRU), Platten-Cache, 2 Ladefaeden (OSM-Nutzungsregeln)
		private final Map<String, javafx.scene.image.Image> tileCache = new java.util.LinkedHashMap<String, javafx.scene.image.Image>(256, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, javafx.scene.image.Image> eldest) {
				return this.size() > 600;
			}
		};
		private final java.util.Set<String> tilesLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();
		private final Map<String, Long> tilesFailed = new java.util.concurrent.ConcurrentHashMap<String, Long>();
		private final java.util.concurrent.ExecutorService tileLoader = java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "Klein_Method_OSM_Tiles");
			thread.setDaemon(true);
			return thread;
		});

		/** nach eigenen Datenaenderungen (z.B. "Grobe Fehler bereinigen") die offene Karte neu laden. */
		public static void notifyDataChanged() {
			if (instance != null && instance.stage.isShowing())
				instance.reloadNetwork();
		}

		/** Oeffnet das Kartenfenster (bzw. holt es nach vorn) und laedt die Netzdaten neu. */
		public static void open(Klein_Method kleinMethod) {
			if (instance == null)
				instance = new Klein_Method_Map_Window(kleinMethod);
			instance.stage.show();
			instance.stage.toFront();
			instance.reloadNetwork();
		}

		/** offene Karte neu laden (z. B. nach dem Import einer Gitterdatei). */
		public static void reloadIfOpen() {
			if (instance != null && instance.stage.isShowing())
				instance.reloadNetwork();
		}

		/** Projektionsordner (PROJ-Definitionen, je Datei eine Kartenprojektion). */
		public static java.io.File projectionFolder() {
			return Klein_Method_Functions.Klein_Method_Geo.projectionFolder();
		}

		private Klein_Method_Map_Window(Klein_Method kleinMethod) {
			this.kleinMethod = kleinMethod;
			this.stage = new javafx.stage.Stage();
			this.stage.setTitle("Klein_Method – Karte (OpenStreetMap)");

			// --- Kopfzeile ---
			this.modeComboBox.setTooltip(new Tooltip("Kartenprojektion: wie die Netzkoordinaten auf die Karte gebracht werden.\n"
					+ "Die Koordinatenart wird beim Öffnen erkannt und vorgewählt (Statuszeile).\n"
					+ "Geozentrisch WGS84: X/Y/Z-Koordinaten, exakt umgerechnet.\n"
					+ "UTM mit Zonenkennung: Rechtswert mit Zone vorn (z. B. 33 412 345,678).\n"
					+ "Weitere Einträge: Dateien aus dem Projektionsordner (Knopf „Projektionen …“).\n"
					+ "Per Klick einpassen: für lokale Koordinaten - mindestens 2 Randpunkte in der Karte zuordnen.\n"
					+ "Nur Anzeige, die Ausgleichung bleibt unberührt."));
			this.modeComboBox.valueProperty().addListener((obs, oldSource, newSource) -> {
				if (newSource == null || this.ignoreModeEvents)
					return;
				// passt keiner der Punkte zur gewaehlten Projektion (z. B. "UTM mit Zonenkennung" bei Punkten im lokalen
				// System ENh), blieb die Karte leer - stattdessen die passende Projektion waehlen und den Grund nennen
				Map_Source fitting = this.fittingSourceIfNoneCompatible(newSource);
				if (fitting != null) {
					this.detectedText = String.format(tr("„%s“ passt zu keinem Punkt (Koordinaten: %s) - die Karte zeigt sie mit „%s“."), newSource,
							this.majorityKind.label, fitting);
					this.kleinMethod.getLogger().log("Klein_Method: Karte - Projektion " + newSource.key() + " passt zu keinem Punkt (" + this.majorityKind
							+ "), " + fitting.key() + " verwendet.");
					Platform.runLater(() -> this.modeComboBox.setValue(fitting));
					return;
				}
				this.source = newSource;
				this.autoSelectedGaussKrueger = false;
				String key = newSource.key();
				String kind = this.majorityKind.name();
				this.runDatabaseTask(() -> {
					this.kleinMethod.getDatabaseHandler().storeMapSetting(SETTING_MODE, key);
					this.kleinMethod.getDatabaseHandler().storeMapSetting(SETTING_KIND, kind);
				}, null);
				this.recomputeGeoreference();
				this.zoomToNetwork();
			});
			this.swapXyCheckBox.setTooltip(new Tooltip("Nur bei geozentrischen Koordinaten: Standard ist JAG3D-Rechtswert y = geozentrisch Y,\n"
					+ "Hochwert x = X, Höhe z = Z. Liegt das Netz auf der Karte an völlig falscher Stelle (z. B. in Asien\n"
					+ "statt in Deutschland), wurden X und Y beim Import vertauscht - dann diesen Haken setzen."));
			this.swapXyCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
				if (this.ignoreModeEvents)
					return;
				this.swapXy = newValue;
				this.runDatabaseTask(() -> this.kleinMethod.getDatabaseHandler().storeMapSetting(SETTING_SWAP_XY, String.valueOf(newValue)), null);
				this.recomputeGeoreference();
				this.zoomToNetwork();
			});
			Button projectionFolderButton = new Button("Projektionen …");
			projectionFolderButton.setTooltip(new Tooltip("Weitere Kartenprojektion hinzufügen:\n"
					+ "1. epsg.io im Browser öffnen und das System suchen, z. B. „DHDN Gauß-Krüger 4“ oder „31468“.\n"
					+ "2. Das Ergebnis mit der passenden EPSG-Nummer anklicken.\n"
					+ "3. Unten bei „Export“ das Format „PROJ.4“ wählen und den Text kopieren (beginnt mit +proj=).\n"
					+ "4. Mit diesem Knopf den Projektionsordner öffnen.\n"
					+ "5. Dort eine neue Textdatei anlegen - der Dateiname wird der Name im Dropdown, z. B. „DHDN GK Streifen 4.txt“.\n"
					+ "6. Den kopierten Text als einzige Zeile einfügen und speichern.\n"
					+ "7. In der Karte „Neu laden“ klicken - das System steht dann im Dropdown.\n\n"
					+ "Unterstützt: +proj=tmerc und +proj=utm mit +towgs84 (3 oder 7 Werte) oder NTv2-Gitter (+nadgrids=datei.gsb).\n"
					+ "NTv2-Gitterdateien (*.gsb) einfach in den Ordner legen (oder im Tab \u201EGitterdatei importieren \u2026\u201C):\n"
					+ "die Gauß-Krüger-Streifen ihres Startsystems erscheinen dann automatisch - keine Textdatei nötig.\n"
					+ "Nicht unterstützt: andere Projektionsarten (z. B. Lambert).\n"
					+ "Ungültige Dateien meldet die Karte mit Grund in der Statuszeile.\n"
					+ "Wichtig: Bei EPSG-Systemen mit Zonenkennung im Rechtswert (z. B. +x_0=4500000) muss der\n"
					+ "Rechtswert im Projekt die Kennung auch enthalten."));
			projectionFolderButton.setOnAction(event -> this.openProjectionFolder());
			Button zoomNetworkButton = new Button("Netz anzeigen");
			zoomNetworkButton.setTooltip(new Tooltip("Zoomt auf das gesamte Netz (bzw. auf die Passpunkte, solange das Netz noch nicht eingepasst ist)."));
			zoomNetworkButton.setOnAction(event -> this.zoomToNetwork());
			Button reloadButton = new Button("Neu laden");
			reloadButton.setTooltip(new Tooltip("Liest Punkte, Passpunkte, Projektionsordner und Projektionseinstellung neu ein\n"
					+ "und lädt die Ebenen des Netzplots neu."));
			reloadButton.setOnAction(event -> this.reloadNetwork());
			// Halbkugel (nur bei "UTM mit Zonenkennung")
			this.hemisphereComboBox.getItems().addAll("Nord", "S\u00FCd");
			this.hemisphereComboBox.setValue("Nord");
			this.hemisphereComboBox.setTooltip(new Tooltip("Nur bei \u201EUTM mit Zonenkennung\u201C: Halbkugel der Koordinaten.\n"
					+ "Auf der S\u00FCdhalbkugel z\u00E4hlt der UTM-Hochwert von 10 000 000 m am \u00C4quator abw\u00E4rts - ein Hochwert wie\n"
					+ "5 700 000 ist daher doppeldeutig (Nord: ca. 51\u00B0 N, S\u00FCd: ca. 39\u00B0 S) und l\u00E4sst sich nicht automatisch erkennen.\n"
					+ "Standard: Nord (Deutschland/Europa). Die Auswahl wird im Projekt gespeichert.\n"
					+ "Geozentrisch, Projektionsdateien (+south) und Einpassen per Klick brauchen diese Angabe nicht."));
			this.hemisphereLabel.setTooltip(this.hemisphereComboBox.getTooltip());
			this.hemisphereComboBox.valueProperty().addListener((obs, oldValue, newValue) -> {
				if (newValue == null || this.ignoreModeEvents)
					return;
				this.southHemisphere = !"Nord".equals(newValue);
				String value = String.valueOf(this.southHemisphere);
				this.runDatabaseTask(() -> this.kleinMethod.getDatabaseHandler().storeMapSetting(SETTING_SOUTH, value), null);
				this.recomputeGeoreference();
				this.zoomToNetwork();
			});
			this.maxErrorButton.setTooltip(new Tooltip("Zeigt die 5 größten Fehler der letzten Ausgleichung in der Karte - rot umrandet,\n"
					+ "farblich nach Rang (1 = dunkelrot = größter Fehler) und mit Wertangabe; Liste oben links.\n"
					+ "Freie und fixe Ausgleichung: die 5 Beobachtungen mit dem größten logPost über alle Typen\n"
					+ "(Linie von Stand- zu Zielpunkt), Angabe: Rang, logPost, Verbesserung v.\n"
					+ "Dynamische Ausgleichung: die 5 beweglichen Anschlusspunkte mit dem größten T_prio,\n"
					+ "Angabe: Rang, T_prio, Punktverschiebung.\n"
					+ "Wird nach jeder Ausgleichung und bei „Neu laden“ neu ermittelt. Erneut klicken = ausblenden.\n"
					+ "Nur Anzeige - an der Berechnung ändert sich nichts."));
			this.grossErrorButton.setTooltip(new Tooltip("Wie im Klein_Method-Tab: nur aktiv, wenn grobe Fehler vermutet werden (lineare Abweichung über der Grenze, Standard 0,10 m).\n"
					+ "Nimmt schrittweise die schlechteste Beobachtung vorübergehend heraus und rechnet neu, danach Bestätigung je Beobachtung.\n"
					+ "Herausgenommene grobe Fehler erscheinen in der Karte rot gestrichelt."));
			this.grossErrorButton.setOnAction(event -> this.kleinMethod.startGrossErrorCleanup());
			Runnable updateGrossButton = () -> {
				boolean active = this.kleinMethod.isGrossErrorsSuspected() && !this.kleinMethod.grossErrorCleanupRunningProperty().get();
				this.grossErrorButton.setDisable(!active);
				this.grossErrorButton.setStyle(active ? "-fx-font-weight: bold; -fx-base: #E53935; -fx-text-fill: white;" : "");
			};
			this.kleinMethod.grossErrorsSuspectedProperty().addListener((obs, o, n) -> updateGrossButton.run());
			this.kleinMethod.grossErrorCleanupRunningProperty().addListener((obs, o, n) -> updateGrossButton.run());
			updateGrossButton.run();
			this.recommendationButton.setTooltip(new Tooltip(tr("Nur dynamische Ausgleichung: zeigt die Festpunkt-Empfehlung in der Karte.\n"
					+ "Bewegliche Anschlusspunkte als Ring in Ampelfarbe (gr\u00FCn / gelb / rot, blau = bereits Festpunkt) mit ihrer Unsicherheit,\n"
					+ "bei langgestreckten Netzen die Achse (blau gestrichelt), bei fl\u00E4chigen die Umrandung der gr\u00FCnen Festpunkte,\n"
					+ "Neupunkte au\u00DFerhalb der Festpunkte als rotes Kreuz. Ergebnis unten links.\n"
					+ "Grenzen und Abst\u00E4nde werden im Klein_Method-Tab eingestellt. Erneut klicken = ausblenden.")));
			this.recommendationButton.selectedProperty().addListener((obs, oldValue, newValue) -> {
				if (newValue)
					this.loadRecommendation();
				else {
					this.recommendation = null;
					this.redraw();
				}
			});
			this.updateRecommendationButtonVisibility();
			this.maxErrorButton.selectedProperty().addListener((obs, oldValue, newValue) -> {
				if (newValue)
					this.loadMaxErrors();
				else {
					this.maxErrors.clear();
					this.maxErrorsLoaded = false;
					this.redraw();
				}
			});
			this.gotoField.setPromptText("Breite, Länge");
			this.gotoField.setPrefColumnCount(12);
			this.gotoField.setTooltip(new Tooltip("Zu einer Stelle springen: geografische Breite und Länge in Grad, z. B. „51,34 12,37“.\n"
					+ "Hilfreich, um beim Einpassen das Netzgebiet zu finden."));
			this.gotoField.setOnAction(event -> this.gotoLatLon(this.gotoField.getText()));
			Button gotoButton = new Button("Gehe zu");
			gotoButton.setTooltip(this.gotoField.getTooltip());
			gotoButton.setOnAction(event -> this.gotoLatLon(this.gotoField.getText()));
			Label modeLabel = new Label("Kartenprojektion:");
			modeLabel.setTooltip(this.modeComboBox.getTooltip());
			this.modeComboBox.setPrefWidth(330);
			HBox header = new HBox(8, modeLabel, this.modeComboBox, this.swapXyCheckBox, this.hemisphereLabel, this.hemisphereComboBox, projectionFolderButton, zoomNetworkButton, reloadButton,
					this.maxErrorButton, this.grossErrorButton, this.recommendationButton, this.symbolCheckBox, new Separator(Orientation.VERTICAL), this.gotoField, gotoButton);
			header.setAlignment(Pos.CENTER_LEFT);
			this.symbolCheckBox.setSelected(false);
			this.symbolCheckBox.setTooltip(new Tooltip(tr("Ein: je Punkt ein Messart-Symbol (Torte aus allen Messarten an diesem Punkt: GNSS violett, Strecke, "
					+ "Richtung, Zenitwinkel farbig) genau auf dem Punkt.\n"
					+ "Aus (Standard): keine Messart-Symbole, nur Punkte und Linien.\n"
					+ "Der JAG3D-Netzplot zeichnet die Symbole ein Stück entlang jeder Linie; laufen viele Linien in dieselbe Richtung (z. B. zu fernen "
					+ "Referenzstationen), wirken sie dort wie eine verschobene Punktwolke - deshalb setzt die Karte sie auf den Punkt.\n"
					+ "Nur Anzeige in der Karte - der Netzplot und seine Einstellungen bleiben unverändert.")));
			this.symbolCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> this.redraw());
			header.setPadding(new Insets(6));
			this.statusLabel.setWrapText(true);
			this.statusLabel.setPadding(new Insets(0, 6, 2, 6));
			this.statusLabel.setTooltip(new Tooltip("Erkannte Koordinatenart und Zustand der Karte."));
			this.plausibilityLabel.setWrapText(true);
			this.plausibilityLabel.setPadding(new Insets(0, 6, 6, 6));
			this.plausibilityLabel.setStyle("-fx-text-fill: #B9770E; -fx-font-weight: bold;");
			this.areaToolbar = this.buildAreaToolbar();
			HBox plotToolbar = this.buildPlotToolbar(zoomNetworkButton, reloadButton);
			VBox top = new VBox(header, plotToolbar, this.areaToolbar, this.statusLabel, this.plausibilityLabel);

			// --- Einpassen per Klick (rechts) ---
			Label passPointLabel = new Label("Passpunkt:");
			this.passPointComboBox.setPrefWidth(170);
			this.passPointComboBox.setTooltip(new Tooltip("Netzpunkt, der in der Karte zugeordnet wird. Wählbar sind nur Randpunkte des Netzes\n"
					+ "(konvexe Hülle) mit mindestens 30 % der Netzausdehnung Abstand zu den bereits gesetzten Passpunkten.\n"
					+ "Nahe beieinander liegende Punkte würden die Darstellung verzerren. Ausgegraute Punkte zeigen den Grund im Tooltip."));
			this.passPointComboBox.setCellFactory(listView -> new javafx.scene.control.ListCell<String>() {
				@Override
				protected void updateItem(String item, boolean empty) {
					super.updateItem(item, empty);
					if (empty || item == null) {
						this.setText(null);
						this.setDisable(false);
						this.setTooltip(null);
						return;
					}
					String reason = Klein_Method_Map_Window.this.passPointBlockReason.get(item);
					boolean isSet = Klein_Method_Map_Window.this.passPoints.containsKey(item);
					this.setText(item + (isSet ? "  ✓" : ""));
					this.setDisable(reason != null);
					this.setStyle(reason != null ? "-fx-text-fill: #9E9E9E;" : "");
					this.setTooltip(new Tooltip(reason != null ? reason
							: isSet ? "Bereits gesetzt - erneutes Klicken in der Karte überschreibt die Lage." : "Randpunkt - wählbar."));
				}
			});
			this.passPointComboBox.valueProperty().addListener((obs, oldValue, newValue) -> {
				if (newValue != null && this.passPointBlockReason.containsKey(newValue))
					Platform.runLater(() -> this.passPointComboBox.setValue(oldValue));
			});
			this.setPassPointButton.setTooltip(new Tooltip("Danach in der Karte auf die Stelle klicken, an der der gewählte Punkt liegt.\n"
					+ "Tipp: vorher weit hineinzoomen - die Klickgenauigkeit bestimmt die Lagegenauigkeit."));
			javafx.scene.control.TableColumn<Pass_Point_Row, String> nameColumn = new javafx.scene.control.TableColumn<Pass_Point_Row, String>("Punkt");
			nameColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(cell.getValue().getName()));
			javafx.scene.control.TableColumn<Pass_Point_Row, String> latColumn = new javafx.scene.control.TableColumn<Pass_Point_Row, String>("Breite");
			latColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(cell.getValue().getLatitude()));
			javafx.scene.control.TableColumn<Pass_Point_Row, String> lonColumn = new javafx.scene.control.TableColumn<Pass_Point_Row, String>("Länge");
			lonColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(cell.getValue().getLongitude()));
			javafx.scene.control.TableColumn<Pass_Point_Row, String> residualColumn = new javafx.scene.control.TableColumn<Pass_Point_Row, String>("Abw. [m]");
			residualColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(cell.getValue().getResidual()));
			this.passPointTable.getColumns().add(nameColumn);
			this.passPointTable.getColumns().add(latColumn);
			this.passPointTable.getColumns().add(lonColumn);
			this.passPointTable.getColumns().add(residualColumn);
			this.passPointTable.setPrefHeight(220);
			this.passPointTable.setPlaceholder(new Label("Noch keine Passpunkte"));
			this.passPointTable.setTooltip(new Tooltip("Zugeordnete Passpunkte. Abweichung nach dem Ausgleich (ab 3 Punkten); "
					+ "rot = mehr als 5 m - Punkt vermutlich falsch geklickt."));
			this.passPointTable.setRowFactory(tableView -> new javafx.scene.control.TableRow<Pass_Point_Row>() {
				@Override
				protected void updateItem(Pass_Point_Row item, boolean empty) {
					super.updateItem(item, empty);
					this.setStyle(!empty && item != null && item.isOutlier() ? "-fx-background-color: #F5B7B1;" : "");
				}
			});
			Button deleteButton = new Button("Passpunkt löschen");
			deleteButton.setTooltip(new Tooltip("Entfernt den in der Tabelle markierten Passpunkt aus dem Projekt."));
			deleteButton.setOnAction(event -> {
				Pass_Point_Row row = this.passPointTable.getSelectionModel().getSelectedItem();
				if (row == null)
					return;
				String name = row.getName();
				this.runDatabaseTask(() -> this.kleinMethod.getDatabaseHandler().deleteMapReference(name), () -> {
					this.passPoints.remove(name);
					this.recomputeGeoreference();
				});
			});
			this.fitLabel.setWrapText(true);
			this.fitLabel.setMaxWidth(300);
			this.fitLabel.setTooltip(new Tooltip("Ergebnis der Einpassung (Helmert, 4 Parameter): Maßstab, Drehung und Prüfung.\n"
					+ "2 Punkte: Warnung, wenn der Maßstab die Strecke um mehr als 5 m verändert.\n"
					+ "Ab 3 Punkten: Warnung, wenn ein Punkt mehr als 5 m abweicht."));
			Label clickHeader = new Label("Einpassen per Klick");
			clickHeader.setStyle("-fx-font-weight: bold;");
			this.clickPanel = new VBox(8, clickHeader, new HBox(6, passPointLabel, this.passPointComboBox), this.setPassPointButton,
					this.passPointTable, deleteButton, this.fitLabel);
			this.clickPanel.setPadding(new Insets(6));
			this.clickPanel.setPrefWidth(320);

			// --- Karte ---
			javafx.scene.layout.Pane canvasPane = new javafx.scene.layout.Pane(this.canvas);
			canvasPane.setMinSize(200, 200);
			this.canvas.widthProperty().bind(canvasPane.widthProperty());
			this.canvas.heightProperty().bind(canvasPane.heightProperty());
			this.canvas.widthProperty().addListener((obs, o, n) -> this.redraw());
			this.canvas.heightProperty().addListener((obs, o, n) -> this.redraw());
			this.canvas.setOnMousePressed(event -> {
				if (this.toolWindowZoomButton.isSelected() && !this.isRectangularAreaTool() && !this.areaPolygonButton.isSelected()) {
					this.windowZoomStart = new double[] { event.getX(), event.getY() };
					this.windowZoomCurrent = this.windowZoomStart.clone();
					return;
				}
				if (this.isRectangularAreaTool()) {
					this.areaStartScreen = new double[] { event.getX(), event.getY() };
					this.areaCurrentScreen = this.areaStartScreen.clone();
					return;
				}
				this.pressX = this.lastDragX = event.getX();
				this.pressY = this.lastDragY = event.getY();
				this.dragged = false;
			});
			this.canvas.setOnMouseDragged(event -> {
				if (this.windowZoomStart != null) {
					this.windowZoomCurrent = new double[] { event.getX(), event.getY() };
					this.redraw();
					return;
				}
				if (this.isRectangularAreaTool() && this.areaStartScreen != null) {
					this.areaCurrentScreen = new double[] { event.getX(), event.getY() };
					this.redraw();
					return;
				}
				double dx = event.getX() - this.lastDragX, dy = event.getY() - this.lastDragY;
				if (Math.abs(event.getX() - this.pressX) + Math.abs(event.getY() - this.pressY) > 3)
					this.dragged = true;
				double factor = Math.pow(2.0, this.zoom);
				this.centerX -= dx / factor;
				this.centerY -= dy / factor;
				this.lastDragX = event.getX();
				this.lastDragY = event.getY();
				this.redraw();
			});
			this.canvas.setOnMouseReleased(event -> {
				if (this.windowZoomStart != null) {
					double[] a = this.windowZoomStart, b = new double[] { event.getX(), event.getY() };
					this.windowZoomStart = null;
					this.windowZoomCurrent = null;
					if (Math.abs(b[0] - a[0]) > 5 && Math.abs(b[1] - a[1]) > 5)
						this.zoomToScreenRectangle(a, b);
					this.redraw();
					return;
				}
				if (this.isRectangularAreaTool()) {
					if (this.areaStartScreen != null) {
						double[] start = this.areaStartScreen, end = new double[] { event.getX(), event.getY() };
						this.areaStartScreen = null;
						this.areaCurrentScreen = null;
						if (Math.hypot(end[0] - start[0], end[1] - start[1]) > 5)
							this.finishRectangularArea(start, end);
						this.redraw();
					}
					return;
				}
				if (this.areaPolygonButton.isSelected() && !this.dragged) {
					if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY || event.getClickCount() >= 2)
						this.finishPolygonArea();
					else
						this.polygonLatLon.add(this.screenToLatLon(event.getX(), event.getY()));
					this.redraw();
					return;
				}
				if (!this.dragged && this.setPassPointButton.isSelected())
					this.assignPassPoint(event.getX(), event.getY());
			});
			this.canvas.setOnMouseMoved(event -> {
				if (this.areaPolygonButton.isSelected() && !this.polygonLatLon.isEmpty()) {
					this.areaCurrentScreen = new double[] { event.getX(), event.getY() };
					this.redraw();
				}
			});
			this.canvas.setOnScroll(event -> {
				double step = event.getDeltaY() > 0 ? 0.25 : event.getDeltaY() < 0 ? -0.25 : 0.0;
				if (step == 0.0)
					return;
				double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, this.zoom + step));
				double[] anchor = this.screenToWorld(event.getX(), event.getY());
				this.zoom = newZoom;
				double factor = Math.pow(2.0, this.zoom);
				this.centerX = anchor[0] - (event.getX() - 0.5 * this.canvas.getWidth()) / factor;
				this.centerY = anchor[1] - (event.getY() - 0.5 * this.canvas.getHeight()) / factor;
				this.redraw();
			});
			Tooltip.install(this.canvas, new Tooltip("Ziehen = verschieben, Mausrad = zoomen.\n"
					+ "Die Darstellung der Punkte, Beobachtungen, Ellipsen und Pfeile wird im JAG3D-Netzplot gesteuert."));

			BorderPane root = new BorderPane();
			root.setTop(top);
			root.setCenter(canvasPane);
			root.setRight(this.clickPanel);
			this.stage.setScene(new javafx.scene.Scene(root, 1250, 800));

			// Aenderungen im Netzplot (Ebenen, Farben, Daten) erkennen und neu zeichnen
			this.refreshTimeline = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1.5), event -> {
				String signature = this.layerSignature();
				if (!signature.equals(this.lastLayerSignature)) {
					this.redraw();
					if (this.maxErrorButton.isSelected())
						this.loadMaxErrors();
					this.updateRecommendationButtonVisibility();
					if (this.recommendationButton.isSelected())
						this.loadRecommendation();
				}
			}));
			this.refreshTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
			this.stage.setOnHidden(event -> this.refreshTimeline.stop());
			this.stage.setOnShown(event -> this.refreshTimeline.play());
		}

		// ------------------------------------------------------------------
		// Daten
		// ------------------------------------------------------------------

		private interface Database_Task {
			void run() throws SQLException;
		}

		/** Datenbankzugriff im Hintergrund, unter der gemeinsamen Ausgleichungs-Sperre (max. 3 s warten). */
		private void runDatabaseTask(Database_Task task, Runnable onSuccessFx) {
			Thread thread = Klein_Method.busyThread(() -> {
				java.util.concurrent.locks.ReentrantLock lock = Klein_Method.getAdjustmentLock();
				boolean locked = false;
				try {
					locked = lock.tryLock(3, java.util.concurrent.TimeUnit.SECONDS);
					if (!locked) {
						Platform.runLater(() -> this.statusLabel.setText("Projektdatenbank gerade belegt (Berechnung läuft) - bitte später erneut."));
						return;
					}
					task.run();
					if (onSuccessFx != null)
						Platform.runLater(onSuccessFx);
				} catch (Exception e) {
					this.kleinMethod.getLogger().log("Klein_Method: Karte - Datenbankfehler: " + e.getMessage());
					Platform.runLater(() -> this.statusLabel.setText("Datenbankfehler: " + e.getMessage()));
				} finally {
					if (locked)
						lock.unlock();
				}
			}, "Klein_Method_Map_Database");
			thread.setDaemon(true);
			thread.start();
		}

		/** Projektionsordner anlegen (mit Vorlagen) und im Dateimanager oeffnen. */
		private void openProjectionFolder() {
			java.io.File folder = projectionFolder();
			this.ensureProjectionFolder();
			Thread thread = new Thread(() -> {
				try {
					if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
						Desktop.getDesktop().open(folder);
					else
						Platform.runLater(() -> this.statusLabel.setText("Dateimanager nicht automatisch öffenbar - Ordner: " + folder.getAbsolutePath()));
				} catch (IOException e) {
					Platform.runLater(() -> this.statusLabel.setText("Projektionsordner nicht öffenbar (" + e.getMessage() + ") - Ordner: "
							+ folder.getAbsolutePath()));
				}
			}, "Klein_Method_Open_Projection_Folder");
			thread.setDaemon(true);
			thread.start();
		}

		/** Legt den Projektionsordner mit den Vorlagen an. */
		private void ensureProjectionFolder() {
			for (String note : Klein_Method_Functions.Klein_Method_Geo.ensureProjectionFolder())
				this.kleinMethod.getLogger().log("Klein_Method: Karte - " + note);
		}

		/** Liest alle *.txt-Dateien des Projektionsordners (erste Zeile, die mit +proj= beginnt). */
		private void loadProjectionDefinitions() {
			this.ensureProjectionFolder();
			this.definitions.clear();
			this.definitionErrors.clear();
			// geographische Definitionen (aus Gitterdateien) sind nur fuer den Export - die Karte braucht ebene
			// Systeme
			for (Klein_Method_Functions.Klein_Method_Geo.Projection_Definition d : Klein_Method_Functions.Klein_Method_Geo.loadProjectionDefinitions(this.definitionErrors))
				if (!d.isGeographic())
					this.definitions.add(d);
		}

		private void reloadNetwork() {
			try {
				org.applied_geodesy.jag3d.ui.graphic.UIGraphicPaneBuilder.getInstance().getLayerManager().redraw();
			} catch (Exception e) {
				this.kleinMethod.getLogger().log("Klein_Method: Karte - Netzplot nicht ladbar: " + e.getMessage());
			}
			this.loadProjectionDefinitions();
			final List<String> names = new ArrayList<String>();
			final List<double[]> coords = new ArrayList<double[]>();
			final Map<String, double[]> references = new java.util.LinkedHashMap<String, double[]>();
			final List<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row> excluded =
					new ArrayList<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row>();
			final String[] settings = new String[4];
			final Klein_Method_Functions.Klein_Method_Local_System.Parameters[] projectLocal = new Klein_Method_Functions.Klein_Method_Local_System.Parameters[1];
			final List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> loadedAreas = new ArrayList<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area>();
			final org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType[] projection =
					new org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType[1];
			this.runDatabaseTask(() -> {
				org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler handler = this.kleinMethod.getDatabaseHandler();
				for (org.applied_geodesy.klein_method.database.Klein_Method_Point_Coordinate_Row row : handler.readAllPointAprioriCoordinatesForFangkreisScan()) {
					if (row.getRechtswert() == null || row.getHochwert() == null)
						continue;
					names.add(row.getName());
					coords.add(new double[] { row.getRechtswert(), row.getHochwert(), row.getHoehe() == null ? 0.0 : row.getHoehe() });
				}
				references.putAll(handler.readMapReferences());
				for (org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row row : handler.readGrossErrorEntries())
					if (org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.GROSS_ERROR_EXCLUDED.equals(row.status))
						excluded.add(row);
				settings[0] = handler.readMapSetting(SETTING_MODE);
				settings[1] = handler.readMapSetting(SETTING_SWAP_XY);
				settings[3] = handler.readMapSetting(SETTING_SOUTH);
				settings[2] = handler.readMapSetting(SETTING_KIND);
				projection[0] = handler.readProjectionType();
				projectLocal[0] = Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(handler.readProjectSetting(
						org.applied_geodesy.klein_method.Klein_Method.SETTING_LOCAL_SYSTEM));
				loadedAreas.addAll(handler.readConnectionAreas());
			}, () -> {
				this.areas.clear();
				this.areas.addAll(loadedAreas);
				this.areasLoaded = true;
				this.pointNames.clear();
				this.pointNames.addAll(names);
				this.localPoints = coords.toArray(new double[0][]);
				this.heightByName.clear();
				this.localByName.clear();
				for (int i = 0; i < names.size(); i++) {
					this.heightByName.put(names.get(i), this.localPoints[i][2]);
					this.localByName.put(names.get(i), this.localPoints[i]);
				}
				this.hullPointNames.clear();
				for (Integer index : Klein_Method_Functions.Klein_Method_Geo.convexHull(this.localPoints))
					this.hullPointNames.add(this.pointNames.get(index));
				this.networkExtent = Klein_Method_Functions.Klein_Method_Geo.maxExtent(this.localPoints);
				this.passPoints.clear();
				this.passPoints.putAll(references);
				this.excludedErrors.clear();
				this.excludedErrors.addAll(excluded);
				this.swapXy = "true".equals(settings[1]);
				this.southHemisphere = "true".equals(settings[3]);

				// Dropdown fuellen und Quelle waehlen: gespeichert, sonst erkannt
				List<Map_Source> sources = new ArrayList<Map_Source>();
				sources.add(new Map_Source(Map_Source.Kind.ECEF, null));
				sources.add(new Map_Source(Map_Source.Kind.UTM_ZONE, null));
				for (Klein_Method_Functions.Klein_Method_Geo.Projection_Definition definition : this.definitions)
					sources.add(new Map_Source(Map_Source.Kind.DEFINITION, definition));
				// lokales System der Koordinatenumrechnung - Parameter aus dem Projekt, sonst die eingestellten
				this.localSystem = projectLocal[0] != null ? projectLocal[0] : this.kleinMethod.getParameter().getLocalSystem().copy();
				this.localSystemFromProject = projectLocal[0] != null;
				sources.add(new Map_Source(Map_Source.Kind.LOCAL_P0, null));
				sources.add(new Map_Source(Map_Source.Kind.CLICK, null));
				Map_Source detected = this.detectSource(sources, projection[0]);
				Map_Source chosen = null;
				if (settings[0] != null)
					for (Map_Source candidate : sources)
						if (candidate.key().equals(settings[0]))
							chosen = candidate;
				// die gespeicherte Auswahl gilt nur, solange die Koordinatenart dieselbe ist. Hat sie sich geaendert
				// (oder stammt die Auswahl aus einer Version ohne diese Angabe und passt nicht zu eindeutig erkannten
				// Koordinaten), gilt die Erkennung.
				if (chosen != null && !chosen.key().equals(detected.key())) {
					boolean kindChanged = settings[2] != null ? !settings[2].equals(this.majorityKind.name())
							: (detected.kind == Map_Source.Kind.ECEF || detected.kind == Map_Source.Kind.UTM_ZONE);
					if (kindChanged || !this.isSourceCompatible(chosen)) {
						this.detectedText += " Gespeicherte Auswahl \u201E" + chosen + "\u201C passte nicht zu den Koordinaten ("
								+ this.majorityKind.label + ") - automatisch \u201E" + detected + "\u201C gew\u00E4hlt.";
						this.kleinMethod.getLogger().log("Klein_Method: Karte - gespeicherte Projektion " + chosen.key() + " passt nicht zur Koordinatenart "
								+ this.majorityKind + ", Erkennung " + detected.key() + " verwendet.");
						chosen = null;
					}
				}
				this.autoSelectedGaussKrueger = chosen == null && detected.kind == Map_Source.Kind.DEFINITION;
				if (chosen == null) {
					chosen = detected;
					String key = detected.key(), kind = this.majorityKind.name();
					this.runDatabaseTask(() -> {
						this.kleinMethod.getDatabaseHandler().storeMapSetting(SETTING_MODE, key);
						this.kleinMethod.getDatabaseHandler().storeMapSetting(SETTING_KIND, kind);
					}, null);
				}
				this.source = chosen;
				this.ignoreModeEvents = true;
				try {
					this.modeComboBox.getItems().setAll(sources);
					this.modeComboBox.setValue(chosen);
					this.swapXyCheckBox.setSelected(this.swapXy);
					this.hemisphereComboBox.setValue(this.southHemisphere ? "S\u00FCd" : "Nord");
				} finally {
					this.ignoreModeEvents = false;
				}
				this.recomputeGeoreference();
				if (!this.viewInitialized) {
					this.zoomToNetwork();
					this.viewInitialized = true;
				}
				if (this.maxErrorButton.isSelected())
					this.loadMaxErrors();
				this.updateRecommendationButtonVisibility();
				if (this.recommendationButton.isSelected())
					this.loadRecommendation();
			});
		}

		// ------------------------------------------------------------------
		// Erkennung der Koordinatenart
		// ------------------------------------------------------------------

		private static double median(List<Double> values) {
			if (values.isEmpty())
				return Double.NaN;
			List<Double> sorted = new ArrayList<Double>(values);
			java.util.Collections.sort(sorted);
			return sorted.get(sorted.size() / 2);
		}

		/**
		 * passt eine Kartenprojektion grundsaetzlich zur Koordinatenart der Mehrheit? Geozentrisch nur zu
		 * geozentrischen Punkten, UTM mit Zonenkennung nur zu solchen.
		 */
		private boolean isSourceCompatible(Map_Source candidate) {
			switch (candidate.kind) {
			case ECEF:
				return this.majorityKind == Point_Kind.ECEF;
			case UTM_ZONE:
				return this.majorityKind == Point_Kind.UTM;
			default:
				return this.majorityKind != Point_Kind.ECEF;
			}
		}

		/**
		 * Kein einziger Punkt passt zur Projektion candidate: passende Alternative aus der Auswahl (lokal -> "lokal um P0"
		 * bzw. "per Klick", geozentrisch, UTM), sonst null (mindestens ein Punkt passt oder keine Punkte).
		 */
		private Map_Source fittingSourceIfNoneCompatible(Map_Source candidate) {
			if (this.localPoints.length == 0 || candidate.kind == Map_Source.Kind.CLICK)
				return null;
			Map_Source saved = this.source;
			this.source = candidate;
			try {
				for (String name : this.pointNames)
					if (this.isPointCompatible(name))
						return null;
			} finally {
				this.source = saved;
			}
			Map_Source fallback = null;
			for (Map_Source option : this.modeComboBox.getItems()) {
				if (option == candidate || !this.isSourceCompatible(option))
					continue;
				switch (this.majorityKind) {
				case ECEF:
					if (option.kind == Map_Source.Kind.ECEF)
						return option;
					break;
				case UTM:
					if (option.kind == Map_Source.Kind.UTM_ZONE)
						return option;
					break;
				case LOCAL:
					if (option.kind == Map_Source.Kind.LOCAL_P0 && this.localSystemFromProject)
						return option;
					break;
				default:
					break;
				}
				if (fallback == null && option.kind == Map_Source.Kind.CLICK)
					fallback = option;
			}
			return fallback;
		}

		/** passt ein einzelner Punkt zur gewaehlten Kartenprojektion? */
		private boolean isPointCompatible(String name) {
			Point_Kind kind = this.kindByName.get(name);
			if (kind == null)
				return true;
			switch (this.source.kind) {
			case ECEF:
				return kind == Point_Kind.ECEF;
			case UTM_ZONE:
				return kind == Point_Kind.UTM;
			default:
				return kind != Point_Kind.ECEF;
			}
		}

		/** Erkennt die Koordinatenart der Punkte und liefert die passende Vorauswahl. */
		private Map_Source detectSource(List<Map_Source> sources, org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType projection) {
			int n = this.localPoints.length;
			Map_Source click = sources.get(sources.size() - 1);
			// jeden Punkt einzeln einordnen, Mehrheit bestimmt die Erkennung, Mischung wird gemeldet
			this.kindByName.clear();
			Map<Point_Kind, List<String>> byKind = new java.util.EnumMap<Point_Kind, List<String>>(Point_Kind.class);
			for (int i = 0; i < n; i++) {
				Point_Kind kind = classify(this.localPoints[i]);
				this.kindByName.put(this.pointNames.get(i), kind);
				byKind.computeIfAbsent(kind, k -> new ArrayList<String>()).add(this.pointNames.get(i));
			}
			this.majorityKind = Point_Kind.LOCAL;
			int best = -1;
			for (Map.Entry<Point_Kind, List<String>> entry : byKind.entrySet())
				if (entry.getValue().size() > best) {
					best = entry.getValue().size();
					this.majorityKind = entry.getKey();
				}
			this.mixedKindsText = "";
			if (byKind.size() > 1) {
				StringBuilder text = new StringBuilder("Gemischte Koordinatenarten im Projekt: ");
				boolean first = true;
				for (Map.Entry<Point_Kind, List<String>> entry : byKind.entrySet()) {
					if (!first)
						text.append(", ");
					first = false;
					text.append(entry.getValue().size()).append(' ').append(entry.getKey().label);
					if (entry.getKey() != this.majorityKind)
						text.append(" (").append(shortList(entry.getValue())).append(')');
				}
				text.append(" - gezeichnet werden nur die Punkte, die zur gew\u00E4hlten Kartenprojektion passen.");
				this.mixedKindsText = text.toString();
			}
			if (n == 0) {
				this.detectedText = "Keine aktiven Punkte im Projekt.";
				return click;
			}
			// Mediane nur ueber die Punkte der Mehrheitsart
			List<Double> easting = new ArrayList<Double>(), northing = new ArrayList<Double>();
			for (int i = 0; i < n; i++)
				if (this.kindByName.get(this.pointNames.get(i)) == this.majorityKind) {
					easting.add(this.localPoints[i][0]);
					northing.add(this.localPoints[i][1]);
				}
			// 1) geozentrisch WGS84 (Mehrheit, mindestens 2 Punkte)
			if (this.majorityKind == Point_Kind.ECEF && best >= 2) {
				this.detectedText = "Erkannt: geozentrische WGS84-Koordinaten (X/Y/Z)" + (best < n ? " - " + (n - best) + " Punkt(e) passen nicht dazu" : "") + ".";
				return sources.get(0);
			}
			double medianEasting = median(easting), medianNorthing = median(northing);
			int zone = Klein_Method_Functions.Klein_Method_Geo.zoneOfEasting(medianEasting);
			double eastPart = medianEasting - zone * 1000000.0;
			// 2) Gauss-Krueger laut Projekteinstellung, Streifen aus dem Rechtswert -> DHDN-Vorlage des Streifens
			if (projection == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.GAUSS_KRUEGER && zone >= 2 && zone <= 5) {
				// liegt eine Definition mit NTv2-Gitter fuer diesen Streifen vor, hat sie Vorrang - bei mehreren
				// Gittern das, dessen verdichtetes Teilgitter die Netzmitte enthaelt
				List<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition> gridDefinitions = new ArrayList<Klein_Method_Functions.Klein_Method_Geo.Projection_Definition>();
				for (Map_Source candidate : sources)
					if (candidate.kind == Map_Source.Kind.DEFINITION)
						gridDefinitions.add(candidate.definition);
				Klein_Method_Functions.Klein_Method_Geo.Projection_Definition bestGrid =
						Klein_Method_Functions.Klein_Method_Geo.bestGridDefinition(gridDefinitions, medianEasting, medianNorthing);
				for (Map_Source candidate : sources)
					if (candidate.kind == Map_Source.Kind.DEFINITION && candidate.definition == bestGrid) {
						this.detectedText = "Erkannt: Gauß-Krüger Streifen " + zone + " (Projekteinstellung) - Datum laut \u201E" + candidate.definition.name
								+ "\u201C (Gitter " + candidate.definition.gridName() + ").";
						return candidate;
					}
				for (Map_Source candidate : sources)
					if (candidate.kind == Map_Source.Kind.DEFINITION && candidate.definition.name.startsWith("DHDN Gauss-Krueger Streifen " + zone)) {
						this.detectedText = "Erkannt: Gauß-Krüger Streifen " + zone + " (Projekteinstellung) - Datum DHDN angenommen.";
						return candidate;
					}
			}
			// 3) UTM mit Zonenkennung
			boolean utmLike = zone >= 1 && zone <= 60 && eastPart > 100000.0 && eastPart < 900000.0 && medianNorthing > 0.0 && medianNorthing < 9350000.0;
			if (utmLike && (projection == org.applied_geodesy.adjustment.network.observation.reduction.ProjectionType.UTM || zone >= 28)) {
				this.detectedText = "Erkannt: UTM-Koordinaten mit Zonenkennung (Zone " + zone + ").";
				return sources.get(1);
			}
			if (utmLike) {
				this.detectedText = "Erkannt: projizierte Koordinaten mit Kennung " + zone + " im Rechtswert (UTM-Zone oder Gauß-Krüger-Streifen) - "
						+ "bitte passende Projektion wählen oder per Klick einpassen.";
				return click;
			}
			// 4) projiziert ohne Kennung
			if (medianEasting > 100000.0 && medianEasting < 900000.0 && medianNorthing > 5000000.0 && medianNorthing < 6200000.0) {
				this.detectedText = "Erkannt: UTM- oder Gauß-Krüger-Koordinaten ohne Zonen-/Streifenkennung - bitte per Klick einpassen "
						+ "(oder eine Datei aus dem Projektionsordner ohne Kennung wählen, z. B. UTM Zone 32/33).";
				return click;
			}
			// lokales System der Klein_Method-Umrechnung (Werte um die Additionskonstanten)
			if (this.localSystem != null && this.majorityKind == Point_Kind.LOCAL && this.localSystem.looksLocal(medianEasting, medianNorthing))
				for (Map_Source candidate : sources)
					if (candidate.kind == Map_Source.Kind.LOCAL_P0) {
						this.detectedText = "Erkannt: lokales System (ENh) der Klein_Method-Koordinatenumrechnung"
								+ (this.localSystemFromProject ? " - Parameter aus der Projekt-Umrechnung." : " - eingestellte Parameter.");
						return candidate;
					}
			// 5) lokal
			this.detectedText = "Erkannt: lokale Koordinaten - bitte per Klick einpassen.";
			return click;
		}

		// ------------------------------------------------------------------
		// Georeferenz
		// ------------------------------------------------------------------

		private boolean isGeoreferenced() {
			switch (this.source.kind) {
			case CLICK:
				return this.helmert != null;
			default:
				return this.localPoints.length > 0;
			}
		}

		/**
		 * Netzkoordinaten eines Punkts (JAG3D y = Rechtswert, x = Hochwert) -> {Breite, Laenge},
		 * oder null. Geozentrisch: Z (JAG3D z) ueber den Punktnamen.
		 */
		private double[] toLatLon(String name, double y, double x) {
			switch (this.source.kind) {
			case ECEF:
				Double z = name == null ? null : this.heightByName.get(name);
				if (z == null)
					return null;
				double ecefX = this.swapXy ? y : x, ecefY = this.swapXy ? x : y;
				double[] llh = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(ecefX, ecefY, z);
				return new double[] { llh[0], llh[1] };
			case UTM_ZONE:
				// Suedhalbkugel - Hochwert mit 10 000 000 m Versatz
				return Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(y, this.southHemisphere ? x - UTM_SOUTH_FALSE_NORTHING : x);
			case DEFINITION:
				return this.source.definition.toLatLon(y, x);
			case LOCAL_P0: {
				if (this.localSystem == null)
					return null;
				Double up = name == null ? null : this.heightByName.get(name);
				return this.localSystem.localToLatLon(y, x, up == null ? Double.NaN : up);
			}
			default:
				if (this.helmert == null)
					return null;
				double[] utm = this.helmert.forward(y, x);
				return Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(utm[0], utm[1]);
			}
		}

		/** Einpassung neu rechnen, Auswahlregeln, Tabelle, Plausibilitaet und Anzeige aktualisieren. */
		private void recomputeGeoreference() {
			this.latLonCache.clear();
			this.helmert = null;
			this.helmertNames.clear();
			List<double[]> local = new ArrayList<double[]>(), target = new ArrayList<double[]>();
			double meanLon = 0.0;
			int count = 0;
			List<String> ignoredPassPoints = new ArrayList<String>();
			for (Map.Entry<String, double[]> entry : this.passPoints.entrySet()) {
				if (this.pointNames.indexOf(entry.getKey()) < 0)
					continue;
				if (!this.isPointCompatible(entry.getKey())) {
					ignoredPassPoints.add(entry.getKey());
					continue;
				}
				meanLon += entry.getValue()[1];
				count++;
			}
			if (count > 0)
				this.helmertZone = Klein_Method_Functions.Klein_Method_Geo.utmZoneOfLongitude(meanLon / count);
			for (Map.Entry<String, double[]> entry : this.passPoints.entrySet()) {
				int index = this.pointNames.indexOf(entry.getKey());
				if (index < 0 || ignoredPassPoints.contains(entry.getKey()))
					continue;
				local.add(new double[] { this.localPoints[index][0], this.localPoints[index][1] });
				target.add(Klein_Method_Functions.Klein_Method_Geo.latLonToUtm(entry.getValue()[0], entry.getValue()[1], this.helmertZone));
				this.helmertNames.add(entry.getKey());
			}
			double[] residuals = null;
			StringBuilder fitText = new StringBuilder();
			boolean warning = false;
			if (local.size() >= 2)
				this.helmert = Klein_Method_Functions.Klein_Method_Geo.Helmert2D.fit(local.toArray(new double[0][]), target.toArray(new double[0][]));
			if (this.helmert != null) {
				double[] firstLatLon = this.passPoints.get(this.helmertNames.get(0));
				double utmScale = Klein_Method_Functions.Klein_Method_Geo.UTM.pointScale(firstLatLon[0], firstLatLon[1],
						Klein_Method_Functions.Klein_Method_Geo.utmCentralMeridian(this.helmertZone));
				double trueScale = this.helmert.scale / utmScale;
				double baseline = 0.0;
				for (int i = 0; i < local.size(); i++)
					for (int j = i + 1; j < local.size(); j++)
						baseline = Math.max(baseline, Math.hypot(local.get(i)[0] - local.get(j)[0], local.get(i)[1] - local.get(j)[1]));
				double scaleDeviation = Math.abs(trueScale - 1.0) * baseline;
				fitText.append(String.format(java.util.Locale.GERMANY, "%d Passpunkte | Maßstab %.5f | Drehung %.4f gon | UTM-Zone %d",
						local.size(), trueScale, Math.toDegrees(this.helmert.rotation) / 0.9, this.helmertZone));
				fitText.append(String.format(java.util.Locale.GERMANY, "%nMaßstab ändert die Strecke um %.1f m", scaleDeviation));
				if (scaleDeviation > PASS_POINT_TOLERANCE_METER) {
					warning = true;
					fitText.append(" - WARNUNG: mehr als 5 m, Punkt vermutlich falsch geklickt oder falsch zugeordnet.");
				}
				if (local.size() >= 3) {
					residuals = this.helmert.residuals;
					double max = 0.0;
					String maxName = "";
					for (int i = 0; i < residuals.length; i++)
						if (residuals[i] > max) {
							max = residuals[i];
							maxName = this.helmertNames.get(i);
						}
					fitText.append(String.format(java.util.Locale.GERMANY, "%nGrößte Abweichung %.1f m (%s)", max, maxName));
					if (max > PASS_POINT_TOLERANCE_METER) {
						warning = true;
						fitText.append(" - WARNUNG: mehr als 5 m, rot markierten Punkt prüfen.");
					}
				}
			}
			else
				fitText.append(local.size() < 2 ? "Mindestens 2 Randpunkte zuordnen." : "Passpunkte fallen zusammen - bitte anders w\u00E4hlen.");
			if (!ignoredPassPoints.isEmpty())
				fitText.append("\nIgnoriert (andere Koordinatenart, z. B. geozentrisch): ").append(shortList(ignoredPassPoints));
			this.fitLabel.setText(fitText.toString());
			this.fitLabel.setStyle(warning ? "-fx-text-fill: #C0392B; -fx-font-weight: bold;" : "");

			List<Pass_Point_Row> rows = new ArrayList<Pass_Point_Row>();
			for (Map.Entry<String, double[]> entry : this.passPoints.entrySet()) {
				int helmertIndex = this.helmertNames.indexOf(entry.getKey());
				double residual = residuals != null && helmertIndex >= 0 ? residuals[helmertIndex] : Double.NaN;
				rows.add(new Pass_Point_Row(entry.getKey(), entry.getValue()[0], entry.getValue()[1], residual));
			}
			this.passPointTable.getItems().setAll(rows);

			// Randpunkte/Ausdehnung nur ueber die Punkte, die zur gewaehlten Projektion passen
			List<double[]> compatiblePoints = new ArrayList<double[]>();
			List<String> compatibleNames = new ArrayList<String>();
			for (int i = 0; i < this.pointNames.size(); i++)
				if (this.isPointCompatible(this.pointNames.get(i))) {
					compatiblePoints.add(this.localPoints[i]);
					compatibleNames.add(this.pointNames.get(i));
				}
			this.hullPointNames.clear();
			double[][] compatibleArray = compatiblePoints.toArray(new double[0][]);
			if (compatibleArray.length > 0) {
				for (Integer index : Klein_Method_Functions.Klein_Method_Geo.convexHull(compatibleArray))
					this.hullPointNames.add(compatibleNames.get(index));
				this.networkExtent = Klein_Method_Functions.Klein_Method_Geo.maxExtent(compatibleArray);
			}

			// Auswahlregeln: nur Randpunkte, Mindestabstand 30 % der Netzausdehnung
			this.passPointBlockReason.clear();
			double minDistance = PASS_POINT_MIN_DISTANCE_FRACTION * this.networkExtent;
			for (int i = 0; i < this.pointNames.size(); i++) {
				String name = this.pointNames.get(i);
				if (!this.isPointCompatible(name)) {
					this.passPointBlockReason.put(name, "Andere Koordinatenart (" + this.kindByName.get(name).label + ") - nicht einpassbar.");
					continue;
				}
				if (!this.hullPointNames.contains(name)) {
					this.passPointBlockReason.put(name, "Innen liegender Punkt - nur Randpunkte des Netzes sind w\u00E4hlbar.");
					continue;
				}
				for (String other : this.passPoints.keySet()) {
					int j = this.pointNames.indexOf(other);
					if (j < 0 || other.equals(name))
						continue;
					double distance = Math.hypot(this.localPoints[i][0] - this.localPoints[j][0], this.localPoints[i][1] - this.localPoints[j][1]);
					if (distance < minDistance) {
						this.passPointBlockReason.put(name, String.format(java.util.Locale.GERMANY,
								"Zu nah an %s (%.0f m, mindestens %.0f m = 30 %% der Netzausdehnung).", other, distance, minDistance));
						break;
					}
				}
			}
			List<String> candidates = new ArrayList<String>(this.hullPointNames);
			java.util.Collections.sort(candidates);
			String selected = this.passPointComboBox.getValue();
			this.passPointComboBox.getItems().setAll(candidates);
			this.passPointComboBox.setValue(selected != null && candidates.contains(selected) && !this.passPointBlockReason.containsKey(selected) ? selected : null);

			boolean clickMode = this.source.kind == Map_Source.Kind.CLICK;
			this.clickPanel.setVisible(clickMode);
			this.clickPanel.setManaged(clickMode);
			boolean ecefMode = this.source.kind == Map_Source.Kind.ECEF;
			this.swapXyCheckBox.setVisible(ecefMode);
			this.swapXyCheckBox.setManaged(ecefMode);
			boolean utmMode = this.source.kind == Map_Source.Kind.UTM_ZONE;
			this.hemisphereLabel.setVisible(utmMode);
			this.hemisphereLabel.setManaged(utmMode);
			this.hemisphereComboBox.setVisible(utmMode);
			this.hemisphereComboBox.setManaged(utmMode);
			this.checkPlausibility();
			this.updateStatus();
			this.syncAreaMembership();
			this.redraw();
		}

		// ------------------------------------------------------------------
		// Plausibilitaet
		// ------------------------------------------------------------------

		/** Prueft die umgerechneten Punkte: Ausreisser, Hoehen, Achsen, Zonen, Ausdehnung, Datum. */
		private void checkPlausibility() {
			this.outlierNames.clear();
			this.farConnectedNames.clear();
			List<String> issues = new ArrayList<String>();
			if (!this.isGeoreferenced() || this.localPoints.length == 0) {
				this.showPlausibility(issues);
				return;
			}
			List<double[]> latLons = new ArrayList<double[]>();
			List<String> names = new ArrayList<String>();
			List<String> notConvertible = new ArrayList<String>();
			List<String> badHeights = new ArrayList<String>();
			List<String> otherKind = new ArrayList<String>();
			for (int i = 0; i < this.localPoints.length; i++) {
				String name = this.pointNames.get(i);
				if (!this.isPointCompatible(name)) {
					otherKind.add(name);
					this.outlierNames.add(name);
					continue;
				}
				double[] ll = this.toLatLon(name, this.localPoints[i][0], this.localPoints[i][1]);
				if (ll == null || Double.isNaN(ll[0]) || Double.isNaN(ll[1]) || Math.abs(ll[0]) > 85.0) {
					notConvertible.add(name);
					this.outlierNames.add(name);
					continue;
				}
				if (this.source.kind == Map_Source.Kind.ECEF) {
					double[] p = this.localPoints[i];
					double h = Klein_Method_Functions.Klein_Method_Geo.ecefToLatLonHeight(this.swapXy ? p[0] : p[1], this.swapXy ? p[1] : p[0], p[2])[2];
					if (!(h > -500.0 && h < 9000.0)) {
						badHeights.add(String.format(java.util.Locale.GERMANY, "%s (%.0f m)", name, h));
						this.outlierNames.add(name);
						continue;
					}
				}
				latLons.add(ll);
				names.add(name);
			}
			if (!otherKind.isEmpty())
				issues.add(!this.mixedKindsText.isEmpty() ? this.mixedKindsText
						: otherKind.size() + " Punkt(e) passen nicht zur gew\u00E4hlten Kartenprojektion (nicht gezeichnet): " + shortList(otherKind));
			if (!notConvertible.isEmpty())
				issues.add(notConvertible.size() + " Punkt(e) nicht umrechenbar: " + shortList(notConvertible));
			if (!badHeights.isEmpty())
				issues.add("Unplausible ellipsoidische Höhe (außerhalb -500 bis 9000 m): " + shortList(badHeights));
			if (!latLons.isEmpty()) {
				List<Double> lats = new ArrayList<Double>(), lons = new ArrayList<Double>();
				for (double[] ll : latLons) {
					lats.add(ll[0]);
					lons.add(ll[1]);
				}
				double medLat = median(lats), medLon = median(lons);
				List<Double> distances = new ArrayList<Double>();
				double[] distance = new double[latLons.size()];
				double maxDistance = 0.0;
				for (int i = 0; i < latLons.size(); i++) {
					distance[i] = groundDistance(medLat, medLon, latLons.get(i)[0], latLons.get(i)[1]);
					distances.add(distance[i]);
				}
				double limit = Math.max(10.0 * median(distances), 20000.0);
				List<String> far = new ArrayList<String>(), farConnected = new ArrayList<String>();
				java.util.Set<String> connected = this.connectedPointNames();
				for (int i = 0; i < latLons.size(); i++) {
					if (distance[i] > limit) {
						String entry = String.format(java.util.Locale.GERMANY, "%s (%.0f km)", names.get(i), distance[i] / 1000.0);
						// ueber Messungen verbunden (z. B. GNSS-Referenzstation): zeichnen, damit die Basislinien vollstaendig sind
						if (connected.contains(names.get(i))) {
							farConnected.add(entry);
							this.farConnectedNames.add(names.get(i));
						} else {
							far.add(entry);
							this.outlierNames.add(names.get(i));
						}
					}
					else
						maxDistance = Math.max(maxDistance, distance[i]);
				}
				if (!far.isEmpty())
					issues.add(tr("Ausreißer weit abseits des Netzes ohne Messungen (nicht gezeichnet): ") + shortList(far));
				if (!farConnected.isEmpty())
					issues.add(tr("Weit entfernte Stationen, über Messungen verbunden (gezeichnet; „Netz anzeigen“ zeigt nur das Netz, „Gesamtansicht“ alles): ")
							+ shortList(farConnected));
				if (2.0 * maxDistance > 100000.0)
					issues.add(String.format(java.util.Locale.GERMANY, "Netzausdehnung ca. %.0f km - bitte prüfen, ob das so gewollt ist.", 2.0 * maxDistance / 1000.0));
			}
			// vertauschte Achsen / gemischte Zonen (projizierte Koordinaten)
			if (this.source.kind == Map_Source.Kind.UTM_ZONE || this.source.kind == Map_Source.Kind.DEFINITION) {
				List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>();
				java.util.Set<Integer> zones = new java.util.TreeSet<Integer>();
				for (double[] p : this.localPoints) {
					ys.add(p[0]);
					xs.add(p[1]);
					zones.add(Klein_Method_Functions.Klein_Method_Geo.zoneOfEasting(p[0]));
				}
				double medY = median(ys), medX = median(xs);
				if (medX > 1.0e7 || (medX < 1.0e6 && medY > 4.0e6 && medY < 7.5e6))
					issues.add("Rechts- und Hochwert scheinen vertauscht (Hochwert hat die Größenordnung eines Rechtswerts).");
				if (this.source.kind == Map_Source.Kind.UTM_ZONE && zones.size() > 1)
					issues.add("Gemischte Zonenkennungen im Rechtswert: " + zones + " - Punkte verschiedener Zonen passen nicht in eine Karte.");
			}
			// Gauss-Krueger: Lagedatum ist in JAG3D nicht hinterlegt
			if (this.source.kind == Map_Source.Kind.DEFINITION && this.source.definition.name.toLowerCase(java.util.Locale.ROOT).contains("gauss-krueger"))
				issues.add((this.autoSelectedGaussKrueger ? "Datum nicht hinterlegt, gerechnet mit DHDN. " : "Das Lagedatum ist in JAG3D nicht hinterlegt. ")
						+ "Liegen die Koordinaten in 42/83 (Krassowski) statt DHDN oder umgekehrt, weicht die Lage um etwa 600 m ab - "
						+ "dann das andere System wählen oder „Per Klick einpassen“.");
			this.showPlausibility(issues);
		}

		private void showPlausibility(List<String> issues) {
			StringBuilder all = new StringBuilder();
			for (String issue : issues)
				all.append("• ").append(issue).append('\n');
			if (!this.definitionErrors.isEmpty()) {
				all.append("• Projektionsordner - nicht nutzbare Dateien:\n");
				for (String error : this.definitionErrors)
					all.append("   ").append(error).append('\n');
			}
			String text = all.toString().trim();
			this.plausibilityLabel.setText(text);
			this.plausibilityLabel.setVisible(!text.isEmpty());
			this.plausibilityLabel.setManaged(!text.isEmpty());
			this.plausibilityLabel.setTooltip(text.isEmpty() ? null : new Tooltip("Plausibilitätsprüfung der umgerechneten Koordinaten:\n" + text));
		}

		private static String shortList(List<String> items) {
			int shown = Math.min(8, items.size());
			return String.join(", ", items.subList(0, shown)) + (items.size() > shown ? " … (+" + (items.size() - shown) + ")" : "");
		}

		/** Abstand auf der Kugel [m] (fuer die Ausreisser-Pruefung). */
		private static double groundDistance(double lat1, double lon1, double lat2, double lon2) {
			double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
			double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
			double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
			return 2.0 * 6371000.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
		}

		private void updateStatus() {
			if (this.localPoints.length == 0) {
				this.statusLabel.setText("Keine aktiven Punkte im Projekt.");
				return;
			}
			String state;
			switch (this.source.kind) {
			case ECEF:
				state = "Geozentrisch WGS84 - jeder Punkt exakt umgerechnet. Konfidenzellipsen und Verschiebungspfeile sind ausgeblendet "
						+ "(JAG3D berechnet sie nur in der X/Y-Ebene).";
				break;
			case UTM_ZONE:
				state = "UTM mit Zonenkennung (ETRS89), " + (this.southHemisphere ? "S\u00FCdhalbkugel" : "Nordhalbkugel")
						+ " - jeder Punkt exakt umgerechnet." + this.hemisphereHint();
				break;
			case LOCAL_P0:
				state = "Lokales System (ENh) um P0 " + String.format(java.util.Locale.GERMANY, "B %.6f°, L %.6f°", this.localSystem.latitude, this.localSystem.longitude)
						+ " - jeder Punkt exakt zurückgerechnet.";
				break;
			case DEFINITION:
				state = "„" + this.source.definition.name + "“ - jeder Punkt exakt umgerechnet"
						+ (this.source.definition.hasGrid() ? " (Datumsübergang über NTv2-Gitter " + this.source.definition.gridName() + ", amtlich, ca. 1 cm)."
								: this.source.definition.hasDatumShift() ? " (Datumsübergang mit 7 Parametern, ca. 1-3 m)." : ".");
				break;
			default:
				state = this.helmert != null
						? "Netz per Klick eingepasst (" + this.helmertNames.size() + " Passpunkte). Nur Anzeige - Genauigkeit hängt vom Klicken ab."
						: "Netz noch nicht eingepasst. Rechts einen Randpunkt wählen, „In Karte klicken“, dann in der Karte auf die Stelle "
								+ "des Punkts klicken (mindestens 2 Punkte).";
			}
			this.statusLabel.setText(this.detectedText + "  " + state);
		}

		/**
		 * Hinweis, wo das Netz bei der anderen Halbkugel laege (Median der Punkte) - die Halbkugel ist aus
		 * UTM-Koordinaten nicht erkennbar, beide Deutungen sind gueltig.
		 */
		private String hemisphereHint() {
			List<Double> ys = new ArrayList<Double>(), xs = new ArrayList<Double>();
			for (int i = 0; i < this.localPoints.length; i++)
				if (this.isPointCompatible(this.pointNames.get(i))) {
					ys.add(this.localPoints[i][0]);
					xs.add(this.localPoints[i][1]);
				}
			if (ys.isEmpty())
				return "";
			double y = median(ys), x = median(xs);
			double[] here = Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(y, this.southHemisphere ? x - UTM_SOUTH_FALSE_NORTHING : x);
			double[] other = Klein_Method_Functions.Klein_Method_Geo.utmToLatLon(y, this.southHemisphere ? x : x - UTM_SOUTH_FALSE_NORTHING);
			if (here == null || other == null)
				return "";
			return String.format(java.util.Locale.GERMANY, " Netz bei %.2f\u00B0 %s; bei %s-Halbkugel l\u00E4ge es bei %.2f\u00B0 %s - bitte pr\u00FCfen.",
					Math.abs(here[0]), here[0] >= 0 ? "N" : "S", this.southHemisphere ? "Nord" : "S\u00FCd", Math.abs(other[0]), other[0] >= 0 ? "N" : "S");
		}

		private void assignPassPoint(double screenX, double screenY) {
			String name = this.passPointComboBox.getValue();
			if (name == null) {
				this.statusLabel.setText("Bitte zuerst rechts einen Passpunkt wählen.");
				return;
			}
			double[] world = this.screenToWorld(screenX, screenY);
			double[] latLon = Klein_Method_Functions.Klein_Method_Geo.worldPixelToLatLon(world[0], world[1]);
			this.setPassPointButton.setSelected(false);
			this.runDatabaseTask(() -> this.kleinMethod.getDatabaseHandler().storeMapReference(name, latLon[0], latLon[1]), () -> {
				this.passPoints.put(name, latLon);
				this.kleinMethod.getLogger().log(String.format(java.util.Locale.ROOT, "Klein_Method: Karte - Passpunkt %s gesetzt (%.7f, %.7f).",
						name, latLon[0], latLon[1]));
				this.recomputeGeoreference();
			});
		}

		// ------------------------------------------------------------------
		// Ansicht
		// ------------------------------------------------------------------

		private double[] screenToWorld(double sx, double sy) {
			double factor = Math.pow(2.0, this.zoom);
			return new double[] { this.centerX + (sx - 0.5 * this.canvas.getWidth()) / factor,
					this.centerY + (sy - 0.5 * this.canvas.getHeight()) / factor };
		}

		private double[] latLonToScreen(double lat, double lon) {
			double[] world = Klein_Method_Functions.Klein_Method_Geo.latLonToWorldPixel(lat, lon);
			double factor = Math.pow(2.0, this.zoom);
			return new double[] { 0.5 * this.canvas.getWidth() + (world[0] - this.centerX) * factor,
					0.5 * this.canvas.getHeight() + (world[1] - this.centerY) * factor };
		}

		private void zoomToNetwork() {
			this.zoomToNetwork(false);
		}

		/** Namen der Punkte, die in den Beobachtungs-Ebenen des Netzplots an einer Messung beteiligt sind. */
		@SuppressWarnings("unchecked")
		private java.util.Set<String> connectedPointNames() {
			java.util.Set<String> names = new java.util.HashSet<String>();
			try {
				for (org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer : this.networkLayers()) {
					if (!(layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer))
						continue;
					java.lang.reflect.Field field = org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer.class.getDeclaredField("observableMeasurements");
					field.setAccessible(true);
					for (org.applied_geodesy.jag3d.ui.graphic.sql.PointPair pair : (List<org.applied_geodesy.jag3d.ui.graphic.sql.PointPair>) field.get(layer)) {
						if (pair.getStartPoint() != null)
							names.add(pair.getStartPoint().getName());
						if (pair.getEndPoint() != null)
							names.add(pair.getEndPoint().getName());
					}
				}
			} catch (Exception e) {
				this.kleinMethod.getLogger().log("Klein_Method: Karte - Messungen der Netzplot-Ebenen nicht lesbar: " + e.getMessage());
			}
			return names;
		}

		/** auf das Netz zoomen; all = auch weit entfernte, verbundene Stationen (Gesamtansicht). */
		private void zoomToNetwork(boolean all) {
			List<double[]> latLons = new ArrayList<double[]>();
			if (this.isGeoreferenced()) {
				for (int i = 0; i < this.localPoints.length; i++) {
					String name = this.pointNames.get(i);
					if (this.outlierNames.contains(name) || (!all && this.farConnectedNames.contains(name)))
						continue;
					double[] ll = this.toLatLon(name, this.localPoints[i][0], this.localPoints[i][1]);
					if (ll != null && !Double.isNaN(ll[0]) && !Double.isNaN(ll[1]))
						latLons.add(ll);
				}
			}
			if (latLons.isEmpty())
				latLons.addAll(this.passPoints.values());
			if (latLons.isEmpty()) {
				double[] world = Klein_Method_Functions.Klein_Method_Geo.latLonToWorldPixel(51.2, 10.4); // Deutschland
				this.centerX = world[0];
				this.centerY = world[1];
				this.zoom = 6.0;
				this.redraw();
				return;
			}
			double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			for (double[] ll : latLons) {
				double[] world = Klein_Method_Functions.Klein_Method_Geo.latLonToWorldPixel(ll[0], ll[1]);
				minX = Math.min(minX, world[0]);
				maxX = Math.max(maxX, world[0]);
				minY = Math.min(minY, world[1]);
				maxY = Math.max(maxY, world[1]);
			}
			this.centerX = 0.5 * (minX + maxX);
			this.centerY = 0.5 * (minY + maxY);
			double width = Math.max(this.canvas.getWidth(), 200) * 0.85, height = Math.max(this.canvas.getHeight(), 200) * 0.85;
			double spanX = Math.max(maxX - minX, 1e-9), spanY = Math.max(maxY - minY, 1e-9);
			double newZoom = Math.log(Math.min(width / spanX, height / spanY)) / Math.log(2.0);
			this.zoom = Math.max(MIN_ZOOM, Math.min(latLons.size() == 1 ? 16.0 : MAX_ZOOM - 1.0, newZoom));
			this.redraw();
		}

		private void gotoLatLon(String text) {
			java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(text == null ? "" : text);
			List<Double> values = new ArrayList<Double>();
			while (matcher.find() && values.size() < 2)
				values.add(Double.parseDouble(matcher.group().replace(',', '.')));
			if (values.size() < 2 || Math.abs(values.get(0)) > 85.0 || Math.abs(values.get(1)) > 180.0) {
				this.statusLabel.setText("Bitte Breite und Länge in Grad eingeben, z. B. „51,34 12,37“.");
				return;
			}
			double[] world = Klein_Method_Functions.Klein_Method_Geo.latLonToWorldPixel(values.get(0), values.get(1));
			this.centerX = world[0];
			this.centerY = world[1];
			this.zoom = 15.0;
			this.redraw();
		}

		// ------------------------------------------------------------------
		// Zeichnen
		// ------------------------------------------------------------------

		private void redraw() {
			double width = this.canvas.getWidth(), height = this.canvas.getHeight();
			if (width <= 0 || height <= 0)
				return;
			javafx.scene.canvas.GraphicsContext gc = this.canvas.getGraphicsContext2D();
			gc.setTransform(new javafx.scene.transform.Affine());
			gc.setFill(Color.rgb(230, 230, 230));
			gc.fillRect(0, 0, width, height);
			this.drawTiles(gc, width, height);
			this.lastLayerSignature = this.layerSignature();
			if (this.isGeoreferenced()) {
				this.drawNetworkLayers(gc, width, height);
				this.scheduleNetzplotRedraw();
			}
			this.drawPassPoints(gc);
			if (this.isGeoreferenced())
				this.drawExcludedErrors(gc);
			if (this.maxErrorButton.isSelected())
				this.drawMaxErrors(gc);
			if (this.isGeoreferenced() && this.kleinMethod.isDynamicStepShown())
				this.drawAreas(gc);
			if (this.recommendationButton.isSelected() && this.recommendation != null)
				this.drawRecommendation(gc);
			if (this.isGeoreferenced() && !gnssSupportLevels.isEmpty())
				this.drawGnssSupport(gc);
			if (this.isGeoreferenced() && !prognosisLevels.isEmpty())
				this.drawPrognosis(gc);
			if (this.highlightName != null) {
				double[] xy = this.screenOfPoint(this.highlightName);
				if (xy != null) {
					gc.setTransform(new javafx.scene.transform.Affine());
					gc.setLineWidth(2.5);
					gc.setStroke(Color.rgb(21, 101, 192, 0.95));
					gc.strokeOval(xy[0] - 14, xy[1] - 14, 28, 28);
				}
			}
			if (this.windowZoomStart != null && this.windowZoomCurrent != null) {
				gc.setTransform(new javafx.scene.transform.Affine());
				double x = Math.min(this.windowZoomStart[0], this.windowZoomCurrent[0]), y = Math.min(this.windowZoomStart[1], this.windowZoomCurrent[1]);
				double w = Math.abs(this.windowZoomCurrent[0] - this.windowZoomStart[0]), h = Math.abs(this.windowZoomCurrent[1] - this.windowZoomStart[1]);
				gc.setLineDashes(6, 4);
				gc.setLineWidth(1.5);
				gc.setStroke(Color.rgb(21, 101, 192, 0.9));
				gc.strokeRect(x, y, w, h);
				gc.setLineDashes((double[]) null);
			}
			gc.setTransform(new javafx.scene.transform.Affine());
			gc.setFont(Font.font("System", 11));
			double textWidth = 150;
			gc.setFill(Color.rgb(255, 255, 255, 0.8));
			gc.fillRect(width - textWidth - 6, height - 18, textWidth + 6, 18);
			gc.setFill(Color.rgb(40, 40, 40));
			gc.fillText(ATTRIBUTION, width - textWidth - 2, height - 5);
		}

		private void drawTiles(javafx.scene.canvas.GraphicsContext gc, double width, double height) {
			int z = (int) Math.max(0, Math.min(MAX_TILE_ZOOM, Math.round(this.zoom)));
			double tileScale = Math.pow(2.0, this.zoom - z);
			double tileSize = Klein_Method_Functions.Klein_Method_Geo.TILE_SIZE;
			double originX = this.centerX * Math.pow(2.0, z) - 0.5 * width / tileScale;
			double originY = this.centerY * Math.pow(2.0, z) - 0.5 * height / tileScale;
			int tilesPerAxis = 1 << z;
			int firstX = (int) Math.floor(originX / tileSize), lastX = (int) Math.floor((originX + width / tileScale) / tileSize);
			int firstY = Math.max(0, (int) Math.floor(originY / tileSize));
			int lastY = Math.min(tilesPerAxis - 1, (int) Math.floor((originY + height / tileScale) / tileSize));
			for (int ty = firstY; ty <= lastY; ty++) {
				for (int tx = firstX; tx <= lastX; tx++) {
					int wrappedX = ((tx % tilesPerAxis) + tilesPerAxis) % tilesPerAxis;
					double sx = (tx * tileSize - originX) * tileScale, sy = (ty * tileSize - originY) * tileScale;
					javafx.scene.image.Image image = this.getTile(z, wrappedX, ty);
					if (image != null)
						gc.drawImage(image, sx, sy, tileSize * tileScale + 0.5, tileSize * tileScale + 0.5);
				}
			}
		}

		private javafx.scene.image.Image getTile(int z, int x, int y) {
			String key = z + "/" + x + "/" + y;
			javafx.scene.image.Image image = this.tileCache.get(key);
			if (image != null)
				return image;
			Long failed = this.tilesFailed.get(key);
			if (failed != null && System.currentTimeMillis() - failed < 60000L)
				return null;
			if (this.tilesLoading.add(key)) {
				this.tileLoader.submit(() -> {
					try {
						byte[] bytes = this.loadTileBytes(z, x, y);
						javafx.scene.image.Image loaded = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes));
						if (loaded.isError())
							throw new IOException("Kachel nicht lesbar");
						Platform.runLater(() -> {
							this.tileCache.put(key, loaded);
							this.tilesLoading.remove(key);
							this.redraw();
						});
					} catch (Exception e) {
						this.tilesFailed.put(key, System.currentTimeMillis());
						this.tilesLoading.remove(key);
						Platform.runLater(() -> this.statusLabel.setText("Kartenkacheln nicht erreichbar (" + e.getMessage()
								+ ") - Internetverbindung prüfen. Das Netz wird trotzdem gezeichnet."));
					}
				});
			}
			return null;
		}

		/** Kachel aus dem Platten-Cache (max. 7 Tage alt) oder von tile.openstreetmap.org. */
		private byte[] loadTileBytes(int z, int x, int y) throws IOException {
			java.io.File cacheFile = new java.io.File(System.getProperty("user.home"),
					".klein_method" + java.io.File.separator + "tiles" + java.io.File.separator + z + java.io.File.separator + x
							+ java.io.File.separator + y + ".png");
			if (cacheFile.isFile() && System.currentTimeMillis() - cacheFile.lastModified() < TILE_CACHE_MAX_AGE_MS)
				return java.nio.file.Files.readAllBytes(cacheFile.toPath());
			java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(String.format(TILE_URL, z, x, y)).openConnection();
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(15000);
			// OSM-Nutzungsregeln: eindeutige Kennung der Anwendung
			connection.setRequestProperty("User-Agent", "Klein_Method/" + Klein_Method_Default_Values.PACKAGE_VERSION + " (JAG3D extension, map view)");
			int code = connection.getResponseCode();
			if (code != 200)
				throw new IOException("HTTP " + code);
			byte[] bytes;
			try (java.io.InputStream in = connection.getInputStream()) {
				bytes = in.readAllBytes();
			}
			try {
				cacheFile.getParentFile().mkdirs();
				java.nio.file.Files.write(cacheFile.toPath(), bytes);
			} catch (IOException e) {
				// Platten-Cache ist optional
			}
			return bytes;
		}

		/** Ebenen des Netzplots in seiner Reihenfolge (ohne Legende). */
		@SuppressWarnings("unchecked")
		private List<org.applied_geodesy.jag3d.ui.graphic.layer.Layer> networkLayers() {
			org.applied_geodesy.jag3d.ui.graphic.layer.LayerManager layerManager =
					org.applied_geodesy.jag3d.ui.graphic.UIGraphicPaneBuilder.getInstance().getLayerManager();
			List<org.applied_geodesy.jag3d.ui.graphic.layer.Layer> layers = new ArrayList<org.applied_geodesy.jag3d.ui.graphic.layer.Layer>();
			try {
				// Reihenfolge wie im Netzplot (privates Feld, im selben Modul lesbar)
				java.lang.reflect.Field field = layerManager.getClass().getDeclaredField("layers");
				field.setAccessible(true);
				layers.addAll((List<org.applied_geodesy.jag3d.ui.graphic.layer.Layer>) field.get(layerManager));
			} catch (Exception e) {
				for (org.applied_geodesy.jag3d.ui.graphic.layer.LayerType type : org.applied_geodesy.jag3d.ui.graphic.layer.LayerType.values()) {
					org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer = layerManager.getLayer(type);
					if (layer != null)
						layers.add(layer);
				}
			}
			layers.removeIf(layer -> layer == null || layer.getLayerType() == org.applied_geodesy.jag3d.ui.graphic.layer.LayerType.LEGEND);
			return layers;
		}

		/** Kennung aller Darstellungs-Einstellungen und Datenmengen des Netzplots (fuer das Neuzeichnen). */
		private String layerSignature() {
			StringBuilder signature = new StringBuilder();
			signature.append(this.symbolCheckBox.isSelected());
			try {
				for (org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer : this.networkLayers()) {
					signature.append(layer.getLayerType()).append(layer.isVisible()).append(layer.getColor()).append(layer.getLineWidth())
							.append(layer.getSymbolSize()).append(layer.hasContent());
					if (layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.PointLayer) {
						org.applied_geodesy.jag3d.ui.graphic.layer.PointLayer pointLayer = (org.applied_geodesy.jag3d.ui.graphic.layer.PointLayer) layer;
						signature.append(pointLayer.getPoints().size()).append(System.identityHashCode(pointLayer.getPoints())).append(pointLayer.getFontSize())
								.append(pointLayer.getFontColor()).append(pointLayer.getPointSymbolType());
					}
					if (layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.ArrowLayer)
						signature.append(((org.applied_geodesy.jag3d.ui.graphic.layer.ArrowLayer) layer).getVectorScale());
					if (layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.AbsoluteConfidenceLayer)
						signature.append(((org.applied_geodesy.jag3d.ui.graphic.layer.AbsoluteConfidenceLayer) layer).getConfidenceScale());
					signature.append('|');
				}
			} catch (Exception e) {
				signature.append(e.getClass().getSimpleName());
			}
			return signature.toString();
		}

		/** Liest ein privates Listenfeld einer Ebene (im selben Modul erlaubt), sonst leere Liste. */
		private static List<?> privateList(Object owner, String fieldName) {
			Class<?> type = owner.getClass();
			while (type != null) {
				try {
					java.lang.reflect.Field field = type.getDeclaredField(fieldName);
					field.setAccessible(true);
					Object value = field.get(owner);
					return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
				} catch (NoSuchFieldException e) {
					type = type.getSuperclass();
				} catch (Exception e) {
					return java.util.Collections.emptyList();
				}
			}
			return java.util.Collections.emptyList();
		}

		/** alle Punkt-Objekte der Netzplot-Ebenen (Punkte, Linien-Endpunkte, Verschiebungspfeile). */
		private static java.util.Set<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint> collectGraphicPoints(
				List<org.applied_geodesy.jag3d.ui.graphic.layer.Layer> layers) {
			java.util.Set<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint> points =
					java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, Boolean>());
			for (org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer : layers) {
				if (layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.PointLayer)
					points.addAll(((org.applied_geodesy.jag3d.ui.graphic.layer.PointLayer) layer).getPoints());
				for (String fieldName : new String[] { "observableMeasurements", "relativeConfidences" })
					for (Object item : privateList(layer, fieldName))
						if (item instanceof org.applied_geodesy.jag3d.ui.graphic.sql.PointPair) {
							org.applied_geodesy.jag3d.ui.graphic.sql.PointPair pair = (org.applied_geodesy.jag3d.ui.graphic.sql.PointPair) item;
							if (pair.getStartPoint() != null)
								points.add(pair.getStartPoint());
							if (pair.getEndPoint() != null)
								points.add(pair.getEndPoint());
						}
			}
			return points;
		}

		private static void setVectors(org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint point, double[] values) {
			for (int i = 0; i < VECTOR_FIELDS.length; i++) {
				try {
					java.lang.reflect.Field field = org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint.class.getDeclaredField(VECTOR_FIELDS[i]);
					field.setAccessible(true);
					field.setDouble(point, values[i]);
				} catch (Exception e) {
					// Feld nicht vorhanden - Vektor bleibt unveraendert
				}
			}
		}

		/** Breite/Laenge eines Netzplot-Punkts (zwischengespeichert, solange sich seine Koordinaten nicht aendern). */
		private double[] latLonOf(org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint point) {
			double y = point.getCoordinate().getX(), x = point.getCoordinate().getY();
			double[] cached = this.latLonCache.get(point);
			if (cached != null && cached[0] == y && cached[1] == x)
				return cached[2] != cached[2] ? null : new double[] { cached[2], cached[3] };
			double[] ll = this.toLatLon(point.getName(), y, x);
			this.latLonCache.put(point, new double[] { y, x, ll == null ? Double.NaN : ll[0], ll == null ? Double.NaN : ll[1] });
			return ll;
		}

		/**
		 * Zeichnet die Netzplot-Ebenen mit ihrem eigenen Code in der Kartenebene (Meter am
		 * Bildmittelpunkt, nordorientiert). Jeder Punkt wird dafuer exakt umgerechnet,
		 * Ellipsen und Vektoren mit der oertlichen Ableitung der Abbildung; danach werden
		 * alle Originalwerte wiederhergestellt.
		 */
		private void drawNetworkLayers(javafx.scene.canvas.GraphicsContext gc, double width, double height) {
			double centerLat = Klein_Method_Functions.Klein_Method_Geo.worldPixelToLatLon(this.centerX, this.centerY)[0];
			double cosCenter = Math.cos(Math.toRadians(centerLat));
			double metersPerWorldPixel = EARTH_CIRCUMFERENCE / Klein_Method_Functions.Klein_Method_Geo.TILE_SIZE * cosCenter;
			double metersPerPixel = metersPerWorldPixel / Math.pow(2.0, this.zoom);
			org.applied_geodesy.jag3d.ui.graphic.util.GraphicExtent extent = new org.applied_geodesy.jag3d.ui.graphic.util.GraphicExtent();
			extent.setDrawingBoardWidth(width);
			extent.setDrawingBoardHeight(height);
			extent.set(-0.5 * width * metersPerPixel, -0.5 * height * metersPerPixel, 0.5 * width * metersPerPixel, 0.5 * height * metersPerPixel);
			boolean ecef = this.source.kind == Map_Source.Kind.ECEF;

			List<org.applied_geodesy.jag3d.ui.graphic.layer.Layer> layers = this.networkLayers();
			java.util.Set<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint> points = collectGraphicPoints(layers);
			Map<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, Saved_Point> saved =
					new java.util.IdentityHashMap<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, Saved_Point>();
			List<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties> hiddenSymbols =
					new ArrayList<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties>();
			try {
				for (org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint point : points) {
					if (point.getCoordinate() == null)
						continue;
					Saved_Point original = new Saved_Point(point);
					saved.put(point, original);
					double[] ll = this.latLonOf(point);
					if (ll == null || this.outlierNames.contains(point.getName())) {
						point.setVisible(false);
						continue;
					}
					double[] plane = this.toPlane(ll, metersPerWorldPixel);
					point.setCoordinate(new org.applied_geodesy.jag3d.ui.graphic.coordinate.WorldCoordinate(plane[0], plane[1]));
					boolean hasVectors = false;
					for (double v : original.vectors)
						hasVectors |= v != 0.0;
					if (ecef) {
						// Vektoren geozentrisch -> Ost/Nord/Oben; Ellipsen nicht uebertragbar (X/Y-Ebene)
						point.setMajorAxis(0.0);
						point.setMinorAxis(0.0);
						if (hasVectors) {
							double k = cosCenter / Math.cos(Math.toRadians(ll[0]));
							double[] v = original.vectors;
							double[] r = this.ecefVectorToLocal(v[0], v[1], v[2], ll, k);
							double[] c = this.ecefVectorToLocal(v[3], v[4], v[5], ll, k);
							setVectors(point, new double[] { r[0], r[1], r[2], c[0], c[1], c[2] });
						}
						continue;
					}
					if (original.majorAxis <= 0.0 && !hasVectors)
						continue;
					// oertliche Ableitung der Abbildung Netz [m] -> Kartenebene [m]
					double y = original.coordinate.getX(), x = original.coordinate.getY(), step = 1.0;
					double[] llEast = this.toLatLon(point.getName(), y + step, x);
					double[] llNorth = this.toLatLon(point.getName(), y, x + step);
					if (llEast == null || llNorth == null)
						continue;
					double[] pEast = this.toPlane(llEast, metersPerWorldPixel), pNorth = this.toPlane(llNorth, metersPerWorldPixel);
					double j11 = (pEast[0] - plane[0]) / step, j21 = (pEast[1] - plane[1]) / step;
					double j12 = (pNorth[0] - plane[0]) / step, j22 = (pNorth[1] - plane[1]) / step;
					if (original.majorAxis > 0.0) {
						// Winkel dreht im Bildschirm (y nach unten): Hauptachse in Ost/Nord = (cos a, -sin a)
						double ue = Math.cos(original.angle), un = -Math.sin(original.angle);
						double ve = Math.sin(original.angle), vn = Math.cos(original.angle);
						double ue2 = j11 * ue + j12 * un, un2 = j21 * ue + j22 * un;
						double ve2 = j11 * ve + j12 * vn, vn2 = j21 * ve + j22 * vn;
						point.setMajorAxis(original.majorAxis * Math.hypot(ue2, un2));
						point.setMinorAxis(original.minorAxis * Math.hypot(ve2, vn2));
						point.setAngle(Math.atan2(-un2, ue2));
					}
					if (hasVectors) {
						double[] v = original.vectors;
						double scale = Math.sqrt(Math.abs(j11 * j22 - j12 * j21));
						setVectors(point, new double[] { j11 * v[0] + j12 * v[1], j21 * v[0] + j22 * v[1], v[2] * scale,
								j11 * v[3] + j12 * v[4], j21 * v[3] + j22 * v[4], v[5] * scale });
					}
				}
				// Messart-Symbole des Netzplots liegen neben dem Punkt (entlang der Linie) - beim Zeichnen der Ebenen immer
				// ausblenden; auf Wunsch zeichnet die Karte sie danach selbst auf den Punkt
				for (org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer : layers)
						if (layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer)
							for (org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType symbolType
									: org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType.values()) {
								org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties properties =
										((org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer) layer).getObservationSymbolProperties(symbolType);
								if (properties != null && properties.isVisible()) {
									properties.setVisible(false);
									hiddenSymbols.add(properties);
								}
							}
				for (org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer : layers) {
					if (!layer.isVisible())
						continue;
					if (ecef && (layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.ConfidenceLayer
							|| layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.PointShiftArrowLayer))
						continue;
					gc.save();
					try {
						gc.setTransform(new javafx.scene.transform.Affine());
						layer.draw(gc, extent);
					} catch (Exception e) {
						this.kleinMethod.getLogger().log("Klein_Method: Karte - Ebene " + layer.getLayerType() + " nicht zeichenbar: " + e.getMessage());
					} finally {
						gc.restore();
					}
				}
				for (org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties properties : hiddenSymbols)
					properties.setVisible(true);
				hiddenSymbols.clear();
				if (this.symbolCheckBox.isSelected())
					this.drawSymbolsOnPoints(gc, layers, extent);
			} finally {
				for (org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties properties : hiddenSymbols)
					properties.setVisible(true);
				for (Map.Entry<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, Saved_Point> entry : saved.entrySet()) {
					org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint point = entry.getKey();
					Saved_Point original = entry.getValue();
					point.setCoordinate(original.coordinate);
					point.setMajorAxis(original.majorAxis);
					point.setMinorAxis(original.minorAxis);
					point.setAngle(original.angle);
					setVectors(point, original.vectors);
					point.setVisible(original.visible);
				}
			}
		}

		/**
		 * Messart-Symbole genau auf den Punkten: je Punkt eine Torte aus allen Messarten der sichtbaren Beobachtungsebenen
		 * (Farben und Sichtbarkeit je Messart wie im Netzplot). Die Punkte stehen dabei schon in Kartenkoordinaten.
		 */
		private void drawSymbolsOnPoints(javafx.scene.canvas.GraphicsContext gc, List<org.applied_geodesy.jag3d.ui.graphic.layer.Layer> layers,
				org.applied_geodesy.jag3d.ui.graphic.util.GraphicExtent extent) {
			for (org.applied_geodesy.jag3d.ui.graphic.layer.Layer layer : layers) {
				if (!layer.isVisible() || !(layer instanceof org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer))
					continue;
				org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer observationLayer = (org.applied_geodesy.jag3d.ui.graphic.layer.ObservationLayer) layer;
				Map<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType, org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties> properties =
						new java.util.EnumMap<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType, org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties>(
								org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType.class);
				for (org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType type
						: org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType.values()) {
					org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties p = observationLayer.getObservationSymbolProperties(type);
					if (p != null)
						properties.put(type, p);
				}
				Map<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, java.util.Set<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType>> types =
						new java.util.IdentityHashMap<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, java.util.Set<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType>>();
				for (Object item : privateList(observationLayer, "observableMeasurements")) {
					if (!(item instanceof org.applied_geodesy.jag3d.ui.graphic.sql.ObservableMeasurement))
						continue;
					org.applied_geodesy.jag3d.ui.graphic.sql.ObservableMeasurement m = (org.applied_geodesy.jag3d.ui.graphic.sql.ObservableMeasurement) item;
					if (m.getStartPoint() != null && !m.getStartPointObservationType().isEmpty())
						types.computeIfAbsent(m.getStartPoint(), k -> new java.util.TreeSet<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType>())
								.addAll(m.getStartPointObservationType());
					if (m.getEndPoint() != null && !m.getEndPointObservationType().isEmpty())
						types.computeIfAbsent(m.getEndPoint(), k -> new java.util.TreeSet<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType>())
								.addAll(m.getEndPointObservationType());
				}
				double size = observationLayer.getSymbolSize();
				gc.save();
				try {
					gc.setTransform(new javafx.scene.transform.Affine());
					gc.setLineWidth(observationLayer.getLineWidth());
					gc.setStroke(observationLayer.getColor());
					for (Map.Entry<org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint, java.util.Set<org.applied_geodesy.jag3d.ui.graphic.layer.ObservationSymbolProperties.ObservationType>> e : types.entrySet()) {
						org.applied_geodesy.jag3d.ui.graphic.sql.GraphicPoint point = e.getKey();
						if (!point.isVisible() || point.getCoordinate() == null)
							continue;
						org.applied_geodesy.jag3d.ui.graphic.coordinate.PixelCoordinate pixel =
								org.applied_geodesy.jag3d.ui.graphic.util.GraphicExtent.toPixelCoordinate(point.getCoordinate(), extent);
						org.applied_geodesy.jag3d.ui.graphic.layer.symbol.SymbolBuilder.drawSymbol(gc,
								new org.applied_geodesy.jag3d.ui.graphic.coordinate.PixelCoordinate(pixel.getX() - 0.5 * size, pixel.getY() - 0.5 * size),
								properties, e.getValue(), size);
					}
				} catch (Exception e) {
					this.kleinMethod.getLogger().log("Klein_Method: Karte - Messart-Symbole nicht zeichenbar: " + e.getMessage());
				} finally {
					gc.restore();
				}
			}
		}

		/**
		 * Netzplot nach einem Kartenbild einmal neu zeichnen: die Karte verwendet beim Zeichnen kurz die Punkte und
		 * Symbol-Einstellungen des Netzplots (danach wiederhergestellt) - so zeigt der Netzplot sicher wieder seinen eigenen Stand.
		 */
		private void scheduleNetzplotRedraw() {
			if (this.netzplotRedrawPending)
				return;
			this.netzplotRedrawPending = true;
			Platform.runLater(() -> {
				this.netzplotRedrawPending = false;
				try {
					org.applied_geodesy.jag3d.ui.graphic.UIGraphicPaneBuilder.getInstance().getLayerManager().draw();
				} catch (Exception e) {
					// Netzplot nicht verfuegbar - nichts zu tun
				}
			});
		}

		/** Breite/Laenge -> Kartenebene [m am Bildmittelpunkt], Ursprung = Bildmitte, y nach Norden. */
		private double[] toPlane(double[] latLon, double metersPerWorldPixel) {
			double[] world = Klein_Method_Functions.Klein_Method_Geo.latLonToWorldPixel(latLon[0], latLon[1]);
			return new double[] { (world[0] - this.centerX) * metersPerWorldPixel, -(world[1] - this.centerY) * metersPerWorldPixel };
		}

		/** Vektor in JAG3D-Achsen (y, x, z = geozentrisch Y, X, Z) -> {Ost, Nord, Oben} * k. */
		private double[] ecefVectorToLocal(double alongY, double alongX, double alongZ, double[] latLon, double k) {
			double dX = this.swapXy ? alongY : alongX, dY = this.swapXy ? alongX : alongY;
			double[] enu = Klein_Method_Functions.Klein_Method_Geo.ecefVectorToEnu(dX, dY, alongZ, latLon[0], latLon[1]);
			return new double[] { enu[0] * k, enu[1] * k, enu[2] * k };
		}

		// ----------------------------------------------------------------- Max Fehler
		// ------------------------------------------------------------------

		/** Liest die 5 groessten Fehler je nach Ausgleichungsart (frei/fix: logPost, dynamisch: T_prio). */
		private void loadMaxErrors() {
			final List<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row> rows =
					new ArrayList<org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row>();
			final boolean[] points = new boolean[1];
			this.runDatabaseTask(() -> {
				org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler handler = this.kleinMethod.getDatabaseHandler();
				points[0] = this.kleinMethod.getCurrentAdjustmentStep() == Klein_Method_Adjustment_Step.DYNAMIC;
				rows.addAll(points[0] ? handler.readLargestPointErrors(MAX_ERROR_COUNT) : handler.readLargestObservationErrors(MAX_ERROR_COUNT));
			}, () -> {
				if (!this.maxErrorButton.isSelected())
					return;
				this.maxErrors.clear();
				this.maxErrors.addAll(rows);
				this.maxErrorsArePoints = points[0];
				this.maxErrorsLoaded = true;
				this.redraw();
			});
		}

		private static final Color MAX_ERROR_HEM = Color.rgb(211, 0, 0);
		private static final Color[] MAX_ERROR_COLORS = { Color.rgb(127, 0, 0), Color.rgb(183, 28, 28), Color.rgb(229, 57, 53),
				Color.rgb(239, 108, 0), Color.rgb(249, 168, 37) };

		public static String observationTypeName(org.applied_geodesy.adjustment.network.ObservationType type) {
			if (type == null)
				return "Punkt";
			switch (type) {
			case LEVELING:
				return "Nivellement";
			case DIRECTION:
				return "Richtung";
			case HORIZONTAL_DISTANCE:
				return "Horizontalstrecke";
			case SLOPE_DISTANCE:
				return "Schrägstrecke";
			case ZENITH_ANGLE:
				return "Zenitwinkel";
			case GNSS1D:
				return "GNSS-Basislinie 1D";
			case GNSS2D:
				return "GNSS-Basislinie 2D";
			case GNSS3D:
				return "GNSS-Basislinie 3D";
			default:
				return type.name();
			}
		}

		/** Verbesserung bzw. Verschiebung mit passender Einheit (mm/m bzw. mgon/gon). */
		public static String formatMaxErrorResidual(org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row row) {
			double r = Math.abs(row.residual);
			if (row.isAngle()) {
				double gon = r * 200.0 / Math.PI;
				return gon < 1.0 ? String.format(java.util.Locale.GERMANY, "%.1f mgon", gon * 1000.0)
						: String.format(java.util.Locale.GERMANY, "%.4f gon", gon);
			}
			return r < 1.0 ? String.format(java.util.Locale.GERMANY, "%.1f mm", r * 1000.0) : String.format(java.util.Locale.GERMANY, "%.3f m", r);
		}

		public static String formatMaxErrorValue(org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row row) {
			return row.isPoint() ? "T_prio " + String.format(java.util.Locale.GERMANY, "%.2f", row.value)
					: "logPost " + String.format(java.util.Locale.GERMANY, "%.1f", row.value);
		}

		// ----------------------------------------------------------------- Anschlussbereiche
		// ------------------------------------------------------------------

		private HBox buildAreaToolbar() {
			Label label = new Label(tr("Anschlussbereiche:"));
			String common = tr("Anschlussbereiche markieren (nur dynamische Ausgleichung). Die Bereiche werden im Projekt gespeichert.\n"
					+ "Jeder Bereich muss mindestens so viele gr\u00FCne Festpunkte enthalten, wie im Klein_Method-Tab bei\n"
					+ "\u201EGr\u00FCne Festpunkte je Ende / Anschlussbereich\u201C eingestellt ist - zus\u00E4tzlich zur Pr\u00FCfung der Verteilung.\n"
					+ "Die Zuordnung der Punkte wird bei jedem \u00D6ffnen der Karte und jeder \u00C4nderung neu bestimmt.");
			label.setTooltip(new Tooltip(common));
			this.areaCircleButton.setTooltip(new Tooltip(tr("Kreis: in der Mitte dr\u00FCcken und bis zum Rand ziehen, dann einen Namen vergeben.") + "\n\n" + common));
			this.areaRectangleButton.setTooltip(new Tooltip(tr("Viereck: an einer Ecke dr\u00FCcken und bis zur gegen\u00FCberliegenden Ecke ziehen, dann einen Namen vergeben.")
					+ "\n\n" + common));
			this.areaPolygonButton.setTooltip(new Tooltip(tr("Polygon: Ecken nacheinander anklicken, Doppelklick oder Rechtsklick schlie\u00DFt (mind. 3 Ecken).\n"
					+ "Verschieben der Karte bleibt m\u00F6glich. Erneut auf \u201EPolygon\u201C klicken = abbrechen.") + "\n\n" + common));
			this.areaDeleteButton.setTooltip(new Tooltip(tr("Einen markierten Anschlussbereich ausw\u00E4hlen und l\u00F6schen.")));
			for (ToggleButton button : new ToggleButton[] { this.areaCircleButton, this.areaRectangleButton, this.areaPolygonButton })
				button.setToggleGroup(this.areaToolGroup);
			this.areaToolGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
				this.polygonLatLon.clear();
				this.areaStartScreen = null;
				this.areaCurrentScreen = null;
				if (newValue != null && !this.isGeoreferenced()) {
					this.statusLabel.setText(tr("Zum Markieren muss die Karte zuerst eingepasst sein."));
					this.areaToolGroup.selectToggle(null);
					return;
				}
				if (newValue != null)
					this.setPassPointButton.setSelected(false);
				this.canvas.setCursor(newValue == null ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.CROSSHAIR);
				this.redraw();
			});
			this.areaDeleteButton.setOnAction(event -> this.deleteArea());
			HBox toolbar = new HBox(8, label, this.areaCircleButton, this.areaRectangleButton, this.areaPolygonButton, this.areaDeleteButton);
			toolbar.setAlignment(Pos.CENTER_LEFT);
			toolbar.setPadding(new Insets(0, 6, 4, 6));
			toolbar.setVisible(false);
			toolbar.setManaged(false);
			return toolbar;
		}

		private boolean isRectangularAreaTool() {
			return this.areaCircleButton.isSelected() || this.areaRectangleButton.isSelected();
		}

		private double[] screenToLatLon(double sx, double sy) {
			double[] world = this.screenToWorld(sx, sy);
			return Klein_Method_Functions.Klein_Method_Geo.worldPixelToLatLon(world[0], world[1]);
		}

		private static double distanceMeters(double[] a, double[] b) {
			double r = 6371000.0, lat = Math.toRadians(0.5 * (a[0] + b[0]));
			return r * Math.hypot(Math.toRadians(b[0] - a[0]), Math.toRadians(b[1] - a[1]) * Math.cos(lat));
		}

		private void finishRectangularArea(double[] start, double[] end) {
			boolean circle = this.areaCircleButton.isSelected();
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area = new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area(this.nextAreaId(), "", circle ? Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area.CIRCLE : Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area.RECTANGLE);
			if (circle) {
				double[] center = this.screenToLatLon(start[0], start[1]);
				area.latLon.add(center);
				area.radiusM = distanceMeters(center, this.screenToLatLon(end[0], end[1]));
			} else {
				area.latLon.add(this.screenToLatLon(start[0], start[1]));
				area.latLon.add(this.screenToLatLon(end[0], start[1]));
				area.latLon.add(this.screenToLatLon(end[0], end[1]));
				area.latLon.add(this.screenToLatLon(start[0], end[1]));
			}
			this.addNamedArea(area);
		}

		private void finishPolygonArea() {
			if (this.polygonLatLon.size() < 3) {
				this.statusLabel.setText(tr("Ein Polygon braucht mindestens 3 Ecken."));
				return;
			}
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area = new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area(this.nextAreaId(), "", Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area.POLYGON);
			area.latLon.addAll(this.polygonLatLon);
			this.polygonLatLon.clear();
			this.areaCurrentScreen = null;
			this.addNamedArea(area);
		}

		private int nextAreaId() {
			int id = 0;
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area : this.areas)
				id = Math.max(id, area.id);
			return id + 1;
		}

		/** Namen abfragen, Punkte zuordnen, speichern. */
		private void addNamedArea(Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area draft) {
			javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
					String.format(tr("Anschlussbereich %d"), draft.id));
			dialog.setTitle(tr("Klein_Method - Anschlussbereich"));
			dialog.setHeaderText(tr("Name des Anschlussbereichs"));
			dialog.setContentText(tr("Name:"));
			java.util.Optional<String> answer = dialog.showAndWait();
			this.areaToolGroup.selectToggle(null);
			if (!answer.isPresent() || answer.get().trim().isEmpty()) {
				this.redraw();
				return;
			}
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area = new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area(draft.id, answer.get().trim(), draft.shape);
			area.latLon.addAll(draft.latLon);
			area.radiusM = draft.radiusM;
			this.assignAreaPoints(area);
			this.areas.add(area);
			this.kleinMethod.getLogger().log("Klein_Method: Anschlussbereich \"" + area.name + "\" (" + area.shape + ") mit " + area.pointNames.size()
					+ " Punkt(en) markiert.");
			this.saveAreas();
		}

		private void deleteArea() {
			if (this.areas.isEmpty()) {
				this.statusLabel.setText(tr("Es sind keine Anschlussbereiche markiert."));
				return;
			}
			List<String> names = new ArrayList<String>();
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area : this.areas)
				names.add(area.id + ": " + area.name);
			javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<String>(names.get(0), names);
			dialog.setTitle(tr("Klein_Method - Anschlussbereich"));
			dialog.setHeaderText(tr("Welcher Anschlussbereich soll gel\u00F6scht werden?"));
			dialog.setContentText(tr("Bereich:"));
			java.util.Optional<String> answer = dialog.showAndWait();
			if (!answer.isPresent())
				return;
			int index = names.indexOf(answer.get());
			if (index < 0)
				return;
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area removed = this.areas.remove(index);
			this.kleinMethod.getLogger().log("Klein_Method: Anschlussbereich \"" + removed.name + "\" geloescht.");
			this.saveAreas();
		}

		/** Punkte (a-priori-Lage) im Bereich bestimmen. */
		private void assignAreaPoints(Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area) {
			area.pointNames.clear();
			for (int i = 0; i < this.localPoints.length; i++) {
				String name = this.pointNames.get(i);
				double[] ll = this.toLatLon(name, this.localPoints[i][0], this.localPoints[i][1]);
				if (ll != null && !Double.isNaN(ll[0]) && !Double.isNaN(ll[1]) && area.contains(ll[0], ll[1]))
					area.pointNames.add(name);
			}
		}

		/** Nach Laden/Einpassen: Zuordnung neu bestimmen und bei Aenderung speichern. */
		private void syncAreaMembership() {
			if (!this.areasLoaded || this.areas.isEmpty() || !this.isGeoreferenced())
				return;
			boolean changed = false;
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area : this.areas) {
				List<String> before = new ArrayList<String>(area.pointNames);
				this.assignAreaPoints(area);
				if (!before.equals(area.pointNames))
					changed = true;
			}
			if (changed)
				this.saveAreas();
		}

		private void saveAreas() {
			final List<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area> copy = new ArrayList<Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area>(this.areas);
			this.runDatabaseTask(() -> this.kleinMethod.getDatabaseHandler().replaceConnectionAreas(copy), () -> {
				Thread thread = Klein_Method.busyThread(() -> {
					this.kleinMethod.refreshFixedPointRecommendation();
					Platform.runLater(() -> {
						if (this.recommendationButton.isSelected())
							this.loadRecommendation();
					});
				}, "Klein_Method_Area_Recommendation");
				thread.setDaemon(true);
				thread.start();
				this.redraw();
			});
		}

		private static final Color AREA_COLOR = Color.rgb(239, 108, 0);

		private void drawAreas(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			Font font = Font.font("System", FontWeight.BOLD, 12);
			gc.setFont(font);
			for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area area : this.areas) {
				double labelX, labelY;
				gc.setFill(AREA_COLOR.deriveColor(0, 1, 1, 0.12));
				gc.setStroke(AREA_COLOR);
				gc.setLineWidth(2.0);
				if (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Area.CIRCLE.equals(area.shape)) {
					double[] center = this.latLonToScreen(area.latLon.get(0)[0], area.latLon.get(0)[1]);
					double[] edge = this.latLonToScreen(area.latLon.get(0)[0] + Math.toDegrees(area.radiusM / 6371000.0), area.latLon.get(0)[1]);
					double r = Math.abs(edge[1] - center[1]);
					gc.fillOval(center[0] - r, center[1] - r, 2 * r, 2 * r);
					gc.strokeOval(center[0] - r, center[1] - r, 2 * r, 2 * r);
					labelX = center[0] - r;
					labelY = center[1] - r - 4;
				} else {
					double[] xs = new double[area.latLon.size()], ys = new double[area.latLon.size()];
					labelX = Double.POSITIVE_INFINITY;
					labelY = Double.POSITIVE_INFINITY;
					for (int k = 0; k < xs.length; k++) {
						double[] xy = this.latLonToScreen(area.latLon.get(k)[0], area.latLon.get(k)[1]);
						xs[k] = xy[0];
						ys[k] = xy[1];
						if (xy[1] < labelY) {
							labelY = xy[1];
							labelX = xy[0];
						}
					}
					gc.fillPolygon(xs, ys, xs.length);
					gc.strokePolygon(xs, ys, xs.length);
					labelY -= 4;
				}
				String label = area.name + String.format(tr(" (%d Punkte)"), area.pointNames.size());
				javafx.scene.text.Text measure = new javafx.scene.text.Text(label);
				measure.setFont(font);
				double w = measure.getLayoutBounds().getWidth() + 8;
				gc.setFill(Color.rgb(255, 255, 255, 0.85));
				gc.fillRect(labelX, labelY - 15, w, 18);
				gc.setFill(AREA_COLOR.darker());
				gc.fillText(label, labelX + 4, labelY - 1);
			}
			// Vorschau beim Zeichnen
			gc.setStroke(AREA_COLOR);
			gc.setLineWidth(2.0);
			gc.setLineDashes(6, 4);
			if (this.areaStartScreen != null && this.areaCurrentScreen != null) {
				double[] a = this.areaStartScreen, b = this.areaCurrentScreen;
				if (this.areaCircleButton.isSelected()) {
					double r = Math.hypot(b[0] - a[0], b[1] - a[1]);
					gc.strokeOval(a[0] - r, a[1] - r, 2 * r, 2 * r);
				} else
					gc.strokeRect(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
			}
			if (this.areaPolygonButton.isSelected() && !this.polygonLatLon.isEmpty()) {
				double[] previous = null;
				for (double[] ll : this.polygonLatLon) {
					double[] xy = this.latLonToScreen(ll[0], ll[1]);
					gc.setFill(AREA_COLOR);
					gc.fillOval(xy[0] - 3, xy[1] - 3, 6, 6);
					if (previous != null)
						gc.strokeLine(previous[0], previous[1], xy[0], xy[1]);
					previous = xy;
				}
				if (previous != null && this.areaCurrentScreen != null)
					gc.strokeLine(previous[0], previous[1], this.areaCurrentScreen[0], this.areaCurrentScreen[1]);
			}
			gc.setLineDashes((double[]) null);
		}

		// ----------------------------------------------------------------- Festpunkt-Empfehlung
		// ------------------------------------------------------------------

		private void updateRecommendationButtonVisibility() {
			boolean dynamic = this.kleinMethod.isDynamicStepShown();
			this.recommendationButton.setVisible(dynamic);
			this.recommendationButton.setManaged(dynamic);
			if (this.areaToolbar != null) {
				this.areaToolbar.setVisible(dynamic);
				this.areaToolbar.setManaged(dynamic);
				if (!dynamic && this.areaToolGroup.getSelectedToggle() != null)
					this.areaToolGroup.selectToggle(null);
			}
			if (!dynamic && this.recommendationButton.isSelected())
				this.recommendationButton.setSelected(false);
		}

		private void loadRecommendation() {
			final Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result[] holder =
					new Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result[1];
			this.runDatabaseTask(() -> holder[0] = this.kleinMethod.computeFixedPointRecommendation(), () -> {
				if (!this.recommendationButton.isSelected())
					return;
				this.recommendation = holder[0];
				this.redraw();
			});
		}

		private static Color ratingColor(Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Rating rating) {
			switch (rating) {
			case GREEN:
				return Color.rgb(46, 125, 50);
			case YELLOW:
				return Color.rgb(249, 168, 37);
			case RED:
				return Color.rgb(198, 40, 40);
			case FIXED:
				return Color.rgb(21, 101, 192);
			default:
				return Color.rgb(120, 120, 120);
			}
		}

		/** Schaltflaeche mit JAG3D-Symbol (wie im Netzplot); ohne Symbol mit Text. */
		private <B extends javafx.scene.control.ButtonBase> B plotButton(B button, String icon, String text, String tooltip) {
			try (java.io.InputStream in = org.applied_geodesy.jag3d.ui.graphic.layer.LayerManager.class.getResourceAsStream("/org/applied_geodesy/ui/gfx/" + icon)) {
				if (in != null) {
					javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(in));
					view.setFitWidth(20);
					view.setFitHeight(20);
					view.setPreserveRatio(true);
					button.setGraphic(view);
				} else
					button.setText(text);
			} catch (Exception e) {
				button.setText(text);
			}
			button.setTooltip(new Tooltip(tooltip));
			return button;
		}

		/** Bedienleiste wie im JAG3D-Netzplot. */
		private HBox buildPlotToolbar(Button zoomNetworkButton, Button reloadButton) {
			javafx.scene.control.ToggleGroup mode = new javafx.scene.control.ToggleGroup();
			this.plotButton(this.toolMoveButton, "graphic_move_32x32.png", tr("Verschieben"), tr("Verschieben: Karte mit gedrückter Maustaste ziehen (Mausrad = zoomen)."));
			this.plotButton(this.toolWindowZoomButton, "graphic_window_zoom_32x32.png", tr("Fensterzoom"), tr("Fensterzoom: Rechteck aufziehen, auf das gezoomt wird."));
			this.toolMoveButton.setToggleGroup(mode);
			this.toolWindowZoomButton.setToggleGroup(mode);
			this.toolMoveButton.setSelected(true);
			mode.selectedToggleProperty().addListener((obs, was, now) -> {
				if (now == null)
					this.toolMoveButton.setSelected(true);
			});
			Button zoomIn = this.plotButton(new Button(), "graphic_zoom_in_32x32.png", tr("Vergrößern"), tr("Vergrößern (Mitte der Karte bleibt)."));
			zoomIn.setOnAction(event -> this.zoomBy(1.0));
			Button zoomOut = this.plotButton(new Button(), "graphic_zoom_out_32x32.png", tr("Verkleinern"), tr("Verkleinern (Mitte der Karte bleibt)."));
			zoomOut.setOnAction(event -> this.zoomBy(-1.0));
			Button expand = this.plotButton(new Button(), "graphic_expand_32x32.png", tr("Gesamtansicht"), tr("Gesamtansicht: auf alle Punkte zoomen, auch weit entfernte Stationen (z. B. GNSS-Referenzstationen)."));
			expand.setOnAction(event -> this.zoomToNetwork(true));
			Button refresh = this.plotButton(new Button(), "graphic_refresh_32x32.png", tr("Neu zeichnen"), tr("Neu zeichnen: Daten und Ebenen aus dem Projekt neu laden."));
			refresh.setOnAction(event -> reloadButton.fire());
			Button layers = this.plotButton(new Button(), "graphic_layer_32x32.png", tr("Ebenen"), tr("Ebenen: Eigenschaften der Ebenen des Netzplots (Punkte, Beobachtungen, Ellipsen, Farben) - "
					+ "wirkt im Netzplot und in der Karte."));
			layers.setOnAction(event -> this.openLayerProperties());
			Button export = this.plotButton(new Button(), "graphic_export_32x32.png", tr("Export"), tr("Karte als Bild (PNG) speichern, mit Quellenangabe der Kartendaten."));
			export.setOnAction(event -> this.exportMapImage());
			Button feature = this.plotButton(new Button(), "graphic_feature_zoom_32x32.png", tr("Punkt suchen"), tr("Auf einen Punkt zoomen: Punktnummer eingeben."));
			feature.setOnAction(event -> this.zoomToPointByName());
			HBox bar = new HBox(4, this.toolMoveButton, this.toolWindowZoomButton, zoomIn, zoomOut, expand, refresh, new Separator(Orientation.VERTICAL), layers, export, feature);
			bar.setAlignment(Pos.CENTER_LEFT);
			bar.setPadding(new Insets(0, 6, 4, 6));
			return bar;
		}

		private void zoomBy(double step) {
			this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, this.zoom + step));
			this.redraw();
		}

		/** auf ein Bildschirm-Rechteck zoomen (Fensterzoom). */
		private void zoomToScreenRectangle(double[] a, double[] b) {
			double[] wa = this.screenToWorld(a[0], a[1]), wb = this.screenToWorld(b[0], b[1]);
			double spanX = Math.abs(wb[0] - wa[0]), spanY = Math.abs(wb[1] - wa[1]);
			if (spanX <= 0 || spanY <= 0)
				return;
			this.centerX = 0.5 * (wa[0] + wb[0]);
			this.centerY = 0.5 * (wa[1] + wb[1]);
			double newZoom = Math.log(Math.min(this.canvas.getWidth() / spanX, this.canvas.getHeight() / spanY)) / Math.log(2.0);
			this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
		}

		/** Ebenen-Dialog des JAG3D-Netzplots (dieselben Ebenen wie in der Karte). */
		private void openLayerProperties() {
			try {
				org.applied_geodesy.jag3d.ui.graphic.layer.LayerManager layerManager = org.applied_geodesy.jag3d.ui.graphic.UIGraphicPaneBuilder.getInstance().getLayerManager();
				java.lang.reflect.Method method = layerManager.getClass().getDeclaredMethod("layerProperties");
				method.setAccessible(true);
				method.invoke(layerManager);
				this.redraw();
			} catch (Exception e) {
				this.statusLabel.setText(tr("Ebenen-Dialog des Netzplots nicht verfügbar: ") + e);
			}
		}

		/** Karte als PNG speichern (Quellenangabe der Kartendaten unten rechts). */
		private void exportMapImage() {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Karte als Bild speichern"));
			chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PNG", "*.png"));
			chooser.setInitialFileName("Klein_Method_Karte.png");
			java.io.File file = chooser.showSaveDialog(this.stage);
			if (file == null)
				return;
			try {
				javafx.scene.image.WritableImage image = this.canvas.snapshot(null, null);
				int w = (int) image.getWidth(), h = (int) image.getHeight();
				java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
				javafx.scene.image.PixelReader reader = image.getPixelReader();
				for (int y = 0; y < h; y++)
					for (int x = 0; x < w; x++)
						out.setRGB(x, y, reader.getArgb(x, y));
				java.awt.Graphics2D g = out.createGraphics();
				g.setColor(new java.awt.Color(255, 255, 255, 200));
				g.fillRect(w - 230, h - 18, 230, 18);
				g.setColor(java.awt.Color.DARK_GRAY);
				g.drawString("Kartendaten \u00A9 OpenStreetMap-Mitwirkende", w - 225, h - 5);
				g.dispose();
				javax.imageio.ImageIO.write(out, "png", file);
				this.statusLabel.setText(tr("Karte gespeichert: ") + file.getAbsolutePath());
			} catch (Exception e) {
				this.statusLabel.setText(tr("Karte konnte nicht gespeichert werden: ") + e.getMessage());
			}
		}

		/** auf einen Netzpunkt zentrieren und zoomen (wie "Merkmalszoom" im JAG3D-Netzplot: Punktliste und Skalierungsfaktor). */
		private void zoomToPointByName() {
			List<String> names = new ArrayList<String>();
			for (String name : this.pointNames)
				if (name != null && !this.outlierNames.contains(name) && this.localByName.containsKey(name))
					names.add(name);
			java.util.Collections.sort(names);
			if (names.isEmpty()) {
				this.statusLabel.setText(tr("Keine Punkte in der Karte darstellbar."));
				return;
			}
			javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<ButtonType>();
			dialog.initOwner(this.stage);
			dialog.setTitle(tr("Punkt suchen"));
			dialog.setHeaderText(tr("Auf Punkt zentrieren und zoomen"));
			ComboBox<String> pointBox = new ComboBox<String>();
			pointBox.getItems().addAll(names);
			pointBox.setValue(this.lastFeatureZoomPoint != null && names.contains(this.lastFeatureZoomPoint) ? this.lastFeatureZoomPoint : names.get(0));
			pointBox.setEditable(true);
			pointBox.setPrefWidth(240);
			pointBox.setTooltip(new Tooltip(tr("Punkt, auf den die Karte zentriert wird (auswählen oder Punktnummer eintippen).")));
			javafx.scene.control.Slider scale = new javafx.scene.control.Slider(0.25, 1.75, this.lastFeatureZoomScale);
			scale.setShowTickMarks(true);
			scale.setShowTickLabels(true);
			scale.setMajorTickUnit(0.5);
			scale.setMinorTickCount(1);
			scale.setPrefWidth(240);
			scale.setTooltip(new Tooltip(tr("Skalierungsfaktor wie im JAG3D-Netzplot: bezogen auf die aktuelle Ansicht - 1 = Maßstab bleibt, nur zentrieren; kleiner = näher heran (0,5 = doppelt so nah), größer = weiter weg.")));
			javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
			grid.setHgap(10);
			grid.setVgap(12);
			grid.add(new Label(tr("Punkt:")), 0, 0);
			grid.add(pointBox, 1, 0);
			grid.add(new Label(tr("Skalierungsfaktor:")), 0, 1);
			grid.add(scale, 1, 1);
			dialog.getDialogPane().setContent(grid);
			dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
			if (!dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
				return;
			String key = pointBox.getValue() == null ? "" : pointBox.getValue().trim();
			double[] local = this.localByName.get(key);
			double[] ll = local == null || this.outlierNames.contains(key) ? null : this.toLatLon(key, local[0], local[1]);
			if (ll == null || Double.isNaN(ll[0]) || Double.isNaN(ll[1])) {
				this.statusLabel.setText(String.format(tr("Punkt %s nicht gefunden bzw. nicht in der Karte darstellbar."), key));
				return;
			}
			this.lastFeatureZoomPoint = key;
			this.lastFeatureZoomScale = scale.getValue();
			double[] world = Klein_Method_Functions.Klein_Method_Geo.latLonToWorldPixel(ll[0], ll[1]);
			this.centerX = world[0];
			this.centerY = world[1];
			// wie JAG3D (FeatureZoomDialog): Massstab der aktuellen Ansicht mal Skalierungsfaktor
			this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, this.zoom - Math.log(this.lastFeatureZoomScale) / Math.log(2.0)));
			this.highlightName = key;
			this.redraw();
		}

		/** Prognose der Messplanung: gefuellter Hof unter dem Punkt (gelb bzw. rot = schwach bestimmt). */
		private void drawPrognosis(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			for (Map.Entry<String, Integer> e : prognosisLevels.entrySet()) {
				double[] xy = this.screenOfPoint(e.getKey());
				if (xy == null)
					continue;
				double r = e.getValue() >= 2 ? 13.0 : 10.0;
				gc.setFill(e.getValue() >= 2 ? Color.rgb(198, 40, 40, 0.25) : Color.rgb(230, 160, 0, 0.25));
				gc.fillOval(xy[0] - r, xy[1] - r, 2 * r, 2 * r);
			}
		}

		/** Punkte ausserhalb der GNSS-Stuetzung: gestrichelter Ring (gelb bzw. rot). */
		private void drawGnssSupport(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			gc.setLineDashes(4, 3);
			gc.setLineWidth(2.0);
			for (Map.Entry<String, Integer> e : gnssSupportLevels.entrySet()) {
				double[] xy = this.screenOfPoint(e.getKey());
				if (xy == null)
					continue;
				double r = 9.0;
				gc.setStroke(e.getValue() >= 2 ? Color.rgb(198, 40, 40, 0.9) : Color.rgb(230, 160, 0, 0.9));
				gc.strokeOval(xy[0] - r, xy[1] - r, 2 * r, 2 * r);
			}
			gc.setLineDashes((double[]) null);
		}
		private void drawRecommendation(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result r = this.recommendation;
			boolean georeferenced = this.isGeoreferenced();
			int notShown = 0;
			if (georeferenced) {
				// Achse bzw. Umrandung
				List<String> outline = r.elongated ? r.axisNames : r.hullNames;
				List<double[]> screen = new ArrayList<double[]>();
				for (String name : outline) {
					double[] xy = this.screenOfPoint(name);
					if (xy != null)
						screen.add(xy);
				}
				if (screen.size() >= 2) {
					gc.setLineDashes(10, 6);
					gc.setLineWidth(2.5);
					if (r.elongated) {
						gc.setStroke(Color.rgb(21, 101, 192, 0.9));
						for (int k = 1; k < screen.size(); k++)
							gc.strokeLine(screen.get(k - 1)[0], screen.get(k - 1)[1], screen.get(k)[0], screen.get(k)[1]);
					} else {
						double[] xs = new double[screen.size()], ys = new double[screen.size()];
						for (int k = 0; k < screen.size(); k++) {
							xs[k] = screen.get(k)[0];
							ys[k] = screen.get(k)[1];
						}
						gc.setFill(Color.rgb(46, 125, 50, 0.08));
						gc.fillPolygon(xs, ys, xs.length);
						gc.setStroke(Color.rgb(46, 125, 50, 0.9));
						gc.strokePolygon(xs, ys, xs.length);
					}
					gc.setLineDashes((double[]) null);
				}
				Font labelFont = Font.font("System", FontWeight.BOLD, 11);
				gc.setFont(labelFont);
				for (Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Point p : r.points) {
					boolean isNew = p.kind == Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.KIND_NEW;
					if (isNew && !p.outside)
						continue;
					double[] xy = this.screenOfPoint(p.name);
					if (xy == null) {
						if (!isNew)
							notShown++;
						continue;
					}
					if (isNew) {
						double d = 7.0;
						gc.setStroke(Color.rgb(198, 40, 40));
						gc.setLineWidth(3.0);
						gc.strokeLine(xy[0] - d, xy[1] - d, xy[0] + d, xy[1] + d);
						gc.strokeLine(xy[0] - d, xy[1] + d, xy[0] + d, xy[1] - d);
						continue;
					}
					Color color = ratingColor(p.rating);
					double radius = 10.0;
					gc.setFill(color.deriveColor(0, 1, 1, 0.25));
					gc.fillOval(xy[0] - radius, xy[1] - radius, 2 * radius, 2 * radius);
					gc.setStroke(color);
					gc.setLineWidth(3.0);
					gc.strokeOval(xy[0] - radius, xy[1] - radius, 2 * radius, 2 * radius);
					if (!Double.isNaN(p.sigmaMm)) {
						String label = String.format(java.util.Locale.GERMANY, "%.1f mm", p.sigmaMm) + " \u00B7 "
								+ Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.zoneLabel(p) + (p.leveled3dHint ? " \u24D8" : "");
						gc.setFill(Color.rgb(255, 255, 255, 0.85));
						javafx.scene.text.Text measure = new javafx.scene.text.Text(label);
						measure.setFont(labelFont);
						double w = measure.getLayoutBounds().getWidth() + 6;
						gc.fillRect(xy[0] + radius + 2, xy[1] - 8, w, 15);
						gc.setFill(color.darker());
						gc.fillText(label, xy[0] + radius + 5, xy[1] + 4);
					}
				}
			}
			// Ergebnis unten links
			List<String> lines = new ArrayList<String>();
			lines.add(r.recommended ? tr("Festpunkt-Empfehlung: empfohlen") : tr("Festpunkt-Empfehlung: nicht empfohlen"));
			lines.addAll(java.util.Arrays.asList(this.kleinMethod.recommendationSummary(r).split("\n")));
			for (int k = 0; k < Math.min(3, r.reasons.size()); k++)
				lines.add("\u2022 " + r.reasons.get(k));
			if (r.reasons.size() > 3)
				lines.add(String.format(tr("\u2026 und %d weitere (siehe Bericht)"), r.reasons.size() - 3));
			if (!r.outsideNewNames.isEmpty())
				lines.add(tr("rotes Kreuz = Neupunkt au\u00DFerhalb der gr\u00FCnen Festpunkte"));
			lines.add(r.elongated ? tr("blau gestrichelt = Achse des Netzes") : tr("gr\u00FCn gestrichelt = Umrandung der gr\u00FCnen Festpunkte"));
			if (!georeferenced)
				lines.add(tr("(Karte nicht eingepasst - Punkte nicht darstellbar)"));
			else if (notShown > 0)
				lines.add(String.format(tr("%d Punkt(e) nicht in der Karte"), notShown));
			Font font = Font.font("System", 12);
			gc.setFont(font);
			double maxWidth = 0.0;
			for (String line : lines) {
				javafx.scene.text.Text measure = new javafx.scene.text.Text(line);
				measure.setFont(font);
				maxWidth = Math.max(maxWidth, measure.getLayoutBounds().getWidth());
			}
			maxWidth = Math.min(maxWidth, this.canvas.getWidth() - 40);
			double lineHeight = 16.0, boxHeight = lines.size() * lineHeight + 10;
			double x0 = 8, y0 = this.canvas.getHeight() - boxHeight - 26;
			gc.setFill(Color.rgb(255, 255, 255, 0.92));
			gc.fillRect(x0, y0, maxWidth + 16, boxHeight);
			gc.setStroke(r.recommended ? Color.rgb(46, 125, 50) : Color.rgb(198, 40, 40));
			gc.setLineWidth(2.0);
			gc.strokeRect(x0, y0, maxWidth + 16, boxHeight);
			for (int k = 0; k < lines.size(); k++) {
				gc.setFill(k == 0 ? (r.recommended ? Color.rgb(46, 125, 50) : Color.rgb(198, 40, 40)) : Color.rgb(40, 40, 40));
				gc.fillText(lines.get(k), x0 + 8, y0 + 18 + k * lineHeight, maxWidth);
			}
		}

		/** Bildschirmlage eines Netzpunkts (a-priori-Koordinaten), oder null. */
		private double[] screenOfPoint(String name) {
			double[] local = name == null ? null : this.localByName.get(name);
			if (local == null)
				return null;
			double[] ll = this.toLatLon(name, local[0], local[1]);
			if (ll == null || Double.isNaN(ll[0]) || Double.isNaN(ll[1]))
				return null;
			return this.latLonToScreen(ll[0], ll[1]);
		}

		/** Rot umrandete Markierung (Kapsel um die Linie bzw. Ring um den Punkt), Wertangabe und Liste oben links. */
		private void drawMaxErrors(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			if (!this.maxErrorsLoaded)
				return;
			boolean georeferenced = this.isGeoreferenced();
			List<String> legend = new ArrayList<String>();
			Font labelFont = Font.font("System", FontWeight.BOLD, 12);
			// in umgekehrter Reihenfolge zeichnen: Rang 1 liegt oben
			for (int i = this.maxErrors.size() - 1; i >= 0; i--) {
				org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row row = this.maxErrors.get(i);
				Color color = MAX_ERROR_COLORS[Math.min(i, MAX_ERROR_COLORS.length - 1)];
				double[] a = georeferenced ? this.screenOfPoint(row.startPointName) : null;
				double[] b = georeferenced && !row.isPoint() ? this.screenOfPoint(row.endPointName) : null;
				boolean drawn = a != null && (row.isPoint() || b != null);
				String residualText = (row.isPoint() ? "Δ = " : "v = ") + formatMaxErrorResidual(row);
				legend.add(0, (i + 1) + "  " + observationTypeName(row.observationType) + " " + row.startPointName
						+ (row.isPoint() ? "" : " – " + row.endPointName) + "   " + formatMaxErrorValue(row) + "   " + residualText
						+ (drawn ? "" : "   (nicht in der Karte)"));
				if (!drawn)
					continue;
				double labelX, labelY;
				if (row.isPoint()) {
					double r = 14.0;
					gc.setFill(color.deriveColor(0, 1, 1, 0.30));
					gc.fillOval(a[0] - r, a[1] - r, 2 * r, 2 * r);
					gc.setStroke(MAX_ERROR_HEM);
					gc.setLineWidth(3.0);
					gc.strokeOval(a[0] - r, a[1] - r, 2 * r, 2 * r);
					labelX = a[0] + r + 4;
					labelY = a[1] - r;
				} else {
					double r = 9.0, dx = b[0] - a[0], dy = b[1] - a[1], length = Math.hypot(dx, dy);
					gc.save();
					gc.translate(a[0], a[1]);
					gc.rotate(Math.toDegrees(Math.atan2(dy, dx)));
					gc.setFill(color.deriveColor(0, 1, 1, 0.30));
					gc.fillRoundRect(-r, -r, length + 2 * r, 2 * r, 2 * r, 2 * r);
					gc.setStroke(MAX_ERROR_HEM);
					gc.setLineWidth(3.0);
					gc.strokeRoundRect(-r, -r, length + 2 * r, 2 * r, 2 * r, 2 * r);
					gc.restore();
					// Beschriftung neben der Linienmitte, senkrecht versetzt
					double nx = length > 0 ? -dy / length : 0.0, ny = length > 0 ? dx / length : -1.0;
					if (ny > 0) {
						nx = -nx;
						ny = -ny;
					}
					labelX = 0.5 * (a[0] + b[0]) + nx * (r + 6);
					labelY = 0.5 * (a[1] + b[1]) + ny * (r + 6);
				}
				String label = (i + 1) + ": " + formatMaxErrorValue(row) + " | " + residualText;
				gc.setFont(labelFont);
				javafx.scene.text.Text measure = new javafx.scene.text.Text(label);
				measure.setFont(labelFont);
				double w = measure.getLayoutBounds().getWidth() + 8, h = measure.getLayoutBounds().getHeight() + 4;
				gc.setFill(Color.rgb(255, 255, 255, 0.9));
				gc.fillRect(labelX, labelY - h, w, h);
				gc.setStroke(MAX_ERROR_HEM);
				gc.setLineWidth(1.5);
				gc.strokeRect(labelX, labelY - h, w, h);
				gc.setFill(color);
				gc.fillText(label, labelX + 4, labelY - 5);
			}

			// Liste oben links
			String title = "Max Fehler – " + (this.maxErrorsArePoints ? "dynamische Ausgleichung (T_prio, Punkte)" : "logPost, alle Beobachtungstypen");
			if (this.maxErrors.isEmpty())
				legend.add("Keine Ergebnisse - bitte zuerst eine Ausgleichung rechnen (F5).");
			else if (!georeferenced)
				legend.add("Netz noch nicht auf der Karte - Markierung erst nach dem Einpassen.");
			Font legendFont = Font.font("System", 12);
			javafx.scene.text.Text titleMeasure = new javafx.scene.text.Text(title);
			titleMeasure.setFont(labelFont);
			double width = titleMeasure.getLayoutBounds().getWidth();
			for (String line : legend) {
				javafx.scene.text.Text measure = new javafx.scene.text.Text(line);
				measure.setFont(legendFont);
				width = Math.max(width, measure.getLayoutBounds().getWidth() + 18);
			}
			double x0 = 10, y0 = 10, lineHeight = 17, boxHeight = 24 + lineHeight * legend.size();
			gc.setFill(Color.rgb(255, 255, 255, 0.92));
			gc.fillRect(x0, y0, width + 16, boxHeight);
			gc.setStroke(MAX_ERROR_HEM);
			gc.setLineWidth(2.0);
			gc.strokeRect(x0, y0, width + 16, boxHeight);
			gc.setFont(labelFont);
			gc.setFill(MAX_ERROR_HEM);
			gc.fillText(title, x0 + 8, y0 + 17);
			gc.setFont(legendFont);
			for (int k = 0; k < legend.size(); k++) {
				double y = y0 + 17 + lineHeight * (k + 1);
				boolean ranked = k < this.maxErrors.size();
				if (ranked) {
					gc.setFill(MAX_ERROR_COLORS[Math.min(k, MAX_ERROR_COLORS.length - 1)]);
					gc.fillRect(x0 + 8, y - 10, 10, 10);
				}
				gc.setFill(Color.rgb(40, 40, 40));
				gc.fillText(legend.get(k), x0 + (ranked ? 24 : 8), y);
			}
		}

		/** vor Phase 1 herausgenommene grobe Fehler - rot gestrichelte Linie, Hinweis unten links. */
		private void drawExcludedErrors(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			if (this.excludedErrors.isEmpty())
				return;
			gc.save();
			gc.setStroke(Color.rgb(211, 0, 0));
			gc.setLineWidth(3.0);
			gc.setLineDashes(9.0, 6.0);
			for (org.applied_geodesy.klein_method.database.Klein_Method_Database_Handler_Interface.Max_Error_Row row : this.excludedErrors) {
				double[] a = this.screenOfPoint(row.startPointName), b = this.screenOfPoint(row.endPointName);
				if (a != null && b != null)
					gc.strokeLine(a[0], a[1], b[0], b[1]);
			}
			gc.restore();
			String text = this.excludedErrors.size() + " grobe(r) Fehler vorübergehend herausgenommen (rot gestrichelt)";
			gc.setFont(Font.font("System", FontWeight.BOLD, 12));
			javafx.scene.text.Text measure = new javafx.scene.text.Text(text);
			measure.setFont(Font.font("System", FontWeight.BOLD, 12));
			double w = measure.getLayoutBounds().getWidth() + 12, h = 20, y = this.canvas.getHeight() - h - 24;
			gc.setFill(Color.rgb(255, 255, 255, 0.9));
			gc.fillRect(8, y, w, h);
			gc.setStroke(Color.rgb(211, 0, 0));
			gc.setLineWidth(1.5);
			gc.strokeRect(8, y, w, h);
			gc.setFill(Color.rgb(211, 0, 0));
			gc.fillText(text, 14, y + 14);
		}

		/** Geklickte Passpunkte: roter Kreis mit Namen. */
		private void drawPassPoints(javafx.scene.canvas.GraphicsContext gc) {
			gc.setTransform(new javafx.scene.transform.Affine());
			if (this.source.kind != Map_Source.Kind.CLICK)
				return;
			gc.setFont(Font.font("System", FontWeight.BOLD, 12));
			for (Map.Entry<String, double[]> entry : this.passPoints.entrySet()) {
				double[] screen = this.latLonToScreen(entry.getValue()[0], entry.getValue()[1]);
				gc.setStroke(Color.rgb(192, 57, 43));
				gc.setLineWidth(2.0);
				gc.strokeOval(screen[0] - 7, screen[1] - 7, 14, 14);
				gc.strokeLine(screen[0] - 11, screen[1], screen[0] + 11, screen[1]);
				gc.strokeLine(screen[0], screen[1] - 11, screen[0], screen[1] + 11);
				gc.setFill(Color.rgb(192, 57, 43));
				gc.fillText(entry.getKey(), screen[0] + 10, screen[1] - 10);
			}
		}
	}

	// ============================================================================================================
	/** Bereich Optionen (Einstellungen, Gruppen-Daempfungs-Gitter, Bewertungsmethode). */
	private final class Options_Part {

		/**
		 * Baut den Inhalt der Gruppe "Optionen": Toggle "Verteilung" und Toggle "Turbo" (ja/nein, Standard ja). Kuenftige
		 * Optionen-Toggles kommen einfach mit in diese VBox.
		 */
		private VBox createOptionsSection() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			// Selected=false -> NORMAL, Selected=true -> PERCENTAGE (Reihenfolge/Zuordnung willkuerlich, aber fest -
			// siehe applyDistributionSelection()). Vorbelegung (welcher Zustand beim Erkennen von freier/fixer
			// Ausgleichung automatisch vorausgewaehlt wird) kommt NICHT von hier, sondern von
			// Klein_Method.refreshDistributionState() (ruft applyDistributionSelection() bei jedem Tab-Aktivieren neu
			// auf).
			Label distributionLabel = new Label(tr("Verteilung:"));
			// Anfangszustand aus dem (bereits geladenen) Parameter statt fest "normal".
			boolean startPercentage = parameter.getUserDistributionType() == Klein_Method_Distribution_Type.PERCENTAGE;
			Klein_Method_Tab_Builder.this.distributionToggleButton = new ToggleButton(tr(parameter.getUserDistributionType().getDisplayName()));
			Klein_Method_Tab_Builder.this.distributionToggleButton.setSelected(startPercentage);
			Klein_Method_Tab_Builder.this.distributionToggleButton.setTooltip(new Tooltip(startPercentage
					? tr("Prozentuale Verteilung der Werte.") : tr("Normale Verteilung der Werte.")));
			Klein_Method_Tab_Builder.this.distributionToggleButton.setOnAction(event -> {
				Klein_Method_Distribution_Type selected = Klein_Method_Tab_Builder.this.distributionToggleButton.isSelected()
						? Klein_Method_Distribution_Type.PERCENTAGE : Klein_Method_Distribution_Type.NORMAL;
				Klein_Method_Tab_Builder.this.distributionToggleButton.setText(tr(selected.getDisplayName()));
				Klein_Method_Tab_Builder.this.distributionToggleButton.setTooltip(new Tooltip(selected == Klein_Method_Distribution_Type.PERCENTAGE
						? tr("Prozentuale Verteilung der Werte.") : tr("Normale Verteilung der Werte.")));
				parameter.setDistributionType(selected);
				Klein_Method_Tab_Builder.this.updateDampingFormulaAvailability();
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Verteilung auf '" + selected.getDisplayName() + "' gesetzt.");
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox distributionRow = new HBox(10, distributionLabel, Klein_Method_Tab_Builder.this.distributionToggleButton);
			Klein_Method_Tab_Builder.this.distributionRow = distributionRow;
			// Seit im Rechenkern tatsaechlich verdrahtet (Klein_Method_Functions.performIndividualCalibrationStep(),
			// liest parameter.getDistributionType()).
			// siehe applyDistributionEnabled() und Klein_Method.refreshDistributionState(). Initial (vor dem ersten
			// Tab-Aktivieren, wenn der tatsaechliche Ausgleichungs-schritt noch nicht bekannt ist) startet der Toggle
			// sicherheitshalber deaktiviert.
			distributionRow.setVisible(true);
			distributionRow.setManaged(true);
			Klein_Method_Tab_Builder.this.distributionToggleButton.setDisable(true);

			// -- Trichter-Faktor: steuert die Breite des Bearbeitungsbandes in Phase 2 (siehe
			// Klein_Method_Functions.computeLogPostBandLowerBound()), hart begrenzt auf [0.2; 0.3], Standard 0.30 ---
			Label funnelFactorLabel = new Label(tr("Trichter-Faktor:"));
			TextField funnelFactorField = new TextField(String.valueOf(parameter.getFunnelFactor()));
			funnelFactorField.setPrefWidth(100);
			funnelFactorField.setTooltip(new Tooltip(tr("Steuert die Breite des Bearbeitungsbandes in Phase 2. Ein gr\u00F6\u00DFerer "
					+ "Faktor (0.30) \u00F6ffnet den Trichter weiter, verarbeitet mehr Beobachtungen simultan und verringert die "
					+ "Anzahl der ben\u00F6tigten Iterationen drastisch. Ein kleinerer Faktor (0.20) verengt das Band.")));
			Klein_Method_Tab_Builder.this.lastAppliedFunnelFactor = parameter.getFunnelFactor();

			Runnable applyFunnelFactor = () -> {
				try {
					double enteredValue = Double.parseDouble(funnelFactorField.getText().replace(",", "."));
					double correctedValue = parameter.setFunnelFactor(enteredValue);
					if (correctedValue != enteredValue) {
						funnelFactorField.setText(String.valueOf(correctedValue));
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Trichter-Faktor war " + enteredValue
								+ ", auf " + correctedValue + " korrigiert (erlaubter Bereich [0.2; 0.3]).");
					}
					if (correctedValue != Klein_Method_Tab_Builder.this.lastAppliedFunnelFactor) {
						Klein_Method_Tab_Builder.this.lastAppliedFunnelFactor = correctedValue;
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Trichter-Faktor ge\u00E4ndert"));
						alert.setHeaderText(null);
						alert.setContentText(tr("Achtung: Der Trichter-Faktor wurde ge\u00E4ndert. Ein h\u00F6herer Faktor beschleunigt "
								+ "die Konvergenz massiv, w\u00E4hrend ein kleinerer Faktor die Gewichts\u00E4nderungen feiner aufsplittet."));
						alert.showAndWait();
					}
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				} catch (NumberFormatException e) {
					funnelFactorField.setText(String.valueOf(parameter.getFunnelFactor()));
				}
			};
			funnelFactorField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					applyFunnelFactor.run();
			});
			funnelFactorField.setOnAction(event -> applyFunnelFactor.run());

			HBox funnelFactorRow = new HBox(10, funnelFactorLabel, funnelFactorField);

			// -- eigener, eng begrenzter Trichter-Faktor NUR fuer NIVELLEMENT_LOG_POST, Standard 0.05, hart begrenzt auf
			// [0.01; 0.10] - siehe Klein_Method_Parameter.setNivellementFunnelFactor() und Klein_Method_Functions'
			// Phase-2-Bandbreiten-Berechnung. Exakt dasselbe Bedien-/Hinweismuster wie beim allgemeinen Trichter-Faktor
			// oben. ---
			Label nivellementFunnelFactorLabel = new Label(tr("Nivellement-Trichter-Faktor:"));
			TextField nivellementFunnelFactorField = new TextField(String.valueOf(parameter.getNivellementFunnelFactor()));
			nivellementFunnelFactorField.setPrefWidth(100);
			nivellementFunnelFactorField.setTooltip(new Tooltip(tr("Eigener, enger Trichter-Faktor NUR f\u00FCr Nivellementsz\u00FCge "
					+ "(getrennt vom allgemeinen Trichter-Faktor oben). F\u00E4ngt systematische Schleppfehler isoliert ab, ohne "
					+ "gesunde H\u00F6henz\u00FCge zu verschmieren. Standard 0.05, Bereich [0.01; 0.10].")));
			final double[] lastAppliedNivellementFunnelFactor = { parameter.getNivellementFunnelFactor() };

			Runnable applyNivellementFunnelFactor = () -> {
				try {
					double enteredValue = Double.parseDouble(nivellementFunnelFactorField.getText().replace(",", "."));
					double correctedValue = parameter.setNivellementFunnelFactor(enteredValue);
					if (correctedValue != enteredValue) {
						nivellementFunnelFactorField.setText(String.valueOf(correctedValue));
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Nivellement-Trichter-Faktor war " + enteredValue
								+ ", auf " + correctedValue + " korrigiert (erlaubter Bereich [0.01; 0.10]).");
					}
					if (correctedValue != lastAppliedNivellementFunnelFactor[0]) {
						lastAppliedNivellementFunnelFactor[0] = correctedValue;
						Alert alert = new Alert(AlertType.INFORMATION);
						alert.setTitle(tr("Klein_Method - Nivellement-Trichter-Faktor ge\u00E4ndert"));
						alert.setHeaderText(null);
						alert.setContentText(tr("Achtung: Der Nivellement-Trichter-Faktor wurde ge\u00E4ndert. Er wirkt AUSSCHLIESSLICH "
								+ "auf Nivellementsz\u00FCge (Typ LEVELING) - alle anderen Beobachtungstypen nutzen weiterhin den "
								+ "allgemeinen Trichter-Faktor."));
						alert.showAndWait();
					}
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				} catch (NumberFormatException e) {
					nivellementFunnelFactorField.setText(String.valueOf(parameter.getNivellementFunnelFactor()));
				}
			};
			nivellementFunnelFactorField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					applyNivellementFunnelFactor.run();
			});
			nivellementFunnelFactorField.setOnAction(event -> applyNivellementFunnelFactor.run());

			HBox nivellementFunnelFactorRow = new HBox(10, nivellementFunnelFactorLabel, nivellementFunnelFactorField);

			// -- (Auftrag "Geometrischer Fangkreis-Waechter (Vor-Scan)", als ehrliche Warn-Variante umgesetzt - siehe
			// Klein_Method_Parameter. FANGKREIS_RADIUS_DEFAULT fuer die vollstaendige fachliche Begruendung, warum HIER
			// bewusst NICHT autonom in der HSQLDB umnummeriert wird): Radius fuer den Vor-Scan gleichnamiger Punkte vor
			// dem Start. ---
			Label fangkreisRadiusLabel = new Label(tr("Fangkreis-Radius (m):"));
			TextField fangkreisRadiusField = new TextField(String.valueOf(parameter.getFangkreisRadius()));
			fangkreisRadiusField.setPrefWidth(100);
			fangkreisRadiusField.setTooltip(new Tooltip(tr("Vor jedem Start werden alle Punktnamen gepr\u00FCft, die MEHRFACH "
					+ "vorkommen (z.B. in mehreren Punktgruppen). Liegen ihre Lagen weiter als dieser Radius auseinander, warnt "
					+ "Klein_Method VOR dem Start (Protokollzeile + Dialog) - eine automatische Umnummerierung findet NICHT statt, "
					+ "die Entscheidung bleibt bei Ihnen. Standard 1.0 m.")));

			Runnable applyFangkreisRadius = () -> {
				try {
					double enteredValue = Double.parseDouble(fangkreisRadiusField.getText().replace(",", "."));
					double correctedValue = parameter.setFangkreisRadius(enteredValue);
					if (correctedValue != enteredValue) {
						fangkreisRadiusField.setText(String.valueOf(correctedValue));
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Fangkreis-Radius war " + enteredValue
								+ ", auf " + correctedValue + " korrigiert (erlaubter Bereich [0.01; 10.0] m).");
					}
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				} catch (NumberFormatException e) {
					fangkreisRadiusField.setText(String.valueOf(parameter.getFangkreisRadius()));
				}
			};
			fangkreisRadiusField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					applyFangkreisRadius.run();
			});
			fangkreisRadiusField.setOnAction(event -> applyFangkreisRadius.run());

			HBox fangkreisRadiusRow = new HBox(10, fangkreisRadiusLabel, fangkreisRadiusField);

			// -- siehe Klein_Method_Parameter. COLLECTIVE_DELTA_DEFAULT fuer die vollstaendige Begruendung, warum dies
			// den bestehenden logarithmischen Trichter-Faktor ERGAENZT statt ersetzt: die tatsaechliche Bandbreite ist
			// immer die GROSSZUEGIGERE der beiden. ---
			Label collectiveDeltaLabel = new Label(tr("Kollektiver Delta-Trichter:"));
			TextField collectiveDeltaField = new TextField(String.valueOf(parameter.getCollectiveDelta()));
			collectiveDeltaField.setPrefWidth(100);
			collectiveDeltaField.setTooltip(new Tooltip(tr("Alle Beobachtungen eines Typs, deren |logPost| innerhalb dieser "
					+ "linearen Nachbarschaft um den aktuellen Max-logPost-Wert liegen ([Max_logPost - Delta; Max_logPost]), "
					+ "werden im selben Iterationsschritt KOLLEKTIV gedaempft. Ergaenzt (erweitert) den bestehenden, "
					+ "logarithmischen Trichter-Faktor, ersetzt ihn NICHT - wirksam ist stets das groesszuegigere der beiden "
					+ "Baender. Standard 0.20 (logPost-Einheiten, einheitenlos).")));

			Runnable applyCollectiveDelta = () -> {
				try {
					double enteredValue = Double.parseDouble(collectiveDeltaField.getText().replace(",", "."));
					double correctedValue = parameter.setCollectiveDelta(enteredValue);
					if (correctedValue != enteredValue) {
						collectiveDeltaField.setText(String.valueOf(correctedValue));
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Kollektiver Delta-Trichter war " + enteredValue
								+ ", auf " + correctedValue + " korrigiert (erlaubter Bereich [0.05; 1.00]).");
					}
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				} catch (NumberFormatException e) {
					collectiveDeltaField.setText(String.valueOf(parameter.getCollectiveDelta()));
				}
			};
			collectiveDeltaField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					applyCollectiveDelta.run();
			});
			collectiveDeltaField.setOnAction(event -> applyCollectiveDelta.run());

			HBox collectiveDeltaRow = new HBox(10, collectiveDeltaLabel, collectiveDeltaField);

			// -- Untergrenze erzwingen: ja/nein, Standard ja. UI-Hinweis: hier bewusst ein binaerer RadioButton-Toggle
			// (identisches Muster wie "Turbo" oben), NICHT der vom Anwender erwaehnte fliessende Waechter-Farbverlauf -
			// jener stellt einen KONTINUIERLICHEN Belastungszustand dar, waehrend dieser Schalter ein echtes EIN/AUS ohne
			// Zwischenstufe ist. Steuert Klein_Method_Parameter.isFloorEnforcementEnabled() ->
			// Klein_Method_Functions.performFloorEnforcementCompression(), siehe dort fuer die vollstaendige fachliche
			// Begruendung. ---
			Label floorEnforcementLabel = new Label(tr("Untergrenze erzwingen:"));
			ToggleGroup floorEnforcementGroup = new ToggleGroup();

			RadioButton floorEnforcementYesRadio = new RadioButton(tr("ja (empfohlen)"));
			floorEnforcementYesRadio.setToggleGroup(floorEnforcementGroup);
			floorEnforcementYesRadio.setUserData(Boolean.TRUE);
			floorEnforcementYesRadio.setSelected(parameter.isFloorEnforcementEnabled());
			floorEnforcementYesRadio.setTooltip(new Tooltip(tr("Erzwingt beim Uebergang zu PHASE: DONE einmalig eine "
					+ "Abschluss-Kompression aller bereits individuell kalibrierten sigma_0-Werte in Richtung der "
					+ "geodaetischen Mindestwerte (sigma_Boden, z.B. 0.5 mm bei Horizontalstrecke): "
					+ "sigma_neu = sigma_Boden + (sigma_alt - sigma_Boden) * 0.4. Wirkt NUR bei diesem einen Uebergang, "
					+ "NICHT waehrend der laufenden Iteration (dort weiterhin keine Untergrenze, User-Vorgabe V0.308).")));

			RadioButton floorEnforcementNoRadio = new RadioButton(tr("nein"));
			floorEnforcementNoRadio.setToggleGroup(floorEnforcementGroup);
			floorEnforcementNoRadio.setUserData(Boolean.FALSE);
			floorEnforcementNoRadio.setSelected(!parameter.isFloorEnforcementEnabled());
			floorEnforcementNoRadio.setTooltip(new Tooltip(tr("Keine Abschluss-Kompression: individuell kalibrierte sigma_0-Werte "
					+ "bleiben beim Uebergang zu PHASE: DONE unveraendert (fruehere V0.306/V0.307-Vorgabe, keine Untergrenze).")));

			floorEnforcementGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setFloorEnforcementEnabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Untergrenze erzwingen " + (selected ? "aktiviert." : "deaktiviert."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox floorEnforcementRow = new HBox(10, floorEnforcementLabel, floorEnforcementYesRadio, floorEnforcementNoRadio);

			// -- Schlussangleichung an das VKS-Band: nach fertiger Automatik Genauigkeiten von Messarten unter dem Band verkleinern
			Label finalScalingLabel = new Label(tr("Schlussangleichung VKS:"));
			ToggleGroup finalScalingGroup = new ToggleGroup();
			RadioButton finalScalingYesRadio = new RadioButton(tr("ja (empfohlen)"));
			finalScalingYesRadio.setToggleGroup(finalScalingGroup);
			finalScalingYesRadio.setUserData(Boolean.TRUE);
			finalScalingYesRadio.setSelected(parameter.isFinalScaling());
			finalScalingYesRadio.setTooltip(new Tooltip(tr("Nach fertiger Automatik: Liegt der Varianzfaktor einer Messart unter dem VKS-Band, weil Untergrenzen "
					+ "ihre Genauigkeiten festhalten (Genauigkeiten zu pessimistisch), werden alle Genauigkeiten dieser Messart mit Wurzel(Faktor) verkleinert "
					+ "und neu ausgeglichen - höchstens 3 Runden, je Runde höchstens halbiert. Log und Protokoll: SCHLUSSANGLEICHUNG VKS.")));
			RadioButton finalScalingNoRadio = new RadioButton(tr("nein"));
			finalScalingNoRadio.setToggleGroup(finalScalingGroup);
			finalScalingNoRadio.setUserData(Boolean.FALSE);
			finalScalingNoRadio.setSelected(!parameter.isFinalScaling());
			finalScalingNoRadio.setTooltip(new Tooltip(tr("Keine Schlussangleichung: Genauigkeiten bleiben an den Untergrenzen, der Kalibrierungsnachweis meldet "
					+ "nur „Untergrenze prüfen“.")));
			finalScalingGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setFinalScaling(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Schlussangleichung VKS " + (selected ? "ein." : "aus."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			HBox finalScalingRow = new HBox(10, finalScalingLabel, finalScalingYesRadio, finalScalingNoRadio);

			// -- Grobe Fehler automatisch bereinigen (vor Durchlauf 1) und laengenabhaengige Grenze fuer GNSS
			Label autoGrossLabel = new Label(tr("Grobe Fehler automatisch bereinigen:"));
			ToggleGroup autoGrossGroup = new ToggleGroup();
			RadioButton autoGrossYesRadio = new RadioButton(tr("ja (empfohlen)"));
			autoGrossYesRadio.setToggleGroup(autoGrossGroup);
			autoGrossYesRadio.setUserData(Boolean.TRUE);
			autoGrossYesRadio.setSelected(parameter.isAutoGrossErrorCleanup());
			autoGrossYesRadio.setTooltip(new Tooltip(tr("Liegt beim Start der Automatik (freie/fixe AGL) eine Beobachtung über der Grenze grober Fehler, läuft "
					+ "„Grobe Fehler bereinigen“ selbst – vor Durchlauf 1, damit auch die Gruppenwerte aus sauberen Daten geschätzt werden.\n"
					+ "Schrittweise mit Gegenprobe; das Netz bleibt bestimmbar. Ohne Bestätigungsfenster: jede Abschaltung steht im Protokoll "
					+ "(GROBE FEHLER BEREINIGEN (AUTOMATIK)); vor Phase 2 fragt Klein_Method wie bisher nach der Zuordnung.")));
			RadioButton autoGrossNoRadio = new RadioButton(tr("nein"));
			autoGrossNoRadio.setToggleGroup(autoGrossGroup);
			autoGrossNoRadio.setUserData(Boolean.FALSE);
			autoGrossNoRadio.setSelected(!parameter.isAutoGrossErrorCleanup());
			autoGrossNoRadio.setTooltip(new Tooltip(tr("Wie bisher: bei vermuteten groben Fehlern ist der Start gesperrt, bis „Grobe Fehler bereinigen“ von Hand gelaufen ist.")));
			autoGrossGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setAutoGrossErrorCleanup(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Grobe Fehler automatisch bereinigen " + (selected ? "ein." : "aus."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				Klein_Method_Tab_Builder.this.refreshAutomationAvailability();
			});
			Label grossPpmLabel = new Label(tr("GNSS-Zuschlag (ppm):"));
			TextField grossPpmField = new TextField(Klein_Method_Format.format(parameter.getGrossErrorGnssPpm(), "", 1));
			grossPpmField.setPrefWidth(60);
			grossPpmField.setTooltip(new Tooltip(tr("Längenabhängiger Zuschlag zur Grenze grober Fehler für GNSS-Basislinien: Grenze = Grenze grober Fehler + ppm × Länge.\n"
					+ "Beispiel 0,100 m + 2 ppm: 10 km → 0,120 m, 40 km → 0,180 m. Lange SAPOS-Basislinien werden so nicht wegen normaler "
					+ "Troposphären- und Höheneffekte abgeschaltet.\nStandard 2,0, erlaubt 0 bis 20, 0 = feste Grenze.")));
			grossPpmLabel.setTooltip(grossPpmField.getTooltip());
			Runnable applyPpm = () -> {
				double value;
				try {
					value = Double.parseDouble(grossPpmField.getText().trim().replace(',', '.'));
				} catch (NumberFormatException e) {
					value = Double.NaN;
				}
				double stored = parameter.setGrossErrorGnssPpm(value);
				grossPpmField.setText(Klein_Method_Format.format(stored, "", 1));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			};
			grossPpmField.setOnAction(event -> applyPpm.run());
			grossPpmField.focusedProperty().addListener((obs, was, is) -> {
				if (!is)
					applyPpm.run();
			});
			HBox autoGrossRow = new HBox(10, autoGrossLabel, autoGrossYesRadio, autoGrossNoRadio, grossPpmLabel, grossPpmField);
			autoGrossRow.setAlignment(Pos.CENTER_LEFT);

			// -- Kaskaden-Doppellauf: ja/nein, Standard ja. Direkt neben "Untergrenze erzwingen" platziert (identisches
			// RadioButton-Muster). Steuert Klein_Method_Parameter.isCascadeDoubleRunEnabled() ->
			// Klein_Method_Iteration_Task, siehe dort fuer die vollstaendige Ablaufsteuerung (Pass 1/Pass 2). ---
			Label cascadeDoubleRunLabel = new Label(tr("Kaskaden-Mehrfachlauf:"));
			ToggleGroup cascadeDoubleRunGroup = new ToggleGroup();

			RadioButton cascadeDoubleRunYesRadio = new RadioButton(tr("ja (empfohlen)"));
			cascadeDoubleRunYesRadio.setToggleGroup(cascadeDoubleRunGroup);
			cascadeDoubleRunYesRadio.setUserData(Boolean.TRUE);
			cascadeDoubleRunYesRadio.setSelected(parameter.isCascadeDoubleRunEnabled());
			cascadeDoubleRunYesRadio.setTooltip(new Tooltip(tr("V0.353:\n"
					+ "Durchlauf 1 endet bei Max-logPost unter 2,45. Ist \u201EUntergrenze erzwingen\u201C und/oder \u201EGruppen-\u03C3 nach DL 1\u201C "
					+ "eingeschaltet, werden die Werte dort zur\u00FCckgesetzt und Durchlauf 2 startet.\n"
					+ "Ab Durchlauf 2 beendet Max-logPost unter der \u201EAbbruchgrenze ab DL 2\u201C (Standard 5,2) bzw. unter 2,45 die Berechnung (ohne weitere Untergrenzen-Erzwingung).\n"
					+ "Endet ein Durchlauf am Grenzwert Iterationen ohne 2,45, startet der n\u00E4chste.\n"
					+ "Abbruchziel: Durchlauf 3 = bester Max-logPost aus Durchlauf 2 (nur unter 5,2), ab Durchlauf 4 = 5,2. Maximal 20 Durchl\u00E4ufe.")));

			RadioButton cascadeDoubleRunNoRadio = new RadioButton(tr("nein"));
			cascadeDoubleRunNoRadio.setToggleGroup(cascadeDoubleRunGroup);
			cascadeDoubleRunNoRadio.setUserData(Boolean.FALSE);
			cascadeDoubleRunNoRadio.setSelected(!parameter.isCascadeDoubleRunEnabled());
			cascadeDoubleRunNoRadio.setTooltip(new Tooltip(tr("Kein weiterer Durchlauf: die Automatik endet bei PHASE: DONE "
					+ "oder am Grenzwert Iterationen.")));

			cascadeDoubleRunGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setCascadeDoubleRunEnabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Kaskaden-Mehrfachlauf " + (selected ? "aktiviert." : "deaktiviert."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox cascadeDoubleRunRow = new HBox(10, cascadeDoubleRunLabel, cascadeDoubleRunYesRadio, cascadeDoubleRunNoRadio);

			// -- Deaktivieren BL: ja/nein (empfohlen), Standard nein (empfohlen). Exakt vertikal UNTERHALB von
			// "Kaskaden-Mehrfachlauf:" platziert (identisches RadioButton-Muster). Steuert
			// Klein_Method_Parameter.isDeactivateGnssOutlierEnabled() ->
			// Klein_Method_Functions.performIndividualCalibrationStep(), siehe dort fuer die vollstaendige
			// Ablaufsteuerung UND den Fundstellen-Hinweis zur Begrenzung des Wirkungsbereichs. ---
			Label deactivateGnssOutlierLabel = new Label(tr("Deaktivieren BL:"));
			ToggleGroup deactivateGnssOutlierGroup = new ToggleGroup();

			RadioButton deactivateGnssOutlierYesRadio = new RadioButton("ja");
			deactivateGnssOutlierYesRadio.setToggleGroup(deactivateGnssOutlierGroup);
			deactivateGnssOutlierYesRadio.setUserData(Boolean.TRUE);
			deactivateGnssOutlierYesRadio.setSelected(parameter.isDeactivateGnssOutlierEnabled());
			deactivateGnssOutlierYesRadio.setTooltip(new Tooltip(tr("Eine GNSS-Basislinie, deren |p_post| die Trichter-Kappungsgrenze "
					+ "(aktuell > 1000.0, siehe Klein_Method_Default_Values.GNSS_HARD_OUTLIER_LOG_POST_THRESHOLD) ueberschreitet, wird "
					+ "wie bisher seit V0.282 sofort per enable=false in der HSQLDB deaktiviert.\n"
					+ "V0.346: ebenso eine Basislinie mit Achsen-Uebersteuerung im Modus \u201Eprozentual\u201C "
					+ "(Achse < 0,1 mm oder > 35 mm).")));

			RadioButton deactivateGnssOutlierNoRadio = new RadioButton(tr("nein (empfohlen)"));
			deactivateGnssOutlierNoRadio.setToggleGroup(deactivateGnssOutlierGroup);
			deactivateGnssOutlierNoRadio.setUserData(Boolean.FALSE);
			deactivateGnssOutlierNoRadio.setSelected(!parameter.isDeactivateGnssOutlierEnabled());
			deactivateGnssOutlierNoRadio.setTooltip(new Tooltip(tr("Die betroffene GNSS-Basislinie bleibt zu 100% AKTIV im Netz und wird "
					+ "ueber die Kaskade weiter Richtung Boden gedaempft, jedoch NIEMALS automatisch inaktiv geschaltet - verhindert den "
					+ "unkontrollierten Verlust geometrischer Redundanz. V0.346: gilt auch fuer die Achsen-Uebersteuerung "
					+ "im Modus \u201Eprozentual\u201C - die Achsenwerte werden dann auf 0,1-35 mm geklemmt, die Basislinie bleibt aktiv.")));

			deactivateGnssOutlierGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setDeactivateGnssOutlierEnabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Automatisches Deaktivieren von GNSS-Basislinien (Trichter-Eliminierung) "
						+ (selected ? "aktiviert." : "deaktiviert - betroffene Basislinien bleiben aktiv."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox deactivateGnssOutlierRow = new HBox(10, deactivateGnssOutlierLabel, deactivateGnssOutlierYesRadio, deactivateGnssOutlierNoRadio);

			// -- Gruppen-Daempfung in Phase 2: an / aus. Direkt UNTER "Deaktivieren BL:", identisches RadioButton-Muster.
			// ---
			Label groupDampingPhase2Label = new Label(tr("Gruppen-D\u00E4mpfung Phase 2:"));
			ToggleGroup groupDampingPhase2Group = new ToggleGroup();

			RadioButton groupDampingPhase2OnRadio = new RadioButton("ja");
			groupDampingPhase2OnRadio.setToggleGroup(groupDampingPhase2Group);
			groupDampingPhase2OnRadio.setUserData(Boolean.TRUE);
			groupDampingPhase2OnRadio.setSelected(parameter.isGroupDampingInPhase2Enabled());
			groupDampingPhase2OnRadio.setTooltip(new Tooltip(tr("Ablauf V0.342-V0.344: Phase 1 nur ein Versuch je Durchlauf, danach werden "
					+ "sigma_a/b/c in JEDER Iteration der Phase 2 per Ur-Formel nachgeregelt.")));

			RadioButton groupDampingPhase2OffRadio = new RadioButton(tr("nein (empfohlen)"));
			groupDampingPhase2OffRadio.setToggleGroup(groupDampingPhase2Group);
			groupDampingPhase2OffRadio.setUserData(Boolean.FALSE);
			groupDampingPhase2OffRadio.setSelected(!parameter.isGroupDampingInPhase2Enabled());
			groupDampingPhase2OffRadio.setTooltip(new Tooltip(tr("Ablauf V0.299: Phase 1 kalibriert sigma_a/b/c so lange, bis alle Gruppen im "
					+ "VKS-Korridor oder an der Untergrenze sind. W\u00E4hrend Phase 2 bleiben die Gruppenwerte danach unver\u00E4ndert.")));

			groupDampingPhase2Group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setGroupDampingInPhase2Enabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Gruppen-Daempfung in Phase 2 " + (selected ? "eingeschaltet." : "ausgeschaltet (Ablauf wie V0.299)."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox groupDampingPhase2Row = new HBox(10, groupDampingPhase2Label, groupDampingPhase2OnRadio, groupDampingPhase2OffRadio);

			// -- Gruppen-sigma nach Durchlauf 1 auf Untergrenze: ja (empfohlen) / nein. Direkt UNTER "Gruppen-Daempfung
			// Phase 2:", identisches RadioButton-Muster. ---
			Label groupFloorAfterPass1Label = new Label(tr("Gruppen-\u03C3 nach DL 1 auf Untergrenze:"));
			ToggleGroup groupFloorAfterPass1Group = new ToggleGroup();

			RadioButton groupFloorAfterPass1YesRadio = new RadioButton(tr("ja (empfohlen)"));
			groupFloorAfterPass1YesRadio.setToggleGroup(groupFloorAfterPass1Group);
			groupFloorAfterPass1YesRadio.setUserData(Boolean.TRUE);
			groupFloorAfterPass1YesRadio.setSelected(parameter.isGroupFloorAfterPass1Enabled());
			groupFloorAfterPass1YesRadio.setTooltip(new Tooltip(tr("Nach dem ersten Durchlauf des Kaskaden-Mehrfachlaufs werden sigma_a/b/c aller "
					+ "Gruppen auf ihre Untergrenze (Grenzwerte Gruppenunsicherheit) gesetzt. In allen weiteren Durchl\u00E4ufen bleibt "
					+ "Phase 1 gesperrt, damit der Wert nicht wieder hochgeregelt wird.\n"
					+ "Wirkt nur mit \u201EKaskaden-Mehrfachlauf: ja\u201C: am Ende von Durchlauf 1 (DONE oder Grenzwert Iterationen) vor dem Start von Durchlauf 2.")));

			RadioButton groupFloorAfterPass1NoRadio = new RadioButton(tr("nein"));
			groupFloorAfterPass1NoRadio.setToggleGroup(groupFloorAfterPass1Group);
			groupFloorAfterPass1NoRadio.setUserData(Boolean.FALSE);
			groupFloorAfterPass1NoRadio.setSelected(!parameter.isGroupFloorAfterPass1Enabled());
			groupFloorAfterPass1NoRadio.setTooltip(new Tooltip(tr("Jeder Durchlauf beginnt wie bisher mit Phase 1 (Gruppenkalibrierung per VKS).")));

			groupFloorAfterPass1Group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setGroupFloorAfterPass1Enabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Gruppen-sigma nach Durchlauf 1 auf Untergrenze " + (selected ? "eingeschaltet." : "ausgeschaltet."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox groupFloorAfterPass1Row = new HBox(10, groupFloorAfterPass1Label, groupFloorAfterPass1YesRadio, groupFloorAfterPass1NoRadio);

			// --- Turbo: ja/nein, Standard ja ---
			Label turboLabel = new Label(tr("Turbo:"));
			ToggleGroup turboGroup = new ToggleGroup();

			RadioButton turboYesRadio = new RadioButton("ja");
			turboYesRadio.setToggleGroup(turboGroup);
			turboYesRadio.setUserData(Boolean.TRUE);
			turboYesRadio.setSelected(parameter.isTurboEnabled());
			// kurze Tooltips, wann der Schalter greift
			turboYesRadio.setTooltip(new Tooltip(tr("Beschleunigt die Einzelkalibrierung der Festpunkte (dynamische AGL, Phase 2), "
					+ "sobald die Hälfte fertig ist.\nNur beim Verkleinern. Bei Richtungswechsel 5 Schritte Pause, beim 2. Wechsel aus.\n"
					+ "Steht T_prio eines Punkts unter dem Band dreimal in Folge fast still, obwohl sigma verkleinert wird,\n"
					+ "geht der Punkt direkt auf die Untergrenze – das gilt sofort, nicht erst ab der Hälfte.\n"
					+ "Phase 1 (Punktgruppen) rechnet immer ohne Turbo.")));

			RadioButton turboNoRadio = new RadioButton(tr("nein"));
			turboNoRadio.setToggleGroup(turboGroup);
			turboNoRadio.setUserData(Boolean.FALSE);
			turboNoRadio.setSelected(!parameter.isTurboEnabled());
			turboNoRadio.setTooltip(new Tooltip(tr("Einzelkalibrierung ohne Beschleunigung.")));

			turboGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setTurboEnabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Turbo " + (selected ? "aktiviert." : "deaktiviert."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox turboRow = new HBox(10, turboLabel, turboYesRadio, turboNoRadio);
			Klein_Method_Tab_Builder.this.turboRow = turboRow;
			// Seit /241 fuer die individuelle Festpunktkalibrierung ("Phase 2") tatsaechlich verdrahtet - sichtbar bleibt
			// der Schalter unveraendert NUR bei erkannter dynamischer Ausgleichung (initial ausgeblendet, bis
			// refreshAdjustmentStepDependentVisibility() beim ersten Tab-Aktivieren den tatsaechlichen Schritt kennt).
			turboRow.setVisible(false);
			turboRow.setManaged(false);

			// --- Protokoll: keine/kurz/lang, Standard kurz, sperrt sich selbst nach
			// der ersten protokollierten Berechnung (siehe Klein_Method_Protocol) ---
			Klein_Method_Protocol protocol = Klein_Method_Tab_Builder.this.kleinMethod.getProtocol();

			Klein_Method_Tab_Builder.this.protocolLevelComboBox = new ComboBox<Klein_Method_Protocol_Level>();
			Klein_Method_Tab_Builder.this.protocolLevelComboBox.getItems().addAll(Klein_Method_Protocol_Level.values());
			Klein_Method_Tab_Builder.this.protocolLevelComboBox.setValue(protocol.getLevel());
			Klein_Method_Tab_Builder.this.protocolLevelComboBox.setConverter(new StringConverter<Klein_Method_Protocol_Level>() {
				@Override
				public String toString(Klein_Method_Protocol_Level level) {
					return level == null ? "" : tr(level.getDisplayName());
				}
				@Override
				public Klein_Method_Protocol_Level fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			Klein_Method_Tab_Builder.this.protocolLevelComboBox.setTooltip(new Tooltip(tr("Protokolliert Rechenergebnisse dieses Projekts in eine eigene Datei.\n"
					+ "keine: kein Protokoll.\nkurz: eine Zeile je abgeschlossener Iteration.\nlang: zus\u00E4tzlich ALLE einzelnen "
					+ "sigma_a/sigma_b/sigma_c- bzw. sigma_0-Anpassungen dieser Iteration.\nSperrt sich automatisch, sobald die erste "
					+ "Berechnung protokolliert wurde, damit keine L\u00FCcke entsteht.\nSpeicherort: ") + Klein_Method_Protocol.getProtocolFolderPath()));

			Klein_Method_Tab_Builder.this.protocolLevelComboBox.setOnAction(event -> {
				protocol.setLevel(Klein_Method_Tab_Builder.this.protocolLevelComboBox.getValue());
				Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
			});

			Button openProtocolFolderButton = new Button(tr("Ordner \u00F6ffnen"));
			openProtocolFolderButton.setTooltip(new Tooltip(tr("\u00D6ffnet den Ordner mit den Protokolldateien im Datei-Explorer:\n")
					+ Klein_Method_Protocol.getProtocolFolderPath()));
			openProtocolFolderButton.setOnAction(event -> {
				try {
					File folder = new File(Klein_Method_Protocol.getProtocolFolderPath());
					if (!folder.exists())
						folder.mkdirs();
					if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
						Desktop.getDesktop().open(folder);
					else
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Kann den Datei-Explorer auf diesem System nicht automatisch \u00F6ffnen. Ordner: " + folder.getAbsolutePath());
				} catch (IOException e) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Konnte den Protokoll-Ordner nicht \u00F6ffnen: " + e.getMessage());
				}
			});

			HBox protocolRow = new HBox(10, new Label(tr("Protokoll:")), Klein_Method_Tab_Builder.this.protocolLevelComboBox, openProtocolFolderButton);

			// --- Nachkommastellen: 3/4/5/6, Standard 4 - gilt fuer ALLE
			// angezeigten Zahlenwerte in Klein_Method (siehe Klein_Method_Format) ---
			ComboBox<Integer> decimalPlacesComboBox = new ComboBox<Integer>();
			decimalPlacesComboBox.getItems().addAll(3, 4, 5, 6);
			decimalPlacesComboBox.setValue(parameter.getDecimalPlaces());
			decimalPlacesComboBox.setTooltip(new Tooltip(tr("Anzahl Nachkommastellen f\u00FCr alle angezeigten Zahlenwerte "
					+ "(Grenzwerte, Ausgelesene Werte, Live-Modus, Protokoll). Werte werden immer mit Nullen aufgef\u00FCllt "
					+ "und mit Einheit angezeigt, z.B. \"0.0050 m\".")));
			decimalPlacesComboBox.setOnAction(event -> {
				parameter.setDecimalPlaces(decimalPlacesComboBox.getValue());
				Klein_Method_Tab_Builder.this.updateDiagnosticValueLabels();
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			HBox decimalPlacesRow = new HBox(10, new Label(tr("Nachkommastellen:")), decimalPlacesComboBox);

			// -- Oberflaechensprache. "Automatisch" = Sprache des PCs (Windows/Linux/macOS), nicht unterstuetzt ->
			// Englisch. Wirkt erst nach Neustart von JAG3D. Protokoll bleibt immer deutsch. ---
			ComboBox<String> languageComboBox = new ComboBox<String>();
			languageComboBox.getItems().add(Klein_Method_Functions.Klein_Method_I18N.AUTO);
			for (String lang : Klein_Method_Functions.Klein_Method_I18N.LANGUAGES)
				languageComboBox.getItems().add(lang);
			languageComboBox.setValue(parameter.getLanguage());
			languageComboBox.setConverter(new StringConverter<String>() {
				@Override
				public String toString(String value) {
					if (value == null)
						return "";
					if (Klein_Method_Functions.Klein_Method_I18N.AUTO.equals(value))
						return tr("Automatisch") + " (" + endonym(Klein_Method_Functions.Klein_Method_I18N.resolve(value)) + ")";
					return endonym(value);
				}
				@Override
				public String fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			languageComboBox.setTooltip(new Tooltip(tr("Sprache der Klein_Method-Oberfl\u00E4che (Tab, Kn\u00F6pfe, Tooltips, Statuszeilen, Dialoge).\n"
					+ "Automatisch: Sprache des PCs (Windows/Linux/macOS); nicht unterst\u00FCtzte Sprachen -> Englisch.\n"
					+ "Fehlt ein einzelner Text in der Sprachdatei, erscheint er deutsch.\n"
					+ "Wirksam nach Neustart von JAG3D. Das Protokoll bleibt immer deutsch.")));
			languageComboBox.setOnAction(event -> {
				String chosen = languageComboBox.getValue();
				if (chosen == null || chosen.equals(parameter.getLanguage()))
					return;
				parameter.setLanguage(chosen);
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Oberfl\u00E4chensprache auf " + chosen + " gesetzt (wirksam nach Neustart von JAG3D).");
				Alert alert = new Alert(AlertType.INFORMATION);
				alert.setTitle(tr("Klein_Method - Sprache"));
				alert.setHeaderText(tr("Sprache gespeichert"));
				alert.setContentText(tr("Die neue Sprache ist wirksam nach Neustart von JAG3D.") + "\n"
						+ endonym(Klein_Method_Functions.Klein_Method_I18N.resolve(chosen)));
				alert.show();
			});
			Label languageLabel = new Label(tr("Sprache:"));
			languageLabel.setTooltip(languageComboBox.getTooltip());
			HBox languageRow = new HBox(10, languageLabel, languageComboBox);

			// --- Geraeteklassen: elektronisch/historisch/kombiniert, Standard
			// elektronisch - setzt die Untergrenze der GRUPPENunsicherheit
			// (sigma_a/sigma_b/sigma_c, "globale Werte") passend zur Praezision
			// des verwendeten Messinstruments (siehe Klein_Method_Default_Values.
			// getGroupUncertaintyFloor()) ---
			ComboBox<Klein_Method_Device_Class> deviceClassComboBox = new ComboBox<Klein_Method_Device_Class>();
			deviceClassComboBox.getItems().addAll(Klein_Method_Device_Class.values());
			deviceClassComboBox.setValue(parameter.getDeviceClass());
			deviceClassComboBox.setConverter(new StringConverter<Klein_Method_Device_Class>() {
				@Override
				public String toString(Klein_Method_Device_Class deviceClass) {
					return deviceClass == null ? "" : tr(deviceClass.getDisplayName());
				}
				@Override
				public Klein_Method_Device_Class fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			deviceClassComboBox.setTooltip(new Tooltip(tr("Legt die Untergrenze der Gruppenunsicherheit (sigma_a/sigma_b/sigma_c) passend zum "
					+ "verwendeten Messinstrument fest.\nElektronisch: bisherige (pr\u00E4zise) Werte.\nHistorisch: gr\u00F6\u00DFere, "
					+ "f\u00FCr historische Instrumente passende Werte - nur f\u00FCr Horizontalrichtung, Horizontalstrecke, "
					+ "Vertikalrichtung und Schr\u00E4gstrecke definiert.\nKombiniert: verh\u00E4lt sich identisch zu Elektronisch.\n"
					+ "Einzelne Ger\u00E4teklassen-Unterarten (z.B. Bussole, Theodolit) werden hier nicht unterschieden - das muss "
					+ "der Nutzer selbst einordnen.")));
			deviceClassComboBox.setOnAction(event -> {
				parameter.setDeviceClass(deviceClassComboBox.getValue());
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				// die 3 sigma_a/b/c-Felder in "Grenzwerte Gruppenunsicherheit" zeigen
				// immer die AKTUELLE Geraeteklasse - bei Wechsel hier mit aktualisieren
				if (Klein_Method_Tab_Builder.this.groupUncertaintyFieldsRefresher != null)
					Klein_Method_Tab_Builder.this.groupUncertaintyFieldsRefresher.run();
			});

			HBox deviceClassRow = new HBox(10, new Label(tr("Ger\u00E4teklasse:")), deviceClassComboBox);

			// -- Koordinatensystem: rein informativ, nicht editierbar -
			// TODO: Datenquelle (JAG3D-Tabelle/Spalte) noch nicht bekannt, Wert bleibt vorerst Platzhalter. Sobald
			// geklaert, in refreshCoordinateSystemLabel() (analog zu refreshAdditionalParameters()) tatsaechlich
			// befuellen.
			Klein_Method_Tab_Builder.this.coordinateSystemLabel = new Label(tr("unbekannt"));
			Klein_Method_Tab_Builder.this.coordinateSystemLabel.setTooltip(new Tooltip(tr("Aktuell in JAG3D eingestelltes Koordinatensystem - rein informativ.")));
			HBox coordinateSystemRow = new HBox(10, new Label(tr("Koordinatensystem:")), Klein_Method_Tab_Builder.this.coordinateSystemLabel);

			// --- Waechter: an/aus, Standard an - ueberwacht Dauer + Beobachtungs-
			// anzahl jeder Netzausgleichung, fliessender Farbverlauf auf dem
			// Button selbst (siehe Klein_Method_Guardian) ---
			Klein_Method_Tab_Builder.this.guardianToggleButton = new ToggleButton();
			Klein_Method_Tab_Builder.this.applyStatusBadgeGeometry(Klein_Method_Tab_Builder.this.guardianToggleButton);
			Klein_Method_Tab_Builder.this.guardianToggleButton.setSelected(Klein_Method_Tab_Builder.this.kleinMethod.getGuardian().isEnabled());
			Klein_Method_Tab_Builder.this.guardianToggleButton.setOnAction(event -> {
				Klein_Method_Tab_Builder.this.kleinMethod.getGuardian().setEnabled(Klein_Method_Tab_Builder.this.guardianToggleButton.isSelected());
				Klein_Method_Tab_Builder.this.updateGuardianAppearance();
			});
			Klein_Method_Tab_Builder.this.updateGuardianAppearance(); // Anfangszustand

			HBox guardianRow = new HBox(10, new Label(tr("W\u00E4chter:")), Klein_Method_Tab_Builder.this.guardianToggleButton);

			// die Oszillations-Status-Ampel steht jetzt direkt UNTER der Waechter-Zeile, in derselben Optionen-Sektion.
			// Feld, Geometrie und Tooltip sind unveraendert 1:1 uebernommen, nur der Bauort hat sich geaendert. Wie bei
			// ALLEN anderen Zeilen dieser Sektion bewusst KEIN eigenes setAlignment()/setPadding() - reines "new HBox(10,
			// ...)", linksbuendig durch den HBox-Standard, vertikaler Abstand ausschliesslich aus dem gemeinsamen
			// VBox(10, ...)-Spacing. Der Waechter-Button selbst (guardianToggleButton, fliessender
			// Gruen-Gelb-Rot-Verlauf) bleibt unangetastet - nur diese separate Zeile zieht um.
			Label oscillationStatusNameLabel = new Label(tr("Oszillations-Status:"));
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel = new Button();
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setFocusTraversable(false); // rein informativ, kein Klick-Verhalten
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setMouseTransparent(true); // keine Hover-/Press-Optik eines echten Buttons
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setAlignment(Pos.CENTER);
			Klein_Method_Tab_Builder.this.applyStatusBadgeGeometry(Klein_Method_Tab_Builder.this.oscillationStatusValueLabel);
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setTooltip(new Tooltip(tr("Ampel-Anzeige des adaptiven Backoff-W\u00E4chters (nur im "
					+ "Verteilungsmodus \u201Eprozentual\u201C wirksam): GR\u00DCN = kein Stagnations-/Oszillationssignal, GELB = "
					+ "Vorstufe bzw. laufendes Beruhigungsfenster, ROT = Ausl\u00F6se-Iteration des autonomen Wechsels auf NORMAL.")));
			Klein_Method_Tab_Builder.this.applyOscillationStatus(parameter.getOscillationStatus());

			HBox oscillationStatusRow = new HBox(10, oscillationStatusNameLabel, Klein_Method_Tab_Builder.this.oscillationStatusValueLabel);

			// --- Live-Modus: an/aus, Standard an - zeigt die aktuellen Max-logPost/
			// Max-EP-Werte live in 2 Feldern, dieselben Werte wie im Protokoll ---
			Klein_Method_Tab_Builder.this.liveModeToggleButton = new ToggleButton();
			Klein_Method_Tab_Builder.this.liveModeToggleButton.setSelected(true); // Standard an, nicht dauerhaft gespeichert
			Klein_Method_Tab_Builder.this.liveModeToggleButton.setTooltip(new Tooltip(tr("Zeigt die aktuellen Max-logPost-/Max-EP-Werte live w\u00E4hrend Automatik/"
					+ "Einzelschritt an - dieselben Werte, die auch ins Protokoll geschrieben werden.")));

			Klein_Method_Tab_Builder.this.liveLogPostLabel = new Label("\u2013");
			Klein_Method_Tab_Builder.this.liveEpLabel = new Label("\u2013");

			Klein_Method_Tab_Builder.this.liveModeToggleButton.setOnAction(event -> {
				Klein_Method_Tab_Builder.this.liveModeToggleButton.setText(Klein_Method_Tab_Builder.this.liveModeToggleButton.isSelected() ? "AN" : tr("AUS"));
				if (!Klein_Method_Tab_Builder.this.liveModeToggleButton.isSelected()) {
					Klein_Method_Tab_Builder.this.liveLogPostLabel.setText("\u2013");
					Klein_Method_Tab_Builder.this.liveEpLabel.setText("\u2013");
					if (Klein_Method_Tab_Builder.this.dynamicLiveLabel != null)
						Klein_Method_Tab_Builder.this.dynamicLiveLabel.setText("\u2013");
				}
			});
			Klein_Method_Tab_Builder.this.liveModeToggleButton.setText("AN");

			// Live-Werte in eigener Zeile - die lange Zeile bestimmte sonst die Spaltenbreite.
			HBox liveModeRow = new HBox(10, new Label(tr("Live-Modus:")), Klein_Method_Tab_Builder.this.liveModeToggleButton);
			HBox liveValuesRow = new HBox(10, new Label(tr("Live-Werte:")), new Label(tr("logPost:")), Klein_Method_Tab_Builder.this.liveLogPostLabel,
					new Label("EP:"), Klein_Method_Tab_Builder.this.liveEpLabel);
			Klein_Method_Tab_Builder.this.liveValuesRow = liveValuesRow;
			// eigene Live-Werte der dynamischen AGL - nur dort sichtbar
			Klein_Method_Tab_Builder.this.dynamicLiveLabel = new Label("\u2013");
			Klein_Method_Tab_Builder.this.dynamicLiveLabel.setWrapText(true);
			Klein_Method_Tab_Builder.this.dynamicLiveLabel.setTooltip(new Tooltip(tr("Dynamische AGL, live nach jeder Iteration:\n"
					+ "Phase 1: fertige Punktgruppen und Varianzkomponente F je Gruppe.\n"
					+ "Phase 2: fertige Festpunkte, kleinster/gr\u00F6\u00DFter T_prio mit Punktname "
					+ "(ohne Punkte mit T_prio 0 an der Untergrenze) und Turbo-Zustand. Band 0,996 - 1,004.")));
			HBox dynamicLiveValuesRow = new HBox(10, new Label(tr("Live-Werte:")), Klein_Method_Tab_Builder.this.dynamicLiveLabel);
			Klein_Method_Tab_Builder.this.dynamicLiveValuesRow = dynamicLiveValuesRow;
			dynamicLiveValuesRow.setVisible(false);
			dynamicLiveValuesRow.setManaged(false);

			// neue ComboBox, exakt vertikal UNTER "Live-Modus:" und OBERHALB von "Elemente / Iter.:". Zwei Eintraege wie
			// beauftragt, Default "Gedaempfter Achs-Split". FUNDSTELLEN-HINWEIS (siehe ausfuehrlicher Klassenkommentar
			// von Klein_Method_Damping_Formula_Type UND Methoden-Javadoc von performIndividual CalibrationStep()): die
			// Auswahl wirkt technisch nur innerhalb des GNSS-PERCENTAGE-Zweigs - fuer alle anderen Faelle ist "Ur-Formel"
			// ohnehin bereits die einzige anwendbare Formel.
			Label dampingFormulaNameLabel = new Label(tr("Dämpfungs-Formel:"));
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox = new ComboBox<Klein_Method_Damping_Formula_Type>();
			// Reihenfolge in der Auswahlliste = Empfehlungs-Reihenfolge
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.getItems().addAll(Klein_Method_Damping_Formula_Type.GEDAEMPFTER_ACHS_SPLIT_V333,
					Klein_Method_Damping_Formula_Type.LAGE_HOEHE_SPLIT_V366, Klein_Method_Damping_Formula_Type.UR_FORMEL_V100);
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setValue(parameter.getDampingFormulaType());
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setConverter(translatingConverter());
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setPrefWidth(UI_COMBO_WIDTH);
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setMaxWidth(UI_COMBO_WIDTH);
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setTooltip(new Tooltip(tr("Wirkt nur bei GNSS-Basislinien im Verteilungsmodus „prozentual“:\n"
					+ "1. „gedämpfter Achs Split“ (Empfehlung): Korrektur nach den Achsen X/Y/Z aufgeteilt (Anteil je Achse aus dem EP), gedämpft - "
					+ "im Vergleich stabil und zeigt die schwächere GNSS-Höhe.\n"
					+ "2. „Lage/Höhe-Split“: Korrektur nach Lage (horizontal) und Höhe (Lotrichtung) aufgeteilt, gedämpft; "
					+ "die Lotrichtung wird je Basislinie aus den ausgeglichenen Koordinaten bestimmt - am stabilsten, unterscheidet Höhe/Lage aber kaum.\n"
					+ "3. „einfache Anpassung“: sigma_neu = sigma_alt + sigma_alt*log10(F), alle Achsen gleich - am unruhigsten.\n"
					+ "Im Modus „normal“ gilt immer die einfache Anpassung.")));
			Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setOnAction(event -> {
				parameter.setDampingFormulaType(Klein_Method_Tab_Builder.this.dampingFormulaComboBox.getValue());
				// nur eine Auswahl im Modus "prozentual" merken - im Modus "normal" setzt das Programm den Wert selbst
				// (Feld gesperrt).
				if (!Klein_Method_Tab_Builder.this.dampingFormulaComboBox.isDisabled())
					Klein_Method_Tab_Builder.this.userDampingFormulaChoice = Klein_Method_Tab_Builder.this.dampingFormulaComboBox.getValue();
			});
			// im Modus "normal" steht im Parameter zwangsweise die einfache Anpassung - das ist keine Anwender-Auswahl;
			// dann gilt fuer "prozentual" der Standard Achs Split.
			Klein_Method_Tab_Builder.this.userDampingFormulaChoice = parameter.getUserDistributionType() == Klein_Method_Distribution_Type.PERCENTAGE
					? parameter.getDampingFormulaType() : Klein_Method_Damping_Formula_Type.GEDAEMPFTER_ACHS_SPLIT_V333;
			HBox dampingFormulaRow = new HBox(10, dampingFormulaNameLabel, Klein_Method_Tab_Builder.this.dampingFormulaComboBox);
			// Daempfung im Modus normal bei Pendeln (Versuch, Standard aus)
			CheckBox normalDampingCheckBox = new CheckBox(tr("Dämpfung bei Pendeln (normal, Versuch)"));
			normalDampingCheckBox.setSelected(parameter.isNormalDampingEnabled());
			normalDampingCheckBox.setTooltip(new Tooltip(tr("Pendelt Max-logPost in der freien bzw. fixen Ausgleichung (Phase 2) sägezahnartig "
					+ "(in den letzten 4 Werten mindestens 2 Richtungswechsel),\nwird im Modus „normal“ nur die Hälfte des berechneten Schritts "
					+ "geschrieben: sigma_neu = sigma_alt + 0,5 · (sigma_formel − sigma_alt).\n"
					+ "Gilt für GNSS im Modus „normal“ (auch während des Wächter-Wechsels) und für Richtungen, Strecken und Nivellement.\n"
					+ "Versuch – Standard aus. Wirkung im Protokoll: Zeile „DAEMPFUNG NORMAL“.")));
			normalDampingCheckBox.setOnAction(event -> {
				parameter.setNormalDampingEnabled(normalDampingCheckBox.isSelected());
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Daempfung bei Pendeln (normal) = "
						+ (normalDampingCheckBox.isSelected() ? "ein" : "aus"));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			dampingFormulaRow.getChildren().add(normalDampingCheckBox);
			Klein_Method_Tab_Builder.this.dampingFormulaRowRef = dampingFormulaRow;

			// -- Split-Normierung automatisch (empfohlen) / ja / nein, direkt UNTER "Daempfungs-Formel". ---
			Label splitNormalizationLabel = new Label(tr("Split-Normierung:"));
			ToggleGroup splitNormalizationGroup = new ToggleGroup();
			HBox splitNormalizationRow = new HBox(10, splitNormalizationLabel);
			for (Klein_Method_Parameter.Klein_Method_Split_Normalization_Mode mode : Klein_Method_Parameter.Klein_Method_Split_Normalization_Mode.values()) {
				RadioButton radio = new RadioButton(tr(mode.getDisplayName()));
				radio.setToggleGroup(splitNormalizationGroup);
				radio.setUserData(mode);
				radio.setSelected(parameter.getSplitNormalizationMode() == mode);
				switch (mode) {
				case AUTO:
					radio.setTooltip(new Tooltip(tr("Beide Split-Formeln (gedämpfter Achs Split und Lage/H\u00F6he-Split) MIT Normierung "
							+ "(V0.371, Vergleich Projekt 22 000 BL); einfache Anpassung ohne.")));
					break;
				case ON:
					radio.setTooltip(new Tooltip(tr("Beide Split-Formeln MIT Normierung: Faktor = 1 + log10(F) \u00B7 k \u00B7 Anteil "
							+ "(k = 3 Achs Split, k = 2 Lage/H\u00F6he-Split) - die Korrektur der einfachen Anpassung wird nur verteilt.")));
					break;
				default:
					radio.setTooltip(new Tooltip(tr("Beide Split-Formeln OHNE Normierung (bis V0.367): Faktor = 0,6 + 0,4 \u00B7 Anteil \u00B7 (1 + log10(F)) - "
							+ "verkleinert sigma, solange F nicht sehr gro\u00DF ist.")));
					break;
				}
				splitNormalizationRow.getChildren().add(radio);
			}
			splitNormalizationGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				Klein_Method_Parameter.Klein_Method_Split_Normalization_Mode selected =
						(Klein_Method_Parameter.Klein_Method_Split_Normalization_Mode) ((Toggle) newToggle).getUserData();
				parameter.setSplitNormalizationMode(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Split-Normierung = " + selected.getDisplayName());
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			// -- Turbo Phase 1 ja (empfohlen) / nein ---
			Label phase1TurboLabel = new Label(tr("Turbo Phase 1:"));
			ToggleGroup phase1TurboGroup = new ToggleGroup();
			RadioButton phase1TurboYesRadio = new RadioButton(tr("ja (empfohlen)"));
			phase1TurboYesRadio.setToggleGroup(phase1TurboGroup);
			phase1TurboYesRadio.setUserData(Boolean.TRUE);
			phase1TurboYesRadio.setSelected(parameter.isPhase1TurboEnabled());
			// kurzer Tooltip, wann der Schalter greift
			phase1TurboYesRadio.setTooltip(new Tooltip(tr("Beschleunigt in der freien und festen AGL das Verkleinern der Gruppen-Unsicherheiten.\n"
					+ "In der dynamischen AGL ohne Wirkung.")));
			RadioButton phase1TurboNoRadio = new RadioButton(tr("nein"));
			phase1TurboNoRadio.setToggleGroup(phase1TurboGroup);
			phase1TurboNoRadio.setUserData(Boolean.FALSE);
			phase1TurboNoRadio.setSelected(!parameter.isPhase1TurboEnabled());
			phase1TurboNoRadio.setTooltip(new Tooltip(tr("Phase 1 ohne Beschleunigung.")));
			phase1TurboGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				boolean selected = (Boolean) ((Toggle) newToggle).getUserData();
				parameter.setPhase1TurboEnabled(selected);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Turbo Phase 1 " + (selected ? "eingeschaltet." : "ausgeschaltet."));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			HBox phase1TurboRow = new HBox(10, phase1TurboLabel, phase1TurboYesRadio, phase1TurboNoRadio);

			// -- Mathe-Debug aus / Stichprobe / alle (Standard aus) ---
			Label mathDebugLabel = new Label(tr("Mathe-Debug:"));
			ComboBox<Klein_Method_Parameter.Klein_Method_Math_Debug_Mode> mathDebugComboBox = new ComboBox<Klein_Method_Parameter.Klein_Method_Math_Debug_Mode>();
			mathDebugComboBox.getItems().addAll(Klein_Method_Parameter.Klein_Method_Math_Debug_Mode.values());
			mathDebugComboBox.setValue(parameter.getMathDebugMode());
			mathDebugComboBox.setConverter(translatingConverter());
			mathDebugComboBox.setPrefWidth(UI_COMBO_WIDTH);
			mathDebugComboBox.setMaxWidth(UI_COMBO_WIDTH);
			mathDebugComboBox.setTooltip(new Tooltip(tr("Umfang der Zeilen [Klein_Method MATHE-DEBUG] je GNSS-Basislinie im Protokoll "
					+ "(nur Achs Split bzw. Lage/H\u00F6he-Split, Modus prozentual, Protokollstufe LONG):\n"
					+ "aus = keine (kleinstes Protokoll), Stichprobe = die ersten 5 je Iteration, alle = jede Basislinie (sehr gro\u00DFe Protokolle).")));
			mathDebugComboBox.setOnAction(event -> {
				parameter.setMathDebugMode(mathDebugComboBox.getValue());
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Mathe-Debug = " + parameter.getMathDebugMode().getDisplayName());
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			HBox mathDebugRow = new HBox(10, mathDebugLabel, mathDebugComboBox);
			Klein_Method_Tab_Builder.this.updateDampingFormulaAvailability();

			// neues Live-Feld, exakt vertikal UNTER "Live-Modus:" und OBERHALB von "logPost-EtappeMin:". WIE
			// BEAUFTRAGT: setEditable(false), setFocusTraversable(false) (fokus-gesperrt), standardmaessig mit "0"
			// vorbefuellt, kraeftiges Blau/Fett - siehe updateElementsPerIterationLabel() fuer die Herleitung des
			// angezeigten Werts.
			Label elementsPerIterationNameLabel = new Label(tr("Elemente / Iter.:"));
			Klein_Method_Tab_Builder.this.elementsPerIterationField = new TextField("0");
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setEditable(false);
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setPrefColumnCount(8);
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setStyle(UI_READ_ONLY_FIELD);
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setCursor(javafx.scene.Cursor.DEFAULT);
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setTooltip(new Tooltip(tr("Anzahl der Beobachtungen/Achsen, die in der zuletzt "
					+ "abgeschlossenen Iterationsrunde reell im RAM bearbeitet/gedaempft wurden (Klein_Method_Parameter."
					+ "getElementsProcessedLastIteration(), aus der bestehenden Zaehlschleife in performIndividualCalibration"
					+ "Step() summiert) - waehrend Phase 1 (Gruppenkalibrierung) fachlich korrekt 0, da dort keine einzelnen "
					+ "Beobachtungen/Achsen verarbeitet werden.")));
			HBox elementsPerIterationRow = new HBox(10, elementsPerIterationNameLabel, Klein_Method_Tab_Builder.this.elementsPerIterationField);
			Klein_Method_Tab_Builder.this.elementsPerIterationRowRef = elementsPerIterationRow;

			Klein_Method_Tab_Builder.this.updateElementsPerIterationLabel(); // Anfangszustand

			// "Deaktivierte BL:" aus Sektion "Ausgelesene Werte" HIERHER verschoben, exakt vertikal UNTER "Elemente /
			// Iter.:" und OBERHALB von "logPost-EtappeMin:". WIE BEAUFTRAGT: setEditable(false), fokus-gesperrt, neutral
			// grau hinterlegt wie die anderen Info-Boxen als Basis, ueberlagert gemaess Punkt 2 mit kraeftigem Blau
			// (#3498db)/Fett fuer den Live-Wert - dieselbe Formatierung wie das direkt darueberliegende "Elemente /
			// Iter.:". Zaehlerstaende unveraendert aus
			// Klein_Method_Parameter.getDisabledGnssBaselineCount()/getTotalGnssBaselineCount() (siehe
			// formatDeactivatedGnssBaselineCount() unten).
			Label deactivatedGnssBaselineCountNameLabel = new Label(tr("Deaktivierte BL:"));
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField = new TextField(Klein_Method_Tab_Builder.this.formatDeactivatedGnssBaselineCount(parameter));
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setEditable(false);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setPrefColumnCount(19);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.getProperties().put(UI_WIDE_FIELD, Boolean.TRUE);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setStyle(UI_READ_ONLY_FIELD);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setCursor(javafx.scene.Cursor.DEFAULT);
			Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setTooltip(new Tooltip(tr("Anzahl der aktuell deaktivierten (enable=false) GNSS-Basislinien "
					+ "im geladenen Projekt, bezogen auf die Gesamtzahl aller GNSS-Basislinien. Erfasst ALLE Deaktivierungen, "
					+ "unabhängig vom Grund (z. B. auch die Trichter-Kappungsgrenze >1000.0).")));
			HBox deactivatedGnssBaselineCountRow = new HBox(10, deactivatedGnssBaselineCountNameLabel, Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField);

			// rein informatives Feld, exakt vertikal DIREKT UNTER "Live-Modus:". WIE BEAUFTRAGT: setEditable(false),
			// setFocusTraversable(false) (fokussperrt), dezent grau hinterlegt - siehe updateLogPostDynamicBandFields()
			// fuer die Herleitung des angezeigten Werts.
			// "logPost-EtappeMin:" ruekt dadurch fluchtsymmetrisch nach oben und liegt jetzt direkt (statt eine Zeile
			// tiefer) unter "Live-Modus:".
			Label logPostEtappeMinNameLabel = new Label(tr("logPost-EtappeMin:"));
			Klein_Method_Tab_Builder.this.logPostEtappeMinField = new TextField();
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setEditable(false);
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setPrefColumnCount(8);
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setStyle(UI_READ_ONLY_FIELD);
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setCursor(javafx.scene.Cursor.DEFAULT);
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setTooltip(new Tooltip(tr("Live berechnete Untergrenze des aktuellen logPost-Bearbeitungs-"
					+ "bereichs (Klein_Method_Functions.computeCurrentLogPostEtappeMin(), dieselbe Formel wie in performIndividual"
					+ "CalibrationStep()) - das dynamisch mitschwimmende Zwischenziel des Trichters, bei JEDER Iteration neu "
					+ "berechnet.")));
			HBox logPostEtappeMinRow = new HBox(10, logPostEtappeMinNameLabel, Klein_Method_Tab_Builder.this.logPostEtappeMinField);
			Klein_Method_Tab_Builder.this.logPostEtappeMinRowRef = logPostEtappeMinRow;

			// -- "Kaskaden-Durchlauf:" und "Bester Max-logPost:" - nicht editierbar, gleiche Formatierung wie "Elemente /
			// Iter.:", direkt unter "logPost-EtappeMin:". ---
			Label cascadePassNameLabel = new Label(tr("Kaskaden-Durchlauf:"));
			Klein_Method_Tab_Builder.this.cascadePassField = new TextField("\u2013");
			Klein_Method_Tab_Builder.this.cascadePassField.setEditable(false);
			Klein_Method_Tab_Builder.this.cascadePassField.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.cascadePassField.setPrefColumnCount(8);
			Klein_Method_Tab_Builder.this.cascadePassField.setStyle(UI_READ_ONLY_FIELD);
			Klein_Method_Tab_Builder.this.cascadePassField.setCursor(javafx.scene.Cursor.DEFAULT);
			Klein_Method_Tab_Builder.this.cascadePassField.setTooltip(new Tooltip(tr("Nummer des aktuellen Kaskaden-Durchlaufs dieses Automatik-Laufs "
					+ "(1 = erster Durchlauf; ohne Kaskaden-Mehrfachlauf immer 1). Nur Anzeige.")));
			HBox cascadePassRow = new HBox(10, cascadePassNameLabel, Klein_Method_Tab_Builder.this.cascadePassField);

			Label bestMaxLogPostNameLabel = new Label(tr("Bester Max-logPost:"));
			Klein_Method_Tab_Builder.this.bestMaxLogPostField = new TextField("\u2013");
			Klein_Method_Tab_Builder.this.bestMaxLogPostField.setEditable(false);
			Klein_Method_Tab_Builder.this.bestMaxLogPostField.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.bestMaxLogPostField.setPrefColumnCount(22);
			Klein_Method_Tab_Builder.this.bestMaxLogPostField.setStyle(UI_READ_ONLY_FIELD);
			Klein_Method_Tab_Builder.this.bestMaxLogPostField.setCursor(javafx.scene.Cursor.DEFAULT);
			Klein_Method_Tab_Builder.this.bestMaxLogPostField.setTooltip(new Tooltip(tr("Kleinster Max-logPost ab Kaskaden-Durchlauf 2 (Durchlauf 1 z\u00E4hlt nicht mit), "
					+ "mit Durchlauf (DL) und Iteration. Grundlage f\u00FCr das Abbruchziel in Durchlauf 3. "
					+ "Wird bei jedem neuen Start zur\u00FCckgesetzt.")));
			Klein_Method_Tab_Builder.this.bestMaxLogPostInfoLabel = new Label("");
			HBox bestMaxLogPostRow = new HBox(10, bestMaxLogPostNameLabel, Klein_Method_Tab_Builder.this.bestMaxLogPostField, Klein_Method_Tab_Builder.this.bestMaxLogPostInfoLabel);

			// Kaskaden-Abbruchziel (nur Anzeige)
			Label cascadeTargetNameLabel = new Label(tr("Kaskaden-Abbruchziel:"));
			Klein_Method_Tab_Builder.this.cascadeTargetField = new TextField("\u2013");
			Klein_Method_Tab_Builder.this.cascadeTargetField.setEditable(false);
			Klein_Method_Tab_Builder.this.cascadeTargetField.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.cascadeTargetField.setPrefColumnCount(22);
			Klein_Method_Tab_Builder.this.cascadeTargetField.setStyle(UI_READ_ONLY_FIELD);
			Klein_Method_Tab_Builder.this.cascadeTargetField.setCursor(javafx.scene.Cursor.DEFAULT);
			Klein_Method_Tab_Builder.this.cascadeTargetField.setTooltip(new Tooltip(tr("Nur mit Kaskaden-Mehrfachlauf. Ab Durchlauf 3 endet die Automatik, sobald "
					+ "Max-logPost dieses Ziel erreicht - ohne Untergrenzen-Erzwingung, der aktuelle Stand bleibt erhalten.\n"
					+ "Durchlauf 3: bester Max-logPost aus Durchlauf 2, nur wenn er unter ")
					+ Klein_Method_Default_Values.CASCADE_BACKUP_TARGET_LOG_POST + tr(" liegt.\n"
					+ "Ab Durchlauf 4: ") + Klein_Method_Default_Values.CASCADE_BACKUP_TARGET_LOG_POST + tr(" (Backup).")));
			Klein_Method_Tab_Builder.this.cascadeTargetInfoLabel = new Label("");
			HBox cascadeTargetRow = new HBox(10, cascadeTargetNameLabel, Klein_Method_Tab_Builder.this.cascadeTargetField, Klein_Method_Tab_Builder.this.cascadeTargetInfoLabel);
			Klein_Method_Tab_Builder.this.updateCascadeStatusFields(); // Anfangszustand

			Klein_Method_Tab_Builder.this.updateLogPostDynamicBandFields(); // Anfangszustand

			// Verteilung-Zeile jetzt direkt UNTER der Live-Modus-Zeile. Trichter-Faktor-Zeile direkt UNTER der
			// Verteilung-Zeile. links die Anzeigen/allgemeinen Einstellungen, rechts die Berechnungs-Einstellungen. nur
			// freie/fixe AGL (in der dynamischen AGL ausgeblendet)
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(oscillationStatusRow, deactivatedGnssBaselineCountRow, cascadePassRow, bestMaxLogPostRow, cascadeTargetRow,
					distributionRow, splitNormalizationRow, phase1TurboRow, funnelFactorRow, nivellementFunnelFactorRow, collectiveDeltaRow,
					floorEnforcementRow, cascadeDoubleRunRow, deactivateGnssOutlierRow, groupDampingPhase2Row, groupFloorAfterPass1Row);
			VBox displayColumn = new VBox(10, protocolRow, decimalPlacesRow, languageRow, deviceClassRow, coordinateSystemRow, guardianRow,
					oscillationStatusRow, liveModeRow, liveValuesRow, dynamicLiveValuesRow, elementsPerIterationRow, deactivatedGnssBaselineCountRow, logPostEtappeMinRow,
					cascadePassRow, bestMaxLogPostRow, cascadeTargetRow);
			VBox calculationColumn = new VBox(10, distributionRow, dampingFormulaRow, splitNormalizationRow, phase1TurboRow, turboRow,
					mathDebugRow, funnelFactorRow, nivellementFunnelFactorRow, fangkreisRadiusRow, collectiveDeltaRow, floorEnforcementRow,
					finalScalingRow, autoGrossRow, cascadeDoubleRunRow, deactivateGnssOutlierRow, groupDampingPhase2Row, groupFloorAfterPass1Row);
			Klein_Method_Tab_Builder.this.alignRowLabels(displayColumn);
			Klein_Method_Tab_Builder.this.alignRowLabels(calculationColumn);
			HBox columns = new HBox(20, displayColumn, new Separator(Orientation.VERTICAL), calculationColumn);
			VBox section = new VBox(10, columns);
			section.setPadding(new Insets(10, 0, 0, 0));
			return section;
		}
		/**
		 * Baut den Inhalt der Gruppe "Iterationen": Grenzwert-Eingabefeld
		 * (Standard 100, Obergrenze 200, automatische Korrektur), Live-Anzeige
		 * des aktuellen Zaehlerstands sowie ein Reset-Button (setzt den
		 * Zaehler manuell auf 0 zurueck).
		 */
		/**
		 * 8-zeiliges Gitter (dieselben 8 LOG_POST-Typen wie performGroupCalibration Step()) mit je einem editierbaren
		 * Feld fuer den Gruppen-Faktor V. Anwendung/Formel siehe Klein_Method_Functions.performGroupCalibrationStep()
		 * (sigma_skaliert = sigma_alt * V) und Klein_Method_Parameter.getMaxGroupScaleLimit() (Autopilot-Deckel).
		 */
		private VBox createGroupDampingGridSection() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			Label infoLabel = new Label(tr("Werte > 1.00 reduzieren das stochastische Gewicht der Gruppe proportional zu 1/V\u00B2 - "
					+ "verhindert, dass Massenbeobachtungen (viele Beobachtungen einer Gruppe) kleine Pr\u00E4zisionsbeobachtungen "
					+ "(z.B. Lasertracker) im gemeinsamen Netz erdr\u00FCcken."));
			infoLabel.setWrapText(true);
			infoLabel.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
			infoLabel.setStyle("-fx-text-fill: black; -fx-background-color: " + UI_YELLOW + "; -fx-padding: 6;");

			GridPane grid = new GridPane();
			grid.setHgap(10);
			grid.setVgap(8);

			Label typeHeader = new Label(tr("Beobachtungstyp"));
			typeHeader.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
			typeHeader.setStyle("-fx-font-weight: bold;");
			Label factorHeader = new Label(tr("Gruppen-Faktor V"));
			factorHeader.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
			factorHeader.setStyle("-fx-font-weight: bold;");
			grid.add(typeHeader, 0, 0);
			grid.add(factorHeader, 1, 0);

			int rowIndex = 1;
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST)) {
				Label typeLabel = new Label(tr(type.getDisplayName()));

				TextField factorField = new TextField(String.valueOf(parameter.getGroupScaleFactor(type)));
				factorField.setPrefWidth(100);
				factorField.setTooltip(new Tooltip(tr("Werte > 1.00 reduzieren das stochastische Gewicht dieser Gruppe proportional zu "
						+ "1/V\u00B2 - so werden kleine Pr\u00E4zisionsbeobachtungen (z.B. Lasertracker) nicht von Massenbeobachtungen "
						+ "erdr\u00FCckt. Standard 1.00, aktuelle Obergrenze ")
						+ Klein_Method_Format.format(parameter.getMaxGroupScaleLimit(), "", 2)
						+ tr(" (Autopilot: steigt automatisch in 50er-Schritten, sobald das Projekt \u2265 ")
						+ Klein_Method_Parameter.AUTOPILOT_AXIS_THRESHOLD + tr(" Messachsen enth\u00E4lt).")));
				Klein_Method_Tab_Builder.this.groupScaleFactorFields.put(type, factorField);

				Runnable applyFactor = () -> {
					try {
						double enteredValue = Double.parseDouble(factorField.getText().replace(",", "."));
						double correctedValue = parameter.setGroupScaleFactor(type, enteredValue);
						if (correctedValue != enteredValue) {
							factorField.setText(String.valueOf(correctedValue));
							Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Gruppen-Faktor V " + type.getDisplayName() + " war "
									+ enteredValue + ", auf " + correctedValue + " korrigiert (erlaubter Bereich [1.00; "
									+ parameter.getMaxGroupScaleLimit() + "]).");
						}
						Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
					} catch (NumberFormatException e) {
						factorField.setText(String.valueOf(parameter.getGroupScaleFactor(type)));
					}
				};
				factorField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
					if (!isNowFocused)
						applyFactor.run();
				});
				factorField.setOnAction(event -> applyFactor.run());

				grid.add(typeLabel, 0, rowIndex);
				grid.add(factorField, 1, rowIndex);
				rowIndex++;
			}

			VBox section = new VBox(10, infoLabel, grid);
			section.setPadding(new Insets(10, 0, 0, 0));
			return section;
		}
		/**
		 * Erzeugt den Toggle "Bewertungsmethode" (logPost/EP, Standard logPost).
		 * Bei Auswahl EP werden die Automatik-Buttons deaktiviert und der
		 * Einzelschritt-Button aktiviert - bewusst KEINE Endlosschleife bei EP.
		 */
		private HBox createEvaluationMethodToggle() {
			Label label = new Label(tr("Bewertungsmethode:"));

			Klein_Method_Tab_Builder.this.evaluationMethodGroup = new ToggleGroup();

			Klein_Method_Tab_Builder.this.logPostRadio = new RadioButton(tr(Klein_Method_Evaluation_Method.LOG_POST.getDisplayName()));
			Klein_Method_Tab_Builder.this.logPostRadio.setToggleGroup(Klein_Method_Tab_Builder.this.evaluationMethodGroup);
			Klein_Method_Tab_Builder.this.logPostRadio.setUserData(Klein_Method_Evaluation_Method.LOG_POST);
			Klein_Method_Tab_Builder.this.logPostRadio.setTooltip(new Tooltip(tr("Automatik: l\u00E4uft als Endlosschleife bis ein Grenzwert erreicht ist (Start/Stop).")));

			Klein_Method_Tab_Builder.this.epRadio = new RadioButton(tr(Klein_Method_Evaluation_Method.EP.getDisplayName()));
			Klein_Method_Tab_Builder.this.epRadio.setToggleGroup(Klein_Method_Tab_Builder.this.evaluationMethodGroup);
			Klein_Method_Tab_Builder.this.epRadio.setUserData(Klein_Method_Evaluation_Method.EP);
			Klein_Method_Tab_Builder.this.epRadio.setTooltip(new Tooltip(tr("Manuell: keine Automatik, nur einzelne Durchl\u00E4ufe per \u201EEinzelschritt\u201C.\n"
					+ "Nur w\u00E4hlbar, wenn Max-logPost < Grenzwert logPost ist.")));
			// EP ist nur waehlbar, wenn Max-logPost < Grenzwert logPost (Standard 2.45) und das Netz Messarten mit
			// EP-Bewertung enthaelt (reines Nivellement: nein)
			boolean epNetworkSuitable = Klein_Method_Tab_Builder.this.hasEpEligibleObservations();
			boolean epCurrentlyAllowed = epNetworkSuitable
					&& Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().isLogPostBelowThreshold(Klein_Method_Tab_Builder.this.kleinMethod.getParameter());
			Klein_Method_Tab_Builder.this.epRadio.setDisable(!epCurrentlyAllowed);
			Klein_Method_Tab_Builder.this.epLevelingNote = new Label(tr("(reines Nivellement: nur logPost)"));
			Klein_Method_Tab_Builder.this.epLevelingNote.setTooltip(new Tooltip(tr("Im Nivellement sind die Redundanzanteile klein - jeder H\u00F6henunterschied ist kaum kontrolliert. "
					+ "EP ist dort durch die Netzform hoch und kein Hinweis auf einen Messfehler. Bewertet wird deshalb mit logPost.\n"
					+ "In gemischten Netzen bewertet EP nur die \u00FCbrigen Messarten, das Nivellement bleibt bei logPost.")));
			Klein_Method_Tab_Builder.this.epLevelingNote.setVisible(!epNetworkSuitable);
			Klein_Method_Tab_Builder.this.epLevelingNote.setManaged(!epNetworkSuitable);

			// KORREKTUR Klein_Method_Parameter_Store.load() laeuft in Klein_Method's Konstruktor VOR diesem
			// Tab_Builder-Konstruktor (siehe Klein_Method.java: this.settingsStore.load(...) VOR this.tabBuilder = new
			// Klein_Method_Tab_Builder(this)) und kann parameter.evaluationMethod dabei bereits aus einer fruehreren
			// Sitzung auf EP setzen (persistierte Einstellung, siehe Klein_Method_Parameter_Store.KEY_EVALUATION_METHOD).
			// Behoben, indem die Vorauswahl der Radio-Buttons jetzt aus dem TATSAECHLICH bereits geladenen Parameterwert
			// abgeleitet wird, statt ihn zu ignorieren - UI und Parameter sind dadurch von Anfang an konsistent.
			Klein_Method_Evaluation_Method loadedEvaluationMethod = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getEvaluationMethod();
			boolean selectEpInitially = loadedEvaluationMethod == Klein_Method_Evaluation_Method.EP && epCurrentlyAllowed;
			Klein_Method_Tab_Builder.this.logPostRadio.setSelected(!selectEpInitially);
			Klein_Method_Tab_Builder.this.epRadio.setSelected(selectEpInitially);
			if (!selectEpInitially && loadedEvaluationMethod != Klein_Method_Evaluation_Method.LOG_POST) {
				Klein_Method_Tab_Builder.this.kleinMethod.getParameter().setEvaluationMethod(Klein_Method_Evaluation_Method.LOG_POST);
			}

			// HINWEIS (bewusst NICHT ge\u00E4ndert): der Listener wird weiterhin ERST NACH den obigen
			// setSelected()-Aufrufen registriert, und NICHT vorgezogen. Klein_Method_Tab_Builder.init()
			// baut rightColumn (und damit diese Methode) VOR createControlSection() auf, wo this.status
			// erst erzeugt wird - ein Listener-Trigger durch setSelected() waere an dieser Stelle bereits
			// ein Zugriff auf this.status.setText(...), BEVOR this.status ueberhaupt existiert
			// (NullPointerException beim Start der Oberflaeche). Die Synchronisation oben erfolgt deshalb
			// bewusst durch einen direkten Parameter-Aufruf statt durch vorzeitiges Registrieren des
			// Listeners.
			Klein_Method_Tab_Builder.this.evaluationMethodGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
				if (newToggle == null)
					return;
				Klein_Method_Evaluation_Method selected = (Klein_Method_Evaluation_Method) ((Toggle) newToggle).getUserData();
				Klein_Method_Tab_Builder.this.kleinMethod.getParameter().setEvaluationMethod(selected);

				boolean isLogPost = selected == Klein_Method_Evaluation_Method.LOG_POST;

				if (Klein_Method_Tab_Builder.this.automaticButtonsBox != null)
					Klein_Method_Tab_Builder.this.automaticButtonsBox.setDisable(!isLogPost);
				// manualButtonsBox (Einzelschritt) bleibt IMMER aktiv - unabhaengig von der Bewertungsmethode, siehe
				// updateAutomationAvailability().

				// Die Obergrenzen-Sperre hat Vorrang vor der Bewertungsmethode -
				// falls aktiv, Start/Einzelschritt trotzdem deaktiviert lassen.
				Klein_Method_Tab_Builder.this.updateAutomationAvailability();
				Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();

				Klein_Method_Tab_Builder.this.status.setText(tr("Bewertungsmethode: ") + tr(selected.getDisplayName()));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			return new HBox(10, label, Klein_Method_Tab_Builder.this.logPostRadio, Klein_Method_Tab_Builder.this.epRadio, Klein_Method_Tab_Builder.this.epLevelingNote);
		}
		/**
		 * Prueft die Voraussetzung fuer EP (Max-logPost < Grenzwert) neu und sperrt/entsperrt den EP-Radiobutton
		 * entsprechend.
		 *
		 * Siehe Aufruf in Klein_Method.runHeadlessAdjustmentThenRefreshStatus()).
		 */
		public void updateEvaluationMethodAvailability() {
			boolean epNetworkSuitable = Klein_Method_Tab_Builder.this.hasEpEligibleObservations();
			boolean epAllowed = epNetworkSuitable
					&& Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().isLogPostBelowThreshold(Klein_Method_Tab_Builder.this.kleinMethod.getParameter());
			Klein_Method_Tab_Builder.this.epRadio.setDisable(!epAllowed);
			if (Klein_Method_Tab_Builder.this.epLevelingNote != null) {
				Klein_Method_Tab_Builder.this.epLevelingNote.setVisible(!epNetworkSuitable);
				Klein_Method_Tab_Builder.this.epLevelingNote.setManaged(!epNetworkSuitable);
			}

			if (!epAllowed && Klein_Method_Tab_Builder.this.epRadio.isSelected()) {
				Klein_Method_Tab_Builder.this.evaluationMethodGroup.selectToggle(Klein_Method_Tab_Builder.this.logPostRadio); // loest den Listener oben aus
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log(epNetworkSuitable
						? "Klein_Method: EP nicht mehr zulaessig (Max-logPost >= Grenzwert) - automatisch auf logPost zurueckgeschaltet."
						: "Klein_Method: EP nicht zulaessig (reines Nivellement, kleine Redundanzanteile) - automatisch auf logPost zurueckgeschaltet.");
			}
		}	}

	// ============================================================================================================
	/** Ansicht (Einsteiger/Experte), Waechter, Live-Werte, Protokollstufe. */
	private final class View_Part {

		/**
		 * Aktualisiert Beschriftung, Farbe (fliessender Verlauf gr\u00FCn->gelb->rot)
		 * und Tooltip des W\u00E4chter-Toggles anhand des aktuellen Klein_Method_Guardian-
		 * Zustands. Aufzurufen: beim Aufbau der Oberfl\u00E4che, nach jeder Messung
		 * (Klein_Method.refreshGuardianAppearance(), von JEDER Netzausgleichung
		 * ausgel\u00F6st - manuell UND automatisch) sowie beim Umschalten des Toggles.
		 */
		public void updateGuardianAppearance() {
			Klein_Method_Guardian guardian = Klein_Method_Tab_Builder.this.kleinMethod.getGuardian();
			boolean enabled = guardian.isEnabled();

			if (!enabled) {
				Klein_Method_Tab_Builder.this.guardianToggleButton.setText(tr("AUS"));
				Klein_Method_Tab_Builder.this.guardianToggleButton.setStyle("-fx-background-color: " + UI_GREY + ";" + UI_STATUS_SHAPE); // grau = deaktiviert
				Klein_Method_Tab_Builder.this.guardianToggleButton.setTooltip(new Tooltip(tr("W\u00E4chter ist ausgeschaltet. Misst bei jeder Netzausgleichung "
						+ "(manuell oder automatisch) Dauer und Beobachtungsanzahl und vergleicht sie mit der ersten Messung "
						+ "(Referenz) - da feste Schwellen PC-abh\u00E4ngig w\u00E4ren.")));
				return;
			}

			if (!guardian.hasReferenceMeasurement()) {
				Klein_Method_Tab_Builder.this.guardianToggleButton.setText("AN");
				Klein_Method_Tab_Builder.this.guardianToggleButton.setStyle("-fx-background-color: " + UI_GREY + ";" + UI_STATUS_SHAPE); // grau = noch keine Referenz
				Klein_Method_Tab_Builder.this.guardianToggleButton.setTooltip(new Tooltip(tr("W\u00E4chter ist an, wartet auf die erste Netzausgleichung "
						+ "(wird zur Referenzmessung).")));
				return;
			}

			// feste Status-Farbe (Gruen/Gelb/Rot) statt fliessendem Verlauf - eine Farbe je Bedeutung
			String hexColor = guardian.getLastStatus().getColorHex();
			Klein_Method_Tab_Builder.this.guardianToggleButton.setText(tr(guardian.getLastStatus().getDisplayName()));
			Klein_Method_Tab_Builder.this.guardianToggleButton.setStyle("-fx-background-color: " + hexColor + ";" + UI_STATUS_SHAPE);

			String ratioText = Klein_Method_Format.format(guardian.getLastRatio(), "\u00D7 Referenz", Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getDecimalPlaces());
			Klein_Method_Tab_Builder.this.guardianToggleButton.setTooltip(new Tooltip(tr("Aktuelle Netzausgleichung: ") + ratioText + tr(" (Dauer bzw. Beobachtungsanzahl, "
					+ "der schlechtere Wert entscheidet).\nStabil < 2,0\u00D7, Kritisch 2,0\u20134,0\u00D7, Fehler \u2265 4,0\u00D7 der ersten Messung "
					+ "in diesem Projekt.")));
		}
		/**
		 * Fliessender Farbverlauf gr\u00FCn(0.0)->gelb(0.5)->rot(1.0) - dieselbe
		 * Interpolationslogik wie updateProgressBarColor(), nur in umgekehrter
		 * Richtung (0.0=gr\u00FCn statt rot) und als eigener Rueckgabewert (Hex-String)
		 * statt direkter Style-Anwendung, da hier kein ProgressBar/Label-Paar
		 * betroffen ist, sondern ein einzelner Button.
		 */
		private String interpolateGuardianColor(double fraction) {
			double clamped = Math.max(0.0, Math.min(fraction, 1.0));
			Color color;
			if (clamped <= 0.5) {
				// gruen -> gelb
				color = Color.rgb((int) Math.round(255 * (clamped / 0.5)), 255, 0);
			} else {
				// gelb -> rot
				color = Color.rgb(255, (int) Math.round(255 * (1.0 - (clamped - 0.5) / 0.5)), 0);
			}
			return String.format("#%02X%02X%02X",
					(int) Math.round(color.getRed() * 255),
					(int) Math.round(color.getGreen() * 255),
					(int) Math.round(color.getBlue() * 255));
		}
		/**
		 * Aktualisiert die 2 Live-Modus-Felder (Max-logPost/Max-EP) - dieselben
		 * Werte, die auch ins Protokoll geschrieben werden (siehe Klein_Method_
		 * Iteration_Task, \u00FCber Klein_Method.refreshLiveModeFields()). Tut nichts,
		 * wenn der Live-Modus gerade ausgeschaltet ist.
		 */
		public void updateLiveModeFields(double maxLogPost, double maxEp) {
			if (!Klein_Method_Tab_Builder.this.liveModeToggleButton.isSelected())
				return;
			this.applyDynamicLiveText();

			int decimalPlaces = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getDecimalPlaces();
			Klein_Method_Tab_Builder.this.liveLogPostLabel.setText(Klein_Method_Format.format(maxLogPost, "", decimalPlaces));
			Klein_Method_Tab_Builder.this.liveEpLabel.setText(Klein_Method_Format.format(maxEp, "mm", decimalPlaces));
		}
		/** Live-Zeile der dynamischen AGL aus dem Parameter uebernehmen (JavaFX-Thread). */
		private void applyDynamicLiveText() {
			if (Klein_Method_Tab_Builder.this.dynamicLiveLabel == null || !Klein_Method_Tab_Builder.this.liveModeToggleButton.isSelected())
				return;
			String text = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getDynamicLiveText();
			Klein_Method_Tab_Builder.this.dynamicLiveLabel.setText(text == null ? "\u2013" : text);
		}
		/** Elemente, die nur in der freien/fixen AGL verwendet werden. */
		private void markFreeFixedOnly(Node... nodes) {
			for (Node node : nodes)
				if (node != null)
					Klein_Method_Tab_Builder.this.freeFixedOnlyNodes.add(node);
		}
		private void markExpertOnly(Node... nodes) {
			for (Node node : nodes)
				if (node != null)
					Klein_Method_Tab_Builder.this.expertOnlyNodes.add(node);
		}
		/**
		 * Einsteigeransicht blendet alle Experten-Elemente aus; Expertenansicht zeigt sie je nach Ausgleichungsschritt.
		 */
		private void applyViewMode() {
			boolean simple = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().isSimpleView();
			boolean dynamic = Klein_Method_Tab_Builder.this.visibleStep == Klein_Method_Adjustment_Step.DYNAMIC;
			for (Node node : Klein_Method_Tab_Builder.this.expertOnlyNodes) {
				boolean show = !simple && !(dynamic && Klein_Method_Tab_Builder.this.freeFixedOnlyNodes.contains(node));
				node.setVisible(show);
				node.setManaged(show);
			}
			if (Klein_Method_Tab_Builder.this.simpleViewHintLabel != null) {
				Klein_Method_Tab_Builder.this.simpleViewHintLabel.setVisible(simple);
				Klein_Method_Tab_Builder.this.simpleViewHintLabel.setManaged(simple);
			}
		}
		/**
		 * Aktualisiert Beschriftung und Auswahlzustand des Protokoll-Toggles
		 * gemaess dem tatsaechlichen Zustand von Klein_Method_Protocol.
		 */
		private void updateProtocolLevelSelection() {
			Klein_Method_Tab_Builder.this.protocolLevelComboBox.setValue(Klein_Method_Tab_Builder.this.kleinMethod.getProtocol().getLevel());
		}
		private void updateProtocolToggleAvailability() {
			this.updateProtocolLevelSelection();
		}	}

	// ============================================================================================================
	/** Bereiche Iteration und Ausgelesene Werte (Diagnose, Zusatzparameter, Integritaet). */
	private final class Diagnostic_Part {

		private VBox createIterationSection() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			Label thresholdLabel = new Label(tr("Grenzwert Iterationen:"));
			TextField thresholdField = new TextField(String.valueOf(parameter.getIterationThreshold()));
			thresholdField.setPrefWidth(100);
			thresholdField.setTooltip(new Tooltip(tr("Bricht die Automatik ab, sobald diese Anzahl Iterationen \u00FCberschritten ist.\n"
					+ "Standard: ") + Klein_Method_Parameter.ITERATION_THRESHOLD_DEFAULT
					+ tr(", Obergrenze: ") + Klein_Method_Parameter.ITERATION_THRESHOLD_MAX + tr(" (wird automatisch erzwungen).")));

			Runnable applyThreshold = () -> {
				try {
					int enteredValue = Integer.parseInt(thresholdField.getText().trim());
					int correctedValue = parameter.setIterationThreshold(enteredValue);
					if (correctedValue != enteredValue) {
						thresholdField.setText(String.valueOf(correctedValue));
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Grenzwert Iterationen war " + enteredValue
								+ ", auf " + correctedValue + " korrigiert (Obergrenze " + Klein_Method_Parameter.ITERATION_THRESHOLD_MAX + ").");
						Klein_Method_Tab_Builder.this.showMinimumCorrectedAlert("Grenzwert Iterationen", enteredValue, correctedValue);
					}
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				} catch (NumberFormatException e) {
					thresholdField.setText(String.valueOf(parameter.getIterationThreshold()));
				}
			};
			thresholdField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					applyThreshold.run();
			});
			thresholdField.setOnAction(event -> applyThreshold.run());

			HBox thresholdRow = new HBox(10, thresholdLabel, thresholdField);

			Klein_Method_Tab_Builder.this.iterationCountLabel = new Label(tr("Aktuelle Iteration: ") + parameter.getIterationCount());

			Klein_Method_Tab_Builder.this.resetIterationButton = new Button(tr("Reset"));
			// Reset setzt wieder NUR den Iterationszaehler zurueck - die Notfreigabe nach fertiger Automatik liegt jetzt
			// bei "Sperre aufheben".
			Klein_Method_Tab_Builder.this.resetIterationButton.setTooltip(new Tooltip(tr("Setzt den Iterationszaehler manuell auf 0 zur\u00FCck. Beeinflusst NICHT eine gerade laufende Automatik.")));
			Klein_Method_Tab_Builder.this.resetIterationButton.setOnAction(event -> {
				parameter.resetIterationCount();
				Klein_Method_Tab_Builder.this.iterationCountLabel.setText(tr("Aktuelle Iteration: ") + parameter.getIterationCount());
				Klein_Method_Tab_Builder.this.progressBarOverlayLabel.setText(tr("Iteration: ") + parameter.getIterationCount());
				this.applyIterationLimitStatus();
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Iterationszaehler manuell zurueckgesetzt.");
			});

			HBox countRow = new HBox(10, Klein_Method_Tab_Builder.this.iterationCountLabel, Klein_Method_Tab_Builder.this.resetIterationButton);

			// -- 2-Farben-Ampel fuer die Iterationsgrenze, EXAKT dieselbe Geometrie/derselbe Control-Typ wie
			// oscillationStatusValueLabel (Button, 160x26, BOLD 12, zentriert, mouseTransparent) - siehe
			// applyIterationLimitStatus() unten. ---
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel = new Button();
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setFocusTraversable(false);
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setMouseTransparent(true);
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setAlignment(Pos.CENTER);
			Klein_Method_Tab_Builder.this.applyStatusBadgeGeometry(Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel);
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setTooltip(new Tooltip(tr("Gr\u00FCn: Iterationszahl liegt noch unter der "
					+ "Iterationsgrenze. Rot: Die Iterationsgrenze wurde erreicht (siehe Feld \"Iterationsgrenze\" oben).")));
			this.applyIterationLimitStatus();
			HBox iterationLimitStatusRow = new HBox(10, new Label(tr("Iterationsgrenze-Status:")), Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel);

			VBox section = new VBox(10, thresholdRow, countRow, iterationLimitStatusRow);
			section.setPadding(new Insets(10, 0, 0, 0));
			return section;
		}
		/**
		 * Baut den Inhalt der Gruppe "Ausgelesene Werte": pro Grenzwert-Typ
		 * (dynamisch aus Klein_Method_Threshold_Type, gilt also automatisch
		 * auch fuer kuenftige Typen) ein Anzeigefeld mit dem zuletzt aus der
		 * JAG3D-Projektdatenbank gelesenen Wert, plus ein Button, der genau
		 * EINEN read()-Durchlauf anstoesst (keine Automatik, kein Schreiben) -
		 * rein zum Pruefen, ob das Auslesen fuer das aktuell geladene Projekt
		 * funktioniert. Ist ein Wert fuer das Projekt nicht verfuegbar (z.B.
		 * keine Zenitwinkel-Beobachtungen vorhanden), liefert read() dafuer 0
		 * (siehe Ruckfallebene in Klein_Method_Database_Handler).
		 */
		private VBox createDiagnosticSection() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			GridPane logPostGrid = new GridPane();
			logPostGrid.setHgap(10);
			logPostGrid.setVgap(8);

			GridPane epGrid = new GridPane();
			epGrid.setHgap(10);
			epGrid.setVgap(8);

			// dritte Gruppe rechts: aktuelle Gruppen-Messunsicherheiten sigma_a | sigma_b(sqrt d) | sigma_c(d) je Beobachtungsart
			// (Spanne ueber alle aktiven Gruppen), mit Kopfzeile - logPost/EP bekommen eine leere Kopfzeile, damit die Zeilen fluchten
			GridPane sigmaGrid = new GridPane();
			sigmaGrid.setHgap(18);
			sigmaGrid.setVgap(8);
			String[] sigmaHeaders = { "\u03C3a", "\u03C3b(\u221Ad)", "\u03C3c(d)" };
			String[] sigmaHeaderTips = {
					tr("Aktueller konstanter Anteil \u03C3a der Gruppen-Messunsicherheit (wie in JAG3D unter Eigenschaften \u2192 Messunsicherheiten), "
							+ "kleinster bis größter Wert aller aktiven Gruppen dieser Beobachtungsart."),
					tr("Aktueller streckenwurzelabhängiger Anteil \u03C3b(\u221Ad) der Gruppen-Messunsicherheit, kleinster bis größter Wert aller aktiven Gruppen "
							+ "dieser Beobachtungsart. 0 = Anteil nicht verwendet."),
					tr("Aktueller streckenabhängiger Anteil \u03C3c(d) der Gruppen-Messunsicherheit (Richtungen in mm, Strecken und GNSS in ppm), kleinster bis "
							+ "größter Wert aller aktiven Gruppen dieser Beobachtungsart. 0 = Anteil nicht verwendet.") };
			String sigmaCommonTip = "\n" + tr("Live aus der JAG3D-Projektdatenbank gelesen (gleicher Lesezyklus wie logPost und EP). Nur Anzeige.");
			for (int c = 0; c < 3; c++) {
				Label header = new Label(sigmaHeaders[c] + " " + tr("aktuell"));
				header.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
				header.setTooltip(new Tooltip(sigmaHeaderTips[c] + sigmaCommonTip));
				sigmaGrid.add(header, c, 0);
			}
			logPostGrid.add(new Label(""), 0, 0);
			epGrid.add(new Label(""), 0, 0);

			int logPostRow = 1;
			int epRow = 1;
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.values()) {
				String unitSuffix = type.getUnit().isEmpty() ? "" : " [" + type.getUnit() + "]";
				Label nameLabel = new Label(tr(type.getDisplayName()) + unitSuffix + ":");

				Label valueLabel = new Label(this.formatDiagnosticValue(parameter, type));
				valueLabel.setTooltip(new Tooltip(tr("Zuletzt aus der Datenbank gelesener Wert f\u00FCr ") + tr(type.getDisplayName())
						+ tr(". \u201Ek.A.\u201C, wenn f\u00FCr das aktuelle Projekt nicht verf\u00FCgbar.")));
				Klein_Method_Tab_Builder.this.diagnosticValueLabels.put(type, valueLabel);

				// links alles mit logPost, rechts alles mit EP.
				if (type.getCategory() == Klein_Method_Threshold_Type.Category.LOG_POST) {
					logPostGrid.add(nameLabel, 0, logPostRow);
					logPostGrid.add(valueLabel, 1, logPostRow);
					Label[] sigmaLabels = new Label[3];
					for (int c = 0; c < 3; c++) {
						sigmaLabels[c] = new Label(this.formatGroupSigma(parameter, type, c));
						sigmaLabels[c].setTooltip(new Tooltip(sigmaHeaderTips[c] + " (" + tr(type.getDisplayName()).replace(" logPost", "") + ")"));
						sigmaGrid.add(sigmaLabels[c], c, logPostRow);
					}
					Klein_Method_Tab_Builder.this.groupSigmaLabels.put(type, sigmaLabels);
					logPostRow++;
				} else {
					epGrid.add(nameLabel, 0, epRow);
					epGrid.add(valueLabel, 1, epRow);
					epRow++;
				}
			}

			Separator verticalSeparator = new Separator(Orientation.VERTICAL);
			HBox columns = new HBox(15, logPostGrid, verticalSeparator, epGrid, new Separator(Orientation.VERTICAL), sigmaGrid);

			Button readButton = new Button(tr("Werte jetzt auslesen"));
			readButton.setTooltip(new Tooltip(tr("F\u00FChrt einen einmaligen Lesevorgang aus der JAG3D-Projektdatenbank aus "
					+ "(keine Automatik, kein Schreiben) - zum Pr\u00FCfen, ob das Auslesen funktioniert.")));
			readButton.setOnAction(event -> {
				readButton.setDisable(true);
				Thread thread = Klein_Method.busyThread(() -> {
					try {
						Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().read(parameter);
						double freshMaxLogPost = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().getMaxLogPostValue(parameter);
						double freshMaxEp = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().getMaxEpValue(parameter);
						Platform.runLater(() -> {
							this.updateDiagnosticValueLabels();
							Klein_Method_Tab_Builder.this.updateLiveModeFields(freshMaxLogPost, freshMaxEp);
							readButton.setDisable(false);
						});
					} catch (SQLException e) {
						Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Fehler beim Auslesen der Diagnose-Werte: " + e.getMessage());
						Platform.runLater(() -> readButton.setDisable(false));
					}
				}, "Klein_Method_Diagnostic_Read");
				thread.setDaemon(true);
				thread.start();
			});

			// RESTLOS aus dieser Sektion entfernt - kein verwaister UI-Rest verbleibt. Das Feld lebt jetzt als
			// deactivatedGnssBaselineCountField in Sektion "Steuerung" (createOptionsSection()), siehe dort. "columns"
			// (logPost/EP-Zeile) rueckt dadurch direkt vor hardClampStatusRow.

			// rein informative Zeile, gespeist aus Klein_Method_Parameter.isHardClampAppliedThisRun() (gesetzt von
			// Klein_Method_Functions.performFloorEnforcementCompression(), siehe dort). Aktualisiert ueber
			// updateHardClampStatusLabel(), aufgerufen aus updateDiagnosticValueLabels() (wie vom Auftrag gefordert)
			// sowie einmalig hier fuer den Anfangszustand.
			Label hardClampStatusNameLabel = new Label(tr("Erzwungener HSQLDB-Boden:"));
			Klein_Method_Tab_Builder.this.hardClampStatusValueLabel = new Label();
			Klein_Method_Tab_Builder.this.hardClampStatusValueLabel.setTooltip(new Tooltip(tr("Bestaetigt, ob die Untergrenzen-Erzwingung (Toggle \"Untergrenze "
					+ "erzwingen\") beim Erreichen von PHASE: DONE in DIESEM Lauf tatsaechlich mindestens eine individuelle "
					+ "sigma_0 per Hard-Clamp auf den geodaetischen Boden gesetzt hat. Wird bei jedem neuen Automatik-/"
					+ "Einzelschritt-Lauf zurueckgesetzt.")));
			HBox hardClampStatusRow = new HBox(10, hardClampStatusNameLabel, Klein_Method_Tab_Builder.this.hardClampStatusValueLabel);
			this.updateHardClampStatusLabel(); // Anfangszustand

			// rein informative Zeile, direkt UNTER "Erzwungener HSQLDB-Boden:" - gespeist aus
			// Klein_Method_Parameter.getTotalGnssCalculations()/getProzentualSplittedCalculations() (gefuellt von
			// Klein_Method_Functions.performIndividualCalibrationStep(), siehe dort). "k.A." (neutrales Grau), solange
			// noch keine GNSS-Achse in dieser Sitzung den finalen Verarbeitungs-Zyklus erreicht hat. Sobald Daten
			// vorliegen: kraeftiges Blau/Fett, "X.X % (Aktiv im RAM)". Aktualisiert ueber
			// updateGnssPercentageRateLabel(), aufgerufen aus updateDiagnosticValueLabels() (wie vom Auftrag gefordert)
			// sowie einmalig hier fuer den Anfangszustand.
			Label gnssPercentageRateNameLabel = new Label(tr("Reelle EP-Verteilungsrate:"));
			Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel = new Label();
			Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel.setTooltip(new Tooltip(tr("Anteil der GNSS-Achsen (X/Y/Z), bei denen in DIESER Sitzung "
					+ "tatsaechlich der prozentuale Achsen-Split (Verteilungsmodus PERCENTAGE) berechnet UND wirklich in die HSQLDB "
					+ "geschrieben wurde (nicht durch die Schreib-Sperre blockiert), bezogen auf ALLE GNSS-Achsen, die den finalen "
					+ "Verarbeitungs-Zyklus erreicht haben. Wird bei jedem neuen Automatik-Lauf zurueckgesetzt.")));
			HBox gnssPercentageRateRow = new HBox(10, gnssPercentageRateNameLabel, Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel);
			this.updateGnssPercentageRateLabel(); // Anfangszustand

			// Diese Sektion ("Ausgelesene Werte") zeigt seitdem
			// columns/hardClampStatusRow/gnssPercentageRateRow/readButton. "Deaktivierte BL: X / Y" ebenfalls RESTLOS aus
			// dieser Sektion entfernt und nach createOptionsSection() verschoben - siehe dort.
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(hardClampStatusRow, gnssPercentageRateRow);
			VBox section = new VBox(10, columns,
					hardClampStatusRow, gnssPercentageRateRow, readButton);
			section.setPadding(new Insets(10, 0, 0, 0));
			return section;
		}
		/**
		 * setzt Text und Ampel-Farben (Hintergrund UND Textfarbe, siehe Klein_Method_Oscillation_Status) der
		 * Ampel-Anzeige "Oszillations-Status".
		 *
		 * Feldtyp Button statt Label, damit derselbe Standard-Skin wie guardianToggleButton greift.
		 * Breite/Hoehe/Ausrichtung/Schrift sind jetzt FEST in createDiagnosticSection() gesetzt (siehe dort) - hier
		 * bleibt bewusst NUR noch der pro Zustand wechselnde Teil (Text + Farben).
		 */
		// Parametertyp qualifiziert ueber Klein_Method_Parameter, seit das Enum als Nested Enum dort lebt (keine eigene
		// Datei/Import mehr).
		private void applyOscillationStatus(Klein_Method_Parameter.Klein_Method_Oscillation_Status status) {
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setText(tr(status.getDisplayName()));
			Klein_Method_Tab_Builder.this.oscillationStatusValueLabel.setStyle("-fx-background-color: " + status.getColorHex() + "; -fx-text-fill: black;" + UI_STATUS_SHAPE);
		}
		/**
		 * 2-Farben-Ampel fuer iterationLimitStatusValueLabel - GRUEN/#2ecc71, weisse fette zentrierte Schrift, solange
		 * iterationCount < iterationThreshold, sonst ROT/#e74c3c - exakt dieselbe Bedingung wie "iterationLimitExceeded"
		 * in Klein_Method_Iteration_Task (this.parameter.getIterationCount() > this.parameter.getIterationThreshold()
		 * dort) - hier bewusst ">=" statt ">", da diese Anzeige das ERREICHEN der Grenze signalisieren soll, nicht erst
		 * deren Ueberschreiten.
		 *
		 * BEWUSST NICHT angefasst: guardianToggleButton bleibt UNVERAENDERT bei seinem bestehenden, FLIESSENDEN
		 * Gruen-Gelb-Rot-Verlauf (siehe interpolateGuardian Color() und der Klassendoc von Klein_Method_Guardian_Status,
		 * die diesen Verlauf ausdruecklich als gewollt dokumentiert) - ein Umbau auf eine harte 2-Farben-Ampel wuerde die
		 * bestehende Fruehwarnstufe "KRITISCH"/gelb (2.0x bis 4.0x Referenz) und den gesamten kontinuierlichen Verlauf
		 * ersatzlos loeschen - eine echte fachliche Regression, keine reine Kosmetik.
		 */
		private void applyIterationLimitStatus() {
			int iterationCount = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getIterationCount();
			int iterationThreshold = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getIterationThreshold();
			boolean limitReached = iterationCount >= iterationThreshold;
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setText(limitReached ? tr("Grenze erreicht") : tr("Innerhalb Grenze"));
			Klein_Method_Tab_Builder.this.iterationLimitStatusValueLabel.setStyle("-fx-background-color: " + (limitReached ? UI_RED : UI_GREEN)
					+ "; -fx-text-fill: black;" + UI_STATUS_SHAPE);
		}
		/**
		 * berechnet und zeigt einen SHA-256-Fingerabdruck des aktuell GELADENEN Rechenkerns.
		 *
		 * WICHTIGE FACHLICHE EINSCHRAENKUNG (bewusste Abweichung vom woertlichen Auftrag, hier dokumentiert statt
		 * stillschweigend eine falsche Sicherheit vorzutaeuschen): der Auftrag wollte einen Abgleich "Ist-Signatur" gegen
		 * eine vorab hinterlegte " Soll-Signatur". Eine SOLCHE Soll-Signatur kann in diesem Projekt NICHT ehrlich vorab
		 * hinterlegt werden: sie muesste von genau dem Bytecode stammen, den DEIN Eclipse/JDK aus diesem Quellcode
		 * erzeugt - der Compiler-Output ist aber von JDK-Version/Einstellungen abhaengig, hier beim Erzeugen der
		 * Quelldateien nicht identisch nachstell-bar. Eine fest einprogrammierte "Soll"-Konstante waere daher entweder
		 * (a) inhaltslos gruen (wenn sie zirkulaer aus genau dem Wert gebildet wird, den die Laufzeit ohnehin selbst
		 * berechnet - taeuscht eine Pruefung nur vor) oder (b) bei JEDEM Build faelschlich rot (falscher Alarm bei
		 * voellig korrektem Code) - beides schlimmer als der ehrliche Verzicht darauf.
		 *
		 * STATTDESSEN (echte, ueberpruefbare Information): ein LIVE-Fingerabdruck der .class-Bytes von
		 * Klein_Method_Functions ( = "Rechenkern", siehe durchgehende Bezeichnung in diesem Projekt), gelesen ueber den
		 * Classloader (Klein_Method_Functions.class.getResourceAsStream(...)) - funktioniert unveraendert, ob aus einem
		 * Eclipse-"bin"-Ordner oder einem gepackten JAR geladen, OHNE einen Dateisystempfad zu erraten.
		 *
		 * GRUEN: das Modul konnte gelesen/gehasht werden - es ist tatsaechlich das geladene, lauffaehige
		 * Bytecode-Artefakt. ROT ("nicht verfuegbar"): der Classloader konnte die .class-Datei nicht liefern (z.B.
		 * Workspace nicht gebaut, kaputte Installation) - IN DIESEM FALL WUERDE DER TAB OHNEHIN NICHT LADEN, das Label
		 * ist hier vor allem defensiv (nie NullPointer/ Absturz statt Statusanzeige).
		 */
		private void applyIntegrityStatus() {
			String signature = this.computeCoreModuleSignature();
			if (signature != null) {
				Klein_Method_Tab_Builder.this.integrityStatusValueLabel.setText(tr("SHA256 Prüfsumme: ") + signature + tr(" (gültig)"));
				Klein_Method_Tab_Builder.this.integrityStatusValueLabel.setStyle("-fx-background-color: " + UI_GREEN + "; -fx-text-fill: black;");
			} else {
				Klein_Method_Tab_Builder.this.integrityStatusValueLabel.setText(tr("SHA256 Prüfsumme: nicht verfügbar (ungültig)"));
				Klein_Method_Tab_Builder.this.integrityStatusValueLabel.setStyle("-fx-background-color: " + UI_RED + "; -fx-text-fill: black;");
			}
		}
		/**
		 * Liest die tatsaechlich geladenen .class-Bytes von Klein_Method_Functions
		 * ueber den Classloader und bildet daraus einen SHA-256-Hex-String (64
		 * Zeichen). null bei jedem Fehler (Ressource nicht auffindbar, Lesefehler,
		 * Algorithmus nicht verfuegbar - SHA-256 ist zwar in jeder Standard-JVM
		 * vorhanden, die Pruefung bleibt trotzdem defensiv statt eine ungeprüfte
		 * Annahme zu treffen).
		 */
		private String computeCoreModuleSignature() {
			try (InputStream in = Klein_Method_Functions.class.getResourceAsStream("Klein_Method_Functions.class")) {
				if (in == null)
					return null;

				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = in.read(buffer)) != -1)
					digest.update(buffer, 0, bytesRead);

				byte[] hashBytes = digest.digest();
				StringBuilder hex = new StringBuilder(hashBytes.length * 2);
				for (byte b : hashBytes)
					hex.append(String.format("%02x", b));
				return hex.toString();
			} catch (IOException | NoSuchAlgorithmException e) {
				return null;
			}
		}
		/**
		 * formatiert den reinen Wert-Text ("X / Y") aus den zuletzt gelesenen Zaehlerstaenden
		 * (Klein_Method_Parameter.getDisabledGnssBaselineCount()/getTotalGnssBaselineCount()).
		 */
		private String formatDeactivatedGnssBaselineCount(Klein_Method_Parameter parameter) {
			return parameter.getDisabledGnssBaselineCount() + " / " + parameter.getTotalGnssBaselineCount();
		}
		/**
		 * Baut den Inhalt der Gruppe "Zusatzparameter Strecken": Nullpunktabweichung
		 * a und Ma\u00DFstab m aus dem JAG3D-Tab "Zusatzparameter" (Beobachtungsgruppen
		 * -> Horizontalstrecken/Schr\u00E4gstrecken), je Typ die EINE Gruppe, die diese
		 * Parameter aktiv hat (laut Vorgabe kann nur eine Gruppe je Typ diese
		 * Parameter aktiv haben - siehe Klein_Method_Database_Handler.
		 * readAdditionalParameters()). Rein informativ, nicht editierbar. Kein
		 * eigener Button - wird automatisch bei jedem Tab-Aktivieren aktualisiert
		 * (siehe refreshAdditionalParameters(), aufgerufen von Klein_Method.java).
		 */
		private VBox createAdditionalParameterSection() {
			GridPane grid = new GridPane();
			grid.setHgap(10);
			grid.setVgap(8);

			int row = 0;
			for (Klein_Method_Threshold_Type type : ADDITIONAL_PARAMETER_TYPES) {
				String typeName = tr(type.getDisplayName()).replace(" logPost", "");

				Label offsetNameLabel = new Label(typeName + tr(" - Nullpunktabweichung a [mm]:"));
				Label offsetValueLabel = new Label("k.A.");
				offsetValueLabel.setTooltip(new Tooltip(tr("Nullpunktabweichung a der EINEN Gruppe dieses Typs, die diesen Parameter "
						+ "aktiv hat (Zusatzparameter-Tab in JAG3D). Wird bei jedem \u00D6ffnen dieses Tabs neu gelesen. "
						+ "\u201Ek.A.\u201C, wenn keine Gruppe diesen Parameter aktiv hat (ein tats\u00E4chlicher Wert von 0 wird als \u201E0\u201C angezeigt).")));
				Klein_Method_Tab_Builder.this.additionalParameterOffsetLabels.put(type, offsetValueLabel);

				CheckBox offsetCheckBox = new CheckBox(tr("Additionskonstante berechnen (") + typeName + ")");
				offsetCheckBox.setTooltip(new Tooltip(tr("Aktiviert/deaktiviert die Nullpunktabweichung (\u201EAdditionskonstante berechnen\u201C) "
						+ "f\u00FCr alle Gruppen dieses Typs, direkt aus Klein_Method heraus - erspart den Wechsel in die JAG3D-"
						+ "Gruppeneigenschaften. Gibt es mehrere Gruppen dieses Typs, wird der Haken auf ALLE Gruppen angewendet "
						+ "und eine Hinweis-Messagebox gezeigt.")));
				offsetCheckBox.setOnAction(event -> this.applyAdditionalParameterCheckBox(type, false, offsetCheckBox));
				Klein_Method_Tab_Builder.this.additionalParameterOffsetCheckBoxes.put(type, offsetCheckBox);
				// Initial ausgeblendet - bis applyAdjustmentStep() beim ersten Tab-Aktivieren den echten Schritt kennt.
				offsetCheckBox.setVisible(false);
				offsetCheckBox.setManaged(false);

				Label scaleNameLabel = new Label(typeName + tr(" - Ma\u00DFstab m [ppm]:"));
				Label scaleValueLabel = new Label("k.A.");
				scaleValueLabel.setTooltip(new Tooltip(tr("Ma\u00DFstab m der EINEN Gruppe dieses Typs, die diesen Parameter aktiv hat "
						+ "(Zusatzparameter-Tab in JAG3D). Wird bei jedem \u00D6ffnen dieses Tabs neu gelesen. "
						+ "\u201Ek.A.\u201C, wenn keine Gruppe diesen Parameter aktiv hat (ein tats\u00E4chlicher Wert von 0 wird als \u201E0\u201C angezeigt).")));
				Klein_Method_Tab_Builder.this.additionalParameterScaleLabels.put(type, scaleValueLabel);

				CheckBox scaleCheckBox = new CheckBox(tr("Ma\u00DFstab berechnen (") + typeName + ")");
				scaleCheckBox.setTooltip(new Tooltip(tr("Aktiviert/deaktiviert den Ma\u00DFstab f\u00FCr alle Gruppen dieses Typs, direkt aus "
						+ "Klein_Method heraus - erspart den Wechsel in die JAG3D-Gruppeneigenschaften. Gibt es mehrere Gruppen "
						+ "dieses Typs, wird der Haken auf ALLE Gruppen angewendet und eine Hinweis-Messagebox gezeigt.")));
				scaleCheckBox.setOnAction(event -> this.applyAdditionalParameterCheckBox(type, true, scaleCheckBox));
				Klein_Method_Tab_Builder.this.additionalParameterScaleCheckBoxes.put(type, scaleCheckBox);
				// Initial ausgeblendet - bis applyAdjustmentStep() beim ersten Tab-Aktivieren den echten Schritt kennt.
				scaleCheckBox.setVisible(false);
				scaleCheckBox.setManaged(false);

				grid.add(offsetNameLabel, 0, row);
				grid.add(offsetValueLabel, 1, row);
				grid.add(offsetCheckBox, 2, row);
				row++;
				grid.add(scaleNameLabel, 0, row);
				grid.add(scaleValueLabel, 1, row);
				grid.add(scaleCheckBox, 2, row);
				row++;
			}

			VBox section = new VBox(10, grid);
			section.setPadding(new Insets(10, 0, 0, 0));
			return section;
		}
		/**
		 * Liest Nullpunktabweichung/Ma\u00DFstab fuer beide Streckentypen frisch aus
		 * der Datenbank und aktualisiert die Labels - OHNE eigenen Thread (der
		 * Aufrufer, Klein_Method.runHeadlessAdjustmentThenRefreshStatus(), laeuft
		 * bereits im Hintergrund-Thread). NUR die Label-Aktualisierung selbst
		 * laeuft ueber Platform.runLater(), wie bei den anderen Refresh-Methoden.
		 */
		public void refreshAdditionalParameters() throws SQLException {
			for (Klein_Method_Threshold_Type type : ADDITIONAL_PARAMETER_TYPES) {
				double[] raw = Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().readAdditionalParameters(type);
				// Rohwerte aus der DB (Meter/unitless) in Arbeitseinheit (mm/ppm)
				// umrechnen - Massstab ist eine MULTIPLIKATIVE Korrektur (um 1.0
				// zentriert), deshalb PARTS_PER_MILLION_WRT_ONE statt WRT_ZERO
				// (das waere fuer additive Unsicherheiten wie sigma_c, siehe dort).
				double offsetMm = Double.isNaN(raw[0]) ? Double.NaN : LengthUnit.MILLIMETER.fromMeter(raw[0]);
				double scalePpm = Double.isNaN(raw[1]) ? Double.NaN : ScaleUnit.PARTS_PER_MILLION_WRT_ONE.fromUnitless(raw[1]);

				// Checkbox-Zustand: nach validateAdditionalParameterConsistencyOnTabActivation()
				// (laeuft VOR diesem Aufruf) sind alle Gruppen eines Typs bereits konsistent -
				// es reicht daher, den Zustand irgendeiner Gruppe zu uebernehmen (leere Map -> aus).
				Map<Integer, boolean[]> groupStates = Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().readAdditionalParameterGroupStates(type);
				boolean offsetEnabled = groupStates.values().stream().anyMatch(state -> state[0]);
				boolean scaleEnabled = groupStates.values().stream().anyMatch(state -> state[1]);

				Platform.runLater(() -> {
					int decimalPlaces = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getDecimalPlaces();
					Label offsetLabel = Klein_Method_Tab_Builder.this.additionalParameterOffsetLabels.get(type);
					offsetLabel.setText(Double.isNaN(offsetMm) ? "k.A." : Klein_Method_Format.format(offsetMm, "mm", decimalPlaces));
					offsetLabel.setStyle(this.colorStyleForAdditionalParameter(offsetMm,
							Klein_Method_Default_Values.ADDITIONAL_PARAMETER_OFFSET_YELLOW_THRESHOLD_MM,
							Klein_Method_Default_Values.ADDITIONAL_PARAMETER_OFFSET_RED_THRESHOLD_MM));
					Klein_Method_Tab_Builder.this.additionalParameterOffsetCheckBoxes.get(type).setSelected(offsetEnabled);

					Label scaleLabel = Klein_Method_Tab_Builder.this.additionalParameterScaleLabels.get(type);
					scaleLabel.setText(Double.isNaN(scalePpm) ? "k.A." : Klein_Method_Format.format(scalePpm, tr("ppm"), decimalPlaces));
					scaleLabel.setStyle(this.colorStyleForAdditionalParameter(scalePpm,
							Klein_Method_Default_Values.ADDITIONAL_PARAMETER_SCALE_YELLOW_THRESHOLD_PPM,
							Klein_Method_Default_Values.ADDITIONAL_PARAMETER_SCALE_RED_THRESHOLD_PPM));
					Klein_Method_Tab_Builder.this.additionalParameterScaleCheckBoxes.get(type).setSelected(scaleEnabled);
				});
			}
		}
		/**
		 * Wird ausgel\u00F6st, wenn der User die neue Checkbox "Additionskonstante berechnen"/"Ma\u00DFstab berechnen"
		 * anklickt:
		 *
		 * GENAU EINE Gruppe dieses Streckentyps -\u003E direkt auf diese schreiben.
		 *
		 * MEHRERE Gruppen -\u003E den gew\u00FCnschten Zustand auf ALLE Gruppen dieses Typs anwenden, dann eine
		 * Hinweis-Messagebox zeigen (Checkbox bleibt danach ganz normal nutzbar, da ab jetzt wieder konsistent). Laeuft
		 * in einem Hintergrund-Thread (DB-Zugriff), Checkbox waehrenddessen gesperrt - analog zum "Werte jetzt
		 * auslesen"-Button.
		 */
		private void applyAdditionalParameterCheckBox(Klein_Method_Threshold_Type distanceType, boolean isScale, CheckBox checkBox) {
			boolean desiredState = checkBox.isSelected();
			checkBox.setDisable(true);

			Thread thread = Klein_Method.busyThread(() -> {
				Klein_Method.getAdjustmentLock().lock();
				try {
					Map<Integer, boolean[]> groupStates = Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().readAdditionalParameterGroupStates(distanceType);

					for (Integer groupId : groupStates.keySet())
						Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().writeAdditionalParameterEnabled(groupId, isScale, desiredState);
					Klein_Method_Tab_Builder.this.kleinMethod.acceptOwnChangesIntoChecksum("Zusatzparameter-Haken");

					String parameterName = isScale ? "Ma\u00DFstab" : "Additionskonstante";
					String typeName = distanceType.getDisplayName().replace(" logPost", "");
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: " + parameterName + " (" + typeName + ") auf "
							+ (desiredState ? "aktiviert" : "deaktiviert") + " gesetzt (" + groupStates.size() + " Gruppe(n)).");

					if (groupStates.size() > 1) {
						String message = String.format(tr("%s f\u00FCr \"%s\" wurde f\u00FCr ALLE %d Gruppen dieses Typs auf \"%s\" gesetzt, da mehrere Gruppen vorhanden sind."),
								tr(parameterName), tr(distanceType.getDisplayName()).replace(" logPost", ""), groupStates.size(), desiredState ? tr("an") : tr("aus"));
						Platform.runLater(() -> {
							Alert alert = new Alert(AlertType.INFORMATION);
							alert.setTitle(tr("Klein_Method - Auf alle Gruppen angewendet"));
							alert.setHeaderText(tr("Mehrere Gruppen dieses Typs gefunden"));
							alert.setContentText(message);
							alert.showAndWait();
						});
					}

					// Deshalb hier ein frisches Hintergrund-F5, BEVOR der Wert neu angezeigt wird.
					Klein_Method_Tab_Builder.this.kleinMethod.runHeadlessAdjustmentSynchronously();

					this.refreshAdditionalParameters();
					Platform.runLater(() -> checkBox.setDisable(false));
				} catch (SQLException e) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Fehler beim Setzen des Zusatzparameters: " + e.getMessage());
					Platform.runLater(() -> {
						checkBox.setSelected(!desiredState); // Aenderung rueckgaengig machen, da unklar ob sie griff
						checkBox.setDisable(false);
					});
				} catch (Exception e) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Netzausgleichung nach Zusatzparameter-Aenderung fehlgeschlagen: " + e.getMessage());
					Platform.runLater(() -> checkBox.setDisable(false));
				} finally {
					Klein_Method.getAdjustmentLock().unlock();
				}
			}, "Klein_Method_Additional_Parameter_Write");
			thread.setDaemon(true);
			thread.start();
		}
		/**
		 * Liefert die Schriftfarbe (CSS-Style) fuer einen Zusatzparameter-Wert je nach seinem BETRAG: normal unter dem
		 * Gelb-Grenzwert, gelb dazwischen, rot ab (inkl.) dem Rot-Grenzwert. "k.A." (Double.NaN) bleibt unveraendert
		 * (normale Farbe) - kein Wert heisst nicht automatisch "unauffaellig 0", sondern einfach "keine Aussage
		 * moeglich".
		 */
		private String colorStyleForAdditionalParameter(double value, double yellowThreshold, double redThreshold) {
			if (Double.isNaN(value))
				return "";
			double absValue = Math.abs(value);
			if (absValue >= redThreshold)
				return "-fx-text-fill: black; -fx-background-color: " + UI_RED + ";" + UI_BADGE_PADDING;
			if (absValue >= yellowThreshold)
				return "-fx-text-fill: black; -fx-background-color: " + UI_YELLOW + ";" + UI_BADGE_PADDING;
			return "";
		}
		/**
		 * Aktualisiert alle Anzeigefelder der Gruppe "Ausgelesene Werte" mit
		 * den aktuellen Werten aus dem Parameter-Objekt. Wird nach jedem
		 * manuellen read() (Diagnose-Button) sowie nach jeder Iteration/
		 * jedem Einzelschritt aufgerufen, damit die Anzeige immer aktuell ist.
		 */
		/**
		 * aktuelle Gruppen-Messunsicherheit einer Komponente (0 = a, 1 = b, 2 = c) als "min \u2013 max Einheit" bzw. ein Wert,
		 * wenn alle Gruppen gleich sind; "k.A." ohne aktive Gruppe dieses Typs. Werte aus dem Hintergrund-Lesezyklus.
		 */
		private String formatGroupSigma(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, int component) {
			double[] range = parameter.getGroupUncertaintyRange(type);
			if (range == null || Double.isNaN(range[2 * component]))
				return "k.A.";
			String unit = component == 0 ? Klein_Method_Default_Values.getSigmaAUnit(type)
					: component == 1 ? Klein_Method_Default_Values.getSigmaBUnit(type) : Klein_Method_Default_Values.getSigmaCUnit(type);
			Klein_Method_Functions functions = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions();
			double min = functions.toWorkingUnit(range[2 * component], unit), max = functions.toWorkingUnit(range[2 * component + 1], unit);
			int decimals = Math.min(parameter.getDecimalPlaces(), 4);
			if (Math.abs(max - min) < 0.5 * Math.pow(10.0, -decimals))
				return Klein_Method_Format.format(min, unit, decimals);
			return Klein_Method_Format.format(min, "", decimals) + " \u2013 " + Klein_Method_Format.format(max, unit, decimals);
		}
		private void updateDiagnosticValueLabels() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			for (Map.Entry<Klein_Method_Threshold_Type, Label> entry : Klein_Method_Tab_Builder.this.diagnosticValueLabels.entrySet())
				entry.getValue().setText(this.formatDiagnosticValue(parameter, entry.getKey()));
			for (Map.Entry<Klein_Method_Threshold_Type, Label[]> entry : Klein_Method_Tab_Builder.this.groupSigmaLabels.entrySet())
				for (int c = 0; c < 3; c++)
					entry.getValue()[c].setText(this.formatGroupSigma(parameter, entry.getKey(), c));
			// dieselben, im Hintergrund-Lesezyklus bereits gecachten Zaehlerstaende (siehe
			// Klein_Method_Database_Handler.readGnssBaselineDeactivationCounts()) nur noch formatieren/anzeigen - KEIN
			// eigener DB-Zugriff hier auf dem JavaFX-UI-Thread. jetzt TextField statt Label, sitzt in Sektion "Steuerung"
			// - siehe deactivatedGnssBaselineCountField.
			if (Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField != null)
				Klein_Method_Tab_Builder.this.deactivatedGnssBaselineCountField.setText(this.formatDeactivatedGnssBaselineCount(parameter));
			// dieselbe Stelle wie oben - Ampel NUR anzeigen/formatieren, NICHT auf dem JavaFX-UI-Thread neu berechnen
			// (das passiert bereits in Klein_Method_Functions.performIndividualCalibrationStep()).
			if (Klein_Method_Tab_Builder.this.oscillationStatusValueLabel != null)
				this.applyOscillationStatus(parameter.getOscillationStatus());
			// siehe updateHardClampStatusLabel() unten.
			this.updateHardClampStatusLabel();
			// siehe updateGnssPercentageRateLabel() unten.
			this.updateGnssPercentageRateLabel();
			// siehe updateLogPostDynamicBandFields() unten.
			this.updateLogPostDynamicBandFields();
			// siehe updateElementsPerIterationLabel() unten.
			this.updateElementsPerIterationLabel();
			// siehe updateCascadeStatusFields() unten.
			this.updateCascadeStatusFields();
		}
		/**
		 * fuellt die beiden Anzeigefelder "Kaskaden-Durchlauf:" und "Bester Max-logPost:" aus dem Parameter (kein eigener
		 * DB-Zugriff).
		 */
		private void updateCascadeStatusFields() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			if (Klein_Method_Tab_Builder.this.cascadePassField != null)
				Klein_Method_Tab_Builder.this.cascadePassField.setText(String.valueOf(parameter.getCascadePassCount() + 1));
			if (Klein_Method_Tab_Builder.this.bestMaxLogPostField != null) {
				double best = parameter.getBestMaxLogPost();
				// Wert im Feld, Zusatz (DL, Iteration) als Text daneben
				Klein_Method_Tab_Builder.this.bestMaxLogPostField.setText(Double.isNaN(best) ? "\u2013" : Klein_Method_Format.format(best, "", parameter.getDecimalPlaces()));
				if (Klein_Method_Tab_Builder.this.bestMaxLogPostInfoLabel != null)
					Klein_Method_Tab_Builder.this.bestMaxLogPostInfoLabel.setText(Double.isNaN(best) ? ""
							: "(DL " + parameter.getBestMaxLogPostPass() + tr(", Iteration ") + parameter.getBestMaxLogPostIteration() + ")");
			}
			if (Klein_Method_Tab_Builder.this.cascadeTargetField != null) {
				double target = parameter.getCascadeTargetLogPost();
				Klein_Method_Tab_Builder.this.cascadeTargetField.setText(Double.isNaN(target) ? "\u2013" : Klein_Method_Format.format(target, "", parameter.getDecimalPlaces()));
				if (Klein_Method_Tab_Builder.this.cascadeTargetInfoLabel != null)
					Klein_Method_Tab_Builder.this.cascadeTargetInfoLabel.setText(Double.isNaN(target) ? ""
							: (parameter.isCascadeTargetReached() ? tr("(erreicht)") : "(ab DL " + (parameter.getCascadePassCount() + 1) + ")"));
			}
		}
		/**
		 * NUR wenn PHASE:DONE erreicht IST UND parameter.isHardClampAppliedThisRun() true liefert, erscheint die feste
		 * Erfolgsmeldung in Gruen/Fett.
		 */
		private void updateHardClampStatusLabel() {
			if (Klein_Method_Tab_Builder.this.hardClampStatusValueLabel == null)
				return;
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			boolean confirmed = parameter.getProcessPhase() == Klein_Method_Process_Phase.DONE
					&& parameter.isHardClampAppliedThisRun();
			if (confirmed) {
				double gnss3dMm = 1000.0 * Klein_Method_Default_Values.INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_GNSS;
				double nivellementMm = 1000.0 * Klein_Method_Default_Values.INDIVIDUAL_UNCERTAINTY_COMPRESSION_FLOOR_NIVELLEMENT;
				Klein_Method_Tab_Builder.this.hardClampStatusValueLabel.setText(tr("GNSS 3D = ") + Klein_Method_Format.format(gnss3dMm, "mm", 1)
						+ " / dH = " + Klein_Method_Format.format(nivellementMm, "mm", 1) + tr(" [ANSCHLAG OK]"));
				Klein_Method_Tab_Builder.this.hardClampStatusValueLabel.setStyle("-fx-text-fill: black; -fx-background-color: " + UI_GREEN + ";" + UI_BADGE_PADDING);
			}
			else {
				Klein_Method_Tab_Builder.this.hardClampStatusValueLabel.setText("k.A.");
				Klein_Method_Tab_Builder.this.hardClampStatusValueLabel.setStyle("-fx-text-fill: black;");
			}
		}
		/**
		 * "k.A." (neutrales Grau), solange in dieser Sitzung noch keine GNSS-Achse den finalen Verarbeitungs-Zyklus
		 * erreicht hat (Klein_Method_Parameter.getTotalGnssCalculations() == 0 - Division durch 0 vermieden). Sobald
		 * Daten vorliegen: die reelle Quote in kraeftigem Blau/Fett, wie beauftragt.
		 */
		private void updateGnssPercentageRateLabel() {
			if (Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel == null)
				return;
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			int total = parameter.getTotalGnssCalculations();
			if (total <= 0) {
				Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel.setText("k.A.");
				Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel.setStyle("-fx-text-fill: black;");
			}
			else {
				int split = parameter.getProzentualSplittedCalculations();
				double quote = (split * 100.0) / total;
				Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel.setText(Klein_Method_Format.format(quote, "", 1) + tr(" % (Aktiv im RAM)"));
				Klein_Method_Tab_Builder.this.gnssPercentageRateValueLabel.setStyle("-fx-text-fill: black;");
			}
		}
		/**
		 * logPost-EtappeMin: liest weiterhin live Klein_Method_Functions.computeCurrentLogPostEtappeMin() (siehe dort
		 * fuer die vollstaendige Herleitung, unveraendert) - dieselbe Formel, die performIndividual CalibrationStep()
		 * intern je Beobachtungstyp verwendet, hier auf den GLOBALEN Max-logPost-Wert angewendet (dieselbe Vereinfachung
		 * wie beim bereits bestehenden "Live-Modus"-Feld, das ebenfalls einen einzelnen globalen Wert statt 4 Werte je
		 * Typ zeigt). Landet bit-genau bei "1.00", sobald das Band den Grenzwert erreicht/unterschreitet (Ausnahme-Regel
		 * in computeLogPostBandLowerBound()) - NICHT bei "2.45": die Ausnahme hebt die Begrenzung dann bewusst ganz auf
		 * (siehe dortiger Methoden-Javadoc), sie klemmt nicht auf den Grenzwert selbst.
		 */
		private void updateLogPostDynamicBandFields() {
			if (Klein_Method_Tab_Builder.this.logPostEtappeMinField == null)
				return;
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			double etappeMin = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().computeCurrentLogPostEtappeMin(parameter);
			Klein_Method_Tab_Builder.this.logPostEtappeMinField.setText(Klein_Method_Format.format(etappeMin, "", 4));
		}
		/**
		 * zeigt Klein_Method_Parameter.getElementsProcessedLastIteration() an (siehe dortiger Feld-kommentar fuer die
		 * vollstaendige Herleitung - Summe der bereits bestehenden, je Beobachtungstyp gefuehrten processedCounts-Map aus
		 * performIndividualCalibrationStep(), aufsummiert in Klein_Method_Iteration_Task.call() nach jedem
		 * recompute()-Aufruf).
		 *
		 * FUNDSTELLEN-HINWEIS ("uebergib diesen Integer-Wert direkt an updateDiagnosticValueLabels()", woertlich aus dem
		 * Auftrag): updateDiagnostic ValueLabels() wird an DREI Stellen aufgerufen (readButton-Handler,
		 * Nachkommastellen-Aenderung, UND - das ist der fuer diesen Auftrag relevante Fall - der
		 * task.messageProperty()-Listener, der bereits VOR diesem Patch bei JEDER einzelnen Iterationsrunde feuert, siehe
		 * Klein_Method_Iteration_Task.updateMessage()-Aufruf) - eine Signaturaenderung zu einem Pflicht-Parameter haette
		 * die anderen beiden, iterationslosen Aufrufstellen ohne sinnvollen Wert zum Uebergeben gebrochen. Stattdessen,
		 * wie bei JEDEM anderen Diagnose-Feld dieser Klasse (updateHardClampStatusLabel(),
		 * updateGnssPercentageRateLabel(), updateLogPostDynamicBandFields() - allesamt parameterlos, lesen live aus
		 * this.kleinMethod.getParameter()): diese Methode liest den Wert selbst, ohne Parameter - "direkt an
		 * updateDiagnosticValueLabels() uebergeben" ist damit im Sinne des Auftrags (die Anzeige aktualisiert sich bei
		 * jeder Iteration) erfuellt, nur architektonisch konsistent mit den bereits bestehenden Schwester-Methoden
		 * umgesetzt statt als Methodenparameter.
		 *
		 * Formatierung mit Tausender-Trennpunkt (deutsches Zahlenformat, z.B. "1.432" fuer 1432, wie im Auftrag als
		 * Beispiel vorgegeben) - Klein_Method_Format ist bewusst NICHT verwendet (reine Dezimalstellen-Formatierung fuer
		 * Fliesskommawerte, kein Tausender-Gruppierungsformat fuer Ganzzahlen, siehe dortiger Klassenkommentar).
		 */
		private void updateElementsPerIterationLabel() {
			if (Klein_Method_Tab_Builder.this.elementsPerIterationField == null)
				return;
			int count = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getElementsProcessedLastIteration();
			Klein_Method_Tab_Builder.this.elementsPerIterationField.setText(
					java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(count));
		}
		/**
		 * Sonst ueber Klein_Method_Format einheitlich formatiert (Nachkomma-stellen gemaess Einstellung, Einheit immer
		 * mit angezeigt).
		 */
		private String formatDiagnosticValue(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type) {
			if (!parameter.isValueAvailable(type))
				return GNSS_EP_TYPES_WITHOUT_MODEL_VALUE.contains(type) ? "k.A." : "k.A.";
			return Klein_Method_Format.format(parameter.getCurrentValue(type), type.getUnit(), parameter.getDecimalPlaces());
		}	}

	// ============================================================================================================
	/** Datenstatus, Koordinatensystem, Export-Systeme, Gitter-Import, Ausgleichungsstufe, Verteilung, Hinweis. */
	private final class Status_Part {

		/**
		 * Ermittelt den Ampel-Status der Messdaten NEU (siehe Klein_Method_Data_Status) und aktualisiert das Label. Liest
		 * dafuer frisch aus der Datenbank - KEIN gecachter/gespeicherter Status, damit auch Aenderungen an den Rohdaten
		 * ausserhalb von Klein_Method sofort korrekt erkannt werden.
		 *
		 * Aufzurufen: bei jedem erfolgreichen Oeffnen des Klein_Method-Tabs (siehe Klein_Method.registerTab()) sowie nach
		 * jeder Iteration/jedem Einzelschritt.
		 */
		/** Aufzurufen bei jedem Tab-Aktivieren. */
		public void refreshCoordinateSystem() {
			try {
				String displayName = Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().readProjectionTypeDisplayName();
				Platform.runLater(() -> Klein_Method_Tab_Builder.this.coordinateSystemLabel.setText(displayName));
			} catch (SQLException e) {
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Koordinatensystem konnte nicht gelesen werden: " + e.getMessage());
			}
		}
		public void refreshDataStatus() {
			Klein_Method_Tab_Builder.this.dataStatusLabel.setText(tr("Messdaten: wird gepr\u00FCft ..."));
			Klein_Method_Tab_Builder.this.dataStatusLabel.setStyle("-fx-background-color: " + UI_GREY + ";"); // grau waehrend der Pruefung

			Thread thread = Klein_Method.busyThread(() -> {
				Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
				try {
					Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().read(parameter);
					Klein_Method_Data_Status status = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().determineDataStatus(parameter);
					Platform.runLater(() -> this.applyDataStatus(status));

					// Werden beide IMMER gemeinsam aus demselben frischen Lesevorgang aktualisiert, damit sie nie mehr
					// auseinanderlaufen koennen.
					double freshMaxLogPost = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().getMaxLogPostValue(parameter);
					double freshMaxEp = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().getMaxEpValue(parameter);
					Platform.runLater(() -> Klein_Method_Tab_Builder.this.updateLiveModeFields(freshMaxLogPost, freshMaxEp));
					// verfuegbare Zielsysteme des Koordinaten-Exports
					org.applied_geodesy.klein_method.Klein_Method.Export_Plan exportPlan = Klein_Method_Tab_Builder.this.kleinMethod.readCoordinateExportPlan();
					Platform.runLater(() -> this.applyCoordinateExportSystems(exportPlan));
				} catch (SQLException e) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Fehler beim Ermitteln des Messdaten-Status: " + e.getMessage());
					Platform.runLater(() -> this.applyDataStatus(Klein_Method_Data_Status.ERROR));
				}
			}, "Klein_Method_Data_Status_Check");
			thread.setDaemon(true);
			thread.start();
		}
		/**
		 * Gitterdatei (*.gsb) oder Zip des Landesamts waehlen - alles Weitere automatisch: pruefen, in den
		 * Projektionsordner legen, mitgelieferte Pruefdaten nachrechnen, Dropdown und offene Karte auffrischen.
		 */
		private void importGridFile() {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("NTv2-Gitterdatei wählen"));
			javafx.stage.FileChooser.ExtensionFilter gridFiles = new javafx.stage.FileChooser.ExtensionFilter(tr("NTv2-Gitter (*.gsb, *.zip)"), "*.gsb", "*.GSB", "*.zip", "*.ZIP");
			chooser.getExtensionFilters().addAll(gridFiles, new javafx.stage.FileChooser.ExtensionFilter(tr("Alle Dateien (*.*)"), "*.*", "*"));
			chooser.setSelectedExtensionFilter(gridFiles);
			java.io.File source = chooser.showOpenDialog(Klein_Method_Tab_Builder.this.gridImportButton.getScene() == null ? null : Klein_Method_Tab_Builder.this.gridImportButton.getScene().getWindow());
			if (source == null)
				return;
			// Namen der Gitterdateien (bei Zip aus dem Inhaltsverzeichnis) - vorhandene erst nach Rueckfrage ersetzen
			List<String> names = new ArrayList<String>();
			boolean zip = source.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
			if (zip) {
				try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(source)) {
					java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = file.entries();
					while (entries.hasMoreElements()) {
						String name = new java.io.File(entries.nextElement().getName()).getName();
						if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".gsb"))
							names.add(name);
					}
				} catch (IOException e) {
					names.clear();
				}
			}
			else
				names.add(source.getName());
			boolean same = false;
			try {
				java.io.File target = new java.io.File(Klein_Method_Functions.Klein_Method_Geo.projectionFolder(), source.getName());
				same = !zip && target.exists() && target.getCanonicalPath().equals(source.getCanonicalPath());
			} catch (IOException e) {
				same = false;
			}
			List<String> existing = new ArrayList<String>();
			for (String name : names)
				if (new java.io.File(Klein_Method_Functions.Klein_Method_Geo.projectionFolder(), name).exists())
					existing.add(name);
			if (!same && !existing.isEmpty()) {
				Alert confirm = new Alert(AlertType.CONFIRMATION, String.format(tr("Im Projektionsordner gibt es schon „%s“. Ersetzen?"), String.join(", ", existing)),
						ButtonType.YES, ButtonType.NO);
				confirm.setTitle(tr("Klein_Method - Gitterdatei importieren"));
				confirm.setHeaderText(null);
				if (!confirm.showAndWait().filter(b -> b == ButtonType.YES).isPresent())
					return;
			}
			final boolean copy = !same;
			Thread thread = Klein_Method.busyThread(() -> {
				StringBuilder message = new StringBuilder();
				AlertType type = AlertType.INFORMATION;
				try {
					org.applied_geodesy.klein_method.Klein_Method.Grid_Import_Result result = Klein_Method_Tab_Builder.this.kleinMethod.importGridFile(source, copy);
					for (Map.Entry<String, List<String>> entry : result.systems.entrySet()) {
						if (message.length() > 0)
							message.append("\n\n");
						message.append(String.format(tr("Gitterdatei „%s“ übernommen. Neu verfügbar:"), entry.getKey())).append("\n").append(String.join("\n", entry.getValue()));
					}
					if (!result.checks.isEmpty())
						message.append("\n\n").append(tr("Automatische Prüfung mit den mitgelieferten Prüfdaten:")).append("\n").append(String.join("\n", result.checks));
					for (String check : result.checks)
						if (check.contains("NICHT bestanden"))
							type = AlertType.WARNING;
				} catch (Exception e) {
					type = AlertType.ERROR;
					message.append(tr("Gitterdatei nicht übernommen: ")).append(e.getMessage());
				}
				org.applied_geodesy.klein_method.Klein_Method.Export_Plan plan = Klein_Method_Tab_Builder.this.kleinMethod.readCoordinateExportPlan();
				final String text = message.toString();
				final AlertType alertType = type;
				Platform.runLater(() -> {
					this.applyCoordinateExportSystems(plan);
					Klein_Method_Map_Window.reloadIfOpen();
					Alert alert = new Alert(alertType);
					alert.setTitle(tr("Klein_Method - Gitterdatei importieren"));
					alert.setHeaderText(null);
					javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text);
					area.setEditable(false);
					area.setWrapText(true);
					area.setPrefRowCount(Math.min(16, 4 + text.split("\n").length));
					area.setPrefColumnCount(70);
					alert.getDialogPane().setContent(area);
					alert.setResizable(true);
					alert.showAndWait();
				});
			}, "Klein_Method_Grid_Import");
			thread.setDaemon(true);
			thread.start();
		}
		/** Textfenster (nur lesen) fuer Ergebnisse von Import und Pruefung. */
		private void showQuasigeoidText(AlertType alertType, String title, String text) {
			Alert alert = new Alert(alertType);
			alert.setTitle(title);
			alert.setHeaderText(null);
			javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text);
			area.setEditable(false);
			area.setWrapText(true);
			area.setPrefRowCount(Math.min(16, 4 + text.split("\n").length));
			area.setPrefColumnCount(70);
			alert.getDialogPane().setContent(area);
			alert.setResizable(true);
			alert.showAndWait();
		}
		/**
		 * Quasigeoidmodell waehlen (Zip des BKG, ASCII Breite/Laenge/N oder ISG) - pruefen, kompakt im Ordner ablegen; ein
		 * Modell gleichen Namens wird erst nach Rueckfrage ersetzt. Das erste Modell wird aktiv.
		 */
		private void importQuasigeoid() {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle(tr("Quasigeoidmodell wählen"));
			javafx.stage.FileChooser.ExtensionFilter files = new javafx.stage.FileChooser.ExtensionFilter(tr("Quasigeoid (*.zip, *.txt, *.isg, *.gri)"), "*.zip", "*.ZIP",
					"*.txt", "*.TXT", "*.isg", "*.ISG", "*.gri", "*.GRI");
			chooser.getExtensionFilters().addAll(files, new javafx.stage.FileChooser.ExtensionFilter(tr("Alle Dateien (*.*)"), "*.*", "*"));
			chooser.setSelectedExtensionFilter(files);
			Button owner = Klein_Method_Tab_Builder.this.quasigeoidImportButton;
			java.io.File source = chooser.showOpenDialog(owner.getScene() == null ? null : owner.getScene().getWindow());
			if (source != null)
				this.runQuasigeoidImport(source, false);
		}
		private void runQuasigeoidImport(java.io.File source, boolean replace) {
			Klein_Method_Tab_Builder.this.quasigeoidImportButton.setDisable(true);
			Thread thread = Klein_Method.busyThread(() -> {
				String text;
				AlertType type = AlertType.INFORMATION;
				String exists = null;
				try {
					Klein_Method_Functions.Klein_Method_Quasigeoid.Import_Result result = Klein_Method_Tab_Builder.this.kleinMethod.importQuasigeoid(source, null, replace);
					String active = Klein_Method_Functions.Klein_Method_Quasigeoid.activeName();
					text = String.format(tr("Quasigeoidmodell „%s“ übernommen."), result.model.title())
							+ (result.model.license.isEmpty() ? "" : "\n" + tr("Lizenz: ") + result.model.license)
							+ "\n" + (result.model.name.equals(active) ? tr("Es ist das aktive Modell.") : tr("Aktiv bleibt: ") + active)
							+ "\n\n" + tr("Automatische Prüfung:") + "\n" + String.join("\n", result.checks)
							+ "\n\n" + tr("Ordner: ") + Klein_Method_Functions.Klein_Method_Quasigeoid.folder().getAbsolutePath();
				} catch (Klein_Method_Functions.Klein_Method_Quasigeoid.Exists_Exception e) {
					exists = e.name;
					text = null;
				} catch (Exception e) {
					type = AlertType.ERROR;
					text = tr("Quasigeoid nicht übernommen: ") + e.getMessage();
				}
				final String message = text, existing = exists;
				final AlertType alertType = type;
				Platform.runLater(() -> {
					Klein_Method_Tab_Builder.this.quasigeoidImportButton.setDisable(false);
					Klein_Method_Tab_Builder.this.refreshNormalHeightCheckBox();
					if (existing != null) {
						Alert confirm = new Alert(AlertType.CONFIRMATION, String.format(tr("Das Quasigeoidmodell „%s“ ist schon vorhanden. Ersetzen?"), existing),
								ButtonType.YES, ButtonType.NO);
						confirm.setTitle(tr("Klein_Method - Quasigeoid importieren"));
						confirm.setHeaderText(null);
						if (confirm.showAndWait().filter(b -> b == ButtonType.YES).isPresent())
							this.runQuasigeoidImport(source, true);
						return;
					}
					this.showQuasigeoidText(alertType, tr("Klein_Method - Quasigeoid importieren"), message);
				});
			}, "Klein_Method_Quasigeoid_Import");
			thread.setDaemon(true);
			thread.start();
		}
		/**
		 * Auswahl "Hoehe:" je nach Projekt und Zielsystem fuellen: ellipsoidisch / Normalhoehe NHN (nur mit aktivem Modell),
		 * sonst nur "wie im Zielsystem" (gesperrt). Die gespeicherte Wahl bleibt erhalten.
		 */
		private void refreshNormalHeightCheckBox() {
			ComboBox<Integer> box = Klein_Method_Tab_Builder.this.exportHeightComboBox;
			if (box == null)
				return;
			org.applied_geodesy.klein_method.Klein_Method.Export_Plan plan = Klein_Method_Tab_Builder.this.lastExportPlan;
			Klein_Method_Functions.Klein_Method_Coordinate_Export.Option option = Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox == null ? null
					: Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.getValue();
			boolean applies = plan != null && option != null && org.applied_geodesy.klein_method.Klein_Method.heightChoiceApplies(plan.source, option.target);
			boolean model = Klein_Method_Functions.Klein_Method_Quasigeoid.activeName() != null;
			Klein_Method_Tab_Builder.this.exportHeightUpdating = true;
			try {
				if (!applies)
					box.getItems().setAll(2);
				else if (!model)
					box.getItems().setAll(0);
				else
					box.getItems().setAll(0, 1);
				int wanted = !applies ? 2 : model && Klein_Method_Tab_Builder.this.kleinMethod.getParameter().isCoordinateExportNormalHeight() ? 1 : 0;
				box.setValue(wanted);
				box.setDisable(box.getItems().size() < 2);
			} finally {
				Klein_Method_Tab_Builder.this.exportHeightUpdating = false;
			}
		}
		/**
		 * Verwaltung der Quasigeoidmodelle: Liste mit Fassung, Gebiet, Lizenz; aktives Modell setzen, loeschen (nach
		 * Rueckfrage), Hoehensysteme des Projekts pruefen.
		 */
		private void manageQuasigeoids() {
			Dialog<ButtonType> dialog = new Dialog<ButtonType>();
			dialog.setTitle(tr("Klein_Method - Quasigeoide verwalten"));
			dialog.setHeaderText(null);
			dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
			javafx.scene.control.ListView<Klein_Method_Functions.Klein_Method_Quasigeoid.Model> list = new javafx.scene.control.ListView<Klein_Method_Functions.Klein_Method_Quasigeoid.Model>();
			list.setPrefSize(620, 220);
			list.setTooltip(new Tooltip(tr("Importierte Quasigeoidmodelle. Das aktive Modell (★) wird für die Höhensystem-Prüfung und den Export mit Normalhöhen verwendet.")));
			Label details = new Label();
			details.setWrapText(true);
			details.setTooltip(new Tooltip(tr("Angaben zum gewählten Modell: Gebiet, Gitterweite, Herkunft, Lizenz.")));
			Runnable reload = () -> {
				String active = Klein_Method_Functions.Klein_Method_Quasigeoid.activeName();
				list.setCellFactory(v -> new javafx.scene.control.ListCell<Klein_Method_Functions.Klein_Method_Quasigeoid.Model>() {
					@Override
					protected void updateItem(Klein_Method_Functions.Klein_Method_Quasigeoid.Model m, boolean empty) {
						super.updateItem(m, empty);
						setText(empty || m == null ? null : (m.name.equals(active) ? "★ " : "   ") + m.title() + "  -  " + tr("importiert ") + m.imported);
					}
				});
				list.getItems().setAll(Klein_Method_Functions.Klein_Method_Quasigeoid.list());
				for (Klein_Method_Functions.Klein_Method_Quasigeoid.Model m : list.getItems())
					if (m.name.equals(active))
						list.getSelectionModel().select(m);
				Klein_Method_Tab_Builder.this.refreshNormalHeightCheckBox();
			};
			list.getSelectionModel().selectedItemProperty().addListener((obs, o, m) -> details.setText(m == null ? tr("Kein Modell gewählt.")
					: String.format(java.util.Locale.ROOT, tr("Gebiet Breite %.3f° bis %.3f°, Länge %.3f° bis %.3f°; Gitter %d x %d, Schritt %.5f° x %.5f°"), m.latMin, m.latMax(),
							m.lonMin, m.lonMax(), m.rows, m.cols, m.dLat, m.dLon)
							+ "\n" + tr("Quelle: ") + m.source + (m.license.isEmpty() ? "" : "\n" + tr("Lizenz: ") + m.license)));
			Runnable[] heightRefresh = new Runnable[1]; // Knoepfe der Hoehenumrechnung (weiter unten angelegt)
			Button activate = new Button(tr("Aktiv setzen"));
			activate.setTooltip(new Tooltip(tr("Gewähltes Modell für Höhensystem-Prüfung und Export mit Normalhöhen verwenden.")));
			activate.setOnAction(e -> {
				Klein_Method_Functions.Klein_Method_Quasigeoid.Model m = list.getSelectionModel().getSelectedItem();
				if (m == null)
					return;
				try {
					Klein_Method_Tab_Builder.this.kleinMethod.setActiveQuasigeoid(m.name);
				} catch (IOException ex) {
					this.showQuasigeoidText(AlertType.ERROR, tr("Klein_Method - Quasigeoide verwalten"), ex.getMessage());
				}
				reload.run();
				if (heightRefresh[0] != null)
					heightRefresh[0].run();
			});
			Button delete = new Button(tr("Löschen …"));
			delete.setTooltip(new Tooltip(tr("Gewähltes Modell nach Rückfrage aus dem Ordner löschen. War es aktiv, ist danach keins aktiv.")));
			delete.setOnAction(e -> {
				Klein_Method_Functions.Klein_Method_Quasigeoid.Model m = list.getSelectionModel().getSelectedItem();
				if (m == null)
					return;
				Alert confirm = new Alert(AlertType.CONFIRMATION, String.format(tr("Quasigeoidmodell „%s“ wirklich löschen?"), m.title()), ButtonType.YES, ButtonType.NO);
				confirm.setTitle(tr("Klein_Method - Quasigeoide verwalten"));
				confirm.setHeaderText(null);
				if (!confirm.showAndWait().filter(b -> b == ButtonType.YES).isPresent())
					return;
				try {
					Klein_Method_Tab_Builder.this.kleinMethod.deleteQuasigeoid(m.name);
				} catch (IOException ex) {
					this.showQuasigeoidText(AlertType.ERROR, tr("Klein_Method - Quasigeoide verwalten"), ex.getMessage());
				}
				reload.run();
			});
			Button importMore = new Button(tr("Importieren …"));
			importMore.setTooltip(Klein_Method_Tab_Builder.this.quasigeoidImportButton.getTooltip());
			importMore.setOnAction(e -> {
				dialog.close();
				this.importQuasigeoid();
			});
			Button check = new Button(tr("Höhensysteme jetzt prüfen"));
			check.setTooltip(new Tooltip(tr("Vergleicht im geöffneten Projekt die Höhen aus Nivellements (ab Höhenfestpunkten) mit denen aus GNSS (ab 3D-Festpunkten). "
					+ "Eine Abweichung in Größe der Höhenanomalie N zeigt NHN- und ellipsoidische Höhen gemischt.")));
			check.setDisable(!Klein_Method_Tab_Builder.this.kleinMethod.isProjectOpen());
			check.setOnAction(e -> {
				check.setDisable(true);
				Thread thread = Klein_Method.busyThread(() -> {
					String text;
					try {
						text = Klein_Method_Tab_Builder.this.kleinMethod.describeHeightCheck(Klein_Method_Tab_Builder.this.kleinMethod.checkHeightSystems(), true);
					} catch (Exception ex) {
						text = tr("Prüfung nicht möglich: ") + ex.getMessage();
					}
					final String message = text;
					Platform.runLater(() -> {
						check.setDisable(false);
						this.showQuasigeoidText(AlertType.INFORMATION, tr("Klein_Method - Höhensysteme"), message);
					});
				}, "Klein_Method_Height_Check");
				thread.setDaemon(true);
				thread.start();
			});
			// Hoehenfestpunkte NHN -> ellipsoidisch (veraendert das Projekt, nur nach Rueckfrage) und Ruecknahme
			Button convert = new Button(tr("Höhenfestpunkte umrechnen …"));
			convert.setTooltip(new Tooltip(tr("VERÄNDERT DAS PROJEKT (nach Rückfrage): rechnet die Höhenfestpunkte (1D, keine Neupunkte) mit dem aktiven Quasigeoid "
					+ "von Normalhöhen (NHN) in ellipsoidische Höhen um: h = H + N. Danach passen Nivellement und GNSS zusammen. "
					+ "Das verwendete N wird je Punkt gesichert - Rücknahme mit „Umrechnung zurücknehmen …“.")));
			Button restore = new Button(tr("Umrechnung zurücknehmen …"));
			restore.setTooltip(new Tooltip(tr("Rechnet die umgerechneten Höhenfestpunkte mit dem damals verwendeten N zurück in Normalhöhen: H = h - N.")));
			Runnable heightButtons = () -> {
				boolean open = Klein_Method_Tab_Builder.this.kleinMethod.isProjectOpen();
				boolean stored = Klein_Method_Tab_Builder.this.kleinMethod.isFixedHeightConversionStored();
				convert.setDisable(!open || stored || Klein_Method_Functions.Klein_Method_Quasigeoid.activeName() == null);
				restore.setDisable(!open || !stored);
			};
			java.util.function.BiConsumer<Boolean, Button> runConversion = (toEllipsoidal, button) -> {
				Alert confirm = new Alert(AlertType.CONFIRMATION, toEllipsoidal
						? tr("Höhenfestpunkte (1D, keine Neupunkte) jetzt mit dem aktiven Quasigeoid in ellipsoidische Höhen umrechnen (h = H + N)? Das verändert das Projekt.")
						: tr("Umgerechnete Höhenfestpunkte zurück in Normalhöhen umrechnen (H = h - N)? Das verändert das Projekt."), ButtonType.YES, ButtonType.NO);
				confirm.setTitle(tr("Klein_Method - Höhensysteme"));
				confirm.setHeaderText(null);
				if (!confirm.showAndWait().filter(b -> b == ButtonType.YES).isPresent())
					return;
				button.setDisable(true);
				Thread thread = Klein_Method.busyThread(() -> {
					String text;
					AlertType type = AlertType.INFORMATION;
					try {
						text = toEllipsoidal ? Klein_Method_Tab_Builder.this.kleinMethod.convertFixedHeightsToEllipsoidal()
								: Klein_Method_Tab_Builder.this.kleinMethod.restoreFixedHeights();
					} catch (Exception ex) {
						type = AlertType.WARNING;
						text = tr("Umrechnung nicht möglich: ") + ex.getMessage();
					}
					final String message = text;
					final AlertType alertType = type;
					Platform.runLater(() -> {
						heightButtons.run();
						this.showQuasigeoidText(alertType, tr("Klein_Method - Höhensysteme"), message);
					});
				}, "Klein_Method_Height_Conversion");
				thread.setDaemon(true);
				thread.start();
			};
			convert.setOnAction(e -> runConversion.accept(true, convert));
			restore.setOnAction(e -> runConversion.accept(false, restore));
			heightButtons.run();
			heightRefresh[0] = heightButtons;
			HBox buttons = new HBox(8, activate, delete, importMore, check);
			HBox heightRow = new HBox(8, convert, restore);
			Label folder = new Label(tr("Ordner: ") + Klein_Method_Functions.Klein_Method_Quasigeoid.folder().getAbsolutePath());
			folder.setWrapText(true);
			folder.setTooltip(new Tooltip(tr("Hier liegen die Modelle kompakt (*.kmqg); die Originaldatei wird nicht mehr gebraucht.")));
			VBox content = new VBox(8, list, details, buttons, heightRow, folder);
			content.setPadding(new Insets(10));
			dialog.getDialogPane().setContent(content);
			dialog.setResizable(true);
			reload.run();
			dialog.showAndWait();
		}
		/**
		 * Dropdown "System" auf die fuer dieses Projekt verfuegbaren Zielsysteme setzen (gespeicherte Wahl bleibt
		 * erhalten).
		 */
		private void applyCoordinateExportSystems(org.applied_geodesy.klein_method.Klein_Method.Export_Plan plan) {
			if (Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox == null || plan == null)
				return;
			Klein_Method_Tab_Builder.this.coordinateExportSystemUpdating = true;
			try {
				Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.getItems().setAll(plan.available);
				Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.setValue(plan.option);
			} finally {
				Klein_Method_Tab_Builder.this.coordinateExportSystemUpdating = false;
			}
			Klein_Method_Tab_Builder.this.lastExportPlan = plan;
			this.refreshNormalHeightCheckBox();
		}
		/**
		 * Setzt das Ampel-Label direkt auf den uebergebenen Status, OHNE selbst
		 * neu aus der Datenbank zu lesen. Fuer den Fall, dass der Aufrufer
		 * (z.B. Klein_Method nach einer fehlgeschlagenen automatischen
		 * Netzausgleichung) den Status bereits kennt bzw. erzwingen will.
		 */
		public void applyDataStatus(Klein_Method_Data_Status status) {
			Klein_Method_Tab_Builder.this.dataStatusLabel.setText(tr(status.getDisplayName()));
			Klein_Method_Tab_Builder.this.dataStatusLabel.setStyle("-fx-background-color: " + status.getColorHex() + ";");
		}
		/**
		 * Zeigt den aktuell erkannten Ausgleichungsschritt (siehe Klein_Method_
		 * Adjustment_Step, Klein_Method.getCurrentAdjustmentStep()) als Text an -
		 * rein informativ, keine Farbe/Ampel-Logik hier (das \u00FCbernimmt bereits
		 * die Messdaten-Ampel daneben). Wird bei jedem Tab-Klick nach erfolgreicher
		 * Ausgleichung frisch gesetzt (siehe Klein_Method.refreshAdjustmentStepLabel()).
		 */
		public void applyAdjustmentStep(Klein_Method_Adjustment_Step step) {
			Klein_Method_Tab_Builder.this.adjustmentStepLabel.setText(tr("Ausgleichungsschritt: ") + tr(step.getDisplayName()));

			// Turbo ist /241 fuer die individuelle Festpunktkalibrierung ("Phase 2") tatsaechlich verdrahtet
			// (parameter.isTurboEnabled(), siehe Klein_Method_Functions.performPointIndividualCalibrationStep()) - und da
			// waehrend DYNAMIC ausschliesslich die Festpunktanalyse laeuft (nie mehr Beobachtungs-Kalibrierung, "Option
			// B"), ist dieser Schalter jetzt durchgehend wirksam, sobald er sichtbar ist. Sichtbar bleibt er unveraendert
			// NUR bei erkannter dynamischer Ausgleichung.
			boolean showTurbo = step == Klein_Method_Adjustment_Step.DYNAMIC;
			Klein_Method_Tab_Builder.this.turboRow.setVisible(showTurbo);
			Klein_Method_Tab_Builder.this.turboRow.setManaged(showTurbo);
			// festes VKS-Band nur in der dynamischen AGL
			Klein_Method_Tab_Builder.this.applyDynamicThresholdFields(step == Klein_Method_Adjustment_Step.DYNAMIC);
			// in der dynamischen AGL eigene Live-Werte; Zeilen der freien/fixen AGL (Elemente / Iter., logPost-EtappeMin,
			// Daempfungs-Formel) ausblenden
			boolean dynamic = step == Klein_Method_Adjustment_Step.DYNAMIC;
			boolean swarmStepChanged = Klein_Method_Tab_Builder.this.visibleStep != step;
			Klein_Method_Tab_Builder.this.visibleStep = step;
			if (swarmStepChanged)
				Klein_Method_Tab_Builder.this.refreshSwarmView();
			Klein_Method_Tab_Builder.this.updateRecommendationView();
			for (Node node : Klein_Method_Tab_Builder.this.freeFixedOnlyNodes) {
				node.setVisible(!dynamic);
				node.setManaged(!dynamic);
			}
			for (Node node : Klein_Method_Tab_Builder.this.freeOnlyNodes) {
				boolean free = step == Klein_Method_Adjustment_Step.FREE;
				node.setVisible(free);
				node.setManaged(free);
			}
			for (Node node : Klein_Method_Tab_Builder.this.dynamicFixedOnlyNodes) {
				boolean dynFix = step == Klein_Method_Adjustment_Step.DYNAMIC || step == Klein_Method_Adjustment_Step.FIXED;
				node.setVisible(dynFix);
				node.setManaged(dynFix);
			}
			if (Klein_Method_Tab_Builder.this.heightFrom1DCheckBox != null) {
				Klein_Method_Tab_Builder.this.ignoreHeightFrom1DEvents = true;
				try {
					Klein_Method_Tab_Builder.this.heightFrom1DCheckBox.setSelected(Klein_Method_Tab_Builder.this.kleinMethod.isHeightFrom1DEnabled());
				} finally {
					Klein_Method_Tab_Builder.this.ignoreHeightFrom1DEvents = false;
				}
			}
			Klein_Method_Tab_Builder.this.applyViewMode();
			if (!Klein_Method_Tab_Builder.this.runLockActive)
				Klein_Method_Tab_Builder.this.updateAutomationAvailability();
			setRowShown(Klein_Method_Tab_Builder.this.liveValuesRow, !dynamic);
			setRowShown(Klein_Method_Tab_Builder.this.dynamicLiveValuesRow, dynamic);
			setRowShown(Klein_Method_Tab_Builder.this.dampingFormulaRowRef, !dynamic);
			setRowShown(Klein_Method_Tab_Builder.this.elementsPerIterationRowRef, !dynamic);
			setRowShown(Klein_Method_Tab_Builder.this.logPostEtappeMinRowRef, !dynamic);
			Klein_Method_Tab_Builder.this.applyDynamicLiveText();

			// die Checkboxen "Additionskonstante berechnen"/"Ma\u00DFstab berechnen" duerfen nur im jeweils fachlich
			// sinnvollen Ausgleichungsschritt verfuegbar sein - Nullpunktabweichung NUR in der freien AGL, Ma\u00DFstab
			// NUR in der dynamischen UND fixen AGL. Ausgeblendet statt nur deaktiviert, analog zu Turbo/Verteilung (bei
			// Nichtanwendbarkeit nimmt die Checkbox keinen Platz mehr ein).
			boolean showOffsetCheckBox = step == Klein_Method_Adjustment_Step.FREE;
			boolean showScaleCheckBox = step == Klein_Method_Adjustment_Step.DYNAMIC || step == Klein_Method_Adjustment_Step.FIXED;
			for (CheckBox offsetCheckBox : Klein_Method_Tab_Builder.this.additionalParameterOffsetCheckBoxes.values()) {
				offsetCheckBox.setVisible(showOffsetCheckBox);
				offsetCheckBox.setManaged(showOffsetCheckBox);
			}
			for (CheckBox scaleCheckBox : Klein_Method_Tab_Builder.this.additionalParameterScaleCheckBoxes.values()) {
				scaleCheckBox.setVisible(showScaleCheckBox);
				scaleCheckBox.setManaged(showScaleCheckBox);
			}
		}
		/**
		 * Sichtbarkeit der Verteilung-Zeile (normal/prozentual), siehe
		 * Klein_Method.refreshDistributionVisibility()/hasActiveGnssBaselines().
		 */
		public void applyDistributionVisibility(boolean visible) {
			Klein_Method_Tab_Builder.this.distributionRow.setVisible(visible);
			Klein_Method_Tab_Builder.this.distributionRow.setManaged(visible);
		}
		/**
		 * setzt den Verteilung-Toggle programmatisch auf den angegebenen Zustand, OHNE den regulaeren onAction-Handler
		 * (und damit ohne Log-Zeile/saveSettings()) auszuloesen.
		 *
		 * Fix: dieselbe Zuweisung wie im onAction-Handler, NUR ohne dessen Log-Zeile/saveSettings().
		 */
		public void applyDistributionSelection(Klein_Method_Distribution_Type type) {
			boolean isPercentage = type == Klein_Method_Distribution_Type.PERCENTAGE;
			Klein_Method_Tab_Builder.this.distributionToggleButton.setSelected(isPercentage);
			Klein_Method_Tab_Builder.this.distributionToggleButton.setText(tr(type.getDisplayName()));
			Klein_Method_Tab_Builder.this.distributionToggleButton.setTooltip(new Tooltip(isPercentage
					? tr("Prozentuale Verteilung der Werte.") : tr("Normale Verteilung der Werte.")));
			Klein_Method_Tab_Builder.this.kleinMethod.getParameter().setDistributionType(type);
			this.updateDampingFormulaAvailability();
		}
		/**
		 * im Modus "normal" wird ausschliesslich die einfache Anpassung (Ur-Formel) gerechnet - das Feld
		 * "Dämpfungs-Formel" wird dann automatisch darauf gestellt und gesperrt. Im Modus "prozentual" ist es wieder
		 * waehlbar, mit der zuletzt dort gewaehlten Formel.
		 */
		private void updateDampingFormulaAvailability() {
			if (Klein_Method_Tab_Builder.this.dampingFormulaComboBox == null)
				return;
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			boolean isNormal = parameter.getUserDistributionType() != Klein_Method_Distribution_Type.PERCENTAGE;
			if (isNormal) {
				Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setDisable(true);
				Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setValue(Klein_Method_Damping_Formula_Type.UR_FORMEL_V100);
				parameter.setDampingFormulaType(Klein_Method_Damping_Formula_Type.UR_FORMEL_V100);
			} else {
				Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setDisable(false);
				Klein_Method_Damping_Formula_Type restore = Klein_Method_Tab_Builder.this.userDampingFormulaChoice != null
						? Klein_Method_Tab_Builder.this.userDampingFormulaChoice : Klein_Method_Damping_Formula_Type.GEDAEMPFTER_ACHS_SPLIT_V333;
				Klein_Method_Tab_Builder.this.dampingFormulaComboBox.setValue(restore);
				parameter.setDampingFormulaType(restore);
			}
		}
		/**
		 * aktiviert/deaktiviert (inkl. optischem Ausgrauen ueber JavaFX-eigenes disabled-Pseudoklassen-Styling) den
		 * Verteilung-Toggle. Aufrufer entscheidet WANN (siehe Klein_Method.refreshDistributionState()).
		 */
		public void applyDistributionEnabled(boolean enabled) {
			Klein_Method_Tab_Builder.this.distributionToggleButton.setDisable(!enabled);
		}
		/**
		 * Zeigt den "was als N\u00E4chstes tun"-Hinweis an, sobald der aktuelle
		 * Ausgleichungsschritt erfolgreich abgeschlossen ist - siehe
		 * Klein_Method.buildNextStepHint()/refreshNextStepHint(). Leerer Text
		 * blendet den Hinweis wieder aus (nimmt dann keinen Platz mehr ein).
		 */
		public void applyNextStepHint(String hintText) {
			boolean hasHint = hintText != null && !hintText.isEmpty();
			Klein_Method_Tab_Builder.this.nextStepHintLabel.setText(hasHint ? hintText : "");
			Klein_Method_Tab_Builder.this.nextStepHintLabel.setManaged(hasHint);
			Klein_Method_Tab_Builder.this.nextStepHintLabel.setVisible(hasHint);
		}	}

	// ============================================================================================================
	/**
	 * Ladekreis im Stil von Windows 11: unvollstaendiger Kreis mit runden Enden, der sich dreht und dabei laenger und
	 * kuerzer wird. Animation nur, solange er sichtbar ist; Klicks gehen durch ihn hindurch.
	 */
	public static final class Busy_Ring extends Region {
		private final javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc();
		private final javafx.animation.Timeline spin;

		public Busy_Ring(double size) {
			double stroke = Math.max(2.0, size / 9.0);
			this.arc.setType(javafx.scene.shape.ArcType.OPEN);
			this.arc.setFill(null);
			this.arc.setStroke(Color.web("#0067C0"));
			this.arc.setStrokeWidth(stroke);
			this.arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
			this.arc.setRadiusX((size - stroke) / 2.0);
			this.arc.setRadiusY((size - stroke) / 2.0);
			this.arc.setCenterX(size / 2.0);
			this.arc.setCenterY(size / 2.0);
			this.getChildren().add(this.arc);
			this.setPrefSize(size, size);
			this.setMinSize(size, size);
			this.setMaxSize(size, size);
			this.setMouseTransparent(true);
			javafx.animation.Interpolator ease = javafx.animation.Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0);
			this.spin = new javafx.animation.Timeline(
					new javafx.animation.KeyFrame(javafx.util.Duration.ZERO,
							new javafx.animation.KeyValue(this.arc.startAngleProperty(), 90.0),
							new javafx.animation.KeyValue(this.arc.lengthProperty(), -10.0)),
					new javafx.animation.KeyFrame(javafx.util.Duration.millis(1000),
							new javafx.animation.KeyValue(this.arc.startAngleProperty(), -180.0, ease),
							new javafx.animation.KeyValue(this.arc.lengthProperty(), -270.0, ease)),
					new javafx.animation.KeyFrame(javafx.util.Duration.millis(2000),
							new javafx.animation.KeyValue(this.arc.startAngleProperty(), -630.0, ease),
							new javafx.animation.KeyValue(this.arc.lengthProperty(), -10.0, ease)));
			this.spin.setCycleCount(javafx.animation.Animation.INDEFINITE);
			this.visibleProperty().addListener((obs, was, now) -> this.updateAnimation());
			this.sceneProperty().addListener((obs, was, now) -> this.updateAnimation());
		}

		private void updateAnimation() {
			if (this.isVisible() && this.getScene() != null)
				this.spin.play();
			else
				this.spin.stop();
		}
	}

	/**
	 * Steuerung des Ladekreises: sichtbar, solange Hintergrundarbeit laeuft (Klein_Method.busyCountProperty() > 0),
	 * die Automatik NICHT laeuft und kein Fenster auf eine Antwort wartet - erst nach 300 ms (kein Flackern), aus sofort.
	 * Zwei Ringe: im Reiterkopf "Klein_Method" (im ganzen JAG3D-Fenster sichtbar) und vor der Statuszeile.
	 */
	private final class Busy_Indicator {
		private final long showDelayMs = 300L;
		private final Busy_Ring statusRing = new Busy_Ring(22.0);
		private final Busy_Ring tabRing = new Busy_Ring(14.0);
		private final Label statusRingText = new Label();
		private final HBox statusBox;
		private final javafx.animation.Timeline check;
		private long wantedSince = -1L;

		Busy_Indicator() {
			this.statusRingText.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
			this.statusRingText.textProperty().bind(Klein_Method.busyTextProperty());
			this.statusBox = new HBox(8, this.statusRing, this.statusRingText);
			this.statusBox.setAlignment(Pos.CENTER_LEFT);
			Tooltip tip = new Tooltip();
			tip.textProperty().bind(javafx.beans.binding.Bindings.concat(tr("Klein_Method arbeitet im Hintergrund: "), Klein_Method.busyTextProperty(),
					tr("\nDer Ladekreis verschwindet von selbst, sobald die Arbeit fertig ist. Während der Automatik und solange ein Fenster auf deine Antwort wartet, ist er ausgeblendet.")));
			Tooltip.install(this.statusBox, tip);
			Tooltip.install(this.tabRing, tip);
			this.setShown(false);
			this.check = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(100), e -> this.evaluate()));
			this.check.setCycleCount(javafx.animation.Animation.INDEFINITE);
			Klein_Method.busyCountProperty().addListener((obs, was, now) -> {
				if (now.intValue() > 0) {
					if (this.check.getStatus() != javafx.animation.Animation.Status.RUNNING)
						this.check.play();
				} else {
					this.check.stop();
					this.wantedSince = -1L;
					this.setShown(false);
				}
			});
		}

		HBox statusBox() {
			return this.statusBox;
		}

		private void evaluate() {
			boolean wanted = Klein_Method.busyCountProperty().get() > 0 && !Klein_Method_Tab_Builder.this.kleinMethod.isAutomaticTaskRunning() && !modalWindowOpen();
			long now = System.currentTimeMillis();
			if (!wanted) {
				this.wantedSince = -1L;
				this.setShown(false);
				return;
			}
			if (this.wantedSince < 0L)
				this.wantedSince = now;
			if (now - this.wantedSince >= this.showDelayMs)
				this.setShown(true);
		}

		/** wartet ein Fenster (Rueckfrage, Meldung) auf eine Antwort? */
		private boolean modalWindowOpen() {
			for (javafx.stage.Window window : javafx.stage.Window.getWindows())
				if (window.isShowing() && window instanceof javafx.stage.Stage && ((javafx.stage.Stage) window).getModality() != javafx.stage.Modality.NONE)
					return true;
			return false;
		}

		private void setShown(boolean show) {
			this.statusBox.setVisible(show);
			this.statusBox.setManaged(show);
			this.statusRing.setVisible(show);
			this.tabRing.setVisible(show);
			javafx.scene.control.Tab tab = Klein_Method_Tab_Builder.this.kleinMethod.getKleinMethodTab();
			if (tab != null) {
				if (show && tab.getGraphic() == null)
					tab.setGraphic(this.tabRing);
				else if (!show && tab.getGraphic() == this.tabRing)
					tab.setGraphic(null);
			}
		}
	}

	// ============================================================================================================
	/** Bereich Steuerung: Start/Stop, Einzelschritt, Sperren, Fortschritt. */
	private final class Control_Part {

		private VBox createControlSection() {
			Klein_Method_Tab_Builder.this.status = new Label(tr("Bereit."));
			Klein_Method_Tab_Builder.this.status.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
			// lange Hinweise (z.B. vom Einzelschritt) nicht ueber die ganze Breite, sondern mit automatischem
			// Zeilenumbruch. Breite = Fortschrittsbalken (250 px) + Reserve; die Hoehe waechst mit dem Text (min. =
			// bevorzugte Hoehe).
			Klein_Method_Tab_Builder.this.status.setWrapText(true);
			Klein_Method_Tab_Builder.this.status.setMaxWidth(STATUS_LABEL_MAX_WIDTH);
			Klein_Method_Tab_Builder.this.status.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

			Klein_Method_Tab_Builder.this.progressBar = new ProgressBar(0.0);
			Klein_Method_Tab_Builder.this.progressBar.setPrefWidth(250);
			Klein_Method_Tab_Builder.this.progressBar.setPrefHeight(22);
			Klein_Method_Tab_Builder.this.progressBar.setTooltip(new Tooltip(tr("Fortschritt der aktuellen Automatik: rot = Start, gr\u00FCn = Grenzwert erreicht.\n"
					+ "Basiert auf dem Max-Wert der 3 logPost-Werte.")));

			Klein_Method_Tab_Builder.this.progressBarOverlayLabel = new Label(tr("Iteration: ") + Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getIterationCount());
			Klein_Method_Tab_Builder.this.progressBarOverlayLabel.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
			Klein_Method_Tab_Builder.this.progressBarOverlayLabel.setTextFill(Color.BLACK);
			Klein_Method_Tab_Builder.this.progressBarOverlayLabel.setPadding(new Insets(2, 4, 2, 4));

			this.updateProgressBarColor(0.0); // Start: rot (faerbt Balken + Label-Hintergrund)

			StackPane progressStack = new StackPane(Klein_Method_Tab_Builder.this.progressBar, Klein_Method_Tab_Builder.this.progressBarOverlayLabel);
			progressStack.setPrefWidth(250);

			Klein_Method_Tab_Builder.this.startButton = new Button(tr("Klein_Method starten"));
			Klein_Method_Tab_Builder.this.startButton.setTooltip(new Tooltip(tr("Startet die Automatik (Endlosschleife: lesen -> bewerten/neuberechnen -> schreiben -> Grenzwert pr\u00FCfen).")));
			Klein_Method_Tab_Builder.this.stopButton = new Button(tr("Stop"));
			Klein_Method_Tab_Builder.this.stopButton.setTooltip(new Tooltip(tr("Bricht die laufende Automatik ab.")));
			Klein_Method_Tab_Builder.this.stopButton.setDisable(true);
			Klein_Method_Tab_Builder.this.singleStepButton = new Button(tr("Einzelschritt"));
			Klein_Method_Tab_Builder.this.singleStepButton.setTooltip(new Tooltip(tr("F\u00FChrt genau EINEN Durchlauf aus (lesen -> bewerten/neuberechnen -> schreiben), keine Automatik.")));

			Klein_Method_Tab_Builder.this.resetCeilingLockButton = new Button(tr("Sperre aufheben"));
			Klein_Method_Tab_Builder.this.resetCeilingLockButton.setTooltip(new Tooltip(tr("Hebt eine aktive Sperre auf:\n"
					+ "\u2022 Obergrenze der individuellen Messunsicherheit \u00FCberschritten - erst klicken, wenn die Ursache gepr\u00FCft/bereinigt wurde.\n"
					+ "\u2022 Automatik in dieser Ausgleichungsstufe bereits fertig - gibt \u201EKlein_Method starten\u201C wieder frei "
					+ "(z.B. f\u00FCr einen Vergleichslauf in derselben Stufe).")));
			Klein_Method_Tab_Builder.this.resetCeilingLockButton.setOnAction(event -> {
				Klein_Method_Parameter lockParameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
				if (lockParameter.isIndividualUncertaintyCeilingExceeded()) {
					lockParameter.resetIndividualUncertaintyCeilingExceeded();
					this.updateAutomationAvailability();
					Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
					Klein_Method_Tab_Builder.this.status.setText(tr("Sperre aufgehoben."));
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Obergrenzen-Sperre manuell aufgehoben.");
				}
				else if (lockParameter.isAutomaticRunCompleted()) {
					// Notfreigabe nach fertiger Automatik.
					Klein_Method_Tab_Builder.this.kleinMethod.clearAutomaticRunCompleted();
					this.updateAutomationAvailability();
					Klein_Method_Tab_Builder.this.status.setText(tr("Sperre aufgehoben: Automatik kann wieder gestartet werden."));
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Start-Sperre nach fertiger Automatik manuell aufgehoben.");
				}
			});

			this.wireStartStopButtons();
			this.wireSingleStepButton();

			Klein_Method_Tab_Builder.this.grossErrorCleanupButton = new Button(tr("Grobe Fehler bereinigen"));
			Klein_Method_Tab_Builder.this.grossErrorCleanupButton.setTooltip(new Tooltip(tr("Nur aktiv, wenn nach F5 grobe Fehler vermutet werden: eine Beobachtung weicht linear um mehr als\n"
					+ "die \u201EGrenze grober Fehler (m)\u201C ab (Standard 0,10 m) - dann ist \u201EKlein_Method starten\u201C gesperrt, bis bereinigt ist.\n"
					+ "Kein logPost oder anderer statistischer Wert, nur die lineare Abweichung in Metern.\n\n"
					+ "Ablauf: die Beobachtung mit der gr\u00F6\u00DFten linearen Abweichung wird vor\u00FCbergehend deaktiviert und neu gerechnet, dann die n\u00E4chste -\n"
					+ "bis keine mehr \u00FCber der Grenze liegt (schrittweise, weil ein grober Fehler auch gesunde Nachbarn auff\u00E4llig macht).\n"
					+ "Danach entscheidest du je Beobachtung: grober Fehler (bleibt f\u00FCr Phase 1 au\u00DFen vor) oder kein grober Fehler (wieder aufnehmen).\n"
					+ "Vor Phase 2 fragt Klein_Method nach, ob die groben Fehler wieder aufgenommen oder deaktiviert gelassen werden.\n"
					+ "Nur freie und fixe Ausgleichung. Auch in der Karte verf\u00FCgbar.")));
			Klein_Method_Tab_Builder.this.grossErrorCleanupButton.setStyle("-fx-font-weight: bold;");
			Klein_Method_Tab_Builder.this.grossErrorCleanupButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.startGrossErrorCleanup());
			Klein_Method_Tab_Builder.this.grossErrorLinearLimitField = Klein_Method_Tab_Builder.this.createGrossErrorLinearLimitField(Klein_Method_Tab_Builder.this.kleinMethod.getParameter());
			Label grossErrorLinearLabel = new Label(tr("Grenze grober Fehler (m):"));
			grossErrorLinearLabel.setTooltip(Klein_Method_Tab_Builder.this.grossErrorLinearLimitField.getTooltip());
			Button statisticalTestButton = new Button(tr("Statistischer Test …"));
			statisticalTestButton.setTooltip(new Tooltip(tr("Separate Funktion, nicht Teil der Automatik: testet kleine grobe Fehler (unter der Grenze in Metern) mit dem "
					+ "Stand nach der Gruppenkalibrierung (Ausgangsgenauigkeiten), den die Automatik dafür still merkt (JAG3D-Test: Signifikanz, geschätzter Fehler, "
					+ "kleinster aufdeckbarer Fehler). Danach wird der aktuelle Stand exakt wiederhergestellt.\n"
					+ "Der Bericht empfiehlt „Schließen“, „Gruppentests …“ oder „Statistisch auffällige bereinigen …“.")));
			statisticalTestButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.startStatisticalTest());
			HBox grossErrorRow = new HBox(10, Klein_Method_Tab_Builder.this.grossErrorCleanupButton, grossErrorLinearLabel, Klein_Method_Tab_Builder.this.grossErrorLinearLimitField,
					statisticalTestButton);
			grossErrorRow.setAlignment(Pos.CENTER_LEFT);
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(grossErrorRow);
			Klein_Method_Tab_Builder.this.kleinMethod.grossErrorsSuspectedProperty().addListener((obs, o, n) -> this.updateAutomationAvailability());
			Klein_Method_Tab_Builder.this.kleinMethod.grossErrorCleanupRunningProperty().addListener((obs, o, n) -> this.updateAutomationAvailability());

			Klein_Method_Tab_Builder.this.automaticButtonsBox = new HBox(10, Klein_Method_Tab_Builder.this.startButton, Klein_Method_Tab_Builder.this.stopButton);
			Klein_Method_Tab_Builder.this.manualButtonsBox = new HBox(10, Klein_Method_Tab_Builder.this.singleStepButton);
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(Klein_Method_Tab_Builder.this.manualButtonsBox);
			// Einzelschritt ist IMMER aktiv, unabhaengig von der Bewertungsmethode - siehe updateAutomationAvailability()
			// weiter unten, die den tatsaechlichen Zustand (nur die Sperre kann ihn deaktivieren) setzt.

			Klein_Method_Tab_Builder.this.exportCoordinatesButton = new Button(tr("Koordinaten exportieren"));
			Klein_Method_Tab_Builder.this.exportCoordinatesButton.setTooltip(new Tooltip(tr("Exportiert die Ergebniskoordinaten (Tab \u201EErgebnis\u201C) ALLER aktiven Projektpunkte "
					+ "im rechts gew\u00E4hlten Format - nur m\u00F6glich, wenn gerade g\u00FCltige Ausgleichungsergebnisse vorliegen. Speicherort wie das Protokoll.")));
			Klein_Method_Tab_Builder.this.exportCoordinatesButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.exportCoordinates());

			// Export-Format-Dropdown: bewusst als eigenes Enum (Klein_Method_Coordinate_Export_Format), damit spaeter
			// einfach weitere Formate ergaenzt werden koennen, ohne die Auswahl-Logik hier anfassen zu muessen - neue
			// Werte tauchen automatisch im Dropdown auf.
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox = new ComboBox<Klein_Method_Coordinate_Export_Format>();
			Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox.getItems().addAll(Klein_Method_Coordinate_Export_Format.values());
			Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox.setValue(parameter.getCoordinateExportFormat());
			Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox.setConverter(new StringConverter<Klein_Method_Coordinate_Export_Format>() {
				@Override
				public String toString(Klein_Method_Coordinate_Export_Format format) {
					return format == null ? "" : tr(format.getDisplayName());
				}
				@Override
				public Klein_Method_Coordinate_Export_Format fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox.setTooltip(new Tooltip(tr("Format f\u00FCr den Koordinaten-Export. Weitere Formate folgen sp\u00E4ter.")));
			Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox.valueProperty().addListener((obs, oldFormat, newFormat) -> {
				if (newFormat == null)
					return;
				parameter.setCoordinateExportFormat(newFormat);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Koordinaten-Export-Format auf '" + newFormat.getDisplayName() + "' gesetzt.");
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});

			// Koordinatensystem der Exportdatei - nur Systeme, die sich aus dem Projektsystem mit eindeutigen Formeln
			// umformen lassen (keine Datumstransformation); die Liste wird beim Pruefen des Tabs aufgefrischt.
			Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox = new ComboBox<Klein_Method_Functions.Klein_Method_Coordinate_Export.Option>();
			Klein_Method_Functions.Klein_Method_Coordinate_Export.Option projectOption = new Klein_Method_Functions.Klein_Method_Coordinate_Export.Option(Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.PROJECT, null);
			Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.getItems().add(projectOption);
			Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.setValue(projectOption);
			Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.setConverter(new StringConverter<Klein_Method_Functions.Klein_Method_Coordinate_Export.Option>() {
				@Override
				public String toString(Klein_Method_Functions.Klein_Method_Coordinate_Export.Option option) {
					if (option == null)
						return "";
					// feste Ziele uebersetzt; Definitionen aus dem Projektionsordner mit Dateinamen
					return option.target == Klein_Method_Functions.Klein_Method_Coordinate_Export.Target.DEFINITION
							? option.definition.name + (!option.definition.hasGrid() ? ""
									: option.definition.name.contains(option.definition.gridName()) ? tr(" - Transformation NTv2")
											: tr(" - Transformation NTv2 (") + option.definition.gridName() + ")")
							: tr(option.target.displayName);
				}
				@Override
				public Klein_Method_Functions.Klein_Method_Coordinate_Export.Option fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.setTooltip(new Tooltip(tr("Koordinatensystem der Exportdatei. Angeboten werden nur Systeme, die sich aus dem "
					+ "Projektsystem mit eindeutigen Formeln umformen lassen (gleiches Datum ETRS89/WGS84, GRS80) - keine Datumstransformation, "
					+ "kein Geoidmodell.\nLokal (ENh) mit P0: geozentrisch, B/L/h und UTM 32/33 mit ellipsoidischer Höhe.\n"
					+ "Geozentrisch: B/L/h, UTM 32/33 und lokal (ENh) um P0.\nUTM mit Zonenkennung: B/L und die andere Zone, Höhe unverändert.\n"
					+ "Gauß-Krüger mit Streifenkennung: wie UTM, wenn im Projektionsordner eine NTv2-Gitterdatei für das Gebiet liegt, "
					+ "sonst nur das Projektsystem.\n"
					+ "Jede Gitterdatei (*.gsb) im Projektionsordner stellt ihre Systeme automatisch bereit (geographisch und Gauß-Krüger-Streifen, "
					+ "als „Transformation NTv2“ gekennzeichnet); dazu kommen eindeutige Definitionen aus dem Ordner (reine Umformungen ETRS89). "
					+ "7-Parameter-Sätze werden nicht angeboten.")));
			Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.valueProperty().addListener((obs, oldTarget, newTarget) -> {
				if (newTarget == null || Klein_Method_Tab_Builder.this.coordinateExportSystemUpdating)
					return;
				parameter.setCoordinateExportTarget(newTarget.key());
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Koordinaten-Export-System auf '" + newTarget.displayName() + "' gesetzt.");
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				Klein_Method_Tab_Builder.this.refreshNormalHeightCheckBox();
			});

			// Format unter den Button (schmalere linke Spalte)
			HBox exportFormatRow = new HBox(10, new Label(tr("Format:")), Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox);
			Label exportSystemLabel = new Label(tr("System:"));
			exportSystemLabel.setTooltip(Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox.getTooltip());
			HBox exportSystemRow = new HBox(10, exportSystemLabel, Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox);
			// Gitterdatei importieren - wird in den Projektionsordner kopiert und dort im Original gelesen; jede
			// Gitterdatei im Ordner stellt ihre Koordinatensysteme automatisch bereit (nichts davon im Quellcode)
			Klein_Method_Tab_Builder.this.gridImportButton = new Button(tr("Gitterdatei importieren …"));
			Klein_Method_Tab_Builder.this.gridImportButton.setTooltip(new Tooltip(tr("NTv2-Gitterdatei (*.gsb) oder das Zip des Landesvermessungsamts wählen - der Rest geschieht "
					+ "automatisch: Datei prüfen, in den Projektionsordner legen (dort wird sie im Original gelesen), mitgelieferte Prüfdaten nachrechnen, "
					+ "Dropdown „System:“ und offene Karte auffrischen. Danach stehen die Koordinatensysteme ihres Startsystems bereit: geographisch (B/L) "
					+ "und - bei Bessel/Krassowski - die Gauß-Krüger-Streifen über dem Gittergebiet. Weitere Einstellungen sind nicht nötig.\n"
					+ "Voraussetzung: Zielsystem des Gitters ETRS89 (GRS80). Ordner: ") + Klein_Method_Functions.Klein_Method_Geo.projectionFolder().getAbsolutePath()));
			Klein_Method_Tab_Builder.this.gridImportButton.setOnAction(event -> Klein_Method_Tab_Builder.this.importGridFile());
			// Quasigeoid (Hoehenanomalie N) - wie die Gitterdateien importiert und verwaltet, unabhaengig vom Quellcode
			Klein_Method_Tab_Builder.this.quasigeoidImportButton = new Button(tr("Quasigeoid importieren …"));
			Klein_Method_Tab_Builder.this.quasigeoidImportButton.setTooltip(new Tooltip(tr("Quasigeoidmodell wählen: das Zip des BKG (z. B. GCG2016), eine ASCII-Datei mit "
					+ "Breite, Länge, N je Zeile oder eine ISG-Datei. Es wird geprüft und kompakt abgelegt; das erste Modell wird aktiv. "
					+ "Damit prüft Klein_Method die Höhensysteme (NHN gegen ellipsoidisch) und kann Normalhöhen H = h - N exportieren.\nOrdner: ")
					+ Klein_Method_Functions.Klein_Method_Quasigeoid.folder().getAbsolutePath()));
			Klein_Method_Tab_Builder.this.quasigeoidImportButton.setOnAction(event -> Klein_Method_Tab_Builder.this.importQuasigeoid());
			Klein_Method_Tab_Builder.this.quasigeoidManageButton = new Button(tr("Quasigeoide verwalten …"));
			Klein_Method_Tab_Builder.this.quasigeoidManageButton.setTooltip(new Tooltip(tr("Liste der importierten Quasigeoidmodelle mit Fassung und Lizenz: aktives Modell setzen, "
					+ "Modelle löschen, Höhensysteme des Projekts prüfen.")));
			Klein_Method_Tab_Builder.this.quasigeoidManageButton.setOnAction(event -> Klein_Method_Tab_Builder.this.manageQuasigeoids());
			// Hoehe der Exportdatei - Auswahl wie beim Koordinatensystem
			Klein_Method_Tab_Builder.this.exportHeightComboBox = new ComboBox<Integer>();
			Klein_Method_Tab_Builder.this.exportHeightComboBox.setConverter(new StringConverter<Integer>() {
				@Override
				public String toString(Integer choice) {
					if (choice == null)
						return "";
					if (choice == 1) {
						String active = Klein_Method_Functions.Klein_Method_Quasigeoid.activeName();
						return String.format(tr("Normalhöhe NHN (H = h - N, %s)"), active == null ? "-" : active);
					}
					return choice == 0 ? tr("ellipsoidisch (h)") : tr("wie im Zielsystem (keine Umrechnung)");
				}
				@Override
				public Integer fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			Klein_Method_Tab_Builder.this.exportHeightComboBox.setTooltip(new Tooltip(tr("Höhe der Exportdatei.\nellipsoidisch (h): GRS80-Höhe aus der Ausgleichung.\n"
					+ "Normalhöhe NHN: H = h - N mit dem aktiven Quasigeoidmodell (nur wählbar, wenn eines importiert und aktiv ist); Dateiname mit „_NH“, "
					+ "Modell und Lizenz im Dateikopf, Punkte außerhalb des Modells: Höhe k.A.\n"
					+ "Wählbar für geographisch, UTM und Definitionen, wenn das Projekt lokal um P0 oder geozentrisch ist. Sonst schreibt der Export die Höhe "
					+ "des Zielsystems (z. B. Projekthöhe unverändert, geozentrisch, lokal Oben).")));
			Klein_Method_Tab_Builder.this.exportHeightComboBox.valueProperty().addListener((obs, o, n) -> {
				if (n == null || n == 2 || Klein_Method_Tab_Builder.this.exportHeightUpdating)
					return;
				parameter.setCoordinateExportNormalHeight(n == 1);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Koordinaten-Export Hoehe " + (n == 1 ? "Normalhoehe NHN" : "ellipsoidisch") + ".");
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			Label exportHeightLabel = new Label(tr("Höhe:"));
			exportHeightLabel.setTooltip(Klein_Method_Tab_Builder.this.exportHeightComboBox.getTooltip());
			HBox exportHeightRow = new HBox(10, exportHeightLabel, Klein_Method_Tab_Builder.this.exportHeightComboBox);
			Klein_Method_Tab_Builder.this.refreshNormalHeightCheckBox();
			HBox quasigeoidRow = new HBox(8, Klein_Method_Tab_Builder.this.quasigeoidImportButton, Klein_Method_Tab_Builder.this.quasigeoidManageButton);
			VBox exportCoordinatesRow = new VBox(6, Klein_Method_Tab_Builder.this.exportCoordinatesButton, exportFormatRow, exportSystemRow,
					exportHeightRow, Klein_Method_Tab_Builder.this.gridImportButton, quasigeoidRow);
			Klein_Method_Tab_Builder.this.markExpertOnly(Klein_Method_Tab_Builder.this.exportCoordinatesButton, exportFormatRow, exportSystemRow, Klein_Method_Tab_Builder.this.gridImportButton,
					exportHeightRow, quasigeoidRow, Klein_Method_Tab_Builder.this.manualButtonsBox);
			// Kalibrierungsnachweis auf Anforderung (freie/fixe Ausgleichung)
			Button calibrationProofButton = new Button(tr("Kalibrierungsnachweis erstellen"));
			calibrationProofButton.setTooltip(new Tooltip(tr("Schreibt den Kalibrierungsnachweis mit dem aktuellen Stand als Textdatei in den Protokollordner:\n"
					+ "je Beobachtungstyp, Gruppe und Komponente Start- und Endwert, VKS, Band, Redundanz, Anzahl und Status,\n"
					+ "dazu robuste VKS, Plausibilit\u00E4t gegen Datenblattwerte und Modellpr\u00FCfung a/c.\n"
					+ "Nach jedem Automatik-Lauf der freien/fixen Ausgleichung wird er automatisch geschrieben (dann mit Startwerten).")));
			calibrationProofButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.createCalibrationProofOnDemand());
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(calibrationProofButton);
			exportCoordinatesRow.getChildren().add(calibrationProofButton);
			exportCoordinatesRow.getChildren().add(Klein_Method_Tab_Builder.this.buildApproximationBox());

			VBox recommendationSection = Klein_Method_Tab_Builder.this.buildRecommendationBox();

			// eigenes Kartenfenster, reine Anzeige
			Button mapButton = new Button(tr("Karte (OpenStreetMap)"));
			mapButton.setTooltip(new Tooltip(tr("\u00D6ffnet die Karte in einem eigenen Fenster: OpenStreetMap mit dem Netz dar\u00FCber.\n"
					+ "Die Darstellung (Punkte, Beobachtungen, Ellipsen, Pfeile) wird 1:1 aus dem JAG3D-Netzplot \u00FCbernommen und dort gesteuert.\n"
					+ "UTM/Gau\u00DF-Kr\u00FCger werden automatisch umgerechnet, lokale Netze per Klick eingepasst.\n"
					+ "Nur Anzeige - die Ausgleichung bleibt unber\u00FChrt. Ben\u00F6tigt eine Internetverbindung f\u00FCr die Kartenkacheln.")));
			mapButton.setOnAction(event -> Klein_Method_Map_Window.open(Klein_Method_Tab_Builder.this.kleinMethod));
			// Koordinatenumrechnung WGS84 <-> lokal (auch ueber das JAG3D-Menue "Module")
			Button conversionButton = new Button(tr("Koordinatenumrechnung WGS84 \u2194 lokal"));
			conversionButton.setTooltip(new Tooltip(tr("\u00D6ffnet das Fenster Koordinatenumrechnung: Dateien vorab umrechnen oder das ge\u00F6ffnete Projekt\n"
					+ "in das lokale System (Ost/Nord/Oben um P0) umrechnen. Auch \u00FCber das JAG3D-Men\u00FC \u201EModule\u201C erreichbar.")));
			conversionButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.openCoordinateConversion());

			Klein_Method_Tab_Builder.this.busyIndicator = new Busy_Indicator();
			VBox controlSection = new VBox(10, Klein_Method_Tab_Builder.this.status, Klein_Method_Tab_Builder.this.busyIndicator.statusBox(), progressStack, grossErrorRow, Klein_Method_Tab_Builder.this.automaticButtonsBox, Klein_Method_Tab_Builder.this.manualButtonsBox, Klein_Method_Tab_Builder.this.resetCeilingLockButton,
					exportCoordinatesRow, recommendationSection, mapButton, conversionButton);
			controlSection.setPadding(new Insets(10, 0, 0, 0));
			this.updateAutomationAvailability(); // Anfangszustand (Sperre normalerweise inaktiv)
			Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
			return controlSection;
		}
		/**
		 * Prueft die Obergrenzen-Sperre (Klein_Method_Parameter.isIndividualUncertaintyCeilingExceeded()) und setzt den
		 * Zustand von Start-/Einzelschritt-/"Sperre aufheben"-Button entsprechend. Ist die Sperre aktiv, bleiben Start
		 * UND Einzelschritt deaktiviert - erst nach manuellem Klick auf "Sperre aufheben" werden sie wieder freigegeben.
		 * Einzelschritt ist UNABHAENGIG von der Bewertungsmethode IMMER aktiv - fuehrt so oder so immer nur GENAU EINEN
		 * Durchlauf aus, egal ob logPost oder EP gerade gewaehlt ist.
		 *
		 * Aufzurufen: beim Aufbau der Oberflaeche, nach jeder Iteration/jedem Einzelschritt (auch bei Abbruch/Fehler) und
		 * nach "Sperre aufheben".
		 */
		private void updateAutomationAvailability() {
			boolean locked = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().isIndividualUncertaintyCeilingExceeded();
			// "Sperre aufheben" ist aktiv bei Obergrenzen-Sperre ODER Start-Sperre nach fertiger Automatik.
			Klein_Method_Tab_Builder.this.resetCeilingLockButton.setDisable(!locked && !Klein_Method_Tab_Builder.this.kleinMethod.getParameter().isAutomaticRunCompleted());
			// grobe Fehler vermutet -> nur "Grobe Fehler bereinigen" aktiv
			boolean grossSuspected = Klein_Method_Tab_Builder.this.kleinMethod.isGrossErrorsSuspected() && Klein_Method_Tab_Builder.this.visibleStep != Klein_Method_Adjustment_Step.DYNAMIC;
			boolean cleanupRunning = Klein_Method_Tab_Builder.this.kleinMethod.grossErrorCleanupRunningProperty().get();
			if (Klein_Method_Tab_Builder.this.grossErrorCleanupButton != null) {
				Klein_Method_Tab_Builder.this.grossErrorCleanupButton.setDisable(!grossSuspected || cleanupRunning || locked);
				Klein_Method_Tab_Builder.this.grossErrorCleanupButton.setStyle(grossSuspected && !cleanupRunning
						? "-fx-font-weight: bold; -fx-base: #E53935; -fx-text-fill: white;" : "-fx-font-weight: bold;");
			}

			// Solange die Automatik laeuft, bleibt Start gesperrt. Diese Pruefung laeuft auch nach jeder Iteration
			// (Statusmeldung des Laufs); freigegeben wird Start erst am Laufende (attachTaskCallbacks()).
			if (Klein_Method_Tab_Builder.this.kleinMethod.isAutomatikRunning()) {
				Klein_Method_Tab_Builder.this.startButton.setDisable(true);
				return;
			}
			if (cleanupRunning) {
				Klein_Method_Tab_Builder.this.startButton.setDisable(true);
				Klein_Method_Tab_Builder.this.singleStepButton.setDisable(true);
				Klein_Method_Tab_Builder.this.status.setText(tr("Grobe Fehler werden bereinigt (schrittweise Netzausgleichungen) ..."));
			}
			else if (grossSuspected && !locked && !Klein_Method_Tab_Builder.this.kleinMethod.getParameter().isAutoGrossErrorCleanup()) {
				Klein_Method_Tab_Builder.this.startButton.setDisable(true);
				Klein_Method_Tab_Builder.this.singleStepButton.setDisable(true);
				Klein_Method_Tab_Builder.this.status.setText(Klein_Method_Tab_Builder.this.kleinMethod.getGrossErrorGateText());
			}
			else if (grossSuspected && !locked) {
				// "Grobe Fehler automatisch bereinigen" ein: Start bleibt frei, die Automatik bereinigt vor Durchlauf 1
				Klein_Method_Tab_Builder.this.startButton.setDisable(false);
				Klein_Method_Tab_Builder.this.singleStepButton.setDisable(true);
				Klein_Method_Tab_Builder.this.status.setText(tr("Grobe Fehler vermutet – „Klein_Method starten“ bereinigt sie automatisch vor Durchlauf 1 (Protokoll, Rücknahme möglich)."));
			}
			else if (locked) {
				Klein_Method_Tab_Builder.this.startButton.setDisable(true);
				Klein_Method_Tab_Builder.this.singleStepButton.setDisable(true);
				Klein_Method_Tab_Builder.this.status.setText(tr("GESPERRT: Obergrenze der individuellen Messunsicherheit erreicht - siehe Hinweis-Fenster. "
						+ "Nach Bereinigung \u201ESperre aufheben\u201C klicken."));
			}
			else if (Klein_Method_Tab_Builder.this.logPostRadio != null) {
				// Sperre nicht (mehr) aktiv -> Start nur bei logPost, Einzelschritt IMMER Start zusaetzlich gesperrt,
				// solange eine Automatik fertig (DONE) ist. in der dynamischen AGL ist die Bewertungsmethode ausgeblendet
				// und ohne Bedeutung
				boolean isLogPost = Klein_Method_Tab_Builder.this.logPostRadio.isSelected() || Klein_Method_Tab_Builder.this.visibleStep == Klein_Method_Adjustment_Step.DYNAMIC;
				boolean completed = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().isAutomaticRunCompleted();
				Klein_Method_Tab_Builder.this.startButton.setDisable(!isLogPost || completed);
				Klein_Method_Tab_Builder.this.singleStepButton.setDisable(false);
			}
		}
		/**
		 * Waechter, Oszillations-Status und Iterationsgrenze-Status bekommen exakt dieselbe Geometrie - feste
		 * Breite/Hoehe (min = pref = max), zentrierter fetter Text. Form (Radius, keine Innenabstaende) per
		 * UI_STATUS_SHAPE im jeweiligen setStyle(), da ToggleButton und Button sonst unterschiedliche Standard-Raender
		 * des Designs haben.
		 */
		private void applyStatusBadgeGeometry(javafx.scene.control.Labeled badge) {
			badge.setFont(Font.font("System", FontWeight.BOLD, UI_FONT_SIZE));
			badge.setAlignment(Pos.CENTER);
			badge.setMinSize(UI_STATUS_WIDTH, UI_STATUS_HEIGHT);
			badge.setPrefSize(UI_STATUS_WIDTH, UI_STATUS_HEIGHT);
			badge.setMaxSize(UI_STATUS_WIDTH, UI_STATUS_HEIGHT);
		}
		/**
		 * richtet die Namens-Labels (erstes Element jeder Zeile) einer Spalte auf eine gemeinsame Breite aus, damit die
		 * Felder/Schalter buendig untereinander stehen.
		 *
		 * Breite = laengster Text der Spalte in der Einheitsschrift.
		 */
		private void alignRowLabels(VBox column) {
			double maxWidth = 0.0;
			java.util.List<Label> labels = new java.util.ArrayList<Label>();
			for (Node row : column.getChildren()) {
				if (row instanceof HBox && !((HBox) row).getChildren().isEmpty() && ((HBox) row).getChildren().get(0) instanceof Label) {
					Label label = (Label) ((HBox) row).getChildren().get(0);
					javafx.scene.text.Text probe = new javafx.scene.text.Text(label.getText());
					probe.setFont(Font.font("System", FontWeight.NORMAL, UI_FONT_SIZE));
					maxWidth = Math.max(maxWidth, probe.getLayoutBounds().getWidth());
					labels.add(label);
				}
			}
			double width = Math.ceil(maxWidth) + 8.0;
			for (Label label : labels) {
				label.setMinWidth(width);
				label.setPrefWidth(width);
			}
		}
		/**
		 * einheitliche Wertefelder - alle Textfelder gleich breit (UI_VALUE_FIELD_WIDTH), Hoehe ergibt sich aus der
		 * Einheitsschrift. Rekursiv ueber den gesamten Inhalt.
		 */
		private void normalizeValueFields(Node node) {
			if (node == null)
				return;
			if (node instanceof TextField) {
				TextField field = (TextField) node;
				// breite Anzeigefelder (z. B. "Deaktivierte BL: 111087 / 1111") doppelt so breit
				double width = field.getProperties().containsKey(UI_WIDE_FIELD) ? 2.0 * UI_VALUE_FIELD_WIDTH : UI_VALUE_FIELD_WIDTH;
				field.setMinWidth(width);
				field.setPrefWidth(width);
				field.setMaxWidth(width);
				return;
			}
			if (node instanceof ScrollPane)
				this.normalizeValueFields(((ScrollPane) node).getContent());
			else if (node instanceof TitledPane)
				this.normalizeValueFields(((TitledPane) node).getContent());
			else if (node instanceof javafx.scene.Parent && !(node instanceof javafx.scene.control.Control))
				for (Node child : ((javafx.scene.Parent) node).getChildrenUnmodifiable())
					this.normalizeValueFields(child);
		}
		/**
		 * sperrt waehrend der Automatik alle Eingaben - Schalter, Felder, Auswahllisten und Buttons - bis auf "Stop".
		 * Anzeigen (Status, Fortschritt, Ausgelesene Werte, Waechter/Live-Modus-Farbe, schreibgeschuetzte Felder) bleiben
		 * lesbar. Die Bereiche werden zusaetzlich maus-transparent, damit eine Pruefung waehrend des Laufs, die einen
		 * Schalter wieder freigibt, keinen Klick durchlaesst. Beim Entsperren werden NUR die hier gesperrten Elemente
		 * wieder freigegeben; danach entscheiden die bestehenden Verfuegbarkeits-Pruefungen.
		 */
		private void setRunLock(boolean locked) {
			java.util.List<Node> containers = new java.util.ArrayList<Node>(Klein_Method_Tab_Builder.this.runLockContainers);
			for (Node controlNode : new Node[] { Klein_Method_Tab_Builder.this.singleStepButton, Klein_Method_Tab_Builder.this.resetCeilingLockButton, Klein_Method_Tab_Builder.this.grossErrorCleanupButton, Klein_Method_Tab_Builder.this.grossErrorLinearLimitField,
					Klein_Method_Tab_Builder.this.exportCoordinatesButton,
					Klein_Method_Tab_Builder.this.coordinateExportFormatComboBox, Klein_Method_Tab_Builder.this.coordinateExportSystemComboBox, Klein_Method_Tab_Builder.this.gridImportButton,
					Klein_Method_Tab_Builder.this.quasigeoidImportButton, Klein_Method_Tab_Builder.this.quasigeoidManageButton, Klein_Method_Tab_Builder.this.exportHeightComboBox,
					Klein_Method_Tab_Builder.this.recommendationBox })
				if (controlNode != null)
					containers.add(controlNode);

			if (locked) {
				if (Klein_Method_Tab_Builder.this.runLockActive)
					return;
				Klein_Method_Tab_Builder.this.runLockActive = true;
				Klein_Method_Tab_Builder.this.runLockDisabledNodes.clear();
				// erst sammeln (Anzeige-Elemente erkennt collectRunLockNodes() an
				// setMouseTransparent(true)), DANACH die Bereiche maus-transparent machen
				for (Node container : containers)
					this.collectRunLockNodes(container);
				for (Node container : containers)
					if (container != null)
						container.setMouseTransparent(true);
				this.reapplyRunLock();
			} else {
				if (!Klein_Method_Tab_Builder.this.runLockActive)
					return;
				Klein_Method_Tab_Builder.this.runLockActive = false;
				for (Node container : containers)
					if (container != null)
						container.setMouseTransparent(false);
				for (Node node : Klein_Method_Tab_Builder.this.runLockDisabledNodes)
					node.setDisable(false);
				Klein_Method_Tab_Builder.this.runLockDisabledNodes.clear();
				Klein_Method_Tab_Builder.this.updateDampingFormulaAvailability();
				Klein_Method_Tab_Builder.this.updateRecommendationView();
			}
		}
		/**
		 * erneut sperren - nach Pruefungen waehrend des Laufs (z.B.updateAutomationAvailability()), die einzelne Elemente
		 * wieder freigeben.
		 */
		private void reapplyRunLock() {
			if (!Klein_Method_Tab_Builder.this.runLockActive)
				return;
			for (Node node : Klein_Method_Tab_Builder.this.runLockDisabledNodes)
				node.setDisable(true);
		}
		private void collectRunLockNodes(Node node) {
			if (node == null || node == Klein_Method_Tab_Builder.this.stopButton)
				return;
			boolean displayOnly = node.isMouseTransparent() || node == Klein_Method_Tab_Builder.this.guardianToggleButton || node == Klein_Method_Tab_Builder.this.liveModeToggleButton;
			boolean input = node instanceof javafx.scene.control.ButtonBase
					|| node instanceof javafx.scene.control.ComboBoxBase
					|| (node instanceof javafx.scene.control.TextInputControl && ((javafx.scene.control.TextInputControl) node).isEditable());
			if (input && !displayOnly && !node.isDisable())
				Klein_Method_Tab_Builder.this.runLockDisabledNodes.add(node);
			if (node instanceof ScrollPane)
				this.collectRunLockNodes(((ScrollPane) node).getContent());
			else if (node instanceof TitledPane)
				this.collectRunLockNodes(((TitledPane) node).getContent());
			else if (node instanceof javafx.scene.Parent && !(node instanceof javafx.scene.control.Control))
				for (Node child : ((javafx.scene.Parent) node).getChildrenUnmodifiable())
					this.collectRunLockNodes(child);
		}
		/**
		 * oeffentlicher Einstieg fuer Klein_Method (z.B. nach Projektwechsel), damit der Start-Button nach Aufhebung der
		 * "Automatik fertig"-Sperre sofort wieder freigegeben wird.
		 */
		public void refreshAutomationAvailability() {
			if (Klein_Method_Tab_Builder.this.startButton == null)
				return;
			this.updateAutomationAvailability();
		}
		/**
		 * Faerbt den Fortschrittsbalken je nach Anteil ein:
		 * 0.0 = rot (Start) -> 0.5 = gelb (Mitte) -> 1.0 = gruen (Ende).
		 * Linear interpoliert zwischen den beiden Abschnitten.
		 */
		private void updateProgressBarColor(double fraction) {
			double clamped = Math.max(0.0, Math.min(fraction, 1.0));
			Color color;
			if (clamped <= 0.5) {
				// rot -> gelb
				color = Color.rgb(255, (int) Math.round(255 * (clamped / 0.5)), 0);
			} else {
				// gelb -> gruen
				color = Color.rgb((int) Math.round(255 * (1.0 - (clamped - 0.5) / 0.5)), 255, 0);
			}
			String hexColor = String.format("#%02X%02X%02X",
					(int) Math.round(color.getRed() * 255),
					(int) Math.round(color.getGreen() * 255),
					(int) Math.round(color.getBlue() * 255));

			Klein_Method_Tab_Builder.this.progressBar.setStyle("-fx-accent: " + hexColor + ";");

			// Label bleibt jetzt transparent, nur der Balken selbst (siehe oben) traegt die Farbe; Text bleibt schwarz
			// und dank Padding gut lesbar.
		}
		private void wireStartStopButtons() {
			Klein_Method_Tab_Builder.this.startButton.setOnAction(event -> {
				// Ablauf-Assistent: rote Schritte nur in Log und Protokoll vermerken (kein Fenster)
				if (Klein_Method_Tab_Builder.this.kleinMethod.checkWorkflowBeforeStart())
					return;
				// Einstellung "Wiederholte Messungen" noch nicht angewendet: zuerst anwenden (Hintergrund), dann erneut starten
				if (Klein_Method_Tab_Builder.this.kleinMethod.checkRepetitionsBeforeStart(() -> {
					if (!Klein_Method_Tab_Builder.this.restartAutomatic())
						Klein_Method_Tab_Builder.this.status.setText(tr("Wiederholte Messungen angewendet - bitte „Klein_Method starten“ erneut klicken."));
				}))
					return;
				// Start-Schutzweiche - ist die Bewertungsmethode logPost aktiv UND der aktuelle Max-logPost-Wert bereits
				// unter dem Grenzwert, wird KEIN neuer Automatik-Lauf gestartet. Ohne diese Weiche wuerde ein erneuter
				// Klick auf "Klein_Method starten" unkontrolliert wieder resetProcessPhase() auf GROUP_CALIBRATION
				// ausloesen (siehe Klein_Method_Parameter.resetProcessPhase(), aufgerufen zu Beginn jedes neuen
				// Klein_Method_Iteration_Task-Laufs) - der bereits erreichte, unterschrittene Grenzwert wird dadurch fuer
				// mehrere weitere Iterationen wieder verlassen, bevor die Phase erneut DONE erreicht. Nutzt bewusst
				// dieselbe Pruefmethode (isLogPostBelowThreshold()), die auch ueber Klein_Method_Process_Phase.DONE
				// entscheidet, statt einer zweiten, davon unabhaengigen Schwellenwert-Abfrage - damit diese Schutzweiche
				// niemals von der tatsaechlichen Abbruch-bedingung abweichen kann.
				boolean isLogPostMethod = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getEvaluationMethod() == Klein_Method_Evaluation_Method.LOG_POST;
				boolean alreadyBelowThreshold = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().isLogPostBelowThreshold(Klein_Method_Tab_Builder.this.kleinMethod.getParameter());
				// in der dynamischen AGL keine logPost-Pruefung - die Festpunktanalyse muss auch bei bereits
				// unterschrittenem Grenzwert starten.
				boolean dynamicStep = Klein_Method_Tab_Builder.this.visibleStep == Klein_Method_Adjustment_Step.DYNAMIC;
				if (!dynamicStep && isLogPostMethod && alreadyBelowThreshold) {
					Klein_Method_Tab_Builder.this.status.setText(tr("Grenzwert bereits erreicht."));
					return;
				}

				// vor JEDEM Start-Klick werden mehrfach vorkommende Punktnamen mit zu grossem Lageabstand gemeldet -
				// Protokollzeile + Dialog, der Start selbst wird NICHT blockiert (ehrliche Warn-Variante statt autonomer
				// Datenbank-Manipulation, siehe Klein_Method_Parameter.FANGKREIS_RADIUS_DEFAULT und
				// Klein_Method_Functions.scanForFangkreisViolations() fuer die vollstaendige Begruendung). Eine
				// SQLException hier (z.B. abweichender Spaltenname) wird protokolliert, aber bricht den Start bewusst
				// NICHT ab - der Scan ist eine zusaetzliche Hilfe, keine Voraussetzung fuer den eigentlichen Lauf.
				try {
					java.util.List<Klein_Method_Functions.Klein_Method_Fangkreis_Violation> fangkreisViolations = Klein_Method_Tab_Builder.this.kleinMethod.getFunctions()
							.scanForFangkreisViolations(Klein_Method_Tab_Builder.this.kleinMethod.getParameter(), Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler());
					if (!fangkreisViolations.isEmpty()) {
						for (Klein_Method_Functions.Klein_Method_Fangkreis_Violation violation : fangkreisViolations)
							Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method W\u00C4CHTER V0.303 [Fangkreis]: " + violation.getMessage());

						// der vorgeschlagene, jetzt vom Anwender ausdruecklich bestaetigte Mittelweg - JEDER Fund bekommt
						// einen EIGENEN "Umbenennung bestaetigen"-Button; das UPDATE laeuft AUSSCHLIESSLICH nach diesem
						// einen, expliziten Klick (renamePointForFangkreisViolation() in Klein_Method_Database_Handler),
						// niemals automatisch beim Scan selbst.
						Dialog<Void> dialog = new Dialog<Void>();
						dialog.setTitle(tr("Klein_Method - Fangkreis-Vor-Scan"));
						dialog.getDialogPane().setHeaderText(fangkreisViolations.size() + tr(" auff\u00E4llige(r) Punktname(n) gefunden. "
								+ "Die Punkte wurden NICHT automatisch umnummeriert - pr\u00FCfen Sie jeden Fall einzeln und best\u00E4tigen "
								+ "Sie die Umbenennung nur, wenn es sich tats\u00E4chlich um unterschiedliche physische Punkte handelt."));

						VBox violationsBox = new VBox(10);
						for (Klein_Method_Functions.Klein_Method_Fangkreis_Violation violation : fangkreisViolations) {
							Label messageLabel = new Label(violation.getMessage());
							messageLabel.setWrapText(true);
							messageLabel.setPrefWidth(360);

							Button confirmButton = new Button(tr("Umbenennung best\u00E4tigen"));
							confirmButton.setTooltip(new Tooltip(tr("F\u00FChrt aus: UPDATE \"PointApriori\" SET \"name\" = \"name\" || "
									+ "'_KLEIN_A' WHERE \"id\" = ") + violation.getPointId() + tr(". Nicht rueckg\u00E4ngig machbar.")));
							confirmButton.setOnAction(confirmEvent -> {
								try {
									Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().renamePointForFangkreisViolation(violation.getPointId());
									Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method W\u00C4CHTER V0.303 [Fangkreis]: Punkt-ID "
											+ violation.getPointId() + " auf Anwender-Best\u00E4tigung hin umbenannt (..._KLEIN_A).");
									confirmButton.setText(tr("Umbenannt."));
									confirmButton.setDisable(true);
								} catch (SQLException confirmException) {
									Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Umbenennung Punkt-ID " + violation.getPointId()
											+ " fehlgeschlagen (SQLException): " + confirmException.getMessage());
									confirmButton.setText(tr("Fehlgeschlagen - siehe Log"));
								}
							});

							HBox violationRow = new HBox(10, messageLabel, confirmButton);
							violationsBox.getChildren().add(violationRow);
						}

						ScrollPane violationsScrollPane = new ScrollPane(violationsBox);
						violationsScrollPane.setFitToWidth(true);
						violationsScrollPane.setPrefSize(480, 300);
						dialog.getDialogPane().setContent(violationsScrollPane);
						dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
						dialog.showAndWait();
					}
				} catch (SQLException e) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Fangkreis-Vor-Scan uebersprungen (SQLException): " + e.getMessage());
				}

				// Messachsenzahl VOR jedem Start neu ermitteln, damit der Autopilot-Deckel
				// (Klein_Method_Parameter.getMaxGroupScaleLimit()) auf dem aktuellen Projektstand beruht. Eine
				// SQLException wird protokolliert, bricht den Start aber NICHT ab (derselbe Grundsatz wie beim
				// Fangkreis-Vor-Scan direkt oberhalb) - bei einem Fehler bleibt einfach der zuletzt bekannte Stand (bzw.
				// 0 vor dem allerersten erfolgreichen Scan) erhalten.
				try {
					int axisCount = Klein_Method_Tab_Builder.this.kleinMethod.getDatabaseHandler().readTotalMeasurementAxisCount();
					Klein_Method_Tab_Builder.this.kleinMethod.getParameter().setMeasurementAxisCount(axisCount);
					for (Map.Entry<Klein_Method_Threshold_Type, TextField> entry : Klein_Method_Tab_Builder.this.groupScaleFactorFields.entrySet()) {
						double currentValue = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getGroupScaleFactor(entry.getKey());
						entry.getValue().setText(String.valueOf(currentValue));
					}
				} catch (SQLException e) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Messachsenzahl fuer Autopilot-Deckel nicht ermittelbar (SQLException): " + e.getMessage());
				}

				// beim Start der Automatik soll sich der Iterationszaehler zuruecksetzen, damit er nicht ueber mehrere
				// Start-Klicks hinweg immer weiterzaehlt.
				Klein_Method_Tab_Builder.this.kleinMethod.getParameter().resetIterationCount();
				Klein_Method_Tab_Builder.this.iterationCountLabel.setText(tr("Aktuelle Iteration: ") + Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getIterationCount());
				Klein_Method_Tab_Builder.this.progressBarOverlayLabel.setText(tr("Iteration: ") + Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getIterationCount());
				Klein_Method_Tab_Builder.this.applyIterationLimitStatus();

				// Deshalb hier explizit auf Rot (0%) zuruecksetzen, VOR dem eigentlichen Start.
				this.updateProgressBarColor(0.0);

				// waehrend die Automatik laeuft, darf nicht in andere Tabs/Baumbereiche gewechselt werden (siehe
				// Klein_Method.registerTab()-Listener).
				Klein_Method_Tab_Builder.this.kleinMethod.setAutomatikRunning(true);

				// Radio-Buttons "logPost"/"EP" waehrend der laufenden Automatik sperren - verhindert, dass die
				// Bewertungsmethode mitten im Lauf umgeschaltet wird. Wird wieder freigegeben, sobald der Task endet
				// (DONE, Abbruch oder Fehler) - siehe attachTaskCallbacks(). Ein Klick auf "Stop" fuehrt ueber
				// task.cancel() ebenfalls zu genau diesem Task-Ende (setOnCancelled), eine zusaetzliche Freigabe hier ist
				// daher nicht noetig.
				Klein_Method_Tab_Builder.this.logPostRadio.setDisable(true);
				Klein_Method_Tab_Builder.this.epRadio.setDisable(true);

				Klein_Method_Tab_Builder.this.kleinMethod.startIteration();
				Klein_Method_Tab_Builder.this.startButton.setDisable(true);
				Klein_Method_Tab_Builder.this.stopButton.setDisable(false);
				this.setRunLock(true);
				Klein_Method_Tab_Builder.this.status.setText(tr("Iteration laeuft ..."));
				this.attachTaskCallbacks(Klein_Method_Tab_Builder.this.startButton, Klein_Method_Tab_Builder.this.stopButton);
			});

			Klein_Method_Tab_Builder.this.stopButton.setOnAction(event -> {
				Klein_Method_Tab_Builder.this.kleinMethod.stopIteration();
				Klein_Method_Tab_Builder.this.stopButton.setDisable(true);
			});
		}
		private void wireSingleStepButton() {
			Klein_Method_Tab_Builder.this.singleStepButton.setOnAction(event -> {
				Klein_Method_Tab_Builder.this.singleStepButton.setDisable(true);
				Klein_Method_Tab_Builder.this.status.setText(tr("EP-Einzelschritt l\u00E4uft ..."));
				Klein_Method_Tab_Builder.this.kleinMethod.runEpSingleStep();
			});
		}
		/**
		 * Wird von Klein_Method.runEpSingleStep() aufgerufen, sobald der
		 * EP-Einzelschritt fertig ist (egal ob erfolgreich, mit Hinweis, oder
		 * abgebrochen) - setzt den Button und die Statuszeile zurueck, aktualisiert
		 * die Zusatzparameter mit (koennten sich durch die Korrektur mit veraendert
		 * haben - eher unwahrscheinlich, aber konsistent mit dem sonstigen Refresh-Muster).
		 */
		public void onEpSingleStepFinished() {
			this.updateAutomationAvailability(); // Einzelschritt bleibt aktiv, es sei denn die Obergrenzen-Sperre hat gerade zugeschlagen
			Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability(); // logPost koennte sich durch den Einzelschritt veraendert haben
			// Hinweis des Einzelschritts in der Statuszeile statt als Meldungsfenster
			String hint = Klein_Method_Tab_Builder.this.kleinMethod.getLastEpSingleStepHint();
			Klein_Method_Tab_Builder.this.status.setText(hint == null ? tr("Bereit.") : tr("Einzelschritt: ") + hint);
		}
		/**
		 * Haengt Status-Callbacks an den aktuell laufenden Task (egal ob
		 * Automatik oder Einzelschritt) und setzt die uebergebenen Buttons
		 * beim Abschluss wieder in einen bedienbaren Zustand zurueck.
		 */
		private void attachTaskCallbacks(Button primaryButtonToReEnable, Button secondaryButtonToDisable) {
			var task = Klein_Method_Tab_Builder.this.kleinMethod.getRunningTask();

			Klein_Method_Tab_Builder.this.resetIterationButton.setDisable(true);
			Klein_Method_Tab_Builder.this.progressBar.progressProperty().unbind();
			Klein_Method_Tab_Builder.this.progressBar.progressProperty().bind(task.progressProperty());

			task.progressProperty().addListener((obs, oldProgress, newProgress) ->
					Platform.runLater(() -> this.updateProgressBarColor(newProgress.doubleValue()))
			);

			task.messageProperty().addListener((obs, oldMsg, newMsg) ->
					Platform.runLater(() -> {
						Klein_Method_Tab_Builder.this.status.setText(newMsg);
						int currentCount = Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getIterationCount();
						Klein_Method_Tab_Builder.this.iterationCountLabel.setText(tr("Aktuelle Iteration: ") + currentCount);
						Klein_Method_Tab_Builder.this.progressBarOverlayLabel.setText(tr("Iteration: ") + currentCount);
						Klein_Method_Tab_Builder.this.applyIterationLimitStatus();
						Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability();
						Klein_Method_Tab_Builder.this.updateDiagnosticValueLabels();
						this.updateAutomationAvailability();
						Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
						this.reapplyRunLock();
					})
			);
			task.setOnSucceeded(e -> Platform.runLater(() -> {
				this.setRunLock(false);
				Klein_Method_Tab_Builder.this.status.setText(tr("Abgeschlossen."));
				// Automatik regulaer fertig (Phase DONE) -> Start sperren, bis ein Projekt neu geoeffnet/gewechselt oder
				// "Reset" geklickt wird. Abbruch, Fehler oder Ende ohne DONE sperren NICHT (Neustart dort sinnvoll).
				if (Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getProcessPhase() == Klein_Method_Process_Phase.DONE) {
					Klein_Method_Tab_Builder.this.kleinMethod.markAutomaticRunCompleted();
					Klein_Method_Tab_Builder.this.status.setText(tr("Abgeschlossen. Erneuter Start der Automatik erst nach Wechsel der Ausgleichungsstufe "
							+ "(dynamisch/fix) oder \u201ESperre aufheben\u201C - Einzelschritt bleibt m\u00F6glich."));
					if (Klein_Method_Tab_Builder.this.visibleStep == Klein_Method_Adjustment_Step.DYNAMIC
							&& !Klein_Method_Tab_Builder.this.kleinMethod.getFunctions().isLogPostBelowThreshold(Klein_Method_Tab_Builder.this.kleinMethod.getParameter()))
						Klein_Method_Tab_Builder.this.status.setText(tr("Festpunkte kalibriert. Max-logPost liegt über dem Grenzwert - die Beobachtungen "
								+ "werden in der fixen Ausgleichung kalibriert.") + " " + Klein_Method_Tab_Builder.this.status.getText());
				}
				primaryButtonToReEnable.setDisable(false);
				Klein_Method_Tab_Builder.this.resetIterationButton.setDisable(false);
				Klein_Method_Tab_Builder.this.progressBar.progressProperty().unbind();
				if (secondaryButtonToDisable != null)
					secondaryButtonToDisable.setDisable(true);
				Klein_Method_Tab_Builder.this.kleinMethod.setAutomatikRunning(false);
				this.updateAutomationAvailability();
				Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
				// Deshalb hier am TASK-ENDE nochmal EINE autoritative, garantierte Neupruefung.
				Klein_Method_Tab_Builder.this.logPostRadio.setDisable(false);
				Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability();
				Klein_Method_Tab_Builder.this.refreshDataStatus();
			}));
			task.setOnCancelled(e -> Platform.runLater(() -> {
				this.setRunLock(false);
				Klein_Method_Tab_Builder.this.status.setText(tr("Abgebrochen."));
				primaryButtonToReEnable.setDisable(false);
				Klein_Method_Tab_Builder.this.resetIterationButton.setDisable(false);
				Klein_Method_Tab_Builder.this.progressBar.progressProperty().unbind();
				if (secondaryButtonToDisable != null)
					secondaryButtonToDisable.setDisable(true);
				Klein_Method_Tab_Builder.this.kleinMethod.setAutomatikRunning(false);
				this.updateAutomationAvailability();
				Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
				// siehe Kommentar bei setOnSucceeded().
				Klein_Method_Tab_Builder.this.logPostRadio.setDisable(false);
				Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability(); // siehe Kommentar bei setOnSucceeded()
				Klein_Method_Tab_Builder.this.refreshDataStatus();
			}));
			task.setOnFailed(e -> Platform.runLater(() -> {
				this.setRunLock(false);
				Klein_Method_Tab_Builder.this.status.setText(tr("Fehler: ") + task.getException().getMessage());
				primaryButtonToReEnable.setDisable(false);
				Klein_Method_Tab_Builder.this.resetIterationButton.setDisable(false);
				Klein_Method_Tab_Builder.this.progressBar.progressProperty().unbind();
				if (secondaryButtonToDisable != null)
					secondaryButtonToDisable.setDisable(true);
				Klein_Method_Tab_Builder.this.kleinMethod.setAutomatikRunning(false);
				this.updateAutomationAvailability();
				Klein_Method_Tab_Builder.this.updateProtocolToggleAvailability();
				// siehe Kommentar bei setOnSucceeded().
				Klein_Method_Tab_Builder.this.logPostRadio.setDisable(false);
				Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability(); // siehe Kommentar bei setOnSucceeded()
				Klein_Method_Tab_Builder.this.refreshDataStatus();
			}));
		}	}

	// ============================================================================================================
	/** Grenzwerte und Eingabefelder (logPost, Gruppenunsicherheiten, VKS-Band, Trichter, grobe Fehler). */
	private final class Threshold_Part {

		/**
		 * Umh\u00FCllt createThresholdInputGrid() (bestehende Grenzwerte, unver\u00E4ndert) links, mit einem vertikalen
		 * Trenner und einer rechten Spalte f\u00FCr K\u00FCNFTIGE Grenzwerte - aktuell nur ein Platzhalter-Hinweis,
		 * bewusst noch OHNE eigene Felder, da GNSS von Klein_Method noch nicht ausgewertet wird (siehe
		 * SUPPORTED_OBSERVATION_TYPES).
		 */
		/**
		 * Umgezogen: das gemeinsame logPost-Grenzwertfeld gehoert jetzt zur zusammengefassten Sektion "Grenzwerte
		 * Gruppenunsicherheit &amp; Zusatzparameter" statt zur alten "Grenzwerte"-Sektion (die jetzt nur noch die 4
		 * EP-Grenzwerte enthaelt).
		 */
		private HBox createSharedLogPostThresholdRow() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			Label logPostLabel = new Label(tr("logPost (gemeinsam):"));
			TextField logPostField = new TextField(String.valueOf(parameter.getSharedLogPostThreshold()));
			logPostField.setPrefWidth(100);
			logPostField.setTooltip(new Tooltip(tr("Gemeinsamer logPost-Grenzwert f\u00FCr Horizontalrichtung, Horizontalstrecke, "
					+ "Vertikalrichtung und Schr\u00E4gstrecke - ein statistisches Signifikanzkriterium, das nicht vom Gr\u00F6\u00DFentyp "
					+ "abh\u00E4ngt.\nMindestwert: 2.45 (wird beim Editieren automatisch erzwungen).")));

			logPostField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					this.applySharedLogPostFieldValue(parameter, logPostField);
			});
			logPostField.setOnAction(event -> this.applySharedLogPostFieldValue(parameter, logPostField));

			return new HBox(10, logPostLabel, logPostField);
		}
		private HBox createThresholdSection() {
			GridPane existingThresholds = this.createThresholdInputGrid();

			Separator verticalSeparator = new Separator(Orientation.VERTICAL);

			// Platzhaltertext "Reserviert fuer kuenftige Grenzwerte ..." entfernt - an seiner Stelle stehen jetzt
			// VKS-Band Min/Max, Trichter-Untergrenze und Abbruchgrenze ab DL 2.
			GridPane additionalThresholds = this.createAdditionalThresholdGrid();
			VBox rightColumn = new VBox(additionalThresholds);
			rightColumn.setPadding(new Insets(0, 0, 0, 10));

			Klein_Method_Tab_Builder.this.markFreeFixedOnly(existingThresholds, verticalSeparator);
			HBox section = new HBox(15, existingThresholds, verticalSeparator, rightColumn);
			return section;
		}
		/**
		 * Erzeugt fuer JEDEN EP-Grenzwert (Klein_Method_Threshold_Type, Kategorie EP) automatisch eine Zeile mit
		 * Beschriftung und Eingabefeld. Neue Grenzwerte muessen also nur im Enum ergaenzt werden - hier ist keine
		 * Anpassung noetig.
		 */
		private GridPane createThresholdInputGrid() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			GridPane grid = new GridPane();
			grid.setHgap(10);
			grid.setVgap(8);
			grid.setPadding(new Insets(10, 0, 0, 0));

			int row = 0;

			// EP-Grenzwerte weiterhin einzeln je Typ (4 Felder)
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.EP)) {
				String unitSuffix = type.getUnit().isEmpty() ? "" : " [" + type.getUnit() + "]";
				Label label = new Label(tr(type.getDisplayName()) + unitSuffix + ":");

				TextField field = new TextField(String.valueOf(parameter.getThresholdValue(type)));
				field.setPrefWidth(100);
				field.setTooltip(new Tooltip(thresholdTooltip(type)));

				field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
					if (!isNowFocused) {
						this.applyFieldValue(parameter, type, field);
					}
				});
				field.setOnAction(event -> this.applyFieldValue(parameter, type, field));

				grid.add(label, 0, row);
				grid.add(field, 1, row);
				row++;
			}

			return grid;
		}
		/**
		 * die vier Grenzwerte VKS-Band Min/Max, Trichter-Untergrenze und Abbruchgrenze ab DL 2 stehen jetzt in der
		 * RECHTEN Spalte der Sektion "Grenzwerte". Gleiches Raster wie links (hgap 10, vgap 8, Feldbreite 100).
		 */
		private GridPane createAdditionalThresholdGrid() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			GridPane grid = new GridPane();
			grid.setHgap(10);
			grid.setVgap(8);
			grid.setPadding(new Insets(10, 0, 0, 0));

			int row = 0;

			// Zielkorridor der Gruppen-Daempfung (Spalte 0 Label, Spalte 1 Feld).
			TextField vksBandMinField = this.createVksBandField(parameter, true);
			Klein_Method_Tab_Builder.this.vksBandMinField = vksBandMinField;
			Klein_Method_Tab_Builder.this.vksBandMinLabel = new Label(tr("VKS-Band Min (s\u00B2):"));
			grid.add(Klein_Method_Tab_Builder.this.vksBandMinLabel, 0, row);
			grid.add(vksBandMinField, 1, row);
			row++;

			TextField vksBandMaxField = this.createVksBandField(parameter, false);
			Klein_Method_Tab_Builder.this.vksBandMaxField = vksBandMaxField;
			Klein_Method_Tab_Builder.this.vksBandMaxLabel = new Label(tr("VKS-Band Max (s\u00B2):"));
			grid.add(Klein_Method_Tab_Builder.this.vksBandMaxLabel, 0, row);
			grid.add(vksBandMaxField, 1, row);
			row++;

			// Trichter-Untergrenze (logPost) - dasselbe GridPane, fluchtet mit den Feldern darueber.
			TextField funnelFloorField = this.createFunnelFloorField(parameter);
			Label funnelFloorLabel = new Label(tr("Trichter-Untergrenze (logPost):"));
			grid.add(funnelFloorLabel, 0, row);
			grid.add(funnelFloorField, 1, row);
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(funnelFloorLabel, funnelFloorField);
			row++;

			// Abbruchgrenze ab Kaskaden-Durchlauf 2 - dasselbe GridPane, fluchtet.
			TextField cascadeAbortLimitField = this.createCascadeAbortLimitField(parameter);
			Label cascadeAbortLimitLabel = new Label(tr("Abbruchgrenze ab DL 2 (logPost):"));
			grid.add(cascadeAbortLimitLabel, 0, row);
			grid.add(cascadeAbortLimitField, 1, row);
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(cascadeAbortLimitLabel, cascadeAbortLimitField);
			row++;

			// Nachlauf auf den Grenzwert (nach Erreichen der Abbruchgrenze ab DL 2)
			Klein_Method_Parameter p = parameter;
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Nachlauf Grenzwert: max. Iterationen (0 = aus):"), p::getFollowUpMaxIterations,
					p::setFollowUpMaxIterations, 0,
					tr("Liegt Max-logPost beim Erreichen der Abbruchgrenze ab DL 2 noch über dem logPost-Grenzwert, rechnet Phase 2 weiter\n"
							+ "in Richtung Grenzwert. Der jeweils beste Stand (Unsicherheiten, Gruppenwerte, aktive Beobachtungen, Zusatzparameter)\n"
							+ "wird im Projekt gesichert. Wird der Grenzwert nicht erreicht, wird der beste Stand zurückgeschrieben und noch einmal\n"
							+ "ausgeglichen; der Lauf endet mit Vermerk und zählt nicht für das Schwarmwissen.\n"
							+ "Lag Max-logPost schon in Durchlauf 1 unter dem Grenzwert, wird auch dieser Stand gesichert und übernommen,\n"
							+ "wenn die weiteren Durchläufe den Grenzwert nicht erreichen (dann gilt der Lauf als kalibriert).\n"
							+ "Höchstzahl der Nachlauf-Iterationen. Standard 30, erlaubt 1 bis 200, 0 = Nachlauf und Sicherung aus."));
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Nachlauf: Ende ohne Verbesserung nach:"), p::getFollowUpPatience,
					p::setFollowUpPatience, 0,
					tr("Der Nachlauf endet, wenn sich der beste Max-logPost so viele Iterationen lang nicht mehr verbessert hat\n"
							+ "(mindestens 0,1 % besser). Dann wird der beste Stand zurückgeschrieben.\n"
							+ "Standard 8, erlaubt 2 bis 50."));
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Dynamische AGL: Untergrenze Festpunkte Lage/2D/3D mit GNSS (mm):"),
					p::getPointFloorPositionMillimeter, p::setPointFloorPositionMillimeter, 2,
					tr("Genauer als dieser Wert wird ein Festpunkt (beweglicher Anschlusspunkt) in der dynamischen AGL nicht angesetzt.\n"
							+ "Ist ein Festpunkt mit den vorhandenen Messungen nicht prüfbar, würde seine Genauigkeit sonst bis fast null sinken:\n"
							+ "er wäre praktisch starr, und die Messungen (z. B. GNSS-Basislinien) hätten keinen Einfluss mehr auf ihn.\n"
							+ "Liegt ein Festpunkt am Ende an dieser Untergrenze, steht ein Hinweis im Protokoll.\n"
							+ "Gilt nur, wenn das Projekt aktive GNSS-Basislinien hat - ohne GNSS gilt 0,01 mm (Lage und Höhe).\n"
							+ "Standard 1,0 mm, erlaubt 0,01 bis 50 mm."));
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Dynamische AGL: Untergrenze Höhenfestpunkte 1D mit GNSS (mm):"),
					p::getPointFloorHeight1DMillimeter, p::setPointFloorHeight1DMillimeter, 2,
					tr("Untergrenze der Genauigkeit für Höhenfestpunkte (1D) in der dynamischen AGL.\n"
							+ "Wirkt wie die Untergrenze für die Lage: nicht prüfbare Höhenpunkte werden nicht genauer als dieser Wert angesetzt.\n"
							+ "Gilt nur, wenn das Projekt aktive GNSS-Basislinien hat - ohne GNSS gilt 0,01 mm.\n"
							+ "Standard 0,3 mm, erlaubt 0,01 bis 50 mm."));

			// Qualitaet der Gruppenkalibrierung (nur freie/fixe Ausgleichung)
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Volles VKS-Band ab Redundanz:"), p::getFullBandRedundancy, p::setFullBandRedundancy, 0,
					tr("Unterhalb dieser Redundanz einer Komponente (a/b/c eines Beobachtungstyps) wird das VKS-Band um \u221A(Wert/Redundanz) aufgeweitet,\n"
							+ "h\u00F6chstens auf 0,900-1,100. Wenige \u00FCbersch\u00FCssige Beobachtungen tragen eine enge Kalibrierung statistisch nicht -\n"
							+ "so wird das Rauschen weniger Beobachtungen nicht \u201Ewegkalibriert\u201C und es gibt weniger Schleifen ohne Nutzen.\n"
							+ "Standard 100, erlaubt 5 bis 100 000, 0 = aus (festes Band f\u00FCr alle)."));
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Mindestredundanz Kalibrierung:"), p::getMinCalibrationRedundancy, p::setMinCalibrationRedundancy, 1,
					tr("Hat eine Komponente weniger Redundanz als dieser Wert, wird sie nicht kalibriert: der Startwert (bzw. Schwarm-Startwert) bleibt,\n"
							+ "Hinweis im Protokoll und im Kalibrierungsnachweis. Verhindert zuf\u00E4llige, unrealistische sigma-Werte.\n"
							+ "Standard 5, erlaubt 0 bis 100, 0 = aus."));
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Plausibilit\u00E4t (Faktor Datenblatt):"), p::getPlausibilityFactor, p::setPlausibilityFactor, 1,
					tr("Im Kalibrierungsnachweis erscheint ein Hinweis, wenn eine kalibrierte Gruppenunsicherheit gr\u00F6\u00DFer ist als dieser Faktor\n"
							+ "mal ein typischer Datenblattwert (z. B. Richtung 0,3 mgon, Strecke 1 mm + 1,5 ppm, Nivellement 0,2 mm, GNSS 3 mm + 0,5 ppm;\n"
							+ "Ger\u00E4teklasse historisch x5). Deutet auf Datenfehler, falsche Gruppen oder grobe Fehler.\n"
							+ "Standard 10, erlaubt 2 bis 100, 0 = aus."));
			row = Klein_Method_Tab_Builder.this.addQualityField(grid, row, tr("Robuste VKS (Grenzfaktor):"), p::getRobustRatioLimit, p::setRobustRatioLimit, 2,
					tr("Zu Beginn der Kalibrierung wird je Beobachtungstyp die VKS zus\u00E4tzlich robust (Median) gerechnet. Ist die normale VKS\n"
							+ "mehr als dieser Faktor gr\u00F6\u00DFer als die robuste, steckt vermutlich ein unentdeckter grober Fehler im Typ:\n"
							+ "Hinweisfenster (die Automatik l\u00E4uft weiter), Protokoll und Kalibrierungsnachweis.\n"
							+ "Standard 2,0, erlaubt 1,2 bis 10, 0 = aus."));

			return grid;
		}
		/**
		 * erzeugt eines der beiden VKS-Band-Felder inkl. Pruefung bei Fokusverlust/Enter. Die eigentliche Grenzpruefung
		 * liegt in Klein_Method_Parameter.setVksBandMin()/setVksBandMax() (Werte ausserhalb werden dort HART auf den
		 * Default zurueckgesetzt) - hier nur Parsen, Anzeige zuruecksetzen, Hinweis und Speichern.
		 */
		private TextField createCascadeAbortLimitField(Klein_Method_Parameter parameter) {
			TextField field = new TextField(Klein_Method_Format.format(parameter.getCascadeAbortLimit(), "", 2));
			field.setPrefWidth(100);
			field.setTooltip(new Tooltip(tr("Nur mit Kaskaden-Mehrfachlauf: ab Durchlauf 2 endet die Berechnung, sobald Max-logPost "
					+ "unter diesem Wert liegt (ohne Untergrenzen-Erzwingung, der aktuelle Stand bleibt).\n"
					+ "Mit 2,45 endet Durchlauf 2 erst bei vollst\u00E4ndiger Kalibrierung (streng).\n"
					+ "Erlaubt: ") + Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_UPPER_LIMIT
					+ tr(", sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_DEFAULT + "."));

			Runnable apply = () -> {
				String enteredText = field.getText();
				double enteredValue;
				try {
					enteredValue = Double.parseDouble(enteredText.trim().replace(",", "."));
				} catch (NumberFormatException e) {
					enteredValue = Double.NaN;
				}
				double previousValue = parameter.getCascadeAbortLimit();
				double storedValue = parameter.setCascadeAbortLimit(enteredValue);
				field.setText(Klein_Method_Format.format(storedValue, "", 2));

				boolean reset = Double.isNaN(enteredValue) || storedValue != enteredValue;
				if (reset) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Abbruchgrenze ab DL 2 Eingabe \"" + enteredText + "\" ausserhalb "
							+ "des erlaubten Bereichs - auf Default " + storedValue + " zurueckgesetzt.");
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Wert zur\u00FCckgesetzt"));
					alert.setHeaderText(tr("Eingegebener Wert au\u00DFerhalb des erlaubten Bereichs"));
					alert.setContentText(tr("Der Wert f\u00FCr \"Abbruchgrenze ab DL 2 (logPost)\" lag au\u00DFerhalb von ")
							+ Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.CASCADE_ABORT_LIMIT_UPPER_LIMIT
							+ tr(" und wurde auf den Standardwert ") + storedValue + tr(" zur\u00FCckgesetzt."));
					alert.showAndWait();
				}
				if (reset || storedValue != previousValue)
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			};
			field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					apply.run();
			});
			field.setOnAction(event -> apply.run());
			return field;
		}
		/** Eingabefeld "Richtwert lineare Abweichung (m)" - Vorauswahl und Farbe in den Fenstern der Bereinigung. */
		private TextField createGrossErrorLinearLimitField(Klein_Method_Parameter parameter) {
			TextField field = new TextField(Klein_Method_Format.format(parameter.getGrossErrorLinearLimit(), "", 3));
			field.setPrefWidth(100);
			field.setTooltip(new Tooltip(tr("Grenze grober Fehler als lineare Abweichung einer Beobachtung in Metern (Standard 0,10 m).\n"
					+ "Einziges Kriterium - kein logPost oder anderer statistischer Wert:\n"
					+ "\u2022 Sperre nach F5: liegt eine Beobachtung dar\u00FCber, ist nur \u201EGrobe Fehler bereinigen\u201C aktiv.\n"
					+ "\u2022 Bereinigung: nimmt schrittweise die gr\u00F6\u00DFte Abweichung heraus, bis keine mehr dar\u00FCber liegt.\n"
					+ "\u2022 Fenster: bis zu diesem Wert gr\u00FCn (h\u00E4lt die Ausgleichung aus, wieder aufnehmen), dar\u00FCber rot (grober Fehler).\n"
					+ "Lineare Abweichung = gesch\u00E4tzter grober Fehler aus JAG3D; Richtung/Zenitwinkel mal Strecke, GNSS als 3D-L\u00E4nge.\n"
					+ "Kaum kontrollierte Beobachtungen (Redundanzanteil unter 5 %) sind nicht pr\u00FCfbar und z\u00E4hlen nicht mit.\n"
					+ "Erlaubt: ") + Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_UPPER_LIMIT
					+ tr(" m, sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_DEFAULT + " m."));
			Runnable apply = () -> {
				double enteredValue;
				try {
					enteredValue = Double.parseDouble(field.getText().trim().replace(",", "."));
				} catch (NumberFormatException e) {
					enteredValue = Double.NaN;
				}
				double previousValue = parameter.getGrossErrorLinearLimit();
				double storedValue = parameter.setGrossErrorLinearLimit(enteredValue);
				field.setText(Klein_Method_Format.format(storedValue, "", 3));
				if (Double.isNaN(enteredValue) || storedValue != enteredValue) {
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Wert zur\u00FCckgesetzt"));
					alert.setHeaderText(tr("Eingegebener Wert au\u00DFerhalb des erlaubten Bereichs"));
					alert.setContentText(tr("Die Grenze grober Fehler muss zwischen ") + Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_LOWER_LIMIT
							+ tr(" und ") + Klein_Method_Default_Values.GROSS_ERROR_LINEAR_LIMIT_UPPER_LIMIT + tr(" m liegen - auf ") + storedValue + tr(" m gesetzt."));
					alert.showAndWait();
				}
				if (storedValue != previousValue) {
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Grenze grober Fehler auf " + storedValue + " m gesetzt.");
					// Sperre mit der neuen Grenze neu pruefen
					Thread thread = new Thread(() -> {
						Klein_Method.getAdjustmentLock().lock();
						try {
							Klein_Method_Tab_Builder.this.kleinMethod.evaluateGrossErrorGate(Klein_Method_Tab_Builder.this.kleinMethod.getCurrentAdjustmentStep());
						} catch (SQLException e) {
							Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Pruefung auf grobe Fehler nicht moeglich: " + e.getMessage());
						} finally {
							Klein_Method.getAdjustmentLock().unlock();
						}
					}, "Klein_Method_Gross_Error_Gate");
					thread.setDaemon(true);
					thread.start();
				}
			};
			field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					apply.run();
			});
			field.setOnAction(event -> apply.run());
			return field;
		}
		private TextField createFunnelFloorField(Klein_Method_Parameter parameter) {
			TextField field = new TextField(Klein_Method_Format.format(parameter.getFunnelFloorLogPost(), "", 2));
			field.setPrefWidth(100);
			field.setTooltip(new Tooltip(tr("Untergrenze des Bearbeitungsbands in Phase 2, sobald sich der Trichter unten \u00F6ffnet.\n"
					+ "Beobachtungen zwischen diesem Wert und dem logPost-Grenzwert werden bereits mitkorrigiert, damit sie nicht knapp "
					+ "\u00FCber den Grenzwert zur\u00FCckrutschen. Das Ende der Automatik (Max-logPost unter dem Grenzwert) bleibt unver\u00E4ndert.\n"
					+ "Erlaubt: ") + Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_UPPER_LIMIT
					+ tr(", sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_DEFAULT + "."));

			Runnable apply = () -> {
				String enteredText = field.getText();
				double enteredValue;
				try {
					enteredValue = Double.parseDouble(enteredText.trim().replace(",", "."));
				} catch (NumberFormatException e) {
					enteredValue = Double.NaN;
				}
				double previousValue = parameter.getFunnelFloorLogPost();
				double storedValue = parameter.setFunnelFloorLogPost(enteredValue);
				field.setText(Klein_Method_Format.format(storedValue, "", 2));

				boolean reset = Double.isNaN(enteredValue) || storedValue != enteredValue;
				if (reset) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Trichter-Untergrenze Eingabe \"" + enteredText + "\" ausserhalb "
							+ "des erlaubten Bereichs - auf Default " + storedValue + " zurueckgesetzt.");
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Wert zur\u00FCckgesetzt"));
					alert.setHeaderText(tr("Eingegebener Wert au\u00DFerhalb des erlaubten Bereichs"));
					alert.setContentText(tr("Der Wert f\u00FCr \"Trichter-Untergrenze (logPost)\" lag au\u00DFerhalb von ")
							+ Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.FUNNEL_FLOOR_LOG_POST_UPPER_LIMIT
							+ tr(" und wurde auf den Standardwert ") + storedValue + tr(" zur\u00FCckgesetzt."));
					alert.showAndWait();
				}
				if (reset || storedValue != previousValue)
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			};
			field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					apply.run();
			});
			field.setOnAction(event -> apply.run());
			return field;
		}
		/**
		 * in der dynamischen AGL zeigen die VKS-Band-Felder das feste Band 0,996/1,004 ("VKS-Band dynamisch Min/Max",
		 * nicht editierbar, nichts gespeichert). In der freien/festen AGL wieder die gespeicherten Werte (editierbar).
		 * keine Sprungwert-Felder mehr.
		 */
		private void applyDynamicThresholdFields(boolean dynamic) {
			if (Klein_Method_Tab_Builder.this.vksBandMinField == null || Klein_Method_Tab_Builder.this.vksBandMaxField == null)
				return;
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			Klein_Method_Tab_Builder.this.vksBandDynamicMode = dynamic;
			if (dynamic) {
				Klein_Method_Tab_Builder.this.vksBandMinLabel.setText(tr("VKS-Band dynamisch Min (s\u00B2):"));
				Klein_Method_Tab_Builder.this.vksBandMaxLabel.setText(tr("VKS-Band dynamisch Max (s\u00B2):"));
				Klein_Method_Tab_Builder.this.vksBandMinField.setText(Klein_Method_Format.format(Klein_Method_Default_Values.DYNAMIC_BAND_MIN, "", 3));
				Klein_Method_Tab_Builder.this.vksBandMaxField.setText(Klein_Method_Format.format(Klein_Method_Default_Values.DYNAMIC_BAND_MAX, "", 3));
				String fixedTooltip = tr("Dynamische AGL: festes Zielband 0,996 bis 1,004 (Ziel 1,000), nicht editierbar.\n"
						+ "Phase 1 bewertet die VKS je Dimension, Phase 2 T_prio je Festpunkt.\n"
						+ "Sprungwerte fest: 1D 0,10 mm, 2D/3D 0,50 mm (Wert <= Sprungwert -> Untergrenze).\n"
						+ "Untergrenze der Festpunkte einstellbar (Standard Lage/2D/3D 1,0 mm, 1D 0,3 mm).");
				Klein_Method_Tab_Builder.this.vksBandMinField.setTooltip(new Tooltip(fixedTooltip));
				Klein_Method_Tab_Builder.this.vksBandMaxField.setTooltip(new Tooltip(fixedTooltip));
			} else {
				Klein_Method_Tab_Builder.this.vksBandMinLabel.setText(tr("VKS-Band Min (s\u00B2):"));
				Klein_Method_Tab_Builder.this.vksBandMaxLabel.setText(tr("VKS-Band Max (s\u00B2):"));
				Klein_Method_Tab_Builder.this.vksBandMinField.setText(Klein_Method_Format.format(parameter.getVksBandMin(), "", 3));
				Klein_Method_Tab_Builder.this.vksBandMaxField.setText(Klein_Method_Format.format(parameter.getVksBandMax(), "", 3));
				Klein_Method_Tab_Builder.this.vksBandMinField.setTooltip(new Tooltip(this.buildVksBandTooltip(true)));
				Klein_Method_Tab_Builder.this.vksBandMaxField.setTooltip(new Tooltip(this.buildVksBandTooltip(false)));
			}
			for (TextField field : new TextField[] { Klein_Method_Tab_Builder.this.vksBandMinField, Klein_Method_Tab_Builder.this.vksBandMaxField }) {
				field.setEditable(!dynamic);
				field.setFocusTraversable(!dynamic);
				field.setStyle(dynamic ? "-fx-background-color: " + UI_GREY + "; -fx-text-fill: black;" : "");
			}
		}
		private String buildVksBandTooltip(boolean isMin) {
			return isMin
					? tr("Untergrenze des Zielkorridors der Gruppen-D\u00E4mpfung (a-posteriori Varianzfaktor s\u00B2).\n"
							+ "F < Min: sigma_a/b/c wird per Ur-Formel reduziert.\n"
							+ "Erlaubt: ") + Klein_Method_Default_Values.VKS_BAND_MIN_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.VKS_BAND_MIN_UPPER_LIMIT
							+ tr(", sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.VKS_BAND_MIN_DEFAULT + "."
					: tr("Obergrenze des Zielkorridors der Gruppen-D\u00E4mpfung (a-posteriori Varianzfaktor s\u00B2).\n"
							+ "F > Max: sigma_a/b/c wird per Ur-Formel erh\u00F6ht.\n"
							+ "Erlaubt: ") + Klein_Method_Default_Values.VKS_BAND_MAX_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.VKS_BAND_MAX_UPPER_LIMIT
							+ tr(", sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.VKS_BAND_MAX_DEFAULT + ".";
		}
		private TextField createVksBandField(Klein_Method_Parameter parameter, boolean isMin) {
			double currentValue = isMin ? parameter.getVksBandMin() : parameter.getVksBandMax();
			TextField field = new TextField(Klein_Method_Format.format(currentValue, "", 3));
			field.setPrefWidth(100);
			field.setTooltip(new Tooltip(isMin
					? tr("Untergrenze des Zielkorridors der Gruppen-D\u00E4mpfung (a-posteriori Varianzfaktor s\u00B2).\n"
							+ "F < Min: sigma_a/b/c wird per Ur-Formel reduziert.\n"
							+ "Erlaubt: ") + Klein_Method_Default_Values.VKS_BAND_MIN_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.VKS_BAND_MIN_UPPER_LIMIT
							+ tr(", sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.VKS_BAND_MIN_DEFAULT + "."
					: tr("Obergrenze des Zielkorridors der Gruppen-D\u00E4mpfung (a-posteriori Varianzfaktor s\u00B2).\n"
							+ "F > Max: sigma_a/b/c wird per Ur-Formel erh\u00F6ht.\n"
							+ "Erlaubt: ") + Klein_Method_Default_Values.VKS_BAND_MAX_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.VKS_BAND_MAX_UPPER_LIMIT
							+ tr(", sonst R\u00FCcksetzung auf ") + Klein_Method_Default_Values.VKS_BAND_MAX_DEFAULT + "."));

			Runnable apply = () -> {
				// in der dynamischen AGL zeigt das Feld das feste Band 0,996/1,004 (nicht editierbar) - der gespeicherte
				// Wert der freien AGL bleibt dann unberuehrt.
				if (Klein_Method_Tab_Builder.this.vksBandDynamicMode || !field.isEditable())
					return;
				String fieldLabel = isMin ? "VKS-Band Min (s\u00B2)" : "VKS-Band Max (s\u00B2)";
				String enteredText = field.getText();
				double enteredValue;
				try {
					enteredValue = Double.parseDouble(enteredText.trim().replace(",", "."));
				} catch (NumberFormatException e) {
					enteredValue = Double.NaN; // nicht lesbar -> wie "ausserhalb" behandeln
				}
				double previousValue = isMin ? parameter.getVksBandMin() : parameter.getVksBandMax();
				double storedValue = isMin ? parameter.setVksBandMin(enteredValue) : parameter.setVksBandMax(enteredValue);
				field.setText(Klein_Method_Format.format(storedValue, "", 3));

				if (Double.isNaN(enteredValue) || storedValue != enteredValue) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: " + fieldLabel + " Eingabe \"" + enteredText + "\" ausserhalb "
							+ "des erlaubten Bereichs - auf Default " + storedValue + " zurueckgesetzt.");
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Wert zur\u00FCckgesetzt"));
					alert.setHeaderText(tr("Eingegebener Wert au\u00DFerhalb des erlaubten Bereichs"));
					alert.setContentText(tr("Der Wert f\u00FCr \"") + fieldLabel + tr("\" lag au\u00DFerhalb von ")
							+ (isMin ? Klein_Method_Default_Values.VKS_BAND_MIN_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.VKS_BAND_MIN_UPPER_LIMIT
									: Klein_Method_Default_Values.VKS_BAND_MAX_LOWER_LIMIT + tr(" bis ") + Klein_Method_Default_Values.VKS_BAND_MAX_UPPER_LIMIT)
							+ tr(" und wurde auf den Standardwert ") + storedValue + tr(" zur\u00FCckgesetzt."));
					alert.showAndWait();
				}
				if (storedValue != previousValue || Double.isNaN(enteredValue) || storedValue != enteredValue)
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			};
			field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
				if (!isNowFocused)
					apply.run();
			});
			field.setOnAction(event -> apply.run());
			return field;
		}
		/**
		 * Platzsparende Darstellung der 5x3=15 editierbaren Gruppenunsicherheits-
		 * Grenzwerte (siehe Klein_Method_Parameter.getGroupUncertaintyFloorA()/B()/C()):
		 * EIN Typ-Auswahlmenue + 3 wiederverwendete Felder (sigma_a/sigma_b/sigma_c)
		 * statt 30 einzelner Felder. Die 3 Felder zeigen/editieren IMMER die Werte
		 * der AKTUELL gewaehlten Geraeteklasse (aendert sich automatisch mit, wenn
		 * die Geraeteklasse in "Optionen" umgeschaltet wird) - Einheiten-Beschriftung
		 * wechselt beim Typwechsel automatisch mit (mgon/mm/ppm je nach Typ+Komponente).
		 */
		private VBox createGroupUncertaintySection() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();

			ComboBox<Klein_Method_Threshold_Type> typeComboBox = new ComboBox<Klein_Method_Threshold_Type>();
			typeComboBox.getItems().addAll(Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST));
			typeComboBox.setValue(typeComboBox.getItems().get(0));
			typeComboBox.setConverter(new StringConverter<Klein_Method_Threshold_Type>() {
				@Override
				public String toString(Klein_Method_Threshold_Type type) {
					// "... logPost" weglassen, hier geht es nicht um den logPost-Grenzwert
					return type == null ? "" : tr(type.getDisplayName()).replace(" logPost", "");
				}
				@Override
				public Klein_Method_Threshold_Type fromString(String string) {
					return null; // nicht editierbar, nur Auswahl
				}
			});
			typeComboBox.setTooltip(new Tooltip(tr("Beobachtungstyp ausw\u00E4hlen, um dessen Gruppenunsicherheits-Grenzwerte (sigma_a/sigma_b/sigma_c) "
					+ "anzuzeigen bzw. zu editieren. Zeigt IMMER die Werte der aktuell in \"Optionen\" gew\u00E4hlten Ger\u00E4teklasse.")));

			Label sigmaALabel = new Label(tr("sigma_a:"));
			TextField sigmaAField = new TextField();
			sigmaAField.setPrefWidth(90);

			Label sigmaBLabel = new Label(tr("sigma_b(\u221Ad):"));
			TextField sigmaBField = new TextField();
			sigmaBField.setPrefWidth(90);

			Label sigmaCLabel = new Label(tr("sigma_c(d):"));
			TextField sigmaCField = new TextField();
			sigmaCField.setPrefWidth(90);

			Runnable refreshFields = () -> {
				Klein_Method_Threshold_Type selectedType = typeComboBox.getValue();
				sigmaAField.setText(String.valueOf(parameter.getGroupUncertaintyFloorA(selectedType)));
				sigmaBField.setText(String.valueOf(parameter.getGroupUncertaintyFloorB(selectedType)));
				sigmaCField.setText(String.valueOf(parameter.getGroupUncertaintyFloorC(selectedType)));
				sigmaALabel.setText(tr("sigma_a [") + Klein_Method_Default_Values.getSigmaAUnit(selectedType) + "]:");
				sigmaBLabel.setText(tr("sigma_b(\u221Ad) [") + Klein_Method_Default_Values.getSigmaBUnit(selectedType) + "]:");
				sigmaCLabel.setText(tr("sigma_c(d) [") + Klein_Method_Default_Values.getSigmaCUnit(selectedType) + "]:");
			};
			refreshFields.run(); // Anfangszustand

			typeComboBox.setOnAction(event -> refreshFields.run());

			sigmaAField.focusedProperty().addListener((obs, was, isNow) -> {
				if (!isNow) this.applyGroupUncertaintyFieldValue(parameter, typeComboBox.getValue(), 'a', sigmaAField);
			});
			sigmaAField.setOnAction(event -> this.applyGroupUncertaintyFieldValue(parameter, typeComboBox.getValue(), 'a', sigmaAField));

			sigmaBField.focusedProperty().addListener((obs, was, isNow) -> {
				if (!isNow) this.applyGroupUncertaintyFieldValue(parameter, typeComboBox.getValue(), 'b', sigmaBField);
			});
			sigmaBField.setOnAction(event -> this.applyGroupUncertaintyFieldValue(parameter, typeComboBox.getValue(), 'b', sigmaBField));

			sigmaCField.focusedProperty().addListener((obs, was, isNow) -> {
				if (!isNow) this.applyGroupUncertaintyFieldValue(parameter, typeComboBox.getValue(), 'c', sigmaCField);
			});
			sigmaCField.setOnAction(event -> this.applyGroupUncertaintyFieldValue(parameter, typeComboBox.getValue(), 'c', sigmaCField));

			// Wenn die Geraeteklasse anderswo (Optionen) gewechselt wird, muessen
			// diese Felder ebenfalls aktualisiert werden - siehe updateGroupUncertaintyFieldsOnDeviceClassChange()
			Klein_Method_Tab_Builder.this.groupUncertaintyFieldsRefresher = refreshFields;

			HBox row = new HBox(10, new Label(tr("Typ:")), typeComboBox, sigmaALabel, sigmaAField, sigmaBLabel, sigmaBField, sigmaCLabel, sigmaCField);
			VBox section = new VBox(10, row);
			section.setPadding(new Insets(10, 0, 0, 0));
			return section;
		}
		/**
		 * Wird IMMER vom JavaFX-UI-Thread aus aufgerufen (focusedProperty-Listener bzw. TextField.setOnAction), daher
		 * OHNE Platform.runLater() - anders als die Alerts in Klein_Method.java, die teils aus einem Hintergrund-Thread
		 * stammen.
		 */
		private void showMinimumCorrectedAlert(String fieldLabel, double enteredValue, double correctedValue) {
			Alert alert = new Alert(AlertType.INFORMATION);
			alert.setTitle(tr("Klein_Method - Wert korrigiert"));
			alert.setHeaderText(tr("Eingegebener Wert au\u00DFerhalb des erlaubten Bereichs"));
			alert.setContentText(tr("Der Wert f\u00FCr \"") + fieldLabel + tr("\" war ") + enteredValue
					+ tr(" und wurde automatisch auf ") + correctedValue + tr(" korrigiert."));
			alert.showAndWait();
		}
		private void applyGroupUncertaintyFieldValue(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, char component, TextField field) {
			try {
				double enteredValue = Double.parseDouble(field.getText().replace(",", "."));

				// Mindestwert ist der dokumentierte Default/Seed-Wert der aktuellen Geraeteklasse (siehe
				// Klein_Method_Default_Values.getGroupUncertaintyFloor()) - alle 3 Komponenten starten mit demselben
				// Wert, daher hier fuer alle 3 gleich verwendet.
				double minimumAllowed = Klein_Method_Default_Values.getGroupUncertaintyFloor(type, parameter.getDeviceClass());
				double correctedValue = Math.max(enteredValue, minimumAllowed);

				switch (component) {
				case 'a': parameter.setGroupUncertaintyFloorA(type, correctedValue); break;
				case 'b': parameter.setGroupUncertaintyFloorB(type, correctedValue); break;
				case 'c': parameter.setGroupUncertaintyFloorC(type, correctedValue); break;
				}

				if (correctedValue != enteredValue) {
					field.setText(String.valueOf(correctedValue));
					String componentLabel = "sigma_" + component + " " + type.getDisplayName().replace(" logPost", "");
					String componentLabelUi = "sigma_" + component + " " + tr(type.getDisplayName()).replace(" logPost", "");
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log(
							"Klein_Method: Gruppenunsicherheits-Grenzwert '" + componentLabel + "' war " + enteredValue
							+ ", auf Mindestwert " + correctedValue + " korrigiert.");
					this.showMinimumCorrectedAlert(componentLabelUi, enteredValue, correctedValue);
				}
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			} catch (NumberFormatException e) {
				double currentValue;
				switch (component) {
				case 'a': currentValue = parameter.getGroupUncertaintyFloorA(type); break;
				case 'b': currentValue = parameter.getGroupUncertaintyFloorB(type); break;
				default:  currentValue = parameter.getGroupUncertaintyFloorC(type); break;
				}
				field.setText(String.valueOf(currentValue));
			}
		}
		private void applySharedLogPostFieldValue(Klein_Method_Parameter parameter, TextField field) {
			try {
				double enteredValue = Double.parseDouble(field.getText().replace(",", "."));
				double correctedValue = parameter.setSharedLogPostThreshold(enteredValue);

				if (correctedValue != enteredValue) {
					field.setText(String.valueOf(correctedValue));
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log(
							"Klein_Method: gemeinsamer logPost-Grenzwert war " + enteredValue
							+ ", auf Mindestwert " + correctedValue + " korrigiert.");
					this.showMinimumCorrectedAlert("logPost (gemeinsam)", enteredValue, correctedValue);
				}
				Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability();
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			} catch (NumberFormatException e) {
				field.setText(String.valueOf(parameter.getSharedLogPostThreshold()));
			}
		}
		private void applyFieldValue(Klein_Method_Parameter parameter, Klein_Method_Threshold_Type type, TextField field) {
			try {
				double enteredValue = Double.parseDouble(field.getText().replace(",", "."));
				double correctedValue = parameter.setThresholdValue(type, enteredValue);

				if (correctedValue != enteredValue) {
					field.setText(String.valueOf(correctedValue));
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log(
							"Klein_Method: Grenzwert '" + type.getDisplayName() + "' war " + enteredValue
							+ ", auf Mindestwert " + correctedValue + " korrigiert.");
					this.showMinimumCorrectedAlert(tr(type.getDisplayName()), enteredValue, correctedValue);
				}
				Klein_Method_Tab_Builder.this.updateEvaluationMethodAvailability();
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			} catch (NumberFormatException e) {
				field.setText(String.valueOf(parameter.getThresholdValue(type)));
			}
		}	}

	// ============================================================================================================
	/** Naeherungskoordinaten und Qualitaetsfelder. */
	private final class Approximation_Part {

		/**
		 * Naeherungskoordinaten (nur freie AGL): Toleranzen (Expertenmodus), Pruefung auf Anforderung, Ruecknahme der
		 * letzten Uebernahme. Die automatische Pruefung laeuft beim Oeffnen des Tabs.
		 */
		private VBox buildApproximationBox() {
			Klein_Method_Parameter p = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			Label title = new Label(tr("N\u00E4herungskoordinaten (freie AGL)"));
			title.setStyle("-fx-font-weight: bold;");
			title.setTooltip(new Tooltip(tr("Beim \u00D6ffnen des Tabs rechnet Klein_Method in der freien Ausgleichung eigene N\u00E4herungskoordinaten der Neupunkte\n"
					+ "(Startwerte aus polaren Bestimmungen, Schnitten und Standpunktsystemen, danach robuste Vorausgleichung aller\n"
					+ "Beobachtungen, gelagert auf die Altpunkte) und vergleicht sie mit den vorhandenen. Weichen sie ab, erscheint ein\n"
					+ "Fenster mit Empfehlung und Begr\u00FCndung. Gepr\u00FCft wird einmal je Datenstand.")));
			GridPane grid = new GridPane();
			grid.setHgap(8);
			grid.setVgap(4);
			int row = 0;
			row = this.addQualityField(grid, row, tr("Toleranz Lage (m):"), p::getApproximationTolerancePosition, p::setApproximationTolerancePosition, 3,
					tr("Weicht die vorhandene Lage eines Neupunkts um mehr als diesen Wert von der neu berechneten ab, erscheint das Fenster\n"
							+ "\u201EN\u00E4herungskoordinaten\u201C. Kleinere Abweichungen st\u00F6ren die Ausgleichung nicht.\n"
							+ "Standard 0,050 m, erlaubt 0,005 bis 5,000 m, 0 = Lage nicht pr\u00FCfen.\n"
							+ "Neupunkte mit 0,000 (kein plausibler Wert) werden unabh\u00E4ngig davon immer zur \u00DCbernahme angeboten."));
			row = this.addQualityField(grid, row, tr("Toleranz H\u00F6he (m):"), p::getApproximationToleranceHeight, p::setApproximationToleranceHeight, 3,
					tr("Wie die Toleranz Lage, f\u00FCr die H\u00F6he der Neupunkte.\n"
							+ "Standard 0,050 m, erlaubt 0,005 bis 5,000 m, 0 = H\u00F6he nicht pr\u00FCfen.\n"
							+ "Neupunkte mit 0,000 (kein plausibler Wert) werden unabh\u00E4ngig davon immer zur \u00DCbernahme angeboten."));
			Button checkButton = new Button(tr("N\u00E4herungskoordinaten jetzt pr\u00FCfen"));
			checkButton.setTooltip(new Tooltip(tr("Rechnet die N\u00E4herungskoordinaten sofort neu und vergleicht sie mit den vorhandenen -\n"
					+ "auch wenn sich seit der letzten Pr\u00FCfung nichts ge\u00E4ndert hat. Nur in der freien Ausgleichung.")));
			checkButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.checkApproximationsOnDemand());
			Button undoButton = new Button(tr("Letzte \u00DCbernahme zur\u00FCcknehmen"));
			undoButton.setTooltip(new Tooltip(tr("Setzt die N\u00E4herungskoordinaten der zuletzt \u00FCbernommenen Neupunkte auf die vorher gesicherten Werte zur\u00FCck.")));
			undoButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.restoreApproximationsOnDemand());
			// Rangfolge der Beobachtungsarten
			CheckBox priorityCheckBox = new CheckBox(tr("Rangfolge: Nivellement > Tachymeter > GNSS"));
			priorityCheckBox.setSelected(p.isApproximationPriority());
			priorityCheckBox.setTooltip(new Tooltip(tr("Ein (Standard): Startwerte der Höhe zuerst aus Nivellement, dann aus trigonometrischen Höhen, GNSS-Höhen nur\n"
					+ "für Punkte, die sonst keine Höhe bekommen; Startwerte der Lage zuerst terrestrisch, dann GNSS.\n"
					+ "In der Höhe zählen wiederholte GNSS-Sessions zwischen denselben Punkten zusammen wie eine Basislinie,\n"
					+ "und die GNSS-Höhe bekommt die 3-fache Lageunsicherheit - viele Sessions überstimmen so nicht die terrestrischen Höhen.\n"
					+ "Aus: alle Beobachtungsarten gleichrangig (wie bis V0.417).")));
			priorityCheckBox.selectedProperty().addListener((obs, was, now) -> {
				p.setApproximationPriority(now);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Naeherungskoordinaten Rangfolge " + (now ? "ein" : "aus"));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			// Wiederholte Messungen (Uebergewicht) je Messart: Aus / Zusammenfassen / Regewichten
			Label repetitionTitle = new Label(tr("Wiederholte Messungen (Übergewicht):"));
			String repetitionTooltip = tr("Direkt aufeinanderfolgende Messungen desselben Standpunkts und Ziels (GNSS: Epochen einer Sitzung, Lasertracker: "
					+ "Messreihen) sind nicht unabhängig und wiegen als Einzelbeobachtungen etwa Wurzel(n)-fach zu schwer.\n"
					+ "Zusammenfassen: je Wiederholungsgruppe ein Mittel, Einzelwerte deaktiviert; Genauigkeit mindestens die Streuung "
					+ "der Gruppe, das Mittel wird normal kalibriert.\n"
					+ "Aus: alle Messungen einzeln wie importiert.\n"
					+ "Klein_Method wendet die Einstellung automatisch an (Tab-Klick, Änderung hier, Start der Automatik); Rücknahme jederzeit.") + "\n"
					+ tr("Zusammenfassen + Kalibrieren (Standard): wie Zusammenfassen; Mittel mit eigener Genauigkeit (stark streuend oder driftend) "
					+ "starten mit der Streuung und werden in der Automatik aus ihren Verbesserungen nachkalibriert (eigener Varianzfaktor).");
			repetitionTitle.setTooltip(new Tooltip(repetitionTooltip));
			GridPane repetitionGrid = new GridPane();
			repetitionGrid.setHgap(8);
			repetitionGrid.setVgap(4);
			int repetitionRow = 0;
			for (org.applied_geodesy.adjustment.network.ObservationType type : Klein_Method_Parameter.REPETITION_TYPES) {
				ComboBox<Integer> modeBox = new ComboBox<Integer>();
				modeBox.getItems().setAll(Klein_Method_Default_Values.REPETITION_MODE_MERGE, Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE,
						Klein_Method_Default_Values.REPETITION_MODE_OFF);
				modeBox.setConverter(new StringConverter<Integer>() {
					@Override
					public String toString(Integer value) {
						return value == null ? "" : Klein_Method.repetitionModeName(value);
					}
					@Override
					public Integer fromString(String string) {
						return null;
					}
				});
				modeBox.setValue(p.getRepetitionMode(type));
				modeBox.setTooltip(new Tooltip(repetitionTooltip));
				modeBox.valueProperty().addListener((obs, was, now) -> {
					if (now == null)
						return;
					p.setRepetitionMode(type, now);
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: wiederholte Messungen " + type.name() + " -> " + now);
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
					// nach der letzten Aenderung (mehrere Messarten nacheinander) einmal automatisch anwenden
					Klein_Method_Tab_Builder.this.repetitionApplyDelay.playFromStart();
				});
				Label typeLabel = new Label(Klein_Method.repetitionTypeName(type) + ":");
				typeLabel.setTooltip(modeBox.getTooltip());
				repetitionGrid.add(typeLabel, 0, repetitionRow);
				repetitionGrid.add(modeBox, 1, repetitionRow);
				repetitionRow++;
			}
			Button mergeButton = new Button(tr("Jetzt anwenden …"));
			mergeButton.setTooltip(new Tooltip(tr("VERÄNDERT DAS PROJEKT (nach Rückfrage): wiederholte Messungen je Messart wie eingestellt regewichten bzw. "
					+ "zusammenfassen (bei geänderter Einstellung vorher die bisherige Behandlung zurücknehmen).")));
			mergeButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.mergeGnssSessionsOnDemand());
			Button mergeUndoButton = new Button(tr("Alles zurücknehmen …"));
			mergeUndoButton.setTooltip(new Tooltip(tr("Angelegte Mittel löschen, Einzelwerte wieder aktivieren und regewichtete Unsicherheiten "
					+ "zurücksetzen (nach Rückfrage).")));
			mergeUndoButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.undoGnssSessionMergeOnDemand());
			CheckBox massCheckBox = new CheckBox(tr("Massen-Basislinien automatisch ausdünnen"));
			massCheckBox.setSelected(p.isMassThinning());
			massCheckBox.setTooltip(new Tooltip(tr("Erkennt sehr viele GNSS-Basislinien (z. B. Epochen alle 1-5 s über 30 min zu mehreren SAPOS-Stationen): mehr als "
					+ "20 000 oder im Mittel mindestens 20 je Punktpaar.\nEin (Standard): vor der ersten Berechnung je Punktpaar ein robustes Mittel - Median, "
					+ "Epochen über 3 Streuungen werden verworfen, Genauigkeit nicht durch Wurzel(n) geteilt; Einzelwerte deaktiviert, Rücknahme jederzeit.\n"
					+ "Aus: die Ausgleichung bleibt bei mehr als 20 000 aktiven Basislinien gesperrt, bis im Ablauf-Assistenten „Jetzt ausdünnen“ gewählt wird.")));
			massCheckBox.selectedProperty().addListener((obs, was, now) -> {
				p.setMassThinning(now);
				Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Massen-Basislinien automatisch ausduennen " + (now ? "ein" : "aus"));
				Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			});
			VBox mergeRow = new VBox(4, repetitionTitle, repetitionGrid, massCheckBox);
			HBox mergeButtons = new HBox(8, mergeButton, mergeUndoButton);
			VBox box = new VBox(6, title, grid, priorityCheckBox, mergeRow, mergeButtons, checkButton, undoButton);
			Klein_Method_Tab_Builder.this.markExpertOnly(grid);
			Klein_Method_Tab_Builder.this.freeOnlyNodes.add(box);
			box.setVisible(false);
			box.setManaged(false);
			return box;
		}
		/** Eingabefeld eines Qualitaetswerts; ungueltig -> Standard (Pruefung im Parameter), Hinweis, Speichern. */
		private int addQualityField(GridPane grid, int row, String labelText, java.util.function.DoubleSupplier getter,
				java.util.function.DoubleUnaryOperator setter, int decimals, String tooltip) {
			TextField field = new TextField(Klein_Method_Format.format(getter.getAsDouble(), "", decimals));
			field.setPrefWidth(100);
			field.setTooltip(new Tooltip(tooltip));
			Label label = new Label(labelText);
			label.setTooltip(field.getTooltip());
			Runnable apply = () -> {
				String entered = field.getText() == null ? "" : field.getText().trim().replace(",", ".");
				double value;
				try {
					value = Double.parseDouble(entered);
				} catch (NumberFormatException e) {
					value = Double.NaN;
				}
				double before = getter.getAsDouble();
				double stored = setter.applyAsDouble(value);
				field.setText(Klein_Method_Format.format(stored, "", decimals));
				if (Double.isNaN(value) || stored != value) {
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Wert zur\u00FCckgesetzt"));
					alert.setHeaderText(labelText);
					alert.setContentText(tr("Eingabe au\u00DFerhalb des erlaubten Bereichs - Standardwert gesetzt.") + "\n\n" + tooltip);
					alert.show();
				}
				if (stored != before) {
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: " + labelText + " " + stored);
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
				}
			};
			field.setOnAction(event -> apply.run());
			field.focusedProperty().addListener((obs, was, now) -> {
				if (!now)
					apply.run();
			});
			grid.add(label, 0, row);
			grid.add(field, 1, row);
			Klein_Method_Tab_Builder.this.markFreeFixedOnly(label, field);
			return row + 1;
		}	}

	// ============================================================================================================
	/** Schwarmwissen und Festpunkt-Empfehlung. */
	private final class Swarm_Part {

		/** Bereich "Schwarmwissen" - Erfahrungswerte, Startwerte, Abruf, Teilen, Einstellungen. */
		private VBox createSwarmSection() {
			Klein_Method_Parameter parameter = Klein_Method_Tab_Builder.this.kleinMethod.getParameter();
			Klein_Method_Tab_Builder.this.swarmOverviewArea = new javafx.scene.control.TextArea("Erfahrungswerte werden geladen ...");
			Klein_Method_Tab_Builder.this.swarmOverviewArea.setEditable(false);
			Klein_Method_Tab_Builder.this.swarmOverviewArea.setWrapText(false);
			Klein_Method_Tab_Builder.this.swarmOverviewArea.setPrefRowCount(9);
			Klein_Method_Tab_Builder.this.swarmOverviewArea.setStyle("-fx-font-family: monospace;");
			Klein_Method_Tab_Builder.this.swarmOverviewArea.setTooltip(new Tooltip(tr("Erfahrungswerte der Gruppenunsicherheiten je Beobachtungstyp, Ger\u00E4teklasse und \u03C3a/\u03C3b/\u03C3c:\n"
					+ "Median und Anzahl Projekte (eigene L\u00E4ufe mit DONE in freier/fixer Ausgleichung plus zuletzt abgerufener Schwarm).\n"
					+ "Jedes Projekt z\u00E4hlt mit seinem letzten DONE-Lauf. Rechte Spalte: m\u00F6glicher Startwert nach der 10-%-Regel\n"
					+ "(nur andere Projekte, untereinander h\u00F6chstens 10 % Unterschied, mindestens 3 Projekte).")));

			Button startValuesButton = new Button(tr("Erfahrungswerte als Startwerte \u00FCbernehmen"));
			startValuesButton.setTooltip(new Tooltip(tr("Schreibt Erfahrungswerte als Gruppenunsicherheit (Startwerte f\u00FCr Phase 1) - erst nach Vorschau und \u201E\u00DCbernehmen\u201C.\n"
					+ "Dynamische Ausgleichung: Startwert je Dimension f\u00FCr alle beweglichen Anschlusspunkte (nur gleiche Festpunktart).\n"
					+ "Nur Werte aus ANDEREN Projekten, die sich untereinander h\u00F6chstens 10 % unterscheiden (Gruppe um den Median, mindestens 3 Projekte).\n"
					+ "Abweichende Projekte werden weggelassen und in der Vorschau genannt; ohne passende Gruppe bleibt der Wert unver\u00E4ndert.\n"
					+ "Deaktivierte Komponenten (0) bleiben 0, Untergrenzen werden eingehalten. Danach wird das Netz neu ausgeglichen.")));
			startValuesButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.startSwarmStartValues());

			Button downloadButton = new Button(tr("Erfahrungswerte abrufen (Schwarm)"));
			downloadButton.setTooltip(new Tooltip(tr("L\u00E4dt die Erfahrungswerte des Schwarms aus dem \u00F6ffentlichen GitHub-Repository (nur lesend, ohne Schl\u00FCssel):\n"
					+ "Datei ") + Klein_Method_Functions.Klein_Method_Swarm.SWARM_FILE + tr(" im Zweig main. Ben\u00F6tigt eine Internetverbindung.")));
			downloadButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.downloadSwarmExperience());

			Klein_Method_Tab_Builder.this.swarmShareButton = new Button(tr("Laufbericht teilen"));
			Klein_Method_Tab_Builder.this.swarmShareButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.shareLatestRunReport());

			Button folderButton = new Button(tr("Berichtsordner \u00F6ffnen"));
			folderButton.setTooltip(new Tooltip(tr("\u00D6ffnet den Ordner mit den eigenen Laufberichten: ")
					+ Klein_Method_Functions.Klein_Method_Swarm.reportFolder().getAbsolutePath() + tr("\nJeder Automatik-Lauf schreibt dort einen Bericht (keine Koordinaten, keine Namen).")));
			folderButton.setOnAction(event -> {
				java.io.File folder = Klein_Method_Functions.Klein_Method_Swarm.reportFolder();
				folder.mkdirs();
				Thread thread = new Thread(() -> {
					try {
						java.awt.Desktop.getDesktop().open(folder);
					} catch (Exception e) {
						Platform.runLater(() -> Klein_Method_Tab_Builder.this.status.setText(tr("Ordner nicht \u00F6ffenbar: ") + folder.getAbsolutePath()));
					}
				}, "Klein_Method_Open_Report_Folder");
				thread.setDaemon(true);
				thread.start();
			});

			TextField repositoryField = new TextField(parameter.getSwarmRepository());
			repositoryField.setPrefColumnCount(26);
			repositoryField.setTooltip(new Tooltip(tr("GitHub-Repository f\u00FCr das Schwarmwissen als \u201EKonto/Repository\u201C, Standard ")
					+ Klein_Method_Functions.Klein_Method_Swarm.DEFAULT_REPOSITORY + tr(".\n"
					+ "Hier \u00E4ndern, wenn Konto oder Repository auf GitHub anders hei\u00DFen oder umbenannt wurden.\n"
					+ "Ein \u00F6ffentliches Repository ist ohne Token lesbar. Ein eigener Token (Datei ") + Klein_Method_Functions.Klein_Method_Swarm.tokenFile().getAbsolutePath()
					+ tr(") ist nur zum direkten Senden n\u00F6tig und muss f\u00FCr dieses Repository freigegeben sein.")));
			Runnable applyRepository = () -> {
				String previous = parameter.getSwarmRepository();
				String stored = parameter.setSwarmRepository(repositoryField.getText());
				if (!stored.equals(repositoryField.getText().trim())) {
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Schwarmwissen"));
					alert.setHeaderText(tr("Repository-Name ung\u00FCltig"));
					alert.setContentText(tr("Bitte im Format \u201EKonto/Repository\u201C eingeben (Buchstaben, Ziffern, - . _). Zur\u00FCckgesetzt auf ") + stored + ".");
					alert.showAndWait();
				}
				repositoryField.setText(stored);
				if (!stored.equals(previous)) {
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Schwarmwissen-Repository auf " + stored + " gesetzt.");
					this.refreshSwarmView();
				}
			};
			repositoryField.focusedProperty().addListener((obs, was, now) -> {
				if (!now)
					applyRepository.run();
			});
			repositoryField.setOnAction(event -> applyRepository.run());
			Label repositoryLabel = new Label(tr("Repository:"));
			repositoryLabel.setTooltip(repositoryField.getTooltip());

			TextField relayField = new TextField(parameter.getSwarmRelayUrl());
			relayField.setPrefColumnCount(30);
			relayField.setTooltip(new Tooltip(tr("Vermittlungsdienst zum Senden der Laufberichte ohne eigenen Token, Standard ")
					+ Klein_Method_Functions.Klein_Method_Swarm.DEFAULT_RELAY_URL + tr(".\n"
					+ "Der Dienst h\u00E4lt den Token gesch\u00FCtzt, pr\u00FCft jeden Bericht und legt ihn als Issue im Repository an.\n"
					+ "Mit Token-Datei wird direkt gesendet, der Dienst wird dann nicht benutzt.\n"
					+ "Leer = kein Dienst: Senden dann nur mit Token-Datei oder mit einem Klick im Browser (GitHub-Konto n\u00F6tig).")));
			Runnable applyRelay = () -> {
				String previous = parameter.getSwarmRelayUrl();
				String entered = relayField.getText() == null ? "" : relayField.getText().trim();
				String stored = parameter.setSwarmRelayUrl(entered);
				if (!stored.equals(entered)) {
					Alert alert = new Alert(AlertType.INFORMATION);
					alert.setTitle(tr("Klein_Method - Schwarmwissen"));
					alert.setHeaderText(tr("Adresse des Vermittlungsdienstes ung\u00FCltig"));
					alert.setContentText(tr("Bitte eine Adresse mit https:// eingeben oder das Feld leeren. Bisheriger Wert bleibt: ") + (stored.isEmpty() ? tr("(leer)") : stored));
					alert.showAndWait();
				}
				relayField.setText(stored);
				if (!stored.equals(previous)) {
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
					Klein_Method_Tab_Builder.this.kleinMethod.getLogger().log("Klein_Method: Schwarmwissen-Vermittlungsdienst auf " + (stored.isEmpty() ? "(keiner)" : stored) + " gesetzt.");
					this.refreshSwarmView();
				}
			};
			relayField.focusedProperty().addListener((obs, was, now) -> {
				if (!now)
					applyRelay.run();
			});
			relayField.setOnAction(event -> applyRelay.run());
			Label relayLabel = new Label(tr("Vermittlungsdienst:"));
			relayLabel.setTooltip(relayField.getTooltip());

			TextField warnField = new TextField(Klein_Method_Format.format(parameter.getSwarmWarnFactor(), "", 1));
			warnField.setPrefWidth(100);
			warnField.setTooltip(new Tooltip(tr("Warnung nach Phase 1: liegt eine Gruppenunsicherheit mehr als dieser Faktor \u00FCber dem Erfahrungswert\n"
					+ "(Median anderer Projekte, mindestens 3), h\u00E4lt die Automatik an und fragt \u201EWeiter\u201C / \u201EAbbrechen\u201C.\n"
					+ "Standard ") + Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_DEFAULT + tr(", erlaubt ")
					+ Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_MIN + tr(" bis ") + Klein_Method_Functions.Klein_Method_Swarm.WARN_FACTOR_MAX + "."));
			Runnable applyWarn = () -> {
				double entered;
				try {
					entered = Double.parseDouble(warnField.getText().trim().replace(",", "."));
				} catch (NumberFormatException e) {
					entered = Double.NaN;
				}
				double previous = parameter.getSwarmWarnFactor();
				double stored = parameter.setSwarmWarnFactor(entered);
				warnField.setText(Klein_Method_Format.format(stored, "", 1));
				if (stored != previous)
					Klein_Method_Tab_Builder.this.kleinMethod.saveSettings();
			};
			warnField.focusedProperty().addListener((obs, was, now) -> {
				if (!now)
					applyWarn.run();
			});
			warnField.setOnAction(event -> applyWarn.run());
			Label warnLabel = new Label(tr("Warnfaktor nach Phase 1:"));
			warnLabel.setTooltip(warnField.getTooltip());

			// Festpunktart (nur dynamische AGL) - nur Gleiches wird mit Gleichem verglichen
			Klein_Method_Tab_Builder.this.swarmFixedPointClassBox = new ComboBox<Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class>();
			Klein_Method_Tab_Builder.this.swarmFixedPointClassBox.getItems().addAll(Klein_Method_Functions.Klein_Method_Swarm.Fixed_Point_Class.values());
			Klein_Method_Tab_Builder.this.swarmFixedPointClassBox.setValue(Klein_Method_Tab_Builder.this.kleinMethod.getSwarmFixedPointClass());
			Klein_Method_Tab_Builder.this.swarmFixedPointClassBox.setTooltip(new Tooltip(tr("Nur dynamische Ausgleichung: Art der Festpunkte (bewegliche Anschlusspunkte) dieses Projekts.\n"
					+ "GNSS-Referenz (z. B. SAPOS/Referenzstationen), TP/AP (Trigonometrische/Aufnahmepunkte), H\u00F6henfestpunkt, Sonstige.\n"
					+ "Erfahrungswerte, Startwerte und die Warnung nach Phase 2 vergleichen nur Projekte gleicher Festpunktart und Dimension.\n"
					+ "Wird im Projekt gespeichert.")));
			Klein_Method_Tab_Builder.this.swarmFixedPointClassBox.valueProperty().addListener((obs, oldValue, newValue) -> {
				if (!Klein_Method_Tab_Builder.this.ignoreSwarmClassEvents)
					Klein_Method_Tab_Builder.this.kleinMethod.setSwarmFixedPointClass(newValue);
			});
			Label fixedPointClassLabel = new Label(tr("Festpunktart:"));
			fixedPointClassLabel.setTooltip(Klein_Method_Tab_Builder.this.swarmFixedPointClassBox.getTooltip());
			Klein_Method_Tab_Builder.this.swarmFixedPointClassRow = new HBox(10, fixedPointClassLabel, Klein_Method_Tab_Builder.this.swarmFixedPointClassBox);
			Klein_Method_Tab_Builder.this.swarmFixedPointClassRow.setAlignment(Pos.CENTER_LEFT);
			setRowShown(Klein_Method_Tab_Builder.this.swarmFixedPointClassRow, false);

			HBox buttonRow = new HBox(10, startValuesButton, downloadButton, Klein_Method_Tab_Builder.this.swarmShareButton, folderButton);
			buttonRow.setAlignment(Pos.CENTER_LEFT);
			HBox settingsRow = new HBox(10, repositoryLabel, repositoryField, warnLabel, warnField);
			settingsRow.setAlignment(Pos.CENTER_LEFT);
			HBox relayRow = new HBox(10, relayLabel, relayField);
			relayRow.setAlignment(Pos.CENTER_LEFT);
			VBox section = new VBox(10, Klein_Method_Tab_Builder.this.swarmFixedPointClassRow, Klein_Method_Tab_Builder.this.swarmOverviewArea, buttonRow, settingsRow, relayRow);
			this.refreshSwarmView();
			return section;
		}
		/** Uebersicht und Teilen-Knopf neu aufbauen (Hintergrund-Thread, Anzeige im FX-Thread). */
		public void refreshSwarmView() {
			if (Klein_Method_Tab_Builder.this.swarmOverviewArea == null)
				return;
			boolean dynamic = Klein_Method_Tab_Builder.this.isDynamicStepVisible();
			if (Klein_Method_Tab_Builder.this.swarmFixedPointClassRow != null) {
				setRowShown(Klein_Method_Tab_Builder.this.swarmFixedPointClassRow, dynamic);
				Klein_Method_Tab_Builder.this.ignoreSwarmClassEvents = true;
				try {
					Klein_Method_Tab_Builder.this.swarmFixedPointClassBox.setValue(Klein_Method_Tab_Builder.this.kleinMethod.getSwarmFixedPointClass());
				} finally {
					Klein_Method_Tab_Builder.this.ignoreSwarmClassEvents = false;
				}
			}
			Thread thread = Klein_Method.busyThread(() -> {
				String text;
				try {
					text = Klein_Method_Tab_Builder.this.kleinMethod.buildSwarmOverviewText(dynamic);
				} catch (Exception e) {
					text = "Erfahrungswerte nicht lesbar: " + e.getMessage();
				}
				String route = Klein_Method_Functions.Klein_Method_Swarm.sendRoute(Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getSwarmRelayUrl());
				boolean hasReport = !Klein_Method_Functions.Klein_Method_Swarm.reportFiles().isEmpty();
				final String finalText = text;
				Platform.runLater(() -> {
					Klein_Method_Tab_Builder.this.swarmOverviewArea.setText(finalText);
					boolean enabled = hasReport;
					if (!Klein_Method_Tab_Builder.this.runLockActive)
						Klein_Method_Tab_Builder.this.swarmShareButton.setDisable(!enabled);
					Klein_Method_Tab_Builder.this.swarmShareButton.setTooltip(new Tooltip(tr("Sendet den neuesten eigenen Laufbericht nach Vorschau und \u201ESenden\u201C als \u00F6ffentliches Issue an ")
							+ Klein_Method_Tab_Builder.this.kleinMethod.getParameter().getSwarmRepository() + tr(".\nKeine Koordinaten, keine Punkt- oder Projektnamen.\n")
							+ ("Token".equals(route) ? tr("Gesendet wird direkt mit der Token-Datei.\n")
									: "Vermittlungsdienst".equals(route) ? tr("Gesendet wird \u00FCber den Vermittlungsdienst, kein Token n\u00F6tig.\n")
											: tr("Kein Token und kein Vermittlungsdienst: Senden mit einem Klick im Browser (GitHub-Konto n\u00F6tig).\n"))
							+ (hasReport ? "" : tr("AUSGEGRAUT: noch kein Laufbericht vorhanden."))));
				});
			}, "Klein_Method_Swarm_View");
			thread.setDaemon(true);
			thread.start();
		}
		private double recommendationValue(String key) {
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings s = Klein_Method_Tab_Builder.this.kleinMethod.getRecommendationSettings();
			switch (key) {
			case Klein_Method.SETTING_FPE_TACHY_GREEN: return s.tachyGreen;
			case Klein_Method.SETTING_FPE_TACHY_YELLOW: return s.tachyYellow;
			case Klein_Method.SETTING_FPE_LEVEL_GREEN: return s.levelGreen;
			case Klein_Method.SETTING_FPE_LEVEL_YELLOW: return s.levelYellow;
			case Klein_Method.SETTING_FPE_MAX_GAP: return s.maxGapM;
			case Klein_Method.SETTING_FPE_MID_POS_GREEN: return s.midPositionGreen;
			case Klein_Method.SETTING_FPE_MID_POS_YELLOW: return s.midPositionYellow;
			case Klein_Method.SETTING_FPE_MID_HEIGHT_GREEN: return s.midHeightGreen;
			case Klein_Method.SETTING_FPE_MID_HEIGHT_YELLOW: return s.midHeightYellow;
			default: return s.endCount;
			}
		}
		/** Eingabefeld mit Pruefung: min..max, bei maxGap leer = automatisch; ungueltig -> alter Wert. */
		private TextField createRecommendationField(String key, double min, double max, String tooltip) {
			TextField field = new TextField(formatRecommendationValue(key, this.recommendationValue(key)));
			field.setPrefColumnCount(6);
			field.setTooltip(new Tooltip(tooltip));
			Runnable commit = () -> {
				String text = field.getText() == null ? "" : field.getText().trim().replace(',', '.');
				double old = this.recommendationValue(key);
				double value;
				if (text.isEmpty() && Klein_Method.SETTING_FPE_MAX_GAP.equals(key))
					value = 0.0;
				else {
					try {
						value = Double.parseDouble(text);
					} catch (NumberFormatException e) {
						value = Double.NaN;
					}
					if (Klein_Method.SETTING_FPE_MAX_GAP.equals(key) && value == 0.0)
						value = 0.0;
					else if (Double.isNaN(value) || value < min || value > max)
						value = Double.NaN;
				}
				// gelb muss >= gruen sein
				Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Settings s = Klein_Method_Tab_Builder.this.kleinMethod.getRecommendationSettings();
				if (!Double.isNaN(value)) {
					if (Klein_Method.SETTING_FPE_TACHY_GREEN.equals(key) && value > s.tachyYellow
							|| Klein_Method.SETTING_FPE_TACHY_YELLOW.equals(key) && value < s.tachyGreen
							|| Klein_Method.SETTING_FPE_LEVEL_GREEN.equals(key) && value > s.levelYellow
							|| Klein_Method.SETTING_FPE_LEVEL_YELLOW.equals(key) && value < s.levelGreen
							|| Klein_Method.SETTING_FPE_MID_POS_GREEN.equals(key) && value > s.midPositionYellow
							|| Klein_Method.SETTING_FPE_MID_POS_YELLOW.equals(key) && value < s.midPositionGreen
							|| Klein_Method.SETTING_FPE_MID_HEIGHT_GREEN.equals(key) && value > s.midHeightYellow
							|| Klein_Method.SETTING_FPE_MID_HEIGHT_YELLOW.equals(key) && value < s.midHeightGreen)
						value = Double.NaN;
				}
				if (Double.isNaN(value)) {
					field.setText(formatRecommendationValue(key, old));
					this.showRecommendationInputAlert(min, max);
					return;
				}
				if (Klein_Method.SETTING_FPE_END_COUNT.equals(key))
					value = Math.round(value);
				field.setText(formatRecommendationValue(key, value));
				if (Math.abs(value - old) > 1e-12)
					Klein_Method_Tab_Builder.this.kleinMethod.setRecommendationValue(key, value);
			};
			field.setOnAction(event -> commit.run());
			field.focusedProperty().addListener((obs, was, now) -> {
				if (!now)
					commit.run();
			});
			Klein_Method_Tab_Builder.this.recommendationFields.put(key, field);
			return field;
		}
		private void showRecommendationInputAlert(double min, double max) {
			Alert alert = new Alert(AlertType.WARNING);
			alert.setTitle(tr("Klein_Method - Festpunkt-Empfehlung"));
			alert.setHeaderText(tr("Ungültige Eingabe - der bisherige Wert bleibt."));
			alert.setContentText(String.format(tr("Erlaubt: %s bis %s. Die Grenze für gelb darf nicht kleiner sein als die für grün."),
					formatPlain(min), formatPlain(max)));
			alert.show();
		}
		/** Baut den Bereich "Festpunkt-Empfehlung" mit Feldern, Ergebnis und den beiden Knoepfen. */
		private VBox buildRecommendationBox() {
			Label title = new Label(tr("Festpunkt-Empfehlung"));
			title.setStyle("-fx-font-weight: bold;");
			title.setTooltip(new Tooltip(tr("Nur dynamische Ausgleichung: Welche beweglichen Anschlusspunkte können künftig als Festpunkte dienen?\n"
					+ "Bewertet wird je Punkt die kalibrierte individuelle Unsicherheit (eine Zahl für y/x/z) in Ampelfarben:\n"
					+ "Randpunkte (Endbereiche, äußere Umrandung, Anschlussbereiche) mit den Rand-Grenzen, Stützpunkte in der Mitte mit den Mitte-Grenzen;\n"
					+ "1D-Punkte (Nivellement) jeweils mit der Höhen-, 2D/3D-Punkte mit der Lage-Grenze.\n"
					+ "Danach wird die Verteilung der grünen Festpunkte geprüft: langgestreckte Netze auf Lücken und Enden,\n"
					+ "flächige Netze darauf, dass alle Neupunkte innerhalb der Festpunkte liegen.\n"
					+ "Es ist eine Empfehlung - im Zweifel entscheidet der Anwender.")));

			String tachyTip = tr("Randpunkte - Tachymeter (2D- und 3D-Punkte in Endbereichen, auf der äußeren Umrandung oder in Anschlussbereichen): Grenzen der individuellen Unsicherheit in mm.\n"
					+ "grün: bis zu diesem Wert empfohlen (Standard 3 mm), gelb: bedingt (Standard 5 mm), darüber rot.\n"
					+ "Nivellierte 3D-Punkte erhalten einen Hinweis, weil Lage und Höhe dieselbe Zahl haben.\nErlaubt: 0,1 bis 100 mm. Je Projekt gespeichert.");
			String levelTip = tr("Randpunkte - Nivellement (1D-Punkte in Endbereichen, auf der äußeren Umrandung oder in Anschlussbereichen): Grenzen der individuellen Unsicherheit in mm.\n"
					+ "grün: bis zu diesem Wert empfohlen (Standard 0,5 mm), gelb: bedingt (Standard 1,0 mm), darüber rot.\nErlaubt: 0,01 bis 10 mm. Je Projekt gespeichert.");
			String gapTip = tr("Langgestreckte Netze: größter erlaubter Abstand zwischen zwei grünen Festpunkten entlang der Achse in Metern,\n"
					+ "z. B. 600 (innerstädtisch, Punkte alle 300 m) oder 6000 (Bahn, Punkte alle 3 km).\n"
					+ "Leer = automatisch: 2 × der übliche Abstand der Altpunkte dieses Netzes. Der gerade geltende Wert steht rechts daneben.\n"
					+ "Derselbe Wert legt die Länge der Endbereiche fest. Erlaubt: 1 bis 1 000 000 m. Je Projekt gespeichert.");
			String endTip = tr("So viele gr\u00FCne Festpunkte muss es mindestens geben:\n"
					+ "- langgestreckte Netze: an jedem Ende (Endbereich = max. L\u00FCcke vom Netzende),\n"
					+ "- in jedem Anschlussbereich, der in der Karte markiert ist (zus\u00E4tzlich zur Pr\u00FCfung der Verteilung).\n"
					+ "Standard 3, erlaubt 1 bis 20. Je Projekt gespeichert.");

			GridPane grid = new GridPane();
			grid.setHgap(6);
			grid.setVgap(4);
			Label tachyLabel = new Label(tr("Rand - Tachymeter: gr\u00FCn \u2264"));
			tachyLabel.setTooltip(new Tooltip(tachyTip));
			Label tachyYellowLabel = new Label(tr("mm, gelb ≤"));
			tachyYellowLabel.setTooltip(new Tooltip(tachyTip));
			Label tachyUnit = new Label(tr("mm"));
			tachyUnit.setTooltip(new Tooltip(tachyTip));
			grid.addRow(0, tachyLabel, this.createRecommendationField(Klein_Method.SETTING_FPE_TACHY_GREEN, 0.1, 100.0, tachyTip), tachyYellowLabel,
					this.createRecommendationField(Klein_Method.SETTING_FPE_TACHY_YELLOW, 0.1, 100.0, tachyTip), tachyUnit);
			Label levelLabel = new Label(tr("Rand - Nivellement: gr\u00FCn \u2264"));
			levelLabel.setTooltip(new Tooltip(levelTip));
			Label levelYellowLabel = new Label(tr("mm, gelb ≤"));
			levelYellowLabel.setTooltip(new Tooltip(levelTip));
			Label levelUnit = new Label(tr("mm"));
			levelUnit.setTooltip(new Tooltip(levelTip));
			grid.addRow(1, levelLabel, this.createRecommendationField(Klein_Method.SETTING_FPE_LEVEL_GREEN, 0.01, 10.0, levelTip), levelYellowLabel,
					this.createRecommendationField(Klein_Method.SETTING_FPE_LEVEL_YELLOW, 0.01, 10.0, levelTip), levelUnit);
			// Stuetzpunkte in der Mitte
			String midPosTip = tr("Stützpunkte in der Mitte (2D- und 3D-Punkte): Grenzen der individuellen Unsicherheit (Lage y/x) in mm.\n"
					+ "Mitte = alle Altpunkte außerhalb der Endbereiche (langgestreckt) bzw. innerhalb der äußeren Umrandung (flächig)\n"
					+ "und nicht in einem markierten Anschlussbereich. Randpunkte werden mit den Rand-Grenzen darüber bewertet.\n"
					+ "grün: Standard 5 mm, gelb: Standard 10 mm. Erlaubt: 0,1 bis 100 mm. Je Projekt gespeichert.");
			String midHeightTip = tr("Stützpunkte in der Mitte (1D-Punkte, Nivellement): Grenzen der individuellen Unsicherheit (Höhe z) in mm.\n"
					+ "Mitte wie bei der Lage. grün: Standard 0,5 mm, gelb: Standard 1,0 mm. Erlaubt: 0,01 bis 10 mm. Je Projekt gespeichert.");
			Label midPosLabel = new Label(tr("Mitte - Lage (y/x): gr\u00FCn \u2264"));
			midPosLabel.setTooltip(new Tooltip(midPosTip));
			Label midPosYellowLabel = new Label(tr("mm, gelb \u2264"));
			midPosYellowLabel.setTooltip(new Tooltip(midPosTip));
			Label midPosUnit = new Label(tr("mm"));
			midPosUnit.setTooltip(new Tooltip(midPosTip));
			grid.addRow(2, midPosLabel, this.createRecommendationField(Klein_Method.SETTING_FPE_MID_POS_GREEN, 0.1, 100.0, midPosTip), midPosYellowLabel,
					this.createRecommendationField(Klein_Method.SETTING_FPE_MID_POS_YELLOW, 0.1, 100.0, midPosTip), midPosUnit);
			Label midHeightLabel = new Label(tr("Mitte - H\u00F6he (z): gr\u00FCn \u2264"));
			midHeightLabel.setTooltip(new Tooltip(midHeightTip));
			Label midHeightYellowLabel = new Label(tr("mm, gelb \u2264"));
			midHeightYellowLabel.setTooltip(new Tooltip(midHeightTip));
			Label midHeightUnit = new Label(tr("mm"));
			midHeightUnit.setTooltip(new Tooltip(midHeightTip));
			grid.addRow(3, midHeightLabel, this.createRecommendationField(Klein_Method.SETTING_FPE_MID_HEIGHT_GREEN, 0.01, 10.0, midHeightTip), midHeightYellowLabel,
					this.createRecommendationField(Klein_Method.SETTING_FPE_MID_HEIGHT_YELLOW, 0.01, 10.0, midHeightTip), midHeightUnit);

			Label gapLabel = new Label(tr("Max. Lücke zwischen Festpunkten [m]:"));
			gapLabel.setTooltip(new Tooltip(gapTip));
			Klein_Method_Tab_Builder.this.maxGapInfoLabel = new Label();
			Klein_Method_Tab_Builder.this.maxGapInfoLabel.setTooltip(new Tooltip(gapTip));
			HBox gapRow = new HBox(6, gapLabel, this.createRecommendationField(Klein_Method.SETTING_FPE_MAX_GAP, 1.0, 1000000.0, gapTip), Klein_Method_Tab_Builder.this.maxGapInfoLabel);
			gapRow.setAlignment(Pos.CENTER_LEFT);
			Label endLabel = new Label(tr("Gr\u00FCne Festpunkte je Ende / Anschlussbereich:"));
			endLabel.setTooltip(new Tooltip(endTip));
			HBox endRow = new HBox(6, endLabel, this.createRecommendationField(Klein_Method.SETTING_FPE_END_COUNT, 1.0, 20.0, endTip));
			endRow.setAlignment(Pos.CENTER_LEFT);

			Klein_Method_Tab_Builder.this.recommendationResultLabel = new Label(tr("Ergebnis: noch nicht berechnet (nach der nächsten Ausgleichung)."));
			Klein_Method_Tab_Builder.this.recommendationResultLabel.setWrapText(true);
			Klein_Method_Tab_Builder.this.recommendationResultLabel.setTooltip(new Tooltip(tr("Ergebnis der Festpunkt-Empfehlung nach der letzten Ausgleichung.\n"
					+ "Wird nach jeder Ausgleichung und nach jeder Eingabe oben neu berechnet. Einzelheiten im Bericht.")));

			Klein_Method_Tab_Builder.this.pointCandidateReportButton = new Button(tr("Festpunktanalyse-Bericht erstellen"));
			Klein_Method_Tab_Builder.this.pointCandidateReportButton.setTooltip(new Tooltip(tr("Bericht der Festpunkt-Empfehlung als Textdatei im Protokollordner: Ampel je beweglichem Anschlusspunkt "
					+ "(individuelle Unsicherheit), Prüfung der Verteilung mit Gründen und Hinweisen, EP-Wert zur Information.\n"
					+ "REINE INFORMATION - legt KEINE neue Punktgruppe an und verschiebt KEINE Punkte.")));
			Klein_Method_Tab_Builder.this.pointCandidateReportButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.generatePointCandidateReport());

			Klein_Method_Tab_Builder.this.promotePointsButton = new Button(tr("Empfehlung übernehmen"));
			Klein_Method_Tab_Builder.this.promotePointsButton.setTooltip(new Tooltip(tr("ACHTUNG - VERÄNDERT DAS PROJEKT: legt unter „Varianzfreie Festpunkte“ eine neue Gruppe „Varianzfreie Festpunkte (Festpunktanalyse)“ an "
					+ "und verschiebt nur die GRÜNEN Punkte dorthin.\nNur aktiv, wenn die Empfehlung „empfohlen“ lautet (Grenzen und Verteilung erfüllt). "
					+ "Zeigt vorher eine Sicherheitsabfrage - passiert NIE automatisch.")));
			Klein_Method_Tab_Builder.this.promotePointsButton.setOnAction(event -> Klein_Method_Tab_Builder.this.kleinMethod.promoteStablePointsToPlannedFixedPoints());
			Klein_Method_Tab_Builder.this.promotePointsButton.setDisable(true);

			Klein_Method_Tab_Builder.this.markExpertOnly(grid, gapRow, endRow);
			VBox box = new VBox(6, title, grid, gapRow, endRow, Klein_Method_Tab_Builder.this.recommendationResultLabel, Klein_Method_Tab_Builder.this.pointCandidateReportButton, Klein_Method_Tab_Builder.this.promotePointsButton);
			box.setPadding(new Insets(6));
			box.setStyle("-fx-border-color: #b0bec5; -fx-border-radius: 4; -fx-border-width: 1;");
			box.setVisible(false);
			box.setManaged(false);
			Klein_Method_Tab_Builder.this.recommendationBox = box;
			return box;
		}
		/** Felder, Ergebnis und Knopf-Zustand aus Klein_Method uebernehmen (FX-Thread). */
		public void updateRecommendationView() {
			if (Klein_Method_Tab_Builder.this.recommendationBox == null)
				return;
			boolean dynamic = Klein_Method_Tab_Builder.this.isDynamicStepVisible();
			Klein_Method_Tab_Builder.this.recommendationBox.setVisible(dynamic);
			Klein_Method_Tab_Builder.this.recommendationBox.setManaged(dynamic);
			for (Map.Entry<String, TextField> entry : Klein_Method_Tab_Builder.this.recommendationFields.entrySet())
				if (!entry.getValue().isFocused())
					entry.getValue().setText(formatRecommendationValue(entry.getKey(), this.recommendationValue(entry.getKey())));
			Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.Result r = Klein_Method_Tab_Builder.this.kleinMethod.getLastRecommendation();
			if (r == null) {
				Klein_Method_Tab_Builder.this.recommendationResultLabel.setText(tr("Ergebnis: noch nicht berechnet (nach der nächsten Ausgleichung)."));
				Klein_Method_Tab_Builder.this.recommendationResultLabel.setStyle("");
				Klein_Method_Tab_Builder.this.maxGapInfoLabel.setText("");
			} else {
				String head = r.recommended ? tr("Ergebnis: Festpunkte empfohlen") : tr("Ergebnis: Festpunkte nicht empfohlen");
				String text = head + "\n" + Klein_Method_Tab_Builder.this.kleinMethod.recommendationSummary(r);
				if (!r.reasons.isEmpty())
					text += "\n• " + r.reasons.get(0) + (r.reasons.size() > 1 ? String.format(tr(" (und %d weitere Gründe, siehe Bericht)"), r.reasons.size() - 1) : "");
				Klein_Method_Tab_Builder.this.recommendationResultLabel.setText(text);
				Klein_Method_Tab_Builder.this.recommendationResultLabel.setStyle(r.recommended ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #c62828;");
				if (r.elongated && !Double.isNaN(r.maxGapM))
					Klein_Method_Tab_Builder.this.maxGapInfoLabel.setText("(" + (r.maxGapAuto ? tr("automatisch") : tr("eingegeben")) + ": "
							+ Klein_Method_Functions.Klein_Method_Fixed_Point_Recommendation.formatLength(r.maxGapM) + ")");
				else if (r.geometryAvailable && !r.elongated)
					Klein_Method_Tab_Builder.this.maxGapInfoLabel.setText(tr("(flächiges Netz - nicht verwendet)"));
				else
					Klein_Method_Tab_Builder.this.maxGapInfoLabel.setText("");
			}
			if (!Klein_Method_Tab_Builder.this.runLockActive)
				Klein_Method_Tab_Builder.this.promotePointsButton.setDisable(r == null || !r.recommended);
		}	}
}
// V1.00_RC3_Klein_Method_Tab_Builder.java
