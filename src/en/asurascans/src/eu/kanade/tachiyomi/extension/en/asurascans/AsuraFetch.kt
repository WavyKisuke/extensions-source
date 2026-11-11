package eu.kanade.tachiyomi.extension.en.asurascans

import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Browser request modes compatible with the older shared core in this fork.
 */
internal enum class Fetch {
    DOCUMENT,
    XHR,
}

private fun Headers.withFetchHeaders(fetch: Fetch, url: HttpUrl): Headers {
    val builder = newBuilder()
    builder.set(
        "Accept",
        when (fetch) {
            Fetch.DOCUMENT -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            Fetch.XHR -> "*/*"
        },
    )
    builder.set("Sec-Fetch-Dest", if (fetch == Fetch.DOCUMENT) "document" else "empty")
    builder.set("Sec-Fetch-Mode", if (fetch == Fetch.DOCUMENT) "navigate" else "cors")
    val refererHost = get("Referer")?.let { runCatching { it.toHttpUrl().host }.getOrNull() }
    builder.set(
        "Sec-Fetch-Site",
        when {
            refererHost == null -> "none"
            refererHost == url.host -> "same-origin"
            refererHost.endsWith(".asurascans.com") && url.host.endsWith(".asurascans.com") -> "same-site"
            else -> "cross-site"
        },
    )
    if (fetch == Fetch.DOCUMENT) builder.set("Upgrade-Insecure-Requests", "1")
    return builder.build()
}

internal suspend fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers,
    fetch: Fetch,
    ensureSuccess: Boolean = true,
): Response {
    val request = Request.Builder()
        .url(url)
        .headers(headers.withFetchHeaders(fetch, url))
        .get()
        .build()
    val call = newCall(request)
    return if (ensureSuccess) call.awaitSuccess() else call.await()
}

internal suspend fun OkHttpClient.get(
    url: String,
    headers: Headers,
    fetch: Fetch,
    ensureSuccess: Boolean = true,
): Response = get(url.toHttpUrl(), headers, fetch, ensureSuccess)
