package com.epam.reportportal;

import com.epam.reportportal.service.impl.ComplexMigrationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class ComplexMigrationsApplication implements ApplicationRunner {

  private final ComplexMigrationService migrationService;

  public ComplexMigrationsApplication(ComplexMigrationService migrationService) {
    this.migrationService = migrationService;
  }

  public static void main(String[] args) {
    System.exit(
        SpringApplication.exit(SpringApplication.run(ComplexMigrationsApplication.class, args)));
  }

  @Override
  public void run(ApplicationArguments args) throws InterruptedException {
    migrationService.migrate();
  }
}
