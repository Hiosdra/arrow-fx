package arrow.fx.internal

import arrow.core.nonFatalOrThrow
import arrow.fx.IO
import arrow.fx.IOConnection
import arrow.fx.IOFrame
import arrow.fx.IOOf
import arrow.fx.IOResult
import arrow.fx.IORunLoop
import arrow.fx.fix
import arrow.fx.handleErrorWith
import arrow.fx.typeclasses.ExitCase2
import kotlinx.atomicfu.atomic

internal object IOBracket {

  /**
   * Implementation for `IO.bracketCase`.
   */
  operator fun <E, A, B> invoke(acquire: IOOf<E, A>, release: (A, ExitCase2<E>) -> IOOf<Nothing, Unit>, use: (A) -> IOOf<E, B>): IO<E, B> =
    IO.Async { conn, cb ->
      // Placeholder for the future finalizer
      val deferredRelease = ForwardCancellable()
      conn.push(deferredRelease.cancel())

      // Race-condition check, avoiding starting the bracket if the connection
      // was cancelled already, to ensure that `cancel` really blocks if we
      // start `acquire` — n.b. `isCancelled` is visible here due to `push`
      if (!conn.isCancelled()) {
        // Note `acquire` is uncancellable due to usage of `IORunLoop.start`
        // (in other words it is disconnected from our IOConnection)
        IORunLoop.start(acquire, BracketStart(use, release, conn, deferredRelease, cb))
      } else {
        deferredRelease.complete(IO.unit)
      }
    }

  // Internals of `IO.bracketCase`.
  private class BracketStart<E, A, B>(
    val use: (A) -> IOOf<E, B>,
    val release: (A, ExitCase2<E>) -> IOOf<Nothing, Unit>,
    val conn: IOConnection,
    val deferredRelease: ForwardCancellable,
    val cb: (IOResult<E, B>) -> Unit
  ) : (IOResult<E, A>) -> Unit {
    override fun invoke(ea: IOResult<E, A>) {
      // Introducing a light async boundary, otherwise executing the required logic directly will yield a StackOverflowException
      Platform.trampoline {
        when (ea) {
          is IOResult.Success -> {
            val a = ea.value
            val frame = BracketReleaseFrame<E, A, B>(a, release)
            val onNext = {
              val fb = try {
                use(a)
              } catch (e: Throwable) {
                IO.raiseException<B>(e.nonFatalOrThrow())
              }

              IO.Bind(fb, frame)
            }

            // Registering our cancellable token ensures that in case cancellation is detected, release gets called
            deferredRelease.complete(frame.cancel)
            IORunLoop.startCancellable(onNext(), conn, cb)
          }
          is IOResult.Error -> cb(ea)
          is IOResult.Exception -> cb(ea)
        }
      }
    }
  }

  fun <E, A> guaranteeCase(source: IOOf<E, A>, release: (ExitCase2<E>) -> IOOf<Nothing, Unit>): IO<E, A> =
    IO.Async { conn, cb ->
      Platform.trampoline {
        val frame = EnsureReleaseFrame<E, A>(release)
        val onNext = IO.Bind(source, frame)
        // Registering our cancellable token ensures that in case
        // cancellation is detected, `release` gets called
        conn.push(frame.cancel)

        // Race condition check, avoiding starting `source` in case
        // the connection was already cancelled — n.b. we don't need
        // to trigger `release` otherwise, because it already happened
        if (!conn.isCancelled()) IORunLoop.startCancellable(onNext, conn, cb)
      }
    }

  private class BracketReleaseFrame<E, A, B>(val a: A, val releaseFn: (A, ExitCase2<E>) -> IOOf<Nothing, Unit>) :
    BaseReleaseFrame<E, A, B>() {

    override fun release(c: ExitCase2<E>): IOOf<Nothing, Unit> =
      releaseFn(a, c)
  }

  private class EnsureReleaseFrame<E, A>(val releaseFn: (ExitCase2<E>) -> IOOf<Nothing, Unit>) : BaseReleaseFrame<E, Unit, A>() {

    override fun release(c: ExitCase2<E>): IOOf<Nothing, Unit> = releaseFn(c)
  }

  private abstract class BaseReleaseFrame<E, A, B> : IOFrame<E, B, IO<E, B>> {

    // Guard used for thread-safety, to ensure the idempotency
    // of the release; otherwise `release` can be called twice
    private val waitsForResult = atomic(true)

    abstract fun release(c: ExitCase2<E>): IOOf<Nothing, Unit>

    private fun applyRelease(e: ExitCase2<E>): IO<Nothing, Unit> =
      IO.defer {
        if (waitsForResult.compareAndSet(true, false)) release(e)
        else IO.unit
      }

    val cancel: IOOf<Nothing, Unit> = applyRelease(ExitCase2.Cancelled).fix().uncancellable()

    // Unregistering cancel token, otherwise we can have a memory leak;
    // N.B. conn.pop() happens after the evaluation of `release`, because
    // otherwise we might have a conflict with the auto-cancellation logic
    override fun recover(e: Throwable): IO<E, B> =
      IO.Bind(IO.ContextSwitch(applyRelease(ExitCase2.Exception(e)), IO.ContextSwitch.makeUncancellable, disableUncancellableAndPop),
        ReleaseRecoverException(e))

    override operator fun invoke(a: B): IO<E, B> =
    // Unregistering cancel token, otherwise we can have a memory leak
    // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      IO.ContextSwitch(applyRelease(ExitCase2.Completed), IO.ContextSwitch.makeUncancellable, disableUncancellableAndPop)
        .map { a }

    override fun handleError(e: E): IO<E, B> =
      IO.Bind(IO.ContextSwitch(applyRelease(ExitCase2.Error(e)), IO.ContextSwitch.makeUncancellable, disableUncancellableAndPop),
        ReleaseRecoverError(e))
  }

  private class ReleaseRecoverException(val error: Throwable) : IOFrame<Any?, Unit, IO<Nothing, Nothing>> {

    override fun recover(e: Throwable): IO<Nothing, Nothing> =
      IO.raiseException(Platform.composeErrors(error, e))

    override fun invoke(a: Unit): IO<Nothing, Nothing> = IO.raiseException(error)

    override fun handleError(e: Any?): IO<Nothing, Nothing> = IO.raiseException(error)
  }

  private class ReleaseRecoverError<E>(val error: E) : IOFrame<E, Unit, IO<E, Nothing>> {

    override fun handleError(e: E): IO<E, Nothing> =
      IO.RaiseError(error)

    override fun recover(e: Throwable): IO<E, Nothing> =
      IO.RaiseError(error)

    override fun invoke(a: Unit): IO<E, Nothing> =
      IO.RaiseError(error)
  }

  private val disableUncancellableAndPop: (Any?, Any?, Throwable?, IOConnection, IOConnection) -> IOConnection =
    { _, _, _, old, _ ->
      old.pop()
      old
    }
}
