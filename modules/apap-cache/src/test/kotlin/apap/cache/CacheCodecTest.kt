package apap.cache

import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Cost
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 分散KVS実装（P8想定）が接ぐ予定のSPI seam。PassthroughCacheCodecは恒等変換のみ行う。 */
class CacheCodecTest {
    @Test
    fun `PassthroughCacheCodec encode and decode are identity operations`() {
        val response =
            CanonicalResponse(
                responseId = "resp-1",
                requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA0"),
                output = listOf(ContentPart.Text("hi")),
                finishReason = FinishReason.COMPLETED,
                usage = Usage.of(TokenCount(1), TokenCount(1)),
                cost = Cost(Money.zero("USD")),
                resolvedProvider = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1"),
                resolvedModel = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2"),
            )
        val codec = PassthroughCacheCodec<CanonicalResponse>()

        val encoded = codec.encode(response)
        assertEquals(response, encoded)
        assertEquals(response, codec.decode(encoded))
    }
}
