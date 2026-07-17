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

## Последствия

- импорт выполняется одним bounded stream без временной распаковки архива целиком;
- links, devices, compression bombs и duplicate archive headers отсутствуют в формате по определению;
- изменённый byte, лишний byte в конце или недостающий payload отклоняют весь candidate;
- pack немного больше потенциально сжатого ZIP, но это приемлемо для уже оптимизированных model binaries;
- если будущие измерения покажут существенную пользу compression, она получит новый versioned format, а не неявное расширение v1.
