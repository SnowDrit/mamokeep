<a href="README.ru.md"><kbd>Русская версия →</kbd></a>

# MamoKeep

An Android app for tracking vehicle maintenance: mileage, fuel fill-ups, expenses,
work performed, and document deadlines. No registration, ads, or paid features.

**A release candidate has been published on [GitHub](https://github.com/SnowDrit/mamokeep/releases/tag/v0.1.0-rc.20261028). Store publication is being prepared.**
OCR is available as an early version: review recognition results before saving.

## Features

- Multiple vehicles, maintenance and fuel logs, reminders by date and mileage.
- Local storage; backup and restore through a file you choose.
- Export vehicle data to an app of your choice, including an AI service.
  The app does not send exports to an AI service on its own. Review free-form notes
  before sharing: hiding the VIN and vehicle name does not remove their contents.
- Import a receipt image, PDF, or text through the app, Android “Share,” or
  “Open with.” Processing takes place on the device; the result becomes a draft
  for review, and a person confirms the amounts and saves it.
- Built-in OCR V5 model that must be explicitly enabled; the original v1 is retained
  for rollback. A limited comparison does not guarantee accuracy on every new receipt.
- Step-by-step onboarding, help, and app language selection.

There is no dedicated server or user accounts. The optional OCR catalog and packages
are downloaded from GitHub. Backups and exports can be sent to a file provider of
your choice, including a cloud provider; external links open in a browser.
[Privacy policy](https://snowdrit.github.io/privacy-policies/mamokeep/#en).
Boosty offers a way to support development voluntarily; it does not unlock app features.

## Building and project structure

Kotlin, Jetpack Compose, Room, WorkManager; OCR uses ONNX Runtime CPU and OpenCV.
Requires Android 8.0 / API 26 or later. No dependency on Google Mobile Services.

The [build guide](BUILD_FROM_SOURCE.md) covers the environment, tests, debug builds,
and optimized release builds with your own signing key. The public source does not
include the developer’s key, local settings, or training photos.

- `app/src/main/` — application and ready-to-use resources.
- `app/src/test/`, `app/src/androidTest/` — local and Android tests.
- `app/schemas/` — Room schema history.
- `design/vehicle-silhouettes/` — editable artwork and its processor.
- `gradle/libs.versions.toml` — dependency versions.

## Licenses and contributing

Code is licensed under [GNU GPLv3](LICENSE); original presets, help, and translations
are licensed under CC BY-SA 4.0. Third-party libraries, icons, and models retain
their own licenses. See the [exact scope, attribution, and full license texts](LICENSE-NOTICE.md).

To add regional rules, use `app/src/main/assets/presets/regions/` and cite sources for
the deadlines. Translations are in `app/src/main/res/values*/`, and help is in
`app/src/main/assets/help/`. Do not include personal data, keys, or photos of other
people’s receipts in proposed changes.
