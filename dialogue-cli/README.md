# Dialogue validation CLI

With JDK25, unpack the versioned release ZIP and run `bin/dialogue validate --root <project> --format json` (Windows: `bin\dialogue.bat`). It needs no server or Gradle. `--help` and `--version` are standalone commands.

The project uses `dialogues/**/*.json`, flat canonical language files `texts/en.json`, `texts/de.json`, and `context/host-catalog.json`. All definitions are checked, including unused ones. The CLI reads files only; it does not repair, activate or publish content. The existing shared catalogue loader is the validator. Start with `examples/headless-dialogue/src/main/resources` in this repository.

Exit0 means valid (warnings allowed), 1 invalid content, 2 usage/input/execution error. JSON report v1 is UTF-8, stable for the same snapshot/tool version, and contains the existing catalogue contentPin only on success. Problems retain common validator codes and relative file/JSON path/nodeId fields; optional fields are null. Human and AI authors can correct those problems in their files and validate again. Supply real host speakers/actions/conditions/capabilities, stable choice IDs, all translations and at least one unconditional answer. The CLI invents no providers.

Limits: 1MiB/definition, 4MiB/language, 1024 definitions,64 languages,16MiB overall,10,000 discovered entries and depth32. It rejects symlinks, special files and recognized concurrent file changes. Its bounded snapshot is not an atomic map-publication proof on a hostile filesystem.
