from __future__ import annotations

import hashlib
import json

TOKENIZER_CASE_COUNT = 500


def tokenizer_cases() -> tuple[str, ...]:
    cases: list[str] = []
    for index in range(100):
        cases.extend(
            (
                _russian_case(index),
                _english_case(index),
                _mixed_case(index),
                _identifier_case(index),
                _boundary_case(index),
            ),
        )
    result = tuple(cases)
    if len(result) != TOKENIZER_CASE_COUNT or len(set(result)) != TOKENIZER_CASE_COUNT:
        raise AssertionError("tokenizer corpus must contain 500 unique cases")
    return result


def corpus_sha256(cases: tuple[str, ...]) -> str:
    canonical = json.dumps(cases, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _russian_case(index: int) -> str:
    subjects = (
        "договор аренды квартиры",
        "рыжий кот на синем диване",
        "чек из продуктового магазина",
        "расписание поездов на завтра",
        "фотография зимнего леса",
    )
    qualifiers = ("срочно", "без печати", "за июль", "оригинал", "копия №2")
    return f"Найди {subjects[index % len(subjects)]}, {qualifiers[(index // 5) % len(qualifiers)]} — пример {index:03d}"


def _english_case(index: int) -> str:
    subjects = (
        "sunset over a quiet lake",
        "handwritten meeting notes",
        "red bicycle near the station",
        "invoice for cloud hosting",
        "family picnic in the park",
    )
    return f"Find {subjects[index % len(subjects)]}; revision {index:03d}, don't crop the image."


def _mixed_case(index: int) -> str:
    emoji = ("📷", "🔎", "🧾", "🏡", "🚲")
    return (
        f"Фото {emoji[index % len(emoji)]} from trip-{2020 + index % 7}: "
        f"Москва / Berlin, tag=Лето{index:02d}, ёлка & café"
    )


def _identifier_case(index: int) -> str:
    return (
        f"Договор № AB-{index:04d}/{2024 + index % 3}; "
        f"полис ЕЕЕ {100000 + index}; +7 (999) 120-{index:02d}-{(index * 7) % 100:02d}"
    )


def _boundary_case(index: int) -> str:
    repetitions = 56 + index % 20
    long_tail = " ".join(f"токен{(index + offset) % 31}" for offset in range(repetitions))
    leading = "\t" if index % 2 == 0 else "  "
    separator = "\n" if index % 3 == 0 else "\u00a0"
    return f"{leading}boundary-{index:03d}{separator}{long_tail} 😀 end-{index:03d}  "
