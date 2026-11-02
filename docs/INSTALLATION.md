# Installation

Verfasser: Dipl.-Ing. (FH) Stefan Klein

## Weg 1a: Fertiges Programm für Windows (ohne Eclipse, ohne installiertes Java)

1. Auf der Seite [Releases](https://github.com/htw9056/jag3d-klein-methode/releases/latest) die Datei `JAG3D_Klein_Methode_V1.00_RC3_Windows.zip` herunterladen.
2. Entpacken, z. B. nach `C:\JAG3D_Klein`. Der Ordner enthält `jag3d.exe`, `jag3d.jar` (mit Klein'scher Methode), `jag3d.l4j.ini` sowie die Ordner `openjdk`, `javafx`, `lib` und `resources`.
3. **Arbeitsspeicher erhöhen:** `jag3d.l4j.ini` mit dem Editor öffnen und die Zeile mit `-Xmx` anpassen bzw. ergänzen, z. B. `-Xmx6g` bei 16 GB RAM. Für GNSS-Massendaten ist der Standard von 1 GB zu wenig.
4. `jag3d.exe` starten. Im Reiter „Klein'sche Methode“ steht oben die Version, F1 öffnet die Hilfe.

Das Paket beruht auf dem offiziellen JAG3D-Paket für Windows (JAG3D v20260906); nur `jag3d.jar` ist durch die Fassung mit Klein'scher Methode ersetzt.

## Weg 1b: Fertiges Programm für Linux und macOS

1. **Java 25 oder neuer** installieren, z. B. Eclipse Temurin oder Azul Zulu (JRE genügt). Prüfen im Terminal mit `java -version`. Auf Macs mit Apple-Chip eine **x64**-Java-Fassung verwenden (läuft über Rosetta 2), weil das mitgelieferte JavaFX für Intel gebaut ist.
2. Auf der Seite [Releases](https://github.com/htw9056/jag3d-klein-methode/releases/latest) `JAG3D_Klein_Methode_V1.00_RC3_Linux.zip` bzw. `JAG3D_Klein_Methode_V1.00_RC3_MacOSX.zip` herunterladen und entpacken. JavaFX ist enthalten.
3. Starten:
   - **Linux:** im entpackten Ordner `./jag3d.sh` (falls nötig vorher `chmod +x jag3d.sh`).
   - **macOS:** Doppelklick auf `jag3d.command`. Meldet macOS beim ersten Mal „nicht verifizierter Entwickler“: Rechtsklick → Öffnen → Öffnen.
   - Alternativ in beiden Fällen: `java -Xmx4g -jar jag3d.jar`
4. **Arbeitsspeicher:** im Startskript den Wert `-Xmx4g` anpassen, z. B. `-Xmx6g` bei 16 GB RAM.

Die Pakete beruhen auf den offiziellen JAG3D-Paketen v20260906 für Linux und macOS; nur `jag3d.jar` ist durch die Fassung mit Klein'scher Methode ersetzt, dazu kommen Startskript und `LIESMICH.txt`.

## Weg 2: Aus den Quellen bauen (Eclipse)

Dieses Repository ist ein Fork von https://github.com/applied-geodesy/jag3d, die Erweiterung ist bereits eingebaut.

1. Repository herunterladen (grüner Knopf „Code“ → „Download ZIP“ oder per Git klonen) und den Ordner `JAG3D` als Projekt in Eclipse importieren (Java 25 oder neuer, JavaFX wie im JAG3D-Projekt eingerichtet).
2. Projekt aktualisieren (F5) und übersetzen. Start wie gewohnt über `org.applied_geodesy.jag3d.JAG3DApp`.
3. **Export als JAR:** Rechtsklick auf das Projekt → Export → Java → **JAR file** (nicht „Runnable JAR“):
   - links nur `JAG3D/src` anhaken, rechts nichts,
   - „Export generated class files and resources“ einschalten (damit Hilfe und Sprachdateien ins JAR kommen),
   - Dateiname `jag3d.jar`,
   - auf der letzten Seite „Use existing manifest from workspace“ → `/JAG3D/META-INF/MANIFEST.MF`.
4. Das erzeugte `jag3d.jar` im offiziellen JAG3D-Paket für Windows (https://software.applied-geodesy.org) gegen die dortige Datei austauschen.

## Schwarmwissen (optional)

Die Erweiterung kann anonyme Laufberichte teilen und Erfahrungswerte abrufen. Voreingestellt ist das öffentliche Repository `htw9056/klein-method-schwarm` mit einem Vermittlungsdienst, Senden ist ohne eigenen Token möglich. Wer einen eigenen Austausch betreiben möchte, findet alles Nötige im Ordner `schwarmwissen`. Berichte enthalten keine Koordinaten, keine Punkt- und keine Projektnamen.
