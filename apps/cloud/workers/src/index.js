const EVENT_TYPES = new Set([
  "SHOP_INITIALIZED", "SHOP_SETTINGS_UPDATED", "CARD_TYPE_CREATED",
  "MEMBER_CREATED", "MEMBER_UPDATED", "MEMBER_DELETED", "CARD_OPENED",
  "CARD_STATUS_CHANGED", "CARD_TRANSACTION", "POINTS_CHANGED"
]);

const json = (body, status = 200, extra = {}) => new Response(JSON.stringify(body), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra }
});
const ok = (data) => json({ ok: true, data });
const fail = (status, code, message) => json({ ok: false, error: { code, message } }, status);
const now = () => Date.now();

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function canonical(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
}

async function hmacHex(secret, value) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const digest = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function bearer(request, token) {
  return Boolean(token) && request.headers.get("authorization") === `Bearer ${token}`;
}

function validId(value) { return typeof value === "string" && value.length >= 1 && value.length <= 128; }
function safeEqual(left, right) {
  if (typeof left !== "string" || typeof right !== "string" || left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i += 1) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}
function parsePayload(value) {
  try { const parsed = JSON.parse(value); return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null; }
  catch (_) { return null; }
}

async function signedEvents(request, env, raw) {
  const timestamp = request.headers.get("x-liteshop-timestamp");
  const nonce = request.headers.get("x-liteshop-nonce");
  const signature = request.headers.get("x-liteshop-signature");
  if (!timestamp || !nonce || !signature || !/^\d{10,16}$/.test(timestamp) || !validId(nonce)) return false;
  if (Math.abs(now() - Number(timestamp)) > 300000) return false;
  const expected = await hmacHex(env.LITESHOP_TERMINAL_TOKEN, `${timestamp}\n${nonce}\n${raw}`);
  if (!safeEqual(expected, signature.toLowerCase())) return false;
  const used = await env.DB.prepare("SELECT nonce FROM request_nonce WHERE nonce=? AND expires_at>?").bind(nonce, now()).first();
  if (used) return false;
  await env.DB.prepare("INSERT INTO request_nonce(nonce,expires_at) VALUES (?,?)").bind(nonce, now() + 300000).run();
  await env.DB.prepare("DELETE FROM request_nonce WHERE expires_at<?").bind(now()).run();
  return true;
}

async function own(db, entityId, event) {
  if (!validId(entityId)) throw new Error("invalid entity reference");
  const owner = await db.prepare("SELECT shop_id,device_id FROM entity_owner WHERE entity_id=?").bind(entityId).first();
  if (owner && (owner.shop_id !== event.shop_id || owner.device_id !== event.device_id)) throw new Error("entity belongs to another terminal");
  await db.prepare("INSERT OR IGNORE INTO entity_owner(entity_id,shop_id,device_id) VALUES (?,?,?)").bind(entityId, event.shop_id, event.device_id).run();
}

async function project(db, event) {
  const payload = event.payload;
  const stamp = now();
  if (["MEMBER_CREATED", "MEMBER_UPDATED", "MEMBER_DELETED"].includes(event.event_type)) {
    await db.prepare("INSERT INTO member_projection(member_id,shop_id,payload,updated_at) VALUES (?,?,?,?) ON CONFLICT(member_id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at,shop_id=excluded.shop_id").bind(event.entity_id, event.shop_id, JSON.stringify(payload), stamp).run();
  } else if (["CARD_OPENED", "CARD_STATUS_CHANGED"].includes(event.event_type)) {
    await db.prepare("INSERT INTO card_projection(card_id,member_id,shop_id,payload,updated_at) VALUES (?,?,?,?,?) ON CONFLICT(card_id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at,shop_id=excluded.shop_id").bind(event.entity_id, payload.member_id || "", event.shop_id, JSON.stringify(payload), stamp).run();
  } else if (event.event_type === "POINTS_CHANGED") {
    if (!validId(payload.member_id) || typeof payload.balance_after !== "number" || !Number.isInteger(payload.balance_after)) throw new Error("invalid points projection");
    const points = { member_id: payload.member_id, balance: payload.balance_after, updated_at: event.created_at };
    await db.prepare("INSERT INTO points_projection(member_id,shop_id,payload,updated_at) VALUES (?,?,?,?) ON CONFLICT(member_id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at,shop_id=excluded.shop_id").bind(payload.member_id, event.shop_id, JSON.stringify(points), stamp).run();
  } else if (event.event_type === "CARD_TRANSACTION") {
    if (!validId(payload.card_id) || !Number.isInteger(payload.balance_after) || !Number.isInteger(payload.times_after)) throw new Error("invalid card transaction");
    const card = await db.prepare("SELECT payload FROM card_projection WHERE card_id=?").bind(payload.card_id).first();
    if (!card) throw new Error("card must be opened before transactions");
    const current = parsePayload(card.payload);
    current.balance = payload.balance_after; current.remaining_times = payload.times_after;
    current.version = (current.version || 1) + 1; current.updated_at = event.created_at;
    await db.prepare("UPDATE card_projection SET payload=?,updated_at=? WHERE card_id=?").bind(JSON.stringify(current), stamp, payload.card_id).run();
  }
}

async function ingest(env, events) {
  if (!Array.isArray(events) || events.length > 500) throw new Error("events must be an array with at most 500 items");
  const accepted = [];
  for (const event of events) {
    if (!event || typeof event !== "object" || !validId(event.event_id) || !validId(event.shop_id) || !validId(event.device_id) || !validId(event.event_type) || !validId(event.entity_id) || !event.payload || typeof event.payload !== "object") throw new Error("invalid event shape");
    if (!Number.isInteger(event.sequence) || event.sequence < 1 || !Number.isInteger(event.created_at) || event.created_at < 0 || event.schema_version !== 1 || !EVENT_TYPES.has(event.event_type)) throw new Error("invalid event schema");
    const digest = await sha256Hex(canonical(event));
    const previous = await env.DB.prepare("SELECT fingerprint FROM cloud_event WHERE event_id=?").bind(event.event_id).first();
    if (previous) { if (previous.fingerprint !== digest) throw new Error("event_id already used for different content"); accepted.push({ event_id: event.event_id, status: "ALREADY_ACCEPTED" }); continue; }
    const last = await env.DB.prepare("SELECT COALESCE(MAX(sequence),0) AS sequence FROM cloud_event WHERE shop_id=? AND device_id=?").bind(event.shop_id, event.device_id).first();
    if (event.sequence !== Number(last.sequence) + 1) throw new Error(`sequence must follow last accepted event: ${last.sequence}`);
    for (const field of ["shop_id", "device_id"]) if (event.payload[field] && event.payload[field] !== event[field]) throw new Error(`payload ${field} does not match event`);
    await own(env.DB, event.entity_id, event);
    for (const field of ["member_id", "card_id"]) if (event.payload[field]) await own(env.DB, event.payload[field], event);
    await env.DB.prepare("INSERT INTO cloud_event(event_id,shop_id,device_id,sequence,schema_version,event_type,entity_id,payload,fingerprint,created_at,received_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)").bind(event.event_id, event.shop_id, event.device_id, event.sequence, 1, event.event_type, event.entity_id, JSON.stringify(event.payload), digest, event.created_at, now()).run();
    await project(env.DB, event);
    await env.DB.prepare("INSERT INTO terminal_registry(device_id,shop_id,last_seen) VALUES (?,?,?) ON CONFLICT(device_id) DO UPDATE SET shop_id=excluded.shop_id,last_seen=excluded.last_seen").bind(event.device_id, event.shop_id, now()).run();
    accepted.push({ event_id: event.event_id, status: "ACCEPTED" });
  }
  return accepted;
}

async function member(env, memberId) {
  const m = await env.DB.prepare("SELECT payload,updated_at FROM member_projection WHERE member_id=?").bind(memberId).first();
  if (!m) return null;
  const data = parsePayload(m.payload);
  const cards = await env.DB.prepare("SELECT payload FROM card_projection WHERE member_id=?").bind(memberId).all();
  const points = await env.DB.prepare("SELECT payload FROM points_projection WHERE member_id=?").bind(memberId).first();
  data.cards = cards.results.map((row) => parsePayload(row.payload));
  data.points = points ? parsePayload(points.payload) : { member_id: memberId, balance: 0 };
  data.last_synced_at = m.updated_at;
  return data;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/healthz") return ok({ service: "cloud-api", runtime: "cloudflare-workers" });
    if (request.method === "POST" && url.pathname === "/api/v1/terminal/sync/events") {
      if (!bearer(request, env.LITESHOP_TERMINAL_TOKEN)) return fail(401, "UNAUTHORIZED", "鉴权失败");
      const raw = await request.text();
      if (!(await signedEvents(request, env, raw))) return fail(401, "INVALID_SIGNATURE", "同步请求签名无效或已重放");
      try { const body = JSON.parse(raw); return ok({ accepted: await ingest(env, body.events) }); }
      catch (error) { return fail(400, "INVALID_EVENT", error.message); }
    }
    if (request.method === "GET" && url.pathname === "/api/v1/miniapp/me") {
      if (!bearer(request, env.LITESHOP_MINIAPP_TOKEN)) return fail(401, "UNAUTHORIZED", "鉴权失败");
      const id = request.headers.get("x-liteshop-member"); if (!id) return fail(400, "MEMBER_REQUIRED", "需要绑定会员");
      return ok(await member(env, id));
    }
    if (request.method === "GET" && url.pathname === "/api/v1/cloud/shops") {
      if (!bearer(request, env.LITESHOP_MINIAPP_TOKEN)) return fail(401, "UNAUTHORIZED", "鉴权失败");
      const result = await env.DB.prepare("SELECT shop_id,COUNT(*) AS member_count FROM member_projection GROUP BY shop_id ORDER BY shop_id").all(); return ok(result.results);
    }
    if (request.method === "GET" && url.pathname === "/api/v1/cloud/members") {
      if (!bearer(request, env.LITESHOP_MINIAPP_TOKEN)) return fail(401, "UNAUTHORIZED", "鉴权失败");
      const query = url.searchParams.get("shop_id"); const result = query ? await env.DB.prepare("SELECT member_id,shop_id,payload FROM member_projection WHERE shop_id=?").bind(query).all() : await env.DB.prepare("SELECT member_id,shop_id,payload FROM member_projection").all();
      return ok(result.results.map((row) => ({ member_id: row.member_id, shop_id: row.shop_id, ...parsePayload(row.payload) })));
    }
    return fail(404, "NOT_FOUND", "接口不存在");
  }
};
