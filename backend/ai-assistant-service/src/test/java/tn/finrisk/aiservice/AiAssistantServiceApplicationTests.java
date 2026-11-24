package tn.finrisk.aiservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Le contexte démarre sans base de données (aucune table en M6) :
 * seuls web, actuator et les 4 clients sortants sont chargés.
 */
@SpringBootTest
class AiAssistantServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}
