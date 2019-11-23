package philip_q;


import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class Resilience4jTest {

    private MyService service;

    private static final CircuitBreakerConfig CUSTOM_CONFIG = CircuitBreakerConfig.custom()
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .slowCallRateThreshold(50)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofMillis(1000))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(5)
            .recordException(e -> e instanceof NumberFormatException)
            .recordExceptions(IOException.class, TimeoutException.class)
            .ignoreExceptions(NullPointerException.class)
            .build();

    @BeforeEach
    public void setup() {
        service = Mockito.spy(MyService.class);
    }

    @Test
    public void circuit_breaker_fails_fast() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker cb = registry.circuitBreaker("example");
        Supplier<String> wrapped = CircuitBreaker.decorateSupplier(cb, service::heavyFacadeOperation);

        doThrow(new RuntimeException()).when(service).resourceCall3();

        for(int i = 0; i < 1000; i++) {
            try {
                wrapped.get();
                // String result = cb.executeSupplier(service::heavyFacadeOperation);
            } catch (Exception e) {
                System.out.println(e);
            }
        }

        int miniumToTriggerCircuitBreaker = registry.getDefaultConfig().getMinimumNumberOfCalls();

        assertThat(miniumToTriggerCircuitBreaker).isEqualTo(100);

        verify(service, times(miniumToTriggerCircuitBreaker)).resourceCall1();
        verify(service, times(miniumToTriggerCircuitBreaker)).resourceCall2();
        verify(service, times(miniumToTriggerCircuitBreaker)).resourceCall3();
    }

    @Test
    public void circuit_breaker_half_open_test() throws InterruptedException {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofMillis(1000))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        CircuitBreakerRegistry reg = CircuitBreakerRegistry.of(config);
        CircuitBreaker cb = reg.circuitBreaker("breaker");

        Supplier<String> wrapped = CircuitBreaker.decorateSupplier(cb, service::heavyFacadeOperation);
        doThrow(new RuntimeException()).when(service).resourceCall3();

        for(int i = 0; i < 1000; i++) {
            try {
                String result = wrapped.get();
                System.out.println(result);

            } catch (Exception e) {
                System.out.println(e);
                if (e instanceof CallNotPermittedException) {
                    TimeUnit.SECONDS.sleep(2);
                }
            }

        }

    }


    @Test
    public void config() throws InterruptedException {

        CircuitBreakerRegistry reg = CircuitBreakerRegistry.of(CUSTOM_CONFIG);
        CircuitBreaker cb = reg.circuitBreaker("config");

        Supplier<String> wrapped = CircuitBreaker.decorateSupplier(cb, service::heavyFacadeOperation);
        doThrow(new RuntimeException()).when(service).resourceCall3();

        for(int i = 0; i < 1000; i++) {
            try {
                String result = wrapped.get();
                System.out.println(result);

            } catch (Exception e) {
                System.out.println(e);
                if (e instanceof CallNotPermittedException) {
                    TimeUnit.SECONDS.sleep(2);
                }
            }

        }

        verify(service, times(1000)).resourceCall1();
        verify(service, times(1000)).resourceCall2();
        verify(service, times(1000)).resourceCall3();

        assertThat(1).isEqualTo(1);
    }


}
