package apap.provider

import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0041 / FR-SEC-005（Provider Isolation） / NFR-SEC-004（Adapterは自ProviderのCredentialのみ）。
 *
 * 従来の`AdapterRegistry`は**pluginIdをキー**にしていたため、1つのPluginを複数のProviderが参照すると
 * 同一インスタンスが共有された。共有インスタンスは全Providerの設定に触れられるので、この2要件は
 * 実装の良し悪し以前に**原理的に成立しなかった**。ここでは分離が機構として成立していることを見る。
 */
class ProviderAdapterProvisionerTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val factory = RecordingAdapterFactory()
    private val provisioner = ProviderAdapterProvisioner(factory, MapSecretStore())

    @Test
    fun `two providers sharing one plugin get separate adapter instances`() {
        val first = provisioner.provision(provider(PROVIDER_A, "key-a"))
        val second = provisioner.provision(provider(PROVIDER_B, "key-b"))

        assertNotSame(
            first.adapter,
            second.adapter,
            "同じPluginを参照する2つのProviderが1つのインスタンスを共有しています（FR-SEC-005が成立しません）",
        )
        assertEquals(2, factory.created.size, "Providerごとに生成されていません")
        assertSame(first.adapter, provisioner.resolve(PROVIDER_A).adapter)
        assertSame(second.adapter, provisioner.resolve(PROVIDER_B).adapter)
    }

    @Test
    fun `an adapter can resolve only its own provider's credentials`() {
        provisioner.provision(provider(PROVIDER_A, "key-a"))
        provisioner.provision(provider(PROVIDER_B, "key-b"))
        val secretsOfA = (provisioner.resolve(PROVIDER_A).adapter as RecordingAdapter).secrets!!

        secretsOfA.resolve(ref("key-a")).use { assertEquals("secret-key-a", String(it.charArray())) }

        // 他Providerの参照は拒否される。**規約ではなく機構で**弾いている点が要点。
        val denied = assertThrows(CredentialAccessDeniedException::class.java) { secretsOfA.resolve(ref("key-b")) }
        assertTrue(
            denied.message.orEmpty().contains("key-b"),
            "どの参照が拒否されたのか分かりません: ${denied.message}",
        )
    }

    @Test
    fun `each adapter is initialized with only its own provider's configuration`() {
        provisioner.provision(provider(PROVIDER_A, "key-a", baseUrl = "https://a.invalid"))
        provisioner.provision(provider(PROVIDER_B, "key-b", baseUrl = "https://b.invalid"))

        val configA = (provisioner.resolve(PROVIDER_A).adapter as RecordingAdapter).config!!
        val configB = (provisioner.resolve(PROVIDER_B).adapter as RecordingAdapter).config!!

        assertEquals(PROVIDER_A, configA.providerId)
        assertEquals("https://a.invalid", configA.endpoints.single().baseUrl)
        assertEquals(listOf("key-a"), configA.credentialRefs.map { it.secretRef })
        assertEquals(PROVIDER_B, configB.providerId)
        assertEquals("https://b.invalid", configB.endpoints.single().baseUrl)
        assertEquals(listOf("key-b"), configB.credentialRefs.map { it.secretRef })
    }

    @Test
    fun `provisioning again with an unchanged configuration keeps the same instance`() {
        val first = provisioner.provision(provider(PROVIDER_A, "key-a"))
        val again = provisioner.provision(provider(PROVIDER_A, "key-a"))

        assertSame(first.adapter, again.adapter, "設定が同じなのに作り直しています")
        assertEquals(1, factory.created.size)
    }

    /** 設定変更・Credentialローテーションが**プロセス再起動なしに**反映されること。 */
    @Test
    fun `a changed credential set replaces the instance and shuts the old one down`() {
        val before = provisioner.provision(provider(PROVIDER_A, "key-a")).adapter as RecordingAdapter

        val rotated =
            provider(PROVIDER_A, "key-a").copy(
                credentialRefs =
                    listOf(
                        CredentialRef("key-a", 1, CredentialState.REVOKED_PENDING),
                        CredentialRef("key-a2", 2, CredentialState.ACTIVE),
                    ),
            )
        val after = provisioner.provision(rotated).adapter as RecordingAdapter

        assertNotSame(before, after, "Credentialが入れ替わったのにインスタンスが同じままです")
        assertTrue(before.shutdownCalled, "差し替え前のインスタンスがshutdownされていません")
        after.secrets!!.resolve(ref("key-a2", 2)).use { assertEquals("secret-key-a2", String(it.charArray())) }
        // 差し替え後のインスタンスは、失効した旧参照をもう解決できない。
        assertThrows(CredentialAccessDeniedException::class.java) { after.secrets!!.resolve(ref("key-a3", 3)) }
    }

    @Test
    fun `release shuts the adapter down and forgets it`() {
        val adapter = provisioner.provision(provider(PROVIDER_A, "key-a")).adapter as RecordingAdapter

        provisioner.release(PROVIDER_A)

        assertEquals(1, adapter.shutdownCount, "releaseでshutdownされていません")
        assertFalse(PROVIDER_A in provisioner.provisionedProviders())
        assertThrows(AdapterNotProvisionedException::class.java) { provisioner.resolve(PROVIDER_A) }

        provisioner.release(PROVIDER_A)
        assertEquals(1, adapter.shutdownCount, "releaseが冪等ではありません（2度shutdownしています）")
    }

    @Test
    fun `resolving a provider that was never provisioned says when the instance would have been created`() {
        val thrown = assertThrows(AdapterNotProvisionedException::class.java) { provisioner.resolve(PROVIDER_A) }
        assertTrue(
            thrown.message.orEmpty().contains("VALIDATING"),
            "いつ生成されるはずだったのかが分からないメッセージです: ${thrown.message}",
        )
    }

    private fun provider(
        providerId: ProviderId,
        secretRef: String,
        baseUrl: String = "https://example.invalid",
    ) = Provider(
        providerId = providerId,
        name = "provider-${providerId.value}",
        adapterPluginId = SHARED_PLUGIN,
        spiVersion = SemVer(1, 1, 0),
        endpoints = listOf(Endpoint("ep1", region, baseUrl, 100)),
        authType = "api_key",
        credentialRefs = listOf(CredentialRef(secretRef, 1, CredentialState.ACTIVE)),
        rateLimits = RateLimits(600, 100_000, 10),
        priority = 50,
        regions = setOf(region),
    )

    private fun ref(
        secretRef: String,
        version: Int = 1,
    ) = CredentialRef(secretRef, version, CredentialState.ACTIVE)

    private companion object {
        const val SHARED_PLUGIN = "plugin-shared"
        val PROVIDER_A = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
        val PROVIDER_B = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    }
}
