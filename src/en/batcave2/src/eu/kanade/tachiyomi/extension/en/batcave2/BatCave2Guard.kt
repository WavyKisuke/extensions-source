package eu.kanade.tachiyomi.extension.en.batcave2

import android.webkit.CookieManager
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Interceptor
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Automatically handles BatCave's browser-side trust cookie.
 *
 * The extension opens the guard page itself when required, so the user does not
 * need to visit batcave.biz in Safari first.
 */
class BatCave2Guard(private val site: String) {
    private val cookieName = "__guard_trust"
    private var solvedAt = 0L

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.request.url.pathSegments.contains("_c")) return@Interceptor response
        response.close()

        if (!solve(request.header("User-Agent"), request.url.toString(), chain.call())) {
            throw IOException("BatCave protection could not be solved automatically")
        }

        // Retry the original request after WebView has stored the trust cookie.
        chain.proceed(request)
    }

    @Synchronized
    private fun solve(userAgent: String?, challengedUrl: String, call: okhttp3.Call): Boolean {
        if (System.currentTimeMillis() - solvedAt < 10_000) return true

        val cookies = CookieManager.getInstance()
        val siteUrl = site.trimEnd('/') + "/"

        cookies.setCookie(siteUrl, "$cookieName=; Max-Age=0; Path=/")
        cookies.flush()

        return try {
            runWebViewBlocking<Unit>(call, timeout = 30.seconds) {
                this.userAgent = userAgent ?: this.userAgent
                blockImages = true

                onPageFinished { finishedUrl ->
                    poll(250.milliseconds) {
                        val value = cookies.getCookie(finishedUrl).orEmpty()
                        if (value.split(';').any { it.trim().startsWith("$cookieName=") }) {
                            resolve(Unit)
                        }
                    }
                }

                // Start from the site root rather than requiring the user to
                // open the website manually. This also lets the guard redirect
                // through its normal browser flow before the original request
                // is retried by OkHttp.
                loadUrl(siteUrl)
            }

            cookies.flush()
            val solved = listOf(siteUrl, challengedUrl).any { url ->
                cookies.getCookie(url).orEmpty().split(';')
                    .any { it.trim().startsWith("$cookieName=") }
            }

            if (solved) solvedAt = System.currentTimeMillis()
            solved
        } catch (_: Exception) {
            false
        }
    }
}
