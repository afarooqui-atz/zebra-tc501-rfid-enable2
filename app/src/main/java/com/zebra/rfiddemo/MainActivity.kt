package com.zebra.rfiddemo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), ZebraRfidManager.RfidEventListener {

    private lateinit var rfidManager: ZebraRfidManager
    private lateinit var tvStatus: TextView
    private lateinit var tvTagCount: TextView
    private lateinit var tvTotalReads: TextView
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var sliderPower: SeekBar
    private lateinit var tvPowerValue: TextView
    private lateinit var rvTags: RecyclerView
    private lateinit var tagAdapter: TagAdapter

    private val tagMap = ConcurrentHashMap<String, TagItem>()
    private var totalReadCount = 0L
    private var toneGenerator: ToneGenerator? = null

    data class TagItem(val epc: String, var rssi: Short, var count: Int, var timestamp: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initAudio()
        checkPermissions()

        rfidManager = ZebraRfidManager.getInstance(this)
        rfidManager.setListener(this)
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvTagCount = findViewById(R.id.tvTagCount)
        tvTotalReads = findViewById(R.id.tvTotalReads)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        sliderPower = findViewById(R.id.sliderPower)
        tvPowerValue = findViewById(R.id.tvPowerValue)
        rvTags = findViewById(R.id.rvTags)

        rvTags.layoutManager = LinearLayoutManager(this)
        tagAdapter = TagAdapter(tagMap)
        rvTags.adapter = tagAdapter

        btnConnect.setOnClickListener {
            tvStatus.text = "Status: Connecting to Zebra Reader..."
            rfidManager.initReaders()
        }

        btnScan.setOnClickListener {
            if (btnScan.text == "START SCAN") {
                rfidManager.startInventory()
                btnScan.text = "STOP SCAN"
                btnScan.setBackgroundColor(0xFFE11D48.toInt()) // Red
            } else {
                rfidManager.stopInventory()
                btnScan.text = "START SCAN"
                btnScan.setBackgroundColor(0xFF2563EB.toInt()) // Blue
            }
        }

        sliderPower.max = 30 // 30 dBm
        sliderPower.progress = 27
        sliderPower.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(10)
                tvPowerValue.text = "$clamped dBm"
                if (fromUser) {
                    rfidManager.configureAntennaPower(clamped * 10)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initAudio() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playBeep(rssi: Short) {
        // High RSSI (-35 dBm) gives higher pitch tone
        val tone = when {
            rssi > -45 -> ToneGenerator.TONE_PROP_BEEP2
            rssi > -60 -> ToneGenerator.TONE_PROP_BEEP
            else -> ToneGenerator.TONE_PROP_ACK
        }
        toneGenerator?.startTone(tone, 40)
    }

    override fun onReaderConnected(readerName: String) {
        runOnUiThread {
            tvStatus.text = "Connected: $readerName"
            btnConnect.text = "DISCONNECT"
            btnScan.isEnabled = true
            Toast.makeText(this, "Connected to $readerName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onReaderDisconnected() {
        runOnUiThread {
            tvStatus.text = "Status: Disconnected"
            btnConnect.text = "CONNECT SLED"
            btnScan.isEnabled = false
            btnScan.text = "START SCAN"
        }
    }

    override fun onTagRead(epc: String, rssi: Short, count: Int, memoryBank: String?) {
        runOnUiThread {
            playBeep(rssi)
            totalReadCount++

            val existing = tagMap[epc]
            if (existing != null) {
                existing.count = count
                existing.rssi = rssi
                existing.timestamp = System.currentTimeMillis()
            } else {
                tagMap[epc] = TagItem(epc, rssi, count, System.currentTimeMillis())
            }

            tvTagCount.text = tagMap.size.toString()
            tvTotalReads.text = totalReadCount.toString()
            tagAdapter.notifyDataSetChanged()
        }
    }

    override fun onTriggerChanged(isPressed: Boolean) {
        runOnUiThread {
            btnScan.text = if (isPressed) "SCANNING (TRIGGER)" else "START SCAN"
            btnScan.setBackgroundColor(if (isPressed) 0xFFD97706.toInt() else 0xFF2563EB.toInt())
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            tvStatus.text = "Error: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidManager.disconnect()
        toneGenerator?.release()
    }

    inner class TagAdapter(private val items: Map<String, TagItem>) : RecyclerView.Adapter<TagAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvEpc: TextView = v.findViewById(R.id.itemEpc)
            val tvRssi: TextView = v.findViewById(R.id.itemRssi)
            val tvCount: TextView = v.findViewById(R.id.itemCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tag, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items.values.toList()[position]
            holder.tvEpc.text = item.epc
            holder.tvRssi.text = "${item.rssi} dBm"
            holder.tvCount.text = "Reads: ${item.count}"
        }

        override fun getItemCount() = items.size
    }
}
