package apap.gateway.routes

import apap.gateway.notImplemented
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * HTTPメソッド名（[apap.gateway.catalog.EndpointSpec.method]の文字列）から
 * 対応するKtorのルート定義関数へ委譲する。
 *
 * カタログ側はメソッドを文字列で持つ（設計書13.1の表をそのまま写すため）ので、
 * ここで一箇所だけ文字列→Ktor DSLの対応を持つ。未知のメソッドは黙って無視せず例外にする
 * （無視するとカタログに書いたのにルートが生えず、404になる＝表と実装がズレる）。
 */
internal fun Route.registerNotImplementedRoute(
    method: String,
    path: String,
    reason: String,
) {
    val handler: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
        notImplemented(method, path, reason)
    }
    when (method.uppercase()) {
        "GET" -> get(path) { handler() }
        "POST" -> post(path) { handler() }
        "PUT" -> put(path) { handler() }
        "PATCH" -> patch(path) { handler() }
        "DELETE" -> delete(path) { handler() }
        else -> error("Unsupported HTTP method in the endpoint catalog: $method $path")
    }
}
