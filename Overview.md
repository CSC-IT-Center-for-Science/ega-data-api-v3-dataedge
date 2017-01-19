# EGA Data API v3 -- Overview

The EGA Data API is a portable set of microservices providing a REST API to access to EGA data. There are currently several access tools available for this API, including a command line download client and a FUSE layer for direct random access to archived files.

The API is written in Java 1.8 using the Spring Framework and Spring Boot 1.4. As a prerequisite two Spring Servers are expected to be running at all times:
* (Netflix) Eureka Server
* Config Server

All sercives register themselves with the Eureka instance, using the Spring application name `{app.name}` specified in `'bootstrap.configuration'`:`"spring.application.name={app.name}"`. Contact between microservices then utilises `{app.name}` instead of the absolute URL. Eureka automatically resolves the application name to a URL; if multiple instances with the same application name are present, Eureka automatically performs Ribbon load balancing between the instances. This greatly simplifies deployment and allows for easy scaling to meet demand.

The Configuration server serves all `'application.configration'` files to the respective microservices. The location of the config server is specified in the `'bootstrap.configuration'`:`"spring.cloud.config.uri="` of each service. This entry requires an absolute URL to the configration server. This is necessary because the configuration file must be loaded before the application startup. A config file named `'{app.name}.configuration'` must be in the config directory referenced by the configuration server before a service with `"spring.application.name={app.name}"` can be started.

Without these two services the remaining microservices will not function properly.

There are two microservices interfacing with a database:
* DATA
* DOWNLOADER

The DATA service reads (read-only) file-related information (archive paths; file-dataset associations; etc.) while the DOWNLOADER service keeps track of download requests and logs (read/write). It write and updates download requests and logs download avtivity in a database.

Two microservices provide publicly accessible Edge services:
* ACCESS
* DATAEDGE

ACCESS provides most of the user interaction: listing datasets, files. Making, updating requests. DATAEDGE provides access to the data. It provides access to archived files directly; it streams download tickets. This is the primary back end for the FUSE layer. ACCESS is completely secured by the EGA AAI; all endpoints require a valid EGA OAuth2 Bearer Token. Security for DATAEDGE is mixed. These two services validate access token with the EGA AAI service running at EGA Central.

At EGA Central these services are deployed behind an SSL terminating load balancer; therefore https is not initially implemented with these services.

The CPU heavy cryptographic work is performed by the RES service, supported by a Key provider service and an H2 database (to enable this service to be properly load balanced). The key provider service produces the archive decryption key for a specified file, and is only used from within RES. The H2 database stores MD5 values of data transfers, to enable verification of completed downloads.
* KEY
* H2 DB
* RES

### Summary
* EUREKA
    * Requires:
    * Required By: ACCESS, DATAEDGE, DATA, DOWNLOADER, RES, KEY
* CONFIG
    * Requires:
    * Required By: ACCESS, DATAEDGE, DATA, DOWNLOADER, RES, KEY
* H2
    * Requires:
    * Required By: RES, DATAEDGE
* KEY
    * Requires: EUREKA, CONFIG
    * Required By: RES
* DATA
    * Requires: EUREKA, CONFIG
    * Required By: ACCESS
* DOWNLOADER
    * Requires: EUREKA, CONFIG
    * Required By: ACCESS, DATAEDGE
* ACCESS
    * Requires: EUREKA, CONFIG, DATA, DOWNLOADER, [EGA AAI]
    * Required By:
* DATAEDGE
    * Requires: EUREKA, CONFIG, DOWNLOADER, H2, [EGA AAI]
    * Required By:
* RES
    * Requires: EUREKA, CONFIG, KEY, H2
    * Required By: DATAEDGE

### Todos

 - Write Tests
 - Move Authorisations to EGA AAI

License
----

Apache 2.0

