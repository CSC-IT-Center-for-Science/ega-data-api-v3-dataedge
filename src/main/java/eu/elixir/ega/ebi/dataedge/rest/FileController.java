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

import eu.elixir.ega.ebi.dataedge.config.InvalidAuthenticationException;
import eu.elixir.ega.ebi.dataedge.config.UnsupportedFormatException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import eu.elixir.ega.ebi.dataedge.service.FileService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

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
                        @RequestParam(value = "destinationKey", required = false, defaultValue = "") String destinationKey,
                        @RequestParam(value = "startCoordinate", required = false, defaultValue = "0") long startCoordinate,
                        @RequestParam(value = "endCoordinate", required = false, defaultValue = "0") long endCoordinate, 
                        HttpServletRequest request,
                        HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        fileService.getFile(auth, 
                            file_id,
                            destinationFormat,
                            destinationKey,
                            startCoordinate,
                            endCoordinate,
                            request,
                            response);
    }

    // Experimental - Return a BAM Header
    @RequestMapping(value = "/{file_id}/header", method = GET)
    public Object getFileHeader(@PathVariable String file_id,
                                @RequestParam(value = "destinationFormat", required = false, defaultValue="aes128") String destinationFormat,
                                @RequestParam(value = "destinationKey", required = false, defaultValue = "") String destinationKey) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        return fileService.getFileHeader(auth, 
                                         file_id,
                                         destinationFormat,
                                         destinationKey);
        
    }
    
    // {id} -- 'file', 'sample', 'run', ...
    @RequestMapping(value = "/byid/{id}", method = GET)
    @ResponseBody
    public void getById(@PathVariable String idType,
                        @RequestParam(value = "accession", required = true) String accession,
                        @RequestParam(value = "format", required = false, defaultValue = "bam") String format,
                        @RequestParam(value = "chr", required = false, defaultValue = "") String reference,
                        @RequestParam(value = "start", required = false, defaultValue = "0") long start,
                        @RequestParam(value = "end", required = false, defaultValue = "0") long end, 
                        @RequestParam(value = "destinationFormat", required = false, defaultValue="aes128") String destinationFormat,
                        @RequestParam(value = "destinationKey", required = false, defaultValue = "") String destinationKey,
                        HttpServletRequest request,
                        HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth==null) {
            throw new InvalidAuthenticationException(accession);
        }
            
        // Basic Parameter Validation
        validateFormat(format);
        
        fileService.getById(auth,
                            idType, 
                            accession, 
                            format, 
                            reference,
                            start, 
                            end, 
                            destinationFormat,
                            destinationKey,
                            request, 
                            response);
    }

    // Ensure BAM/CRAM & General Parameters
    private void validateFormat(String format) {
        if  (!((format.equalsIgnoreCase("bam") || format.equalsIgnoreCase("cram")))) {
            throw new UnsupportedFormatException(format);            
        }
        
        // TODO ... Other Validations according to API Specs
    }
    
    
}
