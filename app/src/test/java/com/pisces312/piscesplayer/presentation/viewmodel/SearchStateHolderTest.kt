package com.pisces312.piscesplayer.presentation.viewmodel

import com.pisces312.piscesplayer.data.model.SearchFilterType
import com.pisces312.piscesplayer.data.model.SearchResultItem
import com.pisces312.piscesplayer.data.repository.MusicRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Regression tests for the silent search failure caused by a second `PlayerViewModel`
 * instance tearing down this `@Singleton` holder's scope via `onCleared()`.
 *
 * Two test-infrastructure notes:
 * - The eager dispatcher matters: `initialize()` launches the collector that consumes
 *   `searchRequests`, and a request emitted before that collector subscribes is dropped
 *   (`replay = 0`).
 * - `advanceUntilIdle()` does **not** drive work launched in `backgroundScope`; only
 *   `advanceTimeBy(...)` + `runCurrent()` does, which is what the 300 ms debounce needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateHolderTest {

    private companion object {
        const val PAST_DEBOUNCE_MS = 500L
    }

    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val holder = SearchStateHolder(musicRepository)
    private val owner = Any()

    private fun stubRepository() {
        every { musicRepository.searchAll(any(), any()) } returns
            flowOf(emptyList<SearchResultItem>())
    }

    @Test
    fun `search request reaches the repository after initialize`() =
        runTest(UnconfinedTestDispatcher()) {
            stubRepository()
            holder.initialize(owner, backgroundScope)

            holder.performSearch("tone")
            advanceTimeBy(PAST_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 1) { musicRepository.searchAll("tone", SearchFilterType.ALL) }
        }

    @Test
    fun `onCleared from a non-owner keeps the holder usable`() =
        runTest(UnconfinedTestDispatcher()) {
            stubRepository()
            holder.initialize(owner, backgroundScope)

            // A second, never-owning ViewModel instance is destroyed — e.g. leaving a
            // screen that created its own PlayerViewModel via a default hiltViewModel().
            holder.onCleared(Any())

            holder.performSearch("tone")
            advanceTimeBy(PAST_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 1) { musicRepository.searchAll("tone", SearchFilterType.ALL) }
        }

    @Test
    fun `initialize from a non-owner cannot steal the scope`() =
        runTest(UnconfinedTestDispatcher()) {
            stubRepository()
            holder.initialize(owner, backgroundScope)

            val intruder = Any()
            holder.initialize(intruder, backgroundScope)
            // The intruder's teardown must not affect the real owner.
            holder.onCleared(intruder)

            holder.performSearch("tone")
            advanceTimeBy(PAST_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 1) { musicRepository.searchAll("tone", SearchFilterType.ALL) }
        }

    @Test
    fun `onCleared from the owner stops further searches`() =
        runTest(UnconfinedTestDispatcher()) {
            stubRepository()
            holder.initialize(owner, backgroundScope)

            holder.onCleared(owner)

            holder.performSearch("tone")
            advanceTimeBy(PAST_DEBOUNCE_MS)
            runCurrent()

            verify(exactly = 0) { musicRepository.searchAll(any(), any()) }
        }
}
