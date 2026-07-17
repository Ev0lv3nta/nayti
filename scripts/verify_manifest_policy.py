#!/usr/bin/env python3
"""Fail when the packaged app drifts from Nayti's offline permission policy."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID = "{http://schemas.android.com/apk/res/android}"
ALLOWED_PERMISSIONS = {
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
}
ALLOWED_EXPORTED_COMPONENTS = {"app.nayti.MainActivity"}
DEBUG_ONLY_EXPORTED_COMPONENTS = {
    "androidx.activity.ComponentActivity",
    "androidx.compose.ui.tooling.PreviewActivity",
}
PROTECTED_EXPORTED_COMPONENTS = {
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
}


def fail(message: str) -> None:
    print(f"manifest policy violation: {message}", file=sys.stderr)
    raise SystemExit(1)


def attr(element: ET.Element, name: str) -> str | None:
    return element.get(f"{ANDROID}{name}")


def normalize_component(package_name: str, name: str) -> str:
    if name.startswith("."):
        return f"{package_name}{name}"
    if "." not in name:
        return f"{package_name}.{name}"
    return name


def main() -> None:
    if len(sys.argv) != 2:
        fail("expected one merged AndroidManifest.xml path")

    manifest_path = Path(sys.argv[1])
    if not manifest_path.is_file():
        fail(f"merged manifest not found: {manifest_path}")

    root = ET.parse(manifest_path).getroot()
    package_name = root.get("package") or "app.nayti"
    permissions = {
        attr(node, "name")
        for tag in ("uses-permission", "uses-permission-sdk-23")
        for node in root.findall(tag)
    }
    permissions.discard(None)

    internal_receiver_permission = (
        f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    )
    permissions.discard(internal_receiver_permission)

    unexpected = permissions - ALLOWED_PERMISSIONS
    if unexpected:
        fail(f"unexpected permissions: {', '.join(sorted(unexpected))}")
    if "android.permission.INTERNET" in permissions:
        fail("INTERNET permission is forbidden")

    application = root.find("application")
    if application is None:
        fail("application element is missing")
    if attr(application, "allowBackup") != "false":
        fail("android:allowBackup must remain false")
    if attr(application, "usesCleartextTraffic") == "true":
        fail("cleartext network traffic must not be enabled")

    exported_components: set[str] = set()
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for component in application.findall(tag):
            if attr(component, "exported") == "true":
                name = attr(component, "name")
                if not name:
                    fail(f"exported {tag} has no android:name")
                normalized_name = normalize_component(package_name, name)
                required_permission = PROTECTED_EXPORTED_COMPONENTS.get(normalized_name)
                if required_permission and attr(component, "permission") == required_permission:
                    continue
                exported_components.add(normalized_name)

    allowed_exports = set(ALLOWED_EXPORTED_COMPONENTS)
    if package_name.endswith(".debug"):
        allowed_exports.update(DEBUG_ONLY_EXPORTED_COMPONENTS)
    unexpected_exports = exported_components - allowed_exports
    if unexpected_exports:
        fail(f"unexpected exported components: {', '.join(sorted(unexpected_exports))}")
    if "app.nayti.MainActivity" not in exported_components:
        fail("launcher activity must be the only exported component")

    print(
        "manifest policy verified: offline permission allowlist, backup disabled, "
        "and exported components constrained"
    )


if __name__ == "__main__":
    main()
