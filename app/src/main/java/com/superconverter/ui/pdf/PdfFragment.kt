package com.superconverter.ui.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.superconverter.databinding.FragmentPdfBinding
import com.superconverter.utils.FileUtils
import com.superconverter.utils.ImageBatchAdapter
import com.superconverter.utils.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfFragment : Fragment() {

    private var _binding: FragmentPdfBinding? = null
    private val binding get() = _binding!!

    private val adapter = ImageBatchAdapter()
    private val selectedItems = mutableListOf<ImageItem>()
    private val MAX_BATCH = 30

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val toAdd = uris.take(MAX_BATCH - selectedItems.size)
        toAdd.forEach { uri ->
            selectedItems.add(ImageItem(uri, getFileName(uri), getFileSize(uri)))
        }
        if (uris.size > toAdd.size) showToast("Maximum $MAX_BATCH images allowed")
        updateUI()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPdfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.uploadZone.setOnClickListener {
            if (selectedItems.size < MAX_BATCH) pickImages.launch("image/*")
            else showToast("Maximum $MAX_BATCH images already selected")
        }
        binding.btnClearAll.setOnClickListener {
            selectedItems.clear()
            updateUI()
        }
        binding.btnConvert.setOnClickListener {
            if (selectedItems.isNotEmpty()) convertToPdf()
        }
        updateUI()
    }

    private fun updateUI() {
        adapter.submitList(selectedItems.toList())
        val count = selectedItems.size
        binding.batchCountBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.batchLabel.text = "$count file${if (count != 1) "s" else ""} selected"
        binding.btnConvert.isEnabled = count > 0
        if (count > 0) {
            binding.uploadZoneTitle.text = "Add More Images"
            binding.uploadZoneSub.text = "${MAX_BATCH - count} slots remaining"
        } else {
            binding.uploadZoneTitle.text = "Tap to Select Images"
            binding.uploadZoneSub.text = "Select 1–30 photos to combine into one PDF"
        }
    }

    private fun convertToPdf() {
        binding.progressGroup.visibility = View.VISIBLE
        binding.btnConvert.isEnabled = false
        binding.progressTitle.text = "Building PDF…"
        binding.progressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pdfBytes = buildPdf()
                withContext(Dispatchers.Main) {
                    binding.progressGroup.visibility = View.GONE
                    onPdfReady(pdfBytes)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressGroup.visibility = View.GONE
                    binding.btnConvert.isEnabled = true
                    showToast("PDF creation failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun buildPdf(): ByteArray {
        // Android built-in PdfDocument — no external library needed
        val pdfDocument = PdfDocument()

        selectedItems.forEachIndexed { index, item ->
            withContext(Dispatchers.Main) {
                adapter.updateItemStatus(index, ImageItem.Status.PROCESSING)
                binding.progressBar.progress = ((index.toFloat() / selectedItems.size) * 100).toInt()
                binding.progressStep.text = "Processing ${index + 1} / ${selectedItems.size}"
            }

            try {
                val inputStream = requireContext().contentResolver.openInputStream(item.uri)
                    ?: throw Exception("Cannot open ${item.name}")

                val bitmap = BitmapFactory.decodeStream(inputStream)
                    ?: throw Exception("Cannot decode ${item.name}")

                // A4 size in points: 595 x 842
                val pageWidth = 595
                val pageHeight = 842

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // White background
                canvas.drawColor(Color.WHITE)

                // Scale bitmap to fit page
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val scale = minOf(
                    pageWidth.toFloat() / bitmap.width,
                    pageHeight.toFloat() / bitmap.height
                )
                val scaledW = bitmap.width * scale
                val scaledH = bitmap.height * scale
                val left = (pageWidth - scaledW) / 2f
                val top = (pageHeight - scaledH) / 2f

                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    scaledW.toInt(),
                    scaledH.toInt(),
                    true
                )
                canvas.drawBitmap(scaledBitmap, left, top, paint)

                bitmap.recycle()
                scaledBitmap.recycle()

                pdfDocument.finishPage(page)

                withContext(Dispatchers.Main) {
                    adapter.updateItemStatus(index, ImageItem.Status.DONE)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    adapter.updateItemStatus(index, ImageItem.Status.ERROR)
                }
            }
        }

        val baos = ByteArrayOutputStream()
        pdfDocument.writeTo(baos)
        pdfDocument.close()
        return baos.toByteArray()
    }

    private fun onPdfReady(pdfBytes: ByteArray) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SuperConverter_$timestamp.pdf"

        val uri = FileUtils.saveToDownloads(
            requireContext(), pdfBytes, fileName, "application/pdf"
        )

        if (uri != null) {
            val kb = pdfBytes.size / 1024
            showToast("✓ PDF saved ($kb KB) — check Downloads")
            binding.successGroup.visibility = View.VISIBLE
            binding.btnConvert.isEnabled = false
            binding.successTitle.text = "PDF Ready!"
            binding.successSub.text = "${selectedItems.size} page${if (selectedItems.size != 1) "s" else ""} · $kb KB\nSaved to Downloads"
            binding.btnOpenPdf.setOnClickListener {
                FileUtils.openFile(requireContext(), uri, "application/pdf")
            }
            binding.btnConvertMore.setOnClickListener { resetState() }
        } else {
            showToast("Failed to save PDF.")
            binding.btnConvert.isEnabled = true
        }
    }

    private fun resetState() {
        selectedItems.clear()
        binding.successGroup.visibility = View.GONE
        binding.btnConvert.isEnabled = false
        updateUI()
    }

    private fun getFileName(uri: Uri): String {
        var name = "image_${System.currentTimeMillis()}"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && cursor.moveToFirst()) name = cursor.getString(col) ?: name
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (col >= 0 && cursor.moveToFirst()) size = cursor.getLong(col)
        }
        return size
    }

    private fun showToast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}