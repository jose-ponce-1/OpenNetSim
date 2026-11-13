package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.UnreachableKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException

class UnreachableClassifierTest {

    @Test
    fun mapsSocketErrorsToUnreachableKinds() {
        assertEquals(UnreachableKind.PORT, UnreachableClassifier.classify(PortUnreachableException()))
        assertEquals(UnreachableKind.HOST, UnreachableClassifier.classify(NoRouteToHostException("No route to host")))
        assertEquals(UnreachableKind.NETWORK, UnreachableClassifier.classify(SocketException("Network is unreachable")))
        assertEquals(UnreachableKind.HOST, UnreachableClassifier.classify(SocketException("Host is unreachable")))
        assertEquals(UnreachableKind.PORT, UnreachableClassifier.classify(SocketException("Connection refused")))
    }

    @Test
    fun otherErrorsAreNotReported() {
        assertNull(UnreachableClassifier.classify(IOException("Permission denied")))
        assertNull(UnreachableClassifier.classify(IOException()))
    }
}
