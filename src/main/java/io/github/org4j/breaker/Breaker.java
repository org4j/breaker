package io.github.org4j.breaker;

import io.github.org4j.error.GeneralException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A circuit breaker for one outside system: it opens after {@link BreakerConfig#failures()}
 * failures in a row, sheds calls while open, and after {@link BreakerConfig#probeAfter()} lets one
 * probe through to see whether the system is back.
 *
 * <ul>
 *   <li>{@link State#CLOSED CLOSED}: every call is admitted.
 *   <li>{@link State#OPEN OPEN}: calls are refused until the cooldown ends.
 *   <li>{@link State#HALF_OPEN HALF_OPEN}: one probe is in flight; other calls are refused. A
 *       success closes the breaker, a failure opens it again for a new cooldown, and a probe that
 *       never reports back is replaced after another {@code probeAfter}.
 * </ul>
 *
 * <p>Use {@link #execute} or {@link #run} around a call, or {@link #tryAcquire} and the {@code
 * record} methods to drive it by hand. Made by {@link BreakerConfig#create()}; thread-safe.
 *
 * {@snippet :
 * var breaker = BreakerConfig.of("sms", 5, Duration.ofSeconds(15)).create();
 * var messageId = breaker.execute(() -> smsClient.send(otp));
 * }
 */
public final class Breaker {

  /** Where a breaker is in its cycle. */
  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final BreakerConfig config;
  private final Clock clock;
  private final AtomicLong successfulCalls = new AtomicLong();
  private final AtomicLong failedCalls = new AtomicLong();
  private final AtomicLong rejectedCalls = new AtomicLong();

  private int failuresInARow;
  private @Nullable Instant openedAt;
  private boolean probing;

  Breaker(BreakerConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Asks to make a call. Admits every call while closed; once the cooldown has ended, admits one
   * probe and moves to {@code HALF_OPEN}. A refusal is counted in {@link #rejectedCalls()}.
   *
   * @return {@code true} if the call may go ahead; it must then be reported with {@link
   *     #recordSuccess()} or {@link #recordFailure()}
   */
  public synchronized boolean tryAcquire() {
    if (openedAt == null) {
      return true;
    }
    var now = clock.instant();
    if (!now.isBefore(openedAt.plus(config.probeAfter()))) {
      probing = true;
      openedAt = now;
      return true;
    }
    rejectedCalls.incrementAndGet();
    return false;
  }

  /** Reports that the system answered: the failure count is reset and the breaker closes. */
  public synchronized void recordSuccess() {
    failuresInARow = 0;
    openedAt = null;
    probing = false;
    successfulCalls.incrementAndGet();
  }

  /**
   * Reports that the system failed. The breaker opens on the {@code failures}-th failure in a row,
   * or on a failed probe, and the cooldown starts again.
   */
  public synchronized void recordFailure() {
    failuresInARow++;
    failedCalls.incrementAndGet();
    if (probing || failuresInARow >= config.failures()) {
      openedAt = clock.instant();
      probing = false;
    }
  }

  /**
   * Reports a call that threw: a {@linkplain #recordFailure() failure} when the configured
   * predicate blames the system, a {@linkplain #recordSuccess() success} otherwise (a 4xx answer
   * still means the system is up).
   *
   * @param error what the call threw
   */
  public void recordError(Throwable error) {
    recordError(error, config.failurePredicate());
  }

  /**
   * Runs an action through the breaker, with the configured failure predicate.
   *
   * @see #execute(Supplier, Predicate)
   */
  public <T> T execute(Supplier<T> action) {
    return execute(action, config.failurePredicate());
  }

  /**
   * Runs an action through the breaker: refuses it while open, otherwise runs it and records the
   * outcome. Whatever the action throws is rethrown unchanged.
   *
   * @param action the call to the outside system
   * @param isFailure which of the action's exceptions count as the system's failures, for this call
   * @return what the action returned
   * @throws GeneralException {@link BreakerExceptionStatus#BREAKER_OPEN} (503) when refused, with
   *     {@code Retry-After} set to the rest of the cooldown
   */
  public <T> T execute(Supplier<T> action, Predicate<Throwable> isFailure) {
    if (!tryAcquire()) {
      throw refused();
    }
    T result;
    try {
      result = action.get();
    } catch (Throwable error) {
      recordError(error, isFailure);
      throw error;
    }
    recordSuccess();
    return result;
  }

  /**
   * Runs an action with no result through the breaker, with the configured failure predicate.
   *
   * @see #execute(Supplier, Predicate)
   */
  public void run(Runnable action) {
    run(action, config.failurePredicate());
  }

  /**
   * Runs an action with no result through the breaker.
   *
   * @see #execute(Supplier, Predicate)
   */
  public void run(Runnable action, Predicate<Throwable> isFailure) {
    execute(
        () -> {
          action.run();
          return null;
        },
        isFailure);
  }

  public synchronized State state() {
    if (openedAt == null) {
      return State.CLOSED;
    }
    return probing ? State.HALF_OPEN : State.OPEN;
  }

  public boolean isClosed() {
    return state() == State.CLOSED;
  }

  public boolean isOpen() {
    return state() == State.OPEN;
  }

  public boolean isHalfOpen() {
    return state() == State.HALF_OPEN;
  }

  /**
   * Tells how long until the next probe is admitted.
   *
   * @return the rest of the cooldown, or of the probe in flight; zero when closed or when a probe
   *     may go now
   */
  public synchronized Duration remainingProbeDelay() {
    if (openedAt == null) {
      return Duration.ZERO;
    }
    var remaining = Duration.between(clock.instant(), openedAt.plus(config.probeAfter()));
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  public BreakerConfig config() {
    return config;
  }

  public String name() {
    return config.name();
  }

  public synchronized int consecutiveFailures() {
    return failuresInARow;
  }

  public long successfulCalls() {
    return successfulCalls.get();
  }

  public long failedCalls() {
    return failedCalls.get();
  }

  /** Calls refused by {@link #tryAcquire()} while open or probing. */
  public long rejectedCalls() {
    return rejectedCalls.get();
  }

  private void recordError(Throwable error, Predicate<Throwable> isFailure) {
    if (isFailure.test(error)) {
      recordFailure();
    } else {
      recordSuccess();
    }
  }

  private GeneralException refused() {
    var wait = remainingProbeDelay();
    // Zero when the cooldown ended between tryAcquire and here; never tell a client to retry at
    // once.
    return new GeneralException(BreakerExceptionStatus.BREAKER_OPEN)
        .subError("breaker", "open")
        .retryAfter(wait.isZero() ? config.probeAfter() : wait);
  }
}
