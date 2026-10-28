# Сборка MamoKeep из исходников

Android-приложение собирается из этой папки. Фотографии для обучения OCR, ключ
разработчика и внутренние отчёты не нужны для сборки. Готовые ресурсы и используемая
приложением модель V5 включены в исходники. Состав и лицензии описаны в
[LICENSE-NOTICE.md](LICENSE-NOTICE.md).

## Окружение

- Последняя рабочая конфигурация проекта: JDK 24.0.2; Java target — 17.
- Android SDK Platform 37. Приложение поддерживает Android 8.0 / API 26 и выше;
  target SDK — 36.
- Gradle Wrapper 9.7.1 с закреплённым SHA-256 дистрибутива в
  [gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties).
- Версии Android Gradle Plugin, Kotlin, Compose, Room и остальных зависимостей
  указаны в [каталоге версий](gradle/libs.versions.toml).
- Репозитории зависимостей заданы в [settings.gradle.kts](settings.gradle.kts):
  Google Maven, Maven Central и Gradle Plugin Portal.

Установите JDK и Android SDK, задайте `JAVA_HOME` и `ANDROID_HOME` для своего
компьютера. Вместо `ANDROID_HOME` можно указать путь SDK в локальном
`local.properties` через `sdk.dir`. Не добавляйте этот файл в публичный архив.
При первой сборке нужны интернет и принятые владельцем SDK условия Android.
`--offline` подходит только для уже заполненного кеша Wrapper и зависимостей.

## Debug и локальные тесты

Из корня проекта:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  --no-daemon --no-parallel --max-workers=1 --no-configuration-cache \
  -Pkotlin.compiler.execution.strategy=in-process
```

На Windows используйте `gradlew.bat`. Результат —
`app/build/outputs/apk/debug/app-debug.apk`. Постоянный ключ разработчика для debug
не нужен. Python, обучение OCR и повторная обработка рисунков не требуются.

На компьютере с 8 ГБ памяти запускайте сборку при выключенном эмуляторе. Когда
сборка закончится, запускайте один эмулятор для проверки готового APK.

## Оптимизированный release

Для собственной release-сборки нужен ваш PKCS12-ключ. Настройте подпись локально:

```sh
python3 tools/configure_release_signing.py \
  --keystore /absolute/path/to/your-release.p12 \
  --keytool "$JAVA_HOME/bin/keytool" \
  --alias your-alias
```

[Помощник](tools/configure_release_signing.py) спрашивает пароль интерактивно,
проверяет ключ и сохраняет настройки в `local.properties` с доступом только
владельцу. Ключ, пароль и этот файл не входят в распространяемые исходники.
При отсутствии настройки release-сборка завершается явной ошибкой.

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease \
  --no-daemon --no-parallel --max-workers=1 --no-configuration-cache \
  -Pkotlin.compiler.execution.strategy=in-process
```

Результат — универсальный `app/build/outputs/apk/release/app-release.apk`;
включены R8 и удаление неиспользуемых ресурсов. Собственный сертификат не позволяет
обновить установку, подписанную другим ключом. Проверяйте такие сборки на отдельном
эмуляторе, сохраняя данные повседневной установки.

## Инструментальные тесты

Они работают с настоящими Android API, включая миграции Room. Сначала соберите
тестовые APK при выключенном эмуляторе:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  --no-daemon --no-parallel --max-workers=1 --no-configuration-cache \
  -Pkotlin.compiler.execution.strategy=in-process
```

Затем запустите выделенный тестовый эмулятор, проверьте его серийный номер командой
`adb devices` и выполните:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  --no-daemon --no-parallel --max-workers=1 --no-configuration-cache \
  -Pkotlin.compiler.execution.strategy=in-process
```

Замените `emulator-5554` на фактический номер эмулятора. Не направляйте эту команду
на телефон с личными данными: установка тестовых APK может удалить данные
приложения. Одна успешная сборка не подтверждает работу сценариев на устройстве.

## Модели и редактируемые рисунки

V5 находится в `app/src/main/assets/scanner/local-models/` вместе со словарём,
лицензией и сведениями о происхождении. Файлы v1 в
`app/src/androidTest/assets/ocr-v1/` служат эталоном инструментальных тестов.
Описание компонентов и адаптаций — в
[THIRD_PARTY.md](tools/document_scanner/THIRD_PARTY.md).
Частные фотографии и обучающий корпус исключены; воспроизведение обучения V5 этим
набором исходников не заявляется. OCR остаётся в раннем доступе, его результат
нужно проверить перед сохранением.

Мастера силуэтов транспорта и их обработчик сохранены в
`design/vehicle-silhouettes/`. Приложение использует уже готовые Android-ресурсы;
редактирование мастеров — отдельная операция, не часть обычной сборки.

Архив релиза дополнительно содержит `SOURCE-MANIFEST.json` с размерами и SHA-256
файлов и привязкой к APK. Это проверка состава, а не обещание побитовой
воспроизводимости подписанного APK. Результат независимой пересборки из архива
фиксируется отдельно рядом с распространяемым архивом.

## Фактически выбранные зависимости

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
  --no-daemon --no-parallel --max-workers=1 --no-configuration-cache
```

Команда выводит выбранные версии, включая транзитивные зависимости. Полные лицензии
поставляемых компонентов находятся в `app/src/main/assets/licenses/`; Maven-граф
сам по себе не заменяет уведомления о компонентах внутри нативных библиотек.
