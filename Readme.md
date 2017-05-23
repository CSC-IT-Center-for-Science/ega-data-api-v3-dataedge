# EGA.Data.API.v3.DATAEDGE

This is an Edge Server (DATAEDGE). It enforces user authentication for some edpoints by requiring an EGA Bearer Token for API access. DATAEDGE is the service providing streaming file downloads and provides the back end for a FUSE layer implementation. Data downloads can be served as whole-file downloads, or be specifying byte ranges of the underlying file.

Dependency: 
* CONFIG (`https://github.com/elixir-europe/ega-data-api-v3-config`). The `'bootstrap-blank.properties'` file must be modified to point to a running configuration service, which will be able to serve the `application.properties` file for this service `DATAEDGE`
* EUREKA (`https://github.com/elixir-europe/ega-data-api-v3-eureka`). DATAEDGE service will contact the other services via EUREKA and registers itself with it.
* DATA (``). This service provides certain information to advanced data access.
* DOWNLOADER (``). This service handles download requests and logging. Users can download previously requested files. Downloads are logged via DOWNLOADER.
* RES (`https://github.com/elixir-europe/ega-data-api-v3-res_mvc`). All downloads originate from the RES service, which prepares all outgoing data so that it is encrypted, either with a user's public GPG key or with the key selected upon requesting the download.
* EGA AAI. This is an Authentication and Authorisation Infrastructure service (OpenID Connect IdP) available at Central EGA.

### Documentation

This is the edge service to provide direct access to EGA archive files, via RES. This service offers endpoints secured by OAuth2 tokens for direct access to files, and unsecured endpoints for downloading request tickets (which is the main functionality).

There is also some experimental functionality to return “semantic” file information - such as the header of a BAM file; unencrypted at the moment (future: also of CRAM, VCF files).

[GET]	`/stats/load`
[GET]	`/files/{file_stable_id} [id, destinationFormat, destinationKey, startCoordinate, endCoordinate]`
[GET] `/files/{file_stable_id}/header`
[GET]	`/download/{download_ticket}`
[GET]	`/session/{session_id}`

### Usage Examples


### Todos

 - Write Tests
 - Develop GA4GH Functionality


### Deploy

The service can be deployed directly to a Docker container, using these instructions:

`wget https://raw.github.com/elixir-europe/ega-data-api-v3-dataedge/master/docker/runfromsource.sh`  
`wget https://raw.github.com/elixir-europe/ega-data-api-v3-dataedge/master/docker/build.sh`  
`chmod +x runfromsource.sh`  
`chmod +x build.sh`  
`./runfromsource.sh`  

These commands perform a series of actions:  
	1. Pull a build environment from Docker Hub  
	2. Run the 'build.sh' script inside of a transient build environment container.  
	3. The source code is pulled from GitHub and built  
	4. A Deploy Docker Image is built and the compiled service is added to the image  
	5. The deploy image is started; the service is automatically started inside the container  

The Docker image can also be obtained directly from Docker Hub:  

`sudo docker run -d -p 9059:9059 alexandersenf/ega_dataedge`  or by running the `./runfromimage.sh` file.