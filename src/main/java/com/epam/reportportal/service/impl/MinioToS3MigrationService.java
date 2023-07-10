package com.epam.reportportal.service.impl;

import com.epam.reportportal.logging.LogMigration;
import com.epam.reportportal.service.MigrationService;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
@Order(4)
@ConditionalOnProperty(name = "rp.minio.s3.migration", havingValue = "true")
public class MinioToS3MigrationService implements MigrationService {
  private static final Logger logger = LoggerFactory.getLogger(MinioToS3MigrationService.class);
  private static final String SHELL_SCRIPT_PATH = "migration.sh";

  @Override
  @LogMigration(value = "Migration from MinIO to S3")
  public void migrate() {
    try {
      setupAndExecuteShellScript();
    } catch (IOException | InterruptedException e) {
      logger.error("Error executing migration.sh script: {}", e.getMessage());
    }
  }

  private void setupAndExecuteShellScript() throws IOException, InterruptedException {
    File scriptFile = createTempShellScriptFile();
    grantShellScriptPermissions(scriptFile);

    // Pass the environment variables for use in the shell script
    Map<String, String> environment = new HashMap<>();
    environment.put("MINIO_ENDPOINT", System.getenv("MINIO_ENDPOINT"));
    environment.put("MINIO_ACCESS_KEY", System.getenv("MINIO_ACCESS_KEY"));
    environment.put("MINIO_SECRET_KEY", System.getenv("MINIO_SECRET_KEY"));
    environment.put("S3_ENDPOINT", System.getenv("S3_ENDPOINT"));
    environment.put("S3_ACCESS_KEY", System.getenv("S3_ACCESS_KEY"));
    environment.put("S3_SECRET_KEY", System.getenv("S3_SECRET_KEY"));
    environment.put("MINIO_SINGLE_BUCKET", System.getenv("MINIO_SINGLE_BUCKET"));
    environment.put("S3_SINGLE_BUCKET", System.getenv("S3_SINGLE_BUCKET"));

    ProcessBuilder processBuilder = new ProcessBuilder(scriptFile.getAbsolutePath());
    processBuilder.environment().putAll(environment);

    processBuilder.redirectErrorStream(true);

    Process process = processBuilder.start();

    // Capture both outputs (stdout and stderr) and log them using Logger
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        logger.info(line);
      }
    }
    int exitCode = process.waitFor();
    logger.info("Shell script executed with exit code: {}", exitCode);

    Files.deleteIfExists(scriptFile.toPath());
  }

  private File createTempShellScriptFile() throws IOException {
    ClassPathResource classPathResource = new ClassPathResource(SHELL_SCRIPT_PATH);
    Path tempDirPath = Files.createTempDirectory("rp-migration-script");
    Path scriptFilePath = Paths.get(tempDirPath.toString(), SHELL_SCRIPT_PATH);
    Files.copy(
        classPathResource.getInputStream(), scriptFilePath, StandardCopyOption.REPLACE_EXISTING);
    return scriptFilePath.toFile();
  }

  private void grantShellScriptPermissions(File scriptFile) throws IOException {
    if (SystemUtils.IS_OS_UNIX) {
      Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
      FileAttribute<Set<PosixFilePermission>> fileAttributes =
          PosixFilePermissions.asFileAttribute(perms);
      Files.setPosixFilePermissions(scriptFile.toPath(), fileAttributes.value());
    } else {
      scriptFile.setExecutable(true, false);
      scriptFile.setReadable(true, false);
      scriptFile.setWritable(true, false);
    }
  }
}
