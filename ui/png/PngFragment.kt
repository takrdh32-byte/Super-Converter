package com.superconverter.ui.png

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
import com.superconverter.databinding.FragmentPngBinding
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

class PngFragment : Fragment() {

    private var _binding: FragmentPngBinding? = null
    private val binding get() = _binding!!

    private val adapter = ImageBatchAdapter()
    private val selectedItems = mutableListOf<ImageItem>()
    private val MAX_BATCH = 30
    private val pngResults = mutableListOf<Pair<String, ByteArray>>()

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
        _binding = FragmentPngBinding.inflate(inflater, container, false)
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
        binding.btnConvert.setOnClickListener { if (selectedItems.isNotEmpty()) convertToPng() }
        binding.btnConvertMore.setOnClickListener { resetState() }
        updateUI()
    }

    private fun updateUI() {
        adapter.submitList(selectedItems.toList())
        val count = selectedItems.size
        binding.batchCountBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.batchLabel.text = "$count file${if (count != 1) "s" else ""} selected"
        binding.btnConvert.isEnabled = count > 0
    }

    private fun convertToPng() {
        binding.progressGroup.visibility = View.VISIBLE
        binding.btnConvert.isEnabled = false
        pngResults.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            selectedItems.forEachIndexed { index, item ->
                withContext(Dispatchers.Main) {
                    adapter.updateItemStatus(index, ImageItem.Status.PROCESSING)
                    binding.progressBar.progress = ((index.toFloat() / selectedItems.size) * 100).toInt()
                    binding.progressStep.text = "Converting ${index + 1} / ${selectedItems.size}"
                }
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(item.uri)
                        ?: throw Exception("Cannot open ${item.name}")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                        ?: throw Exception("Could not decode ${item.name}")
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    bitmap.recycle()
                    val pngBytes = baos.toByteArray()
                    val baseName = item.name.substringBeforeLast('.', item.name)
                    pngResults.add(Pair("${baseName}.png", pngBytes))
                    withContext(Dispatchers.Main) { adapter.updateItemStatus(index, ImageItem.Status.DONE) }
                } catch (e: Exception) {
                    e.printStackTrace()
                    pngResults.add(Pair("", ByteArray(0)))
                    withContext(Dispatchers.Main) { adapter.updateItemStatus(index, ImageItem.Status.ERROR) }
                }
            }
            withContext(Dispatchers.Main) {
                binding.progressGroup.visibility = View.GONE
                showResults()
            }
        }
    }

    private fun showResults() {
        val successCount = pngResults.count { it.second.isNotEmpty() }
        binding.successGroup.visibility = View.VISIBLE
        binding.successSub.text = "$successCount / ${selectedItems.size} images converted"
        binding.pngResultList.removeAllViews()
        pngResults.forEachIndexed { index, (name, bytes) ->
            if (bytes.isEmpty()) return@forEachIndexed
            val kb = bytes.size / 1024
            val btn = android.widget.Button(requireContext()).apply {
                text = "Save  $name  (${kb} KB)"
                textSize = 13f
                setOnClickListener { saveSinglePng(index) }
            }
            binding.pngResultList.addView(btn)
        }
        binding.btnDownloadAll.setOnClickListener { downloadAllPng() }
    }

    private fun saveSinglePng(index: Int) {
        val (name, bytes) = pngResults[index]
        if (bytes.isEmpty()) { showToast("File not available"); return }
        val uri = FileUtils.saveToDownloads(requireContext(), bytes, name, "image/png")
        if (uri != null) showToast("✓ Saved: $name") else showToast("Failed to save $name")
    }

    private fun downloadAllPng() {
        var saved = 0
        pngResults.forEach { (name, bytes) ->
            if (bytes.isNotEmpty()) {
                val uri = FileUtils.saveToDownloads(requireContext(), bytes, name, "image/png")
                if (uri != null) saved++
            }
        }
        showToast("✓ Saved $saved PNG file${if (saved != 1) "s" else ""} to Downloads")
    }

    private fun resetState() {
        selectedItems.clear()
        pngResults.clear()
        binding.successGroup.visibility = View.GONE
        binding.pngResultList.removeAllViews()
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