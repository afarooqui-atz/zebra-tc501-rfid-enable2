package com.zebra.rfiddemo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zebra.rfid.api3.*
import java.util.concurrent.Executors

/**
 * Zebra RFID API3 Manager for the TC501 integrated RFID reader.
 * Handles:
 * - Integrated reader detection through QC_SERIAL
 * - Connection lifecycle & reconnection
 * - Trigger pull/release events
 * - Tag inventory reads & RSSI telemetry
 * - Antenna transmit power (dBm) configuration
 */
class ZebraRfidManager(private val context: Context) : RfidEventsListener {

    companion object {
        private const val TAG = "ZebraRfidManager"
        @Volatile private var instance: ZebraRfidManager? = null

        fun getInstance(context: Context): ZebraRfidManager {
            return instance ?: synchronized(this) {
                instance ?: ZebraRfidManager(context.applicationContext).also { instance = it }
            }
        }
    }

    interface RfidEventListener {
        fun onReaderConnected(readerName: String)
        fun onReaderDisconnected()
        fun onTagRead(epc: String, rssi: Short, count: Int, memoryBank: String?)
        fun onTriggerChanged(isPressed: Boolean)
        fun onError(message: String)
    }

    private var readers: Readers? = null
    private var rfidReader: RFIDReader? = null
    private var isConnected = false
    private var isInventoryRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: RfidEventListener? = null

    // Cache of scanned tags: EPC -> (RSSI, Count)
    private val tagCache = mutableMapOf<String, TagRecord>()

    data class TagRecord(val epc: String, var rssi: Short, var count: Int, var memoryBank: String? = null)

    fun setListener(listener: RfidEventListener) {
        this.listener = listener
    }

    /**
     * Initializes the Zebra Readers object to discover connected sleds or integrated readers.
     */
    /**
     * Initializes the Zebra API3 SDK for the TC501 integrated RFID reader.
     */
    fun initReaders() {
        executor.execute {
            try {
                Log.d(TAG, "Initializing TC501 integrated RFID through QC_SERIAL...")
    
                // Clean up an existing SDK object before creating another one.
                try {
                    readers?.Dispose()
                } catch (e: Exception) {
                    Log.w(TAG, "Previous Readers instance could not be disposed cleanly.", e)
                }
    
                readers = Readers(context, ENUM_TRANSPORT.QC_SERIAL)
    
                val availableReaders = readers?.GetAvailableRFIDReaderList()
    
                if (availableReaders.isNullOrEmpty()) {
                    notifyError(
                        "TC501 integrated RFID reader was not detected. " +
                        "Disconnect USB, confirm Airplane Mode is off, and verify the device OS supports integrated RFID."
                    )
                    return@execute
                }
    
                Log.d(TAG, "QC_SERIAL returned ${availableReaders.size} reader(s).")
    
                val integratedDevice = availableReaders.first()
    
                Log.d(
                    TAG,
                    "Found integrated reader: ${integratedDevice.name} " +
                    "[Transport: ${integratedDevice.transport}]"
                )
    
                connectToReader(integratedDevice)
    
            } catch (e: InvalidUsageException) {
                Log.e(TAG, "InvalidUsageException during initialization.", e)
                notifyError("TC501 RFID SDK initialization failed: ${e.info ?: e.message}")
    
            } catch (e: OperationFailureException) {
                Log.e(TAG, "OperationFailureException during initialization.", e)
                notifyError(
                    "TC501 RFID initialization failed: ${e.vendorMessage ?: e.message}"
                )
    
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected TC501 RFID initialization error.", e)
                notifyError("TC501 integrated RFID error: ${e.message}")
            }
        }
    }

    /**
     * Connects to the TC501 integrated RFID reader and configures events.
     * This is called from the RFID executor thread.
     */
    private fun connectToReader(device: ReaderDevice) {
        try {
            val reader = device.rfidReader
    
            rfidReader = reader
    
            if (!reader.isConnected) {
                Log.d(TAG, "Connecting to integrated RFID reader: ${device.name}...")
                reader.connect()
            }
    
            if (!reader.isConnected) {
                throw IllegalStateException(
                    "The RFID SDK returned from connect(), but the reader is not connected."
                )
            }
    
            configureReaderEvents()
    
            isConnected = true
            isInventoryRunning = false
    
            mainHandler.post {
                listener?.onReaderConnected(device.name)
            }
    
            Log.i(TAG, "Connected to TC501 integrated RFID reader: ${device.name}")
    
        } catch (e: InvalidUsageException) {
            isConnected = false
            Log.e(TAG, "Invalid RFID API usage while connecting.", e)
            notifyError("TC501 RFID connection error: ${e.info ?: e.message}")
    
        } catch (e: OperationFailureException) {
            isConnected = false
            Log.e(TAG, "TC501 integrated RFID connection failed.", e)
            notifyError(
                "TC501 RFID connection failed: ${e.vendorMessage ?: e.message}"
            )
    
        } catch (e: Exception) {
            isConnected = false
            Log.e(TAG, "Unexpected TC501 RFID connection error.", e)
            notifyError("Error connecting to integrated RFID: ${e.message}")
        }
    }

    /**
     * Registers tag, trigger, inventory, and disconnection events.
     */
    private fun configureReaderEvents() {
        val reader = rfidReader ?: return
    
        try {
            reader.Events.addEventsListener(this)
    
            reader.Events.setHandheldEvent(true)
            reader.Events.setTagReadEvent(true)
    
            // eventReadNotify() retrieves tags through getReadTags().
            reader.Events.setAttachTagDataWithReadEvent(false)
    
            reader.Events.setReaderDisconnectEvent(true)
            reader.Events.setInventoryStartEvent(true)
            reader.Events.setInventoryStopEvent(true)
    
            val triggerInfo = TriggerInfo()
    
            triggerInfo.StartTrigger.setTriggerType(
                START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            )
    
            triggerInfo.StopTrigger.setTriggerType(
                STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
            )
    
            reader.Config.setStartTrigger(triggerInfo.StartTrigger)
            reader.Config.setStopTrigger(triggerInfo.StopTrigger)
    
            val reportConfig = TagStorageSettings()
            reportConfig.tagFields = arrayOf(
                TAG_FIELD.PEAK_RSSI,
                TAG_FIELD.TAG_SEEN_COUNT,
                TAG_FIELD.ANTENNA_ID
            )
    
            reader.Config.tagStorageSettings = reportConfig
    
            Log.d(TAG, "Configured TC501 integrated RFID events.")
    
        } catch (e: InvalidUsageException) {
            Log.e(TAG, "Invalid RFID event configuration.", e)
            throw e
    
        } catch (e: OperationFailureException) {
            Log.e(TAG, "RFID event configuration failed.", e)
            throw e
        }
    }

    /**
     * Adjusts the antenna transmit power (e.g., 100 = 10.0 dBm, 300 = 30.0 dBm).
     
    fun configureAntennaPower(powerDbmTenths: Int) {
        executor.execute {
            val reader = rfidReader ?: return@execute
            try {
                val antennaConfig = reader.Config.Antennas.getAntennaRfConfig(1)
                // Set transmit power index (clamped between reader min and max)
                antennaConfig.transmitPowerIndex = powerDbmTenths.coerceIn(50, 300)
                reader.Config.Antennas.setAntennaRfConfig(1, antennaConfig)
                Log.d(TAG, "Set Antenna 1 power to ${powerDbmTenths / 10.0} dBm")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set antenna power: ${e.message}")
            }
        }
    }
*/
    /**
     * Starts continuous RFID inventory scanning.
     */
    fun startInventory() {
        executor.execute {
            val reader = rfidReader ?: return@execute
            if (!isConnected || isInventoryRunning) return@execute
            try {
                reader.Actions.Inventory.perform()
                isInventoryRunning = true
                Log.d(TAG, "Inventory started.")
            } catch (e: InvalidUsageException) {
                notifyError("Cannot start inventory: ${e.info}")
            } catch (e: OperationFailureException) {
                notifyError("Inventory failed: ${e.vendorMessage ?: e.message}")
            }
        }
    }

    /**
     * Stops RFID inventory scanning.
     */
    fun stopInventory() {
        executor.execute {
            val reader = rfidReader ?: return@execute
            if (!isInventoryRunning) return@execute
            try {
                reader.Actions.Inventory.stop()
                isInventoryRunning = false
                Log.d(TAG, "Inventory stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop inventory: ${e.message}")
            }
        }
    }

    /**
     * Callback when tags are read by Zebra RFID reader.
     */
    override fun eventReadNotify(e: RfidReadEvents?) {
        val reader = rfidReader ?: return
        val myTags = reader.Actions.getReadTags(100) ?: return

        for (tag in myTags) {
            val epc = tag.tagID ?: continue
            val rssi = tag.peakRSSI
            val count = tag.tagSeenCount

            synchronized(tagCache) {
                val record = tagCache[epc]
                if (record != null) {
                    record.count += count
                    record.rssi = rssi
                } else {
                    tagCache[epc] = TagRecord(epc, rssi, count)
                }
            }

            mainHandler.post {
                val current = tagCache[epc]
                listener?.onTagRead(epc, rssi, current?.count ?: count, null)
            }
        }
    }

    /**
     * Callback for status events (Handheld trigger, Disconnection, Battery).
     */
    override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents?) {
        val eventData = rfidStatusEvents?.StatusEventData ?: return

        when (eventData.statusEventType) {
            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                val triggerType = eventData.HandheldTriggerEventData.handheldEvent
                val isPressed = (triggerType == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED)
                Log.d(TAG, "Zebra TC501 Hardware Trigger: ${if (isPressed) "PRESSED" else "RELEASED"}")

                if (isPressed) {
                    startInventory()
                } else {
                    stopInventory()
                }

                mainHandler.post {
                    listener?.onTriggerChanged(isPressed)
                }
            }
            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                Log.w(TAG, "Reader disconnected.")
                isConnected = false
                isInventoryRunning = false
                mainHandler.post {
                    listener?.onReaderDisconnected()
                }
            }
            STATUS_EVENT_TYPE.INVENTORY_START_EVENT -> {
                isInventoryRunning = true
            }
            STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT -> {
                isInventoryRunning = false
            }
            else -> {
                // Other events: BATTERY_EVENT, POWER_EVENT
            }
        }
    }

    /**
     * Disconnects the reader and cleans up SDK resources.
     */
    fun disconnect() {
        executor.execute {
            try {
                if (isInventoryRunning) {
                    rfidReader?.Actions?.Inventory?.stop()
                }
                rfidReader?.Events?.removeEventsListener(this)
                rfidReader?.disconnect()
                readers?.Dispose()
                readers = null
                rfidReader = null
                isConnected = false
                isInventoryRunning = false
                Log.d(TAG, "Disconnected and disposed Zebra RFID SDK.")
                mainHandler.post { listener?.onReaderDisconnected() }
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            }
        }
    }

    private fun notifyError(msg: String) {
        mainHandler.post {
            listener?.onError(msg)
        }
    }
}
