plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-infrastructure"))
    implementation(libs.findLibrary("slf4j-api").get())

    testImplementation(project(":modules:apap-testkit"))
}
