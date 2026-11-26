package cn.edu.haut.campus.core.textextract.ocr

internal object CtcDecoder {
    private const val BLANK_INDEX = 0

    fun decode(output: FloatArray, shape: LongArray, characterList: List<String>): List<Pair<String, Float>> {
        require(shape.size == 3) { "CTC 输出必须是 batch/time/classes 三维" }
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()
        require(batchSize >= 0 && timeSteps >= 0 && numClasses > 0) { "CTC 输出尺寸非法" }
        require(output.size >= batchSize * timeSteps * numClasses) { "CTC 输出长度不足" }

        val results = mutableListOf<Pair<String, Float>>()
        for (batch in 0 until batchSize) {
            val baseOffset = batch * timeSteps * numClasses
            val indices = IntArray(timeSteps)
            val probabilities = FloatArray(timeSteps)
            for (time in 0 until timeSteps) {
                val offset = baseOffset + time * numClasses
                var maxIndex = 0
                var maxValue = output[offset]
                for (clazz in 1 until numClasses) {
                    val value = output[offset + clazz]
                    if (value > maxValue) {
                        maxValue = value
                        maxIndex = clazz
                    }
                }
                indices[time] = maxIndex
                probabilities[time] = maxValue
            }

            val keptProbabilities = mutableListOf<Float>()
            val text = StringBuilder()
            var previous = -1
            for (time in 0 until timeSteps) {
                val index = indices[time]
                if (index != BLANK_INDEX && index != previous) {
                    val characterIndex = index - 1
                    if (characterIndex >= 0 && characterIndex < characterList.size) {
                        text.append(characterList[characterIndex])
                        keptProbabilities.add(probabilities[time])
                    }
                }
                previous = index
            }
            val confidence = if (keptProbabilities.isEmpty()) 0f else keptProbabilities.average().toFloat()
            results.add(Pair(text.toString(), confidence))
        }
        return results
    }
}
