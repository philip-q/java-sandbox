package philip_q;


import javax.persistence.Basic;
import javax.persistence.Column;
import javax.persistence.Entity;
import java.util.*;

@Entity
public class MyEntity {

    @Basic
    @Column
    int i;

}
