package examples.keras.cifar10.util

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import examples.getImage
import tf_api.keras.dataset.ImageDataset
import java.awt.image.DataBufferByte
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

const val IMAGES_ARCHIVE = "cifar10/images.zip"
const val LABELS_ARCHIVE = "cifar10/trainLabels.csv"
const val DATASET_SIZE = 50000

@Throws(IOException::class)
fun extractCifar10Images(archiveName: String): Array<FloatArray> {
    val fullPathToImages = ImageDataset::class.java.classLoader.getResource(archiveName)?.path.toString()
    val zipFile = ZipFile(fullPathToImages)
    val entries = zipFile.entries()

    val numOfPixels: Int = 32 * 32 * 3

    val images = Array(DATASET_SIZE) { FloatArray(numOfPixels) }
    //val imageBuffer = ByteArray(numOfPixels)
    var cnt = 0

    while (entries.hasMoreElements()) {
        val entry = entries.nextElement() as ZipEntry
        val (imageByteArrays, image) = getImage(zipFile.getInputStream(entry))

        val pixels = (image.raster.dataBuffer as DataBufferByte).data

        images[cnt] =
            ImageDataset.toNormalizedVector(
                pixels
            )
        cnt++
    }

    zipFile.close()

    return images
}

@Throws(IOException::class)
fun extractCifar10Labels(pathToLabels: String, numClasses: Int): Array<FloatArray> {
    val realPathToLabels = ImageDataset::class.java.classLoader.getResource(pathToLabels)?.path.toString()

    val labelCount = DATASET_SIZE
    println(String.format("Extracting %d labels from %s", labelCount, realPathToLabels))
    val labelBuffer = ByteArray(labelCount)

    val dictionary = mapOf(
        "airplane" to 0, "automobile" to 1, "bird" to 2, "cat" to 3, "deer" to 4, "dog" to 5, "frog" to 6,
        "horse" to 7,
        "ship" to 8,
        "truck" to 9
    )

    var cnt = 0
    csvReader().open(realPathToLabels) {
        readAllAsSequence().forEach { row ->
            labelBuffer[cnt] = dictionary.getOrElse(row[1]) { 1 }.toByte()
            cnt++
        }
    }

    val floats =
        Array(labelCount) { FloatArray(numClasses) }

    for (i in 0 until labelCount) {
        floats[i] =
            ImageDataset.toOneHotVector(
                numClasses,
                labelBuffer[i]
            )
    }
    return floats
}