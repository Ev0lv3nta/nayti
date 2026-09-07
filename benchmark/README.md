# Измерение запуска

Модуль содержит реальные cold/warm startup-сценарии: 10 и 30 повторов. Они измеряют первый кадр minified-варианта `app.nayti.benchmark`. Этот отдельный ID не заменяет личную установку `app.nayti`. Profileable включён только в benchmark-варианте; release остаётся неизменным.

На выделенном ARM64-устройстве после проверки serial:

```bash
runtime="$(./scripts/fetch_reduced_ort.sh)"
ANDROID_SERIAL="$SERIAL" NAYTI_ORT_AAR="$runtime" ./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Сборка `:benchmark:assemble` только компилирует тесты. Она не измеряет скорость. Чисел текущего прогона пока нет: выполнение на устройстве отложено. В отчёте нужно указать, был ли это первый запуск без моделей или запуск подготовленного тестового профиля; эти сценарии несопоставимы между собой.

Результаты и traces остаются в `benchmark/build/outputs/`. Не публикуйте traces, полученные на личных данных. Startup benchmark не заменяет измерение поиска, индексации или скролла на безопасном корпусе.
