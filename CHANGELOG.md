# Changelog

## [1.1.2](https://github.com/groundsgg/library-dialogue/compare/v1.1.1...v1.1.2) (2026-11-20)


### Bug Fixes

* **assets:** prevent bitmap glyphs from bleeding into adjacent tiles ([#12](https://github.com/groundsgg/library-dialogue/issues/12)) ([f9fb73d](https://github.com/groundsgg/library-dialogue/commit/f9fb73ddc36025c70196b0b75dc342795397d198))

## [1.1.1](https://github.com/groundsgg/library-dialogue/compare/v1.1.0...v1.1.1) (2026-11-20)


### Bug Fixes

* **minestom:** allow NPC panels to clear taller actors ([#10](https://github.com/groundsgg/library-dialogue/issues/10)) ([c2ba928](https://github.com/groundsgg/library-dialogue/commit/c2ba928cade2e206bf0fe335d43ea337172319da))

## [1.1.0](https://github.com/groundsgg/library-dialogue/compare/v1.0.0...v1.1.0) (2026-11-20)


### Features

* **minestom:** support confirmed proxy packs and NPC themes ([#8](https://github.com/groundsgg/library-dialogue/issues/8)) ([cfdc340](https://github.com/groundsgg/library-dialogue/commit/cfdc3402296d37c7824d05f8e4cff7070c9903ff))

## [1.0.0](https://github.com/groundsgg/library-dialogue/compare/v0.1.0...v1.0.0) (2026-11-20)


### ⚠ BREAKING CHANGES

* value types that defensively copy or normalize constructor inputs no longer expose JVM record metadata.

### Code Refactoring

* migrate dialogue library to Kotlin ([#6](https://github.com/groundsgg/library-dialogue/issues/6)) ([04c380e](https://github.com/groundsgg/library-dialogue/commit/04c380e98a10e021b78f3be7eea265ea6dcc339e))

## 0.1.0 (2026-11-18)


### Features

* add connection scoped Minestom dialogue adapter ([#3](https://github.com/groundsgg/library-dialogue/issues/3)) ([e299b88](https://github.com/groundsgg/library-dialogue/commit/e299b88d56df3e745ac45b319bdc88199d1ddaee))
* add dialogue core and shared presentation ([a428e15](https://github.com/groundsgg/library-dialogue/commit/a428e15ad178d05ae9c3b101cf60cd1a4e818453))
* distribute dialogue CLI and public testkit ([#5](https://github.com/groundsgg/library-dialogue/issues/5)) ([2b62ce8](https://github.com/groundsgg/library-dialogue/commit/2b62ce812dedb54ad66f3acca21cfe631ec0eba6))

## Changelog

Release Please maintains this file. The first release contains the headless core, JSON authoring and shared presentation contracts. Actual Minestom surfaces follow separately.
