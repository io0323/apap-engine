plugins {
    id("apap.library")
}

dependencies {
    // 15.1 Step1: 依存は apap-adapter-spi のみ（コアモジュールへの依存禁止）。
    // AdapterDependencyRuleTestがimport単位でも機械検証している。
    implementation(project(":modules:apap-adapter-spi"))

    // 実HTTP/SSEを話すために必要な第三者ライブラリ。コアモジュールではないため
    // 15.1 Step1の「コアへの依存禁止」に抵触しない。既にversion catalogにあるものだけを使う
    // （ADR-0017: JSONスタックはJacksonへ一本化）。
    implementation(libs.findLibrary("ktor-client-cio").get())
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-module-kotlin").get())
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())

    // ADR-0015: testソースセットのみapap-testkit（Adapter Contract Test）へ依存してよい。
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
