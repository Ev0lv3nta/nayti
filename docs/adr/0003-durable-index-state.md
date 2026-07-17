# ADR 0003: долговечное состояние индексации

Статус: принято 17 июля 2026 года.

## Контекст

Индексация библиотеки может длиться несколько дней и многократно переживать process death, reboot, отзыв доступа и ограничения Android. Coroutine, Service и Worker не могут быть источником истины о прогрессе. Отдельная таблица очереди также дублировала бы channel state и неизбежно расходилась с ним.

## Решение

Room хранит одну запись `index_channel_work` для каждой пары `(assetId, channel)`. Работа проходит состояния `PENDING → RUNNING → STAGED → DONE` с ветвями `RETRYABLE_ERROR` и `PERMANENT_ERROR`. Запись связывает результат с source fingerprint, access revision, pipeline version, component hash, attempt, lease и publication token.

`IndexOperation` хранит долговечное намерение и denominator snapshot. `ExecutionWindow` представляет один ограниченный запуск приложения, FGS либо Worker. Claim разрешён только живому window и атомарно выдаёт небольшой batch из самих channel rows; отдельного списка задач нет. Lease ограничен сроком window, а поздний commit отклоняется.

Operation отдельно фиксирует exact набор `assetId` и упорядоченные channel contracts, поэтому restart не пересчитывает denominator по уже изменившейся библиотеке. Coordinator требует executor для каждого declared channel до создания operation; скрытых fallback-процессоров нет. Один execution loop обрабатывает claims последовательно, а OCR-semantic становится eligible только после `DONE` OCR того же asset и fingerprint.

Перед SQL-publication одна Room transaction повторно сверяет:

- живой lease и его token;
- текущий source fingerprint и доступность MediaStore asset;
- текущую process access revision;
- pipeline version и component hash.

В той же transaction записывается publication evidence, увеличивается монотонный epoch и channel становится `DONE`. SQL-only output не оставляет долговечного `STAGED`: этот переход находится внутри одной transaction. Для vector output `STAGED` будет долговечным только после sealed filesystem artifact и завершится `DONE` вместе с manifest/snapshot visibility.

## Последствия

- process death возвращает только истёкшие leases в `PENDING`, не угадывая состояние из памяти;
- остановка execution window инвалидирует outstanding work, поэтому поздний native callback не публикует результат;
- новый fingerprint, access revision, pipeline или component contract создаёт новое `PENDING` evidence, а прежняя публикация остаётся недействительной для query;
- три исчерпанных transient attempts становятся видимым `PERMANENT_ERROR`, а не бесконечным retry;
- publication token имеет глобальную уникальность и не может заменить evidence другого asset;
- active данные не изменяются этим state machine: vector visibility и `ActivationSnapshot` получают отдельный атомарный publication contract.
