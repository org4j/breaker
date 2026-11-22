# breaker

A circuit breaker for one outside system: an SMS aggregator, an identity provider, a payment scheme,
a core banking host. Plain Java on top of the error library: no beans and no properties, so a
service builds each breaker from its own settings.

```groovy
implementation libs.breaker
```

### How it behaves

| State | Calls | Leaves when |
|---|---|---|
| `CLOSED` | all admitted | `failures` failures in a row: `OPEN` |
| `OPEN` | refused with `BREAKER_OPEN` | `probeAfter` has passed: the next call is a probe, `HALF_OPEN` |
| `HALF_OPEN` | the probe only | the probe succeeds: `CLOSED`; fails: `OPEN` for a new `probeAfter`; never reports back: a new probe after `probeAfter` |

A refusal is a `GeneralException(BREAKER_OPEN)`: HTTP 503, messages in English, Russian and Uzbek,
and `Retry-After` set to the rest of the cooldown.

What counts as a failure is the config's predicate, `BreakerPredicates.defaultPredicate()` unless
set. It searches the exception's cause chain: transport problems (`IOException`, `TimeoutException`,
`ResourceAccessException`) and 5xx responses are failures; a 4xx is the system answering (a bad PIN
or an unknown phone number never opens the breaker) and counts as a success.

```java
var base = BreakerConfig.of(5, Duration.ofSeconds(30));
var cards = base.withName("cards").withFailures(3).withProbeAfter(Duration.ofSeconds(3)).create();
var sms = base.withName("sms").withProbeAfter(Duration.ofSeconds(15)).create();

var auth = cards.execute(() -> schemeClient.authorize(payment));
sms.run(() -> smsClient.send(otp));
sms.execute(() -> smsClient.status(id), BreakerPredicates.defaultPredicate().or(Throttled.class::isInstance));
```

`tryAcquire()`, `recordSuccess()`, `recordFailure()` and `recordError(throwable)` drive a breaker by
hand, for calls that do not fit a `Supplier`. `create(clock)` takes a test clock.

### Metrics

`new BreakerMeterBinder(breaker).bindTo(registry)` publishes, tagged `name=<breaker name>`:
`org4j.breaker.state` (0 closed, 1 half-open, 2 open), `org4j.breaker.consecutive-failures`,
`org4j.breaker.failure-threshold`, and `org4j.breaker.calls` by `result` (`success`, `failure`,
`rejected`). Micrometer is the service's own dependency.

## Tests

Unit tests only (`./gradlew test`): the breaker has no infrastructure dependencies. `BreakerTest` is
the specification, one nested group per behaviour: closed, open, half-open, execution, failure
classification and configuration. `BreakerMeterBinderTest` covers the meters.
