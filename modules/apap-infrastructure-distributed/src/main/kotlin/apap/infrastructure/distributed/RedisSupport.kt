package apap.infrastructure.distributed

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object RedisSupport {
    /**
     * ADR-0017: Jacksonに一本化。[apap.infrastructure.jdbc.JdbcSupport.objectMapper]と同じ構成。
     * `FAIL_ON_UNKNOWN_PROPERTIES`を無効化する: apap-domainのVOはgetter専用の計算プロパティ
     * （例: `WindowStats.failureRate`）を持つことがあり、Jacksonの既定シリアライズはこれも
     * JSONフィールドとして出力するが、コンストラクタ引数ではないため逆シリアライズ時に
     * `UnrecognizedPropertyException`となる。ドメイン層にJackson注釈を持ち込めない
     * （依存ゼロ原則）ため、Infrastructure層のこのフラグで吸収する。
     */
    val objectMapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
