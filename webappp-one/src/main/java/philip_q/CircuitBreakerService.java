package philip_q;

public class CircuitBreakerService {


    public String heavyFacadeOperation() {
        System.out.println(Thread.currentThread().getName() + " " + "heavyFacadeOperation");
        resourceCall1();
        resourceCall2();
        resourceCall3();
        return "Success";
    }

    public String resourceCall1() { return "resource1"; }
    public void resourceCall2() {}
    public void resourceCall3() {}

}
