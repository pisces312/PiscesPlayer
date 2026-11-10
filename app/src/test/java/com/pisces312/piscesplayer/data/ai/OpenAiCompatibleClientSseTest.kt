/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: unit tests for the SSE handling of the OpenAI-compatible client.
 */
package com.pisces312.piscesplayer.data.ai

import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.ai.provider.AiErrorKind
import com.pisces312.piscesplayer.data.ai.provider.AiProviderException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiCompatibleClientSseTest {

    private val events = mutableListOf<AiProgressEvent>()

    private fun accumulator() = ChatStreamAccumulator(AiProgressListener { events += it })

    @Test
    fun `only data lines carry a payload`() {
        assertThat(parseSseLine("")).isEqualTo(SseLine.Ignore)
        assertThat(parseSseLine(": ping")).isEqualTo(SseLine.Ignore)
        assertThat(parseSseLine("event: message")).isEqualTo(SseLine.Ignore)
        assertThat(parseSseLine("id: 42")).isEqualTo(SseLine.Ignore)
        assertThat(parseSseLine("data: not-json")).isEqualTo(SseLine.Ignore)
        assertThat(parseSseLine("data: [DONE]")).isEqualTo(SseLine.Done)
        assertThat(parseSseLine("""data: {"a":1}""")).isInstanceOf(SseLine.Data::class.java)
    }

    @Test
    fun `thinking and answer deltas are accumulated separately`() {
        val accumulator = accumulator()
        accumulator.accept("""data: {"choices":[{"delta":{"reasoning_content":"想"}}]}""")
        accumulator.accept("")
        accumulator.accept(": keep-alive")
        accumulator.accept("""data: {"choices":[{"delta":{"reasoning_content":"了一下"}}]}""")
        accumulator.accept("""data: {"choices":[{"delta":{"content":"Title - Artist"}}]}""")
        accumulator.accept(
            """data: {"choices":[{"delta":{"content":"\nSong - Band"},"finish_reason":"stop"}]}"""
        )
        accumulator.accept("data: [DONE]")
        accumulator.accept("""data: {"choices":[{"delta":{"content":"late"}}]}""")

        assertThat(accumulator.isDone).isTrue()
        assertThat(events)
            .containsExactly(
                AiProgressEvent.Thinking("想", "想"),
                AiProgressEvent.Thinking("了一下", "想了一下"),
                AiProgressEvent.Answer("Title - Artist", "Title - Artist"),
                AiProgressEvent.Answer("\nSong - Band", "Title - Artist\nSong - Band")
            )
        assertThat(accumulator.toResult().content).isEqualTo("Title - Artist\nSong - Band")
    }

    @Test
    fun `reasoning is accepted as a fallback field name`() {
        val accumulator = accumulator()
        accumulator.accept("""data: {"choices":[{"delta":{"reasoning":"because"}}]}""")

        assertThat(events).containsExactly(AiProgressEvent.Thinking("because", "because"))
    }

    @Test
    fun `the trailing usage packet feeds the token counters`() {
        val accumulator = accumulator()
        accumulator.accept("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
        accumulator.accept(
            """data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"completion_tokens_details":{"reasoning_tokens":5}}}"""
        )
        accumulator.accept("data: [DONE]")

        val result = accumulator.toResult()
        assertThat(result.promptTokens).isEqualTo(12)
        assertThat(result.outputTokens).isEqualTo(34)
        assertThat(result.thoughtTokens).isEqualTo(5)
    }

    @Test
    fun `a stream without usage reports zero tokens`() {
        val accumulator = accumulator()
        accumulator.accept("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
        accumulator.accept("data: [DONE]")

        val result = accumulator.toResult()
        assertThat(result.promptTokens).isEqualTo(0)
        assertThat(result.outputTokens).isEqualTo(0)
        assertThat(result.thoughtTokens).isEqualTo(0)
    }

    @Test
    fun `a buffered non-streamed body is still parsed`() {
        val accumulator = accumulator()
        accumulator.acceptWholeBody(
            """
            {"choices":[{"message":{"role":"assistant",
             "reasoning_content":"想了","content":"A - B"}}],
             "usage":{"prompt_tokens":1,"completion_tokens":2,"reasoning_tokens":3}}
            """
                .trimIndent()
        )

        assertThat(accumulator.isDone).isTrue()
        assertThat(events)
            .containsExactly(
                AiProgressEvent.Thinking("想了", "想了"),
                AiProgressEvent.Answer("A - B", "A - B")
            )
        val result = accumulator.toResult()
        assertThat(result.content).isEqualTo("A - B")
        assertThat(result.thoughtTokens).isEqualTo(3)
    }

    @Test
    fun `an error packet fails the stream`() {
        val accumulator = accumulator()
        val error =
            assertThrows<AiProviderException> {
                accumulator.accept("""data: {"error":{"message":"quota exceeded"}}""")
            }

        assertThat(error.kind).isEqualTo(AiErrorKind.SERVER)
        assertThat(error).hasMessageThat().isEqualTo("quota exceeded")
    }

    @Test
    fun `thinking without an answer is a parse failure`() {
        val accumulator = accumulator()
        accumulator.accept("""data: {"choices":[{"delta":{"reasoning_content":"只思考"}}]}""")
        accumulator.accept("data: [DONE]")

        val error = assertThrows<AiProviderException> { accumulator.toResult() }

        assertThat(error.kind).isEqualTo(AiErrorKind.RESPONSE_PARSE)
    }

    @Test
    fun `an empty stream is a parse failure`() {
        val error = assertThrows<AiProviderException> { accumulator().toResult() }

        assertThat(error.kind).isEqualTo(AiErrorKind.RESPONSE_PARSE)
    }
}
