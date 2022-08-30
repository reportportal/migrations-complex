FROM alpine:latest
LABEL version=5.7.2 description="EPAM Report portal. Complex migrations service" maintainer="Ivan Kustau <ivan_kustau@epam.com>, Hleb Kanonik <hleb_kanonik@epam.com>"
COPY ./complex-migrations-*-exec.jar complex-migrations-latest-exec.jar
RUN apk -U -q upgrade && apk --no-cache -q add fontconfig font-noto openjdk11 ca-certificates && \
   echo 'exec java ${JAVA_OPTS} -jar complex-migrations-*-exec.jar' > /start.sh && chmod +x /start.sh
ENV JAVA_OPTS="-Xmx512m -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=70 -Djava.security.egd=file:/dev/./urandom"
VOLUME ["/tmp"]
EXPOSE 8080
ENTRYPOINT ./start.sh
