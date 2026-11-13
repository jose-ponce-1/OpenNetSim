package com.opennetsim.service.forwarder

import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.service.diag.NetDiagnostics
import kotlinx.coroutines.CoroutineScope
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dependencias comunes de los forwarders.
 *
 * @param strictThreadChecks Si es true, cualquier operación sobre el selector o el estado de sesión
 * fuera del hilo del selector lanza [IllegalStateException] (solo para tests).
 */
data class ForwarderEnvironment(
    val socketProtector: VpnSocketProtector,
    val coroutineScope: CoroutineScope,
    val bufferPool: ByteBufferPool = ByteBufferPool.defaultInstance,
    val diagnostics: NetDiagnostics = NetDiagnostics.NONE,
    val strictThreadChecks: Boolean = false
)

/**
 * Cola de tareas que se ejecutan en el hilo dueño de un [Selector] (modelo actor).
 *
 * Los productores llaman a [post] desde cualquier hilo; el hilo del selector llama a [runPending]
 * antes de cada `select`. Solo se despierta el selector en la transición de "sin aviso pendiente" a
 * "aviso pendiente", para no hacer un `wakeup()` por paquete.
 */
class SelectorTaskQueue(private val selector: Selector, private val strictThreadChecks: Boolean) {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val wakeupRequested = AtomicBoolean(false)

    @Volatile
    private var ownerThread: Thread? = null

    /** Marca el hilo actual como dueño del selector. */
    fun bindToCurrentThread() {
        ownerThread = Thread.currentThread()
    }

    fun post(task: Runnable) {
        tasks.add(task)
        if (wakeupRequested.compareAndSet(false, true)) {
            selector.wakeup()
        }
    }

    /** Ejecuta las tareas pendientes. Debe llamarse desde el hilo del selector. */
    fun runPending() {
        checkOwnerThread()
        // Rearmar el aviso antes de vaciar: una tarea añadida durante el vaciado vuelve a despertar.
        wakeupRequested.set(false)
        while (true) {
            val task = tasks.poll() ?: break
            task.run()
        }
    }

    /** Lanza [IllegalStateException] en modo estricto si no se está en el hilo del selector. */
    fun checkOwnerThread() {
        if (!strictThreadChecks) return
        val owner = ownerThread
        check(owner == null || owner === Thread.currentThread()) {
            "Operación de selector fuera de su hilo: ${Thread.currentThread().name} (dueño: ${owner?.name})"
        }
    }

    fun clear() = tasks.clear()
}
