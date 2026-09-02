package apap.hostcompat

import apap.api.ApapException
import apap.api.ApapRequest
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.TenantId
import apap.runtime.ApapEngine
import kotlinx.coroutines.runBlocking
import java.time.Duration

/**
 * `docs/integration/prompt-engine.md` 2-b章のコード例の実体。
 *
 * ホスト側のPort（[ExecutionAdapter]）実装として`ApapEngine`を注入する推奨パターン。
 * **このファイルが実際にコンパイルされること自体が検証**である——P9では
 * `apap.execution.ExecutionFailedException`（ホストから見えない内部型）をimportする例を
 * 載せてしまい、ドキュメントが検査対象外だったため気づけなかった（ADR-0029）。
 */
// docs:begin execution-adapter
class ApapExecutionAdapter(
    private val apapEngine: ApapEngine,
    private val tenantId: TenantId,
    private val capabilityId: CapabilityId = CapabilityId("chat"),
) : ExecutionAdapter {
    /**
     * ホスト側の`execute`は非suspend、`ApapEngine.execute`はsuspend。
     * `runBlocking`でのブリッジをこの境界1箇所に閉じ込める。
     */
    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse {
        val startNanos = System.nanoTime()
        return try {
            val response =
                runBlocking {
                    apapEngine.execute(
                        ApapRequest(
                            tenantId = tenantId,
                            principal = "prompt-engine",
                            capabilityId = capabilityId,
                            input = prompt.messages.map { ContentPart.Text(it.content) },
                            timeoutBudget = Duration.ofMillis(policy.timeoutMs),
                        ),
                    )
                }
            RawResponse(
                content =
                    SensitiveValue.of(
                        response.output.filterIsInstance<ContentPart.Text>().joinToString("") { it.text },
                    ),
                usage =
                    HostUsage(
                        HostTokenCount(response.usage.inputTokens.value),
                        HostTokenCount(response.usage.outputTokens.value),
                    ),
                latency = LatencyMs((System.nanoTime() - startNanos) / NANOS_PER_MILLI),
            )
        } catch (e: ApapException) {
            // 実行系の失敗はすべて apap.api.ApapException へ正規化されている。
            // 内部例外（apap.execution.* 等）はホストから見えないのでcatchしてはならない。
            throw ExecutionFailedException(e.error.toExecutionErrorType(), retryCount = 0, cause = e)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
// docs:end execution-adapter

// docs:begin error-mapping
/**
 * 13.4のコード体系（`NormalizedError`が保持）からホスト側の分類へ写す。
 *
 * **エラー分類をやり直さない**のが要点。retryableかどうかはAPAP側（2.11の表）で確定済みで、
 * `CONNECT_TIMEOUT`/`READ_TIMEOUT`のような「未送信と言い切れるか」の判定も
 * Provider⇔APAP間の境界でAPAPが済ませている。ホストは`retryable`を読むだけでよい。
 */
fun NormalizedError.toExecutionErrorType(): ExecutionErrorType =
    when {
        category == AdapterErrorCategory.RATE_LIMITED -> ExecutionErrorType.RATE_LIMITED
        retryable -> ExecutionErrorType.SERVER_ERROR
        else -> ExecutionErrorType.CLIENT_ERROR
    }
// docs:end error-mapping
