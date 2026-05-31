package com.locallink.pro.data.repository

import com.locallink.pro.service.llm.LlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    private val fakeEngine = object : LlmEngine {
        override fun ensureLoaded() {}
        override fun generateStream(
            prompt: String,
            image: android.graphics.Bitmap?,
            history: List<Pair<String, String>>,
        ): Flow<String> = flowOf("Hello", " world")

        override fun generateRaw(prompt: String, temperature: Float): Flow<String> =
            flowOf("Hello", " world")
    }

    @Test fun streamingAccumulatesIntoFullText() = runTest {
        val chunks = fakeEngine.generateRaw("hi").toList()
        assertEquals("Hello world", chunks.joinToString(""))
    }
}
