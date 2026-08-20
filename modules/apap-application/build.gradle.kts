plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-execution"))
    implementation(project(":modules:apap-routing"))
    implementation(project(":modules:apap-provider"))
}
