package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.sync.util.CaldavUrlNormalizer
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

/**
 * Live, on every configured server: signing in the way the app does and refreshing the
 * calendar list keeps the same calendars, whatever way the server address was typed,
 * and the account's sync client can reach every calendar it stored.
 *
 * Everything is read-only on the server: sign-in and refresh only list calendars.
 * Accounts and calendars live in an in-memory database.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerDiscoveryRefreshStabilityTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerDiscoveryRefreshStabilityTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }
    }

    private var creds: ServerCredentials? = null
    private val databases = mutableListOf<KashCalDatabase>()

    @Before
    fun setUp() {
        creds = CalDavTestServerLoader.createClient(config)?.second
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private val isICloud get() = config.name.equals("iCloud", ignoreCase = true)

    /** Returns labelled spellings of the server address, each a real way a user might type it. */
    private fun typedVariants(): List<Pair<String, String>> {
        // iCloud sign-in takes no address: one pass covers it.
        if (isICloud) return listOf("iCloud sign-in" to creds!!.serverUrl)
        val typed = if (config.usesWellKnownDiscovery) creds!!.serverUrl else creds!!.davEndpoint
        val uri = URI(typed)
        val out = linkedMapOf("as configured" to typed)
        out["upper-case host"] = typed.replaceFirst(uri.host, uri.host.uppercase())
        out[if (typed.endsWith("/")) "no trailing slash" else "trailing slash"] =
            if (typed.endsWith("/")) typed.trimEnd('/') else "$typed/"
        if (uri.scheme == "https" && uri.port == -1) out["explicit :443"] = typed.replaceFirst(uri.host, "${uri.host}:443")
        return out.toList()
    }

    // ---- 1. natural: sign in, refresh twice, same calendars ----

    @Test
    fun `signing in and refreshing twice keeps the same calendars for every way of typing the address`() = runBlocking<Unit> {
        assumeReady()
        val failures = mutableListOf<String>()
        for ((label, typed) in typedVariants()) {
            val env = Env()
            val signedIn = env.signIn(typed)
            if (signedIn == null) {
                println("STABILITY|${config.name}|$label|sign-in did not complete (not a finding for this case)")
                continue
            }
            val afterSignIn = env.calendars()
            val r1 = env.refresh(signedIn)
            val afterFirst = env.calendars()
            val r2 = env.refresh(signedIn)
            val afterSecond = env.calendars()
            println("STABILITY|${config.name}|$label|signIn=${afterSignIn.size} refresh1=${afterFirst.size}(${r1.javaClass.simpleName}) refresh2=${afterSecond.size}(${r2.javaClass.simpleName})")
            if (afterSignIn.map { key(it.caldavUrl) }.toSet() != afterSecond.map { key(it.caldavUrl) }.toSet() ||
                afterSecond.size != afterSignIn.size) {
                failures += "$label: ${afterSignIn.size} -> ${afterFirst.size} -> ${afterSecond.size}"
            }
            val dups = afterSecond.groupBy { key(it.caldavUrl) }.filterValues { it.size > 1 }
            if (dups.isNotEmpty()) failures += "$label: ${dups.size} calendar(s) stored twice"
        }
        assertTrue("${config.name}: calendar set changed or duplicated: $failures", failures.isEmpty())
    }

    // ---- 2. simulated: a stored row spelled another way (older install, other sign-in) ----

    @Test
    fun `a refresh with a calendar stored under another spelling of its address`() = runBlocking<Unit> {
        assumeReady()
        val (label, typed) = typedVariants().first()
        val env = Env()
        val accountId = env.signIn(typed)
        assumeTrue("${config.name}: sign-in did not complete", accountId != null)
        val before = env.calendars()
        assumeTrue("${config.name}: no calendars", before.isNotEmpty())

        val results = mutableListOf<String>()
        for ((spelling, respell) in respellings()) {
            val e = Env()
            val id = e.signIn(typed) ?: continue
            val cal = e.calendars().first()
            val altered = respell(cal.caldavUrl) ?: continue
            if (altered == cal.caldavUrl) continue
            e.db.calendarsDao().updateCaldavUrl(cal.id, altered)
            val r = e.refresh(id)
            val after = e.calendars()
            val dup = after.groupBy { key(it.caldavUrl) }.filterValues { it.size > 1 }.isNotEmpty()
            results += "$spelling: ${before.size} -> ${after.size}${if (dup) " DUPLICATE" else ""} (${r.javaClass.simpleName})"
        }
        println("RESPELL|${config.name}|$label|" + results.joinToString(" ; "))
    }

    // ---- 3. the sync client isn't refused on any stored calendar ----

    @Test
    fun `the sync client built from the stored credentials reaches every stored calendar`() = runBlocking<Unit> {
        assumeReady()
        val failures = mutableListOf<String>()
        for ((label, typed) in typedVariants()) {
            val env = Env()
            val accountId = env.signIn(typed) ?: continue
            val stored = env.credentials[accountId]!!
            val quirks = if (isICloud) ICloudQuirks() else DefaultQuirks(env.db.accountsDao().getById(accountId)?.homeSetUrl ?: stored.serverUrl)
            val client = OkHttpCalDavClientFactory().createClient(
                Credentials(stored.username, stored.password, stored.serverUrl, stored.trustInsecure), quirks,
            )
            for ((i, cal) in env.calendars().withIndex()) {
                val r = client.getCtag(cal.caldavUrl)
                if (r is CalDavResult.Error && (r.code == CalDavResult.CODE_TRANSPORT_REFUSED || r.code in setOf(401, 403))) {
                    failures += "$label cal $i: ${r.code} ${FixtureRedactor.redact(r.message.replace("%40", "@"))}"
                }
            }
            println("SYNCREACH|${config.name}|$label|credentialScheme=${URI(stored.serverUrl).scheme} calendars=${env.calendars().size} schemes=${env.calendars().map { URI(it.caldavUrl).scheme }.toSet()}")
        }
        assertTrue("${config.name}: the sync client was refused: $failures", failures.isEmpty())
    }

    // ---- helpers ----

    private fun respellings(): List<Pair<String, (String) -> String?>> = listOf(
        "upper-case host" to { u -> URI(u).host?.let { h -> u.replaceFirst(h, h.uppercase()) } },
        "trailing slash toggled" to { u -> if (u.endsWith("/")) u.trimEnd('/') else "$u/" },
        "explicit default port" to { u ->
            val x = URI(u)
            if (x.port == -1) u.replaceFirst(x.host, "${x.host}:${if (x.scheme == "https") 443 else 80}") else null
        },
        "@ as %40" to { u -> if ('@' in u.substringAfter("://").substringAfter('/')) u.substringBefore("://") + "://" +
            u.substringAfter("://").substringBefore('/') + "/" + u.substringAfter("://").substringAfter('/').replace("@", "%40") else null },
    )

    /**
     * Returns a comparison key: pchar-decoded, host lowercased, default port dropped, trailing
     * slash trimmed.
     */
    private fun key(url: String): String {
        val c = CaldavUrlNormalizer.canonicalize(url) ?: url
        val x = URI(c)
        val port = if (x.port == -1 || (x.scheme == "https" && x.port == 443) || (x.scheme == "http" && x.port == 80)) "" else ":${x.port}"
        return "${x.scheme}://${x.host?.lowercase()}$port${(x.rawPath ?: "").trimEnd('/')}"
    }

    private inner class Env {
        val db: KashCalDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), KashCalDatabase::class.java,
        ).allowMainThreadQueries().build().also { databases += it }
        val credentials = mutableMapOf<Long, AccountCredentials>()
        private val credentialManager = object : CredentialManager {
            override fun isEncryptionAvailable() = true
            override suspend fun saveCredentials(accountId: Long, credentials: AccountCredentials): Boolean {
                this@Env.credentials[accountId] = credentials; return true
            }
            override suspend fun getCredentials(accountId: Long) = credentials[accountId]
            override suspend fun hasCredentials(accountId: Long) = credentials.containsKey(accountId)
            override suspend fun deleteCredentials(accountId: Long) { credentials.remove(accountId) }
            override suspend fun clearAllCredentials() = credentials.clear()
        }
        private val accountRepository = AccountRepositoryImpl(
            accountsDao = db.accountsDao(), addressBookDao = db.addressBookDao(), calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(), pendingOperationsDao = db.pendingOperationsDao(),
            credentialManager = credentialManager,
            reminderScheduler = mockk(relaxed = true), workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true), contactsProviderRepository = mockk(relaxed = true),
        )
        private val calendarRepository = CalendarRepositoryImpl(db.calendarsDao())
        private val caldav = CalDavAccountDiscoveryService(OkHttpCalDavClientFactory(), accountRepository, calendarRepository)
        private val icloud = ICloudAccountDiscoveryService(
            OkHttpCalDavClientFactory(), ICloudCredentialProvider(credentialManager, accountRepository), ICloudQuirks(),
            accountRepository, calendarRepository,
        )

        suspend fun calendars(): List<Calendar> = db.calendarsDao().getAllOnce()

        /**
         * Signs in as the add-account screen does; returns the account id, or null if sign-in
         * didn't complete.
         */
        suspend fun signIn(typed: String): Long? {
            val c = creds!!
            if (isICloud) {
                val r = icloud.discoverAndCreateAccount(c.username, c.password)
                return (r as? DiscoveryResult.Success)?.account?.id
            }
            val found = caldav.discoverCalendars(typed, c.username, c.password, false) as? DiscoveryResult.CalendarsFound
                ?: return null
            val created = caldav.createAccountWithSelectedCalendars(
                serverUrl = found.serverUrl, username = c.username, password = c.password, trustInsecure = false,
                principalUrl = found.principalUrl, calendarHomeUrl = found.calendarHomeUrl,
                selectedCalendars = found.calendars,
            )
            return (created as? DiscoveryResult.Success)?.account?.id
        }

        suspend fun refresh(accountId: Long): DiscoveryResult =
            if (isICloud) icloud.refreshCalendars(accountId) else caldav.refreshCalendars(accountId)
    }
}
