package org.thingsboard.rulechain.library;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.thingsboard")
public class RuleChainLibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleChainLibraryApplication.class, args);
    }

}
