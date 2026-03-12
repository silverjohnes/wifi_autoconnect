# WiFi AutoConnect

[🇷🇺 Русский](#русский) | [🇬🇧 English](#english)

---

## Русский

Приложение для **рутованных Android-устройств**, которое автоматически переподключается к сохранённой Wi-Fi точке доступа в фоне.

Программа решает задачу подключения Андроид-магнитолы к точке доступа на iPhone. Проблема с таким подключением заключается в том, что на iPhone реализован проприетарный механизм вещания точки доступа, ограниченный только устройствами экосистемы Apple (то есть точку доступа с телефона всегда видят только другие устройства из экосистемы). При включении точки доступа на iPhone через Пункт управления, Настройки или другим способом (Команды при обнаружении автомобиля или чипа в зарядном устройстве) через короткое время видимость точки доступа пропадает.

При этом, таймаут подключения к Wi-fi-сетям у Андроида дольше, чем время работы точки, из-за этого магнитола может успеть, а может не успеть подключиться к телефону до выключения точки доступа. Программа запускает сервис, который автоматически подключается к заданной точке доступа с небольшим интервалом времени, достаточным чтобы гарантировать успешное подключение.

### Возможности

- 🔄 Периодически пытается переподключиться к целевой Wi-Fi сети
- 📱 Работает как foreground-сервис — выживает при выключенном экране и закрытом приложении
- 🔁 Автозапуск после перезагрузки устройства (через WorkManager)
- 🚫 Уважает ручное отключение Wi-Fi — не включает его обратно, если вы сами выключили
- 🌍 Локализовано на 18 языков (RU, EN, ZH, DE, FR, ES, PT, JA, KO, AR, TR, PL, CS, SK, SL, SR, BE, MK)
- 🔧 Использует root-оболочку (`su`) для подключения — работает на Android 10+, где стандартные Wi-Fi API ограничены

### Требования

- Android 10+ (minSdk 29)
- **Рутованное устройство** с Magisk (или любым root-менеджером, предоставляющим `su`)
- Целевая сеть должна быть хотя бы раз сохранена в настройках Wi-Fi Android

### Как это работает

Приложение открывает постоянный `su`-процесс (Magisk спрашивает разрешение один раз) и использует два метода по порядку:

1. **`wpa_cli select_network`** — напрямую командует демоном Wi-Fi; работает с любым типом безопасности (WPA2, WPA3, открытая)
2. **`cmd wifi connect-network`** — Shell API Android 10+ в качестве запасного варианта
3. **`wpa_cli` без указания интерфейса** — для прошивок, которые не предоставляют именованный интерфейс

`NetworkCallback` следит за реальным состоянием соединения, поэтому root-команды выполняются только при отсутствии подключения — лишних вызовов `su` нет.

### Установка и запуск

1. Установите APK
2. Откройте приложение — оно покажет имя текущей подключённой сети или предложит подключиться вручную
3. Подтвердите или введите SSID целевой сети
4. Выберите интервал переподключения (5 / 10 / 20 / 30 / 60 секунд)
5. Нажмите **Запустить сервис**
6. При первом запуске Magisk спросит разрешение на root — нажмите **Разрешить** и отметьте **Запомнить**

После этого сервис работает в фоне и тихо переподключается при потере соединения.

### Разрешения

| Разрешение | Зачем |
|---|---|
| `ACCESS_FINE_LOCATION` | Android требует его для чтения информации о Wi-Fi подключении |
| `ACCESS_NETWORK_STATE` | Отслеживание изменений состояния соединения |
| `ACCESS_WIFI_STATE` | Проверка состояния Wi-Fi (включён/выключен) |
| `FOREGROUND_SERVICE` | Удержание сервиса в живых |
| `RECEIVE_BOOT_COMPLETED` | Автозапуск после перезагрузки |

Разрешение на интернет не используется. Никакие данные не покидают устройство.

### Структура проекта

```
app/src/main/java/silver/wifiautoconnect/
├── MainActivity.kt         — UI: ввод SSID, выбор интервала, запуск/остановка
├── WifiConnectService.kt   — Foreground-сервис с циклом переподключения
├── RootShell.kt            — Постоянный su-процесс (синглтон)
├── BootReceiver.kt         — BOOT_COMPLETED → ставит задачу в WorkManager
└── BootStartWorker.kt      — WorkManager-воркер, запускающий сервис
```

### Сборка

1. Откройте в Android Studio (проверено на 2022.3+)
2. Создайте `local.properties` с путём к SDK (Android Studio делает это автоматически при первом открытии):
   ```
   sdk.dir=/Users/yourname/Library/Android/sdk
   ```
3. Build → Make Project, или запустите напрямую на устройстве

### Решение проблем

**«Сеть не найдена в сохранённых»**
→ Откройте настройки Wi-Fi, подключитесь к целевой сети вручную хотя бы один раз, затем попробуйте снова.

**«Root-доступ не предоставлен»**
→ Откройте Magisk → Список приложений → найдите WiFi AutoConnect → выдайте права суперпользователя.

**После перезагрузки сервис не запускается**
→ Некоторые прошивки с агрессивным энергосбережением убивают WorkManager. Отключите оптимизацию батареи для приложения: Настройки → Приложения → WiFi AutoConnect → Батарея → Без ограничений.

**Статус показывает «Сервис работает», но Wi-Fi не подключается**
→ Включите режим разработчика, подключитесь через ADB и проверьте логи:
```bash
adb logcat -s WifiConnectService
```

---

## English

An application for **rooted Android devices** that automatically reconnects to a saved Wi-Fi hotspot in the background. The program solves the problem of connecting an Android head unit to a hotspot on an iPhone.

The issue with such a connection is that the iPhone uses a proprietary hotspot broadcasting mechanism that is limited to devices within the Apple ecosystem (that is, the hotspot from the phone is always visible only to other devices from the ecosystem). When the hotspot is enabled on the iPhone via Control Center, Settings, or by other means (such as Shortcuts triggered by detecting a car or a chip in the charger), the hotspot visibility disappears after a short time.

At the same time, Android’s timeout for connecting to Wi-Fi networks is longer than the time the hotspot remains visible. Because of this, the head unit may or may not manage to connect to the phone before the hotspot disappears. The application runs a service that automatically attempts to connect to the specified hotspot at short intervals, sufficient to reliably ensure a successful connection

### Features

- 🔄 Periodically attempts to reconnect to a target Wi-Fi network
- 📱 Runs as a foreground service — survives screen-off and app close
- 🔁 Auto-starts after device reboot (via WorkManager)
- 🚫 Respects manual Wi-Fi disable — won't fight you if you turn it off yourself
- 🌍 Localized into 18 languages (RU, EN, ZH, DE, FR, ES, PT, JA, KO, AR, TR, PL, CS, SK, SL, SR, BE, MK)
- 🔧 Uses root shell (`su`) to connect — works on Android 10+ where standard Wi-Fi APIs are restricted

### Requirements

- Android 10+ (minSdk 29)
- **Rooted device** with Magisk (or any root manager that provides `su`)
- Target network must be saved in Android Wi-Fi settings at least once

### How it works

The app opens a persistent `su` shell (Magisk prompts once for permission) and uses three methods in order:

1. **`wpa_cli select_network`** — directly commands the Wi-Fi daemon; works with any security type (WPA2, WPA3, open)
2. **`cmd wifi connect-network`** — Android 10+ shell API as fallback
3. **`wpa_cli` without interface** — for firmwares that don't expose a named interface

A `NetworkCallback` monitors the actual connection state, so root commands are only issued when truly disconnected — no unnecessary `su` calls every tick.

### Setup

1. Install the APK
2. Open the app — it will show the currently connected network name, or prompt you to connect first
3. Confirm or type the target network SSID
4. Choose a reconnection interval (5 / 10 / 20 / 30 / 60 seconds)
5. Tap **Start service**
6. On first run, Magisk will ask for root permission — tap **Allow** and check **Remember**

After that the service runs silently in the notification bar and reconnects automatically whenever the connection drops.

### Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Required by Android to read Wi-Fi connection info |
| `ACCESS_NETWORK_STATE` | Monitor connection state changes |
| `ACCESS_WIFI_STATE` | Check Wi-Fi enabled/disabled status |
| `FOREGROUND_SERVICE` | Keep the service alive |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |

No internet permission is used. No data leaves the device.

### Project structure

```
app/src/main/java/silver/wifiautoconnect/
├── MainActivity.kt         — UI: SSID input, interval picker, start/stop
├── WifiConnectService.kt   — Foreground service with reconnect loop
├── RootShell.kt            — Persistent su process (singleton)
├── BootReceiver.kt         — BOOT_COMPLETED → enqueues WorkManager task
└── BootStartWorker.kt      — WorkManager worker that starts the service
```

### Building

1. Open in Android Studio (tested on 2022.3+)
2. Create `local.properties` with your SDK path (Android Studio does this automatically on first open):
   ```
   sdk.dir=/Users/yourname/Library/Android/sdk
   ```
3. Build → Make Project, or run directly on device

### Troubleshooting

**"Network not found in saved networks"**
→ Open Wi-Fi settings, connect to the target network manually at least once, then try again.

**"Root access not granted"**
→ Open Magisk → App list → find WiFi AutoConnect → grant Superuser access.

**Service shows as stopped after reboot**
→ Some aggressive battery-saving ROMs kill WorkManager. Try disabling battery optimization for the app: Settings → Apps → WiFi AutoConnect → Battery → Unrestricted.

**Status shows "Service running" but Wi-Fi doesn't connect**
→ Enable developer options, connect via ADB and check logs:
```bash
adb logcat -s WifiConnectService
```

### License

MIT
