package dev.omniand.hub.files

import android.os.FileObserver
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Publishes invalidations; clients always reload authoritative state after an event. */
object FilesEventBroadcaster {
    private val outputs = CopyOnWriteArrayList<PipedOutputStream>()
    private val watchers = LinkedHashMap<String, FileObserver>()

    /** Watches directories clients browse, retaining a bounded least-recently-used set. */
    @Synchronized
    fun watch(directory: File) {
        val path = directory.absolutePath
        watchers.remove(path)?.let {
            watchers[path] = it
            return
        }
        val watcher =
            object :
                FileObserver(
                    path,
                    CREATE or DELETE or MOVED_FROM or MOVED_TO or MODIFY or ATTRIB,
                ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.startsWith(".omniand-") != true) publish("storage")
                }
            }
        watcher.startWatching()
        watchers[path] = watcher
        while (watchers.size > 128) {
            val oldest = watchers.entries.first()
            oldest.value.stopWatching()
            watchers.remove(oldest.key)
        }
    }

    fun subscribe(): PipedInputStream {
        lateinit var output: PipedOutputStream
        val input =
            object : PipedInputStream(8 * 1024) {
                override fun close() {
                    outputs.remove(output)
                    runCatching { output.close() }
                    super.close()
                }
            }
        output = PipedOutputStream(input)
        outputs += output
        runCatching { output.write(": connected\n\n".toByteArray()) }
        return input
    }

    fun publish(kind: String = "storage") {
        val bytes = "event: invalidated\ndata: {\"kind\":\"$kind\"}\n\n".toByteArray()
        outputs.forEach { output ->
            if (
                runCatching {
                        output.write(bytes)
                        output.flush()
                    }
                    .isFailure
            ) {
                outputs.remove(output)
                runCatching { output.close() }
            }
        }
    }
}
