package apap.adapter.anthropic

import java.time.Duration

/**
 * Adapterが実HTTPを話すための唯一の出口。
 *
 * ## なぜインタフェースにするのか
 *
 * 15.1 Step3が求める「record/replay可能なProviderスタブ」を成立させるための継ぎ目である。
 * ここを差し替えるだけで、**Adapter本体のコードは実APIに対してもrecordingに対しても
 * 完全に同一の経路を通る**。HTTPクライアントの内側にモックを差し込む方式だと、
 * 再生時にAdapterの一部（ヘッダ組立・SSE解析・エラー分類）が迂回されてしまい、
 * 「再生では通るが実APIでは落ちる」を作り込む。
 *
 * 実装は2つ:
 * - [KtorHttpTransport] 実API向け
 * - `ReplayHttpTransport`（testソース）記録済みのやりとりを返す
 */
interface HttpTransport {
    suspend fun send(request: HttpCall): HttpReply

    /** SSEを**pull型**で開く。SPIの[apap.adapter.spi.ProviderAdapter.AdapterStream]へそのまま写せる形。 */
    suspend fun openEventStream(request: HttpCall): EventStream

    fun close()
}

/**
 * 1回のHTTP呼出。[headers]にはCredentialを含むため、**ログ出力・toString生成を避ける**
 * （01_CLAUDE.md 不変条件4）。data classにしていないのはそのため——自動生成の
 * `toString()`がヘッダ値を丸ごと文字列化してしまう。
 */
class HttpCall(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeout: Duration,
) {
    /** Credentialを含みうるため、ヘッダ値と本文は絶対に出さない。 */
    override fun toString(): String = "HttpCall($method $path)"
}

/**
 * HTTPレスポンス。エラー分類（[ErrorMapper]）が[status]と[headers]と[body]の3つを見るため、
 * 生の形のまま持ち回る（Adapter内部に閉じた型で、SPIへは漏れない）。
 */
data class HttpReply(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** SSEの1イベント。`event:` 行が無いストリームもあるため[event]はnull許容。 */
data class ServerSentEvent(
    val event: String?,
    val data: String,
)

/**
 * pull型のSSE購読。[next]がnullを返したら終端。
 *
 * [cancel]はProviderへの接続を確実に切る（15.1 Step1「`cancel()`でProvider接続を確実に切断」/
 * 02_システム仕様.md 2.10「クライアント切断時はProviderへキャンセル伝播」）。
 */
interface EventStream {
    suspend fun next(): ServerSentEvent?

    fun cancel()
}
