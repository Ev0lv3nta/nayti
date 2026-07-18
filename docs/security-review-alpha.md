# Security и privacy review перед device alpha

Дата review: 19 июля 2026. Область: Android application, локальное хранилище и индекс, SAF/model-pack import, diagnostics, release dependency graph и unsigned release APK. Производительность, поведение прошивки Samsung и production signing остаются отдельными device/release gates.

## Сетевой и platform boundary

- Release manifest не содержит `INTERNET`; приложение не включает analytics, ads, remote config или telemetry SDK.
- Разрешения ограничены чтением фотографий, уведомлениями и foreground execution. `ACCESS_NETWORK_STATE`, `WAKE_LOCK` и boot receiver приходят из WorkManager, но без `INTERNET` не создают сетевого data flow.
- Единственная публичная application activity — launcher `MainActivity`. Foreground service и providers не экспортируются; системные WorkManager/Profile Installer components либо закрыты, либо защищены системным permission.
- Все `PendingIntent` создаются для явных application intents и имеют `FLAG_IMMUTABLE`.
- Cleartext traffic не включён, network security config отсутствует, legacy external storage не запрашивается. Release variant не debuggable.

Merged debug и release manifests проверяются fail-closed allowlist-скриптом. Gate отдельно требует `allowBackup=false`, `backup_rules` и `data_extraction_rules`; database, files, shared preferences и external app data исключены и из cloud backup, и из device transfer.

## Доступ к фотографиям и локальные данные

- MediaStore URI открываются только через текущий platform grant. Query/publication повторно сверяют access revision, source fingerprint и snapshot contract до выдачи данных.
- Selected Photos Access сначала немедленно скрывает asset из всех retrievers, затем держит производные данные в 30-дневном quarantine. Просроченные OCR/FTS/vector/pHash/work данные удаляются bounded GC с учётом живых query leases и незавершённой activation.
- Thumbnail cache существует только в RAM. Cache key включает access и catalog revision; смена любой revision очищает cache. Decode, завершившийся после revoke, не возвращается в UI и немедленно освобождает bitmap.
- Search, similar, duplicate и viewer state сбрасываются при смене access/catalog revision. Viewer не удерживает старое изображение после отзыва доступа.
- Явный Reset index удаляет все производные данные, но сохраняет MediaStore catalog и verified model-pack registry; файловая очистка не следует symlink.

## Недоверенные файлы и диагностика

- Model pack импортируется только через SAF и собственный signed container format. Проверяются canonical paths, размеры, число entries, EOF, SHA-256 каждого payload, Ed25519 signature, manifest policy, decoder contract и sequential runtime KAT. Архивные symlink/path traversal/compression обходы не используются.
- Installed pack повторно проверяется по app-private canonical path и manifest hash после process restart. Candidate не меняет active snapshot до canary, deep integrity verification и атомарной activation.
- Diagnostics export содержит только версии, агрегированные counts/status и device/storage context. Query text, OCR, filenames, URI, MediaStore IDs, thumbnails, embeddings и изображения запрещены и закрыты regression test.
- Production-код не пишет пользовательские данные через Android Log, `println`, stack traces или telemetry.

## Dependencies, лицензии и ключи

- Build-time PyTorch обновлён до версии без известного low-severity advisory; он не входит в Android APK.
- Release dependency report преобразуется в CycloneDX 1.6 SBOM. Любой новый Maven group без review ломает CI; текущий allowlist содержит только Apache-2.0, BSD-3-Clause и MIT.
- Reduced ONNX Runtime распространяется отдельным prerelease asset, проверяется по точному имени, размеру и SHA-256, а затем повторно проверяется Gradle. Полный Maven ORT fallback отсутствует.
- Apache-2.0, Protocol Buffers BSD-3-Clause и обе MIT-лицензии ORT включаются в APK assets; точный список resolved components публикуется рядом с unsigned release APK.
- Private model-pack signing key и будущий production Android signing key не входят в Git, Gradle configuration или CI. В приложении находится только публичный Ed25519 verification key. Release APK на этом этапе намеренно unsigned.
- GitHub secret scanning и push protection включены; `.gitignore` исключает keys, model packs, APK/AAB/AAR и локальную диагностику.

## Результат и остаточные gates

Device-independent review не обнаружил открытого сетевого data flow, экспортированного application component без необходимости, backup-утечки, известного vulnerable runtime dependency или пути возврата thumbnail после revoke. API 30 прошёл privacy regression, а R8-minified ARM64 release APK прошёл manifest и 16 KiB checks.

Synthetic scale/resource rehearsal и полная emulator matrix API 30–37, включая реальный 16K process на API 37, завершены отдельно в [resource-rehearsal-alpha.md](resource-rehearsal-alpha.md). До personal alpha остаются финальные checksums/runbooks и проверка на Galaxy S23+. Production signing, Google Play policy и публичное распространение model pack в этот review не входят.
