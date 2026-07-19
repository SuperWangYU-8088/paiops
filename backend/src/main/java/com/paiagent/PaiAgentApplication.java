package com.paiagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.paiagent.mapper")
@EnableScheduling
public class PaiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaiAgentApplication.class, args);
	}

}
