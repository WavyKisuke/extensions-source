import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaFire"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
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
