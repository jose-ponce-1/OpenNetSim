package com.opennetsim.service.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class Ipv6AvailabilityTest {

    private val v4Default = InetAddress.getByName("0.0.0.0")
    private val v6Default = InetAddress.getByName("::")
    private val v4Address = InetAddress.getByName("192.168.1.20")
    private val linkLocal = InetAddress.getByName("fe80::1")
    private val ula = InetAddress.getByName("fd12:3456::20")
    private val global = InetAddress.getByName("2a01:4f8:1c0c:1::20")

    @Test
    fun ipv4OnlyNetworkDoesNotRouteIpv6() {
        assertFalse(Ipv6Availability.isRoutable(listOf(v4Default), listOf(v4Address, linkLocal)))
    }

    @Test
    fun dualStackNetworkRoutesIpv6() {
        assertTrue(Ipv6Availability.isRoutable(listOf(v4Default, v6Default), listOf(v4Address, linkLocal, global)))
    }

    @Test
    fun ipv6DefaultRouteWithoutGlobalAddressIsNotEnough() {
        // Router que anuncia ruta pero solo hay link-local o ULA: sin salida IPv6 real.
        assertFalse(Ipv6Availability.isRoutable(listOf(v6Default), listOf(linkLocal, ula)))
    }

    @Test
    fun globalAddressWithoutIpv6DefaultRouteIsNotEnough() {
        assertFalse(Ipv6Availability.isRoutable(listOf(v4Default), listOf(global)))
    }

    @Test
    fun classifiesGlobalAddresses() {
        assertTrue(Ipv6Availability.isGlobal(global as Inet6Address))
        assertFalse(Ipv6Availability.isGlobal(ula as Inet6Address))
        assertFalse(Ipv6Availability.isGlobal(linkLocal as Inet6Address))
        assertFalse(Ipv6Availability.isGlobal(InetAddress.getByName("::1") as Inet6Address))
    }
}
