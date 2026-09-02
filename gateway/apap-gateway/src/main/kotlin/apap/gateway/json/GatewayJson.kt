package apap.gateway.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * 13_API設計.md 13.2/13.3のJSON表現に合わせたObjectMapper。
 *
 * - フィールド名はsnake_case（13.2/13.3・13.4の例がすべてsnake_case）。Kotlin側はcamelCaseで書き、
 *   ここで一括変換する（DTOごとに`@JsonProperty`を書くと付け忘れが静かに混ざる）。
 * - `null`は出力しない: 13.3の`tool_calls`や13.4の`retry_after_ms`は「無い場合は出さない」
 *   のが自然で、`null`を明示すると必須フィールドとの区別が付きにくくなる。
 *   ただし13.3の`"tool_calls": null`のように**明示的にnullが例示されている**箇所があるため、
 *   完全な省略ではなくNON_NULLに留める判断の根拠をここに残す
 *   （13.3のChat応答例はnullを出しているが、Tool Calling応答例では`tool_calls`が値を持つ。
 *   どちらの例もクライアントは「キーの有無」ではなく「値の有無」で分岐できるため、
 *   NON_NULLで実害がない。ADR化するほどの要件影響は無いと判断した）。
 * - 未知フィールドで失敗しない: クライアントが将来のフィールドを送っても400にしない
 *   （13.4の`INVALID_REQUEST`はスキーマ違反に使うが、未知フィールドの混入は違反としない）。
 */
object GatewayJson {
    val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
