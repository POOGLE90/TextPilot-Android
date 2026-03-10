package live.textpilot.companion

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class CampaignActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvCurrentContact: TextView
    private lateinit var tvLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnAction: Button

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private var campaignMessages = mutableListOf<CampaignMessage>()
    private var sendInterval = 5 // seconds between messages
    private var currentIndex = 0
    private var isPaused = false
    private var isCancelled = false
    private var successCount = 0
    private var failCount = 0

    data class CampaignMessage(
        val id: Int,
        val phone: String,
        val text: String,
        var status: String = "pending"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_campaign)

        tvTitle = findViewById(R.id.tvCampaignTitle)
        tvProgress = findViewById(R.id.tvProgress)
        tvCurrentContact = findViewById(R.id.tvCurrentContact)
        tvLog = findViewById(R.id.tvLog)
        progressBar = findViewById(R.id.progressBar)
        btnAction = findViewById(R.id.btnAction)

        val campaignUrl = intent.getStringExtra("campaign_url")
        if (campaignUrl.isNullOrBlank()) {
            Toast.makeText(this, "No campaign URL provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tvTitle.text = "Loading campaign..."
        tvProgress.text = "Fetching data..."
        btnAction.isEnabled = false

        fetchCampaign(campaignUrl)
    }

    private fun fetchCampaign(url: String) {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post {
                    tvTitle.text = "Failed to load campaign"
                    tvProgress.text = e.message ?: "Network error"
                    appendLog("ERROR: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    handler.post {
                        tvTitle.text = "Failed to load campaign"
                        tvProgress.text = "Server returned ${response.code}"
                        appendLog("ERROR: HTTP ${response.code}")
                    }
                    return
                }

                try {
                    parseCampaignJson(body)
                } catch (e: Exception) {
                    handler.post {
                        tvTitle.text = "Invalid campaign data"
                        tvProgress.text = e.message ?: "Parse error"
                        appendLog("ERROR: ${e.message}")
                    }
                }
            }
        })
    }

    private fun parseCampaignJson(json: String) {
        val root = JSONObject(json)
        sendInterval = root.optInt("sendInterval", 5)
        val messagesObj = root.getJSONObject("messages")

        campaignMessages.clear()
        val keys = messagesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val msg = messagesObj.getJSONObject(key)
            val phoneArr = msg.getJSONArray("phoneNumbers")
            if (phoneArr.length() > 0) {
                campaignMessages.add(
                    CampaignMessage(
                        id = msg.optInt("id", key.toIntOrNull() ?: 0),
                        phone = phoneArr.getString(0),
                        text = msg.getString("text")
                    )
                )
            }
        }

        // Sort by ID to maintain order
        campaignMessages.sortBy { it.id }

        handler.post {
            if (campaignMessages.isEmpty()) {
                tvTitle.text = "Empty campaign"
                tvProgress.text = "No messages to send"
                return@post
            }

            tvTitle.text = "Campaign Ready"
            tvProgress.text = "${campaignMessages.size} messages · ${sendInterval}s interval"
            progressBar.max = campaignMessages.size
            progressBar.progress = 0
            btnAction.isEnabled = true
            btnAction.text = "Start Sending"

            // Show preview of first 3 messages
            val preview = campaignMessages.take(3).joinToString("\n") { msg ->
                "  → ${formatPhone(msg.phone)}: ${msg.text.take(50)}${if (msg.text.length > 50) "..." else ""}"
            }
            appendLog("Campaign loaded:\n$preview")
            if (campaignMessages.size > 3) {
                appendLog("  ...and ${campaignMessages.size - 3} more")
            }

            // Show confirmation dialog
            showConfirmDialog()

            btnAction.setOnClickListener {
                when {
                    isPaused -> resumeSending()
                    currentIndex == 0 -> showConfirmDialog()
                    currentIndex >= campaignMessages.size -> {
                        finish() // Done — go back
                    }
                }
            }
        }
    }

    private fun showConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Send ${campaignMessages.size} messages?")
            .setMessage(
                "This will send ${campaignMessages.size} SMS messages from your phone number.\n\n" +
                "Interval: ${sendInterval} seconds between messages\n\n" +
                "Standard messaging rates may apply."
            )
            .setPositiveButton("Send All") { _, _ -> startSending() }
            .setNegativeButton("Cancel") { _, _ -> }
            .setCancelable(true)
            .show()
    }

    private fun startSending() {
        currentIndex = 0
        successCount = 0
        failCount = 0
        isPaused = false
        isCancelled = false
        btnAction.text = "Pause"
        btnAction.setOnClickListener { pauseSending() }
        appendLog("\n--- Campaign started ---")
        sendNext()
    }

    private fun pauseSending() {
        isPaused = true
        btnAction.text = "Resume"
        btnAction.setOnClickListener { resumeSending() }
        appendLog("⏸ Paused at message ${currentIndex + 1}")
    }

    private fun resumeSending() {
        isPaused = false
        btnAction.text = "Pause"
        btnAction.setOnClickListener { pauseSending() }
        appendLog("▶ Resumed")
        sendNext()
    }

    private fun sendNext() {
        if (isCancelled || isPaused) return
        if (currentIndex >= campaignMessages.size) {
            onCampaignComplete()
            return
        }

        val msg = campaignMessages[currentIndex]
        tvCurrentContact.text = "Sending to ${formatPhone(msg.phone)}..."
        tvProgress.text = "${currentIndex + 1} / ${campaignMessages.size}  ·  ✓ $successCount  ✗ $failCount"

        try {
            sendSms(msg.phone, msg.text)
            msg.status = "sent"
            successCount++
            appendLog("✓ ${formatPhone(msg.phone)}")
        } catch (e: Exception) {
            msg.status = "failed"
            failCount++
            appendLog("✗ ${formatPhone(msg.phone)}: ${e.message}")
            Log.e("TextPilot", "SMS send failed for ${msg.phone}", e)
        }

        currentIndex++
        progressBar.progress = currentIndex

        tvProgress.text = "${currentIndex} / ${campaignMessages.size}  ·  ✓ $successCount  ✗ $failCount"

        if (currentIndex < campaignMessages.size) {
            // Schedule next message after interval
            handler.postDelayed({ sendNext() }, sendInterval * 1000L)
        } else {
            onCampaignComplete()
        }
    }

    private fun sendSms(phone: String, message: String) {
        val smsManager = getSystemService(SmsManager::class.java)
            ?: throw IllegalStateException("SMS service unavailable")

        // Handle messages longer than 160 chars
        val parts = smsManager.divideMessage(message)
        if (parts.size == 1) {
            smsManager.sendTextMessage(phone, null, message, null, null)
        } else {
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
        }
    }

    private fun onCampaignComplete() {
        tvTitle.text = "Campaign Complete"
        tvCurrentContact.text = ""
        tvProgress.text = "Done: ✓ $successCount sent  ·  ✗ $failCount failed"
        btnAction.text = "Done"
        btnAction.setOnClickListener { finish() }
        appendLog("\n--- Campaign complete ---")
        appendLog("Sent: $successCount  |  Failed: $failCount")

        // Save stats
        val prefs = getSharedPreferences("textpilot", MODE_PRIVATE)
        val totalSent = prefs.getInt("total_sent", 0)
        prefs.edit().putInt("total_sent", totalSent + successCount).apply()
    }

    private fun formatPhone(phone: String): String {
        // Format 10-digit US numbers nicely
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            digits.length == 11 && digits.startsWith("1") ->
                "(${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"
            digits.length == 10 ->
                "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            else -> phone
        }
    }

    private fun appendLog(text: String) {
        handler.post {
            val current = tvLog.text.toString()
            val lines = current.split("\n").toMutableList()
            lines.add(text)
            // Keep last 50 lines
            if (lines.size > 50) {
                tvLog.text = lines.takeLast(50).joinToString("\n")
            } else {
                tvLog.text = lines.joinToString("\n")
            }
            // Scroll to bottom
            val scrollView = tvLog.parent as? android.widget.ScrollView
            scrollView?.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onBackPressed() {
        if (currentIndex in 1 until campaignMessages.size && !isPaused) {
            // Sending in progress — confirm before leaving
            AlertDialog.Builder(this)
                .setTitle("Cancel campaign?")
                .setMessage("${campaignMessages.size - currentIndex} messages haven't been sent yet.")
                .setPositiveButton("Stop & Exit") { _, _ ->
                    isCancelled = true
                    handler.removeCallbacksAndMessages(null)
                    super.onBackPressed()
                }
                .setNegativeButton("Keep Sending") { _, _ -> }
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        isCancelled = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
