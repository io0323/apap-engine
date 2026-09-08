package apap.provider

import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryProviderRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0041: Adapterインスタンスの生存期間が**Providerライフサイクルへ実際に配線されている**ことの検証。
 *
 * [ProviderAdapterProvisionerTest]がProvisioner単体の性質を見るのに対し、こちらは
 * `ProviderManager`の状態遷移を実際に通して「いつ生成され、いつ畳まれるか」を見る。
 * ADR-0041が記録した欠陥は「型は揃っているのに本番の誰も呼ばない」ことだったので、
 * 呼び出し元の側から確認しないと同じ穴がまた空く。
 *
 * 本番配線と同じく、Provisionerを`AdapterRegistry`としても渡している。
 */
class ProviderAdapterLifecycleTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val providerRepository = InMemoryProviderRepository()
    private val eventPublisher = InMemoryDomainEventPublisher()
    private val factory = RecordingAdapterFactory()
    private val provisioner = ProviderAdapterProvisioner(factory, MapSecretStore())
    private val manager =
        ProviderManager(
            providerRepository,
            eventPublisher,
            InMemoryClock(),
            InMemoryIdGenerator(),
            provisioner,
            provisioner,
        )

    @Test
    fun `the adapter is initialized before the provider ever reaches ACTIVE`(): Unit =
        runBlocking {
            val registered = manager.register(registerCommand())
            assertTrue(factory.created.isEmpty(), "登録しただけでAdapterが生成されています")

            manager.beginValidation(registered.providerId)
            assertTrue(
                factory.created.single().initialized,
                "VALIDATINGでinitializeされていません。" +
                    "直後のcompleteValidationが未初期化のAdapterへvalidateCredential/healthCheckを呼びます",
            )

            assertTrue(manager.completeValidation(registered.providerId) is ValidationOutcome.Passed)
            val active = manager.enable(registered.providerId, "verified")
            assertEquals(ProviderStatus.ACTIVE, active.status)

            // ACTIVE時点で使われるインスタンスは初期化済みで、かつ**昇格後の**Credentialを持つ。
            val inUse = provisioner.resolve(registered.providerId).adapter as RecordingAdapter
            assertTrue(inUse.initialized, "ACTIVEなProviderのAdapterが未初期化です")
            assertEquals(
                listOf(CredentialState.ACTIVE),
                inUse.config!!.credentialRefs.map { it.state },
                "検証でACTIVEへ昇格したCredentialがAdapterへ届いていません",
            )
            // 未初期化のまま外へ出たインスタンスが1つも無いこと。
            assertTrue(factory.created.all { it.initialized }, "initializeされていないインスタンスがあります")
        }

    @Test
    fun `the adapter is shut down when the provider becomes DISABLED`() {
        val provider = manager.register(registerCommand())
        manager.beginValidation(provider.providerId)
        val adapter = factory.created.single()
        manager.enable(provider.providerId, "verified")
        manager.drain(provider.providerId, "maintenance")

        manager.completeDraining(provider.providerId, "drained")

        assertEquals(1, adapter.shutdownCount, "DISABLEDでshutdownされていません")
        assertTrue(provisioner.provisionedProviders().isEmpty())
    }

    @Test
    fun `the adapter is shut down when the provider is deleted`() {
        val provider = manager.register(registerCommand())
        manager.beginValidation(provider.providerId)
        val adapter = factory.created.single()
        manager.enable(provider.providerId, "verified")
        manager.drain(provider.providerId, "maintenance")
        manager.completeDraining(provider.providerId, "drained")
        assertEquals(1, adapter.shutdownCount)

        manager.delete(provider.providerId)

        // DISABLEDで既に畳んであるので二重には呼ばない（releaseは冪等）。
        assertEquals(1, adapter.shutdownCount, "shutdownが2度呼ばれています")
        assertTrue(provisioner.provisionedProviders().isEmpty())
    }

    /** 9.7 Credential Rotation。**再起動を要求せず**、新しいCredentialで動き続けること。 */
    @Test
    fun `after rotation the adapter runs on the new credential without a restart`(): Unit =
        runBlocking {
            val provider = manager.register(registerCommand())
            manager.beginValidation(provider.providerId)
            manager.completeValidation(provider.providerId)
            manager.enable(provider.providerId, "verified")
            val before = provisioner.resolve(provider.providerId).adapter as RecordingAdapter

            val rotated =
                manager.rotateCredential(
                    provider.providerId,
                    CredentialRef("secret-ref-v2", 2, CredentialState.STANDBY),
                    verified = true,
                )

            assertEquals(
                CredentialState.ACTIVE,
                rotated.credentialRefs.single { it.secretRef == "secret-ref-v2" }.state,
            )
            assertEquals(
                CredentialState.REVOKED_PENDING,
                rotated.credentialRefs.single { it.secretRef == "secret-ref" }.state,
            )

            val after = provisioner.resolve(provider.providerId).adapter as RecordingAdapter
            assertNotSame(before, after, "ローテーションしたのに古い設定のインスタンスのままです")
            assertTrue(before.shutdownCalled, "旧インスタンスが畳まれていません")
            assertTrue(after.initialized, "新インスタンスがinitializeされていません")
            // 新インスタンスは新しいACTIVE Credentialを解決できる。プロセス再起動は要らない。
            after.secrets!!
                .resolve(CredentialRef("secret-ref-v2", 2, CredentialState.ACTIVE))
                .use { assertEquals("secret-secret-ref-v2", String(it.charArray())) }
        }

    private fun registerCommand() =
        RegisterProviderCommand(
            name = "test-provider",
            adapterPluginId = "plugin-a",
            spiVersion = SemVer(1, 0, 0),
            endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
            authType = "api_key",
            credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
            rateLimits = RateLimits(rpm = 60, tpm = 1000, concurrent = 10),
            priority = 50,
            regions = setOf(region),
        )
}
