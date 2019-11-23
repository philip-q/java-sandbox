package philip_q;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class Controller {

    @Autowired
    private MyService service;

    @RequestMapping("/")
    public String index() {
        return "Greetings from Spring Boot!";
    }

    @RequestMapping("/ddos")
    public void doDdos() {
        for (int i = 0; i < 1000; i++) {
            try {
                String result = service.heavyFacadeOperation();
                log.info(result);
            } catch (Exception e) {
                System.out.println(String.format("%d %s", i, e));
            }
        }
    }

}
