import unittest

from nayti_model_tools.tokenizer_corpus import corpus_sha256, tokenizer_cases


class TokenizerCorpusTest(unittest.TestCase):
    def test_tokenizer_corpus_is_stable_and_complete(self) -> None:
        cases = tokenizer_cases()
        self.assertEqual(500, len(cases))
        self.assertEqual(500, len(set(cases)))
        self.assertEqual(
            "c503f734a4d81b6320b702c24777a94c4b8f61207d8f5582abc39c19301cecb2",
            corpus_sha256(cases),
        )

    def test_tokenizer_corpus_covers_required_classes(self) -> None:
        cases = tokenizer_cases()
        self.assertTrue(any("Найди" in case for case in cases))
        self.assertTrue(any("Find" in case for case in cases))
        self.assertTrue(any("Москва / Berlin" in case for case in cases))
        self.assertTrue(any("Договор №" in case for case in cases))
        self.assertTrue(any("\n" in case and len(case) > 400 for case in cases))
        self.assertTrue(any("😀" in case for case in cases))


if __name__ == "__main__":
    unittest.main()
