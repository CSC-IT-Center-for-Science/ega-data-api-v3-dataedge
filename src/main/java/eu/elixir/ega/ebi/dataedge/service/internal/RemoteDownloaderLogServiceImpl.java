/*
 * Copyright 2016 ELIXIR EBI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.elixir.ega.ebi.dataedge.service.internal;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import eu.elixir.ega.ebi.dataedge.dto.DownloadEntry;
import eu.elixir.ega.ebi.dataedge.dto.EventEntry;
import eu.elixir.ega.ebi.dataedge.service.DownloaderLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

/**
 *
 * @author asenf
 */
@Service
@Transactional
@EnableDiscoveryClient
public class RemoteDownloaderLogServiceImpl implements DownloaderLogService {

    private final String SERVICE_URL = "http://DOWNLOADER";
    
    @Autowired
    RestTemplate restTemplate;    

    @Override
    @HystrixCommand
    public void logDownload(DownloadEntry downloadEntry) {
                
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        restTemplate.postForObject(SERVICE_URL + "/log/download/", downloadEntry, Void.class);
      
    }

    @Override
    @HystrixCommand
    public void logEvent(EventEntry eventEntry) {
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        restTemplate.postForObject(SERVICE_URL + "/log/event/", eventEntry, Void.class);
        
    }
    
    
    
}
