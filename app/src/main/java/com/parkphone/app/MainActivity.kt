package com.parkphone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.parkphone.app.databinding.ActivityMainBinding
import com.parkphone.app.databinding.DialogConfirmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnShutter.setOnClickListener { takePicture() }
        binding.btnRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }

        if (hasCameraPermission()) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        lifecycleScope.launch {
            try {
                val provider = ProcessCameraProvider.getInstance(this@MainActivity).await()
                provider.unbindAll()
                val preview = Preview().apply {
                    surfaceProvider = binding.previewView.surfaceProvider
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(
                    this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "相机启动失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun takePicture() {
        if (!hasCameraPermission()) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val capture = imageCapture ?: return
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        binding.btnShutter.isEnabled = false
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    recognize(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnShutter.isEnabled = true
                    file.delete()
                    Toast.makeText(this@MainActivity, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun recognize(file: File) {
        binding.status.setText(R.string.recognizing)
        lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    InputImage.fromFilePath(this@MainActivity, Uri.fromFile(file))
                }
                val text = recognizer.await(image)
                val phones = PhoneExtractor.extract(text)
                val bitmap = withContext(Dispatchers.IO) { decodeSampled(file, 1280) }
                showConfirmDialog(file, text, phones, bitmap)
            } catch (t: Throwable) {
                file.delete()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.recognize_failed, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.btnShutter.isEnabled = true
                binding.status.setText(R.string.hint_ready)
            }
        }
    }

    /** 把 ML Kit 的 Task 包装成挂起函数，不额外引入协程扩展库 */
    private suspend fun com.google.mlkit.vision.text.TextRecognizer.await(
        image: InputImage
    ): String = suspendCancellableCoroutine { cont ->
        process(image)
            .addOnSuccessListener { result: Text ->
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener { e: Exception ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    private fun showConfirmDialog(
        file: File, rawText: String, phones: List<String>, bitmap: Bitmap?
    ) {
        val df = DialogConfirmBinding.inflate(layoutInflater)
        val fmt = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
        df.etTitle.setText(getString(R.string.default_title, fmt.format(Date())))
        if (phones.isNotEmpty()) df.etPhone.setText(phones.first())
        df.tvRaw.text =
            if (rawText.isBlank()) getString(R.string.no_text) else rawText.trim()
        if (phones.size > 1) {
            df.tvCandidates.text = getString(R.string.candidates, phones.joinToString("   "))
            df.tvCandidates.visibility = View.VISIBLE
        } else {
            df.tvCandidates.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setView(df.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = df.etTitle.text.toString().trim().ifBlank { getString(R.string.untitled) }
                val phone = df.etPhone.text.toString().filter { it.isDigit() || it == '+' }
                if (phone.length < 7) {
                    Toast.makeText(this, R.string.phone_too_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                RecordStore.save(this, title, phone, bitmap)
                bitmap?.recycle()
                file.delete()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                bitmap?.recycle()
                file.delete()
            }
            .setOnDismissListener { bitmap?.recycle() }
            .show()
    }

    private fun decodeSampled(f: File, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()
}
