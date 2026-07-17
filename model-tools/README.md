# Model tools

Здесь находится воспроизводимый toolchain для model-export proof Nayti. В Git входят только код, lockfile, source manifest и синтетические fixtures. Веса, ONNX exports, runtime builds и AAR хранятся в отдельном локальном `model-lab` и никогда не коммитятся.

## Изолированный запуск

```bash
export UV_CACHE_DIR=/path/to/nayti/model-lab/uv-cache
export UV_PYTHON_INSTALL_DIR=/path/to/nayti/model-lab/python
export NAYTI_MODEL_LAB=/path/to/nayti/model-lab
export UV_PROJECT_ENVIRONMENT=$NAYTI_MODEL_LAB/venv
export HF_HOME=$NAYTI_MODEL_LAB/hf-home

uv python install 3.12
uv sync --project model-tools --frozen
uv run --project model-tools nayti-model audit
uv run --project model-tools nayti-model fetch --max-workers 2
uv run --project model-tools nayti-model verify
uv run --project model-tools nayti-model export-user2
uv run --project model-tools nayti-model export-siglip2
uv run --project model-tools nayti-model verify-ocr
model-tools/scripts/build_ortx_validation.sh
uv run --project model-tools --no-sync nayti-model export-user2-tokenizer
uv run --project model-tools --no-sync nayti-model export-siglip2-tokenizer
uv run --project model-tools --no-sync nayti-model convert-ort
uv run --project model-tools --no-sync nayti-model verify-ort
uv run --project model-tools --no-sync nayti-model prepare-android-kat
uv run --project model-tools --no-sync nayti-model quantize-encoders
uv run --project model-tools --no-sync nayti-model pack-keygen
uv run --project model-tools --no-sync nayti-model pack-assemble
uv run --project model-tools --no-sync nayti-model pack-inspect --pack "$NAYTI_MODEL_LAB/model-packs/nayti-alpha.naytipack"
model-tools/scripts/build_reduced_ort_android.sh
model-tools/scripts/verify_reduced_ort_aar.sh
model-tools/scripts/run_reduced_ort_android_smoke.sh
model-tools/scripts/run_signed_pack_android_smoke.sh
uv run --project model-tools pytest
```

`audit` не скачивает веса и показывает точный объём pinned source artifacts. `fetch` отказывается работать при менее чем 100 ГБ свободного места, использует максимум два download workers и после загрузки проверяет byte length и SHA-256. `verify` можно безопасно повторять офлайн.

`export-user2` работает только по локальным проверенным bytes, сам перезапускает Python с детерминированным hash seed, экспортирует fixed-shape FP32 graph, выполняет `onnx.checker`, открывает его CPU ONNX Runtime и сравнивает 100 embeddings с PyTorch reference. Существующий export без явного `--force` не перезаписывается.

`export-user2-tokenizer` требует локальную сборку ONNX Runtime Extensions с revision из `manifests/runtime-sources.v1.json`. Он создаёт fixed-shape `[1,128]` graph и требует NFC-normalized UTF-8 input; BPE, special tokens, truncation, padding и attention mask находятся в graph. Gate сравнивает все 500 синтетических строк с official tokenizer byte-for-byte.

Pinned validation build создаётся командой `model-tools/scripts/build_ortx_validation.sh`. Скрипт отказывается работать вне project-local `model-lab`, проверяет 100 ГБ свободного места и чистоту checkout, устанавливает source build только в `UV_PROJECT_ENVIRONMENT` и ограничивает C++ compilation двумя workers. Следующий tokenizer export запускается с `uv run --no-sync`, чтобы `uv` намеренно не заменил validation build опубликованным wheel из основного lockfile.

`convert-ort` берёт ровно семь проверенных graphs, создаёт Fixed/ARM ORT artifacts с type reduction и генерирует operator config для minimal runtime. Для legacy IR3 Paddle recognizer меняется только IR marker на 4: иначе ORT converter ошибочно превращает embedded initializers в обязательные runtime inputs. Исходный graph не изменяется. `verify-ort` требует тот же public input/output contract и сравнивает выходы каждой пары ONNX↔ORT.

`build_reduced_ort_android.sh` проверяет exact ORT/Extensions revisions, 100 ГиБ free-space gate и нулевой swap, затем собирает только `arm64-v8a` Release AAR с двумя workers. Pinned Extensions generator ещё не знает имя `HfJsonTokenizer`, хотя сам op уже входит в GPT2-tokenizer family. Build-only config выбирает ту же family через известное generator имя `GPT2Tokenizer`; deploy graph и runtime contract не меняются. Upstream checkouts не патчатся, лишний standalone Extensions C API не собирается. Released ORT tag собирается с `--compile_no_warning_as_error`: это сохраняет предупреждения в логе, но не ломает воспроизводимую сборку из-за новых диагностик pinned Android NDK.

После сборки `verify_reduced_ort_aar.sh` проверяет exact ARM64 library set, 16 KiB ELF LOAD alignment, AArch64 headers, системные зависимости и наличие публичного ORT API вместе с `HfJsonTokenizer`. AAR и распакованные библиотеки остаются только в `model-lab`.

`prepare-android-kat` сохраняет synthetic raw inputs, cross-platform reference outputs, tensor contracts и exact model identities в локальный `android-kat`. `run_reduced_ort_android_smoke.sh` требует одно подключённое ARM64-устройство, включает test-only Gradle module только через explicit `NAYTI_ORT_AAR`, проверяет ELF/APK ZIP alignment, потоково помещает KAT и семь models в private app storage, запускает их последовательно и всегда очищает временные bytes/package. Ожидаемый page size можно зафиксировать через `NAYTI_EXPECTED_PAGE_SIZE`.

`run_signed_pack_android_smoke.sh` проверяет exact SHA-256 alpha pack и reduced AAR, оценивает свободное место до копирования и прогоняет production importer на недоверенном контейнере. Все семь ORT known-answer tests выполняются над app-private staging payload; immutable candidate публикуется только после их успеха. Скрипт всегда удаляет временный pack, candidate directory и test APK.

`quantize-encoders` строит measured mixed profile из проверенных FP32 exports. SigLIP2 image остаётся FP32; SigLIP2 text получает только row-wise QInt8 token embedding; USER2 дополнительно использует dynamic per-channel QInt8 для constant-weight MatMul/Gemm. Более агрессивные SigLIP2 candidates прошли host, но не ARM64 cosine gate и не публикуются. Candidate становится deploy-артефактом атомарно только после held-out cosine и top-10 retrieval gates. OCR и tokenizer graphs остаются FP32/int64.

`pack-keygen` создаёт отдельную workspace-local пару Ed25519 для alpha model pack; private key никогда не попадает в Git и не связан с APK signing. `pack-assemble` строит deterministic uncompressed container из reviewable profile, подписывает canonical manifest и затем заново проверяет подпись, exact lengths и SHA-256 всего payload. `pack-inspect` выполняет ту же потоковую проверку без извлечения файлов. Формат и threat model зафиксированы в `docs/adr/0001-model-pack-container.md`.

Reviewable `pack-profile.alpha1.json` перечисляет семь ORT graphs, machine-readable contracts, exact KAT, preprocessing, operator allowlist, provenance, CycloneDX SBOM, notices и Apache-2.0 text. Generated pack и private key остаются в `model-lab`; в Git находятся только профиль, публичные сведения о происхождении и тесты формата.

Непинованные revisions, глобальный Hugging Face cache и пользовательские фотографии в этом toolchain запрещены.
