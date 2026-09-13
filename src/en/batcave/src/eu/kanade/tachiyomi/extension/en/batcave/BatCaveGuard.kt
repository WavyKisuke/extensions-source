package eu.kanade.tachiyomi.extension.en.batcave

import android.webkit.CookieManager
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Interceptor
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Small, self-contained solver for BatCave's DLE guard page. */
class BatCaveGuard(private val site: String) {
    private val cookieName = "__guard_trust"
    private var solvedAt = 0L

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.request.url.pathSegments.contains("_c")) return@Interceptor response
        response.close()

        if (!solve(request.header("User-Agent"), request.url.toString(), chain.call())) {
            throw IOException("BatCave protection could not be solved")
        }
        chain.proceed(request)
    }

    @Synchronized
    private fun solve(userAgent: String?, url: String, call: okhttp3.Call): Boolean {
        if (System.currentTimeMillis() - solvedAt < 10_000) return true
        val cookies = CookieManager.getInstance()
        cookies.setCookie(url, "$cookieName=; Max-Age=0; Path=/")

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
                loadUrl(url)
            }
            val solved = cookies.getCookie(url).orEmpty().split(';')
                .any { it.trim().startsWith("$cookieName=") }
            if (solved) solvedAt = System.currentTimeMillis()
            solved
        } catch (_: Exception) {
            false
        }
    }
}
