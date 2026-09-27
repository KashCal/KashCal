package org.onekash.kashcal.network

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

/**
 * Checks whether a PROPFIND or REPORT reply is a WebDAV multistatus.
 *
 * RFC 4918 §9.1 has the server answer with an XML body whose root is `{DAV:}multistatus`.
 * Anything else that arrives with a 2xx is not an answer about the collection: a hotspot's
 * login page, a single-sign-on proxy's form, a reply cut off half way. Read leniently, such a
 * body gives an empty listing, and an empty listing makes a sync delete local events, so the
 * body must be well-formed XML to the end with that root. A mislabelled Content-Type on a
 * valid multistatus is tolerated; the body decides.
 */
object DavMultistatus {

    private const val TAG = "DavMultistatus"
    private const val DAV_NS = "DAV:"

    /**
     * Holds what reading a body found.
     *
     * @property problem why the body is not a multistatus, or null when it is.
     * @property truncated the server cut the listing short: a 507 status inside it, or a
     *   `DAV:number-of-matches-within-limits` error (RFC 6578 §3.6, RFC 4791 §7.8). A
     *   truncated listing must not be treated as the whole collection.
     */
    data class Reading(val problem: String?, val truncated: Boolean)

    private val factory: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
    }

    /** Returns why [body] is not a WebDAV multistatus, or null when it is one. */
    fun check(body: String): String? = read(body).problem

    /**
     * Reads a 2xx reply to a PROPFIND or REPORT once: [code] must be 207 and [body] a
     * multistatus. A valid body under a Content-Type that isn't XML is accepted and logged,
     * since it points at a proxy or a misconfigured server.
     */
    fun readReply(code: Int, contentType: String?, body: String): Reading {
        if (code != 207) return Reading("status $code, not 207", truncated = false)
        val reading = read(body)
        if (reading.problem == null && contentType != null && !contentType.contains("xml", ignoreCase = true)) {
            Log.i(TAG, "multistatus served as $contentType")
        }
        return reading
    }

    /** Reads [body] as a multistatus without checking the status code, which [readReply] adds. */
    fun read(body: String): Reading {
        // A byte-order mark or whitespace before the XML declaration is harmless
        // and some servers send it; strict pull parsers reject a late declaration.
        val xml = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (xml.isEmpty()) return Reading("empty body", truncated = false)
        return try {
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var root: String? = null
            var truncated = false
            var inStatus = false
            var rootClosed = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (root == null) {
                            root = "{${parser.namespace}}${parser.name}"
                            if (parser.name != "multistatus" || parser.namespace != DAV_NS) {
                                return Reading("root element is $root, not {DAV:}multistatus", truncated = false)
                            }
                        }
                        if (parser.namespace == DAV_NS) {
                            when (parser.name) {
                                "status" -> inStatus = true
                                "number-of-matches-within-limits" -> truncated = true
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inStatus && STATUS_507.containsMatchIn(parser.text)) truncated = true
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "status") inStatus = false
                        if (parser.depth == 1) rootClosed = true
                    }
                }
                event = parser.next()
            }
            when {
                root == null -> Reading("no XML element", truncated = false)
                // Pull parsers can reach the end of input with elements still open
                // and not complain: a reply cut off between two tags.
                !rootClosed -> Reading("cut off before the end of the multistatus", truncated = false)
                else -> Reading(null, truncated)
            }
        } catch (e: XmlPullParserException) {
            Reading("unreadable XML (${e.javaClass.simpleName})", truncated = false)
        } catch (e: IOException) {
            Reading("unreadable XML (${e.javaClass.simpleName})", truncated = false)
        }
    }

    private val STATUS_507 = Regex("""HTTP/\S+\s+507\b""")
}
