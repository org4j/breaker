package io.github.org4j.breaker;

/** The breaker's default name and the names of its meters and tags. */
public final class BreakerConstants {

  /** The name of a breaker whose config sets none. */
  public static final String DEFAULT_BREAKER_NAME = "default";

  public static final String BREAKER_STATE_METRIC = "org4j.breaker.state";
  public static final String BREAKER_FAILURES_METRIC = "org4j.breaker.consecutive-failures";
  public static final String BREAKER_THRESHOLD_METRIC = "org4j.breaker.failure-threshold";
  public static final String BREAKER_CALLS_METRIC = "org4j.breaker.calls";

  public static final String NAME_TAG = "name";
  public static final String RESULT_TAG = "result";

  public static final String RESULT_SUCCESS = "success";
  public static final String RESULT_FAILURE = "failure";
  public static final String RESULT_REJECTED = "rejected";

  private BreakerConstants() {}
}
