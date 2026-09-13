import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaFire"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    // Use the modern KeiSource contract required by current Tachimanga.
    libVersion = "1.6"

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
