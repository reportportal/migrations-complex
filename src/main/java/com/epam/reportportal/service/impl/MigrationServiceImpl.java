package com.epam.reportportal.service.impl;

import com.epam.reportportal.service.ElasticMigrationService;
import com.epam.reportportal.service.MigrationService;
import com.epam.reportportal.service.SingleBucketMigrationService;
import org.springframework.stereotype.Service;

@Service
public class MigrationServiceImpl implements MigrationService {

  private final ElasticMigrationService elasticMigrationService;

  private final SingleBucketMigrationService singleBucketMigrationService;

  public MigrationServiceImpl(ElasticMigrationService elasticMigrationService,
      SingleBucketMigrationService singleBucketMigrationService) {
    this.elasticMigrationService = elasticMigrationService;
    this.singleBucketMigrationService = singleBucketMigrationService;
  }

  @Override
  public void migrate() {
    elasticMigrationService.migrateLogs();
	singleBucketMigrationService.migrateAttachments();
  }
}
