FROM amazoncorretto:11.0.17
LABEL version=1.0.0 description="EPAM Report portal. Complex migrations service" maintainer="Ivan Kustau <ivan_kustau@epam.com>, Hleb Kanonik <hleb_kanonik@epam.com>"

# Install MinIO Client (mc)
RUN yum install -y curl && \
    curl https://dl.min.io/client/mc/release/linux-amd64/mc \
    -o /usr/local/bin/mc && \
    chmod +x /usr/local/bin/mc

ARG GH_TOKEN
RUN echo 'exec java ${JAVA_OPTS} -jar complex-migrations-1.0.0-exec.jar' > /start.sh && chmod +x /start.sh && \
	wget --header="Authorization: Bearer ${GH_TOKEN}"  -q https://maven.pkg.github.com/reportportal/complex-migrations/com/epam/reportportal/complex-migrations/1.0.0/complex-migrations-1.0.0-exec.jar

ENV JAVA_OPTS="-Xmx1g -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70 -Djava.security.egd=file:/dev/./urandom"
VOLUME ["/tmp"]
EXPOSE 8080

ENTRYPOINT ./start.sh