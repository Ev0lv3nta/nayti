# Подпись personal alpha

Для кандидата `0.1.0-alpha.2` (app code 2) сохраняется прежний сертификат. Model pack alpha.2 остаётся неизменным, с узким manifest-hash разрешением для нового app code; APK signing и Ed25519 pack signing — разные проверки. `assemble_alpha_bundle.sh` собирает кандидат, но не подтверждает device gates и не публикует release автоматически.

Публичные APK Nayti подписываются отдельным alpha release key. Android Debug certificate и model-pack Ed25519 key для этого не используются.

## Граница секретов

- keystore хранится вне репозитория в каталоге с правами доступа `0700`;
- пароль хранится в macOS Keychain, а не в файле проекта;
- CI не получает signing secrets и собирает только unsigned control APK;
- release bundle содержит только SHA-256 публичного сертификата;
- до публикации первого APK keystore и пароль должны иметь независимую зашифрованную резервную копию вне этого Mac.

Потеря alpha key лишит возможности обновлять уже установленную personal alpha. Смена ключа потребует удаления приложения вместе с private model pack и индексом.

## Переменные сборки

Локальная release-сборка требует полный набор:

```text
NAYTI_RELEASE_KEYSTORE
NAYTI_RELEASE_KEY_ALIAS
NAYTI_RELEASE_STORE_PASSWORD
NAYTI_RELEASE_KEY_PASSWORD
```

Если задана только часть значений, Gradle завершает конфигурацию с ошибкой. `scripts/assemble_alpha_bundle.sh` дополнительно проверяет подпись через `apksigner`, требует v3 signature и точное совпадение с закреплённым публичным SHA-256 сертификата. Android Debug certificate и случайно выбранный другой release key отклоняются.

## Ротация

Ключ не ротируется между alpha-обновлениями без отдельного migration plan. Его публичный certificate fingerprint фиксируется в `BUILD-INFO.txt`, `SHA256SUMS` и GitHub Release; private key и пароли туда не попадают.
