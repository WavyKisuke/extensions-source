import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaFire"
    versionCode = 5
    contentWarning = ContentWarning.MIXED
    // Build against extension-lib 1.4 for compatibility with older Tachimanga builds.
    libVersion = "1.4"

    // Publish trigger marker.
    // Compatibility build after the lib 1.6 IncompatibleClassChangeError.
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
