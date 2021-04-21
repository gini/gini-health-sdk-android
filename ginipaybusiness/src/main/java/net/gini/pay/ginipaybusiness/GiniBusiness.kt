package net.gini.pay.ginipaybusiness

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.gini.android.Gini
import net.gini.android.models.Document
import net.gini.pay.ginipaybusiness.requirement.Requirement
import net.gini.pay.ginipaybusiness.requirement.internalCheckRequirements
import net.gini.pay.ginipaybusiness.review.ReviewFragment
import net.gini.pay.ginipaybusiness.review.model.PaymentDetails
import net.gini.pay.ginipaybusiness.review.model.PaymentRequest
import net.gini.pay.ginipaybusiness.review.model.ResultWrapper
import net.gini.pay.ginipaybusiness.review.model.toPaymentDetails
import net.gini.pay.ginipaybusiness.review.model.wrapToResult

/**
 * [GiniBusiness] is the main class for interacting with Gini Pay Business SDK.
 * It provides a way to submit a document for reviewing its extracted payment details and
 * let's the user make the payment with one of the payment providers.
 *
 * The recommended flow is:
 *  1. Call [checkRequirements] to make sure that the flow can be completed.
 *  2. Call one of the overloads of [setDocumentForReview], to submit a document.
 *  3. Display [ReviewFragment].
 *
 * [setDocumentForReview] can be called with:
 *  1. [Document] instance in the case the upload was performed with Gini Pay Api lib ([Gini]).
 *  2. Document id, this will probably be the case when there's backend integration between the Business Client and Gini.
 *      This method will make a network call to obtain a [Document] instance so the other one is preferred if you have the [Document] instance.
 *
 *  [documentFlow], [paymentFlow], [openBankState] are used by the ReviewFragment to observe their state, but they are public
 *  so that they can be observed anywhere, the main purpose for this is to observe errors.
 */
class GiniBusiness(
    val giniApi: Gini
) {
    private val documentManager = giniApi.documentManager

    private var capturedArguments: CapturedArguments? = null

    private val _documentFlow = MutableStateFlow<ResultWrapper<Document>>(ResultWrapper.Loading())

    /**
     * A flow for getting the [Document] set for review [setDocumentForReview].
     *
     * It always starts with [ResultWrapper.Loading] when setting a document.
     * [Document] will be wrapped in [ResultWrapper.Success], otherwise the throwable will
     * be in a [ResultWrapper.Error].
     *
     * It never completes.
     */
    val documentFlow: StateFlow<ResultWrapper<Document>> = _documentFlow

    private val _paymentFlow = MutableStateFlow<ResultWrapper<PaymentDetails>>(ResultWrapper.Loading())

    /**
     * A flow for getting extracted [PaymentDetails] for the document set for review (see [setDocumentForReview]).
     *
     * It always starts with [ResultWrapper.Loading] when setting a document.
     * [PaymentDetails] will be wrapped in [ResultWrapper.Success], otherwise the throwable will
     * be in a [ResultWrapper.Error].
     *
     * It never completes.
     */
    val paymentFlow: StateFlow<ResultWrapper<PaymentDetails>> = _paymentFlow

    private val _openBankState = MutableStateFlow<PaymentState>(PaymentState.NoAction)

    /**
     * A flow that exposes the state of opening the bank. You can collect this flow to get information about the errors of this action.
     */
    val openBankState: StateFlow<PaymentState> = _openBankState

    /**
     * Sets a [Document] for review. Results can be collected from [documentFlow] and [paymentFlow].
     *
     * @param document document received from Gini API.
     */
    suspend fun setDocumentForReview(document: Document) {
        capturedArguments = CapturedArguments.DocumentInstance(document)
        _documentFlow.value = ResultWrapper.Success(document)
        _paymentFlow.value = ResultWrapper.Loading()

        _paymentFlow.value = wrapToResult {
            documentManager.getExtractions(document).toPaymentDetails()
        }
    }

    /**
     * Sets a [Document] for review. Results can be collected from [documentFlow] and [paymentFlow].
     *
     * @param documentId id of the document returned by Gini API.
     * @param paymentDetails optional [PaymentDetails] for the document corresponding to [documentId]
     */
    suspend fun setDocumentForReview(documentId: String, paymentDetails: PaymentDetails? = null) {
        capturedArguments = CapturedArguments.DocumentId(documentId, paymentDetails)
        _paymentFlow.value = ResultWrapper.Loading()
        _documentFlow.value = ResultWrapper.Loading()
        _documentFlow.value = wrapToResult {
            documentManager.getDocument(documentId)
        }
        if (paymentDetails != null) {
            _paymentFlow.value = ResultWrapper.Success(paymentDetails)
        } else {
            when (val documentResult = documentFlow.value) {
                is ResultWrapper.Success -> {
                    _paymentFlow.value = wrapToResult { documentManager.getExtractions(documentResult.value).toPaymentDetails() }
                }
                is ResultWrapper.Error -> {
                    _paymentFlow.value = ResultWrapper.Error(Throwable("Failed to get document"))
                }
            }
        }
    }

    /**
     * Checks the required conditions needed to finish the payment flow to avoid unnecessary document upload.
     * See [Requirement] for possible requirements.
     *
     * @return List of missing requirements. Empty list means all requirements are met.
     */
    fun checkRequirements(packageManager: PackageManager): List<Requirement> = internalCheckRequirements(packageManager)

    internal fun setOpenBankState(state: PaymentState) {
        _openBankState.value = state
    }

    internal suspend fun retryDocumentReview() {
        when (val arguments = capturedArguments) {
            is CapturedArguments.DocumentId -> setDocumentForReview(arguments.id, arguments.paymentDetails)
            is CapturedArguments.DocumentInstance -> setDocumentForReview(arguments.value)
            null -> { // Nothing
            }
        }
    }

    private sealed class CapturedArguments {
        class DocumentInstance(val value: Document) : CapturedArguments()
        class DocumentId(val id: String, val paymentDetails: PaymentDetails? = null) : CapturedArguments()
    }

    sealed class PaymentState {
        object NoAction : PaymentState()
        object Loading : PaymentState()
        class Success(val paymentRequest: PaymentRequest) : PaymentState()
        class Error(val throwable: Throwable) : PaymentState()
    }
}