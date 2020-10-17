FROM amazoncorretto:11 as build-env

ADD https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein /bin/lein
RUN chmod +x /bin/lein
RUN lein version

WORKDIR /usr/src/webjars
COPY project.clj /usr/src/webjars/
RUN lein deps

COPY . /usr/src/webjars
RUN lein uberjar

# Application build

FROM amazoncorretto:11

RUN mkdir -p /opt/webjars/lib
COPY --from=build-env /usr/src/webjars/target/*-standalone.jar /opt/webjars/lib/webjars.jar
CMD java -XX:InitialRAMPercentage=85 -XX:MaxRAMPercentage=85 -jar /opt/webjars/lib/webjars.jar
