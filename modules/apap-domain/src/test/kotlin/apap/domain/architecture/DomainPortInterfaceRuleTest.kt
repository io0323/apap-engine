package apap.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * docs/design/08_パッケージ図.md: apap-domain の port パッケージは
 * Repository/SPIのIF（interface）置き場。実装クラスやobjectを紛れ込ませない。
 */
class DomainPortInterfaceRuleTest {
    @Test
    fun `apap-domain-port package contains only interfaces`() {
        Konsist
            .scopeFromDirectory("modules/apap-domain")
            .classesAndObjects()
            .assertFalse { it.resideInPackage("apap.domain.port..") }
    }
}
