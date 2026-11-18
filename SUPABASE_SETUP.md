# YouCode 1.3.4 — Supabase backend foundation

Supabase project URL: `https://gzuzyagiqgvpeqkksaml.supabase.co`

The URL supplied as `/rest/v1/` has been normalized to the project root. The `sb_publishable_...` key is a client publishable key, not an admin credential.

## Setup
1. Supabase Dashboard → SQL Editor → run `supabase/setup.sql`.
2. Enable Email authentication in Authentication → Providers.
3. Register the owner account once Android Supabase Auth is implemented.
4. In Authentication → Users, copy the owner's UUID and run the final UPDATE statement in `setup.sql` to grant admin role.
5. Never place `service_role` / `sb_secret` in the Android app or public repository.

## Security and limits
The schema creates per-user profiles, chat sessions, messages, and usage records with Row Level Security. Clients cannot insert usage events or edit their own role/limits. A trusted Edge Function or gateway must validate the Supabase JWT, enforce quotas atomically, call OpenCode through an authenticated tunnel, and write usage using server-side credentials.

A phone generally has one shared public egress IP for all app users. Per-user quotas are application-level limits, not unique public IPs. Do not expose the OpenCode port directly to the internet. The phone must remain online and Android must keep the foreground service alive.

## Implementation status
This package adds the database/RLS foundation only. Android sign-in UI and the metered public AI gateway are not complete; per-user quotas are not enforced until every model request passes through that gateway. Existing local chat behavior remains unchanged.

## Подтверждение почты кодом (1.3.6)
Приложение просит ввести код из письма (`POST /auth/v1/verify`, type `signup`) и умеет отправить код повторно (`POST /auth/v1/resend`). Чтобы письмо приходило и содержало код, а не ссылку:

1. **Подключите свой SMTP** — Authentication → Emails → SMTP Settings (Resend, Brevo, Mailgun, Gmail SMTP и т.п.). Встроенная отправка Supabase рассчитана только на тесты: письма уходят лишь адресам из команды проекта (остальным — ошибка `email_address_not_authorized`) и не больше 2 в час на весь проект (`over_email_send_rate_limit`). Без своего SMTP код тем, кто не в команде, не придёт.
2. **Замените шаблон** — Authentication → Emails → «Confirm sign up»: вставьте содержимое `supabase/email-template-confirm-signup.html`. Главное — переменная `{{ .Token }}` вместо `{{ .ConfirmationURL }}`. (На новых бесплатных проектах шаблоны можно менять только после подключения SMTP.)
3. Убедитесь, что включено Authentication → Providers → Email → **Confirm email**.
4. При необходимости поднимите лимит писем: Authentication → Rate Limits.

Если письмо не пришло, причина видна в Dashboard → Authentication → Logs (ошибка SMTP или лимит).

## Вход через Telegram (1.3.8)
Не требует SMTP и писем. Приложение просит у функции `telegram-auth` ссылку на бота и открывает Telegram. Пользователь жмёт «Запустить» и «Да, это я», бот сообщает об этом функции, приложение само получает сессию Supabase. Аккаунт создаётся при первом входе.

1. Telegram → @BotFather → `/newbot` → придумайте имя и username (заканчивается на `bot`). Сохраните **токен бота** и username.
2. Supabase → SQL Editor → выполните `supabase/telegram.sql`.
3. Supabase → Edge Functions → Deploy a new function → Via Editor. Имя строго `telegram-auth`, код — содержимое `supabase/functions/telegram-auth/index.ts`. Deploy.
4. В настройках этой функции **выключите Verify JWT**. Telegram не присылает JWT, а ключ `sb_publishable_...` JWT не является, поэтому с включённой проверкой приложение получит 401.
5. Edge Functions → Secrets → добавьте три секрета:
   - `TELEGRAM_BOT_TOKEN` — токен от BotFather
   - `TELEGRAM_BOT_USERNAME` — username бота без `@`
   - `TELEGRAM_WEBHOOK_SECRET` — любая случайная строка из латинских букв, цифр, `_` и `-`
6. Привяжите бота к функции: откройте в браузере (подставьте свои значения)
   `https://api.telegram.org/bot<ТОКЕН>/setWebhook?url=https://gzuzyagiqgvpeqkksaml.supabase.co/functions/v1/telegram-auth&secret_token=<ТОТ_ЖЕ_СЕКРЕТ>`
   Ответ должен содержать `"ok":true`. Проверить привязку: `https://api.telegram.org/bot<ТОКЕН>/getWebhookInfo`.
7. В приложении нажмите «Войти через Telegram».

Технические адреса Telegram-аккаунтов имеют вид `tg<id>-<случайно>@gmail.com`; письма на них никогда не отправляются. Домен можно сменить секретом `TG_EMAIL_DOMAIN` (у домена должны быть MX-записи). Если токен бота когда-либо попадёт в чужие руки — перевыпустите его в BotFather (`/revoke`) и обновите секрет.

8. (необязательно) Команда бота `/store`: добавьте секрет `STORE_URL` — полная ссылка на магазин с `https://`. Без него бот ответит, что ссылка не задана.
