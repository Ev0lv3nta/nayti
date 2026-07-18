# Third-party notices

Nayti packages open-source Android libraries and a reduced ONNX Runtime. The exact resolved component versions and SPDX license identifiers are emitted as a CycloneDX SBOM for every checked release build.

- AndroidX, Kotlin, Kotlin Coroutines, Dagger/Hilt, Tink, Gson, Guava annotations and the remaining Google/JetBrains runtime components are distributed under Apache License 2.0. The full text is in `apache-2.0/LICENSE`.
- Protocol Buffers runtime is distributed under BSD 3-Clause. The full upstream text is in `protobuf/LICENSE`.
- ONNX Runtime and ONNX Runtime Extensions are distributed under MIT. Their full texts are in `onnxruntime/LICENSE` and `onnxruntime-extensions/LICENSE`.
- The native SQLite code carried by AndroidX `sqlite-bundled` is dedicated to the public domain; the AndroidX wrapper remains covered by Apache License 2.0.

These notices describe third-party components only. Nayti source code is licensed separately under the repository's Apache License 2.0 file, and model packs carry their own SBOM, provenance and notices.
