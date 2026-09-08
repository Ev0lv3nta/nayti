# Готовность следующей alpha

Обновлено 8 сентября 2026. Текущая линия — app `0.1.0-alpha.2`, versionCode 2, неизменённый model pack `0.1.0-alpha.2`. Это состояние разработки, не заявление об успешной приёмке на телефоне.

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
| Android runtime suites | Исторический API30 на `c90ba3e`: 139 PASS / 1 opt-in skip; opt-in E2E затем отдельно прошёл. Последующие изменения на Android ещё не выполнены |
| Matrix API 30/33/34/35/36/37, 16 KiB | Для конечного commit не выполнена. Новые проверки — только физические устройства; недоступные API/16 KiB не объявляются проверенными |
| Model-backed baseline/final и калибровка | [Baseline на c90ba3e](../evaluation/baseline-2026-09-08.md) выполнен; final и калибровка не выполнены, сложные сцены/действия покрыты ограниченно |
| Signed release clean install и upgrade на физическом ARM64 | Не выполнены, release blocker |
| Визуальная приёмка, TalkBack/шрифт, gestures, sharing, scrolling, RAM/FD/thermal | Требуют устройства; не обозначаются PASS по компиляции |

## Далее

1. Собрать конечный commit с прежней подписью и checksums/SBOM/notices; до device gates это кандидат.
2. Выполнить final [corpus run](../evaluation/README.md) на изолированном физическом ARM64-устройстве по заранее записанным критериям. Сохранённый baseline/holdout не менять; личную медиатеку не очищать и не использовать как публичный benchmark.
3. Пройти доступные телефонные UX/resource сценарии: resume, отмена, смена периода/доступа, ошибки моделей, поиск при подготовке, внешний viewer, zoom/rotation, крупный шрифт. Эмуляторы не использовать. Другие API/page-size configurations оставить непроверенными, если соответствующего физического устройства нет.
4. Проверить **точный подписанный APK**: чистая установка без удаления личной установки и upgrade с прежним ключом/пакетом/индексом. Не удалять личные данные ради теста.
5. Снять реальные screenshots на собственных synthetic documents, добавить проверенные агрегаты; сверить release notes и remote digests. После gates публиковать GitHub prerelease. Google Play, аккаунты и монетизация сейчас не нужны.

Персональные данные, сырые логи, keys, модели и тяжёлый корпус остаются вне Git. Отсутствие телефона не превращается в «100% готово».
