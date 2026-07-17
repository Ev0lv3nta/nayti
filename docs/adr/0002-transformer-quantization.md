# ADR 0002: измеряемое mixed INT8-квантование энкодеров

Статус: принято 17 июля 2026 года.

## Контекст

Model pack должен помещаться на обычном Android-устройстве и не терять качество retrieval. Исходные FP32 ONNX-графы трёх энкодеров занимают около 1,64 ГБ, причём главная доля приходится на multilingual token embedding SigLIP2. Одного критерия размера недостаточно: candidate обязан пройти cosine и top-10 parity относительно уже проверенного FP32 export.

ONNX Runtime рекомендует dynamic quantization для transformer-моделей, а static quantization — в первую очередь для CNN. Это соответствует локальному измерению: static S8S8/QDQ для SigLIP2 image дал median cosine ниже 0,90 и был отклонён до публикации.

## Решение

Использовать measured mixed profile:

- SigLIP2 image: dynamic per-channel QInt8 только для MLP projections; patch embedding, QKV, attention projections и head остаются FP32;
- SigLIP2 text: dynamic per-channel QInt8 для всех constant-weight MatMul/Gemm;
- USER2: dynamic per-channel QInt8 для всех constant-weight MatMul/Gemm;
- token embedding tables SigLIP2 и USER2: row-wise symmetric QInt8, `Gather` выполняется до восстановления выбранных строк в FP32;
- OCR и tokenizer graphs не квантовать.

Не публиковать candidate, если median cosine ниже `0,995`, p1 ниже `0,98` или средний top-10 overlap ниже `0,95`. Evaluation fixtures детерминированы, не содержат пользовательских данных и не пересекаются с source weights.

## Измеренный результат

| Graph | FP32 | Mixed INT8 | Median cosine | p1 cosine |
|---|---:|---:|---:|---:|
| SigLIP2 image | 372 937 617 B | 189 113 808 B | 0,99664 | 0,98537 |
| SigLIP2 text | 1 130 377 787 B | 287 050 493 B | 0,99806 | 0,99656 |
| USER2 | 139 588 832 B | 36 761 651 B | 0,99949 | 0,99927 |

Cross-modal SigLIP2 top-10 overlap равен `1,00`, USER2 retrieval overlap — `0,98`. Полный host-run занял меньше минуты, peak observed process RSS был существенно ниже safety limit, swap не использовался.

## Последствия

- три deploy-энкодера уменьшаются примерно с 1,64 ГБ до 489 МБ без нарушения quality gates;
- vision graph остаётся больше первоначальной оценки, потому что более агрессивное квантование воспроизводимо не прошло p1 gate;
- dynamic activation parameters добавляют небольшой per-inference overhead, который нужно измерить на Galaxy S23+;
- Android ORT execution и latency/PSS остаются отдельным обязательным gate: host parity не считается доказательством производительности ARM64.

ORT 1.27 Fixed serializer не оказался byte-reproducible: при одинаковых ONNX inputs меняется небольшая служебная FlatBuffers-область, хотя размеры, operator config и все outputs совпадают. Поэтому canonical identities — hashes исходных mixed-INT8 ONNX graphs; каждый конкретный ORT release artifact получает собственный SHA-256, ONNX↔ORT KAT и подпись model pack. Неподтверждённое побайтовое воспроизведение ORT не заявляется.

Источник выбора метода: [ONNX Runtime — Quantize ONNX models](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html).
