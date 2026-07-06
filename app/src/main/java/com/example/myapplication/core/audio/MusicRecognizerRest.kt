package com.example.myapplication.core.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MusicRecognizerRest(
    private val context: Context,
    private val onResult: (MusicResult?) -> Unit,
    private val onError: (String) -> Unit,
    private val onVolumeChanged: (Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "MUSIC_REST"

        // Credenciales cargadas desde local.properties (ver local.properties.example)
        private val ACCESS_KEY = com.example.myapplication.BuildConfig.ACRCLOUD_ACCESS_KEY
        private val ACCESS_SECRET = com.example.myapplication.BuildConfig.ACRCLOUD_ACCESS_SECRET
        private const val HOST = "identify-us-west-2.acrcloud.com"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_LENGTH = 1024
    }

    //  DATA CLASS COMPLETA con todos los campos
    data class MusicResult(
        val title: String,
        val artist: String,
        val album: String = "",
        val coverUrl: String = "",
        val genre: String = "",
        val durationMs: Long = 0L,
        val externalUrls: List<String> = emptyList()
    )

    private var isRecognizing = false
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun start(durationSeconds: Int = 30) {
        if (isRecognizing) {
            Log.w(TAG, "Ya está reconociendo")
            return
        }

        isRecognizing = true
        Log.d(TAG, " Iniciando grabación para reconocimiento...")
        startRecording(durationSeconds)
    }

    private fun startRecording(durationSeconds: Int) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onError("Permiso de micrófono no concedido")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer * 2, FRAME_LENGTH * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord no inicializado")
                stop()
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, " Grabando durante $durationSeconds segundos a ${SAMPLE_RATE}Hz...")

            val audioBuffer = ByteArrayOutputStream()
            val buffer = ShortArray(FRAME_LENGTH)

            captureJob = scope.launch {
                var samplesRead = 0
                val maxSamples = SAMPLE_RATE * durationSeconds
                var maxRms = 0f
                var minRms = Float.MAX_VALUE

                while (isRecognizing && samplesRead < maxSamples) {
                    val read = audioRecord?.read(buffer, 0, FRAME_LENGTH) ?: break
                    if (read > 0) {
                        val byteBuffer = java.nio.ByteBuffer.allocate(read * 2)
                        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) {
                            byteBuffer.putShort(buffer[i])
                        }
                        audioBuffer.write(byteBuffer.array())

                        val rms = calculateRMS(buffer, read)
                        if (rms > maxRms) maxRms = rms
                        if (rms < minRms) minRms = rms
                        onVolumeChanged(rms)

                        if (samplesRead % (SAMPLE_RATE * 2) == 0) {
                            Log.d(TAG, " Volumen: max=$maxRms, min=$minRms, actual=$rms")
                        }

                        samplesRead += read
                    }
                    delay(10)
                }

                if (isRecognizing) {
                    Log.d(TAG, " Grabación completa. Volumen máximo: $maxRms")

                    if (maxRms < 0.5f) {
                        onError(" No se detectó audio. ¿La música está sonando?")
                        return@launch
                    }

                    val audioData = audioBuffer.toByteArray()
                    recognizeAudio(audioData)
                }
            }

            timeoutJob = scope.launch {
                delay((durationSeconds + 5).toLong() * 1000)
                if (isRecognizing) {
                    Log.w(TAG, " Timeout")
                    stop()
                    onError("Tiempo agotado")
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, " Error de seguridad: ${e.message}")
            onError("Permiso de micrófono denegado")
            stop()
        } catch (e: Exception) {
            Log.e(TAG, " Error grabando: ${e.message}")
            onError("Error: ${e.message}")
            stop()
        }
    }

    private fun recognizeAudio(audioData: ByteArray) {
        scope.launch {
            try {
                val wavData = pcmToWav(audioData, SAMPLE_RATE, 1, 16)
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                val signature = buildSignature(timestamp)

                Log.d(TAG, " Tamaño PCM: ${audioData.size} bytes, WAV: ${wavData.size} bytes")

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("access_key", ACCESS_KEY)
                    .addFormDataPart("signature", signature)
                    .addFormDataPart("timestamp", timestamp)
                    .addFormDataPart("signature_version", "1")
                    .addFormDataPart("data_type", "audio")
                    .addFormDataPart(
                        "sample",
                        "audio.wav",
                        wavData.toRequestBody("audio/wav".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://$HOST/v1/identify")
                    .post(requestBody)
                    .build()

                Log.d(TAG, " Enviando ${wavData.size} bytes a ACRCloud...")

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    Log.d(TAG, " Respuesta: $responseBody")
                    parseResponse(responseBody)
                } else {
                    Log.e(TAG, " HTTP ${response.code}: $responseBody")
                    onError("Error HTTP: ${response.code}")
                }

            } catch (e: Exception) {
                Log.e(TAG, " Error: ${e.message}")
                onError("Error: ${e.message}")
            }
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)

        val header = ByteArray(44)

        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0 // PCM
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * (bitsPerSample / 8)).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0

        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = (pcmData.size shr 8 and 0xff).toByte()
        header[42] = (pcmData.size shr 16 and 0xff).toByte()
        header[43] = (pcmData.size shr 24 and 0xff).toByte()

        return header + pcmData
    }

    private fun buildSignature(timestamp: String): String {
        val method = "POST"
        val uri = "/v1/identify"
        val dataType = "audio"
        val signatureVersion = "1"
        val stringToSign = "$method\n$uri\n$ACCESS_KEY\n$dataType\n$signatureVersion\n$timestamp"

        Log.d(TAG, " StringToSign: $stringToSign")

        val mac = Mac.getInstance("HmacSHA1")
        val secretKey = SecretKeySpec(ACCESS_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1")
        mac.init(secretKey)
        val hash = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    //  PARSE COMPLETO Y CORREGIDO
    private fun parseResponse(jsonString: String) {
        try {
            Log.d(TAG, " Parseando respuesta: ${jsonString.take(300)}...")

            val json = JSONObject(jsonString)
            val status = json.optJSONObject("status")
            val code = status?.optInt("code", -1) ?: -1

            Log.d(TAG, " Respuesta status: $code")

            if (code != 0) {
                val msg = status?.optString("msg") ?: "Unknown error"
                Log.e(TAG, "ACRCloud error: $code - $msg")

                val userMsg = when (code) {
                    1001 -> "No se encontró la canción. Asegúrate de que la música se escuche bien."
                    2000 -> "Error de autenticación. Verifica tus credenciales."
                    2001 -> "Error en el formato del audio."
                    3000 -> "Error interno del servidor."
                    else -> "ACRCloud error: $msg"
                }
                onError(userMsg)
                return
            }

            val metadata = json.optJSONObject("metadata")
            val musicList = metadata?.optJSONArray("music")

            if (musicList == null || musicList.length() == 0) {
                Log.w(TAG, " No se encontraron resultados")
                onError("No se encontró la canción. ¿La música se escucha bien?")
                return
            }

            val track = musicList.getJSONObject(0)

            //  Título y artista
            val title = track.optString("title", "Título desconocido")
            val artist = parseArtist(track)

            //  Álbum y portada
            val albumObj = track.optJSONObject("album")
            val album = albumObj?.optString("name", "") ?: ""
            val coverUrl = albumObj?.optString("cover_url", "") ?: ""

            //  Género (desde el array "genres")
            val genresArray = track.optJSONArray("genres")
            val genre = if (genresArray != null && genresArray.length() > 0) {
                genresArray.getJSONObject(0).optString("name", "")
            } else ""

            //  Duración
            val durationMs = track.optLong("duration_ms", 0L)

            //  ENLACES EXTERNOS - PARSING COMPLETO
            val externalUrls = mutableListOf<String>()
            val externalMeta = track.optJSONObject("external_metadata")

            Log.d(TAG, " external_metadata: $externalMeta")

            if (externalMeta != null) {
                // ═══════ SPOTIFY ═══════
                val spotifyObj = externalMeta.optJSONObject("spotify")
                if (spotifyObj != null) {
                    val trackObj = spotifyObj.optJSONObject("track")
                    val trackId = trackObj?.optString("id")
                    if (!trackId.isNullOrBlank()) {
                        val url = "https://open.spotify.com/track/$trackId"
                        externalUrls.add(url)
                        Log.d(TAG, " Spotify URL: $url")
                    }
                }

                // ═══════ YOUTUBE ═══════
                val youtubeObj = externalMeta.optJSONObject("youtube")
                if (youtubeObj != null) {
                    val videoId = youtubeObj.optString("vid")
                    if (!videoId.isNullOrBlank()) {
                        val url = "https://music.youtube.com/watch?v=$videoId"
                        externalUrls.add(url)
                        Log.d(TAG, " YouTube URL: $url")
                    }
                }

                // ═══════ APPLE MUSIC ═══════
                val appleObj = externalMeta.optJSONObject("apple_music")
                if (appleObj != null) {
                    val url = appleObj.optString("url")
                    if (!url.isNullOrBlank()) {
                        externalUrls.add(url)
                        Log.d(TAG, " Apple Music URL: $url")
                    }
                }

                // ═══════ DEEZER ═══════
                val deezerObj = externalMeta.optJSONObject("deezer")
                if (deezerObj != null) {
                    val url = deezerObj.optString("url")
                    if (!url.isNullOrBlank()) {
                        externalUrls.add(url)
                        Log.d(TAG, " Deezer URL: $url")
                    }
                }
            }

            //  Si no hay enlaces, intentar con external_ids (fallback)
            if (externalUrls.isEmpty()) {
                val externalIds = track.optJSONObject("external_ids")
                val spotifyId = externalIds?.optString("spotify")
                if (!spotifyId.isNullOrBlank()) {
                    val url = "https://open.spotify.com/track/$spotifyId"
                    externalUrls.add(url)
                    Log.d(TAG, " Spotify ID fallback: $url")
                }
            }

            Log.d(TAG, " Enlaces finales: $externalUrls")

            val result = MusicResult(
                title = title,
                artist = artist,
                album = album,
                coverUrl = coverUrl,
                genre = genre,
                durationMs = durationMs,
                externalUrls = externalUrls
            )

            Log.d(TAG, " Canción identificada: $title - $artist")
            onResult(result)

        } catch (e: Exception) {
            Log.e(TAG, " Error parseando: ${e.message}", e)
            onError("Error parseando resultado")
        }
    }

    private fun parseArtist(track: JSONObject): String {
        val artistsField = track.opt("artists")
        return when (artistsField) {
            is String -> artistsField
            is JSONArray -> {
                (0 until artistsField.length()).joinToString(", ") { i ->
                    artistsField.getJSONObject(i).optString("name", "")
                }
            }
            is JSONObject -> artistsField.optString("name", "Artista desconocido")
            else -> "Artista desconocido"
        }
    }

    fun stop() {
        isRecognizing = false
        captureJob?.cancel()
        timeoutJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando AudioRecord: ${e.message}")
        }
        audioRecord = null

        Log.d(TAG, " Reconocimiento detenido")
    }

    private fun calculateRMS(frame: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return (kotlin.math.sqrt(sum / size).toFloat() / 400f).coerceIn(0f, 12f)
    }
}