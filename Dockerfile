FROM gradle:6.3-jdk11 AS build
ARG APP_VERSION
WORKDIR /usr/app
COPY . /usr/app
RUN gradle build --exclude-task test -Dorg.gradle.project.version=${APP_VERSION};

# For ARM build use flag: `--platform linux/arm64`
FROM --platform=$BUILDPLATFORM amazoncorretto:11.0.17
LABEL version=${APP_VERSION} description="EPAM Report portal. Complex migrations service" maintainer="Ivan Kustau <ivan_kustau@epam.com>, Hleb Kanonik <hleb_kanonik@epam.com>"
ARG APP_VERSION=${APP_VERSION}
ENV APP_DIR=/usr/app JAVA_OPTS="-Xmx1g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70 -Djava.security.egd=file:/dev/./urandom"

# Install MinIO Client (mc)
RUN yum install -y curl && \
    curl https://dl.min.io/client/mc/release/linux-amd64/mc \
    -o /usr/local/bin/mc && \
    chmod +x /usr/local/bin/mc

WORKDIR $APP_DIR
COPY --from=build $APP_DIR/build/libs/complex-migrations-*exec.jar .
VOLUME ["/tmp"]
EXPOSE 8080
ENTRYPOINT exec java ${JAVA_OPTS} -jar ${APP_DIR}/complex-migrations-*exec.jar