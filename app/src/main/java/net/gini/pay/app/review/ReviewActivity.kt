package net.gini.pay.app.review

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.commit
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.insetter.applyInsetter
import net.gini.pay.app.R
import net.gini.pay.app.databinding.ActivityReviewBinding
import net.gini.pay.ginipaybusiness.GiniBusiness
import net.gini.pay.ginipaybusiness.review.ReviewFragment
import org.koin.androidx.viewmodel.ext.android.viewModel

class ReviewActivity : AppCompatActivity() {

    private val viewModel: ReviewViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        viewModel.giniBusiness.setSavedStateRegistryOwner(this, viewModel.viewModelScope)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportFragmentManager.fragmentFactory = ReviewFragmentFactory(viewModel.giniBusiness)
        super.onCreate(savedInstanceState)
        val binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.applyInsetter {
            type(statusBars = true) {
                padding(top = true)
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.review_fragment, ReviewFragment::class.java, null)
            }
        }

        binding.close.setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_URIS = "EXTRA_URIS"
        fun getStartIntent(context: Context, pages: List<Uri>): Intent = Intent(context, ReviewActivity::class.java).apply {
            putParcelableArrayListExtra(EXTRA_URIS, if (pages is ArrayList<Uri>) pages else ArrayList<Uri>().apply { addAll(pages) })
        }

        private val Intent.pageUris: List<Uri>
            get() = getParcelableArrayListExtra<Uri>(EXTRA_URIS)?.toList() ?: emptyList()
    }
}


class ReviewFragmentFactory(private val giniBusiness: GiniBusiness) : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        return ReviewFragment(giniBusiness)
    }
}