package com.mufeng.xhs.location.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mufeng.xhs.location.R
import com.mufeng.xhs.location.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * VPN 代理服务。
 *
 * 工作原理：
 *  1. 通过 [VpnService] 创建 TUN 网卡并接管系统默认路由（0.0.0.0/0），其他 App 的流量进入本服务。
 *  2. 本服务在用户态实现一个最小可用的 IPv4 + TCP 转发栈：
 *     - 解析 TUN 中的 IP 包；
 *     - TCP 连接请求经 SOCKS5 / HTTP CONNECT 代理隧道转发出去；
 *     - 代理返回的数据封回 TCP 包写回 TUN。
 *  3. DNS（UDP 端口 53）被本服务拦截：在直连网络（本 App 已从 VPN 路由中排除）解析域名，
 *     把 A 记录封回 DNS 响应。AAAA 返回 NODATA，App 自动回退 IPv4。
 *  4. 本 App 自身的网络请求（代理连接、DNS 解析）不经过 VPN，避免回环。
 *
 * 说明：
 *  - 只支持 IPv4 / TCP 与 UDP/53(DNS)，不支持 ICMP（ping 不可用是预期行为）。
 *  - 免费代理质量不稳定，若连接代理失败，对应 TCP 连接会被 RST，App 表现为无法上网。
 */
class VpnProxyService : VpnService() {

    private val notificationId = 2001
    private val channelId = "vpn_proxy"

    @Volatile private var running = false
    private var tunFd: ParcelFileDescriptor? = null
    private var loopThread: Thread? = null
    private var reaperThread: Thread? = null

    private val writeLock = Any()
    private val idCounter = AtomicInteger(1)

    /** key → 连接状态，key 为 "srcIp:srcPort>dstIp:dstPort"（客户端视角） */
    private val conns = ConcurrentHashMap<String, Conn>()

    /** DNS 解析映射：host → (时间戳, A 记录)。ing 转发代理时优先使用域名 CONNECT */
    private val dnsMapLock = Any()
    private val dnsHostToIps = LinkedHashMap<String, Pair<Long, List<Inet4Address>>>()

    /** DNS 解析线程池（解析动作不占用 TUN 主循环） */
    private val dnsExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "vpn-dns").apply { isDaemon = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpnAndSelf()
            null -> if (readProxyConfig().enabled) startVpn() // 系统粘性拉起：恢复上次启用的 VPN
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    // ========== 启动 / 停止 ==========

    private fun startVpn() {
        if (running) {
            Log.i(TAG, "startVpn: already running")
            return
        }
        val config = readProxyConfig()
        if (config.server.isBlank()) {
            Log.w(TAG, "startVpn: proxy server not configured")
            startForegroundWith("VPN 启动失败：未配置代理服务器")
            stopSelf()
            return
        }

        running = true
        startForegroundWith("VPN 代理连接中…")

        try {
            val builder = Builder()
                .setSession(SESSION_NAME)
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS)
                .setMtu(1500)
                .addDisallowedApplication(packageName)

            val configureIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                this, 0, configureIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setConfigureIntent(pending)

            tunFd = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            tunFd = null
        }

        val fd = tunFd
        if (fd == null) {
            Log.e(TAG, "establish returned null (VPN 授权未通过？)")
            updateNotification("VPN 连接被拒绝，请在系统弹框中点击“允许”")
            running = false
            stopForeground(true)
            stopSelf()
            return
        }

        Log.i(TAG, "VPN established, proxy=${config.server}:${config.port} type=${config.type}")
        updateNotification("VPN 代理已连接：${config.server}")

        loopThread = thread(start = true, isDaemon = true, name = "vpn-tun-loop") { runTunLoop(fd) }
        reaperThread = thread(start = true, isDaemon = true, name = "vpn-reaper") { runReaper() }
    }

    private fun stopVpnAndSelf() {
        teardown()
        stopForeground(true)
        stopSelf()
    }

    private fun teardown() {
        running = false
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        for (conn in conns.values) {
            try { conn.tunnel?.close() } catch (_: Exception) {}
        }
        conns.clear()
        synchronized(dnsMapLock) { dnsHostToIps.clear() }
        dnsExecutor.shutdownNow()
    }

    private fun runReaper() {
        while (running) {
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
                return
            }
            val now = System.currentTimeMillis()
            val it = conns.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val conn = entry.value
                if (now - conn.lastActivity > IDLE_TIMEOUT_MS || (conn.clientClosed && conn.ourFinSent)) {
                    try { conn.tunnel?.close() } catch (_: Exception) {}
                    it.remove()
                }
            }
        }
    }

    // ========== TUN 主循环 ==========

    private fun runTunLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(65535)
        while (running) {
            val len = try {
                input.read(buffer)
            } catch (e: Exception) {
                Log.w(TAG, "tun read failed (probably closing): ${e.message}")
                break
            }
            if (len <= 0) continue
            try {
                handlePacket(buffer, len)
            } catch (e: Exception) {
                Log.e(TAG, "handlePacket error", e)
            }
        }
        Log.i(TAG, "tun loop exited")
    }

    private fun handlePacket(packet: ByteArray, len: Int) {
        if (len < 20) return
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (len < ihl) return
        val totalLen = (((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)).coerceAtMost(len)
        val frag = (((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)) and 0x1FFF
        if (frag != 0) return // 不处理分片
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = readInt(packet, 12)
        val dstIp = readInt(packet, 16)

        when (protocol) {
            6 -> handleTcp(packet, totalLen, ihl, srcIp, dstIp)
            17 -> handleUdp(packet, totalLen, ihl, srcIp, dstIp)
            else -> Unit // ICMP 等协议不转发（ping 不可用属于预期）
        }
    }

    // ========== TCP ==========

    private fun handleTcp(packet: ByteArray, totalLen: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 20) return
        val off = ihl
        val srcPort = readUShort(packet, off)
        val dstPort = readUShort(packet, off + 2)
        val seq = readSeq(packet, off + 4)
        val ack = readSeq(packet, off + 8)
        val dataOffset = ((packet[off + 12].toInt() and 0xF0) ushr 4) * 4
        val flags = packet[off + 13].toInt() and 0xFF
        val dataStart = off + dataOffset
        if (dataStart > totalLen) return
        val dataLen = totalLen - dataStart
        val isSyn = flags and FLAG_SYN != 0
        val isFin = flags and FLAG_FIN != 0
        val isRst = flags and FLAG_RST != 0
        val isAck = flags and FLAG_ACK != 0

        val key = "${ipToString(srcIp)}:$srcPort>${ipToString(dstIp)}:$dstPort"

        if (isSyn && !isAck) {
            // 新连接（可能是重传，幂等处理）
            val existing = conns[key]
            if (existing != null) {
                sendSynAck(existing)
                return
            }
            val config = readProxyConfig()
            val conn = Conn(
                key = key,
                srcIp = srcIp, srcPort = srcPort, dstIp = dstIp, dstPort = dstPort,
                clientNextSeq = seq + 1,
                sendSeq = randomInitialSeq(),
                lastActivity = System.currentTimeMillis()
            )
            conns[key] = conn
            sendSynAck(conn)

            thread(start = true, isDaemon = true, name = "vpn-connect") {
                try {
                    // 有 DNS 映射时把域名传给代理（ATYP=DOMAIN），避免代理拒绝裸 IP CONNECT
                    val targetHost = findHostForIp(dstIp) ?: ipToString(dstIp)
                    val tunnel: ProxyTunnel = if (config.type == ProxyType.HTTP) {
                        HttpProxyClient.create(config.server, config.port)
                    } else {
                        Socks5Client.create(config.server, config.port)
                    }
                    tunnel.connect(targetHost, dstPort)
                    synchronized(conn) {
                        conn.tunnel = tunnel
                        conn.connected = true
                        for (pending in conn.pending) {
                            tunnel.write(pending, 0, pending.size)
                        }
                        conn.pending.clear()
                    }
                    Log.i(TAG, "proxy connected -> ${ipToString(dstIp)}:$dstPort")
                    startUpstreamReader(conn)
                } catch (e: Exception) {
                    Log.e(TAG, "proxy connect failed -> ${ipToString(dstIp)}:$dstPort: ${e.message}")
                    sendRst(conn)
                    conns.remove(key)
                    updateNotification("VPN：连接代理失败 (${e.message ?: "unknown"})")
                }
            }
            return
        }

        val conn = conns[key]
        if (conn == null) {
            if (!isRst) {
                // 无连接上下文：回 RST|ACK
                val rstAck = seq + dataLen + (if (isFin) 1 else 0)
                writeTcp(dstIp, dstPort, srcIp, srcPort, 0, rstAck, FLAG_RST or FLAG_ACK, null)
            }
            return
        }

        if (isRst) {
            closeAndRemove(conn)
            return
        }

        val ackForClient = {
            // ACK 不携带数据，只占用不新 seq；读取 sendSeq 需要与上游读线程串行
            synchronized(conn) {
                writeTcp(dstIp, dstPort, srcIp, srcPort, conn.sendSeq, conn.clientNextSeq, FLAG_ACK, null)
            }
        }

        if (dataLen > 0 || isFin) {
            if (seq + dataLen < conn.clientNextSeq) {
                // 纯重传：只补 ACK
                if (isFin && seq + dataLen + 1L == conn.clientNextSeq) markClientClosed(conn) else ackForClient()
                if (conn.clientClosed && conn.ourFinSent) closeAndRemove(conn)
                return
            }
            if (seq != conn.clientNextSeq) {
                // 乱序：丢弃并 ACK 当前期望
                ackForClient()
                return
            }
            // 精确匹配：转发数据
            if (dataLen > 0) {
                val payload = packet.copyOfRange(dataStart, dataStart + dataLen)
                forwardUpstream(conn, payload)
                synchronized(conn) { conn.clientNextSeq += dataLen }
            }
            if (isFin) {
                markClientClosed(conn)
                sendFin(conn)
            } else {
                ackForClient()
            }
            return
        }

        if (isAck) {
            if (conn.ourFinSent) {
                // 客户端确认了我们的 FIN：连接可以回收
                closeAndRemove(conn)
            } else {
                conn.lastActivity = System.currentTimeMillis()
            }
        }
    }

    private fun markClientClosed(conn: Conn) {
        if (conn.clientClosed) return
        synchronized(conn) {
            if (conn.clientClosed) return
            conn.clientClosed = true
            conn.clientNextSeq += 1
        }
        try { conn.tunnel?.close() } catch (_: Exception) {}
    }

    private fun forwardUpstream(conn: Conn, data: ByteArray) {
        conn.lastActivity = System.currentTimeMillis()
        synchronized(conn) {
            val tunnel = conn.tunnel
            if (tunnel != null && conn.connected) {
                try {
                    tunnel.write(data, 0, data.size)
                } catch (e: Exception) {
                    Log.e(TAG, "upstream write failed: ${e.message}")
                    sendRst(conn)
                }
            } else if (conn.pending.size < MAX_PENDING_BYTES) {
                conn.pending.add(data)
            }
        }
    }

    private fun startUpstreamReader(conn: Conn) {
        thread(start = true, isDaemon = true, name = "vpn-read") {
            val buffer = ByteArray(1400)
            while (running) {
                val tunnel = conn.tunnel ?: break
                val n = try {
                    tunnel.read(buffer, 0, buffer.size)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: java.io.InterruptedIOException) {
                    continue
                } catch (e: Exception) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                val payload = buffer.copyOfRange(0, n)
                synchronized(conn) {
                    writeTcp(
                        conn.dstIp, conn.dstPort, conn.srcIp, conn.srcPort,
                        conn.sendSeq, conn.clientNextSeq,
                        FLAG_PSH or FLAG_ACK, payload
                    )
                    conn.sendSeq += n
                    conn.lastActivity = System.currentTimeMillis()
                }
            }
            // 上游关闭：向客户端发 FIN
            if (running && !conn.ourFinSent && conns.containsKey(conn.key)) {
                sendFin(conn)
            }
        }
    }

    private fun sendSynAck(conn: Conn) {
        synchronized(conn) {
            writeTcp(conn.dstIp, conn.dstPort, conn.srcIp, conn.srcPort,
                conn.sendSeq, conn.clientNextSeq, FLAG_SYN or FLAG_ACK, null)
            conn.sendSeq += 1
        }
    }

    private fun sendFin(conn: Conn) {
        if (conn.ourFinSent) return
        synchronized(conn) {
            if (conn.ourFinSent) return
            writeTcp(conn.dstIp, conn.dstPort, conn.srcIp, conn.srcPort,
                conn.sendSeq, conn.clientNextSeq, FLAG_FIN or FLAG_ACK, null)
            conn.sendSeq += 1
            conn.ourFinSent = true
        }
        if (conn.clientClosed) {
            closeAndRemove(conn)
        }
    }

    private fun sendRst(conn: Conn) {
        synchronized(conn) {
            writeTcp(conn.dstIp, conn.dstPort, conn.srcIp, conn.srcPort,
                conn.sendSeq, conn.clientNextSeq, FLAG_RST or FLAG_ACK, null)
        }
        closeAndRemove(conn)
    }

    private fun closeAndRemove(conn: Conn) {
        try { conn.tunnel?.close() } catch (_: Exception) {}
        conns.remove(conn.key)
    }

    // ========== DNS 映射 ==========

    private fun recordDnsMapping(host: String, ips: List<Inet4Address>) {
        synchronized(dnsMapLock) {
            dnsHostToIps[host] = System.currentTimeMillis() to ips
            // 限量：超过 200 条清掉最旧的
            while (dnsHostToIps.size > 200) {
                val oldestKey = dnsHostToIps.minByOrNull { it.value.first }?.key ?: break
                dnsHostToIps.remove(oldestKey)
            }
        }
    }

    private fun findHostForIp(ip: Int): String? {
        val now = System.currentTimeMillis()
        synchronized(dnsMapLock) {
            // 清理过期（10 分钟）
            val expired = dnsHostToIps.filterValues { now - it.first > 10 * 60 * 1000L }.keys
            expired.forEach { dnsHostToIps.remove(it) }
            for ((host, entry) in dnsHostToIps) {
                if (entry.second.any { addr -> inetToInt(addr) == ip }) return host
            }
        }
        return null
    }

    private fun inetToInt(addr: Inet4Address): Int {
        val b = addr.address
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    // ========== UDP (仅 DNS) ==========

    private fun handleUdp(packet: ByteArray, totalLen: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 8) return
        val off = ihl
        val srcPort = readUShort(packet, off)
        val dstPort = readUShort(packet, off + 2)
        val udpLen = readUShort(packet, off + 4)
        if (dstPort != 53) return // 只拦截 DNS
        val dnsStart = off + 8
        val dnsLen = (udpLen - 8).coerceAtMost(totalLen - dnsStart)
        if (dnsLen < 12) return
        val dnsPayload = packet.copyOfRange(dnsStart, dnsStart + dnsLen)

        val id = readUShort(dnsPayload, 0)
        val qdCount = readUShort(dnsPayload, 4)
        if (qdCount < 1) return

        // 解析 qname
        var pos = 12
        val nameBuilder = StringBuilder()
        var ok = true
        while (pos < dnsPayload.size) {
            val lenByte = dnsPayload[pos].toInt() and 0xFF
            if (lenByte == 0) { pos += 1; break }
            if (lenByte and 0xC0 == 0xC0) { pos += 2; break } // 压缩指针（查询中不应出现）
            if (pos + 1 + lenByte > dnsPayload.size) { ok = false; break }
            nameBuilder.append('.').append(String(dnsPayload, pos + 1, lenByte, Charsets.UTF_8))
            pos += 1 + lenByte
        }
        if (!ok || pos + 4 > dnsPayload.size) return
        val host = nameBuilder.removePrefix(".").toString()
        val qtype = readUShort(dnsPayload, pos)
        val questionEnd = (pos + 4).coerceAtMost(dnsPayload.size)
        val questionBytes = dnsPayload.copyOfRange(0, questionEnd)

        Log.d(TAG, "DNS query host=$host qtype=$qtype id=$id")

        dnsExecutor.execute {
            val answers: List<ByteArray> = if (qtype == QTYPE_A) {
                try {
                    InetAddress.getAllByName(host)
                        .filterIsInstance<Inet4Address>()
                        .take(4)
                        .also { list ->
                            // 记录 host -> IP 映射，供代理 CONNECT 使用域名
                            if (list.isNotEmpty()) recordDnsMapping(host, list)
                        }
                        .map { it.address }
                } catch (e: Exception) {
                    Log.w(TAG, "DNS resolve failed host=$host: ${e.message}")
                    emptyList()
                }
            } else {
                emptyList() // AAAA 等：NODATA 响应
            }

            val response = buildDnsResponse(
                id = id,
                question = questionBytes,
                answers = answers,
                qdCount = qdCount
            )
            writeUdp(dstIp, 53, srcIp, srcPort, response)
        }
    }

    private fun buildDnsResponse(id: Int, question: ByteArray, answers: List<ByteArray>, qdCount: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // 头部
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        // flags: QR=1, RD=1, RA=1, RCODE=0（NOERROR）；无答案时也按 NOERROR+NODATA 处理
        out.write(0x81); out.write(0x80)
        writeShort(out, qdCount)
        writeShort(out, answers.size)
        writeShort(out, 0)
        writeShort(out, 0)
        // 问题区原样回显
        out.write(question, 12, question.size - 12)
        // 答案区：name 使用压缩指针 C00C 指向问题区开头的名字
        for (ip in answers) {
            out.write(0xC0); out.write(0x0C)
            writeShort(out, QTYPE_A)
            writeShort(out, 1) // IN
            writeIntBytes(out, 60) // TTL
            writeShort(out, 4)
            out.write(ip)
        }
        return out.toByteArray()
    }

    private fun writeUdp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray) {
        val udp = ByteArray(8 + payload.size)
        writeUShort(udp, 0, srcPort)
        writeUShort(udp, 2, dstPort)
        writeUShort(udp, 4, udp.size)
        writeUShort(udp, 6, 0) // checksum = 0（IPv4 可选）
        System.arraycopy(payload, 0, udp, 8, payload.size)
        writeIp(srcIp, dstIp, PROTO_UDP, udp)
    }

    // ========== 包构造 ==========

    private fun writeTcp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
                         seq: Long, ack: Long, flags: Int, payload: ByteArray?) {
        val data = payload ?: ByteArray(0)
        val tcp = ByteArray(20 + data.size)
        writeUShort(tcp, 0, srcPort)
        writeUShort(tcp, 2, dstPort)
        writeSeq(tcp, 4, seq)
        writeSeq(tcp, 8, ack)
        tcp[12] = (5 shl 4).toByte() // dataOffset=5 (20 bytes)
        tcp[13] = flags.toByte()
        writeUShort(tcp, 14, 32768) // 窗口
        writeUShort(tcp, 16, 0)     // checksum 先占位
        writeUShort(tcp, 18, 0)     // urgent
        if (data.isNotEmpty()) System.arraycopy(data, 0, tcp, 20, data.size)

        val checksum = tcpChecksum(srcIp, dstIp, tcp)
        writeUShort(tcp, 16, checksum)

        writeIp(srcIp, dstIp, PROTO_TCP, tcp)
    }

    private fun tcpChecksum(srcIp: Int, dstIp: Int, tcp: ByteArray): Int {
        // 伪首部：src(4) + dst(4) + zero(1) + protocol(1) + tcpLength(2)
        val pseudo = ByteArray(12)
        writeInt(pseudo, 0, srcIp)
        writeInt(pseudo, 4, dstIp)
        pseudo[8] = 0
        pseudo[9] = PROTO_TCP.toByte()
        writeUShort(pseudo, 10, tcp.size)
        return internetChecksum(pseudo, tcp)
    }

    private fun writeIp(srcIp: Int, dstIp: Int, protocol: Int, payload: ByteArray) {
        val total = 20 + payload.size
        val ip = ByteArray(total)
        ip[0] = 0x45.toByte()
        ip[1] = 0
        writeUShort(ip, 2, total)
        writeUShort(ip, 4, idCounter.getAndIncrement() and 0xFFFF)
        writeUShort(ip, 6, 0x4000) // DF
        ip[8] = 64 // TTL
        ip[9] = protocol.toByte()
        writeUShort(ip, 10, 0) // checksum 先占位
        writeInt(ip, 12, srcIp)
        writeInt(ip, 16, dstIp)
        System.arraycopy(payload, 0, ip, 20, payload.size)
        writeUShort(ip, 10, internetChecksum(null, ip, 0, 20))
        synchronized(writeLock) {
            try {
                val output = FileOutputStream(tunFd!!.fileDescriptor)
                output.write(ip)
                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "tun write failed: ${e.message}")
            }
        }
    }

    /** 计算 16 位反码校验和（internet checksum） */
    private fun internetChecksum(pseudo: ByteArray?, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var sum = 0L
        fun addBytes(buf: ByteArray, off: Int, len: Int) {
            var i = off
            var remaining = len
            while (remaining >= 2) {
                sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                i += 2
                remaining -= 2
            }
            if (remaining == 1) {
                sum += (buf[i].toInt() and 0xFF) shl 8
            }
        }
        if (pseudo != null) addBytes(pseudo, 0, pseudo.size)
        addBytes(data, offset, length)
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    // ========== 字节工具 ==========

    private fun readUShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun readSeq(b: ByteArray, off: Int): Long =
        readInt(b, off).toLong() and 0xFFFFFFFFL

    private fun writeUShort(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 24) and 0xFF).toByte()
        b[off + 1] = ((value ushr 16) and 0xFF).toByte()
        b[off + 2] = ((value ushr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }

    private fun writeSeq(b: ByteArray, off: Int, value: Long) {
        writeInt(b, off, (value and 0xFFFFFFFFL).toInt())
    }

    private fun writeShort(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeIntBytes(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    private fun randomInitialSeq(): Long =
        (System.nanoTime() and 0x7FFFFFFFL) or 1L

    // ========== 通知 ==========

    private fun startForegroundWith(text: String) {
        createChannel()
        startForeground(notificationId, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            channelId, "VPN 代理", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = "VPN 代理运行状态" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): android.app.Notification {
        val stopIntent = Intent(this, VpnProxyService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VPN 代理")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "停止 VPN", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            NotificationManagerCompat.from(this).notify(notificationId, buildNotification(status))
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification failed: ${e.message}")
        }
    }

    private fun readProxyConfig(): ProxyConfig {
        val prefs = getSharedPreferences("vlocation_preferences", Context.MODE_PRIVATE)
        return ProxyConfig(
            server = prefs.getString(KEY_PROXY_SERVER, "") ?: "",
            port = prefs.getInt(KEY_PROXY_PORT, 1080),
            type = prefs.getString(KEY_PROXY_TYPE, ProxyType.SOCKS5) ?: ProxyType.SOCKS5,
            enabled = prefs.getBoolean(KEY_PROXY_ENABLED, true),
            lastUpdated = prefs.getLong(KEY_PROXY_LAST_UPDATED, 0L)
        )
    }

    private inner class Conn(
        val key: String,
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int,
        var clientNextSeq: Long,
        var sendSeq: Long,
        var tunnel: ProxyTunnel? = null,
        @Volatile var connected: Boolean = false,
        @Volatile var clientClosed: Boolean = false,
        @Volatile var ourFinSent: Boolean = false,
        @Volatile var lastActivity: Long = System.currentTimeMillis()
    ) {
        val pending: MutableList<ByteArray> = mutableListOf()
    }

    companion object {
        private const val TAG = "VpnProxyService"

        const val ACTION_START = "com.mufeng.xhs.location.vpn.action.START"
        const val ACTION_STOP = "com.mufeng.xhs.location.vpn.action.STOP"

        private const val SESSION_NAME = "xhs-location-vpn"
        private const val TUN_ADDRESS = "10.7.0.2"
        private const val TUN_DNS = "10.7.0.2"
        private const val IDLE_TIMEOUT_MS = 180_000L
        private const val MAX_PENDING_BYTES = 512 * 1024

        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17

        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10

        private const val QTYPE_A = 1

        fun createStartIntent(context: Context): Intent =
            Intent(context, VpnProxyService::class.java).apply { action = ACTION_START }

        fun createStopIntent(context: Context): Intent =
            Intent(context, VpnProxyService::class.java).apply { action = ACTION_STOP }
    }
}
