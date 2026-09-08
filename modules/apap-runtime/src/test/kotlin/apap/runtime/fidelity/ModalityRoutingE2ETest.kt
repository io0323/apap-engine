package apap.runtime.fidelity

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ImageContentPart
import apap.adapter.spi.InputMessage
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TurnRole
import apap.api.ApapException
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.vo.Modality
import apap.runtime.ApapRepositories
import apap.runtime.EngineFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0039: 非対応modalityのProviderへ**実行前に**ルーティングされないこと。
 *
 * ## 何が問題だったか
 *
 * `ModelCapability.constraints`は`Map<String, String>`の自由領域で**誰も読んでいなかった**。
 * Adapter側の`CapabilityConstraints`にも申告口が無く、Routingは`capabilityId`でしか絞れなかった。
 * その結果、画像非対応のProviderへ画像リクエストが割り当てられ、
 * **Adapterを呼んで初めてUNSUPPORTED_CAPABILITYで失敗**していた（Fallbackを1段無駄に消費する）。
 */
class ModalityRoutingE2ETest {
    private val capabilityId = FidelitySentinels.CAPABILITY

    @Test
    fun `a provider that does not accept images is excluded before it is ever called`() {
        val textOnly = CountingAdapter(EngineFixture.mock(mockConfig()))
        val repositories = ApapRepositories()
        val fixture = EngineFixture.build(capabilityId, mapOf("plugin-a" to textOnly), repositories)

        fixture.use {
            runBlocking {
                val modelId = EngineFixture.registerActive(fixture, capabilityId)
                declareModalities(repositories, modelId, setOf(Modality.TEXT))

                val failure =
                    assertThrows(ApapException::class.java) {
                        runBlocking { fixture.engine.execute(imageRequest()) }
                    }

                assertEquals(
                    0,
                    textOnly.calls.get(),
                    "非対応と申告しているProviderが呼ばれています（実行前に除外できていない）",
                )
                assertTrue(
                    failure.message.orEmpty().isNotBlank(),
                    "候補が無いことが利用側へ伝わっていません: ${failure.code}",
                )
            }
        }
    }

    @Test
    fun `a provider that accepts images is still selected`() {
        val capable = CountingAdapter(EngineFixture.mock(mockConfig()))
        val repositories = ApapRepositories()
        val fixture = EngineFixture.build(capabilityId, mapOf("plugin-a" to capable), repositories)

        fixture.use {
            runBlocking {
                val modelId = EngineFixture.registerActive(fixture, capabilityId)
                declareModalities(repositories, modelId, setOf(Modality.TEXT, Modality.IMAGE))

                fixture.engine.execute(imageRequest())
                assertEquals(1, capable.calls.get(), "対応を申告しているのに候補から外れています")
            }
        }
    }

    /**
     * 申告の無いModelは従来どおり候補に残す。全Modelを落とすと、
     * modalityを宣言していない既存の登録がすべて使えなくなるため（ADR-0039の既定）。
     */
    @Test
    fun `a model that declares no modalities is left in the candidate set`() {
        val undeclared = CountingAdapter(EngineFixture.mock(mockConfig()))
        val fixture = EngineFixture.build(capabilityId, mapOf("plugin-a" to undeclared))

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)
                fixture.engine.execute(imageRequest())
                assertEquals(1, undeclared.calls.get(), "未申告のModelが候補から消えています")
            }
        }
    }

    private fun imageRequest() =
        EngineFixture.request(capabilityId).copy(
            input = listOf(TextContentPart("look"), ImageContentPart("https://example.invalid/a.png", "image/png")),
            messages =
                listOf(
                    InputMessage(
                        TurnRole.USER,
                        listOf(
                            TextContentPart("look"),
                            ImageContentPart("https://example.invalid/a.png", "image/png"),
                        ),
                    ),
                ),
        )

    /** 登録済みModelのCapability申告を差し替える（Admin APIにmodality設定の口がまだ無いため直接書く）。 */
    private fun declareModalities(
        repositories: ApapRepositories,
        modelId: apap.domain.model.vo.ModelId,
        modalities: Set<Modality>,
    ) {
        val model = repositories.modelRepository.findById(modelId)!!
        repositories.modelRepository.save(
            model.copy(
                capabilities =
                    model.capabilities.map { capability ->
                        if (capability.capabilityId == capabilityId) {
                            ModelCapability(capability.capabilityId, capability.constraints, modalities)
                        } else {
                            capability
                        }
                    },
            ),
        )
    }

    private fun mockConfig() = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))

    private class CountingAdapter(
        private val delegate: ProviderAdapter,
    ) : ProviderAdapter by delegate {
        val calls = AtomicInteger(0)

        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            calls.incrementAndGet()
            return delegate.execute(request)
        }
    }
}
