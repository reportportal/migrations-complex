package com.epam.reportportal;

import com.epam.reportportal.service.MigrationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ComplexMigrationsApplication implements ApplicationRunner {

	private final MigrationService migrationService;

	public ComplexMigrationsApplication(MigrationService migrationService) {
		this.migrationService = migrationService;
	}

	public static void main(String[] args) {
		System.exit(SpringApplication.exit(SpringApplication.run(ComplexMigrationsApplication.class, args)));
	}

	@Override
	public void run(ApplicationArguments args) {
		migrationService.migrate();
	}
}
