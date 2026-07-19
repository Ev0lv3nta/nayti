# Nayti

Nayti — Android-приложение для полностью локального поиска по фотогалерее. Оно объединяет распознанный текст, смысл документа, визуальное описание и поиск похожих изображений, не отправляя фотографии и поисковые запросы в сеть.

Проект разрабатывается с нуля как самостоятельная greenfield-кодовая база. Основной пользовательский путь уже реализован; сейчас идёт pre-device hardening перед первой проверкой на Galaxy S23+. Публичной alpha-сборки пока нет, а показатели производительности не считаются подтверждёнными до измерений на реальном устройстве.

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

Реализованы MediaStore catalog, Selected Photos Access, подписанные model packs, возобновляемая индексация, OCR/FTS/USER2/SigLIP2/pHash retrieval, гибридное ранжирование и продуктовый Compose UI. Security review, synthetic resource rehearsal и полная эмуляторная матрица API 30–37 завершены; до personal alpha остаются финальный release-like комплект и приёмка на Galaxy S23+.

## Локальная сборка

Для CLI-сборки нужны JDK 17, Android SDK Platform 37.0, Build Tools 36.0.0, NDK `27.0.12077973` и CMake 3.22.1. Путь к SDK задаётся стандартной переменной `ANDROID_SDK_ROOT` или локальным `local.properties`; сами SDK, кэши и model packs в репозиторий не входят.

```bash
runtime="$(./scripts/fetch_reduced_ort.sh)"
NAYTI_ORT_AAR="$runtime" ./scripts/check.sh
```

Загрузчик принимает только зафиксированный ARM64 runtime с ожидаемыми размером и SHA-256. Основная команда проверяет границы модулей, JVM-тесты, Android Lint, debug/benchmark и minified unsigned release-сборки, оба merged manifest, 16 KiB ELF/ZIP alignment и нативный runtime-контракт на host-машине. APK после успешного прогона находятся в `app/build/outputs/apk/debug/` и `app/build/outputs/apk/release/`.

Результаты масштабного прогона и способ воспроизвести API-матрицу описаны в [docs/resource-rehearsal-alpha.md](docs/resource-rehearsal-alpha.md).

После зелёного check локальный device-alpha bundle собирается отдельно; signed model pack остаётся вне Git:

```bash
NAYTI_MODEL_PACK=/path/to/nayti-offline-search-0.1.0-alpha.2.naytipack \
  ./scripts/assemble_alpha_bundle.sh
```

Bundle содержит installable local-signed APK, контрольный unsigned APK, pack, checksums, SBOM/notices и [инструкцию приёмки](docs/device-alpha-runbook.md).

## Участие и безопасность

Правила участия описаны в [CONTRIBUTING.md](CONTRIBUTING.md). Об уязвимостях следует сообщать приватно по процедуре из [SECURITY.md](SECURITY.md).

## Происхождение

Nayti не является fork, template или импортом другого приложения. Политика независимой реализации и учёта сторонних материалов описана в [docs/PROVENANCE.md](docs/PROVENANCE.md).

## Лицензия

Исходный код распространяется по [Apache License 2.0](LICENSE). Модели, их веса и отдельные сторонние компоненты могут иметь собственные лицензии и не покрываются автоматически лицензией приложения.
