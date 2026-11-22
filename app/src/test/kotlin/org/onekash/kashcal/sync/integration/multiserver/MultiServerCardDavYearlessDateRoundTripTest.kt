package org.onekash.kashcal.sync.integration.multiserver

import android.provider.ContactsContract.CommonDataKinds.Event
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEventDate
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.contacts.VCardContactMapper
import org.onekash.kashcal.sync.carddav.CardDavClient
import org.onekash.kashcal.sync.carddav.CardDavContactReader
import org.onekash.kashcal.sync.carddav.contactResourceName
import org.onekash.kashcal.sync.carddav.model.CardDavAddressBook
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.contacts.ContactPushStrategy
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
import org.onekash.kashcal.sync.contacts.LocalContactChanges
import org.onekash.kashcal.sync.contacts.LocalContactEdit
import org.onekash.vcard.model.Contact
import org.onekash.vcard.model.ContactDate
import org.onekash.vcard.model.StructuredName
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale
import java.util.UUID

/**
 * Pushes a birthday or anniversary without a year through the real [ContactPushStrategy.push]
 * create path, live on every configured CardDAV server including the hosted accounts, and reads
 * it back the way a pull does.
 *
 * A date without a year is a phone `--MM-DD` value or a date in 1604 (`OMIT_YEAR` in
 * vcard-core's YearlessDate.kt). The hard check, on every server that accepts the write, is
 * the read-back: no year on the parsed date, and an Event START_DATE the birthday calendar reads
 * as month and day only. Dated controls keep their year. On a vCard 3.0 book the served line is
 * also expected to hold the 1604 form with `X-APPLE-OMIT-YEAR=1604`; a server that reshapes that
 * line while the read-back still has no year is printed as `SERVED-LINE` and not failed, since
 * the reader's bare-1604 rule still covers it. Zoho is checked against its documented server
 * behavior ([CardDavServerConfig.shiftsEarlyYearDates],
 * [CardDavServerConfig.dropsAppleAnniversary]).
 *
 * Unlike [MultiServerContactPushReconcileTest], which runs only [CardDavServerConfig.allServers],
 * this covers [CardDavServerConfig.allDiscoveryProbeServers], so Zoho and Fastmail are pushed to
 * as well. Each run uses fresh UIDs: a server whose DELETE leaves the old href readable could
 * otherwise turn the next create into a 412 adopt of a previous run's body, which would let a
 * writer regression pass. A finally deletes each created resource by its own URL when the server
 * lists it; anything left (a crashed run, a listing that lags) carries the UID prefix and is
 * deleted before the next run's push. A refused write is a
 * per-(server, case) skip.
 *
 * All data is synthetic (`@example.test`); messages carry only the server name, the case label,
 * the book version and the matched date line, never an href or a body.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCardDavYearlessDateRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCardDavYearlessDateRoundTripTest(
    private val config: CardDavServerConfig,
    private val case: DateCase,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} · {1}")
        fun cases(): List<Array<Any>> =
            CardDavServerConfig.allDiscoveryProbeServers().flatMap { server ->
                CASES.map { arrayOf<Any>(server, it) }
            }

        private const val ACCOUNT = "yearless@example.test"
        private const val UID_PREFIX = "kashcal-yearless-"
        private const val LOCAL_ID = 710L

        /** One suffix per JVM run, so no UID is reused across runs. */
        private val RUN = UUID.randomUUID().toString().take(8)

        /**
         * One date on one field. [expected] is what the read-back must hold; for a date without a
         * year it is `--MM-DD` text, and [served] is the 1604 value a 3.0 book should store.
         */
        class DateCase(
            private val label: String,
            val slug: String,
            val anniversary: Boolean,
            val input: ContactDate,
            val expected: ContactDate,
            val served: String?,
        ) {
            override fun toString(): String = label
        }

        private val CASES = listOf(
            DateCase("BDAY-yearless", "bday-yearless", false,
                ContactDate(text = "--03-15"), ContactDate(text = "--03-15"), "1604-03-15"),
            DateCase("BDAY-1604", "bday-1604", false,
                ContactDate(date = LocalDate.of(1604, 3, 15)), ContactDate(text = "--03-15"), "1604-03-15"),
            DateCase("ANNIVERSARY-yearless", "anniv-yearless", true,
                ContactDate(text = "--09-22"), ContactDate(text = "--09-22"), "1604-09-22"),
            DateCase("ANNIVERSARY-1604", "anniv-1604", true,
                ContactDate(date = LocalDate.of(1604, 9, 22)), ContactDate(text = "--09-22"), "1604-09-22"),
            DateCase("BDAY-dated", "bday-dated", false,
                ContactDate(date = LocalDate.of(1985, 3, 14)), ContactDate(date = LocalDate.of(1985, 3, 14)), null),
            DateCase("ANNIVERSARY-dated", "anniv-dated", true,
                ContactDate(date = LocalDate.of(2010, 9, 22)), ContactDate(date = LocalDate.of(2010, 9, 22)), null),
        )
    }

    private var client: CardDavClient? = null
    private var creds: ServerCredentials? = null
    private lateinit var reader: CardDavContactReader
    private lateinit var provider: FakeContactsProviderRepository
    private lateinit var strategy: ContactPushStrategy

    @Before
    fun setup() {
        CardDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
            reader = CardDavContactReader(it.first)
        }
        provider = FakeContactsProviderRepository()
        strategy = ContactPushStrategy(provider)
    }

    @Test
    fun `date without a year survives the push and reads back without a year`() = runBlocking {
        skipUnless(client != null, "no credentials in local.properties")
        skipUnless(CardDavTestServerLoader.isServerReachable(creds!!.davEndpoint), "server unreachable")
        val c = client!!
        val book = resolveWritableBook(c, creds!!)
        skipUnless(book != null, "no writable address book to push into")
        book!!

        sweepLeftovers(c, book.url)

        val uid = "$UID_PREFIX${case.slug}-$RUN"
        val resourceUrl = book.url.trimEnd('/') + "/" + contactResourceName(uid)
        val contact = Contact(
            version = book.vcardVersion,
            uid = uid,
            structuredName = StructuredName(family = "Yearless", given = "Kashcal"),
            displayName = "Kashcal Yearless",
            birthday = case.input.takeUnless { case.anniversary },
            anniversary = case.input.takeIf { case.anniversary },
            rawVCard = "",
        )
        provider.pendingChanges = LocalContactChanges(
            edited = listOf(LocalContactEdit(href = "", uid = uid, storedEtag = null, contact = contact, localId = LOCAL_ID)),
            deleted = emptyList(),
        )
        provider.markNewUploadedCalls.clear()

        try {
            strategy.push(ACCOUNT, listOf(book), c)
            skipUnless(provider.markNewUploadedCalls.isNotEmpty(), "server did not persist the contact (refused or deferred)")

            val name = contactResourceName(uid)
            val listed = (c.listAllContactHrefs(book.url) as? CalDavResult.Success)?.data.orEmpty()
            val href = listed.firstOrNull { it.first.substringAfterLast('/') == name }?.first
            assertNotNull("${config.name} $case: own resource not listed after the push", href)
            val readBack = (reader.readContacts(book.url, listOf(href!!), book.vcardVersion) as? CalDavResult.Success)
                ?.data?.contacts?.firstOrNull { it.contact.uid == uid }?.contact
            assertNotNull("${config.name} $case: own contact not readable after the push", readBack)
            readBack!!

            val read = if (case.anniversary) readBack.anniversary else readBack.birthday
            val line = servedDateLine(readBack.rawVCard)
            println("=== ${config.name} $case book v${book.vcardVersion}: served=$line read=$read")
            skipUnless(
                !(case.anniversary && config.dropsAppleAnniversary && book.vcardVersion == "3.0"),
                "server drops X-ABDATE anniversaries (CardDavServerConfig.dropsAppleAnniversary)",
            )

            val type = if (case.anniversary) Event.TYPE_ANNIVERSARY else Event.TYPE_BIRTHDAY
            val startDate = VCardContactMapper.toEntity(readBack).dataRows
                .firstOrNull {
                    it.getAsString("mimetype") == Event.CONTENT_ITEM_TYPE && it.getAsInteger(Event.TYPE) == type
                }
                ?.getAsString(Event.START_DATE)
            val dated = case.expected.date
            if (dated != null) {
                // A dated 3.0 X-ABDATE keeps its raw string as text beside the date.
                assertEquals("${config.name} $case: read-back date", dated, read?.date)
                assertEquals(
                    "${config.name} $case: birthday calendar date (START_DATE=$startDate)",
                    ContactEventDate(dated.monthValue, dated.dayOfMonth, dated.year),
                    ContactEventUtils.parseContactDate(startDate),
                )
            } else {
                val expected = if (config.shiftsEarlyYearDates && book.vcardVersion == "3.0") {
                    dayEarlier(case.expected)
                } else {
                    case.expected
                }
                assertEquals("${config.name} $case: read-back date", expected, read)
                assertEquals("${config.name} $case: phone START_DATE", expected.text, startDate)
            }
            if (case.served != null && book.vcardVersion == "3.0") {
                val kept = line != null && line.contains("X-APPLE-OMIT-YEAR=1604") &&
                    line.substringAfterLast(':').trim().startsWith(case.served)
                if (!kept) println("=== SERVED-LINE ${config.name} $case: expected ${case.served} with X-APPLE-OMIT-YEAR=1604, got $line")
            }
        } finally {
            deleteIfPresent(c, book.url, resourceUrl)
        }
    }

    /** Skips this (server, case) unless [condition], printing [reason] first: Gradle drops it. */
    private fun skipUnless(condition: Boolean, reason: String) {
        if (!condition) println("=== SKIP ${config.name} $case: $reason")
        assumeTrue("${config.name} $case: $reason", condition)
    }

    /** Returns [date], a `--MM-DD` text, one day earlier, counted in leap year 1604. */
    private fun dayEarlier(date: ContactDate): ContactDate {
        val shifted = MonthDay.parse(date.text!!).atYear(1604).minusDays(1)
        return ContactDate(text = String.format(Locale.ROOT, "--%02d-%02d", shifted.monthValue, shifted.dayOfMonth))
    }

    /** Returns the unfolded BDAY line of [body], or its X-ABDATE or ANNIVERSARY line, or null. */
    private fun servedDateLine(body: String): String? {
        val properties = if (case.anniversary) setOf("X-ABDATE", "ANNIVERSARY") else setOf("BDAY")
        return body.replace(Regex("""\r?\n[ \t]"""), "").lines().firstOrNull { line ->
            line.substringBefore(':').substringBefore(';').substringAfter('.').uppercase() in properties
        }
    }

    /** Deletes resources this class left behind in a run that crashed before its finally ran. */
    private suspend fun sweepLeftovers(c: CardDavClient, bookUrl: String) {
        val listed = (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data.orEmpty()
        val own = contactResourceName("$UID_PREFIX${case.slug}-").removeSuffix(".vcf")
        for ((href, etag) in listed) {
            val name = href.substringAfterLast('/')
            if (etag != null && name.startsWith(own) && !name.contains(RUN)) {
                c.deleteContact(bookUrl.trimEnd('/') + "/" + name, etag)
            }
        }
    }

    /** Deletes [resourceUrl] with its listed etag if the server still lists it; best-effort. */
    private suspend fun deleteIfPresent(c: CardDavClient, bookUrl: String, resourceUrl: String) {
        val listed = (c.listAllContactHrefs(bookUrl) as? CalDavResult.Success)?.data.orEmpty()
        val name = resourceUrl.substringAfterLast('/')
        listed.firstOrNull { it.first.substringAfterLast('/') == name }?.second?.let { c.deleteContact(resourceUrl, it) }
    }

    /** Returns the login's first writable address book, or null if none is writable. */
    private suspend fun resolveWritableBook(c: CardDavClient, cr: ServerCredentials): CardDavAddressBook? {
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
            .also { skipUnless(it != null, "principal discovery failed") } ?: return null
        val homes = (c.discoverAddressBookHome(principal) as? CalDavResult.Success)?.data.orEmpty()
        if (homes.isEmpty()) return null
        val books = (c.listAddressBooks(homes.first()) as? CalDavResult.Success)?.data.orEmpty()
        return books.firstOrNull { !it.isReadOnly }
    }
}
