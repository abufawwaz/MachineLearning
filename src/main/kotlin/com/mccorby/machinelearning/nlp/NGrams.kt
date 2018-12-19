package com.mccorby.machinelearning.nlp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

typealias LanguageModel = HashMap<String, MutableMap<Char, Int>>

const val START_CHAR = "~"

class NGrams(private val rankingModel: RankingModel) {

    suspend fun train(data: String, order: Int): LanguageModel {
        val result = coroutineScope {
            // Discount the added start token
            val total = (data.count() - order)
            (1..order).map { async(Dispatchers.IO) { trainForOrder(total, it, data) } }.awaitAll()
        }
        return LanguageModel().apply {
            result.map { putAll(it) }
        }
    }

    private fun trainForOrder(total: Int, ngram: Int, allData: String): LanguageModel {
        println("Training for order $ngram")
        val languageModel = LanguageModel()
        for (i in 0..total) {
            val lastIdx = min(i + ngram, allData.length - 1)
            val history = allData.slice(IntRange(i, lastIdx - 1))
            val aChar = allData[lastIdx]
            val entry = languageModel.getOrElse(history) { mutableMapOf(aChar to 0) }
            val count = entry.getOrDefault(aChar, 0)
            entry[aChar] = count.plus(1)

            languageModel[history] = entry
        }
        println("END Training for order $ngram")
        return languageModel
    }

    fun generateText(
        languageModel: LanguageModel,
        modelOrder: Int,
        nLetters: Int,
        seed: String = ""
    ): String {
        var history = START_CHAR.repeat(modelOrder)
        var out = ""
        for (i in IntRange(0, nLetters)) {
            val aChar = if (i < seed.length) {
                seed[i].toString()
            } else {
                var candidates = mutableMapOf<Char, Float>()
                var backoffOrder = modelOrder
                while (candidates.isEmpty() && backoffOrder > 0) {
                    candidates = rankingModel.rank(languageModel, history, modelOrder, backoffOrder--).toMutableMap()
                }
                if (candidates.isNotEmpty()) {
                    charRand(candidates).toString()
                } else {
                    ""
                }
            }
            history = history.drop(1).plus(aChar)
            out += aChar
        }
        return out
    }

}

interface DataPreprocessor {
    fun processData(order: Int, data: String): String {
        val pad = START_CHAR.repeat(order)
        return pad + data.toLowerCase()
    }
}