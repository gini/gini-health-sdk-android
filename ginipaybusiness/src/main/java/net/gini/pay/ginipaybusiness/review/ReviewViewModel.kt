package net.gini.pay.ginipaybusiness.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.gini.android.models.Document
import net.gini.android.models.PaymentProvider
import net.gini.android.models.PaymentRequestInput
import net.gini.pay.ginipaybusiness.GiniBusiness
import net.gini.pay.ginipaybusiness.review.bank.BankApp
import net.gini.pay.ginipaybusiness.review.error.NoBankSelected
import net.gini.pay.ginipaybusiness.review.error.NoProviderForPackageName
import net.gini.pay.ginipaybusiness.review.model.PaymentDetails
import net.gini.pay.ginipaybusiness.review.model.ResultWrapper
import net.gini.pay.ginipaybusiness.review.model.withFeedback
import net.gini.pay.ginipaybusiness.review.pager.DocumentPageAdapter

internal class ReviewViewModel(internal val giniBusiness: GiniBusiness) : ViewModel() {

    private val _paymentDetails = MutableStateFlow(PaymentDetails("", "", "", ""))
    val paymentDetails: StateFlow<PaymentDetails> = _paymentDetails

    private val _paymentValidation = MutableStateFlow<List<ValidationMessage>>(emptyList())
    val paymentValidation: StateFlow<List<ValidationMessage>> = _paymentValidation

    var selectedBank: BankApp? = null

    init {
        viewModelScope.launch {
            giniBusiness.paymentFlow.collect { extractedPaymentDetails ->
                if (extractedPaymentDetails is ResultWrapper.Success) {
                    _paymentDetails.value = extractedPaymentDetails.value
                }
            }
        }
    }

    fun getPages(document: Document): List<DocumentPageAdapter.Page> {
        return (1..document.pageCount).map { pageNumber ->
            DocumentPageAdapter.Page(document.id, pageNumber)
        }
    }

    fun setRecipient(recipient: String) {
        _paymentDetails.value = paymentDetails.value.copy(recipient = recipient)
    }

    fun setIban(iban: String) {
        _paymentDetails.value = paymentDetails.value.copy(iban = iban)
    }

    fun setAmount(amount: String) {
        _paymentDetails.value = paymentDetails.value.copy(amount = amount)
    }

    fun setPurpose(purpose: String) {
        _paymentDetails.value = paymentDetails.value.copy(purpose = purpose)
    }

    private fun validatePaymentDetails(): Boolean {
        val items = paymentDetails.value.validate()
        _paymentValidation.value = items
        return items.isEmpty()
    }

    private suspend fun getPaymentProviderForPackage(packageName: String): PaymentProvider {
        return giniBusiness.giniApi.documentManager.getPaymentProviders().find { it.packageName == packageName }
            ?: throw NoProviderForPackageName(packageName)
    }

    private suspend fun getPaymentRequest(bank: BankApp): String {
        return giniBusiness.giniApi.documentManager.createPaymentRequest(
            PaymentRequestInput(
                paymentProvider = getPaymentProviderForPackage(bank.packageName).id,
                recipient = paymentDetails.value.recipient,
                iban = paymentDetails.value.iban,
                amount = "${paymentDetails.value.amount}:EUR",
                bic = null,
                purpose = paymentDetails.value.purpose,
            )
        )
    }

    fun onPayment() {
        viewModelScope.launch {
            val valid = validatePaymentDetails()
            if (valid) {
                giniBusiness.setOpenBankState(GiniBusiness.PaymentState.Loading)
                sendFeedback()
                giniBusiness.setOpenBankState(try {
                    selectedBank?.let { bankApp ->
                        GiniBusiness.PaymentState.Success(bankApp, getPaymentRequest(bankApp)) // TODO bank app has to be associated with request id
                    } ?: GiniBusiness.PaymentState.Error(NoBankSelected())
                } catch (throwable: Throwable) {
                    GiniBusiness.PaymentState.Error(throwable)
                })
            }
        }
    }

    fun onBankOpened() {
        giniBusiness.setOpenBankState(GiniBusiness.PaymentState.NoAction)
    }

    private fun sendFeedback() {
        viewModelScope.launch {
            try {
                when (val documentResult = giniBusiness.documentFlow.value) {
                    is ResultWrapper.Success -> paymentDetails.value.extractions?.let { extractionsContainer ->
                        giniBusiness.giniApi.documentManager.sendFeedback(
                            documentResult.value,
                            extractionsContainer.specificExtractions.withFeedback(paymentDetails.value),
                            extractionsContainer.compoundExtractions
                        )
                    }
                }
            } catch (ignored: Throwable) {
                // Ignored since we don't want to interrupt the flow because of feedback failure
            }
        }
    }

    fun retryDocumentReview() {
        viewModelScope.launch {
            giniBusiness.retryDocumentReview()
        }
    }
}

internal fun getReviewViewModelFactory(giniBusiness: GiniBusiness) = object : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ReviewViewModel(giniBusiness) as T
    }
}