package philip_q;

import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import javax.xml.ws.WebServiceException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@Slf4j
class Resilience4jTest {

    private CircuitBreakerService service;

    CircuitBreakerConfig CUSTOM_CONFIG = CircuitBreakerConfig.custom()
            .minimumNumberOfCalls(3)
            .failureRateThreshold(50)
            .slowCallRateThreshold(50)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofMillis(1000))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)

            // SlidingWindowType.TIME_BASED - considered any number of invocations during last N minutes
            // SlidingWindowType.COUNT_BASED - considered exactly N last invocations no matter how long it took to wait
            // for them


            .slidingWindowSize(5)

            // If {slidingWindowType} is COUNT_BASED, the last {slidingWindowSize} calls are recorded and aggregated.
            // If {slidingWindowType} is TIME_BASED, the calls of the last {slidingWindowSize} seconds are recorded and aggregated.

            .recordException(e -> e instanceof NumberFormatException)
            .recordExceptions(IOException.class, TimeoutException.class)
            .ignoreExceptions(NullPointerException.class)
            .writableStackTraceEnabled(false) // logging
            .build();

    @BeforeEach
    void setup() {
        service = Mockito.spy(CircuitBreakerService.class);
    }

    @Test
    void retry_repeatedly_invokes_failed_functions() {
        int retryAttempts = 5;

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(retryAttempts)
                .waitDuration(Duration.ofMillis(100))
                // .retryOnResult(response -> response.getStatus() == 500)
                // .retryOnException(e -> e instanceof WebServiceException)
                // .retryExceptions(IOException.class, TimeoutException.class)
                // .ignoreExceptions(BusinessException.class, OtherBusinessException.class)
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        Retry retry = registry.retry("retry");
        Supplier<String> wrapped = Retry.decorateSupplier(retry, service::heavyFacadeOperation);

        /*
         * decorateCallable
         * decorateRunnable
         * decorateConsumer
         * decorateSupplier
         * decorateFunction
         * decorateCompletionStage // returns Supplier<CompletionStage<T>>, where CompletionStage = js Promise
         * + vavr versions of those functions
         *
         * want more args? decorateRunnable(() -> service.multiArgMethod(this, is, how, it's, done))
         *
         * */

        doThrow(new RuntimeException()).when(service).resourceCall3();

        retry.getEventPublisher().onError(e -> System.out.println("Error: " + e.toString()));

        int numberOfExplicitInvocations = 10;
        for (int i = 0; i < numberOfExplicitInvocations; i++) {
            try {
                wrapped.get();
            } catch (Exception e) {
                System.out.println("Got exception: " + e);
            }

        }



        verify(service, times(retryAttempts * numberOfExplicitInvocations)).resourceCall1();

        // we get one exception per block of retries. Meaning that if each call needs to be cleaned up
        // we should perform cleanup inside the function we wrap


    }

    @Test
    void circuit_breaker_fails_fast() {
        CircuitBreakerConfig config = CircuitBreakerConfig.ofDefaults();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker cb = registry.circuitBreaker("example");
        Supplier<String> wrapped = CircuitBreaker.decorateSupplier(cb, service::heavyFacadeOperation);

        doThrow(new RuntimeException()).when(service).resourceCall3();

        for (int i = 0; i < 1000; i++) {
            try {
                // todo: two ways of circuit breaker decoration
                wrapped.get();
                // String result = cb.executeSupplier(service::heavyFacadeOperation);
            } catch (Exception e) {
                System.out.println(i + ": " + e.toString());
            }
        }

        int minimumToTriggerCircuitBreaker = registry.getDefaultConfig().getMinimumNumberOfCalls();
        assertThat(minimumToTriggerCircuitBreaker).isEqualTo(100);

        verify(service, times(minimumToTriggerCircuitBreaker)).resourceCall1();
        verify(service, times(minimumToTriggerCircuitBreaker)).resourceCall2();
        verify(service, times(minimumToTriggerCircuitBreaker)).resourceCall3();


    }

    @Test
    void circuit_breaker_half_open_test() throws InterruptedException {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofMillis(10000))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        CircuitBreakerRegistry reg = CircuitBreakerRegistry.of(config);
        CircuitBreaker cb = reg.circuitBreaker("breaker");

        Supplier<String> wrapped = CircuitBreaker.decorateSupplier(cb, service::heavyFacadeOperation);
        doThrow(new RuntimeException()).when(service).resourceCall3();

        for (int i = 0; i < 1000; i++) {
            try {
                wrapped.get();
            } catch (Exception e) {
                if (e instanceof CallNotPermittedException) {
                    System.out.println(e.toString());
                    TimeUnit.SECONDS.sleep(2);
                }
            }

        }

    }



    @Test
    void bulkhead_limits_number_of_concurrent_calls_semaphore() throws InterruptedException {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxWaitDuration(Duration.ofMillis(0))
                .maxConcurrentCalls(1).build();

        // case 1: no bulkhead -> 80 inv
        // case 2: .maxWaitDuration(Duration.ofMillis(10000)) -> 80 inv
        // case 3: .maxWaitDuration(Duration.ofMillis(0)) -> 10 inv


        BulkheadRegistry registry = BulkheadRegistry.of(config);
        Bulkhead bh = registry.bulkhead("semaphore");
        Supplier<String> wrapped = Bulkhead.decorateSupplier(bh, service::heavyFacadeOperation);

        Runnable invokeMultiple = () -> {
            for (int i = 0; i < 10; i++) {
                try {
                    System.out.println(Thread.currentThread().getName() + ": " + wrapped.get());
                } catch (BulkheadFullException e) {
                    System.out.println(e.toString());
                }

            }
        };

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(invokeMultiple, "other" + i);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("Joined"); // in semaphore version this is always last printed

        // verify(service, times(10)).heavyFacadeOperation();
        // expected number of invocations if maxConcurrentCalls > 1 is unpredictable

    }

    @Test
    void bulkhead_thread_pool_version() throws InterruptedException {
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(2)
                .maxThreadPoolSize(2)
                .queueCapacity(36)
                .keepAliveDuration(Duration.ofMinutes(1))
                .build();

        ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.of(config);
        ThreadPoolBulkhead bh = registry.bulkhead("thread-pool-version");
        Supplier<CompletionStage<String>> wrapped = ThreadPoolBulkhead.decorateSupplier(bh, service::heavyFacadeOperation);

        Runnable invokeMultiple = () -> {
            for (int i = 0; i < 10; i++) {
                try {
                    wrapped.get().thenAccept(s ->
                            System.out.println(Thread.currentThread().getName() + " " + s));
                } catch (BulkheadFullException e) {
                    System.out.println(Thread.currentThread().getName() + " " + e.toString());
                }

            }
        };

        invokeMultiple.run();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread other = new Thread(invokeMultiple, "other" + i);
            threads.add(other);
            other.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        System.out.println("Joined"); // in thread pool version this not last printed line

    }

    @Test
    void rate_limiter_delays_invocation_when_invocation_limit_already_exceeded() {

        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(20))
                .build();

        // config: "allow maximum {2} invocations per {1 second}. store each delayed invocation in queue
        // Maximum wait time in queue {20 milliseconds}

        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rl = registry.rateLimiter("test");
        Supplier<String> wrapped = RateLimiter.decorateSupplier(rl, service::resourceCall1);

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            try {

                System.out.println(wrapped.get());
                System.out.println("Success " + i);
            } catch (RequestNotPermitted e) {
                System.out.println(e.toString());
            }
        }

    }

    @Test
    void rate_limiter_backpressure_test() {

        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofDays(10))
                .timeoutDuration(Duration.ofDays(20))
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rl = registry.rateLimiter("test");
        Supplier<String> wrapped = RateLimiter.decorateSupplier(rl, service::resourceCall1);

        given(service.resourceCall1()).willAnswer(invocation -> {
            System.out.println("Going to long sleep");
            TimeUnit.DAYS.sleep(1);
            return "result after long wait";
        });

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            try {
                System.out.println("Going to invoke wrapped function");
                System.out.println(wrapped.get());
                System.out.println("Success " + i);
            } catch (RequestNotPermitted e) {
                System.out.println(e.toString());
            }
        }

        // we see that main thread waits for the execution and no invocations are
        // actually queued

    }

    @Test
    void rate_limiter_concurrent_backpressure_test() throws InterruptedException {

        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofDays(10))
                .timeoutDuration(Duration.ofDays(20))
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rl = registry.rateLimiter("test");
        Supplier<String> wrapped = RateLimiter.decorateSupplier(rl, service::resourceCall1);

        given(service.resourceCall1()).willAnswer(invocation -> {
            System.out.println("Going to long sleep");
            TimeUnit.DAYS.sleep(1);
            return "result after long wait";
        });

        Runnable invokeMultiple = () -> {
            for (int i = 0; i < 100; i++) {
                try {
                    System.out.println("Going to invoke wrapped function");
                    System.out.println(wrapped.get());
                    System.out.println("Success " + i);
                } catch (RequestNotPermitted e) {
                    System.out.println(e.toString());
                }
            }
        };

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Thread other = new Thread(invokeMultiple, "other" + i);
            threads.add(other);
            other.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        // backpressure is not the problem that can occur. Cause each spawned thread will be blocked
        // the number of threads will rather be our bottleneck

    }





}
