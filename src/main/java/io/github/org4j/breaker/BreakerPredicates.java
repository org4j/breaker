package io.github.org4j.breaker;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Tells an outside system's failures from answers it gave. */
public final class BreakerPredicates {

  private BreakerPredicates() {}

  /** {@link #isFailure} as a predicate: the one every config gets unless it sets its own. */
  public static Predicate<Throwable> defaultPredicate() {
    return BreakerPredicates::isFailure;
  }

  /**
   * Tells whether an exception means the outside system failed. Its cause chain is searched,
   * nearest first:
   *
   * <ul>
   *   <li>an HTTP response ({@link RestClientResponseException}) decides by its status: a 5xx is a
   *       failure, a 4xx is not (a bad PIN or an unknown phone number never opens the breaker);
   *   <li>a transport problem ({@link IOException}, {@link TimeoutException}, {@link
   *       ResourceAccessException}) is a failure.
   * </ul>
   *
   * @param error what a call threw
   * @return {@code true} if the system is to blame; {@code false} for anything else
   */
  public static boolean isFailure(Throwable error) {
    for (@Nullable Throwable link = error; link != null; link = link.getCause()) {
      if (link instanceof RestClientResponseException response) {
        return response.getStatusCode().is5xxServerError();
      }
      if (link instanceof IOException
          || link instanceof TimeoutException
          || link instanceof ResourceAccessException) {
        return true;
      }
    }
    return false;
  }
}
