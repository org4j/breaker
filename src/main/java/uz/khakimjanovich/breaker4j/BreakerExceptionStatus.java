package uz.khakimjanovich.breaker4j;

import uz.khakimjanovich.error4j.StatusThrowable;

public enum BreakerExceptionStatus implements StatusThrowable {
  BREAKER_OPEN;

  @Override
  public String getType() {
    return name();
  }
}
