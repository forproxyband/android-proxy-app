# Proxy Agent — управление приложением через ADB

Что можно делать с агентом по ADB: **управлять сервисом без экрана**,
**проверить, что прокси поднялся после ребута**, **снять логи приложения и
системный `logcat`**. Предполагается, что ADB и телефон у вас уже под рукой.

> **Пакет:** `com.proxyagent.app` · процессы: `:main` (UI), `:proxy` (туннель)
> **`<KEY>`** — `Agent Key` из настроек / QR-кода агента. Нужен **каждой**
> команде управления, включая `status` (иначе `error: unauthorized`).

---

## 1. Управление агентом без экрана (headless)

Сервис `ProxyService` не экспортирован — напрямую через `am start-service`
его не дёрнуть. Официальный интерфейс — broadcast в `RemoteControlReceiver`:

```bash
adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
  -a com.proxyagent.app.REMOTE_CONTROL --es cmd <CMD> --es key <KEY>
```

`<CMD>`: `start` · `stop` · `toggle` · `status`.

| Команда | Что делает |
| --- | --- |
| `start` | поднять агента (эквивалент кнопки `START` на экране) |
| `stop` | остановить |
| `toggle` | переключить (запущен → стоп, иначе → старт) |
| `status` | вернуть текущее состояние, ничего не меняя |

Ответ приходит в строке `Broadcast completed: ... data="..."`:

| `data="…"` | Значение |
| --- | --- |
| `status: connected` / `running` | ✅ агент работает |
| `status: stopped` | сервис не запущен |
| `error: unauthorized` | неверный или пустой `<KEY>` |
| `error: not configured` | нет сохранённого host/port/key — сперва настроить (QR/Settings) |

> `status` тоже требует `<KEY>` — без ключа состояние не опросить.

---

## 2. Проверить, что прокси работает (в т.ч. после ребута)

Три независимых признака — от быстрого к железному.

**1) Опрос состояния** (не зависит от экрана и от уведомлений):
```bash
adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
  -a com.proxyagent.app.REMOTE_CONTROL --es cmd status --es key <KEY>
```
`status: connected/running` → работает. Это самый надёжный признак —
отвечает даже на headless-устройстве, где уведомления скрыты.

**2) Процесс `:proxy` жив:**
```bash
adb shell pidof com.proxyagent.app:proxy
```
Вернулся PID → туннельный процесс запущен. Пусто → не запущен.

**3) Лог загрузки — почему поднялся / не поднялся** (сразу после ребута,
и после разблокировки, если стоит PIN):
```bash
adb logcat -d -s ProxyAgent.BootReceiver ProxyAgent.RemoteCtl ProxyAgent.PkgReplaced
```
- `auto-restarted ProxyService after boot (…)` — штатный автозапуск сработал;
- `remote start (…)` — поднял root-скрипт автозапуска;
- `notifications DISABLED …` — прокси работает, уведомление скрыто (см. §5);
- пусто / нет `was_running` — сессия НЕ была активна до ребута (был `STOP`),
  подниматься по правилу нечему (это by design).

### Если после ребута не поднялся
1. **Блокировка экрана.** С PIN/паролем агент стартует только **после
   первой ручной разблокировки** — до неё зашифрованное хранилище с
   настройками недоступно (Android Direct Boot). Для автономных ребутов —
   снять блокировку или использовать root-автозапуск.
2. Подождать ~15 с, повторить опрос `status` (автозапуск бывает с задержкой).
3. Поднять вручную: `--es cmd start --es key <KEY>` (см. §1).
4. Если и это не помогает — прошивка режет автозапуск
   (Xiaomi/Huawei/Samsung/Oppo/Vivo/OnePlus): на телефоне
   **Настройки → Приложения → Proxy Agent** включить «Автозапуск» и убрать
   из-под экономии батареи. Либо держать агента поднятым командой `start`.

---

## 3. Логи приложения (`agent.log`)

Человекочитаемый лог агента + шапка device-info (версия, статус,
регистратор, скорость) — самый полезный для разработчика.

**Способ А — кнопкой (есть доступ к экрану):** в приложении раскрыть блок
**`LOGS`** → **`SAVE`** → в chooser сохранить в **Downloads** → забрать:
```bash
adb pull /sdcard/Download/proxy-agent-<ДАТА-ВРЕМЯ>.log
```
Имя — `proxy-agent-YYYYMMDD-HHMMSS.log`. Список:
`adb shell ls -t /sdcard/Download/ | grep proxy-agent`.
(До первого запуска агента `SAVE` выдаст Toast «No log to save yet».)

**Способ Б — только root (без экрана):** внутренний лог в приватной папке,
обычным `adb pull` не берётся.
```bash
adb shell "su -c 'cat /data/data/com.proxyagent.app/files/agent.log'" > agent.log
```
Снапшоты после `SAVE` — в `/data/data/com.proxyagent.app/files/exports/`.

> Без root и без экрана внутренний лог не достать (release-сборка, `run-as`
> не работает) — снимайте `logcat` (§4).

---

## 4. Системный `logcat` по приложению

Доступен всегда, экран и root не нужны.

**По процессу приложения (компактно):**
```bash
adb logcat -d --pid=$(adb shell pidof -s com.proxyagent.app) > logcat-app.txt
```

**По тегам приложения** (если нужно и `:proxy`, и `:main`, и ресиверы):
```bash
adb logcat -d -s ProxyAgent ProxyAgent-Android ProxyAgent-Android-OTA \
  ProxyAgent.BootReceiver ProxyAgent.RemoteCtl ProxyAgent.PkgReplaced \
  ProxyAgent.Autostart > logcat-app.txt
```

**Поймать проблему «с чистого листа»:**
```bash
adb logcat -c                    # очистить буфер
# ...воспроизвести (ребут / рестарт / ротация)...
adb logcat -d > logcat-repro.txt
```

Теги приложения: `ProxyAgent`, `ProxyAgent-Android`, `ProxyAgent-Android-OTA`,
`ProxyAgent-SelfTest`, `ProxyAgent.BootReceiver`, `ProxyAgent.RemoteCtl`,
`ProxyAgent.PkgReplaced`, `ProxyAgent.Autostart`.

---

## 5. Уведомление скрыто, а прокси работает (Android 13+)

Если приложение на устройстве **ни разу не открывали** (развёрнуто через
ADB/root), система прячет уведомление в шторке — не выдано runtime-право
`POST_NOTIFICATIONS`. **Сам прокси при этом исправен** — проверяйте через
`cmd status` (§2). Чтобы уведомление появилось — открыть приложение один раз
и разрешить уведомления, либо выдать право напрямую:
```bash
adb shell pm grant com.proxyagent.app android.permission.POST_NOTIFICATIONS
```

---

## 6. Шпаргалка

```bash
# работает ли прокси?
adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
  -a com.proxyagent.app.REMOTE_CONTROL --es cmd status --es key <KEY>
adb shell pidof com.proxyagent.app:proxy

# поднять / остановить
adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
  -a com.proxyagent.app.REMOTE_CONTROL --es cmd start --es key <KEY>
adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
  -a com.proxyagent.app.REMOTE_CONTROL --es cmd stop  --es key <KEY>

# почему не поднялся после ребута
adb logcat -d -s ProxyAgent.BootReceiver ProxyAgent.RemoteCtl

# логи: приложения (после LOGS→SAVE→Downloads) и системный
adb pull /sdcard/Download/proxy-agent-*.log
adb logcat -d --pid=$(adb shell pidof -s com.proxyagent.app) > logcat-app.txt
```

## 7. Частые затыки

| Симптом | Причина / решение |
| --- | --- |
| `status`/`start` → `error: unauthorized` | неверный `<KEY>` — актуальный Agent Key из настроек/QR |
| `error: not configured` | не сохранены host/port/key — настроить через QR/Settings |
| `status: stopped` после ребута | блокировка экрана (Direct Boot) / OEM режет автозапуск / был `STOP` до ребута — см. §2 |
| `adb pull` не находит лог | сперва `LOGS → SAVE` на телефоне и сохранить в Downloads (§3) |
| Прокси работает, уведомления нет | Android 13+, не выдан `POST_NOTIFICATIONS` — §5, работоспособность проверять через `cmd status` |
| Не достать `agent.log` без root | снимать `logcat` (§4) — доступен всегда |
