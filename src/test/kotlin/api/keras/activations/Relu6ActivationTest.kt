package api.keras.activations

import org.junit.jupiter.api.Test

internal class Relu6ActivationTest : ActivationTest() {

    @Test
    fun apply() {
        val input = floatArrayOf(-100f, -10f, -1f, 0f, 1f, 10f, 100f)
        val actual = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val expected = floatArrayOf(
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            6.0f, 6.0f
        )

        assertActivationFunction(Relu6Activation(), input, actual, expected)
    }
}