from __future__ import annotations

import unittest

try:
    import numpy as np
except ModuleNotFoundError:  # Host policy tests run without the model toolchain environment.
    np = None

from nayti_model_tools.quantize_encoders import (
    cosine_distribution,
    mean_top_k_overlap,
    procedural_image,
    rowwise_symmetric_int8,
    semantic_retrieval_image,
)


@unittest.skipIf(np is None, "numpy is available in the pinned model-tools environment")
class QuantizeEncodersTest(unittest.TestCase):
    def test_procedural_images_are_deterministic_and_bounded(self) -> None:
        first = procedural_image(7, np)
        second = procedural_image(7, np)
        other = procedural_image(8, np)

        self.assertEqual((1, 3, 256, 256), first.shape)
        self.assertEqual(np.float32, first.dtype)
        self.assertTrue(np.array_equal(first, second))
        self.assertFalse(np.array_equal(first, other))
        self.assertGreaterEqual(float(first.min()), -1.0)
        self.assertLessEqual(float(first.max()), 1.0)

    def test_semantic_retrieval_fixture_has_stable_categories(self) -> None:
        first = semantic_retrieval_image(0, np)
        same_category = semantic_retrieval_image(9, np)
        other_category = semantic_retrieval_image(10, np)

        self.assertEqual((1, 3, 256, 256), first.shape)
        self.assertFalse(np.array_equal(first, same_category))
        self.assertFalse(np.array_equal(first, other_category))
        with self.assertRaises(ValueError):
            semantic_retrieval_image(100, np)

    def test_cosine_and_top_k_metrics_have_exact_interpretation(self) -> None:
        reference = np.eye(4, dtype=np.float32)
        candidate = reference.copy()
        cosine = cosine_distribution(reference, candidate, np)
        overlap = mean_top_k_overlap(
            reference,
            reference,
            candidate,
            candidate,
            np,
            k=2,
        )

        self.assertEqual(1.0, cosine["minimum"])
        self.assertEqual(1.0, cosine["median"])
        self.assertEqual(1.0, overlap["minimum"])
        self.assertEqual(1.0, overlap["mean"])

    def test_rowwise_symmetric_quantization_preserves_zero_and_row_scale(self) -> None:
        weights = np.asarray(
            [[-2.0, 0.0, 2.0], [0.0, 0.0, 0.0], [-0.25, 0.5, 1.0]],
            dtype=np.float32,
        )

        quantized, scales, maximum_error = rowwise_symmetric_int8(weights, np)
        reconstructed = quantized.astype(np.float32) * scales[:, None]

        self.assertEqual(np.int8, quantized.dtype)
        self.assertEqual(np.float32, scales.dtype)
        self.assertTrue(np.array_equal(quantized[1], np.zeros(3, dtype=np.int8)))
        self.assertLessEqual(maximum_error, float(scales.max()) / 2 + 1e-6)
        np.testing.assert_allclose(weights, reconstructed, atol=float(scales.max()) / 2 + 1e-6)

    def test_metric_shapes_and_invalid_k_are_rejected(self) -> None:
        matrix = np.eye(3, dtype=np.float32)
        with self.assertRaises(ValueError):
            cosine_distribution(matrix, matrix[:, :2], np)
        with self.assertRaises(ValueError):
            mean_top_k_overlap(matrix, matrix, matrix, matrix, np, k=4)


if __name__ == "__main__":
    unittest.main()
