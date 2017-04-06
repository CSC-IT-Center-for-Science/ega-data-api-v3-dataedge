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
import eu.elixir.ega.ebi.dataedge.dto.File;
import eu.elixir.ega.ebi.dataedge.service.DemoGA4GHService;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SamInputResource;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.seekablestream.SeekableStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

/**
 *
 * @author asenf
 */
@Service
@Transactional
@EnableDiscoveryClient
public class DemoGA4GHServiceImpl implements DemoGA4GHService {

    private final String SERVICE_URL = "http://DATA";
    private final String RES_URL = "http://RES";
    
    @Autowired
    RestTemplate restTemplate;
    
    @Override
    @HystrixCommand
    @ResponseBody
    public SAMFileHeader getHeader(String fileId) {
        
        ResponseEntity<File> forEntity = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}", File.class, fileId);
        //String dataset_id = forEntity.getBody().getDatasetStableId();
        //String permission = restTemplate.getForObject(SERVICE_URL + "/user/{user_email}/datasets/{dataset_id}/", String.class, user_email, dataset_id);
        String permission = "approved";

        try {
            if (permission.equalsIgnoreCase("approved")) {
                
                
                
                
                
                
                SeekableStream file = null;
                
                SamInputResource samInputResource = SamInputResource.of(file);
                
                SamReaderFactory samReaderFactory = SamReaderFactory.makeDefault().validationStringency(ValidationStringency.LENIENT);
                SamReader samReader = samReaderFactory.open(samInputResource);                
                
                SAMFileHeader fileHeader = samReader.getFileHeader();
                
                return fileHeader;
            }
        } catch (Throwable t) {}
        
        return null;
    }
    
}
