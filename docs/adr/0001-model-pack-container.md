# ADR 0001: контейнер model pack без сжатия

Статус: принято 17 июля 2026 года.

## Контекст

Model pack приходит через SAF или локальный debug-инструмент и считается полностью недоверенным до завершения проверки. Обычный ZIP добавляет форматы local/central headers, платформенные атрибуты, ссылки, коэффициент распаковки и неоднозначное поведение при trailing payload. ORT-графы и бинарные tensor fixtures при этом почти не выигрывают от повторного сжатия.

## Решение

Формат v1 — последовательный контейнер без сжатия:

```text
0..7    magic `NAYTIPK1`
8..11   длина canonical manifest, uint32 big-endian
12..13  длина Ed25519 signature, uint16 big-endian
14..15  reserved, всегда ноль
...     canonical UTF-8 JSON manifest
...     Ed25519 signature manifest bytes с domain separator
...     payload каждого файла в порядке `manifest.files`
EOF     должен наступить сразу после последнего declared byte
```

Manifest содержит exact path, role, length и SHA-256 каждого payload. Пути нормализованы как относительные POSIX paths; запрещены пустые сегменты, `.`, `..`, absolute paths, backslash, управляющие символы, duplicates и Unicode case collisions. Число файлов, manifest, один файл и общий payload имеют жёсткие caps.

Подпись покрывает exact canonical manifest, а manifest — hashes и lengths всего payload. Private Ed25519 key не связан с APK signing и не находится в Git. `keyId` вычисляется из SHA-256 X.509 DER public key.

Android импортирует контейнер только в app-private staging. До публикации проверяются canonical manifest, доверенный key ID, подпись, compatibility policy, declared length/SHA-256 каждого файла и exact EOF. Затем production ORT runtime открывает все семь графов и выполняет signed known-answer suite; после callback payload повторно хешируется, чтобы валидатор не мог незаметно изменить candidate. Только после этого staging атомарно переименовывается в immutable directory и регистрируется со статусом `INSTALLED_CANDIDATE`. Установка сама по себе не меняет active pack.

После process restart runtime не принимает произвольный filesystem path. Он разрешает точные `packId` и `packVersion` через Room registry, проверяет canonical private-directory layout и SHA-256 manifest, а native sessions открывает только из найденного `payload`. OCR sessions принадлежат одному bounded execution window и закрываются вместе с ним. Использование candidate как явно указанной цели shadow-индексации не делает его query-active: это происходит только через отдельный `ActivationSnapshot` после coverage gates.

## Последствия

- импорт выполняется одним bounded stream без временной распаковки архива целиком;
- links, devices, compression bombs и duplicate archive headers отсутствуют в формате по определению;
- изменённый byte, лишний byte в конце или недостающий payload отклоняют весь candidate;
- ошибка подписи, policy либо runtime KAT удаляет staging и не затрагивает active state;
- pack немного больше потенциально сжатого ZIP, но это приемлемо для уже оптимизированных model binaries;
- если будущие измерения покажут существенную пользу compression, она получит новый versioned format, а не неявное расширение v1.
