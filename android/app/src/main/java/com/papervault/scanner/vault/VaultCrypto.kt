package com.papervault.scanner.vault

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongVaultPasswordException : Exception("Incorrect password")
class CorruptVaultFileException(msg: String) : Exception(msg)

/**
 * Vault key handling and file encryption. No Android dependencies, so it is unit-tested on the JVM.
 *
 * Design:
 *  - A random 256-bit data key (DEK) encrypts every vault file.
 *  - The DEK is stored only wrapped: once under a key derived from the password (PBKDF2-SHA256)
 *    and optionally once under a biometric-bound Android Keystore key.
 *  - Files use chunked AES-256-GCM so large PDFs never need to fit in memory, with chunk index
 *    and a final-chunk flag in the AAD to prevent reordering or truncation.
 */
object VaultCrypto {

    const val PBKDF2_ITERATIONS = 150_000
    private const val GCM_TAG_BITS = 128
    private const val CHUNK = 256 * 1024
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'V'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns iv(12) || ciphertext+tag. */
    fun wrap(secret: ByteArray, key: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + c.doFinal(secret)
    }

    fun unwrap(blob: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, blob, 0, 12))
        return try {
            c.doFinal(blob, 12, blob.size - 12)
        } catch (e: AEADBadTagException) {
            throw WrongVaultPasswordException()
        }
    }

    fun encrypt(dek: ByteArray, input: InputStream, output: OutputStream) {
        val prefix = randomBytes(8)
        val out = DataOutputStream(output)
        out.write(MAGIC)
        out.write(prefix)
        val key = SecretKeySpec(dek, "AES")
        val buf = ByteArray(CHUNK)
        var index = 0
        var current = readFully(input, buf)
        while (true) {
            // Look ahead one chunk to know whether this one is final.
            val next = ByteArray(CHUNK)
            val nextLen = if (current == CHUNK) readFully(input, next) else 0
            val final = nextLen == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv(prefix, index)))
            c.updateAAD(aad(index, final))
            val ct = c.doFinal(buf, 0, current)
            out.writeInt(ct.size)
            out.write(ct)
            if (final) break
            System.arraycopy(next, 0, buf, 0, nextLen)
            current = nextLen
            index++
        }
        out.flush()
    }

    fun decrypt(dek: ByteArray, input: InputStream, output: OutputStream) {
        val inp = DataInputStream(input)
        val magic = ByteArray(4)
        try { inp.readFully(magic) } catch (e: EOFException) { throw CorruptVaultFileException("Empty vault file") }
        if (!magic.contentEquals(MAGIC)) throw CorruptVaultFileException("Not a vault file")
        val prefix = ByteArray(8).also { inp.readFully(it) }
        val key = SecretKeySpec(dek, "AES")
        var index = 0
        while (true) {
            val len = try { inp.readInt() } catch (e: EOFException) { throw CorruptVaultFileException("Vault file is truncated") }
            if (len < 16 || len > CHUNK + 16) throw CorruptVaultFileException("Bad chunk length")
            val ct = ByteArray(len).also { inp.readFully(it) }
            // A chunk is final iff nothing follows it.
            inp.mark(1)
            val final = inp.read() == -1
            if (!final) inp.reset()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv(prefix, index)))
            c.updateAAD(aad(index, final))
            val pt = try { c.doFinal(ct) } catch (e: AEADBadTagException) {
                throw CorruptVaultFileException("Vault file failed integrity check")
            }
            output.write(pt)
            if (final) break
            index++
        }
        output.flush()
    }

    private fun iv(prefix: ByteArray, index: Int) = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
    private fun aad(index: Int, final: Boolean) = ByteBuffer.allocate(5).putInt(index).put(if (final) 1 else 0).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
