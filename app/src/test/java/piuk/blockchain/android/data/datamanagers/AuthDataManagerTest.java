package piuk.blockchain.android.data.datamanagers;

import info.blockchain.wallet.payload.data.Wallet;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;
import okhttp3.ResponseBody;
import piuk.blockchain.android.RxTest;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.services.WalletService;
import piuk.blockchain.android.ui.transactions.PayloadDataManager;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.PrefsUtil;
import piuk.blockchain.android.util.StringUtils;
import retrofit2.Response;

import static junit.framework.TestCase.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by adambennett on 15/08/2016.
 */
public class AuthDataManagerTest extends RxTest {

    private static final String ERROR_BODY = "{\n" +
            "\t\"message\": \"Authorization Required\"\n" +
            "}";

    @Mock private PayloadDataManager payloadDataManager;
    @Mock private PrefsUtil prefsUtil;
    @Mock private WalletService walletService;
    @Mock private AppUtil appUtil;
    @Mock private AccessState accessState;
    @Mock private StringUtils stringUtils;
    @InjectMocks AuthDataManager subject;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void getEncryptedPayload() throws Exception {
        // Arrange
        ResponseBody mockResponseBody = mock(ResponseBody.class);
        when(walletService.getEncryptedPayload(anyString(), anyString()))
                .thenReturn(Observable.just(Response.success(mockResponseBody)));
        // Act
        TestObserver<Response<ResponseBody>> observer = subject.getEncryptedPayload("1234567890", "1234567890").test();
        // Assert
        verify(walletService).getEncryptedPayload(anyString(), anyString());
        observer.assertComplete();
        observer.assertNoErrors();
        assertTrue(observer.values().get(0).isSuccessful());
    }

    @Test
    public void getSessionId() throws Exception {
        // Arrange
        String sessionId = "SESSION_ID";
        when(walletService.getSessionId(anyString())).thenReturn(Observable.just(sessionId));
        // Act
        TestObserver<String> testObserver = subject.getSessionId("1234567890").test();
        // Assert
        verify(walletService).getSessionId(anyString());
        testObserver.assertComplete();
        testObserver.onNext(sessionId);
        testObserver.assertNoErrors();
    }

    @Test
    public void updatePayload() throws Exception {
        // Arrange
        String sharedKey = "SHARED_KEY";
        String guid = "GUID";
        String password = "PASSWORD";
        when(payloadDataManager.initializeAndDecrypt(sharedKey, guid, password)).thenReturn(Completable.complete());
        // Act
        TestObserver<Void> observer = subject.updatePayload(sharedKey, guid, password).test();
        // Assert
        verify(payloadDataManager).initializeAndDecrypt(sharedKey, guid, password);
        observer.assertComplete();
        observer.assertNoErrors();
    }

    @Test
    public void validatePin() throws Exception {
        // Arrange
        String decryptedPassword = "1234567890";
        when(accessState.validatePin(anyString())).thenReturn(Observable.just(decryptedPassword));
        // Act
        TestObserver<String> observer = subject.validatePin(anyString()).test();
        // Assert
        verify(accessState).validatePin(anyString());
        observer.assertComplete();
        observer.onNext(decryptedPassword);
        observer.assertNoErrors();
    }

    @Test
    public void createPin() throws Exception {
        // Arrange
        when(accessState.createPin(any(String.class), anyString())).thenReturn(Observable.just(true));
        // Act
        TestObserver<Boolean> observer = subject.createPin("", "").test();
        // Assert
        verify(accessState).createPin(any(String.class), anyString());
        observer.assertComplete();
        observer.assertValue(true);
        observer.assertNoErrors();
    }

    @Test
    public void createHdWallet() throws Exception {
        // Arrange
        Wallet payload = new Wallet();
        payload.setSharedKey("shared key");
        payload.setGuid("guid");
        when(payloadDataManager.createHdWallet(anyString(), anyString(), anyString()))
                .thenReturn(Observable.just(payload));
        // Act
        TestObserver<Wallet> observer = subject.createHdWallet("", "", "").test();
        // Assert
        verify(payloadDataManager).createHdWallet(anyString(), anyString(), anyString());
        verify(appUtil).setSharedKey("shared key");
        verify(appUtil).setNewlyCreated(true);
        verify(prefsUtil).setValue(PrefsUtil.KEY_GUID, "guid");
        observer.assertComplete();
        observer.onNext(payload);
        observer.assertNoErrors();
    }

    @Test
    public void restoreHdWallet() throws Exception {
        // Arrange
        Wallet payload = new Wallet();
        payload.setSharedKey("shared key");
        payload.setGuid("guid");
        when(payloadDataManager.restoreHdWallet(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Observable.just(payload));
        when(stringUtils.getString(anyInt())).thenReturn("string resource");
        // Act
        TestObserver<Wallet> observer = subject.restoreHdWallet("", "", "").test();
        // Assert
        verify(payloadDataManager).restoreHdWallet(anyString(), anyString(), anyString(), anyString());
        verify(appUtil).setSharedKey("shared key");
        verify(appUtil).setNewlyCreated(true);
        verify(prefsUtil).setValue(PrefsUtil.KEY_GUID, "guid");
        observer.assertComplete();
        observer.onNext(payload);
        observer.assertNoErrors();
    }

    @Test
    public void initializeFromPayload() throws Exception {
        // Arrange
        String payload = "PAYLOAD";
        String password = "PASSWORD";
        String guid = "GUID";
        String sharedKey = "SHARED_KEY";
        Wallet mockWallet = mock(Wallet.class);
        when(mockWallet.getGuid()).thenReturn(guid);
        when(mockWallet.getSharedKey()).thenReturn(sharedKey);
        when(payloadDataManager.getWallet()).thenReturn(mockWallet);
        when(payloadDataManager.initializeFromPayload(payload, password)).thenReturn(Completable.complete());
        // Act
        TestObserver<Void> testObserver = subject.initializeFromPayload(payload, password).test();
        // Assert
        verify(payloadDataManager).initializeFromPayload(payload, password);
        verify(payloadDataManager, times(2)).getWallet();
        verify(prefsUtil).setValue(PrefsUtil.KEY_GUID, guid);
        verify(prefsUtil).setValue(PrefsUtil.KEY_EMAIL_VERIFIED, true);
        verify(appUtil).setSharedKey(sharedKey);
        testObserver.assertComplete();
        testObserver.assertNoErrors();
    }
}