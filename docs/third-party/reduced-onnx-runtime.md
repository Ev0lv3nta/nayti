# Reduced ONNX Runtime для Android

Nayti использует собственную минимальную ARM64-сборку ONNX Runtime вместо полного Maven AAR. В runtime оставлены только операторы и типы, необходимые семи зафиксированным графам model pack; fallback на полный ONNX Runtime отсутствует.

## Зафиксированный артефакт

- ONNX Runtime: `v1.27.0`, revision `8f0278c77bf44b0cc83c098c6c722b92a36ac4b5`;
- ONNX Runtime Extensions: revision `fe4e13f46b19fb490c90b09fe280277308bd5bb7`;
- Android ABI/API: `arm64-v8a`, min API 30, NDK `27.0.12077973`;
- профиль: minimal custom-ops build с reduced operator/type config;
- AAR: `onnxruntime-reduced-1.27.0-arm64-v8a.aar`, 2 091 115 bytes;
- SHA-256: `9822f3b34d64d25e62e7b0bfc59beab3313451b08d4d4dba7013ac9d5ee9531f`.

Артефакт содержит только `libonnxruntime.so` и `libonnxruntime4j_jni.so` для ARM64. ELF LOAD alignment равен `0x4000`; итоговый APK дополнительно проверяется на 16 KiB ZIP alignment. Процедура сборки зафиксирована скриптом `model-tools/scripts/build_reduced_ort_android.sh`, а точные revision, размер и hash повторно проверяются перед упаковкой приложения.

## Распространение

Проверенный AAR публикуется отдельным prerelease-артефактом GitHub, а не коммитится в source history. `scripts/fetch_reduced_ort.sh` загружает только зафиксированное имя release asset и принимает файл лишь при совпадении размера и SHA-256. CI и локальная release-сборка используют один и тот же контракт.

ONNX Runtime и ONNX Runtime Extensions распространяются Microsoft Corporation по лицензии MIT. Тексты лицензий находятся рядом в `third_party/onnxruntime/LICENSE` и `third_party/onnxruntime-extensions/LICENSE` и должны входить в notices любого распространяемого APK.
