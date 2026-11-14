# Host integration contracts

## Local ownership

`DialogueStart` binds audience ID, host/connection generation, content pin, binding generation, a stable request ID, locale, preferences and a host cancellation token. The token is useful before `Started`. Identical pending requests return the same future; conflicting data under an ID is rejected. The request register lasts for that runtime, defaults to 65,536 requests and rejects new IDs when full. Closed handles do not retain live renderer/actor/provider handles or full content catalogues. Configure capacity/lifetime for the workload; there is no implicit distributed deduplication or expiring-ID replay.

Normal game starts are local NPC/gameplay triggers. On disconnect, instance change or backend/proxy transfer, cancel the old session first. A new backend uses fresh local bindings and a valid local trigger. Live player/entity/renderer handles never transfer between processes. Resolve identity, language and authoritative game state through the host's network-wide services rather than assuming a player is on the current proxy.

## Scheduler and callbacks

Provide monotonic `nanoTime`, `execute` and cancellable scheduling. Keep it usable until shutdown cleanup settles. The library owns no threads. Port methods must return a stage promptly; the host runs blocking work on its own executor. A never-completing stage is bounded and does not block another session. A synchronously blocking method body cannot be preempted by the core.

No foreign callback is called under an internal state monitor. State changes and asynchronous responses are serialized per session. Session, node visit and operation generation protect results; changing the render revision alone must not discard an otherwise valid action result.

## Input and view identity

Input adapters assign monotonic impulse IDs and use the server-issued session/render revision. The opening impulse and reused/stale impulses do not advance. Confirm during reveal first reveals the full line; a new impulse is needed to advance. A default choice is not automatic. Renderer `RevealFinished` acknowledges output and never advances the graph.

`stateRevision` orders snapshots. `renderRevision` invalidates targets on node, locale, surface or answer-offer changes; reveal frames and purely visual selection do not invalidate targets. Confirm identifies the current selected choice. Before confirm, conditions are rechecked; changed offers are republished and that impulse does not choose a replacement.

Bindings represent Minecraft actions, not a guaranteed physical F/right/left key. The presenter has an action-label profile; the future platform adapter owns actual event binding, packet debouncing and geometric hover validation. The host must accept only targets actually issued by its current visible surface generation.

## Rendering and language

One core session owns reveal and active choice ID. A locale refresh uses the latest host revision, preserves node visit/choice, resolves a supported fallback and displays the complete current line. It does not rerun actions, outcomes or actor cues. Renderer changes likewise retain the visit and use new input targets.

Surface `open` prepares hidden resources; first `replace` activates output. Frames replace the whole view. A surface prevents late mutation after `closeAsync`. The presenter serializes same-surface frame writes and owns every prepared surface, including late ones. Layout uses full text, explicit availability and fixed hit rectangles. Visual scale is separate.

A semantic surface can render the original view without font measurement. For graphical overflow/missing glyphs, `FallbackRequired` carries a diagnostic. Hosts can preflight content or intercept that result and request their semantic/chat surface through the session. The library does not silently truncate or swap resource packs. Actual Minestom pack, inventory/HUD, focus and camera resources belong to package C and its host coordinator.

`ReconfigurationRejected` means the previous surface remains intact. A generic failure or timeout can mean a partially completed handoff; the session ends instead of claiming the old surface is active. A completed preference update already exposes the new snapshot/frame. Cancellation and cleanup bypass the bounded user-input queue (default 256 waiting inputs).

## Actions and outcomes

Conditions return TRUE/FALSE/UNAVAILABLE. Do not convert unknown to false, including under negation. Author catalogues validate names/arguments; action handlers still verify current authorization and prerequisites themselves.

For a durable action, persist the stable `ActionRef` and payload before sending a mutation. Deduplicate on that ID. Timeout ends the UI wait, not the external job: use UNCERTAIN if dispatch/result cannot be disproved. A late success after the uncertain branch or stop updates the host's job state and does not jump the old graph. `reconcile` queries that same reference and never performs a new execute. The core retains at most 128 unresolved action references/session; the host owns reconciliation and persistence.

Outcomes are explicit reached milestones, not render events. Event identity is per node visit; locale/render refresh does not create a visit. A new loop visit or conversation can create another event. The quest service decides whether it counts. Durable delivery requires declared catalogue support, a sink and the `outcome.durable` runtime capability. ACK follows the promised durable registration, not a best-effort send. Local delivery makes no durability promise.

No quest database, reward authority, automatic action retry or network-wide dialog-start service is implemented here.

## Ending and failures

Stop invalidates immediately and then closes only owned resources, in reverse acquisition order. Register acquisitions before invoking asynchronous prepare. Late resources remain assigned to cleanup. Multiple failures do not skip remaining closes. `closeAsync` is idempotent. On deadline/failure, completion is exceptional and cleanup is not claimed as confirmed; keep the cleanup owner reachable for late completion.

Default budgets: text/conditions 2 s, preparation/reconfiguration 5 s, action/outcome 10 s, cleanup 30 s. A host can change them through runtime options. Actor preparation is optional unless required; optional unavailable/slow actors do not reject the conversation. Existing NPC/Scene systems can implement `ActorPort` without entering the core dependency graph.
