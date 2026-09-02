plugins {
    id("apap.library")
}

fun lib(alias: String) = libs.findLibrary(alias).get()

// ADR-0029: このモジュールは「埋込ホスト（prompt-engine）が実際に持つ依存だけで
// docs/integration/prompt-engine.md のコード例がコンパイルできるか」を検証するためにある。
//
// **依存を増やしてはならない。** apap-runtime / apap-api 以外のAPAPモジュールを足すと、
// ホストには見えない型がここでは見えてしまい、検証の意味が失われる
// （P9では実際に、ホストから見えない apap.execution.ExecutionFailedException を
// import するコード例をドキュメントへ載せてしまい、検査対象外だったため気づけなかった）。
// この制約は HostCompileClasspathTest が機械検証する。
dependencies {
    implementation(project(":modules:apap-runtime"))
    implementation(project(":modules:apap-api"))

    // ホスト側もテストではadapter-mockを使う（ガイド4章）。テストスコープに限定し、
    // mainのコンパイルクラスパスには入れない。
    testImplementation(project(":adapters:adapter-mock"))
    testImplementation(project(":modules:apap-adapter-spi"))
    testImplementation(lib("kotlinx-coroutines-test"))
}

/**
 * HostCompileClasspathTest が読むため、mainのコンパイルクラスパスをファイルへ書き出す。
 *
 * テストからGradleの依存解決API（Tooling API等）を直接叩くとテスト依存が増え、
 * このモジュールの「依存を増やさない」という趣旨と衝突する。そこでビルド時に書き出し、
 * テストは結果を読むだけにする。
 *
 * Configuration Cache対応のため、`Provider`をタスクアクションへ捕捉せず、
 * `ConfigurableFileCollection`/`RegularFileProperty`として受け渡す。
 */
abstract class DumpCompileClasspath : DefaultTask() {
    @get:InputFiles
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(classpath.files.joinToString("\n") { it.absolutePath })
    }
}

val dumpCompileClasspath by tasks.registering(DumpCompileClasspath::class) {
    classpath.from(configurations.named("compileClasspath"))
    outputFile.set(layout.buildDirectory.file("host-compat/compile-classpath.txt"))
}

tasks.named("test") {
    dependsOn(dumpCompileClasspath)
}
