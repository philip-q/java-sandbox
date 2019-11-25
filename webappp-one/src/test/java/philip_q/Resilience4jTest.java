package philip_q;


//import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
//import io.github.resilience4j.ratelimiter.RateLimiter;
//import io.github.resilience4j.ratelimiter.RateLimiterConfig;
//import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
//import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Slf4j
public class Resilience4jTest {

    private MyService service;

//    CircuitBreakerConfig CUSTOM_CONFIG = CircuitBreakerConfig.custom()
//            .minimumNumberOfCalls(3)
//            .failureRateThreshold(50)
//            .slowCallRateThreshold(50)
//            .slowCallDurationThreshold(Duration.ofSeconds(2))
//            .waitDurationInOpenState(Duration.ofMillis(1000))
//            .permittedNumberOfCallsInHalfOpenState(3)
//            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
//            .slidingWindowSize(5)
////            .recordException(e -> e instanceof NumberFormatException)
////            .recordExceptions(IOException.class, TimeoutException.class)
////            .ignoreExceptions(NullPointerException.class)
//            .writableStackTraceEnabled(false)
//            .build();

    @BeforeEach
    public void setup() {
        service = Mockito.spy(MyService.class);
    }

    @Test
    public void circuit_breaker_fails_fast() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .writableStackTraceEnabled(false).build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker cb = registry.circuitBreaker("example");
        Supplier<String> wrapped = CircuitBreaker.decorateSupplier(cb, service::heavyFacadeOperation);

        System.out.println("as");
        doThrow(new RuntimeException()).when(service).resourceCall3();

        for (int i = 0; i < 1000; i++) {
            try {
                wrapped.get();
                // String result = cb.executeSupplier(service::heavyFacadeOperation);
            } catch (Exception e) {
                // log.info(e.toString());
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

        for (int i = 0; i < 1000; i++) {
            try {
                String result = wrapped.get();
                log.info(result);

            } catch (Exception e) {
                log.info(e.getMessage());
                if (e instanceof CallNotPermittedException) {
                    TimeUnit.SECONDS.sleep(2);
                }
            }

        }

    }


//    @Test
//    public void bulkhead_limits_number_of_concurrent_calls() throws InterruptedException {
//        BulkheadConfig config = BulkheadConfig.custom()
//                .maxWaitDuration(Duration.ofMillis(0))
//                .maxConcurrentCalls(1).build();
//
//        BulkheadRegistry registry = BulkheadRegistry.of(config);
//        Bulkhead bh = registry.bulkhead("semaphore");
//        Supplier<String> wrapped = Bulkhead.decorateSupplier(bh, service::heavyFacadeOperation);
//
//        Runnable invokeMultiple = () -> {
//            for (int i = 0; i < 10; i++) {
//                try {
//                    log.info(wrapped.get());
//                } catch (BulkheadFullException e) {
//                    log.info(e.toString());
//                }
//
//            }
//        };
//
//
//        List<Thread> threads = new ArrayList<>();
//        for (int i = 0; i < 2; i++) {
//            Thread t = new Thread(invokeMultiple, "other" + i);
//            threads.add(t);
//            t.start();
//        }
//
//        for (Thread t : threads) {
//            t.join();
//        }
//
//        verify(service, times(10)).heavyFacadeOperation();
//        // no further assertions possible if number of threads > 2
//
//    }
//
//    @Test
//    public void thread_pool_bulkhead_version() throws InterruptedException {
//        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
//                .coreThreadPoolSize(2)
//                .maxThreadPoolSize(2)
//                .queueCapacity(7)
//                .keepAliveDuration(Duration.ofMinutes(1))
//                .build();
//
//        ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.of(config);
//        ThreadPoolBulkhead bh = registry.bulkhead("thread-pool-version");
//        Supplier<CompletionStage<String>> wrapped = ThreadPoolBulkhead.decorateSupplier(bh, service::heavyFacadeOperation);
//
//        Runnable invokeMultiple = () -> {
//            for (int i = 0; i < 10; i++) {
//                try {
//                    wrapped.get().thenAccept(log::info);
//                } catch (BulkheadFullException e) {
//                    log.info(e.toString());
//                }
//
//            }
//        };
//
//        invokeMultiple.run();
//
//        List<Thread> threads = new ArrayList<>();
//        for (int i = 0; i < 2; i++) {
//            Thread other = new Thread(invokeMultiple, "other" + i);
//            threads.add(other);
//            other.start();
//        }
//
//        for (Thread t : threads) {
//            t.join();
//        }
//
//
//    }
//
//    @Test
//    public void rate_limiter_does_the_job() {
//
//        RateLimiterConfig config = RateLimiterConfig.custom()
//                .limitForPeriod(1)
//                .limitRefreshPeriod(Duration.ofSeconds(1))
//                .timeoutDuration(Duration.ofHours(20))
//                .build();
//
//        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
//        RateLimiter rl = registry.rateLimiter("test");
//        Supplier<String> wrapped = RateLimiter.decorateSupplier(rl, service::resourceCall1);
//
//        long start = System.nanoTime();
//        for(int i = 0; i < 10; i++) {
//            try {
//
//                wrapped.get();
//                log.info("Siccess " + i);
//            } catch (RequestNotPermitted e) {
//                log.info(e.toString());
//            }
//        }
//
//    }


}
