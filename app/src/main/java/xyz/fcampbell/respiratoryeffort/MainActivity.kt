package xyz.fcampbell.respiratoryeffort

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import com.empatica.empalink.EmpaDeviceManager
import com.empatica.empalink.config.EmpaSensorStatus
import com.empatica.empalink.config.EmpaSensorType
import com.empatica.empalink.config.EmpaStatus
import com.empatica.empalink.delegate.EmpaDataDelegate
import com.empatica.empalink.delegate.EmpaStatusDelegate
import kotlinx.android.synthetic.main.activity_main.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val BATCH_SIZE = 16
    }

    private val empaDataDelegate = object : EmpaDataDelegate {
        var calibrated = false

        override fun didReceiveTemperature(t: Float, timestamp: Double) = Unit

        override fun didReceiveGSR(gsr: Float, timestamp: Double) = Unit

        override fun didReceiveBatteryLevel(level: Float, timestamp: Double){
            runOnUiThread {
                battery.text = (level * 100).toInt().toString()
            }
        }

        override fun didReceiveAcceleration(x: Int, y: Int, z: Int, timestamp: Double) = Unit

        override fun didReceiveIBI(ibi: Float, timestamp: Double) = Unit

        override fun didReceiveBVP(bvp: Float, timestamp: Double) {
            if (calibrated) {
                batchAndSend(bvp)
            } else {
                if (bvp != 0f) {
                    calibrated = true
                    batchAndSend(bvp)
                }
            }
        }
    }
    private val empaStatusDelegate = object : EmpaStatusDelegate {
        override fun didUpdateSensorStatus(p0: EmpaSensorStatus?, p1: EmpaSensorType?) = Unit

        override fun didRequestEnableBluetooth() = Unit

        override fun didUpdateStatus(status: EmpaStatus) {
            Log.d(TAG, "didUpdateStatus($status)")

            when (status) {
                EmpaStatus.READY -> {
                    uiThreatToast("Ready to scan for wristband")
                    empaManager.startScanning()
                }
                EmpaStatus.DISCOVERING -> uiThreatToast("Scanning for wristband")
                EmpaStatus.CONNECTING -> uiThreatToast("Connecting to wristband")
                EmpaStatus.CONNECTED -> uiThreatToast("Connected to wristband")
                EmpaStatus.DISCONNECTING -> uiThreatToast("Disconnecting from wristband")
                EmpaStatus.DISCONNECTED -> uiThreatToast("Disconnected from wristband")
            }
        }

        override fun didDiscoverDevice(device: BluetoothDevice, deviceLabel: String, rssi: Int, allowed: Boolean) {
            Log.d(TAG, "didDiscoverDevice($device, $deviceLabel, $rssi, $allowed)")

            if (allowed) {
                empaDataDelegate.calibrated = false
                restartServer()
                empaManager.connectDevice(device)
            }
        }
    }
    private lateinit var empaManager: EmpaDeviceManager
    private val httpClient = OkHttpClient()
    private var wsClient: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "onCreate")

        empaManager = EmpaDeviceManager(this, empaDataDelegate, empaStatusDelegate)
        serverConnect.setOnClickListener {
            @Suppress("UNNECESSARY_SAFE_CALL")
            wsClient?.close(1000, "Reconnecting")
            wsClient = makeWsClient()
            getAnalysisModes()
        }
        serverRestart.setOnClickListener {
            restartServer()
        }
        serverDisconnect.setOnClickListener {
            wsClient?.close(1000, "Disconnecting")
        }
        scan.setOnClickListener { authAndScan() }
        disconnect.setOnClickListener {
            empaManager.disconnect()
        }
        setMode.setOnClickListener {
            val mode = spinner.selectedItem as? String
            if (mode != null) {
                sendCommand("change_mode", mapOf("mode" to mode))
            }
        }
    }

    private fun uiThreatToast(text: String) {
        runOnUiThread {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getAnalysisModes() {
        sendCommand("list_modes")
    }

    private fun restartServer() {
        sendCommand("restart")
    }

    private fun sendCommand(command: String, args: Map<String, String> = mapOf()) {
        wsClient?.send(JSONObject(mapOf("command" to command, "args" to args)).toString())
    }

    private fun getServerAddress(): String {
        val address = address.text.toString()
        val port = port.text.toString()
        return "http://$address:$port"
    }

    private fun makeWsClient(): WebSocket {
        return httpClient.newWebSocket(
                Request.Builder()
                        .url(getServerAddress())
                        .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        processReceivedData(text)
                    }
                })
    }

    private fun processReceivedData(text: String) {
        val data = JSONObject(text)
        if (data.has("modes")) {
            val modes = data.getJSONArray("modes")
            val modesList = Array(modes.length()) { i -> modes[i] as String }
            runOnUiThread {
                val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modesList)
                arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = arrayAdapter
            }
        }
    }

    private fun authAndScan() {
        empaManager.authenticateWithAPIKey(getString(R.string.EMPALINK_API_KEY))
    }

    private val bvpData = FloatArray(BATCH_SIZE)
    private var bvpDataHead = 0
    private fun batchAndSend(data: Float) {
        bvpData[bvpDataHead++] = data

        if (bvpDataHead >= BATCH_SIZE) {
            bvpDataHead = 0
            val jsonData = JSONArray(bvpData)
            wsClient?.send(jsonData.toString())
        }
    }
}
