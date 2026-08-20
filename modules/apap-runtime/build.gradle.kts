plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-application"))
    implementation(project(":modules:apap-execution"))
    implementation(project(":modules:apap-routing"))
    implementation(project(":modules:apap-prompt"))
    implementation(project(":modules:apap-context"))
    implementation(project(":modules:apap-provider"))
    implementation(project(":modules:apap-plugin"))
    implementation(project(":modules:apap-cache"))
    implementation(project(":modules:apap-cost"))
    implementation(project(":modules:apap-observability"))
    implementation(project(":modules:apap-infrastructure"))
    implementation(project(":modules:apap-adapter-spi"))
}
