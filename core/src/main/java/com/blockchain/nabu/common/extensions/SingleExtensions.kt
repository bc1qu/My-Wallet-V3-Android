package com.blockchain.nabu.common.extensions

import com.blockchain.api.NabuApiExceptionFactory
import com.blockchain.core.BuildConfig
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import retrofit2.HttpException
import timber.log.Timber

internal inline fun <reified T> Flow<T>.wrapErrorMessage(): Flow<T> = this.catch {
    if (BuildConfig.DEBUG) {
        Timber.e("RX Wrapped Error: {${it.message} --- ${T::class.simpleName}")
    }
    when (it) {
        is HttpException -> throw NabuApiExceptionFactory.fromResponseBody(it)
        else -> throw it
    }
}

internal inline fun <reified T> Observable<T>.wrapErrorMessage(): Observable<T> = this.onErrorResumeNext {
    if (BuildConfig.DEBUG) {
        Timber.e("RX Wrapped Error: {${it.message} --- ${T::class.simpleName}")
    }
    when (it) {
        is HttpException -> Observable.error(NabuApiExceptionFactory.fromResponseBody(it))
        else -> Observable.error(it)
    }
}

internal inline fun <reified T> Single<T>.wrapErrorMessage(): Single<T> = this.onErrorResumeNext {
    if (BuildConfig.DEBUG) {
        Timber.e("RX Wrapped Error: {${it.message} --- ${T::class.simpleName}")
    }
    when (it) {
        is HttpException -> Single.error(NabuApiExceptionFactory.fromResponseBody(it))
        else -> Single.error(it)
    }
}

internal fun Completable.wrapErrorMessage(): Completable = this.onErrorResumeNext {
    if (BuildConfig.DEBUG) {
        Timber.e("RX Wrapped Error: {${it.message}")
    }

    when (it) {
        is HttpException -> Completable.error(NabuApiExceptionFactory.fromResponseBody(it))
        else -> Completable.error(it)
    }
}

internal inline fun <reified T> Maybe<T>.wrapErrorMessage(): Maybe<T> = this.onErrorResumeNext {
    if (BuildConfig.DEBUG) {
        Timber.e("RX Wrapped Error: {${it.message} --- ${T::class.simpleName}")
    }

    when (it) {
        is HttpException -> Maybe.error(NabuApiExceptionFactory.fromResponseBody(it))
        else -> Maybe.error(it)
    }
}
