package dev.chanooh.alert.network

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.SettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val eventId = inputData.getString(KEY_EVENT_ID).orEmpty()
        if (eventId.isBlank()) return@withContext Result.failure()
        when (deliverNow(applicationContext, eventId)) {
            DeliveryResult.DELIVERED -> Result.success()
            DeliveryResult.PERMANENT_FAILURE -> Result.failure()
            DeliveryResult.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val KEY_EVENT_ID = "event_id"

        fun enqueue(context: Context, eventId: String) {
            if (eventId.isBlank()) return
            val request = OneTimeWorkRequestBuilder<AckWorker>()
                .setInputData(workDataOf(KEY_EVENT_ID to eventId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "alert-ack-$eventId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Root ingress has a short foreground execution window. Try its ACK
         * immediately rather than waiting for WorkManager's next scheduling pass.
         */
        fun acknowledge(context: Context, eventId: String) {
            if (eventId.isBlank()) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                if (deliverNow(context.applicationContext, eventId) != DeliveryResult.DELIVERED) {
                    enqueue(context.applicationContext, eventId)
                }
            }
        }

        suspend fun deliverNow(context: Context, eventId: String): DeliveryResult = withContext(Dispatchers.IO) {
            val settings = SettingsRepository(context).settings.first()
            val token = SecretStore(context).getDeviceApiToken()
            if (settings.serverBaseUrl.isBlank() || settings.deviceId.isBlank() || token.isBlank()) {
                return@withContext DeliveryResult.RETRY
            }

            runCatching {
                val endpoint = settings.serverBaseUrl.trimEnd('/') + "/api/alerts/$eventId/ack"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { output ->
                    output.write(JSONObject().put("deviceId", settings.deviceId).toString().toByteArray())
                }
                val code = connection.responseCode
                connection.disconnect()
                when {
                    code in 200..299 -> DeliveryResult.DELIVERED
                    code == 404 || code in 400..499 -> DeliveryResult.PERMANENT_FAILURE
                    else -> DeliveryResult.RETRY
                }
            }.getOrElse { DeliveryResult.RETRY }
        }
    }
}

enum class DeliveryResult { DELIVERED, PERMANENT_FAILURE, RETRY }
