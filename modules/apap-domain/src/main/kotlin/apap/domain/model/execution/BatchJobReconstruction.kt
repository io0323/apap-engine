package apap.domain.model.execution

import apap.domain.event.BatchItemCompleted
import apap.domain.event.BatchJobCancelled
import apap.domain.event.BatchJobCompleted
import apap.domain.event.BatchJobFailed
import apap.domain.event.BatchJobStarted
import apap.domain.event.BatchJobSubmitted
import apap.domain.event.DomainEvent
import apap.domain.model.UnexpectedEventForAggregateException

private const val AGGREGATE_TYPE = "BatchJob"

/**
 * ADR-0026: [apap.domain.model.reconstruct]と組み合わせ、BatchJobのstream_idに記録された
 * イベント列からBatchJobの現在状態を復元する純粋関数。
 *
 * `BatchJob.transitionTo`が行う遷移合法性チェック（COMPLETED到達後は常に拒否等）はここでは使わず、
 * `copy`で直接statusを設定する。理由: SUBMITTED→QUEUEDには14章に対応するイベントが無く
 * （[BatchJobStarted]はQUEUED→RUNNINGへの遷移を表すが、QUEUED到達そのものを表すイベントは無い）、
 * 再構築時はQUEUEDを経由せずSUBMITTEDから直接RUNNINGへ遷移する（ADR-0026「意図的に再構築されない
 * 遷移的状態」）。遷移の合法性はコマンド決定時（ライブパス）で既に検証済みの前提のため、
 * 再構築では再検証しない。
 */
fun applyBatchJobEvent(
    state: BatchJob?,
    event: DomainEvent,
): BatchJob =
    when (event) {
        is BatchJobSubmitted ->
            BatchJob(
                jobId = event.jobId,
                tenantId = event.tenantId,
                targetCapability = event.targetCapability,
                items = event.items,
            )
        is BatchJobStarted -> requireState(state, event).copy(status = BatchJobStatus.RUNNING)
        is BatchItemCompleted -> requireState(state, event).completeItem(event.itemId, event.status)
        is BatchJobCompleted -> requireState(state, event).copy(status = BatchJobStatus.COMPLETED)
        is BatchJobFailed -> requireState(state, event).copy(status = BatchJobStatus.FAILED)
        is BatchJobCancelled -> requireState(state, event).copy(status = BatchJobStatus.CANCELLED)
        else -> throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
    }

private fun BatchJob.completeItem(
    itemId: String,
    status: String,
): BatchJob {
    val newStatus = BatchItemStatus.valueOf(status)
    return copy(
        items =
            items.map {
                if (it.itemId == itemId) it.copy(status = newStatus) else it
            },
    )
}

private fun requireState(
    state: BatchJob?,
    event: DomainEvent,
): BatchJob = state ?: throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
