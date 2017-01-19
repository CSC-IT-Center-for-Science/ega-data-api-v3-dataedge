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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import org.springframework.web.bind.annotation.RestController;
import eu.elixir.ega.ebi.dataedge.service.FileService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestParam;

/**
 *
 * @author asenf
 */
@RestController
@EnableDiscoveryClient
@RequestMapping("/files")
public class FileController {

    @Autowired
    private FileService fileService;
    
    @RequestMapping(value = "/{file_id}", method = GET)
    public void getFile(@PathVariable String file_id,
                        @RequestParam(value = "destinationFormat", required = false, defaultValue="aes128") String destinationFormat,
                        @RequestParam(value = "destinationKey", required = false) String destinationKey,
                        @RequestParam(value = "startCoordinate", required = false) long startCoordinate,
                        @RequestParam(value = "endCoordinate", required = false) long endCoordinate, 
                        HttpServletRequest request,
                        HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user_email = auth.getName();        

        fileService.getFile(user_email, 
                            file_id,
                            destinationFormat,
                            destinationKey,
                            startCoordinate,
                            endCoordinate,
                            request,
                            response);
    }

    @RequestMapping(value = "/{file_id}/header", method = GET)
    public Object getFileHeader(@PathVariable String file_id,
                              @RequestParam(value = "destinationFormat", required = false, defaultValue="aes128") String destinationFormat,
                              @RequestParam(value = "destinationKey", required = false) String destinationKey) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user_email = auth.getName();        
        
        return fileService.getFileHeader(user_email, 
                                         file_id,
                                         destinationFormat,
                                         destinationKey);
        
    }
    
    
}
