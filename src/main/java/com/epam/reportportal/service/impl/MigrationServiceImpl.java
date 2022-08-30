package com.epam.reportportal.service.impl;

import com.epam.reportportal.service.ElasticMigrationService;
import com.epam.reportportal.service.MigrationService;
import org.springframework.stereotype.Service;

@Service
public class MigrationServiceImpl implements MigrationService {

	private final ElasticMigrationService elasticMigrationService;

	public MigrationServiceImpl(ElasticMigrationService elasticMigrationService) {
		this.elasticMigrationService = elasticMigrationService;
	}

	@Override
	public void migrate() {
		elasticMigrationService.migrateLogs();
	}
}
