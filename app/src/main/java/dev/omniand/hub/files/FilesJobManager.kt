package dev.omniand.hub.files

import android.content.Context
import dev.omniand.hub.services.FilesService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs bounded recursive filesystem operations and exposes process-lifetime progress/cancellation.
 */
object FilesJobManager {
    private val executor = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<String, Job>()

    private class Job(val owner: String, val operation: String, val total: Int) {
        val cancelled = AtomicBoolean(false)
        @Volatile var state = "queued"
        @Volatile var completed = 0
        @Volatile var results = JSONArray()
        @Volatile var code: String? = null
    }

    /** Starts one owner-scoped job and returns its initial pollable representation. */
    fun create(
        context: Context,
        owner: String,
        operation: String,
        ids: List<String>,
        destination: String?,
        conflict: String,
    ): JSONObject {
        if (jobs.values.count { it.owner == owner && it.state in setOf("queued", "running") } >= 4)
            throw FilesService.Invalid("too-many-jobs")
        val id = UUID.randomUUID().toString()
        val job = Job(owner, operation, ids.size)
        jobs[id] = job
        executor.execute {
            job.state = "running"
            try {
                job.results =
                    FilesService(context).operate(
                        operation,
                        ids,
                        destination,
                        conflict,
                        job.cancelled::get,
                    ) { done, _ ->
                        job.completed = done
                        FilesEventBroadcaster.publish("job")
                    }
                job.state = if (job.cancelled.get()) "cancelled" else "completed"
            } catch (error: FilesService.Invalid) {
                job.code = error.code
                job.state = if (error.code == "cancelled") "cancelled" else "failed"
            } catch (_: Exception) {
                job.code = "operation-failed"
                job.state = "failed"
            }
            FilesEventBroadcaster.publish("job")
        }
        return json(id, job)
    }

    fun get(owner: String, id: String): JSONObject {
        val job =
            jobs[id]?.takeIf { it.owner == owner } ?: throw FilesService.Invalid("job-not-found")
        return json(id, job)
    }

    fun cancel(owner: String, id: String): JSONObject {
        val job =
            jobs[id]?.takeIf { it.owner == owner } ?: throw FilesService.Invalid("job-not-found")
        if (job.state !in setOf("queued", "running")) throw FilesService.Invalid("job-finished")
        job.cancelled.set(true)
        return json(id, job)
    }

    private fun json(id: String, job: Job) =
        JSONObject()
            .put("id", id)
            .put("operation", job.operation)
            .put("state", job.state)
            .put("completed", job.completed)
            .put("total", job.total)
            .put("results", job.results)
            .put("code", job.code ?: JSONObject.NULL)
}
