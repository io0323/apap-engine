plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // TokenBucketRateLimiter/PassthroughCacheEngineが警告ログを出すために使う
    // （CLAUDE.md不変条件6でSLF4J APIは許可）。
    implementation(libs.findLibrary("slf4j-api").get())
    // TokenBucketRateLimiter.acquire()の有界待機（delay）に使う。
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    testImplementation(project(":modules:apap-testkit"))
}
