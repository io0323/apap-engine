plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("konsist").get())
}
