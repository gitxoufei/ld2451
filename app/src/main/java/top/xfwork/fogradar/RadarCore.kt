package top.xfwork.fogradar

import java.io.ByteArrayOutputStream
import kotlin.math.*

data class Target(val angle: Int, val range: Int, val approaching: Boolean, val speed: Int, val snr: Int) {
    fun ttc(): Double? = if (approaching && speed > 0) 3.6 * range / speed else null
}
data class Snapshot(val timeMs: Long, val targets: List<Target>, val alert: Int, val raw: ByteArray)

// V1.03: F4 F3 F2 F1 | uint16 payload size | count | alert | 5*count | F8 F7 F6 F5.
class RadarParser(private val onFrame: (Snapshot) -> Unit) {
    private val data = ArrayList<Byte>(512)
    var invalidFrames = 0L; private set
    var frames = 0L; private set
    fun feed(bytes: ByteArray) {
        bytes.forEach { data.add(it) }
        while (data.size >= 6) {
            val i = data.indexOfFirst { it.toInt() and 255 == 0xF4 }
            if (i < 0) { data.clear(); return }
            if (i > 0) repeat(i) { data.removeAt(0) }
            if (data.size < 6) return
            if ((data[1].toInt() and 255) != 0xF3 || (data[2].toInt() and 255) != 0xF2 || (data[3].toInt() and 255) != 0xF1) { data.removeAt(0); continue }
            val len = (data[4].toInt() and 255) or ((data[5].toInt() and 255) shl 8)
            if (len < 2 || len > 252 || 10 + len > 300) { invalidFrames++; data.removeAt(0); continue }
            val total = 10 + len
            if (data.size < total) return
            val pkt = ByteArray(total) { data[it] }
            val tail = intArrayOf(0xF8,0xF7,0xF6,0xF5)
            if ((0..3).any { (pkt[total-4+it].toInt() and 255) != tail[it] }) { invalidFrames++; data.removeAt(0); continue }
            repeat(total) { data.removeAt(0) }
            val count = pkt[6].toInt() and 255
            if (len != 2 + count * 5) { invalidFrames++; continue }
            val targets = ArrayList<Target>()
            for (n in 0 until count) {
                val p = 8 + n*5
                val dir = pkt[p+2].toInt() and 255
                if (dir > 1) continue
                targets.add(Target((pkt[p].toInt() and 255)-128, pkt[p+1].toInt() and 255, dir==1, pkt[p+3].toInt() and 255, pkt[p+4].toInt() and 255))
            }
            frames++
            onFrame(Snapshot(android.os.SystemClock.elapsedRealtime(),targets,pkt[7].toInt() and 255,pkt))
        }
        if (data.size > 512) { data.clear(); invalidFrames++ }
    }
}
data class Params(val laneWidth: Float=3.5f, val radarOffset: Float=0f, val yaw: Float=0f,
                  val rangeBias: Float=0f, val laneShift: Float=0f, val snrMin: Int=4,
                  val keepSeconds: Float=1f, val staleMs: Long=650, val alarmEnabled: Boolean=false,
                  val maxRange: Int=100, val showAngles: Boolean=true) {
    fun xy(t: Target): Pair<Double,Double> {
        val d = (t.range + rangeBias).coerceAtLeast(0f)
        val a = Math.toRadians((t.angle + yaw).toDouble())
        return Pair(d*sin(a)+radarOffset+laneShift, d*cos(a))
    }
}
fun exampleSnapshot(t: Long): Snapshot {
    val phase = (t/200L)%300
    val a = Target(3,(95-phase/4).toInt().coerceIn(6,100),true,48,22)
    val b = Target(-6,(64+(phase%70)/3).toInt().coerceIn(1,100),false,20,17)
    val c = Target(10,(31+(phase%35)/2).toInt().coerceIn(1,100),true,25,14)
    return Snapshot(t,listOf(a,b,c),1,byteArrayOf())
}
