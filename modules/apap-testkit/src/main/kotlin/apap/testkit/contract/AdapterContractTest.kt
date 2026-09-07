package apap.testkit.contract

import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterException
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.ProviderAdapter
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.FinishReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.Instant

/**
 * 15_Provider追加手順.md 15.4 / 16_拡張ポイント.md 16.1: 任意の[ProviderAdapter]実装が満たすべき
 * 共通契約（Contract Test）。具体のAdapter実装（例: `adapters/adapter-mock`）はこのクラスを継承し、
 * [createAdapter]と各種プローブ用フックを実装/上書きする。
 *
 * 一部の検証（エラー分類毎の再現、Credentialリーク検査、タイムアウト遵守、Stream中断）は、
 * Adapter実装が意図的にその状況を再現できる場合にのみ意味を持つため、対応するフックが
 * `null`（既定）のままの場合はその検証を`Assumptions`でスキップする。フックを実装するほど
 * このContract Testで検証できる範囲が広がる。
 *
 * テストメソッド名はリポジトリ全体の規約（バッククォートで文章として記述する）に従うため`FunctionNaming`を、
 * 15.4のContract Test要件を1クラスに集約する設計のため`TooManyFunctions`を、それぞれ抑制する。
 */
@Suppress("FunctionNaming", "TooManyFunctions")
abstract class AdapterContractTest {
    /** 新規（未initialize）または initialize 済みのAdapterインスタンスを返す。 */
    protected abstract fun createAdapter(): ProviderAdapter

    /** [ProviderAdapter.supportedCapabilities]が申告する各Capabilityに対する、成功しうる最小限のリクエスト。 */
    protected abstract fun supportedCapabilityRequests(): Map<CapabilityId, AdapterRequest>

    /** 申告されていないCapabilityへのリクエスト。用意できないAdapterは`null`のままでよい（検証はスキップ）。 */
    protected open fun unsupportedCapabilityRequest(): AdapterRequest? = null

    /** 指定分類のエラーを確実に再現するリクエスト。再現できないAdapterは`null`のままでよい（検証はスキップ）。 */
    protected open fun errorRequestFor(category: AdapterErrorCategory): AdapterRequest? = null

    /** [errorRequestFor]の実行結果（例外メッセージ・toString等）に絶対に現れてはならない秘密値。 */
    protected open fun secretProbeValue(): String? = null

    /** `request.timeout`を確実に超過する所要時間を持つリクエスト。用意できないAdapterは`null`のままでよい。 */
    protected open fun timeoutExceedingRequest(): AdapterRequest? = null

    /** Streamを開始できるリクエスト。用意できないAdapterは`null`のままでよい（cancel検証をスキップ）。 */
    protected open fun streamRequest(): AdapterRequest? = null

    /** [ProviderAdapter.healthCheck]が返るまでに許容する最大時間。 */
    protected open fun healthCheckMaxDuration(): Duration = Duration.ofSeconds(HEALTH_CHECK_DEFAULT_SECONDS)

    /**
     * コンテンツ拒否（セーフティ拒否）が**どちらの経路で表面化するか**の申告（ADR-0037）。
     *
     * 設計書は同じ概念を2箇所に持つ。2.9のFinishReason 6値（応答側）と、2.11のエラー分類（例外側）。
     * どちらで来るかはProviderによって違うため、**Adapterが宣言し、それに応じて検証する**。
     * 宣言しないことは選べない——「再現できないからスキップ」を許すと、緑のまま
     * 「コンテンツ拒否の扱いを一度も確かめていない」状態になる。
     */
    protected abstract fun contentFilteringSurface(): ContentFilteringSurface

    /** [ProviderAdapter.estimateTokens]呼出に使うサンプル入力。 */
    protected open fun estimateTokensSampleInput(): List<ContentPart> = listOf(ContentPart.Text("sample input"))

    @Test
    fun `supportedCapabilities is consistent with execute`() =
        runBlocking {
            val adapter = createAdapter()
            val requests = supportedCapabilityRequests()
            assertTrue(requests.isNotEmpty(), "supportedCapabilityRequests() must not be empty")

            requests.forEach { (capabilityId, request) ->
                assertTrue(
                    capabilityId in adapter.supportedCapabilities(),
                    "supportedCapabilities() does not declare $capabilityId even though a request for it was supplied",
                )
                val thrown =
                    runCatching { adapter.execute(request) }
                        .exceptionOrNull() as? AdapterException
                assertTrue(
                    thrown?.category != AdapterErrorCategory.UNSUPPORTED_CAPABILITY,
                    "execute() rejected a declared-supported capability ($capabilityId) as UNSUPPORTED_CAPABILITY",
                )
            }
        }

    @Test
    fun `execute on an unsupported capability throws UNSUPPORTED_CAPABILITY`() =
        runBlocking {
            val request = unsupportedCapabilityRequest()
            assumeTrue(request != null, "unsupportedCapabilityRequest() not provided by this adapter's test")
            val adapter = createAdapter()

            val exception =
                assertThrows(AdapterException::class.java) {
                    runBlocking { adapter.execute(request!!) }
                }
            assertEquals(AdapterErrorCategory.UNSUPPORTED_CAPABILITY, exception.category)
        }

    /**
     * CONTENT_FILTEREDは除外する。Providerによって例外／正常応答のどちらでも来うるため、
     * 専用の`content filtering surfaces the way this adapter declares it does`で申告どおりに検証する。
     */
    @TestFactory
    fun `each error category maps to the matching AdapterException category`(): List<DynamicTest> =
        AdapterErrorCategory.entries.filterNot { it == AdapterErrorCategory.CONTENT_FILTERED }.map { category ->
            DynamicTest.dynamicTest("category=$category") {
                val request = errorRequestFor(category)
                assumeTrue(request != null, "errorRequestFor($category) not provided by this adapter's test")
                val adapter = createAdapter()

                val exception =
                    assertThrows(AdapterException::class.java) {
                        runBlocking { adapter.execute(request!!) }
                    }
                assertEquals(category, exception.category)
            }
        }

    @Test
    fun `execute honors AdapterRequest timeout`() =
        runBlocking {
            val request = timeoutExceedingRequest()
            assumeTrue(request != null, "timeoutExceedingRequest() not provided by this adapter's test")
            val adapter = createAdapter()

            val start = Instant.now()
            assertThrows(AdapterException::class.java) {
                runBlocking { adapter.execute(request!!) }
            }
            val elapsed = Duration.between(start, Instant.now())
            assertTrue(
                elapsed <= request!!.timeout.plus(TIMEOUT_ENFORCEMENT_SLACK),
                "execute() took $elapsed, exceeding the requested timeout of ${request.timeout} " +
                    "by more than the allowed slack ($TIMEOUT_ENFORCEMENT_SLACK)",
            )
        }

    @Test
    fun `executeStream cancel stops further chunks from being delivered`() =
        runBlocking {
            val request = streamRequest()
            assumeTrue(request != null, "streamRequest() not provided by this adapter's test")
            val adapter = createAdapter()

            val stream = adapter.executeStream(request!!)
            stream.cancel()
            val chunkAfterCancel = stream.next()
            assertTrue(
                chunkAfterCancel == null || chunkAfterCancel.type == AdapterChunkType.ERROR,
                "next() kept delivering ordinary chunks after cancel(): $chunkAfterCancel",
            )
        }

    @Test
    fun `credentials never leak into exception messages or captured output`() =
        runBlocking {
            val secret = secretProbeValue()
            val request = errorRequestFor(AdapterErrorCategory.AUTH_ERROR)
            assumeTrue(secret != null, "secretProbeValue() not provided by this adapter's test")
            assumeTrue(request != null, "errorRequestFor(AUTH_ERROR) not provided by this adapter's test")
            val adapter = createAdapter()

            val originalOut = System.out
            val originalErr = System.err
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))
            System.setErr(PrintStream(captured))
            val exception =
                try {
                    assertThrows(AdapterException::class.java) {
                        runBlocking { adapter.execute(request!!) }
                    }
                } finally {
                    System.setOut(originalOut)
                    System.setErr(originalErr)
                }

            val haystack = "${exception.message}|${exception.providerDetail}|$exception|$captured"
            assertFalse(haystack.contains(secret!!), "Credential value leaked into adapter output: $haystack")
        }

    /**
     * 15.4「エラー分類」のうちCONTENT_FILTEREDだけは、Providerによって
     * 例外／正常応答のどちらでも来うる。[contentFilteringSurface]の申告どおりに届くことを確認する。
     */
    @Test
    fun `content filtering surfaces the way this adapter declares it does`() =
        runBlocking {
            when (val surface = contentFilteringSurface()) {
                is ContentFilteringSurface.AsException -> {
                    val adapter = createAdapter()
                    val exception =
                        assertThrows(AdapterException::class.java) {
                            runBlocking { adapter.execute(surface.request) }
                        }
                    assertEquals(AdapterErrorCategory.CONTENT_FILTERED, exception.category)
                }
                is ContentFilteringSurface.AsFinishReason -> {
                    val adapter = createAdapter()
                    val response = adapter.execute(surface.request)
                    assertEquals(
                        FinishReason.CONTENT_FILTERED,
                        response.finishReason,
                        "拒否が正常応答として返る宣言なのに、finishReasonがCONTENT_FILTEREDではありません",
                    )
                }
                is ContentFilteringSurface.NotReachable -> {
                    // 「このProviderでは再現手段が無い」ことを理由付きで宣言した場合のみ許す。
                    assertTrue(
                        surface.reason.isNotBlank(),
                        "再現できない理由を書かない宣言は、単なる検証漏れと区別できません",
                    )
                }
            }
        }

    /**
     * ストリームの終端が終了理由を運ぶこと（13.3 `message_end` の `finish_reason`）。
     *
     * これが無いと **`length_limit`で切られたストリームが正常完了と区別できない**。
     * Streamを用意できないAdapterはスキップする。
     */
    @Test
    fun `the terminal stream chunk carries a finish reason`() =
        runBlocking {
            val request = streamRequest()
            assumeTrue(request != null, "streamRequest() not provided by this adapter's test")
            val stream = createAdapter().executeStream(request!!)
            val chunks = buildList { while (true) add(stream.next() ?: break) }
            val terminal = chunks.lastOrNull { it.type == AdapterChunkType.MESSAGE_END }
            assumeTrue(terminal != null, "this adapter's stream does not emit MESSAGE_END")
            assertNotNull(
                terminal!!.finishReason,
                "MESSAGE_ENDに終了理由が載っていません。載せないと length_limit と正常完了が区別できません",
            )
        }

    @Test
    fun `healthCheck responds within the expected time`() =
        runBlocking {
            val adapter = createAdapter()
            val start = Instant.now()
            val result = adapter.healthCheck()
            val elapsed = Duration.between(start, Instant.now())
            assertTrue(
                elapsed <= healthCheckMaxDuration(),
                "healthCheck() took $elapsed, exceeding the allowed ${healthCheckMaxDuration()}",
            )
            assertTrue(!result.latency.isNegative, "HealthResult.latency must not be negative")
        }

    @Test
    fun `estimateTokens is either unimplemented (null) or returns a non-negative estimate`() =
        runBlocking {
            val adapter = createAdapter()
            val estimate = adapter.estimateTokens(estimateTokensSampleInput())
            if (estimate == null) {
                assertNull(estimate)
            } else {
                assertTrue(estimate.value >= 0, "estimateTokens() returned a negative TokenCount: $estimate")
            }
        }

    companion object {
        private const val HEALTH_CHECK_DEFAULT_SECONDS = 5L
        private val TIMEOUT_ENFORCEMENT_SLACK = Duration.ofSeconds(2)
    }
}
