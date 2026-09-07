"""Run the opt-in corpus test in an explicitly selected, isolated Android test installation."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import tarfile

from build_corpus import digest


def run(adb: str, serial: str, corpus: Path, pack: Path, output: Path) -> None:
    repo = Path(__file__).resolve().parents[1]
    output = output.resolve()
    if output.is_relative_to(repo):
        raise ValueError("Raw device output must remain outside the Git repository")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=repo).strip():
        raise ValueError("Build and run a clean, committed tree for an attributable result")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    manifest_path = corpus / "manifest.json"
    manifest_hash = digest(manifest_path)
    if manifest_hash != digest(repo / "evaluation/corpus-v1.json"):
        raise ValueError("Corpus is not the pinned benchmark; do not silently change labels")
    if digest(pack) != "2c90206b2c1ac09233a2b4f3c882dbe4e721bd52ddc3bde46cc6631d51a42167":
        raise ValueError("Unreviewed model pack")
    manifest = json.loads(manifest_path.read_text())
    for asset in manifest["assets"]:
        path = (corpus / asset["file"]).resolve()
        if not path.is_relative_to(corpus.resolve()) or digest(path) != asset["sha256"]:
            raise ValueError("Corpus file identity mismatch")
    command = [adb, "-s", serial]
    if subprocess.check_output(command + ["shell", "getprop", "ro.product.cpu.abi"], text=True).strip() != "arm64-v8a":
        raise ValueError("ARM64 target required")
    output.mkdir(parents=True, exist_ok=False)
    with (output / "build.log").open("wb") as log:
        subprocess.run(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--no-daemon", "--max-workers=2"], cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
    # No install, uninstall, reset, permission grant, user switch, or system-setting changes here.
    # An old installed APK cannot be attributed to the current commit by passing an argument alone.
    for package, apk in (
        ("app.nayti.debug", repo / "app/build/outputs/apk/debug/app-debug.apk"),
        ("app.nayti.debug.test", repo / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
    ):
        installed = subprocess.check_output(command + ["shell", "pm", "path", package], text=True).strip().splitlines()
        if len(installed) != 1 or not installed[0].startswith("package:/data/app/"):
            raise ValueError("Expected the single installed test APK; install the freshly built artifacts first")
        remote_path = installed[0].removeprefix("package:")
        if any(char.isspace() for char in remote_path):
            raise ValueError("Unexpected APK path")
        actual = subprocess.check_output(command + ["shell", "sha256sum", remote_path], text=True).split()[0]
        if actual != digest(apk):
            raise ValueError("Installed APK differs from the current build; no evaluation started")
    subprocess.run(command + ["shell", "run-as", "app.nayti.debug", "mkdir", "-p", "files/evaluation"], check=True)
    staging_log = output / "staging.log"
    with staging_log.open("wb") as log:
        process = subprocess.Popen(command + ["shell", "-T", "run-as", "app.nayti.debug", "tar", "-xf", "-", "-C", "files/evaluation"], stdin=subprocess.PIPE, stdout=log, stderr=log)
        try:
            with tarfile.open(fileobj=process.stdin, mode="w|") as archive:
                archive.add(manifest_path, arcname="input/manifest.json", recursive=False)
                for asset in manifest["assets"]:
                    archive.add(corpus / asset["file"], arcname="input/" + asset["file"], recursive=False)
                archive.add(pack, arcname="model.naytipack", recursive=False)
            process.stdin.close()
            if process.wait(timeout=120) != 0:
                raise RuntimeError("Corpus staging failed; inspect local staging.log")
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
    log_path = output / "instrumentation.log"
    with log_path.open("wb") as log:
        result = subprocess.run(command + [
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "app.nayti.PublicCorpusEvaluationTest",
            "-e", "naytiEvaluation", "true", "-e", "sourceCommit", commit,
            "-e", "manifestSha256", manifest_hash,
            "app.nayti.debug.test/androidx.test.runner.AndroidJUnitRunner",
        ], stdout=log, stderr=subprocess.STDOUT, timeout=65 * 60)
    text = log_path.read_text(errors="replace")
    if result.returncode or "OK (1 test)" not in text or "FAILURES!!!" in text:
        raise RuntimeError("Android evaluation did not pass; inspect the local log, no metrics published")
    with (output / "run.json").open("wb") as target:
        subprocess.run(command + ["exec-out", "run-as", "app.nayti.debug", "cat", "files/evaluation/run.json"], stdout=target, check=True)
    from score import evaluate
    report = evaluate(manifest, json.loads((output / "run.json").read_text()), manifest_hash)
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Evaluation complete. Review safe aggregates in {output / 'summary.json'} before publication.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--isolated-test-installation", action="store_true", required=True)
    args = parser.parse_args()
    run(args.adb, args.serial, args.corpus, args.pack, args.output)
