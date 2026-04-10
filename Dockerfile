FROM gradle:8.12-jdk11 AS build
ARG APP_VERSION
WORKDIR /usr/app
COPY . /usr/app
RUN gradle build --exclude-task test -Dorg.gradle.project.version=${APP_VERSION} \
    && cp build/libs/*-exec.jar /usr/app/application.jar

FROM amazoncorretto:11.0.30
ARG APP_VERSION
ARG TARGETARCH
LABEL version=${APP_VERSION} description="EPAM ReportPortal. Complex migrations service" maintainer="Ivan Kustau <ivan_kustau@epam.com>, Hleb Kanonik <hleb_kanonik@epam.com>"
ENV APP_DIR=/usr/app JAVA_OPTS="-Xmx1g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70 -Djava.security.egd=file:/dev/./urandom"

# Install MinIO Client (mc) for the image architecture (amd64/arm64 in buildx multi-platform builds)
RUN yum install -y curl && \
    arch="${TARGETARCH:-amd64}" && \
    curl -fsSL "https://dl.min.io/client/mc/release/linux-${arch}/mc" -o /usr/local/bin/mc && \
    chmod +x /usr/local/bin/mc

WORKDIR $APP_DIR
COPY --from=build /usr/app/application.jar /usr/app/application.jar
VOLUME ["/tmp"]
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar ${APP_DIR}/application.jar"]