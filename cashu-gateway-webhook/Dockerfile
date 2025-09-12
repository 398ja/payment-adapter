# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -q -pl cashu-gateway-webhook -am package -DskipTests

# Runtime stage (Servlet container)
FROM tomcat:10.1-jdk21-temurin

WORKDIR /usr/local/tomcat

# Clean default apps and deploy our WAR as ROOT
RUN rm -rf webapps/*
COPY --from=build /build/cashu-gateway-webhook/target/cashu-gateway-webhook-*.war webapps/ROOT.war

# Expose Tomcat HTTP port
EXPOSE 8080

CMD ["catalina.sh","run"]

