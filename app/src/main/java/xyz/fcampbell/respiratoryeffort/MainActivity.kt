package xyz.fcampbell.respiratoryeffort

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
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
        private const val SERVER_URL = "http://192.168.0.15:5000"
        private const val BATCH_SIZE = 16
    }

    private val empaDataDelegate = object : EmpaDataDelegate {
        var calibrated = false

        override fun didReceiveTemperature(t: Float, timestamp: Double) = Unit

        override fun didReceiveGSR(gsr: Float, timestamp: Double) = Unit

        override fun didReceiveBatteryLevel(level: Float, timestamp: Double) = Unit

        override fun didReceiveAcceleration(x: Int, y: Int, z: Int, timestamp: Double) = Unit

        override fun didReceiveIBI(ibi: Float, timestamp: Double) = Unit

        override fun didReceiveBVP(bvp: Float, timestamp: Double) {
            if (calibrated) {
                batchAndSend(bvp)
            } else {
                if (bvp != 0f){
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

            if (status == EmpaStatus.READY) {
                empaManager.startScanning()
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
    private var wsClient = makeWsClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "onCreate")

        empaManager = EmpaDeviceManager(this, empaDataDelegate, empaStatusDelegate)
        scan.setOnClickListener { authAndScan() }
        disconnect.setOnClickListener {
            empaManager.disconnect()
        }
        serverConnect.setOnClickListener {
            wsClient.close(1000, "Reconnecting")
            wsClient = makeWsClient()
        }
        serverRestart.setOnClickListener {
            restartServer()
        }
        serverDisconnect.setOnClickListener {
            wsClient.close(1000, "Disconnecting")
        }
    }

    private fun restartServer() {
        val command = JSONObject(mapOf("command" to "restart"))
        wsClient.send(command.toString())
    }

    private fun makeWsClient(): WebSocket {
        return httpClient.newWebSocket(
                Request.Builder()
                        .url(SERVER_URL)
                        .build(),
                object : WebSocketListener() {})
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
            wsClient.send(jsonData.toString())
        }
    }
}
