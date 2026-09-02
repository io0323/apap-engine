package apap.hostcompat

/**
 * prompt-engine側の型の**写し**（mirror）。
 *
 * このモジュールの目的は「ホストが持つ依存だけでAPAP側の呼び出しコードがコンパイルできるか」
 * を検証することであり、prompt-engineそのものをここへ持ち込むことではない
 * （prompt-engineは別リポジトリで、依存に加えると検証したい境界が壊れる）。
 *
 * そこでprompt-engine側のPortとVOの**シグネチャだけ**を最小限で再現する。
 * 2026-09-02時点の`/Users/io/projects/GitHub/engine/prompt-engine`の現物と対応:
 *
 * | ここでの型 | prompt-engine側の型 |
 * |---|---|
 * | [ExecutionAdapter] | `promptengine.domain.execution.ExecutionAdapter` |
 * | [RenderedPrompt] | `promptengine.domain.render.RenderedPrompt` |
 * | [RenderedMessage] | `promptengine.domain.render.RenderedMessage` |
 * | [ExecutionPolicy] | `promptengine.domain.execution.ExecutionPolicy` |
 * | [RawResponse] | `promptengine.domain.execution.RawResponse` |
 * | [HostUsage] | `promptengine.domain.execution.Usage` |
 * | [ExecutionErrorType] | `promptengine.domain.execution.ExecutionErrorType` |
 * | [ExecutionFailedException] | `promptengine.domain.execution.ExecutionFailedException` |
 *
 * **写しがズレると検証が嘘になる**ため、prompt-engine側のシグネチャを変更した場合は
 * ここも追従させること。ここは「APAP側の呼び出しコードが型として成立するか」を見る足場であり、
 * prompt-engineの振る舞いを再現するものではない（本体のロジックは持たない）。
 */
fun interface ExecutionAdapter {
    /** prompt-engine側は**非suspend**（同リポジトリは現時点でcoroutinesを使っていない）。 */
    fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse
}

data class RenderedMessage(
    val role: String,
    val content: String,
)

data class RenderedPrompt(
    val messages: List<RenderedMessage>,
)

data class ExecutionPolicy(
    val timeoutMs: Long,
    val maxRetries: Int = 2,
)

data class HostTokenCount(
    val value: Int,
)

data class HostUsage(
    val inputTokens: HostTokenCount,
    val outputTokens: HostTokenCount,
)

data class LatencyMs(
    val value: Long,
)

/** prompt-engine側は`SensitiveValue`でラップしている。ここでは境界の形だけを再現する。 */
class SensitiveValue private constructor(
    private val raw: String,
) {
    fun expose(): String = raw

    override fun toString(): String = "***"

    companion object {
        fun of(raw: String): SensitiveValue = SensitiveValue(raw)
    }
}

data class RawResponse(
    val content: SensitiveValue,
    val usage: HostUsage,
    val latency: LatencyMs,
    val retryCount: Int = 0,
)

enum class ExecutionErrorType {
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    CONNECTION_FAILURE,
    RATE_LIMITED,
    SERVER_ERROR,
    CLIENT_ERROR,
    UNKNOWN,
}

class ExecutionFailedException(
    val errorType: ExecutionErrorType,
    val retryCount: Int,
    cause: Throwable? = null,
) : RuntimeException("EXECUTION_FAILED: errorType=$errorType retryCount=$retryCount", cause)
