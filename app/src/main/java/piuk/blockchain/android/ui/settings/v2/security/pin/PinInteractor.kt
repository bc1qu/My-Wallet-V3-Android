package piuk.blockchain.android.ui.settings.v2.security.pin

import androidx.annotation.VisibleForTesting
import com.blockchain.featureflag.FeatureFlag
import com.blockchain.nabu.datamanagers.ApiStatus
import com.blockchain.preferences.AuthPrefs
import com.blockchain.preferences.WalletStatusPrefs
import com.blockchain.wallet.DefaultLabels
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.tasks.Task
import info.blockchain.wallet.api.data.UpdateType
import io.intercom.android.sdk.Intercom
import io.intercom.android.sdk.identity.Registration
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.zipWith
import piuk.blockchain.android.data.biometrics.BiometricsController
import piuk.blockchain.android.ui.auth.MobileNoticeDialog
import piuk.blockchain.android.ui.auth.MobileNoticeRemoteConfig
import piuk.blockchain.android.ui.home.CredentialsWiper
import piuk.blockchain.androidcore.data.access.PinRepository
import piuk.blockchain.androidcore.data.auth.AuthDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.data.walletoptions.WalletOptionsDataManager
import piuk.blockchain.androidcore.utils.SessionPrefs
import piuk.blockchain.androidcore.utils.extensions.then

class PinInteractor internal constructor(
    private val apiStatus: ApiStatus,
    private val sessionPrefs: SessionPrefs,
    private val authDataManager: AuthDataManager,
    private val payloadManager: PayloadDataManager,
    private val pinRepository: PinRepository,
    private val biometricsController: BiometricsController,
    private val mobileNoticeRemoteConfig: MobileNoticeRemoteConfig,
    private val credentialsWiper: CredentialsWiper,
    private val walletOptionsDataManager: WalletOptionsDataManager,
    private val authPrefs: AuthPrefs,
    private val defaultLabels: DefaultLabels,
    private val walletStatusPrefs: WalletStatusPrefs,
    private val isIntercomEnabledFlag: FeatureFlag
) {

    fun shouldShowFingerprintLogin(): Boolean {
        return biometricsController.isBiometricUnlockEnabled && (!isCreatingNewPin() || !isConfirmingPin())
    }

    fun disableBiometrics() {
        biometricsController.setBiometricUnlockDisabled()
    }

    fun isCreatingNewPin(): Boolean = authPrefs.pinId.isEmpty()

    fun isConfirmingPin(): Boolean = isCreatingNewPin() && pinRepository.pin.isNotEmpty()

    fun updatePayload(password: String): Completable =
        payloadManager.initializeAndDecrypt(
            authPrefs.sharedKey,
            authPrefs.walletGuid,
            password
        ).then { verifyCloudBackup() }

    fun checkApiStatus(): Single<Boolean> = apiStatus.isHealthy()

    fun hasExceededPinAttempts(): Boolean = authPrefs.pinFails >= LOCAL_MAX_ATTEMPTS

    fun getTempPassword(): String? = payloadManager.tempPassword

    fun incrementFailureCount() {
        var fails = authPrefs.pinFails
        authPrefs.pinFails = ++fails
    }

    fun clearPin() {
        authPrefs.removePinID()
        authPrefs.pinId = ""
        pinRepository.clearPin()
    }

    fun resetPinFailureCount() {
        authPrefs.pinFails = 0
    }

    fun clearPrefs() {
        sessionPrefs.clear()
    }

    fun getCurrentPin(): String = pinRepository.pin

    fun fetchInfoMessage(): Single<MobileNoticeDialog> =
        mobileNoticeRemoteConfig.mobileNoticeDialog()

    fun createPin(tempPassword: String, pin: String): Completable =
        authDataManager.createPin(tempPassword, pin)
            .then { verifyCloudBackup() }

    fun checkForceUpgradeStatus(versionName: String): Observable<UpdateType> {
        return walletOptionsDataManager.checkForceUpgrade(versionName)
    }

    fun validatePIN(pin: String, isForValidatingPinForResult: Boolean = false): Single<String> =
        authDataManager.validatePin(pin)
            .firstOrError()
            .zipWith(isIntercomEnabledFlag.enabled)
            .flatMap { (validatedPin, isIntercomEnabled) ->
                if (isIntercomEnabled) {
                    registerIntercomUser()
                }

                if (isForValidatingPinForResult) {
                    authDataManager.verifyCloudBackup().toSingle { validatedPin }
                } else {
                    Single.just(validatedPin)
                }
            }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun registerIntercomUser() {
        val registration = Registration.create().withUserId(authPrefs.walletGuid)
        Intercom.client().registerIdentifiedUser(registration)
    }

    private fun verifyCloudBackup(): Completable =
        authDataManager.verifyCloudBackup()

    fun resetApp() {
        credentialsWiper.wipe()
    }

    fun validatePassword(password: String): Completable {
        return payloadManager.initializeAndDecrypt(
            authPrefs.sharedKey,
            authPrefs.walletGuid,
            password
        )
    }

    fun updateShareKeyInPrefs() {
        authPrefs.sharedKey = payloadManager.wallet?.sharedKey.orEmpty()
    }

    fun isWalletUpgradeRequired(): Boolean = payloadManager.isWalletUpgradeRequired

    fun updateInfo(appUpdateManager: AppUpdateManager): Observable<Task<AppUpdateInfo>> {
        return Observable.fromCallable { appUpdateManager.appUpdateInfo }
    }

    fun doUpgradeWallet(secondPassword: String?): Completable {
        return payloadManager.upgradeWalletPayload(
            secondPassword,
            defaultLabels.getDefaultNonCustodialWalletLabel()
        )
    }

    companion object {
        private const val LOCAL_MAX_ATTEMPTS: Long = 4
    }
}
