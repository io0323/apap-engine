package apap.runtime.fidelity

import apap.adapter.spi.AdapterRequest
import apap.api.ApapRequest
import apap.domain.model.execution.CanonicalRequest
import apap.runtime.fidelity.RequestFidelityContract.Disposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RequestFidelityContract]をクローズドセットとして機械検証する。
 *
 * 狙いは「フィールドを足したのに分類も到達性テストも書かなかった」を**ビルドで落とす**こと。
 * 分類表は書いた時点では正しくても、後からフィールドが増えれば黙って穴が開く——F4/F3は
 * まさにその形（`role`と`outputSchema`が、扱いを決めないまま型にだけ存在した）で生まれた。
 */
class RequestFidelityContractTest {
    @Test
    fun `every ApapRequest field is classified, and nothing is classified that does not exist`() {
        assertClassificationMatches(ApapRequest::class.java, RequestFidelityContract.apapRequestFields.keys)
    }

    @Test
    fun `every CanonicalRequest field is classified, and nothing is classified that does not exist`() {
        assertClassificationMatches(CanonicalRequest::class.java, RequestFidelityContract.canonicalRequestFields.keys)
    }

    @Test
    fun `every field said to reach the adapter names a real AdapterRequest field`() {
        val adapterFields = RequestFidelityContract.declaredFieldsOf(AdapterRequest::class.java)
        val bogus =
            RequestFidelityContract.canonicalRequestFields
                .mapNotNull { (name, disposition) ->
                    val target = (disposition as? Disposition.ReachesAdapter)?.adapterField ?: return@mapNotNull null
                    if (target in adapterFields) null else "$name -> AdapterRequest.$target"
                }
        assertTrue(
            bogus.isEmpty(),
            "存在しないAdapterRequestフィールドを到達先として宣言しています: $bogus。" +
                "実在するフィールド: $adapterFields",
        )
    }

    @Test
    fun `every AdapterRequest field is either filled from the request or declared adapter-only`() {
        val filled =
            RequestFidelityContract.canonicalRequestFields.values
                .filterIsInstance<Disposition.ReachesAdapter>()
                .map { it.adapterField }
                .toSet()
        val accounted = filled + RequestFidelityContract.adapterOnlyFields.keys
        val orphans = RequestFidelityContract.declaredFieldsOf(AdapterRequest::class.java) - accounted
        assertTrue(
            orphans.isEmpty(),
            "どのリクエストフィールドからも埋まらないAdapterRequestフィールドがあります: $orphans。" +
                "リクエストから埋めるなら分類表へ、Adapter側で作るならadapterOnlyFieldsへ理由付きで加えること" +
                "（未対応のまま黙ってProviderへ空値が行くのを防ぐ）。",
        )
        val ghosts =
            RequestFidelityContract.adapterOnlyFields.keys -
                RequestFidelityContract
                    .declaredFieldsOf(AdapterRequest::class.java)
        assertTrue(ghosts.isEmpty(), "存在しないフィールドがadapterOnlyFieldsに残っています: $ghosts")
    }

    @Test
    fun `every field that must not reach the adapter has a probe`() {
        val needProbe =
            RequestFidelityContract.canonicalRequestFields
                .filterValues { it !is Disposition.ReachesAdapter }
                .keys
        val missing = needProbe - RequestFidelityContract.probes.keys
        assertTrue(
            missing.isEmpty(),
            "到達しないと宣言したフィールドに見張り値がありません: $missing。" +
                "実測で追えないなら Probe(sentinel = null, noSentinelReason = ...) で理由を明示すること" +
                "——宣言だけで確かめないのは、F4/F3を生んだのと同じ「書いたつもり」になる。",
        )
        val extra = RequestFidelityContract.probes.keys - needProbe
        assertTrue(extra.isEmpty(), "到達するフィールドに見張り値が残っています（分類変更の取り残し）: $extra")
    }

    @Test
    fun `probe sentinels are distinct enough to be searched for`() {
        val sentinels = RequestFidelityContract.probes.values.mapNotNull { it.sentinel }
        assertEquals(
            sentinels.size,
            sentinels.toSet().size,
            "見張り値が重複しています。重複すると、どのフィールドが漏れたのか判別できません: $sentinels",
        )
        val tooShort = sentinels.filter { it.length < MIN_SENTINEL_LENGTH }
        assertTrue(
            tooShort.isEmpty(),
            "見張り値が短すぎて偶然一致しうる（${MIN_SENTINEL_LENGTH}文字以上にすること）: $tooShort",
        )
    }

    private fun assertClassificationMatches(
        type: Class<*>,
        classified: Set<String>,
    ) {
        val declared = RequestFidelityContract.declaredFieldsOf(type)
        assertTrue(declared.isNotEmpty(), "${type.simpleName}のフィールドを1つも読み取れていません（走査の破綻）")
        val unclassified = declared - classified
        assertTrue(
            unclassified.isEmpty(),
            "${type.simpleName}に分類されていないフィールドがあります: $unclassified。" +
                "RequestFidelityContractへ「Adapterへ到達すべき / 到達してはならない / 内部で消費する」の" +
                "いずれかを理由付きで追加すること。",
        )
        val stale = classified - declared
        assertTrue(
            stale.isEmpty(),
            "${type.simpleName}に存在しないフィールドが分類表に残っています: $stale",
        )
    }

    private companion object {
        const val MIN_SENTINEL_LENGTH = 6
    }
}
