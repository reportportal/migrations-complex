package com.epam.reportportal.logging;

import com.epam.reportportal.utils.MigrationUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LogAspect {

  @Around("@annotation(logMigration)")
  public Object logMigration(ProceedingJoinPoint point, LogMigration logMigration)
      throws Throwable {
    String name = logMigration.value();

    MigrationUtils.startLog(name);
    Object object = point.proceed();
    MigrationUtils.endLog(name);

    return object;
  }
}