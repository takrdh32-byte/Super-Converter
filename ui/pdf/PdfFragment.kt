package com.superconverter.ui.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.superconverter.R
import com.superconverter.databinding.FragmentPdfBinding
import com.superconverter.utils.FileUtils
import com.superconverter.utils.ImageBatchAdapter
import com.superconverter.utils.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
        binding.btnClearAll.setOnClickListener { selectedItems.clear(); updateUI() }
        binding.btnConvert.setOnClickListener { if (selectedItems.isNotEmpty()) convertToPdf() }
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
        val baos = ByteArrayOutputStream()
        val writer = PdfWriter(baos)
        val pdfDoc = PdfDocument(writer)
        val document = Document(pdfDoc, PageSize.A4)
        document.setMargins(0f, 0f, 0f, 0f)

        selectedItems.forEachIndexed { index, item ->
            withContext(Dispatchers.Main) {
                adapter.updateItemStatus(index, ImageItem.Status.PROCESSING)
                binding.progressBar.progress = ((index.toFloat() / selectedItems.size) * 100).toInt()
                binding.progressStep.text = "Processing ${index + 1} / ${selectedItems.size}"
            }
            try {
                val inputStream: InputStream = requireContext().contentResolver.openInputStream(item.uri)
                    ?: throw Exception("Cannot open ${item.name}")
                val bitmapBytes = processImageToJpegBytes(inputStream)
                val imgData = ImageDataFactory.create(bitmapBytes)
                val pdfImage = Image(imgData)
                val pageW = PageSize.A4.width
                val pageH = PageSize.A4.height
                val scaleW = pageW / pdfImage.imageWidth
                val scaleH = pageH / pdfImage.imageHeight
                val scale = minOf(scaleW, scaleH)
                pdfImage.scaleAbsolute(pdfImage.imageWidth * scale, pdfImage.imageHeight * scale)
                pdfImage.setFixedPosition(
                    index + 1,
                    (pageW - pdfImage.imageScaledWidth) / 2f,
                    (pageH - pdfImage.imageScaledHeight) / 2f
                )
                if (index > 0) pdfDoc.addNewPage()
                document.add(pdfImage)
                withContext(Dispatchers.Main) { adapter.updateItemStatus(index, ImageItem.Status.DONE) }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { adapter.updateItemStatus(index, ImageItem.Status.ERROR) }
            }
        }
        document.close()
        return baos.toByteArray()
    }

    private fun processImageToJpegBytes(inputStream: InputStream): ByteArray {
        val bitmap = BitmapFactory.decodeStream(inputStream) ?: throw Exception("Could not decode image")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun onPdfReady(pdfBytes: ByteArray) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SuperConverter_$timestamp.pdf"
        val uri = FileUtils.saveToDownloads(requireContext(), pdfBytes, fileName, "application/pdf")
        if (uri != null) {
            val kb = pdfBytes.size / 1024
            showToast("✓ PDF saved ($kb KB) — check Downloads")
            binding.successGroup.visibility = View.VISIBLE
            binding.btnConvert.isEnabled = false
            binding.successTitle.text = "PDF Ready!"
            binding.successSub.text = "${selectedItems.size} page${if (selectedItems.size != 1) "s" else ""} · $kb KB\nSaved to Downloads"
            binding.btnOpenPdf.setOnClickListener { FileUtils.openFile(requireContext(), uri, "application/pdf") }
            binding.btnConvertMore.setOnClickListener { resetState() }
        } else {
            showToast("Failed to save PDF. Check storage permission.")
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

    private fun showToast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}