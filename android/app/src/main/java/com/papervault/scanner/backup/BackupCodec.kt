package com.papervault.scanner.backup

import com.papervault.scanner.vault.CorruptVaultFileException
import com.papervault.scanner.vault.VaultCrypto
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

class WrongBackupPasswordException : Exception("Wrong password, or the file isn't a PaperVault backup")

/**
 * Backup file = "PVB1" | salt(16) | iterations(int) | chunked AES-256-GCM stream (see [VaultCrypto]).
 * The key is derived from the backup password with PBKDF2-SHA256, so the file is useless without it
 * and can be stored anywhere (Google Drive, email, USB).
 */
object BackupCodec {
    private val MAGIC = "PVB1".toByteArray()

    fun encrypt(password: CharArray, plain: InputStream, out: OutputStream, iterations: Int = VaultCrypto.PBKDF2_ITERATIONS) {
        val salt = VaultCrypto.randomBytes(16)
        val key = VaultCrypto.deriveKek(password, salt, iterations)
        try {
            val d = DataOutputStream(out)
            d.write(MAGIC); d.write(salt); d.writeInt(iterations); d.flush()
            VaultCrypto.encrypt(key, plain, out)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    fun decrypt(password: CharArray, input: InputStream, out: OutputStream) {
        val d = DataInputStream(BufferedInputStream(input))
        val magic = ByteArray(4)
        runCatching { d.readFully(magic) }.onFailure { throw WrongBackupPasswordException() }
        if (!magic.contentEquals(MAGIC)) throw WrongBackupPasswordException()
        val salt = ByteArray(16).also { d.readFully(it) }
        val iterations = d.readInt()
        if (iterations !in 1_000..5_000_000) throw WrongBackupPasswordException()
        val key = VaultCrypto.deriveKek(password, salt, iterations)
        try {
            VaultCrypto.decrypt(key, d, out)
        } catch (e: CorruptVaultFileException) {
            // The first chunk fails authentication when the key (password) is wrong.
            throw WrongBackupPasswordException()
        } finally {
            Arrays.fill(key, 0)
        }
    }
}
