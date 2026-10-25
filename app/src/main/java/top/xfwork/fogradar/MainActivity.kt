package top.xfwork.fogradar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

class MainActivity : Activity() {
    private val serviceUuid = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
    private val notifyUuid = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var adapter: BluetoothAdapter? = null
    private var connected = false
    private var simulated = true
    private var latest = AtomicReference<Snapshot?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("fog_params", MODE_PRIVATE) }
    private var params = Params()
    private lateinit var radarView: RadarView
    private lateinit var status: TextView
    private lateinit var primary: LinearLayout
    private lateinit var btnConnect: Button
    private var frameCount = 0L
    private var recentFrame = 0L
    private var logWriter: java.io.BufferedWriter? = null
    private var tone: android.media.ToneGenerator? = null
    private var beepAt = 0L
    private val parser = RadarParser { snap ->
        latest.set(snap); frameCount++; recentFrame=SystemClock.elapsedRealtime()
        try { logWriter?.apply {
            val ts=System.currentTimeMillis(); write("F,$ts,${snap.raw.joinToString(""){ "%02X".format(it.toInt() and 255) }}\n")
            snap.targets.forEach { write("T,$ts,${it.angle},${it.range},${if(it.approaching) 1 else 0},${it.speed},${it.snr}\n") }
            flush()
        } } catch (_: Exception) { }
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state)
        params=Params(
            laneWidth=prefs.getFloat("laneWidth",3.5f),radarOffset=prefs.getFloat("radarOffset",0f),
            yaw=prefs.getFloat("yaw",0f),rangeBias=prefs.getFloat("rangeBias",0f),
            laneShift=prefs.getFloat("laneShift",0f),snrMin=prefs.getInt("snrMin",4),
            keepSeconds=prefs.getFloat("keepSeconds",1f),staleMs=prefs.getLong("staleMs",650),
            alarmEnabled=prefs.getBoolean("alarm",false),maxRange=prefs.getInt("maxRange",100),showAngles=true)
        adapter=(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        tone=try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC,55) }catch(_:Exception){null}
        primary=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(10,8,10,4);setBackgroundColor(Color.rgb(7,17,28))}
        status=TextView(this).apply{setTextColor(Color.WHITE);textSize=14f;text="模拟模式 · 点击连接搜索LD2451"}
        val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        btnConnect=Button(this).apply{text="搜索蓝牙";setOnClickListener{requestScan()}}
        val demo=Button(this).apply{text="模拟/实测";setOnClickListener{simulated=!simulated; status.text=if(simulated)"模拟模式" else "实测模式：请连接雷达"}}
        val settings=Button(this).apply{text="设置";setOnClickListener{showSettings()}}
        for (b in arrayOf(btnConnect,demo,settings))buttons.addView(b,LinearLayout.LayoutParams(0,-2,1f))
        radarView=RadarView(this)
        primary.addView(status);primary.addView(buttons)
        primary.addView(radarView,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(this)
        val log=Button(this).apply{text="开始记录";setOnClickListener {if(logWriter==null){
            val f=File(getExternalFilesDir(null),"fogradar_${System.currentTimeMillis()}.csv")
            logWriter=f.bufferedWriter();logWriter?.write("TYPE,TIMESTAMP,RAW_OR_ANGLE,DISTANCE,DIRECTION,SPEED,SNR\n");text="停止记录";Toast.makeText(this@MainActivity,f.absolutePath,Toast.LENGTH_LONG).show()
        } else{logWriter?.close();logWriter=null;text="开始记录"}}}
        val share=Button(this).apply{text="日志目录";setOnClickListener{Toast.makeText(this@MainActivity,getExternalFilesDir(null)?.absolutePath,Toast.LENGTH_LONG).show()}}
        footer.addView(log,LinearLayout.LayoutParams(0,-2,1f));footer.addView(share,LinearLayout.LayoutParams(0,-2,1f));primary.addView(footer)
        setContentView(primary)
        handler.post(object:Runnable {override fun run(){val now=SystemClock.elapsedRealtime();if(simulated)latest.set(exampleSnapshot(now))
            val s=latest.get();val age=if(s==null)Long.MAX_VALUE else now-s.timeMs
            val valid=simulated || (connected && age <= params.staleMs)
            radarView.update(if(valid)s else null,params)
            status.text=when {simulated->"演示数据 | ${s?.targets?.size?:0} 目标";!connected->"未连接 · 蓝牙需要实机验证";!valid->"蓝牙已连接 · 数据过期/无效";else->"实测在线 | ${s?.targets?.size?:0} 目标 | ${age}ms | 帧 ${frameCount}"}
            if(params.alarmEnabled&&valid&&s!=null){val nearest=s.targets.filter{it.approaching&&it.range in 1..100&&it.snr>=params.snrMin}.minByOrNull{it.range};if(nearest!=null){val interval=(100+4900*(nearest.range/100.0).pow(1.5)).toLong().coerceAtLeast(150);if(now-beepAt>=interval){tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP,100);beepAt=now}}}
            handler.postDelayed(this,33)
        }})
    }
    private fun permitted():Boolean = if(Build.VERSION.SDK_INT>=31) checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun requestScan(){if(!permitted()){if(Build.VERSION.SDK_INT>=31)requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT),12)else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),12);return};scan()}
    @SuppressLint("MissingPermission") private fun scan(){val bt=adapter;if(bt==null||!bt.isEnabled){startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return}
        val found=linkedMapOf<String,BluetoothDevice>();val cb=object:ScanCallback(){override fun onScanResult(type:Int,r:ScanResult){val name=r.device.name?:r.scanRecord?.deviceName?:"";if(name.contains("LD2451",true)||name.contains("HLK",true))found[r.device.address]=r.device}}
        scanning=true;bt.bluetoothLeScanner?.startScan(cb)
        handler.postDelayed({bt.bluetoothLeScanner?.stopScan(cb);scanning=false;val entries=found.values.toList();if(entries.isEmpty()){Toast.makeText(this,"未扫描到HLK设备",Toast.LENGTH_SHORT).show();return@postDelayed};AlertDialog.Builder(this).setTitle("选择LD2451").setItems(entries.map{(it.name?:"BLE")+"  "+it.address}.toTypedArray()){_,n->connect(entries[n])}.show()},6500)
    }
    @SuppressLint("MissingPermission") private fun connect(device:BluetoothDevice){gatt?.close();connected=false;simulated=false;status.text="正在连接 ${device.address}"
        gatt=device.connectGatt(this,false,object:BluetoothGattCallback(){
            override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){if(newState==BluetoothProfile.STATE_CONNECTED){connected=true;g.discoverServices()}else{connected=false;g.close()}}
            override fun onServicesDiscovered(g:BluetoothGatt,status:Int){
                // Official demo uses AE00/AE02/AE01 for the LD2450 family;
                // it does not prove LD2451 data notifications share these UUIDs.
                val characteristic=g.getService(serviceUuid)?.getCharacteristic(notifyUuid)
                if(characteristic==null){runOnUiThread{this@MainActivity.status.text="已连接，未找到AE02特征，请抓取服务UUID"};return}
                g.setCharacteristicNotification(characteristic,true)
                val desc=characteristic.getDescriptor(cccd)
                if(desc!=null){if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(desc,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else {desc.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;g.writeDescriptor(desc)}}
            }
            override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){if(Build.VERSION.SDK_INT<33)parser.feed(c.value)}
            override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){parser.feed(value)}
        })
    }
    private fun showSettings(){val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,8)};val scroll=ScrollView(this);scroll.addView(panel)
        fun item(label:String,value:Float,min:Float,max:Float,step:Float,change:(Float)->Unit){val title=TextView(this);panel.addView(title);val seek=SeekBar(this);seek.max=((max-min)/step).roundToInt();seek.progress=((value-min)/step).roundToInt();title.text="$label: $value";seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){val v=min+p*step;title.text="$label: ${"%.2f".format(v)}";change(v)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}});panel.addView(seek)}
        var p=params
        item("车道宽度 m",p.laneWidth,3f,4.5f,0.1f){p=p.copy(laneWidth=it)}
        item("雷达安装偏移 m",p.radarOffset,-2f,2f,0.1f){p=p.copy(radarOffset=it)}
        item("角度纠偏 °",p.yaw,-15f,15f,0.5f){p=p.copy(yaw=it)}
        item("距离纠偏 m",p.rangeBias,-5f,5f,0.5f){p=p.copy(rangeBias=it)}
        item("车道模型偏移 m",p.laneShift,-5f,5f,0.1f){p=p.copy(laneShift=it)}
        item("SNR阈值",p.snrMin.toFloat(),0f,20f,1f){p=p.copy(snrMin=it.toInt())}
        item("目标保留延时 s (显示设置)",p.keepSeconds,0f,10f,0.5f){p=p.copy(keepSeconds=it)}
        item("数据超时 ms",p.staleMs.toFloat(),200f,5000f,100f){p=p.copy(staleMs=it.toLong())}
        val cb=CheckBox(this).apply{text="开启距离蜂鸣（测试）";isChecked=p.alarmEnabled;setOnCheckedChangeListener{_,v->p=p.copy(alarmEnabled=v)}};panel.addView(cb)
        AlertDialog.Builder(this).setTitle("模型校准 / 本地参数").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->params=p;prefs.edit().putFloat("laneWidth",p.laneWidth).putFloat("radarOffset",p.radarOffset).putFloat("yaw",p.yaw).putFloat("rangeBias",p.rangeBias).putFloat("laneShift",p.laneShift).putInt("snrMin",p.snrMin).putFloat("keepSeconds",p.keepSeconds).putLong("staleMs",p.staleMs).putBoolean("alarm",p.alarmEnabled).apply()}.show()
    }
    override fun onDestroy(){super.onDestroy();handler.removeCallbacksAndMessages(null);gatt?.close();logWriter?.close();tone?.release()}
}
class RadarView(context:Context): View(context){private val pen=Paint(3);private var snapshot:Snapshot?=null;private var params=Params()
    fun update(s:Snapshot?,p:Params){snapshot=s;params=p;invalidate()}
    override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.rgb(10,27,43));val w=width.toFloat();val h=height.toFloat();val left=42f;val right=w-12f;val top=32f;val bottom=h-45f;val ww=right-left;val hh=bottom-top
        pen.style=Paint.Style.FILL;pen.color=Color.rgb(18,42,62);c.drawRect(left,top,right,bottom,pen)
        pen.color=Color.rgb(27,73,77);val center=left+ww/2;c.drawRect(center-ww/10,top,center+ww/10,bottom,pen)
        for(i in 0..5){pen.color=Color.rgb(88,130,149);pen.strokeWidth=1f;c.drawLine(left+ww*i/5,top,left+ww*i/5,bottom,pen)}
        for(i in 0..10){val y=top+hh*i/10;pen.color=Color.rgb(72,104,125);c.drawLine(left,y,right,y,pen);pen.color=Color.LTGRAY;pen.textSize=11f;c.drawText("${(10-i)*params.maxRange/10}",2f,y+4,pen)}
        pen.textAlign=Paint.Align.CENTER;pen.textSize=12f;pen.color=Color.WHITE
        for(i in 0..4)c.drawText(arrayOf("L2","L1","C","R1","R2")[i],left+ww*(i+.5f)/5,19f,pen)
        if(params.showAngles){for(a in intArrayOf(-15,-10,-5,0,5,10,15)){val dx=tan(Math.toRadians(a.toDouble()))*params.maxRange;pen.color=if(a==0)Color.rgb(81,195,159) else Color.rgb(160,135,77);pen.strokeWidth=1f;c.drawLine(center,bottom,(center+dx/17.5*ww).toFloat(),top,pen)}}
        snapshot?.targets?.filter{it.snr>=params.snrMin}?.forEach { t-> val (x,y)=params.xy(t);if(y<0||y>params.maxRange||abs(x)>params.laneWidth*2.5)return@forEach
            val px=(center+x/(params.laneWidth*5)*ww).toFloat();val py=(bottom-y/params.maxRange*hh).toFloat()
            pen.color=if(t.approaching)Color.rgb(255,100,88) else Color.rgb(78,160,240);c.drawCircle(px,py,7f,pen)
            val str=t.ttc()?.takeIf{it<=99.9}?.let{"%.1fs".format(it)}?:"--";pen.color=Color.WHITE;pen.textSize=12f;pen.textAlign=Paint.Align.LEFT;c.drawText(str,(px+9).coerceAtMost(right-34),py-9,pen)
        }
        pen.color=Color.WHITE;pen.textSize=14f;pen.textAlign=Paint.Align.CENTER;c.drawText("雷达位置 / 0m",center,h-15f,pen)
    }
}
