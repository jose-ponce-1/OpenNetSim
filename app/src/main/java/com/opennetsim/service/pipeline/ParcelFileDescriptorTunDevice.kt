package com.opennetsim.service.pipeline

import android.os.ParcelFileDescriptor
import com.opennetsim.service.diag.DiagLevel
import com.opennetsim.service.diag.NetDiagnostics
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * [TunDevice] sobre el descriptor que devuelve `VpnService.Builder.establish()`.
 */
class ParcelFileDescriptorTunDevice(
    private val descriptor: ParcelFileDescriptor,
    private val diagnostics: NetDiagnostics
) : TunDevice {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(packet: ByteArray) = output.write(packet)

    override fun close() {
        closeQuietly("flujo de salida") { output.close() }
        closeQuietly("flujo de entrada") { input.close() }
        closeQuietly("descriptor") { descriptor.close() }
    }

    private inline fun closeQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "tun.close", "Error al cerrar el $what del TUN", e)
        }
    }
}
