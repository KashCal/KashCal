package org.onekash.kashcal.sync.strategy

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections

/**
 * One calendar collection on a MockWebServer that keeps each resource's body
 * and etag and refuses a write whose If-Match or If-None-Match doesn't hold,
 * the way a real CalDAV server does. It also answers a pull: the collection's
 * ctag and sync-token, a sync-collection REPORT listing what changed since a
 * token, and a calendar-multiget.
 *
 * [refusePuts] makes the next writes fail with the given status codes, in
 * order, before any precondition is checked; [refuseGets] does the same for
 * resource GETs. [noEtagOnGet] answers GETs without an ETag header.
 */
class FakeDavCalendar(val collectionPath: String = "/cal/") : Dispatcher() {

    class Resource(var body: String, var etagNo: Int = 1, var changedAt: Int = 0) {
        val etag get() = "e$etagNo"
    }

    /** Bumped by every change; the ctag and sync-token are derived from it. */
    @Volatile var version = 0
        private set
    val ctag get() = "c$version"
    val syncToken get() = "t$version"

    private fun changed(resource: Resource) {
        version++
        resource.changedAt = version
    }

    val resources: MutableMap<String, Resource> = Collections.synchronizedMap(linkedMapOf())

    /** Every PUT: path, body sent, If-Match header. */
    val puts: MutableList<Triple<String, String, String?>> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val refusePuts: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val refuseGets: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    @Volatile var noEtagOnGet = false

    fun put(path: String, body: String) {
        resources[path] = Resource(body).also(::changed)
    }

    /** Another client changes [path] on the server. */
    fun editElsewhere(path: String, change: (String) -> String) {
        val resource = resources.getValue(path)
        resource.body = change(resource.body)
        resource.etagNo++
        changed(resource)
    }

    fun body(path: String) = resources.getValue(path).body
    fun etag(path: String) = resources.getValue(path).etag

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path!!
        requests += "${request.method} $path"
        val resource = resources[path]
        if (path == collectionPath) {
            return when (request.method) {
                "PROPFIND" -> multistatus(
                    response(path, "<cs:getctag>$ctag</cs:getctag><d:sync-token>$syncToken</d:sync-token><d:displayname>Cal</d:displayname>")
                )
                "REPORT" -> report(request.body.readUtf8())
                else -> MockResponse().setResponseCode(405)
            }
        }
        return when (request.method) {
            "PUT" -> put(path, resource, request)
            "GET" -> if (refuseGets.isNotEmpty()) MockResponse().setResponseCode(refuseGets.removeAt(0)) else resource?.let {
                MockResponse().setResponseCode(200).setBody(it.body)
                    .apply { if (!noEtagOnGet) setHeader("ETag", "\"${it.etag}\"") }
            } ?: MockResponse().setResponseCode(404)
            "PROPFIND" -> resource?.let {
                MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href>""" +
                        """<d:propstat><d:prop><d:getetag>"${it.etag}"</d:getetag></d:prop>""" +
                        """<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                )
            } ?: MockResponse().setResponseCode(404)
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun response(href: String, props: String) =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop>""" +
            """<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun multistatus(vararg responses: String, tail: String = "") = MockResponse().setResponseCode(207).setBody(
        """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" """ +
            """xmlns:cs="http://calendarserver.org/ns/">${responses.joinToString("")}$tail</d:multistatus>"""
    )

    private fun report(body: String): MockResponse = when {
        "sync-collection" in body -> {
            val since = Regex("""<d:sync-token>t(\d+)</d:sync-token>""").find(body)?.groupValues?.get(1)?.toInt() ?: 0
            val changed = resources.filter { it.value.changedAt > since }
                .map { (path, r) -> response(path, "<d:getetag>\"${r.etag}\"</d:getetag>") }
            multistatus(*changed.toTypedArray(), tail = "<d:sync-token>$syncToken</d:sync-token>")
        }
        "calendar-multiget" in body -> {
            val hrefs = Regex("""<d:href>(.*?)</d:href>""").findAll(body).map { it.groupValues[1] }.toList()
            val found = hrefs.mapNotNull { href ->
                resources[href]?.let {
                    response(href, "<d:getetag>\"${it.etag}\"</d:getetag><c:calendar-data>${escape(it.body)}</c:calendar-data>")
                }
            }
            multistatus(*found.toTypedArray())
        }
        else -> MockResponse().setResponseCode(501)
    }

    private fun put(path: String, resource: Resource?, request: RecordedRequest): MockResponse {
        val sent = request.body.readUtf8()
        val ifMatch = request.getHeader("If-Match")
        puts += Triple(path, sent, ifMatch)
        if (refusePuts.isNotEmpty()) return MockResponse().setResponseCode(refusePuts.removeAt(0))
        val ifNoneMatch = request.getHeader("If-None-Match")
        return when {
            ifNoneMatch == "*" && resource != null -> MockResponse().setResponseCode(412)
            ifNoneMatch == "*" -> {
                val created = Resource(sent)
                resources[path] = created
                changed(created)
                MockResponse().setResponseCode(201).setHeader("ETag", "\"${created.etag}\"")
            }
            resource == null && ifMatch != null -> MockResponse().setResponseCode(412)
            resource == null -> {
                val created = Resource(sent)
                resources[path] = created
                changed(created)
                MockResponse().setResponseCode(201).setHeader("ETag", "\"${created.etag}\"")
            }
            ifMatch != null && ifMatch != "\"${resource.etag}\"" -> MockResponse().setResponseCode(412)
            else -> {
                resource.body = sent
                resource.etagNo++
                changed(resource)
                MockResponse().setResponseCode(204).setHeader("ETag", "\"${resource.etag}\"")
            }
        }
    }
}
