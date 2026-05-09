# syntax=docker/dockerfile:1.7
# ---------- build ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Cache Maven dependencies first for faster rebuilds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
 && cp target/arxivlens-*.jar app.jar

# ---------- runtime ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as a non-root user.
RUN useradd --system --create-home --shell /usr/sbin/nologin arxivlens \
 && mkdir -p /app/var/arxivlens/cache \
 && chown -R arxivlens:arxivlens /app
USER arxivlens

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
COPY --from=build --chown=arxivlens:arxivlens /workspace/app.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
