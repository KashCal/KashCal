package org.onekash.kashcal.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.share.ShareCardStyle

class ShareCardViewModelTest {

    @Test
    fun `initial style is Regular before any event is loaded`() {
        val vm = ShareCardViewModel()
        assertEquals(ShareCardStyle.Standard, vm.selectedStyle.value)
    }

    @Test
    fun `loadEventTitle auto-picks Celebration for emoji title`() {
        val vm = ShareCardViewModel()
        vm.loadEventTitle("🎂 Maya turns 5")
        assertEquals(ShareCardStyle.Celebration, vm.selectedStyle.value)
    }

    @Test
    fun `loadEventTitle auto-picks Regular for plain title`() {
        val vm = ShareCardViewModel()
        vm.loadEventTitle("Brunch at Sam's")
        assertEquals(ShareCardStyle.Standard, vm.selectedStyle.value)
    }

    @Test
    fun `setStyle overrides the auto-picked value`() {
        val vm = ShareCardViewModel()
        vm.loadEventTitle("Brunch at Sam's") // → Regular
        vm.setStyle(ShareCardStyle.Celebration)
        assertEquals(ShareCardStyle.Celebration, vm.selectedStyle.value)
    }

    @Test
    fun `loadEventTitle is idempotent on repeat calls`() {
        val vm = ShareCardViewModel()
        vm.loadEventTitle("🎂 birthday")
        vm.loadEventTitle("🎂 birthday")
        assertEquals(ShareCardStyle.Celebration, vm.selectedStyle.value)
    }
}
