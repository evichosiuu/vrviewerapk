package com.example.vrviewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIpValidationTest {

    @Test
    fun testValidIpAddresses() {
        val validIps = listOf(
            "192.168.1.1",
            "10.0.0.1",
            "172.16.254.1",
            "127.0.0.1",
            "0.0.0.0",
            "255.255.255.255",
            "  192.168.1.100  ",
            "\t10.0.0.5\n"
        )

        for (ip in validIps) {
            assertTrue("Expected valid IP: '$ip'", MainActivity.isValidIpAddress(ip))
        }
    }

    @Test
    fun testInvalidIpAddressesWrongOctetCount() {
        val invalidIps = listOf(
            "",
            "   ",
            "192",
            "192.168",
            "192.168.1",
            "192.168.1.1.1",
            "1.2.3.4.5"
        )

        for (ip in invalidIps) {
            assertFalse("Expected invalid IP due to octet count: '$ip'", MainActivity.isValidIpAddress(ip))
        }
    }

    @Test
    fun testInvalidIpAddressesOutOfRange() {
        val invalidIps = listOf(
            "256.0.0.1",
            "192.168.1.256",
            "300.168.1.1",
            "999.999.999.999",
            "-1.0.0.1",
            "192.168.1.-5"
        )

        for (ip in invalidIps) {
            assertFalse("Expected invalid IP due to value out of range: '$ip'", MainActivity.isValidIpAddress(ip))
        }
    }

    @Test
    fun testInvalidIpAddressesNonNumericAndMalformed() {
        val invalidIps = listOf(
            "abc.def.ghi.jkl",
            "192.168.1.x",
            "192.168.1.1#",
            "192.168.1.1/24",
            "192.168..1",
            ".192.168.1.1",
            "192.168.1.1.",
            "192. 168.1.1",
            "192.168.1. 1"
        )

        for (ip in invalidIps) {
            assertFalse("Expected invalid IP due to non-numeric or malformed characters: '$ip'", MainActivity.isValidIpAddress(ip))
        }
    }
}
