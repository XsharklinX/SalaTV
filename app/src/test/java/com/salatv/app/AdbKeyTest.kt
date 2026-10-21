package com.salatv.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/** The two things adbd checks: the token signature and the public key blob it stores. */
class AdbKeyTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val key = AdbKey(pair)

    @Test fun signatureRecoversTheTokenWithSha1Padding() {
        val token = ByteArray(20) { (it * 7).toByte() }
        val sig = key.sign(token)
        assertEquals(256, sig.size)
        val plain = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, pair.public) }.doFinal(sig)
        assertEquals(0x01, plain[1].toInt())
        assertArrayEquals(token, plain.copyOfRange(256 - 20, 256))
    }

    @Test fun publicBlobHasAndroidLayout() {
        val text = String(key.publicBlob()).trimEnd('\u0000')
        val b64 = text.substringBefore(' ')
        assertEquals("salatv@firetv", text.substringAfter(' '))
        val raw = java.util.Base64.getDecoder().decode(b64)
        assertEquals(524, raw.size)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int)
        val n0inv = bb.int.toLong() and 0xffffffffL
        val n = (pair.public as RSAPublicKey).modulus
        val n0 = n.mod(BigInteger.ONE.shiftLeft(32)).toLong()
        // n0inv * n[0] ≡ -1 (mod 2^32)
        assertEquals(0xffffffffL, (n0inv * n0) and 0xffffffffL)
        bb.position(4 + 4 + 256 + 256)
        assertEquals(65537, bb.int)
    }
}
