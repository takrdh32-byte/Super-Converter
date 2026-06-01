package com.superconverter.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.superconverter.R
import com.superconverter.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardPdf.setOnClickListener { findNavController().navigate(R.id.pdfFragment) }
        binding.cardOcr.setOnClickListener { findNavController().navigate(R.id.ocrFragment) }
        binding.cardPng.setOnClickListener { findNavController().navigate(R.id.pngFragment) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}