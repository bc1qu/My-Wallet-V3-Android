package piuk.blockchain.android.ui.kyc.settings

import androidx.annotation.VisibleForTesting
import com.blockchain.core.kyc.domain.KycService
import com.blockchain.core.kyc.domain.model.KycTiers
import com.blockchain.domain.eligibility.EligibilityService
import com.blockchain.domain.eligibility.model.GetRegionScope
import com.blockchain.nabu.NabuToken
import com.blockchain.nabu.api.getuser.domain.UserService
import com.blockchain.nabu.models.responses.nabu.KycState
import com.blockchain.nabu.models.responses.nabu.UserState
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.Singles
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.rx3.asCoroutineDispatcher
import piuk.blockchain.androidcore.data.settings.SettingsDataManager
import piuk.blockchain.androidcore.utils.extensions.rxSingleOutcome
import timber.log.Timber

class KycStatusHelper(
    private val eligibilityService: EligibilityService,
    private val userService: UserService,
    private val nabuToken: NabuToken,
    private val settingsDataManager: SettingsDataManager,
    private val kycService: KycService
) {

    private val fetchOfflineToken
        get() = nabuToken.fetchNabuToken()

    fun getSettingsKycStateTier(): Single<KycTiers> =
        shouldDisplayKyc().flatMap {
            if (it) {
                getKycTierStatus()
            } else {
                Single.just(KycTiers.default())
            }
        }.onErrorReturn {
            KycTiers.default()
        }

    fun shouldDisplayKyc(): Single<Boolean> = Singles.zip(
        isInKycRegion(), hasAccount()
    ) { allowedRegion, hasAccount -> allowedRegion || hasAccount }

    fun getKycStatus(): Single<KycState> =
        userService.getUser()
            .subscribeOn(Schedulers.io())
            .map { it.kycState }
            .doOnError { Timber.e(it) }
            .onErrorReturn { KycState.None }

    fun getKycTierStatus(): Single<KycTiers> =
        kycService.getTiersLegacy()
            .onErrorReturn { KycTiers.default() }
            .doOnError { Timber.e(it) }

    fun getUserState(): Single<UserState> =
        userService.getUser()
            .subscribeOn(Schedulers.io())
            .map { it.state }
            .doOnError { Timber.e(it) }
            .onErrorReturn { UserState.None }

    @VisibleForTesting
    internal fun hasAccount(): Single<Boolean> = fetchOfflineToken
        .map { true }
        .onErrorReturn { false }

    @VisibleForTesting
    internal fun isInKycRegion(): Single<Boolean> =
        settingsDataManager.getSettings()
            .subscribeOn(Schedulers.io())
            .map { it.countryCode }
            .flatMapSingle { isInKycRegion(it) }
            .singleOrError()

    private fun isInKycRegion(countryCode: String?): Single<Boolean> =
        rxSingleOutcome(Schedulers.io().asCoroutineDispatcher()) {
            eligibilityService.getCountriesList(GetRegionScope.Kyc)
        }.subscribeOn(Schedulers.io())
            .map { countries ->
                countries.asSequence()
                    .map { it.countryCode }
                    .contains(countryCode)
            }
}
