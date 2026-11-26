package com.example.repro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Reproduces an issue where the intrinsic height of a Text with `autoSize` does not take
 * the auto-sized font size into account.
 *
 * On the first measurement, the intrinsic height is computed with the font size *before*
 * autoSize is applied (i.e. maxFontSize), so a parent that sizes itself by intrinsics
 * (e.g. TabRow, or Modifier.height(IntrinsicSize.Max)) becomes too tall.
 * Once the Text has been laid out and is remeasured, the intrinsic height is computed with
 * the auto-sized font size, and the parent shrinks.
 *
 * Expected: the parent's height is the same from the first measurement and does not change
 * on remeasure.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextAutoSizeIntrinsicHeightTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * Demonstrates the bug: the parent's height changes after a remeasure with the same constraints.
     * This test PASSES while the bug exists.
     */
    @Test
    fun parentHeightShrinksAfterRemeasure() {
        val heights = measureParentHeights()
        println(heights)

        assertThat(heights.withoutWorkaroundAfterRemeasure).isLessThan(heights.withoutWorkaroundInitial)
    }

    /**
     * Expected behavior: the parent's height should be stable from the first measurement.
     * This test FAILS while the bug exists.
     */
    @Test
    fun parentHeightIsStableWithoutWorkaround() {
        val heights = measureParentHeights()
        println(heights)

        assertThat(heights.withoutWorkaroundInitial).isEqualTo(heights.withoutWorkaroundAfterRemeasure)
    }

    /**
     * With a workaround that returns the intrinsic height using the auto-sized font size,
     * the parent's height is correct from the first measurement.
     */
    @Test
    fun parentHeightIsCorrectFromFirstMeasureWithWorkaround() {
        val heights = measureParentHeights()
        println(heights)

        // From the first measurement, it equals the height after remeasure without the workaround
        assertThat(heights.withWorkaroundInitial).isEqualTo(heights.withoutWorkaroundAfterRemeasure)
        // And it does not change on remeasure
        assertThat(heights.withWorkaroundAfterRemeasure).isEqualTo(heights.withoutWorkaroundAfterRemeasure)
    }

    private data class ParentHeights(
        val withoutWorkaroundInitial: Dp,
        val withoutWorkaroundAfterRemeasure: Dp,
        val withWorkaroundInitial: Dp,
        val withWorkaroundAfterRemeasure: Dp,
    )

    /**
     * Lays out parents with and without the workaround side by side, and returns their heights
     * on the first measurement and after a remeasure with unchanged constraints.
     */
    private fun measureParentHeights(): ParentHeights {
        var remeasureTrigger by mutableIntStateOf(0)
        rule.setContent {
            Column {
                listOf(false, true).forEach { useWorkaround ->
                    AutoSizeText(
                        useWorkaround = useWorkaround,
                        remeasureTrigger = { remeasureTrigger },
                        modifier = Modifier.testTag(parentTag(useWorkaround)),
                    )
                }
            }
        }
        fun height(useWorkaround: Boolean): Dp = rule.onNodeWithTag(parentTag(useWorkaround)).getUnclippedBoundsInRoot().height

        val withoutWorkaroundInitial = height(useWorkaround = false)
        val withWorkaroundInitial = height(useWorkaround = true)

        rule.runOnIdle { remeasureTrigger++ }

        return ParentHeights(
            withoutWorkaroundInitial = withoutWorkaroundInitial,
            withoutWorkaroundAfterRemeasure = height(useWorkaround = false),
            withWorkaroundInitial = withWorkaroundInitial,
            withWorkaroundAfterRemeasure = height(useWorkaround = true),
        )
    }

    private fun parentTag(useWorkaround: Boolean): String = "parent_$useWorkaround"
}

@Composable
private fun AutoSizeText(
    useWorkaround: Boolean,
    remeasureTrigger: () -> Int,
    modifier: Modifier = Modifier,
) {
    val text = "Hello World Hello"
    val style = TextStyle(fontSize = MAX_FONT_SIZE)
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = modifier
            .width(100.dp)
            .height(IntrinsicSize.Max),
    ) {
        BasicText(
            text = text,
            style = style,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = MIN_FONT_SIZE,
                maxFontSize = MAX_FONT_SIZE,
                stepSize = STEP_SIZE,
            ),
            modifier = Modifier
                .then(
                    if (useWorkaround) {
                        Modifier.autoSizeIntrinsicHeight(
                            text = text,
                            style = style,
                            textMeasurer = textMeasurer,
                            fontSize = { maxWidth ->
                                stepBasedFontSize(
                                    text = text,
                                    style = style,
                                    textMeasurer = textMeasurer,
                                    maxWidth = maxWidth,
                                )
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                // Trigger a remeasure only, without changing constraints
                .layout { measurable, constraints ->
                    remeasureTrigger()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                },
        )
    }
}

private val MIN_FONT_SIZE = 8.sp
private val MAX_FONT_SIZE = 20.sp
private val STEP_SIZE = 1.sp

/**
 * Same as [TextAutoSize.StepBased]: finds the largest font size that fits in a single line.
 */
private fun Density.stepBasedFontSize(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
    maxWidth: Int,
): TextUnit {
    var fontSizePx = MAX_FONT_SIZE.toPx()
    while (fontSizePx > MIN_FONT_SIZE.toPx()) {
        val fontSize = fontSizePx.toSp()
        val result = textMeasurer.measure(
            text = text,
            style = style.copy(fontSize = fontSize),
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidth),
            density = this,
        )
        if (!result.hasVisualOverflow) {
            return fontSize
        }
        fontSizePx -= STEP_SIZE.toPx()
    }
    return MIN_FONT_SIZE
}

/**
 * Workaround: returns the intrinsic height computed with the auto-sized font size.
 * [fontSize] must return the same value as the [TextAutoSize] passed to the Text.
 */
private fun Modifier.autoSizeIntrinsicHeight(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
    fontSize: Density.(maxWidth: Int) -> TextUnit,
): Modifier = this then AutoSizeIntrinsicHeightElement(
    text = text,
    style = style,
    textMeasurer = textMeasurer,
    fontSize = fontSize,
)

private data class AutoSizeIntrinsicHeightElement(
    val text: String,
    val style: TextStyle,
    val textMeasurer: TextMeasurer,
    val fontSize: Density.(maxWidth: Int) -> TextUnit,
) : ModifierNodeElement<AutoSizeIntrinsicHeightNode>() {
    override fun create(): AutoSizeIntrinsicHeightNode = AutoSizeIntrinsicHeightNode(element = this)

    override fun update(node: AutoSizeIntrinsicHeightNode) {
        node.element = this
        node.invalidateMeasurement()
    }
}

private class AutoSizeIntrinsicHeightNode(
    var element: AutoSizeIntrinsicHeightElement,
) : Modifier.Node(),
    LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = intrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = intrinsicHeight(width)

    private fun Density.intrinsicHeight(width: Int): Int = with(element) {
        textMeasurer.measure(
            text = text,
            style = style.copy(fontSize = fontSize(width)),
            maxLines = 1,
            constraints = Constraints(maxWidth = width),
            density = this@intrinsicHeight,
        ).size.height
    }
}
