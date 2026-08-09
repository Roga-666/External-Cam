package com.muh.usbcameraviewer.gallery

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muh.usbcameraviewer.ui.theme.UcvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class ScreenFakeMediaRepository : MediaRepository {
    val items = mutableListOf<MediaItem>()
    val deleted = mutableListOf<MediaItem>()

    override suspend fun loadItems(): List<MediaItem> = items.toList()

    override suspend fun delete(item: MediaItem): Boolean {
        deleted += item
        return items.remove(item)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GalleryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val repo = ScreenFakeMediaRepository()
    private val photo = MediaItem("content://media/external/images/media/1", false, "UCV_20260722_120000.jpg", 100)
    private val video = MediaItem("content://media/external/video/media/2", true, "UCV_20260722_120100.mp4", 200)

    private fun setContent(onBack: () -> Unit = {}) {
        compose.setContent {
            UcvTheme { GalleryScreen(onBack = onBack, viewModel = GalleryViewModel(repo)) }
        }
    }

    private fun openViewerFor(item: MediaItem) {
        compose.onNodeWithContentDescription(item.displayName).performClick()
    }

    @Test
    fun emptyGallery_showsEmptyMessage() {
        setContent()
        compose
            .onNodeWithText("No captures yet. Photos and videos you take appear here.")
            .assertIsDisplayed()
    }

    @Test
    fun itemsRender_withVideoBadgeOnVideos() {
        repo.items += listOf(video, photo)
        setContent()
        compose.onNodeWithContentDescription(photo.displayName).assertIsDisplayed()
        compose.onNodeWithContentDescription(video.displayName).assertIsDisplayed()
        // Only the video tile carries the play badge.
        compose.onNodeWithContentDescription("Video").assertIsDisplayed()
    }

    @Test
    fun backButton_invokesCallback() {
        var backInvoked = false
        setContent(onBack = { backInvoked = true })
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backInvoked)
    }

    @Test
    fun deleteInViewer_showsConfirmDialog_withoutDeletingYet() {
        repo.items += listOf(video, photo)
        setContent()
        openViewerFor(photo)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete capture?").assertIsDisplayed()
        compose.onNodeWithText("${photo.displayName} will be removed permanently.").assertIsDisplayed()
        assertTrue(repo.deleted.isEmpty())
    }

    @Test
    fun confirmingDelete_deletesViaRepository() {
        repo.items += listOf(video, photo)
        setContent()
        openViewerFor(photo)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf(photo), repo.deleted)
        compose.onNodeWithText("Delete capture?").assertDoesNotExist()
    }

    @Test
    fun cancellingDelete_keepsItem() {
        repo.items += listOf(video, photo)
        setContent()
        openViewerFor(photo)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(repo.deleted.isEmpty())
        compose.onNodeWithText("Delete capture?").assertDoesNotExist()
    }

    @Test
    fun deletingLastItem_closesViewerAndShowsEmptyState() {
        repo.items += photo
        setContent()
        openViewerFor(photo)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose
            .onNodeWithText("No captures yet. Photos and videos you take appear here.")
            .assertIsDisplayed()
    }

    @Test
    fun shareInViewer_firesChooserWrappingSendIntent() {
        repo.items += photo
        setContent()
        openViewerFor(photo)
        compose.onNodeWithContentDescription("Share").performClick()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val chooser = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(Intent.ACTION_SEND, send?.action)
        assertEquals("image/jpeg", send?.type)
    }
}
