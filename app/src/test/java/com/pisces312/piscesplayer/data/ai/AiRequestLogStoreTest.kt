/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work by pisces312: tests for the AI request log store.
 */
package com.pisces312.piscesplayer.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiRequestLogStoreTest {

    @Test
    fun `url without query is returned unchanged`() {
        assertThat(AiRequestLogStore.redactUrl("https://api.openai.com/v1/chat/completions"))
            .isEqualTo("https://api.openai.com/v1/chat/completions")
    }

    @Test
    fun `api key in query is redacted`() {
        assertThat(
                AiRequestLogStore.redactUrl(
                    "https://generativelanguage.googleapis.com/v1beta/models?key=SECRET"
                )
            )
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models?key=***")
    }

    @Test
    fun `only credential params are redacted`() {
        assertThat(
                AiRequestLogStore.redactUrl("https://host/v1?model=mimo&access_token=SECRET&stream=false")
            )
            .isEqualTo("https://host/v1?model=mimo&access_token=***&stream=false")
    }

    @Test
    fun `param without value is redacted`() {
        assertThat(AiRequestLogStore.redactUrl("https://host/v1?key"))
            .isEqualTo("https://host/v1?key=***")
    }
}
