plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // SecretStoreAccessorがapap.domain.port.SecretStoreをapap.adapter.spi.SecretAccessorへ
    // ブリッジするために必要（SecretValueの所有元、apap-domainには持ち込めない依存ゼロ原則のため）。
    // apap-adapter-spi自体はapap-domainのみに依存する軽量モジュール。
    implementation(project(":modules:apap-adapter-spi"))
    implementation(libs.findLibrary("slf4j-api").get())
}
