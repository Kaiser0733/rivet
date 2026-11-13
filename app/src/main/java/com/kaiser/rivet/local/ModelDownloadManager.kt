package com.kaiser.rivet.local

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
enum class DownloadStatus {
    @SerialName("queued") Queued,
    @SerialName("downloading") Downloading,
    @SerialName("paused") Paused,
    @SerialName("verifying") Verifying,
    @SerialName("failed") Failed,
}

@Serializable
data class DownloadJob(
    val entry: LocalModelEntry,
    val status: DownloadStatus,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val wifiOnly: Boolean = true,
    val allowMobile: Boolean = false,
    val byo: Boolean = false,
    val etag: String? = null,
    val checksumVerified: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

@Serializable
private data class DownloadsDocument(val jobs: List<DownloadJob> = emptyList())

/** Pure policy so storage and mobile-data rules are testable without Android. */
enum class NetworkKind { Wifi, Cellular, Other }

enum class Http416Action { Finish, RestartFromScratch, Fail }

object DownloadPolicy {
    const val KEEP_FREE_BYTES = 1_000_000_000L
    const val WARN_BELOW_BYTES = 2_500_000_000L
    const val WIFI_DEFAULT_THRESHOLD = 500_000_000L

    fun refusal(availableBytes: Long, remainingBytes: Long): String? {
        if (availableBytes < remainingBytes + KEEP_FREE_BYTES) {
            return "Not enough free storage. Rivet keeps at least 1 GB free for Android."
        }
        return null
    }

    fun warning(availableBytes: Long, remainingBytes: Long): String? {
        val left = availableBytes - remainingBytes
        if (left in 0 until WARN_BELOW_BYTES) {
            return "Storage is tight. Keep at least 2.5 GB free while a model downloads."
        }
        return null
    }

    fun wifiOnlyByDefault(sizeBytes: Long): Boolean = sizeBytes > WIFI_DEFAULT_THRESHOLD

    fun effectiveWifiOnly(globalWifiOnly: Boolean, allowMobile: Boolean, sizeBytes: Long): Boolean =
        !allowMobile && globalWifiOnly && wifiOnlyByDefault(sizeBytes)

    fun http416Action(partBytes: Long, expectedBytes: Long, rangedAttempt: Boolean): Http416Action = when {
        expectedBytes > 0 && partBytes == expectedBytes -> Http416Action.Finish
        rangedAttempt -> Http416Action.RestartFromScratch
        else -> Http416Action.Fail
    }

    /** A metered Wi-Fi network is never called mobile data. */
    fun meteredWarning(kind: NetworkKind, sizeBytes: Long): String {
        val size = String.format(Locale.US, "%.1f GB", sizeBytes / 1_000_000_000.0)
        return when (kind) {
            NetworkKind.Cellular ->
                "This model is $size. On mobile data that can use a lot of your plan."
            NetworkKind.Wifi ->
                "This Wi-Fi is set as metered. The model is $size; downloading it can use a lot of your plan."
            NetworkKind.Other ->
                "This network is metered. The model is $size; downloading it can use a lot of your plan."
        }
    }

    fun networkWait(kind: NetworkKind): String = when (kind) {
        NetworkKind.Cellular -> "This download waits for Wi-Fi. Use mobile data for this one? It can use a lot."
        NetworkKind.Wifi -> "This Wi-Fi is set as metered. Download anyway?"
        NetworkKind.Other -> "This network is metered. Download anyway?"
    }

    fun checksumFromEtag(etag: String?): String? {
        val value = etag?.trim()?.trim('"')?.lowercase(Locale.ROOT) ?: return null
        return value.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
    }
}

class ModelDownloadManager(
    private val app: Application,
    private val models: LocalModelManager,
) {
    private val root = File(app.filesDir, "local-models").apply { mkdirs() }
    private val journal = File(root, "downloads.json")
    private val prefs = app.getSharedPreferences("local-models", Application.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<Map<String, DownloadJob>> = _jobs.asStateFlow()
    private var runner: Job? = null
    private var runSequence = 0L
    @Volatile private var activeToken = 0L
    @Volatile private var activeCall: Call? = null
    @Volatile private var activeId: String? = null

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        private set(value) { prefs.edit().putBoolean("wifi_only", value).apply() }

    fun setWifiOnlyPreference(enabled: Boolean) {
        wifiOnly = enabled
        val updated = _jobs.value.mapValues { (_, job) ->
            val targetWifiOnly = DownloadPolicy.effectiveWifiOnly(
                globalWifiOnly = enabled,
                allowMobile = job.allowMobile,
                sizeBytes = job.entry.size,
            )
            if (targetWifiOnly == job.wifiOnly) job else job.copy(wifiOnly = targetWifiOnly)
        }
        if (updated != _jobs.value) {
            _jobs.value = updated
            persist()
        }
    }

    fun job(id: String): DownloadJob? = _jobs.value[id]

    /** Starts or resumes one download. A second active download is refused plainly. */
    fun start(entry: LocalModelEntry, allowMobile: Boolean = false, byo: Boolean = false): String? {
        if (runner?.isActive == true || _jobs.value.values.any {
                it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued ||
                    it.status == DownloadStatus.Verifying
            }) {
            return "Another model is still downloading or being installed. Pause it first."
        }
        val installed = models.installedFor(entry.id)
        if (installed != null) return "${entry.name} is already installed."
        val prior = _jobs.value[entry.id]
        val part = models.partFor(entry)
        val offset = if (part.isFile) part.length() else 0L
        val effectiveAllowMobile = allowMobile || (prior?.allowMobile == true)
        val effectiveWifiOnly = DownloadPolicy.effectiveWifiOnly(
            globalWifiOnly = wifiOnly,
            allowMobile = effectiveAllowMobile,
            sizeBytes = entry.size,
        )
        val job = (prior ?: DownloadJob(
            entry = entry,
            status = DownloadStatus.Queued,
            downloaded = offset,
            total = entry.size,
            wifiOnly = effectiveWifiOnly,
            allowMobile = effectiveAllowMobile,
            byo = byo,
        )).copy(
            status = DownloadStatus.Queued,
            downloaded = offset,
            total = entry.size,
            error = null,
            wifiOnly = effectiveWifiOnly,
            allowMobile = effectiveAllowMobile,
            updatedAt = System.currentTimeMillis(),
        )
        DownloadPolicy.refusal(freeBytes(), (entry.size - offset).coerceAtLeast(1))?.let {
            set(job.copy(status = DownloadStatus.Failed, error = it))
            return it
        }
        networkProblem(job)?.let {
            set(job.copy(status = DownloadStatus.Paused, error = it))
            return it
        }
        set(job)
        activeId = entry.id
        startService()
        val token = ++runSequence
        activeToken = token
        runner = scope.launch { runJob(job, token) }
        return null
    }

    fun pause(id: String) {
        if (activeId == id || _jobs.value[id]?.status == DownloadStatus.Verifying) {
            activeCall?.cancel()
            runner?.cancel()
            runner = null
            activeToken = 0
        }
        val job = _jobs.value[id] ?: return
        val part = models.partFor(job.entry)
        set(job.copy(status = DownloadStatus.Paused, downloaded = part.length(),
            error = "Paused.", updatedAt = System.currentTimeMillis()))
        stopServiceIfIdle()
    }

    fun cancel(id: String) {
        if (activeId == id || _jobs.value[id]?.status == DownloadStatus.Verifying) {
            activeCall?.cancel()
            runner?.cancel()
            runner = null
            activeToken = 0
        }
        _jobs.value[id]?.let { models.partFor(it.entry).delete() }
        _jobs.value = _jobs.value - id
        persist()
        stopServiceIfIdle()
    }

    fun resume(id: String, allowMobile: Boolean = false): String? {
        val job = _jobs.value[id] ?: return "That download is no longer queued."
        if (job.status == DownloadStatus.Downloading) return null
        return start(job.entry, allowMobile = allowMobile, byo = job.byo)
    }

    fun storageWarning(job: DownloadJob): String? =
        DownloadPolicy.warning(freeBytes(), (job.total - job.downloaded).coerceAtLeast(1))

    fun networkProblem(job: DownloadJob): String? {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return "No network right now. Connect and resume."
        val caps = manager.getNetworkCapabilities(network)
            ?: return "No network right now. Connect and resume."
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "No internet right now. Connect and resume."
        }
        if (job.wifiOnly && !job.allowMobile &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return DownloadPolicy.networkWait(networkKind(caps))
        }
        return null
    }

    private fun networkKind(caps: NetworkCapabilities): NetworkKind = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
        else -> NetworkKind.Other
    }

    fun staleBytes(): Long {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val journaled = _jobs.value.values.filter {
            it.updatedAt < cutoff && models.partFor(it.entry).isFile
        }
        val known = journaled.map { models.partFor(it.entry) }.toSet()
        val orphaned = root.listFiles()?.filter {
            it.name.endsWith(".gguf.part") && it !in known &&
                it.lastModified() < cutoff
        }.orEmpty()
        return journaled.sumOf { models.partFor(it.entry).length() } + orphaned.sumOf { it.length() }
    }

    fun cleanupStaleParts() {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val stale = _jobs.value.values.filter {
            it.updatedAt < cutoff && models.partFor(it.entry).isFile
        }
        for (job in stale) {
            models.partFor(job.entry).delete()
            _jobs.value = _jobs.value - job.entry.id
        }
        val known = _jobs.value.values.map { models.partFor(it.entry) }.toSet()
        root.listFiles()?.filter {
            it.name.endsWith(".gguf.part") && it !in known && it.lastModified() < cutoff
        }?.forEach { it.delete() }
        persist()
    }

    fun freeBytes(): Long = try {
        StatFs(app.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        0L
    }

    private suspend fun runJob(job: DownloadJob, token: Long) {
        val id = job.entry.id
        val part = models.partFor(job.entry)
        if (activeToken != token) return
        try {
            executeDownloadAttempt(job, part, allowRange = true, token = token)
        } catch (e: CancellationException) {
            if (activeToken != token) throw e
            val current = _jobs.value[id] ?: throw e
            set(current.copy(status = DownloadStatus.Paused, downloaded = part.length(),
                error = "Paused.", updatedAt = System.currentTimeMillis()))
            throw e
        } catch (e: Exception) {
            if (activeToken != token) return
            val current = _jobs.value[id] ?: return
            val message = when (e) {
                is LocalFailure -> e.message ?: "The model could not be installed."
                is IOException -> if ((e.message ?: "").contains("space", ignoreCase = true) ||
                    (e.message ?: "").contains("ENOSPC", ignoreCase = true)) {
                    "Storage is full. Free some space, then resume."
                } else e.message?.takeIf { it.isNotBlank() }
                    ?: "The download stopped. Resume when the connection is back."
                else -> "The download stopped. Resume when the connection is back."
            }
            set(current.copy(status = DownloadStatus.Paused,
                downloaded = part.length(), error = message,
                updatedAt = System.currentTimeMillis()))
        } finally {
            if (activeToken == token) {
                if (activeId == id) activeId = null
                activeCall = null
                runner = null
            }
            stopServiceIfIdle()
        }
    }

    private fun executeDownloadAttempt(
        job: DownloadJob,
        part: File,
        allowRange: Boolean,
        token: Long,
    ) {
        val id = job.entry.id
        set((_jobs.value[id] ?: job).copy(status = DownloadStatus.Downloading, error = null,
            downloaded = part.length(), updatedAt = System.currentTimeMillis()))
        val requestBuilder = Request.Builder()
            .url(job.entry.downloadUrl)
            .header("Accept-Encoding", "identity")
            .get()
        if (allowRange && part.length() > 0) {
            requestBuilder.header("Range", "bytes=${part.length()}-")
        }
        val call = client.newCall(requestBuilder.build())
        activeCall = call
        var restartFromScratch = false
        call.execute().use { response ->
            if (response.code == 416) {
                when (DownloadPolicy.http416Action(part.length(), job.entry.size, allowRange)) {
                    Http416Action.Finish -> {
                        finish(job, part, response.header("ETag"), job.entry.sha256, token)
                        return
                    }
                    Http416Action.RestartFromScratch -> {
                        part.delete()
                        set((_jobs.value[id] ?: job).copy(downloaded = 0L,
                            updatedAt = System.currentTimeMillis()))
                        restartFromScratch = true
                        return@use
                    }
                    Http416Action.Fail ->
                        throw IOException("The download server returned HTTP 416.")
                }
            }
            if (!response.isSuccessful) {
                throw IOException("The download server returned HTTP ${response.code}.")
            }
            val rangeStart = if (response.code == 206 && allowRange) part.length() else 0L
            if (response.code != 206 && part.length() > 0) {
                // The server ignored Range and is sending the whole file. Restart
                // cleanly rather than stitching two different responses together.
                part.delete()
            }
            val body = response.body ?: throw IOException("The download response was empty.")
            val contentLength = body.contentLength()
            val linkedSize = response.header("X-Linked-Size")?.toLongOrNull()
            val total = when {
                contentLength >= 0 -> contentLength + rangeStart
                linkedSize != null && linkedSize > 0 -> linkedSize
                else -> job.entry.size
            }
            if (total > 0 && total != job.total) {
                set(_jobs.value[id]?.copy(total = total, updatedAt = System.currentTimeMillis()) ?: return)
            }
            val etag = response.header("ETag") ?: response.header("X-Linked-ETag")
            val existingEtag = _jobs.value[id]?.etag
            if (existingEtag != null && etag != null && existingEtag != etag) {
                throw IOException("The model file changed on Hugging Face. Start the download again.")
            }
            set((_jobs.value[id] ?: job).copy(etag = existingEtag ?: etag,
                total = total, updatedAt = System.currentTimeMillis()))
            FileOutputStream(part, rangeStart > 0).use { output ->
                copy(response.body!!.byteStream(), output, part, total, id, token)
            }
            if (part.length() != job.entry.size) {
                throw IOException("The download stopped early. Resume to finish it.")
            }
            finish(job.copy(etag = etag ?: existingEtag), part, etag,
                job.entry.sha256, token)
        }
        if (restartFromScratch && activeToken == token) {
            executeDownloadAttempt(job, part, allowRange = false, token = token)
        }
    }

    private fun copy(input: InputStream, output: FileOutputStream, part: File,
                     total: Long, id: String, token: Long) {
        val buffer = ByteArray(64 * 1024)
        var lastReport = System.currentTimeMillis()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            if (activeToken != token) throw CancellationException()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            output.flush()
            val now = System.currentTimeMillis()
            if (now - lastReport > 250) {
                val job = _jobs.value[id] ?: return
                if (activeToken != token) return
                set(job.copy(downloaded = part.length(), total = if (total > 0) total else job.total,
                    updatedAt = now))
                lastReport = now
            }
        }
    }

    private fun finish(job: DownloadJob, part: File, etag: String?, expected: String?,
                       token: Long) {
        val id = job.entry.id
        // Cancel/delete can happen while the response is being finalized. Do not
        // resurrect a journal row for a model the user just removed.
        if (activeToken != token || _jobs.value[id] == null) return
        set(job.copy(status = DownloadStatus.Verifying, downloaded = part.length(),
            etag = job.etag ?: etag, updatedAt = System.currentTimeMillis()))
        if (job.entry.size > 0 && part.length() != job.entry.size) {
            throw IOException("The downloaded file is the wrong size. Start the download again.")
        }
        val checksum = expected ?: DownloadPolicy.checksumFromEtag(etag) ?: job.entry.sha256
        if (checksum != null) {
            val actual = digest(part)
            if (!actual.equals(checksum, ignoreCase = true)) {
                part.delete()
                if (activeToken != token || _jobs.value[id] == null) return
                val message = if (job.byo) {
                    "The upstream file no longer matches the checksum. Add the link again to use the new file."
                } else {
                    "The downloaded model didn't match its published checksum. Start the download again."
                }
                set(job.copy(status = DownloadStatus.Failed, downloaded = 0, etag = null,
                    error = message, updatedAt = System.currentTimeMillis()))
                return
            }
        }
        val target = models.fileFor(job.entry)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        if (activeToken != token) return
        scope.launch {
            if (activeToken != token || _jobs.value[id] == null) {
                target.delete()
                return@launch
            }
            val failure = runCatching {
                models.install(job.entry, job.byo, checksum != null)
                models.activate(job.entry)
            }.exceptionOrNull()
            if (_jobs.value[id] == null) {
                runCatching { models.remove(job.entry) }
                target.delete()
            } else if (failure != null) {
                set(job.copy(status = DownloadStatus.Failed,
                    error = failure.message ?: "The model could not be registered."))
            } else {
                _jobs.value = _jobs.value - id
                persist()
            }
        }
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun set(job: DownloadJob) {
        _jobs.value = _jobs.value + (job.entry.id to job)
        persist()
    }

    private fun persist() {
        runCatching {
            val temp = File(root, "downloads.json.tmp")
            temp.writeText(json.encodeToString(DownloadsDocument.serializer(),
                DownloadsDocument(_jobs.value.values.toList())))
            if (!temp.renameTo(journal)) {
                journal.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun load(): Map<String, DownloadJob> {
        val parsed = runCatching {
            if (!journal.isFile) emptyList()
            else json.decodeFromString(DownloadsDocument.serializer(), journal.readText()).jobs
        }.getOrDefault(emptyList())
        return parsed.associateBy { it.entry.id }
            // A crash after the file was installed but before the journal row was
            // removed must not leave a download row for an already usable model.
            .filterValues { models.installedFor(it.entry.id) == null }
            .mapValues { (_, job) ->
                if (job.status == DownloadStatus.Downloading || job.status == DownloadStatus.Queued ||
                    job.status == DownloadStatus.Verifying) {
                    val part = models.partFor(job.entry)
                    job.copy(status = DownloadStatus.Paused, downloaded = part.length(),
                        error = "Rivet closed while this was downloading. Resume to finish it.",
                        updatedAt = System.currentTimeMillis())
                } else job
            }
    }

    private fun startService() {
        runCatching {
            app.startForegroundService(android.content.Intent(app, ModelDownloadService::class.java)
                .setAction(ModelDownloadService.ACTION_START))
        }
    }

    private fun stopServiceIfIdle() {
        if (_jobs.value.values.none { it.status == DownloadStatus.Downloading ||
                it.status == DownloadStatus.Verifying || it.status == DownloadStatus.Queued }) {
            runCatching { app.startService(android.content.Intent(app, ModelDownloadService::class.java)
                .setAction(ModelDownloadService.ACTION_STOP)) }
        }
    }
}
