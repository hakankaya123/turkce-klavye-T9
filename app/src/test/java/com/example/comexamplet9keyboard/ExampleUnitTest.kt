package com.example.comexamplet9keyboard

import org.junit.Test
import java.io.File
import java.net.URL

class ExampleUnitTest {
    @Test
    fun mergeDictionary() {
        val assetFile = File("src/main/assets/words_tr.txt")
        if (!assetFile.exists()) return

        val existingWords = mutableSetOf<String>()
        val lines = assetFile.readLines()
        val outputLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val word = trimmed.split(Regex("\\s+"))[0].lowercase()
                existingWords.add(word)
                outputLines.add(trimmed)
            }
        }

        try {
            val url = URL("https://raw.githubusercontent.com/CanNuhlar/Turkce-Kelime-Listesi/master/turkce_kelime_listesi.txt")
            val remoteText = url.readText()
            val tokens = remoteText.split(Regex("\\s+")).map { it.trim().lowercase() }.filter { it.length > 1 && it.all { c -> c.isLetter() } }
            
            var addedCount = 0
            for (token in tokens) {
                if (!existingWords.contains(token)) {
                    existingWords.add(token)
                    outputLines.add(token)
                    addedCount++
                }
            }
            println("Added $addedCount new words from CanNuhlar dictionary.")
            assetFile.writeText(outputLines.joinToString("\n"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}