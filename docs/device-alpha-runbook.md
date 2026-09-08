# Установка и приёмка personal alpha

Этот runbook предназначен для ручной установки и проверки Nayti на ARM64 Android 11+, прежде всего Samsung Galaxy S23+. Канал публикации — GitHub Releases, не Google Play. Фактические проверки alpha.2 описаны в `evaluation/physical-2026-09-08.md` репозитория; точный APK и сертификат закреплены в BUILD-INFO и SHA256SUMS выпуска.

Release-процесс не собирает пользовательское содержимое. Не прикладывать к issue, pull request или release фотографии, скриншоты личной медиатеки, запросы, OCR, имена файлов, URI, MediaStore IDs, embeddings или raw diagnostics. Для отчёта достаточно агрегированных процентов, counts, PSS, thermal state и результата сценария.

## Перед началом

- зарядить телефон минимум до 70% и на время первой индексации подключить питание;
- оставить не менее 10 ГиБ свободного места: signed model pack занимает около 967 МиБ, импорт временно требует примерно три его размера, затем нужны database и vector artifacts;
- включить Developer options и USB debugging, подтвердить RSA fingerprint этого Mac;
- для воспроизводимой приёмки использовать изолированный безопасный корпус; личная галерея не является публичным benchmark;
- не копировать фотографии, OCR, запросы или raw diagnostics из телефона в GitHub artifacts.

Проверить, что ADB видит ровно одно ожидаемое устройство:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getconf PAGE_SIZE
adb shell df -h /data
```

## Проверка bundle и установка

Из каталога bundle сначала проверить все identities:

```bash
shasum -a 256 -c SHA256SUMS
```

`nayti-0.1.0-alpha.2-arm64.apk` — minified, non-debuggable ARM64 build, подписанный прежним alpha release certificate. Unsigned control APK используется только внутри release gate и публично не устанавливается. Укажите ожидаемый serial через `ANDROID_SERIAL`, если ADB содержит несколько целей; не выбирайте устройство автоматически.

Для чистого первого прогона:

```bash
adb install nayti-0.1.0-alpha.2-arm64.apk
```

Для обновления с прежней signed alpha.1 используйте `adb install -r nayti-0.1.0-alpha.2-arm64.apk`: code 2 и прежний сертификат сохраняют app-private данные. Старый pack alpha.2 принимается по точному manifest hash как при restore, так и при новом импорте; APK upgrade не меняет embedding identities. Зафиксируйте поканальную готовность до/после и убедитесь, что подготовка не началась с нуля. Этот сценарий пока требует физической проверки.

Если на устройстве уже установлена developer-сборка с Android Debug certificate под тем же `app.nayti`, поверх неё signed APK не установится. Удаление сотрёт private database, model pack и индекс: **не удалять личную установку ради теста**. Нужен другой физический ARM64-телефон либо отдельное согласованное решение о переходе. Обычный secondary/work profile не является обходом конфликта подписей: данные пользователей раздельны, но управление установленным пакетом влияет на других пользователей ([AOSP](https://source.android.com/docs/devices/admin/multi-user)). Современные debug/benchmark имеют другие IDs, но проверка такого APK не заменяет проверку точного release artifact.

Model pack можно скопировать в Downloads и выбрать в системном SAF picker:

```bash
adb push nayti-offline-search-0.1.0-alpha.2.naytipack /sdcard/Download/
```

После импорта исходный файл в Downloads можно удалить: приложение хранит проверенный immutable candidate в private storage. Не удалять app data между импортом и тестом.

## Функциональная последовательность

1. Пройти clean-install setup, импортировать pack и выдать доступ к фототеке. Android Selected Photos Access проверяется отдельно от периода локальной индексации.
2. Для первого измеряемого прогона выбрать «3 мес.» или собственную начальную дату. Записать показанные числа выбранных и всех доступных фотографий; дата фиксируется и не сдвигается во время многодневного прогона.
3. Дождаться завершения всех четырёх каналов. Записать активное время и предварительный прогноз всей медиатеки; время пауз и ожидания ограничений в эту оценку не входит.
4. Убедиться, что readiness показывает независимый прогресс OCR, OCR semantic, visual и duplicates, а поиск не возвращает фотографии за пределами выбранного периода.
5. Проверить exact/phrase/identifier, fuzzy, OCR-semantic, visual, Auto, Similar и Duplicates на заранее известных примерах.
6. Проверить date, album и MIME filters; исключённые фильтром совпадения не должны вытеснять подходящие из top-K.
7. Расширить период до года или всей медиатеки. Уже подготовленные фотографии должны остаться готовыми, а новая operation — планировать только недостающую работу.
8. Расширить selected access, затем отозвать часть доступа. Thumbnail, Viewer, Similar и Search должны очиститься немедленно; возврат доступа должен переиспользовать стабильный catalog identity и переиндексировать purged данные.
9. Проверить Pause/Resume, swipe-away, process kill и reboot. Durable operation продолжается без повторной публикации готовых items.
10. Экспортировать redacted diagnostics и вручную убедиться, что там нет queries, OCR, имён файлов, URI, MediaStore IDs, изображений или embeddings. В отчёте должны быть scope revision, граница периода, охват и активное время.
11. Проверить подтверждаемый Reset index: model pack и catalog сохраняются, производный индекс исчезает и строится заново.

## Ресурсы и stop conditions

Базовые команды не извлекают пользовательский контент:

```bash
adb shell dumpsys meminfo app.nayti
adb shell dumpsys thermalservice
adb shell dumpsys battery
adb shell dumpsys activity services app.nayti
```

Остановить индексацию и дать устройству остыть, если Android сообщает severe/critical thermal status, интерфейс перестаёт отвечать, PSS устойчиво превышает 1,2 ГиБ, свободное место падает ниже безопасного staging budget или система начинает убивать процесс. Эти события фиксируются как device evidence; лимиты runtime не обходятся принудительно.

## Обновление personal alpha

Повторный `adb install -r` работает только для APK, подписанных тем же alpha release certificate. Keystore и пароль не входят в репозиторий или release bundle; потеря ключа сделает обновление существующей установки невозможным.
