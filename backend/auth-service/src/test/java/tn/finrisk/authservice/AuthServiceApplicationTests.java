package tn.finrisk.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Les tests ne touchent JAMAIS la base de developpement (finrisk). */
@SpringBootTest
@ActiveProfiles("test")
class AuthServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
