package piuk.blockchain.android.ui.kyc.invalidcountry

import com.blockchain.android.testutils.rxInit
import com.blockchain.nabu.datamanagers.NabuDataManager
import com.blockchain.nabu.models.responses.tokenresponse.NabuOfflineTokenResponse
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import piuk.blockchain.android.ui.kyc.countryselection.util.CountryDisplayModel

class KycInvalidCountryPresenterTest {

    private lateinit var subject: KycInvalidCountryPresenter
    private val nabuDataManager: NabuDataManager = mock()
    private val view: KycInvalidCountryView = mock()

    @get:Rule
    val rxSchedulers = rxInit {
        mainTrampoline()
        ioTrampoline()
    }

    @Before
    fun setUp() {
        subject = KycInvalidCountryPresenter(nabuDataManager)
        subject.initView(view)
    }

    @Test
    fun `on no thanks clicked request successful`() {
        // Arrange
        givenSuccessfulUserCreation()
        givenSuccessfulRecordCountryRequest()
        givenViewReturnsDisplayModel()
        // Act
        subject.onNoThanks()
        // Assert
        verify(nabuDataManager).recordCountrySelection(any(), any(), any(), eq(null), eq(false))
        verify(view).showProgressDialog()
        verify(view).dismissProgressDialog()
        verify(view).finishPage()
    }

    @Test
    fun `on notify me clicked request successful`() {
        // Arrange
        givenSuccessfulUserCreation()
        givenSuccessfulRecordCountryRequest()
        givenViewReturnsDisplayModel()
        // Act
        subject.onNotifyMe()
        // Assert
        verify(nabuDataManager).recordCountrySelection(any(), any(), any(), eq(null), eq(true))
        verify(view).showProgressDialog()
        verify(view).dismissProgressDialog()
        verify(view).finishPage()
    }

    @Test
    fun `on no thanks clicked request fails but exception swallowed`() {
        // Arrange
        givenSuccessfulUserCreation()
        whenever(nabuDataManager.recordCountrySelection(any(), any(), any(), eq(null), any()))
            .thenReturn(Completable.error { Throwable() })
        givenSuccessfulRecordCountryRequest()
        givenViewReturnsDisplayModel()
        // Act
        subject.onNoThanks()
        // Assert
        verify(view).showProgressDialog()
        verify(view).dismissProgressDialog()
        verify(view).finishPage()
    }

    private fun <T> any(type: Class<T>): T = Mockito.any<T>(type)

    private fun givenSuccessfulUserCreation() {
        val jwt = "JWT"
        whenever(nabuDataManager.requestJwt()).thenReturn(Single.just(jwt))
        val offlineToken = NabuOfflineTokenResponse("", "", false)
        whenever(nabuDataManager.getAuthToken(jwt))
            .thenReturn(Single.just(offlineToken))
    }

    private fun givenSuccessfulRecordCountryRequest() {
        whenever(nabuDataManager.recordCountrySelection(any(), any(), any(), eq(null), any()))
            .thenReturn(Completable.complete())
    }

    private fun givenViewReturnsDisplayModel() {
        whenever(view.displayModel).thenReturn(
            CountryDisplayModel(
                name = "Great Britain",
                countryCode = "GB",
                state = null,
                flag = null,
                isState = false
            )
        )
    }
}
