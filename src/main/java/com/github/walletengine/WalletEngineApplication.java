package com.github.walletengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WalletEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletEngineApplication.class, args);
    }

}
