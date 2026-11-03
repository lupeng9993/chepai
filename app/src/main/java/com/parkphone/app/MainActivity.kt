package com.parkphone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.parkphone.app.databinding.ActivityMainBinding
import com.parkphone.app.databinding.DialogConfirmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        showCrashIfNeeded()
    }

    /** 如果上一次运行异常退出，提示并允许复制日志 */
    private fun showCrashIfNeeded() {
        val log = CrashGuard.take() ?: return
        val tv = android.widget.TextView(this).apply {
            text = log.take(1200)
            textSize = 11f
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_title)
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                CrashGuard.copy(this, log)
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                val provider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(this@MainActivity).get()
                }
                provider.unbindAll()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.bindToLifecycle(
                    this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
            } catch (t: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.camera_failed, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT
                ).show()
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
        val image = try {
            InputImage.fromFilePath(this, Uri.fromFile(file))
        } catch (t: Throwable) {
            onRecognizeFailed(file, t)
            return
        }
        recognizer.process(image)
            .addOnSuccessListener { result ->
                binding.btnShutter.isEnabled = true
                binding.status.setText(R.string.hint_ready)
                val text = result.text
                showConfirmDialog(file, text, PhoneExtractor.extract(text))
            }
            .addOnFailureListener { e -> onRecognizeFailed(file, e) }
    }

    private fun onRecognizeFailed(file: File, t: Throwable) {
        file.delete()
        binding.btnShutter.isEnabled = true
        binding.status.setText(R.string.hint_ready)
        Toast.makeText(
            this,
            getString(R.string.recognize_failed, t.message ?: t.javaClass.simpleName),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showConfirmDialog(file: File, rawText: String, phones: List<String>) {
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

        // 历史标题快捷选择（最多 4 个）
        val titles = RecordStore.titles(this).take(4)
        if (titles.isNotEmpty()) {
            val density = resources.displayMetrics.density
            titles.forEach { t ->
                val chip = TextView(this).apply {
                    text = t
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFF1565C0.toInt())
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.tag_bg)
                    val h = (10 * density).toInt()
                    val v = (5 * density).toInt()
                    setPadding(h, v, h, v)
                    val lp = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    lp.marginEnd = (6 * density).toInt()
                    layoutParams = lp
                    setOnClickListener { df.etTitle.setText(t) }
                }
                df.titleChips.addView(chip)
            }
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
                RecordStore.save(this, title, phone)
                file.delete()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.discard) { _, _ -> file.delete() }
            .show()
    }
}
