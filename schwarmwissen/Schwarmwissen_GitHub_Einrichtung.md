# Schwarmwissen – Einrichtung bei GitHub

> **Ab V1.00 RC1:** Standard ist das öffentliche Repository `htw9056/klein-method-schwarm` mit Vermittlungsdienst – Anleitung `Schwarmwissen_Oeffentlich_mit_Vermittlungsdienst.md`. Diese Anleitung bleibt für den alten Übungsbetrieb gültig.

Anleitung für Klein_Method ab V0.395 (Stand V0.399).

> **Erst üben, ohne öffentlichen Zugang?** Dafür gibt es die Anleitung `Schwarmwissen_GitHub_Privat_Uebungsbetrieb.md` (privates Repository, ab V0.399). Später lässt sich das Repository mit wenigen Klicks öffentlich schalten. Die Einrichtung ist einmalig, kostenlos und nur für dich als Betreiber nötig. Die Nutzer von Klein_Method brauchen kein Konto.

---

## Überblick

| Was | Wozu |
|---|---|
| GitHub-Konto `htw9056` | Eigenes Konto nur für den Schwarm, getrennt von deinem privaten Konto |
| Öffentliches Repository `klein-method-schwarm-test` | Hier landen die Laufberichte als Issues, und hier liegen die Erfahrungswerte |
| Fine-grained Token (nur „Issues“) | Damit legt Klein_Method die Issues an. Löschen und Code ändern kann dieser Token nicht |
| GitHub Action (aus dem ZIP) | Sammelt die Berichte und berechnet die Erfahrungswerte (`erfahrungswerte.txt`) |

Lesen geht ohne Schlüssel: Klein_Method holt `erfahrungswerte.txt` direkt aus dem öffentlichen Repository.

**Namen frei wählbar:** Konto und Repository können anders heißen oder später auf GitHub umbenannt werden. Dann in Klein_Method im Bereich **„Schwarmwissen“** das Feld **„Repository“** anpassen (Format `Konto/Repository`, Standard `htw9056/klein-method-schwarm-test`). Der Token muss für genau dieses Repository freigegeben sein.

---

## Schritt 1 – Konto anlegen

1. `https://github.com` öffnen und auf **Sign up** klicken.
2. E-Mail-Adresse eingeben. Tipp: Eine eigene Adresse oder ein Alias, z. B. `deinname+schwarm@…`, falls dein Anbieter das unterstützt.
3. Benutzername: **`htw9056`** (oder ein anderer freier Name – dann in Klein_Method im Feld „Repository“ eintragen).
4. E-Mail bestätigen.
5. **Zwei-Faktor-Anmeldung einrichten**, wenn GitHub danach fragt (Settings → Password and authentication → Two-factor authentication), z. B. mit einer Authenticator-App. Die Wiederherstellungscodes sicher aufbewahren.

> GitHub erlaubt ausdrücklich ein zusätzliches kostenloses „Maschinenkonto“ pro Person für Automatisierungen. Missbrauch oder Spam trifft so nie dein persönliches Konto.

---

## Schritt 2 – Repository anlegen

1. Mit `htw9056` angemeldet oben rechts auf **„+“ → „New repository“**.
2. **Repository name:** `klein-method-schwarm-test`
3. **Description:** z. B. „Anonyme Laufberichte und Erfahrungswerte für Klein_Method (JAG3D)“
4. **Public** auswählen. Nötig, damit Klein_Method die Erfahrungswerte ohne Schlüssel lesen kann.
5. Haken bei **„Add a README file“**.
6. **Create repository**.

Die Adresse lautet dann: `https://github.com/htw9056/klein-method-schwarm-test`

---

## Schritt 3 – Actions Schreibrecht geben

Die Action muss `erfahrungswerte.txt` ins Repository schreiben dürfen.

1. Im Repository: **Settings → Actions → General**.
2. Unter **Actions permissions**: „Allow all actions and reusable workflows“ (Standard) belassen.
3. Unter **Workflow permissions**: **„Read and write permissions“** wählen.
4. **Save**.

---

## Schritt 4 – Fine-grained Token erzeugen

1. Oben rechts auf das Profilbild → **Settings**.
2. Links ganz unten: **Developer settings**.
3. **Personal access tokens → Fine-grained tokens → Generate new token**.
4. Ausfüllen:

| Feld | Eintrag |
|---|---|
| Token name | `klein-method-issues` |
| Expiration | längste angebotene Laufzeit (Ablaufdatum notieren) |
| Resource owner | `htw9056` |
| Repository access | **Only select repositories** → `klein-method-schwarm-test` |
| Permissions → Repository permissions | **Issues: Read and write** |
| alle anderen Rechte | **No access** lassen („Metadata: Read-only“ setzt GitHub automatisch) |

5. **Generate token** und den Token (beginnt mit `github_pat_…`) **sofort kopieren**. Er wird nur einmal angezeigt.

### Was dieser Token kann – und was nicht

| Kann | Kann nicht |
|---|---|
| Issues anlegen, kommentieren, bearbeiten, schließen | Issues löschen (nur Administrator) |
| | Code, Dateien, Einstellungen, andere Repositories |

---

## Schritt 5 – Token in Klein_Method ablegen

Der Token kommt in eine eigene Datei auf dem Rechner, **nie in den Quellcode**.

1. Eine Textdatei mit genau diesem Namen anlegen:
   - **Windows:** `C:\Users\<Benutzername>\.klein_method\schwarm_token.txt`
   - **Linux/macOS:** `~/.klein_method/schwarm_token.txt`
2. Inhalt: **nur der Token**, eine Zeile, ohne Leerzeichen davor oder danach.
3. Speichern. Beim nächsten Öffnen des Klein_Method-Tabs ist „Laufbericht teilen“ aktiv.

> Wichtig
> - Den Token **nicht** in den Chat, in E-Mails oder in ein Git-Repository geben. GitHub erkennt Schlüssel im Code und sperrt sie automatisch.
> - Wird der Token bekannt oder missbraucht: in den Developer settings **Revoke** klicken, neuen Token erzeugen, Datei ersetzen.
> - Vor dem Ablaufdatum einen neuen Token erzeugen und die Datei ersetzen.

---

## Schritt 6 – Action und Auswerteskript hochladen

Die Dateien liegen im ZIP von V0.395 im Ordner **`03_Schwarm_GitHub`**:

| Datei im ZIP | Ziel im Repository |
|---|---|
| `.github/workflows/schwarm_auswertung.yml` | `.github/workflows/schwarm_auswertung.yml` |
| `scripts/auswerten.py` | `scripts/auswerten.py` |

**Am PC:** Im Repository **„Add file → Upload files“**, die Ordner `.github` und `scripts` aus dem ZIP hineinziehen (Ordnerstruktur bleibt erhalten), unten **Commit changes**.

**Am Smartphone** (Ordner-Upload geht dort oft nicht):
1. **„Add file → Create new file“**.
2. Als Dateiname `.github/workflows/schwarm_auswertung.yml` eintippen. Die Schrägstriche legen die Ordner automatisch an.
3. Inhalt der Datei aus dem ZIP hineinkopieren, **Commit changes**.
4. Dasselbe für `scripts/auswerten.py`.

---

## Schritt 7 – Test

1. In Klein_Method einen Automatik-Lauf bis DONE rechnen (z. B. Testnetz komplett nach der Bereinigung grober Fehler).
2. **„Laufbericht teilen“** → Vorschau prüfen → **Senden**.
3. Im Repository unter **Issues** erscheint der Bericht. Die Action versieht ihn mit dem Label `laufbericht` (ungültige Berichte mit `ungueltig`).
4. Unter **Actions** läuft „Schwarm-Auswertung“ (grüner Haken = erfolgreich).
5. Im Repository liegt danach `erfahrungswerte.txt`.
6. In Klein_Method **„Erfahrungswerte abrufen (Schwarm)“** – die Werte erscheinen im Bereich „Schwarmwissen“.

Klappt ein Schritt nicht: Unter **Actions** den fehlgeschlagenen Lauf öffnen und die rote Zeile kopieren, damit wir den Fehler finden.

---

## Pflege

- **Spam:** Unerwünschte Issues schließen und mit dem Label `ungueltig` versehen – die Action zählt sie dann nie. Gezählt werden nur Berichte mit gültigem Format, DONE und freier/fixer Ausgleichung; je Projekt der letzte Lauf.
- **Umbenennen:** Konto oder Repository auf GitHub umbenannt? In Klein_Method das Feld „Repository“ anpassen und den Token für das neue Repository freigeben.
- **Diskussion:** Berichte können direkt in den Issues kommentiert werden – das ist der fachliche Austausch.
- **Updates:** Bringt eine neue Klein_Method-Version ein geändertes `scripts/auswerten.py` mit (z. B. V0.396: Punktwerte der dynamischen Ausgleichung), die Datei im Repository ersetzen („Edit“ → Inhalt einfügen → „Commit changes“).
- **Später bei Missbrauch:** Umstellung auf eine Zwischenstation (z. B. Cloudflare Worker), dann liegt der Token nicht mehr bei den Nutzern. Die Absendeadresse ist in Klein_Method dafür austauschbar.

---

## Datenschutz (was im Bericht steht)

Enthalten: Klein_Method-Version, Ausgleichungsstufe, Beobachtungstypen und Anzahl, Geräteklasse, Anzahl Punkte, Netzausdehnung (auf km gerundet), Einstellungen, Verlauf (Iterationen, Rechenzeit, Endgrund), endgültige Gruppenunsicherheiten und Kennzahlen, eine anonyme Projektkennung (Prüfsumme, nicht umkehrbar).

**Nicht enthalten:** Koordinaten, Punktnamen, Projektnamen, Dateipfade, Namen von Personen oder Kunden.

Der Nutzer sieht jeden Bericht vor dem Senden vollständig und entscheidet selbst.

---

## Checkliste

- [ ] Konto `htw9056` angelegt, Zwei-Faktor aktiv
- [ ] Repository `klein-method-schwarm-test` (Public) angelegt
- [ ] Actions: „Read and write permissions“ gesetzt
- [ ] Fine-grained Token nur für dieses Repository, nur „Issues: Read and write“
- [ ] Token in `.klein_method/schwarm_token.txt` abgelegt, Ablaufdatum notiert
- [ ] Workflow und Skript aus `03_Schwarm_GitHub` hochgeladen
- [ ] Testbericht gesendet, Action grün, `erfahrungswerte.txt` vorhanden
- [ ] Falls andere Namen: Feld „Repository“ in Klein_Method angepasst
