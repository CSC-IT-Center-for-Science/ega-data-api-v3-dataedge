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
package eu.elixir.ega.ebi.dataedge.rest;

import eu.elixir.ega.ebi.dataedge.service.DownloadService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import org.springframework.web.bind.annotation.RestController;


/**
 *
 * @author asenf
 */
@RestController
@EnableDiscoveryClient
@RequestMapping("/download")
public class DownloadController {

    @Autowired
    DownloadService downloadService;
    
    @RequestMapping(value = "/{download_ticket}", method = GET)
    public void download(@PathVariable String download_ticket,
                         HttpServletRequest request,
                         HttpServletResponse response) {
        
        // Get Ticket from DOWNLOADER
        downloadService.download(download_ticket,
                                 request,
                                 response);
        
    }
    
    
}
