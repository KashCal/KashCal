package org.onekash.kashcal.ui.print

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests [WebViewPrintLauncher]: a locked-down WebView that prints once and can't take the app
 * down when WebView is missing or its renderer dies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WebViewPrintLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The WebView's own print adapter, recording what the wrapper passes on. */
    private class RecordingAdapter : PrintDocumentAdapter() {
        var layouts = 0
        var writes = 0
        var finished = 0
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?, extras: android.os.Bundle?,
        ) { layouts++ }
        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?,
        ) { writes++ }
        override fun onFinish() { finished++ }
    }

    private inner class TestWebView : WebView(context) {
        val adapter = RecordingAdapter()
        var destroyed = 0
        override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter = adapter
        override fun destroy() { destroyed++; super.destroy() }
    }

    private class Started(val jobName: String, val adapter: PrintDocumentAdapter, val attributes: PrintAttributes)

    private val results = mutableListOf<WebViewPrintLauncher.Result>()
    private val starts = mutableListOf<Started>()
    private var web: TestWebView? = null
    private var lastView: WebView? = null

    private fun launcher(factory: (Context) -> WebView = { TestWebView().also { web = it } }) = WebViewPrintLauncher(
        context = context,
        webViewFactory = factory,
        startPrint = { name, adapter, attributes -> starts += Started(name, adapter, attributes) },
    )

    private fun finishPage() = web!!.webViewClient.onPageFinished(web!!, "about:blank")

    @Test
    fun `the WebView is locked down and loads the document as data`() {
        launcher().print("<p>Week</p>", "KashCal – Week of Oct 25") { results += it }
        val settings = web!!.settings
        assertFalse(settings.javaScriptEnabled)
        assertTrue(settings.blockNetworkLoads)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        // The phone's font size must not resize the printout.
        assertEquals(100, settings.textZoom)
        val load = shadowOf(web!!).lastLoadDataWithBaseURL
        assertNull(load.baseUrl)
        assertEquals("<p>Week</p>", load.data)
        assertEquals("text/html", load.mimeType)
        assertEquals("utf-8", load.encoding)
    }

    @Test
    fun `a finished page starts one print job and reports Started once`() {
        launcher().print("<p/>", "KashCal – Week of Oct 25") { results += it }
        finishPage()
        finishPage()

        assertEquals(1, starts.size)
        assertEquals("KashCal – Week of Oct 25", starts.single().jobName)
        assertEquals(PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE, starts.single().attributes.mediaSize)
        assertEquals(listOf(WebViewPrintLauncher.Result.Started), results)
    }

    @Test
    fun `the WebView is kept until the print adapter finishes, then destroyed`() {
        launcher().print("<p/>", "Job") { results += it }
        finishPage()
        val wrapped = starts.single().adapter
        assertEquals(0, web!!.destroyed)

        wrapped.onLayout(null, null, null, mockk(relaxed = true), null)
        wrapped.onFinish()
        assertEquals(1, web!!.adapter.layouts)
        assertEquals(1, web!!.adapter.finished)
        assertEquals(1, web!!.destroyed)
    }

    @Test
    fun `a renderer death is handled, destroys the WebView and reports Failed once`() {
        val launcher = launcher()
        launcher.print("<p/>", "Job") { results += it }
        val detail: RenderProcessGoneDetail = mockk(relaxed = true)

        assertTrue(web!!.webViewClient.onRenderProcessGone(web!!, detail))
        assertTrue(web!!.webViewClient.onRenderProcessGone(web!!, detail))
        assertEquals(listOf(WebViewPrintLauncher.Result.Failed), results)
        assertEquals(1, web!!.destroyed)
        assertTrue(starts.isEmpty())
    }

    @Test
    fun `after a renderer death the print dialog gets failures, not the dead WebView`() {
        launcher().print("<p/>", "Job") { results += it }
        finishPage()
        val wrapped = starts.single().adapter
        web!!.webViewClient.onRenderProcessGone(web!!, mockk(relaxed = true))

        val layout: PrintDocumentAdapter.LayoutResultCallback = mockk(relaxed = true)
        val write: PrintDocumentAdapter.WriteResultCallback = mockk(relaxed = true)
        wrapped.onLayout(null, null, null, layout, null)
        wrapped.onWrite(arrayOf(PageRange.ALL_PAGES), null, null, write)

        verify { layout.onLayoutFailed(any()) }
        verify { write.onWriteFailed(any()) }
        assertEquals(0, web!!.adapter.layouts)
        assertEquals(0, web!!.adapter.writes)
        assertEquals(listOf(WebViewPrintLauncher.Result.Started, WebViewPrintLauncher.Result.Failed), results)
    }

    @Test
    fun `after a cancel the print dialog gets failures, not the destroyed WebView`() {
        val launcher = launcher()
        launcher.print("<p/>", "Job") { results += it }
        finishPage()
        val wrapped = starts.single().adapter
        launcher.cancel()

        val layout: PrintDocumentAdapter.LayoutResultCallback = mockk(relaxed = true)
        wrapped.onLayout(null, null, null, layout, null)
        wrapped.onFinish()
        verify { layout.onLayoutFailed(any()) }
        assertEquals(0, web!!.adapter.layouts)
        assertEquals(0, web!!.adapter.finished)
        assertEquals(1, web!!.destroyed)
    }

    @Test
    fun `an adapter that throws fails the print instead of the print system`() {
        val throwing = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?, extras: android.os.Bundle?,
            ) { throw IllegalStateException("renderer gone") }
            override fun onWrite(
                pages: Array<out PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?,
            ) { throw IllegalStateException("renderer gone") }
        }
        val launcher = WebViewPrintLauncher(
            context = context,
            webViewFactory = {
                object : WebView(context) {
                    override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter = throwing
                }.also { view -> web = null; lastView = view }
            },
            startPrint = { name, adapter, attributes -> starts += Started(name, adapter, attributes) },
        )
        launcher.print("<p/>", "Job") { results += it }
        lastView!!.webViewClient.onPageFinished(lastView!!, "about:blank")
        val wrapped = starts.single().adapter

        val layout: PrintDocumentAdapter.LayoutResultCallback = mockk(relaxed = true)
        val write: PrintDocumentAdapter.WriteResultCallback = mockk(relaxed = true)
        wrapped.onLayout(null, null, null, layout, null)
        wrapped.onWrite(arrayOf(PageRange.ALL_PAGES), null, null, write)
        verify { layout.onLayoutFailed(any()) }
        verify { write.onWriteFailed(any()) }
        assertEquals(listOf(WebViewPrintLauncher.Result.Started, WebViewPrintLauncher.Result.Failed), results)
    }

    @Test
    fun `a WebView that can't be created reports Failed without throwing`() {
        launcher(factory = { throw IllegalStateException("WebView is updating") })
            .print("<p/>", "Job") { results += it }
        assertEquals(listOf(WebViewPrintLauncher.Result.Failed), results)
        assertTrue(starts.isEmpty())
    }

    @Test
    fun `a print service that refuses the job reports Failed`() {
        val launcher = WebViewPrintLauncher(
            context = context,
            webViewFactory = { TestWebView().also { web = it } },
            startPrint = { _, _, _ -> throw IllegalStateException("no print service") },
        )
        launcher.print("<p/>", "Job") { results += it }
        finishPage()
        assertEquals(listOf(WebViewPrintLauncher.Result.Failed), results)
        assertEquals(1, web!!.destroyed)
    }

    @Test
    fun `cancel before the page finishes destroys the WebView and prints nothing`() {
        val launcher = launcher()
        launcher.print("<p/>", "Job") { results += it }
        val view = web!!
        launcher.cancel()
        view.webViewClient.onPageFinished(view, "about:blank")

        assertEquals(1, view.destroyed)
        assertTrue(starts.isEmpty())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `cancel is safe to repeat and after a result`() {
        val launcher = launcher()
        launcher.print("<p/>", "Job") { results += it }
        finishPage()
        starts.single().adapter.onFinish()
        launcher.cancel()
        launcher.cancel()
        assertEquals(1, web!!.destroyed)
        assertNotNull(results.singleOrNull())
    }
}
