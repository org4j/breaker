package io.github.org4j.breaker;

import io.github.org4j.error.StatusThrowable;

public enum BreakerExceptionStatus implements StatusThrowable {
  BREAKER_OPEN;

  @Override
  public String getType() {
    return name();
  }
}
