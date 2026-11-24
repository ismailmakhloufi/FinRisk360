package tn.finrisk.customerservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Le contexte complet (tous les beans + Hibernate en mode validate) doit se charger. */
@SpringBootTest
@ActiveProfiles("test")
class CustomerServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
