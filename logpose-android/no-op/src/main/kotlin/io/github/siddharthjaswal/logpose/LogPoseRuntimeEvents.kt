package io.github.siddharthjaswal.logpose

/**
 * No-op mirrors of the DB / background-work holders, shipped in the `logpose-no-op` artifact
 * for release builds. Field-for-field identical to the real ones, so call sites compile
 * unchanged across variants.
 */
data class DbQueryInfo(
    val sql: String,
    val args: List<String> = emptyList(),
    val database: String? = null,
    val rows: Int? = null,
    val durationMillis: Long? = null,
    val error: String? = null,
    val operation: String? = null,
    val table: String? = null,
)

data class WorkerEventInfo(
    val worker: String,
    val state: String,
    val workId: String? = null,
    val uniqueName: String? = null,
    val runAttempt: Int = 0,
    val tags: List<String> = emptyList(),
    val inputData: Map<String, String> = emptyMap(),
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null,
)
