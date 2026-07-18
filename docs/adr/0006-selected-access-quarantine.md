# ADR-0006: карантин производных данных при Selected access

Статус: принято 19 июля 2026 года.

## Контекст

Android может сузить Selected Photos Access без удаления самой фотографии из MediaStore. Такой asset должен исчезнуть из поиска сразу, но мгновенная перезапись всех неизменяемых vector snapshots при каждом изменении системного выбора создаёт лишнюю I/O-нагрузку и конфликтует с активными query leases.

Одновременно производные данные скрытой фотографии нельзя хранить бессрочно. К ним относятся OCR и FTS publications, semantic chunks, perceptual hash, durable work metadata и векторные records в active, rollback или исторических snapshots.

## Решение

- `OUT_OF_SCOPE` немедленно исключается из eligibility всех retrievers через current catalog/access contract. Карантин не означает доступность результата поиска.
- Срок хранения начинается в `quarantineStartedAtMillis` и составляет 30 дней. Повторно доступный asset сохраняет прежний `assetId`, а marker завершённой очистки сбрасывается, чтобы обычная индексация могла восстановить производные данные.
- Короткий foreground-trigger и уникальная periodic WorkManager-задача запускают один и тот же GC под общим `IndexExecutionGate`. Один batch ограничен 256 assets; одна фоновая сессия — четырьмя batch и четырьмя минутами.
- Если quarantined records присутствуют в active vector manifest, создаётся новый независимый root без этих records. Если records остались только в rollback или историческом root, active snapshot всё равно отделяется от защищённой цепочки. Неизменившиеся segments переиспользуются по content address, затронутые segments переписываются.
- Privacy re-root очищает rollback pointer и переводит активные model-pack candidates в `ROLLED_BACK`: snapshot с удаляемыми данными не может оставаться корнем GC только ради отката модели.
- Snapshot с живым query lease и незавершённый activation candidate откладывают очистку. После освобождения root повторный проход продолжает ту же durable операцию.
- Файлы удаляются существующим двухфазным протоколом delete intents с проверкой SHA-256 и replay после process death. Только когда vector references исчезли, одна Room transaction удаляет OCR/FTS/chunks/pHash/work metadata и ставит `derivedDataPurgedAtMillis`.
- Действие «Удалить сейчас» остаётся явно подтверждаемым полным сбросом производного индекса. Оно не удаляет MediaStore catalog или установленный model pack.

## Последствия

Очистка переживает process death, не меняет доступный индекс частично и не считает временное сужение Selected access удалением фотографии. После privacy re-root откат на предыдущий model snapshot недоступен до следующей полноценной model activation; сохранение такого rollback противоречило бы обещанию физического удаления.

WorkManager может отложить выполнение из-за системных ограничений батареи или хранилища, поэтому 30 дней означают первый разрешённый maintenance window после истечения срока. Немедленная невидимость из поиска от этого не зависит.
