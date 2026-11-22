package io.github.org4j.breaker;

import static io.github.org4j.breaker.BreakerConstants.BREAKER_CALLS_METRIC;
import static io.github.org4j.breaker.BreakerConstants.BREAKER_FAILURES_METRIC;
import static io.github.org4j.breaker.BreakerConstants.BREAKER_STATE_METRIC;
import static io.github.org4j.breaker.BreakerConstants.BREAKER_THRESHOLD_METRIC;
import static io.github.org4j.breaker.BreakerConstants.NAME_TAG;
import static io.github.org4j.breaker.BreakerConstants.RESULT_FAILURE;
import static io.github.org4j.breaker.BreakerConstants.RESULT_REJECTED;
import static io.github.org4j.breaker.BreakerConstants.RESULT_SUCCESS;
import static io.github.org4j.breaker.BreakerConstants.RESULT_TAG;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Publishes a breaker's meters, tagged {@code name=<breaker name>} plus any given tags:
 *
 * <ul>
 *   <li>{@value BreakerConstants#BREAKER_STATE_METRIC}: 0 closed, 1 half-open, 2 open;
 *   <li>{@value BreakerConstants#BREAKER_FAILURES_METRIC}: failures in a row;
 *   <li>{@value BreakerConstants#BREAKER_THRESHOLD_METRIC}: the failures that open it;
 *   <li>{@value BreakerConstants#BREAKER_CALLS_METRIC}: calls by {@code result}: {@code success},
 *       {@code failure} or {@code rejected}.
 * </ul>
 *
 * <p>Needs Micrometer on the classpath; the library does not bring it.
 */
public final class BreakerMeterBinder implements MeterBinder {

  private final Breaker breaker;
  private final Iterable<Tag> tags;

  public BreakerMeterBinder(Breaker breaker) {
    this(breaker, List.of());
  }

  public BreakerMeterBinder(Breaker breaker, Iterable<Tag> tags) {
    this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
    this.tags = Objects.requireNonNull(tags, "tags must not be null");
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    var named = Tags.concat(tags, NAME_TAG, breaker.name());

    Gauge.builder(BREAKER_STATE_METRIC, breaker, b -> level(b.state()))
        .description("Circuit breaker state: 0 closed, 1 half-open, 2 open")
        .tags(named)
        .register(registry);
    Gauge.builder(BREAKER_FAILURES_METRIC, breaker, Breaker::consecutiveFailures)
        .description("Failures in a row recorded by the circuit breaker")
        .tags(named)
        .register(registry);
    Gauge.builder(BREAKER_THRESHOLD_METRIC, breaker, b -> b.config().failures())
        .description("Failures in a row that open the circuit breaker")
        .tags(named)
        .register(registry);

    calls(registry, named, RESULT_SUCCESS, Breaker::successfulCalls);
    calls(registry, named, RESULT_FAILURE, Breaker::failedCalls);
    calls(registry, named, RESULT_REJECTED, Breaker::rejectedCalls);
  }

  private void calls(
      MeterRegistry registry, Tags named, String result, ToDoubleFunction<Breaker> count) {
    FunctionCounter.builder(BREAKER_CALLS_METRIC, breaker, count)
        .description("Calls through the circuit breaker, by result")
        .tags(named.and(RESULT_TAG, result))
        .register(registry);
  }

  private static double level(Breaker.State state) {
    return switch (state) {
      case CLOSED -> 0;
      case HALF_OPEN -> 1;
      case OPEN -> 2;
    };
  }
}
