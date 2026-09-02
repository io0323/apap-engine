# syntax=docker/dockerfile:1
#
# gateway/apap-gateway のコンテナイメージ（11_デプロイメント図.md）。
#
# ビルドは2段階に分ける。1段目でGradleの`installDist`を実行し、2段目にはJREと生成物だけを置く
# （Gradle・ソース・ビルドキャッシュを実行イメージへ持ち込まない）。

# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# 依存解決の結果をレイヤキャッシュへ載せるため、ビルド定義を先にコピーする。
# ソース変更のたびに依存を取り直さないための順序。
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY modules modules
COPY adapters adapters
COPY gateway gateway
COPY integration integration
COPY config config

RUN ./gradlew --no-daemon :gateway:apap-gateway:installDist

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre

# rootで動かさない。Kubernetesの`runAsNonRoot`と整合させる。
RUN groupadd --system apap && useradd --system --gid apap --home /app apap

WORKDIR /app
COPY --from=build --chown=apap:apap /workspace/gateway/apap-gateway/build/install/apap-gateway/ /app/

USER apap

# 13章共通事項のBase Path配下を待ち受ける既定ポート（APAP_GATEWAY_PORTで変更可能）。
EXPOSE 8080

# ADR-0004: 認証設定が無ければ起動を拒否する（無認証公開への縮退を作らない）ので、
# 実行時に APAP_AUTH_ISSUER / APAP_AUTH_AUDIENCE / APAP_AUTH_JWKS_URI を必ず与えること。
#
# グレースフルシャットダウン（本タスク指示9 / 2.10）:
# - SIGTERMを受けるとMain.ktのシャットダウンフックが
#   server.stop(gracePeriod, timeout) -> engine.close() の順で停止する。
#   この順序が重要で、逆にすると処理中のHTTPリクエストが「新規」として拒否される。
# - Streamingは全体既定300s（2.10）まで猶予を取るため、Kubernetes側の
#   terminationGracePeriodSeconds は 300 より大きく設定すること（例: 330）。
#   短いとPodがSIGKILLされ、in-flightのStreamingが途中で切れる。
# - PreStopフックは不要: Ktorのstopが新規受付停止とin-flight完遂を行う。
#   LBからの切り離しを待ちたい場合のみ `sleep` のPreStopを足す。
STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/bin/apap-gateway"]
