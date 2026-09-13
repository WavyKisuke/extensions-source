import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaFire"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    // Use the legacy 1.4 source contract for maximum Tachimanga compatibility.
    libVersion = "1.4"

    source {
        name = "MangaFire"
        lang = "en"
        baseUrl = "https://mangafire.to"
    }
}
