package com.epam.reportportal.service.impl;

import com.epam.reportportal.service.MigrationService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ComplexMigrationService {

  private final List<MigrationService> migrationServices;

  @Autowired
  public ComplexMigrationService(List<MigrationService> migrationServices) {
    this.migrationServices = migrationServices;
  }

  public void migrate() {
    migrationServices.forEach(MigrationService::migrate);
  }
}
