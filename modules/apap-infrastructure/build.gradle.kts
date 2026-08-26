plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(libs.findLibrary("slf4j-api").get())
}
