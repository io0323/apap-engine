plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // TokenBucketRateLimiterが警告ログを出すために使う（CLAUDE.md不変条件6でSLF4J APIは許可）。
    implementation(libs.findLibrary("slf4j-api").get())
    // TokenBucketRateLimiter.acquire()の有界待機（delay）に使う。
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    // NormalizedJsonCacheKeyStrategyのキー正規化に使う（ADR-0017は従来constraintsのみで実利用ゼロ
    // だったため、apap-cacheが本SPIの実利用第一号になる）。
    implementation(libs.findLibrary("jackson-databind").get())
    testImplementation(project(":modules:apap-testkit"))
}
