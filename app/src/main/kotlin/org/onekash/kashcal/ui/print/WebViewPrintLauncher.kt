package org.onekash.kashcal.ui.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Prints one HTML document through Android printing with an off-screen WebView.
 *
 * The WebView runs no JavaScript and loads nothing from the network, files or content
 * providers. It is held until the print system finishes with the document, then destroyed.
 * A WebView that can't be created, a print service that refuses the job, a renderer that dies
 * (reported to `WebViewClient.onRenderProcessGone`, which must return true or Android aborts the
 * whole process) and an exception from the WebView's print adapter all end as [Result.Failed]
 * instead of reaching the app or the print system. Call on the main thread, with an Activity
 * [context]: [PrintManager.print] needs one.
 *
 * @param startPrint hands the job to the print system; [PrintManager.print] unless a test swaps it.
 */
class WebViewPrintLauncher(
    private val context: Context,
    private val webViewFactory: (Context) -> WebView = { WebView(it) },
    private val startPrint: (jobName: String, adapter: PrintDocumentAdapter, attributes: PrintAttributes) -> Unit =
        { jobName, adapter, attributes ->
            (context.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(jobName, adapter, attributes)
        },
) {
    /** What became of the job: handed to the print system, or failed. */
    sealed interface Result {
        data object Started : Result
        data object Failed : Result
    }

    private var webView: WebView? = null
    private var onResult: ((Result) -> Unit)? = null
    private var started = false
    private var failed = false

    /**
     * Loads [html] and, once it has loaded, starts the print job [jobName]. Reports
     * [Result.Started] once when the job is handed over and [Result.Failed] once on any failure,
     * including a renderer death after hand-off.
     */
    fun print(html: String, jobName: String, onResult: (Result) -> Unit) {
        this.onResult = onResult
        val view = try {
            webViewFactory(context)
        } catch (e: Exception) {
            Log.w(TAG, "WebView unavailable: ${e.javaClass.simpleName}")
            fail()
            return
        }
        webView = view
        view.settings.apply {
            javaScriptEnabled = false
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            // The phone's font size must not resize the printout.
            textZoom = 100
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView, url: String?) {
                if (started || failed || webView !== page) return
                started = true
                try {
                    val adapter = GuardedAdapter(page.createPrintDocumentAdapter(jobName))
                    startPrint(jobName, adapter, ATTRIBUTES)
                    this@WebViewPrintLauncher.onResult?.invoke(Result.Started)
                } catch (e: Exception) {
                    Log.w(TAG, "Print job refused: ${e.javaClass.simpleName}")
                    release()
                    fail()
                }
            }

            override fun onRenderProcessGone(page: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === page) {
                    release()
                    fail()
                }
                return true
            }
        }
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /** Drops the job: destroys any WebView still held and reports nothing more. Safe to repeat. */
    fun cancel() {
        onResult = null
        release()
    }

    private fun fail() {
        if (failed) return
        failed = true
        onResult?.invoke(Result.Failed)
    }

    private fun release() {
        webView?.destroy()
        webView = null
    }

    /**
     * Passes the print system's calls to the WebView's adapter while the WebView is held, and
     * answers with failures once it is released (its renderer died, or [cancel] ran while the
     * print dialog was still open), since a destroyed WebView's adapter may hang or throw. An
     * exception from the adapter fails the print the same way. Its [onFinish] releases the
     * WebView.
     */
    private inner class GuardedAdapter(private val delegate: PrintDocumentAdapter) : PrintDocumentAdapter() {
        override fun onStart() {
            if (webView != null) guarded { delegate.onStart() }
        }

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (webView == null || !guarded { delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras) }) {
                callback.onLayoutFailed(null)
            }
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            if (webView == null || !guarded { delegate.onWrite(pages, destination, cancellationSignal, callback) }) {
                callback.onWriteFailed(null)
            }
        }

        override fun onFinish() {
            if (webView != null) guarded { delegate.onFinish() }
            release()
        }

        /**
         * Runs [call]; on an exception releases the WebView, fails the print and returns false.
         */
        private inline fun guarded(call: () -> Unit): Boolean = try {
            call()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Print adapter failed: ${e.javaClass.simpleName}")
            release()
            fail()
            false
        }
    }

    private companion object {
        const val TAG = "WebViewPrintLauncher"

        /** Prefers landscape and leaves the paper to the user and the printer. */
        val ATTRIBUTES: PrintAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE)
            .build()
    }
}
