# Уведомления о компонентах model pack

Nayti offline-search model pack `0.1.0-alpha.1` содержит производные deployment-графы четырёх зафиксированных upstream-моделей. Исходные веса в pack не включены; точные revisions и hashes зафиксированы в `provenance/sources.json`.

| Компонент | Правообладатель / проект | Revision | Лицензия |
|---|---|---|---|
| SigLIP2 Base Patch16 256 | Google | `3f9f96cb90da5dbc758b01813f2f6f1aee24c1ab` | Apache-2.0 |
| USER2-small | DeepPavlov / deepvk | `23f65b34cf7632032061f5cc66c14714e6d4cee4` | Apache-2.0 |
| PP-OCRv6 small detector ONNX | PaddlePaddle | `28fe5895c24fd108c19eb3e8479f4ab385fbfc62` | Apache-2.0 |
| East Slavic PP-OCRv5 mobile recognizer ONNX | PaddlePaddle | `9a32171fc5718746875e1a261818884517975013` | Apache-2.0 |

Полный текст Apache License 2.0 находится в `licenses/apache-2.0.txt`. Model pack также содержит ORT-format графы, созданные инструментами ONNX Runtime 1.27.0 и ONNX Runtime Extensions из зафиксированных revisions; сам runtime распространяется с приложением отдельно и описан в `provenance/runtime-sources.json`.

Названия проектов и организаций используются только для атрибуции и не означают одобрение Nayti соответствующими правообладателями.
