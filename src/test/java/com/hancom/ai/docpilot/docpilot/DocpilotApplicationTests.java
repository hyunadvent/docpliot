package com.hancom.ai.docpilot.docpilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"config.llm-config-path=src/test/resources/config/test_llm_config.json",
		"config.structure-config-path=src/test/resources/config/test_page_structure_config.json"
})
class DocpilotApplicationTests {

    @Test
    void contextLoads() {
    }

}
