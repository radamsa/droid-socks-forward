package dev.dav.socksforward.core

import dev.dav.socksforward.core.model.AuthCredentials
import dev.dav.socksforward.core.model.ProxyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyConfigTest {

    @Test
    fun `defaults are valid`() {
        assertTrue(ProxyConfig().validationErrors().isEmpty())
    }

    @Test
    fun `rejects privileged and out of range ports`() {
        assertTrue(ProxyConfig(port = 80).validationErrors().isNotEmpty())
        assertTrue(ProxyConfig(port = 0).validationErrors().isNotEmpty())
        assertTrue(ProxyConfig(port = 70_000).validationErrors().isNotEmpty())
    }

    @Test
    fun `rejects malformed addresses`() {
        listOf("", "999.1.1.1", "1.2.3", "localhost", "1.2.3.4.5", ":::").forEach { address ->
            assertTrue(
                ProxyConfig(bindAddresses = listOf(address)).validationErrors().isNotEmpty(),
                "expected $address to be invalid",
            )
        }
    }

    @Test
    fun `accepts loopback wildcard and concrete addresses`() {
        listOf("127.0.0.1", "0.0.0.0", "::", "192.168.1.42", "::1", "2001:db8::1").forEach { address ->
            assertTrue(
                ProxyConfig(bindAddresses = listOf(address)).validationErrors().isEmpty(),
                "expected $address to be valid",
            )
        }
    }

    @Test
    fun `accepts several concrete addresses`() {
        val config = ProxyConfig(bindAddresses = listOf("127.0.0.1", "192.168.1.42"))
        assertTrue(config.validationErrors().isEmpty())
    }

    @Test
    fun `rejects empty and wildcard mixed address lists`() {
        assertTrue(ProxyConfig(bindAddresses = emptyList()).validationErrors().isNotEmpty())
        assertTrue(
            ProxyConfig(bindAddresses = listOf("0.0.0.0", "127.0.0.1")).validationErrors()
                .isNotEmpty(),
        )
    }

    @Test
    fun `auth requires both fields`() {
        val auth = AuthCredentials("user", "pass")
        assertEquals(emptyList(), ProxyConfig(auth = auth).validationErrors())
    }

    @Test
    fun `rejects non ascii credentials`() {
        val error = runCatching { AuthCredentials("юзер", "pass") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
