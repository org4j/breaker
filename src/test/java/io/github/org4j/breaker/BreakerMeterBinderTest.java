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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.org4j.error.GeneralException;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class BreakerMeterBinderTest {

  private final TestClock clock = new TestClock();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Breaker breaker = BreakerConfig.of("sms", 2, Duration.ofSeconds(10)).create(clock);

  @BeforeEach
  void bind() {
    new BreakerMeterBinder(breaker, List.of(Tag.of("region", "uz"))).bindTo(registry);
  }

  private double gauge(String metric) {
    return registry.get(metric).tag(NAME_TAG, "sms").tag("region", "uz").gauge().value();
  }

  private double calls(String result) {
    return registry
        .get(BREAKER_CALLS_METRIC)
        .tag(NAME_TAG, "sms")
        .tag(RESULT_TAG, result)
        .functionCounter()
        .count();
  }

  private void fail() {
    assertThatThrownBy(
            () ->
                breaker.execute(
                    () -> {
                      throw new ResourceAccessException("down");
                    }))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  void startsClosedWithNothingCounted() {
    assertThat(gauge(BREAKER_STATE_METRIC)).isZero();
    assertThat(gauge(BREAKER_FAILURES_METRIC)).isZero();
    assertThat(gauge(BREAKER_THRESHOLD_METRIC)).isEqualTo(2);
    assertThat(calls(RESULT_SUCCESS)).isZero();
    assertThat(calls(RESULT_FAILURE)).isZero();
    assertThat(calls(RESULT_REJECTED)).isZero();
  }

  @Test
  void followsTheBreakerThroughItsCycle() {
    breaker.execute(() -> "ok");
    fail();
    assertThat(calls(RESULT_SUCCESS)).isEqualTo(1);
    assertThat(calls(RESULT_FAILURE)).isEqualTo(1);
    assertThat(gauge(BREAKER_FAILURES_METRIC)).isEqualTo(1);
    assertThat(gauge(BREAKER_STATE_METRIC)).as("closed").isZero();

    fail();
    assertThat(gauge(BREAKER_STATE_METRIC)).as("open").isEqualTo(2);

    assertThatThrownBy(() -> breaker.execute(() -> "shed")).isInstanceOf(GeneralException.class);
    assertThat(calls(RESULT_REJECTED)).isEqualTo(1);

    clock.advance(Duration.ofSeconds(10));
    breaker.tryAcquire();
    assertThat(gauge(BREAKER_STATE_METRIC)).as("half-open").isEqualTo(1);

    breaker.recordSuccess();
    assertThat(gauge(BREAKER_STATE_METRIC)).as("closed").isZero();
    assertThat(gauge(BREAKER_FAILURES_METRIC)).isZero();
    assertThat(calls(RESULT_SUCCESS)).isEqualTo(2);
  }
}
