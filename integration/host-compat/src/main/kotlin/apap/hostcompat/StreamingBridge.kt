package apap.hostcompat

import apap.api.ApapRequest
import apap.api.ApapStreamChunk
import apap.api.ApapStreamChunkType
import apap.domain.model.vo.ContentPart
import apap.runtime.ApapEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking

/**
 * `docs/integration/prompt-engine.md` 3章のコード例の実体。
 *
 * prompt-engineは現時点でcoroutines/`Flow`を使っていないため、Streamingの取り込み方は
 * ホスト側のアーキテクチャ判断になる。ここでは**APAP側から見て型として成立する**
 * 2通りの橋渡しを、実際にコンパイルされる形で示す。どちらを採るかはホストが決める。
 */
object StreamingBridge {
    // docs:begin streaming-flow
    /**
     * 方式1: `Flow`のまま渡す。ホストがcoroutinesを受け入れられるなら最も素直で、
     * バックプレッシャ（2.10のpull型）もそのまま活きる。
     * テキストデルタだけを取り出す例。
     */
    fun textDeltas(
        engine: ApapEngine,
        request: ApapRequest,
    ): Flow<String> =
        engine
            .executeStream(request)
            .mapNotNull { chunk ->
                if (chunk.type == ApapStreamChunkType.CONTENT_DELTA) {
                    (chunk.delta as? ContentPart.Text)?.text
                } else {
                    null
                }
            }
    // docs:end streaming-flow

    // docs:begin streaming-callback
    /**
     * 方式2: コールバックで押し出す。非同期を持ち込みたくないホスト向け。
     *
     * **注意**: `runBlocking`はストリーム全体を1スレッドで待つ。SSE等へ逐次書き出すなら
     * 呼び出し側が専用スレッド/ディスパッチャで実行すること（そうしないと
     * 「逐次」の意味が失われ、全チャンク受信後にまとめて処理される形になる）。
     */
    fun forEachChunk(
        engine: ApapEngine,
        request: ApapRequest,
        onChunk: (ApapStreamChunk) -> Unit,
    ) {
        runBlocking {
            engine.executeStream(request).collect { chunk -> onChunk(chunk) }
        }
    }
    // docs:end streaming-callback
}
