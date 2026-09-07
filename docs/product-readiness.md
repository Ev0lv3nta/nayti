# Готовность следующей alpha

Обновлено 8 сентября 2026. Текущая линия — app `0.1.0-alpha.2`, versionCode 2, неизменённый model pack `0.1.0-alpha.2`. Это состояние разработки, не заявление об успешной приёмке на телефоне.

## Реализованные изменения

- Worker boundary поиска, cancellation-safe sessions/decode, защита от устаревших результатов.
- Раздельные draft/submitted запросы, ручной запуск, pending changes и сохранение фильтров.
- Typed причины невозможного старта индексации; остановка FGS на timeout без ожидания базы/ORT.
- Original share/open, bounded zoom/pan и double tap.
- Отмена импорта без потери старого пакета; стадии и понятные причины ошибки; KAT использует общую очередь нейровычислений.
- Hash-specific совместимость пакета с app code 2, одинаковая policy на import и restore. Embedding identities не меняются из-за номера APK.
- Все AndroidTest в fast gate, platform-media в emulator matrix, отдельный реальный startup benchmark.
- Безопасный corpus/evaluator: 180 изображений, 118 запросов, 38 holdout; production Android path при фактическом запуске.

## Проверки

| Уровень | Статус |
| --- | --- |
| Host unit/lint/build/native, статические safety gates | Выполняются в PR; источником истины являются checks данного commit |
| Signed pack: import app 1 → тот же immutable pack на app 2, restore/отрицательная compatibility | Проверен JVM importer с настоящим контейнером; без ARM64 KAT на Mac |
| AndroidTest compilation | Включена в gate; не заменяет выполнение |
| Новая matrix API 30/33/34/35/36/37, 16 KiB | Не выполнена для конечного polish commit; прежние результаты сохранены отдельно |
| Model-backed baseline/final и калибровка | Не выполнены; labels пока не покрывают все сложные сцены/действия |
| Signed release clean install и upgrade на физическом ARM64 | Не выполнены, release blocker |
| Визуальная приёмка, TalkBack/шрифт, gestures, sharing, scrolling, RAM/FD/thermal | Требуют устройства; не обозначаются PASS по компиляции |

## Далее

1. Собрать конечный commit с прежней подписью и checksums/SBOM/notices; до device gates это кандидат.
2. Выполнить [corpus run](../evaluation/README.md) в изолированной ARM64-среде. Сохранить baseline, разобрать development, заранее зафиксировать критерии, затем final на неизменённом holdout. Проверить новые scene/action/negative/filters cases; расширять development по выявленным пробелам, не подгонять holdout.
3. Пройти Android matrix и телефонные UX/resource сценарии: resume, отмена, смена периода/доступа, ошибки моделей, поиск при подготовке, внешний viewer, zoom/rotation, крупный шрифт.
4. Проверить **точный подписанный APK**: чистая установка без удаления личной установки и upgrade с прежним ключом/пакетом/индексом. Не удалять личные данные ради теста.
5. Снять реальные screenshots на собственных synthetic documents, добавить проверенные агрегаты; сверить release notes и remote digests. После gates публиковать GitHub prerelease. Google Play, аккаунты и монетизация сейчас не нужны.

Персональные данные, сырые логи, keys, модели и тяжёлый корпус остаются вне Git. Отсутствие телефона не превращается в «100% готово».
