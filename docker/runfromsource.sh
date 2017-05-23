#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
sudo docker run --rm --name build -v $DIR:/EGA_build -it alexandersenf/ega_build sh -c 'exec /EGA_build/build.sh'
sudo docker build -t ega_eureka -f Dockerfile_Deploy .
sudo rm DiscoveryServer-0.0.1-SNAPSHOT.war
sudo rm Dockerfile_Deploy
sudo rm eurekad.sh
sudo docker run -d -p 8761:8761 ega_eureka
