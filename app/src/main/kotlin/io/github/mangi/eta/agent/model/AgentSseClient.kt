package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.buffer

/**
 * Blocking JSON/SSE collector backed by a single OkHttp call.
 *
 * Inspect a bounded prefix of that same response before choosing the protocol path.
 * EventSource still owns SSE framing, charset, backpressure and cancellation;
 * JSON is delivered once to the provider's terminal-response adapter.
 * Call [SseStream.finish] on terminal events
 * such as `[DONE]` so a keep-alive connection cannot hang the turn.
 */
internal object AgentSseClient {
    fun collect(
        request: Request,
        runController: AgentRunController,
        onOpen: (Int) -> Unit = {},
        onEvent: SseStream.(id: String?, type: String?, data: String) -> Unit,
        onJson: (String) -> Unit = { throw AgentModelFailure.unexpectedResponse(null, "application/json", "") },
        shouldIgnoreFailure: () -> Boolean = { false },
        inspectHttpErrorBody: (String) -> Unit = {},
        transportFactory: Call.Factory = AgentHttpClient.modelClient,
    ) {
        runController.throwIfCancelled()
        val timingId = java.util.UUID.randomUUID().toString().take(8)
        val timings = StreamArrivalStats(System.nanoTime())
        fun reportTimings(final: Boolean = false) {
            timings.report(System.nanoTime(), final)?.let {
                // Diagnostics must never fail or replace the actual network outcome.
                runCatching { io.github.mangi.eta.core.AndroidAgentLogger.info("SseDiag id=$timingId $it") }
            }
        }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val opened = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val eventSourceRef = AtomicReference<EventSource?>(null)
        val stream = SseStream(
            completed = completed,
            done = done,
            cancelSource = { eventSourceRef.get()?.cancel() },
        )
        val emitOpen = onOpen
        val emitEvent = onEvent
        fun httpFailure(response: Response): AgentModelFailure {
            val secrets = request.headers.names().filter {
                it.equals("Authorization", true) || it.contains("key", true) || it.contains("token", true) || it.equals("Cookie", true)
            }.flatMap { name -> request.headers.values(name) }.flatMap { value ->
                listOf(value, value.removePrefix("Bearer ").removePrefix("bearer "))
            }
            val body = runCatching { response.peekBody(64L * 1024).string() }.getOrDefault("")
            inspectHttpErrorBody(body)
            return AgentModelFailure.http(
                status = response.code,
                body = body,
                headers = response.headers,
                secrets = secrets,
            )
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                opened.set(true)
                try {
                    runController.withTransportCallback { emitOpen(response.code) }
                    if (!response.isSuccessful) {
                        failure.compareAndSet(
                            null,
                            httpFailure(response),
                        )
                        stream.finish()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    stream.finish()
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (completed.get() || runController.hasPendingImmediateSteering || runController.isPaused) {
                    stream.finish()
                    return
                }
                try {
                    runController.withTransportCallback { runController.throwIfCancelled() }
                    if (runController.hasPendingImmediateSteering || runController.isPaused) {
                        stream.finish()
                        return
                    }
                    val arrivalNs = System.nanoTime()
                    timings.arrival(arrivalNs, data.length)
                    try {
                        runController.withTransportCallback { emitEvent(stream, id, type, data) }
                    } finally {
                        timings.callback(System.nanoTime() - arrivalNs)
                        reportTimings()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    stream.finish()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                completed.set(true)
                done.countDown()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                if (completed.get()) {
                    done.countDown()
                    return
                }
                if (failure.get() == null) {
                    when {
                        runController.isCancelled ->
                            failure.compareAndSet(null, AgentRunCancelledException())
                        runController.hasPendingImmediateSteering || runController.isPaused || runController.hasPausedInterrupt -> Unit
                        response != null && !response.isSuccessful -> {
                            if (!opened.get()) {
                                runCatching { runController.withTransportCallback { emitOpen(response.code) } }
                            }
                            failure.compareAndSet(
                                null,
                                httpFailure(response),
                            )
                        }
                        t != null &&
                            !shouldIgnoreFailure() ->
                            failure.compareAndSet(null, t)
                    }
                }
                completed.set(true)
                done.countDown()
            }
        }

        val sseRequest = if (request.header("Accept").isNullOrBlank()) {
            request.newBuilder().header("Accept", "text/event-stream").build()
        } else {
            request
        }
        // Adapt only this response, not all requests on modelClient. The delegated Call owns
        // cancellation even while sniffing/reading JSON; no generation request is replayed.
        val callFactory = object : Call.Factory {
            override fun newCall(request: Request): Call {
                val call = transportFactory.newCall(request)
                return object : Call by call {
                    override fun enqueue(responseCallback: Callback) {
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(call, e)

                            override fun onResponse(call: Call, response: Response) {
                                if (completed.get()) {
                                    response.close()
                                    return
                                }
                                // Preserve HTTP rejection handling before any format adaptation.
                                if (!response.isSuccessful) {
                                    responseCallback.onResponse(call, response)
                                    return
                                }
                                try {
                                    val body = response.body ?: throw AgentModelFailure.unexpectedResponse(
                                        response.code, response.header("Content-Type"), "",
                                    )
                                    val source = body.source()
                                    val inspection = AgentResponseFormat.inspect(source, response.header("Content-Type"))
                                    if (completed.get()) {
                                        response.close()
                                        return
                                    }
                                    when (inspection.kind) {
                                        AgentResponseFormat.Kind.SSE -> {
                                            source.skip(inspection.preambleBytes)
                                            val sseSource = SseLineEndingSource(source).buffer()
                                            val mediaType = "text/event-stream".toMediaType()
                                            responseCallback.onResponse(call, response.newBuilder()
                                                .header("Content-Type", mediaType.toString())
                                                .body(sseSource.asResponseBody(mediaType, -1L))
                                                .build())
                                        }
                                        AgentResponseFormat.Kind.JSON -> response.use {
                                            opened.set(true)
                                            // Callback failures (including IOException) are not network
                                            // disconnects and must never enter shouldIgnoreFailure.
                                            try {
                                                runController.withTransportCallback {
                                                    runController.throwIfCancelled()
                                                    emitOpen(response.code)
                                                }
                                            } catch (error: Throwable) {
                                                failure.compareAndSet(null, error)
                                                stream.finish()
                                                return@use
                                            }
                                            // Keep body reads outside the callback catches so transport
                                            // cancellation/pause handling still follows the network path.
                                            source.skip(inspection.preambleBytes)
                                            val json = body.string()
                                            try {
                                                if (!completed.get()) runController.withTransportCallback {
                                                    runController.throwIfCancelled()
                                                    onJson(json)
                                                }
                                            } catch (error: Throwable) {
                                                failure.compareAndSet(null, error)
                                                stream.finish()
                                                return@use
                                            }
                                            stream.finish()
                                        }
                                        AgentResponseFormat.Kind.UNKNOWN -> response.use {
                                            // Do not retain/log arbitrary successful-response bodies as diagnostics.
                                            throw AgentModelFailure.unexpectedResponse(
                                                response.code, response.header("Content-Type"), "",
                                            )
                                        }
                                    }
                                } catch (error: Throwable) {
                                    response.close()
                                    if (error is IOException) {
                                        responseCallback.onFailure(call, error)
                                    } else {
                                        failure.compareAndSet(null, error)
                                        stream.finish()
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        val eventSource = EventSources.createFactory(callFactory).newEventSource(sseRequest, listener)
        eventSourceRef.set(eventSource)
        if (completed.get()) eventSource.cancel()
        // finish() 先标记 completed 并唤醒 collect，再 cancel EventSource。
        // 若先 cancel，OkHttp 可能排完当前 body 才返回，追加指令就会等到整段输出结束。
        val binding = runController.register(interruptible = true) {
            stream.finish()
        }
        try {
            runController.throwIfCancelled()
            done.await()
            runController.throwIfCancelled()
            val recordedFailure = failure.get()
            // A received provider rejection is not a benign socket cancellation.
            if (recordedFailure is AgentModelFailure ||
                (!runController.hasPendingImmediateSteering && !runController.hasPausedInterrupt)) {
                recordedFailure?.let { throw it }
            }
        } finally {
            completed.set(true)
            binding.close()
            runCatching { eventSource.cancel() }
            reportTimings(final = true)
        }
    }

    internal class SseStream(
        private val completed: AtomicBoolean,
        private val done: CountDownLatch,
        private val cancelSource: () -> Unit,
    ) {
        fun finish() {
            if (!completed.compareAndSet(false, true)) return
            done.countDown()
            runCatching { cancelSource() }
        }
    }

}
