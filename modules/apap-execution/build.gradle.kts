plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-routing"))
    implementation(project(":modules:apap-prompt"))
    implementation(project(":modules:apap-context"))
    implementation(project(":modules:apap-cache"))
    implementation(project(":modules:apap-cost"))
}
