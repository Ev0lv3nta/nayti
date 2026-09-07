import copy
import unittest

from score import evaluate


class ScoreTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"assets": [
            {"id": "a", "family": "a", "split": "development"},
            {"id": "b", "family": "b", "split": "development"},
            {"id": "c", "family": "c", "split": "holdout"},
        ], "queries": [
            {"id": "q1", "category": "visual", "split": "development", "relevant": ["b"]},
            {"id": "q2", "category": "negative_identifier", "split": "holdout", "relevant": []},
        ]}
        self.run = {"schema": 1, "manifest_sha256": "h", "execution": "production-mediastore-index-query",
                    "metadata": {"source_commit": "a" * 40, "pack_manifest_sha256": "b" * 64,
                                 "app_version": "0.1.0-alpha.2", "android_api": 35, "abi": "arm64-v8a",
                                 "page_size": 4096, "preprocessing": "test-contract"},
                    "results": [
                        {"query_id": "q1", "status": "complete", "latency_ms": 12,
                         "hits": [{"id": "a", "rank": 1, "reason": "VISUAL_CONTENT"},
                                  {"id": "b", "rank": 2, "reason": "VISUAL_CONTENT"}]},
                        {"query_id": "q2", "status": "complete", "latency_ms": 5,
                         "hits": [{"id": "c", "rank": 1, "reason": "EXACT_IDENTIFIER"}]},
                    ]}

    def test_counts_and_reciprocal_rank(self):
        groups = evaluate(self.manifest, self.run, "h")["groups"]
        self.assertEqual(groups[0]["positive_queries"], 1)
        self.assertEqual(groups[0]["hit_at_1_count"], 0)
        self.assertEqual(groups[0]["hit_at_5_count"], 1)
        self.assertEqual(groups[0]["mrr"], .5)
        self.assertEqual(groups[1]["false_exact_count"], 1)
        self.assertIsNone(groups[1]["mrr"])

    def test_neural_reason_is_not_a_confident_exact_match(self):
        self.run["results"][1]["hits"][0]["reason"] = "VISUAL_CONTENT"
        group = evaluate(self.manifest, self.run, "h")["groups"][1]
        self.assertEqual(group["false_exact_count"], 0)
        self.assertEqual(group["negative_with_any_result_count"], 1)

    def test_rejects_incomplete_duplicate_or_cancelled_runs(self):
        for change in (lambda r: r["results"].pop(),
                       lambda r: r["results"].append(r["results"][0]),
                       lambda r: r["results"][0].update(status="cancelled")):
            with self.subTest(change=change):
                run = copy.deepcopy(self.run)
                change(run)
                with self.assertRaises(ValueError):
                    evaluate(self.manifest, run, "h")

    def test_rejects_private_unknown_and_out_of_scope_ids(self):
        for value in ("private-file", "c"):
            self.run["results"][0]["hits"][0]["id"] = value
            with self.assertRaises(ValueError):
                evaluate(self.manifest, self.run, "h")

    def test_rejects_family_leakage(self):
        self.manifest["assets"][2]["family"] = "a"
        with self.assertRaises(ValueError):
            evaluate(self.manifest, self.run, "h")

    def test_rejects_wrong_corpus_fake_execution_and_private_metadata(self):
        for change in (lambda r: r.update(manifest_sha256="other"),
                       lambda r: r.update(execution="fake-embeddings"),
                       lambda r: r["metadata"].update(serial="private")):
            run = copy.deepcopy(self.run)
            change(run)
            with self.assertRaises(ValueError):
                evaluate(self.manifest, run, "h")


if __name__ == "__main__":
    unittest.main()
