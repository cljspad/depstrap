FROM amazoncorretto:11 as build-env

ADD https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein /bin/lein
RUN chmod +x /bin/lein
RUN lein version

WORKDIR /usr/src/depstrap
COPY project.clj /usr/src/depstrap/
RUN lein deps

COPY . /usr/src/depstrap
RUN lein uberjar

# Application build

FROM amazoncorretto:11

RUN mkdir -p /opt/depstrap/lib
COPY --from=build-env /usr/src/depstrap/target/*-standalone.jar /opt/depstrap/lib/depstrap.jar
CMD java -XX:InitialRAMPercentage=85 -XX:MaxRAMPercentage=85 -jar /opt/depstrap/lib/depstrap.jar
