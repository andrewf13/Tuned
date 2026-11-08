package com.andrewf13.tuned.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupCryptoTest {
    private val plain = """{"account":{"server":"http://x:8080","username":"u","password":"secret-pw"}}""".toByteArray()

    @Test fun roundTrips() {
        val enc = BackupCrypto.encrypt(plain, "correct horse".toCharArray())
        assertArrayEquals(plain, BackupCrypto.decrypt(enc, "correct horse".toCharArray()))
    }

    @Test fun passwordIsNotVisibleInTheFile() {
        val enc = BackupCrypto.encrypt(plain, "correct horse".toCharArray()).decodeToString()
        assertFalse(enc.contains("secret-pw"))
        assertFalse(enc.contains("username"))
    }

    @Test(expected = BadPassphraseException::class)
    fun wrongPassphraseIsRejected() {
        val enc = BackupCrypto.encrypt(plain, "correct horse".toCharArray())
        BackupCrypto.decrypt(enc, "wrong horse".toCharArray())
    }

    @Test(expected = BadPassphraseException::class)
    fun tamperingIsDetected() {
        val enc = BackupCrypto.encrypt(plain, "pw123456".toCharArray()).decodeToString()
        val data = Regex("\"data\":\"([^\"]+)\"").find(enc)!!.groupValues[1]
        val flipped = (if (data[5] == 'A') 'B' else 'A').toString()
        BackupCrypto.decrypt(enc.replace(data, data.substring(0, 5) + flipped + data.substring(6)).toByteArray(), "pw123456".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherFilesAreRefused() {
        BackupCrypto.decrypt("hello".toByteArray(), "pw123456".toCharArray())
    }
}
