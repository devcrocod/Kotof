package api.keras

import api.KGraph
import api.TrainableTFModel
import api.TrainingHistory
import api.keras.dataset.ImageBatch
import api.keras.dataset.ImageDataset
import api.keras.layers.Dense
import api.keras.layers.Input
import api.keras.layers.Layer
import api.keras.loss.LossFunctions
import api.keras.metric.Metrics
import api.keras.optimizers.Optimizer
import api.keras.optimizers.SGD
import api.keras.shape.TensorShape
import api.keras.shape.convertTensorToFlattenFloatArray
import api.keras.shape.convertTensorToMultiDimArray
import api.keras.shape.tail
import ch.qos.logback.classic.Level
import mu.KotlinLogging
import org.tensorflow.*
import org.tensorflow.op.Ops
import org.tensorflow.op.core.Assign
import org.tensorflow.op.core.Variable
import org.tensorflow.op.nn.Softmax
import java.io.File

private const val TRAINING_LOSS = "training_loss"

private const val OUTPUT_NAME = "output"

private val logger = KotlinLogging.logger {}

/**
 * Sequential groups a linear stack of layers into a TFModel.
 * Also, it provides training and inference features on this model.
 *
 * @param T the type of data elements in Tensors.
 * @property [input] the input layer with initial shapes.
 * @property [layers] the layers to describe the model design.
 * @constructor Creates a Sequential group with [input] and [layers].
 */

class Sequential<T : Number>(input: Input<T>, vararg layers: Layer<T>) : TrainableTFModel<T>() {
    /** Input layer. */
    private val firstLayer: Input<T> = input

    /** The bunch of layers. */
    private val layers: List<Layer<T>> = listOf(*layers)

    /** The bunch of layers. */
    private var layersByName: Map<String, Layer<T>> = mapOf()

    /** A list of variables to train. */
    private var trainableVars: MutableList<Variable<T>> = mutableListOf()

    /** A list of initializer to initialize the trainableVariables. */
    private var initializers: Map<String, Assign<T>> = mapOf()

    /** Optimizer. Approach how aggressively to update the weights. */
    private var optimizer: Optimizer<T> = SGD(0.2f)

    /** Loss function. */
    private var loss: LossFunctions = LossFunctions.SOFT_MAX_CROSS_ENTROPY_WITH_LOGITS

    /** Metric on validation dataset for training phase. */
    private var metric: Metrics = Metrics.ACCURACY

    /** List of metrics for evaluation phase. */
    private var metrics: List<Metrics> = listOf(Metrics.ACCURACY)

    /** TensorFlow operand for prediction phase. */
    private lateinit var yPred: Operand<T>

    /** TensorFlow operand for X data. */
    private lateinit var xOp: Operand<T>

    /** TensorFlow operand for Y data. */
    private lateinit var yOp: Operand<T>

    /** Amount of classes for classification tasks. -1 is a default value for regression tasks. */
    private var amountOfClasses: Long = -1

    init {
        for (layer in layers) {
            if (layersByName.containsKey(layer.name)) {
                throw RepeatableLayerName(layer.name)
            } else {
                layersByName = layersByName + (layer.name to layer)
            }
        }

        kGraph = KGraph(Graph().toGraphDef())
        tf = Ops.create(kGraph.tfGraph)
        session = Session(kGraph.tfGraph)

        // TODO: think about different logic for different architectures and regression and unsupervised tasks
        if (layers.last() is Dense) {
            amountOfClasses = (layers.last() as Dense).outputSize.toLong()
        }
    }

    companion object {
        /**
         * Creates the [Sequential] model.
         *
         * @param [T] The type of data elements in Tensors.
         * @property [input] The input layer with initial shapes.
         * @property [layers] The layers to describe the model design.
         * @return the [Sequential] model.
         */
        fun <T : Number> of(input: Input<T>, vararg layers: Layer<T>): TrainableTFModel<T> {
            preProcessLayerNames(layers)
            val seqModel = Sequential(input, *layers)
            postProcessLayerNames(layers, seqModel)
            return seqModel
        }

        private fun <T : Number> preProcessLayerNames(layers: Array<out Layer<T>>) {
            var cnt = 1
            for (layer in layers) {
                if (layer.name.isEmpty()) {
                    layer.name = "layer_$cnt"
                    cnt++
                }


            }
        }

        private fun <T : Number> postProcessLayerNames(
            layers: Array<out Layer<T>>,
            seqModel: Sequential<T>
        ) {
            for (layer in layers) {
                layer.parentModel = seqModel
            }
        }
    }

    /**
     * Configures the model for training.
     *
     * @param [optimizer] Optimizer instance.
     * @param [loss] Loss function.
     * @param [metric] Metric to evaluate during training.
     */
    override fun compile(optimizer: Optimizer<T>, loss: LossFunctions, metric: Metrics) {
        this.loss = loss
        this.metrics = listOf(metric) // handle multiple metrics
        this.optimizer = optimizer

        firstLayer.defineVariables(tf)
        var inputShape: Shape = firstLayer.computeOutputShape()

        layers.forEach {
            it.defineVariables(tf, inputShape = inputShape)

            trainableVars.addAll(it.variables.values)  // TODO: keep here and variable names too (if it exist)
            initializers = initializers + it.initializers

            logger.debug { it.toString() + " " + TensorShape(inputShape).dims().contentToString() }

            inputShape = it.computeOutputShape(inputShape)

            logger.debug { it.toString() + " " + TensorShape(inputShape).dims().contentToString() }
        }
    }

    override fun fit(
        trainingDataset: ImageDataset,
        validationDataset: ImageDataset,
        epochs: Int,
        trainBatchSize: Int,
        validationBatchSize: Int,
        validationMetric: Metrics,
        verbose: Boolean
    ): TrainingHistory {
        val validationIsEnbaled = true

        val trainingHistory = TrainingHistory()

        this.isDebugMode = verbose
        if (!isDebugMode) {
            logger.level = Level.INFO
        }

        xOp = firstLayer.input
        yOp = tf.placeholder(getDType()) as Operand<T>

        yPred = transformInputWithNNModel(xOp)

        val (xBatchShape, yBatchShape) = calculateXYShapes(trainBatchSize)

        val lossOp = LossFunctions.convert<T>(loss).apply(tf, yPred, yOp, getDType())

        val prediction = tf.withName(OUTPUT_NAME).nn.softmax(yPred)

        val metricOp = Metrics.convert<T>(Metrics.ACCURACY).apply(tf, prediction, yOp, getDType())

        logger.debug { "Initialization of TensorFlow Graph variables" }

        initializeGraphVariables()

        val targets = optimizer.prepareTargets(kGraph, tf, lossOp, trainableVars)

        initializeOptimizerVariables()

        for (i in 1..epochs) {
            val batchIter: ImageDataset.ImageBatchIterator = trainingDataset.batchIterator(
                trainBatchSize
            )

            var batchCounter = 0
            var averageTrainingMetricAccum = 0.0f
            var averageTrainingLossAccum = 0.0f


            while (batchIter.hasNext()) {
                val batch: ImageBatch = batchIter.next()

                Tensor.create(
                    xBatchShape,
                    batch.images()
                ).use { batchImages ->
                    Tensor.create(yBatchShape, batch.labels()).use { batchLabels ->
                        val (lossValue, metricValue) = trainOnEpoch(targets, batchImages, batchLabels, metricOp)

                        averageTrainingLossAccum += lossValue
                        averageTrainingMetricAccum += metricValue
                        trainingHistory.append(i, batchCounter, lossValue.toDouble(), metricValue.toDouble())

                        //logger.debug { "epochs: $i lossValue: $lossValue metricValue: $metricValue" }
                    }
                }
                batchCounter++
            }

            val avgTrainingMetricValue = (averageTrainingMetricAccum / batchCounter)
            val avgLossValue = (averageTrainingLossAccum / batchCounter)

            if (validationIsEnbaled) {
                val validationMetricValue = evaluate(validationDataset, validationMetric, validationBatchSize)
                logger.debug { "epochs: $i avgLossValue: $avgLossValue avgTrainingMetricValue: $avgTrainingMetricValue validationMetricValue: $validationMetricValue" }
            } else {
                logger.debug { "epochs: $i avgLossValue: $avgLossValue avgTrainingMetricValue: $avgTrainingMetricValue" }
            }
        }

        return trainingHistory
    }

    /**
     * Trains the model for a fixed number of [epochs] (iterations on a dataset).
     *
     * @param [dataset] The train dataset that combines input data (X) and target data (Y).
     * @param [epochs] Number of epochs to train the model. An epoch is an iteration over the entire x and y data provided.
     * @param [batchSize] Number of samples per gradient update.
     * @param [verbose] Verbosity mode. False = silent, True = one line per batch and epoch.
     *
     * @return A [TrainingHistory] object. Its History.history attribute is a record of training loss values and metrics values per each batch and epoch.
     */
    override fun fit(
        dataset: ImageDataset,
        epochs: Int,
        batchSize: Int,
        verbose: Boolean
    ): TrainingHistory {
        val trainingHistory = TrainingHistory()

        this.isDebugMode = verbose
        if (!isDebugMode) {
            logger.level = Level.INFO
        }

        xOp = firstLayer.input
        yOp = tf.placeholder(getDType()) as Operand<T>

        yPred = transformInputWithNNModel(xOp)

        val (xBatchShape, yBatchShape) = calculateXYShapes(batchSize)

        val lossOp = LossFunctions.convert<T>(loss).apply(tf, yPred, yOp, getDType())

        val prediction = tf.withName(OUTPUT_NAME).nn.softmax(yPred)

        val metricOp = Metrics.convert<T>(Metrics.ACCURACY).apply(tf, prediction, yOp, getDType())

        logger.debug { "Initialization of TensorFlow Graph variables" }

        initializeGraphVariables()

        val targets = optimizer.prepareTargets(kGraph, tf, lossOp, trainableVars)

        initializeOptimizerVariables()


        for (i in 1..epochs) {
            val batchIter: ImageDataset.ImageBatchIterator = dataset.batchIterator(
                batchSize
            )

            var batchCounter = 0

            while (batchIter.hasNext()) {
                val batch: ImageBatch = batchIter.next()

                Tensor.create(
                    xBatchShape,
                    batch.images()
                ).use { batchImages ->
                    Tensor.create(yBatchShape, batch.labels()).use { batchLabels ->
                        val (lossValue, metricValue) = trainOnEpoch(targets, batchImages, batchLabels, metricOp)

                        trainingHistory.append(i, batchCounter, lossValue.toDouble(), metricValue.toDouble())

                        logger.debug { "epochs: $i lossValue: $lossValue metricValue: $metricValue" }
                    }
                }
                batchCounter++
            }
        }

        return trainingHistory
    }

    private fun initializeGraphVariables() {
        val runner = session.runner()

        initializers.forEach {
            runner.addTarget(it.value as Operand<T>)
        }

        runner.run()
    }

    private fun initializeOptimizerVariables() {
        if (kGraph.optimizerInitializers.isNotEmpty()) {

            // TODO: need to optimize the initialization to do it together (but some initializers depedends on another in Adam/Adamax optimizers
            kGraph.optimizerInitializers.forEach {
                val runner = session.runner()
                runner.addTarget(it as Operand<*>)
                runner.run()
            }

        }

        runAssignAddOpsForOptimizers()
    }

    private fun runAssignAddOpsForOptimizers() {
        if (kGraph.optimizerAssignAddInitializers.isNotEmpty()) {
            val runner = session.runner()

            kGraph.optimizerAssignAddInitializers.forEach {
                runner.addTarget(it as Operand<*>)
            }
            runner.run()
        }
    }


    /**
     * Returns the loss value and metric value on train batch.
     *
     * TODO: disable the metric value calculation to speed up the training (measure that)
     */
    private fun trainOnEpoch(
        targets: List<Operand<T>>,
        batchImages: Tensor<Float>,
        batchLabels: Tensor<Float>,
        metricOp: Operand<T>
    ): Pair<Float, Float> {
        val runner = session.runner()

        targets.forEach {
            runner.addTarget(it)
        }

        runner
            .feed(xOp.asOutput(), batchImages)
            .feed(yOp.asOutput(), batchLabels)

        runner
            .fetch(TRAINING_LOSS)
            .fetch(metricOp) // TODO: comment to measure

        try {
            val tensorList = runner.run()
            val lossValue = tensorList[0].floatValue()
            val metricValue = tensorList[1].floatValue()

            return Pair(lossValue, metricValue)
        } catch (e: TensorFlowException) {
            e.printStackTrace()
            throw RuntimeException(e.message)
        }

    }

    /**
     * Returns the loss value & metrics values for the model in test (evaluation) mode.
     *
     * @param [dataset] The train dataset that combines input data (X) and target data (Y).
     * @param [batchSize] Number of samples per batch of computation.
     * @param [metric] Metric to evaluate during test phase.
     *
     * @return Value of calculated metric.
     */
    override fun evaluate(
        dataset: ImageDataset,
        metric: Metrics,
        batchSize: Int
    ): Double {
        val prediction = tf.withName(OUTPUT_NAME).nn.softmax(yPred)

        val metricOp = Metrics.convert<T>(metric).apply(tf, prediction, yOp, getDType())

        val (imageShape, labelShape) = calculateXYShapes(batchSize)

        val batchIter: ImageDataset.ImageBatchIterator = dataset.batchIterator(
            batchSize
        )

        var averageMetricAccum = 0.0f
        var amountOfBatches = 0

        while (batchIter.hasNext()) {
            val batch: ImageBatch = batchIter.next()
            amountOfBatches++

            Tensor.create(
                imageShape,
                batch.images()
            ).use { testImages ->
                Tensor.create(labelShape, batch.labels()).use { testLabels ->
                    val metricValue = session.runner()
                        .fetch(metricOp)
                        .feed(xOp.asOutput(), testImages)
                        .feed(yOp.asOutput(), testLabels)
                        .run()[0]

                    // logger.debug { "test batch acc: ${metricValue.floatValue()}" }

                    averageMetricAccum += metricValue.floatValue()
                }
            }
        }
        return (averageMetricAccum / amountOfBatches).toDouble()
    }

    /**
     * Generates output predictions for the input samples.
     * Computation is done in batches.
     */
    override fun predictAll(dataset: ImageDataset, batchSize: Int): IntArray {
        assert(dataset.imagesSize() % batchSize == 0)

        val prediction = tf.withName(OUTPUT_NAME).nn.softmax(yPred)

        val imageShape = calculateXShape(batchSize)

        val predictions = IntArray(dataset.imagesSize()) { Int.MIN_VALUE }

        val batchIter: ImageDataset.ImageBatchIterator = dataset.batchIterator(
            batchSize
        )

        var amountOfBatches = 0

        while (batchIter.hasNext()) {
            val batch: ImageBatch = batchIter.next()
            amountOfBatches++

            Tensor.create(
                imageShape,
                batch.images()
            ).use { testImages ->
                val predictionsTensor = session.runner()
                    .fetch(prediction)
                    .feed(xOp.asOutput(), testImages)
                    .run()[0]

                val dst = Array(imageShape[0].toInt()) { FloatArray(amountOfClasses.toInt()) { 0.0f } }

                predictionsTensor.copyTo(dst)

                val argMaxBatchPrediction = IntArray(imageShape[0].toInt()) { 0 }

                dst.forEachIndexed { index, element ->
                    argMaxBatchPrediction[index] = element.indexOf(element.max()!!)
                }

                argMaxBatchPrediction.copyInto(predictions, batchSize * (amountOfBatches - 1))
            }
        }
        return predictions
    }

    /**
     * Predicts the unknown class for the given image.
     */
    override fun predict(image: FloatArray): Int {
        val softPrediction = predictSoftly(image)
        return softPrediction.indexOf(softPrediction.max()!!)
    }

    override fun predictAndGetActivations(image: FloatArray): Pair<Int, List<*>> {
        val (softPrediction, activations) = predictSoftlyAndGetActivations(image, true)
        return Pair(softPrediction.indexOf(softPrediction.max()!!), activations)
    }

    override fun predictSoftly(image: FloatArray): FloatArray {
        val (softPrediction, _) = predictSoftlyAndGetActivations(image, false)
        return softPrediction
    }

    /**
     * Predicts the probability distribution for all classes for the given image.
     */
    override fun predictSoftlyAndGetActivations(
        image: FloatArray,
        formActivationData: Boolean
    ): Pair<FloatArray, List<*>> {
        val predictionData: Array<FloatArray> = arrayOf(image)

        val prediction = tf.withName(OUTPUT_NAME).nn.softmax(yPred)

        val imageShape = calculateXShape(1)

        Tensor.create(
            imageShape,
            ImageDataset.serializeToBuffer(predictionData, 0, 1)
        ).use { testImages ->
            val tensors =
                formPredictionAndActivationsTensors(prediction, testImages, formActivationData)

            val predictionsTensor = tensors[0]

            val dst = Array(1) { FloatArray(amountOfClasses.toInt()) { 0.0f } }

            predictionsTensor.copyTo(dst)

            val activations = mutableListOf<Any>()
            if (formActivationData && tensors.size > 1) {
                for (i in 1 until tensors.size) {
                    activations.add(convertTensorToMultiDimArray(tensors[i]))
                }
            }
            return Pair(dst[0], activations.toList())
        }
    }

    private fun formPredictionAndActivationsTensors(
        prediction: Softmax<T>,
        testImages: Tensor<Float>,
        visualizationIsEnabled: Boolean
    ): List<Tensor<*>> {
        val runner = session
            .runner()
            .fetch(prediction)
            .feed(xOp.asOutput(), testImages)

        if (visualizationIsEnabled) {
            for (layer in layers) {
                if (layer.hasActivation()) runner.fetch("Activation_${layer.name}")
            }
        }

        return runner.run()
    }

    private fun calculateXYShapes(batchSize: Int): Pair<LongArray, LongArray> {
        val xBatchShape = calculateXShape(batchSize)

        val yBatchShape = longArrayOf(
            batchSize.toLong(),
            amountOfClasses
        )
        return Pair(xBatchShape, yBatchShape)
    }

    private fun transformInputWithNNModel(input: Operand<T>): Operand<T> {
        var out: Operand<T> = input
        for (layer in layers) {
            out = layer.transformInput(tf, out)
        }
        return out
    }

    private fun calculateXShape(batchSize: Int): LongArray {
        return calculateXShape(batchSize.toLong())
    }

    private fun calculateXShape(amountOfImages: Long): LongArray {
        val xTensorShape = firstLayer.input.asOutput().shape()

        return longArrayOf(
            amountOfImages,
            *tail(xTensorShape)
        )
    }

    override fun close() {
        session.close()
    }

    fun getGraph(): KGraph {
        return kGraph
    }

    // TODO: refactor to special module of extension functions or method of Writable/Readable interface
    override fun save(pathToModelDirectory: String) {
        val directory = File(pathToModelDirectory)
        if (!directory.exists()) {
            directory.mkdir()
        }
        File("$pathToModelDirectory/graph.pb").writeBytes(kGraph.tfGraph.toGraphDef())

        val modelWeightsExtractorRunner = session.runner()

        trainableVars.forEach {
            modelWeightsExtractorRunner.fetch(it)
        }

        val modelWeights = modelWeightsExtractorRunner.run()

        File("$pathToModelDirectory/variableNames.txt").bufferedWriter().use { variableNamesFile ->
            for (modelWeight in modelWeights.withIndex()) {
                val variableName = trainableVars[modelWeight.index].asOutput().op().name()
                variableNamesFile.write(variableName)
                variableNamesFile.newLine()

                File("$pathToModelDirectory/$variableName.txt").bufferedWriter().use { file ->
                    val tensorForCopying = modelWeight.value

                    val reshaped = convertTensorToFlattenFloatArray(tensorForCopying)

                    for (i in 0..reshaped.size - 2) {
                        file.write(reshaped[i].toString() + " ")
                    }

                    file.write(reshaped[reshaped.size - 1].toString())
                    file.flush()
                }
                variableNamesFile.flush()
            }
        }
    }

    infix fun getLayer(layerName: String): Layer<T> {
        return layersByName[layerName]!!
    }
}