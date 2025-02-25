package piuk.blockchain.android.ui.kyc.reentry

import com.blockchain.domain.dataremediation.DataRemediationService
import com.blockchain.domain.dataremediation.model.QuestionnaireContext
import com.blockchain.featureflag.FeatureFlag
import com.blockchain.nabu.models.responses.nabu.NabuUser
import io.reactivex.rxjava3.core.Single
import piuk.blockchain.androidcore.utils.extensions.rxMaybeOutcome

class TiersReentryDecision(
    private val dataRemediationService: DataRemediationService,
    private val loqateFeatureFlag: FeatureFlag,
) : ReentryDecision {

    private lateinit var nabuUser: NabuUser
    private val isTierZero: Boolean by lazy {
        nabuUser.tiers?.current == 0
    }

    override fun findReentryPoint(user: NabuUser): Single<ReentryPoint> {
        nabuUser = user
        val entryPoint = when {
            tier0UnverifiedEmail() -> ReentryPoint.EmailEntry
            tier0UnselectedCountry() -> ReentryPoint.CountrySelection
            tier0ProfileIncompleteOrResubmitAllowed() &&
                !tier0UnselectedCountry() -> ReentryPoint.Profile
            tier0AndCanAdvance() && tier0MissingAddress() -> ReentryPoint.Address
            !hasMobileVerified() -> ReentryPoint.MobileEntry
            else -> return rxMaybeOutcome {
                dataRemediationService.getQuestionnaire(QuestionnaireContext.TIER_TWO_VERIFICATION)
            }.map { questionnaire ->
                ReentryPoint.Questionnaire(questionnaire) as ReentryPoint
            }.defaultIfEmpty(ReentryPoint.Veriff)
        }

        if (entryPoint == ReentryPoint.Address) {
            return loqateFeatureFlag.enabled.map { enabled ->
                if (enabled) ReentryPoint.Address else ReentryPoint.OldAddress
            }
        }

        return Single.just(entryPoint)
    }

    private fun tier0UnverifiedEmail(): Boolean = isTierZero && !nabuUser.emailVerified

    private fun tier0UnselectedCountry(): Boolean = isTierZero && nabuUser.address?.countryCode.isNullOrBlank()

    private fun tier0ProfileIncompleteOrResubmitAllowed(): Boolean {
        return isTierZero &&
            (
                nabuUser.isProfileIncomplete() ||
                    nabuUser.isMarkedForRecoveryResubmission
                )
    }

    private fun tier0AndCanAdvance() = isTierZero && nabuUser.tiers!!.next == 1

    private fun tier0MissingAddress() =
        isTierZero &&
            nabuUser.address?.line1.isNullOrEmpty() &&
            nabuUser.address?.line2.isNullOrEmpty() &&
            nabuUser.address?.city.isNullOrEmpty() &&
            nabuUser.address?.postCode.isNullOrEmpty()

    private fun hasMobileVerified() = nabuUser.mobileVerified
}

private fun NabuUser.isProfileIncomplete() =
    firstName.isNullOrBlank() ||
        lastName.isNullOrBlank() ||
        dob.isNullOrBlank()
