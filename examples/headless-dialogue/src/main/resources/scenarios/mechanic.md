# Mechanic scenario

- Open the dialogue; four choices exist, Later is preselected.
- Switch en → de; preserve visit and selection.
- Select ask_lift and confirm.
- Confirm the explanation; emit one explicit local milestone.
- Confirm farewell; close all owned resources.

### Local author validation

Run `dialogue validate --root examples/headless-dialogue/src/main/resources --format json` with the standalone JDK25 CLI. It validates every definition against these real host/text catalogues. Correct the reported relative file/JSON path and validate again. Preserve stable choice IDs, complete EN/DE translations and an unconditional answer. A successful report neither installs quests nor activates map content.
