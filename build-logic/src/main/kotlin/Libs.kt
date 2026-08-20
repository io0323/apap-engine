import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

// 型安全なVersion Catalogアクセサ（`libs.xxx`）はPrecompiled Script Plugin内では
// 解決されないため、汎用ルックアップを共有ヘルパーとして提供する。
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
