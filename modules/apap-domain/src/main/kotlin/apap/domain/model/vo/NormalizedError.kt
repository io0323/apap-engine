package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: code, category, message, retryable, fallbackable, providerDetail?。
 * codeは13章のエラー体系（[ErrorCode]）。categoryはAdapterException分類（[AdapterErrorCategory]）。
 *
 * [cbRecordable] は02_システム仕様.md 2.11「CB記録」列の**カテゴリ単位の基本可否**を表す
 * （4.4の代表フィールド一覧にはないが、2.11がRetry/Fallbackと並置している同一表の一部であり、
 * 2.13のCircuit Breaker実装（apap-execution）がこの表を再実装せず参照できるようにするための
 * フィールド追加。RATE_LIMITEDの「△（連続時のみ）」は本フィールドではtrueとし、
 * 「直前も同一カテゴリで失敗した場合のみ実際に記録する」という文脈依存の絞り込みは
 * 状態を持つCircuitBreaker（apap-execution）側の責務とする）。
 *
 * [retryAfterMs] も4.4の一覧にないフィールド追加。`AdapterException.retryAfter`（Provider由来の
 * 再試行猶予）とローカルRateLimiterの待機上限（`AcquireResult.Rejected.maxWaitMillis`）は、
 * それぞれの発生源には存在するのに本正規化を経由すると捨てられ、13.4のAPIエラー応答が要求する
 * `retry_after_ms`をGateway側（P10）が組み立てる際に上流まで遡って配線し直す羽目になっていた。
 * ドメイン層への追加変更で済むうちに受け皿を用意しておく（着手前レビュー指摘、2026-08-22）。
 */
data class NormalizedError(
    val code: ErrorCode,
    val category: AdapterErrorCategory,
    val message: String,
    val retryable: Boolean,
    val fallbackable: Boolean,
    val cbRecordable: Boolean,
    val retryAfterMs: Long? = null,
    val providerDetail: String? = null,
) {
    init {
        require(message.isNotBlank()) { "NormalizedError.message must not be blank" }
    }
}
