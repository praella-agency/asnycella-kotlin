@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.hulkapps.asyncella

import java.util.*
import rx.Observable

/**
 * Adds the option for the process to be stored in the background
 * thread before observable emits it's value
 */

suspend fun <V> AsynchronousController.await(observable: Observable<V>): V = this.await {
    observable.toBlocking().first()
}