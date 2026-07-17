# ADR 0005: атомарное OCR- и lexical-хранилище

Статус: принято 17 июля 2026 года.

## Контекст

OCR одной фотографии создаёт несколько связанных представлений: исходный текст, строки с геометрией, нормализованные формы, FTS-документ и durable channel evidence. Если обновлять их независимо, process death может показать новый текст со старыми координатами, вернуть FTS-кандидат без provenance либо отметить work как `DONE` до публикации результата.

## Решение

Одна Room transaction повторно проверяет живой OCR lease, MediaStore fingerprint, доступ, access revision, pipeline и component hash. Затем она целиком заменяет OCR document, ordered regions, content-bearing `unicode61` FTS5 row и отдельный trigram FTS5 row, записывает publication evidence, увеличивает общий publication epoch и переводит work в `DONE`.

Payload имеет каноническую bounded-кодировку `NAYTIOCR1`. SHA-256 связывает raw/display/canonical/stem/identifier формы, версии правил, размеры исходника, порядок regions, confidence и нормализованные quadrilaterals. Пустой распознанный текст является валидным опубликованным результатом; битый input оформляется channel error, а не пустым документом.

FTS хранит собственный content, а не является external-content cache. Это даёт транзакционное восстановление штатными свойствами SQLite и не требует ручных sync triggers. Trigram index используется только для bounded candidate generation; итоговая fuzzy distance и пороги считаются вне FTS.

Query всегда ограничен точным OCR contract, captured publication epoch и текущим access/fingerprint evidence. Поэтому старая строка физически может оставаться до следующей успешной публикации, но становится недоступной поиску сразу после изменения источника или доступа.

Production executor читает только текущий `MediaStore` URI, декодирует software sRGB bitmap с длинной стороной не больше 1280 px и выполняет detector/recognizer в одной bounded neural lane. За один asset распознаётся не больше 512 regions, recognizer работает batch-ами до четырёх строк, а bitmap освобождается до Room publication. В индекс попадают непустые строки с итоговой confidence не ниже 0,25; координаты переводятся в fixed-point quadrilaterals относительно decoded image и потому не зависят от исходного разрешения.

Ошибки различаются по возможности восстановления. Повреждённое изображение становится permanent item gap, временная media I/O или ORT failure получает bounded retry, а потеря URI, смена fingerprint/access revision и истёкший lease отклоняют publication. `OutOfMemoryError` и ошибки инвариантов не маскируются как пользовательские данные.

## Последствия

- неудачная публикация полностью откатывается и сохраняет прежний документ;
- publication token одноразовый и не может повторно привязать другой OCR payload;
- query получает не больше 256 кандидатов на один retrieval pass и не материализует всю библиотеку;
- raw text не перезаписывается нормализованной формой, а версии normalizer/stemmer/identifier rules входят в provenance;
- detector confidence и recognizer confidence объединяются консервативно через минимум, поэтому слабая геометрия не получает высокий итоговый вес только из-за уверенного CTC;
- стабильность нескольких UI-страниц обеспечивается bounded query session поверх captured epoch, а не долгоживущей SQLite transaction.
