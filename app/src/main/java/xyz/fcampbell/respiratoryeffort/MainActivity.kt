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
import com.squareup.okhttp.*
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONArray
import java.io.IOException

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val SERVER_URL = "http://192.168.0.23:5000"
        private const val WIN_SIZE = 16
    }

    private val empaDataDelegate = object : EmpaDataDelegate {
        override fun didReceiveTemperature(t: Float, timestamp: Double) = Unit

        override fun didReceiveGSR(gsr: Float, timestamp: Double) = Unit

        override fun didReceiveBatteryLevel(level: Float, timestamp: Double) = Unit

        override fun didReceiveAcceleration(x: Int, y: Int, z: Int, timestamp: Double) = Unit

        override fun didReceiveIBI(ibi: Float, timestamp: Double) = Unit

        override fun didReceiveBVP(bvp: Float, timestamp: Double) {
            batchAndSend(bvp)
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
                empaManager.connectDevice(device)
            }
        }
    }
    private lateinit var empaManager: EmpaDeviceManager
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "onCreate")

        empaManager = EmpaDeviceManager(this, empaDataDelegate, empaStatusDelegate)
        scan.setOnClickListener {
            empaManager.authenticateWithAPIKey(getString(R.string.EMPALINK_API_KEY))
        }
        disconnect.setOnClickListener {
            empaManager.disconnect()
        }
    }

    private val bvpData = FloatArray(WIN_SIZE)
    private var bvpDataHead = 0
    private fun batchAndSend(data: Float) {
        bvpData[bvpDataHead++] = data

        if (bvpDataHead >= WIN_SIZE) {
            bvpDataHead = 0
            val jsonData = JSONArray(bvpData)
            val request = Request.Builder()
                    .url(SERVER_URL)
                    .post(RequestBody.create(MediaType.parse("application/json"), jsonData.toString()))
                    .build()
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(request: Request, e: IOException) {
                    Log.d(TAG, "onFailure($request, $e)")
                }

                override fun onResponse(response: Response?) {
                    Log.d(TAG, "onResponse($response)")
                }
            })
        }
    }
}
