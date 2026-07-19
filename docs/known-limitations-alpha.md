# Известные ограничения personal alpha

- Alpha предназначена для локальной проверки на ARM64 Android 11+; это не публичный релиз и не обещание совместимости с произвольным устройством.
- Installable APK подписан Android debug certificate. Он non-debuggable и minified, но этот certificate нельзя использовать для Google Play или публичного распространения.
- Численные latency, PSS, throughput, battery и thermal показатели пока подтверждены только host/emulator regression. Galaxy S23+ acceptance остаётся обязательной.
- Signed model pack `0.1.0-alpha.2` занимает около 967 МиБ; импорт требует временного staging, а полный индекс личной галереи дополнительно расходует app-private storage.
- Индексация выполняется на CPU и намеренно приостанавливается при memory, thermal, battery, storage и Android execution constraints. Первая обработка большой библиотеки может занять несколько charging sessions.
- Для первичной проверки можно выбрать нижнюю границу периода вместо всей медиатеки. Предварительный прогноз полного прогона линейно масштабирует активное время завершённой выборки; различия старых фотографий и thermal throttling делают его ориентиром, а не гарантией.
- Поддерживаются фотографии из MediaStore. Видео, лица, геопоиск, облачная синхронизация, аккаунты, collections/tags и редактирование оригиналов не входят в alpha.
- Качество OCR и semantic/visual retrieval зависит от содержимого и языков галереи; скрытых cloud/VLM fallback нет.
- Selected Photos Access скрывает отозванные данные сразу, а физически удаляет производный индекс после 30-дневного quarantine. Немедленное удаление доступно через полный Reset index.
- Приложение не содержит `INTERNET`, telemetry или automatic crash upload. Диагностика экспортируется пользователем вручную и остаётся агрегированной.
- Model-pack update/rollback реализованы, но alpha включает один распространяемый pack. Production key rotation и Play Asset Delivery не настроены.
- Database migrations fail closed; destructive fallback запрещён. Перед переходом между экспериментальными сборками следует сохранять точный APK/pack identity и соблюдать runbook.
