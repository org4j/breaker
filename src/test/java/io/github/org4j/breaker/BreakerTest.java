package io.github.org4j.breaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.org4j.error.ErrorExceptionStatus;
import io.github.org4j.error.GeneralException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

// The specification of Breaker: when it opens, what it sheds, and how it recovers.
class BreakerTest {

  private final TestClock clock = new TestClock();

  private Breaker breaker(int failures, Duration probeAfter) {
    return BreakerConfig.of(failures, probeAfter).create(clock);
  }

  private static void open(Breaker breaker) {
    for (var i = 0; i < breaker.config().failures(); i++) {
      breaker.recordFailure();
    }
  }

  private static void callThatThrows(Breaker breaker, RuntimeException thrown) {
    assertThatThrownBy(
            () ->
                breaker.execute(
                    () -> {
                      throw thrown;
                    }))
        .isSameAs(thrown);
  }

  @Nested
  class Closed {

    private final Breaker breaker = breaker(3, Duration.ofSeconds(5));

    @Test
    void admitsEveryCall() {
      assertThat(breaker.tryAcquire()).isTrue();
      assertThat(breaker.execute(() -> "sent")).isEqualTo("sent");
      assertThat(breaker.state()).isEqualTo(Breaker.State.CLOSED);
      assertThat(breaker.remainingProbeDelay()).isZero();
    }

    @Test
    void opensOnlyAfterFailuresInARow() {
      breaker.recordFailure();
      breaker.recordFailure();
      breaker.recordSuccess();
      breaker.recordFailure();
      breaker.recordFailure();
      assertThat(breaker.isClosed()).isTrue();

      breaker.recordFailure();
      assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void neverOpensOnClientErrors() {
      for (var status :
          new HttpStatus[] {
            HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.UNPROCESSABLE_CONTENT
          }) {
        for (var i = 0; i < 10; i++) {
          callThatThrows(breaker, new HttpClientErrorException(status));
        }
      }
      assertThat(breaker.isClosed()).isTrue();
      assertThat(breaker.consecutiveFailures()).isZero();
    }

    @Test
    void opensOnTransportFailuresAndServerErrors() {
      callThatThrows(breaker, new ResourceAccessException("connect timed out"));
      callThatThrows(breaker, new HttpServerErrorException(HttpStatus.BAD_GATEWAY));
      assertThat(breaker.isClosed()).isTrue();

      callThatThrows(breaker, new UncheckedIOException(new IOException("connection reset")));
      assertThat(breaker.isOpen()).isTrue();
    }
  }

  @Nested
  class Open {

    private final Breaker breaker = breaker(3, Duration.ofSeconds(10));

    @Test
    void shedsCallsWithBreakerOpenAndTheRestOfTheCooldown() {
      open(breaker);
      clock.advance(Duration.ofSeconds(4));
      var ran = new AtomicBoolean();

      assertThatThrownBy(() -> breaker.run(() -> ran.set(true)))
          .isInstanceOfSatisfying(
              GeneralException.class,
              refused -> {
                assertThat(refused.getStatus()).isEqualTo(BreakerExceptionStatus.BREAKER_OPEN);
                assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofSeconds(6));
              });
      assertThat(ran).isFalse();
      assertThat(breaker.rejectedCalls()).isEqualTo(1);
    }

    @Test
    void countsTheCooldownDown() {
      open(breaker);
      assertThat(breaker.remainingProbeDelay()).isEqualTo(Duration.ofSeconds(10));

      clock.advance(Duration.ofSeconds(4));
      assertThat(breaker.remainingProbeDelay()).isEqualTo(Duration.ofSeconds(6));

      clock.advance(Duration.ofSeconds(10));
      assertThat(breaker.remainingProbeDelay()).isZero();
    }
  }

  @Nested
  class HalfOpen {

    private final Breaker breaker = breaker(3, Duration.ofSeconds(5));

    @Test
    void admitsOneProbeAfterTheCooldown() {
      open(breaker);
      clock.advance(Duration.ofSeconds(5));

      assertThat(breaker.tryAcquire()).isTrue();
      assertThat(breaker.state()).isEqualTo(Breaker.State.HALF_OPEN);
      assertThat(breaker.tryAcquire()).as("a second call while the probe is out").isFalse();
      assertThat(breaker.isHalfOpen()).isTrue();
    }

    @Test
    void closesWhenTheProbeSucceeds() {
      open(breaker);
      clock.advance(Duration.ofSeconds(5));

      assertThat(breaker.execute(() -> "probe")).isEqualTo("probe");
      assertThat(breaker.state()).isEqualTo(Breaker.State.CLOSED);
      assertThat(breaker.consecutiveFailures()).isZero();
      assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void closesWhenTheProbeGetsAClientError() {
      open(breaker);
      clock.advance(Duration.ofSeconds(5));

      callThatThrows(breaker, new HttpClientErrorException(HttpStatus.NOT_FOUND));
      assertThat(breaker.isClosed()).isTrue();
    }

    @Test
    void reopensForAFullCooldownWhenTheProbeFails() {
      open(breaker);
      clock.advance(Duration.ofSeconds(5));

      callThatThrows(breaker, new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
      assertThat(breaker.state()).isEqualTo(Breaker.State.OPEN);

      clock.advance(Duration.ofSeconds(4));
      assertThat(breaker.tryAcquire()).isFalse();
      clock.advance(Duration.ofSeconds(1));
      assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void replacesAProbeThatNeverReportsBack() {
      open(breaker);
      clock.advance(Duration.ofSeconds(5));
      assertThat(breaker.tryAcquire()).isTrue();

      clock.advance(Duration.ofSeconds(4));
      assertThat(breaker.tryAcquire()).isFalse();
      clock.advance(Duration.ofSeconds(1));
      assertThat(breaker.tryAcquire()).as("a new probe").isTrue();
    }
  }

  @Nested
  class Execution {

    @Test
    void runsAnActionWithNoResult() {
      var breaker = breaker(3, Duration.ofSeconds(5));
      var count = new AtomicInteger();

      breaker.run(count::incrementAndGet);

      assertThat(count).hasValue(1);
      assertThat(breaker.successfulCalls()).isEqualTo(1);
    }

    @Test
    void usesTheConfiguredPredicate() {
      var breaker =
          BreakerConfig.of(1, Duration.ofSeconds(5))
              .withFailurePredicate(IllegalStateException.class::isInstance)
              .create(clock);

      callThatThrows(breaker, new IllegalStateException("ledger locked"));
      assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void letsOneCallUseItsOwnPredicate() {
      var breaker = breaker(1, Duration.ofSeconds(5));

      assertThatThrownBy(
              () ->
                  breaker.execute(
                      () -> {
                        throw new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
                      },
                      error -> true))
          .isInstanceOf(HttpClientErrorException.class);
      assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void recordsAnErrorByThePredicate() {
      var breaker = breaker(3, Duration.ofSeconds(5));

      breaker.recordError(new ResourceAccessException("read timed out"));
      assertThat(breaker.consecutiveFailures()).isEqualTo(1);
      assertThat(breaker.failedCalls()).isEqualTo(1);

      breaker.recordError(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
      assertThat(breaker.consecutiveFailures()).isZero();
      assertThat(breaker.successfulCalls()).isEqualTo(1);
    }

    @Test
    void keepsItsCountsRightUnderContention() throws Exception {
      var breaker = breaker(5, Duration.ofMillis(50));
      var threads = 16;
      var iterations = 200;
      var start = new CountDownLatch(1);
      var admitted = new AtomicInteger();
      var refused = new AtomicInteger();
      var futures = new ArrayList<Future<?>>();

      try (var executor = Executors.newFixedThreadPool(threads)) {
        for (var t = 0; t < threads; t++) {
          futures.add(
              executor.submit(
                  () -> {
                    start.await();
                    for (var i = 0; i < iterations; i++) {
                      if (!breaker.tryAcquire()) {
                        refused.incrementAndGet();
                      } else if (admitted.incrementAndGet() % 7 == 0) {
                        breaker.recordFailure();
                      } else {
                        breaker.recordSuccess();
                      }
                    }
                    return null;
                  }));
        }
        start.countDown();
        for (var future : futures) {
          future.get(10, TimeUnit.SECONDS);
        }
      }

      assertThat(admitted.get() + refused.get()).isEqualTo(threads * iterations);
      assertThat(breaker.successfulCalls() + breaker.failedCalls()).isEqualTo(admitted.get());
      assertThat(breaker.rejectedCalls()).isEqualTo(refused.get());
    }
  }

  @Nested
  class Classification {

    @Test
    void blamesTheSystemForTransportProblemsAndServerErrors() {
      assertThat(BreakerPredicates.isFailure(new IOException("connection reset"))).isTrue();
      assertThat(BreakerPredicates.isFailure(new TimeoutException("gateway timed out"))).isTrue();
      assertThat(BreakerPredicates.isFailure(new ResourceAccessException("socket timeout")))
          .isTrue();
      assertThat(
              BreakerPredicates.isFailure(
                  new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)))
          .isTrue();
      assertThat(BreakerPredicates.isFailure(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))
          .isTrue();
    }

    @Test
    void doesNotBlameItForClientErrorsOrOtherExceptions() {
      assertThat(BreakerPredicates.isFailure(new HttpClientErrorException(HttpStatus.BAD_REQUEST)))
          .isFalse();
      assertThat(BreakerPredicates.isFailure(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)))
          .isFalse();
      assertThat(BreakerPredicates.isFailure(new IllegalArgumentException("invalid payload")))
          .isFalse();
    }

    @Test
    void searchesTheWholeCauseChain() {
      var deep = new RuntimeException(new IllegalStateException(new IOException("broken pipe")));
      assertThat(BreakerPredicates.isFailure(deep)).isTrue();

      var withCause = new IOException("read failed", new IllegalArgumentException("bad frame"));
      assertThat(BreakerPredicates.isFailure(withCause)).isTrue();

      var wrappedClientError =
          new RuntimeException(new HttpClientErrorException(HttpStatus.CONFLICT));
      assertThat(BreakerPredicates.isFailure(wrappedClientError)).isFalse();
    }
  }

  @Nested
  class Configuration {

    @Test
    void derivesProfilesFromABase() {
      var base = BreakerConfig.of(5, Duration.ofSeconds(30));
      var cards = base.withName("cards").withFailures(3).withProbeAfter(Duration.ofSeconds(3));

      assertThat(base).isEqualTo(BreakerConfig.of(5, Duration.ofSeconds(30)));
      assertThat(cards.name()).isEqualTo("cards");
      assertThat(cards.failures()).isEqualTo(3);
      assertThat(cards.probeAfter()).isEqualTo(Duration.ofSeconds(3));
      assertThat(cards.failurePredicate()).isSameAs(base.failurePredicate());

      var breaker = cards.create(clock);
      assertThat(breaker.config()).isSameAs(cards);
      assertThat(breaker.name()).isEqualTo("cards");
      assertThat(cards.create().isClosed()).isTrue();
    }

    @Test
    void defaultsTheNameAndThePredicate() {
      var config = BreakerConfig.of(5, Duration.ofSeconds(30));

      assertThat(config.name()).isEqualTo(BreakerConstants.DEFAULT_BREAKER_NAME);
      assertThat(config.failurePredicate().test(new ResourceAccessException("down"))).isTrue();
    }

    @Test
    void refusesBadSettings() {
      assertInvalid("failures", () -> BreakerConfig.of(0, Duration.ofSeconds(30)));
      assertInvalid("probeAfter", () -> BreakerConfig.of(5, Duration.ZERO));
      assertInvalid("probeAfter", () -> BreakerConfig.of(5, Duration.ofSeconds(-1)));
      assertInvalid("name", () -> BreakerConfig.of(" ", 5, Duration.ofSeconds(30)));
    }

    private void assertInvalid(String setting, Runnable create) {
      assertThatThrownBy(create::run)
          .isInstanceOfSatisfying(
              GeneralException.class,
              invalid -> {
                assertThat(invalid.getStatus()).isEqualTo(ErrorExceptionStatus.INTERNAL_ERROR);
                assertThat(invalid.getSubErrors())
                    .extracting(GeneralException.SubError::code)
                    .containsExactly(setting);
              });
    }
  }
}
