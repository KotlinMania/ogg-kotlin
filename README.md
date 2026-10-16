# ogg-kotlin in Kotlin

[![GitHub link](https://img.shields.io/badge/GitHub-KotlinMania%2Fogg-kotlin-blue.svg)](https://github.com/KotlinMania/ogg-kotlin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/ogg-kotlin)](https://central.sonatype.com/artifact/io.github.kotlinmania/ogg-kotlin)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/ogg-kotlin/ci.yml?branch=main)](https://github.com/KotlinMania/ogg-kotlin/actions)

This is a Kotlin Multiplatform line-by-line transliteration port of [`RustAudio/ogg`](https://github.com/RustAudio/ogg) (crate `ogg`).

**Original Project:** This port is based on [`RustAudio/ogg`](https://github.com/RustAudio/ogg). All design credit and project intent belong to the upstream authors; this repository is a faithful port to Kotlin Multiplatform with no behavioural changes intended.

### Porting status

This is an **in-progress port**. The goal is feature parity with the upstream Rust crate while providing a native Kotlin Multiplatform API. Every Kotlin file carries a `// port-lint: source <path>` header naming its upstream Rust counterpart so the AST-distance tool can track provenance.

---

## Upstream README — `RustAudio/ogg`

> The text below is reproduced from [`https://github.com/RustAudio/ogg`](https://github.com/RustAudio/ogg). It is the upstream project's own description and remains under the upstream authors' authorship; links have been rewritten to absolute upstream URLs where appropriate so they continue to resolve from this repository.

## Ogg [![docs.rs documentation](https://img.shields.io/docsrs/ogg?label=docs.rs)](https://docs.rs/ogg/latest)

An Ogg decoder and encoder. Implements the [xiph.org Ogg spec](https://www.xiph.org/vorbis/doc/framing.html) in pure Rust.

If the `async` feature is disabled, Version 1.61 of Rust is the minimum supported one.

Note: `.ogg` files are vorbis encoded audio files embedded into an Ogg transport stream.
There is no extra support for vorbis codec decoding or encoding in this crate,
so you need additional functionality in order to decode them. For example, you can use the
[lewton](https://github.com/RustAudio/lewton) crate.

Also note that the encoder part of the Crate isn't as well tested as the decoder part,
in fact it was only written in order to write compact testing code for the decoder.

## License

Licensed under the three clause BSD license. For details, see the [LICENSE](LICENSE) file.

---

## About this Kotlin port

### Installation

```kotlin
dependencies {
    implementation("io.github.kotlinmania:ogg-kotlin:0.1.0")
}
```

### Building

```bash
./gradlew build
./gradlew test
```

### Targets

- macOS arm64
- Linux x64
- Windows mingw-x64
- iOS arm64 / simulator-arm64 (Swift export + XCFramework)
- JS (browser + Node.js)
- Wasm-JS (browser + Node.js)
- Android (API 24+)

### Porting guidelines

See [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) for translator discipline, port-lint header convention, and Rust → Kotlin idiom mapping.

### License

This Kotlin port is distributed under the same license terms as the upstream [`RustAudio/ogg`](https://github.com/RustAudio/ogg). See [LICENSE](LICENSE) (and any sibling `LICENSE-*` / `NOTICE` files mirrored from upstream) for the full text.

Original work copyrighted by the ogg authors.  
Kotlin port: Copyright (c) 2026 Sydney Renee and The Solace Project.

### Acknowledgments

Thanks to the [`RustAudio/ogg`](https://github.com/RustAudio/ogg) maintainers and contributors for the original Rust implementation. This port reproduces their work in Kotlin Multiplatform; bug reports about upstream design or behavior should go to the upstream repository.
