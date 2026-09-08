package works.resolve.pathfinder.codingagent.core.tools

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val fileMutationQueues = ConcurrentHashMap<String, Mutex>()

/**
 * Serialize file mutation operations targeting the same file. Operations for
 * different files still run in parallel.
 *
 * Divergences from pi's promise-chain queue: the key is the resolved path
 * string (upstream resolves symlinks with `realpath`, unavailable through the
 * remote-capable operations seam), per-path mutexes are kept rather than
 * garbage-collected when drained (a `Mutex` exposes no waiter count; the map
 * stays bounded by the distinct files touched), and pi's registration queue —
 * only needed to keep its async map updates ordered — is unnecessary here.
 */
suspend fun <T> withFileMutationQueue(filePath: String, block: suspend () -> T): T =
    fileMutationQueues.computeIfAbsent(filePath) { Mutex() }.withLock { block() }
