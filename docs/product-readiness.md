# Готовность personal alpha

Обновлено 8 сентября 2026. Текущая линия — app `0.1.0-alpha.2`, versionCode 2, неизменённый model pack `0.1.0-alpha.2`. [Фактический протокол телефона](../evaluation/physical-2026-09-08.md) отделяет проверки от непроверенных конфигураций.

## Реализованные изменения

- Worker boundary поиска, cancellation-safe sessions/decode, защита от устаревших результатов.
- Раздельные draft/submitted запросы, ручной запуск, pending changes и сохранение фильтров.
- Единый контракт All/automatic для exact syntax; явные подмножества не меняются, выбранные и фактически использованные каналы разделены в UI.
- Typed причины невозможного старта индексации; остановка FGS на timeout без ожидания базы/ORT.
- Original share/open, bounded zoom/pan и double tap.
- Отмена импорта без потери старого пакета; стадии и понятные причины ошибки; KAT использует общую очередь нейровычислений.
- При известном размере пакета свободное место проверяется до чтения и показывается оценка в ГиБ; для неизвестного размера остаются потоковые лимиты.
- Hash-specific совместимость пакета с app code 2, одинаковая policy на import и restore. Embedding identities не меняются из-за номера APK.
- Все AndroidTest в fast gate, platform-media в emulator matrix, отдельный реальный startup benchmark.
- Безопасный corpus/evaluator: 180 изображений, 118 запросов, 38 holdout; production Android path при фактическом запуске.

## Проверки

| Уровень | Статус |
| --- | --- |
| Host unit/lint/build/native, статические safety gates | Выполняются в PR; источником истины являются checks данного commit |
| Signed pack: import app 1 → тот же immutable pack на app 2, restore/отрицательная compatibility | Проверен JVM importer с настоящим контейнером; без ARM64 KAT на Mac |
| AndroidTest compilation | Включена в gate; не заменяет выполнение |
| Android runtime suites | Galaxy S23+: 139 PASS / 1 opt-in skip; затем отдельный corpus PASS. Повторный storage regression и проверка manifest IME policy PASS |
| Matrix API 30/33/34/35/36/37, 16 KiB | Для конечного commit не выполнена. Новые проверки — только физические устройства; недоступные API/16 KiB не объявляются проверенными |
| Model-backed baseline/final и калибровка | Физический final PASS, положительные Hit@1/5 не ухудшились, negative identifiers исправлены. Visual negatives/pHash misses остаются; пороги не подгонялись |
| Signed release clean install и повторная установка на физическом ARM64 | Alpha.2 установлена, pack импортирован и сохранён при install-r; синтетический документ подготовлен и найден. Это не матрица миграций будущих версий |
| Визуальная приёмка, TalkBack/шрифт, gestures, sharing, scrolling, RAM/FD/thermal | UI suites и реальная клавиатура проверены; наблюдались ограниченные PSS и thermal0–1. Полная TalkBack/FD/macrobenchmark матрица не заявляется |

## Порядок публикации

1. Собрать конечный commit с прежней подписью и checksums/SBOM/notices; до device gates это кандидат.
2. Для изменений движка повторить [corpus run](../evaluation/README.md) по заранее записанным критериям. Сохранённый baseline/holdout не менять; личную медиатеку не очищать и не использовать как публичный benchmark.
3. Пройти доступные телефонные UX/resource сценарии: resume, отмена, смена периода/доступа, ошибки моделей, поиск при подготовке, внешний viewer, zoom/rotation, крупный шрифт. Эмуляторы не использовать. Другие API/page-size configurations оставить непроверенными, если соответствующего физического устройства нет.
4. Проверить **точный подписанный APK** с прежним ключом/пакетом/индексом. Переход с developer signer, требующий удаления внутренних данных Nayti, допустим только с явным разрешением владельца. Фотографии не удаляются.
5. Добавить проверенные агрегаты; сверить release notes и remote digests. После gates публиковать GitHub prerelease. Скриншоты личного телефона не публикуются. Google Play, аккаунты и монетизация сейчас не нужны.

Персональные данные, сырые логи, keys, модели и тяжёлый корпус остаются вне Git. Отсутствие телефона не превращается в «100% готово».
