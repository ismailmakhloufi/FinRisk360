package tn.finrisk.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Les tests ne touchent JAMAIS la base de developpement (finrisk) :
 * le profil "test" les redirige vers finrisk_test.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
