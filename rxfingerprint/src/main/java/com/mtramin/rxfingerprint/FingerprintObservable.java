/*
 * Copyright 2015 Marvin Ramin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mtramin.rxfingerprint;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.CryptoObject;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationCallback;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationResult;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import com.mtramin.rxfingerprint.data.FingerprintAuthenticationException;
import com.mtramin.rxfingerprint.data.FingerprintUnavailableException;

import androidx.biometric.BiometricPrompt;
import androidx.core.os.CancellationSignal;
import io.reactivex.Emitter;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.functions.Cancellable;

import static android.Manifest.permission.USE_FINGERPRINT;

/**
 * Base observable for Fingerprint authentication. Provides abstract methods that allow
 * to alter the input and result of the authentication.
 */
@SuppressLint("NewApi") // SDK check happens in {@link FingerprintObservable#subscribe}
abstract class FingerprintObservable<T> implements ObservableOnSubscribe<T> {

	private final FingerprintApiWrapper fingerprintApiWrapper;
	CancellationSignal cancellationSignal;
	BiometricPrompt.PromptInfo promptInfo;

	/**
	 * Default constructor for fingerprint authentication
	 *
	 * @param context Context to be used for the fingerprint authentication
	 */
	FingerprintObservable(FingerprintApiWrapper fingerprintApiWrapper) {
		this.fingerprintApiWrapper = fingerprintApiWrapper;
	}


	@Override
	@RequiresPermission(USE_FINGERPRINT)
	@RequiresApi(Build.VERSION_CODES.M)
	public void subscribe(ObservableEmitter<T> emitter) throws Exception {
		if (fingerprintApiWrapper.isUnavailable()) {
			emitter.onError(new FingerprintUnavailableException("Fingerprint authentication is not available on this device! Ensure that the device has a Fingerprint sensor and enrolled Fingerprints by calling RxFingerprint#isAvailable(Context) first"));
			return;
		}

		AuthenticationCallback compactCallback = createAuthenticationCallbackCompat(emitter);
		BiometricPrompt.AuthenticationCallback biometricAuthCallback =
				createBiometricAuthenticationCallback(emitter);

		cancellationSignal = fingerprintApiWrapper.createCancellationSignal();

		BiometricPrompt.CryptoObject biometricCryptoObject = initBiometricCryptoObject(emitter);
		CryptoObject compactCryptoObj =initCryptoObject(emitter);

		final BiometricHelper biometricHelper =
				BiometricHelper.set(fingerprintApiWrapper.hasActivity()?
								biometricCryptoObject :compactCryptoObj
						 );

		final AuthenticationCallbackHelper authCallbackHelper =
				AuthenticationCallbackHelper.set(fingerprintApiWrapper.hasActivity()? biometricAuthCallback :
						compactCallback);

		fingerprintApiWrapper
				.authenticateForBiometric(biometricHelper, authCallbackHelper);

		emitter.setCancellable(new Cancellable() {
			@Override
			public void cancel() throws Exception {
				if (cancellationSignal != null && !cancellationSignal.isCanceled()) {
					cancellationSignal.cancel();
				}
			}
		});
	}




	private BiometricPrompt.AuthenticationCallback createBiometricAuthenticationCallback(final ObservableEmitter<T> emitter) {
		return new BiometricPrompt.AuthenticationCallback() {
			@Override
			public void onAuthenticationError(int errMsgId, CharSequence errString) {
				if (!emitter.isDisposed()) {
					emitter.onError(new FingerprintAuthenticationException(errString));
				}
			}

			@Override
			public void onAuthenticationFailed() {
				FingerprintObservable.this.onAuthenticationFailed(emitter);
			}

			@Override
			public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
				FingerprintObservable.this.onAuthenticationSucceeded(emitter, result);
			}
		};
	}

	private AuthenticationCallback createAuthenticationCallbackCompat(final ObservableEmitter<T> emitter) {
			return new AuthenticationCallback() {
				@Override
				public void onAuthenticationError(int errMsgId, CharSequence errString) {
					if (!emitter.isDisposed()) {
						emitter.onError(new FingerprintAuthenticationException(errString));
					}
				}

				@Override
				public void onAuthenticationFailed() {
					FingerprintObservable.this.onAuthenticationFailed(emitter);
				}

				@Override
				public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
					FingerprintObservable.this.onAuthenticationHelp(emitter, helpMsgId, helpString.toString());
				}

				@Override
				public void onAuthenticationSucceeded(AuthenticationResult result) {
					FingerprintObservable.this.onAuthenticationSucceeded(emitter, result);
				}
			};
		}

	/**
	 * Method to initialize the {@link CryptoObject}
	 * used for the fingerprint authentication.
	 *
	 * @param subscriber current subscriber
	 * @return a {@link CryptoObject}
	 * that is to be used in the authentication. May be {@code null}.
	 */
	@Nullable
	@Deprecated
	protected abstract CryptoObject initCryptoObject(ObservableEmitter<T> subscriber);


	@Nullable
	protected abstract BiometricPrompt.CryptoObject initBiometricCryptoObject(ObservableEmitter<T> subscriber);

	/**
	 * Action to execute when fingerprint authentication was successful.
	 * Should return the needed result via the given {@link Emitter}.
	 * <p/>
	 * Should call {@link Emitter#onComplete()}.
	 *
	 * @param emitter current subscriber
	 * @param result  result of the successful fingerprint authentication
	 *
	 * @
	 */

	@Deprecated
	protected abstract void onAuthenticationSucceeded(ObservableEmitter<T> emitter, AuthenticationResult result);

	protected abstract void onAuthenticationSucceeded(ObservableEmitter<T> emitter,
													  BiometricPrompt.AuthenticationResult result);

	/**
	 * Action to execute when the fingerprint authentication returned a help result.
	 * Should return the needed actions to the subscriber via the given {@link Emitter}.
	 * <p/>
	 * Should <b>not</b> {@link Emitter#onComplete()}.
	 *
	 * @param emitter       current subscriber
	 * @param helpMessageId ID of the help message returned from the {@link FingerprintManagerCompat}
	 * @param helpString    Help message string returned by the {@link FingerprintManagerCompat}
	 */
	protected abstract void onAuthenticationHelp(ObservableEmitter<T> emitter, int helpMessageId, String helpString);

	/**
	 * Action to execute when the fingerprint authentication failed.
	 * Should return the needed action to the given {@link Emitter}.
	 * <p/>
	 * Should only call {@link Emitter#onComplete()} when fingerprint authentication should be
	 * canceled due to the failed event.
	 *
	 * @param emitter current subscriber
	 */
	protected abstract void onAuthenticationFailed(ObservableEmitter<T> emitter);
}
