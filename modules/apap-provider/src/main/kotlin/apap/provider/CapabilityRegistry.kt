package apap.provider

import apap.domain.model.capability.CapabilityDefinition
import apap.domain.model.vo.CapabilityId
import apap.domain.port.CapabilityRepository
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

data class RegisterCapabilityCommand(
    val capabilityId: CapabilityId,
    val name: String,
    val inputSchema: String,
    val outputSchema: String,
    val streamable: Boolean,
)

data class SchemaValidationResult(
    val valid: Boolean,
    val errors: List<String>,
)

/**
 * 02_システム仕様.md 2.3 Capability Resolver / 16_拡張ポイント.md 16.9: CapabilityDefinitionの登録と、
 * JSON Schemaによる入出力検証。判断ロジック（公開後の後方互換制約）は[CapabilityDefinition]自身に、
 * スキーマ構造の照合は`com.networknt:json-schema-validator`（公式JSON Schema Test Suiteで
 * 検証済み）に委譲する。
 *
 * ここで検証する入出力はテナントのリクエスト/レスポンスペイロード、すなわち攻撃者が到達可能な
 * 入力境界である。`plugin.yaml`（運用者が書く信頼された小さな設定、PluginManifestParser参照）とは
 * 性質が異なり、自前の簡易パーサ・簡易バリデータで済ませてはならない（深いネストによるスタック
 * オーバーフロー、重複キー、Unicodeエスケープ、数値精度等の典型的な脆弱性に加え、JSON Schemaは
 * 仕様が大きく、$ref/allOf/anyOf/oneOf/patternProperties/formatを黙って無視する「小さなバリデータ」は
 * 「検証していない」より危険——検証済みだと誤信させるため）。
 */
class CapabilityRegistry(
    private val capabilityRepository: CapabilityRepository,
) {
    fun register(command: RegisterCapabilityCommand): CapabilityDefinition {
        val definition =
            CapabilityDefinition(
                capabilityId = command.capabilityId,
                name = command.name,
                inputSchema = command.inputSchema,
                outputSchema = command.outputSchema,
                streamable = command.streamable,
            )
        capabilityRepository.register(definition)
        return definition
    }

    fun validateInput(
        capabilityId: CapabilityId,
        inputJson: String,
    ): SchemaValidationResult = validateAgainst(capabilityId, inputJson) { it.inputSchema }

    fun validateOutput(
        capabilityId: CapabilityId,
        outputJson: String,
    ): SchemaValidationResult = validateAgainst(capabilityId, outputJson) { it.outputSchema }

    private fun validateAgainst(
        capabilityId: CapabilityId,
        json: String,
        schemaOf: (CapabilityDefinition) -> String,
    ): SchemaValidationResult {
        val definition =
            checkNotNull(capabilityRepository.findById(capabilityId)) {
                "CapabilityDefinition not found: $capabilityId"
            }
        return JsonSchemaValidator.validate(schemaOf(definition), json)
    }

    /**
     * 02_システム仕様.md 2.4のCapability一覧を初期登録データとして登録する。
     * 各schemaは`{"type":"object"}`という意図的に最小限のプレースホルダである
     * （2.4は自然言語での概要記述に留まり、フィールド単位のJSON Schemaを定義していないため、
     * 未指定の詳細を憶測で埋めない。将来のRequest/Response Mapper実装時に段階的に拡充する前提）。
     * `streamable`のみ2.4の「Streaming」列の値をそのまま反映する。
     */
    fun seedInitialCapabilities(): List<CapabilityDefinition> =
        INITIAL_CAPABILITIES.map { (id, name, streamable) ->
            register(
                RegisterCapabilityCommand(
                    capabilityId = CapabilityId(id),
                    name = name,
                    inputSchema = PLACEHOLDER_SCHEMA,
                    outputSchema = PLACEHOLDER_SCHEMA,
                    streamable = streamable,
                ),
            )
        }

    companion object {
        private const val PLACEHOLDER_SCHEMA = """{"type":"object"}"""

        // Draft 2020-12（最新かつ最上位互換）を既定とする。個々のCapabilityスキーマが独自の
        // `$schema`を宣言している場合は、networknt/json-schema-validatorがそちらを優先する。
        private val SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

        private val INITIAL_CAPABILITIES: List<Triple<String, String, Boolean>> =
            listOf(
                Triple("chat", "マルチターン対話", true),
                Triple("completion", "単発テキスト補完", true),
                Triple("structured_output", "Schema準拠JSON生成", true),
                Triple("function_calling", "Function定義に基づく呼出指示", true),
                Triple("tool_calling", "Tool定義に基づく呼出指示・結果返信", true),
                Triple("embedding", "ベクトル生成", false),
                Triple("vision", "画像入力理解", true),
                Triple("image_generation", "画像生成", false),
                Triple("image_editing", "画像編集", false),
                Triple("image_analysis", "画像解析（分類・OCR等）", false),
                Triple("speech_to_text", "音声認識", true),
                Triple("text_to_speech", "音声合成", true),
                Triple("audio_translation", "音声翻訳", false),
                Triple("video_analysis", "動画解析", false),
                Triple("video_generation", "動画生成", false),
                Triple("reasoning", "推論強化生成", true),
                Triple("memory", "長期記憶保存/検索", false),
                Triple("rag", "検索拡張生成", true),
                Triple("fine_tuning", "チューニングジョブ管理", false),
                Triple("batch", "非同期一括処理", false),
            )
    }
}

/**
 * 任意のJSON Schemaに対する検証。[CapabilityRegistry]（登録済みCapabilityのスキーマ）と、
 * リクエスト単位の`outputSchema`（FR-CAP-003 Structured Output）の両方が使う。
 *
 * 検証器を`com.networknt:json-schema-validator`へ委譲する理由は[CapabilityRegistry]のKDoc参照
 * ——ここで扱うのは攻撃者が到達可能な入力境界であり、自前の簡易バリデータは
 * 「検証していない」より危険（検証済みだと誤信させる）。
 */
object JsonSchemaValidator {
    // TooGenericExceptionCaught: 検証器が投げうる例外は不正スキーマ・JSON構文エラー・
    // 内部状態エラーと多岐にわたり、取りこぼすと「検証していない応答」を通してしまう。
    // 種類ではなく「検証できなかった」という事実だけを見て、常に不適合として扱う。
    @Suppress("TooGenericExceptionCaught")
    fun validate(
        schema: String,
        json: String,
    ): SchemaValidationResult =
        try {
            val messages = SCHEMA_FACTORY.getSchema(schema).validate(json, InputFormat.JSON)
            SchemaValidationResult(valid = messages.isEmpty(), errors = messages.map { it.message })
        } catch (e: RuntimeException) {
            // スキーマ自体が不正、または検証対象がJSONとして壊れている場合。
            // 「検証できなかった」を「検証に通った」と読み替えてはならない。
            SchemaValidationResult(valid = false, errors = listOf(e.message ?: e::class.simpleName.orEmpty()))
        }

    private val SCHEMA_FACTORY: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
}
