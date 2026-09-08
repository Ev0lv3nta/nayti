# Synthetic HEIC fixture

`synthetic-document.heic` is a conversion of Nayti's own generated document recipe `document-00-0` from `evaluation/build_corpus.py`. It contains no real receipt, private photograph or personal identifier. The rendered image is dedicated under CC0-1.0. Golos Text is the existing OFL-licensed repository font; its license remains in the third-party notices.

Converted with macOS `sips -s format heic`. SHA-256: `fd247297084bb7a4e6bc307d17d43b01407a36457e392a7bce0b0059bc806be7`.

The Android test checks bounded decode. Platforms without a decoder report an explicit skipped HEIC capability case after a typed content rejection; that is not a support claim.
