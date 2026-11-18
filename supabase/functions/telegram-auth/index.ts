// YouCode: вход через Telegram. Одна функция на три задачи:
//   POST {action:"start"}            — приложение просит ссылку на бота
//   POST {action:"poll", token}      — приложение ждёт подтверждения и получает сессию
//   POST от Telegram (webhook)       — бот получает /start <token> и нажатие кнопки «Да, это я»
// У функции должен быть ВЫКЛЮЧЕН Verify JWT: Telegram не шлёт JWT, а publishable-ключ не JWT.
import { createClient } from "npm:@supabase/supabase-js@2";

const BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
const BOT_USERNAME = (Deno.env.get("TELEGRAM_BOT_USERNAME") ?? "").replace(/^@/, "");
const WEBHOOK_SECRET = Deno.env.get("TELEGRAM_WEBHOOK_SECRET") ?? "";
// Технические адреса для аккаунтов Telegram. Письма на них никогда не отправляются.
// Домен должен иметь MX-записи, иначе Supabase отклонит адрес.
const EMAIL_DOMAIN = Deno.env.get("TG_EMAIL_DOMAIN") ?? "gmail.com";
// Ссылка на магазин для команды /store (необязательный секрет).
const STORE_URL = Deno.env.get("STORE_URL") ?? "";
const TTL_MS = 10 * 60 * 1000;

function pickKey(jsonName: string, legacyName: string): string {
  const raw = Deno.env.get(jsonName);
  if (raw) {
    try {
      const o = JSON.parse(raw);
      if (typeof o.default === "string") return o.default;
      const first = Object.values(o)[0];
      if (typeof first === "string") return first;
    } catch { /* падаем на старый ключ */ }
  }
  return Deno.env.get(legacyName) ?? "";
}

const URL_ = Deno.env.get("SUPABASE_URL")!;
const opts = { auth: { persistSession: false, autoRefreshToken: false } };
const admin = createClient(URL_, pickKey("SUPABASE_SECRET_KEYS", "SUPABASE_SERVICE_ROLE_KEY"), opts);
const anon = createClient(URL_, pickKey("SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY"), opts);

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function randomHex(bytes: number): string {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => b.toString(16).padStart(2, "0")).join("");
}

async function tg(method: string, payload: unknown) {
  await fetch(`https://api.telegram.org/bot${BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }).catch(() => {});
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ message: "method not allowed" }, 405);
  if (req.headers.get("x-telegram-bot-api-secret-token") !== null) return await webhook(req);
  const body = await req.json().catch(() => ({}));
  try {
    if (body.action === "start") return await start();
    if (body.action === "poll") return await poll(String(body.token ?? ""));
  } catch (e) {
    return json({ status: "error", message: e instanceof Error ? e.message : "Ошибка сервера" });
  }
  return json({ message: "bad request" }, 400);
});

async function start() {
  if (!BOT_TOKEN || !BOT_USERNAME || !WEBHOOK_SECRET) {
    return json({ message: "Не заданы секреты TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_USERNAME, TELEGRAM_WEBHOOK_SECRET" }, 500);
  }
  const token = randomHex(16); // 32 символа: влезает в параметр /start (до 64)
  const { error } = await admin.from("telegram_login_requests").insert({ token });
  if (error) return json({ message: `База данных: ${error.message}` }, 500);
  await admin.from("telegram_login_requests").delete().lt("created_at", new Date(Date.now() - TTL_MS * 3).toISOString());
  return json({ token, link: `https://t.me/${BOT_USERNAME}?start=${token}` });
}

async function poll(token: string) {
  if (!/^[0-9a-f]{32}$/.test(token)) return json({ status: "expired" });
  const { data } = await admin.from("telegram_login_requests").select("*").eq("token", token).maybeSingle();
  if (!data) return json({ status: "expired" });
  if (Date.now() - new Date(data.created_at).getTime() > TTL_MS) {
    await admin.from("telegram_login_requests").delete().eq("token", token);
    return json({ status: "expired" });
  }
  if (!data.telegram_id) return json({ status: "pending" });
  // Одноразовость: забираем запись; кто удалил — тот и получает сессию.
  const { data: removed } = await admin.from("telegram_login_requests").delete().eq("token", token).select("token");
  if (!removed || removed.length === 0) return json({ status: "expired" });
  return json({ status: "ok", session: await sessionFor(data) });
}

async function sessionFor(r: { telegram_id: number; first_name: string | null; username: string | null }) {
  const tgId = r.telegram_id;
  let email: string;
  const { data: acc } = await admin.from("telegram_accounts").select("user_id").eq("telegram_id", tgId).maybeSingle();
  if (acc) {
    const { data, error } = await admin.auth.admin.getUserById(acc.user_id);
    if (error || !data.user?.email) throw new Error("Не удалось найти аккаунт");
    email = data.user.email;
  } else {
    // Случайная часть в адресе: чужой регистрацией по почте этот адрес не занять.
    email = `tg${tgId}-${randomHex(4)}@${EMAIL_DOMAIN}`;
    const { data, error } = await admin.auth.admin.createUser({
      email,
      email_confirm: true,
      user_metadata: { username: `tg_${tgId}`, full_name: r.first_name ?? "", telegram_id: tgId, telegram_username: r.username ?? null },
    });
    if (error || !data.user) throw new Error(`Не удалось создать аккаунт: ${error?.message ?? "нет данных"}`);
    const { error: linkErr } = await admin.from("telegram_accounts").insert({ telegram_id: tgId, user_id: data.user.id });
    if (linkErr) {
      await admin.auth.admin.deleteUser(data.user.id);
      throw new Error("Повторите попытку входа");
    }
  }
  const { data: link, error: linkError } = await admin.auth.admin.generateLink({ type: "magiclink", email });
  const hashed = link?.properties?.hashed_token;
  if (linkError || !hashed) throw new Error(`Не удалось создать вход: ${linkError?.message ?? "нет токена"}`);
  const { data: v, error: verr } = await anon.auth.verifyOtp({ token_hash: hashed, type: "magiclink" });
  if (verr || !v.session || !v.user) throw new Error(`Не удалось войти: ${verr?.message ?? "нет сессии"}`);
  return {
    access_token: v.session.access_token,
    refresh_token: v.session.refresh_token,
    user: { id: v.user.id, email: v.user.email },
  };
}

async function link(token: string, from: { id: number; first_name?: string; username?: string }): Promise<boolean> {
  const { data } = await admin.from("telegram_login_requests").select("telegram_id, created_at").eq("token", token).maybeSingle();
  if (!data || data.telegram_id || Date.now() - new Date(data.created_at).getTime() > TTL_MS) return false;
  const { data: upd } = await admin.from("telegram_login_requests")
    .update({ telegram_id: from.id, first_name: from.first_name ?? null, username: from.username ?? null, linked_at: new Date().toISOString() })
    .eq("token", token).is("telegram_id", null).select("token");
  return !!upd && upd.length > 0;
}

async function webhook(req: Request): Promise<Response> {
  if (req.headers.get("x-telegram-bot-api-secret-token") !== WEBHOOK_SECRET || !WEBHOOK_SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  const update = await req.json().catch(() => null);
  try {
    const msg = update?.message;
    if (msg?.chat?.type === "private" && typeof msg.text === "string" && msg.text.startsWith("/start")) {
      const m = msg.text.match(/^\/start(?:@\w+)?\s+([0-9a-f]{32})$/);
      if (m) {
        // Подтверждение кнопкой: защита от случайной ссылки, присланной посторонним.
        await tg("sendMessage", {
          chat_id: msg.chat.id,
          text: "Вход в приложение.\n\nЕсли вы сами только что нажали «Войти через Telegram» в приложении — подтвердите вход. Если эту ссылку вам прислал кто-то другой, не нажимайте кнопку.",
          reply_markup: { inline_keyboard: [[{ text: "✅ Да, это я", callback_data: `ok:${m[1]}` }]] },
        });
      } else {
        await tg("sendMessage", { chat_id: msg.chat.id, text: "Откройте приложение и нажмите «Войти через Telegram»." });
      }
    }
    if (msg?.chat?.type === "private" && typeof msg.text === "string" && /^\/store(?:@\w+)?(\s|$)/.test(msg.text)) {
      if (STORE_URL) {
        await tg("sendMessage", {
          chat_id: msg.chat.id,
          text: "Магазин приложений You Store",
          reply_markup: { inline_keyboard: [[{ text: "🛍 Открыть магазин", url: STORE_URL }]] },
        });
      } else {
        await tg("sendMessage", { chat_id: msg.chat.id, text: "Ссылка на магазин пока не задана." });
      }
    }
    const cb = update?.callback_query;
    if (cb?.data?.startsWith("ok:") && cb.message?.chat?.type === "private") {
      const ok = await link(cb.data.slice(3), cb.from);
      await tg("answerCallbackQuery", { callback_query_id: cb.id });
      await tg("editMessageText", {
        chat_id: cb.message.chat.id,
        message_id: cb.message.message_id,
        text: ok
          ? "✅ Готово! Вернитесь в приложение — вход выполнится автоматически."
          : "Ссылка устарела. Вернитесь в приложение и нажмите «Войти через Telegram» ещё раз.",
      });
    }
  } catch (_) { /* Telegram всегда должен получить 200 */ }
  return new Response("ok");
}
