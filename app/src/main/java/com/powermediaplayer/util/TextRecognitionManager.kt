package com.powermediaplayer.util

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Supported script languages for Text Recognition.
 */
enum class RecognitionScript {
    LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI
}

/**
 * A robust manager for Text Recognition using Google ML Kit v2.
 * It provides explicit character set support designed to correctly identify
 * typography formatting, unusual characters, and punctuation (like apostrophes)
 * globally across different scripts.
 */
class TextRecognitionManager {

    /**
     * Extracts text from a given Bitmap.
     *
     * @param bitmap The image to process.
     * @param script The language script to target. Defaults to LATIN.
     * @param rotationDegrees The rotation of the image, usually 0 if properly oriented.
     * @return Result mapped as a String containing formatted text, or failure.
     */
    suspend fun extractText(
        bitmap: Bitmap,
        script: RecognitionScript = RecognitionScript.LATIN,
        rotationDegrees: Int = 0
    ): Result<String> {
        val recognizer = getRecognizer(script)
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)

        return try {
            val result = recognizer.process(image).await()
            val text = extractFormattedText(result)
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                recognizer.close()
            } catch (ignored: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Manually extracts and assembles the text while trying to maintain structural formatting
     * (blocks, lines, and elements) effectively to resolve formatting drift issues and guarantee
     * line breaks are preserved exactly as seen on screen.
     */
    private fun extractFormattedText(result: Text): String {
        val builder = java.lang.StringBuilder()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                // By appending lines individually, we prevent the library from compressing
                // paragraphs and stripping intentional newline structuring.
                builder.append(line.text)
                builder.append("\n")
            }
            builder.append("\n") // Separate discrete visual text blocks by an extra newline
        }
        return builder.toString().trim()
    }

    /**
     * Retrieves the appropriate client config for the requested language script.
     * This implementation uses exact specific bundled recognizer objects which natively
     * have higher resilience for distinct alphabet subsets.
     */
    private fun getRecognizer(script: RecognitionScript): TextRecognizer {
        return when (script) {
            RecognitionScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            RecognitionScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            RecognitionScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            RecognitionScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            RecognitionScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }
}
