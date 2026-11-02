# Urheber, Herkunft und Änderungen

## Klein'sche Methode

Copyright © 2025–2026 Dipl.-Ing. (FH) Stefan Klein

Dieses Programm ist freie Software: Sie können es unter den Bedingungen der GNU General Public License, wie von der Free Software Foundation veröffentlicht, weitergeben und/oder verändern, entweder gemäß Version 3 der Lizenz oder (nach Ihrer Wahl) jeder späteren Version. Die Veröffentlichung erfolgt in der Hoffnung, dass es nützlich ist, aber **ohne jede Gewährleistung**, sogar ohne die implizite Gewährleistung der Marktreife oder der Verwendbarkeit für einen bestimmten Zweck. Details siehe `LICENSE`.

Betrifft: `JAG3D/src/org/applied_geodesy/klein_method` (Quelltext, Hilfe, Oberflächentexte), `schwarmwissen`, `docs`.

## JAG3D

Java·Applied·Geodesy·3D (JAG3D), Copyright © Michael Lösler, https://software.applied-geodesy.org, Quelltext: https://github.com/applied-geodesy/jag3d, Lizenz: GNU GPL v3.

Dieses Repository ist ein Fork von JAG3D. Die Klein'sche Methode ist eine **inoffizielle Erweiterung**. Sie wird vom Autor von JAG3D weder entwickelt noch unterstützt oder geprüft.

## Geänderte JAG3D-Dateien

Die folgenden Dateien stammen aus JAG3D und wurden für die Einbindung des Reiters „Klein'sche Methode“ geändert (Vermerk jeweils im Dateikopf):

| Datei | Änderung |
|---|---|
| `JAG3D/src/org/applied_geodesy/jag3d/ui/dialog/NetworkAdjustmentDialog.java` | gemeinsame Ausgleichungssperre mit der Automatik, Messung für den Wächter, Gültigkeit der Ergebnisse an die Erweiterung melden, Sperre während der Klein-Reiter aktiv ist |
| `JAG3D/src/org/applied_geodesy/jag3d/ui/tabpane/TabType.java` | neuer Reitertyp `KLEIN_METHOD` |
| `JAG3D/src/org/applied_geodesy/jag3d/ui/tabpane/UITabPaneBuilder.java` | Aufbau des Reiters „Klein'sche Methode“ |
| `JAG3D/src/org/applied_geodesy/jag3d/ui/tree/RootTreeItemValue.java` | Reiter „Klein'sche Methode“ beim Projektknoten anzeigen |

## Fremde Dienste

- Kartenhintergrund: © OpenStreetMap-Mitwirkende, https://www.openstreetmap.org/copyright (Kacheln von tile.openstreetmap.org, Nutzungsbedingungen beachten).
- Quasigeoid- und NTv2-Gitterdateien werden nicht mitgeliefert; sie unterliegen den Bedingungen ihrer jeweiligen Herausgeber.
