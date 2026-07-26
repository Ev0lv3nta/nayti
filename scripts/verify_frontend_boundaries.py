#!/usr/bin/env python3
"""Fail fast when the production UI drifts back to legacy or storage-layer contracts."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "app/src/main/kotlin/app/nayti/ui"
PRESENTATION_SUFFIXES = ("Screen.kt", "Sheet.kt", "Card.kt")


def main() -> None:
    kotlin_sources = sorted(UI_ROOT.rglob("*.kt"))
    violations: list[str] = []

    legacy_sources = sorted((UI_ROOT / "theme").rglob("*.kt"))
    if legacy_sources:
        violations.extend(f"legacy theme source: {path.relative_to(ROOT)}" for path in legacy_sources)

    theme_definitions = sum(
        len(re.findall(r"\bfun\s+NaytiTheme\s*\(", source.read_text()))
        for source in kotlin_sources
    )
    if theme_definitions != 1:
        violations.append(
            f"production must define exactly one NaytiTheme; found {theme_definitions}"
        )

    presentation_sources = [
        source
        for source in kotlin_sources
        if source.name.endswith(PRESENTATION_SUFFIXES)
        or source.name == "CommonComponents.kt"
    ]
    for source in presentation_sources:
        for line_number, line in enumerate(source.read_text().splitlines(), start=1):
            if line.startswith("import app.nayti.storage."):
                violations.append(
                    f"{source.relative_to(ROOT)}:{line_number}: presentation imports storage: {line}"
                )

    if violations:
        raise SystemExit("Frontend boundary violations:\n" + "\n".join(violations))
    print(
        f"frontend boundaries verified: {len(kotlin_sources)} UI sources, "
        "one theme, no storage imports in presentation"
    )


if __name__ == "__main__":
    main()
