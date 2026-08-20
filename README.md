# AI Provider Abstraction Platform（APAP）設計書

AIを利用するすべてのシステムで共通利用する「AI Provider抽象化基盤」の設計書。特定のAI Provider・ベンダー・製品に依存せず、Provider / Model / Version / Capabilityの変更・追加をアプリケーションへ無影響で実現する。

**位置付け**: AACP（AI Agent Common Platform）= AIの使い方、CIAP（Common Identity & Access Platform）= 認証認可、**APAP（本書）= AIを提供する相手の抽象化（AI Gateway / AI Fabric）**。

## 目次

| 章 | ファイル | 内容 |
|---|---|---|
| 1 | [01_要件定義.md](01_要件定義.md) | 概要 / 目的 / 適用範囲 / 対象外 / 利用シナリオ / ユースケース / 機能要件（FR-xxx） / 非機能要件（NFR-xxx） |
| 2 | [02_システム仕様.md](02_システム仕様.md) | 全体構成 / アーキテクチャ / レイヤ・モジュール構成 / 責務一覧 / Capability一覧 / Routing仕様 / Provider・Model管理 / Request・Response・Streaming Flow / Retry / Fallback / Circuit Breaker / Cache / Session / Conversation / Memory / Audit / Monitoring |
| 3 | [03_基本設計.md](03_基本設計.md) | パッケージ・ディレクトリ構成 / 主要クラス / Interface（疑似コード） / Repository / Service / Factory / Builder / Strategy / Adapter / Facade / Observer / Command / State / Policy / DI構成 |
| 4 | [04_ドメイン設計.md](04_ドメイン設計.md) | Bounded Context / Context Map / Entity / Aggregate / Value Object / Repository / Domain Service / Domain Event / ER（論理） |
| 5 | [05_シーケンス設計.md](05_シーケンス設計.md) | Chat / Embedding / Streaming / Tool Calling / Function Calling / Fallback / Retry / Provider切替 / Capability Discovery / Health Check（PlantUML） |
| 6 | [06_クラス図.md](06_クラス図.md) | コア実行系 / Provider・Model管理 / Context・Cost（PlantUML） |
| 7 | [07_コンポーネント図.md](07_コンポーネント図.md) | 全体コンポーネントと接続契約（PlantUML） |
| 8 | [08_パッケージ図.md](08_パッケージ図.md) | モジュール依存と依存規則（PlantUML） |
| 9 | [09_状態遷移図.md](09_状態遷移図.md) | Provider / Model / Circuit Breaker / Request / Batch Job / Stream / Credential（PlantUML） |
| 10 | [10_アクティビティ図.md](10_アクティビティ図.md) | 実行全体 / Fallback / Routing決定 / Provider追加 / Streaming（PlantUML） |
| 11 | [11_デプロイメント図.md](11_デプロイメント図.md) | Kubernetes構成 / マルチリージョン / スケール方針（PlantUML） |
| 12 | [12_ER図.md](12_ER図.md) | 物理データモデル（PlantUML） |
| 13 | [13_API設計.md](13_API設計.md) | REST API / Request / Response / Error / HTTP Status |
| 14 | [14_イベント一覧.md](14_イベント一覧.md) | イベント名 / 発火元 / 購読先 / 用途 |
| 15 | [15_Provider追加手順.md](15_Provider追加手順.md) | 新Provider / 新Model / 新Capability追加手順、Go-Liveチェックリスト |
| 16 | [16_拡張ポイント.md](16_拡張ポイント.md) | Plugin / Routing / Policy / Cache / Retry / Monitoring / Prompt / Provider / Capability の拡張SPI |

## 読み方ガイド

| 読者 | 推奨順 |
|---|---|
| アーキテクト | 1 → 2 → 4 → 7 → 8 → 11 → 16 |
| 実装者（コア） | 2 → 3 → 6 → 5 → 9 → 10 → 13 |
| Adapter開発者 | 3.3.2（SPI） → 15 → 16 → 5 |
| 運用者 / SRE | 2.6〜2.19 → 9 → 11 → 14 → 15 |

## 設計原則（全章共通）

Provider Independent / Model Independent / Vendor Neutral / Capability Driven / API First / Plugin Architecture / Event Driven / Cloud Native / DDD / Clean Architecture / SOLID / CQRS / Event Sourcing（構成系・ジョブ系に適用）。

図はすべてPlantUML。特定製品名・ベンダー名は設計に含まれない。
