package org.onekash.kashcal.domain.feeds

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerCalendarFeeds"

/** An account that can list calendar feeds: its id, its name for display, and its email. */
data class FeedAccount(val id: Long, val displayName: String, val email: String)

/**
 * A calendar feed a server lists, ready to subscribe to.
 *
 * @property url the cleaned feed URL ([CalendarFeedUrl.normalize]), never with userinfo.
 * @property name the server's name for the feed, or else the URL's host.
 * @property color the server's color as ARGB, or null when it sent none or one that doesn't parse.
 */
data class ServerFeed(val url: String, val name: String, val color: Int?)

/** The outcome of listing one account's calendar feeds. */
sealed interface FeedListing {
    /** The server answered; [feeds] is empty when it lists none KashCal can use. */
    data class Feeds(val feeds: List<ServerFeed>) : FeedListing

    /** No listing: the account can't list feeds or lacks what a request needs, or it failed. */
    data object Failed : FeedListing
}

/**
 * Reads the calendar feeds (`CS:subscribed` collections) that CalDAV servers store on each
 * account, for the Calendar Feeds "Add from account" sheet. It never writes: subscribing goes
 * through the ordinary ICS subscription path.
 */
@Singleton
class ServerCalendarFeeds @Inject constructor(
    private val accountRepository: AccountRepository,
    private val providerRegistry: ProviderRegistry,
    private val clientFactory: CalDavClientFactory,
) {

    /** Observes the accounts whose provider has `supportsCalendarFeeds`, in repository order. */
    fun observeFeedAccounts(): Flow<List<FeedAccount>> =
        accountRepository.getAllAccountsFlow()
            .map { accounts ->
                accounts.filter { it.provider.supportsCalendarFeeds }.map { account ->
                    FeedAccount(
                        id = account.id,
                        displayName = account.displayName ?: account.provider.displayName,
                        email = account.email,
                    )
                }
            }
            .distinctUntilChanged()

    /**
     * Lists the calendar feeds stored on account [accountId]'s calendar home.
     *
     * The client is built the way calendar sync builds it (credentials and quirks from
     * [ProviderRegistry]). Feeds whose URL [CalendarFeedUrl.normalize] rejects are left out, and a
     * URL listed twice appears once (the first listing wins). Any failure is [FeedListing.Failed];
     * cancellation is rethrown.
     */
    suspend fun listFeeds(accountId: Long): FeedListing {
        return try {
            val account = accountRepository.getAccountById(accountId) ?: return FeedListing.Failed
            if (!account.provider.supportsCalendarFeeds) return FeedListing.Failed
            val homeUrl = account.homeSetUrl ?: return FeedListing.Failed
            val credentials = providerRegistry.getCredentialProvider(account.provider)
                ?.getCredentials(accountId) ?: return FeedListing.Failed
            val quirks = providerRegistry.getQuirksForAccount(account) ?: return FeedListing.Failed

            when (val result = clientFactory.createClient(credentials, quirks).listWebcalFeeds(homeUrl)) {
                is CalDavResult.Success -> FeedListing.Feeds(toServerFeeds(result.data))
                is CalDavResult.Error -> {
                    Log.w(TAG, "Feed listing failed for account $accountId: ${result.code}")
                    FeedListing.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Feed listing failed for account $accountId: ${e.javaClass.simpleName}")
            FeedListing.Failed
        }
    }

    private fun toServerFeeds(parsed: List<CalDavQuirks.ParsedWebcalFeed>): List<ServerFeed> {
        val seen = HashSet<String>()
        return parsed.mapNotNull { feed ->
            val url = CalendarFeedUrl.normalize(feed.source) ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            ServerFeed(
                url = url,
                name = feed.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: CalendarFeedUrl.hostOf(url),
                color = parseServerFeedColor(feed.color),
            )
        }
    }

    /**
     * Parses a server color (#RRGGBBAA, #RRGGBB or RRGGBB) to ARGB, or null. Calendar discovery
     * has its own parsers with a palette fallback (CalDavAccountDiscoveryService.parseColor,
     * ICloudAccountDiscoveryService.parseColor); here the caller picks the fallback.
     */
    private fun parseServerFeedColor(raw: String?): Int? {
        val hex = raw?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            8 -> (((value and 0xFF) shl 24) or (value ushr 8)).toInt()
            else -> (0xFF000000L or value).toInt()
        }
    }
}
