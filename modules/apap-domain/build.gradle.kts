plugins {
    id("apap.library")
}

dependencies {
    testImplementation(libs.findLibrary("konsist").get())
}
