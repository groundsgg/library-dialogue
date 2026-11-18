# Dialogue testkit

Use `gg.grounds:dialogue-testkit:<version>` in your test scope. It needs JDK25, Core and Presentation; it has no JUnit, game server or infrastructure runtime dependency.

`ManualDialogueScheduler` starts at zero. Enqueue work with the normal scheduler port, call `runUntilIdle()` for current work or `advanceBy(Duration)` to visit deadlines chronologically. Equal deadlines use one FIFO admission order. Submission is thread-safe; drain/advance has exactly one non-reentrant controller. Callbacks run outside the queue lock. Each controller call permits 10,000 callbacks; this cannot interrupt a blocking foreign callback. Actual concurrent races follow actual admission order.

`RecordingDialogueRenderer`, `RecordingDialogueSurface` and `RecordingOutcomeSink` implement the real public ports. Their immutable snapshots preserve session identity, preferences, views/frames and cleanup observations without exposing cancellation tokens. Closing a handle is idempotent; later writes fail and increment `rejectedWrites`. The cleanup stage is read-only. Control delayed actions/actors with ordinary JDK `CompletableFuture`s in your test. The Java/Kotlin consumer tests demonstrate the real runtime rather than a second graph simulator.
