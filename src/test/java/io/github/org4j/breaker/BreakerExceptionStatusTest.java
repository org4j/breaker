package io.github.org4j.breaker;

import io.github.org4j.error.ErrorDefinitions;
import org.junit.jupiter.api.Test;

class BreakerExceptionStatusTest {

  @Test
  void everyErrorIsDefinedInEveryLanguage() {
    ErrorDefinitions.assertComplete(
        "io.github.org4j.breaker", "classpath*:properties/breaker-exception.yaml");
  }
}
