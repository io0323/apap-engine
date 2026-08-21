package apap.execution.streaming

/** 02_システム仕様.md 2.10。CLAUDE.md不変条件7に従い設定可能、既定値は設計書と一致させる。 */
data class StreamingConfig(
    val heartbeatSeconds: Long = 15,
    val idleTimeoutSeconds: Long = 60,
    val overallTimeoutSeconds: Long = 300,
    val backpressureBufferBytes: Int = 262_144,
) {
    init {
        require(heartbeatSeconds > 0) { "heartbeatSeconds must be positive: $heartbeatSeconds" }
        require(idleTimeoutSeconds > 0) { "idleTimeoutSeconds must be positive: $idleTimeoutSeconds" }
        require(overallTimeoutSeconds > 0) { "overallTimeoutSeconds must be positive: $overallTimeoutSeconds" }
        require(backpressureBufferBytes > 0) { "backpressureBufferBytes must be positive: $backpressureBufferBytes" }
    }
}
