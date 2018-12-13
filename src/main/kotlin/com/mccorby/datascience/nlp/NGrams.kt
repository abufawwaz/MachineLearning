package com.mccorby.datascience.nlp

import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.system.measureTimeMillis

//    val data =
//        "Poovalli Induchoodan  is sentenced for six years prison life for murdering his classmate. " +
//                "The nation of Panem consists of a wealthy Capitol and twelve poorer districts misona " +
//                "misono misona misosol mishako misha"


suspend fun main(args: Array<String>) {
    // TODO These values to be provided in the args
    val modelFile = "/tmp/titles_language_model"
    val data = object {}.javaClass.getResource("/titles.txt").readText()
    val order = 5

    val nGrams = NGrams()
    // Load or train the model
    val lm = loadModel(modelFile) ?: trainModel(nGrams, data, order, modelFile)

    // Inference
    val nLetters = 20
    print(nGrams.generateText(lm, order, nLetters, "star w".toLowerCase()))
}

private suspend fun trainModel(
    nGrams: NGrams,
    data: String,
    order: Int,
    modelFile: String
): LanguageModel {
    val lm = nGrams.train(data, order)
    ObjectOutputStream(FileOutputStream(modelFile)).use { it -> it.writeObject(lm) }
    return lm
}

private fun loadModel(file: String): LanguageModel? {
    ObjectInputStream(FileInputStream(file)).use { it ->

        return it.readObject() as LanguageModel
    }
}


// Taking from Yoav Goldberg blog post
// http://nbviewer.jupyter.org/gist/yoavg/d76121dfde2618422139
typealias LanguageModel = HashMap<String, MutableMap<Char, Float>>

class NGrams {

    suspend fun train(data: String, order: Int): LanguageModel {
        val result = coroutineScope {
            val allData = prepareData(order, data)

            val total = (allData.count() - order)
            val listOfAsyncs = mutableListOf<Deferred<LanguageModel>>()
            for (ngram in order downTo 1) {
                listOfAsyncs.add(async { trainForOrder(total, ngram, allData) })
            }
            listOfAsyncs.awaitAll()
        }
        val languageModel = LanguageModel()
        result.map { languageModel.putAll(it) }
        return languageModel
    }

    private fun prepareData(order: Int, data: String): String {
        val pad = "~".repeat(order)
        return pad + data.toLowerCase()
    }

    private fun trainForOrder(
        total: Int,
        ngram: Int,
        allData: String
    ): LanguageModel {
        println("Training for order $ngram")
        val languageModel = LanguageModel()
        for (i in 0..total) {
            val lastIdx = min(i + ngram, allData.length - 1)
            val history = allData.slice(IntRange(i, lastIdx - 1))
            val aChar = allData[lastIdx]
            val entry = languageModel.getOrElse(history) { mutableMapOf(aChar to 0f) }
            val count = entry.getOrDefault(aChar, 0f)
            entry[aChar] = count.plus(1)

            languageModel[history] = entry
        }
        println("END Training for order $ngram")
        return languageModel
    }

    private fun stupidBackoffRanking(
        languageModel: LanguageModel,
        history: String,
        order: Int,
        modelOrder: Int
    ): MutableMap<Char, Float> {
        val currentHistory = history.slice(IntRange(max(history.length - order, 0), history.length - 1))
        val candidates = mutableMapOf<Char, Float>()
        if (languageModel.containsKey(currentHistory)) {
            val distribution = languageModel[currentHistory]!!
            val lesserOrderHistory =
                history.slice(IntRange(max(history.length - (order - 1), 0), history.length - 1))
            val lesserOrderCount = languageModel[lesserOrderHistory]!!.values.sum().toInt()

            for ((aChar, count) in distribution) {
                val lambdaCorrection = 0.4.pow(modelOrder - order).toFloat()
                val orderCount = lambdaCorrection * count.div(lesserOrderCount)
                candidates[aChar] = orderCount
            }
        }
        return candidates
    }

    fun generateText(languageModel: LanguageModel, modelOrder: Int, nLetters: Int, seed: String = ""): String {
        var history = "~".repeat(modelOrder)
        var out = ""
        for (i in IntRange(0, nLetters)) {
            val aChar = if (i < seed.length) {
                seed[i].toString()
            } else {
                var candidates = mutableMapOf<Char, Float>()
                var backoffOrder = modelOrder
                while (candidates.isEmpty() && backoffOrder > 0) {
                    println("Doin backoff for modelOrder $backoffOrder")
                    candidates = stupidBackoffRanking(languageModel, history, backoffOrder--, modelOrder)
                    println(candidates)
                }
                if (candidates.isNotEmpty()) {
                    charmax(candidates).toString()
                } else {
                    ""
                }
            }
            history = history.slice(IntRange(history.length - modelOrder + 1, history.length - 1)) + aChar
            out += aChar
        }
        return out
    }

    /**
     * Selects the character with the best score
     */
    fun charmax(candidates: Map<Char, Float>): Char {
        return candidates.toList().sortedByDescending { (_, score) -> score }[0].first
    }
}
