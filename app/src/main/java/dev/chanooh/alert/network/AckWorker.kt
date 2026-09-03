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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val eventId = inputData.getString(KEY_EVENT_ID).orEmpty()
        if (eventId.isBlank()) return@withContext Result.failure()

        val settings = SettingsRepository(applicationContext).settings.first()
        val token = SecretStore(applicationContext).getDeviceApiToken()
        if (settings.serverBaseUrl.isBlank() || settings.deviceId.isBlank() || token.isBlank()) {
            return@withContext Result.retry()
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
                code in 200..299 -> Result.success()
                code == 404 -> Result.failure()
                code in 400..499 -> Result.failure()
                else -> Result.retry()
            }
        }.getOrElse { Result.retry() }
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
    }
}
