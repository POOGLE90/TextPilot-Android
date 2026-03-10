package live.textpilot.companion

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var scanned = false  // Prevent double-scans

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, QRAnalyzer { qrValue -> onQRScanned(qrValue) }) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("TextPilot", "Camera bind failed", e)
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onQRScanned(rawValue: String) {
        if (scanned) return
        scanned = true

        Log.d("TextPilot", "QR scanned: ${rawValue.take(100)}")

        // Extract campaign URL from QR code
        // QR contains: shortcuts://run-shortcut?name=TextPilot&input=text&text=<encoded_url>
        // We need to extract the campaign URL from the 'text' parameter
        val campaignUrl = extractCampaignUrl(rawValue)

        if (campaignUrl == null) {
            runOnUiThread {
                Toast.makeText(this, "Not a TextPilot QR code", Toast.LENGTH_LONG).show()
                scanned = false  // Allow retry
            }
            return
        }

        runOnUiThread {
            Toast.makeText(this, "Campaign found! Loading...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, CampaignActivity::class.java)
            intent.putExtra("campaign_url", campaignUrl)
            startActivity(intent)
            finish()
        }
    }

    /**
     * Extract the campaign URL from various QR code formats:
     * 1. shortcuts://run-shortcut?name=TextPilot&input=text&text=<url>  (iOS QR format)
     * 2. textpilot://campaign?url=<url>  (Android deep link)
     * 3. https://textpilot.osazeokunboca.workers.dev/campaign/<id>  (Direct URL)
     * 4. Raw campaign URL
     */
    private fun extractCampaignUrl(raw: String): String? {
        val trimmed = raw.trim()

        // Format 1: iOS Shortcut URL — extract 'text' parameter
        if (trimmed.startsWith("shortcuts://")) {
            return try {
                val uri = android.net.Uri.parse(trimmed)
                val textParam = uri.getQueryParameter("text")
                if (textParam != null) URLDecoder.decode(textParam, "UTF-8") else null
            } catch (e: Exception) {
                Log.e("TextPilot", "Failed to parse shortcuts URL", e)
                null
            }
        }

        // Format 2: Android deep link
        if (trimmed.startsWith("textpilot://")) {
            return try {
                val uri = android.net.Uri.parse(trimmed)
                uri.getQueryParameter("url")
            } catch (e: Exception) { null }
        }

        // Format 3: Direct campaign URL
        if (trimmed.contains("/campaign/") && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            return trimmed
        }

        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    /**
     * ML Kit barcode analyzer — processes camera frames for QR codes
     */
    private inner class QRAnalyzer(private val onScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val scanner = BarcodeScanning.getClient()

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.format == Barcode.FORMAT_QR_CODE && barcode.rawValue != null) {
                            onScanned(barcode.rawValue!!)
                            break
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        }
    }
}
