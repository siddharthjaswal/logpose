package io.github.siddharthjaswal.logpose

/**
 * A database access, as the app describes it.
 *
 * Firebase-free/androidx-free by design, exactly like [FcmMessageInfo]: the app maps its own
 * types across, so LogPose depends on no storage library and the release `no-op` artifact stays
 * a pure-JVM jar.
 *
 * With Room, the whole integration is one call in the database builder:
 *
 * ```kotlin
 * Room.databaseBuilder(app, AppDb::class.java, "app-db")
 *     .apply {
 *         if (BuildConfig.DEBUG) setQueryCallback({ sql, args ->
 *             LogPose.logDbQuery(DbQueryInfo(sql = sql, args = args.map { it.toString() },
 *                                           database = "app-db"))
 *         }, Executors.newSingleThreadExecutor())
 *     }
 *     .build()
 * ```
 *
 * Operation and table are parsed from [sql] by the IDE plugin — set [operation] / [table]
 * yourself only for stores that aren't SQL.
 */
data class DbQueryInfo(
    val sql: String,
    val args: List<String> = emptyList(),
    val database: String? = null,
    /** Rows returned or affected, when you know it. */
    val rows: Int? = null,
    /** How long the query took, when you measured it. Room's query callback doesn't provide it. */
    val durationMillis: Long? = null,
    val error: String? = null,
    val operation: String? = null,
    val table: String? = null,
)

/**
 * A background work request at one point in its life.
 *
 * The natural single integration point is one observer over WorkManager, which covers every
 * worker — including ones written later — without touching any `Worker` class:
 *
 * ```kotlin
 * if (BuildConfig.DEBUG) {
 *     WorkManager.getInstance(this)
 *         .getWorkInfosLiveData(WorkQuery.fromStates(WorkInfo.State.values().toList()))
 *         .observeForever { infos ->
 *             infos.forEach { info ->
 *                 LogPose.logWorker(
 *                     WorkerEventInfo(
 *                         worker = info.tags.firstOrNull { it.contains('.') }?.substringAfterLast('.')
 *                             ?: "Worker",
 *                         state = info.state.name.lowercase(),
 *                         workId = info.id.toString(),
 *                         runAttempt = info.runAttemptCount,
 *                         tags = info.tags.toList(),
 *                         outputData = info.outputData.keyValueMap.mapValues { it.value.toString() },
 *                     )
 *                 )
 *             }
 *         }
 * }
 * ```
 *
 * Note that timings derived this way include queue time — `WorkInfo` reports state, not
 * execution duration.
 */
data class WorkerEventInfo(
    val worker: String,
    /** "enqueued" | "running" | "succeeded" | "failed" | "cancelled" | "blocked". */
    val state: String,
    /** Stable id across the request's life; it's what keeps this to one row that updates. */
    val workId: String? = null,
    val uniqueName: String? = null,
    val runAttempt: Int = 0,
    val tags: List<String> = emptyList(),
    val inputData: Map<String, String> = emptyMap(),
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null,
)
