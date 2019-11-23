package philip_q;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MyService {

    @CircuitBreaker(name="my-breaker", fallbackMethod = "fallback")
    public String heavyFacadeOperation() {
        log.info("heavyFacadeOperation");
        resourceCall1();
        resourceCall2();
        resourceCall3();
        return "Success";
    }

    public void resourceCall1() {}
    public void resourceCall2() {}
    public void resourceCall3() {
        throw new RuntimeException();
    }

    public String fallback(Throwable e) {
        return "fallback";
    }

}
