package com.papervault.scanner

import com.papervault.scanner.vault.CorruptVaultFileException
import com.papervault.scanner.vault.VaultCrypto
import com.papervault.scanner.vault.WrongVaultPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class VaultCryptoTest {

    private val dek = VaultCrypto.randomBytes(32)

    private fun roundTrip(size: Int) {
        val data = Random(size).nextBytes(size)
        val enc = ByteArrayOutputStream().also { VaultCrypto.encrypt(dek, ByteArrayInputStream(data), it) }.toByteArray()
        assertFalse("ciphertext must not contain plaintext prefix", size > 64 && enc.copyOfRange(12, 76).contentEquals(data.copyOfRange(0, 64)))
        val dec = ByteArrayOutputStream().also { VaultCrypto.decrypt(dek, BufferedInputStream(ByteArrayInputStream(enc)), it) }.toByteArray()
        assertArrayEquals(data, dec)
    }

    @Test fun emptyFile() = roundTrip(0)
    @Test fun smallFile() = roundTrip(1000)
    @Test fun exactChunk() = roundTrip(256 * 1024)
    @Test fun multiChunk() = roundTrip(256 * 1024 * 3 + 17)

    @Test(expected = CorruptVaultFileException::class)
    fun detectsTruncation() {
        val data = Random(1).nextBytes(256 * 1024 * 2 + 5)
        val enc = ByteArrayOutputStream().also { VaultCrypto.encrypt(dek, ByteArrayInputStream(data), it) }.toByteArray()
        // Drop the last chunk entirely: the previous chunk was not marked final, so it must fail.
        val lastLen = 5 + 16
        val cut = enc.copyOf(enc.size - lastLen - 4)
        VaultCrypto.decrypt(dek, BufferedInputStream(ByteArrayInputStream(cut)), ByteArrayOutputStream())
    }

    @Test(expected = CorruptVaultFileException::class)
    fun detectsTampering() {
        val enc = ByteArrayOutputStream().also { VaultCrypto.encrypt(dek, ByteArrayInputStream(ByteArray(500)), it) }.toByteArray()
        enc[enc.size - 3] = (enc[enc.size - 3].toInt() xor 1).toByte()
        VaultCrypto.decrypt(dek, BufferedInputStream(ByteArrayInputStream(enc)), ByteArrayOutputStream())
    }

    @Test(expected = CorruptVaultFileException::class)
    fun wrongKeyFails() {
        val enc = ByteArrayOutputStream().also { VaultCrypto.encrypt(dek, ByteArrayInputStream(ByteArray(500)), it) }.toByteArray()
        VaultCrypto.decrypt(VaultCrypto.randomBytes(32), BufferedInputStream(ByteArrayInputStream(enc)), ByteArrayOutputStream())
    }

    @Test fun passwordWrapRoundTrip() {
        val salt = VaultCrypto.randomBytes(16)
        val kek = VaultCrypto.deriveKek("correct horse".toCharArray(), salt, iterations = 1000)
        val wrapped = VaultCrypto.wrap(dek, kek)
        assertArrayEquals(dek, VaultCrypto.unwrap(wrapped, VaultCrypto.deriveKek("correct horse".toCharArray(), salt, 1000)))
    }

    @Test(expected = WrongVaultPasswordException::class)
    fun wrongPasswordRejected() {
        val salt = VaultCrypto.randomBytes(16)
        val wrapped = VaultCrypto.wrap(dek, VaultCrypto.deriveKek("right".toCharArray(), salt, 1000))
        VaultCrypto.unwrap(wrapped, VaultCrypto.deriveKek("wrong".toCharArray(), salt, 1000))
    }
}
