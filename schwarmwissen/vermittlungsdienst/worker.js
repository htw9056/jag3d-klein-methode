// Klein_Method Schwarmwissen - Vermittlungsdienst (Cloudflare Worker)
//
// Nimmt anonyme Laufberichte von Klein_Method entgegen, prueft sie und legt sie als Issue im
// Schwarm-Repository an. Der GitHub-Token liegt nur hier als Geheimnis (GITHUB_TOKEN), nie im Programm.
//
// Einstellungen im Cloudflare-Dashboard (Worker -> Settings -> Variables and Secrets):
//   GITHUB_TOKEN  (Typ Secret)  Fine-grained Token, nur dieses Repository, nur "Issues: Read and write"
//   REPOSITORY    (Typ Text)    htw9056/klein-method-schwarm
// Optional (Bremse gegen Massen-Spam): KV-Namespace mit dem Bindungsnamen LIMITS.
//
// Schnittstelle: POST /bericht  {"repository": "...", "title": "[Laufbericht] ...", "body": "..."}
//   201 {"url": "<Issue-Adresse>"} | 4xx/5xx {"error": "<Grund>"}
//   GET / -> kurze Statusauskunft (ohne Token)

const MARKER = "KLEIN_METHOD_LAUFBERICHT format=1";
const TITLE_PREFIX = "[Laufbericht]";
const TYPES = new Set([
  "HORIZONTAL_DIRECTION_LOG_POST", "HORIZONTAL_DISTANCE_LOG_POST", "VERTICAL_DIRECTION_LOG_POST",
  "SLOPE_DISTANCE_LOG_POST", "NIVELLEMENT_LOG_POST",
  "GNSS_BASELINE_1D_LOG_POST", "GNSS_BASELINE_2D_LOG_POST", "GNSS_BASELINE_3D_LOG_POST",
]);
const POINT_TYPES = new Set();
for (const kind of ["GRUPPE", "MEDIAN"]) for (const d of [1, 2, 3]) POINT_TYPES.add(`PUNKT${kind}_${d}D`);
const DEVICE_CLASSES = new Set(["ELEKTRONISCH", "HISTORISCH", "KOMBINIERT"]);
const FIXED_POINT_CLASSES = new Set(["GNSS_REFERENZ", "TP_AP", "HOEHENFESTPUNKT", "SONSTIGE"]);
const SIGMA_KEY = /^sigma\.([A-Z0-9_]+)\.([abcs])$/;
const PROJECT = /^[0-9a-f]{12}$/;
const TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;
const MAX_BODY = 16000;     // Zeichen
const MAX_TITLE = 200;
const MAX_LINES = 300;
export const MAX_VALUE = 1000.0;  // Plausibilitaetsgrenze je Genauigkeit (mgon, mm, ppm)
const PER_SENDER_PER_DAY = 20;
const ALL_PER_DAY = 500;

function reply(status, object) {
  return new Response(JSON.stringify(object), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

export function parseReport(body) {
  if (!body || !body.includes(MARKER)) return null;
  const match = /```[a-z]*\n([\s\S]*?)```/.exec(body);
  if (!match) return {};
  const report = {};
  for (const line of match[1].split("\n")) {
    if (line.includes("=") && !line.startsWith("#")) {
      const i = line.indexOf("=");
      report[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
  }
  return report;
}

/** null = gueltig, sonst Grund */
export function validate(report) {
  if (report.format !== "1") return "Format";
  if (!PROJECT.test(report.projekt || "")) return "Projektkennung";
  if (!DEVICE_CLASSES.has(report.geraeteklasse)) return "Geraeteklasse";
  const dynamic = report.stufe === "DYNAMIC";
  if (dynamic && !FIXED_POINT_CLASSES.has(report.festpunktart)) return "Festpunktart";
  if (!TIME.test(report.zeit || "")) return "Zeit";
  for (const [key, value] of Object.entries(report)) {
    const m = SIGMA_KEY.exec(key);
    if (!m) continue;
    const allowed = dynamic ? POINT_TYPES : TYPES;
    if (!allowed.has(m[1]) || (m[2] === "s") !== dynamic) return "Schluessel " + key;
    const number = Number(value);
    if (value === "" || !Number.isFinite(number)) return "Zahl " + key;
    if (number < 0 || number > MAX_VALUE) return "unplausibler Wert " + key;
  }
  return null;
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

/** Zaehler je Tag (optional, nur mit KV-Bindung LIMITS). true = Grenze ueberschritten. */
async function overLimit(env, request) {
  if (!env.LIMITS) return false;
  const day = new Date().toISOString().slice(0, 10);
  const sender = await sha256((request.headers.get("cf-connecting-ip") || "?") + "|" + day);
  const keys = [[`s:${day}:${sender}`, PER_SENDER_PER_DAY], [`a:${day}`, ALL_PER_DAY]];
  for (const [key, limit] of keys) {
    const n = parseInt((await env.LIMITS.get(key)) || "0", 10);
    if (n >= limit) return true;
  }
  for (const [key] of keys) {
    const n = parseInt((await env.LIMITS.get(key)) || "0", 10);
    await env.LIMITS.put(key, String(n + 1), { expirationTtl: 172800 });
  }
  return false;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const repository = (env.REPOSITORY || "").trim();
    if (request.method === "GET" && url.pathname === "/")
      return reply(200, { dienst: "Klein_Method Schwarmwissen - Vermittlungsdienst", repository, bereit: Boolean(env.GITHUB_TOKEN && repository) });
    if (request.method !== "POST" || url.pathname !== "/bericht") return reply(404, { error: "unbekannter Pfad" });
    if (!env.GITHUB_TOKEN || !repository) return reply(503, { error: "Dienst nicht eingerichtet (GITHUB_TOKEN/REPOSITORY fehlen)" });
    const text = await request.text();
    if (text.length > MAX_BODY + 1000) return reply(413, { error: "Bericht zu gross" });
    let data;
    try { data = JSON.parse(text); } catch (e) { return reply(400, { error: "kein gueltiges JSON" }); }
    const { title, body } = data || {};
    if (typeof title !== "string" || typeof body !== "string") return reply(400, { error: "title/body fehlen" });
    if (data.repository && String(data.repository).trim().toLowerCase() !== repository.toLowerCase())
      return reply(400, { error: `dieser Dienst sendet nur an ${repository}` });
    if (!title.startsWith(TITLE_PREFIX) || title.length > MAX_TITLE) return reply(400, { error: "kein Laufbericht-Titel" });
    if (body.length > MAX_BODY || body.split("\n").length > MAX_LINES) return reply(413, { error: "Bericht zu gross" });
    const report = parseReport(body);
    if (!report) return reply(400, { error: "kein Laufbericht (Kennung fehlt)" });
    const reason = validate(report);
    if (reason) return reply(422, { error: "Bericht ungueltig: " + reason });
    if (await overLimit(env, request)) return reply(429, { error: "Tagesgrenze erreicht" });
    const response = await fetch(`https://api.github.com/repos/${repository}/issues`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Klein_Method-Vermittlungsdienst",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ title, body }),
    });
    if (response.status !== 201) {
      const detail = response.status === 401 ? "Token abgelaufen oder widerrufen"
        : response.status === 403 || response.status === 404 ? "Token ohne Recht fuer dieses Repository" : "GitHub-Fehler";
      return reply(502, { error: `GitHub HTTP ${response.status}: ${detail}` });
    }
    const issue = await response.json();
    return reply(201, { url: issue.html_url });
  },
};
