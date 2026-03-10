package live.textpilot.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSIONS_REQUEST = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val scanBtn = findViewById<Button>(R.id.btnScan)
        val statusText = findViewById<TextView>(R.id.tvStatus)

        // Check for deep link: textpilot://campaign?url=<campaign_url>
        intent?.data?.let { uri ->
            if (uri.scheme == "textpilot" && uri.host == "campaign") {
                val campaignUrl = uri.getQueryParameter("url")
                if (!campaignUrl.isNullOrBlank()) {
                    if (hasPermissions()) {
                        launchCampaign(campaignUrl)
                    } else {
                        // Store URL and request permissions first
                        pendingCampaignUrl = campaignUrl
                        requestPermissions()
                    }
                    return
                }
            }
        }

        // Normal launch — show scan button
        scanBtn.setOnClickListener {
            if (hasPermissions()) {
                startScannerActivity()
            } else {
                requestPermissions()
            }
        }

        // Update status
        val sentCount = getPrefs().getInt("total_sent", 0)
        if (sentCount > 0) {
            statusText.text = "Total messages sent: $sentCount"
        }
    }

    private var pendingCampaignUrl: String? = null

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Check if we had a pending deep link
                pendingCampaignUrl?.let {
                    launchCampaign(it)
                    pendingCampaignUrl = null
                    return
                }
                startScannerActivity()
            } else {
                // Check specifically which permission was denied
                val smsDenied = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
                val cameraDenied = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED

                val msg = when {
                    smsDenied && cameraDenied -> "SMS and Camera permissions are required"
                    smsDenied -> "SMS permission is required to send campaign messages"
                    cameraDenied -> "Camera permission is required to scan QR codes"
                    else -> "Required permissions were denied"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startScannerActivity() {
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    private fun launchCampaign(url: String) {
        val intent = Intent(this, CampaignActivity::class.java)
        intent.putExtra("campaign_url", url)
        startActivity(intent)
    }

    private fun getPrefs() = getSharedPreferences("textpilot", MODE_PRIVATE)
}
