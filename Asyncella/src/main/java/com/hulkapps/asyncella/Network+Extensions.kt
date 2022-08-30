@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.hulkapps.asyncella

import retrofit2.Call
import retrofit2.Response
import java.lang.RuntimeException

class NetworkExtensions {
    suspend fun <V> AsynchronousController.awaitSuccessful(call: Call<V>): V = this.await {
        val response = call.execute()
        if (response.isSuccessful) {
            response.body()
        } else {
            throw RetrofitHttpError(response)
        }
    }
}

class RetrofitHttpError(private val errorResponse: Response<*>): RuntimeException("${errorResponse.code()}")
