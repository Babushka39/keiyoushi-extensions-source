plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    className = "NHentaiFactory"
    versionCode = 55
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}
