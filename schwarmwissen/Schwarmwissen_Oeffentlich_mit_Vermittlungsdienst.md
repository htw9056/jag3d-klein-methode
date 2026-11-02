# Schwarmwissen – öffentlicher Betrieb mit Vermittlungsdienst

Anleitung für Klein_Method ab V1.00 RC1. Sie ersetzt für den Dauerbetrieb die beiden älteren Anleitungen (`Schwarmwissen_GitHub_Einrichtung.md`, `Schwarmwissen_GitHub_Privat_Uebungsbetrieb.md`). Das private Übungs-Repository `klein-method-schwarm-test` kann bestehen bleiben.

**Ziel:** Jeder Anwender von Klein_Method kann Erfahrungswerte abrufen und Laufberichte senden, **ohne eigenen Token und ohne GitHub-Konto**. Der Token liegt nur an einer Stelle: geschützt im Vermittlungsdienst bei Cloudflare. Er steht nie im Programm.

Die Einrichtung ist einmalig, kostenlos und dauert etwa 30 Minuten. Alles geht auch am Smartphone im Browser.

---

## Überblick

| Baustein | Wozu | Wo |
|---|---|---|
| Öffentliches Repository `htw9056/klein-method-schwarm` | Berichte (Issues) und Erfahrungswerte (`erfahrungswerte.txt`) | GitHub |
| GitHub Action + `auswerten.py` | prüft die Berichte, berechnet die Erfahrungswerte | GitHub |
| Fine-grained Token „Vermittlungsdienst“ | legt die Issues an | **nur** als Geheimnis bei Cloudflare |
| Vermittlungsdienst `worker.js` | nimmt Berichte von Klein_Method an, prüft sie, legt das Issue an | Cloudflare Workers (kostenlos bis 100.000 Aufrufe am Tag) |

So läuft es in Klein_Method:

| Aktion | Mit eigener Token-Datei | Ohne Token-Datei |
|---|---|---|
| Erfahrungswerte abrufen | direkt von GitHub | direkt von GitHub (öffentlich) |
| Laufbericht teilen (Knopf) | direkt an GitHub | über den Vermittlungsdienst; ist er nicht erreichbar, Angebot „Im Browser senden“ |
| Automatisch teilen nach DONE | direkt an GitHub | über den Vermittlungsdienst |

---

## Teil A – GitHub

### Schritt 1 – Repository öffentlich anlegen

1. Mit dem Konto `htw9056` bei GitHub anmelden.
2. Oben rechts **„+“ → New repository**.
3. Repository name: **`klein-method-schwarm`**, Sichtbarkeit **Public**.
4. Haken bei **„Add a README file“** (damit der Zweig `main` entsteht).
5. **Create repository**.

### Schritt 2 – Actions Schreibrecht geben

**Settings → Actions → General → Workflow permissions → „Read and write permissions“ → Save.**

### Schritt 3 – Action und Auswerteskript hochladen

Aus dem Klein_Method-ZIP, Ordner `03_Schwarm_GitHub`:

| Datei im ZIP | Ziel im Repository |
|---|---|
| `.github/workflows/schwarm_auswertung.yml` | `.github/workflows/schwarm_auswertung.yml` |
| `scripts/auswerten.py` | `scripts/auswerten.py` |

**Am PC:** **„Add file → Upload files“**, die Ordner `.github` und `scripts` hineinziehen, **Commit changes**.
**Am Smartphone:** **„Add file → Create new file“**, als Dateiname `.github/workflows/schwarm_auswertung.yml` eintippen (die Schrägstriche legen die Ordner an), Inhalt einfügen, **Commit changes**. Dasselbe für `scripts/auswerten.py`.

> `auswerten.py` aus V1.00 RC1 prüft zusätzlich, ob jede Genauigkeit plausibel ist (0 bis 1000 mgon/mm/ppm). Unsinnige Werte bekommen das Label `ungueltig` und zählen nicht. Bitte diese Fassung verwenden, auch im alten Übungs-Repository.

### Schritt 4 – Token für den Vermittlungsdienst erzeugen

Profilbild → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.

| Feld | Eintrag |
|---|---|
| Token name | `klein-method-vermittlungsdienst` |
| Expiration | längste angebotene Laufzeit (Ablaufdatum im Kalender notieren) |
| Resource owner | `htw9056` |
| Repository access | **Only select repositories** → `klein-method-schwarm` |
| Repository permissions → **Issues** | **Read and write** |
| alle anderen Rechte | **No access** („Metadata: Read-only“ setzt GitHub automatisch) |

**Generate token** und den Token (`github_pat_…`) kopieren. Er wird nur einmal angezeigt. Er kommt **nur** in Schritt 7 zu Cloudflare – nicht in eine Datei, nicht in den Chat, nicht in eine E-Mail.

### Schritt 5 – eigener Token (nur falls du selbst direkt senden willst)

Dein bisheriger Token (Datei `schwarm_token.txt`) gilt nur für `klein-method-schwarm-test`. Zwei Möglichkeiten:

- **Einfach:** Token-Datei auf deinem Rechner löschen oder umbenennen. Dann sendet auch dein Klein_Method über den Vermittlungsdienst.
- **Direkt senden:** Token öffnen → **Edit** → bei „Repository access“ zusätzlich `klein-method-schwarm` auswählen → **Update**. Der Token-Text bleibt gleich.

---

## Teil B – Cloudflare

### Schritt 6 – Konto und Worker anlegen

1. Auf **dash.cloudflare.com** ein kostenloses Konto anlegen (E-Mail bestätigen).
2. Links **Workers & Pages → Create → Worker** (bzw. „Start with Hello World“).
3. Beim ersten Worker fragt Cloudflare nach der **workers.dev-Subdomain**: **`htw9056`** eingeben.
4. Name des Workers: **`klein-method-schwarm`** → **Deploy**.
5. **Edit code**: den gesamten Beispielcode löschen, den Inhalt von `03_Schwarm_GitHub/vermittlungsdienst/worker.js` einfügen → **Deploy**.

Die Adresse lautet dann **`https://klein-method-schwarm.htw9056.workers.dev`** – das ist der Standard in Klein_Method.

> **Andere Adresse?** Ist die Subdomain `htw9056` vergeben oder heißt der Worker anders, steht die Adresse oben im Worker. Dann in Klein_Method im Bereich „Schwarmwissen“ das Feld **„Vermittlungsdienst“** anpassen und mir die Adresse nennen – sie wird dann in der nächsten Version Standard für alle.

### Schritt 7 – Token und Repository eintragen

Im Worker: **Settings → Variables and Secrets → Add**:

| Type | Variable name | Value |
|---|---|---|
| **Secret** | `GITHUB_TOKEN` | Token aus Schritt 4 |
| **Text** | `REPOSITORY` | `htw9056/klein-method-schwarm` |

**Deploy** bzw. **Save**. Ein Secret ist danach auch für dich nicht mehr lesbar, nur ersetzbar.

### Schritt 8 – Bremse gegen Massen-Spam (empfohlen)

1. Links **Storage & Databases → KV → Create** (Name z. B. `klein-method-limits`).
2. Im Worker **Settings → Bindings → Add → KV namespace**, Variable name **`LIMITS`**, den Namespace auswählen → **Deploy**.

Dann nimmt der Dienst je Absender höchstens 20 und insgesamt höchstens 500 Berichte am Tag an. Ohne diese Bindung läuft der Dienst trotzdem, nur ohne Zählung. Die IP-Adresse wird nicht gespeichert, nur ein täglich wechselnder Prüfwert.

### Schritt 9 – Prüfen

Die Adresse des Workers im Browser öffnen. Erwartet:

```
{"dienst":"Klein_Method Schwarmwissen - Vermittlungsdienst","repository":"htw9056/klein-method-schwarm","bereit":true}
```

`"bereit":false` bedeutet: Secret oder `REPOSITORY` fehlt (Schritt 7).

---

## Teil C – Test mit Klein_Method

1. Einen Automatik-Lauf bis DONE rechnen.
2. Bereich „Schwarmwissen“: **„Laufbericht teilen“** → Vorschau → **Senden**. Der Tooltip des Knopfs zeigt, auf welchem Weg gesendet wird.
3. Auf GitHub unter **Issues** erscheint der Bericht, die Action setzt das Label `laufbericht`.
4. Unter **Actions** läuft „Schwarm-Auswertung“ (grüner Haken), danach liegt `erfahrungswerte.txt` im Repository.
5. In Klein_Method **„Erfahrungswerte abrufen (Schwarm)“**.

---

## Was der Vermittlungsdienst prüft

- Nur `POST /bericht` mit einem Laufbericht: Titel beginnt mit `[Laufbericht]`, Kennung `KLEIN_METHOD_LAUFBERICHT format=1`, höchstens 16.000 Zeichen.
- Format wie `auswerten.py`: Projektkennung (12 Hex-Zeichen), Geräteklasse, Festpunktart, Zeit, nur bekannte Genauigkeits-Schlüssel, Werte 0 bis 1000.
- Gesendet wird nur an das eingetragene Repository.
- Über den Dienst kann niemand Issues bearbeiten, schließen, Labels ändern oder lesen.

**Grenzen:** Ein gefälschter Bericht im richtigen Format mit plausiblen Werten lässt sich technisch nicht von einem echten unterscheiden. Dagegen helfen die Tagesgrenzen, die 10-%-Regel und die Mindestzahl von 3 Projekten in Klein_Method, und die Moderation: Ein Issue mit dem Label **`ungueltig`** zählt nie.

---

## Pflege

| Wann | Was |
|---|---|
| vor Ablauf des Tokens | neuen Token (Schritt 4) erzeugen, in Cloudflare das Secret `GITHUB_TOKEN` ersetzen. Klein_Method braucht kein Update |
| Verdacht auf Missbrauch | Token auf GitHub **Revoke**, neuen erzeugen, Secret ersetzen; verdächtige Issues mit Label `ungueltig` versehen |
| Dienst abschalten | Worker in Cloudflare löschen oder Secret entfernen. Klein_Method bietet dann „Im Browser senden“ an |

---

## Fehlersuche

| Meldung in Klein_Method | Ursache | Abhilfe |
|---|---|---|
| Vermittlungsdienst …: HTTP 503 | Secret oder `REPOSITORY` fehlt | Schritt 7 |
| Vermittlungsdienst …: HTTP 502 – GitHub HTTP 401 | Token abgelaufen oder widerrufen | neuer Token, Secret ersetzen |
| Vermittlungsdienst …: HTTP 502 – GitHub HTTP 403/404 | Token nicht für `klein-method-schwarm` freigegeben oder ohne „Issues: Read and write“ | Token bearbeiten (Schritt 4) |
| Vermittlungsdienst …: HTTP 400 – dieser Dienst sendet nur an … | Feld „Repository“ in Klein_Method passt nicht zum Dienst | Repository-Feld auf `htw9056/klein-method-schwarm` |
| Vermittlungsdienst …: HTTP 422 | Bericht ungültig oder unplausibel | Bericht prüfen; bei echtem Bericht melden |
| Vermittlungsdienst …: HTTP 429 | Tagesgrenze erreicht | am nächsten Tag erneut senden |
| ConnectException / keine Verbindung | Dienst nicht eingerichtet oder Adresse falsch | Schritt 6 und 9, Feld „Vermittlungsdienst“ |
| Erfahrungswerte abrufen: HTTP 404 | `erfahrungswerte.txt` gibt es noch nicht | erst einen Bericht senden und die Action abwarten |

---

## Datenschutz

Die Berichte enthalten keine Koordinaten, keine Punkt- und keine Projektnamen – nur Genauigkeiten, Anzahlen, Einstellungen und eine anonyme Projektkennung. Sie sind im öffentlichen Repository für jeden lesbar. Der Vermittlungsdienst speichert nichts außer den optionalen Tageszählern.
