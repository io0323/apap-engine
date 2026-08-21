plugins {
    id("apap.library")
}

dependencies {
    // apap-testkitはmainソースセットからJUnit/Domain/Adapter SPIの型を公開APIとして
    // 露出させる（AdapterContractTest、In-Memory Port実装）。利用側（他モジュールのtestソースセット）が
    // これらを再宣言せずに使えるよう、implementationではなくapiとする。
    api(project(":modules:apap-domain"))
    api(project(":modules:apap-adapter-spi"))
    api(libs.findLibrary("junit-jupiter").get())
}
