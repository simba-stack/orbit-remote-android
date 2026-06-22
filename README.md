# Orbit Remote — Android Agent

Android-агент Orbit Remote: устанавливается на телефон, регистрируется на сигнальном
сервере, отдаёт экран по WebRTC (аппаратный H.264) и исполняет удалённое управление
через Accessibility Service. Подключается к уже развёрнутому сигнальному серверу.

## Архитектура

Clean Architecture + MVVM + Hilt (DI) + Repository.

```
app/src/main/java/com/orbit/remote/
├── OrbitApp.kt                       # Application (Hilt)
├── di/AppModule.kt                   # Hilt-провайдеры (OkHttp, Json, SignalingClient)
├── domain/model/                     # Модели: AgentState, DeviceInfo, ControlMessage
├── data/
│   ├── settings/SettingsStore.kt     # DataStore: deviceId, code, trusted, URL
│   ├── device/DeviceInfoProvider.kt  # Модель, Android, батарея, память, IP
│   └── signaling/                    # WebSocket-клиент + протокол
├── webrtc/WebRtcManager.kt           # PeerConnection, ScreenCapturer (H.264), data channel
├── service/
│   ├── ScreenCaptureService.kt       # Foreground service (mediaProjection) — оркестратор
│   └── AgentStateHolder.kt           # Общее состояние сервис ↔ UI
├── accessibility/OrbitAccessibilityService.kt  # Жесты/ввод/навигация
├── system/                           # BootReceiver, PowerManagementHelper (вендоры)
└── ui/                               # MainActivity + Compose + ViewModel
```

## Как это работает

1. Приложение поднимает foreground service и подключается к сигнальному серверу
   (`wss://orbit-remote-signaling-production.up.railway.app/ws` по умолчанию).
2. Сервер выдаёт **Device ID** и **код подключения** — они показаны на главном экране.
3. Десктоп-клиент подключается по ID + коду. Сервер связывает стороны.
4. Контроллер шлёт WebRTC-offer → агент отдаёт экран (ScreenCapturerAndroid +
   аппаратный энкодер H.264) и открывает data channel.
5. По data channel приходят команды управления (тап/свайп/текст/клавиши), которые
   исполняет Accessibility Service официальными API (`dispatchGesture`,
   `performGlobalAction`, `ACTION_SET_TEXT`).

## Сборка

Требуется **Android Studio** (Koala+), JDK 17, Android SDK 34.

```
1. Открыть папку android/ в Android Studio.
2. Дать IDE синхронизировать Gradle (она доустановит Gradle wrapper при необходимости;
   либо выполнить `gradle wrapper` один раз, если установлен Gradle).
3. Run ▶ на устройстве/эмуляторе, или Build → Generate Signed Bundle/APK → APK (release).
```

> В песочнице, где генерировался проект, нет Android SDK, поэтому APK здесь не собирался.
> Это полноценный проект, который собирается в Android Studio; возможны мелкие правки
> версий зависимостей под вашу среду.

Сменить сигнальный сервер: `app/build.gradle.kts` → `DEFAULT_SIGNALING_URL`, либо через
`SettingsStore.setSignalingUrl(...)`.

## Что реализовано

- Регистрация устройства, постоянный Device ID, код подключения.
- Foreground service, который не останавливается при выключенном экране.
- Захват экрана через **MediaProjection + аппаратный MediaCodec H.264** (WebRTC
  `DefaultVideoEncoderFactory`, H264 high profile) — не последовательность скриншотов.
- Удалённое управление: тап, двойной тап, удержание, свайп, прокрутка, ввод текста,
  Назад/Домой/Recents/шторка/блокировка, запуск приложений.
- Синхронизация буфера обмена (ПК ↔ телефон) по data channel.
- Авто-реконнект сигнального соединения с экспоненциальным backoff.
- Обработка энергосбережения вендоров (Xiaomi/Redmi/Poco, Oppo/Realme, Vivo, OnePlus,
  Huawei/Honor, Samsung — через стандартный диалог + autostart-экраны).

## Системные ограничения Android (честно)

- **MediaProjection после перезагрузки**: Android требует заново подтвердить захват
  экрана пользователем после каждой перезагрузки — фоновое автовозобновление невозможно.
  `BootReceiver` показывает уведомление с предложением открыть приложение и переподключиться.
- **Передача файлов >2 ГБ и докачка**: транспорт (WebRTC data channel) реализован;
  чанкинг больших файлов и докачка — на стороне протокола файлов, который наращивается
  поверх data channel в десктоп-клиенте и агенте (следующий этап).
- **Autostart-экраны вендоров** различаются между прошивками — компоненты подобраны
  best-effort и при отсутствии открывают общий диалог.
- Полное сквозное шифрование обеспечивается самим WebRTC (DTLS-SRTP) поверх TLS-сигналинга.
