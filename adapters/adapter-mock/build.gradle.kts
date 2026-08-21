plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-adapter-spi"))
    // ADR-0015: adapter-mockのmainソースセットはapap-adapter-spiのみに依存する。apap-testkit
    // （Adapter Contract Test置き場）はtestソースセット限定でのみ利用する。
    testImplementation(project(":modules:apap-testkit"))
}
