# ADR 0004: immutable vector publication

Статус: принято 17 июля 2026 года.

## Контекст

Векторный результат нельзя считать опубликованным после inference или появления файла. Process death возможен между write, `fsync`, rename, manifest и Room transaction; query при этом не должен увидеть частичный generation. Обновление visual/semantic пространства также нельзя отделять от pack, ranking contract и остальных query-active каналов.

## Решение

QINT8 records кодируются каноническим writer формата `NAYTIVEC` v1: не более 256 records, один channel, dimension и embedding-space hash, zero padding и content SHA-256. Segment записывается bounded loop во временный файл на том же filesystem, синхронизируется, атомарно переименовывается в content-addressed read-only path и проверяется production native parser до изменения Room state.

После seal связанные work leases переходят в долговечный `STAGED`. Затем создаётся канонический immutable manifest с полным упорядоченным списком segments. Одна Room transaction повторно проверяет access revision, source fingerprints, generation/segment/manifest contracts и optimistic parent snapshot, регистрирует records/artifacts, публикует новый `ActivationSnapshot`, переключает active pointer и только после этого переводит work rows и publication в `DONE`.

`ActivationSnapshot` является единственной query-visible единицей. Он pin-ит pack manifest, engine/ranking versions, lexical/pHash epochs, semantic/visual manifest revisions, catalog watermark и parent rollback slot. `QuerySnapshotLease` выдаётся только для текущего active snapshot и текущей access revision.

Startup recovery оставляет active pointer на последнем самосогласованном snapshot, откатывается к parent при corrupt child, сбрасывает незавершённые `STAGED` publications в `PENDING`, удаляет temp и только после grace — filesystem orphans. Быстрый режим проверяет manifests и segment metadata; deep scrub дополнительно проверяет SHA-256 и native parser.

GC не изменяет active files. Active snapshot, его непосредственный rollback parent и snapshots с живыми query leases защищены. Для остальных сначала одной transaction фиксируются delete intents с ожидаемыми hashes, затем проверенные файлы удаляются и синхронизируется directory, после чего metadata удаляется второй transaction. Recovery replay-ит intents после process death; shared content-addressed segments сохраняются, пока на них ссылается хотя бы один manifest.

Generation после достижения cutover coverage явно переводится из `BUILDING` в `SEALED`; новые work publications в него после этого запрещены. Compaction допускается и для sealed generation, потому что не меняет логический набор records или embedding space: bounded группа соседних segments общим размером не более 256 records читается после SHA/native validation, кодируется в новый segment, а новый manifest обязан сохранить полный record count. Старые файлы не перезаписываются и остаются доступны через rollback parent.

Один startup entry point сначала освобождает истёкшие execution/work leases, затем replay-ит vector delete intents, abandoned `STAGED`, query leases, active rollback и orphan cleanup. I/O protocol имеет fault boundaries отдельно для segment и manifest: write chunk, file fsync, atomic move и directory fsync. Ошибка на любой из них не меняет active pointer и устраняется тем же recovery, включая `ENOSPC`/`EIO`-подобные исключения.

## Последствия

- `DONE` означает durable filesystem + manifest + snapshot publication, а не окончание вычисления;
- crash до DB commit оставляет только невидимый temp/orphan или recoverable `STAGED`, crash после commit безопасен;
- устаревший concurrent publisher не может заменить active snapshot, потому что parent проверяется внутри transaction;
- corrupt child не требует destructive reset и не повреждает целого parent;
- installed model pack и query-active snapshot остаются разными состояниями;
- compaction обязана строить новые segments/manifest рядом и использовать тот же publication/GC protocol.
