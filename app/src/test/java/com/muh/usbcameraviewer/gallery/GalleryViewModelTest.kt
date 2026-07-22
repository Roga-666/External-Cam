package com.muh.usbcameraviewer.gallery

import com.muh.usbcameraviewer.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeMediaRepository : MediaRepository {
    val items = mutableListOf<MediaItem>()
    var deleteResult = true
    val deleted = mutableListOf<MediaItem>()
    override suspend fun loadItems(): List<MediaItem> = items.toList()
    override suspend fun delete(item: MediaItem): Boolean {
        deleted += item
        if (deleteResult) items.remove(item)
        return deleteResult
    }
}

class GalleryViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val repo = FakeMediaRepository()
    private val photo = MediaItem("content://1", false, "UCV_20260722_120000.jpg", 100)
    private val video = MediaItem("content://2", true, "UCV_20260722_120100.mp4", 200)

    @Test
    fun `empty store shows Empty`() = runTest {
        val vm = GalleryViewModel(repo)
        assertEquals(GalleryUiState.Empty, vm.state.value)
    }

    @Test
    fun `items load newest first as given by repo`() = runTest {
        repo.items += listOf(video, photo)
        val vm = GalleryViewModel(repo)
        val content = vm.state.value as GalleryUiState.Content
        assertEquals(listOf(video, photo), content.items)
    }

    @Test
    fun `delete removes item and refreshes`() = runTest {
        repo.items += listOf(video, photo)
        val vm = GalleryViewModel(repo)
        vm.delete(video)
        assertEquals(listOf(video), repo.deleted)
        val content = vm.state.value as GalleryUiState.Content
        assertEquals(listOf(photo), content.items)
    }

    @Test
    fun `deleting last item transitions to Empty`() = runTest {
        repo.items += photo
        val vm = GalleryViewModel(repo)
        vm.delete(photo)
        assertTrue(vm.state.value is GalleryUiState.Empty)
    }
}
