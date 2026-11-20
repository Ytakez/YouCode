// Вызывается приложением: action "start" создаёт запрос входа, action "poll" выдаёт сессию после подтверждения в боте.
import { createClient } from "npm:@supabase/supabase-js@2";

// Клиент для обращений к БД. Через него нельзя вызывать verifyOtp/signIn: они сохраняют сессию пользователя
// внутри клиента, и все следующие запросы пошли бы уже с правами пользователя, а не service role.
const newClient = () =>
  createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
const admin = newClient();
const BOT_USERNAME = (Deno.env.get("TELEGRAM_BOT_USERNAME") ?? "").replace(/^@/, "");
const TTL_MS = 10 * 60 * 1000;
// Потолок неподтверждённых запросов: защита таблицы от переполнения теми, у кого есть publishable key.
const MAX_PENDING = 300;
const TABLE = "telegram_login_requests";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  let body: { action?: string; token?: string };
  try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }

  if (body.action === "start") {
    if (!BOT_USERNAME) return json({ error: "bot not configured" }, 500);
    await admin.from(TABLE).delete().lt("created_at", new Date(Date.now() - TTL_MS).toISOString());
    const { count } = await admin.from(TABLE).select("token", { count: "exact", head: true });
    if ((count ?? 0) >= MAX_PENDING) return json({ error: "too many requests" }, 429);
    const token = randomToken();
    const { error } = await admin.from(TABLE).insert({ token });
    if (error) return json({ error: "db" }, 500);
    return json({ token, url: `https://t.me/${BOT_USERNAME}?start=${token}`, link: `https://t.me/${BOT_USERNAME}?start=${token}` });
  }

  if (body.action === "poll") {
    const token = String(body.token ?? "");
    if (!/^[0-9a-f]{48}$/.test(token)) return json({ error: "bad token" }, 400);

    const { data: row } = await admin.from(TABLE).select("*").eq("token", token).maybeSingle();
    if (!row) return json({ status: "expired" }, 410);
    if (Date.now() - new Date(row.created_at).getTime() > TTL_MS) {
      await admin.from(TABLE).delete().eq("token", token);
      return json({ status: "expired" }, 410);
    }
    if (row.telegram_id == null) return json({ status: "pending" });

    // Забираем запись атомарно: токен работает один раз.
    const { data: taken } = await admin.from(TABLE).delete().eq("token", token).not("telegram_id", "is", null).select("*");
    if (!taken || taken.length === 0) return json({ status: "pending" });
    const claimed = taken[0];

    // Если сессию выдать не удалось, запрос возвращается в таблицу (с прежним created_at):
    // приложение сможет повторить опрос, а не начинать вход заново.
    const fail = async (error: string, detail?: string) => {
      await admin.from(TABLE).insert(claimed);
      return json({ error, detail }, 500);
    };

    try {
      const email = `tg${claimed.telegram_id}@users.youcode.app`;
      const username = claimed.telegram_username || `tg${claimed.telegram_id}`;
      // Для вернувшегося пользователя createUser вернёт «already registered» — это нормально.
      await admin.auth.admin.createUser({
        email,
        email_confirm: true,
        user_metadata: { username, telegram_id: claimed.telegram_id, first_name: claimed.first_name },
      });
      const { data: link, error: linkError } = await admin.auth.admin.generateLink({ type: "magiclink", email });
      const hashed = link?.properties?.hashed_token;
      if (linkError || !hashed) return await fail("link", linkError?.message);

      // Отдельный одноразовый клиент: его сессия не должна попасть в общий admin.
      const verifier = newClient();
      const { data: verified, error: verifyError } = await verifier.auth.verifyOtp({ token_hash: hashed, type: "magiclink" });
      const session = verified?.session;
      if (verifyError || !session?.access_token || !session?.refresh_token) {
        return await fail("session", verifyError?.message ?? "no session");
      }

      const userId = session.user?.id ?? verified?.user?.id ?? "";
      const userEmail = session.user?.email ?? verified?.user?.email ?? email;
      if (!userId) return await fail("user", "session without user id");

      return json({
        status: "ok",
        access_token: session.access_token,
        refresh_token: session.refresh_token,
        user: { id: userId, email: userEmail },
      });
    } catch (e) {
      return await fail("exception", e instanceof Error ? e.message : String(e));
    }
  }

  return json({ error: "unknown action" }, 400);
});
