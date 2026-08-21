plugins {
    id("apap.library")
}

dependencies {
    // AdapterStream.asFlow()の戻り値(Flow<AdapterChunk>)としてこのSPIの公開APIに現れるため、
    // implementationではなくapiとして依存先(adapters/*等)のコンパイルクラスパスへ伝播させる。
    api(libs.findLibrary("kotlinx-coroutines-core").get())
    api(project(":modules:apap-domain"))
}
