#!/usr/bin/env python3
"""Generate deterministic CycloneDX and notices from Gradle's release report."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote


GRAPH_LINE = re.compile(r"^[| ]*(?:\+---|\\---)\s+(.*)$")
TRAILING_MARKERS = re.compile(r"\s+\((?:\*|c|n)\).*$")


@dataclass(frozen=True, order=True)
class Component:
    group: str
    name: str
    version: str
    license: str = ""
    purl: str = ""


def parse_gradle_report(text: str) -> list[Component]:
    components: set[Component] = set()
    for raw_line in text.splitlines():
        graph_line = GRAPH_LINE.match(raw_line)
        if graph_line is None:
            continue
        body = graph_line.group(1).strip()
        if not body or body.startswith("project ") or body.startswith("/"):
            continue
        body = TRAILING_MARKERS.sub("", body)
        left, separator, selected = body.partition(" -> ")
        parts = left.split(":", 2)
        if len(parts) != 3:
            continue
        group, name, requested = parts
        if not group or not name or "." not in group:
            continue
        if name.endswith("-bom") or name == "compose-bom":
            continue
        if requested.startswith("{strictly "):
            requested = requested.removeprefix("{strictly ").split("}", 1)[0]
        version = selected.split()[0] if separator else requested.split()[0]
        if ":" in version:
            version = version.rsplit(":", 1)[-1]
        version = version.strip("{}")
        if not version or version in {"FAILED", "unspecified"}:
            continue
        components.add(Component(group=group, name=name, version=version))
    return sorted(components)


def load_policy(path: Path) -> dict[str, object]:
    policy = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(policy, dict):
        raise ValueError("license policy must be a JSON object")
    return policy


def apply_policy(
    components: list[Component],
    policy: dict[str, object],
) -> list[Component]:
    allowed = set(policy.get("allowedLicenses", []))
    rules = policy.get("groupRules", [])
    if not allowed or not isinstance(rules, list):
        raise ValueError("license policy is incomplete")

    licensed: list[Component] = []
    for component in components:
        license_id = next(
            (
                rule["license"]
                for rule in rules
                if isinstance(rule, dict)
                and component.group.startswith(str(rule.get("prefix", "")))
            ),
            None,
        )
        if not license_id:
            raise ValueError(
                f"release component has no reviewed license rule: "
                f"{component.group}:{component.name}:{component.version}"
            )
        if license_id not in allowed:
            raise ValueError(f"release component uses a disallowed license: {license_id}")
        purl = (
            f"pkg:maven/{quote(component.group, safe='.')}/"
            f"{quote(component.name, safe='-._')}@{quote(component.version, safe='-._')}"
        )
        licensed.append(
            Component(
                group=component.group,
                name=component.name,
                version=component.version,
                license=str(license_id),
                purl=purl,
            )
        )

    local_components = policy.get("localComponents", [])
    if not isinstance(local_components, list):
        raise ValueError("localComponents must be an array")
    for raw in local_components:
        if not isinstance(raw, dict):
            raise ValueError("local component must be an object")
        license_id = str(raw.get("license", ""))
        if license_id not in allowed:
            raise ValueError(f"local component uses a disallowed license: {license_id}")
        licensed.append(
            Component(
                group=str(raw["group"]),
                name=str(raw["name"]),
                version=str(raw["version"]),
                license=license_id,
                purl=str(raw["purl"]),
            )
        )
    return sorted(set(licensed), key=lambda component: component.purl)


def cyclonedx(components: list[Component]) -> dict[str, object]:
    root_ref = "pkg:generic/nayti@0.1.0-dev"
    entries = [
        {
            "type": "library",
            "bom-ref": component.purl,
            "group": component.group,
            "name": component.name,
            "version": component.version,
            "licenses": [{"license": {"id": component.license}}],
            "purl": component.purl,
        }
        for component in components
    ]
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": root_ref,
                "group": "app.nayti",
                "name": "nayti",
                "version": "0.1.0-dev",
                "licenses": [{"license": {"id": "Apache-2.0"}}],
                "purl": root_ref,
            }
        },
        "components": entries,
        "dependencies": [
            {"ref": root_ref, "dependsOn": [entry["bom-ref"] for entry in entries]},
            *({"ref": entry["bom-ref"], "dependsOn": []} for entry in entries),
        ],
    }


def notices(components: list[Component]) -> str:
    by_license: dict[str, list[Component]] = {}
    for component in components:
        by_license.setdefault(component.license, []).append(component)
    lines = [
        "# Third-party notices for Nayti 0.1.0-dev",
        "",
        "This inventory is generated from the resolved Android release runtime classpath.",
        "The corresponding license texts are packaged in the APK under `assets/`.",
    ]
    for license_id in sorted(by_license):
        lines.extend(("", f"## {license_id}", ""))
        lines.extend(
            f"- `{component.group}:{component.name}:{component.version}`"
            for component in by_license[license_id]
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--notices", type=Path, required=True)
    args = parser.parse_args()

    resolved = parse_gradle_report(args.report.read_text(encoding="utf-8"))
    if not resolved:
        raise SystemExit("release dependency report contained no external components")
    components = apply_policy(resolved, load_policy(args.policy))

    args.sbom.parent.mkdir(parents=True, exist_ok=True)
    args.notices.parent.mkdir(parents=True, exist_ok=True)
    args.sbom.write_text(
        json.dumps(cyclonedx(components), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    args.notices.write_text(notices(components), encoding="utf-8")
    print(f"release SBOM generated for {len(components)} licensed components")


if __name__ == "__main__":
    main()
