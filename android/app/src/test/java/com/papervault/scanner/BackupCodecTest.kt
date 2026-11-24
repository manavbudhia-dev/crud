package com.papervault.scanner

import com.papervault.scanner.backup.BackupCodec
import com.papervault.scanner.backup.WrongBackupPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupCodecTest {
    private fun enc(pw: String, data: ByteArray) =
        ByteArrayOutputStream().also { BackupCodec.encrypt(pw.toCharArray(), ByteArrayInputStream(data), it, iterations = 2000) }.toByteArray()

    @Test fun roundTrip() {
        val data = Random(4).nextBytes(700_000)
        val out = ByteArrayOutputStream()
        BackupCodec.decrypt("correct horse".toCharArray(), ByteArrayInputStream(enc("correct horse", data)), out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test(expected = WrongBackupPasswordException::class)
    fun wrongPassword() {
        BackupCodec.decrypt("wrong".toCharArray(), ByteArrayInputStream(enc("right", ByteArray(1000))), ByteArrayOutputStream())
    }

    @Test(expected = WrongBackupPasswordException::class)
    fun notABackup() {
        BackupCodec.decrypt("x".toCharArray(), ByteArrayInputStream("%PDF-1.7 hello".toByteArray()), ByteArrayOutputStream())
    }
}
