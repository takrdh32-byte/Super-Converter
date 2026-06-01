package com.superconverter.ui.ocr

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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.superconverter.databinding.FragmentOcrBinding
import com.superconverter.utils.FileUtils
import com.superconverter.utils.ImageBatchAdapter
import com.superconverter.utils.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrFragment : Fragment() {

    private var _binding: FragmentOcrBinding? = null
    private val binding get() = _binding!!

    private val adapter = ImageBatchAdapter()
    private val selectedItems = mutableListOf<ImageItem>()
    private val MAX_BATCH = 30
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
        _binding = FragmentOcrBinding.inflate(inflater, container, false)
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
        binding.btnExtract.setOnClickListener { if (selectedItems.isNotEmpty()) runOcr() }
        binding.btnCopyText.setOnClickListener {
            val text = binding.ocrResult.text.toString()
            if (text.isNotBlank()) {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR Result", text))
                showToast("Copied to clipboard!")
            }
        }
        binding.btnSaveTxt.setOnClickListener { saveTxtFile() }
        binding.btnScanMore.setOnClickListener { resetState() }
        updateUI()
    }

    private fun updateUI() {
        adapter.submitList(selectedItems.toList())
        val count = selectedItems.size
        binding.batchCountBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.batchLabel.text = "$count file${if (count != 1) "s" else ""} selected"
        binding.btnExtract.isEnabled = count > 0
    }

    private fun runOcr() {
        binding.progressGroup.visibility = View.VISIBLE
        binding.btnExtract.isEnabled = false
        val sb = StringBuilder()

        lifecycleScope.launch(Dispatchers.IO) {
            selectedItems.forEachIndexed { index, item ->
                withContext(Dispatchers.Main) {
                    adapter.updateItemStatus(index, ImageItem.Status.PROCESSING)
                    binding.progressBar.progress = ((index.toFloat() / selectedItems.size) * 100).toInt()
                    binding.progressStep.text = "Scanning ${index + 1} / ${selectedItems.size}"
                }
                try {
                    val image = InputImage.fromFilePath(requireContext(), item.uri)
                    val result = recognizeText(image)
                    sb.append("── ${item.name} ──\n")
                    sb.append(result.text.ifBlank { "(no text found)" })
                    sb.append("\n\n")
                    withContext(Dispatchers.Main) { adapter.updateItemStatus(index, ImageItem.Status.DONE) }
                } catch (e: Exception) {
                    sb.append("── ${item.name} ──\n(error: ${e.message})\n\n")
                    withContext(Dispatchers.Main) { adapter.updateItemStatus(index, ImageItem.Status.ERROR) }
                }
            }
            withContext(Dispatchers.Main) {
                binding.progressGroup.visibility = View.GONE
                val finalText = sb.toString().trim()
                binding.ocrResult.setText(finalText)
                binding.successGroup.visibility = View.VISIBLE
                binding.successSub.text = "${selectedItems.size} image${if (selectedItems.size != 1) "s" else ""} scanned"
            }
        }
    }

    private suspend fun recognizeText(image: InputImage) =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun saveTxtFile() {
        val text = binding.ocrResult.text.toString()
        if (text.isBlank()) { showToast("No text to save"); return }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "OCR_$timestamp.txt"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val uri = FileUtils.saveToDownloads(requireContext(), bytes, fileName, "text/plain")
        if (uri != null) showToast("✓ Saved as $fileName in Downloads")
        else showToast("Failed to save file")
    }

    private fun resetState() {
        selectedItems.clear()
        binding.successGroup.visibility = View.GONE
        binding.ocrResult.setText("")
        binding.btnExtract.isEnabled = false
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

    override fun onDestroyView() { super.onDestroyView(); recognizer.close(); _binding = null }
}