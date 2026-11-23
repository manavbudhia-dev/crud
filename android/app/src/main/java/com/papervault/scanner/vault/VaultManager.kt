package com.papervault.scanner.vault

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.ui.components.PageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.security.KeyStore
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class VaultState { NOT_SET_UP, LOCKED, UNLOCKED }

class VaultLockedException : Exception("Vault is locked")
class LockedOutException(val secondsLeft: Long) : Exception("Too many attempts. Try again in ${secondsLeft}s")

/**
 * Owns the vault: password/biometric unlock, the in-memory data key, encrypted storage of vault
 * documents, and auto-lock when the app goes to the background.
 */
class VaultManager(private val context: Context, private val repo: DocumentRepository) {

    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val vaultDir = File(context.filesDir, "vault").apply { mkdirs() }
    private val viewDir get() = File(context.cacheDir, "vault_view").apply { mkdirs() }

    @Volatile private var dek: ByteArray? = null

    private val _state = MutableStateFlow(if (prefs.contains(KEY_WRAPPED_PW)) VaultState.LOCKED else VaultState.NOT_SET_UP)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val documents: Flow<List<Document>> = repo.observeVault()

    // ---------------- settings ----------------

    /** Seconds in background before locking; 0 = immediately. */
    var autoLockSeconds: Int
        get() = prefs.getInt(KEY_AUTOLOCK, 0)
        set(v) = prefs.edit().putInt(KEY_AUTOLOCK, v).apply()

    val biometricEnabled get() = prefs.contains(KEY_WRAPPED_BIO)

    fun biometricAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    // ---------------- setup & unlock ----------------

    suspend fun setUp(password: CharArray) = withContext(Dispatchers.Default) {
        check(_state.value == VaultState.NOT_SET_UP) { "Vault already exists" }
        val salt = VaultCrypto.randomBytes(16)
        val key = VaultCrypto.randomBytes(32)
        val kek = VaultCrypto.deriveKek(password, salt)
        prefs.edit()
            .putString(KEY_SALT, b64(salt))
            .putString(KEY_WRAPPED_PW, b64(VaultCrypto.wrap(key, kek)))
            .putInt(KEY_ITER, VaultCrypto.PBKDF2_ITERATIONS)
            .apply()
        Arrays.fill(kek, 0)
        Arrays.fill(password, '\u0000')
        dek = key
        _state.value = VaultState.UNLOCKED
    }

    suspend fun unlockWithPassword(password: CharArray) = withContext(Dispatchers.Default) {
        lockoutRemaining().let { if (it > 0) throw LockedOutException(it) }
        val salt = unb64(prefs.getString(KEY_SALT, null)!!)
        val kek = VaultCrypto.deriveKek(password, salt, prefs.getInt(KEY_ITER, VaultCrypto.PBKDF2_ITERATIONS))
        Arrays.fill(password, '\u0000')
        try {
            dek = VaultCrypto.unwrap(unb64(prefs.getString(KEY_WRAPPED_PW, null)!!), kek)
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKOUT_UNTIL, 0).apply()
            _state.value = VaultState.UNLOCKED
        } catch (e: WrongVaultPasswordException) {
            registerFailure()
            throw e
        } finally {
            Arrays.fill(kek, 0)
        }
    }

    private fun registerFailure() {
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val edit = prefs.edit().putInt(KEY_FAILS, fails)
        if (fails >= MAX_ATTEMPTS) {
            // 30s, 60s, 120s … capped at 1h. Uses elapsedRealtime so changing the clock doesn't help.
            val seconds = (30L shl (fails - MAX_ATTEMPTS).coerceAtMost(7)).coerceAtMost(3600)
            edit.putLong(KEY_LOCKOUT_UNTIL, SystemClock.elapsedRealtime() + seconds * 1000)
        }
        edit.apply()
    }

    /** Seconds until another password attempt is allowed (0 if allowed now). */
    fun lockoutRemaining(): Long {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val now = SystemClock.elapsedRealtime()
        // After a reboot elapsedRealtime restarts; treat a lockout further away than its max as stale.
        if (until - now > 3600_000) { prefs.edit().putLong(KEY_LOCKOUT_UNTIL, 0).apply(); return 0 }
        return ((until - now) / 1000).coerceAtLeast(0)
    }

    fun failedAttempts() = prefs.getInt(KEY_FAILS, 0)

    suspend fun changePassword(old: CharArray, new: CharArray) = withContext(Dispatchers.Default) {
        val salt = unb64(prefs.getString(KEY_SALT, null)!!)
        val oldKek = VaultCrypto.deriveKek(old, salt, prefs.getInt(KEY_ITER, VaultCrypto.PBKDF2_ITERATIONS))
        val key = VaultCrypto.unwrap(unb64(prefs.getString(KEY_WRAPPED_PW, null)!!), oldKek)
        val newSalt = VaultCrypto.randomBytes(16)
        val newKek = VaultCrypto.deriveKek(new, newSalt)
        prefs.edit()
            .putString(KEY_SALT, b64(newSalt))
            .putString(KEY_WRAPPED_PW, b64(VaultCrypto.wrap(key, newKek)))
            .putInt(KEY_ITER, VaultCrypto.PBKDF2_ITERATIONS)
            .apply()
        Arrays.fill(oldKek, 0); Arrays.fill(newKek, 0)
        Arrays.fill(old, '\u0000'); Arrays.fill(new, '\u0000')
    }

    // ---------------- biometric ----------------

    /**
     * Cipher to hand to BiometricPrompt as a CryptoObject when enabling biometrics.
     * The Keystore key requires strong biometric auth for every use and is invalidated when a
     * new fingerprint/face is enrolled.
     */
    fun biometricEnrollCipher(): Cipher {
        val key = createBioKey()
        return Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Complete enrollment with the cipher BiometricPrompt authenticated. */
    fun finishBiometricEnroll(cipher: Cipher) {
        val key = dek ?: throw VaultLockedException()
        val ct = cipher.doFinal(key)
        prefs.edit().putString(KEY_WRAPPED_BIO, b64(cipher.iv + ct)).apply()
    }

    /**
     * Cipher for unlocking, or null if biometrics are not enabled or the key was invalidated
     * (e.g. a new fingerprint was added) — in that case biometrics are switched off and the
     * password must be used.
     */
    fun biometricUnlockCipher(): Cipher? {
        val blob = prefs.getString(KEY_WRAPPED_BIO, null)?.let(::unb64) ?: return null
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = ks.getKey(BIO_ALIAS, null) as? SecretKey ?: run { disableBiometric(); return null }
            Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometric()
            null
        }
    }

    fun finishBiometricUnlock(cipher: Cipher) {
        val blob = unb64(prefs.getString(KEY_WRAPPED_BIO, null)!!)
        dek = cipher.doFinal(blob, 12, blob.size - 12)
        prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKOUT_UNTIL, 0).apply()
        _state.value = VaultState.UNLOCKED
    }

    fun disableBiometric() {
        prefs.edit().remove(KEY_WRAPPED_BIO).apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(BIO_ALIAS) }
    }

    private fun createBioKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(builder.build()) }.generateKey()
    }

    // ---------------- lock ----------------

    fun lock() {
        dek?.let { Arrays.fill(it, 0) }
        dek = null
        viewDir.deleteRecursively()
        PageCache.clear()
        if (_state.value == VaultState.UNLOCKED) _state.value = VaultState.LOCKED
    }

    @Volatile private var keepUnlockedUntil = 0L

    /** Call before launching another app/picker from inside the vault so returning doesn't find it locked. */
    fun keepUnlockedForExternal() {
        keepUnlockedUntil = SystemClock.elapsedRealtime() + 5 * 60_000
    }

    private var backgroundedAt = 0L

    /** Observe app foreground/background to auto-lock. Call once from Application.onCreate. */
    fun installAutoLock() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = SystemClock.elapsedRealtime()
                if (autoLockSeconds == 0 && backgroundedAt > keepUnlockedUntil) lock()
            }

            override fun onStart(owner: LifecycleOwner) {
                val away = SystemClock.elapsedRealtime() - backgroundedAt
                val external = SystemClock.elapsedRealtime() < keepUnlockedUntil
                keepUnlockedUntil = 0
                if (backgroundedAt > 0 && !external && away > autoLockSeconds * 1000L) lock()
            }
        })
    }

    /** Erase the vault and every document in it (for a forgotten password). */
    suspend fun reset(docs: List<Document>) {
        docs.forEach { repo.delete(it) }
        lock()
        disableBiometric()
        vaultDir.deleteRecursively()
        vaultDir.mkdirs()
        prefs.edit().clear().apply()
        _state.value = VaultState.NOT_SET_UP
    }

    // ---------------- documents ----------------

    private fun requireKey() = dek ?: throw VaultLockedException()

    /** Encrypt a library document into the vault and delete its plaintext file. */
    suspend fun moveIn(doc: Document) = withContext(Dispatchers.IO) {
        val key = requireKey()
        require(!doc.inVault)
        val src = File(doc.filePath)
        val enc = File(vaultDir, "${UUID.randomUUID()}.pvault")
        src.inputStream().use { input -> enc.outputStream().use { VaultCrypto.encrypt(key, input, it) } }
        repo.update(doc.copy(inVault = true, filePath = enc.absolutePath, folder = null, updatedAt = System.currentTimeMillis()))
        src.delete()
    }

    /** Decrypt a vault document back into the normal library. */
    suspend fun moveOut(doc: Document) = withContext(Dispatchers.IO) {
        val key = requireKey()
        require(doc.inVault)
        val out = repo.newPdfFile()
        decryptTo(key, File(doc.filePath), out)
        repo.update(doc.copy(inVault = false, filePath = out.absolutePath, updatedAt = System.currentTimeMillis()))
        File(doc.filePath).delete()
        File(viewDir, "${doc.id}.pdf").delete()
    }

    /** Plaintext copy for viewing; lives in cache and is wiped on lock. */
    suspend fun openForViewing(doc: Document): File = withContext(Dispatchers.IO) {
        val key = requireKey()
        val f = File(viewDir, "${doc.id}.pdf")
        if (!f.exists() || f.lastModified() < File(doc.filePath).lastModified()) decryptTo(key, File(doc.filePath), f)
        f
    }

    /** Plaintext copy named after the document, for sharing (explicit user action). */
    suspend fun exportForSharing(doc: Document): File = withContext(Dispatchers.IO) {
        val key = requireKey()
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "${doc.title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "document" }}.pdf")
        decryptTo(key, File(doc.filePath), f)
        f
    }

    private fun decryptTo(key: ByteArray, enc: File, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        BufferedInputStream(enc.inputStream()).use { input -> tmp.outputStream().use { VaultCrypto.decrypt(key, input, it) } }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val BIO_ALIAS = "papervault_vault_bio"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val MAX_ATTEMPTS = 5
        private const val KEY_SALT = "salt"
        private const val KEY_ITER = "iterations"
        private const val KEY_WRAPPED_PW = "wrapped_pw"
        private const val KEY_WRAPPED_BIO = "wrapped_bio"
        private const val KEY_FAILS = "fails"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_AUTOLOCK = "autolock_seconds"
    }
}
