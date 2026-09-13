import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaFire"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    // Legacy HttpSource build for compatibility with older Tachimanga/Mihon forks.
    // Trigger a clean CI rebuild after the compatibility source fixes.
    libVersion = "1.4"

    source {
        name = "MangaFire"
        lang = "en"
        baseUrl = "https://mangafire.to"
    }

    deeplink {
        host("mangafire.to")
        path("/title/..*")
    }
}
