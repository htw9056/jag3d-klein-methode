// V1.00_RC3_Klein_Method_Parameter_Store.java
package org.applied_geodesy.klein_method.parameter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import org.applied_geodesy.klein_method.logging.Klein_Method_Logger;

/**
 * Laedt und speichert alle dauerhaften Klein_Method-Einstellungen
 * (Grenzwerte, Grenzwert Iterationen, Bewertungsmethode, Verteilung,
 * Turbo, Nachkommastellen) in einer eigenen Properties-Datei im
 * Benutzerverzeichnis - unabhaengig von JAG3Ds eigener Projektdatenbank,
 * damit die Einstellungen auch projektuebergreifend erhalten bleiben.
 *
 * NICHT gespeichert werden Laufzeit-/Zaehlerwerte, die bei jedem
 * Programmstart ohnehin neu beginnen sollen: aktueller Iterationszaehler,
 * aktuelle (gemessene) Werte je Grenzwert-Typ, Fortschritts-Startwert.
 *
 * Ablageort: ~/.klein_method/klein_method.properties
 * (eigener, versteckter Ordner - beruehrt keine JAG3D-eigenen Dateien/Ordner)
 */
public class Klein_Method_Parameter_Store {

	private static final String SETTINGS_DIRECTORY_NAME = ".klein_method";
	private static final String SETTINGS_FILE_NAME = "klein_method.properties";

	private static final String KEY_THRESHOLD_PREFIX = "threshold.";
	private static final String KEY_LOGPOST_THRESHOLD = "threshold.logpost.shared";
	private static final String KEY_ITERATION_THRESHOLD = "iteration.threshold";
	private static final String KEY_VKS_BAND_MIN = "vks.band.min";
	private static final String KEY_VKS_BAND_MAX = "vks.band.max";
	private static final String KEY_FUNNEL_FLOOR_LOG_POST = "funnel.floor.logpost";
	private static final String KEY_GROSS_ERROR_LINEAR_LIMIT = "gross.error.linear.limit";
	private static final String KEY_SWARM_REPOSITORY = "swarm.repository";
	private static final String KEY_SWARM_WARN_FACTOR = "swarm.warn.factor";
	private static final String KEY_SWARM_RELAY_URL = "swarm.relay.url";
	/** Merker: alte Standard-Repositorys wurden einmalig auf den neuen Standard umgestellt (danach bleibt eine bewusste Eingabe erhalten). */
	private static final String KEY_SWARM_REPOSITORY_MIGRATED = "swarm.repository.migrated";
	private static final String SWARM_REPOSITORY_MIGRATION = "V1.00";
	private static final String KEY_CASCADE_ABORT_LIMIT = "cascade.abort.limit";
	private static final String KEY_EVALUATION_METHOD = "evaluation.method";
	private static final String KEY_DISTRIBUTION_TYPE = "distribution.type";
	private static final String KEY_COORDINATE_EXPORT_FORMAT = "coordinate.export.format";
	private static final String KEY_COORDINATE_EXPORT_NORMAL_HEIGHT = "coordinate.export.normal_height";
	private static final String KEY_COORDINATE_EXPORT_TARGET = "coordinate.export.system";
	private static final String KEY_BASELINE_EPOCH_MODE = "baseline.epoch.mode", KEY_BASELINE_SIGMA_FACTOR = "baseline.sigma.factor",
			KEY_BASELINE_SIGMA_MIN = "baseline.sigma.min.mm";
	private static final String KEY_LANGUAGE = "language";
	private static final String KEY_VIEW_MODE = "view.mode";
	private static final String KEY_FULL_BAND_REDUNDANCY = "calibration.full.band.redundancy";
	private static final String KEY_MIN_CALIBRATION_REDUNDANCY = "calibration.min.redundancy";
	private static final String KEY_PLAUSIBILITY_FACTOR = "calibration.plausibility.factor";
	private static final String KEY_ROBUST_RATIO_LIMIT = "calibration.robust.ratio.limit";
	private static final String KEY_LOCAL_SYSTEM = "local.system";
	private static final String KEY_APPROXIMATION_TOLERANCE_POSITION = "approximation.tolerance.position";
	private static final String KEY_APPROXIMATION_TOLERANCE_HEIGHT = "approximation.tolerance.height";
	private static final String KEY_FOLLOW_UP_MAX_ITERATIONS = "followup.max.iterations";
	private static final String KEY_FOLLOW_UP_PATIENCE = "followup.patience";
	private static final String KEY_POINT_FLOOR_POSITION = "dynamic.point.floor.position.mm";
	private static final String KEY_POINT_FLOOR_HEIGHT_1D = "dynamic.point.floor.height1d.mm";
	private static final String KEY_TURBO_ENABLED = "turbo.enabled";
	private static final String KEY_NORMAL_DAMPING_ENABLED = "normal.damping.enabled";
	private static final String KEY_PHASE1_TURBO_ENABLED = "phase1.turbo.enabled";
	private static final String KEY_FLOOR_ENFORCEMENT_ENABLED = "floor.enforcement.enabled";
	private static final String KEY_CASCADE_DOUBLE_RUN_ENABLED = "cascade.double.run.enabled";
	private static final String KEY_DEACTIVATE_GNSS_OUTLIER_ENABLED = "deactivate.gnss.outlier.enabled";
	private static final String KEY_APPROXIMATION_PRIORITY = "approximation.priority";
	private static final String KEY_MASS_THINNING = "gnss.mass.thinning";
	private static final String KEY_FINAL_SCALING = "vks.final.scaling";
	private static final String KEY_AUTO_GROSS_CLEANUP = "gross.auto.cleanup";
	private static final String KEY_GROSS_GNSS_PPM = "gross.gnss.ppm";
	/** Merker: Standard der Wiederholungen auf "Zusammenfassen + Kalibrieren" umgestellt (einmalig fuer gespeicherte Altwerte). */
	private static final String KEY_REPETITION_DEFAULT_CALIBRATE = "repetition.default.calibrate";
	private static final String KEY_RENAME_TEMPLATES = "rename.templates";
	private static final String KEY_GNSS_SESSION_MERGE = "gnss.session.merge";
	/** Wiederholte Messungen je Messart: Praefix + ObservationType.name(), Wert 0/1/2. */
	private static final String KEY_REPETITION_MODE_PREFIX = "repetition.mode.";
	private static final String KEY_GROUP_DAMPING_PHASE2_ENABLED = "group.damping.phase2.enabled";
	private static final String KEY_SPLIT_NORMALIZATION_MODE = "split.normalization.mode";
	private static final String KEY_MATH_DEBUG_MODE = "math.debug.mode";
	private static final String KEY_GROUP_FLOOR_AFTER_PASS1_ENABLED = "group.floor.after.pass1.enabled";
	private static final String KEY_DECIMAL_PLACES = "decimal.places";
	private static final String KEY_DEVICE_CLASS = "device.class";
	private static final String KEY_GROUP_UNCERTAINTY_PREFIX = "group.uncertainty.";

	private File getSettingsFile() {
		String userHome = System.getProperty("user.home");
		File directory = new File(userHome, SETTINGS_DIRECTORY_NAME);
		return new File(directory, SETTINGS_FILE_NAME);
	}

	/**
	 * Laedt gespeicherte Einstellungen in das uebergebene Parameter-Objekt.
	 * Existiert noch keine Datei (erster Start), passiert einfach nichts -
	 * die Standardwerte aus Klein_Method_Parameter bleiben erhalten.
	 */
	public void load(Klein_Method_Parameter parameter, Klein_Method_Logger logger) {
		File file = this.getSettingsFile();
		if (!file.exists())
			return;

		Properties properties = new Properties();
		try (FileInputStream input = new FileInputStream(file)) {
			properties.load(input);

			// EP-Grenzwerte weiterhin einzeln je Typ (4 Werte)
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.EP)) {
				String value = properties.getProperty(KEY_THRESHOLD_PREFIX + type.name());
				if (value != null) {
					try {
						parameter.setThresholdValue(type, Double.parseDouble(value));
					} catch (NumberFormatException e) {
						// ungueltiger Wert in der Datei -> ignorieren, Standardwert bleibt
					}
				}
			}

			// logPost-Grenzwert jetzt EIN gemeinsamer Wert fuer alle 4 Typen
			// (siehe Klein_Method_Parameter.setSharedLogPostThreshold())
			String sharedLogPostValue = properties.getProperty(KEY_LOGPOST_THRESHOLD);
			if (sharedLogPostValue != null) {
				try {
					parameter.setSharedLogPostThreshold(Double.parseDouble(sharedLogPostValue));
				} catch (NumberFormatException e) {
					// ungueltiger Wert in der Datei -> ignorieren, Standardwert bleibt
				}
			}

			String iterationThresholdValue = properties.getProperty(KEY_ITERATION_THRESHOLD);
			if (iterationThresholdValue != null) {
				try {
					parameter.setIterationThreshold(Integer.parseInt(iterationThresholdValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			// VKS-Band - setVksBandMin()/Max() setzen ungueltige Werte selbst auf den Default zurueck, hier also nur
			// Parse-Fehler abfangen.
			String cascadeAbortLimitValue = properties.getProperty(KEY_CASCADE_ABORT_LIMIT);
			if (cascadeAbortLimitValue != null) {
				try {
					parameter.setCascadeAbortLimit(Double.parseDouble(cascadeAbortLimitValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			String swarmRepositoryValue = properties.getProperty(KEY_SWARM_REPOSITORY);
			if (swarmRepositoryValue != null) {
				// alte Standardnamen einmalig auf den neuen, oeffentlichen Standard umstellen
				String trimmedRepository = swarmRepositoryValue.trim();
				boolean migrated = SWARM_REPOSITORY_MIGRATION.equals(properties.getProperty(KEY_SWARM_REPOSITORY_MIGRATED));
				if (trimmedRepository.equalsIgnoreCase("klein-method-bot/klein-method-schwarm")
						|| trimmedRepository.equalsIgnoreCase("htw9056/klein--method-schwarm-test")
						|| (!migrated && trimmedRepository.equalsIgnoreCase(org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.PREVIOUS_DEFAULT_REPOSITORY))) {
					swarmRepositoryValue = org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Swarm.DEFAULT_REPOSITORY;
					if (logger != null)
						logger.log("Klein_Method: Schwarmwissen-Repository " + trimmedRepository + " auf den neuen Standard " + swarmRepositoryValue + " umgestellt.");
				}
				parameter.setSwarmRepository(swarmRepositoryValue);
			}
			String swarmRelayValue = properties.getProperty(KEY_SWARM_RELAY_URL);
			if (swarmRelayValue != null)
				parameter.setSwarmRelayUrl(swarmRelayValue);
			String swarmWarnValue = properties.getProperty(KEY_SWARM_WARN_FACTOR);
			if (swarmWarnValue != null) {
				try {
					parameter.setSwarmWarnFactor(Double.parseDouble(swarmWarnValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			String grossErrorLinearValue = properties.getProperty(KEY_GROSS_ERROR_LINEAR_LIMIT);
			if (grossErrorLinearValue != null) {
				try {
					parameter.setGrossErrorLinearLimit(Double.parseDouble(grossErrorLinearValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			String funnelFloorValue = properties.getProperty(KEY_FUNNEL_FLOOR_LOG_POST);
			if (funnelFloorValue != null) {
				try {
					parameter.setFunnelFloorLogPost(Double.parseDouble(funnelFloorValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			String vksBandMinValue = properties.getProperty(KEY_VKS_BAND_MIN);
			if (vksBandMinValue != null) {
				try {
					parameter.setVksBandMin(Double.parseDouble(vksBandMinValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}
			String vksBandMaxValue = properties.getProperty(KEY_VKS_BAND_MAX);
			if (vksBandMaxValue != null) {
				try {
					parameter.setVksBandMax(Double.parseDouble(vksBandMaxValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			String evaluationMethodValue = properties.getProperty(KEY_EVALUATION_METHOD);
			if (evaluationMethodValue != null) {
				try {
					parameter.setEvaluationMethod(Klein_Method_Evaluation_Method.valueOf(evaluationMethodValue));
				} catch (IllegalArgumentException e) {
					// unbekannter Wert (z.B. aeltere Version) -> ignorieren
				}
			}

			String distributionTypeValue = properties.getProperty(KEY_DISTRIBUTION_TYPE);
			if (distributionTypeValue != null) {
				try {
					parameter.setDistributionType(Klein_Method_Distribution_Type.valueOf(distributionTypeValue));
				} catch (IllegalArgumentException e) {
					// ignorieren
				}
			}

			parameter.setSimpleView("SIMPLE".equals(properties.getProperty(KEY_VIEW_MODE)));

			// Qualitaet der Gruppenkalibrierung
			String[][] qualityKeys = { { KEY_FULL_BAND_REDUNDANCY }, { KEY_MIN_CALIBRATION_REDUNDANCY }, { KEY_PLAUSIBILITY_FACTOR }, { KEY_ROBUST_RATIO_LIMIT },
					{ KEY_APPROXIMATION_TOLERANCE_POSITION }, { KEY_APPROXIMATION_TOLERANCE_HEIGHT }, { KEY_FOLLOW_UP_MAX_ITERATIONS }, { KEY_FOLLOW_UP_PATIENCE },
					{ KEY_POINT_FLOOR_POSITION }, { KEY_POINT_FLOOR_HEIGHT_1D } };
			for (int q = 0; q < qualityKeys.length; q++) {
				String qualityValue = properties.getProperty(qualityKeys[q][0]);
				if (qualityValue == null)
					continue;
				try {
					double value = Double.parseDouble(qualityValue);
					switch (q) {
					case 0: parameter.setFullBandRedundancy(value); break;
					case 1: parameter.setMinCalibrationRedundancy(value); break;
					case 2: parameter.setPlausibilityFactor(value); break;
					case 3: parameter.setRobustRatioLimit(value); break;
					case 4: parameter.setApproximationTolerancePosition(value); break;
					case 5: parameter.setApproximationToleranceHeight(value); break;
					case 6: parameter.setFollowUpMaxIterations(value); break;
					case 7: parameter.setFollowUpPatience(value); break;
					case 8: parameter.setPointFloorPositionMillimeter(value); break;
					default: parameter.setPointFloorHeight1DMillimeter(value); break;
					}
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Local_System.Parameters local =
					org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Local_System.Parameters.decode(properties.getProperty(KEY_LOCAL_SYSTEM));
			if (local != null) {
				org.applied_geodesy.klein_method.logic.Klein_Method_Functions.Klein_Method_Local_System.Parameters target = parameter.getLocalSystem();
				target.latitude = local.latitude;
				target.longitude = local.longitude;
				target.height = local.height;
				target.addEast = local.addEast;
				target.addNorth = local.addNorth;
				target.addUp = local.addUp;
				target.decimals = local.decimals;
			}

			String languageValue = properties.getProperty(KEY_LANGUAGE);
			if (languageValue != null)
				parameter.setLanguage(languageValue);

			parameter.setCoordinateExportNormalHeight("true".equals(properties.getProperty(KEY_COORDINATE_EXPORT_NORMAL_HEIGHT)));
			String coordinateExportFormatValue = properties.getProperty(KEY_COORDINATE_EXPORT_FORMAT);
			if (coordinateExportFormatValue != null) {
				try {
					parameter.setCoordinateExportFormat(Klein_Method_Coordinate_Export_Format.valueOf(coordinateExportFormatValue));
				} catch (IllegalArgumentException e) {
					// unbekannter Wert (z.B. aeltere Version) -> ignorieren
				}
			}

			try {
				if (properties.getProperty(KEY_BASELINE_EPOCH_MODE) != null)
					parameter.setBaselineEpochMode(Integer.parseInt(properties.getProperty(KEY_BASELINE_EPOCH_MODE).trim()));
				if (properties.getProperty(KEY_BASELINE_SIGMA_FACTOR) != null)
					parameter.setBaselineSigmaFactor(Double.parseDouble(properties.getProperty(KEY_BASELINE_SIGMA_FACTOR).trim()));
				if (properties.getProperty(KEY_BASELINE_SIGMA_MIN) != null)
					parameter.setBaselineSigmaMinMm(Double.parseDouble(properties.getProperty(KEY_BASELINE_SIGMA_MIN).trim()));
			} catch (NumberFormatException e) {
				// ungueltig -> Standard
			}
			String coordinateExportTargetValue = properties.getProperty(KEY_COORDINATE_EXPORT_TARGET);
			if (coordinateExportTargetValue != null)
				parameter.setCoordinateExportTarget(coordinateExportTargetValue);

			String deviceClassValue = properties.getProperty(KEY_DEVICE_CLASS);
			if (deviceClassValue != null) {
				try {
					parameter.setDeviceClass(Klein_Method_Device_Class.valueOf(deviceClassValue));
				} catch (IllegalArgumentException e) {
					// ignorieren
				}
			}

			// Editierbare Gruppenunsicherheits-Grenzwerte (sigma_a/sigma_b/sigma_c)
			// je Typ UND Geraeteklasse laden - BEIDE Saetze (Elektronisch UND
			// Historisch), unabhaengig von der aktuell gewaehlten Geraeteklasse
			// (siehe Klein_Method_Parameter.getGroupUncertaintyFloors()).
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST)) {
				for (Klein_Method_Device_Class deviceClass : new Klein_Method_Device_Class[] { Klein_Method_Device_Class.ELEKTRONISCH, Klein_Method_Device_Class.HISTORISCH }) {
					double[] floors = parameter.getGroupUncertaintyFloors(type, deviceClass);
					String[] suffixes = { "a", "b", "c" };
					for (int i = 0; i < 3; i++) {
						String value = properties.getProperty(KEY_GROUP_UNCERTAINTY_PREFIX + type.name() + "." + deviceClass.name() + "." + suffixes[i]);
						if (value != null) {
							try {
								floors[i] = Double.parseDouble(value);
							} catch (NumberFormatException e) {
								// ungueltiger Wert -> Seed-Default bleibt
							}
						}
					}
				}
			}

			String phase1TurboValue = properties.getProperty(KEY_PHASE1_TURBO_ENABLED);
			if (phase1TurboValue != null)
				parameter.setPhase1TurboEnabled(Boolean.parseBoolean(phase1TurboValue));

			String normalDampingValue = properties.getProperty(KEY_NORMAL_DAMPING_ENABLED);
			if (normalDampingValue != null)
				parameter.setNormalDampingEnabled(Boolean.parseBoolean(normalDampingValue));

			String turboEnabledValue = properties.getProperty(KEY_TURBO_ENABLED);
			if (turboEnabledValue != null)
				parameter.setTurboEnabled(Boolean.parseBoolean(turboEnabledValue));

			String floorEnforcementEnabledValue = properties.getProperty(KEY_FLOOR_ENFORCEMENT_ENABLED);
			if (floorEnforcementEnabledValue != null)
				parameter.setFloorEnforcementEnabled(Boolean.parseBoolean(floorEnforcementEnabledValue));

			String cascadeDoubleRunEnabledValue = properties.getProperty(KEY_CASCADE_DOUBLE_RUN_ENABLED);
			if (cascadeDoubleRunEnabledValue != null)
				parameter.setCascadeDoubleRunEnabled(Boolean.parseBoolean(cascadeDoubleRunEnabledValue));

			String groupFloorAfterPass1Value = properties.getProperty(KEY_GROUP_FLOOR_AFTER_PASS1_ENABLED);
			if (groupFloorAfterPass1Value != null)
				parameter.setGroupFloorAfterPass1Enabled(Boolean.parseBoolean(groupFloorAfterPass1Value));

			String splitNormalizationValue = properties.getProperty(KEY_SPLIT_NORMALIZATION_MODE);
			if (splitNormalizationValue != null) {
				try {
					parameter.setSplitNormalizationMode(Klein_Method_Parameter.Klein_Method_Split_Normalization_Mode.valueOf(splitNormalizationValue));
				} catch (IllegalArgumentException e) {
					parameter.setSplitNormalizationMode(Klein_Method_Parameter.Klein_Method_Split_Normalization_Mode.AUTO);
				}
			}

			String mathDebugModeValue = properties.getProperty(KEY_MATH_DEBUG_MODE);
			if (mathDebugModeValue != null) {
				try {
					parameter.setMathDebugMode(Klein_Method_Parameter.Klein_Method_Math_Debug_Mode.valueOf(mathDebugModeValue));
				} catch (IllegalArgumentException e) {
					parameter.setMathDebugMode(Klein_Method_Parameter.Klein_Method_Math_Debug_Mode.OFF);
				}
			}

			String groupDampingPhase2Value = properties.getProperty(KEY_GROUP_DAMPING_PHASE2_ENABLED);
			if (groupDampingPhase2Value != null)
				parameter.setGroupDampingInPhase2Enabled(Boolean.parseBoolean(groupDampingPhase2Value));

			for (org.applied_geodesy.adjustment.network.ObservationType type : Klein_Method_Parameter.REPETITION_TYPES) {
				String repetitionValue = properties.getProperty(KEY_REPETITION_MODE_PREFIX + type.name());
				if (repetitionValue != null) {
					try {
						int mode = Integer.parseInt(repetitionValue.trim());
						// einmalige Umstellung: der fruehere Standard "Zusammenfassen" wird zum neuen Standard "Zusammenfassen + Kalibrieren"
						if (properties.getProperty(KEY_REPETITION_DEFAULT_CALIBRATE) == null && mode == Klein_Method_Default_Values.REPETITION_MODE_MERGE)
							mode = Klein_Method_Default_Values.REPETITION_MODE_MERGE_CALIBRATE;
						parameter.setRepetitionMode(type, mode);
					} catch (NumberFormatException e) {
						// ungueltig - Standard bleibt
					}
				}
			}
			String gnssSessionMergeValue = properties.getProperty(KEY_GNSS_SESSION_MERGE);
			if (gnssSessionMergeValue != null)
				parameter.setGnssSessionMerge(Boolean.parseBoolean(gnssSessionMergeValue));
			String renameTemplatesValue = properties.getProperty(KEY_RENAME_TEMPLATES);
			if (renameTemplatesValue != null)
				parameter.setRenameTemplates(renameTemplatesValue);
			String finalScalingValue = properties.getProperty(KEY_FINAL_SCALING);
			if (finalScalingValue != null)
				parameter.setFinalScaling(Boolean.parseBoolean(finalScalingValue));
			String autoGrossValue = properties.getProperty(KEY_AUTO_GROSS_CLEANUP);
			if (autoGrossValue != null)
				parameter.setAutoGrossErrorCleanup(Boolean.parseBoolean(autoGrossValue));
			String grossPpmValue = properties.getProperty(KEY_GROSS_GNSS_PPM);
			if (grossPpmValue != null)
				try {
					parameter.setGrossErrorGnssPpm(Double.parseDouble(grossPpmValue));
				} catch (NumberFormatException e) {
					// Standardwert bleibt
				}
			String massThinningValue = properties.getProperty(KEY_MASS_THINNING);
			if (massThinningValue != null)
				parameter.setMassThinning(Boolean.parseBoolean(massThinningValue));
			String approximationPriorityValue = properties.getProperty(KEY_APPROXIMATION_PRIORITY);
			if (approximationPriorityValue != null)
				parameter.setApproximationPriority(Boolean.parseBoolean(approximationPriorityValue));
			String deactivateGnssOutlierEnabledValue = properties.getProperty(KEY_DEACTIVATE_GNSS_OUTLIER_ENABLED);
			if (deactivateGnssOutlierEnabledValue != null)
				parameter.setDeactivateGnssOutlierEnabled(Boolean.parseBoolean(deactivateGnssOutlierEnabledValue));

			String decimalPlacesValue = properties.getProperty(KEY_DECIMAL_PLACES);
			if (decimalPlacesValue != null) {
				try {
					parameter.setDecimalPlaces(Integer.parseInt(decimalPlacesValue));
				} catch (NumberFormatException e) {
					// ignorieren, Standardwert bleibt
				}
			}

			if (logger != null)
				logger.log("Klein_Method: Einstellungen geladen aus " + file.getAbsolutePath());

		} catch (IOException e) {
			if (logger != null)
				logger.log("Klein_Method: Konnte Einstellungen nicht laden (" + file.getAbsolutePath() + "): " + e.getMessage());
		}
	}

	/**
	 * Speichert die aktuellen dauerhaften Einstellungen. Wird bei jeder
	 * Aenderung in der Oberflaeche aufgerufen (siehe Klein_Method_Tab_Builder),
	 * damit nichts verloren geht, falls JAG3D ohne sauberes Beenden
	 * geschlossen wird.
	 */
	public void save(Klein_Method_Parameter parameter, Klein_Method_Logger logger) {
		File file = this.getSettingsFile();
		File directory = file.getParentFile();

		try {
			if (!directory.exists() && !directory.mkdirs()) {
				if (logger != null)
					logger.log("Klein_Method: Konnte Verzeichnis nicht anlegen: " + directory.getAbsolutePath());
				return;
			}

			Properties properties = new Properties();
			// EP-Grenzwerte weiterhin einzeln je Typ
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.EP))
				properties.setProperty(KEY_THRESHOLD_PREFIX + type.name(), String.valueOf(parameter.getThresholdValue(type)));

			// logPost-Grenzwert jetzt EIN gemeinsamer Wert
			properties.setProperty(KEY_LOGPOST_THRESHOLD, String.valueOf(parameter.getSharedLogPostThreshold()));

			properties.setProperty(KEY_ITERATION_THRESHOLD, String.valueOf(parameter.getIterationThreshold()));
			properties.setProperty(KEY_VKS_BAND_MIN, String.valueOf(parameter.getVksBandMin()));
			properties.setProperty(KEY_VKS_BAND_MAX, String.valueOf(parameter.getVksBandMax()));
			properties.setProperty(KEY_FUNNEL_FLOOR_LOG_POST, String.valueOf(parameter.getFunnelFloorLogPost()));
			properties.setProperty(KEY_GROSS_ERROR_LINEAR_LIMIT, String.valueOf(parameter.getGrossErrorLinearLimit()));
			properties.setProperty(KEY_SWARM_REPOSITORY, parameter.getSwarmRepository());
			properties.setProperty(KEY_SWARM_WARN_FACTOR, String.valueOf(parameter.getSwarmWarnFactor()));
			properties.setProperty(KEY_SWARM_RELAY_URL, parameter.getSwarmRelayUrl());
			properties.setProperty(KEY_SWARM_REPOSITORY_MIGRATED, SWARM_REPOSITORY_MIGRATION);
			properties.setProperty(KEY_CASCADE_ABORT_LIMIT, String.valueOf(parameter.getCascadeAbortLimit()));
			properties.setProperty(KEY_EVALUATION_METHOD, parameter.getEvaluationMethod().name());
			properties.setProperty(KEY_DISTRIBUTION_TYPE, parameter.getUserDistributionType().name());
			properties.setProperty(KEY_COORDINATE_EXPORT_FORMAT, parameter.getCoordinateExportFormat().name());
			properties.setProperty(KEY_COORDINATE_EXPORT_NORMAL_HEIGHT, String.valueOf(parameter.isCoordinateExportNormalHeight()));
			properties.setProperty(KEY_COORDINATE_EXPORT_TARGET, parameter.getCoordinateExportTarget());
			properties.setProperty(KEY_BASELINE_EPOCH_MODE, String.valueOf(parameter.getBaselineEpochMode()));
			properties.setProperty(KEY_BASELINE_SIGMA_FACTOR, String.valueOf(parameter.getBaselineSigmaFactor()));
			properties.setProperty(KEY_BASELINE_SIGMA_MIN, String.valueOf(parameter.getBaselineSigmaMinMm()));
			properties.setProperty(KEY_LANGUAGE, parameter.getLanguage());
			properties.setProperty(KEY_VIEW_MODE, parameter.isSimpleView() ? "SIMPLE" : "EXPERT");
			properties.setProperty(KEY_FULL_BAND_REDUNDANCY, String.valueOf(parameter.getFullBandRedundancy()));
			properties.setProperty(KEY_MIN_CALIBRATION_REDUNDANCY, String.valueOf(parameter.getMinCalibrationRedundancy()));
			properties.setProperty(KEY_PLAUSIBILITY_FACTOR, String.valueOf(parameter.getPlausibilityFactor()));
			properties.setProperty(KEY_ROBUST_RATIO_LIMIT, String.valueOf(parameter.getRobustRatioLimit()));
			properties.setProperty(KEY_LOCAL_SYSTEM, parameter.getLocalSystem().encode());
			properties.setProperty(KEY_APPROXIMATION_TOLERANCE_POSITION, String.valueOf(parameter.getApproximationTolerancePosition()));
			properties.setProperty(KEY_APPROXIMATION_TOLERANCE_HEIGHT, String.valueOf(parameter.getApproximationToleranceHeight()));
			properties.setProperty(KEY_FOLLOW_UP_MAX_ITERATIONS, String.valueOf(parameter.getFollowUpMaxIterations()));
			properties.setProperty(KEY_FOLLOW_UP_PATIENCE, String.valueOf(parameter.getFollowUpPatience()));
			properties.setProperty(KEY_POINT_FLOOR_POSITION, String.valueOf(parameter.getPointFloorPositionMillimeter()));
			properties.setProperty(KEY_POINT_FLOOR_HEIGHT_1D, String.valueOf(parameter.getPointFloorHeight1DMillimeter()));
			properties.setProperty(KEY_DEVICE_CLASS, parameter.getDeviceClass().name());
			properties.setProperty(KEY_TURBO_ENABLED, String.valueOf(parameter.isTurboEnabled()));
			properties.setProperty(KEY_NORMAL_DAMPING_ENABLED, String.valueOf(parameter.isNormalDampingEnabled()));
			properties.setProperty(KEY_PHASE1_TURBO_ENABLED, String.valueOf(parameter.isPhase1TurboEnabled()));
			properties.setProperty(KEY_FLOOR_ENFORCEMENT_ENABLED, String.valueOf(parameter.isFloorEnforcementEnabled()));
			properties.setProperty(KEY_CASCADE_DOUBLE_RUN_ENABLED, String.valueOf(parameter.isCascadeDoubleRunEnabled()));
			properties.setProperty(KEY_DEACTIVATE_GNSS_OUTLIER_ENABLED, String.valueOf(parameter.isDeactivateGnssOutlierEnabled()));
			properties.setProperty(KEY_APPROXIMATION_PRIORITY, String.valueOf(parameter.isApproximationPriority()));
			properties.setProperty(KEY_MASS_THINNING, String.valueOf(parameter.isMassThinning()));
			properties.setProperty(KEY_FINAL_SCALING, String.valueOf(parameter.isFinalScaling()));
			properties.setProperty(KEY_AUTO_GROSS_CLEANUP, String.valueOf(parameter.isAutoGrossErrorCleanup()));
			properties.setProperty(KEY_GROSS_GNSS_PPM, String.valueOf(parameter.getGrossErrorGnssPpm()));
			properties.setProperty(KEY_REPETITION_DEFAULT_CALIBRATE, "true");
			properties.setProperty(KEY_RENAME_TEMPLATES, parameter.getRenameTemplates());
			properties.setProperty(KEY_GNSS_SESSION_MERGE, String.valueOf(parameter.isGnssSessionMerge()));
			for (org.applied_geodesy.adjustment.network.ObservationType type : Klein_Method_Parameter.REPETITION_TYPES)
				properties.setProperty(KEY_REPETITION_MODE_PREFIX + type.name(), String.valueOf(parameter.getRepetitionMode(type)));
			properties.setProperty(KEY_GROUP_DAMPING_PHASE2_ENABLED, String.valueOf(parameter.isGroupDampingInPhase2Enabled()));
			properties.setProperty(KEY_SPLIT_NORMALIZATION_MODE, parameter.getSplitNormalizationMode().name());
			properties.setProperty(KEY_MATH_DEBUG_MODE, parameter.getMathDebugMode().name());
			properties.setProperty(KEY_GROUP_FLOOR_AFTER_PASS1_ENABLED, String.valueOf(parameter.isGroupFloorAfterPass1Enabled()));
			properties.setProperty(KEY_DECIMAL_PLACES, String.valueOf(parameter.getDecimalPlaces()));

			// Editierbare Gruppenunsicherheits-Grenzwerte - BEIDE Geraeteklassen-
			// Saetze, unabhaengig von der aktuell gewaehlten Geraeteklasse
			for (Klein_Method_Threshold_Type type : Klein_Method_Threshold_Type.valuesOfCategory(Klein_Method_Threshold_Type.Category.LOG_POST)) {
				for (Klein_Method_Device_Class deviceClass : new Klein_Method_Device_Class[] { Klein_Method_Device_Class.ELEKTRONISCH, Klein_Method_Device_Class.HISTORISCH }) {
					double[] floors = parameter.getGroupUncertaintyFloors(type, deviceClass);
					String[] suffixes = { "a", "b", "c" };
					for (int i = 0; i < 3; i++)
						properties.setProperty(KEY_GROUP_UNCERTAINTY_PREFIX + type.name() + "." + deviceClass.name() + "." + suffixes[i], String.valueOf(floors[i]));
				}
			}

			try (FileOutputStream output = new FileOutputStream(file)) {
				properties.store(output, "Klein_Method - dauerhafte Einstellungen (automatisch erzeugt)");
			}

		} catch (IOException e) {
			if (logger != null)
				logger.log("Klein_Method: Konnte Einstellungen nicht speichern (" + file.getAbsolutePath() + "): " + e.getMessage());
		}
	}
}
// V1.00_RC3_Klein_Method_Parameter_Store.java
