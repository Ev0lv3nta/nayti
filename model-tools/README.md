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
uv run --project model-tools pytest
```

`audit` не скачивает веса и показывает точный объём pinned source artifacts. `fetch` отказывается работать при менее чем 100 ГБ свободного места, использует максимум два download workers и после загрузки проверяет byte length и SHA-256. `verify` можно безопасно повторять офлайн.

`export-user2` работает только по локальным проверенным bytes, сам перезапускает Python с детерминированным hash seed, экспортирует fixed-shape FP32 graph, выполняет `onnx.checker`, открывает его CPU ONNX Runtime и сравнивает 100 embeddings с PyTorch reference. Существующий export без явного `--force` не перезаписывается.

`export-user2-tokenizer` требует локальную сборку ONNX Runtime Extensions с revision из `manifests/runtime-sources.v1.json`. Он создаёт fixed-shape `[1,128]` graph и требует NFC-normalized UTF-8 input; BPE, special tokens, truncation, padding и attention mask находятся в graph. Gate сравнивает все 500 синтетических строк с official tokenizer byte-for-byte.

Pinned validation build создаётся командой `model-tools/scripts/build_ortx_validation.sh`. Скрипт отказывается работать вне project-local `model-lab`, проверяет 100 ГБ свободного места и чистоту checkout, устанавливает source build только в `UV_PROJECT_ENVIRONMENT` и ограничивает C++ compilation двумя workers. Следующий tokenizer export запускается с `uv run --no-sync`, чтобы `uv` намеренно не заменил validation build опубликованным wheel из основного lockfile.

Непинованные revisions, глобальный Hugging Face cache и пользовательские фотографии в этом toolchain запрещены.
