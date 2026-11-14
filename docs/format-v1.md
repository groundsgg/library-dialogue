# Dialogue format v1

The schemas are shipped in `dialogue-json/src/main/resources/schemas/`. The bilingual mechanic example is complete and executable. A visual editor and standalone validation CLI are later work; tools can already call `DialogueJsonCodec` or `DialogueCatalogLoader` directly.

## Definition

Root fields: `schemaVersion: 1`, `id`, `contentVersion`, `entry`, `locales`, `nodes`; optional `capabilities`, `interaction`, `presentation` use the builder defaults when omitted. Content IDs are bounded ASCII identifiers; story text is Unicode. Supported locale tags are normalized BCP 47, with a declared default.

| Node | Continuation |
|---|---|
| `message` | `speaker`, text key, optional gesture; exactly `next` or nonempty `choices` |
| `branch` | ordered `cases`, `defaultTo`, `unavailableTo` |
| `action` | action key, safe scalar arguments, `onSuccess`, `onRejected`, `onUnavailable`, `onUncertain` |
| `outcome` | milestone, `delivery: local/durable`, `onAcknowledged`, `onUnavailable`, `onUncertain` |
| `end` | neutral reason, such as `completed` |

Choice fields: `id`, `text`, `to`, optional `default`, optional condition. A condition is `{ "key": "demo:known", "arguments": {}, "negated": false }`. Unknown/unavailable is preserved under negation. Every choice list contains an unconditional choice. In a branch, an unavailable earlier case does not fall through to a later true case.

Translation files are JSON objects from keys to safe plain strings. Speaker labels optionally use `speaker.<speakerId>`; without that key the logical speaker ID is the safe label. Typed placeholders use `{name}`; `{{` and `}}` escape literal braces. Replacement values are inserted once as text and cannot execute chat/commands/markup. A custom `TextResolver` can reuse the host's i18n implementation.

The host catalogue declares generation, speakers, gestures, capabilities, condition/action argument schemas and durable-outcome support. Argument types are `text`, `number`, `boolean`, `null`, with required/nullable flags. Runtime provider availability remains a separate binding check.

## Validation and reload

Unknown fields/versions, duplicate JSON keys, trailing data, invalid UTF-8, BOMs, unpaired surrogates and nonfinite/unbounded numbers are rejected. Problems contain `code`, `severity`, `file`, `path`, optional `nodeId` and a message. Semantic validation additionally checks targets, supported-language text keys, host references, argument shapes, an end path and automatic-only cycles. A loop through a message remains allowed.

Defaults: definition 1 MiB; 1,024 nodes; 64 choices/node; 32 automatic transitions; JSON depth 32; ID 128 codepoints; text 16,384 codepoints; 64 locales; text file 4 MiB and text package 16 MiB; 1,024 provider arguments. A loaded catalogue has at most 1,024 definitions and 16 MiB aggregate source input. Java definitions use the same semantic limits and canonical byte budgets.

`prepareContext` shares an immutable text/host context between definitions. `DialogueCatalogLoader.reload` returns `ReloadResult(problems, candidate, activated)` and replaces its current catalogue only when the whole candidate is valid. Concurrent reloads cannot overwrite a newer valid catalogue with an older one. Already running sessions retain their `ValidatedDialogue` and `ContentPin`.

## Pins

Fingerprint v1: SHA-256 of the canonical envelope containing `fingerprintVersion: 1`, the normalized definition and `textsManifest`, which is the SHA-256 of the canonical locale/text map. The text manifest is cached per immutable catalogue. The host provider implementation and live entity handles are excluded; the validation catalogue/binding generation is separate.

Canonical data uses UTF-8, codepoint-ordered object keys, original array order, normalized finite decimal numbers and deterministic JSON escapes. It does not normalize Unicode NFC: distinct authored codepoint sequences remain distinct. Object whitespace/key order does not change a pin; choice order or translation content does. Full golden vectors are tested independently.
