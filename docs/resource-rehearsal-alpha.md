# Ресурсная репетиция перед device alpha

Дата прогона: 19 июля 2026 года. Цель — до подключения телефона проверить ограниченность алгоритмов, production-схему хранения и совместимость Android API 30–37. Приведённые задержки относятся к Mac и эмулятору и не заменяют приёмку на Galaxy S23+.

## Синтетический профиль

Production-schema тест создаёт 13 000 записей `catalog_asset`, по одному OCR document и region на фотографию, lexical/trigram FTS rows и publication clock. Корпус содержит смешанные JPEG/PNG, альбомы и даты, поэтому запрос проходит через те же album/date/MIME predicates до `ORDER BY … LIMIT`, что и приложение.

Тест проверяет:

- bounded top-100 snapshot с production join и latest-publication rules;
- четыре параллельных WAL reader и один writer;
- немедленную пустую выдачу после смены access revision на `None`;
- сохранность состояния и невидимость отозванных данных после закрытия и повторного открытия базы;
- верхнюю границу размера database и диагностический PSS без превращения emulator timing в product SLA.

На API 30 corpus insert занял 498 мс, тёплый filtered query — 17 мс, основной database file — 15 486 976 байт, PSS тестового процесса — 53 195 КБ. Gates намеренно широкие: database не более 128 МиБ и тёплый запрос не более 5 секунд, чтобы ловить алгоритмическую регрессию, а не различия host-машин.

## Векторный и duplicate search

Host-native suite теперь всегда исполняет exact top-K rehearsal:

| Канал | Записей | Размерность | Сегментов | top-K | Медиана на текущем Mac |
|---|---:|---:|---:|---:|---:|
| visual | 13 000 | 768 | 51 | 50 | 754 мкс |
| OCR semantic | 50 000 | 384 | 196 | 50 | 1 169 мкс |

Benchmark проверяет полный проход по immutable QINT8 segments и bounded retention ровно 50 результатов. pHash ranker отдельно проходит детерминированный exact scan по 13 000 текущим записям с лимитом 50. Эти результаты подтверждают выбранную flat-scan архитектуру на целевом масштабе, но не являются Android UI latency.

## Эмуляторная матрица

Полный instrumented portfolio прошёл на каждой конфигурации:

| AVD | API | Страница процесса | Результат |
|---|---:|---:|---|
| `nayti-api30` | 30 | 4 КиБ | 16 app, 4 ML runtime, 3 search-engine, 42 storage |
| `nayti-api33` | 33 | 4 КиБ | 14 app, 4 ML runtime, 3 search-engine, 42 storage |
| `nayti-api34` | 34 | 4 КиБ | 14 app, 4 ML runtime, 3 search-engine, 42 storage |
| `nayti-api35` | 35 | 4 КиБ | 14 app, 4 ML runtime, 3 search-engine, 42 storage |
| `nayti-api36` | 36 | 4 КиБ | 14 app, 4 ML runtime, 3 search-engine, 42 storage |
| `nayti-api37-16k` | 37 | 16 КиБ | 14 app, 4 ML runtime, 3 search-engine, 42 storage |

API 30 дополнительно исполняет два pixel-golden теста, поэтому app count выше. На 16 КиБ AVD нативные ML preprocessing и vector-search suites действительно загрузили ARM64 shared libraries внутри 16K-процесса; это дополняет статические ELF и APK ZIP-alignment gates.

Матрица воспроизводится последовательным runner без Android Studio:

```bash
runtime="$(./scripts/fetch_reduced_ort.sh)"
NAYTI_ORT_AAR="$runtime" ./scripts/run_emulator_matrix.sh
```

Runner отказывается стартовать при уже подключённом устройстве, поднимает ровно один AVD, ограничивает Gradle двумя workers, сохраняет логи в ignored `build/reports/emulator-matrix/` и гасит эмулятор даже при ошибке.

## Что остаётся доказать на Galaxy S23+

- реальный peak PSS при последовательной загрузке OCR, USER2 и SigLIP2;
- p95 end-to-end latency с декодированием MediaStore изображений и Compose publication;
- throughput полной индексации около 13 000 личных фотографий;
- thermal duty cycle, battery drain, Android/Samsung foreground limits и reboot recovery;
- качество поиска на приватном evaluation corpus.

До этих измерений README и release notes не должны обещать численные показатели устройства.
