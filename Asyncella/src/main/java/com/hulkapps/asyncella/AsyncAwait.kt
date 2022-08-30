@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.hulkapps.asyncella

import android.app.Activity
import android.os.Looper
import android.os.Message
import java.lang.Exception
import java.lang.RuntimeException
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.coroutines.*

/**
 * Asyncella - A portable multiplatform easier to manage asynchronous alternative
 * 2022
 * [HulkApps] | [Praella Agency] - Admir Saheta
 */

// ** Executor and Coroutine values && Maps ** //
private val codeExecutor = WeakHashMap<Any, ExecutorService>()
private val coroutines = WeakHashMap<Any, ArrayList<WeakReference<AsynchronousController>>>()

// ** Typealiases ** //
typealias HandleErrors = (Exception) -> Unit
typealias HandleProgress<P> = (P) -> Unit


/** Run asynchronous code based on [asy] coroutine parameter
 *  Keep inside of the UI thread | For iOS always check main threads.
 *  Calling 'async' will start the [codeExecutors] automatically until
 *  the first point is reached [await] is triggered.
 *  Remaining parts are executed and is delivered into the UI thread
 *
 *  @param asy - a coroutine representing async computations
 *  @return AsynchronousController - an object allowing you to define optional error or final handlers
 *
 */

fun Any.async(asy: suspend AsynchronousController.() -> Unit): AsynchronousController {
    val controller = AsynchronousController(this)
    coroutineExpandedTimeLimit(controller)
    return async(asy, controller)
}

fun Activity.async(asy: suspend AsynchronousController.() -> Unit): AsynchronousController {
    val controller = AsynchronousController(this)
    coroutineExpandedTimeLimit(controller)
    return async(asy, controller)
}

internal fun async(asy: suspend AsynchronousController.() -> Unit, controller: AsynchronousController): AsynchronousController {
    asy.startCoroutine(controller, completion = object : Continuation<Unit> {
        override val context: CoroutineContext
            get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {

        }

    })
    return controller
}

/**
 *  [AsynchronousController] controls workers and handlers.
 */

class AsynchronousController(private val target: Any){

    private var handleErrors: HandleErrors? = null
    private var handleFinalResult: (() -> Unit)? = null

    private val userInterfaceHandler = object : android.os.Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (isActive()) {
                @Suppress("UNCHECKED_CAST")
                (msg.obj as () -> Unit)()
            }
        }
    }
    internal var actualTask: CancelableTask<*>? = null
    private lateinit var mainThreadStackTrace: Array<out StackTraceElement>

    suspend fun <V> await(finished: () -> V): V {
        keepAwaitCallerStackTrace()
        return suspendCoroutine {
            actualTask = AwaitTask(finished, this, it)
            target.getExecutorService().submit(actualTask)
        }
    }

    suspend fun <V, P> awaitWithProgress(
        finished: (HandleProgress<P>) -> V,
        progressActive: HandleProgress<P>
    ): V {
        keepAwaitCallerStackTrace()
        return suspendCoroutine {
            actualTask = AwaitWithProgressTask(finished, progressActive, this, it)
            target.getExecutorService().submit(actualTask)
        }
    }

    fun onError(handleErrors: HandleErrors): AsynchronousController {
        this.handleErrors = handleErrors
        return this
    }

    fun final(handleFinalResult: () -> Unit) {
        this.handleFinalResult = handleFinalResult
    }

    internal fun cancel() {
        actualTask?.cancel()
    }

    internal fun <V> handleException(originalException: Exception, continuation: Continuation<V>) {
        runOnMain {
            actualTask = null

            try {
                continuation.resumeWithException(originalException)
            } catch (e: Exception) {

            }
            applyFinalBlock()
        }
    }

    internal fun applyFinalBlock() {
        if (isLastCoroutineResumeExecuted()) {
            handleFinalResult?.invoke()
        }
    }

    private fun isLastCoroutineResumeExecuted() = actualTask == null
    private fun isActive(): Boolean {
        return when (target) {
            is Activity -> return !target.isFinishing
            else -> true
        }
    }
    internal fun runOnMain(block: () -> Unit) {
        userInterfaceHandler.obtainMessage(0, block).sendToTarget()
    }
    private fun keepAwaitCallerStackTrace() {
        mainThreadStackTrace = Thread.currentThread().stackTrace
    }
    private fun refineMainThreadStackTrace(): Array<out StackTraceElement> {
        return mainThreadStackTrace
            .dropWhile { it.methodName != "keepAwaitCallerStackTrace" }
            .drop(2)
            .toTypedArray()
    }

}

private fun Any.coroutineExpandedTimeLimit(controller: AsynchronousController) {
    var list = coroutines.getOrElse(this) {
        val newList = ArrayList<WeakReference<AsynchronousController>>()
        coroutines[this] = newList
        newList
    }
}

private fun Any.getExecutorService(): ExecutorService {
    val threadName = "AsyncAwait-${this::class.java.simpleName}"
    return codeExecutor.getOrElse(this) {
        val newCodeExecutor = Executors.newSingleThreadExecutor(AsynchronousThreadFactory(threadName))
        codeExecutor[this] = newCodeExecutor
        newCodeExecutor
    }
}

private class AsynchronousThreadFactory(val name: String): ThreadFactory {
    private var counter = 0
    override fun newThread(result: Runnable?): Thread {
        counter++
        return Thread(result, "$name-$counter")
    }
}

val Any.async: Asynchronous
    get() = Asynchronous(this)

class Asynchronous(private val asynchronousTarget: Any) {
    fun cancelAll() {
        coroutines[asynchronousTarget]?.forEach {
            it.get()?.cancel()
        }
    }
}

internal abstract class CancelableTask<V>(@Volatile var asynchronousController: AsynchronousController?, @Volatile var continuation: Continuation<V>?): Runnable {
    private val isCancelled = AtomicBoolean(false)
    open internal fun cancel() {
        isCancelled.set(true)
        asynchronousController = null
        continuation = null
    }

    override fun run() {
        if (isCancelled.get()) return

        try {
            val value = obtainValue()
            if(isCancelled.get()) return
            asynchronousController?.apply {
                runOnMain {
                    actualTask = null
                    continuation?.resume(value)
                    applyFinalBlock()
                }
            }
        } catch (e: Exception) {
            if (isCancelled.get()) return
            continuation?.apply {
                asynchronousController?.handleException(e, this)
            }
        }
    }
    abstract  fun obtainValue(): V
}

private class AwaitTask<V>(val finished: () -> V, asynchronousController: AsynchronousController, continuation: Continuation<V>): CancelableTask<V>(asynchronousController, continuation) {
    override fun obtainValue(): V {
        return finished()
    }
}

private class AwaitWithProgressTask<P, V>(val finished: (HandleProgress<P>) -> V, @Volatile var progressActive: HandleProgress<P>?, asynchronousController: AsynchronousController, continuation: Continuation<V>): CancelableTask<V>(asynchronousController, continuation) {
    override fun obtainValue(): V {
        return finished { valueProgress ->
            progressActive?.apply {
                asynchronousController?.runOnMain {
                    this(valueProgress)
                }
            }
        }

    }

    override fun cancel() {
        super.cancel()
        progressActive = null
    }
}

class AsyncException(e: Exception, stackTrace: Array<out StackTraceElement>): RuntimeException(e) {
    init {
        this.stackTrace = stackTrace
    }
}