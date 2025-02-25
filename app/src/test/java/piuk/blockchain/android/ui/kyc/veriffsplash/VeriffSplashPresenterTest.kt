package piuk.blockchain.android.ui.kyc.veriffsplash

import com.blockchain.analytics.Analytics
import com.blockchain.analytics.AnalyticsEvent
import com.blockchain.android.testutils.rxInit
import com.blockchain.api.NabuApiExceptionFactory
import com.blockchain.core.kyc.data.datasources.KycTiersStore
import com.blockchain.nabu.NabuToken
import com.blockchain.nabu.datamanagers.NabuDataManager
import com.blockchain.nabu.models.responses.nabu.SupportedDocuments
import com.blockchain.nabu.models.responses.tokenresponse.NabuOfflineToken
import com.blockchain.veriff.VeriffApplicantAndToken
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import junit.framework.Assert.assertEquals
import okhttp3.ResponseBody
import org.junit.Rule
import org.junit.Test
import piuk.blockchain.android.ui.kyc.navhost.models.UiState
import piuk.blockchain.androidcore.utils.SessionPrefs
import retrofit2.HttpException
import retrofit2.Response

class VeriffSplashPresenterTest {

    private val nabuToken: NabuToken = mock()
    private val nabuDataManager: NabuDataManager = mock()
    private val kycTiersStore: KycTiersStore = mock()
    private val view: VeriffSplashView = mock()
    private val analytics: Analytics = mock()
    private val prefs: SessionPrefs = mock()

    private val subject = VeriffSplashPresenter(
        nabuToken = nabuToken,
        nabuDataManager = nabuDataManager,
        kycTiersStore = kycTiersStore,
        analytics = analytics,
        prefs = prefs
    )

    @Suppress("unused")
    @get:Rule
    val initSchedulers = rxInit {
        mainTrampoline()
        ioTrampoline()
    }

    @Test
    fun onViewReady_happyPath_displayDocsView() {
        // Arrange
        setupFetchNabuToken_ok()
        setupFetchRequiredDocs_ok()
        setupFetchApplicantToken_ok()

        setupUiButtonStubs_unclicked()

        subject.initView(view)

        // Act
        subject.onViewReady()

        // Assert
        verify(view).setUiState(UiState.LOADING)

        argumentCaptor<List<SupportedDocuments>>().apply {
            verify(view).supportedDocuments(capture())
            assertEquals(firstValue, SUPPORTED_DOCS)
        }

        verify(view).setUiState(UiState.CONTENT)

        argumentCaptor<AnalyticsEvent>().apply {
            verify(analytics).logEvent(capture())

            assertEquals(allValues.size, 1)
            assertEquals(firstValue.params["result"], "START_KYC")
        }

        verify(view, never()).setUiState(UiState.FAILURE)
    }

    @Test
    fun onViewReady_pre_IDV_fail_displayUnavailableView() {
        // Arrange
        setupFetchNabuToken_ok()
        setupFetchRequiredDocs_ok()
        setupFetchApplicantToken_error_4xx()

        setupUiButtonStubs_unclicked()

        subject.initView(view)

        // Act
        subject.onViewReady()

        // Assert
        verify(view).setUiState(UiState.LOADING)

        argumentCaptor<List<SupportedDocuments>>().apply {
            verify(view).supportedDocuments(capture())

            assertEquals(allValues.size, 1)
            assertEquals(firstValue, SUPPORTED_DOCS)
        }

        verify(view).setUiState(UiState.FAILURE)
        verify(prefs).devicePreIDVCheckFailed = true

        argumentCaptor<AnalyticsEvent>().apply {
            verify(analytics).logEvent(capture())

            assertEquals(allValues.size, 1)
            assertEquals(firstValue.params["result"], "UNAVAILABLE")
        }

        verify(view, never()).setUiState(UiState.CONTENT)
    }

    // Setup:
    private fun setupFetchNabuToken_ok() {
        whenever(nabuToken.fetchNabuToken()).thenReturn(Single.just(TOKEN))
    }

    private fun setupFetchRequiredDocs_ok() {
        whenever(view.countryCode).thenReturn(COUNTRY_CODE)
        whenever(nabuDataManager.getSupportedDocuments(TOKEN, COUNTRY_CODE))
            .thenReturn(Single.just(SUPPORTED_DOCS))
    }

    private fun setupFetchApplicantToken_ok() {
        whenever(nabuDataManager.startVeriffSession(TOKEN))
            .thenReturn(Single.just(APPLICANT_TOKEN))
    }

    private fun setupFetchApplicantToken_error_4xx() {
        val body = Response.error<VeriffApplicantAndToken>(
            406,
            ResponseBody.create(
                null,
                "{\"message\":\"Totes Nope\"}"
            )
        )
        val httpError = NabuApiExceptionFactory.fromResponseBody(HttpException(body))

        whenever(nabuDataManager.startVeriffSession(TOKEN))
            .thenReturn(Single.error(httpError))
    }

    private fun setupUiButtonStubs_unclicked() {
        whenever(view.nextClick).thenReturn(Observable.never())
        whenever(view.swapClick).thenReturn(Observable.never())
    }

    companion object {
        private const val COUNTRY_CODE = "UK"
        private const val NABU_TOKEN = "TTTT_TOKEN_NNNN"

        private val TOKEN = NabuOfflineToken(
            userId = "userId",
            token = NABU_TOKEN
        )

        private val SUPPORTED_DOCS = listOf(
            SupportedDocuments.PASSPORT,
            SupportedDocuments.DRIVING_LICENCE
        )

        private val APPLICANT_TOKEN = VeriffApplicantAndToken(
            applicantId = "WHATEVER",
            token = "ANOTHER_TOKEN"
        )
    }
}
