import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "BatCave New"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://batcave.biz"
    }

    deeplink {
        path("/..*")
    }
}
