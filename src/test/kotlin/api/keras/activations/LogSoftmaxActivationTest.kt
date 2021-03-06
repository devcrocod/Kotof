package api.keras.activations

import org.junit.jupiter.api.Test

internal class LogSoftmaxActivationTest : ActivationTest() {

    @Test
    fun apply() {
        val input = floatArrayOf(-100f, -10f, -1f, 0f, 1f, 10f, 100f)
        val actual = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val expected = floatArrayOf(
            -200.0f, -110.0f, -101.0f, -100.0f, -99.0f,
            -90.0f, 0.0f
        )

        assertActivationFunction(LogSoftmaxActivation(), input, actual, expected)
    }
}