package uz.khakimjanovich.breaker4j;

import org.junit.jupiter.api.Test;
import uz.khakimjanovich.error4j.ErrorDefinitions;

class BreakerExceptionStatusTest {

  @Test
  void everyErrorIsDefinedInEveryLanguage() {
    ErrorDefinitions.assertComplete(
        "uz.khakimjanovich.breaker4j", "classpath*:properties/breaker-exception.yaml");
  }
}
