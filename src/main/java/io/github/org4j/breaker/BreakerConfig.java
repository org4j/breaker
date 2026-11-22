package io.github.org4j.breaker;

import io.github.org4j.error.ErrorExceptionStatus;
import io.github.org4j.error.GeneralException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * How a {@link Breaker} behaves. Immutable: a service keeps one base and derives a profile per
 * outside system with the {@code with} methods. A bad setting is a {@link GeneralException} {@code
 * INTERNAL_ERROR} with a sub-error naming it.
 *
 * {@snippet :
 * var base = BreakerConfig.of(5, Duration.ofSeconds(30));
 * var cards = base.withName("cards").withFailures(3).withProbeAfter(Duration.ofSeconds(3));
 * var breaker = cards.create();
 * }
 *
 * @param name names the breaker in metrics; {@value BreakerConstants#DEFAULT_BREAKER_NAME} by
 *     default
 * @param failures how many failures in a row open the breaker; at least 1
 * @param probeAfter how long the breaker stays open before a probe; positive
 * @param failurePredicate which exceptions are the outside system's failures; {@link
 *     BreakerPredicates#defaultPredicate()} by default
 */
public record BreakerConfig(
    String name, int failures, Duration probeAfter, Predicate<Throwable> failurePredicate) {

  public BreakerConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(probeAfter, "probeAfter must not be null");
    Objects.requireNonNull(failurePredicate, "failurePredicate must not be null");
    if (name.isBlank()) {
      throw invalid("name", "must not be blank");
    }
    if (failures < 1) {
      throw invalid("failures", "must be at least 1, not " + failures);
    }
    if (!probeAfter.isPositive()) {
      throw invalid("probeAfter", "must be positive, not " + probeAfter);
    }
  }

  public static BreakerConfig of(int failures, Duration probeAfter) {
    return of(BreakerConstants.DEFAULT_BREAKER_NAME, failures, probeAfter);
  }

  public static BreakerConfig of(String name, int failures, Duration probeAfter) {
    return new BreakerConfig(name, failures, probeAfter, BreakerPredicates.defaultPredicate());
  }

  public BreakerConfig withName(String name) {
    return new BreakerConfig(name, failures, probeAfter, failurePredicate);
  }

  public BreakerConfig withFailures(int failures) {
    return new BreakerConfig(name, failures, probeAfter, failurePredicate);
  }

  public BreakerConfig withProbeAfter(Duration probeAfter) {
    return new BreakerConfig(name, failures, probeAfter, failurePredicate);
  }

  public BreakerConfig withFailurePredicate(Predicate<Throwable> failurePredicate) {
    return new BreakerConfig(name, failures, probeAfter, failurePredicate);
  }

  /** Creates a closed breaker on the system clock (UTC). */
  public Breaker create() {
    return create(Clock.systemUTC());
  }

  /** Creates a closed breaker that reads time from {@code clock}, such as a test clock. */
  public Breaker create(Clock clock) {
    return new Breaker(this, clock);
  }

  private static GeneralException invalid(String setting, String problem) {
    return new GeneralException(ErrorExceptionStatus.INTERNAL_ERROR).subError(setting, problem);
  }
}
