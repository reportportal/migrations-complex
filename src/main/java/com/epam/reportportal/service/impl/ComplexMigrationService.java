package com.epam.reportportal.service.impl;

import com.epam.reportportal.service.MigrationService;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

@Service
public class ComplexMigrationService {

  private final List<MigrationService> migrationServices;

  @Autowired
  public ComplexMigrationService(List<MigrationService> migrationServices) {
    // Sort migration services based on their order
    migrationServices.sort(Comparator.comparingInt(o -> {
      if (o instanceof Ordered) {
        return ((Ordered) o).getOrder();
      } else {
        return Ordered.LOWEST_PRECEDENCE;
      }
    }));
    this.migrationServices = migrationServices;
  }

  public void migrate() {
    migrationServices.forEach(MigrationService::migrate);
  }
}
