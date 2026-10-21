package com.salatv.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * A small client for the TV's own ADB daemon on 127.0.0.1:5555, so SalaTV can run the commands
 * CleanYourTV ran from the PC. Speaks the classic ADB protocol (no TLS), which is what Fire OS
 * exposes when «Depuración ADB» is on.
 */
class Adb private constructor(private val sock: Socket) : Closeable {
    private val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
    private val out = BufferedOutputStream(sock.getOutputStream())
    private var nextId = 1

    class NeedsApproval : IOException("La tele aún no ha aceptado a SalaTV")

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000000
        private const val MAXDATA = 256 * 1024

        /**
         * Connects and authenticates. The first time, the TV asks «¿Permitir la depuración?»; [onPrompt] runs
         * then and the wait is long. With [allowPrompt] false (timers, quick actions) it gives up instead.
         */
        fun connect(c: Context, allowPrompt: Boolean = true, onPrompt: (() -> Unit)? = null): Adb {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val adb = Adb(s)
            try {
                adb.handshake(AdbKey.get(c), allowPrompt, onPrompt)
            } catch (e: Exception) {
                adb.close(); throw e
            }
            return adb
        }

        /** A sentence for the person in front of the TV. */
        fun explain(e: Throwable): String = when (e) {
            is ConnectException -> "No hay conexión con la depuración de la tele. Actívala en Ajustes › Mi Fire TV › Opciones para desarrolladores › Depuración ADB y vuelve a intentarlo."
            is NeedsApproval -> "La tele todavía no ha aceptado a SalaTV. Usa «Configurar todo» y acepta el aviso en pantalla."
            is SocketTimeoutException -> "La tele no respondió. Si salió el aviso «¿Permitir la depuración?», márcalo como «Permitir siempre», acéptalo y vuelve a intentarlo."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0
        for (x in data) sum += x.toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        out.write(h.array())
        out.write(data)
        out.flush()
    }

    private fun read(): Msg {
        val h = ByteArray(24)
        inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len)
        if (len > 0) inp.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    private fun handshake(key: AdbKey, allowPrompt: Boolean, onPrompt: (() -> Unit)?) {
        send(CNXN, VERSION, MAXDATA, "host::salatv\u0000".toByteArray())
        var signed = false
        var offered = false
        var m = read()
        while (m.cmd != CNXN) {
            if (m.cmd != AUTH) throw IOException("Respuesta inesperada de la tele")
            when {
                !signed -> { send(AUTH, 2, 0, key.sign(m.data)); signed = true }
                !offered -> {
                    if (!allowPrompt) throw NeedsApproval()
                    send(AUTH, 3, 0, key.publicBlob()); offered = true
                    onPrompt?.invoke()
                    sock.soTimeout = 90_000
                }
                else -> throw IOException("La tele rechazó la conexión")
            }
            m = read()
        }
        sock.soTimeout = 60_000
    }

    /** Runs a shell command and returns what it printed. */
    fun shell(cmd: String): String {
        val local = nextId++
        send(OPEN, local, 0, "shell:$cmd\u0000".toByteArray())
        val buf = ByteArrayOutputStream()
        var remote = 0
        while (true) {
            val m = read()
            when (m.cmd) {
                OKAY -> remote = m.a0
                WRTE -> { buf.write(m.data); send(OKAY, local, m.a0) }
                CLSE -> { if (remote != 0) send(CLSE, local, remote); break }
            }
        }
        return buf.toString("UTF-8")
    }

    override fun close() {
        try { sock.close() } catch (_: Exception) {}
    }
}

/** SalaTV's RSA key, kept in its private files; the TV remembers it after «Permitir siempre». */
class AdbKey(private val pair: KeyPair) {
    companion object {
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        @Synchronized
        fun get(c: Context): AdbKey {
            val priv = File(c.filesDir, "adbkey")
            val pub = File(c.filesDir, "adbkey.pub")
            if (priv.exists() && pub.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                return AdbKey(KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))))
            }
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val kp = g.generateKeyPair()
            priv.writeBytes(kp.private.encoded)
            pub.writeBytes(kp.public.encoded)
            return AdbKey(kp)
        }
    }

    /** adbd checks RSA_sign(NID_sha1) over its 20-byte token: PKCS#1 type 1 padding, SHA-1 DigestInfo, raw RSA. */
    fun sign(token: ByteArray): ByteArray {
        val k = 256
        val em = ByteArray(k)
        val start = k - SHA1_PREFIX.size - token.size
        em[0] = 0; em[1] = 1
        for (i in 2 until start - 1) em[i] = 0xff.toByte()
        em[start - 1] = 0
        System.arraycopy(SHA1_PREFIX, 0, em, start, SHA1_PREFIX.size)
        System.arraycopy(token, 0, em, start + SHA1_PREFIX.size, token.size)
        val ci = Cipher.getInstance("RSA/ECB/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, pair.private)
        return ci.doFinal(em)
    }

    /** The public key in Android's own format (RSAPublicKey struct, base64), as adbd stores it. */
    fun publicBlob(): ByteArray {
        val pk = pair.public as RSAPublicKey
        val n = pk.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(64)
        b.putInt(n0inv.toLong().toInt())
        putWords(b, n)
        putWords(b, rr)
        b.putInt(pk.publicExponent.toInt())
        return (java.util.Base64.getEncoder().encodeToString(b.array()) + " salatv@firetv\u0000").toByteArray()
    }

    private fun putWords(b: ByteBuffer, v: BigInteger) {
        var x = v
        val mask = BigInteger.valueOf(0xffffffffL)
        repeat(64) {
            b.putInt(x.and(mask).toLong().toInt())
            x = x.shiftRight(32)
        }
    }
}
