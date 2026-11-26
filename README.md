# Text autoSize intrinsic height problem repro

Reproduction for an issue where the **intrinsic height of `BasicText` with `autoSize` does not take the auto-sized font size into account on the first measurement**.

## Problem

When a parent sizes itself using intrinsic measurements (e.g. `TabRow`, or `Modifier.height(IntrinsicSize.Max)`):

1. On the first measurement, the Text's intrinsic height is computed with the font size *before* autoSize is applied (`maxFontSize`), so the parent becomes too tall.
2. Once the Text has been laid out and is remeasured (with the same constraints), the intrinsic height is computed with the auto-sized font size, and the parent shrinks.

As a result, the parent's height depends on whether a remeasure happened, and the layout "jumps".

### Expected

The intrinsic height reflects the auto-sized font size from the first measurement, and the parent's height does not change on remeasure.

## Reproduction

[`TextAutoSizeIntrinsicHeightTest`](app/src/androidTest/kotlin/com/example/repro/TextAutoSizeIntrinsicHeightTest.kt) (instrumented test on an emulator):

- A `Box` with `width(100.dp).height(IntrinsicSize.Max)` contains a single-line `BasicText` with `TextAutoSize.StepBased(8.sp, 20.sp, 1.sp)`.
- The test reads the parent's height on the first frame, then forces a remeasure of the Text without changing constraints, and reads it again.

| Test | Result while the bug exists |
| --- | --- |
| `parentHeightShrinksAfterRemeasure` | passes (demonstrates the bug) |
| `parentHeightIsStableWithoutWorkaround` | **fails** (expected behavior) |
| `parentHeightIsCorrectFromFirstMeasureWithWorkaround` | passes (workaround) |
| `captureScreenshots` | saves `comparison.png` (top: first frame, bottom: after remeasure; left: without workaround, right: with workaround; gray = parent Box; red / blue line = top edge of each parent Box) |

Run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

GitHub Actions runs the same tests on an API 35 emulator and uploads the test report and screenshots as artifacts: see the [Reproduce workflow](../../actions/workflows/test.yml).

## Workaround

A `LayoutModifierNode` that overrides `minIntrinsicHeight` / `maxIntrinsicHeight` and returns the height measured with the font size that `TextAutoSize.StepBased` would pick. See `autoSizeIntrinsicHeight` in the test file.

## Versions

- Compose Foundation 1.13.0
- AGP 9.5.3 / Kotlin 2.4.21
- Emulator: API 35 (google_apis, x86_64)
