# Frame, row and block correlation

This is diagnostic instrumentation, not a claimed FPS improvement. It does not
change callback delivery, delta coalescing, stop/seal, usage accounting, ledger,
backup or restore. Android compilation and behavioral tests use the existing
GitHub Actions workflow, never local Gradle on the phone.

## Evidence

- `frameCorrelation` joins FrameMetrics anomalies to main-thread completed spans
  on half-open `[intendedVsyncNs, intendedVsyncNs + totalNs)` intervals.
  Union coverage is not a sum of nested inclusive times, CPU time or proof of causality.
- `spanOverlap` identifies parent/stage, duration, overlap and a self-time upper bound
  after unioning retained direct children. Missing children make this an upper bound.
- Render identity is `(StreamDiag id, listToken, rowToken, blockIndex)` with fixed
  row/block types and character counts. Message text, raw lazy keys, IDs and key
  hashes are never printed. Row tokens have their own 1024-entry session-local budget.
- `frameList`/`frameListRow` include message/row count, first index/offset, viewport
  and at most eight anonymous visible row geometries. Timestamp/age are printed.
  These are the latest observed post-layout geometries, not guaranteed exact-frame
  geometry. Future samples are never borrowed. The existing callback reads the
  geometry; there is no new frame loop or Compose state writer.
- `frameLookback` reports preceding main spans separately in a 200 ms lookback.
  Preceding work is never relabeled as a frame overlap.
- `frameMainMessage` prints numeric dispatch wall time, coverage and thread CPU
  delta retained at the anomaly callback. Wall-minus-CPU includes scheduler and
  other effects; it does not identify blocking, GC or binder by itself.
- Settings cards additionally use `settings.section.measure/draw` and 9 fixed
  section identities on the settings home page, supplementing root/DataStore timing
  without changing actions.

## Bounded retention

An independent 512-entry primitive slow ring supplements the ordinary 2048-entry
ring, preventing floods of short spans from overwriting all accepted slow evidence.
Severe anomalies (`total >= 33 ms` or `unknown >= 8 ms`) copy recent evidence on the
existing diagnostic worker before periodic output. Deadline-only anomalies keep
normal frame admission without all requesting extra evidence copies.

Locks copy columns/admit bounded references. Overlap search, record construction,
selection and formatting are outside the shared admission lock. At most 512
candidates are kept per severe frame and 2048 protected unique spans per window.
Output selects at most 512 spans, prioritizing actual frame matches. Each frame
prints at most 48 overlaps and eight preceding spans. Only one detached previous
window is retained for delayed callbacks; snapshots do not form a reference chain.
Final detach rejects later protection and releases the previous-window reference.

Review all loss counters: `ringOverwritten`, `slowBudgetDropped`, `frameBudgetDropped`,
`spanOutputTruncated`, `protectedBudgetDropped`, `frameCaptureTruncated`,
`rowTokenSaturated`, `listSampleOverwritten`, and `openSpansAtCutoff`.
`previousWindowSpans` marks older-window evidence. Zero loss counters do not prove
coverage of all Compose, GC, scheduler, native, RenderThread or GPU work.
`evidenceComplete=notClaimed` is unconditional; zero span matches are not proof of idle CPU.

Disabled modifiers return the original chain before node allocation. Enabled
observers have overhead: clocks/context, bounded counters, additional layout/draw
nodes and worker copies. Equal stage/render metadata reuse the node; changed
metadata keeps default invalidation. Constraints, size, relative placement, draw
count, lazy keys and markdown freeze policy remain unchanged by the observer.

## Reproduction and acceptance

1. Build/test the exact `main` SHA through GitHub Actions. Verify APK hash and original
   signing identity before installation. Compilation success is not behavioral acceptance.
2. Capture a completed StreamDiag session during the same long text, scrolling,
   expansion and Settings reproduction. Also use `eta-jank.perfetto` for bounded
   FrameTimeline, scheduler, binder, dalvik and main/RenderThread evidence.
3. The attached device was queried read-only: ftrace, SurfaceFlinger FrameTimeline,
   process/sys stats and the chosen atrace categories are registered. This is not
   proof of a completed capture or availability of every kernel event. Check trace
   errors/data-loss statistics and actual slices before using them as evidence.
4. Existing `ComposeSystemTrace.install()` at app startup, compiler trace markers
   and `<profileable android:shell="true"/>` supply the composition tracing path.
   Both file logging and platform tracing must be enabled for `Eta.compose:` slices.
   Row/block instrumentation alone is not an arbitrary Composable call-stack profiler.
5. Deduplicate frames by intended-vsync/total and spans by span/begin/stage. Keep
   the baseline >33 ms and blind-frame predicates. FrameMetrics count divided by
   elapsed time is not presented FPS; use presentation/FrameTimeline evidence.
6. Use repeated matched runs and a logging-off control. Do not assign causal blame
   solely from overlap or claim an FPS gain from this patch or one noisy A/B.

The profile is prepared configuration, not an already-recorded reproduction.
Heap sampling/dumps are not automatically enabled; use a separately justified
capture if scheduler/GC evidence indicates allocation pressure.
