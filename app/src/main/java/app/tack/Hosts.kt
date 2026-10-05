package app.tack

/**
 * Host policy. Deliberately conservative: only third-party ad and tracking
 * networks are blocked. Nothing on Pinterest's own domains is ever blocked or
 * altered, so the account sees the same first-party traffic a browser produces.
 */
object Hosts {
    private val pinterest = Regex("""(^|\.)pinterest\.[a-z]{2,3}(\.[a-z]{2})?$""")

    // Sign-in providers have to stay inside the app or the login can't complete:
    // a step handed to the browser arrives there without the session and fails.
    // Google hops between its own domains while verifying (country domains,
    // youtube.com for cookie sync), so all of them count, not just accounts.google.com.
    private val google = Regex("""(^|\.)google\.[a-z]{2,3}(\.[a-z]{2})?$""")
    private val signIn = listOf("youtube.com", "facebook.com", "appleid.apple.com")

    private val blocked = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "google-analytics.com", "googletagmanager.com",
        "amazon-adsystem.com", "criteo.com", "criteo.net", "adnxs.com", "adsrvr.org",
        "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com",
        "moatads.com", "rubiconproject.com", "pubmatic.com", "casalemedia.com",
        "openx.net", "bat.bing.com", "analytics.tiktok.com",
    )

    private fun String.under(domain: String) = this == domain || endsWith(".$domain")

    fun isPinterest(host: String): Boolean = pinterest.containsMatchIn(host) || host == "pin.it"

    /** Pages that load in the app; everything else opens in the browser. */
    fun staysInApp(host: String): Boolean =
        isPinterest(host) || google.containsMatchIn(host) || signIn.any { host.under(it) }

    fun isBlocked(host: String): Boolean = !isPinterest(host) && blocked.any { host.under(it) }
}
