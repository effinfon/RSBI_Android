//package ro.rsbideveloper.rsbi.dialogs
//
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.fragment.app.Fragment
//import ro.rsbideveloper.rsbi.R
//import ro.rsbideveloper.rsbi.databinding.DetailedViewEventBinding
//
//class DetailedViewEvent : Fragment(R.layout.detailed_view_event) {
//    private var _binding: DetailedViewEventBinding? = null
//    private val binding get() = _binding!!
//
//    override fun onCreateView(
//        inflater: LayoutInflater, container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View {
//        _binding = DetailedViewEventBinding.inflate(inflater, container, false)
//        return binding.root
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//}