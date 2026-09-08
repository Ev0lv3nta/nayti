"""Validate an actual Android run and report bounded, per-category retrieval metrics."""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

EXACT_REASONS = {"EXACT_IDENTIFIER", "QUOTED_PHRASE", "PERSON_NAME", "LITERAL_TEXT"}


def evaluate(manifest: dict, run: dict, manifest_sha256: str) -> dict:
    if run.get("schema") != 1 or run.get("manifest_sha256") != manifest_sha256:
        raise ValueError("Run does not match this corpus manifest")
    metadata = run["metadata"]
    allowed_metadata = {"source_commit", "pack_manifest_sha256", "app_version", "android_api", "abi", "page_size", "preprocessing"}
    if set(metadata) != allowed_metadata:
        raise ValueError("Metadata must not include device identifiers or arbitrary extra fields")
    for key, pattern in (("source_commit", r"[0-9a-f]{40}"), ("pack_manifest_sha256", r"[0-9a-f]{64}")):
        if not re.fullmatch(pattern, metadata.get(key, "")):
            raise ValueError(f"Missing or invalid {key}")
    for key in ("app_version", "android_api", "abi", "page_size", "preprocessing"):
        if not metadata.get(key):
            raise ValueError(f"Missing {key}")
    if metadata["abi"] != "arm64-v8a" or run.get("execution") != "production-mediastore-index-query":
        raise ValueError("This report requires the production ARM64 model-backed runner")
    assets = {asset["id"]: asset for asset in manifest["assets"]}
    queries = {query["id"]: query for query in manifest["queries"]}
    if len(assets) != len(manifest["assets"]) or len(queries) != len(manifest["queries"]):
        raise ValueError("Duplicate corpus IDs")
    families = defaultdict(set)
    for asset in assets.values():
        families[asset["family"]].add(asset["split"])
    if any(len(splits) != 1 for splits in families.values()):
        raise ValueError("Related-image family leaks across development and holdout")
    seen = set()
    grouped = defaultdict(list)
    for row in run["results"]:
        query_id = row["query_id"]
        if query_id not in queries or query_id in seen:
            raise ValueError("Unexpected or duplicate query result")
        seen.add(query_id)
        query = queries[query_id]
        if row.get("status") != "complete":
            raise ValueError(f"Query {query_id} did not complete; do not score a partial run")
        milliseconds = row["latency_ms"]
        if not isinstance(milliseconds, (int, float)) or not math.isfinite(milliseconds) or milliseconds < 0:
            raise ValueError("Invalid query timing")
        hits = row["hits"]
        ids = [hit["id"] for hit in hits]
        if len(set(ids)) != len(ids):
            raise ValueError("Duplicate result IDs")
        if any(key not in assets or (query["category"] != "duplicates" and assets[key]["split"] != query["split"]) for key in ids):
            raise ValueError("Result contains a private, unknown, or out-of-scope asset")
        if [hit["rank"] for hit in hits] != list(range(1, len(hits) + 1)):
            raise ValueError("Ranks are not in actual displayed order")
        relevant = set(query["relevant"])
        if any(key not in assets or assets[key]["split"] != query["split"] for key in relevant):
            raise ValueError("Invalid relevance labels")
        rank = next((i for i, key in enumerate(ids, 1) if key in relevant), None)
        grouped[(query["split"], query["category"])].append({
            "positive": bool(relevant), "hit1": rank == 1, "hit5": rank is not None and rank <= 5,
            "rr": 0 if rank is None else 1 / rank, "ms": milliseconds,
            "false_exact": any(hit.get("reason") in EXACT_REASONS for hit in hits),
            "any_result": bool(hits),
            "literal_enabled": "literal" in query["channels"],
        })
    if seen != set(queries):
        raise ValueError("Missing queries; incomplete runs must not become a final score")
    groups = []
    for (split, category), rows in sorted(grouped.items()):
        positive = [row for row in rows if row["positive"]]
        negative = [row for row in rows if not row["positive"]]
        negative_literal = [row for row in negative if row["literal_enabled"]]
        latency = sorted(row["ms"] for row in rows)
        groups.append({
            "split": split, "category": category, "queries": len(rows),
            "positive_queries": len(positive), "hit_at_1_count": sum(row["hit1"] for row in positive),
            "hit_at_5_count": sum(row["hit5"] for row in positive),
            "mrr": sum(row["rr"] for row in positive) / len(positive) if positive else None,
            "negative_queries": len(negative), "negative_with_literal_queries": len(negative_literal),
            "false_exact_count": sum(row["false_exact"] for row in negative_literal) if negative_literal else None,
            "negative_with_any_result_count": sum(row["any_result"] for row in negative),
            "latency_p50_ms": statistics.median(latency),
            "latency_p95_ms": latency[math.ceil(len(latency) * .95) - 1],
        })
    return {"schema": 1, "manifest_sha256": manifest_sha256, "metadata": metadata, "groups": groups,
            "limits": "No cosine/confidence threshold inferred. False-exact counts apply only where literal retrieval is enabled; visual-only negatives report any-result counts, not a vacuous zero exact-error rate. Latency measures production query API, not Compose hydration/rendering. Device run remains required."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("run", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raw = args.manifest.read_bytes()
    report = evaluate(json.loads(raw), json.loads(args.run.read_text()), hashlib.sha256(raw).hexdigest())
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
