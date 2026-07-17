# ADR 0002: измеряемое mixed INT8-квантование энкодеров

Статус: принято 17 июля 2026 года.

## Контекст

Model pack должен помещаться на обычном Android-устройстве и не терять качество retrieval. Исходные FP32 ONNX-графы трёх энкодеров занимают около 1,64 ГБ, причём главная доля приходится на multilingual token embedding SigLIP2. Одного критерия размера недостаточно: candidate обязан пройти cosine и top-10 parity относительно уже проверенного FP32 export.

ONNX Runtime рекомендует dynamic quantization для transformer-моделей, а static quantization — в первую очередь для CNN. Это соответствует локальному измерению: static S8S8/QDQ для SigLIP2 image дал median cosine ниже 0,90 и был отклонён до публикации.

## Решение

Использовать профиль, прошедший host- и Android ARM64-gates:

- SigLIP2 image: FP32 целиком;
- SigLIP2 text: transformer остаётся FP32, только token embedding table переводится в row-wise symmetric QInt8; `Gather` выполняется до восстановления выбранных строк в FP32;
- USER2: token embedding table переводится в row-wise symmetric QInt8, constant-weight MatMul/Gemm — в dynamic per-channel QInt8;
- OCR и tokenizer graphs не квантовать.

Не публиковать candidate, если median cosine ниже `0,995`, p1 ниже `0,98` или средний top-10 overlap ниже `0,95`. Evaluation fixtures детерминированы, не содержат пользовательских данных и не пересекаются с source weights.

## Измеренный результат

| Graph | FP32 ONNX | Deploy ONNX | Median cosine | p1 cosine |
|---|---:|---:|---:|---:|
| SigLIP2 image | 372 937 617 B | 372 937 617 B | 1,00000 | 1,00000 |
| SigLIP2 text | 1 130 377 787 B | 541 578 396 B | 0,99932 | 0,99788 |
| USER2 | 139 588 832 B | 36 761 651 B | 0,99949 | 0,99927 |

Cross-modal SigLIP2 top-10 overlap равен `1,00`, USER2 retrieval overlap — `0,98`. Полный host-run занял меньше минуты, peak observed process RSS был существенно ниже safety limit, swap не использовался.

Предварительный более агрессивный профиль прошёл host gate, но был отклонён на настоящем Android ARM64 execution gate: SigLIP2 image дал cosine `0,96289`, SigLIP2 text — `0,96800`. USER2 дал `0,99752` и остался квантованным. Финальный профиль повторно прошёл все семь графов на API 30/PAGESIZE 4096 и API 37/PAGESIZE 16384. На обеих конфигурациях SigLIP2 image/text дали cosine практически `1,0`, USER2 — `0,99752`; максимальная абсолютная ошибка OCR не превысила `3,13e-6`.

## Последствия

- три deploy-энкодера уменьшаются примерно с 1,64 ГБ до 951 МБ без нарушения host- и ARM64-gates;
- SigLIP2 image и transformer-часть text graph остаются FP32, потому что агрессивные candidates прошли host corpus, но не воспроизвели качество на ARM64;
- dynamic activation parameters добавляют небольшой per-inference overhead, который нужно измерить на Galaxy S23+;
- latency/PSS остаются отдельным обязательным gate на Galaxy S23+: эмулятор доказывает корректность, но не производительность устройства.

ORT 1.27 Fixed serializer не оказался byte-reproducible: при одинаковых ONNX inputs меняется небольшая служебная FlatBuffers-область, хотя размеры, operator config и все outputs совпадают. Поэтому canonical identities — hashes исходных mixed-INT8 ONNX graphs; каждый конкретный ORT release artifact получает собственный SHA-256, ONNX↔ORT KAT и подпись model pack. Неподтверждённое побайтовое воспроизведение ORT не заявляется.

Источник выбора метода: [ONNX Runtime — Quantize ONNX models](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html).
