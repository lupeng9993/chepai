package com.parkphone.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parkphone.app.databinding.ActivityRecordsBinding
import com.parkphone.app.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding
    private val adapter = RecAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val data = RecordStore.all(this)
        adapter.data = data
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPhoto(rec: ParkRecord) {
        val bmp: Bitmap? = RecordStore.loadPhoto(this, rec.id)
        if (bmp == null) {
            Toast.makeText(this, R.string.photo_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        val pad = (16 * resources.displayMetrics.density).toInt()
        iv.setPadding(pad, pad, pad, pad)
        MaterialAlertDialogBuilder(this)
            .setTitle(rec.title)
            .setView(iv)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { bmp.recycle() }
            .show()
    }

    private fun confirmDelete(rec: ParkRecord) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, rec.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                RecordStore.delete(this, rec)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class RecAdapter : RecyclerView.Adapter<RecVH>() {
        var data: List<ParkRecord> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecVH =
            RecVH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: RecVH, position: Int) = holder.bind(data[position])
    }

    private inner class RecVH(private val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(rec: ParkRecord) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            b.tvTitle.text = rec.title
            b.tvPhone.text = rec.phone
            b.tvTime.text = fmt.format(Date(rec.time))
            b.btnPhoto.isEnabled = rec.hasPhoto
            b.btnDial.setOnClickListener { dial(rec.phone) }
            b.btnPhoto.setOnClickListener { showPhoto(rec) }
            b.btnDelete.setOnClickListener { confirmDelete(rec) }
        }
    }
}
