package com.epam.reportportal.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MigrationUtils {

    private static final Logger logger = LoggerFactory.getLogger(MigrationUtils.class);

    public static void startLog(String message) {
        logWithAsterisks();
        logger.info("Starting {}", message);
        logWithAsterisks();
    }

    public static void endLog(String message) {
        logWithAsterisks();
        logger.info("{} is completed", message);
        logWithAsterisks();
    }

    private static void logWithAsterisks() {
        logger.info("************************************************************");
    }
}