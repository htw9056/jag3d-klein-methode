# Schwarmwissen – privater Übungsbetrieb auf GitHub

> **Ab V1.00 RC1:** Standard ist das öffentliche Repository `htw9056/klein-method-schwarm` mit Vermittlungsdienst – Anleitung `Schwarmwissen_Oeffentlich_mit_Vermittlungsdienst.md`. Diese Anleitung bleibt für den alten Übungsbetrieb gültig.

Anleitung für Klein_Method ab V0.399 (Stand 28.09.2026). Sie ergänzt `Schwarmwissen_GitHub_Einrichtung.md`. Unterschied: Das Repository ist **privat**. Nur du siehst Berichte und Erfahrungswerte. So kannst du alles in Ruhe üben und später mit wenigen Klicks öffentlich schalten – oder wieder zurück.

---

## Überblick: privat oder öffentlich

| | Privat (Übungsbetrieb) | Öffentlich (später) |
|---|---|---|
| Wer sieht Berichte und Erfahrungswerte? | nur du (und Personen, die du ausdrücklich einlädst) | jeder im Internet |
| Wer kann Berichte senden? | nur Rechner mit deinem Token | jeder Rechner mit einem Token für das Repository |
| Erfahrungswerte abrufen | **nur mit Token** (Klein_Method ab V0.399) | auch ohne Token |
| GitHub Action | läuft, zählt auf das Freikontingent für private Repositories | läuft, unbegrenzt frei |
| Kosten | keine | keine |
| Umschalten | jederzeit, beliebig oft (Schritt 10 und 11) | |

> **Wichtig:** Das Abrufen der Erfahrungswerte aus einem privaten Repository braucht Klein_Method **V0.399** (Abruf mit Token). Mit V0.395–V0.398 meldet „Erfahrungswerte abrufen (Schwarm)“ bei privatem Repository „HTTP 404“. Berichte senden und die Auswertung durch die Action funktionieren schon mit V0.395.

---

## Schritt 1 – Konto anlegen

Wie in der Hauptanleitung (Schritt 1): eigenes Konto, z. B. **`htw9056`**, mit Zwei-Faktor-Anmeldung. Hast du es schon, weiter mit Schritt 2.

---

## Schritt 2 – Repository **privat** anlegen

1. Mit `htw9056` angemeldet oben rechts auf **„+“ → „New repository“**.
2. **Repository name:** `klein-method-schwarm-test` (so heißt das Übungs-Repository).
3. **Description:** z. B. „Übungsbetrieb Schwarmwissen Klein_Method“.
4. **Private** auswählen.
5. Haken bei **„Add a README file“**.
6. **Create repository**.

**Repository gibt es schon öffentlich?** Dann auf privat umstellen wie in Schritt 11 beschrieben.

---

## Schritt 3 – Actions Schreibrecht geben

1. Im Repository: **Settings → Actions → General**.
2. **Actions permissions:** „Allow all actions and reusable workflows“ belassen.
3. **Workflow permissions:** **„Read and write permissions“** wählen.
4. **Save**.

> Private Repositories haben im kostenlosen Tarif ein monatliches Freikontingent an Action-Minuten (bei GitHub Free derzeit 2 000 Minuten). Eine Auswertung dauert unter einer Minute. Das Kontingent siehst du unter Profilbild → **Settings → Billing and plans**. Ist es aufgebraucht, laufen Actions erst im nächsten Monat wieder, es entstehen keine Kosten.

---

## Schritt 4 – Token mit Leserecht erzeugen

Im privaten Betrieb braucht der Token **zusätzlich das Leserecht auf den Inhalt**, damit Klein_Method `erfahrungswerte.txt` abrufen kann.

1. Profilbild → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Ausfüllen:

| Feld | Eintrag |
|---|---|
| Token name | `klein-method-schwarm-test` |
| Expiration | längste angebotene Laufzeit (Ablaufdatum notieren) |
| Resource owner | `htw9056` |
| Repository access | **Only select repositories** → `klein-method-schwarm-test` |
| Repository permissions → **Issues** | **Read and write** (Berichte senden) |
| Repository permissions → **Contents** | **Read-only** (Erfahrungswerte abrufen) |
| alle anderen Rechte | **No access** („Metadata: Read-only“ setzt GitHub automatisch) |

3. **Generate token**, Token (`github_pat_…`) **sofort kopieren** – er wird nur einmal angezeigt.

**Du hast schon einen Token nur mit „Issues“?** Token öffnen → **Edit** → bei „Contents“ **Read-only** ergänzen → **Update**. Der Token-Text bleibt gleich, die Datei muss nicht geändert werden.

| Der Token kann | Der Token kann nicht |
|---|---|
| Berichte (Issues) anlegen und lesen | Issues löschen |
| `erfahrungswerte.txt` und andere Dateien lesen | Dateien ändern, Code ändern, Einstellungen ändern |
| | andere Repositories erreichen |

---

## Schritt 5 – Token in Klein_Method ablegen

1. Datei anlegen:
   - **Windows:** `C:\Users\<Benutzername>\.klein_method\schwarm_token.txt`
   - **Linux/macOS:** `~/.klein_method/schwarm_token.txt`
2. Inhalt: **nur der Token**, eine Zeile, ohne Leerzeichen.
3. Speichern, Klein_Method-Tab neu öffnen. „Laufbericht teilen“ ist jetzt aktiv.

> - Den Token **nie** in den Chat, in E-Mails, in Quellcode oder in ein Repository geben.
> - Bei Verdacht auf Missbrauch: in den Developer settings **Revoke**, neuen Token erzeugen, Datei ersetzen.
> - Vor dem Ablaufdatum neuen Token erzeugen und die Datei ersetzen.

**Windows-Tipp:** Der Explorer legt Ordner mit Punkt am Anfang ungern an. Eingabeaufforderung öffnen und `mkdir %USERPROFILE%\.klein_method` eingeben, dann die Datei mit dem Editor dort speichern (Dateityp „Alle Dateien“, damit kein `.txt.txt` entsteht).

---

## Schritt 6 – Repository in Klein_Method eintragen

Im Bereich **„Schwarmwissen“** das Feld **„Repository“** prüfen: `htw9056/klein-method-schwarm-test` (Format `Konto/Repository`). Bei anderem Namen diesen eintragen – Schreibweise genau wie auf GitHub (auf doppelte Bindestriche achten).

---

## Schritt 7 – Action und Auswerteskript hochladen

Aus dem Klein_Method-ZIP, Ordner **`03_Schwarm_GitHub`**:

| Datei im ZIP | Ziel im Repository |
|---|---|
| `.github/workflows/schwarm_auswertung.yml` | `.github/workflows/schwarm_auswertung.yml` |
| `scripts/auswerten.py` | `scripts/auswerten.py` |

**Am PC:** **„Add file → Upload files“**, die Ordner `.github` und `scripts` hineinziehen, **Commit changes**.

**Am Smartphone:** **„Add file → Create new file“**, als Dateiname `.github/workflows/schwarm_auswertung.yml` eintippen (die Schrägstriche legen die Ordner an), Inhalt einfügen, **Commit changes**. Dasselbe für `scripts/auswerten.py`.

---

## Schritt 8 – Test

1. In Klein_Method einen Automatik-Lauf bis DONE rechnen (z. B. Testnetz Leipzig).
2. **„Laufbericht teilen“** → Vorschau prüfen → **Senden**.
3. Im Repository unter **Issues** erscheint der Bericht, die Action setzt das Label `laufbericht` (ungültige Berichte: `ungueltig`).
4. Unter **Actions** läuft „Schwarm-Auswertung“ (grüner Haken).
5. Im Repository liegt danach `erfahrungswerte.txt`.
6. In Klein_Method **„Erfahrungswerte abrufen (Schwarm)“** (V0.399) – die Werte erscheinen im Bereich „Schwarmwissen“.

> **Erfahrungswerte erst ab 3 Projekten:** Startwerte und Warnungen nutzen nur Werte **anderer** Projekte, mindestens 3. Zum Üben also mehrere verschiedene Projekte (verschiedene Projektdateien) rechnen und teilen.

---

## Schritt 9 – Üben: Tipps für den Übungsbetrieb

- **Testberichte aussortieren:** Issue öffnen → rechts bei **Labels** `ungueltig` setzen und **Close issue**. Die Action zählt es dann nicht mehr (nach dem nächsten Lauf).
- **Berichte löschen:** Nur als Besitzer möglich (mit `htw9056` angemeldet): Issue öffnen → rechte Spalte ganz unten **„Delete issue“**. Gelöscht ist endgültig.
- **Auswertung neu anstoßen:** **Actions → Schwarm-Auswertung → Run workflow**. Sie läuft außerdem automatisch bei jedem neuen, geänderten oder geschlossenen Bericht, bei Label-Änderungen und einmal nachts.
- **Alles auf Anfang:** Alle Übungs-Issues löschen oder mit `ungueltig` schließen, dann die Auswertung neu laufen lassen – `erfahrungswerte.txt` wird neu berechnet.
- **Zweiter Rechner zum Üben:** Dieselbe Token-Datei dorthin kopieren. Beide Rechner senden dann als `htw9056`.
- **Andere Personen mitüben lassen (optional):** **Settings → Collaborators → Add people**. Eingeladene sehen alles, brauchen aber für Klein_Method trotzdem eine Token-Datei. Gib ihnen einen **eigenen** Token (Schritt 4, je Person einer), damit du einzelne sperren kannst.

---

## Schritt 10 – Später öffentlich schalten

Vorher prüfen:

- [ ] Übungs- und Fehlberichte gelöscht oder mit `ungueltig` geschlossen – nach dem Umschalten sind **alle** Issues, auch geschlossene, öffentlich sichtbar.
- [ ] Kommentare in den Issues enthalten nichts Vertrauliches (Kundennamen, Orte, Dateipfade).
- [ ] README beschreibt kurz, wozu das Repository dient.

Umschalten:

1. Im Repository **Settings → General**, ganz nach unten zur **„Danger Zone“**.
2. **„Change repository visibility“** → **„Change to public“**.
3. Die Sicherheitsabfrage bestätigen (Repository-Name eintippen).

Danach:

- Klein_Method ruft die Erfahrungswerte bei Nutzern **ohne** Token automatisch ohne Schlüssel ab. Mit Token funktioniert es weiter wie bisher.
- Der Token darf das Recht „Contents: Read-only“ behalten; nötig ist es dann nicht mehr.
- Die Action läuft bei öffentlichen Repositories ohne Minutenkontingent.
- Für weitere Nutzer gilt die Hauptanleitung `Schwarmwissen_GitHub_Einrichtung.md`.

---

## Schritt 11 – Wieder privat schalten

Jederzeit möglich, auf demselben Weg: **Settings → General → Danger Zone → Change repository visibility → Change to private**.

Beachten:

- Nutzer ohne Token können dann keine Erfahrungswerte mehr abrufen (Meldung „HTTP 404“), bereits heruntergeladene Werte bleiben bei ihnen erhalten.
- Sterne („Stars“) und Beobachter gehen beim Umschalten auf privat verloren.
- Kopien (Forks), die andere von einem öffentlichen Repository gemacht haben, bleiben bei denen bestehen. Was einmal öffentlich war, kann also schon kopiert worden sein. Deshalb: vor dem ersten Öffentlich-Schalten gründlich aufräumen (Schritt 10).

---

## Fehlersuche

| Meldung / Beobachtung | Ursache | Abhilfe |
|---|---|---|
| „Erfahrungswerte abrufen“: HTTP 404 | privates Repository und Klein_Method vor V0.399, oder keine Token-Datei, oder Name im Feld „Repository“ falsch, oder `erfahrungswerte.txt` gibt es noch nicht | V0.399 verwenden, Token-Datei prüfen, Namen prüfen, erst einen Bericht senden und Action abwarten |
| HTTP 401 | Token abgelaufen oder widerrufen | neuen Token erzeugen, Datei ersetzen |
| HTTP 403 | Token fehlt das Recht („Contents: Read-only“ bzw. „Issues: Read and write“) oder ist nicht für dieses Repository freigegeben | Token bearbeiten (Schritt 4) |
| „Laufbericht teilen“ ausgegraut | keine Token-Datei gefunden | Datei und Pfad prüfen (Schritt 5) |
| Action rot | Workflow-Rechte fehlen oder Minutenkontingent aufgebraucht | Schritt 3 prüfen; unter **Actions** den Lauf öffnen und die rote Zeile kopieren |
| Keine Startwerte trotz Berichten | weniger als 3 **andere** Projekte, oder die Werte weichen mehr als 10 % voneinander ab | weitere Projekte rechnen und teilen |

---

## Datenschutz

Auch im privaten Betrieb gilt: Berichte enthalten **keine** Koordinaten, Punktnamen, Projektnamen oder Dateipfade. Enthalten sind Version, Ausgleichungsstufe, Beobachtungstypen und -anzahl, Geräteklasse, Punktanzahl, Netzausdehnung (km), Einstellungen, Verlauf, Gruppen- bzw. Punktunsicherheiten, Kennzahlen der Festpunkt-Empfehlung (Längen, Anzahlen) und eine anonyme Projektkennung. Jeder Bericht wird vor dem Senden vollständig angezeigt.

---

## Checkliste Übungsbetrieb

- [ ] Konto `htw9056` mit Zwei-Faktor
- [ ] Repository **Private** angelegt
- [ ] Actions: „Read and write permissions“
- [ ] Token: nur dieses Repository, **Issues: Read and write** + **Contents: Read-only**
- [ ] Token-Datei `.klein_method/schwarm_token.txt`, Ablaufdatum notiert
- [ ] Feld „Repository“ in Klein_Method stimmt
- [ ] Workflow und Skript hochgeladen
- [ ] Testbericht gesendet, Action grün, `erfahrungswerte.txt` vorhanden
- [ ] Erfahrungswerte in Klein_Method abgerufen (V0.399)

## Checkliste vor dem Öffentlich-Schalten

- [ ] Übungsberichte gelöscht oder `ungueltig` + geschlossen
- [ ] Kommentare geprüft
- [ ] README aktuell
- [ ] Settings → General → Danger Zone → Change to public
