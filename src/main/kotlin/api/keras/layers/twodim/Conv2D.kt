package api.keras.layers.twodim

import api.keras.activations.Activations
import api.keras.initializers.Initializer
import api.keras.layers.Layer
import api.keras.shape.TensorShape
import api.keras.shape.convertTensorToMultiDimArray
import api.keras.shape.shapeFromDims
import org.tensorflow.Operand
import org.tensorflow.Shape
import org.tensorflow.op.Ops
import org.tensorflow.op.core.Variable
import kotlin.math.roundToInt


enum class ConvPadding {
    SAME,
    VALID
}

class Conv2D<T : Number>(
    private val filters: Long,
    private val kernelSize: LongArray,
    private val strides: LongArray,
    private val activation: Activations = Activations.Relu,
    private val kernelInitializer: Initializer<T>,
    private val biasInitializer: Initializer<T>,
    private val padding: ConvPadding,
    name: String = ""
) : Layer<T>() {

    // weight tensors
    private lateinit var kernel: Variable<T>
    private lateinit var bias: Variable<T>

    private val KERNEL = "conv2d_kernel"
    private val KERNEL_INIT = "conv2d_kernelInit"
    private val BIAS = "conv2d_bias"
    private val BIAS_INIT = "conv2d_biasInit"

    init {
        this.name = name
    }

    override fun defineVariables(tf: Ops, inputShape: Shape) {
        // Amount of channels should be the last value in the inputShape (make warning here)
        val lastElement = inputShape.size(inputShape.numDimensions() - 1)

        // Compute shapes of kernel and bias matrices
        val kernelShape = shapeFromDims(*kernelSize, lastElement, filters)
        val biasShape = Shape.make(filters)

        // TODO: refactor to logging
        println("kernelShape" + TensorShape(kernelShape).dims().contentToString())
        println("biasShape" + TensorShape(biasShape).dims().contentToString())

        // should be calculated before addWeight because it's used in calculation, need to rewrite addWEight to avoid strange behaviour
        // calculate fanIn, fanOut
        val inputDepth = lastElement // amount of channels
        val outputDepth = filters // amount of channels for the next layer

        fanIn = (inputDepth * kernelSize[0] * kernelSize[1]).toInt()
        fanOut = ((outputDepth * kernelSize[0] * kernelSize[1] / (strides[0].toDouble() * strides[1])).roundToInt())

        // TODO: refactor to remove duplicated code
        if (name.isNotEmpty()) {
            val kernelVariableName = name + "_" + KERNEL
            val biasVariableName = name + "_" + BIAS
            val kernelInitName = name + "_" + KERNEL_INIT
            val biasInitName = name + "_" + BIAS_INIT

            kernel = tf.withName(kernelVariableName).variable(kernelShape, getDType())
            bias = tf.withName(biasVariableName).variable(biasShape, getDType())

            kernel = addWeight(tf, kernelVariableName, kernel, kernelInitName, kernelInitializer)
            bias = addWeight(tf, biasVariableName, bias, biasInitName, biasInitializer)
        } else {
            kernel = tf.variable(kernelShape, getDType())
            bias = tf.variable(biasShape, getDType())
            kernel = addWeight(tf, KERNEL, kernel, KERNEL_INIT, kernelInitializer)
            bias = addWeight(tf, BIAS, bias, BIAS_INIT, biasInitializer)
        }
    }

    override fun computeOutputShape(inputShape: Shape): Shape {
        //TODO: outputShape calculation depending on padding type https://github.com/keras-team/keras/blob/master/keras/utils/conv_utils.py

        return Shape.make(inputShape.size(0), inputShape.size(1), inputShape.size(2), filters)
    }

    override fun transformInput(tf: Ops, input: Operand<T>): Operand<T> {
        val tfPadding = when (padding) {
            ConvPadding.SAME -> "SAME"
            ConvPadding.VALID -> "VALID"
        }

        val signal = tf.nn.biasAdd(tf.nn.conv2d(input, kernel, strides.toMutableList(), tfPadding), bias)
        return Activations.convert<T>(activation).apply(tf, signal, name)
    }

    override fun getWeights(): List<Array<*>> {
        val result = mutableListOf<Array<*>>()

        val session = parentModel.session

        val runner = session.runner()
            .fetch("${name}_$KERNEL")
            .fetch("${name}_$BIAS")

        val tensorList = runner.run()
        val filtersTensor = tensorList[0]
        val biasTensor = tensorList[1]

        val dstData =
            convertTensorToMultiDimArray(filtersTensor) // Array(1) { Array(28) { Array(28) { FloatArray(32) } } }
        result.add(dstData)

        val dstData2 =
            convertTensorToMultiDimArray(biasTensor) //Array(1) { Array(14) { Array(14) { FloatArray(64) } } }
        result.add(dstData2)

        return result.toList()
    }

    override fun hasActivation(): Boolean {
        return true
    }
}