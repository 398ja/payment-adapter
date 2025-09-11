# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -q -pl cashu-gateway-webhook -am package -DskipTests

# Runtime stage
FROM tomcat:10.1-jdk21-temurin

# Configure Tomcat
ENV CATALINA_OPTS="${CATALINA_OPTS}"
WORKDIR /usr/local/tomcat

# Deploy the WAR as ROOT
RUN rm -rf webapps/*
COPY --from=build /build/cashu-gateway-webhook/target/cashu-gateway-webhook-*.war webapps/ROOT.war

EXPOSE 8080
CMD ["catalina.sh","run"]

