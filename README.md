# Nayti

Nayti — Android-приложение для полностью локального поиска по фотогалерее. Оно объединяет распознанный текст, смысл документа, визуальное описание и поиск похожих изображений, не отправляя фотографии и поисковые запросы в сеть.

Проект разрабатывается с нуля как самостоятельная greenfield-кодовая база. Основной пользовательский путь реализован и проверен на Samsung Galaxy S23+. Кандидат `0.1.0-alpha.1` собран как подписанные APK и model pack для ручной установки из GitHub Releases.

## Цели первой alpha

- Android 11 и новее, приоритетная оптимизация под Samsung Galaxy S23+;
- поиск по точному, нечёткому и смысловому OCR-тексту;
- текстовый поиск по визуальному содержанию фотографии;
- поиск похожих изображений и near-duplicates;
- устойчивое возобновление индексации после остановки процесса;
- выбираемый период первичной индексации с переиспользованием готовых результатов при расширении;
- отсутствие `INTERNET`, телеметрии и облачной обработки;
- отдельный проверяемый model pack без публикации весов в Git.

## Техническая основа

- Kotlin, Coroutines/Flow и Jetpack Compose;
- Room с FTS5 для каталога и текстового поиска;
- неизменяемые версионированные векторные сегменты;
- ONNX Runtime Mobile на CPU;
- гибридное ранжирование с приоритетом буквальных доказательств;
- foreground execution с учётом памяти, батареи и thermal state.

Постоянный application ID: `app.nayti`.

## Статус

Реализованы MediaStore catalog, Selected Photos Access, подписанные model packs, возобновляемая индексация, OCR/FTS/USER2/SigLIP2/pHash retrieval, гибридное ранжирование и продуктовый Compose UI. Security review, synthetic resource rehearsal, эмуляторная матрица API 30–37 и device-проверки на Galaxy S23+ завершены. Release candidate включает отдельные APK и model pack с checksums, SBOM и notices. Точный release-signed APK прошёл clean-install smoke на API 30; публикация alpha ожидает последнего clean-install smoke на физическом ARM64-устройстве.

Ни фотографии, ни поисковые запросы, ни OCR, ни имена файлов не входят в репозиторий, CI-артефакты или release bundle. Device-приёмка фиксирует только агрегированные показатели и системные состояния.

## Локальная сборка

Для CLI-сборки нужны JDK 17, Android SDK Platform 37.0, Build Tools 36.0.0, NDK `27.0.12077973` и CMake 3.22.1. Путь к SDK задаётся стандартной переменной `ANDROID_SDK_ROOT` или локальным `local.properties`; сами SDK, кэши и model packs в репозиторий не входят.

```bash
runtime="$(./scripts/fetch_reduced_ort.sh)"
NAYTI_ORT_AAR="$runtime" ./scripts/check.sh
```

Загрузчик принимает только зафиксированный ARM64 runtime с ожидаемыми размером и SHA-256. Основная команда проверяет границы модулей, JVM-тесты, Android Lint, debug/benchmark и minified unsigned release-сборки, оба merged manifest, 16 KiB ELF/ZIP alignment и нативный runtime-контракт на host-машине. APK после успешного прогона находятся в `app/build/outputs/apk/debug/` и `app/build/outputs/apk/release/`.

Результаты масштабного прогона и способ воспроизвести API-матрицу описаны в [docs/resource-rehearsal-alpha.md](docs/resource-rehearsal-alpha.md).

После зелёного check public-alpha bundle собирается отдельно; signing key и signed model pack остаются вне Git:

```bash
export NAYTI_RELEASE_KEYSTORE=/path/to/nayti-personal-alpha.p12
export NAYTI_RELEASE_KEY_ALIAS=nayti-personal-alpha
export NAYTI_RELEASE_STORE_PASSWORD='read-from-your-secret-store'
export NAYTI_RELEASE_KEY_PASSWORD="$NAYTI_RELEASE_STORE_PASSWORD"
NAYTI_MODEL_PACK=/path/to/nayti-offline-search-0.1.0-alpha.2.naytipack \
  ./scripts/assemble_alpha_bundle.sh
```

Bundle содержит подписанный ARM64 APK, pack, checksums, SBOM/notices, release notes и [инструкцию установки и приёмки](docs/device-alpha-runbook.md). Для сборки требуются все переменные `NAYTI_RELEASE_*`; неполная signing-конфигурация завершает Gradle с ошибкой, а debug certificate запрещён release-скриптом.

Правила хранения и резервного копирования ключа описаны в [docs/release-signing.md](docs/release-signing.md).

## Участие и безопасность

Правила участия описаны в [CONTRIBUTING.md](CONTRIBUTING.md). Об уязвимостях следует сообщать приватно по процедуре из [SECURITY.md](SECURITY.md).

## Лицензия

Исходный код распространяется по [Apache License 2.0](LICENSE). Модели, их веса и отдельные сторонние компоненты могут иметь собственные лицензии и не покрываются автоматически лицензией приложения.
