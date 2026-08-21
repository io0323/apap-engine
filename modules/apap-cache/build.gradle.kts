plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // TokenBucketRateLimiter/PassthroughCacheEngineが警告ログを出すために使う
    // （CLAUDE.md不変条件6でSLF4J APIは許可）。
    implementation(libs.findLibrary("slf4j-api").get())
    testImplementation(project(":modules:apap-testkit"))
}
