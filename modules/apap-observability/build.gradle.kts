plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-infrastructure"))
    implementation(libs.findLibrary("slf4j-api").get())
    implementation(libs.findLibrary("opentelemetry-api").get())

    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("opentelemetry-sdk").get())
    testImplementation(libs.findLibrary("opentelemetry-sdk-testing").get())
}
