package com.revela.app.tether.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.revela.app.tether.CameraLink
import com.revela.app.tether.CapturedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class SonyApiException(message: String, val code: Int) : IOException(message)

/**
 * Câmeras Sony por Wi-Fi usando a "Camera Remote API" (JSON-RPC sobre HTTP).
 * Funciona nos modelos com o app "Smart Remote" / "Ctrl c/ Smartphone"
 * (ex.: a6000, a6300, a6500, a7 II, a7 III, a7R III, RX100 IV–VII, a9).
 * Modelos mais novos (a7 IV, a7C II, ZV-E1, a6700…) não têm essa API: use o cabo USB.
 */
class SonyWifiCamera private constructor(
    private val network: Network?,
    private val callback: ConnectivityManager.NetworkCallback?,
    private val cm: ConnectivityManager,
    private val endpoint: String,
    override val displayName: String,
) : CameraLink {

    override val canTrigger = true

    private var rpcId = 1
    private val seenUrls = HashSet<String>()
    private val seenLock = Mutex()
    @Volatile private var captureSink: (suspend (CapturedFile) -> Unit)? = null
    @Volatile private var infoSink: ((String) -> Unit)? = null

    companion object {
        private const val DEFAULT_ENDPOINT = "http://192.168.122.1:8080/sony/camera"

        suspend fun connect(ctx: Context, status: (String) -> Unit): SonyWifiCamera = withContext(Dispatchers.IO) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            status("Procurando a rede Wi-Fi da câmera…")
            val (net, cb) = acquireWifi(cm)

            status("Procurando a câmera na rede…")
            val location = ssdpDiscover(net)
            var endpoint = DEFAULT_ENDPOINT
            var name = "Câmera Sony (Wi-Fi)"
            if (location != null) {
                runCatching {
                    val xml = httpGet(net, location, 5000).toString(Charsets.UTF_8)
                    Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1)?.let { name = it }
                    Regex("<av:X_ScalarWebAPI_Service>(.*?)</av:X_ScalarWebAPI_Service>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml).forEach { m ->
                            val block = m.groupValues[1]
                            val type = Regex("<av:X_ScalarWebAPI_ServiceType>(.*?)</").find(block)?.groupValues?.get(1)
                            val url = Regex("<av:X_ScalarWebAPI_ActionList_URL>(.*?)</").find(block)?.groupValues?.get(1)
                            if (type == "camera" && url != null) endpoint = url.trimEnd('/') + "/camera"
                        }
                }
            }

            val cam = SonyWifiCamera(net, cb, cm, endpoint, name)
            try {
                status("Iniciando modo remoto…")
                val apis = cam.call("getAvailableApiList").optJSONArray(0)
                val list = (0 until (apis?.length() ?: 0)).map { apis!!.getString(it) }
                if ("startRecMode" in list) {
                    cam.call("startRecMode")
                    delay(1500)
                }
                // Pede a foto em tamanho original (em alguns modelos o padrão é 2 MP)
                runCatching { cam.call("setPostviewImageSize", JSONArray().put("Original")) }
            } catch (e: Exception) {
                cam.close()
                throw IOException(
                    "Câmera não respondeu. Conecte o celular ao Wi-Fi da câmera (DIRECT-…) e abra " +
                        "\"Ctrl c/ Smartphone\" / \"Smart Remote\" na câmera. (${e.message})",
                )
            }
            cam
        }

        /** Pede ao Android a rede Wi-Fi mesmo sem internet (a rede da câmera não tem). */
        private suspend fun acquireWifi(cm: ConnectivityManager): Pair<Network?, ConnectivityManager.NetworkCallback?> {
            var callback: ConnectivityManager.NetworkCallback? = null
            val net = withTimeoutOrNull(8000) {
                suspendCancellableCoroutine<Network> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) cont.resume(network)
                        }
                    }
                    callback = cb
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.requestNetwork(req, cb)
                }
            }
            if (net == null) {
                callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                return null to null
            }
            return net to callback
        }

        private fun ssdpDiscover(net: Network?): String? {
            val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n").toByteArray()
            return try {
                DatagramSocket().use { s ->
                    net?.bindSocket(s)
                    s.soTimeout = 1200
                    val addr = InetAddress.getByName("239.255.255.250")
                    repeat(3) { s.send(DatagramPacket(msg, msg.size, addr, 1900)) }
                    val buf = ByteArray(2048)
                    val deadline = System.currentTimeMillis() + 3500
                    while (System.currentTimeMillis() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(p)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val text = String(p.data, 0, p.length)
                        val loc = text.lines().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        if (loc != null) return loc.substringAfter(':').trim()
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun open(net: Network?, url: String): HttpURLConnection =
            (net?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection

        private fun httpGet(net: Network?, url: String, timeoutMs: Int): ByteArray {
            val c = open(net, url)
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            try {
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
    }

    /** Chamada JSON-RPC. Retorna o array "result" ou lança SonyApiException. */
    private suspend fun call(
        method: String, params: JSONArray = JSONArray(), version: String = "1.0", timeoutMs: Int = 10_000,
    ): JSONArray = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("method", method); put("params", params); put("id", rpcId++); put("version", version)
        }.toString().toByteArray()
        val c = open(network, endpoint)
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body) }
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val o = JSONObject(text)
            o.optJSONArray("error")?.let { err ->
                throw SonyApiException("${method}: ${err.optString(1)} (${err.optInt(0)})", err.optInt(0))
            }
            o.optJSONArray("result") ?: JSONArray()
        } finally {
            c.disconnect()
        }
    }

    override suspend fun trigger() {
        val result = try {
            call("actTakePicture", timeoutMs = 30_000)
        } catch (e: SonyApiException) {
            // 40403 = exposição longa ainda em andamento
            if (e.code == 40403) call("awaitTakePicture", timeoutMs = 60_000) else throw e
        }
        val urls = result.optJSONArray(0) ?: return
        for (i in 0 until urls.length()) download(urls.getString(i))
    }

    private suspend fun download(url: String) {
        val isNew = seenLock.withLock { seenUrls.add(url) }
        if (!isNew) return
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "Sony_${System.currentTimeMillis()}.jpg" }
            .let { if ('.' in it) it else "$it.jpg" }
        infoSink?.invoke("Recebendo $name…")
        val bytes = withContext(Dispatchers.IO) { httpGet(network, url, 30_000) }
        captureSink?.invoke(CapturedFile(name, bytes))
    }

    /** Escuta fotos feitas no botão da própria câmera usando getEvent (long polling). */
    override suspend fun listen(onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit) {
        captureSink = onCapture
        infoSink = onInfo
        onInfo("Pronto. Dispare pelo app ou pela câmera.")
        var failures = 0
        while (coroutineContext.isActive) {
            try {
                val res = call("getEvent", JSONArray().put(true), timeoutMs = 65_000)
                failures = 0
                for (url in findTakePictureUrls(res)) download(url)
            } catch (e: SonyApiException) {
                // 40402 = já existe um getEvent pendente; 2 = parâmetro; espera e tenta de novo
                delay(500)
            } catch (e: SocketTimeoutException) {
                // long polling sem novidades
            } catch (e: IOException) {
                if (++failures >= 4) throw IOException("Conexão Wi-Fi com a câmera perdida")
                delay(1000)
            }
        }
    }

    private fun findTakePictureUrls(any: Any?): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> {
                    if (v.optString("type") == "takePicture") {
                        v.optJSONArray("takePictureUrl")?.let { a ->
                            for (i in 0 until a.length()) out += a.getString(i)
                        }
                    } else {
                        v.keys().forEach { walk(v.opt(it)) }
                    }
                }
            }
        }
        walk(any)
        return out
    }

    override fun close() {
        captureSink = null
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
}
